import json
import sys
from pathlib import Path

import pytest
import yaml


AGENT_DIR = Path(__file__).resolve().parent
if str(AGENT_DIR) not in sys.path:
    sys.path.insert(0, str(AGENT_DIR))

import recipe_search
import run_recipe


def _recipe(steps, **overrides):
    value = {
        "schema_version": 1,
        "name": "Contract test",
        "id": "contract_test",
        "description": "Exercise every recipe dispatcher.",
        "domain": "testing",
        "difficulty": "beginner",
        "preconditions": {
            "image_type": ["any"],
            "min_channels": 0,
            "needs_stack": False,
            "needs_calibration": False,
        },
        "parameters": [
            {"name": "enabled", "type": "boolean", "default": True},
            {"name": "amount", "type": "numeric", "default": 2, "range": [1, 5]},
        ],
        "steps": steps,
    }
    value.update(overrides)
    return value


class FakeTransport:
    def __init__(self, active=True):
        self.calls = []
        self.active = active

    def execute_macro(self, code, timeout=None):
        self.calls.append(("macro", code, timeout))
        return {"ok": True, "result": {"success": True}}

    def run_script(self, code, language, timeout=None):
        self.calls.append(("script", code, language, timeout))
        return {"ok": True, "result": {"success": True}}

    def capture(self):
        self.calls.append(("capture",))
        return {"ok": True, "result": {"data": "not-used"}}

    def get_state(self):
        self.calls.append(("state",))
        active = None
        if self.active:
            active = {
                "title": "test.tif", "type": "16-bit", "channels": 2,
                "slices": 3, "frames": 4, "calibration": "0.5 um/px",
            }
        return {"ok": True, "result": {"activeImage": active}}

    def get_results(self):
        self.calls.append(("results",))
        return {"ok": True, "result": "Area,Mean\n10,20\n"}


def test_canonical_schema_and_dry_run_cover_every_bundled_recipe(monkeypatch):
    schema = json.loads((AGENT_DIR / "recipes" / "schema.json").read_text(encoding="utf-8"))
    assert schema["properties"]["schema_version"]["const"] == recipe_search.SCHEMA_VERSION
    monkeypatch.setattr(run_recipe, "FijiTransport", lambda: pytest.fail("dry-run contacted Fiji"))

    paths = sorted((AGENT_DIR / "recipes").glob("*.yaml"))
    assert paths
    for path in paths:
        recipe = recipe_search.load_recipe(str(path))
        assert recipe_search.validate_recipe(recipe) == [], path.name
        plan = run_recipe.dry_run_recipe(recipe, str(path))
        assert len(plan) == len(recipe["steps"])
        assert all(item["type"] in recipe_search.STEP_TYPES for item in plan)


def test_unsupported_later_step_prevents_every_mutation(tmp_path):
    recipe = _recipe([
        {"id": 1, "description": "Would mutate", "type": "macro", "macro": 'run("Invert");'},
        {"id": 2, "description": "Unknown", "type": "teleport", "code": "x"},
    ])
    transport = FakeTransport()

    with pytest.raises(ValueError, match="unsupported"):
        run_recipe.execute_recipe(recipe, str(tmp_path / "recipe.yaml"), transport=transport)

    assert transport.calls == []


def test_all_dispatchers_conditions_captures_and_contracts(tmp_path):
    script = tmp_path / "step.groovy"
    script.write_text('println("${amount}")\n', encoding="utf-8")
    recipe = _recipe([
        {
            "id": 1, "description": "Macro", "type": "macro",
            "macro": 'run("Multiply...", "value=${amount}");',
            "capture_after": True,
            "validation": {"type": "image_open"},
        },
        {
            "id": 2, "description": "Inline script", "type": "script",
            "language": "groovy", "script": 'println("${amount}")',
        },
        {
            "id": 3, "description": "File script", "type": "script",
            "language": "groovy", "file": "step.groovy",
        },
        {
            "id": 4, "description": "Manual", "type": "manual",
            "instructions": "Inspect the image", "validate": "It looks correct",
        },
        {
            "id": 5, "description": "False branch", "type": "macro",
            "macro": 'run("Invert");', "when": {"parameter": "enabled", "equals": False},
        },
    ], postconditions={"check": "Output exists", "method": "Inspect it"},
       validation={"type": "results_nonempty"})
    transport = FakeTransport()
    prompts = []

    receipt = run_recipe.execute_recipe(
        recipe, str(tmp_path / "recipe.yaml"), transport=transport,
        acknowledge=lambda prompt: prompts.append(prompt) or True,
        emit=lambda _message: None,
    )

    assert [row["status"] for row in receipt["steps"]] == ["ok", "ok", "ok", "ok", "not_applicable"]
    assert [call[0] for call in transport.calls].count("macro") == 1
    assert [call[0] for call in transport.calls].count("script") == 2
    assert ("capture",) in transport.calls
    assert any("Inspect the image" in prompt for prompt in prompts)
    assert any("Output exists" in prompt for prompt in prompts)


