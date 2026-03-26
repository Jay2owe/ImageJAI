# Colour Deconvolution & Histological Scoring Reference

Colour deconvolution, DAB quantification, IHC scoring systems, stain normalisation,
and WSI handling in ImageJ/Fiji.

---

## 1. Quick Start — DAB Area Fraction

```bash
python ij.py macro '
  title = getTitle();
  run("Colour Deconvolution", "vectors=[H DAB]");
  selectWindow(title + "-(Colour_2)");
  setAutoThreshold("Default dark");
  run("Set Measurements...", "area area_fraction limit display redirect=None decimal=3");
  run("Measure");
'
python ij.py results
```

---

## 2. Colour Deconvolution v1

**Spelling:** Plugin uses British "Colour" not "Color".

```java
run("Colour Deconvolution", "vectors=[VECTOR_NAME]");
run("Colour Deconvolution", "vectors=[VECTOR_NAME] hide");
```

### Output Images

Produces three 8-bit greyscale images named `title-(Colour_1)`, `title-(Colour_2)`, `title-(Colour_3)`.

- Dark pixels = high stain concentration, bright = low/none
- Output is transmittance, NOT optical density
- OD conversion: `OD = -log10(pixel_value / 255)`

### Channel Mapping

| Vector Set | Colour_1 | Colour_2 | Colour_3 |
|-----------|----------|----------|----------|
| H&E | Haematoxylin | Eosin | Residual |
| H&E 2 | Haematoxylin | Eosin | Residual |
| H DAB | Haematoxylin | DAB | Residual |
| H&E DAB | Haematoxylin | Eosin | DAB |
| H AEC | Haematoxylin | AEC | Residual |
| FastRed FastBlue DAB | Fast Red | Fast Blue | DAB |
| Methyl Green DAB | Methyl Green | DAB | Residual |
| Azan-Mallory | Azocarmine (red) | Aniline Blue | Residual |
| Masson Trichrome | Ponceau Fuchsin (red) | Methyl Blue | Residual |
| Alcian Blue & H | Alcian Blue | Haematoxylin | Residual |
| H PAS | Haematoxylin | PAS | Residual |

### Selecting Output

```java
title = getTitle();
run("Colour Deconvolution", "vectors=[H DAB]");
selectWindow(title + "-(Colour_2)");  // DAB channel
```

---

## 3. Colour Deconvolution 2 (Landini)

**Install:** Help > Update > Manage Update Sites > "Colour Deconvolution2" > Apply > Restart.

### v1 vs v2

| Feature | v1 | v2 (CD2) |
|---------|-----|----------|
| Output types | 8-bit transmittance only | 8-bit/32-bit transmittance, 32-bit absorbance, RGB |
| Custom vectors | Macro only | Interactive ROI selection + macro |
| Simulated LUTs | No | Yes |
| Macro auto-generation | No | Yes (from ROI vectors) |

### Macro Syntax

```java
// Standard
run("Colour Deconvolution2", "vectors=[H DAB] output=[8bit_Transmittance] hide");

// True optical density output (for quantitative work)
run("Colour Deconvolution2", "vectors=[H DAB] output=[32bit_Absorbance] hide");

// Simulated colour LUTs
run("Colour Deconvolution2", "vectors=[H&E] output=[8bit_Transmittance] simulated hide");

// Custom vectors
run("Colour Deconvolution2", "vectors=[User values] output=[8bit_Transmittance] "
    + "[r1]=0.650 [g1]=0.704 [b1]=0.286 "
    + "[r2]=0.268 [g2]=0.570 [b2]=0.776 "
    + "[r3]=0.0 [g3]=0.0 [b3]=0.0 hide");

// Interactive from ROI
run("Colour Deconvolution2", "vectors=[From ROI]");

// Show matrix in Log + cross-product for third vector
run("Colour Deconvolution2", "vectors=[H DAB] output=[8bit_Transmittance] cross show hide");
```

### Output Types

| Output Type | Format | Use For |
|-------------|--------|---------|
| 8bit_Transmittance | 8-bit | Visual inspection, thresholding |
| 32bit_Transmittance | 32-bit float | Precise calculations |
| 32bit_Absorbance | 32-bit float | Quantitative OD measurements |
| RGB_intensity | RGB | Visualisation only |

