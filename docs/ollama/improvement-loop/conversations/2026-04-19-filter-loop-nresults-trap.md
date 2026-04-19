# 2026-04-19 — filter-loop nResults-after-Analyze-Particles trap

Model: Gemma 4 31B (`gemma4:31b-cloud`) via Ollama.
Context loaded: `agent/gemma4_31b/GEMMA_CLAUDE.md` (post-commits `1ddd869`, `a71f908`).

User asks for "10 different filter sets and get the counts from each and compare them. filters not thresholds". Gemma writes a macro that loops over filters, thresholds, calls `Analyze Particles...`, and reads `nResults` — which is always 0 because Analyze Particles doesn't populate the Results table without a measurement flag. The agent burns ~7 macros chasing the wrong cause (BlackBackground, threshold value, particle size, syntax typo) before `describe_image` reminds it Analyze Particles works fine, then a one-shot probe macro pivots to ROI Manager (`add` + `roiManager("count")`) and the loop finally returns counts.

Side issues: hallucinated `Laplacian...` / `Difference of Gaussians...` / `Band-pass Filter...` (regression of `ca8509c`); wasted ~4000-token `run_shell` dump of `.tmp\commands.txt` looking for filter names; `print()` output not surfaced in `run_macro` reply so each iteration is followed by `get_log()`; `visual_diff` misfires "Convert to Mask should leave 2-bin histogram" against the original (non-mask) image left active when the temp is closed.

<!-- F1: Analyze Particles + nResults trap → plan §1.1, applied (lint warn `analyze_particles_no_output_flag`) -->
<!-- F2: REGRESSION of ca8509c — hallucinated Laplacian/DoG/Band-pass Filter; prompt-only fix didn't stick → plan §1.2, applied (lint block `hallucinated_run_command`) -->
<!-- F3: print() output not surfaced in run_macro reply → plan §3.1, applied (Java `logDelta` field, rebuilt + redeployed) -->
<!-- F4: wasted run_shell dump of commands.txt → plan §2.1+§2.2, applied (commands.md with lookup map header + run_shell cwd pinned to repo root) -->
<!-- F5: visual_diff Convert-to-Mask false positive against unchanged active image → plan §2.3, applied (suppress when pix_frac/mean_shift/hist_dist all zero) -->
<!-- F6: sanity check — `_rule_run_with_three_args` does NOT match `run("Analyze Particles...", "add")` (only fires on 3+ string args). No change needed. -->

## Applied — 2026-04-19

| # | Section | Change | File |
|---|---|---|---|
| F1 | §1.1 | New lint rule `analyze_particles_no_output_flag` (warn): detects `run("Analyze Particles...", "<args>")` followed by `nResults` read with no `summarize`/`display`/`display_results`/`results`/`add_to_manager` flag. Suppresses if `Clear Results` runs between. Hint suggests both fixes (output flag or ROI Manager). | `agent/gemma4_31b/lint.py` |
| F2 | §1.2 | New lint rule `hallucinated_run_command` (block): hardcoded blocklist `{Laplacian..., Laplacian, Difference of Gaussians..., Difference of Gaussians, DoG..., DoG, Band-pass Filter..., Band-pass Filter}`, each with a recipe-style repair hint (FeatureJ Laplacian, two-Gaussians DoG, real name `Bandpass Filter...` no hyphen). Also deleted the now-redundant prompt bullet at GEMMA_CLAUDE.md:110 (per "prefer code fixes over prompt expansion", commit 6bdd7f5). | `agent/gemma4_31b/lint.py`, `agent/gemma4_31b/GEMMA_CLAUDE.md` |
| F3 | §3.1 + §3.2 | TCP server: capture `IJ.getLog()` delta during `execute_macro` (snapshot already exists at `logLenBefore`, line ~1174); add `logDelta` field to both success and failure responses, capped at 16 KB. Prompt: tightened the `get_log` row in the Tools table to say "prints come back in `logDelta` — do NOT auto-call `get_log`." Java rebuilt and redeployed. | `src/main/java/imagejai/engine/TCPCommandServer.java`, `agent/gemma4_31b/GEMMA_CLAUDE.md` |
| F4 | §2.1 + §2.2 | `scan_plugins.py` now writes `.tmp/commands.md` (with header lookup map: where to find native macro commands, how to grep, how to regenerate, plus categorised sections) alongside `.tmp/commands.raw.txt` (parser-friendly raw dump). `adviser.py` switched to read the raw file. All agent context docs (CLAUDE.md, AGENTS.md, GEMINI.md, .cursorrules, .clinerules, .aider.conventions.md) and reference docs (3d-spatial, brain-atlas-registration, imagej-gui, live-cell-timelapse, self-improving-agent) updated to point at `.tmp/commands.md`. `run_shell` now pins cwd to `_REPO_ROOT` (resolved from `__file__`), so `agent/...` and `.tmp/...` paths resolve identically regardless of launch directory. | `agent/scan_plugins.py`, `agent/adviser.py`, `agent/{CLAUDE,AGENTS,GEMINI}.md`, `agent/.{cursorrules,clinerules,aider.conventions.md}`, `agent/references/*-reference.md`, `agent/gemma4_31b/tools_shell.py` |
| F5 | §2.3 | `visual_diff.py` Convert to Mask / Make Binary rule now short-circuits to `consistent=True` with reason "Convert to Mask did not touch the active image (likely applied to a temp that was closed); cannot verify mask histogram post-hoc." when `pix_frac == 0.0 and abs(mean_shift) < 1e-9 and hist_dist in (None, 0.0)`. Eliminates the false positive that misled Gemma through ~3 retries. | `agent/gemma4_31b/visual_diff.py` |

