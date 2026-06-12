# ImageJ Agent — Plan

## Motivation

ImageJAI already has a play button with a dropdown that lets the
user pick an AI agent (Claude Code, Aider, Gemini CLI, and so on)
and launches it in a new terminal. All the agents in the dropdown
today are paid or require an API key.

The goal of this project is to add one more entry to that
dropdown — **Gemma 4 31B**, running locally through Ollama — so
the user has a free option that works without an API key. The
model is `gemma4:31b-cloud`.

The new agent must be **standalone**: no shared code or runtime
dependency on AgentConsole or on the existing ollama wrapper at
`agent/ollama_agent/`. Files can be copied across as a starting
point.

Gemma will not be as capable as Claude out of the box. Most of
the value is in the **ImageJ toolbox and domain knowledge** we
wire up around it, built in phases so each improvement can be
tested manually before the next is added.

---

## How the user starts it

The ImageJAI GUI already has a play button with a dropdown
listing available CLI agents. We add one line to the known-agents
list in `AgentLauncher.java`:

```
{"Gemma 4 31B", "gemma4_31b_agent", "Local Ollama agent (free, no API key)", ""}
```

User flow:

1. Open Fiji with the ImageJAI plugin.
2. Open an image.
3. Pick **Gemma 4 31B** from the agent dropdown.
4. Press play.
5. A terminal opens and the agent is ready to chat.

The agent is a pip-installable Python package with a console
entry point called `gemma4_31b_agent`. One `pip install -e
agent/gemma4_31b/` registers the command so the play button
finds it.

---

## How it talks to Fiji

Fiji has a TCP server on port **7746** that already accepts about
forty commands (run a macro, read the results table, capture a
screenshot, probe a plugin, interact with a dialog, and so on).
We keep this.

- Short jobs use `execute_macro` and wait for the result.
- Long jobs use `execute_macro_async`, which returns a job ID.
  The agent polls with `job_status` until it finishes.
- A background thread subscribes to Fiji events (macro started,
  macro finished, results changed) and drops them in a queue so
  the chat loop is never blocked.

Tool settings: `stream=False`, `temperature=0.2`, context
window hard-coded to 131,072 tokens.

---

## How tools are organised

Each tool is a Python function with a docstring. Ollama reads
the function's signature and docstring to build the tool schema,
so the docstring **is** the contract.

- One file per area: `tools_fiji.py`, `tools_python.py`,
  `tools_plugins.py`, `tools_recipes.py`, `tools_dialogs.py`,
  `tools_jobs.py`, `tools_events.py`.
- A small `@tool` decorator in `registry.py` appends each
  function to a single list.
- `loop.py` imports every `tools_*.py` once and hands the list
  to `ollama.chat()`.

Keep parameter types to simple ones (`str`, `int`, `float`,
`bool`, `list[str]`, `dict`). No `Optional` or `Union` — they
confuse the schema builder.

---

## Safety

When the user opens an image, the agent creates a subfolder
called `AI_Exports/` next to it. Everything the agent writes goes
there — processed images, measurement tables, screenshots, the
audit log.

The one rule: the agent can only write into an `AI_Exports/`
folder sitting next to an image the user has opened. Any macro
that tries to save somewhere else is refused before it runs.

Groovy and Jython scripts are allowed. Every macro and every
script the agent runs is written to the audit log inside
`AI_Exports/` with a timestamp.

---

## Friction log (development only)

A second log that records every time a macro fails, every time
the agent loops on the same error, and every time the user
overrides a suggestion. Saved in the working folder for us to
review and improve the agent with.

**On by default for now** — we want the data while we are
building. A command-line flag `--no-friction-log` turns it off
if needed. Closer to a public release the default flips to off
(or the relevant imports get stubbed).

---

## Phases

Each phase is a self-contained improvement that can be tested by
hand before the next one starts.

### Phase 1 — Make it work end to end *(completed)*

**Motivation.** The agent can't do anything until it launches, talks to Fiji, and knows where to put outputs. Everything downstream leans on these four pieces working.

Phase 1 is split into four sub-phases. Each has its own success check so we can stop, test by hand, and fix problems before moving on.

#### 1a — Skeleton (agent launches and chats)

Goal: the play button opens a terminal, the agent says hello, the user can type a message, the agent replies, Ctrl-C exits cleanly. No ImageJ tools yet.

