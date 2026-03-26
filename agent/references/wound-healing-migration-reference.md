# Wound Healing & Cell Migration Analysis Reference

---

## 1. Assay Selection

| Assay | Measures | Resolution | Kinetic? | Cost |
|-------|----------|------------|----------|------|
| Scratch | Collective migration rate | Population | Yes | Low |
| Barrier insert | Collective migration (reproducible) | Population | Yes | Medium |
| Transwell (uncoated) | Chemotactic migration | Population | No | Medium |
| Transwell (Matrigel) | Invasion capacity | Population | No | Medium |
| Chemotaxis chamber | Directed migration | Single cell | Yes | High |
| Random migration | Intrinsic motility | Single cell | Yes | Low |
| Spheroid invasion | 3D invasion | Population/cell | Yes | Medium |

### Decision Tree

```
What do you want to measure?
├─ Collective migration (wound closure)?
│  ├─ Need reproducibility? → Barrier insert
│  └─ Quick & simple? → Scratch assay
├─ Chemotactic response?
│  ├─ Population endpoint? → Transwell
│  └─ Single cell kinetics? → Chemotaxis chamber
├─ Invasion through matrix?
│  ├─ Through membrane + ECM? → Matrigel transwell
│  └─ 3D from spheroid? → Spheroid invasion
└─ Intrinsic motility? → Random migration (sparse cells, time-lapse)
```

---

## 2. Wound Healing Analysis

### 2.1 Wound Edge Detection Methods

| Method | Brightfield | Phase Contrast | Fluorescence |
|--------|------------|----------------|--------------|
| Variance filter | Good (r=10-20) | Good (r=15-25) | Overkill |
| Edge detection | Fair | Poor (halos) | Fair |
| Direct threshold | Poor | Poor | Excellent |
| CLAHE + variance | Excellent | Good | N/A |

**How to choose variance radius:** Start with radius ~10 for clean images. Increase
toward 20-25 for noisy/phase contrast images. If wound edges appear ragged, increase
radius. If fine wound details disappear, decrease.

### 2.2 Variance-Based Detection (Recommended for Brightfield/Phase)

```javascript
// Parameters — adjust per image type
varianceRadius = 15;     // 5-30: higher for noisier images / phase contrast
minWoundSize = 5000;     // minimum wound area in pixels (filters debris)

title = getTitle();
run("Duplicate...", "title=wound_detection duplicate");
selectWindow("wound_detection");

run("32-bit");
run("Variance...", "radius=" + varianceRadius + " stack");
setAutoThreshold("Otsu dark");
run("Convert to Mask", "method=Otsu background=Dark calculate");
// Cells = white, wound = black

run("Close-", "stack");   // fill small gaps in cell layer
run("Open", "stack");     // remove small artifacts in wound
run("Invert", "stack");   // wound = white (foreground)
run("Analyze Particles...", "size=" + minWoundSize + "-Infinity show=Masks stack");
rename("wound_mask");
```

**Modality-specific preprocessing (add before variance filter):**

| Modality | Preprocessing |
|----------|--------------|
| Phase contrast | `run("Subtract Background...", "rolling=50 stack"); run("Median...", "radius=3 stack");` — use variance radius ~20, Triangle threshold |
| Brightfield | `run("Enhance Local Contrast (CLAHE)", "blocksize=127 histogram=256 maximum=3 mask=*None* fast_(less_accurate)");` on duplicate |
| Fluorescence | Skip variance. Instead: `run("Gaussian Blur...", "sigma=5 stack");` then direct Otsu threshold |

### 2.3 Handling Debris

```javascript
// After creating wound mask — remove small objects (debris)
// Method 1: Size filtering (preferred)
run("Analyze Particles...", "size=5000-Infinity show=Masks stack");

// Method 2: Morphological opening for persistent debris
run("Erode", "stack"); run("Erode", "stack"); run("Erode", "stack");
run("Dilate", "stack"); run("Dilate", "stack"); run("Dilate", "stack");
```

### 2.4 Wound Area Measurement Over Time

```javascript
selectWindow("wound_mask");
run("Set Measurements...", "area display redirect=None decimal=3");
nFrames = nSlices();
for (i = 1; i <= nFrames; i++) {
    setSlice(i);
    setThreshold(128, 255);
    run("Create Selection");
    if (selectionType() != -1) {
        run("Measure");
    } else {
        setResult("Area", nResults, 0);  // wound fully closed
    }
    run("Select None");
}
resetThreshold();
updateResults();
```

### 2.5 Wound Width Measurement

Use when wound length varies between images. Measures width at multiple Y positions.

