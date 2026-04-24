# Step 08 — Python Wrappers for ROI / Display / Console

## Motivation

Step 07 ships the Java-side TCP commands. This step exposes them
to agents, tuned per agent type:

- **Gemma** benefits from named `@tool` functions with
  pretraining-friendly docstrings (PA-Tool, arXiv 2510.07248).
  The tool name and docstring are *the schema* — weak models
  pattern-match on wording.
- **Claude** uses `ij.py` subcommands. A concise flag-driven CLI
  matches how Claude already interacts (per `agent/CLAUDE.md`).
- **Codex / Gemini** either use `ij.py` directly or register the
  same Gemma tools (they also benefit from named tools but less
  critically).

## End goal

Gemma's tool menu gains three new function-calling tools:

```python
@tool
def get_rois() -> dict:
    """Return the list of ROIs in Fiji's ROI Manager, with
    their names, types (rectangle, polygon, oval, freehand,
    line, point), and bounding boxes. Use this to count ROIs
    or find a specific one by name. Returns {count, selected_index,
    rois: [{index, name, type, bounds: [x, y, width, height]}]}.
    """

@tool
def get_active_layer() -> dict:
    """Return which channel, slice, and frame is currently
    displayed, along with the image's dimensions, composite
    mode, display range (min/max), and LUT name. Use before
    running channel-specific macros to verify which layer is
    active. Returns {active_image, c, z, t, channels, slices,
    frames, composite_mode, active_channels, display_range:
    {min, max}, lut}.
    """

@tool
def get_console(tail: int = 2000) -> dict:
    """Return recent text written to Fiji's stdout and stderr.
    Use this after a Groovy or Jython script fails — stack
    traces appear here, NOT in the ImageJ Log window. The
    `tail` argument controls how many bytes to return (default
    2000). Returns {stdout, stderr, combined, truncated}.
    """
```

Claude's `ij.py` gains three subcommands:

```bash
python ij.py rois                    # list all ROIs
python ij.py display                 # current C/Z/T, LUT, range
python ij.py console                 # recent stdout/stderr
python ij.py console --tail 5000     # more history
```

## Scope

In:
- `agent/gemma4_31b/tools_fiji.py` — three new `@tool`-decorated
  functions wrapping the TCP commands.
- `agent/gemma4_31b/GEMMA.md` — new section explaining when to
  call each, especially `get_console` for Groovy errors (kills
  the `get_log` reflex on script errors).
- `agent/ij.py` — three new subcommands.
- `agent/CLAUDE.md` — new section listing the subcommands.
- Test fixtures in `agent/gemma4_31b/tests/`.

Out:
- Gemini and Codex wrappers — they use `ij.py` directly today.
  Adding per-wrapper `@tool` versions is optional; not blocking.

## Read first

- `agent/gemma4_31b/tools_fiji.py` — existing `@tool` patterns.
  Match the style exactly (docstring format, return-type hints,
  error handling).
- `agent/gemma4_31b/registry.py` — how tools are registered with
  Ollama.
- `agent/ij.py` — argparse structure, `_send`/`_recv` helpers.
- `agent/CLAUDE.md` — command-list table format.
- `agent/gemma4_31b/GEMMA.md` — tool-description format.
- `docs/tcp_upgrade/07_gemma_tools_server.md` — TCP command
  shapes we're wrapping.

## Gemma tool specifics

PA-Tool lesson: tool names should match what a small model would
guess. `get_rois` beats `query_roi_manager_state`. `get_active_layer`
beats `get_display_state` (Gemma doesn't spontaneously use "display
state" as a concept but readily uses "active layer" when
grounded).

