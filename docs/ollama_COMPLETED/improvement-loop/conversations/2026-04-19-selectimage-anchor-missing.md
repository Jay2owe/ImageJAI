# 2026-04-19 — macros touching active image without `selectImage` anchor

Model: Gemma 4 31B (`gemma4:31b-cloud`) via Ollama.
Context loaded: `agent/gemma4_31b/GEMMA_CLAUDE.md` (post-commit `5f00f1b`).

User directive: every macro that depends on the active image should start
with `selectImage(title)` — makes the macro fail fast and self-document
which image is being operated on.

---

## Transcript

```
  🪄 run_macro({"code": "run(\"Clear Results\");\nroiManager(\"reset\");\nrun(\"Duplicate...\", \"title=check_mask\");\nsetOption(\"BlackBackground\", true);\nsetThreshold(100, 255);\nrun(\"Convert to Mask\");\nsaveAs(\"Tiff\", \"AI_Exports/mask_check.tif\");\nclose(\"check_mask\");\n"})
  → ERROR: No image is open (the active-image cache is empty). Open an image first, or launch the agent with --export-dir PATH to set a fallback folder for macro outputs.
```

## Applied — 2026-04-19

| # | Section | Change | File |
|---|---|---|---|
| F1 | §1 | new prompt section "Anchor every image macro with `selectImage`" — enumerates the active-image-touching calls, names the title sources (triage banner, `windows({})`, last `newImages`), lists three benefits (fail fast, self-documenting, disambiguated) | `GEMMA_CLAUDE.md` |
| F2 | §2 | `detectBlockingDialog` branches on `"Macro Error"` title — replaces the misleading "supply parameters via run(name, args)" suffix with a compile-error-oriented hint pointing at `get_log({})`; plugin-dialog cases keep the original hint. | `TCPCommandServer.java` *(rebuild + redeploy done)* |
| F3 | §3 | new lint block rule `run_with_three_args` — catches `run("A", "B", "C")`; when the first arg matches a Save-As pattern, suggests the canonical `saveAs(type, path)` macro function. | `lint.py` |

<!-- F1: macro assumed an active image without selectImage, failed with "No image is open" → plan §1, applied -->
<!-- F2: "supply parameters" suffix was misleading for Macro Error compile-failure dialogs → plan §2, applied -->
<!-- F3: run("Save As", "Tiff", path) — macro run() takes 2 args max → plan §3, applied -->

**Outstanding action for the user**: close and reopen the Gemma terminal so `GEMMA_CLAUDE.md` + `lint.py` reload. Close and relaunch Fiji so the new JAR's classes take effect (the new JAR is already at `Fiji.app/plugins/imagej-ai-0.2.0.jar`).