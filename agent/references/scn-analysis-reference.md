# SCN Image Analysis Reference

Workflows for SCN identification, bioluminescence rhythm analysis, fluorescence
quantification, live imaging, microglia morphology, and neurodegeneration studies.

---

## 1. SCN Identification & ROI Definition

### 1.1 Anatomical Landmarks

```
         ┌───────────────────┐
         │     Cortex        │
         │  ┌──┬──┴──┬──┐   │
         │  │ LV     LV │   │    ← Lateral ventricles
         │  └──┬──┴──┬──┘   │
         │     │ 3V  │      │    ← Third ventricle (midline)
         │  ┌──┤PVN  ├──┐   │    ← Paraventricular nucleus (dorsal)
         │  │  └──┬──┘  │   │
         │  │  ┌──┤──┐  │   │
         │  │  │SCN│SCN│  │  │    ← Suprachiasmatic nucleus (bilateral)
         │  │  └──┤──┘  │   │
         │  │  ═══╧═══  │   │    ← Optic chiasm (ventral landmark)
         └──┴───────────┴──┘
```

**Checklist:** 3V visible at midline, optic chiasm visible ventrally, bilateral dense nuclei above chiasm flanking 3V.

**Bregma range:** -0.34 to -0.82 mm (mid-SCN ~-0.46 to -0.58). Allen CCFv3 ID: 286 (SCH).

### 1.2 Manual ROI Macro

```javascript
// On DAPI or autofluorescence channel:
setTool("freehand");
waitForUser("Draw ROI around left SCN, then click OK");
roiManager("Add");
roiManager("Rename", "SCN_left");
waitForUser("Draw ROI around right SCN, then click OK");
roiManager("Add");
roiManager("Rename", "SCN_right");
roiManager("Save", "/path/to/SCN_ROIs.zip");
```

### 1.3 Semi-Automated Detection (DAPI density)

```javascript
// SCN has higher nuclear density than surrounding hypothalamus
run("Gaussian Blur...", "sigma=5");
run("Subtract Background...", "rolling=100");
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Fill Holes");
run("Open");
run("Analyze Particles...", "size=5000-50000 circularity=0.3-1.0 show=Masks");
// Adjust size range based on image calibration
```

### 1.4 Atlas Registration (ABBA)

For cross-animal comparison: register to Allen CCFv3 in ABBA, export SCH (ID 286) as ROI. See `brain-atlas-registration-reference.md`.

**Limitation:** Allen CCFv3 does NOT subdivide SCN into dorsal/ventral — must do manually.

### 1.5 Dorsal/Ventral SCN Subdivision

| Method | Approach | When to use |
|--------|----------|-------------|
| Geometric split | Divide SCN ROI at midpoint (dorsal/ventral halves) | Quick, no extra staining |
| Marker-based | Threshold VIP (core) and AVP (shell) channels | Most accurate, requires multi-channel IF |
| Functional | Phase map — dorsal peaks 1–4 h before ventral | For bioluminescence/calcium live imaging |

```javascript
// Geometric split example
roiManager("Select", scnIndex);
getBoundingRect(x, y, w, h);
makeRectangle(x, y, w, h/2);
roiManager("Add"); roiManager("Rename", "SCN_dorsal");
makeRectangle(x, y + h/2, w, h/2);
roiManager("Add"); roiManager("Rename", "SCN_ventral");
```

---

## 2. Bioluminescence Rhythm Analysis

### 2.1 Typical Acquisition Parameters

| Parameter | Typical value |
|-----------|---------------|
| Reporter | PER2::LUC knockin |
| Luciferin | 0.1–0.2 mM D-luciferin |
| Camera | EMCCD, cooled to -70C minimum |
| Objective | 4x–10x, NA 0.3–0.5 |
| Exposure | 25–59 min/frame |
| Binning | 4x4 or 8x8 |
| Duration | 5–7 days recommended |
| Temperature | 36–37C (stable within 0.5C) |

### 2.2 Pre-processing Pipeline

```javascript
// 1. Cosmic ray removal
run("Top Hat...", "radius=2");

// 2. Motion correction
run("StackReg", "transformation=Translation");
// See registration-stitching-reference.md Section 17.1 for optimised approach

// 3. Spatial smoothing (optional)
run("Gaussian Blur...", "sigma=2 stack");

// 4. Define SCN ROI on mean projection
run("Z Project...", "projection=[Average Intensity]");
```

