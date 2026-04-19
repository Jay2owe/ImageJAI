# Gemma improvement loop — context

## What we're doing

The user runs **Gemma 4 31B** (`gemma4:31b-cloud` via Ollama) as the
ImageJ-controlling agent, launched from the ImageJAI play button. Its
prompt and tool surface are the "gemma-claude" stack
(`agent/gemma4_31b/GEMMA_CLAUDE.md` + everything wired into
`loop.py`).

The user pastes Gemma transcripts. **My job is to turn each transcript
into a focused improvement to either the prompt or the wrapper.** This
file is the workflow.

## Folder layout

```
docs/ollama/improvement-loop/
├── README.md          # this file — workflow & rules
└── conversations/
    └── YYYY-MM-DD-<short-slug>.md   # one file per pasted transcript
```

Conversations are the source-of-truth audit trail. Plans, fixes and
follow-ups are derived from them.

## Per-transcript workflow

Two confirmation gates, so the user never pays token cost on research
or edits they didn't want.

1. **Save first.** When the user pastes a transcript, write it
   verbatim to `conversations/YYYY-MM-DD-<short-slug>.md` *before*
   doing anything else. Use today's date and a 3–5 word slug from the
   user's first turn (`blobs-10-filters`, `cellpose-on-tile-scan`).
2. **Analyse.** Read the saved file end to end. List 3–7 concrete
   friction points with a one-line diagnosis each. Quote the offending
   turn briefly. Do not yet propose code changes in detail.
3. **Gate 1 — confirm what to research.** Ask the user which friction
   points are worth deep investigation. Some are obvious and need no
   research; some will be dropped ("ignore that one"). Wait for an
   explicit pick before spending tokens on subagents.
4. **Research.** For each *confirmed* friction whose root cause needs
   code I haven't read, spawn focused research subagents in parallel
   (Explore or general-purpose). One subagent per code area —
   `auto_probe`, `safety`/`active_image`, `loop.py`, `events.py`, Java
   probe handler, etc. Each subagent gets the bug, the files to read,
   the report format, a word cap (≤500 words). Keep the orchestrator
   context clean by delegating reads.
5. **Plan.** Once subagents return, write a comprehensive plan: per
   friction, give file:line, the smallest change, and a draft of the
   new code or prompt text. Group by where the fix lives (prompt vs
   wrapper vs lint vs probe). Note rebuild / redeploy requirements
   for any Java changes.
6. **Gate 2 — confirm what to apply.** Present the plan grouped into
   waves (high-pain-low-risk first). The user picks which sections
   and which waves. Don't edit until they pick.
7. **Apply.** Edit only the chosen items. Use TaskCreate to track
   each section so progress is visible. Verify each file reads
   cleanly end-to-end after.
8. **Rebuild + redeploy** (when Java changed). Run `bash build.sh`
   from the project root; it compiles with Maven and drops the JAR
   into `Fiji.app/plugins/`. Ask the user to restart Fiji and
   confirm the TCP server is back up before marking anything
   "applied". If only Python was touched, the user just restarts
   the Gemma terminal. Catching a build failure here is much
   cheaper than catching it after the annotations are written.
9. **Annotate.** Append lightweight HTML comments to the saved
   transcript next to each friction line, e.g.
   `<!-- F3: auto-probe Median schema mismatch → plan §3, applied -->`.
   These are audit markers only — keep them short, do not refactor
   the transcript, do not summarise.
10. **Commit + push.** One commit per round — rollback point between
    rounds. Include every file the apply step touched, the updated
    plugin cache (or deletion), and the annotated transcript. Commit
    message: `gemma improvement loop: <slug> (<N>/<M> frictions)`
    with a bulleted list of applied `§<n>` sections. Push to origin;
    surface any push failure, do not retry silently.

## Fixed scope

| Layer | Files |
|------|------|
| Prompt | `agent/gemma4_31b/GEMMA_CLAUDE.md`, `agent/gemma4_31b/GEMMA.md` |
| Chat loop | `agent/gemma4_31b/loop.py` |
| Tools | `agent/gemma4_31b/tools_*.py` |
| Pre-run guards | `agent/gemma4_31b/lint.py`, `auto_probe.py`, `safety.py` |
| Image helpers | `describe_image.py`, `triage_image.py`, `threshold_shootout.py`, `visual_diff.py`, `harvest_recipe.py` |
| Event / state | `events.py`, `active_image.py` |

The Java TCP server (`src/main/java/imagejai/engine/`) is
in scope only when a missing TCP feature is the only fix.

## Friction patterns to flag

Hallucinated plugin params · re-running same broken macro ·
wrong-tool-for-job · skipping describe_image/triage_image when
relevant · saving outside `AI_Exports/` · `Enhance Contrast
normalize=true` on measurement data · long jobs blocking chat ·
wasted turns asking obvious questions · verbose padded replies ·
Windows backslashes in macros · misreading numbers from
`get_results` · auto-triage firing on agent-internal masks · tool
result truncation losing actionable suffix.

## Annotation conventions

Inline HTML comments only. Pattern:

```
<!-- F<n>: <one-line label> → plan §<n>[, status] -->
```

Examples:

```
<!-- F1: shootout refused on sample image (no path) → plan §1, applied -->
<!-- F4: auto-probe error truncated mid-sentence → plan §4, applied -->
```

No multi-line annotations. No editorialising. No moving the original
text around.

## What I don't do

- Don't rewrite `GEMMA_CLAUDE.md` wholesale. It's token-budgeted.
- Don't add abstractions or new tool families beyond the ticket.
- Don't touch Java unless a missing TCP feature is the only fix.
- Don't claim a fix works without re-reading the edited file.
- Don't pad the transcript annotations.
