from pathlib import Path
from xml.etree import ElementTree

import pytest

import generate_command_docs as command_docs
import ij


ROOT = Path(__file__).resolve().parents[1]
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}

# The manifest is packaged at two jar paths on purpose. They are not copies of
# each other in any sense that could be de-duplicated:
#
#   imagejai/      canonical, client-facing. Read off the classpath by
#                  CommandManifest.RESOURCE.
#   agent-runtime/ part of the Python workspace BundledAgentWorkspace extracts
#                  to disk for a JAR-only Fiji install. The Python readers
#                  resolve the manifest as a sibling of their own __file__, so
#                  the classpath copy is invisible to them.
#
# Counting occurrences of the <include> cannot express that, and a count of one
# would be satisfied by deleting whichever copy you happened to delete. These
# tests assert the two paths and the reason the second one exists instead.
MANIFEST_JAR_PATHS = {"imagejai", "agent-runtime"}

# Bundled modules that read the manifest from their own directory.
WORKSPACE_MANIFEST_READERS = {"ij.py", "methods_table.py"}


def _packaged_resources():
    """Map each build resource's targetPath to the file names it includes."""
    root = ElementTree.parse(ROOT / "pom.xml").getroot()
    blocks = []
    for resource in root.findall("./m:build/m:resources/m:resource", POM_NS):
        target = resource.findtext("m:targetPath", default="", namespaces=POM_NS)
        includes = {
            node.text.strip()
            for node in resource.findall("./m:includes/m:include", POM_NS)
            if node.text
        }
        blocks.append((target.strip(), includes))
    return blocks


def test_manifest_is_canonical_sorted_complete_server_surface():
    manifest = command_docs.load_manifest()
    counts = command_docs.validate_coverage(manifest)
    assert counts == {
        "total": 71,
        "request_response": 70,
        "stream": 1,
        "convenience": 54,
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


def test_manifest_is_packaged_at_the_two_paths_that_read_it():
    targets = [
        target for target, includes in _packaged_resources()
        if "command_manifest.json" in includes
    ]
    assert len(targets) == len(set(targets))
    assert set(targets) == MANIFEST_JAR_PATHS

    java = (ROOT / "src" / "main" / "java" / "imagejai" / "engine"
            / "CommandManifest.java").read_text(encoding="utf-8")
    assert '"/imagejai/command_manifest.json"' in java


def test_workspace_copy_sits_beside_the_python_that_reads_it():
    workspace = dict(_packaged_resources())["agent-runtime"]
    assert WORKSPACE_MANIFEST_READERS <= workspace
    assert "command_manifest.json" in workspace

    # If a reader ever loads the manifest from the jar instead, the workspace
    # copy becomes dead weight and this is the test that should say so.
    for name in sorted(WORKSPACE_MANIFEST_READERS):
        source = (ROOT / "agent" / name).read_text(encoding="utf-8")
        assert '"command_manifest.json")' in source
        assert "os.path.dirname(os.path.abspath(__file__))" in source


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
