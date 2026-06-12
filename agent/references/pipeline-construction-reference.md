# Pipeline Construction Reference

Designing, building, and executing multi-step image analysis pipelines in
ImageJ/Fiji. Covers the batch skeleton, script parameters, memory and quality
gates, multi-channel/Z-stack flows, Bio-Formats series iteration, matched
file pairs, error handling, progress reporting, decision points, parameter
sweeps, CLIJ2 GPU pipelines, Groovy alternatives, reproducibility, export,
and the ImageJAI `PipelineBuilder` / `<pipeline>` XML / `run_pipeline` TCP
protocol / recipe system.

Sources: `imagej.net/tutorials/batch-processing-with-ij-macro`,
`imagej.net/ij/macros/BatchProcessFolders.txt`,
`imagej.net/ij/developer/macro/functions.html`,
`imagej.net/scripting/parameters`, `clij.github.io`, Bio-Formats macro
extensions Javadoc, scikit-image regionprops, PyImageJ docs, ImageJ Auto
Threshold plugin. ImageJAI-specific sections cross-reference
`src/main/java/imagejai/engine/PipelineBuilder.java`.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript.
`python ij.py raw '{"command": "run_pipeline", "steps": [...]}'` — run a
multi-step pipeline via TCP.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "What's the skeleton of a batch pipeline?" | §2 |
| "How do I filter files by name / extension?" | §2.2 |
| "How do I add parameters / a dialog to my pipeline?" | §2.3 |
| "How do I run things faster in batch mode?" | §3.1 |
| "When should I call `run('Collect Garbage')`?" | §3.3 |
| "How do I validate an image before processing?" | §4 |
| "Should I clear Results between images?" | §5 |
| "How do I pull a value out of the Summary table?" | §5.1 |
| "How do I process multi-channel / Z-stacks / interleaved?" | §6.1, §6.2, §6.4 |
| "How do I segment on one channel and measure on another?" | §6.3, §24 |
| "How do I iterate every series in a .lif / .nd2 / .czi?" | §7 |
| "How do I process DAPI/GFP matched pairs?" | §8 |
| "There's no try/catch — how do I handle errors?" | §9 |
| "How do I show progress and ETA?" | §10 |
| "Which threshold method should I pick?" | §11.1 |
| "How do I sweep parameters (sigma, threshold, size)?" | §12 |
| "How do I write a GPU-accelerated pipeline?" | §13 |
| "When should I use Groovy instead of macro?" | §14 |
| "What's the CellProfiler equivalent in ImageJ?" | §15.1 |
| "How do I make a pipeline reproducible?" | §16 |
| "How does `run_pipeline` work over TCP?" | §20 |
| "What's the `<pipeline>` XML format the LLM emits?" | §19 |
| "What does a recipe YAML file look like?" | §21 |
| "What's the agent's visual feedback loop?" | §22 |
| "Agent CLI vs in-plugin pipeline — what's the difference?" | §23 |
| "What are the canonical step patterns?" | §24 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section covering each function, command, plugin,
concept, or YAML key. Use `grep -n '`<term>`' pipeline-construction-reference.md`
to jump.

### A

`Adaptive processing` §11.2 · `Analyze Particles` §6.3, §8, §24 · `Agent vs Plugin paths` §23 · `Auto Threshold` §11.1, §14

### B

`Batch mode` §3.1 · `batch wrapping` §23 · `bitDepth` §4, §11.2 · `Bio-Formats Importer` §7 · `Bio-Formats Macro Extensions` §7 · `Branching pattern` §2.1

### C

`capture_after` §21 · `capture (visual feedback)` §22 · `CellProfiler translation` §15.1 · `CLIJ2 Macro Extensions` §13 · `CLIJ2_clear` §13 · `CLIJ2_connectedComponentsLabelingBox` §13 · `CLIJ2_gaussianBlur2D/3D` §13 · `CLIJ2_pull` §13 · `CLIJ2_push` §13 · `CLIJ2_release` §13 · `CLIJ2_statisticsOfLabelledPixels` §13 · `CLIJ2_thresholdOtsu` §13 · `close("*") / close("\\Others") / close("temp_*")` §3.2 · `Collect Garbage` §3.3, §9.1 · `Convert to Mask` §9, §11.1, §19, §24 · `CTCF` §24

### D

`d2s` §12 · `decision_logic` §21 · `decision_point` §21 · `Deinterleave` §6.4 · `difficulty (recipe)` §21 · `Duplicate` §24 · `domain (recipe)` §21

### E

`Edge case handling` §4.1 · `endsWith` §2, §2.2 · `ETA calculation` §10.1 · `exit` §4, §9 · `Ext.close` §7.1 · `Ext.closeFileOnly` §7.1 · `Ext.getCurrentFile` §7.1 · `Ext.getDimensionOrder` §7.1 · `Ext.getEffectiveSizeC` §7.1 · `Ext.getFormat` §7.1 · `Ext.getImageCount` §7.1 · `Ext.getImageCreationDate` §7.1 · `Ext.getIndex` §7.1 · `Ext.getMetadataValue` §7.1 · `Ext.getPixelsPhysicalSizeX/Y/Z` §7.1 · `Ext.getPixelsTimeIncrement` §7.1 · `Ext.getPixelType` §7.1 · `Ext.getPlanePositionX/Y/Z` §7.1 · `Ext.getPlaneTimingDeltaT` §7.1 · `Ext.getPlaneTimingExposureTime` §7.1 · `Ext.getRGBChannelCount` §7.1 · `Ext.getSeries` §7, §7.1 · `Ext.getSeriesCount` §7, §7.1 · `Ext.getSeriesMetadataValue` §7.1 · `Ext.getSeriesName` §7, §7.1 · `Ext.getSizeX/Y/Z/C/T` §7.1 · `Ext.getZCTCoords` §7.1 · `Ext.isIndexed` §7.1 · `Ext.isThisType` §7.1 · `Ext.openImagePlus` §7.1 · `Ext.setId` §7, §7.1 · `Ext.setSeries` §7, §7.1

