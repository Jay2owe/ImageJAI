# Live Cell Imaging & Time-Lapse Analysis Reference

Time-lapse preprocessing, dynamic measurements (FRAP, kymographs, ratiometric),
event detection (division, death, confluency), tracking, video export.

Covers drift correction (StackReg, HyperStackReg, Correct 3D Drift, SIFT,
Fast4DReg, Template Matching, Manual), bleach correction (Histogram Matching,
Simple Ratio, Exponential Fit), FRAP normalization and recovery-curve fitting,
kymograph generation (Reslice, KymoResliceWide, Multi Kymograph) and velocity
extraction, TrackMate detectors/trackers, division/death/confluency detection,
ratiometric and FRET imaging, phototoxicity, multi-position Bio-Formats
navigation, and annotated video export.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript.
`python probe_plugin.py "Plugin..."` — discover installed plugin parameters.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Which drift correction method should I pick?" | §3.1 |
| "How do I do bleach correction?" | §3.2 |
| "How do I subtract background from a time-lapse?" | §3.3 |
| "Full preprocessing pipeline in one macro?" | §3.4 |
| "How do I compute FRAP mobile fraction / t1/2 / D?" | §4 |
| "Double-normalization formula for FRAP traces?" | §4 |
| "How do I generate a kymograph and measure velocity?" | §5 |
| "TrackMate detectors / trackers — which to use?" | §6 |
| "How do I run TrackMate from a Groovy script?" | §6 |
| "How do I detect cell division by morphology?" | §7 |
| "FUCCI phase colour table?" | §7 |
| "How do I detect cell death (PI/SYTOX)?" | §8 |
| "How do I measure confluency over time?" | §9 |
| "How do I extract a single-ROI intensity trace?" | §10 |
| "How do I compute dF/F0 per pixel?" | §10 |
| "How do I generate a ratio image / FRET E_FRET?" | §11 |
| "Signs of phototoxicity and how to minimise?" | §12 |
| "Nyquist temporal sampling table?" | §12 |
| "How do I iterate Bio-Formats series (multi-position)?" | §13 |
| "How do I export an annotated MP4 / AVI?" | §14 |
| "What's the exact macro command for StackReg / HyperStackReg?" | §15 |
| "How do I convert a stack to a hyperstack / swap dims?" | §16 |
| "Why did my macro fail / my data get destroyed?" | §17 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '`<term>`' live-cell-timelapse-reference.md` to jump.

### A
`Analyze Particles` §7, §8, §9 · `Anaphase` §7 · `Annotations (Time Stamper, Scale Bar)` §14, §15 · `Apoptosis` §8 · `AVI export` §14, §15

### B
`Background subtraction` §3.3, §3.4, §11 · `Batch all positions` §13 · `BIC (model comparison)` §4 · `Bio-Formats Importer` §13 · `Bio-Formats Macro Extensions` §13 · `Bleach Correction` §3.2, §3.4, §15, §17 · `Bleaching severity diagnosis` §17 · `Blebbing` §8, §12

### C
`Calcium ratiometric (Fura-2)` §11 · `Cellpose (TrackMate detector)` §6 · `Circularity (division indicator)` §7 · `Circularity (phototoxicity indicator)` §12 · `Condensation` §12, §17 · `Confluency` §9 · `Confinement ratio` §6 · `Correct 3D Drift` §3.1, §15 · `Cytokinesis` §7

### D
`dF/F0 (relative fluorescence)` §10 · `Decision tree` §2 · `Descriptor-based registration` §3.1 · `Detector (TrackMate)` §6 · `Diffusion coefficient (D)` §4 · `Displacement (track)` §6 · `Division detection` §2, §7 · `DoG (TrackMate detector)` §6 · `Doubling time` §9 · `Drift correction` §3.1, §15 · `Duplicate (before StackReg)` §3.1, §17

### E
`E_FRET` §11 · `Exponential Fit (bleach)` §3.2, §15 · `Exporting TrackMate results` §6

### F
`F_double / F_fullscale / F_norm` §4 · `Fast4DReg` §3.1, §15 · `FFmpeg (post-processing)` §14 · `FFT frequency analysis` §10 · `Find best focus per timepoint` §17 · `Flatten overlay` §14 · `Focus drift` §17 · `FRAP` §2, §4, §15 · `FRAP Tools plugin` §4, §15 · `Frame interval (Stack.setFrameInterval)` §2 · `FRET` §11, §15 · `FUCCI reporters` §7

### G
`Gap closing (tracker)` §6

### H
`Half-time (t1/2)` §4 · `Histogram Matching (bleach)` §3.2, §3.4, §15, §17 · `HyperStackReg` §3.1, §15 · `Hyperstack (Stack to Hyperstack)` §2, §16, §17

### I
`Image Sequence export` §15 · `Initial checks` §2 · `Intensity fluctuations` §17

### K
`Kalman (tracker)` §6 · `Kymograph` §2, §5, §15 · `KymoResliceWide` §5, §15

### L
`LAP (Jaqaman) tracker` §6 · `LINKING_MAX_DISTANCE` §6 · `LoG (TrackMate detector)` §6 · `LUT (Fire / mpl-inferno)` §10, §11

### M
`Manual Drift Correction` §3.1, §15 · `Manual Tracking` §15 · `Mask (TrackMate detector)` §6 · `MAX_FRAME_GAP` §6 · `Metaphase` §7 · `Mobile fraction (Mf)` §4 · `MP4 (FFmpeg)` §14, §15 · `MRI Wound Healing Tool` §9 · `MSD (mean squared displacement)` §6 · `MTrackJ` §15 · `Multi Kymograph` §5, §15 · `Multi Measure (ROI Manager)` §16 · `Multi-position` §13 · `Multi-ROI traces` §10

### N
`Nearest Neighbor (tracker)` §6 · `Necrosis` §8 · `Normalization formulas (FRAP)` §4 · `Nyquist temporal sampling` §12

### O
`Optimized (Phase-Corr) drift` §3.1 · `Otsu threshold (confluency / death)` §8, §9 · `Overlap (tracker)` §6

### P
`Peak detection` §10 · `Per-frame ROI background` §3.3 · `Phototoxicity` §12 · `PI / SYTOX` §8 · `Plot Z-axis Profile` §10 · `Plugin Quick Reference` §15 · `Preprocessing pipeline (complete)` §3.4 · `Probing plugins` §3.1 · `Prometaphase` §7 · `Python curve fitting (scipy)` §4 · `Python trace analysis utilities` §16

### Q
`Quick Start` §2

### R
`Ratio image generation` §11 · `Ratio Profiler` §15 · `Ratiometric imaging` §11 · `Recovery curve models (FRAP)` §4 · `Reference region correction` §3.2 · `Re-order Hyperstack` §16 · `Reslice` §5, §15 · `RGB Color (side-by-side)` §14 · `RiFRET` §11, §15 · `Rolling ball` §3.3 · `Rounding (mitotic)` §7, §12

### S
`Scale Bar` §14, §15 · `scipy curve_fit` §4 · `Side-by-side comparison` §14 · `SIFT Alignment` §3.1, §15 · `Simple LAP (tracker)` §6 · `Simple Ratio (bleach)` §3.2, §15, §17 · `Sliding paraboloid` §3.3 · `Soumpasis (D formula)` §4 · `SPLITTING_MAX_DISTANCE` §7 · `Stack to Hyperstack` §2, §16 · `Stack.setFrameInterval` §2 · `StackReg` §3.1, §3.4, §15, §17 · `StarDist (TrackMate detector)` §6 · `Static background image` §3.3 · `Subtract Background` §3.3, §3.4, §11

### T
`Template Matching` §3.1 · `Temporal-Color Code` §14 · `Temporal intensity analysis` §10 · `Temporal median subtraction` §3.3 · `Temporal statistics maps (Z Project)` §16 · `Threshold (TrackMate detector)` §6 · `Time Series Analyzer` §15 · `Time Stamper` §14, §15 · `Track length` §6 · `Track metrics` §6 · `Tracker (TrackMate)` §6 · `TrackMate` §2, §6, §15 · `TrackMate + division detection` §7 · `Trace extraction (FRAP)` §4 · `Translate (manual drift)` §3.1

### V
`Velocity (kymograph)` §5 · `Video export` §14, §15

### W
`Whole-image dF/F0 map` §10 · `Wound healing` §9, §15 · `Wound_healing_size_tool` §9

### Z
`Z Project` §3.3, §10, §16 · `Z Project (Max / Min / StdDev)` §16

---

## §2 Quick Start

### Minimal pipeline
```
Open → Drift correct → Bleach correct → Background subtract → Measure → Export
```

### Initial checks
```bash
python ij.py info                    # check dimensions
python ij.py macro '
  getDimensions(w, h, c, z, t);
  print("C=" + c + ", Z=" + z + ", T=" + t);
