# Organoid & Spheroid Image Analysis Reference

For an AI agent controlling Fiji/ImageJ via TCP. Covers brightfield/fluorescence
segmentation, morphometry, lumen detection, budding/branching, 3D analysis,
growth curves, multi-well plates, drug response, and statistics.

---

## 1. Quick Start

```bash
python ij.py macro '
  open("/path/to/organoid.tif");
  run("8-bit");
  run("Subtract Background...", "rolling=50 light");
  run("Auto Threshold", "method=Otsu");
  run("Fill Holes");
  run("Open");
  run("Watershed");
  run("Set Measurements...", "area perimeter shape feret display redirect=None decimal=3");
  run("Analyze Particles...", "size=500-Infinity circularity=0.10-1.00 show=Outlines display exclude clear summarize");
'
python ij.py capture after_segment
python ij.py results
```

### Key Measurements

| Measurement | ImageJ Name | Interpretation |
|-------------|-------------|----------------|
| Area | Area | Cross-sectional size / growth |
| Circularity | Circ. | 4pi*area/perim^2; 1.0 = circle |
| Solidity | Solidity | area / convex hull; detects budding |
| Feret diameter | Feret | Maximum caliper diameter |
| Aspect ratio | AR | Major/minor axis; elongation |

---

## 2. Structure Types Comparison

| Feature | Spheroid | Organoid | Tumoroid | Embryoid Body |
|---------|----------|----------|----------|---------------|
| Shape | Round | Irregular | Variable | Round then irregular |
| Circularity | 0.8-1.0 | 0.3-0.9 | 0.2-0.9 | 0.6-1.0 |
| Lumens | Rare | Common | Variable | Sometimes |
| Budding | No | Yes (type-dependent) | Variable | Rare |
| Size range | 100-800 um | 50-2000 um | 50-500 um | 100-500 um |
| Main readout | Size, viability | Morphology, function | Drug response | Size uniformity |
| Threshold | Otsu typically works | Adaptive often needed | Adaptive/Weka | Otsu typically works |
| Typical n | 10-50/well | 5-50/well | 5-30/well | 50-200/well |

**Key distinction:** Organoids are morphologically heterogeneous. Branching/budding
indicates proper differentiation, not defects.

---

## 3. Brightfield Segmentation

### 3.1 Decision Tree

```
Is the background uniform?
+-- YES: Good contrast? --> Global Otsu (Method 1)
|                   NO? --> Variance filter (Method 6) or Edge detection (Method 4)
+-- NO: Gradual variation? --> Local adaptive threshold (Method 2)
        Patchy/complex?
        +-- Phase halo? --> Halo removal + threshold (Section 3.7)
        +-- Otherwise  --> Weka ML (Method 5)

Touching organoids? --> Multi-window adaptive + watershed (Method 3)
High-throughput (100+ images)? --> Train Weka once, batch-apply (Method 5)
```

### 3.2 Method 1: Global Threshold (spheroids with uniform background)

```javascript
run("8-bit");
run("Subtract Background...", "rolling=50 light");
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Fill Holes");
run("Analyze Particles...", "size=500-Infinity show=Masks");
```

**Choosing threshold method:**

| Method | Best for |
|--------|----------|
| Otsu | Bimodal histogram (clear object/background) |
| Triangle | Skewed histogram (organoids = small fraction of image) |
| Li | Low-contrast objects (organoids in Matrigel) |
| Huang | Gradual edges (phase contrast) |
| MinError | Clean brightfield with Gaussian distributions |
| RenyiEntropy | Complex histograms where Otsu fails |

### 3.3 Method 2: Local Adaptive Threshold (uneven illumination)

```javascript
run("8-bit");
run("Subtract Background...", "rolling=100 light");
run("Duplicate...", "title=local");
run("Median...", "radius=3");
run("Duplicate...", "title=local_mean");
run("Mean...", "radius=50");
imageCalculator("Subtract create", "local", "local_mean");
rename("local_subtracted");
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Fill Holes");
run("Open");
run("Close-");
run("Analyze Particles...", "size=1000-Infinity show=Masks");
rename("organoid_mask");
```

### 3.4 Method 3: Multi-Window Adaptive (OrganoSeg-style)

