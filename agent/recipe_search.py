#!/usr/bin/env python
"""
Recipe Search — find the right analysis recipe for a task.

Usage:
    python recipe_search.py "count cells"           # search by description
    python recipe_search.py --domain neuroscience   # filter by domain
    python recipe_search.py --tag threshold          # filter by tag
    python recipe_search.py --image-type 16-bit      # filter by image requirements
    python recipe_search.py --list                   # list all recipes
    python recipe_search.py --show cell_counting     # show full recipe details
    python recipe_search.py --validate               # validate all recipe YAML files

From Python:
    from recipe_search import search, recommend, load_all_recipes
    results = search("colocalization analysis")
    recipe = recommend(image_info={"type": "16-bit", "channels": 2}, task="colocalization")
"""

import os
import sys
import json
import re

# ---------------------------------------------------------------------------
# YAML loading — use PyYAML if available, otherwise simple parser
# ---------------------------------------------------------------------------

try:
    import yaml

    def _load_yaml(path):
        with open(path, "r", encoding="utf-8") as f:
            return yaml.safe_load(f)

except ImportError:
    # Minimal YAML parser for the recipe schema — handles the subset we need
    def _load_yaml(path):
        with open(path, "r", encoding="utf-8") as f:
            text = f.read()
        return _simple_yaml_parse(text)


