# Immunofluorescence Image Post-Processing Reference

IF postprocessing = immunofluorescence post-processing workflows: filtering,
thresholding, deconvolution, and processing pipelines for IF images in
Fiji/ImageJ. Covers filter comparison, auto-threshold methods (global and
local), PSF generation, DeconvolutionLab2 / Iterative Deconvolve 3D,
complete pipelines (quant intensity, cell counting, colocalization,
publication figures, z-stacks), pitfalls, and decision trees.

Sources: `imagej.net/plugins/rolling-ball-background-subtraction`,
`imagej.net/plugins/auto-threshold`, `imagej.net/plugins/auto-local-threshold`,
`bigwww.epfl.ch/deconvolution/deconvolutionlab2/`,
`imagej.net/plugins/iterative-deconvolve-3d`, `imagej.net/plugins/diffraction-psf-3d`,
`clij.github.io/clij2-docs/reference__filter`, BaSiC (Nature Comms 2017),
Nature Portfolio image integrity guidelines, `bioimagebook.github.io`,
Pete Bankhead ImageJ intro, `wsr.imagej.net/developer/macro/functions.html`.
Full list in §Sources at end.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript.
`python probe_plugin.py "Plugin..."` — discover any installed plugin's
parameters at runtime.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Which filter should I use for denoising?" | §3 filter comparison table, §8 decision tree |
| "How do I pick a threshold method?" | §4.1 method guide, §4.2 scenarios, §8 decision tree |
| "When should I deconvolve?" | §5 when-to-deconvolve table, §8 decision tree |
| "How do I generate a PSF?" | §5 PSF generation |
| "What algorithm codes does DeconvolutionLab2 take?" | §5 algorithm table |
| "How do I correct uneven illumination?" | §3 BaSiC flat-field correction |
| "How do I use CLIJ2 GPU filters?" | §3 CLIJ2 GPU filters |
| "What order should I process in?" | §3 filter order, §8 order of operations |
| "How do I measure IF intensity quantitatively?" | §6.1 quantitative intensity pipeline |
| "How do I count cells?" | §6.2 cell counting pipeline |
| "How do I do colocalization?" | §6.3 colocalization pipeline |
| "How do I prepare a publication figure?" | §6.4 publication figures |
| "What operations destroy quantitative data?" | §9 destroys vs preserves table |
| "What IF plugins are installed?" | §8 installed IF processing plugins |
| "Quick-start example?" | §2 quick start |
| "Why did my deconvolution ring / over-sharpen?" | §5 deconvolution artifacts |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '<term>' if-postprocessing-reference.md` to jump.

### A
`Analyze Particles` §2, §6.1, §6.2 · `Auto Local Threshold` §4.2 · `Auto Threshold (Try all)` §4.1, §8 · `autofluorescence (FFPE)` §4.1

### B
`Bandpass Filter` §3, §8 · `BaSiC` §3, §8 · `Bernsen` §4.2 · `Bio-Formats` §8 · `Brightness/Contrast` §9

### C
`Cellpose` §8 · `CLIJ2` §3, §8 · `CLIJ2_clear` §3 · `CLIJ2_differenceOfGaussian3D` §3 · `CLIJ2_gaussianBlur3D` §3 · `CLIJ2_median3DBox` §3 · `CLIJ2_push` §3 · `CLIJ2_subtractGaussianBackground` §3 · `CLIJ2_topHatBox` §3 · `CLIJx_bilateral` §3 · `CLIJx_nonLocalMeans` §3 · `Coloc 2` §6.3, §8 · `colocalization` §6.3 · `Contrast (local threshold)` §4.2 · `Convert to Mask` §2, §4.1, §6.1, §6.2, §9 · `CTCF` §9

### D
`dark background (fluorescence)` §4.1 · `dark frame` §3, §8 · `DeconvolutionLab2` §5 · `Default / IsoData` §4.1 · `Despeckle` §3 · `Diffraction PSF 3D` §5, §8 · `Difference of Gaussians (DoG)` §3, §4.1, §8 · `Dilate (grayscale)` §3 · `Duplicate` §6.4, §9

### E
`Enhance Contrast` §6.4, §9 · `Erode (grayscale)` §3

### F
`FISTA` §5 · `flat-field` §3, §6.1, §8 · `Flatten` §6.4 · `flatfield correction` §3, §6.1, §6.5, §8

### G
`Gaussian Blur` §3, §6.2, §8 · `Gaussian Blur 3D` §3, §6.5 · `getThreshold` §4.1 · `Green LUT` §6.4

### H
`Huang` §4.1 · `Hyperstack` (see Stack) — §3

### I
`ICTM` §5 · `imageCalculator` §3 · `Intermodes / Minimum (threshold)` §4.1 · `Iterative Deconvolve 3D` §5, §8

### J
`JACoP` §8 · `journal guidelines` §9

### L
`lambda (RLTV / Tikhonov / FISTA)` §5 · `Landweber (LW)` §5 · `Li (threshold)` §4.1, §8 · `light (rolling ball flag)` §3 · `LUT` §6.4, §9

### M
`Magenta LUT` §6.4 · `MaxEntropy` §4.1 · `Maximum` §3 · `Mean (filter)` §3 · `Mean (local threshold)` §4.2 · `Measured PSF` §5 · `Median` §3, §8 · `Median 3D` §3 · `Median (local threshold)` §4.2 · `Merge Channels` §6.4 · `MidGrey (local threshold)` §4.2 · `MinError(I)` §4.1 · `Minimum` §3 · `Moments` §4.1 · `MorphoLibJ` §8 · `Multi Otsu Threshold` §4.1, §8

### N
`NA correction` §5 · `Naive Inverse (NIF)` §5 · `Niblack` §4.2 · `NNLS` §5 · `Non-Local Means Denoising` §3, §8

### O
`Otsu` §2, §4.1, §6.1 · `Otsu (local)` §4.2 · `order of operations` §3, §8

### P
`Phansalkar` §4.1, §4.2, §8 · `probe_plugin` §5 · `PSF Generator (Gibson-Lanni)` §8 · `Publication Figures` §6.4

### R
`rank filters` §3 · `red/green overlays (avoid)` §9 · `Regularized Inverse (RIF)` §5 · `Remove Outliers` §3 · `RenyiEntropy` §4.1 · `Richardson-Lucy (RL)` §5, §8 · `Richardson-Lucy Total Variation (RLTV)` §5, §8 · `rolling ball` §3, §6.1, §6.2, §6.3, §6.5, §9 · `rolling (parameter)` §3

### S
`Sauvola` §4.2 · `Scale Bar` §6.4 · `Set Measurements` §2, §6.1 · `setAutoThreshold` §2, §4.1 · `setMinAndMax` §6.4, §9 · `setThreshold` §4.3 · `Shanbhag` §4.1 · `sigma (Gaussian)` §3 · `sliding paraboloid` §3 · `Split Channels` §2 · `StarDist 2D/3D` §8 · `Subtract Background` §2, §3, §6.1, §6.2, §6.3, §6.5, §9 · `Sum Slices (Z Project)` §6.5, §9

### T
`Theoretical PSF` §5 · `Tikhonov-Miller (TM)` §5 · `Triangle` §4.1, §6.2, §8

### U
`Ultimate Points` (see macro-reference) — cross-ref, not in this doc · `Unsharp Mask` §3

### V
`Variance` §3

### W
`Watershed` §2, §6.1, §6.2 · `Weka Segmentation` §8 · `widefield z-stack` §5, §8 · `Wiener` §5

### Y
`Yen` — (see macro-reference / imagej-gui-reference; not enumerated here)

### Z
`Z Project` §6.5, §9 · `z-stack processing` §5, §6.5

---

## §2 Quick Start

```javascript
open("/path/to/your/IF_image.tif");
run("Split Channels");
selectWindow("C2-IF_image.tif");
run("Subtract Background...", "rolling=50");
run("Gaussian Blur...", "sigma=1");
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Watershed");
run("Set Measurements...", "area mean integrated decimal=3");
run("Analyze Particles...", "size=50-Infinity display summarize");
```

---

## §3 Filtering

### Filter Comparison Table

| Filter | Macro | Best For | Quant Safe? | Notes |
|--------|-------|----------|-------------|-------|
| **Gaussian Blur** | `run("Gaussian Blur...", "sigma=2");` | General noise reduction | Modifies values | sigma < smallest feature to keep |
| **Gaussian 3D** | `run("Gaussian Blur 3D...", "x=2 y=2 z=1");` | Z-stack smoothing | Modifies values | Typically lower z sigma (anisotropic) |
| **Median** | `run("Median...", "radius=2");` | Salt-and-pepper / hot pixels | Safer than Gaussian | Preserves edges; radius 3+ may remove puncta |
| **Median 3D** | `run("Median 3D...", "x=2 y=2 z=1");` | Z-stack shot noise | Safer than Gaussian | — |
| **Rolling Ball** | `run("Subtract Background...", "rolling=50");` | Uneven background | Essential for quant | radius >= 2-5x largest object radius |
| **Sliding Paraboloid** | `run("Subtract Background...", "rolling=50 sliding stack");` | More accurate bg subtraction | Essential for quant | Recommended over rolling ball |
| **Flat-Field (BaSiC)** | See below | Shading + temporal drift | Essential for quant | Needs image set; do FIRST |
| **Bandpass (FFT)** | `run("Bandpass Filter...", "filter_large=40 filter_small=3 suppress=None tolerance=5");` | Periodic noise / stripes | Display only | `suppress=Horizontal` for scan stripes |
| **Non-Local Means** | `run("Non-local Means Denoising", "sigma=15 smoothing_factor=1");` | Low-SNR, preserve fine detail | Better than Gaussian | Requires plugin; slower |
| **Unsharp Mask** | `run("Unsharp Mask...", "radius=2 mask=0.6");` | Edge sharpening for display | NOT safe | Display only |
| **Despeckle** | `run("Despeckle");` | Quick 3x3 median | Modifies values | No parameters |
| **Remove Outliers** | `run("Remove Outliers...", "radius=2 threshold=50 which=Bright");` | Hot/dead pixels | Targeted | Do before other processing |

### Rolling Ball Parameters

| Key | Type | Description |
|-----|------|-------------|
| `rolling` | float | Ball radius in pixels (REQUIRED) |
| `light` | flag | Light background (dark objects). Omit for fluorescence |
| `sliding` | flag | Sliding paraboloid (more accurate) |
| `disable` | flag | Skip pre-smoothing (faster) |
| `create` | flag | Output background estimate (debugging) |
| `stack` | flag | Process all slices |

**Radius guidance:** cells (~20-50 um) consider 50-100 px; nuclei (~5-15 um) consider 30-50 px; puncta consider 10-20 px. Err larger.

### Difference of Gaussians (DoG)

Bandpass enhancing features at a specific scale. sigma2/sigma1 typically ~1.6.

```javascript
run("Duplicate...", "title=blur1 duplicate");
run("Gaussian Blur...", "sigma=1 stack");
id1 = getImageID();
selectImage(id);
run("Duplicate...", "title=blur2 duplicate");
run("Gaussian Blur...", "sigma=3 stack");
imageCalculator("Subtract create 32-bit stack", "blur1", "blur2");
rename("DoG");
```

Not suitable for absolute intensity — use for object detection.

### Rank Filters (Mean / Min / Max / Variance)

```javascript
run("Mean...", "radius=2");         // smoothing (prefer Gaussian)
run("Minimum...", "radius=1");      // grayscale erosion, shrinks bright objects
run("Maximum...", "radius=1");      // grayscale dilation, expands bright objects
run("Variance...", "radius=2");     // highlights edges/texture, QC use
```
All accept `stack` keyword. 3D versions: `run("Minimum 3D...", "x=1 y=1 z=1");`

### BaSiC Flat-Field Correction

```javascript
run("BaSiC ", "input=stack_name flat-field=None dark-field=None" +
    " shading_estimation=[Estimate shading profiles]" +
    " shading_model=[Estimate both flat-field and dark-field]" +
    " setting_regularisation_parameters=Automatic" +
    " temporal_drift=Replace correction_options=[Compute shading and correct images]" +
    " lambda_flat=0.5 lambda_dark=0.5");
