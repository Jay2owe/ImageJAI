"""Refuse plugin replacement while the target Fiji may still be using its JARs."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def process_snapshot() -> list[dict]:
    if os.name == "nt":
        command = [
            "powershell", "-NoProfile", "-NonInteractive", "-Command",
            "$ErrorActionPreference='Stop'; "
            "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR "
            "Name='javaw.exe' OR Name LIKE 'ImageJ%.exe' OR Name LIKE 'fiji%.exe'\" "
            "| Select-Object ProcessId,Name,ExecutablePath,CommandLine) "
            "| ConvertTo-Json -Compress",
        ]
        result = subprocess.run(command, capture_output=True, text=True,
                                errors="replace", timeout=15, check=True)
        data = json.loads(result.stdout) if result.stdout.strip() else []
        if data is None:
            return []
        if not isinstance(data, (dict, list)):
            raise ValueError("Unexpected process snapshot format")
        return [data] if isinstance(data, dict) else data
    result = subprocess.run(["ps", "-axo", "pid=,comm=,args="],
                            capture_output=True, text=True, timeout=10, check=True)
    rows = []
    for line in result.stdout.splitlines():
        parts = line.strip().split(None, 2)
        if len(parts) < 2:
            continue
        pid, executable = parts[:2]
        arguments = parts[2] if len(parts) > 2 else ""
        name = Path(executable).name.lower()
        if name != "java" and not name.startswith(("imagej", "fiji")):
            continue
        cwd = ""
        try:
            cwd = str(Path(f"/proc/{pid}/cwd").resolve(strict=True))
        except OSError:
            pass
        rows.append({"ProcessId": pid, "Name": name,
                     "ExecutablePath": executable, "CommandLine": arguments,
                     "WorkingDirectory": cwd})
    return rows


def running_in(root: Path, processes: list[dict]) -> list[str]:
    def normalise(value: str) -> str:
        value = value.replace("\\", "/").rstrip("/")
        return value.casefold() if os.name == "nt" else value

    roots = {normalise(str(root.absolute())), normalise(str(root.resolve()))}
    markers = [re.compile(re.escape(value) + r"(?=$|[/\s\"'])") for value in roots]
    running = []
    for process in processes:
        pid = process.get("ProcessId", "unknown")
        name = str(process.get("Name") or "java")
        executable = str(process.get("ExecutablePath") or "")
        command = str(process.get("CommandLine") or "")
        cwd = str(process.get("WorkingDirectory") or "")
        if any(pattern.search(normalise(value))
               for pattern in markers for value in (executable, command, cwd)):
            running.append(f"{name} (PID {pid})")
        elif not command:
            raise RuntimeError(f"Cannot inspect the command line of {name} (PID {pid}).")
        elif not cwd and re.search(r"(?:ij\.jar|ij\.ImageJ|fiji\.Main)", command, re.I):
            # Java can launch with a relative classpath. Without its working
            # directory, absence of the target path does not prove a different Fiji.
            if not re.search(r"(?:^|[\s\"=])(?:[A-Za-z]:[/\\]|/)[^\"\n]*ij\.jar", command, re.I):
                raise RuntimeError(f"Cannot locate ImageJ's Java process (PID {pid}).")
        elif name.lower().startswith(("fiji", "imagej")) and not Path(executable).is_absolute():
            # A relative native launcher without a readable working directory
            # cannot establish that this is a different installation.
            if not cwd:
                raise RuntimeError(f"Cannot locate {name} (PID {pid}).")
    return running


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fiji-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        running = running_in(args.fiji_dir, process_snapshot())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
        print(f"Cannot verify Fiji is stopped; no JAR may be replaced. {exc}")
        return 2
    if running:
        print("Fiji is still running: " + ", ".join(running) +
              ". Close it before installing ImageJAI; replacing a live JAR breaks class loading.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
