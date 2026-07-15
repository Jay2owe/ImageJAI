#!/usr/bin/env python
"""
Validate ImageJ macro code before sending to the TCP server.

Checks for common mistakes: unmatched quotes, missing semicolons,
unbalanced parentheses, and unknown function names.

Usage:
    from macro_lint import lint_macro
    warnings = lint_macro('run("Blobs (25K)")')
    # -> ["Line 1: missing semicolon at end of statement"]
"""

import re

# Known ImageJ macro functions — extracted from macro-reference.md and common usage.
# This is not exhaustive; macros are flexible so we only warn, never block.
KNOWN_FUNCTIONS = {
    # Core
    "run", "open", "close", "save", "saveAs", "newImage", "rename",
    "selectWindow", "selectImage", "getTitle", "getImageID",
    "getWidth", "getHeight", "getDimensions", "nSlices", "nResults",
    # Threshold
    "setAutoThreshold", "setThreshold", "resetThreshold",
    # Measurement
    "getResult", "setResult", "updateResults", "getStatistics",
    # ROI
    "makeRectangle", "makeOval", "makePolygon", "makeLine", "makePoint",
    "makeSelection", "setSelectionName", "roiManager",
    # Stack
    "Stack.setSlice", "Stack.setChannel", "Stack.setFrame",
    "Stack.getPosition", "Stack.getDimensions",
    # Math
    "imageCalculator",
    # Drawing
    "setColor", "setForegroundColor", "setBackgroundColor",
    "setLineWidth", "drawLine", "drawRect", "fillRect",
    "drawOval", "fillOval", "drawString", "setFont",
    # Display
    "setMinAndMax", "resetMinAndMax", "setOption", "getOption",
    # Utility
    "print", "getDirectory", "getFileList", "File.exists",
    "File.isDirectory", "File.makeDirectory", "File.getName",
    "File.getParent", "File.separator",
    "setBatchMode", "showMessage", "showStatus", "showProgress",
    "waitForUser", "getBoolean", "getString", "getNumber",
    # Variables / info
    "getInfo", "getPixel", "setPixel", "getProfile",
    "Array.show", "Array.getStatistics", "Array.sort",
    "split", "replace", "substring", "indexOf", "lengthOf",
    "startsWith", "endsWith", "matches", "toLowerCase", "toUpperCase",
    "toString", "parseInt", "parseFloat", "d2s",
    "abs", "round", "floor", "ceil", "sqrt", "pow", "log", "exp",
    "sin", "cos", "tan", "atan", "atan2", "PI", "random",
    "IJ.log", "IJ.renameResults",
    # Wait/timing
    "wait",
}


def lint_macro(code):
    """Validate ImageJ macro code and return a list of warnings.

    Args:
        code: string of ImageJ macro code (can be multi-line).

    Returns:
        List of warning strings. Empty list means no issues found.
    """
    warnings = []
    clean, lexical_warnings = _strip_literals_and_comments(code)
    warnings.extend(lexical_warnings)
    clean_lines = clean.split("\n")
    paren_depth = 0

    for i, clean_line in enumerate(clean_lines, 1):
        stripped = clean_line.strip()
        start_depth = paren_depth
        paren_depth += clean_line.count("(") - clean_line.count(")")
        if not stripped or _is_control_flow(stripped):
            continue

        # Only judge statement termination at top level. Multiline calls and
        # strings containing punctuation otherwise produce false positives.
        if start_depth == 0 and paren_depth == 0:
            if _needs_semicolon(stripped) and not stripped.endswith(";"):
                warnings.append("Line %d: missing semicolon at end of statement" % i)

        warnings.extend(_check_function_names(stripped, i))

    for opener, closer, label in (("(", ")", "parentheses"),
                                   ("[", "]", "square brackets"),
                                   ("{", "}", "curly braces")):
        opened = clean.count(opener)
        closed = clean.count(closer)
        if opened != closed:
            warnings.append("Unbalanced %s: %d open, %d close" % (label, opened, closed))

    warnings.extend(_check_safety_rules(code, clean))
    return warnings