'
# Convert stack to hyperstack if needed
python ij.py macro 'run("Stack to Hyperstack...", "order=xyczt(default) channels=1 slices=1 frames=100 display=Color");'

# Set frame interval (critical for temporal measurements)
python ij.py macro 'Stack.setFrameInterval(5);'
```

### Decision tree
```
FRAP experiment? → Section 3
Track moving objects? → Section 5 (TrackMate)
Kymograph (space vs time)? → Section 4
Intensity over time? → Ratiometric/FRET → Section 10, else Section 9
Cell growth/coverage? → Section 8
Division detection? → Section 6
Death detection? → Section 7
```

---

## §3 Time-Lapse Preprocessing

Apply in order: (1) drift correction, (2) bleach correction, (3) background subtraction.

### §3.1 Drift Correction

| Method | Dimensions | Transform | Best For |
|--------|-----------|-----------|----------|
| **Optimized (Phase-Corr)**| 2D+T | Translation/Rigid | **Optimized for SCN/culture (3-path logic)** |
| StackReg | 2D+T | Translation/Rigid/Affine | Standard 2D time-lapse |
| HyperStackReg | 2D+T multi-ch | Same as StackReg | Multi-channel (computes from one channel) |
| Correct 3D Drift | 3D+T | Translation only | Z-stacks over time |
| Fast4DReg | 3D+T | Translation (XYZ) | Large 3D+T (>2 GB), works on projections |
| Descriptor-based | 2D/3D | Rigid/Affine/Similarity | Feature-rich images (beads, nuclei) |
| SIFT Alignment | 2D+T | Translation/Rigid/Affine | Large drift, rich texture |
| Template Matching | 2D+T | Translation | Bright fiducial markers |
| Manual Drift Correction | 2D+T | Translation | When auto methods fail |

**Choosing:** SCN/Tissue Slice → **Optimized Phase-Corr** (Section 17.1 in `registration-stitching-reference.md`). 3D+T? Large → Fast4DReg, else Correct 3D Drift. 2D multi-channel → HyperStackReg. 2D single channel → StackReg (Translation for >90% of cases). Large drift with features → SIFT.

**Optimized Workflow:** (1) 4x downsampled phase corr scan, (2) Analyze drift trace, (3) Choose Path A (Sparse), Path B (Continuous), or Path C (Chaotic). Much faster than standard methods (~1s per 300 frames).

```bash
# StackReg — duplicate first (modifies in-place)
python ij.py macro '
  run("Duplicate...", "title=registered duplicate");
  run("StackReg", "transformation=Translation");
