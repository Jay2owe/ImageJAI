# Self-Improving ImageJ Agent — Technical Reference

## 1. ImageJ Plugin Ecosystem

### 1.1 Update Site Registry

The official registry lives at https://imagej.net/list-of-update-sites/ (maintained on
GitHub at https://github.com/imagej/list-of-update-sites). As of this Fiji installation,
there are ~330 update sites (22 enabled, 308 available). The `scan_plugins.py` script
discovers 1966 installed commands.

**How to discover what's available:**
```bash
python scan_plugins.py          # writes .tmp/commands.txt + .tmp/plugins_summary.txt
grep -i "keyword" .tmp/commands.txt
grep -i "keyword" .tmp/update_sites.json
```

### 1.2 Key Installed Plugins (This Fiji)

| Plugin | Category | Commands | Macro-Recordable | Headless-Safe |
|--------|----------|----------|-------------------|---------------|
| **StarDist 2D/3D** | Deep learning segmentation | 2 | Yes | Yes (with model) |
| **Cellpose** | Deep learning segmentation | 3+ | Yes | Partial (needs Python backend) |
| **TrackMate** | Tracking | 10+ | Limited (wizard-based) | No (GUI wizard) |
| **Trainable Weka Segmentation** | ML segmentation | 3 | Yes (scriptable API) | Yes |
| **CLIJ2** | GPU processing | 504 | Yes (Ext. functions) | Yes |
| **Bio-Formats** | File I/O | 5+ | Yes | Yes |
| **3D ImageJ Suite** | 3D analysis | 64 | Yes | Mostly |
| **Coloc 2** | Colocalization | 1 | Yes | Yes |
| **AnalyzeSkeleton** | Morphology | 3 | Yes | Yes |
| **ABBA** | Brain atlas | 10+ | Limited | No |
| **MorphoLibJ** | Morphological ops | 30+ | Yes | Yes |
| **Labkit** | Interactive ML seg. | 2 | No (interactive) | No |

### 1.3 How Plugins Expose Their Functionality

**GenericDialog pattern (most plugins):**
- Plugin creates a `GenericDialog`, adds fields (numeric, string, checkbox, choice)
- When user clicks OK, the Recorder captures: `run("Plugin Name", "param1=value1 param2=value2")`
- Parameter names = first word of each label, lowercased, spaces replaced with underscores
- **Constraint**: first word of each label must be unique within the dialog
- **Discovery**: no way to enumerate parameters without opening the dialog — use `probe_plugin.py`

**MacroExtension pattern (CLIJ2, Bio-Formats):**
```
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_gaussianBlur3D(input, output, sigmaX, sigmaY, sigmaZ);
```
- Plugins register `Ext.` functions callable from macro language
- Parameters are positional, not named key=value pairs

**call() function (direct Java method invocation):**
```
result = call("fully.qualified.ClassName.staticMethod", "arg1", "arg2");
```
- Only works with `public static` methods that accept and return `String`
- Limited but useful for plugins designed to be called this way

**eval() function (cross-language escape hatch):**
```
eval("script", "importClass(Packages.ij.IJ); IJ.run('Blobs (25K)');");
eval("python", "from ij import IJ; IJ.run('Blobs (25K)')");
eval("bsh", "import ij.IJ; IJ.run(\"Blobs (25K)\");");
```
- Executes JavaScript, BeanShell, or Python (Jython) from within a macro
- Can access any Java API — the escape hatch when macro language is insufficient

### 1.4 Macro-Recordable vs GUI-Only Plugins

**Fully macro-recordable** (safe for agent control):
- All built-in ImageJ commands (Process, Analyze, Image menus)
- Most GenericDialog-based plugins (Gaussian, Threshold, Analyze Particles, etc.)
- Bio-Formats Importer/Exporter
- StarDist, 3D Objects Counter, MorphoLibJ, AnalyzeSkeleton
- CLIJ2 (via Ext. functions)