### Additional Vectors in CD2

Brilliant Blue, Astra Blue & Fuchsin, NBT/BCIP & Red Counterstain II,
Haematoxylin/DAB/New-Fuchsin, Haematoxylin/HRP-Green/New-Fuchsin, Feulgen & Light Green.

---

## 4. Custom Stain Vector Determination

### Why Custom Vectors

Built-in vectors were determined from specific protocols. Stain vectors vary with
haematoxylin formulation, DAB kit/incubation, microscope/camera, section thickness,
and tissue type. Custom vectors typically improve separation for quantitative work.

### How to Determine

**Ideal:** Single-stained control sections (haematoxylin only, DAB only).
**Practical:** Find regions in double-stained tissue where only one stain is present
(e.g., nucleus in DAB-negative area, strong DAB cytoplasm without nuclear staining).

**Easiest method:** CD2's "From ROI" option — draw ROIs on pure stain regions,
plugin calculates vectors and generates a copy-paste macro. Check Log for results.

### Manual Vector Extraction Macro

```java
// Draw ROIs on pure stain regions, then run this macro.
// Set coordinates for pure stain 1 and pure stain 2 regions:
x1 = 100; y1 = 100; w1 = 30; h1 = 30;  // pure stain 1
x2 = 200; y2 = 200; w2 = 30; h2 = 30;  // pure stain 2

title = getTitle();
run("Duplicate...", "title=temp_rgb");
makeRectangle(x1, y1, w1, h1);
run("Split Channels");

selectWindow("temp_rgb (red)"); makeRectangle(x1, y1, w1, h1); getStatistics(area, r1);
selectWindow("temp_rgb (green)"); makeRectangle(x1, y1, w1, h1); getStatistics(area, g1);
selectWindow("temp_rgb (blue)"); makeRectangle(x1, y1, w1, h1); getStatistics(area, b1);

// Convert to OD and normalise
od_r1 = -log(r1 / 255) / log(10);
od_g1 = -log(g1 / 255) / log(10);
od_b1 = -log(b1 / 255) / log(10);
len1 = sqrt(od_r1*od_r1 + od_g1*od_g1 + od_b1*od_b1);
od_r1 /= len1; od_g1 /= len1; od_b1 /= len1;

close("temp_rgb*");

// Repeat for stain 2 with (x2, y2, w2, h2) coordinates...
// Then use vectors in CD2 "User values" macro syntax
print("Stain 1: [" + d2s(od_r1,6) + ", " + d2s(od_g1,6) + ", " + d2s(od_b1,6) + "]");
```

### Troubleshooting Vectors

| Problem | Solution |
|---------|----------|
| Cannot find pure stain regions | Use tissue edges, or heavily positive regions; try CD2 "From ROI" |
| Colour_3 not white | Vectors not orthogonal enough; check matrix determinant (should approach 1.0) |
| Cross-contamination between channels | Sample from darker/more saturated stain regions; avoid overlap areas |

---

## 5. DAB Quantification Methods

### Method Comparison

| Method | Reliability with DAB | When to Use |
|--------|---------------------|-------------|
| Area fraction (positive/negative) | High | Preferred default method |
| Mean OD | Moderate | Relative comparisons within same batch |
| Integrated OD (IOD) | Moderate | Total stain amount comparisons |
| Reciprocal intensity (255-I) | Low | Rough screening only |

**DAB caveat:** DAB is a precipitate that scatters light and shifts spectrum with
concentration, violating Beer-Lambert law. OD measurements are semi-quantitative.
Area fraction is more reliable because it avoids intensity quantification.

### Area Fraction

```java
title = getTitle();
run("Colour Deconvolution", "vectors=[H DAB]");
selectWindow(title + "-(Colour_2)");
rename("DAB");
setAutoThreshold("Default dark");  // consider: Triangle, Huang, Otsu
run("Set Measurements...", "area area_fraction limit display redirect=None decimal=3");
run("Measure");
```

### Optical Density (using CD2 32-bit absorbance)

