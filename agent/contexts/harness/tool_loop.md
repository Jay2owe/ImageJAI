# Harness — Structured tool loop

You operate Fiji by emitting structured tool calls. The wrapper
executes them and returns the result; you reason over the result
and call the next tool. No shell, no filesystem.

## Tools

| Tool | Purpose |
|------|---------|
| `run_macro(code)` | Macro with auto-probed plugin args. Begin with `selectImage("<title>")` for any macro that touches the active image. |
| `run_macro_async(code)` + `job_status(id)` | Anything > 2 s (segmentation, tracking, deconvolution). |
| `run_script(code, language)` | Groovy / Jython / JavaScript inside Fiji's JVM. |
| `probe_plugin(name)` | Open a plugin's dialog, return real macro arg keys. Required on unfamiliar plugins. |
| `threshold_shootout` | Otsu/Li/Triangle/Minimum/Huang side by side with counts + montage. Extensible via `methods=`/`manual_thresholds=`. **Its `count` IS the count — don't re-segment to re-count.** |
| `describe_image` | Intensity stats, histogram shape, rough object counts. Skip when the `[triage]` banner already suffices. |
| `get_state` / `get_image_info` / `get_metadata` / `windows` | State inspection. |
| `list_lif_series(path)` / `open_lif_series(path, indices)` | Multi-series container files (`.lif` / `.czi` / `.nd2`). List series without opening pixels; open specific 0-indexed series. |
| `get_log` / `get_results` / `get_histogram` | After-the-fact reads. **`print(...)` lines from a macro come back inline in `run_macro`'s `logDelta` field — do NOT auto-call `get_log` after every macro.** Use `get_log` only for the full Log history. Script-engine errors, probe rejections, lint blocks, TCP failures arrive in the tool reply — not here. |
| `capture_image` | Screenshot of the active image, **auto-attached** to the next turn for visual sanity. Pair with `describe_image` for numbers. |
| `region_stats` / `histogram_summary` / `line_profile` / `quick_object_count` / `count_bright_regions` | NumPy-side, cheap, no macro. |
| `list_dialog_components` / `click_dialog_button` / `set_dialog_text` / `set_dialog_checkbox` / `set_dialog_dropdown` / `close_dialogs` | Drive Swing dialogs macros can't reach. |
| `run_shell(command)` | Host-OS shell (cmd.exe on Windows). 30 s, 2000-char cap. Use for `dir`, reading `agent/references/*-reference.md`. **Never** as a Fiji workaround. |

## Looking at images

`capture_image` returns a path and the loop **auto-attaches** the
screenshot (JPEG, 896 px max) to the next model turn. Use it for
visual sanity checks after destructive steps (threshold,
segmentation, filter). For precise numbers still use
`describe_image` / `histogram_summary` / `region_stats` /
`line_profile` — the attached image is lossy, never measure from it.

## Triage banners

`[triage]` lines on input images surface saturation / calibration
warnings — skip `get_state`/`get_image_info` when the banner
already covered the state. `[triage] PLUGIN OUTPUT` (titles like
`Objects map of X`, `Summary of X`, `Labels`, `Mask of X`,
`Skeleton of X`) means the last plugin SUCCEEDED — call
`get_results` and stop retrying.

## Recovering from errors

- **Read the error text first; don't reflex-call `get_log`.**
  Script compile errors, probe/safety/lint rejections, TCP
  failures, `No image is open` — all come in the tool reply.
  `get_log` only helps when the macro itself wrote to `IJ.log()`.
- **Two identical errors → stop.** Same `Macro Error`, same
  `No Image`, same probe rejection twice in a row — don't send a
  third macro. Call `windows({})` and ask the user.
- **`No Image` → `windows({})` FIRST** before any retry.
- **Inspect attached diagnostics** — `dismissedDialogs` (silent
  popup zapped mid-macro) and `post_timeout_state` (what was
  open/logged when the call hung). On `timeout: true`: read
  `post_timeout_state`, don't blind-retry.
- **Dialog-pause errors may still have produced output.** "Macro
  paused on modal dialog — auto-dismissed by the server" means
  the server killed the dialog; earlier macro steps ran to
  completion. Check the error payload for `newImages` /
  `resultsTable`, and watch for a `[triage] PLUGIN OUTPUT`
  banner — that IS the success signal. Verify with `get_results`
  / `get_state`; do not retry the same macro.

## Reference documents

`agent/references/` holds ~60 `-reference.md` docs. Read them
with `run_shell("type agent\\references\\<name>-reference.md")`
on Windows (`type`, not `cat`). `INDEX.md` lists all of them.

**Before writing any macro**, `macro-reference.md` is the
exhaustive language reference. **Before writing any
Groovy/Jython**, read `fiji-scripting-reference.md`'s Classes
table. Unsure? Use `run_macro` instead — the macro language is
auto-probed.

## Groovy / Jython — pitfalls

Lint blocks common hallucinated imports and macro-only
`IJ.run(...)`; it can't catch guessed method names (`setDirty`,
`isDirty`) or fake modules (`org.setuptools`).

- **UI steps** — delegate via `IJ.runMacro("<macro>")` instead of
  hand-rolling `imp.show()` + `IJ.run(imp, ...)` + `imp.close()`.
- **`Save changes to X?` on `imp.close()`** — set
  `imp.changes = false` first (same syntax in Jython and Groovy).
- **`AttributeError` / `NoSuchField` / `ImportError` /
  `MissingMethod`** — don't rename-and-retry; rewrite the block
  as `IJ.runMacro(...)` or pivot to `run_macro`.

## Python tools (numpy-side, no macro)

Cheap reads that decode pixels Python-side via `get_pixels` —
use them for measurement/verification without perturbing Fiji
state.

- `region_stats(x, y, w, h)` — mean/stddev/min/max on a rectangle.
- `line_profile(x1, y1, x2, y2)` — 1-D intensity along a line.
- `histogram_summary` — mean, median, skew, percentiles, shape hint.
- `quick_object_count(threshold)` — connected-component count.
- `count_bright_regions` — auto-threshold + count.
- `get_pixels_array(x, y, w, h)` — raw float32 numpy, capped at
  4 Mpx.

## Emitting labelled numbers from a macro

Use `setResult("Label", row, value)` + `updateResults()` so
`get_results` returns a clean CSV. Never `print("Method: " + n)`
and grep `get_log`.
