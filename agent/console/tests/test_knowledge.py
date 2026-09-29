"""Hermetic tests for the read-only bundled knowledge index."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from agent.console import knowledge as kz  # noqa: E402

RECIPE = """
schema_version: 1
name: Cell Counting (Threshold + Analyze Particles)
id: cell_counting
description: >
  Count cells or nuclei using auto-thresholding, watershed separation, and
  Analyze Particles.
domain: cell_biology
difficulty: beginner
preconditions:
  image_type: [8-bit, 16-bit]
  min_channels: 1
  needs_stack: false
parameters:
  - name: blur_sigma
    type: numeric
    default: 1
  - name: threshold_method
    type: choice
    default: Otsu
steps:
  - id: 1
    description: Duplicate the image
    macro: |
      run("Duplicate...", "title=SECRETMASKMACRO");
  - id: 2
    description: Threshold
    macro: |
      setAutoThreshold("Otsu");
known_issues:
  - condition: Dense clusters
    symptom: watershed cannot separate
    fix: use stardist
tags: [cells, counting, nuclei]
"""

TRACKING_RECIPE = """
schema_version: 1
name: Particle Tracking
id: particle_tracking
description: Track moving particles over time with TrackMate.
domain: tracking
difficulty: intermediate
preconditions:
  image_type: [8-bit]
  min_channels: 1
  needs_stack: true
  needs_time: true
steps:
  - id: 1
    description: Open TrackMate
tags: [tracking, trackmate]
"""

REFERENCE = """# Calcium Imaging Analysis Reference

Intro paragraph that is document BODYSECRET material.

## §1 ROI selection

Pick ROIs over cell somata. BODYSECRET one.

### §1.1 Nested detail

Nested BODYSECRET text.

## §2 Photobleaching

