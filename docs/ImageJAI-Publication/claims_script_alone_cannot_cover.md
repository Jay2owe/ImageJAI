# Claims Beyond A Script Alone

This file lists the capabilities to "sell" as the need for ImageJAI. The exact
claim should be "impossible or impractical with a conventional fixed macro or
script alone," not mathematically impossible for any software ever written.

## 1. Adaptive Visual Decision-Making

A script can apply a threshold. ImageJAI can ask "does this look like nuclei or
cytoplasm?", capture the active image, inspect the result, and choose the next
tool accordingly.

Paper framing: many real analyses are not a fixed command sequence; they are a
conversation between the image, the method, and the scientist.

Demo idea: present nuclei and cytoplasm channels, ask which segmentation tool
fits each, then compare the output visually and quantitatively.

## 2. Plugin Discovery Before Use

A script needs the correct plugin name and argument string in advance. ImageJAI
can open an unfamiliar plugin dialog, read numeric fields, checkboxes,
dropdown choices, sliders, and generated macro keys, then cancel without
executing.

Local source: `probe_command` and `agent/probe_plugin.py`.

Paper framing: this is a direct attack on one of the biggest failure modes of
LLM-generated ImageJ macros: plausible but wrong plugin names or arguments.

## 3. Dialog-Aware Execution

A script often blocks or fails silently when a plugin opens a dialog or error
window. ImageJAI exposes dialog titles, text, type, buttons, and interactable
components, and can fill or click them.

Local source: `get_dialogs`, `close_dialogs`, and `interact_dialog` in
`TCPCommandServer.java`.

Paper framing: Fiji is GUI-rich. A useful AI assistant needs to operate the
actual GUI state, not only write headless code.

## 4. Self-Correction From Structured Feedback

A script stops at an error. A naive LLM sees a vague failure string. ImageJAI
can expose structured error categories, retry safety, recovery hints, canonical
macro echoes, logs, and console traces.

Local sources:

- `docs/tcp_upgrade_COMPLETED/02_structured_errors_COMPLETED.md`
- `docs/tcp_upgrade_COMPLETED/03_canonical_macro_echo_COMPLETED.md`
- `agent/CLAUDE.md`

Paper framing: ImageJAI moves error recovery from "try again with a guess" to
"repair the specific failed command."

## 5. Live State And Change Tracking

A script can query state if the author wrote that logic. ImageJAI makes state
inspection a first-class agent channel: active image, calibration, windows,
ROIs, Results table, display state, memory, progress, logs, and state deltas.

Paper framing: agents need compact, current state to avoid wasting tokens and
repeating full session summaries.

## 6. Quantitative Visual Feedback Without Full Images

Not every model can process images, and image payloads are expensive. ImageJAI
can provide histogram deltas, pixel statistics, line profiles, region stats,
and results-table summaries as compact numerical feedback.

Paper framing: the plugin supports both vision-capable and text-only models.

## 7. Provenance, Audit, And Reproducibility

A macro can be saved. ImageJAI can also record what ran, which image changed,
what results table was produced, and which known fix solved a previous failure.

Local source candidates:

- `docs/tcp_upgrade_COMPLETED/13_provenance_graph_COMPLETED.md`
- `docs/tcp_upgrade_COMPLETED/14_federated_ledger_COMPLETED.md`
- `agent/auditor.py`

Paper framing: the output should be inspectable by a scientist after the agent
finishes, not just accepted as chat text.

## 8. Agent-Agnostic And Local-Model Operation

A script has no model. Many AI plugins assume one hosted model provider. ImageJAI
separates Fiji control from the model and can launch multiple command-line
agents, including a local Gemma/Ollama workflow.

Local source: `AgentLauncher.java` lists Claude Code, Aider, Gemini CLI, Codex
CLI, and Gemma 4 31B. `README.md` documents Gemini, Ollama, and
OpenAI-compatible backends.

Paper framing: users can choose cost, privacy, speed, and model quality rather
than being locked to one cloud API.

## 9. Safety For Measurement Integrity

A macro can accidentally normalize intensities, overwrite raw data, clear ROIs,
or reset calibration. ImageJAI's safety work should be presented as a planned
or implemented guardrail only after verification.

Local source candidates:

- `docs/safe_mode_v2/00_overview.md`
- safe-mode classes in `src/main/java/imagejai/engine/safeMode/`

Paper framing: scientific image analysis is not only about producing output;
it is about avoiding plausible but invalid output.

## 10. Defence Against Indirect Prompt Injection From Image Metadata

A naive bridge would forward attacker-authored OME-XML, log lines, dialog
text, and console output verbatim to the agent — a textbook indirect
prompt-injection surface (Greshake et al. 2023; Microsoft, "Indirect
prompt injection in agentic AI", Apr 2025). ImageJAI wraps externally-
sourced text in tagged envelopes (`[OME-XML: ...]`, `[META:key: ...]`,
`[LOG: ...]`, `[DIALOG: ...]`, `[CONSOLE: ...]`), strips C0/C1 control
chars, and length-caps to 8 KB.

**Status: Implemented (modest) — 2026-05-09.** Five boundary points in
`TCPCommandServer.java` route through `AgentContextSanitizer`; a 27-test
adversarial corpus at
`src/test/java/imagejai/engine/security/AgentContextSanitizerTest.java`
covers control-char, length, role-confusion, encoding, unicode-attack,
boundary, and idempotency cases.

Local sources:

- `src/main/java/imagejai/engine/security/AgentContextSanitizer.java`
- `docs/imagejai-publication/security/agent_context_sanitization.md`
- `docs/imagejai-publication/supplements/adversarial_corpus.md`

Paper framing: indirect-prompt-injection mitigation is presented as a
modest, testable boundary — not a sandbox. Trusted-surface attacks (e.g.
recipe-YAML signing) remain `Planned`.

## 11. QUAREP-LiMi-Aligned Methods Table Auto-Emission

A script can write whatever methods paragraph its author types. ImageJAI
walks the session log + Bio-Formats metadata + the provenance graph and
emits a Markdown methods table aligned with QUAREP-LiMi WG11 *Bare
Minimum Microscopy Methods Reporting* fields. Populated values come
verbatim from structured data, not from an LLM paraphrase; missing
fields stay `[unknown]` and two fields are marked `[unknown - human
only]` (statistical-test protocol, acquisition rationale).

**Status: Implemented (modest, draft-tool) — 2026-05-09.**
`agent/methods_table.py` (~270 LOC, stdlib-only) emits 33 WG11-aligned
fields with a per-image field-coverage statistic. Three example outputs
in `docs/imagejai-publication/supplements/exported_macros/methods_examples/`
demonstrate count + measure (15/33), segment + classify (13/33), and
time-series (13/33) on synthesised public-benchmark inputs. The TCP
command `emit_methods_table` triggers the exporter from the agent side.

Local sources:

- `agent/methods_table.py`
- `agent/methods_table_template.md`
- `docs/imagejai-publication/unique-features/methods_paragraph_export.md`
- `docs/imagejai-publication/supplements/exported_macros/methods_examples/`
- `src/main/java/imagejai/engine/TCPCommandServer.java` —
  `handleEmitMethodsTable` + dispatch entry

Paper framing: a draft tool, not an oracle. Citation harvesting (DOI
lookup, BibTeX) is explicitly out of scope (Option D in D8). The point
is what IS automated — analysis-side fields that take dozens of clicks
to chase down by hand — not 100% coverage.

