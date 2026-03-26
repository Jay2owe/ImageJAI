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

## CRITICAL: Never Oversaturate Images

**Oversaturation is unacceptable in microscopy.** When pixel intensities are
clipped to the maximum value (255 for 8-bit, 65535 for 16-bit), real intensity
differences are destroyed and quantitative analysis becomes invalid. Journals
will reject figures with blown-out pixels, and reviewers treat it as data
manipulation.

### What causes it:
- `run("Enhance Contrast...", "saturated=0.3 normalize process_all");` — `normalize`
  permanently rescales pixel values, clipping the top 0.3% to max. Irreversible.
- Converting 16-bit → 8-bit without setting display range first
- Any operation that permanently modifies pixel values to fill the dynamic range

### Why it's bad beyond just saturation:
- **Destroys the user's ability to re-adjust later.** If you save enhanced data,
  the original intensity relationships are gone. The user can always enhance
  display later themselves, but they can never undo your enhancement.
- Journals reject it. Reviewers treat it as data manipulation.

### What to do instead:
- **NEVER use Enhance Contrast on output data.** Do not enhance contrast at all
  on images that will be saved or rendered. The raw intensity IS the data.
- If you need better visibility for YOUR inspection during processing, use
  `setMinAndMax(low, high)` which only changes the DISPLAY range, not the pixels.
- **For 3D renders**: do NOT enhance. The projection already accumulates signal
  along the ray path. The raw data renders fine.
- **For quantitative work**: NEVER modify pixel values. Only adjust display
  range (Min/Max sliders). Use `setMinAndMax()` not `normalize`.
- **Let the user decide**: save the raw data. They can always adjust contrast
  themselves. They can never undo yours.

### Quick check for saturation:
```bash
python ij.py histogram
# If max == 255 (8-bit) or 65535 (16-bit) AND the last bin has many pixels,
# the image is saturated. Also check after processing steps.
```

## CRITICAL: Incucyte Calibration Breaks Subtract Background

Incucyte images have spatial calibration of ~0.01 inch/pixel. `Subtract Background...`
(rolling ball and sliding paraboloid) interprets the radius in calibrated units, so
`rolling=50` becomes ~5000 pixels, zeroing out the entire image.

**Always remove calibration before using Subtract Background on Incucyte images:**
```
run("Properties...", "pixel_width=1 pixel_height=1 voxel_depth=1 unit=pixel");
```

Note: `Gaussian Blur...` uses pixel units regardless of calibration, which is why
Gaussian subtraction works fine but rolling ball/paraboloid doesn't.

## Incucyte GFP Extraction: HSB Saturation + Double Paraboloid

Best pipeline for extracting GFP signal from Incucyte RGB stacks:
1. Remove calibration (see above)
2. RGB → HSB Stack → extract Saturation channel (isolates GFP from phase contrast)
3. Sliding Paraboloid r=50 (removes broad ventricle glow)
4. Sliding Paraboloid r=15 (cleans residual edge glow)
5. Median r=1 (fills single-pixel holes from HSB conversion)

Recipe: `recipes/incucyte_gfp_extraction.yaml`

Key findings:
- HSB Saturation naturally separates GFP (colour-saturated) from phase contrast (grey)
- HSB conversion creates single-pixel holes at near-zero brightness pixels
- Median filter should go AFTER paraboloid to catch any holes enlarged by subtraction
- Double paraboloid (large then small) handles both broad glow and edge glow

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

### 3D rendering: ranking of methods (best → worst)

**1. 3Dscript (BEST — true volume raycasting, scriptable)**
- Installed as `Batch Animation` / `Interactive Animation` (animation3d package)
- Natural language animation scripts: `From frame 0 to frame 100 rotate by 360 degrees horizontally`
- Macro syntax:
  ```
  run("Batch Animation", "animation=[/path/to/script.txt] width=512 height=512");
  ```
- Script file is a plain text file with one instruction per line
- Produces smooth volume-rendered rotation with proper depth/opacity
- Output resolution = input image dimensions (width/height args are ignored)
  so scale up the input image XY BEFORE running 3Dscript
