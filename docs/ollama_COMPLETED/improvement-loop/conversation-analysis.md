# Gemma conversation analysis — context

## What we're doing

The user runs **Gemma 4 31B** (model `gemma4:31b-cloud`, via Ollama) as
a Fiji-controlling agent. It is launched from the ImageJAI play button
and chats in a terminal. Its system prompt and tool surface are the
"gemma-claude" context — meaning `agent/gemma4_31b/GEMMA_CLAUDE.md`
plus everything wired into `loop.py`.

The user pastes a full conversation transcript with Gemma. **My job is
to read it, find friction, and propose targeted fixes** to either:

1. **The context** — `agent/gemma4_31b/GEMMA_CLAUDE.md` (the prompt
   Gemma reads on every turn) or `agent/gemma4_31b/GEMMA.md` (rules
   overview).
2. **The Python wrapper** — anything under `agent/gemma4_31b/`
   (`loop.py`, `tools_*.py`, `lint.py`, `auto_probe.py`,
   `describe_image.py`, `triage_image.py`, `safety.py`,
   `harvest_recipe.py`, etc.).

I do not edit until the user agrees on what to change.

## Files I will be touching

| File | Role |
|------|------|
| `agent/gemma4_31b/GEMMA_CLAUDE.md` | System prompt — every word costs tokens, every word steers behaviour |
| `agent/gemma4_31b/GEMMA.md` | Shorter rules card |
| `agent/gemma4_31b/loop.py` | Chat loop, tool dispatch, event draining, friction log |
| `agent/gemma4_31b/tools_fiji.py` | run_macro, get_state, get_log, capture, etc. |
| `agent/gemma4_31b/tools_python.py` | numpy-side pixel analysis |
| `agent/gemma4_31b/tools_dialogs.py` | Swing dialog drivers |
| `agent/gemma4_31b/tools_jobs.py` | async macro + job status |
| `agent/gemma4_31b/tools_plugins.py` | probe_plugin |
| `agent/gemma4_31b/tools_recipes.py` | search/get/save recipes |
| `agent/gemma4_31b/tools_shell.py` | run_shell |
| `agent/gemma4_31b/lint.py` | Pre-run macro checks |
| `agent/gemma4_31b/auto_probe.py` | Schema injection before macros |
| `agent/gemma4_31b/describe_image.py` | Template-based image describer |
| `agent/gemma4_31b/triage_image.py` | New-image warnings |
| `agent/gemma4_31b/threshold_shootout.py` | Otsu/Li/Triangle/etc side-by-side |
| `agent/gemma4_31b/visual_diff.py` | Before/after pixel diffing |
| `agent/gemma4_31b/safety.py` | AI_Exports path enforcement |
| `agent/gemma4_31b/harvest_recipe.py` | Phase 9 recipe save |

## What "friction" looks like in a transcript

Examples of things to flag and propose a fix for:

- **Hallucinated plugin params** → auto_probe didn't fire, or schema
  cache stale, or context doesn't tell Gemma to trust schema injections.
- **Re-running the same broken macro** → loop didn't break out of a
  doom loop; consider a `same_error_twice → escalate` rule.
- **Calling a wrong tool / shell-wrapper for something a real tool
  exists for** → context tool table needs sharpening, or tool docstring
  is unclear.
- **Skipping `describe_image` / `triage_image`** → context doesn't
  emphasise enough, or Gemma isn't getting reminded after image opens.
- **Saving outside `AI_Exports/`** → safety.py blocked it correctly?
  If yes, was the message clear? If no, fix safety.py.
- **`Enhance Contrast normalize=true` on measurement data** → lint
  rule missing or not firing.
- **Long jobs blocking chat** → context or loop didn't push async
  pattern.
- **Wasted turns asking the user obvious questions** → describe_image
  output not being read, or the prompt is making Gemma over-cautious.
- **Verbose, padded replies** → context doesn't say "be concise".
- **Wrong path separators** (Windows backslashes in macros) → lint
  rule or context note.
- **Misreading numbers from `get_results`** → format the table better
  before returning it.

## How I propose fixes

For each issue I find:

1. **Quote** the relevant turns from the transcript (one or two lines).
2. **Diagnose** what went wrong and where in the agent stack.
3. **Propose** the smallest change that prevents it next time —
   exact file, exact section to edit, draft text. No speculative
   refactors.
4. **Wait** for the user to pick which proposals to apply before
   touching files.

## What I don't do

- Don't edit Java / TCP server code unless a missing TCP feature is
  the only fix. The wrapper and prompt are the primary surface.
- Don't add abstractions, "future-proofing", or new tool families
  speculatively. One transcript = at most a handful of focused edits.
- Don't rewrite `GEMMA_CLAUDE.md` wholesale. It's already token-budgeted;
  every addition forces a removal somewhere else.
- Don't claim a fix works without verifying the edited file still
  reads as intended end-to-end (especially the prompt).

## Workflow per pasted conversation

1. Read the transcript end to end before saying anything.
2. List 3–7 concrete friction points, each with a proposed fix.
3. Group by where the fix lives (prompt vs. wrapper vs. lint rule).
4. Ask which to apply.
5. Apply, then summarise what changed in one or two sentences.