### F

`File.close` §5, §9.1, §16.1 · `File.exists` §4, §8, §8.1, §9 · `File.getNameWithoutExtension` §5 · `File.open` §5, §9.1, §16.1 · `File filtering` §2.2 · `foregroundPercent` §4, §12

### G

`getDateAndTime` §16.1 · `getDimensions` §4, §6.1 · `getDirectory` §2 · `getFileList` §2, §8 · `getImageID` §8 · `getList("threshold.methods")` §11.1, §12 · `getResult` §5.1, §10 · `getStatistics` §4, §12 · `getTime` §10.1 · `getTitle` §4, §13 · `getVersion` §16.1 · `getVoxelSize` §4 · `Groovy pipeline` §14 · `Groovy pipeline example` §14.1

### H

`Hypothesis agent pipeline` §19.1

### I

`IJ.currentMemory` §3.3 · `IJ.log` §14.1 · `IJ.maxMemory` §3.3 · `IJ.openImage` §14.1 · `IJ.redirectErrorMessages` §4.1, §9, §9.1 · `IJ.renameResults` §5, §5.1 · `IJ.run` §14.1 · `IJ.showProgress` §14.1 · `imageCalculator` §24 · `ImagePlus (script param)` §2.3 · `indexOf` §2.2 · `Input validation` §4 · `Integrated density (CTCF)` §24

### L

`Linear pattern` §2.1 · `LLM response format` §19

### M

`Matched file pair processing` §8 · `Measurement agent pipeline` §19.1 · `Memory management` §3 · `Memory monitoring` §3.3 · `Merge Channels` §6.1

### N

`nImages` §4, §9, §9.1 · `nResults` §4, §9

### O

`Output (script param)` §2.3 · `Otsu` §11.1

### P

`Parameters (recipe)` §21 · `Parameter logging` §16.1 · `Parameter substitution (${})` §21 · `Parameter sweeps` §12 · `parsePipeline` §18.1 · `Pattern choices (Linear / Branching / Recursive)` §2.1 · `Pipeline (class)` §18, §18.1 · `Pipeline.exportAsMacro` §18.2 · `Pipeline.getStatusSummary` §18.2 · `PipelineBuilder` §18 · `PipelineBuilder.createBatchMacro` §18.1, §23 · `PipelineBuilder.executePipeline` §18.1 · `PipelineBuilder.resumePipeline` §18.1 · `PipelineBuilder.retryStep` §18.1 · `PipelineBuilder.savePipeline` §18.1 · `PipelineCallback` §18.1 · `PipelineStep fields` §18 · `preconditions (recipe)` §21 · `print` §10 · `print("\\Clear")` §10 · `print("\\Update:text")` §10 · `print("[Window Name]", ...)` §10 · `Progress reporting` §10 · `PyImageJ bridge` §15.3 · `Python/scikit-image pipeline` §15.2

### Q

`Quality gates` §4.1

### R

`Recipe schema` §21 · `Recipe discovery` §21.1 · `Recursive folder pattern` §2.1 · `redirect= (Set Measurements)` §6.3, §24 · `regionprops_table` §15.2 · `replace` §8 · `Reproducibility checklist` §16.2 · `Results table management` §5 · `Robust pipeline wrapper` §9.1 · `roiManager("count")` §4, §9 · `roiManager("Measure")` §6.3, §8, §15.1 · `roiManager("Reset")` §8 · `run_pipeline` §20 · `run_pipeline (failure response)` §20.2 · `run_pipeline (success response)` §20.1

### S

`saveAs("Results", ...)` §2, §5, §15.1 · `saveAs("Tiff", ...)` §2, §9.1 · `Script Parameters` §2.3 · `Segmentation agent pipeline` §19.1 · `selectImage` §3.2, §8 · `selectWindow` §3.2, §6.1 · `Separate folder pairs` §8.1 · `setAutoThreshold` §4.1, §8, §9, §11.1, §12, §19 · `setBatchMode` §3.1 · `setResult` §5, §5.1 · `Set Measurements` §2, §6.3, §24 · `showProgress` §2, §9.1, §10 · `showStatus` §10, §10.1 · `Specialist agent pipelines` §19.1 · `Split Channels` §6.1, §6.2, §24 · `startsWith` §2.2 · `steps (recipe)` §21 · `Summary (table)` §5.1

### T

`tags (recipe)` §21 · `Triangle (threshold)` §11.1

### U

`updateResults` §5

### V

`validate (recipe)` §21 · `validateImage` §4 · `Visualization agent pipeline` §19.1 · `Visual feedback loop` §22

### W

`Watershed` §8, §24 · `Well plate processing` §8.2

### X

`<pipeline>` XML format §19 · `<step description="...">` §19

### Z

`Z Project` §6.2 · `Z-stack pipeline` §6.2

---

