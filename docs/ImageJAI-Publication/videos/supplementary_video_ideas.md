# Supplementary Video Ideas

Each idea below is written as a paper-demo unit: claim, setup, prompt, expected
agent actions, and what the viewer should see.

## Priority Set

### Video 1 - Plain English To Valid Fiji Macro

**Claim:** ImageJAI turns a natural-language request into a working Fiji macro
and executes it on the live image.

**Setup:** Open `Blobs` or a simple nuclei image.

**Prompt:** "Segment the bright objects, count them, and export the
measurements next to the image."

**Expected actions:** Check state, run threshold/particles macro, capture
image, read Results table, save CSV in `AI_Exports/`.

**End artifact:** Overlay plus CSV and macro.

### Video 2 - Plugin Probing Before Execution

**Claim:** ImageJAI can use unfamiliar Fiji plugins by inspecting their dialogs
before running them.

**Setup:** Choose a GenericDialog-based plugin such as Gaussian Blur or
Analyze Particles.

**Prompt:** "Use the Gaussian Blur plugin with a sigma of 2, but first check
the exact macro arguments."

**Expected actions:** Run `probe_command`, list fields and macro keys, cancel
dialog, then execute the correct macro.

**End artifact:** Probe output beside successful processed image.

### Video 3 - Dialog Interaction Instead Of Stalling

**Claim:** The agent can read and operate Fiji dialogs, not just headless
commands.

**Setup:** Open a plugin with a modal dialog.

**Prompt:** "Open this plugin, set the thresholding method to Otsu, turn on
display results, and click OK."

**Expected actions:** `get_dialogs`, `interact_dialog list_components`,
set dropdown/check boxes/text fields, click OK, capture result.

**End artifact:** Visible filled dialog, then result image/table.

### Video 4 - Self-Correction After A Wrong Command

**Claim:** Structured feedback lets the agent repair ImageJ mistakes.

**Setup:** Inject or ask for a command likely to be wrong, e.g. a misspelled
plugin name.

**Prompt:** "Apply a Laplacian-of-Gaussian style filter and show the result."

**Expected actions:** Attempt, receive structured error/fuzzy suggestions,
choose valid Fiji command or plugin, rerun, capture output.

**End artifact:** Before/after plus error and corrected command.

### Video 5 - Seeing The Image To Choose The Method

**Claim:** ImageJAI can use image appearance to select analysis strategy.

**Setup:** Open two channels: nuclei and cytoplasm, or two different sample
images.

**Prompt:** "Look at these images and decide which one should use nuclei
segmentation and which should use cytoplasm/cell segmentation."

**Expected actions:** Capture screenshot, inspect image, explain choice, run
different segmentation strategies.

**End artifact:** Two outputs with distinct method choices.

### Video 6 - Free Local Model Controls Fiji

**Claim:** A local/free model can control ImageJAI through the same bridge.

**Setup:** Launch Gemma 4 31B or another Ollama-backed model from the agent
dropdown.

**Prompt:** "Open Blobs, count objects, and export the Results table."

**Expected actions:** Local agent uses `ij.py` TCP commands, completes a small
task with no hosted API call.

**End artifact:** Terminal showing local model, Fiji result, exported CSV.

### Video 7 - Token-Efficient State Delta

**Claim:** The server gives compact state updates instead of repeated full
state dumps.

**Setup:** Run a short sequence with `hello` capabilities enabling `pulse` and
`state_delta`.

**Prompt:** "Invert this image, blur it, then tell me what changed after each
step."

**Expected actions:** Show replies with compact pulse/state delta rather than
full session context.

**End artifact:** Side-by-side log of command, pulse, and changed state.

### Video 8 - Histogram Delta As Vision-Free Feedback

**Claim:** Text-only or local models can detect image changes from compact
numerical summaries.

**Setup:** Open grayscale image.

**Prompt:** "Try two preprocessing methods and pick the one that improves
object-background separation."

**Expected actions:** Run methods, inspect `histogramDelta`, optionally
capture, choose better output.

**End artifact:** Before/after histograms and chosen image.

### Video 9 - Results Table Audit

**Claim:** ImageJAI does not stop at image generation; it checks measurements.

**Setup:** Segment and measure objects.

**Prompt:** "Check whether these measurements make sense before exporting."

**Expected actions:** Read Results table, check row count/columns/ranges, run
auditor or simple sanity checks, flag missing source image or calibration.

**End artifact:** Validation note plus corrected/exported results.

### Video 10 - Bio-Formats Metadata And Calibration

**Claim:** The agent can inspect metadata and avoid uncalibrated measurements.

**Setup:** Open calibrated microscopy file if available.

**Prompt:** "Measure cell areas in square microns and confirm the image is
calibrated first."

**Expected actions:** `get_metadata`, `get_image_info`, inspect calibration,
then measure or stop with a clear warning.