```

Manual flat-field: `imageCalculator("Divide create 32-bit", "raw_image", "flatfield_image");`

Install: Help > Update > Manage Update Sites > check "BaSiC" > Apply > Restart.

### CLIJ2 GPU Filters

```javascript
// Setup (required before any CLIJ2 call)
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_clear();
Ext.CLIJ2_push(input);

// Filters
Ext.CLIJ2_gaussianBlur3D(input, "blurred", 2, 2, 1);
Ext.CLIJ2_median3DBox(input, "denoised", 2, 2, 1);
Ext.CLIJ2_topHatBox("blurred", "bg_removed", 50, 50, 5);
Ext.CLIJ2_differenceOfGaussian3D(input, "dog", 1, 1, 1, 3, 3, 1);
Ext.CLIJ2_subtractGaussianBackground(input, "bg_sub", 50, 50, 5);

// Cleanup (always)
Ext.CLIJ2_pull("bg_removed");
Ext.CLIJ2_clear();
```

Also available: `mean2D/3DBox`, `minimum/maximum2D/3DBox`, `divideByGaussianBackground`, `CLIJx_nonLocalMeans`, `CLIJx_bilateral`.

### Filter Order of Operations

**For quantification:**
1. Flatfield correction / dark frame subtraction
2. Deconvolution (on raw data)
3. Background subtraction (rolling ball)
4. Minimal noise filtering (for segmentation aid only)
5. Threshold / segment
6. Measure on step-3 output (NOT filtered image)

Never filter after thresholding a binary mask.

---

## §4 Thresholding

### §4.1 Global Auto-Threshold Methods

```javascript
setAutoThreshold("METHOD dark");       // always add "dark" for fluorescence
run("Convert to Mask");