def _simple_yaml_parse(text):
    """Bare-bones YAML parser for recipe files.

    Handles: scalars, lists (- item and [a, b] flow), multi-line strings (> and |),
    nested mappings (2-space indent), and inline lists. Does NOT handle anchors,
    aliases, or complex nesting beyond 3 levels. Good enough for recipe YAML.
    """
    result = {}
    lines = text.split("\n")
    i = 0

    def _parse_value(val):
        val = val.strip()
        if val == "" or val == "~" or val.lower() == "null":
            return None
        if val.lower() == "true":
            return True
        if val.lower() == "false":
            return False
        # Inline list [a, b, c]
        if val.startswith("[") and val.endswith("]"):
            inner = val[1:-1]
            items = []
            for item in inner.split(","):
                item = item.strip().strip('"').strip("'")
                if item:
                    items.append(item)
            return items
        # Quoted string
        if (val.startswith('"') and val.endswith('"')) or (val.startswith("'") and val.endswith("'")):
            return val[1:-1]
        # Number
        try:
            if "." in val:
                return float(val)
            return int(val)
        except ValueError:
            pass
        return val

    def _indent_level(line):
        return len(line) - len(line.lstrip())

    def _parse_block(lines, start, base_indent):
        """Parse a mapping block at a given indent level."""
        obj = {}
        i = start
        while i < len(lines):
            line = lines[i]
            stripped = line.strip()

            if not stripped or stripped.startswith("#"):
                i += 1
                continue

            indent = _indent_level(line)
            if indent < base_indent:
                break

            if indent > base_indent:
                i += 1
                continue

            # Key: value
            if ":" in stripped and not stripped.startswith("-"):
                colon_pos = stripped.index(":")
                key = stripped[:colon_pos].strip()
                val_part = stripped[colon_pos + 1:].strip()

                if val_part == "" or val_part == "|" or val_part == ">":
                    # Could be a block scalar or nested structure
                    # Peek at next lines
                    j = i + 1
                    if j < len(lines):
                        next_stripped = lines[j].strip()
                        next_indent = _indent_level(lines[j]) if next_stripped else 0
                        if next_stripped.startswith("- ") and next_indent > indent:
                            # List
                            obj[key] = _parse_list(lines, j, next_indent)
                            i = _skip_block(lines, j, next_indent)
                            continue
                        elif next_indent > indent and next_stripped and ":" in next_stripped:
                            # Nested mapping
                            sub, end = _parse_block_return(lines, j, next_indent)
                            obj[key] = sub
                            i = end
                            continue
                        elif val_part in ("|", ">") and next_indent > indent:
                            # Block scalar
                            text_lines = []
                            k = j
                            while k < len(lines):
                                if lines[k].strip() == "":
                                    text_lines.append("")
                                    k += 1
                                    continue
                                if _indent_level(lines[k]) <= indent:
                                    break
                                text_lines.append(lines[k].strip())
                                k += 1
                            sep = "\n" if val_part == "|" else " "
                            obj[key] = sep.join(text_lines).strip()
                            i = k
                            continue
                    obj[key] = None
                else:
                    obj[key] = _parse_value(val_part)
                i += 1
            else:
                i += 1

        return obj

    def _parse_block_return(lines, start, base_indent):
        """Parse block and return (obj, next_line_index)."""
        obj = {}
        i = start
        while i < len(lines):
            line = lines[i]
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                i += 1
                continue
            indent = _indent_level(line)
            if indent < base_indent:
                break
            if indent > base_indent:
                i += 1
                continue
            if ":" in stripped and not stripped.startswith("-"):
                colon_pos = stripped.index(":")
                key = stripped[:colon_pos].strip()
                val_part = stripped[colon_pos + 1:].strip()
                if val_part == "" or val_part == "|" or val_part == ">":
                    j = i + 1
                    if j < len(lines):
                        next_stripped = lines[j].strip()
                        next_indent = _indent_level(lines[j]) if next_stripped else 0
                        if next_stripped.startswith("- ") and next_indent > indent:
                            obj[key] = _parse_list(lines, j, next_indent)
                            i = _skip_block(lines, j, next_indent)
                            continue
                        elif val_part in ("|", ">") and next_indent > indent:
                            text_lines = []
                            k = j
                            while k < len(lines):
                                if lines[k].strip() == "":
                                    text_lines.append("")
                                    k += 1
                                    continue
                                if _indent_level(lines[k]) <= indent:
                                    break
                                text_lines.append(lines[k].strip())
                                k += 1
                            sep = "\n" if val_part == "|" else " "
                            obj[key] = sep.join(text_lines).strip()
                            i = k
                            continue
                    obj[key] = None
                else:
                    obj[key] = _parse_value(val_part)
            i += 1
        return obj, i

    def _parse_list(lines, start, base_indent):
        """Parse a YAML list starting at the given position."""
        items = []
        i = start
        while i < len(lines):
            line = lines[i]
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                i += 1
                continue
            indent = _indent_level(line)
            if indent < base_indent:
                break
            if stripped.startswith("- "):
                item_val = stripped[2:].strip()
                if ":" in item_val:
                    # Dict item — collect all sub-keys
                    colon_pos = item_val.index(":")
                    first_key = item_val[:colon_pos].strip()
                    first_val = item_val[colon_pos + 1:].strip()
                    item_dict = {first_key: _parse_value(first_val) if first_val else None}
                    j = i + 1
                    # Collect continuation keys at deeper indent
                    item_indent = indent + 2
                    while j < len(lines):
                        sub_line = lines[j]
                        sub_stripped = sub_line.strip()
                        if not sub_stripped or sub_stripped.startswith("#"):
                            j += 1
                            continue
                        sub_indent = _indent_level(sub_line)
                        if sub_indent < item_indent:
                            break
                        if ":" in sub_stripped and not sub_stripped.startswith("-"):
                            cp = sub_stripped.index(":")
                            sk = sub_stripped[:cp].strip()
                            sv = sub_stripped[cp + 1:].strip()
                            if sv == "|" or sv == ">":
                                # Block scalar inside list item
                                text_lines = []
                                k = j + 1
                                while k < len(lines):
                                    if lines[k].strip() == "":
                                        text_lines.append("")
                                        k += 1
                                        continue
                                    if _indent_level(lines[k]) <= sub_indent:
                                        break
                                    text_lines.append(lines[k].strip())
                                    k += 1
                                sep = "\n" if sv == "|" else " "
                                item_dict[sk] = sep.join(text_lines).strip()
                                j = k
                                continue
                            else:
                                item_dict[sk] = _parse_value(sv) if sv else None
                        j += 1
                    items.append(item_dict)
                    i = j
                    continue
                else:
                    items.append(_parse_value(item_val))
            i += 1
        return items

    def _skip_block(lines, start, base_indent):
        """Skip past a block at the given indent level."""
        i = start
        while i < len(lines):
            stripped = lines[i].strip()
            if not stripped or stripped.startswith("#"):
                i += 1
                continue
            if _indent_level(lines[i]) < base_indent:
                break
            i += 1
        return i

    result = _parse_block(lines, 0, 0)
    return result


# ---------------------------------------------------------------------------
# Recipe loading
# ---------------------------------------------------------------------------

RECIPE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "recipes")
SCHEMA_PATH = os.path.join(RECIPE_DIR, "schema.json")
SCHEMA_VERSION = 1
USER_RECIPE_DIR_ENV = "IMAGEJAI_USER_RECIPES_DIR"
RECIPE_DIRS_ENV = "IMAGEJAI_RECIPE_DIRS"


