from pathlib import Path

import pytest

import generate_reference_index as reference_index
from contexts import loader


ROOT = Path(__file__).resolve().parents[1]


def test_reference_index_is_current_and_byte_stable():
    before = (ROOT / "agent" / "references" / "INDEX.md").read_bytes()
    assert reference_index.generate(check=True) is False
    assert reference_index.render().encode("utf-8") == before
    assert before.startswith(b"# Reference Documents Index\n")
    assert b"60 reference documents" in before


def test_reference_generation_rejects_oversized_inputs(monkeypatch, tmp_path):
    refs = tmp_path / "references"
    refs.mkdir()
    (refs / "large.md").write_text("# Large\n" + "x" * 64, encoding="utf-8")
    monkeypatch.setattr(reference_index, "MAX_REFERENCE_BYTES", 32)
    with pytest.raises(ValueError, match="exceeds"):
        reference_index.render(refs)


def test_reference_generation_stops_at_the_file_count_bound(monkeypatch, tmp_path):
    refs = tmp_path / "references"
    refs.mkdir()
    for index in range(4):
        (refs / f"{index}.md").write_text("# Reference\n", encoding="utf-8")
    monkeypatch.setattr(reference_index, "MAX_REFERENCE_FILES", 3)
    with pytest.raises(ValueError, match="file limit"):
        reference_index.render(refs)


def test_context_loader_rejects_oversized_overlay(monkeypatch, tmp_path):
    overlay = tmp_path / "large.md"
    overlay.write_text("x" * 64, encoding="utf-8")
    monkeypatch.setattr(loader, "MAX_CONTEXT_FILE_BYTES", 32)
    with pytest.raises(ValueError, match="exceeds"):
        loader._read(overlay)


def test_context_registry_has_a_deterministic_model_bound():
    registry = loader.load_registry()
    assert len(registry) <= loader.MAX_REGISTRY_MODELS


def test_user_release_documents_agree_on_artifact_version_and_runtime():
    paths = [ROOT / "README.md", ROOT / "docs" / "USER_GUIDE.md",
             ROOT / "docs" / "DEVELOPER.md"]
    for path in paths:
        text = path.read_text(encoding="utf-8")
        assert "imagej-ai-0.3.0.jar" in text
        assert "Java 11" in text
        assert "imagej-ai-0.2.0.jar" not in text
        assert "Java 8+" not in text
