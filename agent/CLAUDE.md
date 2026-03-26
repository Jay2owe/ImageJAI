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
python ij.py probe "Gaussian Blur..."                 # discover plugin parameters
python ij.py script 'println("hello")'                # run Groovy inside Fiji's JVM
python ij.py script --file path/to/script.groovy      # run Groovy file
python ij.py script --lang jython 'print("hello")'   # run Jython script
python ij.py raw '{"command": "ping"}'                # raw JSON command
python ij.py ui list                                  # list all dialog components
python ij.py ui list "Dialog Title"                   # components in specific dialog
python ij.py ui click "OK"                            # click button by text
python ij.py ui check "3D Object Analysis" true       # set checkbox on/off
python ij.py ui toggle "Create Bin File"              # flip checkbox state
python ij.py ui text "sigma" 2.5                      # set text field by label
python ij.py ui texti 0 hello                         # set text field by index
python ij.py ui dropdown "Method" Otsu                # select dropdown value
python ij.py ui slider 0 128                          # set slider by index
python ij.py ui spinner 0 42                          # set spinner by index
python ij.py ui scroll 0 50                           # set scrollbar by index
python ij.py ui tab "Advanced"                        # focus a tab
```

### Plugin argument discovery (with caching):
```bash
python probe_plugin.py "Gaussian Blur..."              # probe + cache + pretty print
python probe_plugin.py --batch "Median..." "Subtract Background..." "Analyze Particles..."
python probe_plugin.py --search threshold              # search cached probes
python probe_plugin.py --lookup "Gaussian Blur..."     # check cache only
python probe_plugin.py --list                          # list all cached
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
**NOTE:** For automated 3D renders, prefer `3D Project...` macro (see Quick Recipes).
The 3D Viewer TCP API is useful for interactive volume rendering but has known
classloader issues that were fixed — requires a Fiji restart after the fix is deployed.
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

### interact_dialog — Interact with dialog components (buttons, checkboxes, fields, etc.)
```json
// List all interactable components in all open dialogs
{"command": "interact_dialog", "action": "list_components"}
// List components in a specific dialog
{"command": "interact_dialog", "action": "list_components", "dialog": "IHF Analysis Pipeline"}

// Click a button by text
{"command": "interact_dialog", "action": "click_button", "target": "OK"}

// Set checkbox/toggle ON or OFF (by label text)
{"command": "interact_dialog", "action": "set_checkbox", "target": "3D Object Analysis", "value": true}
// Toggle checkbox (flip current state)
{"command": "interact_dialog", "action": "toggle_checkbox", "target": "Create Bin File"}

// Set text field value (by nearest label)
{"command": "interact_dialog", "action": "set_text", "target": "sigma", "value": "2.5"}
// Set text field by index (0-based)
{"command": "interact_dialog", "action": "set_text", "index": 0, "value": "hello"}

// Set dropdown/choice selection
{"command": "interact_dialog", "action": "set_dropdown", "target": "Method", "value": "Otsu"}

// Set slider value
{"command": "interact_dialog", "action": "set_slider", "index": 0, "value": 128}

// Set spinner value
{"command": "interact_dialog", "action": "set_spinner", "index": 0, "value": "42"}

// Set scrollbar value
{"command": "interact_dialog", "action": "set_scrollbar", "index": 0, "value": 50}

// Focus a tab in a JTabbedPane
{"command": "interact_dialog", "action": "focus_tab", "target": "Advanced"}

// Get details about a specific component
{"command": "interact_dialog", "action": "get_component", "type": "checkbox", "index": 2}
```
**Target matching:** `target` matches component labels by case-insensitive substring.
`index` selects the Nth component of that type (0-based). Both can be combined.
**Always `list_components` first** to see what's available before interacting.
Supports: JButton, Button, JCheckBox, Checkbox, JToggleButton, JRadioButton,
JTextField, TextField, JTextArea, TextArea, JComboBox, Choice, JSlider,
Scrollbar, JSpinner, JTabbedPane.

### probe_command — Discover plugin parameters and macro syntax
```json
{"command": "probe_command", "plugin": "Gaussian Blur..."}
→ {"ok": true, "result": {
    "plugin": "Gaussian Blur...",
    "hasDialog": true,
    "dialogType": "GenericDialog",
    "fields": [
      {"type": "numeric", "label": "Sigma (Radius):", "default": "2.00", "macro_key": "sigma"},
      {"type": "checkbox", "label": "Stack", "default": false, "macro_key": "stack"}
    ],
    "macro_syntax": "run(\"Gaussian Blur...\", \"sigma=2.00\");"
  }}
```
Opens the plugin's dialog, reads ALL fields (numeric, string, checkbox, choice
with every option, slider with range), derives the macro argument key for each,
generates example macro syntax, then cancels the dialog without executing.

