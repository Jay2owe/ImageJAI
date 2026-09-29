"""Public-release hygiene gate.

ImageJAI is distributed publicly. Two artifacts leave this repository:

1. the lab/agent ZIP built by ``scripts/make_lab_bundle.ps1`` (allowlist only);
2. the ``imagejai-console`` wheel built from the root ``pyproject.toml``.

This module fails if private material of one site can reach either artifact:
personal absolute filesystem paths, the maintainer's private sync-folder
layout, the owning laboratory's folder identity, or one lab's learned facts.

Published paper citations are NOT private data. Reference documents under
``agent/references/`` cite work such as "Brancaccio et al. 2019 Neuron", and
those citations must keep shipping. The scanner therefore matches a private
*folder* identity ("<lab name> Lab", the institute's sync-folder name) and
private *paths*, never author surnames in citation form.

Sources of truth (never duplicate the lists here):

* ``scripts/lab_bundle_allowlist.psd1`` - what the bundle ships.
* the ``PRIVATE_AGENT_PATHS`` block in ``scripts/make_lab_bundle.ps1`` - what is
  explicitly excluded from any bundle.
* ``[tool.setuptools]`` in ``pyproject.toml`` - what the wheel ships.
"""

from __future__ import annotations

import fnmatch
import re
import tomllib
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
AGENT_ROOT = REPO_ROOT / "agent"
BUNDLER = REPO_ROOT / "scripts" / "make_lab_bundle.ps1"
ALLOWLIST = REPO_ROOT / "scripts" / "lab_bundle_allowlist.psd1"
PYPROJECT = REPO_ROOT / "pyproject.toml"

# This file quotes marker patterns, so scanning it would report itself.
SELF = "agent/test_public_release_hygiene.py"

# Author attribution is deliberate and public. A LICENSE copyright line and a
# citation file are meant to name the author; they are not private data.
ATTRIBUTION_FILES = {"LICENSE", "CITATION.cff"}

TEXT_SUFFIXES = {
    ".py", ".md", ".txt", ".toml", ".yaml", ".yml", ".json", ".groovy", ".ijm",
    ".ps1", ".cfg", ".ini", ".clinerules", ".cursorrules", "",
}

# User folder names that are placeholders in documentation and test fixtures.
_SAFE_USER_SEGMENT = re.compile(
    r"^(?:\.{3}|me|example|examples|user|username|youruser|somebody|someone|"
    r"public|data|<[^>]+>|%[^%]+%|\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_]*)$",
    re.IGNORECASE,
)

_PRIVATE_HOST_PATH = re.compile(r"[A-Za-z]:[\\/]Users[\\/]([^\\/\s\"'`,)\]]+)")
# A POSIX home path needs a real following segment; this keeps regex literals
# such as "/Users/|^[A-Za-z" in test sources from reading as a host path.
_PRIVATE_POSIX_HOME = re.compile(
    r"(?<![\w.\-/\\|\[])/(?:home|Users)/([A-Za-z0-9._\-]+)(?=[/\s\"'`,)\]]|$)"
)
_PRIVATE_SYNC_PATH = re.compile(r"[A-Za-z]:[\\/][^\r\n\"']*Dropbox[\\/]")

# Private folder identities. "Brancaccio Lab" is a directory name on the
# maintainer's machine; "Brancaccio et al." in a reference is a citation and is
# not matched here.
_PRIVATE_IDENTITY = re.compile(
    r"UK Dementia Research Institute|[A-Z][a-z]+ Lab(?:Admin)? Dropbox|"
    r"Brancaccio Lab",
)


def _iter_private_markers(text: str):
    """Yield (kind, evidence) for every private marker found in ``text``."""
    for match in _PRIVATE_HOST_PATH.finditer(text):
        if not _SAFE_USER_SEGMENT.match(match.group(1)):
            yield "private-host-path", match.group(0)
    for match in _PRIVATE_POSIX_HOME.finditer(text):
        if not _SAFE_USER_SEGMENT.match(match.group(1)):
            yield "private-host-path", match.group(0)
    for match in _PRIVATE_SYNC_PATH.finditer(text):
        yield "private-sync-path", match.group(0)
    for match in _PRIVATE_IDENTITY.finditer(text):
        yield "private-folder-identity", match.group(0)


def find_private_markers(text: str) -> list[str]:
    return [f"{kind}: {evidence}" for kind, evidence in _iter_private_markers(text)]


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="ignore")


def scan_paths(paths) -> dict[str, list[str]]:
    findings: dict[str, list[str]] = {}
    for path in sorted(set(paths)):
        relative = path.relative_to(REPO_ROOT).as_posix()
        if relative == SELF or path.name in ATTRIBUTION_FILES:
            continue
        if path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        if not path.is_file() or path.stat().st_size > 8 * 1024 * 1024:
            continue
        markers = find_private_markers(read_text(path))
        if markers:
            findings[relative] = markers
    return findings


def private_agent_paths() -> list[str]:
    """Exclusion list owned by the bundler, parsed as the source of truth."""
    text = read_text(BUNDLER)
    block = re.search(
        r"# BEGIN PRIVATE_AGENT_PATHS(.*?)# END PRIVATE_AGENT_PATHS",
        text,
        re.S,
    )
    assert block is not None, "make_lab_bundle.ps1 lost its PRIVATE_AGENT_PATHS block"
    entries = re.findall(r"^\s*'([^']+)'\s*$", block.group(1), re.M)
    assert entries, "PRIVATE_AGENT_PATHS block is empty"
    return entries


def is_private_agent_path(relative: str) -> bool:
    normal = relative.replace("\\", "/").lower()
    for entry in private_agent_paths():
        pattern = entry.replace("\\", "/").lower()
        if pattern.endswith("/"):
            if normal == pattern.rstrip("/") or normal.startswith(pattern):
                return True
        elif normal == pattern:
            return True
    return False


