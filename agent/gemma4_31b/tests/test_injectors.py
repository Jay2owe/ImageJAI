from __future__ import annotations

import sys
from pathlib import Path


PACKAGE_ROOT = Path(__file__).resolve().parents[2]
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from gemma4_31b.loop import (  # noqa: E402
    _3d_render_prep_note,
    _groovy_jython_note,
    _hallucinated_filter_cmds_note,
    _hallucination_reflector_injector,
    _multiseries_note,
    _no_image_reflex_injector,
    _normalise_tool_error,
    _post_tool_system_notes,
    _pre_dispatch_abort_note,
    _recipe_before_loop_note,
    _sample_image_note,
    _selectimage_anchor_note,
    _stale_error_loop_injector,
)


# --- User-text injectors: positive + negative -------------------------------


def test_multiseries_note_fires_on_lif():
    note = _multiseries_note("open /data/experiment.lif please")
    assert note is not None
    assert "list_lif_series" in note


def test_multiseries_note_fires_on_czi_case_insensitive():
    assert _multiseries_note("here is /data/scan.CZI") is not None


def test_multiseries_note_silent_on_tif():
    assert _multiseries_note("open /data/foo.tif") is None


def test_multiseries_note_silent_on_plain_text():
    assert _multiseries_note("threshold this for me") is None


def test_groovy_jython_note_fires_on_groovy():
    note = _groovy_jython_note("write a groovy script that filters")
    assert note is not None
    assert "IJ.setAutoThreshold" in note
    assert "imp.changes = false" in note


def test_groovy_jython_note_fires_on_jython():
    assert _groovy_jython_note("jython pls") is not None


def test_groovy_jython_note_fires_on_run_script():
    assert _groovy_jython_note("use run_script to close the image") is not None


def test_groovy_jython_note_silent_on_macro():
    assert _groovy_jython_note("write a macro to threshold") is None


def test_hallucinated_filter_cmds_fires_on_count_and_filter():
    note = _hallucinated_filter_cmds_note("run 10 different filters and compare counts")
    assert note is not None
    assert "Laplacian" in note
    assert "DoG" in note


def test_hallucinated_filter_cmds_fires_on_compare_filters():
    assert _hallucinated_filter_cmds_note("compare several filter sets") is not None


def test_hallucinated_filter_cmds_silent_on_single_filter():
    assert _hallucinated_filter_cmds_note("apply a gaussian filter") is None


def test_hallucinated_filter_cmds_silent_on_no_filter_word():
    assert _hallucinated_filter_cmds_note("10 different thresholds please") is None


def test_sample_image_note_fires_on_blobs_open():
    note = _sample_image_note("open Blobs for me")
    assert note is not None
    assert '"Blobs (25K)"' in note


def test_sample_image_note_fires_on_fluorescent_cells_load():
    assert _sample_image_note("load the fluorescent cells sample") is not None


def test_sample_image_note_silent_without_verb():
    assert _sample_image_note("blobs look like blobs") is None


def test_sample_image_note_silent_on_unrelated_image():
    assert _sample_image_note("open /data/my_stack.tif") is None


def test_recipe_before_loop_note_fires_on_first_turn():
    note = _recipe_before_loop_note("run 10 different filters", is_first_turn=True)
    assert note is not None
    assert "recipe_search" in note


def test_recipe_before_loop_note_silent_after_first_turn():
    assert _recipe_before_loop_note("run 10 different filters", is_first_turn=False) is None


def test_recipe_before_loop_note_silent_without_count():
    assert _recipe_before_loop_note("just threshold it", is_first_turn=True) is None


def test_3d_render_prep_note_fires_on_3d_render():
    note = _3d_render_prep_note("give me a 3D render of the cell")
    assert note is not None
    assert "8-bit" in note
    assert "Multiply mask" in note


def test_3d_render_prep_note_fires_on_3dscript():
    assert _3d_render_prep_note("use 3Dscript to make an animation") is not None


def test_3d_render_prep_note_silent_on_2d_render():
    assert _3d_render_prep_note("render the histogram") is None


def test_selectimage_anchor_note_fires_on_duplicate():
    note = _selectimage_anchor_note("duplicate the image")
    assert note is not None
    assert "selectImage" in note


def test_selectimage_anchor_note_fires_on_measure():
    assert _selectimage_anchor_note("measure the bright regions") is not None


