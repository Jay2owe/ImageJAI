#!/usr/bin/env python
"""Run an ImageJAI recipe one macro step at a time."""

from __future__ import print_function

import json
import os
import re
import socket
import sys
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RECIPE_DIR = os.path.join(SCRIPT_DIR, "recipes")

sys.path.insert(0, SCRIPT_DIR)
from recipe_search import load_recipe  # noqa: E402


HOST = os.environ.get("IMAGEJAI_TCP_HOST", "localhost")
try:
    PORT = int(os.environ.get("IMAGEJAI_TCP_PORT", "7746"))
except ValueError:
    PORT = 7746


def tcp_command(payload, timeout=120):
    data = json.dumps(payload).encode("utf-8") + b"\n"
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    try:
        sock.connect((HOST, PORT))
        sock.sendall(data)
        chunks = []
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                break
            chunks.append(chunk)
            if chunk.endswith(b"\n"):
                break
        if not chunks:
            raise RuntimeError("empty TCP response")
        return json.loads(b"".join(chunks).decode("utf-8"))
    finally:
        try:
            sock.close()
        except Exception:
            pass


def execute_macro(code):
    return tcp_command({
        "command": "execute_macro",
        "code": code,
        "source": "rail:recipe",
    })


def normalize_parameters(raw):
    if not raw:
        return []
    if isinstance(raw, list):
        return [p for p in raw if isinstance(p, dict)]
    if isinstance(raw, dict):
        out = []
        for name in raw.keys():
            value = raw.get(name)
            if isinstance(value, dict):
                param = dict(value)
            else:
                param = {"default": value}
            param.setdefault("name", name)
            out.append(param)
        return out
    return []


def infer_type(param):
    ptype = str(param.get("type") or "").lower()
    if ptype:
        return ptype
    default = param.get("default")
    if isinstance(default, bool):
        return "boolean"
    if isinstance(default, (int, float)):
        return "numeric"
    if param.get("options"):
        return "choice"
    return "string"


def show_generic_dialog(params):
    if not params:
        return {}
    try:
        from ij.gui import GenericDialog  # type: ignore
    except Exception:
        return show_fallback_dialog(params)

    gd = GenericDialog("Run ImageJAI recipe")
    normalized = normalize_parameters(params)
    for param in normalized:
        label = str(param.get("label") or param.get("name"))
        ptype = infer_type(param)
        default = param.get("default")
        if ptype == "boolean":
            gd.addCheckbox(label, bool(default))
        elif ptype == "choice":
            options = [str(v) for v in param.get("options", [])]
            if not options:
                options = [str(default or "")]
            gd.addChoice(label, options, str(default if default is not None else options[0]))
        elif ptype == "numeric":
            try:
                gd.addNumericField(label, float(default if default is not None else 0), 3)
            except Exception:
                gd.addStringField(label, str(default if default is not None else ""))
        else:
            gd.addStringField(label, str(default if default is not None else ""))

    gd.showDialog()
    if gd.wasCanceled():
        raise RuntimeError("recipe parameter dialog cancelled")

    chosen = {}
    for param in normalized:
        name = str(param.get("name"))
        ptype = infer_type(param)
        if ptype == "boolean":
            chosen[name] = bool(gd.getNextBoolean())
        elif ptype == "choice":
            chosen[name] = str(gd.getNextChoice())
        elif ptype == "numeric":
            chosen[name] = gd.getNextNumber()
        else:
            chosen[name] = str(gd.getNextString())
    return chosen


def show_fallback_dialog(params):
    normalized = normalize_parameters(params)
    try:
        import tkinter as tk
        from tkinter import ttk
    except Exception:
        chosen = {}
        for param in normalized:
            chosen[str(param.get("name"))] = param.get("default", "")
        print("[params] GenericDialog unavailable; using recipe defaults", flush=True)
        return chosen

    try:
        root = tk.Tk()
    except Exception:
        chosen = {}
        for param in normalized:
            chosen[str(param.get("name"))] = param.get("default", "")
        print("[params] dialog unavailable; using recipe defaults", flush=True)
        return chosen
    root.title("Run ImageJAI recipe")
    values = {}
    row = 0
    for param in normalized:
        name = str(param.get("name"))
        label = str(param.get("label") or name)
        ttk.Label(root, text=label).grid(row=row, column=0, sticky="w", padx=8, pady=4)
        ptype = infer_type(param)
        default = param.get("default", "")
        if ptype == "boolean":
            var = tk.BooleanVar(value=bool(default))
            widget = ttk.Checkbutton(root, variable=var)
        elif ptype == "choice":
            options = [str(v) for v in param.get("options", [])] or [str(default)]
            var = tk.StringVar(value=str(default if default is not None else options[0]))
            widget = ttk.Combobox(root, values=options, textvariable=var, state="readonly")
        else:
            var = tk.StringVar(value=str(default if default is not None else ""))
            widget = ttk.Entry(root, textvariable=var, width=36)
        values[name] = var
        widget.grid(row=row, column=1, sticky="ew", padx=8, pady=4)
        row += 1

    cancelled = {"value": True}

    def ok():
        cancelled["value"] = False
        root.destroy()

    def cancel():
        root.destroy()

    buttons = ttk.Frame(root)
    buttons.grid(row=row, column=0, columnspan=2, sticky="e", padx=8, pady=8)
    ttk.Button(buttons, text="Cancel", command=cancel).pack(side="right", padx=4)
    ttk.Button(buttons, text="OK", command=ok).pack(side="right", padx=4)
    root.columnconfigure(1, weight=1)
    root.mainloop()

    if cancelled["value"]:
        raise RuntimeError("recipe parameter dialog cancelled")
    return {name: var.get() for name, var in values.items()}