Apply local threshold at multiple scales, combine with OR. Captures fine edges
and full extent simultaneously.

```javascript
original = getTitle();
run("8-bit");
run("Subtract Background...", "rolling=100 light");
run("Median...", "radius=2");
// Small window (fine edges), medium (main body), large (full extent)
// Choose radii based on organoid size: ~25%, ~75%, ~150% of typical diameter
sizes = newArray(25, 75, 150);
for (s = 0; s < sizes.length; s++) {
    selectWindow(original);
    run("Duplicate...", "title=w" + s);
    run("Auto Local Threshold", "method=Bernsen radius=" + sizes[s] + " parameter_1=15 parameter_2=0 white");
}
imageCalculator("OR create", "w0", "w1");
rename("w01");
imageCalculator("OR create", "w01", "w2");
rename("combined_mask");
run("Fill Holes");
run("Open");
run("Analyze Particles...", "size=2000-Infinity show=Masks");
rename("final_mask");
```

**Auto Local Threshold methods:**

| Method | Good for |
|--------|----------|
| Bernsen | Good local contrast (brightfield) |
| Phansalkar | Low contrast (organoids in Matrigel) -- often best choice |
| Sauvola | Cell images, variable illumination |
| Niblack | Variable illumination |
| Mean/Median | Simple, fast baseline |

### 3.5 Method 4: Edge Detection

```javascript
run("8-bit");
run("Subtract Background...", "rolling=100 light");
run("Gaussian Blur...", "sigma=2");
run("Duplicate...", "title=edges");
run("Find Edges");
setAutoThreshold("Triangle dark");
run("Convert to Mask");
run("Dilate"); run("Dilate");
run("Fill Holes");
run("Erode"); run("Erode");
run("Analyze Particles...", "size=1000-Infinity show=Masks");
```

### 3.6 Method 5: Weka ML (most accurate, difficult images)

```javascript
// Train interactively, then batch-apply saved classifier:
open("/path/to/image.tif");
run("Trainable Weka Segmentation");
call("trainableSegmentation.Weka_Segmentation.loadClassifier",
     "/path/to/saved_classifier.model");
call("trainableSegmentation.Weka_Segmentation.applyClassifier",
     getDirectory("imagej"), "classify", "showResults=true",
     "storeResults=false", "probabilityMaps=false", "v3d=false");
run("Duplicate...", "title=organoid_class use");
setThreshold(0, 0);
run("Convert to Mask");
run("Fill Holes");
run("Analyze Particles...", "size=1000-Infinity show=Masks");
```

### 3.7 Method 6: Variance Filter

Detects textured regions (organoids) vs smooth background.

```javascript
run("8-bit");
run("Subtract Background...", "rolling=100 light");
run("Duplicate...", "title=variance_img");
run("Variance...", "radius=5");
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Close-"); run("Close-");
run("Fill Holes"); run("Open");
run("Analyze Particles...", "size=2000-Infinity show=Masks");
```

### 3.8 Phase Contrast Halo Removal

```javascript
run("8-bit");
run("Invert");
run("Median...", "radius=5");  // merge halo with object
run("Subtract Background...", "rolling=80 light");
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Fill Holes");
run("Close-"); run("Close-"); run("Close-");
run("Analyze Particles...", "size=1000-Infinity show=Masks");
```

---

## 4. Fluorescence Analysis

### 4.1 Live/Dead Viability

```javascript
// Input: 2-channel (C1=calcein/live, C2=PI/dead)
title = getTitle();
run("Split Channels");

// Segment + measure live channel
selectWindow("C1-" + title); rename("live");
run("Duplicate...", "title=live_mask");
run("Gaussian Blur...", "sigma=2");
setAutoThreshold("Otsu dark");
run("Convert to Mask"); run("Fill Holes"); run("Open");

// Segment + measure dead channel
selectWindow("C2-" + title); rename("dead");
run("Duplicate...", "title=dead_mask");
run("Gaussian Blur...", "sigma=2");
setAutoThreshold("Otsu dark");
run("Convert to Mask"); run("Fill Holes"); run("Open");

// Combined organoid mask
imageCalculator("OR create", "live_mask", "dead_mask");
rename("organoid_mask");
run("Analyze Particles...", "size=500-Infinity show=Masks");
rename("organoid_cleaned");

// Measure live IntDen within organoid ROI
run("Set Measurements...", "area mean integrated limit display redirect=live decimal=3");
selectWindow("organoid_cleaned");
run("Create Selection");
selectWindow("live"); run("Restore Selection"); run("Measure");
live_intden = getResult("IntDen", nResults-1);

// Measure dead IntDen
run("Set Measurements...", "area mean integrated limit display redirect=dead decimal=3");
selectWindow("organoid_cleaned");
run("Create Selection");
selectWindow("dead"); run("Restore Selection"); run("Measure");
dead_intden = getResult("IntDen", nResults-1);

viability = live_intden / (live_intden + dead_intden) * 100;
print("Viability: " + d2s(viability, 1) + "%");
```

