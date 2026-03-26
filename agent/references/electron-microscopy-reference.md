# Electron Microscopy Image Analysis Reference

Reference for EM image analysis in ImageJ/Fiji: TEM/SEM processing, organelle segmentation,
particle analysis, stereology, serial section reconstruction, and CLEM workflows.

---

## 1. Quick Start

```bash
# Open EM image (Bio-Formats handles .dm3/.dm4/.mrc/.ser)
python ij.py macro 'run("Bio-Formats Importer", "open=/path/to/image.dm3 color_mode=Grayscale");'
python ij.py info
python ij.py capture em_opened

# Calibrate if not embedded in metadata
python ij.py macro 'run("Set Scale...", "distance=500 known=1 unit=um");'

# Segment and measure
python ij.py macro 'run("Gaussian Blur...", "sigma=2");'
python ij.py macro 'setAutoThreshold("Otsu dark"); run("Convert to Mask");'
python ij.py macro 'run("Set Measurements...", "area perimeter shape feret redirect=None decimal=3");'
python ij.py macro 'run("Analyze Particles...", "size=100-Infinity circularity=0.0-1.0 show=Outlines display clear summarize");'
python ij.py results
```

**Before any EM analysis:** (1) check metadata for pixel size, (2) determine if dark=signal or light=signal, (3) check bit depth, (4) look for artifacts.

---

## 2. EM Image Characteristics

### TEM vs SEM vs Fluorescence

| Property | TEM | SEM | Fluorescence |
|----------|-----|-----|-------------|
| Signal | Electron scatter through section | Secondary/backscattered electrons | Fluorophore emission |
| Colour | Greyscale only | Greyscale only | Multi-channel |
| Resolution | 0.1-1 nm | 1-10 nm | ~200 nm |
| Contrast | Heavy metal stain (dark=dense) | Surface topology / composition | Bright=signal |
| Typical depth | 8-16 bit | 8-bit | 12-16 bit |

### TEM Structure Appearance

| Structure | TEM Appearance | Typical Size |
|-----------|---------------|-------------|
| Membranes | Dark lines (OsO4 staining) | ~7 nm bilayer |
| Mitochondria | Double membrane, dark cristae folds | 0.3-3 um |
| Nucleus | Variable density (heterochromatin=dark) | 5-20 um |
| Ribosomes | Small dark dots | 20-30 nm |
| Vesicles | Round dark-outlined circles | 30 nm-1.5 um |
| Immunogold | Very electron-dense round particles | 5-20 nm |
| Myelin | Concentric dark/light layers | ~12 nm period |

### SEM Signal Types

| Detector | Information |
|----------|------------|
| Secondary electron (SE) | Surface topography (most common) |
| Backscattered electron (BSE) | Atomic number contrast (heavier=brighter) |
| EDX | Elemental composition |

### File Formats

| Format | Extension | Bio-Formats |
|--------|-----------|-------------|
| Gatan DigitalMicrograph | .dm3, .dm4 | Yes |
| MRC/CCP4 | .mrc, .rec, .st | Yes |
| TIFF | .tif | Yes |
| HDF5 | .h5 | Via plugin |

```bash
# Large volume — use virtual stack for memory
python ij.py macro 'run("Bio-Formats Importer", "open=/path/to/large.dm4 color_mode=Grayscale use_virtual_stack");'
```

### Noise Types

| Type | Appearance | Mitigation |
|------|-----------|-----------|
| Shot noise | Random pixel variation | Longer exposure, averaging |
| Charging (SEM) | Bright streaks/distortion | Better coating, lower voltage |
| Beam damage | Progressive contrast loss | Lower dose, cryo |
| Scan noise | Horizontal banding | Longer dwell time |
| Drift | Blurring, stretching | Shorter acquisition |

---

## 3. Scale Calibration

### From Metadata
```bash
python ij.py macro 'run("Bio-Formats Importer", "open=/path/to/image.dm3 color_mode=Grayscale");'
python ij.py metadata
python ij.py macro 'getPixelSize(unit, pw, ph); print("Pixel size: " + pw + " " + unit);'
# If unit is "pixel" or "inch", manual calibration is needed
```

