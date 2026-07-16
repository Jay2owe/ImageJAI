from __future__ import annotations

import pytest

from agent.gemma4_31b import safety


@pytest.mark.parametrize(
    "code",
    [
        'text = File.openAsString("/private/subjects.csv");',
        'raw = File.openAsRawString("/private/image.bin", 64);',
        'text = File.openUrlAsString("https://example.invalid/data");',
        'File.openSequence("/private/images", "virtual");',
        'path = File.openDialog("Choose private data");',
        'open("/private/subject.tif");',
        'openVirtual("/private/subject.tif");',
        'names = getFileList("/private");',
        'home = getDirectory("home");',
        'size = File.length("/private/subject.tif");',
        'size = File.getLength("/private/subject.tif");',
        'present = File.exists("/private/subject.tif");',
        'cwd = File.getAbsolutePath(".");',
        'defaultDir = File.getDefaultDir();',
        'lastDir = File.directory;',
        'lastName = File.name;',
    ],
)
def test_filesystem_reads_and_enumeration_are_rejected(code: str) -> None:
    error = safety.check_filesystem(code, "/safe/AI_Exports")
    assert error is not None
    assert "filesystem primitive" in error


@pytest.mark.parametrize(
    "code",
    [
        'File.delete("/private/subject.tif");',
        'File.rename("/private/a.tif", "/private/b.tif");',
        'File.copy("/private/a.tif", "/safe/AI_Exports/a.tif");',
        'File.setDefaultDir("/private");',
        'run("Save");',
        'run("Revert");',
        'run("Open...");',
        'run("Bio-Formats Importer");',
    ],
)
def test_destructive_and_import_primitives_are_rejected(code: str) -> None:
    assert safety.check_filesystem(code, "/safe/AI_Exports") is not None


def test_strings_comments_and_pure_path_helpers_are_ignored() -> None:
    code = r'''
        // File.delete("/private/a.tif");
        /* text = File.openAsString("/private/subjects.csv"); */
        print("File.copy(a, b) and open('/private') are documentation");
        name = File.getName("/private/subject.tif");
        parent = File.getParent("/private/subject.tif");
        separator = File.separator;
        run("Gaussian Blur...", "sigma=2");
    '''
    assert safety.check_filesystem(code, "/safe/AI_Exports") is None


def test_unknown_file_primitive_fails_closed() -> None:
    error = safety.check_filesystem(
        'File.futureFilesystemMethod("/private/subject.tif");',
        "/safe/AI_Exports",
    )
    assert error is not None
    assert "unrecognised File.*" in error

    assert safety.check_filesystem(
        'File.write("data", handle);', "/safe/AI_Exports"
    ) is not None


def test_literal_output_writes_under_resolved_ai_exports_are_allowed(tmp_path) -> None:
    exports = tmp_path / "AI_Exports"
    exports.mkdir()
    output = (exports / "nested" / "result.csv").as_posix()
    directory = (exports / "nested").as_posix()
    code = f'''
        File.makeDirectory("{directory}");
        File.mkdir("{directory}");
        File.saveString("header", "{output}");
        File.append("row", "{output}");
        f = File.open("{(exports / "log.txt").as_posix()}");
        print(f, "ok"); File.close(f);
        saveAs("Results", "{output}");
        IJ.saveAs("Tiff", "{(exports / "image.tif").as_posix()}");
    '''
    assert safety.check_filesystem(code, str(exports)) is None


@pytest.mark.parametrize(
    "code",
    [
        'saveAs("Tiff", outputPath);',
        'File.saveString("data", exportDir + "/result.csv");',
        'File.open(path);',
        'File.makeDirectory(directory);',
        'run("Tiff...", "save=" + outputPath);',
    ],
)
def test_dynamic_output_paths_fail_closed(code: str, tmp_path) -> None:
    exports = tmp_path / "AI_Exports"
    exports.mkdir()
    error = safety.check_filesystem(code, str(exports))
    assert error is not None


def test_literal_output_outside_or_traversing_ai_exports_is_rejected(tmp_path) -> None:
    exports = tmp_path / "AI_Exports"
    exports.mkdir()
    outside = (exports / ".." / "subject.tif").as_posix()
    error = safety.check_filesystem(
        f'saveAs("Tiff", "{outside}");', str(exports)
    )
    assert error is not None
    assert "outside the current AI_Exports" in error


def test_literal_run_save_path_obeys_ai_exports_policy(tmp_path) -> None:
    exports = tmp_path / "AI_Exports"
    exports.mkdir()
    inside = (exports / "result.tif").as_posix()
    outside = (tmp_path / "subject.tif").as_posix()
    assert safety.check_filesystem(
        f'run("Tiff...", "save=[{inside}]");', str(exports)
    ) is None
    assert safety.check_filesystem(
        f'run("Tiff...", "save=[{outside}]");', str(exports)
    ) is not None
