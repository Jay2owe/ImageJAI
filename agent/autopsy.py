#!/usr/bin/env python
"""
Failure Autopsy System -- structured logging of macro failures and bad results.

Logs every failure with full context (command, error, image state) so the agent
can learn from past mistakes. The check_known_issues() method looks up past
failures for a given command and returns warnings before you make the same
mistake twice.

Usage:
    from autopsy import Autopsy
    autopsy = Autopsy()

    # Log a macro failure
    autopsy.log_failure(
        command='run("Analyze Particles...", "size=50-Infinity");',
        error="Not a binary image",
        image_state={"title": "blobs.gif", "type": "16-bit", "slices": 1},
        context="Tried to run particle analysis on raw image"
    )

    # Log a bad result (command succeeded but output was wrong)
    autopsy.log_bad_result(
        command='run("Analyze Particles...", "size=0-Infinity summarize");',
        result={"count": 250},
        expected={"count": "60-65"},
        image_state={"title": "blobs.gif", "type": "8-bit"}
    )

    # Check for known issues before running a command
    warnings = autopsy.check_known_issues(
        command='run("Analyze Particles..."',
        image_state={"type": "16-bit"}
    )
    # -> ["Analyze Particles previously failed on 16-bit images: 'Not a binary image'. Fix: Added threshold step before Analyze Particles"]

    # Get a summary report
    print(autopsy.get_report())
"""

import json
import os
import re
from datetime import datetime


AGENT_DIR = os.path.dirname(os.path.abspath(__file__))
TMP_DIR = os.path.join(AGENT_DIR, ".tmp")
LOG_FILE = os.path.join(TMP_DIR, "autopsy_log.json")


