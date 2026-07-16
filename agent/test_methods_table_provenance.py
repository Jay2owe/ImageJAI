import json
import sys

import methods_table


def test_dataset_binding_rejects_metadata_from_async_current_image(monkeypatch, capsys):
    captured = {}
    monkeypatch.setattr(methods_table, "latest_session_log", lambda: ("session.json", {}))
    monkeypatch.setattr(methods_table, "fetch_metadata", lambda: {"info": "wrong image"})
    monkeypatch.setattr(methods_table, "fetch_graph", lambda: {})
    monkeypatch.setattr(methods_table, "fetch_state", lambda: {
        "activeImage": {"filePath": "C:/data/B.tif", "width": 999}
    })

    def extract(session, metadata, graph, info):
        captured["metadata"] = metadata
        captured["info"] = info
        return []

    monkeypatch.setattr(methods_table, "extract_fields", extract)
    monkeypatch.setattr(methods_table, "render", lambda *_args: ("draft\n", 0, 0))
    binding = {"identity": "img-a", "hash": "hash-a", "title": "A",
               "filePath": "C:/data/A.tif", "width": 10, "height": 20}
    monkeypatch.setattr(sys, "argv", ["methods_table.py", "--dry-run",
                                      "--dataset-json", json.dumps(binding)])

    assert methods_table.main() == 0
    assert captured["metadata"] is None
    assert captured["info"]["identity"] == "img-a"
    assert captured["info"]["filePath"] == "C:/data/A.tif"
    assert captured["info"]["width"] == 10
    assert "draft" in capsys.readouterr().out


def test_methods_document_is_published_atomically(tmp_path):
    output = tmp_path / "AI_Exports" / "methods.md"
    methods_table.atomic_write_text(str(output), "complete\n")
    assert output.read_text(encoding="utf-8") == "complete\n"
    assert not list(output.parent.glob("*.tmp"))