// Stack
setAutoThreshold("METHOD dark stack");
run("Convert to Mask", "method=METHOD background=Dark calculate");

// Inspect threshold value
setAutoThreshold("Otsu dark");
getThreshold(lower, upper);

// Compare all methods visually
run("Auto Threshold", "method=[Try all] white");
```

**Modifier keywords:** `dark` (fluorescence), `red`/`b&w`/`over/under`/`no-lut` (display), `no-reset` (keep display range on 16/32-bit).

#### Method Guide

| Method | Best For | Avoid When |
|--------|----------|------------|
| **Otsu** | Bimodal histograms, clear separation | Sparse bright objects |
| **Triangle** | Skewed histograms, sparse cells on dark bg | Bimodal distributions |
| **Li** | Dim signals, noisy images | Very sparse signals |
| **Huang** | Smooth intensity transitions | Very noisy; slow on 16-bit |
| **MaxEntropy** | Complex histograms, small bright features | Signal close to background |
| **Moments** | Maintaining statistical properties | Sparse or very bright signals |
| **RenyiEntropy** | Similar to MaxEntropy | No clear advantage in most IF |
| **Default/IsoData** | General purpose, bimodal | Very skewed histograms |
| **Intermodes/Minimum** | Clear bimodal with valley | Unimodal histograms (fails) |
| **MinError(I)** | Gaussian-distributed classes | Very skewed data |
| **Mean/Percentile/Shanbhag** | Rarely best for IF | — |

#### Scenario Recommendations

| Scenario | Consider | Why |
|----------|----------|-----|
| Sparse bright cells on dark bg | Triangle, Li | Designed for skewed histograms |
| Dense tissue, moderate signal | Otsu, Default | Balanced foreground/background |
| Dim signal, high background | Li, Triangle + bg subtraction first | Handles dim signals |
| High autofluorescence (FFPE) | Triangle/Li after bg subtraction; Phansalkar if uneven | — |
| Puncta / spots | MaxEntropy, RenyiEntropy; DoG first | Good for small bright features |
| Multiple intensity populations | Multi Otsu (`run("Multi Otsu Threshold", "levels=3");`) | — |

### §4.2 Local/Adaptive Threshold Methods

Use when background varies across the field of view.

```javascript
run("Auto Local Threshold", "method=Phansalkar radius=15 parameter_1=0 parameter_2=0 white");
run("Auto Local Threshold", "method=[Try all] radius=15 parameter_1=0 parameter_2=0 white");
// Add "stack" for z-stacks
```

| Method | param_1 (default) | param_2 (default) | Formula | Notes |
|--------|-------------------|-------------------|---------|-------|
| **Phansalkar** | k (0.25) | r (0.5) | Modified Sauvola for low-contrast | **Best for IF** |
| **Bernsen** | contrast thresh (15) | — | max-min local contrast | Good when contrast is reliable |
| **Otsu (local)** | — | — | Local Otsu per window | — |
| **Sauvola** | k (0.5) | r (128) | mean*(1+k*(stddev/r-1)) | — |
| **Niblack** | k (-0.2) | c (0) | mean+k*stddev-c | Often noisy in background |
| **Mean/Median/MidGrey** | c offset (0) | — | Local mean/median/midgrey - c | Simple |
| **Contrast** | — | — | Closest to local max or min | — |

**Radius guidance:** typically 15-25 px for IF. Too small = noisy; too large = loses adaptivity.

**Use global** when background is uniform. **Use local** when illumination is uneven or autofluorescence varies spatially.

### §4.3 Manual/Fixed Thresholding

For consistent thresholds across an experiment:
```javascript
setThreshold(500, 65535);  // determined from representative images
run("Convert to Mask");
```

---

## §5 Deconvolution

### When to Deconvolve

| Modality | Benefit | Typical Iterations |
|----------|---------|-------------------|
| Widefield z-stack | High (removes OOF haze) | 15-30 (RL) |
| Confocal z-stack | Modest (improves axial res) | 10-20 (RL) |
| Single 2D slice | Limited | Skip |
| Saturated image | None | Skip |

**Always deconvolve FIRST on raw data** — pre-processing alters noise statistics.

### PSF Generation

**Theoretical (Diffraction PSF 3D):**
```javascript
run("Diffraction PSF 3D",
    "type=WIDEFIELD na=1.4 wavelength=509 immersion_ri=1.515 sample_ri=1.33" +
    " pixel_spacing_lateral=65 pixel_spacing_axial=200 size_x=128 size_y=128 size_z=64");
