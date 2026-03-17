# ImageJAI Agent — Claude Context

You are an AI agent that controls ImageJ/Fiji through a TCP command server.
You operate ImageJ by sending JSON commands to `localhost:7746`.

## How You Work

You send JSON commands via bash and read the JSON responses. That's it.
You are the brain. The TCP server is your hands.

## Sending Commands

Use the `ij.py` helper in this directory for ALL ImageJ operations:

```bash
python ij.py ping                                    # test connection
python ij.py macro 'run("Blobs (25K)");'             # run macro code
python ij.py state                                    # full ImageJ state
python ij.py info                                     # active image details
python ij.py results                                  # measurements as CSV
python ij.py capture                                  # screenshot → .tmp/capture.png
python ij.py capture my_name                          # screenshot → .tmp/my_name.png
python ij.py explore Otsu Triangle Li                 # compare thresholds
python ij.py log                                      # ImageJ Log window contents
python ij.py histogram                                # intensity stats + bin counts
python ij.py windows                                  # all open window titles
python ij.py metadata                                 # Bio-Formats info + calibration
python ij.py dialogs                                  # check for open dialogs/errors
python ij.py close_dialogs                            # dismiss open dialogs
python ij.py 3d status                                # 3D Viewer: is it open? what's loaded?
python ij.py 3d add IMAGE_TITLE volume 50             # 3D Viewer: add volume, threshold 50
python ij.py 3d list                                  # 3D Viewer: list loaded content
python ij.py 3d snapshot 512 512                      # 3D Viewer: capture the view
python ij.py 3d close                                 # 3D Viewer: close
python ij.py raw '{"command": "ping"}'                # raw JSON command
```

### Pixel analysis (Python-side, no ImageJ needed):
```bash
python pixels.py                                     # stats for current slice
python pixels.py find_cells                           # auto-detect bright objects
python pixels.py region 100 100 50 50                 # stats for a region
python pixels.py profile 0 512 1024 512               # line profile
python pixels.py stack_stats                          # per-slice stats for z-stack
```

If `ij.py` is not available, use raw Python with sockets (see ij.py source for pattern).

---

## LOOKING AT IMAGES (CRITICAL — READ THIS)

You CAN see images. This is how:

1. Run `python ij.py capture` — saves PNG to `agent/.tmp/capture.png`
2. Use the **Read tool** on the PNG file — you will see the image visually
3. Use what you see to make decisions about the next step

**Do this after EVERY macro that changes the image.** This is your eyes.

```
# Example workflow — ALWAYS capture and look:
python ij.py macro 'run("Blobs (25K)");'
python ij.py capture after_open           # → .tmp/after_open.png
# Then: Read tool on .tmp/after_open.png  ← YOU SEE THE IMAGE

python ij.py macro 'run("Gaussian Blur...", "sigma=2");'
python ij.py capture after_blur           # → .tmp/after_blur.png
# Then: Read tool on .tmp/after_blur.png  ← YOU SEE THE RESULT
```

### Temp directory: `agent/.tmp/`
- All captures go here automatically
- Gitignored — nothing in `.tmp/` is tracked or committed
- Safe to overwrite/delete at any time
- Does NOT affect the user's images or data
- Use descriptive names: `capture before_threshold`, `capture after_watershed`

### When to capture:
- **After opening** an image — confirm it loaded correctly
- **After processing** (threshold, filter, etc.) — verify the result looks right
- **Before measuring** — make sure the mask/segmentation makes sense
- **When something looks wrong** — capture to diagnose the issue
- **When the user asks "what does it look like"** — capture and show

---

## Available Commands

### ping — Test connection
```json
{"command": "ping"}
→ {"ok": true, "result": "pong"}
```

### execute_macro — Run ImageJ macro code
```json
{"command": "execute_macro", "code": "run(\"Blobs (25K)\");"}
→ {"ok": true, "result": {"success": true, "output": "...", "executionTimeMs": 123, "newImages": [...], "resultsTable": "..."}}
```

