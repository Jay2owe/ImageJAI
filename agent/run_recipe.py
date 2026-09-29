#!/usr/bin/env python
"""Validate and run a versioned ImageJAI recipe without silent skips."""

from __future__ import print_function

import json
import os
import re
import sys
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RECIPE_DIR = os.path.join(SCRIPT_DIR, "recipes")

sys.path.insert(0, SCRIPT_DIR)
from recipe_search import (  # noqa: E402
    load_recipe, recipe_directories, step_type, validate_recipe,
)


class FijiTransport(object):
    """Thin adapter over the authenticated, durable ``ij.py`` client."""

    def __init__(self):
        import ij
        self._ij = ij

    def execute_macro(self, code, timeout=None):
        return self._ij.imagej_command({
            "command": "execute_macro", "code": code, "source": "rail:recipe",
        }, timeout=timeout or 120)

    def run_script(self, code, language, timeout=None):
        return self._ij.imagej_command({
            "command": "run_script", "code": code, "language": language,
            "source": "rail:recipe",
        }, timeout=timeout or 180)

    def get_state(self):
        return self._ij.get_state()

    def get_results(self):
        return self._ij.get_results_table()

    def capture(self):
        return self._ij.capture_image()


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


def substitute_slots(macro, values, strict=False):
    # ``$${name}`` is an escaped runtime-language interpolation (for example a
    # Groovy GString), not a recipe parameter.
    escaped = {}

    def protect(match):
        token = "__IMAGEJAI_ESCAPED_SLOT_%d__" % len(escaped)
        escaped[token] = "${%s}" % match.group(1)
        return token

    macro = re.sub(r"\$\$\{([A-Za-z0-9_]+)\}", protect, str(macro))
    pattern = re.compile(r"\$\{([A-Za-z0-9_]+)\}")

    def repl(match):
        name = match.group(1)
        if name not in values:
            if strict:
                raise ValueError("unresolved recipe parameter: %s" % name)
            return match.group(0)
        text = macro_value(values[name])
        if should_bracket(text, macro, match.start(), match.end()):
            return "[" + text.replace("]", "\\]") + "]"
        return text

    rendered = pattern.sub(repl, macro)
    for token, literal in escaped.items():
        rendered = rendered.replace(token, literal)
    return rendered


def step_code(step):
    if not isinstance(step, dict):
        return ""
    return step.get("macro") or step.get("code") or ""


def _parameter_values(recipe):
    return {str(param["name"]): param.get("default")
            for param in normalize_parameters(recipe.get("parameters"))
            if param.get("name")}


def _validate_parameter_values(recipe, values):
    issues = []
    params = normalize_parameters(recipe.get("parameters"))
    known = {str(param.get("name")): param for param in params}
    for name in values:
        if name not in known:
            issues.append("Unknown parameter value: %s" % name)
    for name, param in known.items():
        if name not in values:
            issues.append("Missing parameter value: %s" % name)
            continue
        value = values[name]
        ptype = param.get("type")
        if ptype == "boolean" and not isinstance(value, bool):
            issues.append("Parameter %s must be boolean" % name)
        elif ptype == "numeric" and (not isinstance(value, (int, float)) or isinstance(value, bool)):
            issues.append("Parameter %s must be numeric" % name)
        elif ptype in ("string", "text") and not isinstance(value, str):
            issues.append("Parameter %s must be text" % name)
        elif ptype == "choice" and value not in (param.get("options") or []):
            issues.append("Parameter %s must be one of %s" % (name, param.get("options")))
        bounds = param.get("range")
        if ptype == "numeric" and isinstance(value, (int, float)) and isinstance(bounds, list) and len(bounds) == 2:
            if value < bounds[0] or value > bounds[1]:
                issues.append("Parameter %s is outside range %s" % (name, bounds))
    return issues