def test_selectimage_anchor_note_silent_on_general_question():
    assert _selectimage_anchor_note("what is this image?") is None


# --- Post-tool injectors ----------------------------------------------------


def test_normalise_tool_error_strips_line_numbers():
    a = _normalise_tool_error("ERROR: Macro Error in line 12: foo")
    b = _normalise_tool_error("ERROR: Macro Error in line 47: foo")
    assert a == b


def test_normalise_tool_error_none_on_success():
    assert _normalise_tool_error("success output") is None


def test_stale_error_loop_fires_on_repeat():
    state: dict = {}
    first = _stale_error_loop_injector(
        "run_macro", {}, "ERROR: Macro Error in line 1: undefined variable", state
    )
    second = _stale_error_loop_injector(
        "run_macro", {}, "ERROR: Macro Error in line 1: undefined variable", state
    )
    assert first is None
    assert second is not None
    assert "STOP submitting" in second


def test_stale_error_loop_silent_on_different_errors():
    state: dict = {}
    _stale_error_loop_injector(
        "run_macro", {}, "ERROR: Macro Error line 1: foo", state
    )
    second = _stale_error_loop_injector(
        "run_macro", {}, "ERROR: Macro Error line 1: bar", state
    )
    assert second is None


def test_stale_error_loop_resets_on_success():
    state: dict = {}
    _stale_error_loop_injector("run_macro", {}, "ERROR: boom", state)
    _stale_error_loop_injector("run_macro", {}, "success ok", state)
    # After a success, the previous error should be cleared; the next
    # identical error must not trip the "two in a row" rule.
    second = _stale_error_loop_injector("run_macro", {}, "ERROR: boom", state)
    assert second is None


def test_stale_error_loop_ignores_unrelated_tools():
    state: dict = {}
    note = _stale_error_loop_injector(
        "get_open_windows", {}, "ERROR: transport failed", state
    )
    assert note is None


def test_hallucination_reflector_captures_command_name():
    result = 'ERROR: Unrecognized command: "Laplacian" in macro'
    note = _hallucination_reflector_injector("run_macro", {}, result, {})
    assert note is not None
    assert "Laplacian" in note
    assert "probe_plugin" in note


def test_hallucination_reflector_silent_on_other_error():
    note = _hallucination_reflector_injector(
        "run_macro", {}, "ERROR: Macro Error line 5", {}
    )
    assert note is None


def test_no_image_reflex_fires_on_no_image():
    note = _no_image_reflex_injector(
        "run_macro", {}, "ERROR: No image is currently open", {}
    )
    assert note is not None
    assert "get_open_windows" in note


def test_no_image_reflex_fires_on_no_window_with_title():
    note = _no_image_reflex_injector(
        "run_macro", {}, 'ERROR: No window with the title "Raw"', {}
    )
    assert note is not None


def test_no_image_reflex_silent_on_success():
    assert _no_image_reflex_injector("run_macro", {}, "macro ok", {}) is None


def test_post_tool_dispatcher_runs_all_injectors():
    # An Unrecognized command error should trip BOTH the hallucination
    # reflector (first time seeing it) AND leave turn_state primed for the
    # stale-error detector on a repeat.
    state: dict = {}
    result = 'ERROR: Unrecognized command: "Laplacian"'
    notes = _post_tool_system_notes("run_macro", {}, result, state)
    assert any("Laplacian" in n for n in notes)
    # Stale-error shouldn't fire yet — only one occurrence.
    assert not any("STOP submitting" in n for n in notes)
    notes2 = _post_tool_system_notes("run_macro", {}, result, state)
    assert any("STOP submitting" in n for n in notes2)


def test_post_tool_dispatcher_survives_injector_exception(monkeypatch):
    # A misbehaving injector must not poison the rest of the dispatch chain.
    import gemma4_31b.loop as loop_mod

    def boom(*args, **kwargs):
        raise RuntimeError("bad injector")

    monkeypatch.setattr(
        loop_mod,
        "_POST_TOOL_INJECTORS",
        (boom, loop_mod._no_image_reflex_injector),
    )
    notes = loop_mod._post_tool_system_notes(
        "run_macro", {}, "ERROR: No image is open", {}
    )
    assert any("get_open_windows" in n for n in notes)


# --- Pre-dispatch hook ------------------------------------------------------


def test_pre_dispatch_hook_returns_none_by_default():
    assert _pre_dispatch_abort_note("run_macro", {"code": 'run("AND")'}) is None