**Partially recordable** (some operations need workarounds):
- TrackMate: wizard produces a macro but doesn't cover all options
- Cellpose: macro-recordable but needs external Python environment
- Weka Segmentation: scriptable via Jython API, not pure macro

**GUI-only** (cannot be automated via macro):
- Labkit (interactive painting)
- ABBA (atlas alignment GUI)
- Manual ROI drawing tools
- Some TrackMate features (manual track editing)

---

## 2. Automation Approaches

### 2.1 Scripting Language Comparison

| Feature | IJ Macro | Groovy | Jython | BeanShell |
|---------|----------|--------|--------|-----------|
| **Java version** | N/A (interpreted) | Any | Python 2.7 | Any |
| **Java API access** | Limited (run/call/eval) | Full | Full | Full |
| **Type system** | Weakly typed | Strongly typed | Duck typed | Strongly typed |
| **Script parameters** | No (@Parameter) | Yes | Yes | Yes |
| **Headless support** | Partial | Full | Full | Full |
| **Speed** | Slowest | Fastest (compiled) | Medium | Medium |
| **Complexity** | Simplest | Medium | Medium | Medium |
| **SciJava services** | No | Yes | Yes | Yes |
| **Error handling** | Minimal | try/catch | try/except | try/catch |
| **Community examples** | Most | Growing | Many | Few |

**Recommendation for agent control**: Use IJ Macro as the primary language because:
1. Every macro-recordable plugin produces macro code (largest corpus of examples)
2. `IJ.runMacro()` is the simplest execution path through the TCP server
3. For anything the macro language cannot do, use `eval("script", "...")` to drop into JavaScript/BeanShell for single operations
4. Reserve Groovy/Jython for complex standalone scripts (generated via `ScriptGenerator.java`)

### 2.2 Bio-Formats Macro Commands

**Opening proprietary formats:**
```javascript
// Basic open with default settings
run("Bio-Formats Importer", "open=/path/to/file.nd2 autoscale color_mode=Default view=Hyperstack stack_order=XYCZT");

// Open specific series from multi-position file
run("Bio-Formats Importer", "open=/path/to/file.lif autoscale color_mode=Default view=Hyperstack stack_order=XYCZT series_1");

// Split channels on import
run("Bio-Formats Importer", "open=/path/to/file.czi autoscale color_mode=Default split_channels view=Hyperstack stack_order=XYCZT");

// Open as virtual stack (memory-efficient for large files)
run("Bio-Formats Importer", "open=/path/to/file.nd2 color_mode=Default view=Hyperstack stack_order=XYCZT use_virtual_stack");
```

**Bio-Formats Macro Extensions (for metadata access):**
```javascript
run("Bio-Formats Macro Extensions");
Ext.setId("/path/to/file.nd2");
Ext.getSeriesCount(seriesCount);
Ext.getSizeX(width);
Ext.getSizeY(height);
Ext.getSizeZ(slices);
Ext.getSizeC(channels);
Ext.getSizeT(frames);
Ext.getPixelsPhysicalSizeX(pixelSizeX);
```

**Format-specific notes:**

| Format | Extension | Key Options | Gotchas |
|--------|-----------|-------------|---------|
| Nikon NIS | .nd2 | `autoscale` recommended | Multi-position: check series count |
| Leica LAS | .lif | Often multi-series | Must specify `series_N` |
| Zeiss ZEN | .czi | Scenes = series | Large tiles may need `crop` |
| OME-TIFF | .ome.tiff | Standard format | Usually opens cleanly |
| Olympus | .oif/.oib | Works well | May need `group_files` |

### 2.3 The call() Pattern for Plugin-Specific APIs

```javascript
// Get/set JPEG quality
quality = call("ij.plugin.JpegWriter.getQuality");
call("ij.plugin.JpegWriter.setQuality", "95");

// Access plugin internals
call("trainableSegmentation.Weka_Segmentation.loadClassifier", "/path/to/classifier.model");

// StarDist via call (alternative to run)
call("de.csbdresden.stardist.StarDist2D.main2D", "modelChoice", "Versatile (fluorescent nuclei)");
```

