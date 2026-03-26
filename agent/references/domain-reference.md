# Microscopy Image Analysis — Domain Reference

---

## 1. Microscopy Modalities

### 1.1 Widefield Fluorescence

| Need | Solution |
|------|----------|
| **Flat-field correction** | `imageCalculator("Subtract", "raw", "dark"); imageCalculator("Divide", "corrected", "flat");` or BaSiC plugin |
| **Deconvolution** | DeconvolutionLab2, Iterative Deconvolve 3D. Consider deconvolving before colocalization. |
| **Bleach correction** | Bleach Correction plugin: simple ratio, exponential fit, or histogram matching. Correct before temporal intensity analysis. |

### 1.2 Confocal

| Need | Solution |
|------|----------|
| **Z-stack quantification** | Use Sum projection (not Max) for intensity measurements |
| **3D analysis** | 3D Objects Counter, MorphoLibJ, 3D ImageJ Suite, CLIJ2 |
| **Colocalization** | Deconvolve first; use Coloc 2 |
| **Chromatic aberration** | Channel alignment or bead calibration; false coloc if uncorrected |

### 1.3 Two-Photon / Multiphoton

| Need | Solution |
|------|----------|
| **Motion correction** | TurboReg, StackReg, Correct 3D Drift; Suite2p/CaImAn (Python) |
| **Calcium imaging** | ROIs → mean intensity/frame → dF/F = (F - F0) / F0 |
| **Scattering correction** | Exponential depth correction |

### 1.4 Light Sheet (SPIM)

| Need | Solution |
|------|----------|
| **Large data** | BigDataViewer (lazy-loading), HDF5/N5/Zarr |
| **Registration/fusion** | BigStitcher (multi-tile, multi-angle, multi-channel) |
| **Stripe removal** | Dual illumination or post-processing |

### 1.5 Super-Resolution

| Type | Tool | Notes |
|------|------|-------|
| STORM/PALM | ThunderSTORM | Detection, localization, drift correction, rendering. FRC for resolution. |
| SIM | SIMcheck | Validates reconstruction quality. Honeycomb = bad reconstruction. |
| STED | Standard confocal analysis | Higher resolution, more bleaching |

### 1.6 Electron Microscopy

| Need | Solution |
|------|----------|
| **Segmentation** | TrakEM2, Weka Segmentation, ilastik |
| **Stereology** | IMOD (systematic sampling for unbiased estimates) |
| **Measurement** | Scale bars critical — no embedded calibration |

### 1.7 Brightfield / Histology

| Need | Solution |
|------|----------|
| **Colour deconvolution** | `run("Colour Deconvolution", "vectors=[H&E]");` or `[H DAB]` |
| **Tissue segmentation** | QuPath (gold standard for whole-slide) |
| **IHC quantification** | H-DAB deconvolution → threshold DAB → H-score |

### 1.8 Phase Contrast / DIC

| Need | Solution |
|------|----------|
| **Cell boundaries** | Cellpose/StarDist (DL), Weka (ML), K-means for halo removal |
| **Confluency** | `run("8-bit"); setAutoThreshold("Huang"); run("Measure");` → %Area |

---

## 2. Standard Quantitative Analyses

### 2.1 CTCF

```
CTCF = Integrated Density - (Area_cell × Mean_background)
```

Measure 3+ background regions per cell. Same exposure across compared images. Do not normalize before measuring.

### 2.2 Colocalization

| Method | Measures | Range | When |
|--------|----------|-------|------|
| **Pearson's (PCC)** | Intensity correlation | -1 to +1 | Quick overview |
| **Manders' M1/M2** | Fraction of overlap | 0 to 1 | "What % of A colocs with B?" |
| **Costes threshold** | Auto threshold for Manders' | N/A | Always use with Manders' |

**Requirements**: Single-labeled controls, deconvolve first, no max projections, Costes randomization test.

### 2.3 Cell Counting — Method Selection

| Scenario | Method |
|----------|--------|
| Well-separated, high contrast | Threshold + Watershed + Analyze Particles |
| Touching round nuclei | StarDist |
| Irregular shapes | Cellpose |
| Noisy/low contrast | Weka Segmentation |
| Ground truth needed | Manual (Cell Counter) |