```javascript
selectWindow("wound_mask");
nLines = 30;  // more lines = more precise average
imgWidth = getWidth();
imgHeight = getHeight();
spacing = floor(imgHeight / (nLines + 1));
getPixelSize(unit, pw, ph);

for (frame = 1; frame <= nSlices(); frame++) {
    setSlice(frame);
    widths = newArray(nLines);
    count = 0;
    for (line = 0; line < nLines; line++) {
        y = (line + 1) * spacing;
        leftEdge = -1; rightEdge = -1;
        for (x = 0; x < imgWidth; x++) {
            v = getPixel(x, y);
            if (v > 128 && leftEdge == -1) leftEdge = x;
            if (v > 128) rightEdge = x;
        }
        if (leftEdge >= 0 && rightEdge > leftEdge) {
            widths[count] = (rightEdge - leftEdge) * pw;
            count++;
        }
    }
    sum = 0;
    for (j = 0; j < count; j++) sum += widths[j];
    mean = (count > 0) ? sum / count : 0;
    sumSq = 0;
    for (j = 0; j < count; j++) sumSq += (widths[j] - mean) * (widths[j] - mean);
    sd = (count > 1) ? sqrt(sumSq / (count - 1)) : 0;
    row = nResults;
    setResult("Frame", row, frame);
    setResult("MeanWidth", row, mean);
    setResult("SD", row, sd);
}
updateResults();
```

### 2.6 Closure Rate Metrics

| Metric | Formula | When to Use |
|--------|---------|-------------|
| Absolute rate | `(Area_t0 - Area_t) / (t - t0)` [um^2/h] | Comparing same initial size |
| % Closure | `(Area_t0 - Area_t) / Area_t0 * 100` | Standard — normalizes initial size |
| T50 | Time to 50% closure | Single summary statistic |
| AUC | Integral of closure curve | Full kinetic comparison |

### 2.7 Python Closure Analysis

```python
import numpy as np
from scipy.optimize import curve_fit
from scipy.integrate import trapezoid

def full_analysis(csv_path, time_interval_hours, pixel_size_um=None):
    """Complete wound healing analysis from ImageJ CSV with Area column."""
    import csv
    areas = []
    with open(csv_path, 'r') as f:
        for row in csv.DictReader(f):
            areas.append(float(row['Area']))
    areas = np.array(areas)
    times = np.arange(len(areas)) * time_interval_hours
    if pixel_size_um is not None:
        areas = areas * (pixel_size_um ** 2)

    initial = areas[0]
    pct = (initial - areas) / initial * 100.0 if initial > 0 else np.zeros_like(areas)

    # T50: interpolate time to 50% closure
    target = initial * 0.5
    t50 = float('nan')
    for i in range(1, len(areas)):
        if areas[i] <= target:
            t50 = times[i-1] + (times[i] - times[i-1]) * (areas[i-1] - target) / (areas[i-1] - areas[i])
            break

    auc = trapezoid(areas, times)
    avg_rate = (areas[0] - areas[-1]) / (times[-1] - times[0]) if times[-1] > times[0] else 0.0

    # Fit exponential: Area = a0 * exp(-k * t)
    try:
        popt, _ = curve_fit(lambda t, a0, k: a0 * np.exp(-k * t),
                            times, areas, p0=[areas[0], 0.1], maxfev=5000)
        predicted = popt[0] * np.exp(-popt[1] * times)
        ss_res = np.sum((areas - predicted) ** 2)
        ss_tot = np.sum((areas - np.mean(areas)) ** 2)
        r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else 0.0
        fit = {'a0': popt[0], 'k': popt[1], 'half_life': np.log(2)/popt[1], 'r2': r2}
    except Exception as e:
        fit = {'error': str(e)}

    return {'times': times, 'areas': areas, 'pct_closure': pct,
            'final_pct': pct[-1], 'avg_rate_um2h': avg_rate,
            'T50_hours': t50, 'AUC': auc, 'exp_fit': fit}
```

---

## 3. ImageJ Plugins for Wound Healing

| Plugin | Method | Speed | Error vs Ref | Install |
|--------|--------|-------|-------------|---------|
| Wound Healing Size Tool | Variance + threshold | 6-13 s/img | -0.5% +/- 2.8% | macros/toolsets/ |
| MRI Wound Healing Tool | Variance or Find Edges | 6-13 s/img | +6.5% +/- 4.4% | macros/toolsets/ |
| CSMA | Canny edge detection | Varies | Reference | JAR + Python env |
| TScratch | Curvelet transform | 1-2 s/img | ~34% error | MATLAB MCR (legacy) |

**MRI Wound Healing Tool parameters:** `VARIANCE_FILTER_RADIUS=20`, `THRESHOLD=1`,
`RADIUS_CLOSE=4`, `MINIMAL_SIZE=999999`, `METHOD="variance"`. Variant with coherency
analysis requires ImageScience + IJPB-plugins update sites.

**CSMA** detects migrating cells within the wound center (unique feature). Requires
Anaconda with ImageJCSMA environment.

### Complete Core-ImageJ Wound Analysis Macro (No Plugins)

