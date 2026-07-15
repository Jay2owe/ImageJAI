from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest


AUDITOR_PATH = Path(__file__).with_name("auditor.py")
SPEC = importlib.util.spec_from_file_location("auditor_under_test", AUDITOR_PATH)
auditor = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(auditor)


def minimal_result():
    return {
        "status": "pass",
        "checks": [
            {
                "check": "count",
                "status": "pass",
                "message": "2 objects detected.",
                "affected_rows": [],
            }
        ],
        "summary": "All checks passed. Results look valid.",
    }


def test_public_api_is_explicit():
    assert auditor.__all__ == [
        "audit_results",
        "audit_current_results",
        "audit_csv_file",
        "format_report",
        "main",
    ]
    for name in auditor.__all__:
        assert hasattr(auditor, name)
    assert "_format_report" not in auditor.__all__
    assert auditor._format_report is auditor.format_report


def test_format_report_private_alias_preserves_output():
    result = minimal_result()

    formatted = auditor.format_report(result)

    assert formatted == auditor._format_report(result)
    assert "MEASUREMENT AUDIT REPORT" in formatted
    assert "[PASS]" in formatted


def test_audit_csv_file_reads_and_delegates(tmp_path, monkeypatch):
    csv_path = tmp_path / "results.csv"
    csv_path.write_text("Area,Mean\n10,5\n", encoding="utf-8")
    expected = minimal_result()
    captured = {}

    def fake_audit_results(csv_string, **kwargs):
        captured["csv_string"] = csv_string
        captured["kwargs"] = kwargs
        return expected

    monkeypatch.setattr(auditor, "audit_results", fake_audit_results)

    result = auditor.audit_csv_file(
        csv_path,
        check="area",
        unit="um",
        pixel_size=0.25,
        bit_depth=16,
    )

    assert result is expected
    assert captured == {
        "csv_string": "Area,Mean\n10,5\n",
        "kwargs": {
            "pixel_size": 0.25,
            "unit": "um",
            "bit_depth": 16,
            "check": "area",
        },
    }


def test_audit_current_results_uses_ij_helpers_and_metadata(monkeypatch):
    expected = minimal_result()
    captured = {}

    def fake_get_results_table():
        return {"ok": True, "result": "Area,Mean\n10,5\n"}

    def fake_get_image_info():
        return {"ok": True, "result": {"type": "16-bit"}}

    def fake_get_metadata():
        return {
            "ok": True,
            "result": {
                "calibration": {
                    "pixelWidth": 0.5,
                    "unit": "um",
                }
            },
        }

    def fake_audit_results(csv_string, **kwargs):
        captured["csv_string"] = csv_string
        captured["kwargs"] = kwargs
        return expected

    monkeypatch.setattr(
        auditor,
        "_load_ij_helpers",
        lambda: (fake_get_results_table, fake_get_image_info, fake_get_metadata),
    )
    monkeypatch.setattr(auditor, "audit_results", fake_audit_results)

    result = auditor.audit_current_results(check="area")

    assert result is expected
    assert captured == {
        "csv_string": "Area,Mean\n10,5\n",
        "kwargs": {
            "pixel_size": 0.5,
            "unit": "um",
            "bit_depth": 16,
            "check": "area",
        },
    }


def test_audit_current_results_no_results_message_is_compatible(monkeypatch):
    monkeypatch.setattr(
        auditor,
        "_load_ij_helpers",
        lambda: (
            lambda: {"ok": True, "result": ""},
            lambda: {"ok": True, "result": {}},
            lambda: {"ok": True, "result": {}},
        ),
    )

    with pytest.raises(RuntimeError) as excinfo:
        auditor.audit_current_results()

    assert str(excinfo.value) == "No results table available in ImageJ."


