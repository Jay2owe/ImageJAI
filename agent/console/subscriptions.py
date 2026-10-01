"""Adapters for vendor command-line clients authenticated by a subscription."""
from __future__ import annotations

import json
import base64
import os
import queue
import signal
import shutil
import subprocess
import tempfile
import threading
import uuid
from pathlib import Path
from typing import Any, Callable

from .agent_loop import AbortFlag, TurnCallbacks, MAX_TOOL_ROUNDS, _import_registry
from .workspace import ensure_importable

from .installed_agents import CLI_PROVIDERS

SUBSCRIPTION_PROVIDERS = frozenset({"codex-subscription", "claude-subscription"}) | CLI_PROVIDERS

# Launcher pass-through arguments, set by ``imagejai --<flag>`` and read here so
# the agent factory does not have to thread them through every call site.
_EXTRA_ARGS_ENV = "IMAGEJAI_VENDOR_ARGS"


def set_vendor_extra_args(args: "list[str] | None") -> None:
    """Record vendor CLI flags for this process (see __main__.parse_args)."""
    if args:
        os.environ[_EXTRA_ARGS_ENV] = json.dumps(list(args))
    else:
        os.environ.pop(_EXTRA_ARGS_ENV, None)


def vendor_extra_args(provider: str = "") -> list[str]:
    """Flags the user asked the vendor client to receive, or an empty list."""
    raw = os.environ.get(_EXTRA_ARGS_ENV, "")
    if not raw:
        return []
    try:
        values = json.loads(raw)
    except ValueError:
        return []
    return [str(v) for v in values if isinstance(v, (str, int, float))]

_COMMANDS = {
    "codex-subscription": ("codex", "codex.cmd"),
    "claude-subscription": ("claude", "claude.exe"),
}


def _executable(provider: str) -> str | None:
    if provider in CLI_PROVIDERS:
        from .installed_agents import executable
        return executable(provider)
    for name in _COMMANDS.get(provider, ()):
        found = shutil.which(name)
        if found:
            return found
    return None


def subscription_status(provider: str) -> tuple[bool, str]:
    executable = _executable(provider)
    if not executable:
        return False, "official command-line client is not installed"
    if provider in CLI_PROVIDERS:
        return True, "installed; uses the program's own login (checked on first request)"
    if provider == "codex-subscription":
        command = [executable, "login", "status"]
    else:
        command = [executable, "auth", "status"]
    completed = subprocess.run(
        command, capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=20,
    )
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout).strip()
        return False, detail or "not logged in"
    if provider == "claude-subscription":
        try:
            if not json.loads(completed.stdout).get("loggedIn"):
                return False, "not logged in"
        except (ValueError, AttributeError):
            pass
    return True, "subscription login ready"


def run_subscription_login(provider: str) -> int:
    if provider in CLI_PROVIDERS:
        raise RuntimeError("Open this program in a terminal to complete its own login")
    executable = _executable(provider)
    if not executable:
        raise RuntimeError("official command-line client is not installed")
    command = (
        [executable, "login"]
        if provider == "codex-subscription"
        else [executable, "auth", "login"]
    )
    return subprocess.call(command)