**IMPORTANT — probe before using any unfamiliar plugin.** This eliminates
guessing at macro arguments. For choice/dropdown fields, the response includes
ALL available options so you know exactly what values are valid.

### run_script — Execute Groovy/Jython/JavaScript inside Fiji's JVM
```json
{"command": "run_script", "code": "println('hello')", "language": "groovy"}
→ {"ok": true, "result": {"success": true, "language": "groovy", "output": "hello", "executionTimeMs": 100}}
```
Runs arbitrary scripts inside Fiji's JVM via javax.script.ScriptEngine.
Default language is Groovy. Also supports "jython" and "javascript".
This gives full access to Java APIs, Swing components, and plugin internals.

**Key use cases:**
- Toggle Swing UI checkboxes that macros can't reach (e.g., 3Dscript raycaster)
- Direct Java API calls to plugins
- Complex control flow not possible in ImageJ macro language

**Helper:** `python ij.py script 'code'` or `python ij.py script --file script.groovy`

**Example — toggle 3Dscript checkboxes:**
```groovy
import javax.swing.JCheckBox
import java.awt.Window
import java.awt.Container
import java.awt.Component
def walk(Container c) {
    c.getComponents().each { x ->
        if (x instanceof JCheckBox) {
            if (x.getText()?.contains('Bounding') && x.isSelected()) x.doClick()
            if (x.getText()?.contains('light') && !x.isSelected()) x.doClick()
        }
        if (x instanceof Container) walk(x)
    }
}
Window.getWindows().findAll { it.class.name.contains('animation3d') }.each { walk(it) }
```

---

## Your Workflow

1. **Check state first**: `python ij.py state` — know what's open
2. **Check metadata**: `python ij.py metadata` — is the image calibrated?
3. **Probe unfamiliar plugins**: `python probe_plugin.py "Plugin Name"` — know the arguments
4. **Execute macros**: `python ij.py macro '...'` — do the work
5. **Capture and LOOK**: `python ij.py capture step_name` then Read the PNG
6. **Check histogram**: `python ij.py histogram` — verify intensity distribution
7. **Verify results**: `python ij.py results` — check measurements
8. **Check log**: `python ij.py log` — look for warnings from plugins
8. **Iterate**: if something looks wrong, fix the macro, retry

---

## Error Handling

If a macro fails, the response will have `"success": false` and an `"error"` field. Read the error, figure out what went wrong, and try a different approach. Common issues:
- "No image open" → open an image first
- "Not a binary image" → threshold first
- "Selection required" → create an ROI first
- Command not found → check the exact command name in ImageJ menus
- Wrong/unknown arguments → probe the plugin first: `python probe_plugin.py "Plugin Name"`

---

## Macro Reference

See **`references/macro-reference.md`** for the complete ImageJ macro
command reference with syntax, examples, recipes, and best practices.

---

## Quick Recipes

### 3D Render of a Cell

When asked for a 3D render, **ask the user which method they prefer** and explain
the trade-offs, or offer to try all three:

| Method | Type | Pros | Cons |
|--------|------|------|------|
| **3Dscript** | Volume raycasting | Best quality, scriptable, proper depth/opacity | Z-staircase from few slices, can't disable overlays via macro |
| **3D Viewer** | OpenGL volume | Interactive, smooth, good quality | Screenshot capture can grab wrong window |
| **3D Project** | Rotating MIP | Always works via macro, reliable | Not true 3D — no depth/opacity, Z-striping |

**Step 1: Isolate the cell (required for ALL methods)**
```bash
python pixels.py find_cells                    # find cells, pick by area+intensity
python ij.py macro '
  run("Duplicate...", "title=seg duplicate");
  run("Gaussian Blur 3D...", "x=2 y=2 z=1");
  run("3D Objects Counter", "threshold=37000 min.=100 max.=999999 objects");
'
# Find label at target (X,Y) in objects map, then create 0/1 mask:
python ij.py macro '
  selectWindow("Objects map of seg");
  run("Duplicate...", "title=mask duplicate");
  // Loop: set target label pixels to 1, all else to 0
  imageCalculator("Multiply create stack", "ORIGINAL", "mask");
  rename("isolated");
  makeRectangle(X, Y, W, H); run("Crop");     # tight crop
'
# IMPORTANT: Use Multiply, NOT AND (AND corrupts 16-bit through 8-bit mask)
```