'
# Transformation options: Translation, [Rigid Body], [Scaled Rotation], Affine
# Use simplest that corrects the drift. Edge pixels become zero after alignment.

# HyperStackReg — compute from channel 1
python ij.py macro 'run("HyperStackReg", "transformation=Translation channel1");'

# Correct 3D Drift
python ij.py macro 'run("Correct 3D Drift", "channel=1");'
# Creates new corrected hyperstack. Parameters: channel, only, lowest, highest.

# Fast4DReg (macro — check if installed)
grep -i "fast4dreg" .tmp/commands.md
python ij.py macro 'run("Estimate and apply 3D+t drift");'

# SIFT alignment — for large drift
python ij.py macro '
  run("Linear Stack Alignment with SIFT", "initial_gaussian_blur=1.60 steps_per_scale_octave=3 minimum_image_size=64 maximum_image_size=1024 feature_descriptor_size=4 feature_descriptor_orientation_bins=8 closest/next_closest_ratio=0.92 maximal_alignment_error=25 inlier_ratio=0.05 expected_transformation=Translation interpolate");
'

# Manual drift correction with known values
python ij.py macro '
  n = nSlices;
  for (i = 1; i <= n; i++) {
      setSlice(i);
      dx = -2 * (i - 1);  // negative to reverse drift
      run("Translate...", "x=" + dx + " y=0 interpolation=Bilinear slice");
  }
'
```

**Gotchas:**
- StackReg modifies in-place — duplicate first
- StackReg works on single-channel only — use HyperStackReg for multi-channel
- First frame is reference by default
- Probe unfamiliar plugins: `python probe_plugin.py "HyperStackReg"`

### §3.2 Bleach Correction

| Method | When to Use | Gotcha |
|--------|-------------|--------|
| Simple Ratio | Uniform bleaching, no moving objects | Bright objects entering field cause artifacts |
| Exponential Fit | True single-exponential decay | Multi-component bleaching poorly fit |
| Histogram Matching | **Typically best** — moving objects, non-uniform bleaching | — |

Histogram Matching preserves intensity distribution without multiplicative noise amplification.

```bash
python probe_plugin.py "Bleach Correction"

# Histogram Matching (typically preferred)
python ij.py macro 'run("Bleach Correction", "correction=[Histogram Matching]");'

# Simple Ratio
python ij.py macro 'run("Bleach Correction", "correction=[Simple Ratio]");'

# Exponential Fit
python ij.py macro 'run("Bleach Correction", "correction=[Exponential Fit]");'
```

**Manual ratio correction** (when plugin unavailable):
```bash
python ij.py macro '
  nF = nSlices;
  means = newArray(nF);
  for (i = 1; i <= nF; i++) { setSlice(i); getStatistics(area, mean); means[i-1] = mean; }
  ref = means[0];
  for (i = 1; i <= nF; i++) { setSlice(i); run("Multiply...", "value=" + (ref/means[i-1]) + " slice"); }
'
```

**Reference region correction** — if a region has constant fluorescence, use it as reference to derive per-frame correction factors.

### §3.3 Background Subtraction

| Method | When to Use | Key Parameter |
|--------|-------------|---------------|
| Rolling ball | General, per-frame | Radius: larger than biggest foreground object |
| Temporal median | Static debris, autofluorescence | Objects stationary >50% of frames get removed |
| Static background image | Acquired separate background | Subtract with imageCalculator |
| Per-frame ROI | Measurable cell-free region per frame | Define background ROI coordinates |

```bash
# Rolling ball — radius guidance: cells ~50-100px, subcellular ~20-50px, tissue ~100-200px
python ij.py macro 'run("Subtract Background...", "rolling=50 stack");'
# Add "sliding" for sliding paraboloid (better for uneven illumination)

# Temporal median subtraction — removes static background, preserves moving objects
python ij.py macro '
  title = getTitle(); id = getImageID();
  run("Z Project...", "projection=Median");
  medID = getImageID(); rename("temporal_median");
  imageCalculator("Subtract create 32-bit stack", title, "temporal_median");
  rename(title + "_bg_subtracted");
  selectImage(medID); close();
  selectWindow(title + "_bg_subtracted"); setMinAndMax(0, 65535); run("16-bit");
'
```

### §3.4 Complete Preprocessing Pipeline

```bash
python ij.py macro '
  run("Duplicate...", "title=processed duplicate");
  run("StackReg", "transformation=Translation");
  run("Bleach Correction", "correction=[Histogram Matching]");
  run("Subtract Background...", "rolling=50 stack");
  print("Preprocessing complete");
