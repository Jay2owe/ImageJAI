"""Contract tests for the ImageJ-AI test automation bridge.

These run without Fiji. They keep the three artifacts an independent client
depends on in agreement: the prose contract
(``docs/automation-bridge/PROTOCOL.md``), the JSON Schemas, the golden
fixtures, and the canonical command manifest.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

jsonschema = pytest.importorskip("jsonschema")

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "docs" / "automation-bridge"
SCHEMAS = BRIDGE / "schemas"
FIXTURES = BRIDGE / "fixtures"
PROTOCOL_MD = BRIDGE / "PROTOCOL.md"
MANIFEST = ROOT / "agent" / "command_manifest.json"

PROTOCOL_VERSION = "1.0.0"
CAPABILITY = "test_automation"
COMMANDS = [
    "get_ui_tree",
    "get_ui_component",
    "perform_ui_action",
    "wait_for_ui_state",
    "wait_for_ui_idle",
    "capture_ui",
    "start_ui_trace",
    "stop_ui_trace",
    "get_ui_metrics",
]


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def index() -> dict:
    return _load(FIXTURES / "index.json")


@pytest.fixture(scope="module")
def validator_registry():
    """Resolve ``$ref`` between sibling schema files without network access."""
    store = {}
    for schema_path in SCHEMAS.glob("*.schema.json"):
        schema = _load(schema_path)
        store[schema_path.name] = schema
        store[schema.get("$id", schema_path.name)] = schema
    return store


def _validator(schema_name: str, registry):
    schema = registry[schema_name]
    resolver = jsonschema.RefResolver(base_uri="", referrer=schema, store=registry)
    return jsonschema.Draft202012Validator(schema, resolver=resolver)


def test_every_fixture_validates_against_its_declared_schema(index, validator_registry):
    assert index["protocol_version"] == PROTOCOL_VERSION
    assert index["fixtures"], "the fixture index cannot be empty"
    for entry in index["fixtures"]:
        document = _load(FIXTURES / entry["file"])
        errors = sorted(
            _validator(entry["schema"], validator_registry).iter_errors(document),
            key=lambda error: list(error.path),
        )
        assert not errors, "{}: {}".format(
            entry["file"], "; ".join(error.message for error in errors)
        )


def test_the_index_covers_every_fixture_and_schema(index):
    listed = {entry["file"] for entry in index["fixtures"]}
    on_disk = {
        path.name
        for path in FIXTURES.glob("*.json")
        if path.name not in {"index.json", "requests.json"}
    }
    assert listed == on_disk

    referenced = {entry["schema"] for entry in index["fixtures"]}
    schemas = {path.name for path in SCHEMAS.glob("*.schema.json")}
    # ui-node is referenced transitively rather than bound to a fixture.
    assert referenced | {"ui-node.schema.json"} == schemas


def test_schemas_are_self_consistent(validator_registry):
    for name, schema in validator_registry.items():
        if not name.endswith(".schema.json"):
            continue
        jsonschema.Draft202012Validator.check_schema(schema)
        assert schema["$id"] == name
        assert schema["$schema"].endswith("2020-12/schema")


def _strings(node):
    if isinstance(node, str):
        yield node
    elif isinstance(node, dict):
        for value in node.values():
            yield from _strings(value)
    elif isinstance(node, list):
        for value in node:
            yield from _strings(value)


_HOST_PATH = re.compile(r"(Users\\Owner|/Users/|^[A-Za-z]:\\)")
_BASE64URL = re.compile(r"^[A-Za-z0-9_-]{40,}$")
_HEX_DIGEST = re.compile(r"^[0-9a-f]+$")


def test_no_fixture_or_schema_carries_a_credential_or_host_path():
    for path in list(FIXTURES.glob("*.json")) + list(SCHEMAS.glob("*.json")):
        for value in _strings(_load(path)):
            assert not _HOST_PATH.search(value), f"{path.name}: host path {value!r}"
            assert not value.startswith("sk-"), f"{path.name}: api key {value!r}"
            # A real installation token is 32 random bytes base64url-encoded.
            # SHA-256 hex digests are longer but strictly lowercase hex, so the
            # two are distinguishable without weakening the check.
            looks_like_token = (
                _BASE64URL.match(value) and not _HEX_DIGEST.match(value)
            )
            assert not looks_like_token, f"{path.name}: token-shaped {value!r}"

    # Token values appear only as placeholders that tell a client where to read
    # the real one from.
    for request in _load(FIXTURES / "requests.json").values():
        assert request.get("token", "<").startswith("<")


def test_ready_file_fixture_has_no_token_field():
    ready = _load(FIXTURES / "ready-file.json")
    assert "token" not in json.dumps(ready)
    assert ready["test_automation"] is True
    assert ready["workspace_id"].startswith("sha256:")


def test_manifest_declares_every_gated_command_as_capability_bound():
    manifest = _load(MANIFEST)
    entries = {entry["name"]: entry for entry in manifest["commands"]}
    for command in COMMANDS:
        assert command in entries, f"{command} is missing from the manifest"
        entry = entries[command]
        assert entry["capabilities"] == [CAPABILITY], command
        # Every automation command needs a negotiated session; none of them may
        # fall back to the unauthenticated compatibility surface.
        assert entry["authentication"] == "session_required", command
        assert "Test automation only." in entry["summary"], command


def test_mutating_commands_declare_their_generation_and_poll_fields():
    entries = {
        entry["name"]: entry for entry in _load(MANIFEST)["commands"]
    }
    action = entries["perform_ui_action"]
    assert action["classification"] == "mutation"
    assert set(action["request"]["required"]) == {"node_id", "generation", "action"}
    # A timed-out mutation is polled, never replayed.
    assert "operation_id" in action["request"]["optional"]
    assert "generation" in entries["capture_ui"]["request"]["required"]
    assert "operation_id" in entries["capture_ui"]["request"]["optional"]


def test_protocol_document_covers_the_whole_surface():
    text = PROTOCOL_MD.read_text(encoding="utf-8")
    assert f"v{PROTOCOL_VERSION}" in text
    for command in COMMANDS:
        assert f"`{command}`" in text, f"{command} is undocumented"
    for prop in (
        "imagejai.testAutomation.enabled",
        "imagejai.testAutomation.workspace",
        "imagejai.testAutomation.readyFile",
        "imagejai.testAutomation.port",
    ):
        assert prop in text
    for topic in ("ui.window.appeared", "ui.window.closed", "ui.idle",
                  "ui.action.started", "ui.action.completed"):
        assert topic in text
    # The semantic/physical split is the whole reason the harness exists; it
    # must be stated, not implied.
    assert "physical_input" in text
    assert "never synthesises native mouse or keyboard input" in text


def test_protocol_document_lists_every_error_code_the_fixtures_use(index):
    text = PROTOCOL_MD.read_text(encoding="utf-8")
    for entry in index["fixtures"]:
        document = _load(FIXTURES / entry["file"])
        error = document.get("error")
        if isinstance(error, dict):
            assert f"`{error['code']}`" in text, error["code"]


def test_error_codes_in_the_schema_are_all_documented():
    schema = _load(SCHEMAS / "error.schema.json")
    codes = schema["properties"]["error"]["properties"]["code"]["enum"]
    text = PROTOCOL_MD.read_text(encoding="utf-8")
    for code in codes:
        assert f"`{code}`" in text, f"{code} is not documented in PROTOCOL.md"


def test_request_examples_exist_for_every_command():
    requests = _load(FIXTURES / "requests.json")
    named = {value["command"] for value in requests.values()}
    assert set(COMMANDS) <= named
    assert "hello" in named
    for name, request in requests.items():
        if request["command"] == "hello":
            assert request["capabilities"][CAPABILITY] is True
        else:
            assert "session_id" in request and "token" in request, name


def test_python_client_exposes_opt_in_helpers_without_changing_defaults():
    source = (ROOT / "agent" / "ij.py").read_text(encoding="utf-8")
    # The capability must never be part of the default handshake: a normal
    # agent session on a test-mode Fiji still cannot reach the UI surface.
    hello_caps = source.split("_HELLO_CAPS = {", 1)[1].split("}", 1)[0]
    assert CAPABILITY not in hello_caps
    assert "def use_test_automation(" in source
    for command in COMMANDS:
        assert f"def {command}(" in source, f"ij.py has no {command} helper"
