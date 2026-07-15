#!/usr/bin/env python3
"""Probe ImageJ plugins to discover their parameters and macro syntax.

Usage:
    python probe_plugin.py "Gaussian Blur..."              # probe + cache
    python probe_plugin.py --lookup "Gaussian Blur..."     # cache only
    python probe_plugin.py --search sigma                  # search cached
    python probe_plugin.py --batch "Blur" "Median" "Otsu"  # probe multiple
    python probe_plugin.py --list                          # list all cached
"""

import hashlib
import json
import os
import re
import socket
import sys
import tempfile
import unicodedata

HOST = "localhost"
PORT = 7746
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CACHE_DIR = os.path.join(SCRIPT_DIR, ".tmp", "plugin_args")
CACHE_SCHEMA_VERSION = 2
_FINGERPRINT_CACHE = {"signature": None, "value": None}

# REGRESSION GUARD: Past probe features mixed terse legacy names, CLI-only behavior, and raw TCP cache logic.
# The fix: add clear helper names to __all__, keep compatibility aliases, and test cache plus CLI routing.
__all__ = [
    "cache_key",
    "probe_plugin",
    "lookup_cached_probe",
    "search_cached_probes",
    "list_cached_probes",
    "format_probe_result",
    "probe_plugins",
    "probe",
    "lookup",
    "search",
    "list_cached",
    "format_result",
    "send",
]


def _load_ij_probe_command():
    """Return ij.probe_command when ij.py is importable in this context."""
    try:
        from ij import probe_command
        return probe_command
    except ImportError:
        pass

    try:
        from .ij import probe_command
        return probe_command
    except ImportError:
        return None


def _load_ij_imagej_command():
    """Return ij.imagej_command when ij.py is importable in this context."""
    try:
        from ij import imagej_command
        return imagej_command
    except ImportError:
        pass

    try:
        from .ij import imagej_command
        return imagej_command
    except ImportError:
        return None


def _raw_send(cmd):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(15)
    try:
        s.connect((HOST, PORT))
        s.sendall((json.dumps(cmd) + "\n").encode("utf-8"))
        data = b""
        while True:
            try:
                chunk = s.recv(65536)
                if not chunk:
                    break
                data += chunk
            except socket.timeout:
                break
        return json.loads(data.decode("utf-8"))
    finally:
        try:
            s.close()
        except Exception:
            pass


def send(cmd):
    """Legacy low-level helper for sending a JSON command to ImageJAI."""
    imagej_command = _load_ij_imagej_command()
    if imagej_command is not None:
        return imagej_command(cmd, timeout=15)
    return _raw_send(cmd)


def _request_probe_command(plugin_name):
    probe_command = _load_ij_probe_command()
    if probe_command is not None:
        return probe_command(plugin_name)
    return send({"command": "probe_command", "plugin": plugin_name})


def cache_key(plugin_name):
    """Convert a plugin name to a safe, collision-resistant filename."""
    safe = re.sub(r'[^\w\-]', '_', plugin_name).strip('_')
    if not safe:
        safe = "plugin"
    exact_name = unicodedata.normalize("NFKC", plugin_name).strip()
    digest = hashlib.sha256(exact_name.encode("utf-8")).hexdigest()[:12]
    return "{}-{}.json".format(safe, digest)


def plugin_fingerprint():
    """Return a stable identity for the installed Fiji command inventory."""
    paths = [os.path.join(SCRIPT_DIR, ".tmp", name)
             for name in ("commands.raw.txt", "update_sites.json")]
    stats = []
    for path in paths:
        try:
            stat = os.stat(path)
            stats.append((path, stat.st_size, stat.st_mtime_ns))
        except OSError:
            stats.append((path, None, None))
    signature = (os.environ.get("IMAGEJAI_PLUGIN_FINGERPRINT", ""), tuple(stats))
    if _FINGERPRINT_CACHE["signature"] == signature:
        return _FINGERPRINT_CACHE["value"]

    digest = hashlib.sha256()
    digest.update(b"imagejai-probe-cache-v2\0")
    digest.update(signature[0].encode("utf-8"))
    for path in paths:
        digest.update(os.path.basename(path).encode("utf-8"))
        try:
            with open(path, "rb") as handle:
                for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                    digest.update(chunk)
        except OSError:
            digest.update(b"<missing>")
    value = digest.hexdigest()
    _FINGERPRINT_CACHE.update(signature=signature, value=value)
    return value


