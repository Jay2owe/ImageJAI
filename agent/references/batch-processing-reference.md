# Batch Processing & Bio-Formats Reference

Reference for processing multiple images in ImageJ/Fiji via macro language.
Covers folder iteration, `setBatchMode`, Bio-Formats import/export, Bio-Formats
Macro Extensions, memory management, Results/ROI handling in loops, error
handling, progress logging, and five full batch templates.

Sources: `imagej.net/ij/docs/guide/146-4.html` (Batch Processing), Bio-Formats
docs (`bio-formats.readthedocs.io/en/stable/users/imagej/`), Fiji wiki
(`imagej.net/software/fiji`). Use `python probe_plugin.py "Plugin..."` to
discover any installed plugin's parameters at runtime.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript.
`python ij.py state` — inventory of open images / windows before a batch.
`python ij.py log` — read `showProgress` / `print` output during/after a batch.
`python ij.py results` — retrieve the Results table as CSV after a batch.
`python ij.py metadata` — Bio-Formats metadata on the active image.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Give me a minimal batch skeleton I can copy." | §2 |
| "How do I list / filter files in a folder?" | §3 |
| "How do I walk subdirectories recursively?" | §3 |
| "How do I match pairs of files (e.g. DAPI + GFP)?" | §3, §13 template 4 |
| "What does `setBatchMode(true)` actually do?" | §4 |
| "How do I close every image after a batch step?" | §4 |
| "What options does the Bio-Formats Importer take?" | §5 |
| "How do I open a specific series from a `.lif` / `.nd2`?" | §5, §13 template 3 |
| "Can I peek at file dimensions without opening it?" | §6 |
| "What's the full list of `Ext.*` functions?" | §6 |
| "How do I estimate memory before opening a big image?" | §8 |
| "How do I accumulate measurements across many images?" | §9 |
| "How do I use custom `Table.*` tables instead of Results?" | §9 |
| "How do I save / reuse ROIs across images?" | §10 |
| "Macro has no try/catch — how do I skip bad files?" | §11 |
| "How do I log progress in a way the agent can read?" | §12 |
| "Which template matches my data layout?" | §13, Appendix decision tree |
| "How do I drive batches over TCP from Python?" | §14, §31 |
| "Out of memory / slow / hung on a dialog — why?" | §15, §29 |
| "Utility functions I can paste in?" | Appendix |
| "How do I make my pipeline idempotent / resumable?" | §18 |
| "How do I do atomic file writes (no half-finished outputs)?" | §18.3 |
| "What does a manifest CSV/YAML look like?" | §18.4 |
| "How do I quarantine bad files instead of crashing?" | §19.2 |
| "How do I retry transient failures with backoff?" | §19.3 |
| "How do I time-out a single file?" | §19.4 |
| "What goes in a per-run log header?" | §20.1 |
| "What goes in a per-file log row?" | §20.2 |
| "How do I record the macro/script next to its output?" | §20.4 |
| "When should I restart Fiji during a long batch?" | §21.4 |
| "How do I spawn a fresh JVM per N files?" | §21.5 |
| "What is `.bfmemo` and how do I enable it?" | §22 |
| "How do I run a parameter grid sweep?" | §23.1 |
| "Random / Latin-hypercube sweep for many params?" | §23.2 |
| "How do I name sweep output dirs?" | §23.3 |
| "Exact `--headless --console --run` syntax?" | §24.1 |
| "How do I pass args into a headless macro?" | §24.2 |
| "How do I install plugins from CI (`--update`)?" | §24.3 |
| "Which plugins won't work headless?" | §24.5 |
| "How do I run N Fiji headless processes in parallel?" | §25.1 |
| "GNU parallel template for headless Fiji?" | §25.2 |
| "Snakemake / Nextflow patterns?" | §25.4 |
| "How do I watch a folder and process new files?" | §26 |
| "How do I detect a file is fully written before opening?" | §26.3 |
| "What is the canonical batch directory layout?" | §27 |
| "Bash + headless + sweep + resume template?" | §28.1 |
| "Pure-macro robust template?" | §28.2 |
| "Python orchestrator template (subprocess / pyimagej)?" | §28.3 |
| "Minimal Snakefile for a 3-stage Fiji pipeline?" | §28.4 |
| "What does an `hs_err_pid<N>.log` mean?" | §29.2 |
| "Comma-vs-dot decimals breaking my CSVs?" | §29.6 |
| "How do I validate a finished batch run?" | §30 |
| "TCP recipes for batch operations?" | §31 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '`<term>`' batch-processing-reference.md` to jump.

### A
`Analyze Particles` §2, §10, §13 · `Array.concat` §13 · `Array.getStatistics` §9, §13 · `Array.sort` §3 · `Atomic file write (.tmp + rename)` §18.3 · `Auditor pattern` §30.4

### B
`Batch Mode` §4 · `Bio-Formats Exporter` §5 · `Bio-Formats Importer` §2, §5, §13 · `Bio-Formats Macro Extensions` §6, §8 · `Bio-Formats Windowless Importer` §5, §15 · `bfconvert` §15 · `.bfmemo (Memoizer)` §22 · `bioformats2raw` §22, §28 · `bpp (bytes per pixel)` §8 · `Backoff (exponential)` §19.3

### C
`checkMemoryMB` Appendix · `Clear Results` §9, §13 · `close / close("*") / close("\\Others") / close("<name>") / close("<pattern>*")` §2, §4 · `color_mode` §5 · `concatenate` §5 · `Collect Garbage` §8, §13, §21 · `Convert to Mask` §2, §10, §13 · `crop (Bio-Formats import)` §5 · `Checkpointing` §18.2 · `Console (Fiji stdout)` §24.6 · `--console flag` §24.1, §24.6 · `cron / scheduled batches` §26.5

### D
`Decision tree (batch)` Appendix · `display_metadata` §5 · `display_ome-xml` §5 · `display_rois` §5 · `Duplicate...` §4, §5, §13 · `Directory layout (canonical)` §27

### E
`endsWith` §2, §3 · `Error handling` §11, §19 · `Ext.close` §6, §8 · `Ext.getImageCount` §6 · `Ext.getImageCreationDate` §6 · `Ext.getMetadataValue` §6 · `Ext.getPixelType` §6, §8 · `Ext.getPlanePositionX / Y / Z` §6 · `Ext.getPlaneTimingDeltaT` §6 · `Ext.getPlaneTimingExposureTime` §6 · `Ext.getSeriesCount` §6, §13 · `Ext.getSeriesName` §6, §13 · `Ext.getSizeC / SizeT / SizeX / SizeY / SizeZ` §6, §8, §13 · `Ext.isIndexed / isInterleaved / isLittleEndian / isRGB` §6 · `Ext.openImage / openImagePlus` §6 · `Ext.setId` §6, §8, §13 · `Ext.setSeries` §6, §13, §15 · `End-to-end templates` §28

### F
`File format patterns (TIFF/OME-TIFF/ND2/LIF/CZI/LSM/OIB/OIF/IMS)` §7 · `File.exists` §3, §11 · `File.getDirectory` §3 · `File.getName` §3 · `File.getNameWithoutExtension` §3, §10, §13 · `File.length` §11 · `File.makeDirectory` §2, §13 · `File.rename` §18.3 · `File.saveString` §11 · `Fill Holes` §13 · `Failure modes (post-mortem)` §29 · `Fingerprint (input hash)` §18.2, §20.2 · `Fresh JVM per N files` §21.5 · `FSEvents (macOS)` §26.2 · `fsync` §18.3

### G
`Gaussian Blur` §13 · `getDimensions` §13 · `getFileList` §2, §3 · `getImageID` §4, §15 · `getInfo("log")` §12 · `getResult` §9 · `getTime` §11, §12 · `getTitle` §13 · `Git SHA in run log` §20.1 · `GNU parallel` §25.2 · `Grid sweep` §23.1

### H
`Hyperstack (view)` §5 · `headless (Bio-Formats)` §15, §22 · `Headless ImageJ` §24 · `--headless flag` §24.1 · `Health-check (mid-run JVM)` §21.4 · `hs_err_pid<N>.log` §29.2

### I
`IJ.currentMemory` §8, §21 · `IJ.maxMemory` §8, §15, §21 · `Image Sequence` §7 · `inotify` §26.1 · `isValidImageFile` §3, §8, §11, Appendix · `Idempotent processing` §18.1 · `Integration tests (post-batch)` §30 · `--ij2 flag` §24.1

### J
`JSON sidecar (failure)` §19.2 · `JSON Lines (jsonl) logs` §20.3 · `JVM lifecycle (heap, restart)` §21 · `Jaunch launcher` §21.2

### L
`Large multi-series memory strategy` §15 · `Latin hypercube sweep` §23.2 · `Locale (decimal separator)` §29.6 · `Log window` §2, §12 · `lifPath / .lif iteration` §13 · `lowercase filtering` §13

### M
`Manifest CSV / YAML` §18.4, §27 · `Matched pairs` §3, §13 · `Measure` §9, §10, §13 · `Memoizer (loci.formats)` §22 · `Memory per image (formula)` §8 · `Memory-efficient batch template` §8 · `--mem flag` §21.2, §24.1 · `Mirror input directory tree` §27.2

### N
`Network drives (timeouts)` §29.4 · `Nextflow` §25.4 · `nImages` §4, §8, §11 · `nResults` §9, §13

### O
`open` §2, §3 · `open_all_series` §5 · `OME-TIFF` §7 · `OOM diagnosis` §29.1 · `Otsu (threshold)` §2, §13 · `Output organization` §27

### P
`Parallelism (when worth it)` §25 · `Param sweep (grid / random / LHS)` §23 · `params.yaml` §27 · `Per-file logging` §20.2 · `Per-run log header` §20.1 · `pyimagej` §28.3 · `print` §11, §12 · `Progress bar` §2, §12 · `Provenance` §20

### Q
`Quarantine bin (failed/<reason>/)` §19.2 · `Queue-driven processing` §26

### R
`ReadDirectoryChangesW (Windows)` §26.2 · `rename` §3, §13 · `replace` §3, §13 · `Restart Fiji periodically` §21.4 · `Results table (accumulate / read / save)` §9 · `Resumable runs` §18.2 · `Retry with exponential backoff` §19.3 · `rois_import` §5 · `roiManager("Add")` §10 · `roiManager("Count")` §10, §13 · `roiManager("Deselect")` §10, §13 · `roiManager("Measure")` §10, §13 · `roiManager("Multi Measure")` §10 · `roiManager("Open")` §10 · `roiManager("Reset")` §10, §13 · `roiManager("Save")` §10, §13 · `roiManager("Select")` §10 · `roiManager("Show All with labels")` §10 · `RUN.md` §27, §28 · `--run flag` §24.1, §24.2

### S
`safeOpen` §11, §15, Appendix · `sanitiseFilename` Appendix · `saveAs("Results", …)` §2, §9, §13 · `saveAs("Text", …)` §8 · `saveAs("Tiff", …)` §2, §13 · `selectImage` §4, §15 · `selectWindow` §8, §13, §15 · `series_N (1-indexed)` §5, §6, §13, §15 · `Set Measurements` §9, §13 · `setAutoThreshold` §2, §10, §13 · `setBatchMode` §2, §4 · `setResult` §9, §13 · `showProgress` §2, §3, §12 · `showStatus` §12 · `Skip already-processed files` §15, §18.2 · `Snakemake` §25.4, §28.4 · `Split Channels` §13 · `split_channels` §5, §15 · `split_focal` §5 · `split_timepoints` §5 · `Spaces in paths` §3 · `Stable-file detection` §26.3 · `stack_order` §5 · `startsWith` §3 · `stdout / stderr capture` §24.6 · `Subfolders as conditions` §13 · `Sweep output paths (sweep_<sha>/)` §23.3

### T
`Table.applyMacro` §9 · `Table.create` §9, §13 · `Table.deleteRows` §9 · `Table.getColumn` §9 · `Table.save` §9, §13 · `Table.set` §9, §13 · `Table.setColumn` §9 · `Table.update` §9, §13 · `TCP batch command` §14, §31 · `TCP chunk approach` §14, §31 · `Timeout per file` §19.4 · `Timeout tracking` §11 · `toLowerCase` §3, §13

### U
`--update flag (CI plugin install)` §24.3 · `updateResults` §9, §13 · `use_virtual_stack` §5, §8, §15 · `Utility functions` Appendix

### V
`Validation (post-batch)` §30 · `Virtual stacks` §5, §8 · `view (Hyperstack / Standard ImageJ / Image5D / View5D)` §5 · `Visual sampling (1%)` §30.3

### W
`Watershed` §13 · `Watch-folder` §26 · `watchdog (Python)` §26.2 · `Wildcard close` §4 · `while (nImages > 0) close();` §4, §8, §11 · `Windows path quirks` §24.1, §29.7

### X
`xargs` §25.2 · `xvfb-run` §24.5

### Z
`Z Project` §13

---

## §2 Quick Start

```javascript
dir = "/path/to/images/";
output = "/path/to/results/";
File.makeDirectory(output);
list = getFileList(dir);
setBatchMode(true);

for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif")) {
        showProgress(i, list.length);
        open(dir + list[i]);
        setAutoThreshold("Otsu");
        run("Convert to Mask");
        run("Analyze Particles...", "size=50-Infinity summarize add");
        saveAs("Tiff", output + "mask_" + list[i]);
        close();
    }
}

setBatchMode(false);
saveAs("Results", output + "results.csv");
```

| Principle | Why |
|-----------|-----|
| `setBatchMode(true)` | 10-100x faster, no window flicker |
| `close()` after processing | Prevents out-of-memory |
| Filter by extension | Skips .DS_Store, Thumbs.db, folders |
| `showProgress()` | Agent can track completion via Log |
| Save results periodically | Protects against crashes |

---

## §3 Folder Iteration Patterns

### §3.1 File listing and filtering

```javascript
dir = "/path/to/folder/";
list = getFileList(dir);
Array.sort(list);  // alphabetical sort

// getFileList() returns NAMES only — prepend dir for full path
// Subdirectory names end with "/"
// ImageJ accepts "/" on all platforms including Windows
```

### §3.2 Extension and file validation

```javascript
function isValidImageFile(name) {
    if (startsWith(name, ".") || startsWith(name, "._")) return false;
    if (name == "Thumbs.db" || name == "desktop.ini") return false;
    if (endsWith(name, "/")) return false;
    lname = toLowerCase(name);
    exts = newArray(".tif",".tiff",".nd2",".lif",".czi",".oib",".oif",
                    ".lsm",".ims",".ome.tif",".ome.tiff",".png",".jpg",".jpeg");
    for (e = 0; e < exts.length; e++) {
        if (endsWith(lname, exts[e])) return true;
    }
    return false;
}
```

### §3.3 Recursive traversal

```javascript
count = 0;
countFiles(dir);
n = 0;

function countFiles(dir) {
    list = getFileList(dir);
    for (i = 0; i < list.length; i++) {
        if (endsWith(list[i], "/")) countFiles(dir + list[i]);
        else count++;
    }
}

function processFiles(dir) {
    list = getFileList(dir);
    for (i = 0; i < list.length; i++) {
        if (endsWith(list[i], "/")) processFiles(dir + list[i]);
        else {
            showProgress(n++, count);
            if (isValidImageFile(list[i])) processFile(dir + list[i]);
        }
    }
}
```

### §3.4 File name parts

| Function | Example input | Returns |
|----------|--------------|---------|
| `File.getName(path)` | `/data/exp/s_DAPI_001.tif` | `s_DAPI_001.tif` |
| `File.getNameWithoutExtension(path)` | same | `s_DAPI_001` |
| `File.getDirectory(path)` | same | `/data/exp/` |
| `split(name, "_")` | `s_DAPI_001` | `["s","DAPI","001"]` |

### §3.5 Matched pairs

```javascript
// Naming convention: sample001_DAPI.tif / sample001_GFP.tif
for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], "_DAPI.tif")) {
        base = replace(list[i], "_DAPI.tif", "");
        gfpFile = dir + base + "_GFP.tif";
        if (File.exists(gfpFile)) {
            open(dir + list[i]); rename("DAPI");
            open(gfpFile); rename("GFP");
            // Process pair...
            close("*");
        }
    }
}
```

### §3.6 Spaces in paths

Bio-Formats REQUIRES square brackets: `open=[/path/with spaces/file.nd2]`
Plain `open()` and `saveAs()` handle spaces natively without brackets.

---

## §4 setBatchMode

| Mode | Effect |
|------|--------|
| `setBatchMode(true)` | Images in memory only, no display (standard) |
| `setBatchMode("hide")` | Same as true (since 1.48f) |
| `setBatchMode("show")` | Display current image, stay in batch mode |
| `setBatchMode("exit and display")` | Show all hidden images |
| `setBatchMode(false)` | Exit, show last active image |
| `is("Batch Mode")` | Check if currently in batch mode |

**When NOT to use**: `waitForUser()`, manual ROI selection, debugging, 3D Viewer/3Dscript, plugins requiring visible windows.

**Critical**: batch mode does NOT reduce pixel memory -- only saves display buffer overhead. You MUST `close()` images after processing or they accumulate invisibly.

```javascript
// Track IDs when working with multiple intermediate images
setBatchMode(true);
open(dir + list[i]);
id = getImageID();
run("Duplicate...", "title=working");
run("Measure");
close();           // closes "working"
selectImage(id);   // switch back to original
close();
```

### §4.1 Closing images

```javascript
close();           // active image
close("mask");     // by name
close("working_*");// wildcard
close("*");        // all images
close("\\Others"); // all except active
while (nImages > 0) { close(); }  // guaranteed cleanup
```

---

## §5 Bio-Formats Importer -- Complete Reference

### §5.1 Basic syntax

```javascript
run("Bio-Formats Importer",
    "open=[/path/to/file.nd2] autoscale color_mode=Default " +
    "view=Hyperstack stack_order=XYCZT");
