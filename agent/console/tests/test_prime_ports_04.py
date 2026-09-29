from types import SimpleNamespace
import pytest
from agent.console.skills import discover_skills
from agent.console.runtime_tools import attach_runtime_tools

def make_skill(root, name, hidden=False, body="Use calibrated measurements."):
    folder = root / ".imagejai" / "skills" / name
    folder.mkdir(parents=True)
    path = folder / "SKILL.md"
    path.write_text(f"---\nname: {name}\ndescription: Measure safely\ndisable-model-invocation: {str(hidden).lower()}\n---\n{body}")
    return path

def test_model_can_load_guidance_and_hidden_skill_stays_user_only(tmp_path):
    make_skill(tmp_path, "visible")
    make_skill(tmp_path, "hidden", True)
    catalog = discover_skills(tmp_path, repo_root=tmp_path)
    agent = SimpleNamespace(tools=[])
    attach_runtime_tools(agent, skill_loader=lambda name:catalog.load_body(name, model_invoked=True))
    assert agent.tools[0]("visible")["guidance"] == "Use calibrated measurements."
    with pytest.raises(PermissionError): agent.tools[0]("hidden")
    assert catalog.load_body("hidden", model_invoked=False)

def test_rediscovery_reads_changed_body_and_rejects_new_hidden_flag(tmp_path):
    path = make_skill(tmp_path, "test")
    path.write_text(path.read_text().replace("Use calibrated measurements.", "Use micrometres."))
    catalog = discover_skills(tmp_path, repo_root=tmp_path)
    assert catalog.load_body("test") == "Use micrometres."
    path.write_text(path.read_text().replace("false", "true"))
    catalog = discover_skills(tmp_path, repo_root=tmp_path)
    with pytest.raises(PermissionError): catalog.load_body("test")
