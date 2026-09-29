"""Private newline-JSON worker for the approved Python scratchpad.

The reduced language omits file/network/shell helpers. This is defense in
depth for approved host code, not an operating-system security sandbox.
"""
import ast
import base64
import builtins
import contextlib
import io
import json
import sys
import traceback

# Keep installed numerical libraries available without importing neighbouring
# project scripts. Host code still requires explicit user approval.
if sys.path:
    sys.path.pop(0)

WIRE_IN, WIRE_OUT = sys.stdin, sys.stdout


def emit(value):
    WIRE_OUT.write(json.dumps(value, default=str, ensure_ascii=False) + "\n")
    WIRE_OUT.flush()


class Output(io.TextIOBase):
    def __init__(self):
        self.text, self.count = "", 0
    def write(self, value):
        self.count += len(value)
        self.text += value[:max(0, 16384 - len(self.text))]
        return len(value)


def allowed_import(name, *args, **kwargs):
    if name.split(".")[0] not in {"numpy", "scipy", "math", "statistics"}:
        raise ValueError("Only numpy, scipy, math and statistics imports are available")
    return builtins.__import__(name, *args, **kwargs)


def check(code):
    tree = ast.parse(code)
    forbidden = {"open", "eval", "exec", "compile", "input", "globals", "locals", "vars", "getattr", "setattr", "delattr", "breakpoint", "help", "exit", "quit"}
    file_calls = {"load", "save", "savez", "savez_compressed", "loadtxt", "savetxt", "fromfile", "tofile", "memmap", "genfromtxt", "dump", "dumps", "ctypes", "f2py", "testing"}
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and (node.id.startswith("_") or node.id in forbidden):
            raise ValueError("Unavailable Python name: " + node.id)
        if isinstance(node, ast.Attribute) and (node.attr.startswith("_") or node.attr in file_calls):
            raise ValueError("Unavailable Python attribute: " + node.attr)
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name.split(".")[0] not in {"numpy", "scipy", "math", "statistics"}:
                    raise ValueError("Unavailable import: " + alias.name)
        if isinstance(node, ast.ImportFrom) and (node.level or (node.module or "").split(".")[0] not in {"numpy", "scipy", "math", "statistics"}):
            raise ValueError("Unavailable import")
    return tree


def pixels(x=0, y=0, width=256, height=256):
    import numpy as np
    emit({"kind": "host", "command": "get_pixels", "x": x, "y": y, "width": width, "height": height})
    reply = json.loads(WIRE_IN.readline())
    if reply.get("error"):
        raise ValueError(reply["error"])
    result = reply["result"]
    raw = base64.b64decode(result["data"], validate=True)
    shape = (result["height"], result["width"])
    if len(raw) != shape[0] * shape[1] * 4:
        raise ValueError("Pixel payload size does not match its dimensions")
    namespace["image_meta"] = {k: v for k, v in result.items() if k != "data"}
    return np.frombuffer(raw, dtype="<f4").reshape(shape).copy()


safe = {name: getattr(builtins, name) for name in (
    "abs", "all", "any", "bool", "dict", "enumerate", "filter", "float", "int", "len", "list", "map", "max", "min", "pow", "print", "range", "repr", "reversed", "round", "set", "slice", "sorted", "str", "sum", "tuple", "zip", "Exception", "ValueError", "TypeError")}
safe["__import__"] = allowed_import
namespace = {"__builtins__": safe, "pixels": pixels}


def main():
    for line in WIRE_IN:
        output = Output()
        try:
            request = json.loads(line)
            if request.get("kind") != "cell":
                raise ValueError("Expected a cell request")
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                import numpy as np
                import math
                namespace.setdefault("np", np)
                namespace.setdefault("math", math)
                tree = check(request["code"])
                last = tree.body.pop() if tree.body and isinstance(tree.body[-1], ast.Expr) else None
                exec(compile(tree, "<imagejai-python>", "exec"), namespace)
                if last:
                    value = eval(compile(ast.Expression(last.value), "<imagejai-python>", "eval"), namespace)
                    if value is not None:
                        print(repr(value))
            emit({"kind": "result", "ok": True, "output": output.text,
                  "omitted_output_chars": max(0, output.count - len(output.text)),
                  "variables": sorted(k for k in namespace if not k.startswith("_") and k not in {"pixels", "np", "math"})[:128]})
        except BaseException as exc:
            emit({"kind": "result", "ok": False, "output": output.text,
                  "error": f"{type(exc).__name__}: {exc}",
                  "traceback": traceback.format_exc(limit=3)[-2000:]})

if __name__ == "__main__":
    main()
