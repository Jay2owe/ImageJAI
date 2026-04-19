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

<!-- F1: macro assumed an active image without selectImage, failed with "No image is open" → plan §1, applied -->

**Outstanding action for the user**: close and reopen the Gemma terminal so the updated `GEMMA_CLAUDE.md` loads. No Java change this round.