Docstrings are long on purpose. Gemma reads the full docstring
on each turn — include the common use case, the return shape,
and one explicit "use this when..." sentence. The improvement
loop showed a ~15% accuracy lift on tool selection when
docstrings started with the imperative ("Return..." / "Use this
to...").

```python
# agent/gemma4_31b/tools_fiji.py

from .registry import tool, tcp_request

@tool
def get_rois() -> dict:
    """Return the list of ROIs in Fiji's ROI Manager, with
    names, types, and bounding boxes. Use this to count ROIs
    or find a specific one by name — DO NOT write a macro that
    queries roiManager('count') for this.

    Returns:
        {"count": int,
         "selected_index": int,
         "rois": [{"index": int, "name": str, "type": str,
                   "bounds": [x, y, width, height]}]}
    """
    resp = tcp_request({"command": "get_roi_state"})
    if not resp.get("ok"):
        return {"error": resp.get("error", "unknown")}
    return resp["result"]


@tool
def get_active_layer() -> dict:
    """Return which channel, slice, and frame is currently
    displayed in Fiji, plus the image's dimensions, composite
    mode, display range, and LUT. Call this BEFORE running a
    channel-specific macro to verify the right layer is active.

    Returns:
        {"active_image": str, "c": int, "z": int, "t": int,
         "channels": int, "slices": int, "frames": int,
         "composite_mode": str, "active_channels": str,
         "display_range": {"min": float, "max": float},
         "lut": str}
    """
    resp = tcp_request({"command": "get_display_state"})
    if not resp.get("ok"):
        return {"error": resp.get("error", "unknown")}
    return resp["result"]


@tool
def get_console(tail: int = 2000) -> dict:
    """Return recent text from Fiji's stdout and stderr. Use
    this when a Groovy or Jython script fails — stack traces
    appear here, NOT in the ImageJ Log window. The `tail`
    argument controls how many bytes to return (default 2000,
    max ~60000).

    Args:
        tail: Number of bytes to return from the end of the
              buffer. Default 2000. Use -1 for the full buffer.

    Returns:
        {"stdout": str, "stderr": str, "combined": str,
         "truncated": bool}
    """
    resp = tcp_request({"command": "get_console", "tail": tail})
    if not resp.get("ok"):
        return {"error": resp.get("error", "unknown")}
    return resp["result"]
```

## Claude CLI subcommands

`agent/ij.py` patches:

```python
# In argparse registration
sub.add_parser("rois", help="List ROIs in Fiji's ROI Manager")
p_display = sub.add_parser("display", help="Show current C/Z/T, LUT, range")
p_console = sub.add_parser("console", help="Show recent stdout/stderr")
p_console.add_argument("--tail", type=int, default=2000)

# Dispatch
elif args.cmd == "rois":
    print(json.dumps(
        _call({"command": "get_roi_state"}),
        indent=2))
elif args.cmd == "display":
    print(json.dumps(
        _call({"command": "get_display_state"}),
        indent=2))
elif args.cmd == "console":
    print(json.dumps(
        _call({"command": "get_console", "tail": args.tail}),
        indent=2))
```

## Doc updates

### `agent/CLAUDE.md`

Append to the "Sending Commands" table:

```
python ij.py rois                      # ROI Manager state
python ij.py display                   # current C/Z/T, LUT, range
python ij.py console                   # recent stdout/stderr
python ij.py console --tail 5000       # more console history
```

And a note in Error Handling:

> **Groovy/Jython errors don't land in `IJ.getLog()`.** They go
> to `System.err`, captured via `ij.py console`. If
> `run_script` returns a bare error, call `ij.py console`
> before retrying.

### `agent/gemma4_31b/GEMMA.md`

New section:

> ### Quick state queries
>
> Three small tools give you Fiji state without writing macros:
>
> - `get_rois()` — how many ROIs, what they're called.
> - `get_active_layer()` — which channel/slice is showing; LUT and
>   display range.
> - `get_console(tail=2000)` — stack traces from failed Groovy
>   or Jython scripts live here. The IJ Log is separate.
>
> Prefer these to writing a macro that queries the same thing —
> they're one round-trip instead of two.

## Tests

- Gemma: tool registration picked up by Ollama, tool calls
  succeed, empty-ROI-manager returns sensible structure.
- Claude: `ij.py rois` prints valid JSON when the manager is
  empty, three entries when populated.
- `ij.py console --tail 1000` after a known-bad Groovy run
  contains the expected exception class name.
- Gemma's existing tool menu still works (no regression on the
  other `@tool` functions from touching `tools_fiji.py`).

## Failure modes

- Renaming tools later breaks Gemma's fine-tuned responses.
  Commit to the names on first ship — `get_rois`,
  `get_active_layer`, `get_console` — and don't churn.
- Claude Code caching out-of-date `ij.py` help text. Not our
  problem to solve; user restarts the wrapper.
- Verbose `get_rois` output (500-ROI images) chews context for
  Gemma. The Java-side 500-ROI cap (step 07) keeps this bounded;
  document the cap in the Gemma docstring.