```javascript
Dialog.create("Wound Healing Analysis");
Dialog.addChoice("Image type:", newArray("Brightfield", "Phase Contrast", "Fluorescence"), "Brightfield");
Dialog.addNumber("Variance filter radius:", 15);
Dialog.addNumber("Min wound size (pixels):", 5000);
Dialog.addNumber("Time interval (hours):", 1.0);
Dialog.addCheckbox("Show wound overlay", true);
Dialog.show();

imageType = Dialog.getChoice();
varRadius = Dialog.getNumber();
minSize = Dialog.getNumber();
timeInterval = Dialog.getNumber();
showOverlay = Dialog.getCheckbox();

srcTitle = getTitle();
srcID = getImageID();
getPixelSize(unit, pw, ph);
run("Duplicate...", "title=_wound_proc duplicate");
procID = getImageID();

if (imageType == "Brightfield" || imageType == "Phase Contrast") {
    run("32-bit");
    if (imageType == "Phase Contrast") {
        run("Subtract Background...", "rolling=50 stack");
        run("Median...", "radius=3 stack");
    }
    run("Variance...", "radius=" + varRadius + " stack");
    setAutoThreshold("Otsu dark");
    run("Convert to Mask", "method=Otsu background=Dark calculate");
    run("Invert", "stack");
} else {
    run("Gaussian Blur...", "sigma=5 stack");
    setAutoThreshold("Otsu dark");
    run("Convert to Mask", "method=Otsu background=Dark calculate");
    run("Invert", "stack");
}

run("Close-", "stack"); run("Open", "stack"); run("Fill Holes", "stack");
run("Analyze Particles...", "size=" + minSize + "-Infinity show=Masks stack");
rename("_wound_mask"); maskID = getImageID();

run("Set Measurements...", "area display redirect=None decimal=3");
run("Clear Results");
nFrames = nSlices();
for (i = 1; i <= nFrames; i++) {
    setSlice(i);
    setThreshold(128, 255);
    run("Create Selection");
    if (selectionType() != -1) run("Measure");
    else setResult("Area", nResults, 0);
    run("Select None");
}
resetThreshold();

initialArea = getResult("Area", 0);
for (i = 0; i < nResults; i++) {
    area = getResult("Area", i);
    setResult("Time_h", i, i * timeInterval);
    if (initialArea > 0)
        setResult("PctClosure", i, (initialArea - area) / initialArea * 100);
    if (i > 0)
        setResult("ClosureRate", i, (getResult("Area", i-1) - area) / timeInterval);
}
updateResults();

if (showOverlay) {
    selectImage(srcID); run("Remove Overlay");
    for (i = 1; i <= nFrames; i++) {
        selectImage(maskID); setSlice(i);
        setThreshold(128, 255); run("Create Selection");
        if (selectionType() != -1) {
            selectImage(srcID); setSlice(i);
            run("Restore Selection"); Overlay.addSelection("cyan", 2);
        }
    }
    selectImage(srcID); Overlay.show();
}
selectImage(procID); close();
```

---

## 4. Individual Cell Migration Analysis

### 4.1 TrackMate Configuration

**Detector selection:**

| Detector | Best For | Key Param |
|----------|----------|-----------|
| LoG/DoG | Round cells, nuclei | RADIUS = half cell diameter |
| Threshold/Mask | Pre-segmented | INTENSITY_THRESHOLD |
| StarDist | Nuclei (deep learning) | SCORE_THRESHOLD |
| Cellpose | Any cell type | Model, diameter |

**Tracker selection:**

| Tracker | Best For | Key Param |
|---------|----------|-----------|
| Simple LAP | Non-dividing, well-separated | LINKING_MAX_DISTANCE |
| LAP | Dividing cells | + merge/split params |
| Kalman | Fast, predictable motion | KALMAN_SEARCH_RADIUS |
| Overlap | Large cells, segmentation | IoU threshold |

**How to choose parameters:**
- `RADIUS`: Half the typical cell diameter (in calibrated units)
- `THRESHOLD`: Start at 0 (keep all), increase to reject dim/spurious detections
- `LINKING_MAX_DISTANCE`: max_speed * frame_interval + 50% safety margin
- `GAP_CLOSING_MAX_DISTANCE`: Typically 1.5x LINKING_MAX_DISTANCE
- `MAX_FRAME_GAP`: 2-3 frames is typical

### 4.2 TrackMate Jython Script

