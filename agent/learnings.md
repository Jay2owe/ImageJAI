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

### Proper workflow for isolating and rendering a single cell in 3D
1. **Filter a working copy** to separate cells — Gaussian blur, threshold. This is
   just for FINDING cells, not for the final output
2. **3D Objects Counter with REDIRECT** — this is the key:
   ```
   run("3D Objects Counter", "threshold=128 min.=200 max.=999999 objects
        redirect=ORIGINAL_IMAGE_TITLE");
   ```
   The `redirect` option means it segments on the filtered image but produces
   the mask/measurements from the original unfiltered image. This gives clean
   intensity-preserved objects.
3. **Extract the desired object** from the label map — pick by size, position,
   or intensity. The label map has unique integer IDs per object.
4. **Render with 3D tools** — with a clean isolated object on black background,
   3D Viewer and 3Dscript work properly:
   ```
   run("3D Viewer");        // interactive OpenGL rendering
   run("3Dscript");         // scripted animation/rendering
   ```
   These struggled before because background/neighbouring cells cluttered the view.

**DO NOT** try to manually multiply masks by images — 3D Objects Counter's
redirect does this automatically and correctly. Don't reinvent what plugins
already provide.

### 3D rendering: use actual 3D renderers, NOT orthogonal projections
- Orthogonal XY/XZ/YZ montages are NOT 3D renders — they're flat 2D slices
- Only use orthogonal views if the user specifically asks for them
- For actual 3D rendering use:
  - `3D Viewer` — interactive OpenGL volume/surface rendering (may block macro)
  - `Volume Viewer` — simpler volume rendering (check exact parameter names)
  - `3Dscript` — scriptable 3D animation/rendering (if installed)
- If 3D plugins block the macro thread, the agent should detect the timeout
  and inform the user that the 3D viewer is open for interactive use
- The 3D Viewer CAN be controlled via `call()` functions:
  ```
  call("ij3d.ImageJ_3D_Viewer.add", "title", "None", "title", "50", "true", "true", "true", "2", "0");
  call("ij3d.ImageJ_3D_Viewer.snapshot", "512", "512");
  ```
  But these may timeout — handle gracefully
- **3D Viewer snapshot issue**: `takeSnapshot()` and manual View > Take Snapshot
  both produce a tiny image in the corner of a large black canvas. The 3D Viewer
  renders at the correct zoom in its window but the snapshot doesn't match.
  This is a known 3D Viewer bug. WORKAROUND NEEDED — possibly use OS-level
  screenshot of the 3D Viewer window instead of the built-in snapshot.
- **3D Viewer API via TCP** — the `3d` commands work reliably:
  - `3d add IMAGE volume 50` — adds content (uses addContent(ImagePlus, int, int))
  - `3d fit` — calls resetView() to fit content
  - `3d list` — lists loaded content
  - `3d snapshot` — takes snapshot (but has the sizing bug above)
  - `3d status` — checks if open and what's loaded
  - `3d close` — closes the viewer
  - Universe reference is cached across calls
- **3D Viewer needs 8-bit images** — convert before adding
- **Non-binary volumes look blocky** — 3D Viewer doesn't interpolate between
  z-slices for intensity data. For smooth surfaces, threshold to binary first.

### Save deliverables next to the source image, NOT in .tmp/
- `.tmp/` is for agent working captures only (visual feedback for the agent)
- When the user asks you to save/produce something, save it in an `AI_output/`
  subdirectory within the directory the source image came from
- Get the source directory with: `dir = getInfo("image.directory");`
  (note: this prints to Log, not macro return — check with `python ij.py log`)
- Create the output dir: `File.makeDirectory(dir + "AI_output");`
- Save there: `saveAs("PNG", dir + "AI_output/filename.png");`
- This keeps outputs organized next to the data they came from

### Dialogs: read them immediately, don't ignore them
- After EVERY macro execution, check the response for `"dialogs"` in the result
- If dialogs appeared, READ their content before doing anything else
- Error dialogs tell you exactly what went wrong — use that info to fix the macro
- If a plugin opens a dialog with settings (like Volume Viewer), read all the
  fields/dropdowns/sliders to understand what parameters it accepts
- Use `python ij.py dialogs` to check for open dialogs at any time
- Use `python ij.py close_dialogs` to dismiss them after reading
- NEVER just blindly close dialogs without reading what they say

### Run macros headless where possible
- Use `setBatchMode(true);` at the start of processing macros to suppress
  unnecessary display updates and avoid dialogs where possible
- Remember to `setBatchMode(false);` at the end
- This also speeds up execution 10-100x for batch operations
- Some plugins still show dialogs even in batch mode — those need to be read

### ALWAYS fix things, never work around them manually
- The goal is COMPLETE autonomous control of ImageJ — no manual user steps
- If something doesn't work (3D Viewer, dialog reading, snapshot), FIX THE CODE
- Never say "you can do it manually" — that defeats the entire purpose
- Every manual workaround is a failure to be addressed by editing the plugin code
- If a feature is missing, add it. If a command is broken, fix it. Always.

### NEVER close the ImageJ/Fiji main window
- The main toolbar window title is "Fiji" (NOT "ImageJ") in Fiji installations
- `close("*")` will close it — NEVER use `close("*")` blindly
- `close_dialogs` with no pattern WAS closing it because the protection
  only checked for "ImageJ" not "Fiji" — THIS BUG CAUSED THE TOOLBAR TO VANISH
- Fixed: close_dialogs now protects "ImageJ", "Fiji", anything containing
  "ImageJ", and checks against IJ.getInstance() directly
- Always close windows BY NAME: `close("specific_title");`
- Protected windows that must NEVER be closed:
  - The main Fiji/ImageJ toolbar window
  - The AI Assistant window
- Before closing any window, verify its title is one YOU created
- If toolbar vanishes, user must restart Fiji — there's no reliable macro
  to restore it

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
