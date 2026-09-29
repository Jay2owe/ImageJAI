"""Tests for agent/contexts/loader.py — Phase F.

Coverage:
- Snapshot test for one canonical model per family. Locks the
  composed output; quirk changes show up as explicit snapshot diffs.
- Equivalence test for the Claude path (CLAUDE.md): every distinct
  meaningful line currently in agent/CLAUDE.md must appear in the
  composed output, modulo a small justified-replacement allow-list.
  Test reports unexpected drops separately from new content.
- Loader correctness: missing model_id raises ``KeyError``; missing
  overlay file raises ``FileNotFoundError`` with a helpful message;
  unknown harness value raises ``ValueError``.
- Smoke test: every entry in the registry composes without raising.
"""

from __future__ import annotations

import os
import re
import importlib.util
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Iterable

import pytest
import yaml

from agent.contexts import loader

CTX_DIR = Path(loader.__file__).parent
AGENT_DIR = CTX_DIR.parent
REGISTRY_PATH = AGENT_DIR / "providers" / "models.yaml"
SNAPSHOT_DIR = CTX_DIR / "_snapshots"
CLAUDE_MD_PATH = AGENT_DIR / "CLAUDE.md"
UPDATE_SNAPSHOTS_ENV = "IMAGEJAI_UPDATE_CONTEXT_SNAPSHOTS"
PROJECT_ROOT = AGENT_DIR.parent


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #


def _registry_entries() -> list[dict]:
    cfg = yaml.safe_load(REGISTRY_PATH.read_text(encoding="utf-8"))
    return list(cfg["models"])


def _model_id(entry: dict) -> str:
    return f"{entry['provider']}/{entry['model_id']}"


