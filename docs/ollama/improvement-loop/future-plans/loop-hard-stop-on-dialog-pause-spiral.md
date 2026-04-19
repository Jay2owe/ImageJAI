# Deferred fix — loop.py hard-stop on 3rd identical dialog-pause

## Origin

Round: 2026-04-19-ghost-dialog-queued-macros. Gemma fired ~20 `run_macro`
retries in a row, each hitting the same "Macro paused on modal dialog:
Convert Stack to Binary" error. Meanwhile Fiji was producing the actual
"Objects map of ch2_final-0005" output in the background. Gemma's own
analysis: *"I inadvertently queued up multiple redundant processes."*

The prompt already has a "Two identical errors → stop" rule
(`GEMMA_CLAUDE.md` §"Recovering from errors"). Gemma did not honor it
under confusion. Prior rounds have repeatedly added prompt-level retry
guards that cloud models ignore when the pattern is slightly different
each attempt.

## Why deferred

Waves 1+2 (plugin-output triage marker, reversed backwards triage rule,
Java dialog-pause payload includes `newImages`/`resultsTable`, softer
error prefix) may be enough on their own — if Gemma sees the success
signal inside the tool result itself, the retry spiral shouldn't start.

Revisit this plan only if a future transcript shows Gemma ignoring
Wave 1+2 signals and still spiralling.

## The design

Track the last 3 `run_macro` errors in the tool-dispatch layer
(`agent/gemma4_31b/loop.py`, near `_run_interruptible` / the call-site
that wraps `tools_fiji.run_macro`).

Normalise each error text to compare against prior calls:

- Strip trailing numeric suffixes from image titles
  (`ch2_final-0005` → `ch2_final-N`, `Objects map of X-0012` → `Objects map of X-N`).
- Drop dialog-body content after the first 200 chars.
- Keep dialog title and the "auto-dismissed" marker.

If the 3rd consecutive call in a rolling window returns a
"Macro paused on modal dialog" error with the same normalised key
(same dialog title), intercept the tool result **before it reaches
the model** and rewrite it to:

```json
{
  "ok": false,
  "error": "LOOP BLOCKED: 3rd consecutive dialog-pause on '<title>'. Call get_state and get_results BEFORE any more run_macro — the plugin may already have produced output."
}
```

This forces the turn to pivot away from retries. It is strictly more
reliable than a prompt rule because the cloud model cannot see past
the rewritten tool output.

## Edge cases

- **Reset the counter** when the user takes a turn (new user message) or
  when any non-`run_macro` tool call returns successfully. Two retries
  with a `get_state` in between should not trip the guard.
- **Reset when a run_macro succeeds** — normal iterative refinement
  should not accumulate toward a stop.
- **Don't stop on different dialog titles** — fighting with *multiple*
  unexpected dialogs is different from banging on the same one.

## Implementation sketch

```python
# loop.py, module-level
_DIALOG_PAUSE_RE = re.compile(
    r"Macro paused on modal dialog: ([^—]+?)(?:\s+—|$)"
)
_dialog_pause_history: deque[str] = deque(maxlen=3)

def _dialog_pause_key(result: dict) -> str | None:
    if not isinstance(result, dict):
        return None
    err = result.get("result", {}).get("error") if result.get("ok") else result.get("error")
    if not isinstance(err, str) or "Macro paused on modal dialog" not in err:
        return None
    m = _DIALOG_PAUSE_RE.search(err)
    return m.group(1).strip() if m else None

def _intercept_run_macro_spiral(name: str, result: dict) -> dict:
    if name == "run_macro":
        key = _dialog_pause_key(result)
        if key:
            _dialog_pause_history.append(key)
            if (
                len(_dialog_pause_history) == 3
                and len(set(_dialog_pause_history)) == 1
            ):
                _dialog_pause_history.clear()
                return {
                    "ok": False,
                    "error": (
                        f"LOOP BLOCKED: 3rd consecutive dialog-pause on "
                        f"'{key}'. Call get_state and get_results BEFORE "
                        f"any more run_macro — the plugin may already have "
                        f"produced output."
                    ),
                }
        else:
            _dialog_pause_history.clear()
    elif name != "run_macro":
        _dialog_pause_history.clear()
    return result
```

Wire into the tool dispatch path in `loop.py` (wherever the tool result
is assembled into the `{"role": "tool", ...}` message).

## Test plan

- Run a deliberately failing macro 3 times and verify the 3rd response
  is the `LOOP BLOCKED` stub, not the raw dialog-pause.
- Confirm that calling `get_state` between retries resets the counter.
- Confirm that a successful `run_macro` between retries resets the
  counter.