### 2.4 Morphometry (Shape Descriptors)

| Descriptor | Interpretation |
|-----------|---------------|
| Circularity (4pi*A/P^2) | 1.0 = circle, →0 = elongated |
| Aspect Ratio (Major/Minor) | Elongation |
| Solidity (Area/Convex) | 1 = smooth, <1 = irregular |
| Feret diameter | Longest dimension |

### 2.5 Nuclear/Cytoplasmic Ratio

Segment nuclei → expand ROIs → subtract nuclear from expanded = cytoplasmic ROI → N/C = Mean_nuclear / Mean_cytoplasmic.

### 2.6 Skeleton / Branch Analysis

```javascript
run("Skeletonize");
run("Analyze Skeleton (2D/3D)", "prune=none");
// → branches, junctions, endpoints, avg branch length
```

SNT for comprehensive neurite tracing with Sholl and Strahler analysis.

### 2.7 Texture (GLCM)

| Feature | Low | High |
|---------|-----|------|
| Entropy | Uniform | Complex |
| Homogeneity | Heterogeneous | Uniform |
| Contrast | Similar neighbors | Large local variation |

Plugins: GLCM Texture, GLCM Texture Tool.

### 2.8 Motion / Flow (PIV)

PIV plugins measure displacement/velocity fields via cross-correlation. Applications: collective migration, wound healing, morphogenesis.

---

## 3. Quality Control for Publication

### Image Integrity Rules

- No clipping/saturation (check histogram)
- Adjustments applied uniformly across compared panels
- No undisclosed non-linear (gamma) adjustments
- Raw data preserved

### Required in Methods (QUAREP)

Microscope type/model, objective (mag/NA/immersion), light source, detector, filters, pixel size, bit depth, acquisition software, post-processing, analysis software version, scale bars.

### Common Mistakes

| Mistake | Fix |
|---------|-----|
| Intensity from Max projections | Use Sum projection |
| Threshold on RGB | Convert to single channel |
| Compare across different exposures | Identical acquisition settings |
| JPEG for quantitative data | Save as TIFF |
| Cells as biological replicates | N = independent experiments |
| No background subtraction | CTCF or rolling ball |
| `Enhance Contrast > Normalize` | Use `setMinAndMax()` (display only) |
| Coloc from max projections | Analyze z-stack or single slices |
| No single-labeled controls | Image each label alone |
| No multiple comparison correction | Bonferroni, Tukey, or FDR |

---

## 4. Domain-Specific Plugins

### Neuroscience
SNT, Sholl Analysis, AnalyzeSkeleton, ABBA (Allen Atlas registration), TrackMate, CLIJ2

### Cell Biology
StarDist, Cellpose, MorphoLibJ, Weka, TrackMate, Coloc 2, BioVoxxel, Labkit

### Calcium Imaging
TACI, MCA (ImageJ); Suite2p, CaImAn (Python)

### Histology
QuPath, Colour Deconvolution 2, StarDist, Weka, Bio-Formats

---

## 5. Decision Trees

### Threshold Method

```
Bimodal histogram? → Otsu
Small foreground? → Triangle
Fluorescence? → Li
Low contrast/fuzzy? → Huang
Faint objects? → MaxEntropy
```

### Colocalization Metric

```
"Is there correlation?" → Pearson's
"What fraction overlaps?" → Manders' M1/M2 + Costes
Comparing conditions? → Manders' (produces fractions for stats)
Significance test? → Costes randomization
```

---

## 6. Statistical Considerations

### What Counts as N

| Experiment | N = | NOT N = |
|------------|-----|---------|
| Animal study | Animals | Cells from one animal |
| Cell culture | Independent passages/plates | Cells from one well |
| Patient samples | Patients | Regions from one section |

### Test Selection

| Comparison | Normal? | Test |
|-----------|---------|------|
| Two groups | Yes | t-test |
| Two groups | No | Mann-Whitney U |
| 3+ groups | Yes | ANOVA + Tukey |
| 3+ groups | No | Kruskal-Wallis + Dunn's |
| Two factors | Yes | Two-way ANOVA |
| Correlation | Yes/No | Pearson's r / Spearman's rho |

Correct for multiple comparisons when testing >2 groups or >1 hypothesis.
