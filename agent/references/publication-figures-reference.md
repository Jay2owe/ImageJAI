# Publication Figures Reference

Journal-quality scientific figures in ImageJ/Fiji: 2025/2026 journal specifications,
macro commands, multi-channel layout, scale bars and DPI mechanics, figure
assembly in Illustrator/Inkscape/Affinity, statistical plots, AI-disclosure
norms, and pre-submission checklists.

Sources: Nature Portfolio research-figure-guide
(https://research-figure-guide.nature.com/figures/preparing-figures-our-specifications/)
and final-submission page (https://www.nature.com/nature/for-authors/final-submission);
Cell Press figure guidelines (https://www.cell.com/figureguidelines); Science author
instructions (https://www.science.org/content/page/instructions-preparing-initial-manuscript);
eLife reviewed-preprint guide (https://elife-rp.msubmit.net/html/elife-rp_author_instructions.html);
PLOS figure guide (https://journals.plos.org/plosone/s/figures); bioRxiv submission
FAQ (https://www.biorxiv.org/about/FAQ); NeurIPS 2025/2026 instructions
(https://neurips.cc/Conferences/2025/PaperInformation/NeurIPS-FAQ); ImageJ macro
docs (https://imagej.net/ij/developer/macro/functions.html); ImageJ ScaleBar source
(https://imagej.net/ij/developer/source/ij/plugin/ScaleBar.java.html); Bio-Formats
Exporter docs (https://bio-formats.readthedocs.io); BioVoxxel Figure Tools
(https://imagej.github.io/plugins/biovoxxel-figure-tools); Wong 2011 (Nature Methods
8:441) colorblind palette; Liu & Moore 2018 (Nature Methods 15:113) magenta-green;
Crameri et al. 2020 (Nature Communications 11:5444) "The misuse of colour in
science communication"; Lord et al. 2020 (J Cell Biol 219:e202001064) SuperPlots;
Krzywinski & Altman 2013 (Nature Methods 10:921) error bars.

Cross-references: `image-integrity-reference.md` (allowed/forbidden manipulations,
journal forensic screening, integrity macros), `color-science-reference.md`
(LUT mechanics, composites vs RGB, calibration bars), `statistics-reference.md`
and `hypothesis-testing-microscopy-reference.md` (test selection, replicate
structure).

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code to build figures.
`python ij.py capture <name>` — screenshot the current image to inspect a step
before saving.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "What DPI does Nature / Science / Cell / eLife / PLOS want?" | §2.1, §2.4, §2.7 |
| "Does Nature accept CMYK or only RGB?" | §2.4, §2.8 |
| "What is the exact column width in mm for journal X?" | §2.2, §2.4, §2.7 |
| "Pixel dimensions for an 89 mm column at 600 DPI?" | §2.2 |
| "What font size is the minimum for Nature / eLife?" | §2.5, §2.7 |
| "Lowercase or uppercase panel labels — which journal wants which?" | §2.6, §9 |
| "Per-journal cheat sheet?" | §2.7 |
| "Can I save a micrograph as JPEG?" | §2.3, §14 |
| "How do I adjust display brightness without destroying data?" | §3, §4 |
| "Which two-channel LUTs are colorblind-safe and why?" | §5.1, §10.1, §10.2 |
| "Where do I get Wong's CVD palette as RGB values?" | §10.2 |
| "How do I simulate deuteranopia / protanopia before submission?" | §10.2, §10.3 |
| "What are the macro arguments for `Scale Bar...` (every flag)?" | §6, §6.4 |
| "How do I show the bar without the numeric label?" | §6, §6.4 |
| "How big should a scale bar be at 40×?" | §6.1 |
| "How do I add panel labels A/a, B/b, C/c on a montage?" | §9 |
| "How do I build a multi-channel panel montage?" | §10.1 |
| "How do I batch-generate figures for a directory?" | §12 |
| "What quantification chart should I use for N=8?" | §13 |
| "How do I handle biological vs technical replicates?" | §13.2 |
| "What is a SuperPlot / raincloud plot?" | §13.1, §13.2 |
| "Why did my overlays disappear when I saved?" | §11.6, §14 |
| "Methods section template?" | §11.7 |
| "How does the agent drive this workflow?" | §15 |
| "How does ImageJ actually embed DPI in the TIFF — there's no `resolution=` flag?" | §11.1 |
| "How do I save a 600 DPI 89 mm-wide TIFF?" | §11.1, §11.4 |
| "When raster vs vector? TIFF vs PNG vs PDF?" | §11.2 |
| "Resampling: Bicubic vs Bilinear vs None?" | §11.3 |
| "How do I export an SVG from ImageJ?" | §11.5 |
| "How do I write LZW-compressed TIFF (saveAs has no LZW)?" | §11.4 |
| "What software for figure assembly — Illustrator vs Inkscape vs Affinity?" | §17 |
| "Why must I avoid PowerPoint for final figures?" | §17.4 |
| "How do I link panels into Illustrator at 600 DPI?" | §17.5 |
| "Can I use generative AI to build/clean figures?" | §18 |
| "Where do I deposit raw image data — IDR or BioImage Archive?" | §19 |
| "Pre-submission checklist?" | §20 |
| "Image integrity / forbidden manipulations?" | §16, image-integrity-reference.md |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each journal, export setting,
or figure technique. Use `grep -n '`<term>`' publication-figures-reference.md`
to jump.

### A
`Adobe Illustrator` §17.1 · `Affinity Designer` §17.2 · `AI disclosure (Nature/Cell/Science/eLife/PLOS)` §18 · `AI-edited images` §18 · `AI model weights deposit` §19.3 · `Add Image...` §8 · `Add Selection...` §7, §8 · `Apply LUT (destructive)` §4, §11.6, §16 · `Arrow (makeArrow)` §7 · `Assembly (canvas)` §9 · `average (Scale...)` §11.3

### B
`bar chart (avoid alone)` §13.1 · `beeswarm` §13.1 · `Bicubic / Bilinear / None` §8, §11.3 · `Bio-Formats Exporter (LZW TIFF)` §11.4 · `BioImage Archive` §19.1 · `bioRxiv specs` §2.7 · `BioStudies` §19.1 · `BioVoxxel Figure Tools` §11.5 · `bitDepth` §11.6 · `Blue (LUT)` §5.1, §10.1 · `Bluish Green (Wong)` §10.2 · `box plot` §13.1 · `brightness/contrast (disclose)` §3, §11.7

### C
`calibration (Set Scale)` §11.1 · `Calibration Bar...` §6.2 · `Canvas Size...` §9 · `Cell (journal)` §2.4, §2.7 · `channel identity (color)` §6.2 · `Chart conventions` §13 · `CMYK (Nature/Cell)` §2.8 · `Coblis simulator` §10.3 · `Color Oracle` §10.3 · `Combine...` §8, §14 · `Composite` §5.2 · `compressing TIFF (LZW)` §11.4 · `Copy / Paste (assembly)` §9 · `Crameri et al. 2020` (sources), §10.2 · `Cyan + Magenta` §10.1 · `Cyan + Yellow` §5.1, §10.1 · `Cyan (LUT)` §5.1, §10.1

### D
`DAPI` §8, §10.1 · `dataset deposition` §19 · `Daltonization` §10.3 · `Destructive drawing (drawString/drawLine)` §7 · `Dot plot` §13.1 · `DPI (mechanism)` §11.1 · `DPI (per journal)` §2.1, §2.4, §2.7 · `drawLine` §7 · `drawString` §7 · `Duplicate` §8, §12

### E
`eLife` §2.4, §2.7 · `Enhance Contrast (never)` §3, §4, §14, §16 · `EPS` §2.3, §2.4, §2.7 · `error bars (SD/SEM/CI)` §13.3 · `exact P-values` §13.4 · `Export checklist` §11.7, §14, §20 · `Extended Data figures` §2.7 (Nature)

### F
`File formats` §2.3, §2.4, §2.7, §11.2 · `Figma` §17.3 · `Figure assembly software` §17 · `Fill (rectangle)` §7 · `Fire (LUT)` §5.1, §6.2 · `Flatten` §5.2, §7, §11.6, §14 · `Font Arial/Helvetica` §2.5 · `Font pixel size (pt × DPI / 72)` §2.5, §14 · `Font SansSerif` §2.5, §7, §9 · `Font (5pt minimum, Nature)` §2.5, §2.7 · `format decision tree` §11.2

### G
`Gamma (disclose if used)` §3 · `getMinAndMax` §4 · `getPixelSize` §6, §11.1 · `GFP` §8, §10.1 · `Glasbey for label maps` §10.2 · `GraphPad Prism` §13.5 · `Grays (LUT)` §5.1, §10.1 · `Green (LUT)` §5.1, §10.1 · `Green + Magenta` §5.1, §10.1

### H
`H&E (panel label color)` §9 · `hide flag (Scale Bar text)` §6, §6.4 · `histogram clipping` §14 · `Hugging Face (model deposit)` §19.3

### I
`Image integrity` §16 · `Image Data Resource (IDR)` §19.1 · `Imagetwin` §16 · `Images to Stack` §8, §10.1, §12 · `Inkscape` §17.2 · `Inset / Zoom` §8 · `Intensity legend (Calibration Bar)` §6.2 · `interpolation (None/Bilinear/Bicubic/average)` §8, §11.3

### J
`JCB (RUP)` §16 · `JPEG (never for microscopy)` §2.3, §14, §16 · `Journals (Nature/Cell/Science/eLife/PLOS/bioRxiv/NeurIPS)` §2.4, §2.7

### K
`Krzywinski & Altman 2013` (sources), §13.3

### L
`Layered PSD (Science)` §2.7 · `Line art DPI` §2.1 · `Linear adjustments only` §3, §16 · `Liu & Moore 2018` (sources), §10.2 · `Lord et al. 2020 (SuperPlots)` (sources), §13.2 · `LUT (colorblind-safe)` §5.1, §10.1, §10.2 · `LUT (perceptually uniform)` §5.1, §10.2 · `LZW compression (TIFF)` §11.4

### M
`Magenta (LUT)` §5.1, §10.1 · `Magnification (bar size)` §6.1 · `Make Montage...` §10, §14 · `makeArrow` §7 · `makeRectangle` §7, §8 · `Mean ± SEM bar (avoid)` §13.1, §13.3 · `Measure raw data first` §3 · `Merge Channels...` §5.2 · `Methods template` §11.7 · `Montage (border, label)` §10 · `mpl-inferno` §5.1, §10.2 · `mpl-magma` §5.1, §10.2 · `mpl-plasma` §5.1, §10.2 · `mpl-viridis` §5.1, §10.2 · `Multi-channel composite vs splits` §10.1, §17.6

### N
`Nature` §2.4, §2.7, §10.2 · `NeurIPS specs` §2.7 · `newImage` §9 · `n disclosure` §13.2 · `Non-destructive (Overlay)` §7 · `Normalize flag (destroys data)` §4, §14, §16

### O
`OME-TIFF (LZW via Bio-Formats)` §11.4 · `Orange (Wong)` §10.2 · `Overlay.addSelection` §7 · `Overlay.drawLine` §7 · `Overlay.drawRect` §7 · `Overlay.drawString` §7, §9, §14 · `Overlay.flatten` §11.6 · `Overlay.hide / remove / show` §7 · `Overlay.size` §11.6

### P
`P-values (exact)` §13.4 · `Panel label conventions per journal` §2.6, §9 · `Panel labels (a/A, b/B, c/C)` §9 · `PDF (preferred for vector)` §2.3, §11.2, §17 · `PDF/X-1a / PDF/X-4` §11.2, §17 · `PLOS ONE / PLOS Biology` §2.4, §2.7 · `PNG` §2.3, §11.2 · `Point size to pixels` §2.5, §14 · `PowerPoint (avoid)` §17.4 · `Properties... (calibration)` §11.1 · `Proofig AI` §16 · `Pseudocolored image` §6.2 · `pyplot / seaborn` §13.5

### Q
`QuickFigures (SVG/PDF/PPT export)` §11.5 · `Quantification panels` §13

### R
`raincloud plot` §13.1 · `Rainbow / Jet / Spectrum (avoid)` §5.1, §10.2 · `Reddish Purple (Wong)` §10.2 · `Rectangle overlay` §7, §8 · `Red + Green (avoid)` §5.1, §10.1 · `REMBI metadata` §19.2 · `Reproducibility` §19 · `resetMinAndMax` §4 · `RGB (vs CMYK)` §2.8, §11.6 · `RGB Color` §5.2, §10.1, §11.6 · `rename` §10.1

### S
`Sans-serif (Arial/Helvetica)` §2.5 · `saveAs("Tiff")` §11.4 · `saveAs Results (CSV)` §13.5 · `Scale Bar...` §6, §6.4 · `Scale Bar size guidelines` §6.1 · `Scale... (resize for export)` §11.3 · `Science (journal)` §2.4, §2.7 · `Selective enhancement (never)` §3, §16 · `selectWindow` §4, §8, §9, §10.1 · `SEM vs SD vs CI` §13.3 · `Set Scale...` §11.1 · `setBackgroundColor` §9 · `setColor` §7, §9 · `setFont` §7, §9, §14 · `setMinAndMax` §3, §4, §8, §10.1, §12 · `setVoxelSize` §11.1 · `Significance notation (*, **, ***, ****)` §13.4 · `Sim Daltonism` §10.3 · `Simulate Color Blindness (Fiji)` §10.2, §10.3 · `Single / double column width` §2.2, §2.4, §2.7 · `Size... (resize inset)` §8 · `Sky Blue (Wong)` §10.2 · `splice rules (gels/blots)` §16 · `Split Channels` §5.2, §10.1, §12 · `sRGB` §2.8 · `SuperPlots` §13.2 · `SVG export (BioVoxxel)` §11.5 · `Strip chart` §13.1

### T
`Term Index` §1 · `thickness (Scale Bar)` §6, §6.4 · `TIFF` §2.3, §2.4, §11.2, §11.4 · `Two-channel / three-channel LUTs` §5.1, §10.2 · `Typography conventions` §17.6

### V
`Vector vs raster` §11.2 · `Vermillion (Wong)` §10.2 · `Violin plot` §13.1

### W
`watermark (AI images)` §18 · `Wong's Palette` §10.2

### Y
`Yellow (LUT)` §5.1, §10.1 · `Yellow (Wong)` §10.2

### Z
`Zenodo` §19.1, §19.3

---

## §2 Journal Requirements

### §2.1 Resolution (DPI)

DPI must be the *effective* resolution at final printed size. Re-sampling 300 DPI
up to 600 DPI in software does not improve quality — start with enough source
pixels and resize down, never up.

| Content Type | Min DPI | Recommended | Notes |
|-------------|---------|-------------|-------|
| Halftone / photographic (micrographs, EM, histology) | 300 | 300-600 | Fluorescence: 300 minimum; 600 if dense detail |
| Line art / diagrams / plots | 600 | 1000-1200 | Vector preferred — resolution becomes irrelevant |
| Combination (photo + text + plots) | 500 | 600 | Match photographic content tier |

Most journals (Nature, Cell, Science, eLife, PLOS) accept 300 DPI as the floor;
600 DPI is the safe submission default for combined figures.

### §2.2 Pixel Dimensions at Common Widths

| Width | mm | inches | Pixels (300 DPI) | Pixels (600 DPI) |
|-------|-----|--------|------------------|------------------|
| Single column (Nature) | 89 | 3.50 | 1051 | **2103** |
| Single column (Cell) | 85 | 3.35 | 1004 | 2008 |
| Single column (Science) | 55 | 2.17 | 650 | 1300 |
| 1.5 column (Cell) | 114 | 4.49 | 1346 | 2693 |
| 1.5 column (Science) | 120 | 4.72 | 1417 | 2835 |
| Double column (Nature) | 183 | 7.20 | 2161 | **4323** |
| Double column (Cell) | 174 | 6.85 | 2055 | 4110 |
| Double column (Science) | 183 | 7.20 | 2161 | 4323 |
| eLife max | 174 | 6.85 | 2055 | 4110 |
| PLOS max | 190 | 7.50 | 2250 | 4500 |

Compute on the fly: `pixels = mm × DPI / 25.4` (or `inches × DPI`).

### §2.3 File Formats

| Format | Use | Notes |
|--------|-----|-------|
| **TIFF** (LZW or none) | Submission for raster | Lossless, 8/16-bit. ImageJ native saveAs writes uncompressed; LZW requires Bio-Formats Exporter (§11.4) |
| **PNG** | Web preview, supplementary, some open-access | Lossless. Nature/Cell prefer TIFF or PDF for primary figures |
| **EPS** | Vector figures, plots | Legacy; PDF replaces it at most journals |
| **PDF** (PDF/X-1a or X-4) | Vector + raster combination, final figures | Preferred final format at Nature/eLife/PLOS |
| **AI** | Cell Press, Science (editable vector) | Native Adobe Illustrator |
| **SVG** | Web/preprint and editable plots | Some journals accept; ImageJ exports via BioVoxxel (§11.5) |
| **JPEG** | **NEVER for microscopy** | Lossy, destroys quantitative data, comb-pattern detected by forensic tools (§16) |

### §2.4 Journal Column Widths and Headline Specs

| Journal | Single col | 1.5 col | Double col | DPI floor | Formats | Color | Notes |
|---------|-----------|---------|-----------|-----------|---------|-------|-------|
| **Nature** family | 89 mm | 120-136 mm | 183 mm | 300 (line art 600+) | PDF/EPS preferred; AI/TIFF accepted | RGB recommended; CMYK still accepted | Max ~10 MB/fig; Extended Data peer-reviewed; lowercase **a**/**b** panels |
| **Cell Press** | 85 mm | 114 mm | 174 mm | 300 (b/w 500; line 1000) | AI/EPS preferred (vector); TIFF/JPG raster | RGB | 6-8 pt min font; **A**/**B** uppercase panels; max height 225 mm |
| **Science** family | 55 mm (5.5 cm) | 120 mm | 183 mm (18.3 cm) | 300 (line 600) | AI/EPS/PDF preferred; layered PSD raster | RGB; print converted to CMYK | Helvetica/Myriad 5-7 pt; **A**/**B** uppercase |
| **eLife** | 85 mm | — | 170-200 mm | 300 (line 600) | TIFF/JPG/EPS/AI/PDF | RGB | Min font 7 pt; **A**/**B** uppercase; reviewed-preprint model |
| **PLOS** | 83 mm | — | 173-190 mm | 300-600 | TIFF or EPS only | RGB (sRGB) or grayscale | 10 MB/fig cap; **A**/**B** uppercase; PACE pre-flight tool |
| **bioRxiv** | n/a | n/a | n/a | n/a (PDF only) | PDF, GIF, TIFF, EPS, JPEG | n/a | Recommend match target journal specs |
| **NeurIPS** | 5.5" text width | n/a | n/a | n/a (vector preferred) | PDF (embedded vector) | n/a | Times Roman 10 pt body; figures via `\includegraphics` |

URLs (consult for the latest):
- Nature: https://research-figure-guide.nature.com/figures/preparing-figures-our-specifications/
- Nature integrity: https://www.nature.com/nature-portfolio/editorial-policies/image-integrity
- Cell Press: https://www.cell.com/figureguidelines (and per-journal `figureguidelines` paths)
- Science: https://www.science.org/content/page/instructions-preparing-initial-manuscript
- eLife: https://elife-rp.msubmit.net/html/elife-rp_author_instructions.html
- PLOS: https://journals.plos.org/plosone/s/figures
- bioRxiv: https://www.biorxiv.org/about/FAQ
- NeurIPS: https://neurips.cc/Conferences/2025/PaperInformation/NeurIPS-FAQ

### §2.5 Font Requirements

General rule: **Sans-serif (Arial/Helvetica), 5-8 pt minimum at final print size.**

| Journal | Min body font | Panel label | Family |
|---------|--------------|-------------|--------|
| Nature | **5 pt** | 8 pt bold lowercase | Arial/Helvetica |
| Cell | 6 pt | 8-10 pt bold uppercase | Arial/Helvetica |
| Science | 5 pt | 8-10 pt bold uppercase | Helvetica/Myriad Pro |
| eLife | **7 pt** | 8-10 pt bold uppercase | Arial/Helvetica |
| PLOS | 6 pt | 8-10 pt bold uppercase | Arial/Times |

Convert points to pixels: `pixels = pointSize × DPI / 72`.
At 300 DPI: 6 pt = 25 px; 7 pt = 29 px; 8 pt = 33 px.
At 600 DPI: 6 pt = 50 px; 7 pt = 58 px; 8 pt = 67 px.

```javascript
// Compute font pixel size for a target point size at a given export DPI
dpi = 600;
pointSize = 8;
pixelSize = Math.round(pointSize * dpi / 72);
setFont("SansSerif", pixelSize, "bold antialiased");
```

Italics: variables (*n*, *t*, *P*, *r*), gene names (*Bmal1*, *Per2*), species
binomials. Upright: units (μm, ms, Hz), protein names, statistical functions
in some house styles. Use the proper minus sign U+2212 (−) for negative axis
values, not the hyphen-minus.

### §2.6 Panel Label Conventions Per Journal

| Journal | Case | Style | Font weight |
|---------|------|-------|-------------|
| Nature | lowercase | **a**, **b**, **c** | bold |
| Cell | UPPERCASE | **A**, **B**, **C** | bold |
| Science | UPPERCASE | **A**, **B**, **C** | bold |
| eLife | UPPERCASE | **A**, **B**, **C** | bold |
| PLOS | UPPERCASE | **A**, **B**, **C** | bold |
| NeurIPS / arXiv | n/a | **(a)**, **(b)** within figure caption | per template |

Position: top-left of each panel, inset 1-2 mm. Color: white on dark
fluorescence/EM, black on brightfield/H&E/plots.

### §2.7 Per-Journal Cheat Sheet

**Nature** — single 89 mm / double 183 mm. PDF/EPS preferred (AI/TIFF accepted).
RGB recommended; CMYK still accepted. Min font 5 pt. Lowercase bold panel
labels (a, b, c). Max ~10 MB per figure file. Extended Data figures (max ~10
per article) follow main-figure specs and are peer-reviewed and citable.
Supplementary Information is a single PDF, not copyedited or typeset.
Generative AI for image content is forbidden except in articles about AI.

**Cell Press** (Cell, Mol Cell, Cancer Cell, Neuron, Curr Biol) — 85 mm /
114 mm / 174 mm. AI or EPS for vector; TIFF/JPG for raster. 300 DPI photo,
500 DPI black-and-white halftone, 1000 DPI line art. Uppercase bold panel
labels. Max page height 225 mm. Generative AI in figures forbidden.

**Science** family — single 5.5 cm / intermediate 12 cm / double 18.3 cm.
AI/EPS/PDF preferred (editable vector); layered PSD acceptable for complex
raster. 300 DPI halftone, 600 DPI line art. Helvetica preferred (Myriad Pro
acceptable). Generative AI forbidden without editor permission; full prompt
+ tool + version disclosure in cover letter and acknowledgments.

**eLife** (post-2023 reviewed-preprint model) — half 85 mm, full 170-200 mm.
Min font 7 pt. RGB. TIFF/JPG/EPS/AI/PDF accepted. Source data files linked
per figure (`Figure 1 — figure supplement 1` naming).

**PLOS ONE / PLOS Biology / PLOS Comp Bio** — max width 7.5 in (190 mm).
TIFF or EPS only. 300-600 DPI. Max 10 MB per figure. Pre-flight via the
PLOS PACE tool before submission.

**bioRxiv** — single PDF or text + figures (PDF, GIF, TIFF, EPS, JPEG).
No DPI floor specified — match target journal.

**NeurIPS / arXiv ML conferences** — single-column LaTeX, text width ~5.5 in,
9 in tall, 1.5 in left margin, body Times Roman 10 pt. Figures: vector PDF
preferred via `\includegraphics`. No specific DPI floor; vector strongly
preferred for reviewer legibility on B/W print.

### §2.8 Color Space (RGB vs CMYK vs sRGB)

| Journal | Recommendation | Practical |
|---------|----------------|-----------|
| Nature | **RGB recommended**; CMYK accepted | Submit RGB; print is auto-converted to CMYK by Nature |
| Cell | RGB | Print converted to CMYK on production |
| Science | RGB | Print converted to CMYK on production |
| eLife | RGB | Online-only; no CMYK relevant |
| PLOS | RGB or grayscale; sRGB de facto | Online-only |

Earlier guidance ("Nature accepts no CMYK") is outdated for 2025/2026 — Nature's
current Final Submission page explicitly accepts CMYK while recommending RGB.
sRGB is the safe color profile for screen-first journals (eLife, PLOS, all
preprints). ImageJ does not embed an ICC profile in TIFFs; if a journal demands
explicit sRGB tagging, post-process with ImageMagick:
`magick fig.tif -profile sRGB.icc fig_sRGB.tif`.

---

## §3 Cardinal Rules

1. **NEVER modify pixels for display.** Use `setMinAndMax()` only.
   `run("Enhance Contrast", "normalize")` permanently destroys data (§16,
   image-integrity-reference.md §3 forbidden).
2. **Identical display settings** across all compared panels (same min/max
   per channel, same gamma if disclosed).
3. **Measure raw data FIRST**, then process a COPY for display.
4. **Linear adjustments only.** Gamma must be disclosed in figure legend.
5. **No selective enhancement.** Whole-image adjustments only.
6. **Scale bars on all micrographs**, calibrated in metric units.
7. **Colorblind-safe palette.** Magenta/Green or Cyan/Yellow, never Red/Green.
8. **Disclose everything** in Methods (display range, software version, every
   processing step, every plugin parameter).
9. **Generative AI in image content is forbidden** at Nature, Cell, Science
   without explicit editor permission (§18).

---

## §4 Display Adjustment (setMinAndMax)

```javascript
setMinAndMax(lower, upper);    // display only, pixels unchanged
resetMinAndMax();              // auto range
getMinAndMax(min, max);        // query current

// Apply SAME range to compared panels
selectWindow("control");  setMinAndMax(100, 3500);
selectWindow("treated");  setMinAndMax(100, 3500);
```

**NEVER use:** `run("Enhance Contrast", "saturated=0.35 normalize");` (destroys
data) or `run("Apply LUT");` (converts display to pixels). See
color-science-reference.md §2 for the full safe/destructive operation table.

---

## §5 LUTs and Color

### §5.1 Colorblind-Safe Assignments

| Channels | Recommended | Avoid |
|----------|------------|-------|
| 2-channel | Green + Magenta, or Cyan + Yellow, or Cyan + Magenta | Red + Green |
| 3-channel | Blue + Green + Magenta | RGB direct, R+G+B with red and green |
| 4-channel | Blue + Green + Magenta + Yellow | adds Cyan if 5+ |
| Intensity map | Grays, mpl-viridis, mpl-inferno, mpl-magma, mpl-plasma | Rainbow, Jet, Spectrum, Fire (non-uniform) |

```javascript
run("Green"); run("Magenta"); run("Cyan"); run("Blue"); run("Grays");
run("mpl-viridis");  // perceptually uniform, colorblind-safe (preferred for heatmaps)
run("mpl-inferno");  // wide luminance range
run("mpl-magma");    // sparse signal
run("mpl-plasma");   // vibrant
```

See color-science-reference.md §3 for the full LUT catalogue (50+ LUTs ranked
by uniformity and CVD-safety).

### §5.2 Composite/Merge

```javascript
run("Split Channels");  // creates C1-title, C2-title, ...

// Apply LUTs + display range to each, then merge
run("Merge Channels...", "c1=C1-title c2=C2-title c3=C3-title create");
// c1=red, c2=green, c3=blue, c4=gray, c5=cyan, c6=magenta, c7=yellow

// Convert for export
run("RGB Color");   // composite to RGB (no overlays)
run("Flatten");     // burns overlays AND converts to RGB
```

---

## §6 Scale Bars

```javascript
run("Scale Bar...",
    "width=20 height=8 thickness=4 font=18 "
  + "color=White background=None "
  + "location=[Lower Right] "
  + "bold serif overlay hide");
```

Full parameter set (verified against `ij.plugin.ScaleBar` source):

| Parameter | Type | Notes |
|-----------|------|-------|
| `width` | numeric | Bar length in **calibrated units** (μm, mm) |
| `thickness` | int (px) | Bar thickness in pixels |
| `height` | int (px) | Text height; older builds ignore — `font=` controls label size |
| `font` | int (px) | Label font size in pixels |
| `color` | enum | `White`, `Black`, `Light Gray`, `Gray`, `Dark Gray`, `Red`, `Green`, `Blue`, `Yellow` |
| `background` | enum | `None`, `Black`, `White`, `Dark Gray`, `Gray`, `Light Gray`, `Yellow`, `Blue`, `Green`, `Red` |
| `location` | enum | `[Upper Right]`, `[Lower Right]`, `[Lower Left]`, `[Upper Left]`, `[At Selection]` (square brackets required) |
| `bold` | flag | Bold label |
| `serif` | flag | Serif typeface (rare for scientific figures) |
| `overlay` | flag | **Non-destructive** — bar is overlay, not pixel data |
| `hide` | flag | **Hides numeric label** (bar without text — caption-based) |

Without `overlay`, the bar is **burned into pixel data immediately** and
cannot be removed. Always use `overlay` for editable workflows; flatten only
on the export copy.

### §6.1 Size Guidelines

| Magnification | Typical Bar Width |
|---------------|-------------------|
| Low (4-5×) | 500 μm - 1 mm |
| Medium (10-20×) | 100-200 μm |
| High (40-63×) | 20-50 μm |
| Oil (100×) | 5-10 μm |
| EM / super-res | 1-5 μm or nm |

Rule of thumb: bar ~10-20% of image width. For multi-panel same-magnification
figures, place one scale bar on the last panel and note in legend "scale bar
applies to all panels".

```javascript
// Verify calibration before adding
getPixelSize(unit, pw, ph);
if (unit == "pixels") exit("Image NOT calibrated!");
```

### §6.2 Calibration Bars (Intensity Legends)

For pseudocolored images (Fire, Inferno, viridis) where color represents
intensity, not channel identity.

```javascript
run("Calibration Bar...",
    "location=[Upper Right] fill=Black label=White "
  + "number=5 decimal=0 font=14 zoom=1 overlay");
```

Not needed for standard fluorescence channels (Green, Magenta) where color
encodes channel identity. See color-science-reference.md §7 for the full
parameter table including `[At Selection]` placement.

### §6.3 Scale Bar Number — Hide vs Show

Many journals (Nature, Cell, eLife) ask authors to put scale-bar dimensions in
the figure caption, not on the image. The `hide` flag does this:

```javascript
// Bar visible, numeric label hidden — value goes in caption
run("Scale Bar...",
    "width=20 height=8 thickness=4 font=18 color=White "
  + "background=None location=[Lower Right] bold overlay hide");
```

The legend then reads: "Scale bar, 20 μm." Always still record the numeric
value programmatically so the caption stays in sync if you regenerate.

### §6.4 Scale Bar Without Overwriting Pixels

```javascript
// Step 1 — add as overlay, non-destructive
run("Scale Bar...",
    "width=50 height=5 thickness=4 font=20 color=White "
  + "background=None location=[Lower Right] bold overlay");

// Step 2 — flatten ONLY on the export copy
run("Duplicate...", "title=for_export");
selectWindow("for_export");
run("Flatten");                         // burns overlay into pixels
saveAs("Tiff", "/path/AI_Exports/figure_with_bar.tif");
close();                                // close the export copy
selectWindow("original");               // overlay still editable on original
```

---

## §7 Overlays and Annotations

### Non-Destructive (Preferred)

```javascript
setFont("SansSerif", 24, "bold antialiased");
setColor("white");
Overlay.drawString("GFP", 10, 30);     // text at (x, baseline_y)
Overlay.drawLine(x1, y1, x2, y2);
Overlay.drawRect(x, y, w, h);

// Arrow
makeArrow(x1, y1, x2, y2, "filled small");
Overlay.addSelection;

// ROI to overlay
makeRectangle(100, 100, 200, 200);
run("Add Selection...", "stroke=Yellow width=2 fill=None");

Overlay.show; Overlay.hide; Overlay.remove;
```

### Destructive (burns into pixels — use on COPY only)

```javascript
setColor("white"); setFont("SansSerif", 24, "bold");
drawString("Label", x, y);
drawLine(x1, y1, x2, y2);
fillRect(x, y, w, h);
```

**Flatten:** `run("Flatten");` burns all overlays into RGB. Required before
saving raster figures with annotations. For SVG/PDF export via BioVoxxel
(§11.5), do **not** flatten — overlays export as editable vector elements.

---

## §8 Insets and Zooms

```javascript
// Mark region on original
makeRectangle(x, y, w, h);
run("Add Selection...", "stroke=Yellow width=2 fill=None");

// Create zoomed inset
run("Duplicate...", "title=inset");
makeRectangle(x, y, w, h); run("Crop");
run("Size...",
    "width=" + (getWidth()*3) + " height=" + (getHeight()*3) + " "
  + "interpolation=None");
// interpolation=None for pixel-perfect; Bicubic for smooth (§11.3)

// Place as overlay on main image
selectWindow("original");
run("Add Image...", "image=inset x=350 y=10 opacity=100");
```

Always include a **separate scale bar** on the inset — its scale differs from
the parent panel.

---

## §9 Panel Labels (a/A, b/B, c/C)

Use lowercase for Nature, uppercase for Cell/Science/eLife/PLOS (§2.6).

```javascript
// Single image, Nature-style lowercase
setFont("SansSerif", 36, "bold antialiased"); setColor("white");
Overlay.drawString("a", 8, 36);

// On montage — calculate panel positions
panelW = getWidth() / cols;
panelH = getHeight() / rows;
labels = newArray("a", "b", "c", "d");          // lowercase for Nature
// labels = newArray("A", "B", "C", "D");       // uppercase for Cell/Science
for (r = 0; r < rows; r++)
    for (c = 0; c < cols; c++)
        Overlay.drawString(labels[r*cols+c], c*panelW+8, r*panelH+36);
```

| Style | When |
|-------|------|
| White bold on dark | Fluorescence, EM, dark-background panels |
| Black bold on light | H&E, brightfield, plots |
| Top-left of each panel | Universal convention |

Font size: 8-10 pt at final print size (32-67 px depending on DPI). For an
89 mm panel at 600 DPI, 10 pt label = 83 px.

### Combining Images (Canvas)

```javascript
// Add margins
setBackgroundColor(255, 255, 255);
run("Canvas Size...",
    "width=" + (getWidth()+80) + " height=" + (getHeight()+80) + " "
  + "position=Center");

// Manual assembly on blank canvas
newImage("Figure", "RGB Color", 2400, 1800, 1);
selectWindow("panelA"); run("Select All"); run("Copy");
selectWindow("Figure"); makeRectangle(0, 0, w, h); run("Paste");
run("Select None");
```

---

## §10 Montages, Color Palettes, CVD Simulation

```javascript
// From stack/hyperstack
run("Make Montage...",
    "columns=3 rows=2 scale=1 first=1 last=6 increment=1 "
  + "border=2 font=14 label use");
// border=N: gap in pixels (foreground color if `use` set)
// label: slice labels printed on each panel

// From separate images (must be same size/type)
run("Images to Stack", "use");
run("Make Montage...", "columns=3 rows=1 scale=1 border=2");

// Side-by-side (2 images)
run("Combine...", "stack1=left stack2=right");        // horizontal
run("Combine...", "stack1=top stack2=bottom combine"); // vertical
```

### §10.1 Channel Panel Montage (common pattern)

```javascript
// Split, apply LUT+range to each, convert to RGB, stack, montage
run("Split Channels");
selectWindow("C1-fig"); run("Blue");    setMinAndMax(50, 2000);  run("RGB Color"); rename("DAPI");
selectWindow("C2-fig"); run("Green");   setMinAndMax(100, 3500); run("RGB Color"); rename("GFP");
selectWindow("C3-fig"); run("Magenta"); setMinAndMax(80, 3000);  run("RGB Color"); rename("Marker");
// Create merge from originals before RGB conversion (use `keep`)
run("Images to Stack", "name=Figure use");
run("Make Montage...", "columns=4 rows=1 scale=1 border=2");
```

For multiplex (4+ channels), per-channel grayscale panels read better than a
single coloured composite. Use:

```javascript
// Multiplex: grayscale singles + one composite
selectWindow("C1-fig"); run("Grays"); resetMinAndMax();
selectWindow("C2-fig"); run("Grays"); resetMinAndMax();
// ... per channel
```

### §10.2 Accessibility and Color

#### Why magenta+green beats red+green

~6% of males have deuteranopia (M-cone defect) or protanopia (L-cone defect),
reducing red-green discrimination. Magenta = R+B (RGB 255,0,255); even when
the red channel is invisible to a deuteranope, the **blue component
(S-cone, unaffected)** preserves distinguishability against green.
Co-localization appears as **white** (R+G+B all on) — unambiguous to all
viewers. Red+green colocalization gives yellow (255,255,0), invisible against
either parent channel for CVD viewers. Citations: Wong 2011 *Nat Methods*
8:441; Liu & Moore 2018 *Nat Methods* 15:113.

#### Colorblind-Safe Combinations

| Combo | Safe? | Notes |
|-------|-------|-------|
| Green + Magenta | YES | Best — colocalization is white |
| Cyan + Magenta | YES | Excellent — colocalization is light blue/white |
| Cyan + Yellow | YES | Good, but yellow on white background is poor |
| Blue + Yellow | YES | Strong contrast |
| Blue + Green + Magenta | YES (3-ch) | Standard for DAPI + 2 markers |
| Green + Red | **NO** | Avoid for any new figure |
| Rainbow / Jet / Spectrum | **NO** | Creates false features (Crameri 2020) |

#### Wong's Palette (Wong 2011, *Nature Methods*) — for plot colors

| Color | RGB | Hex |
|-------|-----|-----|
| Black | 0, 0, 0 | #000000 |
| Orange | 230, 159, 0 | #E69F00 |
| Sky Blue | 86, 180, 233 | #56B4E9 |
| Bluish Green | 0, 158, 115 | #009E73 |
| Yellow | 240, 228, 66 | #F0E632 |
| Blue | 0, 114, 178 | #0072B2 |
| Vermillion | 213, 94, 0 | #D55E00 |
| Reddish Purple | 204, 121, 167 | #CC79A7 |

R: `ggthemes::scale_colour_colorblind()`. Python: `seaborn.color_palette("colorblind")`.

#### Glasbey for Label Maps

For segmentation/tracking output (each object an integer label), use Glasbey
LUTs (`glasbey`, `glasbey_inverted`, `glasbey_on_dark`). Adjacent integer
labels get maximally distinct colours in CIELAB space, so neighbouring
touching cells are visibly different. Rainbow assigns adjacent labels
adjacent hues, which fail under CVD and look like banding.

```javascript
// After segmentation produces a label image
run("glasbey on dark");
```

#### Sequential Heatmaps — Crameri's Recommendation

Use perceptually-uniform LUTs (Crameri et al. 2020, *Nat Commun* 11:5444):

| LUT | When |
|-----|------|
| `mpl-viridis` | General default — perceptually uniform, CVD-safe |
| `mpl-inferno` | Wide luminance range; sparse-on-dark signals |
| `mpl-magma` | Sparse signals on dark background |
| `mpl-plasma` | Vibrant; presentation-friendly |
| `mpl-cividis` (if installed) | Optimized for both deuteranopes and tritanopes |
| **AVOID** `Rainbow RGB`, `Spectrum`, `Jet`, `Fire` (non-uniform) | Creates false perceptual edges |

### §10.3 Daltonization / CVD-Simulation Tools

| Tool | Platform | URL | Use |
|------|---------|-----|-----|
| **Color Oracle** | Mac/Win/Linux desktop | https://colororacle.org | Hotkey simulator over the whole screen — leave running while building figures |
| **Coblis** | Web | https://www.color-blindness.com/coblis-color-blindness-simulator/ | Drag-drop PNG, simulates 8 CVD types |
| **Sim Daltonism** | Mac/iOS | App Store | Cursor-following preview |
| **Fiji built-in** | ImageJ | `Image > Color > Simulate Color Blindness` | Run on the active image |
| **Adobe Illustrator** | Illustrator | `View > Proof Setup > Color Blindness — Protanopia/Deuteranopia` | Soft-proof on layout |

```javascript
// Fiji built-in simulation
run("Simulate Color Blindness", "type=Deuteranopia");  // most common (~6% males)
run("Simulate Color Blindness", "type=Protanopia");     // ~2% males
run("Simulate Color Blindness", "type=Tritanopia");     // very rare
```

**Mandatory**: simulate **both** deuteranopia AND protanopia before submission;
they differ subtly. Tritanopia recommended if the figure uses blue/yellow
channel pairs.

---

## §11 Figure Export, DPI, Resampling

### §11.1 The DPI Mechanism — Pixel Size IS DPI

`run("Save", "tiff resolution=600")` **does NOT exist** — `run("Save")` accepts
no arguments and the `resolution=` token is silently ignored. ImageJ TIFFs
carry the standard TIFF tags `XResolution`, `YResolution`, and `ResolutionUnit`
(1=none, 2=inch, 3=centimeter), and ImageJ derives those tags from the spatial
calibration: **pixel-size IS the DPI metadata.**

Three equivalent macro idioms set the calibration:

```javascript
// Idiom A — Set Scale with unit "inch" (canonical DPI workflow)
run("Set Scale...", "distance=600 known=1 unit=inch");
// 600 px per 1 inch -> ResolutionUnit=2, XResolution=YResolution=600

// Idiom B — Properties... directly
run("Properties...",
    "channels=1 slices=1 frames=1 unit=inch "
  + "pixel_width=0.0016667 pixel_height=0.0016667 voxel_depth=1");
// pixel_width = 1/600 inch -> same TIFF tags

// Idiom C — programmatic (preferred inside macros, no dialog flicker)
setVoxelSize(1/600, 1/600, 1, "inch");
saveAs("Tiff", "/path/AI_Exports/figure.tif");   // tags now embedded
```

For a Nature single-column 89 mm panel at 600 DPI: 89 / 25.4 = 3.504 inches ×
600 DPI = **2103 pixels wide**. Set the unit to `inch` before `saveAs` so that
Photoshop / Illustrator read the TIFF as exactly 600 DPI.

You can keep the science calibration in `micron` for analysis and only switch
to `inch` on the export copy:

```javascript
// On the export copy only:
run("Duplicate...", "title=for_export");
// resize to target panel pixels (§11.3)
setVoxelSize(1/600, 1/600, 1, "inch");
saveAs("Tiff", path);
close();
selectWindow("original");   // micron calibration intact
```

ImageJ does **not** write resolution tags into JPEG. Only TIFF (and PNG via
the pHYs chunk in recent IJ versions).

### §11.2 Format / DPI Decision Tree

```
Microscopy / EM / histology raw panel
    -> TIFF (LZW or none), 8-bit RGB after display setup, 600 DPI

Plot / chart / line graph / pathway diagram / schematic
    -> SVG or PDF (vector — scales to any DPI)

Mixed combination (raster + vector text + line art)
    -> PDF (PDF/X-1a or PDF/X-4) with embedded raster + vector layers

Final assembled multi-panel figure for submission
    -> PDF with rasters embedded at 600 DPI, fonts embedded
       OR .ai (Cell Press) / .eps (legacy)

When 8-bit vs 16-bit?
    Raw acquisition / quantification    -> 16-bit
    Final published figure              -> 8-bit RGB (most journals require)

When TIFF vs PNG vs PDF?
    TIFF (LZW)  -> Submission for raster microscopy. Lossless, archival.
    PNG         -> Web/preview/supplementary only. Some open-access OK.
    PDF         -> Vector preferred. Final figure container.
    JPEG        -> Never for microscopy. Period.
```

### §11.3 Resampling for Export — `run("Scale...", ...)`

```javascript
run("Scale...",
    "x=- y=- width=2103 height=1500 "
  + "interpolation=Bicubic average "
  + "create title=fig1_resized");
```

| Parameter | Notes |
|-----------|-------|
| `x`, `y` | Numeric scale factors; use `-` to let `width`/`height` drive |
| `z`, `depth` | Stack only |
| `width`, `height` | Target pixels |
| `interpolation` | `None`, `Bilinear`, `Bicubic`, `average` |
| `average` | **Flag** — average when downsampling, prevents aliasing — required for any reduction below 1.0× |
| `create` | New window, source untouched |
| `process` | All slices (stacks) |
| `title=...` | Output window name |

**Choice of interpolation for microscopy panel export:**

| Operation | Recommended |
|-----------|-------------|
| Upscaling for "scientific" pixel-grid look | `None` (nearest-neighbour) |
| Upscaling for smooth display | `Bicubic` |
| Downscaling (any scale < 1.0) | `Bicubic` + `average` |
| Quantification preserved | Avoid resampling; resize on a copy only |

Bilinear is rarely the best choice — softer than Bicubic without being
faithful to the pixel grid.

### §11.4 Save Commands and Compression

| Command | Format | Compression | Notes |
|---------|--------|-------------|-------|
| `run("Save")` | TIFF (current path) | None | No options; just round-trips |
| `saveAs("Tiff", path)` | TIFF | **None** | ImageJ's TIFF writer has no LZW |
| `saveAs("PNG", path)` | PNG | DEFLATE (lossless) | 8-bit RGB or 16-bit gray |
| `saveAs("Jpeg", path)` | JPEG | Lossy | Never for microscopy |
| `saveAs("ZIP", path)` | TIFF in ZIP | DEFLATE wrapper | Compressed but not LZW |
| `run("Bio-Formats Exporter", ...)` | TIFF/OME-TIFF | LZW, JPEG-2000, zlib | For LZW use this |

```javascript
// LZW-compressed OME-TIFF via Bio-Formats Exporter
run("Bio-Formats Exporter",
    "save=[/path/AI_Exports/figure1.ome.tif] export compression=LZW");
// Compression options: Uncompressed, LZW, JPEG, JPEG-2000,
//                     JPEG-2000 Lossy, zlib
```

Path goes in square brackets if it has spaces. Use `.tif` for ImageJ-flavoured
TIFF or `.ome.tif` for OME-TIFF; both accept `compression=LZW`.

### §11.5 SVG Export

ImageJ has no core SVG export. Two practical paths:

```javascript
// BioVoxxel Figure Tools update site
run("Export SVG");                       // active image + overlays as one .svg
run("Export Time Series to SVGs");
```

Install: `Help > Update... > Manage update sites > BioVoxxel Figure Tools`.
Overlays (scale bars added with `overlay`, ROIs, text) export as **editable
vector objects** — Inkscape/Illustrator can edit each element. Pixel data
embeds as a base64 PNG inside the SVG.

Alternatives: **QuickFigures** (TIF/PNG/PDF/EPS/SVG/PPT, fully macro-recordable),
**EzFig**, **ScientiFig**. From Groovy, `org.freehep.graphicsio.svg.SVGGraphics2D`
if FreeHEP is on the classpath.

### §11.6 Flatten and Bit-Depth for Export

```javascript
// Export checklist
if (bitDepth() != 24) run("RGB Color");      // 8-bit RGB for figures
if (Overlay.size > 0) run("Flatten");         // burn overlays for raster
saveAs("Tiff", "/path/AI_Exports/Figure1.tif");
```

| Command | Behaviour |
|---------|-----------|
| `run("Flatten")` | New RGB image with overlays burned to pixels (original untouched) |
| `Overlay.flatten` | Macro-language equivalent; safer (no dialog flicker) |
| `run("Flatten", "stack")` | Flatten across all slices |

**Until you call Flatten**, an overlay's scale bar and labels live in the TIFF
header only. Photoshop/Illustrator will not show them — for raster export you
must Flatten before `saveAs`. For SVG export via BioVoxxel, do **not** Flatten
— overlays then export as vector elements.

Known bug: in some Fiji 1.54 builds, `Flatten` misbehaves on Composite-mode
hyperstacks (channels render wrong colours). Workaround: `run("Stack to RGB")`
or `run("RGB Color")` first, then Flatten.

### §11.7 Methods Template

```
Images acquired on [microscope] with [objective NA, immersion]. [Channel]
excited at [nm], collected through [filter manufacturer + catalogue]. 
[Detector] gain [N], offset [N], [N] frame averages. Pinhole [N] AU
(confocal). Z-step [N] um, [N] slices. Sequential acquisition.

Images processed in Fiji/ImageJ v[X.Y.Z]. Display adjustments
(brightness/contrast) applied linearly and uniformly to the entire image
using setMinAndMax(). All images within a comparison group displayed with
identical settings (channel 1: min=X, max=Y; channel 2: min=X, max=Y).
[Background subtracted: rolling ball radius=[N], default settings.]
[Filter: Gaussian sigma=[N] for visualization only — measurements on raw
data.] [Pseudocolor: mpl-viridis LUT with calibration bar.] No non-linear
adjustments were applied. Multi-channel composites use [Magenta + Green]
for [Marker1 + Marker2]. Individual channels shown as grayscale panels in
[Supplementary Figure X]. Scale bars: [N] um (panel labels [a, b, c] /
[A, B, C]).

Statistical analysis: [test] performed in [R/Python/Prism vN]. Plots:
[ggplot2/seaborn/Prism]. Each dot represents one [cell/animal]; bars
show mean +/- [SD/SEM/95% CI]; n = [N independent biological replicates,
each with N >= [M] cells per condition]. P-values reported exactly;
significance computed on biological-replicate means.
```

---

## §12 Batch Figure Generation

```javascript
input = "/path/to/raw/"; output = "/path/to/figures/";
list = getFileList(input);
ch1_min = 50;  ch1_max = 2000;   // SAME for all images
ch2_min = 100; ch2_max = 3500;

for (i = 0; i < list.length; i++) {
    if (!endsWith(list[i], ".tif")) continue;
    open(input + list[i]);
    run("Duplicate...", "title=fig duplicate");
    run("Split Channels");
    selectWindow("C1-fig");
    run("Green");   setMinAndMax(ch1_min, ch1_max); run("RGB Color"); rename("ch1");
    selectWindow("C2-fig");
    run("Magenta"); setMinAndMax(ch2_min, ch2_max); run("RGB Color"); rename("ch2");
    run("Images to Stack", "name=panel use");
    run("Make Montage...", "columns=2 rows=1 scale=1 border=2");
    run("Scale Bar...",
        "width=50 height=5 thickness=4 font=18 color=White "
      + "background=None location=[Lower Right] bold overlay");
    run("Flatten");
    setVoxelSize(1/600, 1/600, 1, "inch");                 // tag 600 DPI
    saveAs("Tiff", output + replace(list[i], ".tif", "_figure.tif"));
    run("Close All");
}
```

---

## §13 Quantification Panels

### §13.1 Plot Type Selection

| Chart Type | When to Use | Avoid When |
|-----------|-------------|------------|
| **Dot plot / strip chart** | n < 20 per group | Very large n |
| **Box plot** (median + IQR) | n 10-50 per group | n < 5 |
| **Violin plot** | Large n, distribution shape matters | Small n |
| **Raincloud plot** (half-violin + box + dots) | n 20-200 — 2024-2026 default | Very small n |
| **Beeswarm** | n 20-100 — exact density matters | n very large (use violin) |
| **Bar + individual points** | Field convention; fine if dots overlaid | Mean ± SEM with no points (avoid) |
| **Scatter + regression** | Correlations | Causal claims |
| **Heatmap (categorical)** | Multi-condition × multi-readout | Single comparison |
| **Bar chart alone** | **AVOID** | Hides distribution — flagged at most journals |
| **Mean ± SEM bar (alone)** | **AVOID** | Misleads about variability |

The 2024-2026 default for n=20-200 is the **raincloud plot** (Allen et al. 2021,
*Wellcome Open Res*) — `ggrain` (R) and `ptitprince` (Python). Shows distribution
shape, summary statistics, and raw data simultaneously.

### §13.2 N Disclosure and SuperPlots

**State biological vs technical replicates separately.** Pseudoreplication
(e.g., treating 500 cells from one mouse as n=500) is a leading reason for
rejection at *Nature Methods*, *eLife*, *J Cell Biol*, and *PLOS Biology*.

**SuperPlots** (Lord, Velle, Mullins & Fritz-Laylin 2020, *J Cell Biol*
219:e202001064) are the de facto standard:

- Each dot = one cell, **colour-coded by biological replicate** (one colour per
  mouse / dish / differentiation).
- Larger symbol = mean per biological replicate.
- Statistics performed on **biological-replicate means**, not on pooled cells.

Standard legend phrasing:

> "Each dot represents one cell, colour-coded by biological replicate
> (N=4 mice). Larger symbols show the mean per replicate. Bars show
> mean ± SD computed across the 4 biological means. Two-tailed
> Welch's t-test on biological means; exact P reported."

### §13.3 Error Bars — SD, SEM, CI, IQR

| Metric | What it describes | When to use |
|--------|-------------------|-------------|
| **SD** | Spread of the data | Communicating biological variability |
| **SEM** | Uncertainty about the mean (= SD/√n) | Rare; misleading because shrinks with n |
| **95% CI** | Range that contains the true mean with 95% confidence | Inference about means |
| **IQR** | Middle 50% of data (Q1-Q3) | Non-Gaussian data (most cell-level intensity distributions) |

*Nature Methods* (Krzywinski & Altman 2013, *Nat Methods* 10:921 "Error bars")
explicitly discourages SEM as the default. Always state which is shown in the
legend: *"Bars represent mean ± SD"* — without this the figure is unreadable.

### §13.4 Significance Notation

Stars are still acceptable but **exact P-values are now preferred** (Nature
2024+). Report P to two significant figures; only fall back to P<0.0001 when
the test cannot resolve further. Never report P=0; use P<10⁻⁴ or the actual
lower bound.

| Notation | P |
|----------|---|
| ns | P ≥ 0.05 |
| * | P < 0.05 |
| ** | P < 0.01 |
| *** | P < 0.001 |
| **** | P < 0.0001 |

For multiple comparisons: state the correction (Bonferroni, Holm-Šidák,
Benjamini-Hochberg FDR for >10 comparisons) and report adjusted P.

### §13.5 Plot Tools and ImageJ Output

| Tool | Notes |
|------|-------|
| **GraphPad Prism 10** | Biomedical gold standard; PDF/EPS export preserves vectors; ~$300/year academic |
| **R + ggplot2 + ggpubr** | Free, fully reproducible; `ggpubr::stat_compare_means()` adds tests inline |
| **Python + seaborn / matplotlib** | Free, reproducible; raincloud via `ptitprince` |
| **Origin / JMP / SPSS** | Usable but less common |
| **Excel** | Discouraged — no exact P, raster export, no perceptual color control |

```javascript
// Export ImageJ Results to CSV for plotting downstream
saveAs("Results", "/path/AI_Exports/results.csv");
```

The built-in `Plot.create()` is fine for QC but never for publication figures
— no error bars, raster export, no perceptual color control.

Best practice: deposit plotting scripts on Zenodo/GitHub and cite the DOI in
Methods so plots are reproducible.

---

## §14 Tips and Gotchas

### Image Integrity Checklist

- [ ] Measurements on raw data (before display adjustments)
- [ ] Linear display adjustments only (`setMinAndMax`, no gamma without
      disclosure)
- [ ] Same display range for all compared panels
- [ ] No selective enhancement
- [ ] Scale bars on all micrographs (calibrated, metric units)
- [ ] Colorblind-safe LUTs (Magenta/Green, never Red/Green)
- [ ] CVD-simulation pass run on final figure
- [ ] Font readable at print size (5-7 pt minimum at final scale)
- [ ] TIFF or PDF (never JPEG for microscopy)
- [ ] 300+ DPI photographic, 600+ DPI line art
- [ ] All processing documented for Methods
- [ ] No histogram clipping (verify with `getHistogram` after
      `setMinAndMax`)
- [ ] Generative AI not used in image content (or disclosed if
      methodologically required)

### Common Mistakes

| Mistake | Fix |
|---------|-----|
| `run("Save", "tiff resolution=600")` doesn't set DPI | Use `setVoxelSize(1/600, 1/600, 1, "inch")` (§11.1) |
| `saveAs("Tiff")` makes huge files | Use Bio-Formats Exporter with `compression=LZW` (§11.4) |
| JPEG for micrographs | Always TIFF or PDF |
| Different display ranges for control vs treated | Store min/max, apply identically |
| Overlays missing in saved file | `run("Flatten")` before raster save |
| Scale bar on uncalibrated image | Check `getPixelSize()` first |
| Font too small at print size | Compute pixels from `pt × DPI / 72` |
| RGB conversion before merge | Merge LUT channels first, then RGB/Flatten |
| `Enhance Contrast` with `normalize` | Use `setMinAndMax()` instead |
| Montage border color unexpected | Set foreground color + `use` flag |
| Serif fonts for labels | Always sans-serif (Arial/Helvetica) |
| Merge-only without individual channels | Most journals require individual channel panels |
| Lowercase panel labels for Cell/Science | Cell, Science, eLife, PLOS use uppercase A/B/C |
| Uppercase panel labels for Nature | Nature uses lowercase a/b/c |
| Mean ± SEM bar with no points | Show individual points; prefer SD or 95% CI |
| Pooled cells as n | Use SuperPlots; statistics on biological-replicate means |
| Stars only, no exact P | Report exact P-values where the test resolves them |

### ImageJ Quirks

- `Flatten` converts to RGB permanently.
- Scale bars without `overlay` flag burn into pixels immediately.
- `Make Montage` requires a stack; use `Images to Stack` first.
- `Combine` works with exactly 2 images at a time.
- Font size in `setFont` / `drawString` is **pixels**, not points.
- `Overlay.drawString` y coordinate is text **baseline**, not top.
- `setOption("DPIAndUnit", ...)` does **not** exist (forum-folklore mistake).
  Resolution always comes from `setVoxelSize` / `Properties` / `Set Scale`.
- ImageJ does not embed ICC profiles. Post-process with ImageMagick if needed.
- `saveAs("PNG", ...)` preserves 16-bit grayscale (current 1.54+); older
  guidance ("8-bit only") is outdated.

---

## §15 Agent Integration

```bash
python ij.py state              # check state + calibration
python ij.py metadata           # verify calibration
python ij.py histogram          # check for saturation
python ij.py macro '...'        # run figure creation commands
python ij.py capture step_name  # capture + inspect each step
```

**Agent constraints:**

- Never use `Enhance Contrast` with `normalize` (§16, §11.6).
- Always identical display settings across compared panels.
- Always capture and inspect images visually after each step.
- Check calibration with `getPixelSize` before adding scale bars.
- Save outputs to `AI_Exports/` next to the source image (project rule).
- Set `setVoxelSize(1/600, 1/600, 1, "inch")` immediately before `saveAs("Tiff", ...)`
  to embed correct DPI metadata.
- Run Color Oracle / `Simulate Color Blindness` mental check on every figure
  with multi-channel composites.

---

## §16 Image Integrity Boundaries

(Cross-reference: image-integrity-reference.md §3 for the full allowed/forbidden
table; §4 for detection signatures the journals use.)

### Forbidden — will trigger rejection

| Operation | Why |
|-----------|-----|
| Selective region enhancement | Misrepresents data |
| Clone stamp / healing / erasing | Creates or removes data elements |
| Splicing without visible demarcation | Implies false continuity |
| `Enhance Contrast` with `normalize` on quantitative data | Permanently destroys pixel values |
| JPEG for microscopy data | Lossy compression alters pixel values; 8×8 block artifacts and comb-pattern in histogram are forensically detectable |
| AI-generated image content (without explicit editor permission) | Forbidden at Nature, Cell, Science (§18) |
| Non-linear adjustments without disclosure | Gamma, log, histogram equalization |
| Different processing between compared conditions | Invalidates comparison |
| Creative deconvolution that adds signal not in raw data | Creates artifactual structure |

### Allowed (with disclosure)

- Linear B/C uniform to entire image and to all controls (`setMinAndMax`).
- Cropping that preserves context.
- Pseudocoloring with documented LUT and calibration bar where intensity
  matters.
- Channel merging with individual channels shown.
- Compositing/montage with **visible lines** between separate acquisitions —
  never imply continuity.
- Background subtraction, uniform method, parameters disclosed.
- Gaussian/median filter, sigma/radius disclosed, applied uniformly.
- Deconvolution with documented PSF and algorithm.
- Z-projection (state MIP/average/sum and slice range).

### Gel/Blot Splice Rules

Lanes from different gels **must be marked with a thin black line** between
non-contiguous regions, with a note in the legend. Vertical splices: same
rule. Cropping to a region of a gel: state in legend ("Cropped from a single
blot; full blot in Source Data"). Source data must be deposited or provided
on request.

### Forensic Tools the Journals Run

| Tool | Used by | Detects |
|------|---------|---------|
| **Proofig AI** | Science family (since 2024), Wiley, Springer Nature | Duplication, splicing, AI-generated content |
| **Imagetwin** | ASM Journals (since 2023), MSK pilot 2024 | Cross-paper duplication |
| **ORI Forensic Droplets** | JCB, several others | Erasures, edge anomalies |

The **JCB test** (Rossner & Yamada 2004): "Is the resulting image still an
accurate representation of the original data?" — apply this to every panel.

See image-integrity-reference.md for the complete integrity-verification macro
(§10) and pre-submission self-check (§9).

---

## §17 Figure Assembly Outside ImageJ

ImageJ exports panels; final assembly happens in dedicated layout software.

### §17.1 Adobe Illustrator (gold standard)

Adobe Illustrator 2025 (v29.x). Key features for science figures:

- **File > Place** with **Link** checked (NOT Embed) for raster panels —
  keeps `.ai` file small, lets you re-export panels from ImageJ without
  re-placing. Embed only for final submission.
- **Window > Layers** — one layer per panel group (panels, labels, scale bars,
  annotations). Schematics on a separate layer above rasters.
- **View > Smart Guides** (Ctrl+U) and **Snap to Pixel** — essential for
  1-pixel alignment. Set **Preferences > Units > General: Millimeters**, but
  keep stroke in **points**.
- **600 DPI raster + vector text**: place TIFF at 100% (1:1), check
  **Window > Document Info > Linked Images** to confirm effective PPI ≥ 600.
  Text remains vector regardless.
- **Export**: **File > Save As > PDF (Press Quality)** with "Preserve
  Illustrator Editing Capabilities" off for submission. Embed all fonts; do
  not downsample images; compression = ZIP (lossless). EPS is legacy — only
  if the journal portal demands it. PDF/X-1a or PDF/X-4 is now the standard
  at Nature, Cell, eLife, PLOS.

### §17.2 Affinity Designer (no-subscription alternative) and Inkscape (open source)

**Affinity Designer 2.5** — one-time licence (~£70), no subscription. Strengths:
pixel-precise, fast, native PDF/SVG/EPS/TIFF export, **Place** linked or
embedded, layer panel matches Illustrator's. Weaknesses: `.afdesign` is
proprietary and Illustrator can't open it — collaborators must export to PDF.
CMYK soft-proofing is weaker than Illustrator. Good solo; awkward in
collaborative pipelines where reviewers/editors want `.ai` or `.eps`.

**Inkscape 1.3+ (1.4 stable as of 2025)** — open-source, SVG-native, capable
for full figures. Quirks:

- **Text rendering on Windows**: Pango/HarfBuzz handles fonts, but installed
  `.ttf` fonts sometimes substitute on other machines. Convert text to paths
  (**Path > Object to Path**) only on the FINAL export copy — keep an
  editable master.
- **EPS export** requires Ghostscript installed separately and is lossy for
  filters/transparency. Use **PDF 1.5** instead.
- **Raster panels at 600 DPI**: import as Link, set **File > Document
  Properties > Resize to content**, and on export use **File > Export > PDF,
  Rasterize filter effects: 600 dpi**. Don't let Inkscape re-rasterize the
  whole document.

### §17.3 Figma

Browser/desktop, real-time collaboration. **Avoid for final figure submission**:
no true CMYK, DPI control is via export `@nx` multipliers (not absolute),
embedded rasters can be re-compressed. Useful for early figure brainstorming
with co-authors, then rebuild final in Illustrator/Inkscape.

### §17.4 PowerPoint — Do Not Use

Specifically:

- Default canvas is 96 DPI; placing a 600 DPI TIFF auto-downsamples on export
  to PDF unless **File > Options > Advanced > Image Size and Quality > Do not
  compress + High fidelity** is set. Even then PowerPoint's PDF engine
  resamples to 220 DPI at most.
- Fonts substitute silently when the file moves between Mac/Windows/Office
  versions.
- sRGB conversion shifts colour balance on PDF export.
- Vector content (SVG icons, plots) gets rasterised at low DPI on export.
- **Some journals (Nature, EMBO) explicitly reject `.pptx`**.

### §17.5 The ImageJ → Illustrator/Inkscape Workflow

1. **Decide final printed size first.** Nature single column = 89 mm; double
   = 183 mm. A 4-panel row at 89 mm = ~22 mm per panel; at 600 DPI that's
   **520 px wide per panel**.
2. **Image > Scale** each panel to its final pixel size in ImageJ. Do not
   rely on the assembly app to "auto-fit" — that resamples and softens edges.
3. **Add scale bar in ImageJ** via `Scale Bar...` with **overlay** flag (§6),
   then `saveAs("Tiff", ...)` after `Flatten` only on the export copy.
4. **Export panels**: `saveAs("Tiff", path)` (LZW via Bio-Formats Exporter
   if size matters); after `setVoxelSize(1/600, 1/600, 1, "inch")` to embed
   600 DPI metadata. Convert to RGB first (`Image > Type > 8-bit / RGB Color`)
   for figure-final 8-bit RGB.
5. **Plots**: export as **SVG** or **PDF** (vectors — infinite resolution).
6. **In Illustrator/Inkscape**: `File > Place > Link`, position at 100%. Use
   **Align** to snap panels to a grid.
7. **Scale bars**: prefer the ImageJ overlay (calibration-correct). If
   adding text annotations in Illustrator (e.g. "10 μm"), use Arial/Helvetica
   6-7 pt, white with a 0.25 pt black stroke if on a fluorescence image.
8. **Panel labels** (a/A, b/B, c/C) in Illustrator: Arial Bold 8 pt, top-left
   corner, 2 mm inset.
9. **Column-width guides**: **View > Rulers > New Guide** at 0 and 89 mm
   (or 183 mm). Lock the layer.
10. Keep file layered until the final step. Flatten only on a `_submission.pdf`
    copy.

### §17.6 Multi-Channel Composite Presentation

- **Default for journals**: split single channels in grayscale + colour merge.
  Six-panel row: DAPI / Marker1 / Marker2 / Marker3 / Merge / Inset is
  standard.
- **Merge-only** acceptable for very well-known pairs (DAPI + one marker)
  and supplementary figures. Most reviewers ask for splits.
- **Grayscale singles + colour merge** (Nature/Cell house style): each
  channel in grayscale (better dynamic range perception than coloured singles),
  then a colour merge using accessible LUTs — magenta/green or
  magenta/yellow/cyan, never red/green.
- **Inset zooms**: 2-3× zoom of a region of interest, drawn as a boxed ROI
  in the parent panel with a matching coloured border on the inset. Place
  insets adjacent (right side or bottom). Always include a separate scale bar
  for the inset.

### §17.7 Typography Conventions

- **Family**: Arial or Helvetica (Nature, Science, Cell). Myriad Pro acceptable
  at Science. Avoid Calibri, Times, Cambria, decorative fonts.
- **Panel labels**: Nature lowercase bold (**a**, **b**, **c**); Cell, Science,
  eLife, PLOS uppercase bold (**A**, **B**, **C**).
- **Sizes**: panel labels 8-10 pt; axis tick labels and legends 6-8 pt; body
  text in figure 5-7 pt minimum. Nature: nothing below **5 pt** at final
  printed size. eLife: **7 pt** minimum.
- **Italics**: variables (*n*, *t*, *P*, *r*), gene names (*Bmal1*, *Per2*),
  species binomials. **Upright**: units (μm, ms, Hz), protein names (BMAL1,
  PER2).
- Single space after period. Use proper minus sign (−, U+2212) in axis values,
  not the hyphen-minus.

---

## §18 AI-Disclosure Norms (2025/2026)

| Publisher | Generative AI in figures? | Disclosure location |
|-----------|---------------------------|---------------------|
| **Nature** | **Forbidden** (photos/video/illustrations created wholly or partly with generative AI), except in articles specifically about AI. LLM text use must be disclosed in **Methods** (or Acknowledgments if no Methods). LLMs cannot be authors. | Methods |
| **Cell Press** | **Forbidden** for image creation/alteration and graphical abstracts. Exception: AI as part of research methodology (must describe model, version, manufacturer in Methods). Cover art may be allowed with prior editor permission. | Methods + declaration on submission |
| **Science (AAAS)** | **Forbidden** without explicit editor permission; exception for AI/ML-focused papers. **Full prompt + tool + version must be disclosed.** | Cover letter AND Acknowledgments |
| **eLife** | Generally permits AI-assisted writing with disclosure; figure-specific generative AI rules align with general industry norm (disclose in Methods). | Methods |
| **PLOS** | Disclose use of generative AI in Methods; AI cannot be author. | Methods |

**Watermarks** are not currently mandated by any major publisher (as of mid-
2026). Discussion ongoing — Proofig AI now offers AI-generated microscopy
detection (~98% accuracy in their own benchmarks).

URLs:
- Nature AI policy: https://www.nature.com/nature-portfolio/editorial-policies/ai
- Cell Press: https://www.cell.com/structure/information-for-authors/journal-policies
- Science: https://www.science.org/content/blog-post/change-policy-use-generative-ai-and-large-language-models
- Science editorial policies: https://www.science.org/content/page/science-journals-editorial-policies

**Bottom line for figures:**

1. Do not use generative AI to create, denoise creatively, in-paint, or
   "enhance" microscopy images intended for publication.
2. AI-based image analysis (StarDist, Cellpose, Weka, deep-learning
   denoisers like Noise2Noise, deconvolution networks) IS allowed and is
   considered methodology — disclose model, version, training data,
   hyperparameters in Methods.
3. AI-generated schematics or illustrations: forbidden at Nature/Cell;
   needs explicit editor permission at Science.
4. If unsure, ask the editor before submission.

---

## §19 Reproducibility and Data Deposition

### §19.1 Image Repositories

| Repository | URL | Best for | Capacity |
|------------|-----|----------|----------|
| **Image Data Resource (IDR)** | https://idr.openmicroscopy.org | Curated, study-level metadata, large collections | TB-scale; ~3 month review |
| **BioImage Archive** | https://www.ebi.ac.uk/bioimage-archive/ | Easy deposition, less curation | TBs by arrangement |
| **BioStudies** | https://www.ebi.ac.uk/biostudies/ | General supplementary deposition | TBs |
| **Zenodo** | https://zenodo.org | Code + small datasets + model weights | 50 GB / record (200+ GB on request) |

The BioImage Archive (operated by EMBL-EBI) is increasingly mandated by
**Nature** and **eLife** for image-heavy papers (2024+).

### §19.2 REMBI Metadata Standard

REMBI (Recommended Metadata for Biological Images, Sarkans et al. 2021,
*Nat Methods* 18:1418) — the standard for what to deposit alongside images:

| REMBI field | Includes |
|-------------|----------|
| Study | hypothesis, organism, study design |
| Specimen | preparation, fixation, mounting |
| Image acquisition | microscope, objective, channels, lasers, exposure, pinhole |
| Image data | format, dimensions, calibration |
| Analysis | software, parameters, scripts |

The BioImage Archive submission form maps to REMBI fields directly.

### §19.3 Code and Models

- **GitHub** for analysis scripts (use a tagged release).
- **Zenodo** to mint a DOI for that GitHub release at submission — citable
  and archival.
- **Hugging Face** (https://huggingface.co) for active model distribution
  with a model card; **Zenodo** for archival snapshot tied to the paper's
  DOI.
- Include training data provenance, hyperparameters, and a `requirements.txt`
  or `environment.yml`.

### §19.4 Reproducibility Checklist

- [ ] Raw images deposited (BioImage Archive / IDR / Zenodo) with REMBI
      metadata
- [ ] Analysis scripts on GitHub, tagged release archived on Zenodo (DOI)
- [ ] Model weights deposited (Hugging Face + Zenodo)
- [ ] Software versions in Methods (Fiji v…, Python v…, R v…, every plugin)
- [ ] Acquisition settings in Methods (microscope, objective, lasers, gain,
      exposure, pinhole, z-step, channel order)
- [ ] Display settings in Methods (min/max per channel, LUT, gamma if used)

---

## §20 Pre-Submission Checklist

A single pass before clicking submit:

### Image content
- [ ] Measurements taken on raw data, not display-adjusted
- [ ] Linear adjustments only (`setMinAndMax`); no `Enhance Contrast normalize`
- [ ] Same display range across compared panels
- [ ] No selective enhancement; whole-image only
- [ ] Splices marked with visible lines; gel/blot rules followed
- [ ] No generative AI in image content (or disclosed)

### Visual presentation
- [ ] Scale bar on every micrograph; metric units; calibrated
- [ ] Channel labels by name (not just colours)
- [ ] Individual channels shown alongside merge
- [ ] Colorblind-safe LUTs (Magenta/Green; never Red/Green)
- [ ] CVD simulation pass (Color Oracle / Coblis) on every multi-channel panel
- [ ] Panel labels in correct case for target journal (Nature lowercase;
      others uppercase)
- [ ] Sans-serif font ≥ journal minimum (5 pt Nature/Science; 7 pt eLife)

### Format and size
- [ ] Final file: PDF (preferred) or TIFF (LZW); never JPEG
- [ ] 8-bit RGB final; 16-bit raw retained
- [ ] DPI: 300+ photographic; 600+ line art (effective at print size)
- [ ] Pixel dimensions match target column width × DPI
- [ ] Per-figure file size within journal cap (~10 MB Nature/PLOS)
- [ ] DPI metadata embedded (`setVoxelSize(1/DPI, 1/DPI, 1, "inch")` before
      `saveAs`)
- [ ] Color space: RGB recommended; sRGB tag if web-first journal

### Statistical figures
- [ ] Each dot = one biological / technical unit, declared in legend
- [ ] SuperPlot colour-coding for nested designs
- [ ] Error bar type stated (SD / SEM / 95% CI / IQR)
- [ ] Exact P-values reported where the test resolves them
- [ ] n stated for each group (biological vs technical)
- [ ] Multiple-comparison correction declared

### Methods and reproducibility
- [ ] Microscope, objective, lasers, exposure, gain, pinhole, z-step disclosed
- [ ] Software versions disclosed
- [ ] Plugin parameters disclosed
- [ ] Display range stated per channel
- [ ] Raw data deposited (BioImage Archive / IDR / Zenodo) and DOI cited
- [ ] Analysis scripts on GitHub, Zenodo DOI cited
- [ ] AI use (image analysis, text) disclosed

### Forensic
- [ ] Histogram per panel: smooth, no comb (no JPEG history)
- [ ] No image reused across figures (independent fields per panel)
- [ ] No suspicious clone-pattern or repeated noise
- [ ] Self-screen with `image-integrity-reference.md §10` macro before
      submission

If every box is ticked, the figure should pass Proofig / Imagetwin / ORI
forensic screening and reviewer scrutiny.