def _step_payload(step):
    kind = step_type(step)
    if kind == "macro":
        return step.get("macro") or step.get("code")
    if kind == "script":
        for key in ("script", "groovy", "code"):
            if step.get(key):
                return step[key]
    return None


def _resolve_file(path, recipe_file):
    raw = str(path or "").replace("\\", "/")
    if not raw or os.path.isabs(raw) or ".." in raw.split("/"):
        raise ValueError("script file must be a safe relative path: %s" % path)
    source_dir = os.path.dirname(os.path.abspath(recipe_file))
    roots = [source_dir]
    # Only bundled recipes may resolve a file from the agent root. User recipe
    # directories named "recipes" remain confined to their own directory.
    if os.path.normcase(os.path.realpath(source_dir)) == os.path.normcase(os.path.realpath(RECIPE_DIR)):
        roots.append(SCRIPT_DIR)
    escaped = False
    for root in roots:
        root_real = os.path.normcase(os.path.realpath(root))
        candidate = os.path.join(root, raw)
        candidate_real = os.path.normcase(os.path.realpath(candidate))
        try:
            contained = os.path.commonpath([root_real, candidate_real]) == root_real
        except ValueError:
            contained = False
        if not contained:
            if os.path.isfile(candidate):
                escaped = True
            continue
        if os.path.isfile(candidate_real):
            return os.path.abspath(candidate_real)
    if escaped:
        raise ValueError("script file escapes its allowed recipe root: %s" % path)
    raise ValueError("script file not found: %s" % path)


def _condition_matches(condition, values):
    if condition is None:
        return True
    name = condition["parameter"]
    value = values[name]
    if "equals" in condition:
        return value == condition["equals"]
    if "not_equals" in condition:
        return value != condition["not_equals"]
    return value in condition["in"]


def prevalidate_recipe(recipe, recipe_file, values=None):
    """Validate schema and resolve every dispatcher before Fiji is contacted."""
    issues = list(validate_recipe(recipe))
    values = dict(_parameter_values(recipe) if values is None else values)
    issues.extend(_validate_parameter_values(recipe, values))
    parameter_names = set(values)
    for index, step in enumerate(recipe.get("steps") or [], 1):
        if not isinstance(step, dict):
            continue
        where = "Step %d" % index
        when = step.get("when")
        if isinstance(when, dict) and when.get("parameter") not in parameter_names:
            issues.append("%s condition references unknown parameter: %s" % (where, when.get("parameter")))
        try:
            if step_type(step) == "script" and step.get("file"):
                with open(_resolve_file(step["file"], recipe_file), "r", encoding="utf-8") as handle:
                    code = handle.read()
                substitute_slots(code, values, strict=True)
            else:
                payload = _step_payload(step)
                if payload is not None:
                    substitute_slots(payload, values, strict=True)
                if step_type(step) == "manual" and step.get("instructions"):
                    substitute_slots(step["instructions"], values, strict=True)
        except (IOError, OSError, ValueError) as exc:
            issues.append("%s: %s" % (where, exc))
    return issues


def dry_run_recipe(recipe, recipe_file, values=None):
    """Return a fully resolved dispatch plan without connecting to Fiji."""
    values = dict(_parameter_values(recipe) if values is None else values)
    issues = prevalidate_recipe(recipe, recipe_file, values)
    if issues:
        raise ValueError("; ".join(issues))
    plan = []
    for index, step in enumerate(recipe["steps"], 1):
        kind = step_type(step)
        entry = {
            "index": index,
            "name": step_name(step, index),
            "type": kind,
            "condition_matches": _condition_matches(step.get("when"), values),
            "capture_after": bool(step.get("capture_after")),
        }
        if kind == "script" and step.get("file"):
            path = _resolve_file(step["file"], recipe_file)
            with open(path, "r", encoding="utf-8") as handle:
                payload = handle.read()
            entry["file"] = path
            entry["payload"] = substitute_slots(payload, values, strict=True)
        elif kind in ("macro", "script"):
            entry["payload"] = substitute_slots(_step_payload(step), values, strict=True)
        else:
            entry["instructions"] = substitute_slots(
                step.get("instructions") or step.get("notes"), values, strict=True)
        plan.append(entry)
    return plan