**End artifact:** Results with physical units or a principled refusal.

### Video 11 - 3D Viewer / Stack Workflow

**Claim:** ImageJAI can reason across z-stacks and use Fiji 3D tools.

**Setup:** Open a 3D sample stack.

**Prompt:** "Make a max projection, then create a 3D volume view snapshot."

**Expected actions:** Inspect dimensions, run projection, use `3d_viewer`
status/add/snapshot, export image.

**End artifact:** Max projection and 3D snapshot.

### Video 12 - Recovering From A Blocking Macro Error Dialog

**Claim:** ImageJAI avoids the common failure where a modal error dialog blocks
every later command.

**Setup:** Run a deliberately broken macro.

**Prompt:** "Run this macro, fix any error, and continue without me touching
the dialog."

**Expected actions:** Detect Macro Error dialog, read text, dismiss safely,
repair code, rerun.

**End artifact:** Error dialog text, corrected macro, successful result.

## Extended Capability Set

### Video 13 - Recipe Search And Reuse

Show the agent finding a YAML recipe for a known workflow, adapting it to the
open image, and exporting outputs.

### Video 14 - Reference-Grounded Advice

Show the agent answering a microscopy-analysis question using local references,
then applying the recommended workflow.

### Video 15 - Parameter Exploration Shootout

Ask for "best threshold method for this image." The agent runs multiple
methods, compares counts/areas/histograms, and recommends one.

### Video 16 - ROI Manager State

Draw or load ROIs, ask the agent to measure inside them, then verify it used
the ROI Manager instead of measuring the whole image.

### Video 17 - Display-Only Contrast Warning

Ask the agent to improve visibility before measurement. It should use
display-range changes, not destructive `normalize=true`.

### Video 18 - Batch Processing With Source-Image Tracking

Run the same workflow over a small folder and show outputs in per-image
`AI_Exports/` folders or a combined CSV with source-image identity.

### Video 19 - Provenance Graph

Run a multi-step workflow, then show a graph or JSON trace linking derived
images to commands and source images.

### Video 20 - Undo / Branch Experimentation

If verified, show the agent trying two branches of preprocessing, comparing
outputs, and rewinding to the preferred branch.

### Video 21 - Event Stream / Progress Monitoring

Run a long job and show the agent receiving progress/status events rather than
polling full state repeatedly.

### Video 22 - Reactive Rules

If verified, show a rule detecting a common problem, such as an error dialog or
uncalibrated measurement, and surfacing a warning automatically.

### Video 23 - Python-Side Pixel Analysis

Use `pixels.py` for region stats, line profile, or stack stats without loading
large pixel payloads into the model context.

### Video 24 - Groovy Inside Fiji's JVM

Ask for a task macros cannot handle cleanly, then show `run_script` using Fiji
Java APIs or Swing components.

### Video 25 - Local Model Failure And Recovery

Use Gemma/Ollama on a task where it first guesses wrong, then show how
structured errors, pulse, and plugin probing help it recover.

### Video 26 - Agent Choice From Dropdown

Show the same small task launched through Claude Code, Codex CLI, Gemini CLI,
and Gemma to demonstrate the server is model-agnostic.

### Video 27 - Public-Release Install Flow

Show a clean Fiji installation, drag in the JAR, open Plugins > AI Assistant,
choose Gemini/Ollama, and run the first task.

### Video 28 - End-To-End Biological Story

A longer capstone: open a real lab image, inspect metadata, choose method,
segment, measure, audit, export CSV/overlay/macro, and write a concise methods
paragraph.

### Video 29 - Open Fiji Versus Paid Imaris-Style Workflow

Show the same routine analysis goal framed two ways: Imaris-style polished
commercial workflow versus ImageJAI making Fiji conversational, transparent,
and low-cost. The video should not claim ImageJAI wins 3D rendering. It should
show that a biologist can get a valid CSV, overlay, macro, and audit trail from
free/open Fiji without needing a commercial license for that task.

### Video 56 - QUAREP-LiMi Methods Table Auto-Emission

Demonstrate the QUAREP-LiMi WG11-aligned `methods.md` exporter (D8). Brief
shot list:

1. Open a public BBBC020 image (mouse-monocyte Hoechst nuclei) — drag onto
   Fiji, no Blobs.
2. Run a short count + measure analysis through the agent (Gaussian blur,
   Otsu threshold, Watershed, Analyze Particles).
3. Call `python methods_table.py` (or `emit_methods_table` via TCP).
4. Show the resulting `AI_Exports/methods.md` side-by-side with the image,
   highlighting which fields auto-populated from Bio-Formats / session log /
   provenance graph and which are `[unknown]`.
5. End on the **field-coverage statistic line** that the exporter prints —
   `Field coverage: N/M WG11 fields populated; (M-N) marked [unknown]` — with
   a voice-over note that the file is a draft for the author to edit, NOT
   automated journal compliance.
