from __future__ import annotations

from types import SimpleNamespace

from agent.gemma4_31b import tools_recipes


class _RecordingSession:
    def __init__(self):
        self.calls = []

    def request(self, command, timeout=None):
        self.calls.append((command, timeout))
        return {"ok": True, "result": command["command"]}


def test_recipe_transport_routes_every_command_through_one_session() -> None:
    session = _RecordingSession()
    transport = tools_recipes._GemmaRecipeTransport(session)

    transport.get_state()
    transport.execute_macro("run(\"Measure\");", timeout=7)
    transport.run_script("println 1", "groovy", timeout=9)
    transport.get_results()
    transport.capture()

    assert [call[0]["command"] for call in session.calls] == [
        "get_state",
        "execute_macro",
        "run_script",
        "get_results_table",
        "capture_image",
    ]
    assert session.calls[1][0]["source"] == "rail:recipe"
    assert session.calls[1][1] == 7
    assert session.calls[2][0]["source"] == "rail:recipe"
    assert session.calls[2][1] == 9


def test_saved_recipe_execution_injects_the_registry_session(monkeypatch) -> None:
    session = _RecordingSession()
    seen = {}

    def execute_recipe_file(path, **kwargs):
        seen["path"] = path
        seen.update(kwargs)
        return {"ok": True, "recipe": "safe-recipe", "steps": []}

    runner = SimpleNamespace(
        recipe_path=lambda name: "/recipes/{}.yaml".format(name),
        load_recipe=lambda path: {"id": "safe-recipe", "steps": [{"macro": "print(1);"}]},
        dry_run_recipe=lambda recipe, path: [{"index": 1, "type": "macro"}],
        requires_acknowledgement=lambda recipe: False,
        execute_recipe_file=execute_recipe_file,
    )
    monkeypatch.setattr(tools_recipes, "_agent_module", lambda name: runner)
    monkeypatch.setattr(tools_recipes, "imagej_session", lambda: session)

    result = tools_recipes.run_saved_recipe("safe-recipe")

    assert '"ok": true' in result
    assert seen["path"] == "/recipes/safe-recipe.yaml"
    assert seen["transport"]._session is session
    assert seen["dry_run"] is False
