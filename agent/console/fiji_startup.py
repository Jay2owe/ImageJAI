"""Find a Fiji installation and ask its resident plugin to open TCP.

The request file is deliberately scoped to one Fiji installation. A Fiji that
is already open can consume it without opening the embedded assistant panel;
when Fiji is closed the console launches the selected installation first.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import time
import uuid
from pathlib import Path
from typing import Callable

from . import config as console_config
from .workspace import find_workspace

REQUEST_NAME = "tcp-start-request.properties"
REQUEST_MAX_AGE_S = 120
# Single quotes only: the macro must survive Windows argument quoting intact.
JAVA8_WATCHER_MACRO = "call('imagejai.engine.automation.ConsoleBootstrapService.startWatcher');"
_ROOT_NAMES = ("Fiji.app", "Fiji")
_EXECUTABLES = (
    "ImageJ-win64.exe", "fiji-windows-x64.exe", "fiji-win64.exe",
    "ImageJ-win32.exe", "ImageJ.exe", "fiji.bat", "ImageJ-linux64",
    "ImageJ-linux32", "ImageJ-macosx", "ImageJ-macosx64",
)


def fiji_root(value: str | Path | None) -> Path | None:
    """Return a verified Fiji root from a directory or launcher path."""
    if not value:
        return None
    path = Path(value).expanduser().resolve()
    root = path.parent if path.is_file() else path
    if root.name == "MacOS" and root.parent.name == "Contents":
        root = root.parent.parent
    return root if root.is_dir() and launcher_for(root) is not None else None


def launcher_for(root: Path) -> Path | None:
    """Use a custom launcher only when it names this Fiji installation."""
    if not root.is_dir():
        return None
    if os.name == "nt":
        custom = sorted(root.glob("Launch Fiji*.bat"))
        for script in custom:
            try:
                body = script.read_text(encoding="utf-8", errors="replace").casefold()
            except OSError:
                continue
            # Copied Fiji folders can retain a batch file with an absolute
            # path to the original installation. Never launch that by mistake.
            if (str(root).casefold() + os.sep) in body or "%~dp0" in body:
                return script
    for name in _EXECUTABLES:
        candidate = root / name
        if candidate.is_file():
            return candidate
    for name in ("ImageJ-macosx", "ImageJ-macosx64"):
        candidate = root / "Contents" / "MacOS" / name
        if candidate.is_file():
            return candidate
    return None


def candidates(saved: str | None = None) -> list[Path]:
    """Check explicit choices, nearby installs, PATH, and conventional homes."""
    possible: list[Path] = []
    for raw in (saved, os.getenv("IMAGEJAI_FIJI_PATH"), os.getenv("FIJI_HOME"),
                os.getenv("IMAGEJ_HOME")):
        if raw:
            possible.append(Path(raw))
    workspace = find_workspace()
    if workspace is not None:
        for parent in (workspace, *workspace.parents):
            possible.extend(parent / name for name in _ROOT_NAMES)
    possible.extend(running_fiji_roots())
    for parent in (Path.cwd(), Path.home(), Path.home() / "Applications",
                   Path("/Applications"), Path("/opt")):
        possible.extend(parent / name for name in _ROOT_NAMES)
    for name in _EXECUTABLES:
        found = shutil.which(name)
        if found:
            possible.append(Path(found))
    roots: list[Path] = []
    for candidate in possible:
        root = fiji_root(candidate)
        if root is not None and root not in roots:
            roots.append(root)
    return roots


def running_fiji_roots() -> list[Path]:
    """Discover Fiji from its actual executable when a desktop copy is open."""
    if os.name != "nt":
        return []
    command = [
        "powershell", "-NoProfile", "-Command",
        "Get-CimInstance Win32_Process -Filter \"Name='ImageJ-win64.exe' OR "
        "Name='ImageJ-win32.exe' OR Name='fiji-windows-x64.exe' OR "
        "Name='fiji-win64.exe'\" | Select-Object -ExpandProperty ExecutablePath",
    ]
    try:
        result = subprocess.run(command, capture_output=True, text=True,
                                errors="replace", timeout=8, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return []
    if result.returncode != 0:
        return []
    roots: list[Path] = []
    for line in result.stdout.splitlines():
        root = fiji_root(line.strip())
        if root is not None and any(
                part.casefold() == ".imagej-plugin-test-harness"
                for part in root.parts):
            continue
        if root is not None and root not in roots:
            roots.append(root)
    return roots


def request_path() -> Path:
    return console_config.CONFIG_DIR / "console" / REQUEST_NAME


def write_start_request(root: Path, port: int, now: float | None = None) -> Path:
    """Atomically publish a short-lived, installation-specific TCP request."""
    verified = fiji_root(root)
    if verified is None:
        raise ValueError(f"not a Fiji installation: {root}")
    if not 1 <= int(port) <= 65535:
        raise ValueError("TCP port must be between 1 and 65535")
    target = request_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    request_id = uuid.uuid4().hex
    temporary = target.with_name(target.name + f".{request_id}.tmp")
    body = (
        "version=1\n"
        f"request_id={request_id}\n"
        f"target_uri={verified.as_uri()}\n"
        f"port={int(port)}\n"
        f"created_ms={int((time.time() if now is None else now) * 1000)}\n"
    )
    temporary.write_text(body, encoding="utf-8")
    os.replace(temporary, target)
    return target


def _matching_process_running(root: Path) -> bool:
    """Avoid starting a second copy of the chosen Fiji installation."""
    marker = str(root).casefold()
    try:
        if os.name == "nt":
            command = [
                "powershell", "-NoProfile", "-Command",
                "Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR "
                "Name='javaw.exe' OR "
                "Name='ImageJ-win64.exe' OR Name='ImageJ-win32.exe' OR "
                "Name='fiji-windows-x64.exe' OR "
                "Name='fiji-win64.exe'\" | Select-Object -ExpandProperty CommandLine",
            ]
        else:
            command = ["ps", "-eo", "comm=,args="]
        result = subprocess.run(command, capture_output=True, text=True,
                                errors="replace", timeout=8, check=False)
        if result.returncode != 0:
            return True
        for line in result.stdout.splitlines():
            if os.name == "nt":
                if marker in line.casefold():
                    return True
                continue
            executable = line.split(None, 1)[0].casefold() if line.strip() else ""
            if any(name in executable for name in ("java", "imagej", "fiji")):
                if marker in line.casefold():
                    return True
        return False
    except (OSError, subprocess.TimeoutExpired):
        # If process inspection is unavailable, the caller must not guess that
        # Fiji is closed and risk launching a duplicate desktop application.
        return True


def launch_fiji(root: Path) -> subprocess.Popen:
    launcher = launcher_for(root)
    if launcher is None:
        raise ValueError(f"no Fiji launcher found in {root}")
    if os.name == "nt" and launcher.suffix.lower() == ".bat":
        command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", str(launcher)]
    else:
        command = [str(launcher)]
        if os.name == "nt" and launcher.suffix.lower() == ".exe":
            java_home = _bundled_java_home(root)
            if java_home is not None:
                command += ["--java-home", str(java_home), "--default-gc"]
            else:
                # Java 8 Fiji: ImageJ 2.16 there does not start plugin services
                # at boot, so start the plugin's request watcher from IJ1.
                # -port0 keeps ImageJ from handing the macro to another
                # running Fiji instead of starting this one.
                command += ["--", "-port0", "-eval", JAVA8_WATCHER_MACRO]
    flags = 0
    if os.name == "nt":
        flags = (subprocess.CREATE_NEW_PROCESS_GROUP
                 | subprocess.CREATE_NO_WINDOW)
    return subprocess.Popen(command, cwd=root, stdin=subprocess.DEVNULL,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            creationflags=flags)


def _bundled_java_home(root: Path) -> Path | None:
    """Choose a bundled Java 11+ runtime for Fiji's packaged Windows launcher."""
    choices: list[tuple[int, Path]] = []
    for home in (root / "java" / "win64").glob("*"):
        match = re.search(r"(?:jdk|jre)(\d+)", home.name.casefold())
        if match and int(match.group(1)) >= 11 and (home / "bin" / "java.exe").is_file():
            choices.append((int(match.group(1)), home))
    return min(choices, default=None, key=lambda item: item[0])[1] if choices else None


