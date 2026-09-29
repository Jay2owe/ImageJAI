"""Unit tests for the console provider/model catalog engine.

Run from the repo root:

    python -m pytest agent/console/tests -q

No network: every test injects a fake fetcher, a fake clock and temp paths.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from agent.console import catalog as cat  # noqa: E402

NOW = datetime(2026, 5, 1, 9, 0, 0, tzinfo=timezone.utc)
TODAY = date(2026, 5, 1)


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


def entry(provider="openai", model_id="gpt-5", **kw) -> cat.CatalogEntry:
    return cat.CatalogEntry(provider=provider, model_id=model_id, **kw)


def fetcher_for(payloads: dict[str, object]):
    """Fake fetcher: provider -> body string, HttpResponse, or Exception."""

    def fetch(endpoint: cat.Endpoint, timeout: float) -> cat.HttpResponse:
        value = payloads.get(endpoint.provider)
        if isinstance(value, BaseException):
            raise value
        if isinstance(value, cat.HttpResponse):
            return value
        if value is None:
            return cat.HttpResponse(500, "boom")
        return cat.HttpResponse(200, value)

    return fetch


def engine(tmp_path: Path, payloads: dict[str, object], curated=None,
           credentials=None, endpoints=None) -> cat.CatalogEngine:
    return cat.CatalogEngine(
        fetcher=fetcher_for(payloads),
        cache=cat.ModelsCache(tmp_path / "cache", sleep=lambda _s: None),
        overrides=cat.OverridesStore(tmp_path / "models_local.yaml"),
        curated=curated if curated is not None else [],
        credentials=credentials if credentials is not None else {},
        endpoints=endpoints if endpoints is not None else {
            "openai": cat.Endpoint("openai", "https://api.openai.com/v1/models"),
        },
        state_path=tmp_path / "state.json",
        clock=lambda: NOW,
    )


# ---------------------------------------------------------------------------
# launch policy / identifiers
# ---------------------------------------------------------------------------


def test_openrouter_alias_ids_are_invalid():
    # LaunchPolicy.java:35 - "~vendor/model" cannot start an id.
    assert cat.is_valid_model_id("openai/gpt-5")
    assert not cat.is_valid_model_id("~openai/gpt-5-latest")
    assert not cat.is_valid_model_id("")
    assert not cat.is_valid_model_id(None)
    with pytest.raises(ValueError):
        cat.require_model_id("~alias/model")


def test_endpoint_rejects_credentials_in_url():
    with pytest.raises(ValueError):
        cat.Endpoint("openai", "https://api.openai.com/v1/models?api_key=sk-secret")
    with pytest.raises(ValueError):
        cat.Endpoint("openai", "https://user:pw@api.openai.com/v1/models")


# ---------------------------------------------------------------------------
# tier classification and badges
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("value,expected", [
    ("free", cat.Tier.FREE),
    ("free-with-limits", cat.Tier.FREE_WITH_LIMITS),
    ("paid", cat.Tier.PAID),
    ("requires-subscription", cat.Tier.REQUIRES_SUBSCRIPTION),
    ("subscription", cat.Tier.REQUIRES_SUBSCRIPTION),
    ("uncurated", cat.Tier.UNCURATED),
    ("nonsense", cat.Tier.UNCURATED),
    (None, cat.Tier.UNCURATED),
])
def test_classify_tier(value, expected):
    assert cat.classify_tier(value) is expected


def test_tier_colours_and_tooltips_are_data():
    assert cat.tier_colour("free") == (60, 180, 75)
    assert cat.tier_colour("paid") == (70, 130, 220)
    assert cat.tier_colour("free", retired=True) == (200, 60, 60)
    assert cat.tier_tooltip("free").startswith("Free to use.")
    assert "subscription" in cat.tier_tooltip("requires-subscription")
    assert set(cat.TIER_COLOURS) == {t.value for t in cat.Tier} | {cat.BADGE_RETIRED}


def test_badge_is_retired_only_for_pinned_deprecated():
    old = TODAY - timedelta(days=40)
    pinned = entry(tier="paid", pinned=True, deprecated_since=old)
    fresh = entry(tier="paid", deprecated_since=TODAY)
    assert pinned.badge(TODAY) == cat.BADGE_RETIRED
    assert fresh.badge(TODAY) == "paid"


# ---------------------------------------------------------------------------
# cache
# ---------------------------------------------------------------------------


def test_cache_round_trip_uses_plugin_file_shape(tmp_path):
    cache = cat.ModelsCache(tmp_path)
    skipped = cache.write("openai", NOW, "https://api.openai.com/v1/models?key=sk-x",
                          ["gpt-5", "gpt-4o"])
    assert skipped == 0
    path = cache.path_for("openai")
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert payload["provider"] == "openai"
    assert payload["fetched_at"] == "2026-05-01T09:00:00Z"
    # credential query stripped before it reaches disk (ModelsCache.java:263)
    assert payload["endpoint"] == "https://api.openai.com/v1/models"
    assert payload["models"] == ["gpt-5", "gpt-4o"]

    snapshot = cache.read("openai")
    assert snapshot.provider == "openai"
    assert snapshot.model_ids == ("gpt-5", "gpt-4o")
    assert snapshot.fetched_at == NOW


def test_cache_path_is_the_directory_the_plugin_uses(tmp_path, monkeypatch):
    monkeypatch.setenv("IMAGEJAI_HOME", str(tmp_path))
    root = cat.default_cache_root()
    assert root == tmp_path / "cache" / "models"
    assert cat.ModelsCache().path_for("groq") == root / "groq.json"


def test_cache_ttl_is_24h(tmp_path):
    cache = cat.ModelsCache(tmp_path)
    cache.write("openai", NOW, "https://api.openai.com/v1/models", ["gpt-5"])
    assert cache.is_fresh("openai", NOW + timedelta(hours=23, minutes=59))
    assert not cache.is_fresh("openai", NOW + timedelta(hours=24, seconds=1))
    assert not cache.is_fresh("groq", NOW)


def test_cache_read_missing_and_malformed(tmp_path):
    cache = cat.ModelsCache(tmp_path)
    assert cache.read("openai") is None
    tmp_path.mkdir(exist_ok=True)
    cache.path_for("openai").write_text("{not json", encoding="utf-8")
    with pytest.raises(cat.CacheReadError) as failure:
        cache.read("openai")
    assert failure.value.code == "malformed"


def test_cache_write_cleans_up_temp_file_on_failure(tmp_path, monkeypatch):
    cache = cat.ModelsCache(tmp_path, sleep=lambda _s: None)

    def always_locked(src, dst):
        raise PermissionError("target locked by Fiji")

    monkeypatch.setattr(cat.os, "replace", always_locked)
    monkeypatch.setattr(cat.shutil, "copyfile",
                        lambda src, dst: (_ for _ in ()).throw(PermissionError("locked")))
    with pytest.raises(OSError):
        cache.write("openai", NOW, "https://api.openai.com/v1/models", ["gpt-5"])
    assert list(tmp_path.glob("*.tmp")) == []
    assert not cache.path_for("openai").exists()


def test_cache_write_falls_back_to_non_atomic_replace(tmp_path, monkeypatch):
    cache = cat.ModelsCache(tmp_path, sleep=lambda _s: None)
    calls = {"n": 0}

    def flaky(src, dst):
        calls["n"] += 1
        raise PermissionError("windows lock")

    monkeypatch.setattr(cat.os, "replace", flaky)
    cache.write("openai", NOW, "https://api.openai.com/v1/models", ["gpt-5"])
    assert calls["n"] == 5  # ModelsCache.java:257 - five retries, then copy
    assert cache.read("openai").model_ids == ("gpt-5",)
    assert list(tmp_path.glob("*.tmp")) == []


def test_cache_sweeps_stale_temp_files(tmp_path):
    cache = cat.ModelsCache(tmp_path)
    tmp_path.mkdir(exist_ok=True)
    stale = tmp_path / "openai-old.tmp"
    stale.write_text("junk", encoding="utf-8")
    old = os.stat(stale).st_mtime - (cat.STALE_TEMP_SECONDS + 60)
    os.utime(stale, (old, old))
    recent = tmp_path / "openai-new.tmp"
    recent.write_text("junk", encoding="utf-8")

    cache.write("openai", NOW, "", ["gpt-5"])
    assert not stale.exists()
    assert recent.exists()


def test_cache_skips_unusable_ids_instead_of_failing(tmp_path):
    cache = cat.ModelsCache(tmp_path)
    skipped = cache.write("openrouter", NOW, "https://openrouter.ai/api/v1/models",
                          ["openai/gpt-5", "~openai/gpt-5-latest", "  ", "z/model"])
    assert skipped == 2
    assert cache.last_rejected_count == 2
    assert cache.read("openrouter").model_ids == ("openai/gpt-5", "z/model")


def test_cache_read_rejects_oversized_model_list(tmp_path):
    cache = cat.ModelsCache(tmp_path)
    tmp_path.mkdir(exist_ok=True)
    cache.path_for("openai").write_text(json.dumps({
        "provider": "openai",
        "fetched_at": "2026-05-01T09:00:00Z",
        "endpoint": "",
        "models": [f"m{i}" for i in range(cat.MAX_CACHED_MODELS + 1)],
    }), encoding="utf-8")
    with pytest.raises(cat.CacheReadError) as failure:
        cache.read("openai")
    assert failure.value.code == "model_cap"


# ---------------------------------------------------------------------------
# discovery
# ---------------------------------------------------------------------------


def test_default_endpoints_match_the_java_table():
    endpoints = cat.default_endpoints({"anthropic": "sk-ant", "openai": "sk-o",
                                       "gemini": "g-key", "lmstudio": "http://box:9999/v1/"})
    assert endpoints["openai"].url == "https://api.openai.com/v1/models"
    assert endpoints["openai"].headers == {"Authorization": "Bearer sk-o"}
    assert endpoints["anthropic"].headers == {"x-api-key": "sk-ant",
                                              "anthropic-version": "2023-06-01"}
    assert endpoints["gemini"].headers == {"x-goog-api-key": "g-key"}
    assert endpoints["ollama"].url == "http://localhost:11434/api/tags"
    assert endpoints["openrouter"].headers == {}
    # base-URL override normalised, /v1 not doubled (ProviderDiscovery.java:186)
    assert endpoints["lmstudio"].url == "http://box:9999/v1/models"


@pytest.mark.parametrize("provider,body,expected", [
    ("openai", '{"data":[{"id":"gpt-5"},{"id":"gpt-4o"}]}', ["gpt-5", "gpt-4o"]),
    ("ollama", '{"models":[{"name":"llama3.2:3b"}]}', ["llama3.2:3b"]),
    ("gemini", '{"models":[{"name":"models/gemini-3-pro"}]}', ["gemini-3-pro"]),
    ("github-models", '[{"name":"gpt-5","publisher":"openai"}]', ["openai/gpt-5"]),
])
def test_parse_model_ids_per_provider_shape(provider, body, expected):
    assert cat.parse_model_ids(provider, body) == expected


def test_discovery_skips_unusable_ids_and_counts_them():
    discovery = cat.ProviderDiscovery(
        {"openrouter": cat.Endpoint("openrouter", "https://openrouter.ai/api/v1/models")},
        fetcher_for({"openrouter":
                     '{"data":[{"id":"openai/gpt-5"},{"id":"~openai/gpt-5-latest"}]}'}))
    result = discovery.discover("openrouter")
    assert result.successful
    assert result.model_ids == ("openai/gpt-5",)
    assert result.skipped == 1
    assert discovery.last_error_for("openrouter") is None


def test_discovery_failure_reason_is_redacted():
    endpoint = cat.Endpoint("openai", "https://api.openai.com/v1/models",
                            {"Authorization": "Bearer sk-secret"})
    discovery = cat.ProviderDiscovery(
        {"openai": endpoint},
        fetcher_for({"openai": cat.HttpResponse(401, 'bad key sk-secret')}))
    result = discovery.discover("openai")
    assert not result.successful
    assert "sk-secret" not in result.failure_reason
    assert "[REDACTED]" in result.failure_reason
    assert discovery.last_error_for("openai") == result.failure_reason


def test_discovery_survives_a_throwing_fetcher():
    def boom(endpoint, timeout):
        raise RuntimeError("socket exploded")

    discovery = cat.ProviderDiscovery(
        {"openai": cat.Endpoint("openai", "https://api.openai.com/v1/models")}, boom)
    result = discovery.discover("openai")
    assert result.failure_reason == "discovery failed (RuntimeError)"


def test_curated_only_providers_are_never_asked():
    calls = []

    def fetch(endpoint, timeout):
        calls.append(endpoint.provider)
        return cat.HttpResponse(200, "{}")

    discovery = cat.ProviderDiscovery(
        {"perplexity": cat.Endpoint("perplexity", "https://example.invalid/models")}, fetch)
    result = discovery.discover("perplexity")
    assert calls == []
    assert not result.successful
    assert result.failure_reason == ""       # not an error, just no signal
    assert discovery.last_error_for("perplexity") is None


def test_discover_all_is_parallel_and_keeps_order():
    endpoints = {p: cat.Endpoint(p, f"https://{p}.example/v1/models")
                 for p in ("openai", "groq", "mistral", "xai", "deepseek")}
    payloads = {p: '{"data":[{"id":"m-%s"}]}' % p for p in endpoints}
    payloads["xai"] = cat.HttpResponse(500, "upstream down")
    discovery = cat.ProviderDiscovery(endpoints, fetcher_for(payloads))
    results = discovery.discover_all(timeout=1.0)
    assert list(results) == list(endpoints)
    assert results["openai"].model_ids == ("m-openai",)
    assert not results["xai"].successful


def test_timeout_is_clamped_to_the_java_budget():
    seen = []

    def fetch(endpoint, timeout):
        seen.append(timeout)
        return cat.HttpResponse(200, '{"data":[]}')

    discovery = cat.ProviderDiscovery(
        {"openai": cat.Endpoint("openai", "https://api.openai.com/v1/models")}, fetch)
    discovery.discover("openai", timeout=999)
    discovery.discover("openai", timeout=0)
    assert seen == [cat.MAX_TIMEOUT_S, cat.DEFAULT_TIMEOUT_S]


# ---------------------------------------------------------------------------
# merge precedence, pins, hides
# ---------------------------------------------------------------------------


def test_merge_keeps_curator_fields_and_takes_upstream_context():
    curated = [entry(display_name="GPT-5", description="curated copy",
                     tier="paid", context_window=100, reliability="high")]
    live = {"openai": cat.LiveResult.success(
        ["gpt-5"], {"gpt-5": {"context_length": 400000}})}
    merged = cat.merge(curated, live, {}, TODAY)
    row = merged[0]
    assert row.display_name == "GPT-5"
    assert row.description == "curated copy"
    assert row.tier is cat.Tier.PAID
    assert row.reliability is cat.Reliability.HIGH
    assert row.context_window == 400000      # upstream wins
    assert row.last_verified == TODAY
    assert row.curated


def test_merge_adds_uncurated_stubs_for_upstream_only_models():
    live = {"openai": cat.LiveResult.success(["gpt-5", "o9-vision"])}
    merged = cat.merge([entry(model_id="gpt-5")], live, {}, TODAY)
    assert [m.model_id for m in merged] == ["gpt-5", "o9-vision"]
    stub = merged[1]
    assert not stub.curated
    assert stub.tier is cat.Tier.UNCURATED
    assert stub.description == cat.UNCURATED_DESCRIPTION
    assert stub.vision_capable          # inferred from the id
    groups = cat.group_by_provider(merged)
    assert [m.curated for m in groups["openai"]] == [True, False]


def test_merge_ordering_is_stable_across_runs():
    curated = [entry(model_id="a"), entry(provider="groq", model_id="b")]
    live = {"openai": cat.LiveResult.success(["a", "z"]),
            "groq": cat.LiveResult.success(["b", "y"])}
    first = [m.key for m in cat.merge(curated, live, {}, TODAY)]
    second = [m.key for m in cat.merge(curated, live, {}, TODAY)]
    assert first == second == ["openai a", "groq b", "openai z", "groq y"]


def test_user_pin_overrides_curator_and_survives_hide():
    curated = [entry(model_id="gpt-5", pinned=False)]
    overrides = {"openai gpt-5": cat.Override("openai", "gpt-5", pinned=True, hidden=True)}
    merged = cat.merge(curated, {}, overrides, TODAY)
    assert merged[0].pinned
    # hidden loses to pinned (MergeFunction.java:183)
    assert [m.key for m in cat.apply_visibility(merged, overrides, TODAY)] == ["openai gpt-5"]


def test_user_hide_removes_an_unpinned_row():
    curated = [entry(model_id="gpt-5"), entry(model_id="gpt-4o")]
    overrides = {"openai gpt-4o": cat.Override("openai", "gpt-4o", hidden=True)}
    visible = cat.apply_visibility(cat.merge(curated, {}, overrides, TODAY), overrides, TODAY)
    assert [m.model_id for m in visible] == ["gpt-5"]


def test_overrides_file_round_trip(tmp_path):
    store = cat.OverridesStore(tmp_path / "models_local.yaml")
    store.set_pinned("openai", "gpt-5", True)
    store.set_hidden("openai", "gpt-5", True)
    store.set_hidden("groq", "llama", True)
    text = (tmp_path / "models_local.yaml").read_text(encoding="utf-8")
    assert text.startswith("# Managed by the ImageJAI dropdown")
    assert "version: 1" in text

    loaded = cat.OverridesStore(tmp_path / "models_local.yaml").load_as_map()
    assert loaded["openai gpt-5"].pinned is True
    assert loaded["openai gpt-5"].hidden is True    # a later hide keeps the pin
    assert loaded["groq llama"].pinned is None
    assert not store.last_error


def test_corrupt_overrides_file_is_reported_not_fatal(tmp_path):
    path = tmp_path / "models_local.yaml"
    path.write_text("overrides: not-a-list\n", encoding="utf-8")
    store = cat.OverridesStore(path)
    assert store.load() == []
    assert "corrupt" in store.last_error


# ---------------------------------------------------------------------------
# deprecation
# ---------------------------------------------------------------------------


def test_missing_from_successful_listing_marks_deprecated_once():
    curated = [entry(model_id="gpt-4")]
    live = {"openai": cat.LiveResult.success(["gpt-5"])}
    first = cat.merge(curated, live, {}, TODAY)
    assert first[0].deprecated_since == TODAY
    later = cat.merge(first, live, {}, TODAY + timedelta(days=5))
    assert later[0].deprecated_since == TODAY      # clock keeps counting from day 1


def test_failed_listing_never_deprecates():
    curated = [entry(model_id="gpt-4")]
    live = {"openai": cat.LiveResult.failure("HTTP 500")}
    assert cat.merge(curated, live, {}, TODAY)[0].deprecated_since is None


def test_model_returning_upstream_clears_deprecation():
    curated = [entry(model_id="gpt-4", deprecated_since=TODAY - timedelta(days=10))]
    live = {"openai": cat.LiveResult.success(["gpt-4"])}
    row = cat.merge(curated, live, {}, TODAY)[0]
    assert row.deprecated_since is None
    assert row.last_verified == TODAY


def test_deprecation_lifecycle_states_and_notices():
    since = TODAY - timedelta(days=1)
    soft = entry(deprecated_since=since, replacement="gpt-5")
    assert cat.deprecation_state(soft, TODAY) is cat.DeprecationState.SOFT_DEPRECATED
    assert cat.deprecation_notice(soft, TODAY) == (
        f"No longer available since {since.isoformat()} \u2014 try gpt-5.")

    old = TODAY - timedelta(days=cat.DEPRECATION_WINDOW_DAYS + 1)
    retired = entry(deprecated_since=old, pinned=True, replacement="gpt-5")
    assert cat.deprecation_state(retired, TODAY) is cat.DeprecationState.PINNED_DEPRECATED
    assert cat.deprecation_notice(retired, TODAY) == (
        "RETIRED \u2014 calls will fail. Switch to gpt-5.")

    dropped = entry(deprecated_since=old)
    assert cat.deprecation_state(dropped, TODAY) is cat.DeprecationState.HIDDEN
    assert cat.apply_visibility([dropped, retired], {}, TODAY) == [retired]
    assert cat.deprecation_notice(entry(), TODAY) is None


# ---------------------------------------------------------------------------
# change detection
# ---------------------------------------------------------------------------


def priced(model_id, tier, input_price, output_price=None, **kw):
    features = {"pricing": {"input_usd_per_mtok": input_price}}
    if output_price is not None:
        features["pricing"]["output_usd_per_mtok"] = output_price
    features.update(kw.pop("features", {}))
    return entry(model_id=model_id, tier=tier, features=features, **kw)


def test_detect_tier_change_is_high_severity():
    previous = cat.snapshot_of([priced("gpt-5", "free", 0.0)])
    current = [priced("gpt-5", "paid", 0.0)]
    changes = cat.detect_changes(previous, current, TODAY)
    assert len(changes) == 1
    assert changes[0].kind == "tier"
    assert changes[0].severity is cat.Severity.HIGH
    assert changes[0].body.startswith("Was free, now paid.")


def test_detect_price_change_threshold_and_direction():
    previous = cat.snapshot_of([priced("gpt-5", "paid", 3.0, 15.0)])
    assert cat.detect_changes(previous, [priced("gpt-5", "paid", 3.2, 15.0)], TODAY) == []
    up = cat.detect_changes(previous, [priced("gpt-5", "paid", 4.0, 15.0)], TODAY)
    assert up[0].kind == "price" and up[0].severity is cat.Severity.HIGH
    assert "Input was $3.0/M, now $4.0/M." in up[0].body
    down = cat.detect_changes(previous, [priced("gpt-5", "paid", 1.0, 15.0)], TODAY)
    assert down[0].severity is cat.Severity.LOW


def test_scheduled_price_change_prewarns_inside_seven_days():
    soon = TODAY + timedelta(days=3)
    late = TODAY + timedelta(days=30)
    rows = [priced("gpt-5", "paid", 3.0, features={"pricing_changes": [
        {"effective_date": soon.isoformat(), "input_usd_per_mtok": 5.0, "reason": "Vendor notice"},
        {"effective_date": late.isoformat(), "input_usd_per_mtok": 9.0},
    ]})]
    changes = cat.scheduled_changes(rows, TODAY)
    assert len(changes) == 1
    assert changes[0].severity is cat.Severity.MEDIUM
    assert changes[0].body.endswith(f"for {soon.isoformat()} (T-3 days).")


def test_changes_can_be_limited_and_dismissed():
    previous = cat.snapshot_of([priced("gpt-5", "free", 0.0), priced("o9", "free", 0.0)])
    current = [priced("gpt-5", "paid", 0.0), priced("o9", "paid", 0.0)]
    only = cat.detect_changes(previous, current, TODAY, only_keys=["openai gpt-5"])
    assert [c.key for c in only] == ["openai gpt-5"]
    both = cat.detect_changes(previous, current, TODAY)
    assert cat.filter_dismissed(both, {"openai gpt-5"}) == [both[1]]


def test_new_model_is_not_reported_as_a_change():
    previous = cat.snapshot_of([priced("gpt-5", "paid", 3.0)])
    current = [priced("gpt-5", "paid", 3.0), priced("o9", "free", 0.0)]
    assert cat.detect_changes(previous, current, TODAY) == []


# ---------------------------------------------------------------------------
# curated yaml
# ---------------------------------------------------------------------------


def test_load_curated_reads_the_shared_models_yaml(tmp_path):
    path = tmp_path / "models.yaml"
    path.write_text(
        "models:\n"
        "  - provider: openai\n"
        "    model_id: gpt-5\n"
        "    display_name: GPT-5\n"
        "    tier: paid\n"
        "    context_window: 400000\n"
        "    vision_capable: true\n"
        "    tool_call_reliability: high\n"
        "    last_verified: 2026-05-01\n"
        "    pricing:\n"
        "      input_usd_per_mtok: 3.0\n"
        "      output_usd_per_mtok: 15.0\n"
        "  - provider: not-a-provider\n"
        "    model_id: ghost\n",
        encoding="utf-8")
    entries = cat.load_curated(path)
    assert [e.key for e in entries] == ["openai gpt-5"]
    row = entries[0]
    assert row.tier is cat.Tier.PAID
    assert row.context_window == 400000
    assert row.last_verified == date(2026, 5, 1)
    assert row.input_usd_per_mtok == 3.0
    assert row.output_usd_per_mtok == 15.0


def test_load_curated_handles_a_missing_file(tmp_path):
    assert cat.load_curated(tmp_path / "nope.yaml") == []


def test_repo_models_yaml_loads():
    entries = cat.load_curated()
    assert entries, "bundled agent/providers/models.yaml should parse"
    assert all(e.provider in cat.CANONICAL_PROVIDERS for e in entries)


# ---------------------------------------------------------------------------
# refresh entry point
# ---------------------------------------------------------------------------


def test_refresh_reports_status_counts_and_writes_cache(tmp_path):
    eng = engine(tmp_path, {"openai": '{"data":[{"id":"gpt-5"},{"id":"gpt-4o"}]}'},
                 curated=[entry(model_id="gpt-5", display_name="GPT-5", tier="paid")])
    result = eng.refresh()
    assert result.statuses["openai"].state == cat.STATUS_OK
    assert result.statuses["openai"].ui_status == cat.UI_READY
    assert [m.model_id for m in result.models] == ["gpt-5", "gpt-4o"]
    assert result.uncurated_count == 1
    assert set(result.added) == {"openai gpt-5", "openai gpt-4o"}
    assert result.removed_count == 0
    assert eng.cache.read("openai").model_ids == ("gpt-5", "gpt-4o")
    assert "2 new, 0 removed" in result.summary()


def test_refresh_falls_back_to_cache_when_a_provider_fails(tmp_path):
    good = engine(tmp_path, {"openai": '{"data":[{"id":"gpt-5"}]}'})
    good.refresh()

    bad = engine(tmp_path, {"openai": cat.HttpResponse(503, "service unavailable")},
                 credentials={"openai": "sk-key"})
    result = bad.refresh()
    status = result.statuses["openai"]
    assert status.state == cat.STATUS_CACHED
    assert "503" in status.reason
    assert status.ui_status == cat.UI_UNAVAILABLE      # key present, endpoint down
    assert [m.model_id for m in result.models] == ["gpt-5"]
    assert "Couldn't reach openai" in result.summary()


def test_refresh_without_cache_reports_failure_and_keeps_curated(tmp_path):
    eng = engine(tmp_path, {"openai": cat.HttpResponse(401, "no key")},
                 curated=[entry(model_id="gpt-5")])
    result = eng.refresh()
    status = result.statuses["openai"]
    assert status.state == cat.STATUS_FAILED
    assert status.ui_status == cat.UI_NEEDS_SETUP     # no credential on file
    assert [m.model_id for m in result.models] == ["gpt-5"]
    assert result.models[0].deprecated_since is None  # failure never deprecates


def test_refresh_reports_skipped_ids(tmp_path):
    eng = engine(
        tmp_path,
        {"openrouter": '{"data":[{"id":"openai/gpt-5"},{"id":"~openai/gpt-5-latest"}]}'},
        endpoints={"openrouter": cat.Endpoint(
            "openrouter", "https://openrouter.ai/api/v1/models")})
    result = eng.refresh()
    assert result.skipped_count == 1
    assert [m.model_id for m in result.models] == ["openai/gpt-5"]


def test_refresh_diffs_against_the_previous_refresh(tmp_path):
    curated_free = [priced("gpt-5", "free", 3.0)]
    eng = engine(tmp_path, {"openai": '{"data":[{"id":"gpt-5"},{"id":"gpt-4o"}]}'},
                 curated=curated_free)
    first = eng.refresh()
    assert first.changes == ()

    eng2 = engine(tmp_path, {"openai": '{"data":[{"id":"gpt-5"}]}'},
                  curated=[priced("gpt-5", "paid", 3.0)])
    second = eng2.refresh()
    assert second.removed == ("openai gpt-4o",)
    assert second.added == ()
    assert [c.kind for c in second.changes] == ["tier"]
    assert second.changes[0].severity is cat.Severity.HIGH
    assert "1 new" not in second.summary()


def test_refresh_curated_only_provider_is_ready_without_a_call(tmp_path):
    eng = engine(tmp_path, {}, curated=[entry(provider="perplexity", model_id="sonar")],
                 endpoints={"perplexity": cat.Endpoint(
                     "perplexity", "https://api.perplexity.ai/models")})
    result = eng.refresh()
    assert result.statuses["perplexity"].state == cat.STATUS_CURATED_ONLY
    assert result.statuses["perplexity"].ui_status == cat.UI_READY
    assert [m.model_id for m in result.models] == ["sonar"]


def test_offline_refresh_makes_no_fetch_calls(tmp_path):
    seen = []

    def fetch(endpoint, timeout):
        seen.append(endpoint.provider)
        return cat.HttpResponse(200, '{"data":[{"id":"gpt-5"}]}')

    warm = engine(tmp_path, {"openai": '{"data":[{"id":"gpt-5"}]}'})
    warm.refresh()
    eng = engine(tmp_path, {})
    eng.fetcher = fetch
    result = eng.offline()
    assert seen == []
    assert [m.model_id for m in result.models] == ["gpt-5"]
    assert result.statuses["openai"].state == cat.STATUS_CACHED


def test_engine_pin_and_hide_change_the_visible_list(tmp_path):
    eng = engine(tmp_path, {"openai": '{"data":[{"id":"gpt-5"},{"id":"gpt-4o"}]}'})
    result = eng.refresh()
    eng.set_hidden("openai", "gpt-4o", True)
    assert [m.model_id for m in eng.visible_models()] == ["gpt-5"]
    eng.set_pinned("openai", "gpt-4o", True)
    visible = eng.visible_models()
    assert [m.model_id for m in visible] == ["gpt-5", "gpt-4o"]
    assert visible[1].pinned
    assert result.model_count == 2
