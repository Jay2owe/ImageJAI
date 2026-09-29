"""Provider/model catalog engine for the standalone ImageJAI console.

This is the headless port of the Fiji picker's engine layer
(``src/main/java/imagejai/engine/picker/``). It answers one question for the
TUI: *which models may the user pick right now, and what changed?*

Why a port instead of a thin HTTP client: the Swing picker already encodes
years of field rules — a 24 h on-disk cache so the dropdown opens offline, a
soft-deprecation window so a provider outage cannot retire a model the user
relies on, curator-wins/upstream-wins merge precedence, and pin/hide overrides
the user can hand-edit. The console must behave identically, and it must share
the same files on disk so running Fiji and the console side by side does not
produce two different model lists.

Everything here is pure logic: no Textual, no threads owned by the UI, no
implicit network. The HTTP fetcher, the clock, and every path are injected so
the unit tests run offline and deterministically.

Java references are cited as ``file:line`` next to each rule that was copied.
"""
from __future__ import annotations

import concurrent.futures
import json
import os
import re
import shutil
import tempfile
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field, replace
from datetime import date, datetime, timedelta, timezone
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence

# ---------------------------------------------------------------------------
# Launch policy — identifier validation
# ---------------------------------------------------------------------------

# LaunchPolicy.java:33 / :35. A model id must start alphanumeric, which is how
# OpenRouter's "~vendor/model-latest" aliases get rejected: they are listing
# artefacts, not ids any launcher can use.
_PROVIDER_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")
_MODEL_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/@+~-]{0,255}\Z")


def is_valid_provider_id(value: object) -> bool:
    """True when ``value`` is a provider id the launcher would accept."""
    return bool(isinstance(value, str) and _PROVIDER_ID_RE.match(value.strip()))


def is_valid_model_id(value: object) -> bool:
    """True when ``value`` is a model id the launcher would accept.

    LaunchPolicy.java:35. Used to skip unusable ids rather than fail a whole
    provider (ModelsCache.java:196).
    """
    return bool(isinstance(value, str) and _MODEL_ID_RE.match(value.strip()))


def require_provider_id(value: object) -> str:
    if not is_valid_provider_id(value):
        raise ValueError("Invalid provider identifier.")
    return str(value).strip()


def require_model_id(value: object) -> str:
    if not is_valid_model_id(value):
        raise ValueError("Invalid model identifier.")
    return str(value).strip()


# ---------------------------------------------------------------------------
# Canonical providers (ProviderRegistry.java:47, :86)
# ---------------------------------------------------------------------------

CANONICAL_PROVIDERS: tuple[str, ...] = (
    "anthropic", "cerebras", "deepseek", "gemini", "github-models", "groq",
    "huggingface", "mistral", "ollama", "ollama-cloud", "openai", "openrouter",
    "perplexity", "together", "xai", "lmstudio", "jan", "llamacpp", "vllm",
)

PROVIDER_DISPLAY_NAMES: dict[str, str] = {
    "anthropic": "Anthropic",
    "cerebras": "Cerebras",
    "deepseek": "DeepSeek",
    "gemini": "Gemini",
    "github-models": "GitHub Models",
    "groq": "Groq",
    "huggingface": "HuggingFace",
    "mistral": "Mistral",
    "ollama": "Ollama (local)",
    "ollama-cloud": "Ollama Cloud",
    "openai": "OpenAI",
    "openrouter": "OpenRouter",
    "perplexity": "Perplexity",
    "together": "Together AI",
    "xai": "xAI",
    "lmstudio": "LM Studio (local)",
    "jan": "Jan (local)",
    "llamacpp": "llama.cpp (local)",
    "vllm": "vLLM (local)",
}

# ProviderRegistry.java:72 — keyless local daemons are READY without a key.
LOCAL_DAEMON_PROVIDERS = frozenset({
    "ollama", "ollama-cloud", "lmstudio", "jan", "llamacpp", "vllm",
})

# ProviderDiscovery.java:113 — no public listing endpoint, curated is truth.
CURATED_ONLY = ("ollama-cloud", "perplexity")


def is_local_daemon_provider(provider_id: str) -> bool:
    return provider_id in LOCAL_DAEMON_PROVIDERS


# ---------------------------------------------------------------------------
# Tiers and badges (ModelEntry.java:32, TierBadgeIcon.java:44)
# ---------------------------------------------------------------------------


class Tier(str, Enum):
    """Curated price/access class. The yaml value is the enum value."""

    FREE = "free"
    FREE_WITH_LIMITS = "free-with-limits"
    PAID = "paid"
    REQUIRES_SUBSCRIPTION = "requires-subscription"
    UNCURATED = "uncurated"


# Accepted spellings seen in the wild: the console's own login flow writes
# "subscription" (agent/console/providers.py) while models.yaml writes
# "requires-subscription".
_TIER_ALIASES = {
    "subscription": Tier.REQUIRES_SUBSCRIPTION,
    "requires_subscription": Tier.REQUIRES_SUBSCRIPTION,
    "free-with-limits": Tier.FREE_WITH_LIMITS,
    "free_with_limits": Tier.FREE_WITH_LIMITS,
    "": Tier.UNCURATED,
}


def classify_tier(value: object) -> Tier:
    """Map a yaml/user string onto a :class:`Tier`; unknown means uncurated.

    ModelEntry.java:53 — unknown values must not crash the picker, they fall
    back to the neutral grey "uncurated" badge.
    """
    if isinstance(value, Tier):
        return value
    if value is None:
        return Tier.UNCURATED
    text = str(value).strip().lower()
    for tier in Tier:
        if tier.value == text:
            return tier
    return _TIER_ALIASES.get(text, Tier.UNCURATED)


# "retired" is not a yaml tier: it is the badge a pinned-deprecated row paints
# instead of its tier colour (TierBadgeIcon.java:41). Kept here as data so the
# TUI never re-derives the colour table.
BADGE_RETIRED = "retired"

TIER_COLOURS: dict[str, tuple[int, int, int]] = {
    Tier.FREE.value: (60, 180, 75),                    # TierBadgeIcon.java:47
    Tier.FREE_WITH_LIMITS.value: (240, 200, 50),       # TierBadgeIcon.java:48
    Tier.PAID.value: (70, 130, 220),                   # TierBadgeIcon.java:49
    Tier.REQUIRES_SUBSCRIPTION.value: (160, 95, 215),  # TierBadgeIcon.java:50
    Tier.UNCURATED.value: (200, 200, 205),             # TierBadgeIcon.java:52
    BADGE_RETIRED: (200, 60, 60),                      # TierBadgeIcon.java:44
}

# ModelMenuItem.java:309 — lay-language badge meaning, verbatim so the console
# and the plugin explain a tier with the same words.
TIER_TOOLTIPS: dict[str, str] = {
    Tier.FREE.value:
        "Free to use. No card needed, no usage caps that matter for normal sessions.",
    Tier.FREE_WITH_LIMITS.value:
        "Free, but rate-limited. You may hit a per-minute or per-day cap on long "
        "sessions \u2014 keep an eye on the status bar.",
    Tier.PAID.value:
        "You pay per use. Charges only happen if you've already added credit or a "
        "card to this provider \u2014 see hover for current rates.",
    Tier.REQUIRES_SUBSCRIPTION.value:
        "Requires an active monthly subscription with this provider. Without it the "
        "request will be refused \u2014 no surprise charges.",
    Tier.UNCURATED.value:
        "Auto-detected from the provider \u2014 pricing and reliability not yet "
        "verified. Check the provider's pricing page directly before running a long "
        "session.",
}


def tier_tooltip(tier: Tier | str | None) -> str:
    """Plain-English explanation of a tier badge (S3.22)."""
    return TIER_TOOLTIPS[classify_tier(tier).value]


def tier_colour(tier: Tier | str | None, retired: bool = False) -> tuple[int, int, int]:
    """RGB badge colour; retired rows override the tier colour with red."""
    if retired:
        return TIER_COLOURS[BADGE_RETIRED]
    return TIER_COLOURS[classify_tier(tier).value]


class Reliability(str, Enum):
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"


def classify_reliability(value: object) -> Reliability:
    """ModelEntry.java:70 — anything unrecognised is treated as low."""
    if isinstance(value, Reliability):
        return value
    text = "" if value is None else str(value).strip().lower()
    for item in Reliability:
        if item.value == text:
            return item
    return Reliability.LOW