### From Scale Bar
```bash
# User draws line along burned-in scale bar, then:
python ij.py macro '
  getLine(x1, y1, x2, y2, lineWidth);
  length = sqrt((x2-x1)*(x2-x1) + (y2-y1)*(y2-y1));
  print("Line length in pixels: " + length);
'
# Set scale: e.g., 312 px = 500 nm
python ij.py macro 'run("Set Scale...", "distance=312 known=500 unit=nm");'
```

### Calibration Standards

| Standard | Known Size |
|----------|-----------|
| Gold particles | 5, 10, 15, 20 nm |
| Latex spheres | 50 nm - 1 um |
| Collagen banding | ~67 nm periodicity |
| Grid bars | 2000 mesh = 7.5 um |

### Batch Calibration
```bash
# Apply to all images in session (use same magnification)
python ij.py macro 'run("Set Scale...", "distance=1 known=2 unit=nm global");'
# Remove global scale after batch:
python ij.py macro 'run("Set Scale...", "distance=0 known=0 unit=pixel global");'
```

### Removing Burned-In Data Bar
```bash
python ij.py macro '
  getDimensions(w, h, c, s, f);
  barHeight = 60;  // adjust based on image
  makeRectangle(0, 0, w, h - barHeight);
  run("Crop");
'
```

---

## 4. Image Enhancement for EM

**Critical:** Always work on a duplicate. Never modify the original for quantification.

```bash
python ij.py macro 'run("Duplicate...", "title=enhanced");'
```

### Filter Selection

| Purpose | Filter | Command | When to Choose |
|---------|--------|---------|----------------|
| Uneven staining | CLAHE | `run("Enhance Local Contrast (CLAHE)", "blocksize=127 histogram=256 maximum=3 mask=*None*");` | Staining varies across field |
| Display adjustment | setMinAndMax | `setMinAndMax(30, 220);` | Quick visualisation (no pixel change) |
| Salt-and-pepper | Median | `run("Median...", "radius=2");` | Preserves edges; radius 1-2 for fine structures |
| General smoothing | Gaussian | `run("Gaussian Blur...", "sigma=1.5");` | Choose sigma based on structure size |
| Best quality | Non-local means | `run("Non-local Means Denoising", "sigma=15 smoothing_factor=1");` | Requires CLIJ2/Xlib |
| Gradient + noise | Bandpass | `run("Bandpass Filter...", "filter_large=40 filter_small=3 suppress=None tolerance=5 autoscale saturate");` | Removes slow gradients AND high-freq noise |

**Choosing filter by structure:**

| Structure | Recommended | Reason |
|-----------|------------|--------|
| Membranes (thin lines) | Median r=1 | Preserves edges |
| Vesicles (round) | Gaussian sigma=1-2 | Preserves round shape |
| Gold particles | Median r=1 only | Larger filters merge particles |
| Fibrillar structures | Anisotropic diffusion | Preserves orientation |

### Background Correction

```bash
# Rolling ball — radius should be larger than largest object of interest
python ij.py macro 'run("Subtract Background...", "rolling=50");'

# Pseudo-flat-field (for severe gradients)
python ij.py macro '
  original = getTitle();
  run("Duplicate...", "title=background");
  run("Gaussian Blur...", "sigma=50");
  imageCalculator("Divide create 32-bit", original, "background");
  rename("flat_fielded");
  close("background");
'
```

### Inversion

TEM: dark structures on light background. Many ImageJ tools assume bright=signal.

```bash
python ij.py histogram
# If structures of interest are DARK, consider inverting:
python ij.py macro 'run("Duplicate...", "title=inverted"); run("Invert");'
```

Invert when: structures are dark and threshold method expects bright signal.
Do NOT invert when: using "dark background" threshold option, or structures are already bright.

---

## 5. Organelle Segmentation

### Approach Hierarchy

1. **Manual thresholding** — high-contrast structures (gold, dark organelles)
2. **Adaptive/local thresholding** — uneven contrast
3. **Edge detection + watershed** — touching structures
4. **Trainable Weka Segmentation** — complex cases
5. **Manual tracing** — structures that defy automation

### 5.1 Mitochondria

**Key metrics:** Area (typically 0.1-2 um^2), Circularity (0.2-0.9), Aspect Ratio (1-5), Feret diameter (0.3-3 um), Cristae density.