```

Path MUST be wrapped in square brackets: `open=[path]`

### §5.2 Display options

| Parameter | Values | Default | Description |
|-----------|--------|---------|-------------|
| `autoscale` | (flag) | off | Auto-adjust brightness/contrast |
| `color_mode` | `Default`, `Composite`, `Colorized`, `Grayscale`, `Custom` | `Default` | Channel display mode |
| `view` | `Hyperstack`, `Standard ImageJ`, `[Image5D]`, `[View5D]` | `Hyperstack` | Window type |
| `stack_order` | `XYCZT`, `XYTZC`, `XYZCT`, `XYTCZ`, `XYZTC`, `XYCTZ`, `Default` | `Default` | Dimension ordering |

**color_mode**: `Default` = composite for multi-channel, grayscale for single. `Composite` = all channels overlaid. `Colorized` = each channel in own window. `Grayscale` = all channels grayscale.

**view**: `Hyperstack` (recommended) = single window with C/Z/T sliders. `Standard ImageJ` = flat stack.

### §5.3 Data handling

| Parameter | Description |
|-----------|-------------|
| `open_all_series` | Open every series in the file |
| `series_N` | Open specific series N (1-indexed) |
| `concatenate` | Join all series into one stack |
| `crop` | Enable crop on import (needs coordinates) |
| `virtual` or `use_virtual_stack` | Open as virtual stack (memory-saving) |
| `split_channels` | Each channel as separate image |
| `split_focal` | Each Z-plane as separate image |
| `split_timepoints` | Each timepoint as separate image |

### §5.4 Metadata

| Parameter | Description |
|-----------|-------------|
| `display_metadata` | Show OME metadata window |
| `display_ome-xml` | Show raw OME-XML |
| `display_rois` | Import ROIs from file |
| `rois_import` | `[ROI manager]` or `[Overlay]` |

### §5.5 Common import strings

```javascript
// Standard multi-channel Z-stack
run("Bio-Formats Importer",
    "open=[/path/to/file.nd2] autoscale color_mode=Composite " +
    "view=Hyperstack stack_order=XYCZT");

// Virtual stack (large files)
run("Bio-Formats Importer",
    "open=[/path/to/file.czi] color_mode=Default " +
    "view=Hyperstack stack_order=XYCZT use_virtual_stack");

// Split channels at import
run("Bio-Formats Importer",
    "open=[/path/to/file.lif] color_mode=Default " +
    "view=Hyperstack stack_order=XYCZT split_channels");

// Specific series (1-indexed)
run("Bio-Formats Importer",
    "open=[/path/to/file.lif] autoscale color_mode=Default " +
    "view=Hyperstack stack_order=XYCZT series_3");

// Windowless (uses previous settings, no dialog)
run("Bio-Formats Windowless Importer", "open=[/path/to/file.nd2]");

// Crop on import (suffix _1 = series 1)
run("Bio-Formats Importer",
    "open=[/path/to/file.nd2] color_mode=Default " +
    "view=Hyperstack stack_order=XYCZT crop " +
    "x_coordinate_1=100 y_coordinate_1=200 width_1=500 height_1=400");

// Import with ROIs
run("Bio-Formats Importer",
    "open=[/path/to/file.nd2] color_mode=Default " +
    "view=Hyperstack stack_order=XYCZT display_rois rois_import=[ROI manager]");
```

### §5.6 Virtual stacks

Load planes from disk on demand. Essential for large files.

| Limitation | Detail |
|------------|--------|
| Read-only | Cannot modify pixel data |
| Slower scrolling | Reads from disk each time |
| Some plugins refuse | Need editable data |
| No undo | No history maintained |

To make editable: `run("Duplicate...", "duplicate");` then close the virtual stack.

### §5.7 Bio-Formats Exporter

```javascript
run("Bio-Formats Exporter",
    "save=[/path/to/output.ome.tif] compression=LZW");
// Compression options: Uncompressed, LZW, JPEG, JPEG-2000, JPEG-2000 Lossy, zlib
// For files > 4 GB, use .ome.btf extension (BigTIFF)
```

---

## §6 Bio-Formats Macro Extensions

Query file metadata WITHOUT opening the image. Essential for batch -- peek at dimensions and series count before opening.

### §6.1 Initialisation

```javascript
run("Bio-Formats Macro Extensions");  // required once
Ext.setId("/path/to/file.nd2");       // set file to query
// ... query ...
Ext.close();                           // release file handle
```

### §6.2 Complete function reference

| Function | Returns | Description |
|----------|---------|-------------|
| `Ext.setId(path)` | -- | Open file for metadata |
| `Ext.close()` | -- | Release file handle |
| `Ext.getSeriesCount(var)` | int | Number of series |
| `Ext.setSeries(n)` | -- | Select series (**0-indexed**) |
| `Ext.getSeriesName(var)` | string | Current series name |
| `Ext.getSizeX(var)` | int | Width in pixels |
| `Ext.getSizeY(var)` | int | Height in pixels |
| `Ext.getSizeZ(var)` | int | Z slices |
| `Ext.getSizeC(var)` | int | Channels |
| `Ext.getSizeT(var)` | int | Time points |
| `Ext.getImageCount(var)` | int | Total planes (Z*C*T) |
| `Ext.getPixelType(var)` | string | "uint8", "uint16", "float" etc. |
| `Ext.isLittleEndian(var)` | bool | Byte order |
| `Ext.isRGB(var)` | bool | Whether RGB |
| `Ext.isInterleaved(var)` | bool | Channel interleaving |
| `Ext.isIndexed(var)` | bool | Palette-based |
| `Ext.getImageCreationDate(var)` | string | Acquisition timestamp |
| `Ext.getPlaneTimingDeltaT(var, plane)` | float | Seconds since start |
| `Ext.getPlaneTimingExposureTime(var, plane)` | float | Exposure time (s) |
| `Ext.getPlanePositionX(var, plane)` | float | Stage X |
| `Ext.getPlanePositionY(var, plane)` | float | Stage Y |
| `Ext.getPlanePositionZ(var, plane)` | float | Focus Z |
| `Ext.getMetadataValue(key, var)` | string | Arbitrary metadata |
| `Ext.openImage(title, plane)` | -- | Open single plane |
| `Ext.openImagePlus(path)` | -- | Open file as ImagePlus |

**Key reminders**:
- `Ext.setSeries()` is **0-indexed**; `series_N` in import string is **1-indexed**
- Always `Ext.close()` when done to release file handles
- NaN check for timing: `if (deltaT == deltaT)` (NaN != NaN)

### §6.3 Inspect and filter series

```javascript
run("Bio-Formats Macro Extensions");
path = "/path/to/experiment.lif";
Ext.setId(path);
Ext.getSeriesCount(nSeries);

setBatchMode(true);
for (s = 0; s < nSeries; s++) {
    Ext.setSeries(s);
    Ext.getSizeZ(z); Ext.getSizeC(c);
    Ext.getSeriesName(name);
    if (z > 1 && c >= 2) {  // only Z-stacks with 2+ channels
        sNum = s + 1;
        run("Bio-Formats Importer",
            "open=[" + path + "] autoscale color_mode=Composite " +
            "view=Hyperstack stack_order=XYCZT series_" + sNum);
        // ... process ...
        close();
    }
}
Ext.close();
setBatchMode(false);
```

---

## §7 File Format Patterns

| Format | Extension | Multi-series | Typical series = | Notes |
|--------|-----------|-------------|-----------------|-------|
| TIFF | .tif, .tiff | No | N/A | `open()` works directly; Bio-Formats for OME metadata |
| OME-TIFF | .ome.tif | Possible | Image | Open standard, preserves metadata |
| Nikon | .nd2 | Yes | Position/FOV | Channel order = acquisition order |
| Leica | .lif | Yes | Experiment | Container; series names include tree path |
| Zeiss | .czi | Yes | Scene | Tiles may be separate series |
| Zeiss | .lsm | Rare | Tile | Legacy format |
| Olympus | .oib/.oif | Rare | Area | .oif needs companion folder intact |
| Imaris | .ims | Yes | Resolution level | HDF5, consider virtual stacks |
| MicroManager | .ome.tif | No | N/A | Open-source microscopy |

```javascript
// TIFF sequence as stack
run("Image Sequence...", "open=[/path/to/folder/] sort use");
// With filter: file=ch1; with range: number=41 starting=10; virtual for large

// Format-agnostic open (Bio-Formats auto-detects)
run("Bio-Formats Importer",
    "open=[" + path + "] autoscale color_mode=Default " +
    "view=Hyperstack stack_order=XYCZT");
```

**When to use `open()` vs Bio-Formats**: `open()` is faster for simple TIFFs. Use Bio-Formats when you need metadata fidelity, multi-channel/Z handling, or non-TIFF formats.

---

## §8 Memory Management

### §8.1 Memory per image

| Bit depth | Formula |
|-----------|---------|
| 8-bit | W x H x Z bytes |
| 16-bit | W x H x Z x 2 bytes |
| 32-bit / RGB | W x H x Z x 4 bytes |

Example: 2048 x 2048 x 50 x 16-bit ~ 400 MB per image.

### §8.2 Estimating before opening

```javascript
run("Bio-Formats Macro Extensions");
Ext.setId(path);
Ext.getSizeX(w); Ext.getSizeY(h); Ext.getSizeZ(z); Ext.getSizeC(c);
Ext.getPixelType(ptype);
bpp = 2;  // default 16-bit
if (ptype == "uint8" || ptype == "int8") bpp = 1;
if (ptype == "float" || ptype == "int32" || ptype == "uint32") bpp = 4;
sizeMB = (w * h * z * c * bpp) / (1024 * 1024);
Ext.close();
```

### §8.3 Memory-efficient batch template

```javascript
dir = "/path/to/images/";
output = "/path/to/output/";
list = getFileList(dir);
setBatchMode(true);

for (i = 0; i < list.length; i++) {
    if (!isValidImageFile(list[i])) continue;
    showProgress(i, list.length);

    run("Bio-Formats Importer",
        "open=[" + dir + list[i] + "] autoscale color_mode=Default " +
        "view=Hyperstack stack_order=XYCZT");

    // ... process ...

    // Save checkpoint periodically
    if ((i + 1) % 10 == 0) {
        selectWindow("Results");
        saveAs("Text", output + "results_checkpoint.csv");
    }

    while (nImages > 0) { close(); }  // clean up ALL images
    if (i % 5 == 0) run("Collect Garbage");
}
setBatchMode(false);
```

### §8.4 Memory tips

| Strategy | When to use |
|----------|-------------|
| `run("Collect Garbage")` every ~5-10 images | Long batches |
| Virtual stacks | Files > ~500 MB |
| Save & clear Results when > ~5000 rows | Many measurements |
| Process one series at a time from .lif/.nd2 | Multi-series files |
| Fiji memory: consider ~75% of system RAM | Edit > Options > Memory & Threads |
| Convert .nd2/.lif to TIFF first | Repeated analysis of same files |

```javascript
// Check current memory
print("Max: " + IJ.maxMemory()/(1024*1024) + " MB");
print("Used: " + IJ.currentMemory()/(1024*1024) + " MB");
```

---

## §9 Results Table Management

### §9.1 Accumulate across images

```javascript
run("Clear Results");
run("Set Measurements...",
    "area mean integrated display redirect=None decimal=3");
setBatchMode(true);

for (i = 0; i < list.length; i++) {
    if (!endsWith(list[i], ".tif")) continue;
    open(dir + list[i]);
    startRow = nResults;
    run("Measure");
    // Tag rows with source filename
    for (row = startRow; row < nResults; row++) {
        setResult("Filename", row, list[i]);
    }
    updateResults();
    close();
}
setBatchMode(false);
saveAs("Results", output + "results.csv");
```

### §9.2 Reading results programmatically

```javascript
area = getResult("Area", 0);        // specific cell
n = nResults;                        // row count
label = getResultString("Label", 0); // string column

// All values into array + stats
areas = newArray(nResults);
for (row = 0; row < nResults; row++) areas[row] = getResult("Area", row);
Array.getStatistics(areas, min, max, mean, stdDev);
```

### §9.3 Custom tables (Table.* functions, since 1.52a)

```javascript
Table.create("Summary");
Table.set("Image", 0, "sample1.tif");
Table.set("Cell Count", 0, 42);
Table.update;
Table.save(output + "summary.csv");

// Useful operations
counts = Table.getColumn("Cell Count");
Table.setColumn("Image", namesArray);
Table.deleteRows(0, 2);
Table.applyMacro("Density = Cell_Count / Mean_Area");  // computed column
// Note: use underscores for column names with spaces in applyMacro
```

---

## §10 ROI Manager in Batch

### §10.1 Core operations

| Command | Effect |
|---------|--------|
| `roiManager("Reset")` | Clear all ROIs |
| `roiManager("Add")` | Add current selection |
| `roiManager("Count")` | Number of ROIs |
| `roiManager("Select", n)` | Select ROI (0-indexed) |
| `roiManager("Measure")` | Measure all ROIs |
| `roiManager("Multi Measure")` | Measure all ROIs on all slices |
| `roiManager("Save", path)` | Save as .zip (all) or .roi (single) |
| `roiManager("Open", path)` | Load ROIs from file |
| `roiManager("Show All with labels")` | Display all ROIs |

### §10.2 Batch pattern: save ROIs per image

```javascript
setBatchMode(true);
for (i = 0; i < list.length; i++) {
    if (!endsWith(list[i], ".tif")) continue;
    open(dir + list[i]);
    nameNoExt = File.getNameWithoutExtension(list[i]);
    roiManager("Reset");
    setAutoThreshold("Otsu");
    run("Convert to Mask");
    run("Analyze Particles...", "size=50-Infinity add");
    if (roiManager("Count") > 0)
        roiManager("Save", roiDir + nameNoExt + "_rois.zip");
    close();
}
setBatchMode(false);
```

### §10.3 Reuse saved ROIs on different channel

```javascript
open(dir + list[i]);
roiManager("Reset");
roiManager("Open", roiDir + nameNoExt + "_rois.zip");
roiManager("Deselect");
roiManager("Measure");
close();
```

---

## §11 Error Handling

ImageJ macro has NO try/catch. Guard with precondition checks.

```javascript
function safeOpen(path) {
    if (!File.exists(path)) { print("NOT FOUND: " + path); return false; }
    if (File.length(path) == 0) { print("EMPTY: " + path); return false; }
    nBefore = nImages;
    run("Bio-Formats Importer",
        "open=[" + path + "] autoscale color_mode=Default " +
        "view=Hyperstack stack_order=XYCZT");
    if (nImages <= nBefore) { print("OPEN FAILED: " + path); return false; }
    return true;
}
```

### §11.1 Skip-and-continue with error logging

```javascript
errorLog = "";
successCount = 0;
setBatchMode(true);

for (i = 0; i < list.length; i++) {
    if (!isValidImageFile(list[i])) continue;
    if (!safeOpen(dir + list[i])) { errorLog += "FAIL: " + list[i] + "\n"; continue; }

    // Process...
    successCount++;
    close("*");
}

setBatchMode(false);
if (lengthOf(errorLog) > 0) File.saveString(errorLog, output + "error_log.txt");
print("Success: " + successCount);
```

### §11.2 Timeout tracking

```javascript
startTime = getTime();
maxTimeMs = 300000;  // 5 minutes
for (i = 0; i < list.length; i++) {
    if (getTime() - startTime > maxTimeMs) {
        print("TIMEOUT after " + i + " files"); break;
    }
    // ... process ...
}
```

---

## §12 Progress & Logging

```javascript
showProgress(i, n);                              // progress bar
showStatus("Processing image 5 of 100...");      // status bar
print("Found " + nResults + " cells");           // Log window
logContent = getInfo("log");                      // read Log contents
print("\\Clear");                                // clear Log

// Time estimation
startTime = getTime();
// ... in loop:
elapsed = (getTime() - startTime) / 1000;
perImage = elapsed / (i + 1);
remaining = perImage * (list.length - i - 1);
print("[" + (i+1) + "/" + list.length + "] ~" + d2s(remaining, 0) + "s left");

// Machine-parseable for agent
print("BATCH_PROGRESS|" + (i+1) + "|" + list.length + "|" + list[i]);
```

---

## §13 Batch Templates

### §13.1 Template 1: Threshold + Analyze Particles + save outlines

```javascript
dir = "/path/to/input/";
output = "/path/to/output/";
File.makeDirectory(output);
File.makeDirectory(output + "masks/");
list = getFileList(dir);
run("Set Measurements...",
    "area mean standard min integrated shape display redirect=None decimal=3");
run("Clear Results");
setBatchMode(true);