# ---------------------------------------------------------------------------
# Catalog entry (ModelEntry.java:30)
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class CatalogEntry:
    """One provider/model row. Immutable: merge steps return copies.

    Identity is the ``(provider, model_id)`` pair, matching ModelEntry.java:194
    so a curated row and its live counterpart collapse onto one row.
    """

    provider: str
    model_id: str
    display_name: str = ""
    description: str = ""
    tier: Tier = Tier.UNCURATED
    context_window: int = 0
    vision_capable: bool = False
    reliability: Reliability = Reliability.LOW
    pinned: bool = False
    curated: bool = True
    notes: str = ""
    last_verified: date | None = None
    deprecated_since: date | None = None
    replacement: str | None = None
    features: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        object.__setattr__(self, "provider", str(self.provider))
        object.__setattr__(self, "model_id", str(self.model_id))
        if not self.display_name:
            object.__setattr__(self, "display_name", self.model_id)
        object.__setattr__(self, "tier", classify_tier(self.tier))
        object.__setattr__(self, "reliability", classify_reliability(self.reliability))

    @property
    def key(self) -> str:
        """MergeFunction.java:255 — the merge/override key is ``provider + ' ' + id``."""
        return f"{self.provider} {self.model_id}"

    @property
    def input_usd_per_mtok(self) -> float | None:
        return _price_from_features(self.features, "input")

    @property
    def output_usd_per_mtok(self) -> float | None:
        return _price_from_features(self.features, "output")

    def badge(self, today: date) -> str:
        """Badge key for this row: its tier, or ``retired`` past the window."""
        if deprecation_state(self, today) is DeprecationState.PINNED_DEPRECATED:
            return BADGE_RETIRED
        return self.tier.value

    def with_pinned(self, pinned: bool) -> "CatalogEntry":
        return replace(self, pinned=bool(pinned))

    def with_context_window(self, context_window: int) -> "CatalogEntry":
        return replace(self, context_window=int(context_window))

    def with_last_verified(self, when: date | None) -> "CatalogEntry":
        return replace(self, last_verified=when)

    def with_deprecated_since(self, when: date | None) -> "CatalogEntry":
        return replace(self, deprecated_since=when)


def _price_from_features(features: Mapping[str, Any] | None, which: str) -> float | None:
    """Read ``pricing.{input,output}_usd_per_mtok``.

    MainNotificationCheck.java:207 keeps pricing inside the same opaque feature
    map the yaml loader folds it into, so both sides read one shape.
    """
    if not isinstance(features, Mapping):
        return None
    pricing = features.get("pricing")
    if not isinstance(pricing, Mapping):
        return None
    key = "input_usd_per_mtok" if which == "input" else "output_usd_per_mtok"
    return _as_float(pricing.get(key))


def _as_float(value: object) -> float | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    try:
        return float(str(value))
    except (TypeError, ValueError):
        return None


# ---------------------------------------------------------------------------
# Soft deprecation (SoftDeprecationPolicy.java:26)
# ---------------------------------------------------------------------------

#: Days a dropped model stays visible with a strikethrough before it is hidden
#: (SoftDeprecationPolicy.java:30).
DEPRECATION_WINDOW_DAYS = 30


class DeprecationState(str, Enum):
    ACTIVE = "active"
    SOFT_DEPRECATED = "soft-deprecated"
    PINNED_DEPRECATED = "pinned-deprecated"
    HIDDEN = "hidden"


def mark_if_missing(entry: CatalogEntry, today: date) -> CatalogEntry:
    """Stamp the first day upstream stopped listing the model.

    Idempotent (SoftDeprecationPolicy.java:53): later misses must not restart
    the 30-day clock, otherwise a model could never age out.
    """
    if entry.deprecated_since is not None:
        return entry
    return entry.with_deprecated_since(today)


def clear_and_refresh(entry: CatalogEntry, today: date) -> CatalogEntry:
    """Model is listed again: clear the marker, refresh ``last_verified``.

    SoftDeprecationPolicy.java:66.
    """
    refreshed = entry.with_last_verified(today)
    if refreshed.deprecated_since is None:
        return refreshed
    return refreshed.with_deprecated_since(None)


def deprecation_state(entry: CatalogEntry, today: date) -> DeprecationState:
    """SoftDeprecationPolicy.java:76."""
    if entry is None or entry.deprecated_since is None:
        return DeprecationState.ACTIVE
    days = (today - entry.deprecated_since).days
    if days <= DEPRECATION_WINDOW_DAYS:
        return DeprecationState.SOFT_DEPRECATED
    return (DeprecationState.PINNED_DEPRECATED if entry.pinned
            else DeprecationState.HIDDEN)


def is_lifecycle_hidden(entry: CatalogEntry, today: date) -> bool:
    return deprecation_state(entry, today) is DeprecationState.HIDDEN


def deprecation_notice(entry: CatalogEntry, today: date) -> str | None:
    """User-facing sentence for a dropped model, or ``None`` when active.

    Text copied from ModelMenuItem.java:326 so the console and the plugin say
    the same thing about the same model.
    """
    if entry is None or entry.deprecated_since is None:
        return None
    state = deprecation_state(entry, today)
    if state is DeprecationState.PINNED_DEPRECATED:
        if not entry.replacement:
            return "RETIRED \u2014 calls will fail. Provider has retired this model."
        return f"RETIRED \u2014 calls will fail. Switch to {entry.replacement}."
    if state is DeprecationState.SOFT_DEPRECATED:
        suffix = f" \u2014 try {entry.replacement}" if entry.replacement else ""
        return f"No longer available since {entry.deprecated_since.isoformat()}{suffix}."
    return None


# ---------------------------------------------------------------------------
# Live discovery result (MergeFunction.java:39)
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class LiveResult:
    """One provider's discovery outcome.

    ``successful`` separates "listed nothing" from "could not be asked".
    Only a successful listing may retire a curated row (MergeFunction.java:127),
    so a transient outage can never cascade into mass deprecation.
    """

    successful: bool
    model_ids: tuple[str, ...] = ()
    meta: Mapping[str, Mapping[str, Any]] = field(default_factory=dict)
    failure_reason: str = ""
    skipped: int = 0

    @staticmethod
    def success(model_ids: Iterable[str],
                meta: Mapping[str, Mapping[str, Any]] | None = None,
                skipped: int = 0) -> "LiveResult":
        return LiveResult(True, tuple(dict.fromkeys(model_ids)), dict(meta or {}), "", skipped)

    @staticmethod
    def failure(reason: str = "") -> "LiveResult":
        return LiveResult(False, (), {}, reason or "", 0)


# ---------------------------------------------------------------------------
# Merge (MergeFunction.java:103)
# ---------------------------------------------------------------------------

UNCURATED_DESCRIPTION = "Uncurated \u2014 pricing and capabilities unknown."

_VISION_HINTS = ("vision", "vl", "gpt-4o", "gpt-5", "gemma3", "gemma4",
                 "pixtral", "llama-4-scout")
_VISION_PATTERNS = (re.compile(r".*claude-(opus|sonnet|haiku)-[4-9].*"),
                    re.compile(r".*gemini-[2-9].*"))


def _infer_vision_from_name(model_id: str) -> bool:
    """MergeFunction.java:243 — best-effort guess for an uncurated stub."""
    text = model_id.lower()
    if any(hint in text for hint in _VISION_HINTS):
        return True
    return any(pattern.match(text) for pattern in _VISION_PATTERNS)


def synthesise_stub(provider_id: str, model_id: str,
                    meta: Mapping[str, Any] | None, today: date) -> CatalogEntry:
    """Build the "auto-discovered (unverified)" row for an upstream-only model.

    MergeFunction.java:206. Marked ``curated=False`` so the TUI can render it
    below the ``\u2014 Auto-discovered (unverified) \u2014`` separator (S3.17).
    """
    context_window = 0
    vision = False
    if isinstance(meta, Mapping):
        context_window = int(_as_float(meta.get("context_length")) or 0)
        modality = meta.get("modality")
        if modality is not None and "image" in str(modality).lower():
            vision = True
    if not vision:
        vision = _infer_vision_from_name(model_id)
    return CatalogEntry(
        provider=provider_id,
        model_id=model_id,
        display_name=model_id,
        description=UNCURATED_DESCRIPTION,
        tier=Tier.UNCURATED,
        context_window=context_window,
        vision_capable=vision,
        reliability=Reliability.LOW,
        pinned=False,
        curated=False,
        notes="",
        last_verified=today,
    )


