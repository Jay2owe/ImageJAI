"""Turn engine for the console: model + tools + Fiji, no UI.

Drives any provider through ``agent.providers.router.get_client`` with the
Fiji tool registry from ``gemma4_31b`` (whose modules are provider-agnostic).
The catalogue is supplied as text, so native function calling is not required.
Each turn:

    user text -> [ model reply -> tool calls -> tool results ]* -> final text

Progress is reported through a callbacks object so any front-end (TUI,
tests, headless script) can stream it. All calls block; run ``turn`` on a
worker thread and abort via the shared ``AbortFlag``.

Host-code tools (scripts and recipes) pause for approval —
the callbacks object decides (the TUI shows a modal, tests auto-allow).
"""
from __future__ import annotations

import json
import re
import threading
import traceback
from dataclasses import dataclass, field
from typing import Any, Callable

from .compaction import estimate_tokens, plan_compaction, should_compact
from .workspace import ensure_importable

MAX_TOOL_ROUNDS = 24
MAX_TOOL_RESULT_CHARS = 4000
MAX_EVIDENCE_RESULT_CHARS = 1_000_000

FALLBACK_SYSTEM_PROMPT = (
    "You are ImageJAI, an AI assistant embedded in a scientist's ImageJ/Fiji "
    "session. You control Fiji through tools over a TCP command server. "
    "Prefer the provided Fiji tools for anything image-related. Run macros "
    "with run_macro, inspect state with state tools, and check results with "
    "results tools before summarising. Be concise and quantitative. When a "
    "tool fails, read the error and adapt instead of repeating the call."
)


class AbortFlag:
    """Cooperative cancel shared between the UI and a running turn."""

    def __init__(self) -> None:
        self._event = threading.Event()
        self._lock = threading.Lock()
        self._callbacks: list[Callable[[], None]] = []

    def set(self) -> None:
        with self._lock:
            if self._event.is_set():
                return
            self._event.set()
            callbacks = list(self._callbacks)
            self._callbacks.clear()
        for callback in callbacks:
            threading.Thread(target=self._call_safely, args=(callback,),
                             daemon=True, name="imagejai-cancel").start()

    @staticmethod
    def _call_safely(callback: Callable[[], None]) -> None:
        try:
            callback()
        except Exception:
            pass

    def register(self, callback: Callable[[], None]) -> None:
        with self._lock:
            if not self._event.is_set():
                self._callbacks.append(callback)
                return
        threading.Thread(target=self._call_safely, args=(callback,),
                         daemon=True, name="imagejai-cancel").start()

    def unregister(self, callback: Callable[[], None]) -> None:
        with self._lock:
            if callback in self._callbacks:
                self._callbacks.remove(callback)

    @property
    def set_flag(self) -> bool:
        return self._event.is_set()


@dataclass
class TurnCallbacks:
    """Everything the UI wants to know about while a turn runs."""
    on_user: Callable[[str], None] = lambda text: None
    on_assistant: Callable[[str], None] = lambda text: None
    on_tool_start: Callable[[str, dict], None] = lambda name, args: None
    on_tool_result: Callable[[str, bool, str], None] = lambda name, ok, summary: None
    on_tool_record: Callable[[str, str, dict, bool, str], None] = (
        lambda correlation_id, name, args, ok, result: None
    )
    """Lossless bounded tool evidence; separate from the short UI summary."""
    on_error: Callable[[str], None] = lambda err: None
    on_done: Callable[[bool], None] = lambda ok: None
    on_approval: Callable[[str, dict], bool] = lambda name, args: False
    """Return False to refuse a host-code tool call."""
    on_text_delta: Callable[[str], None] = lambda text: None
    """Streaming text chunk (providers exposing chat_stream); UI shows live text."""
    on_thinking_delta: Callable[[str], None] = lambda text: None
    """Provider-exposed thinking or reasoning summary, separate from the answer."""
    on_tool_preparing: Callable[[str], None] = lambda name: None
    """The model has started generating arguments; the tool has not run yet."""
    on_compaction: Callable[[dict[str, Any]], None] = lambda report: None
    """A successful context rewrite; the append-only evidence remains untouched."""
    on_image_attached: Callable[[str, int], None] = lambda tool_name, base64_chars: None
    on_usage: Callable[[dict], None] = lambda report: None
    before_model_call: Callable[[dict], bool] = lambda request: True
    take_steering: Callable[[], list[str]] = lambda: []
    """Pixels actually left the machine for this tool result."""