```bash
python ij.py macro '
  original = getTitle();
  run("Duplicate...", "title=mito_seg");
  run("Enhance Local Contrast (CLAHE)", "blocksize=127 histogram=256 maximum=3 mask=*None*");
  // Smooth to reduce cristae — choose sigma based on cristae spacing
  run("Gaussian Blur...", "sigma=3");
  // Mitochondria are dark in TEM
  setAutoThreshold("Otsu");
  run("Convert to Mask");
  run("Fill Holes");
  run("Open");
  run("Watershed");
'
python ij.py macro '
  selectWindow("mito_seg");
  run("Set Measurements...", "area perimeter shape feret redirect=None decimal=3");
  run("Analyze Particles...", "size=0.1-Infinity circularity=0.1-1.0 show=Outlines display clear summarize");
'
python ij.py results
```

**Weka approach (for complex images):**
```bash
# Once a classifier is saved, apply in batch:
python ij.py macro '
  call("trainableSegmentation.Weka_Segmentation.loadClassifier", "/path/to/classifier.model");
  call("trainableSegmentation.Weka_Segmentation.applyClassifier", "/path/to/image.tif", "/path/to/output/", "showResults=true", "storeResults=false", "probabilityMaps=false", "");
'
```

**Cristae density:** Threshold within individual mitochondria ROI using CLAHE + "Mean dark", skeletonize, count skeleton pixels. Cristae density = skeleton length / mitochondria area.

### 5.2 ER Network

```bash
python ij.py macro '
  run("Duplicate...", "title=er_seg");
  run("Enhance Local Contrast (CLAHE)", "blocksize=63 histogram=256 maximum=3 mask=*None*");
  run("Duplicate...", "title=er_edges");
  run("FeatureJ Edges", "compute smoothing=1.0 suppress lower=[] upper=[]");
  setAutoThreshold("Otsu");
  run("Convert to Mask");
  run("Close-");  // bridges small gaps
  run("Skeletonize");
  run("Analyze Skeleton (2D/3D)", "prune=[shortest branch] show");
'
```

### 5.3 Vesicles

| Vesicle Type | Typical Diameter | Characteristics |
|-------------|------------------|----------------|
| Synaptic vesicle | 30-50 nm | Clear center, clustered at presynapse |
| Clathrin-coated | 50-100 nm | Fuzzy dark coat |
| Early endosome | 100-500 nm | Tubulo-vesicular, irregular |
| Late endosome/MVB | 200-500 nm | Contains internal vesicles |
| Lysosome | 200-500 nm | Dense, heterogeneous |
| Autophagosome | 500-1500 nm | Double membrane |

```bash
python ij.py macro '
  run("Duplicate...", "title=ves_seg");
  run("Gaussian Blur...", "sigma=2");
  run("Invert");  // vesicle interiors become bright
  setAutoThreshold("MaxEntropy");
  run("Convert to Mask");
  run("Fill Holes");
  run("Watershed");
  run("Set Measurements...", "area centroid perimeter shape feret redirect=None decimal=3");
  // Adjust size range for your vesicle type and pixel size
  run("Analyze Particles...", "size=500-50000 circularity=0.5-1.0 show=Outlines display clear");
'
```

### 5.4 Synapses

| Feature | Measurement Method |
|---------|-------------------|
| PSD length | Segmented line along PSD, measure length |
| Synaptic cleft width | Perpendicular lines, average multiple measurements |
| Presynaptic vesicle count | Find Maxima in terminal ROI, or threshold + count |
| Docked vesicles | Manual count at membrane |

### 5.5 Nuclei — Heterochromatin Fraction

```bash
python ij.py macro '
  // Within nuclear ROI:
  run("Duplicate...", "title=hetero");
  setAutoThreshold("Triangle");  // works well for bimodal hetero/euchromatin
  run("Convert to Mask");
  getRawStatistics(n, meanMask);
  heteroFraction = meanMask / 255;
  print("Heterochromatin area fraction: " + heteroFraction);
'
```

### 5.6 Myelin G-ratio

G-ratio = inner diameter (axon) / outer diameter (axon+myelin). Typical: ~0.6-0.7 CNS, ~0.7-0.8 PNS. Higher = thinner myelin.

Measure by drawing lines across outer and inner myelin boundaries. For lamella counting, use line profile — myelin period ~12 nm.

