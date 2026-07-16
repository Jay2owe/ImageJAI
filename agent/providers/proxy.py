"""Start and supervise ImageJAI's bundled LiteLLM Proxy sidecar.

The public API is intentionally small:

    start(config_path) -> subprocess.Popen
    stop(handle) -> None
    wait_healthy(timeout=8.0) -> bool

The shipped config contains placeholders for every supported provider. Before
launch, this module writes a filtered runtime config that keeps only entries
whose required environment variables are present, plus Ollama Local entries so
the proxy can boot with no cloud keys configured.
"""
from __future__ import annotations

import argparse
import atexit
import json
import os
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError as exc:  # pragma: no cover - exercised by manual install state
    raise RuntimeError(
        "PyYAML is required. Install with: pip install -r agent/providers/requirements.txt"
    ) from exc

try:
    from litellm.integrations.custom_logger import CustomLogger
except Exception:  # pragma: no cover - litellm may not be installed yet
    class CustomLogger:  # type: ignore[no-redef]
        pass


HOST = "127.0.0.1"
DEFAULT_PORT = 4000
PORT_RANGE = range(4000, 4011)
DEFAULT_API_KEY = ""  # Runtime keys are generated per sidecar start.
COST_HEADER = "x-litellm-response-cost"
BILLING_STATUSES = frozenset({401, 402, 429})

_LAST_HANDLE: subprocess.Popen[str] | None = None
_LAST_PORT = DEFAULT_PORT
_LAST_MASTER_KEY = ""
_LAST_RUNTIME_DIR: Path | None = None
_LAST_AUTH_FILE: Path | None = None
_LAST_STATE_FILE: Path | None = None
_LAST_PORT_FILE: Path | None = None


class ResponseCostMiddleware(CustomLogger):
    """Records LiteLLM response-cost values for Phase H subscribers.

    LiteLLM loads the module-level ``response_cost_middleware`` instance from
    litellm.config.yaml as a custom callback. The optional ASGI wrapper support
    keeps the class usable as middleware if LiteLLM exposes direct middleware
    registration in a future release.
    """

    def __init__(self, app: Any | None = None, sink_path: str | os.PathLike[str] | None = None):
        self.app = app
        self.sink_path = Path(
            sink_path or os.environ.get("IMAGEJAI_LITELLM_COST_LOG", "")
        ) if (sink_path or os.environ.get("IMAGEJAI_LITELLM_COST_LOG")) else None

    async def __call__(self, scope: dict[str, Any], receive: Any, send: Any) -> None:
        async def send_wrapper(message: dict[str, Any]) -> None:
            if message.get("type") == "http.response.start":
                for key, value in message.get("headers", []):
                    if key.decode("latin-1").lower() == COST_HEADER:
                        cost = value.decode("latin-1")
                        record_cost_header(cost)
                        break
            await send(message)

        await self.app(scope, receive, send_wrapper)

    async def async_log_success_event(
        self,
        kwargs: dict[str, Any],
        response_obj: Any,
        start_time: float,
        end_time: float,
    ) -> None:
        cost = kwargs.get("response_cost")
        if cost is None:
            hidden = getattr(response_obj, "_hidden_params", None) or {}
            cost = hidden.get("response_cost")
        if cost is not None:
            record_cost_header(str(cost))

    async def async_log_failure_event(
        self,
        kwargs: dict[str, Any],
        response_obj: Any,
        start_time: float,
        end_time: float,
    ) -> None:
        """Surface upstream auth/billing failures (401/402/429) to Phase H.

        LiteLLM calls this on the same callback instance as the success event
        when an upstream provider rejects the request. Only billing-class
        statuses are forwarded; transient 5xx and validation 4xx are left to
        the agent loop's own retry handling.
        """

        status, provider, message = _extract_failure(kwargs, response_obj)
        if status in BILLING_STATUSES:
            record_billing_failure(provider, status, message)


def record_cost_header(value: str) -> None:
    """Record a LiteLLM response cost value for future Phase H subscribers."""

    line = json.dumps({"ts": time.time(), "header": COST_HEADER, "cost": value})
    sink = os.environ.get("IMAGEJAI_LITELLM_COST_LOG")
    if sink:
        with open(sink, "a", encoding="utf-8") as out:
            out.write(line + "\n")
    print("[ImageJAI-LiteLLM-Cost] " + line, flush=True)