'
```

---

## §4 FRAP (Fluorescence Recovery After Photobleaching)

### Key outputs
- **Mobile fraction (Mf)** — proportion of molecules free to move
- **Half-time (t1/2)** — time to 50% recovery
- **Diffusion coefficient (D)** — D = 0.224 * r^2 / t1/2 (Soumpasis, circular spot)

### Three required ROIs

| ROI | Purpose | Placement |
|-----|---------|-----------|
| Bleach | Photobleached region | Centered on bleach spot |
| Reference | Unbleached same fluorophore | Far from bleach, same or neighbouring cell |
| Background | No fluorescence | Outside all cells |

### Normalization formulas
```
Single:     F_norm(t) = [F_bleach(t) - F_bg(t)] / [F_ref(t) - F_bg(t)]
Double:     F_double(t) = F_norm(t) / mean(F_norm over pre-bleach frames)
Full-scale: F_full(t) = [F_norm(t) - F_norm(post-bleach)] / [F_norm(pre-bleach) - F_norm(post-bleach)]
```

### Trace extraction + double normalization
```bash
python ij.py macro '
  // === ADJUST THESE ===
  bleachX = 200; bleachY = 200; bleachR = 15;
  refX = 350; refY = 200; refR = 15;
  bgX = 10; bgY = 10; bgW = 30; bgH = 30;
  bleachFrame = 10;
  // ====================

  nF = nSlices;
  bleachI = newArray(nF); refI = newArray(nF); bgI = newArray(nF);

  for (i = 1; i <= nF; i++) {
      setSlice(i);
      makeOval(bleachX-bleachR, bleachY-bleachR, 2*bleachR, 2*bleachR);
      getStatistics(area, mean); bleachI[i-1] = mean;
  }
  for (i = 1; i <= nF; i++) {
      setSlice(i);
      makeOval(refX-refR, refY-refR, 2*refR, 2*refR);
      getStatistics(area, mean); refI[i-1] = mean;
  }
  for (i = 1; i <= nF; i++) {
      setSlice(i);
      makeRectangle(bgX, bgY, bgW, bgH);
      getStatistics(area, mean); bgI[i-1] = mean;
  }
  run("Select None");

  // Double normalization
  run("Clear Results");
  preSum = 0; preCount = 0;
  for (i = 0; i < nF; i++) {
      fn = (bleachI[i] - bgI[i]) / (refI[i] - bgI[i] + 0.001);
      if (i < bleachFrame - 1) { preSum += fn; preCount++; }
      setResult("Frame", i, i+1);
      setResult("F_norm", i, fn);
  }
  preAvg = preSum / preCount;
  postVal = getResult("F_norm", bleachFrame);
  for (i = 0; i < nF; i++) {
      fn = getResult("F_norm", i);
      setResult("F_double", i, fn / preAvg);
      setResult("F_fullscale", i, (fn - postVal) / (preAvg - postVal + 0.001));
  }
  updateResults();
'
```

### Recovery curve models

| Model | Formula | Use When |
|-------|---------|----------|
| Single exp | F(t) = Mf * (1 - exp(-t/tau)) | One kinetic population |
| Double exp | F(t) = A_fast*(1-exp(-t/tau_fast)) + A_slow*(1-exp(-t/tau_slow)) | Two populations (diffusion + binding) |

Compare models with BIC: BIC difference >10 = strong evidence for preferred model.

### Python curve fitting

Export Results CSV, then use scipy:
```python
import numpy as np
from scipy.optimize import curve_fit

def single_exp(t, plateau, tau):
    return plateau * (1.0 - np.exp(-t / tau))

# Extract post-bleach data, fit:
popt, pcov = curve_fit(single_exp, t_post, y_post, p0=[0.8, t_post[-1]/4],
                       bounds=([0, 0.001], [2.0, t_post[-1]*10]))
plateau, tau = popt
t_half = tau * np.log(2)
# D = 0.224 * r_um**2 / t_half
```

### FRAP Tools plugin
```bash
grep -i "frap" .tmp/commands.md
python ij.py macro 'run("FRAP Tools");'
```

---

## §5 Kymograph Analysis

A kymograph represents motion along a 1D path over time: distance on one axis, time on the other.

**Interpreting:** Slope = velocity. Vertical = stationary. Diagonal = moving. Steeper = slower (when time is vertical).

### Generation methods

| Method | Syntax | Advantage |
|--------|--------|-----------|
| Reslice | `run("Reslice [/]...", "output=1.000 start=Top avoid");` | Simple, exact pixel values |
| KymoResliceWide | `run("KymoResliceWide ", "intensity=Maximum");` | Wide lines, better SNR |
| Multi Kymograph | `run("Multi Kymograph", "linewidth=3");` | Multiple lines at once |

```bash
# Draw line along transport path, then reslice
python ij.py macro '
  makeLine(50, 200, 450, 200);
  run("Line Width...", "line=3");
  run("Reslice [/]...", "output=1.000 start=Top avoid");
  rename("kymograph");
'
```

### Velocity measurement
```
velocity (um/s) = (dx_pixels * pixel_size_um) / (dy_pixels * frame_interval_s)
```

```bash
python ij.py macro '
  getLine(x1, y1, x2, y2, lineWidth);
  pixel_size = 0.325; frame_interval = 5.0;
  velocity = abs(x2-x1) * pixel_size / (abs(y2-y1) * frame_interval);
  print("Velocity: " + d2s(velocity, 4) + " um/s");