---

## 6. Membrane Distance Measurements

### ER-Mitochondria Contact Sites

ER-mito contacts (MAMs) typically defined as ER within 10-30 nm of outer mitochondrial membrane.

```bash
python ij.py macro '
  // From mito_mask binary: create distance map
  selectWindow("mito_mask");
  run("Duplicate...", "title=mito_distance");
  run("Invert");
  run("Distance Map");
  // Each pixel = distance to nearest mito boundary in pixels

  // Threshold at contact distance (e.g., 30 nm)
  getPixelSize(unit, pw, ph);
  threshold_px = 30 / pw;
  run("Duplicate...", "title=contact_zone");
  setThreshold(0, threshold_px);
  run("Convert to Mask");

  // Intersect with ER mask
  imageCalculator("AND create", "contact_zone", "er_mask");
  rename("er_mito_contacts");
  run("Analyze Particles...", "size=0-Infinity display clear summarize");
'
```

### General Boundary-to-Boundary Distance

```bash
python ij.py macro '
  // Distance map from mask1, sample at mask2 boundary
  selectWindow("mask1");
  run("Duplicate...", "title=dist1");
  run("Invert");
  run("Distance Map");

  selectWindow("mask2");
  run("Duplicate...", "title=boundary2");
  run("Outline");

  imageCalculator("AND create 32-bit", "dist1", "boundary2");
  getRawStatistics(nPixels, mean, min, max, std);
  getPixelSize(unit, pw, ph);
  print("Mean distance: " + (mean * pw) + " " + unit);
'
```

---

## 7. Particle Analysis

### 7.1 Immunogold Detection

| Gold Size | Typical Use |
|-----------|------------|
| 5 nm | Double labeling (small) |
| 10 nm | Standard labeling |
| 15 nm | Double labeling (large) |
| 20 nm | Triple labeling |

```bash
python ij.py macro '
  original = getTitle();
  run("Duplicate...", "title=gold_detect");
  // Bandpass: filter_large > particle diameter in px, filter_small < particle diameter
  run("Bandpass Filter...", "filter_large=80 filter_small=10 suppress=None tolerance=5 autoscale saturate");
  // If gold is dark, invert first
  run("Invert");
  setAutoThreshold("MaxEntropy");
  run("Convert to Mask");
  run("Watershed");
  run("Set Measurements...", "area centroid shape feret redirect=None decimal=3");
  // Size filter: calculate expected area from gold diameter and pixel size
  // Allow ~50-200% of expected area for size tolerance
  run("Analyze Particles...", "size=1000-4000 circularity=0.7-1.0 show=Outlines display clear");
'
python ij.py results
```

**Density calculation:** `density = nResults / (imageWidth * pixelSize * imageHeight * pixelSize)`

**Double labeling:** Classify by Feret diameter — set boundary midpoint between the two gold sizes.

### 7.2 Nearest-Neighbor & Clustering (Clark-Evans)

```bash
python ij.py macro '
  n = nResults;
  nnDistances = newArray(n);
  getPixelSize(unit, pw, ph);
  for (i = 0; i < n; i++) {
    xi = getResult("X", i); yi = getResult("Y", i);
    minDist = 999999;
    for (j = 0; j < n; j++) {
      if (i != j) {
        d = sqrt(pow(xi-getResult("X",j),2) + pow(yi-getResult("Y",j),2));
        if (d < minDist) minDist = d;
      }
    }
    nnDistances[i] = minDist;
  }
  Array.getStatistics(nnDistances, min, max, mean, stdDev);
  // Clark-Evans: R = observed_mean_NN / expected_mean_NN
  getDimensions(w, h, c, s, f);
  density = n / (w * pw * h * ph);
  R = mean / (0.5 / sqrt(density));
  print("Clark-Evans R: " + R + " (R<1=clustered, R=1=random, R>1=dispersed)");
'
```

### 7.3 Gold-Organelle Association

```bash
python ij.py macro '
  // Check each gold centroid against organelle binary mask
  selectWindow("organelle_mask");
  getPixelSize(unit, pw, ph);
  associated = 0;
  for (i = 0; i < nResults; i++) {
    if (getPixel(round(getResult("X",i)/pw), round(getResult("Y",i)/ph)) > 128) associated++;
  }
  print("Associated: " + associated + "/" + nResults);
'
```