### 2.3 ROI Time Series Extraction

```javascript
run("Set Measurements...", "mean integrated redirect=None decimal=4");
for (r = 0; r < roiManager("count"); r++) {
    for (s = 1; s <= nSlices(); s++) {
        setSlice(s);
        roiManager("select", r);
        run("Measure");
    }
}
// IMPORTANT: Include a background ROI — subtract background mean per timepoint
```

### 2.4 Detrending Methods

Bioluminescence decays due to luciferin consumption — MUST detrend before rhythm analysis.

| Method | Best for | Caution |
|--------|----------|---------|
| Moving average (24 h) | General purpose | Window MUST equal one period exactly |
| Polynomial (degree 2–3) | Slow monotonic drift | Can distort at edges |
| Sinc filter (pyBOAT) | Non-stationary baselines | Typically best all-round |
| Hodrick-Prescott | Shan et al. 2020 method | Smooth trend removal |
| Sliding percentile | Common default | Robust to transients |

### 2.5 Pixel-by-Pixel Period/Phase Mapping

```python
import numpy as np
from scipy.fft import rfft, rfftfreq

def compute_pixel_maps(stack, dt_hours, period_range=(20, 28)):
    """Period, phase, amplitude maps from bioluminescence stack."""
    nt, ny, nx = stack.shape
    period_map = np.full((ny, nx), np.nan)
    phase_map = np.full((ny, nx), np.nan)
    amplitude_map = np.full((ny, nx), np.nan)
    time = np.arange(nt) * dt_hours
    freq = rfftfreq(nt, d=dt_hours)
    f_min, f_max = 1/period_range[1], 1/period_range[0]
    circ_mask = (freq >= f_min) & (freq <= f_max)

    for y in range(ny):
        for x in range(nx):
            ts = stack[:, y, x].astype(float)
            if np.std(ts) < np.mean(ts) * 0.05:
                continue
            ts_det = ts - np.polyval(np.polyfit(time, ts, 2), time)
            fft_vals = np.abs(rfft(ts_det))
            circ_power = fft_vals.copy()
            circ_power[~circ_mask] = 0
            if np.max(circ_power) > 0:
                peak_idx = np.argmax(circ_power)
                period_map[y, x] = 1.0 / freq[peak_idx]
                fft_c = rfft(ts_det)
                phase_map[y, x] = np.arctan2(fft_c[peak_idx].imag, fft_c[peak_idx].real)
                amplitude_map[y, x] = np.abs(fft_c[peak_idx])
    phase_hours = (phase_map % (2*np.pi)) * 24 / (2*np.pi)
    return period_map, phase_hours, amplitude_map
```

### 2.6 Rhythm Metrics

| Metric | How to compute | Typical SCN value |
|--------|---------------|-------------------|
| Period | FFT peak / cosinor / autocorrelation | 23.5–24.5 h |
| Amplitude | Cosinor A or (peak-trough)/2 | Varies by reporter |
| Phase (acrophase) | Cosinor phi or peak time | CT12–16 for PER2 |
| Damping rate | Exponential fit to peak envelope | SCN: minimal (weeks) |
| Goodness of rhythm | Cosinor R^2 | >0.3 rhythmic, >0.5 strong |
| Kuramoto R (synchrony) | mean(exp(i*phase)) across cells | >0.7 synchronised |

### 2.7 Software Tools

| Tool | Type | Strength |
|------|------|----------|
| BioDare2 | Web | Free, multiple methods, publication figures |
| pyBOAT | Python | Wavelet time-frequency, GUI + API |
| CircaCompare (R) | R package | Statistical comparison between groups |
| MetaCycle (R) | R package | Meta-analysis of multiple period methods |
| CosinorPy | Python | Cosinor fitting, differential rhythmicity |

---

## 3. Fluorescence Microscopy of SCN

### 3.1 Common Staining Panels

