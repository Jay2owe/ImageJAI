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

import pathlib
import re

from .registry import send


# --- known-command set (read once from scan_plugins.py output) ------------


_COMMANDS_RAW_PATH = (
    pathlib.Path(__file__).resolve().parent.parent / ".tmp" / "commands.raw.txt"
)


def _load_known_commands() -> frozenset:
    """Parse agent/.tmp/commands.raw.txt into a set of valid run() command names.

    File format is one `Name=java.class.path` per line, written by
    scan_plugins.py. Leading whitespace on names and divider lines like
    `-=null` are stripped. Returns an empty frozenset when the file is
    missing; the unknown_run_command rule short-circuits in that case so
    a user who hasn't run scan_plugins.py gets no spurious warnings.
    """
    try:
        text = _COMMANDS_RAW_PATH.read_text(encoding="utf-8", errors="replace")
    except (OSError, FileNotFoundError):
        return frozenset()
    names = set()
    for line in text.splitlines():
        if "=" not in line:
            continue
        name = line.split("=", 1)[0].strip()
        if not name or name in ("-",) or name.startswith("#"):
            continue
        names.add(name)
    return frozenset(names)


_KNOWN_COMMANDS: frozenset = _load_known_commands()


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


_HALLUCINATED_COMMANDS = {
    "Laplacian...": (
        'run("Laplacian...") is not a base-Fiji command. Use '
        'run("FeatureJ Laplacian", "compute smoothing=1.0") if FeatureJ is '
        'installed, or build it from a Gaussian + Convolve.'
    ),
    "Laplacian": (
        'run("Laplacian") is not a base-Fiji command. Use '
        'run("FeatureJ Laplacian", "compute smoothing=1.0") if FeatureJ is '
        'installed, or build it from a Gaussian + Convolve.'
    ),
    "Difference of Gaussians...": (
        'run("Difference of Gaussians...") does not exist in base Fiji. '
        'Build DoG from two Gaussian blurs then '
        'imageCalculator("Subtract create", "blur_small", "blur_large").'
    ),
    "Difference of Gaussians": (
        'run("Difference of Gaussians") does not exist in base Fiji. '
        'Build DoG from two Gaussian blurs then '
        'imageCalculator("Subtract create", "blur_small", "blur_large").'
    ),
    "DoG...": (
        'run("DoG...") does not exist. Build DoG from two '
        'run("Gaussian Blur...") calls then '
        'imageCalculator("Subtract create", "blur_small", "blur_large").'
    ),
    "DoG": (
        'run("DoG") does not exist. Build DoG from two '
        'run("Gaussian Blur...") calls then '
        'imageCalculator("Subtract create", "blur_small", "blur_large").'
    ),
    "Band-pass Filter...": (
        'run("Band-pass Filter...") is misspelled. The real name is '
        '"Bandpass Filter..." (one word, no hyphen): '
        'run("Bandpass Filter...", "filter_large=40 filter_small=3").'
    ),
    "Band-pass Filter": (
        'run("Band-pass Filter") is misspelled. The real name is '
        '"Bandpass Filter..." (one word, no hyphen): '
        'run("Bandpass Filter...", "filter_large=40 filter_small=3").'
    ),
    "Band Pass Filter...": (
        'run("Band Pass Filter...") is misspelled. The real name is '
        '"Bandpass Filter..." (one word, no space, no hyphen): '
        'run("Bandpass Filter...", "filter_large=40 filter_small=3").'
    ),
    "Band Pass Filter": (
        'run("Band Pass Filter") is misspelled. The real name is '
        '"Bandpass Filter..." (one word, no space, no hyphen): '
        'run("Bandpass Filter...", "filter_large=40 filter_small=3").'
    ),
    "Variance": (
        'run("Variance") is missing the ellipsis. The real name is '
        '"Variance..." (Process > Filters > Variance...): '
        'run("Variance...", "radius=2").'
    ),
}