```
Probe first: `python probe_plugin.py "Diffraction PSF 3D"`

**NA correction** when immersion != design medium: `NA_corrected = (n_actual / n_design) * NA_nominal`

**Measured PSF (gold standard):** Image sub-resolution beads (100-170 nm) under identical conditions. Crop single bead z-stack.

### DeconvolutionLab2

Install: Help > Update > Manage Update Sites > check "DeconvolutionLab2".

**Algorithm table:**

| Algorithm | Code | Parameters | Typical | Best For |
|-----------|------|-----------|---------|----------|
| Richardson-Lucy | `RL` | iterations | `RL 25` | Standard fluorescence |
| RL Total Variation | `RLTV` | iterations, lambda | `RLTV 25 0.001` | Noisy images |
| Tikhonov-Miller | `TM` | iterations, gamma, lambda | `TM 25 0.1 0.01` | When RL diverges |
| ICTM | `ICTM` | iterations, gamma, lambda | `ICTM 25 0.1 0.01` | TM + non-negativity |
| Landweber | `LW` | iterations, gamma | `LW 50 1.5` | Linear least squares |
| FISTA | `FISTA` | iterations, lambda | `FISTA 25 0.001` | Fast, sparsity prior |
| NNLS | `NNLS` | iterations | `NNLS 25` | Positive constraint |
| Naive Inverse | `NIF` | — | `NIF` | Quick preview only |
| Regularized Inverse | `RIF` | lambda | `RIF 0.001` | Quick preview |

**Key flags:** `-image platform TITLE`, `-psf platform TITLE`, `-algorithm CODE PARAMS`, `-out stack NAME`, `-pad mirror`, `-apo tukey`, `-constraint nonnegativity`

```javascript
// Standard RL
run("DeconvolutionLab2 Run",
    "-image platform input -psf platform psf -algorithm RL 25 -out stack result");

