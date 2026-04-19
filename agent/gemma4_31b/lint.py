"""Macro lint — ten rules that run after safety.check_macro and before Fiji.

Each rule is a pure function taking (code, state). It returns None when the
macro passes, or a short repair-hint string when it fails. Rules are tagged
"block" (reject the run) or "warn" (run anyway, but prepend a warning to the
tool feedback so the agent sees it). State is fetched once per lint call via
get_image_info; if Fiji is unreachable, state is an empty dict and stateful
rules treat that as "cannot check, pass" rather than blocking on a missing
connection.
"""

from __future__ import annotations

import re

from .registry import send


# --- image state probe ----------------------------------------------------


def _fetch_state() -> dict:
    """Fetch minimal image state once via the get_image_info TCP command.

    Returns the parsed result dict on success or an empty dict when Fiji is
    unreachable, the reply is malformed, or the server reports a failure.
    Rules that need state treat an empty dict as "cannot check, pass".
    """
    try:
        resp = send("get_image_info")
    except Exception:
        return {}
    if not isinstance(resp, dict) or not resp.get("ok"):
        return {}
    result = resp.get("result")
    if not isinstance(result, dict):
        return {}
    return result


def _bit_depth(state: dict) -> int:
    """Parse get_image_info's 'type' field into a numeric bit depth.

    Returns 0 when the type is missing or unrecognised so stateful rules can
    treat it as "cannot check". Known mappings: GRAY8 -> 8, GRAY16 -> 16,
    GRAY32 / FLOAT -> 32, RGB / COLOR_RGB -> 24.
    """
    type_str = state.get("type") if isinstance(state, dict) else None
    if not isinstance(type_str, str):
        return 0
    t = type_str.upper()
    if t in ("GRAY8", "8-BIT", "8BIT", "COLOR_256"):
        return 8
    if t in ("GRAY16", "16-BIT", "16BIT"):
        return 16
    if t in ("GRAY32", "32-BIT", "32BIT", "FLOAT"):
        return 32
    if t in ("RGB", "COLOR_RGB", "24-BIT"):
        return 24
    return 0


def _strip_comments(code: str) -> str:
    """Drop // line comments and /* block */ comments so regexes match only real code."""
    out = re.sub(r"/\*.*?\*/", "", code, flags=re.DOTALL)
    out = re.sub(r"//[^\n]*", "", out)
    return out


# --- rules ----------------------------------------------------------------


def _rule_analyze_particles_non_binary(code, state):
    """Reject Analyze Particles on a non-8-bit image when Convert to Mask is absent."""
    clean = _strip_comments(code)
    m = re.search(r'run\s*\(\s*"Analyze Particles\.\.\."', clean)
    if not m:
        return None
    depth = _bit_depth(state)
    if depth == 0 or depth == 8:
        return None
    pre = clean[: m.start()]
    if re.search(r'run\s*\(\s*"Convert to Mask"', pre):
        return None
    return (
        "Analyze Particles requires an 8-bit binary image, but the active "
        "image is {}-bit. Threshold first with setAutoThreshold(...) and "
        "run(\"Convert to Mask\"); before calling Analyze Particles."
    ).format(depth)


def _rule_blackbackground_option_missing(code, state):
    """Warn when Convert to Mask runs without setOption('BlackBackground', true) earlier."""
    clean = _strip_comments(code)
    m = re.search(r'run\s*\(\s*"Convert to Mask"', clean)
    if not m:
        return None
    pre = clean[: m.start()]
    if re.search(r'setOption\s*\(\s*"BlackBackground"', pre):
        return None
    return (
        "Convert to Mask runs without setOption(\"BlackBackground\", true) "
        "first — on some Fiji installs the mask polarity silently flips. "
        "Add setOption(\"BlackBackground\", true); before Convert to Mask."
    )


def _rule_autothreshold_stack_flag_missing(code, state):
    """Warn when setAutoThreshold runs on a multi-slice stack without 'stack' in its argument."""
    slices = state.get("slices") if isinstance(state, dict) else None
    if not isinstance(slices, int) or slices <= 1:
        return None
    clean = _strip_comments(code)
    for m in re.finditer(r'setAutoThreshold\s*\(\s*"([^"]*)"\s*\)', clean):
        args = m.group(1)
        if "stack" not in args.lower():
            suggested = (args + " stack").strip()
            return (
                "setAutoThreshold(\"{}\") runs on a {}-slice stack but the "
                "argument string does not include 'stack'; only the current "
                "slice will be thresholded. Use setAutoThreshold(\"{}\")."
            ).format(args, slices, suggested)
    return None