**Limitations**: Only `public static` methods accepting/returning `String`. For anything
more complex, use `eval("script", "...")` to access the full Java API.

---

## 3. Self-Improvement Architecture

### 3.1 Recipe Database Design

Store analysis workflows as structured YAML/JSON files:

```yaml
# recipes/nuclei_count_fluorescence.yaml
name: "Fluorescence Nuclei Count"
version: 2
description: "Count fluorescent nuclei in single-channel images"
tags: [nuclei, count, fluorescence, DAPI, Hoechst]
image_requirements:
  type: [8-bit, 16-bit]
  channels: 1
  min_snr: 3.0

parameters:
  blur_sigma:
    default: 1.5
    range: [0.5, 5.0]
    step: 0.5
    description: "Gaussian blur sigma for noise reduction"
  threshold_method:
    default: "Otsu"
    options: ["Otsu", "Triangle", "Li", "Huang", "MaxEntropy"]
  min_particle_size:
    default: 50
    range: [10, 500]
    unit: "pixels"
  max_particle_size:
    default: 5000
    range: [500, 50000]
    unit: "pixels"
  circularity_min:
    default: 0.3
    range: [0.0, 1.0]
  watershed:
    default: true
    description: "Apply watershed to separate touching nuclei"

steps:
  - name: "Preprocess"
    macro: 'run("Gaussian Blur...", "sigma={blur_sigma}");'
  - name: "Threshold"
    macro: 'setAutoThreshold("{threshold_method} dark"); run("Convert to Mask");'
  - name: "Separate"
    condition: "watershed == true"
    macro: 'run("Watershed");'
  - name: "Analyze"
    macro: 'run("Analyze Particles...", "size={min_particle_size}-{max_particle_size} circularity={circularity_min}-1.00 show=Outlines display summarize");'

validation:
  expected_count_range: [10, 500]
  expected_circularity_mean: [0.5, 1.0]
  failure_indicators:
    - count < 3: "Too few objects — threshold too aggressive or min_size too large"
    - count > 1000: "Too many objects — noise detected, increase blur or min_size"
    - circularity_mean < 0.3: "Objects not circular — check if segmentation is fragmenting"

history:
  - date: "2026-03-17"
    change: "Increased default blur from 1.0 to 1.5 after testing on noisy confocal data"
    performance: "Count accuracy improved from 85% to 92% on test set"
```

### 3.2 Auto-Testing Framework

**Verification strategies by analysis type:**

| Analysis Type | Verification Method | Expected Output |
|--------------|---------------------|-----------------|
| Object counting | Count within expected range | 10-500 for typical FOV |
| Segmentation | Circularity distribution | Mean > 0.5 for round objects |
| Intensity measurement | Mean within calibrated range | Non-zero, non-saturated |
| Colocalization | Pearson/Manders in valid range | -1 to 1 (Pearson), 0 to 1 (Manders) |
| Thresholding | Foreground fraction | 5-60% of pixels typically |
| Particle analysis | Size distribution | CV < 100% for uniform populations |

**Automated validation checks (implementable in Python):**
```python
def validate_segmentation(results_csv, params):
    """Validate segmentation results against expected ranges."""
    data = parse_results(results_csv)
    checks = {
        "count_in_range": params["min_count"] <= len(data) <= params["max_count"],
        "no_giant_objects": all(r["Area"] < params["max_area"] for r in data),
        "circularity_ok": mean(r["Circ."] for r in data) > params["min_circ"],
        "no_zero_area": all(r["Area"] > 0 for r in data),
        "intensity_valid": all(0 < r["Mean"] < 255 for r in data),  # 8-bit
    }
    return checks

def validate_histogram(hist_data):
    """Check for saturation and other intensity problems."""
    checks = {
        "not_saturated": hist_data["bins"][-1] < hist_data["nPixels"] * 0.01,
        "not_empty": hist_data["mean"] > 0,
        "good_dynamic_range": hist_data["stdDev"] > 10,
        "not_clipped_low": hist_data["bins"][0] < hist_data["nPixels"] * 0.1,
    }
    return checks
```