for (i = 0; i < list.length; i++) {
    if (!endsWith(toLowerCase(list[i]), ".tif")) continue;
    showProgress(i, list.length);
    open(dir + list[i]);
    nameNoExt = File.getNameWithoutExtension(list[i]);

    run("Duplicate...", "title=working");
    run("Gaussian Blur...", "sigma=1");
    // Choose threshold: consider Otsu for bimodal, Triangle for skewed, Li for dim signals
    setAutoThreshold("Otsu dark");
    run("Convert to Mask");
    run("Fill Holes");
    run("Watershed");

    saveAs("Tiff", output + "masks/" + nameNoExt + "_mask.tif");
    startRow = nResults;
    // Adjust size range based on your objects; circularity 0.3-1.0 excludes elongated debris
    run("Analyze Particles...",
        "size=50-Infinity circularity=0.3-1.0 show=Outlines display exclude");
    for (row = startRow; row < nResults; row++) setResult("Source", row, list[i]);
    updateResults();
    close("*");
}

setBatchMode(false);
saveAs("Results", output + "particle_results.csv");
```

### §13.2 Template 2: Multi-channel split + per-channel measurement

```javascript
dir = "/path/to/input/";
output = "/path/to/output/";
File.makeDirectory(output);
list = getFileList(dir);
Table.create("Channel_Results");
row = 0;
run("Set Measurements...",
    "area mean standard min integrated display redirect=None decimal=3");
setBatchMode(true);

for (i = 0; i < list.length; i++) {
    if (!isValidImageFile(list[i])) continue;
    nameNoExt = File.getNameWithoutExtension(list[i]);
    run("Bio-Formats Importer",
        "open=[" + dir + list[i] + "] autoscale color_mode=Default " +
        "view=Hyperstack stack_order=XYCZT");
    title = getTitle();
    getDimensions(w, h, channels, slices, frames);
    if (channels < 2) { close(); continue; }

    run("Split Channels");
    for (c = 1; c <= channels; c++) {
        selectWindow("C" + c + "-" + title);
        run("Clear Results");
        if (slices > 1) run("Z Project...", "projection=[Max Intensity]");
        run("Measure");
        selectWindow("Channel_Results");
        Table.set("Image", row, nameNoExt);
        Table.set("Channel", row, c);
        Table.set("Mean", row, getResult("Mean", 0));
        Table.set("IntDen", row, getResult("IntDen", 0));
        Table.update;
        row++;
    }
    close("*");
    run("Collect Garbage");
}

setBatchMode(false);
selectWindow("Channel_Results");
Table.save(output + "channel_results.csv");
```

### §13.3 Template 3: Multi-series .lif iteration

```javascript
lifPath = "/path/to/experiment.lif";
output = "/path/to/output/";
File.makeDirectory(output);

run("Bio-Formats Macro Extensions");
Ext.setId(lifPath);
Ext.getSeriesCount(nSeries);
run("Clear Results");
setBatchMode(true);

for (s = 0; s < nSeries; s++) {
    showProgress(s, nSeries);
    Ext.setSeries(s);
    Ext.getSeriesName(seriesName);
    Ext.getSizeX(w); Ext.getSizeY(h); Ext.getSizeZ(z);

    // Skip overview/thumbnails (typically small)
    if (w < 256 || h < 256) continue;

    sNum = s + 1;  // import is 1-indexed
    run("Bio-Formats Importer",
        "open=[" + lifPath + "] autoscale color_mode=Default " +
        "view=Hyperstack stack_order=XYCZT series_" + sNum);

    safeName = replace(replace(replace(seriesName, "/", "_"), " ", "_"), ":", "_");
    if (z > 1) run("Z Project...", "projection=[Max Intensity]");

    startRow = nResults;
    run("Measure");
    for (row = startRow; row < nResults; row++) setResult("Series", row, seriesName);
    updateResults();

    saveAs("Tiff", output + safeName + ".tif");
    close("*");
    if (s % 5 == 0) run("Collect Garbage");
}

Ext.close();
setBatchMode(false);
saveAs("Results", output + "lif_results.csv");
```

### §13.4 Template 4: Matched pairs (segment DAPI, measure marker)

```javascript
dir = "/path/to/images/";
output = "/path/to/output/";
File.makeDirectory(output);
list = getFileList(dir);
run("Set Measurements...",
    "area mean standard integrated display redirect=None decimal=3");
run("Clear Results");
setBatchMode(true);

for (i = 0; i < list.length; i++) {
    if (!endsWith(list[i], "_DAPI.tif")) continue;
    base = replace(list[i], "_DAPI.tif", "");
    gfpPath = dir + base + "_GFP.tif";
    if (!File.exists(gfpPath)) { print("No match: " + base); continue; }

    // Segment on DAPI
    open(dir + list[i]); rename("DAPI");
    run("Gaussian Blur...", "sigma=2");
    setAutoThreshold("Otsu dark");
    run("Convert to Mask");
    run("Watershed");
    roiManager("Reset");
    run("Analyze Particles...", "size=50-Infinity circularity=0.5-1.0 add");
    if (roiManager("Count") == 0) { close("*"); continue; }

    // Measure GFP in nuclear ROIs
    open(gfpPath); rename("GFP");
    run("Set Measurements...",
        "area mean standard integrated display redirect=GFP decimal=3");
    startRow = nResults;
    roiManager("Deselect");
    roiManager("Measure");
    for (row = startRow; row < nResults; row++) setResult("Sample", row, base);
    updateResults();
    roiManager("Save", output + base + "_nuclei.zip");
    close("*");
}

setBatchMode(false);
saveAs("Results", output + "paired_results.csv");
```

### §13.5 Template 5: Condition-based (subfolders = groups)

```javascript
// Structure: input/Control/, input/Drug_A/, input/Drug_B/
baseDir = "/path/to/input/";
output = "/path/to/output/";
File.makeDirectory(output);
conditions = getFileList(baseDir);
Table.create("Summary");
summaryRow = 0;
setBatchMode(true);

for (c = 0; c < conditions.length; c++) {
    if (!endsWith(conditions[c], "/")) continue;
    condition = replace(conditions[c], "/", "");
    files = getFileList(baseDir + conditions[c]);
    condAreas = newArray(0);

    for (f = 0; f < files.length; f++) {
        if (!endsWith(toLowerCase(files[f]), ".tif")) continue;
        open(baseDir + conditions[c] + files[f]);
        run("Clear Results");
        setAutoThreshold("Otsu dark");
        run("Analyze Particles...", "size=50-Infinity display");
        for (r = 0; r < nResults; r++)
            condAreas = Array.concat(condAreas, getResult("Area", r));
        close();
    }

    if (condAreas.length > 0) Array.getStatistics(condAreas, aMin, aMax, aMean, aStd);
    else { aMean = 0; aStd = 0; }
    selectWindow("Summary");
    Table.set("Condition", summaryRow, condition);
    Table.set("N_cells", summaryRow, condAreas.length);
    Table.set("Mean_Area", summaryRow, aMean);
    Table.set("SD_Area", summaryRow, aStd);
    Table.update;
    summaryRow++;
}

setBatchMode(false);
selectWindow("Summary");
Table.save(output + "condition_summary.csv");
```

---

## §14 Agent-Specific Patterns

### §14.1 Agent batch workflow

```
1. python ij.py state               — check what's open
2. Send macro to getFileList/print   — discover files
3. python ij.py log                  — read file list
4. Open one file, check dims/channels
5. Test workflow on single image
6. python ij.py capture              — verify result visually
7. Run full batch macro
8. python ij.py results              — retrieve measurements
9. python auditor.py                 — validate measurements
```

### §14.2 TCP timeout strategies

For long batches, either increase socket timeout or break into chunks:

```python
# Chunk approach
chunk_size = 10
for start in range(0, len(file_list), chunk_size):
    chunk = file_list[start:start + chunk_size]
    chunk_macro = generate_batch_macro(chunk)
    result = subprocess.run(
        ["python", "ij.py", "macro", chunk_macro],
        capture_output=True, text=True, timeout=120
    )
```

### §14.3 Batch TCP command

```python
batch_cmd = {
    "command": "batch",
    "commands": [
        {"command": "execute_macro", "code": 'open("img1.tif"); run("Measure"); close();'},
        {"command": "execute_macro", "code": 'open("img2.tif"); run("Measure"); close();'},
        {"command": "get_results_table"}
    ]
}
```

---

## §15 Common Problems & Solutions

| Problem | Cause | Solution |
|---------|-------|---------|
| Out of memory | Missing `close()`, intermediates accumulate | `while (nImages > 0) { close(); }` + `run("Collect Garbage")` every ~10 images (§4, §8) |
| Hidden/junk files processed | .DS_Store, Thumbs.db | Use `isValidImageFile()` filter function (§3) |
| Results table slow | Too many rows | Save & clear when `nResults > 5000` (§9) |
| Image title conflicts | Duplicate names | Use `getImageID()` + `selectImage(id)` instead of `selectWindow()` (§4) |
| Inconsistent dimensions | Mixed image sizes | Check dims on first image, warn on mismatch |
| Bio-Formats dialog blocks batch | Series selector appears | Specify `series_N` explicitly, or use Windowless Importer (§5) |
| Slow performance | Display updates, large files, network drive | `setBatchMode(true)`, virtual stacks, copy to local, convert to TIFF first (§4, §5) |
| Macro aborts on one bad file | No try/catch | Use `safeOpen()` guard function (§11) |
| Series indexing confusion | `Ext.setSeries(0)` vs `series_1` | Extensions 0-indexed, import string 1-indexed: `sNum = s + 1` (§6) |
| Headless Bio-Formats fails | Requires AWT classes | Use `bfconvert` CLI tool instead |

### §15.1 Large multi-series memory strategy

```javascript
// Check size before opening each series
Ext.setSeries(s);
Ext.getSizeX(w); Ext.getSizeY(h); Ext.getSizeZ(z); Ext.getSizeC(c);
Ext.getPixelType(ptype);
bpp = 2;
if (ptype == "uint8") bpp = 1;
sizeMB = (w * h * z * c * bpp) / (1024 * 1024);
freeMB = (IJ.maxMemory() - IJ.currentMemory()) / (1024 * 1024);

importStr = "open=[" + path + "] color_mode=Default view=Hyperstack stack_order=XYCZT series_" + (s+1);
if (sizeMB > freeMB * 0.8) importStr += " use_virtual_stack";
run("Bio-Formats Importer", importStr);
```

### §15.2 Skip already-processed files

```javascript
outFile = output + "result_" + list[i];
if (File.exists(outFile)) continue;  // already processed
```

### §15.3 Dynamic Bio-Formats import string

```javascript
function buildImportString(path, series, virtualStack, splitChannels) {
    str = "open=[" + path + "] autoscale color_mode=Default view=Hyperstack stack_order=XYCZT";
    if (series > 0) str += " series_" + series;
    if (virtualStack) str += " use_virtual_stack";
    if (splitChannels) str += " split_channels";
    return str;
}
```

---

## §16 Appendix: Utility Functions

```javascript
function isValidImageFile(name) {
    if (startsWith(name, ".") || startsWith(name, "._")) return false;
    if (name == "Thumbs.db" || name == "desktop.ini") return false;
    if (endsWith(name, "/")) return false;
    lname = toLowerCase(name);
    exts = newArray(".tif",".tiff",".nd2",".lif",".czi",".oib",".oif",
                    ".lsm",".ims",".ome.tif",".ome.tiff",".png",".jpg",".jpeg");
    for (e = 0; e < exts.length; e++) {
        if (endsWith(lname, exts[e])) return true;
    }
    return false;
}

function safeOpen(path) {
    if (!File.exists(path)) { print("NOT FOUND: " + path); return false; }
    if (File.length(path) == 0) { print("EMPTY: " + path); return false; }
    nBefore = nImages;
    run("Bio-Formats Importer",
        "open=[" + path + "] autoscale color_mode=Default " +
        "view=Hyperstack stack_order=XYCZT");
    if (nImages <= nBefore) { print("OPEN FAILED: " + path); return false; }
    return true;
}

function closeAll() { while (nImages > 0) close(); }

function checkMemoryMB() {
    return (IJ.maxMemory() - IJ.currentMemory()) / (1024 * 1024);
}

function sanitiseFilename(name) {
    chars = newArray("/","\\", " ",":","*","?","<",">","|");
    for (c = 0; c < chars.length; c++) name = replace(name, chars[c], "_");
    return name;
}
```

---

## §17 Appendix: Batch Decision Tree

```
Single multi-series file (.lif, .nd2)?
  YES -> Iterate series with Bio-Formats Extensions (Template 3)
  NO  -> Files in flat folder?
    YES -> Simple loop (Template 1)
    NO  -> Subfolders = conditions?
      YES -> Condition-based (Template 5)
      NO  -> Recursive traversal (§3)

Files paired (DAPI + marker)?  -> Template 4
Need to compare methods?       -> Parameter sweep (§23)
Z-stack data?                  -> Project first, then analyse
Files very large (>500 MB)?    -> Virtual stacks, one at a time, garbage collect
Whole experiment / unattended? -> Robust pipeline (§18-§28)
```

§2-§17 cover the "happy path" of a one-shot batch that fits in a single
Fiji session. §18 onwards covers what changes when the batch must run
unattended for hours, survive partial failure, recover after a crash, be
re-runnable, and leave behind a complete provenance trail. Read §18-§22
for the architecture, §24-§26 for execution, §28 for copy-paste templates,
and §29-§30 for what to check when it goes wrong.

---

## §18 Robust Pipeline Architecture

A batch that just iterates files (§2) is fine for an interactive 10-minute
job. A batch that processes a whole experiment overnight needs four
properties that the simple loop does not give you: idempotency,
resumability, atomic writes, and a manifest. Each is independently useful
and they compose; together they let you run, kill, restart, and re-run a
pipeline without losing work or corrupting outputs.

### §18.1 Idempotent processing

A pipeline is **idempotent** if running it twice with the same inputs and
parameters produces the same outputs (bit-identical or
within-numerical-tolerance, depending on the algorithm). Idempotency is
the property that makes resumability and re-runs safe.

Three things break idempotency:

| Anti-pattern | Why it breaks idempotency | Fix |
|---|---|---|
| Reading the system clock into output filenames | Same input → different output paths each run | Hash inputs + params, or accept user-supplied run id |
| Random seeds left unset | StarDist / Cellpose / shuffle-based ops vary | `Random.setSeed(seed)`; pass seed in params |
| Mutable shared state (e.g. `Random` between iterations) | File N depends on file N-1 | Reset state at start of each iteration |
| Plugins that learn / remember (Trainable Weka without saved model) | First run trains, second run reuses cache | Save model artifact, point at it explicitly |
| `setAutoThreshold("Otsu")` on whole batch when each image varies | Threshold drift if order changes | Use fixed threshold or per-image threshold |

```javascript
// Idempotent: deterministic seed, fixed threshold, hash-derived output
seed = 42;
threshold = 1500;          // pre-determined from a calibration step
inputHash = "abc123";      // sha256 of input file (truncated)

outputBase = output + inputHash + "/";
File.makeDirectory(outputBase);
// ... process with threshold and seed ...
```

When you cannot make pixel output bit-identical (e.g. GPU floating-point
non-determinism, parallel reduction order), you can still make
**deterministic identifiers**: the manifest row, the output path, and the
log line are all reproducible from the inputs even if the pixel values
drift in the last bit. That is enough for resumability.

### §18.2 Resumable runs (checkpointing)

A resumable pipeline can be killed at any moment and resumed from where
it stopped without redoing completed work and without writing duplicates.

Three skip strategies, weakest to strongest:

```javascript
// 1. Output-exists check (cheap, brittle: file may be half-written)
if (File.exists(outputPath)) continue;

// 2. Output-exists + non-empty (catches truncated files)
if (File.exists(outputPath) && File.length(outputPath) > 1024) continue;

// 3. Output-exists + sentinel file (most robust, see §18.3 for atomic write)
if (File.exists(outputPath + ".done")) continue;
```

The third pattern is the only one that survives `kill -9` mid-write. The
sentinel `.done` is created **after** the output file is fully written and
fsync'd. If the process dies between writing the output and writing the
sentinel, the resume logic will re-process the file (re-creating the
output) and write the sentinel on the second run.

A more robust resumability pattern uses a **state CSV** that the orchestrator
appends to atomically:

```
input_path,input_sha256,status,output_path,duration_s,timestamp_utc
/data/in/a.tif,d2a4...,DONE,/data/out/a_mask.tif,4.21,2026-05-01T12:00:00Z
/data/in/b.tif,9f1e...,DONE,/data/out/b_mask.tif,3.98,2026-05-01T12:00:04Z
/data/in/c.tif,71bc...,FAIL,,12.0,2026-05-01T12:00:16Z
```

On resume, read the CSV and skip every row marked `DONE`. Rows marked
`FAIL` should be retried in case the failure was transient (§19.3) — the
manifest tells you so explicitly, separately from "never seen".

### §18.3 Atomic file writes

The cardinal sin of batch processing is leaving a **half-written output**
that looks like a complete one. Atomic write means writing to a temp path
in the same filesystem, fsync'ing, then renaming over the final path. The
rename is atomic on POSIX and on NTFS for same-volume moves, so a reader
either sees the old file (or no file) or the complete new file — never an
intermediate state.

```javascript
// Macro pattern: write to .tmp, then rename
tmpPath = outputPath + ".tmp";
saveAs("Tiff", tmpPath);
ok = File.rename(tmpPath, outputPath);
if (!ok) {
    // Fallback: copy + delete (different volume)
    File.copy(tmpPath, outputPath);
    File.delete(tmpPath);
}
// Sentinel for resume
File.saveString("ok", outputPath + ".done");
```

```python
# Python pattern: tempfile + os.replace (atomic across platforms when same FS)
import os, tempfile, shutil
fd, tmp = tempfile.mkstemp(dir=os.path.dirname(outputPath), suffix=".tmp")
os.close(fd)
try:
    save_image(tmp, data)            # actually write
    with open(tmp, "rb+") as f:
        os.fsync(f.fileno())          # force to disk
    os.replace(tmp, outputPath)       # atomic on POSIX + NTFS-same-volume