def _rule_windows_backslashes_in_path(code, state):
    """Reject string literals that contain a backslash-backslash path separator."""
    for m in re.finditer(r'"([^"]*)"', code):
        literal = m.group(1)
        if "\\\\" in literal:
            return (
                "String literal \"{}\" contains '\\\\' — ImageJ macros "
                "misread Windows-style paths. Use forward slashes instead, "
                "e.g. \"C:/Users/jamie/data.tif\" not "
                "\"C:\\\\Users\\\\jamie\\\\data.tif\"."
            ).format(literal)
    return None


def _rule_enhance_contrast_normalize_before_measure(code, state):
    """Reject Enhance Contrast with normalize= followed later by Measure or Analyze Particles."""
    clean = _strip_comments(code)
    ec = re.search(
        r'run\s*\(\s*"Enhance Contrast[^"]*"\s*,\s*"([^"]*)"\s*\)',
        clean,
        re.IGNORECASE,
    )
    if not ec:
        return None
    args = ec.group(1).lower()
    if "normalize" not in args:
        return None
    if re.search(r"normalize\s*=\s*false", args):
        return None
    after = clean[ec.end():]
    measures = re.search(r'run\s*\(\s*"Measure"\s*\)', after)
    particles = re.search(r'run\s*\(\s*"Analyze Particles\.\.\."', after)
    if not measures and not particles:
        return None
    return (
        "Enhance Contrast with 'normalize' permanently rewrites pixel "
        "values, and a Measure / Analyze Particles call runs afterwards. "
        "Measurements will reflect the rescaled image, not the raw data. "
        "Use setMinAndMax(min, max) for display-only contrast, or move "
        "the measurement before the Enhance Contrast call."
    )


def _rule_roi_manager_not_reset(code, state):
    """Warn when roiManager('Measure') runs without a prior roiManager('reset')."""
    clean = _strip_comments(code)
    m = re.search(r'roiManager\s*\(\s*"Measure"', clean)
    if not m:
        return None
    pre = clean[: m.start()]
    if re.search(r'roiManager\s*\(\s*"reset"', pre, re.IGNORECASE):
        return None
    return (
        "roiManager(\"Measure\") runs without an earlier "
        "roiManager(\"reset\"); ROIs from a previous run may still be in "
        "the manager and get measured again. Add roiManager(\"reset\"); "
        "before populating the manager."
    )


def _rule_results_not_cleared(code, state):
    """Warn when run('Measure') runs without a prior run('Clear Results')."""
    clean = _strip_comments(code)
    m = re.search(r'run\s*\(\s*"Measure"\s*\)', clean)
    if not m:
        return None
    pre = clean[: m.start()]
    if re.search(r'run\s*\(\s*"Clear Results"', pre):
        return None
    return (
        "run(\"Measure\") runs without run(\"Clear Results\") first — rows "
        "from a previous run will still be in the Results table and will "
        "be mixed with the new measurements. Add run(\"Clear Results\"); "
        "before the first Measure."
    )


def _rule_run_with_no_argument_string(code, state):
    """Reject run('Plugin...') calls that have no argument string or an empty one."""
    clean = _strip_comments(code)
    pattern = r'run\s*\(\s*"([^"]*\.\.\.)"\s*(,\s*"([^"]*)")?\s*\)'
    for m in re.finditer(pattern, clean):
        plugin = m.group(1)
        has_second = m.group(2) is not None
        second_val = m.group(3)
        if not has_second:
            return (
                "run(\"{}\") has no argument string — the plugin name ends "
                "in '...' which opens a dialog. Autonomous mode cannot click "
                "through dialogs and the macro will hang. Pass the arguments "
                "as a second quoted string, e.g. run(\"{}\", \"sigma=2\")."
            ).format(plugin, plugin)
        if has_second and second_val == "":
            return (
                "run(\"{}\", \"\") passes an empty argument string to a "
                "plugin whose name ends in '...' — the dialog still opens "
                "and the macro hangs. Probe the plugin with probe_plugin "
                "to discover parameter names, then fill in the string."
            ).format(plugin)
    return None


