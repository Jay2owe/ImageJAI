"""Translate vendor JSON lines into the console's visible turn callbacks.

Only documented display fields are used: never print signatures, encrypted
reasoning, credentials, or an arbitrary event's JSON as a fallback.
"""
from __future__ import annotations

import json
from typing import Any

from .agent_loop import TurnCallbacks
from .shell_actions import fiji_actions


class VendorEvents:
    def __init__(self, provider: str, callbacks: TurnCallbacks):
        self.provider = provider
        self.cb = callbacks
        self.session_id: str | None = None
        self.result: dict[str, Any] | None = None
        self.last_assistant = ""
        self.error = ""
        self.usage = {}
        self._text: dict[tuple, str] = {}
        self._tools: dict[str, str] = {}
        self._tool_inputs: dict[str, tuple[str, dict]] = {}
        self._completed: set[str] = set()
        self._message_id = ""
        self._last_thinking: tuple | None = None

    def feed(self, line: str) -> None:
        try:
            event = json.loads(line)
        except (ValueError, TypeError):
            return
        if not isinstance(event, dict):
            return
        if self.provider == "codex-subscription":
            self._codex(event)
        else:
            self._claude(event)

    def _emit(self, key: tuple, text: str, thinking: bool, *, snapshot: bool = False) -> None:
        if not isinstance(text, str) or not text:
            return
        previous = self._text.get(key, "")
        if snapshot:
            # item.updated and the final assistant message contain snapshots.
            # A non-prefix replacement must not duplicate already shown text.
            delta = text[len(previous):] if text.startswith(previous) else ""
            self._text[key] = text
        else:
            delta = text
            self._text[key] = previous + text
        if not delta:
            return
        if thinking:
            if self._last_thinking is not None and self._last_thinking != key:
                delta = "\n\n" + delta
            self._last_thinking = key
            self.cb.on_thinking_delta(delta)
        else:
            self.cb.on_text_delta(delta)

    def _tool_start(self, ident: str, name: str, args: dict) -> None:
        if ident not in self._tools:
            self._tool_inputs[ident] = (name, args)
            # Distinct Fiji names also keep overlapping Shell/Bash calls
            # associated with their own results when they finish out of order.
            if name.lower() in {"shell", "bash", "exec_command"}:
                actions = fiji_actions(args.get("command") or args.get("cmd") or "")
                if len(actions) == 1 and actions[0].name != "Shell":
                    name, args = actions[0].name, actions[0].args
            self._tools[ident] = name
            self.cb.on_tool_start(name, args)

    def _tool_done(self, ident: str, ok: bool, summary: str) -> None:
        if ident in self._tools and ident not in self._completed:
            self._completed.add(ident)
            self.cb.on_tool_result(self._tools[ident], ok, summary)
            original_name, original_args = self._tool_inputs[ident]
            # The readable label never replaces the actual submitted command
            # in the evidence journal.
            self.cb.on_tool_record(ident, original_name, original_args, ok, summary[:1_000_000])

    def _codex(self, event: dict) -> None:
        kind = event.get("type")
        if kind == "thread.started":
            self.session_id = event.get("thread_id") or self.session_id
        elif kind == "turn.completed":
            self.usage = {"usage": event.get("usage") or {}}
        elif kind in {"error", "turn.failed"}:
            error = event.get("error") or event
            self.error = str(error.get("message") or "Codex turn failed")
        elif kind in {"item.started", "item.updated", "item.completed"}:
            item = event.get("item") or {}
            ident = str(item.get("id", ""))
            item_type = item.get("type")
            if item_type in {"reasoning", "agent_message"}:
                text = item.get("text") or ""
                self._emit((ident,), text, item_type == "reasoning", snapshot=True)
                if item_type == "agent_message" and kind == "item.completed" and text:
                    self.last_assistant = text.strip()
                    self.cb.on_assistant(text)
            elif item_type in {"command_execution", "mcp_tool_call", "web_search", "file_change"}:
                if item_type == "command_execution":
                    name, args = "Shell", {"command": item.get("command", "")}
                elif item_type == "mcp_tool_call":
                    name, args = str(item.get("tool") or "Tool"), item.get("arguments") or {}
                elif item_type == "web_search":
                    name, args = "Web search", {"query": item.get("query", "")}
                else:
                    name, args = "Edit files", {"paths": [c.get("path") for c in item.get("changes", [])]}
                self._tool_start(ident, name, args if isinstance(args, dict) else {})
                if kind == "item.completed":
                    ok = item.get("status") != "failed" and item.get("exit_code") in (None, 0)
                    summary = str(item.get("aggregated_output") or ("Completed" if ok else "Failed"))
                    self._tool_done(ident, ok, summary)

    def _claude(self, event: dict) -> None:
        self.session_id = event.get("session_id") or self.session_id
        kind = event.get("type")
        if kind == "result" or (kind is None and "result" in event):
            self.result = event
            self.usage = {"usage": event.get("usage") or {}, "total_cost_usd": event.get("total_cost_usd")}
            if event.get("is_error"):
                self.error = str(event.get("result") or "; ".join(event.get("errors") or []) or "Claude turn failed")
        elif kind == "stream_event":
            partial = event.get("event") or {}
            stage = partial.get("type")
            if stage == "message_start":
                self._message_id = str((partial.get("message") or {}).get("id", ""))
            key = (self._message_id, partial.get("index", 0))
            if stage == "content_block_start":
                block = partial.get("content_block") or {}
                if block.get("type") != "tool_use":
                    self._claude_block(key, block)
                else:
                    self.cb.on_tool_preparing(str(block.get("name") or "Tool"))
            elif stage == "content_block_delta":
                delta = partial.get("delta") or {}
                if delta.get("type") == "thinking_delta":
                    self._emit(key, delta.get("thinking", ""), True)
                elif delta.get("type") == "text_delta":
                    self._emit(key, delta.get("text", ""), False)
        elif kind == "assistant":
            message = event.get("message") or {}
            ident = str(message.get("id") or self._message_id)
            text = []
            for index, block in enumerate(message.get("content") or []):
                if not isinstance(block, dict):
                    continue
                if block.get("type") != "tool_use":
                    self._claude_block((ident, index), block)
                if block.get("type") == "text":
                    text.append(block.get("text", ""))
            if text:
                self.last_assistant = "".join(text).strip()
                self.cb.on_assistant(self.last_assistant)
            # Complete the streamed commentary before a tool moves it into the
            # chat log, and use the complete input rather than partial JSON.
            for index, block in enumerate(message.get("content") or []):
                if isinstance(block, dict) and block.get("type") == "tool_use":
                    self._claude_block((ident, index), block)
        elif kind == "user":
            for block in (event.get("message") or {}).get("content") or []:
                if isinstance(block, dict) and block.get("type") == "tool_result":
                    content = block.get("content", "")
                    if isinstance(content, list):
                        content = "\n".join(b.get("text", "") for b in content if isinstance(b, dict))
                    self._tool_done(str(block.get("tool_use_id", "")),
                                    not block.get("is_error", False), str(content))

    def _claude_block(self, key: tuple, block: dict) -> None:
        kind = block.get("type")
        if kind == "thinking":
            self._emit(key, block.get("thinking", ""), True, snapshot=True)
        elif kind == "text":
            self._emit(key, block.get("text", ""), False, snapshot=True)
        elif kind == "tool_use":
            self._tool_start(str(block.get("id", "")), str(block.get("name") or "Tool"), block.get("input") or {})