def _strip_literals_and_comments(code):
    """Return same-length code with strings/comments blanked and lexer warnings."""
    chars = list(code)
    warnings = []
    state = "normal"
    quote_line = 0
    line = 1
    i = 0
    while i < len(code):
        char = code[i]
        nxt = code[i + 1] if i + 1 < len(code) else ""
        if state == "normal":
            if char == "/" and nxt == "/":
                chars[i] = chars[i + 1] = " "
                state = "line_comment"
                i += 2
                continue
            if char == "/" and nxt == "*":
                chars[i] = chars[i + 1] = " "
                state = "block_comment"
                quote_line = line
                i += 2
                continue
            if char in ('"', "'"):
                state = "double" if char == '"' else "single"
                quote_line = line
                chars[i] = " "
        elif state == "line_comment":
            if char == "\n":
                state = "normal"
            else:
                chars[i] = " "
        elif state == "block_comment":
            if char == "*" and nxt == "/":
                chars[i] = chars[i + 1] = " "
                state = "normal"
                i += 2
                continue
            if char != "\n":
                chars[i] = " "
        else:
            quote = '"' if state == "double" else "'"
            if char == "\\" and nxt:
                chars[i] = " "
                if nxt != "\n":
                    chars[i + 1] = " "
                i += 2
                continue
            if char == quote:
                chars[i] = " "
                state = "normal"
            elif char != "\n":
                chars[i] = " "
        if char == "\n":
            line += 1
        i += 1

    if state in ("double", "single"):
        warnings.append("Line %d: unmatched %s quote" % (
            quote_line, "double" if state == "double" else "single"))
    elif state == "block_comment":
        warnings.append("Line %d: unterminated block comment" % quote_line)
    return "".join(chars), warnings


def _string_arguments(code, clean, function_name):
    """Yield (line, string arguments) for calls found by the lexical scanner."""
    pattern = re.compile(r"\b%s\s*\(" % re.escape(function_name), re.IGNORECASE)
    for match in pattern.finditer(clean):
        opening = clean.find("(", match.start())
        depth = 0
        end = None
        for pos in range(opening, len(clean)):
            if clean[pos] == "(":
                depth += 1
            elif clean[pos] == ")":
                depth -= 1
                if depth == 0:
                    end = pos
                    break
        if end is None:
            continue
        segment = code[opening + 1:end]
        args = []
        for string_match in re.finditer(r'"((?:\\.|[^"\\])*)"|\'((?:\\.|[^\'\\])*)\'', segment, re.DOTALL):
            value = string_match.group(1) if string_match.group(1) is not None else string_match.group(2)
            args.append(re.sub(r"\\([\\\"'])", r"\1", value))
        yield code.count("\n", 0, match.start()) + 1, args