### 4.2 Nuclear Counting

```javascript
// Option A: Threshold + watershed
run("Gaussian Blur...", "sigma=1");
run("Duplicate...", "title=nuclei_mask");
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Watershed");
run("Set Measurements...", "area centroid shape display redirect=None decimal=3");
run("Analyze Particles...", "size=20-500 circularity=0.40-1.00 display exclude clear summarize");
print("Nuclei counted: " + nResults);

// Option B: StarDist (more accurate for touching nuclei)
// run("Command From Macro", "command=[de.csbdresden.stardist.StarDist2D], args=['input':'" + getTitle() + "', 'modelChoice':'Versatile (fluorescent nuclei)', 'normalizeInput':'true', 'percentileBottom':'1.0', 'percentileTop':'99.8', 'probThresh':'0.5', 'nmsThresh':'0.4', 'outputType':'Both', 'nTiles':'1', 'excludeBoundary':'2', 'roiPosition':'Automatic', 'verbose':'false', 'showCsbDeepProgress':'false', 'showProbAndDist':'false'], process=[false]");
```

### 4.3 Z-Stack Projections

| Projection | Use for | Syntax |
|-----------|---------|--------|
| Max Intensity | Morphometry (sharp edges) | `run("Z Project...", "projection=[Max Intensity]");` |
| Sum Slices | Total fluorescence quantification | `run("Z Project...", "projection=[Sum Slices]");` |
| Extended DoF | Brightfield sharpness from z-stack | `run("Extended Depth of Field", "quality='1' topology='0' show-topology='off' show-view='on'");` |

---

## 5. Morphometric Measurements

### 5.1 Standard Shape Descriptors

```javascript
run("Set Measurements...", "area perimeter shape feret fit display redirect=None decimal=3");
```

| Measurement | ImageJ Name | Formula | Range |
|-------------|-------------|---------|-------|
| Area | Area | Pixels * pixel_size^2 | >0 |
| Perimeter | Perim. | Boundary length | >0 |
| Circularity | Circ. | 4*pi*Area/Perim^2 | 0-1 (1=circle) |
| Solidity | Solidity | Area / ConvexHullArea | 0-1 (1=no concavities) |
| Roundness | Round | 4*Area/(pi*Major^2) | 0-1 |
| Aspect Ratio | AR | Major/Minor axis | >=1 (1=round) |
| Feret | Feret | Max caliper distance | >0 |

### 5.2 Derived Measurements

```javascript
for (i = 0; i < nResults; i++) {
    area = getResult("Area", i);
    perim = getResult("Perim.", i);
    feret = getResult("Feret", i);
    minFeret = getResult("MinFeret", i);
    solid = getResult("Solidity", i);

    eqDiam = 2 * sqrt(area / PI);
    setResult("EqDiam", i, eqDiam);
    setResult("BuddingIdx", i, 1.0 - solid);
    setResult("FeretRatio", i, minFeret / feret);
    setResult("Roughness", i, perim / (PI * eqDiam));

    // Volume estimate (sphere assumption -- overestimates for flat/irregular)
    r = sqrt(area / PI);
    setResult("VolEst", i, (4.0/3.0) * PI * r * r * r);
}
updateResults();
```

### 5.3 Calibration Check

```javascript
getPixelSize(unit, pw, ph);
if (unit == "pixels" || unit == "pixel") {
    print("WARNING: Not calibrated! Set with: run('Properties...', 'pixel_width=X pixel_height=X unit=um');");
} else {
    print("Calibrated: " + d2s(pw, 4) + " " + unit + "/pixel");
}
```

