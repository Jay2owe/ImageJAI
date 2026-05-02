# Microscopy Image Analysis — Domain Reference

Agent-oriented reference for microscopy modalities, standard quantitative
analyses, quality control for publication, domain-specific plugins, decision
trees, and statistical considerations. Covers widefield, confocal, two-photon,
light sheet, super-resolution, EM, brightfield/histology, and phase/DIC; plus
CTCF, colocalization, cell counting, morphometry, N/C ratio, skeleton
analysis, GLCM texture, and PIV.

Sources: Fiji/ImageJ plugin documentation, QUAREP-LiMi reporting guidelines,
standard microscopy methods literature. Use `python probe_plugin.py
"Plugin..."` to discover any installed plugin's parameters at runtime.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Which modality am I dealing with / what are its quirks?" | §2 |
| "How do I flat-field / bleach-correct / deconvolve widefield?" | §2.1 |
| "How do I handle confocal z-stacks and colocalization?" | §2.2 |
| "How do I process two-photon / calcium imaging?" | §2.3 |
| "How do I handle light sheet (SPIM) data?" | §2.4 |
| "How do I process STORM / SIM / STED?" | §2.5 |
| "How do I segment / measure EM?" | §2.6 |
| "How do I quantify IHC / H&E / whole-slide?" | §2.7 |
| "How do I segment phase contrast / DIC?" | §2.8 |
| "How do I compute CTCF?" | §3.1 |
| "Which colocalization metric do I use?" | §3.2, §6.2 |
| "Which cell counting method fits my data?" | §3.3 |
| "What do the shape descriptors mean?" | §3.4 |
| "How do I compute nuclear/cytoplasmic ratio?" | §3.5 |
| "How do I do skeleton / branch analysis?" | §3.6 |
| "What does GLCM texture tell me?" | §3.7 |
| "How do I measure motion / flow?" | §3.8 |
| "What are the image integrity / QUAREP rules?" | §4 |
| "What are common publication mistakes?" | §4 |
| "Which plugins for neuroscience / cell bio / histology / calcium?" | §5 |
| "Which threshold method should I pick?" | §6.1 |
| "Which statistical test should I use?" | §7 |
| "What counts as N?" | §7 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '`<term>`' domain-reference.md` to jump.

### A
`ABBA` §5 · `AnalyzeSkeleton` §3.6, §5 · `Analyze Particles` §3.3 · `ANOVA` §7 · `Aspect Ratio` §3.4

### B
`BaSiC` §2.1 · `Bio-Formats` §5 · `BigDataViewer` §2.4 · `BigStitcher` §2.4 · `BioVoxxel` §5 · `Bleach correction` §2.1 · `Brightfield` §2.7

### C
`CaImAn` §2.3, §5 · `Calcium imaging` §2.3, §5 · `Cell counting` §3.3 · `Cellpose` §2.8, §3.3, §5 · `Chromatic aberration` §2.2 · `Circularity` §3.4 · `CLIJ2` §2.2, §5 · `Coloc 2` §2.2, §5 · `Colocalization` §2.2, §3.2, §6.2 · `Colour Deconvolution` §2.7, §5 · `Confluency` §2.8 · `Confocal` §2.2 · `Costes` §3.2, §6.2 · `CTCF` §3.1

### D
`DeconvolutionLab2` §2.1 · `Decision Trees` §6 · `Deconvolution` §2.1, §2.2 · `DIC` §2.8

### E
`Electron Microscopy` §2.6 · `Entropy` §3.7

### F
`Feret diameter` §3.4 · `FRC` §2.5 · `Flat-field correction` §2.1

### G
`GLCM Texture` §3.7 · `GLCM Texture Tool` §3.7

### H
`H&E` §2.7 · `H-DAB` §2.7 · `HDF5` §2.4 · `Histology` §2.7, §5 · `Homogeneity` §3.7 · `H-score` §2.7 · `Huang` §6.1 · `Hypothesis testing` §7

### I
`ilastik` §2.6 · `IHC quantification` §2.7 · `IMOD` §2.6 · `Iterative Deconvolve 3D` §2.1

### K
`K-means` §2.8 · `Kruskal-Wallis` §7

### L
`Labkit` §5 · `Li` §6.1 · `Light Sheet` §2.4

### M
`Manders` §3.2, §6.2 · `Mann-Whitney U` §7 · `MaxEntropy` §6.1 · `MCA` §5 · `Morphometry` §3.4 · `MorphoLibJ` §2.2, §5 · `Motion / Flow (PIV)` §3.8 · `Multi-photon` §2.3

### N
`N (what counts)` §7 · `N/C ratio` §3.5 · `Neuroscience` §5 · `N5` §2.4

### O
`Otsu` §6.1

### P
`PALM` §2.5 · `Pearson's (PCC)` §3.2, §6.2, §7 · `Phase Contrast` §2.8 · `PIV` §3.8