finally:
    if os.path.exists(tmp):
        os.unlink(tmp)
```

Same-volume requirement: `File.rename` and `os.replace` are atomic only
when source and destination are on the same filesystem. Writing to
`/tmp/.../foo.tif.tmp` and renaming to `/data/.../foo.tif` is **not
atomic** if `/tmp` and `/data` are different mounts — Python silently
falls back to copy+unlink, which is not atomic. Always create the temp
file in the **same directory** as the destination.

Bio-Formats Exporter respects atomic writes if you point it at a `.tmp`
suffix:

```javascript
run("Bio-Formats Exporter", "save=[" + outputPath + ".tmp] compression=LZW");
File.rename(outputPath + ".tmp", outputPath);
```

### §18.4 Manifest-driven execution

A **manifest** is a single CSV or YAML file that lists every input, every
parameter, and every expected output path. The pipeline reads the
manifest and processes its rows. The manifest is the contract between
"which files exist on disk" and "which files this run cares about".

Why a manifest beats `getFileList(dir)`:

- Slicing the input set (process rows 100–200) becomes a simple
  `head/tail`. With a folder iterator you have to scan everything to
  filter.
- Per-row parameters are explicit and reviewable in version control.
- A re-run reads the same manifest and produces the same outputs.
- Failure log can be joined back to the manifest by row id.

Minimal manifest:

```csv
id,input_path,channel,sigma,threshold_method,output_dir
001,/data/in/a.lif,0,1.5,Otsu,/data/out/a_ch0/
002,/data/in/a.lif,1,1.5,Triangle,/data/out/a_ch1/
003,/data/in/b.czi,0,2.0,Otsu,/data/out/b_ch0/
```

A YAML form is more human-readable for nested params:

```yaml
- id: "001"
  input: /data/in/a.lif
  series: 3
  params:
    sigma: 1.5
    threshold: Otsu
    min_size: 50
  output_dir: /data/out/001/
```

Read the manifest with the macro `File.openAsString` + line split, with
Groovy `new File(path).readLines()`, or with Python `pandas.read_csv`. A
hybrid template lives in §28.1.

Auto-generate the manifest from a folder before the run rather than
maintaining it by hand:

```python
import csv, hashlib, glob, os
rows = []
for path in sorted(glob.glob("/data/in/**/*.tif", recursive=True)):
    with open(path, "rb") as f:
        h = hashlib.sha256()
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    rows.append({
        "id": os.path.basename(path),
        "input_path": path,
        "input_sha256": h.hexdigest()[:16],
        "sigma": 1.5,
        "threshold": "Otsu",
        "output_path": "/data/out/" + os.path.basename(path).replace(".tif", "_mask.tif"),
    })
with open("manifest.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=rows[0].keys())
    w.writeheader(); w.writerows(rows)
```

Bake the input hash into the manifest at generation time. From then on,
any pipeline step that reads the manifest can detect "input file replaced
since manifest generated" without re-hashing — just compare mtime to a
recorded baseline, or recompute the hash for a single file.

---

## §19 Error Handling Patterns

§11 covered the basics: macro has no try/catch, so guard with
preconditions and skip on failure. This section covers what to do AFTER
the file fails: where to put it, when to retry, and when to give up.

### §19.1 Per-file isolation

Every file is a transaction. A bad file must not abort the run, must not
leak windows or memory into the next file's processing, and must not
poison the Results table. The shape:

```javascript
function processOne(inputPath, params) {
    nBefore = nImages;
    nResultsBefore = nResults;
    startMs = getTime();
    status = "OK";
    errMsg = "";

    if (!safeOpen(inputPath)) { status = "OPEN_FAIL"; errMsg = "open failed"; }
    else {
        // ... per-file processing here ...
    }

    // Cleanup happens regardless of status
    while (nImages > nBefore) close();    // close only what we opened
    roiManager("Reset");

    durationMs = getTime() - startMs;
    return status + "|" + d2s(durationMs/1000, 2) + "|" + errMsg;
}
```

The Groovy equivalent gets real exceptions and `try/finally`:

```groovy
import ij.IJ
def processOne(inputPath, params) {
    def imp = null
    try {
        imp = IJ.openImage(inputPath)
        if (imp == null) return [status: "OPEN_FAIL", error: "openImage returned null"]
        // ... process ...
        return [status: "OK", error: ""]
    } catch (Throwable t) {
        return [status: "EXCEPTION", error: t.getClass().simpleName + ": " + t.message]
    } finally {
        if (imp != null) imp.close()
        IJ.run("Collect Garbage")
    }
}
```

### §19.2 Quarantine bin (failed/ tree)

Bad inputs go into a quarantine tree alongside the output, organised by
failure reason, with a JSON sidecar describing what went wrong. This
turns "I had 47 failures" from a useless number into a triagable list.

Layout:

```
outputs/
  failed/
    OPEN_FAIL/
      sample_023.tif
      sample_023.tif.error.json
    OOM/
      sample_104.lif
      sample_104.lif.error.json
    TIMEOUT/
      sample_180.czi
      sample_180.czi.error.json
```

The sidecar:

```json
{
  "input": "/data/in/sample_023.tif",
  "input_sha256": "d2a4...",
  "status": "OPEN_FAIL",
  "error": "Bio-Formats: cannot read file (truncated header)",
  "stack": "loci.formats.FormatException: ...",
  "duration_s": 0.42,
  "attempts": 1,
  "macro_path": "/runs/2026-05-01_12-00/script.ijm",
  "machine": "lab-ws-04",
  "fiji_version": "2.16.0/1.54k",
  "timestamp_utc": "2026-05-01T12:01:23Z"
}
```

```python
# Python helper to quarantine a file
import json, shutil, os, datetime
def quarantine(input_path, reason, error, run_meta):
    base = run_meta["output_root"] + "/failed/" + reason + "/"
    os.makedirs(base, exist_ok=True)
    shutil.copy2(input_path, base + os.path.basename(input_path))
    sidecar = base + os.path.basename(input_path) + ".error.json"
    with open(sidecar, "w") as f:
        json.dump({
            "input": input_path,
            "status": reason,
            "error": str(error),
            "timestamp_utc": datetime.datetime.utcnow().isoformat() + "Z",
            **run_meta,
        }, f, indent=2)
```

`shutil.copy2` not `move` — the original input file should never be
modified by the pipeline. Quarantine is for triage, not for relocation.

Reasons should be a small closed set so they form readable directory
names. A practical taxonomy:

| Reason | Meaning |
|---|---|
| `OPEN_FAIL` | Bio-Formats / `open` returned null or threw |
| `READ_FAIL` | File opened but pixel data unreadable |
| `EMPTY` | Zero-size or single-plane where multi-plane expected |
| `WRONG_DIMS` | Dimensions outside expected range |
| `OOM` | Out-of-memory during processing |
| `TIMEOUT` | Exceeded per-file time budget (§19.4) |
| `EXCEPTION` | Java/Groovy exception with stack |
| `VALIDATION` | Output produced but failed sanity check (§30) |

Avoid free-text reasons — they fragment the quarantine tree and break
downstream `ls failed/` triage scripts.

### §19.3 Retry with exponential backoff

Some failures are transient: the file was being written, the network
share dropped a packet, the JVM was paused for GC. Retrying is the
right answer. Other failures are permanent: the file is corrupt, the
algorithm cannot converge. Retrying just wastes time.

Distinguish the two with a small whitelist of retriable errors:

```python
import time, random
RETRIABLE = (
    "Connection reset",
    "stale file handle",
    "Resource temporarily unavailable",
    "I/O error",
    "Permission denied",      # Windows file lock during AV scan
    "The process cannot access the file because it is being used",
)
def is_retriable(err):
    s = str(err)
    return any(t in s for t in RETRIABLE)

def with_backoff(fn, max_attempts=5, base=2.0, cap=60.0):
    for attempt in range(1, max_attempts + 1):
        try:
            return fn()
        except Exception as e:
            if attempt == max_attempts or not is_retriable(e):
                raise
            delay = min(cap, base ** attempt) + random.uniform(0, 1)
            print(f"[retry {attempt}/{max_attempts}] {e} — sleeping {delay:.1f}s")
            time.sleep(delay)
```

Backoff parameters that work in practice for microscopy file servers:
`base=2.0, cap=60.0, max_attempts=5` → waits roughly 2, 4, 8, 16, 32
seconds. The `+ random.uniform(0, 1)` jitter avoids thundering-herd
behaviour when many parallel workers retry the same flaky NAS at once.

Retries should be counted in the per-file log row so a post-mortem can
distinguish "1 attempt, slow" from "5 attempts, finally succeeded" — the
second is a signal something is wrong with the storage.

### §19.4 Per-file timeout

A single hung file can block a whole batch. Bio-Formats can hang on
malformed files; some plugins enter infinite loops on degenerate
inputs. Time-bound every file.

In pure macro you cannot kill a hung `run("...")` from inside the macro,
because the macro interpreter is single-threaded. The timeout has to come
from the **outside** — the orchestrator that launched the macro:

```python
import subprocess
try:
    subprocess.run(
        ["./ImageJ-linux64", "--headless", "--console", "--run", "macro.ijm",
         "input=" + input_path + ",output=" + output_path],
        timeout=600,                            # 10 min per file
        check=True, capture_output=True,
    )
except subprocess.TimeoutExpired:
    quarantine(input_path, "TIMEOUT", "exceeded 600s", run_meta)
```

If you must run inside a long-lived Fiji session (TCP-driven from this
project), use a `--per-file-timeout` heuristic that re-validates the JVM
mid-run and kills the worker if a single file exceeds budget — see §21.4.

In Groovy you can wrap each file in a `Future` with a timeout:

```groovy
import java.util.concurrent.*
def exec = Executors.newSingleThreadExecutor()
def future = exec.submit({ -> processOne(path, params) } as Callable)
def result
try {
    result = future.get(600, TimeUnit.SECONDS)
} catch (TimeoutException ex) {
    future.cancel(true)
    result = [status: "TIMEOUT", error: "600s budget exceeded"]
}
exec.shutdownNow()
```

Caveat: `cancel(true)` only sets the interrupt flag — most ImageJ
operations do not check it. The thread will keep running until it
reaches a checkpoint that does (typically I/O). The robust fix is the
external-process timeout above.

### §19.5 Health check + restart

Even with batch mode and explicit closes, JVM heap can fragment over
hundreds of files and free heap drifts down. A periodic health check
catches this before it becomes an OOM:

```javascript
function jvmHealthy(thresholdMB) {
    freeMB = (IJ.maxMemory() - IJ.currentMemory()) / (1024 * 1024);
    return (freeMB > thresholdMB);
}

// every N files
if ((i + 1) % 25 == 0) {
    run("Collect Garbage");
    if (!jvmHealthy(500)) {
        print("BATCH_RESTART_HINT|" + i + "|free<500MB");
        // exit and let the orchestrator restart Fiji (§21.5)
        exit;
    }
}
```

Print a **machine-readable hint line** rather than calling `exit` blindly
— the orchestrator wraps the macro and decides whether to honour the
hint. The convention used by `ij.py log` (this project) is
`BATCH_*` prefixed lines, parsed downstream.

---

## §20 Logging & Provenance

A finished batch is only as useful as your ability to answer "what
exactly did this produce, and how" three months from now. Log enough
that the run is reconstructable from the logs alone — version, params,
inputs, outputs.

### §20.1 Per-run header

Once at the start of every run, write a header file capturing the
environment. Everything downstream is keyed off this.

```
runs/2026-05-01_12-00-00_a1b2c3/
  RUN.md                   <- header (this file)
  manifest.csv             <- copy of input manifest
  params.yaml              <- copy of params used
  script.ijm               <- exact macro that ran
  log.jsonl                <- per-file structured log
  log.txt                  <- human-readable log
  failed/                  <- quarantine tree
  outputs/                 <- mirror of input dir tree
```

`RUN.md` content:

```markdown
# Run 2026-05-01_12-00-00_a1b2c3

- **Started**: 2026-05-01T12:00:00Z
- **Machine**: lab-ws-04 (Windows 11, 64 GB RAM, NVIDIA RTX A4000)
- **User**: jamie
- **Working dir**: D:/experiments/E2026-04/
- **Git SHA**: a1b2c3d (clean) | a1b2c3d-dirty
- **Fiji version**: 2.16.0/1.54k (output of Help > About ImageJ)
- **Java version**: OpenJDK 21.0.2
- **Bio-Formats version**: 8.3.1
- **Plugins of interest**: StarDist 0.9.0, CLIJ2 2.5.3.1
- **Python version**: 3.11.7
- **Manifest**: manifest.csv (412 rows)
- **Params**: params.yaml (sigma=1.5, threshold=Otsu, min_size=50)
- **Heap**: -Xmx16g
- **Command**: ./ImageJ-linux64 --headless --console --run script.ijm "manifest=manifest.csv,params=params.yaml"
```

The `Git SHA` line is the most important entry — it points at the exact
code that produced the outputs. Generate it with `git rev-parse HEAD` and
mark it `-dirty` if `git status --porcelain` is non-empty:

```python
import subprocess
sha = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
dirty = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True).stdout.strip()
git_id = sha + ("-dirty" if dirty else "")
```

If your repo has uncommitted changes, log it as `dirty` and either
refuse to run (strict mode) or warn loudly (lenient mode). Reproducing
a `-dirty` run is a different conversation than reproducing a clean
SHA.

### §20.2 Per-file log row

For every file processed (success OR failure), append a row:

| Column | Example | Notes |
|---|---|---|
| `timestamp_utc` | `2026-05-01T12:00:04.213Z` | ISO 8601 UTC |
| `run_id` | `2026-05-01_12-00-00_a1b2c3` | Joins to RUN.md |
| `input_path` | `/data/in/a.tif` | Absolute |
| `input_sha256` | `d2a4f1...` | First 16 hex chars OK |
| `output_path` | `/data/out/a_mask.tif` | Empty on failure |
| `output_sha256` | `7e1c...` | Lets you detect bit-identical re-runs |
| `params_hash` | `b8a9...` | Hash of the params dict |
| `status` | `OK` / `OPEN_FAIL` / `TIMEOUT` / ... | §19.2 taxonomy |
| `attempts` | `1` | From retry loop (§19.3) |
| `duration_s` | `4.21` | Wall clock |
| `peak_mem_mb` | `2840` | `IJ.currentMemory()` peak |
| `n_objects` | `42` | Algorithm-specific result count |

Hash the input rather than relying on path + mtime — paths get renamed,
mtimes get reset by `cp`. SHA-256 prefix (16 hex chars) is collision-safe
in practice for any single experiment.

### §20.3 Structured logs (JSON Lines)

Plain-text logs are fine for humans. For programmatic post-mortem you
want JSON Lines (`.jsonl`) — one JSON object per line. `jq`, `pandas`,
and `polars` all read it directly.

```python
import json, datetime
def log_row(path, run_id, **kw):
    row = {"timestamp_utc": datetime.datetime.utcnow().isoformat(timespec="milliseconds") + "Z",
           "run_id": run_id, **kw}
    with open(path, "a") as f:
        f.write(json.dumps(row) + "\n")
```

Querying:

```bash
# How many files failed by reason?
jq -r 'select(.status != "OK") | .status' log.jsonl | sort | uniq -c

# Mean duration of OK files
jq -s 'map(select(.status == "OK") | .duration_s) | add/length' log.jsonl