---

## 6. Lumen Detection

### 6.1 Phase Contrast / Brightfield

Lumens appear as bright regions within darker organoid body.

```javascript
original = getTitle();
run("8-bit");
run("Subtract Background...", "rolling=100 light");

// Segment organoid boundary
run("Duplicate...", "title=org_seg");
run("Gaussian Blur...", "sigma=3");
setAutoThreshold("Li dark");
run("Convert to Mask"); run("Fill Holes"); run("Open");
run("Analyze Particles...", "size=2000-Infinity show=Masks");
rename("organoid_mask");

// Detect bright lumen within organoid
selectWindow(original);
run("Duplicate...", "title=lumen_detect");
run("Gaussian Blur...", "sigma=2");
setAutoThreshold("Triangle");
run("Convert to Mask");
run("Invert");
imageCalculator("AND create", "lumen_detect", "organoid_mask");
rename("lumen_within");
run("Open"); run("Open");
run("Analyze Particles...", "size=200-Infinity show=Masks");
rename("lumen_mask");

// Measure lumen-to-organoid ratio
run("Set Measurements...", "area perimeter shape feret display redirect=None decimal=3");
selectWindow("lumen_mask");
run("Analyze Particles...", "size=200-Infinity display clear");
n_lumens = nResults;
selectWindow("organoid_mask");
run("Create Selection"); getStatistics(org_area);
lumen_total = 0;
for (i = 0; i < n_lumens; i++) lumen_total += getResult("Area", i);
print("Lumens: " + n_lumens + ", Ratio: " + d2s(lumen_total/org_area, 3));
```

### 6.2 Fluorescence (Nuclear Stain)

Lumens = dark voids enclosed by bright nuclei. Segment organoid (fill holes),
segment nuclear signal, subtract nuclei from filled organoid to find voids.

### 6.3 Lumen Classification

| Phenotype | Lumens | Lumen/Organoid Ratio | Notes |
|-----------|--------|---------------------|-------|
| No lumen | 0 | 0 | Dense, undifferentiated |
| Single lumen | 1 | 0.1-0.6 | Properly polarised cyst |
| Multi-lumen | >1 | 0.1-0.4 | Multiple cavities |
| Cystic | 1 | >0.6 | Over-expanded |
| Collapsed | 0-1 | <0.05 | Lumen lost |

---

## 7. Budding and Branching

### 7.1 Convex Hull Budding Index

```javascript
// Input: binary mask of single organoid
run("Set Measurements...", "area perimeter shape feret display redirect=None decimal=3");
run("Create Selection");
run("Measure");
actual_solidity = getResult("Solidity", nResults-1);
budding_index = 1.0 - actual_solidity;
run("Convex Hull");
getStatistics(hull_area);
run("Measure");
hull_perim = getResult("Perim.", nResults-1);
print("Budding index: " + d2s(budding_index, 3));
```

### 7.2 Skeleton-Based Branching

```javascript
run("Duplicate...", "title=skel_input");
run("Options...", "iterations=2 count=1 do=Open");
run("Fill Holes");
run("Skeletonize");
run("Analyze Skeleton (2D/3D)", "prune=[shortest branch] show");
// Results: # Branches, # Junctions, # End-points, Avg Branch Length
```

### 7.3 Protrusion Detection

Subtract a heavily-opened (smoothed) version from the original mask to isolate
protrusions. Tune opening iterations based on protrusion thickness: start with
~10 iterations and adjust.

```javascript
run("Duplicate...", "title=smoothed");
run("Options...", "iterations=10 count=1 do=Open");
run("Options...", "iterations=8 count=1 do=Dilate");
run("Fill Holes");
rename("core_body");
imageCalculator("Subtract create", "organoid_mask", "core_body");
rename("protrusions");
run("Analyze Particles...", "size=100-Infinity display clear");
```

### 7.4 Branching Complexity Metrics

| Metric | Low = | High = |
|--------|-------|--------|
| Solidity | No budding (>0.95) | Extensive budding (<0.7) |
| Branch count | Simple (1-3) | Complex (>10) |
| Junction count | No branching (0) | Highly branched (>5) |
| End-point count | Linear (1-2) | Star/tree (many) |
| Protrusion fraction | Smooth (0) | Significant (>0.2) |
| Irregularity | Circle (1.0) | Irregular (>1.5) |

