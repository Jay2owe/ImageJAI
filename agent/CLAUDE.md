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
python ij.py raw '{"command": "ping"}'                # raw JSON command
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

---

## Your Workflow

1. **Check state first**: `python ij.py state` — know what's open
2. **Execute macros**: `python ij.py macro '...'` — do the work
3. **Capture and LOOK**: `python ij.py capture step_name` then Read the PNG
4. **Verify results**: `python ij.py results` — check measurements
5. **Iterate**: if something looks wrong, fix the macro, retry

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