```java
title = getTitle();
run("Colour Deconvolution2", "vectors=[H DAB] output=[32bit_Absorbance] hide");
selectWindow(title + "-(Colour_2)");
rename("DAB_OD");
// IntDen measurement = Integrated Optical Density
run("Set Measurements...", "mean integrated area limit display redirect=None decimal=6");
run("Measure");
```

### Converting v1 8-bit Transmittance to OD

```java
function toOpticalDensity() {
    run("32-bit");
    run("Divide...", "value=255.0");
    run("Max...", "value=0.00001");  // avoid log(0)
    run("Log");  // natural log
    run("Divide...", "value=-2.302585");  // -ln(10)
    run("Multiply...", "value=-1");
}
```

---

## 6. IHC Scoring Systems

### 6.1 H-Score (Histoscore)

```
H-Score = 1 x (%weak) + 2 x (%moderate) + 3 x (%strong)
Range: 0-300
```

**Intensity thresholds must be calibrated per study.** Starting points on inverted
DAB transmittance (0=no stain, 255=max stain):

| Category | Starting Range | Calibration Guidance |
|----------|---------------|---------------------|
| Background | 0-~10 | Adjust based on glass/empty areas |
| Negative | ~10-75 | Compare with negative control tissue |
| Weak (1+) | ~76-135 | Faint brown, barely visible by eye |
| Moderate (2+) | ~136-195 | Clearly brown |
| Strong (3+) | ~196-255 | Dark brown to black-brown |

```java
title = getTitle();
run("Colour Deconvolution", "vectors=[H DAB]");
selectWindow(title + "-(Colour_2)");
rename("DAB_inv");
run("Invert");  // 0=no stain, 255=max stain

// Thresholds — CALIBRATE for your staining protocol
bgCutoff = 10; negMax = 75; weakMax = 135; modMax = 195;

getHistogram(values, counts, 256);
negCount = 0; weakCount = 0; modCount = 0; strongCount = 0; totalCount = 0;
for (i = bgCutoff; i < 256; i++) {
    totalCount += counts[i];
    if (i <= negMax) negCount += counts[i];
    else if (i <= weakMax) weakCount += counts[i];
    else if (i <= modMax) modCount += counts[i];
    else strongCount += counts[i];
}
pctWeak = (weakCount / totalCount) * 100;
pctMod = (modCount / totalCount) * 100;
pctStrong = (strongCount / totalCount) * 100;
hScore = 1 * pctWeak + 2 * pctMod + 3 * pctStrong;
print("H-Score: " + d2s(hScore, 1) + " / 300");
```

**Cell-based H-Score** (more accurate): segment nuclei from haematoxylin channel,
measure mean DAB intensity per nucleus, classify each cell. Use
`run("Analyze Particles...", "size=20-500 circularity=0.3-1.00 display");`
with `redirect=DAB_inv`.

### 6.2 Allred Score

Used primarily for ER/PR in breast cancer. Range: 0-8.

```
Allred = Proportion Score (PS) + Intensity Score (IS)
```

| PS | % Positive | IS | Staining |
|----|-----------|-----|----------|
| 0 | None | 0 | None |
| 1 | <1% | 1 | Weak |
| 2 | 1-10% | 2 | Moderate |
| 3 | 11-33% | 3 | Strong |
| 4 | 34-66% | | |
| 5 | >66% | | |

**Interpretation:** 0 or 2 = Negative; 3-8 = Positive.

### 6.3 Ki67 Index

```
Ki67 Index (%) = (Ki67-positive nuclei / total tumour nuclei) x 100
```

**Two approaches:**

| Approach | Pros | Cons |
|----------|------|------|
| Colour deconvolution + threshold + watershed | Simple, no plugins needed | Less accurate for touching nuclei |
| StarDist + intensity classification | Better segmentation, more accurate counts | Requires StarDist plugin |

**StarDist approach (recommended):**
1. Colour deconvolution with H DAB
2. Invert haematoxylin channel, run StarDist to detect all nuclei
3. Measure mean DAB intensity per nucleus (redirect measurement)
4. Classify: intensity > threshold = positive. **Threshold must be calibrated per study.**

**Hot-spot method:** Some protocols require scoring in the highest-proliferation region.
Tile the image, score each field, report the maximum (or average of top 3).

### 6.4 HER2 Scoring (0 / 1+ / 2+ / 3+)

