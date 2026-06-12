"""Auto-probe — verify macro run() arguments against each plugin's real dialog.

Runs after safety and lint and before the macro reaches Fiji. For every
run("Plugin...", "args") call in the macro, look up the plugin's parameter
schema. If the schema is not cached, ask the TCP server to probe the
dialog and cache the result. Any argument name in the macro that is not
in the schema gets flagged with a repair hint — and the macro is rejected
before it reaches Fiji.

Probing the dialog is the only reliable way to know the real macro keys.
Gemma invents plausible-looking names like "sigma_value" or "particle_size"
that do not exist, the plugin silently ignores them, and the macro
produces a wrong result with no error. Catching this before the run is
the entire point of the module.

Fail-closed policy (updated): if the plugin name ends in "..." — ImageJ's
convention for "this menu item opens a dialog" — and the probe completes
but yields no introspectable schema (custom Swing dialog, server error),
the macro is rejected. A run() with wrong args against such a plugin
will hang on a modal dialog; blocking it up-front is cheaper than
timing out. The only "cannot verify, allow through" case is when the
TCP server is unreachable — the macro will fail cleanly at send-time,
so blocking here would be redundant.
"""

from __future__ import annotations

import json
import os
import re

from .registry import send


CACHE_DIR = os.path.abspath(
    os.path.join(os.path.dirname(__file__), ".plugin_cache")
)


_RUN_CALL_RE = re.compile(
    r'run\s*\(\s*"([^"]*)"\s*(?:,\s*"([^"]*)")?\s*\)'
)


def _strip_comments(code: str) -> str:
    """Drop // line comments and /* block */ comments so a run() inside a comment is not picked up."""
    out = re.sub(r"/\*.*?\*/", "", code, flags=re.DOTALL)
    out = re.sub(r"//[^\n]*", "", out)
    return out


def _slug(plugin: str) -> str:
    """Convert a plugin name into a safe filename for the on-disk cache."""
    safe = re.sub(r"[^\w\-]", "_", plugin).strip("_")
    if not safe:
        safe = "plugin"
    return safe + ".json"


def _ensure_cache_dir() -> None:
    """Create the cache folder on first use. Called from get_schema, never at import."""
    os.makedirs(CACHE_DIR, exist_ok=True)


