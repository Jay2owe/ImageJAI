"""Focused behavioral checks for build.sh's local install transaction."""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BUILD_SH = ROOT / "build.sh"


def usable_bash() -> str | None:
    candidates = [shutil.which("bash")]
    if os.name == "nt":
        program_files = Path(os.environ.get("ProgramFiles", r"C:\Program Files"))
        candidates.extend(
            [
                str(program_files / "Git" / "bin" / "bash.exe"),
                str(program_files / "Git" / "usr" / "bin" / "bash.exe"),
            ]
        )
    for candidate in candidates:
        if not candidate or not Path(candidate).is_file():
            continue
        probe = subprocess.run(
            [candidate, "--version"], capture_output=True, text=True, timeout=5
        )
        if probe.returncode == 0 and "bash" in probe.stdout.lower():
            return candidate
    return None


class BuildScriptInstallTest(unittest.TestCase):
    def test_static_build_contract(self) -> None:
        script = BUILD_SH.read_text(encoding="utf-8")
        self.assertIn("--skip-tests", script)
        self.assertIn("--no-deploy", script)
        self.assertNotIn("maven.test.skip", script)
        self.assertNotIn("2>/dev/null", script)
        self.assertIn('jar_file="target/$jar_name"', script)

    @unittest.skipUnless(usable_bash(), "a working Bash runtime is unavailable")
    def test_hash_failure_preserves_old_jar(self) -> None:
        bash = usable_bash()
        assert bash is not None
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            plugins = work / "plugins"
            plugins.mkdir()
            source = work / "imagej-ai-0.3.0.jar"
            installed = plugins / source.name
            source.write_bytes(b"new verified artifact")
            installed.write_bytes(b"old installed artifact")
            expected = hashlib.sha256(source.read_bytes()).hexdigest()
            command = f'''
source "{BUILD_SH.as_posix()}"
cp() {{ command cp "$@"; printf corrupt >> "${{@: -1}}"; }}
if install_verified_artifact "{source.as_posix()}" "{plugins.as_posix()}" \
        "{source.name}" "{expected}"; then
    exit 90
fi
'''
            result = subprocess.run([bash, "-c", command], capture_output=True, text=True)
            self.assertNotEqual(90, result.returncode, result.stderr)
            self.assertEqual(b"old installed artifact", installed.read_bytes())

    @unittest.skipUnless(usable_bash(), "a working Bash runtime is unavailable")
    def test_verified_install_replaces_then_removes_only_stale_imagejai_jar(self) -> None:
        bash = usable_bash()
        assert bash is not None
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            plugins = work / "plugins"
            plugins.mkdir()
            source = work / "imagej-ai-0.3.0.jar"
            installed = plugins / source.name
            stale = plugins / "imagej-ai-0.2.0.jar"
            unrelated = plugins / "another-plugin.jar"
            source.write_bytes(b"new verified artifact")
            installed.write_bytes(b"old installed artifact")
            stale.write_bytes(b"stale")
            unrelated.write_bytes(b"unrelated")
            expected = hashlib.sha256(source.read_bytes()).hexdigest()
            command = f'''
source "{BUILD_SH.as_posix()}"
install_verified_artifact "{source.as_posix()}" "{plugins.as_posix()}" \
    "{source.name}" "{expected}"
'''
            subprocess.run([bash, "-c", command], check=True)
            self.assertEqual(source.read_bytes(), installed.read_bytes())
            self.assertFalse(stale.exists())
            self.assertEqual(b"unrelated", unrelated.read_bytes())


if __name__ == "__main__":
    unittest.main()