| Score | Pattern | Interpretation |
|-------|---------|---------------|
| 0 | No staining or faint incomplete membrane in <=10% | Negative |
| 1+ | Faint incomplete membrane in >10% | Negative |
| 2+ | Weak-moderate complete membrane in >10%, or strong complete in <=10% | Equivocal (needs FISH) |
| 3+ | Strong complete circumferential membrane in >10% | Positive |

**Automation is difficult** — requires membrane vs cytoplasmic distinction, circumferential
pattern recognition. Consider QuPath, HALO, or deep learning for HER2. Simple
thresholding is insufficient for clinical scoring.

### 6.5 PD-L1 Scoring

**TPS:** `(PD-L1+ tumour cells / total tumour cells) x 100`
**CPS:** `(PD-L1+ tumour cells + PD-L1+ immune cells) / total tumour cells x 100`

Clinical thresholds vary by cancer type and antibody clone. Automated PD-L1 scoring
should be validated against pathologist assessment.

---

## 7. IHC Profiler Plugin

**Install:** Download from https://sourceforge.net/projects/ihcprofiler/ > Plugins > Install.

Automates pixel-based IHC scoring using colour deconvolution + histogram classification.

### Intensity Zones (on DAB transmittance)

| Zone | Intensity Range | Score |
|------|----------------|-------|
| High Positive (3+) | 0-60 | 4 |
| Positive (2+) | 61-120 | 3 |
| Low Positive (1+) | 121-180 | 2 |
| Negative (0) | 181-235 | 1 |

Pixels 236-255 excluded as background. Overall score assigned if >=66% in one zone,
otherwise weighted average.

```java
run("IHC Profiler");  // interactive dialog: select stain type and location
```

### Limitations and When to Use

| Scenario | Use IHC Profiler? | Alternative |
|----------|-------------------|-------------|
| Quick screening of many images | Yes | -- |
| Publication-quality quantification | No | Custom H-score macro |
| Nuclear staining (Ki67, ER) | Maybe | StarDist + intensity |
| Membrane staining (HER2) | No | Deep learning / QuPath |
| Non-DAB chromogens | No | Custom colour deconvolution |

Pixel-based (not cell-based), fixed thresholds, ~88.6% agreement with pathologists.

---

## 8. Stain Normalisation

### When to Normalise

**Do:** Comparing images from different staining batches, training ML classifiers on
multi-batch data, images with visible colour variation between slides.

**Don't:** Same-batch images with consistent appearance, area-fraction-only measurements,
relative comparisons within single images, or if normalisation introduces artifacts.

### Method Comparison

| Method | Speed | Quality | Available In |
|--------|-------|---------|-------------|
| Reinhard | Fast | Good for simple tissue | Python (staintools) |
| Macenko | Medium | Good, stain-aware | Python (staintools) |
| Vahadane | Slow | Best structure preservation | Python (staintools) |

None are natively available in ImageJ. Use Python before importing to ImageJ:

```python
import staintools
target = staintools.read_image('/path/to/reference.tif')
target = staintools.LuminosityStandardizer.standardize(target)
normaliser = staintools.StainNormalizer(method='vahadane')
normaliser.fit(target)
source = staintools.read_image('/path/to/source.tif')
source = staintools.LuminosityStandardizer.standardize(source)
normalised = normaliser.transform(source)
from PIL import Image
Image.fromarray(normalised).save('/path/to/normalised.tif')
```

---

## 9. Whole Slide Image (WSI) Handling

### Common Formats

.svs (Aperio/Leica), .ndpi (Hamamatsu), .mrxs (3DHISTECH), .scn (Leica),
.bif (Ventana/Roche), .vsi (Olympus), .qptiff (PerkinElmer).

### Bio-Formats Import

```java
// Open at specific pyramid level (lower resolution)
run("Bio-Formats Importer", "open=/path/to/slide.svs color_mode=Default view=Hyperstack series_3");

// Open a tile at full resolution
run("Bio-Formats Importer", "open=/path/to/slide.svs color_mode=Default "
    + "crop x_coordinate_1=10000 y_coordinate_1=15000 width_1=4096 height_1=4096");
```

