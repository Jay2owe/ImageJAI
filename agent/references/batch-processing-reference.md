# Batch Processing & Bio-Formats Reference

Reference for processing multiple images in ImageJ/Fiji via macro language.
All macros work with: `python ij.py macro 'CODE_HERE'`

---

## 1. Quick Start

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

## 2. Folder Iteration Patterns

### File listing and filtering

```javascript
dir = "/path/to/folder/";
list = getFileList(dir);
Array.sort(list);  // alphabetical sort

// getFileList() returns NAMES only — prepend dir for full path
// Subdirectory names end with "/"
// ImageJ accepts "/" on all platforms including Windows
```

### Extension and file validation

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

### Recursive traversal

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

### File name parts

| Function | Example input | Returns |
|----------|--------------|---------|
| `File.getName(path)` | `/data/exp/s_DAPI_001.tif` | `s_DAPI_001.tif` |
| `File.getNameWithoutExtension(path)` | same | `s_DAPI_001` |
| `File.getDirectory(path)` | same | `/data/exp/` |
| `split(name, "_")` | `s_DAPI_001` | `["s","DAPI","001"]` |

### Matched pairs

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

### Spaces in paths

Bio-Formats REQUIRES square brackets: `open=[/path/with spaces/file.nd2]`
Plain `open()` and `saveAs()` handle spaces natively without brackets.

---

## 3. setBatchMode

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

### Closing images

```javascript
close();           // active image
close("mask");     // by name
close("working_*");// wildcard
close("*");        // all images
close("\\Others"); // all except active
while (nImages > 0) { close(); }  // guaranteed cleanup
```

---

## 4. Bio-Formats Importer -- Complete Reference

### Basic syntax

```javascript
run("Bio-Formats Importer",
    "open=[/path/to/file.nd2] autoscale color_mode=Default " +
    "view=Hyperstack stack_order=XYCZT");
```

Path MUST be wrapped in square brackets: `open=[path]`

### Display options

| Parameter | Values | Default | Description |
|-----------|--------|---------|-------------|
| `autoscale` | (flag) | off | Auto-adjust brightness/contrast |
| `color_mode` | `Default`, `Composite`, `Colorized`, `Grayscale`, `Custom` | `Default` | Channel display mode |
| `view` | `Hyperstack`, `Standard ImageJ`, `[Image5D]`, `[View5D]` | `Hyperstack` | Window type |
| `stack_order` | `XYCZT`, `XYTZC`, `XYZCT`, `XYTCZ`, `XYZTC`, `XYCTZ`, `Default` | `Default` | Dimension ordering |

**color_mode**: `Default` = composite for multi-channel, grayscale for single. `Composite` = all channels overlaid. `Colorized` = each channel in own window. `Grayscale` = all channels grayscale.

**view**: `Hyperstack` (recommended) = single window with C/Z/T sliders. `Standard ImageJ` = flat stack.

### Data handling

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

### Metadata

| Parameter | Description |
|-----------|-------------|
| `display_metadata` | Show OME metadata window |
| `display_ome-xml` | Show raw OME-XML |
| `display_rois` | Import ROIs from file |
| `rois_import` | `[ROI manager]` or `[Overlay]` |

### Common import strings

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

### Virtual stacks

Load planes from disk on demand. Essential for large files.

| Limitation | Detail |
|------------|--------|
| Read-only | Cannot modify pixel data |
| Slower scrolling | Reads from disk each time |
| Some plugins refuse | Need editable data |
| No undo | No history maintained |

To make editable: `run("Duplicate...", "duplicate");` then close the virtual stack.

### Bio-Formats Exporter

```javascript
run("Bio-Formats Exporter",
    "save=[/path/to/output.ome.tif] compression=LZW");
// Compression options: Uncompressed, LZW, JPEG, JPEG-2000, JPEG-2000 Lossy, zlib
// For files > 4 GB, use .ome.btf extension (BigTIFF)
```

---

## 5. Bio-Formats Macro Extensions

Query file metadata WITHOUT opening the image. Essential for batch -- peek at dimensions and series count before opening.

### Initialisation

```javascript
run("Bio-Formats Macro Extensions");  // required once
Ext.setId("/path/to/file.nd2");       // set file to query
// ... query ...
Ext.close();                           // release file handle
```

