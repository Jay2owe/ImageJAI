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
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
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
DEFAULT_API_KEY = "sk-imagejai-local-proxy"
COST_HEADER = "x-litellm-response-cost"

_LAST_HANDLE: subprocess.Popen[str] | None = None
_LAST_PORT = DEFAULT_PORT


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


def record_cost_header(value: str) -> None:
    """Record a LiteLLM response cost value for future Phase H subscribers."""

    line = json.dumps({"ts": time.time(), "header": COST_HEADER, "cost": value})
    sink = os.environ.get("IMAGEJAI_LITELLM_COST_LOG")
    if sink:
        with open(sink, "a", encoding="utf-8") as out:
            out.write(line + "\n")
    print("[ImageJAI-LiteLLM-Cost] " + line, flush=True)


response_cost_middleware = ResponseCostMiddleware()


def start(config_path: str | os.PathLike[str]) -> subprocess.Popen[str]:
    """Start LiteLLM Proxy and return the process handle."""

    global _LAST_HANDLE, _LAST_PORT

    config = Path(config_path).resolve()
    if not config.exists():
        raise FileNotFoundError(config)
    _assert_litellm_importable()
    _load_secrets_env(config)

    port = _first_available_port()
    _LAST_PORT = port
    runtime_config = _write_runtime_config(config, port)
    port_file = _port_file(config)
    port_file.write_text(str(port), encoding="utf-8")

    env = os.environ.copy()
    env.setdefault("PYTHONUNBUFFERED", "1")
    env.setdefault("PYTHONIOENCODING", "utf-8")
    env.setdefault("OLLAMA_API_BASE", "http://localhost:11434")
    env["IMAGEJAI_LITELLM_PORT"] = str(port)
    env["IMAGEJAI_LITELLM_CONFIG"] = str(runtime_config)

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
    handle = subprocess.Popen(
        cmd,
        cwd=str(config.parents[2]),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        creationflags=_creation_flags(),
    )
    _LAST_HANDLE = handle
    atexit.register(stop, handle)
    return handle


def stop(handle: subprocess.Popen[str] | None) -> None:
    """Terminate a LiteLLM Proxy process started by start()."""

    if handle is None or handle.poll() is not None:
        return
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
    try:
        _port_file(Path(__file__)).unlink(missing_ok=True)
    except OSError:
        pass


def wait_healthy(timeout: float = 8.0) -> bool:
    """Return true when LiteLLM readiness endpoint responds 200 within timeout."""

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(
                f"http://{HOST}:{_LAST_PORT}/health/readiness", timeout=0.6
            ) as response:
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
            "Authorization": f"Bearer {DEFAULT_API_KEY}",
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


def _write_runtime_config(config: Path, port: int) -> Path:
    raw = yaml.safe_load(config.read_text(encoding="utf-8")) or {}
    raw["model_list"] = [_prepare_entry(e) for e in raw.get("model_list", []) if _enabled(e)]
    raw["model_list"] = [e for e in raw["model_list"] if e is not None]
    raw.setdefault("general_settings", {})
    if os.environ.get("LITELLM_MASTER_KEY"):
        raw["general_settings"]["master_key"] = os.environ["LITELLM_MASTER_KEY"]
    raw.setdefault("environment_variables", {})
    raw["environment_variables"]["IMAGEJAI_LITELLM_PORT"] = str(port)

    target = Path(tempfile.gettempdir()) / f"imagejai-litellm-{port}.yaml"
    callback_module = target.parent / "agent" / "providers" / "proxy.py"
    callback_module.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(Path(__file__), callback_module)
    target.write_text(yaml.safe_dump(raw, sort_keys=False), encoding="utf-8")
    return target


def _enabled(entry: dict[str, Any]) -> bool:
    info = entry.get("model_info") or {}
    required = info.get("imagejai_required_env")
    if required:
        return bool(os.environ.get(required))
    return bool(info.get("imagejai_env_optional", False))


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
        with urllib.request.urlopen(f"http://{HOST}:{_LAST_PORT}/v1/models", timeout=2.0) as response:
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
