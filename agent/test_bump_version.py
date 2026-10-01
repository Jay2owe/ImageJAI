import importlib.util
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


def _bump_module():
    spec = importlib.util.spec_from_file_location("bump_version", ROOT / "scripts" / "bump_version.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _fake_repo(root: Path) -> None:
    (root / "agent").mkdir()
    (root / "docs").mkdir()
    (root / "pom.xml").write_text(
        "<parent><artifactId>pom-scijava</artifactId><version>40.0.0</version></parent>\n"
        "<artifactId>imagej-ai</artifactId>\n    <version>1.2.3</version>\n", encoding="utf-8")
    (root / "agent" / "command_manifest.json").write_text(
        '{\n  "product_version": "1.2.3"\n}\n', encoding="utf-8")
    (root / "CITATION.cff").write_text(
        'version: "1.2.3"\ndate-released: 2026-01-01\n', encoding="utf-8")
    for name in ("README.md", "docs/DEVELOPER.md", "docs/USER_GUIDE.md"):
        (root / name).write_text("Copy `imagej-ai-1.2.3.jar` (Java 8).\n", encoding="utf-8")


def test_bump_moves_every_versioned_file_and_leaves_the_parent_alone(tmp_path):
    _fake_repo(tmp_path)
    changes = _bump_module().bump(tmp_path, "1.3.0", "2026-10-01")

    assert changes[0] == "1.2.3 -> 1.3.0"
    pom = (tmp_path / "pom.xml").read_text(encoding="utf-8")
    assert "<version>1.3.0</version>" in pom and "<version>40.0.0</version>" in pom
    assert '"product_version": "1.3.0"' in (tmp_path / "agent" / "command_manifest.json").read_text(encoding="utf-8")
    citation = (tmp_path / "CITATION.cff").read_text(encoding="utf-8")
    assert 'version: "1.3.0"' in citation and "date-released: 2026-10-01" in citation
    for name in ("README.md", "docs/DEVELOPER.md", "docs/USER_GUIDE.md"):
        assert "imagej-ai-1.3.0.jar" in (tmp_path / name).read_text(encoding="utf-8")


def test_bump_rejects_a_malformed_version_without_touching_files(tmp_path):
    _fake_repo(tmp_path)
    with pytest.raises(SystemExit):
        _bump_module().bump(tmp_path, "1.3", "2026-10-01")
    assert "1.2.3" in (tmp_path / "pom.xml").read_text(encoding="utf-8")


def test_real_repo_versions_agree_with_pom():
    module = _bump_module()
    version = module.current_version(ROOT)
    assert f'"product_version": "{version}"' in (ROOT / "agent" / "command_manifest.json").read_text(encoding="utf-8")