def recipe_directories(recipe_dir=None):
    """Return recipe directories in discovery order.

    By default ImageJAI searches user-saved recipes first
    (Fiji.app/ImageJAI/recipes, passed in IMAGEJAI_USER_RECIPES_DIR by
    the Java launcher), then bundled recipes in agent/recipes. Passing
    recipe_dir keeps the old override behavior and searches only that
    directory.
    """
    if recipe_dir:
        if isinstance(recipe_dir, (list, tuple)):
            raw_dirs = [str(d) for d in recipe_dir if str(d).strip()]
        else:
            raw_dirs = [str(recipe_dir)]
    else:
        raw_dirs = []
        env_dirs = os.environ.get(RECIPE_DIRS_ENV, "").strip()
        if env_dirs:
            raw_dirs.extend([d for d in env_dirs.split(os.pathsep) if d.strip()])
        else:
            user_dir = os.environ.get(USER_RECIPE_DIR_ENV, "").strip()
            if user_dir:
                raw_dirs.append(user_dir)
            raw_dirs.append(RECIPE_DIR)

    dirs = []
    seen = set()
    for raw in raw_dirs:
        path = os.path.abspath(os.path.expanduser(str(raw)))
        key = os.path.normcase(os.path.normpath(path))
        if key in seen:
            continue
        seen.add(key)
        dirs.append(path)
    return dirs


def load_recipe(path):
    """Load a single recipe YAML file and return as dict."""
    try:
        recipe = _load_yaml(path)
        if not isinstance(recipe, dict):
            return {"_error": "Recipe document must be a YAML mapping", "_path": path}
        return recipe
    except Exception as e:
        return {"_error": str(e), "_path": path}


def load_all_recipes(recipe_dir=None):
    """Load all .yaml/.yml recipe files from configured recipe directories.

    Returns:
        List of recipe dicts.
    """
    recipes = []
    for d in recipe_directories(recipe_dir):
        if not os.path.isdir(d):
            continue
        for fname in sorted(os.listdir(d)):
            if fname.endswith((".yaml", ".yml")) and not fname.startswith("."):
                path = os.path.join(d, fname)
                recipe = load_recipe(path)
                recipe["_source_file"] = fname
                recipe["_source_dir"] = d
                recipe["_source_path"] = path
                recipes.append(recipe)
    return recipes


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------

REQUIRED_FIELDS = [
    "schema_version", "name", "id", "description", "domain", "difficulty",
    "preconditions", "parameters", "steps",
]

ROOT_FIELDS = frozenset([
    "schema_version", "name", "id", "description", "domain", "difficulty",
    "preconditions", "parameters", "steps", "postconditions", "validation",
    "outputs", "known_issues", "tags", "created", "related_recipes",
    # Documented, informational extensions used by specialist bundled recipes.
    "decision_points", "quality_settings", "decisions", "display_ranges",
    "animations", "naming", "batch_script", "groovy_reference", "tips",
    "agent_instructions",
])
PRECONDITION_FIELDS = frozenset([
    "image_type", "min_channels", "needs_stack", "needs_time",
    "needs_calibration", "notes",
])
PARAMETER_FIELDS = frozenset([
    "name", "label", "type", "default", "range", "options", "description",
    "image_specific", "value", "note",
])
STEP_FIELDS = frozenset([
    "id", "name", "description", "type", "macro", "code", "script",
    "groovy", "file", "language", "instructions", "when", "decision_point",
    "decision_logic", "capture_after", "validate", "validation", "notes",
    "timeout_seconds", "expected_output", "source",
])
PARAMETER_TYPES = frozenset(["numeric", "string", "text", "choice", "boolean"])
STEP_TYPES = frozenset(["macro", "script", "manual"])
SCRIPT_LANGUAGES = frozenset(["groovy", "jython", "javascript"])
CONTRACT_TYPES = frozenset(["acknowledgement", "image_open", "results_nonempty"])
_SCHEMA_CACHE = None


def _canonical_schema_issues(recipe):
    """Apply schema.json when jsonschema is available.

    The hand-written semantic checks below remain the dependency-free fallback
    and add execution-specific rules (such as rejecting comment-only macros).
    """
    try:
        from jsonschema import Draft202012Validator
    except ImportError:
        return []
    global _SCHEMA_CACHE
    try:
        if _SCHEMA_CACHE is None:
            with open(SCHEMA_PATH, "r", encoding="utf-8") as handle:
                _SCHEMA_CACHE = json.load(handle)
        document = {key: value for key, value in recipe.items() if not str(key).startswith("_")}
        errors = sorted(Draft202012Validator(_SCHEMA_CACHE).iter_errors(document),
                        key=lambda error: list(error.absolute_path))
    except (IOError, OSError, ValueError) as exc:
        return ["Canonical schema could not be loaded: %s" % exc]
    issues = []
    for error in errors:
        path = ".".join(str(part) for part in error.absolute_path) or "recipe"
        issues.append("Schema %s: %s" % (path, error.message))
    return issues


def _unknown_fields(mapping, allowed, prefix):
    return ["%s has unknown field: %s" % (prefix, key)
            for key in mapping if key not in allowed and not str(key).startswith("_")]


def _is_nonempty_text(value):
    return isinstance(value, str) and bool(value.strip())