### 3.3 Parameter Optimization

**Approach 1: Grid search (simple, exhaustive)**
Already demonstrated in `evaluate_blobs.py` and the `explore_thresholds` TCP command.
Extend to multi-parameter grids:

```python
# Grid search over blur sigma and threshold method
param_grid = {
    "blur_sigma": [0.5, 1.0, 1.5, 2.0, 3.0],
    "threshold": ["Otsu", "Triangle", "Li", "Huang", "MaxEntropy"],
    "min_size": [30, 50, 100, 200],
}

# Score function: maximize object count while keeping circularity > 0.5
def score(count, circ, expected_count=65):
    if circ < 0.3:
        return 0  # reject fragmented segmentation
    count_score = 1.0 - abs(count - expected_count) / expected_count
    circ_score = circ
    return 0.6 * count_score + 0.4 * circ_score
```

**Approach 2: Genetic algorithm (for large parameter spaces)**
Research shows genetic algorithms outperform grid search for segmentation pipelines.
The approach:
1. Encode pipeline parameters as a "chromosome" (vector of values)
2. Fitness = Jaccard/Dice similarity to ground truth, or proxy metrics
3. Crossover + mutation to explore the space
4. Converges in 50-100 generations for typical pipelines

**Approach 3: Bayesian optimization (fewest evaluations)**
Use a surrogate model (Gaussian process) to predict performance, sample where
uncertainty is highest. Best when each evaluation is expensive (e.g., deep learning
inference). Libraries: scikit-optimize, Optuna (Python-side).

**Practical recommendation**: Start with grid search (it's what `explore_thresholds`
already does). Graduate to Bayesian optimization for pipelines with >3 parameters.

### 3.4 Error Recovery Patterns

| Error | Cause | Auto-Fix |
|-------|-------|----------|
| `"No image open"` | Macro expects an image | Check state first; open image if needed |
| `"Not a binary image"` | Plugin needs mask | Auto-threshold + Convert to Mask |
| `"Selection required"` | Plugin needs ROI | `run("Select All")` or detect objects first |
| `"Macro execution timed out"` | EDT deadlock or long operation | Check for dialogs; increase timeout |
| `"Not a stack"` | Z-projection on single slice | Skip the projection step |
| `"Unknown command"` | Plugin not installed | Search commands.txt; suggest update site |
| `"Out of memory"` | Image too large | Close unused images; use virtual stack |
| `"File not found"` | Wrong path or format | Verify path; try Bio-Formats |
| `"Calibration mismatch"` | Mixed units | Check metadata; set calibration explicitly |
| Dialog appears unexpectedly | Plugin needs user input | Read dialog, extract params, close, re-run with params |

**Recovery loop implementation:**
```python
MAX_RETRIES = 3
for attempt in range(MAX_RETRIES):
    result = run_macro(macro_code)
    if result["ok"] and result["result"]["success"]:
        break
    error = result.get("result", {}).get("error", "")
    # Check for dialogs
    dialogs = check_dialogs()
    if dialogs:
        close_dialogs()
    # Apply fix based on error pattern
    macro_code = apply_error_fix(macro_code, error, dialogs)
```

### 3.5 Benchmark Suite

**Standard test images available in ImageJ:**
```javascript
run("Blobs (25K)");         // Binary segmentation, ~65 objects
run("Boats (356K)");        // Edge detection, texture
run("Dot Blot (7K)");       // Circular objects on array
run("Fluorescent Cells");   // Multi-channel fluorescence (3 channels)
run("Leaf (36K)");          // Complex shape segmentation
run("Lena (68K)");          // General image processing
run("T1 Head (2.4M)");     // 3D medical stack (129 slices)
run("Bat Cochlea Volume");  // 3D volume (141 slices)
run("Embryos");             // Brightfield, touching objects
run("HeLa Cells (1.3M)");  // Fluorescent cells, multi-channel
run("Organ of Corti");      // Complex tissue structure
```

