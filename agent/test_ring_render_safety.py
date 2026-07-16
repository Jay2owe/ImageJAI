from __future__ import annotations

import os
import inspect

import pytest

from agent import ring_render


def test_render_outputs_are_confined_to_ai_exports(tmp_path):
    exports = tmp_path / "AI_Exports"
    nested = exports / "rings"

    assert ring_render.resolve_output_dir(str(nested), str(exports)) == os.path.realpath(
        nested
    )
    with pytest.raises(ValueError, match="beneath"):
        ring_render.resolve_output_dir(str(tmp_path / "outside"), str(exports))


def test_batch_animation_uses_explicit_script_capability(monkeypatch, tmp_path):
    calls = []
    monkeypatch.setattr(
        ring_render._ij_client,
        "run_groovy",
        lambda code, timeout=180: calls.append((code, timeout))
        or {"ok": True, "result": {"success": True}},
    )

    animation = tmp_path / "animation.txt"
    reply = ring_render.run_batch_animation(str(animation))

    assert reply["ok"] is True
    assert calls[0][1] == 300
    assert "Batch Animation" in calls[0][0]
    assert str(animation).replace("\\", "/") in calls[0][0]
    render_source = inspect.getsource(ring_render.render)
    assert "run_batch_animation(anim_path)" in render_source
    assert 'run("Batch Animation"' not in render_source


def test_avi_export_uses_script_channel_without_macro_save(monkeypatch, tmp_path):
    calls = []
    monkeypatch.setattr(
        ring_render._ij_client,
        "run_groovy",
        lambda code, timeout=180: calls.append((code, timeout))
        or {"ok": True, "result": {"success": True}},
    )

    output = tmp_path / "AI_Exports" / "ring.avi"
    ring_render.save_avi_output(str(output), 24, str(output.parent))

    assert "AVI..." in calls[0][0]
    assert "save=[" in calls[0][0]
    assert 'run("AVI' not in inspect.getsource(ring_render.render)

    with pytest.raises(ValueError, match="beneath"):
        ring_render.save_avi_output(
            str(tmp_path / "outside.avi"), 24, str(output.parent)
        )