**Step 2: Render (choose method)**
```bash
# --- 3Dscript (best quality) ---
# Menu commands:
#   run("Interactive Animation");  ← opens Interactive Raycaster GUI + 3D canvas (linked pair)
#   run("Batch Animation", "animation=[/path/to/file.txt]");  ← headless render to .avi stack
# NOTE: "Interactive Raycaster" is NOT a valid command — use "Interactive Animation"
#
# IMPORTANT: The Interactive Raycaster dialog and 3D Animation window are a LINKED PAIR.
# Closing one makes the other uncontrollable. You cannot reuse a dialog for a different image.
# Batch Animation creates its OWN separate renderer — it does NOT inherit Interactive settings.
#
# RENDERING SETTINGS IN ANIMATION TEXT (the correct way):
# All rendering settings can be controlled directly in the animation script:
#   - change all channels' lighting to on          ← enables shading/depth
#   - change all channels' object light to 1       ← light intensity
#   - change channel 1 object light to 0.8         ← per-channel light
#   - change bounding box visibility to off         ← removes wireframe edges
#   - change scalebar visibility to off             ← removes scale overlay
#   - change channel 1 front clipping to 1000      ← hide a channel
#   - change all channels' weight to 1              ← channel visibility weight
# These go in the "At frame 0:" block before rotation commands.
#
# For Swing checkbox toggling (if needed), use run_script with Groovy:
#   python ij.py script --file .tmp/toggle_3dscript.groovy
#
# Animation text keywords:
#   "horizontally" = X-axis rotation, "vertically" = Y-axis rotation
#   "around (1,0,0)" = custom axis rotation
#   "ease-in", "ease-out", "ease-in-out" = easing
#   "zoom by a factor of N" = zoom
#   "translate horizontally/vertically by N" = pan
#
# Example animation script with all settings:
# ```
# At frame 0:
# - change all channels' lighting to on
# - change all channels' object light to 1
# - change bounding box visibility to off
# From frame 0 to frame 100 rotate by 360 degrees vertically
# ```
#
# Example scripts: ~/UK Dementia Research Institute Dropbox/Brancaccio Lab/Jamie/Macros and Scripts/3D Scripts/
#
python ij.py macro 'selectWindow("isolated"); run("8-bit");
  run("Scale...", "x=10 y=10 z=1.0 interpolation=Bicubic process create title=cell_big");'
python ij.py macro 'selectWindow("cell_big");
  run("Batch Animation", "animation=[/path/to/rotate.animation.txt]");'
# NOTE: Do NOT Z-interpolate for 3Dscript — dims the signal below alpha threshold

# --- 3D Viewer (interactive) ---
python ij.py macro 'selectWindow("isolated"); run("8-bit");'
python ij.py 3d add isolated volume 20
python ij.py 3d fit
python ij.py 3d capture output_name