def step_name(step, index):
    if not isinstance(step, dict):
        return "step_%d" % index
    return str(step.get("name") or step.get("id") or step.get("description") or "step_%d" % index)


def recipe_path(name):
    raw = os.path.expanduser(str(name or ""))
    if os.path.isfile(raw):
        return os.path.abspath(raw)

    safe = os.path.basename(name)
    if safe.endswith((".yaml", ".yml")):
        candidates = [safe]
    else:
        candidates = [safe + ".yaml", safe + ".yml"]
    for recipe_dir in recipe_directories():
        for candidate in candidates:
            path = os.path.join(recipe_dir, candidate)
            if os.path.isfile(path):
                return path
    raise RuntimeError("recipe not found: %s" % name)


def _response_error(response, operation):
    if not isinstance(response, dict):
        return "%s returned a non-object response" % operation
    if not response.get("ok"):
        error = response.get("error") or "%s failed" % operation
        if isinstance(error, dict):
            error = error.get("message") or json.dumps(error, sort_keys=True)
        return str(error)
    result = response.get("result")
    if isinstance(result, dict) and result.get("success") is False:
        error = result.get("error") or "%s failed" % operation
        if isinstance(error, dict):
            error = error.get("message") or json.dumps(error, sort_keys=True)
        return str(error)
    return None


def _active_image(response):
    error = _response_error(response, "get_state")
    if error:
        raise RuntimeError(error)
    result = response.get("result") or {}
    if not isinstance(result, dict):
        raise RuntimeError("get_state result is not an object")
    active = result.get("activeImage")
    return active if isinstance(active, dict) else None


def _check_preconditions(preconditions, transport):
    allowed = preconditions.get("image_type") or []
    min_channels = preconditions.get("min_channels", 0)
    requires_image = (
        bool([item for item in allowed if str(item).lower() != "any"])
        or min_channels > 0
        or preconditions.get("needs_stack") is True
        or preconditions.get("needs_time") is True
        or preconditions.get("needs_calibration") is True
    )
    if not requires_image:
        return
    active = _active_image(transport.get_state())
    if active is None:
        raise RuntimeError("precondition failed: an active image is required")
    if allowed and "any" not in [str(item).lower() for item in allowed]:
        actual = str(active.get("type") or "").lower()
        if actual not in [str(item).lower() for item in allowed]:
            raise RuntimeError("precondition failed: image type %s is not one of %s" % (active.get("type"), allowed))
    if int(active.get("channels") or 0) < min_channels:
        raise RuntimeError("precondition failed: requires at least %d channel(s)" % min_channels)
    if preconditions.get("needs_stack") and int(active.get("slices") or 0) <= 1:
        raise RuntimeError("precondition failed: a z-stack is required")
    if preconditions.get("needs_time") and int(active.get("frames") or 0) <= 1:
        raise RuntimeError("precondition failed: a time series is required")
    if preconditions.get("needs_calibration") and not str(active.get("calibration") or "").strip():
        raise RuntimeError("precondition failed: calibrated pixel/voxel dimensions are required")


def _default_acknowledge(prompt):
    try:
        answer = input("%s\nConfirm [y/N]: " % prompt)
    except (EOFError, KeyboardInterrupt):
        return False
    return answer.strip().lower() in ("y", "yes")


def _contracts(value):
    # Bundled v1 recipes retain a few explicit ``validate: ""`` fields to mean
    # that the step has no validation contract. The schema validator applies
    # the same interpretation. Non-empty strings remain blocking
    # acknowledgements, and empty strings inside a contract list remain invalid.
    if value is None or value == "":
        return []
    return value if isinstance(value, list) else [value]