def merge(curated: Sequence[CatalogEntry],
          live: Mapping[str, LiveResult] | None,
          overrides: Mapping[str, "Override"] | None,
          today: date) -> list[CatalogEntry]:
    """Join curated x live x user overrides into the list the picker renders.

    Precedence (MergeFunction.java:30):

    * display name / description / tier / reliability / notes  -> curator wins,
      because provider payloads do not carry them consistently;
    * ``context_window`` -> upstream wins, the provider knows its own limit;
    * ``pinned`` -> curator default, user override wins.

    Curated rows keep their order, then upstream-only models are appended as
    uncurated stubs in provider order, so the list is stable between refreshes.
    """
    curated = list(curated or [])
    live = dict(live or {})
    overrides = dict(overrides or {})

    out: list[CatalogEntry] = []
    rendered: set[str] = set()

    for entry in curated:
        key = entry.key
        if key in rendered:
            continue
        rendered.add(key)
        result = live.get(entry.provider)
        merged = entry
        if result is None or not result.successful:
            pass  # No upstream signal: never touch the deprecation flag.
        elif entry.model_id in result.model_ids:
            merged = clear_and_refresh(merged, today)
            meta = result.meta.get(entry.model_id)
            if isinstance(meta, Mapping):
                upstream = _as_float(meta.get("context_length"))
                if upstream and int(upstream) > 0 and int(upstream) != merged.context_window:
                    merged = merged.with_context_window(int(upstream))
        else:
            merged = mark_if_missing(merged, today)

        override = overrides.get(key)
        if override is not None and override.pinned is not None:
            merged = merged.with_pinned(override.pinned)
        out.append(merged)

    for provider_id, result in live.items():
        if not result.successful:
            continue
        for model_id in result.model_ids:
            key = f"{provider_id} {model_id}"
            if key in rendered:
                continue
            stub = synthesise_stub(provider_id, model_id,
                                   result.meta.get(model_id), today)
            override = overrides.get(key)
            if override is not None and override.pinned is not None:
                stub = stub.with_pinned(override.pinned)
            out.append(stub)
            rendered.add(key)
    return out


def apply_visibility(merged: Sequence[CatalogEntry],
                     overrides: Mapping[str, "Override"] | None,
                     today: date) -> list[CatalogEntry]:
    """Drop user-hidden rows and rows that aged out of the deprecation window.

    MergeFunction.java:183. A pin beats a hide: the user asked for that model
    explicitly, so it stays even when hidden or long retired.
    """
    overrides = dict(overrides or {})
    out: list[CatalogEntry] = []
    for entry in merged or []:
        override = overrides.get(entry.key)
        if override is not None and override.hidden is True and not entry.pinned:
            continue
        if is_lifecycle_hidden(entry, today):
            continue
        out.append(entry)
    return out


def group_by_provider(entries: Sequence[CatalogEntry]) -> dict[str, list[CatalogEntry]]:
    """Group in canonical provider order, curated rows before uncurated ones.

    ProviderMenu.rebuildChildren splits on ``curated`` to place the
    "auto-discovered (unverified)" separator (S3.17); doing the split here
    keeps that ordering decision out of the view.
    """
    buckets: dict[str, list[CatalogEntry]] = {}
    for entry in entries or []:
        buckets.setdefault(entry.provider, []).append(entry)
    ordered: dict[str, list[CatalogEntry]] = {}
    known = [p for p in CANONICAL_PROVIDERS if p in buckets]
    extra = [p for p in buckets if p not in CANONICAL_PROVIDERS]
    for provider_id in known + extra:
        rows = buckets[provider_id]
        ordered[provider_id] = ([r for r in rows if r.curated]
                                + [r for r in rows if not r.curated])
    return ordered


# ---------------------------------------------------------------------------
# Live discovery (ProviderDiscovery.java:47)
# ---------------------------------------------------------------------------

MAX_RESPONSE_BYTES = 1024 * 1024      # ProviderDiscovery.java:49
MAX_MODEL_IDS = 2000                  # ProviderDiscovery.java:50
MAX_TIMEOUT_S = 10.0                  # ProviderDiscovery.java:51
MAX_CONCURRENT_DISCOVERIES = 4        # ProviderDiscovery.java:52
MAX_DISCOVER_ALL_S = 30.0             # ProviderDiscovery.java:53
DEFAULT_TIMEOUT_S = 5.0               # ProviderDiscovery.java:497
REFRESH_TIMEOUT_S = 4.0               # AiRootPanel user-initiated refresh (S3.9)

_CREDENTIAL_QUERY_RE = re.compile(
    r"[?&](?:key|api_?key|token|access_token|secret|password)=", re.IGNORECASE)
_CREDENTIAL_VALUE_RE = re.compile(
    r"([?&](?:key|api_?key|token|access_token|secret|password)=)[^&\s]+",
    re.IGNORECASE)


@dataclass(frozen=True)
class Endpoint:
    """A provider's ``/models`` URL plus its auth headers.

    Credentials belong in headers, never in the URL: the URL is written into
    the shared cache file (ProviderDiscovery.java:56).
    """

    provider: str
    url: str
    headers: Mapping[str, str] = field(default_factory=dict)

    def __post_init__(self) -> None:
        object.__setattr__(self, "provider", require_provider_id(self.provider))
        url = (self.url or "").strip()
        if not url or _CREDENTIAL_QUERY_RE.search(url) or "@" in url.split("//")[-1].split("/")[0]:
            raise ValueError("Provider endpoint must not contain credentials.")
        object.__setattr__(self, "url", url)
        object.__setattr__(self, "headers", dict(self.headers or {}))


@dataclass(frozen=True)
class HttpResponse:
    """Fetcher result. ``error`` set means the request never completed."""

    status: int = -1
    body: str = ""
    error: BaseException | None = None

    @property
    def ok(self) -> bool:
        return self.error is None and 200 <= self.status < 300


#: ``fetch(endpoint, timeout_seconds) -> HttpResponse``
Fetcher = Callable[[Endpoint, float], HttpResponse]


def _auth_bearer(key: str | None) -> dict[str, str]:
    return {"Authorization": f"Bearer {key}"} if key else {}


def _anthropic_headers(key: str | None) -> dict[str, str]:
    headers: dict[str, str] = {}
    if key:
        headers["x-api-key"] = key
    headers["anthropic-version"] = "2023-06-01"  # ProviderDiscovery.java:214
    return headers


def _google_headers(key: str | None) -> dict[str, str]:
    return {"x-goog-api-key": key} if key else {}


def _local_base(override: str | None, default_host: str) -> str:
    """Normalise a local server base URL so ``/v1/models`` appends cleanly.

    ProviderDiscovery.java:186 — the saved override may already end in ``/v1``.
    """
    base = (override or "").strip() or default_host
    base = base.rstrip("/")
    if base.endswith("/v1"):
        base = base[: -len("/v1")]
    return base


def default_endpoints(credentials: Mapping[str, str] | None = None) -> dict[str, Endpoint]:
    """The canonical endpoint table (ProviderDiscovery.java:122, S3.10).

    ``credentials`` maps provider id -> API key, except for the keyless local
    servers where the value is a base-URL override.
    """
    creds = dict(credentials or {})
    out: dict[str, Endpoint] = {}

    def add(provider: str, url: str, headers: Mapping[str, str] | None = None) -> None:
        out[provider] = Endpoint(provider, url, headers or {})

    add("ollama", "http://localhost:11434/api/tags")
    add("openai", "https://api.openai.com/v1/models", _auth_bearer(creds.get("openai")))
    add("anthropic", "https://api.anthropic.com/v1/models",
        _anthropic_headers(creds.get("anthropic")))
    add("gemini", "https://generativelanguage.googleapis.com/v1beta/models",
        _google_headers(creds.get("gemini")))
    add("groq", "https://api.groq.com/openai/v1/models", _auth_bearer(creds.get("groq")))
    add("cerebras", "https://api.cerebras.ai/v1/models", _auth_bearer(creds.get("cerebras")))
    add("openrouter", "https://openrouter.ai/api/v1/models")
    add("github-models", "https://models.github.ai/catalog/models",
        _auth_bearer(creds.get("github-models")))
    add("mistral", "https://api.mistral.ai/v1/models", _auth_bearer(creds.get("mistral")))
    add("together", "https://api.together.xyz/v1/models", _auth_bearer(creds.get("together")))
    add("huggingface", "https://router.huggingface.co/v1/models",
        _auth_bearer(creds.get("huggingface")))
    add("deepseek", "https://api.deepseek.com/v1/models", _auth_bearer(creds.get("deepseek")))
    add("xai", "https://api.x.ai/v1/models", _auth_bearer(creds.get("xai")))
    add("lmstudio", _local_base(creds.get("lmstudio"), "http://localhost:1234") + "/v1/models")
    add("jan", _local_base(creds.get("jan"), "http://localhost:1337") + "/v1/models")
    add("llamacpp", _local_base(creds.get("llamacpp"), "http://localhost:8080") + "/v1/models")
    add("vllm", _local_base(creds.get("vllm"), "http://localhost:8000") + "/v1/models")
    return out