'
```

### Frequency from kymograph
Draw vertical line through periodic trace, get profile, count threshold crossings, divide by total time.

---

## §6 Cell Tracking with TrackMate

### Detector comparison

| Detector | Best For | Key Parameters |
|----------|----------|---------------|
| LoG | Round bright objects (nuclei, beads) | RADIUS, THRESHOLD |
| DoG | Faster LoG approximation | RADIUS, THRESHOLD |
| StarDist | Deep learning nuclei | MODEL_FILEPATH, SCORE_THRESHOLD |
| Cellpose | Deep learning cells | MODEL, CELL_DIAMETER |
| Threshold | Binary masks | THRESHOLD |
| Mask | Pre-segmented labels | — |

### Tracker comparison

| Tracker | Best For | Key Parameters |
|---------|----------|---------------|
| Simple LAP | Non-dividing, non-merging | MAX_LINKING_DISTANCE, MAX_GAP_CLOSING_DISTANCE, MAX_FRAME_GAP |
| LAP (Jaqaman) | Division/merging support | Same + ALLOW_TRACK_SPLITTING/MERGING |
| Nearest Neighbor | Dense, slow-moving | MAX_LINKING_DISTANCE |
| Kalman | Fast, directed motion | MAX_SEARCH_RADIUS, INITIAL_SEARCH_RADIUS |
| Overlap | Large touching objects | MIN_IOU, SCALE_FACTOR |

**Choosing parameters:** RADIUS should match typical object radius in calibrated units. LINKING_MAX_DISTANCE should reflect the maximum displacement between frames — consider typical speed * frame interval. MAX_FRAME_GAP typically 2-3 for brief disappearances.

### TrackMate via Groovy (automated)
```bash
python ij.py script '
import fiji.plugin.trackmate.*
import fiji.plugin.trackmate.detection.LogDetectorFactory
import fiji.plugin.trackmate.tracking.jaqaman.SparseLAPTrackerFactory
import fiji.plugin.trackmate.features.FeatureFilter
import ij.IJ

def imp = IJ.getImage()
def model = new Model()
def settings = new Settings(imp)

settings.detectorFactory = new LogDetectorFactory()
settings.detectorSettings = settings.detectorFactory.getDefaultSettings()
settings.detectorSettings.put("RADIUS", 7.5d)
settings.detectorSettings.put("THRESHOLD", 1.0d)
settings.detectorSettings.put("DO_SUBPIXEL_LOCALIZATION", true)
settings.detectorSettings.put("TARGET_CHANNEL", 1)

settings.trackerFactory = new SparseLAPTrackerFactory()
settings.trackerSettings = settings.trackerFactory.getDefaultSettings()
settings.trackerSettings.put("MAX_FRAME_GAP", 2)
settings.trackerSettings.put("LINKING_MAX_DISTANCE", 15.0d)
settings.trackerSettings.put("GAP_CLOSING_MAX_DISTANCE", 15.0d)

settings.addAllAnalyzers()
def trackmate = new TrackMate(model, settings)

if (!trackmate.execDetection()) { println("Detection failed: " + trackmate.errorMessage); return }
println("Spots: " + model.spots.getNSpots(true))
if (!trackmate.execTracking()) { println("Tracking failed: " + trackmate.errorMessage); return }
println("Tracks: " + model.trackModel.nTracks(true))

// Optional: filter tracks with >= 10 spots
settings.addTrackFilter(new FeatureFilter("NUMBER_SPOTS", 10.0d, true))
trackmate.execTrackFiltering(true)

// Print summary
for (id in model.trackModel.trackIDs(true)) {
    def spots = model.trackModel.trackSpots(id)
    def sorted = new ArrayList(spots); sorted.sort { it.getFeature("FRAME") }
    def first = sorted.first(); def last = sorted.last()
    def dx = last.getFeature("POSITION_X") - first.getFeature("POSITION_X")
    def dy = last.getFeature("POSITION_Y") - first.getFeature("POSITION_Y")
    println("Track ${id}: ${spots.size()} spots, disp=${String.format(\"%.1f\", Math.sqrt(dx*dx+dy*dy))}")
}
'
```

### Track metrics

| Metric | Meaning |
|--------|---------|
| Displacement | Net distance first→last |
| Track length | Total path traveled |
| Confinement ratio | Displacement/Length (1=straight, 0=random) |
| Mean speed | Length / duration |
| MSD | Mean squared displacement — characterises diffusion |

**MSD interpretation:** linear → Brownian; alpha >1 → directed transport; alpha <1 → confined; plateau → bounded.

### Exporting results
```bash
python ij.py macro 'selectWindow("Spots in tracks statistics"); saveAs("Results", "/path/to/spots.csv");'
python ij.py macro 'selectWindow("Track statistics"); saveAs("Results", "/path/to/tracks.csv");'
```

---

## §7 Cell Division Analysis

### Morphological indicators

| Phase | Change | Measurable |
|-------|--------|-----------|
| Prometaphase | Cell rounding | Circularity → 1.0 |
| Metaphase | Maximum rounding | Peak circularity |
| Anaphase | Chromosomes separate | Two intensity peaks |
| Cytokinesis | Cell splits | Area halves |

### Detection by area/circularity
```bash
python ij.py macro '
  nF = nSlices;
  run("Set Measurements...", "area centroid circularity shape display redirect=None decimal=3");
  for (i = 1; i <= nF; i++) {
      setSlice(i);
      run("Analyze Particles...", "size=100-Infinity circularity=0.3-1.0 display slice");
  }
  print("Look for: area drops (division), circularity spikes (rounding)");
'
```

### TrackMate with division detection
Enable `ALLOW_TRACK_SPLITTING=true` and set `SPLITTING_MAX_DISTANCE` in tracker settings.

### FUCCI reporters

| Phase | Color | Indicator |
|-------|-------|-----------|
| G1 | Red (mKO2-Cdt1) | Only red |
| S | Yellow | Red + Green |
| S/G2 | Green (mAG-Geminin) | Only green |
| M | Dim/absent | Both degraded |

Measure red/green ratio per cell over time: ratio >1 = S/G2, <1 = G1.

---

## §8 Cell Death Detection

### Morphological indicators

| Type | Sign | Measurable |
|------|------|-----------|
| Apoptosis | Blebbing, shrinkage, fragmentation, detachment | Area decrease >30%/frame, circularity oscillations |
| Necrosis | Swelling, membrane rupture | Area increase >50%, PI/SYTOX uptake spike |

### Death marker detection (PI/SYTOX)
```bash
python ij.py macro '
  getDimensions(w, h, c, z, t);
  run("Clear Results"); row = 0;
  for (f = 1; f <= t; f++) {
      Stack.setPosition(2, 1, f);  // death marker channel
      getStatistics(area, mean, min, max);
      setResult("Frame", row, f);
      setResult("PI_Mean", row, mean);
      setAutoThreshold("Otsu");
      run("Analyze Particles...", "size=50-Infinity display slice");
      resetThreshold(); row++;
  }
  updateResults();