class Autopsy(object):
    """Structured failure logger with pattern matching for known issues."""

    def __init__(self, log_path=None):
        """Load existing log or start fresh.

        Args:
            log_path: path to the JSON log file. Defaults to .tmp/autopsy_log.json
        """
        self.log_path = log_path or LOG_FILE
        self.entries = []
        self._load()

    def _load(self):
        """Load existing entries from disk."""
        if os.path.exists(self.log_path):
            try:
                with open(self.log_path, "r") as f:
                    data = json.load(f)
                self.entries = data if isinstance(data, list) else []
            except (json.JSONDecodeError, IOError):
                self.entries = []

    def _save(self):
        """Persist entries to disk."""
        os.makedirs(os.path.dirname(os.path.abspath(self.log_path)), exist_ok=True)
        with open(self.log_path, "w") as f:
            json.dump(self.entries, f, indent=2)

    def log_failure(self, command, error, image_state=None, context=None,
                    fix_applied=None, tags=None):
        """Record a macro failure.

        Args:
            command: the macro code or command string that failed.
            error: the error message from ImageJ.
            image_state: dict with image info (title, type, slices, etc.).
            context: free-text description of what was being attempted.
            fix_applied: description of how the issue was resolved (if known).
            tags: list of string tags for categorization.
        """
        entry = {
            "timestamp": datetime.now().isoformat(),
            "type": "failure",
            "command": command,
            "error": str(error),
            "image_state": image_state or {},
            "context": context,
            "fix_applied": fix_applied,
            "tags": tags or _auto_tag(command, str(error)),
        }
        self.entries.append(entry)
        self._save()
        return entry

    def log_bad_result(self, command, result, expected, image_state=None,
                       context=None, fix_applied=None, tags=None):
        """Record a command that succeeded but produced wrong results.

        Args:
            command: the macro code that ran.
            result: dict of what was actually produced.
            expected: dict of what was expected.
            image_state: dict with image info.
            context: free-text description.
            fix_applied: how the issue was resolved.
            tags: list of string tags.
        """
        entry = {
            "timestamp": datetime.now().isoformat(),
            "type": "bad_result",
            "command": command,
            "result": result,
            "expected": expected,
            "image_state": image_state or {},
            "context": context,
            "fix_applied": fix_applied,
            "tags": tags or _auto_tag(command, ""),
        }
        self.entries.append(entry)
        self._save()
        return entry

    def log_fix(self, original_command, original_error, fixed_command,
                image_state=None, notes=None):
        """Record a successful fix for a previously failing command.

        Also retroactively updates the most recent matching failure entry
        with the fix information.

        Args:
            original_command: the command that originally failed.
            original_error: the error it produced.
            fixed_command: the command that worked.
            image_state: dict with image info.
            notes: free-text notes about why the fix works.
        """
        # Update the most recent matching failure
        for entry in reversed(self.entries):
            if (entry.get("type") == "failure"
                    and entry.get("command") == original_command
                    and entry.get("fix_applied") is None):
                entry["fix_applied"] = fixed_command
                if notes:
                    entry["context"] = (entry.get("context") or "") + " Fix: " + notes
                break

        # Also log the fix as its own entry for traceability
        fix_entry = {
            "timestamp": datetime.now().isoformat(),
            "type": "fix",
            "original_command": original_command,
            "original_error": str(original_error),
            "fixed_command": fixed_command,
            "image_state": image_state or {},
            "notes": notes,
            "tags": _auto_tag(original_command, str(original_error)),
        }
        self.entries.append(fix_entry)
        self._save()
        return fix_entry

    def check_known_issues(self, command, image_state=None):
        """Look up past failures relevant to a command and return warnings.

        Searches the log for entries whose command or tags overlap with the
        given command, and whose image state matches. Returns human-readable
        warning strings.

        Args:
            command: the macro code or command about to be run.
            image_state: dict with current image info (type, title, slices...).

        Returns:
            List of warning strings. Empty if no known issues found.
        """
        warnings = []
        command_lower = command.lower()
        image_type = (image_state or {}).get("type", "").lower()

        # Extract the key ImageJ command name from the macro code
        command_name = _extract_command_name(command)

        seen = set()  # deduplicate warnings

        for entry in self.entries:
            if entry.get("type") not in ("failure", "bad_result"):
                continue

            entry_cmd = entry.get("command", "")
            entry_cmd_name = _extract_command_name(entry_cmd)

            # Match by command name similarity
            match = False
            if command_name and entry_cmd_name and command_name == entry_cmd_name:
                match = True
            elif _commands_overlap(command_lower, entry_cmd.lower()):
                match = True

            if not match:
                continue

            # Build warning message
            if entry["type"] == "failure":
                error = entry.get("error", "unknown error")
                msg = "%s previously failed: '%s'" % (
                    entry_cmd_name or entry_cmd[:60], error
                )

                # Extra context if image type matches
                entry_type = entry.get("image_state", {}).get("type", "")
                if entry_type and image_type and entry_type.lower() == image_type:
                    msg += " (same image type: %s)" % image_type

                if entry.get("fix_applied"):
                    msg += ". Fix: %s" % entry["fix_applied"]

            else:  # bad_result
                msg = "%s previously produced bad results: got %s, expected %s" % (
                    entry_cmd_name or entry_cmd[:60],
                    json.dumps(entry.get("result", {})),
                    json.dumps(entry.get("expected", {})),
                )

            if msg not in seen:
                seen.add(msg)
                warnings.append(msg)

        return warnings

    def get_report(self):
        """Return a human-readable summary of all logged failures.

        Returns:
            Formatted string with failure counts, common errors, and fixes.
        """
        if not self.entries:
            return "No failures logged yet."

        failures = [e for e in self.entries if e.get("type") == "failure"]
        bad_results = [e for e in self.entries if e.get("type") == "bad_result"]
        fixes = [e for e in self.entries if e.get("type") == "fix"]

        lines = []
        lines.append("=== Autopsy Report ===")
        lines.append("Total entries: %d" % len(self.entries))
        lines.append("  Failures: %d" % len(failures))
        lines.append("  Bad results: %d" % len(bad_results))
        lines.append("  Fixes recorded: %d" % len(fixes))
        lines.append("")

        # Most common errors
        error_counts = {}
        for e in failures:
            err = e.get("error", "unknown")
            # Normalize the error to group similar messages
            normalized = _normalize_error(err)
            error_counts[normalized] = error_counts.get(normalized, 0) + 1

        if error_counts:
            lines.append("Most common errors:")
            sorted_errors = sorted(error_counts.items(), key=lambda x: -x[1])
            for err, count in sorted_errors[:10]:
                lines.append("  %dx  %s" % (count, err))
            lines.append("")

        # Most common tags
        tag_counts = {}
        for e in self.entries:
            for tag in e.get("tags", []):
                tag_counts[tag] = tag_counts.get(tag, 0) + 1

        if tag_counts:
            lines.append("Most common tags:")
            sorted_tags = sorted(tag_counts.items(), key=lambda x: -x[1])
            for tag, count in sorted_tags[:10]:
                lines.append("  %dx  %s" % (count, tag))
            lines.append("")

        # Recent entries
        recent = self.entries[-5:]
        if recent:
            lines.append("Recent entries:")
            for e in recent:
                ts = e.get("timestamp", "?")[:19]
                etype = e.get("type", "?")
                cmd = e.get("command", e.get("original_command", "?"))
                if len(cmd) > 60:
                    cmd = cmd[:57] + "..."
                if etype == "failure":
                    err = e.get("error", "")[:50]
                    lines.append("  [%s] FAIL  %s -> %s" % (ts, cmd, err))
                elif etype == "bad_result":
                    lines.append("  [%s] BAD   %s" % (ts, cmd))
                elif etype == "fix":
                    lines.append("  [%s] FIX   %s" % (ts, cmd))

        return "\n".join(lines)

    def clear(self):
        """Remove all entries and delete the log file."""
        self.entries = []
        if os.path.exists(self.log_path):
            os.remove(self.log_path)

    def __len__(self):
        return len(self.entries)


