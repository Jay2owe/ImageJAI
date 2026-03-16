# ImageJAI Agent — Learnings

This file is updated by the AI agent as it learns how to operate ImageJ.
Each section documents working patterns, error fixes, and tips.

## Working Macros

### Open sample image
```
run("Blobs (25K)");
```

### Threshold + watershed + analyze particles (complete workflow)
```
setAutoThreshold("Default");
run("Convert to Mask");
run("Watershed");
run("Set Measurements...", "area mean min centroid shape redirect=None decimal=3");
run("Analyze Particles...", "size=50-Infinity show=Outlines display summarize");
```
- "Default" threshold works well for Blobs sample
- Watershed is essential to separate touching particles
- Without watershed: everything merges into 1 giant object
- `size=50-Infinity` filters out noise

### Close all images
```
close("*");
```

## Error Patterns & Fixes

### "Macro execution timed out after 30000ms" — EDT deadlock
**Root cause**: TCPCommandServer dispatched macros to EDT via `invokeLater`,
but `CommandEngine.executeMacroWithTimeout` internally used `invokeAndWait`
to get back onto the EDT — deadlock. The EDT was waiting on `future.get()`
while the future needed the EDT to execute.

**Fix applied**: `handleExecuteMacro` now calls `IJ.runMacro()` directly on
the TCP handler thread. The ImageJ macro interpreter handles its own EDT
dispatch internally.

**Also fixed**: The real root cause was `CommandEngine.MacroTask` using
`SwingUtilities.invokeAndWait()` to run `IJ.runMacro()` on the EDT. This
blocked the EDT for the entire macro duration, preventing GUI operations
(like showing new image windows) from completing. Fixed by removing
`invokeAndWait` — `IJ.runMacro()` runs directly on the executor thread,
and the macro interpreter handles its own EDT dispatch internally.

**All commands now work**: execute_macro, run_pipeline, explore_thresholds,
batch, get_state, get_image_info, get_results_table, capture_image,
get_state_context, ping — all tested and verified.

### "Default dark" threshold too aggressive for Blobs
Using `setAutoThreshold("Default dark")` on the Blobs sample merges all
foreground into one object. Use `setAutoThreshold("Default")` (without dark)
or `setAutoThreshold("Otsu")` instead.

## Workflow Recipes

### Visual feedback loop
1. `python ij.py macro '...'` — execute a step
2. `python ij.py capture step_name` — save screenshot to .tmp/
3. Read tool on `.tmp/step_name.png` — visually inspect
4. Decide next step based on what you see

### Particle analysis from scratch
1. Open image
2. Check state (`python ij.py state`)
3. Capture and inspect (`python ij.py capture`)
4. Apply threshold
5. Capture and inspect — verify mask looks right
6. Watershed if particles are touching
7. Capture and inspect — verify separation
8. Analyze Particles with appropriate size filter
9. Get results table (`python ij.py results`)

### Enumerate all available Fiji commands
```
List.setCommands;
cmds = List.getList;
f = File.open("ABSOLUTE_PATH_TO_OUTPUT.txt");
print(f, cmds);
File.close(f);
```
- `print()` in macros goes to ImageJ's Log window, NOT to the macro return value
- To capture output, write to a file with `File.open()` / `print(f, ...)` / `File.close(f)`
- Must use absolute paths — relative paths resolve to Fiji's install directory, not the agent directory
- The `scan_plugins.py` script automates this and categorizes results

## TCP Command Test Results (all pass)

| Command | Status | Notes |
|---------|--------|-------|
| ping | PASS | instant |
| execute_macro | PASS | runs on TCP handler thread via IJ.runMacro() |
| get_state | PASS | returns images, results, memory |
| get_image_info | PASS | returns active image details |
| get_results_table | PASS | returns CSV |
| capture_image | PASS | returns base64 PNG |
| get_state_context | PASS | returns formatted state string |
| run_pipeline | PASS | multi-step execution works |
| explore_thresholds | PASS | compares methods, recommends best |
| batch | PASS | multiple commands in one call |

## User-Specific Notes

- User runs Fiji from OneDrive, not Dropbox: `C:\Users\jamie\OneDrive - Imperial College London\ImageJ\Fiji.app`
- Fiji caches JARs — must fully restart after deploying new plugin JAR
- User prefers no Co-Authored-By lines on commits
- Fiji has 1966 available commands including StarDist, Cellpose, TrackMate, Weka, CLIJ2 (504 GPU ops), Bio-Formats, ABBA brain atlas