def _rule_wait_for_user_in_autonomous(code, state):
    """Reject any waitForUser call — autonomous mode cannot click OK on the popup."""
    clean = _strip_comments(code)
    if re.search(r'\bwaitForUser\s*\(', clean):
        return (
            "waitForUser(...) pauses the macro until a human clicks OK. "
            "The agent runs autonomously and cannot click the dialog, so "
            "the macro will hang forever. Remove the waitForUser call."
        )
    return None


def _rule_save_as_outside_ai_exports(code, state):
    """Warn early when a save path does not contain 'AI_Exports' — safety will reject later."""
    clean = _strip_comments(code)
    patterns = (
        r'saveAs\s*\(\s*"[^"]*"\s*,\s*"([^"]*)"\s*\)',
        r'IJ\.saveAs\s*\(\s*"[^"]*"\s*,\s*"([^"]*)"\s*\)',
        r'(?<![\w.])save\s*\(\s*"([^"]*)"\s*\)',
        r'File\.saveString\s*\(\s*"[^"]*"\s*,\s*"([^"]*)"\s*\)',
        r'File\.append\s*\(\s*"[^"]*"\s*,\s*"([^"]*)"\s*\)',
    )
    for pat in patterns:
        for m in re.finditer(pat, clean):
            path = m.group(1)
            if "AI_Exports" not in path:
                return (
                    "Save path \"{}\" does not contain 'AI_Exports'. The "
                    "safety layer only allows writes under the AI_Exports/ "
                    "folder sitting next to the active image, so this call "
                    "will be rejected. Put the output file inside that "
                    "folder before running."
                ).format(path)
    return None


def _rule_bitshift_in_for_condition(code, state):
    """Warn when a for-loop test clause contains '<<' — almost always a '<' typo.

    ImageJ's macro parser treats '<<' as bit-shift, so `for (i = 0; i << 10; i++)`
    evaluates `i << 10` (=0 while i==0) then trips a macro error on the non-boolean.
    Catches the exact typo Gemma keeps making when drafting loops.
    """
    clean = _strip_comments(code)
    if re.search(r"for\s*\([^;]*;[^;]*<<[^;]*;", clean):
        return (
            "for-loop test clause contains '<<' (bit-shift). Almost certainly a "
            "typo of '<' — e.g. `for (i = 0; i << 10; i++)` should be "
            "`for (i = 0; i < 10; i++)`."
        )
    return None


def _rule_run_with_three_args(code, state):
    """Reject run('A', 'B', 'C', ...) — macro run() only takes (name) or (name, args).

    ImageJ macro `run()` is a two-argument function. A third string argument
    is always a bug — Gemma tends to write it when conflating `IJ.saveAs(type,
    path)` with `run()`. When the first arg looks like a Save-As command,
    suggest the canonical `saveAs(type, path)` macro function.
    """
    clean = _strip_comments(code)
    # Match run(...) with exactly 3+ comma-separated string args. We do not
    # try to parse full general expressions — any positional string args are
    # enough to catch the real-world bug.
    pattern = r'run\s*\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"'
    m = re.search(pattern, clean)
    if not m:
        return None
    first = m.group(1)
    second = m.group(2)
    third = m.group(3)
    suggestion = (
        'run("{0}", "{1}", "{2}", ...) passes a third argument to run() — the '
        'macro function only accepts (name) or (name, args). '
    ).format(first, second, third)
    if re.search(r"save\s*as|^\s*save\s*$", first, re.IGNORECASE):
        suggestion += (
            'This looks like a save call; use `saveAs("{0}", "{1}");` instead '
            'of wrapping it in run().'
        ).format(second, third)
    else:
        suggestion += (
            'Merge the extra arg into the second string (space-separated '
            'key=value pairs) or use the plugin\'s macro-level function.'
        )
    return suggestion


def _rule_doubled_identifier(code, state):
    """Warn when a 4+ char identifier is immediately repeated — `methodsmethods`.

    Gemma sometimes doubles an identifier during loop drafting, e.g.
    `for (i=0; i<<methodsmethods.length; i++)`. The 4-char floor avoids false
    positives on legitimate repeats like `aa` in identifiers; ImageJ macro
    variables this long never contain a natural doubled suffix.
    """
    clean = _strip_comments(code)
    # Strip string literals so "hellohello" inside a log message doesn't trip.
    no_strings = re.sub(r'"[^"]*"', '""', clean)
    m = re.search(r"\b([A-Za-z_]\w{3,})\1\b", no_strings)
    if not m:
        return None
    token = m.group(1)
    return (
        "doubled identifier '{0}{0}' detected — almost certainly a typo where "
        "you meant '{0}'. Check the for-loop condition and array indexing."
    ).format(token)