def _comment_only(code):
    if not _is_nonempty_text(code):
        return True
    meaningful = []
    in_block = False
    for raw in code.splitlines():
        line = raw.strip()
        if in_block:
            if "*/" in line:
                line = line.split("*/", 1)[1].strip()
                in_block = False
            else:
                continue
        if line.startswith("/*"):
            if "*/" in line[2:]:
                line = line.split("*/", 1)[1].strip()
            else:
                in_block = True
                continue
        line = line.split("//", 1)[0].strip()
        if line:
            meaningful.append(line)
    return not meaningful


def step_type(step):
    """Resolve a validated step to one exhaustive dispatcher name."""
    explicit = str(step.get("type") or "").strip().lower()
    if explicit:
        return explicit
    if _is_nonempty_text(step.get("groovy")) or _is_nonempty_text(step.get("script")):
        return "script"
    if _is_nonempty_text(step.get("macro")) or _is_nonempty_text(step.get("code")):
        return "macro"
    return None


def _validate_contract(contract, where):
    issues = []
    if isinstance(contract, str):
        if not contract.strip():
            issues.append("%s acknowledgement is empty" % where)
        return issues
    if not isinstance(contract, dict):
        return ["%s must be a string or mapping" % where]
    if "type" not in contract and _is_nonempty_text(contract.get("check")):
        allowed = frozenset(["check", "method"])
        issues.extend(_unknown_fields(contract, allowed, where))
        if "method" in contract and not _is_nonempty_text(contract.get("method")):
            issues.append("%s method must be non-empty text" % where)
        return issues
    allowed = frozenset(["type", "prompt"])
    issues.extend(_unknown_fields(contract, allowed, where))
    kind = contract.get("type")
    if kind not in CONTRACT_TYPES:
        issues.append("%s has unsupported contract type: %s" % (where, kind))
    if kind == "acknowledgement" and not _is_nonempty_text(contract.get("prompt")):
        issues.append("%s acknowledgement requires prompt" % where)
    return issues


def _validate_contracts(value, where):
    if value is None or value == "":
        return []
    contracts = value if isinstance(value, list) else [value]
    if not contracts:
        return ["%s must not be empty" % where]
    issues = []
    for index, contract in enumerate(contracts, 1):
        issues.extend(_validate_contract(contract, "%s[%d]" % (where, index)))
    return issues