# Files that took >30s
jq -c 'select(.duration_s > 30)' log.jsonl
```

```python
import pandas as pd
df = pd.read_json("log.jsonl", lines=True)
df.groupby("status")["duration_s"].agg(["count", "mean", "max"])
```

Macro-side, write JSON-ish rows by hand:

```javascript
function logJson(jsonlPath, run_id, input, status, ms, n_obj) {
    iso = "" + getTime();   // macro has no ISO formatter; use ms-since-epoch
    line = "{\"ts_ms\":" + iso +
           ",\"run\":\"" + run_id + "\"" +
           ",\"input\":\"" + input + "\"" +
           ",\"status\":\"" + status + "\"" +
           ",\"duration_s\":" + d2s(ms/1000, 3) +
           ",\"n_objects\":" + n_obj + "}\n";
    File.append(line, jsonlPath);
}
```

`File.append` adds without truncating — that's the macro equivalent of
`open(p, "a")`. Avoid `File.saveString` in a loop; it overwrites.

### §20.4 Save the script next to the output

The single most useful provenance practice: copy the macro/script into
the run directory before executing it. If you lose the git history but
keep the run directory, you can still reproduce the run.

```python
import shutil, os
run_dir = "/runs/" + run_id + "/"
os.makedirs(run_dir, exist_ok=True)
shutil.copy("./pipeline.ijm", run_dir + "script.ijm")
shutil.copy("./params.yaml", run_dir + "params.yaml")
shutil.copy("./manifest.csv", run_dir + "manifest.csv")
```

Even simpler: hash the script and write the hash into the per-run
header. Two runs with the same script hash are guaranteed to use the
same code; two runs with different hashes are not.

```python
import hashlib
def file_sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()
```

Cross-link: this project's `session_log.py` already exports a replayable
`.ijm` file from a TCP session — when ending an interactive session, save
that file into the run dir as the canonical "what I ran".

---

## §21 JVM Lifecycle Management

A long batch lives or dies on JVM hygiene. The JVM never returns memory
to the OS once allocated, so a leaky run accumulates resident memory
that the next run inherits. A leaked Plot window or undisposed
ImagePlus accumulates 50 MB; over 1000 files that's 50 GB of leak.

### §21.1 setBatchMode revisited

§4 covered the basics. The performance impact comes from skipping:

| Operation | Cost in interactive mode | Cost in batch mode |
|---|---|---|
| Window open / close | ~30 ms each (paint, AWT) | 0 |
| `setSlice()` | Forces redraw | No-op |
| LUT change | Redraws histogram | No-op |
| `roiManager("Show All")` | Repaints overlay | No-op |
| `print()` | Append to Log window UI | Buffers to log only |

Batch mode does **not** skip:

- Pixel allocation. A 2k × 2k × 50 z-stack still occupies 400 MB whether
  visible or not.
- File I/O. `open()` and `saveAs()` are unchanged.
- Plugin computation. `Gaussian Blur...` runs identically.

Common mistake: assuming `setBatchMode(true)` means "I don't have to
close images". You still have to. Hidden ImagePlus objects accumulate in
the WindowManager just like visible ones, until you `close()` them.

### §21.2 Heap and GC tuning

Modern Fiji (≥ 2.15.0) uses the **Jaunch launcher** with TOML config
(`jvm.cfg`) instead of the legacy `ImageJ.cfg`. Two ways to set the
heap:

```bash
# CLI override (one-off)
./fiji-windows-x64.exe --mem=16g --headless --run macro.ijm

# Or via JVM args after `--`
./ImageJ-linux64 -- -Xmx16g -- --headless --run macro.ijm
```

Either form works on modern Fiji; do not pass both. On the legacy
launcher (Fiji < 2.15.0), use `Edit > Options > Memory & Threads...`
which writes `ImageJ.cfg` next to the binary, or pass `-Xmx16g` directly
to the binary.

| Setting | Recommended value | When |
|---|---|---|
| `-Xmx` | 50–75% of system RAM | Default; leave OS room for file cache |
| `-Xms` | Equal to `-Xmx` | Long batches, avoids resize stalls |
| `-XX:+UseG1GC` | Enable | Heaps > 8 GB; better latency than default Parallel GC |
| `-XX:+UseParallelGC` | Default | Heaps < 8 GB or pure throughput |
| `-XX:MaxGCPauseMillis=200` | With G1GC | Soft target for pause time |
| `-XX:+HeapDumpOnOutOfMemoryError` | Always | Dumps `.hprof` on OOM for analysis |
| `-XX:HeapDumpPath=/runs/...` | Always | Where the dump goes |
| `-XX:ErrorFile=/runs/.../hs_err_%p.log` | Always | Where fatal logs go (§29.2) |

Do not over-allocate `-Xmx`. If you give the JVM more than the OS can
back, it works fine until first major GC, then thrashes the page file.
Symptom: 100% CPU, 0% disk activity, batch grinds to a halt. Reduce
`-Xmx`, do not increase it.

### §21.3 Garbage collection between files

```javascript
run("Collect Garbage");          // Plugins > Utilities > Collect Garbage
call("java.lang.System.gc");     // direct JVM call
```

Both work; `run("Collect Garbage")` also flushes ImageJ-internal caches.
A common pattern is "GC every N files":

```javascript
if ((i + 1) % 10 == 0) {
    run("Collect Garbage");
    print("[gc] free=" + d2s((IJ.maxMemory()-IJ.currentMemory())/1048576, 0) + " MB");
}
```

Caveats from the field:

- `run("Collect Garbage")` is **advisory** to the JVM. The JVM may decline
  if it has plenty of free heap. Don't expect free heap to drop to a
  baseline — expect it to hold steady.
- SCIFIO leaks file handles in batch mode in some Fiji versions. If you
  see `Too many open files` (Linux) or hung `Bio-Formats Importer`
  calls, disable SCIFIO: `Edit > Options > ImageJ2... > Use SCIFIO when
  opening files (legacy)` off.
- Per-image `close()` does not always release pixel memory in batch mode
  — see Fiji bug #819. The workaround is the GC every-N pattern above.

### §21.4 Periodic restart: the "1000 files" rule

Native libraries (Bio-Formats JNI, CLIJ2 OpenCL contexts, deconvolution
CUDA kernels) can corrupt internal state after many invocations. The
JVM heap looks fine, but the next file silently produces garbage or
segfaults. The cure is a periodic restart of the Fiji process.

A practical heuristic: **restart Fiji every ~1000 files or every ~4
hours**, whichever comes first. The exact threshold depends on which
native libs you use:

| Workload | Restart cadence |
|---|---|
| Pure ImageJ macro (no JNI) | Every ~5000 files |
| Bio-Formats heavy | Every ~1000 files |
| CLIJ2 GPU pipelines | Every ~500 files |
| Deconvolution / cuda libs | Every ~100 files |
| StarDist / Cellpose via TF/PyTorch | Every ~200 files |

Do not pick a number; instrument and observe. Print a heap+native-mem
line every 50 files and watch for upward drift over time.

The orchestrator handles the restart; the macro requests it via a
sentinel print:

```javascript
// Inside the macro:
if ((i+1) % 1000 == 0) {
    print("BATCH_RESTART_HINT|completed=" + (i+1));
    exit;          // returns to launcher, exits cleanly
}
```

```python
# Outside the macro (orchestrator):
while not done:
    proc = subprocess.run([fiji_cmd], capture_output=True, text=True)
    if "BATCH_RESTART_HINT" in proc.stdout:
        print("[orch] restart requested, relaunching")
        continue
    if proc.returncode != 0:
        # crash — see §29.2 for what to do with hs_err_pid logs
        ...