## §2 Core Pipeline Structure

Every batch pipeline follows this skeleton:

```javascript
run("Set Measurements...", "area mean integrated redirect=None decimal=3");
inputDir = getDirectory("Select Input Directory");
outputDir = getDirectory("Select Output Directory");
list = getFileList(inputDir);
setBatchMode(true);

for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif")) {
        processFile(inputDir, outputDir, list[i]);
    }
    showProgress(i, list.length);
}
setBatchMode(false);
saveAs("Results", outputDir + "results.csv");

function processFile(input, output, filename) {
    open(input + filename);
    // ... processing steps ...
    saveAs("Tiff", output + filename);
    close();
}
```

### §2.1 Pipeline Patterns

| Pattern | When to Use | Key Idea |
|---------|-------------|----------|
| **Linear** | Standard analysis | Open -> Preprocess -> Segment -> Measure -> Save |
| **Branching** | Mixed image types | Check `bitDepth()`, `getStatistics()`, route accordingly |
| **Recursive folder** | Nested subdirectories | `endsWith(list[i], "/")` detects directories; two-pass (count then process) for accurate progress |

### §2.2 File Filtering

```javascript
if (endsWith(list[i], ".tif") || endsWith(list[i], ".nd2"))  // by extension
if (startsWith(list[i], "DAPI_"))                             // by prefix
if (indexOf(list[i], "channel1") >= 0)                        // by substring
if (endsWith(list[i], ".tif") && indexOf(list[i], "thumb") < 0) // exclude pattern
```

### §2.3 Script Parameters (Parameterised Pipelines)

```javascript
#@ File (label="Input directory", style="directory") inputDir
#@ File (label="Output directory", style="directory") outputDir
#@ String (label="File extension", value=".tif") suffix
#@ Integer (label="Min particle size", value=50) minSize
#@ Float (label="Gaussian sigma", value=2.0) sigma
#@ String (label="Threshold method", choices={"Otsu","Triangle","Li","Huang","MaxEntropy"}) threshMethod
#@ Boolean (label="Apply watershed", value=true) doWatershed
```

| Type | Syntax | Notes |
|------|--------|-------|
| `Integer` | `#@ Integer (label="X", value=10, min=1, max=100) x` | |
| `Float` / `Double` | `#@ Double (label="X", value=0.5, style="slider", min=0, max=1, stepSize=0.01) x` | |
| `String` | `#@ String (label="X", value="exp1") x` | |
| Choice | `#@ String (label="X", choices={"A","B","C"}) x` | `style="radioButtonHorizontal"` for radio buttons |
| `File` | `#@ File (label="X", style="directory") x` | styles: `"directory"`, `"save"`, `"files"` |
| `Boolean` | `#@ Boolean (label="X", value=false) x` | |
| `ImagePlus` | `#@ ImagePlus (label="X") imp` | auto-populates from open images |
| Message | `#@ String (visibility=MESSAGE, value="Header", required=false) h` | display-only |
| Output | `#@output String summary` | |

---

## §3 Memory Management and Performance

### §3.1 setBatchMode

| Mode | Effect |
|------|--------|
| `setBatchMode(true)` | Images not displayed, typically ~20x faster |
| `setBatchMode("show")` | Shows current image without exiting batch mode |
| `setBatchMode("exit and display")` | Shows all images created in batch mode |
| `setBatchMode(false)` | Normal exit |

**Gotchas:** SCIFIO plugin can prevent memory release in batch mode. Some Bio-Formats operations misbehave in batch mode.

### §3.2 Closing Images

```javascript
close("temp_*");       // wildcard
close("\\Others");     // everything except front image
close("*");            // all image windows
```

**Tip:** Use `selectImage(id)` not `selectWindow(title)` -- IDs are unique, titles may not be.

### §3.3 Memory Monitoring

```javascript
usedMB = parseInt(IJ.currentMemory()) / (1024 * 1024);
maxMB = parseInt(IJ.maxMemory()) / (1024 * 1024);
if ((usedMB / maxMB) * 100 > 80) run("Collect Garbage");

// Run GC periodically in large batches
if (i % 10 == 0) run("Collect Garbage");
```

---

## §4 Input Validation and Quality Gates

```javascript
function validateImage() {
    if (nImages == 0) exit("ERROR: No image open");

    depth = bitDepth();
    getDimensions(w, h, channels, slices, frames);
    getVoxelSize(voxW, voxH, voxD, unit);
    getStatistics(area, mean, min, max, stdDev);

    if (unit == "pixel" || unit == "pixels")
        print("WARNING: Image not calibrated — " + getTitle());
    if (depth == 8 && max == 255)
        print("WARNING: Possible saturation in " + getTitle());
    if (depth == 16 && max == 65535)
        print("WARNING: Possible saturation in " + getTitle());
}
```

### §4.1 Quality Gates Between Steps

| Gate | Check | Action on Fail |
|------|-------|----------------|
| Post-preprocessing | `mean < 5` after background subtraction | Skip (too dark) |
| Post-segmentation | `foregroundPercent < 0.1` or `> 90` | Warning or skip |
| Post-measurement | `nResults == 0` | Log warning |
| ROI count | `roiManager("count") == 0` | Skip measurement |
| File existence | `File.exists(path)` | Skip |
| Already processed | `File.exists(outputName)` | Skip |

### Edge Case Handling