def validate_recipe(recipe):
    """Check that a recipe has all required fields and correct structure.

    Returns:
        List of issue strings (empty = valid).
    """
    issues = []

    if not isinstance(recipe, dict):
        return ["Recipe document must be a mapping"]
    if recipe.get("_error"):
        issues.append("Parse error: %s" % recipe["_error"])
        return issues

    issues.extend(_canonical_schema_issues(recipe))
    issues.extend(_unknown_fields(recipe, ROOT_FIELDS, "recipe"))

    for field in REQUIRED_FIELDS:
        if field not in recipe or recipe.get(field) is None or recipe.get(field) == "":
            issues.append("Missing required field: %s" % field)

    if recipe.get("schema_version") != SCHEMA_VERSION:
        issues.append("schema_version must be %d" % SCHEMA_VERSION)
    rid = recipe.get("id")
    if not _is_nonempty_text(rid) or not re.match(r"^[a-z0-9][a-z0-9_]*$", rid or ""):
        issues.append("id must be lower snake_case")
    if recipe.get("difficulty") not in ("beginner", "intermediate", "advanced"):
        issues.append("difficulty must be beginner, intermediate, or advanced")

    # Validate steps structure
    steps = recipe.get("steps", [])
    if isinstance(steps, list) and steps:
        seen_ids = set()
        for i, step in enumerate(steps):
            where = "Step %d" % (i + 1)
            if not isinstance(step, dict):
                issues.append("%s is not a mapping" % where)
                continue
            issues.extend(_unknown_fields(step, STEP_FIELDS, where))
            if not _is_nonempty_text(step.get("description")):
                issues.append("%s requires description" % where)
            step_id = step.get("id", i + 1)
            if step_id in seen_ids:
                issues.append("%s duplicates step id %s" % (where, step_id))
            seen_ids.add(step_id)
            kind = step_type(step)
            if kind not in STEP_TYPES:
                issues.append("%s has unsupported or missing type" % where)
                continue
            payloads = [key for key in ("macro", "code", "script", "groovy", "file", "instructions")
                        if _is_nonempty_text(step.get(key))]
            executable = [key for key in payloads if key != "instructions"]
            if kind == "manual":
                if executable:
                    issues.append("%s manual step cannot contain executable payloads" % where)
                if not _is_nonempty_text(step.get("instructions") or step.get("notes")):
                    issues.append("%s manual step requires instructions or notes" % where)
            elif kind == "macro":
                macro_payloads = [key for key in ("macro", "code") if key in executable]
                if len(macro_payloads) != 1 or len(executable) != 1:
                    issues.append("%s macro step requires exactly one macro/code payload" % where)
                elif _comment_only(step.get(macro_payloads[0])):
                    issues.append("%s macro payload contains comments only; use type: manual" % where)
            elif kind == "script":
                script_payloads = [key for key in ("script", "groovy", "code", "file") if key in executable]
                if len(script_payloads) != 1 or len(executable) != 1:
                    issues.append("%s script step requires exactly one script/groovy/code/file payload" % where)
                elif script_payloads[0] != "file" and _comment_only(step.get(script_payloads[0])):
                    issues.append("%s script payload contains comments only" % where)
                language = str(step.get("language") or ("groovy" if "groovy" in script_payloads else "")).lower()
                if language not in SCRIPT_LANGUAGES:
                    issues.append("%s script language is unsupported: %s" % (where, language or "missing"))
            when = step.get("when")
            if when is not None:
                if not isinstance(when, dict):
                    issues.append("%s when must be a mapping" % where)
                else:
                    issues.extend(_unknown_fields(when, frozenset(["parameter", "equals", "not_equals", "in"]), where + ".when"))
                    if not _is_nonempty_text(when.get("parameter")):
                        issues.append("%s when requires parameter" % where)
                    operators = [key for key in ("equals", "not_equals", "in") if key in when]
                    if len(operators) != 1:
                        issues.append("%s when requires exactly one operator" % where)
                    if "in" in when and not isinstance(when.get("in"), list):
                        issues.append("%s when.in must be a list" % where)
            if "decision_point" in step and not isinstance(step.get("decision_point"), bool):
                issues.append("%s decision_point must be boolean" % where)
            if step.get("decision_point") and not _is_nonempty_text(step.get("decision_logic")):
                issues.append("%s decision_point requires decision_logic" % where)
            if "capture_after" in step and not isinstance(step.get("capture_after"), bool):
                issues.append("%s capture_after must be boolean" % where)
            timeout = step.get("timeout_seconds")
            if timeout is not None and (not isinstance(timeout, (int, float)) or isinstance(timeout, bool) or timeout <= 0):
                issues.append("%s timeout_seconds must be positive" % where)
            if "validate" in step:
                issues.extend(_validate_contracts(step.get("validate"), where + ".validate"))
            if "validation" in step:
                issues.extend(_validate_contracts(step.get("validation"), where + ".validation"))
    else:
        issues.append("steps must be a non-empty list")

    # Validate parameters structure
    params = recipe.get("parameters", [])
    parameter_names = set()
    if isinstance(params, list):
        for i, p in enumerate(params):
            where = "Parameter %d" % (i + 1)
            if not isinstance(p, dict):
                issues.append("%s is not a mapping" % where)
                continue
            issues.extend(_unknown_fields(p, PARAMETER_FIELDS, where))
            name = p.get("name")
            if not _is_nonempty_text(name):
                issues.append("%s has no name" % where)
            elif name in parameter_names:
                issues.append("%s duplicates name %s" % (where, name))
            else:
                parameter_names.add(name)
            ptype = p.get("type")
            if ptype not in PARAMETER_TYPES:
                issues.append("Parameter '%s' has unsupported type: %s" % (name or i + 1, ptype))
            if "default" not in p:
                issues.append("Parameter '%s' has no default" % (name or i + 1))
            if ptype == "choice":
                options = p.get("options")
                if not isinstance(options, list) or not options:
                    issues.append("Parameter '%s' choice requires options" % (name or i + 1))
                elif p.get("default") not in options:
                    issues.append("Parameter '%s' default is not in options" % (name or i + 1))
            if "range" in p:
                bounds = p.get("range")
                if not isinstance(bounds, list) or len(bounds) != 2 or not all(isinstance(x, (int, float)) for x in bounds):
                    issues.append("Parameter '%s' range must contain two numbers" % (name or i + 1))
    else:
        issues.append("parameters must be a list")

    # Validate preconditions
    pre = recipe.get("preconditions", {})
    if isinstance(pre, dict):
        issues.extend(_unknown_fields(pre, PRECONDITION_FIELDS, "preconditions"))
        img_type = pre.get("image_type")
        if img_type and not isinstance(img_type, list):
            issues.append("preconditions.image_type should be a list")
        min_channels = pre.get("min_channels")
        if min_channels is not None and (not isinstance(min_channels, int) or isinstance(min_channels, bool) or min_channels < 0):
            issues.append("preconditions.min_channels must be a non-negative integer")
        for key in ("needs_stack", "needs_time", "needs_calibration"):
            if key in pre and not isinstance(pre.get(key), bool):
                issues.append("preconditions.%s must be boolean" % key)
    else:
        issues.append("preconditions must be a mapping")

    # Validate tags
    tags = recipe.get("tags", [])
    if tags and not isinstance(tags, list):
        issues.append("'tags' should be a list")

    issues.extend(_validate_contracts(recipe.get("postconditions"), "postconditions"))
    issues.extend(_validate_contracts(recipe.get("validation"), "validation"))

    return list(dict.fromkeys(issues))