| Experiment | Ch1 | Ch2 | Ch3 | Ch4 |
|-----------|-----|-----|-----|-----|
| Clock protein quantification | DAPI | PER2 | BMAL1 | — |
| Cell-type identification | DAPI | VIP (core) | AVP (shell) | — |
| Neuron/astrocyte distinction | DAPI | NeuN | GFAP/S100b | Clock protein |
| Microglia in SCN | DAPI | Iba1 | TMEM119 | Clock protein |
| Neurodegeneration | DAPI | Amyloid (MOAB-2/6E10) | Iba1 | GFAP |

### 3.2 Cell Counting

```javascript
// StarDist for nuclear counting (DAPI channel)
run("Command From Macro", "command=[de.csbdresden.stardist.StarDist2D], args=['input':'DAPI', 'modelChoice':'Versatile (fluorescent nuclei)', 'probThresh':'0.5', 'nmsThresh':'0.4', 'outputType':'Both']");

// Or threshold + watershed:
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Watershed");
run("Analyze Particles...", "size=20-200 circularity=0.5-1.0 show=Outlines display");

// Report: total DAPI+ / marker+ per SCN, % positive, by subdivision
```

### 3.3 CTCF (Corrected Total Cell Fluorescence)

```javascript
run("Set Measurements...", "area mean integrated redirect=None decimal=4");
// Measure background regions (non-SCN hypothalamus)
// For each cell/SCN region:
roiManager("Select", scnROI);
run("Measure");
// CTCF = IntDen - (Area * MeanBackground)
```

### 3.4 Z-Stack Handling

| Purpose | Projection | Notes |
|---------|-----------|-------|
| Visualisation / counting | Max Intensity | Standard |
| Intensity quantification | Sum Slices | Preserves total signal |
| 3D cell counting | 3D Objects Counter | Direct on stack |
| Colocalization | Per-plane or 3D | **Never project before coloc** — creates false overlap |

### 3.5 Colocalization

```javascript
// PER2 vs VIP within SCN ROI
roiManager("Select", scnROI);
run("Coloc 2", "channel_1=C2-PER2 channel_2=C3-VIP roi_or_mask=<None> threshold_regression=Costes costes_randomisations=100 psf=3");
// Key outputs: Pearson's R, Manders' M1/M2, Costes p-value (>0.95 = significant)
```

---

## 4. Live Imaging Analysis

### 4.1 Incucyte Time-Lapse

Typical: 6/24-well with Millicell inserts, 30–60 min interval, phase + GFP/RFP, 5–14 days, 36–37C.

Workflow: export TIFF sequence → assemble stack → register → draw ROIs on mean projection → extract time series → rhythm analysis (Section 2).

### 4.2 GCaMP Calcium Imaging

```python
def compute_dff(signal, baseline_percentile=10, window_hours=1, dt_hours=0.017):
    """dF/F using sliding percentile baseline."""
    from scipy.ndimage import uniform_filter1d, minimum_filter1d
    window = int(window_hours / dt_hours)
    f0 = uniform_filter1d(minimum_filter1d(signal, window), window)
    return (signal - f0) / f0, f0
```

### 4.3 Phase Map Generation (Hilbert Transform)

```python
from scipy.signal import hilbert, butter, filtfilt

def scn_phase_map(stack, dt_hours, band=(20, 28)):
    """Circadian phase map. CRITICAL: bandpass filter first."""
    nt, ny, nx = stack.shape
    fs = 1.0 / dt_hours
    nyq = fs / 2
    b, a = butter(3, [(1.0/band[1])/nyq, (1.0/band[0])/nyq], btype='band')
    phase_map = np.full((ny, nx), np.nan)
    for y in range(ny):
        for x in range(nx):
            ts = stack[:, y, x].astype(float)
            if np.std(ts) < 0.01 * np.mean(ts):
                continue
            ts_det = ts - np.polyval(np.polyfit(np.arange(nt), ts, 2), np.arange(nt))
            analytic = hilbert(filtfilt(b, a, ts_det))
            phase_map[y, x] = np.angle(analytic[nt // 2])
    return (phase_map % (2*np.pi)) * 24 / (2*np.pi)
```

Typical gradient: dorsal SCN peaks 1–4 h before ventral (dorsomedial → ventrolateral wave).

### 4.4 Kuramoto Order Parameter

