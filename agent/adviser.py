#!/usr/bin/env python3
"""
ImageJ Analysis Adviser — research-only mode.

An expert consultant that answers microscopy analysis questions, recommends
plugins, suggests workflows, and writes macros — without touching ImageJ.

No TCP connection needed. No images are modified. Pure advice.

Usage:
    python adviser.py "how do I measure colocalization?"
    python adviser.py "best plugin for neurite tracing"
    python adviser.py --plugins "deconvolution"
    python adviser.py --recipe cell_counting
    python adviser.py --macro "count cells in 16-bit z-stack"
    python adviser.py --compare "StarDist vs Cellpose"
    python adviser.py --install "ilastik"
"""

import os
import sys
import json
import re

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RECIPES_DIR = os.path.join(SCRIPT_DIR, "recipes")
TMP_DIR = os.path.join(SCRIPT_DIR, ".tmp")


def load_commands():
    """Load the available commands list from the raw scan output."""
    path = os.path.join(TMP_DIR, "commands.raw.txt")
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return [line.strip() for line in f if line.strip() and "=null" not in line]


def load_update_sites():
    """Load update sites (enabled and available)."""
    path = os.path.join(TMP_DIR, "update_sites.json")
    if not os.path.exists(path):
        return {}
    with open(path) as f:
        return json.load(f)


def load_recipes():
    """Load all recipe files."""
    recipes = []
    if not os.path.exists(RECIPES_DIR):
        return recipes
    for fname in sorted(os.listdir(RECIPES_DIR)):
        if not fname.endswith(".yaml"):
            continue
        fpath = os.path.join(RECIPES_DIR, fname)
        recipe = _parse_yaml_simple(fpath)
        if recipe:
            recipe["_file"] = fname
            recipes.append(recipe)
    return recipes


def _parse_yaml_simple(path):
    """Simple YAML parser — handles the recipe format without PyYAML."""
    try:
        import yaml
        with open(path) as f:
            return yaml.safe_load(f)
    except ImportError:
        pass
    # Fallback: extract key fields from YAML text
    result = {}
    try:
        with open(path) as f:
            text = f.read()
        for field in ["name", "id", "description", "domain", "difficulty"]:
            m = re.search(r"^" + field + r":\s*(.+)$", text, re.MULTILINE)
            if m:
                result[field] = m.group(1).strip().strip('"').strip("'")
        # Extract tags
        m = re.search(r"^tags:\s*\[(.+)\]", text, re.MULTILINE)
        if m:
            result["tags"] = [t.strip().strip('"').strip("'") for t in m.group(1).split(",")]
        # Extract steps as raw text
        m = re.search(r"^steps:\s*\n((?:\s+.+\n)*)", text, re.MULTILINE)
        if m:
            result["_steps_raw"] = m.group(1)
        # Extract macro code blocks
        result["_macros"] = re.findall(r"macro:\s*\|?\s*\n((?:\s{6,}.+\n)*)", text)
    except Exception:
        pass
    return result if result else None


def load_reference(name):
    """Load a reference document."""
    path = os.path.join(SCRIPT_DIR, name)
    if os.path.exists(path):
        with open(path) as f:
            return f.read()
    return ""


def search_commands(keyword):
    """Search installed commands for a keyword."""
    commands = load_commands()
    keyword_lower = keyword.lower()
    return [cmd for cmd in commands if keyword_lower in cmd.lower()]


def search_update_sites(keyword):
    """Search update sites for a plugin."""
    sites = load_update_sites()
    keyword_lower = keyword.lower()
    results = []
    for name, info in sites.items():
        if keyword_lower in name.lower() or keyword_lower in info.get("description", "").lower():
            results.append({
                "name": name,
                "enabled": info.get("enabled", False),
                "description": info.get("description", ""),
                "url": info.get("url", ""),
            })
    return results


def search_recipes(keyword):
    """Search recipes for a keyword."""
    recipes = load_recipes()
    keyword_lower = keyword.lower()
    results = []
    for r in recipes:
        blob = json.dumps(r).lower()
        if keyword_lower in blob:
            results.append(r)
    return results


def search_reference(keyword):
    """Search reference documents for relevant sections."""
    results = []
    for doc_name in ["analysis-landscape.md", "domain-reference.md", "self-improving-agent-reference.md"]:
        text = load_reference(doc_name)
        if not text:
            continue
        # Find sections containing the keyword
        sections = text.split("\n## ")
        for section in sections:
            if keyword.lower() in section.lower():
                # Get section title and first few lines
                lines = section.strip().split("\n")
                title = lines[0].strip("#").strip() if lines else "?"
                preview = "\n".join(lines[:10])
                results.append({
                    "source": doc_name,
                    "title": title,
                    "preview": preview,
                })
    return results


def check_plugin_installed(plugin_name):
    """Check if a plugin is installed (in commands list)."""
    commands = load_commands()
    name_lower = plugin_name.lower()
    for cmd in commands:
        if name_lower in cmd.lower():
            return True, cmd
    return False, None


