# Microscopy Image Analysis — Domain Reference

Comprehensive reference for an AI agent controlling ImageJ/Fiji. Covers microscopy
modalities, standard quantitative analyses, quality control, and domain-specific plugins.

---

## 1. Microscopy Modalities and Their Analysis Needs

### 1.1 Widefield Fluorescence

**What it is**: Excitation light illuminates the entire field. All fluorescence
(in-focus + out-of-focus) is collected. Cheapest and fastest fluorescence modality.

**Key analysis needs**:

- **Flat-field correction**: Uneven illumination across the field causes intensity
  gradients that corrupt quantification. Corrected via:
  `I_true = (I_measured - I_dark) / I_flat`
  where I_dark is a no-light exposure and I_flat is a uniform fluorescent slide.
  ImageJ: `BaSiC` plugin (automatic), or manual calculator:
  ```
  imageCalculator("Subtract create", "raw", "dark");
  imageCalculator("Divide create", "corrected", "flatfield");
  ```
  BigStitcher also has built-in flat-field correction for tiled acquisitions.

- **Deconvolution**: Removes out-of-focus blur using the point spread function (PSF).
  Iterative constrained algorithms (Richardson-Lucy) vastly outperform inverse/Wiener
  filters on real microscopy data. Requires measured or theoretical PSF.
  ImageJ plugins: `DeconvolutionLab2`, `Iterative Deconvolve 3D`.
  **Always deconvolve before colocalization analysis** — it acts as a smart noise
  filter that increases contrast while suppressing noise.

- **Bleach correction**: Photobleaching causes progressive intensity loss in
  time-lapses. Three algorithms available in Fiji's `Bleach Correction` plugin:
  - Simple ratio: normalizes each frame to the first
  - Exponential fitting: fits exponential decay, corrects
  - Histogram matching: matches each frame's histogram to the first
  **Must correct before any temporal intensity analysis (calcium, FRAP, etc.)**

### 1.2 Confocal (Laser Scanning / Spinning Disk)

**What it is**: Pinhole rejects out-of-focus light. Optical sectioning produces
clean z-stacks. The workhorse of biological fluorescence imaging.

**Key analysis needs**:

- **Z-stack processing**: Z Project (Max/Mean/Sum Intensity), 3D rendering,
  orthogonal views. **Never measure intensity from Max projections** — use Sum
  projection to preserve quantitative relationships.
  ```
  run("Z Project...", "projection=[Sum Slices]");  // for quantification
  run("Z Project...", "projection=[Max Intensity]");  // for visualization only
  ```

- **3D analysis**: Use 3D plugins directly rather than flattening to 2D:
  - `3D Objects Counter`: threshold + label + measure in 3D
  - `MorphoLibJ`: morphological segmentation, watershed, 3D labeling
  - `3D ImageJ Suite`: 64+ commands for 3D processing and analysis
  - `CLIJ2`: GPU-accelerated 3D processing (504 commands)

- **Colocalization**: See section 2.2 for full details. Key point: confocal
  data should be deconvolved before colocalization. Use `Coloc 2` plugin.

- **Chromatic aberration**: Different wavelengths focus at slightly different
  positions. Correct with channel alignment or measure with sub-resolution beads.
  Can produce false colocalization if uncorrected.

### 1.3 Two-Photon / Multiphoton

**What it is**: Near-infrared excitation via two-photon absorption. Deeper tissue
penetration (500-1000 um), less phototoxicity, intrinsic optical sectioning.
Used heavily for in vivo brain imaging.

**Key analysis needs**:

- **Motion correction**: In vivo imaging suffers from breathing, heartbeat, and
  brain motion artifacts. Registration-based correction is essential before analysis.
  ImageJ: `TurboReg`, `StackReg`, `Correct 3D Drift`.
  Python: Suite2p and CaImAn have built-in motion correction.

- **Calcium imaging analysis**: Extract fluorescence traces from neurons, detect
  calcium transients, compute dF/F or dF/F0.
  - `TACI` (TrackMate Analysis of Calcium Imaging): ImageJ plugin for 3D calcium imaging
  - `MCA` (Multicellular Analysis): ImageJ toolbox for functional imaging
  - `Suite2p` / `CaImAn`: Python tools (external, not ImageJ-native)
  - Basic workflow in ImageJ:
    1. Motion correct the time-lapse
    2. Draw ROIs around neurons (or use segmentation)
    3. `Set Measurements` → Mean, IntDen
    4. `Multi Measure` across all frames
    5. Export to spreadsheet, calculate dF/F = (F - F0) / F0

- **Scattering correction**: Deep tissue causes signal attenuation with depth.
  Exponential correction can partially compensate.

### 1.4 Light Sheet (SPIM / Lattice Light Sheet)