def test_precondition_and_validation_failures_are_blocking(tmp_path):
    recipe = _recipe(
        [{"description": "Mutate", "type": "macro", "macro": 'run("Invert");'}],
        preconditions={
            "image_type": ["16-bit"], "min_channels": 2, "needs_stack": True,
            "needs_calibration": True,
        },
    )
    transport = FakeTransport(active=False)
    with pytest.raises(RuntimeError, match="active image"):
        run_recipe.execute_recipe(recipe, str(tmp_path / "recipe.yaml"), transport=transport)
    assert all(call[0] != "macro" for call in transport.calls)

    recipe["preconditions"] = {"image_type": ["any"], "min_channels": 0}
    recipe["validation"] = {"type": "acknowledgement", "prompt": "Confirm result"}
    transport = FakeTransport()
    with pytest.raises(RuntimeError, match="acknowledgement"):
        run_recipe.execute_recipe(
            recipe, str(tmp_path / "recipe.yaml"), transport=transport,
            acknowledge=lambda _prompt: False, emit=lambda _message: None,
        )
    assert any(call[0] == "macro" for call in transport.calls)


def test_empty_step_validation_is_explicitly_no_contract(tmp_path):
    recipe = _recipe([
        {
            "description": "Default-style macro", "type": "macro",
            "macro": 'run("Invert");', "validate": "",
        }
    ])
    transport = FakeTransport()
    prompts = []

    receipt = run_recipe.execute_recipe(
        recipe, str(tmp_path / "recipe.yaml"), transport=transport,
        acknowledge=lambda prompt: prompts.append(prompt) or False,
        emit=lambda _message: None,
    )

    assert receipt["ok"] is True
    assert receipt["steps"][0]["status"] == "ok"
    assert prompts == []
    assert [call[0] for call in transport.calls] == ["macro"]


def test_malformed_yaml_and_unknown_fields_exit_nonzero(tmp_path, capsys):
    (tmp_path / "broken.yaml").write_text("name: [unterminated\n", encoding="utf-8")
    bad = _recipe([{"description": "x", "type": "macro", "macro": 'run("Invert");'}])
    bad["mystery"] = True
    (tmp_path / "unknown.yaml").write_text(yaml.safe_dump(bad), encoding="utf-8")

    results = recipe_search.validate_all(str(tmp_path))
    assert len(results) == 2
    assert all(not row["valid"] for row in results)
    assert recipe_search.main(["--validate", "--recipe-dir", str(tmp_path)]) == 1
    assert "validation failed" in capsys.readouterr().out.lower()


def test_script_files_cannot_traverse_or_follow_symlinks_outside_recipe_root(tmp_path):
    recipe_dir = tmp_path / "recipes"
    recipe_dir.mkdir()
    recipe_file = recipe_dir / "recipe.yaml"
    outside = tmp_path / "outside.groovy"
    outside.write_text('println("outside")\n', encoding="utf-8")

    with pytest.raises(ValueError, match="safe relative path"):
        run_recipe._resolve_file("../outside.groovy", str(recipe_file))
    with pytest.raises(ValueError, match="safe relative path"):
        run_recipe._resolve_file(str(outside.resolve()), str(recipe_file))

    link = recipe_dir / "linked.groovy"
    try:
        link.symlink_to(outside)
    except OSError:
        pytest.skip("creating a symlink is unavailable on this platform")
    with pytest.raises(ValueError, match="escapes its allowed recipe root"):
        run_recipe._resolve_file("linked.groovy", str(recipe_file))


def test_recommendation_bonuses_are_additive(monkeypatch):
    compatible = _recipe([{"description": "count", "type": "macro", "macro": 'run("Invert");'}])
    compatible.update({"id": "compatible", "name": "Count", "preconditions": {
        "image_type": ["16-bit"], "min_channels": 2, "needs_stack": True,
    }})
    type_only = _recipe([{"description": "count", "type": "macro", "macro": 'run("Invert");'}])
    type_only.update({"id": "type_only", "name": "Count", "preconditions": {
        "image_type": ["16-bit"], "min_channels": 0, "needs_stack": False,
    }})
    monkeypatch.setattr(recipe_search, "load_all_recipes", lambda _dir=None: [type_only, compatible])

    chosen = recipe_search.recommend(
        image_info={"type": "16-bit", "channels": 2, "slices": 5}, task="count"
    )

    assert chosen["id"] == "compatible"