def get_install_instructions(plugin_name):
    """Get installation instructions for a plugin."""
    sites = search_update_sites(plugin_name)
    if not sites:
        return None

    instructions = []
    for site in sites:
        status = "INSTALLED" if site["enabled"] else "NOT INSTALLED"
        instructions.append({
            "name": site["name"],
            "status": status,
            "description": site["description"],
            "install_steps": [
                "Open Fiji",
                "Go to Help > Update...",
                "Click 'Manage Update Sites'",
                "Find and check '%s'" % site["name"],
                "Click 'Apply changes'",
                "Restart Fiji",
            ] if not site["enabled"] else ["Already installed via update site '%s'" % site["name"]],
        })
    return instructions


def generate_macro_suggestion(task_description):
    """Generate a macro code suggestion based on matching recipes."""
    recipes = search_recipes(task_description)
    if not recipes:
        # Try individual words
        words = task_description.lower().split()
        for word in words:
            if len(word) > 3:
                recipes = search_recipes(word)
                if recipes:
                    break

    if not recipes:
        return None

    # Use the best matching recipe
    recipe = recipes[0]
    macros = recipe.get("_macros", [])
    if macros:
        return {
            "recipe": recipe.get("name", "?"),
            "description": recipe.get("description", ""),
            "macro_code": "\n".join(m.strip() for m in macros),
        }
    return {
        "recipe": recipe.get("name", "?"),
        "description": recipe.get("description", ""),
        "note": "See recipe file: recipes/%s" % recipe.get("_file", "?"),
    }


def format_advice(query, mode=None):
    """Generate formatted advice for a query."""
    lines = []
    lines.append("")
    lines.append("=" * 60)
    lines.append("  ImageJ Analysis Adviser")
    lines.append("=" * 60)
    lines.append("")
    lines.append("Query: %s" % query)
    lines.append("")

    # 1. Search recipes
    recipes = search_recipes(query)
    if recipes:
        lines.append("--- Matching Recipes ---")
        for r in recipes[:5]:
            lines.append("  [%s] %s" % (r.get("domain", "?"), r.get("name", "?")))
            lines.append("    %s" % r.get("description", ""))
            lines.append("    Difficulty: %s | File: recipes/%s" % (
                r.get("difficulty", "?"), r.get("_file", "?")))
            lines.append("")

    # 2. Search installed commands
    matching_commands = search_commands(query)
    if matching_commands:
        lines.append("--- Installed Commands (%d matches) ---" % len(matching_commands))
        for cmd in matching_commands[:15]:
            name = cmd.split("=")[0].strip()
            lines.append("  - %s" % name)
        if len(matching_commands) > 15:
            lines.append("  ... and %d more" % (len(matching_commands) - 15))
        lines.append("")

    # 3. Search update sites (plugins to install)
    sites = search_update_sites(query)
    if sites:
        lines.append("--- Available Plugins ---")
        for s in sites[:10]:
            status = "[INSTALLED]" if s["enabled"] else "[AVAILABLE]"
            lines.append("  %s %s" % (status, s["name"]))
            if s["description"]:
                lines.append("    %s" % s["description"][:100])
            if not s["enabled"]:
                lines.append("    Install: Help > Update > Manage Update Sites > check '%s'" % s["name"])
        lines.append("")

    # 4. Search reference documents
    refs = search_reference(query)
    if refs:
        lines.append("--- Reference Material ---")
        for ref in refs[:5]:
            lines.append("  [%s] %s" % (ref["source"], ref["title"]))
            # Show first 3 content lines
            preview_lines = ref["preview"].split("\n")[1:4]
            for pl in preview_lines:
                if pl.strip():
                    lines.append("    %s" % pl.strip()[:100])
        lines.append("")

    # 5. Macro suggestion
    if mode == "macro":
        macro = generate_macro_suggestion(query)
        if macro:
            lines.append("--- Suggested Macro ---")
            lines.append("  Based on recipe: %s" % macro.get("recipe", "?"))
            if "macro_code" in macro:
                lines.append("")
                for code_line in macro["macro_code"].split("\n"):
                    if code_line.strip():
                        lines.append("  %s" % code_line.strip())
            elif "note" in macro:
                lines.append("  %s" % macro["note"])
            lines.append("")

    if len(lines) <= 7:
        lines.append("No matches found. Try different keywords or check:")
        lines.append("  python adviser.py --plugins KEYWORD   # search plugins")
        lines.append("  python adviser.py --install KEYWORD   # installation help")
        lines.append("  python adviser.py --recipe KEYWORD    # search recipes")
        lines.append("")

    lines.append("=" * 60)
    return "\n".join(lines)