### Q
`QUAREP` §4 · `QuPath` §2.7, §5

### R
`Registration` §2.4

### S
`Scattering correction` §2.3 · `Shape descriptors` §3.4 · `Sholl` §3.6, §5 · `SIM` §2.5 · `SIMcheck` §2.5 · `Skeleton analysis` §3.6 · `Skeletonize` §3.6 · `SNT` §3.6, §5 · `Solidity` §3.4 · `Spearman's rho` §7 · `Stack (Sum projection)` §2.2, §4 · `StackReg` §2.3 · `StarDist` §3.3, §5 · `Statistics` §7 · `STED` §2.5 · `Stereology` §2.6 · `Stitching` §2.4 · `STORM` §2.5 · `Strahler` §3.6 · `Stripe removal` §2.4 · `Suite2p` §2.3, §5 · `Super-resolution` §2.5

### T
`TACI` §5 · `Test selection` §7 · `Texture (GLCM)` §3.7 · `Threshold method` §6.1 · `ThunderSTORM` §2.5 · `Tissue segmentation` §2.7 · `t-test` §7 · `TrackMate` §5 · `TrakEM2` §2.6 · `Triangle` §6.1 · `TurboReg` §2.3 · `Two-Photon` §2.3 · `Tukey` §7

### W
`Weka Segmentation` §2.6, §2.8, §3.3, §5 · `Widefield Fluorescence` §2.1

### Z
`Zarr` §2.4 · `Z-stack quantification` §2.2

---

## §2. Microscopy Modalities

### §2.1 Widefield Fluorescence

| Need | Solution |
|------|----------|
| **Flat-field correction** | `imageCalculator("Subtract", "raw", "dark"); imageCalculator("Divide", "corrected", "flat");` or BaSiC plugin |
| **Deconvolution** | DeconvolutionLab2, Iterative Deconvolve 3D. Consider deconvolving before colocalization. |
| **Bleach correction** | Bleach Correction plugin: simple ratio, exponential fit, or histogram matching. Correct before temporal intensity analysis. |

### §2.2 Confocal

| Need | Solution |
|------|----------|
| **Z-stack quantification** | Use Sum projection (not Max) for intensity measurements |
| **3D analysis** | 3D Objects Counter, MorphoLibJ, 3D ImageJ Suite, CLIJ2 |
| **Colocalization** | Deconvolve first; use Coloc 2 |
| **Chromatic aberration** | Channel alignment or bead calibration; false coloc if uncorrected |

### §2.3 Two-Photon / Multiphoton

| Need | Solution |
|------|----------|
| **Motion correction** | TurboReg, StackReg, Correct 3D Drift; Suite2p/CaImAn (Python) |
| **Calcium imaging** | ROIs → mean intensity/frame → dF/F = (F - F0) / F0 |
| **Scattering correction** | Exponential depth correction |

### §2.4 Light Sheet (SPIM)

| Need | Solution |
|------|----------|
| **Large data** | BigDataViewer (lazy-loading), HDF5/N5/Zarr |
| **Registration/fusion** | BigStitcher (multi-tile, multi-angle, multi-channel) |
| **Stripe removal** | Dual illumination or post-processing |

### §2.5 Super-Resolution

| Type | Tool | Notes |
|------|------|-------|
| STORM/PALM | ThunderSTORM | Detection, localization, drift correction, rendering. FRC for resolution. |
| SIM | SIMcheck | Validates reconstruction quality. Honeycomb = bad reconstruction. |
| STED | Standard confocal analysis | Higher resolution, more bleaching |

### §2.6 Electron Microscopy

| Need | Solution |
|------|----------|
| **Segmentation** | TrakEM2, Weka Segmentation, ilastik |
| **Stereology** | IMOD (systematic sampling for unbiased estimates) |
| **Measurement** | Scale bars critical — no embedded calibration |

### §2.7 Brightfield / Histology

| Need | Solution |
|------|----------|
| **Colour deconvolution** | `run("Colour Deconvolution", "vectors=[H&E]");` or `[H DAB]` |
| **Tissue segmentation** | QuPath (gold standard for whole-slide) |
| **IHC quantification** | H-DAB deconvolution → threshold DAB → H-score |

### §2.8 Phase Contrast / DIC

| Need | Solution |
|------|----------|
| **Cell boundaries** | Cellpose/StarDist (DL), Weka (ML), K-means for halo removal |
| **Confluency** | `run("8-bit"); setAutoThreshold("Huang"); run("Measure");` → %Area |

---

## §3. Standard Quantitative Analyses

### §3.1 CTCF

