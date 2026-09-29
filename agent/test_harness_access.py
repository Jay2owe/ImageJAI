from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

from agent import harness_access as access
from agent.console.harness import HarnessStore


def admin_store(root: Path) -> HarnessStore:
    return HarnessStore(root / "harness_state.json", root / "refinements.jsonl")


def promote_once(store: HarnessStore, entry_id: str):
    entry = store.get(entry_id)
    return store.promote(entry.id, expected_version=entry.version,
                         reviewer="test-reviewer", evidence="hermetic test review")


def write_skill(root: Path, name: str, body: str, *, disabled: bool = False) -> None:
    folder = root / name
    folder.mkdir(parents=True)
    (folder / "SKILL.md").write_text(
        "---\n"
        f"name: {name}\n"
        f"description: Guidance for {name}\n"
        f"disable-model-invocation: {'true' if disabled else 'false'}\n"
        "---\n"
        f"{body}\n",
        encoding="utf-8",
    )


def test_cross_interface_candidate_then_admin_review(tmp_path: Path):
    home = tmp_path / "home"
    candidate = access.propose_candidate(
        "fact", "user", "Reviewed fact", "Use a calibrated scale",
        home=home,
    )
    root = home / "harness"
    store = admin_store(root)
    assert store.get(candidate["id"]).status == "candidate"

    before = access.reviewed_context("calibrated", {}, {}, home=home)
    assert "Use a calibrated scale" not in before
    promote_once(store, candidate["id"])
    after = access.reviewed_context("calibrated", {}, {}, home=home)
    assert "Use a calibrated scale" in after
    assert "session_confirmed/user" in after


def test_default_path_redaction_and_residual_sample_rejection(tmp_path: Path):
    home = tmp_path / "home"
    entry = access.propose_candidate(
        "fact", "user", "Location", r"Observed at C:\private\donor-7\image.tif",
        home=home,
    )
    assert entry["content"] == "Observed at [REDACTED_PATH]"
    assert "private" not in json.dumps(entry)

    with pytest.raises(access.HarnessAccessError, match="sample filename"):
        access.propose_candidate(
            "fact", "user", "Sample", "donor-7.tif",
            home=home,
        )
    with pytest.raises(access.HarnessAccessError, match="absolute path"):
        access.propose_candidate(
            "fact", "user", "Path", "/private/donor/image.tif",
            privacy_sanitizer=lambda value: value, home=home,
        )


def test_reviewed_context_redacts_admin_authored_absolute_path(tmp_path: Path):
    home = tmp_path / "home"
    store = admin_store(home / "harness")
    item = store.propose(kind="fact", scope="user", title="Private",
                         content=r"Saved in C:\private-fixture\Somebody\secret\mouse.czi")
    promote_once(store, item.id)
    digest = access.reviewed_context("Private", {}, {}, home=home)
    assert "Somebody" not in digest
    assert "[REDACTED_PATH]" in digest


def test_scope_routing_and_required_context(tmp_path: Path):
    home, project = tmp_path / "home", tmp_path / "project"
    project.mkdir()
    routed = {
        "session": (dict(home=home, session_id="s-1"),
                    home / "sessions" / "s-1" / "harness"),
        "project": (dict(project_dir=project),
                    project / "AI_Exports" / ".imagejai-harness"),
        "instrument": (dict(project_dir=project),
                       project / "AI_Exports" / ".imagejai-harness"),
        "user": (dict(home=home), home / "harness"),
        "shared": (dict(home=home), home / "harness"),
    }
    ids = {}
    for scope, (kwargs, destination) in routed.items():
        value = access.propose_candidate("fact", scope, scope, f"content {scope}", **kwargs)
        ids[scope] = value["id"]
        assert admin_store(destination).get(value["id"]).scope == scope
    with pytest.raises(access.HarnessAccessError, match="project_dir"):
        access.propose_candidate("fact", "project", "x", "y")
    with pytest.raises(access.HarnessAccessError, match="session_id"):
        access.propose_candidate("fact", "session", "x", "y", home=home)
    with pytest.raises(access.HarnessAccessError, match="home"):
        access.propose_candidate("fact", "user", "x", "y")