def _rule_hallucinated_run_command(code, state):
    """Block run('<name>') calls that name commands not present in base Fiji.

    Hardcoded blocklist of names Gemma keeps inventing — Laplacian, DoG,
    Difference of Gaussians, Band-pass Filter (real name has no hyphen).
    Each entry returns a repair-hint with the correct recipe.
    """
    clean = _strip_comments(code)
    for m in re.finditer(r'run\s*\(\s*"([^"]+)"\s*(?:,|\))', clean):
        name = m.group(1)
        if name in _HALLUCINATED_COMMANDS:
            return _HALLUCINATED_COMMANDS[name]
    return None


def _rule_analyze_particles_no_output_flag(code, state):
    """Block when Analyze Particles runs with no output flag and nResults is read after.

    Analyze Particles only populates the Results table when args contain
    `display`/`display_results` or `results`. With `add_to_manager` the count
    comes from roiManager("count") instead. `summarize` writes to a separate
    Summary window — NOT the Results table — so reads of `nResults` or
    `getResult("Count", …)` return 0 / NaN even when summarize is present.
    Without any of those, a later read of `nResults` always returns 0 — a trap
    Gemma chased through seven macros in the filter-shootout transcript and
    re-hit in the replace-regex-phantom-dialog transcript.
    """
    clean = _strip_comments(code)
    ap = re.search(
        r'run\s*\(\s*"Analyze Particles\.\.\."\s*,\s*"([^"]*)"\s*\)', clean
    )
    if not ap:
        return None
    args = ap.group(1).lower()
    if re.search(r'\b(display|display_results|results|add_to_manager)\b', args):
        return None
    after = clean[ap.end():]
    nres = re.search(r'\bnResults\b', after)
    if not nres:
        return None
    clear = re.search(r'run\s*\(\s*"Clear Results"', after)
    if clear and clear.start() < nres.start():
        return None
    return (
        'run("Analyze Particles...", "size=...") with no output flag does '
        'not populate the Results table — nResults will always be 0. '
        'Preferred fix: add `add_to_manager` and read `roiManager("count")` '
        '— this is the most reliable count. Alternative: add `display` (not '
        '`summarize`; `summarize` writes to the Summary window, not the '
        'Results table, so nResults STILL stays 0).'
    )


# Chars that cause replace() to throw PatternSyntaxException or silently
# match more than intended when the agent meant a literal match.
_REPLACE_LITERAL_META = frozenset("().[]")
# Regex-intent markers — if any are present, assume deliberate regex and skip.
_REPLACE_REGEX_INTENT = re.compile(r'\\[sSdDwWbB]|\\\\|[*+?{}|^$]')


def _skip_ws_and_comments(code: str, i: int) -> int:
    """Advance past whitespace and comments without disturbing source spans."""
    n = len(code)
    while i < n:
        if code.startswith("/*", i):
            end = code.find("*/", i + 2)
            return n if end == -1 else _skip_ws_and_comments(code, end + 2)
        if code.startswith("//", i):
            end = code.find("\n", i + 2)
            return n if end == -1 else _skip_ws_and_comments(code, end + 1)
        if code[i].isspace():
            i += 1
            continue
        return i
    return n


def _parse_string_literal(code: str, i: int):
    """Return (text, content_start, content_end, next_index) for a quoted literal."""
    if i >= len(code) or code[i] != '"':
        return None
    j = i + 1
    n = len(code)
    while j < n:
        ch = code[j]
        if ch == "\\" and j + 1 < n:
            j += 2
            continue
        if ch == '"':
            return (code[i + 1:j], i + 1, j, j + 1)
        j += 1
    return None