Fit an exponential. BODYSECRET two.
"""

PRIVATE_LEARNINGS = "# learnings\\n\\nPRIVATESECRET about this microscope.\\n"
PRIVATE_PROFILE = '{"lab": "PRIVATESECRET", "scope": "PRIVATESECRET"}'


@pytest.fixture
def dirs(tmp_path, monkeypatch):
    """Explicit folders only; no real home is ever touched."""
    fake_home = tmp_path / "no-home"
    monkeypatch.setenv("HOME", str(fake_home))
    monkeypatch.setenv("USERPROFILE", str(fake_home))
    recipes = tmp_path / "recipes"
    references = tmp_path / "references"
    recipes.mkdir()
    references.mkdir()
    (recipes / "cell_counting.yaml").write_text(RECIPE, encoding="utf-8")
    (recipes / "particle_tracking.yaml").write_text(TRACKING_RECIPE, encoding="utf-8")
    (references / "calcium-imaging-reference.md").write_text(REFERENCE, encoding="utf-8")
    (references / "INDEX.md").write_text("# Index\\n\\n| doc | title |\\n", encoding="utf-8")
    return recipes, references, fake_home


def index(dirs, **kwargs):
    recipes, references, _home = dirs
    return kz.KnowledgeIndex(recipes, references, **kwargs)


def ledger_file(tmp_path, entries, version=1):
    path = tmp_path / "ledger.json"
    path.write_text(json.dumps({"version": version, "entries": entries}), encoding="utf-8")
    return path


def ledger_record(**kwargs):
    record = {
        "errorCode": "MACRO_ERROR",
        "errorFragment": "Unrecognized command: Watershed",
        "macroPrefix": 'run("Watershed")',
        "confirmedFix": "Convert to Mask before running Watershed.",
        "exampleMacro": 'run("Convert to Mask");\\nrun("Watershed");',
        "timesSeen": 7,
        "confirmationsTrue": 5,
        "confirmationsFalse": 1,
    }
    record.update(kwargs)
    return record


# ---------------------------------------------------------------- recipes

def test_recipe_rows_carry_applicability_and_short_summary(dirs):
    rows = {e.source: e for e in index(dirs).rows()}
    entry = rows["bundled:recipe:cell_counting"]
    assert (entry.kind, entry.scope, entry.status) == ("procedure", "shared", "validated")
    assert entry.title == "Cell Counting (Threshold + Analyze Particles)"
    assert entry.applicability == {
        "domain": "cell_biology",
        "tags": ["cells", "counting", "nuclei"],
        "image_type": ["8-bit", "16-bit"],
        "min_channels": 1,
        "needs_stack": False,
    }
    assert entry.metadata["step_count"] == 2
    assert entry.metadata["parameters"] == ["blur_sigma", "threshold_method"]
    # A summary, not the file: macro code never lands in the row.
    assert "SECRETMASKMACRO" not in entry.content
    assert len(entry.content) <= kz.MAX_CONTENT
    assert "cell_counting.yaml" in entry.content


def test_state_excludes_recipes_that_cannot_apply(dirs):
    idx = index(dirs)
    state = {"domain": "cell_biology", "image_type": "16-bit", "channels": 1}
    ids = [e.id for _why, e in idx.search("count cells", state=state)]
    assert "bundled-recipe-cell_counting" in ids
    assert "bundled-recipe-particle_tracking" not in ids
    # A single-frame image cannot satisfy a time-lapse recipe.
    ids = [e.id for _why, e in idx.search("tracking", state={"domain": "tracking", "is_stack": False})]
    assert "bundled-recipe-particle_tracking" not in ids


# ------------------------------------------------------------- references

def test_reference_rows_index_headings_and_never_the_body(dirs):
    idx = index(dirs)
    rows = [e for e in idx.rows() if e.source.startswith("bundled:reference:")]
    assert len(rows) == 1  # INDEX.md is navigation, not knowledge
    entry = rows[0]
    assert (entry.kind, entry.scope, entry.status) == ("fact", "shared", "validated")
    assert entry.title == "Calcium Imaging Analysis Reference"
    assert entry.source == "bundled:reference:calcium-imaging-reference.md"
    assert "§1 ROI selection" in entry.content and "§2 Photobleaching" in entry.content
    assert "BODYSECRET" not in json.dumps(entry.to_dict())


def test_search_never_leaks_reference_body(dirs):
    results = index(dirs).search("calcium photobleaching ROI")
    assert results
    blob = json.dumps([[why, e.to_dict()] for why, e in results])
    assert "BODYSECRET" not in blob
    assert "SECRETMASKMACRO" not in blob


def test_load_reference_section_slice_and_bound(dirs):
    idx = index(dirs)
    section = idx.load_reference("calcium-imaging-reference.md", section="§1 ROI selection")
    assert section.startswith("## §1 ROI selection")
    assert "BODYSECRET one" in section
    assert "Nested BODYSECRET text." in section  # subsection belongs to §1
    assert "§2 Photobleaching" not in section    # stops at the next peer heading

    whole = idx.load_reference("calcium-imaging-reference")  # suffix optional
    assert "§2 Photobleaching" in whole
    bounded = idx.load_reference("calcium-imaging-reference.md", max_chars=60)
    assert len(bounded) <= 60 and bounded.endswith("[truncated]")

    assert idx.load_reference("missing-reference.md") == ""
    assert idx.load_reference("calcium-imaging-reference.md", section="nope") == ""
    assert idx.load_reference("../secrets.md") == ""
    assert len(idx.diagnostics) == 3


# ----------------------------------------------------------------- ledger

def test_ledger_fixes_are_session_confirmed_and_state_their_limits(dirs, tmp_path):
    path = ledger_file(tmp_path, {"fp-good": ledger_record()})
    idx = index(dirs, ledger_path=path)
    fixes = idx.fixes("MACRO_ERROR", "Unrecognized command: Watershed", 'run("Watershed")')
    assert len(fixes) == 1
    fix = fixes[0]
    assert (fix.kind, fix.scope, fix.status) == ("failure_fix", "shared", "session_confirmed")
    assert fix.status != "validated"
    assert fix.metadata["timesSeen"] == 7
    assert fix.metadata["confirmationsTrue"] == 5
    assert fix.metadata["confirmationsFalse"] == 1
    assert "software reliability, not scientific validity" in fix.content
    assert "Convert to Mask" in fix.content


def test_contradicted_fixes_are_dropped(dirs, tmp_path):
    path = ledger_file(tmp_path, {
        "fp-bad": ledger_record(confirmationsTrue=2, confirmationsFalse=2),
        "fp-worse": ledger_record(confirmationsTrue=1, confirmationsFalse=6),
        "fp-good": ledger_record(confirmationsTrue=4, confirmationsFalse=0),
    })
    idx = index(dirs, ledger_path=path)
    fixes = idx.fixes("MACRO_ERROR", "Unrecognized command: Watershed", 'run("Watershed")')
    assert [f.metadata["fingerprint"] for f in fixes] == ["fp-good"]


def test_ledger_lookup_is_preferred_and_limited(dirs, tmp_path):
    calls = []

    def lookup(error_code, error_fragment, macro_prefix):
        calls.append((error_code, error_fragment, macro_prefix))
        return {
            "fp-a": ledger_record(confirmedFix="A", confirmationsTrue=9, timesSeen=9),
            "fp-b": ledger_record(confirmedFix="B", confirmationsTrue=3, timesSeen=3),
            "fp-c": ledger_record(confirmedFix="C", confirmationsTrue=1, confirmationsFalse=5),
        }

    never = ledger_file(tmp_path, {"fp-file": ledger_record()})
    idx = index(dirs, ledger_lookup=lookup, ledger_path=never)
    fixes = idx.fixes("MACRO_ERROR", "frag", "prefix", limit=1)
    assert calls == [("MACRO_ERROR", "frag", "prefix")]
    assert [f.metadata["fingerprint"] for f in fixes] == ["fp-a"]


# ---------------------------------------------------------------- privacy

def test_private_files_are_ignored_even_when_present(dirs, tmp_path):
    recipes, references, _home = dirs
    for folder in (recipes, references):
        (folder / "learnings.md").write_text(PRIVATE_LEARNINGS, encoding="utf-8")
        (folder / "lab_profile.json").write_text(PRIVATE_PROFILE, encoding="utf-8")
    idx = index(dirs)
    blob = json.dumps([e.to_dict() for e in idx.rows()])
    assert "PRIVATESECRET" not in blob
    assert "learnings" not in blob and "lab_profile" not in blob
    assert "PRIVATESECRET" not in json.dumps(
        [[w, e.to_dict()] for w, e in idx.search("learnings lab profile microscope")]
    )
    assert idx.load_reference("learnings.md") == ""
    assert idx.load_reference("lab_profile.json") == ""
    assert not (tmp_path / "no-home").exists()


# ------------------------------------------------------------ robustness

def test_malformed_inputs_are_reported_not_raised(dirs):
    recipes, references, _home = dirs
    (recipes / "broken.yaml").write_text("name: [unclosed\n  - : :", encoding="utf-8")
    (recipes / "scalar.yaml").write_text("just a string", encoding="utf-8")
    (recipes / "no_id.yaml").write_text("name: Nameless\nsteps: []\n", encoding="utf-8")
    (recipes / "huge.yaml").write_text("id: huge\nname: Huge\n" + "# pad\n" * 500, encoding="utf-8")
    (references / "headless-reference.md").write_text("plain text, no headings\n", encoding="utf-8")
    idx = index(dirs, max_file_bytes=2_000)
    rows = idx.rows()
    assert [e.source for e in rows if e.kind == "procedure"] == [
        "bundled:recipe:cell_counting", "bundled:recipe:particle_tracking"
    ]
    joined = " ".join(idx.diagnostics)
    assert "malformed recipe broken.yaml" in joined
    assert "scalar.yaml: not a mapping" in joined
    assert "no_id.yaml: missing id" in joined
    assert "too large, skipped: huge.yaml" in joined
    assert "malformed reference headless-reference.md" in joined


def test_malformed_ledger_is_reported_not_raised(dirs, tmp_path):
    bad = tmp_path / "bad.json"
    bad.write_text("{not json", encoding="utf-8")
    idx = index(dirs, ledger_path=bad)
    assert idx.fixes("E", "f", "p") == []

    wrong_version = ledger_file(tmp_path, {"fp": ledger_record()}, version=99)
    idx2 = index(dirs, ledger_path=wrong_version)
    assert idx2.fixes("MACRO_ERROR", "Unrecognized", 'run("W")') == []

    shaped = tmp_path / "shape.json"
    shaped.write_text(json.dumps({"version": 1, "entries": {"fp": "oops"}}), encoding="utf-8")
    idx3 = index(dirs, ledger_path=shaped)
    assert idx3.fixes("MACRO_ERROR", "Unrecognized", 'run("W")') == []
    joined = " ".join(idx.diagnostics + idx2.diagnostics + idx3.diagnostics)
    assert "malformed ledger" in joined and "unsupported ledger version" in joined


def test_missing_folders_and_file_count_bound(dirs, tmp_path):
    idx = kz.KnowledgeIndex(tmp_path / "gone", tmp_path / "also-gone")
    assert idx.rows() == []
    assert len(idx.diagnostics) == 2

    bounded = index(dirs, max_files=1)
    recipe_rows = [e for e in bounded.rows() if e.kind == "procedure"]
    assert len(recipe_rows) == 1
    assert any("truncated at max_files=1" in d for d in bounded.diagnostics)


# ----------------------------------------------------- ranking behaviour

def test_search_is_deterministic_and_respects_limit(dirs):
    first = index(dirs).search("cells counting calcium", limit=5)
    second = index(dirs).search("cells counting calcium", limit=5)
    again = index(dirs).search("cells counting calcium", limit=5)
    assert [(w, e.id) for w, e in first] == [(w, e.id) for w, e in second]
    assert [(w, e.id) for w, e in first] == [(w, e.id) for w, e in again]
    assert first[0][1].id == "bundled-recipe-cell_counting"
    assert all(why.startswith(("procedure match", "fact match")) for why, _e in first)
    assert len(index(dirs).search("cells", limit=1)) == 1
    assert index(dirs).search("cells", limit=0) == []


def test_sanitizer_is_applied_to_every_returned_string(dirs):
    def sanitizer(text):
        return text.replace("Cell", "[redacted]").replace("calcium", "[redacted]")

    results = index(dirs).search("cells calcium", sanitizer=sanitizer)
    blob = json.dumps([[why, e.to_dict()] for why, e in results])
    assert "[redacted]" in blob
    assert "Cell Counting" not in blob
    assert "calcium" not in blob.lower() or "[redacted]" in blob

    with pytest.raises(ValueError):
        index(dirs).search("cells", sanitizer=lambda text: None)