def test_main_routes_current_results_through_helper(monkeypatch, capsys):
    expected = minimal_result()
    captured = {}

    def fake_audit_current_results(**kwargs):
        captured.update(kwargs)
        return expected

    def fake_format_report(result):
        assert result is expected
        return "FORMATTED"

    monkeypatch.setattr(sys, "argv", [
        "auditor.py",
        "--check",
        "area",
        "--unit",
        "um",
        "--pixel-size",
        "0.25",
        "--bit-depth",
        "16",
    ])
    monkeypatch.setattr(auditor, "audit_current_results", fake_audit_current_results)
    monkeypatch.setattr(auditor, "format_report", fake_format_report)

    auditor.main()

    assert captured == {
        "pixel_size": 0.25,
        "unit": "um",
        "bit_depth": 16,
        "check": "area",
    }
    assert capsys.readouterr().out == "FORMATTED\n"


def test_main_routes_csv_through_helper(monkeypatch, capsys):
    expected = minimal_result()
    captured = {}

    def fake_audit_csv_file(path, **kwargs):
        captured["path"] = path
        captured["kwargs"] = kwargs
        return expected

    monkeypatch.setattr(sys, "argv", [
        "auditor.py",
        "--csv",
        "results.csv",
        "--check",
        "outliers",
        "--unit",
        "um",
    ])
    monkeypatch.setattr(auditor, "audit_csv_file", fake_audit_csv_file)
    monkeypatch.setattr(auditor, "format_report", lambda result: "CSV REPORT")

    auditor.main()

    assert captured == {
        "path": "results.csv",
        "kwargs": {
            "pixel_size": None,
            "unit": "um",
            "bit_depth": None,
            "check": "outliers",
        },
    }
    assert capsys.readouterr().out == "CSV REPORT\n"


def test_main_json_preserves_result_shape(monkeypatch, capsys):
    expected = minimal_result()

    monkeypatch.setattr(sys, "argv", ["auditor.py", "--json"])
    monkeypatch.setattr(auditor, "audit_current_results", lambda **kwargs: expected)

    auditor.main()

    assert json.loads(capsys.readouterr().out) == expected


def test_main_no_results_message_and_exit_code(monkeypatch, capsys):
    def fake_audit_current_results(**kwargs):
        raise auditor._NoResultsTable("No results table available in ImageJ.")

    monkeypatch.setattr(sys, "argv", ["auditor.py"])
    monkeypatch.setattr(auditor, "audit_current_results", fake_audit_current_results)

    with pytest.raises(SystemExit) as excinfo:
        auditor.main()

    assert excinfo.value.code == 1
    assert capsys.readouterr().out == "No results table available in ImageJ.\n"


@pytest.mark.parametrize(
    "csv_text, message",
    [
        ("Area,Mean\nnan,2\n", "non-finite"),
        ("Area,Mean\ninf,2\n", "non-finite"),
        ("Area,Mean\n-1,2\n", "negative"),
        ("Major,Minor\n2,3\n", "Minor exceeds Major"),
        ("Min,Mean,Max\n5,2,4\n", "impossible Min/Mean/Max"),
        ("Count,Area\n-2,1\n", "negative"),
    ],
)
def test_audit_rejects_invalid_numeric_domains(csv_text, message):
    result = auditor.audit_results(csv_text)

    assert result["status"] == "fail"
    validity = next(c for c in result["checks"] if c["check"] == "numeric_validity")
    assert message in validity["message"]


@pytest.mark.parametrize("pixel_size", [0, -1, float("nan"), float("inf")])
def test_audit_rejects_invalid_pixel_size(pixel_size):
    result = auditor.audit_results("Area\n10\n", pixel_size=pixel_size, unit="um")

    assert result["status"] == "fail"
    assert result["checks"][0]["check"] == "audit_parameters"


def test_unknown_named_check_is_a_failure_not_an_empty_pass():
    result = auditor.audit_results("Area\n10\n", check="definitely_not_a_check")

    assert result["status"] == "fail"
    assert result["checks"][0]["check"] == "unknown_check"