```

### §21.5 Fresh JVM per N files

For maximum isolation, run **one Fiji invocation per file** (or per
chunk of N files). Each invocation gets a clean JVM. The cost is
~3–8 s of JVM startup per invocation, so this is most appropriate when
per-file processing is at least ~30 s — otherwise startup dominates.

```bash
# fresh JVM per file
for f in /data/in/*.lif; do
    ./ImageJ-linux64 --headless --console --mem=8g \
        --run process_one.ijm "input=${f},output=/data/out/" \
        > "/data/logs/$(basename "$f").log" 2>&1
done
```

```bash
# fresh JVM per chunk (N=50), parallel-friendly via xargs
ls /data/in/*.lif | xargs -n 50 -I{} bash -c '
    ./ImageJ-linux64 --headless --console --mem=8g \
        --run process_chunk.ijm "files=$0"
' {}
```

Per-chunk amortises startup but loses the "kill any single file's hung
JVM" property. Pick per-file when files are slow or risky, per-chunk
when files are uniform and fast.

For HPC, this is the natural Snakemake / Nextflow shape — each rule is
its own process. See §25.4.

---

## §22 Bio-Formats Memoization (`.bfmemo`)

Bio-Formats spends most of its time on first-open scanning multi-series
files (LIF, CZI, ND2 with hundreds of series). The `Memoizer` class
caches the parsed metadata to a hidden `.bfmemo` file next to the source
so subsequent opens skip the scan.

Cross-link: §6 (Bio-Formats Macro Extensions),
[bioformats-multiseries-reference.md](bioformats-multiseries-reference.md).

### §22.1 Behaviour

- **Off by default** — you must enable it explicitly.
- Threshold: cache only if first parse took > 100 ms
  (`Memoizer.DEFAULT_MINIMUM_ELAPSED`). Small TIFFs are not cached.
- Default location: `.<filename>.bfmemo` in the same directory as the
  source. A custom cache directory is supported (recommended for
  read-only data trees).
- Invalidated when: source file mtime changes, Bio-Formats version
  differs from the version that wrote the memo, or memo serialization
  format changes.
- Speedup: typically 5–50× on first-time-to-pixels for multi-series
  files. Negligible benefit for single-series TIFF.

### §22.2 Enabling from the GUI / preferences

`Plugins > Bio-Formats > Bio-Formats Plugins Configuration > Memoization`
— check "Enable Memoization", optionally set "Cache directory". The
setting persists in `IJ_Prefs.txt`. After enabling, the **first** open
of each file is the same speed as before (it writes the memo); the
**second** and later opens are fast.

### §22.3 Enabling from Groovy

```groovy
import loci.formats.ImageReader
import loci.formats.Memoizer

def reader = new ImageReader()
def memo = new Memoizer(reader, 0)              // 0 = always cache
// or:
// def memo = new Memoizer(reader, 100, new File("/cache/bfmemo"))
memo.setId("/data/in/big_experiment.lif")
def nSeries = memo.getSeriesCount()
println "series=${nSeries}"
memo.close()
```

Pass a custom cache `File` to keep `.bfmemo` files out of read-only
shared storage and into a fast local SSD:

```groovy
def cacheDir = new File(System.getProperty("user.home") + "/.bfcache")
cacheDir.mkdirs()
def memo = new Memoizer(new ImageReader(), 100L, cacheDir)
```

### §22.4 Pre-warming the cache

For a multi-day batch on hundreds of multi-series files, pre-warm the
cache once before the run starts:

```groovy
import loci.formats.ImageReader
import loci.formats.Memoizer
def cacheDir = new File("/local-ssd/bfcache")
cacheDir.mkdirs()
new File("/data/in").eachFileRecurse { f ->
    if (!(f.name ==~ /(?i).*\.(lif|czi|nd2|ims)$/)) return
    def m = new Memoizer(new ImageReader(), 100L, cacheDir)
    long t0 = System.currentTimeMillis()
    m.setId(f.absolutePath)
    long ms = System.currentTimeMillis() - t0
    println "[warmed] ${f.name} in ${ms}ms"
    m.close()
}
```

After this, every batch worker that points at the same `cacheDir` skips
the slow scan.

### §22.5 Caveats

- `.bfmemo` files are **opaque Java serialization**. Bio-Formats ≤ 8.3.0
  has known unsafe-deserialization issues — never load memo files from
  untrusted sources. For an internal lab batch this is not a concern;
  for a multi-tenant cluster it might be.
- If your storage is read-only (e.g. mounted shared drive), the in-place
  default fails silently. Use a writable cache dir.
- After upgrading Fiji, all memos invalidate automatically. The first
  run after an upgrade is back to baseline speed.

---

## §23 Parameter Sweeps

When you don't yet know the right parameters, sweep over candidates and
compare. Output organisation matters more than sweep strategy — a
sweep with no auditable layout is unreproducible.

### §23.1 Grid sweep (every combination)

```python
import itertools, hashlib, json, csv
sigmas = [0.5, 1.0, 1.5, 2.0]
methods = ["Otsu", "Triangle", "Li"]
min_sizes = [25, 50, 100]
sweep = list(itertools.product(sigmas, methods, min_sizes))   # 36 combos

def combo_id(sigma, method, ms):
    payload = json.dumps({"sigma": sigma, "method": method, "min_size": ms}, sort_keys=True)
    return hashlib.sha1(payload.encode()).hexdigest()[:8]

with open("sweep_manifest.csv", "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["combo_id","sigma","method","min_size","output_dir"])
    for sigma, method, ms in sweep:
        cid = combo_id(sigma, method, ms)
        w.writerow([cid, sigma, method, ms, f"outputs/sweep_{cid}/"])
```

Grid sweeps are fine up to ~3 parameters. Beyond that the cost grows
exponentially.

### §23.2 Random / Latin hypercube sweep

For 4+ parameters or continuous ranges, random sampling explores the
space more efficiently per-sample than grid. Latin Hypercube Sampling
(LHS) goes further: it stratifies each dimension so every range bucket
is hit at least once.

```python
import numpy as np
rng = np.random.default_rng(seed=42)

# Random uniform — simple
n = 64
sigmas    = rng.uniform(0.5, 3.0, n)
min_sizes = rng.integers(25, 200, n)
thresh    = rng.uniform(500, 5000, n)

# LHS via scipy (better coverage)
from scipy.stats import qmc
sampler = qmc.LatinHypercube(d=3, seed=42)
unit = sampler.random(n=n)                # n × 3 in [0,1)
lo = np.array([0.5, 25,  500])
hi = np.array([3.0, 200, 5000])
samples = qmc.scale(unit, lo, hi)
```

For Bayesian optimisation of a single quantitative score (e.g. best
F1 against a hand-labelled subset), use `scikit-optimize` or
`optuna` — out of scope for this reference but worth knowing exists.

### §23.3 Sweep output layout

The hard rule: every sweep output directory is named after a stable
hash of its parameter dict, and contains a `params.yaml` that the
hash was computed from.

```
outputs/
  sweep_a3f1c290/
    params.yaml         <- {sigma: 1.5, method: Otsu, min_size: 50}
    masks/
    results.csv
    log.jsonl
  sweep_71b29df0/
    params.yaml         <- {sigma: 2.0, method: Triangle, min_size: 50}
    ...
  sweep_summary.csv     <- one row per combo with summary metrics
```

The summary CSV is what you read into pandas / a notebook to pick the
winner:

```csv
combo_id,sigma,method,min_size,n_objects_mean,n_objects_sd,duration_s_mean,score
a3f1c290,1.5,Otsu,50,38.4,4.1,4.2,0.92
71b29df0,2.0,Triangle,50,41.1,3.7,4.5,0.88
...
```

`score` is whatever quantitative target you defined (F1 vs ground
truth, distance from a reference, etc.). Without a score the sweep is
just a pile of outputs to scroll through visually.

### §23.4 Sweeping in macro

```javascript
sigmas    = newArray(0.5, 1.0, 1.5, 2.0);
methods   = newArray("Otsu", "Triangle", "Li");
minSizes  = newArray(25, 50, 100);

setBatchMode(true);
for (a = 0; a < sigmas.length; a++) {
    for (b = 0; b < methods.length; b++) {
        for (c = 0; c < minSizes.length; c++) {
            comboId = "s" + sigmas[a] + "_t" + methods[b] + "_m" + minSizes[c];
            outDir = output + "sweep_" + comboId + "/";
            File.makeDirectory(outDir);

            open(inputPath);
            run("Gaussian Blur...", "sigma=" + sigmas[a]);
            setAutoThreshold(methods[b] + " dark");
            run("Convert to Mask");
            run("Analyze Particles...",
                "size=" + minSizes[c] + "-Infinity display");
            saveAs("Results", outDir + "results.csv");
            run("Clear Results");
            close();
        }
    }
}
setBatchMode(false);
```

Cross-link: `python ij.py explore Otsu Triangle Li` (this project's
threshold-comparison helper) is a one-shot threshold sweep over a
single image. Use it for interactive exploration; use the template
above for batch sweeps over many files.

---

## §24 Headless ImageJ

Headless mode runs Fiji without a display. Required for SLURM, CI,
unattended overnight batches, and parallel workers (§25).

### §24.1 Command-line invocation

```bash
# Linux / macOS
./ImageJ-linux64 --ij2 --headless --console --mem=8g \
    --run macro.ijm 'manifest=manifest.csv,run_id=20260501_a1b2c3'

# Windows (Jaunch launcher, ≥ 2.15.0)
fiji-windows-x64.exe --ij2 --headless --console --mem=8g ^
    --run macro.ijm "manifest=manifest.csv,run_id=20260501_a1b2c3"

# Windows (legacy launcher)
ImageJ-win64.exe --ij2 --headless --console --mem=8g ^
    --run macro.ijm "manifest=manifest.csv,run_id=20260501_a1b2c3"
```

| Flag | Effect |
|---|---|
| `--ij2` | Use the modern (ImageJ2) launcher path. Recommended on Windows. |
| `--headless` | No display; AWT-dependent code throws `HeadlessException`. |
| `--console` | Attach a console for stdout/stderr (essential on Windows; without it `print()` is silently discarded). |
| `--no-splash` | Suppress splash screen (rarely needed with `--headless`). |
| `--mem=8g` | Set max JVM heap (Jaunch launcher). Equivalent to `-Xmx8g`. |
| `--run <macro> <args>` | Run an `.ijm` file with `key=val,key=val` args (ImageJ2 args). |
| `-macro <macro> <args>` | Legacy form: passes whole arg string, retrieved via `getArgument()`. |
| `--debug` | Verbose internal logging. |
| `--update <subcmd>` | Run the imagej-updater non-interactively (§24.3). |
| `-- -Xmx8g` | Pass JVM args after `--`. |

The `-macro` and `--run` forms differ:
- `-macro` is **legacy** and passes one free-text string; the macro reads
  it via `getArgument()` and parses it itself. You cannot use `#@`
  script parameters with `-macro`.
- `--run` is **ImageJ2-style** and supports `#@` script parameters with
  proper typing, but is comma-separated and quote-sensitive.

### §24.2 Argument passing

Three idioms, increasing in robustness.

**1. `getArgument()` with `-macro`** — quick and dirty:

```bash
./ImageJ-linux64 --headless -macro process.ijm "/data/in,/data/out,Otsu,1.5"
```

```javascript
// process.ijm
args = getArgument();
parts = split(args, ",");
inDir = parts[0]; outDir = parts[1]; method = parts[2]; sigma = parseFloat(parts[3]);
```

Fragile: any comma in a path breaks it; quoting is shell-dependent.

**2. ImageJ2 `--run` with key=value**:

```bash
./ImageJ-linux64 --headless --console \
    --run process.ijm 'in_dir=/data/in,out_dir=/data/out,method=Otsu,sigma=1.5'
```

```javascript
// process.ijm — values come in as global variables
print("in_dir=" + in_dir);
print("method=" + method);
```

Better, but still chokes on commas inside values. Escape commas or use form 3.

**3. ImageJ2 `#@` script parameters (Groovy preferred):**

```groovy
#@ String in_dir
#@ String out_dir
#@ String method
#@ Float sigma
println "in_dir=${in_dir} method=${method} sigma=${sigma}"
```

```bash
./ImageJ-linux64 --headless --console \
    --run process.groovy 'in_dir="/data/in",out_dir="/data/out",method="Otsu",sigma=1.5'
```

`#@` parameters are typed, validated, and quoted strings handle commas
correctly. Recommended for any new headless pipeline.

**4. Manifest path as the single arg** (most robust):

Pass a single argument: the path to a manifest. Read everything else
from the manifest. Avoids all quoting headaches.

```bash
./ImageJ-linux64 --headless --console --run process.groovy 'manifest="/runs/.../manifest.csv"'
```

### §24.3 Plugin install in CI (`--update`)

The imagej-updater has a CLI:

```bash
./ImageJ-linux64 --update list                                  # list subcommands
./ImageJ-linux64 --update update                                # apply pending updates
./ImageJ-linux64 --update update-force                          # ignore conflicts
./ImageJ-linux64 --update update-force-pristine                 # match remote exactly
./ImageJ-linux64 --update edit-update-site Bio-Formats https://sites.imagej.net/Bio-Formats/
./ImageJ-linux64 --update add-update-site Cellpose https://sites.imagej.net/Cellpose/
./ImageJ-linux64 --update upload-complete-site MySite
```

Typical CI workflow:

```bash
# Download Fiji, install plugins from update sites non-interactively
wget https://downloads.imagej.net/fiji/latest/fiji-linux64.zip
unzip fiji-linux64.zip
./Fiji.app/ImageJ-linux64 --update add-update-site Bio-Formats https://sites.imagej.net/Bio-Formats/
./Fiji.app/ImageJ-linux64 --update add-update-site clij2 https://sites.imagej.net/clij/
./Fiji.app/ImageJ-linux64 --update update-force-pristine
# Now run the actual pipeline
./Fiji.app/ImageJ-linux64 --headless --console --run pipeline.groovy
```

Side effect: `update-force-pristine` will **revert** any local changes
to bundled plugins — fine for CI, dangerous on your dev box. Don't run
it on a Fiji install you've hand-customised.

### §24.4 Capturing stdout / stderr

```bash
# Combined log
./ImageJ-linux64 --headless --console --run macro.ijm 'args=...' \
    > run.log 2>&1

# Separate
./ImageJ-linux64 --headless --console --run macro.ijm 'args=...' \
    > stdout.log 2> stderr.log
```

Where output goes:

| Source | Goes to |
|---|---|
| `print(...)` (macro) | stdout (with `--console`) |
| `IJ.log(...)` (Java/Groovy) | stdout via the Log window adapter |
| `System.out.println` (Groovy/Java) | stdout |
| `System.err.println` | stderr |
| Uncaught Java exception | stderr |
| Bio-Formats native warnings | stderr |
| Plugin runtime exceptions | stderr (and Log window if mode allows) |

On Windows without `--console`, **stdout is silently discarded** because
`ImageJ-win64.exe` is a GUI-subsystem binary. Always pass `--console` in
batch.

### §24.5 What does NOT work headless

| Plugin / API | Why it fails | Workaround |
|---|---|---|
| 3D Viewer, 3D ROI Manager | Java3D windows | Render via `3D Project...` macro (rendered into ImagePlus, no window) |
| 3Dscript Batch Animation | Swing canvas | Use offline render mode if available |
| `RoiManager` (legacy) | Extends `java.awt.Frame` | Use `ij.gui.Overlay` API directly |
| `WaitForUser` | UI prompt | Remove or guard with `if (!IJ.isHeadless())` |
| `GenericDialog.showDialog()` (third-party) | UI prompt | Bytecode-patched for IJ1; third-party dialogs may not patch — replace with explicit params |
| `WindowManager.getCurrentWindow()` | No windows | Use `selectImage(title)` instead of `selectWindow(name)` |
| AWT font metrics on Linux without X | Tries to call X server | Run under `xvfb-run -a fiji ...` |
| Some Trainable Weka GUI ops | Need Swing | Train interactively, save model, apply via headless macro |

The tell: a `HeadlessException` or `NoClassDefFoundError: sun.awt...` in
stderr means a code path tried to touch Swing/AWT. Either avoid that
plugin headless or run under Xvfb:

```bash
# Linux: virtual X server
sudo apt-get install xvfb
xvfb-run -a -s "-screen 0 1280x1024x24" \
    ./ImageJ-linux64 --console --run macro.ijm 'args=...'
```

Note: under `xvfb-run` you do **not** pass `--headless` — Fiji thinks it
has a display. This works around AWT-touching plugins at the cost of
losing the `--headless` performance and AWT-free guarantee.

### §24.6 Console behaviour on Windows

Windows-specific gotcha: `ImageJ-win64.exe` is built with the GUI
subsystem (linked with `/SUBSYSTEM:WINDOWS`). A GUI binary is **detached
from the parent console** by Windows. Without `--console`, all `print()`
output disappears into the void.

`--console` causes the launcher to allocate a new console and reattach
stdout/stderr. Some Jaunch builds also ship a side-by-side
`fiji-windows-x64-console.exe` that is built with the console subsystem
— easier to use from PowerShell.

```powershell
# PowerShell: capture stdout reliably
& ".\fiji-windows-x64.exe" --console --headless --run "macro.ijm" "args=..." `
    *> "run.log"
```

The `*>` PowerShell redirect captures all streams.

---

## §25 Parallelism

Per-file processing is **embarrassingly parallel**. The constraint is
RAM and (for GPU pipelines) GPU memory, not CPU. Spinning up N Fiji
processes uses N × `-Xmx` of RAM, so size your worker count to the
machine.

Cross-link: §7 of large-dataset-optimization-reference.md (CLIJ2 GPU)
discusses GPU contention; in short, multiple Fiji processes cannot
share one GPU well — pick CPU parallelism on multi-GPU systems, or use
one worker per GPU.

### §25.1 When parallelism is worth it

| Worth it | Not worth it |
|---|---|
| Independent files, identical processing | Sequential dependency between files |
| Per-file time > 10 s (amortise JVM startup) | Per-file time < 1 s (startup dominates) |
| Plenty of RAM (`-Xmx × N` < total) | Tight on RAM |
| Disk is local SSD or fast NAS | Single slow spinning disk (becomes the bottleneck) |
| Pure CPU work | GPU work without per-worker GPU |

A useful rule: parallelism gives near-linear speedup up to
`min(physical_cores, total_RAM_GB / Xmx_GB)`. Beyond that, scaling
plateaus or reverses.

### §25.2 GNU parallel / xargs

The simplest cross-process parallelism:

```bash
# GNU parallel — N workers (here: 4)
ls /data/in/*.lif | parallel -j 4 \
    './ImageJ-linux64 --headless --console --mem=4g \
        --run process_one.ijm "input={},output=/data/out/" \
        > /data/logs/{/.}.log 2>&1'

# {} = full path, {/.} = basename without extension
# parallel handles --eta progress, --joblog, retry-failed
```

```bash
# xargs alternative — fewer features but ubiquitous
ls /data/in/*.lif | xargs -P 4 -I{} bash -c '
    ./ImageJ-linux64 --headless --console --mem=4g \
        --run process_one.ijm "input=$0,output=/data/out/" \
        > "/data/logs/$(basename "$0").log" 2>&1
' {}
```

GNU parallel features worth knowing:

```bash
parallel --joblog jobs.log ...                # CSV log of every job
parallel --resume-failed --joblog jobs.log ... # rerun only the failures
parallel --retries 3 ...                       # retry transient failures
parallel --eta ...                             # progress + ETA
```

### §25.3 Multi-instance Fiji caveats

Fiji writes to `IJ_Prefs.txt` on shutdown. Concurrent shutdowns can
**race and corrupt** prefs. Workarounds:

1. **Don't share the install** — each worker gets its own Fiji copy.
   Cheap to set up: copy `Fiji.app/`, set `-Dij.dir=/path/to/copyN/`.
2. **Disable prefs writes** in headless: `-Dij1.plugin.frame.NoSave=true`
   (community workaround, not officially supported).
3. **Pre-create separate config dirs** and point each worker at one:

```bash
for i in 1 2 3 4; do
    mkdir -p "/runs/worker$i/.imagej"
done
parallel -j 4 \
    'HOME=/runs/worker{%} ./ImageJ-linux64 --headless --console --run ...'
# {%} = parallel job slot number 1..N, gives each worker a unique HOME
```

`xvfb-run -a` per worker is also a clean isolation pattern on Linux —
each gets its own X DISPLAY.

### §25.4 Snakemake / Nextflow

For HPC clusters, wrap Fiji headless in a Snakemake rule:

```python
# Snakefile
INPUTS, = glob_wildcards("/data/in/{name}.lif")

rule all:
    input: expand("/data/out/{name}_mask.tif", name=INPUTS)

rule process:
    input:  "/data/in/{name}.lif"
    output: "/data/out/{name}_mask.tif"
    log:    "/data/logs/{name}.log"
    threads: 1
    resources: mem_mb=8000, time="00:30:00"
    shell:
        "./ImageJ-linux64 --headless --console --mem={resources.mem_mb}m "
        "--run process_one.groovy 'input={input},output={output}' "
        "> {log} 2>&1"
```

Run: `snakemake -j 100 --cluster "sbatch --mem={resources.mem_mb}M ..."`

Snakemake gives idempotency for free — outputs are dependencies, so
re-runs skip existing files automatically. The `log:` directive
captures stdout per rule. Failed rules can be re-run with
`snakemake --rerun-incomplete`.

Cross-link: §28.4 has a complete 3-rule Snakefile for an open →
segment → measure pipeline.

Nextflow analogue:

```nextflow
process imageProcess {
    input:  path img
    output: path "${img.simpleName}_mask.tif"
    memory '8 GB'
    time   '30m'
    """
    ImageJ-linux64 --headless --console --mem=8g \
        --run process_one.groovy 'input=${img},output=${img.simpleName}_mask.tif'
    """
}

workflow {
    Channel.fromPath('/data/in/*.lif') | imageProcess
}
```

### §25.5 Internal Fiji parallelism

ImageJ's macro `run("Parallel...", ...)` and `Process > Batch >
Parallel` use thread-based parallelism inside one JVM. Limitations:

- Single Results table — concurrent writes need locks.
- Single ROI Manager — same.
- Memory pressure adds up: N threads each holding an ImagePlus = N×size.
- Some plugins are not thread-safe.

Use for **lightweight per-image ops** (filtering, thresholding) where
the saving is "no JVM startup per file" and the per-file processing
is small. Use cross-process parallelism (§25.2) for everything else.

---

## §26 Watch-Folder / Queue-Driven Processing

For acquisition that produces files continuously (microscope writes
files as they're acquired), poll-and-process is wasteful and laggy.
Watch the folder and react when new files appear.

### §26.1 Linux: inotify

```bash
# inotifywait — block until a write completes
inotifywait -m -e close_write --format '%w%f' /data/in | while read path; do
    if [[ "$path" == *.lif ]]; then
        echo "[watch] new: $path"
        ./ImageJ-linux64 --headless --console \
            --run process_one.ijm "input=$path,output=/data/out/" &
    fi
done
```

`-e close_write` fires when the file is fully written and closed by the
producer. If you watch `-e create` instead, you fire on file creation
which happens **before** the file is fully written — see §26.3.

### §26.2 Cross-platform: Python watchdog

```python
from watchdog.observers import Observer
from watchdog.events import FileSystemEventHandler
import subprocess, queue, threading, time, os

job_queue = queue.Queue()

class Handler(FileSystemEventHandler):
    def on_closed(self, event):                  # Linux/macOS: file closed for write
        if event.src_path.endswith(".lif"):
            job_queue.put(event.src_path)

    def on_created(self, event):                 # Windows: only signal we get
        if event.src_path.endswith(".lif"):
            job_queue.put(event.src_path)

def worker():
    while True:
        path = job_queue.get()
        if not wait_until_stable(path):
            print(f"[skip] {path} unstable"); continue
        subprocess.run(
            ["./ImageJ-linux64", "--headless", "--console",
             "--run", "process_one.ijm", f"input={path}"],
            check=False
        )

threading.Thread(target=worker, daemon=True).start()
obs = Observer()
obs.schedule(Handler(), "/data/in", recursive=True)
obs.start()
try:
    while True: time.sleep(1)
except KeyboardInterrupt:
    obs.stop()
obs.join()
```

`watchdog` uses inotify on Linux, FSEvents on macOS, and
ReadDirectoryChangesW on Windows — one API, three OSes.

### §26.3 Stable-file detection

The single most important watch-folder pattern: **wait until the file
size stops changing** before opening it. Microscope software writes
files in chunks; opening mid-write reads a truncated file and gets
corrupt pixels.

```python
import os, time
def wait_until_stable(path, settle_s=5.0, poll_s=1.0, timeout_s=600.0):
    deadline = time.monotonic() + timeout_s
    last_size = -1
    last_change = time.monotonic()
    while time.monotonic() < deadline:
        try:
            size = os.path.getsize(path)
        except FileNotFoundError:
            return False
        if size != last_size:
            last_size = size
            last_change = time.monotonic()
        elif time.monotonic() - last_change >= settle_s:
            return True
        time.sleep(poll_s)
    return False
```

A file is "stable" once its size has not changed for `settle_s`
seconds. Pick `settle_s` long enough that microscope-software inter-chunk
gaps don't trip a false positive — 5 s is conservative, 30 s is safer
for slow writers.

For LIF/CZI/ND2 the stable-size heuristic is fine because the producer
writes one big file. For TIFF series ("OME-TIFF MicroManager style")
where dozens of `_pos00.ome.tif`, `_pos01.ome.tif`, ... are written in
sequence, stable-detection on the **directory's file count** plus the
**last file's size** is the right signal.

### §26.4 Producer-side sentinel

The most robust pattern: have the producer write a sentinel file
**after** the data file is complete:

```
/data/in/sample_A001.lif        <- written by microscope
/data/in/sample_A001.lif.done   <- written by microscope after .lif is closed
```

The watcher only processes files that have a matching `.done` sibling.
This makes "fully-written" an explicit signal instead of a guess.

Most acquisition software does not do this by default, but most
microscope software does support a post-acquisition script — point it
at `touch %F.done`.

### §26.5 Cron / scheduled batches

For periodic non-realtime processing (every hour, every night), a cron
job that runs the resumable batch pipeline (§18) is simpler than a
watcher. Resumability ensures no work is repeated; the manifest grows
to include any new files.

```cron
# crontab: every hour at :05, idempotent batch
5 * * * * /opt/pipelines/run_batch.sh >> /var/log/pipeline.log 2>&1
```

`run_batch.sh` regenerates the manifest from the input dir, then runs
the pipeline. Resumability skips files already in the state CSV.

---

## §27 Output Organisation Conventions

A messy output tree makes provenance impossible. Standardise the layout
once, use it everywhere.

### §27.1 Canonical layout

```
experiment_2026-04/
  inputs/                                      <- read-only, raw acquisitions
    plate_01/
      well_A01.lif
      well_A02.lif
    plate_02/
      ...
  manifest.csv                                 <- one row per (input, params) tuple
  params.yaml                                  <- shared params for the run
  scripts/
    pipeline.groovy                            <- the pipeline code
    sweep.sh                                   <- launcher
  runs/
    2026-05-01_12-00_a1b2c3/
      RUN.md                                   <- run header (§20.1)
      script.groovy                            <- exact script copy
      params.yaml                              <- exact params copy
      manifest.csv                             <- exact manifest copy
      log.jsonl                                <- per-file structured log
      log.txt                                  <- human-readable log
      state.csv                                <- resumability state
      outputs/                                 <- mirrors inputs/ structure
        plate_01/
          well_A01_mask.tif
          well_A01_results.csv
      intermediates/                           <- gitignored, regeneratable
      failed/
        OPEN_FAIL/
        OOM/
        TIMEOUT/
  notebooks/
    explore.ipynb                              <- analysis on outputs/
  README.md                                    <- one-paragraph what-this-is
```

### §27.2 Mirror the input tree

If the input tree has structure, mirror it in the output tree:

```
inputs/
  control/sample_01.tif
  control/sample_02.tif
  drug_a/sample_01.tif
  drug_a/sample_02.tif

outputs/
  control/sample_01_mask.tif
  control/sample_02_mask.tif
  drug_a/sample_01_mask.tif
  drug_a/sample_02_mask.tif
```

Don't flatten:

```
outputs/                  <- BAD
  control_sample_01_mask.tif
  drug_a_sample_01_mask.tif
```

Mirroring preserves the input's grouping (well, plate, condition, time
point, region) and makes the relationship inputs[i] ↔ outputs[i]
visually obvious.

```python
import os
def mirror_path(input_path, input_root, output_root, suffix):
    rel = os.path.relpath(input_path, input_root)
    base, _ = os.path.splitext(rel)
    return os.path.join(output_root, base + suffix)
```

### §27.3 Gitignore intermediates

`intermediates/` holds regeneratable artifacts: cropped versions, debug
masks, per-step screenshots. They are valuable during debugging but
should not be checked in or shipped:

```gitignore
# .gitignore
intermediates/
runs/*/intermediates/
runs/*/log.txt
runs/*/log.jsonl
*.bfmemo
hs_err_pid*.log
```

Keep `runs/.../RUN.md`, `runs/.../manifest.csv`, `runs/.../script.*`,
and `runs/.../state.csv` — those are the provenance that lets you
reconstruct the run.

---

## §28 End-to-End Templates

Four self-contained templates: bash + headless Fiji, pure macro,
Python orchestrator, and Snakemake. Pick the one that matches your
infrastructure.

### §28.1 Bash + headless Fiji (sweep + resume)

```bash
#!/usr/bin/env bash
# run_batch.sh — robust batch with resumability, sweep, structured log
set -euo pipefail

