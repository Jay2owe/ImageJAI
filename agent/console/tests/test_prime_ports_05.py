from pathlib import Path
import pytest
from agent.console.project_context import load_project_instructions, protected_text

def test_precedence_dedup_reload_and_fallback(tmp_path):
    broad = tmp_path / "workspace"
    broad.mkdir()
    project = tmp_path / "images"
    project.mkdir()
    (broad / "AGENTS.md").write_text("Use calibrated units.")
    (project / "CLAUDE.md").write_text("Use channel 2.")
    (project / "IMAGEJAI.md").write_text("Export CSV.")
    result = load_project_instructions(project, broad)
    assert result.text.index("calibrated") < result.text.index("channel 2") < result.text.index("Export CSV")
    assert len(result.sources) == 3
    assert str(tmp_path) not in result.text
    (project / "IMAGEJAI.md").write_text("Export TIFF.")
    assert "Export TIFF" in load_project_instructions(project, broad).text
    assert len(load_project_instructions(project, project).sources) == 2

def test_bounds_and_private_paths_withheld(tmp_path):
    (tmp_path / "AGENTS.md").write_text("x" * 65537)
    (tmp_path / "IMAGEJAI.md").write_text("Sample at C:\\patients\\patient01.tif")
    result = load_project_instructions(tmp_path, sanitizer=lambda text:text)
    assert not result.text and len(result.warnings) == 2
    with pytest.raises(ValueError): protected_text("/home/user/patient.tif", lambda text:text)
    assert protected_text("C:\\private\\sample.tif", lambda text:text.replace("C:\\private\\sample.tif", "TOKEN123")) == "TOKEN123"

def test_nested_repository_reads_root_and_leaf_with_missing_middle(tmp_path):
    (tmp_path / ".git").mkdir()
    leaf = tmp_path / "analysis" / "images"
    leaf.mkdir(parents=True)
    (tmp_path / "AGENTS.md").write_text("Root units convention")
    (leaf / "IMAGEJAI.md").write_text("Leaf channel convention")
    result = load_project_instructions(leaf)
    assert result.text.index("Root units") < result.text.index("Leaf channel")
