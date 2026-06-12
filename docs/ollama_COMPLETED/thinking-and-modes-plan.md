# Plan — Thinking-mode switch + per-mode sampling profiles

For the Gemma-4-31B agent wrapper (`agent/gemma4_31b/`), running
`gemma4:31b-cloud` via the Ollama Python client.

## Goal

Tune the wrapper so:

1. **Thinking mode** (Ollama's reasoning pass) can be on, off, or chosen automatically per turn — with a user lock override via slash command.
2. **Sampling parameters** (temperature, top_p, top_k) change by *mode* — `tool` vs `plan` vs `recover` vs `explain` vs `recipe` — instead of one global `temperature=0.2`.

Both features share the same architecture: a `TurnMode` resolved once per user turn, overridable by the user, re-evaluated only on tool failure.

## Constraints (from codex review and GEMMA_CLAUDE.md)

- **Use Ollama's native `think` parameter** in `options` — *not* a `<|think|>` prompt prefix. The Python client and REST API expose it. If `gemma4:31b-cloud` rejects it, fall back gracefully (don't crash) and log one friction entry.
- **State lives in `loop.run()`**, not `registry.py` (which is tool transport only).
- **Resolve once per user turn.** Do not re-check inside `_one_turn()`'s model-round loop. Exception: re-evaluate when `_format_macro_failure_note` fires, or when a `_TurnAborted` / timeout surfaces — this is what flips `tool` → `recover` mid-turn.
- **Don't add `threshold_shootout` disagreement as a thinking trigger** — contradicts GEMMA_CLAUDE.md:54 ("shootout's count IS the count — stop").
- **Don't add `probe_plugin` contradiction as a thinking trigger** — that's a hard-stop situation, not a deliberation opportunity. Out of scope for this plan.
- **Don't keyword `why`** as a decision-word — it's explain-mode territory.

---

## Architecture

### 1. Mode definitions (new constants near `loop.py:73`)

```python
# loop.py — next to TEMPERATURE, NUM_CTX
SAMPLING_PROFILES: dict[str, dict] = {
    "tool":    {"temperature": 0.25, "top_p": 0.90, "top_k": 30,  "thinking": False},
    "plan":    {"temperature": 0.60, "top_p": 0.92, "top_k": 50,  "thinking": True},
    "recover": {"temperature": 0.30, "top_p": 0.90, "top_k": 35,  "thinking": True},
    "explain": {"temperature": 0.60, "top_p": 0.95, "top_k": 64,  "thinking": False},
    "recipe":  {"temperature": 0.40, "top_p": 0.92, "top_k": 40,  "thinking": False},
}
DEFAULT_MODE = "tool"
```

Keep `TEMPERATURE` (loop.py:73) as a legacy fallback used only if `SAMPLING_PROFILES` lookup fails.

### 2. Session state (inside `loop.run()` at loop.py:1301)

Add two dicts alongside `ctx_state`:

```python
mode_state = {"lock": None}         # None = auto; else one of SAMPLING_PROFILES keys
think_state = {"lock": None}        # None = follow mode's thinking default; True/False = forced
```

Pass both into the turn worker, and read them from `_one_turn` via a new `turn_config` dict (below).

### 3. Per-turn resolution (new function in `loop.py`)

```python
def _resolve_turn_config(
    user_text: str,
    mode_lock: str | None,
    think_lock: bool | None,
    last_turn_had_failure: bool,
) -> dict:
    """Pick mode + thinking once at the start of a user turn.

    Returns: {"mode": str, "thinking": bool, "sampling": dict}
    """
```

**Heuristics for `auto` (when mode_lock is None):**