def record_billing_failure(provider: str | None, status: int, message: str | None) -> None:
    """Emit a billing/auth-failure sentinel the Java side surfaces as a dialog.

    Mirrors record_cost_header's stdout channel so LiteLlmProxyService can parse
    the line from the sidecar log stream (docs/multi_provider/06 §3.7). The
    message is truncated to keep a verbose provider HTML error from bloating the
    log line.
    """

    payload = {
        "ts": time.time(),
        "event": "billing",
        "provider": provider or "",
        "status": int(status),
        "message": (message or "")[:240],
    }
    line = json.dumps(payload)
    sink = os.environ.get("IMAGEJAI_LITELLM_COST_LOG")
    if sink:
        with open(sink, "a", encoding="utf-8") as out:
            out.write(line + "\n")
    print("[ImageJAI-LiteLLM-Billing] " + line, flush=True)


def _extract_failure(kwargs: Any, response_obj: Any) -> tuple[int | None, str | None, str | None]:
    """Pull (status, provider, message) out of a LiteLLM failure event.

    Defensive against the several shapes LiteLLM passes failures in across
    versions: an exception carrying ``status_code``, a response object, or only
    a message string with the status embedded.
    """

    exception = kwargs.get("exception") if isinstance(kwargs, dict) else None
    status = _status_code(exception)
    if status is None:
        status = _status_code(response_obj)
    message: str | None = None
    if exception is not None:
        message = getattr(exception, "message", None) or str(exception)
    elif response_obj is not None:
        message = str(response_obj)
    if status is None and message:
        # Word-boundary match so a code embedded in a larger number (e.g. a
        # request id like "req_4021") does not get misread as 402.
        match = re.search(r"\b(401|402|429)\b", message)
        if match:
            status = int(match.group(1))
    provider: str | None = None
    if isinstance(kwargs, dict):
        provider = kwargs.get("custom_llm_provider") or _provider_from_model(kwargs.get("model"))
    return status, provider, message


def _status_code(obj: Any) -> int | None:
    if obj is None:
        return None
    value = getattr(obj, "status_code", None)
    if value is None:
        value = getattr(obj, "code", None)
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _provider_from_model(model: Any) -> str | None:
    if isinstance(model, str) and "/" in model:
        return model.split("/", 1)[0]
    return None


response_cost_middleware = ResponseCostMiddleware()


