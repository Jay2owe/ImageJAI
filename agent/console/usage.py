"""Session usage estimation for /budget.

Rough, local, zero-network: token counts are estimated from message sizes
(chars/4, adjusted by the model's tokenizer_multiplier from models.yaml)
and priced with the yaml's input/output usd-per-Mtok fields. Good enough
for a live "how much is this session costing" readout — the authoritative
numbers stay with the Java budget engine and LiteLLM cost listeners.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any
import math
import time
import uuid
from contextlib import contextmanager

CHARS_PER_TOKEN = 4.0


@dataclass
class UsageEstimate:
    input_tokens: int = 0
    output_tokens: int = 0
    input_usd: float = 0.0
    output_usd: float = 0.0
    tokenizer_multiplier: float = 1.0
    priced: bool = False  # False when models.yaml had no pricing for the model

    @property
    def total_tokens(self) -> int:
        return self.input_tokens + self.output_tokens

    @property
    def total_usd(self) -> float:
        return self.input_usd + self.output_usd


def _message_text(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for block in content:
            if isinstance(block, dict):
                if isinstance(block.get("text"), str):
                    parts.append(block["text"])
                elif isinstance(block.get("content"), str):
                    parts.append(block["content"])
        return "\n".join(parts)
    if content is None:
        return ""
    return str(content)


def _pricing_for(provider: str, model: str) -> tuple[dict, float]:
    """(pricing dict, tokenizer_multiplier) from models.yaml; ({}, 1.0) when unknown."""
    try:
        from .providers import load_models_yaml
        for entry in load_models_yaml():
            if entry.provider == provider and entry.model_id == model:
                raw = getattr(entry, "_pricing", None)
                if raw is None:
                    # ModelEntry doesn't carry pricing — re-scan the yaml entry dict
                    raw = {}
                    for m in _raw_models():
                        if m.get("provider") == provider and m.get("model_id") == model:
                            raw = m.get("pricing") or {}
                            break
                mult = 1.0
                for m in _raw_models():
                    if m.get("provider") == provider and m.get("model_id") == model:
                        try:
                            mult = float(m.get("tokenizer_multiplier") or 1.0)
                        except (TypeError, ValueError):
                            mult = 1.0
                        break
                return raw, mult
    except Exception:
        pass
    return {}, 1.0


def _raw_models() -> list[dict]:
    try:
        from .workspace import ensure_importable
        import yaml
        ws = ensure_importable()
        path = ws / "providers" / "models.yaml"
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        return [m for m in data.get("models", []) if isinstance(m, dict)]
    except Exception:
        return []


def estimate_usage(messages: list[dict], provider: str, model: str) -> UsageEstimate:
    est = UsageEstimate()
    pricing, est.tokenizer_multiplier = _pricing_for(provider, model)
    try:
        in_price = float(pricing.get("input_usd_per_mtok") or 0.0)
        out_price = float(pricing.get("output_usd_per_mtok") or 0.0)
        est.priced = in_price > 0 or out_price > 0
    except (TypeError, ValueError):
        in_price = out_price = 0.0

    for msg in messages or []:
        role = msg.get("role", "")
        text = _message_text(msg.get("content"))
        # tool-call blocks ride in assistant content/tool_calls — count them too
        calls = msg.get("tool_calls") or []
        if isinstance(calls, list):
            text += "\n".join(str(c) for c in calls)
        tokens = int(len(text) / CHARS_PER_TOKEN * est.tokenizer_multiplier)
        if role in ("assistant",):
            est.output_tokens += tokens
        else:
            est.input_tokens += tokens

    est.input_usd = est.input_tokens / 1e6 * in_price
    est.output_usd = est.output_tokens / 1e6 * out_price
    return est


def _field(value, key, default=None):
    return value.get(key, default) if isinstance(value, dict) else getattr(value, key, default)


def _number(value):
    try:
        result = float(value)
        return result if math.isfinite(result) and result >= 0 else None
    except (ValueError, TypeError, OverflowError):
        return None


def request_usage(provider, model, payload, messages=(), text="", request_id=None, failed=False):
    """Extract only accounting fields, never arbitrary response/credential data."""
    usage = _field(payload, "usage") or _field(payload, "usage_metadata") or payload or {}
    def quantity(*names):
        for key in names:
            value = _number(_field(usage, key))
            if value is not None:
                return int(value)
        return None
    inp = quantity("input_tokens", "prompt_tokens", "prompt_token_count", "prompt")
    out = quantity("output_tokens", "completion_tokens", "candidates_token_count", "candidates")
    cached = quantity("cache_read_input_tokens", "cached_input_tokens", "cached_content_token_count", "cached") or 0
    detail = _field(usage, "prompt_tokens_details", {})
    cached = int(_number(_field(detail, "cached_tokens")) or cached)
    cache_write = quantity("cache_creation_input_tokens") or 0
    thoughts = quantity("thoughts_token_count", "thoughts") or 0
    if _field(usage, "candidates_token_count") is not None or _field(usage, "candidates") is not None:
        out = (out or 0) + thoughts
    reported = inp is not None and out is not None
    if not reported:
        inp = int(sum(len(_message_text(m.get("content"))) for m in messages) / CHARS_PER_TOKEN)
        out = int(len(text or "") / CHARS_PER_TOKEN)
    # Anthropic input_tokens excludes cache reads/writes; OpenAI includes them.
    if _field(usage, "cache_read_input_tokens") is not None:
        inp += cached + cache_write
    cost = _number(_field(payload, "total_cost_usd"))
    if cost is None:
        cost = _number(_field(payload, "response_cost_usd"))
    if cost is None:
        cost = _number(_field(_field(payload, "_hidden_params", {}), "response_cost"))
    return {"request_id": request_id or uuid.uuid4().hex, "provider": provider, "model": model,
            "timestamp": time.time(), "input_tokens": inp, "output_tokens": out,
            "cache_read_tokens": cached, "cache_write_tokens": cache_write,
            "tokens_source": "reported" if reported else "estimated",
            "cost_usd": cost, "cost_source": "reported" if cost is not None else "unknown",
            "failed": bool(failed)}


class UsageLedger:
    """Append once per request; resumes do not count old usage a second time."""
    def __init__(self, rows=()):
        self.rows = []
        self._ids = set()
        for row in rows or ():
            if isinstance(row, dict) and row.get("request_id") not in self._ids:
                self.rows.append(dict(row))
                self._ids.add(row.get("request_id"))

    def record(self, row, entry=None):
        if row["request_id"] in self._ids:
            return None
        row = dict(row)
        if row.get("cost_usd") is None:
            from .cost_notices import cost_variant
            if cost_variant(row["provider"], row["model"], entry) is None:
                row.update(cost_usd=0.0, cost_source="free/local")
            elif not row.get("failed"):
                pricing = (entry.features or {}).get("pricing", {}) if entry else _pricing_for(row["provider"], row["model"])[0]
                in_rate = _number(pricing.get("input_usd_per_mtok"))
                out_rate = _number(pricing.get("output_usd_per_mtok"))
                if in_rate is not None and out_rate is not None:
                    # Missing cache prices remain a conservative full-price estimate.
                    cache_rate = _number(pricing.get("cache_read_usd_per_mtok"))
                    cached = min(row["input_tokens"], row.get("cache_read_tokens", 0)) if cache_rate is not None else 0
                    cost = ((row["input_tokens"] - cached) * in_rate + cached * (cache_rate or 0)
                            + row["output_tokens"] * out_rate) / 1e6
                    row.update(cost_usd=cost, cost_source="registry estimate")
        self.rows.append(row)
        self._ids.add(row["request_id"])
        return row

    @property
    def total_usd(self):
        return sum(_number(row.get("cost_usd")) or 0 for row in self.rows)

    @property
    def unknown_requests(self):
        return sum(row.get("cost_usd") is None for row in self.rows)

    def summary(self):
        sources = sorted({row.get("cost_source", "unknown") for row in self.rows})
        return (f"Completed/failed requests: {len(self.rows)}\n"
                f"Input tokens: {sum(r.get('input_tokens', 0) for r in self.rows):,}; "
                f"output tokens: {sum(r.get('output_tokens', 0) for r in self.rows):,}\n"
                f"Known cost: ${self.total_usd:.4f}; requests with unknown cost: {self.unknown_requests}\n"
                f"Sources: {', '.join(sources) or 'no requests recorded'}\n"
                "Estimated tokens use characters/4. Registry costs and subscription equivalents are not provider invoices.")


class ModelCallStopped(RuntimeError):
    pass


@contextmanager
def model_request(agent, messages, callbacks=None):
    """One accounting boundary also shared by compaction and refinement calls."""
    from types import SimpleNamespace
    from .agent_loop import TurnCallbacks
    callbacks = callbacks or getattr(agent, "model_callbacks", None) or TurnCallbacks()
    if not callbacks.before_model_call({"provider": agent.provider, "model": agent.model}):
        raise ModelCallStopped("Model request stopped before sending")
    request = SimpleNamespace(reply=None, text="", messages=messages)
    failed = True
    try:
        yield request
        failed = False
    finally:
        callbacks.on_usage(request_usage(agent.provider, agent.model, request.reply,
                                        request.messages, request.text, failed=failed))
