"""
Sync agent context to all AI CLI tools.

Reads CLAUDE.md (the canonical source of truth) and generates equivalent
context files for every other AI CLI tool that auto-reads project files.

Run after updating CLAUDE.md:
    python sync_context.py

Also called automatically by scan_plugins.py on startup.
"""

import os
import re
import shutil

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CLAUDE_MD = os.path.join(SCRIPT_DIR, "CLAUDE.md")

# Map of agent -> {filename, transform}
# filename: what the agent auto-reads (relative to agent dir)
# transform: optional function to adapt content
AGENT_FILES = {
    "Gemini CLI": {
        "filename": "GEMINI.md",
        "header": "# Agent Context for Gemini CLI\n# (Auto-generated from CLAUDE.md — do not edit directly)\n\n",
    },
    "Codex CLI": {
        "filename": "AGENTS.md",
        "header": "# Agent Context for Codex CLI\n# (Auto-generated from CLAUDE.md — do not edit directly)\n\n",
    },
    "Cline": {
        "filename": ".clinerules",
        "header": "# Agent Context for Cline\n# (Auto-generated from CLAUDE.md — do not edit directly)\n\n",
    },
    "Cursor": {
        "filename": ".cursorrules",
        "header": "# Agent Context for Cursor\n# (Auto-generated from CLAUDE.md — do not edit directly)\n\n",
    },
    "Aider": {
        "filename": ".aider.conventions.md",
        "header": "# Agent Context for Aider\n# (Auto-generated from CLAUDE.md — do not edit directly)\n\n",
    },
}


def read_claude_md():
    """Read the canonical CLAUDE.md context file."""
    if not os.path.exists(CLAUDE_MD):
        print("ERROR: CLAUDE.md not found at", CLAUDE_MD)
        return None
    with open(CLAUDE_MD, "r", encoding="utf-8") as f:
        return f.read()


def strip_claude_specific(content):
    """Remove Claude-specific references and make content agent-generic."""
    # Replace "Claude" agent references with generic ones
    result = content

    # Remove the first H1 line if it says "ImageJAI Agent — Claude Context"
    result = re.sub(
        r"^# ImageJAI Agent — Claude Context",
        "# ImageJAI Agent Context",
        result,
    )

    return result


def sync():
    """Generate all agent-specific context files from CLAUDE.md."""
    content = read_claude_md()
    if content is None:
        return

    generic_content = strip_claude_specific(content)
    generated = []

    for agent_name, config in AGENT_FILES.items():
        filename = config["filename"]
        header = config.get("header", "")
        filepath = os.path.join(SCRIPT_DIR, filename)

        output = header + generic_content

        with open(filepath, "w", encoding="utf-8") as f:
            f.write(output)

        generated.append((agent_name, filename))
        print("  [OK] {} -> {}".format(agent_name, filename))

    return generated


def main():
    print("Syncing CLAUDE.md to other AI agent formats...")
    print()
    results = sync()
    if results:
        print()
        print("Generated {} context files from CLAUDE.md".format(len(results)))
        print("These files are auto-generated — edit CLAUDE.md and re-run to update.")
    else:
        print("No files generated.")


if __name__ == "__main__":
    main()