```python
from fiji.plugin.trackmate import Model, Settings, TrackMate, Logger
from fiji.plugin.trackmate.detection import LogDetectorFactory
from fiji.plugin.trackmate.tracking.jaqaman import SparseLAPTrackerFactory
from fiji.plugin.trackmate.tracking import LAPUtils
from fiji.plugin.trackmate.features import FeatureFilter
from ij import IJ

imp = IJ.getImage()
model = Model()
model.setLogger(Logger.IJ_LOGGER)
settings = Settings(imp)

settings.detectorFactory = LogDetectorFactory()
settings.detectorSettings = {
    'DO_SUBPIXEL_LOCALIZATION': True,
    'RADIUS': 7.5,          # half cell diameter in um
    'TARGET_CHANNEL': 1,
    'THRESHOLD': 0.0,
    'DO_MEDIAN_FILTERING': False,
}

settings.trackerFactory = SparseLAPTrackerFactory()
settings.trackerSettings = LAPUtils.getDefaultLAPSettingsMap()
settings.trackerSettings['LINKING_MAX_DISTANCE'] = 25.0
settings.trackerSettings['GAP_CLOSING_MAX_DISTANCE'] = 30.0
settings.trackerSettings['MAX_FRAME_GAP'] = 3

settings.addAllAnalyzers()  # CRITICAL: scripts must add analyzers explicitly
filter_track = FeatureFilter('NUMBER_SPOTS', 5, True)
settings.addTrackFilter(filter_track)

trackmate = TrackMate(model, settings)
ok = trackmate.checkInput() and trackmate.process()
if not ok:
    IJ.error(str(trackmate.getErrorMessage()))
else:
    fm = model.getFeatureModel()
    IJ.log("TrackID,Duration,Displacement,TotalDist,MeanSpeed,Directionality")
    for tid in model.getTrackModel().trackIDs(True):
        dur = fm.getTrackFeature(tid, 'TRACK_DURATION')
        disp = fm.getTrackFeature(tid, 'TRACK_DISPLACEMENT')
        dist = fm.getTrackFeature(tid, 'TOTAL_DISTANCE_TRAVELED')
        speed = fm.getTrackFeature(tid, 'TRACK_MEAN_SPEED')
        dr = disp / dist if dist > 0 else 0
        IJ.log("%d,%.2f,%.2f,%.2f,%.4f,%.4f" % (tid, dur, disp, dist, speed, dr))
```

### 4.3 Migration Metrics

| Metric | Formula | TrackMate Feature | Interpretation |
|--------|---------|-------------------|----------------|
| Total distance | sum of step distances | `TOTAL_DISTANCE_TRAVELED` | How far cell actually traveled |
| Net displacement | start-to-end distance | `TRACK_DISPLACEMENT` | Straight-line displacement |
| Directionality ratio | displacement / total_dist | Compute from above | 0=random, 1=straight line |
| Mean speed | total_dist / duration | `TRACK_MEAN_SPEED` | Average velocity |
| Confinement ratio | D_net^2 / (4 * D_total * dt * N) | Compute | 0=confined, 1=free |

**Caveat:** Directionality ratio is biased by track length — short tracks have higher
DR by chance. Direction autocorrelation is less biased (persistence time = tau where
C(tau) = 1/e).

### 4.4 MSD Analysis

| Motion Type | MSD Behavior | Alpha | Meaning |
|------------|-------------|-------|---------|
| Free diffusion | MSD = 4D*tau | 1 | Random walk |
| Directed | MSD = 4D*tau + (v*tau)^2 | >1 | Persistent/ballistic |
| Confined | MSD plateaus | <1 | Restricted |
| Anomalous | MSD ~ tau^alpha | 0-1 | Crowded environment |

```python
import numpy as np
from scipy.optimize import curve_fit

def calculate_msd(x, y, max_lag=None):
    """MSD for a single track. max_lag defaults to N/4."""
    N = len(x)
    if max_lag is None: max_lag = N // 4
    lags = np.arange(1, max_lag + 1)
    msd = np.zeros(len(lags))
    for i, lag in enumerate(lags):
        dx = x[lag:] - x[:-lag]
        dy = y[lag:] - y[:-lag]
        msd[i] = np.mean(dx**2 + dy**2)
    return lags, msd

def classify_motion(x, y, dt, max_lag=None):
    """Fit anomalous MSD model: MSD = 4D * tau^alpha. Returns alpha, D, classification."""
    lags, msd = calculate_msd(x, y, max_lag)
    tau = lags * dt
    D_est = msd[0] / (4.0 * tau[0])
    popt, _ = curve_fit(lambda t, D, a: 4*D*t**a, tau, msd,
                        p0=[D_est, 1.0], bounds=([0, 0.01], [np.inf, 3.0]), maxfev=5000)
    alpha = popt[1]
    cls = 'directed' if alpha > 1.2 else ('confined' if alpha < 0.8 else 'free_diffusion')
    return {'D': popt[0], 'alpha': alpha, 'classification': cls}

def load_trackmate_csv(csv_path):
    """Load tracks from TrackMate CSV (TRACK_ID, POSITION_X, POSITION_Y, FRAME)."""
    import csv
    tracks_dict = {}
    with open(csv_path, 'r') as f:
        for row in csv.DictReader(f):
            tid = int(float(row['TRACK_ID']))
            tracks_dict.setdefault(tid, []).append(
                {'x': float(row['POSITION_X']), 'y': float(row['POSITION_Y']),
                 'frame': int(float(row['FRAME']))})
    tracks = []
    for tid in sorted(tracks_dict):
        pts = sorted(tracks_dict[tid], key=lambda p: p['frame'])
        tracks.append((np.array([p['x'] for p in pts]), np.array([p['y'] for p in pts])))
    return tracks
```

