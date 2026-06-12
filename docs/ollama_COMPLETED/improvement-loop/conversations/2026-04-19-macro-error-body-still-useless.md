# 2026-04-19 — Macro Error body still shows only the checkbox

Follow-up on last round's F2 in
`2026-04-19-10-filter-shootout-debug-dialog.md`. That fix added
Signal 5 in `detectIjMacroError`: if signals 1–4 all miss AND a
Macro Error dialog is open, surface the last non-empty log line.
For compile errors the log is usually empty (noted in the Java
comment itself), so Signal 5 returns null and we fall through to
`detectBlockingDialog`, which prints the dialog body — which is
still just the checkbox. The agent has no way to recover.

Model: Gemma 4 31B (`gemma4:31b-cloud`) via Ollama.
Context loaded: `agent/gemma4_31b/GEMMA_CLAUDE.md`.

## Friction the user called out

```
🪄 run_macro({"code": "selectImage(\"blobs.gif\");\nrun(\"Clear Results\");\nrun(\"Duplicate...\", \"title=original\");\n\n// Define 10 filter sets …\n… hand-unrolled 10-block macro …\n"})
  → ERROR: Macro paused on modal dialog: Macro Error
    — body: [checkbox: Show "Debug" Window = OFF]
    — the macro itself failed to compile or run; re-read the macro source
      for syntax/typo/argument-count bugs (do not probe a plugin — this
      is not a missing-args case; the ImageJ log is usually empty for
      compile errors)
    — the dialog has been auto-dismissed by the server.
```

User's complaint verbatim:
> we drasitcally need better error logging. it is not good enough
> to say this after creating a massive macro like this … he needs
> to know exactly the errors.

## Why F2 didn't fix this

- F2's Signal 5 only helps when the ImageJ Log has content.
- ImageJ compile errors (and many runtime errors) are routed via
  `Interpreter.abort(...)` → `IJ.showMessage("Macro Error", msg)` →
  `GenericDialog.addMessage(msg)` — the text lives in a
  `ij.gui.MultiLineLabel` on the dialog itself, not in the log.
- `extractDialogContent` (line 3171) iterates components and
  handles `JLabel/Label/JTextArea/TextArea` etc. For unknown
  components it reflects over `getText()/getLabel()/getMessage()`
  — but these are `Class.getMethod()` calls, which only see
  public methods.
- `ij.gui.MultiLineLabel` stores its text in private fields
  (`text2`, `lines[]`) and in older ImageJ versions has no public
  `getText()`. The reflection probe fails silently, so only the
  `[checkbox: …]` component makes it into the dialog body.
- Classification then also breaks: `detectOpenDialogs` tags
  `type=error` based on keywords in the extracted text. With the
  real error message missing, the tag falls back to `info`, so
  Signal 4 (error-typed dialogs) also misses.

## Applied — 2026-04-19

| # | Section | Change | File |
|---|---|---|---|
| F1 | §1 | `extractDialogContent` snapshots `text.length()` before the `getText/getLabel/getMessage` method probes; if nothing was appended, calls new helper `readFieldText(comp)` which walks the declared-field hierarchy for `text2/text/label/msg/message` (String) and `lines[]` (String[]). Recovers the real compile/runtime error text from `ij.gui.MultiLineLabel` on the Macro Error dialog, so `dismissedDialogs[*].body` and `detectBlockingDialog`'s `body:` line now carry the actual error. Signal 4 and type classification benefit automatically. | `TCPCommandServer.java` *(rebuild + redeploy done)* |

<!-- F1: Macro Error dialog body was reduced to the checkbox line because
extractDialogContent's reflection fallback used Class.getMethod() (public only)
and ImageJ's MultiLineLabel stores its text in private fields with no public
getter on many builds → plan §1, applied (server-side, MultiLineLabel-aware
field reflection; no prompt growth, no client-side get_log suggestion noise —
the existing Signal 3/5 chain already handles the log-present cases) -->

**Outstanding action for the user**: restart Fiji so the running JVM loads the new `imagej-ai-0.2.0.jar` (already copied into `Fiji.app/plugins/`). No Gemma-terminal restart needed — no Python was touched.
