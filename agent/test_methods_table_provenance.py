import json
import os
import sys

import methods_table


def test_dataset_binding_rejects_metadata_from_async_current_image(monkeypatch, capsys):
    captured = {}
    monkeypatch.setattr(
        methods_table, "latest_session_log",
        lambda _client_session_id=None: ("session.json", {}),
    )
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


def test_methods_fields_use_real_state_keys_and_provenance(monkeypatch):
    monkeypatch.setattr(methods_table.platform, "platform", lambda: "TestOS")
    info = {
        "width": 100,
        "height": 200,
        "slices": 3,
        "channels": 2,
        "frames": 4,
        "type": "16-bit",
        "imagejVersion": "2.16.0/1.54p",
        "tcpSessionId": "tcp-session",
        "clientSessionId": "launch-session",
        "sessionLogId": "log-id",
        "sessionLogPath": "C:/work/session_log.json",
        "title": "initiating.tif",
        "filePath": "C:/data/initiating.tif",
        "identity": "image-identity",
        "hash": "image-hash",
    }
    fields = dict(methods_table.extract_fields({}, {}, {}, info))
    assert fields["Image dimensions (X x Y x Z x C x T)"] == "100x200x3x2x4"
    assert fields["Bit depth"] == "16-bit"
    assert fields["ImageJAI version"] == "0.3.0"
    assert fields["Fiji / ImageJ version"] == "2.16.0/1.54p"
    assert fields["TCP session ID"] == "tcp-session"
    assert fields["Agent launch session ID"] == "launch-session"
    assert fields["Session log ID"] == "log-id"
    assert fields["Session log path"] == "C:/work/session_log.json"
    assert fields["Initiating dataset title"] == "initiating.tif"
    assert fields["Initiating dataset path"] == "C:/data/initiating.tif"
    assert fields["Initiating dataset identity"] == "image-identity"
    assert fields["Initiating dataset hash"] == "image-hash"


def test_methods_input_read_is_bounded(monkeypatch, tmp_path):
    path = tmp_path / "oversized.json"
    path.write_bytes(b"{}" + b" " * 64)
    monkeypatch.setattr(methods_table, "MAX_INPUT_BYTES", 32)
    try:
        methods_table.load_json_file(str(path))
    except ValueError as exc:
        assert "exceeds" in str(exc)
    else:
        raise AssertionError("oversized methods input was accepted")


def test_offline_relative_log_path_is_resolved_beside_bundle(tmp_path):
    bundle = tmp_path / "exports" / "methods-input.json"
    expected = bundle.parent / "logs" / "session.json"
    resolved = methods_table.resolve_bundle_log_path(
        str(bundle), os.path.join("logs", "session.json"))
    assert os.path.normcase(os.path.abspath(resolved)) == os.path.normcase(str(expected))


def test_latest_session_log_never_crosses_launcher_sessions(monkeypatch, tmp_path):
    older = tmp_path / "session_owned.json"
    newer = tmp_path / "session_other.json"
    older.write_text(
        '{"session_id":"owned-log","client_session_id":"launch-owned"}',
        encoding="utf-8",
    )
    newer.write_text(
        '{"session_id":"other-log","client_session_id":"launch-other"}',
        encoding="utf-8",
    )
    older.touch()
    newer.touch()
    monkeypatch.setattr(methods_table, "TMP_DIR", str(tmp_path))

    path, data = methods_table.latest_session_log("launch-owned")
    assert path == str(older)
    assert data["session_id"] == "owned-log"
    assert methods_table.latest_session_log("missing-launch") == (None, None)


def test_latest_session_log_scan_has_a_file_count_bound(monkeypatch, tmp_path):
    for index in range(4):
        (tmp_path / f"session_{index}.json").write_text("{}", encoding="utf-8")
    monkeypatch.setattr(methods_table, "TMP_DIR", str(tmp_path))
    monkeypatch.setattr(methods_table, "MAX_SESSION_LOG_CANDIDATES", 3)
    try:
        methods_table.latest_session_log()
    except ValueError as exc:
        assert "file limit" in str(exc)
    else:
        raise AssertionError("unbounded session log directory was accepted")
