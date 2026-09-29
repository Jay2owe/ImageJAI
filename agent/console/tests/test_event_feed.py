"""User-facing summaries and retrospective filters for Fiji event frames."""
from __future__ import annotations

from agent.console.event_feed import EventFeed, describe_event


def test_fiji_frames_become_readable_colored_lines():
    opened = describe_event({
        "event": "image.opened",
        "data": {"title": "cells [red]", "dims": {"width": 512, "height": 256}},
        "ts": 1, "seq": 1,
    }, now_ms=60_000)
    assert opened is not None
    assert opened.category == "image"
    assert opened.message == "Opened image: cells [red] (512 × 256)"
    assert "[green]Opened image: cells \\[red]" in opened.markup()
    assert "{\"" not in opened.markup()

    failed = describe_event({"event": "macro.completed",
                             "data": {"success": False, "error": "No image"}}, now_ms=60_000)
    assert failed is not None
    assert failed.color == "red"
    assert failed.message == "Macro failed: No image"
    assert describe_event({"event": "heartbeat"}) is None
    assert describe_event({"event": "subscribed", "data": {"topics": ["*"]}}) is None


def test_event_feed_filters_history_by_type_time_and_text():
    feed = EventFeed(limit=4)
    feed.add({"event": "image.opened", "data": {"title": "old.tif"}}, now_ms=1_000)
    feed.add({"event": "results.changed", "data": {"rows": 3, "cols": 2}}, now_ms=400_000)
    feed.add({"event": "dialog.appeared", "data": {"title": "Threshold"}}, now_ms=401_000)
    feed.add({"event": "memory.pressure", "data": {"used_pct": 91}}, now_ms=402_000)
    assert [row.message for row in feed.visible("all", "5m", now_ms=402_000)] == [
        "Results table: 3 rows, 2 columns", "Dialog opened: Threshold",
        "Fiji memory is 91% full"]
    assert [row.message for row in feed.visible("dialog", "all", "threshold")] == [
        "Dialog opened: Threshold"]
    assert [row.category for row in feed.visible("warning")] == ["warning"]
    assert feed.add({"event": "memory.pressure", "data": {"used_pct": 91}},
                    now_ms=402_500) is None


def test_posture_event_states_the_resulting_fiji_policy():
    line = describe_event({"event": "data_governance.posture.requested",
                           "data": {"from": "PSEUDONYMISED", "to": "STANDARD"}})
    assert line is not None
    assert line.message == "Fiji privacy posture: Standard"
    assert line.category == "privacy"