# --- 3D Project (fallback MIP) ---
python ij.py macro 'selectWindow("isolated");
  run("Scale...", "x=10 y=10 z=1.0 interpolation=Bicubic process create title=cell_big");
  run("3D Project...", "projection=[Brightest Point] axis=Y-Axis slice=0.28
       initial=0 total=360 rotation=10 lower=1 upper=255 opacity=0
       surface=100 interior=50");
  run("Cyan Hot");'
```

**Critical rules:**
- **Isolate before rendering.** Crop alone is NOT enough — neighbours bleed in.
- **Multiply not AND** for masking 16-bit images with binary masks.
- **Never enhance contrast.** Raw intensity is the data.
- **8-bit required** for 3D Viewer and 3Dscript.
- **Scale XY before 3Dscript** — output size = input image size.
- **Do NOT Z-interpolate for 3Dscript** — interpolated masked data falls below
  alpha threshold. Z-staircase at oblique angles is a data limitation (few slices).

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

### Learning plugin arguments (DO THIS BEFORE USING ANY UNFAMILIAR PLUGIN)

The scanner tells you WHAT plugins exist. To learn HOW to use one:

```bash
python probe_plugin.py "Analyze Particles..."
```

This opens the plugin's dialog, reads every field (numeric, string, checkbox,
dropdown with all options, slider with range), derives the macro argument keys,
generates the exact macro syntax, and caches the result. The dialog is canceled
without executing — nothing happens to the image.

**Batch-probe common plugins** at the start of a session:
```bash
python probe_plugin.py --batch "Gaussian Blur..." "Median..." \
    "Subtract Background..." "Analyze Particles..." \
    "Set Measurements..." "3D Objects Counter"
```

**Search cached probes** when you need a specific parameter:
```bash
python probe_plugin.py --search threshold
```

**NOTE:** Probing requires the plugin's dialog to appear. Some plugins need an
image to be open first — open a test image before probing. The probe works for
GenericDialog-based plugins (the vast majority). Custom Swing dialogs get
best-effort text extraction.

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
Deploy JARs to the Dropbox Fiji.app plugins directory.

### Build & deploy
```bash
cd "C:\Users\Owner\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Experiments\ImageJAI"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot"
/c/Users/Owner/apache-maven-3.9.6/bin/mvn clean package -q
# IMPORTANT: Remove old version before deploying — multiple JARs cause classloader conflicts
rm -f "C:\Users\Owner\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Fiji.app\plugins\imagej-ai-"*.jar
cp target/imagej-ai-*.jar "C:\Users\Owner\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Fiji.app\plugins\"
```
User must restart Fiji after deploying. Fiji caches classes — hot-reload does NOT work.

### Versioning (MAJOR.MINOR.PATCH)
- **MAJOR** (first digit): new features
- **MINOR** (second digit): big refactors or improvements
- **PATCH** (third digit): bug fixes

Version is set in `pom.xml` `<version>` tag. Current version: **0.2.0**.
Bump the appropriate digit when making changes. No `-SNAPSHOT` suffix.

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

- **`adviser.py`** — research-only analysis consultant. No TCP needed. Searches
  recipes, installed commands, update sites, and reference docs to answer questions.
  ```bash
  python adviser.py "colocalization"                   # general query
  python adviser.py --plugins "deconvolution"          # search plugins
  python adviser.py --install "ilastik"                # installation help
  python adviser.py --recipe "cell counting"           # find recipes
  python adviser.py --macro "count cells in z-stack"   # suggest macro code
  python adviser.py --compare "StarDist vs Cellpose"   # compare approaches
  ```

- **`recipe_search.py`** — find and recommend analysis recipes from the recipe book.
  ```bash
  python recipe_search.py "count cells"           # search by keyword
  python recipe_search.py --domain neuroscience   # filter by domain
  python recipe_search.py --list                  # list all recipes
  python recipe_search.py --show cell_counting    # show full recipe
  ```

- **`auditor.py`** — validate measurement results for sanity (plausible areas,
  outliers, systematic biases, saturation). Run after any quantitative analysis.
  ```bash
  python auditor.py                               # audit current results table
  python auditor.py --csv results.csv             # audit a CSV file
  ```

- **`practice.py`** — autonomous self-improvement. Opens sample images, runs
  analysis workflows, validates results, tries variations, logs what works best.
  ```bash
  python practice.py                              # run all practice tasks
  python practice.py --task cell_counting         # run specific task
  python practice.py --report                     # show practice history
  ```

- **`autopsy.py`** — failure logging system. Records every macro failure with
  context (image state, error, histogram). Check before retrying failed commands.
  ```python
  from autopsy import Autopsy
  autopsy = Autopsy()
  warnings = autopsy.check_known_issues("Analyze Particles", image_state)
  ```

---

## Recipe Book (`recipes/` directory)

Structured YAML files describing analysis workflows. Each recipe has:
preconditions, parameters with defaults/ranges, step-by-step macro code,
decision points, validation checks, and known issues.

**Always check the recipe book before starting an analysis:**
```bash
python recipe_search.py "what the user asked for"
```

If a matching recipe exists, follow it. If not, build the workflow and
**create a new recipe** so the next agent has it. Recipes are generalisable —
lab-specific adjustments go in `learnings.md` instead.

After completing an analysis, **audit the results**:
```bash
python auditor.py
```

---

## Reference Documents

All reference files live in `references/`. Read them when you need detailed
information about a specific analysis type, plugin, or method:

- **`references/macro-reference.md`** — ImageJ macro command reference
- **`references/file-formats-saving-reference.md`** — File formats & saving (all native + Bio-Formats
  163 formats, saveAs() complete ref, File.* functions, TIFF deep dive, Bio-Formats Exporter,
  batch save patterns, data integrity, Java FileSaver API, 65+ export commands, agent integration)
- **`references/3d-visualisation-reference.md`** — All 3D rendering, animation, cell isolation,
  video export (3Dscript, 3D Viewer, 3D Project, ClearVolume, BDV, napari, FFmpeg)
- **`references/3dscript-reference.md`** — 3Dscript animation language quick reference
  (keywords, rendering properties, easing, camera controls, common patterns)
- **`references/colocalization-reference.md`** — Colocalization analysis expert reference
  (PCC, Manders, Costes, 7 methods, 7 plugins, decision tree, reporting standards, pitfalls)
- **`references/circadian-analysis-reference.md`** — Circadian rhythm analysis of organotypic slices
  (CHRONOS pipeline, FFT/wavelet/cosinor/JTK, detrending, phase maps, Kuramoto, CircaCompare)
- **`references/circadian-imaging-reference.md`** — Circadian imaging protocols and analysis
- **`references/if-postprocessing-reference.md`** — Immunofluorescence post-processing
  (filtering, thresholding, deconvolution, pipelines, decision trees, journal guidelines)
- **`references/fluorescence-microscopy-reference.md`** — Fluorescence microscopy physics,
  modalities, fluorophores, sample prep, acquisition, artifacts, quantitative methods,
  ImageJ operations (Jablonski, Stokes shift, Nyquist, CTCF, FRAP, FRET, filter sets,
  clearing, deconvolution, thresholding, segmentation, batch processing)
- **`references/3d-spatial-reference.md`** — Complete 3D spatial analysis reference
  (~300 commands: 3D ImageJ Suite, MorphoLibJ, CLIJ2, AnalyzeSkeleton, DiAna,
  3D Manager macro API, spatial statistics, 3D colocalization, morphometry, distances)
- **`references/domain-reference.md`** — microscopy modalities, quantitative methods,
  quality control standards, decision trees for choosing approaches
- **`references/analysis-landscape.md`** — 75+ analysis tasks across 15 research domains
  with workflows, plugins, and automation opportunities
- **`references/self-improving-agent-reference.md`** — plugin API patterns, automation
  approaches, comparison matrices, parameter optimization strategies
- **`references/fluorescence-theory-reference.md`** — physics of fluorescence, fluorophore
  encyclopedia (nuclear stains, Alexa Fluor, fluorescent proteins, calcium indicators,
  membrane/viability dyes), optical resolution formulas, SNR theory, spectral overlap
  and multi-colour panel selection, CTCF/FRAP/FRET/ratiometric methods
- **`references/histology-neurodegeneration-reference.md`** — neurodegenerative disease
  histology: staining methods (H&E, IHC antibodies for Abeta/tau/alphaSyn/TDP-43,
  silver stains, special stains, multiplex IF), pathological features by disease
  (AD, PD/DLB, FTLD, HD, prion, CTE, LATE), staging systems (NIA-AA ABC, Braak,
  CERAD, Thal, McKeith, LATE-NC, Vonsattel, CTE), ImageJ analysis pipelines
  (DAB colour deconvolution, area fraction, plaque counting, cell counting),
  brain anatomy for ROI selection, best practices and pitfalls
- **`references/imagej-gui-reference.md`** — Complete ImageJ/Fiji GUI and usage reference
  (every menu item, all dialogs with every field, toolbar tools, keyboard shortcuts,
  plugin installation, macro recording, image types, ROIs, overlays, results tables,
  Dialog/ROI/Overlay/Table/File macro API, batch automation, scripting, agent integration)
- **`references/gui-interaction-reference.md`** — ImageJ macro API deep reference (companion)
  (Plot.* charting, Fit.* curve fitting, Color.* API, Ext.* extensions, setOption,
  pixel access, math functions, drawing/graphics, all function signatures with types)
- **`references/publication-figures-reference.md`** — Publication-quality figure creation
  (journal requirements, DPI, scale bars, montages, LUTs, panel labels, multi-channel
  figures, insets, export, accessibility, image integrity checklist)
- **`references/python-image-analysis-reference.md`** — Python image analysis libraries
  (scikit-image 0.26, scipy.ndimage, numpy, tifffile, matplotlib, Pillow, aicsimageio,
  colocalization, regionprops, ImageJ integration, roifile)
- **`references/ai-image-analysis-reference.md`** — AI/deep learning for microscopy
  (StarDist, Cellpose, WEKA, Labkit, CSBDeep/CARE, DeepImageJ, foundation models,
  decision trees, macro syntax, training workflows, Python ecosystem, batch processing)
- **`references/weka-segmentation-reference.md`** — Trainable Weka Segmentation (TWS)
  deep reference (20 features explained, macro call() API, Groovy scripting API,
  WekaSegmentation class, training workflows, batch processing, tile processing,
  probability maps, classifier tuning, feature selection guide, memory management)
- **`references/brain-atlas-registration-reference.md`** — Brain atlas registration
  and neuroanatomy (ABBA, Allen CCFv3, elastix, BigWarp, QuPath integration,
  bregma coordinates, SCN/hippocampal identification, ontology, batch workflows)
- **`references/trackmate-reference.md`** — TrackMate particle/cell tracking
  (all 15+ detectors incl. LoG/DoG/Hessian/StarDist/Cellpose/ilastik/Weka/Spotiflow/YOLO,
  all trackers incl. LAP/Kalman/Overlap, complete feature lists, Jython/Groovy scripting API,
  batch processing, TrackMate-Helper parameter optimization, export options, TrackScheme)
- **`references/statistics-reference.md`** — Statistics for biological microscopy
  (probability distributions, hypothesis testing framework, parametric/non-parametric tests,
  ANOVA, post-hoc tests, correlation, mixed-effects models, multiple comparisons, effect sizes,
  power analysis, circular statistics, transformations, R/Python/ImageJ/Prism APIs, decision trees)
- **`references/hypothesis-testing-microscopy-reference.md`** — Question-driven hypothesis
  testing: 36 research questions mapped to H0/H1/measurements/tests, logic of statistical
  proof (courtroom/fire alarm analogies, effect sizes, power, fallacies, modern perspectives),
  measurement extraction guide (intensity/morphology/counts/coloc/spatial/temporal),
  experimental design pitfalls (pseudoreplication, selection bias, batch effects, blinding)
- **`references/statistical-analysis-workflow-reference.md`** — End-to-end statistical
  analysis pipelines: ImageJ measurement command reference, 8 copy-paste Python scripts
  (two-group, multi-group, paired, correlation, mixed-effects, proportion, dose-response,
  multiple corrections), integration workflow (ImageJ → CSV → Python → figure → report)
- **`references/large-dataset-optimization-reference.md`** — Large dataset optimization
  (memory management, JVM/GC tuning, setBatchMode, virtual stacks, CLIJ2 GPU acceleration,
  file formats (OME-Zarr/N5/HDF5), Bio-Formats efficient import, Dask/Python out-of-core
  processing, headless mode, CellProfiler/QuPath/OMERO, cluster/SLURM, data management,
  whole-slide tiled processing, agent TCP batch patterns, decision trees, troubleshooting)
- **`references/pipeline-construction-reference.md`** — Pipeline construction for image
  analysis automation (batch processing, folder templates, setBatchMode, memory management,
  input validation, multi-channel/z-stack handling, Bio-Formats iteration, matched pairs,
  CLIJ2 GPU pipelines, Script Parameters, decision points, parameter sweeps, ImageJAI
  PipelineBuilder API, TCP run_pipeline protocol, recipe YAML system, agent workflow)
- **`references/registration-stitching-reference.md`** — Image registration & stitching:
  Grid/Collection stitching (all grid types, orders, fusion methods), Pairwise stitching,
  StackReg/TurboReg (macro syntax, -align/-transform, landmark extraction), SIFT alignment,
  bUnwarpJ (elastic B-spline, all call() methods), BigWarp (TPS, scripting, landmarks),
  Correct 3D Drift, Register Virtual Stack Slices, Descriptor-based Registration,
  scikit-image (phase_cross_correlation, optical_flow, transforms, ORB, RANSAC),
  SimpleITK (full registration framework, all metrics/optimizers/transforms),
  OpenCV (SIFT/ORB/AKAZE, BFMatcher/FLANN, findHomography/estimateAffine2D, Stitcher),
  pystackreg, MIST (NIST large-grid), CLIJ2 GPU registration, decision tree,
  agent workflow patterns, batch processing, multi-channel strategies, tips/gotchas,
  installed plugin inventory, cross-tool workflows
- **`references/light-sheet-microscopy-reference.md`** — Light-sheet fluorescence microscopy:
  all microscope types (SPIM, diSPIM, lattice, mesoSPIM, commercial systems), tissue clearing
  (20+ methods: iDISCO, CUBIC, CLARITY, 3DISCO, ECI, etc.), big data handling (N5/Zarr/HDF5,
  chunked formats, dask, out-of-core), BigStitcher/BDV, whole-brain analysis pipelines
  (brainglobe/cellfinder/ClearMap), deconvolution, acquisition parameters, file formats,
  Python ecosystem, Fiji integration
- **`references/troubleshooting-quality-reference.md`** — Image troubleshooting & quality
  assessment: QC protocol (PASS/WARN/FAIL), focus metrics (Laplacian/Brenner/Tenengrad),
  saturation/dynamic range, SNR estimation, illumination uniformity, 25+ artifact types
  with detection macros and fixes, journal integrity standards, batch QC, agent QC macros
- **`references/calcium-imaging-reference.md`** — Calcium imaging analysis (GCaMP/Fura-2/Fluo-4,
  dF/F calculation, ratiometric imaging, motion correction, event detection, frequency analysis,
  population synchrony, all ImageJ plugins, Python analysis scripts)
- **`references/neurite-tracing-reference.md`** — Neurite tracing & neuronal morphometry
  (SNT Groovy API, Sholl analysis, Strahler analysis, AnalyzeSkeleton, SWC format,
  morphometric measurements, vessel/filament analysis, batch processing)
- **`references/colour-deconvolution-histology-reference.md`** — Colour deconvolution &
  histological scoring (CD1/CD2, all stain vectors, custom vectors, DAB quantification,
  H-score, Allred, Ki67, HER2, PD-L1, IHC Profiler, stain normalisation, WSI handling)
- **`references/wound-healing-migration-reference.md`** — Wound healing & cell migration
  (scratch assay, barrier assay, invasion assays, wound edge detection, closure rate,
  MSD analysis, chemotaxis, TrackMate integration, all plugins)
- **`references/live-cell-timelapse-reference.md`** — Live cell & time-lapse analysis
  (drift correction, bleach correction, FRAP, kymographs, cell division/death detection,
  confluency, temporal intensity, ratiometric/FRET, phototoxicity, video export)
- **`references/batch-processing-reference.md`** — Batch processing & Bio-Formats
  (folder iteration, setBatchMode, Bio-Formats Importer complete parameter reference,
  macro extensions API, file format patterns, memory management, 10 batch macro templates)
- **`references/deconvolution-reference.md`** — Image deconvolution (DeconvolutionLab2
  all 14 algorithms, PSF generation, Iterative Deconvolve 3D, CLIJ2 GPU deconvolution,
  iteration/regularisation guidelines, quality assessment, batch workflows)
- **`references/proliferation-apoptosis-reference.md`** — Cell proliferation & apoptosis
  (Ki67/EdU/BrdU/PCNA/pHH3/FUCCI, TUNEL, caspase-3, Annexin V, live/dead, colony assays,
  cell cycle from DNA content, multi-marker classification, high-content screening)
- **`references/fiji-scripting-reference.md`** — Fiji scripting (Groovy & Jython) via
  run_script TCP command: IJ1/IJ2 API reference (ImagePlus, ImageProcessor, ImageStack,
  WindowManager, RoiManager, ResultsTable, Calibration, Overlay, FileSaver), SciJava
  script parameters (#@ injection), Groovy patterns (closures, collections, GStrings,
  file I/O, regex, classes), Jython patterns (Java interop, jarray, gotchas), GUI
  manipulation (Swing component walking, checkbox toggling, dialog creation), plugin
  scripting (TrackMate, SNT, TWS, CLIJ2, MorphoLibJ, Bio-Formats), agent integration
  (ij.py script, data passing, error handling), complete examples (batch processing,
  CTCF, parallel processing, overlay annotations, colocalization, JSON export)
- **`references/organoid-spheroid-reference.md`** — Organoid & spheroid image analysis
  (brightfield segmentation methods (global/adaptive/multi-window/edge/Weka/variance),
  fluorescence viability (calcein-AM/PI), nuclear counting, morphometric measurements
  (25+ shape descriptors), lumen detection & classification, budding/branching analysis
  (convex hull, skeleton, protrusion detection), 3D volume/sphericity (3D Objects Counter,
  MorphoLibJ), growth curve fitting (exponential/logistic/Gompertz), multi-well plate
  batch processing, drug response (IC50/AUC/dose-response), invasion assays, 10+ dedicated
  tools (OrganoSeg, SpheroidJ, INSIDIA, MOrgAna, OrganoID, Cellos, AnaSP, Lusca),
  statistical considerations (nested data, mixed-effects models), reporting standards)
- **`references/method-validation-reference.md`** — Method validation & agreement statistics
  (Bland-Altman, ICC, CCC, Dice/IoU, Hausdorff, Panoptic Quality, kappa, AP, F1,
  inter-observer variability, power analysis, ground truth preparation, reporting templates)
- **`references/super-resolution-reference.md`** — Super-resolution microscopy analysis
  (ThunderSTORM complete API, NanoJ-SRRF, fairSIM/SIMcheck, FRC resolution estimation,
  DBSCAN/Ripley's K cluster analysis, nanoscale colocalization, expansion microscopy)
- **`references/fiber-orientation-reference.md`** — Fiber & orientation analysis
  (OrientationJ complete API, Directionality, FibrilTool, DiameterJ, AnalyzeSkeleton,
  coherency, anisotropy, nematic order, SHG collagen, circular statistics)
- **`references/color-science-reference.md`** — Color science for microscopy
  (display vs data, all Fiji LUTs, colorblind-safe palettes, composites, RGB conversion,
  pseudocolor, calibration bars, journal color requirements, common mistakes)
- **`references/electron-microscopy-reference.md`** — Electron microscopy analysis
  (TEM/SEM, organelle segmentation, membrane distances, immunogold, stereology,
  serial section alignment, TrakEM2, CLEM with BigWarp, cryo-EM)
- **`references/spatial-statistics-reference.md`** — Spatial statistics & point patterns
  (NND, Clark-Evans, Ripley's K/L/H, pair correlation, cross-type bivariate, Voronoi,
  DBSCAN, distance-to-features, Moran's I, Getis-Ord hotspots, 3D spatial, Monte Carlo)
- **`references/scn-reference.md`** — SCN (suprachiasmatic nucleus) comprehensive biology
  (anatomy, core/shell subdivisions, cell types & markers with antibody catalog, TTFL
  molecular clock, network properties & coupling hierarchy, oscillator theory & mathematical
  models (Kuramoto, Goodwin, PRC), quantification methods (Lomb-Scargle, cosinor, JTK_CYCLE,
  CircaCompare), electrophysiology, light input/output pathways, development, disease)
- **`references/scn-analysis-reference.md`** — SCN image analysis workflows
  (SCN identification & ROI definition, bregma coordinates, dorsal/ventral subdivision,
  PER2::LUC bioluminescence rhythm analysis, pixel-by-pixel period/phase maps, detrending,
  Kuramoto synchrony, fluorescence cell counting & CTCF, colocalization of clock proteins,
  GCaMP calcium imaging, Incucyte time-lapse, phase map generation (Hilbert transform),
  microglia morphology (Sholl/skeleton/FracLac/MotiQ), circadian microglial variation,
  amyloid plaque quantification (DAB/ThioS), microglia-plaque proximity analysis,
  batch workflows, statistical considerations for circadian data)

---

## Lab Training (First-Time Setup)

Any lab can train the agent on their specific images before using it:

```bash
python train_agent.py /path/to/lab/images              # train on a directory
python train_agent.py /path/to/lab/images --domain neuro # specify domain
python train_agent.py --profile                         # show current lab profile
```

This runs 5 phases: image characterization, threshold discovery, segmentation
testing, parameter tuning, and writes findings to `lab_profile.json` + `learnings.md`.
The agent then knows the lab's typical image types, best thresholds, optimal
parameters, and which plugins work on their data.

## Self-Improvement

The practice runner autonomously tests 15 analysis tasks on sample images:

```bash
python practice.py                    # run all 15 tasks
python practice.py --task stardist    # run specific task
python practice.py --report           # show results history
```

Tasks cover: cell counting, threshold comparison, measurements, background
subtraction, z-stacks, channel splitting, particle filtering, StarDist, 3D
Objects Counter, Coloc 2, CLIJ2 GPU, MorphoLibJ, AnalyzeSkeleton, Bio-Formats,
and all measurement types. Results saved to `.tmp/practice_results.json`.

---

## Learning

As you work, update `learnings.md` in this directory with:
- Macros that work well for common tasks
- Error patterns and their fixes
- Workflows you've discovered
- Tips about the user's specific images/data

If you discover a new working workflow, **create a recipe** in `recipes/` so
the next agent can use it directly. Learnings are for lab-specific notes;
recipes are for generalisable workflows.

---

## Rules
- Always check state before acting
- Never assume an image is open — verify
- **ALWAYS probe unfamiliar plugins before using them** — `python probe_plugin.py "Plugin Name"`
  discovers all parameters, their types, defaults, dropdown options, and the exact macro
  syntax. Never guess at macro arguments when you can probe.
- **ALWAYS capture and visually inspect images after processing steps**
- **NEVER use Enhance Contrast on output data.** `normalize` permanently modifies
  pixel values — the user can never undo it. Raw intensity IS the data in
  scientific microscopy. The user can always adjust display range themselves
  later, but they can't recover destroyed pixel values. If you need better
  visibility during YOUR inspection, use `setMinAndMax()` which only changes
  display, not data.
- **When multiple approaches exist, ASK the user.** Present the options with
  pros/cons and let them choose, or offer to try all so they can compare.
  Don't just pick one silently.
- **Check the recipe book first** — `python recipe_search.py "task"` before
  building a workflow from scratch. If a recipe exists, use it.
- **Audit results after analysis** — `python auditor.py` catches measurement
  errors before they propagate to papers.
- **Create recipes for new workflows** — if you solve a task that has no recipe,
  create one in `recipes/` so the next agent has it. Keep recipes generalisable.
- **ALWAYS close error dialogs immediately** — after any macro execution that
  produces a dialog (error or otherwise), run `python ij.py close_dialogs` right
  away. Never leave dialogs sitting open for the user to deal with.
- If a macro fails, try to fix it (up to 3 attempts)
- Show the user what you're doing and why
- For multi-step tasks, explain the plan before executing
- No Co-Authored-By lines on git commits
- If something doesn't work, fix the TCP server code — don't work around bugs silently