- Renders bounding box and scale bar by default (no way to disable via batch macro)
- Input MUST be 8-bit
- Z-staircase artefact at oblique angles when few Z slices (inherent to data,
  not a rendering bug — confocal z-steps are ~3.5x coarser than XY pixels)
- Do NOT Z-interpolate before 3Dscript — bicubic interpolation of masked data
  (0→signal→0) produces very dim interpolated slices that fall below 3Dscript's
  alpha threshold, making the cell invisible. Feed the raw z-stack instead.

**2. 3D Viewer (GOOD — interactive OpenGL volume rendering)**
- TCP API: `python ij.py 3d add IMAGE volume 50` then `3d capture`
- Produces smooth volume renders with proper depth/opacity
- Interactive — user can rotate/zoom in the Fiji window
- `3d capture` uses Robot to screenshot the actual window (preferred over
  `3d snapshot` which has a sizing bug — renders tiny in corner)
- `3d capture` may grab the wrong window if other windows are in front
- Input MUST be 8-bit
- addContent classloader bug FIXED: scan methods by name+param count
  instead of exact class matching (org.jogamp vs org.scijava vecmath)

**3. 3D Project (FALLBACK — rotating max intensity projection)**
- `run("3D Project...", "projection=[Brightest Point] axis=Y-Axis ...")`
- NOT a volume render — it's a MIP at each rotation angle
- No depth/opacity — just brightest voxel along each ray
- Z-striping at side views regardless of interpolation because it renders
  voxels as discrete columns (3D Project doesn't interpolate during projection)
- Works reliably via macro every time — good fallback when 3D Viewer/3Dscript
  have issues

### Cell isolation workflow (REQUIRED before any 3D render)

**ISOLATE FIRST** — cropping alone is NOT enough. Neighbouring cells bleed into
the render. The volume must contain ONLY the target cell on black background.

1. **Find cells**: `python pixels.py find_cells` — pick by area + intensity
2. **Segment**: Duplicate, blur, run 3D Objects Counter:
   ```
   run("Duplicate...", "title=seg duplicate");
   run("Gaussian Blur 3D...", "x=2 y=2 z=1");
   run("3D Objects Counter", "threshold=37000 min.=100 max.=999999 objects");
   ```
3. **Find target label**: Read pixel value in objects map at the cell's (x,y) position.
   Check multiple positions/slices — the cell may not be at exactly the centroid
   from `find_cells` (that's from a single-slice 2D analysis).
4. **Create binary mask (0/1)**: From the objects map, set target label → 1,
   everything else → 0.
5. **Multiply mask × original**: Use `imageCalculator("Multiply create stack", ...)`
   NOT `AND` — bitwise AND between 8-bit mask and 16-bit image corrupts values
   (only keeps the low byte).
6. **Crop tightly**: `makeRectangle(x, y, w, h); run("Crop");`
7. **Do NOT enhance contrast** — raw intensity is the data.

### 3D rendering: complete recipe

```
// After isolation (step 6 above):
// For 3Dscript (PREFERRED):
run("8-bit");
run("Scale...", "x=10 y=10 z=1.0 interpolation=Bicubic process create title=cell_big");
// Write script file: "From frame 0 to frame 100 rotate by 360 degrees horizontally"
run("Batch Animation", "animation=[/path/to/rotate.animation.txt]");

// For 3D Viewer:
run("8-bit");
// Then via TCP: python ij.py 3d add IMAGE_TITLE volume 20
// Then: python ij.py 3d fit
// Then: python ij.py 3d capture name

// For 3D Project (fallback):
run("Scale...", "x=10 y=10 z=1.0 interpolation=Bicubic process create title=cell_big");
run("3D Project...", "projection=[Brightest Point] axis=Y-Axis slice=0.28 initial=0 total=360 rotation=10 lower=1 upper=255 opacity=0 surface=100 interior=50");
run("Cyan Hot");
```

### Key 3D rendering lessons
- **ISOLATE FIRST** — always segment and mask, never just crop
- **Multiply not AND** — for masking 16-bit images, use imageCalculator Multiply
  with a 0/1 mask. Bitwise AND corrupts 16-bit data through an 8-bit mask.
- **8-bit for renderers** — 3D Viewer and 3Dscript both need 8-bit input
- **Scale XY before 3Dscript** — output size = input image size
- **Do NOT Z-interpolate for 3Dscript** — interpolated masked data falls below
  alpha threshold and renders invisible. Feed raw z-stack instead.
- **Z-staircase is a data limitation** — 13 confocal slices at ~1µm spacing
  will always show steps from the side. Only more z-slices during acquisition
  can fix this.
- **No contrast enhancement** — raw intensity is the data. User adjusts later.

### Save deliverables next to the source image, NOT in .tmp/
- `.tmp/` is for agent working captures only (visual feedback for the agent)
- When the user asks you to save/produce something, save it in an `AI_output/`
  subdirectory within the directory the source image came from
- Get the source directory with: `dir = getInfo("image.directory");`
  (note: this prints to Log, not macro return — check with `python ij.py log`)
- Create the output dir: `File.makeDirectory(dir + "AI_output");`
- Save there: `saveAs("PNG", dir + "AI_output/filename.png");`
- This keeps outputs organized next to the data they came from

### ALWAYS probe plugins before using them
- `python probe_plugin.py "Plugin Name..."` discovers ALL parameters
- Returns: field types, labels, defaults, dropdown options, slider ranges, macro syntax
- Results are cached in `.tmp/plugin_args/` — subsequent lookups are instant
- `--batch` flag probes multiple plugins at once
- `--search keyword` searches all cached probes
- This eliminates trial-and-error with macro arguments
- For GenericDialog plugins (vast majority): structured extraction via getNumericFields(),
  getStringFields(), getCheckboxes(), getChoices(), getSliders()
- For custom dialogs: falls back to flat text extraction
- **Probe NEEDS an image to be open** for most plugins — open a test image first
- The probe cancels the dialog without executing — no side effects on the image

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

## Drift Correction for Moving Cells (Incucyte hIba1-EGFP)

### Key insight: register on blue channel
- RGB Incucyte images have green microglia that MOVE — cannot use for registration
- Blue channel has only tissue texture (no fluorescence) — ideal for cross-correlation
- Convert RGB to composite (`run("Make Composite")`), then `channel=3` for blue
- `run("Correct 3D drift", "channel=3 edge_enhance max_shift_x=250 max_shift_y=250")`

### Crop-based registration for large drift
- Full-image registration can be confused by moving cells even in the blue channel
- For large drift (>100px): crop to a stable tissue region, register the crop, apply shifts to full image
- The crop must avoid: black edges from previous registration, fluorescent cells, image borders
- Top-left corner usually good (tissue texture, no cells)
- Save log immediately after registration — log gets cleared between commands

### SIFT does NOT work for refinement
- SIFT rigid alignment on full image completely fails when microglia are present
- Moving cells create spurious feature matches that produce wild rotations
- Never use SIFT on images with moving fluorescent objects

### Green signal extraction (Incucyte GFP)
- HSB Saturation channel separates GFP from phase contrast
- Double sliding paraboloid (r=50 then r=15) removes ambient glow
- Median r=1 fills HSB conversion holes
- Must remove inch calibration first (`pixel_width=1 unit=pixel`)

### Build & deploy
- Build: `JAVA_HOME="/c/Program Files/Java/jdk-25.0.2" mvn clean package -q`
- Deploy to Dropbox Fiji: `Brancaccio Lab/Jamie/Fiji.app/plugins/`
- Fiji caches classes — must restart after deploy

## User-Specific Notes

- User runs Fiji from Dropbox: `C:\Users\jamie\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Fiji.app`
- Fiji caches JARs — must fully restart after deploying new plugin JAR
- User prefers no Co-Authored-By lines on commits
- Fiji has 1966 available commands including StarDist, Cellpose, TrackMate, Weka, CLIJ2 (504 GPU ops), Bio-Formats. Note: ABBA JARs are present but menu commands may not appear — PTBIOP update site needs re-enabling and elastix binary must be installed separately