```javascript
// Redirect errors to Log instead of dialog
IJ.redirectErrorMessages();
open(input + filename);
if (nImages == 0) { print("Failed to open: " + filename); return; }

// Check if binary before binary operations
if (!is("binary")) { setAutoThreshold("Otsu dark"); run("Convert to Mask"); }
```

---

## §5 Results Table Management

| Pattern | Use Case | Key Commands |
|---------|----------|--------------|
| Accumulate | All measurements in one table | Don't clear between images; `"display"` option adds filename label |
| Per-image | Separate CSV per image | `run("Clear Results")` before each; save with `File.getNameWithoutExtension()` |
| Custom summary | One row per image | Use `setResult("Col", row, value)` + `updateResults()` |
| Rename table | Multiple concurrent tables | `IJ.renameResults("Results", "MyData")` frees "Results" for reuse |
| Log file | Tab-delimited output | `f = File.open(path); print(f, "col1\tcol2"); File.close(f);` |

### §5.1 Summary Table Pattern

```javascript
// Access the Summary window created by "summarize" option
IJ.renameResults("Summary", "Results");
count = getResult("Count", nResults - 1);
avgSize = getResult("Average Size", nResults - 1);
IJ.renameResults("Results", "Summary");
```

---

## §6 Multi-Channel and Z-Stack Pipelines

### §6.1 Split-Process-Merge

```javascript
getDimensions(w, h, channels, slices, frames);
if (channels > 1) {
    run("Split Channels");
    selectWindow("C1-" + title); run("Gaussian Blur...", "sigma=1"); rename("DAPI_processed");
    selectWindow("C2-" + title); run("Subtract Background...", "rolling=50"); rename("GFP_processed");
    run("Merge Channels...", "c1=DAPI_processed c2=GFP_processed create");
}
```

### §6.2 Z-Stack: Project Then Split (Memory Efficient)

```javascript
if (slices > 1) {
    run("Z Project...", "projection=[Max Intensity] all");
    rename("projected");
    selectWindow(title); close();  // close original stack
    selectWindow("projected");
}
if (channels > 1) run("Split Channels");
```

### §6.3 Segment on One Channel, Measure on Another

```javascript
// Segment nuclei on DAPI, measure marker intensity in nuclear ROIs
selectWindow("nuclei_mask");
run("Analyze Particles...", "size=50-Infinity add");
selectWindow("marker");
run("Set Measurements...", "area mean integrated redirect=marker decimal=3");
roiManager("Measure");
```

### §6.4 De-interleaving

```javascript
// When channels are interleaved in a plain stack
numChannels = 2;  // set based on your data
run("Deinterleave", "how=" + numChannels);
```

---

## §7 Bio-Formats Series Iteration

For multi-series files (.lif, .nd2, .czi):

```javascript
run("Bio-Formats Macro Extensions");
Ext.setId(path);
Ext.getSeriesCount(seriesCount);

for (s = 0; s < seriesCount; s++) {
    Ext.setSeries(s);
    Ext.getSeriesName(seriesName);
    // Note: series_N is 1-based in the Importer
    run("Bio-Formats Importer",
        "open=[" + path + "] color_mode=Default " +
        "view=Hyperstack stack_order=XYCZT series_" + (s + 1));
    processCurrentImage();
    close();
}
Ext.close();
```

### §7.1 Bio-Formats Macro Extension Functions

| Category | Functions |
|----------|----------|
| **File** | `Ext.setId(path)`, `Ext.close()`, `Ext.closeFileOnly()`, `Ext.getCurrentFile(file)` |
| **Series** | `Ext.getSeriesCount(n)`, `Ext.setSeries(i)`, `Ext.getSeries(i)`, `Ext.getSeriesName(name)` |
| **Dimensions** | `Ext.getSizeX/Y/Z/C/T(val)`, `Ext.getImageCount(n)`, `Ext.getDimensionOrder(order)` |
| **Pixel info** | `Ext.getPixelType(type)`, `Ext.getEffectiveSizeC(c)`, `Ext.getRGBChannelCount(c)`, `Ext.isIndexed(b)` |
| **Physical** | `Ext.getPixelsPhysicalSizeX/Y/Z(um)`, `Ext.getPixelsTimeIncrement(dt)` |
| **Plane** | `Ext.getPlaneTimingDeltaT(dt,n)`, `Ext.getPlaneTimingExposureTime(t,n)`, `Ext.getPlanePositionX/Y/Z(v,n)` |
| **Coordinates** | `Ext.getIndex(z,c,t,index)`, `Ext.getZCTCoords(index,z,c,t)` |
| **Metadata** | `Ext.getMetadataValue(key,val)`, `Ext.getSeriesMetadataValue(key,val)`, `Ext.getImageCreationDate(d)` |
| **Format** | `Ext.getFormat(path,fmt)`, `Ext.isThisType(path,b)` |
| **Open** | `Ext.openImagePlus(path)` |

---

## §8 Matched File Pair Processing

```javascript
// Files named: sample01_DAPI.tif, sample01_GFP.tif
for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], "_DAPI.tif")) {
        dapiFile = list[i];
        gfpFile = replace(dapiFile, "_DAPI.tif", "_GFP.tif");
        if (!File.exists(inputDir + gfpFile)) { print("No match: " + dapiFile); continue; }

        open(inputDir + dapiFile); dapiID = getImageID();
        open(inputDir + gfpFile);  gfpID = getImageID();

        // Segment on DAPI, measure on GFP
        selectImage(dapiID);
        run("Gaussian Blur...", "sigma=2");
        setAutoThreshold("Otsu dark"); run("Convert to Mask"); run("Watershed");
        run("Analyze Particles...", "size=50-Infinity add");

        selectImage(gfpID);
        roiManager("Measure");
        saveAs("Results", outputDir + replace(dapiFile, "_DAPI.tif", "") + "_results.csv");

        roiManager("Reset"); run("Clear Results"); close("*");
    }
}
```