def _collect_key(payload: Any, key: str, out: list[str]) -> None:
    """Depth-first collect every string value stored under ``key``."""
    if len(out) >= MAX_MODEL_IDS:
        return
    if isinstance(payload, Mapping):
        value = payload.get(key)
        if isinstance(value, str) and value:
            out.append(value)
        for item in payload.values():
            _collect_key(item, key, out)
    elif isinstance(payload, (list, tuple)):
        for item in payload:
            _collect_key(item, key, out)


def _regex_key(body: str, key: str) -> list[str]:
    """Fallback scan used when the payload is not valid JSON.

    ProviderDiscovery.java:404 parses by regex only; we keep it as a fallback
    so a provider that answers with slightly broken JSON still yields a list.
    """
    pattern = re.compile(r'"' + re.escape(key) + r'"\s*:\s*"([^"]+)"')
    return pattern.findall(body)[:MAX_MODEL_IDS]


def _values_for(body: str, key: str) -> list[str]:
    try:
        payload = json.loads(body)
    except (ValueError, TypeError):
        return _regex_key(body, key)
    out: list[str] = []
    _collect_key(payload, key, out)
    return out[:MAX_MODEL_IDS]


def parse_model_ids(provider_id: str, body: str | None) -> list[str]:
    """Extract model ids from one provider's listing payload (S3.10).

    Shapes per ProviderDiscovery.java:378: Ollama exposes ``name``, GitHub
    Models joins ``publisher + "/" + name``, Gemini strips the ``models/``
    prefix, everyone else uses the OpenAI ``data[].id`` shape.
    """
    if not body:
        return []
    seen: dict[str, None] = {}
    if provider_id == "ollama":
        values = _values_for(body, "name")
    elif provider_id == "github-models":
        names = _values_for(body, "name")
        publishers = _values_for(body, "publisher")
        values = [
            (f"{publishers[i]}/{names[i]}" if i < len(publishers) and publishers[i] else names[i])
            for i in range(len(names))
        ]
    elif provider_id == "gemini":
        values = [v[len("models/"):] if v.startswith("models/") else v
                  for v in _values_for(body, "name")]
    else:
        values = _values_for(body, "id")
    for value in values:
        if len(seen) >= MAX_MODEL_IDS:
            break
        seen[value] = None
    return list(seen)


def _redact(text: str | None, endpoint: Endpoint | None) -> str:
    """Never let a key reach a log line or an error dialog."""
    safe = text or ""
    if endpoint is not None:
        for value in endpoint.headers.values():
            if not value or not value.strip():
                continue
            safe = safe.replace(value, "[REDACTED]")
            if value.startswith("Bearer ") and len(value) > len("Bearer "):
                safe = safe.replace(value[len("Bearer "):], "[REDACTED]")
    return _CREDENTIAL_VALUE_RE.sub(r"\1[REDACTED]", safe)


def describe_failure(response: HttpResponse, endpoint: Endpoint | None) -> str:
    """Short, credential-free reason string (ProviderDiscovery.java:295)."""
    if response.error is not None:
        message = _redact(str(response.error), endpoint)
        name = type(response.error).__name__
        return f"{name}: {message}" if message else name
    body = _redact(response.body, endpoint).strip()
    if len(body) > 240:
        body = body[:240] + "\u2026"
    if not body:
        return f"HTTP {response.status} from provider"
    return f"HTTP {response.status} \u2014 {body}"


def _clamp_timeout(timeout: float | None) -> float:
    """ProviderDiscovery.java:493 — non-positive means default, 10 s is the cap."""
    if timeout is None or timeout <= 0:
        return DEFAULT_TIMEOUT_S
    return min(float(timeout), MAX_TIMEOUT_S)


def urllib_fetcher(endpoint: Endpoint, timeout: float) -> HttpResponse:
    """Default fetcher. Never used in tests — the engine takes an injected one.

    Redirects are not followed, so a credential header cannot be replayed to a
    redirect target (ProviderDiscovery.java:456).
    """

    class _NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D102
            return None

    opener = urllib.request.build_opener(_NoRedirect)
    request = urllib.request.Request(endpoint.url, method="GET")
    for name, value in endpoint.headers.items():
        request.add_header(name, value)
    try:
        with opener.open(request, timeout=_clamp_timeout(timeout)) as response:
            body = response.read(MAX_RESPONSE_BYTES + 1)
            if len(body) > MAX_RESPONSE_BYTES:
                return HttpResponse(error=OSError(
                    f"provider response exceeded {MAX_RESPONSE_BYTES} byte limit"))
            return HttpResponse(getattr(response, "status", 200),
                                body.decode("utf-8", "replace"))
    except urllib.error.HTTPError as http_error:
        try:
            body = http_error.read(MAX_RESPONSE_BYTES).decode("utf-8", "replace")
        except Exception:  # pragma: no cover - error body is best effort
            body = ""
        return HttpResponse(http_error.code, body)
    except Exception as failure:
        return HttpResponse(error=failure)


class ProviderDiscovery:
    """Fan-out over the provider ``/models`` endpoints (S3.9).

    The fetcher is injected so the whole class is testable without a socket.
    """

    def __init__(self, endpoints: Mapping[str, Endpoint] | None = None,
                 fetcher: Fetcher | None = None,
                 max_workers: int = MAX_CONCURRENT_DISCOVERIES) -> None:
        self.endpoints: dict[str, Endpoint] = dict(endpoints or {})
        self.fetcher: Fetcher = fetcher or urllib_fetcher
        self.max_workers = max(1, int(max_workers))
        self._last_errors: dict[str, str] = {}

    def last_error_for(self, provider_id: str) -> str | None:
        """Most recent failure reason, or ``None`` after a success (S3.19)."""
        return self._last_errors.get(provider_id)

    def _fail(self, provider_id: str, reason: str) -> LiveResult:
        self._last_errors[provider_id] = reason
        return LiveResult.failure(reason)

    def discover(self, provider_id: str, timeout: float = REFRESH_TIMEOUT_S) -> LiveResult:
        """Fetch and parse one provider.

        Model ids the launch policy refuses are skipped and counted rather than
        failing the provider: OpenRouter publishes ``~vendor/model`` aliases
        that no launcher can use (ModelsCache.java:196). The plugin filters
        these only on the cache path, which lets an unusable alias reach the
        dropdown; filtering here closes that hole for the console.
        """
        if provider_id in CURATED_ONLY:
            # No listing endpoint: curated rows are authoritative and this is
            # not an error (ProviderDiscovery.java:258).
            self._last_errors.pop(provider_id, None)
            return LiveResult.failure()
        endpoint = self.endpoints.get(provider_id)
        if endpoint is None:
            return self._fail(provider_id, f"no endpoint configured for provider {provider_id}")
        try:
            response = self.fetcher(endpoint, _clamp_timeout(timeout))
        except Exception as failure:
            return self._fail(provider_id, f"discovery failed ({type(failure).__name__})")
        if response is None:
            return self._fail(provider_id, "discovery returned no response")
        if not response.ok:
            return self._fail(provider_id, describe_failure(response, endpoint))
        body = response.body or ""
        if len(body.encode("utf-8", "replace")) > MAX_RESPONSE_BYTES:
            return self._fail(
                provider_id, f"provider response exceeded {MAX_RESPONSE_BYTES} byte limit")
        self._last_errors.pop(provider_id, None)
        raw_ids = parse_model_ids(provider_id, body)
        usable = [i for i in raw_ids if is_valid_model_id(i)]
        return LiveResult.success(usable, skipped=len(raw_ids) - len(usable))

    def discover_all(self, timeout: float = REFRESH_TIMEOUT_S,
                     providers: Sequence[str] | None = None) -> dict[str, LiveResult]:
        """Discover every endpoint in parallel, bounded by the Java budgets.

        Per-provider timeout is the fetcher's business; the overall wall clock
        is capped at 30 s (ProviderDiscovery.java:53) so a hung provider cannot
        block a refresh forever.
        """
        ids = list(providers if providers is not None else self.endpoints.keys())
        out: dict[str, LiveResult] = {}
        if not ids:
            return out
        per_call = _clamp_timeout(timeout)
        workers = min(self.max_workers, len(ids))
        waves = (len(ids) + workers - 1) // workers
        deadline = time.monotonic() + min(MAX_DISCOVER_ALL_S, per_call * waves + 1.0)
        with concurrent.futures.ThreadPoolExecutor(
                max_workers=workers, thread_name_prefix="imagejai-discovery") as pool:
            futures = {pool.submit(self.discover, pid, per_call): pid for pid in ids}
            for future, provider_id in futures.items():
                remaining = max(0.0, deadline - time.monotonic())
                try:
                    out[provider_id] = future.result(timeout=remaining)
                except concurrent.futures.TimeoutError:
                    future.cancel()
                    out[provider_id] = self._fail(provider_id, "discovery timed out")
                except Exception as failure:
                    out[provider_id] = self._fail(
                        provider_id, f"discovery failed ({type(failure).__name__})")
        return {pid: out[pid] for pid in ids if pid in out}