# --- rule table -----------------------------------------------------------


RULES = [
    {
        "id": "analyze_particles_non_binary",
        "severity": "block",
        "description": "Analyze Particles requires an 8-bit binary image.",
        "check": _rule_analyze_particles_non_binary,
    },
    {
        "id": "blackbackground_option_missing",
        "severity": "warn",
        "description": "Convert to Mask without setOption('BlackBackground', true) can flip mask polarity.",
        "check": _rule_blackbackground_option_missing,
    },
    {
        "id": "autothreshold_stack_flag_missing",
        "severity": "warn",
        "description": "setAutoThreshold on a stack without 'stack' only thresholds the current slice.",
        "check": _rule_autothreshold_stack_flag_missing,
    },
    {
        "id": "windows_backslashes_in_path",
        "severity": "block",
        "description": "Backslash-backslash path separators break ImageJ macro string parsing.",
        "check": _rule_windows_backslashes_in_path,
    },
    {
        "id": "enhance_contrast_normalize_before_measure",
        "severity": "block",
        "description": "Enhance Contrast 'normalize' rewrites pixels and corrupts later measurements.",
        "check": _rule_enhance_contrast_normalize_before_measure,
    },
    {
        "id": "roi_manager_not_reset",
        "severity": "warn",
        "description": "roiManager('Measure') without a prior reset re-measures stale ROIs.",
        "check": _rule_roi_manager_not_reset,
    },
    {
        "id": "results_not_cleared",
        "severity": "warn",
        "description": "run('Measure') without Clear Results mixes new rows with stale ones.",
        "check": _rule_results_not_cleared,
    },
    {
        "id": "run_with_no_argument_string",
        "severity": "block",
        "description": "run('Plugin...') without a second argument hangs on the plugin dialog.",
        "check": _rule_run_with_no_argument_string,
    },
    {
        "id": "wait_for_user_in_autonomous",
        "severity": "block",
        "description": "waitForUser pauses for a human click; autonomous mode hangs forever.",
        "check": _rule_wait_for_user_in_autonomous,
    },
    {
        "id": "save_as_outside_ai_exports",
        "severity": "warn",
        "description": "Early hint that a save path is not inside AI_Exports/ (safety rejects later).",
        "check": _rule_save_as_outside_ai_exports,
    },
    {
        "id": "bitshift_in_for_condition",
        "severity": "warn",
        "description": "'<<' inside a for-loop test clause is almost always a typo of '<'.",
        "check": _rule_bitshift_in_for_condition,
    },
    {
        "id": "doubled_identifier",
        "severity": "warn",
        "description": "A 4+ char identifier is immediately repeated (e.g. 'methodsmethods') — almost always a typo.",
        "check": _rule_doubled_identifier,
    },
    {
        "id": "run_with_three_args",
        "severity": "block",
        "description": "run() only takes (name) or (name, args) — a third string argument is always a bug.",
        "check": _rule_run_with_three_args,
    },
]


# --- public entry point ---------------------------------------------------


def lint_macro(code):
    """Run every rule on the macro. Return None on pass, else a repair-hint string.

    A blocking-rule failure returns its hint as-is — callers should surface
    that hint to the agent as a tool-level error and not send the macro to
    Fiji. If no blocking rule fires, warning hints are joined into a single
    block of WARNING: lines, followed by '(run anyway? yes/no)'; callers
    should prepend this block to the normal tool feedback and still run the
    macro. Returns None when no rule fires.
    """
    if not isinstance(code, str) or not code.strip():
        return None
    state = _fetch_state()
    warnings = []
    for rule in RULES:
        try:
            hint = rule["check"](code, state)
        except Exception:
            continue
        if not hint:
            continue
        if rule["severity"] == "block":
            return hint
        warnings.append(hint)
    if not warnings:
        return None
    body = "\n".join("WARNING: " + w for w in warnings)
    return body + "\n(run anyway? yes/no)"