### §8.1 Separate Folder Pairs

Same approach: iterate one folder, use `File.exists(otherDir + filename)` to find matches.

### §8.2 Well Plate Processing

Pattern: extract well ID from filename (e.g. `WellA01_s1_w1.tif`), collect unique wells, then iterate per-well.

---

## §9 Error Handling

ImageJ macro has no try/catch. Use these patterns:

| Pattern | Syntax |
|---------|--------|
| Redirect error | `IJ.redirectErrorMessages();` -- next error goes to Log, not dialog |
| Pre-check file | `if (!File.exists(path)) return;` |
| Pre-check image | `if (nImages == 0) exit("No image open");` |
| Pre-check results | `if (nResults == 0) print("Nothing to save");` |
| Pre-check ROIs | `if (roiManager("count") == 0) return;` |
| Pre-check binary | `if (!is("binary")) { setAutoThreshold("Otsu dark"); run("Convert to Mask"); }` |

### §9.1 Robust Pipeline Wrapper

```javascript
function safePipeline(inputDir, outputDir) {
    list = getFileList(inputDir);
    successCount = 0; failCount = 0;
    logFile = File.open(outputDir + "pipeline_log.txt");

    for (i = 0; i < list.length; i++) {
        if (!endsWith(list[i], ".tif")) continue;
        showProgress(i, list.length);

        IJ.redirectErrorMessages();
        open(inputDir + list[i]);
        if (nImages == 0) { print(logFile, "FAIL: " + list[i]); failCount++; continue; }

        success = processAndValidate(list[i]);
        if (success) { saveAs("Tiff", outputDir + "out_" + list[i]); successCount++; }
        else { failCount++; }

        close("*");
        if (i % 20 == 0) run("Collect Garbage");
    }
    print(logFile, "Done: " + successCount + " OK, " + failCount + " failed");
    File.close(logFile);
}
```

---

## §10 Progress Reporting and Logging

| Function | Purpose |
|----------|---------|
| `showProgress(i, total)` | Built-in progress bar (lower right) |
| `showStatus("text")` | Status bar message |
| `print("text")` | Log window |
| `print("\\Clear")` | Clear log |
| `print("\\Update:text")` | Replace last log line |
| `print("[Window Name]", "text\n")` | Named text window |

### §10.1 ETA Calculation

```javascript
startTime = getTime();
for (i = 0; i < list.length; i++) {
    processFile(list[i]);
    elapsed = getTime() - startTime;
    remaining = (elapsed / (i + 1)) * (list.length - i - 1);
    showStatus("Image " + (i+1) + "/" + list.length +
               " — ETA: " + floor(remaining/60000) + "m " + floor((remaining%60000)/1000) + "s");
}
```

---

## §11 Pipeline Decision Points

### §11.1 Choosing Threshold Method

| Histogram Shape | Recommended Method |
|----------------|-------------------|
| Bimodal (two peaks) | Otsu |
| Mostly dark, sparse bright objects | Triangle |
| Low contrast (stdDev/mean < 0.3) | Li |
| General purpose fallback | Default |

To choose programmatically, analyse the histogram for peak count and intensity distribution. Use `explore_thresholds` (TCP) or `getList("threshold.methods")` to compare all methods on a test image.

### §11.2 Adaptive Processing Considerations

| Image Property | How to Check | Adaptation |
|---------------|--------------|------------|
| Image size | `w * h` | Larger images may benefit from larger rolling ball radius |
| Noise level | Subtract median-filtered copy, check stdDev | Higher noise -> larger blur sigma |
| Bit depth | `bitDepth()` | RGB: convert to grayscale first; 16-bit may need different threshold |
| Content level | `stdDev` after preprocessing | Very low stdDev suggests empty/blank image |

---

## §12 Parameter Sweeps

General approach: duplicate image, apply each parameter value, measure outcome, compare.

```javascript
// Example: sweep threshold methods
methods = getList("threshold.methods");
open(imagePath);
original = getImageID();

for (m = 0; m < methods.length; m++) {
    selectImage(original);
    run("Duplicate...", "title=test_" + methods[m]);
    setAutoThreshold(methods[m] + " dark");
    run("Convert to Mask");
    getStatistics(area, mean);
    foreground = (mean / 255) * 100;
    print(methods[m] + ": " + d2s(foreground, 1) + "% foreground");
    close();
}
```

Same pattern works for sigma values, size filters, circularity ranges. Compare results against expected foreground percentage or known cell counts.

---

## §13 CLIJ2 GPU-Accelerated Pipelines

### Pattern

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");  // auto-select GPU
open("image.tif");
input = getTitle();