### 4.5 Wind Rose Plot (ImageJ Macro Generator)

```python
def generate_wind_rose_macro(tracks, output_size=512, scale_um=100):
    """Generate ImageJ macro for wind rose plot. Tracks should be origin-normalized."""
    cx = cy = output_size // 2
    px_per_um = (output_size / 2.0) / scale_um
    macro = ['newImage("Wind Rose", "RGB White", %d, %d, 1);' % (output_size, output_size)]
    macro.append('setColor(200, 200, 200);')
    macro.append('drawLine(%d, 0, %d, %d);' % (cx, cx, output_size))
    macro.append('drawLine(0, %d, %d, %d);' % (cy, output_size, cy))
    macro.append('setColor(0, 100, 200); setLineWidth(1);')
    for x, y in tracks:
        for i in range(len(x) - 1):
            x1, y1 = int(cx + x[i]*px_per_um), int(cy - y[i]*px_per_um)
            x2, y2 = int(cx + x[i+1]*px_per_um), int(cy - y[i+1]*px_per_um)
            if all(0 <= v < output_size for v in [x1, y1, x2, y2]):
                macro.append('drawLine(%d, %d, %d, %d);' % (x1, y1, x2, y2))
    macro.append('setColor(255, 0, 0);')
    for x, y in tracks:
        ex, ey = int(cx + x[-1]*px_per_um), int(cy - y[-1]*px_per_um)
        if 0 <= ex < output_size and 0 <= ey < output_size:
            macro.append('fillOval(%d, %d, 4, 4);' % (ex-2, ey-2))
    return '\n'.join(macro)
```

---

## 5. Chemotaxis Analysis

### 5.1 Chemotaxis and Migration Tool (ibidi)

Install from ibidi website. Input: X,Y coordinates from Manual Tracking or TrackMate.
Define gradient direction, then calculate metrics.

### 5.2 Key Metrics

| Metric | Formula | Interpretation |
|--------|---------|----------------|
| FMI parallel | `mean(y_displacement / d_total)` | >0 = toward attractant, ~0 = no response |
| FMI perpendicular | `mean(x_displacement / d_total)` | Should be ~0 (no systematic drift) |
| Center of mass | `mean(endpoint_x), mean(endpoint_y)` | Population average displacement |
| Rayleigh R | Mean resultant length | 0=uniform, 1=perfectly aligned |

### 5.3 Rayleigh Test and Chemotaxis Analysis

```python
import numpy as np

def rayleigh_test(angles_degrees):
    """Test for non-uniform circular distribution. Returns R, p_value."""
    angles_rad = np.radians(angles_degrees)
    n = len(angles_rad)
    C, S = np.mean(np.cos(angles_rad)), np.mean(np.sin(angles_rad))
    R = np.sqrt(C**2 + S**2)
    Z = n * R**2
    p = np.exp(-Z) * (1 + (2*Z - Z**2)/(4*n) -
        (24*Z - 132*Z**2 + 76*Z**3 - 9*Z**4)/(288*n**2))
    return {'R': R, 'mean_angle': np.degrees(np.arctan2(S, C)),
            'p_value': max(p, 0.0), 'significant': p < 0.05}

def chemotaxis_analysis(tracks, dt, gradient_dir_deg=90):
    """Full chemotaxis analysis. Tracks: list of (x,y) in um, normalized to origin."""
    grad_rad = np.radians(gradient_dir_deg)
    fmi_x, fmi_y, speeds, endpoint_angles = [], [], [], []
    for x, y in tracks:
        x_rot = x * np.sin(grad_rad) - y * np.cos(grad_rad)
        y_rot = x * np.cos(grad_rad) + y * np.sin(grad_rad)
        d_total = np.sum(np.sqrt(np.diff(x)**2 + np.diff(y)**2))
        if d_total > 0:
            fmi_y.append(y_rot[-1] / d_total)
            fmi_x.append(x_rot[-1] / d_total)
        speeds.append(d_total / ((len(x)-1) * dt))
        endpoint_angles.append(np.degrees(np.arctan2(y[-1], x[-1])))
    rayleigh = rayleigh_test(np.array(endpoint_angles))
    n = len(tracks)
    return {
        'n_tracks': n,
        'FMI_parallel': np.mean(fmi_y), 'FMI_parallel_sem': np.std(fmi_y)/np.sqrt(n),
        'FMI_perpendicular': np.mean(fmi_x),
        'mean_speed': np.mean(speeds),
        'rayleigh_R': rayleigh['R'], 'rayleigh_p': rayleigh['p_value'],
    }
```

---

## 6. Invasion Assays

