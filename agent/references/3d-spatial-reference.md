# 3D Spatial Analysis Reference for Claude Agent

Comprehensive reference for 3D object analysis, spatial statistics, colocalization,
morphometry, distance analysis, and skeleton/network analysis in ImageJ/Fiji.

Every `run()` command below works with: `python ij.py macro 'COMMAND_HERE'`

For multi-line macros with Ext. functions, wrap the entire block in a single string.

---

## Table of Contents

1. [3D Objects Counter](#1-3d-objects-counter)
2. [3D ImageJ Suite — Overview](#2-3d-imagej-suite)
3. [3D Manager (Ext.Manager3D)](#3-3d-manager)
4. [3D ImageJ Suite — Segmentation](#4-3d-segmentation)
5. [3D ImageJ Suite — Filters](#5-3d-filters)
6. [3D ImageJ Suite — Analysis & Measurements](#6-3d-analysis--measurements)
7. [3D ImageJ Suite — Distances & Relationships](#7-3d-distances--relationships)
8. [3D ImageJ Suite — Spatial Statistics](#8-3d-spatial-statistics)
9. [3D ImageJ Suite — Colocalization](#9-3d-colocalization)
10. [3D ImageJ Suite — Binary Operations](#10-3d-binary-operations)
11. [MorphoLibJ — 3D Operations](#11-morpholibj-3d)
12. [CLIJ2 — GPU-Accelerated 3D](#12-clij2-gpu-accelerated-3d)
13. [Coloc 2 — Pixel-Based Colocalization](#13-coloc-2)
14. [DiAna — Object-Based 3D Colocalization](#14-diana)
15. [AnalyzeSkeleton — 3D Network Analysis](#15-analyzeskeleton)
16. [SNT / Sholl Analysis — 3D Neurite Analysis](#16-snt--sholl-analysis)
17. [Spatial Statistics Plugins](#17-spatial-statistics-plugins)
18. [Recipes — Common 3D Workflows](#18-recipes)

---

## 1. 3D Objects Counter

**Plugin:** `Plugins > 3D Objects Counter`
**Docs:** https://imagej.net/plugins/3d-objects-counter

Counts and measures 3D objects in a stack. Integrated into Fiji by default.

### Set Measurements

```javascript
run("3D OC Options", "volume surface nb_of_obj._voxels "
  + "integrated_density mean_gray_value std_dev_gray_value "
  + "median_gray_value minimum_gray_value maximum_gray_value "
  + "centroid mean_distance_to_surface std_dev_distance_to_surface "
  + "median_distance_to_surface centre_of_mass bounding_box "
  + "dots_size=5 font_size=10 redirect_to=none");
```

### Run Counter

```javascript
run("3D Objects Counter", "threshold=128 slice=25 "
  + "min.=100 max.=9999999 "
  + "objects surfaces centroids "
  + "centres_of_masses statistics summary");
```

### Parameters

| Parameter | Description |
|-----------|-------------|
| `threshold=N` | Intensity threshold for object detection |
| `slice=N` | Display slice for maps |
| `min.=N` | Minimum object size in voxels |
| `max.=N` | Maximum object size in voxels |
| `objects` | Generate labeled objects map |
| `surfaces` | Generate surface voxels map |
| `centroids` | Generate centroids map |
| `centres_of_masses` | Generate centre of mass map |
| `statistics` | Output per-object statistics table |
| `summary` | Output summary table |
| `redirect_to=TITLE` | Measure intensity on a different image |

### Output Columns (Statistics Table)

| Column | Description |
|--------|-------------|
| Volume (px^3) | Object volume in voxels |
| Volume (unit) | Calibrated volume |
| Surface (px^2) | Surface area in voxels |
| Surface (unit) | Calibrated surface area |
| Nb of Obj. Voxels | Total voxels in object |
| IntDen | Integrated density (sum of pixel values) |
| Mean | Mean gray value |
| StdDev | Standard deviation of gray values |
| Median | Median gray value |
| Min | Minimum gray value |
| Max | Maximum gray value |
| X, Y, Z (centroid) | Geometric center coordinates |
| Mean dist to surf. | Mean distance from centroid to surface |
| SD dist to surf. | StdDev of distance to surface |
| Median dist to surf. | Median distance to surface |
| XM, YM, ZM | Centre of mass (intensity-weighted centroid) |
| BX, BY, BZ | Bounding box origin |
| B-width, B-height, B-depth | Bounding box dimensions |

---

## 2. 3D ImageJ Suite

**Author:** Thomas Boudier (Sorbonne University)
**Update Site:** `3D ImageJ Suite`
**Docs:** https://mcib3d.frama.io/3d-suite-imagej/
**Paper:** Ollion et al., TANGO (2013)

A comprehensive suite of 48+ plugins for 3D image analysis. All plugins work with
binary or labeled images (16-bit or 32-bit label maps).

### Complete Plugin List by Category

**3D Manager** (interactive ROI management):
- 3D Manager, 3D Manager macros, 3D Manager options

**Analysis — Geometry:**
- 3D Centroid, 3D DistanceContour, 3D Feret, 3D Mesh, 3D Volume Surface

**Analysis — Intensity:**
- 3D Intensity

**Analysis — Relationships:**
- 3D Distances, 3D Interactions, 3D Mereotopology, 3D MultiColoc, 3D Numbering

**Analysis — Shape:**
- 3D Ellipsoid, 3D RDAR, 3D Shape

**Analysis — Spatial:**
- 3D Layers, 3D Radial, 3D SpatialStatistics

**Binary Operations:**
- 3D Binary Interpolate, 3D Close Labels, 3D ConvexHull, 3D Density Filter
- 3D Distance Map EVF, 3D Exclude Edges, 3D Fill Holes, 3D Merge Labels

**Filters:**
- 3D Edge and Symmetry Filter, 3D Fast Filters, 3D Maxima Finder

**Segmentation:**
- 3D Hysteresis Segmentation, 3D Iterative Segmentation, 3D Simple Segmentation
- 3D Watershed, 3D Nuclei Segmentation (custom), 3D Spots Segmentation (custom)

**Tools:**
- 3D Draw Line, 3D Draw Rois, 3D Draw Shape, 3D Voxelizer

---

## 3. 3D Manager

**Menu:** `Plugins > 3D Manager`
**Docs:** https://mcib3d.frama.io/3d-suite-imagej/plugins/3DManager/3D-Manager-macros/

The 3D Manager is the central tool for managing 3D ROIs and performing measurements.
Uses `Ext.Manager3D_xxx()` macro extension functions.

### Initialization

```javascript
// MUST be called first to enable Ext functions
run("3D Manager");
```

### Object Management

| Function | Description | Example |
|----------|-------------|---------|
| `Ext.Manager3D_Close()` | Close 3D Manager | |
| `Ext.Manager3D_Reset()` | Remove all objects | |
| `Ext.Manager3D_Load(file)` | Load objects from zip | `Ext.Manager3D_Load("objs.zip");` |
| `Ext.Manager3D_Save(file)` | Save selected to zip | `Ext.Manager3D_Save("objs.zip");` |
| `Ext.Manager3D_Segment(lo,hi)` | Threshold and label | `Ext.Manager3D_Segment(128, 255);` |
| `Ext.Manager3D_AddImage()` | Add objects from labeled image | |
| `Ext.Manager3D_Count(n)` | Get object count | `Ext.Manager3D_Count(nb); print(nb);` |

### Selection

| Function | Description | Example |
|----------|-------------|---------|
| `Ext.Manager3D_Select(i)` | Select object i (0-based) | `Ext.Manager3D_Select(0);` |
| `Ext.Manager3D_SelectAll()` | Select all objects | |
| `Ext.Manager3D_SelectFor(s,e,inc)` | Select range | `Ext.Manager3D_SelectFor(10,20,2);` |
| `Ext.Manager3D_MultiSelect()` | Enable multi-select mode | |
| `Ext.Manager3D_MonoSelect()` | Enable single-select mode | |
| `Ext.Manager3D_DeselectAll()` | Deselect all | |
| `Ext.Manager3D_GetSelected(list)` | Get selected indices | `indices=split(list,":");` |

### Naming

| Function | Description | Example |
|----------|-------------|---------|
| `Ext.Manager3D_GetName(i, name)` | Get name of object i | `Ext.Manager3D_GetName(0, name);` |
| `Ext.Manager3D_Rename(name)` | Rename selected object | `Ext.Manager3D_Rename("Nucleus");` |

### Object Operations

| Function | Description | Example |
|----------|-------------|---------|
| `Ext.Manager3D_Delete()` | Remove selected from list | |
| `Ext.Manager3D_Erase()` | Remove + fill black in image | |
| `Ext.Manager3D_FillStack(r,g,b)` | Fill selected in current stack | `Ext.Manager3D_FillStack(255,0,0);` |
| `Ext.Manager3D_Fill3DViewer(r,g,b)` | Draw in 3D Viewer | `Ext.Manager3D_Fill3DViewer(0,255,0);` |
| `Ext.Manager3D_List()` | Get voxel list of selected | |

### Geometrical Measurements (Measure)

```javascript
// Compute all geometrical measurements → Results Table
Ext.Manager3D_SelectAll();
Ext.Manager3D_Measure();
```

**Per-object measurement without Results Table:**

```javascript
Ext.Manager3D_Measure3D(objectIndex, "measureType", result);
```

| Measure Type | Description |
|-------------|-------------|
| `"Vol"` | Volume (calibrated) |
| `"Surf"` | Surface area |
| `"NbVox"` | Number of voxels |
| `"Comp"` | Compactness (36*PI*V^2 / S^3) |
| `"Feret"` | 3D Feret diameter (max caliper) |
| `"Elon1"` | Elongation ratio 1 (major/2nd axis) |
| `"Elon2"` | Elongation ratio 2 (2nd/3rd axis = flatness) |
| `"DCMin"` | Min distance from centroid to surface |
| `"DCMax"` | Max distance from centroid to surface |
| `"DCMean"` | Mean distance from centroid to surface |
| `"DCSD"` | StdDev distance from centroid to surface |
| `"RatioVolEll"` | Volume ratio (object / fitted ellipsoid) |

**Example:**
```javascript
run("3D Manager");
Ext.Manager3D_Segment(128, 255);
Ext.Manager3D_Count(nb);
for (i = 0; i < nb; i++) {
    Ext.Manager3D_Measure3D(i, "Vol", vol);
    Ext.Manager3D_Measure3D(i, "Comp", comp);
    Ext.Manager3D_Measure3D(i, "Feret", feret);
    Ext.Manager3D_Measure3D(i, "Elon1", elon);
    print("Object " + i + ": Vol=" + vol + " Comp=" + comp
      + " Feret=" + feret + " Elon=" + elon);
}
```

### Centroid and Feret Coordinates

```javascript
// Geometric centroid
Ext.Manager3D_Centroid3D(0, cx, cy, cz);
print("Centroid: " + cx + " " + cy + " " + cz);

// Centre of mass (intensity-weighted, from current stack)
Ext.Manager3D_MassCenter3D(0, cmx, cmy, cmz);
print("Mass center: " + cmx + " " + cmy + " " + cmz);

// Feret diameter endpoints
Ext.Manager3D_Feret1(0, fx1, fy1, fz1);
Ext.Manager3D_Feret2(0, fx2, fy2, fz2);

// Bounding box
Ext.Manager3D_Bounding3D(0, x0, x1, y0, y1, z0, z1);
print("BBox: X[" + x0 + "-" + x1 + "] Y[" + y0 + "-" + y1
  + "] Z[" + z0 + "-" + z1 + "]");
```

### Intensity Measurements (Quantif)

```javascript
// Compute intensity measurements → Results Table
Ext.Manager3D_SelectAll();
Ext.Manager3D_Quantif();
```

**Per-object without Results Table:**

```javascript
Ext.Manager3D_Quantif3D(objectIndex, "measureType", result);
```

| Measure Type | Description |
|-------------|-------------|
| `"IntDen"` | Integrated density (sum of intensities) |
| `"Mean"` | Mean intensity |
| `"Min"` | Minimum intensity |
| `"Max"` | Maximum intensity |
| `"Sigma"` | Standard deviation of intensity |

### Distance Measurements

```javascript
// All pairwise distances → Results Table
Ext.Manager3D_SelectAll();
Ext.Manager3D_Distance();
```

**Between two specific objects:**

```javascript
Ext.Manager3D_Dist2(obj1, obj2, "distType", distance);
```

| Distance Type | Description |
|--------------|-------------|
| `"cc"` | Centre-to-centre |
| `"bb"` | Border-to-border (surface-to-surface) |
| `"c1b2"` | Centre of obj1 to border of obj2 |
| `"c2b1"` | Centre of obj2 to border of obj1 |
| `"r1c2"` | Radius of obj1 to centre of obj2 |
| `"r2c1"` | Radius of obj2 to centre of obj1 |
| `"ex1c2"` | Extreme of obj1 to centre of obj2 |
| `"ex2c1"` | Extreme of obj2 to centre of obj1 |

**Nearest neighbor:**

```javascript
// Find closest object to object 0 (centre-to-centre)
Ext.Manager3D_Closest(0, "cc", closestIdx);
print("Nearest to 0 is " + closestIdx);

// Border-to-border
Ext.Manager3D_Closest(0, "bb", closestIdx);

// Get border voxel coordinates
Ext.Manager3D_BorderVoxel(0, 1, bx, by, bz);
print("Border contact point: " + bx + " " + by + " " + bz);
```

### Colocalization

```javascript
// Pairwise colocalization percentages → Results Table
Ext.Manager3D_SelectAll();
Ext.Manager3D_Coloc();

// Between two specific objects
Ext.Manager3D_Coloc2(0, 1, pctA, pctB, surfContact);
print("Object 0 overlap: " + pctA + "%, Object 1 overlap: " + pctB + "%");
print("Surface contact: " + surfContact);
```

### Angles

```javascript
// Angle between 3 selected objects (based on centres)
Ext.Manager3D_MultiSelect();
Ext.Manager3D_Select(0);
Ext.Manager3D_Select(1);
Ext.Manager3D_Select(2);
Ext.Manager3D_Angle();
```

### Saving Results

```javascript
// Save measurement results
Ext.Manager3D_Measure();
Ext.Manager3D_SaveResult("M", "ResultsMeasure.csv");
Ext.Manager3D_CloseResult("M");

Ext.Manager3D_Quantif();
Ext.Manager3D_SaveResult("Q", "ResultsQuantif.csv");
Ext.Manager3D_CloseResult("Q");

Ext.Manager3D_Distance();
Ext.Manager3D_SaveResult("D", "ResultsDistance.csv");
Ext.Manager3D_CloseResult("D");

// Result window codes: "M"=Measure, "Q"=Quantif, "D"=Distance,
// "C"=Coloc, "V"=Voxels, "A"=All
```

---

## 4. 3D Segmentation

### 3D Simple Segmentation

```javascript
run("3D Simple Segmentation", "low_threshold=128 min_size=100 max_size=-1");
```

| Parameter | Description | Default |
|-----------|-------------|---------|
| `low_threshold` | Minimum intensity for objects | 128 |
| `min_size` | Min object size in voxels (-1 = no limit) | -1 |
| `max_size` | Max object size in voxels (-1 = no limit) | -1 |

Output: 16-bit labeled image where each object has a unique value.

### 3D Hysteresis Segmentation

Two-threshold segmentation: seeds must exceed high threshold,
objects grow to low threshold.

```javascript
run("3D Hysteresis Segmentation", "low_threshold=50 high_threshold=150 "
  + "min_size=100 max_size=-1 labelling");
```

### 3D Iterative Segmentation

Iteratively adjusts thresholds to separate touching objects.

```javascript
run("3D Iterative Segmentation", "threshold_method=OTSU min_vol=100 max_vol=-1 "
  + "criteria_method=ELONGATION threshold_criteria=2.0 value=3000");
```

### 3D Watershed

Seeded watershed for splitting touching objects. Requires a seeds image.

```javascript
// Typical workflow: create seeds from local maxima
run("3D Fast Filters", "filter=MaximumLocal radius_x_pix=5 radius_y_pix=5 radius_z_pix=5");
// Then watershed
run("3D Watershed", "seeds_threshold=10 image_threshold=50 image=raw_image seeds=seeds_image radius=2");
```

| Parameter | Description |
|-----------|-------------|
| `seeds_threshold` | Only seeds above this value are used |
| `image_threshold` | Only voxels above this value are clustered |
| `image` | Signal image for intensity-guided growth |
| `seeds` | Seeds/markers image |
| `radius` | Watershed radius |

### 3D Spots Segmentation

Specialized for detecting bright spots (puncta, foci).

```javascript
run("3D Spots Segmentation", "seeds_threshold=50 local_background=Local "
  + "radius_0=2 radius_1=4 radius_2=6 weighting=Gaussian local_threshold=Auto");
```

### 3D Nuclei Segmentation

Specialized pipeline for nuclear segmentation.

```javascript
run("3D Nuclei Segmentation", "seeds_threshold=50 local_background=100 "
  + "min_volume=1000 max_volume=100000");
```

---

## 5. 3D Filters

### 3D Fast Filters

**Menu:** `Plugins > 3D Suite > 3D Fast Filters`

```javascript
run("3D Fast Filters", "filter=FILTER_TYPE radius_x_pix=RX radius_y_pix=RY radius_z_pix=RZ");
```

| Filter Type | Description |
|-------------|-------------|
| `Mean` | Mean (average) filter |
| `Median` | Median filter |
| `Minimum` | Minimum (3D erosion for grayscale) |
| `Maximum` | Maximum (3D dilation for grayscale) |
| `Open` | Morphological opening (min then max) |
| `Close` | Morphological closing (max then min) |
| `MaximumLocal` | Local maxima detection |
| `TopHat` | Top-hat transform (spot detection) |
| `Sobel` | 3D Sobel edge detection |
| `Adaptive` | Nagao-like adaptive filter |
| `Variance` | Local variance |

Radii are independent for X, Y, Z (supports anisotropic kernels).
Uses fast rolling-ball algorithm when all three radii are equal.

**Examples:**
```javascript
// 3D Gaussian-like smoothing
run("3D Fast Filters", "filter=Mean radius_x_pix=2 radius_y_pix=2 radius_z_pix=2");

// 3D median filter
run("3D Fast Filters", "filter=Median radius_x_pix=2 radius_y_pix=2 radius_z_pix=1");

// 3D morphological erosion
run("3D Fast Filters", "filter=Minimum radius_x_pix=1 radius_y_pix=1 radius_z_pix=1");

// 3D morphological dilation
run("3D Fast Filters", "filter=Maximum radius_x_pix=1 radius_y_pix=1 radius_z_pix=1");

// Spot detection with top-hat
run("3D Fast Filters", "filter=TopHat radius_x_pix=5 radius_y_pix=5 radius_z_pix=3");

// Find local maxima for seeds
run("3D Fast Filters", "filter=MaximumLocal radius_x_pix=5 radius_y_pix=5 radius_z_pix=3");
```

### 3D Edge and Symmetry Filter

Edge detection and radial symmetry filter for detecting spherical objects.

```javascript
run("3D Edge and Symmetry Filter", "radius_x=10 radius_y=10 radius_z=5 "
  + "normalize edge symmetry");
```

### 3D Maxima Finder

Find local maxima in 3D (for generating seeds).

```javascript
run("3D Maxima Finder", "radius_x=5 radius_y=5 radius_z=3 noise=100");
```

---

## 6. 3D Analysis & Measurements

### 3D Shape (Compactness & Sphericity)

**Menu:** `Plugins > 3D Suite > Analysis > Shape > 3D Shape`

```javascript
run("3D Shape");
```

Output columns:
| Column | Description |
|--------|-------------|
| `Compactness(pix)` | C = 36*PI*V^2 / S^3 in pixel units |
| `Compactness(unit)` | C in calibrated units |
| `CompactCorrected(pix)` | With corrected surface |
| `CompactDiscrete(pix)` | For binary objects |
| `Sphericity(pix)` | S = C^(1/3), 1.0 = perfect sphere |
| `Sphericity(unit)` | In calibrated units |
| `SpherCorrected(pix)` | With corrected surface |
| `SpherDiscrete(pix)` | For binary objects |

### 3D Ellipsoid (Elongation & Flatness)

**Menu:** `Plugins > 3D Suite > Analysis > Shape > 3D Ellipsoid`

```javascript
run("3D Ellipsoid");
```

Output columns:
| Column | Description |
|--------|-------------|
| `VolEll` | Ellipsoid volume (calibrated) |
| `Spareness` | Ratio: ellipsoid volume / actual volume |
| `EllMajRad` | Major radius of fitted ellipsoid |
| `Elongation` | Major radius / 2nd radius (>1 = elongated) |
| `Flatness` | 2nd radius / 3rd radius (>1 = flat) |

Computed from eigenvalues of 3D moment covariance matrix.
Radii = sqrt(5 * eigenvalue).

### 3D Feret (Maximum Caliper)

```javascript
run("3D Feret");
```

Output columns:
| Column | Description |
|--------|-------------|
| `Feret(unit)` | Maximum diameter in calibrated units |
| `Feret1X/Y/Z(pix)` | First endpoint coordinates |
| `Feret2X/Y/Z(pix)` | Second endpoint coordinates |

Note: Computes all pairwise distances — can be slow for large objects.

### 3D Volume Surface

```javascript
run("3D Volume Surface");
```

### 3D Centroid

```javascript
run("3D Centroid");
```

### 3D Intensity

```javascript
run("3D Intensity");
```

Measures intensity statistics per object from a signal image.

### 3D DistanceContour

Measures distances from each surface voxel to the centroid.

```javascript
run("3D DistanceContour");
```

### 3D Mesh

Generates 3D mesh surface for each object.

```javascript
run("3D Mesh");
```

### 3D RDAR (Radial Distribution of Average Radius)

Shape analysis comparing object contour to a sphere.

```javascript
run("3D RDAR");
```

---

## 7. 3D Distances & Relationships

### 3D Distances

**Menu:** `Plugins > 3D Suite > Analysis > Relationships > 3D Distances`

Computes distances between all objects in two images (can be same image).

```javascript
run("3D Distances", "image_a=labelA image_b=labelB "
  + "distance=CenterCenter closest");
```

| Parameter | Options |
|-----------|---------|
| `distance` | `CenterCenter`, `BorderBorder`, `Hausdorff` |
| `closest` | Return only nearest neighbor |
| `closest2` | Return 2 nearest neighbors |
| (no flag) | All pairwise distances |

Output columns (closest mode):
| Column | Description |
|--------|-------------|
| `Label` | Object ID in image A |
| `Closest_1` | Nearest object label in B |
| `Distance_1` | Distance to nearest |
| `Closest_2` | 2nd nearest label |
| `Distance_2` | Distance to 2nd nearest |

When image A = image B, Distance_1 = 0 (self), Distance_2 = nearest neighbor.

### 3D Interactions

Analyzes spatial relationships between two populations of objects.

```javascript
run("3D Interactions");
```

### 3D Mereotopology

Topological relationships between objects (contains, overlaps, touches, etc.).

```javascript
run("3D Mereotopology");
```

### 3D Numbering

Assigns numbering/labeling to objects based on spatial criteria.

```javascript
run("3D Numbering");
```

---

## 8. 3D Spatial Statistics

### 3D SpatialStatistics

**Menu:** `Plugins > 3D Suite > Analysis > Spatial > 3D SpatialStatistics`
**Docs:** https://imagejdocu.list.lu/plugin/analysis/spatial_statistics_2d_3d/start

Assesses whether objects are randomly distributed, clustered, or regularly spaced
within a reference structure using Monte Carlo simulations.

```javascript
run("3D SpatialStatistics", "pattern=objects reference=mask "
  + "nb_evaluations=1000 nb_random=100 hardcore_distance=0");
```

**Computed Functions:**

| Function | Description |
|----------|-------------|
| **G-function** | CDF of nearest-neighbor distance between pattern points. Clustering = G rises faster than random expectation. |
| **F-function** | CDF of distance from random positions to nearest pattern point. Regularity = F rises slower than random. |
| **H-function** | CDF of distance between any two pattern points (all pairs). |
| **SDI** | Spatial Distribution Index — normalized deviation from complete randomness. SDI > 1 = clustered, SDI < 1 = regular. |

**Interpretation:**
- If observed G-function is above the random envelope → **clustering**
- If observed G-function is below the random envelope → **regularity/repulsion**
- Confidence intervals shown from Monte Carlo simulation of random patterns

**Input:** Two binary images — one defining the reference structure, one marking detected objects.

### 3D Layers

Spatial analysis of objects in layers/shells within a reference structure.

```javascript
run("3D Layers");
```

### 3D Radial

Radial distribution analysis — measures object density as a function of distance
from a reference point or structure.

```javascript
run("3D Radial");
```

---

## 9. 3D Colocalization

### 3D MultiColoc (Object-Based)

**Menu:** `Plugins > 3D Suite > Analysis > Relationships > 3D MultiColoc`

Computes volume overlap between all object pairs across two labeled images.

```javascript
run("3D MultiColoc", "image_a=labelA image_b=labelB");
```

Output tables:
- **ColocAll**: Every possible pair with overlap volume
- **ColocOnly**: Only overlapping pairs

| Column | Description |
|--------|-------------|
| `O` | Object ID in image B |
| `V` | Colocalized volume (intersection) |
| `P` | Percentage of object A that colocalizes with B |

### Using 3D Manager for Colocalization

```javascript
run("3D Manager");
Ext.Manager3D_Segment(128, 255);
Ext.Manager3D_SelectAll();

// All pairwise colocalization → Results Table
Ext.Manager3D_Coloc();

// Between two specific objects
Ext.Manager3D_Coloc2(0, 1, pctA, pctB, surfContact);
// pctA = % of object 0 overlapping with object 1
// pctB = % of object 1 overlapping with object 0
// surfContact = surface contact area
```

---

## 10. 3D Binary Operations

### 3D Distance Map EVF

**Menu:** `Plugins > 3D Suite > Binary > 3D Distance Map EVF`

Computes 3D Euclidean Distance Map (EDM) using calibrated units.
Also computes EVF (Eroded Volume Fraction) for normalized distance layers.

```javascript
run("3D Distance Map EVF", "inside outside");
```

- **EDM output**: Pixel value = Euclidean distance to nearest object border
- **EVF output**: Normalized distance creating equal-volume layers
- Supports calibrated images (uses pixel size from image properties)

### 3D ConvexHull

Computes 3D convex hull of labeled objects.

```javascript
run("3D ConvexHull");
```

### 3D Fill Holes

Fills holes inside 3D objects.

```javascript
run("3D Fill Holes");
```

### 3D Exclude Edges

Removes objects touching image borders.

```javascript
run("3D Exclude Edges");
```

### 3D Close Labels

Morphological closing on labeled images (fills gaps between labels).

```javascript
run("3D Close Labels", "radius_x=2 radius_y=2 radius_z=2");
```

### 3D Merge Labels

Merges adjacent labels based on criteria.

```javascript
run("3D Merge Labels");
```

### 3D Density Filter

Filters objects based on local density.

```javascript
run("3D Density Filter", "radius_x=10 radius_y=10 radius_z=5 number=3");
```

### 3D Binary Interpolate

Interpolates between binary slices to create smooth 3D objects.

```javascript
run("3D Binary Interpolate");
```

---

## 11. MorphoLibJ 3D

**Plugin:** `MorphoLibJ` (included in Fiji by default)
**Update Site:** `IJPB-plugins`
**Docs:** https://imagej.net/plugins/morpholibj
**Source:** https://github.com/ijpb/MorphoLibJ

### Connected Components Labeling

```javascript
// 3D labeling with 6-connectivity (face-connected)
run("Connected Components Labeling", "connectivity=6 type=[16 bits]");

// 3D labeling with 26-connectivity (face+edge+corner)
run("Connected Components Labeling", "connectivity=26 type=[16 bits]");
```

| Parameter | Options |
|-----------|---------|
| `connectivity` | `6` (face only), `26` (all neighbors) for 3D |
| `type` | `[8 bits]`, `[16 bits]`, `[32 bits]` — bit depth of output labels |

### Analyze Regions 3D

**Menu:** `Plugins > MorphoLibJ > Analyze > Analyze Regions 3D`

```javascript
run("Analyze Regions 3D", "volume surface_area sphericity "
  + "euler_number equivalent_ellipsoid ellipsoid_elongations "
  + "max._inscribed surface_area_method=[Crofton (13 dirs.)] "
  + "euler_connectivity=C6");
```

| Checkbox | Output Columns |
|----------|---------------|
| `volume` | Volume (calibrated) |
| `surface_area` | Surface area (Crofton formula) |
| `sphericity` | Sphericity = (36*PI*V^2/S^3)^(1/3) |
| `euler_number` | Euler number (topology: holes, tunnels) |
| `equivalent_ellipsoid` | Centroid XYZ, Radius 1/2/3, Azimut/Elevation/Roll |
| `ellipsoid_elongations` | Elong1 (R1/R2), Elong2 (R1/R3) |
| `max._inscribed` | Inscribed ball center XYZ, Radius |

| Parameter | Options |
|-----------|---------|
| `surface_area_method` | `[Crofton (3 dirs.)]`, `[Crofton (13 dirs.)]` |
| `euler_connectivity` | `C6`, `C26` |

### Intensity Measurements 3D

**Menu:** `Plugins > MorphoLibJ > Analyze > Intensity Measurements 3D`

```javascript
run("Intensity Measurements 3D", "input=raw_image labels=label_image "
  + "mean stddev max min median mode skewness kurtosis numberofvoxels volume");
```

### Morphological Filters (3D)

```javascript
run("Morphological Filters (3D)", "operation=Erosion element=Ball "
  + "x-radius=3 y-radius=3 z-radius=3");
```

| Operation | Description |
|-----------|-------------|
| `Erosion` | Shrink objects |
| `Dilation` | Grow objects |
| `Closing` | Fill small holes (dilate then erode) |
| `Opening` | Remove small protrusions (erode then dilate) |
| `Gradient` | Morphological gradient (edge detection) |
| `Laplacian` | Morphological Laplacian |
| `BlackTopHat` | Detect dark features in bright background |
| `WhiteTopHat` | Detect bright features in dark background |

| Element | Description |
|---------|-------------|
| `Ball` | Spherical structuring element |
| `Cube` | Cubic structuring element |

### Chamfer Distance Map 3D

Approximate 3D Euclidean distance map from binary image.

```javascript
run("Chamfer Distance Map 3D", "distances=[Borgefors (3,4,5)] "
  + "output=[32 bits] normalize");
```

| Distances | Weights (face, edge, corner) |
|-----------|-----|
| `[Chessboard (1,1,1)]` | Fast but inaccurate |
| `[City-Block (1,2,3)]` | Manhattan-like |
| `[Quasi-Euclidean (1,1.41,1.73)]` | Good approximation |
| `[Borgefors (3,4,5)]` | Best 3x3x3 mask approximation |

### Geodesic Distance Map 3D

Distance constrained to propagate within a mask (follows object shape).

```javascript
run("Geodesic Distance Map 3D", "marker=marker_image mask=mask_image "
  + "distances=[Borgefors (3,4,5)] output=[32 bits] normalize");
```

### Distance Transform Watershed 3D

Splits touching objects using distance transform + watershed.

```javascript
run("Distance Transform Watershed 3D", "distances=[Borgefors (3,4,5)] "
  + "output=[32 bits] normalize dynamic=2 connectivity=6");
```

| Parameter | Description |
|-----------|-------------|
| `distances` | Chamfer distance type (see above) |
| `output` | `[16 bits]` or `[32 bits]` |
| `normalize` | Divide by first weight |
| `dynamic` | Watershed flooding depth (higher = fewer objects) |
| `connectivity` | `6` or `26` (3D neighborhood) |

### Classic Watershed (3D)

```javascript
run("Classic Watershed", "input=gradient_image mask=binary_mask "
  + "use min max h_minima=5 output=[32 bits]");
```

### Marker-Controlled Watershed (3D)

```javascript
run("Marker-controlled Watershed", "input=gradient_image marker=seeds_image "
  + "mask=binary_mask compactness=0 binary=[32 bits]");
```

### Morphological Segmentation (3D)

Interactive plugin with macro compatibility.

```javascript
run("Morphological Segmentation");
// Then use the GUI, or for batch:
// Morphological Segmentation supports macro recording
```

### Other Binary Operations

```javascript
// Remove largest region
run("Remove Largest Region");

// Keep largest region
run("Keep Largest Region");

// Size opening (remove small objects)
run("Size Opening 2D/3D", "min=500");

// Fill holes in 3D
run("Fill Holes (Binary/Gray)");

// Kill borders
run("Kill Borders");

// Regional Min/Max 3D
run("Regional Min & Max 3D", "operation=[Regional Maxima] connectivity=6");
run("Extended Min & Max 3D", "operation=[Extended Maxima] dynamic=10 connectivity=6");
```

---

## 12. CLIJ2 GPU-Accelerated 3D

**Plugin:** `CLIJ2` (GPU processing via OpenCL)
**Menu:** `Plugins > ImageJ on GPU (CLIJ2)`
**Docs:** https://clij.github.io/clij2-docs/
**Reference:** https://clij.github.io/clij2-docs/reference

CLIJ2 provides ~300+ GPU-accelerated operations. All use `Ext.CLIJ2_xxx()` syntax.

### Initialization

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");
// Pushes image to GPU
Ext.CLIJ2_push("image_title");
```

### 3D Filtering

```javascript
// Gaussian blur 3D
Ext.CLIJ2_gaussianBlur3D(input, output, sigmaX, sigmaY, sigmaZ);

// Mean filter 3D (box)
Ext.CLIJ2_mean3DBox(input, output, radiusX, radiusY, radiusZ);

// Mean filter 3D (sphere)
Ext.CLIJ2_mean3DSphere(input, output, radiusX, radiusY, radiusZ);

// Maximum (dilation) 3D
Ext.CLIJ2_maximum3DBox(input, output, radiusX, radiusY, radiusZ);
Ext.CLIJ2_maximum3DSphere(input, output, radiusX, radiusY, radiusZ);

// Minimum (erosion) 3D
Ext.CLIJ2_minimum3DBox(input, output, radiusX, radiusY, radiusZ);
Ext.CLIJ2_minimum3DSphere(input, output, radiusX, radiusY, radiusZ);

// Difference of Gaussian 3D
Ext.CLIJ2_differenceOfGaussian3D(input, output, sigma1x, sigma1y, sigma1z,
  sigma2x, sigma2y, sigma2z);

// Detect maxima/minima 3D
Ext.CLIJ2_detectMaxima3DBox(input, output, radiusX, radiusY, radiusZ);
Ext.CLIJ2_detectMinima3DBox(input, output, radiusX, radiusY, radiusZ);
```

### Thresholding

```javascript
Ext.CLIJ2_thresholdOtsu(input, binary_output);
```

### Labeling & Segmentation

```javascript
// Connected components (3D)
Ext.CLIJ2_connectedComponentsLabelingBox(binary_input, label_output);

// Voronoi labeling (expands labels until they touch)
Ext.CLIJ2_voronoiLabeling(binary_input, voronoi_labels);

// Extend labels via Voronoi (from existing label map)
Ext.CLIJ2_extendLabelingViaVoronoi(label_input, voronoi_output);

// Voronoi + Otsu in one step
Ext.CLIJ2_voronoiOtsuLabeling(input, label_output, spotSigma, outlineSigma);

// Masked Voronoi (within a mask)
Ext.CLIJ2_maskedVoronoiLabeling(binary_input, mask, voronoi_output);

// Exclude labels on edges
Ext.CLIJ2_excludeLabelsOnEdges(label_input, label_output);

// Exclude by size
Ext.CLIJ2_excludeLabelsOutsideSizeRange(label_input, label_output, minSize, maxSize);

// Dilate / erode labels
Ext.CLIJ2_dilateLabels(label_input, label_output, radius);
Ext.CLIJ2_erodeLabels(label_input, label_output, radius);
```

### Distance & Neighbor Analysis

```javascript
// Distance map from binary
Ext.CLIJ2_distanceMap(binary_input, distance_output);

// Generate distance matrix between all label centroids
Ext.CLIJ2_generateDistanceMatrix(pointlist1, pointlist2, distance_matrix);

// Touch matrix (which labels are adjacent)
Ext.CLIJ2_generateTouchMatrix(label_input, touch_matrix);

// N nearest neighbors matrix
Ext.CLIJ2_generateNNearestNeighborsMatrix(distance_matrix, nn_matrix, n);

// Proximal neighbors (within distance range)
Ext.CLIJ2_generateProximalNeighborsMatrix(distance_matrix, proximal_matrix,
  minDist, maxDist);

// Average distance of N closest neighbors map
Ext.CLIJ2_averageDistanceOfNClosestNeighborsMap(label_input, distance_map, n);

// Count touching neighbors
Ext.CLIJ2_touchingNeighborCountMap(label_input, count_map);

// Draw mesh between neighbors (for visualization)
Ext.CLIJ2_drawMeshBetweenTouchingLabels(pointlist, touch_matrix, mesh_image);
Ext.CLIJ2_drawMeshBetweenNClosestLabels(pointlist, distance_matrix, mesh, n);
```

### Measurements on Labels

```javascript
// Full statistics per label
Ext.CLIJ2_statisticsOfLabelledPixels(intensity_input, label_input);
// → Results table with: IDENTIFIER, BOUNDING_BOX_X/Y/Z/WIDTH/HEIGHT/DEPTH,
//   MINIMUM_INTENSITY, MAXIMUM_INTENSITY, MEAN_INTENSITY, SUM_INTENSITY,
//   STANDARD_DEVIATION_INTENSITY, PIXEL_COUNT, CENTROID_X/Y/Z

// Parametric maps (value per label visualized as image)
Ext.CLIJ2_meanIntensityMap(intensity, labels, mean_map);
Ext.CLIJ2_maximumIntensityMap(intensity, labels, max_map);
Ext.CLIJ2_minimumIntensityMap(intensity, labels, min_map);
Ext.CLIJ2_standardDeviationIntensityMap(intensity, labels, sd_map);
Ext.CLIJ2_pixelCountMap(labels, count_map);
Ext.CLIJ2_extensionRatioMap(labels, ratio_map);

// Centroid extraction
Ext.CLIJ2_centroidsOfLabels(labels, pointlist);

// Distance from each labeled pixel to its centroid
Ext.CLIJ2_euclideanDistanceFromLabelCentroidMap(labels, distance_map);

// Export labels to ROI Manager
Ext.CLIJ2_pullLabelsToROIManager(label_input);
```

### Overlap & Colocalization

```javascript
// Jaccard index between two binary images
Ext.CLIJ2_jaccardIndex(binary1, binary2, jaccard);

// Sorensen-Dice coefficient
Ext.CLIJ2_sorensenDiceCoefficient(binary1, binary2, dice);

// Overlap matrix between two label maps
Ext.CLIJ2_generateBinaryOverlapMatrix(labels1, labels2, overlap_matrix);

// Jaccard index matrix between label maps
Ext.CLIJ2_generateJaccardIndexMatrix(labels1, labels2, jaccard_matrix);

// Label overlap count map
Ext.CLIJ2_labelOverlapCountMap(labels1, labels2, overlap_count);
```

### Utility

```javascript
// Pull result from GPU back to ImageJ
Ext.CLIJ2_pull("output_name");

// Create empty 3D image on GPU
Ext.CLIJ2_create3D(image, width, height, depth, bitDepth);

// Crop 3D region
Ext.CLIJ2_crop3D(input, output, startX, startY, startZ, width, height, depth);

// Release GPU memory
Ext.CLIJ2_release("image_name");
Ext.CLIJ2_clear();

// 3D affine transform
Ext.CLIJ2_affineTransform3D(input, output, transformString);
```

### Complete CLIJ2 3D Workflow Example

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_push("raw_stack");

// Denoise
Ext.CLIJ2_gaussianBlur3D("raw_stack", "blurred", 2, 2, 1);

// Threshold
Ext.CLIJ2_thresholdOtsu("blurred", "binary");

// Label
Ext.CLIJ2_connectedComponentsLabelingBox("binary", "labels");

// Remove edge objects
Ext.CLIJ2_excludeLabelsOnEdges("labels", "labels_no_edge");

// Size filter
Ext.CLIJ2_excludeLabelsOutsideSizeRange("labels_no_edge", "labels_filtered", 100, 100000);

// Measure
Ext.CLIJ2_statisticsOfLabelledPixels("raw_stack", "labels_filtered");

// Voronoi tessellation
Ext.CLIJ2_extendLabelingViaVoronoi("labels_filtered", "voronoi_labels");

// Neighbor analysis
Ext.CLIJ2_generateTouchMatrix("labels_filtered", "touch_matrix");
Ext.CLIJ2_touchingNeighborCountMap("labels_filtered", "neighbor_count");

// Pull results back
Ext.CLIJ2_pull("labels_filtered");
Ext.CLIJ2_pull("voronoi_labels");
Ext.CLIJ2_pull("neighbor_count");
Ext.CLIJ2_clear();
```

---

## 13. Coloc 2

**Menu:** `Analyze > Colocalization Analysis > Coloc 2`
**Docs:** https://imagej.net/plugins/coloc-2

Pixel-based colocalization analysis. Works on 2D images and full 3D z-stacks.

### Usage

```javascript
// Coloc 2 has limited macro support. Typical approach:
// 1. Open a 2-channel image
// 2. Split channels if needed
run("Split Channels");
// 3. Run Coloc 2
run("Coloc 2", "channel_1=C1-image channel_2=C2-image "
  + "roi_or_mask=<None> threshold_regression=Costes "
  + "display_shuffled_images psf=3 costes_randomisations=100");
```

### Output Statistics

| Metric | Description | Range |
|--------|-------------|-------|
| **Pearson's r** | Linear correlation of pixel intensities | -1 to +1 |
| **Manders M1** | Fraction of channel 1 in colocalizing pixels | 0 to 1 |
| **Manders M2** | Fraction of channel 2 in colocalizing pixels | 0 to 1 |
| **tM1** | Thresholded Manders (above auto-threshold) | 0 to 1 |
| **tM2** | Thresholded Manders (above auto-threshold) | 0 to 1 |
| **Li ICQ** | Li's Intensity Correlation Quotient | -0.5 to +0.5 |
| **Costes p-value** | Statistical significance by randomization | 0 to 1 |

### 3D Notes
- ROI applies across all slices in a z-stack
- 3D masks accepted (must match XYZ dimensions)
- Time series NOT supported (analyze frame by frame)
- Background subtraction recommended before analysis
- PSF size parameter used for Costes randomization block size

---

## 14. DiAna

**Plugin:** `DiAna` (Distance Analysis)
**Docs:** https://imagej.net/plugins/distance-analysis
**Paper:** Gilles et al. (2017)

Object-based 3D colocalization and distance analysis. Part of 3D ImageJ Suite.

### Segmentation

```javascript
// Threshold-based segmentation
run("DiAna_Segment", "img=C1.tif filter=median rad=1.0 thr=739-3-2000-true-false");
// thr format: threshold-minSize-maxSize-excludeOnEdges-use32bit

// Spot segmentation (for puncta/foci)
run("DiAna_Segment", "img=C1.tif peaks=2.0-2.0-50.0 spots=30-10-1.5-3-2000-true");
// peaks format: radiusXY-radiusZ-noise
// spots format: seedThreshold-localBg-convergence-minSize-maxSize-excludeEdges

// Iterative segmentation
run("DiAna_Segment", "img=C1.tif iter=3-2000-20-30-true");
// iter format: minSize-maxSize-step-startThreshold-excludeEdges
```

### Analysis

```javascript
run("DiAna_Analyse", "img1=C1.tif img2=C2.tif "
  + "lab1=C1_seg.tif lab2=C2_seg.tif "
  + "coloc distc=50.0 adja kclosest=1 dista=50.0 measure");
```

| Parameter | Description |
|-----------|-------------|
| `img1` / `img2` | Original intensity images |
| `lab1` / `lab2` | Segmented/labeled images |
| `coloc` | Compute colocalization (volume overlap) |
| `distc=N` | Distance threshold for colocalization (um) |
| `adja` | Compute adjacency analysis |
| `kclosest=N` | Number of closest objects to find |
| `kclosest=Nb` | Use border-to-border distance (add 'b' suffix) |
| `dista=N` | Maximum distance for adjacency (um) |
| `measure` | Measure objects (volume, surface, centroid, Feret) |

### Distance Types
- **Centre-to-centre**: Default for `kclosest`
- **Border-to-border**: Append `b` to kclosest value (e.g., `kclosest=1b`)
- **Centre-to-border** and **border-to-centre**: Available in analysis

---

## 15. AnalyzeSkeleton

**Menu:** `Analyze > Skeleton > Analyze Skeleton (2D/3D)`
**Docs:** https://imagej.net/plugins/analyze-skeleton/
**Source:** https://github.com/fiji/AnalyzeSkeleton

Analyzes 2D and 3D skeleton images. Tags all voxels and counts junctions, branches,
and measures their lengths.

### Skeletonize First

```javascript
// Convert binary to skeleton
run("Skeletonize (2D/3D)");
```

### Run Analysis

```javascript
run("Analyze Skeleton (2D/3D)", "prune=none show display");
```

| Parameter | Options |
|-----------|---------|
| `prune` | `none`, `[shortest branch]`, `[lowest intensity voxel]`, `[lowest intensity branch]` |
| `prune_ends` | Remove end-point branches |
| `exclude_roi` | Preserve end-points within ROI from pruning |
| `show` | Display labeled skeleton image |
| `display` | Show detailed branch information table |

### Output: Results Table (per skeleton)

| Column | Description |
|--------|-------------|
| `# Branches` | Total branch count |
| `# Junctions` | Junction (branching) points |
| `# End-point voxels` | Terminal points |
| `# Junction voxels` | Voxels at junctions |
| `# Slab voxels` | Voxels along branches |
| `# Triple points` | Junctions with exactly 3 branches |
| `# Quadruple points` | Junctions with exactly 4 branches |
| `Average Branch Length` | Mean branch length (calibrated) |
| `Maximum Branch Length` | Longest branch (calibrated) |
| `Longest Shortest Path` | Longest geodesic path through skeleton |

### Output: Branch Information Table (when `display` enabled)

| Column | Description |
|--------|-------------|
| `Skeleton ID` | Which skeleton this branch belongs to |
| `Branch length` | Calibrated branch length |
| `V1 x/y/z` | First vertex coordinates |
| `V2 x/y/z` | Second vertex coordinates |
| `Euclidean distance` | Straight-line distance V1 to V2 |

**Tortuosity** = Branch length / Euclidean distance (>1 means curved).

### Voxel Classification (in labeled output image)

| Color | Type | Criterion |
|-------|------|-----------|
| Blue (end) | End-point | < 2 neighbors in 26-connected |
| Orange (slab) | Slab | Exactly 2 neighbors |
| Purple (junction) | Junction | > 2 neighbors |

### 3D Skeleton Workflow

```javascript
// 1. Start with a binary 3D image of your structure
// 2. Skeletonize
run("Skeletonize (2D/3D)");

// 3. Analyze
run("Analyze Skeleton (2D/3D)", "prune=[shortest branch] show display");

// 4. Results are in "Results" and "Branch information" tables
```

### Strahler Analysis

Complementary to AnalyzeSkeleton for branching order analysis:

```javascript
run("Strahler Analysis...");
```

---

## 16. SNT / Sholl Analysis

**Menu:** `Plugins > Neuroanatomy > SNT` or `Plugins > Neuroanatomy > Sholl`
**Docs:** https://imagej.net/plugins/snt/sholl

### Sholl Analysis (from image — works in 3D)

Uses spherical sampling shells in 3D (circles in 2D).

```javascript
run("Sholl Analysis (From Image)...",
  "datamodechoice=Intersections "
  + "startradius=10.0 stepsize=5.0 endradius=200.0 "
  + "hemishellchoice=[None. Use full shells] "
  + "polynomialchoice=['Best fitting' degree] "
  + "normalizationmethoddescription=[Automatically choose] "
  + "save=true");
```

| Parameter | Description |
|-----------|-------------|
| `startradius` | Starting radius of concentric spheres |
| `stepsize` | Distance between successive shells (0 = 1 voxel) |
| `endradius` | Maximum radius (`NaN` for auto) |
| `hemishellchoice` | `[None. Use full shells]`, `[Above center]`, `[Below center]` |

### 3D-Specific Options
- Uses 26-connected voxel connectivity for intersection counting
- **Ignore isolated voxels**: Filters single 6-connected isolated voxels on sphere surfaces
- **Parallel threads**: Multi-threaded for performance (0 = use all CPUs)
- Z-position = active slice (registered in results)

### Measurements

| Metric | Description |
|--------|-------------|
| Intersection counts | Per-shell crossing count |
| Critical radius | Radius of maximum intersections |
| Critical value | Maximum intersection count |
| Ramification index | Max intersections / primary branches |
| Enclosing radius | Radius containing the arbor |
| Sholl regression coefficient | Slope of semi-log regression |
| Polynomial fit | Best-fit polynomial parameters |

---

## 17. Spatial Statistics Plugins

### MosaicIA (Spatial Interaction Analysis)

**Menu:** `Plugins > Mosaic > Interaction Analysis`
**Docs:** https://pmc.ncbi.nlm.nih.gov/articles/PMC4219334/

Infers pairwise interaction potentials between two populations of objects
using nearest-neighbor Gibbs model.

```javascript
run("Interaction Analysis");
```

- Input: Two 2D or 3D images with spot-like objects, or coordinate files
- Uses kd-trees for efficient NN distance computation
- Estimates: attraction, repulsion, or independence

### BioVoxxel Toolbox — Nearest Neighbor

**Plugin:** `BioVoxxel Toolbox`
**Docs:** https://imagej.net/plugins/biovoxxel-toolbox

```javascript
run("Neighbor Analysis");
```

Uses Voronoi tessellation to compute inter-particle distances.

### Custom Nearest-Neighbor Analysis

When specialized plugins are unavailable, compute NN distances via 3D Manager:

```javascript
run("3D Manager");
Ext.Manager3D_Segment(128, 255);
Ext.Manager3D_Count(nb);

// Find nearest neighbor for each object
for (i = 0; i < nb; i++) {
    Ext.Manager3D_Closest(i, "cc", closest);
    Ext.Manager3D_Dist2(i, closest, "cc", dist);
    print("Object " + i + " → nearest=" + closest + " dist=" + dist);
}
```

---

## 18. Recipes — Common 3D Workflows

### Recipe: Complete 3D Object Analysis

```javascript
// 1. Open z-stack
open("/path/to/stack.tif");

// 2. Pre-process: denoise
run("3D Fast Filters", "filter=Median radius_x_pix=2 radius_y_pix=2 radius_z_pix=1");

// 3. Segment
run("3D Simple Segmentation", "low_threshold=128 min_size=500 max_size=-1");

// 4. Load into 3D Manager
run("3D Manager");
Ext.Manager3D_AddImage();

// 5. Measure everything
Ext.Manager3D_SelectAll();
Ext.Manager3D_Measure();   // geometry
Ext.Manager3D_Quantif();   // intensity (switch to raw image first)
Ext.Manager3D_Distance();  // distances

// 6. Save results
Ext.Manager3D_SaveResult("M", "/path/to/geometry.csv");
Ext.Manager3D_SaveResult("Q", "/path/to/intensity.csv");
Ext.Manager3D_SaveResult("D", "/path/to/distances.csv");
```

### Recipe: 3D Nearest-Neighbor Distance Analysis

```javascript
// Full nearest-neighbor analysis for all objects
run("3D Manager");
Ext.Manager3D_Segment(128, 255);
Ext.Manager3D_Count(nb);

// Get all pairwise distances
Ext.Manager3D_SelectAll();
Ext.Manager3D_Distance();

// Or compute nearest-neighbor specifically
nnDistances = "";
for (i = 0; i < nb; i++) {
    Ext.Manager3D_Closest(i, "bb", closest);  // border-to-border
    Ext.Manager3D_Dist2(i, closest, "bb", dist);
    nnDistances = nnDistances + dist + "\n";
    print("Object " + i + ": NN=" + closest + " dist=" + dist + " um");
}
```

### Recipe: 3D Object-Based Colocalization

```javascript
// Two-channel colocalization using DiAna
// Segment both channels
run("DiAna_Segment", "img=C1.tif filter=median rad=1.0 thr=500-100-50000-true-false");
run("DiAna_Segment", "img=C2.tif filter=median rad=1.0 thr=300-50-50000-true-false");

// Analyze distances and overlap
run("DiAna_Analyse", "img1=C1.tif img2=C2.tif "
  + "lab1=C1_seg.tif lab2=C2_seg.tif "
  + "coloc distc=1.0 kclosest=3 dista=5.0 measure");
```

### Recipe: 3D Watershed Splitting

```javascript
// Split touching objects using MorphoLibJ
// Start with binary stack
run("Distance Transform Watershed 3D", "distances=[Borgefors (3,4,5)] "
  + "output=[16 bits] normalize dynamic=2 connectivity=6");
```

Or using 3D ImageJ Suite:
```javascript
// Create seeds from local maxima of distance map
run("Chamfer Distance Map 3D", "distances=[Borgefors (3,4,5)] output=[32 bits] normalize");
run("3D Maxima Finder", "radius_x=5 radius_y=5 radius_z=3 noise=1");
// Watershed with seeds
run("3D Watershed", "seeds_threshold=1 image_threshold=0 image=binary seeds=maxima radius=2");
```

### Recipe: 3D Morphometry Pipeline

```javascript
// Complete shape analysis
// Input: labeled 3D image

// MorphoLibJ measurements
run("Analyze Regions 3D", "volume surface_area sphericity "
  + "equivalent_ellipsoid ellipsoid_elongations max._inscribed "
  + "surface_area_method=[Crofton (13 dirs.)] euler_connectivity=C6");

// 3D ImageJ Suite additional measurements
run("3D Shape");      // compactness, sphericity
run("3D Ellipsoid");  // elongation, flatness
run("3D Feret");      // max caliper diameter
```

### Recipe: 3D Skeleton Analysis (Vasculature/Neurites)

```javascript
// 1. Start with binary image of network structure
// 2. Skeletonize
run("Skeletonize (2D/3D)");

// 3. Analyze skeleton
run("Analyze Skeleton (2D/3D)", "prune=[shortest branch] show display");
// → Branch count, junction count, branch lengths

// 4. For neurites: Sholl analysis
// Place point ROI at soma center, then:
run("Sholl Analysis (From Image)...",
  "datamodechoice=Intersections startradius=10 stepsize=5 endradius=NaN");
```

### Recipe: GPU-Accelerated 3D Pipeline (CLIJ2)

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");

// Push to GPU
Ext.CLIJ2_push("raw");

// Process
Ext.CLIJ2_gaussianBlur3D("raw", "smooth", 2, 2, 1);
Ext.CLIJ2_thresholdOtsu("smooth", "binary");
Ext.CLIJ2_connectedComponentsLabelingBox("binary", "labels");
Ext.CLIJ2_excludeLabelsOnEdges("labels", "labels_clean");
Ext.CLIJ2_excludeLabelsOutsideSizeRange("labels_clean", "labels_sized", 200, 50000);

// Voronoi tessellation for territory analysis
Ext.CLIJ2_extendLabelingViaVoronoi("labels_sized", "voronoi");

// Measurements
Ext.CLIJ2_statisticsOfLabelledPixels("raw", "labels_sized");

// Neighbor analysis
Ext.CLIJ2_generateTouchMatrix("labels_sized", "touches");
Ext.CLIJ2_touchingNeighborCountMap("labels_sized", "ncount");
Ext.CLIJ2_averageDistanceOfNClosestNeighborsMap("labels_sized", "nn_dist", 3);

// Pull back
Ext.CLIJ2_pull("labels_sized");
Ext.CLIJ2_pull("voronoi");
Ext.CLIJ2_pull("nn_dist");
Ext.CLIJ2_clear();
```

### Recipe: Spatial Randomness Test

```javascript
// Test whether objects are clustered, random, or regular
// Requires: 3D ImageJ Suite

// 1. Create mask of reference structure (e.g., tissue region)
// 2. Create binary image of detected objects

// 3. Run spatial statistics
run("3D SpatialStatistics", "pattern=objects_binary reference=tissue_mask "
  + "nb_evaluations=1000 nb_random=100 hardcore_distance=0");
// Outputs G, F, H functions with confidence intervals
// SDI > 1 = clustered, SDI < 1 = regular
```

---

## Key Documentation URLs

| Resource | URL |
|----------|-----|
| 3D Objects Counter | https://imagej.net/plugins/3d-objects-counter |
| 3D ImageJ Suite | https://mcib3d.frama.io/3d-suite-imagej/ |
| 3D Manager Macros | https://mcib3d.frama.io/3d-suite-imagej/plugins/3DManager/3D-Manager-macros/ |
| MorphoLibJ | https://imagej.net/plugins/morpholibj |
| MorphoLibJ GitHub | https://github.com/ijpb/MorphoLibJ |
| CLIJ2 Reference | https://clij.github.io/clij2-docs/reference |
| CLIJ2 Label Functions | https://clij.github.io/clij2-docs/reference__label |
| CLIJ2 Measurements | https://clij.github.io/clij2-docs/reference__measurement |
| Coloc 2 | https://imagej.net/plugins/coloc-2 |
| DiAna | https://imagej.net/plugins/distance-analysis |
| AnalyzeSkeleton | https://imagej.net/plugins/analyze-skeleton/ |
| SNT / Sholl | https://imagej.net/plugins/snt/sholl |
| MosaicIA | https://pmc.ncbi.nlm.nih.gov/articles/PMC4219334/ |
| Spatial Statistics 2D/3D | https://imagejdocu.list.lu/plugin/analysis/spatial_statistics_2d_3d/start |
| Distance Transform Watershed | https://imagej.net/plugins/distance-transform-watershed |
| BioVoxxel Toolbox | https://imagej.net/plugins/biovoxxel-toolbox |
# 3D Analysis Plugin Reference — Complete Inventory

All commands verified as installed in this Fiji instance via `commands.txt`.
Total 3D-related commands found: ~300.

---

## 1. SEGMENTATION

### 3D ImageJ Suite (mcib3d)

| Command | Macro | Description |
|---------|-------|-------------|
| 3D Simple Segmentation | `run("3D Simple Segmentation", "low_threshold=128 high_threshold=255 min_size=100 max_size=-1");` | Basic threshold + size filter. Outputs labeled image. |
| 3D Hysteresis Thresholding | `run("3D Hysteresis Thresholding", "high=200 low=100");` | Two-level threshold: seeds at high, grows to low. Good for noisy data. |
| 3D Iterative Thresholding | `run("3D Iterative Thresholding", "...");` | Tries multiple thresholds, keeps objects meeting size/shape criteria. Best for touching objects with variable intensity. |
| 3D Iterative Thresholding 2 (beta) | `run("3D Iterative Thresholding 2 (beta)", "...");` | Improved version of iterative thresholding. |
| 3D Spot Segmentation | `run("3D Spot Segmentation", "...");` | Seed-based segmentation optimized for spot-like objects (puncta, foci). |
| 3D Nuclei Segmentation | `run("3D Nuclei Segmentation", "...");` | Specialized for nuclear segmentation with Gaussian pre-filter. |
| 3D Maxima Finder | `run("3D Maxima Finder", "radiusxy=2 radiusz=2 noise=50");` | Detects local maxima in 3D. Used as seeds for watershed. |
| 3D Watershed | `run("3D Watershed", "...");` | Classic watershed segmentation in 3D. |
| 3D Watershed Split | `run("3D Watershed Split", "...");` | Splits touching objects using watershed on distance map. |
| 3D Watershed Voronoi | `run("3D Watershed Voronoi", "...");` | Voronoi-based watershed — assigns all space to nearest seed. |
| 3D Fill Holes | `run("3D Fill Holes");` | Fills internal holes in 3D binary objects. |
| 3D Exclude Edges | `run("3D Exclude Edges");` | Removes objects touching the image border. |
| 3D Filter Objects | `run("3D Filter Objects", "...");` | Filter labeled objects by size, shape, or intensity criteria. |

### 3D Objects Counter (ImageJ built-in)

| Command | Macro | Description |
|---------|-------|-------------|
| 3D Objects Counter | `run("3D Objects Counter", "threshold=128 slice=1 min.=100 max.=1000000 objects surfaces centroids centres_of_masses statistics summary");` | Counts and measures 3D connected objects. |
| 3D OC Options | `run("3D OC Options", "...");` | Configure which measurements 3D Objects Counter computes. |

**3D Objects Counter output columns:**
- Volume (voxels), Surface (voxels)
- Integrated density, Mean gray value, StdDev gray value, Min/Max/Median gray value
- Mean distance to surface, StdDev distance to surface, Median distance to surface
- Centroid X/Y/Z, Center of mass X/Y/Z
- Bounding box (x, y, z, width, height, depth)

**3D Objects Counter output maps:**
- Objects map (labeled), Surface voxels map, Centroids map, Centers of mass map

### MorphoLibJ

| Command | Description |
|---------|-------------|
| Connected Components Labeling | 3D labeling with 6 or 26 connectivity |
| Classic Watershed | Flooding-based watershed in 3D |
| Marker-controlled Watershed | Seeded watershed in 3D |
| Interactive Marker-controlled Watershed | GUI-assisted seeded watershed |
| Distance Transform Watershed | Watershed from distance map of binary image |

### CLIJ2 (GPU-accelerated)

| Command | Ext. Function | Description |
|---------|--------------|-------------|
| Connected components (box) | `Ext.CLIJ2_connectedComponentsLabelingBox(src, dst);` | 26-connectivity labeling on GPU |
| Connected components (diamond) | `Ext.CLIJ2_connectedComponentsLabelingDiamond(src, dst);` | 6-connectivity labeling on GPU |
| Voronoi-Otsu-Labeling | `Ext.CLIJ2_voronoiOtsuLabeling(src, dst, spot_sigma, outline_sigma);` | One-step spot segmentation: Gaussian + Otsu + Voronoi |
| Binary watershed | `Ext.CLIJ2_watershed(src, dst);` | Watershed on GPU |
| Seeded watershed (exp.) | `Ext.CLIJx_seededWatershed(src, seeds, dst);` | Marker-controlled watershed on GPU |
| Parametric Watershed (exp.) | `Ext.CLIJx_parametricWatershed(src, dst, sigma, threshold);` | Automated watershed with parameters |

### Deep Learning Segmentation

| Command | Description |
|---------|-------------|
| StarDist 2D | Pre-trained nuclei segmentation (operates slice-by-slice on stacks) |
| Trainable Weka Segmentation 3D | Machine-learning pixel classification in 3D |
| Advanced Weka Segmentation | Trainable segmentation with feature selection |
| Labkit (Segment Image) | Interactive ML-based segmentation |
| 3D Image segmentation on GPU (CLIJx) | GPU-accelerated interactive segmentation |

### Other

| Command | Description |
|---------|-------------|
| Segment blob in 3D Viewer | Interactive blob segmentation in 3D Viewer |
| Find Connected Regions | Simple connected component finder |
| Particle Analyzer (3D) | process3d package — basic 3D particle analysis |
| Statistical Region Merging | Region-based segmentation |

---

## 2. MEASUREMENT

### 3D ImageJ Suite — Simple Measurements

Each accepts binary or labeled images. All output: Label, Value, Channel, Frame + specific columns.

#### 3D Volume (`run("3D Volume");`)
| Column | Description |
|--------|-------------|
| Volume(pix) | Volume in voxels |
| Volume(unit) | Volume in calibrated units |

#### 3D Surface (`run("3D Surface");`)
| Column | Description |
|--------|-------------|
| Surface(pix) | Surface area in pixel units |
| Surface(unit) | Surface area in calibrated units |
| SurfaceCorrected(pix) | Smoothed surface in pixel units |
| SurfaceNb | Count of contour voxels |

#### 3D Centroid (`run("3D Centroid");`)
| Column | Description |
|--------|-------------|
| CX(pix) | X coordinate of geometric center |
| CY(pix) | Y coordinate of geometric center |
| CZ(pix) | Z coordinate of geometric center |

#### 3D Intensity Measure (`run("3D Intensity Measure");`)
| Column | Description |
|--------|-------------|
| Average | Mean signal intensity |
| Minimum | Minimum signal value |
| Maximum | Maximum signal value |
| StandardDeviation | StdDev of signal |
| IntegratedDensity | Sum of all voxel values |

#### 3D Compactness (`run("3D Compactness");`)
| Column | Description |
|--------|-------------|
| Compactness(pix) | C = 36*pi*Vol^2/Area^3 (pixel units) |
| Compactness(unit) | Same in calibrated units |
| CompactCorrected(pix) | Using corrected surface |
| CompactDiscrete(pix) | Alternative for binary objects |
| Sphericity(pix) | S = C^(1/3) in pixel units |
| Sphericity(unit) | Same in calibrated units |
| SpherCorrected(pix) | Using corrected surface |
| SpherDiscrete(pix) | Alternative for binary objects |

#### 3D Ellipsoid Fitting (`run("3D Ellipsoid Fitting");`)
| Column | Description |
|--------|-------------|
| VolEll(unit) | Fitted ellipsoid volume |
| Spareness | Ratio ellipsoid volume / object volume |
| EllMajRad(unit) | Major radius of ellipsoid |
| Elongation | Major radius / second radius |
| Flatness | Second radius / third radius |

#### 3D Ellipsoid measure (`run("3D Ellipsoid measure");`)
Additional ellipsoid measurements from eigenvalue decomposition of 3D moments.

#### 3D Feret (`run("3D Feret");`)
| Column | Description |
|--------|-------------|
| Feret(unit) | Maximum diameter (calibrated) |
| Feret1X/Y/Z(pix) | First endpoint coordinates |
| Feret2X/Y/Z(pix) | Second endpoint coordinates |

#### 3D Distance Contour (`run("3D Distance Contour");`)
| Column | Description |
|--------|-------------|
| DCMin(pix/unit) | Min center-to-contour distance |
| DCMax(pix/unit) | Max center-to-contour distance |
| DCAvg(pix/unit) | Mean center-to-contour distance |
| DCsd(pix/unit) | StdDev of center-to-contour distance |

#### 3D Mesh Measure (`run("3D Mesh Measure (slow)");`)
| Column | Description |
|--------|-------------|
| SurfaceArea | Mesh surface in pixel units |
| SurfaceAreaSmooth | Smoothed mesh surface |

#### 3D Convex Hull (`run("3D Convex Hull (slow)");`)
Computes convex hull volume for each object.

#### 3D RDAR (`run("3D RDAR");`)
Quantifies morphological deviation from fitted ellipsoid — protruding vs. indented regions.

### 3D Manager Measurements (via macro Ext. functions)

**Measure3D types** (geometric, via `Ext.Manager3D_Measure3D(obj, type, result)`):
- `"Vol"` — volume
- `"Surf"` — surface area
- `"NbVox"` — voxel count
- `"Comp"` — compactness
- `"Feret"` — Feret diameter
- `"Elon1"` — elongation (major/second radius)
- `"Elon2"` — flatness (second/third radius)
- `"DCMin"` — min center-to-contour distance
- `"DCMax"` — max center-to-contour distance
- `"DCMean"` — mean center-to-contour distance
- `"DCSD"` — StdDev center-to-contour distance
- `"RatioVolEll"` — ratio of ellipsoid volume to object volume

**Quantif3D types** (intensity, via `Ext.Manager3D_Quantif3D(obj, type, result)`):
- `"IntDen"` — integrated density
- `"Mean"` — mean intensity
- `"Min"` — minimum intensity
- `"Max"` — maximum intensity
- `"Sigma"` — standard deviation of intensity

**Spatial queries:**
- `Ext.Manager3D_Centroid3D(obj, cx, cy, cz)` — geometric center
- `Ext.Manager3D_MassCenter3D(obj, cmx, cmy, cmz)` — intensity-weighted center
- `Ext.Manager3D_Feret1(obj, fx, fy, fz)` — first Feret endpoint
- `Ext.Manager3D_Feret2(obj, fx, fy, fz)` — second Feret endpoint
- `Ext.Manager3D_Bounding3D(obj, x0, x1, y0, y1, z0, z1)` — bounding box

### MorphoLibJ — Analyze Regions 3D

`run("Analyze Regions 3D", "...");`

| Column | Description |
|--------|-------------|
| Label | Object identifier |
| Box.X.Min/Max, Box.Y.Min/Max, Box.Z.Min/Max | Bounding box |
| Volume | Voxel count x voxel volume |
| SurfaceArea | Crofton formula (3 or 13 orientations) |
| Sphericity | 36*pi*V^2 / S^3, then cube root |
| Elli.Center.X/Y/Z | Inertia ellipsoid centroid |
| Elli.Radius1/2/3 | Ellipsoid radii (decreasing) |
| Elli.Phi/Theta/Psi | Euler angles (degrees) |

### CLIJ2 Label Statistics

`Ext.CLIJ2_statisticsOfLabelledPixels(input, labelmap);`

Output columns: Bounding box, Area/volume (pixels), Min/Max/Mean intensity.

Additional label map operations:
- `Ext.CLIJ2_pixelCountMap(labels, dst)` — pixel count per label
- `Ext.CLIJ2_labelMeanIntensityMap(src, labels, dst)` — mean intensity per label
- `Ext.CLIJ2_labelStandardDeviationIntensityMap(src, labels, dst)` — StdDev per label

### Other Measurement Tools

| Command | Description |
|---------|-------------|
| Simple Analysis 2D/3D | Combined distance + basic measurements |
| Sync Measure 3D | Synchronized measurement across 3 orthogonal views |
| Volume Calculator | Estimates volume from stack |
| Local Thickness (complete process) | Computes local thickness map of 3D structures |
| Local Thickness (masked, calibrated, silent) | Silent/batch mode local thickness |

---

## 3. DISTANCE ANALYSIS

### 3D ImageJ Suite

| Command | Description |
|---------|-------------|
| 3D Distance Map | `run("3D Distance Map");` — Euclidean distance transform (EDT) on binary 3D image |
| 3D Distances | `run("3D Distances");` — All pairwise distances between objects in two images |
| 3D Distances Closest | `run("3D Distances Closest");` — Nearest neighbor distances (outputs Closest_1, Distance_1, Closest_2, Distance_2) |
| 3D Distances Advanced | `run("3D Distances Advanced");` — Extended distance options |
| 3D Distances Container | `run("3D Distances Container");` — Distances when objects are inside other objects |
| 3D Distances2 Container | `run("3D Distances2 Container");` — Alternative container distance |
| 3D Density Map | `run("3D Density Map");` — Local density map based on object counts |

**Distance types available (for Distances and 3D Manager):**
- `"cc"` — center-to-center
- `"bb"` — border-to-border
- `"c1b2"` — center of A to border of B
- `"c2b1"` — center of B to border of A
- `"r1c2"` — radius of A towards center of B
- `"r2c1"` — radius of B towards center of A
- `"ex2c1"` — extended distance (for non-overlapping)
- `"ex1c2"` — extended distance (reverse)
- Hausdorff distance (in standalone plugin)

### 3D Manager Distance Functions

```javascript
// All pairwise distances to Results table
Ext.Manager3D_Distance();

// Specific pair distance
Ext.Manager3D_Dist2(obj1, obj2, "bb", dist);  // border-to-border
Ext.Manager3D_Dist2(obj1, obj2, "cc", dist);  // center-to-center

// Nearest neighbor
Ext.Manager3D_Closest(obj, "cc", closest_obj);  // center-to-center
Ext.Manager3D_Closest(obj, "bb", closest_obj);  // border-to-border

// Border voxel coordinates
Ext.Manager3D_BorderVoxel(obj1, obj2, bx, by, bz);
```

### Fiji Core Distance Transforms

| Command | Description |
|---------|-------------|
| Distance Map | `run("Distance Map");` — 2D EDT |
| Distance Transform 3D | `run("Distance Transform 3D");` — process3d package 3D EDT |
| Exact Euclidean Distance Transform (3D) | `run("Exact Euclidean Distance Transform (3D)");` — precise 3D EDT |
| Exact Signed Euclidean Distance Transform (3D) | Signed EDT (positive outside, negative inside) |
| Geometry to Distance Map | Part of Local Thickness pipeline |
| Distance Map to Distance Ridge | Medial axis from distance map |

### MorphoLibJ Distance

| Command | Description |
|---------|-------------|
| Chamfer Distance Map 3D | Approximate Euclidean distance using chamfer weights |
| Geodesic Distance Map | Distance constrained to within a mask region |

### CLIJ2 Distance Functions (GPU)

| Ext. Function | Description |
|--------------|-------------|
| `Ext.CLIJ2_distanceMap(src, dst)` | Binary distance map on GPU |
| `Ext.CLIJ2_generateDistanceMatrix(pointlist1, pointlist2, dst)` | All pairwise distances between point sets |
| `Ext.CLIJ2_averageDistanceOfTouchingNeighbors(distMatrix, touchMatrix, dst)` | Mean distance to touching neighbors |
| `Ext.CLIJ2_averageDistanceOfNClosestPoints(distMatrix, dst, n)` | Mean distance to N nearest neighbors |
| `Ext.CLIJ2_nClosestDistances(distMatrix, dst, n)` | N nearest distances from distance matrix |
| `Ext.CLIJ2_nClosestPoints(distMatrix, dst, n)` | N nearest neighbor indices |
| `Ext.CLIJ2_shortestDistances(distMatrix, dst)` | Nearest neighbor distance |
| `Ext.CLIJ2_minimumDistanceOfTouchingNeighbors(distMatrix, touchMatrix, dst)` | Min distance to touching neighbor |
| `Ext.CLIJ2_maximumDistanceOfTouchingNeighbors(distMatrix, touchMatrix, dst)` | Max distance to touching neighbor |
| `Ext.CLIJ2_euclideanDistanceFromLabelCentroidMap(src, labels, dst)` | Distance from each pixel to its label's centroid |

**CLIJ2 Distance Map visualizations:**
- `AverageDistanceOfNClosestNeighborsMap` — map of mean N-nearest-neighbor distances
- `AverageNeighborDistanceMap` — map of mean centroid distances to neighbors
- `MinimumTouchingNeighborDistanceMap` — map of min touching-neighbor distance
- `MaximumTouchingNeighborDistanceMap` — map of max touching-neighbor distance
- `NeighborDistanceRangeRatioMap` — ratio of max/min neighbor distances

---

## 4. FILTERING (3D)

### ImageJ Core 3D Filters (`ij.plugin.Filters3D`)

| Command | Macro | Description |
|---------|-------|-------------|
| Maximum 3D... | `run("Maximum 3D...", "x=2 y=2 z=2");` | 3D maximum filter (box kernel) |
| Mean 3D... | `run("Mean 3D...", "x=2 y=2 z=2");` | 3D mean filter |
| Median 3D... | `run("Median 3D...", "x=2 y=2 z=2");` | 3D median filter |
| Minimum 3D... | `run("Minimum 3D...", "x=2 y=2 z=2");` | 3D minimum filter |
| Variance 3D... | `run("Variance 3D...", "x=2 y=2 z=2");` | 3D variance filter |
| Gaussian Blur 3D... | `run("Gaussian Blur 3D...", "x=2 y=2 z=2");` | 3D Gaussian blur |

### process3d Package

| Command | Description |
|---------|-------------|
| Convolve (3D) | 3D convolution with custom kernel |
| Dilate (3D) | Binary dilation in 3D |
| Erode (3D) | Binary erosion in 3D |
| Gradient (3D) | 3D gradient magnitude |
| Laplace (3D) | 3D Laplacian |
| Maximum (3D) / Max (3D) | 3D maximum filter |
| Median (3D) | 3D median filter |
| Minimum (3D) | 3D minimum filter |
| Smooth (3D) | 3D smoothing |
| Rebin (3D) | 3D binning / downsampling |
| Flood Fill (3D) | 3D flood fill |
| IFT (3D) | Inverse Fourier Transform 3D |

### 3D ImageJ Suite — 3D Fast Filters

`run("3D Fast Filters", "filter=Median radius_x_pix=2 radius_y_pix=2 radius_z_pix=2");`

Supported filters: Median, Mean, Minimum, Maximum, MaximumLocal, TopHat, OpenGray, CloseGray, Variance, Adaptive (within one plugin dialog).

### 3D ImageJ Suite — Edge/Feature Detection

| Command | Description |
|---------|-------------|
| 3D Edge and Symmetry Filter | Canny edge detection + radial symmetry filter in 3D. Good for detecting round objects. |

### MorphoLibJ 3D Morphological Filters

`run("Morphological Filters (3D)", "operation=Opening element=Ball x-radius=3 y-radius=3 z-radius=3");`

**Operations:** Erosion, Dilation, Closing, Opening, Gradient (dilation - erosion), Laplacian, Internal Gradient, External Gradient, White Top Hat, Black Top Hat.

**Structuring elements:** Ball (sphere), Cube, Planar (per-slice disc).

### MorphoLibJ 3D Reconstruction and Extrema

| Command | Description |
|---------|-------------|
| Geodesic Reconstruction 3D | Morphological reconstruction by dilation or erosion |
| Interactive Geodesic Reconstruction 3D | Point-and-click reconstruction |
| Regional Min & Max 3D | Detect regional minima/maxima with connectivity option |
| Extended Min & Max 3D | Detect extended minima/maxima with tolerance |
| Impose Min & Max 3D | Impose specific minima/maxima on grayscale |
| Gray Scale Attribute Filtering 3D | Attribute opening/closing using voxel count criterion |

### CLIJ2 3D Filters (GPU)

| Ext. Function | Description |
|--------------|-------------|
| `Ext.CLIJ2_gaussianBlur3D(src, dst, sx, sy, sz)` | 3D Gaussian blur |
| `Ext.CLIJ2_differenceOfGaussian3D(src, dst, s1x, s1y, s1z, s2x, s2y, s2z)` | DoG filter |
| `Ext.CLIJ2_maximum3DBox(src, dst, rx, ry, rz)` | Maximum filter (box) |
| `Ext.CLIJ2_maximum3DSphere(src, dst, rx, ry, rz)` | Maximum filter (sphere) |
| `Ext.CLIJ2_mean3DBox(src, dst, rx, ry, rz)` | Mean filter (box) |
| `Ext.CLIJ2_mean3DSphere(src, dst, rx, ry, rz)` | Mean filter (sphere) |
| `Ext.CLIJ2_median3DBox(src, dst, rx, ry, rz)` | Median filter (box) |
| `Ext.CLIJ2_median3DSphere(src, dst, rx, ry, rz)` | Median filter (sphere) |
| `Ext.CLIJ2_minimum3DBox(src, dst, rx, ry, rz)` | Minimum filter (box) |
| `Ext.CLIJ2_minimum3DSphere(src, dst, rx, ry, rz)` | Minimum filter (sphere) |
| `Ext.CLIJ2_erodeBox(src, dst)` | Binary erosion (box) |
| `Ext.CLIJ2_erodeSphere(src, dst)` | Binary erosion (sphere) |
| `Ext.CLIJ2_dilateBox(src, dst)` | Binary dilation (box) |
| `Ext.CLIJ2_dilateSphere(src, dst)` | Binary dilation (sphere) |
| `Ext.CLIJ2_openingBox(src, dst, radius)` | Morphological opening (box) |
| `Ext.CLIJ2_openingDiamond(src, dst, radius)` | Morphological opening (diamond) |
| `Ext.CLIJ2_closingBox(src, dst, radius)` | Morphological closing (box) |
| `Ext.CLIJ2_closingDiamond(src, dst, radius)` | Morphological closing (diamond) |
| `Ext.CLIJ2_detectMaxima3DBox(src, dst, rx, ry, rz)` | Detect local maxima |
| `Ext.CLIJ2_detectMinima3DBox(src, dst, rx, ry, rz)` | Detect local minima |
| `Ext.CLIJx_subtractBackground3D(src, dst, rx, ry, rz)` | Background subtraction 3D |
| `Ext.CLIJx_laplacianOfGaussian3D(src, dst, sx, sy, sz)` | LoG filter 3D |
| `Ext.CLIJx_hessianEigenvalues3D(src, small, middle, large, sx, sy, sz)` | Hessian eigenvalues (vessel/tube detection) |

### CLIJ2 3D Thresholding (GPU)

All via `Ext.CLIJ2_threshold<Method>(src, dst)`:
Methods: Default, Huang, IJ_IsoData, Intermodes, IsoData, Li, MaxEntropy, Mean, MinError, Minimum, Moments, Otsu, Percentile, Renyi Entropy, Shanbhag, Triangle, Yen.

Local thresholds (CLIJx experimental): Bernsen, Contrast, Mean, Median, MidGrey, Niblack, Phansalkar, Sauvola.

---

## 5. VISUALIZATION

### 3D Viewer

| Command | Description |
|---------|-------------|
| 3D Viewer | `run("3D Viewer");` — Full Java3D-based 3D rendering. Volume, surface, orthoslice modes. |

**TCP server `3d_viewer` commands:**
```
python ij.py 3d status          # check if open, list contents
python ij.py 3d add TITLE volume 50   # add as volume rendering
python ij.py 3d add TITLE surface 50  # add as isosurface
python ij.py 3d add TITLE orthoslice   # add as orthogonal slices
python ij.py 3d list            # list loaded content
python ij.py 3d snapshot 512 512      # capture current view
python ij.py 3d close           # close viewer
```

Display types: volume, surface, orthoslice, surface_plot.

### Other Visualization

| Command | Description |
|---------|-------------|
| 3D Project... | `run("3D Project...", "projection=[Brightest Point] axis=Y-Axis ...");` — Rotation projection |
| 3D Surface Plot | Interactive surface height plot |
| Volume Viewer | Alternative volume rendering |
| VolumeJ | Volume rendering via ray-casting |
| Volume Rendering Tech Demo | BigVolumeViewer — modern GPU rendering |
| Interactive Animation | Raycasting-based animation |
| Batch Animation | Batch raycasting for movies |
| Color Inspector 3D | Interactive 3D color space visualization |
| Spheres and Tubes in 3D | Create mesh primitives in 3D Viewer |

### CLIJ2 Visualization

| Command | Description |
|---------|-------------|
| Depth color projection on GPU | Z-depth color-coded projection |
| Interactive depth color projection (CLIJx) | Interactive version |
| Interactive Z projection (CLIJx) | GPU-accelerated interactive Z projection |
| Interactive max z with tip/tilt (CLIJx) | 3D-like view from Z-projections with tilting |

### Projections (Z)

| Command | Macro |
|---------|-------|
| Z Project... | `run("Z Project...", "projection=[Max Intensity]");` |
| Grouped Z Project... | Projects groups of slices |
| Temporal-Color Code | Time-to-color mapping for stacks |
| Dynamic Reslice | Interactive orthogonal reslice |
| Radial Reslice | Reslice around a center point |

**CLIJ2 Z-projection variants (GPU):**
MaximumZProjection, MinimumZProjection, MeanZProjection, SumZProjection, StandardDeviationZProjection, MedianZProjection, ArgMaximumZProjection, MaximumXProjection, MaximumYProjection, DepthColorProjection, ExtendedDepthOfFocus (Sobel/Tenengrad/Variance).

### Orthogonal Views

| Command | Macro |
|---------|-------|
| Orthogonal Views | `run("Orthogonal Views");` — XY, XZ, YZ synchronized views |
| Crop (3D) | Three-pane 3D crop |

---

## 6. SKELETON ANALYSIS

| Command | Macro | Description |
|---------|-------|-------------|
| Skeletonize (2D/3D) | `run("Skeletonize (2D/3D)");` | Medial axis thinning in 3D |
| Analyze Skeleton (2D/3D) | `run("Analyze Skeleton (2D/3D)", "prune=[none] calculate show");` | Full skeleton analysis |
| Skeletonize on GPU (exp.) | `Ext.CLIJx_skeletonize(src, dst);` | GPU-accelerated skeletonization |

### Analyze Skeleton Parameters

- **Prune cycles:** none, shortest branch, lowest intensity voxel, lowest intensity branch
- **Prune ends:** remove endpoint branches
- **Calculate shortest path:** largest shortest path via all-pairs algorithm
- **Show detailed info:** branch-level table
- **Display labeled skeletons:** color-coded skeleton IDs

### Analyze Skeleton — Results Table Columns

**Per-skeleton:**
| Column | Description |
|--------|-------------|
| # Branches | Total branch count |
| # Junctions | Merged junction cluster count |
| # End-point voxels | Terminal voxel count |
| # Junction voxels | Junction voxel count |
| # Slab voxels | Non-endpoint, non-junction voxel count |
| # Triple points | 3-way junction count |
| # Quadruple points | 4-way junction count |
| Average Branch Length | Mean branch length (calibrated) |
| Maximum Branch Length | Longest branch (calibrated) |
| Longest Shortest Path | Longest geodesic path through skeleton |

**Per-branch (detailed info):**
| Column | Description |
|--------|-------------|
| Skeleton ID | Which skeleton |
| Branch length | Calibrated branch length |
| V1 x/y/z | First endpoint coordinates |
| V2 x/y/z | Second endpoint coordinates |
| Euclidean distance | Straight-line distance between endpoints |

**Voxel classification:** endpoints (blue), slabs (orange), junctions (purple).

---

## 7. REGISTRATION & DRIFT CORRECTION

| Command | Description |
|---------|-------------|
| Correct 3D drift | `run("Correct 3D Drift");` — Python script, cross-correlation based |
| 3D Image registration on GPU (CLIJx) | GPU-accelerated 3D registration |
| Descriptor-based registration (2d/3d) | Feature-point matching registration |
| Descriptor-based series registration (2d/3d + t) | Time-series registration |
| 3D Stitching | Tile stitching in 3D |
| Linear Stack Alignment with SIFT | SIFT-based slice alignment |
| Elastic Stack Alignment | Non-rigid slice alignment |

**CLIJ2 3D transforms:**
- `Ext.CLIJ2_affineTransform3D(src, dst, transform_string)` — affine transform
- `Ext.CLIJ2_rotate3D(src, dst, angleX, angleY, angleZ, rotateAroundCenter)` — rotation
- `Ext.CLIJ2_translate3D(src, dst, tx, ty, tz)` — translation
- `Ext.CLIJ2_scale3D(src, dst, sx, sy, sz, rotateAroundCenter)` — scaling
- `Ext.CLIJ2_applyVectorField3D(src, vectorX, vectorY, vectorZ, dst)` — deformation field

**CLIJ2 drift correction:**
- `Ext.CLIJx_driftCorrectionByCenterOfMassFixation(src, dst)` — center-of-mass based
- `Ext.CLIJx_driftCorrectionByCentroidFixation(src, dst)` — centroid-based

---

## 8. SPATIAL STATISTICS & RELATIONSHIPS

### 3D ImageJ Suite

| Command | Description |
|---------|-------------|
| Spatial Analysis 2D/3D | Spatial distribution analysis: tests whether object arrangement is random, clustered, or dispersed. Uses F-function, G-function, or spatial descriptors. |
| 3D Radial Distribution | Radial distribution function around objects |
| 3D EVF Layers | Eroded Volume Fraction — concentric layers analysis |
| 3D Interactions | Detect/quantify touching or proximal objects. Outputs interaction surfaces. |
| 3D Association | Associate objects between two images based on overlap |
| 3D Association Track | Track object associations across time |
| 3D MereoTopology | Topological relationships (contains, overlaps, touches, etc.) |
| 3D Numbering | Number/relabel objects |
| 3D MultiColoc | Volume overlap between all object pairs across two channels |

### 3D Manager Relationship Functions

```javascript
// Colocalization
Ext.Manager3D_Coloc();                           // all pairs → Results table
Ext.Manager3D_Coloc2(obj1, obj2, pct1, pct2, surf_contact);  // specific pair

// Distances (see Distance section above)
Ext.Manager3D_Distance();                        // all pairs → Results table
Ext.Manager3D_Dist2(obj1, obj2, type, dist);    // specific pair

// Angles
Ext.Manager3D_Angle();                           // angle between 3 selected objects
```

### CLIJ2 Neighbor Analysis (GPU)

**Touch/adjacency matrices:**
- `Ext.CLIJ2_generateTouchMatrix(labels, dst)` — (n+1)x(n+1) binary touch matrix
- `Ext.CLIJ2_generateTouchCountMatrix(labels, dst)` — count of touching pixels per pair
- `Ext.CLIJ2_touchMatrixToAdjacencyMatrix(touch, adj)` — convert formats
- `Ext.CLIJ2_countTouchingNeighbors(touchMatrix, dst)` — neighbor count per label
- `Ext.CLIJ2_mergeTouchingLabels(labels, dst)` — merge touching labels

**Neighbor matrices:**
- `Ext.CLIJ2_generateNNearestNeighborsMatrix(distMatrix, dst, n)` — N nearest neighbor matrix
- `Ext.CLIJ2_generateProximalNeighborsMatrix(distMatrix, dst, maxDist)` — neighbors within radius

**Neighbor statistics maps** (replace each label with a statistic of its neighbors):
- `MeanOfTouchingNeighborsMap`, `MaximumOfTouchingNeighborsMap`, `MinimumOfTouchingNeighborsMap`
- `StandardDeviationOfTouchingNeighborsMap`, `ModeOfTouchingNeighborsMap`
- `MeanOfNNearestNeighborsMap`, `MaximumOfNNearestNeighborsMap`, etc.
- `MeanOfProximalNeighborsMap`, `MaximumOfProximalNeighborsMap`, etc.
- `TouchingNeighborCountMap` — map of how many neighbors each label touches
- `LabelProximalNeighborCountMap` — how many labels within radius
- `LabelOverlapCountMap` — overlap counts

**Mesh visualizations:**
- `Ext.CLIJ2_drawMeshBetweenTouchingLabels(labels, dst)` — mesh connecting touching labels
- `Ext.CLIJ2_drawMeshBetweenNNearestLabels(labels, dst, n)` — mesh connecting N nearest
- `Ext.CLIJ2_drawMeshBetweenProximalLabels(labels, dst, maxDist)` — mesh within radius
- `Ext.CLIJ2_drawDistanceMeshBetweenTouchingLabels(labels, dst)` — distance-weighted mesh

**Label operations:**
- `Ext.CLIJ2_excludeLabelsOnEdges(labels, dst)` — remove edge-touching labels
- `Ext.CLIJ2_excludeLabelsOnSurface(labels, dst)` — remove surface-touching labels (3D)
- `Ext.CLIJ2_excludeLabelsSubSurface(labels, dst)` — remove sub-surface labels
- `Ext.CLIJ2_excludeLabelsOutsideSizeRange(labels, dst, min, max)` — size filter
- `Ext.CLIJ2_excludeLabelsWithValuesOutOfRange(values, labels, dst, min, max)` — value filter
- `Ext.CLIJ2_dilateLabels(labels, dst, radius)` — grow labels
- `Ext.CLIJ2_erodeLabels(labels, dst, radius)` — shrink labels
- `Ext.CLIJ2_reduceLabelsToCentroids(labels, dst)` — single-pixel centroids
- `Ext.CLIJ2_reduceLabelsToLabelEdges(labels, dst)` — label outlines only
- `Ext.CLIJ2_closeIndexGapsInLabelMap(labels, dst)` — relabel sequentially
- `Ext.CLIJ2_labelVoronoiOctagon(labels, dst)` — extend labels via Voronoi
- `Ext.CLIJ2_maskedVoronoiLabeling(binary, labels, dst)` — Voronoi within mask
- `Ext.CLIJ2_detectLabelEdges(labels, dst)` — binary edge detection on labels

**Jaccard/overlap:**
- `Ext.CLIJ2_generateJaccardIndexMatrix(labels, dst)` — pairwise Jaccard indices
- `Ext.CLIJ2_generateBinaryOverlapMatrix(labels, dst)` — binary overlap matrix

---

## 9. DECONVOLUTION & PSF

| Command | Description |
|---------|-------------|
| Iterative Deconvolve 3D | Richardson-Lucy iterative deconvolution for 3D stacks |
| Diffraction PSF 3D | Generate theoretical 3D PSF based on microscope parameters |
| 3D Denoising - Planaria | Deep learning denoising (CSBDeep) |
| 3D Denoising - Tribolium | Deep learning denoising (CSBDeep) |
| Deconvolution - Microtubules | Deep learning deconvolution (CSBDeep) |
| Isotropic Reconstruction - Retina | Deep learning isotropic reconstruction (CSBDeep) |

---

## 10. BINARY OPERATIONS (3D)

### 3D ImageJ Suite

| Command | Description |
|---------|-------------|
| 3D Binary Close Labels | Morphological closing on labeled objects |
| 3D Binary Interpolate | Interpolate between binary slices |
| 3D Fill Holes | Fill internal holes in 3D binary |
| 3D Exclude Edges | Remove border-touching objects |
| 3D Merge Labels | Merge/stitch adjacent labeled objects |
| 3D Shuffle | Randomly shuffle object positions (for spatial stats null model) |
| 3D Crop | Crop around a specific 3D object |
| 3D Crop All | Crop each object to separate image |

### Other

| Command | Description |
|---------|-------------|
| Implicit Interpolate Binary | Interpolate between binary slices |

---

## 11. COLOCALIZATION (3D-AWARE)

| Command | Description |
|---------|-------------|
| 3D MultiColoc | Object-based 3D colocalization — volume overlap between all pairs |
| Coloc 2 | Pixel-based colocalization with statistics (Pearson, Manders, Li, etc.) |
| Colocalization Finder | Scatterplot-based colocalization |
| Colocalization Threshold | Automatic threshold for colocalization |
| Colocalization Test | Statistical significance of colocalization |
| Colocalization Object Counter | Count colocalized objects |
| Colocalization Image Creator | Generate colocalization map |

### 3D Manager Colocalization

```javascript
Ext.Manager3D_Coloc();   // all pairs: % overlap for each pair
Ext.Manager3D_Coloc2(obj1, obj2, pctA, pctB, surface_contact);
// pctA = % of obj1 that overlaps with obj2
// pctB = % of obj2 that overlaps with obj1
// surface_contact = contact surface area
```

---

## 12. 3D MANAGER — COMPLETE MACRO API

### Initialization & Lifecycle
```javascript
run("3D Manager");                    // open and init
Ext.Manager3D_Close();                // close
Ext.Manager3D_Reset();                // remove all objects
```

### Loading Objects
```javascript
Ext.Manager3D_Segment(lowThr, highThr);    // threshold + label current image
Ext.Manager3D_AddImage();                   // add objects from current labeled image
Ext.Manager3D_Load("file.zip");             // load from file
Ext.Manager3D_Save("file.zip");             // save to file
```

### Selection
```javascript
Ext.Manager3D_Select(index);          // select one object (0-based)
Ext.Manager3D_SelectAll();            // select all
Ext.Manager3D_SelectFor(start, end, step);  // select range
Ext.Manager3D_MultiSelect();          // enable multi-select mode
Ext.Manager3D_MonoSelect();           // single-select mode
Ext.Manager3D_DeselectAll();          // deselect all
Ext.Manager3D_GetSelected(list);      // get selected indices as ":" separated string
Ext.Manager3D_Count(nb);              // get object count
Ext.Manager3D_GetName(index, name);   // get object name
Ext.Manager3D_Rename("name");         // rename selected object
```

### Deletion & Drawing
```javascript
Ext.Manager3D_Delete();               // remove selected from list
Ext.Manager3D_Erase();                // remove + fill black in image
Ext.Manager3D_FillStack(r, g, b);     // fill selected in current stack with color
Ext.Manager3D_Fill3DViewer(r, g, b);  // draw selected in 3D Viewer with color
Ext.Manager3D_List();                 // list voxels of selected
```

### Measurement (to Results Table)
```javascript
Ext.Manager3D_Measure();              // geometry → Results table
Ext.Manager3D_Quantif();              // intensity → Results table
Ext.Manager3D_Distance();             // pairwise distances → Results table
Ext.Manager3D_Coloc();                // colocalization → Results table
Ext.Manager3D_Angle();                // angles between 3 objects
```

### Measurement (programmatic, no table)
```javascript
Ext.Manager3D_Measure3D(obj, "Vol", val);       // volume
Ext.Manager3D_Measure3D(obj, "Surf", val);      // surface
Ext.Manager3D_Measure3D(obj, "NbVox", val);     // voxel count
Ext.Manager3D_Measure3D(obj, "Comp", val);      // compactness
Ext.Manager3D_Measure3D(obj, "Feret", val);     // Feret diameter
Ext.Manager3D_Measure3D(obj, "Elon1", val);     // elongation
Ext.Manager3D_Measure3D(obj, "Elon2", val);     // flatness
Ext.Manager3D_Measure3D(obj, "DCMin", val);     // min center-to-contour
Ext.Manager3D_Measure3D(obj, "DCMax", val);     // max center-to-contour
Ext.Manager3D_Measure3D(obj, "DCMean", val);    // mean center-to-contour
Ext.Manager3D_Measure3D(obj, "DCSD", val);      // StdDev center-to-contour
Ext.Manager3D_Measure3D(obj, "RatioVolEll", val); // ellipsoid/object volume ratio

Ext.Manager3D_Quantif3D(obj, "IntDen", val);    // integrated density
Ext.Manager3D_Quantif3D(obj, "Mean", val);      // mean intensity
Ext.Manager3D_Quantif3D(obj, "Min", val);       // min intensity
Ext.Manager3D_Quantif3D(obj, "Max", val);       // max intensity
Ext.Manager3D_Quantif3D(obj, "Sigma", val);     // StdDev intensity

Ext.Manager3D_Centroid3D(obj, cx, cy, cz);      // geometric center
Ext.Manager3D_MassCenter3D(obj, cmx, cmy, cmz); // intensity-weighted center
Ext.Manager3D_Feret1(obj, fx, fy, fz);          // Feret point 1
Ext.Manager3D_Feret2(obj, fx, fy, fz);          // Feret point 2
Ext.Manager3D_Bounding3D(obj, x0, x1, y0, y1, z0, z1);  // bounding box

Ext.Manager3D_Dist2(o1, o2, "cc", dist);        // center-to-center distance
Ext.Manager3D_Dist2(o1, o2, "bb", dist);        // border-to-border distance
Ext.Manager3D_Closest(obj, "cc", closest);       // nearest neighbor (center)
Ext.Manager3D_Closest(obj, "bb", closest);       // nearest neighbor (border)
Ext.Manager3D_BorderVoxel(o1, o2, bx, by, bz);  // closest border point
Ext.Manager3D_Coloc2(o1, o2, pct1, pct2, surf); // colocalization between pair
```

### Saving Results
```javascript
Ext.Manager3D_SaveResult("M", "measure.csv");    // save Measure results
Ext.Manager3D_SaveResult("Q", "quantif.csv");    // save Quantif results
Ext.Manager3D_SaveResult("D", "distance.csv");   // save Distance results
Ext.Manager3D_SaveResult("C", "coloc.csv");      // save Coloc results
Ext.Manager3D_SaveResult("V", "voxels.csv");     // save Voxels
Ext.Manager3D_SaveResult("A", "all.csv");        // save all

Ext.Manager3D_CloseResult("M");                  // close Measure window
Ext.Manager3D_CloseResult("Q");                  // close Quantif window
// etc. — first letter of the window type
```

---

## 13. MISCELLANEOUS 3D TOOLS

| Command | Description |
|---------|-------------|
| 3D Draw Line | Draw a 3D line in a stack |
| 3D Draw Shape | Draw geometric shapes (sphere, cube, etc.) in 3D |
| 3D Draw Rois | Draw ROI contours on each slice |
| 3D Draw Montage (beta) | Create montage from 3D objects |
| Voxelizer | Convert surface meshes to voxel representation |
| 3D Optimisation | Hungarian algorithm optimization (for tracking/matching) |
| Fast FFT (2D/3D) | Fast Fourier Transform in 3D |
| Flip Z | Reverse slice order |
| Re-order Hyperstack | Rearrange dimension order |

### CLIJ2 Miscellaneous 3D

| Function | Description |
|----------|-------------|
| `Ext.CLIJ2_create3D(dst, w, h, d, bitdepth)` | Create empty 3D image on GPU |
| `Ext.CLIJ2_flip3D(src, dst, flipX, flipY, flipZ)` | Flip 3D image |
| `Ext.CLIJ2_crop3D(src, dst, x, y, z, w, h, d)` | Crop 3D region |
| `Ext.CLIJ2_paste3D(src, dst, x, y, z)` | Paste into 3D image |
| `Ext.CLIJ2_downsample3D(src, dst, fx, fy, fz)` | Downsample 3D |
| `Ext.CLIJ2_combineHorizontally(src1, src2, dst)` | Combine stacks side by side |
| `Ext.CLIJ2_combineVertically(src1, src2, dst)` | Combine stacks top to bottom |
| `Ext.CLIJ2_concatenateStacks(src1, src2, dst)` | Concatenate along Z |
| `Ext.CLIJ2_setRampZ(dst)` | Fill with Z-coordinate values |
| `Ext.CLIJ2_countNonZeroVoxels3DSphere(src, dst, rx, ry, rz)` | Local non-zero voxel count |
| `Ext.CLIJ2_imageToStack(src, dst, nSlices)` | Replicate 2D to 3D |
| `Ext.CLIJ2_reduceStack(src, dst, factor, offset)` | Subsample slices |
| `Ext.CLIJ2_stackToTiles(src, dst, tilesX, tilesY)` | Unstack to tile grid |
| `Ext.CLIJ2_maskStackWithPlane(src3d, mask2d, dst)` | Mask 3D with 2D |
| `Ext.CLIJ2_multiplyStackWithPlane(src3d, plane2d, dst)` | Multiply 3D by 2D |

---

## QUICK REFERENCE: Which Tool for Which Task

| Task | Best Tool | Alternative |
|------|-----------|-------------|
| Segment nuclei in 3D | 3D Iterative Thresholding / StarDist | 3D Watershed Split |
| Segment spots/puncta | 3D Spot Segmentation / 3D Maxima Finder | CLIJ2 Voronoi-Otsu-Labeling |
| Split touching objects | 3D Watershed Split | Marker-controlled Watershed (MorphoLibJ) |
| Measure 3D volume | 3D Volume / 3D Manager Measure3D("Vol") | MorphoLibJ Analyze Regions 3D |
| Measure 3D shape | 3D Compactness + 3D Ellipsoid Fitting | MorphoLibJ Analyze Regions 3D (Sphericity) |
| 3D distance between objects | 3D Distances Closest / Manager3D_Dist2 | CLIJ2 generateDistanceMatrix |
| Spatial statistics | Spatial Analysis 2D/3D | CLIJ2 neighbor analysis maps |
| 3D colocalization (object) | 3D MultiColoc / Manager3D_Coloc | Colocalization Object Counter |
| 3D colocalization (pixel) | Coloc 2 | Colocalization Threshold |
| 3D filtering (fast) | CLIJ2 (GPU) | 3D Fast Filters (mcib3d) |
| 3D morphological ops | MorphoLibJ Morphological Filters (3D) | CLIJ2 opening/closing |
| Skeleton/branch analysis | Skeletonize (2D/3D) + Analyze Skeleton | — |
| 3D rendering | 3D Viewer (TCP: `python ij.py 3d`) | Volume Viewer |
| Drift correction | Correct 3D drift | CLIJ2 drift correction |
| 3D deconvolution | Iterative Deconvolve 3D + Diffraction PSF 3D | CSBDeep models |
| Local thickness | Local Thickness (complete process) | 3D Distance Map |
| Count 3D objects | 3D Objects Counter | Connected Components (MorphoLibJ/CLIJ2) |
# 3D Spatial Analysis Reference for ImageJAI Agent

Practical workflows and working code for 3D spatial analysis in ImageJ/Fiji.
Every macro example below can be sent via `python ij.py macro '...'`.

---

## Table of Contents

1. [Foundation: 3D Manager Macro API](#1-foundation-3d-manager-macro-api)
2. [Count Cells in a Z-Stack and Measure 3D Positions](#2-count-cells-in-a-z-stack-and-measure-3d-positions)
3. [Nearest Neighbor Distances Between Two Populations](#3-nearest-neighbor-distances-between-two-populations)
4. [3D Colocalization Between Channels](#4-3d-colocalization-between-channels)
5. [Neurite Branching Analysis in 3D](#5-neurite-branching-analysis-in-3d)
6. [Cell-to-Surface Distance (e.g. Distance to Ventricle)](#6-cell-to-surface-distance)
7. [3D Voronoi / Territory Analysis](#7-3d-voronoi--territory-analysis)
8. [Cluster Analysis of 3D Distributions (Spatial Statistics)](#8-cluster-analysis-of-3d-distributions)
9. [Reusable Patterns](#9-reusable-patterns)
10. [Plugin Availability](#10-plugin-availability)

---

## 1. Foundation: 3D Manager Macro API

The 3D ImageJ Suite by Thomas Boudier provides the `Ext.Manager3D_*` macro
extension functions. These are the building blocks for all 3D spatial analysis.

### Setup (required at top of every macro using 3D Manager)

```javascript
run("3D Manager");
Ext.Manager3D_AddImage();
Ext.Manager3D_Count(nb);
print("Found " + nb + " objects");
```

### Complete Ext.Manager3D_* Function Reference

Extracted from the source code (RoiManager3D_2.java):

#### Object Management
| Function | Syntax | Description |
|----------|--------|-------------|
| AddImage | `Ext.Manager3D_AddImage();` | Load labeled image into 3D Manager |
| Count | `Ext.Manager3D_Count(nb);` | Get number of objects (output: nb) |
| Select | `Ext.Manager3D_Select(index);` | Select object by index |
| SelectAll | `Ext.Manager3D_SelectAll();` | Select all objects |
| DeselectAll | `Ext.Manager3D_DeselectAll();` | Deselect all |
| MonoSelect | `Ext.Manager3D_MonoSelect();` | Single-select mode |
| MultiSelect | `Ext.Manager3D_MultiSelect();` | Multi-select mode |
| GetName | `Ext.Manager3D_GetName(index, name);` | Get object name (output: name) |
| GetSelected | `Ext.Manager3D_GetSelected(indices);` | Get selected indices as string |
| Delete | `Ext.Manager3D_Delete();` | Delete selected object |
| Reset | `Ext.Manager3D_Reset();` | Clear all objects |
| Rename | `Ext.Manager3D_Rename("new_name");` | Rename selected object |
| Merge | `Ext.Manager3D_Merge();` | Merge selected objects |
| Label | `Ext.Manager3D_Label();` | Create label image |
| Close | `Ext.Manager3D_Close();` | Close 3D Manager |
| Load | `Ext.Manager3D_Load("/path/to/objects.zip");` | Load objects from file |
| Save | `Ext.Manager3D_Save("/path/to/objects.zip");` | Save objects to file |

#### Geometry Measurements — Ext.Manager3D_Measure3D
```javascript
Ext.Manager3D_Measure3D(index, "parameter", result);
```

| Parameter | Description |
|-----------|-------------|
| `"Vol"` | Volume in calibrated units |
| `"NbVox"` | Volume in voxels |
| `"Surf"` | Surface area in calibrated units |
| `"Comp"` | Compactness |
| `"Spher"` | Sphericity |
| `"Feret"` | Feret diameter |
| `"Elon1"` | Main elongation |
| `"Elon2"` | Median elongation |
| `"DCMin"` | Min distance from center to surface |
| `"DCMax"` | Max distance from center to surface |
| `"DCMean"` | Mean distance from center to surface |
| `"DCSD"` | Std dev of center-to-surface distance |
| `"RatioVolEll"` | Ratio of volume to fitted ellipsoid |

#### Intensity Measurements — Ext.Manager3D_Quantif3D
```javascript
Ext.Manager3D_Quantif3D(index, "parameter", result);
```

| Parameter | Description |
|-----------|-------------|
| `"IntDen"` | Integrated density |
| `"Mean"` | Mean intensity |
| `"Min"` | Minimum intensity |
| `"Max"` | Maximum intensity |
| `"Sigma"` | Standard deviation of intensity |

#### Position Functions

```javascript
// Geometric centroid (pixel coordinates)
Ext.Manager3D_Centroid3D(index, cx, cy, cz);

// Intensity-weighted center of mass (pixel coordinates)
Ext.Manager3D_MassCenter3D(index, mx, my, mz);

// Bounding box
Ext.Manager3D_Bounding3D(index, xmin, xmax, ymin, ymax, zmin, zmax);

// Feret endpoints (pixel coordinates)
Ext.Manager3D_Feret1(index, fx, fy, fz);
Ext.Manager3D_Feret2(index, fx, fy, fz);
```

#### Distance Functions

```javascript
// Distance between two specific objects
Ext.Manager3D_Dist2(indexA, indexB, "type", distance);
```

Distance type options:
| Type | Description |
|------|-------------|
| `"cc"` | Center-to-center distance |
| `"bb"` | Border-to-border distance |
| `"c1b2"` | Center of obj1 to border of obj2 |
| `"c2b1"` | Center of obj2 to border of obj1 |
| `"r1c2"` | Radius of obj1 in direction of obj2 center |
| `"r2c1"` | Radius of obj2 in direction of obj1 center |
| `"ex2c1"` | Excentricity: dist(c1,c2) / radius(obj1→obj2) |
| `"ex1c2"` | Excentricity: dist(c2,c1) / radius(obj2→obj1) |

```javascript
// Find closest object to a given object
// Returns the INDEX of the closest object
Ext.Manager3D_Closest(index, "cc", closestIdx);   // by center-center
Ext.Manager3D_Closest(index, "bb", closestIdx);   // by border-border

// Find k-th closest object
Ext.Manager3D_ClosestK(index, k, "cc", closestIdx);
Ext.Manager3D_ClosestK(index, k, "bb", closestIdx);
```

```javascript
// Border voxel in direction of another object
Ext.Manager3D_RadiusBorderVoxel(indexA, indexB, direction, bx, by, bz);
// direction=1: from A toward B; direction=2: from B toward A

// Border voxel between two objects (closest contact point)
Ext.Manager3D_BorderVoxel(indexA, indexB, bx, by, bz);
```

#### Colocalization
```javascript
// Overlap between two objects
Ext.Manager3D_Coloc2(indexA, indexB, pctA, pctB, surfContact);
// pctA: % of A overlapping B
// pctB: % of B overlapping A
// surfContact: number of surface contact voxels
```

#### Batch Operations (no parameters — operate on all objects)
```javascript
Ext.Manager3D_Measure();    // Run all geometry measurements → results table
Ext.Manager3D_Quantif();    // Run all intensity measurements → results table
Ext.Manager3D_Distance();   // Compute all pairwise distances → results table
Ext.Manager3D_Coloc();      // Compute all pairwise colocalization → results table
Ext.Manager3D_Angle();      // Compute angles between objects → results table
Ext.Manager3D_List();       // List all voxels per object
```

#### Saving Results
```javascript
Ext.Manager3D_CloseResult("M");  // Close measure results window
Ext.Manager3D_CloseResult("Q");  // Close quantif results window
Ext.Manager3D_SaveResult("M", "/path/to/measures.csv");
Ext.Manager3D_SaveResult("Q", "/path/to/quantif.csv");
```

#### Visualization
```javascript
Ext.Manager3D_FillStack(R, G, B);           // Color selected object in stack
Ext.Manager3D_FillStack2(obj, R, G, B);     // Color specific object
Ext.Manager3D_Fill3DViewer(R, G, B);        // Color in 3D Viewer
Ext.Manager3D_Segment(low, high);           // Segment by threshold range
Ext.Manager3D_Rotate(rx, ry, rz);          // Rotate 3D view
```

---

## 2. Count Cells in a Z-Stack and Measure 3D Positions

### Method A: 3D Objects Counter (simplest)

```javascript
// Step 1: Pre-process
run("Duplicate...", "title=seg duplicate");
run("Gaussian Blur 3D...", "x=2 y=2 z=1");

// Step 2: Threshold
setAutoThreshold("Otsu dark stack");
run("Convert to Mask", "method=Otsu background=Dark calculate");

// Step 3: 3D Objects Counter
run("3D Objects Counter", "threshold=128 min.=200 max.=999999 objects surfaces statistics summary");

// Results appear in "Statistics for ..." window with columns:
//   Volume, Surface, IntDen, Mean, StdDev, Centroid X/Y/Z,
//   Center of Mass X/Y/Z, Bounding Box
```

### Method B: 3D Manager (more control, scriptable)

```javascript
// Step 1: Segment (produce binary or label image)
run("Duplicate...", "title=seg duplicate");
run("Gaussian Blur 3D...", "x=2 y=2 z=1");
setAutoThreshold("Otsu dark stack");
run("Convert to Mask", "method=Otsu background=Dark calculate");

// Step 2: Load into 3D Manager
run("3D Manager");
Ext.Manager3D_Segment(128, 255);  // or use Ext.Manager3D_AddImage() for label images
Ext.Manager3D_Count(nb);
print("Found " + nb + " cells");

// Step 3: Extract centroids
for (i = 0; i < nb; i++) {
    Ext.Manager3D_Centroid3D(i, cx, cy, cz);
    Ext.Manager3D_Measure3D(i, "Vol", vol);
    print("Cell " + i + ": pos=(" + cx + "," + cy + "," + cz + ") vol=" + vol);
}

// Step 4: Save all measurements at once
Ext.Manager3D_Measure();  // populates results table
Ext.Manager3D_SaveResult("M", "/path/to/cell_positions.csv");
```

### Method C: 3D Spots Segmentation (for small bright spots like foci)

```javascript
// Better for small round objects (foci, vesicles, puncta)
// Uses seed-based watershed — prevents merging
run("3D Spots Segmentation", "seeds_threshold=500 local_background=200 radius_0=2 radius_1=4 radius_2=6 weighting=0.50 seeds_method=[Local Maxima] spots_method=[Block] watershed");
```

### Method D: StarDist 3D (deep learning, best for nuclei)

```javascript
// Best for densely packed nuclei — handles touching/overlapping
run("Command From Macro", "command=[de.csbdresden.stardist.StarDist3D], args=['input':'seg', 'modelChoice':'Versatile (fluorescent nuclei 3D)', 'normalizeInput':'true', 'percentileBottom':'1.0', 'percentileTop':'99.8', 'nTiles':'1', 'excludeBoundary':'2'], process=[false]");
```

---

## 3. Nearest Neighbor Distances Between Two Populations

This is the classic question: "How far are objects in channel A from objects
in channel B?" (e.g., distance from synaptic vesicles to active zones).

### Method A: DiAna Plugin (purpose-built, recommended)

DiAna (Distance Analysis) by Gilles & Bhatt-Grover handles two-population
distance analysis natively with proper 3D segmentation and distance computation.

```javascript
// Step 1: Segment both channels separately
run("DiAna_Segment", "img=channelA.tif filter=median rad=1.0 thr=500-0-200-true-false");
run("DiAna_Segment", "img=channelB.tif filter=median rad=1.0 thr=300-0-100-true-false");

// Step 2: Analyze distances between populations
run("DiAna_Analyse", "img1=channelA.tif img2=channelB.tif lab1=channelA-labelled.tif lab2=channelB-labelled.tif distc=50.0 kclosest=1 dista=50.0 measure");

// Options:
//   coloc      — compute colocalization
//   distc=50.0 — max center-center distance to report
//   adja       — adjacency analysis
//   kclosest=1 — find k nearest neighbors
//   dista=50.0 — max distance for adjacency
//   measure    — output per-object measurements
```

### Method B: 3D Manager with Two Populations (manual approach)

When you need full control over the distance computation:

```javascript
// === APPROACH: Load both populations into one 3D Manager, compute cross-distances ===

// Segment channel A
selectWindow("channelA");
run("Duplicate...", "title=segA duplicate");
run("Gaussian Blur 3D...", "x=2 y=2 z=1");
setAutoThreshold("Otsu dark stack");
run("Convert to Mask", "method=Otsu background=Dark calculate");

// Segment channel B
selectWindow("channelB");
run("Duplicate...", "title=segB duplicate");
run("Gaussian Blur 3D...", "x=2 y=2 z=1");
setAutoThreshold("Li dark stack");
run("Convert to Mask", "method=Li background=Dark calculate");

// Count objects in each
selectWindow("segA");
run("3D Objects Counter", "threshold=128 min.=100 max.=999999 objects");
rename("labelsA");

selectWindow("segB");
run("3D Objects Counter", "threshold=128 min.=50 max.=999999 objects");
rename("labelsB");
```

Then in a second macro pass, use the 3D Manager to compute cross-distances:

```javascript
// Load population A centroids from 3D Objects Counter results
// Then compute NND to population B using Euclidean distance
// from each A centroid to all B centroids

// Extract from Results tables:
// "Statistics for segA" has Centroid X/Y/Z columns
// "Statistics for segB" has Centroid X/Y/Z columns

// Agent can parse these tables with python results_parser.py
// and compute NND in Python (more flexible than macro for cross-population):

// Python pseudocode:
// for each objectA:
//     min_dist = infinity
//     for each objectB:
//         d = sqrt((ax-bx)^2 + (ay-by)^2 + (az-bz)^2)
//         min_dist = min(min_dist, d)
//     nnd_A_to_B.append(min_dist)
```

### Method C: 3D Manager Macro (single population NND)

For nearest neighbor within a single population:

```javascript
run("3D Manager");
Ext.Manager3D_AddImage();
Ext.Manager3D_Count(nb);

// For each object, find its nearest neighbor
for (i = 0; i < nb; i++) {
    Ext.Manager3D_Closest(i, "cc", closestIdx);
    Ext.Manager3D_Dist2(i, closestIdx, "cc", nndCC);
    Ext.Manager3D_Dist2(i, closestIdx, "bb", nndBB);
    Ext.Manager3D_Centroid3D(i, cx, cy, cz);
    print(i + "\t" + closestIdx + "\t" + nndCC + "\t" + nndBB + "\t" + cx + "\t" + cy + "\t" + cz);
}

// For k-th nearest neighbor:
k = 3;
for (i = 0; i < nb; i++) {
    Ext.Manager3D_ClosestK(i, k, "cc", kClosestIdx);
    Ext.Manager3D_Dist2(i, kClosestIdx, "cc", dist);
    print("Object " + i + " -> " + k + "th nearest = " + kClosestIdx + " dist=" + dist);
}
```

---

## 4. 3D Colocalization Between Channels

### Method A: Coloc 2 (pixel-based, works on z-stacks natively)

```javascript
// Coloc 2 operates on the FULL 3D stack — no need to flatten
// Requires two single-channel images

// Split channels if multi-channel
run("Split Channels");

// Run Coloc 2
run("Coloc 2", "channel_1=C1-image.tif channel_2=C2-image.tif roi_or_mask=<None> threshold_regression=Costes li_histogram_channel_1 li_histogram_channel_2 li_icq spearman's_rank_correlation manders'_correlation kendall's_tau_rank_correlation 2d_intensity_histogram costes'_significance_test psf=3 costes_randomisations=10");

// Key outputs:
//   Pearson's R (whole image and above threshold)
//   Manders' M1, M2 (fraction of A colocalizing with B and vice versa)
//   Costes' significance test (p-value for statistical significance)
//   Li's ICQ (intensity correlation quotient, -0.5 to +0.5)
```

### Method B: Object-based 3D Colocalization (3D Manager)

Better for discrete objects (foci, vesicles, organelles):

```javascript
// Segment both channels into label images, then use 3D Manager

// Load population A
selectWindow("labelsA");
run("3D Manager");
Ext.Manager3D_AddImage();
Ext.Manager3D_Count(nbA);

// For each pair, check overlap
for (i = 0; i < nbA; i++) {
    for (j = 0; j < nbA; j++) {
        Ext.Manager3D_Coloc2(i, j, pctA, pctB, surfContact);
        if (pctA > 0) {
            print("Object " + i + " overlaps " + j + ": " + pctA + "% / " + pctB + "% surface=" + surfContact);
        }
    }
}

// Or use batch colocalization:
Ext.Manager3D_Coloc();  // all-vs-all → results table
```

### Method C: DiAna for Object-based 3D Colocalization + Distances

```javascript
// DiAna combines colocalization AND distance in one call
run("DiAna_Analyse", "img1=chA.tif img2=chB.tif lab1=labA.tif lab2=labB.tif coloc distc=10.0 kclosest=1 measure");

// Output includes:
//   - Which objects overlap
//   - Volume of overlap
//   - Center-center and border-border distances
//   - Per-object measurements
```

### Method D: BIOP-JACoP (batch z-stack colocalization)

For automated batch processing of multiple z-stacks:

```javascript
// BIOP-JACoP streamlines Manders coefficient calculation
// Automatically trims empty z-slices before analysis
run("BIOP JACoP", "channel_a=1 channel_b=2 threshold_a=Otsu threshold_b=Otsu get_manders");
```

---

## 5. Neurite Branching Analysis in 3D

### Complete workflow: Skeletonize + AnalyzeSkeleton

```javascript
// Step 1: Pre-process (fluorescent neuron in z-stack)
run("Duplicate...", "title=neuron duplicate");
run("Gaussian Blur 3D...", "x=1 y=1 z=0.5");

// Step 2: Threshold
setAutoThreshold("Li dark stack");
run("Convert to Mask", "method=Li background=Dark calculate");

// Step 3: Clean up binary
run("Fill Holes", "stack");
run("Close-", "stack");  // morphological closing to bridge small gaps

// Step 4: Skeletonize in 3D
run("Skeletonize (2D/3D)");

// Step 5: Analyze skeleton
run("Analyze Skeleton (2D/3D)", "prune=[none] calculate show");

// Results table columns:
//   # Branches, # Junctions, # End-point voxels,
//   # Junction voxels, # Slab voxels, # Triple points,
//   # Quadruple points, Average Branch Length, Maximum Branch Length,
//   Longest Shortest Path

// "Branch information" table (if "show" is checked):
//   Skeleton ID, Branch #, Branch length,
//   V1 x/y/z, V2 x/y/z, Euclidean distance
//   (V1/V2 are branch endpoints — can be junction or endpoint)
```

### Pruning options for AnalyzeSkeleton
```javascript
// Remove short spurious branches:
run("Analyze Skeleton (2D/3D)", "prune=[shortest branch] prune_0 calculate show");

// Options for "prune":
//   [none]              — keep all branches
//   [shortest branch]   — remove shortest branch at each cycle
//   [lowest intensity voxel]  — remove based on original image intensity
//   [lowest intensity branch] — remove entire branch with lowest mean intensity
```

### Sholl Analysis in 3D (requires traced neuron)
```javascript
// After tracing with SNT or having a binary skeleton:
// Place center point at soma
makePoint(soma_x, soma_y);
run("Sholl Analysis...", "starting=10 ending=200 radius_step=10 #_samples=1 integration=Mean enclosing=1 #_primary=[] infer fit polynomial=[Best fitting degree] most linear kurtosis semi-log normalizer=Area/Volume create save");

// Outputs: Sholl profile (intersections vs distance from soma)
```

---

## 6. Cell-to-Surface Distance

Measuring how far each cell is from a reference surface (ventricle wall,
tissue boundary, blood vessel, etc.).

### Method A: 3D Distance Map (EDT)

```javascript
// Step 1: Create binary mask of the reference surface/boundary
// e.g., segment the ventricle lumen
selectWindow("ventricle_channel");
run("Duplicate...", "title=boundary duplicate");
run("Gaussian Blur 3D...", "x=3 y=3 z=1");
setAutoThreshold("Otsu dark stack");
run("Convert to Mask", "method=Otsu background=Dark calculate");

// Step 2: Compute 3D Euclidean Distance Map
// Every voxel gets the distance to the nearest boundary voxel
run("3D Distance Map", "map=EDT image=boundary inverse");
rename("distance_map");

// The "inverse" flag computes distance OUTSIDE the mask
// Without inverse: distance inside the mask to its border

// Step 3: Segment cells in the other channel
selectWindow("cell_channel");
run("Duplicate...", "title=cells duplicate");
run("Gaussian Blur 3D...", "x=2 y=2 z=1");
run("3D Objects Counter", "threshold=500 min.=200 max.=999999 objects statistics");

// Step 4: Measure distance at each cell's centroid
// Use the 3D Objects Counter centroids, then sample the distance map
// at those coordinates

// In macro: read centroid from results, sample distance map
selectWindow("distance_map");
for (i = 0; i < nResults; i++) {
    cx = getResult("X", i);
    cy = getResult("Y", i);
    cz = getResult("Z", i);
    // Convert calibrated to pixel coordinates if needed
    setSlice(round(cz));
    dist = getPixel(round(cx), round(cy));
    setResult("DistToSurface", i, dist);
}
updateResults();
```

### Method B: EVF (Eroded Volume Fraction) for Layer Analysis

EVF normalizes the distance map so that each "layer" encloses equal volume.
This is the proper way to ask "are cells enriched near the surface?"

```javascript
// Step 1: Create binary mask of the reference structure
// (same as above)

// Step 2: Compute EVF
run("3D Distance Map EVF");
// The resulting image has values 0-1:
//   0 = at the border of the structure
//   1 = at the deepest interior point
// Each iso-value layer encloses equal volume

// Step 3: Sample EVF at each cell centroid
// Cells with low EVF values are near the surface
// Cells with high EVF values are deep in the interior

// Step 4: Histogram of EVF values at cell positions
// Compare to uniform distribution to test for spatial preference
// If cells cluster near low EVF → enriched near surface
// If uniform → random distribution within structure
```

### Method C: Using MorphoLibJ Geodesic Distance Map

For distance measurements that must follow the tissue geometry (e.g., around
folds), geodesic distance is more accurate than Euclidean:

```javascript
// Geodesic distance follows the mask, doesn't cut through tissue
run("Geodesic Distance Map", "marker=boundary_mask mask=tissue_mask distances=[Chessknight (5,7,11)] output=[32 bits] normalize");

// Then sample at cell centroids as above
```

---

## 7. 3D Voronoi / Territory Analysis

Voronoi tessellation assigns each voxel to the nearest object center, defining
"territories" or "domains" for each cell.

### Method A: Label-based Voronoi (MorphoLibJ)

```javascript
// Step 1: Create seed/label image from cell centroids
// Use 3D Objects Counter to get centroids, then create point image

// Step 2: Dilate labels until they fill the space
run("Dilate Labels", "radius=200");
// MorphoLibJ's "Dilate Labels" expands each label by equal amounts
// until labels meet — this IS a Voronoi tessellation

// Step 3: Measure territory volumes
run("Analyze Regions 3D", "volume surface_area sphericity");
// Each label = one territory. Volume = territory size.
```

### Method B: 2D Voronoi on Max Projection (quick approximation)

```javascript
// For quick 2D territory analysis:
run("Z Project...", "projection=[Max Intensity]");
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Watershed");  // separate touching cells
run("Voronoi");    // built-in binary Voronoi
// Result: Voronoi diagram where each cell has its territory
```

### Method C: Delaunay/Voronoi from Point ROIs

```javascript
// From a set of point ROIs (e.g., cell centroids):
run("Delaunay Voronoi", "mode=Voronoi");
// Creates Voronoi diagram from point selections
// Useful for 2D; 3D requires the label dilation approach
```

### Territory Size Distribution Analysis

```javascript
// After Voronoi tessellation (Method A):
// Measure each territory volume
run("3D Manager");
Ext.Manager3D_AddImage();
Ext.Manager3D_Count(nb);

for (i = 0; i < nb; i++) {
    Ext.Manager3D_Measure3D(i, "Vol", vol);
    print("Territory " + i + " volume = " + vol);
}

// Compare territory sizes to test for regularity:
// - Regular spacing → similar territory sizes (low CV)
// - Random spacing → variable territory sizes (higher CV)
// - Clustered → very unequal sizes (high CV, some very large)
```

---

## 8. Cluster Analysis of 3D Distributions

### Method A: Spatial Statistics 2D/3D Plugin (Andrey et al.)

This plugin tests whether a point pattern (object positions) deviates from
complete spatial randomness (CSR). It computes G, F, and H functions.

```javascript
// Input: two binary images
//   1. Mask of the containing structure (reference volume)
//   2. Binary spots image (object positions)

// Step 1: Create the mask (e.g., whole tissue volume)
selectWindow("tissue");
run("Duplicate...", "title=mask duplicate");
setAutoThreshold("Otsu dark stack");
run("Convert to Mask", "method=Otsu background=Dark calculate");

// Step 2: Create spots image (centroids of cells)
// From 3D Objects Counter centroids, create a binary image
// with single voxels at each centroid position

// Step 3: Run spatial statistics
run("Spatial Statistics 2D/3D", "mask=mask spots=centroids nb_evaluations=1000 nb_random=100 hardcore=5 confidence=0.95");

// Outputs:
//   G-function plot: nearest-neighbor distance distribution
//     - Blue line ABOVE red envelope → clustering (cells closer than random)
//     - Blue line BELOW red envelope → regularity (cells more spaced than random)
//     - Blue line WITHIN envelope → consistent with random
//
//   F-function plot: empty space function
//     - Blue BELOW envelope → clustering (more empty space than random)
//     - Blue ABOVE envelope → regularity (less empty space)
//
//   SDI (Spatial Distribution Index):
//     - p < 0.05 → significantly non-random
//     - SDI near 0 → clustered
//     - SDI near 1 → regular
//     - SDI near 0.5 → random
```

### Method B: DBSCAN-like Clustering from NND

Use nearest neighbor distances to identify clusters:

```javascript
// Step 1: Get all object centroids from 3D Manager
run("3D Manager");
Ext.Manager3D_AddImage();
Ext.Manager3D_Count(nb);

// Step 2: Build NND distribution
for (i = 0; i < nb; i++) {
    Ext.Manager3D_Closest(i, "cc", closestIdx);
    Ext.Manager3D_Dist2(i, closestIdx, "cc", nnd);
    setResult("NND", i, nnd);
    Ext.Manager3D_Centroid3D(i, cx, cy, cz);
    setResult("X", i, cx);
    setResult("Y", i, cy);
    setResult("Z", i, cz);
}
updateResults();

// Step 3: Analyze NND distribution
// Agent computes in Python:
//   - Mean NND, median NND, CV of NND
//   - Compare to expected NND for random distribution:
//     Expected NND (random) = 0.554 * (V/N)^(1/3)
//     where V = volume, N = number of objects
//   - Clark-Evans ratio R = observed_mean_NND / expected_mean_NND
//     R < 1 → clustered
//     R = 1 → random
//     R > 1 → regular/dispersed
```

### Method C: MosaicIA (Interaction Analysis)

The MosaicIA plugin provides rigorous spatial interaction analysis
between two point patterns:

```javascript
// Tests whether pattern A and B show attraction, repulsion, or independence
run("Interaction Analysis", "pattern_a=channelA pattern_b=channelB significance=0.05");
// Uses Gibbs point process model
// Output: interaction potential, significance, range of interaction
```

---

## 9. Reusable Patterns

### Pattern: Segment → Label → Measure → Export

This is the universal skeleton for any 3D analysis:

```javascript
// 1. PRE-PROCESS
run("Duplicate...", "title=processing duplicate");
run("Gaussian Blur 3D...", "x=2 y=2 z=1");

// 2. SEGMENT (choose one)
// Option A: threshold
setAutoThreshold("Otsu dark stack");
run("Convert to Mask", "method=Otsu background=Dark calculate");
// Option B: 3D Objects Counter (segment + label in one step)
run("3D Objects Counter", "threshold=T min.=V max.=999999 objects");
// Option C: StarDist 3D
// Option D: 3D Spots Segmentation

// 3. LABEL (if not already labeled)
run("Connected Components Labeling", "connectivity=26 type=[16 bits]");

// 4. LOAD into 3D Manager
run("3D Manager");
Ext.Manager3D_AddImage();
Ext.Manager3D_Count(nb);

// 5. MEASURE
Ext.Manager3D_Measure();   // geometry
Ext.Manager3D_Quantif();   // intensity (on original image)

// 6. EXPORT
Ext.Manager3D_SaveResult("M", "/path/to/geometry.csv");
Ext.Manager3D_SaveResult("Q", "/path/to/intensity.csv");
```

### Pattern: Two-Channel Cross-Analysis

```javascript
// Split channels
run("Split Channels");

// Segment each channel
selectWindow("C1-original");
// ... threshold/segment → label image "labelsA"

selectWindow("C2-original");
// ... threshold/segment → label image "labelsB"

// Option 1: DiAna for complete cross-analysis
run("DiAna_Analyse", "img1=C1-original img2=C2-original lab1=labelsA lab2=labelsB coloc distc=50 kclosest=3 measure");

// Option 2: Manual with 3D Manager
// Load A, record centroids
// Load B, record centroids
// Compute cross-distances in Python
```

### Pattern: Distance Map Sampling

For measuring any spatial property at object locations:

```javascript
// 1. Create the spatial map (distance, EVF, intensity, etc.)
run("3D Distance Map", "map=EDT image=reference");
rename("spatial_map");

// 2. Segment objects of interest
// ... get centroid list (X, Y, Z)

// 3. Sample the map at each centroid
selectWindow("spatial_map");
for (i = 0; i < nResults; i++) {
    x = getResult("X", i);
    y = getResult("Y", i);
    z = getResult("Z", i);
    setSlice(round(z));
    val = getPixel(round(x), round(y));
    setResult("SpatialValue", i, val);
}
updateResults();
```

### Pattern: NND for Any Population

```javascript
// Generic nearest neighbor distance extraction
run("3D Manager");
Ext.Manager3D_AddImage();
Ext.Manager3D_Count(nb);

// Write results to file via macro print-to-file
path = "/path/to/nnd_results.csv";
f = File.open(path);
print(f, "ObjectID,CentroidX,CentroidY,CentroidZ,NearestNeighborID,NND_CenterCenter,NND_BorderBorder,Volume,Sphericity");

for (i = 0; i < nb; i++) {
    Ext.Manager3D_Centroid3D(i, cx, cy, cz);
    Ext.Manager3D_Closest(i, "cc", closestCC);
    Ext.Manager3D_Dist2(i, closestCC, "cc", nndCC);
    Ext.Manager3D_Closest(i, "bb", closestBB);
    Ext.Manager3D_Dist2(i, closestBB, "bb", nndBB);
    Ext.Manager3D_Measure3D(i, "Vol", vol);
    Ext.Manager3D_Measure3D(i, "Spher", sph);
    print(f, "" + i + "," + cx + "," + cy + "," + cz + "," + closestCC + "," + nndCC + "," + nndBB + "," + vol + "," + sph);
}
File.close(f);
```

### Pattern: Check Calibration Before 3D Analysis

**Critical** — wrong calibration means wrong volumes and distances:

```javascript
// Always check calibration first
getVoxelSize(vx, vy, vz, unit);
print("Voxel size: " + vx + " x " + vy + " x " + vz + " " + unit);

// If uncalibrated (unit="pixel"), set from known metadata:
run("Properties...", "pixel_width=0.325 pixel_height=0.325 voxel_depth=1.0 unit=um");

// Verify anisotropy ratio (Z/XY):
ratio = vz / vx;
print("Z/XY anisotropy ratio: " + ratio);
// Typical confocal: ratio = 2-5 (Z steps coarser than XY)
// Isotropic: ratio = 1
```

---

## 10. Plugin Availability

### Installed in this Fiji (confirmed available)

| Plugin | Key Commands | Use For |
|--------|-------------|---------|
| 3D ImageJ Suite | 64 commands (3D Objects Counter, 3D Segment, 3D Centroid, 3D Distance Map, 3D Manager, etc.) | All 3D analysis |
| MorphoLibJ | Connected Components 3D, Watershed 3D, Distance Transform 3D, Analyze Regions 3D, Dilate Labels | Label operations, morphology |
| AnalyzeSkeleton | Analyze Skeleton (2D/3D) | Branching, neurite analysis |
| Skeletonize3D | Skeletonize (2D/3D) | Create skeletons from binary |
| StarDist | StarDist 2D, StarDist 3D | Deep learning nuclei segmentation |
| Cellpose | Cellpose, Cellpose SAM | Deep learning cell segmentation |
| Coloc 2 | Coloc 2 | Pixel-based colocalization |
| 3D Viewer | 3D Viewer | Interactive 3D visualization |
| 3Dscript | Batch Animation | Scripted 3D rendering |
| CLIJ2 | 504 GPU-accelerated commands | Fast 3D processing |
| Bio-Formats | Bio-Formats Importer | Read .nd2, .lif, .czi, etc. |

### May need to verify / install

| Plugin | Update Site | Use For |
|--------|------------|---------|
| DiAna | 3D ImageJ Suite | Two-population distance analysis |
| Spatial Statistics 2D/3D | 3D ImageJ Suite | Spatial randomness testing |
| MosaicIA | Mosaic ToolSuite | Interaction analysis |
| BIOP-JACoP | PTBIOP | Batch colocalization |
| SNT | Neuroanatomy | Neurite tracing + Sholl |

### How to check if a plugin is available
```bash
grep -i "diana\|spatial stat\|mosaic" .tmp/commands.txt
```

If not found, tell user:
> "DiAna is part of the 3D ImageJ Suite update site. Enable it via
> Help > Update > Manage Update Sites > check '3D ImageJ Suite' > Apply,
> then restart Fiji."

---

## Key References

- Boudier T. (2020). NEUBIAS Academy: Introduction to 3D Analysis with 3D ImageJ Suite
- Ollion J. et al. (2013). TANGO: A Generic Tool for High-throughput 3D Image Analysis
- Gilles J.F., Bhatt-Grover S. (2016). DiAna: object-based 3D co-localization and distance analysis. Methods 115:55-64
- Andrey P. et al. (2010). Statistical Analysis of 3D Images Detects Regular Spatial Distributions. PLoS Comput Biol 6(7):e1000853
- Legland D. et al. (2016). MorphoLibJ: Integrated library and plugins for mathematical morphology with ImageJ. Bioinformatics 32(22):3532-3534
- Arganda-Carreras I. et al. (2010). 3D reconstruction of histological sections: Application to mammary gland tissue. Microscopy Research and Technique 73(11):1019-1029 (AnalyzeSkeleton)