Ext.CLIJ2_push(input);
Ext.CLIJ2_gaussianBlur2D(input, blurred, 2, 2);
Ext.CLIJ2_release(input);                       // release as you go
Ext.CLIJ2_thresholdOtsu(blurred, binary);
Ext.CLIJ2_release(blurred);
Ext.CLIJ2_connectedComponentsLabelingBox(binary, labels);
Ext.CLIJ2_release(binary);
Ext.CLIJ2_statisticsOfLabelledPixels(input, labels);  // results to CPU table
Ext.CLIJ2_pull(labels);                          // pull only final result
Ext.CLIJ2_release(labels);
// Or: Ext.CLIJ2_clear();  to release everything
```

### Key CLIJ2 Operations

| Category | Functions |
|----------|----------|
| **Filter** | `gaussianBlur2D/3D(src,dst,sX,sY[,sZ])`, `mean2DBox`, `median2DBox`, `topHatBox(src,dst,rX,rY,rZ)` |
| **Threshold** | `thresholdOtsu(src,dst)`, `threshold(src,dst,val)`, `automaticThreshold(src,dst,method)` |
| **Segment** | `connectedComponentsLabelingBox(src,dst)`, `voronoiOtsuLabeling(src,dst,spotSigma,outlineSigma)` |
| **Morphology** | `erodeBox`, `dilateBox`, `binaryFillHoles` |
| **Measure** | `statisticsOfLabelledPixels(intensity,labels)` |
| **Math** | `addImages`, `subtractImages`, `multiplyImages` |
| **Project** | `maximumZProjection`, `meanZProjection` |
| **Memory** | `reportMemory()`, `release(name)`, `clear()` |

**Batch tip:** Push to GPU, close CPU copy immediately (`close()`), process on GPU, pull only the final result.

---

## §14 Groovy Pipeline Advantages

| Feature | Macro Language | Groovy |
|---------|---------------|--------|
| Error handling | No try/catch | Full try/catch/finally |
| Data structures | Arrays only | Lists, Maps, Sets, Classes |
| File I/O | Limited | Full Java I/O |
| String operations | Basic | Full regex, templating |
| External libraries | None | Any Java library |

### §14.1 Groovy Pipeline Example

```groovy
#@ File (label="Input", style="directory") inputDir
#@ File (label="Output", style="directory") outputDir
#@ String (label="Threshold", choices={"Otsu","Triangle","Li"}) method

import ij.IJ
import ij.measure.ResultsTable

def files = inputDir.listFiles().findAll { it.name.endsWith('.tif') }.sort()
def summary = []

files.eachWithIndex { file, idx ->
    IJ.showProgress(idx, files.size())
    try {
        def imp = IJ.openImage(file.absolutePath)
        if (imp == null) { IJ.log("WARN: Failed to open ${file.name}"); return }
        imp.show()
        IJ.run(imp, "Gaussian Blur...", "sigma=2")
        IJ.run(imp, "Auto Threshold", "method=${method} white")
        IJ.run(imp, "Analyze Particles...", "size=50-Infinity show=Nothing display")
        def rt = ResultsTable.getResultsTable()
        summary << [file: file.name, count: rt.size()]
        imp.changes = false; imp.close()
    } catch (Exception e) {
        IJ.log("ERROR processing ${file.name}: ${e.message}")
    }
}

new File(outputDir, "summary.csv").text = "Filename,Count\n" +
    summary.collect { "${it.file},${it.count}" }.join("\n")