// RLTV (regularized, for noisy data)
run("DeconvolutionLab2 Run",
    "-image platform input -psf platform psf -algorithm RLTV 25 0.001" +
    " -pad mirror -apo tukey -constraint nonnegativity -out stack result");

// Batch (file paths)
run("DeconvolutionLab2 Run",
    "-image file /path/to/image.tif -psf file /path/to/psf.tif" +
    " -algorithm RLTV 25 0.001 -path /path/to/output/ -out stack result");
```

### Iterative Deconvolve 3D

Simpler alternative bundled with many Fiji installations.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `image` | string | — | Image title (REQUIRED) |
| `point` | string | — | PSF title (REQUIRED) |
| `output` | string | — | Output title (REQUIRED) |
| `normalize` | flag | off | Normalize PSF |
| `perform` | flag | off | Anti-ringing (recommended) |
| `detect` | flag | off | Auto-stop on divergence |
| `log` | flag | off | Log convergence |
| `wiener` | float | 0.0 | Wiener preconditioning (0.001-1.0) |
| `low` | float | 0.1 | Low-pass filter radius |
| `maximum` | int | 100 | Max iterations |
| `terminate` | float | 0.010 | Stop if change < this % |

```javascript
run("Iterative Deconvolve 3D",
    "image=input point=PSF output=Deconvolved" +
    " normalize perform detect log wiener=0.001 low=0.0 maximum=25 terminate=0.001");