# ---------------------------------------------------------------------------
# Helper functions
# ---------------------------------------------------------------------------

def _extract_command_name(macro_code):
    """Extract the ImageJ command name from a run() call.

    For example:
        'run("Analyze Particles...", "size=50-Infinity");'
        -> "Analyze Particles..."
    """
    match = re.search(r'run\(\s*"([^"]+)"', macro_code)
    if match:
        return match.group(1)
    return ""


def _commands_overlap(cmd_a, cmd_b):
    """Check if two command strings are about the same operation."""
    # Extract significant words (3+ chars, not common words)
    skip = {"run", "the", "and", "for", "with", "from", "size", "true", "false"}

    def words(s):
        return set(w for w in re.findall(r'[a-z]{3,}', s) if w not in skip)

    words_a = words(cmd_a)
    words_b = words(cmd_b)

    if not words_a or not words_b:
        return False

    overlap = words_a & words_b
    smaller = min(len(words_a), len(words_b))
    if smaller == 0:
        return False
    return len(overlap) / float(smaller) >= 0.5


def _normalize_error(error_msg):
    """Normalize an error message for grouping similar errors."""
    # Remove file paths
    normalized = re.sub(r'[A-Za-z]:\\[^\s]+', '<path>', error_msg)
    normalized = re.sub(r'/[^\s]+', '<path>', normalized)
    # Remove numbers that vary
    normalized = re.sub(r'\b\d{3,}\b', 'N', normalized)
    # Trim
    normalized = normalized.strip()
    if len(normalized) > 100:
        normalized = normalized[:97] + "..."
    return normalized


def _auto_tag(command, error):
    """Generate tags automatically from command and error text."""
    tags = []
    combined = (command + " " + error).lower()

    tag_patterns = {
        "threshold_required": ["not a binary", "binary image", "8-bit binary"],
        "no_image": ["no image", "no window"],
        "selection_required": ["selection required", "no selection", "roi required"],
        "analyze_particles": ["analyze particles"],
        "threshold": ["threshold", "setautothreshold"],
        "measure": ["measure"],
        "gaussian_blur": ["gaussian blur"],
        "z_project": ["z project", "z-project"],
        "split_channels": ["split channel"],
        "background_subtract": ["subtract background", "rolling ball"],
        "watershed": ["watershed"],
        "type_conversion": ["convert", "8-bit", "16-bit", "32-bit", "rgb"],
        "file_io": ["open(", "save", "saveas"],
        "stack": ["stack", "slice", "nslices"],
    }

    for tag, patterns in tag_patterns.items():
        for pat in patterns:
            if pat in combined:
                tags.append(tag)
                break

    return tags


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import sys

    autopsy = Autopsy()

    if len(sys.argv) < 2 or sys.argv[1] in ("--help", "-h"):
        print(__doc__)
        print("Commands:")
        print("  python autopsy.py report          # show failure report")
        print("  python autopsy.py clear           # clear all entries")
        print("  python autopsy.py check COMMAND   # check known issues for a command")
        print("  python autopsy.py count           # number of logged entries")
        sys.exit(0)

    cmd = sys.argv[1].lower()

    if cmd == "report":
        print(autopsy.get_report())

    elif cmd == "clear":
        autopsy.clear()
        print("Autopsy log cleared.")

    elif cmd == "check":
        if len(sys.argv) < 3:
            print("Usage: python autopsy.py check 'run(\"Analyze Particles...\")'")
            sys.exit(1)
        command = " ".join(sys.argv[2:])
        warnings = autopsy.check_known_issues(command)
        if warnings:
            for w in warnings:
                print("WARNING: %s" % w)
        else:
            print("No known issues for this command.")

    elif cmd == "count":
        print("%d entries logged" % len(autopsy))

    elif cmd == "demo":
        # Add some demo entries for testing
        autopsy.log_failure(
            command='run("Analyze Particles...", "size=50-Infinity summarize");',
            error="Not a binary image",
            image_state={"title": "blobs.gif", "type": "16-bit", "slices": 1},
            context="Tried to count cells without thresholding first",
            fix_applied='Added setAutoThreshold("Otsu"); run("Convert to Mask"); before Analyze Particles',
        )
        autopsy.log_failure(
            command='run("Z Project...", "projection=[Max Intensity]");',
            error="This command requires a stack",
            image_state={"title": "single.tif", "type": "8-bit", "slices": 1},
            context="Image was a single plane, not a stack",
        )
        autopsy.log_bad_result(
            command='run("Analyze Particles...", "size=0-Infinity summarize");',
            result={"count": 250},
            expected={"count": "60-65"},
            image_state={"title": "blobs.gif", "type": "8-bit"},
            context="No size filter — counted noise as particles",
            fix_applied="Changed size filter to 50-Infinity",
        )
        print("Added 3 demo entries.")
        print(autopsy.get_report())

    else:
        print("Unknown command: %s" % cmd)
        sys.exit(1)
