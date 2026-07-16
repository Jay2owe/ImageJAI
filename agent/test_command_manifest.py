from pathlib import Path

import pytest

import generate_command_docs as command_docs
import ij


ROOT = Path(__file__).resolve().parents[1]


def test_manifest_is_canonical_sorted_complete_server_surface():
    manifest = command_docs.load_manifest()
    counts = command_docs.validate_coverage(manifest)
    assert counts == {
        "total": 61,
        "request_response": 60,
        "stream": 1,
        "convenience": 44,
        "raw": 17,
    }
    assert command_docs.extract_server_commands() == {
        entry["name"] for entry in manifest["commands"]
    }


def test_python_coverage_is_explicit_and_resolves_to_exported_helpers():
    manifest = command_docs.load_manifest()
    functions, exported = command_docs.extract_python_api()
    helper_commands = command_docs.extract_python_helper_commands()
    for entry in manifest["commands"]:
        python = entry["python"]
        if python["coverage"] == "convenience":
            assert python["helpers"]
            assert set(python["helpers"]) <= functions
            assert set(python["helpers"]) <= exported
            assert any(
                entry["name"] in helper_commands.get(helper, set())
                for helper in python["helpers"]
            )
        else:
            assert python["coverage"] == "raw"
            assert python["helpers"] == []
            assert "imagej_command" in entry["summary"]


def test_manifest_is_packaged_at_one_stable_jar_path():
    pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
    assert pom.count("<include>command_manifest.json</include>") == 1
    assert "<targetPath>imagejai</targetPath>" in pom


def test_generated_command_documents_are_current_and_repeatable():
    first = (ROOT / "docs" / "COMMAND_API.md").read_bytes()
    assert command_docs.generate(check=True) == []
    assert command_docs.render_api(
        command_docs.load_manifest(),
        command_docs.validate_coverage(command_docs.load_manifest()),
    ).encode("utf-8") == first


def test_command_docs_disclose_session_envelope_and_hash_cache_fields():
    text = (ROOT / "docs" / "COMMAND_API.md").read_text(encoding="utf-8")
    assert "Every request also carries `command`" in text
    assert "`session_id` and `token`" in text
    assert "hash cache: optional `if_none_match`" in text


def test_command_doc_inputs_are_read_with_a_hard_byte_limit(tmp_path):
    path = tmp_path / "oversized.txt"
    path.write_bytes(b"x" * 33)
    with pytest.raises(ValueError, match="oversized"):
        command_docs._read_bounded(path, 32)


def test_java_dispatch_extractor_ignores_braces_in_literals_and_comments():
    source = '''
        void dispatchCore(String command) {
            String sample = "}"; // } must not close the method
            /* { must not open a block */
            if ("ping".equals(command)) { return; }
        }
        void later() {}
    '''
    body = command_docs._java_method_body(source, "dispatchCore")
    assert '"ping".equals(command)' in body
    assert "void later" not in body


def test_python_hash_cache_set_is_manifest_backed():
    expected = {
        entry["name"] for entry in command_docs.load_manifest()["commands"]
        if entry.get("dedup_hash") is True
    }
    assert ij.READONLY_COMMANDS == expected