ImageJ cannot load full-resolution WSIs (Java array limit ~46k x 46k pixels).
Use tiled processing: open thumbnail to identify tissue, then process tiles at
full resolution, aggregate results.

### Memory Management

```java
// Between tiles:
close("*");
run("Collect Garbage");
```

**Consider QuPath** for large-scale WSI analysis — native tile-based processing,
unlimited image sizes, built-in cell detection, StarDist support, and Groovy scripting.

---

## 10. Agent Workflows

### DAB Area Fraction

```bash
python ij.py state
python ij.py macro 'open("/path/to/image.tif");'
python ij.py capture original

python ij.py macro '
  title = getTitle();
  run("Colour Deconvolution", "vectors=[H DAB]");
  selectWindow(title + "-(Colour_2)");
  rename("DAB");
'
python ij.py capture dab_channel
python ij.py histogram
python ij.py explore Default Triangle Huang Otsu Li

python ij.py macro '
  selectWindow("DAB");
  setAutoThreshold("Triangle dark");
  run("Set Measurements...", "area area_fraction limit display redirect=None decimal=3");
  run("Measure");
'
python ij.py results
```

### Batch Processing

```java
inputDir = "/path/to/ihc_images/";
outputFile = "/path/to/results.csv";
File.saveString("Filename,DAB_AreaFraction,DAB_MeanOD\n", outputFile);

list = getFileList(inputDir);
for (i = 0; i < list.length; i++) {
    if (!endsWith(list[i], ".tif") && !endsWith(list[i], ".jpg")) continue;
    open(inputDir + list[i]);
    title = getTitle();
    run("Colour Deconvolution", "vectors=[H DAB]");
    selectWindow(title + "-(Colour_2)");
    setAutoThreshold("Triangle dark");
    run("Set Measurements...", "area area_fraction mean limit display redirect=None decimal=4");
    run("Measure");
    areaFrac = getResult("%Area", nResults - 1);
    meanIntensity = getResult("Mean", nResults - 1);
    meanOD = -log(meanIntensity / 255) / log(10);
    File.append(list[i] + "," + d2s(areaFrac, 2) + "," + d2s(meanOD, 4) + "\n", outputFile);
    close("*");
    run("Collect Garbage");
}
```

### Fibrosis (Masson Trichrome)

```bash
python ij.py macro '
  title = getTitle();
  run("Colour Deconvolution", "vectors=[Masson Trichrome]");
  selectWindow(title + "-(Colour_2)");  // blue = collagen
  rename("collagen");
  setAutoThreshold("Triangle dark");
  run("Set Measurements...", "area area_fraction limit display redirect=None decimal=3");
  run("Measure");
'
# Fibrosis % = blue area / (blue + red area) x 100
```

---

## 11. Common Problems and Solutions

| Problem | Solution |
|---------|----------|
| **Uneven staining** | `run("Subtract Background...", "rolling=100 light");` before deconvolution, or use local thresholding: `run("Auto Local Threshold", "method=Bernsen radius=50 ...");` |
| **Necrotic background** | Use haematoxylin channel to mask viable tissue (areas with intact nuclei); exclude low-nuclei-density regions |
| **Tissue folds** | Folds are dark in ALL channels; exclude pixels dark in both H and DAB channels simultaneously |
| **Variable counterstain** | Custom vectors per batch, or stain normalisation before analysis |
| **Brown pigment mimicking DAB** | Check residual channel (Colour_3) — pigment appears dark there too; use as exclusion mask |
| **Saturation** | Check: if >5% pixels at value 0, staining may be too heavy for quantification |
| **Wrong vectors** | Capture all 3 channels; Colour_3 should be mostly white. If it shows tissue structure, try custom vectors |

### Brown Pigment Identification

| Pigment | Location | Distinguish By |
|---------|----------|---------------|
| Melanin | Melanocytes | Bleach with H2O2; dark in all deconv channels |
| Haemosiderin | Macrophages, haemorrhage | Perl's iron stain |
| Lipofuscin | Neurons, hepatocytes | Autofluorescence; Sudan Black pretreatment |
| Formalin pigment | Near blood vessels | Birefringent; treat with alcoholic picric acid |

---

## 12. Best Practices

### Threshold Selection

