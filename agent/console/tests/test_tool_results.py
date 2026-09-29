"""Return summaries expose useful facts without dumping agent data into chat."""
import json

import pytest
from rich import get_console

from agent.console import activity
from agent.console.tool_results import MAX_SUMMARY_CHARS, formatted_return, summarize_result


@pytest.mark.parametrize("name,ok,payload,expected,kind", [
    ("get_state", True, {"ok": True, "result": {"allImages": [{"title": "blobs.gif"}],
        "activeImage": {"title": "blobs.gif", "width": 256, "height": 254, "nFrames": 99},
        "memory": {"secret": "transport-only"}}},
     "1 image open · Active: blobs.gif · 256 × 254 pixels · 99 frames", "success"),
    ("get_state", True, {"ok": True, "result": {"images": []}}, "No images open", "success"),
    ("run_macro", True, {"ok": True, "result": {"success": True},
        "stateDelta": {"newImages": [{"title": "blobs.gif"}]}}, "Opened blobs.gif", "success"),
    ("run_macro", True, {"ok": True, "result": {"success": True, "output": "raw output"}},
     "Macro completed", "success"),
    ("run_macro", True, {"ok": True, "result": {"success": False,
        "error": "Safe mode refused this operation\nfull trace"}},
     "Safe mode refused this operation", "error"),
    ("run_script", False, {"ok": False, "error": {"code": "error", "message": "Script failed"}},
     "Script failed", "error"),
    ("open_image", False, {"ok": False, "error": {"code": "operation_in_progress"},
        "operation": {"operation_id": "internal-operation-id"}}, "Image is opening", "pending"),
    ("job_status", True, {"ok": True, "result": {"status": "running"}}, "Job is running", "pending"),
    ("get_histogram", True, {"ok": True, "result": {"mean": 0, "min": 0, "max": 255,
        "bins": [0] * 256}}, "Mean: 0 · Min: 0 · Max: 255", "success"),
    ("region_stats", True, {"ok": True, "result": {"mean": 12.25}}, "Mean: 12.25", "success"),
    ("get_open_windows", True, {"ok": True, "result": {"images": ["a", "b"], "nonImages": ["Log"]}},
     "Open: 2 images, 1 other window", "success"),
    ("region_stats", True, {"ok": True, "result": {"rois": [{}, {}]}}, "Found 2 regions", "success"),
    ("list_dialog_components", True, {"ok": True, "result": {"dialogs": []}}, "Found 0 dialogs", "success"),
    ("capture_image", True, {"ok": True, "result": {"base64": "very large encoded picture", "width": 64,
        "height": 64}}, "Image captured · 64 × 64 pixels", "success"),
    ("get_results", True, {"ok": True, "result": "Area,Mean\n12,99\n14,87\n"},
     "Found 2 measurements · 2 columns", "success"),
    ("get_log", True, {"ok": True, "result": {"log": "first\nsecond\nthird"}}, "Read 3 log lines", "success"),
    ("unknown", True, {"ok": True, "result": {"internal": [1, 2, 3]}}, "Completed", "success"),
    ("unknown", True, [1, 2, 3], "Returned 3 items", "success"),
    ("run_macro", True, {"ok": True, "result": {"success": True}, "warning": "Check calibration"},
     "Macro completed · Check calibration", "warning"),
], ids=["state", "empty-state", "opened", "macro", "nested-error", "protocol-error", "pending-open",
        "running-job", "histogram", "statistics", "windows", "regions", "dialogs", "capture",
        "measurements", "log", "unknown-object", "list", "warning"])
def test_known_tool_returns(name, ok, payload, expected, kind):
    summary = summarize_result(name, ok, json.dumps(payload))
    assert summary.text == expected
    assert summary.kind == kind
    assert "\n" not in summary.text and len(summary.text) <= MAX_SUMMARY_CHARS


def test_plain_returns_csv_newlines_and_long_literal_markup():
    assert summarize_result("get_results", True, 'Label,Area\n"two\nlines",5\n').text == "Found 1 measurement · 2 columns"
    assert summarize_result("get_results", True, "").text == "No measurements"
    assert summarize_result("capture_image", True, r"C:\images\AI_Exports\capture.png").text == "Image captured"
    assert summarize_result("get_log", True, "").text == "Log is empty"
    rendered = activity.tool_result_text("unknown", True, "Literal [red]" + "x" * 40_000, detail_id=42)
    assert len(rendered.plain) < 220 and "[red]" in rendered.plain
    assert rendered.get_style_at_offset(get_console(), 5).meta["@click"] == "app.tool_result(42)"
    # Rich text never interprets tool output as executable markup.
    assert rendered.get_style_at_offset(get_console(), rendered.plain.index("[red]")).color.name == "bright_black"


def test_only_pending_operations_are_yellow_failures_are_red():
    pending = activity.tool_result_text("open_image", False, json.dumps({
        "ok": False, "error": {"code": "operation_in_progress"}}))
    assert pending.style == "yellow" and "✗" not in pending.plain
    failure = activity.tool_result_text("run_macro", False, '{"error":{"message":"No image open"}}')
    assert failure.style == "red" and "No image open" in failure.plain


def test_full_formatter_preserves_every_json_field_and_plain_text():
    raw = json.dumps({"output": "x" * 40_000, "tail": "FULL_RETURN_END", "image": "blobs.gif"})
    formatted = formatted_return(raw)
    assert json.loads(formatted) == json.loads(raw)
    assert "FULL_RETURN_END" in formatted and len(formatted) > 40_000
    assert formatted_return("line one\n[red]literal[/red]\n") == "line one\n[red]literal[/red]\n"


def test_artifact_original_preserves_line_endings(tmp_path):
    from agent.console.tool_results_ui import ToolResultDetail
    raw = "first line\r\nsecond line\r\n"
    path = tmp_path / "return.txt"
    path.write_bytes(raw.encode("utf-8"))
    assert ToolResultDetail("get_log", "Read 2 log lines", artifact=path).read() == raw


def test_unfamiliar_image_metadata_does_not_break_display():
    result = summarize_result("get_metadata", True, '{"result":{"image":"blobs.gif"}}')
    assert result.text == "Image metadata read"