def _slug(model_id: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", model_id)


def _meaningful_lines(text: str) -> list[str]:
    """Strip trivial markup and return non-empty content lines.

    Drops blank lines, separator rules (``---``), markdown table
    formatting rows (``|---|---|``), trailing whitespace.
    """
    out: list[str] = []
    for raw in text.splitlines():
        line = raw.rstrip()
        if not line:
            continue
        stripped = line.strip()
        if stripped == "---":
            continue
        if re.fullmatch(r"\|[\s|:.\-]+\|", stripped):
            continue
        out.append(line)
    return out


def _load_context_hook_module():
    path = PROJECT_ROOT / "context_hook.py"
    spec = importlib.util.spec_from_file_location("imagejai_context_hook_test", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _assert_snapshot(model_id: str, composed: str, snap_path: Path) -> None:
    """Assert a tracked snapshot, updating only after an explicit opt-in."""
    if not snap_path.exists():
        if os.environ.get(UPDATE_SNAPSHOTS_ENV) == "1":
            snap_path.parent.mkdir(parents=True, exist_ok=True)
            snap_path.write_text(composed, encoding="utf-8")
            pytest.skip(f"wrote requested snapshot for {model_id}; review and rerun")
        pytest.fail(
            f"tracked context snapshot is missing for {model_id}: {snap_path}. "
            f"To create it intentionally, set {UPDATE_SNAPSHOTS_ENV}=1, run this "
            "test, then review and commit the generated file."
        )
    expected = snap_path.read_text(encoding="utf-8")
    if composed != expected and os.environ.get(UPDATE_SNAPSHOTS_ENV) == "1":
        snap_path.write_text(composed, encoding="utf-8")
        pytest.skip(f"updated requested snapshot for {model_id}; review and rerun")
    assert composed == expected, (
        f"composition for {model_id} drifted from snapshot at {snap_path}; "
        f"set {UPDATE_SNAPSHOTS_ENV}=1 only when intentionally regenerating it."
    )


# --------------------------------------------------------------------------- #
# loader correctness
# --------------------------------------------------------------------------- #


def test_unknown_model_id_raises_key_error():
    with pytest.raises(KeyError, match="unknown model id"):
        loader.load_context("anthropic/does-not-exist")


def test_missing_provider_prefix_raises_key_error():
    with pytest.raises(KeyError, match="provider/model_id"):
        loader.load_context("claude-opus-4-7")


def test_invalid_harness_raises_value_error(monkeypatch):
    fake = {
        "models": [
            {
                "provider": "test",
                "model_id": "bad-harness",
                "harness": "not_a_real_harness",
                "family": "claude",
                "vision_capable": True,
                "tool_call_reliability": "high",
                "context_size": "large",
            }
        ]
    }

    def _fake_safe_load(_text):
        return fake

    monkeypatch.setattr(loader.yaml, "safe_load", _fake_safe_load)
    with pytest.raises(ValueError, match="harness must be one of"):
        loader.load_context("test/bad-harness")


def test_missing_overlay_file_raises_filenotfound(monkeypatch, tmp_path):
    """Pointing the loader at an empty overlay tree produces a clear
    FileNotFoundError naming the missing path."""
    monkeypatch.setattr(loader, "CTX_DIR", tmp_path)
    fake = {
        "models": [
            {
                "provider": "test",
                "model_id": "missing-overlays",
                "harness": "cli_shell",
                "family": "claude",
                "vision_capable": True,
                "tool_call_reliability": "high",
                "context_size": "large",
            }
        ]
    }
    monkeypatch.setattr(loader.yaml, "safe_load", lambda _t: fake)
    with pytest.raises(FileNotFoundError, match="context overlay missing"):
        loader.load_context("test/missing-overlays")


def test_missing_family_overlay_is_not_silently_dropped(monkeypatch, tmp_path):
    """A models.yaml entry whose `family` names a missing families/<family>.md
    must fail loudly rather than silently composing a generic context.

    Regression: the family overlay used to be guarded by ``.exists()`` and
    skipped when absent, so a curation typo dropped the whole family ruleset
    with no error. base.md + the harness overlay are present here, so the only
    missing file is the family overlay — proving the loader fails specifically
    on it (the empty-tree test above trips on base.md instead)."""
    (tmp_path / "base.md").write_text("BASE", encoding="utf-8")
    (tmp_path / "harness").mkdir()
    (tmp_path / "harness" / "cli_shell.md").write_text("HARNESS", encoding="utf-8")
    # families/claude.md is deliberately absent.
    monkeypatch.setattr(loader, "CTX_DIR", tmp_path)
    fake = {
        "models": [
            {
                "provider": "test",
                "model_id": "missing-family",
                "harness": "cli_shell",
                "family": "claude",
                "vision_capable": True,
                "tool_call_reliability": "high",
                "context_size": "large",
            }
        ]
    }
    monkeypatch.setattr(loader.yaml, "safe_load", lambda _t: fake)
    with pytest.raises(FileNotFoundError, match=r"families.*claude\.md"):
        loader.load_context("test/missing-family")


# --------------------------------------------------------------------------- #
# smoke: every registered model composes
# --------------------------------------------------------------------------- #


@pytest.mark.parametrize("entry", _registry_entries(), ids=_model_id)
def test_every_model_composes(entry):
    """Every entry in the unified registry composes without raising."""
    composed = loader.load_context(_model_id(entry))
    assert composed.strip(), f"empty composition for {_model_id(entry)}"
    # base.md content always present
    assert "ImageJAI Agent" in composed


def test_every_model_declares_exactly_one_known_family_and_harness():
    validated = loader.load_registry()
    assert len(validated) == len(_registry_entries())
    for entry in validated.values():
        assert entry["family"] in loader._VALID_FAMILIES
        assert entry["harness"] in loader._VALID_HARNESSES


def test_misspelled_family_is_not_hidden_by_generic_overlay(monkeypatch):
    fake = {
        "models": [{
            "provider": "test",
            "model_id": "typo",
            "harness": "tool_loop",
            "family": "llamma",
            "vision_capable": False,
            "tool_call_reliability": "medium",
            "context_size": "small",
        }]
    }
    monkeypatch.setattr(loader.yaml, "safe_load", lambda _text: fake)
    with pytest.raises(ValueError, match="family must be one of"):
        loader.load_context("test/typo")


# --------------------------------------------------------------------------- #
# snapshot — one canonical model per family that has registry coverage
# --------------------------------------------------------------------------- #


def _canonical_per_family() -> dict[str, str]:
    """Return ``{family: model_id}`` picking one entry per family.

    Picks the first entry of each family in registry order.
    """
    chosen: dict[str, str] = {}
    for entry in _registry_entries():
        fam = entry.get("family")
        if not fam or fam in chosen:
            continue
        chosen[fam] = _model_id(entry)
    return chosen


SNAPSHOT_CANDIDATES = sorted(_canonical_per_family().items())


@pytest.mark.parametrize(
    "family,model_id",
    SNAPSHOT_CANDIDATES,
    ids=[fam for fam, _ in SNAPSHOT_CANDIDATES],
)
def test_snapshot_canonical_per_family(family, model_id):
    """Compositions are snapshotted; unintended drift fails the test.

    Missing snapshots fail. Intentional creation requires
    ``IMAGEJAI_UPDATE_CONTEXT_SNAPSHOTS=1`` and review of the generated file.
    """
    composed = loader.load_context(model_id)
    snap_path = SNAPSHOT_DIR / f"{_slug(model_id)}.md"
    _assert_snapshot(model_id, composed, snap_path)


def test_missing_snapshot_requires_explicit_update(monkeypatch, tmp_path):
    monkeypatch.delenv(UPDATE_SNAPSHOTS_ENV, raising=False)
    missing = tmp_path / "missing.md"
    with pytest.raises(pytest.fail.Exception, match="tracked context snapshot is missing"):
        _assert_snapshot("test/missing", "COMPOSED", missing)
    assert not missing.exists()


# --------------------------------------------------------------------------- #
# CLAUDE.md equivalence
# --------------------------------------------------------------------------- #


# Substantive facts/features from agent/CLAUDE.md that the composed Claude
# context must preserve. Each fragment is taken verbatim from CLAUDE.md (or
# a near-verbatim phrasing) so a literal substring search in the composed
# output is enough. The list intentionally targets *content*, not formatting:
# the overlay system restructures headings, table layouts, and prose
# ordering — the equivalence check verifies that every substantive fact
# survives that restructuring.
_CLAUDE_MUST_CONTAIN: tuple[str, ...] = (
    # Server endpoint
    "localhost:7746",
    # Handshake (CLAUDE.md:6-25)
    "Handshake",
    "python ij.py capabilities",
    "vision=true",
    "output_format=markdown",
    "token_budget=20000",
    # Core commands (CLAUDE.md:28-71)
    "python ij.py macro",
    "python ij.py state",
    "python ij.py info",
    "python ij.py results",
    "python ij.py capture",
    "python ij.py explore",
    "python ij.py log",
    "python ij.py histogram",
    "python ij.py windows",
    "python ij.py metadata",
    "python ij.py rois",
    "python ij.py display",
    "python ij.py console",
    "python ij.py dialogs",
    "python ij.py close_dialogs",
    "python ij.py 3d",
    "python ij.py probe",
    "python ij.py script",
    "python ij.py ui list",
    "python ij.py ui click",
    "python ij.py ui dropdown",
    # Long-running EDT mutation handoff
    "operation_in_progress",
    "wait_for_operation",
    "Never submit the original mutation",
    # Plugin probing
    "probe_plugin.py",
    # Pixel-side analysis
    "pixels.py",
    # Capture path
    "agent/.tmp/",
    # Looking at images directive
    "you will see the image",
    # JSON protocol (CLAUDE.md:121-149)
    "execute_macro",
    "get_state",
    "get_image_info",
    "get_results_table",
    "capture_image",
    "run_pipeline",
    "explore_thresholds",
    "get_log",
    "get_histogram",
    "get_open_windows",
    "get_metadata",
    "get_dialogs",
    "get_pixels",
    "3d_viewer",
    "interact_dialog",
    "probe_command",
    "run_script",
    # Workflow (CLAUDE.md:184-197)
    "Check state",
    "Check metadata",
    "Probe unfamiliar plugins",
    "Capture and",
    "Verify results",
    "Audit",
    "Iterate",
    # Error handling — including the Groovy/console fact (CLAUDE.md:209-213)
    "No image open",
    "Not a binary image",
    "Selection required",
    "Wrong/unknown arguments",
    "Groovy",
    "System.err",
    # Macro / scripting references
    "macro-reference.md",
    "fiji-toolbar-tools-reference.md",
    # Plugin scan
    "scan_plugins.py",
    # Reference categories (CLAUDE.md:319-353)
    "ai-image-analysis",
    "weka-segmentation",
    "trackmate",
    "spatial-statistics",
    "registration-stitching",
    "brain-atlas-registration",
    "fluorescence-microscopy",
    "deconvolution",
    "wound-healing-migration",
    "circadian-analysis",
    # Lab training
    "lab_profile.json",
    "learnings.md",
    # House rules (CLAUDE.md:378-393)
    "Enhance Contrast",
    "setMinAndMax",
    "AI_Exports/",
    "auditor.py",
    "recipe",
    # Recipe book / 3D rendering quick recipes
    "3D Project",
    "3Dscript",
)


def test_claude_path_equivalence():
    """Every substantive fact from agent/CLAUDE.md must survive in the
    composed Claude context. Restructured headings and reformatted tables
    are accepted; missing content fails the test."""
    if not CLAUDE_MD_PATH.exists():
        pytest.skip("agent/CLAUDE.md absent — equivalence baseline unavailable")
    composed = loader.load_context("anthropic/claude-opus-4-7")
    missing = [frag for frag in _CLAUDE_MUST_CONTAIN if frag not in composed]
    if missing:
        sample = "\n  - " + "\n  - ".join(missing)
        pytest.fail(
            f"{len(missing)} fact(s) from CLAUDE.md missing from the "
            f"composed Claude context. Either add to the overlay system or "
            f"remove from _CLAUDE_MUST_CONTAIN with justification:{sample}"
        )


def test_claude_equivalence_reports_new_content():
    """Surface *additions* in the composed Claude context — content
    introduced by base.md / overlays that wasn't in agent/CLAUDE.md
    before sync_context.py last regenerated it. Always passes; the
    list is printed for human review of intentional drift.

    Once sync_context.py has run, CLAUDE.md *is* the loader output and
    the diff collapses to zero — that is correct, not a regression."""
    if not CLAUDE_MD_PATH.exists():
        pytest.skip("agent/CLAUDE.md absent — diff unavailable")
    legacy = set(_meaningful_lines(CLAUDE_MD_PATH.read_text(encoding="utf-8")))
    composed_lines = set(_meaningful_lines(
        loader.load_context("anthropic/claude-opus-4-7")
    ))
    added = sorted(composed_lines - legacy)
    removed = sorted(legacy - composed_lines)
    print(
        f"\n[Phase F equivalence] composed Claude context vs CLAUDE.md: "
        f"{len(added)} line(s) only in composed, {len(removed)} only in legacy."
    )


def test_claude_path_carries_handshake():
    """The Claude family overlay must carry the handshake content lifted
    from the original CLAUDE.md:6-25 block."""
    composed = loader.load_context("anthropic/claude-opus-4-7")
    assert "Handshake" in composed
    assert "python ij.py capabilities" in composed
    assert "vision=true" in composed


def test_groovy_console_error_fact_in_base():
    """The Groovy / Jython console-error fact (CLAUDE.md:209-213 today)
    must propagate to every regenerated CLI context, including AGENTS.md
    and GEMINI.md which previously lacked it."""
    for model_id in (
        "anthropic/claude-opus-4-7",
        "openai/gpt-5-codex",
        "gemini/gemini-2.5-pro",
    ):
        composed = loader.load_context(model_id)
        assert "Groovy" in composed and "System.err" in composed, (
            f"{model_id}: composed context lacks Groovy/System.err fact"
        )


# --------------------------------------------------------------------------- #
# regression: capability and reliability overlays apply
# --------------------------------------------------------------------------- #


def test_no_vision_overlay_applied_for_text_only_model():
    composed = loader.load_context("groq/llama-3.3-70b-versatile")
    assert "You cannot see images" in composed


def test_weak_tools_overlay_applied_for_low_reliability(monkeypatch):
    fake = {
        "models": [
            {
                "provider": "test",
                "model_id": "weak",
                "harness": "tool_loop",
                "family": "phi",
                "vision_capable": False,
                "tool_call_reliability": "low",
                "context_size": "small",
            }
        ]
    }
    monkeypatch.setattr(loader.yaml, "safe_load", lambda _t: fake)
    composed = loader.load_context("test/weak")
    assert "Weak tool calling" in composed
    assert "Small context" in composed
    assert "You cannot see images" in composed


def test_high_reliability_omits_reliability_overlay():
    composed = loader.load_context("anthropic/claude-opus-4-7")
    assert "Weak tool calling" not in composed
    assert "Good tool calling" not in composed


# --------------------------------------------------------------------------- #
# context sidecar freshness, ownership, and deterministic generation
# --------------------------------------------------------------------------- #


def test_reconnect_does_not_revive_an_expired_heartbeat(monkeypatch):
    hook = _load_context_hook_module()
    monkeypatch.setattr(hook.time, "time", lambda: 200.0)
    reconnected = {
        "updated_ts": 200_000,       # a recent "subscribed" rewrite
        "last_heartbeat_ts": 100_000,  # but no recent server heartbeat
    }
    assert not hook._snapshot_is_fresh(reconnected, max_age_seconds=90)
    reconnected["last_heartbeat_ts"] = 200_000
    assert hook._snapshot_is_fresh(reconnected, max_age_seconds=90)


def test_concurrent_subscriber_lease_has_one_owner(monkeypatch, tmp_path):
    hook = _load_context_hook_module()
    monkeypatch.setattr(hook, "CACHE_DIR", str(tmp_path))
    monkeypatch.setattr(hook, "TCP_HOST", "127.0.0.1")
    monkeypatch.setattr(hook, "TCP_PORT", 47746)
    monkeypatch.setattr(
        hook, "_process_identity",
        lambda pid: "current-process" if pid == os.getpid() else None,
    )
    gate = threading.Barrier(2)

    def claim(owner):
        gate.wait(timeout=2)
        return owner, hook._acquire_subscriber_lease(
            owner, os.getpid(), ttl_seconds=30, now=100.0)

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(claim, ("owner-a", "owner-b")))
    winners = [owner for owner, acquired in results if acquired]
    assert len(winners) == 1
    lease = hook._read_subscriber_lease()
    assert lease["owner"] == winners[0]
    assert lease["pid"] == os.getpid()
    assert lease["process_identity"] == "current-process"
    hook._release_subscriber_lease(winners[0], os.getpid())


def test_sync_context_is_byte_stable_and_does_not_rewrite(monkeypatch, tmp_path):
    from agent import sync_context

    monkeypatch.setattr(sync_context, "SCRIPT_DIR", str(tmp_path))
    monkeypatch.setattr(
        sync_context, "AGENT_FILES", {"AGENTS.md": "openai/gpt-5-codex"})
    monkeypatch.setattr(sync_context.loader, "load_context", lambda _model: "BODY\n")
    sync_context.sync()
    target = tmp_path / "AGENTS.md"
    first = target.read_bytes()
    fixed_ns = 1_700_000_000_000_000_000
    os.utime(target, ns=(fixed_ns, fixed_ns))
    sync_context.sync()
    assert target.read_bytes() == first
    assert target.stat().st_mtime_ns == fixed_ns
    assert list(tmp_path.glob("*.tmp")) == []