**What it is**: Thin sheet of light illuminates one plane at a time. Very fast,
low phototoxicity, large volumes. Generates massive datasets (terabytes).

**Key analysis needs**:

- **Large data handling**: Datasets can be 1-10+ TB. Cannot load into RAM.
  Use BigDataViewer (BDV) for lazy-loading visualization, HDF5/N5/Zarr formats.

- **Registration and fusion**: Multi-view acquisitions from different angles
  must be registered and fused. **BigStitcher** is the standard tool:
  - Handles multi-tile, multi-angle, multi-channel, time-lapse
  - Supports images up to many terabytes
  - Automatic tile alignment via phase correlation
  - Multi-view registration via interest point detection
  - Weighted average fusion or multi-view deconvolution
  - Flat-field correction built in
  - Install via Fiji update site: `BigStitcher`

- **Stripe artifact removal**: Light sheet creates striping from absorption.
  Dual-illumination or post-processing can reduce this.

### 1.5 Super-Resolution (STORM / PALM / SIM / STED)

**What it is**: Breaks the diffraction limit (~200 nm). Different approaches
achieve 20-120 nm resolution.

**Key analysis needs**:

- **Single-molecule localization (STORM/PALM)**: Raw data is thousands of frames
  of blinking fluorophores. Must be reconstructed into a super-resolution image.
  - `ThunderSTORM`: The standard ImageJ plugin for SMLM reconstruction.
    Comprehensive: detection, localization, filtering, drift correction,
    rendering (Gaussian, histogram, average shifted histogram).
    Has Monte Carlo simulation for performance evaluation.
  - Key parameters: detection threshold, fitting method (Gaussian PSF),
    lateral uncertainty, drift correction method.
  - Resolution measurement: Fourier Ring Correlation (FRC) gives
    data-driven resolution estimate.

- **Structured Illumination (SIM)**: Uses patterned illumination + computation.
  2x resolution improvement. Reconstruction typically done in acquisition software
  (Zeiss ZEN, Nikon NIS). `SIMcheck` ImageJ plugin validates reconstruction quality.
  Artifacts: honeycomb pattern indicates bad reconstruction.

- **STED**: Hardware-based, no reconstruction needed. Analysis same as confocal
  but at higher resolution. Main concern: increased photobleaching.

### 1.6 Electron Microscopy (TEM / SEM / FIB-SEM)

**What it is**: Electron beam imaging at nanometer resolution. No fluorescence —
contrast from heavy metal staining or backscatter.

**Key analysis needs**:

- **Segmentation**: EM images are noisy and dense. Manual segmentation is common
  but extremely slow (weeks per GB).
  - `TrakEM2`: Fiji plugin for manual and semi-automatic EM segmentation.
    Handles large volumes, supports skeletonization of neuronal arbors,
    synapse annotation, and 3D meshes.
  - `IMOD`: External tool for contour tracing, stereology, 3D meshing.
  - `Weka Segmentation`: Trainable pixel classifier in Fiji, useful for
    EM membrane/organelle detection.
  - `ilastik`: Interactive ML segmentation (requires separate install).

- **Stereology**: Systematic sampling + counting for unbiased volume/number
  estimates. IMOD has stereology module. Scales linearly with dataset size
  (hours vs weeks for full segmentation).

- **Measurement**: Membrane thickness, organelle dimensions, vesicle counts,
  synapse density. Scale bars are critical — EM has no embedded calibration
  metadata like fluorescence.

### 1.7 Brightfield / H&E / Histology

**What it is**: Transmitted light with tissue stains (H&E, IHC with DAB, etc.).
Digital pathology with whole-slide scanners.

**Key analysis needs**:

- **Color deconvolution**: Separates stain contributions from an RGB image.
  Based on Ruifrok & Johnston method using stain vectors (normalized color
  representations of each pure stain).
  - ImageJ: `Colour Deconvolution 2` plugin by Gabriel Landini
  - QuPath: Built-in with preset vectors for H&E, H-DAB
  - Key: stain vectors must match your staining; use `Estimate stain vectors`
    for best results on your specific tissue/protocol.
  - **Limitation**: Output should not be interpreted quantitatively — the
    method provides approximate unmixing, not true spectral decomposition.
  ```
  // ImageJ macro for H&E deconvolution:
  run("Colour Deconvolution", "vectors=[H&E]");
  // Creates 3 images: Hematoxylin, Eosin, Residual
  ```

- **Tissue segmentation**: Detect tissue vs background, classify regions.
  QuPath is the gold standard for whole-slide analysis:
  - Simple tissue detection via thresholding
  - Cell detection with nuclear + cell boundary segmentation
  - Trainable classifiers for tissue types
  - StarDist integration for nuclei
  - Export to ImageJ for further analysis

- **IHC quantification**: DAB (brown) intensity as proxy for protein expression.
  After H-DAB deconvolution, measure DAB channel intensity.
  H-score = (% weak * 1) + (% moderate * 2) + (% strong * 3).