### 7.4 Nanoparticle Size Distribution

```bash
python ij.py macro '
  run("Duplicate...", "title=np_seg");
  run("Enhance Local Contrast (CLAHE)", "blocksize=63 histogram=256 maximum=3 mask=*None*");
  run("Gaussian Blur...", "sigma=2");
  setAutoThreshold("Otsu dark");
  run("Convert to Mask");
  run("Watershed");
  run("Set Measurements...", "area perimeter shape feret redirect=None decimal=3");
  run("Analyze Particles...", "size=50-Infinity circularity=0.4-1.0 show=Outlines display clear");
'
# Equivalent circular diameter: d = 2 * sqrt(area / PI)
# Polydispersity index (PDI): (stdDev/mean)^2
```

---

## 8. Stereology

### Key Parameters

| Parameter | Symbol | Estimates | Method |
|-----------|--------|-----------|--------|
| Volume density | Vv | Volume fraction | Point counting |
| Surface density | Sv | Surface area/volume | Intersection counting |
| Numerical density | Nv | Number/volume | Disector |

### Point Counting for Volume Fraction

```bash
python ij.py macro '
  // Automated point counting with binary mask
  selectWindow("organelle_mask");
  getDimensions(w, h, c, s, f);
  gridSpacing = 50;  // choose to give 100-200 points per structure

  hitCount = 0; totalCount = 0;
  for (x = gridSpacing/2; x < w; x += gridSpacing) {
    for (y = gridSpacing/2; y < h; y += gridSpacing) {
      totalCount++;
      if (getPixel(x, y) > 128) hitCount++;
    }
  }
  Vv = hitCount / totalCount;
  CE = sqrt(Vv * (1-Vv) / totalCount);
  print("Vv: " + Vv + "  CE: " + CE + " (target CE < 0.05)");
'

# Or use built-in grid:
python ij.py macro 'run("Grid...", "grid=Points area=10000 color=Cyan");'
```

### Physical Disector

For unbiased number estimation: compare two serial sections, count objects present in reference but absent in lookup. Nv = Q- / (a * h), where Q- = disector count, a = frame area, h = section separation.

```bash
python ij.py macro 'run("Combine...", "stack1=reference stack2=lookup");'
# User marks objects in left panel absent from right with Multi-point tool
```

---

## 9. Serial Section Analysis

### Methods Comparison

| Method | Section Thickness | Resolution | Dataset Size |
|--------|------------------|-----------|-------------|
| Serial section TEM | 50-100 nm | 1-5 nm lateral | Small |
| Array tomography | 50-200 nm | 5-100 nm | Medium |
| SBF-SEM | 25-50 nm | 5-10 nm | Medium-large |
| FIB-SEM | 4-10 nm | 4-10 nm (isotropic) | Small-medium |

### TrakEM2

Primary Fiji tool for serial section EM reconstruction. Handles alignment, segmentation, and 3D visualisation.

```bash
python ij.py macro 'run("TrakEM2 (New)");'
# Interactive GUI — user imports images, places landmarks, traces structures
```

**Scripting (Jython):**
```bash
python ij.py script --lang jython '
import ini.trakem2.Project as Project
project = Project.getProjects().get(0)
if project is not None:
    root = project.getRootLayerSet()
    print("Layers: " + str(root.size()))
'
```

**When to use TrakEM2:** <100 sections, manual tracing needed, moderate dataset (GB not TB).
**When NOT:** Very large datasets (use CATMAID/WebKnossos), fully automated segmentation (use Weka/ilastik/DL).

### Alignment Without TrakEM2

```bash
# StackReg — rigid/affine
python ij.py macro '
  run("Image Sequence...", "open=/path/to/sections/ sort");
  run("StackReg", "transformation=[Rigid Body]");
'

# Register Virtual Stack Slices — for large datasets
python ij.py macro '
  run("Register Virtual Stack Slices",
    "source=/path/to/input/ output=/path/to/output/ feature=[Rigid]" +
    " registration=[Rigid -- translate + rotate] shrinkage=1 save");
'

# bUnwarpJ — elastic/nonlinear (for section deformation)
python ij.py macro '
  run("bUnwarpJ",
    "source_image=section1 target_image=section2 registration=Accurate" +
    " img_subsamp_fact=0 initial_deformation=[Very Coarse] final_deformation=Fine" +
    " divergence_weight=0 curl_weight=0 landmark_weight=0 image_weight=1" +
    " consistency_weight=10 stop_threshold=0.01");
'
```