```
CTCF = Integrated Density - (Area_cell × Mean_background)
```

Measure 3+ background regions per cell. Same exposure across compared images. Do not normalize before measuring.

### §3.2 Colocalization

| Method | Measures | Range | When |
|--------|----------|-------|------|
| **Pearson's (PCC)** | Intensity correlation | -1 to +1 | Quick overview |
| **Manders' M1/M2** | Fraction of overlap | 0 to 1 | "What % of A colocs with B?" |
| **Costes threshold** | Auto threshold for Manders' | N/A | Always use with Manders' |

**Requirements**: Single-labeled controls, deconvolve first, no max projections, Costes randomization test.

### §3.3 Cell Counting — Method Selection

| Scenario | Method |
|----------|--------|
| Well-separated, high contrast | Threshold + Watershed + Analyze Particles |
| Touching round nuclei | StarDist |
| Irregular shapes | Cellpose |
| Noisy/low contrast | Weka Segmentation |
| Ground truth needed | Manual (Cell Counter) |

### §3.4 Morphometry (Shape Descriptors)

| Descriptor | Interpretation |
|-----------|---------------|
| Circularity (4pi*A/P^2) | 1.0 = circle, →0 = elongated |
| Aspect Ratio (Major/Minor) | Elongation |
| Solidity (Area/Convex) | 1 = smooth, <1 = irregular |
| Feret diameter | Longest dimension |

### §3.5 Nuclear/Cytoplasmic Ratio

Segment nuclei → expand ROIs → subtract nuclear from expanded = cytoplasmic ROI → N/C = Mean_nuclear / Mean_cytoplasmic.

### §3.6 Skeleton / Branch Analysis

```javascript
run("Skeletonize");
run("Analyze Skeleton (2D/3D)", "prune=none");
// → branches, junctions, endpoints, avg branch length
```

SNT for comprehensive neurite tracing with Sholl and Strahler analysis.

### §3.7 Texture (GLCM)

| Feature | Low | High |
|---------|-----|------|
| Entropy | Uniform | Complex |
| Homogeneity | Heterogeneous | Uniform |
| Contrast | Similar neighbors | Large local variation |

Plugins: GLCM Texture, GLCM Texture Tool.

### §3.8 Motion / Flow (PIV)

PIV plugins measure displacement/velocity fields via cross-correlation. Applications: collective migration, wound healing, morphogenesis.

---

## §4. Quality Control for Publication

### §4.1 Image Integrity Rules

- No clipping/saturation (check histogram)
- Adjustments applied uniformly across compared panels
- No undisclosed non-linear (gamma) adjustments
- Raw data preserved

### §4.2 Required in Methods (QUAREP)

Microscope type/model, objective (mag/NA/immersion), light source, detector, filters, pixel size, bit depth, acquisition software, post-processing, analysis software version, scale bars.

### §4.3 Common Mistakes

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

## §5. Domain-Specific Plugins

### §5.1 Neuroscience
SNT, Sholl Analysis, AnalyzeSkeleton, ABBA (Allen Atlas registration), TrackMate, CLIJ2

### §5.2 Cell Biology
StarDist, Cellpose, MorphoLibJ, Weka, TrackMate, Coloc 2, BioVoxxel, Labkit

### §5.3 Calcium Imaging
TACI, MCA (ImageJ); Suite2p, CaImAn (Python)

### §5.4 Histology
QuPath, Colour Deconvolution 2, StarDist, Weka, Bio-Formats

---

## §6. Decision Trees

### §6.1 Threshold Method

```
Bimodal histogram? → Otsu
Small foreground? → Triangle
Fluorescence? → Li
Low contrast/fuzzy? → Huang
Faint objects? → MaxEntropy
```

### §6.2 Colocalization Metric

```
"Is there correlation?" → Pearson's
"What fraction overlaps?" → Manders' M1/M2 + Costes
Comparing conditions? → Manders' (produces fractions for stats)
Significance test? → Costes randomization
```

---

## §7. Statistical Considerations

### §7.1 What Counts as N

| Experiment | N = | NOT N = |
|------------|-----|---------|
| Animal study | Animals | Cells from one animal |
| Cell culture | Independent passages/plates | Cells from one well |
| Patient samples | Patients | Regions from one section |

### §7.2 Test Selection

| Comparison | Normal? | Test |
|-----------|---------|------|
| Two groups | Yes | t-test |
| Two groups | No | Mann-Whitney U |
| 3+ groups | Yes | ANOVA + Tukey |
| 3+ groups | No | Kruskal-Wallis + Dunn's |
| Two factors | Yes | Two-way ANOVA |
| Correlation | Yes/No | Pearson's r / Spearman's rho |

Correct for multiple comparisons when testing >2 groups or >1 hypothesis.