```

### Deconvolution Artifacts

| Artifact | Cause | Solution |
|----------|-------|----------|
| Ringing (dark halos) | PSF mismatch, too many iterations | Measured PSF, fewer iterations, use RLTV |
| Noise amplification | Too many iterations | Reduce iterations, use RLTV (lambda=0.001) |
| Edge artifacts | FFT boundary effects | Use `-pad mirror`, anti-ringing, crop edges |
| Z-striping | Wrong z-spacing in PSF | Verify z-step matches PSF parameters |
| Over-sharpened | Excessive iterations | Reduce iterations, add regularization |

Better to slightly under-deconvolve than over-deconvolve.

---

## §6 Complete IF Processing Pipelines

### §6.1 Quantitative Intensity Measurement

```
RAW → Flatfield correction → Background subtraction → [Minimal filter] → Segment on DAPI → Measure on bg-subtracted image
```

```javascript
// Segment on nuclear channel
selectWindow("C1-DAPI"); run("Subtract Background...", "rolling=50");
setAutoThreshold("Otsu dark"); run("Convert to Mask"); run("Watershed");

// Measure marker channel (bg-subtracted, NOT filtered)
selectWindow("C2-marker"); run("Subtract Background...", "rolling=50");
run("Set Measurements...", "area mean integrated redirect=C2-marker decimal=3");
run("Analyze Particles...", "size=50-Infinity display");
```

### §6.2 Cell Counting

```javascript
run("Subtract Background...", "rolling=50");
run("Gaussian Blur...", "sigma=2");
setAutoThreshold("Triangle dark");   // sparse cells; Otsu for dense
run("Convert to Mask");
run("Watershed");
run("Analyze Particles...", "size=50-Infinity circularity=0.3-1.0 show=Outlines display summarize");
```

### §6.3 Colocalization

```javascript
// Per channel: background subtraction only — DO NOT filter (inflates coloc)
run("Subtract Background...", "rolling=50 stack");
// Optional: deconvolve each channel first (improves accuracy)