### 6.1 Transwell Quantification

Two approaches: **cell counting** (individual cells visible) or **area fraction**
(confluent/clustered cells).

```javascript
// TRANSWELL CELL COUNTING — Crystal Violet
run("8-bit");
run("Subtract Background...", "rolling=50 light");
run("Invert");
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Watershed");
// Size range: consider ~50-500 um^2 (small cells) to ~200-10000 um^2 (large cells)
run("Analyze Particles...",
    "size=200-5000 circularity=0.3-1.00 show=Outlines display summarize");
```

```javascript
// TRANSWELL AREA FRACTION — when cells are clustered
run("Colour Deconvolution", "vectors=[H&E]");
selectWindow("area_frac-(Colour_1)");  // crystal violet ~ hematoxylin
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Set Measurements...", "area area_fraction display redirect=None decimal=3");
run("Select All"); run("Measure");
// %Area = fraction of stained pixels
```

### 6.2 Batch Transwell Processing

```javascript
inputDir = getDirectory("Choose input folder");
outputDir = getDirectory("Choose output folder");
list = getFileList(inputDir);
setBatchMode(true);
for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif") || endsWith(list[i], ".jpg")) {
        open(inputDir + list[i]);
        run("8-bit"); run("Subtract Background...", "rolling=50 light");
        run("Invert"); setAutoThreshold("Otsu dark");
        run("Convert to Mask"); run("Watershed");
        run("Analyze Particles...", "size=200-5000 circularity=0.3-1.00 summarize");
        close();
    }
}
setBatchMode(false);
saveAs("Results", outputDir + "transwell_counts.csv");
```

### 6.3 3D Invasion Depth (Z-Stack)

```javascript
// Count cells per z-slice; membrane surface at z=1
getPixelSize(unit, pw, ph);
zSpacing = Stack.getZStep();
if (zSpacing == 0) { zSpacing = 1; print("WARNING: Z-spacing not calibrated"); }

print("Slice\tDepth_um\tCellCount");
for (z = 1; z <= nSlices(); z++) {
    setSlice(z);
    run("Duplicate...", "title=_slice");
    setAutoThreshold("Otsu dark"); run("Convert to Mask"); run("Watershed");
    run("Analyze Particles...", "size=50-5000 summarize");
    close();
}
```

**Invasion metrics:**

```
Invasion Index = cells_beyond_threshold / total_cells * 100
Weighted Invasion = sum(n_cells(z) * depth(z)) / total_cells
```

### 6.4 Spheroid Invasion

```javascript
// Detect total invaded region (core + protrusions)
run("8-bit"); run("Subtract Background...", "rolling=100");
run("Duplicate...", "title=_total");
run("Gaussian Blur...", "sigma=3");
setAutoThreshold("Triangle dark"); run("Convert to Mask");
run("Fill Holes"); run("Close-");
run("Set Measurements...", "area centroid shape feret's redirect=None decimal=3");
run("Analyze Particles...", "size=1000-Infinity display");
totalArea = getResult("Area", nResults - 1);

// Detect core only (stricter threshold)
selectWindow("_sph_analysis");
run("Duplicate...", "title=_core");
run("Gaussian Blur...", "sigma=5");
setAutoThreshold("Otsu dark"); run("Convert to Mask");
run("Fill Holes"); run("Close-");
run("Erode"); run("Erode"); run("Dilate"); run("Dilate");
run("Analyze Particles...", "size=5000-Infinity display");
coreArea = getResult("Area", nResults - 1);

invasionIndex = (totalArea - coreArea) / coreArea;
```

**INSIDIA plugin** (Moriconi et al. 2017): Automated spheroid invasion via radial
density profiling. Handles brightfield and fluorescence. Optional Frangi filtering
for low-contrast cells.

---

## 7. Statistical Analysis

### 7.1 Biological Replicates (Critical)

The biological replicate is the **independent experiment** (different passage/day),
NOT individual wells. Multiple wells per condition within one experiment are technical
replicates. Average wells within each experiment first, then compare between experiments.

### 7.2 Sample Size Recommendations

| Assay | Min n (experiments) | Technical reps per condition |
|-------|--------------------|-----------------------------|
| Scratch/barrier | 3 | 2-3 wells |
| Transwell | 3 | 3 fields/membrane, 3 membranes |
| Cell tracking | 3 | 20-50 cells/experiment |
| Spheroid invasion | 3 | 5-10 spheroids |

### 7.3 Comparing Conditions

