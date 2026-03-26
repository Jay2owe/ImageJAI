# Calcium Imaging Analysis Reference

Reference for calcium imaging analysis using ImageJ/Fiji and Python.
Covers preprocessing, trace extraction, event detection, population analysis,
and quantification for an AI agent controlling Fiji via TCP.

---

## Table of Contents

1. [Quick Start](#1-quick-start)
2. [Indicator Selection](#2-indicator-selection)
3. [Motion Correction](#3-motion-correction)
4. [ROI Selection & Trace Extraction](#4-roi-selection--trace-extraction)
5. [Baseline & dF/F Calculation](#5-baseline--dff-calculation)
6. [Ratiometric Imaging (Fura-2)](#6-ratiometric-imaging-fura-2)
7. [Photobleaching Correction](#7-photobleaching-correction)
8. [Event Detection](#8-event-detection)
9. [Frequency & Spectral Analysis](#9-frequency--spectral-analysis)
10. [Population & Network Analysis](#10-population--network-analysis)
11. [ImageJ Plugins](#11-imagej-plugins)
12. [Agent Workflows](#12-agent-workflows)
13. [Common Problems & Solutions](#13-common-problems--solutions)
14. [Exporting Data](#14-exporting-data)
15. [Decision Trees](#15-decision-trees)
16. [Parameter Quick-Reference](#16-parameter-quick-reference)

---

## 1. Quick Start

```
Open recording (Bio-Formats)
  -> Motion correct (StackReg rigid body)
    -> Draw ROIs on mean projection (soma + background)
      -> Multi Measure all ROIs across all frames
        -> Export CSV
          -> Python: subtract background, compute dF/F0, detect events
```

```bash
# 1. Open and inspect
python ij.py macro 'run("Bio-Formats Importer", "open=/path/to/recording.tif color_mode=Default view=Hyperstack stack_order=XYCZT");'
python ij.py info
python ij.py metadata

# 2. Motion correct
python ij.py macro 'run("StackReg", "transformation=[Rigid Body]");'

# 3. Mean projection for ROI placement
python ij.py macro 'run("Z Project...", "projection=[Average Intensity]"); rename("AVG_projection");'
python ij.py capture after_projection

# 4. Auto-detect cells
python ij.py macro '
  selectWindow("AVG_projection");
  run("Duplicate...", "title=for_detection");
  run("Gaussian Blur...", "sigma=3");
  run("Find Maxima...", "prominence=500 output=[Point Selection]");
  run("ROI Manager..."); roiManager("Add");
'

# 5. Multi Measure on original stack
python ij.py macro '
  selectWindow("recording.tif");
  run("Set Measurements...", "mean redirect=None decimal=6");
  roiManager("Multi Measure");
'
python ij.py results
```

---

## 2. Indicator Selection

### Single-Wavelength vs Ratiometric

| Property | Single-wavelength | Ratiometric |
|---|---|---|
| Examples | Fluo-4, GCaMP, Cal-520 | Fura-2, Indo-1 |
| Measurement | Intensity change at one wavelength | Ratio of two wavelengths |
| Advantages | Simpler, faster | Self-correcting for concentration, bleaching |
| Quantification | Relative (dF/F) | Absolute [Ca2+] possible with calibration |

### Key GCaMP Variants

| Variant | Rise t1/2 (ms) | Decay t1/2 (ms) | dF/F (1AP) | Kd (nM) | Best For |
|---|---|---|---|---|---|
| GCaMP6s | 179 | 550 | 22-25% | 144 | Max sensitivity, slow processes |
| GCaMP6f | 45 | 142 | 11-18% | 375 | Fast processes, high firing rates |
| jGCaMP8f | 2 | 56 | ~18% | 334 | Ultrafast, resolving single spikes |
| jGCaMP8m | 5 | 80 | ~24% | 108 | Fast + sensitive, general purpose |
| jGCaMP8s | 10 | 180 | ~38% | 46 | Maximum single-AP sensitivity |

**Trade-off**: Slow (s) variants give brightest response per spike but smooth over rapid firing. Fast (f) variants resolve individual spikes but with smaller dF/F. Medium (m) is a general-purpose compromise.

### Common Chemical Indicators

| Indicator | Ex/Em (nm) | Kd (nM) | Notes |
|---|---|---|---|
| Fluo-4 AM | 494/516 | 345 | Standard green, 488 nm excitable |
| Cal-520 AM | 494/514 | 320 | Brightest green, best for local signals |
| Rhod-2 AM | 552/581 | 570 | Red, mitochondrial loading, multiplexable |
| Fura-2 AM | 340,380/510 | 224 | Gold standard ratiometric, UV excitation |

### Indicator Decision Tree

```
Need absolute [Ca2+]? -> YES -> Fura-2 (ratiometric, calibratable)
  NO -> In vivo deep imaging (>200 um)? -> YES -> GCaMP + two-photon
  NO -> Need to resolve individual spikes? -> YES -> jGCaMP8f (or 8m)
  NO -> Need max sensitivity? -> YES -> jGCaMP8s or GCaMP6s
  NO -> Multiplex with optogenetics?
    Blue light stim -> jRGECO1a (red)
    Red light stim -> GCaMP (green)
  NO -> Acute experiment? -> YES -> Chemical (Fluo-4 AM, Cal-520 AM)
  NO -> Chronic imaging? -> YES -> GECI (viral or transgenic)
```

---

## 3. Motion Correction

### When Needed

| Preparation | Typical Motion | Correction |
|---|---|---|
| Cultured cells (coverslip) | Minimal drift | Usually not needed |
| Acute brain slice | Slow drift | Rigid body typically sufficient |
| In vivo (head-fixed) | Breathing/heartbeat 1-5 px | Always needed |
| In vivo (freely moving) | >10 px | Non-rigid often needed |
| Organotypic slice | Slow drift over hours | Needed for long recordings |

### Methods

| Method | Command | When to Use |
|---|---|---|
| StackReg Translation | `run("StackReg", "transformation=Translation");` | XY shift only, fastest |
| StackReg Rigid Body | `run("StackReg", "transformation=[Rigid Body]");` | Translation + rotation (standard) |
| Correct 3D Drift | `run("Correct 3D Drift", "channel=1 only=0 lowest=1 highest=1");` | Z-stacks over time |
| moco | `run("moco", "w=20 downsample=2");` | Large displacements, faster than StackReg |
| bUnwarpJ | See elastic registration reference | Non-rigid (slow, consider Python instead) |

### Quality Check

```bash
# Temporal SD projection -- halos around cells = residual motion
python ij.py macro '
  run("Z Project...", "projection=[Standard Deviation]");
  rename("temporal_SD");
'
python ij.py capture temporal_SD
```

Clean cell outlines in temporal SD = good correction. Halos = incomplete.

**Choosing motion correction**: For most calcium imaging, StackReg Rigid Body is sufficient. Only use non-rigid (CaImAn/Suite2p) for in vivo freely-moving or visible tissue warping.

---

## 4. ROI Selection & Trace Extraction

### ROI Types

| ROI Type | Typical Size | Notes |
|---|---|---|
| Soma | 8-20 um (neurons), 5-10 um (glia) | Main signal source |
| Dendrite | 1-3 um wide | Lower SNR |
| Neuropil | 20-40 um outer annulus | For contamination correction |
| Background | Any rectangle in cell-free area | For subtraction |

### Automated Cell Detection

```javascript
// Threshold-based detection on mean projection
selectWindow("mean_proj");
run("Duplicate...", "title=for_detection");
run("Gaussian Blur...", "sigma=3");  // merge pixels into cell-sized blobs
setAutoThreshold("Triangle dark");    // consider Triangle, Otsu, or Li
run("Convert to Mask");
run("Watershed");                     // separate touching cells
run("Analyze Particles...", "size=50-2000 circularity=0.3-1.0 add");
// Adjust size range based on cell diameter and pixel scale
```

**Alternative**: For densely packed cells, consider StarDist:
```javascript
run("Command From Macro", "command=[de.csbdresden.stardist.StarDist2D], "
    + "args=['input':'mean_proj', 'modelChoice':'Versatile (fluorescent nuclei)', "
    + "'normalizeInput':'true', 'probThresh':'0.5', 'nmsThresh':'0.3', "
    + "'outputType':'ROI Manager']");
```

### Background ROI

Always include a background ROI for subtraction:
```javascript
makeRectangle(10, 10, 30, 30);  // adjust to cell-free area
roiManager("Add");
roiManager("Select", roiManager("count") - 1);
roiManager("Rename", "Background");
```

### Trace Extraction

```javascript
// Multi Measure: extracts mean intensity per ROI per frame
selectWindow("registered_stack");
run("Set Measurements...", "mean redirect=None decimal=6");
roiManager("Deselect");
roiManager("Multi Measure");
// Result: columns Mean1, Mean2, ..., MeanN; one row per frame
```

### Save/Load ROIs

```javascript
roiManager("Deselect");
roiManager("Save", "/path/to/ROIs.zip");
// Load: roiManager("Open", "/path/to/ROIs.zip");
```

---

## 5. Baseline & dF/F Calculation

### The Equation

```
dF/F0 = (F(t) - F0) / F0
```

Where F(t) is background-subtracted fluorescence and F0 is the baseline.

### Choosing a Baseline Method

| Method | Formula | Best For | Avoid When |
|---|---|---|---|
| Fixed window | mean(F[1:N]) | Stimulation with quiet pre-stim | Spontaneous activity |
| Sliding percentile (8th) | percentile_8(F[t-W/2:t+W/2]) | Spontaneous, long recordings | Cell active >80% of time |
| Sliding percentile (20th) | percentile_20(window) | Moderate activity | Very high or very low activity |
| Median | median(F) | Infrequent events (<50% active) | Frequent activity, bleaching |
| Exponential fit | A*exp(-t/tau)+C | Chemical indicators with bleaching | GECIs with minimal bleaching |

**How to choose window size for sliding percentile**: Typically 20-60 seconds. The window should be long enough to span several inter-event intervals so the percentile captures true baseline, not event peaks.

**How to choose percentile**: 8th percentile is standard for active neurons. If cells are active >80% of the time, consider 5th percentile with a longer window (60-120s).

### Python dF/F

```python
import numpy as np
from scipy.ndimage import percentile_filter

def compute_dff(traces, frame_rate, method='sliding_percentile',
                baseline_frames=30, window_s=30.0, percentile=8):
    """
    Compute dF/F. traces shape: (n_cells, n_frames).
    Returns dff same shape.
    """
    if method == 'fixed_window':
        f0 = np.mean(traces[:, :baseline_frames], axis=1, keepdims=True)
    elif method == 'sliding_percentile':
        win = int(window_s * frame_rate)
        if win % 2 == 0: win += 1
        f0 = np.zeros_like(traces)
        for i in range(traces.shape[0]):
            f0[i] = percentile_filter(traces[i], percentile, size=win)
    elif method == 'median':
        f0 = np.median(traces, axis=1, keepdims=True)
    f0 = np.maximum(f0, 1e-6)
    return (traces - f0) / f0
```

### dF/F Image Stack in ImageJ

```javascript
title = getTitle();
run("Z Project...", "start=1 stop=30 projection=[Average Intensity]");
rename("F0");
selectWindow(title); run("32-bit");
selectWindow("F0"); run("32-bit");
selectWindow(title);
run("Duplicate...", "title=dFF duplicate");
imageCalculator("Subtract stack", "dFF", "F0");
imageCalculator("Divide stack", "dFF", "F0");
selectWindow("dFF");
setMinAndMax(-0.1, 2.0);
run("Fire");
```

**Gotcha**: Do NOT clip negative dF/F values to zero -- they contain information (noise below baseline or real Ca2+ decreases after hyperpolarization).

---

## 6. Ratiometric Imaging (Fura-2)

### Ratio Calculation

Fura-2: R = F340/F380 increases with [Ca2+], independent of dye concentration and bleaching.

```javascript
// For interleaved stack (340, 380, 340, 380, ...)
title = getTitle();
run("Deinterleave", "how=2");
selectWindow(title + " #1"); rename("F340"); run("32-bit");
selectWindow(title + " #2"); rename("F380"); run("32-bit");

// Background subtract (measure bg in cell-free region first)
bg340 = 100;  // REPLACE with measured value
bg380 = 120;  // REPLACE with measured value
selectWindow("F340"); run("Subtract...", "value=" + bg340 + " stack");
selectWindow("F380"); run("Subtract...", "value=" + bg380 + " stack");

// Ratio
imageCalculator("Divide create 32-bit stack", "F340", "F380");
rename("Ratio_340_380");
setMinAndMax(0.2, 3.0);  // adjust to data
run("Fire");
```

For 2-channel hyperstacks, use `run("Split Channels");` instead of Deinterleave.

### Grynkiewicz Calibration to [Ca2+]

```
[Ca2+] = Kd * ((R - Rmin) / (Rmax - R)) * (Sf2 / Sb2)
```

| Parameter | Definition | How to Obtain |
|---|---|---|
| Kd | Dissociation constant | ~224 nM at 37C, ~135 nM at 22C |
| Rmin | Ratio at zero Ca2+ | EGTA + ionomycin at end of experiment |
| Rmax | Ratio at saturating Ca2+ | High Ca2+ + ionomycin at end of experiment |
| Sf2 | F380 at zero Ca2+ | Measured during Rmin calibration |
| Sb2 | F380 at saturating Ca2+ | Measured during Rmax calibration |

```python
def grynkiewicz(ratio, Kd=224, Rmin=0.3, Rmax=5.0, Sf2=800, Sb2=200):
    ratio_clipped = np.clip(ratio, Rmin + 1e-6, Rmax - 1e-6)
    return Kd * ((ratio_clipped - Rmin) / (Rmax - ratio_clipped)) * (Sf2 / Sb2)
```

---

## 7. Photobleaching Correction

### When It Matters

| Factor | Effect on Bleaching |
|---|---|
| Chemical dyes vs GECIs | Chemical bleach faster |
| UV excitation (Fura-2) | More bleaching than visible |
| Higher excitation intensity | More bleaching |
| Two-photon | Less bleaching (focal point only) |

**Gotcha**: Correct bleaching BEFORE computing dF/F. The sliding percentile method partially handles bleaching automatically.

### Correction Methods

| Method | Command/Approach | Best For |
|---|---|---|
| Exponential Fit | `run("Bleach Correction", "correction=[Exponential Fit]");` | Most cases |
| Simple Ratio | `run("Bleach Correction", "correction=[Simple Ratio] background=0");` | Uniform bleaching |
| Histogram Matching | `run("Bleach Correction", "correction=[Histogram Matching]");` | Visualization only (NOT quantitative) |
| Sliding percentile dF/F | (inherent in baseline method) | Combined bleaching + baseline |
| Reference region | Normalize to stable non-responsive region | When stable reference exists |

### Quick Bleach Check

```javascript
// Plot mean intensity over time
run("Plot Z-axis Profile");
// Downward slope = bleaching is significant
```

---

## 8. Event Detection

### Event Parameters

| Parameter | Definition | Unit |
|---|---|---|
| Amplitude | Peak dF/F - baseline | dF/F |
| Rise time | 10% to 90% of peak | seconds |
| Decay time (tau) | Peak to 50% decay | seconds |
| Duration | Time above threshold (FWHM) | seconds |
| AUC | Integral of dF/F during event | dF/F * seconds |

### Threshold Selection

| Threshold | Character | When to Consider |
|---|---|---|
| 2 SD | Sensitive, more false positives | Low SNR, don't want to miss events |
| 3 SD | Standard balance | General purpose starting point |
| 4-5 SD | Conservative, few false positives | High SNR, only large events |

### Python Event Detection

```python
from scipy.signal import find_peaks

def detect_events(dff_trace, frame_rate, sd_threshold=3.0,
                  min_duration_s=0.1, min_interval_s=0.5):
    """Detect calcium transients via threshold crossing."""
    min_duration = int(min_duration_s * frame_rate)
    min_interval = int(min_interval_s * frame_rate)

    baseline_vals = dff_trace[dff_trace < np.percentile(dff_trace, 50)]
    if len(baseline_vals) < 10: baseline_vals = dff_trace
    noise_sd = np.std(baseline_vals)
    noise_mean = np.mean(baseline_vals)
    threshold = noise_mean + sd_threshold * noise_sd

    peaks, _ = find_peaks(dff_trace, height=threshold, distance=min_interval,
                          prominence=sd_threshold * noise_sd * 0.5)

    events = []
    for peak_idx in peaks:
        # Find onset (backward search for threshold crossing)
        onset = peak_idx
        for j in range(peak_idx - 1, -1, -1):
            if dff_trace[j] < threshold:
                onset = j + 1; break

        # Find offset (forward search)
        offset = peak_idx
        for j in range(peak_idx + 1, len(dff_trace)):
            if dff_trace[j] < threshold:
                offset = j; break
        else:
            offset = len(dff_trace) - 1

        if offset - onset < min_duration: continue

        amplitude = dff_trace[peak_idx] - noise_mean
        events.append({
            'onset_frame': onset, 'peak_frame': peak_idx,
            'offset_frame': offset,
            'onset_time_s': onset / frame_rate,
            'amplitude': amplitude,
            'rise_time_s': (peak_idx - onset) / frame_rate,
            'decay_time_s': (offset - peak_idx) / frame_rate,
            'duration_s': (offset - onset) / frame_rate,
            'auc': np.trapz(dff_trace[onset:offset+1] - noise_mean) / frame_rate
        })
    return events
```

### Spike Deconvolution

For inferring spike trains from calcium traces, consider CaImAn's OASIS algorithm or Suite2p's built-in deconvolution. Key parameter: `tau` (indicator decay time constant).

---

## 9. Frequency & Spectral Analysis

### Event Frequency

```python
def event_frequency(events, total_duration_s):
    n = len(events)
    return n / total_duration_s, n / total_duration_s * 60  # Hz, per min
```

### Inter-Event Interval Interpretation

| IEI Pattern | Indicates |
|---|---|
| Regular (low CV) | Pacemaker-like activity |
| Random (CV ~1, exponential) | Poisson-like firing |
| Bimodal (short + long) | Burst firing |
| CV > 1 | Clustered/bursty activity |

### FFT Power Spectrum

```python
def compute_power_spectrum(dff, frame_rate):
    """Returns (freqs_hz, power_spectral_density)."""
    n = len(dff)
    dff_centered = dff - np.mean(dff)
    window = np.hanning(n)
    fft_vals = np.fft.rfft(dff_centered * window)
    psd = (2.0 / n) * np.abs(fft_vals) ** 2
    freqs = np.fft.rfftfreq(n, d=1.0/frame_rate)
    return freqs, psd
```

For non-stationary oscillations (frequency changes over time), consider continuous wavelet transform (scipy.signal.cwt with morlet2 wavelet).

---

## 10. Population & Network Analysis

### Correlation Matrix

```python
corr_matrix = np.corrcoef(dff_all)  # dff_all shape: (n_cells, n_frames)
```

### Synchrony Index

```python
def population_synchrony(dff_all):
    """0 = independent, 1 = perfectly synchronous."""
    n_cells = dff_all.shape[0]
    var_pop = np.var(np.mean(dff_all, axis=0))
    var_ind = np.mean(np.var(dff_all, axis=1))
    sync = var_pop / (var_ind + 1e-10)
    sync_norm = (sync - 1.0/n_cells) / (1.0 - 1.0/n_cells)
    return np.clip(sync_norm, 0, 1)
```

### Cross-Correlation with Lag

```python
def peak_lag(trace1, trace2, frame_rate, max_lag_s=2.0):
    """Find lag at which cross-correlation peaks. Positive = trace2 leads."""
    max_lag = int(max_lag_s * frame_rate)
    t1 = (trace1 - np.mean(trace1)) / (np.std(trace1) + 1e-10)
    t2 = (trace2 - np.mean(trace2)) / (np.std(trace2) + 1e-10)
    xcorr = np.correlate(t1, t2, mode='full') / len(t1)
    center = len(t1) - 1
    lags = np.arange(-max_lag, max_lag + 1)
    segment = xcorr[center + lags[0]:center + lags[-1] + 1]
    peak_idx = np.argmax(segment)
    return lags[peak_idx] / frame_rate, segment[peak_idx]
```

### Cell Clustering

```python
from scipy.cluster.hierarchy import linkage, fcluster
from scipy.spatial.distance import pdist

distances = pdist(dff_all, metric='correlation')  # 1 - r
Z = linkage(distances, method='ward')
labels = fcluster(Z, n_clusters, criterion='maxclust')
```

---

## 11. ImageJ Plugins

| Plugin | ROI Type | dF/F | Events | Batch | 3D | Best For |
|---|---|---|---|---|---|---|
| Multi Measure (built-in) | Manual | No | No | No | No | Quick trace extraction |
| Time Series Analyzer | Manual | No | No | No | No | Beginners |
| TACI | TrackMate | Yes | No | Yes | Yes | 3D calcium imaging |
| SynActJ | Auto (watershed) | Yes | Yes (R) | Yes | No | Synaptic activity |
| CaManager | Manual (line) | Yes | No | No | No | Dendritic kymographs |
| dFoF-imagej | Manual | Yes | No | No | No | Quick dF/F stack |
| FAST | Skeleton | Yes | Yes | No | No | Dendritic/axonal |
| MCA | Auto | Yes | Yes | Yes | No | Population analysis |

**TACI**: 3D calcium imaging (TrackMate for detection, dF/F, z-drift tracking). Install from GitHub.
**SynActJ**: Synaptic bouton analysis. Enable "Cellular Imaging" update site.

---

## 12. Agent Workflows

### 12.1 Single-Wavelength (GCaMP/Fluo-4) Complete Pipeline

```bash
# Step 1: Open
python ij.py macro 'run("Bio-Formats Importer", "open=/path/to/recording.tif color_mode=Default view=Hyperstack stack_order=XYCZT");'
python ij.py info
python ij.py capture step1_opened

# Step 2: Check metadata for frame rate and calibration
python ij.py metadata

# Step 3: Motion correct
python ij.py macro 'run("32-bit"); run("StackReg", "transformation=[Rigid Body]"); rename("registered");'
python ij.py macro 'selectWindow("registered"); run("Z Project...", "projection=[Standard Deviation]"); rename("temporal_SD");'
python ij.py capture step3_temporal_SD

# Step 4: Detect cells
python ij.py macro '
  selectWindow("registered");
  run("Z Project...", "projection=[Average Intensity]"); rename("mean_projection");
  run("Duplicate...", "title=for_detection");
  run("Gaussian Blur...", "sigma=3");
  setAutoThreshold("Triangle dark"); run("Convert to Mask"); run("Watershed");
  run("Analyze Particles...", "size=30-5000 circularity=0.3-1.0 add");
  close("for_detection");
  print("Detected " + roiManager("count") + " cells");
'

# Step 5: Add background ROI
python ij.py macro '
  selectWindow("mean_projection");
  makeRectangle(5, 5, 20, 20);
  roiManager("Add");
  roiManager("Select", roiManager("count") - 1);
  roiManager("Rename", "Background");
  roiManager("Show All");
'
python ij.py capture step5_rois

# Step 6: Extract traces
python ij.py macro '
  run("Set Measurements...", "mean redirect=None decimal=6");
  selectWindow("registered");
  roiManager("Deselect"); roiManager("Multi Measure");
'
python ij.py results

# Step 7: Save ROIs
python ij.py macro 'roiManager("Deselect"); roiManager("Save", "/path/to/analysis/calcium_ROIs.zip");'
```

### 12.2 Fura-2 Ratiometric Pipeline

```bash
# Open -> Split channels -> StackReg on F380 (more stable) -> Background subtract -> Ratio
python ij.py macro '
  run("Bio-Formats Importer", "open=/path/to/fura2.tif color_mode=Default view=Hyperstack stack_order=XYCZT");
  title = getTitle();
  getDimensions(w, h, channels, slices, frames);
  if (channels == 2) {
    run("Split Channels");
    selectWindow("C1-" + title); rename("F340");
    selectWindow("C2-" + title); rename("F380");
  } else {
    run("Deinterleave", "how=2");
    selectWindow(title + " #1"); rename("F340");
    selectWindow(title + " #2"); rename("F380");
  }
  // Motion correct on 380 channel
  selectWindow("F380"); run("32-bit"); run("StackReg", "transformation=[Rigid Body]");
  selectWindow("F340"); run("32-bit"); run("StackReg", "transformation=[Rigid Body]");
'

# Measure background, subtract, compute ratio
python ij.py macro '
  selectWindow("F380"); setSlice(1);
  makeRectangle(5, 5, 20, 20); getRawStatistics(n, bg380);
  selectWindow("F340"); setSlice(1);
  makeRectangle(5, 5, 20, 20); getRawStatistics(n, bg340);
  selectWindow("F340"); run("Subtract...", "value=" + bg340 + " stack");
  selectWindow("F380"); run("Subtract...", "value=" + bg380 + " stack");
  imageCalculator("Divide create 32-bit stack", "F340", "F380");
  rename("Ratio_340_380"); setMinAndMax(0.3, 3.0); run("Fire");
'
python ij.py capture ratio_image

# Draw ROIs on F380 mean projection, then Multi Measure on ratio stack
```

### 12.3 Batch Processing

```bash
python ij.py macro '
  input_dir = "/path/to/recordings/";
  output_dir = "/path/to/analysis/";
  list = getFileList(input_dir);
  for (f = 0; f < list.length; f++) {
    if (!endsWith(list[f], ".tif")) continue;
    open(input_dir + list[f]);
    title = getTitle();
    base = replace(title, ".tif", "");
    run("32-bit"); run("StackReg", "transformation=[Rigid Body]");
    run("Z Project...", "projection=[Average Intensity]");
    run("Duplicate...", "title=temp");
    run("Gaussian Blur...", "sigma=3");
    setAutoThreshold("Triangle dark"); run("Convert to Mask");
    run("Watershed");
    run("Analyze Particles...", "size=30-5000 circularity=0.3-1.0 add");
    close("temp");
    run("Set Measurements...", "mean redirect=None decimal=6");
    selectWindow(title); roiManager("Deselect"); roiManager("Multi Measure");
    selectWindow("Results"); saveAs("Results", output_dir + base + "_traces.csv");
    roiManager("Deselect"); roiManager("Save", output_dir + base + "_ROIs.zip");
    roiManager("Reset"); run("Clear Results"); run("Close All");
  }
  print("Batch complete");
'
```

---

## 13. Common Problems & Solutions

| Problem | Symptom | Solution |
|---|---|---|
| Motion artifacts | Intensity fluctuations at cell edges | StackReg Rigid Body; if fails, try moco with larger search |
| Photobleaching | Downward drift in raw traces | Bleach Correction (Exponential Fit) or sliding percentile baseline |
| Low SNR | Can't distinguish events from noise | Temporal bin (`run("Grouped Z Project...", "projection=[Average Intensity] group=4");`), spatial smooth (sigma=1), larger ROIs |
| Indicator saturation | Flat-top traces, max pixel values | Check histogram; reduce exposure/gain or use lower-affinity indicator |
| Focus drift | Gradual blurring, decreasing SD | Hardware autofocus; discard bad frames |
| Overlapping cells | Mixed signals, double peaks | Smaller ROIs, StarDist/Cellpose, or source separation (CaImAn NMF) |
| Neuropil contamination | All cells correlate artificially | Neuropil subtraction: F_corr = F_cell - r*F_neuropil (r typically 0.7) |
| Stimulation artifact | Flash in all ROIs + background | Subtract background trace; interpolate over stim frames |
| Too many false events | Events in background ROI | Increase SD threshold to 4-5 |
| Missing real events | Known responses not detected | Decrease SD threshold to 2; check dF/F baseline |
| StackReg fails | Error or garbled output | Try Translation only, or use moco |

### Neuropil Correction

```python
def neuropil_subtraction(f_cell, f_neuropil, r=0.7):
    """r=0.7 (Suite2p default). Range: 0.5 sparse, 0.8-0.9 dense labeling."""
    return np.maximum(f_cell - r * f_neuropil, 0)
```

---

## 14. Exporting Data

### From ImageJ

```javascript
// Save traces CSV
selectWindow("Results"); saveAs("Results", "/path/to/traces.csv");

// Save registered stack for Python pipelines
selectWindow("registered"); saveAs("Tiff", "/path/to/registered.tif");
```

### Load in Python

```python
import pandas as pd
import numpy as np

# From ImageJ Multi Measure CSV
df = pd.read_csv("traces.csv")
mean_cols = [c for c in df.columns if c.startswith('Mean')]
traces = df[mean_cols].values.T  # shape: (n_rois, n_frames)

# From TIFF stack
import tifffile
data = tifffile.imread("registered.tif")  # (n_frames, height, width)
```

### For CaImAn / Suite2p

Both expect TIFF stacks. Export registered stack from ImageJ as TIFF:
- **CaImAn**: `cm.load("registered.tif")` or use motion correction + CNMF
- **Suite2p**: Place TIFF in directory, run Suite2p. Output in `suite2p/plane0/`: F.npy, Fneu.npy, spks.npy, iscell.npy

---

## 15. Decision Trees

### Analysis Pipeline Selection

```
Single-wavelength (GCaMP, Fluo-4)?
  In vitro? -> ImageJ: Open -> StackReg -> ROI -> Multi Measure -> Python dF/F -> Events
  In vivo two-photon? -> Suite2p/CaImAn (better motion + source separation)
  Long-term (hours)? -> Add Bleach Correction before ROI step

Ratiometric (Fura-2)?
  -> Split channels -> StackReg -> Background subtract -> Ratio -> ROI -> Traces

3D volumetric? -> TACI plugin
Synaptic boutons? -> SynActJ
```

### Baseline Method

```
Defined quiet baseline period? -> Fixed window (mean of first N frames)
Spontaneous activity?
  Significant bleaching? -> Exponential fit, or bleach correct then sliding percentile
  Cell active <50%? -> Median
  Cell active 50-80%? -> Sliding 8th percentile (20-60s window)
  Cell active >80%? -> Sliding 5th percentile (60-120s window)
```

### Event Detection Method

```
High SNR? -> Threshold: 3 SD above baseline
Moderate SNR, stereotyped waveform? -> Template matching (cross-correlation)
Moderate SNR, variable waveform? -> Threshold 2.5 SD + min duration filter
Low SNR, can bin? -> Bin 2-4 frames, then 3 SD threshold
Low SNR, need full resolution? -> Wavelet-based or ML (DeepCINAC, CalTrig)
Need spike inference? -> Deconvolution (OASIS/CaImAn, MLspike, CASCADE)
```

### Motion Correction

```
No visible motion? -> Skip (but check temporal SD to confirm)
Small translation (<5 px)? -> StackReg Translation
Translation + rotation? -> StackReg Rigid Body
Large motion (>10 px), rigid? -> moco
Non-rigid deformation? -> CaImAn/Suite2p (export TIFF from ImageJ)
3D drift? -> Correct 3D Drift plugin
```

---

## 16. Parameter Quick-Reference

### dF/F Parameters

| Parameter | Starting Point | How to Choose |
|---|---|---|
| Baseline frames (fixed) | 10-50 | Must cover a quiet period before stimulation |
| Sliding window | 20-60 s | Longer than several inter-event intervals |
| Percentile | 8th | Lower (5th) for very active cells; higher (20th) for moderate |
| Background ROI | 20x20 px | Place in cell-free area; verify no signal |

### Event Detection

| Parameter | Starting Point | How to Choose |
|---|---|---|
| SD threshold | 3 | Decrease if missing events; increase if false positives in background ROI |
| Min duration | 0.1-0.5 s | Match to indicator kinetics (see table below) |
| Min interval | 0.3-1.0 s | Match to indicator decay time |
| Min amplitude | 0.05-0.5 dF/F | Depends on indicator and expected signal |

### Indicator-Specific Detection Parameters

| Indicator | Min Duration (s) | Min IEI (s) | Expected Decay Tau (s) |
|---|---|---|---|
| jGCaMP8f | 0.05 | 0.1 | 0.06 |
| GCaMP6f | 0.1 | 0.3 | 0.14 |
| jGCaMP8s | 0.1 | 0.3 | 0.18 |
| GCaMP6s | 0.3 | 1.0 | 0.55 |
| Fluo-4 | 0.1 | 0.3 | ~0.3 |
| Cal-520 | 0.1 | 0.3 | ~0.2 |
| Fura-2 | 0.2 | 0.5 | ~0.3 |

### Motion Correction

| Parameter | StackReg | moco | CaImAn |
|---|---|---|---|
| Max shift (pixels) | Auto | 10-30 | 6-20 |
| Transformation | Rigid Body | Translation | Piecewise rigid |
| Downsampling | None | 2-4 | Auto |
| Speed (512x512, 1000 fr) | ~30 s | ~10 s | ~60 s |

### Neuropil Correction

| Parameter | Starting Point | Notes |
|---|---|---|
| Inner radius | Cell radius + 2 px | Exclude cell body |
| Outer radius | Cell radius + 15-20 px | Capture local neuropil |
| r coefficient | 0.7 | 0.5-0.6 sparse, 0.8-0.9 dense labeling |

### Acquisition by Application

| Application | Frame Rate | Indicator | Duration |
|---|---|---|---|
| Neuronal population (widefield) | 10-30 Hz | GCaMP6m/f | 5-30 min |
| Neuronal population (2P) | 15-30 Hz | GCaMP6/7/8 | 10-60 min |
| Dendritic spines (2P) | 30-100 Hz | jGCaMP8f | 5-20 min |
| Astrocyte waves | 1-5 Hz | GCaMP6s, Rhod-2 | 10-60 min |
| Cardiac myocyte | 50-200 Hz | Fluo-4, Cal-520 | 1-10 min |
| Ratiometric (Fura-2) | 0.5-5 Hz | Fura-2 | 5-30 min |
| Circadian rhythm | 1/min to 1/5min | GCaMP6s | 24-120 h |

---

## Appendix: Complete Python Pipeline Script

Self-contained script for processing ImageJ Multi Measure CSV output:

```bash
python calcium_analysis.py traces.csv --frame-rate 10 --output results/
```

```python
#!/usr/bin/env python3
"""calcium_analysis.py -- Calcium imaging analysis pipeline.
Reads Multi Measure CSV, produces dF/F traces, events, summary, plots."""

import argparse, json, os, warnings
import numpy as np
import pandas as pd
from scipy.ndimage import percentile_filter
from scipy.signal import find_peaks
warnings.filterwarnings('ignore')

def compute_dff(traces, fr, method='sliding_percentile',
                bl_frames=30, win_s=30.0, pct=8):
    if method == 'fixed_window':
        f0 = np.mean(traces[:, :bl_frames], axis=1, keepdims=True)
    elif method == 'sliding_percentile':
        win = int(win_s * fr); win += 1 - win % 2
        f0 = np.array([percentile_filter(t, pct, size=win) for t in traces])
    elif method == 'median':
        f0 = np.median(traces, axis=1, keepdims=True)
    return (traces - np.maximum(f0, 1e-6)) / np.maximum(f0, 1e-6)

def detect_events(dff, fr, sd_thr=3.0, min_dur=0.1, min_iei=0.5):
    bl = dff[dff < np.percentile(dff, 50)]
    if len(bl) < 10: bl = dff
    sd, mu = np.std(bl), np.mean(bl)
    thr = mu + sd_thr * sd
    peaks, _ = find_peaks(dff, height=thr, distance=int(min_iei*fr),
                          prominence=sd_thr*sd*0.5)
    events = []
    for p in peaks:
        on = p
        for j in range(p-1, -1, -1):
            if dff[j] < thr: on = j+1; break
        off = p
        for j in range(p+1, len(dff)):
            if dff[j] < thr: off = j; break
        else: off = len(dff)-1
        if off - on < int(min_dur*fr): continue
        events.append({
            'onset_frame': int(on), 'peak_frame': int(p), 'offset_frame': int(off),
            'onset_time_s': round(on/fr, 3), 'peak_time_s': round(p/fr, 3),
            'amplitude': round(float(dff[p]-mu), 4),
            'rise_time_s': round((p-on)/fr, 3),
            'decay_time_s': round((off-p)/fr, 3),
            'duration_s': round((off-on)/fr, 3),
            'auc': round(float(np.trapz(dff[on:off+1]-mu)/fr), 4)
        })
    return events

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('input'); ap.add_argument('--frame-rate', type=float, required=True)
    ap.add_argument('--output', default='calcium_results')
    ap.add_argument('--baseline-method', default='sliding_percentile',
                    choices=['fixed_window', 'sliding_percentile', 'median'])
    ap.add_argument('--sd-threshold', type=float, default=3.0)
    ap.add_argument('--window-s', type=float, default=30.0)
    ap.add_argument('--percentile', type=float, default=8.0)
    ap.add_argument('--has-background', action='store_true')
    args = ap.parse_args()
    os.makedirs(args.output, exist_ok=True)

    df = pd.read_csv(args.input)
    mcols = [c for c in df.columns if c.startswith('Mean')]
    if not mcols: mcols = [c for c in df.columns if df[c].dtype in ['float64','int64']]
    traces = df[mcols].values.T
    if args.has_background:
        traces = np.maximum(traces[:-1] - traces[-1:], 0)
    names = [f'Cell_{i+1}' for i in range(traces.shape[0])]
    nc, nf = traces.shape; dur = nf / args.frame_rate
    print(f"{nc} cells, {nf} frames, {dur:.1f}s at {args.frame_rate} Hz")

    dff = compute_dff(traces, args.frame_rate, args.baseline_method,
                      window_s=args.window_s, pct=args.percentile)
    ev_list = [detect_events(dff[i], args.frame_rate, args.sd_threshold) for i in range(nc)]
    tot = sum(len(e) for e in ev_list)
    print(f"Detected {tot} events across {nc} cells")

    # Save outputs
    pd.DataFrame(dff.T, columns=names).assign(
        time_s=np.arange(nf)/args.frame_rate).to_csv(f'{args.output}/dff_traces.csv', index=False)
    rows = [dict(ev, cell=names[i], cell_index=i) for i, evs in enumerate(ev_list) for ev in evs]
    pd.DataFrame(rows).to_csv(f'{args.output}/events.csv', index=False)
    summary = [{'cell': names[i], 'n_events': len(evs),
                'freq_per_min': len(evs)/dur*60,
                'mean_amplitude': np.mean([e['amplitude'] for e in evs]) if evs else 0,
                'mean_duration_s': np.mean([e['duration_s'] for e in evs]) if evs else 0
               } for i, evs in enumerate(ev_list)]
    pd.DataFrame(summary).to_csv(f'{args.output}/summary.csv', index=False)

    corr = np.corrcoef(dff)
    sync = np.var(np.mean(dff,0)) / (np.mean(np.var(dff,1))+1e-10)
    sync_n = np.clip((sync - 1/nc)/(1 - 1/nc), 0, 1)
    json.dump({'n_cells': nc, 'n_frames': nf, 'frame_rate_hz': args.frame_rate,
               'total_events': tot, 'mean_corr': round(float(np.mean(
                   corr[np.triu_indices_from(corr,k=1)])),4),
               'synchrony_index': round(float(sync_n),4),
               'parameters': vars(args)},
              open(f'{args.output}/metadata.json','w'), indent=2)
    print(f"Results saved to {args.output}/")

if __name__ == '__main__': main()
```

---

## Appendix: ImageJ Macro Library

```javascript
// MACRO: Compute dF/F Stack (Fixed Window Baseline) [F5]
macro "Compute dF/F Stack [F5]" {
    baselineFrames = getNumber("Baseline frames:", 30);
    title = getTitle();
    run("Z Project...", "start=1 stop=" + baselineFrames + " projection=[Average Intensity]");
    rename("F0_temp"); run("32-bit");
    selectWindow(title); run("Duplicate...", "title=dFF duplicate"); run("32-bit");
    imageCalculator("Subtract stack", "dFF", "F0_temp");
    imageCalculator("Divide stack", "dFF", "F0_temp");
    close("F0_temp");
    selectWindow("dFF"); setMinAndMax(-0.1, 2.0); run("Fire");
}

// MACRO: Auto-Detect Cells and Extract Traces [F6]
macro "Auto Cell Detection [F6]" {
    minArea = getNumber("Min cell area (px):", 50);
    maxArea = getNumber("Max cell area (px):", 2000);
    sigma = getNumber("Gaussian sigma:", 3);
    title = getTitle();
    run("Z Project...", "projection=[Average Intensity]"); rename("mean_det");
    run("Duplicate...", "title=temp");
    run("Gaussian Blur...", "sigma=" + sigma);
    setAutoThreshold("Triangle dark"); run("Convert to Mask"); run("Watershed");
    run("Analyze Particles...", "size=" + minArea + "-" + maxArea + " circularity=0.3-1.0 add");
    close("temp");
    selectWindow(title);
    run("Set Measurements...", "mean redirect=None decimal=6");
    roiManager("Deselect"); roiManager("Multi Measure");
    print("Detected " + roiManager("count") + " cells. Traces in Results.");
}

// MACRO: Quick Bleach Check [F8]
macro "Bleach Check [F8]" {
    n = nSlices; means = newArray(n);
    for (i = 1; i <= n; i++) { setSlice(i); getRawStatistics(nPx, mean); means[i-1] = mean; }
    bleach_pct = (1 - means[n-1] / means[0]) * 100;
    xvals = Array.getSequence(n);
    Plot.create("Bleach Check", "Frame", "Mean Intensity");
    Plot.add("line", xvals, means); Plot.show();
    print("Bleaching: " + d2s(bleach_pct, 1) + "%");
    if (bleach_pct > 5) print("WARNING: >5% bleaching. Consider correction.");
}

// MACRO: Activity Map (CV = SD/Mean) [F9]
macro "Activity Map [F9]" {
    title = getTitle();
    run("Z Project...", "projection=[Average Intensity]"); rename("MEAN_" + title);
    selectWindow(title);
    run("Z Project...", "projection=[Standard Deviation]"); rename("SD_" + title);
    imageCalculator("Divide create 32-bit", "SD_" + title, "MEAN_" + title);
    rename("Activity_CV"); run("Fire");
}
```

---

## Troubleshooting Quick Reference

| Problem | Symptom | Solution |
|---|---|---|
| No signal | Dark image | Check excitation, indicator loading, focus |
| All cells saturated | Flat-top traces, max pixel values | Reduce excitation/exposure/gain |
| Motion blur | Edge artifacts | StackReg before analysis |
| Bleaching | Downward drift | Bleach Correction or sliding baseline |
| Neuropil contamination | All cells correlate | Neuropil subtraction (r=0.7) |
| Too many false events | Events in background ROI | Increase SD threshold (4-5) |
| Missing events | Known responses not detected | Decrease SD threshold (2) |
| StackReg fails | Error or garbled | Try Translation only or moco |
| Multi Measure slow | Hangs with many ROIs | Reduce ROI count or measure in batches |
