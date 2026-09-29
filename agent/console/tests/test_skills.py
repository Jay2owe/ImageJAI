from __future__ import annotations

from pathlib import Path
import pytest

from agent.console.skills import SkillLoadError, discover_skills


def write_skill(root: Path, name: str, description: str = "Useful guidance", body: str = "SECRET BODY", extra: str = "") -> Path:
    folder = root / name
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / "SKILL.md"
    path.write_text(
        f"---\nname: {name}\ndescription: {description}\n{extra}---\n\n{body}\n",
        encoding="utf-8",
    )
    return path


def test_precedence_collision_and_diagnostics(tmp_path: Path):
    repo = tmp_path / "repo"; project = repo / "work"; project.mkdir(parents=True)
    high = write_skill(project / ".imagejai" / "skills", "segment", body="project body")
    write_skill(project / ".agents" / "skills", "segment", body="agents body")
    write_skill(tmp_path / "user", "segment", body="user body")
    catalog = discover_skills(project, repo_root=repo, user_skills_dir=tmp_path / "user")
    assert catalog.get("segment").path == high
    assert catalog.load_body("segment") == "project body"
    assert any(d.code == "collision" for d in catalog.diagnostics)


def test_prompt_catalog_is_metadata_only_and_recipe_is_reference(tmp_path: Path):
    repo = tmp_path / "repo"; repo.mkdir()
    write_skill(repo / ".imagejai" / "skills", "measure", description="Measure cells", body="DO NOT EAGERLY INCLUDE", extra="metadata:\n  recipe: cell_counting\n")
    catalog = discover_skills(repo, repo_root=repo)
    prompt = catalog.prompt_catalog()
    assert "Measure cells" in prompt and "cell_counting" in prompt
    assert "reference only" in prompt
    assert "DO NOT EAGERLY INCLUDE" not in prompt
    assert str(repo) not in prompt
    assert "skill:measure" in prompt


def test_disable_model_invocation_but_user_can_load(tmp_path: Path):
    repo = tmp_path / "repo"; repo.mkdir()
    write_skill(repo / ".imagejai" / "skills", "private-guide", extra="disable-model-invocation: true\n")
    catalog = discover_skills(repo, repo_root=repo)
    with pytest.raises(PermissionError):
        catalog.load_body("private-guide", model_invoked=True)
    assert catalog.load_body("private-guide", model_invoked=False) == "SECRET BODY"


def test_invalid_name_directory_and_yaml_are_diagnostics(tmp_path: Path):
    repo = tmp_path / "repo"; root = repo / ".imagejai" / "skills"; root.mkdir(parents=True)
    bad = root / "Wrong"; bad.mkdir(); (bad / "SKILL.md").write_text("---\nname: Wrong\ndescription: x\n---\nbody", encoding="utf-8")
    mismatch = root / "folder"; mismatch.mkdir(); (mismatch / "SKILL.md").write_text("---\nname: other\ndescription: x\n---\nbody", encoding="utf-8")
    catalog = discover_skills(repo, repo_root=repo)
    assert len(catalog) == 0
    assert len([d for d in catalog.diagnostics if d.code == "invalid-skill"]) == 2


def test_search_uses_metadata_not_body(tmp_path: Path):
    repo = tmp_path / "repo"; repo.mkdir()
    write_skill(repo / ".imagejai" / "skills", "threshold-help", description="Choose a threshold", body="hidden stardist phrase")
    catalog = discover_skills(repo, repo_root=repo)
    assert [s.name for s in catalog.search("threshold")] == ["threshold-help"]
    assert catalog.search("stardist") == []


def test_body_change_after_discovery_fails_closed(tmp_path: Path):
    repo = tmp_path / "repo"; repo.mkdir()
    path = write_skill(repo / ".imagejai" / "skills", "stable")
    catalog = discover_skills(repo, repo_root=repo)
    path.write_text("---\nname: changed\ndescription: Changed\n---\nnew", encoding="utf-8")
    with pytest.raises(SkillLoadError):
        catalog.load_body("stable")


def test_catalog_bound_and_skill_count(tmp_path: Path):
    repo = tmp_path / "repo"; root = repo / ".imagejai" / "skills"; repo.mkdir()
    for n in range(4): write_skill(root, f"skill-{n}", description="x" * 80)
    catalog = discover_skills(repo, repo_root=repo, max_skills=2)
    assert len(catalog) == 2
    assert len(catalog.prompt_catalog(max_chars=300)) <= 300
    assert any(d.code == "skill-count-limit" for d in catalog.diagnostics)


def test_project_must_be_inside_explicit_repo(tmp_path: Path):
    repo = tmp_path / "repo"; repo.mkdir(); outside = tmp_path / "outside"; outside.mkdir()
    with pytest.raises(ValueError):
        discover_skills(outside, repo_root=repo)
