# TCP Upgrade Plan

A plan to improve the TCP command server's feedback channel so AI
agents — especially smaller local models — perform better at real
Fiji tasks. Each step is self-contained enough for one agent to
pick up and implement without needing the full picture.

## Why

Transcripts from the Gemma 4 31B improvement loop
(`docs/ollama_COMPLETED/improvement-loop/conversations/`) show
recurring failure patterns that the server is positioned to fix
once — for every agent at once:

- **Hallucinated plugin names** (`Laplacian...`, `DoG...`,
  `Variance` with no ellipsis). Server has the canonical list;
  doesn't consult it.
- **Repeated retries of errors that will never succeed**. The
  `{"error": "string"}` reply has no "stop trying" signal, so
  small models loop.
- **Wasted `get_state` calls just to reorient**. No compact
  always-on status readout means agents pay full state-dump cost
  to answer "what image am I on?"
- **Lost track of what actually ran**. Agents send `"Otzu"` and
  never learn ImageJ corrected it to `"Otsu"` because the reply
  doesn't echo the canonical macro.
- **Observability gaps on the Fiji side**. RoiManager, channel/slice
  cursor, Fiji Console (where Groovy stack traces land) — none
  exposed, so agents guess.

Production protocols (MCP 2025-11-25, OpenAI function calling,
Anthropic tool_use) all converge on the same shape: structured
errors with `is_error` markers, typed output alongside text,
capability negotiation at connect, pointers instead of inlined
payloads. ImageJAI's server predates all of this. We're aligning
it with where the field has landed.

## End goal

An agent-aware TCP server where:

1. **Agents introduce themselves** at connect (`hello` handshake)
   and get replies shaped to their context budget, vision
   capability, and error-recovery strategy.
2. **Errors are objects**, not strings. Each error names a
   category, a recovery hint, whether retry is safe, and a
   concrete suggested-next-action when the server can infer one.
3. **Hallucinated plugin names are caught server-side** via a
   cached command table and fuzzy matching. High-confidence
   corrections auto-apply; ambiguous ones become suggestions.
4. **Every reply carries a one-line state pulse** (for agents
   without session-state hooks) and a `stateDelta` sub-object
   listing only what changed.
5. **Canonical macro echoes** the normalised code ImageJ actually
   ran — only when it differs from what the agent sent or when
   the call failed.
6. **Fiji observability gaps close**: RoiManager, display state,
   Fiji Console exposed as first-class commands.
7. **Small-model-specific tools** exist in the Gemma wrapper with
   docstring-driven schemas tuned to how Gemma talks.

## Agent-type impact (expected)

| Agent | Biggest wins |
|---|---|
| Gemma 4 31B | `retry_safe: false` stops loops. `pulse` removes needless `get_state`. Fuzzy match kills hallucinations. Dedicated ROI/display/console @tool wrappers raise tool-selection accuracy. |
| Claude Code | Structured errors unlock `is_error: true` branching. `ranCode` echo shows normalisation. Hooks still feed state; `pulse` is skipped for Claude. |
| Codex / GPT-5 | Strict error schemas match function-calling expectations. 10k-token reply cap. `stateDelta` instead of full dumps fits 400k context tighter. |
| Gemini CLI | Images-in-function-response for multimodal replies. Terse JSON + thumbnail pattern plays to Gemini's strengths. |

## Architecture

All changes respect three rules:

1. **Backwards compatible.** A client that never calls `hello`
   gets exactly today's reply shape. Safe migration per wrapper.
2. **Capability-gated.** Every new field is opt-in via the `hello`
   handshake. Agents with hooks (Claude) opt out of fields their
   harness already provides. Agents without vision opt out of
   thumbnail attachments.
3. **Hard ceiling per reply.** 10k tokens max. Anything over
   returns a pointer, not the payload. Prevents context-overflow
   collapse documented in arXiv 2511.22729 (2025).

## Phase structure

**Phase 1 (steps 01–08)** — the foundation. Each step here is
either a prerequisite for later work or a high-leverage fix
that stands alone.

**Phase 2 (steps 09–14)** — keepers that depend on Phase 1 being
in place. Histogram delta, phantom dialog detector, dedup,
telemetry, provenance graph, federated ledger.

**Phase 3 (step 15)** — undo stack as API. Standalone, research-
grade. Worth its own design doc before code.

## Not in this plan (deliberately)

- **Safe mode** (rehearsal + destructive-op blocking) —
  documented separately in `docs/safe_mode/`. Overlaps with this
  plan on the `hello` handshake but is a distinct feature.
- **Honeypot hallucination traps** — cut. Poisoning the plugin
  list in a live tool makes things worse to prove a point. Keep
  the idea for a future eval harness only.
- **Dialog precedents** — cut.
- **Intent echo** — cut.

## File index

### Phase 1 — foundation

- `01_hello_handshake.md` — capability negotiation and per-socket
  `AgentCaps`. Prerequisite for everything else.
- `02_structured_errors.md` — `{code, category, retry_safe,
  recovery_hint, suggested[]}` replaces the bare error string.
- `03_canonical_macro_echo.md` — `ranCode` via the ImageJ Recorder,
  returned only when it differs or when the call failed.
- `04_fuzzy_plugin_registry.md` — cache `Menus.getCommands()` at
  startup, Jaro-Winkler match, auto-correct at ≥0.95 unique,
  suggest between 0.85–0.95.
- `05_state_delta_and_pulse.md` — promote internal diffs to a
  `stateDelta` object, add a one-line `pulse` (skipped for Claude).
- `06_nresults_trap.md` — macro analyser that inlines a warning
  when `Analyze Particles` is called without a results-writing flag.
- `07_gemma_tools_server.md` — three new TCP commands:
  `get_roi_state`, `get_display_state`, `get_console`.
- `08_gemma_tools_wrapper.md` — Gemma `@tool` functions and
  `ij.py` subcommands that wrap the new TCP commands.

### Phase 2 — depends on Phase 1

- `09_histogram_delta.md` — 32-bin before/after intensity
  distribution, always-on, vision-free change proxy.
- `10_phantom_dialog_detector.md` — post-macro AWT focus check,
  surface invisible modal dialogs, optional auto-dismiss.
- `11_dedup_response.md` — `{"unchanged": true}` short-circuit
  when identical-state replies repeat within 10 seconds.
- `12_per_agent_telemetry.md` — `agent_id` propagation through
  FrictionLog, pattern-detection hints
  (`PATTERN_DETECTED: close_dialogs x4`).
- `13_provenance_graph.md` — session-scoped DAG of images and
  the macros that created them; response carries the changed
  subgraph only.
- `14_federated_ledger.md` — `~/.imagejai/ledger.json`: error
  fingerprint → known-good fix, populated across sessions and
  agents. Queryable via a new TCP command.

### Phase 3 — research-grade standalone

- `15_undo_stack_api.md` — rolling per-image undo stack with
  `/rewind` and `/branch` endpoints. Lets agents experiment and
  abandon. Memory budget is the hard problem.

## Reading order for implementers

Each step file leads with a "Read first" list naming the server
and wrapper files that must be understood before touching code.
Steps can be skipped or reordered within a phase, but **Step 01
must land before any other step reads capabilities**.