```python
def scn_synchrony(phases_matrix):
    """Time-varying synchrony (0=desync, 1=perfect sync)."""
    Z = np.mean(np.exp(1j * phases_matrix), axis=0)
    return np.abs(Z), np.angle(Z)
# R > 0.7: strong sync (intact SCN); 0.3–0.7: partial; < 0.3: desynchronised
```

---

## 5. Microglia Analysis in SCN

### 5.1 Markers

| Marker | Target | Notes |
|--------|--------|-------|
| Iba1 | All microglia | Gold standard, labels soma + processes |
| CX3CR1-GFP | Microglia (heterozygous) | Live imaging; haploinsufficiency caveat |
| TMEM119 | Homeostatic | Distinguishes from macrophages |
| P2RY12 | Homeostatic | Circadian-regulated |
| CD68 | Phagocytic/activated | Lysosomal marker |

### 5.2 Skeleton Analysis (Young & Morrison, JoVE 2018)

```javascript
// Preprocessing
run("8-bit");
run("Unsharp Mask...", "radius=3 mask=0.60");
run("Despeckle");

// Binarisation — adjust threshold to capture processes without merging cells
run("Threshold...");
run("Convert to Mask");
run("Despeckle");
run("Close-");
run("Remove Outliers...", "radius=2 threshold=50 which=Bright");

// Skeleton analysis
run("Skeletonize");
run("Analyze Skeleton (2D/3D)", "prune=[none] show");
// Outputs: endpoints, branches, junctions, branch length per skeleton
// Per-cell: divide totals by soma count
```

### 5.3 Fractal Analysis (FracLac)

Isolate single cell in consistent ROI (consider ~120x120 um), erase neighbours, then:

```javascript
run("Outline");
// Plugins > Fractal Analysis > FracLac: BC method, Num G = 4, check Metrics
// Outputs: fractal dimension (DB: resting ~1.3–1.5, activated ~1.1–1.2),
// lacunarity, density, span ratio, circularity, convex hull area
```

### 5.4 Sholl Analysis

```javascript
// After isolating + skeletonising single cell:
makePoint(soma_x, soma_y);
run("Sholl Analysis...", "starting=5 ending=0 radius_step=5 #_samples=1 integration=N/A enclosing=1 #_primary=[] infer fit linear polynomial=[Best fitting degree] most semi-log normalizer=Default");
// Key: max intersections, critical radius, ramification index, enclosing radius
```

### 5.5 MotiQ (Automated)

Three-step plugin: MotiQ_Cropper → MotiQ_Thresholder → MotiQ_2D/3D_Analyzer. Extracts 60+ parameters (ramification index, territory, soma area, process tree, motility).

### 5.6 Density & Distribution

```javascript
// Automated counting
run("Subtract Background...", "rolling=30");
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Watershed");
run("Analyze Particles...", "size=30-500 circularity=0.1-1.0 show=Outlines display summarize");
// Spatial distribution: export centroids, compute NND, Clark-Evans R
```

---

## 6. Amyloid/Neurodegeneration in SCN

### 6.1 Plaque Quantification

**DAB-stained sections:**
```javascript
run("Colour Deconvolution", "vectors=[H DAB]");
selectWindow("Colour Deconvolution - DAB");
roiManager("Select", scnROI);
run("Set Measurements...", "area area_fraction limit redirect=None decimal=4");
setAutoThreshold("Otsu");  // or fixed threshold — keep CONSTANT across batch
run("Measure");  // Area Fraction = % SCN covered by amyloid
run("Analyze Particles...", "size=10-Infinity show=Outlines display summarize");
```

**Thioflavin S (fluorescence):**
```javascript
run("8-bit");
roiManager("Select", scnROI);
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Analyze Particles...", "size=10-Infinity circularity=0.2-1.0 show=Outlines display summarize");
// Report: plaque count, density, mean area, area fraction, size distribution
```

### 6.2 Microglia-Plaque Proximity

```python
from scipy.ndimage import distance_transform_edt

def microglia_plaque_distances(plaque_mask, microglia_centroids):
    dist_map = distance_transform_edt(~plaque_mask)
    return np.array([dist_map[int(y), int(x)] for x, y in microglia_centroids])

# Proximity zones (literature standard):
# 0–10 um: plaque-associated microglia (PAM)
# 10–20 um: peri-plaque
# 20–50 um: near-plaque
# >50 um: plaque-distal
```