def _import_registry() -> tuple[list[Any], frozenset[str]]:
    """Import the gemma4_31b tool registry; ([], set()) on failure."""
    try:
        ensure_importable()
        from gemma4_31b import (  # noqa: F401 — imports fire @tool decorators
            describe_image, events, safety, threshold_shootout,  # type: ignore
            tools_bioformats, tools_dialogs, tools_fiji, tools_jobs,
            tools_plugins, tools_python, tools_recipes, tools_shell,
            triage_image,
        )
        from gemma4_31b.registry import HOST_CODE_TOOL_NAMES, REGISTRY
        return list(REGISTRY), HOST_CODE_TOOL_NAMES
    except Exception:
        return [], frozenset()


def _system_prompt(provider: str, model_id: str) -> str:
    """Per-model context overlay from agent/contexts (provider/model_id key)."""
    try:
        ensure_importable()
        from agent.contexts.loader import load_context
        ctx = load_context(f"{provider}/{model_id}")
        if isinstance(ctx, str) and ctx.strip():
            return ctx
    except Exception:
        pass
    return FALLBACK_SYSTEM_PROMPT


def _image_bytes(message: Any) -> int:
    """Base64 characters of image blocks in one provider message, 0 on failure."""
    try:
        from agent.providers.base import message_image_bytes
    except ImportError:
        return 0
    try:
        return int(message_image_bytes(message))
    except Exception:
        return 0


def _clip(text: str, limit: int = MAX_TOOL_RESULT_CHARS) -> str:
    text = str(text)
    if limit < 0:
        raise ValueError("Preview limit cannot be negative")
    if len(text) <= limit:
        return text
    # The total budget includes the disclosure; the complete return remains
    # in receipts/evidence. Preserve error summaries often found at the end.
    marker = f"\n… [incomplete preview: {len(text):,} source characters; middle omitted] …\n"
    if len(marker) >= limit:
        return "[incomplete preview]"[:limit]
    remaining = limit - len(marker)
    head = (remaining + 1) // 2
    tail = remaining - head
    return text[:head] + marker + (text[-tail:] if tail else "")