def _check_safety_rules(code, clean):
    """Warn about documented ImageJ operations that mutate shared user state."""
    warnings = []
    for match in re.finditer(r'["\']((?:\\.|[^"\'\\])*)["\']', code, re.DOTALL):
        value = match.group(1)
        if re.search(r"[A-Za-z]:\\|\\[^\\\"']+\\", value):
            line = code.count("\n", 0, match.start()) + 1
            warnings.append("Line %d: macro paths must use forward slashes, including on Windows" % line)
            break
    run_calls = list(_string_arguments(code, clean, "run"))
    for line, args in run_calls:
        if not args:
            continue
        command = args[0].strip().casefold().replace("…", "...")
        options = args[1].casefold() if len(args) > 1 else ""
        if command.startswith("enhance contrast") and re.search(r"(?:^|\s)normalize(?:=true)?(?:\s|$)", options):
            warnings.append(
                "Line %d: Enhance Contrast normalize rewrites pixel values; use setMinAndMax for display-only contrast" % line)
        if command == "close all":
            warnings.append("Line %d: Close All can destroy unrelated user images; close only images created by this workflow" % line)
        if command == "clear results":
            warnings.append("Line %d: Clear Results mutates the user's shared Results table; preserve and restore it" % line)
        if command == "convert to mask":
            # A macro-wide check is deliberately conservative; exact dataflow
            # belongs to the server-side validator.
            if not re.search(r"\bset(?:Auto)?Threshold\s*\(", clean, re.IGNORECASE):
                warnings.append("Line %d: Convert to Mask has no explicit threshold in this macro" % line)
            if not re.search(
                    r"\bsetOption\s*\(\s*[\"']BlackBackground[\"']\s*,\s*true\s*\)",
                    code, re.IGNORECASE):
                warnings.append(
                    "Line %d: Convert to Mask requires setOption(\"BlackBackground\", true) earlier" % line)
        if command.startswith("analyze particles"):
            if not any(call_args and call_args[0].strip().casefold().replace("…", "...") == "convert to mask"
                       for _, call_args in run_calls):
                warnings.append("Line %d: Analyze Particles has no Convert to Mask step in this macro" % line)
            option_tokens = set(re.split(r"[\s=]+", options.strip()))
            if "add" in option_tokens and "add_to_manager" not in option_tokens:
                warnings.append("Line %d: Analyze Particles uses 'add'; the macro keyword is add_to_manager" % line)
        if command == "measure" and not any(
                call_args and call_args[0].strip().casefold() == "clear results"
                for _, call_args in run_calls):
            warnings.append("Line %d: Measure can append to stale Results; call Clear Results first" % line)
        if command.endswith("...") and len(args) == 1:
            warnings.append("Line %d: a dialog command without an argument string can hang automation" % line)
        if len(args) > 2:
            warnings.append("Line %d: run() accepts at most a command and one argument string" % line)

        for arg in args:
            if "\\" in arg:
                warnings.append("Line %d: macro paths must use forward slashes, including on Windows" % line)
                break

    for line, args in _string_arguments(code, clean, "roiManager"):
        if not args:
            continue
        action = args[0].strip().casefold()
        if action == "reset":
            warnings.append("Line %d: roiManager reset deletes the user's ROIs; remove only workflow-created ROIs" % line)
        if action == "measure" and not re.search(
                r"\broiManager\s*\(\s*[\"']reset[\"']\s*\)", code, re.IGNORECASE):
            warnings.append("Line %d: roiManager Measure can include stale ROIs; reset a workflow-owned manager first" % line)

    if re.search(r"\bwaitForUser\s*\(", clean):
        line = code.count("\n", 0, re.search(r"\bwaitForUser\s*\(", clean).start()) + 1
        warnings.append("Line %d: waitForUser freezes an automated session" % line)

    if re.search(r"\b(?:Stack\.|nSlices\b|getDimensions\s*\()", clean) and re.search(
            r"\bsetAutoThreshold\s*\(", clean, re.IGNORECASE):
        threshold_args = [args for _, args in _string_arguments(code, clean, "setAutoThreshold")]
        if any(not args or "stack" not in args[0].casefold() for args in threshold_args):
            warnings.append("setAutoThreshold on a stack should include the 'stack' option")

    if re.search(r"\bprint\s*\([^)]*[+]", clean, re.DOTALL):
        warnings.append("print-concatenated measurements are hard to parse; return them through setResult/updateResults")

    if re.search(r"\bsetBatchMode\s*\(\s*true\s*\)", clean, re.IGNORECASE) and not re.search(
            r"\bsetBatchMode\s*\(\s*false\s*\)", clean, re.IGNORECASE):
        line = code.count("\n", 0, re.search(r"\bsetBatchMode\s*\(\s*true", clean, re.IGNORECASE).start()) + 1
        warnings.append("Line %d: setBatchMode(true) is not restored with setBatchMode(false)" % line)

    if re.search(r"\bsetOption\s*\(\s*\"BlackBackground\"", code, re.IGNORECASE):
        warnings.append("Global setting BlackBackground is changed; capture and restore its previous value")
    return warnings


def _is_control_flow(line):
    """Check if a line is control flow that doesn't need a semicolon."""
    # Opening/closing braces
    if line in ("{", "}"):
        return True
    if line.endswith("{") or line.endswith("}"):
        return True
    # Control keywords
    control_prefixes = ("for", "for(", "if", "if(", "else", "while", "while(",
                        "do", "function", "macro", "return;")
    for prefix in control_prefixes:
        if line.startswith(prefix):
            return True
    return False