'
```

---

## §9 Confluency Analysis

### Threshold-based confluency
```bash
python ij.py macro '
  nF = nSlices; totalArea = getWidth() * getHeight();
  run("Clear Results");
  for (i = 1; i <= nF; i++) {
      setSlice(i);
      setAutoThreshold("Otsu dark");
      run("Create Selection");
      if (selectionType() != -1) { getStatistics(cellArea); } else { cellArea = 0; }
      resetThreshold(); run("Select None");
      setResult("Frame", i-1, i);
      setResult("Confluency_pct", i-1, (cellArea / totalArea) * 100);
  }
  updateResults();
'
```

### Doubling time
From confluency data: find when confluency doubles (e.g., 20% → 40%), divide frame difference by frame interval.

### Wound healing plugins

| Plugin | Features |
|--------|----------|
| Wound_healing_size_tool | Auto detection, area, width |
| MRI Wound Healing Tool | Coherency, directional migration |

```bash
grep -i "wound\|scratch" .tmp/commands.md
python ij.py macro 'run("Wound healing size tool");'
```

---

## §10 Temporal Intensity Analysis

### Single ROI trace
```bash
# Quick method
python ij.py macro '
  makeOval(200, 200, 30, 30);
  run("Plot Z-axis Profile");
'

# Full extraction
python ij.py macro '
  makeOval(200, 200, 30, 30);
  run("Set Measurements...", "mean min max integrated display redirect=None decimal=3");
  nF = nSlices; run("Clear Results");
  for (i = 1; i <= nF; i++) { setSlice(i); run("Measure"); }
'
```

### Multi-ROI traces
```bash
python ij.py macro '
  cellX = newArray(100, 200, 300); cellY = newArray(100, 150, 200); cellR = 15;
  nCells = cellX.length; nF = nSlices;
  run("Clear Results"); row = 0;
  for (f = 1; f <= nF; f++) {
      setSlice(f);
      for (c = 0; c < nCells; c++) {
          makeOval(cellX[c]-cellR, cellY[c]-cellR, 2*cellR, 2*cellR);
          getStatistics(area, mean);
          setResult("Frame", row, f); setResult("Cell", row, c+1); setResult("Mean", row, mean);
          row++;
      }
  }
  run("Select None"); updateResults();
'
```

### dF/F0 (relative fluorescence change)
```
dF/F0 = (F(t) - F0) / F0    where F0 = mean of baseline frames
```

```bash
python ij.py macro '
  makeOval(200, 200, 30, 30);
  nF = nSlices; baseline_frames = 10;
  intensities = newArray(nF);
  for (i = 1; i <= nF; i++) { setSlice(i); getStatistics(area, mean); intensities[i-1] = mean; }
  f0 = 0;
  for (i = 0; i < baseline_frames; i++) f0 += intensities[i];
  f0 /= baseline_frames;
  run("Clear Results");
  for (i = 0; i < nF; i++) {
      setResult("Frame", i, i+1);
      setResult("dF_F0", i, (intensities[i] - f0) / f0);
  }
  updateResults(); run("Select None");
'
```

### Whole-image dF/F0 map
```bash
python ij.py macro '
  title = getTitle(); nF = nSlices; baseline_frames = 20;
  run("Duplicate...", "title=baseline duplicate range=1-" + baseline_frames);
  run("Z Project...", "projection=[Average Intensity]"); rename("F0");
  close("baseline");
  selectWindow(title); run("32-bit");
  selectWindow("F0"); run("32-bit");
  imageCalculator("Subtract create 32-bit stack", title, "F0"); rename("dF");
  imageCalculator("Divide create 32-bit stack", "dF", "F0"); rename("dF_F0_" + title);
  setMinAndMax(-0.5, 2.0); run("Fire");
  close("dF"); close("F0");
'
```

### Peak detection
Consider threshold (minimum dF/F0 to count), minimum peak width (frames), and minimum inter-peak distance. Threshold at mean + 1 SD is a typical starting point.

### FFT frequency analysis
Create 1-row image from detrended signal, run FFT. Dominant peak position / (nFrames * frame_interval) = frequency.

---

## §11 Ratiometric & FRET Imaging

### Ratio image generation
```bash
python ij.py macro '
  title = getTitle();
  run("Split Channels");
  ch1 = "C1-" + title; ch2 = "C2-" + title;
  selectWindow(ch1); run("Subtract Background...", "rolling=50 stack"); run("32-bit");
  selectWindow(ch2); run("Subtract Background...", "rolling=50 stack"); run("32-bit");
  imageCalculator("Divide create 32-bit stack", ch1, ch2);
  rename("Ratio_" + title);
  // Remove NaN/Inf
  nF = nSlices;
  for (i = 1; i <= nF; i++) { setSlice(i); changeValues(NaN, NaN, 0); changeValues(1.0/0, 1.0/0, 0); }
  setMinAndMax(0.5, 2.0); run("Fire");