- Set threshold ONCE using representative images (include controls), apply to ALL images
- Do NOT adjust per image (introduces bias)
- Document method and any manual adjustments

| Method | Typically Suited For |
|--------|---------------------|
| Default (IsoData) | Moderate staining, bimodal distribution |
| Triangle | Skewed distributions, faint staining |
| Otsu | Clear positive/negative separation |
| Huang | Fuzzy boundaries |
| Li | Low contrast |

### Common Mistakes

1. **JPEG images** — compression destroys colour information. Use TIFF/lossless.
2. **Adjusting brightness/contrast before deconvolution** — invalidates OD calculation.
3. **Enhance Contrast / Normalize** — permanently alters pixel values.
4. **Comparing across batches without normalisation** — batch effects can exceed biological effects.
5. **Cherry-picking fields** — use systematic random sampling.
6. **Ignoring Colour_3** — if residual shows tissue structure, vectors are suboptimal.
7. **Measuring in RGB space** — always deconvolve first to isolate stain components.
8. **Forgetting tissue mask** — background/lumen must be excluded or area fraction is underestimated.

### Reporting Checklist

Report: antibody details (clone, supplier, dilution), detection system, counterstain,
antigen retrieval, image acquisition settings, colour deconvolution vectors used,
threshold method, measurements performed, sampling strategy (fields/section, sections/specimen,
hot-spot vs random), statistical analysis, representative images of each scoring category.

### Quality Control

Verify before analysis: image in focus, no folds/bubbles/debris, white background,
uniform staining, controls behave as expected, not over/under-exposed, TIFF format.

### Inter-Observer Agreement

For semi-automated scoring: >= 2 independent observers on a subset, report Cohen's kappa
or ICC. Kappa > 0.80 = excellent, 0.60-0.80 = good, < 0.60 = revise criteria.

---

## 13. Decision Tree — Choosing Quantification Method

```
What type of IHC quantification?
|
+-- Simple positive/negative --> Area Fraction (threshold DAB, measure %Area)
|
+-- Intensity matters (weak vs strong)
|   +-- Pixel-based (faster) --> H-Score from histogram
|   +-- Cell-based (more accurate) --> Segment nuclei, classify by mean DAB
|
+-- Clinical scoring
|   +-- ER/PR --> Allred Score
|   +-- Ki67 --> Ki67 Index (StarDist recommended)
|   +-- HER2 --> QuPath or manual (membrane detection needed)
|   +-- PD-L1 --> Validated platform (pathologist review required)
|
+-- Fibrosis --> Masson Trichrome deconvolution, blue channel area fraction
|
+-- Batch of images --> Batch macro (same parameters for all)
```

---

## 14. Troubleshooting Colour Deconvolution

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| Plugin not found | Wrong spelling | Use "Colour" (British). Check Image > Color menu |
| "Not an RGB image" | Greyscale or composite input | Convert first: `run("RGB Color");` |
| All outputs identical | Wrong vector set | Try different vectors; match to your stains |
| Cross-contamination | Suboptimal vectors | Custom vectors from single-stained controls |
| Colour_3 shows structure | Expected for 2-stain; but if excessive, vectors suboptimal | Resample from purer stain regions |
| "Singular matrix" | Two vectors too similar | Use more distinct stain vectors |
| Batch macro crashes | Memory leak | Add `close("*"); run("Collect Garbage");` between images |

---

## Appendix A: Stain Vector Values

All built-in normalised optical density vectors [R, G, B]:

| Vector Set | Stain | R | G | B |
|-----------|-------|-----|-----|-----|
| H&E | Haematoxylin | 0.644211 | 0.716556 | 0.266844 |
| H&E | Eosin | 0.092789 | 0.954111 | 0.283111 |
| H&E 2 | Haematoxylin | 0.650 | 0.704 | 0.286 |
| H&E 2 | Eosin | 0.072 | 0.990 | 0.105 |
| H DAB | Haematoxylin | 0.650 | 0.704 | 0.286 |
| H DAB | DAB | 0.268 | 0.570 | 0.776 |
| H&E DAB | Haematoxylin | 0.650 | 0.704 | 0.286 |
| H&E DAB | Eosin | 0.072 | 0.990 | 0.105 |
| H&E DAB | DAB | 0.268 | 0.570 | 0.776 |
| H AEC | Haematoxylin | 0.650 | 0.704 | 0.286 |
| H AEC | AEC | 0.2743 | 0.6796 | 0.6803 |
| FastRed FastBlue DAB | Fast Red | 0.213939 | 0.851127 | 0.477940 |
| FastRed FastBlue DAB | Fast Blue | 0.748903 | 0.606242 | 0.267311 |
| FastRed FastBlue DAB | DAB | 0.268 | 0.570 | 0.776 |
| Methyl Green DAB | Methyl Green | 0.98003 | 0.144316 | 0.133146 |
| Methyl Green DAB | DAB | 0.268 | 0.570 | 0.776 |
| Azan-Mallory | Azocarmine | 0.853033 | 0.508733 | 0.112656 |
| Azan-Mallory | Aniline Blue | 0.070933 | 0.977311 | 0.198067 |
| Masson Trichrome | Ponceau Fuchsin | 0.799511 | 0.591352 | 0.105287 |
| Masson Trichrome | Methyl Blue | 0.099972 | 0.737386 | 0.668142 |
| Alcian Blue & H | Alcian Blue | 0.874622 | 0.457711 | 0.158256 |
| Alcian Blue & H | Haematoxylin | 0.552556 | 0.7544 | 0.353744 |
| H PAS | Haematoxylin | 0.644211 | 0.716556 | 0.266844 |
| H PAS | PAS | 0.175411 | 0.972178 | 0.154589 |

Diagnostic vectors (RGB: Cyan/Magenta/Yellow; CMY: Red/Green/Blue) use unit values
along axes — for testing only.

---

## Appendix B: OD Conversion

```
OD = -log10(I / 255)     ImageJ macro: -log(I/255) / log(10)
I  = 255 * 10^(-OD)
```

| OD | Transmittance | Interpretation |
|----|--------------|----------------|
| 0.0 | 100% | No absorption |
| 0.3 | 50% | Light staining |
| 0.5 | 32% | Moderate staining |
| 1.0 | 10% | Heavy staining |
| 2.0 | 1% | Near-opaque |

---

## Appendix C: All v1 Vector Dropdown Names

```java
run("Colour Deconvolution", "vectors=[H&E]");
run("Colour Deconvolution", "vectors=[H&E 2]");
run("Colour Deconvolution", "vectors=[H DAB]");
run("Colour Deconvolution", "vectors=[H&E DAB]");
run("Colour Deconvolution", "vectors=[H AEC]");
run("Colour Deconvolution", "vectors=[FastRed FastBlue DAB]");
run("Colour Deconvolution", "vectors=[Methyl Green DAB]");
run("Colour Deconvolution", "vectors=[Azan-Mallory]");
run("Colour Deconvolution", "vectors=[Masson Trichrome]");
run("Colour Deconvolution", "vectors=[Alcian blue & H]");
run("Colour Deconvolution", "vectors=[H PAS]");
run("Colour Deconvolution", "vectors=[Feulgen Light Green]");
run("Colour Deconvolution", "vectors=[Giemsa]");
run("Colour Deconvolution", "vectors=[RGB]");
run("Colour Deconvolution", "vectors=[CMY]");
```

---

## Key References

1. Ruifrok & Johnston (2001). Analytical and Quantitative Cytology and Histology 23: 291-299. *Original colour deconvolution.*
2. Landini, Martinelli & Piccinini (2021). Bioinformatics 37(10): 1485-1487. *CD2.*
3. Varghese et al. (2014). PLoS ONE 9(5): e96801. *IHC Profiler.*
4. Macenko et al. (2009). IEEE ISBI, pp. 1107-1110. *Stain normalisation.*
5. Vahadane et al. (2016). IEEE TMI 35(8): 1962-1971. *Structure-preserving normalisation.*
6. Bankhead et al. (2017). Scientific Reports 7: 16878. *QuPath.*

**Links:** [CD2 GitHub](https://github.com/landinig/IJ-Colour_Deconvolution2) |
[IHC Profiler](https://sourceforge.net/projects/ihcprofiler/) |
[QuPath](https://qupath.github.io/) |
[image.sc forum](https://forum.image.sc/)