```

---

## §15 External Tool Concepts

### §15.1 CellProfiler Translation

| CellProfiler Module | ImageJ Equivalent |
|---------------------|-------------------|
| IdentifyPrimaryObjects | Threshold + Watershed + Analyze Particles |
| IdentifySecondaryObjects | Marker-controlled watershed / Voronoi from seeds |
| MeasureObjectIntensity | `roiManager("Measure")` with `redirect=` |
| MeasureObjectSizeShape | `"area shape feret's"` in Set Measurements |
| ExportToSpreadsheet | `saveAs("Results", path)` |

### §15.2 Python/scikit-image Pipeline

Functional pattern: `preprocess()` -> `segment()` -> `measure_objects()` -> `quality_check()`. Use `regionprops_table()` for measurements, `pd.DataFrame` for results.

### §15.3 PyImageJ Bridge

```python
import imagej
ij = imagej.init('sc.fiji:fiji')
ij.py.run_macro('open("/path/to/image.tif"); run("Gaussian Blur...", "sigma=2");')
```

---

## §16 Reproducibility

### §16.1 Parameter Logging

```javascript
function logParameters(outputDir) {
    f = File.open(outputDir + "pipeline_parameters.txt");
    getDateAndTime(year, month, dow, dom, hour, min, sec, msec);
    print(f, "Date: " + year + "-" + (month+1) + "-" + dom);
    print(f, "ImageJ version: " + getVersion());
    print(f, "Parameters:");
    print(f, "  Sigma: " + sigma);
    print(f, "  Threshold: " + threshMethod);
    print(f, "  Size range: " + minSize + "-" + maxSize);
    File.close(f);
}
```

### §16.2 Checklist

- Record all processing parameters; use macros/scripts (never manual-only for quantitative work)
- Apply uniformly across all images in a dataset
- Save segmentation masks as intermediate results
- Never modify pixel values for quantitative analysis (no Enhance Contrast/normalize)
- Store raw data; never overwrite originals
- Test pipeline on representative images first
- Include pipeline details in methods section

---

## §17 Pipeline Export Formats

| Format | Extension | How to Run | Notes |
|--------|-----------|-----------|-------|
| ImageJ Macro | `.ijm` | Script Editor (IJ1 Macro) or `Plugins > Macros > Install` | Native format |
| Groovy | `.groovy` | Script Editor (Groovy) or `fiji --run script.groovy` | Better error handling |
| PyImageJ | `.py` | External Python | `ij.py.run_macro()` bridge |

### Macro Recorder to Pipeline

1. `Plugins > Macros > Record...` -- perform steps manually
2. Copy recorded commands
3. Wrap in function with parameters
4. Add batch loop, validation, error handling

---

## §18 ImageJAI PipelineBuilder API

**Source**: `src/main/java/imagejai/engine/PipelineBuilder.java`

### PipelineStep Fields

| Field | Type | Description |
|-------|------|-------------|
| `index` | `int` | 1-based step number |
| `description` | `String` | Human-readable step name |
| `macroCode` | `String` | ImageJ macro to execute |
| `status` | `String` | `"pending"` / `"running"` / `"success"` / `"failed"` / `"skipped"` |
| `result` | `ExecutionResult` | Populated after execution |
| `executionTimeMs` | `long` | Time taken |

### §18.1 PipelineBuilder Methods

```java
Pipeline parsePipeline(String llmResponse)                    // Parse <pipeline> XML from LLM
void executePipeline(Pipeline pipeline, PipelineCallback cb)  // Execute; stops on first failure
void resumePipeline(Pipeline pipeline, PipelineCallback cb)   // Resume from first non-success step
void retryStep(Pipeline p, int idx, String newCode, PipelineCallback cb)
void savePipeline(Pipeline pipeline, String filePath)         // Save as .ijm
String createBatchMacro(Pipeline p, String inputDir, String outputDir)
```

### §18.2 Execution Model

- Each step dispatched to EDT via `SwingUtilities.invokeAndWait()`
- Sequential only, no parallelism
- On failure: current step `"failed"`, remaining `"skipped"`, pipeline `"failed"`
- Resume/retry picks up from failed step; completed steps not re-run
- `Pipeline.exportAsMacro()` -- all steps as single `.ijm`
- `Pipeline.getStatusSummary()` -- formatted `[OK]/[FAIL]/[SKIP]` per step

---

## §19 ImageJAI Pipeline XML Format

### LLM Response Format

```xml
<pipeline>
  <step description="Duplicate image">
    run("Duplicate...", "title=working");
  </step>
  <step description="Apply Gaussian blur">
    run("Gaussian Blur...", "sigma=2");
  </step>
  <step description="Auto-threshold">
    setAutoThreshold("Otsu");
    run("Convert to Mask");
  </step>
</pipeline>
```

**Parsing:** Outer `<pipeline>`, inner `<step description="...">code</step>`. Description attribute required. Empty steps skipped.

### When to Use

| Format | When |
|--------|------|
| `<pipeline>` | 3+ sequential operations, batch, full analysis, "workflow" requests |
| `<macro>` | Single operation, quick adjustment, testing |

Never mix `<macro>` and `<pipeline>` in the same response.

### §19.1 Specialist Agent Pipeline Structures

| Agent | Typical Flow |
|-------|-------------|
| **Hypothesis** | IV/DV -> channels -> ROI -> segment -> measure -> normalise -> stats |
| **Segmentation** | Preprocess -> threshold/DL -> cleanup -> validate |
| **Measurement** | Set measurements -> ROIs -> measure -> normalise (CTCF) -> export |
| **Visualization** | Merge channels -> project -> LUT -> scale bar -> montage -> export |

---

## §20 ImageJAI TCP run_pipeline Protocol

### Request

```json
{
  "command": "run_pipeline",
  "steps": [
    {"description": "Open sample", "code": "run('Blobs (25K)');"},
    {"description": "Blur", "code": "run('Gaussian Blur...', 'sigma=2');"},
    {"description": "Threshold", "code": "setAutoThreshold('Otsu'); run('Convert to Mask');"}
  ]
}
```

### §20.1 Response (success)

```json
{
  "ok": true,
  "result": {
    "status": "completed",
    "steps": [
      {"index": 1, "description": "Open sample", "status": "success", "executionTimeMs": 150},
      {"index": 2, "description": "Blur", "status": "success", "executionTimeMs": 45},
      {"index": 3, "description": "Threshold", "status": "success", "executionTimeMs": 30}
    ]
  }
}
```

### §20.2 Response (failure)

On failure: failed step has `"status": "failed"` with `"error"` field; subsequent steps have `"status": "skipped"`.

### Agent Step-by-Step Alternative

For more control (visual inspection between steps), execute macros individually:

```bash
python ij.py macro 'run("Blobs (25K)");'
python ij.py capture step1_open
# Read .tmp/step1_open.png — visual check

python ij.py macro 'run("Gaussian Blur...", "sigma=2");'
python ij.py capture step2_blur
# Read .tmp/step2_blur.png — visual check
```

Preferred when: agent needs decisions between steps, visual inspection required, parameters may need adjustment, or error recovery requires understanding current state.

---

## §21 ImageJAI Recipe System

Recipes are YAML files in `agent/recipes/` defining reproducible workflows.

### Recipe Schema (Key Fields)

```yaml
name: Cell Counting (Threshold + Analyze Particles)
id: cell_counting
domain: cell_biology       # cell_biology|neuroscience|histology|fluorescence|tracking|3d|publication|preprocessing|measurement
difficulty: beginner       # beginner|intermediate|advanced

preconditions:
  image_type: [8-bit, 16-bit]
  min_channels: 1
  needs_stack: false

