# ImageJ/Fiji Analysis Landscape — Reference

Catalog of analysis domains, tasks, workflows, and automation opportunities.

---

## 1. Cell Biology & General Microscopy

| Task | Workflow Summary | Key Commands/Plugins | Automation Value |
|------|-----------------|---------------------|-----------------|
| **Cell Counting** | Threshold → Watershed → Analyze Particles (or StarDist/Cellpose for touching) | `run("Analyze Particles...")`, StarDist, Cellpose | VERY HIGH |
| **Morphometry** | Segment → Set Measurements (shape descriptors) → Analyze Particles | Area, Circ., AR, Solidity, Feret | HIGH |
| **CTCF** | ROI per cell → IntDen - (Area × BG Mean) | `run("Set Measurements...", "area mean integrated...")` | VERY HIGH |
| **Wound Healing** | Time-lapse → threshold gap → measure area per frame → closure rate | Wound Healing Size Tool, manual threshold | MEDIUM-HIGH |
| **Proliferation** | Split channels → count total (DAPI) vs marker (Ki67/EdU) → % positive | StarDist or threshold+watershed per channel | HIGH |
| **Cell Size Distribution** | Segment → Analyze Particles → area histogram | Analyze Particles, export | MEDIUM |

---

## 2. Neuroscience

| Task | Workflow Summary | Key Plugins | Automation Value |
|------|-----------------|-------------|-----------------|
| **Neurite Tracing** | SNT semi-auto 3D tracing → Sholl + morphometry | SNT, NeuronJ | MEDIUM |
| **Sholl Analysis** | Binary/traced neuron → center on soma → count intersections vs radius | Sholl Analysis (in SNT) | HIGH |
| **Dendritic Spines** | High-res dendrite images → trace → classify (mushroom/thin/stubby) | SNT, SpineJ | LOW (manual) |
| **Calcium Imaging** | Motion correct → ROIs on cells → mean intensity/frame → dF/F | TACI, Suite2p (Python), StackReg | HIGH |
| **Atlas Registration** | Register sections to Allen CCFv3 → quantify by region | ABBA, QuPath | MEDIUM |
| **Microglia Morphology** | Segment → Skeletonize → AnalyzeSkeleton → branches/endpoints | AnalyzeSkeleton, FracLac | HIGH |

---

## 3. Histology & Pathology

| Task | Workflow Summary | Key Plugins | Automation Value |
|------|-----------------|-------------|-----------------|
| **IHC Quantification** | Colour Deconvolution (H-DAB) → threshold DAB → % area / H-score | Colour Deconvolution 2, IHC Profiler | VERY HIGH |
| **H&E Morphometry** | Deconvolve → threshold hematoxylin → watershed → count/measure | Colour Deconvolution, Analyze Particles | HIGH |
| **Fibrosis** | Threshold blue (Trichrome) or red (Sirius Red) → % area | Threshold + measure | HIGH |
| **Vessel Density** | Segment CD31/vWF → count profiles per area | Analyze Particles | MEDIUM |

**IHC Scoring**: H-score = 3×(%strong) + 2×(%moderate) + 1×(%weak). Allred = proportion + intensity.

---

## 4. Fluorescence & Advanced Microscopy

| Task | Workflow Summary | Key Plugins |
|------|-----------------|-------------|
| **Colocalization** | Split channels → Coloc 2 (PCC, Manders+Costes) → significance test | Coloc 2, JACoP |
| **FRET** | Corrected FRET = FRET - a×donor - b×acceptor → efficiency | FRET Analyzer |
| **FRAP** | 3 ROIs (bleach/reference/BG) → double normalize → fit recovery → t½, mobile fraction | FRAP Tools |
| **Ratiometric** | BG subtract both channels → `imageCalculator("Divide create 32-bit")` → measure | Built-in |
| **Bleach Correction** | Simple ratio / exponential / histogram matching | Bleach Correction plugin |

---

## 5. Tracking & Time-Lapse

| Task | Workflow Summary | Key Plugins |
|------|-----------------|-------------|
| **Cell Tracking** | TrackMate: detect (LoG/StarDist/Cellpose) → link (LAP) → filter → export | TrackMate |
| **Kymograph** | Line ROI along path → Reslice → slope = velocity | KymographBuilder, Multi Kymograph |
| **Mitosis Detection** | TrackMate splitting events or TrackMate-Oneat | TrackMate |

**TrackMate outputs**: speed, displacement, MSD, directionality ratio, rose plots.

---

## 6. 3D Analysis

| Task | Workflow Summary | Key Plugins |
|------|-----------------|-------------|
| **Z-Stack Processing** | Z Project (Max/Mean/Sum), Reslice, Orthogonal Views | Built-in |
| **3D Object Counting** | 3D Gaussian → threshold → 3D Objects Counter → volume/surface/sphericity | 3D Objects Counter, 3D ImageJ Suite |
| **3D Rendering** | 3Dscript, 3D Viewer, 3D Project | See 3d-visualisation-reference.md |
| **3D Colocalization** | Coloc 2 on z-stack (handles stacks natively) | Coloc 2, BIOP-JACoP |
| **3D Distance** | 3D ImageJ Suite distance functions, DiAna | 3D ImageJ Suite |

