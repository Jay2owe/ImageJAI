"""Headless command-line agents sharing ImageJAI's text action protocol.

The console re-sends its persisted conversation, rather than depending on
different vendors' resume formats. No model output is executed as shell code.
"""
from __future__ import annotations

import json
import re
import shutil
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class InstalledAgent:
    provider: str
    label: str
    commands: tuple[str, ...]


AGENTS = (
    InstalledAgent("gemini-cli", "Gemini CLI", ("gemini", "gemini.cmd")),
    InstalledAgent("aider-cli", "Aider", ("aider", "aider.exe")),
    InstalledAgent("copilot-cli", "GitHub Copilot CLI", ("copilot", "copilot.cmd")),
    InstalledAgent("cline-cli", "Cline", ("cline", "cline.cmd")),
    InstalledAgent("interpreter-cli", "Open Interpreter", ("interpreter", "interpreter.exe")),
)
CLI_PROVIDERS = frozenset(a.provider for a in AGENTS)


def executable(provider: str) -> str | None:
    agent = next((a for a in AGENTS if a.provider == provider), None)
    return next((found for name in agent.commands if (found := shutil.which(name))), None) if agent else None


def installed_entries():
    from .catalog import CatalogEntry, Tier
    return [CatalogEntry(provider=a.provider, model_id="default", display_name=a.label,
                         tier=Tier.UNCURATED,
                         description="Installed program; uses its own login and model settings. Billing depends on that account.")
            for a in AGENTS if executable(a.provider)]


def command(provider: str, exe: str, model: str, effort: str, tmp: Path, prompt: str):
    """Return static argv and stdin; arbitrary conversation text never enters argv."""
    model_args = ["--model", model] if model != "default" else []
    if provider == "gemini-cli":
        return [exe, "--output-format", "stream-json", "--approval-mode", "plan", *model_args,
                "--prompt", "Follow the ImageJAI conversation on stdin."], prompt
    if provider == "aider-cli":
        source = tmp / "conversation.txt"
        source.write_text(prompt, encoding="utf-8")
        return [exe, "--message-file", str(source), "--chat-mode", "ask", "--dry-run",
                "--no-auto-commits", "--no-git", "--no-pretty", "--no-stream",
                "--llm-history-file", str(tmp / "response.log"),
                "--chat-history-file", str(tmp / "chat.md"),
                "--input-history-file", str(tmp / "input.txt"), "--map-tokens", "0", *model_args], None
    if provider == "copilot-cli":
        return [exe, "-s", "--no-ask-user", "--available-tools=", *model_args], prompt
    if provider == "cline-cli":
        args = [exe, "--json", "--plan", "--auto-approve", "false", *model_args]
        if effort != "default":
            args += ["--thinking", effort]
        return args, prompt
    if provider == "interpreter-cli":
        return [exe, "exec", "--sandbox", "read-only", "--json", *model_args, "-"], prompt
    raise ValueError(f"Unsupported installed agent: {provider}")


class AgentOutput:
    """Only known text fields become visible; control traffic stays private."""
    def __init__(self, provider, callbacks):
        self.provider, self.cb = provider, callbacks
        self.text = ""
        self.error = ""
        self.usage = {}
        self.session_id = None

    def feed(self, line):
        if self.provider == "aider-cli":
            # Aider mixes banners with stdout; its separate assistant log is
            # the response boundary. Never promote an example in prose to a call.
            return
        if self.provider == "copilot-cli":
            self.text += line
            self.cb.on_text_delta(line)
            return
        try:
            event = json.loads(line)
        except (TypeError, ValueError):
            return
        if not isinstance(event, dict):
            return
        kind = event.get("type")
        self.session_id = event.get("session_id") or self.session_id
        text = ""
        if self.provider == "gemini-cli":
            if kind == "message" and event.get("role") == "assistant":
                text = event.get("content") or ""
            elif kind == "result":
                self.usage = event.get("stats") or {}
                if event.get("status") == "error":
                    error = event.get("error") or {}
                    self.error = str(error.get("message") if isinstance(error, dict) else error)
            elif kind == "error" and event.get("severity") == "error":
                self.error = str(event.get("message") or "Gemini CLI failed")
        elif self.provider == "cline-cli":
            if kind == "ask" and event.get("ask") in {"tool", "command", "command_output"}:
                raise RuntimeError("Cline requested a native tool; use ImageJAI text actions instead")
            if event.get("reasoning"):
                self.cb.on_thinking_delta(str(event["reasoning"]))
            if kind == "say" and event.get("say") in {"text", "completion_result"}:
                text = event.get("text") or ""
        elif self.provider == "interpreter-cli":
            item = event.get("item") or {}
            if kind == "item.completed" and item.get("type") == "agent_message":
                text = item.get("text") or ""
            elif kind == "item.completed" and item.get("type") == "reasoning":
                self.cb.on_thinking_delta(str(item.get("text") or ""))
            elif kind in {"error", "turn.failed"}:
                self.error = str((event.get("error") or event).get("message") or "Open Interpreter failed")
            elif kind == "turn.completed":
                self.usage = event.get("usage") or {}
        if isinstance(text, str) and text:
            self.text += text
            self.cb.on_text_delta(text)

    def response(self):
        return self.text.strip()


def aider_response(path: Path) -> str:
    lines = path.read_text(encoding="utf-8").splitlines()
    boundaries = [i for i, line in enumerate(lines) if re.fullmatch(r"LLM RESPONSE \d{4}-\d\d-\d\dT\d\d:\d\d:\d\d", line)]
    if not boundaries:
        raise RuntimeError("Aider did not write an identifiable assistant response")
    body = lines[boundaries[-1] + 1:]
    return "\n".join(line[len("ASSISTANT "):] for line in body if line.startswith("ASSISTANT "))