### 6.3 Plaque-Associated Morphology

Classify microglia by distance zone, quantify per zone (ramification index, fractal dimension, soma/territory ratio, process length). Compare across zones with one-way ANOVA or Kruskal-Wallis.

---

## 7. Batch Processing

### 7.1 Batch Cell Counting

```javascript
input = "/path/to/scn_sections/";
output = "/path/to/results/";
list = getFileList(input);
setBatchMode(true);
for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif")) {
        open(input + list[i]);
        run("Split Channels");
        selectWindow("C1-" + list[i]);  // DAPI
        run("Gaussian Blur...", "sigma=2");
        setAutoThreshold("Otsu dark");
        run("Convert to Mask");
        run("Watershed");
        run("Analyze Particles...", "size=20-200 circularity=0.5-1.0 summarize");
        close("*");
    }
}
setBatchMode(false);
selectWindow("Summary");
saveAs("Results", output + "cell_counts_summary.csv");
```

---

## 8. Statistical Considerations

### 8.1 Biological vs Technical Replicates

| Analysis | Biological replicate | Technical replicate |
|----------|---------------------|-------------------|
| Cell counting | One mouse | Multiple sections/mouse |
| Plaque area fraction | One mouse | Multiple sections/mouse |
| Microglia morphology | One cell (from independent mice) | Multiple measurements/cell |
| Bioluminescence rhythms | One SCN slice | Multiple ROIs/slice |

**Critical:** Average within-mouse first, then compare between mice. N = mice, not sections.

### 8.2 Circadian Statistics

| Comparison | Test |
|-----------|------|
| Phase | Watson-Williams F-test (circular) or CircaCompare |
| Period | t-test or ANOVA (linear) |
| Amplitude | CircaCompare or ANOVA |
| Rhythmicity | Cosinor F-test, JTK_CYCLE, or RAIN |
| Multiple ROIs | Benjamini-Hochberg FDR correction |

### 8.3 Sample Size Guidelines

| Analysis | Minimum N | Recommended N |
|----------|-----------|---------------|
| SCN cell counts | 3/group | 5–6/group |
| Plaque area fraction | 4/group | 6–8/group |
| Biolum rhythms | 3 slices | 5–8 slices |
| Phase comparison | 6/group | 8–12/group |

---

## 9. Common Pitfalls

| Category | Pitfall | Guidance |
|----------|---------|----------|
| SCN ID | Wrong bregma level | SCN spans only ~0.5 mm A-P |
| SCN ID | Confusing SCN with SON | SCN is medial (flanking 3V); SON is lateral to optic chiasm |
| Biolum | Luciferin depletion decay | Normal — detrend it |
| Biolum | Wrong moving average window | MUST be exactly 24 h |
| Biolum | Phase from single peak | Unreliable — use >=3 cycles |
| Biolum | Comparing absolute intensity | Use relative measures (period, phase, normalised amplitude) |
| Fluorescence | Enhance Contrast on data | NEVER. Use setMinAndMax() for display only |
| Fluorescence | Z-project before coloc | Creates false overlap. Analyse per-plane or 3D |
| Microglia | Inconsistent thresholds | Same threshold across entire study |
| Microglia | Incomplete process capture | Iba1 quality varies — processes must be fully captured |
| Plaques | Adjusting threshold per-image | Set once per batch, apply identically |
| Plaques | ThioS vs antibody confusion | ThioS = dense-core only; antibodies = all Abeta |

---

## 10. Cross-References

| Topic | Reference file |
|-------|---------------|
| Circadian rhythm methods | `circadian-analysis-reference.md` |
| Brain atlas registration | `brain-atlas-registration-reference.md` |
| Colocalization | `colocalization-reference.md` |
| Neurite/microglia tracing (SNT, Sholl) | `neurite-tracing-reference.md` |
| Amyloid histology | `histology-neurodegeneration-reference.md` |
| Colour deconvolution (DAB) | `colour-deconvolution-histology-reference.md` |
| Cell counting (StarDist) | `ai-image-analysis-reference.md` |
| Statistics | `statistics-reference.md` |
| 3D spatial analysis | `3d-spatial-reference.md` |
| Batch processing | `batch-processing-reference.md` |
