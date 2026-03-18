# ImageJ/Fiji Analysis Landscape — Complete Reference for ImageJAI Agent

Comprehensive catalog of every major image analysis domain, task, workflow, and
automation opportunity. Organized by research domain, with specific plugins,
macro commands, typical workflows, and pain points that an AI agent can address.

---

## Table of Contents

1. [Cell Biology & General Microscopy](#1-cell-biology--general-microscopy)
2. [Neuroscience](#2-neuroscience)
3. [Histology & Pathology](#3-histology--pathology)
4. [Fluorescence & Advanced Microscopy](#4-fluorescence--advanced-microscopy)
5. [Tracking & Time-Lapse](#5-tracking--time-lapse)
6. [3D Analysis & Volume Imaging](#6-3d-analysis--volume-imaging)
7. [Gel & Blot Analysis](#7-gel--blot-analysis)
8. [Plant Biology](#8-plant-biology)
9. [Materials Science & Physical Sciences](#9-materials-science--physical-sciences)
10. [Bone & Skeletal Analysis](#10-bone--skeletal-analysis)
11. [Publication & Visualization](#11-publication--visualization)
12. [Batch Processing & Automation](#12-batch-processing--automation)
13. [Machine Learning & Deep Learning](#13-machine-learning--deep-learning)
14. [GPU-Accelerated Processing](#14-gpu-accelerated-processing)
15. [Image Restoration & Preprocessing](#15-image-restoration--preprocessing)
16. [Community Resources & Training](#16-community-resources--training)
17. [Pain Points & Automation Opportunities](#17-pain-points--automation-opportunities)
18. [Self-Improving Agent Patterns](#18-self-improving-agent-patterns)

---

## 1. Cell Biology & General Microscopy

### 1.1 Cell Counting

**What**: Count cells in brightfield or fluorescence images. The single most
common ImageJ task.

**Plugins/Commands**:
- `Analyze Particles...` (built-in) — threshold-based counting
- `Find Maxima...` — point detection for touching/overlapping cells
- `StarDist 2D` — deep learning nuclei detection
- `Cellpose` — deep learning cell segmentation
- Cell Counter plugin — manual/semi-auto counting

**Workflow**:
1. Open image → convert to appropriate channel
2. Pre-process: Gaussian blur (sigma=1-2), background subtraction
3. Threshold: `setAutoThreshold("Otsu");` → `run("Convert to Mask");`
4. Watershed to separate touching cells
5. `run("Analyze Particles...", "size=50-Infinity display summarize");`

**Inputs**: Single-channel image (fluorescence DAPI/Hoechst, or brightfield)
**Outputs**: Count, per-cell measurements (area, mean intensity, centroid, circularity)

**Agent Automation Value**: HIGH — most tedious when done across hundreds of images.

### 1.2 Cell Morphology / Morphometry

**What**: Measure cell shape, size, aspect ratio, solidity, compactness.

**Plugins/Commands**:
- `Analyze Particles...` with shape descriptors
- `Set Measurements...` — configure: area, perimeter, circularity, aspect ratio, roundness, solidity, Feret's diameter
- MorphoLibJ — advanced morphological analysis
- Lusca plugin — automated morphological analysis of cellular/subcellular structures
- Cell-TypeAnalyzer — classify cells by user-defined morphological criteria

**Workflow**:
1. Segment cells (threshold or ML-based)
2. `run("Set Measurements...", "area perimeter shape feret's redirect=None decimal=3");`
3. `run("Analyze Particles...", "size=100-Infinity display");`
4. Export Results table

**Inputs**: Binary mask or segmented label image
**Outputs**: Per-cell measurements: area, perimeter, circularity, aspect ratio, solidity, Feret's diameter

### 1.3 CTCF / Corrected Total Cell Fluorescence

**What**: Quantify fluorescence intensity per cell, corrected for background.
Formula: CTCF = Integrated Density - (Area × Mean Background)

**Workflow**:
1. Draw ROI around each cell → measure integrated density + area
2. Draw ROI in background → measure mean intensity
3. Calculate: CTCF = IntDen - (Area_cell × Mean_background)
4. Repeat for all cells

**Macro Pattern**:
```
run("Set Measurements...", "area mean integrated display redirect=None decimal=3");
// For each cell ROI:
roiManager("Select", i);
run("Measure");
// Then compute CTCF from results
```

**Agent Automation Value**: VERY HIGH — extremely tedious manual process,
perfectly suited for automation.

### 1.4 Wound Healing / Scratch Assay

**What**: Measure wound closure over time in scratch assay experiments.

**Plugins/Commands**:
- Wound Healing Size Tool plugin
- MRI Wound Healing Tool
- Manual: threshold gap area at each timepoint

**Workflow**:
1. Open time-lapse stack
2. For each frame: threshold the wound area (dark gap)
3. Measure wound area / width at each timepoint
4. Calculate % closure over time

**Inputs**: Time-lapse brightfield images of scratch wound
**Outputs**: Wound area/width vs time, closure rate, half-closure time

### 1.5 Cell Proliferation (Ki67, EdU, BrdU)

**What**: Count proliferating vs total cells using marker staining.

**Workflow**:
1. Split channels (DAPI = total, Ki67/EdU = proliferating)
2. Count total nuclei in DAPI channel
3. Count positive nuclei in marker channel
4. Calculate proliferation index = positive / total × 100%

**Agent Automation Value**: HIGH — multi-channel counting across many fields of view.

### 1.6 Apoptosis Quantification (TUNEL, Annexin V)

**What**: Count apoptotic cells from TUNEL staining or Annexin V.

**Workflow**: Same as proliferation but with apoptosis markers.

### 1.7 Cell Size Distribution

**What**: Measure size distribution of a cell population.

**Workflow**:
1. Segment cells
2. Analyze Particles → get area for each
3. Export to histogram or statistical analysis

---

## 2. Neuroscience

### 2.1 Neurite Tracing & Outgrowth Measurement

**What**: Trace and measure neurite length, branching, and complexity.

**Plugins/Commands**:
- **SNT** (Simple Neurite Tracer) — the standard. Semi-automated 3D tracing with Sholl analysis, branch classification, morphometry
- **NeuronJ** — 2D neurite tracing and quantification
- **NeuriteTracer** — automated neurite tracing from fluorescence
- **NeurphologyJ** — automated soma count, neurite length, branching complexity

**Workflow (SNT)**:
1. Open z-stack or single image of neurons
2. Launch SNT → trace paths along neurites
3. Run Sholl analysis on traced morphology
4. Export: total neurite length, branch count, Sholl intersections

**Inputs**: Fluorescence images of neurons (GFP, MAP2, beta-III-tubulin)
**Outputs**: Total neurite length, branch points, Sholl profile, complexity metrics

**Agent Automation Value**: MEDIUM — tracing is semi-manual, but batch Sholl analysis and measurement extraction are automatable.

### 2.2 Dendritic Spine Analysis

**What**: Count and classify dendritic spines (mushroom, thin, stubby, filopodia).

**Plugins/Commands**:
- SNT with spine detection
- SpineJ
- Manual counting with Cell Counter

**Workflow**:
1. High-resolution images of dendrites
2. Trace dendrite, mark spines
3. Measure spine density (spines per um of dendrite)
4. Classify by morphology

### 2.3 Sholl Analysis

**What**: Quantify dendritic arbor complexity by counting intersections at concentric circles from soma.

**Plugins/Commands**:
- Sholl Analysis plugin (bundled with SNT)
- `run("Sholl Analysis...");`

**Workflow**:
1. Binary image or traced skeleton of neuron
2. Place center point at soma
3. Run Sholl → count intersections at each radius
4. Generate Sholl profile (intersections vs distance)

**Outputs**: Sholl profile, critical radius, max intersections, enclosing radius, ramification index

### 2.4 Calcium Imaging Analysis

**What**: Measure calcium transients (dF/F) from time-lapse fluorescence.

**Plugins/Commands**:
- **TACI** (TrackMate Analysis of Calcium Imaging) — 3D calcium imaging
- Time Series Analyzer
- Manual ROI-based: draw ROI on each cell, measure intensity over time

**Workflow**:
1. Open calcium imaging time-lapse (e.g., GCaMP)
2. Identify cells (ROIs or automated detection)
3. For each cell: measure mean intensity per frame
4. Normalize: dF/F = (F - F0) / F0
5. Detect peaks, measure amplitude, frequency, duration

**Inputs**: Time-lapse fluorescence (GCaMP, Fura-2, Fluo-4)
**Outputs**: dF/F traces per cell, peak frequency, amplitude, rise/decay times

**Agent Automation Value**: HIGH — ROI placement and trace extraction are tedious but automatable.

### 2.5 Brain Region Segmentation / Atlas Registration

**What**: Register brain sections to an atlas, quantify staining by region.

**Plugins/Commands**:
- **ABBA** (Aligning Big Brains & Atlases) — installed in user's Fiji
- Allen Brain Atlas integration
- QuPath (external, but works with ImageJ)

**Workflow**:
1. Open brain section image
2. Register to atlas using ABBA
3. Quantify marker expression per brain region
4. Export region-specific measurements

### 2.6 Microglia/Astrocyte Morphology

**What**: Analyze process length, branching, soma size of glial cells.

**Plugins/Commands**:
- AnalyzeSkeleton — branch/endpoint counting after skeletonization
- Fractal analysis (FracLac)
- Sholl analysis centered on soma

**Workflow**:
1. Segment individual glial cells
2. Skeletonize: `run("Skeletonize (2D/3D)");`
3. `run("Analyze Skeleton (2D/3D)", "prune=none show");`
4. Measure: branch count, total length, endpoints, junction count

---

## 3. Histology & Pathology

### 3.1 IHC (Immunohistochemistry) Quantification

**What**: Quantify DAB/chromogen staining intensity and positive area.

**Plugins/Commands**:
- **IHC Profiler** — automated scoring (negative/weak/moderate/strong)
- **Colour Deconvolution** — separate H&E or DAB+Hematoxylin stains
- `run("Colour Deconvolution", "vectors=[H DAB]");`

**Workflow**:
1. Color deconvolution to isolate DAB channel
2. Threshold DAB channel
3. Measure: % positive area, mean intensity, H-score

**Scoring Systems**:
- H-score: 3×(% strong) + 2×(% moderate) + 1×(% weak)
- Allred score: proportion score + intensity score
- % positive area

**Inputs**: RGB images of DAB-stained tissue sections
**Outputs**: H-score, % positive area, intensity distribution

**Agent Automation Value**: VERY HIGH — scoring is subjective manually, automated scoring is objective and reproducible.

### 3.2 H&E Morphometry

**What**: Measure tissue architecture from H&E-stained sections.

**Tasks**:
- Nuclear count and size
- Cell density per area
- Tissue area measurement
- Tumor vs normal classification
- Necrosis quantification

**Workflow**:
1. Color deconvolution (separate Hematoxylin from Eosin)
2. Threshold hematoxylin channel for nuclei
3. Watershed to separate touching nuclei
4. Analyze Particles for measurements

### 3.3 Fibrosis / Collagen Quantification (Masson's Trichrome, Sirius Red)

**What**: Measure fibrotic area from collagen-specific stains.

**Workflow**:
1. Threshold blue (Trichrome) or red (Sirius Red under polarized light) channel
2. Measure positive area / total tissue area
3. Report % fibrosis

### 3.4 Vessel Density / Angiogenesis

**What**: Count blood vessels, measure vessel area and density.

**Workflow**:
1. Segment vessels (CD31/vWF staining or H&E)
2. Count vessel profiles per area
3. Measure: vessel density, mean vessel area, total vascular area

### 3.5 Fat / Lipid Droplet Quantification

**What**: Measure lipid droplet count and size (Oil Red O, BODIPY).

**Workflow**:
1. Threshold lipid-stained regions
2. Analyze Particles with size and circularity filters
3. Measure: droplet count, size distribution, total lipid area

---

## 4. Fluorescence & Advanced Microscopy

### 4.1 Colocalization Analysis

**What**: Quantify spatial overlap of two fluorescent markers.

**Plugins/Commands**:
- **Coloc 2** — Pearson's, Manders', Costes significance test (installed in user's Fiji)
- **JACoP** (Just Another Colocalization Plugin) — comprehensive colocalization
- **EzColocalization** — user-friendly colocalization plugin
- **BIOP-JACoP** — batch colocalization from Z-stacks

**Workflow**:
1. Open multi-channel image
2. Split channels: `run("Split Channels");`
3. Run Coloc 2 or JACoP on the two channels
4. Report: Pearson's R, Manders' M1/M2, overlap coefficient
5. Run Costes randomization for statistical significance

**Metrics**:
- Pearson's correlation coefficient (-1 to +1)
- Manders' M1, M2 (fraction of overlap, 0-1)
- Overlap coefficient
- Costes significance (p-value from randomization)

**Inputs**: Multi-channel fluorescence image (2+ channels)
**Outputs**: Colocalization coefficients, scatterplot, Costes p-value

**Agent Automation Value**: HIGH — proper statistical colocalization analysis is complex.

### 4.2 FRET Analysis

**What**: Measure Forster Resonance Energy Transfer efficiency between donor/acceptor fluorophores.

**Plugins/Commands**:
- FRET and Colocalization Analyzer plugin
- Manual ratio calculation between donor/acceptor channels

**Workflow**:
1. Acquire donor-only, acceptor-only, and FRET images
2. Calculate corrected FRET = FRET - a×donor - b×acceptor
3. Compute FRET efficiency or FRET ratio per pixel/ROI

### 4.3 FRAP (Fluorescence Recovery After Photobleaching)

**What**: Measure protein dynamics from photobleaching recovery kinetics.

**Plugins/Commands**:
- **FRAP Tools** — automated recovery curve analysis
- Manual: ROI intensity over time → curve fitting

**Workflow**:
1. Open FRAP time-lapse
2. Define 3 ROIs: bleach region, reference region, background
3. Measure intensity over time for each ROI
4. Double normalization
5. Fit recovery curve (single/double exponential)
6. Extract: mobile fraction, t-half, diffusion coefficient

**Inputs**: FRAP time-lapse (pre-bleach, bleach, recovery frames)
**Outputs**: Recovery curve, mobile fraction (%), half-time (s), diffusion coefficient

### 4.4 Ratiometric Imaging

**What**: Compute ratio between two channels (e.g., Fura-2 calcium ratio, FRET ratio).

**Workflow**:
1. Open dual-wavelength image
2. Background subtract both channels
3. Create ratio image: `imageCalculator("Divide create 32-bit", ch1, ch2);`
4. Apply LUT, measure ratio per ROI

### 4.5 Fluorescence Intensity Quantification (General)

**What**: Measure raw fluorescence intensity in ROIs.

**Workflow**:
1. `run("Set Measurements...", "area mean integrated min display redirect=None decimal=3");`
2. Draw/define ROIs (manual or from segmentation)
3. `roiManager("Measure");`
4. Background subtraction (either subtract background ROI or rolling ball)

### 4.6 Photobleaching Correction

**What**: Correct for fluorescence signal decay over time in time-lapse.

**Plugins/Commands**:
- Bleach Correction plugin
- Manual: fit exponential decay to reference region, divide by fit

---

## 5. Tracking & Time-Lapse

### 5.1 Cell Tracking / Migration Analysis

**What**: Track cell movements over time, measure speed, directionality, persistence.

**Plugins/Commands**:
- **TrackMate** — the standard. Multi-algorithm detection + tracking (installed)
- **TrackAnalyzer** — extended track analysis (MSD, velocity, diffusion)
- **CellTraxx** — automated phase contrast tracking
- **Manual Tracking** plugin

**Workflow (TrackMate)**:
1. Open time-lapse
2. Launch TrackMate: detection (LoG, DoG, StarDist, Cellpose, Weka)
3. Configure linking (LAP tracker, simple tracker)
4. Filter tracks
5. Export: track coordinates, velocity, displacement, MSD

**Outputs**:
- Per-track: mean speed, max speed, total displacement, net displacement
- Directionality ratio (displacement / path length)
- Mean squared displacement (MSD) curves
- Forward migration index
- Rose plots of migration angles

**Agent Automation Value**: MEDIUM — TrackMate has good GUI but parameter selection benefits from AI guidance.

### 5.2 Particle Tracking / Single Molecule Tracking

**What**: Track individual fluorescent particles or molecules.

**Plugins/Commands**:
- TrackMate with sub-pixel localization
- ThunderSTORM (SMLM reconstruction)

### 5.3 Kymograph Analysis

**What**: Visualize and measure transport dynamics along a path over time.

**Plugins/Commands**:
- **KymographBuilder** — from lines on time-lapse
- **Multi Kymograph** — multiple paths simultaneously
- **Dynamic Kymograph** — keyframed paths
- **KymoToolBox** — kymograph filtering and analysis
- **TrackMate-Kymograph** — kymographs between tracked landmarks

**Workflow**:
1. Draw line ROI along transport path (axon, microtubule)
2. Generate kymograph: time on one axis, distance on other
3. Measure slope of tracks = velocity
4. Classify: anterograde, retrograde, stationary, bidirectional

**Inputs**: Time-lapse of vesicle/organelle transport
**Outputs**: Kymograph image, transport velocities, run lengths, pause durations

### 5.4 Mitosis / Division Detection

**What**: Detect and time cell division events.

**Plugins/Commands**:
- TrackMate (has division detection)
- Manual frame-by-frame identification

---

## 6. 3D Analysis & Volume Imaging

### 6.1 Z-Stack Processing

**What**: Process confocal/light-sheet z-stacks for visualization or analysis.

**Commands**:
- Z-projection: `run("Z Project...", "projection=[Max Intensity]");`
  - Options: Max Intensity, Average, Sum, Min, Standard Deviation, Median
- Reslice: `run("Reslice [/]...", "output=1.000 start=Top");`
- Orthogonal views: `run("Orthogonal Views");`

### 6.2 3D Object Counting & Segmentation

**What**: Count and measure objects in 3D (nuclei, cells, organelles).

**Plugins/Commands**:
- **3D Objects Counter** — connected component analysis in 3D
- **3D ImageJ Suite** (3D Segment, 3D Centroid, etc.) — 64 3D commands in user's Fiji
- **StarDist 3D** — deep learning 3D nuclei segmentation
- **Cellpose 3D** — deep learning 3D cell segmentation
- **MorphoLibJ** — 3D morphological processing and watershed

**Workflow**:
1. Pre-process z-stack (3D Gaussian, background subtraction)
2. Threshold or ML-segment
3. 3D Objects Counter: `run("3D Objects Counter", "threshold=T min.=V objects");`
4. Measure: 3D volume, surface area, sphericity, centroid XYZ

### 6.3 3D Rendering & Visualization

**What**: Create 3D visualizations from z-stacks.

**Plugins/Commands**:
- **3D Viewer** — OpenGL-based volume/surface rendering (installed)
- **3Dscript** — scripted animation rendering (installed as Batch Animation)
- **3D Project** — built-in rotation projection
- **Volume Viewer** — interactive volume visualization
- **ClearVolume** — real-time volume rendering

**Rendering types**: Volume, Orthoslice, Isosurface, Surface Plot

### 6.4 3D Colocalization

**What**: Colocalization analysis accounting for 3D structure.

**Workflow**:
1. Process z-stack per channel
2. Run Coloc 2 on 3D data (it handles stacks natively)
3. Or use BIOP-JACoP for batch Z-stack colocalization

### 6.5 3D Distance / Proximity Analysis

**What**: Measure distances between 3D objects.

**Plugins/Commands**:
- 3D ImageJ Suite distance functions
- DiAna (Distance Analysis) plugin

---

## 7. Gel & Blot Analysis

### 7.1 Western Blot Densitometry

**What**: Quantify protein band intensity from Western blots.

**Plugins/Commands**:
- Built-in Gel Analyzer: `Analyze > Gels`
- WBGelDensitometryTool macro
- Manual lane profiling

**Workflow**:
1. Open gel image, convert to 8-bit grayscale
2. `run("Invert");` if needed (dark bands on light background)
3. Select first lane: `run("Select First Lane");` → `run("Select Next Lane");`
4. Plot lanes: `run("Plot Lanes");`
5. Draw baseline, measure peak areas with Wand tool
6. Normalize to loading control (beta-actin, GAPDH)

**Inputs**: Scanned gel/blot image (TIFF, 8-bit grayscale)
**Outputs**: Relative band intensity, normalized expression ratios

**Agent Automation Value**: HIGH — tedious manual lane selection, baseline drawing. AI can automate detection and quantification.

### 7.2 Dot Blot Analysis

**What**: Quantify signal in dot blot arrays.

**Workflow**:
1. Open dot blot image
2. Place circular ROIs on each dot
3. Measure integrated density per dot
4. Subtract background
5. Normalize and compare

### 7.3 Colony Counting (Agar Plates)

**What**: Count bacterial/cell colonies on plates.

**Workflow**:
1. Open plate image
2. Adjust contrast, threshold colonies
3. `run("Analyze Particles...", "size=20-Infinity circularity=0.3-1.0 display summarize");`
4. Report count

**Agent Automation Value**: HIGH — common undergraduate/routine lab task.

---

## 8. Plant Biology

### 8.1 Leaf Area Measurement

**What**: Measure leaf area from scanned images.

**Workflow**:
1. Threshold leaf from background
2. `run("Analyze Particles...", "size=1000-Infinity display");`
3. Report area in calibrated units

### 8.2 Root Architecture / Growth Analysis

**What**: Measure root length, branching, growth rate.

**Plugins/Commands**:
- SmartRoot (semi-automated root tracing)
- Manual line measurement

### 8.3 Stomatal Counting / Measurement

**What**: Count and measure stomata from leaf surface images.

### 8.4 Vessel / Xylem Morphometry

**What**: Measure vessel lumen area, density, hydraulic diameter.

**Workflow**:
1. Threshold vessel lumens from stem cross-section
2. Analyze Particles → count, area, equivalent diameter
3. Calculate hydraulic conductivity

### 8.5 Chlorophyll Fluorescence / NDVI

**What**: Measure photosynthetic parameters from fluorescence/multispectral images.

### 8.6 Pollen Grain Analysis

**What**: Count pollen, measure size/viability.

---

## 9. Materials Science & Physical Sciences

### 9.1 Grain Size / Particle Size Analysis

**What**: Measure grain/particle size distribution in micrographs.

**Workflow**:
1. Threshold grain boundaries
2. Watershed to separate touching grains
3. Analyze Particles → size distribution
4. Report D50, D10, D90, histogram

### 9.2 Porosity Measurement

**What**: Quantify pore fraction in materials/rocks.

**Workflow**:
1. Threshold pores (typically dark on light)
2. Measure: total pore area / total area = porosity fraction
3. Individual pore statistics (size, shape)

### 9.3 Fiber Analysis

**What**: Measure fiber diameter, orientation, density.

**Plugins/Commands**:
- OrientationJ — fiber orientation analysis
- DiameterJ — nanofiber diameter measurement
- Directionality plugin

### 9.4 Surface Roughness

**What**: Quantify surface topography from profilometry images.

### 9.5 Crack / Defect Detection

**What**: Detect and measure cracks, voids, inclusions.

---

## 10. Bone & Skeletal Analysis

### 10.1 Bone Morphometry (Trabecular Analysis)

**What**: Measure trabecular bone parameters from microCT.

**Plugins/Commands**:
- **BoneJ** — comprehensive bone analysis plugin
  - Tb.Th (trabecular thickness)
  - Tb.Sp (trabecular separation)
  - BV/TV (bone volume fraction)
  - Connectivity density
  - Structure Model Index (SMI)
  - Degree of Anisotropy

**Workflow**:
1. Threshold bone from background
2. Run BoneJ measurements
3. Export standard bone morphometry parameters

### 10.2 Skeleton/Branching Analysis

**What**: Analyze branching structures (vasculature, neurons, trabeculae).

**Plugins/Commands**:
- **AnalyzeSkeleton (2D/3D)** — installed in user's Fiji
- **Skeletonize3D** — 3D thinning to skeleton
- **BranchAnalysis2D/3D** — automated branch morphometry

**Workflow**:
1. Binarize the branching structure
2. Skeletonize: `run("Skeletonize (2D/3D)");`
3. Analyze: `run("Analyze Skeleton (2D/3D)", "prune=none show");`
4. Outputs: branch count, junction count, mean/max branch length, endpoints

**Outputs**: Number of branches, junctions, triple/quadruple points, mean branch length, max branch length, total length

---

## 11. Publication & Visualization

### 11.1 Figure Assembly / Montage

**What**: Create multi-panel publication figures with consistent formatting.

**Plugins/Commands**:
- Built-in: `run("Make Montage...", "columns=4 rows=2 scale=1");`
- **QuickFigures** — advanced figure assembly with layout tools
- **FigureJ** — publication figure panel plugin
- **SciFig** — scientific figure layout

**Workflow**:
1. Prepare individual panels (crop, adjust contrast display)
2. Create montage or use QuickFigures for layout
3. Add labels (A, B, C...), scale bars, annotations
4. Flatten and export as TIFF (for journal) or PNG/SVG

### 11.2 Scale Bar Addition

**What**: Add calibrated scale bars to images.

**Commands**:
- `run("Scale Bar...", "width=50 height=5 font=14 color=White background=None location=[Lower Right] bold overlay");`
- Must set calibration first: `run("Set Scale...", "distance=100 known=10 unit=um");`

### 11.3 LUT (Lookup Table) Application

**What**: Apply pseudocolor to grayscale images for visualization.

**Commands**:
- `run("Fire");`, `run("Cyan Hot");`, `run("Green");`, `run("Magenta");`
- `run("Merge Channels...", "c1=red c2=green c3=blue create");`
- Calibration bar: `run("Calibration Bar...", "location=[Upper Right] fill=White ...");`

### 11.4 Overlay Annotations

**What**: Add arrows, text, shapes as non-destructive overlays.

**Commands**:
- `Overlay.drawLine(x1, y1, x2, y2);`
- `Overlay.drawString("text", x, y);`
- `Overlay.add("rectangle", x, y, w, h);`
- `run("Add Selection...");` — add ROI to overlay

### 11.5 Movie / Animation Creation

**What**: Create time-lapse movies or rotation animations.

**Commands**:
- `run("AVI...", "compression=JPEG frame=10 save=/path/movie.avi");`
- 3Dscript animations
- `run("3D Project...");` for rotation animations
- Stamp time/frame labels: `run("Time Stamper", "starting=0 interval=1 ...");`

---

## 12. Batch Processing & Automation

### 12.1 Directory Batch Processing

**What**: Apply the same analysis to all images in a folder.

**Macro Pattern**:
```
dir = getDirectory("Choose input");
list = getFileList(dir);
for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif")) {
        open(dir + list[i]);
        // ... analysis ...
        close();
    }
}
```

### 12.2 Bio-Formats Batch Import

**What**: Open proprietary formats (.nd2, .lif, .czi, .oib) and process.

**Commands**:
- `run("Bio-Formats Importer", "open=/path/to/file.nd2 autoscale color_mode=Default view=Hyperstack");`
- For multi-series: use Bio-Formats Macro Extensions
  ```
  run("Bio-Formats Macro Extensions");
  Ext.setId("/path/to/file.nd2");
  Ext.getSeriesCount(seriesCount);
  for (s = 0; s < seriesCount; s++) {
      Ext.setSeries(s);
      // ... process series ...
  }
  ```

### 12.3 Results Export

**What**: Save measurements to CSV/Excel-compatible format.

**Commands**:
- `saveAs("Results", "/path/to/results.csv");`
- `run("Read and Write Excel", "...");` (if plugin installed)

---

## 13. Machine Learning & Deep Learning

### 13.1 Trainable Weka Segmentation

**What**: Train a random forest classifier for pixel-level segmentation.

**Plugin**: Trainable Weka Segmentation (installed in user's Fiji)

**Workflow**:
1. Open image
2. Launch TWS: `run("Trainable Weka Segmentation");`
3. Draw examples of each class
4. Train classifier → apply to entire image/batch
5. Scriptable for batch: can save classifier and apply via macro

**Use Cases**: Tissue region classification, cell type segmentation, structure detection

### 13.2 StarDist (Deep Learning Nuclei Detection)

**What**: Detect nuclei using pre-trained deep learning model.

**Plugin**: StarDist 2D/3D (installed)

**Commands**:
- `run("StarDist 2D", "...");`
- Uses star-convex polygon detection
- Pre-trained models: Versatile (fluorescent nuclei), DSB 2018
- Can train custom models (requires Python/TensorFlow)

### 13.3 Cellpose (Deep Learning Cell Segmentation)

**What**: Segment whole cells or nuclei using generalist deep learning model.

**Plugin**: Cellpose (installed, also Cellpose SAM)

- Pre-trained models: cyto, nuclei, cyto2
- No training needed for common cell types
- Slower than StarDist but handles more complex shapes

### 13.4 Labkit (Interactive ML Segmentation)

**What**: Paint-based interactive segmentation using ML.

**Plugin**: Labkit (installed)

### 13.5 ilastik Integration

**What**: Pixel classification, object classification, tracking.

**Status**: ilastik update site available but NOT enabled in user's Fiji.

---

## 14. GPU-Accelerated Processing

### 14.1 CLIJ2

**What**: GPU-accelerated image processing (504 operations in user's Fiji).

**Plugin**: CLIJ2 (installed)

**Key Operations** (all callable via macro):
- `Ext.CLIJ2_gaussianBlur3D(input, output, sigmaX, sigmaY, sigmaZ);`
- `Ext.CLIJ2_threshold(input, output, threshold);`
- `Ext.CLIJ2_connectedComponentsLabeling(input, output);`
- `Ext.CLIJ2_statisticsOfLabelledPixels(input, labels);`
- Speedup: 10-100x over CPU ImageJ operations

**Agent Automation Value**: HIGH — can dramatically speed up batch processing.

---

## 15. Image Restoration & Preprocessing

### 15.1 Deconvolution

**What**: Remove optical blur using known/estimated PSF.

**Plugins/Commands**:
- Built-in iterative deconvolution
- CLIJ2 Richardson-Lucy deconvolution (GPU)
- AutoDeconJ — GPU-accelerated 3D deconvolution
- DeconvolutionLab2

### 15.2 Noise Reduction

**What**: Remove noise while preserving features.

**Commands**:
- `run("Gaussian Blur...", "sigma=1");` — Gaussian smoothing
- `run("Median...", "radius=2");` — salt-and-pepper noise
- `run("Despeckle");` — 3x3 median
- `run("Remove Outliers...", "radius=2 threshold=50 which=Bright");`
- Non-local means denoising
- CARE / Noise2Void (deep learning denoising)

### 15.3 Background Correction

**What**: Remove uneven illumination.

**Commands**:
- `run("Subtract Background...", "rolling=50");` — rolling ball
- Flat-field correction: `imageCalculator("Divide create 32-bit", raw, flatfield);`
- BaSiC illumination correction plugin

### 15.4 Drift Correction / Registration

**What**: Correct sample drift in time-lapse or tile alignment.

**Plugins/Commands**:
- **StackReg** / **TurboReg** — rigid/affine registration
- **Correct 3D Drift** — for z-stacks over time
- **Linear Stack Alignment with SIFT**
- Built-in: `run("StackReg", "transformation=Rigid Body");`

### 15.5 Stitching / Mosaic Assembly

**What**: Stitch tiled images into a seamless panorama.

**Plugins/Commands**:
- **Grid/Collection Stitching** — 2D and 3D stitching (installed)
- `run("Grid/Collection stitching", "type=[Grid: snake by rows] ...");`
- BigStitcher for large datasets

---

## 16. Community Resources & Training

### 16.1 Official Resources

- **ImageJ Wiki**: https://imagej.net/ — comprehensive documentation
- **Image.sc Forum**: https://forum.image.sc/ — 10,000+ users, 15,000+ posts/year
- **ImageJ Tutorials**: https://imagej.net/tutorials/
- **ImageJ Macro Language Reference**: https://imagej.net/ij/developer/macro/macros.html

### 16.2 Training Programs

- **NEUBIAS** (Network of European BioImage Analysts): https://neubias.github.io/training-resources/
  - Modular training covering: thresholding, segmentation, colocalization, batch processing, deep learning
  - Instructions for ImageJ GUI, ImageJ Macro, Python, Galaxy
- **NEUBIAS Academy**: https://eubias.org/NEUBIAS/training-schools/neubias-academy-home/
- **iBiology ImageJ course**: https://www.ibiology.org/techniques/image-j/
- **Bioimage Analysis Workflows** (Springer textbook): step-by-step protocols

### 16.3 Macro Collections & Repositories

- **GitHub imagej-macro topic**: https://github.com/topics/imagej-macro — 300+ repositories
- **MRI (Montpellier) macro collection**: https://github.com/MontpellierRessourcesImagerie/imagej_macros_and_scripts — comprehensive set from imaging facility
- **CMCI BioImage Analysis Wiki**: https://wiki.cmci.info/ — protocols and workflows
- **ImageJ built-in examples**: 300+ example macros on the ImageJ website
- **dwaithe/generalMacros**: https://github.com/dwaithe/generalMacros — teaching material for quantitative image analysis

### 16.4 Key Publications

- Schindelin et al. 2012. "Fiji: an open-source platform for biological-image analysis" Nature Methods
- Schneider et al. 2012. "NIH Image to ImageJ: 25 years of image analysis" Nature Methods
- Rueden et al. 2017. "ImageJ2: ImageJ for the next generation of scientific image data" BMC Bioinformatics
- Arganda-Carreras et al. 2017. "Trainable Weka Segmentation" Bioinformatics
- Ershov et al. 2022. "TrackMate 7" Methods

---

## 17. Pain Points & Automation Opportunities

### 17.1 Highest-Impact Automation Targets

Ranked by frequency of need × tedium × automation feasibility:

| Rank | Task | Impact | Why |
|------|------|--------|-----|
| 1 | **Batch cell counting** | VERY HIGH | Every lab does this; extremely tedious across 100s of images |
| 2 | **CTCF quantification** | VERY HIGH | Manual ROI-by-ROI is painful; perfect for automation |
| 3 | **IHC scoring** | VERY HIGH | Subjective manually; objective when automated |
| 4 | **Western blot densitometry** | HIGH | Common but fiddly; standardizable |
| 5 | **Colocalization analysis** | HIGH | Complex stats; easy to do wrong manually |
| 6 | **Batch file conversion** | HIGH | .nd2/.lif/.czi → TIFF, trivial but tedious |
| 7 | **Figure preparation** | HIGH | Scale bars, montages, annotations — every paper needs this |
| 8 | **Particle/colony counting** | HIGH | Simple threshold+count but repetitive |
| 9 | **Wound healing measurement** | MEDIUM-HIGH | Time-lapse analysis, well-defined workflow |
| 10 | **3D object counting** | MEDIUM-HIGH | Parameter-sensitive; benefits from iterative AI tuning |

### 17.2 Common Pain Points from the Community

1. **Steep learning curve**: New users struggle with basic operations. Macro language is unintuitive. Plugin installation is confusing.
   - *Agent opportunity*: Natural language interface eliminates learning curve entirely.

2. **Parameter selection**: "Which threshold method?" "What sigma for Gaussian?" "What size filter for Analyze Particles?" — researchers guess and check.
   - *Agent opportunity*: AI can explore parameters systematically (already implemented: `explore_thresholds`).

3. **Reproducibility**: Manual steps are not recorded. Different people get different results.
   - *Agent opportunity*: Every step is macro-based and logged. Perfect reproducibility.

4. **Plugin compatibility**: Updates break workflows. Plugins conflict. Version management is painful.
   - *Agent opportunity*: Agent can detect and work around known issues.

5. **ROI management**: Working with multiple selections is painful. No filtering by size/intensity.
   - *Agent opportunity*: Agent can automate ROI creation, filtering, and measurement.

6. **Batch processing**: Writing batch macros requires programming. Bio-Formats multi-series files are complex.
   - *Agent opportunity*: Agent handles batch logic, file iteration, format conversion.

7. **Multi-channel workflows**: Split channels, process independently, recombine — complex and error-prone.
   - *Agent opportunity*: Agent orchestrates multi-channel pipelines.

8. **Statistical interpretation**: Researchers struggle with which test to use, how to handle biological replicates.
   - *Agent opportunity*: Already implemented (StatsAgent), but can be expanded.

9. **3D analysis**: Most researchers avoid 3D analysis because it's complex. Default to max projections and lose z-information.
   - *Agent opportunity*: Agent makes 3D analysis as easy as 2D.

10. **Documentation**: Macros are written but not documented. Analysis steps are forgotten.
    - *Agent opportunity*: Agent auto-generates documentation of every analysis.

### 17.3 What Would Benefit Most from AI

- **Adaptive thresholding**: AI examines histogram, tries methods, picks best one
- **Quality control**: AI checks for saturation, uneven illumination, out-of-focus regions
- **Workflow recommendation**: "I have DAPI + GFP images, what analyses make sense?"
- **Error recovery**: When a macro fails, AI reads the error and fixes it
- **Natural language to macro**: "Count the cells" → complete working pipeline
- **Result interpretation**: "Is this colocalization significant?" → statistical assessment
- **Cross-image consistency**: Ensure same parameters work across a dataset

---

## 18. Self-Improving Agent Patterns

### 18.1 Core Architecture: Feedback Loops

From research on self-improving coding agents:

1. **Execution → Observation → Correction cycle**:
   - Execute macro → capture image → evaluate result → adjust parameters
   - Already partially implemented in ImageJAI (visual feedback loop in learnings.md)

2. **AGENTS.md / learnings.md as persistent memory**:
   - When the agent encounters a mistake, append the correction to the knowledge base
   - Over time this becomes a knowledge base steering away from past mistakes
   - ImageJAI already has `learnings.md` — this pattern is live

3. **Self-Taught Optimizer pattern**:
   - Start with a basic code improver
   - Apply the improver to its own code iteratively
   - Discovers optimization patterns without human guidance

### 18.2 Recipe Book Pattern

A growing collection of verified, parameterized workflows:

```
Recipe: Cell Counting (Fluorescence)
Verified: 2025-12-15
Success rate: 95% (19/20 test images)
Parameters:
  pre_filter: Gaussian sigma=1.5
  threshold: Otsu (dark background)
  post_process: Watershed
  analyze: size=50-Infinity, circularity=0.3-1.0
  measurements: area, mean, centroid, shape
Failure modes:
  - Very dense clusters: increase sigma or use StarDist
  - Dim cells: try Triangle threshold instead
  - Debris: increase minimum size filter
```

**Implementation for ImageJAI**:
- Store recipes as JSON in `agent/recipes/`
- Each recipe: name, domain, steps (macro code), parameters, success history
- After each execution: record outcome (success/failure, metrics)
- On failure: log the error and correction → update recipe failure modes
- On repeated success: increase confidence score
- Match user requests to recipes by keyword/intent

### 18.3 Parameter Learning

Track which parameters work for which image types:

```json
{
  "image_type": "confocal_fluorescence_DAPI",
  "indicators": {"bitDepth": 16, "channels": 1, "histogram_shape": "bimodal"},
  "best_threshold": "Li",
  "best_sigma": 1.5,
  "success_count": 12,
  "last_used": "2025-12-15"
}
```

### 18.4 Macro Correction Patterns

Common error → fix mappings that the agent can learn:

| Error Pattern | Fix |
|---------------|-----|
| "No image open" | Insert `open()` or check state first |
| "Not a binary image" | Insert `run("Convert to Mask");` |
| "Selection required" | Insert `run("Select All");` or create ROI |
| "Unrecognized command" | Search `commands.txt` for correct name |
| "Macro execution timed out" | Add `setBatchMode(true);` or break into steps |
| Object count = 0 | Try different threshold, lower size filter |
| Object count = 1 | Needs watershed, or threshold too aggressive |
| All pixels saturated | Don't use normalize, use setMinAndMax |

### 18.5 Self-Evaluation Pipeline

For any analysis, the agent can self-evaluate:

1. **Segmentation quality**: After thresholding, count objects. If count=1, probably under-segmented. If count>10000, probably noise.
2. **Measurement sanity**: Cell area should be 100-5000 pixels. Circularity of cells should be 0.3-0.9.
3. **Histogram analysis**: Check for saturation, bimodality, noise floor.
4. **Visual inspection**: Capture and look — does the result look right?
5. **Cross-validation**: Run same analysis with different parameters, compare.

### 18.6 Progressive Skill Acquisition

Level 1 (Current): Execute user-specified macros, capture results
Level 2 (Near-term): Recommend workflows based on image type
Level 3 (Medium-term): Self-select parameters based on image characteristics
Level 4 (Long-term): Design novel analysis pipelines from user goals
Level 5 (Aspirational): Learn from published methods papers, replicate analyses

### 18.7 Key References for Self-Improving Agents

- **OpenAI Self-Evolving Agents Cookbook**: Repeatable retraining loop capturing issues and promoting improvements. Trajectory-based learning with GRPO-style policy updates.
- **Addy Osmani "Self-Improving Coding Agents"**: AGENTS.md as repository memory, reusable skills from mistakes, operational feedback emphasis.
- **ICLR 2025 "A Self-Improving Coding Agent"**: Autonomously edits own code, 17%→53% on SWE-Bench. Uses Self-Taught Optimizer for recursive self-improvement.
- **Yohei Nakajima**: Feedback loop where execution generates feedback, validation filters it, routing applies improvements to the right components.

---

## Appendix: Complete Task Checklist for Agent Coverage

### Currently Implemented (in ImageJAI)
- [x] Cell counting (threshold + Analyze Particles)
- [x] Basic morphometry (shape descriptors)
- [x] Threshold exploration (multiple methods)
- [x] Z-projection
- [x] 3D rendering (3D Viewer, 3Dscript, 3D Project)
- [x] 3D object isolation and masking
- [x] Scale bar addition
- [x] LUT application
- [x] Basic figure preparation
- [x] State inspection (images, ROIs, results, memory)
- [x] Visual feedback loop (capture + inspect)
- [x] Skeleton analysis
- [x] Plugin discovery (scan_plugins.py)
- [x] Plugin parameter probing (probe_plugin.py)
- [x] Results parsing and statistics
- [x] Macro validation (macro_lint.py)

### High Priority — Should Implement Next
- [ ] CTCF quantification (automated ROI + background correction)
- [ ] Colocalization analysis (Coloc 2 integration)
- [ ] Western blot densitometry (lane detection + quantification)
- [ ] IHC scoring (color deconvolution + H-score)
- [ ] Batch processing framework (iterate over directories)
- [ ] Bio-Formats multi-series handling
- [ ] StarDist/Cellpose integration (ML-based counting)
- [ ] Wound healing measurement
- [ ] Recipe book system (JSON-based verified workflows)
- [ ] Parameter learning from past executions

### Medium Priority
- [ ] Cell tracking (TrackMate orchestration)
- [ ] Kymograph generation and analysis
- [ ] FRAP analysis
- [ ] Calcium imaging trace extraction
- [ ] Sholl analysis
- [ ] Neurite tracing (SNT integration)
- [ ] 3D colocalization
- [ ] Stitching automation
- [ ] Registration/drift correction
- [ ] Deconvolution
- [ ] Publication figure assembly (QuickFigures)
- [ ] Movie/animation export

### Lower Priority / Specialized
- [ ] Bone morphometry (BoneJ)
- [ ] Plant analysis workflows
- [ ] Materials science analysis
- [ ] FRET analysis
- [ ] Brain atlas registration (ABBA)
- [ ] GPU-accelerated workflows (CLIJ2)
- [ ] Custom Weka classifier training
- [ ] Single molecule tracking

---

*This document should be updated as the agent gains new capabilities and
discovers new analysis patterns. It serves as both a roadmap and a reference
for the agent's knowledge of the ImageJ analysis ecosystem.*