def start(config_path: str | os.PathLike[str]) -> subprocess.Popen[str]:
    """Start LiteLLM Proxy and return the process handle."""

    global _LAST_AUTH_FILE, _LAST_HANDLE, _LAST_MASTER_KEY, _LAST_PORT
    global _LAST_PORT_FILE, _LAST_RUNTIME_DIR, _LAST_STATE_FILE

    config = Path(config_path).resolve()
    if not config.exists():
        raise FileNotFoundError(config)
    _assert_litellm_importable()
    _load_secrets_env(config)

    port_file = _port_file(config)

    with _port_file_lock(port_file):
        port = _first_available_port()
        _LAST_PORT = port
        master_key = os.environ.get("LITELLM_MASTER_KEY", "").strip()
        if not master_key:
            master_key = "sk-imagejai-" + secrets.token_urlsafe(32)
        runtime_dir = _create_private_runtime_dir()
        auth_file = config.with_name("proxy.auth")
        state_file = config.with_name("proxy.runtime.json")
        runtime_config = _write_runtime_config(config, port, runtime_dir, master_key)
        _write_private_file(auth_file, master_key + "\n")
        _write_private_file(
            state_file,
            json.dumps(
                {
                    "port": port,
                    "runtime_dir": str(runtime_dir),
                    "auth_file": str(auth_file),
                    "runtime_config": str(runtime_config),
                },
                separators=(",", ":"),
            )
            + "\n",
        )
        _write_port_file_atomic(port_file, port)
        _LAST_MASTER_KEY = master_key
        _LAST_RUNTIME_DIR = runtime_dir
        _LAST_AUTH_FILE = auth_file
        _LAST_STATE_FILE = state_file
        _LAST_PORT_FILE = port_file

        env = os.environ.copy()
        env.setdefault("PYTHONUNBUFFERED", "1")
        env.setdefault("PYTHONIOENCODING", "utf-8")
        env.setdefault("OLLAMA_API_BASE", "http://localhost:11434")
        env["IMAGEJAI_LITELLM_PORT"] = str(port)
        env["IMAGEJAI_LITELLM_CONFIG"] = str(runtime_config)
        env["IMAGEJAI_LITELLM_MASTER_KEY"] = master_key
        env["LITELLM_MASTER_KEY"] = master_key

        cmd = [
            sys.executable,
            "-c",
            "import litellm, sys; sys.exit(litellm.run_server())",
            "--config",
            str(runtime_config),
            "--host",
            HOST,
            "--port",
            str(port),
        ]
        try:
            handle = subprocess.Popen(
                cmd,
                cwd=str(config.parents[2]),
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                creationflags=_creation_flags(),
            )
        except Exception:
            _remove_port_file_if_owned(port_file, port)
            _remove_private_file_if_owned(auth_file, master_key)
            _remove_runtime_state_if_owned(state_file, runtime_dir)
            shutil.rmtree(runtime_dir, ignore_errors=True)
            raise
        # The LiteLLM child runs our ResponseCostMiddleware, which prints the
        # `[ImageJAI-LiteLLM-Cost]` / `[ImageJAI-LiteLLM-Billing]` sentinels to
        # *its* stdout. Drain that pipe and forward it to our own stdout so the
        # Java LiteLlmProxyService log pump actually sees them — and so the
        # child never blocks on a full pipe buffer.
        _start_output_forwarder(handle)
        _wait_for_port_bind(port, handle)
    _LAST_HANDLE = handle
    atexit.register(stop, handle)
    return handle


def _start_output_forwarder(handle: subprocess.Popen[str]) -> threading.Thread | None:
    """Forward the LiteLLM child's stdout/stderr to this process's stdout.

    Without this the child pipe is never read: the cost/billing sentinels its
    callback prints are lost (they never reach Java's pump) and the child can
    eventually block when the OS pipe buffer fills. Runs as a daemon so it
    never keeps the interpreter alive past shutdown.
    """

    stream = handle.stdout
    if stream is None:
        return None

    def _pump() -> None:
        try:
            for line in iter(stream.readline, ""):
                if not line:
                    break
                sys.stdout.write(line if line.endswith("\n") else line + "\n")
                sys.stdout.flush()
        except (ValueError, OSError):
            # Stream closed during shutdown — nothing to forward.
            pass

    thread = threading.Thread(target=_pump, name="litellm-stdout-forward", daemon=True)
    thread.start()
    return thread


def stop(handle: subprocess.Popen[str] | None) -> None:
    """Terminate a LiteLLM Proxy process started by start()."""

    try:
        if handle is not None and handle.poll() is None:
            if os.name == "nt":
                subprocess.run(
                    ["taskkill", "/PID", str(handle.pid), "/T", "/F"],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    check=False,
                )
            else:
                handle.send_signal(signal.SIGTERM)
                try:
                    handle.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    handle.kill()
    finally:
        _remove_port_file_if_owned(_LAST_PORT_FILE or _port_file(Path(__file__)), _LAST_PORT)
        _remove_private_file_if_owned(_LAST_AUTH_FILE, _LAST_MASTER_KEY)
        _remove_runtime_state_if_owned(_LAST_STATE_FILE, _LAST_RUNTIME_DIR)
        if _LAST_RUNTIME_DIR is not None:
            shutil.rmtree(_LAST_RUNTIME_DIR, ignore_errors=True)


def wait_healthy(timeout: float = 8.0) -> bool:
    """Return true when LiteLLM readiness endpoint responds 200 within timeout."""

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            request = urllib.request.Request(
                f"http://{HOST}:{_LAST_PORT}/health/readiness",
                headers=_proxy_auth_headers(),
            )
            with urllib.request.urlopen(request, timeout=0.6) as response:
                if response.status == 200:
                    return True
        except (OSError, urllib.error.URLError):
            time.sleep(0.25)
    return False


