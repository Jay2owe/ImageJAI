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
    "setMinAndMax", "resetMinAndMax",
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
    lines = code.split("\n")

    for i, line in enumerate(lines, 1):
        stripped = line.strip()

        # Skip empty lines and comments
        if not stripped or stripped.startswith("//"):
            continue

        # Skip lines that are block comment delimiters
        if stripped.startswith("/*") or stripped.startswith("*") or stripped.endswith("*/"):
            continue

        # Skip control flow lines (for, if, else, while, do, function, {, })
        if _is_control_flow(stripped):
            continue

        # Check for unmatched quotes
        quote_warnings = _check_quotes(stripped, i)
        warnings.extend(quote_warnings)

        # Check for missing semicolons
        if _needs_semicolon(stripped) and not stripped.endswith(";"):
            warnings.append("Line %d: missing semicolon at end of statement" % i)

        # Check for unbalanced parentheses
        open_parens = stripped.count("(")
        close_parens = stripped.count(")")
        if open_parens != close_parens:
            warnings.append(
                "Line %d: unbalanced parentheses (%d open, %d close)" % (i, open_parens, close_parens)
            )

        # Check function names
        func_warnings = _check_function_names(stripped, i)
        warnings.extend(func_warnings)

    # Multi-line checks
    warnings.extend(_check_block_balance(code))

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