def ensure_fiji(
    root: Path, port: int, probe: Callable[[], Path],
    *, timeout_s: float = 25.0, poll_s: float = 0.5,
    close_startup_error: bool = True,
) -> str:
    """Connect only to the selected Fiji, or launch it and request TCP."""
    verified = fiji_root(root)
    if verified is None:
        raise ValueError(f"not a Fiji installation: {root}")
    def check_target() -> bool:
        try:
            actual = probe().resolve()
        except Exception:
            return False
        if actual != verified:
            raise RuntimeError(
                f"TCP port {port} belongs to a different Fiji installation: "
                f"{actual}. Close that connection or choose its path with /fiji."
            )
        return True

    if check_target():
        return "connected"
    request = write_start_request(verified, port)
    request_created_at = time.monotonic()
    request_id = next((line.split("=", 1)[1] for line in
                       request.read_text(encoding="utf-8").splitlines()
                       if line.startswith("request_id=")), "")
    launched = False
    try:
        if not _matching_process_running(verified):
            launch_fiji(verified)
            launched = True
        deadline = time.monotonic() + timeout_s
        if launched and close_startup_error:
            # Plugin discovery can outlast the connection timeout before its
            # first modal error appears. Keep the watcher alive for our startup
            # request's lifetime; successful startup still returns immediately.
            deadline = max(deadline, request_created_at + REQUEST_MAX_AGE_S - 2.0)
        dialog_closed = False
        while time.monotonic() < deadline:
            try:
                if launched and close_startup_error:
                    from .windows_startup_dialog import dismiss_startup_error
                    if dismiss_startup_error(verified) and not dialog_closed:
                        dialog_closed = True
                        # The modal paused Fiji's own initialization. Keep
                        # polling while the existing request remains valid;
                        # return as soon as Fiji opens the server.
                        deadline = max(
                            deadline, request_created_at + REQUEST_MAX_AGE_S - 2.0)
                if not check_target():
                    time.sleep(poll_s)
                    continue
                return "launched and connected" if launched else "connected to running Fiji"
            except RuntimeError:
                raise
        if dialog_closed:
            raise TimeoutError(
                "Fiji's startup error was closed, but its ImageJAI server did not "
                "open before the startup request expired. Check the Fiji Log "
                "and retry with /fiji start.")
        raise TimeoutError(
            "Fiji did not open the ImageJAI server. Check that this installation "
            "contains the current ImageJAI plugin and has no blocking startup "
            "dialog, then use /fiji start to retry. A Fiji running on Java 8 "
            "may not start the plugin until it is used: open Plugins > AI "
            "Assistant once, then use /fiji start.")
    finally:
        # Only remove our own request. A newer console may have replaced it.
        try:
            text = request.read_text(encoding="utf-8")
            if f"request_id={request_id}\n" in text:
                request.unlink(missing_ok=True)
        except OSError:
            pass
