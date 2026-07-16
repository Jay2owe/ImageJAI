from __future__ import annotations

import queue

from agent.gemma4_31b import events


def test_image_update_flood_coalesces_while_consumer_is_stalled(monkeypatch) -> None:
    bounded = events._BoundedEventQueue(maxsize=8)
    monkeypatch.setattr(events, "EVENT_QUEUE", bounded)

    for sequence in range(10_000):
        events._handle_frame(
            {
                "event": "image.updated",
                "data": {"title": "cells.tif", "sequence": sequence},
            }
        )

    assert bounded.qsize() == 1
    assert bounded.dropped_count == 9_999
    assert bounded.coalesced_count == 9_999
    assert bounded.get_nowait()["data"]["sequence"] == 9_999


def test_noncoalescible_flood_drops_oldest_and_counts_every_drop(monkeypatch) -> None:
    bounded = events._BoundedEventQueue(maxsize=4)
    monkeypatch.setattr(events, "EVENT_QUEUE", bounded)

    for sequence in range(20):
        events._handle_frame(
            {"event": "macro.completed", "data": {"sequence": sequence}}
        )

    assert bounded.qsize() == 4
    assert bounded.dropped_count == 16
    assert bounded.coalesced_count == 0
    assert [bounded.get_nowait()["data"]["sequence"] for _ in range(4)] == [16, 17, 18, 19]
    try:
        bounded.get_nowait()
    except queue.Empty:
        pass
    else:  # pragma: no cover - assertion clarity
        raise AssertionError("bounded event queue retained more than maxsize frames")