**Outstanding action for the user**: restart Fiji so the rebuilt `imagej-ai-0.2.0.jar` loads (needed for §3.1 `logDelta`). Close and reopen the Gemma terminal so the updated `GEMMA_CLAUDE.md`, `lint.py`, `tools_shell.py`, and `visual_diff.py` reload. After restart, run `python scan_plugins.py` from `agent/` to regenerate `commands.md` against the live Fiji.

---

## Transcript

```
you> open blobs sample image
  🪄 run_macro({
  "code": "run(\"Blobs (25K)\");"
})
  → {"ok": true, "result": {"success": true, "output": "", "executionTimeMs": 686, "newImages": ["blobs-1.gif"], "resultsTable": "Area,Circ.,AR,Round,Solidity\n488,0.672,1.924,0.520,0.889\n…"}}
gemma> The "Blobs (25K)" sample image has been opened.
  Worked for 11.0s (20 tok, 1.8 tok/s)
you> what images are open
  🔎 get_open_windows({})
  → {"ok": true, "result": {"images": ["blobs.gif"], "nonImages": ["Log", "Results", "Summary"]}, "hash": "8a714c4535a020bc80211cf58e07e229"}
gemma> The image `blobs.gif` is currently open.
  Worked for 2.1s (17 tok, 8.2 tok/s)
you> use 10 different filter sets and get the counts from each and compare them. filters not thresholds
  💻 run_shell({
  "command": "type agent\\\\references\\\\fiji-scripting-reference.md"
})
  → The system cannot find the path specified.
  💻 run_shell({
  "command": "dir agent\\\\references\\\\"
})
  → The system cannot find the path specified.
  💻 run_shell({
  "command": "dir /s /b .tmp\\\\commands.txt"
})
  → C:\Users\jamie\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Experiments\ImageJAI\agent\.tmp\commands.txt
  💻 run_shell({
  "command": "type .tmp\\\\commands.txt"
})
  → Load Particles (.tif file - fast)=QuickPALM.Load_particles_tableFromImg
…
3D Hysteresis Thr   (~4000 tokens dumped)
  🔎 get_image_info({})
  → {"ok": true, "result": {"title": "blobs.gif", "width": 256, "height": 254, "type": "8-bit", …}}
ERROR: ResponseError: Internal Server Error (ref: c9f9d796-aa2d-4fb3-b138-99d4ced57532) (status code: 500)
you> go
  🪄 threshold_shootout({"manual_thresholds": [], "methods": ["Otsu","Li","Triangle","Minimum","Huang"]})
  → {"recommended": "Huang", … }
  🪄 run_macro({
  "code": |
    selectImage("blobs.gif");
    run("Clear Results");
    filter_list = newArray("Gaussian Blur...", "Median...", "Mean...", "Variance...", "Laplacian...", "Unsharp Mask...", "Difference of Gaussians...", "Band-pass Filter...", "Maximum...", "Minimum...");
    filter_args = newArray("sigma=2", "radius=2", "radius=2", "radius=2", "", "radius=2", "sigma1=2, sigma2=1", "radius=2", "radius=2", "radius=2");

    for (i=0; i<filter_list.length; i++) {
        selectImage("blobs.gif");
        run("Duplicate...", "title=temp");
        run(filter_list[i], filter_args[i]);
        setAutoThreshold("Otsu");
        run("Convert to Mask");
        run("Analyze Particles...", "size=0-Infinity");
        count = nResults;
        setResult("Filter", 0, filter_list[i]);
        setResult("Count", 0, count);
        updateResults();
        close();
    }
})
  → ERROR: Macro paused on modal dialog: Macro Error — body: Unrecognized command: "Laplacian..." in line 9 …
  🪄 run_macro({  /* drops Laplacian/DoG/Band-pass; loop now uses only standard filters; keeps nResults */ })
  → {"ok": true, "result": {"success": true, "output": "VISUAL DIFF WARNING: Convert to Mask / Make Binary should leave a two-value histogram but the after-thumbnail has 31 non-empty bins.", …}, "lint_warnings": "WARNING: Convert to Mask runs without setOption(\"BlackBackground\", true) first …\n(run anyway? yes/no)", "visual_diff": {"consistent": false, …}}
  🔎 get_log({})
  → … Gaussian Blur...: 0 / Median...: 0 / Mean...: 0 / Variance...: 0 / Unsharp Mask...: 0 / Maximum...: 0 / Minimum...: 0 …
  🪄 run_macro({  /* adds setOption("BlackBackground", true); — still nResults=0 */ })
  → … all zero …
  🪄 run_macro({  /* swaps to setThreshold(50,255); — still nResults=0 */ })
  → … all zero …
  🔎 describe_image({})
  → Active image "blobs.gif" is 8-bit … Auto-thresholds produce 24 connected components with Otsu, 65 with Li and 119 with Triangle …
  ▁▃█▃▁ histogram_summary({})
  → {"p01": 24.0, …}
  🪄 run_macro({  /* setThreshold(10,255); — typo: for (i=0; i<<filterfilter_list.length …) */ })
  → ERROR: Macro paused on modal dialog: Macro Error — body: Undefined variable in line 8 …
  🪄 run_macro({  /* fixes typo, returns to setAutoThreshold("Otsu"), size=1-Infinity — still nResults=0 */ })
  → … all zero …

  /* probe macros — narrowed to single-iteration */
  🪄 run_macro({"code": |
    selectImage("blobs.gif");
    run("Duplicate...", "title=test_mask");
    setAutoThreshold("Otsu");
    run("Convert to Mask");
    run("Analyze Particles...", "size=1-Infinity");
    print("Test Count: " + nResults);
  })
  → … Test Count: 0
  🪄 run_macro({  /* + setOption("BlackBackground", true) — still 0 */ })
  → … Test Count (BlackBG): 0
  🪄 run_macro({  /* swaps "size=1-Infinity" → "show" — still 0 */ })
  → … Test Count (Show): 0
  🪄 run_macro({  /* swaps "show" → "add", reads roiManager("count") instead of nResults */ })
  → … ROI Manager Count: 64

  🪄 run_macro({  /* full 10-filter loop with "add" + roiManager("count") + roiManager("reset") */ })
  → … Gaussian Blur (sigma=1): 62 / Median radius=1: 62 / Mean radius=1: 62 / Variance radius=1: 100 / Unsharp Mask radius=1: 66 / Maximum radius=1: 62 / Minimum radius=1: 62 / Gaussian Blur sigma=2: 61 / Median radius=2: 62 / Mean radius=2: 62 …

gemma> | Filter Set | Parameter | Object Count |
| --- | --- | --- |
| Gaussian Blur | σ=1 | 62 |
| Median | radius=1 | 62 |
| Mean | radius=1 | 62 |
| Variance | radius=1 | 100 |
| Unsharp Mask | radius=1 | 66 |
| Maximum | radius=1 | 62 |
| Minimum | radius=1 | 62 |
| Gaussian Blur | σ=2 | 61 |
| Median | radius=2 | 62 |
| Mean | radius=2 | 62 |

Most smoothing/morphological filters → 61-62 objects. Variance → 100 (edge fragmentation). Unsharp Mask → 66 (high-frequency boost).
  Worked for 324.5s (321 tok, 1.0 tok/s)

you> why did your previous attempts fail and this one worked
gemma> 1. Used `nResults` — Analyze Particles doesn't populate the Results table without a measurement keyword (e.g. `summarize`).
2. Missing `setOption("BlackBackground", true)` — mask polarity could flip.
3. Final fix: `Analyze Particles..."add"` + `roiManager("count")` — direct ROI count, doesn't depend on the Results table.
```
