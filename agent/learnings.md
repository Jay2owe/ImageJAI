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
2. Check state (`python ij.py state`) and histogram (`python ij.py histogram`)
3. Capture and inspect (`python ij.py capture`)
4. **Pre-process first**: Gaussian blur (sigma=1-2) to reduce noise
5. Apply threshold — if auto doesn't work, **iterate don't abandon**:
   - Try multiple methods: Otsu, Triangle, Li, MaxEntropy
   - Check histogram to understand intensity distribution
   - Set manual threshold based on histogram: `setThreshold(lower, upper);`
   - For fluorescence: `"dark"` background flag is usually needed
6. Capture and inspect — verify mask looks right
7. Watershed if particles are touching
8. Capture and inspect — verify separation
9. Analyze Particles with appropriate size filter
10. Get results table (`python ij.py results`)

### CRITICAL: Never abandon a working approach
If auto-threshold finds too few/many objects, DO NOT switch to a completely
different method (like Find Maxima). Instead:
- Check the histogram to understand the intensity range
- Pre-filter the image (Gaussian blur, background subtraction)
- Try a different auto threshold method
- Set a manual threshold based on what you see
- Adjust size/circularity filters in Analyze Particles
The cells are visible — the parameters just need tuning.

### Use 3D tools for 3D data — don't flatten to 2D
When working with z-stacks:
- Do NOT make a max projection just to find cells — this destroys z-information
- Use 3D plugins directly (this Fiji has 64 3D commands from 3D ImageJ Suite):
  - `3D Objects Counter` — threshold + label objects in 3D
  - `3D Segment` — 3D segmentation
  - `3D Centroid` — find 3D object centers
  - `3D Crop` — crop around a 3D object
  - `3D Viewer` / `Volume Viewer` — 3D rendering
- Workflow for "find a cell and render it in 3D":
  1. Pre-process the stack (blur in 3D: `run("Gaussian Blur 3D...", "x=2 y=2 z=1");`)
  2. Threshold the stack directly (applies to all slices)
  3. Use `3D Objects Counter` to label connected components in 3D
  4. Pick a well-isolated object by size/position
  5. Crop around it using `3D Crop` or manual `makeRectangle` + `duplicate`
  6. Render with `3D Viewer` or `Volume Viewer`
- Max projection is for VISUALIZATION only, never for 3D analysis workflows

### Clean up after yourself — no window clutter
- Close temporary images, results tables, and plots as soon as you're done with them
- After Analyze Particles: close the "Drawing of..." outlines window if not needed
- After max projections used for inspection: close them immediately
- After Find Maxima: close the point selection / count window
- Check with `python ij.py windows` to see what's open and close stale windows
- Use `close("window title");` for specific windows or `close("*");` only when starting fresh
- The user's workspace should only have images they care about, not agent debris
- Rule: if you opened it for intermediate analysis, you close it when done

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