**External benchmark datasets:**

| Dataset | Source | Ground Truth | Use Case |
|---------|--------|-------------|----------|
| BBBC005 | Broad Institute | Cell counts | Synthetic cells, focus quality |
| BBBC006 | Broad Institute | Cell counts | Human U2OS cells |
| BBBC039 | Broad Institute | 23K annotated nuclei | Nuclei segmentation (Hoechst) |
| BBBC050 | Broad Institute | 3 GT types | Deep learning nuclei seg. |
| BBBC038 | Broad Institute | Instance masks | Kaggle DSB 2018 |
| DSB 2018 | Kaggle | Instance segmentation | Diverse nuclei |
| Cell Tracking Challenge | various | Tracking + seg. GT | Time-lapse segmentation |

**Benchmark protocol:**
1. Run pipeline on each test image
2. Compare output to ground truth (Jaccard, Dice, F1, count accuracy)
3. Record execution time and memory usage
4. Store results in `benchmarks/results.json` with timestamp
5. Compare against previous runs to detect regressions

---

## 4. Technical Comparison Matrix

### 4.1 Segmentation Methods

| Method | Type | Setup | Speed | Accuracy (clean) | Accuracy (noisy) | Touching Objects | GPU | Headless |
|--------|------|-------|-------|-------------------|-------------------|------------------|-----|----------|
| **Manual Threshold** | Classical | None | <1s | High (if tuned) | Low | Poor | No | Yes |
| **Auto Threshold (Otsu)** | Classical | None | <1s | Good | Medium | Poor | No | Yes |
| **Auto Threshold (Triangle)** | Classical | None | <1s | Good (unimodal) | Medium | Poor | No | Yes |
| **Watershed** | Classical | Needs mask | <1s | N/A (post-proc) | N/A | Good | No | Yes |
| **Trainable Weka** | Machine Learning | Training (~5 min) | 5-30s/image | Very Good | Good | Medium | No | Yes |
| **StarDist 2D** | Deep Learning | Pre-trained models | 2-10s/image | Excellent | Excellent | Excellent | Optional | Yes |
| **StarDist 3D** | Deep Learning | Pre-trained models | 10-60s/stack | Excellent | Very Good | Excellent | Optional | Yes |
| **Cellpose** | Deep Learning | Pre-trained models | 5-30s/image | Excellent | Excellent | Excellent | Yes (recommended) | Partial |
| **CLIJ2 Threshold** | Classical (GPU) | CLIJ2 init | <0.1s | Same as CPU | Same as CPU | Poor | Yes | Yes |

**When to use what:**

| Scenario | Recommended Method | Rationale |
|----------|-------------------|-----------|
| High-contrast fluorescence, well-separated | Otsu/Triangle + Watershed | Fast, reliable, no setup |
| Touching/overlapping round nuclei | StarDist 2D | Pre-trained model handles overlap |
| Irregularly shaped cells | Cellpose (cyto model) | Trained on diverse cell shapes |
| Noisy/low-contrast, known structure | Trainable Weka | Learns from user-annotated examples |
| Very large dataset (>1000 images) | StarDist (fast) or CLIJ2+Threshold (fastest) | Processing time matters |
| 3D volumes | StarDist 3D or 3D Objects Counter | Proper 3D connectivity |
| Unknown/novel cell type | Cellpose (try pretrained first) | Most generalist model |

### 4.2 Measurement Methods

