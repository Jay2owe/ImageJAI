"""Tests for receipts, audit-CSV reading, statement and friction summaries.

Covers feature doc 6.24-6.30 and 6.39-6.40 on the console side. Every path is
a tmp_path; the friction reader is pointed at a temp root so the real
~/.imagej-ai is never touched.
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from agent.console import receipts as R
from agent.console.posture import Posture, PostureController, FolderPostureStore


def _fixed_clock(second: int = 0):
    return lambda: datetime(2025, 3, 4, 9, 30, second, tzinfo=timezone.utc)


# ---------------------------------------------------------------------------
# live receipts
# ---------------------------------------------------------------------------

def test_receipt_append_records_the_call_facts():
    log = R.ReceiptsLog(clock=_fixed_clock())
    receipt = log.append("anthropic", "claude-sonnet-4-5", Posture.PSEUDONYMISED,
                         command="get_image_info", tokens_in=1200, tokens_out=300,
                         bytes_out=4096, bytes_in=512,
                         categories=("image_metadata", "results_table"),
                         redaction=R.RedactionStatus.APPLIED,
                         fields_redacted=("Label", "StageLabel"))

    assert receipt.provider == "anthropic"
    assert receipt.posture is Posture.PSEUDONYMISED
    assert receipt.categories == ("image_metadata", "results_table")
    assert receipt.redaction.applied
    assert receipt.time_text == "09:30:00"
    assert receipt.as_row() == ("09:30:00", "get_image_info", "4096", "yes")


def test_receipt_without_a_cached_payload_shows_the_fallback_note():
    log = R.ReceiptsLog()
    receipt = log.append("ollama", "gemma3:27b", Posture.ON_PREMISES)
    assert receipt.detail_text == R.NO_PAYLOAD_NOTE


def test_receipts_summary_counts_and_counter_text():
    log = R.ReceiptsLog()
    log.append("anthropic", "claude", Posture.PSEUDONYMISED, command="get_state",
               tokens_in=10, tokens_out=5, bytes_out=100,
               redaction=R.RedactionStatus.APPLIED, categories=("image_metadata",))
    log.append("anthropic", "claude", Posture.PSEUDONYMISED, command="visual.granted",
               tokens_in=20, tokens_out=7, bytes_out=200,
               redaction=R.RedactionStatus.NOT_APPLIED, categories=("pixels",))
    log.append("anthropic", "claude", Posture.PSEUDONYMISED, command="request_visual",
               redaction=R.RedactionStatus.FAILED)

    summary = log.summary()
    assert summary.total == 3
    assert summary.pseudonymised == 1
    assert summary.visual_overrides == 2
    assert summary.tokens_in == 30 and summary.tokens_out == 12
    assert summary.bytes_out == 300
    assert summary.redaction_failures == 1
    assert summary.by_category == {"image_metadata": 1, "pixels": 1}
    assert summary.counter_text == "3 (1 pseudonymised, 2 visual overrides)"


def test_receipts_log_is_append_only_for_counters_but_bounded_for_display():
    log = R.ReceiptsLog(limit=2)
    for index in range(5):
        log.append("openai", "gpt-5", Posture.STANDARD, command=f"cmd{index}",
                   bytes_out=1)
    assert len(log.recent()) == 2
    assert [r.command for r in log.recent()] == ["cmd3", "cmd4"]
    assert log.summary().total == 5      # nothing can be un-sent
    assert len(log) == 5


def test_large_payloads_are_capped_with_the_plugin_marker():
    payload = "x" * (R.MAX_REDACTED_PAYLOAD_BYTES + 5000)
    capped = R.cap_redacted_payload(payload)
    assert len(capped.encode("utf-8")) <= R.MAX_REDACTED_PAYLOAD_BYTES
    assert capped.endswith("\n...(truncated)...")
    assert R.cap_redacted_payload("short") == "short"


# ---------------------------------------------------------------------------
# audit CSV
# ---------------------------------------------------------------------------

def _write_audit(folder: Path, body_lines) -> Path:
    path = folder / "AI_Exports" / R.AUDIT_FILE_NAME
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join([R.AUDIT_HEADER, *body_lines]) + "\n", encoding="utf-8")
    return path


GOOD_ROWS = [
    "2025-03-04T09:30:00Z,sess-1,get_image_info,Pseudonymised,api.anthropic.com,"
    "microscopy,4096,512,abc123,true,Label;StageLabel,ok",
    "2025-03-04T09:31:00Z,sess-1,visual.granted,Pseudonymised,api.anthropic.com,"
    "microscopy,10,0,abc123,false,,user granted one call",
    "2025-03-04T09:32:00Z,sess-1,posture.downshift,On-premises,,,0,0,,false,,"
    "\"from=Standard, to=On-premises\"",
]


def test_audit_csv_path_resolution(tmp_path):
    assert R.audit_csv_path(tmp_path).name == R.AUDIT_FILE_NAME
    assert R.audit_csv_path(tmp_path).parent.name == "AI_Exports"
    assert R.audit_csv_path(tmp_path / "AI_Exports") == tmp_path / "AI_Exports" / R.AUDIT_FILE_NAME
    explicit = tmp_path / "AI_Exports" / R.AUDIT_FILE_NAME
    assert R.audit_csv_path(explicit) == explicit


def test_missing_audit_file_summarises_as_empty(tmp_path):
    summary = R.summarise_audit(tmp_path)
    assert summary.total_rows == 0
    assert summary.date_range == "none"
    assert summary.malformed_rows == 0


def test_audit_rows_parse_and_summarise(tmp_path):
    _write_audit(tmp_path, GOOD_ROWS)
    summary = R.summarise_audit(tmp_path)

    assert summary.total_rows == 3
    assert summary.redacted_rows == 1
    assert summary.visual_grant_rows == 1
    assert summary.posture_event_rows == 1
    assert summary.downshift_rows == 1
    assert summary.total_bytes_out == 4106
    assert summary.total_bytes_in == 512
    assert summary.fields_redacted == ("Label", "StageLabel")
    assert summary.posture_counts == {"On-premises": 1, "Pseudonymised": 2}
    assert summary.category_counts["visual"] == 1
    assert summary.date_range.startswith("2025-03-04T09:30:00")


def test_quoted_cells_and_formula_protection_round_trip(tmp_path):
    _write_audit(tmp_path, [
        "2025-03-04T09:30:00Z,s,run_macro,Standard,,,1,2,,false,,\"a, b\"",
        "2025-03-04T09:31:00Z,s,run_macro,Standard,,,1,2,,false,,'=cmd()",
    ])
    rows, diagnostics = R.read_audit_rows(tmp_path)
    assert diagnostics == []
    assert rows[0].notes == "a, b"
    assert rows[1].notes == "=cmd()"     # the ' guard is removed on read


def test_malformed_row_is_reported_not_swallowed(tmp_path):
    _write_audit(tmp_path, [
        GOOD_ROWS[0],
        "2025-03-04T09:31:00Z,sess-1,short_row,Standard,,,1",
        "not-a-timestamp,sess-1,get_state,Standard,,,0,0,,false,,x",
    ])
    summary = R.summarise_audit(tmp_path)

    assert summary.total_rows == 1
    assert summary.malformed_rows == 2
    assert summary.malformed_diagnostics[0].startswith("line 3: Expected 12 audit columns")
    assert summary.malformed_diagnostics[1] == "line 4: invalid timestamp"


def test_strict_mode_raises_on_the_first_bad_line(tmp_path):
    _write_audit(tmp_path, ["2025-03-04T09:31:00Z,sess-1,short,Standard,,,1"])
    with pytest.raises(R.AuditRowError) as excinfo:
        R.read_audit_rows(tmp_path, strict=True)
    assert str(excinfo.value).startswith("line 2:")


@pytest.mark.parametrize("line, fragment", [
    ("2025-03-04T09:30:00Z,s,c,Relaxed,,,0,0,,false,,n", "invalid privacy posture"),
    ("2025-03-04T09:30:00Z,s,c,Standard,,,-1,0,,false,,n", "invalid non-negative integer"),
    ("2025-03-04T09:30:00Z,s,c,Standard,,,0,0,,maybe,,n", "invalid boolean"),
])
def test_bad_columns_name_the_problem(line, fragment):
    with pytest.raises(R.AuditRowError) as excinfo:
        R.AuditRow.from_csv_line(line)
    assert fragment in str(excinfo.value)


def test_unbalanced_quotes_are_rejected():
    with pytest.raises(R.AuditRowError):
        R.parse_csv_line('a,"unterminated')
    with pytest.raises(R.AuditRowError):
        R.parse_csv_line('a,b"c')


def test_oversized_audit_file_fails_clearly(tmp_path, monkeypatch):
    _write_audit(tmp_path, GOOD_ROWS)
    monkeypatch.setattr(R, "MAX_SUMMARY_BYTES", 10)
    with pytest.raises(R.AuditLogError) as excinfo:
        R.summarise_audit(tmp_path)
    assert "exceeds summary limit" in str(excinfo.value)


def test_a_directory_where_the_csv_should_be_fails_clearly(tmp_path):
    (tmp_path / "AI_Exports" / R.AUDIT_FILE_NAME).mkdir(parents=True)
    with pytest.raises(R.AuditLogError):
        R.summarise_audit(tmp_path)


# ---------------------------------------------------------------------------
# Data Handling Statement
# ---------------------------------------------------------------------------

def test_statement_has_every_required_section(tmp_path):
    _write_audit(tmp_path, GOOD_ROWS)
    text = R.generate_data_handling_statement(
        tmp_path, Posture.ON_PREMISES, today=datetime(2025, 3, 4, tzinfo=timezone.utc))

    for heading in ("## 1. Purpose", "## 2. Data flow",
                    "## 3. Pseudonymisation scheme (UK GDPR Art. 4(5))",
                    "## 4. Vendor contractual posture", "## 5. Audit trail",
                    "## 6. Opt-out and escalation", "## 7. Limitations"):
        assert heading in text
    assert "localhost:7746" in text
    assert "PseudonymisationFilter" in text
    assert "downsampled to ≤512×512" in text
    assert "Posture in force: On-premises" in text
    assert "github.com/Jay2owe/ImageJAI" in text


def test_statement_includes_the_vendor_terms(tmp_path):
    text = R.generate_data_handling_statement(tmp_path, Posture.STANDARD)
    for term in R.VENDOR_TERMS:
        assert term.vendor in text
    assert "PROMPTS AND OUTPUTS ARE USED FOR TRAINING" in text
    assert "anthropic.com/legal/commercial-terms" in text


def test_statement_includes_the_audit_counts(tmp_path):
    _write_audit(tmp_path, GOOD_ROWS)
    text = R.generate_data_handling_statement(tmp_path, Posture.PSEUDONYMISED)
    assert "Rows in this project to date: 3" in text
    assert "Pseudonymised calls: 1" in text
    assert "Visual override grants: 1" in text
    assert "Posture downshifts: 1" in text


def test_statement_includes_the_posture_history(tmp_path):
    controller = PostureController(FolderPostureStore(), posture=Posture.STANDARD)
    controller.request_posture(Posture.ON_PREMISES, tmp_path, "REC condition")
    controller.override_downshift(Posture.STANDARD, tmp_path, "phantom slides only")

    text = R.generate_data_handling_statement(
        tmp_path, Posture.STANDARD,
        posture_history=controller.events + controller.overrides)

    assert "### Posture history" in text
    assert "data_governance.posture.requested" in text
    assert "posture.override" in text
    assert "phantom slides only" in text
    assert str(tmp_path) not in text.split("### Posture history", 1)[1]


def test_statement_history_falls_back_to_a_plain_line(tmp_path):
    text = R.generate_data_handling_statement(tmp_path, Posture.STANDARD)
    assert "No posture change was recorded for this project." in text


def test_statement_is_written_under_ai_exports(tmp_path):
    target = R.write_data_handling_statement(
        tmp_path, Posture.PSEUDONYMISED,
        today=datetime(2025, 3, 4, tzinfo=timezone.utc))

    assert target.parent.name == "AI_Exports"
    assert target.name == f"DataHandlingStatement_{tmp_path.name}_20250304.md"
    assert "# ImageJAI — Data Handling Statement" in target.read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# friction log
# ---------------------------------------------------------------------------

BASE_MS = 1_700_000_000_000


def _friction_line(ts, command, error):
    return json.dumps({
        "ts": ts, "agent_id": "claude", "command": command,
        "args_summary": "{}", "error": error,
        "normalised_error": R.normalise_error(error),
    })


def test_normalise_error_strips_paths_numbers_and_hex():
    text = R.normalise_error(r"Failed C:\proj\img_12.tif at 0xFF after 3 tries")
    assert text == "failed <path> at <hex> after n tries"
    assert len(R.normalise_error("x" * 500)) == 200


def test_friction_reader_returns_nothing_for_a_missing_journal(tmp_path):
    summary = R.summarise_friction(tmp_path, now_ms=BASE_MS)
    assert summary.entries == 0
    assert summary.patterns == ()
    assert summary.text == "No friction recorded."


def test_friction_summary_groups_repeated_failures(tmp_path):
    lines = [_friction_line(BASE_MS - 1000 * i, "run_macro",
                            f"No image open (attempt {i})") for i in range(4)]
    lines.append(_friction_line(BASE_MS - 500, "get_results", "Results table empty"))
    (tmp_path / "friction.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

    summary = R.summarise_friction(tmp_path, now_ms=BASE_MS)

    assert summary.entries == 5
    assert summary.by_command == {"get_results": 1, "run_macro": 4}
    assert len(summary.patterns) == 1          # threshold is 3 repeats
    pattern = summary.patterns[0]
    assert pattern.command == "run_macro"
    assert pattern.count == 4
    assert pattern.normalised_error == "no image open (attempt n)"
    assert "run_macro x4" in summary.text


def test_friction_patterns_ignore_failures_outside_the_window(tmp_path):
    old = BASE_MS - R.FRICTION_WINDOW_MS - 60_000
    lines = [_friction_line(old + i, "run_macro", "No image open") for i in range(5)]
    (tmp_path / "friction.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

    summary = R.summarise_friction(tmp_path, now_ms=BASE_MS)

    assert summary.entries == 5      # history is still readable
    assert summary.patterns == ()    # but nothing is "currently" failing


def test_friction_reads_rotated_generations_oldest_first(tmp_path):
    (tmp_path / "friction.jsonl.2").write_text(
        _friction_line(BASE_MS - 3000, "a", "oldest") + "\n", encoding="utf-8")
    (tmp_path / "friction.jsonl.1").write_text(
        _friction_line(BASE_MS - 2000, "b", "middle") + "\n", encoding="utf-8")
    (tmp_path / "friction.jsonl").write_text(
        _friction_line(BASE_MS - 1000, "c", "newest") + "\n", encoding="utf-8")

    entries, files, skipped = R.read_friction_entries(tmp_path)

    assert [e.error for e in entries] == ["oldest", "middle", "newest"]
    assert [p.name for p in files] == ["friction.jsonl.2", "friction.jsonl.1",
                                       "friction.jsonl"]
    assert skipped == 0


def test_friction_skips_corrupt_and_oversized_lines_and_counts_them(tmp_path):
    good = _friction_line(BASE_MS - 100, "run_macro", "No image open")
    huge = json.dumps({"ts": BASE_MS, "command": "run_macro",
                       "error": "x" * (R.FRICTION_MAX_LINE_BYTES + 10)})
    (tmp_path / "friction.jsonl").write_text(
        "\n".join([good, "{truncated", huge, "[]"]) + "\n", encoding="utf-8")

    entries, _, skipped = R.read_friction_entries(tmp_path)

    assert len(entries) == 1
    assert skipped == 3


def test_friction_structured_columns_survive_the_round_trip(tmp_path):
    row = json.dumps({"ts": BASE_MS, "command": "saveas", "error": "blocked",
                      "outcome": "blocked", "severity": "L1_reject",
                      "rule_id": "saveas_overwrite", "target": "image-abc.tif"})
    (tmp_path / "friction.jsonl").write_text(row + "\n", encoding="utf-8")

    entry = R.read_friction_entries(tmp_path)[0][0]

    assert entry.outcome == "blocked"
    assert entry.severity == "L1_reject"
    assert entry.rule_id == "saveas_overwrite"
    assert entry.target == "image-abc.tif"


def test_friction_config_dir_follows_imagejai_home(tmp_path, monkeypatch):
    monkeypatch.setenv("IMAGEJAI_HOME", str(tmp_path))
    assert R.config_dir() == tmp_path
    (tmp_path / "friction.jsonl").write_text(
        _friction_line(BASE_MS, "run_macro", "boom") + "\n", encoding="utf-8")
    assert R.summarise_friction(now_ms=BASE_MS).entries == 1