def validate_all(recipe_dir=None):
    """Validate all recipes and return a summary."""
    recipes = load_all_recipes(recipe_dir)
    results = []
    for recipe in recipes:
        issues = validate_recipe(recipe)
        results.append({
            "file": recipe.get("_source_file", "unknown"),
            "id": recipe.get("id", "unknown"),
            "valid": len(issues) == 0,
            "issues": issues,
        })
    ids = {}
    for result in results:
        rid = result["id"]
        if rid == "unknown":
            continue
        if rid in ids:
            issue = "Duplicate recipe id also used by %s" % ids[rid]
            result["issues"].append(issue)
            result["valid"] = False
        else:
            ids[rid] = result["file"]
    return results


# ---------------------------------------------------------------------------
# Search and recommendation
# ---------------------------------------------------------------------------

def _tokenize(text):
    """Split text into lowercase search tokens."""
    if not text:
        return []
    return re.findall(r'[a-z0-9]+', text.lower())


def _recipe_text(recipe):
    """Concatenate all searchable text from a recipe."""
    parts = [
        str(recipe.get("name", "")),
        str(recipe.get("description", "")),
        str(recipe.get("domain", "")),
        str(recipe.get("id", "")),
    ]
    tags = recipe.get("tags", [])
    if isinstance(tags, list):
        parts.extend(str(t) for t in tags)

    # Include step descriptions
    for step in recipe.get("steps", []):
        if isinstance(step, dict):
            parts.append(str(step.get("description", "")))

    # Include known issues
    for issue in recipe.get("known_issues", []):
        if isinstance(issue, dict):
            parts.append(str(issue.get("condition", "")))
            parts.append(str(issue.get("symptom", "")))
            parts.append(str(issue.get("fix", "")))

    # Include parameter descriptions
    for param in recipe.get("parameters", []):
        if isinstance(param, dict):
            parts.append(str(param.get("name", "")))
            parts.append(str(param.get("description", "")))

    return " ".join(parts)


def search(query, domain=None, tag=None, image_type=None, recipe_dir=None):
    """Search recipes by keyword, with optional filters.

    Args:
        query: search string (matched against name, description, tags, steps).
        domain: filter to a specific domain (e.g. "cell_biology", "neuroscience").
        tag: filter to recipes with this tag.
        image_type: filter to recipes compatible with this image type (e.g. "16-bit").
        recipe_dir: override recipe directory.

    Returns:
        List of (recipe, score) tuples sorted by relevance (highest first).
    """
    recipes = load_all_recipes(recipe_dir)
    query_tokens = _tokenize(query) if query else []

    results = []
    for recipe in recipes:
        # Invalid documents remain visible to --validate but are never ranked
        # or recommended as runnable workflows.
        if validate_recipe(recipe):
            continue
        # Apply filters
        if domain:
            if recipe.get("domain", "").lower() != domain.lower():
                continue

        if tag:
            tags = recipe.get("tags", [])
            if not isinstance(tags, list):
                tags = []
            if tag.lower() not in [str(t).lower() for t in tags]:
                continue

        if image_type:
            pre = recipe.get("preconditions", {})
            if isinstance(pre, dict):
                allowed = pre.get("image_type", [])
                if isinstance(allowed, list) and allowed:
                    if image_type not in allowed and image_type.lower() not in [str(a).lower() for a in allowed]:
                        continue

        # Score by keyword match
        if query_tokens:
            text_tokens = _tokenize(_recipe_text(recipe))
            score = 0
            for qt in query_tokens:
                # Exact token match
                matches = sum(1 for tt in text_tokens if qt == tt)
                score += matches * 2
                # Substring match
                partial = sum(1 for tt in text_tokens if qt in tt and qt != tt)
                score += partial

            # Bonus for match in name or id
            name_tokens = _tokenize(recipe.get("name", "") + " " + recipe.get("id", ""))
            for qt in query_tokens:
                if any(qt == nt or qt in nt for nt in name_tokens):
                    score += 5

            if score > 0:
                results.append((recipe, score))
        else:
            # No query — include everything that passed filters
            results.append((recipe, 0))

    results.sort(key=lambda x: -x[1])
    return results