- Default: `tool`.
- User text matches decision words (`should`, `which`, `compare`, `better`, `recommend`, `choose between`): → `plan`.
- User text matches explain words (`why`, `explain`, `what does`, `how does`, `what is the`): → `explain`.
- User text matches `/save-recipe` via `_PHASE9_SLASH_RE` (already at loop.py:194): → `recipe`.
- `last_turn_had_failure` is True (carried from previous turn's tool failure): → `recover`.

**Thinking resolution:**

- If `think_lock is True` or `think_lock is False`: honour the lock.
- Else: use `SAMPLING_PROFILES[mode]["thinking"]`.

Compile decision-word and explain-word regex constants near the existing `_PHASE9_POSITIVE_RE` at loop.py:190. Word-boundary anchored, case-insensitive.

### 4. Passing config into `_one_turn()`

Change the signature at loop.py:1003:

```python
def _one_turn(
    model: str,
    messages: list,
    tools: list,
    tool_map: dict,
    turn_config: dict,              # NEW — mode + thinking + sampling
    abort_event: threading.Event | None = None,
) -> tuple[str, bool]:              # NEW — return (reply_text, had_failure)
```

Replace the hard-coded `options` at loop.py:1035:

```python
options = {
    **turn_config["sampling"],       # unpacks temperature/top_p/top_k
    "num_ctx": NUM_CTX,
}
if turn_config["thinking"]:
    options["think"] = True          # Ollama native; safe to include even if cloud ignores
resp = _chat_interruptible(
    abort_event=abort_event,
    model=model,
    messages=messages,
    tools=tools,
    stream=False,
    options=options,
)
```

**Do NOT** re-resolve mode inside the model-round `while` loop. The same `turn_config` is used for every internal round of this turn.

**Exception — mid-turn flip to `recover`:** when a tool call returns a string starting `"ERROR:"` (loop.py:1085) OR when `_format_macro_failure_note` would fire (macro.completed with success=False), mutate `turn_config` in place to the `recover` profile and set `had_failure=True`. This takes effect on the *next* model round. Only flip once per turn (don't oscillate).

Return both the reply string and `had_failure` so `run()` can feed it into the next turn's resolution.

### 5. Slash commands (add to `_SLASH_COMMANDS` at loop.py:195)

```python
("/think [on|off|auto]",  "Force thinking mode on/off, or return to auto. No arg = show state."),
("/mode [<name>|auto]",   "Lock sampling mode (tool/plan/recover/explain/recipe) or return to auto."),
```

Handler block inside `run()` at loop.py:1423 (next to `/interrupt`):

- `/think` with no arg: print current lock + what would be used for the next turn.
- `/think on` | `/think off` | `/think auto`: set `think_state["lock"]`.
- `/mode` with no arg: print current lock + profile table.
- `/mode <name>`: validate against `SAMPLING_PROFILES.keys()` + `auto`, update `mode_state["lock"]`.
- All print a one-line confirmation via `_console_emit`.
- Do not queue a prompt — `continue` after handling.

Add entries to `_build_slash_completer` names list (derives from `_SLASH_COMMANDS` automatically — no change needed if names are the first token).

### 6. Telemetry

When a turn starts and when it mid-flips to `recover`, append a line to `safety.friction_log` (or the existing turn log if one exists):

```python
{"event": "turn_config", "mode": <mode>, "thinking": <bool>, "source": "lock"|"auto"|"recover"}
```

This lets the improvement-loop skill correlate drift with sampling later.

### 7. `[triage]`-banner-style status injection

Only when a lock is active or the mode changed *from the previous turn's auto-pick*, emit one line before the model call:

```
[mode] <name> (locked)    # if mode_state["lock"] is not None
[mode] <name>             # if auto-picked and differs from previous auto-pick
[thinking] on (locked)    # if think_state["lock"] is not None
```

Append as `{"role": "system", "content": "..."}` to `messages` right before the `_turn_worker` thread starts (in `run()` around loop.py:1370, next to the existing phase6/filter/phase9 notes). Suppress when identical to the prior turn's banner to avoid training the model to ignore it.

### 8. Failure carry-over

After `_one_turn` returns, capture `had_failure` into a `last_turn_had_failure: bool` held in `run()`. Feed into the next `_resolve_turn_config` call. Reset to False on every new user-initiated turn where no failure signal arrives.

---

## CLI flags (in `__main__.py`)

Add optional flags:

```python
parser.add_argument("--mode", default="auto",
                    choices=["auto", "tool", "plan", "recover", "explain", "recipe"],
                    help="Initial mode lock. 'auto' = per-turn heuristic.")
parser.add_argument("--think", default="auto",
                    choices=["auto", "on", "off"],
                    help="Initial thinking lock.")
```

Pass through to `loop.run(..., initial_mode_lock=..., initial_think_lock=...)`. `run()` writes them into `mode_state`/`think_state` before the main loop.

---

## What does NOT change

- `registry.py` — untouched.
- `GEMMA_CLAUDE.md` / `GEMMA.md` — untouched by this patch. A follow-up may add a short "when the user asks you to think harder, they've typed /think on" note, but that's optional.
- Tool definitions, tool dispatch, event plumbing — untouched.
- The `MAX_IDENTICAL_TOOL_REPEATS` brake (loop.py:74) — untouched. Keep it.
- Vision handling (loop.py:1099–1103) — untouched.

---

## Tests

Add to `agent/gemma4_31b/tests/`:

1. **`test_resolve_turn_config.py`**
   - `tool` is the default when user text is plain.
   - `plan` fires on "which method should I use?".
   - `explain` fires on "why does this look noisy?".
   - `recipe` fires on "/save-recipe".
   - `recover` fires when `last_turn_had_failure=True`, regardless of text.
   - `mode_lock="plan"` overrides auto even when text is plain.
   - `think_lock=True` forces thinking on in `tool` mode.
   - `think_lock=False` forces thinking off in `plan` mode.

2. **`test_one_turn_sampling.py`**
   - Mock `ollama.chat` to capture the `options` dict. Assert:
     - `tool` mode sends `temperature=0.25, top_p=0.9, top_k=30`, no `think` key.
     - `plan` mode sends `think=True`.
     - After a tool call returns `"ERROR: ..."`, the NEXT round's `options` come from the `recover` profile.
     - Only one mid-turn flip happens even if multiple errors fire.

3. **`test_slash_commands.py`**
   - `/think on` sets `think_state["lock"] = True`.
   - `/think auto` clears it.
   - `/mode recover` sets `mode_state["lock"] = "recover"`.
   - `/mode bogus` prints an error and does not change state.

No live-Ollama tests. All mocked.

---

## Rollout

1. Implement §1–§4 (internal plumbing, no UX change yet).
2. Implement §5 (slash commands).
3. Implement §6–§8 (telemetry, banner, carry-over).
4. Add tests (§Tests).
5. Run tests, fix.
6. Manual smoke: one tool turn, one plan question, one `/think on` → plan question, one forced error (deliberate bad macro) to trigger recover.
7. Commit.

## Open question for the implementer

The current `_one_turn` signature is used by `_turn_worker` at loop.py:1263. The signature change propagates there and to the call site at loop.py:1374. Verify no other callers exist via `grep -n "_one_turn\|_turn_worker" agent/gemma4_31b/`.

---

## File/line anchors

- `loop.py:73` — `TEMPERATURE` constant; add `SAMPLING_PROFILES` next to it.
- `loop.py:190–202` — regex + `_SLASH_COMMANDS`; extend.
- `loop.py:883` — `_parse_slash_command` (reuse as-is).
- `loop.py:1003` — `_one_turn` signature change.
- `loop.py:1035` — hard-coded `options`; replace.
- `loop.py:1085` — exception catch that produces `"ERROR: ..."` tool result; trigger mid-turn recover flip here.
- `loop.py:1179` — `_format_macro_failure_note`; another recover-flip trigger.
- `loop.py:1263` — `_turn_worker`; signature update + return-tuple handling.
- `loop.py:1301` — `run()`; add mode_state, think_state, last_turn_had_failure, slash handlers, CLI initial-lock injection.
- `__main__.py:18` — `main`; add `--mode` and `--think` flags.