```python
from scipy import stats
import numpy as np

def compare_closure(ctrl, trt, test='welch'):
    """Compare closure (one value per independent experiment)."""
    ctrl, trt = np.array(ctrl), np.array(trt)
    if test == 'welch':
        stat, p = stats.ttest_ind(ctrl, trt, equal_var=False)
    elif test == 'mann-whitney':
        stat, p = stats.mannwhitneyu(ctrl, trt, alternative='two-sided')
    # Cohen's d
    pooled_sd = np.sqrt(((len(ctrl)-1)*np.std(ctrl,ddof=1)**2 +
                          (len(trt)-1)*np.std(trt,ddof=1)**2) / (len(ctrl)+len(trt)-2))
    d = (np.mean(trt) - np.mean(ctrl)) / pooled_sd if pooled_sd > 0 else 0
    return {'p': p, 'cohens_d': d, 'ctrl_mean': np.mean(ctrl), 'trt_mean': np.mean(trt)}

# Multiple conditions: one-way ANOVA + Tukey HSD
stat, p = stats.f_oneway(*groups)
if p < 0.05:
    tukey = stats.tukey_hsd(*groups)
```

### 7.4 Time-Course and Cell-Level Analysis

**Closure curves:** Compare via AUC (trapezoid integration of % closure), then t-test
on AUC values. Alternatively, two-way repeated measures ANOVA (condition x time).

**Cell-level speeds:** Use linear mixed model (cells nested within experiments):
```python
# statsmodels
model = smf.mixedlm("speed ~ condition", data=df, groups=df["experiment"]).fit()
# R: lmer(speed ~ condition + (1|experiment), data=df)
```

---

## 8. Agent Workflows

### 8.1 Wound Healing

```bash
python ij.py state
python ij.py macro 'open("/path/to/scratch_timelapse.tif");'
python ij.py capture after_open
python ij.py metadata              # check calibration
python ij.py info                  # check dimensions

# Wound detection (adjust varRadius for your images)
python ij.py macro '
  run("Duplicate...", "title=proc duplicate");
  run("32-bit"); run("Variance...", "radius=15 stack");
  setAutoThreshold("Otsu dark");
  run("Convert to Mask", "method=Otsu background=Dark calculate");
  run("Invert", "stack"); run("Close-", "stack"); run("Open", "stack");
  run("Analyze Particles...", "size=5000-Infinity show=Masks stack");
  rename("wound_mask");
'
python ij.py capture wound_mask

# Measure + closure %
python ij.py macro '
  selectWindow("wound_mask");
  run("Set Measurements...", "area display redirect=None decimal=3");
  run("Clear Results");
  for (i = 1; i <= nSlices(); i++) {
      setSlice(i); setThreshold(128, 255);
      run("Create Selection");
      if (selectionType() != -1) run("Measure");
      else setResult("Area", nResults, 0);
      run("Select None");
  }
  resetThreshold();
  initialArea = getResult("Area", 0);
  for (i = 0; i < nResults; i++) {
      area = getResult("Area", i);
      if (initialArea > 0) setResult("PctClosure", i, (initialArea - area) / initialArea * 100);
  }
  updateResults();
'
python ij.py results
python auditor.py
```

### 8.2 Cell Tracking with TrackMate

```bash
python ij.py state
python ij.py macro 'open("/path/to/migration_timelapse.tif");'
python ij.py capture migration_open
python ij.py metadata
python ij.py info    # note cell size for RADIUS, expected displacement

# Run TrackMate (see Section 4.2 for full Jython script)
python ij.py script --lang jython '...'
python ij.py log     # parse CSV track statistics
python ij.py capture tracks_overlay
```

### 8.3 Batch Wound Healing

```bash
python ij.py macro '
  inputDir = "/path/to/images/";
  outputDir = "/path/to/results/";
  list = getFileList(inputDir);
  setBatchMode(true);
  for (f = 0; f < list.length; f++) {
      if (endsWith(list[f], ".tif")) {
          open(inputDir + list[f]);
          title = getTitle();
          run("Duplicate...", "title=proc duplicate");
          run("32-bit"); run("Variance...", "radius=15 stack");
          setAutoThreshold("Otsu dark");
          run("Convert to Mask", "method=Otsu background=Dark calculate");
          run("Invert", "stack"); run("Close-", "stack"); run("Open", "stack");
          run("Set Measurements...", "area display redirect=None decimal=3");
          for (i = 1; i <= nSlices(); i++) {
              setSlice(i); setThreshold(128, 255);
              run("Create Selection");
              if (selectionType() != -1) {
                  run("Measure");
                  setResult("File", nResults-1, title);
                  setResult("Frame", nResults-1, i);
              }
              run("Select None");
          }
          resetThreshold(); close(); close();
      }
  }
  setBatchMode(false); updateResults();
  saveAs("Results", outputDir + "batch_wound_results.csv");
'
python ij.py results
python auditor.py
```

---

## 9. Common Problems and Solutions