class ConsoleAgent:
    """One chat session bound to a provider+model, with Fiji tools."""

    def __init__(self, provider: str, model: str, api_key: str | None = None,
                 effort: str = "default") -> None:
        ensure_importable()
        from agent.providers.router import get_client
        opts: dict[str, Any] = {}
        if api_key:
            opts["api_key"] = api_key
        self.provider = provider
        self.model = model
        from .model_choice import api_effort_kwargs
        self.effort = effort
        self._effort_kwargs = api_effort_kwargs(provider, model, effort)
        self.client = get_client(provider, model, **opts)
        self.tools, self.host_code_tools = _import_registry()
        self.fiji_connection = None
        self.tool_result_filter = None
        self.action_receipts: dict = {}
        from .wrapped_tools import instructions
        self._base_system_prompt = _system_prompt(provider, model) + "\n\n" + instructions(self)
        self._harness_digest = ""
        self._skill_catalog = ""
        self._active_skill = ""
        self._project_instructions = ""
        self.messages: list[dict[str, Any]] = [
            {"role": "system", "content": self._base_system_prompt}
        ]
        self.abort = AbortFlag()

    # -------------------------------------------------------------- helpers
    def _rebuild_system_message(self) -> None:
        content = self._base_system_prompt
        if getattr(self, "_project_instructions", ""):
            content += "\n\n" + self._project_instructions
        if self._skill_catalog:
            content += "\n\n---\n\n" + self._skill_catalog
        if self._active_skill:
            content += "\n\n---\n\n[active-imagejai-skill]\n" + self._active_skill + "\n[/active-imagejai-skill]"
        if self._harness_digest:
            content += (
                "\n\n---\n\n[imagejai-harness]\n"
                + self._harness_digest
                + "\n[/imagejai-harness]"
            )
        if self.messages and self.messages[0].get("role") == "system":
            self.messages[0] = {"role": "system", "content": content}
        else:
            self.messages.insert(0, {"role": "system", "content": content})

    def set_harness_digest(self, digest: str | None) -> None:
        """Replace the bounded dynamic harness block in the system message."""
        self._harness_digest = str(digest or "").strip()
        self._rebuild_system_message()

    def set_project_instructions(self, text: str) -> None:
        self._project_instructions = text
        self._rebuild_system_message()

    def set_image_policy(self, allowed: bool, reason: str = "") -> None:
        """Allow or refuse outbound image attachments for the next turn."""
        self._image_policy = bool(allowed)
        try:
            from agent.providers.base import ImageAttachmentPolicy, prune_capture_images
        except ImportError:
            return
        if not allowed:
            prune_capture_images(self.messages)
        configure = getattr(self.client, "configure_image_policy", None)
        if callable(configure):
            configure(ImageAttachmentPolicy(allowed=bool(allowed), reason=str(reason or "")))

    def set_fiji_connection(self, connection) -> None:
        """Execute tools against the installation selected by the console."""
        self.fiji_connection = connection

    def set_tool_result_filter(self, scrubber) -> None:
        self.tool_result_filter = scrubber

    def set_skill_catalog(self, catalog: str | None) -> None:
        """Expose only skill metadata; bodies remain on-demand."""
        self._skill_catalog = str(catalog or "").strip()
        self._rebuild_system_message()

    def set_active_skill(self, name: str | None, body: str | None = None) -> None:
        """Activate one explicitly loaded guidance body, or clear it."""
        self._active_skill = "" if not name else f"name: {name}\n{str(body or '').strip()}"
        self._rebuild_system_message()

    def _tool_by_name(self, name: str) -> Any | None:
        for t in self.tools:
            if getattr(t, "__name__", "") == name:
                return t
        return None

    def _execute_tool(self, name: str, args: dict[str, Any]) -> tuple[bool, str]:
        from .wrapped_tools import execute_function
        return execute_function(self, name, args)

    # ---------------------------------------------------------- refinement
    def propose_learnings(self, evidence_text: str, *, max_proposals: int = 5) -> list[dict[str, Any]]:
        """Use a separate no-tools call to draft candidates; never persist them."""
        maximum = max(1, min(int(max_proposals), 5))
        evidence = str(evidence_text or "")[:80_000]
        prompt = f"""Review this ImageJ analysis session and propose at most {maximum} reusable learnings.
Return strict JSON only: {{"proposals":[{{"kind":"fact|preference|constraint|failure_fix|procedure|validation_rule|environment_capability|prompt_policy","title":"...","content":"...","applicability":{{}}}}]}}.
Rules:
- These are untrusted candidates for human review, not approved knowledge.
- Never include paths, patient/sample identifiers, raw pixels, credentials, or secrets.
- Do not generalise numeric thresholds/defaults from one image. Mark image-specific facts in applicability.
- Prefer exact software failure/fix facts, explicit user preferences, safety constraints, and validation rules.
- Do not claim biological validity from a plausible result.

SESSION EVIDENCE
{evidence}"""
        from .usage import model_request
        with model_request(self, [{'role': 'system', 'content': 'You extract conservative, reviewable ImageJ workflow learnings.'}, {'role': 'user', 'content': prompt}]) as request_usage:
            reply = self.client.chat([
                {"role": "system", "content": "You extract conservative, reviewable ImageJ workflow learnings."},
                {"role": "user", "content": prompt},
            ], [], self.model, **self._effort_kwargs)
            request_usage.reply = reply
            request_usage.text = self.client.extract_text(reply) or ""
        text = (self.client.extract_text(reply) or "").strip()
        if text.startswith("```"):
            text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text, flags=re.I | re.S).strip()
        try:
            parsed = json.loads(text)
        except (TypeError, ValueError) as exc:
            raise ValueError(f"refinement did not return valid JSON: {exc}") from exc
        rows = parsed.get("proposals") if isinstance(parsed, dict) else None
        if not isinstance(rows, list):
            raise ValueError("refinement JSON needs a proposals list")
        allowed = {
            "fact", "preference", "constraint", "failure_fix", "procedure",
            "validation_rule", "environment_capability", "prompt_policy",
        }
        out: list[dict[str, Any]] = []
        for row in rows[:maximum]:
            if not isinstance(row, dict) or row.get("kind") not in allowed:
                continue
            title = str(row.get("title") or "").strip()[:240]
            content = str(row.get("content") or "").strip()[:12_000]
            applicability = row.get("applicability") or {}
            if not title or not content or not isinstance(applicability, dict):
                continue
            # Scope and status are deliberately absent: the human chooses the
            # store, and HarnessStore.propose always creates a candidate.
            out.append({
                "kind": row["kind"], "title": title, "content": content,
                "applicability": applicability,
            })
        return out

    # ----------------------------------------------------------- compaction
    def needs_compaction(self, context_window: int, reserve_tokens: int = 16_384) -> bool:
        return should_compact(self.messages, context_window, reserve_tokens)

    def compact(self, *, keep_recent_tokens: int = 20_000,
                artifact_writer: Callable | None = None) -> dict[str, Any]:
        """Summarise old prose while preserving exact scientific facts.

        The append-only evidence journal is not touched. The rewrite happens
        only after the summary call succeeds and never splits a tool pair.
        """
        plan = plan_compaction(
            self.messages, keep_recent_tokens,
            artifact_writer=artifact_writer,
        )
        if not plan.head_messages:
            return {
                "compacted": False, "reason": "nothing old enough to compact",
                "tokens_before": plan.estimated_tokens_before,
                "tokens_after": plan.estimated_tokens_before,
            }
        if plan.malformed:
            return {
                "compacted": False, "reason": "malformed tool pairing",
                "warnings": list(plan.warnings),
                "tokens_before": plan.estimated_tokens_before,
                "tokens_after": plan.estimated_tokens_before,
            }
        prose = "\n".join(plan.prose_to_summarize)
        # The exact lane bypasses the summariser. Limit only its input prose;
        # full history remains in the evidence journal and session file.
        max_summary_input = 200_000
        if len(prose) > max_summary_input:
            dropped = len(prose) - max_summary_input
            prose = (prose[:100_000] +
                     f"\n… [middle prose omitted: {dropped} chars] …\n" +
                     prose[-100_000:])
        prompt = (
            "Summarise the earlier image-analysis conversation for continuation. "
            "Preserve the user's goal, constraints, completed work, failures, "
            "decisions, and next steps. Do not invent measurements. Exact numeric "
            "facts are carried separately, so do not paraphrase them.\n\n" + prose
        )
        try:
            from .usage import model_request
            with model_request(self, [{'role': 'system', 'content': 'You write concise scientific session checkpoints.'}, {'role': 'user', 'content': prompt}]) as request_usage:
                reply = self.client.chat([
                    {"role": "system", "content": "You write concise scientific session checkpoints."},
                    {"role": "user", "content": prompt},
                ], [], self.model, **self._effort_kwargs)
                request_usage.reply = reply
                request_usage.text = self.client.extract_text(reply) or ""
            summary = self.client.extract_text(reply) or "Earlier prose had no additional summary."
        except Exception as exc:
            return {
                "compacted": False, "reason": f"summary failed: {exc}",
                "tokens_before": plan.estimated_tokens_before,
                "tokens_after": plan.estimated_tokens_before,
            }
        facts = "\n".join(f"- {fact}" for fact in plan.critical_facts)
        checkpoint = (
            "[compaction-summary]\n" + summary.strip()
            + ("\n\nVERBATIM SCIENTIFIC FACTS\n" + facts if facts else "")
            + "\n[/compaction-summary]"
        )
        system = [self.messages[0]] if self.messages and self.messages[0].get("role") == "system" else []
        recent = [message for message in plan.recent_messages
                  if message.get("role") != "system"]
        replacement = system + [{"role": "user", "content": checkpoint}] + recent
        before_count = len(self.messages)
        self.messages = replacement
        return {
            "compacted": True,
            "messages_before": before_count,
            "messages_after": len(replacement),
            "tokens_before": plan.estimated_tokens_before,
            "tokens_after": estimate_tokens(replacement),
            "evicted_artifacts": [artifact.__dict__ for artifact in plan.evicted_artifacts],
            "warnings": list(plan.warnings),
            "critical_fact_count": len(plan.critical_facts),
            "summary_input_chars": len(prompt),
        }

    # ---------------------------------------------------------------- turn
    def turn(self, user_text: str, cb: TurnCallbacks) -> bool:
        """Run one full turn. Returns True when it completed (not aborted)."""
        from .wrapped_tools import (ActionDisplay, ActionError, execute_action,
                                    parse_action, result_message, capture_attachment, append_capture,
                                    model_messages)
        cb.on_user(user_text)
        self.messages.append({"role": "user", "content": user_text})
        ok_overall = True
        try:
            for _round in range(MAX_TOOL_ROUNDS):
                from .turn_queue import inject_steering
                inject_steering(self, cb)
                display = ActionDisplay(cb)
                if self.abort.set_flag:
                    self.messages.append({
                        "role": "user",
                        "content": "[interrupted by user]",
                    })
                    cb.on_done(False)
                    return False
                try:
                    outbound = model_messages(self.messages)
                    from .usage import model_request
                    with model_request(self, outbound, cb) as request_usage:
                        chat_stream = getattr(self.client, "chat_stream", None)
                        if callable(chat_stream):
                            reply = chat_stream(
                                outbound, [], self.model,
                                on_delta=display.delta,
                                on_thinking=cb.on_thinking_delta,
                                on_tool_preparing=cb.on_tool_preparing,
                                abort=self.abort,
                                **self._effort_kwargs,
                            )
                        else:
                            reply = self.client.chat(
                                outbound, [], self.model,
                                **self._effort_kwargs)
                        request_usage.reply = reply
                        request_usage.text = self.client.extract_text(reply) or ""
                except Exception as exc:
                    from .usage import ModelCallStopped
                    if isinstance(exc, ModelCallStopped):
                        cb.on_done(False)
                        return False
                    if self.abort.set_flag:
                        cb.on_done(False)
                        return False
                    cb.on_error(f"model call failed: {exc}")
                    ok_overall = False
                    break

                if self.abort.set_flag:
                    cb.on_done(False)
                    return False

                calls = self.client.extract_tool_calls(reply)
                if not calls:
                    text = self.client.extract_text(reply) or "(no reply)"
                    self.client.append_assistant(self.messages, reply)
                    try:
                        request = parse_action(text)
                    except ActionError as exc:
                        self.messages.append({"role": "user", "content":
                                              f"ImageJAI action rejected without execution: {exc}. Send a corrected action message."})
                        continue
                    if request is not None:
                        corrections = inject_steering(self, cb)
                        if corrections:
                            receipt = {**request.as_dict(), "ok": False, "result": "User correction received; this action was not executed."}
                            cb.on_tool_record(request.id, request.tool, request.arguments, False, receipt["result"])
                        else:
                            receipt = execute_action(self, request, cb)
                        capture = capture_attachment(self, request, receipt)
                        self.messages.append(result_message(receipt))
                        append_capture(self, capture, cb)
                        continue
                    display.assistant(text)
                    if inject_steering(self, cb):
                        continue
                    break

                self.client.append_assistant(self.messages, reply)
                corrections = cb.take_steering()
                for call in calls:
                    name, args = call.name, dict(call.args or {})
                    correlation_id = str(getattr(call, "id", "") or f"tool-{_round}")
                    if self.abort.set_flag:
                        result = "Interrupted by user; this action was not executed."
                        ok = False
                    elif corrections:
                        result = "User correction received; this action was not executed."
                        ok = False
                    elif name in self.host_code_tools and not cb.on_approval(name, args):
                        result = "Refused: host-code tool requires user approval."
                        ok = False
                        ok_overall = False
                    else:
                        cb.on_tool_start(name, args)
                        ok, result = self._execute_tool(name, args)
                        cb.on_tool_result(name, ok, _clip(result, 20_480))
                    cb.on_tool_record(correlation_id, name, args, ok, result)
                    before_count = len(self.messages)
                    self.client.append_tool_result(self.messages, call, _clip(result))
                    # Report pixels that actually left: the provider client, not
                    # this loop, decides whether an image block was added.
                    for message in self.messages[before_count:]:
                        attached = _image_bytes(message)
                        if attached:
                            cb.on_image_attached(name, attached)
                if self.abort.set_flag:
                    cb.on_done(False)
                    return False
                for correction in corrections:
                    self.messages.append({"role": "user", "content": correction})
                    cb.on_user(correction)
            else:
                cb.on_error(f"stopped after {MAX_TOOL_ROUNDS} tool rounds")
                ok_overall = False
        except Exception:
            cb.on_error("unexpected error:\n" + traceback.format_exc(limit=4))
            ok_overall = False
        cb.on_done(ok_overall)
        return ok_overall


def create_agent(
    provider: str, model: str, api_key: str | None = None,
    external_session_id: str | None = None,
    effort: str = "default",
    resume_vendor_latest: bool = False,
) -> ConsoleAgent | Any:
    """Build either an API/local agent or an official subscription CLI adapter."""
    from .subscriptions import SUBSCRIPTION_PROVIDERS, SubscriptionAgent
    if provider in SUBSCRIPTION_PROVIDERS:
        return SubscriptionAgent(provider, model, external_session_id,
                                 effort=effort,
                                 resume_vendor_latest=resume_vendor_latest)
    return ConsoleAgent(provider, model, api_key=api_key, effort=effort)