---

## 8. 3D Analysis

Use when: confocal/light-sheet z-stacks, accurate volume needed, organoids
overlap in 2D, or internal 3D structure matters.

### 8.1 3D Objects Counter

```javascript
run("Gaussian Blur 3D...", "x=2 y=2 z=1");
setAutoThreshold("Otsu dark stack");
run("Convert to Mask", "method=Otsu background=Dark calculate");
run("3D Objects Counter", "threshold=128 slice=1 min.=5000 max.=99999999 objects surfaces statistics");
// Results: Volume, Surface area, Mean intensity, Centroid, Bounding box
```

### 8.2 MorphoLibJ 3D

```javascript
run("Connected Components Labeling", "connectivity=26 type=[16 bits]");
run("Analyze Regions 3D", "voxel_count volume surface_area mean_breadth sphericity euler_number bounding_box centroid inertia_ellipsoid ellipsoid_elongations max._inscribed surface_area_method=[Crofton (13 dirs.)] euler_connectivity=26");
// Sphericity = (36*pi*V^2)^(1/3) / S  (1 = perfect sphere)
```

### 8.3 Sphericity Interpretation

| Sphericity | Interpretation |
|-----------|----------------|
| >0.9 | Nearly spherical (typical spheroid) |
| 0.7-0.9 | Somewhat irregular (early organoid) |
| 0.5-0.7 | Irregular (budding organoid) |
| <0.5 | Highly irregular (branched) |

### 8.4 3D ImageJ Suite (mcib3d)

```javascript
// 3D iterative thresholding (better for touching organoids)
run("3D Iterative Thresholding", "min_vol_pix=5000 max_vol_pix=9999999 min_threshold=0 step_threshold=10 criteria_method=[Volume Increase] threshold_method=Step");
// 3D watershed
run("3D Watershed Split", "seeds_threshold=128 image_threshold=0 radius=2");
// 3D Manager for measurements: volume, surface, sphericity, Feret, convex hull
run("3D Manager");
```

### 8.5 Memory Tips for Large Z-Stacks

```javascript
print("Free memory: " + IJ.freeMemory());
makeRectangle(x, y, width, height); run("Crop");  // crop ROI first
run("Scale...", "x=0.5 y=0.5 z=1.0 interpolation=Bilinear process create");  // downsample if needed
run("Collect Garbage");
```

---

## 9. Growth Curves

### 9.1 Batch Timepoint Measurement

```javascript
inputDir = "/path/to/time_series/";
list = getFileList(inputDir);
Array.sort(list);
run("Set Measurements...", "area perimeter shape feret display redirect=None decimal=3");
setBatchMode(true);
for (f = 0; f < list.length; f++) {
    if (endsWith(list[f], ".tif")) {
        open(inputDir + list[f]);
        run("8-bit");
        run("Subtract Background...", "rolling=50 light");
        setAutoThreshold("Otsu dark");
        run("Convert to Mask"); run("Fill Holes"); run("Open");
        n_before = nResults;
        run("Analyze Particles...", "size=500-Infinity display exclude");
        for (i = n_before; i < nResults; i++) {
            setResult("Timepoint", i, f);
            setResult("File", i, list[f]);
        }
        updateResults();
        close(); close();
    }
}
setBatchMode(false);
saveAs("Results", inputDir + "growth_data.csv");
```

### 9.2 Python Growth Curve Fitting

```python
import numpy as np
from scipy.optimize import curve_fit

def exponential_growth(t, A0, k):
    return A0 * np.exp(k * t)

def logistic_growth(t, A0, K, k):
    return K / (1.0 + ((K - A0) / A0) * np.exp(-k * t))

def gompertz_growth(t, A0, K, k):
    return K * np.exp(np.log(A0 / K) * np.exp(-k * t))

# Choose model based on growth phase:
#   exponential: early unrestricted growth
#   logistic: growth with carrying capacity (plateau)
#   gompertz: asymmetric sigmoid (slow start, fast middle, slow end)
# Fit: popt, pcov = curve_fit(model_fn, timepoints, areas, p0=[...])
# Doubling time = ln(2) / k
# R^2 = 1 - SS_res/SS_tot
```