def _consume_call_arg(code: str, i: int):
    """Consume one replace() argument and return ('comma'|'close'|'eof', index)."""
    n = len(code)
    depth = 0
    while i < n:
        if code.startswith("/*", i):
            end = code.find("*/", i + 2)
            return ("eof", n) if end == -1 else _consume_call_arg(code, end + 2)
        if code.startswith("//", i):
            end = code.find("\n", i + 2)
            return ("eof", n) if end == -1 else _consume_call_arg(code, end + 1)
        ch = code[i]
        if ch == '"':
            parsed = _parse_string_literal(code, i)
            if parsed is None:
                return ("eof", n)
            i = parsed[3]
            continue
        if ch in "([{":
            depth += 1
            i += 1
            continue
        if ch in ")]}":
            if ch == ")" and depth == 0:
                return ("close", i)
            if depth > 0:
                depth -= 1
            i += 1
            continue
        if ch == "," and depth == 0:
            return ("comma", i)
        i += 1
    return ("eof", n)


def _iter_replace_target_literals(code: str):
    """Yield (target_text, content_start, content_end) for replace(..., "target", ...)."""
    n = len(code)
    i = 0
    while i < n:
        if code.startswith("/*", i):
            end = code.find("*/", i + 2)
            if end == -1:
                return
            i = end + 2
            continue
        if code.startswith("//", i):
            end = code.find("\n", i + 2)
            if end == -1:
                return
            i = end + 1
            continue
        if code[i] == '"':
            parsed = _parse_string_literal(code, i)
            if parsed is None:
                return
            i = parsed[3]
            continue
        if (
            code.startswith("replace", i)
            and (i == 0 or not (code[i - 1].isalnum() or code[i - 1] == "_"))
            and (i + 7 >= n or not (code[i + 7].isalnum() or code[i + 7] == "_"))
        ):
            j = _skip_ws_and_comments(code, i + 7)
            if j < n and code[j] == "(":
                kind, end = _consume_call_arg(code, j + 1)
                if kind == "comma":
                    k = _skip_ws_and_comments(code, end + 1)
                    parsed = _parse_string_literal(code, k)
                    if parsed is not None:
                        target, content_start, content_end, after_string = parsed
                        k = _skip_ws_and_comments(code, after_string)
                        if k < n and code[k] == ",":
                            final_kind, final_end = _consume_call_arg(code, k + 1)
                            i = final_end + 1 if final_end < n else n
                            if final_kind == "close":
                                yield (target, content_start, content_end)
                            continue
                i = end + 1 if end < n else n
                continue
        i += 1


def _rule_replace_unescaped_regex_meta(code, state):
    """Auto-escape replace() target literals that look like literal strings.

    ImageJ macro's replace(str, target, repl) treats target as a Java regex.
    Parentheses, brackets, and dots that look like literal characters to the
    agent throw PatternSyntaxException (unbalanced group / class) or silently
    match the wrong thing. Gemma burned ~10 turns on:

        replace(part, "Median... (radius=", "")
        → PatternSyntaxException: Unclosed group near index 18

    Detection: string literal passed as the target arg of replace() that
    contains one of ( ) [ ] . — without any regex-intent marker
    (\\s, \\d, \\w, \\b, a literal backslash, or a quantifier like * + ? {}).
    Intent markers mean "the agent wrote regex on purpose; leave it alone."

    Returns a (warning, patched_code) tuple so lint_macro swaps in the escaped
    version and attaches a warning — the agent sees the fix without losing a
    turn. Does nothing if nothing needs fixing.
    """
    replacements = []
    fixed_targets = []
    for target, start, end in _iter_replace_target_literals(code):
        if not target:
            continue
        if _REPLACE_REGEX_INTENT.search(target):
            continue
        needs_fix = [c for c in target if c in _REPLACE_LITERAL_META]
        if not needs_fix:
            continue
        escaped = re.sub(r'([()\[\].])', r'\\\\\1', target)
        replacements.append((start, end, escaped))
        fixed_targets.append((target, escaped))
    if not fixed_targets:
        return None
    pieces = []
    last = 0
    for start, end, escaped in replacements:
        pieces.append(code[last:start])
        pieces.append(escaped)
        last = end
    pieces.append(code[last:])
    patched = "".join(pieces)
    detail = "; ".join(
        'replace(..., "{}", ...) -> "{}"'.format(old, new)
        for old, new in fixed_targets
    )
    warning = (
        "replace() target is a Java regex — unescaped (, ), [, ], or . cause "
        "PatternSyntaxException or silent mismatches. Auto-escaped "
        "{} literal(s): {}. If you actually want regex behaviour, add an "
        "intent marker (\\s, \\d, \\w, \\b, a quantifier, or an explicit "
        "backslash) and the rule will skip the target."
    ).format(len(fixed_targets), detail)
    return (warning, patched)