### 1.8 Live Imaging / Time-Lapse

**What it is**: Repeated imaging over time to capture dynamic processes —
cell migration, division, signaling, morphogenesis.

**Key analysis needs**:

- **Cell tracking**: Follow individual cells across frames.
  - `TrackMate`: The standard Fiji tracking plugin.
    - Detectors: LoG, DoG, StarDist, Cellpose, Weka, ilastik, MorphoLibJ
    - Trackers: LAP (Jaqaman), Kalman, overlap
    - Supports splitting (mitosis) and merging events
    - TrackScheme: lineage visualization (like a train schedule)
    - Works in 2D and 3D
    - Exports: track statistics, spot features, XML
    - `TrackAnalyzer`: Extension for holistic track analysis
  ```
  // Basic TrackMate macro (requires v7+):
  run("TrackMate", "use_gui=false detector=LOG radius=5 threshold=50 tracker=LAP");
  ```

- **Mitosis / cell division detection**: TrackMate's LAP tracker can detect
  splitting events. `TrackMate-Oneat` extension specifically identifies cell
  divisions using deep learning (MARI principle — daughter cells emerge
  perpendicular to mother cell's nuclear major axis).

- **Wound healing / migration assays**: Track wound closure over time.
  Measure wound area per frame, migration speed, directionality.
  PIV analysis (see section 2.10) quantifies collective migration velocity fields.

- **Kymographs**: Space-time plots along a line ROI across a time-lapse.
  Generated in ImageJ with `Image > Stacks > Reslice` on a line selection.
  `KymographClear`: automatic direction color-coding.
  `KymographDirect`: automated quantitative extraction.

### 1.9 Phase Contrast / DIC (Differential Interference Contrast)

**What it is**: Label-free contrast methods. Phase contrast converts phase
shifts to intensity. DIC produces a pseudo-3D shadow effect.

**Key analysis needs**:

- **Cell boundary detection**: The main challenge. Phase contrast produces a
  bright halo artifact around cell boundaries that confounds segmentation.
  Strategies:
  - **Deep learning**: Cellpose and StarDist work well on phase contrast
    (both have pre-trained models for this modality)
  - **Halo removal**: K-means clustering to extract and remove the halo,
    then apply active contour / watershed
  - **DIC advantage**: No bright halo, but shadow artifacts instead.
    Edge detection + morphological operations work better than thresholding.
  - `Weka Segmentation`: Train a pixel classifier on examples of
    cell interior / boundary / background — handles artifacts well.

- **Confluency measurement**: Percentage of field covered by cells.
  Threshold + measure area fraction:
  ```
  run("8-bit");
  setAutoThreshold("Huang");
  run("Measure");  // check %Area
  ```

---

## 2. Standard Quantitative Analyses

### 2.1 Corrected Total Cell Fluorescence (CTCF)

**What it is**: Background-corrected, area-normalized fluorescence measurement.
The standard method for comparing protein expression levels between cells
from fluorescence microscopy images.

**Formula**:
```
CTCF = Integrated Density - (Area_cell × Mean_background)
```

**Why use CTCF instead of raw Integrated Density**: Raw IntDen incorrectly
suggests cells with different morphologies (sizes) have different total
fluorescence. CTCF corrects for both background and cell area.

**ImageJ workflow**:
```
// 1. Set measurements
run("Set Measurements...", "area mean integrated display redirect=None decimal=3");

// 2. Draw ROI around cell, measure
// (for each cell)
roiManager("Select", cellIndex);
run("Measure");
intDen = getResult("IntDen", nResults-1);
cellArea = getResult("Area", nResults-1);

// 3. Measure background (3+ regions near cell, no fluorescence)
roiManager("Select", bgIndex);
run("Measure");
bgMean = getResult("Mean", nResults-1);

// 4. Calculate CTCF
ctcf = intDen - (cellArea * bgMean);
setResult("CTCF", row, ctcf);
```

**Best practices**:
- Measure 3+ background regions per cell, average them
- Background regions should be near the cell but with no fluorescence
- Same exposure settings across all images being compared
- Do NOT normalize or enhance contrast before measuring
- Report CTCF per cell, not per pixel

### 2.2 Colocalization Analysis

**What it is**: Quantifies whether two proteins (labeled with different fluorophores)
occupy the same spatial location. Critical for protein interaction studies.

**Methods and when to use each**:

| Method | What it measures | When to use | Range |
|--------|-----------------|-------------|-------|
| **Pearson's (PCC)** | Linear correlation of pixel intensities | Quick overview; are signals correlated? | -1 to +1 |
| **Manders' M1/M2** | Fraction of signal A overlapping B (and vice versa) | "What % of protein A colocalizes with B?" | 0 to 1 |
| **Costes threshold** | Automatic threshold for Manders' | Always use with Manders' — removes bias | N/A |
| **Li ICQ** | Intensity correlation quotient | Similar to Pearson's, more robust | -0.5 to +0.5 |

**Key decision**: If reviewers ask "what fraction of protein X colocalizes with
protein Y?", use **Manders' coefficients with Costes thresholding**. PCC alone
is less informative because it doesn't tell you the fraction.

**Costes method**: Automatically determines the threshold below which PCC drops
to zero. This defines which pixels are "signal" vs "noise" for Manders' calculation.
Removes user bias from threshold selection.

**ImageJ workflow with Coloc 2**:
```
// 1. Split channels if needed
run("Split Channels");

// 2. Coloc 2 (set both channels, ROI optional)
// In Fiji: Analyze > Colocalization > Coloc 2
// Set channel 1 and channel 2 images
// Check: Manders (with Costes threshold), Pearson's, Costes significance
```

**Critical requirements**:
- **Single-labeled controls**: Image each fluorophore alone to check for bleed-through
- **Deconvolve first**: Blur causes artificial overlap
- **No max projections**: Analyze colocalization in 3D (z-stack) or individual slices
- **Costes randomization test**: p < 0.05 means colocalization is significant
  (not due to random chance)
- **Correct chromatic aberration**: Different wavelengths focus at different z-positions
- **Adequate sampling (Nyquist)**: Pixel size must be ≤ half the resolution limit

### 2.3 Particle / Cell Counting

**Methods ranked by complexity**:

1. **Manual counting** with Cell Counter plugin — gold standard, very slow
2. **Threshold + Analyze Particles** — fast, works for well-separated objects
3. **Find Maxima** — detects intensity peaks, good for dense/touching nuclei
4. **Watershed** — separates touching round objects after thresholding
5. **StarDist** — deep learning nuclei detection, handles touching/overlapping
6. **Cellpose** — deep learning cell segmentation (nuclei and cytoplasm)

**StarDist vs Cellpose**:
- StarDist: faster, excellent for nuclei (star-convex shapes), outperforms on
  large datasets with low signal-to-background. Pre-trained models available.
- Cellpose: more general (any cell shape), no training needed for many cases,
  but slower. Better when cells have irregular shapes.
- Both available as Fiji plugins via update sites.

**Standard workflow** (threshold-based):
```
run("Duplicate...", "title=mask");
run("Gaussian Blur...", "sigma=1");
setAutoThreshold("Otsu");
run("Convert to Mask");
run("Watershed");
run("Analyze Particles...", "size=50-5000 circularity=0.5-1.0 show=Outlines display summarize exclude");
```

### 2.4 Morphometry (Shape Descriptors)

**ImageJ shape measurements** (via `Set Measurements > Shape descriptors`):

| Descriptor | Formula | Interpretation |
|-----------|---------|---------------|
| **Area** | Pixel count × calibrated pixel area | Object size |
| **Perimeter** | Length of outer boundary | Edge complexity |
| **Circularity** | 4π × Area / Perimeter² | 1.0 = perfect circle, →0 = elongated |
| **Roundness** | 4 × Area / (π × Major axis²) | Inverse of aspect ratio |
| **Aspect Ratio** | Major axis / Minor axis | Elongation (1 = circular) |
| **Solidity** | Area / Convex area | Roughness (1 = smooth) |
| **Feret diameter** | Max caliper distance | Longest dimension |
| **MinFeret** | Min caliper distance | Shortest dimension |

**When to use which**:
- **Cell size comparison**: Area (calibrated!)
- **Cell shape changes** (e.g., EMT): Circularity + Aspect Ratio
- **Neurite/process detection**: Low circularity, high aspect ratio
- **Membrane blebbing**: Low solidity (irregular boundary)
- **Particle size distribution**: Feret diameter (closest to physical "diameter")

### 2.5 Intensity Profiles

**Line profiles**: Intensity along a drawn line. Used for:
- Membrane vs cytoplasm vs nucleus distribution
- Channel alignment verification
- Gradient measurements
```
makeLine(x1, y1, x2, y2);
run("Plot Profile");
```

**Radial profiles**: Intensity as function of distance from center.
`Radial Profile` plugin: radially averages intensity around a point.
Used for: focal adhesion analysis, nuclear envelope studies, diffraction patterns.

**Kymographs**: Space-time intensity plot along a line across a time-lapse.
Shows particle/organelle movement speed and direction.
```
// Draw line along transport path, then:
// Image > Stacks > Reslice
// Slope of features = velocity
```

### 2.6 Nuclear/Cytoplasmic Ratio (N/C Ratio)

**What it is**: Ratio of mean nuclear to mean cytoplasmic fluorescence intensity.
Measures protein translocation (e.g., NF-kB activation, transcription factor
nuclear import).

**Formula**:
```
N/C ratio = Mean_nuclear_intensity / Mean_cytoplasmic_intensity
```

**Workflow**:
1. Segment nuclei (DAPI/Hoechst channel) → nuclear ROIs
2. Expand nuclear ROIs by N pixels → cell ROIs
3. Subtract nuclear ROI from cell ROI → cytoplasmic ROI
4. Measure mean intensity in nuclear and cytoplasmic ROIs
5. Calculate ratio

**ImageJ tools**:
- `Cyt/Nuc` macro: automated nuclear vs cytoplasmic measurement
- `Intensity Ratio Nuclei Cytoplasm Tool` (MRI): ImageJ macro toolset
- Manual: ROI Manager + `Enlarge` + XOR selection

**Pitfalls**:
- Cytoplasmic ROI must exclude nucleus entirely
- Background subtract before measuring
- Works best with clear nuclear marker in separate channel

### 2.7 Distance Measurements

**Nearest neighbor distance**: Distance from each object to its closest neighbor.
Reveals clustering (short distances) or regularity (uniform distances).

**Plugins**:
- `BioVoxxel Toolbox`: Nearest neighbor distance with mean and median options
- `MosaicIA`: Spatial pattern analysis using nearest-neighbor Gibbs model.
  Infers pair-wise interaction potential (attraction/repulsion).
- `Spatial Statistics 2D/3D`: Computes:
  - G-function: CDF of nearest-neighbor distances (pattern → neighbor)
  - F-function: CDF of distances from random points to nearest pattern point
  - H-function (Ripley's K): CDF of all pairwise distances
  Tests for significant clustering or dispersion vs random (CSR).
- `Distance Analysis`: Inter-channel object distances for colocalization

**Workflow for nearest neighbor**:
1. Segment objects → binary mask
2. `Analyze Particles` → get centroids
3. Calculate pairwise Euclidean distances
4. Report nearest neighbor per object

### 2.8 Skeleton / Branch Analysis (Neurites, Vasculature)

**Sholl analysis**: Counts intersections of neurites with concentric circles
centered on the soma. Quantifies branching complexity as a function of distance.

**SNT (Simple Neurite Tracer)**: The comprehensive Fiji toolbox for neuronal
morphometry. Supersedes NeuronJ and old Simple Neurite Tracer.
- Semi-automatic 3D tracing with path-finding
- Sholl analysis integrated
- Strahler analysis (branch ordering)
- Root angle analysis
- Import/export SWC morphology format
- Integrates with Allen Brain Atlas

**AnalyzeSkeleton**: Extracts skeleton features from binary skeletonized images.
- Branch length, number of endpoints, junctions, triple points
- Used for: neurite networks, vasculature, trabecular bone
```
// Skeletonize + analyze:
run("Skeletonize");
run("Analyze Skeleton (2D/3D)", "prune=none");
// Results table: branches, junctions, endpoints, avg branch length
```

**NeuronJ**: 2D-only neurite tracing. Still used but SNT is preferred for new work.

### 2.9 Texture Analysis (GLCM)

**What it is**: Gray Level Co-occurrence Matrix quantifies spatial relationships
between pixel intensities. Distinguishes tissue types, disease states.

**Key features**:

| Feature | Low value | High value |
|---------|-----------|------------|
| **Entropy** | Uniform texture | Random/complex texture |
| **Homogeneity** | Heterogeneous | Uniform texture |
| **Contrast** | Similar neighbors | Large local variations |
| **Correlation** | Random | Predictable patterns |
| **Energy (ASM)** | Random | Repetitive/ordered |

**ImageJ plugins**: `GLCM Texture` (Cabrera), `GLCM Texture Tool` (Cornish/Kvaal)

**Parameters to set**: Step size (distance between pixel pairs), angle (0°, 45°,
90°, 135° — or average all), and number of gray levels.

**Applications**: Tumor grading, tissue classification, surface roughness,
cell chromatin patterns.

### 2.10 Motion / Flow Analysis

**Particle Image Velocimetry (PIV)**: Measures displacement/velocity fields
between consecutive frames using cross-correlation of image subregions.

**ImageJ plugins**:
- `PIV` (Qingzong Tseng): iterative PIV with template matching
- `PIV analyser` (Jean-Yves Tinevez): block-based optic flow
- Both produce vector fields overlaid on images

**Applications**:
- Collective cell migration speed and direction
- Wound healing velocity
- Tissue morphogenesis flows
- Cytoplasmic streaming

**Wound healing assay workflow**:
1. Time-lapse of wound closure
2. Threshold tissue vs wound at each timepoint
3. Measure wound area per frame → plot closure curve
4. PIV between consecutive frames → velocity field
5. Report: closure rate (um/hr), migration speed, directionality

---

## 3. Quality Control Standards for Publication

### 3.1 Image Integrity Rules

**What reviewers and journals check** (based on QUAREP-LiMi guidelines and
Nature Methods community checklists):

**Mandatory (will be rejected if violated)**:
- No clipping/oversaturation: pixels at 0 or max value destroy data.
  Check with `Analyze > Histogram`. If first or last bin has large counts, image is clipped.
- No selective manipulation: adjustments must be applied uniformly to entire image
  and equally to all panels being compared
- No gamma/non-linear adjustments without disclosure: many journals prohibit
  non-linear manipulations entirely. If used, must be stated in figure legend.
- Brightness/contrast adjustments must be linear and applied identically across
  compared conditions
- Raw data must be preserved and available upon request

**Required in methods section** (QUAREP Bare Minimum Requirements):
1. Microscope type (widefield, confocal, etc.) and manufacturer/model
2. Objective: magnification, NA, immersion medium
3. Light source: type, wavelength, power (if measured)
4. Detector: type (PMT, HyD, camera model), gain/sensitivity settings
5. Filters: excitation/emission wavelengths or filter set names
6. Pixel size and bit depth
7. Acquisition software and version
8. Any post-processing: deconvolution algorithm, filtering, background subtraction
9. Analysis software and version (e.g., "Fiji/ImageJ v2.14.0")
10. Scale bars on all images

**Recommended (three-tier checklist)**:
- Tier 1 (Minimum): Basic hardware and software identification
- Tier 2 (Recommended): Detailed acquisition parameters, PSF measurement
- Tier 3 (Ideal): Full metadata export (OME-XML), raw data archival

### 3.2 OME Metadata Standards

The Open Microscopy Environment (OME) defines the community standard for
microscopy metadata. The OME Data Model captures:
- Microscope configuration (objective, detector, light source)
- Channel definitions (excitation, emission, fluorophore)
- Image dimensions and calibration (pixel size, time interval, z-step)
- Experiment and sample metadata

**4DN-BINA extensions**: Tiered metadata specifications that extend OME:
- MUST (required): Fields necessary for claim validation and reproducibility
- SHOULD (recommended): Fields for maximal image quality and data sharing
- MAY (optional): Fields for ideal metadata completeness

**Bio-Formats**: OME's file format library, built into Fiji. Reads 150+ formats
(.nd2, .czi, .lif, .oib, .ome.tiff, etc.) and extracts metadata automatically.
```
// Always use Bio-Formats for proprietary formats:
run("Bio-Formats Importer", "open=/path/to/file.nd2 color_mode=Composite");
// Check metadata:
run("Bio-Formats Metadata", "open=/path/to/file.nd2");
```

### 3.3 Common Mistakes That Invalidate Analyses

| Mistake | Why it's wrong | Fix |
|---------|---------------|-----|
| Measuring intensity from Max projections | Pixel value = max voxel along z, not total signal | Use Sum projection |
| Applying threshold to RGB images | RGB conflates brightness with color | Convert to single channel first |
| Comparing intensities across different exposures | Not comparable | Use identical acquisition settings |
| Using JPEG for quantitative data | Lossy compression alters pixel values | Always save as TIFF |
| Counting cells as biological replicates | Cells from one animal/dish = technical replicates | N = number of independent experiments |
| No background subtraction before intensity measurement | Absolute values include background | CTCF or rolling ball subtraction |
| Using `Enhance Contrast > Normalize` on data | Permanently rescales pixels, clips to range | Use `setMinAndMax()` for display only |
| Gamma correction without disclosure | Non-linear change, some journals prohibit | Declare in methods/legend |
| Measuring colocalization from max projections | Creates false overlap from different z-planes | Analyze z-stack or individual slices |
| No single-labeled controls for colocalization | Can't distinguish true coloc from bleed-through | Image each label alone |
| Thresholding without pre-processing | Noise creates false objects | Gaussian blur (sigma=1-2) first |
| Not correcting for multiple comparisons | Inflated p-values | Use Bonferroni, Tukey, or FDR correction |

---

## 4. Domain-Specific ImageJ Plugins by Research Field

### 4.1 Neuroscience

| Plugin | What it does | Key use |
|--------|-------------|---------|
| **SNT** | Semi-automatic neurite tracing in 2D/3D | Neuronal morphometry, SWC export |
| **Sholl Analysis** | Branching complexity vs distance from soma | Dendritic arborization |
| **AnalyzeSkeleton** | Branch/junction/endpoint quantification | Neurite networks |
| **NeuronJ** | 2D neurite tracing (legacy) | Simple 2D neurite length |
| **ABBA** | Brain atlas registration (Allen CCFv3) | Region-specific quantification |
| **TrackMate** | Cell/particle tracking in time-lapse | Neuronal migration, axon transport |
| **3D Viewer** | OpenGL volume rendering | Z-stack visualization |
| **CLIJ2** | GPU-accelerated processing | Large volume processing |

**ABBA details**: Aligning Big Brains & Atlases. Registers 2D brain sections
to Allen Mouse Brain Atlas (CCFv3) or Waxholm Rat Brain Atlas. Works with
Fiji + QuPath. 30 min for 80 sections including manual curation. Uses elastix
for automated registration, BigWarp for manual corrections.

### 4.2 Cell Biology

| Plugin | What it does | Key use |
|--------|-------------|---------|
| **StarDist** | Deep learning nuclei segmentation | Counting, shape analysis |
| **Cellpose** | Deep learning cell segmentation | Cell boundary detection |
| **MorphoLibJ** | Morphological operations, watershed, labeling | Advanced segmentation |
| **Weka Segmentation** | Trainable pixel classification | Complex segmentation tasks |
| **TrackMate** | Object tracking | Cell migration, division |
| **Coloc 2** | Colocalization analysis | Protein interaction studies |
| **BioVoxxel Toolbox** | Extended binary operations, watershed | Touching object separation |
| **Shape Filter** | Extended shape descriptors | Morphometric analysis |
| **Labkit** | Interactive ML segmentation | Quick segmentation with painting |

### 4.3 Calcium Imaging / Electrophysiology

| Tool | Platform | What it does |
|------|----------|-------------|
| **TACI** | ImageJ/Fiji | 3D calcium imaging analysis, handles z-motion |
| **MCA** | ImageJ/Fiji | Multicellular calcium analysis toolbox |
| **SynActJ** | Fiji + R Shiny | Automated synaptic activity analysis |
| **Suite2p** | Python | Motion correction + ROI detection + signal extraction |
| **CaImAn** | Python | Constrained NMF for calcium/voltage imaging |

**Basic calcium imaging in ImageJ** (when Python tools are overkill):
1. `Correct 3D Drift` or `StackReg` for motion correction
2. Draw ROIs or use `Multi-point` tool on neurons
3. `Set Measurements` > Mean
4. `Analyze > Tools > ROI Manager > Multi Measure`
5. Export CSV, calculate dF/F = (F(t) - F_baseline) / F_baseline

### 4.4 Histology / Pathology

| Plugin | What it does | Key use |
|--------|-------------|---------|
| **QuPath** | Digital pathology platform | Whole-slide analysis, cell detection |
| **Colour Deconvolution 2** | Stain separation (H&E, H-DAB) | IHC quantification |
| **StarDist** | Nuclear detection in histology | Cell counting in tissue |
| **Weka Segmentation** | Tissue type classification | Region annotation |
| **Bio-Formats** | Read whole-slide formats (.svs, .ndpi) | Data import |

**Color deconvolution vectors** (most common):
- H&E: Hematoxylin (blue-purple nuclei) + Eosin (pink cytoplasm)
- H-DAB: Hematoxylin + DAB (brown, IHC positive)
- FastRed-FastBlue: specific immunostaining combinations
- Custom vectors can be estimated from single-stained controls

### 4.5 Developmental Biology

| Plugin | What it does | Key use |
|--------|-------------|---------|
| **ABBA** | Brain atlas alignment | Region identification |
| **BigStitcher** | Large volume registration/fusion | Light sheet data |
| **Correct 3D Drift** | Registration across time | Time-lapse embryos |
| **TrakEM2** | Volume segmentation | Tissue morphology |
| **MorphoLibJ** | 3D morphological analysis | Organ morphometry |
| **TrackMate** | Cell lineage tracking | Cell fate mapping |
| **BigWarp** | Landmark-based registration | Atlas alignment |

### 4.6 Microbiology

| Plugin | What it does | Key use |
|--------|-------------|---------|
| **MicrobeJ** | Bacterial cell analysis | Shape, division, fluorescence |
| **ObjectJ** | Linked markers on image stacks | Colony counting |
| **BacStalk** | Bacterial stalk analysis | Caulobacter morphology |
| **Analyze Particles** | Standard particle analysis | Colony/cell counting |

---

## 5. Quick Decision Trees

### "Which threshold method should I use?"

```
Is the histogram bimodal (two clear peaks)?
  YES → Otsu (maximizes between-class variance)
  NO →
    Is the foreground small relative to background?
      YES → Triangle (fits triangle to histogram, finds corner)
      NO →
        Is it fluorescence microscopy?
          YES → Li (minimizes cross-entropy, great for fluorescence)
          NO →
            Low contrast / fuzzy edges?
              YES → Huang (fuzzy sets approach)
              NO → MaxEntropy (maximizes entropy of foreground/background)
```

### "Which colocalization metric should I report?"

```
Are you asking "is there a correlation between channels?"
  YES → Pearson's Correlation Coefficient (PCC)

Are you asking "what fraction of protein A overlaps with B?"
  YES → Manders' M1/M2 with Costes automatic threshold

Are you comparing colocalization between conditions?
  YES → Manders' M1/M2 (produces fractions suitable for stats)

Do you need a significance test?
  YES → Costes randomization test (built into Coloc 2)
```

### "How should I count my cells?"

```
Are cells well-separated and high contrast?
  YES → Threshold + Watershed + Analyze Particles

Are cells touching but round (nuclei)?
  YES → StarDist (or Threshold + Watershed if working)

Are cells irregular shape with varied sizes?
  YES → Cellpose

Is the image very noisy or low contrast?
  YES → Weka Segmentation (train on examples)

Is it a dense tissue section?
  YES → StarDist with "versatile_fluo" model

Do you need absolute ground truth?
  YES → Manual counting with Cell Counter plugin
```

---

## 6. Statistical Considerations

### What counts as N?

| Experiment type | N = | NOT N = |
|----------------|-----|---------|
| Animal study | Animals | Cells from one animal |
| Cell culture | Independent passages/plates | Cells from one well |
| Patient samples | Patients | Regions from one section |
| Time course | Time points (with caveats) | Frames from one movie |

### Which test to use?

| Comparison | Normal distribution? | Test |
|-----------|---------------------|------|
| Two groups | Yes | Unpaired t-test (or paired if matched) |
| Two groups | No / small N | Mann-Whitney U (unpaired) or Wilcoxon (paired) |
| 3+ groups | Yes | One-way ANOVA + Tukey post-hoc |
| 3+ groups | No | Kruskal-Wallis + Dunn's post-hoc |
| Two factors | Yes | Two-way ANOVA |
| Correlation | Yes | Pearson's r |
| Correlation | No | Spearman's rho |

**Always correct for multiple comparisons** when testing >2 groups or >1 hypothesis.
Bonferroni (conservative), Tukey (ANOVA post-hoc), or Benjamini-Hochberg FDR.

---

## Sources

- [CTCF Protocol — The Open Lab Book](https://theolb.readthedocs.io/en/latest/imaging/measuring-cell-fluorescence-using-imagej.html)
- [Colocalization Analysis — ImageJ.net](https://imagej.net/imaging/colocalization-analysis)
- [Practical Guide to Colocalization (Manders, Costes) — PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC3074624/)
- [QUAREP-LiMi Publication Standards](https://quarep.org/working-groups/wg-11-microscopy-publication-standards/)
- [Community Checklists for Publishing Images — Nature Methods](https://www.nature.com/articles/s41592-023-01987-9)
- [OME Tiered Metadata Specifications — Nature Methods](https://www.nature.com/articles/s41592-021-01327-9)
- [SNT Neurite Tracing — ImageJ.net](https://imagej.net/plugins/snt/)
- [Sholl Analysis — ImageJ.net](https://imagej.net/plugins/sholl-analysis)
- [ThunderSTORM for SMLM — PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC4207427/)
- [BigStitcher — ImageJ.net](https://imagej.net/plugins/bigstitcher/)
- [Bleach Correction Plugin — PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC7871415/)
- [Deconvolution — ImageJ.net](https://imagej.net/imaging/deconvolution)
- [MorphoLibJ — ImageJ.net](https://imagej.net/plugins/morpholibj)
- [TrackMate — ImageJ.net](https://imagej.net/plugins/trackmate/)
- [StarDist — ImageJ.net](https://imagej.net/plugins/stardist)
- [Cellpose vs StarDist Comparison — PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC11495889/)
- [QuPath Digital Pathology — Nature Scientific Reports](https://www.nature.com/articles/s41598-017-17204-5)
- [Colour Deconvolution 2 — Landini](https://blog.bham.ac.uk/intellimic/g-landini-software/colour-deconvolution-2/)
- [ABBA Brain Atlas Registration](https://abba-documentation.readthedocs.io/en/latest/)
- [TrakEM2 for EM Reconstruction — PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC3378562/)
- [Phase Contrast Segmentation Challenges — PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC3372640/)
- [MosaicIA Spatial Analysis — PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC4219334/)
- [Digital Image Ethics — UA Microscopy Alliance](https://microscopy.arizona.edu/learn/digital-image-ethics)
- [PIV Plugin for ImageJ — Qingzong Tseng](https://sites.google.com/site/qingzongtseng/piv)
- [TACI Calcium Imaging Plugin — PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC10388512/)
- [Coloc 2 — ImageJ.net](https://imagej.net/plugins/coloc-2)
- [GLCM Texture Analysis — ImageJ.net](https://imagej.net/ij/plugins/texture.html)
- [ImageJ Shape Descriptors — ImageJ.net](https://imagej.net/ij/docs/guide/146-30.html)
- [Kymograph Tutorial — ImageJ.net](https://imagej.net/tutorials/generate-and-exploit-kymographs)