### 3D Reconstruction and Measurement

```bash
python ij.py macro '
  // Set voxel calibration: XY from EM, Z = section thickness
  run("Properties...", "pixel_width=5 pixel_height=5 voxel_depth=70 unit=nm");

  // Segment across stack
  run("Duplicate...", "title=seg_stack duplicate");
  run("Gaussian Blur...", "sigma=2 stack");
  setAutoThreshold("Otsu dark");
  run("Convert to Mask", "method=Otsu background=Dark calculate");

  // 3D object analysis
  run("3D Objects Counter", "threshold=128 slice=1 min.=1000 max.=9999999 objects statistics");
'
```

---

## 10. CLEM (Correlative Light-Electron Microscopy)

### Challenges

| Challenge | Description |
|-----------|-------------|
| Scale mismatch | Fluorescence um/px vs EM nm/px (100-1000x difference) |
| Rotation/flip | Section may be transformed between modalities |
| Deformation | EM processing distorts tissue |
| Registration | Non-rigid deformation needs elastic registration |

### BigWarp (Primary CLEM Tool)

```bash
python ij.py macro '
  open("/path/to/fluorescence.tif"); rename("fluorescence");
  open("/path/to/em.tif"); rename("em");
  run("Big Warp", "moving_image=fluorescence fixed_image=em");
  // User places 10-20 corresponding landmarks (blood vessels, nuclei, cell boundaries)
  // More landmarks near ROI, spread across field
  // Minimum 4 for thin-plate spline
  // File > Export moving image for registered result
'
```

### Simple Manual CLEM Overlay

```bash
python ij.py macro '
  // Calculate scale factor from pixel sizes
  selectWindow("fluorescence");
  getPixelSize(funit, fpw, fph);
  selectWindow("em");
  getPixelSize(eunit, epw, eph);
  scaleFactor = fpw / epw;

  selectWindow("fluorescence");
  run("Scale...", "x=" + (1.0/scaleFactor) + " y=" + (1.0/scaleFactor) +
    " interpolation=Bilinear create title=fluor_scaled");
  // Then rotate/translate to align, merge as RGB overlay
'
```

### Fiducial Markers

| Type | Size | Visible In |
|------|------|-----------|
| TetraSpeck beads | 100-500 nm | Fluorescence + EM |
| Gold nanoparticles | 50-200 nm | EM (some fluorescent) |
| Cell landmarks | Variable | Both (nuclei, membranes) |

---

## 11. SEM-Specific Analysis

### Porosity

```bash
python ij.py macro '
  run("Duplicate...", "title=pore_seg");
  run("Enhance Local Contrast (CLAHE)", "blocksize=127 histogram=256 maximum=3 mask=*None*");
  run("Gaussian Blur...", "sigma=2");
  setAutoThreshold("Otsu");
  run("Convert to Mask");
  // Ensure pores are white, solid is black — invert if needed
  getRawStatistics(nPixels, mean);
  porosity = mean / 255.0;
  print("Porosity: " + d2s(porosity * 100, 1) + "%");
  run("Analyze Particles...", "size=10-Infinity show=Outlines display clear summarize");
'
```

### Fiber Diameter (Skeleton + Distance Transform)

```bash
python ij.py macro '
  run("Duplicate...", "title=fiber_seg");
  run("Gaussian Blur...", "sigma=2");
  setAutoThreshold("Otsu dark");
  run("Convert to Mask");

  // Distance map: value at each pixel = distance to boundary
  run("Duplicate...", "title=dist_transform");
  run("Distance Map");

  // Skeleton: centerlines of fibers
  selectWindow("fiber_seg");
  run("Duplicate...", "title=skeleton");
  run("Skeletonize");

  // Distance at skeleton = radius; diameter = 2x
  imageCalculator("AND create 32-bit", "dist_transform", "skeleton");
  setThreshold(1, 999999);
  run("Measure");
'
# DiameterJ plugin provides more sophisticated analysis if installed
```

