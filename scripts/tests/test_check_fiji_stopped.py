"""Process-based protection even when Windows does not lock an open JAR."""
from pathlib import Path
import subprocess
from unittest.mock import patch

import pytest

from scripts.check_fiji_stopped import main, running_in


def test_native_fiji_is_detected_without_readable_command_line(tmp_path):
    root = tmp_path / "Fiji.app"
    assert running_in(root, [{"ProcessId": 42, "Name": "ImageJ-win64.exe",
                             "ExecutablePath": str(root / "ImageJ-win64.exe")}])


def test_java_launcher_matches_a_quoted_fiji_path(tmp_path):
    root = tmp_path / "Folder with spaces" / "Fiji.app"
    assert running_in(root, [{"ProcessId": 42, "Name": "java.exe",
                             "CommandLine": f'java -cp "{root}/jars/ij.jar" ij.ImageJ'}])


def test_different_installation_with_same_path_prefix_is_not_target(tmp_path):
    root = tmp_path / "Fiji.app"
    assert not running_in(root, [{"ProcessId": 42, "Name": "java.exe",
                                 "CommandLine": f'java -cp "{root}-other/jars/ij.jar" ij.ImageJ'}])


def test_unreadable_java_process_does_not_count_as_stopped(tmp_path):
    with pytest.raises(RuntimeError, match="Cannot inspect"):
        running_in(tmp_path, [{"ProcessId": 42, "Name": "java.exe"}])


def test_empty_snapshot_allows_installation(tmp_path):
    assert not running_in(tmp_path, [])


def test_relative_imagej_classpath_without_working_directory_is_not_safe(tmp_path):
    with pytest.raises(RuntimeError, match="Cannot locate"):
        running_in(tmp_path, [{"ProcessId": 42, "Name": "java",
                              "CommandLine": "java -jar ij.jar"}])


def test_process_enumeration_failure_refuses_installation(tmp_path, capsys):
    with patch("sys.argv", ["guard", "--fiji-dir", str(tmp_path)]), \
            patch("scripts.check_fiji_stopped.process_snapshot",
                  side_effect=subprocess.TimeoutExpired("powershell", 15)):
        assert main() == 2
    assert "no JAR may be replaced" in capsys.readouterr().out