FIJI="./ImageJ-linux64"
INPUT_DIR="/data/in"
OUTPUT_ROOT="/data/runs"
SCRIPT="./pipeline.groovy"
HEAP="8g"

# Identify run
GIT_SHA=$(git rev-parse HEAD 2>/dev/null || echo "no-git")
GIT_DIRTY=$(git status --porcelain 2>/dev/null | head -c1)
[ -n "$GIT_DIRTY" ] && GIT_SHA="${GIT_SHA}-dirty"
RUN_ID="$(date -u +%Y-%m-%dT%H-%M-%SZ)_${GIT_SHA:0:8}"
RUN_DIR="${OUTPUT_ROOT}/${RUN_ID}"
mkdir -p "${RUN_DIR}/outputs" "${RUN_DIR}/failed" "${RUN_DIR}/logs"

# Snapshot script + params
cp "${SCRIPT}" "${RUN_DIR}/script.groovy"
cp params.yaml "${RUN_DIR}/params.yaml" 2>/dev/null || true

# Run header
cat > "${RUN_DIR}/RUN.md" <<EOF
# Run ${RUN_ID}
- Started: $(date -u +%Y-%m-%dT%H:%M:%SZ)
- Machine: $(hostname)
- User: $(whoami)
- Git SHA: ${GIT_SHA}
- Fiji: $(${FIJI} --headless --console --run /dev/stdin <<<'print(IJ.getFullVersion());' 2>&1 | tail -1)
- Heap: ${HEAP}
- Input: ${INPUT_DIR}
EOF

# State CSV header (resumability)
STATE="${RUN_DIR}/state.csv"
[ ! -f "${STATE}" ] && echo "input_path,status,output_path,duration_s,timestamp_utc" > "${STATE}"

# Extract DONE rows for skip
DONE_FILE=$(mktemp)
awk -F, 'NR>1 && $2=="DONE" {print $1}' "${STATE}" > "${DONE_FILE}"

