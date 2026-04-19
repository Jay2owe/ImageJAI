# 2026-04-19 — blobs sample, ten filter sets

Model: Gemma 4 31B (`gemma4:31b-cloud`) via Ollama.
Context loaded: `agent/gemma4_31b/GEMMA_CLAUDE.md`.

---

## Transcript

```
you> open blobs sample image
  ctx [░░░░░░░░░░░░░░░░░░░░░░░░] 0 / 131.1K (0%)

  ⚡ run_macro({"code": "run(\"Blobs (25K)\");"})

  → {"ok": true, "result": {"success": true, "output": "", "executionTimeMs": 744, "newImages": ["blobs.gif"]}}

  ⚡ get_state({})

  → {"ok": true, "result": {"activeImage": {"title": "blobs.gif", "width": 256, "height": 254, "type": "8-bit", "slices": 1, "channels": 1, "frames": 1, "calibration": "", "isStack": false, "isHyperstack"

  ⚡ get_image_info({})

  → {"ok": true, "result": {"title": "blobs.gif", "width": 256, "height": 254, "type": "8-bit", "slices": 1, "channels": 1, "frames": 1, "calibration": "", "isStack": false, "isHyperstack": false}, "hash"

  ⚡ describe_image({})

  → Active image "blobs.gif" is 8-bit, 256×254 pixels, single channel, single z, single frame. Pixel size is **uncalibrated** (reads as "1 pixel"). Intensity ranges from 8 to 248 with mean 103, median 68

  ⚡ triage_image({})

  → ["No pixel-size calibration \u2014 set with Image > Properties before measuring."]
  ⠙ round 3 (Ctrl-C to abort) (0.1s)
[triage] Fiji opened 'blobs.gif'. Automatic triage warnings: No pixel-size calibration — set with Image > Properties before measuring.

gemma> The "blobs.gif" sample image is open. It is an 8-bit, 256x254 pixel image. Triage indicates it is currently uncalibrated.

How would you like to analyze these blobs?
  (11.5s, 56 tok, 4.9 tok/s)

you> use 10 different filter sets and then tell me the count differences
  ctx [░░░░░░░░░░░░░░░░░░░░░░░░] 2.4K / 131.1K (2%)

  ⚡ threshold_shootout({})

  → ERROR: no export folder — open an image first or pass --export-dir

  ⚡ run_macro({"code": "run(\"Blobs (25K)\");"})

  → {"ok": true, "result": {"success": true, "output": "", "executionTimeMs": 656, "newImages": ["blobs-1.gif"]}}

  ⚡ threshold_shootout({})

  → ERROR: no export folder — open an image first or pass --export-dir
  ⠸ round 4 (Ctrl-C to abort) (0.4s)
[triage] Fiji opened 'blobs-1.gif'. Automatic triage warnings: No pixel-size calibration — set with Image > Properties before measuring.

  ⚡ get_state({})

  → {"ok": true, "result": {"activeImage": {"title": "blobs-1.gif", "width": 256, "height": 254, "type": "8-bit", "slices": 1, "channels": 1, "frames": 1, "calibration": "", "isStack": false, "isHyperstac

  ⚡ run_macro({"code": "setOption(\"BlackBackground\", true);\nrun(\"Clear Results\");\nrun(\"Duplicate...\", \"title=temp\");\n\n// 1. Otsu\nrun(\"Duplicate...\", \"title=otsu\");\nsetAutoThreshold(\"Otsu\");\nrun(\"Convert to Mask\");\nrun(\"Analyze Particles...\", \"size=0-Infinity show=Nothing\");\nprint(\"Otsu: \" + nResults);\nrun(\"Close\");\n\n// 2. Li ... (10-step macro, full text in original paste)"})

  → ERROR: run("Analyze Particles...", "...") uses argument name(s) that do not exist in the plugin's dialog: show. Valid argument names for this plugin are: add_to_manager, clear_results, composite_rois,

  ⚡ run_macro({"code": "... display_results=false summarize ... run(\"Median...\", \"radius=2\") ..."})

  → ERROR: run("Median...", "...") uses argument name(s) that do not exist in the plugin's dialog: radius. Valid argument names for this plugin are: pixels, preview. Probe the plugin with probe_plugin bef

  ⚡ run_macro({"code": "... run(\"Median...\", \"pixels=2\") ..."})

  → {"ok": true, "result": {"success": true, "output": "", "executionTimeMs": 1165, "newImages": ["mean5"]}, "plugin_schema": {"Clear Results": ["pixels", "preview", "radius_(sigma)"], "Analyze Particles.
  ⠏ round 8 (Ctrl-C to abort) (3.6s)
[triage] Fiji opened 'default'. Automatic triage warnings: No pixel-size calibration — set with Image > Properties before measuring. | Saturated pixels are 33.3% of the image — lower exposure or flag the data as clipped. | Clipped blacks at 66.7% of pixels — background is pinned to zero, consider offset correction.

  ⚡ get_log({})

  → [AgentLauncher] Context files synced for all agents [AgentLauncher] Launched: Gemma 4 31B (Claude-style) (gemma4_31b_agent) Otsu: 1 Li: 61 Triangle: 1 Default: 61 Gauss2_Otsu: 1 Gauss5_Otsu: 54 Med2_O
```