| Problem | Cause | Solution |
|---------|-------|---------|
| Uneven wound edges | Irregular scratch | Use barrier inserts; measure width at 50+ positions; increase variance radius |
| Proliferation confounds migration | Cell division in wound | Mitomycin C (10 ug/mL, 2h) pre-treatment; short timepoints (6-12h); serum-free media |
| Debris in wound | Detached cells/ECM | Wash 2-3x PBS after scratch; morphological opening; size filtering; use barrier assays |
| Focus drift | Long time-lapse | Autofocus; per-frame threshold (see below); phase contrast is more tolerant |
| Uneven illumination | Optics | `run("Subtract Background...", "rolling=50 stack");` or `run("Auto Local Threshold", "method=Phansalkar radius=25 ...")` |
| Stage drift | No position lock | `run("StackReg", "transformation=Translation");` or `run("Correct 3D drift", "channel=1");` |
| Cells under wound edge | Biology (undermigration) | Use more aggressive threshold; measure width rather than area |

**Per-frame threshold (for focus drift):**
```javascript
for (i = 1; i <= nSlices(); i++) {
    setSlice(i);
    run("Duplicate...", "title=_frame");
    run("32-bit"); run("Variance...", "radius=15");
    setAutoThreshold("Otsu dark"); run("Convert to Mask"); run("Invert");
    run("Select All"); run("Copy"); close();
    setSlice(i); run("Paste");
}
run("Select None");
```

### TrackMate Troubleshooting

| Problem | Parameter | Direction |
|---------|-----------|-----------|
| Too many false detections | THRESHOLD | Increase |
| Missing dim cells | THRESHOLD | Decrease |
| Merged adjacent cells | RADIUS | Decrease |
| Split large cells | RADIUS | Increase |
| Broken tracks | MAX_FRAME_GAP | Increase |
| Wrong linkages | LINKING_MAX_DISTANCE | Decrease |
| Identity swaps | Use Kalman tracker | Or add feature penalties: `'LINKING_FEATURE_PENALTIES': {'MEAN_INTENSITY_CH1': 1.0}` |

---

## 10. Best Practices Checklist

**Experimental:**
- Consider barrier/insert assays when quantitative comparison matters
- Include proliferation control (mitomycin C) in parallel wells
- Image entire wound width; use reference marks for same position over time
- Record metadata: pixel size, time interval, objective, passage, seeding density

**Analysis:**
- Never enhance contrast on source data — work on duplicates
- Verify calibration before measuring
- Normalize to initial wound area (report % closure)
- Overlay detected boundary on original to validate detection
- Combine area and width measurements

**Reporting:**

| Required | Why |
|----------|-----|
| Cell type, passage, seeding density | Reproducibility |
| Scratch method or insert type | Reproducibility |
| Microscope, objective, pixel size | Measurement accuracy |
| Detection method and parameters | Analysis reproducibility |
| % Closure + closure rate + T50/AUC | Standard metrics |
| n (independent experiments) + test | Statistical validity |
| Proliferation control status | Interpretation |
| Representative images T=0 and T=end | Visual evidence |

**Common mistakes:** Pseudoreplication (wells != experiments), cherry-picking timepoints,
no proliferation control, over-processing, not blinding analysis.

---

## Key Formulas

```
% Closure = (Area_0 - Area_t) / Area_0 * 100
Closure rate = delta_Area / delta_time                    [um^2/hour]
Directionality ratio = net_displacement / total_distance
MSD(tau) = <|r(t+tau) - r(t)|^2>_t
Diffusion coefficient: D = MSD(tau) / (4 * tau)           [2D]
FMI = (1/n) * sum(d_parallel / d_total)
Rayleigh: Z = n * R^2
Invasion index = invaded_area / core_area
```

## Plugin Installation

| Plugin | Install Location | Purpose |
|--------|-----------------|---------|
| TrackMate | Built-in | Cell tracking |
| MRI Wound Healing Tool | macros/toolsets/ | Wound area |
| Wound Healing Size Tool | macros/toolsets/ | Wound area + width |
| Chemotaxis Tool | plugins/ (ibidi) | Chemotaxis metrics |
| MorphoLibJ | IJPB-plugins update site | Morphological reconstruction |
| StackReg | BIG-EPFL update site | Drift correction |

## Python Dependencies

```
numpy>=1.20  scipy>=1.7  matplotlib>=3.5  pandas>=1.3  statsmodels>=0.13
```

## References

1. Suarez-Arnedo et al. (2020) PLOS ONE 15(7): e0232565 — Wound Healing Size Tool
2. Moriconi et al. (2017) Biotechnol J 12(10): 1700140 — INSIDIA
3. Ershov et al. (2022) Nat Methods 19: 829-832 — TrackMate 7
4. Gorelik & Bhatt (2015) Nat Protoc 10: 890-899 — Directional persistence
5. Jonkman et al. (2014) Cell Adhes Migr 8(5): 440-451 — Wound healing assay guide

- TrackMate docs: https://imagej.net/plugins/trackmate/
- MRI Wound Healing: https://github.com/MontpellierRessourcesImagerie/imagej_macros_and_scripts
- Chemotaxis Tool: https://ibidi.com/chemotaxis-analysis/171-chemotaxis-and-migration-tool.html