def _needs_semicolon(line):
    """Check if a line should end with a semicolon."""
    # Lines ending with braces don't need semicolons
    if line.endswith("{") or line.endswith("}"):
        return False
    # Certain statements and assignments need semicolons
    if line.endswith(")") or line.endswith('"') or line.endswith("'"):
        return True
    # Variable assignments
    if "=" in line and not line.startswith("for") and not line.startswith("if"):
        return True
    # Function calls
    if re.match(r'^[a-zA-Z_][\w.]*\s*\(', line):
        return True
    return False


def _check_quotes(line, line_num):
    """Check for unmatched quotes in a line."""
    warnings = []

    # Count double quotes (ignore escaped ones)
    clean = line.replace('\\"', '')
    double_count = clean.count('"')
    if double_count % 2 != 0:
        warnings.append("Line %d: unmatched double quote" % line_num)

    # Count single quotes (ignore escaped ones)
    clean = line.replace("\\'", '')
    single_count = clean.count("'")
    if single_count % 2 != 0:
        # Single quotes are less common in ImageJ macros — only warn if it
        # looks intentional (not an apostrophe in a string)
        if '"' not in line:  # not inside a string
            warnings.append("Line %d: unmatched single quote" % line_num)

    return warnings


def _check_function_names(line, line_num):
    """Check that function calls use known function names."""
    warnings = []

    # Strip string contents so we don't match text inside quotes
    clean_line = re.sub(r'"[^"]*"', '""', line)
    clean_line = re.sub(r"'[^']*'", "''", clean_line)

    # Find function calls: word( or word.word(
    pattern = r'\b([a-zA-Z_][\w.]*)\s*\('
    for match in re.finditer(pattern, clean_line):
        func_name = match.group(1)

        # Skip common patterns that aren't function calls
        if func_name in ("for", "if", "while", "else", "do", "true", "false"):
            continue

        # Skip variable declarations that look like function calls
        # e.g., "list = getFileList(...)"
        # The function here is getFileList, which we catch separately

        # Check against known functions
        if func_name not in KNOWN_FUNCTIONS:
            # Check if it could be a method on a known object (e.g., Roi.getName)
            parts = func_name.split(".")
            if len(parts) > 1:
                # Dot-notation — skip, too many valid combos
                continue
            # Only warn for functions that look like they should be known
            # (lowercase start = likely variable, uppercase or known prefix = function)
            if func_name[0].isupper() or func_name in ("exit", "exec"):
                warnings.append(
                    "Line %d: unknown function '%s' (may still be valid)" % (line_num, func_name)
                )

    return warnings


def _check_block_balance(code):
    """Check that curly braces are balanced across the entire macro."""
    warnings = []
    # Strip comments and strings
    clean = re.sub(r'//.*', '', code)
    clean = re.sub(r'/\*.*?\*/', '', clean, flags=re.DOTALL)
    clean = re.sub(r'"[^"]*"', '""', clean)

    open_braces = clean.count("{")
    close_braces = clean.count("}")
    if open_braces != close_braces:
        warnings.append(
            "Unbalanced curly braces: %d open, %d close" % (open_braces, close_braces)
        )
    return warnings


if __name__ == "__main__":
    import sys

    # Demo with various test cases
    test_cases = [
        ('run("Blobs (25K)")', "missing semicolon"),
        ('run("Gaussian Blur...", "sigma=2");', "valid"),
        ('setAutoThreshold("Otsu);\nrun("Convert to Mask");', "unmatched quote"),
        ('run("Measure");\nfoo(1, 2;', "unbalanced parens"),
        ('Xyzzy("test");', "unknown function"),
        ('for (i=0; i<10; i++) {\n  run("Measure");\n}', "valid block"),
    ]

    for code, description in test_cases:
        print("--- Test: %s ---" % description)
        print("Code: %s" % repr(code))
        warnings = lint_macro(code)
        if warnings:
            for w in warnings:
                print("  WARNING: %s" % w)
        else:
            print("  (no warnings)")
        print()

    # Also accept code from command line
    if len(sys.argv) > 1:
        code = " ".join(sys.argv[1:])
        print("--- Command line input ---")
        print("Code: %s" % repr(code))
        warnings = lint_macro(code)
        if warnings:
            for w in warnings:
                print("  WARNING: %s" % w)
        else:
            print("  (no warnings)")
