# Colocalization Analysis — Complete Reference

A comprehensive guide to colocalization analysis in fluorescence microscopy,
covering methods, mathematics, ImageJ/Fiji plugins, best practices, pitfalls,
statistical testing, method selection, and reporting standards.

---

## Table of Contents

1. [Fundamental Concepts](#1-fundamental-concepts)
2. [Colocalization Methods and Mathematical Definitions](#2-colocalization-methods-and-mathematical-definitions)
3. [ImageJ/Fiji Plugins](#3-imagejfiji-plugins)
4. [Best Practices from Published Guidelines](#4-best-practices-from-published-guidelines)
5. [Common Mistakes and Pitfalls](#5-common-mistakes-and-pitfalls)
6. [Statistical Testing](#6-statistical-testing)
7. [When to Use Which Method — Decision Tree](#7-when-to-use-which-method)
8. [Reporting Standards](#8-reporting-standards)
9. [ImageJ Macro Commands](#9-imagej-macro-commands)
10. [Key References](#10-key-references)

---

## 1. Fundamental Concepts

### Co-occurrence vs. Correlation

This is the single most important conceptual distinction in colocalization
analysis (Adler et al. 2021, Journal of Cell Science 2018):

| Concept | Definition | Measures | Coefficients |
|---------|-----------|----------|-------------|
| **Co-occurrence** | The extent to which two molecules appear in the same location | Spatial overlap — are they in the same place? | M1, M2 (Manders' colocalization coefficients) |
| **Correlation** | How well variations in concentration of two molecules match | Intensity relationship — when one is bright, is the other bright too? | PCC (Pearson), SRCC (Spearman), Kendall's Tau |

**The Manders Overlap Coefficient (MOC) is a problematic hybrid** that mixes
co-occurrence and correlation. Adler (2010, 2021) recommends discarding it
entirely in favour of using PCC for correlation and M1/M2 for co-occurrence
separately.

### What "Colocalization" Actually Means

In fluorescence microscopy, colocalization refers to observing spatial overlap
between two or more fluorescent labels with separate emission wavelengths, to
determine if different targets are located in the same subcellular area.

**Critical caveat:** Optical colocalization does NOT prove molecular interaction.
Two proteins in the same diffraction-limited volume (~200 nm lateral, ~500 nm
axial) could be up to hundreds of nanometres apart. Colocalization demonstrates
co-occurrence at the resolution limit of the microscope, not binding.

---

## 2. Colocalization Methods and Mathematical Definitions

### 2.1 Pearson's Correlation Coefficient (PCC)

**What it measures:** Pixel-by-pixel covariance in signal levels between two
channels. Independent of absolute signal levels and background offset because
it subtracts the mean.

**Mathematical definition:**

```
         sum_i[ (Ri - Ravg) * (Gi - Gavg) ]
PCC = -------------------------------------------
      sqrt[ sum_i(Ri - Ravg)^2 * sum_i(Gi - Gavg)^2 ]
```

Where:
- `Ri` = intensity of channel 1 (red) at pixel i
- `Gi` = intensity of channel 2 (green) at pixel i
- `Ravg` = mean intensity of channel 1
- `Gavg` = mean intensity of channel 2

**Range:** -1 to +1
- **+1** = perfect positive correlation (intensities vary together)
- **0** = no correlation
- **-1** = perfect anti-correlation (one increases as other decreases)

**Strengths:**
- Independent of signal intensity and offset (background)
- Well-understood statistical properties
- Can detect anti-correlation (exclusion)

**Weaknesses:**
- Sensitive to noise at low signal
- A single value for the whole image — no spatial information
- Does not distinguish partial overlap from complete overlap at different intensities
- Negative values hard to interpret biologically

**When to use:** Default choice for measuring whether two signals vary together
in intensity. Good for testing whether two proteins are in the same
compartments with correlated abundance.

**Reference:** Manders et al. 1993; Adler et al. 2010.

---

### 2.2 Manders' Colocalization Coefficients (M1, M2)

**What they measure:** The fraction of total intensity of one channel that
co-occurs with signal in the other channel. M1 and M2 are asymmetric — they
answer different questions.

**Mathematical definition:**

```
      sum_i( Ri,colocal )
M1 = ---------------------
         sum_i( Ri )

where Ri,colocal = Ri  if Gi > 0
                 = 0   if Gi = 0
```

```
      sum_i( Gi,colocal )
M2 = ---------------------
         sum_i( Gi )

where Gi,colocal = Gi  if Ri > 0
                 = 0   if Ri = 0
```

**Thresholded versions (tM1, tM2):** Same formula but using Costes auto-threshold
instead of zero:

```
tM1: Ri,colocal = Ri  if Gi > Gthreshold
                = 0   otherwise

tM2: Gi,colocal = Gi  if Ri > Rthreshold
                = 0   otherwise
```

**Range:** 0 to 1
- **0** = no co-occurrence
- **1** = complete co-occurrence (all signal of channel 1 overlaps with channel 2)

**Interpretation example:**
- M1 = 0.85 means 85% of channel 1 intensity is in pixels where channel 2 is present
- M2 = 0.40 means only 40% of channel 2 intensity overlaps with channel 1
- This asymmetry is biologically meaningful (e.g., all of protein A is where B is, but B is also in many other places)

**CRITICAL:** Always use thresholded versions (tM1, tM2) with Costes auto-threshold.
Without thresholding, background noise creates false co-occurrence.

**When to use:** When you need to know what fraction of one protein is in
compartments containing the other. Ideal for questions like "what percentage
of protein X colocalizes with the lysosomal marker?"

**Reference:** Manders et al. 1993 (J Microsc 169:375-382).

---

### 2.3 Manders' Overlap Coefficient (MOC / k1, k2)

**What it measures:** A hybrid metric combining co-occurrence and correlation.

**Mathematical definition:**

```
          sum_i( Ri * Gi )
MOC = ---------------------------
      sqrt[ sum_i(Ri^2) * sum_i(Gi^2) ]
```

Split coefficients:

```
      sum_i( Ri * Gi )            sum_i( Ri * Gi )
k1 = -----------------      k2 = -----------------
       sum_i( Ri^2 )               sum_i( Gi^2 )
```

**Range:** 0 to 1

**WARNING — AVOID THIS METRIC.** Adler et al. (2010, 2021) demonstrated that
MOC is a "confusing hybrid measurement" that:
- Combines correlation with a heavily weighted form of co-occurrence
- Favours high-intensity combinations
- Downplays low-intensity combinations
- Ignores blank pixels
- Is insensitive to changes in colocalization
- Values are typically very high (>0.9) even with random overlap

**Recommendation:** Use PCC for correlation and M1/M2 for co-occurrence instead.

**Reference:** Manders et al. 1993; Adler et al. 2010 (Cytometry A 77:733-742);
Adler et al. 2021 (Cytometry A 99:910-916).

---

### 2.4 Intensity Correlation Quotient (ICQ / Li's ICA)

**What it measures:** Whether two channel intensities vary synchronously
(dependent staining) or asynchronously (segregated staining).

**Mathematical definition:**

The Product of Differences from the Mean (PDM):

```
PDM_i = (Ai - a) * (Bi - b)
```

Where `a` and `b` are mean intensities of channels A and B.

The Intensity Correlation Analysis (ICA) plots PDM values for each pixel.

```
                    N(positive PDM)
ICQ = ---------------------------------------- - 0.5
      N(positive PDM) + N(negative PDM)
```

Where N(positive PDM) = number of pixels where both intensities deviate from
their means in the same direction.

**Range:** -0.5 to +0.5
- **+0.5** = dependent staining (intensities vary together)
- **0** = random staining
- **-0.5** = segregated staining (intensities vary inversely)

**Strengths:**
- Simple, intuitive interpretation
- Sign test can assess significance (number of positive vs negative PDM values)
- ICA plot provides visual information about relationship

**Weaknesses:**
- Only considers the sign of the deviation, not its magnitude
- Less sensitive than PCC
- Gives equal weight to tiny and large deviations

**When to use:** Quick assessment of whether staining is dependent, random,
or segregated. Useful as a complement to PCC, not a replacement.

**Reference:** Li et al. 2004 (J Neurosci 24:4070-4081).

---

### 2.5 Costes' Auto-Threshold

**What it measures:** The threshold below which there is no meaningful
correlation between channels. Not a colocalization coefficient itself, but a
critical preprocessing step.

**Algorithm:**
1. Start with thresholds at maximum intensity in both channels
2. Progressively lower both thresholds together (bisection method)
3. At each step, compute PCC for pixels above the threshold
4. Find the threshold pair where PCC for below-threshold pixels approaches 0
5. This is the point where only noise remains below threshold

**Why it matters:** Manual thresholding is subjective and a major source of
bias. Costes' method removes this subjectivity entirely.

**Implementation note:** The algorithm uses linear regression on the scatter
plot to link the two thresholds, stepping down the regression line until
Pearson's r for the below-threshold pixels reaches zero.

**Reference:** Costes et al. 2004 (Biophys J 86:3993-4003).

---

### 2.6 Van Steensel's Cross-Correlation Function (CCF)

**What it measures:** How PCC changes as one image is shifted laterally
relative to the other, pixel by pixel.

**Algorithm:**
1. Shift channel 1 image by dx pixels in x (-20 <= dx <= +20 typically)
2. Calculate PCC at each shift value
3. Plot PCC vs. shift distance

**Interpretation of CCF shape:**
- **Peak at dx=0:** Positive colocalization
- **Dip at dx=0:** Mutual exclusion
- **Flat/featureless:** Random overlap, no relationship
- **Peak shifted from 0:** Possible chromatic aberration or partial overlap

**Strengths:**
- Visual and intuitive
- Can detect chromatic aberration (peak not centered)
- Can distinguish colocalization from exclusion

**Weaknesses:**
- Only considers shifts in one dimension (x)
- Computationally expensive for large shifts
- Width of peak depends on object size, not just colocalization

**When to use:** Quality control — verify that colocalization is real (peak at
zero) vs. artefactual (peak shifted). Detecting chromatic aberration.

**Reference:** Van Steensel et al. 1996 (J Cell Sci 109:787-792).

---

### 2.7 Object-Based Colocalization

**What it measures:** Spatial relationships between segmented objects rather
than pixel intensities.

**Two main approaches:**

#### Centroid Distance Method
1. Segment objects in each channel
2. Calculate centroid (center of mass) of each object
3. Objects colocalize if centroids are within a defined distance

**Distance threshold:** Typically set to the resolution limit of the microscope
(~250 nm for confocal) or the mean object radius.

#### Object Overlap Method
1. Segment objects in each channel
2. An object in channel 1 colocalizes with channel 2 if its centroid falls
   within a segmented object in channel 2
3. Report percentage of objects that colocalize

**Strengths:**
- Can detect colocalization of spatially separated markers (nuclear + cytoplasmic)
- Per-object statistics (mean, median, proportion colocalized)
- Less sensitive to background and noise
- Results in countable, interpretable numbers

**Weaknesses:**
- Depends entirely on segmentation quality
- Centroid distance unreliable for large/irregular objects
- Binary decision (colocalized or not) loses intensity information

**When to use:**
- Punctate structures (vesicles, spots, foci)
- When markers are in different compartments (e.g., nuclear vs cytoplasmic)
- When you need per-cell or per-object statistics
- When pixel-based methods give ambiguous results

**Reference:** Bolte & Cordelieres 2006.

---

## 3. ImageJ/Fiji Plugins

### 3.1 Coloc 2 (Fiji built-in)

The standard colocalization plugin in Fiji. Implements pixel intensity
correlation methods.

**Menu:** Analyze > Colocalization Analysis > Coloc 2

**What it calculates:**
- Pearson's correlation coefficient (PCC) — whole image and above/below threshold
- Manders' colocalization coefficients (tM1, tM2) with Costes auto-threshold
- Costes' auto-threshold values
- Costes' significance test (P-value)
- Li's Intensity Correlation Quotient (ICQ)
- Spearman's rank correlation
- Kendall's Tau rank correlation
- 2D intensity histogram (scatter plot)

**Does NOT calculate:** Object-based colocalization, Van Steensel CCF, MOC/k1/k2.

**Parameters:**
| Parameter | Description | Recommended |
|-----------|-------------|-------------|
| Channel 1 | First image or channel | — |
| Channel 2 | Second image or channel | — |
| ROI or Mask | Optional binary mask (255=analyse, 0=ignore) | Use to restrict to cells |
| PSF | Point spread function size in pixels | 3 for confocal (calculate from NA/wavelength) |
| Costes randomisations | Number of iterations for significance test | 100 minimum, 200 recommended |
| Threshold regression | Costes auto-threshold method | Bisection (default) |

**Pre-processing requirements:**
- Split channels before running (do NOT use composite/RGB)
- Subtract background first (offset corrupts threshold calculation)
- 8-bit or 16-bit images (not 32-bit float)
- No saturated pixels

**Output tabs:**
- **Log:** Numerical results (PCC, tM1, tM2, thresholds, P-value)
- **Scatter plot:** 2D histogram with threshold lines
- **Regression:** Costes regression line
- **PDF:** Exportable standardised report

**ImageJ macro syntax:**

```javascript
// Split a composite image into channels first
run("Split Channels");

// Run Coloc 2 — use the macro recorder to get exact syntax for your images
// Basic syntax (channel names will vary):
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

**Complete checkbox macro keys** (include to enable, omit to skip):
| Checkbox | Macro key | Default |
|----------|-----------|---------|
| Li Histogram Channel 1 | `li_histogram_channel_1` | on |
| Li Histogram Channel 2 | `li_histogram_channel_2` | on |
| Li ICQ | `li_icq` | on |
| Spearman's Rank | `spearman's_rank_correlation` | on |
| Manders' Correlation | `manders'_correlation` | on |
| Kendall's Tau | `kendall's_tau_rank_correlation` | on |
| 2D Intensity Histogram | `2d_intensity_histogram` | on |
| Costes' Significance Test | `costes'_significance_test` | on |
| Display Images in Result | `display_images_in_result` | off |
| Display Shuffled Images | `display_shuffled_images` | off |
| Show Save PDF Dialog | `show_save_pdf_dialog` | on |

**ROI/Mask options:** `<None>`, `ROI(s) in channel 1`, `ROI(s) in channel 2`, `ROI Manager`, or any open image name as binary mask.

**Threshold regression options:** `Costes`, `Bisection`

**IMPORTANT:** Coloc 2 macro syntax is poorly documented. Always use the macro
recorder (Plugins > Macros > Record) to capture the exact arguments for your
specific images. The channel names in the macro must match the exact window
titles.

**Interpreting Coloc 2 output:**
```
Pearson's R value (no threshold): 0.72    ← correlation of all pixels
Pearson's R value (above threshold): 0.85 ← correlation of signal pixels only
Manders' tM1 (above threshold): 0.91     ← 91% of Ch1 overlaps with Ch2
Manders' tM2 (above threshold): 0.67     ← 67% of Ch2 overlaps with Ch1
Costes' P-value: 1.00                    ← significant (>0.95 required)
Costes' Ch1 threshold: 45                ← auto-determined background level
Costes' Ch2 threshold: 38
```

---

### 3.2 JACoP (Just Another Colocalization Plugin)

**Menu:** Plugins > JACoP (requires separate installation or BIOP update site)

**What it calculates (all in one plugin):**
- Pearson's coefficient
- Manders' M1, M2 (with manual or Costes threshold)
- Overlap coefficient (k1, k2)
- Costes' auto-threshold
- Costes' randomization (significance test)
- Li's ICQ/ICA
- Van Steensel's CCF
- Object-based colocalization (centroid distance + centre-particle coincidence)

**Key advantages over Coloc 2:**
- Van Steensel's CCF (not in Coloc 2)
- Object-based methods (not in Coloc 2)
- All methods in one interface
- Works on 3D datasets

**Parameters:**
- Two single-channel images (split channels first)
- Manual threshold or Costes auto-threshold
- For object-based: distance threshold, minimum object size
- For Costes randomization: number of iterations, PSF width
- For Van Steensel CCF: shift range (pixels)

**Installation:**

The BIOP version (updated) is available via the PTBIOP update site:
Help > Update > Manage Update Sites > check "PTBIOP" > Apply > Restart Fiji.

**ImageJ macro syntax:**

```javascript
// JACoP is a GUI plugin — limited macro support
// Use the BIOP version for better scriptability
// Basic approach: open two single-channel images, then run
run("JACoP ");
```

**Reference:** Bolte & Cordelieres 2006 (J Microsc 224:213-232).

---

### 3.3 Colocalization Threshold

**Menu:** Analyze > Colocalization Analysis > Colocalization Threshold

**What it does:** Implements Costes' auto-threshold method and calculates PCC
and M1/M2 above and below threshold. Simpler than Coloc 2 but produces a
useful scatter plot with threshold lines.

**Macro syntax** (from API probe):
```javascript
run("Colocalization Threshold",
    "channel_1=[C1-image] channel_2=[C2-image] "
  + "roi_or_mask=None channel_pair=[Red : Green] "
  + "show_colocalized_pixel_map show_scatter_plot "
  + "include_zero-zero_pixels_in_threshold_calculation");
```

**Note:** JACoP is NOT installed in this Fiji. Install via: Help > Update > Manage Update Sites > PTBIOP > Apply > Restart.

---

### 3.4 Colocalization Finder

**Menu:** Plugins > Colocalization Finder (installed)

**What it does:** Interactive scatter plot tool. Draw ROIs on the scatter plot
to see which pixels they correspond to in the image. Useful for exploratory
analysis before quantification.

**Macro syntax:**
```javascript
run("Colocalization Finder",
    "image_1_(will_be_shown_in_red)=[C1-image] "
  + "image_2_(will_be_shown_in_green)=[C2-image] "
  + "scatter_plot_size=_512 x 512_");
```

---

### 3.4b Colocalization Test

**Menu:** Analyze > Colocalization Analysis > Colocalization Test (installed)

**What it does:** Statistical significance via randomization — Fay, Costes, or Van Steensel methods.

**Macro syntax:**
```javascript
run("Colocalization Test",
    "channel_1=[C1-image] channel_2=[C2-image] "
  + "roi_or_mask=None "
  + "randomization=[Costes approximation (smoothed noise)] "
  + "current_slice_only_(ch1)");
```

**Randomization options:** `Fay (x,y,z translation)`, `Costes approximation (smoothed noise)`, `van Steensel (x translation)`

---

### 3.4c 3D MultiColoc

**Menu:** Plugins > MCIB > 3D MultiColoc (installed)

**What it does:** 3D multi-channel object-based colocalization from the MCIB 3D suite. Works on label images.

---

### 3.5 EzColocalization

**What it does:** Designed for cell-by-cell colocalization analysis. Features:
- Select individual cells/organisms from images
- Filter cells by physical parameters and signal intensity
- Heat maps and scatter plots
- Multiple metrics (PCC, tM1/tM2, TOS, ICQ)
- Metric matrices at multiple threshold combinations
- Works on cells, tissues, and whole organisms (C. elegans, Drosophila)

**Strengths:** Especially good for automation, individual cell measurements in
dense samples, and reporters with low signal/specificity.

**Installation:** Available via Fiji update sites.

**Reference:** Stauffer et al. 2018 (Sci Rep 8:15764).

---

### 3.6 ComDet (Spots Colocalization)

**Menu:** Plugins > Spots colocalization (ComDet)

**What it does:** Object-based colocalization for punctate structures (spots,
vesicles, particles). Detects bright spots in each channel independently and
measures colocalization based on distance between spot centres.

**Parameters:**
- Spot size (approximate diameter in pixels) for each channel
- Intensity threshold (above local background)
- Max distance between centres for colocalization (pixels)

**Output:** Spot coordinates, integrated intensities (background-subtracted),
colocalization status for each spot.

**Strengths:**
- Handles inhomogeneous background
- Fast and robust
- Per-spot measurements
- Clear colocalization criterion (distance threshold)

**When to use:** Vesicle trafficking, RNA FISH spots, signalling puncta —
any analysis involving discrete bright objects.

**Installation:** Available via Fiji update site: "PTBIOP" or direct download.

**Reference:** Feyder et al. (GitHub: UU-cellbiology/ComDet).

---

### 3.7 CellProfiler Colocalization Modules

**Module:** MeasureColocalization

**Measurements calculated:**
- Pearson's correlation coefficient
- Manders' coefficients (M1, M2)
- Overlap coefficient
- Costes' auto-threshold and P-value
- Rank Weighted Colocalization (RWC) — unique to CellProfiler
- K coefficients (k1, k2)

**Rank Weighted Colocalization (RWC):**
```
RWC1 = sum(Ri_coloc * Wi) / sum(Ri)
where Wi = (Rmax - Di) / Rmax
Di = |Rank(Ri) - Rank(Gi)|
```

**Strengths:**
- Cell-by-cell measurements within segmented objects
- Pipeline-based automation
- Handles batch processing of thousands of images
- Per-object AND whole-image measurements

---

## 4. Best Practices from Published Guidelines

### 4.1 Bolte & Cordelieres 2006

"A guided tour into subcellular colocalization analysis in light microscopy"
(J Microsc 224:213-232)

**Key recommendations:**
1. Use confocal (not widefield) for colocalization — optical sectioning reduces
   out-of-focus blur that creates false co-occurrence
2. Use sequential scanning (not simultaneous) to eliminate crosstalk/bleedthrough
3. Carefully select fluorophore pairs with minimal spectral overlap
4. Always include single-label controls to assess bleedthrough
5. Background subtract before analysis
6. Use multiple quantitative methods — do not rely on visual assessment
7. Object-based methods are more appropriate for punctate structures
8. Pixel-based methods are better for diffuse/continuous staining

---

### 4.2 Dunn, Kamocka & McDonald 2011

"A practical guide to evaluating colocalization in biological microscopy"
(Am J Physiol Cell Physiol 300:C723-C742)

**Key recommendations:**
1. **Never rely on merged images alone.** Yellow in an RGB merge is not
   quantitative evidence of colocalization. The perceived colour depends on
   relative brightness, display settings, and monitor calibration.
2. **Always include scatter plots.** Plot pixel intensities of Ch1 vs Ch2.
   Look for:
   - Linear relationship (correlation)
   - Pixels in the upper-right quadrant (co-occurrence of bright signal)
   - Multiple populations (different subcellular compartments)
3. **Check for linearity.** If the scatter plot shows a single linear
   relationship, PCC is appropriate. If there are multiple relationships
   or nonlinear patterns, PCC may be misleading.
4. **Use Costes' auto-threshold.** Manual thresholds are subjective and
   unreliable.
5. **Report M1 AND M2.** They measure different things — the asymmetry is
   biologically informative.
6. **Include positive and negative controls:**
   - Positive: co-label the same target with two fluorophores
   - Negative: rotate one image 90 degrees and measure (should give ~0)
7. **Statistical comparison across conditions requires multiple cells/fields.**
   Never compare PCC from a single image pair.

---

### 4.3 Costes et al. 2004

"Automatic and quantitative measurement of protein-protein colocalization
in live cells" (Biophys J 86:3993-4003)

**Key contributions:**
1. Automatic threshold selection (removes subjective bias)
2. Statistical significance test via randomization
3. Both methods account for the point spread function (PSF)

---

### 4.4 Manders et al. 1993

"Measurement of co-localization of objects in dual-colour confocal images"
(J Microsc 169:375-382)

**Key contributions:**
1. Introduced M1 and M2 coefficients for asymmetric co-occurrence measurement
2. Introduced the overlap coefficient (MOC) — now considered problematic
3. Tested on real biological specimens
4. Established that colocalization coefficients can provide quantitative
   information about positional relationships between biological structures

---

### 4.5 Adler et al. 2010, 2021

"Quantifying colocalization by correlation: The Pearson correlation coefficient
is superior to the Mander's overlap coefficient" (Cytometry A 77:733-742, 2010)

"Quantifying colocalization: The case for discarding the Manders overlap
coefficient" (Cytometry A 99:910-916, 2021)

**Key arguments:**
1. **Discard the MOC.** It conflates correlation and co-occurrence, is
   insensitive to changes, and produces misleadingly high values.
2. **Use PCC for correlation and M1/M2 for co-occurrence** — keep the two
   concepts separate.
3. **PCC is more robust** to noise and intensity variations than MOC.
4. Additional recommended metrics: Spearman's rank correlation (SRCC) for
   non-normal distributions, Kendall's Tau for ranked data.

---

## 5. Common Mistakes and Pitfalls

### 5.1 Using RGB/Merged Images

**The mistake:** Analysing a merged RGB image rather than individual channels.

**Why it is wrong:** RGB images have only 8 bits per channel (0-255). Merging
destroys the original dynamic range and makes quantitative analysis meaningless.
Different fluorophores contribute different amounts to RGB, and the merge
process is display-dependent.

**Correct approach:** Always analyse original single-channel images (8-bit or
16-bit greyscale). Split channels first:
```javascript
run("Split Channels");
```

### 5.2 Relying on Visual Assessment ("It Looks Yellow")

**The mistake:** Concluding colocalization exists because the merged image
shows yellow/white pixels.

**Why it is wrong:** Perceived colour in a merged image depends on:
- Relative brightness of the two channels
- Display contrast settings (window/level)
- Monitor calibration
- Human colour perception biases

**Correct approach:** Always quantify with appropriate coefficients AND include
scatter plots.

### 5.3 Ignoring Background

**The mistake:** Running colocalization analysis without subtracting background.

**Why it is wrong:** Background signal creates false co-occurrence. If both
channels have a background level of 30, every pixel appears to "colocalize"
even where there is no real signal. This inflates M1, M2, and can distort PCC.
Costes auto-threshold also fails when background is not properly handled, because
the threshold algorithm expects background pixels to have near-zero intensity.

**Correct approach:**
```javascript
// Measure background in a region with no signal
// Subtract it from the whole image
run("Subtract...", "value=30");  // replace 30 with measured background

// OR use rolling ball background subtraction
run("Subtract Background...", "rolling=50");
```

### 5.4 Saturated Pixels

**The mistake:** Acquiring images with saturated (clipped) pixels.

**Why it is wrong:** Saturated pixels have their true intensity replaced with
the maximum detector value (255 for 8-bit, 4095 for 12-bit, 65535 for 16-bit).
This:
- Destroys the linear relationship between fluorophore concentration and
  recorded intensity that ALL correlation methods depend on
- Inflates the apparent area of bright objects (blooming)
- Creates false positive colocalization in bright regions
- Makes PCC unreliable (ceiling effect compresses variance)

**How to detect:**
```javascript
// Check for saturation
run("Histogram");
// Look for a spike at the maximum value (255, 4095, or 65535)

// Or use the agent:
python ij.py histogram
// Check if max bin has many pixels
```

**Correct approach:** Re-acquire images ensuring no pixels are saturated. Use
the range indicator (HiLo LUT) during acquisition to check. The very brightest
features should use ~80-90% of the dynamic range.

### 5.5 Bleedthrough / Crosstalk

**The mistake:** Using fluorophore pairs with overlapping spectra and acquiring
both channels simultaneously.

**Why it is wrong:**
- **Bleedthrough:** Emission from fluorophore A leaks into the detection channel
  for fluorophore B (spectral overlap of emission)
- **Crosstalk:** Excitation light for fluorophore B also excites fluorophore A
  (spectral overlap of excitation)
- Both create false positive colocalization

**How to detect:** Image single-labelled controls. If you see signal in the
"wrong" channel, you have bleedthrough/crosstalk.

**Correct approach:**
1. Use sequential scanning (not simultaneous)
2. Choose fluorophore pairs with maximal spectral separation
3. Use narrow bandpass emission filters
4. Image single-label controls to quantify any residual bleedthrough
5. Apply spectral unmixing if needed

### 5.6 Resolution Limits

**The mistake:** Concluding molecular interaction from confocal colocalization.

**Why it is wrong:** The diffraction limit means two objects closer than ~200 nm
laterally or ~500 nm axially will appear to colocalize. Proteins in different
membrane domains, or even in entirely different organelles, can appear
colocalized if those organelles are within the PSF volume.

**Correct approach:** State that results show "colocalization at the resolution
limit of confocal microscopy." For true co-interaction evidence, use FRET,
BiFC, PLA, or super-resolution methods (STED, STORM, SIM).

### 5.7 No Controls

**The mistake:** Reporting colocalization without positive or negative controls.

**Required controls:**
| Control | Purpose | How |
|---------|---------|-----|
| **Positive** | Validate methodology works | Co-label one target with both fluorophores; expect PCC ~0.9, M1/M2 ~1.0 |
| **Negative (rotation)** | Baseline for random overlap | Rotate one channel 90 degrees; expect PCC ~0, M1/M2 low |
| **Negative (single-label)** | Quantify bleedthrough | Image with only one fluorophore; other channel should be zero |
| **Biological negative** | Proteins known not to interact | Two proteins in different compartments |
| **Costes P-value** | Statistical significance | Must be >= 0.95 |

### 5.8 Analysing the Whole Image

**The mistake:** Running colocalization on the full field of view including
empty background.

**Why it is wrong:** Background pixels (near-zero in both channels) inflate PCC
artificially — they form a cluster at the origin of the scatter plot that
contributes to correlation. M1/M2 are less affected by background (if
thresholded) but can still be skewed.

**Correct approach:** Use ROIs or binary masks to restrict analysis to cells:
```javascript
// Draw ROI around cell, or use a mask
// In Coloc 2: select mask image in the "ROI or Mask" dropdown
```

---

## 6. Statistical Testing

### 6.1 Costes' Randomization / Significance Test

**Purpose:** Determine whether the measured colocalization is statistically
significant — i.e., better than what would occur by random chance.

**Algorithm:**
1. Take one channel image
2. Divide it into blocks whose size matches the PSF
   (typically 3x3 pixels for confocal)
3. Shuffle these blocks randomly to create a new image that preserves local
   structure but destroys spatial correlation with the other channel
4. Calculate PCC between the shuffled image and the unshuffled other channel
5. Repeat N times to build a distribution of random PCC values
6. Compare the real PCC to this null distribution

**Why blocks, not individual pixels?**
Individual pixels are not independent — each pixel's intensity is correlated
with its neighbours due to the PSF. Shuffling individual pixels would destroy
this spatial autocorrelation and create an unrealistically stringent null
distribution (nearly all real images would appear "significant"). Block
scrambling preserves within-PSF correlations and provides a fair test.

**Parameters:**

| Parameter | Recommended Value | Notes |
|-----------|------------------|-------|
| **Number of iterations** | 100-200 (minimum 100) | More is better but slower. Coloc 2 default suggestion is 10 (too few). |
| **Block size (PSF)** | 3 pixels (typical confocal) | Calculate from: PSF_pixels = 0.61 * lambda_em / (NA * pixel_size). Use the larger channel's PSF. |
| **P-value threshold** | >= 0.95 (95% confidence) | Fraction of random shuffles that had LOWER correlation than the real image. P=1.00 means all shuffles were worse than reality. |

**Interpreting P-value:**
- **P >= 0.95:** Colocalization is statistically significant. The measured
  correlation is better than 95% of random arrangements.
- **P < 0.95:** Cannot conclude significant colocalization. Could be random
  overlap.
- **P = 1.00:** All randomised images had lower PCC than the real image.
  Very strong evidence.

**IMPORTANT:** The P-value from Costes' test is NOT the same as a traditional
statistical P-value. In Costes' convention, **higher is better** (P=1.00 is
the strongest evidence for colocalization). This is the opposite of typical
hypothesis testing where low P-values indicate significance.

### 6.2 Statistical Comparison Across Conditions

Costes' P-value tells you whether a SINGLE image pair shows significant
colocalization. To compare colocalization BETWEEN conditions (e.g., treated
vs. untreated), you need:

1. Measure PCC (or tM1/tM2) in multiple cells/fields per condition (n >= 20-30)
2. Test normality of the distribution
3. Apply appropriate statistical test:
   - Normal distributions: t-test (2 groups) or ANOVA (3+ groups)
   - Non-normal: Mann-Whitney U (2 groups) or Kruskal-Wallis (3+ groups)
4. Report individual measurements, not just means

**Common mistake:** Comparing single PCC values between two conditions without
biological replicates. This has N=1 per condition and no statistical power.

---

## 7. When to Use Which Method

### Decision Tree

```
START: What is your biological question?
 |
 +-- "Do the two proteins physically interact?"
 |    --> Colocalization CANNOT answer this.
 |        Use FRET, PLA, Co-IP, or super-resolution.
 |        Colocalization can only show co-occurrence at the diffraction limit.
 |
 +-- "Are both proteins in the same subcellular compartment?"
 |    |
 |    +-- Diffuse/continuous staining (cytoplasm, membrane)?
 |    |    --> Use PCC (correlation) + tM1/tM2 (co-occurrence)
 |    |        Tool: Coloc 2
 |    |
 |    +-- Punctate/spot-like staining (vesicles, foci)?
 |    |    --> Use object-based colocalization
 |    |        Tool: ComDet, JACoP (object-based), or CellProfiler
 |    |
 |    +-- Mixed (one diffuse, one punctate)?
 |         --> Use tM1/tM2 (fraction of puncta in diffuse compartment)
 |             Tool: Coloc 2 with mask restricted to cells
 |
 +-- "What fraction of protein A is where protein B is?"
 |    --> Use tM1 (thresholded Manders' coefficient)
 |        This directly answers "fraction of A in compartments with B"
 |        Tool: Coloc 2 or JACoP with Costes auto-threshold
 |
 +-- "Do the two signals vary together in intensity?"
 |    --> Use PCC (Pearson's correlation coefficient)
 |        If non-linear relationship expected: use Spearman's rank correlation
 |        Tool: Coloc 2
 |
 +-- "Is the measured correlation statistically significant?"
 |    --> Run Costes' randomization test
 |        P >= 0.95 required for significance
 |        Tool: Coloc 2 (with >= 100 iterations)
 |
 +-- "Are the two proteins excluded from each other?"
      --> Use PCC (look for negative values)
          OR Van Steensel CCF (look for dip at dx=0)
          Tool: JACoP (has CCF; Coloc 2 does not)
```

### Quick Reference Table

| Method | Question Answered | Range | Background Sensitive? | Tool |
|--------|------------------|-------|----------------------|------|
| PCC | Do intensities correlate? | -1 to +1 | No (subtracts mean) | Coloc 2, JACoP |
| tM1 | What fraction of Ch1 is where Ch2 is? | 0 to 1 | Yes (use Costes threshold) | Coloc 2, JACoP |
| tM2 | What fraction of Ch2 is where Ch1 is? | 0 to 1 | Yes (use Costes threshold) | Coloc 2, JACoP |
| ICQ | Are intensities dependent or segregated? | -0.5 to +0.5 | Somewhat | Coloc 2, JACoP |
| CCF | Colocalization vs. exclusion vs. random? | PCC at each shift | No | JACoP only |
| MOC | (Avoid — confounding metric) | 0 to 1 | Yes | JACoP |
| Object-based | What fraction of objects overlap? | 0 to 100% | No (uses segmentation) | JACoP, ComDet |
| Costes P | Is colocalization significant? | 0 to 1 | N/A | Coloc 2, JACoP |

### When ICQ is Appropriate

ICQ (Li's Intensity Correlation Quotient) is appropriate when:
- You want a quick binary answer: dependent vs. segregated vs. random staining
- The distributions are non-normal and PCC may be unreliable
- You want to complement PCC with a sign-based test
- You want to visualize the relationship via ICA plots

ICQ is NOT a substitute for PCC or M1/M2 — it loses magnitude information.

---

## 8. Reporting Standards

### Minimum Reporting Requirements

Every colocalization study should report ALL of the following:

#### 1. Image Acquisition Parameters
- Microscope type (confocal, widefield, super-resolution)
- Objective lens (magnification, NA)
- Fluorophore pair used
- Excitation wavelengths and emission filters
- Sequential vs. simultaneous scanning
- Pixel size (nm/pixel) and z-step (if 3D)
- Bit depth (8-bit, 12-bit, 16-bit)
- No pixels saturated (state explicitly)

#### 2. Pre-processing Steps
- Background subtraction method and parameters
- Any deconvolution performed (algorithm, iterations)
- ROI/mask selection method
- Any other processing (median filter, etc.)

#### 3. Quantitative Coefficients (minimum set)
| Value | What to Report | Example |
|-------|---------------|---------|
| **PCC** | Pearson's R, above Costes threshold | PCC = 0.72 +/- 0.08 (n=30 cells) |
| **tM1** | Fraction of Ch1 in Ch2 | tM1 = 0.91 +/- 0.05 |
| **tM2** | Fraction of Ch2 in Ch1 | tM2 = 0.67 +/- 0.12 |
| **Costes P** | Significance | P = 1.00 for all images |
| **N** | Number of cells/fields measured | 30 cells from 3 independent experiments |

#### 4. Controls
- Single-label controls (bleedthrough assessment)
- Negative control (rotation test or biological negative)
- Positive control (if available)

#### 5. Figures to Include
- **Representative images:** Individual channels (greyscale), merged
  (for illustration only — not evidence), and scatter plot
- **Scatter plot:** 2D intensity histogram with Costes threshold lines marked
- **Quantification graph:** Bar plot or box plot of PCC/tM1/tM2 across
  conditions with individual data points shown
- **Statistical test results:** If comparing conditions

### What NOT to Do When Reporting

- Do NOT report only MOC (use PCC + tM1/tM2 instead)
- Do NOT present only merged images as evidence
- Do NOT report coefficients from a single image without replication
- Do NOT use arbitrary manual thresholds (use Costes)
- Do NOT present PCC without the Costes P-value
- Do NOT use the words "colocalized" or "did not colocalize" without
  quantitative evidence and statistical testing
- Do NOT claim molecular interaction based on colocalization alone

### Example Methods Section

> "Colocalization was analysed using Coloc 2 (Fiji) with Costes' automatic
> thresholding (Costes et al. 2004). Pearson's correlation coefficient (PCC)
> and thresholded Manders' coefficients (tM1, tM2) were calculated for each
> cell (n=30 cells per condition from 3 independent experiments). Statistical
> significance of colocalization was confirmed by Costes' randomization test
> (200 iterations, P >= 0.95 for all images). Images were acquired sequentially
> on a Leica SP8 confocal (63x/1.4 NA oil objective) with no saturated pixels.
> Background was subtracted using a 50-pixel rolling ball filter. Differences
> between conditions were assessed by Mann-Whitney U test."

### Interpreting Coefficient Values

There are NO universal thresholds for "high" vs. "low" colocalization.
Context matters:

| PCC Value | General Interpretation |
|-----------|----------------------|
| 0.8-1.0 | Strong positive correlation |
| 0.5-0.8 | Moderate positive correlation |
| 0.1-0.5 | Weak positive correlation |
| -0.1-0.1 | No correlation |
| < -0.1 | Anti-correlation (exclusion) |

**But:** A PCC of 0.5 may be biologically highly significant if the expected
value is 0. Always compare to controls and between conditions rather than
interpreting absolute values.

For tM1/tM2:
- 0.9+ = nearly complete co-occurrence
- 0.5-0.9 = partial co-occurrence
- <0.5 = minority co-occurrence
- But these are only meaningful relative to your biological question and controls

---

## 9. ImageJ Macro Commands

### Complete Colocalization Workflow

```javascript
// === COLOCALIZATION ANALYSIS WORKFLOW ===
// Assumes a two-channel composite image is open

// 1. Record the image title
title = getTitle();

// 2. Split channels
run("Split Channels");

// 3. Subtract background from both channels
selectWindow("C1-" + title);
run("Subtract Background...", "rolling=50");
selectWindow("C2-" + title);
run("Subtract Background...", "rolling=50");

// 4. Optional: restrict to ROI
// Draw an ROI around the cell of interest, or create a binary mask

// 5. Check for saturation
selectWindow("C1-" + title);
getStatistics(area, mean, min, max);
if (max == 255) print("WARNING: Channel 1 is saturated!");

selectWindow("C2-" + title);
getStatistics(area, mean, min, max);
if (max == 255) print("WARNING: Channel 2 is saturated!");

// 6. Run Coloc 2
// NOTE: Use macro recorder to get exact syntax for your images
run("Coloc 2",
    "channel_1=[C1-" + title + "] "
  + "channel_2=[C2-" + title + "] "
  + "roi_or_mask=<None> "
  + "threshold_regression=Costes "
  + "display_images_in_result "
  + "psf=3 costes_randomisations=200");

// 7. Results are displayed in the Log window and as images
```

### Batch Colocalization (Multiple Images)

```javascript
// Process all .tif files in a directory
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

        // Run Coloc 2
        run("Coloc 2",
            "channel_1=[C1-" + title + "] "
          + "channel_2=[C2-" + title + "] "
          + "roi_or_mask=<None> "
          + "threshold_regression=Costes "
          + "psf=3 costes_randomisations=200");

        // Close images
        run("Close All");
    }
}
```

### Creating a Negative Control (90-Degree Rotation)

```javascript
// Rotate one channel 90 degrees as negative control
selectWindow("C1-image");
run("Duplicate...", "title=C1-rotated");
run("Rotate 90 Degrees Right");
// Now run Coloc 2 with C1-rotated vs C2-image
// PCC should be near 0
```

### Check for Bleedthrough (Single-Label Control)

```javascript
// Open single-label control image
// Split channels — the "empty" channel should have near-zero intensity
run("Split Channels");
selectWindow("C2-single_label_control");
getStatistics(area, mean, min, max, std);
print("Bleedthrough assessment:");
print("  Mean intensity in empty channel: " + mean);
print("  Max intensity in empty channel: " + max);
if (mean > 5) print("  WARNING: Possible bleedthrough detected!");
```

### Scatter Plot Generation

```javascript
// Create a scatter plot of two channels
// Use Coloc 2's built-in scatter plot, or:
run("Colocalization Threshold",
    "channel_1=[C1-image] channel_2=[C2-image] use=None");
// This generates a scatter plot with threshold lines
```

---

## 10. Key References

### Essential Reading (in order of importance)

1. **Dunn KW, Kamocka MM, McDonald JH (2011).** "A practical guide to evaluating
   colocalization in biological microscopy." *Am J Physiol Cell Physiol*
   300:C723-C742. PMID: 21209361.
   — The most comprehensive practical guide. Start here.

2. **Bolte S, Cordelieres FP (2006).** "A guided tour into subcellular
   colocalization analysis in light microscopy." *J Microsc* 224:213-232.
   PMID: 17210054.
   — Introduces JACoP plugin, reviews all methods, practical acquisition tips.

3. **Costes SV, Daelemans D, Cho EH, et al. (2004).** "Automatic and
   quantitative measurement of protein-protein colocalization in live cells."
   *Biophys J* 86:3993-4003.
   — The Costes auto-threshold and randomization test. Must-cite if using either.

4. **Manders EMM, Verbeek FJ, Aten JA (1993).** "Measurement of co-localization
   of objects in dual-colour confocal images." *J Microsc* 169:375-382.
   — Original M1/M2 and MOC definitions.

5. **Adler J, Parmryd I (2010).** "Quantifying colocalization by correlation:
   The Pearson correlation coefficient is superior to the Mander's overlap
   coefficient." *Cytometry A* 77:733-742. PMID: 20653013.
   — Why PCC > MOC. Important methodological argument.

6. **Adler J, Parmryd I (2021).** "Quantifying colocalization: The case for
   discarding the Manders overlap coefficient." *Cytometry A* 99:910-916.
   PMID: 33720475.
   — Updated argument against MOC. Introduces co-occurrence vs correlation framework.

7. **Li Q, Lau A, Morris TJ, et al. (2004).** "A syntaxin 1, Galpha(o), and
   N-type calcium channel complex at a presynaptic nerve terminal: analysis by
   quantitative immunocolocalization." *J Neurosci* 24:4070-4081.
   — Introduces ICQ and ICA methods.

8. **Van Steensel B, van Binnendijk EP, Hornsby CD, et al. (1996).**
   "Partial colocalization of glucocorticoid and mineralocorticoid receptors in
   discrete compartments in nuclei of rat hippocampus neurons."
   *J Cell Sci* 109:787-792.
   — Introduces the cross-correlation function (CCF) method.

9. **Stauffer W, Sheng H, Bhatt Lim HN (2018).** "EzColocalization: An ImageJ
   plugin for visualizing and measuring colocalization in cells and organisms."
   *Sci Rep* 8:15764.
   — EzColocalization plugin.

10. **Aaron JS, Taylor AB, Chew TL (2018).** "Image co-localization —
    co-occurrence versus correlation." *J Cell Sci* 131:jcs211847.
    — Critical conceptual paper distinguishing co-occurrence from correlation.

---

### Summary Cheat Sheet

```
ALWAYS DO:
  [x] Use confocal with sequential scanning
  [x] Split channels before analysis (never RGB)
  [x] Subtract background
  [x] Check for saturation (and re-acquire if saturated)
  [x] Use Costes auto-threshold (not manual)
  [x] Run Costes randomization (>=100 iterations)
  [x] Report PCC + tM1 + tM2 + Costes P-value
  [x] Measure multiple cells (n>=20-30)
  [x] Include single-label controls
  [x] Include negative control (rotation test)
  [x] Show scatter plots in figures
  [x] State microscope, objective, pixel size

NEVER DO:
  [ ] Analyse RGB/merged images
  [ ] Rely on visual "yellow = colocalized"
  [ ] Use MOC (discard it — use PCC + M1/M2)
  [ ] Set manual thresholds
  [ ] Report single-image coefficients without replication
  [ ] Claim molecular interaction from colocalization
  [ ] Skip background subtraction
  [ ] Acquire saturated images
  [ ] Skip statistical significance testing
  [ ] Compare conditions without proper statistics
```

---

---

## 11. Integration with Current Project (ImageJAI Agent)

### Lab Context (Brancaccio Lab, UK DRI)

**Typical colocalization questions:**
- Do amyloid plaques (MOAB-2/AF488) colocalize with activated microglia (Iba1)?
- Is apoptosis (Cas3) enriched in amyloid-associated regions?

**Image types:** Confocal z-stacks from .lif files (Leica), SCN brain region, 2/4/8 week timepoints

**Channel mapping:**
| Channel | Marker | Target |
|---------|--------|--------|
| Ch1 | MOAB-2 (AF488) | Amyloid-beta plaques |
| Ch2 | Iba1 (AF555/594) | Microglia |
| Ch3 | DAPI | Nuclei (not for colocalization) |

### Agent Workflow

```bash
# 1. Open multi-channel .lif
python ij.py macro 'run("Bio-Formats Importer", "open=[/path/to/file.lif] color_mode=Default view=Hyperstack stack_order=XYCZT");'

# 2. Check channels and histogram for saturation
python ij.py histogram
python ij.py capture before_coloc

# 3. Split channels
python ij.py macro 'run("Split Channels");'

# 4. Background subtraction (each channel independently)
python ij.py macro 'selectWindow("C1-..."); run("Subtract Background...", "rolling=50 stack");'
python ij.py macro 'selectWindow("C2-..."); run("Subtract Background...", "rolling=50 stack");'

# 5. Run Coloc 2 with Costes threshold
python ij.py macro 'run("Coloc 2", "channel_1=C1-... channel_2=C2-... roi_or_mask=<None> threshold_regression=Costes display_images_in_result costes_randomisations=200 psf=3");'

# 6. Read results
python ij.py log
```

### Installed Colocalization Plugins

| Plugin | Installed | Use case |
|--------|-----------|----------|
| **Coloc 2** | Yes | Primary — PCC, Manders, Costes |
| **3D MultiColoc** | Yes | 3D object colocalization |
| **Colocalization Finder** | Yes | Scatter plot exploration |
| **Colocalization Threshold** | Yes | Manual threshold colocalization |
| **Colocalization Test** | Yes | Statistical testing |
| **GDSC FindFoci** | Yes | Spot/puncta colocalization |

### Recipe

See `recipes/colocalization.yaml` for the complete automated workflow with
parameter templates, validation checks, and known issue handling.

---

*This reference compiled from: ImageJ wiki (imagej.net), PMC/PubMed literature,
SVI ColocalizationTheory, Image.sc Forum, Fiji Coloc 2 documentation, CellProfiler
documentation, and the primary research papers cited above.*