def recommend(image_info=None, task=None, recipe_dir=None):
    """Recommend the best recipe for a given task and image.

    Args:
        image_info: dict with keys like type, channels, slices, width, height.
        task: description of what the user wants to do.
        recipe_dir: override recipe directory.

    Returns:
        Best matching recipe dict, or None.
    """
    image_type = None
    if image_info:
        image_type = image_info.get("type")

    results = search(task or "", image_type=image_type, recipe_dir=recipe_dir)

    if not results:
        return None

    # Further rank by image compatibility
    if image_info:
        for i, (recipe, score) in enumerate(results):
            pre = recipe.get("preconditions", {})
            if not isinstance(pre, dict):
                continue

            bonus = 0

            # Bonus for matching image type
            allowed = pre.get("image_type", [])
            if isinstance(allowed, list) and image_type:
                if image_type in allowed:
                    bonus += 3

            # Bonus for matching channel count
            min_ch = pre.get("min_channels")
            ch = image_info.get("channels", 1)
            if min_ch and ch and ch >= min_ch:
                bonus += 1

            # Bonus/penalty for stack requirement
            needs_stack = pre.get("needs_stack")
            has_stack = image_info.get("slices", 1) > 1
            if needs_stack and has_stack:
                bonus += 2
            elif needs_stack and not has_stack:
                bonus -= 5

            results[i] = (recipe, score + bonus)

        results.sort(key=lambda x: -x[1])

    return results[0][0] if results else None


# ---------------------------------------------------------------------------
# Pretty printing
# ---------------------------------------------------------------------------

def format_recipe(recipe):
    """Format a recipe dict as readable text."""
    lines = []
    width = 56

    name = recipe.get("name", recipe.get("id", "Unknown"))
    domain = recipe.get("domain", "general")
    difficulty = recipe.get("difficulty", "?")

    lines.append("")
    lines.append("=" * width)
    lines.append("  %s" % name)
    lines.append("  Domain: %s | Difficulty: %s" % (domain, difficulty))
    lines.append("=" * width)
    lines.append("")

    desc = recipe.get("description", "")
    if desc:
        # Word wrap at ~70 chars
        words = desc.split()
        current = "  "
        for w in words:
            if len(current) + len(w) + 1 > 72:
                lines.append(current)
                current = "  " + w
            else:
                current += (" " if len(current) > 2 else "") + w
        if current.strip():
            lines.append(current)
        lines.append("")

    # Preconditions
    pre = recipe.get("preconditions", {})
    if isinstance(pre, dict) and pre:
        lines.append("  Preconditions:")
        img_type = pre.get("image_type")
        if img_type:
            if isinstance(img_type, list):
                lines.append("    - Image type: %s" % ", ".join(str(t) for t in img_type))
            else:
                lines.append("    - Image type: %s" % img_type)
        if pre.get("min_channels"):
            lines.append("    - Min channels: %s" % pre["min_channels"])
        if pre.get("needs_stack"):
            lines.append("    - Needs stack: yes")
        cal = pre.get("needs_calibration")
        if cal is not None:
            lines.append("    - Needs calibration: %s" % ("yes" if cal else "recommended" if cal is False else cal))
        notes = pre.get("notes")
        if notes:
            lines.append("    - %s" % notes)
        lines.append("")

    # Parameters
    params = recipe.get("parameters", [])
    if isinstance(params, list) and params:
        lines.append("  Parameters:")
        for p in params:
            if not isinstance(p, dict):
                continue
            pname = p.get("name", "?")
            ptype = p.get("type", "?")
            default = p.get("default", "")
            parts = "    %-18s [%-7s]  default=%-8s" % (pname, ptype, default)
            if p.get("options"):
                opts = p["options"]
                if isinstance(opts, list):
                    parts += "  options: %s" % ", ".join(str(o) for o in opts)
            elif p.get("range"):
                r = p["range"]
                if isinstance(r, list) and len(r) == 2:
                    parts += "  range: %s-%s" % (r[0], r[1])
            lines.append(parts)
        lines.append("")

    # Steps
    steps = recipe.get("steps", [])
    if isinstance(steps, list) and steps:
        lines.append("  Steps:")
        for step in steps:
            if not isinstance(step, dict):
                continue
            step_id = step.get("id", "?")
            desc = step.get("description", "")
            lines.append("    %s. %s" % (step_id, desc))
            if step.get("decision_point"):
                logic = step.get("decision_logic", "")
                if logic:
                    lines.append("       [Decision] %s" % logic[:100])
            if step.get("validate"):
                lines.append("       [Validate] %s" % step["validate"][:100])
        lines.append("")

    # Known Issues
    issues = recipe.get("known_issues", [])
    if isinstance(issues, list) and issues:
        lines.append("  Known Issues:")
        for issue in issues:
            if not isinstance(issue, dict):
                continue
            lines.append("    - \"%s\" -> %s" % (
                issue.get("symptom", issue.get("condition", "?")),
                issue.get("fix", "?")))
        lines.append("")

    # Tags
    tags = recipe.get("tags", [])
    if isinstance(tags, list) and tags:
        lines.append("  Tags: %s" % ", ".join(str(t) for t in tags))

    lines.append("=" * width)
    return "\n".join(lines)


