#!/usr/bin/env bash
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DRY_RUN=0
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=1
fi

warn() {
  printf 'warning: %s\n' "$*" >&2
}

refresh_agent() {
  local id="$1"
  local cli="$2"
  local flags="${3:-}"
  local exe="${cli%% *}"
  local out tmp status

  if ! command -v "$exe" >/dev/null 2>&1; then
    warn "$id: missing '$exe'; skipping"
    return 0
  fi

  tmp="$(mktemp)"
  if command -v timeout >/dev/null 2>&1; then
    timeout 20s bash -lc "$cli $flags /help" >"$tmp" 2>&1
    status=$?
  else
    bash -lc "$cli $flags /help" >"$tmp" 2>&1
    status=$?
  fi
  if [[ "$status" -ne 0 && "$status" -ne 124 ]]; then
    warn "$id: '$cli /help' exited with $status; parsing any captured output"
  elif [[ "$status" -eq 124 ]]; then
    warn "$id: '$cli /help' timed out; parsing any captured output"
  fi

  python - "$ROOT" "$id" "$tmp" "$DRY_RUN" <<'PY'
import json
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
agent_id = sys.argv[2]
output_path = Path(sys.argv[3])
dry_run = sys.argv[4] == "1"
text = output_path.read_text(encoding="utf-8", errors="replace")
commands = []
seen = set()
for line in text.splitlines():
    clean = re.sub(r"\x1b\[[0-9;?]*[A-Za-z]", "", line).strip()
    match = re.search(r"^(/[A-Za-z][A-Za-z0-9_-]*)(?:\s+[-:]\s*|\s{2,})(.*)$", clean)
    if not match:
        match = re.search(r"^(/[A-Za-z][A-Za-z0-9_-]*)(?:\s+([A-Za-z].*))?$", clean)
    if not match:
        continue
    command = match.group(1)
    if command in seen:
        continue
    seen.add(command)
    desc = (match.group(2) or "").strip()
    commands.append({"command": command, "description": desc})

if len(commands) < 2:
    print(f"warning: {agent_id}: no slash command list parsed; leaving registry unchanged", file=sys.stderr)
    sys.exit(0)

target = root / "src" / "main" / "resources" / "agents" / agent_id / "commands.json"
if dry_run:
    print(f"{agent_id}: parsed {len(commands)} commands (dry run)")
else:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(commands, indent=2) + "\n", encoding="utf-8")
    print(f"{agent_id}: wrote {target}")
PY
  rm -f "$tmp"
}

refresh_agent "gemma4_31b" "gemma4_31b_agent"
refresh_agent "gemma4_31b_claude" "gemma4_31b_agent" "--style claude"
refresh_agent "claude" "claude"
refresh_agent "aider" "aider"
refresh_agent "gemini" "gemini"
refresh_agent "codex" "codex"