Files to create in `agent/gemma4_31b/`:

- **`pyproject.toml`** — a Python packaging file (tells `pip` how to install the package and what command to put on the user's PATH). Declares the package name `gemma4_31b_agent`, a dependency on `ollama`, and a console entry point `gemma4_31b_agent = gemma4_31b.__main__:main` so the play button can launch it by name.
- **`__init__.py`** — empty; marks the folder as a Python package.
- **`__main__.py`** — reads command-line flags (`--no-friction-log`, `--model gemma4:31b-cloud`), then calls `loop.run()`. Nothing else.
- **`loop.py`** — the chat loop. Copy `agent/ollama_agent/ollama_chat.py` as the starting point, strip out anything that imports from AgentConsole, and keep: the `ollama.chat()` call with `stream=False`, `temperature=0.2`, context window 131072; a `while True` that reads a line from stdin, appends to message history, sends, prints the reply; a `KeyboardInterrupt` handler that prints `"bye"` and returns.
- **`registry.py`** — defines a `@tool` decorator that appends the wrapped function to a module-level list `REGISTRY`, and an `all_tools()` helper that returns it. Empty list for now; 1b fills it.

One-line Java edit in `src/main/java/imagejai/engine/AgentLauncher.java` — add to `KNOWN_AGENTS`:

```java
{"Gemma 4 31B", "gemma4_31b_agent", "Local Ollama agent (free, no API key)", ""},
```

**Success check:** `pip install -e agent/gemma4_31b/`, rebuild the plugin, click play, pick Gemma, a terminal opens, type "hello", get a reply, Ctrl-C exits.

#### 1b — Core Fiji tools

Goal: the agent can drive Fiji through the existing TCP server on port 7746. Each tool is a thin Python function that sends one JSON command and returns the result. The docstring becomes the schema Ollama sees, so every docstring must describe the tool in one sentence.

In `tools_fiji.py`:
- `run_macro(code: str) -> dict` — run a short ImageJ macro and wait for the result.
- `run_script(code: str, language: str) -> dict` — run a Groovy or Jython script inside Fiji's Java runtime.
- `get_state() -> dict` — return what Fiji has open right now: images, windows, results table, memory.
- `get_image_info() -> dict` — details of the currently active image: size, bit depth, calibration.
- `get_log() -> str` — read the ImageJ Log window.
- `get_results() -> str` — return the Results table as comma-separated text.
- `capture_image(max_size: int) -> str` — save a screenshot of the active image to disk and return its path.
- `get_open_windows() -> dict` — list every open window by type.
- `get_metadata() -> dict` — Bio-Formats metadata and pixel-size calibration.
- `get_histogram() -> dict` — intensity distribution for the active image.

In `tools_jobs.py`:
- `run_macro_async(code: str) -> str` — start a long-running macro, return a job ID.
- `job_status(job_id: str) -> dict` — poll a job: running, completed, failed.
- `cancel_job(job_id: str) -> dict` — stop a job that is still running.

In `tools_dialogs.py`:
- `close_dialogs(pattern: str) -> dict` — dismiss open dialogs, optionally filtered by title.
- `list_dialog_components() -> dict` — list every button, checkbox, text field and dropdown in the open dialog.
- `click_dialog_button(label: str) -> dict` — click a dialog button by its label.
- `set_dialog_text(label: str, value: str) -> dict` — type into a text field identified by its label.
- `set_dialog_checkbox(label: str, value: bool) -> dict` — tick or untick a checkbox.
- `set_dialog_dropdown(label: str, value: str) -> dict` — pick an option from a dropdown.

In `tools_plugins.py`:
- `probe_plugin(name: str) -> dict` — open a plugin's dialog, read its parameters, cancel without running.

All of these wrap commands that already exist in `TCPCommandServer.dispatchCore` (lines 653–735). No Java changes.

**Success check:** the user types "run the Blobs sample, threshold with Otsu, measure". The agent calls `run_macro`, then `get_results`, and reports the object count.

#### 1c — Python-side image tools (agent sees pixels directly)

Goal: the agent can analyse pixels in Python without writing a macro. Faster for quick questions, works when Fiji is busy. `agent/pixels.py` is the reference — the tools in `tools_python.py` are thin wrappers around its functions, exposed as Ollama tools.

In `tools_python.py`:
- `get_pixels_array(slice: int, region: list) -> list` — pull raw pixel values from the active image as a 2D list. `slice` picks a z-slice; `region` is `[x, y, width, height]` or empty for the whole image. Used when the agent wants to inspect specific numbers.
- `region_stats(x: int, y: int, width: int, height: int) -> dict` — mean, median, minimum, maximum and standard deviation of one rectangle. Used to compare background to signal.
- `line_profile(x1: int, y1: int, x2: int, y2: int) -> list` — intensity values along a straight line between two points. Used to check edge sharpness or measure a structure's width.
- `quick_object_count(threshold_method: str) -> int` — return roughly how many bright blobs are in the image using a simple threshold-then-flood-fill (no macro). Used as a sanity check before committing to a full Analyze Particles run.
- `histogram_summary() -> dict` — 1st, 50th, 99th intensity percentiles plus the fraction of pixels at the saturation ceiling. Used to flag over- or under-exposed images.
- `count_bright_regions(min_intensity: int, min_area_pixels: int) -> int` — count blobs above an absolute intensity cutoff with a minimum area. Used when the user gives concrete numbers ("anything brighter than 2000 covering at least 50 pixels").

**Success check:** with a blob image open, the agent can answer "how many cells roughly?" using `quick_object_count` alone, with no macro call.

#### 1d — Active-image detection and `AI_Exports` routing

The hidden subproblem: the agent writes files, so it needs to know *which folder* to write them into. The answer comes from whichever image the user is looking at right now.

**How the agent knows the active image.** Two options exist: (a) call `get_state` every time we need the answer, or (b) subscribe once to Fiji's event stream — the server already pushes events like `image.activated` and `image.opened` (see `imagej_events` in `ij.py`). **Use option (b).** The subscriber thread lives in `events.py`. It caches the last-seen active image path in a module-level variable, updated on every `image.activated` event. Reads are instant; no extra TCP round-trip.

**How the output folder is derived.** Given the active image's file path, the agent:
1. Takes the parent folder of the image file.
2. Checks whether an `AI_Exports/` subfolder already exists inside it.
3. Creates it if missing (one `os.makedirs(..., exist_ok=True)` call).
4. Returns that folder as the absolute path for all writes.

**Switching images mid-session.** If the user clicks a different image from a different folder, the next `image.activated` event updates the cached path, and the next call to `current_export_folder()` returns a new `AI_Exports/` next to the new image. Old outputs stay where they were — nothing is moved.

**No-path images.** If the active image has no file on disk (e.g. freshly created by `File > New`, or Blobs sample), `current_export_folder()` returns `None`. The safety layer treats `None` as "no valid folder" and refuses any macro that contains a save operation, with a clear message: "the active image has no file path — save it first or pick a default folder with `--export-dir`". Command-line flag `--export-dir PATH` sets a fallback.

**Where this lives.** A new file `agent/gemma4_31b/active_image.py` exporting one function:

```python
def current_export_folder() -> str | None:
    """Return the AI_Exports/ folder next to the currently active image,
    creating it if needed. Returns None if the active image has no file path."""
```

**Safety hook.** `safety.py` imports `current_export_folder` and calls it once before every `run_macro` / `run_macro_async`. It scans the macro text for save-family commands (`saveAs`, `save`, `File.saveString`, `IJ.saveAs`, `run("Tiff...", ...`) and extracts the target path. Any path that does not start with the current export folder is rejected before the macro reaches Fiji.

**Success check:** the agent runs Otsu threshold + Analyze Particles on an opened image, calls `saveAs("Results", ...)` with a path inside `AI_Exports/`, and the file lands in the right place. A second attempt with a path outside `AI_Exports/` is blocked with a readable error.

---

**Done when Phase 1 is finished:** the agent launches from the play button, opens an image, thresholds with Otsu, counts objects, and saves the results table into `AI_Exports/` next to the image — all from the terminal.

### Phase 2 — Lint before run *(completed)*

**Motivation.** Small macro mistakes are the single largest source of wasted cycles. Catching the common ones before they hit Fiji saves time and cost on every run.

A small checker that reads every proposed macro before it runs
and flags the ten most common Gemma mistakes, blocking the run
and sending the error back to the agent to fix.

First ten rules: Analyze Particles on a non-binary image; missing
`setOption("BlackBackground", true)`; `setAutoThreshold` on a
stack without the `stack` flag; Windows backslashes in a path;
Enhance Contrast with `normalize` before measuring; ROI Manager
not reset between runs; Results table not cleared; `run("X…")`
with a missing argument string; `waitForUser()` in autonomous
mode; `saveAs` outside the `AI_Exports/` folder.

One file, one list. Easy to add more rules later.

### Phase 3 — Auto-probe before run *(completed)*

**Motivation.** Models routinely invent plugin parameter names that don't exist. Pre-probing the real dialog before the macro runs eliminates the biggest class of macro failure.

Before a macro runs, scan it for `run("Plugin Name", ...)`
calls. For every plugin we don't already have cached parameters
for, silently probe the dialog and cache the real parameter
schema. Inject the schema into the next prompt so Gemma stops
making up parameter names.

### Phase 4 — describe_image *(completed)*

**Motivation.** A text-only model can't see pixels. This tool is the agent's substitute for vision — it turns the image into a short structured description with numbers attached, which every later reasoning step leans on.

**What the tool does.** One tool called `describe_image`. Signature: `describe_image() -> str` — no arguments. Acts on whatever image is currently active in Fiji. Returns a single paragraph of 150–300 words in plain English. Gemma cannot see pixels, so this paragraph is its eyes: every adjective has a number next to it ("moderately bright (mean 842)" not "moderately bright"), every claim comes from a measurement the tool actually performed, and nothing about biology, sample type, experiment goal, or recommended next step is ever included. If a measurement fails or a field is missing, the paragraph says so explicitly rather than omitting it.

**What the tool measures.** Each item below names a fact, how to compute it, and where the code already exists.

1. **Bit depth, dimensions, title.** From `get_image_info` (TCPCommandServer line 1510). Returns `title`, `width`, `height`, `type` (e.g. "GRAY16"), `slices`, `channels`, `frames`, `calibration` string, `isStack`, `isHyperstack`. Already wired. No new code.
2. **Channel / Z / T axes.** Same call — `channels`, `slices`, `frames`. If `isHyperstack` is true, report all three; otherwise report only non-1 axes. No new code.
3. **Pixel-size calibration.** The `calibration` string from `get_image_info`. Parse it: if it contains a unit other than "pixel" / "pixels" and a non-1.0 scale, report the value and unit. If it reads "1 pixel" or similar, report "uncalibrated". No new code, small parser in `describe_image.py`.
4. **Intensity stats: mean, median, min, max, std.** From `get_histogram` (TCPCommandServer line 1767) — returns `min`, `max`, `mean`, `stdDev`, `nPixels`, plus a `bins` array. Median is computed from the cumulative bin count (walk bins, stop when cumulative >= nPixels/2). No new TCP code; derive median in `describe_image.py`.
5. **Saturated-pixel fraction.** Count bins at the bit-depth maximum (255 for 8-bit, 65535 for 16-bit, etc.) divided by `nPixels`. Bit depth read from `type` field. Pure numpy on the `bins` array.
6. **Dynamic range used.** `max / bit_depth_max * 100`, rounded to 1 decimal. Pure arithmetic.
7. **Histogram shape.** Smooth the `bins` array with a 5-bin rolling mean. Unimodal if there is one peak > 5% of `nPixels`. Bimodal if there are two such peaks separated by a valley whose lowest count is at most 70% of the smaller peak; report the valley bin as the intensity value. Skewed if mean and median differ by more than 0.5 × std. Pure numpy on the returned `bins`.
8. **Auto-threshold object counts.** Apply Otsu, Li and Triangle to the `bins` array using standard formulas (all three have short, well-known numpy implementations — no Fiji round-trip). For each threshold, count 4-connected components in a downsampled thumbnail fetched via `get_pixels_array` (max side 512 px) using the flood-fill already in `pixels.py` `find_bright_objects`. Report three numbers; pick nothing.
9. **Obvious artifacts.** On the same thumbnail: (a) fraction of pixels at `min` — "clipped blacks" if > 1%; (b) split the image into four quadrants, compare max per quadrant — flag "saturated patch in {corner}" if one quadrant has > 5× the saturated-fraction of the others; (c) row-mean variance vs column-mean variance — flag "horizontal stripes" or "vertical stripes" if one exceeds the other by > 4×. All numpy in `describe_image.py`.
10. **Active ROI / overlay.** Add a one-line Groovy `run_script` call: `ij.IJ.getImage().getRoi() != null` and `ij.IJ.getImage().getOverlay() != null`. Report "one ROI active (rectangle, 312×240)" or "no ROI, no overlay". New code, ~10 lines.

**What the English paragraph looks like.**

Example 1 (well-behaved):
> Active image "sample01.tif" is 16-bit, 2048×2048 pixels, single channel, single z, single frame. Pixel size is calibrated at 0.325 µm per pixel. Intensity ranges from 118 to 14,220 with mean 842, median 612 and standard deviation 1,104; dynamic range used is 21.7% of the 16-bit maximum. Saturated-pixel fraction is 0.00%. The histogram is bimodal with a valley at intensity 1,480, consistent with a clear separation between dim background and bright foreground. Auto-thresholds produce 147 connected components with Otsu, 162 with Li and 134 with Triangle on a 512-pixel thumbnail. No clipped blacks, no quadrant saturation, no stripe pattern. One rectangular ROI is active (312×240 pixels); no overlay.

Example 2 (saturated, uncalibrated):
> Active image "scan_04.tif" is 8-bit, 1024×768 pixels, single channel, single z, single frame. Pixel size is **uncalibrated** (reads as "1 pixel"). Intensity ranges from 0 to 255 with mean 198, median 221 and standard deviation 52; dynamic range used is 100.0% of the 8-bit maximum. Saturated-pixel fraction is 8.4% — a large share of pixels are pinned at 255. The histogram is unimodal and strongly left-skewed (mean 198 vs median 221). Auto-thresholds produce 12 connected components with Otsu, 9 with Li and 41 with Triangle on a 512-pixel thumbnail. Clipped blacks at 0.3%; the top-right quadrant carries 86% of the saturated pixels, suggesting a saturated patch in that corner. No stripe pattern detected. No ROI, no overlay.

**What the tool must not do.** No guesses about biology ("these look like nuclei", "typical DAPI signal" — forbidden). No guesses about experiment type, modality, or sample. No recommendations ("try Gaussian blur", "lower the exposure"). No measurement may be mentioned unless it was actually computed in this call — if bin data is missing, omit the histogram-shape sentence rather than fake one.

**Implementation outline.** File: `agent/gemma4_31b/describe_image.py`. One public function `describe_image() -> str`. Internally: (a) `get_image_info` via TCP; (b) `get_histogram` via TCP; (c) `get_pixels` (downsampled to max 512 px side) decoded with numpy, adapted from `agent/pixels.py`; (d) a one-line Groovy `run_script` for ROI/overlay presence. Each of the ten measurements becomes a short pure function returning either a formatted fragment or `None`. `describe_image()` slots the non-None fragments into a fixed template matching the two examples above. **Template-based, not LLM-generated** — the agent's own output must never feed this paragraph, to avoid hallucination.

**Success check.** A human reader, given only the paragraph, can say what kind of image it is (bit depth, dimensions, roughly how bright, bimodal or not, how many objects at common thresholds) and whether anything is obviously wrong (saturated, uncalibrated, striped, corner-burned), without ever seeing the image.

### Phase 5 — `triage_image` *(completed)*

**Motivation.** Many failures come from bad setup rather than bad thinking — missing pixel-size calibration, collapsed z-stacks, saturated pixels. Catching these right after the image is opened saves the rest of the session.

Runs on every newly opened image. Checks calibration, bit depth,
axis order, saturation, likely artifacts. Returns a short list
of warnings ("no pixel size — set it before measuring", "image
is 2D but filename says z-stack — was it collapsed?"). Non-
blocking; the agent decides whether to ask the user or press on.

### Phase 6 — Threshold shootout *(completed)*

**Motivation.** Thresholding choice is heavily image-dependent and models tend to guess one method and stick with it. Running several side-by-side lets the data pick rather than the agent's default guess.

When the user mentions thresholding, segmenting or masking, run
Otsu, Li, Triangle, Minimum and Huang side by side. Save a
labelled montage in `AI_Exports/`. Return a small table of
object counts, coverage and mean object size per method so the
agent can pick on evidence instead of guessing.

### Phase 7 — Visual diff *(completed)*

**Motivation.** Sometimes a macro runs without error but does the wrong thing. Comparing before/after pixel statistics catches the class of silent failure that would otherwise slip through unreviewed.

Around every destructive macro, auto-capture before and after.
Compute the fraction of pixels changed, the mean intensity
shift and a shape-of-histogram difference. Flag anything
inconsistent with what the macro claims to do ("Median filter
changed 91% of pixels — probably wrong").

### Phase 8 — Propose two candidates

**Motivation.** When the right approach is genuinely unclear, the agent should say so rather than pick silently. Forcing two candidates with pros and cons makes uncertainty visible to the user.

For ambiguous requests, force Gemma to generate two competing
macros with pros, cons and a recommendation. If both look viable
ask the user before running; otherwise run the recommended one.

### Phase 9 — Recipe autopilot *(completed)*

**Motivation.** What worked once for a biologist almost always works for similar images. Capturing successful workflows as reusable recipes means the agent grows more useful with every session without retraining the model.

After a workflow that worked, the agent offers to save it as a reusable YAML recipe in `agent/recipes/`. Written by hand would take ten minutes; the agent does it in chat in under a minute. The one hard rule: the agent does **not** decide which numbers are generalisable. The user does.

**Trigger.** The phase runs at the end of a successful workflow — no macro errors in the last few turns — when one of the following happens:

- The user sends a short positive message ("thanks", "done", "looks good", "perfect", "that worked"). Simple keyword match, case-insensitive.
- The user types the slash command `/save-recipe`.

Either way the agent opens with one question: "Want to save this workflow as a reusable recipe?" No → nothing else happens, the workflow ends normally. Yes → the rest of the phase runs.

**Raw material.** Everything the agent needs is already on disk. `AI_Exports/` holds the audit log from Phase 1, with every macro and script it ran this session in order. The harvester reads that log and keeps only the steps that did real work — it drops read-only calls like `get_state`, `capture_image`, `get_log`, `get_results`, `get_open_windows`, `get_dialogs`, `get_histogram`, and any macro that only printed to the log. The surviving list becomes the recipe's ordered steps.

**Parameter marking rule (the hard part).** For every literal number that appears inside a kept macro — sigma values, radii, size ranges, threshold numbers, timeouts — the harvester writes one entry in `parameters:` with `image_specific: true`. Nothing is promoted to reusable automatically. This is deliberate: without an auditor the agent cannot tell whether `sigma=2` was a good default or just a guess that happened to work on one image, and a silently-generalised bad number would poison future sessions. Promotion is user-driven: the user must type something like "Otsu always works for this stain, save it as default" or "mark the sigma as reusable". Before flipping the flag the agent must ask once more: "Confirm promote `sigma = 2.0` to reusable (image_specific: false)?" Yes → flip. Anything else → leave it.

**YAML layout.** Follows the style of `agent/recipes/cell_counting.yaml` and `agent/recipes/drift_correction_timelapse.yaml` so new recipes sit next to old ones without standing out:

```yaml
name: <user-supplied display name>
id: <slug from name>
description: <one-line user-supplied>
domain: <filled from describe_image, e.g. fluorescence_2d>
difficulty: unrated

preconditions:            # auto-filled from describe_image output
  image_type: [8-bit]
  min_channels: 1
  needs_stack: false
  needs_calibration: false
  notes: "Captured from a 2048x2048 16-bit single-channel fluorescence image."

parameters:
  - name: blur_sigma
    value: 2
    image_specific: true  # default for every literal number
    note: "Used in step 2 Gaussian Blur. Re-check on new images."

steps:
  - id: 1
    description: "Duplicate to preserve original"
    code: |
      run("Duplicate...", "title=mask");
  - id: 2
    description: "Gaussian blur"
    code: |
      run("Gaussian Blur...", "sigma=${blur_sigma}");

validation:               # only if audit_results ran in the session
  - check: "particle count in range 50-2000"
    method: "auditor.py output"

created:
  timestamp: "2026-04-18T14:02:11Z"
  session_id: "gemma-20260418-1340"
```

**User flow.**

1. Agent asks: "Want to save this as a reusable recipe?"
2. On yes: "What should we call it?" — user replies with a short name.
3. Agent drafts the full YAML and pastes it back in the chat.
4. User edits by chat: "change step 2 sigma note to 'works for DAPI'", "drop step 4", "mark blur_sigma as reusable". Agent applies each edit and reprints the YAML.
5. When the user says "save it" (or equivalent), agent writes `agent/recipes/<slug>.yaml` and confirms the path.
6. Next session `search_recipes` finds it automatically — the tool already scans that directory.

**What it must not do.**

- Never save without an explicit user go-ahead.
- Never promote a parameter to reusable on its own — every `image_specific: false` is confirmed by the user in chat first.
- Never overwrite. If `<slug>.yaml` exists, append `_2`, `_3`, and so on; tell the user the final filename.
- Never invent preconditions. If `describe_image` was not run this session, run it once now; otherwise leave the field blank with a comment.

**Implementation outline.**

- New file `agent/gemma4_31b/harvest_recipe.py` with the log parser, the noise filter, the number-extractor, and the YAML writer.
- One tool `offer_recipe_save()` registered in `tools_recipes.py` — called from `loop.py` when the trigger fires.
- One tool `save_recipe(name, description, promote_list)` where `promote_list` is the parameter names the user approved for reuse. Everything not in that list stays `image_specific: true`.
- No changes to the Fiji side, no new TCP commands.

**Success check.** A workflow that worked once becomes a YAML recipe in under a minute of chat. Next session, the user types "do what we did last time on this image" and `search_recipes` returns the new recipe by name or tag.

### Phase 10 — Sticky lab profile

**Motivation.** Repeatedly answering "it's 16-bit, two channels, 0.28 µm per pixel" wastes the biologist's time. A short stored profile of their typical images lets the agent skip the obvious questions.

After enough sessions the agent derives a profile — typical
modality, bit depth, channels, pixel size, common task — and
asks the user to confirm. Once confirmed, future prompts assume
the profile and stop asking basic questions.

### Stretch (later, not scheduled)

- Second-Gemma critic: a small local model that adversarially
  reviews the main agent's macro before it runs.
- Counterfactual panels: run a macro at four parameter values
  on a duplicate, show a 2×2 preview before committing.
- Silent observer: watches the user's own manual dialog clicks
  and offers to turn recurring patterns into recipes.
- Auto methods-section writer.
- Paper PDF → recipe doppelganger.

---

## File layout

A new folder at `agent/gemma4_31b/`, sibling to
`agent/ollama_agent/`. Does not import from either AgentConsole
or `agent/ollama_agent/`. Starter files can be copied from
`agent/ollama_agent/ollama_chat.py` and `agent/ij.py`.

```
agent/gemma4_31b/
├── __init__.py
├── __main__.py            # entry point launched by the play button
├── pyproject.toml         # registers gemma4_31b_agent on PATH
├── GEMMA.md               # rules and tool overview the agent reads
├── loop.py                # chat loop, Ctrl-C handling, event draining
├── registry.py            # @tool decorator + REGISTRY list
├── events.py              # background subscriber, event queue
├── safety.py              # AI_Exports path rule + audit log
├── friction.py            # dev-only friction log, flag-gated
├── tools_fiji.py          # run_macro, get_state, get_log, capture, ...
├── tools_python.py        # numpy-based image analysis (no macro needed)
├── tools_plugins.py       # probe_plugin, list_installed, schema cache
├── tools_recipes.py       # search_recipes, get_recipe, save_recipe
├── tools_dialogs.py       # list, set text / checkbox / dropdown, click
├── tools_jobs.py          # async macro + job status + cancel
└── tools_events.py        # get_pending_events, wait_for_event
```

Later phases add one file each:

```
├── lint.py                # Phase 2
├── auto_probe.py          # Phase 3
├── describe_image.py      # Phase 4
├── triage_image.py        # Phase 5
├── threshold_shootout.py  # Phase 6
├── visual_diff.py         # Phase 7
├── candidates.py          # Phase 8
├── harvest_recipe.py      # Phase 9
└── lab_profile.py         # Phase 10
```

---

## Fiji-side change

One line added to `KNOWN_AGENTS` in
`src/main/java/imagejai/engine/AgentLauncher.java`:

```java
{"Gemma 4 31B", "gemma4_31b_agent", "Local Ollama agent (free, no API key)", ""},
```

No other Java changes are needed for Phase 1.