### Complete function reference

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

### Inspect and filter series

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

## 6. File Format Patterns

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

## 7. Memory Management

### Memory per image

| Bit depth | Formula |
|-----------|---------|
| 8-bit | W x H x Z bytes |
| 16-bit | W x H x Z x 2 bytes |
| 32-bit / RGB | W x H x Z x 4 bytes |

Example: 2048 x 2048 x 50 x 16-bit ~ 400 MB per image.

### Estimating before opening

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

### Memory-efficient batch template

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

### Memory tips

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

## 8. Results Table Management

### Accumulate across images

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

### Reading results programmatically

```javascript
area = getResult("Area", 0);        // specific cell
n = nResults;                        // row count
label = getResultString("Label", 0); // string column

// All values into array + stats
areas = newArray(nResults);
for (row = 0; row < nResults; row++) areas[row] = getResult("Area", row);
Array.getStatistics(areas, min, max, mean, stdDev);
```

### Custom tables (Table.* functions, since 1.52a)

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

## 9. ROI Manager in Batch

### Core operations

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

### Batch pattern: save ROIs per image

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

### Reuse saved ROIs on different channel

```javascript
open(dir + list[i]);
roiManager("Reset");
roiManager("Open", roiDir + nameNoExt + "_rois.zip");
roiManager("Deselect");
roiManager("Measure");
close();
```

---

## 10. Error Handling

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

### Skip-and-continue with error logging

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

### Timeout tracking

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

## 11. Progress & Logging

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

## 12. Batch Templates

### Template 1: Threshold + Analyze Particles + save outlines

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

### Template 2: Multi-channel split + per-channel measurement

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

### Template 3: Multi-series .lif iteration

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

### Template 4: Matched pairs (segment DAPI, measure marker)

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

### Template 5: Condition-based (subfolders = groups)

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

## 13. Agent-Specific Patterns

### Agent batch workflow

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

### TCP timeout strategies

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

### Batch TCP command

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

## 14. Common Problems & Solutions

| Problem | Cause | Solution |
|---------|-------|---------|
| Out of memory | Missing `close()`, intermediates accumulate | `while (nImages > 0) { close(); }` + `run("Collect Garbage")` every ~10 images |
| Hidden/junk files processed | .DS_Store, Thumbs.db | Use `isValidImageFile()` filter function |
| Results table slow | Too many rows | Save & clear when `nResults > 5000` |
| Image title conflicts | Duplicate names | Use `getImageID()` + `selectImage(id)` instead of `selectWindow()` |
| Inconsistent dimensions | Mixed image sizes | Check dims on first image, warn on mismatch |
| Bio-Formats dialog blocks batch | Series selector appears | Specify `series_N` explicitly, or use Windowless Importer |
| Slow performance | Display updates, large files, network drive | `setBatchMode(true)`, virtual stacks, copy to local, convert to TIFF first |
| Macro aborts on one bad file | No try/catch | Use `safeOpen()` guard function |
| Series indexing confusion | `Ext.setSeries(0)` vs `series_1` | Extensions 0-indexed, import string 1-indexed: `sNum = s + 1` |
| Headless Bio-Formats fails | Requires AWT classes | Use `bfconvert` CLI tool instead |

### Large multi-series memory strategy

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

### Skip already-processed files

```javascript
outFile = output + "result_" + list[i];
if (File.exists(outFile)) continue;  // already processed
```

### Dynamic Bio-Formats import string

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

## Appendix: Utility Functions

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

## Appendix: Batch Decision Tree

```
Single multi-series file (.lif, .nd2)?
  YES -> Iterate series with Bio-Formats Extensions (Template 3)
  NO  -> Files in flat folder?
    YES -> Simple loop (Template 1)
    NO  -> Subfolders = conditions?
      YES -> Condition-based (Template 5)
      NO  -> Recursive traversal (Section 2)

Files paired (DAPI + marker)?  -> Template 4
Need to compare methods?       -> Parameter sweep (explore_thresholds)
Z-stack data?                  -> Project first, then analyse
Files very large (>500 MB)?    -> Virtual stacks, one at a time, garbage collect
```