### 9.3 Tracking Gotchas

- **Splitting** (area drops >30%): flag event, optionally sum daughter areas
- **Merging** (area increases >100%): flag, exclude from growth rate
- **Loss**: track by nearest centroid matching between timepoints

---

## 10. Multi-Well Plate Workflows

### 10.1 Batch Plate Processing

```javascript
inputDir = "/path/to/plate_images/";
outputDir = "/path/to/plate_results/";
File.makeDirectory(outputDir);
list = getFileList(inputDir);
Array.sort(list);
run("Set Measurements...", "area perimeter shape feret display redirect=None decimal=3");
setBatchMode(true);
for (f = 0; f < list.length; f++) {
    if (endsWith(list[f], ".tif") || endsWith(list[f], ".png")) {
        open(inputDir + list[f]);
        run("8-bit");
        run("Subtract Background...", "rolling=50 light");
        setAutoThreshold("Otsu dark");
        run("Convert to Mask"); run("Fill Holes"); run("Open");
        n_before = nResults;
        run("Analyze Particles...", "size=500-Infinity circularity=0.10-1.00 display exclude");
        for (i = n_before; i < nResults; i++) {
            setResult("Well", i, replace(list[f], ".tif", ""));
        }
        updateResults();
        close(); close();
    }
}
setBatchMode(false);
saveAs("Results", outputDir + "plate_all_organoids.csv");
```

### 10.2 Plate Heatmap

```javascript
// One pixel per well, scale up for visibility
newImage("Plate_Heatmap", "32-bit black", 12, 8, 1);
// Fill wells: setPixel(col_num, row_num, value);
run("Scale...", "x=50 y=50 interpolation=None create title=Plate_Heatmap_Large");
run("Fire");
run("Calibration Bar...", "location=[Upper Right] fill=White label=Black number=5 decimal=0 font=12 zoom=1");
```

---

## 11. Drug Response

### 11.1 IC50 from Area (Python)

```python
import numpy as np
from scipy.optimize import curve_fit

def four_parameter_logistic(x, bottom, top, ic50, hill):
    """4PL: y = bottom + (top - bottom) / (1 + (x/ic50)^hill)"""
    return bottom + (top - bottom) / (1.0 + (x / ic50) ** hill)

# Normalize to control = 100%, fit 4PL to non-zero doses
# p0 = [0, 100, median_dose, 1.0]
# bounds = ([0, 50, min_dose/10, 0.1], [50, 150, max_dose*10, 10])
# AUC: np.trapz(responses, np.log10(doses)) -- lower AUC = more sensitive
```

### 11.2 Combined Score

```
composite_score = (area_treated / area_control) * (viability_treated / viability_control)
# 0 = complete response, 1 = no effect
# Captures both cytostatic (growth) and cytotoxic (death) effects
```

---

## 12. Invasion Assays

### 12.1 Spheroid Invasion Quantification

```javascript
original = getTitle();
run("8-bit");
run("Subtract Background...", "rolling=100 light");

// Total extent (core + invasion): sensitive threshold
run("Duplicate...", "title=total");
run("Gaussian Blur...", "sigma=3");
setAutoThreshold("Li dark");
run("Convert to Mask"); run("Fill Holes"); run("Close-");
run("Analyze Particles...", "size=1000-Infinity show=Masks");
rename("total_mask");

// Core only: aggressive threshold + erosion/dilation
selectWindow(original);
run("Duplicate...", "title=core");
run("Gaussian Blur...", "sigma=5");
setAutoThreshold("Otsu dark");
run("Convert to Mask"); run("Fill Holes");
run("Options...", "iterations=5 count=1 do=Erode");
run("Options...", "iterations=5 count=1 do=Dilate");
run("Analyze Particles...", "size=5000-Infinity show=Masks");
rename("core_mask");

// Invasion = total - core
imageCalculator("Subtract create", "total_mask", "core_mask");
rename("invasion_zone");

// Measure areas
selectWindow("total_mask"); run("Create Selection"); getStatistics(total_area);
selectWindow("core_mask"); run("Create Selection"); getStatistics(core_area);
invasion_index = total_area / core_area;
print("Invasion index: " + d2s(invasion_index, 3));
```