---

## Initial friction points (pre-research)

1. `threshold_shootout` refused with "no export folder" even though an image was open — `current_export_folder()` returns `None` for images with no path on disk (sample images), and the error message misleads the model into reopening.  <!-- F1: shootout refused on sample image → plan §1, applied -->
2. `auto_probe` rejected `show=Nothing` for Analyze Particles — `show` is a real Choice/dropdown.  <!-- F2: Java findFieldLabel returned first Label in panel, not nearest preceding — mislabelled all but one field → plan §2, code applied, requires Fiji rebuild -->
3. `auto_probe` rejected `radius=` for `Median...` — wrong cached schema, probably from a different plugin sharing the menu token.  <!-- F3: same findFieldLabel bug + label→key kept "(pixels)" suffix → plan §2 + §3 (cache bust), applied -->
4. Auto-probe error message truncated mid-sentence (`Probe the plugin with probe_plugin bef`) — losing the actionable suffix.  <!-- F4: truncation is terminal-preview only; model input is full. Added "… [+N.NKB]" marker → plan §4, applied -->
5. Auto-triage fired on intermediate mask images (`default`) created by the agent — produces nonsense "33% saturated / 67% clipped" warnings on binaries.  <!-- F5: tracked agent-created titles + mask keyword heuristic → plan §5, applied -->
6. Reflex over-inspection at start: `get_state` + `get_image_info` + `describe_image` + `triage_image` + auto-triage banner = five overlapping reads for one sample image.  <!-- F6: prompt workflow trimmed → plan §6, applied -->
7. Counts emitted via `print(...)` and recovered from `get_log` — should use `setResult(...)` for clean tabular return.  <!-- F7: prompt nudge added → plan §7, applied -->
8. Tool-result truncation in display vs model input — needs verification it's display-only.  <!-- F8: verified cosmetic only; same fix as F4 → plan §4, applied -->

---

## Applied — 2026-04-19

| # | Section | Change | File |
|---|---|---|---|
| F1 | §1a | fallback to `agent/.tmp/shootout/` when current_export_folder is None | `threshold_shootout.py` |
| F1 | §1b | error message distinguishes "no image open" from "sample image open" | `safety.py` |
| F1 | §1c | capture_image falls back to `agent/.tmp/captures/` | `tools_fiji.py` |
| F2 | §2 | `findFieldLabel` walks backwards from field's own index; `labelToMacroKey` strips parenthetical suffix | `TCPCommandServer.java` *(rebuild + redeploy needed)* |
| F3 | §3 | busted `.plugin_cache/*.json`; will re-populate after Fiji rebuild | `.plugin_cache/` |
| F4/F8 | §4 | terminal preview shows `… [+N.NKB]` when truncated; model input unchanged | `loop.py` |
| F5 | §5 | TTL cache of agent-created image titles + mask keyword filter; auto-triage skips them | `events.py`, `tools_fiji.py`, `loop.py` |
| F6 | §6 | workflow trimmed; tool table notes describe_image / triage_image run automatically | `GEMMA_CLAUDE.md` |
| F7 | §7 | prompt nudges `setResult` over `print` for labelled numbers | `GEMMA_CLAUDE.md` |

**Outstanding action for the user**: Java change requires `bash build.sh` then restart Fiji. Plugin cache is already cleared so probes will re-populate with correct schemas on next use.