class SubscriptionAgent:
    """Console turn interface backed by an authenticated vendor CLI."""

    def __init__(
        self, provider: str, model: str = "default",
        external_session_id: str | None = None,
        extra_args: "list[str] | None" = None,
        effort: str = "default",
        resume_vendor_latest: bool = False,
    ) -> None:
        if provider not in SUBSCRIPTION_PROVIDERS:
            raise ValueError(f"unsupported subscription provider: {provider}")
        executable = _executable(provider)
        if not executable:
            raise RuntimeError("official command-line client is not installed")
        ok, message = subscription_status(provider)
        if not ok:
            short = "codex" if provider.startswith("codex") else "claude"
            raise RuntimeError(f"{message}; run `imagejai login {short}`")
        self.provider = provider
        self.model = model
        from .model_choice import normalise_effort
        self.effort = normalise_effort(provider, model, effort)
        self.executable = executable
        self.external_session_id = external_session_id
        if resume_vendor_latest and external_session_id:
            raise ValueError("latest resume cannot also name a vendor session")
        self.resume_vendor_latest = bool(resume_vendor_latest)
        # Pass-through flags from the launcher, e.g.
        #   imagejai --claude-opus-5 --dangerously-skip-permissions
        # The console does not interpret them; the vendor client does.
        self.extra_args = list(extra_args or vendor_extra_args(provider))
        self.messages: list[dict[str, Any]] = []
        self.tools, self.host_code_tools = _import_registry()
        if not self.tools:
            raise RuntimeError("Fiji tool catalogue could not be loaded")
        self.fiji_connection = None
        self.tool_result_filter = None
        self.action_receipts: dict = {}
        self._harness_digest = ""
        self._skill_catalog = ""
        self._active_skill = ""
        self._project_instructions = ""
        self._instruction_tools = ()
        self._tool_instructions = ""
        self._image_policy = False
        self._image_policy_reason = "Image sharing has not been enabled"
        self._pending_image = None
        self.abort = AbortFlag()
        self._last_usage_payload = {}

    def set_fiji_connection(self, connection) -> None:
        self.fiji_connection = connection

    def set_tool_result_filter(self, scrubber) -> None:
        self.tool_result_filter = scrubber

    def set_image_policy(self, allowed: bool, reason: str = "") -> None:
        self._image_policy = bool(allowed)
        self._image_policy_reason = str(reason or "")

    def set_harness_digest(self, digest: str | None) -> None:
        self._harness_digest = str(digest or "").strip()

    def set_project_instructions(self, text: str) -> None:
        self._project_instructions = text

    def set_skill_catalog(self, catalog: str | None) -> None:
        self._skill_catalog = str(catalog or "").strip()

    def set_active_skill(self, name: str | None, body: str | None = None) -> None:
        self._active_skill = "" if not name else f"name: {name}\n{str(body or '').strip()}"

    def _wrapped_prompt(self, prompt: str) -> str:
        from .wrapped_tools import instructions
        tools = tuple(self.tools)
        if tools != self._instruction_tools:
            self._tool_instructions = instructions(self)
            self._instruction_tools = tools
        parts = [self._tool_instructions, self._skill_catalog, self._active_skill,
                 self._harness_digest, getattr(self, "_project_instructions", ""), "Conversation input:\n" + prompt]
        return "\n\n".join(part for part in parts if part)

    def _tool_by_name(self, name: str):
        return next((fn for fn in self.tools if fn.__name__ == name), None)

    def _execute_tool(self, name: str, args: dict) -> tuple[bool, str]:
        from .wrapped_tools import execute_function
        return execute_function(self, name, args)

    def turn(self, user_text: str, cb: TurnCallbacks) -> bool:
        from .wrapped_tools import (ActionDisplay, ActionError, execute_action,
                                    parse_action, result_message, capture_attachment)
        cb.on_user(user_text)
        self.messages.append({"role": "user", "content": user_text})
        self._last_streamed_assistant = ""
        self._pending_image = None
        try:
            prompt = user_text
            for _round in range(MAX_TOOL_ROUNDS):
                from .turn_queue import inject_steering
                corrections = inject_steering(self, cb)
                if corrections:
                    prompt += "\n\nUser correction:\n" + "\n".join(corrections)
                if self.abort.set_flag:
                    cb.on_done(False)
                    return False
                display = ActionDisplay(cb)
                from .usage import model_request
                self._last_usage_payload = {}
                self._last_input_messages = self.messages
                with model_request(self, self.messages, cb) as request:
                    try:
                        if self.provider == "codex-subscription":
                            text = self._codex_turn(prompt, display.callbacks())
                        elif self.provider == "claude-subscription":
                            text = self._claude_turn(prompt, display.callbacks())
                        else:
                            text = self._installed_turn(prompt, display.callbacks())
                        request.text = text
                    finally:
                        request.reply = self._last_usage_payload
                        request.messages = self._last_input_messages
                if self.abort.set_flag:
                    cb.on_done(False)
                    return False
                self.messages.append({"role": "assistant", "content": text})
                try:
                    request = parse_action(text)
                except ActionError as exc:
                    prompt = f"ImageJAI action rejected without execution: {exc}. Send a corrected action message."
                    self.messages.append({"role": "user", "content": prompt})
                    continue
                if request is None:
                    if not text or text.strip() != self._last_streamed_assistant:
                        display.assistant(text or "(no reply)")
                    corrections = inject_steering(self, cb)
                    if corrections:
                        prompt = "User correction:\n" + "\n".join(corrections)
                        continue
                    cb.on_done(True)
                    return True
                if not self.external_session_id:
                    raise RuntimeError("Agent did not identify its conversation; Fiji action was not executed")
                corrections = inject_steering(self, cb)
                if corrections:
                    receipt = {**request.as_dict(), "ok": False, "result": "User correction received; this action was not executed."}
                    cb.on_tool_record(request.id, request.tool, request.arguments, False, receipt["result"])
                else:
                    receipt = execute_action(self, request, cb)
                self._pending_image = capture_attachment(self, request, receipt)
                message = result_message(receipt)
                self.messages.append(message)
                prompt = message["content"]
                if corrections:
                    prompt += "\n\nUser correction:\n" + "\n".join(corrections)
            cb.on_error(f"stopped after {MAX_TOOL_ROUNDS} tool rounds")
            cb.on_done(False)
            return False
        except Exception as exc:
            from .usage import ModelCallStopped
            if isinstance(exc, ModelCallStopped):
                cb.on_done(False)
                return False
            if self.abort.set_flag:
                cb.on_done(False)
                return False
            cb.on_error(f"subscription agent failed: {exc}")
            cb.on_done(False)
            return False

    def _installed_turn(self, prompt: str, cb: TurnCallbacks) -> str:
        from .installed_agents import AgentOutput, command, aider_response
        context = json.dumps(self.messages, ensure_ascii=False)
        payload = self._wrapped_prompt("Saved conversation (including the current input):\n" + context)
        self._last_input_messages = [{"role": "user", "content": payload}]
        output = AgentOutput(self.provider, cb)
        self.external_session_id = self.external_session_id or "console-context:" + str(uuid.uuid4())
        self.resume_vendor_latest = False
        if self._pending_image is not None:
            raise RuntimeError("This command-line adapter cannot attach images; choose an API, Codex or Claude route for image sharing")
        with tempfile.TemporaryDirectory(prefix="imagejai-agent-") as tmp:
            argv, stdin = command(self.provider, self.executable, self.model, self.effort, Path(tmp), payload)
            code, stdout, stderr = self._run(argv + self.extra_args, ensure_importable(), stdin, on_stdout=output.feed)
            if self.provider == "aider-cli" and not code:
                output.text = aider_response(Path(tmp) / "response.log")
                cb.on_text_delta(output.text)
        if code or output.error:
            self._last_usage_payload = {"usage": output.usage}
            raise RuntimeError(output.error or (stderr or stdout).strip() or "Agent program failed")
        self._last_usage_payload = {"usage": output.usage}
        text = output.response()
        if not text:
            raise RuntimeError("Agent program returned no answer")
        self._last_streamed_assistant = ""
        return text

    def _codex_turn(self, prompt: str, cb: TurnCallbacks | None = None) -> str:
        from .vendor_events import VendorEvents
        events = VendorEvents(self.provider, cb or TurnCallbacks())
        workspace = ensure_importable()
        with tempfile.TemporaryDirectory(prefix="imagejai-codex-") as tmp:
            output = Path(tmp) / "last.txt"
            base = [self.executable, "exec", "--approve-for-me"]
            if self.model != "default":
                base += ["--model", self.model]
            if self.effort != "default":
                base += ["--config", f'model_reasoning_effort="{self.effort}"']
            if self.resume_vendor_latest:
                command = base + self.extra_args + [
                    "resume", "--last", "--json", "--skip-git-repo-check",
                    "-o", str(output), "-",
                ]
            elif self.external_session_id:
                command = base + self.extra_args + [
                    "resume", "--json", "--skip-git-repo-check",
                    "-o", str(output), self.external_session_id, "-",
                ]
            else:
                command = base + self.extra_args + [
                    "--json", "--skip-git-repo-check",
                    "-C", str(workspace), "-o", str(output), "-",
                ]
            kwargs = {"on_stdout": self._event_handler(events)} if cb is not None else {}
            if self._pending_image is not None:
                _, data = self._pending_image
                capture = Path(tmp) / "capture.jpg"
                capture.write_bytes(base64.b64decode(data))
                command[-1:-1] = ["--image", str(capture)]
                if cb is not None:
                    cb.on_image_attached("capture_image", len(data))
                self._pending_image = None
            payload = self._wrapped_prompt(prompt)
            self._last_input_messages = [{"role": "user", "content": payload}]
            returncode, stdout, stderr = self._run(command, workspace, payload, **kwargs)
            if cb is None:
                for line in stdout.splitlines():
                    events.feed(line)
            thread_id = events.session_id
            self._last_usage_payload = events.usage
            if returncode != 0:
                raise RuntimeError(events.error or (stderr or stdout).strip())
            if events.error:
                raise RuntimeError(events.error)
            if self.resume_vendor_latest and not thread_id:
                raise RuntimeError("Codex did not identify the resumed conversation")
            self.external_session_id = thread_id or self.external_session_id
            self.resume_vendor_latest = False
            self._last_streamed_assistant = events.last_assistant if cb is not None else ""
            return output.read_text(encoding="utf-8").strip() if output.is_file() else events.last_assistant

    def _claude_turn(self, prompt: str, cb: TurnCallbacks | None = None) -> str:
        from .vendor_events import VendorEvents
        events = VendorEvents(self.provider, cb or TurnCallbacks())
        workspace = ensure_importable()
        candidate_id = None
        if self.resume_vendor_latest:
            session_args = ["--continue"]
        elif not self.external_session_id:
            candidate_id = str(uuid.uuid4())
            session_args = ["--session-id", candidate_id]
        else:
            session_args = ["--resume", self.external_session_id]
        command = [
            self.executable, "--print", "--output-format", "stream-json",
            "--verbose", "--include-partial-messages",
            "--permission-mode", "auto", *self.extra_args, *session_args,
        ]
        if self.model != "default":
            command[1:1] = ["--model", self.model]
        if self.effort != "default":
            command[1:1] = ["--effort", self.effort]
        kwargs = {"on_stdout": self._event_handler(events)} if cb is not None else {}
        stdin = self._wrapped_prompt(prompt)
        self._last_input_messages = [{"role": "user", "content": stdin}]
        if self._pending_image is not None:
            mime, data = self._pending_image
            command += ["--input-format", "stream-json"]
            stdin = json.dumps({"type": "user", "message": {"role": "user", "content": [
                {"type": "text", "text": stdin},
                {"type": "image", "source": {"type": "base64", "media_type": mime, "data": data}},
            ]}}, ensure_ascii=False) + "\n"
            if cb is not None:
                cb.on_image_attached("capture_image", len(data))
            self._pending_image = None
        # Pass the catalogue and macro text over stdin, avoiding Windows argv
        # limits and shell quoting even when Claude's launcher is a .cmd file.
        returncode, stdout, stderr = self._run(command, workspace, stdin, **kwargs)
        if cb is None:
            for line in stdout.splitlines():
                events.feed(line)
        self._last_usage_payload = events.usage
        if returncode != 0:
            raise RuntimeError(events.error or (stderr or stdout).strip())
        if events.error:
            raise RuntimeError(events.error)
        if events.result is None:
            raise RuntimeError("Claude stopped without a final result")
        payload = events.result
        resumed_id = events.session_id
        if self.resume_vendor_latest and not resumed_id:
            raise RuntimeError("Claude did not identify the resumed conversation")
        self.external_session_id = resumed_id or candidate_id or self.external_session_id
        self.resume_vendor_latest = False
        self._last_streamed_assistant = events.last_assistant if cb is not None else ""
        return str(payload.get("result") or "").strip()

    def _event_handler(self, events) -> Callable[[str], None]:
        def receive(line: str) -> None:
            if self.abort.set_flag:
                return
            events.feed(line)
            if events.session_id:
                # Preserve the vendor conversation even if Escape interrupts it.
                self.external_session_id = events.session_id
                self.resume_vendor_latest = False
        return receive

    def _run(
        self, command: list[str], workspace: Path, stdin: str | None = None,
        *, on_stdout: Callable[[str], None] | None = None,
    ) -> tuple[int, str, str]:
        """Run a vendor turn while keeping Escape able to terminate it."""
        process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE if stdin is not None else subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            cwd=workspace,
            creationflags=(subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0),
            start_new_session=os.name != "nt",
        )
        stopping = threading.Event()

        def stop_process() -> None:
            if stopping.is_set():
                return
            stopping.set()
            try:
                if os.name == "nt":
                    subprocess.run(
                        ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                        timeout=3, check=False,
                        creationflags=subprocess.CREATE_NO_WINDOW)
                elif process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
            except OSError:
                pass
            except subprocess.TimeoutExpired:
                pass
            if process.poll() is None:
                try:
                    process.terminate()
                except OSError:
                    pass

        self.abort.register(stop_process)
        lines: queue.Queue[str | None] = queue.Queue()
        stdout_parts: list[str] = []
        stderr_parts: list[str] = []

        def read_stdout() -> None:
            try:
                for line in process.stdout:
                    lines.put(line)
            finally:
                process.stdout.close()
                lines.put(None)

        def read_stderr() -> None:
            try:
                for chunk in iter(lambda: process.stderr.read(4096), ""):
                    stderr_parts.append(chunk)
            finally:
                process.stderr.close()

        def write_prompt() -> None:
            try:
                process.stdin.write(stdin)
            except (OSError, ValueError):
                pass
            finally:
                try:
                    process.stdin.close()
                except OSError:
                    pass

        readers = [threading.Thread(target=read_stdout, daemon=True),
                   threading.Thread(target=read_stderr, daemon=True)]
        for reader in readers:
            reader.start()
        if stdin is not None:
            threading.Thread(target=write_prompt, daemon=True).start()
        try:
            eof = False
            while True:
                if self.abort.set_flag:
                    raise RuntimeError("interrupted by user")
                try:
                    line = lines.get(timeout=0.05)
                except queue.Empty:
                    line = ""
                if line is None:
                    eof = True
                elif line:
                    stdout_parts.append(line)
                    if on_stdout is not None and not self.abort.set_flag:
                        on_stdout(line)
                if eof and process.poll() is not None:
                    if self.abort.set_flag:
                        raise RuntimeError("interrupted by user")
                    readers[1].join(timeout=1)
                    return process.returncode, "".join(stdout_parts), "".join(stderr_parts)
        finally:
            self.abort.unregister(stop_process)
            if process.poll() is None:
                stop_process()
                try:
                    process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=1)
            for reader in readers:
                reader.join(timeout=0.2)