def startup_self_test() -> bool:
    """Verify the proxy is up and cost-header plumbing is observable.

    If Ollama has a reachable local model, this sends a tiny chat and asserts
    the LiteLLM response-cost header exists. With no Ollama listener, it records
    a zero-cost no-op header so Phase A can boot cleanly on fresh machines.
    """

    local_ollama_models = _ollama_models()
    if not local_ollama_models:
        record_cost_header("0")
        return True
    models = _proxy_models()
    models = [model for model in models if model.removeprefix("ollama/") in local_ollama_models]
    if not models:
        record_cost_header("0")
        return True
    payload = json.dumps({
        "model": models[0],
        "messages": [{"role": "user", "content": "Reply with OK."}],
        "max_tokens": 4,
        "stream": False,
    }).encode("utf-8")
    request = urllib.request.Request(
        f"http://{HOST}:{_LAST_PORT}/v1/chat/completions",
        data=payload,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {_LAST_MASTER_KEY}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=8.0) as response:
            cost = response.headers.get(COST_HEADER)
            if not cost:
                raise RuntimeError(f"missing {COST_HEADER} on LiteLLM response")
            record_cost_header(cost)
            return True
    except TimeoutError:
        record_cost_header("0")
        return True
    except urllib.error.HTTPError as exc:
        if exc.code in (400, 404, 424, 500, 502, 503):
            record_cost_header("0")
            return True
        raise


def _load_secrets_env(config_path: Path) -> dict[str, str]:
    """Read every <provider>.env from the secrets directories into os.environ.

    Two locations are checked in order — values from the second override the
    first when the same key appears in both:

      1. ``agent/providers/.secrets/`` next to ``litellm.config.yaml`` —
         development-tree convenience (gitignored).
      2. ``~/.imagej-ai/secrets/`` — canonical per-machine store written by
         the Java MultiProviderPanel installer wizards.

    Returns the merged set of key/value pairs that were applied so callers
    can log or assert on what was loaded.
    """

    applied: dict[str, str] = {}
    candidates: list[Path] = []
    repo_secrets = config_path.parent / ".secrets"
    if repo_secrets.is_dir():
        candidates.append(repo_secrets)
    user_secrets = Path.home() / ".imagej-ai" / "secrets"
    if user_secrets.is_dir():
        candidates.append(user_secrets)
    override = os.environ.get("IMAGEJAI_SECRETS_DIR")
    if override:
        override_path = Path(override)
        if override_path.is_dir():
            candidates.append(override_path)

    for directory in candidates:
        for env_file in sorted(directory.glob("*.env")):
            try:
                contents = env_file.read_text(encoding="utf-8")
            except OSError:
                continue
            for raw in contents.splitlines():
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                key, _, value = line.partition("=")
                key = key.strip()
                value = value.strip().strip("\"").strip("'")
                if not key:
                    continue
                os.environ[key] = value
                applied[key] = value
    return applied


def _assert_litellm_importable() -> None:
    try:
        import litellm  # noqa: F401
    except ImportError as exc:
        raise RuntimeError(
            "LiteLLM is not importable. Run: pip install -r agent/providers/requirements.txt"
        ) from exc


def _first_available_port() -> int:
    for port in PORT_RANGE:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(0.2)
            if probe.connect_ex((HOST, port)) != 0:
                return port
    raise RuntimeError("no free LiteLLM proxy port in 4000..4010")


def _create_private_runtime_dir() -> Path:
    path = Path(tempfile.mkdtemp(prefix="imagejai-litellm-"))
    try:
        path.chmod(0o700)
        _restrict_to_current_user(path, directory=True)
    except OSError:
        shutil.rmtree(path, ignore_errors=True)
        raise
    return path