def requires_acknowledgement(recipe):
    """Return True when a recipe needs a human/visual confirmation surface."""
    contract_values = [recipe.get("postconditions"), recipe.get("validation")]
    for step in recipe.get("steps") or []:
        if not isinstance(step, dict):
            continue
        if step_type(step) == "manual" or step.get("decision_point") or step.get("capture_after"):
            return True
        contract_values.extend([step.get("validate"), step.get("validation")])
    for value in contract_values:
        for contract in _contracts(value):
            if isinstance(contract, str):
                if contract.strip():
                    return True
            elif isinstance(contract, dict):
                if contract.get("type") == "acknowledgement" or contract.get("check"):
                    return True
    return False


def _run_contract(contract, transport, acknowledge, where):
    if isinstance(contract, str):
        if not acknowledge(contract):
            raise RuntimeError("%s acknowledgement was not confirmed" % where)
        return
    if "type" not in contract and contract.get("check"):
        prompt = str(contract["check"])
        if contract.get("method"):
            prompt += "\nMethod: " + str(contract["method"])
        if not acknowledge(prompt):
            raise RuntimeError("%s acknowledgement was not confirmed" % where)
        return
    kind = contract["type"]
    if kind == "acknowledgement":
        if not acknowledge(contract["prompt"]):
            raise RuntimeError("%s acknowledgement was not confirmed" % where)
    elif kind == "image_open":
        if _active_image(transport.get_state()) is None:
            raise RuntimeError("%s failed: no active image" % where)
    elif kind == "results_nonempty":
        response = transport.get_results()
        error = _response_error(response, "get_results_table")
        if error:
            raise RuntimeError("%s failed: %s" % (where, error))
        result = response.get("result")
        csv_text = result.get("csv") if isinstance(result, dict) else result
        if not isinstance(csv_text, str) or len([line for line in csv_text.splitlines() if line.strip()]) < 2:
            raise RuntimeError("%s failed: results table is empty" % where)


def _run_contracts(value, transport, acknowledge, where):
    for index, contract in enumerate(_contracts(value), 1):
        _run_contract(contract, transport, acknowledge, "%s[%d]" % (where, index))


def execute_recipe(recipe, recipe_file, values=None, transport=None, acknowledge=None, emit=None):
    """Execute a prevalidated recipe and return a structured receipt.

    Dependency injection keeps the contract testable without Fiji. The complete
    dry-run plan is built before ``transport`` is created or any mutation is
    sent, so a bad later step cannot partially run a recipe.
    """
    values = dict(_parameter_values(recipe) if values is None else values)
    plan = dry_run_recipe(recipe, recipe_file, values)
    transport = transport or FijiTransport()
    acknowledge = acknowledge or _default_acknowledge
    emit = emit or (lambda message: print(message, flush=True))
    _check_preconditions(recipe["preconditions"], transport)
    receipts = []
    total = len(plan)
    for entry, step in zip(plan, recipe["steps"]):
        index = entry["index"]
        name = entry["name"]
        prefix = "[step %d/%d] %s" % (index, total, name)
        if not entry["condition_matches"]:
            emit(prefix + " ... not applicable (condition false)")
            receipts.append({"index": index, "status": "not_applicable", "type": entry["type"]})
            continue
        if step.get("decision_point"):
            prompt = "%s\n%s" % (step.get("description"), step.get("decision_logic"))
            if not acknowledge(prompt):
                raise RuntimeError("%s decision was not confirmed" % prefix)
        started = time.time()
        emit(prefix + " ...")
        if entry["type"] == "manual":
            if not acknowledge(entry["instructions"]):
                raise RuntimeError("%s manual action was not confirmed" % prefix)
        elif entry["type"] == "macro":
            response = transport.execute_macro(entry["payload"], timeout=step.get("timeout_seconds"))
            error = _response_error(response, "macro")
            if error:
                raise RuntimeError("%s failed: %s" % (prefix, error))
        elif entry["type"] == "script":
            language = str(step.get("language") or ("groovy" if step.get("groovy") else "")).lower()
            response = transport.run_script(entry["payload"], language, timeout=step.get("timeout_seconds"))
            error = _response_error(response, "script")
            if error:
                raise RuntimeError("%s failed: %s" % (prefix, error))
        else:  # Defensive guard; validation should make this unreachable.
            raise RuntimeError("%s has no dispatcher" % prefix)
        if step.get("capture_after"):
            error = _response_error(transport.capture(), "capture_image")
            if error:
                raise RuntimeError("%s capture failed: %s" % (prefix, error))
        _run_contracts(step.get("validate"), transport, acknowledge, prefix + ".validate")
        _run_contracts(step.get("validation"), transport, acknowledge, prefix + ".validation")
        elapsed = int((time.time() - started) * 1000)
        emit(prefix + " ... ok (%d ms)" % elapsed)
        receipts.append({"index": index, "status": "ok", "type": entry["type"], "elapsed_ms": elapsed})
    _run_contracts(recipe.get("postconditions"), transport, acknowledge, "postconditions")
    _run_contracts(recipe.get("validation"), transport, acknowledge, "validation")
    return {"ok": True, "recipe": recipe.get("id"), "steps": receipts}