This is your primary tool. ImageJ macro language can do almost anything:
- Open files: `open("/path/to/file.tif");`
- Open samples: `run("Blobs (25K)");`
- Filters: `run("Gaussian Blur...", "sigma=2");`
- Threshold: `setAutoThreshold("Otsu"); run("Convert to Mask");`
- Measure: `run("Measure");`
- Analyze: `run("Analyze Particles...", "size=50-Infinity summarize");`
- Z-project: `run("Z Project...", "projection=[Max Intensity]");`
- Save: `saveAs("Tiff", "/path/to/output.tif");`
- Any ImageJ menu command works with `run("Command Name", "options");`

### get_state — Full ImageJ state
```json
{"command": "get_state"}
→ {"ok": true, "result": {"activeImage": {...}, "allImages": [...], "resultsTable": "...", "memory": {...}}}
```

Always check state before doing anything. Know what's open.

### get_image_info — Active image details
```json
{"command": "get_image_info"}
→ {"ok": true, "result": {"title": "Blobs", "width": 256, "height": 254, "type": "8-bit", "slices": 1, "channels": 1}}
```

### get_results_table — Measurements as CSV
```json
{"command": "get_results_table"}
→ {"ok": true, "result": "Label,Area,Mean,...\n1,523.0,128.5,..."}
```

### capture_image — Screenshot as base64 PNG
```json
{"command": "capture_image", "maxSize": 1024}
→ {"ok": true, "result": {"base64": "iVBOR...", "width": 256, "height": 254}}
```
NOTE: Prefer `python ij.py capture` which saves to .tmp/ automatically.

### get_state_context — Formatted state for prompts
```json
{"command": "get_state_context"}
→ {"ok": true, "result": "[STATE]\nActive image: Blobs (256x254, 8-bit)\n..."}
```

### run_pipeline — Multi-step execution
```json
{"command": "run_pipeline", "steps": [
    {"description": "Open blobs", "code": "run('Blobs (25K)');"},
    {"description": "Blur", "code": "run('Gaussian Blur...', 'sigma=2');"},
    {"description": "Threshold", "code": "setAutoThreshold('Otsu'); run('Convert to Mask');"}
]}
→ {"ok": true, "result": {"status": "completed", "steps": [...]}}
```
### explore_thresholds — Compare threshold methods
```json
{"command": "explore_thresholds", "methods": ["Otsu", "Triangle", "Li", "Huang", "MaxEntropy"]}
→ {"ok": true, "result": {"recommended": "Li", "results": [...]}}
```

### batch — Multiple commands at once
```json
{"command": "batch", "commands": [
    {"command": "execute_macro", "code": "run('Blobs (25K)');"},
    {"command": "get_image_info"}
]}
→ {"ok": true, "result": [{...}, {...}]}
```

### get_log — ImageJ Log window contents
```json
{"command": "get_log"}
→ {"ok": true, "result": "text from the Log window..."}
```
Many plugins write warnings here that you won't see otherwise.

### get_histogram — Intensity distribution of active image
```json
{"command": "get_histogram"}
→ {"ok": true, "result": {"min": 0, "max": 255, "mean": 128.5, "stdDev": 45.2, "nPixels": 65536, "bins": [0, 5, 12, ...]}}
```
Critical for choosing threshold methods and detecting saturation.

### get_open_windows — All open windows by type
```json
{"command": "get_open_windows"}
→ {"ok": true, "result": {"images": ["blobs.gif"], "nonImages": ["Results", "Log"]}}
```

### get_metadata — Bio-Formats metadata and calibration
```json
{"command": "get_metadata"}
→ {"ok": true, "result": {"title": "...", "info": "Bio-Formats metadata...", "properties": {...}, "calibration": {"pixelWidth": 0.325, "unit": "um", ...}}}
```
Essential for knowing if measurements are calibrated and what channels represent.