| Task | Built-in Method | Plugin Method | When to Use Plugin |
|------|----------------|---------------|-------------------|
| **Area/perimeter** | Analyze Particles | MorphoLibJ | Need geodesic measurements |
| **Intensity (mean/sum)** | Set Measurements + Measure | CLIJ2 statistics | Large images, need speed |
| **CTCF** | Manual formula macro | None (manual calc) | Always use macro formula |
| **Colocalization** | JACoP (basic) | Coloc 2 | Need proper statistics (Costes) |
| **Particle counting** | Analyze Particles | StarDist/Cellpose | Touching particles |
| **Skeleton analysis** | Skeletonize + AnalyzeSkeleton | Neuroanatomy (SNT) | Need Sholl/branch analysis |
| **3D measurements** | 3D Objects Counter | 3D ImageJ Suite | Need surface area, sphericity |
| **Texture** | GLCM (Haralick) | Trainable Weka features | Need entropy, correlation |

### 4.3 Filtering/Preprocessing

| Filter | CPU Time (256x256) | CLIJ2 GPU Time | When to Use |
|--------|-------------------|----------------|-------------|
| **Gaussian Blur** | ~5ms | ~0.1ms | General noise reduction |
| **Median** | ~10ms | ~0.2ms | Salt-and-pepper noise, preserve edges |
| **Bilateral** | ~50ms | ~1ms | Noise reduction preserving edges |
| **Rolling Ball BG** | ~20ms | ~0.5ms | Uneven illumination correction |
| **Unsharp Mask** | ~10ms | ~0.2ms | Edge enhancement |
| **Top Hat** | ~15ms | ~0.3ms | Spot detection on variable BG |
| **3D Gaussian** | ~200ms | ~2ms | Z-stack smoothing |

CLIJ2 benchmarks show **10-30x speedup** for individual operations. For complete
pipelines kept on GPU: **15-33x speedup** (11 min vs 2h44m in published benchmarks).

### 4.4 File Format Handling

| Format | Open Command | Split Channels | Virtual Stack | Metadata Quality |
|--------|-------------|----------------|---------------|------------------|
| TIFF | `open()` or Bio-Formats | Manual | Yes | Basic |
| OME-TIFF | Bio-Formats | `split_channels` | Yes | Excellent (OME-XML) |
| ND2 (Nikon) | Bio-Formats | `split_channels` | Yes | Good |
| LIF (Leica) | Bio-Formats | `split_channels` | Yes | Good |
| CZI (Zeiss) | Bio-Formats | `split_channels` | Yes | Good |
| OIF/OIB (Olympus) | Bio-Formats | `split_channels` | Yes | Good |
| PNG/JPEG | `open()` | N/A | No | None |
| DICOM | `open()` or Bio-Formats | N/A | Yes | Medical metadata |

### 4.5 Scripting Language Decision Matrix for Agent

| Task | Best Language | Reason |
|------|--------------|--------|
| Run a menu command | IJ Macro | `run("Command", "params")` is simplest |
| Access Java API | eval("script") from macro | No separate file needed |
| Generate standalone script for user | Groovy | Best SciJava integration, typed |
| Complex data manipulation | Python (via ij.py) | NumPy/pandas available agent-side |
| Batch processing template | IJ Macro | User-editable, most examples online |
| Headless server execution | Groovy | Full @Parameter support |
| Plugin-specific API | eval("bsh") or call() | Direct Java method access |

---

## 5. Implementation Roadmap for Self-Improvement

### Phase 1: Recipe Database (implement first)
- Create `recipes/` directory with YAML workflow definitions
- Each recipe: name, parameters with ranges, macro steps, validation rules
- Agent loads matching recipe by keyword, fills parameters, executes
- After execution, validates results against expected ranges

### Phase 2: Automated Benchmarking
- Create `benchmarks/` directory with test configurations
- Use ImageJ sample images as baseline test suite
- Record: parameter values, object counts, timing, pass/fail
- Compare runs over time to detect regressions

### Phase 3: Parameter Auto-Tuning
- Extend `explore_thresholds` pattern to multi-parameter optimization
- Implement grid search first (already partially done in evaluate_blobs.py)
- Add scoring functions for each analysis type
- Store optimal parameters per image type in recipe database