def format_recipe_list(recipes):
    """Format a short list of recipes (search results)."""
    lines = []
    lines.append("")
    lines.append("  %-25s %-20s %-12s %s" % ("ID", "Name", "Domain", "Difficulty"))
    lines.append("  " + "-" * 75)
    for recipe in recipes:
        if isinstance(recipe, tuple):
            recipe, score = recipe
        rid = recipe.get("id", "?")[:24]
        name = recipe.get("name", "?")
        if len(name) > 19:
            name = name[:17] + ".."
        domain = recipe.get("domain", "?")[:11]
        diff = recipe.get("difficulty", "?")
        lines.append("  %-25s %-20s %-12s %s" % (rid, name, domain, diff))
    lines.append("")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def main(argv=None):
    import argparse

    parser = argparse.ArgumentParser(description="Search and recommend ImageJ analysis recipes.")
    parser.add_argument("query", nargs="*", help="Search query (keywords)")
    parser.add_argument("--domain", help="Filter by domain (e.g. cell_biology, neuroscience)")
    parser.add_argument("--tag", help="Filter by tag")
    parser.add_argument("--image-type", help="Filter by compatible image type (e.g. 8-bit, 16-bit)")
    parser.add_argument("--list", action="store_true", help="List all available recipes")
    parser.add_argument("--show", help="Show full details for a recipe by ID")
    parser.add_argument("--validate", action="store_true", help="Validate all recipe YAML files")
    parser.add_argument("--json", action="store_true", help="Output as JSON")
    parser.add_argument("--recipe-dir", help="Override recipe directory path")
    args = parser.parse_args(argv)

    recipe_dir = args.recipe_dir

    if args.validate:
        results = validate_all(recipe_dir)
        all_valid = bool(results) and all(r["valid"] for r in results)
        if args.json:
            print(json.dumps(results, indent=2))
        else:
            for r in results:
                status = "OK" if r["valid"] else "ISSUES"
                print("  [%s] %s (%s)" % (status, r["file"], r["id"]))
                if r["issues"]:
                    for issue in r["issues"]:
                        print("        - %s" % issue)
            if all_valid:
                print("\n  All %d recipes are valid." % len(results))
            else:
                print("\n  Recipe validation failed.")
        return 0 if all_valid else 1

    if args.show:
        recipes = load_all_recipes(recipe_dir)
        found = None
        for r in recipes:
            if r.get("id") == args.show or r.get("_source_file", "").replace(".yaml", "").replace(".yml", "") == args.show:
                found = r
                break
        if found:
            if args.json:
                print(json.dumps(found, indent=2, default=str))
            else:
                print(format_recipe(found))
        else:
            print("Recipe '%s' not found. Available IDs:" % args.show)
            for r in recipes:
                print("  - %s" % r.get("id", r.get("_source_file", "?")))
        return 0 if found else 1

    if args.list:
        recipes = load_all_recipes(recipe_dir)
        if not recipes:
            print("No recipes found in %s" % ", ".join(recipe_directories(recipe_dir)))
            return 1
        if args.json:
            summary = [{"id": r.get("id"), "name": r.get("name"), "domain": r.get("domain"),
                         "difficulty": r.get("difficulty"), "tags": r.get("tags", [])}
                        for r in recipes]
            print(json.dumps(summary, indent=2))
        else:
            print(format_recipe_list(recipes))
        return 0

    # Search
    query = " ".join(args.query) if args.query else ""
    if not query and not args.domain and not args.tag and not args.image_type:
        parser.print_help()
        return 2

    results = search(query, domain=args.domain, tag=args.tag,
                     image_type=args.image_type, recipe_dir=recipe_dir)

    if not results:
        print("No recipes found matching '%s'." % query)
        return 1

    if args.json:
        output = [{"id": r.get("id"), "name": r.get("name"), "score": s,
                    "domain": r.get("domain")} for r, s in results]
        print(json.dumps(output, indent=2))
    else:
        print("\n  Found %d recipe%s:" % (len(results), "s" if len(results) != 1 else ""))
        print(format_recipe_list(results))
        # Show top result details
        if len(results) >= 1:
            top = results[0][0]
            print("  Top match:")
            print(format_recipe(top))
    return 0


if __name__ == "__main__":
    sys.exit(main())