def _rule_unknown_run_command(code, state):
    """Warn when run('<name>') calls a command that isn't in the scanned Fiji set.

    Defense in depth over _rule_hallucinated_run_command's exact-match blocklist.
    Reads agent/.tmp/commands.raw.txt (written by scan_plugins.py) once at
    module import. Fires only as a warning, not a block, because the scan
    may miss dynamically-registered commands, sample-image URLopener entries
    (e.g. "Blobs (25K)"), or plugins installed after the last scan.
    """
    if not _KNOWN_COMMANDS:
        return None
    clean = _strip_comments(code)
    for m in re.finditer(r'run\s*\(\s*"([^"]+)"\s*(?:,|\))', clean):
        name = m.group(1)
        if name in _HALLUCINATED_COMMANDS:
            continue
        if name in _KNOWN_COMMANDS:
            continue
        return (
            'run("{0}") is not in the scanned Fiji command list '
            '(agent/.tmp/commands.raw.txt). If you just installed a plugin, '
            're-run `python scan_plugins.py` from agent/. Otherwise, call '
            'probe_plugin("{0}") to find the real name — most likely a typo '
            'or ellipsis issue (e.g. "Variance" vs "Variance...", '
            '"Band Pass Filter..." vs "Bandpass Filter...").'
        ).format(name)
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


def _rule_ijm_array_literal_syntax(code, state):
    """Block `foo = [...]` Python/JS array literals; IJM uses newArray(...).

    Gemma occasionally writes `results = [];` or `xs = [1, 2, 3];` in macro
    source. The ImageJ macro parser rejects `=[` as "Number or numeric
    function expected". Must skip matches inside string arguments — e.g.
    `run("Convolve...", "kernel=[0 -1 0 -1 4 -1 0 -1 0]")` is legitimate
    — by scrubbing quoted strings before the regex runs.
    """
    clean = _strip_comments(code)
    no_strings = re.sub(r'"[^"]*"', '""', clean)
    m = re.search(r'\b((?:[^\W\d]|_)\w*)\s*=\s*\[', no_strings)
    if not m:
        return None
    name = m.group(1)
    return (
        'Variable "{0}" assigned with `= [...]` syntax. ImageJ macro '
        'language has no `[]` array literals — use newArray(): '
        '`{0} = newArray();` for an empty array or '
        '`{0} = newArray(1, 2, 3);` with initial values.'
    ).format(name)


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


# --- Groovy / Jython rules ------------------------------------------------


def _rule_groovy_bad_filter_imports(code, state):
    """Block `import ij.plugin.filter.<FakeClass>` for known hallucinations.

    Gemma repeatedly invents filter classes that don't exist, e.g.
    `ij.plugin.filter.BlurFilter`, `MedianFilter`, `MeanFilter`,
    `VarianceFilter`, `UnsharpMaskFilter`, `ThresholdFilter`. The real filter
    classes live either at the top of `ij.plugin.filter` (`ParticleAnalyzer`,
    `BackgroundSubtracter`, `ConvolveFilter`, `RankFilters`) or in
    `ij.plugin` (`GaussianBlur`, `UnsharpMask`).
    """
    fake = {
        "BlurFilter", "MedianFilter", "MeanFilter", "VarianceFilter",
        "UnsharpMaskFilter", "ThresholdFilter",
    }
    pattern = r"import\s+ij\.plugin\.filter\.(\w+)\s*;?"
    hits = [m.group(1) for m in re.finditer(pattern, code) if m.group(1) in fake]
    if not hits:
        return None
    return (
        "ij.plugin.filter.{0} does not exist. Hallucinated filter classes: {1}. "
        "Real ones: GaussianBlur (ij.plugin), UnsharpMask (ij.plugin), "
        "RankFilters (ij.plugin.filter — covers median/mean/variance), "
        "ParticleAnalyzer, BackgroundSubtracter, ConvolveFilter. "
        "When in doubt use run_macro — its plugin args are auto-probed."
    ).format(hits[0], ", ".join(sorted(set(hits))))