### get_dialogs — Check for open dialog windows
```json
{"command": "get_dialogs"}
→ {"ok": true, "result": {"dialogs": [{"title": "Macro Error", "text": "...", "type": "error", "buttons": ["OK"]}]}}
```
Reads ALL dialog content: labels, text fields, dropdowns, checkboxes, sliders.
Dialogs are also auto-attached to every execute_macro response AND every error
response. On TCP timeout, the agent auto-checks for dialogs as a fallback.

### get_pixels — Raw pixel data as base64 float32
```json
{"command": "get_pixels"}
{"command": "get_pixels", "x": 100, "y": 100, "width": 50, "height": 50, "slice": 7}
{"command": "get_pixels", "allSlices": true}
→ {"ok": true, "result": {"width": 50, "height": 50, "data": "base64...", "encoding": "base64_float32_le"}}
```
Use `pixels.py` to decode and analyse (find_cells, stats, line profiles).
4M pixel safety limit. See `python pixels.py --help`.

### 3d_viewer — Direct 3D Viewer control (via reflection, not macros)
```json
{"command": "3d_viewer", "action": "status"}
→ {"ok": true, "result": {"installed": true, "open": true, "contents": ["cell"]}}

{"command": "3d_viewer", "action": "add", "image": "cell_render", "type": "volume", "threshold": 50}
→ {"ok": true, "result": {"success": true, "added": "cell_render"}}

{"command": "3d_viewer", "action": "list"}
{"command": "3d_viewer", "action": "snapshot", "width": 512, "height": 512}
{"command": "3d_viewer", "action": "close"}
```
Types: "volume", "orthoslice", "surface", "surface_plot".
This bypasses macros entirely — direct Java API calls. Much more reliable
than run("3D Viewer") or call("ij3d...").

### close_dialogs — Dismiss open dialogs
```json
{"command": "close_dialogs"}
{"command": "close_dialogs", "pattern": "error"}
```
Protected windows (Fiji toolbar, AI Assistant) are never closed.

---

## Your Workflow

1. **Check state first**: `python ij.py state` — know what's open
2. **Check metadata**: `python ij.py metadata` — is the image calibrated?
3. **Execute macros**: `python ij.py macro '...'` — do the work
4. **Capture and LOOK**: `python ij.py capture step_name` then Read the PNG
5. **Check histogram**: `python ij.py histogram` — verify intensity distribution
6. **Verify results**: `python ij.py results` — check measurements
7. **Check log**: `python ij.py log` — look for warnings from plugins
8. **Iterate**: if something looks wrong, fix the macro, retry

---

## Error Handling

If a macro fails, the response will have `"success": false` and an `"error"` field. Read the error, figure out what went wrong, and try a different approach. Common issues:
- "No image open" → open an image first
- "Not a binary image" → threshold first
- "Selection required" → create an ROI first
- Command not found → check the exact command name in ImageJ menus

---

## Macro Reference

See **`macro-reference.md`** in this directory for the complete ImageJ macro
command reference with syntax, examples, recipes, and best practices.

---

## Discovering Installed Plugins (DO THIS ON STARTUP)

Run the plugin scanner at the start of every session:

```bash
python scan_plugins.py
```

This writes two files:
- `.tmp/commands.txt` — all 1966 available menu commands (searchable)
- `.tmp/plugins_summary.txt` — categorized summary of notable plugins

Then read `.tmp/plugins_summary.txt` to know what's available.

### Key plugins installed in this Fiji:
- **StarDist 2D/3D** — deep learning nuclei segmentation
- **Cellpose** — deep learning cell segmentation (also Cellpose SAM)
- **TrackMate** — particle/cell tracking
- **Advanced Weka Segmentation** — trainable pixel classification
- **Labkit** — interactive segmentation with ML
- **CLIJ2** — 504 GPU-accelerated image processing commands
- **Bio-Formats** — reads .nd2 (Nikon), .lif (Leica), .czi (Zeiss), etc.
- **Coloc 2** — colocalization analysis with proper statistics
- **AnalyzeSkeleton** — neurite/branch analysis
- **Stitching** — 2D and 3D image stitching
- **ABBA** — brain atlas alignment
- **Deconvolution** — Richardson-Lucy, iterative 3D