def format_comparison(term):
    """Compare two approaches/plugins."""
    parts = re.split(r"\s+vs\.?\s+|\s+or\s+|\s+versus\s+", term, flags=re.IGNORECASE)
    if len(parts) != 2:
        return "Usage: --compare 'PluginA vs PluginB'"

    a, b = parts[0].strip(), parts[1].strip()
    lines = []
    lines.append("")
    lines.append("=" * 60)
    lines.append("  Comparison: %s vs %s" % (a, b))
    lines.append("=" * 60)
    lines.append("")

    for name in [a, b]:
        lines.append("--- %s ---" % name)
        installed, cmd = check_plugin_installed(name)
        lines.append("  Installed: %s" % ("Yes" if installed else "No"))
        if cmd:
            lines.append("  Command: %s" % cmd.split("=")[0].strip())

        # Search recipes
        recipes = search_recipes(name)
        if recipes:
            lines.append("  Recipes: %s" % ", ".join(r.get("name", "?") for r in recipes[:3]))

        # Search reference docs
        refs = search_reference(name)
        if refs:
            for ref in refs[:2]:
                preview_lines = ref["preview"].split("\n")[1:3]
                for pl in preview_lines:
                    if pl.strip():
                        lines.append("  %s" % pl.strip()[:100])

        sites = search_update_sites(name)
        if sites:
            for s in sites[:1]:
                if s["description"]:
                    lines.append("  Description: %s" % s["description"][:120])

        lines.append("")

    lines.append("=" * 60)
    return "\n".join(lines)


def format_install(plugin_name):
    """Show installation instructions for a plugin."""
    instructions = get_install_instructions(plugin_name)
    lines = []
    lines.append("")
    lines.append("=" * 60)
    lines.append("  Installation: %s" % plugin_name)
    lines.append("=" * 60)
    lines.append("")

    if not instructions:
        # Check if it's already installed as a command
        installed, cmd = check_plugin_installed(plugin_name)
        if installed:
            lines.append("  '%s' is already available as a command." % plugin_name)
            lines.append("  Command: %s" % cmd)
            lines.append("")
            lines.append("  Macro usage:")
            cmd_name = cmd.split("=")[0].strip()
            lines.append('  run("%s");' % cmd_name)
        else:
            lines.append("  No update site found for '%s'." % plugin_name)
            lines.append("")
            lines.append("  Try searching with different keywords:")
            lines.append("    python adviser.py --plugins %s" % plugin_name)
            lines.append("")
            lines.append("  Or check the ImageJ wiki:")
            lines.append("    https://imagej.net/plugins/")
    else:
        for inst in instructions:
            lines.append("  %s  %s" % (inst["status"], inst["name"]))
            if inst["description"]:
                lines.append("  %s" % inst["description"][:120])
            lines.append("")
            lines.append("  Steps:")
            for i, step in enumerate(inst["install_steps"], 1):
                lines.append("    %d. %s" % (i, step))
            lines.append("")

    lines.append("=" * 60)
    return "\n".join(lines)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return

    args = sys.argv[1:]

    if args[0] == "--plugins":
        keyword = " ".join(args[1:]) if len(args) > 1 else ""
        if not keyword:
            print("Usage: python adviser.py --plugins KEYWORD")
            return
        # Search both commands and update sites
        cmds = search_commands(keyword)
        sites = search_update_sites(keyword)
        print("\n--- Installed Commands matching '%s' (%d) ---" % (keyword, len(cmds)))
        for cmd in cmds[:20]:
            print("  - %s" % cmd.split("=")[0].strip())
        print("\n--- Update Sites matching '%s' (%d) ---" % (keyword, len(sites)))
        for s in sites:
            status = "[ON]" if s["enabled"] else "[OFF]"
            print("  %s %s" % (status, s["name"]))
            if s["description"]:
                print("       %s" % s["description"][:100])

    elif args[0] == "--recipe":
        keyword = " ".join(args[1:]) if len(args) > 1 else ""
        if not keyword:
            # List all recipes
            recipes = load_recipes()
            print("\n--- All Recipes (%d) ---" % len(recipes))
            for r in recipes:
                print("  [%s] %s — %s" % (
                    r.get("domain", "?"), r.get("name", "?"),
                    r.get("description", "")[:80]))
        else:
            recipes = search_recipes(keyword)
            if recipes:
                for r in recipes[:5]:
                    print("\n  [%s] %s" % (r.get("domain", "?"), r.get("name", "?")))
                    print("  %s" % r.get("description", ""))
                    print("  Difficulty: %s | File: recipes/%s" % (
                        r.get("difficulty", "?"), r.get("_file", "?")))
            else:
                print("No recipes matching '%s'" % keyword)

    elif args[0] == "--macro":
        task = " ".join(args[1:])
        macro = generate_macro_suggestion(task)
        if macro:
            print("\n--- Macro Suggestion ---")
            print("Based on: %s" % macro.get("recipe", "?"))
            print("Description: %s" % macro.get("description", ""))
            if "macro_code" in macro:
                print("\n// Macro code:")
                print(macro["macro_code"])
            elif "note" in macro:
                print(macro["note"])
        else:
            print("No macro suggestion for '%s'. Try different keywords." % task)

    elif args[0] == "--compare":
        term = " ".join(args[1:])
        print(format_comparison(term))

    elif args[0] == "--install":
        plugin = " ".join(args[1:])
        print(format_install(plugin))

    elif args[0] in ("--help", "-h"):
        print(__doc__)

    else:
        # General query
        query = " ".join(args)
        print(format_advice(query))


if __name__ == "__main__":
    main()