### Surface Roughness (Proxy)

```bash
python ij.py macro '
  run("Duplicate...", "title=roughness");
  run("Subtract Background...", "rolling=200");
  getRawStatistics(n, mean, min, max, stdDev);
  print("Roughness proxy (intensity SD): " + stdDev);
'
```

### Fracture Surface / Texture

```bash
python ij.py macro '
  // Local variance map: high = rough, low = smooth
  run("Duplicate...", "title=local_sd");
  run("Variance...", "radius=5");  // adjust radius to feature scale
'
```

---

## 12. Cryo-EM Considerations

Most cryo-EM processing uses RELION, cryoSPARC, or EMAN2 — **not ImageJ**. ImageJ is useful for:

| Task | ImageJ Role |
|------|-------------|
| Micrograph inspection | Open .mrc, adjust display |
| CTF visualisation | FFT to see Thon rings |
| Particle inspection | Extract boxes at coordinates |
| Class average montage | Make Montage from .mrcs stack |
| Tomogram navigation | Browse Z-slices |

```bash
# Open and inspect micrograph
python ij.py macro 'run("Bio-Formats Importer", "open=/path/to/micrograph.mrc color_mode=Grayscale");'

# CTF ring visualisation
python ij.py macro 'run("FFT");'
# Symmetric rings = good; asymmetric = astigmatism; fading = resolution limit

# 2D class average montage
python ij.py macro '
  run("Bio-Formats Importer", "open=/path/to/class2d.mrcs color_mode=Grayscale");
  run("Make Montage...", "columns=10 rows=10 scale=1");
'
```

**Do NOT use ImageJ for:** CTF correction, particle picking, 3D reconstruction, or CTF-unaware filtering on cryo-EM data.

---

## 13. Common Problems and Artifacts

| Problem | Cause | Solution |
|---------|-------|---------|
| Uneven staining | Section thickness variation | Background subtraction, CLAHE, local thresholding, flat-field correction |
| Knife marks | Ultramicrotome blade | Directional bandpass filter (suppress Horizontal/Vertical in FFT) |
| Compression | Cutting deformation | Scale in cutting direction by 1/compressionRatio |
| Charging (SEM) | Non-conductive areas | Remove Outliers (bright), or mask out and exclude |
| Beam damage | Electron dose | Cannot correct; detect by comparing center vs edge contrast SD |
| Scale bar mismatch | Metadata/label disagreement | Draw line along scale bar, measure, compare to label |
| Section folds | Pickup artifact | Detect dark regions with heavy blur + threshold, create exclusion mask |
| Contamination | Hydrocarbon deposition | Remove Outliers (dark) |

### Uneven Staining Solutions

```bash
# Background subtraction
python ij.py macro 'run("Subtract Background...", "rolling=100");'

# Local thresholding (when global fails)
python ij.py macro 'run("Auto Local Threshold", "method=Phansalkar radius=25 parameter_1=0 parameter_2=0 white");'

# Flat-field correction
python ij.py macro '
  run("Duplicate...", "title=bg");
  run("Gaussian Blur...", "sigma=100");
  imageCalculator("Divide create 32-bit", "original", "bg");
  close("bg");
'
```

### Knife Mark Removal

```bash
python ij.py macro '
  run("Bandpass Filter...", "filter_large=500 filter_small=2 suppress=Horizontal tolerance=5 autoscale saturate");
  // "Horizontal" or "Vertical" depending on knife mark orientation
'
```

---

## 14. Decision Trees

### Segmentation Approach

```
High-contrast boundary?
├── YES → Touching other structures?
│   ├── NO → Global threshold (Otsu/Triangle) + Analyze Particles
│   └── YES → Threshold + Watershed → still touching? → Weka or manual
└── NO → Training data available?
    ├── YES → Trainable Weka Segmentation or deep learning
    └── NO → CLAHE + local threshold → still poor? → Manual (TrakEM2/ROI Manager)
```

### Threshold Method

```
Intensity distribution?
├── Bimodal (two peaks) → Otsu
├── Unimodal with tail → Triangle
├── Uneven background → Local threshold (Phansalkar, Bernsen)
├── Very noisy → Pre-filter then Otsu
└── Multiple populations → Multi-threshold or manual
```

