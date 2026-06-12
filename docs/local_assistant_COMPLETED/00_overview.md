# Local Assistant — Staged Implementation

The canonical long-form record is `PLAN.md` in this folder, with the
research synthesis and locked design decisions in `BRAINSTORM.md`.
Each numbered stage below is sized for one focused agent session
(~1–2 days). The stages are executed in numeric order via
`/do-step docs/local_assistant/`.

## End goal

A built-in "Local Assistant" entry in the ImageJAI agent dropdown
that pattern-matches plain English ("what is the pixel size", "close
all images", "list ROIs") to ImageJ macros and state queries. No LLM
at runtime, no API key, no internet. Default-selected on first
launch; once the user picks Claude Code / Codex / Gemini / Gemma from
the dropdown, that choice persists.

## Why we're doing this

ImageJAI today gates the chat input on `Settings.hasApiKey()` and
opens `SettingsDialog` on first launch when no key is configured.
Biologists who do not have an external CLI agent installed see a
disabled input. The Local Assistant gives them a useful floor on day
one — they can ask plain-English questions about the open image,
run common Fiji macros, and discover what is possible — all
offline, with zero per-query cost.

## Architecture (one paragraph)

A new package `imagejai.local` runs a two-tier matcher.
**Tier 1** is an O(1) hash lookup against a phrasebook
(`src/main/resources/phrasebook.json`) generated *at dev time* by
`tools/phrasebook_build.py` (~50 phrasings × ~50 intents,
LLM-distilled). **Tier 2** is `engine.FuzzyMatcher.jaroWinkler` over
the same phrasebook (threshold 0.90) — also drives an autocomplete
chip row under the chat input. **Stage 3** is the existing
`IntentRouter` (regex → macro, persisted to
`~/.imagej-ai/intent_mappings.json`) for user-taught intents.
**Miss** shows "Did you mean…?" with top-3 chips. No BM25, no
runtime embeddings, no runtime LLM. The MiniLM ONNX semantic tier is
opt-in and downloaded on demand via the installer panel (stage 09).

## Stage map

| NN | Slug | Goal | Size | Depends on |
|---|---|---|---|---|
| 01 | settings-and-agent-selector | Persist `selectedAgentName` and add a real agent selector dropdown to the chat header | ~1 day | — |
| 02 | package-skeleton-and-routing | Create the `local` package skeleton; route `ChatView.sendMessage()` through it; bypass API-key/first-run gates | ~1 day | 01 |
| 03 | tier1-matcher-and-first-intent | Phrasebook JSON schema, Tier 1 hash lookup, first read-only intent (`pixel_size`), benchmark scaffold, FrictionLog miss recording | ~1 day | 02 |
| 04 | phrasebook-compiler | `tools/phrasebook_build.py` — dev-time LLM call producing the full ~2,500-entry phrasebook | ~1 day | 03 |
| 05 | tier2-fuzzy-and-autocomplete | Jaro-Winkler fallback inside `IntentMatcher`; debounced autocomplete chip row in `ChatView` | ~1 day | 03 |
| 06 | built-in-intents-control | Implement intents in §5.1, §5.2, §5.6, §5.7, §5.9, §5.10 (~30 intents — inspection, control, ROIs, display, meta) | ~1.5 days | 04, 05 |
| 07 | built-in-intents-analysis | Implement intents in §5.3, §5.4, §5.5, §5.8 (~25 intents — preprocessing, segmentation, measurement, dialog openers) | ~1.5 days | 06 |
| 08 | intent-router-and-slash-commands | Wire `IntentRouter.resolve()` as fallback; implement `/help` `/clear` `/macros` `/info` `/close` `/teach` `/intents` `/forget` | ~1 day | 03 |
| 09 | installer-panel | Models & Agents settings tab; one-click installs (npm/Ollama); MiniLM download; GSD detection + conditional Claude flag | ~1.5 days | 01 |
| 10 | menu-mining-coverage | Mine `ij.Menus.getCommands()` at startup to auto-generate intent stubs for every Fiji menu command, third-party plugins included | ~1 day | 03, 04 |

## House rules (applies to every stage)

Pulled from `CLAUDE.md` and `agent/CLAUDE.md`. These are non-
negotiable; failing them is a regression.

- **State check.** Every mutating intent must verify an image is
  open before acting; reply "no image is open" otherwise.
- **`AI_Exports/`.** All file outputs go to `AI_Exports/` next to
  the opened image (resolve via
  `ImagePlus.getOriginalFileInfo().directory`). If the active image
  has no file-backed directory, ask the user to save it first.
- **Display-only contrast.** Never use `Enhance Contrast
  normalize=true` for data that may be measured. Use `setMinAndMax()`.
- **Never close the Log window.** "Close all but active" intents
  must whitelist the ImageJ Log window.
- **Masks, not overlays.** Detection results display as object masks.
  `Analyze Particles` defaults to `show=Masks`.
- **Probe before guessing.** Dialog-opening intents open the dialog
  and stop. Do not guess parameters for unfamiliar plugin dialogs.
- **No runtime LLM.** Ever. The phrasebook is generated *at dev
  time* (stage 04). The shipped plugin makes zero outbound LLM calls.

## Known open questions

Carried over from `PLAN.md` §14. Each stage that touches one of
these should resolve it locally and note the decision.

1. **Naming.** "Local Assistant" vs alternatives — pick before
   stage 01 ships.
2. **Single-action v1.** No "threshold and measure" chaining in v1.
3. **Parameter prompting.** File dialogs for filenames; chat
   replies for missing numeric/text parameters.
4. **`FrictionLog` persistence.** Stage 03 wires misses into the
   in-memory ring buffer; a JSONL persistence layer is v2 backlog.
5. **Intent ID namespacing.** Built-in IDs use `builtin.<category>.
   <name>`; menu-mined IDs use `menu.<menu-path>`; user-taught
   intents are addressed by regex pattern via `IntentRouter` and
   have no ID.

## How to run a stage

```
/do-step docs/local_assistant/
```

This finds the lowest-numbered `NN_*.md` file without `_COMPLETED`,
reads it, executes it, commits, and renames the file to
`NN_*_COMPLETED.md`.

## Future work (not split into stages)

`PLAN.md` Phase D items — disambiguation chips on submit,
conversational memory, `FrictionLog` JSONL persistence, "improve
from chat history" — are tracked as a backlog rather than as
sequenced stages. Open new stage files for them when they become
priorities.