'
```

### Masked ratio (avoid background artifacts)
Create mask from sum of both channels (Otsu threshold), convert to 0/1, multiply with ratio image.

### FRET (sensitized emission)
Three-filter: DD (donor-donor), DA (donor-acceptor = FRET), AA (acceptor-acceptor).
E_FRET = FRET_corrected / (FRET_corrected + Donor).

```bash
grep -i "fret\|rifret" .tmp/commands.md
python ij.py macro 'run("RiFRET");'
```

### Calcium ratiometric (Fura-2)
Ratio = F340/F380. Higher ratio = higher [Ca2+]. Background subtract both channels, divide, apply LUT (Fire or mpl-inferno). Display range typically 0.3-3.0 as starting point.

---

## §12 Phototoxicity & Imaging Guidance

### Signs of phototoxicity

| Sign | Measurable Indicator |
|------|---------------------|
| Blebbing | Circularity decrease |
| Cell rounding | Circularity → 1.0 |
| Retraction | Area decrease, centroid shift |
| Division arrest | Mitotic index drops |
| Nuclear condensation | Intensity spike |

### Minimising photodamage

| Strategy | Approach |
|----------|----------|
| Reduce excitation | ND filters, lower power, shorter exposure |
| Increase interval | Slowest rate that captures biology |
| Far-red/NIR probes | Less energetic photons |
| Sensitive detection | sCMOS/EMCCD, high-NA objective, wider emission filters |
| Pixel binning | 2x2/4x4 — more signal at lower resolution |
| Sample protection | Antioxidants, phenol red-free media, oxygen scavengers |

### Nyquist temporal sampling

| Process | Typical Frame Rate |
|---------|-------------------|
| Calcium transients | 4-20 fps |
| Vesicle transport | 1-10 fps |
| Cell migration | 1 frame / 1-5 min |
| Cell division | 1 frame / 2-5 min |
| Wound healing | 1 frame / 5-30 min |
| Circadian rhythms | 1 frame / 15-60 min |

---

## §13 Multi-Position Time-Lapse

### Bio-Formats series navigation
```bash
python ij.py macro '
  run("Bio-Formats Macro Extensions");
  Ext.setId("/path/to/multiposition.nd2");
  Ext.getSeriesCount(seriesCount);
  print("Positions: " + seriesCount);
  for (i = 0; i < seriesCount; i++) {
      Ext.setSeries(i); Ext.getSeriesName(name);
      Ext.getSizeX(sizeX); Ext.getSizeY(sizeY); Ext.getSizeT(sizeT);
      print("Series " + i + ": " + name + " (" + sizeX + "x" + sizeY + ", T=" + sizeT + ")");
  }
  Ext.close();
'

# Open specific series (1-indexed in macro)
python ij.py macro 'run("Bio-Formats Importer", "open=/path/to/file.nd2 autoscale color_mode=Default view=Hyperstack stack_order=XYCZT series_3");'
```

### Batch all positions
```bash
python ij.py macro '
  filepath = "/path/to/multiposition.nd2";
  run("Bio-Formats Macro Extensions");
  Ext.setId(filepath); Ext.getSeriesCount(nSeries); Ext.close();
  for (s = 0; s < nSeries; s++) {
      run("Bio-Formats Importer", "open=" + filepath + " autoscale color_mode=Default view=Hyperstack stack_order=XYCZT series_" + (s+1));
      title = getTitle();
      // === analysis pipeline here ===
      close();
  }
  updateResults();
'
```

---

## §14 Video Export & Annotated Movies

### Export formats

| Format | Syntax | Notes |
|--------|--------|-------|
| AVI uncompressed | `run("AVI... ", "compression=Uncompressed frame=10 save=/path.avi");` | Large files |
| AVI JPEG | `run("AVI... ", "compression=JPEG quality=90 frame=10 save=/path.avi");` | Lossy |
| AVI PNG | `run("AVI... ", "compression=PNG frame=10 save=/path.avi");` | Lossless |
| MP4 (FFmpeg) | `run("Movie (FFMPEG)...", "format=mp4 codec=libx264 frame_rate=10 save=/path.mp4");` | Requires FFmpeg plugin |

### Annotations
```bash
# Timestamp
python ij.py macro 'run("Time Stamper", "starting=0 interval=5 x=10 y=20 font=14 decimal=0 anti-aliased or=sec");'

# Scale bar (overlay)
python ij.py macro 'run("Scale Bar...", "width=50 height=5 font=14 color=White background=None location=[Lower Right] overlay");'

# Flatten overlay + export
python ij.py macro '
  run("Flatten", "stack");
  run("AVI... ", "compression=JPEG quality=90 frame=10 save=/path/to/annotated.avi");
'
```

### Side-by-side comparison
```bash
python ij.py macro '
  selectWindow("raw"); run("RGB Color");
  selectWindow("processed"); run("RGB Color");
  run("Combine...", "stack1=raw stack2=processed");