---

## 7. Gel & Blot Analysis

| Task | Workflow Summary | Automation Value |
|------|-----------------|-----------------|
| **Western Blot** | 8-bit → Invert → Select Lanes → Plot → measure peaks → normalize to loading control | HIGH |
| **Dot Blot** | Circular ROIs → IntDen per dot → BG subtract → normalize | HIGH |
| **Colony Counting** | Threshold → `Analyze Particles` size=20-Inf circ=0.3-1.0 | HIGH |

---

## 8. Plant Biology

| Task | Workflow |
|------|---------|
| **Leaf Area** | Threshold → Analyze Particles size=1000+ |
| **Root Architecture** | SmartRoot semi-auto tracing |
| **Stomatal Counting** | Segment + count |
| **Vessel Morphometry** | Threshold lumens → count + equivalent diameter |

---

## 9. Materials Science

| Task | Workflow |
|------|---------|
| **Grain Size** | Threshold boundaries → watershed → size distribution (D10/D50/D90) |
| **Porosity** | Threshold pores → area fraction |
| **Fiber Analysis** | OrientationJ (orientation), DiameterJ (diameter) |

---

## 10. Bone & Skeletal

**BoneJ** provides: Tb.Th, Tb.Sp, BV/TV, connectivity density, SMI, anisotropy.

**Skeleton analysis**: Binarize → `run("Skeletonize (2D/3D)");` → `run("Analyze Skeleton (2D/3D)", "prune=none show");` → branches, junctions, endpoints, lengths.

---

## 11. Publication & Visualization

| Task | Key Commands |
|------|-------------|
| **Montage** | `run("Make Montage...", "columns=4 rows=2 scale=1");` |
| **Scale Bar** | `run("Scale Bar...", "width=50 height=5 color=White location=[Lower Right] bold overlay");` |
| **LUT** | `run("Fire");` `run("Cyan Hot");` etc. |
| **Overlay Annotations** | `Overlay.drawLine()`, `Overlay.drawString()` |
| **Movie Export** | `run("AVI...", "compression=JPEG frame=10 save=...");` or 3Dscript |

---

## 12. Batch Processing

```javascript
// Standard batch pattern
dir = getDirectory("Choose input");
list = getFileList(dir);
setBatchMode(true);
for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif")) {
        open(dir + list[i]);
        // ... process ...
        close();
    }
}
setBatchMode(false);
```

Bio-Formats multi-series:
```javascript
run("Bio-Formats Macro Extensions");
Ext.setId("/path/to/file.nd2");
Ext.getSeriesCount(seriesCount);
```

---

## 13. ML & Deep Learning

| Tool | Type | Status |
|------|------|--------|
| **WEKA** | Random forest pixel classification | INSTALLED |
| **StarDist** | DL nuclei detection | INSTALLED |
| **Cellpose** | DL cell segmentation | INSTALLED (via TrackMate) |
| **Labkit** | Interactive ML | INSTALLED |
| **CLIJ2** | GPU-accelerated (504 ops, 10-100x speedup) | INSTALLED |

---

## Automation Priority

| Rank | Task | Impact | Reason |
|------|------|--------|--------|
| 1 | Batch cell counting | VERY HIGH | Universal, tedious across 100s of images |
| 2 | CTCF quantification | VERY HIGH | Manual ROI-by-ROI; ideal for automation |
| 3 | IHC scoring | VERY HIGH | Subjective manually; objective automated |
| 4 | Western blot densitometry | HIGH | Common, standardizable |
| 5 | Colocalization | HIGH | Complex stats, easy to do wrong |
| 6 | Batch file conversion | HIGH | Trivial but repetitive |
| 7 | Figure preparation | HIGH | Every paper needs it |
| 8 | Wound healing | MEDIUM-HIGH | Well-defined workflow |

---

## Community Pain Points → Agent Opportunities

| Pain Point | Agent Solution |
|------------|---------------|
| Steep learning curve | Natural language interface |
| Parameter guessing | Systematic exploration (`explore_thresholds`) |
| Irreproducibility | Every step is macro-based and logged |
| ROI management | Automated creation, filtering, measurement |
| Batch processing | Agent handles iteration and format conversion |
| Multi-channel complexity | Agent orchestrates pipelines |
| Statistical interpretation | StatsAgent guides test selection |
| 3D analysis avoidance | Agent makes 3D as easy as 2D |

---

## Agent Coverage Checklist

### Implemented
Cell counting, morphometry, threshold exploration, Z-projection, 3D rendering, 3D isolation, scale bars, LUTs, figures, state inspection, visual feedback, skeleton analysis, plugin discovery/probing, results parsing, macro linting.

### High Priority Next
CTCF automation, Coloc 2 integration, Western blot densitometry, IHC scoring, batch framework, Bio-Formats multi-series, StarDist/Cellpose integration, wound healing, recipe system, parameter learning.

### Medium Priority
TrackMate, kymographs, FRAP, calcium imaging, Sholl, SNT, 3D colocalization, stitching, registration, deconvolution, figure assembly, movie export.