def _atomic_json_write(path, payload):
    """Atomically replace *path*, preserving an existing file on failure."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", dir=os.path.dirname(path),
                prefix=os.path.basename(path) + ".", suffix=".tmp",
                delete=False) as handle:
            temp_path = handle.name
            json.dump(payload, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp_path, path)
        temp_path = None
    finally:
        if temp_path:
            try:
                os.unlink(temp_path)
            except OSError:
                pass


def _read_cache_record(path, expected_name=None):
    try:
        with open(path, encoding="utf-8") as handle:
            record = json.load(handle)
    except (OSError, ValueError, TypeError):
        return None
    if not isinstance(record, dict):
        return None
    if record.get("schema_version") != CACHE_SCHEMA_VERSION:
        return None
    if record.get("fingerprint") != plugin_fingerprint():
        return None
    if expected_name is not None and record.get("plugin") != expected_name:
        return None
    result = record.get("result")
    return result if isinstance(result, dict) else None


def _write_cache_record(path, plugin_name, result):
    _atomic_json_write(path, {
        "schema_version": CACHE_SCHEMA_VERSION,
        "plugin": plugin_name,
        "fingerprint": plugin_fingerprint(),
        "result": result,
    })


def probe_plugin(plugin_name, force=False):
    """Probe a plugin to discover its parameters. Caches the result."""
    os.makedirs(CACHE_DIR, exist_ok=True)

    # Check cache first
    cpath = os.path.join(CACHE_DIR, cache_key(plugin_name))
    if not force:
        cached = _read_cache_record(cpath, expected_name=plugin_name)
        if cached is not None:
            return cached

    # Probe via TCP
    resp = _request_probe_command(plugin_name)
    if not resp.get("ok"):
        return {"plugin": plugin_name, "error": resp.get("error", "unknown")}

    result = resp["result"]

    # Cache it
    _write_cache_record(cpath, plugin_name, result)

    return result


def lookup_cached_probe(plugin_name):
    """Look up cached plugin info without probing."""
    cpath = os.path.join(CACHE_DIR, cache_key(plugin_name))
    return _read_cache_record(cpath, expected_name=plugin_name)


def search_cached_probes(keyword):
    """Search cached plugin args by keyword."""
    if not os.path.exists(CACHE_DIR):
        return []
    results = []
    keyword_lower = keyword.lower()
    for fname in sorted(os.listdir(CACHE_DIR)):
        if not fname.endswith(".json"):
            continue
        fpath = os.path.join(CACHE_DIR, fname)
        data = _read_cache_record(fpath)
        if data is None:
            continue
        # Search in plugin name, field labels, macro keys, options
        blob = json.dumps(data).lower()
        if keyword_lower in blob:
            results.append(data)
    return results


def list_cached_probes():
    """List all cached plugin probes."""
    if not os.path.exists(CACHE_DIR):
        return []
    results = []
    for fname in sorted(os.listdir(CACHE_DIR)):
        if not fname.endswith(".json"):
            continue
        fpath = os.path.join(CACHE_DIR, fname)
        data = _read_cache_record(fpath)
        if data is None:
            continue
        results.append(data.get("plugin", fname))
    return results


def format_probe_result(result):
    """Pretty-print a probe result."""
    if not result:
        return "  (no result)"
    if "error" in result:
        return "  ERROR: {}".format(result["error"])

    lines = []
    name = result.get("plugin", "?")
    lines.append("")
    lines.append("=" * 60)
    lines.append("Plugin: {}".format(name))
    lines.append("Dialog: {} ({})".format(
        result.get("dialogTitle", "?"),
        result.get("dialogType", "none")))

    if not result.get("hasDialog"):
        lines.append("  No dialog — runs without parameters.")
        note = result.get("note", "")
        if note:
            lines.append("  Note: {}".format(note))
        lines.append("=" * 60)
        return "\n".join(lines)

    fields = result.get("fields", [])
    if fields:
        lines.append("")
        lines.append("Parameters ({}):" .format(len(fields)))
        for f in fields:
            ftype = f.get("type", "?")
            label = f.get("label", "?")
            key = f.get("macro_key", "?")

            if ftype == "numeric":
                lines.append("  {:10s}  {:<30s}  key={}  default={}".format(
                    "[number]", label, key, f.get("default", "?")))
            elif ftype == "string":
                lines.append("  {:10s}  {:<30s}  key={}  default=\"{}\"".format(
                    "[string]", label, key, f.get("default", "")))
            elif ftype == "checkbox":
                state = "ON" if f.get("default") else "OFF"
                lines.append("  {:10s}  {:<30s}  key={}  default={}".format(
                    "[check]", label, key, state))
            elif ftype == "choice":
                opts = f.get("options", [])
                lines.append("  {:10s}  {:<30s}  key={}  default={}".format(
                    "[choice]", label, key, f.get("default", "?")))
                if opts:
                    lines.append("              options: {}".format(", ".join(opts)))
            elif ftype == "slider":
                lines.append("  {:10s}  {:<30s}  key={}  range={}-{}  default={}".format(
                    "[slider]", label, key,
                    f.get("min", "?"), f.get("max", "?"), f.get("value", "?")))

    syntax = result.get("macro_syntax", "")
    if syntax:
        lines.append("")
        lines.append("Macro syntax (with defaults):")
        lines.append("  {}".format(syntax))

    # If custom dialog, show raw text
    if result.get("dialogType") == "custom":
        lines.append("")
        lines.append("Dialog content:")
        for line in result.get("dialog_text", "").split("\n"):
            lines.append("  {}".format(line))

    lines.append("=" * 60)
    return "\n".join(lines)


def probe_plugins(plugin_names, force=False):
    """Probe multiple plugins and return their results in input order."""
    return [probe_plugin(name, force=force) for name in plugin_names]


# Compatibility names kept for existing agent code and scripts.
probe = probe_plugin
lookup = lookup_cached_probe
search = search_cached_probes
list_cached = list_cached_probes
format_result = format_probe_result


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(0)

    args = sys.argv[1:]

    if args[0] == "--lookup":
        for name in args[1:]:
            result = lookup_cached_probe(name)
            if result:
                print(format_probe_result(result))
            else:
                print("Not cached: {}".format(name))

    elif args[0] == "--search":
        if len(args) < 2:
            print("Usage: python probe_plugin.py --search KEYWORD")
            sys.exit(1)
        results = search_cached_probes(args[1])
        if results:
            print("Found {} cached plugins matching '{}':" .format(len(results), args[1]))
            for r in results:
                print(format_probe_result(r))
        else:
            print("No cached plugins matching '{}'".format(args[1]))

    elif args[0] == "--list":
        cached = list_cached_probes()
        if cached:
            print("Cached plugins ({}):" .format(len(cached)))
            for name in cached:
                print("  - {}".format(name))
        else:
            print("No cached plugin probes yet.")

    elif args[0] == "--batch":
        for name in args[1:]:
            result = probe_plugin(name)
            print(format_probe_result(result))

    elif args[0] == "--force":
        # Force re-probe (ignore cache)
        for name in args[1:]:
            result = probe_plugin(name, force=True)
            print(format_probe_result(result))

    else:
        # Single plugin probe
        name = " ".join(args)
        result = probe_plugin(name)
        print(format_probe_result(result))


if __name__ == "__main__":
    main()
