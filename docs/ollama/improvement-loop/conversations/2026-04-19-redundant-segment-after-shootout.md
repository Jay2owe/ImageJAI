# 2026-04-19 — count-them: redundant segmentation after shootout, `add` vs `add_to_manager`

Model: Gemma 4 31B (`gemma4:31b-cloud`) via Ollama.
Context loaded: updated `agent/gemma4_31b/GEMMA_CLAUDE.md` (post-round `b3253c6`).

---

## Transcript

```
you> open blobs sample image
  🪄 run_macro({"code": "run(\"Blobs (25K)\");"})
  → {"ok": true, "result": {"success": true, "output": "", "executionTimeMs": 656, "newImages": ["blobs.gif"]}}
  🔎 describe_image({})
  → Active image "blobs.gif" is 8-bit, 256×254 pixels, single channel, single z, single frame. Pixel size is **uncalibrated** (reads as "1 pixel"). Intensity ranges from 8 to 248 with mean 103, median 68 and standard deviation 71; dynamic range used is 97.3% of the 8-bit maximum. Saturated-pixel fraction is 0.00%. The histogram is unimodal and strongly right-skewed (mean 103 vs median 68). Auto-thresholds produce 24 connected components with Otsu, 65 with Li and 119 with Triangle on a 512-pixel thumbnail. No clipped blacks, no quadrant saturation, no stripe pattern. No ROI, no overlay.
gemma> I have opened the `blobs.gif` sample image. It is an 8-bit image (256x254 pixels) containing several bright objects on a darker background.

What would you like to do with this image? For example, I can:
1. **Count the blobs** using an automated threshold.
2. **Measure the area and intensity** of each object.
3. **Filter the objects** by size or circularity.

Let me know how you'd like to proceed!
  Worked for 16.0s (115 tok, 7.2 tok/s)
you> count them
  🪄 threshold_shootout({"manual_thresholds": [], "methods": ["Otsu", "Li", "Triangle", "Minimum", "Huang"]})
  → {"montage_path": "...threshold_shootout_20260419_124328.png", "methods": [{"name": "Otsu", "success": true, "count": 64, ...}, {"name": "Li", "success": true, "count": 65, ...}, {"name": "Triangle", "success": true, "count": 119, ...}, {"name": "Minimum", "success": true, "count": 64, ...}, {"name": "Huang", "success": true, "count": 64, ...}], "reasoning": "Huang was selected because it yields 64 objects ...", "recommended": "Huang"}
  🪄 run_macro({"code": "setOption(\"BlackBackground\", true);\nsetAutoThreshold(\"Huang dark\");\nrun(\"Convert to Mask\");\nrun(\"Analyze Particles...\", \"size=10-Infinity show=Nothing add\");\nrun(\"Clear Results\");\nrun(\"Measure\");\n"})
  → ERROR: run("Analyze Particles...", "...") uses argument name(s) that do not exist in the plugin's dialog: add. Valid argument names for this plugin are: add_to_manager, circularity, clear_results, composite_rois, display_results, exclude_on_edges, include_holes, overlay, show, size, summarize. Probe the plugin with probe_plugin before re-running, or use one of the listed names.
```

## Applied — 2026-04-19

| # | Section | Change | File |
|---|---|---|---|
| F1 | §1 | new prompt section "Using threshold_shootout correctly" — its `count` IS the answer for a count request; re-segment only when the user asks for something shootout didn't produce (per-object measures, labelled mask, size-filtered subset, ROIs). Tool-table line also updated. | `GEMMA_CLAUDE.md` |
| F2 | §2 | bullet in "Common mistakes the lint layer catches": `Analyze Particles... add` → use `add_to_manager`. | `GEMMA_CLAUDE.md` |

<!-- F1: Gemma ran shootout, got count=64 for Huang, then redundantly ran a full segmentation pipeline → plan §1, applied -->
<!-- F2: 'add' keyword hallucination for Analyze Particles; server caught it but prompt didn't warn → plan §2, applied -->

**Outstanding action for the user**: close and reopen the Gemma terminal so the updated `GEMMA_CLAUDE.md` loads. No Java change this round — no Fiji restart needed.
