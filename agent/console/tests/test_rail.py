"""Rail model, action, chip, and pane-content tests (S2.3-S2.18, S2.25-S2.26).

No Fiji and no network: the connection facade is a recording fake, so every
test asserts on the payload the rail *would* send.
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from agent.console import rail


class FakeFiji:
    """Records commands; raises what a test tells it to raise."""

    def __init__(self, image_info=None, results="", fail=None):
        self.calls = []
        self.commands = []
        self._image_info = image_info or {}
        self._results = results
        self._fail = fail

    def execute_macro(self, code):
        if self._fail:
            raise self._fail
        self.calls.append(("execute_macro", code))
        return {"ok": True}

    def command(self, payload):
        if self._fail:
            raise self._fail
        self.commands.append(payload)
        return {"ok": True}

    def image_info(self):
        return {"ok": True, "result": dict(self._image_info)}

    def results(self):
        return {"ok": True, "result": {"results_table": self._results}}


# ---------------------------------------------------------------- rail model


def test_sections_follow_java_order():
    assert [s.title for s in rail.SECTIONS] == [
        "Session",
        "Agent",
        "Fiji hotlines",
        "Guidance",
    ]


def test_every_item_has_help_and_a_registered_action():
    for item in rail.rail_items():
        assert item.label and item.help and item.glyph
        assert item.action in rail.ACTIONS


def test_hotline_help_is_the_exact_macro():
    assert rail.find_item("fiji.close_all").help == "run('Close All');"
    assert rail.find_item("fiji.reset_roi").help == "roiManager('Reset');"


def test_find_item_rejects_unknown_id():
    with pytest.raises(KeyError):
        rail.find_item("nope")


# --------------------------------------------------------------- status line


@pytest.mark.parametrize(
    "text,expected",
    [
        (None, " "),
        ("", " "),
        ("Done: Close all images", "Done: Close all images"),
        ("x" * 28, "x" * 28),
        ("y" * 40, "y" * 25 + "..."),
    ],
)
def test_short_status(text, expected):
    assert rail.short_status(text) == expected


def test_readable_message_falls_back_to_class_name():
    assert rail.readable_message(RuntimeError()) == "RuntimeError"
    assert rail.readable_message(RuntimeError("boom")) == "boom"


def test_readable_message_walks_to_root_cause():
    try:
        try:
            raise ValueError("root cause")
        except ValueError as inner:
            raise OSError() from inner
    except OSError as exc:
        assert rail.readable_message(exc) == "root cause"


# ------------------------------------------------------------- item gating


def test_commands_item_is_disabled_without_a_command_list():
    state = rail.item_state("agent.commands", session_alive=True, has_commands=False)
    assert not state.enabled and state.tooltip == "no command list"


def test_agent_items_need_a_live_session():
    for item_id in ("agent.new_chat", "recipe.save"):
        state = rail.item_state(item_id, session_alive=False)
        assert not state.enabled
        assert state.tooltip == "No embedded agent running"
    assert rail.item_state("agent.new_chat", session_alive=True).enabled


def test_hotlines_are_always_enabled():
    assert rail.item_state("fiji.close_all").enabled


# ------------------------------------------------------------- S2.7 hotlines


def test_close_all_runs_the_java_macro():
    conn = FakeFiji()
    result = rail.dispatch("fiji.close_all", conn)
    assert result.ok and result.status == "Done: Close all images"
    assert conn.calls == [("execute_macro", rail.CLOSE_ALL_MACRO)]


def test_reset_roi_runs_the_java_macro():
    conn = FakeFiji()
    assert rail.dispatch("fiji.reset_roi", conn).ok
    assert conn.calls == [("execute_macro", rail.RESET_ROI_MACRO)]


def test_z_project_refuses_a_single_slice():
    conn = FakeFiji(image_info={"slices": 1, "frames": 1})
    result = rail.dispatch("fiji.z_project", conn)
    assert not result.ok and result.skipped
    assert result.status == "Open a stack first."
    assert conn.calls == []


@pytest.mark.parametrize(
    "info", [{"isStack": True}, {"slices": 12}, {"frames": 4}]
)
def test_z_project_runs_on_a_stack(info):
    conn = FakeFiji(image_info=info)
    result = rail.dispatch("fiji.z_project", conn)
    assert result.ok
    assert conn.calls == [("execute_macro", rail.Z_PROJECT_MACRO)]


def test_hotline_failure_becomes_a_status_and_log_line():
    conn = FakeFiji(fail=RuntimeError("connection refused"))
    result = rail.dispatch("fiji.close_all", conn)
    assert not result.ok and not result.skipped
    assert result.status == "connection refused"
    assert "Hotline failed: Close all images" in result.log


# ---------------------------------------------------------------- S2.3 WIP


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("  Spine Density  ", "spine-density"),
        ("--weird--", "weird"),
        ("ok.name_1-2", "ok.name_1-2"),
        ("###", ""),
    ],
)
def test_sanitize_slug(raw, expected):
    assert rail.sanitize_slug(raw) == expected


def test_new_wip_writes_the_template_once(tmp_path):
    first = rail.new_wip(None, slug="My Note", workspace=tmp_path)
    path = tmp_path / "agent" / "work_in_progress" / "my-note.md"
    assert path.is_file()
    assert path.read_text(encoding="utf-8").startswith("# my-note")
    assert first.status == "WIP note created"

    path.write_text("edited", encoding="utf-8")
    again = rail.new_wip(None, slug="my-note", workspace=tmp_path)
    assert path.read_text(encoding="utf-8") == "edited"
    assert "Reusing existing" in again.log


def test_new_wip_sends_a_prompt_when_an_agent_is_running(tmp_path):
    result = rail.new_wip(None, slug="scope", workspace=tmp_path, session_alive=True)
    assert result.status == "WIP prompt sent"
    assert result.prompt.startswith("Start new WIP: read `")
    assert result.prompt.endswith("` and help me scope it.")


def test_new_wip_rejects_an_empty_slug(tmp_path):
    result = rail.new_wip(None, slug="!!!", workspace=tmp_path)
    assert not result.ok and result.status == "Enter a slug"


def test_workspace_that_is_already_agent_is_not_nested(tmp_path):
    workspace = tmp_path / "agent"
    workspace.mkdir()
    rail.new_wip(None, slug="a", workspace=workspace)
    assert (workspace / "work_in_progress" / "a.md").is_file()


# ----------------------------------------------------------- S2.4 commands


def test_commands_popup_groups_builtin_and_user():
    result = rail.commands_popup(
        None,
        builtin=[{"command": "/clear", "description": "reset"}],
        user=[("/mine", "")],
        session_alive=True,
    )
    titles = [group["title"] for group in result.data["groups"]]
    assert titles == ["Built-in", "User"]
    assert result.data["groups"][1]["separator_before"] is True


def test_commands_popup_without_builtins_reports_no_command_list():
    result = rail.commands_popup(None, builtin=[], session_alive=True)
    assert not result.ok and result.status == "No command list"


def test_commands_popup_needs_a_session():
    assert rail.commands_popup(None, builtin=[("/x", "")]).status == "No agent running"


# --------------------------------------------------------- S2.5 new chat


def test_new_agent_chat_sends_slash_clear():
    result = rail.new_agent_chat(None, session_alive=True)
    assert result.prompt == "/clear" and result.status == "Sent /clear"


def test_new_agent_chat_without_a_session():
    assert not rail.new_agent_chat(None, session_alive=False).ok



# ------------------------------------------------------ S2.6 console chats


def test_console_chats_sorted_and_filtered():
    result = rail.console_chats_popup(
        None,
        sessions=[
            {"id": "aaa", "title": "", "updated": 1},
            {"id": "bbb", "title": "Second", "provider": "groq", "updated": 9},
            {"id": "bad id!", "title": "dropped", "updated": 99},
        ],
    )
    items = result.data["items"]
    assert [row["id"] for row in items] == ["bbb", "aaa"]
    assert items[0]["label"] == "Second  [groq]"
    assert items[1]["title"] == "New session"
    assert items[0]["tooltip"] == "Resume ImageJAI Console session bbb"


def test_console_chats_empty_state():
    result = rail.console_chats_popup(None, sessions=[])
    assert result.status == "No console chats yet"


# ------------------------------------------------------- S2.13 save recipe


def test_save_recipe_prompt_names_the_target_folder(tmp_path):
    result = rail.save_recipe_prompt_action(
        None, recipes_dir=tmp_path / "recipes", session_alive=True
    )
    assert (tmp_path / "recipes").is_dir()
    assert str((tmp_path / "recipes").resolve()) in result.prompt
    assert "image_specific: true" in result.prompt
    assert result.status == "Recipe prompt sent"


def test_save_recipe_needs_a_session(tmp_path):
    assert not rail.save_recipe_prompt_action(
        None, recipes_dir=tmp_path, session_alive=False
    ).ok


# ------------------------------------------------------------ S2.14 audit


def test_parse_bit_depth_and_calibration():
    assert rail.parse_bit_depth("16-bit grayscale") == 16
    assert rail.parse_bit_depth("RGB") is None
    assert rail.parse_calibration("0.325 micron/px") == (0.325, "micron")
    assert rail.parse_calibration("uncalibrated") == (None, None)


def test_audit_results_injects_the_summary():
    conn = FakeFiji(
        image_info={"type": "16-bit", "calibration": "0.5 um/px"}, results="Area\n10\n"
    )
    seen = {}

    def fake_auditor(csv, pixel_size=None, unit=None, bit_depth=None):
        seen.update(csv=csv, pixel_size=pixel_size, unit=unit, bit_depth=bit_depth)
        return {"summary": "2 rows look fine"}

    result = rail.audit_results(conn, auditor=fake_auditor)
    assert result.status == "Audit sent"
    assert result.prompt == "Audit my results:\n2 rows look fine"
    assert seen == {
        "csv": "Area\n10\n",
        "pixel_size": 0.5,
        "unit": "um",
        "bit_depth": 16,
    }


def test_audit_results_reports_an_empty_summary():
    conn = FakeFiji(image_info={}, results="")
    result = rail.audit_results(conn, auditor=lambda *a, **k: {"summary": "  "})
    assert not result.ok and result.status == "Audit returned no summary."


# ------------------------------------------------- S1.25 / S1.26 gui actions


def test_highlight_roi_payload_matches_the_gui_action_contract():
    conn = FakeFiji()
    rail.highlight_roi(conn, "cells.tif", (10, 20, 30, 40))
    assert conn.commands == [
        {
            "command": "gui_action",
            "type": "highlight_roi",
            "title": "cells.tif",
            "roi": [10, 20, 30, 40],
        }
    ]


def test_focus_image_payload():
    conn = FakeFiji()
    rail.focus_image(conn, "cells.tif")
    assert conn.commands == [
        {"command": "gui_action", "type": "focus_image", "title": "cells.tif"}
    ]


def test_gui_action_failure_is_reported():
    conn = FakeFiji(fail=RuntimeError("no socket"))
    assert rail.focus_image(conn, "x").status == "no socket"


# ------------------------------------------------------ S1.15 suggestion chips


PHRASES = {
    "image.close_all": ["close all images"],
    "image.z_project": ["z project maximum"],
    "roi.reset": ["reset roi manager"],
    "results.measure": ["measure the selection"],
}


def engine():
    return rail.SuggestionEngine(PHRASES)


def test_normalise_strips_punctuation_and_keeps_slash_commands():
    assert rail.normalise("Close ALL images!!") == "close all images"
    assert rail.normalise("/clear   now  please") == "/clear now please"
    assert rail.normalise("/CLEAR") == "/clear"


def test_sort_tokens_is_word_order_insensitive():
    assert rail.sort_tokens("images close all") == rail.sort_tokens("close all images")


def test_jaro_winkler_bounds():
    assert rail.jaro_winkler("abc", "abc") == 1.0
    assert rail.jaro_winkler("", "abc") == 0.0
    assert rail.jaro_winkler(None, "abc") == 0.0
    assert 0.9 < rail.jaro_winkler("gausian blur", "gaussian blur") < 1.0


def test_suggestion_chips_rank_the_best_match_first():
    chips = rail.suggestion_chips("close all imgs", engine())
    assert chips and chips[0].intent_id == "image.close_all"
    assert chips[0].score >= rail.SUGGESTION_FLOOR


def test_suggestion_chips_are_capped_at_three():
    assert len(rail.suggestion_chips("reset roi manager", engine(), k=10)) <= 3


def test_suggestion_chips_below_the_floor_return_nothing():
    assert rail.suggestion_chips("zzzzzzzzzz qqqqqqqqqq", engine()) == []


def test_suggestion_chips_cleared_when_disabled_or_blank():
    assert rail.suggestion_chips("   ", engine()) == []
    assert rail.suggestion_chips("close all images", engine(), enabled=False) == []
    assert rail.suggestion_chips("close all images", None) == []


def test_one_chip_per_intent():
    many = rail.SuggestionEngine(
        {"image.close_all": ["close all images", "close all the images", "close all"]}
    )
    chips = many.top_k("close all images", 3)
    assert len(chips) == 1


def test_accept_first_returns_the_top_phrase():
    chips = rail.suggestion_chips("close all images", engine())
    assert rail.accept_first(chips) == chips[0].phrase
    assert rail.accept_first([]) is None


# --------------------------------------------------- S1.16 clarification chips


def test_clarification_chips_capped_at_two():
    chips = rail.clarification_chips(["a", "b", "c"])
    assert [chip.phrase for chip in chips] == ["a", "b"]


def test_chip_href_roundtrip():
    assert rail.chip_href(1) == "ijai-chip:1"
    assert rail.parse_chip_href("ijai-chip:1") == 1
    assert rail.parse_chip_href("ijai-chip:x") is None
    assert rail.parse_chip_href("http://example.com") is None
    assert rail.parse_chip_href(None) is None


def test_resolve_chip_ignores_out_of_range_links():
    chips = rail.clarification_chips(["first", "second"])
    assert rail.resolve_chip("ijai-chip:0", chips) == "first"
    assert rail.resolve_chip("ijai-chip:5", chips) is None
    assert rail.resolve_chip("ijai-chip:-1", chips) is None


# ---------------------------------------------- S2.25 governance pane content


def test_audit_counter_line_counts_visual_overrides():
    rows = [
        {"command": "get_state", "posture": "PSEUDONYMISED"},
        {"command": "request_visual", "posture": "STANDARD"},
        {"command": "visual.full_plane", "posture": "PSEUDONYMISED"},
    ]
    assert rail.audit_counter_line(rows) == "3 (2 pseudonymised, 2 visual overrides)"


def test_audit_counter_line_uses_the_last_500_rows_only():
    rows = [{"command": "get_state", "posture": "STANDARD"} for _ in range(600)]
    assert rail.audit_counter_line(rows).startswith("500 (")


def test_governance_model_paths_and_warning(tmp_path):
    model = rail.governance_model(image_folder=tmp_path, audit_rows=[])
    assert model["audit_label"] == "AI_Exports/imagejai_audit.csv"
    assert model["audit_path"].endswith("imagejai_audit.csv")
    assert model["can_generate_statement"] and model["generate_warning"] is None

    without = rail.governance_model(image_folder=None)
    assert not without["can_generate_statement"]
    assert "Open an image from the project folder" in without["generate_warning"]
    assert without["posture"] == "Standard"


# ------------------------------------------------- S2.26 receipts pane content


def test_receipts_rows_are_capped_and_shaped():
    rows = [
        {"time": f"12:00:{i:02d}", "command": "get_state", "bytes_out": i}
        for i in range(60)
    ]
    receipts = rail.receipts_rows(rows)
    assert len(receipts) == rail.RECEIPTS_LIMIT
    assert receipts[0]["show"] == "[show]"
    assert set(rail.RECEIPT_COLUMNS) <= set(receipts[0])


def test_receipt_detail_falls_back_to_metadata():
    detail = rail.receipt_detail(
        {
            "command": "get_results_table",
            "posture": "PSEUDONYMISED",
            "bytes_out": 42,
            "redaction_applied": True,
            "fields_redacted": "results_table, title",
        }
    )
    assert detail["fields_redacted"] == ["results_table", "title"]
    assert detail["redacted_json"]["receipt_note"] == rail.RECEIPT_NOTE


def test_receipt_detail_keeps_a_cached_payload():
    detail = rail.receipt_detail({"redacted_payload": {"ok": True}, "fields_redacted": []})
    assert detail["redacted_json"] == {"ok": True}
    assert detail["fields_redacted"] is None


# ----------------------------------------------------------------- dispatch


def test_dispatch_rejects_an_unknown_item():
    with pytest.raises(KeyError):
        rail.dispatch("does.not.exist", FakeFiji())


def test_dispatch_accepts_an_action_name_directly():
    conn = FakeFiji()
    assert rail.dispatch("close_all_images", conn).ok
