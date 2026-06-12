# 2026-04-19 — `get_log` reflex on errors that aren't in the log

Model: Gemma 4 31B (`gemma4:31b-cloud`) via Ollama.
Context loaded: `agent/gemma4_31b/GEMMA_CLAUDE.md` (post-commit `040ea73`).

User directive: Gemma calls `get_log` after every error, but many error classes
(script-engine compile errors, probe/safety/lint rejections, TCP failures)
come back in the tool reply itself and never reach the IJ Log window.

---

## Transcript

```
  → ERROR: Script error: org.codehaus.groovy.control.MultipleCompilationErrorsException: startup failed:
Script3.groovy: 10: Unexpected input: ',' @ line 10, column 11.
       "None",
             ^

1 error
  🔎 get_log({})
```

## Applied — 2026-04-19

| # | Section | Change | File |
|---|---|---|---|
| F1 | §1 | new bullet in "Recovering from errors": *read the error text before reaching for `get_log`*. Names which error classes live in the tool reply vs. the IJ Log window (scripts / probes / safety / TCP → reply; `print()` / `IJ.log()` → log). | `GEMMA_CLAUDE.md` |

<!-- F1: Gemma reflex-called get_log({}) after a Groovy compile error whose full text was already in the tool reply → plan §1, applied -->

**Outstanding action for the user**: close and reopen the Gemma terminal so `GEMMA_CLAUDE.md` reloads. No Java change this round — no Fiji restart needed.