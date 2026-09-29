"""Tests for the console's privacy posture logic (feature doc 6.1-6.11, 6.23).

No Textual, no network, no real home directory: every path is a tmp_path and
both the wall clock and the blink clock are injected.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from agent.console import posture as P


# ---------------------------------------------------------------------------
# the three postures
# ---------------------------------------------------------------------------

def test_posture_labels_and_descriptions_match_the_plugin():
    assert P.Posture.STANDARD.label == "Standard"
    assert P.Posture.PSEUDONYMISED.label == "Pseudonymised"
    assert P.Posture.ON_PREMISES.label == "On-premises"
    assert "UK GDPR Art. 4(5)" in P.Posture.PSEUDONYMISED.description
    assert P.Posture.ON_PREMISES.description == (
        "Local agents only; pseudonymisation also applied.")


def test_strictness_order_and_default():
    assert P.Posture.default() is P.Posture.STANDARD
    assert P.Posture.ON_PREMISES.is_stricter_than(P.Posture.PSEUDONYMISED)
    assert P.Posture.PSEUDONYMISED.is_stricter_than(P.Posture.STANDARD)
    assert not P.Posture.STANDARD.is_stricter_than(P.Posture.ON_PREMISES)
    assert not P.Posture.STANDARD.is_stricter_than(None)


@pytest.mark.parametrize("text, expected", [
    ("On-premises", P.Posture.ON_PREMISES),
    ("ON_PREMISES", P.Posture.ON_PREMISES),
    ("on-premises", P.Posture.ON_PREMISES),
    ("standard", P.Posture.STANDARD),
    ("PSEUDONYMISED", P.Posture.PSEUDONYMISED),
])
def test_parse_accepts_labels_and_enum_names(text, expected):
    assert P.Posture.parse(text) is expected


def test_parse_rejects_nonsense():
    with pytest.raises(ValueError):
        P.Posture.parse("relaxed")


# ---------------------------------------------------------------------------
# sidecar store
# ---------------------------------------------------------------------------

def test_sidecar_round_trip_uses_the_plugin_file_name_and_keys(tmp_path):
    store = P.FolderPostureStore()
    record = store.write(tmp_path, P.Posture.ON_PREMISES, notes="chosen in console")

    sidecar = tmp_path / ".imagejai-posture.json"
    assert sidecar.is_file()
    raw = json.loads(sidecar.read_text(encoding="utf-8"))
    assert raw["posture"] == "ON_PREMISES"     # Gson writes the enum name
    assert raw["set_by"] == "user"
    assert raw["notes"] == "chosen in console"
    assert raw["set_at"]

    read_back = store.read(tmp_path)
    assert read_back is not None
    assert read_back.posture is P.Posture.ON_PREMISES
    assert read_back.notes == record.notes


def test_read_returns_none_without_a_sidecar(tmp_path):
    assert P.FolderPostureStore().read(tmp_path) is None


def test_existing_sidecar_without_posture_keeps_protective_fallback(tmp_path):
    (tmp_path / ".imagejai-posture.json").write_text("{}", encoding="utf-8")
    assert P.FolderPostureStore().read(tmp_path).posture is P.Posture.PSEUDONYMISED


def test_write_leaves_no_temp_file_behind(tmp_path):
    P.FolderPostureStore().write(tmp_path, P.Posture.STANDARD)
    assert not (tmp_path / ".imagejai-posture.json.tmp").exists()


def test_corrupt_sidecar_raises_a_read_error(tmp_path):
    (tmp_path / ".imagejai-posture.json").write_text("{not json", encoding="utf-8")
    with pytest.raises(P.PostureReadError):
        P.FolderPostureStore().read(tmp_path)


def test_empty_sidecar_raises_a_read_error(tmp_path):
    (tmp_path / ".imagejai-posture.json").write_text("   ", encoding="utf-8")
    with pytest.raises(P.PostureReadError):
        P.FolderPostureStore().read(tmp_path)


def test_write_to_a_read_only_folder_reports_the_plugin_warning(tmp_path, monkeypatch):
    # Windows ignores chmod on directories, so the denial is injected at the
    # rename that FolderPostureStore relies on.
    def deny(src, dst):
        raise PermissionError(13, "Access is denied")

    monkeypatch.setattr(P.os, "replace", deny)
    with pytest.raises(P.PostureWriteError) as excinfo:
        P.FolderPostureStore().write(tmp_path, P.Posture.ON_PREMISES)
    assert "Privacy Posture is in-memory only for this session" in str(excinfo.value)


def test_write_into_a_path_that_is_a_file_fails_clearly(tmp_path):
    blocker = tmp_path / "not-a-folder"
    blocker.write_text("x", encoding="utf-8")
    with pytest.raises(P.PostureWriteError):
        P.FolderPostureStore().write(blocker, P.Posture.STANDARD)


def test_clear_removes_the_sidecar(tmp_path):
    store = P.FolderPostureStore()
    store.write(tmp_path, P.Posture.ON_PREMISES)
    assert store.clear(tmp_path) is True
    assert store.read(tmp_path) is None
    assert store.clear(tmp_path) is False


# ---------------------------------------------------------------------------
# controller transitions
# ---------------------------------------------------------------------------

class StepClock:
    """Monotonic seconds the test moves by hand."""

    def __init__(self, start: float = 1000.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def _controller(tmp_path, posture=None):
    return P.PostureController(P.FolderPostureStore(), posture=posture,
                               clock=StepClock())


def test_opening_a_stricter_folder_downshifts_automatically(tmp_path):
    P.FolderPostureStore().write(tmp_path, P.Posture.ON_PREMISES)
    controller = _controller(tmp_path, P.Posture.STANDARD)

    events = controller.on_folder_opened(tmp_path)

    assert controller.current is P.Posture.ON_PREMISES
    assert [e.event_type for e in events] == [P.EVENT_DOWNSHIFTED]
    assert events[0].message == "Posture downshifted: Standard to On-premises"


def test_opening_a_weaker_folder_never_upshifts(tmp_path):
    P.FolderPostureStore().write(tmp_path, P.Posture.STANDARD)
    controller = _controller(tmp_path, P.Posture.ON_PREMISES)

    events = controller.on_folder_opened(tmp_path)

    assert controller.current is P.Posture.ON_PREMISES
    assert [e.event_type for e in events] == [P.EVENT_UPSHIFT_REFUSED]
    assert events[0].reason == "Never auto-upshift between folders"


def test_equal_folder_posture_only_reports_loaded(tmp_path):
    P.FolderPostureStore().write(tmp_path, P.Posture.PSEUDONYMISED)
    controller = _controller(tmp_path, P.Posture.PSEUDONYMISED)
    events = controller.on_folder_opened(tmp_path)
    assert [e.event_type for e in events] == [P.EVENT_LOADED]
    assert controller.current is P.Posture.PSEUDONYMISED


def test_folder_without_a_decision_asks_the_user(tmp_path):
    controller = _controller(tmp_path, P.Posture.STANDARD)
    events = controller.on_folder_opened(tmp_path)
    assert controller.prompt_needed == Path(os.path.abspath(tmp_path))
    assert controller.current is P.Posture.STANDARD
    assert events[0].event_type == P.EVENT_SELECTION_NEEDED


def test_unreadable_sidecar_warns_and_uses_protective_fallback(tmp_path):
    (tmp_path / ".imagejai-posture.json").write_text("{", encoding="utf-8")
    controller = _controller(tmp_path, P.Posture.STANDARD)

    events = controller.on_folder_opened(tmp_path)

    types = [e.event_type for e in events]
    assert P.EVENT_WARNING in types
    warning = [e for e in events if e.event_type == P.EVENT_WARNING][0]
    assert warning.reason.startswith("Could not read Privacy Posture for")
    assert controller.current is P.Posture.PSEUDONYMISED


def test_repeat_open_inside_the_same_second_is_debounced(tmp_path):
    P.FolderPostureStore().write(tmp_path, P.Posture.ON_PREMISES)
    clock = StepClock()
    controller = P.PostureController(P.FolderPostureStore(),
                                     posture=P.Posture.STANDARD, clock=clock)
    assert controller.on_folder_opened(tmp_path)
    assert controller.on_folder_opened(tmp_path) == []
    clock.advance(1.0)
    assert controller.on_folder_opened(tmp_path) != []


def test_request_posture_writes_the_sidecar(tmp_path):
    controller = _controller(tmp_path, P.Posture.PSEUDONYMISED)
    events = controller.request_posture(P.Posture.ON_PREMISES, tmp_path, "REC amendment")

    assert controller.current is P.Posture.ON_PREMISES
    assert P.EVENT_FOLDER_SAVED in [e.event_type for e in events]
    assert P.FolderPostureStore().read(tmp_path).posture is P.Posture.ON_PREMISES


def test_request_posture_warns_when_the_sidecar_cannot_be_written(tmp_path, monkeypatch):
    controller = _controller(tmp_path, P.Posture.PSEUDONYMISED)
    monkeypatch.setattr(P.os, "replace",
                        lambda src, dst: (_ for _ in ()).throw(PermissionError("denied")))

    events = controller.request_posture(P.Posture.STANDARD, tmp_path, "demo")

    types = [e.event_type for e in events]
    assert P.EVENT_WARNING in types
    # In-memory only, but the session still followed the user's request.
    assert controller.current is P.Posture.STANDARD


def test_weakening_the_posture_logs_an_override_without_the_raw_path(tmp_path):
    controller = _controller(tmp_path, P.Posture.ON_PREMISES)
    events = controller.override_downshift(P.Posture.STANDARD, tmp_path,
                                           "Phantom data, no participants")

    assert P.EVENT_OVERRIDE_LOGGED in [e.event_type for e in events]
    assert len(controller.overrides) == 1
    record = controller.overrides[0]
    assert record.command == "posture.override"
    assert record.notes.startswith("from=On-premises to=Standard folder_token=folder-")
    assert "reason='Phantom data, no participants'" in record.notes
    assert str(tmp_path) not in record.notes


def test_override_without_a_reason_is_refused(tmp_path):
    controller = _controller(tmp_path, P.Posture.ON_PREMISES)
    with pytest.raises(ValueError) as excinfo:
        controller.override_downshift(P.Posture.STANDARD, tmp_path, "   ")
    assert str(excinfo.value) == "A reason is required for a logged override."
    assert controller.current is P.Posture.ON_PREMISES


def test_strengthening_the_posture_is_not_an_override(tmp_path):
    controller = _controller(tmp_path, P.Posture.STANDARD)
    controller.request_posture(P.Posture.ON_PREMISES, tmp_path, "tightened")
    assert controller.overrides == []


def test_revoke_deletes_the_sidecar_and_resets_to_the_default(tmp_path):
    controller = _controller(tmp_path, P.Posture.PSEUDONYMISED)
    controller.request_posture(P.Posture.ON_PREMISES, tmp_path, "strict")
    events = controller.revoke_folder_posture(tmp_path, "project closed")

    assert [e.event_type for e in events] == [P.EVENT_FOLDER_REVOKED]
    assert controller.current is P.Posture.default()
    assert not (tmp_path / ".imagejai-posture.json").exists()


def test_listeners_see_events_and_a_broken_listener_is_survivable(tmp_path):
    controller = _controller(tmp_path, P.Posture.STANDARD)
    seen = []
    controller.add_listener(lambda event: seen.append(event.event_type))
    controller.add_listener(lambda event: (_ for _ in ()).throw(RuntimeError("boom")))

    controller.request_posture(P.Posture.ON_PREMISES, tmp_path, "why")

    assert P.EVENT_REQUESTED in seen


# ---------------------------------------------------------------------------
# launch gating
# ---------------------------------------------------------------------------

def test_on_premises_blocks_a_cloud_provider():
    decision = P.evaluate_launch("anthropic", "claude-sonnet-4-5", P.Posture.ON_PREMISES)
    assert not decision.allowed
    assert decision.reason == P.ON_PREMISES_REFUSAL
    with pytest.raises(P.PostureViolation):
        decision.enforce()


def test_on_premises_allows_a_local_provider():
    decision = P.evaluate_launch("ollama", "gemma3:27b", P.Posture.ON_PREMISES)
    assert decision.allowed and decision.local
    assert decision.enforce() is decision


def test_on_premises_refuses_cloud_ollama_tags():
    for model in ("gemma4:31b-cloud", "qwen3:cloud"):
        decision = P.evaluate_launch("ollama", model, P.Posture.ON_PREMISES)
        assert not decision.allowed
        assert decision.reason == P.CLOUD_OLLAMA_REFUSAL
        assert "gemma3:27b" in decision.reason


def test_on_premises_refuses_the_ollama_cloud_provider_id():
    decision = P.evaluate_launch("ollama-cloud", "gpt-oss:120b", P.Posture.ON_PREMISES)
    assert not decision.allowed
    assert decision.reason == P.CLOUD_OLLAMA_REFUSAL


def test_locality_comes_from_the_endpoint_not_the_provider_name():
    remote = P.evaluate_launch("ollama", "gemma3:27b", P.Posture.ON_PREMISES,
                               endpoint="http://gpu-server.lab:11434")
    assert not remote.allowed
    local = P.evaluate_launch("ollama", "gemma3:27b", P.Posture.ON_PREMISES,
                              endpoint="http://127.0.0.1:11434")
    assert local.allowed


@pytest.mark.parametrize("posture", [P.Posture.STANDARD, P.Posture.PSEUDONYMISED])
def test_cloud_providers_are_allowed_below_on_premises(posture):
    assert P.evaluate_launch("anthropic", "claude-sonnet-4-5", posture).allowed
    assert P.evaluate_launch("ollama", "gemma4:31b-cloud", posture).allowed


def test_missing_posture_defaults_to_standard():
    decision = P.evaluate_launch("openai", "gpt-5", None)
    assert decision.allowed
    assert decision.posture is P.Posture.STANDARD


def test_invalid_identifiers_are_denied():
    assert P.evaluate_launch("bad provider!", "", P.Posture.STANDARD).reason == (
        "Invalid provider identifier.")
    assert P.evaluate_launch("openai", "model with spaces", P.Posture.STANDARD).reason == (
        "Invalid model identifier.")


def test_filter_providers_for_posture_keeps_only_local_on_premises():
    providers = ["anthropic", "openai", "ollama", "lmstudio", "ollama-cloud"]
    kept = P.filter_providers_for_posture(providers, P.Posture.ON_PREMISES)
    assert kept == ["ollama", "lmstudio"]
    assert P.filter_providers_for_posture(providers, P.Posture.STANDARD) == providers


def test_loopback_detection():
    assert P.is_loopback_endpoint("http://localhost:11434")
    assert P.is_loopback_endpoint("127.0.0.1:11434")
    assert not P.is_loopback_endpoint("http://10.0.0.5:11434")
    assert not P.is_loopback_endpoint("")


# ---------------------------------------------------------------------------
# badge + lamp
# ---------------------------------------------------------------------------

def test_badge_state_per_posture():
    standard = P.badge_for(P.Posture.STANDARD)
    strict = P.badge_for(P.Posture.ON_PREMISES)
    assert standard.label == "Standard"
    assert standard.color_role == "posture-standard"
    assert standard.background == "#6F7782"
    assert strict.background == "#2F9E44"
    assert strict.glyph and strict.glyph != standard.glyph
    assert "Privacy Posture: On-premises" in strict.tooltip
    assert P.badge_for(None).label == "Standard"


def test_footer_text_says_where_the_data_goes():
    assert P.footer_text(P.Posture.ON_PREMISES).endswith(
        "your data stays on this machine")
    assert "identifiers tokenised before send" in P.footer_text(P.Posture.PSEUDONYMISED)
    assert P.footer_text(P.Posture.STANDARD) == "Standard — cloud agents allowed"


def test_egress_lamp_blinks_for_300_ms_then_goes_dark():
    clock = StepClock(0.0)
    lamp = P.EgressLamp(clock=clock)
    assert not lamp.is_lit()

    lamp.record(2048)
    assert lamp.is_lit()

    clock.advance(0.299)
    assert lamp.is_lit()
    clock.advance(0.002)          # 301 ms after the call
    assert not lamp.is_lit()


def test_egress_lamp_restarts_the_window_on_every_call():
    clock = StepClock(0.0)
    lamp = P.EgressLamp(clock=clock)
    lamp.record(10)
    clock.advance(0.2)
    lamp.record(10)               # restart, as EgressIndicator.decay.restart() does
    clock.advance(0.2)
    assert lamp.is_lit()

    state = lamp.state()
    assert state.calls == 2 and state.bytes_out == 20 and state.lit


def test_egress_lamp_counts_are_monotonic():
    clock = StepClock(0.0)
    lamp = P.EgressLamp(clock=clock)
    for _ in range(5):
        lamp.record(100)
        clock.advance(10.0)
    assert lamp.calls == 5 and lamp.bytes_out == 500
    assert not lamp.state().lit