def _read_cache(plugin: str) -> dict:
    """Return a cached schema dict for the plugin, or None when no usable cache exists.

    Args:
        plugin: The exact plugin name as it appears inside run("..."), e.g. "Gaussian Blur...".
    """
    path = os.path.join(CACHE_DIR, _slug(plugin))
    if not os.path.exists(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return None
    if not isinstance(data, dict):
        return None
    return data


def _write_cache(plugin: str, schema: dict) -> None:
    """Persist a schema dict under the cache folder. Silent on I/O failure.

    Args:
        plugin: The exact plugin name used as the cache key.
        schema: The probe_command result dict to store.
    """
    _ensure_cache_dir()
    path = os.path.join(CACHE_DIR, _slug(plugin))
    try:
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(schema, fh, indent=2)
    except OSError:
        pass


def parse_run_calls(code: str) -> list:
    """Extract every run("Plugin", "args") call from a macro.

    Comments are stripped first so a run() inside // or /* ... */ is ignored.
    Calls with only the plugin name get an empty args_string.

    Args:
        code: ImageJ macro source as a single string.
    """
    clean = _strip_comments(code)
    results = []
    for match in _RUN_CALL_RE.finditer(clean):
        plugin = match.group(1)
        args = match.group(2) if match.group(2) is not None else ""
        results.append({"plugin": plugin, "args_string": args})
    return results


def parse_args_string(s: str) -> dict:
    """Parse an ImageJ macro argument string into a {name: value} dict.

    Tokens with '=' split into key and value; bracketed values like
    method=[Mean gray] are supported and keep their internal spaces.
    Tokens without '=' are flags and map to True. Whitespace-only input
    returns an empty dict.

    Args:
        s: Argument string of the form 'sigma=2 stack rolling=50 light'.
    """
    result: dict = {}
    if not isinstance(s, str):
        return result
    i = 0
    n = len(s)
    while i < n:
        while i < n and s[i].isspace():
            i += 1
        if i >= n:
            break
        key_start = i
        while i < n and not s[i].isspace() and s[i] != "=":
            i += 1
        key = s[key_start:i]
        if i < n and s[i] == "=":
            i += 1
            if i < n and s[i] == "[":
                i += 1
                val_start = i
                while i < n and s[i] != "]":
                    i += 1
                value = s[val_start:i]
                if i < n:
                    i += 1
            else:
                val_start = i
                while i < n and not s[i].isspace():
                    i += 1
                value = s[val_start:i]
            if key:
                result[key] = value
        else:
            if key:
                result[key] = True
    return result


def _probe(plugin: str) -> tuple:
    """Return (schema_or_None, reason) for a plugin.

    reason is one of:
      - "cache_hit"           — schema came from disk cache.
      - "probed_ok"           — live probe succeeded, schema cached.
      - "tcp_unreachable"     — could not reach Fiji's TCP server.
      - "probe_server_error"  — server replied but the probe command failed.
      - "probe_no_dialog"     — plugin ran to completion without a dialog.
      - "probe_custom_dialog" — dialog opened but is not a GenericDialog we can introspect.
    """
    cached = _read_cache(plugin)
    if cached is not None:
        return cached, "cache_hit"
    try:
        resp = send("probe_command", plugin=plugin)
    except Exception:
        return None, "tcp_unreachable"
    if not isinstance(resp, dict):
        return None, "probe_server_error"
    if not resp.get("ok"):
        return None, "probe_server_error"
    result = resp.get("result")
    if not isinstance(result, dict):
        return None, "probe_server_error"
    if not result.get("hasDialog"):
        return None, "probe_no_dialog"
    fields = result.get("fields")
    if not isinstance(fields, list):
        return None, "probe_custom_dialog"
    _write_cache(plugin, result)
    return result, "probed_ok"


def get_schema(plugin: str) -> dict:
    """Return the parameter schema for a plugin, probing and caching on first sight.

    Thin wrapper over _probe that drops the reason. Returns None on any
    failure mode so callers that only care about the schema can keep the
    old shape. Use _probe directly when you need to distinguish between
    "cannot reach Fiji" and "probe actively failed".

    Args:
        plugin: The exact plugin name as it appears inside run("..."), e.g. "Gaussian Blur...".
    """
    schema, _reason = _probe(plugin)
    return schema


def check_macro(code: str) -> str:
    """Verify every run() call in the macro uses only real argument names.

    Iterates run() calls from parse_run_calls. Calls with an empty argument
    string are skipped — nothing to check. For the rest, fetch the schema
    via get_schema; a None schema means we cannot verify this call, so
    skip it. When a schema is available, compare every parsed argument
    name against the schema's macro_key set; if any are not in the schema,
    return a repair hint naming the plugin, the invalid argument(s), and
    the valid argument names from the schema. Returns None when every
    run() either passes or cannot be verified.

    Args:
        code: ImageJ macro source to inspect before it reaches Fiji.
    """
    if not isinstance(code, str) or not code.strip():
        return None
    for call in parse_run_calls(code):
        plugin = call.get("plugin", "")
        args_string = call.get("args_string", "")
        if not args_string:
            continue
        schema, reason = _probe(plugin)
        if schema is None:
            # Fiji unreachable → let the macro fail at send-time.
            if reason == "tcp_unreachable":
                continue
            # Plugin didn't open a dialog → wrong args will be ignored
            # silently but won't hang. Not ideal but not the user's
            # current pain point.
            if reason == "probe_no_dialog":
                continue
            # Plugin name ends in "..." → ImageJ convention for "opens a
            # dialog". We could not introspect that dialog (custom Swing,
            # server error). Running with unverifiable args risks hanging
            # the session on a modal popup. Block now.
            if plugin.endswith("..."):
                return _build_unprobeable_hint(plugin, reason)
            continue
        valid_keys = []
        for field in schema.get("fields", []):
            if not isinstance(field, dict):
                continue
            key = field.get("macro_key")
            if isinstance(key, str) and key:
                valid_keys.append(key)
        args = parse_args_string(args_string)
        # ImageJ's macro recorder accepts the prefix of a label before
        # a parenthetical suffix — e.g. schema key "sigma_(radius)" also
        # matches the written form "sigma=2". Build an alias set that
        # includes the prefix-before-"_(" for any such key.
        valid_aliases = set()
        for k in valid_keys:
            low = k.lower()
            valid_aliases.add(low)
            if "_(" in low:
                valid_aliases.add(low.split("_(", 1)[0].rstrip("_"))
        invalid = [name for name in args.keys() if name.lower() not in valid_aliases]
        if invalid:
            return _build_hint(plugin, invalid, valid_keys)
    return None


def collect_schema_hints(code: str) -> dict[str, list[str]]:
    """Return real macro keys for every probeable run() plugin in the macro."""
    hints: dict[str, list[str]] = {}
    if not isinstance(code, str) or not code.strip():
        return hints
    for call in parse_run_calls(code):
        plugin = call.get("plugin", "")
        args_string = call.get("args_string", "")
        if not plugin or plugin in hints:
            continue
        # No second argument string means there are no macro keys to hint.
        # Probing such commands is pure downside: sample-image openers like
        # run("Blobs (25K)"); do not need schema help, but probe_command may
        # try to execute them and trigger spurious popups or side effects.
        if not args_string:
            continue
        schema = get_schema(plugin)
        if schema is None:
            continue
        valid_keys = []
        for field in schema.get("fields", []):
            if not isinstance(field, dict):
                continue
            key = field.get("macro_key")
            if isinstance(key, str) and key:
                valid_keys.append(key)
        if valid_keys:
            hints[plugin] = sorted(set(valid_keys), key=str.lower)
    return hints


def _build_hint(plugin: str, invalid: list, valid: list) -> str:
    """Format the repair hint sent back to the agent when a run() fails validation."""
    invalid_str = ", ".join(invalid)
    if valid:
        valid_str = ", ".join(sorted(set(valid)))
    else:
        valid_str = "(none — the probed dialog exposes no macro arguments)"
    return (
        "run(\"{}\", \"...\") uses argument name(s) that do not exist in "
        "the plugin's dialog: {}. Valid argument names for this plugin are: "
        "{}. Probe the plugin with probe_plugin before re-running, or use "
        "one of the listed names."
    ).format(plugin, invalid_str, valid_str)


def _build_unprobeable_hint(plugin: str, reason: str) -> str:
    """Format the rejection sent back when a dialog-opening plugin cannot be introspected."""
    reason_text = {
        "probe_custom_dialog": (
            "it uses a custom Swing dialog rather than ImageJ's GenericDialog"
        ),
        "probe_server_error": (
            "the probe command failed on the server"
        ),
    }.get(reason, "the plugin could not be introspected")
    return (
        "run(\"{plugin}\", \"...\") was blocked: the plugin name ends in "
        "'...' (meaning it opens a modal dialog) and {reason}. Running "
        "with unverifiable arguments risks hanging the macro on an "
        "un-clickable popup. Call probe_plugin(\"{plugin}\") first to "
        "inspect the dialog, then either use interact_dialog to drive it "
        "manually or run_script to call the plugin's Java API directly."
    ).format(plugin=plugin, reason=reason_text)