# Process each file
for f in "${INPUT_DIR}"/*.lif; do
    if grep -qxF "${f}" "${DONE_FILE}"; then
        echo "[skip] already processed: ${f}"; continue
    fi
    base=$(basename "${f}" .lif)
    out="${RUN_DIR}/outputs/${base}_mask.tif"
    log="${RUN_DIR}/logs/${base}.log"
    t0=$(date +%s)
    if timeout 600 "${FIJI}" --ij2 --headless --console --mem="${HEAP}" \
            --run "${RUN_DIR}/script.groovy" \
            "input=\"${f}\",output=\"${out}\",run_id=\"${RUN_ID}\"" \
            > "${log}" 2>&1; then
        ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
        dur=$(( $(date +%s) - t0 ))
        echo "${f},DONE,${out},${dur},${ts}" >> "${STATE}"
        echo "[ok] ${f} (${dur}s)"
    else
        rc=$?
        ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
        dur=$(( $(date +%s) - t0 ))
        reason="EXCEPTION"
        [ $rc -eq 124 ] && reason="TIMEOUT"
        mkdir -p "${RUN_DIR}/failed/${reason}"
        cp -p "${f}" "${RUN_DIR}/failed/${reason}/"
        echo "${f},${reason},,${dur},${ts}" >> "${STATE}"
        echo "[fail] ${f} (${reason}, rc=${rc})"
    fi
done

rm -f "${DONE_FILE}"
echo "[done] run ${RUN_ID} complete"
```

Properties:
- **Resumable**: re-running skips rows marked DONE in state.csv.
- **Atomic**: each output goes through Fiji's own write; the orchestrator
  appends to state.csv only after a successful exit code.
- **Per-file timeout**: `timeout 600` kills any single file after 10 min.
- **Quarantine**: failed files copied into `failed/<reason>/`.
- **Provenance**: RUN.md captures git SHA, Fiji version, command line.

### §28.2 Pure macro (manifest-driven, in-Fiji)

For when you cannot use bash (e.g. running interactively from the Fiji
GUI on Windows).

```javascript
// pipeline.ijm — manifest-driven robust batch
manifestPath = getDirectory("Choose manifest CSV") + "manifest.csv";
runId = "" + getTime();
outputRoot = getDirectory("Choose output root");
runDir = outputRoot + "run_" + runId + "/";
File.makeDirectory(runDir);
File.makeDirectory(runDir + "outputs/");
File.makeDirectory(runDir + "failed/");
File.makeDirectory(runDir + "failed/OPEN_FAIL/");
File.makeDirectory(runDir + "failed/EMPTY/");

logPath = runDir + "log.jsonl";
statePath = runDir + "state.csv";
File.append("input_path,status,output_path,duration_s,timestamp_ms\n", statePath);

// Read manifest
csv = File.openAsString(manifestPath);
lines = split(csv, "\n");
// header: input_path,sigma,threshold,min_size,output_path

setBatchMode(true);
for (i = 1; i < lines.length; i++) {
    if (lengthOf(lines[i]) == 0) continue;
    cols = split(lines[i], ",");
    if (cols.length < 5) continue;

    inputPath  = cols[0];
    sigma      = parseFloat(cols[1]);
    threshold  = cols[2];
    minSize    = parseInt(cols[3]);
    outputPath = cols[4];
    base = File.getNameWithoutExtension(inputPath);

    // Skip if already done
    if (File.exists(outputPath + ".done")) continue;

    t0 = getTime();
    status = "OK";
    errMsg = "";

    if (!File.exists(inputPath)) {
        status = "OPEN_FAIL"; errMsg = "missing";
    } else if (File.length(inputPath) == 0) {
        status = "EMPTY"; errMsg = "zero size";
    } else {
        nBefore = nImages;
        run("Bio-Formats Importer",
            "open=[" + inputPath + "] autoscale color_mode=Default " +
            "view=Hyperstack stack_order=XYCZT");
        if (nImages <= nBefore) {
            status = "OPEN_FAIL"; errMsg = "Bio-Formats null";
        } else {
            // The actual processing
            run("Gaussian Blur...", "sigma=" + sigma);
            setAutoThreshold(threshold + " dark");
            run("Convert to Mask");
            run("Analyze Particles...",
                "size=" + minSize + "-Infinity show=Masks");
            // Atomic write: .tmp + rename
            tmp = outputPath + ".tmp";
            saveAs("Tiff", tmp);
            File.rename(tmp, outputPath);
            File.saveString("ok", outputPath + ".done");
        }
    }
    while (nImages > nBefore) close();

    if (status != "OK") {
        // Quarantine
        qDir = runDir + "failed/" + status + "/";
        File.makeDirectory(qDir);
        File.append("input=" + inputPath + "\nerror=" + errMsg + "\n",
                    qDir + base + ".error.json");
    }
    durMs = getTime() - t0;
    File.append(inputPath + "," + status + "," + outputPath + "," +
                d2s(durMs/1000, 2) + "," + getTime() + "\n", statePath);

    if ((i % 25) == 0) {
        run("Collect Garbage");
        freeMB = (IJ.maxMemory()-IJ.currentMemory())/1048576;
        print("[gc] i=" + i + " free=" + d2s(freeMB,0) + " MB");
    }
    showProgress(i, lines.length-1);
}
setBatchMode(false);
print("Run complete: " + runDir);
```

### §28.3 Python orchestrator

For when you want full control over retry, timeout, and logging from
outside Fiji. Uses `subprocess` to spawn one fresh JVM per file.

```python
#!/usr/bin/env python3
"""orchestrate.py — Python orchestrator for Fiji headless batch."""
from __future__ import annotations
import argparse, csv, datetime, hashlib, json, os, random, shutil, subprocess
import sys, time
from pathlib import Path

FIJI = Path(os.environ.get("FIJI", "./ImageJ-linux64"))
HEAP = os.environ.get("HEAP", "8g")
PER_FILE_TIMEOUT_S = 600
RETRY_MAX = 3

def sha256_prefix(path: Path, n: int = 16) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    return h.hexdigest()[:n]

def utc_now_iso() -> str:
    return datetime.datetime.utcnow().isoformat(timespec="milliseconds") + "Z"

def git_id() -> str:
    try:
        sha = subprocess.check_output(["git","rev-parse","HEAD"], text=True).strip()
        dirty = subprocess.check_output(["git","status","--porcelain"], text=True).strip()
        return sha[:8] + ("-dirty" if dirty else "")
    except Exception: return "no-git"

def is_retriable(err: str) -> bool:
    needles = ("Connection reset","stale file handle","Resource temporarily unavailable",
               "I/O error","being used by another process","Permission denied")
    return any(n in err for n in needles)

def with_backoff(fn, attempts=RETRY_MAX, base=2.0, cap=60.0):
    for k in range(1, attempts + 1):
        try: return fn(), k
        except Exception as e:
            if k == attempts or not is_retriable(str(e)): raise
            d = min(cap, base ** k) + random.uniform(0, 1)
            print(f"[retry {k}/{attempts}] {e} sleeping {d:.1f}s", file=sys.stderr)
            time.sleep(d)

def process_one(input_path: Path, output_path: Path, params: dict, log_dir: Path):
    log_file = log_dir / (input_path.name + ".log")
    args = ",".join(f'{k}="{v}"' for k, v in {
        "input": str(input_path), "output": str(output_path),
        **params,
    }.items())
    cmd = [str(FIJI), "--ij2", "--headless", "--console", f"--mem={HEAP}",
           "--run", "pipeline.groovy", args]
    with log_file.open("w") as f:
        result = subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT,
                                timeout=PER_FILE_TIMEOUT_S, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"Fiji rc={result.returncode}, see {log_file}")

def quarantine(input_path: Path, run_dir: Path, reason: str, error: str, meta: dict):
    qd = run_dir / "failed" / reason
    qd.mkdir(parents=True, exist_ok=True)
    shutil.copy2(input_path, qd / input_path.name)
    sidecar = qd / (input_path.name + ".error.json")
    sidecar.write_text(json.dumps({
        "input": str(input_path), "status": reason, "error": error,
        "timestamp_utc": utc_now_iso(), **meta,
    }, indent=2))

def log_jsonl(jsonl: Path, **fields):
    with jsonl.open("a") as f:
        f.write(json.dumps({"timestamp_utc": utc_now_iso(), **fields}) + "\n")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", required=True, type=Path)
    ap.add_argument("--output-root", required=True, type=Path)
    args = ap.parse_args()

    run_id = datetime.datetime.utcnow().strftime("%Y-%m-%dT%H-%M-%SZ") + "_" + git_id()
    run_dir = args.output_root / run_id
    (run_dir / "outputs").mkdir(parents=True)
    (run_dir / "logs").mkdir()
    log_jsonl_p = run_dir / "log.jsonl"
    state_csv = run_dir / "state.csv"

    # Snapshot script + manifest
    shutil.copy("pipeline.groovy", run_dir / "script.groovy")
    shutil.copy(args.manifest, run_dir / "manifest.csv")
    # Header
    (run_dir / "RUN.md").write_text(
        f"# Run {run_id}\n- Started: {utc_now_iso()}\n- Machine: {os.uname().nodename}\n"
        f"- Git: {git_id()}\n- Heap: {HEAP}\n- Manifest: {args.manifest}\n"
    )
    # State header
    if not state_csv.exists():
        state_csv.write_text("input_path,status,output_path,duration_s,attempts,timestamp_utc\n")

    done = set()
    if state_csv.exists():
        with state_csv.open() as f:
            for row in csv.DictReader(f):
                if row["status"] == "DONE": done.add(row["input_path"])

    with args.manifest.open() as f:
        for row in csv.DictReader(f):
            inp = Path(row["input_path"])
            if str(inp) in done:
                print(f"[skip] {inp}"); continue
            out = run_dir / "outputs" / (inp.stem + "_mask.tif")
            t0 = time.monotonic()
            params = {k: row[k] for k in row if k not in {"input_path","output_path"}}
            try:
                _, attempts = with_backoff(lambda: process_one(inp, out, params, run_dir / "logs"))
                status = "DONE"
                err = ""
            except subprocess.TimeoutExpired as e:
                status = "TIMEOUT"; err = str(e); attempts = 1
                quarantine(inp, run_dir, status, err, {"run_id": run_id})
            except Exception as e:
                status = "EXCEPTION"; err = str(e); attempts = 1
                quarantine(inp, run_dir, status, err, {"run_id": run_id})
            dur = time.monotonic() - t0
            with state_csv.open("a") as sf:
                sf.write(f"{inp},{status},{out if status=='DONE' else ''},{dur:.2f},{attempts},{utc_now_iso()}\n")
            log_jsonl(log_jsonl_p, run_id=run_id, input=str(inp), status=status,
                      duration_s=round(dur, 3), attempts=attempts, error=err)
            print(f"[{status}] {inp.name} ({dur:.1f}s, {attempts} attempts)")

if __name__ == "__main__": main()
```

Run: `python orchestrate.py --manifest manifest.csv --output-root /data/runs/`

For pyimagej-based orchestration (in-process Fiji from Python), see
[bioformats-multiseries-reference.md](bioformats-multiseries-reference.md)
§ on Java APIs.

### §28.4 Snakemake (3-stage pipeline)

```python
# Snakefile — open → segment → measure → aggregate
INPUTS, = glob_wildcards("/data/in/{name}.lif")

rule all:
    input:
        "/data/out/summary.csv",
        expand("/data/out/{name}_mask.tif", name=INPUTS),
        expand("/data/out/{name}_results.csv", name=INPUTS),

rule segment:
    input:  "/data/in/{name}.lif"
    output: "/data/out/{name}_mask.tif"
    log:    "/data/logs/{name}.segment.log"
    threads: 1
    resources: mem_mb=8000, time="00:20:00"
    shell:
        "./ImageJ-linux64 --ij2 --headless --console --mem={resources.mem_mb}m "
        "--run scripts/segment.groovy "
        "'input=\"{input}\",output=\"{output}\"' > {log} 2>&1"

rule measure:
    input:  mask="/data/out/{name}_mask.tif", img="/data/in/{name}.lif"
    output: "/data/out/{name}_results.csv"
    log:    "/data/logs/{name}.measure.log"
    threads: 1
    resources: mem_mb=4000, time="00:10:00"
    shell:
        "./ImageJ-linux64 --ij2 --headless --console --mem={resources.mem_mb}m "
        "--run scripts/measure.groovy "
        "'mask=\"{input.mask}\",img=\"{input.img}\",output=\"{output}\"' > {log} 2>&1"

rule aggregate:
    input:  expand("/data/out/{name}_results.csv", name=INPUTS)
    output: "/data/out/summary.csv"
    run:
        import pandas as pd
        dfs = [pd.read_csv(p).assign(source=p) for p in input]
        pd.concat(dfs).to_csv(output[0], index=False)
```

Run on a workstation:

```bash
snakemake -j 8 --use-conda
```

Run on SLURM:

```bash
snakemake -j 200 \
    --cluster "sbatch --mem={resources.mem_mb} --time={resources.time} --cpus-per-task={threads}" \
    --jobs 50 --keep-going --rerun-incomplete
```

Properties:
- **Idempotent**: outputs are file-system entries; existing outputs skip
  their rule.
- **Resumable**: `--rerun-incomplete` finds half-finished outputs and
  redoes them. `--keep-going` continues past failures so one bad LIF
  doesn't kill the rest.
- **Per-rule logs**: every rule writes its own log; failures are
  triagable.
- **Cluster-aware**: switch from `-j 8` (local) to `--cluster sbatch`
  (HPC) without changing rule code.

---

## §29 Common Failure Modes

A field guide. Match the symptom, find the cause, apply the fix.

### §29.1 Out of memory mid-run

**Symptom**: `java.lang.OutOfMemoryError: Java heap space` after N
files (N > 0). The first hundred files were fine.

**Diagnose**:

1. Plot `IJ.currentMemory()` per file. Steady = good. Upward drift =
   leak. Sudden spike = a particular file is too big.
2. Check `nImages` between files — should be 0 after `close("*")`.
3. Check ROI Manager count — should be 0 after `roiManager("Reset")`.
4. Look for `Plot.create(...)` without `Plot.show(); close();`.
5. Look for plugin output windows you forgot to close (Histogram, Log).

**Fixes** by cause:

| Cause | Fix |
|---|---|
| Image leak (close not reached on error path) | Wrap in `try`/`finally` (Groovy) or guard with `nBefore` (macro) — §19.1 |
| Results table grows unbounded | Save & clear when `nResults > 5000` — §9 |
| ROIs accumulate | `roiManager("Reset")` at start of each file |
| One pathological file | Bound input dimensions before opening — §8.2 |
| GC not keeping up | `run("Collect Garbage")` every 10 files — §21.3 |
| True memory pressure | Increase `-Xmx`, switch to per-file fresh JVM — §21.5 |
| SCIFIO file handle leak | Disable SCIFIO in Bio-Formats prefs — §21.3 |

The distinction "diagnose vs design": **diagnose** if memory was fine
yesterday and broke today (something accumulating). **Design** if no
single file fits in heap (need virtual stacks, chunking, or a fresh-JVM
approach).

### §29.2 JVM crashes (`hs_err_pid<N>.log`)

**Symptom**: Fiji exits with no Java exception. A file like
`hs_err_pid42523.log` appears in the working directory.

**Read the header**:

```
#  SIGSEGV (0xb) at pc=0x00007f0b3a... ...
#  Problematic frame:
#  C  [libjnidispatch.so+0x...]                  <- native library!
#  J  ij.process.ImageProcessor.getPixel(II)I    <- Java code
#  V  [libjvm.so+0x...]                          <- JVM internal
```

| Frame prefix | Meaning |
|---|---|
| `C` | Native (C/C++) library — typically a JNI plugin (CLIJ2, Bio-Formats native, deconvolution, GPU drivers) |
| `J` | Java (JIT-compiled) — usually a real Java bug, often in third-party plugin |
| `V` | JVM internal — often GC thread; correlated with OOM or GC pressure |
| `j` | Java (interpreted) — same diagnosis as `J` |

**Fixes** by frame:

- `C [libjvm.so]`: probably OOM during GC. Increase `-Xmx`, switch GC.
- `C [libfoo.so]` (e.g. `libcuda.so`, `libOpenCL.so`, native imaging
  libs): native-lib bug or driver issue. Update the driver, downgrade
  the plugin, or restart Fiji periodically (§21.4).
- `J [ij.plugin...]`: ImageJ bug. Report on image.sc with the hs_err.
- `J [com.thirdparty...]`: third-party plugin bug. Check the plugin's
  GitHub issues.

Where the file lands: working directory by default; falls back to
`$TMPDIR`/`%TEMP%` if not writable. Force the location with:

```bash
./ImageJ-linux64 -- -XX:ErrorFile=/runs/hs_err_%p.log
```

`%p` expands to the process PID, `%%` to literal `%`.

A pure Java `OutOfMemoryError` does **not** produce `hs_err_pid` — it's
a Java exception, not a JVM crash. Look at stderr instead.

### §29.3 Filesystem race conditions on shared storage

**Symptom**: Two parallel workers process the same file. One writes,
the other reads a half-written output. Or both write to the same temp
path and clobber each other.

**Causes**:
- Manifest split incorrectly — two workers given overlapping rows.
- Both workers write `<base>.tmp` for the same `<base>` then rename.
- `getFileList()` includes a file the other worker just wrote.

**Fixes**:
- Make manifest splits explicit and disjoint. Snakemake's
  `glob_wildcards` is safer than ad-hoc shell `for` loops.
- Make the temp path **per worker**: `<base>.<pid>.tmp` instead of
  `<base>.tmp`.
- Process a snapshot of the input list at start, not the live list. In
  Python: `inputs = sorted(glob.glob(...))` once, then iterate.

### §29.4 Network drive timeouts

**Symptom**: Random `java.io.IOException: stale file handle`,
`Connection reset`, `The semaphore timeout period has expired` (Windows
SMB) — typically after the share has been idle for minutes.

**Causes**:
- SMB / NFS automount unmounted under idle.
- VPN flapped.
- Antivirus on Windows held a lock during scan.

**Fixes**:
- Add retry-with-backoff (§19.3). Most of these are genuinely
  transient.
- Copy inputs to local SSD before processing for very long runs. Disk
  is cheap, time isn't.
- Mount with longer timeouts (`-o intr,timeo=300` on NFS,
  `-o resilient` on Windows mount).

### §29.5 Off-by-one in series indexing

The single most common Bio-Formats bug. Cross-link: §6, §15,
[bioformats-multiseries-reference.md](bioformats-multiseries-reference.md).

```javascript
// WRONG: mixing 0-indexed extension with 1-indexed import string
Ext.setSeries(s);                                       // 0-indexed
run("Bio-Formats Importer", "... series_" + s + ...);   // 1-indexed!
// fix: series_" + (s + 1) + "
```

A corollary bug: skipping a series due to a continue but forgetting to
`Ext.close()` the reader, leaving it open into the next iteration.

### §29.6 Locale-dependent number parsing

**Symptom**: CSVs read fine on dev machine, break on the lab machine.
Or `parseFloat("1,5")` returns 1 instead of 1.5.

**Cause**: ImageJ's macro `parseFloat` and `d2s` are locale-sensitive
in some Fiji builds. On a German Windows install with `,` as decimal
separator, `d2s(1.5, 2)` may produce `"1,50"`. Pandas reading that CSV
on a US machine gets a string.

**Fixes**:
- Always force locale in headless: `export LC_ALL=C; export LANG=C`
  before launching Fiji.
- For Windows: `set LANG=C` in the batch script.
- In Groovy: `String.format(Locale.US, "%.3f", v)` instead of `${v}`.
- For CSV writes: use `,` as separator only with `.` decimals, or
  switch to `;` separator for `,` decimals — pick one and document it
  in `RUN.md`.

```javascript
// Macro: format with explicit decimal point
function fmt(v) {
    s = "" + v;                         // toString
    s = replace(s, ",", ".");           // normalize
    return s;
}
```

### §29.7 Windows path quirks in headless

Common pitfalls:

```bat
:: Backslashes need doubling in Java strings
--run script.ijm "input=\"C:\\data\\in\\sample.lif\""

:: Or use forward slashes (Java accepts them on Windows):
--run script.ijm "input=\"C:/data/in/sample.lif\""

:: Spaces in paths require BOTH outer single-quote AND brackets:
--run script.ijm "input=\"C:/Program Files/data/sample.lif\""
```

Bio-Formats Importer's `open=[...]` syntax handles spaces; plain `open()`
also handles spaces; CLI argument parsing is what breaks. Always use
forward slashes and outer double quotes for safety.

### §29.8 Empty Results table after batch

**Symptom**: `python ij.py results` returns empty CSV; the macro printed
`nResults=0`.

**Causes**:
1. `run("Clear Results")` was called too late (after the last `Measure`).
2. `Set Measurements...` was wrong — measured into a different
   destination.
3. Plugin reported into its own table (e.g. `Analyze Particles...
   summarize` writes to `Summary` table, not `Results`).

**Fix**: explicit `selectWindow("Results"); saveAs(...)` in the macro;
verify with `print("nRows=" + nResults);` before save.

---

## §30 Post-Batch Validation

A batch is not done when the macro exits. It's done when validation
passes.

### §30.1 Output count vs input count

The simplest possible check:

```bash
n_in=$(find /data/in -name '*.lif' | wc -l)
n_out=$(find /data/out -name '*_mask.tif' | wc -l)
n_failed=$(find /data/runs/.../failed -mindepth 2 -maxdepth 2 | wc -l)
[ $((n_out + n_failed)) -eq $n_in ] && echo "accounted for" || echo "MISSING"
```

Every input should be either in outputs or in failed. If
`n_out + n_failed < n_in`, files were silently dropped — find them and
investigate.

### §30.2 Statistical sanity bounds

Run a summary over the per-file results and compare to bounds you
expect from biology / acquisition:

```python
import pandas as pd
df = pd.read_json("log.jsonl", lines=True)
summary = df.groupby("status").agg(
    n=("input", "count"),
    duration_p50=("duration_s", "median"),
    duration_p95=("duration_s", lambda s: s.quantile(0.95)),
    duration_max=("duration_s", "max"),
    n_objects_mean=("n_objects", "mean"),
)
print(summary)
```

Bounds you should sanity-check by domain:

| Quantity | Suspicious if... |
|---|---|
| Per-file duration | p95 > 5× p50 (heavy tail = some files different) |
| Object count | Coefficient of variation > 100% (algorithm unstable) |
| Object count = 0 | A file with zero objects when others have hundreds = segmentation failed silently |
| Pixel intensity mean | Drifts monotonically with file index = bleaching not accounted for |
| Output file size | Wildly different between similar inputs = algorithm hit different paths |

Cross-link: this project's `auditor.py` (`agent/auditor.py`) does a
generic measurement-sanity audit on the active Results table. Run it
on the aggregated batch output:

```bash
python auditor.py --csv /data/runs/.../summary.csv
```

### §30.3 Visual sampling (1%)

Statistics catch numeric outliers; eyeballs catch "the segmentation is
finding background as objects". Sample 1% of outputs, view them.

```python
import random, shutil, glob
outputs = sorted(glob.glob("/data/runs/.../outputs/*_mask.tif"))
sample = random.sample(outputs, max(1, len(outputs) // 100))
out_dir = "/data/runs/.../qc_sample/"
os.makedirs(out_dir, exist_ok=True)
for p in sample:
    shutil.copy(p, out_dir)
print(f"Sampled {len(sample)}/{len(outputs)} into {out_dir}")
```

Open the sample in Fiji; flip through them. Look for:
- Empty masks where there should be objects.
- Masks with one giant blob spanning the whole image (over-thresholded).
- Masks with thousands of single-pixel "objects" (under-thresholded).
- Edge artifacts the algorithm missed.

This step takes 15 minutes and catches systemic algorithm failure that
no statistic flags.

### §30.4 Auditor pattern

The full audit pattern: a separate script that reads the batch outputs
and writes an `audit_report.md` summarising:

- File accounting (n_inputs, n_outputs, n_failed, n_missing).
- Failure breakdown by reason.
- Duration distribution.
- Per-condition / per-batch summary statistics.
- List of outliers worth inspecting (top-K longest, top-K largest, top-K
  smallest object counts).
- Visual sample paths.

```python
# audit.py
def audit(run_dir: Path, manifest: Path) -> dict:
    inputs = pd.read_csv(manifest)
    state = pd.read_csv(run_dir / "state.csv")
    log = pd.read_json(run_dir / "log.jsonl", lines=True)
    n_inputs = len(inputs)
    by_status = state["status"].value_counts().to_dict()
    n_done = by_status.get("DONE", 0)
    n_fail = sum(v for k, v in by_status.items() if k != "DONE")
    return {
        "n_inputs": n_inputs,
        "n_done": n_done,
        "n_failed": n_fail,
        "n_missing": n_inputs - n_done - n_fail,
        "fail_reasons": {k: v for k, v in by_status.items() if k != "DONE"},
        "duration_p50": log["duration_s"].median(),
        "duration_p95": log["duration_s"].quantile(0.95),
        "duration_max": log["duration_s"].max(),
        "outliers_long": log.nlargest(10, "duration_s")["input"].tolist(),
    }
```

Cross-link: this project's `auditor.py` and `practice.py` already
implement variations of this. Use them as starting points, not
replacements.

---

## §31 TCP Server Integration

This project exposes Fiji over TCP on port 7746 (§14). Batches driven
through the TCP server have one architectural difference from headless
batches: the **JVM is long-lived**. That changes which patterns apply.

### §31.1 What's different over TCP

| Aspect | Headless | TCP-driven |
|---|---|---|
| JVM lifetime | One per invocation | Long-lived, single |
| Restart strategy | New process per file (§21.5) | Cannot restart from inside; ask user to restart |
| Per-file timeout | `subprocess.run(timeout=...)` | Socket timeout + macro time check |
| Parallelism | N processes (§25.2) | Sequential (one TCP socket = one macro at a time) |
| Health check | Per-process (free) | Periodic, mid-session (§21.4) |
| Provenance | `RUN.md` at start | Same, but session boundary is fuzzier |

The TCP server is best for **interactive batches** where the user is
present, exploring parameters, and the run is small (tens to a few
hundred files). For overnight jobs over thousands of files, switch to
headless (§24, §28).

### §31.2 Driving a batch from the agent

The standard pattern:

```python
# From the agent (this project)
import subprocess, json
def macro(code, timeout=120):
    p = subprocess.run(["python", "ij.py", "macro", code],
                       capture_output=True, text=True, timeout=timeout)
    return p.stdout

# Build a manifest-aware macro and send it
batch_macro = """
manifestPath = "/data/manifest.csv";
runDir = "/data/runs/" + getTime() + "/";
File.makeDirectory(runDir);
csv = File.openAsString(manifestPath);
lines = split(csv, "\\n");
setBatchMode(true);
for (i = 1; i < lines.length; i++) {
    if (lengthOf(lines[i]) == 0) continue;
    cols = split(lines[i], ",");
    inputPath = cols[0]; outputPath = cols[1];
    if (File.exists(outputPath + ".done")) continue;
    open(inputPath);
    run("Gaussian Blur...", "sigma=2");
    setAutoThreshold("Otsu dark");
    run("Convert to Mask");
    saveAs("Tiff", outputPath + ".tmp");
    File.rename(outputPath + ".tmp", outputPath);
    File.saveString("ok", outputPath + ".done");
    close("*");
    print("BATCH_DONE|" + inputPath);
}
setBatchMode(false);
"""
result = macro(batch_macro, timeout=3600)
```

### §31.3 Chunking to fit TCP timeouts

The default socket timeout is 30 seconds; long batches exceed it. Two
workarounds (§14.2 covered the first):

```python
# Chunk by file count
chunk_size = 25
for start in range(0, n_files, chunk_size):
    chunk = manifest_rows[start:start + chunk_size]
    macro_code = build_chunk_macro(chunk)
    macro(macro_code, timeout=600)
    # Check progress between chunks
    state = subprocess.check_output(["python", "ij.py", "results"])
```

```python
# Or fire-and-forget + poll
import time
macro(long_macro, timeout=10)            # returns ack quickly
while True:
    log = subprocess.check_output(["python", "ij.py", "log"], text=True)
    if "BATCH_COMPLETE" in log: break
    time.sleep(5)
```

The second pattern requires the macro to print a sentinel
`BATCH_COMPLETE` line at the very end. The agent polls
`python ij.py log` every 5 seconds.

### §31.4 Capture-after-each-step pattern

For agent-driven exploratory batches (rare files, small N), the
"capture after each step" pattern from `agent/CLAUDE.md` is the right
shape:

```
for each input file:
    1. python ij.py macro 'open("..."); ...'
    2. python ij.py capture step_open
    3. (Read PNG to verify)
    4. python ij.py macro 'run("Gaussian Blur..."); ...'
    5. python ij.py capture step_blur
    6. ...
    7. python ij.py results
    8. python auditor.py
```

Slow but observable. Useful when developing the pipeline, not when
running it for production.

### §31.5 BATCH_* sentinel convention

Agreed-on sentinels the agent parses out of `python ij.py log`:

| Sentinel | Use |
|---|---|
| `BATCH_PROGRESS\|i\|n\|filename` | Progress per file (§12) |
| `BATCH_DONE\|filename` | One file completed |
| `BATCH_FAIL\|filename\|reason` | One file failed |
| `BATCH_RESTART_HINT\|details` | Macro suggests Fiji restart (§21.4) |
| `BATCH_COMPLETE\|n_done\|n_fail` | Batch finished |

These are convention, not protocol; pick the names you use, document
them in your RUN.md, and stay consistent. The agent searches the log
with simple string matching.

### §31.6 When to switch to headless

If any of the following are true, move the batch from TCP to headless
(§24):

- The run will exceed 1 hour wall time.
- The run will process > 500 files.
- The run will be unattended overnight.
- The run is in CI / on a cluster.
- You want fresh-JVM-per-file isolation (§21.5).
- You want parallelism > 1 (§25.2).

The TCP server is for interactive collaboration with the agent, not
for production-scale batch.

---

## §32 Cross-References

- **Bio-Formats series details** —
  [bioformats-multiseries-reference.md](bioformats-multiseries-reference.md)
- **JVM tuning, virtual stacks, GPU** —
  [large-dataset-optimization-reference.md](large-dataset-optimization-reference.md)
- **Macro language reference** —
  [macro-reference.md](macro-reference.md)
- **Groovy / Jython scripting** —
  [fiji-scripting-reference.md](fiji-scripting-reference.md)
- **Pipeline design (decision points, validation)** —
  [pipeline-construction-reference.md](pipeline-construction-reference.md)
- **File formats and savers** —
  [file-formats-saving-reference.md](file-formats-saving-reference.md)
- **GUI dialog interaction (for headless replacement)** —
  [gui-interaction-reference.md](gui-interaction-reference.md)
- **Post-batch sanity audit** — `agent/auditor.py`, `agent/practice.py`