def allowlisted_agent_files() -> list[str]:
    entries = re.findall(r"^\s*'([^']+)'\s*$", read_text(ALLOWLIST), re.M)
    assert len(entries) > 50, "bundle allowlist looks truncated"
    return entries


def _wheel_config() -> dict:
    return tomllib.loads(read_text(PYPROJECT))["tool"]["setuptools"]


def wheel_package_dirs() -> dict[str, Path]:
    """Directories setuptools discovers, mirroring find_packages pruning."""
    config = _wheel_config()
    find = config.get("packages", {}).get("find", {})
    includes = find.get("include", ["*"])
    excludes = find.get("exclude", [])
    packages: dict[str, Path] = {}

    def walk(directory: Path, dotted: str) -> None:
        packages[dotted] = directory
        for child in sorted(directory.iterdir()):
            if child.is_dir() and (child / "__init__.py").is_file():
                walk(child, f"{dotted}.{child.name}")

    if (AGENT_ROOT / "__init__.py").is_file():
        walk(AGENT_ROOT, "agent")
    selected = {
        name: path
        for name, path in packages.items()
        if any(fnmatch.fnmatch(name, pattern) for pattern in includes)
        and not any(fnmatch.fnmatch(name, pattern) for pattern in excludes)
    }
    return selected


def wheel_shipped_files() -> list[Path]:
    """Every file the wheel carries: package modules plus declared data."""
    packages = wheel_package_dirs()
    package_data = _wheel_config().get("package-data", {})
    shipped: list[Path] = []
    for name, directory in packages.items():
        shipped.extend(sorted(p for p in directory.glob("*.py") if p.is_file()))
        for pattern in package_data.get(name, []):
            shipped.extend(sorted(p for p in directory.glob(pattern) if p.is_file()))
    return shipped


# --------------------------------------------------------------------------
# Scanner self-checks: the gate is worthless if it cannot detect a real leak.
# --------------------------------------------------------------------------

def test_scanner_detects_a_private_host_path():
    planted = "open(r'" + "C:\\Users\\" + "Owner\\notes.txt')"
    assert find_private_markers(planted)


def test_scanner_detects_a_private_sync_folder_path():
    planted = "D:/" + "Some Institute " + "Dropbox/Some Lab/data.tif"
    assert find_private_markers(planted)


def test_scanner_ignores_placeholder_paths():
    for placeholder in (
        "C:/Users/example/data.tif",
        "C:\\Users\\me\\image.czi",
        "C:/Users/.../AI_Exports",
        "/home/user/data",
        "%USERPROFILE%/ImageJAI",
    ):
        assert not find_private_markers(placeholder), placeholder


def test_scanner_keeps_paper_citations():
    """Published citations are science and must never be flagged."""
    citations = [
        "Brancaccio et al. 2019, Neuron 93(6):1420-1435.",
        "See Brancaccio M, Edwards MD, Patton AP et al. (2019) Science.",
        "Schindelin et al. 2012 Nat Methods (Fiji).",
    ]
    for citation in citations:
        assert not find_private_markers(citation), citation


# --------------------------------------------------------------------------
# The two shipped artifacts.
# --------------------------------------------------------------------------

def test_bundle_allowlist_excludes_private_paths():
    offenders = [f for f in allowlisted_agent_files() if is_private_agent_path(f)]
    assert not offenders, f"bundle allowlist entries are private: {offenders}"


def test_bundled_agent_files_carry_no_private_markers():
    paths = [AGENT_ROOT / relative for relative in allowlisted_agent_files()]
    missing = [p for p in paths if not p.is_file()]
    assert not missing, f"allowlisted files are missing: {missing[:5]}"
    findings = scan_paths(paths)
    assert not findings, f"private data in bundled files: {findings}"


def test_wheel_files_carry_no_private_markers():
    shipped = wheel_shipped_files()
    assert len(shipped) > 100, "wheel file discovery collapsed"
    findings = scan_paths(shipped)
    assert not findings, f"private data in wheel files: {findings}"


def test_reference_documents_ship_with_their_citations():
    references = sorted((AGENT_ROOT / "references").glob("*-reference.md"))
    assert len(references) > 20, "reference library did not resolve"
    shipped = {p.as_posix() for p in references}
    allowlisted = {
        (AGENT_ROOT / f).as_posix()
        for f in allowlisted_agent_files()
        if f.startswith("references/")
    }
    assert allowlisted & shipped, "reference documents stopped shipping"
    assert not scan_paths(references)


def test_known_private_material_never_ships():
    known_private = [
        "learnings.md",
        "to-learn.md",
        "scripts/build_montages.py",
        "scripts/scatter_density.groovy",
        ".claude/settings.local.json",
        ".tmp/anything.json",
        "AI_Exports/friction.log",
        "work_in_progress/amyloid_pixel_identification/hybrid/hybrid_gate.py",
    ]
    allowlisted = set(allowlisted_agent_files())
    wheel_relatives = {
        p.relative_to(REPO_ROOT).as_posix() for p in wheel_shipped_files()
    }
    for relative in known_private:
        assert is_private_agent_path(relative), f"{relative} lost its exclusion"
        assert relative not in allowlisted, f"{relative} entered the bundle allowlist"
        assert f"agent/{relative}" not in wheel_relatives, f"{relative} entered the wheel"


def test_release_scripts_hold_no_private_defaults():
    """The bundler and its allowlist ship in the public repository."""
    findings = scan_paths([BUNDLER, ALLOWLIST, PYPROJECT])
    assert not findings, f"private data in release tooling: {findings}"