def test_review_merge_is_deterministic_bounded_and_scope_checked(tmp_path: Path):
    home, project = tmp_path / "home", tmp_path / "project"
    project.mkdir()
    entries = [
        access.propose_candidate("fact", "session", "Session", "session content",
                                 home=home, session_id="abc"),
        access.propose_candidate("fact", "project", "Project", "project content",
                                 project_dir=project),
        access.propose_candidate("fact", "shared", "Shared", "shared content", home=home),
    ]
    roots = [home / "sessions" / "abc" / "harness",
             project / "AI_Exports" / ".imagejai-harness", home / "harness"]
    for entry, root in zip(entries, roots):
        promote_once(admin_store(root), entry["id"])
    profile = {"max_entries": 2, "max_digest_chars": 500}
    one = access.reviewed_context("content", {}, profile, project, "abc", home=home)
    two = access.reviewed_context("content", {}, profile, project, "abc", home=home)
    assert one == two
    assert len(one) <= 500
    assert "session content" in one and "project content" in one
    assert "shared content" not in one


def test_no_promotion_surface_or_cli_command():
    for forbidden in ("promote", "update", "deprecate", "rollback"):
        assert forbidden not in access.__all__
        assert not hasattr(access, forbidden)
    parser = access._parser()
    choices = parser._subparsers._group_actions[0].choices
    assert set(choices) == {"context", "propose", "skills", "skill"}


def test_skills_are_progressive_and_disable_is_enforced(tmp_path: Path):
    project, bundled, home = tmp_path / "project", tmp_path / "bundled", tmp_path / "home"
    (project / ".git").mkdir(parents=True)
    write_skill(bundled, "visible", "VISIBLE SECRET BODY")
    write_skill(bundled, "manual-only", "MANUAL SECRET BODY", disabled=True)

    result = access.skill_catalog(project, home=home, bundled_skills_dir=bundled)
    assert {item["name"] for item in result["skills"]} == {"visible", "manual-only"}
    assert "SECRET BODY" not in result["catalog"]
    assert access.load_skill("visible", project, home=home,
                             bundled_skills_dir=bundled)["body"] == "VISIBLE SECRET BODY"
    with pytest.raises(PermissionError, match="explicit user"):
        access.load_skill("manual-only", project, home=home,
                          bundled_skills_dir=bundled)
    loaded = access.load_skill("manual-only", project, home=home,
                               bundled_skills_dir=bundled, explicit_user=True)
    assert loaded["body"] == "MANUAL SECRET BODY"


def test_cli_smoke_and_structured_unknown_command(tmp_path: Path):
    home = tmp_path / "home"
    script = Path(access.__file__)
    env = {key: value for key, value in os.environ.items()
           if key not in {"HOME", "USERPROFILE", "IMAGEJAI_HOME"}}
    proposed = subprocess.run(
        [sys.executable, str(script), "propose", "--home", str(home),
         "--kind", "fact", "--scope", "user", "--title", "CLI", "--content", "candidate"],
        check=False, capture_output=True, text=True, env=env,
    )
    assert proposed.returncode == 0
    payload = json.loads(proposed.stdout)
    assert payload["ok"] is True and payload["candidate"]["status"] == "candidate"

    context = subprocess.run(
        [sys.executable, str(script), "context", "--home", str(home),
         "--query", "CLI", "--state", "{}", "--model-profile", "{}"],
        check=False, capture_output=True, text=True, env=env,
    )
    assert context.returncode == 0
    assert "candidate" not in json.loads(context.stdout)["context"]

    bad = subprocess.run([sys.executable, str(script), "promote"], check=False,
                         capture_output=True, text=True, env=env)
    assert bad.returncode == 2
    error = json.loads(bad.stderr)
    assert error["ok"] is False and error["error"]["type"] == "ArgumentError"


def test_pseudonym_tokens_are_not_rejected_as_sample_names(tmp_path):
    home = tmp_path / "home"
    proposed = access.propose_candidate(
        "constraint", "user", "Use selected token",
        "Analyse image-deadbeef.tif without revealing its source name", home=home)
    admin = HarnessStore(home / "harness" / "harness_state.json",
                         home / "harness" / "refinements.jsonl")
    entry = admin.get(proposed["id"])
    admin.promote(entry.id, expected_version=entry.version, reviewer="user",
                  evidence="explicit review")
    text = access.reviewed_context("selected token", {}, {"reliability": "low"}, home=home)
    assert "image-deadbeef.tif" in text