### 12.2 INSIDIA Metrics

| Parameter | Description |
|-----------|-------------|
| Core Area | Compact spheroid body |
| Total Area | Core + invaded cells |
| Max Invasion Radius | Farthest cell from core centre |
| Mean Invasion Radius | Average distance from core |
| Invasion Index | Total / Core area |

---

## 13. Plugins and Tools

### 13.1 Comparison Matrix

| Feature | ImageJ Macros | OrganoSeg | SpheroidJ | INSIDIA | MOrgAna | Cellos | Weka |
|---------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| Brightfield seg | Y | Y | Y | Y | Y | N | Y |
| Fluorescence | Y | N | N | Y | Y | Y | Y |
| 3D analysis | Y* | N | N | N | N | Y | N |
| Invasion | Y* | N | N | Y | N | N | N |
| ML-based | N | N | Y* | N | Y | Y | Y |
| Runs in Fiji | Y | N | Y* | Y | N | N | Y |
| Lumen detection | Y* | N | N | N | Y | N | N |
| Protrusion analysis | Y* | N | N | N | Y | N | N |

Y* = with custom code. **Recommendation:** Use ImageJ macros as primary approach
(works via TCP, fully automatable). Mention external tools when users need
capabilities beyond macros.

### 13.2 Tool Summary

| Tool | Type | Best for | Limitation |
|------|------|----------|------------|
| OrganoSeg | Standalone (MATLAB) | Brightfield morphometry, variable illumination | Not callable from macros |
| SpheroidJ | Fiji plugin + Python | Spheroid seg when thresholding fails | DL model needs Python |
| INSIDIA | Fiji macro | Invasion assays (>15 metrics) | Invasion-specific only |
| MOrgAna | Python | Protrusion/bud quantification (LOCO-EFA) | Python-only |
| OrganoID | Python | Time-lapse tracking of individual organoids | Python, needs GPU |
| Cellos | Python | Cell-level 3D analysis within organoids | Needs confocal z-stacks |
| AnaSP | Standalone | Quick spheroid morphometry from brightfield | Cannot integrate with Fiji |
| Lusca | Fiji plugin | Comprehensive morphometry + branching | -- |

---

## 14. Common Problems and Fixes

| Problem | Fix | Macro |
|---------|-----|-------|
| Debris as small organoids | Size filter | `size=500-Infinity` |
| Edge-touching objects | Exclude | `exclude` in Analyze Particles |
| Phase contrast halos | Large median | `run("Median...", "radius=5")` |
| Uneven illumination | Rolling ball | `run("Subtract Background...", "rolling=100 light")` |
| Touching organoids | Watershed | `run("Watershed")` |
| Low contrast | Variance filter | `run("Variance...", "radius=5")` then threshold |
| Blurry objects | Unsharp mask | `run("Unsharp Mask...", "radius=5 mask=0.6")` |
| Matrigel dome edges | Circularity filter | `circularity=0.20-1.00` |

### Choosing Minimum Size Filter

Calculate from pixel calibration: `min_area_px = PI * ((min_diam_um / 2) / pixel_size)^2`

| Objective | ~Pixel Size | Min size for 50 um organoid |
|-----------|------------|---------------------------|
| 2x | ~3.25 um/px | ~200 px |
| 4x | ~1.625 um/px | ~750 px |
| 10x | ~0.65 um/px | ~4,600 px |
| 20x | ~0.325 um/px | ~18,600 px |

### Separating Touching Organoids

```javascript
// Standard watershed (round objects)
run("Watershed");

// MorphoLibJ marker-controlled watershed (irregular objects)
run("Chamfer Distance Map", "distances=[Borgefors (3,4)] output=[16 bits] normalize");
run("Extended Maxima", "dynamic=5 connectivity=4 output=[Maxima]");
rename("seeds");
run("Marker-controlled Watershed", "input=original_mask marker=seeds mask=original_mask compactness=0 binary calculate");
```

### Troubleshooting Decision Tree