### Phase 4: Learning from Failures
- Log every error + the fix that resolved it
- Build error-pattern-to-fix lookup table (extend learnings.md)
- After 3 failed retries, record the failure for manual review
- Periodically review failure log to improve recipes

### Phase 5: Visual Verification
- After each processing step, capture + analyze programmatically
- Use `pixels.py` for quantitative checks (saturation, SNR, foreground fraction)
- Use image_diff.py to compare before/after
- Flag suspicious results for human review

---

## Sources

- [ImageJ List of Update Sites](https://imagej.net/list-of-update-sites/)
- [ImageJ Update Sites](https://imagej.net/update-sites/)
- [GitHub — imagej/list-of-update-sites](https://github.com/imagej/list-of-update-sites)
- [ImageJ Macro Language Reference](https://imagej.net/ij/developer/macro/macros.html)
- [ImageJ Macro Reference Guide (PDF)](https://imagej.net/ij/docs/macro_reference_guide.pdf)
- [ImageJ Scripting Basics](https://imagej.net/scripting/basics)
- [ImageJ Scripting Comparisons](https://imagej.net/scripting/comparisons)
- [ImageJ Plugin Architecture](https://imagej.net/develop/plugin-architecture)
- [GenericDialog — ImageJ Wiki](https://imagej.net/scripting/generic-dialog)
- [Plugin Design Guidelines](https://imagej.net/develop/plugin-design-guidelines)
- [Bio-Formats — ImageJ](https://imagej.net/formats/bio-formats)
- [Bio-Formats Documentation](https://bio-formats.readthedocs.io/en/stable/)
- [Running ImageJ Headless](https://imagej.net/learn/headless)
- [Scripting Headless](https://imagej.net/scripting/headless)
- [ImageJ Jython Scripting](https://imagej.net/scripting/jython/)
- [Recommended Script Languages (Forum)](https://forum.image.sc/t/recommended-script-languages-for-development-with-imagej2/76479)
- [SciJava Extensibility and Scripting (PMC)](https://pmc.ncbi.nlm.nih.gov/articles/PMC8363112/)
- [StarDist vs Cellpose (Forum)](https://forum.image.sc/t/stardist-vs-cellpose/89877)
- [Cell Segmentation Methods Evaluation (Oxford Academic)](https://academic.oup.com/bib/article/25/5/bbae407/7735274)
- [Software Tools for 2D Cell Segmentation (PMC)](https://pmc.ncbi.nlm.nih.gov/articles/PMC10886800/)
- [Trainable Weka Segmentation](https://imagej.net/plugins/tws/)
- [Traditional ML vs Deep Learning in Bioimage Analysis (Frontiers)](https://www.frontiersin.org/journals/artificial-intelligence/articles/10.3389/frai.2026.1695230/full)
- [CLIJ2 Benchmarking](https://clij.github.io/clij2-docs/md/benchmarking/)
- [CLIJ Benchmarking Operations](https://clij.github.io/clij-benchmarking/benchmarking_operations.html)
- [GPU-Accelerating ImageJ with CLIJ (Springer)](https://link.springer.com/chapter/10.1007/978-3-030-76394-7_5)
- [Automatic Parameter Fitting in Segmentation Pipelines (PMC)](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC3678745/)
- [Algorithm Sensitivity Analysis for Segmentation (Oxford Academic)](https://academic.oup.com/bioinformatics/article/33/7/1064/2843894)
- [Broad Bioimage Benchmark Collection](https://bbbc.broadinstitute.org/)
- [BBBC Image Sets](https://bbbc.broadinstitute.org/image_sets)
- [BBBC Benchmarking Methodology](https://bbbc.broadinstitute.org/benchmarking)
- [Analyze Particles Documentation](https://imagej.net/ij/docs/menus/analyze.html)
- [Fiji — Batteries Included](https://fiji.sc/)