def _rule_groovy_macro_only_functions(code, state):
    """Block Groovy that uses macro-only functions as if they were Java APIs.

    `setAutoThreshold`, `setThreshold`, `setOption`, `setBatchMode`,
    `waitForUser`, `setBkgBlack`, `setBlackBackground` are ImageJ *macro
    language* functions. They do not exist as `IJ.<name>(...)` methods and
    `IJ.run("<name>", ...)` cannot dispatch them (the lookup hangs opening a
    Macro Error / command-recorder dialog). Offer the Java equivalent or a
    fallback to run_macro so the macro language handles it.
    """
    # IJ.run("<macro-fn>", ...) — bad dispatch
    run_names = {
        "setAutoThreshold": "call ip.setAutoThreshold(...) via ij.process.AutoThresholder",
        "setThreshold":     "call ip.setThreshold(lo, hi, ImageProcessor.RED_LUT)",
        "setOption":        "set the relevant flag on ij.Prefs (e.g. ij.Prefs.blackBackground)",
        "setBatchMode":     "use ij.macro.Interpreter.batchMode directly",
        "waitForUser":      "autonomous mode cannot click — remove the call",
    }
    for name, fix in run_names.items():
        if re.search(r'IJ\.run\s*\(\s*["\']' + re.escape(name) + r'["\']', code):
            return (
                'IJ.run("{0}", ...) will hang — "{0}" is a macro-language '
                'function, not a plugin command. From Groovy: {1}. Or pass the '
                'whole block through run_macro instead.'
            ).format(name, fix)
    # Bare IJ.<macro-fn>(...) calls — the methods do not exist on ij.IJ
    bare_names = {
        "setBkgBlack":        "use `ij.Prefs.blackBackground = true`",
        "setBlackBackground": "use `ij.Prefs.blackBackground = true`",
        "setAutoThreshold":   "call ip.setAutoThreshold(...) on the ImageProcessor",
        "setThreshold":       "call ip.setThreshold(lo, hi, ImageProcessor.RED_LUT)",
    }
    for name, fix in bare_names.items():
        if re.search(r'\bIJ\.' + re.escape(name) + r'\s*\(', code):
            return (
                "IJ.{0}(...) does not exist on ij.IJ. {1} — or pass the block "
                "through run_macro."
            ).format(name, fix)
    return None


def _rule_groovy_hallucinated_run_command(code, state):
    """Block Groovy IJ.run(...) calls that use known hallucinated command names."""
    clean = _strip_comments(code)
    patterns = (
        r'\bIJ\.run\s*\(\s*["\']([^"\']+)["\']',
        r'\bIJ\.run\s*\(\s*(?!["\'])[^,]+,\s*["\']([^"\']+)["\']',
    )
    for pattern in patterns:
        for match in re.finditer(pattern, clean):
            name = match.group(1)
            if name in _HALLUCINATED_COMMANDS:
                return (
                    'IJ.run("{0}", ...) uses a hallucinated command name. {1}'
                ).format(name, _HALLUCINATED_COMMANDS[name])
    return None