run("Coloc 2", "channel_1=C1 channel_2=C2 roi_or_mask=<None>" +
    " threshold_regression=Costes display_images_in_result" +
    " li_histogram_channel_1 li_histogram_channel_2 li_icq" +
    " spearman's_rank_correlation manders'_correlation costes'_significance_test" +
    " psf=3 costes_randomisations=10");
```

### §6.4 Publication Figures

```javascript
// After all measurements, work on a COPY
run("Duplicate...", "title=display duplicate");
setMinAndMax(lower, upper);    // display only — NEVER "Enhance Contrast" normalize
run("Green");                  // or Magenta — never red+green overlay
run("Merge Channels...", "c1=green c2=magenta create");
run("Scale Bar...", "width=20 height=4 font=14 color=White background=None location=[Lower Right]");
run("Flatten");
saveAs("Tiff", "/path/to/figure_panel.tif");
```

### §6.5 Z-Stack Processing

```javascript
// Deconvolve first (see Section 3), then:
run("Subtract Background...", "rolling=50 stack");
run("Gaussian Blur 3D...", "x=1 y=1 z=1");
run("Z Project...", "projection=[Max Intensity]");      // display
// OR: run("Z Project...", "projection=[Sum Slices]");   // quantification
```

---

## §7 Quick Decision Trees

### Choosing a Filter

```
Salt-and-pepper noise? → Median (radius=1-2)
Gaussian noise, fine structure critical? → Non-Local Means
Gaussian noise, structure not critical? → Gaussian blur (sigma=1-2)
Periodic stripes? → Bandpass filter
Uneven background? → Rolling ball subtraction
Unsure? → Gaussian (sigma=1), compare with median
```

### Choosing a Threshold Method

```
Bimodal histogram? → Otsu
Sparse bright objects? → Triangle
Dim / noisy signal? → Li
Uneven background across FOV? → Local threshold (Phansalkar)
Multiple classes? → Multi Otsu Threshold
Unsure? → run("Auto Threshold", "method=[Try all] white");
```

### Choosing a Deconvolution Approach

```
No z-stack? → Skip
Widefield z-stack? → RL 15-30 iterations (high benefit)
Confocal z-stack? → RL 10-20 iterations (modest benefit)
Have measured PSF? → Use it (gold standard)
No PSF? → Diffraction PSF 3D or PSF Generator (Gibson-Lanni)
Noisy? → RLTV (lambda=0.001)
Ringing artifacts? → Check PSF, reduce iterations, try RLTV
```

### Order of Operations

```
1. Flatfield correction / dark frame
2. Deconvolution (on raw)
3. Background subtraction
4. Minimal filtering (segmentation aid only)
5. Threshold / segment
6. Measure (on step-3 output)
7. Display adjustments (on a COPY): setMinAndMax → LUT → scale bar → flatten
```

---

## §8 Integration with ImageJAI Agent

### Installed IF Processing Plugins

| Plugin | Function |
|--------|----------|
| Iterative Deconvolve 3D | RL deconvolution |
| CLIJ2 (504 commands) | GPU-accelerated filtering + RL |
| Diffraction PSF 3D | Theoretical PSF generation |
| StarDist 2D/3D | DL nuclei segmentation |
| Cellpose | DL cell segmentation |
| Weka Segmentation | Trainable pixel classification |
| Coloc 2 / JACoP | Colocalization analysis |
| Bio-Formats | .nd2, .lif, .czi import |
| BaSiC | Flat-field correction |
| MorphoLibJ | Morphological ops, watershed |

### Related References

- `colocalization-reference.md` — 7 methods, decision trees, Coloc 2
- `domain-reference.md` — modalities, deconvolution theory, QC
- `macro-reference.md` — complete macro command reference
- `deconvolution-reference.md` — full deconvolution deep-dive

---

## §9 Best Practices and Pitfalls

### Operations That DESTROY vs PRESERVE Data

| Destroys Data | Preserves Data |
|---------------|---------------|
| `run("Enhance Contrast", "normalize")` | `setMinAndMax(low, high)` |
| `run("Apply LUT")` | `run("Brightness/Contrast...")` without Apply |
| `run("Convert to Mask")` | ROI creation/manipulation |
| `run("8-bit")` (precision lost) | `run("Duplicate...")` |
| Any filter (Gaussian, median, etc.) | Zooming, scrolling |
| `run("Subtract Background...")` | — |

### Common Mistakes

1. **Filtering before bg subtraction** — spreads background into foreground
2. **Enhance Contrast normalize** — permanently destroys quantitative data; use `setMinAndMax()`
3. **Different processing per condition** — all images must be processed identically
4. **Otsu on sparse cells** — use Triangle instead (designed for skewed histograms)
5. **Not checking for saturation** — saturated pixels have no quantitative info
6. **Deconvolving after filtering** — deconvolution needs raw noise statistics
7. **Red/green overlays** — ~8% of men are red-green colorblind; use green/magenta
8. **Measuring on MIP** — use Sum Slices for intensity quantification
9. **No bg subtraction for CTCF** — CTCF requires local background measurement

### Journal Guidelines Summary

All major journals require:
- Processing applied uniformly to entire image and equally to controls
- No selective enhancement/obscuring of features
- Linear adjustments (brightness/contrast) acceptable if uniform and documented
- Non-linear adjustments (gamma) must be disclosed
- Original unprocessed data retained and available on request
- Methods must state: software + version, all processing steps and parameters

**Methods template:**
```
Images were processed in Fiji (ImageJ v[X.Y.Z]). Background was subtracted
using rolling ball algorithm (radius=[N] pixels). [Optional: Gaussian/median
filter (sigma/radius=[N]) applied for noise reduction.] Thresholding used the
[method] algorithm. Display adjustments applied identically across all panels.
No non-linear adjustments were made.
```

---

## Sources

- [Rolling Ball Background Subtraction](https://imagej.net/plugins/rolling-ball-background-subtraction)
- [Auto Threshold](https://imagej.net/plugins/auto-threshold) / [Auto Local Threshold](https://imagej.net/plugins/auto-local-threshold)
- [DeconvolutionLab2 (EPFL)](https://bigwww.epfl.ch/deconvolution/deconvolutionlab2/)
- [Iterative Deconvolve 3D](https://imagej.net/plugins/iterative-deconvolve-3d)
- [Diffraction PSF 3D](https://imagej.net/plugins/diffraction-psf-3d)
- [CLIJ2 Reference](https://clij.github.io/clij2-docs/reference__filter)
- [BaSiC Plugin](https://www.nature.com/articles/ncomms14836)
- [Image Integrity — Nature Portfolio](https://www.nature.com/nature-portfolio/editorial-policies/image-integrity)
- [Intro to Bioimage Analysis](https://bioimagebook.github.io/)
- [Pete Bankhead ImageJ Intro](https://petebankhead.gitbooks.io/imagej-intro/)
- [ImageJ Built-in Macro Functions](https://wsr.imagej.net/developer/macro/functions.html)