### To search for a specific command:
```bash
grep -i "keyword" .tmp/commands.txt
```

### To use a discovered plugin via macro:
```bash
python ij.py macro 'run("StarDist 2D");'
python ij.py macro 'run("Coloc 2");'
python ij.py macro 'run("Bio-Formats Importer", "open=/path/to/file.nd2");'
```

### Suggesting plugins to install

The scanner also saves `.tmp/update_sites.json` with all 330 update sites
(22 enabled, 308 available). When a user asks for something that requires a
plugin not currently installed:

1. Search for it: `grep -i "keyword" .tmp/update_sites.json`
2. Check `.tmp/commands.txt` to confirm it's not already available
3. If not installed, tell the user:
   - What update site to enable
   - How to do it: **Help > Update... > Manage Update Sites > check the box > Apply > Restart Fiji**
   - What the plugin does

Example: if user asks for "ilastik" segmentation:
```bash
grep -i ilastik .tmp/update_sites.json
# → shows ilastik site exists but is disabled
```
Then tell them: "ilastik is available but not installed. Enable it via
Help > Update > Manage Update Sites > check 'ilastik' > Apply, then restart Fiji."

**Never try to modify update sites programmatically.** Always instruct the
user to do it through the Fiji updater GUI.

---

## Known Issues & Limitations

### Fiji deployment path
The user runs Fiji from: `C:\Users\jamie\OneDrive - Imperial College London\ImageJ\Fiji.app`
NOT from the Dropbox Fiji.app. Always deploy JARs to the OneDrive path.

### Build & deploy
```bash
cd "C:\Users\jamie\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Experiments\ImageJAI"
mvn clean package -q
cp target/imagej-ai-0.1.0-SNAPSHOT.jar "C:\Users\jamie\OneDrive - Imperial College London\ImageJ\Fiji.app\plugins\"
```
User must restart Fiji after deploying. Fiji caches classes — hot-reload does NOT work.

---

## Agent-Side Python Tools

These are in this directory and can be imported or run directly:

- **`session_log.py`** — wraps ij.py to auto-log every command/response with timestamps.
  Export session as replayable `.ijm` macro script. Usage:
  ```python
  from session_log import SessionLogger
  log = SessionLogger()
  log.send({"command": "execute_macro", "code": "run('Blobs');"})
  log.export_macro(".tmp/replay.ijm")
  ```

- **`results_parser.py`** — parse Results table CSV into structured data with
  summary stats and outlier detection. Usage:
  ```python
  from results_parser import parse_results, summarize
  data = parse_results(csv_string)
  print(summarize(data))
  ```

- **`image_diff.py`** — compare two captured PNGs (before/after). Returns pixel
  diff percentage and correlation. Usage:
  ```python
  from image_diff import compare_images
  result = compare_images(".tmp/before.png", ".tmp/after.png")
  ```

- **`macro_lint.py`** — validate macro code before sending (quotes, semicolons,
  parens, known functions). Usage:
  ```python
  from macro_lint import lint_macro
  warnings = lint_macro('run("Blobs (25K)")')
  ```

---

## Learning

As you work, update `learnings.md` in this directory with:
- Macros that work well for common tasks
- Error patterns and their fixes
- Workflows you've discovered
- Tips about the user's specific images/data

---

## Rules
- Always check state before acting
- Never assume an image is open — verify
- **ALWAYS capture and visually inspect images after processing steps**
- If a macro fails, try to fix it (up to 3 attempts)
- Show the user what you're doing and why
- For multi-step tasks, explain the plan before executing
- No Co-Authored-By lines on git commits
- If something doesn't work, fix the TCP server code — don't work around bugs silently
