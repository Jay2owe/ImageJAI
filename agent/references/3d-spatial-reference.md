# 3D Spatial Analysis Reference

Command reference for 3D object analysis, spatial statistics, colocalization,
morphometry, distance analysis, and skeleton/network analysis in Fiji.
All `run()` commands work with: `python ij.py macro 'COMMAND_HERE'`

---

## Table of Contents

1. [3D Objects Counter](#1-3d-objects-counter)
2. [3D Manager (Ext.Manager3D)](#2-3d-manager)
3. [3D Segmentation](#3-3d-segmentation)
4. [3D Filters](#4-3d-filters)
5. [3D Shape & Measurement Plugins](#5-3d-shape--measurement-plugins)
6. [3D Distances & Relationships](#6-3d-distances--relationships)
7. [3D Spatial Statistics](#7-3d-spatial-statistics)
8. [3D Colocalization](#8-3d-colocalization)
9. [3D Binary Operations](#9-3d-binary-operations)
10. [MorphoLibJ 3D](#10-morpholibj-3d)
11. [CLIJ2 GPU-Accelerated 3D](#11-clij2-gpu-accelerated-3d)
12. [Coloc 2](#12-coloc-2)
13. [DiAna](#13-diana)
14. [AnalyzeSkeleton](#14-analyzeskeleton)
15. [SNT / Sholl Analysis](#15-snt--sholl-analysis)
16. [Workflows](#16-workflows)
17. [Quick Reference: Which Tool for Which Task](#17-quick-reference)
18. [Plugin Availability](#18-plugin-availability)

---

## 1. 3D Objects Counter

`Plugins > 3D Objects Counter` — counts and measures 3D connected objects.

### Configure and Run

```javascript
run("3D OC Options", "volume surface nb_of_obj._voxels "
  + "integrated_density mean_gray_value std_dev_gray_value "
  + "centroid mean_distance_to_surface centre_of_mass bounding_box "
  + "dots_size=5 font_size=10 redirect_to=none");

run("3D Objects Counter", "threshold=128 slice=25 "
  + "min.=100 max.=9999999 objects surfaces centroids statistics summary");
```

### Parameters

| Parameter | Description | How to choose |
|-----------|-------------|---------------|
| `threshold=N` | Intensity threshold | Use histogram to find separation point between objects and background |
| `min.=N` | Min object size (voxels) | Estimate from smallest expected object: width x height x depth in voxels |
| `max.=N` | Max object size (voxels) | Set to exclude merged/touching artefacts |
| `objects` | Generate labeled objects map | Needed for downstream 3D Manager analysis |
| `surfaces` | Generate surface voxels map | Needed for surface-based measurements |
| `centroids` | Generate centroids map | For visualization of object positions |
| `statistics` | Per-object statistics table | For quantitative analysis |
| `summary` | Summary table | For quick overview |
| `redirect_to=TITLE` | Measure intensity on different image | For measuring raw intensity after segmenting on processed copy |

### Output Columns

| Column | Description |
|--------|-------------|
| Volume (px^3 / unit) | Object volume in voxels and calibrated |
| Surface (px^2 / unit) | Surface area |
| IntDen, Mean, StdDev, Median, Min, Max | Intensity statistics |
| X, Y, Z (centroid) | Geometric center |
| XM, YM, ZM | Centre of mass (intensity-weighted) |
| BX, BY, BZ, B-width/height/depth | Bounding box |

---

## 2. 3D Manager

`Plugins > 3D Manager` — central tool for 3D ROI management and measurements.
Uses `Ext.Manager3D_*()` macro extensions. Call `run("3D Manager");` first.

### Object Management

| Function | Description |
|----------|-------------|
| `Ext.Manager3D_Segment(lo, hi)` | Threshold and label current image |
| `Ext.Manager3D_AddImage()` | Add objects from labeled image |
| `Ext.Manager3D_Count(nb)` | Get object count |
| `Ext.Manager3D_Select(i)` | Select object i (0-based) |
| `Ext.Manager3D_SelectAll()` | Select all objects |
| `Ext.Manager3D_DeselectAll()` | Deselect all |
| `Ext.Manager3D_MultiSelect()` / `MonoSelect()` | Toggle multi/single-select |
| `Ext.Manager3D_Delete()` | Remove selected from list |
| `Ext.Manager3D_Erase()` | Remove + fill black in image |
| `Ext.Manager3D_Reset()` | Clear all objects |
| `Ext.Manager3D_GetName(i, name)` / `Rename("name")` | Get/set object name |
| `Ext.Manager3D_Load("file.zip")` / `Save("file.zip")` | Load/save objects |
| `Ext.Manager3D_FillStack(r, g, b)` | Color selected in stack |
| `Ext.Manager3D_Close()` | Close 3D Manager |

### Geometry Measurements

```javascript
Ext.Manager3D_Measure3D(index, "parameter", result);
```

| Parameter | Description |
|-----------|-------------|
| `"Vol"` | Volume (calibrated) |
| `"Surf"` | Surface area |
| `"NbVox"` | Voxel count |
| `"Comp"` | Compactness (36*PI*V^2/S^3) |
| `"Feret"` | 3D Feret diameter (max caliper) |
| `"Elon1"` | Elongation (major/2nd axis) |
| `"Elon2"` | Flatness (2nd/3rd axis) |
| `"DCMin"` / `"DCMax"` / `"DCMean"` / `"DCSD"` | Center-to-surface distances |
| `"RatioVolEll"` | Object/ellipsoid volume ratio |

### Intensity Measurements

```javascript
Ext.Manager3D_Quantif3D(index, "parameter", result);
```

| Parameter | Description |
|-----------|-------------|
| `"IntDen"` | Integrated density |
| `"Mean"` / `"Min"` / `"Max"` / `"Sigma"` | Intensity statistics |

### Position Functions

```javascript
Ext.Manager3D_Centroid3D(i, cx, cy, cz);       // geometric center
Ext.Manager3D_MassCenter3D(i, cmx, cmy, cmz);  // intensity-weighted center
Ext.Manager3D_Feret1(i, fx, fy, fz);           // Feret endpoint 1
Ext.Manager3D_Feret2(i, fx, fy, fz);           // Feret endpoint 2
Ext.Manager3D_Bounding3D(i, x0, x1, y0, y1, z0, z1);
```

### Distance Functions

```javascript
Ext.Manager3D_Dist2(objA, objB, "type", distance);
Ext.Manager3D_Closest(obj, "type", closestIdx);
Ext.Manager3D_ClosestK(obj, k, "type", closestIdx);
Ext.Manager3D_BorderVoxel(objA, objB, bx, by, bz);
```

| Distance Type | Description |
|--------------|-------------|
| `"cc"` | Centre-to-centre |
| `"bb"` | Border-to-border (surface-to-surface) |
| `"c1b2"` / `"c2b1"` | Centre of one to border of other |
| `"r1c2"` / `"r2c1"` | Radius in direction of other's centre |

### Colocalization

```javascript
Ext.Manager3D_Coloc2(objA, objB, pctA, pctB, surfContact);
// pctA = % of A overlapping B, pctB = % of B overlapping A
```

### Batch Operations (all objects, results to table)

```javascript
Ext.Manager3D_Measure();    // geometry
Ext.Manager3D_Quantif();    // intensity
Ext.Manager3D_Distance();   // pairwise distances
Ext.Manager3D_Coloc();      // pairwise colocalization
```

### Saving Results

```javascript
Ext.Manager3D_SaveResult("M", "measure.csv");   // M=Measure, Q=Quantif
Ext.Manager3D_SaveResult("D", "distance.csv");   // D=Distance, C=Coloc
Ext.Manager3D_CloseResult("M");                   // V=Voxels, A=All
```

### Example: Complete 3D Analysis

```javascript
run("3D Manager");
Ext.Manager3D_Segment(128, 255);
Ext.Manager3D_Count(nb);
for (i = 0; i < nb; i++) {
    Ext.Manager3D_Measure3D(i, "Vol", vol);
    Ext.Manager3D_Measure3D(i, "Comp", comp);
    Ext.Manager3D_Centroid3D(i, cx, cy, cz);
    print("Obj " + i + ": Vol=" + vol + " Comp=" + comp + " pos=(" + cx + "," + cy + "," + cz + ")");
}
```

---

## 3. 3D Segmentation

### 3D ImageJ Suite Segmentation Plugins

| Command | When to use | Key parameters |
|---------|-------------|----------------|
| `3D Simple Segmentation` | Basic threshold + size filter | `low_threshold`, `min_size`, `max_size` |
| `3D Hysteresis Segmentation` | Noisy data; seeds at high threshold, grow to low | `low_threshold`, `high_threshold`, `min_size` |
| `3D Iterative Segmentation` | Variable-intensity touching objects | `threshold_method`, `min_vol`, `criteria_method` |
| `3D Watershed` | Splitting touching objects (needs seeds image) | `seeds_threshold`, `image_threshold`, `radius` |
| `3D Spots Segmentation` | Bright puncta/foci | `seeds_threshold`, `local_background`, radii |
| `3D Nuclei Segmentation` | Nuclear segmentation | `seeds_threshold`, `min_volume`, `max_volume` |
| `3D Maxima Finder` | Generate seeds for watershed | `radiusxy`, `radiusz`, `noise` |

```javascript
// Simple threshold segmentation
run("3D Simple Segmentation", "low_threshold=128 min_size=100 max_size=-1");

// Hysteresis (two-level) — consider when SNR is low
run("3D Hysteresis Segmentation", "low_threshold=50 high_threshold=150 min_size=100 max_size=-1 labelling");

// Seeded watershed — typical workflow
run("3D Fast Filters", "filter=MaximumLocal radius_x_pix=5 radius_y_pix=5 radius_z_pix=5");
run("3D Watershed", "seeds_threshold=10 image_threshold=50 image=raw seeds=seeds radius=2");
```

**Choosing radius for seed detection:** Start with roughly half the expected object radius in each dimension. Increase if too many false seeds; decrease if missing objects.

### Segmentation Comparison

| Method | Strengths | Limitations |
|--------|-----------|-------------|
| 3D Objects Counter | Simplest, built-in | No splitting of touching objects |
| 3D Simple Segmentation | Fast, produces label map | Single threshold only |
| 3D Hysteresis | Handles noisy edges | Needs two good threshold values |
| 3D Watershed | Splits touching objects | Requires good seed image |
| StarDist 3D | Best for dense nuclei | Requires compatible GPU/model |
| CLIJ2 Voronoi-Otsu | Fast GPU one-step | Two sigma params to tune |

---

## 4. 3D Filters

### Filter Comparison by Implementation

| Filter | ImageJ Core | 3D Fast Filters | MorphoLibJ | CLIJ2 (GPU) |
|--------|-------------|-----------------|------------|-------------|
| Gaussian | `Gaussian Blur 3D...` | — | — | `gaussianBlur3D` |
| Mean | `Mean 3D...` | `Mean` | — | `mean3DBox/Sphere` |
| Median | `Median 3D...` | `Median` | — | `median3DBox/Sphere` |
| Min (erosion) | `Minimum 3D...` | `Minimum` | `Erosion` | `minimum3DBox/Sphere` |
| Max (dilation) | `Maximum 3D...` | `Maximum` | `Dilation` | `maximum3DBox/Sphere` |
| Top-hat | — | `TopHat` | `WhiteTopHat` | — |
| Local maxima | — | `MaximumLocal` | `Regional Maxima 3D` | `detectMaxima3DBox` |
| Variance | `Variance 3D...` | `Variance` | — | — |
| DoG | — | — | — | `differenceOfGaussian3D` |

### 3D Fast Filters (3D ImageJ Suite)

```javascript
run("3D Fast Filters", "filter=FILTER_TYPE radius_x_pix=RX radius_y_pix=RY radius_z_pix=RZ");
```

Filters: `Mean`, `Median`, `Minimum`, `Maximum`, `Open`, `Close`, `MaximumLocal`, `TopHat`, `Sobel`, `Adaptive`, `Variance`

**Choosing radii:** Set Z radius proportional to anisotropy. For typical confocal (Z step 2-5x XY pixel size), use Z radius = XY radius / anisotropy ratio.

### 3D Edge and Symmetry Filter

```javascript
run("3D Edge and Symmetry Filter", "radius_x=10 radius_y=10 radius_z=5 normalize edge symmetry");
```

Consider for detecting spherical objects (nuclei, vesicles).

### MorphoLibJ Morphological Filters (3D)

```javascript
run("Morphological Filters (3D)", "operation=Opening element=Ball x-radius=3 y-radius=3 z-radius=3");
```

Operations: `Erosion`, `Dilation`, `Closing`, `Opening`, `Gradient`, `Laplacian`, `BlackTopHat`, `WhiteTopHat`
Elements: `Ball`, `Cube`

---

## 5. 3D Shape & Measurement Plugins

### 3D ImageJ Suite Standalone Measurement Commands

Each accepts binary or labeled images. Output includes Label, Value columns.

| Command | Key Output Columns |
|---------|--------------------|
| `run("3D Volume");` | Volume(pix), Volume(unit) |
| `run("3D Surface");` | Surface(pix), Surface(unit), SurfaceCorrected |
| `run("3D Centroid");` | CX, CY, CZ (pix) |
| `run("3D Intensity Measure");` | Average, Min, Max, StdDev, IntegratedDensity |
| `run("3D Compactness");` | Compactness, Sphericity (pix/unit/corrected/discrete) |
| `run("3D Ellipsoid Fitting");` | VolEll, Spareness, EllMajRad, Elongation, Flatness |
| `run("3D Feret");` | Feret(unit), Feret1X/Y/Z, Feret2X/Y/Z |
| `run("3D Distance Contour");` | DCMin, DCMax, DCAvg, DCsd (center-to-contour) |
| `run("3D Shape");` | Compactness + Sphericity (all variants) |
| `run("3D Ellipsoid");` | Elongation, Flatness (from eigenvalue decomposition) |
| `run("3D RDAR");` | Morphological deviation from fitted ellipsoid |
| `run("3D Mesh Measure (slow)");` | SurfaceArea, SurfaceAreaSmooth |

### MorphoLibJ: Analyze Regions 3D

```javascript
run("Analyze Regions 3D", "volume surface_area sphericity "
  + "equivalent_ellipsoid ellipsoid_elongations max._inscribed "
  + "surface_area_method=[Crofton (13 dirs.)] euler_connectivity=C6");
```

| Output | Description |
|--------|-------------|
| Volume, SurfaceArea, Sphericity | Basic morphometry |
| Elli.Center.X/Y/Z | Inertia ellipsoid centroid |
| Elli.Radius1/2/3 | Ellipsoid radii (decreasing) |
| Elli.Phi/Theta/Psi | Euler angles (degrees) |
| InscrBall.Center/Radius | Max inscribed sphere |

---

## 6. 3D Distances & Relationships

### 3D Distances Plugin

```javascript
run("3D Distances", "image_a=labelA image_b=labelB distance=CenterCenter closest");
```

| Parameter | Options |
|-----------|---------|
| `distance` | `CenterCenter`, `BorderBorder`, `Hausdorff` |
| `closest` / `closest2` | Return 1 or 2 nearest neighbors |

When image_a = image_b: Distance_1 = 0 (self), Distance_2 = nearest neighbor.

### Distance Type Reference (3D Manager & 3D Distances)

| Type | Description |
|------|-------------|
| `"cc"` | Centre-to-centre |
| `"bb"` | Border-to-border |
| `"c1b2"` / `"c2b1"` | Centre of one to border of other |
| `"r1c2"` / `"r2c1"` | Radius toward other's centre |
| `"ex1c2"` / `"ex2c1"` | Extended distance (non-overlapping) |
| Hausdorff | Maximum of minimum distances (worst-case mismatch) |

### Distance Transforms

| Command | When to use |
|---------|-------------|
| `run("3D Distance Map");` | 3D EDT on binary — distance to nearest boundary |
| `run("Exact Euclidean Distance Transform (3D)");` | Precise 3D EDT |
| `run("3D Distance Map EVF");` | Normalized distance creating equal-volume layers |
| `run("Chamfer Distance Map 3D", "distances=[Borgefors (3,4,5)]");` | Fast approximate EDT (MorphoLibJ) |
| `run("Geodesic Distance Map 3D", "marker=M mask=M distances=[Borgefors (3,4,5)]");` | Distance constrained to follow mask geometry |

### 3D Interactions, Mereotopology, Numbering

| Command | Description |
|---------|-------------|
| `run("3D Interactions");` | Spatial relationships between two populations |
| `run("3D Mereotopology");` | Topological relationships (contains, overlaps, touches) |
| `run("3D Numbering");` | Assign numbering based on spatial criteria |

---

## 7. 3D Spatial Statistics

### 3D SpatialStatistics

Tests whether objects are randomly distributed, clustered, or regular within a reference structure using Monte Carlo simulation.

```javascript
run("3D SpatialStatistics", "pattern=objects reference=mask "
  + "nb_evaluations=1000 nb_random=100 hardcore_distance=0");
```

**Choosing parameters:** `nb_evaluations` controls confidence interval precision (typically 1000). `hardcore_distance` sets minimum inter-object distance for the random model (use 0 unless objects have known minimum spacing).

| Output Function | Interpretation |
|----------------|----------------|
| **G-function** | NND distribution. Above envelope = clustering, below = regularity |
| **F-function** | Empty space CDF. Below envelope = clustering, above = regularity |
| **H-function** | All-pairs distance CDF |
| **SDI** | Spatial Distribution Index. SDI > 1 = clustered, SDI < 1 = regular |

### Other Spatial Analysis

| Command | Description |
|---------|-------------|
| `run("3D Layers");` | Object distribution in concentric layers/shells |
| `run("3D Radial");` | Density as function of distance from reference |
| `run("Interaction Analysis");` | MosaicIA: pairwise interaction potentials (Gibbs model) |
| `run("Neighbor Analysis");` | BioVoxxel: Voronoi-based inter-particle distances |

---

## 8. 3D Colocalization

### Object-Based: 3D MultiColoc

```javascript
run("3D MultiColoc", "image_a=labelA image_b=labelB");
```

Output: ColocAll (every pair), ColocOnly (overlapping pairs) with columns O (object ID), V (overlap volume), P (overlap percentage).

### 3D Manager Colocalization

```javascript
Ext.Manager3D_Coloc();  // all pairs → Results table
Ext.Manager3D_Coloc2(obj1, obj2, pctA, pctB, surfContact);  // specific pair
```

### Pixel-Based and Other Options

| Command | Type | When to use |
|---------|------|-------------|
| Coloc 2 | Pixel-based | Pearson/Manders on z-stacks |
| 3D MultiColoc | Object overlap | Discrete objects, volume overlap |
| DiAna | Object + distance | Combined coloc and distance analysis |
| Colocalization Threshold | Pixel-based | Auto-threshold for coloc |
| BIOP JACoP | Pixel-based | Batch Manders coefficients |

---

## 9. 3D Binary Operations

| Command | Description |
|---------|-------------|
| `run("3D Fill Holes");` | Fill internal holes in 3D objects |
| `run("3D Exclude Edges");` | Remove objects touching image borders |
| `run("3D Close Labels", "radius_x=2 radius_y=2 radius_z=2");` | Morphological closing on labels |
| `run("3D Merge Labels");` | Merge adjacent labels |
| `run("3D ConvexHull");` | 3D convex hull of labeled objects |
| `run("3D Distance Map EVF", "inside outside");` | EDT + Eroded Volume Fraction |
| `run("3D Density Filter", "radius_x=10 radius_y=10 radius_z=5 number=3");` | Filter by local density |
| `run("3D Binary Interpolate");` | Interpolate between binary slices |

---

## 10. MorphoLibJ 3D

### Connected Components Labeling

```javascript
run("Connected Components Labeling", "connectivity=6 type=[16 bits]");
// connectivity: 6 (face-only) or 26 (all neighbors) for 3D
// type: [8 bits], [16 bits], [32 bits]
```

**Choosing connectivity:** 6-connectivity is more conservative (objects must share a face). 26-connectivity considers diagonal neighbors (use when objects have irregular boundaries).

### Watershed Variants

| Command | When to use |
|---------|-------------|
| `Distance Transform Watershed 3D` | Split touching binary objects without seeds |
| `Classic Watershed` | From gradient image |
| `Marker-controlled Watershed` | When you have seed/marker image |

```javascript
// Distance Transform Watershed — typically the best starting point for splitting
run("Distance Transform Watershed 3D", "distances=[Borgefors (3,4,5)] "
  + "output=[16 bits] normalize dynamic=2 connectivity=6");
// dynamic: higher = fewer splits (more conservative). Start around 1-3.
```

### Other MorphoLibJ Operations

```javascript
run("Remove Largest Region");      // Remove background label
run("Keep Largest Region");        // Keep only largest object
run("Size Opening 2D/3D", "min=500");  // Remove small objects
run("Fill Holes (Binary/Gray)");
run("Kill Borders");               // Remove border-touching objects
run("Regional Min & Max 3D", "operation=[Regional Maxima] connectivity=6");
run("Extended Min & Max 3D", "operation=[Extended Maxima] dynamic=10 connectivity=6");
```

---

## 11. CLIJ2 GPU-Accelerated 3D

All use `Ext.CLIJ2_*()` syntax. Initialize with:
```javascript
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_push("image_title");
```

### Filtering

| Function | Description |
|----------|-------------|
| `Ext.CLIJ2_gaussianBlur3D(in, out, sx, sy, sz)` | Gaussian blur |
| `Ext.CLIJ2_mean3DBox(in, out, rx, ry, rz)` | Mean filter (box) |
| `Ext.CLIJ2_mean3DSphere(in, out, rx, ry, rz)` | Mean filter (sphere) |
| `Ext.CLIJ2_median3DBox(in, out, rx, ry, rz)` | Median filter |
| `Ext.CLIJ2_maximum3DBox(in, out, rx, ry, rz)` | Dilation |
| `Ext.CLIJ2_minimum3DBox(in, out, rx, ry, rz)` | Erosion |
| `Ext.CLIJ2_differenceOfGaussian3D(in, out, s1x,s1y,s1z, s2x,s2y,s2z)` | DoG |
| `Ext.CLIJ2_detectMaxima3DBox(in, out, rx, ry, rz)` | Local maxima |

### Thresholding

All via `Ext.CLIJ2_threshold<Method>(src, dst)`:
Methods: Otsu, Triangle, Li, Huang, MaxEntropy, Mean, MinError, Minimum, Moments, IsoData, Yen, RenyiEntropy, Shanbhag, Percentile, Default, Intermodes.

### Labeling & Segmentation

| Function | Description |
|----------|-------------|
| `Ext.CLIJ2_connectedComponentsLabelingBox(bin, lbl)` | 26-connectivity labeling |
| `Ext.CLIJ2_connectedComponentsLabelingDiamond(bin, lbl)` | 6-connectivity labeling |
| `Ext.CLIJ2_voronoiOtsuLabeling(in, lbl, spotSigma, outlineSigma)` | One-step: Gaussian + Otsu + Voronoi |
| `Ext.CLIJ2_extendLabelingViaVoronoi(lbl_in, lbl_out)` | Expand labels via Voronoi |
| `Ext.CLIJ2_excludeLabelsOnEdges(lbl_in, lbl_out)` | Remove edge objects |
| `Ext.CLIJ2_excludeLabelsOutsideSizeRange(lbl_in, lbl_out, min, max)` | Size filter |
| `Ext.CLIJ2_dilateLabels(lbl_in, lbl_out, radius)` | Grow labels |
| `Ext.CLIJ2_erodeLabels(lbl_in, lbl_out, radius)` | Shrink labels |

### Measurements

```javascript
Ext.CLIJ2_statisticsOfLabelledPixels(intensity, labels);
// Output columns: IDENTIFIER, BOUNDING_BOX_*, MIN/MAX/MEAN/SUM_INTENSITY,
//   STANDARD_DEVIATION_INTENSITY, PIXEL_COUNT, CENTROID_X/Y/Z
```

| Parametric maps | Description |
|----------------|-------------|
| `Ext.CLIJ2_meanIntensityMap(int, lbl, out)` | Mean intensity per label |
| `Ext.CLIJ2_pixelCountMap(lbl, out)` | Pixel count per label |
| `Ext.CLIJ2_extensionRatioMap(lbl, out)` | Extension ratio per label |

### Distance & Neighbor Analysis

| Function | Description |
|----------|-------------|
| `Ext.CLIJ2_distanceMap(bin, out)` | Binary distance map |
| `Ext.CLIJ2_generateDistanceMatrix(pts1, pts2, dm)` | All pairwise distances |
| `Ext.CLIJ2_generateTouchMatrix(lbl, tm)` | Which labels are adjacent |
| `Ext.CLIJ2_generateNNearestNeighborsMatrix(dm, nn, n)` | N nearest neighbors |
| `Ext.CLIJ2_touchingNeighborCountMap(lbl, out)` | Count touching neighbors |
| `Ext.CLIJ2_averageDistanceOfNClosestNeighborsMap(lbl, out, n)` | Mean NND map |
| `Ext.CLIJ2_drawMeshBetweenTouchingLabels(pts, tm, mesh)` | Neighbor mesh visualization |

### Overlap & Colocalization

| Function | Description |
|----------|-------------|
| `Ext.CLIJ2_jaccardIndex(bin1, bin2, j)` | Jaccard index |
| `Ext.CLIJ2_sorensenDiceCoefficient(bin1, bin2, d)` | Dice coefficient |
| `Ext.CLIJ2_generateJaccardIndexMatrix(lbl1, lbl2, jm)` | Pairwise Jaccard between label maps |

### Transforms & Utility

| Function | Description |
|----------|-------------|
| `Ext.CLIJ2_pull("name")` | Pull from GPU to ImageJ |
| `Ext.CLIJ2_release("name")` / `clear()` | Free GPU memory |
| `Ext.CLIJ2_crop3D(in, out, x,y,z, w,h,d)` | Crop 3D region |
| `Ext.CLIJ2_affineTransform3D(in, out, transform)` | Affine transform |
| `Ext.CLIJ2_rotate3D(in, out, ax, ay, az, center)` | Rotation |
| `Ext.CLIJ2_centroidsOfLabels(lbl, pts)` | Extract centroids |
| `Ext.CLIJ2_pullLabelsToROIManager(lbl)` | Export to ROI Manager |

### Example: Complete CLIJ2 3D Pipeline

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_push("raw_stack");
Ext.CLIJ2_gaussianBlur3D("raw_stack", "blurred", 2, 2, 1);
Ext.CLIJ2_thresholdOtsu("blurred", "binary");
Ext.CLIJ2_connectedComponentsLabelingBox("binary", "labels");
Ext.CLIJ2_excludeLabelsOnEdges("labels", "labels_clean");
Ext.CLIJ2_excludeLabelsOutsideSizeRange("labels_clean", "labels_sized", 200, 50000);
Ext.CLIJ2_statisticsOfLabelledPixels("raw_stack", "labels_sized");
Ext.CLIJ2_generateTouchMatrix("labels_sized", "touches");
Ext.CLIJ2_touchingNeighborCountMap("labels_sized", "ncount");
Ext.CLIJ2_pull("labels_sized");
Ext.CLIJ2_pull("ncount");
Ext.CLIJ2_clear();
```

---

## 12. Coloc 2

`Analyze > Colocalization Analysis > Coloc 2` — pixel-based colocalization on 2D or 3D stacks.

```javascript
run("Split Channels");
run("Coloc 2", "channel_1=C1-image channel_2=C2-image "
  + "roi_or_mask=<None> threshold_regression=Costes "
  + "display_shuffled_images psf=3 costes_randomisations=100");
```

| Metric | Range | Interpretation |
|--------|-------|----------------|
| Pearson's r | -1 to +1 | Linear correlation of intensities |
| Manders M1/M2 | 0 to 1 | Fraction of channel in colocalizing pixels |
| tM1/tM2 | 0 to 1 | Thresholded Manders (above auto-threshold) |
| Li ICQ | -0.5 to +0.5 | Intensity Correlation Quotient |
| Costes p-value | 0 to 1 | Statistical significance by randomization |

**Gotchas:** ROI applies across all z-slices. Time series not supported (analyze frame by frame). Background subtraction recommended before analysis. PSF parameter sets Costes randomization block size.

---

## 13. DiAna

Object-based 3D colocalization and distance analysis (part of 3D ImageJ Suite).

### Segmentation

```javascript
// Threshold-based: thr format = threshold-minSize-maxSize-excludeEdges-use32bit
run("DiAna_Segment", "img=C1.tif filter=median rad=1.0 thr=739-3-2000-true-false");

// Spot-based: peaks=radXY-radZ-noise, spots=seed-bg-convergence-min-max-excludeEdges
run("DiAna_Segment", "img=C1.tif peaks=2.0-2.0-50.0 spots=30-10-1.5-3-2000-true");
```

### Analysis

```javascript
run("DiAna_Analyse", "img1=C1.tif img2=C2.tif lab1=C1_seg.tif lab2=C2_seg.tif "
  + "coloc distc=50.0 adja kclosest=1 dista=50.0 measure");
```

| Parameter | Description |
|-----------|-------------|
| `coloc` | Compute volume overlap |
| `distc=N` | Distance threshold for colocalization (um) |
| `adja` | Adjacency analysis |
| `kclosest=N` | Find N nearest neighbors (append `b` for border-to-border) |
| `dista=N` | Max distance for adjacency (um) |
| `measure` | Per-object measurements |

---

## 14. AnalyzeSkeleton

`Analyze > Skeleton > Analyze Skeleton (2D/3D)` — analyzes skeleton images for branch/junction metrics.

```javascript
run("Skeletonize (2D/3D)");  // convert binary to skeleton first
run("Analyze Skeleton (2D/3D)", "prune=[shortest branch] show display");
```

| Prune option | When to use |
|-------------|-------------|
| `[none]` | Keep everything |
| `[shortest branch]` | Remove short spurious branches (typically best starting point) |
| `[lowest intensity voxel]` | Use original image intensity to guide pruning |
| `[lowest intensity branch]` | Remove entire low-intensity branches |

### Output: Per-Skeleton Table

| Column | Description |
|--------|-------------|
| # Branches / # Junctions | Branch and junction counts |
| # End-point / Triple / Quadruple points | Terminal and multi-branch points |
| Average / Maximum Branch Length | Calibrated branch lengths |
| Longest Shortest Path | Longest geodesic path through skeleton |

### Output: Branch Information Table (with `display`)

| Column | Description |
|--------|-------------|
| Skeleton ID, Branch length | Which skeleton, calibrated length |
| V1 x/y/z, V2 x/y/z | Branch endpoint coordinates |
| Euclidean distance | Straight-line distance V1-V2 |

**Tortuosity** = Branch length / Euclidean distance (>1 = curved).

**Voxel classification in output:** End-points (blue) < 2 neighbors, Slabs (orange) = 2 neighbors, Junctions (purple) > 2 neighbors.

---

## 15. SNT / Sholl Analysis

`Plugins > Neuroanatomy > Sholl` — spherical sampling shells in 3D.

```javascript
run("Sholl Analysis (From Image)...",
  "datamodechoice=Intersections startradius=10.0 stepsize=5.0 endradius=200.0 "
  + "hemishellchoice=[None. Use full shells] polynomialchoice=['Best fitting' degree]");
```

**Choosing step size:** Start with roughly the resolution of interest. Smaller steps give more detail but noisier profiles.

| Metric | Description |
|--------|-------------|
| Intersection counts | Per-shell crossing count |
| Critical radius/value | Radius and count of maximum intersections |
| Ramification index | Max intersections / primary branches |
| Enclosing radius | Radius containing the arbor |
| Sholl regression coefficient | Slope of semi-log regression |

---

## 16. Workflows

### Workflow: Segment → Label → Measure → Export

```javascript
// 1. Pre-process
run("Duplicate...", "title=seg duplicate");
run("Gaussian Blur 3D...", "x=2 y=2 z=1");

// 2. Segment
setAutoThreshold("Otsu dark stack");
run("Convert to Mask", "method=Otsu background=Dark calculate");

// 3. Label + Load
run("3D Manager");
Ext.Manager3D_Segment(128, 255);

// 4. Measure
Ext.Manager3D_SelectAll();
Ext.Manager3D_Measure();   // geometry
Ext.Manager3D_Quantif();   // intensity (switch to raw image first)

// 5. Export
Ext.Manager3D_SaveResult("M", "/path/to/geometry.csv");
Ext.Manager3D_SaveResult("Q", "/path/to/intensity.csv");
```

### Workflow: Nearest-Neighbor Distance

```javascript
run("3D Manager");
Ext.Manager3D_AddImage();
Ext.Manager3D_Count(nb);

path = "/path/to/nnd.csv";
f = File.open(path);
print(f, "ID,X,Y,Z,NearestID,NND_CC,NND_BB,Volume");
for (i = 0; i < nb; i++) {
    Ext.Manager3D_Centroid3D(i, cx, cy, cz);
    Ext.Manager3D_Closest(i, "cc", nnCC);
    Ext.Manager3D_Dist2(i, nnCC, "cc", dCC);
    Ext.Manager3D_Closest(i, "bb", nnBB);
    Ext.Manager3D_Dist2(i, nnBB, "bb", dBB);
    Ext.Manager3D_Measure3D(i, "Vol", vol);
    print(f, i+","+cx+","+cy+","+cz+","+nnCC+","+dCC+","+dBB+","+vol);
}
File.close(f);
```

### Workflow: Two-Channel Cross-Analysis (DiAna)

```javascript
run("DiAna_Segment", "img=C1.tif filter=median rad=1.0 thr=500-100-50000-true-false");
run("DiAna_Segment", "img=C2.tif filter=median rad=1.0 thr=300-50-50000-true-false");
run("DiAna_Analyse", "img1=C1.tif img2=C2.tif lab1=C1_seg.tif lab2=C2_seg.tif "
  + "coloc distc=1.0 kclosest=3 dista=5.0 measure");
```

### Workflow: Cell-to-Surface Distance

```javascript
// 1. Create binary mask of reference surface
selectWindow("boundary_channel");
run("Duplicate...", "title=boundary duplicate");
setAutoThreshold("Otsu dark stack");
run("Convert to Mask", "method=Otsu background=Dark calculate");

// 2. Compute 3D EDT (distance outside the mask)
run("3D Distance Map", "map=EDT image=boundary inverse");
rename("distance_map");

// 3. Sample distance at each cell centroid
// (after running 3D Objects Counter on cells to get centroids)
selectWindow("distance_map");
for (i = 0; i < nResults; i++) {
    setSlice(round(getResult("Z", i)));
    setResult("DistToSurface", i, getPixel(round(getResult("X", i)), round(getResult("Y", i))));
}
updateResults();
```

For distance following tissue geometry (around folds), use geodesic distance:
```javascript
run("Geodesic Distance Map 3D", "marker=boundary mask=tissue distances=[Borgefors (3,4,5)] output=[32 bits] normalize");
```

### Workflow: Skeleton Analysis (Vasculature/Neurites)

```javascript
run("Skeletonize (2D/3D)");
run("Analyze Skeleton (2D/3D)", "prune=[shortest branch] show display");
// For neurites: add Sholl analysis from soma center point
```

### Workflow: Spatial Randomness Test

```javascript
// Input: binary mask of reference structure + binary spots of objects
run("3D SpatialStatistics", "pattern=objects_binary reference=tissue_mask "
  + "nb_evaluations=1000 nb_random=100 hardcore_distance=0");
// SDI > 1 = clustered, SDI < 1 = regular
```

### Pattern: Check Calibration Before 3D Analysis

```javascript
getVoxelSize(vx, vy, vz, unit);
print("Voxel: " + vx + " x " + vy + " x " + vz + " " + unit);
// If uncalibrated:
run("Properties...", "pixel_width=0.325 pixel_height=0.325 voxel_depth=1.0 unit=um");
// Typical confocal anisotropy ratio (Z/XY): 2-5
```

---

## 17. Quick Reference

| Task | Best Tool | Alternative |
|------|-----------|-------------|
| Segment nuclei 3D | 3D Iterative Thresholding / StarDist 3D | 3D Watershed Split |
| Segment spots/puncta | 3D Spot Segmentation | CLIJ2 Voronoi-Otsu-Labeling |
| Split touching objects | Distance Transform Watershed 3D | 3D Watershed |
| Measure 3D volume | 3D Manager `Measure3D("Vol")` | MorphoLibJ Analyze Regions 3D |
| Measure 3D shape | 3D Compactness + 3D Ellipsoid | MorphoLibJ Analyze Regions 3D |
| Distance between objects | 3D Manager `Dist2` / 3D Distances | CLIJ2 generateDistanceMatrix |
| Spatial statistics | 3D SpatialStatistics | CLIJ2 neighbor maps |
| 3D colocalization (object) | 3D MultiColoc / Manager3D_Coloc | DiAna |
| 3D colocalization (pixel) | Coloc 2 | BIOP JACoP |
| 3D filtering (fast) | CLIJ2 GPU | 3D Fast Filters |
| 3D morphological ops | MorphoLibJ Morphological Filters 3D | CLIJ2 opening/closing |
| Skeleton/branch analysis | Skeletonize + Analyze Skeleton | — |
| 3D rendering | 3D Viewer (`python ij.py 3d`) | 3D Project |
| Drift correction | Correct 3D Drift | CLIJ2 drift correction |
| Count 3D objects | 3D Objects Counter | Connected Components (MorphoLibJ/CLIJ2) |

---

## 18. Plugin Availability

### Confirmed Installed

| Plugin | Key Commands | Use For |
|--------|-------------|---------|
| 3D ImageJ Suite | 64 commands | All 3D analysis |
| MorphoLibJ | Labels, watershed, morphology | Label operations |
| AnalyzeSkeleton | Analyze Skeleton (2D/3D) | Branching analysis |
| StarDist | StarDist 2D/3D | DL nuclei segmentation |
| Coloc 2 | Coloc 2 | Pixel colocalization |
| CLIJ2 | 504 GPU commands | Fast 3D processing |
| 3D Viewer | 3D Viewer | 3D visualization |

### May Need Installation

| Plugin | Update Site | Use For |
|--------|------------|---------|
| DiAna | 3D ImageJ Suite | Two-population distance analysis |
| Spatial Statistics 2D/3D | 3D ImageJ Suite | Spatial randomness testing |
| MosaicIA | Mosaic ToolSuite | Interaction analysis |
| SNT | Neuroanatomy | Neurite tracing + Sholl |

Check availability: `grep -i "diana\|spatial stat" .tmp/commands.md`

---

## Key Gotchas

- **Calibration:** Always verify with `getVoxelSize()` before 3D analysis. Wrong calibration invalidates all volume and distance measurements.
- **Anisotropy:** Set Z filter radii proportional to Z/XY voxel ratio. Typical confocal has 2-5x larger Z steps.
- **3D Objects Counter self-distance:** When using same image for both A and B, Distance_1 = 0 (self). Use Distance_2 for NND.
- **3D Manager indexing:** 0-based. Call `run("3D Manager")` before any `Ext.Manager3D_*` functions.
- **Label image bit depth:** Use 16-bit for up to 65535 objects; 32-bit for more.
- **CLIJ2 memory:** Always `Ext.CLIJ2_clear()` after GPU processing to free VRAM.
- **Coloc 2 time series:** Not supported directly; analyze frame by frame.
- **Feret computation:** Can be slow for large objects (computes all pairwise distances).

---

## References

- Ollion J. et al. (2013). TANGO. Bioinformatics 29(14):1840-1.
- Gilles J.F. et al. (2017). DiAna. Methods 115:55-64.
- Andrey P. et al. (2010). Spatial Statistics 3D. PLoS Comput Biol 6(7):e1000853.
- Legland D. et al. (2016). MorphoLibJ. Bioinformatics 32(22):3532-4.
- Arganda-Carreras I. et al. (2010). AnalyzeSkeleton. Microsc Res Tech 73(11):1019-29.