def _write_private_file(path: Path, content: str) -> None:
    """Atomically publish a user-private runtime file."""

    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    temporary_path = Path(temporary)
    try:
        try:
            os.fchmod(fd, 0o600)
        except (AttributeError, OSError):
            pass
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        temporary_path.chmod(0o600)
        _restrict_to_current_user(temporary_path, directory=False)
        os.replace(temporary_path, path)
        path.chmod(0o600)
    except Exception:
        try:
            os.close(fd)
        except OSError:
            pass
        temporary_path.unlink(missing_ok=True)
        raise


def _restrict_to_current_user(path: Path, *, directory: bool) -> None:
    """Apply a real user-only ACL on Windows; POSIX uses chmod above."""

    if os.name != "nt":
        return
    username = os.environ.get("USERNAME", "").strip()
    domain = os.environ.get("USERDOMAIN", "").strip()
    if not username:
        raise OSError("cannot determine current Windows user for private runtime ACL")
    principal = f"{domain}\\{username}" if domain else username
    permission = "(OI)(CI)F" if directory else "F"
    completed = subprocess.run(
        [
            "icacls",
            str(path),
            "/inheritance:r",
            "/grant:r",
            f"{principal}:{permission}",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if completed.returncode != 0:
        raise OSError(f"failed to secure LiteLLM runtime ACL for {path}")


def _proxy_auth_headers() -> dict[str, str]:
    return (
        {"Authorization": f"Bearer {_LAST_MASTER_KEY}"}
        if _LAST_MASTER_KEY
        else {}
    )


def _remove_private_file_if_owned(path: Path | None, expected: str) -> None:
    if path is None or not expected:
        return
    try:
        if path.read_text(encoding="utf-8").strip() == expected:
            path.unlink()
    except OSError:
        pass


def _remove_runtime_state_if_owned(path: Path | None, runtime_dir: Path | None) -> None:
    if path is None or runtime_dir is None:
        return
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        if Path(str(payload.get("runtime_dir", ""))).resolve() == runtime_dir.resolve():
            path.unlink()
    except (OSError, ValueError, TypeError):
        pass


def _write_runtime_config(
    config: Path,
    port: int,
    runtime_dir: Path | None = None,
    master_key: str | None = None,
) -> Path:
    raw = yaml.safe_load(config.read_text(encoding="utf-8")) or {}
    raw["model_list"] = [_prepare_entry(e) for e in raw.get("model_list", []) if _enabled(e)]
    raw["model_list"] = [e for e in raw["model_list"] if e is not None]
    raw.setdefault("general_settings", {})
    key = str(master_key or os.environ.get("LITELLM_MASTER_KEY", "")).strip()
    if not key:
        raise RuntimeError("LiteLLM master key is required")
    raw["general_settings"]["master_key"] = key
    raw.setdefault("environment_variables", {})
    raw["environment_variables"]["IMAGEJAI_LITELLM_PORT"] = str(port)

    private_dir = runtime_dir or _create_private_runtime_dir()
    target = private_dir / "litellm.runtime.yaml"
    callback_module = target.parent / "agent" / "providers" / "proxy.py"
    callback_module.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(Path(__file__), callback_module)
    _write_private_file(target, yaml.safe_dump(raw, sort_keys=False))
    return target


def _enabled(entry: dict[str, Any]) -> bool:
    info = entry.get("model_info") or {}
    # Keyless backends (local daemons such as Ollama local + cloud) are always
    # enabled — they run without a configured API key. A key, if present, is an
    # optional override, never a precondition, so this wins over required_env.
    if info.get("imagejai_env_optional", False):
        return True
    required = info.get("imagejai_required_env")
    if required:
        return bool(os.environ.get(required))
    return False


def _prepare_entry(entry: dict[str, Any]) -> dict[str, Any]:
    copied = json.loads(json.dumps(entry))
    info = copied.get("model_info") or {}
    params = copied.get("litellm_params") or {}
    default_base = info.get("imagejai_default_api_base")
    if default_base and str(params.get("api_base", "")).startswith("os.environ/"):
        env_name = params["api_base"].split("/", 1)[1]
        params["api_base"] = os.environ.get(env_name, default_base)
    copied["litellm_params"] = params
    return copied


def _proxy_models() -> list[str]:
    try:
        request = urllib.request.Request(
            f"http://{HOST}:{_LAST_PORT}/v1/models",
            headers=_proxy_auth_headers(),
        )
        with urllib.request.urlopen(request, timeout=2.0) as response:
            payload = json.loads(response.read().decode("utf-8"))
        return [item["id"] for item in payload.get("data", []) if item.get("id", "").startswith("ollama/")]
    except (OSError, urllib.error.URLError, json.JSONDecodeError):
        return []


def _ollama_models() -> set[str]:
    try:
        with urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=1.0) as response:
            payload = json.loads(response.read().decode("utf-8"))
        return {item.get("name", "") for item in payload.get("models", [])}
    except (OSError, urllib.error.URLError, json.JSONDecodeError):
        return set()


def _port_file(config_path: Path) -> Path:
    if config_path.name == "proxy.py":
        return Path(__file__).with_name("proxy.port")
    return config_path.with_name("proxy.port")


class _PortFileLock:
    def __init__(self, port_file: Path):
        self.path = port_file.with_name(port_file.name + ".lock")
        self._fh: Any | None = None

    def __enter__(self) -> "_PortFileLock":
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._fh = open(self.path, "a+b")
        self._fh.seek(0)
        if os.name == "nt":
            if self._fh.read(1) == b"":
                self._fh.write(b"\0")
                self._fh.flush()
            self._fh.seek(0)
            import msvcrt

            msvcrt.locking(self._fh.fileno(), msvcrt.LK_LOCK, 1)
        else:
            import fcntl

            fcntl.flock(self._fh.fileno(), fcntl.LOCK_EX)
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        if self._fh is None:
            return
        try:
            self._fh.seek(0)
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(self._fh.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                fcntl.flock(self._fh.fileno(), fcntl.LOCK_UN)
        finally:
            self._fh.close()
            self._fh = None


def _port_file_lock(port_file: Path) -> _PortFileLock:
    return _PortFileLock(port_file)


def _write_port_file_atomic(port_file: Path, port: int) -> None:
    port_file.parent.mkdir(parents=True, exist_ok=True)
    tmp = port_file.with_name(f"{port_file.name}.{os.getpid()}.tmp")
    tmp.write_text(str(port), encoding="utf-8")
    os.replace(tmp, port_file)
    observed = port_file.read_text(encoding="utf-8").strip()
    if observed != str(port):
        raise RuntimeError(
            f"proxy port publication race: wrote {port}, read back {observed!r}"
        )


def _wait_for_port_bind(
    port: int, handle: subprocess.Popen[str], timeout: float = 8.0
) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if handle.poll() is not None:
            return False
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(0.2)
            if probe.connect_ex((HOST, port)) == 0:
                return True
        time.sleep(0.1)
    return False


def _remove_port_file_if_owned(port_file: Path, port: int) -> None:
    try:
        with _port_file_lock(port_file):
            if not port_file.exists():
                return
            if port_file.read_text(encoding="utf-8").strip() == str(port):
                port_file.unlink()
    except OSError:
        pass


def _creation_flags() -> int:
    if os.name != "nt":
        return 0
    return getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)


def _default_config() -> Path:
    return Path(__file__).with_name("litellm.config.yaml")


def _main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="ImageJAI LiteLLM proxy sidecar")
    parser.add_argument("--config", default=str(_default_config()))
    parser.add_argument("--start", action="store_true", help="start and wait until interrupted")
    parser.add_argument("--smoke", action="store_true", help="start, wait healthy, then stop")
    parser.add_argument("--timeout", type=float, default=30.0, help="CLI startup timeout in seconds")
    args = parser.parse_args(argv)

    handle = start(args.config)
    try:
        if not wait_healthy(args.timeout):
            raise RuntimeError(
                f"LiteLLM proxy did not become healthy within {args.timeout:g}s"
            )
        startup_self_test()
        print(f"[ImageJAI-LiteLLM] ready on http://{HOST}:{_LAST_PORT}", flush=True)
        if args.smoke:
            return 0
        if args.start or not args.smoke:
            while handle.poll() is None:
                time.sleep(1)
            return handle.returncode or 0
        return 0
    finally:
        if args.smoke:
            stop(handle)


if __name__ == "__main__":
    raise SystemExit(_main())