parameters:
  - name: blur_sigma
    type: numeric           # numeric|choice|boolean|string
    default: 1
    range: [0, 5]
  - name: threshold_method
    type: choice
    default: "Otsu"
    options: ["Otsu", "Triangle", "Li", "Huang", "MaxEntropy"]

steps:
  - id: 3
    description: "Auto-threshold"
    macro: |
      setAutoThreshold("${threshold_method}");
      run("Convert to Mask");
    decision_point: true
    decision_logic: "Bimodal -> Otsu. Small foreground -> Triangle. Try explore_thresholds if wrong."
    capture_after: true
    validate: "White objects on black background matching visible cells"

known_issues:
  - condition: "Dense clusters"
    fix: "Use cell_counting_stardist recipe instead"

tags: [cells, counting, nuclei, threshold, watershed]
```

**Parameter substitution:** `${param_name}` in macro code replaced with parameter values.

### §21.1 Recipe Discovery

```bash
python recipe_search.py "count cells"           # keyword search
python recipe_search.py --domain neuroscience    # filter by domain
python recipe_search.py --list                   # list all recipes
python recipe_search.py --show cell_counting     # full recipe YAML
```

---

## §22 Agent Pipeline Construction Workflow

### The Visual Feedback Loop

```
1. Check state:     python ij.py state
2. Check metadata:  python ij.py metadata
3. Execute step:    python ij.py macro '...'
4. Capture:         python ij.py capture step_name
5. LOOK:            Read .tmp/step_name.png
6. Decide:          adjust parameters, continue, or retry
7. Verify:          python ij.py results / python ij.py histogram
8. Next step or finish
```

### Error Recovery

```bash
python ij.py macro 'run("Some Plugin...", "param=value");'
# → error: "Not a binary image"
python ij.py close_dialogs
python ij.py info                    # diagnose
python ij.py macro 'setAutoThreshold("Otsu"); run("Convert to Mask");'  # fix
python ij.py macro 'run("Some Plugin...", "param=value");'              # retry
```

### Post-Analysis

```bash
python auditor.py                    # validate measurements
python ij.py log                     # check plugin warnings
python ij.py capture final_result    # visual verification
```

---

## §23 Agent vs Plugin Pipeline Paths

| Aspect | Plugin (Chat UI) | Agent (Claude CLI) |
|--------|------------------|-------------------|
| Pipeline source | LLM XML response | Programmatic / recipe |
| Execution | `run_pipeline` in-process | Individual macros via TCP |
| Visual feedback | HTML progress + optional screenshot | Capture PNG + Read tool |
| Failure handling | LLM auto-retry (3 attempts) | Manual diagnosis + retry |
| Validation | Failure detection only | `auditor.py` + `macro_lint.py` |
| Export | `.ijm` macro file | `session_log.py` replay |
| Recipe integration | None (LLM generates fresh) | `recipe_search.py` templates |
| Batch wrapping | `createBatchMacro()` built-in | Manual folder loop |

---

## §24 Common Step Patterns

### Standard Analysis Sequence

```
1. PREPARE   → Duplicate, preserve original
2. INSPECT   → state, histogram, metadata
3. PREPROCESS → blur, background subtraction
4. SEGMENT   → Threshold (decision point + capture)
5. CLEAN     → Fill holes, watershed (decision)
6. MEASURE   → Set Measurements + Analyze Particles
7. VERIFY    → results, histogram, audit
8. EXPORT    → Save to output directory
```

### Key Patterns

| Pattern | Code | Notes |
|---------|------|-------|
| Preserve original | `run("Duplicate...", "title=working duplicate");` | Add `duplicate` for stacks |
| Threshold decision | `setAutoThreshold("Otsu"); run("Convert to Mask");` | Capture and inspect after |
| Watershed | `run("Fill Holes"); run("Watershed");` | Skip for elongated objects (over-splits) |
| Multi-channel split | `run("Split Channels");` → `C1-title`, `C2-title`, etc. | |
| 3D isolation | Segment -> label -> binary mask -> `imageCalculator("Multiply create stack", orig, mask)` | Multiply not AND for 16-bit |
| Redirect measurements | `run("Set Measurements...", "... redirect=OriginalImage ...");` | Measure intensity on original using mask ROIs |
| CTCF | `IntDen - (Area * bgMean)` | Measure cells and background ROIs separately |

---

## §25 Sources

- [Batch Processing with ImageJ Macro](https://imagej.net/tutorials/batch-processing-with-ij-macro)
- [BatchProcessFolders.txt Template](https://imagej.net/ij/macros/BatchProcessFolders.txt)
- [ImageJ Built-in Macro Functions](https://imagej.net/ij/developer/macro/functions.html)
- [ImageJ Script Parameters](https://imagej.net/scripting/parameters)
- [CLIJ2 GPU-Accelerated Processing](https://clij.github.io/)
- [Bio-Formats Macro Extensions API](https://javadoc.scijava.org/Bio-Formats/loci/plugins/macro/LociFunctions.html)
- [scikit-image Region Properties](https://scikit-image.org/docs/stable/auto_examples/segmentation/plot_regionprops.html)
- [Reproducible Image Handling (PMC)](https://pmc.ncbi.nlm.nih.gov/articles/PMC7849301/)
- [PyImageJ Documentation](https://py.imagej.net/en/latest/07-Running-Macros-Scripts-and-Plugins.html)
- [ImageJ Auto Threshold Plugin](https://imagej.net/plugins/auto-threshold)