'
```

### FFmpeg post-processing
```bash
ffmpeg -i output.avi -c:v libx264 -crf 18 -pix_fmt yuv420p output.mp4   # H.264
ffmpeg -i output.avi -vf "fps=10,scale=512:-1:flags=lanczos" output.gif  # GIF
ffmpeg -i output.avi -filter:v "setpts=2.0*PTS" slow.mp4                 # Slow down
```

### Temporal color coding
```bash
python ij.py macro 'run("Temporal-Color Code", "lut=Fire start=1 end=" + nSlices);'
```
Early frames → cool colours, late → warm. Shows migration paths and dynamic regions.

---

## §15 Plugin Quick Reference

### Drift Correction

| Plugin | Macro Command |
|--------|---------------|
| StackReg | `run("StackReg", "transformation=Translation");` |
| HyperStackReg | `run("HyperStackReg", "transformation=Translation channel1");` |
| Correct 3D Drift | `run("Correct 3D Drift", "channel=1");` |
| Fast4DReg | `run("Estimate and apply 3D+t drift");` |
| SIFT Alignment | See Section 2.1 |
| Manual Drift Correction | `run("Manual Drift Correction Plugin");` |

### Tracking

| Plugin | Key Feature |
|--------|------------|
| TrackMate | GUI + scripting, many detectors/trackers |
| Manual Tracking | Click-based |
| MTrackJ | Point-click with statistics |

### Kymograph

| Plugin | Macro Command |
|--------|---------------|
| Reslice | `run("Reslice [/]...", "output=1.000 start=Top avoid");` |
| KymoResliceWide | `run("KymoResliceWide ", "intensity=Maximum");` |
| Multi Kymograph | `run("Multi Kymograph", "linewidth=3");` |

### Video Export

| Plugin | Macro Command |
|--------|---------------|
| AVI Writer | `run("AVI... ", "compression=JPEG quality=90 frame=10 save=/path.avi");` |
| FFmpeg | `run("Movie (FFMPEG)...", "format=mp4 codec=libx264 frame_rate=10 save=/path.mp4");` |
| Image Sequence | `run("Image Sequence... ", "dir=/path/ format=TIFF");` |

### Annotations

| Plugin | Macro Command |
|--------|---------------|
| Time Stamper | `run("Time Stamper", "starting=0 interval=5 x=10 y=20 font=14 decimal=0 anti-aliased or=sec");` |
| Scale Bar | `run("Scale Bar...", "width=50 height=5 font=14 color=White background=None location=[Lower Right] overlay");` |

### FRAP & Dynamics

| Plugin | Feature |
|--------|---------|
| FRAP Tools | Automated FRAP analysis |
| Ratio Profiler | Ratiometric/Fura-2 |
| RiFRET | Intensity-based FRET |
| Time Series Analyzer | Multi-ROI time traces |

---

## §16 Appendix: Hyperstack Dimension Handling

```bash
# Get/set dimensions
python ij.py macro '
  getDimensions(w, h, c, z, t);
  Stack.setPosition(channel, slice, frame);
'

# Convert stack ↔ hyperstack
python ij.py macro 'run("Stack to Hyperstack...", "order=xyczt(default) channels=2 slices=10 frames=50 display=Color");'
python ij.py macro 'run("Hyperstack to Stack");'

# Swap dimensions (Z and T swapped)
python ij.py macro 'run("Re-order Hyperstack ...", "channels=[Channels (c)] slices=[Frames (t)] frames=[Slices (z)]");'

# Extract subsets
python ij.py macro 'run("Duplicate...", "title=ch1 duplicate channels=1");'
python ij.py macro 'run("Duplicate...", "title=subset duplicate frames=10-50");'
python ij.py macro 'run("Duplicate...", "title=z5 duplicate slices=5");'

# Max projection per timepoint
python ij.py macro 'run("Z Project...", "projection=[Max Intensity] all");'
```

### Useful temporal macros
```bash
# Multi Measure (ROI Manager — measures all frames at once)
python ij.py macro 'roiManager("Add"); roiManager("Multi Measure");'

# Temporal statistics maps
python ij.py macro 'run("Z Project...", "projection=[Standard Deviation]");'  # dynamic regions
python ij.py macro 'run("Z Project...", "projection=[Max Intensity]");'       # cumulative activity
python ij.py macro 'run("Z Project...", "projection=[Min Intensity]");'       # persistent signal
```

### Python trace analysis utilities
Use `load_traces()` to parse ImageJ Results CSV, `find_peaks()` for event detection (threshold at mean+SD), `compute_msd()` + `classify_motion()` for track analysis (alpha >1.5 = directed, 0.8-1.5 = diffusion, <0.8 = confined).

---

## §17 Common Problems & Solutions

### Focus drift
```bash
# Find best focus per timepoint (highest variance = sharpest)
python ij.py macro '
  getDimensions(w, h, c, z, t);
  for (f = 1; f <= t; f++) {
      bestZ = 1; bestVar = 0;
      for (zz = 1; zz <= z; zz++) {
          Stack.setPosition(1, zz, f);
          getStatistics(area, mean, min, max, stdDev);
          if (stdDev*stdDev > bestVar) { bestVar = stdDev*stdDev; bestZ = zz; }
      }
      print("Frame " + f + ": best z=" + bestZ);
  }
'
```
Prevention: pre-equilibrate 1+ hour, hardware autofocus (PFS/Definite Focus).

### Bleaching severity diagnosis
```bash
python ij.py macro '
  nF = nSlices;
  setSlice(1); getStatistics(area, mean1);
  setSlice(nF); getStatistics(area, meanN);
  bleachPct = (1 - meanN/mean1) * 100;
  print("Bleaching: " + d2s(bleachPct, 1) + "%");
  if (bleachPct > 50) print("Severe — consider reducing excitation");
  else if (bleachPct > 20) print("Moderate — Histogram Matching recommended");
  else print("Mild — Simple Ratio typically sufficient");
'
```

### Intensity fluctuations (condensation/temperature)
Large frame-to-frame changes (>5%) suggest condensation or temperature cycling. Pre-equilibrate, use heated lid.

### Common macro errors

| Error | Fix |
|-------|-----|
| "No image open" | Check state, reopen |
| "Not a stack" | Verify frame count >1 |
| "Not a hyperstack" | Convert with Stack to Hyperstack |
| "Selection required" | Create ROI first |
| "Out of memory" | Close other images, increase JVM memory |
| "Plugin not found" | Check .tmp/commands.md |