```bash
python ij.py explore Otsu Triangle Li Huang MaxEntropy
```

### Registration Method (Serial Sections)

```
Deformation level?
├── Minimal (translation+rotation) → StackReg Rigid Body
├── Moderate (scaling+shear) → StackReg Affine / Register Virtual Stack Slices
├── Significant (nonlinear) → bUnwarpJ or TrakEM2 elastic
└── Very large dataset (>100 sections) → TrakEM2 / Register Virtual Stack Slices (virtual)
```

### EM Software Selection

```
Task?
├── 2D measurements → ImageJ/Fiji
├── Serial reconstruction (<100 sections) → TrakEM2
├── Large volume (>1 GB) → ilastik/Weka (auto) or CATMAID/WebKnossos (manual)
├── Cryo-EM SPA → RELION/cryoSPARC (NOT ImageJ)
├── Cryo-ET → IMOD for recon, Fiji for visualisation
├── CLEM registration → BigWarp
└── Stereology → ImageJ grid overlay + manual counting
```

---

## 15. Macro Quick Reference

| Task | Macro |
|------|-------|
| Open EM file | `run("Bio-Formats Importer", "open=/path/file.dm3 color_mode=Grayscale");` |
| Set scale | `run("Set Scale...", "distance=100 known=50 unit=nm");` |
| Get pixel size | `getPixelSize(unit, pw, ph);` |
| CLAHE | `run("Enhance Local Contrast (CLAHE)", "blocksize=127 histogram=256 maximum=3 mask=*None*");` |
| Median filter | `run("Median...", "radius=2");` |
| Gaussian blur | `run("Gaussian Blur...", "sigma=2");` |
| Background subtract | `run("Subtract Background...", "rolling=50");` |
| Bandpass filter | `run("Bandpass Filter...", "filter_large=40 filter_small=3 suppress=None tolerance=5 autoscale saturate");` |
| Auto threshold | `setAutoThreshold("Otsu"); run("Convert to Mask");` |
| Local threshold | `run("Auto Local Threshold", "method=Phansalkar radius=25 parameter_1=0 parameter_2=0 white");` |
| Fill holes | `run("Fill Holes");` |
| Watershed | `run("Watershed");` |
| Morphological open | `run("Open");` |
| Morphological close | `run("Close-");` |
| Skeletonize | `run("Skeletonize");` |
| Analyze Skeleton | `run("Analyze Skeleton (2D/3D)", "prune=[shortest branch] show");` |
| Distance map | `run("Distance Map");` |
| Find edges | `run("Find Edges");` |
| Set measurements | `run("Set Measurements...", "area perimeter shape feret redirect=None decimal=3");` |
| Analyze particles | `run("Analyze Particles...", "size=100-Infinity circularity=0.0-1.0 show=Outlines display clear summarize");` |
| Grid overlay | `run("Grid...", "grid=Points area=10000 color=Cyan");` |
| Invert | `run("Invert");` |
| Duplicate | `run("Duplicate...", "title=copy");` |
| Outline (boundary) | `run("Outline");` |
| Remove outliers | `run("Remove Outliers...", "radius=5 threshold=50 which=Bright");` |
| StackReg | `run("StackReg", "transformation=[Rigid Body]");` |
| 3D Objects Counter | `run("3D Objects Counter", "threshold=128 min.=100 max.=9999999 objects statistics");` |
| Variance map | `run("Variance...", "radius=5");` |

### Fiji Plugins for EM

| Plugin | Purpose | Update Site |
|--------|---------|-------------|
| TrakEM2 | Serial section reconstruction | Built-in |
| Trainable Weka | ML pixel classification | Built-in |
| StackReg/TurboReg | Rigid registration | Built-in |
| bUnwarpJ | Elastic registration | Built-in |
| BigWarp | Landmark deformable registration | Built-in |
| AnalyzeSkeleton | Branch analysis | Built-in |
| 3D Objects Counter | 3D particle analysis | Built-in |
| MorphoLibJ | Morphology, labels, distance maps | IJPB-plugins |
| DiameterJ | Nanofiber diameter | DiameterJ |
| Auto Local Threshold | Adaptive thresholding | Built-in |
| Bio-Formats | .dm3/.dm4/.mrc/.ser reader | Built-in |
