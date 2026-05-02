# Stage 04 — Phrasebook compiler (dev-time tool)

## Why this stage exists

Hand-writing 50 phrasings for each of ~50 intents is ~2,500 lines of
tedious work that the developer should pay for once with an LLM
call rather than typing by hand. This stage builds the dev-time
tool that produces the full phrasebook from a canonical intent
definition file. The shipped plugin still makes zero outbound LLM
calls; the LLM is only invoked when the developer reruns this tool.

## Prerequisites

- Stage 03 (phrasebook JSON schema is locked, starter phrasebook
  exists for `image.pixel_size`).

## Read first

- `docs/local_assistant/00_overview.md`
- `docs/local_assistant/PLAN.md` §5 (intent library), §6.3
  ("Phrasebook compiler"), §7.1 (JSON schema)
- `src/main/resources/phrasebook.json` (the schema we are filling in)
- `agent/recipes/` — example YAML format (this stage produces a
  similar but Python-side intents.yaml)
- ImageJAI's `agent/CLAUDE.md` for the rule "outputs go to
  AI_Exports/", which several mutating intents in §5 need

## Scope

- Create a canonical intent-definition file
  `tools/intents.yaml` listing every intent in PLAN §5 with `id`,
  `description`, optional `slots`, and an example phrasing or two
  to seed the LLM's style. ~50 intents.
- Build `tools/phrasebook_build.py`. Reads `intents.yaml`. For each
  intent, calls Claude (or GPT — provider-agnostic via env var
  `LOCAL_ASSISTANT_LLM_PROVIDER`) with a prompt of roughly:

  > Generate 50 plain-English phrasings a biologist might type to
  > ask for **<description>** in a Fiji image-analysis chat.
  > Include typos, abbreviations, partial phrases, and questions
  > disguised as commands. Output one phrasing per line, no
  > numbering, no markdown.

- Normalise each phrasing (lowercase, strip punctuation, collapse
  whitespace). Deduplicate within an intent and across intents
  (warn on cross-intent collisions; the developer resolves
  manually). Validate that every phrasing passes
  `IntentMatcher.normalise()` (round-trip check).
- Merge the result into `src/main/resources/phrasebook.json`,
  preserving the schema from stage 03. Existing intents are
  overwritten on regen unless `--keep` is passed.
- CLI:
  - `python tools/phrasebook_build.py` — full regen.
  - `python tools/phrasebook_build.py --intent image.pixel_size` —
    regen one intent only.
  - `python tools/phrasebook_build.py --dry-run` — print to stdout
    without writing.
  - `python tools/phrasebook_build.py --provider claude --model
    claude-opus-4-7` — provider/model override.
- Document API key handling in a header comment of the script:
  `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` from env. If both are
  unset, fail loudly.

## Out of scope

- Loading/using the phrasebook at runtime — that is stage 03
  already.
- Intent handler implementations — stages 06–07.
- Tier 2 fuzzy match — stage 05.
- Mining `ij.Menus.getCommands()` to expand the intent set —
  stage 10 (this stage's tool will be reused with a `--menu-dump
  <file>` flag added there).

## Files touched

| Path | Action | Reason |
|---|---|---|
| `tools/intents.yaml` | NEW | Canonical intent list (id, description, seed phrasings) |
| `tools/phrasebook_build.py` | NEW | LLM caller + JSON writer |
| `tools/requirements.txt` | NEW or MODIFY | Pin `anthropic` / `openai` / `pyyaml` |
| `tools/README.md` | NEW or MODIFY | Document how to run the tool, env vars, regen cadence |
| `src/main/resources/phrasebook.json` | MODIFY | Now contains all ~50 intents × ~50 phrasings |
| `.gitignore` | MODIFY | Ensure no API keys end up tracked (`.env` etc.) |

## Implementation sketch

```yaml
# tools/intents.yaml — fragment
- id: image.pixel_size
  description: Report the active image's pixel size
  seeds: ["pixel size", "what is the calibration"]

- id: image.close_all
  description: Close all open images
  seeds: ["close all images", "close everything"]

- id: image.split_channels
  description: Split a multi-channel image into separate channels
  seeds: ["split channels", "isolate the channels"]
```

```python
# tools/phrasebook_build.py — sketch
PROMPT = """\
Generate 50 plain-English phrasings a biologist might type to ask
for **{description}** in a Fiji image-analysis chat. Include typos,
abbreviations, partial phrases, and questions disguised as commands.
Output one phrasing per line, no numbering, no markdown.

Two examples to anchor the style: {seeds}
"""

def build_intent(intent: dict) -> dict:
    raw = call_llm(PROMPT.format(
        description=intent["description"],
        seeds=", ".join(intent.get("seeds", [])),
    ))
    phrases = sorted({normalise(p) for p in raw.splitlines() if p.strip()})
    return {
        "id": intent["id"],
        "description": intent["description"],
        "phrases": phrases,
    }
```

## Exit gate

1. `python tools/phrasebook_build.py --dry-run --intent
   image.pixel_size` prints ≥40 unique normalised phrasings to
   stdout without writing.
2. Full regen produces `src/main/resources/phrasebook.json` with
   one entry per intent in `intents.yaml`, each having ≥40 unique
   normalised phrasings.
3. No phrasing collides across intents (the tool warns and exits
   non-zero if a collision is found; resolve in `intents.yaml`).
4. After regen, `mvn package` bakes the updated phrasebook into the
   JAR. The benchmark test from stage 03 reports a higher top-1
   accuracy than before.
5. Running with no API key set fails loudly with a clear error.

## Known risks

- **Cost.** ~50 intents × 1 LLM call each. Cheap on Haiku/Flash,
  not free on Opus/GPT-5. Default to a smaller model
  (`claude-haiku-4-5` or `gpt-5-mini`) and let the user override.
- **Hallucinated commands.** The LLM may generate phrasings that
  reference plugin names that do not exist or use non-Fiji jargon.
  The validation step does not catch this; spot-check at least
  five intents per release.
- **Cross-intent collisions.** "show channels" could plausibly
  match "split channels" intent or "list channels" intent. The
  tool warns; resolve by tightening either intent's description in
  `intents.yaml` and regenerating that intent only.
- **Schema drift.** If the JSON schema changes (e.g. slots are
  added later), update both the writer here and the loader in
  `IntentLibrary` in lock-step.