def macro_value(value):
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        if isinstance(value, float) and value.is_integer():
            return str(int(value))
        return str(value)
    text = str(value)
    return text.replace("\\", "\\\\").replace('"', '\\"')


def should_bracket(text, macro, start, end):
    if not re.search(r"\s", text):
        return False
    before = macro[:start]
    line_start = before.rfind("\n") + 1
    line_prefix = before[line_start:]
    if "run(" not in line_prefix:
        return False
    prev = before[-1:] if before else ""
    after = macro[end:end + 1]
    if prev == "[" or after == "]":
        return False
    token_start = max(line_prefix.rfind(" "), line_prefix.rfind("\""), line_prefix.rfind("'"))
    token = line_prefix[token_start + 1:]
    return token.endswith("=")


def substitute_slots(macro, values):
    pattern = re.compile(r"\$\{([A-Za-z0-9_]+)\}")

    def repl(match):
        name = match.group(1)
        if name not in values:
            return match.group(0)
        text = macro_value(values[name])
        if should_bracket(text, macro, match.start(), match.end()):
            return "[" + text.replace("]", "\\]") + "]"
        return text

    return pattern.sub(repl, macro)


def step_code(step):
    if not isinstance(step, dict):
        return ""
    return step.get("macro") or step.get("code") or ""


def step_name(step, index):
    if not isinstance(step, dict):
        return "step_%d" % index
    return str(step.get("name") or step.get("id") or step.get("description") or "step_%d" % index)


def recipe_path(name):
    safe = os.path.basename(name)
    if safe.endswith((".yaml", ".yml")):
        candidates = [safe]
    else:
        candidates = [safe + ".yaml", safe + ".yml"]
    for candidate in candidates:
        path = os.path.join(RECIPE_DIR, candidate)
        if os.path.isfile(path):
            return path
    raise RuntimeError("recipe not found: %s" % name)


def main(argv=None):
    argv = argv or sys.argv[1:]
    if len(argv) != 1:
        print("usage: python agent/run_recipe.py <recipe-name>", file=sys.stderr)
        return 2

    path = recipe_path(argv[0])
    recipe = load_recipe(path)
    if not recipe or recipe.get("_error"):
        print("recipe load failed: %s" % (recipe.get("_error") if recipe else path), file=sys.stderr)
        return 2

    params = normalize_parameters(recipe.get("parameters"))
    try:
        chosen = show_generic_dialog(params)
    except Exception as exc:
        print("[params] cancelled or failed: %s" % exc, flush=True)
        return 1

    steps = recipe.get("steps") or []
    if not isinstance(steps, list) or not steps:
        print("recipe has no steps: %s" % argv[0], file=sys.stderr)
        return 1

    total = len(steps)
    for index, step in enumerate(steps, 1):
        name = step_name(step, index)
        code = step_code(step)
        if not code:
            print("[step %d/%d] %s ... skipped (no macro)" % (index, total, name), flush=True)
            continue
        macro = substitute_slots(code, chosen)
        t0 = time.time()
        print("[step %d/%d] %s ..." % (index, total, name), flush=True)
        try:
            resp = execute_macro(macro)
        except Exception as exc:
            dt = int((time.time() - t0) * 1000)
            print("[step %d/%d] %s ... FAIL (%d ms): %s" % (index, total, name, dt, exc), flush=True)
            return 1
        dt = int((time.time() - t0) * 1000)
        if not resp.get("ok"):
            print("[step %d/%d] %s ... FAIL (%d ms): %s" % (
                index, total, name, dt, resp.get("error", "TCP command failed")), flush=True)
            return 1
        result = resp.get("result") or {}
        if isinstance(result, dict) and not result.get("success", True):
            error = result.get("error", "macro failed")
            if isinstance(error, dict):
                error = error.get("message") or json.dumps(error)
            print("[step %d/%d] %s ... FAIL (%d ms): %s" % (index, total, name, dt, error), flush=True)
            return 1
        print("[step %d/%d] %s ... ok (%d ms)" % (index, total, name, dt), flush=True)

    return 0


if __name__ == "__main__":
    sys.exit(main())