```
Threshold result wrong:
+-- Too much background? --> Subtract Background first, try Triangle/Li
+-- Organoids incomplete? --> Local Adaptive (Phansalkar), Fill Holes
+-- Too many small objects? --> Increase min size, add Median filter
+-- Touching merged? --> Watershed (round) or MorphoLibJ (irregular) or Weka
```

---

## 15. Statistics

### 15.1 Experimental Units (Critical)

| Design | n = | NOT n = |
|--------|-----|---------|
| Independent experiments | # experiments | # organoids |
| Multi-well, 1 org/well | # wells | -- |
| Multi-well, many org/well | # wells | # organoids (shared media) |
| Patient-derived | # patients | # organoids |

### 15.2 Analytical Approaches

**Per-well summary (simplest):** Compute mean per well, use well means for tests.

```python
well_summary = df.groupby(['Well', 'Condition']).agg(mean_area=('Area', 'mean')).reset_index()
from scipy import stats
stats.ttest_ind(control_wells['mean_area'], treated_wells['mean_area'])
```

**Mixed-effects model (accounts for nesting, more powerful):**

```python
import statsmodels.formula.api as smf
model = smf.mixedlm("Area ~ Condition", df, groups=df["Well"])
result = model.fit()
```

### 15.3 Test Selection

| Comparison | Normal | Non-Normal | Notes |
|-----------|--------|-----------|-------|
| 2 groups | t-test | Mann-Whitney | On per-well means |
| 2 groups paired | Paired t-test | Wilcoxon | Same organoids before/after |
| >2 groups | ANOVA | Kruskal-Wallis | + post-hoc |
| 2 factors | Two-way ANOVA | -- | Drug x concentration |
| Dose-response | 4PL regression | -- | IC50 |
| Nested data | Mixed-effects | -- | Organoids within wells |

### 15.4 Sample Size Guidelines

- Descriptive: n >= 3 biological replicates
- Comparative: n >= 3, consider n >= 5 per condition
- Dose-response: >= 3 replicates/dose, >= 7 doses
- Per-well: consider >= 20 organoids/well for stable means

---

## 16. Reporting Standards

### Methods Checklist

- [ ] n = biological replicates (not total organoids)
- [ ] Total organoids measured per replicate
- [ ] Central tendency + spread (mean/SD or median/IQR)
- [ ] Statistical test and justification
- [ ] How nesting was handled
- [ ] Exact p-values
- [ ] Size/shape filter criteria
- [ ] Software version and segmentation method
- [ ] Representative images with scale bars
- [ ] Individual data points shown

### Example Methods Paragraph

"Brightfield images were acquired with [microscope], [objective] (pixel size:
X um). Analysis used Fiji (v2.14.0). Background subtracted (rolling ball: 50 px),
Otsu threshold, hole-filling, morphological opening. Objects <[min] um^2 or
touching edges excluded. Measurements: area, circularity, solidity, Feret
diameter. Size reported as equivalent diameter. Statistics: [per-well means /
mixed-effects model], n = [biological replicates]."

---

## Appendix: Measurement Interpretation

### Circularity by Organoid Type

| Type | Expected Circ. |
|------|---------------|
| Spheroid | 0.85-1.00 |
| Intestinal crypt | 0.30-0.70 |
| Pancreatic (cystic) | 0.70-0.95 |
| Brain (early/mature) | 0.70-0.90 / 0.40-0.80 |
| Lung (branching) | 0.20-0.60 |
| Kidney (tubular) | 0.30-0.70 |

### Solidity as Budding Indicator

| Solidity | Interpretation |
|----------|---------------|
| >0.95 | No budding (spheroid, early organoid) |
| 0.85-0.95 | Minimal budding |
| 0.70-0.85 | Moderate budding |
| <0.70 | Extensive budding/branching |

### Growth Rate (Day 7 / Day 0 Fold Change)

| Fold Change | Interpretation |
|-------------|---------------|
| <0.5 | Shrinking (death/drug response) |
| 1.0-2.0 | Slow growth |
| 2.0-5.0 | Moderate (typical) |
| 5.0-20.0 | Rapid |
| >20.0 | Very rapid (verify accuracy) |

### Viability Score

| Viability (%) | Interpretation |
|---------------|---------------|
| >90 | Healthy |
| 70-90 | Moderate |
| 50-70 | Significant death |
| <30 | Severe loss |
