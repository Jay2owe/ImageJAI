# Agent Helper API Drift
**Date**: 2026-05-27
**Files changed**: `agent/ij.py`, `agent/pixels.py`, `agent/probe_plugin.py`, `agent/auditor.py`
**Guard**: guard comments in `agent/ij.py`, `agent/pixels.py`, `agent/probe_plugin.py`, `agent/auditor.py`; tests in `agent/test_ij_api.py`, `agent/test_pixels_api.py`, `agent/test_probe_plugin_api.py`, `agent/test_auditor_api.py`

## What went wrong
Agent helper scripts grew by adding new CLI branches or raw TCP calls without always adding matching importable Python helpers. The user-visible symptom was that an agent could run a command such as `python ij.py script ...`, but `from ij import run_script` did not exist. The same drift risk existed in `pixels.py`, `probe_plugin.py`, and `auditor.py`, where CLI workflows were easier to discover than the reusable API.

## The broken pattern
```python
elif cmd == "script":
    resp = imagej_command({"command": "run_script", ...})  # CLI-only path

# No matching run_script() helper in __all__, no payload test, docs drift.
```

## The fix
Each stable agent-facing workflow now has an importable helper, appears in `__all__`, and the CLI routes through that helper. Focused no-Fiji tests verify payloads, cache behavior, compatibility aliases, and CLI routing.

```python
def run_script(code, language="groovy", timeout=180):
    return imagej_command({"command": "run_script", "language": language, "code": code}, timeout=timeout)

elif cmd == "script":
    resp = run_script(code, language=language)
```

## Why it matters
Agents with different capabilities use these scripts differently: weaker agents copy CLI commands, while stronger agents import helpers and compose workflows. If future extensions bypass the public helper layer, agents will miss capabilities, duplicate raw JSON, and reintroduce shell quoting or timeout bugs.