# ---------------------------------------------------------------------------
# On-disk cache (ModelsCache.java:51)
# ---------------------------------------------------------------------------

CACHE_TTL = timedelta(hours=24)        # ModelsCache.java:51
MAX_CACHE_BYTES = 1024 * 1024          # ModelsCache.java:52
MAX_CACHED_MODELS = 2048               # ModelsCache.java:53
STALE_TEMP_SECONDS = 60 * 60           # ModelsCache.java:55


def config_root() -> Path:
    """``~/.imagej-ai`` (or ``IMAGEJAI_HOME``) — shared with the plugin.

    Read at call time, not import time, so a test that redirects
    ``IMAGEJAI_HOME`` never touches the real config directory.
    """
    env = os.environ.get("IMAGEJAI_HOME")
    if env:
        return Path(env)
    from .config import CONFIG_DIR
    return CONFIG_DIR


def default_cache_root() -> Path:
    """``<config>/cache/models`` — the exact directory the plugin writes (S3.12)."""
    return config_root() / "cache" / "models"


def default_overrides_path() -> Path:
    """Platform path of ``models_local.yaml`` (ModelsLocalLoader.java:80).

    Deliberately the plugin's own location, not the console config dir, so a
    pin made in Fiji shows up in the console and the other way round.
    """
    env = os.environ.get("IMAGEJAI_MODELS_LOCAL")
    if env:
        return Path(env)
    home = Path.home()
    if os.name == "nt":
        appdata = os.environ.get("APPDATA")
        base = Path(appdata) / "imagejai" if appdata else home / ".imagejai"
    elif sys_platform_is_mac():
        base = home / "Library" / "Application Support" / "imagejai"
    else:
        xdg = os.environ.get("XDG_CONFIG_HOME")
        base = (Path(xdg) if xdg else home / ".config") / "imagejai"
    return base / "models_local.yaml"


def sys_platform_is_mac() -> bool:
    import sys
    return sys.platform == "darwin"


@dataclass(frozen=True)
class CacheSnapshot:
    """What one ``<provider>.json`` file holds."""

    provider: str
    fetched_at: datetime
    model_ids: tuple[str, ...] = ()
    endpoint: str = ""

    def age(self, now: datetime) -> timedelta:
        return now - self.fetched_at


def _parse_instant(text: str) -> datetime:
    """Parse the Java ``Instant.toString()`` form (always UTC, ``Z`` suffix)."""
    value = (text or "").strip()
    if value.endswith("Z"):
        value = value[:-1] + "+00:00"
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def _format_instant(when: datetime) -> str:
    """Render as Java does so the plugin can read our files back."""
    if when.tzinfo is None:
        when = when.replace(tzinfo=timezone.utc)
    text = when.astimezone(timezone.utc).isoformat(timespec="seconds")
    return text.replace("+00:00", "Z")


def credential_free_endpoint(endpoint: str | None) -> str:
    """Strip any query/user-info before the URL is written to disk.

    ModelsCache.java:263 — the cache file is plain JSON in the user's profile;
    a key must never land in it.
    """
    candidate = (endpoint or "").strip()
    if not candidate:
        return ""
    from urllib.parse import urlsplit, urlunsplit
    parts = urlsplit(candidate)
    if parts.scheme and parts.hostname:
        netloc = parts.hostname
        if parts.port:
            netloc += f":{parts.port}"
        return urlunsplit((parts.scheme, netloc, parts.path, "", ""))
    if re.search(r"(?:key|api_?key|token|secret|password)=", candidate, re.IGNORECASE):
        return ""
    return candidate