GROOVY_RULES = [
    {
        "id": "groovy_bad_filter_imports",
        "severity": "block",
        "description": "Hallucinated ij.plugin.filter.* class imports.",
        "check": _rule_groovy_bad_filter_imports,
    },
    {
        "id": "groovy_hallucinated_run_command",
        "severity": "block",
        "description": "IJ.run('<name>', ...) uses a command name Gemma hallucinates.",
        "check": _rule_groovy_hallucinated_run_command,
    },
    {
        "id": "groovy_macro_only_functions",
        "severity": "block",
        "description": "Macro-only functions used as Groovy Java calls (hangs or errors).",
        "check": _rule_groovy_macro_only_functions,
    },
]


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
        "id": "hallucinated_run_command",
        "severity": "block",
        "description": "run('<name>') names a command that does not exist in base Fiji (Laplacian, DoG, Band-pass / Band Pass Filter, Variance without ellipsis).",
        "check": _rule_hallucinated_run_command,
    },
    {
        "id": "analyze_particles_no_output_flag",
        "severity": "block",
        "description": "Analyze Particles without display/results/add_to_manager leaves nResults at 0 (summarize writes to the Summary window, not Results).",
        "check": _rule_analyze_particles_no_output_flag,
    },
    {
        "id": "replace_unescaped_regex_meta",
        "severity": "autofix",
        "description": "replace() target literals with unescaped ( ) [ ] . — auto-escape unless regex-intent markers present.",
        "check": _rule_replace_unescaped_regex_meta,
    },
    {
        "id": "unknown_run_command",
        "severity": "warn",
        "description": "run('<name>') names a command not in the scanned Fiji command list — likely a typo or missing ellipsis.",
        "check": _rule_unknown_run_command,
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
        "id": "ijm_array_literal_syntax",
        "severity": "block",
        "description": "IJM has no array literals — `foo = [...]` must be `foo = newArray(...)`.",
        "check": _rule_ijm_array_literal_syntax,
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
    """Run every rule on the macro. Return None on pass, else a repair hint.

    Return values:
      * None — all rules passed.
      * str without "WARNING:" prefix — a block-severity rule fired; callers
        must surface the hint to the agent and NOT send the macro to Fiji.
      * str starting with "WARNING:" — only warn-severity rules fired;
        callers run the macro as-is and prepend the warnings to tool feedback.
      * (patched_code, warnings_str) — one or more auto-fix rules rewrote the
        code. Callers send `patched_code` to Fiji and prepend `warnings_str`
        to tool feedback so the agent sees what was changed.

    Auto-fix rules return a (warning, patched_code) tuple from their check
    function; downstream rules then run against the patched code so later
    checks see the fixed version.
    """
    if not isinstance(code, str) or not code.strip():
        return None
    state = _fetch_state()
    warnings = []
    current_code = code
    for rule in RULES:
        try:
            hint = rule["check"](current_code, state)
        except Exception:
            continue
        if not hint:
            continue
        if (
            isinstance(hint, tuple)
            and len(hint) == 2
            and all(isinstance(h, str) for h in hint)
        ):
            warning_msg, patched = hint
            current_code = patched
            warnings.append(warning_msg)
            continue
        if rule["severity"] == "block":
            return hint
        warnings.append(hint)
    if current_code != code:
        body = "\n".join("WARNING: " + w for w in warnings)
        return (current_code, body + "\n(auto-fixed; macro ran with the patched code)")
    if not warnings:
        return None
    body = "\n".join("WARNING: " + w for w in warnings)
    return body + "\n(run anyway? yes/no)"


def lint_script(code, language):
    """Lint Groovy / Jython source submitted to run_script.

    Runs only the Groovy-specific rules and only when language == "groovy"
    (the Jython / JavaScript rule surface is currently empty — Gemma's
    hallucinations cluster on Groovy imports and IJ.* calls). Returns a
    repair-hint string when a blocking rule fires, or None on pass. Macro
    rules are intentionally not applied here: their patterns would misfire on
    legitimate Groovy syntax.
    """
    if not isinstance(code, str) or not code.strip():
        return None
    if not isinstance(language, str) or language.lower() != "groovy":
        return None
    for rule in GROOVY_RULES:
        try:
            hint = rule["check"](code, {})
        except Exception:
            continue
        if hint and rule["severity"] == "block":
            return hint
    return None
