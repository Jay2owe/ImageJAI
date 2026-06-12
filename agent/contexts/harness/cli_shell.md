# Harness — CLI shell

You operate Fiji by typing shell commands in a terminal. Use the
`ij.py` helper for ALL ImageJ operations:

```bash
python ij.py ping                                    # test connection
python ij.py macro 'run("Blobs (25K)");'             # run macro code
python ij.py state                                    # full ImageJ state
python ij.py info                                     # active image details
python ij.py results                                  # measurements as CSV
python ij.py capture                                  # screenshot -> .tmp/capture.png
python ij.py capture my_name                          # screenshot -> .tmp/my_name.png
python ij.py explore Otsu Triangle Li                 # compare thresholds
python ij.py log                                      # ImageJ Log window contents
python ij.py histogram                                # intensity stats + bin counts
python ij.py windows                                  # all open window titles
python ij.py metadata                                 # Bio-Formats info + calibration
python ij.py rois                                     # ROI Manager state (names, types, bounds)
python ij.py display                                  # active C/Z/T, LUT, display range
python ij.py console                                  # recent Fiji stdout/stderr (Groovy traces)
python ij.py console --tail 5000                      # longer console window
python ij.py dialogs                                  # check for open dialogs/errors
python ij.py close_dialogs                            # dismiss open dialogs
python ij.py 3d status                                # 3D Viewer: is it open?
python ij.py 3d add IMAGE_TITLE volume 50             # 3D Viewer: add volume
python ij.py 3d list                                  # 3D Viewer: list content
python ij.py 3d snapshot 512 512                      # 3D Viewer: capture
python ij.py 3d close                                 # 3D Viewer: close
python ij.py probe "Gaussian Blur..."                 # discover plugin parameters
python ij.py script 'println("hello")'                # run Groovy inside Fiji's JVM
python ij.py script --file path/to/script.groovy      # run Groovy file
python ij.py script --lang jython 'print("hello")'    # run Jython script
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

### Plugin argument discovery (with caching)

```bash
python probe_plugin.py "Gaussian Blur..."              # probe + cache + pretty print
python probe_plugin.py --batch "Median..." "Subtract Background..." "Analyze Particles..."
python probe_plugin.py --search threshold              # search cached probes
python probe_plugin.py --lookup "Gaussian Blur..."     # check cache only
python probe_plugin.py --list                          # list all cached
```

Probing opens the plugin's dialog, reads ALL fields (numeric,
string, checkbox, choice with every option, slider with range),
derives the macro argument key for each, generates example macro
syntax, then cancels without executing. Some plugins need an
image open first. Works for GenericDialog-based plugins (the vast
majority); custom Swing dialogs get best-effort extraction.

### Pixel analysis (Python-side, no ImageJ needed)

```bash
python pixels.py                                     # stats for current slice
python pixels.py find_cells                           # auto-detect bright objects
python pixels.py region 100 100 50 50                 # stats for a region
python pixels.py profile 0 512 1024 512               # line profile
python pixels.py stack_stats                          # per-slice stats for z-stack
```

If `ij.py` is not available, use raw Python with sockets (see
ij.py source for pattern).

### Looking at images

You can see images by reading the captured PNG file:

1. Run `python ij.py capture` — saves PNG to `agent/.tmp/capture.png`.
2. Use your **file-read tool** on the PNG file — you will see the image visually.
3. Use what you see to make decisions about the next step.

**Do this after EVERY macro that changes the image.** This is
your eyes.

- All captures go in `agent/.tmp/` (gitignored, safe to overwrite).
- Use descriptive names: `capture before_threshold`,
  `capture after_watershed`.

### JSON protocol (reference)

All commands use JSON via TCP at `localhost:7746`. The `ij.py`
helper wraps these. Response format: `{"ok": true, "result": ...}`.
Common commands: `ping`, `execute_macro`, `get_state`,
`get_image_info`, `get_results_table`, `capture_image`,
`get_state_context`, `run_pipeline`, `explore_thresholds`,
`batch`, `get_log`, `get_histogram`, `get_open_windows`,
`get_metadata`, `get_dialogs`, `get_pixels`, `3d_viewer`,
`close_dialogs`, `interact_dialog`, `probe_command`, `run_script`.

`execute_macro` runs ImageJ macro code — the primary tool.
`run_script` runs Groovy / Jython / JavaScript inside Fiji's JVM
(default Groovy) when macros can't reach Swing internals.
`interact_dialog` matches labels by case-insensitive substring;
`index` selects Nth component of that type. Always
`list_components` first.

### Plugin discovery

Run at the start of every session:

```bash
python scan_plugins.py
```

Writes `.tmp/commands.md` (annotated, with lookup map at the
top), `.tmp/commands.raw.txt` (raw `Name=class.path` dump),
`.tmp/plugins_summary.txt`, and `.tmp/update_sites.json`.

```bash
grep -i "keyword" .tmp/commands.md
python ij.py macro 'run("StarDist 2D");'
```

### Agent-side Python tools

All in this directory:

- `session_log.py` — auto-log commands, export replayable `.ijm`.
- `results_parser.py` — parse Results CSV, summary stats,
  outliers.
- `image_diff.py` — compare before/after PNGs.
- `macro_lint.py` — validate macro code before sending.
- `adviser.py` — research consultant
  (`python adviser.py "colocalization"` / `--plugins` /
  `--recipe` / `--macro` / `--compare`).
- `recipe_search.py` — find analysis recipes
  (`python recipe_search.py "count cells"` / `--list` / `--show`).
- `auditor.py` — validate measurement sanity.
- `practice.py` — autonomous self-improvement (15 tasks).
- `autopsy.py` — failure logging, check known issues.

### Lab training

```bash
python train_agent.py /path/to/lab/images              # train on a directory
python train_agent.py /path/to/lab/images --domain neuro
python train_agent.py --profile                         # show current lab profile
```

Writes `lab_profile.json` + `learnings.md`. Update `learnings.md`
with macros that work well, error patterns and fixes, workflows
discovered, tips about the user's specific data. Generalisable
workflows go in `recipes/`.
