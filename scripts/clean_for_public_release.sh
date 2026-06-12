#!/usr/bin/env bash
# Pre-public-release hygiene sweep — POSIX/CI variant of
# clean_for_public_release.ps1. See that file for full rationale.
#
# Usage (from repo root):
#   ./scripts/clean_for_public_release.sh           # dry run
#   ./scripts/clean_for_public_release.sh --apply   # delete for real

set -euo pipefail

APPLY=0
if [ "${1:-}" = "--apply" ]; then
    APPLY=1
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

echo ""
echo "ImageJAI public-release cleanup"
echo "Repo root: $REPO_ROOT"
if [ "$APPLY" -eq 1 ]; then
    echo "Mode:      APPLY (deleting)"
else
    echo "Mode:      DRY RUN (no changes)"
fi
echo ""

# Patterns that must never ship. Matched on basename, case-insensitively.
PATTERNS=(
    '*conflicted copy*'
    '*conflicted-copy*'
    '*-Conflict.*'
    'hs_err_pid*.log'
    'replay_pid*.log'
    '*.stackdump'
    '*.hprof'
    '.DS_Store'
    'Thumbs.db'
    'Desktop.ini'
    '*.swp'
    '*.swo'
)

# Top-level scratch directories removed wholesale when present.
DIRS=(
    'agent/.tmp'
    '.tmp/brainstorm-reports'
)

candidates=()

for pat in "${PATTERNS[@]}"; do
    while IFS= read -r -d '' f; do
        candidates+=("$f")
    done < <(find . -path ./.git -prune -o -type f -iname "$pat" -print0 2>/dev/null || true)
done

for d in "${DIRS[@]}"; do
    if [ -d "$d" ]; then
        candidates+=("$d")
    fi
done

if [ "${#candidates[@]}" -eq 0 ]; then
    echo "Nothing to clean. Repo is already release-ready."
    exit 0
fi

echo "Found ${#candidates[@]} item(s) to remove:"
for c in "${candidates[@]}"; do
    if [ -d "$c" ]; then
        echo "  [DIR ] $c"
    else
        echo "  [FILE] $c"
    fi
done

if [ "$APPLY" -ne 1 ]; then
    echo ""
    echo "Dry run complete. Re-run with --apply to delete."
    exit 0
fi

echo ""
failed=0
for c in "${candidates[@]}"; do
    if rm -rf -- "$c" 2>/dev/null; then
        :
    else
        failed=$((failed + 1))
        echo "WARN: Failed to delete: $c" >&2
    fi
done

if [ "$failed" -gt 0 ]; then
    echo ""
    echo "Cleanup completed with $failed failure(s). Investigate before tagging."
    exit 1
fi

echo ""
echo "Clean. Re-run before each tag/release."