def execute_recipe_file(path, values=None, dry_run=False, transport=None, acknowledge=None, emit=None):
    recipe = load_recipe(path)
    if recipe.get("_error"):
        raise ValueError("recipe load failed: %s" % recipe["_error"])
    if dry_run:
        return {"ok": True, "recipe": recipe.get("id"), "plan": dry_run_recipe(recipe, path, values)}
    return execute_recipe(recipe, path, values=values, transport=transport,
                          acknowledge=acknowledge, emit=emit)


def _parse_parameter(raw, parameter):
    ptype = parameter.get("type")
    if ptype == "boolean":
        lowered = raw.strip().lower()
        if lowered not in ("true", "false"):
            raise ValueError("expected true or false")
        return lowered == "true"
    if ptype == "numeric":
        value = float(raw)
        return int(value) if value.is_integer() else value
    return raw


def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser(description="Validate and execute an ImageJAI recipe")
    parser.add_argument("recipe", help="Recipe id or YAML path")
    parser.add_argument("--dry-run", action="store_true", help="Resolve every step without contacting Fiji")
    parser.add_argument("--param", action="append", default=[], metavar="NAME=VALUE")
    args = parser.parse_args(argv)
    try:
        path = recipe_path(args.recipe)
        recipe = load_recipe(path)
        if recipe.get("_error"):
            raise ValueError(recipe["_error"])
        values = _parameter_values(recipe)
        initial_issues = prevalidate_recipe(recipe, path, values)
        if initial_issues:
            raise ValueError("; ".join(initial_issues))
        params = {str(param.get("name")): param for param in normalize_parameters(recipe.get("parameters"))}
        for assignment in args.param:
            if "=" not in assignment:
                raise ValueError("--param requires NAME=VALUE")
            name, raw = assignment.split("=", 1)
            if name not in params:
                raise ValueError("unknown parameter: %s" % name)
            values[name] = _parse_parameter(raw, params[name])
        if not args.dry_run and not args.param and params:
            values = show_generic_dialog(list(params.values()))
            for name, value in list(values.items()):
                param = params[name]
                ptype = param.get("type")
                already_typed = (
                    (ptype == "boolean" and isinstance(value, bool))
                    or (ptype == "numeric" and isinstance(value, (int, float)) and not isinstance(value, bool))
                    or (ptype in ("string", "text", "choice") and isinstance(value, str))
                )
                if not already_typed:
                    values[name] = _parse_parameter(str(value), param)
        result = execute_recipe_file(path, values=values, dry_run=args.dry_run)
        print(json.dumps(result, indent=2, default=str))
        return 0
    except Exception as exc:
        print("recipe failed: %s" % exc, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
