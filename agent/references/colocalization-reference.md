# Colocalization Analysis — Reference

---

## 1. Core Concepts

| Concept | Definition | Coefficients |
|---------|-----------|-------------|
| **Co-occurrence** | Are two molecules in the same place? | M1, M2 (Manders') |
| **Correlation** | When one is bright, is the other bright too? | PCC, Spearman, Kendall |

**Avoid MOC** (Manders Overlap Coefficient) — conflates co-occurrence and correlation, produces misleadingly high values (Adler 2010, 2021). Use PCC + M1/M2 instead.

**Colocalization is NOT molecular interaction.** Two proteins within a diffraction-limited volume (~200 nm) may be hundreds of nm apart. Use FRET/PLA/super-resolution for interaction evidence.

---

## 2. Methods

### PCC (Pearson's Correlation Coefficient)

```
PCC = sum[(Ri - Ravg)(Gi - Gavg)] / sqrt[sum(Ri - Ravg)^2 * sum(Gi - Gavg)^2]
```

| Property | Value |
|----------|-------|
| Range | -1 (anti-correlation) to +1 (perfect correlation) |
| Background-sensitive? | No (subtracts mean) |
| Use when | Testing whether two signals vary together in intensity |

### Manders' Coefficients (tM1, tM2)

```
tM1 = sum(Ri where Gi > threshold) / sum(Ri)    — fraction of Ch1 overlapping Ch2
tM2 = sum(Gi where Ri > threshold) / sum(Gi)    — fraction of Ch2 overlapping Ch1
```

| Property | Value |
|----------|-------|
| Range | 0 (no co-occurrence) to 1 (complete) |
| Asymmetric? | Yes — M1 != M2 is biologically meaningful |
| Always use | Thresholded versions (tM1/tM2) with Costes auto-threshold |

### ICQ (Li's Intensity Correlation Quotient)

```
ICQ = N(positive PDM) / N(total PDM) - 0.5
where PDM_i = (Ai - a)(Bi - b)
```

Range: -0.5 (segregated) to +0.5 (dependent). Quick binary assessment; complement to PCC, not a replacement.

### Costes' Auto-Threshold

Progressively lowers thresholds until PCC for below-threshold pixels approaches 0. Removes subjective threshold selection entirely. Always use for Manders' coefficients.

### Van Steensel's CCF

Shifts one channel laterally (-20 to +20 px), calculates PCC at each shift. Peak at dx=0 = colocalization; dip = exclusion; off-centre peak = chromatic aberration. Available in JACoP only.

### Object-Based Colocalization

Segment objects per channel, then measure overlap (centroid distance or object overlap). Best for punctate structures (vesicles, foci). Depends on segmentation quality.

---

## 3. Plugins

### Coloc 2 (primary — built-in)

**Menu:** Analyze > Colocalization Analysis > Coloc 2

**Calculates:** PCC (whole/above threshold), tM1/tM2 with Costes threshold, Costes P-value, ICQ, Spearman, Kendall, scatter plot. Does NOT calculate CCF or object-based metrics.

**Pre-processing:** Split channels first (no composite/RGB). Subtract background. 8/16-bit only. No saturated pixels.

**Macro syntax:**
```javascript
run("Split Channels");
run("Coloc 2",
    "channel_1=C1-image channel_2=C2-image "
  + "roi_or_mask=<None> "
  + "threshold_regression=Costes "
  + "display_images_in_result "
  + "li_histogram_channel_1 li_histogram_channel_2 "
  + "li_icq spearman's_rank_correlation manders'_correlation "
  + "kendall's_tau_rank_correlation 2d_intensity_histogram "
  + "costes'_significance_test "
  + "psf=3 costes_randomisations=200");
```

**Checkbox macro keys:**

| Checkbox | Macro key |
|----------|-----------|
| Li Histogram Ch1/Ch2 | `li_histogram_channel_1`, `li_histogram_channel_2` |
| Li ICQ | `li_icq` |
| Spearman's Rank | `spearman's_rank_correlation` |
| Manders' Correlation | `manders'_correlation` |
| Kendall's Tau | `kendall's_tau_rank_correlation` |
| 2D Intensity Histogram | `2d_intensity_histogram` |
| Costes' Significance Test | `costes'_significance_test` |
| Display Images in Result | `display_images_in_result` |
| Display Shuffled Images | `display_shuffled_images` |
| Show Save PDF Dialog | `show_save_pdf_dialog` |

**ROI/Mask options:** `<None>`, `ROI(s) in channel 1`, `ROI(s) in channel 2`, `ROI Manager`, or any open binary mask image.

**Threshold regression options:** `Costes`, `Bisection`

**Interpreting output:**
```
Pearson's R value (no threshold): 0.72     — all pixels
Pearson's R value (above threshold): 0.85  — signal pixels only
Manders' tM1 (above threshold): 0.91      — 91% of Ch1 overlaps Ch2
Manders' tM2 (above threshold): 0.67      — 67% of Ch2 overlaps Ch1
Costes' P-value: 1.00                     — significant (>= 0.95 required)
```

### Other Installed Plugins

| Plugin | Menu | Use case |
|--------|------|----------|
| **Colocalization Threshold** | Analyze > Colocalization Analysis | Costes threshold + scatter plot (simpler than Coloc 2) |
| **Colocalization Finder** | Plugins | Interactive scatter plot exploration |
| **Colocalization Test** | Analyze > Colocalization Analysis | Significance via Fay/Costes/Van Steensel randomization |
| **3D MultiColoc** | Plugins > MCIB | 3D object-based colocalization on label images |

**Colocalization Threshold macro:**
```javascript
run("Colocalization Threshold",
    "channel_1=[C1-image] channel_2=[C2-image] "
  + "roi_or_mask=None channel_pair=[Red : Green] "
  + "show_colocalized_pixel_map show_scatter_plot "
  + "include_zero-zero_pixels_in_threshold_calculation");
```

**Colocalization Test macro:**
```javascript
run("Colocalization Test",
    "channel_1=[C1-image] channel_2=[C2-image] "
  + "roi_or_mask=None "
  + "randomization=[Costes approximation (smoothed noise)] "
  + "current_slice_only_(ch1)");
```

**Colocalization Finder macro:**
```javascript
run("Colocalization Finder",
    "image_1_(will_be_shown_in_red)=[C1-image] "
  + "image_2_(will_be_shown_in_green)=[C2-image] "
  + "scatter_plot_size=_512 x 512_");
```

### Not Installed

| Plugin | Install via | Adds |
|--------|------------|------|
| **JACoP** | PTBIOP update site | CCF, object-based, all methods in one GUI |
| **EzColocalization** | Fiji update sites | Cell-by-cell analysis, heat maps, metric matrices |
| **ComDet** | PTBIOP update site | Object-based for puncta/spots, distance-based |

---

## 4. Gotchas

| Mistake | Why it matters | Fix |
|---------|---------------|-----|
| Analysing RGB/merged images | Destroys dynamic range, display-dependent | Always use original single-channel images; `run("Split Channels");` |
| "It looks yellow" | Perceived colour depends on brightness, contrast, monitor | Always quantify + show scatter plots |
| No background subtraction | Background inflates M1/M2, distorts PCC | `run("Subtract Background...", "rolling=50");` or CTCF method |
| Saturated pixels | Destroys linear intensity-concentration relationship | Re-acquire; check with `run("Histogram");` — spike at max = saturated |
| Bleedthrough/crosstalk | Creates false positive colocalization | Sequential scanning, single-label controls, narrow bandpass filters |
| Claiming molecular interaction | Resolution limit is ~200 nm; colocalization != binding | State "colocalization at confocal resolution limit" |
| No controls | Cannot interpret coefficients without baseline | See controls table below |
| Analysing whole image incl. background | Background cluster inflates PCC | Use ROIs/masks to restrict to cells |

### Required Controls

| Control | Purpose | Expected result |
|---------|---------|----------------|
| Positive (co-label one target) | Validate method works | PCC ~0.9, M1/M2 ~1.0 |
| Negative (90-degree rotation) | Baseline for random overlap | PCC ~0, M1/M2 low |
| Single-label | Quantify bleedthrough | Other channel should be ~0 |
| Biological negative | Known non-interacting proteins | Low coefficients |
| Costes P-value | Statistical significance | >= 0.95 for each image |

---

## 5. Statistical Testing

### Costes' Randomization (within a single image pair)

Shuffles one channel in PSF-sized blocks (preserving local structure), computes PCC on shuffled vs real, repeats N times.

| Parameter | Recommended |
|-----------|-------------|
| Iterations | Typically 100-200 (Coloc 2 default of 10 is too few) |
| Block size (PSF) | Typically 3 px for confocal (0.61 * lambda_em / (NA * pixel_size)) |
| P-value threshold | >= 0.95 (NOTE: higher = better, opposite of standard p-values) |

### Comparing Conditions (across images)

Costes P-value is per-image. To compare treatment vs control:
1. Measure PCC/tM1/tM2 per cell/field (n >= 20-30)
2. Test normality
3. Normal: t-test (2 groups) or ANOVA (3+). Non-normal: Mann-Whitney / Kruskal-Wallis
4. Report individual measurements, not just means

---

## 6. Decision Tree

```
What is your question?
 |
 +-- "Physical interaction?" → Colocalization CANNOT answer this. Use FRET/PLA/super-res.
 |
 +-- "Same subcellular compartment?"
 |    +-- Diffuse staining → PCC + tM1/tM2 (Coloc 2)
 |    +-- Punctate/spots → Object-based (ComDet, JACoP)
 |    +-- Mixed → tM1/tM2 with cell mask (Coloc 2)
 |
 +-- "What fraction of A is where B is?" → tM1 (Coloc 2 with Costes threshold)
 +-- "Do signals vary together?" → PCC (or Spearman if non-linear)
 +-- "Significant?" → Costes randomization (>= 100 iterations, P >= 0.95)
 +-- "Excluded from each other?" → PCC (negative) or CCF dip at dx=0 (JACoP)
```

### Quick Reference

| Method | Question | Range | Bg-sensitive? | Tool |
|--------|----------|-------|--------------|------|
| PCC | Intensities correlate? | -1 to +1 | No | Coloc 2, JACoP |
| tM1/tM2 | Fraction of Ch1/Ch2 overlap? | 0 to 1 | Yes (use Costes) | Coloc 2, JACoP |
| ICQ | Dependent/segregated/random? | -0.5 to +0.5 | Somewhat | Coloc 2, JACoP |
| CCF | Coloc vs exclusion vs random? | PCC per shift | No | JACoP only |
| Object-based | Fraction of objects overlap? | 0 to 100% | No | JACoP, ComDet |
| Costes P | Significant? | 0 to 1 | N/A | Coloc 2, JACoP |

### Interpreting Values

There are NO universal thresholds. Always compare to controls and between conditions.

| PCC | General interpretation |
|-----|----------------------|
| 0.8-1.0 | Strong positive correlation |
| 0.5-0.8 | Moderate |
| 0.1-0.5 | Weak |
| -0.1-0.1 | No correlation |
| < -0.1 | Anti-correlation (exclusion) |

---

## 7. Reporting Standards

### Minimum Requirements

1. **Acquisition:** Microscope type, objective (mag/NA), fluorophores, excitation/emission, sequential vs simultaneous, pixel size, bit depth, no saturation
2. **Pre-processing:** Background subtraction method, any deconvolution, ROI/mask method
3. **Coefficients:** PCC (above Costes threshold), tM1, tM2, Costes P-value, n
4. **Controls:** Single-label (bleedthrough), negative (rotation), positive (if available)
5. **Figures:** Individual channels (greyscale), scatter plot with threshold lines, quantification graph with individual data points

### Do NOT

- Report only MOC
- Present only merged images as evidence
- Report single-image coefficients without replication
- Use manual thresholds (use Costes)
- Present PCC without Costes P-value
- Claim molecular interaction from colocalization

### Example Methods Text

> "Colocalization was analysed using Coloc 2 (Fiji) with Costes' automatic thresholding. PCC and tM1/tM2 were calculated per cell (n=30 cells per condition from 3 independent experiments). Costes' randomization (200 iterations, P >= 0.95) confirmed significance. Images acquired sequentially on a confocal microscope with no saturated pixels. Background subtracted with 50-pixel rolling ball. Conditions compared by Mann-Whitney U test."

---

## 8. Macro Recipes

### Complete Workflow
```javascript
title = getTitle();
run("Split Channels");
selectWindow("C1-" + title);
run("Subtract Background...", "rolling=50");
selectWindow("C2-" + title);
run("Subtract Background...", "rolling=50");

// Check saturation
selectWindow("C1-" + title);
getStatistics(area, mean, min, max);
if (max == 255) print("WARNING: Channel 1 saturated!");
selectWindow("C2-" + title);
getStatistics(area, mean, min, max);
if (max == 255) print("WARNING: Channel 2 saturated!");

run("Coloc 2",
    "channel_1=[C1-" + title + "] "
  + "channel_2=[C2-" + title + "] "
  + "roi_or_mask=<None> "
  + "threshold_regression=Costes "
  + "display_images_in_result "
  + "psf=3 costes_randomisations=200");
```

### Batch Processing
```javascript
dir = getDirectory("Choose input directory");
list = getFileList(dir);
for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif")) {
        open(dir + list[i]);
        title = getTitle();
        run("Split Channels");
        selectWindow("C1-" + title);
        run("Subtract Background...", "rolling=50");
        selectWindow("C2-" + title);
        run("Subtract Background...", "rolling=50");
        run("Coloc 2",
            "channel_1=[C1-" + title + "] "
          + "channel_2=[C2-" + title + "] "
          + "roi_or_mask=<None> threshold_regression=Costes "
          + "psf=3 costes_randomisations=200");
        run("Close All");
    }
}
```

### Negative Control (rotation)
```javascript
selectWindow("C1-image");
run("Duplicate...", "title=C1-rotated");
run("Rotate 90 Degrees Right");
// Run Coloc 2 with C1-rotated vs C2-image — PCC should be near 0
```

---

## 9. Agent Workflow

```bash
python ij.py macro 'run("Bio-Formats Importer", "open=[/path/to/file.lif] color_mode=Default view=Hyperstack stack_order=XYCZT");'
python ij.py histogram                    # check saturation
python ij.py capture before_coloc
python ij.py macro 'run("Split Channels");'
python ij.py macro 'selectWindow("C1-..."); run("Subtract Background...", "rolling=50 stack");'
python ij.py macro 'selectWindow("C2-..."); run("Subtract Background...", "rolling=50 stack");'
python ij.py macro 'run("Coloc 2", "channel_1=C1-... channel_2=C2-... roi_or_mask=<None> threshold_regression=Costes display_images_in_result costes_randomisations=200 psf=3");'
python ij.py log                          # read results
```

---

## 10. Key References

1. **Dunn et al. 2011** — Practical guide to evaluating colocalization. *Am J Physiol Cell Physiol* 300:C723-C742.
2. **Bolte & Cordelieres 2006** — Guided tour into subcellular colocalization. *J Microsc* 224:213-232.
3. **Costes et al. 2004** — Auto-threshold and randomization test. *Biophys J* 86:3993-4003.
4. **Manders et al. 1993** — Original M1/M2 definitions. *J Microsc* 169:375-382.
5. **Adler & Parmryd 2010, 2021** — PCC > MOC; discard MOC. *Cytometry A* 77:733-742; 99:910-916.
6. **Aaron et al. 2018** — Co-occurrence vs correlation. *J Cell Sci* 131:jcs211847.

---

## Cheat Sheet

```
ALWAYS:
  [x] Sequential scanning, split channels, subtract background
  [x] Costes auto-threshold (not manual), >= 100 randomisation iterations
  [x] Report PCC + tM1 + tM2 + Costes P, n >= 20-30 cells
  [x] Single-label controls, rotation negative control, scatter plots

NEVER:
  [ ] Analyse RGB/merged images
  [ ] Use MOC — use PCC + M1/M2
  [ ] Set manual thresholds
  [ ] Report single-image coefficients without replication
  [ ] Claim molecular interaction from colocalization
```