class CacheReadError(RuntimeError):
    """Cache file exists but cannot be trusted; caller falls back to live/curated."""

    def __init__(self, code: str, path: Path, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.path = path


class ModelsCache:
    """24 h per-provider cache in the plugin's own directory and file shape.

    Shape (ModelsCache.java:227)::

        {"provider": "openai", "fetched_at": "2026-05-01T09:00:00Z",
         "endpoint": "https://api.openai.com/v1/models", "models": ["id", ...]}

    The console and the plugin therefore share one cache: opening the console
    right after Fiji refreshed costs no network call.
    """

    TTL = CACHE_TTL

    def __init__(self, root: Path | str | None = None,
                 sleep: Callable[[float], None] = time.sleep) -> None:
        self.root = Path(root) if root is not None else default_cache_root()
        self._sleep = sleep
        self.last_rejected_count = 0

    def path_for(self, provider_id: str) -> Path:
        return self.root / (require_provider_id(provider_id) + ".json")

    def has(self, provider_id: str) -> bool:
        return self.path_for(provider_id).is_file()

    def read(self, provider_id: str) -> CacheSnapshot | None:
        """Return the cached snapshot, or ``None`` when there is no usable file.

        A malformed or oversized file raises :class:`CacheReadError` so the
        caller can report it instead of silently pretending the cache is empty.
        """
        path = self.path_for(provider_id)
        try:
            if not path.exists():
                return None
            if not path.is_file():
                raise CacheReadError("not_regular", path, "Model cache is not a regular file.")
            if path.stat().st_size > MAX_CACHE_BYTES:
                raise CacheReadError(
                    "too_large", path,
                    f"Model cache exceeds safety cap of {MAX_CACHE_BYTES} bytes.")
            raw = path.read_text(encoding="utf-8")
        except OSError as failure:
            raise CacheReadError("unreadable", path,
                                 f"Could not read model cache: {failure}") from failure
        try:
            payload = json.loads(raw)
            fetched_at = _parse_instant(str(payload["fetched_at"]))
            models = payload.get("models") or []
            if len(models) > MAX_CACHED_MODELS:
                raise CacheReadError(
                    "model_cap", path,
                    f"Model cache exceeds safety cap of {MAX_CACHED_MODELS} models.")
            ids = []
            for item in models:
                # Forward compatible with [{"id": ...}] payloads (ModelsCache.java:288).
                value = item.get("id") if isinstance(item, Mapping) else item
                if is_valid_model_id(value):
                    ids.append(str(value).strip())
        except CacheReadError:
            raise
        except Exception as failure:
            raise CacheReadError("malformed", path,
                                 f"Could not parse model cache: {failure}") from failure
        return CacheSnapshot(provider_id, fetched_at, tuple(ids),
                             str(payload.get("endpoint") or ""))

    def is_fresh(self, provider_id: str, now: datetime) -> bool:
        """True when a snapshot exists and is younger than 24 h."""
        try:
            snapshot = self.read(provider_id)
        except CacheReadError:
            return False
        if snapshot is None:
            return False
        return snapshot.age(now) < self.TTL

    def write(self, provider_id: str, fetched_at: datetime, endpoint: str | None,
              model_ids: Iterable[str]) -> int:
        """Persist one provider's list; returns how many ids were skipped.

        Unusable ids are dropped rather than failing the provider
        (ModelsCache.java:196): one OpenRouter alias must not cost a provider
        its whole cache entry.
        """
        provider_id = require_provider_id(provider_id)
        ids: list[str] = []
        rejected = 0
        for raw in model_ids or []:
            if not is_valid_model_id(raw):
                rejected += 1
                continue
            value = str(raw).strip()
            if value in ids:
                continue
            if len(ids) >= MAX_CACHED_MODELS:
                raise ValueError(
                    f"Model cache exceeds safety cap of {MAX_CACHED_MODELS} models.")
            ids.append(value)
        self.last_rejected_count = rejected

        self.root.mkdir(parents=True, exist_ok=True)
        self.sweep_stale_temp_files()
        target = self.path_for(provider_id)
        body = (
            "{\n"
            f"  \"provider\": {json.dumps(provider_id)},\n"
            f"  \"fetched_at\": {json.dumps(_format_instant(fetched_at))},\n"
            f"  \"endpoint\": {json.dumps(credential_free_endpoint(endpoint))},\n"
            "  \"models\": [" + ", ".join(json.dumps(i) for i in ids) + "]\n"
            "}\n"
        )
        handle, tmp_name = tempfile.mkstemp(prefix=provider_id + "-", suffix=".tmp",
                                            dir=str(self.root))
        tmp = Path(tmp_name)
        placed = False
        try:
            with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as stream:
                stream.write(body)
            self._move_into_place(tmp, target)
            placed = True
        finally:
            if not placed:
                # A failed write must not litter the shared cache directory.
                try:
                    tmp.unlink()
                except OSError:
                    pass
        return rejected

    def _move_into_place(self, tmp: Path, target: Path) -> None:
        """Rename the finished temp file over the cache slot.

        ``os.replace`` is atomic, but on Windows it fails while a virus
        scanner, a sync client, or a second Fiji holds the target open. The
        temp file is already complete, so retrying and finally falling back to
        a non-atomic copy is still safe (ModelsCache.java:257).
        """
        last: OSError | None = None
        for attempt in range(5):
            try:
                os.replace(tmp, target)
                return
            except OSError as retryable:
                last = retryable
                self._sleep(0.05 * (attempt + 1))
        try:
            shutil.copyfile(tmp, target)  # non-atomic Windows fallback
            tmp.unlink()
            return
        except OSError as fallback_failure:
            raise (last or fallback_failure)

    def sweep_stale_temp_files(self, now: float | None = None) -> int:
        """Delete ``*.tmp`` files abandoned by a crash or a failed rename."""
        cutoff = (time.time() if now is None else now) - STALE_TEMP_SECONDS
        removed = 0
        try:
            entries = list(self.root.glob("*.tmp"))
        except OSError:
            return 0
        for entry in entries:
            try:
                if entry.stat().st_mtime < cutoff:
                    entry.unlink()
                    removed += 1
            except OSError:
                # Another process may own it; the next sweep tries again.
                continue
        return removed


# ---------------------------------------------------------------------------
# User overrides — pins and hides (ModelsLocalLoader.java:37)
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Override:
    """One row of ``models_local.yaml``. ``None`` means "no opinion"."""

    provider: str
    model_id: str
    pinned: bool | None = None
    hidden: bool | None = None

    @property
    def key(self) -> str:
        return f"{self.provider} {self.model_id}"


def _opt_bool(value: object) -> bool | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return value
    text = str(value).strip().lower()
    if text in ("true", "yes"):
        return True
    if text in ("false", "no"):
        return False
    return None


class OverridesStore:
    """Read/write the user's pin and hide file.

    The file is user-editable, so it is parsed with ``yaml.safe_load`` and a
    corrupt file is reported through :attr:`last_error` and treated as empty —
    a typo must not empty the model list (ModelsLocalLoader.java:102).
    """

    def __init__(self, path: Path | str | None = None) -> None:
        self.path = Path(path) if path is not None else default_overrides_path()
        self.last_error = ""

    def load(self) -> list[Override]:
        self.last_error = ""
        if not self.path.is_file():
            return []
        try:
            import yaml
            data = yaml.safe_load(self.path.read_text(encoding="utf-8"))
        except Exception as failure:
            self.last_error = f"Model overrides failed to load ({type(failure).__name__})."
            return []
        if not isinstance(data, Mapping):
            self.last_error = "Model overrides are corrupt: top-level YAML is not an object."
            return []
        rows = data.get("overrides")
        if not isinstance(rows, list):
            self.last_error = "Model overrides are corrupt: missing overrides list."
            return []
        out: list[Override] = []
        for row in rows:
            if not isinstance(row, Mapping):
                continue
            provider = row.get("provider")
            model_id = row.get("model_id")
            if not provider or not model_id:
                continue
            out.append(Override(str(provider).strip().lower(), str(model_id).strip(),
                                _opt_bool(row.get("pinned")), _opt_bool(row.get("hidden"))))
        return out

    def load_as_map(self) -> dict[str, Override]:
        return {o.key: o for o in self.load()}

    def save(self, overrides: Iterable[Override]) -> None:
        """Write atomically; the same header the plugin writes (S3.15)."""
        parent = self.path.parent
        parent.mkdir(parents=True, exist_ok=True)
        lines = [
            "# Managed by the ImageJAI dropdown \u2014 pin/hide overrides.\n",
            "# Hand-edits preserved; the dropdown only writes keys you have toggled.\n",
            "version: 1\n",
            "overrides:\n",
        ]
        for item in overrides:
            lines.append(f"  - provider: {item.provider}\n")
            lines.append(f"    model_id: {item.model_id}\n")
            if item.pinned is not None:
                lines.append(f"    pinned: {str(item.pinned).lower()}\n")
            if item.hidden is not None:
                lines.append(f"    hidden: {str(item.hidden).lower()}\n")
        handle, tmp_name = tempfile.mkstemp(prefix="models_local-", suffix=".tmp",
                                            dir=str(parent))
        tmp = Path(tmp_name)
        try:
            with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as stream:
                stream.write("".join(lines))
            os.replace(tmp, self.path)
        except OSError:
            try:
                tmp.unlink()
            except OSError:
                pass
            raise

    def _set(self, provider_id: str, model_id: str, *,
             pinned: bool | None = None, hidden: bool | None = None) -> Override:
        current = self.load_as_map()
        key = f"{provider_id} {model_id}"
        existing = current.get(key)
        merged = Override(
            provider_id, model_id,
            pinned if pinned is not None else (existing.pinned if existing else None),
            hidden if hidden is not None else (existing.hidden if existing else None))
        current[key] = merged
        self.save(list(current.values()))
        return merged

    def set_pinned(self, provider_id: str, model_id: str, pinned: bool) -> Override:
        return self._set(provider_id, model_id, pinned=bool(pinned))

    def set_hidden(self, provider_id: str, model_id: str, hidden: bool) -> Override:
        return self._set(provider_id, model_id, hidden=bool(hidden))


# ---------------------------------------------------------------------------
# Curated catalogue (ModelsYamlLoader.java:40)
# ---------------------------------------------------------------------------


def _as_date(value: object) -> date | None:
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    if value is None:
        return None
    try:
        return date.fromisoformat(str(value).strip())
    except ValueError:
        return None


def _as_int(value: object, default: int = 0) -> int:
    parsed = _as_float(value)
    return default if parsed is None else int(parsed)


def find_models_yaml() -> Path | None:
    """Locate ``agent/providers/models.yaml`` through the workspace rules."""
    try:
        from .workspace import find_workspace
        workspace = find_workspace()
    except Exception:
        workspace = None
    candidates = []
    if workspace is not None:
        candidates.append(workspace / "providers" / "models.yaml")
    here = Path(__file__).resolve()
    for parent in here.parents:
        if parent.name == "agent":
            candidates.append(parent / "providers" / "models.yaml")
            break
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return None


def load_curated(path: Path | str | None = None) -> list[CatalogEntry]:
    """Parse the bundled curated registry (S3.16).

    Rows for unknown providers are skipped, exactly as ModelsYamlLoader.java:56
    does, so a typo in the yaml cannot invent a provider the launcher has no
    client for. ``pricing`` / ``pricing_changes`` are folded into ``features``
    to match the Java shape the change detector reads.
    """
    yaml_path = Path(path) if path is not None else find_models_yaml()
    if yaml_path is None or not yaml_path.is_file():
        return []
    try:
        from .yaml_io import safe_load
        data = safe_load(yaml_path.read_text(encoding="utf-8")) or {}
    except Exception:
        return []
    rows = data.get("models") if isinstance(data, Mapping) else None
    if not isinstance(rows, list):
        return []
    known = set(CANONICAL_PROVIDERS)
    out: list[CatalogEntry] = []
    for row in rows:
        if not isinstance(row, Mapping):
            continue
        provider = str(row.get("provider") or "").strip().lower()
        model_id = str(row.get("model_id") or "").strip()
        if not provider or not model_id or provider not in known:
            continue
        features: dict[str, Any] = {}
        native = row.get("native_features")
        if isinstance(native, Mapping):
            features.update({str(k): v for k, v in native.items() if v is not None})
        if isinstance(row.get("pricing"), Mapping):
            features["pricing"] = dict(row["pricing"])
        if isinstance(row.get("pricing_changes"), list):
            features["pricing_changes"] = list(row["pricing_changes"])
        out.append(CatalogEntry(
            provider=provider,
            model_id=model_id,
            display_name=str(row.get("display_name") or model_id).strip(),
            description=str(row.get("description") or "").strip(),
            tier=classify_tier(row.get("tier")),
            context_window=_as_int(row.get("context_window"), 0),
            vision_capable=bool(row.get("vision_capable", False)),
            reliability=classify_reliability(row.get("tool_call_reliability")),
            pinned=bool(row.get("pinned", False)),
            curated=bool(row.get("curated", True)),
            notes=str(row.get("notes") or "").strip(),
            last_verified=_as_date(row.get("last_verified")),
            deprecated_since=_as_date(row.get("deprecated_since")),
            replacement=(str(row["replacement"]).strip()
                         if row.get("replacement") else None),
            features=features,
        ))
    return out


# ---------------------------------------------------------------------------
# Change detection since the last refresh (MainNotificationCheck.java:28, S3.34)
# ---------------------------------------------------------------------------

PRICE_CHANGE_THRESHOLD = 0.10   # MainNotificationCheck.java:80
PRE_WARNING_DAYS = 7            # MainNotificationCheck.java:77


class Severity(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"


@dataclass(frozen=True)
class CatalogChange:
    """One thing the user should know about before they launch."""

    key: str
    kind: str              # "tier" | "price" | "scheduled"
    severity: Severity
    title: str
    body: str

    @property
    def provider(self) -> str:
        return self.key.split(" ", 1)[0]

    @property
    def model_id(self) -> str:
        parts = self.key.split(" ", 1)
        return parts[1] if len(parts) > 1 else ""


def snapshot_of(entries: Sequence[CatalogEntry]) -> dict[str, dict[str, Any]]:
    """Reduce a merged list to the fields the change detector compares."""
    out: dict[str, dict[str, Any]] = {}
    for entry in entries or []:
        out[entry.key] = {
            "display_name": entry.display_name,
            "tier": entry.tier.value,
            "input_usd_per_mtok": entry.input_usd_per_mtok,
            "output_usd_per_mtok": entry.output_usd_per_mtok,
        }
    return out


def _crosses_threshold(previous: float | None, current: float | None) -> bool:
    if previous is None or current is None or previous <= 0:
        return False
    return abs(current - previous) / previous >= PRICE_CHANGE_THRESHOLD


def _price_sentence(seen_in: float | None, now_in: float | None,
                    seen_out: float | None, now_out: float | None) -> str:
    """MainNotificationCheck.java:174 wording, kept verbatim."""
    parts = []
    if seen_in is not None and now_in is not None:
        parts.append(f"Input was ${seen_in}/M, now ${now_in}/M.")
    if seen_out is not None and now_out is not None:
        parts.append(f"Output was ${seen_out}/M, now ${now_out}/M.")
    return " ".join(parts)


def scheduled_changes(entries: Sequence[CatalogEntry], today: date,
                      only_keys: Iterable[str] | None = None) -> list[CatalogChange]:
    """Pre-warn about ``pricing_changes:`` entries inside the 7-day window.

    MainNotificationCheck.java:139. Fires between T-7 and T-0 so a user who
    opens the console the week before a price rise is told once, not surprised
    on the invoice.
    """
    wanted = set(only_keys) if only_keys is not None else None
    out: list[CatalogChange] = []
    for entry in entries or []:
        if wanted is not None and entry.key not in wanted:
            continue
        changes = entry.features.get("pricing_changes") if entry.features else None
        if not isinstance(changes, list):
            continue
        for raw in changes:
            if not isinstance(raw, Mapping):
                continue
            effective = _as_date(raw.get("effective_date"))
            if effective is None:
                continue
            days = (effective - today).days
            if days < 0 or days > PRE_WARNING_DAYS:
                continue
            reason = str(raw.get("reason") or "").strip()
            prefix = f"{reason} \u2014 pricing change scheduled " if reason else "Pricing change scheduled "
            out.append(CatalogChange(
                entry.key, "scheduled", Severity.MEDIUM,
                f"{entry.provider}/{entry.model_id}: pricing change on {effective.isoformat()}",
                f"{prefix}for {effective.isoformat()} (T-{days} days)."))
    return out


def detect_changes(previous: Mapping[str, Mapping[str, Any]] | Sequence[CatalogEntry] | None,
                   current: Sequence[CatalogEntry],
                   today: date,
                   only_keys: Iterable[str] | None = None) -> list[CatalogChange]:
    """Compare this refresh with the previous one (S3.34).

    A tier move is always high severity — "free" turning into "paid" is the
    change that can cost a user money without them noticing. A price move
    counts only past 10%, and only rises are high severity.
    """
    if previous is None:
        previous_map: Mapping[str, Mapping[str, Any]] = {}
    elif isinstance(previous, Mapping):
        previous_map = previous
    else:
        previous_map = snapshot_of(previous)
    wanted = set(only_keys) if only_keys is not None else None

    out: list[CatalogChange] = []
    for entry in current or []:
        if wanted is not None and entry.key not in wanted:
            continue
        before = previous_map.get(entry.key)
        if not before:
            continue
        old_tier = before.get("tier")
        new_tier = entry.tier.value
        if old_tier and new_tier and old_tier != new_tier:
            out.append(CatalogChange(
                entry.key, "tier", Severity.HIGH,
                f"{entry.display_name}: tier changed",
                f"Was {old_tier}, now {new_tier}. "
                "Open the dropdown to confirm before launching."))
            continue
        seen_in = _as_float(before.get("input_usd_per_mtok"))
        seen_out = _as_float(before.get("output_usd_per_mtok"))
        now_in = entry.input_usd_per_mtok
        now_out = entry.output_usd_per_mtok
        if _crosses_threshold(seen_in, now_in) or _crosses_threshold(seen_out, now_out):
            went_up = ((seen_in is not None and now_in is not None and now_in > seen_in)
                       or (seen_out is not None and now_out is not None and now_out > seen_out))
            out.append(CatalogChange(
                entry.key, "price", Severity.HIGH if went_up else Severity.LOW,
                f"{entry.display_name}: price changed",
                _price_sentence(seen_in, now_in, seen_out, now_out)))
    out.extend(scheduled_changes(current, today, only_keys))
    return out


def filter_dismissed(changes: Sequence[CatalogChange],
                     dismissed: Iterable[str] | None) -> list[CatalogChange]:
    """Drop banners the user already dismissed (MainNotificationCheck.java:185)."""
    hidden = set(dismissed or ())
    return [c for c in changes or [] if c.key not in hidden]


# ---------------------------------------------------------------------------
# Refresh entry point
# ---------------------------------------------------------------------------

STATUS_OK = "ok"
STATUS_CACHED = "cached"
STATUS_FAILED = "failed"
STATUS_CURATED_ONLY = "curated-only"

UI_READY = "ready"
UI_NEEDS_SETUP = "needs-setup"
UI_UNAVAILABLE = "unavailable"


@dataclass(frozen=True)
class ProviderStatus:
    """What happened to one provider during a refresh (S3.33)."""

    provider: str
    state: str
    reason: str = ""
    model_count: int = 0
    skipped: int = 0
    has_credential: bool = False
    cached_at: datetime | None = None

    @property
    def display_name(self) -> str:
        return PROVIDER_DISPLAY_NAMES.get(self.provider, self.provider)

    @property
    def ok(self) -> bool:
        return self.state in (STATUS_OK, STATUS_CURATED_ONLY)

    @property
    def ui_status(self) -> str:
        """Glyph state the picker paints: ready / needs setup / unavailable.

        AiRootPanel.java:1606 — a failed provider with a key is "unavailable"
        (the key is fine, the endpoint is not); without a key it is "needs
        setup", because that is the action the user can actually take.
        """
        if self.state in (STATUS_OK, STATUS_CURATED_ONLY):
            return UI_READY
        if is_local_daemon_provider(self.provider) and self.state == STATUS_CACHED:
            return UI_UNAVAILABLE
        return UI_UNAVAILABLE if self.has_credential else UI_NEEDS_SETUP


@dataclass(frozen=True)
class RefreshResult:
    """Everything the TUI needs to redraw the picker after a refresh (S3.8)."""

    refreshed_at: datetime
    models: tuple[CatalogEntry, ...] = ()        # visible rows, ordered
    all_models: tuple[CatalogEntry, ...] = ()    # before visibility filtering
    statuses: Mapping[str, ProviderStatus] = field(default_factory=dict)
    added: tuple[str, ...] = ()
    removed: tuple[str, ...] = ()
    changes: tuple[CatalogChange, ...] = ()

    @property
    def new_count(self) -> int:
        return len(self.added)

    @property
    def removed_count(self) -> int:
        return len(self.removed)

    @property
    def model_count(self) -> int:
        return len(self.models)

    @property
    def uncurated_count(self) -> int:
        return sum(1 for m in self.models if not m.curated)

    @property
    def skipped_count(self) -> int:
        return sum(s.skipped for s in self.statuses.values())

    @property
    def failed_providers(self) -> tuple[str, ...]:
        return tuple(p for p, s in self.statuses.items() if not s.ok)

    def by_provider(self) -> dict[str, list[CatalogEntry]]:
        return group_by_provider(self.models)

    def summary(self) -> str:
        """The header strip sentence from S3.8."""
        failed = self.failed_providers
        if not failed:
            return f"Models \u00b7 {self.new_count} new, {self.removed_count} removed since last check"
        if len(failed) == 1:
            return (f"\u26a0 Couldn't reach {failed[0]} \u2014 using cached list. Retry?")
        return f"\u26a0 Couldn't reach {len(failed)} providers \u2014 using cached list."


def default_credentials(providers: Sequence[str] = CANONICAL_PROVIDERS) -> dict[str, str]:
    """Read each provider's key from the store the whole console shares.

    Local daemons store a base URL rather than a key; ``default_endpoints``
    treats the value accordingly.
    """
    from .config import load_secret
    out: dict[str, str] = {}
    for provider in providers:
        try:
            secret = load_secret(provider)
        except Exception:
            secret = None
        if secret:
            out[provider] = secret
    return out


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


class CatalogEngine:
    """Curated + live + overrides, with cache fallback and change reporting.

    Every collaborator is injected so the engine can be driven headless: the
    HTTP fetcher, the clock, the cache root, the overrides file, the curated
    yaml and the state file. Nothing here touches the network unless the
    caller supplies a fetcher that does.
    """

    def __init__(self,
                 *,
                 fetcher: Fetcher | None = None,
                 cache: ModelsCache | None = None,
                 cache_root: Path | str | None = None,
                 overrides: OverridesStore | None = None,
                 overrides_path: Path | str | None = None,
                 curated: Sequence[CatalogEntry] | None = None,
                 curated_path: Path | str | None = None,
                 credentials: Mapping[str, str] | Callable[[], Mapping[str, str]] | None = None,
                 endpoints: Mapping[str, Endpoint] | None = None,
                 state_path: Path | str | None = None,
                 clock: Callable[[], datetime] = _utc_now,
                 max_workers: int = MAX_CONCURRENT_DISCOVERIES) -> None:
        self.fetcher = fetcher
        self.cache = cache or ModelsCache(cache_root)
        self.overrides = overrides or OverridesStore(overrides_path)
        self._curated = list(curated) if curated is not None else None
        self._curated_path = Path(curated_path) if curated_path else None
        self._credentials = credentials
        self._endpoints = dict(endpoints) if endpoints is not None else None
        self.state_path = (Path(state_path) if state_path is not None
                           else config_root() / "console_catalog_state.json")
        self.clock = clock
        self.max_workers = max_workers
        self.last_result: RefreshResult | None = None

    # -- collaborators ----------------------------------------------------

    def curated_entries(self) -> list[CatalogEntry]:
        if self._curated is None:
            self._curated = load_curated(self._curated_path)
        return list(self._curated)

    def credentials(self) -> dict[str, str]:
        if callable(self._credentials):
            return dict(self._credentials())
        if self._credentials is not None:
            return dict(self._credentials)
        return default_credentials()

    def endpoints(self, credentials: Mapping[str, str]) -> dict[str, Endpoint]:
        if self._endpoints is not None:
            return dict(self._endpoints)
        return default_endpoints(credentials)

    def now(self) -> datetime:
        moment = self.clock()
        if moment.tzinfo is None:
            moment = moment.replace(tzinfo=timezone.utc)
        return moment

    def today(self) -> date:
        return self.now().astimezone().date()

    # -- state ------------------------------------------------------------

    def load_state(self) -> dict[str, Any]:
        """Previous refresh snapshot; an unreadable file simply means "no history"."""
        try:
            data = json.loads(self.state_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}
        return data if isinstance(data, dict) else {}

    def save_state(self, entries: Sequence[CatalogEntry], when: datetime) -> None:
        payload = {
            "version": 1,
            "refreshed_at": _format_instant(when),
            "models": snapshot_of(entries),
        }
        try:
            self.state_path.parent.mkdir(parents=True, exist_ok=True)
            self.state_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        except OSError:
            # A read-only profile must not break a refresh; we just lose the
            # "what changed" diff on the next run.
            pass

    # -- refresh ----------------------------------------------------------

    def refresh(self, timeout: float = REFRESH_TIMEOUT_S,
                providers: Sequence[str] | None = None,
                use_network: bool = True) -> RefreshResult:
        """Run one full refresh and return the merged catalog plus status.

        Mirrors ``AiRootPanel.runRefreshOffEdt`` (AiRootPanel.java:1508): every
        provider that answers is written to the shared cache; every provider
        that fails falls back to its cached list so a dead endpoint costs the
        user nothing except a warning.
        """
        now = self.now()
        today = self.today()
        credentials = self.credentials()
        endpoints = self.endpoints(credentials)
        provider_ids = list(providers) if providers is not None else list(endpoints.keys())

        discovery = ProviderDiscovery(endpoints, self.fetcher, self.max_workers)
        discovered: dict[str, LiveResult] = (
            discovery.discover_all(timeout, provider_ids) if use_network
            else {pid: LiveResult.failure("offline") for pid in provider_ids})

        live: dict[str, LiveResult] = {}
        statuses: dict[str, ProviderStatus] = {}

        for provider_id in provider_ids:
            has_credential = bool(credentials.get(provider_id))
            result = discovered.get(provider_id) or LiveResult.failure(
                "discovery returned no result")
            if result.successful:
                skipped = result.skipped
                try:
                    skipped += self.cache.write(
                        provider_id, now, endpoints[provider_id].url, result.model_ids)
                except Exception as failure:
                    # A cache we cannot write is a warning, not a failed refresh.
                    statuses[provider_id] = ProviderStatus(
                        provider_id, STATUS_OK,
                        f"cache write failed ({type(failure).__name__})",
                        len(result.model_ids), skipped, has_credential)
                    live[provider_id] = result
                    continue
                live[provider_id] = result
                statuses[provider_id] = ProviderStatus(
                    provider_id, STATUS_OK, "", len(result.model_ids), skipped,
                    has_credential, now)
                continue

            if provider_id in CURATED_ONLY:
                # No listing endpoint exists; curated rows are the truth (S3.11).
                live[provider_id] = result
                statuses[provider_id] = ProviderStatus(
                    provider_id, STATUS_CURATED_ONLY, "", 0, 0, has_credential)
                continue

            reason = result.failure_reason or discovery.last_error_for(provider_id) or (
                f"Provider did not respond within {int(_clamp_timeout(timeout) * 1000)} ms")
            snapshot = None
            try:
                snapshot = self.cache.read(provider_id)
            except CacheReadError as cache_failure:
                reason = f"{reason}; {cache_failure}"
            if snapshot is not None:
                live[provider_id] = LiveResult.success(snapshot.model_ids)
                statuses[provider_id] = ProviderStatus(
                    provider_id, STATUS_CACHED, reason, len(snapshot.model_ids), 0,
                    has_credential, snapshot.fetched_at)
            else:
                live[provider_id] = LiveResult.failure(reason)
                statuses[provider_id] = ProviderStatus(
                    provider_id, STATUS_FAILED, reason, 0, 0, has_credential)

        overrides = self.overrides.load_as_map()
        curated = self.curated_entries()
        merged = merge(curated, live, overrides, today)
        visible = apply_visibility(merged, overrides, today)

        state = self.load_state()
        previous = state.get("models") if isinstance(state.get("models"), Mapping) else {}
        current_snapshot = snapshot_of(visible)
        added = tuple(k for k in current_snapshot if k not in previous)
        removed = tuple(k for k in previous if k not in current_snapshot)
        changes = tuple(detect_changes(previous, visible, today))

        result = RefreshResult(now, tuple(visible), tuple(merged), statuses,
                               added, removed, changes)
        self.save_state(visible, now)
        self.last_result = result
        return result

    def offline(self) -> RefreshResult:
        """Build the catalog from curated yaml + cache only (startup, S3.32).

        Used before the first network refresh so the picker opens instantly and
        still shows locally discovered models from the last session.
        """
        return self.refresh(use_network=False)

    # -- user overrides ---------------------------------------------------

    def set_pinned(self, provider_id: str, model_id: str, pinned: bool) -> Override:
        """Persist a pin and return the stored override (S3.15)."""
        return self.overrides.set_pinned(provider_id, model_id, pinned)

    def set_hidden(self, provider_id: str, model_id: str, hidden: bool) -> Override:
        """Persist a hide and return the stored override (S3.14)."""
        return self.overrides.set_hidden(provider_id, model_id, hidden)

    def visible_models(self, merged: Sequence[CatalogEntry] | None = None) -> list[CatalogEntry]:
        """Re-apply the current override file to an already merged list.

        Lets the TUI toggle a pin or a hide and redraw without a refresh.
        """
        source = merged if merged is not None else (
            list(self.last_result.all_models) if self.last_result else [])
        overrides = self.overrides.load_as_map()
        today = self.today()
        repinned = []
        for entry in source:
            override = overrides.get(entry.key)
            if override is not None and override.pinned is not None:
                entry = entry.with_pinned(override.pinned)
            repinned.append(entry)
        return apply_visibility(repinned, overrides, today)
