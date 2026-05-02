# Statistical Analysis Workflow Reference

ImageJ measurements, Python statistical scripts, and integration workflow for the ImageJAI agent.

Covers the full ImageJ → Python → figures pipeline: ImageJ measurement
commands (`Set Measurements`, morphometric/intensity metrics, CTCF,
multi-channel redirect, stack/time-lapse, Coloc 2, Analyze Particles,
AnalyzeSkeleton, nearest-neighbour, ROI-based and batch measurements);
eight self-contained Python statistical scripts (two-group, multi-group,
paired, correlation, mixed-effects, proportion, dose-response, multiple-
comparisons correction); end-to-end integration workflow with decision
tree and common extraction patterns; Python package tiers; and a
reporting template.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code to set
measurements, segment, and measure.
`python ij.py results > .tmp/raw_results.csv` — export Results table.
`python stats_<script>.py <csv> <args> --output <plot.png>` — run a
stats script and write a figure.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "What keywords does `Set Measurements...` take?" | §2.1 |
| "What's the formula for Circularity / Solidity / Aspect Ratio?" | §2.2 |
| "What's the difference between IntDen and RawIntDen?" | §2.3 |
| "How do I compute CTCF (Corrected Total Cell Fluorescence)?" | §2.4 |
| "How do I segment on one channel and measure another?" | §2.5 |
| "How do I measure each slice / frame of a stack?" | §2.6 |
| "How do I run Coloc 2 and what does it output?" | §2.7 |
| "What options does `Analyze Particles` take?" | §2.8 |
| "How do I extract skeleton branch counts?" | §2.9 |
| "How do I compute nearest-neighbour distances?" | §2.10 |
| "How do I measure all ROIs / batch a folder?" | §2.11 |
| "Which Python packages are assumed / optional?" | §3.0, §5 |
| "Compare X between two groups (t-test / Mann-Whitney)?" | §3.1 |
| "Compare X across 3+ groups (ANOVA / Kruskal-Wallis)?" | §3.2 |
| "Before-vs-after on same subjects?" | §3.3 |
| "Does X correlate with Y?" | §3.4 |
| "Account for animal-level variability (mixed model / SuperPlot)?" | §3.5 |
| "Compare proportions (Fisher / chi-squared)?" | §3.6 |
| "Fit a dose-response curve / get EC50?" | §3.7 |
| "Correct p-values for multiple tests (Bonferroni / Holm / BH)?" | §3.8 |
| "End-to-end example: ImageJ → CSV → stats → figure?" | §4.1 |
| "Which script for which question?" | §4.2 |
| "Cell counting / CTCF / colocalization / skeleton extraction patterns?" | §4.3 |
| "What format to report stats in?" | §6 |
| "Master decision tree by data type and question?" | §7 |
| "What test for counts / proportions / survival?" | §7.3, §7.4, §7.5 |
| "Why is `n=300 cells from 3 mice` wrong?" | §8.1 |
| "What is a SuperPlot, how do I draw one?" | §9.1, §9.2 |
| "Worked `lmer` example with imaging structure?" | §10.1 |
| "Convergence / singular-fit warning — what next?" | §10.3 |
| "Power for `lmer` with `simr`?" | §11.2 |
| "FWER vs FDR — which correction when?" | §12.1 |
| "Image-wise correction (cluster / TFCE)?" | §12.3 |
| "Why is p alone not enough? Effect size + CI?" | §13.1 |
| "Saturated pixels skew my distribution?" | §14.1 |
| "Spatial / temporal autocorrelation in imaging?" | §14.2, §14.3 |
| "Below-detection / right-censored intensity?" | §14.4 |
| "R vs Python vs Prism — which tool when?" | §15.1 |
| "Worked end-to-end: wrong vs right, 3×5×10×50 cells?" | §16.1 |
| "Reviewer says n is too low / use non-parametric / etc.?" | §17.1 |

**Companion references** (do not duplicate; cross-link):
- `references/statistics-reference.md` — concept-level distributions, post-hoc decision trees, R/Python cheat sheets, transformation rules.
- `references/hypothesis-testing-microscopy-reference.md` — research-question → test mapping (Q1–Q36) and rigour/blinding pitfalls.
- `references/mixed-effects-models-reference.md` — formula syntax, REML vs ML, KR vs Satterthwaite, GLMM, `pymer4`.

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '<term>' statistical-analysis-workflow-reference.md` to jump.

### A
`Aarts 2014` §8.2 · `Analyze Particles` §2.8 · `Analyze Skeleton (2D/3D)` §2.9 ·
`ANOVA` §3.2, §7.2 · `ANOVA assumptions` §7.2 · `area` §2.1 ·
`Area (column)` §2.1, §2.2 · `area_fraction` §2.1 ·
`Aspect Ratio` §2.2 · `ASA 2016 statement` §13.1 · `auditor` §4.3 ·
`autocorrelation (spatial)` §14.2 · `autocorrelation (temporal)` §14.3

### B
`Batch Measurements` §2.11 · `BAR (Fiji)` §15.2 ·
`Benjamini-Hochberg (BH)` §3.8, §12.1 · `Bonferroni` §3.8, §12.1 ·
`bounding_box` §2.1 · `boundary effects (TFCE)` §12.3

### C
`censored data (left/right)` §14.4 · `center_of_mass` §2.1 ·
`centroid` §2.1 · `Chi-squared` §3.6, §7.4 · `Circ.` §2.1, §2.2 ·
`Circularity` §2.2 · `cliff's delta` §13.1 · `cluster correction` §12.3 ·
`Cohen's d` §11.1, §13.1 · `Cohen's dz` §3.3 · `Coloc 2` §2.7, §4.3 ·
`Confidence band (regression)` §3.4 · `Confidence interval (CI)` §13.1 ·
`Correlation` §3.4, §7.6 · `Costes` §2.7, §4.3 ·
`Cox proportional hazards` §7.5 · `CTCF` §2.4, §4.3 · `curve_fit` §3.7

### D
`decimal=N` §2.1 · `Decision tree (script chooser)` §4.2 ·
`Decision tree (test choice)` §7 · `Delacre & Lakens 2017` §7.1 ·
`Detection limit` §14.4 · `display (Set Measurements)` §2.1 ·
`Dose-Response` §3.7 · `Dunn's test` §3.2, §7.2

### E
`EC50` §3.7 · `Effect size` §3.1, §3.3, §6, §11.1, §13.1 ·
`emmeans` §10.2, §15.1 · `End-to-End Example` §4.1, §16.1 ·
`Eta-squared (partial)` §3.2, §11.1, §13.1

### F
`Family-wise error rate (FWER)` §12.1 · `feret's` §2.1, §2.2 ·
`Fisher's exact` §3.6, §7.4 · `fit_ellipse` §2.1 ·
`four_pl (Hill equation)` §3.7

### G
`G*Power` §11.1 · `GLMM (`glmer`)` §10.4 · `GLMM negative binomial` §7.3, §10.4 ·
`Group-wise vs cell-wise n` §8.1

### H
`Hazard ratio` §7.5 · `Hedges' g` §3.1, §13.1 · `Hierarchical data` §8, §10 ·
`High-content screening` §12.4 · `Hill equation` §3.7 ·
`Holm` §3.2, §3.8, §12.1 · `Hyperstack per-frame loop` §2.6

### I
`ICC (intraclass correlation)` §3.5, §10.1, §11.2 ·
`Image-wise corrections` §12.3 · `integrated_density` §2.1, §2.3, §2.4 ·
`IntDen` §2.1, §2.3, §2.4 · `Integration Workflow` §4 ·
`Interaction term (factorial ANOVA)` §7.2 ·
`Intraclass correlation` §10.1, §11.2

### K
`Kaplan-Meier` §7.5 · `Kendall's tau` §7.6 · `Kenward-Roger` §10.2 ·
`KR / Satterthwaite df` §10.2 · `Kruskal-Wallis` §3.2, §7.2 ·
`kurtosis` §2.1

### L
`Label (column)` §2.1, §4.1 · `Lazic 2010` §8.2 · `Left-censored` §14.4 ·
`Levene's test` §3.1 · `Li's ICQ` §2.7 · `lme4` §10 · `lmer` §10.1, §10.2 ·
`lmerTest` §10.2 · `Logistic regression` §7.4 · `Log-rank test` §7.5 ·
`Lord et al. 2020 (SuperPlots)` §9.1

### M
`Manders' M1/M2` §2.7, §4.3 · `Mann-Whitney U` §3.1, §7.1 ·
`Math.* / matplotlib.Agg backend` §3.0 · `mean` §2.1, §2.3 ·
`Measure (macro command)` §2.4, §2.6, §2.11 · `median` §2.1, §2.3 ·
`min / Max (gray)` §2.1, §2.3 · `Mixed-Effects Model` §3.5, §10 ·
`modal_gray / Mode` §2.1, §2.3 · `Morphometric Measurements` §2.2 ·
`Multi-Channel Measurement` §2.5 · `Multi-Group Comparison` §3.2 ·
`Multiple Comparisons Correction` §3.8, §12

### N
`Nearest Neighbor Distances` §2.10 · `Negative binomial GLM` §7.3, §10.4 ·
`Normality check (Shapiro/normaltest)` §3.1, §3.2, §3.3, §3.4

### O
`Odds ratio` §3.6, §11.1 · `Otsu (threshold)` §2.11, §4.1 ·
`Overdispersion` §7.3, §10.4

### P
`Package Detection` §3.0 · `Package Tiers` §5, §15.1 · `Paired comparison` §3.3 ·
`pandas` §3.0, §5 · `Pearson's r` §3.4, §7.6 · `Pearson's R (Coloc 2)` §2.7 ·
`perimeter` §2.1, §2.2 · `pingouin` §3.0, §3.1, §5, §15.1 ·
`Poisson GLM` §7.3, §10.4 · `Power analysis` §11 · `Pre-registration` §17.1 ·
`Prism (when to / not to use)` §15.3 · `Proportion Comparison` §3.6, §7.4 ·
`Pseudoreplication` §8 · `pymer4` §15.1 · `Python Package Tiers` §5, §15.1 ·
`Python Statistical Scripts` §3

### R
`Random intercept / random slope` §10.1 · `RawIntDen` §2.1, §2.3 ·
`redirect=ImageName` §2.1, §2.5, §4.3 · `Regression line` §3.4 ·
`Reporting Template` §6, §17.2 · `Reviewer pushback` §17.1 ·
`Right-censored` §14.4 · `roiManager` §2.4, §2.11 · `Roundness` §2.2 ·
`rstatix` §15.1

### S
`Saturation truncation` §14.1 · `Satterthwaite df` §10.2 ·
`Script 1: Two-Group` §3.1 · `Script 2: Multi-Group` §3.2 ·
`Script 3: Paired` §3.3 · `Script 4: Correlation` §3.4 ·
`Script 5: Mixed-Effects` §3.5 · `Script 6: Proportion` §3.6 ·
`Script 7: Dose-Response` §3.7 · `Script 8: Multiple Corrections` §3.8 ·
`Set Measurements` §2.1 · `setBatchMode` §2.11 ·
`shape_descriptors` §2.1, §2.2 · `Shapiro-Wilk` §3.1, §3.2, §3.3 ·
`Singular fit` §10.3 · `skewness` §2.1 · `Skeletonize (2D/3D)` §2.9, §4.3 ·
`simr (power simulation)` §11.2 · `Solidity` §2.2 · `Spatial autocorrelation` §14.2 ·
`Spearman's rho` §3.4, §7.6 · `stack_position` §2.1 · `Stack.setSlice / setFrame` §2.6 ·
`standard_deviation / StdDev` §2.1, §2.3 · `statsmodels` §3.0, §3.2, §5, §15.1 ·
`SuperPlot` §3.5, §9 · `Survival analysis` §7.5 · `Summary row (summarize)` §2.8

### T
`t-test (Welch's)` §3.1, §3.5, §7.1 · `t-test (paired)` §3.3, §7.1 ·
`Temporal autocorrelation` §14.3 · `TFCE (threshold-free cluster enhancement)` §12.3 ·
`Time-to-event` §7.5 · `Tukey HSD` §3.2, §7.2, §12.1 ·
`Two-Group Comparison` §3.1, §7.1 · `Type I/II error` §11.1

### U
`updateResults` §2.4 · `Unit of analysis` §8.1

### W
`Watershed` §2.11, §4.1 · `Welch's t-test` §3.1, §7.1 ·
`Westfall, Kenny, Judd 2014` §8.2 · `Wilcoxon signed-rank` §3.3, §7.1

### Z
`Z Project (Sum Intensity)` §2.4 · `Zenodo / OSF (data sharing)` §17.1

---

## §2 ImageJ Measurement Commands

### §2.1 Set Measurements — Keyword Reference

```javascript
// Enable all measurements
run("Set Measurements...",
    "area mean standard_deviation modal_gray min centroid "
  + "center_of_mass perimeter bounding_box fit_ellipse "
  + "shape_descriptors feret's integrated_density median "
  + "skewness kurtosis area_fraction stack_position "
  + "display redirect=None decimal=3");
```

| Keyword | Columns | Notes |
|---------|---------|-------|
| `area` | Area | Calibrated units if scale set |
| `mean` | Mean | Mean gray value in selection |
| `standard_deviation` | StdDev | SD of gray values |
| `modal_gray` | Mode | Most frequent gray value |
| `min` | Min, Max | Min/max gray values |
| `centroid` | X, Y | Geometric center |
| `center_of_mass` | XM, YM | Brightness-weighted center |
| `perimeter` | Perim. | Calibrated boundary length |
| `bounding_box` | BX, BY, Width, Height | Bounding rectangle |
| `fit_ellipse` | Major, Minor, Angle | Best-fit ellipse |
| `shape_descriptors` | Circ., AR, Round, Solidity | Shape metrics |
| `feret's` | Feret, FeretAngle, MinFeret, FeretX, FeretY | Caliper diameters |
| `integrated_density` | IntDen, RawIntDen | Sum of pixel values |
| `median` | Median | Median gray value |
| `skewness` | Skew | Distribution skewness |
| `kurtosis` | Kurt | Excess kurtosis |
| `area_fraction` | %Area | % non-zero pixels |
| `stack_position` | Slice | Slice number |
| `display` | Label | Image name as label |
| `redirect=ImageName` | — | Measure from a different image |
| `decimal=N` | — | Decimal places |

### §2.2 Morphometric Measurements

| Measurement | Formula / Range | Typical biological use |
|-------------|----------------|----------------------|
| **Area** | Calibrated (um^2) | Cell/nucleus/plaque size |
| **Perimeter** | Calibrated length | Boundary complexity |
| **Circularity** | 4*pi*Area/Perim^2; 0-1 | Round (0.7-0.9 neurons) vs elongated (0.1-0.3 microglia processes) |
| **Aspect Ratio** | Major/Minor; 1-inf | Cell elongation, fiber alignment |
| **Roundness** | 4*Area/(pi*Major^2); 0-1 | Like circularity, less boundary-sensitive |
| **Solidity** | Area/ConvexHull; 0-1 | Process complexity (low = many concavities) |
| **Feret's** | Max caliper diameter | Longest dimension; MinFeret = narrowest |

### §2.3 Intensity Measurements

| Measurement | What it is | Notes |
|-------------|-----------|-------|
| **Mean** | Average pixel intensity | Relative protein abundance; compare to controls |
| **StdDev** | SD of intensities | Texture heterogeneity (punctate vs diffuse) |
| **Min/Max** | Extremes | Max near saturation = unreliable data |
| **Median** | Median intensity | More robust than mean for skewed distributions |
| **Mode** | Most frequent value | Typically background intensity |
| **IntDen** | Mean * Area (calibrated) | Total fluorescence; affected by spatial calibration |
| **RawIntDen** | Sum of pixel values | Total fluorescence; not affected by calibration |

### §2.4 CTCF (Corrected Total Cell Fluorescence)

```javascript
// Measure cell ROI
run("Set Measurements...", "area mean integrated_density display redirect=None decimal=3");
roiManager("Select", cellIndex);
run("Measure");
intDen = getResult("IntDen", nResults-1);
area = getResult("Area", nResults-1);
// Measure background ROI
roiManager("Select", bgIndex);
run("Measure");
bgMean = getResult("Mean", nResults-1);
// CTCF = IntDen - (Area * bgMean)
ctcf = intDen - (area * bgMean);
setResult("CTCF", nResults-1, ctcf);
updateResults();
```

Use Sum Intensity Z-projection (not Max) for 3D. Never enhance contrast before measuring.

### §2.5 Multi-Channel Measurement (Redirect)

```javascript
// Segment on channel 1, measure intensity from channel 2
selectWindow("C1-mask");
run("Set Measurements...", "mean integrated_density display redirect=C2-imageName decimal=3");
run("Analyze Particles...", "size=50-Infinity display");
```

### §2.6 Stack/Time-Lapse Measurements

```javascript
// Per-slice
for (s = 1; s <= nSlices(); s++) { Stack.setSlice(s); run("Measure"); }
// Per-frame
getDimensions(w, h, channels, slices, frames);
for (f = 1; f <= frames; f++) { Stack.setFrame(f); run("Measure"); }
```

### §2.7 Coloc 2

```javascript
run("Split Channels");
run("Coloc 2",
    "channel_1=C1-image channel_2=C2-image roi_or_mask=<None> "
  + "threshold_regression=Costes psf=3 costes_randomisations=10 "
  + "display_images_in_result li_histogram_channel_1 li_histogram_channel_2 li_icq");
```

Output (Log window): Pearson's R, Manders' M1/M2, Li's ICQ, Costes p-value.

### §2.8 Analyze Particles

```javascript
run("Set Measurements...",
    "area mean centroid shape_descriptors feret's integrated_density display redirect=None decimal=3");
run("Analyze Particles...",
    "size=50-Infinity circularity=0.3-1.0 show=Outlines display summarize exclude");
```

| Option | Effect |
|--------|--------|
| `size=MIN-MAX` | Area filter (calibrated). `50-Infinity` skips noise |
| `circularity=MIN-MAX` | Shape filter. `0.0-1.0` = all shapes |
| `show=Outlines\|Masks\|Overlay` | Visualization |
| `display` | Individual measurements to Results |
| `summarize` | Summary row |
| `exclude` | Exclude edge-touching objects |
| `add` | Add ROIs to ROI Manager |

Particle count: `n = nResults;`

### §2.9 AnalyzeSkeleton

```javascript
run("Skeletonize (2D/3D)");
run("Analyze Skeleton (2D/3D)", "prune=[shortest branch] show display");
```

Output: # Branches, # Junctions, # End-point voxels, Average/Maximum Branch Length, Triple/Quadruple points.

### §2.10 Nearest Neighbor Distances

Best done in Python after exporting centroids:
```python
from scipy.spatial import distance
dists = distance.cdist(coords, coords)
np.fill_diagonal(dists, np.inf)
nn_dists = dists.min(axis=1)
```

### §2.11 ROI-Based and Batch Measurements

```javascript
// Measure all ROIs
roiManager("Deselect");
roiManager("Measure");

// Batch: measure all images in a folder
dir = "/path/to/images/"; list = getFileList(dir);
run("Set Measurements...", "area mean shape_descriptors integrated_density display redirect=None decimal=3");
setBatchMode(true);
for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif")) {
        open(dir + list[i]);
        run("Duplicate...", "title=mask");
        run("Gaussian Blur...", "sigma=1");
        setAutoThreshold("Otsu");
        run("Convert to Mask");
        run("Watershed");
        run("Analyze Particles...", "size=50-Infinity circularity=0.3-1.0 display exclude");
        close("mask"); close();
    }
}
setBatchMode(false);
saveAs("Results", "/path/to/output/all_results.csv");
```

---

## §3 Python Statistical Scripts

### §3.0 Package Detection (use at top of every script)

```python
import numpy as np
from scipy import stats
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

try:
    import pandas as pd; HAS_PANDAS = True
except ImportError: HAS_PANDAS = False
try:
    import statsmodels.api as sm; from statsmodels.stats.multitest import multipletests; HAS_STATSMODELS = True
except ImportError: HAS_STATSMODELS = False
try:
    import pingouin as pg; HAS_PINGOUIN = True
except ImportError: HAS_PINGOUIN = False
```

Check availability at session start:
```bash
python -c "
import sys; print(f'Python {sys.version}')
for p in ['numpy','scipy','matplotlib','pandas','statsmodels','pingouin','scikit_posthocs']:
    try: mod=__import__(p); print(f'  {p}: {getattr(mod,\"__version__\",\"?\")}'  )
    except: print(f'  {p}: NOT INSTALLED')
"
```

---

### §3.1 Script 1: Two-Group Comparison

Usage: `python stats_two_group.py results.csv "Area" "Group" --output plot.png`

```python
#!/usr/bin/env python
"""Two-group comparison: t-test or Mann-Whitney U with effect size and plot."""
import sys, csv, os, math
import numpy as np
from scipy import stats
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt
try: import pingouin as pg; HAS_PINGOUIN = True
except ImportError: HAS_PINGOUIN = False

def read_csv_groups(csv_path, value_col, group_col):
    groups = {}
    with open(csv_path, 'r') as f:
        for row in csv.DictReader(f):
            g = row[group_col].strip()
            try: groups.setdefault(g, []).append(float(row[value_col].strip()))
            except (ValueError, KeyError): continue
    names = sorted(groups.keys())
    if len(names) != 2: raise ValueError(f"Expected 2 groups, found {len(names)}: {names}")
    return names[0], np.array(groups[names[0]]), names[1], np.array(groups[names[1]])

def hedges_g(a, b):
    na, nb = len(a), len(b)
    pooled_sd = math.sqrt(((na-1)*np.var(a,ddof=1)+(nb-1)*np.var(b,ddof=1))/(na+nb-2))
    if pooled_sd == 0: return 0.0
    d = (np.mean(a)-np.mean(b))/pooled_sd
    return d * (1 - 3/(4*(na+nb)-9))

def two_group_test(csv_path, value_col, group_col, output_png=None):
    name1, g1, name2, g2 = read_csv_groups(csv_path, value_col, group_col)
    n1, n2 = len(g1), len(g2)
    results = [f"=== Two-Group Comparison: {value_col} by {group_col} ===",
               f"'{name1}': n={n1}, mean={np.mean(g1):.4f}, SD={np.std(g1,ddof=1):.4f}",
               f"'{name2}': n={n2}, mean={np.mean(g2):.4f}, SD={np.std(g2,ddof=1):.4f}", ""]

    # Normality + variance checks
    use_parametric = True
    if n1 >= 3 and n2 >= 3:
        _, p1 = stats.shapiro(g1) if n1 <= 5000 else stats.normaltest(g1)
        _, p2 = stats.shapiro(g2) if n2 <= 5000 else stats.normaltest(g2)
        results.append(f"Shapiro-Wilk: '{name1}' p={p1:.4f}, '{name2}' p={p2:.4f}")
        if p1 < 0.05 or p2 < 0.05: use_parametric = False
    else: use_parametric = False
    lev_stat, lev_p = stats.levene(g1, g2)
    results.append(f"Levene's: F={lev_stat:.4f}, p={lev_p:.4f}\n")

    # Tests
    if use_parametric:
        t_stat, t_p = stats.ttest_ind(g1, g2, equal_var=False)
        results.append(f"Welch's t-test: t={t_stat:.4f}, p={t_p:.6f}")
    u_stat, u_p = stats.mannwhitneyu(g1, g2, alternative='two-sided')
    results.append(f"Mann-Whitney U: U={u_stat:.1f}, p={u_p:.6f}")
    g_val = hedges_g(g1, g2)
    mag = "negligible" if abs(g_val)<0.2 else "small" if abs(g_val)<0.5 else "medium" if abs(g_val)<0.8 else "large"
    results.append(f"Hedges' g = {g_val:.4f} ({mag})")

    if HAS_PINGOUIN:
        try:
            r = pg.ttest(g1, g2, paired=False)
            results.append(f"BF10={r['BF10'].values[0]}, power={r['power'].values[0]:.4f}")
        except: pass

    primary_p = t_p if use_parametric else u_p
    primary_test = "Welch's t-test" if use_parametric else "Mann-Whitney U"
    results += ["", "=== SUMMARY ===", f"Test: {primary_test}, p = {primary_p:.6f}",
                f"Effect: g = {g_val:.4f} ({mag})"]

    # Plot: box + jitter
    if output_png is None: output_png = ".tmp/two_group_comparison.png"
    fig, ax = plt.subplots(figsize=(5,6))
    ax.boxplot([g1,g2], positions=[0,1], widths=0.5, patch_artist=True, showfliers=False,
               boxprops=dict(facecolor='lightblue',alpha=0.7), medianprops=dict(color='black',linewidth=2))
    for data, pos in [(g1,0),(g2,1)]:
        ax.scatter(pos+np.random.uniform(-0.15,0.15,len(data)), data, alpha=0.6, color='#2c3e50', s=30, zorder=3)
    ax.set_xticks([0,1]); ax.set_xticklabels([f"{name1}\n(n={n1})",f"{name2}\n(n={n2})"])
    ax.set_ylabel(value_col); ax.set_title(f"{value_col} by {group_col}")
    y_max=max(np.max(g1),np.max(g2)); y_range=y_max-min(np.min(g1),np.min(g2))
    by=y_max+0.05*y_range
    ax.plot([0,0,1,1],[by,by+0.02*y_range,by+0.02*y_range,by],'k-',linewidth=1.5)
    sig="***" if primary_p<0.001 else "**" if primary_p<0.01 else "*" if primary_p<0.05 else "n.s."
    ax.text(0.5,by+0.03*y_range,sig,ha='center',va='bottom',fontsize=14)
    ax.spines['top'].set_visible(False); ax.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if len(sys.argv) < 4: print("Usage: python stats_two_group.py <csv> <value_col> <group_col> [--output plot.png]"); sys.exit(1)
    out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
    two_group_test(sys.argv[1], sys.argv[2], sys.argv[3], out)
```

---

### §3.2 Script 2: Multi-Group Comparison (ANOVA / Kruskal-Wallis)

Usage: `python stats_multi_group.py results.csv "Area" "Treatment" --output plot.png`

```python
#!/usr/bin/env python
"""Multi-group comparison with post-hoc tests and grouped plot."""
import sys, csv, os, math, itertools
import numpy as np
from scipy import stats
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt
try: from statsmodels.stats.multicomp import pairwise_tukeyhsd; HAS_STATSMODELS = True
except ImportError: HAS_STATSMODELS = False

def read_csv_multi(csv_path, value_col, group_col):
    groups = {}
    with open(csv_path, 'r') as f:
        for row in csv.DictReader(f):
            try: groups.setdefault(row[group_col].strip(), []).append(float(row[value_col].strip()))
            except: continue
    return {k: np.array(v) for k, v in groups.items()}

def dunn_test(groups_dict):
    """Dunn's test with Holm correction."""
    all_data, all_labels = [], []
    for name, vals in groups_dict.items():
        all_data.extend(vals); all_labels.extend([name]*len(vals))
    all_data = np.array(all_data); all_labels = np.array(all_labels)
    ranks = stats.rankdata(all_data); N = len(all_data)
    names = sorted(groups_dict.keys())
    mean_ranks = {n: np.mean(ranks[all_labels==n]) for n in names}
    group_ns = {n: np.sum(all_labels==n) for n in names}
    _, counts = np.unique(all_data, return_counts=True)
    tie_corr = 1 - np.sum(counts**3-counts)/(N**3-N) if N > 1 else 1
    pairs = list(itertools.combinations(names, 2))
    p_values = []
    for a, b in pairs:
        se = math.sqrt((N*(N+1)/12)*(1/group_ns[a]+1/group_ns[b])*tie_corr)
        z = abs(mean_ranks[a]-mean_ranks[b])/se if se > 0 else 0
        p_values.append(2*(1-stats.norm.cdf(abs(z))))
    # Holm correction
    m = len(p_values); order = np.argsort(p_values); p_adj = np.ones(m)
    for rank, idx in enumerate(order): p_adj[idx] = min(p_values[idx]*(m-rank), 1.0)
    for i in range(1,m):
        if p_adj[order[i]] < p_adj[order[i-1]]: p_adj[order[i]] = p_adj[order[i-1]]
    return [{'group1':a,'group2':b,'p_adj':p_adj[i]} for i,(a,b) in enumerate(pairs)]

def multi_group_test(csv_path, value_col, group_col, output_png=None):
    groups = read_csv_multi(csv_path, value_col, group_col)
    names = sorted(groups.keys()); k = len(names)
    if k < 3: print("Only {k} groups — use two-group script."); return
    arrays = [groups[n] for n in names]
    results = [f"=== Multi-Group: {value_col} by {group_col} ({k} groups) ==="]
    for n in names:
        g = groups[n]; results.append(f"  '{n}': n={len(g)}, mean={np.mean(g):.4f}, SD={np.std(g,ddof=1):.4f}")

    # Normality + omnibus
    all_normal = all(stats.shapiro(groups[n])[1] >= 0.05 for n in names if len(groups[n]) >= 3)
    if all_normal:
        f_stat, f_p = stats.f_oneway(*arrays)
        results.append(f"\nANOVA: F={f_stat:.4f}, p={f_p:.6f}"); omnibus_p=f_p; omnibus_test="ANOVA"
        grand = np.mean(np.concatenate(arrays))
        eta = sum(len(g)*(np.mean(g)-grand)**2 for g in arrays)/sum(np.sum((g-grand)**2) for g in arrays)
        results.append(f"Eta-squared = {eta:.4f}")
    else:
        h_stat, h_p = stats.kruskal(*arrays)
        results.append(f"\nKruskal-Wallis: H={h_stat:.4f}, p={h_p:.6f}"); omnibus_p=h_p; omnibus_test="Kruskal-Wallis"

    # Post-hoc
    if omnibus_p < 0.05:
        results.append("\n--- Post-hoc ---")
        if HAS_STATSMODELS and all_normal:
            all_v = np.concatenate(arrays); all_l = sum([[n]*len(groups[n]) for n in names],[])
            results.append(str(pairwise_tukeyhsd(all_v, all_l, alpha=0.05)))
        else:
            for d in dunn_test(groups):
                sig = "***" if d['p_adj']<0.001 else "**" if d['p_adj']<0.01 else "*" if d['p_adj']<0.05 else "n.s."
                results.append(f"  {d['group1']} vs {d['group2']}: p_adj={d['p_adj']:.6f} {sig}")

    # Plot
    if output_png is None: output_png = ".tmp/multi_group_comparison.png"
    fig, ax = plt.subplots(figsize=(max(5,k*1.5),6))
    bp = ax.boxplot(arrays, positions=range(k), widths=0.5, patch_artist=True, showfliers=False,
                    medianprops=dict(color='black',linewidth=2))
    colors = plt.cm.Set2(np.linspace(0,1,k))
    for i, p in enumerate(bp['boxes']): p.set_facecolor(colors[i])
    for i, n in enumerate(names):
        d = groups[n]; ax.scatter(i+np.random.uniform(-0.15,0.15,len(d)), d, alpha=0.5, color='#2c3e50', s=20, zorder=3)
    ax.set_xticks(range(k)); ax.set_xticklabels([f"{n}\n(n={len(groups[n])})" for n in names], fontsize=9)
    ax.set_ylabel(value_col); ax.set_title(f"{value_col} by {group_col}\n{omnibus_test}: p={omnibus_p:.4f}")
    ax.spines['top'].set_visible(False); ax.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if len(sys.argv) < 4: print("Usage: python stats_multi_group.py <csv> <value_col> <group_col> [--output plot.png]"); sys.exit(1)
    out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
    multi_group_test(sys.argv[1], sys.argv[2], sys.argv[3], out)
```

---

### §3.3 Script 3: Paired Comparison

Usage: `python stats_paired.py results.csv "Before" "After" --output plot.png`

```python
#!/usr/bin/env python
"""Paired comparison: paired t-test or Wilcoxon signed-rank."""
import sys, csv, os, math
import numpy as np
from scipy import stats
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt

def read_paired(csv_path, col1, col2):
    v1, v2 = [], []
    with open(csv_path, 'r') as f:
        for row in csv.DictReader(f):
            try: v1.append(float(row[col1].strip())); v2.append(float(row[col2].strip()))
            except: continue
    return np.array(v1), np.array(v2)

def paired_test(csv_path, col1, col2, output_png=None):
    g1, g2 = read_paired(csv_path, col1, col2)
    n = len(g1); diffs = g1 - g2
    results = [f"=== Paired: {col1} vs {col2}, n={n} ===",
               f"Diffs: mean={np.mean(diffs):.4f}, SD={np.std(diffs,ddof=1):.4f}", ""]

    use_parametric = n >= 3 and (stats.shapiro(diffs)[1] if n <= 5000 else stats.normaltest(diffs)[1]) >= 0.05
    if use_parametric:
        t, p = stats.ttest_rel(g1, g2)
        results.append(f"Paired t-test: t({n-1})={t:.4f}, p={p:.6f}")
        primary_p, primary_test = p, f"Paired t-test (df={n-1})"
    if n >= 6:
        try:
            w, wp = stats.wilcoxon(g1, g2, alternative='two-sided')
            results.append(f"Wilcoxon signed-rank: W={w:.1f}, p={wp:.6f}")
            if not use_parametric: primary_p, primary_test = wp, "Wilcoxon"
        except ValueError: pass
    if np.std(diffs,ddof=1) > 0:
        dz = np.mean(diffs)/np.std(diffs,ddof=1)
        results.append(f"Cohen's dz = {dz:.4f}")

    # Plot: paired lines + difference histogram
    if output_png is None: output_png = ".tmp/paired_comparison.png"
    fig, (ax1,ax2) = plt.subplots(1,2,figsize=(10,6))
    for i in range(n):
        ax1.plot([0,1],[g1[i],g2[i]],'-o',color='#e74c3c' if g2[i]>g1[i] else '#3498db',alpha=0.5,markersize=6)
    ax1.set_xticks([0,1]); ax1.set_xticklabels([col1,col2]); ax1.set_ylabel("Value")
    ax2.hist(diffs,bins=max(5,n//3),color='steelblue',alpha=0.7,edgecolor='black')
    ax2.axvline(0,color='red',ls='--',lw=2); ax2.axvline(np.mean(diffs),color='green',ls='-',lw=2)
    ax2.set_xlabel(f"Difference ({col1} - {col2})")
    for a in [ax1,ax2]: a.spines['top'].set_visible(False); a.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if len(sys.argv) < 4: print("Usage: python stats_paired.py <csv> <col1> <col2> [--output plot.png]"); sys.exit(1)
    out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
    paired_test(sys.argv[1], sys.argv[2], sys.argv[3], out)
```

---

### §3.4 Script 4: Correlation Analysis

Usage: `python stats_correlation.py results.csv "Area" "Mean" --output plot.png`

```python
#!/usr/bin/env python
"""Correlation analysis with scatter plot, regression line, and confidence band."""
import sys, csv, os, math
import numpy as np
from scipy import stats
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt

def read_two_cols(csv_path, col_x, col_y):
    x, y = [], []
    with open(csv_path, 'r') as f:
        for row in csv.DictReader(f):
            try: x.append(float(row[col_x].strip())); y.append(float(row[col_y].strip()))
            except: continue
    return np.array(x), np.array(y)

def correlation_analysis(csv_path, col_x, col_y, output_png=None):
    x, y = read_two_cols(csv_path, col_x, col_y)
    n = len(x)
    results = [f"=== Correlation: {col_x} vs {col_y}, n={n} ==="]

    # Both correlations
    r_p, p_p = stats.pearsonr(x, y)
    r_s, p_s = stats.spearmanr(x, y)
    results.append(f"Pearson's r = {r_p:.4f}, p = {p_p:.6f}, R²={r_p**2:.4f}")
    if n > 3:
        z = np.arctanh(r_p); se = 1/math.sqrt(n-3)
        results.append(f"  95% CI: [{np.tanh(z-1.96*se):.4f}, {np.tanh(z+1.96*se):.4f}]")
    results.append(f"Spearman's rho = {r_s:.4f}, p = {p_s:.6f}")

    # Normality-based recommendation
    both_normal = all((stats.shapiro(v)[1] if n<=5000 else stats.normaltest(v)[1]) >= 0.05 for v in [x,y]) if n>=3 else False
    primary_r, primary_p = (r_p, p_p) if both_normal else (r_s, p_s)
    primary_name = "Pearson's r" if both_normal else "Spearman's rho"
    strength = "negligible" if abs(primary_r)<0.1 else "weak" if abs(primary_r)<0.3 else "moderate" if abs(primary_r)<0.5 else "strong" if abs(primary_r)<0.7 else "very strong"
    results.append(f"\nRecommended: {primary_name} = {primary_r:.4f} ({strength} {'positive' if primary_r>0 else 'negative'})")

    slope, intercept, _, _, se_slope = stats.linregress(x, y)
    results.append(f"Regression: y = {slope:.4f}*x + {intercept:.4f}")

    # Plot
    if output_png is None: output_png = ".tmp/correlation_analysis.png"
    fig, ax = plt.subplots(figsize=(7,6))
    ax.scatter(x, y, alpha=0.5, color='#2c3e50', s=30, zorder=3)
    xl = np.linspace(np.min(x), np.max(x), 100)
    ax.plot(xl, slope*xl+intercept, 'r-', lw=2, label=f'y={slope:.3f}x+{intercept:.3f}')
    # 95% CI band
    xm = np.mean(x); ssx = np.sum((x-xm)**2); mse = np.sum((y-(slope*x+intercept))**2)/(n-2) if n>2 else 0
    tc = stats.t.ppf(0.975,n-2) if n>2 else 1.96
    se_l = np.sqrt(mse*(1/n+(xl-xm)**2/ssx)) if ssx>0 else np.zeros_like(xl)
    ax.fill_between(xl, slope*xl+intercept-tc*se_l, slope*xl+intercept+tc*se_l, alpha=0.15, color='red')
    ax.set_xlabel(col_x); ax.set_ylabel(col_y)
    ax.set_title(f"{primary_name} = {primary_r:.3f}, p = {primary_p:.4f}")
    ax.text(0.05,0.95,f"$R^2$={r_p**2:.3f}\nn={n}",transform=ax.transAxes,fontsize=10,va='top',
            bbox=dict(boxstyle='round',facecolor='wheat',alpha=0.5))
    ax.spines['top'].set_visible(False); ax.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if len(sys.argv) < 4: print("Usage: python stats_correlation.py <csv> <x_col> <y_col> [--output plot.png]"); sys.exit(1)
    out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
    correlation_analysis(sys.argv[1], sys.argv[2], sys.argv[3], out)
```

---

### §3.5 Script 5: Mixed-Effects Model (Hierarchical Data)

Usage: `python stats_mixed.py results.csv "Mean" "Treatment" "Animal" --output plot.png`

```python
#!/usr/bin/env python
"""Mixed-effects model for hierarchical data (cells nested in animals)."""
import sys, csv, os, math
import numpy as np
from scipy import stats
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt
try: import statsmodels.formula.api as smf; import pandas as pd; HAS_MIXED = True
except ImportError: HAS_MIXED = False

def mixed_analysis(csv_path, value_col, group_col, rep_col, output_png=None):
    rows = []
    with open(csv_path, 'r') as f:
        for row in csv.DictReader(f):
            try: rows.append({'value':float(row[value_col].strip()),'group':row[group_col].strip(),'replicate':row[rep_col].strip()})
            except: continue

    groups, rep_vals = {}, {}
    for r in rows:
        groups.setdefault(r['group'],[]).append(r['value'])
        rep_vals.setdefault((r['group'],r['replicate']),[]).append(r['value'])
    names = sorted(groups.keys())
    rep_level = {g: np.array([np.mean(rep_vals[(g,r)]) for g2,r in rep_vals if g2==g]) for g in names}
    # Fix: build rep_level properly
    rep_level = {}
    for g in names:
        rep_level[g] = np.array([np.mean(v) for (gg,r),v in rep_vals.items() if gg==g])

    results = [f"=== Mixed-Effects: {value_col} ~ {group_col}, random: {rep_col} ==="]
    for g in names:
        nr = len(rep_level[g]); no = len(groups[g])
        results.append(f"  '{g}': {nr} bio reps, {no} obs, mean={np.mean(groups[g]):.4f}")

    # Mixed model (if available)
    if HAS_MIXED:
        try:
            df = pd.DataFrame(rows)
            fit = smf.mixedlm('value ~ C(group)', data=df, groups=df['replicate']).fit(reml=True)
            results.append(f"\n{fit.summary().as_text()}")
            vb = fit.cov_re.iloc[0,0]; vw = fit.scale
            results.append(f"ICC = {vb/(vb+vw):.4f}")
        except Exception as e:
            results.append(f"Mixed model failed: {e}")

    # Replicate-averaged fallback (always run)
    results.append("\n--- Replicate-Averaged ---")
    if len(names) == 2:
        g1m, g2m = rep_level[names[0]], rep_level[names[1]]
        if len(g1m)>=2 and len(g2m)>=2:
            t, p = stats.ttest_ind(g1m, g2m, equal_var=False)
            results.append(f"Welch's t on rep means: t={t:.4f}, p={p:.6f}")
    else:
        arrays = [rep_level[g] for g in names]
        if min(len(a) for a in arrays) >= 2:
            f, p = stats.f_oneway(*arrays)
            results.append(f"ANOVA on rep means: F={f:.4f}, p={p:.6f}")

    # SuperPlot
    if output_png is None: output_png = ".tmp/mixed_model_plot.png"
    fig, ax = plt.subplots(figsize=(max(5,len(names)*2),6))
    colors = plt.cm.Set2(np.linspace(0,1,8)); cidx = 0; cmap = {}
    for i, g in enumerate(names):
        ax.scatter(i+np.random.uniform(-0.2,0.2,len(groups[g])), groups[g], alpha=0.2, color='gray', s=10, zorder=2)
        for (gg,r),v in rep_vals.items():
            if gg != g: continue
            if r not in cmap: cmap[r]=colors[cidx%len(colors)]; cidx+=1
            ax.scatter(i, np.mean(v), color=cmap[r], s=120, zorder=4, edgecolors='black', linewidth=1.5)
        gm = np.mean(rep_level[g])
        gsem = np.std(rep_level[g],ddof=1)/math.sqrt(len(rep_level[g])) if len(rep_level[g])>1 else 0
        ax.errorbar(i, gm, yerr=gsem, fmt='_', color='black', linewidth=3, markersize=20, capsize=8, zorder=5)
    ax.set_xticks(range(len(names)))
    ax.set_xticklabels([f"{g}\nN={len(rep_level[g])},n={len(groups[g])}" for g in names], fontsize=9)
    ax.set_ylabel(value_col); ax.set_title(f"SuperPlot: {value_col} by {group_col}")
    ax.spines['top'].set_visible(False); ax.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    results.append("N = biological replicates; n = total observations")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if len(sys.argv) < 5: print("Usage: python stats_mixed.py <csv> <value> <group> <replicate> [--output plot.png]"); sys.exit(1)
    out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
    mixed_analysis(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], out)
```

---

### §3.6 Script 6: Proportion Comparison (Fisher's Exact / Chi-Squared)

Usage: `python stats_proportion.py --counts 45 55 30 70` or `python stats_proportion.py results.csv "Positive" "Group"`

```python
#!/usr/bin/env python
"""Proportion comparison: Fisher's exact or chi-squared."""
import sys, csv, os, math
import numpy as np
from scipy import stats
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt

def proportion_test(a, b, c, d, name1="Group1", name2="Group2", output_png=None):
    """2x2: Group1 a+/b-, Group2 c+/d-"""
    table = np.array([[a,b],[c,d]]); n1,n2 = a+b,c+d
    p1,p2 = a/n1 if n1>0 else 0, c/n2 if n2>0 else 0
    results = [f"=== Proportions: {name1} {a}/{n1}={p1:.1%}, {name2} {c}/{n2}={p2:.1%} ==="]

    odds_ratio, fisher_p = stats.fisher_exact(table)
    results.append(f"Fisher's exact: OR={odds_ratio:.4f}, p={fisher_p:.6f}")
    if a>0 and b>0 and c>0 and d>0:
        se = math.sqrt(1/a+1/b+1/c+1/d)
        results.append(f"  OR 95% CI: [{math.exp(math.log(odds_ratio)-1.96*se):.4f}, {math.exp(math.log(odds_ratio)+1.96*se):.4f}]")

    expected = np.outer(table.sum(axis=1), table.sum(axis=0))/table.sum()
    if np.all(expected >= 5):
        chi2, chi_p, dof, _ = stats.chi2_contingency(table)
        results.append(f"Chi-squared: chi2={chi2:.4f}, df={dof}, p={chi_p:.6f}")

    # Plot
    if output_png is None: output_png = ".tmp/proportion_comparison.png"
    fig, ax = plt.subplots(figsize=(5,6))
    ax.bar([0,1],[p1,p2],color='#3498db',label='Positive',width=0.5)
    ax.bar([0,1],[1-p1,1-p2],bottom=[p1,p2],color='#ecf0f1',edgecolor='#bdc3c7',label='Negative',width=0.5)
    ax.set_xticks([0,1]); ax.set_xticklabels([f"{name1}\n({a}/{n1})",f"{name2}\n({c}/{n2})"])
    ax.set_ylabel("Proportion"); ax.set_title(f"Fisher's: p={fisher_p:.4f}")
    ax.text(0,p1/2,f"{p1:.1%}",ha='center',va='center',fontweight='bold')
    ax.text(1,p2/2,f"{p2:.1%}",ha='center',va='center',fontweight='bold')
    ax.spines['top'].set_visible(False); ax.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if "--counts" in sys.argv:
        idx = sys.argv.index("--counts")
        a,b,c,d = int(sys.argv[idx+1]),int(sys.argv[idx+2]),int(sys.argv[idx+3]),int(sys.argv[idx+4])
        out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
        proportion_test(a,b,c,d,output_png=out)
    elif len(sys.argv) >= 4:
        groups = {}
        with open(sys.argv[1],'r') as f:
            for row in csv.DictReader(f):
                g = row[sys.argv[3]].strip(); pos = row[sys.argv[2]].strip().lower() in ('1','1.0','yes','true','positive')
                groups.setdefault(g, {'pos':0,'neg':0}); groups[g]['pos' if pos else 'neg'] += 1
        gn = sorted(groups.keys())
        if len(gn)==2:
            out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
            proportion_test(groups[gn[0]]['pos'],groups[gn[0]]['neg'],groups[gn[1]]['pos'],groups[gn[1]]['neg'],gn[0],gn[1],out)
```

---

### §3.7 Script 7: Dose-Response Curve

Usage: `python stats_dose_response.py results.csv "Concentration" "Response" --output plot.png`

```python
#!/usr/bin/env python
"""4-parameter logistic (Hill equation) dose-response fitting."""
import sys, csv, os
import numpy as np
from scipy import stats
from scipy.optimize import curve_fit
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt

def four_pl(x, bottom, top, ec50, hill):
    return bottom + (top-bottom)/(1+(ec50/np.clip(x,1e-15,None))**hill)

def dose_response(csv_path, dose_col, resp_col, output_png=None):
    doses, resps = [], []
    with open(csv_path,'r') as f:
        for row in csv.DictReader(f):
            try:
                d,r = float(row[dose_col].strip()), float(row[resp_col].strip())
                if d > 0: doses.append(d); resps.append(r)
            except: continue
    doses,resps = np.array(doses),np.array(resps)
    results = [f"=== Dose-Response, n={len(doses)} ==="]

    try:
        popt, pcov = curve_fit(four_pl, doses, resps,
            p0=[np.min(resps),np.max(resps),np.median(doses),1.0],
            bounds=([0,0,0,0.01],[np.inf,np.inf,np.inf,20]), maxfev=10000)
        bottom,top,ec50,hill = popt; perr = np.sqrt(np.diag(pcov))
        y_pred = four_pl(doses,*popt)
        r2 = 1-np.sum((resps-y_pred)**2)/np.sum((resps-np.mean(resps))**2)
        results += [f"Bottom={bottom:.4f}, Top={top:.4f}", f"EC50={ec50:.4g} +/- {perr[2]:.4g}",
                    f"Hill={hill:.4f}, R²={r2:.4f}"]
        fit_ok = True
    except Exception as e:
        results.append(f"Fit failed: {e}"); fit_ok = False

    if output_png is None: output_png = ".tmp/dose_response.png"
    fig, ax = plt.subplots(figsize=(7,5))
    ax.scatter(doses,resps,color='#2c3e50',alpha=0.6,s=40,zorder=3)
    if fit_ok:
        xf = np.logspace(np.log10(np.min(doses)*0.5),np.log10(np.max(doses)*2),200)
        ax.plot(xf,four_pl(xf,*popt),'r-',lw=2)
        ax.axvline(ec50,color='gray',ls='--',alpha=0.5)
        ax.annotate(f'EC50={ec50:.3g}',xy=(ec50,four_pl(ec50,*popt)),xytext=(ec50*3,four_pl(ec50,*popt)),
                    fontsize=10,arrowprops=dict(arrowstyle='->',color='red'))
    ax.set_xscale('log'); ax.set_xlabel(dose_col); ax.set_ylabel(resp_col)
    ax.set_title("Dose-Response (4PL)"); ax.spines['top'].set_visible(False); ax.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if len(sys.argv) < 4: print("Usage: python stats_dose_response.py <csv> <dose> <response> [--output plot.png]"); sys.exit(1)
    out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
    dose_response(sys.argv[1], sys.argv[2], sys.argv[3], out)
```

---

### §3.8 Script 8: Multiple Comparisons Correction

Usage: `python stats_multiple_corrections.py pvalues.csv "p_value" [effect_col] [label_col] --output plot.png`

```python
#!/usr/bin/env python
"""Multiple comparisons correction (Bonferroni, Holm, BH) with volcano plot."""
import sys, csv, os
import numpy as np
from scipy import stats
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt
try: from statsmodels.stats.multitest import multipletests; HAS_SM = True
except ImportError: HAS_SM = False

def correct_pvalues(p_values):
    p = np.array(p_values, dtype=float); m = len(p)
    if HAS_SM:
        _,p_bonf,_,_ = multipletests(p,method='bonferroni')
        _,p_holm,_,_ = multipletests(p,method='holm')
        _,p_bh,_,_ = multipletests(p,method='fdr_bh')
    else:
        p_bonf = np.minimum(p*m, 1.0)
        # Holm
        order = np.argsort(p); p_holm = np.ones(m)
        for rank, idx in enumerate(order): p_holm[idx] = min(p[idx]*(m-rank), 1.0)
        for i in range(1,m):
            if p_holm[order[i]] < p_holm[order[i-1]]: p_holm[order[i]] = p_holm[order[i-1]]
        # BH
        p_bh = np.ones(m)
        for r, idx in enumerate(order): p_bh[idx] = p[idx]*m/(r+1)
        for i in range(m-2,-1,-1):
            if p_bh[order[i]] > p_bh[order[i+1]]: p_bh[order[i]] = p_bh[order[i+1]]
        p_bh = np.minimum(p_bh, 1.0)
    return {'raw':p, 'bonferroni':p_bonf, 'holm':p_holm, 'benjamini_hochberg':p_bh}

def multiple_corrections(csv_path, p_col, effect_col=None, label_col=None, output_png=None):
    pvals, effects, labels = [], [], []
    with open(csv_path,'r') as f:
        for i, row in enumerate(csv.DictReader(f)):
            try: pvals.append(float(row[p_col].strip()))
            except: continue
            effects.append(float(row[effect_col].strip()) if effect_col and effect_col in row else 0)
            labels.append(row[label_col].strip() if label_col and label_col in row else f"Test_{i+1}")
    pvals = np.array(pvals); effects = np.array(effects); m = len(pvals)
    c = correct_pvalues(pvals)
    results = [f"=== Multiple Corrections ({m} tests) ===",
               f"{'Label':<20} {'p(raw)':>10} {'Bonf':>10} {'Holm':>10} {'BH':>10}","-"*62]
    for i in range(m):
        sig = "*" if c['benjamini_hochberg'][i]<0.05 else ""
        results.append(f"{labels[i]:<20} {pvals[i]:10.6f} {c['bonferroni'][i]:10.6f} {c['holm'][i]:10.6f} {c['benjamini_hochberg'][i]:10.6f} {sig}")
    results += ["",f"Sig at 0.05: raw={np.sum(pvals<0.05)}, Bonf={np.sum(c['bonferroni']<0.05)}, "
                f"Holm={np.sum(c['holm']<0.05)}, BH={np.sum(c['benjamini_hochberg']<0.05)}",
                "Consider Holm for confirmatory, BH for exploratory."]

    if output_png is None: output_png = ".tmp/multiple_corrections.png"
    fig, ax = plt.subplots(figsize=(8,5))
    order = np.argsort(pvals); x = np.arange(1,m+1)
    ax.scatter(x,pvals[order],label='Raw',alpha=0.7,s=30)
    ax.scatter(x,c['holm'][order],label='Holm',alpha=0.7,s=30)
    ax.scatter(x,c['benjamini_hochberg'][order],label='BH',alpha=0.7,s=30)
    ax.axhline(0.05,color='red',ls='--',alpha=0.5,label='alpha=0.05')
    ax.set_xlabel("Rank"); ax.set_ylabel("p-value"); ax.legend(fontsize=9)
    ax.spines['top'].set_visible(False); ax.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if len(sys.argv) < 3: print("Usage: python stats_multiple_corrections.py <csv> <p_col> [effect_col] [label_col] [--output plot.png]"); sys.exit(1)
    effect = sys.argv[3] if len(sys.argv)>3 and not sys.argv[3].startswith("--") else None
    label = sys.argv[4] if len(sys.argv)>4 and not sys.argv[4].startswith("--") else None
    out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
    multiple_corrections(sys.argv[1], sys.argv[2], effect, label, out)
```

---

## §4 Integration Workflow

### §4.1 End-to-End Example

```bash
# 1. Check state
python ij.py state

# 2. Set measurements
python ij.py macro 'run("Set Measurements...", "area mean centroid shape_descriptors integrated_density display redirect=None decimal=3");'

# 3. Segment and measure each image
python ij.py macro 'open("/path/to/WT_mouse1.tif");'
python ij.py capture after_open
python ij.py macro '
  run("Duplicate...", "title=mask"); run("Gaussian Blur...", "sigma=1");
  setAutoThreshold("Otsu"); run("Convert to Mask"); run("Watershed");
  run("Analyze Particles...", "size=50-Infinity circularity=0.3-1.0 display exclude");
  close("mask"); close();
'

# 4. Save and label results
python ij.py results > .tmp/raw_results.csv
python ij.py macro 'saveAs("Results", "/path/.tmp/results.csv");'

# 5. Add group labels (parse from filename)
python -c "
import csv, re
with open('.tmp/raw_results.csv') as f: rows = list(csv.DictReader(f))
with open('.tmp/labeled_results.csv','w',newline='') as f:
    flds = list(rows[0].keys())+['Group','Animal']
    w = csv.DictWriter(f,fieldnames=flds); w.writeheader()
    for r in rows:
        lbl = r.get('Label','')
        r['Group'] = 'WT' if 'WT' in lbl else 'KO' if 'KO' in lbl else 'unknown'
        m = re.search(r'mouse(\d+)', lbl)
        r['Animal'] = m.group(0) if m else 'unknown'
        w.writerow(r)
"

# 6. Run stats
python stats_mixed.py .tmp/labeled_results.csv "Area" "Group" "Animal" --output .tmp/area_mixed.png

# 7. Read figure and report
```

### §4.2 Decision Tree

| User question | Script | Key measurement |
|--------------|--------|-----------------|
| "Compare X between 2 groups" | `stats_two_group.py` | Any |
| "Compare X across 3+ groups" | `stats_multi_group.py` | Any |
| "Before vs after (same subjects)" | `stats_paired.py` | Any |
| "Does X correlate with Y?" | `stats_correlation.py` | Two continuous |
| "What proportion are positive?" | `stats_proportion.py` | Binary count |
| "What's the EC50?" | `stats_dose_response.py` | Dose + response |
| "Multiple tests done" | `stats_multiple_corrections.py` | p-values |
| "Account for animal variability" | `stats_mixed.py` | Any + replicate ID |
| "How many cells?" | Analyze Particles (nResults) | Count |
| "How branched?" | AnalyzeSkeleton | Branches, length |
| "How close together?" | Centroids + scipy NND | Distance |
| "Colocalized?" | Coloc 2 + log parsing | PCC, M1, M2 |

### §4.3 Common Extraction Patterns

```bash
# Cell counting
python ij.py macro '... Analyze Particles ... display summarize ...'
python ij.py results   # count = number of rows

# CTCF (redirect trick)
python ij.py macro '
  run("Set Measurements...", "area mean integrated_density display redirect=C2-original decimal=3");
  selectWindow("C1-mask");
  run("Analyze Particles...", "size=50-Infinity display exclude add");
'
# Calculate CTCF in Python from IntDen, Area, background Mean

# Colocalization
python ij.py macro 'run("Split Channels"); run("Coloc 2", "channel_1=C1-image channel_2=C2-image threshold_regression=Costes psf=3 costes_randomisations=10");'
python ij.py log   # parse PCC, M1, M2, Costes p

# Skeleton
python ij.py macro 'run("Skeletonize (2D/3D)"); run("Analyze Skeleton (2D/3D)", "prune=[shortest branch] show display");'
python ij.py results
```

---

## §5 Python Package Tiers

| Tier | Packages | What they provide |
|------|----------|-------------------|
| **1 (always)** | numpy, scipy, matplotlib | All t-tests, Mann-Whitney, ANOVA, Kruskal-Wallis, chi-sq, Fisher, correlations, Shapiro-Wilk, curve_fit, manual Holm/BH, all plots |
| **2 (typical)** | pandas, csv (stdlib) | DataFrame ops, CSV I/O, groupby |
| **3 (install)** | statsmodels | Mixed-effects, Tukey HSD, multipletests, Type II/III ANOVA, power analysis |
| **3 (install)** | pingouin | Clean API, auto effect sizes, Bayes factors, ICC, repeated-measures ANOVA |

Install: `pip install statsmodels pingouin` (suggest but do not install without asking).

---

## §6 Reporting Template

```
ANALYSIS: [Brief description]
DATA: Group1 N=[bio reps], n=[obs], mean +/- SD | Group2 ...
TEST: [Full name] (e.g., Welch's t-test)
STATISTIC: [with df] (e.g., t(8) = 3.42)
P-VALUE: [exact]
EFFECT SIZE: [name = value, interpretation]
95% CI: [lower, upper]
CONCLUSION: [One sentence]
FIGURE: [path]
CAVEATS: [small n, non-normality, pseudoreplication, etc.]
```

---

## §7 Master Decision Tree — Choosing the Right Test by Data Type and Question

Sister-reference cross-link: a research-question-driven version (Q1–Q36 imaging
scenarios) lives in `references/hypothesis-testing-microscopy-reference.md §2`.
A concept-level decision tree lives in `references/statistics-reference.md §20`.
The tree below is **data-type-first** — pick the row that matches your outcome
variable, then descend.

```
What is the OUTCOME variable?

CONTINUOUS  → §7.1 (two-group)  /  §7.2 (3+ groups, factorial)
COUNTS      → §7.3 (Poisson / NB GLM)
PROPORTIONS → §7.4 (chi-sq / Fisher / McNemar / logistic)
SURVIVAL    → §7.5 (Kaplan-Meier / log-rank / Cox PH)
TWO CONTINUOUS (relationship) → §7.6 (Pearson / Spearman / Kendall)
HIERARCHICAL  (cells in animals, slices in batches) → §10 mixed model
SPATIAL FIELD (per-pixel maps)  → §12.3 cluster / TFCE
```

### §7.1 Two-Group Continuous

```
Continuous outcome, TWO groups
│
├── Independent samples
│   ├── Paired samples? ────── NO ──┐
│   │                                │
│   ├── ~normal residuals (n>=10 or Shapiro p>0.05 + QQ plot OK)?
│   │   ├── YES → Welch's t-test  (DEFAULT — see Delacre & Lakens 2017)
│   │   │           reason: equal-variance Student's t fails badly when
│   │   │           variances differ; Welch is identical when they don't,
│   │   │           safer when they do.
│   │   └── NO  → Mann-Whitney U  (tests stochastic dominance, NOT medians
│   │             unless distributions are identical in shape)
│   └── Effect size: Hedges' g (n<20) or Cohen's d (n>=20). Cliff's delta
│       for non-parametric.
│
└── Paired samples (same subject before/after, same animal two regions,
    same culture two channels)
    ├── Differences ~normal? ── YES → paired t-test (df = n_pairs - 1)
    │                           NO  → Wilcoxon signed-rank
    └── Effect size: Cohen's dz = mean(diffs)/SD(diffs)
```

**Welch's-by-default rationale (Delacre, Lakens & Leys 2017, _IRSP_)**:
- If variances are equal, Student's and Welch's are equivalent (within rounding).
- If variances differ even moderately (ratio ~1.5–2), Student's t inflates
  Type I error to 7–10%; Welch's stays at 5%.
- Pre-testing variance equality (Levene + branch) is *worse* than always
  using Welch — adds a second decision and inflates error.
- **Rule**: in code, default to `equal_var=False` (scipy) /
  `var.equal=FALSE` (R is the default).

**One-tailed vs two-tailed**: only one-tailed if (a) the opposite direction
is biologically impossible AND (b) it was pre-registered. Otherwise two-tailed.

**Common errors**:
- Reporting `mean ± SEM` and calling it the spread of the data — SEM is the
  precision of the mean, not the variability. Show SD (or 95% CI) for spread.
- Using a paired test when subjects are not actually paired across rows —
  scipy's `ttest_rel` blindly pairs `a[i]` with `b[i]`; sort errors silently
  give wrong answers.

### §7.2 Three-or-More Groups, Factorial

```
Continuous outcome, 3+ groups
│
├── ONE-WAY (single factor)
│   ├── ~normal & equal variance → ANOVA (`aov`/`f_oneway`)
│   │      └── significant → all-pairwise: Tukey HSD
│   │                       vs control: Dunnett (more powerful)
│   │                       pre-planned: Holm
│   ├── Unequal variance       → Welch's ANOVA → Games-Howell post-hoc
│   └── Non-normal             → Kruskal-Wallis → Dunn's (Holm-adjusted)
│                                                Conover (more powerful)
│
├── TWO-WAY / FACTORIAL (genotype × time, dose × cell-type)
│   ├── Balanced            → `aov(y ~ A * B)` Type I SS is fine
│   ├── Unbalanced          → Type III SS with sum contrasts (R: `Anova(., type=3)`,
│   │                          set `options(contrasts=c("contr.sum","contr.poly"))`)
│   ├── Interaction term    → if `A:B` is significant, do not interpret main
│   │                          effects without simple-effects analysis
│   │                          (`emmeans(model, ~A|B)` or `pairwise|B`)
│   └── If hierarchical (cells nested in animals) → mixed model (§10)
│
└── REPEATED MEASURES (same subject, multiple conditions)
    ├── Sphericity OK (Mauchly p>0.05) → RM-ANOVA
    ├── Sphericity violated → Greenhouse-Geisser corrected df
    └── Non-normal → Friedman → Nemenyi/Conover post-hoc
```

**When NOT to use ANOVA**:
- Nested / hierarchical data (cells in animals, wells in plates) →
  mixed-effects model (§10).
- Count outcomes with mean ≈ variance → Poisson GLM (§7.3).
- Proportions / binary outcomes → logistic regression (§7.4).
- Time-to-event with censoring → survival models (§7.5).
- Three-or-more time points with autocorrelation → mixed model with
  `(time | subject)` rather than RM-ANOVA.

### §7.3 Counts and Rates

Imaging count outcomes: cells per field, plaques per section, branches per
neuron, vesicles per axon. Counts are NOT continuous — using a t-test/ANOVA
on them works for large means but is biased for small means (predicts
negative values, wrong variance structure).

| Distribution | Variance | Use when | Test / model |
|---|---|---|---|
| Poisson | var = mean | Rare independent events | `glm(count ~ trt + offset(log(area)), family=poisson)` |
| Negative binomial | var = mean + α·mean² | Overdispersed (clustered, contagious) | `MASS::glm.nb`, `statsmodels.NegativeBinomial` |
| Quasi-Poisson | var = φ·mean | Mild overdispersion | `glm(..., family=quasipoisson)` |
| Zero-inflated | excess zeros | Many empty fields + a count process | `pscl::zeroinfl` |

**Detecting overdispersion**: fit Poisson, then compute `residual deviance /
residual df`. If > 1.5, the data are overdispersed → switch to negative
binomial. Or use `performance::check_overdispersion(model)` in R.

```r
library(MASS)
# Cells per field, normalised to area; treatment effect on rate
fit_pois <- glm(count ~ treatment + offset(log(area_mm2)), family=poisson, data=df)
sum(residuals(fit_pois, type="pearson")^2) / df.residual(fit_pois)  # >>1 = bad

fit_nb <- glm.nb(count ~ treatment + offset(log(area_mm2)), data=df)
anova(fit_pois, fit_nb)  # likelihood-ratio test for overdispersion
```

```python
import statsmodels.api as sm
import statsmodels.formula.api as smf
fit = smf.glm("count ~ C(treatment)", data=df,
              family=sm.families.NegativeBinomial(),
              offset=np.log(df.area_mm2)).fit()
```

**Always include an `offset(log(exposure))`** when fields/sections differ in
size — otherwise you are testing absolute counts, not densities.

### §7.4 Proportions and Binary Outcomes

| Design | Test |
|---|---|
| 2×2, large counts (all cells expected ≥ 5) | Chi-squared |
| 2×2, small counts | **Fisher's exact** (always valid; default for n < 20 per cell) |
| Paired binary (same subject yes/no before-and-after) | **McNemar's test** |
| 2×k contingency | Chi-squared with `df = k-1` |
| Predictors continuous or multiple | Logistic regression `glm(y ~ x, family=binomial)` |
| Hierarchical proportions (positive cells per animal) | Mixed-effects logistic `glmer(y ~ x + (1|animal), family=binomial)` |

**Common error**: comparing two proportions by averaging "% positive" per
animal and t-testing the means. This treats a bounded variable (0–100%)
as continuous, ignores the binomial denominator, and is wrong when
denominators differ. Use Fisher / chi-squared at the cell level **with
animal as a random effect**, or aggregate to per-animal proportions and
do a Welch's t — but report both.

```r
# Paired binary: did the same cell respond before vs after?
mcnemar.test(matrix(c(a, b, c, d), 2, 2))

# Per-cell logistic with random animal effect
library(lme4)
fit <- glmer(positive ~ treatment + (1|animal), data=df, family=binomial)
summary(fit)
```

### §7.5 Survival / Time-to-Event

Use when the outcome is *time until X happens* (cell death, division,
fluorescence loss, behavioural failure) AND some events are censored
(experiment ended before they happened).

| Test | Question | Notes |
|---|---|---|
| Kaplan-Meier curve | Visualise survival probability over time | Step function, drops at each event |
| Log-rank test | Are two/more curves different? | Equal-weight; use Wilcoxon if early differences matter more |
| Cox proportional hazards | Effect of covariates on hazard | Assumes hazards proportional over time — check with `cox.zph` |
| AFT (accelerated failure time) | Same Q, parametric distribution | When PH assumption fails |

```r
library(survival); library(survminer)
fit <- survfit(Surv(time, event) ~ treatment, data=df)
ggsurvplot(fit, pval=TRUE, risk.table=TRUE)            # KM + log-rank p
cox <- coxph(Surv(time, event) ~ treatment + age, data=df)
summary(cox)                                            # hazard ratio + CI
cox.zph(cox)                                            # check PH
```

```python
from lifelines import KaplanMeierFitter, CoxPHFitter
kmf = KaplanMeierFitter().fit(df.time, df.event); kmf.plot_survival_function()
from lifelines.statistics import logrank_test
logrank_test(df.time[df.trt=="A"], df.time[df.trt=="B"],
             df.event[df.trt=="A"], df.event[df.trt=="B"]).p_value
cph = CoxPHFitter().fit(df, duration_col="time", event_col="event",
                         formula="treatment + age")
cph.print_summary()
```

**Censoring conventions**: `event = 1` if event observed, `0` if censored.
A cell that survived to end-of-imaging is censored, NOT zero-survival.
Treating censored cells as having survived "exactly until last frame" biases
estimates downward.

### §7.6 Correlation

| Method | Relationship measured | Use when |
|---|---|---|
| Pearson r | Linear, both Gaussian | Continuous, linear, no outliers, both variables ~normal |
| Spearman ρ | Monotonic | Non-normal, outliers present, ordinal data — **default for fluorescence intensity** |
| Kendall τ | Monotonic, more robust to ties | Small n (< 30), many ties; pairs more interpretable |
| Distance correlation | Any dependence (linear or not) | Suspected non-monotonic relationship |
| Mutual information | Any dependence (information-theoretic) | Discretized data, machine-learning prep |

**Imaging gotcha**: pixel-level correlations (e.g. between two channels) are
biased high by spatial autocorrelation — neighbouring pixels are not
independent. For colocalization use **Pearson on whole-image with
Costes-thresholded ROI**, *not* a per-pixel scatter (Coloc 2 does this
correctly; see `references/colocalization-reference.md`).

**Non-monotonic relationships** (e.g. dose-response with peak at intermediate
dose) are missed by all three of Pearson/Spearman/Kendall. Use distance
correlation (`energy::dcor` in R, `dcor` in Python) or a structural model.

---

## §8 Pseudoreplication — The Most Common Imaging Error

**Cross-link**: high-level pitfall list in `references/statistics-reference.md
§9, §21`; mitigation patterns in `references/hypothesis-testing-microscopy-reference.md
§5.1`. This section gives the *theory* and a numerical demonstration of why
it matters.

### §8.1 Imaging Hierarchy and the Unit of Analysis

```
Subject (animal / patient / donor / culture batch)   ← biological replicate, n
│   randomised to treatment HERE
├── Slice / well / plate / coverslip                  ← technical replicate
│   ├── Field of view (tile)
│   │   ├── Cell / nucleus / particle
│   │   │   ├── ROI (per-cell sub-region)
│   │   │   │   └── Pixel
```

The **unit of analysis** is the lowest level at which the treatment was
*independently* applied. If 3 mice were assigned to drug and 3 to vehicle,
**N = 6 (not 600 cells)** — every cell in the same mouse shares everything
about that mouse: genetics, age, perfusion, fixation, mounting, imaging
session.

A common shortcut: **average within each animal first, then test on the
animal-level means**. This is always valid but throws away within-animal
information (variation across cells/fields). The mixed model in §10 keeps
the within-animal variation while still treating animal as the replicate.

### §8.2 Why It Matters — Numerical Demonstration

A toy simulation that shows how pseudoreplication inflates the false-positive
rate when there is *no real effect* (data adapted from Lazic 2010):

```python
import numpy as np
from scipy import stats

rng = np.random.default_rng(42)
N_SIMS = 5000
n_animals_per_group = 3
n_cells_per_animal = 50
animal_sd = 1.0       # variation between animals
cell_sd   = 0.5       # variation between cells WITHIN an animal

wrong_pvals, right_pvals = [], []
for _ in range(N_SIMS):
    # H0 is true: no group effect
    g1 = []; g2 = []
    for a in range(n_animals_per_group):
        a1_mean = rng.normal(0, animal_sd); a2_mean = rng.normal(0, animal_sd)
        g1.extend(rng.normal(a1_mean, cell_sd, n_cells_per_animal))
        g2.extend(rng.normal(a2_mean, cell_sd, n_cells_per_animal))
    g1, g2 = np.array(g1), np.array(g2)
    # WRONG: treat all cells as independent, n_per_group = 150
    wrong_pvals.append(stats.ttest_ind(g1, g2, equal_var=False).pvalue)
    # RIGHT: average per animal, n_per_group = 3
    means1 = g1.reshape(n_animals_per_group, -1).mean(axis=1)
    means2 = g2.reshape(n_animals_per_group, -1).mean(axis=1)
    right_pvals.append(stats.ttest_ind(means1, means2, equal_var=False).pvalue)

print(f"WRONG (cells as n): false-positive rate = {np.mean(np.array(wrong_pvals)<0.05):.1%}")
print(f"RIGHT (animal mean): false-positive rate = {np.mean(np.array(right_pvals)<0.05):.1%}")
# Typical output:
#   WRONG: ~37%        RIGHT: ~5%
# Expected at α=0.05 is 5%. The wrong analysis lies seven-fold.
```

**The result is robust** — it's not about t-tests specifically. Any test
that ignores hierarchy gives the same inflation. The size of the inflation
scales with the intraclass correlation (ICC = animal variance / total
variance). For typical imaging data ICC = 0.2–0.6.

### §8.3 The Four Errors and Their Fixes

| Error | What it looks like | Why it's wrong | Fix |
|---|---|---|---|
| **Pooled t-test on all cells** | "n = 300 cells, p < 0.001" | Inflates n, ignores animal variance | Average per animal, OR mixed model with `(1|animal)` |
| **Average-then-no-replication** | 1 mouse/group, 100 cells/mouse, t-test on cells | Truly only N=2 — no biological replication | Need ≥3 animals per group; cells alone never replicate animals |
| **Fixed-effect dummy for animal** | `lm(y ~ trt + animal)` with animal as fixed factor | Treats animals as the only animals of interest; can't generalise | `lmer(y ~ trt + (1|animal))` — random effect generalises to the population |
| **"Average per cell across animals"** | Pool every cell into one big group, then average | Loses the link between cells and their animals | Keep the hierarchy; aggregate or model |

### §8.4 Key References

- **Lazic 2010** — "The problem of pseudoreplication in neuroscientific
  studies: is it affecting your analysis?" *BMC Neurosci* 11:5. The original
  imaging-aware exposition with worked examples.
- **Aarts et al. 2014** — "A solution to dependency: using multilevel
  analysis to accommodate nested data." *Nat Neurosci* 17(4):491. Mixed-
  models prescription for nested data.
- **Lord, Velle, Mullins, Fritz 2020** — "SuperPlots: Communicating
  reproducibility and variability in cell biology." *J Cell Biol* 219(6):
  e202001064. Visualisation standard, see §9.
- **Westfall, Kenny & Judd 2014** — "Statistical power and optimal design
  in experiments in which samples of participants respond to samples of
  stimuli." *J Exp Psychol Gen* 143(5):2020. The general theory of
  crossed-random-effects designs.
- **Eisner 2021** — "Pseudoreplication in physiology: more means less."
  *J Gen Physiol* 153(2):e202012826. Imaging-cardiology angle.

---

## §9 SuperPlots — Visualising Hierarchical Data Honestly

A SuperPlot (Lord et al. 2020) overlays per-observation points with per-
biological-replicate summary points so the reader simultaneously sees the
full data spread *and* the actual sample size. **Use it whenever your data
has more than one observation per biological replicate.**

### §9.1 Anatomy of a SuperPlot

A correct SuperPlot has THREE layers:

1. **Background swarm** — every cell as a small grey dot (jittered).
2. **Replicate means** — one large coloured dot per biological replicate
   (animal/experiment), coloured *consistently* across groups so reader
   can track the same replicate across conditions.
3. **Group summary** — black bar/line at the mean of replicate means with
   95% CI or SEM-of-replicate-means as error bar.

Statistics are run on **layer 2 (replicate means)** or via mixed model;
layer 1 is descriptive only.

### §9.2 SuperPlot in Python (matplotlib)

```python
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt

def superplot(df, value, group, replicate, ax=None, jitter=0.15, palette=None):
    """SuperPlot: cells as grey dots, replicate means as coloured dots, group mean as bar.

    df         long DataFrame
    value      column with the per-cell measurement (numeric)
    group      column with treatment/condition (categorical)
    replicate  column with biological-replicate ID (animal, experiment)
    """
    if ax is None: fig, ax = plt.subplots(figsize=(5, 6))
    groups = df[group].astype(str).unique().tolist()
    reps   = df[replicate].astype(str).unique().tolist()
    if palette is None:
        cmap = plt.get_cmap("tab10"); palette = {r: cmap(i % 10) for i, r in enumerate(reps)}

    rep_mean_table = []
    for i, g in enumerate(groups):
        sub = df[df[group] == g]
        # layer 1: cells (grey dots)
        x = i + np.random.uniform(-jitter, jitter, len(sub))
        ax.scatter(x, sub[value], color="#999", s=12, alpha=0.4, zorder=2)
        # layer 2: replicate means (coloured dots)
        for r, rsub in sub.groupby(replicate):
            m = rsub[value].mean()
            ax.scatter(i, m, color=palette[r], s=140, edgecolor="black",
                       linewidth=1.2, zorder=4, label=r if i == 0 else None)
            rep_mean_table.append({"group": g, "replicate": r, "mean": m, "n_cells": len(rsub)})
        # layer 3: group summary (mean of replicate means + SEM)
        rep_means = pd.DataFrame(rep_mean_table)
        gm = rep_means[rep_means["group"] == g]["mean"]
        if len(gm) >= 2:
            sem = gm.std(ddof=1) / np.sqrt(len(gm))
            ax.errorbar(i, gm.mean(), yerr=sem, fmt="_", color="black",
                        markersize=28, linewidth=2.5, capsize=10, zorder=5)
    ax.set_xticks(range(len(groups))); ax.set_xticklabels(groups)
    ax.set_ylabel(value)
    ax.spines["top"].set_visible(False); ax.spines["right"].set_visible(False)
    ax.legend(title=replicate, fontsize=8, frameon=False, loc="best")
    return ax, pd.DataFrame(rep_mean_table)
```

The `pd.DataFrame` returned (replicate means) is the right input to a
group-level test (`ttest_ind`/`f_oneway` on the `mean` column grouped by
`group`).

### §9.3 SuperPlot in R (ggplot2)

```r
library(ggplot2); library(dplyr)
rep_means <- df %>% group_by(group, replicate) %>%
  summarise(mean_value = mean(value), n = n(), .groups = "drop")

ggplot(df, aes(x = group, y = value)) +
  # layer 1: cells
  geom_jitter(width = 0.18, alpha = 0.3, size = 1, colour = "grey60") +
  # layer 2: replicate means (one colour per replicate)
  geom_point(data = rep_means, aes(y = mean_value, colour = replicate),
             size = 5, position = position_dodge(width = 0.0)) +
  # layer 3: group summary
  stat_summary(data = rep_means, aes(y = mean_value),
               fun = mean, geom = "crossbar", width = 0.5, colour = "black") +
  stat_summary(data = rep_means, aes(y = mean_value),
               fun.data = mean_se, geom = "errorbar", width = 0.2, colour = "black") +
  theme_classic() +
  labs(y = "Measured value", x = NULL, colour = "Biological replicate")
```

The `SuperPlotsofData` Shiny app (https://huygens.science.uva.nl/SuperPlotsOfData/)
generates these from a CSV upload — useful for a quick check.

### §9.4 What a Wrong vs Right SuperPlot Looks Like

| Reader cue | Bad plot | SuperPlot |
|---|---|---|
| Sample size shown | Bar + SEM only — n could be 3 or 3000 | Replicate dots make N obvious at a glance |
| Within-replicate spread | Hidden | Grey swarm reveals it |
| Replicate consistency | Hidden | One colour per replicate exposes outlier replicates |
| Test interpretation | Reader can't tell what was tested | Black bar shows what the test compared |

### §9.5 SuperPlot ≠ Statistics

A SuperPlot is a *plot*. The statistical test underneath should still be:
- a t-test/ANOVA on **replicate means** (layer 2), OR
- a mixed model with replicate as random effect, marginal means via
  `emmeans::emmeans(model, ~group)` for the bars.

If you draw a SuperPlot but report a p-value from a per-cell t-test, you've
plotted hierarchy but tested as if it didn't exist.

---

## §10 Mixed-Effects Models — Imaging Patterns

**Cross-link**: full formula syntax, REML/ML, KR vs Satterthwaite, GLMM, and
`pymer4` lives in `references/mixed-effects-models-reference.md`. This
section gives **imaging-specific worked patterns** that map common
microscopy designs to `lmer` formulae.

### §10.1 Random Intercepts vs Random Slopes

```
Random INTERCEPT only — (1 | animal)
  Each animal has a different baseline level of the outcome,
  but the treatment effect is assumed identical across animals.
  Use when: treatment is between-animal (each animal sees one condition),
            or you have no reason to expect treatment effect to vary by animal.

Random SLOPE — (treatment | animal)  ≡  (1 + treatment | animal)
  Each animal has its own baseline AND its own treatment effect.
  Use when: treatment varies WITHIN animal (each animal sees multiple doses
            or before/after), AND you have ≥ ~5 animals so the slope variance
            is estimable.
  Identifiability: if every animal sees only one level, the slope is
  unidentifiable — drop it.

Uncorrelated random intercept + slope — (1 | animal) + (0 + dose | animal)
  Same as `(dose || animal)`. Removes the correlation parameter; helps with
  convergence when full model is singular.
```

### §10.2 The Five Common Imaging Designs

| Design | Imaging example | `lmer` formula |
|---|---|---|
| 1. Cells in animals, treatment between-animal | Drug vs vehicle, 3 mice/group, 50 cells/mouse | `lmer(y ~ treatment + (1|animal))` |
| 2. Cells in slices in animals (3 levels) | Drug vs vehicle, 5 mice/group, 5 slices/mouse, 30 cells/slice | `lmer(y ~ treatment + (1|animal/slice))` (= `(1|animal) + (1|animal:slice)`) |
| 3. Repeated dose within animal | Same animal sees 4 doses, multiple cells per dose | `lmer(y ~ dose + (dose|animal))` |
| 4. Crossed: animal × imaging session | Each animal imaged in multiple sessions, sessions shared across animals | `lmer(y ~ trt + (1|animal) + (1|session))` |
| 5. Time course + treatment | Treatment groups, multiple time points per cell | `lmer(y ~ treatment * time + (time|cell) + (1|animal))` |

### §10.3 Worked Example — Designs 1 and 2

```r
library(lme4); library(lmerTest); library(emmeans); library(performance)

# Read the cell-level CSV. Required columns:
#   value     - per-cell measurement (e.g. CTCF, area)
#   treatment - factor: drug / vehicle / ...
#   animal    - factor: m01, m02, ...
#   slice     - factor: m01_s1, m01_s2 ...   (globally unique slice IDs)
df <- read.csv("AI_Exports/cells_labeled.csv", stringsAsFactors = TRUE)

# Design 1: cells in animals
fit1 <- lmer(value ~ treatment + (1 | animal), data = df, REML = TRUE)
summary(fit1)               # Satterthwaite df + p-values via lmerTest
anova(fit1)                 # Type-III F-test for treatment
icc(fit1)                   # performance::icc — between-animal share of variance
emmeans(fit1, pairwise ~ treatment, adjust = "tukey")  # post-hoc

# Design 2: cells in slices in animals
fit2 <- lmer(value ~ treatment + (1 | animal) + (1 | slice), data = df)
# Note: writing (1 | animal/slice) is equivalent IF slice IDs are not unique
# across animals. With globally-unique IDs, the explicit form is safer.

# 95% CI for fixed effects (profile-based, slow but accurate)
confint(fit1, method = "profile", parm = "beta_")

# Kenward-Roger df (more conservative for small N)
library(pbkrtest)
summary(fit1, ddf = "Kenward-Roger")
```

```python
# Python equivalent via statsmodels (random intercept only — for crossed
# random effects use pymer4, which wraps R's lme4)
import statsmodels.formula.api as smf
fit = smf.mixedlm("value ~ C(treatment)", data=df, groups=df["animal"]).fit(
        method="lbfgs", reml=True)
print(fit.summary())
# ICC = group variance / (group variance + residual)
icc = fit.cov_re.iloc[0, 0] / (fit.cov_re.iloc[0, 0] + fit.scale)
```

### §10.4 Convergence and Singular Fits

`lmer` warnings to know:

| Warning | Meaning | Fix |
|---|---|---|
| `boundary (singular) fit: see ?isSingular` | One variance component is estimated as 0 — likely no variance at that level | Simplify the random structure (drop that term); often safe to ignore if it's a slope variance |
| `Model failed to converge with max\|grad\|` | Optimiser stopped before reaching optimum | Try `control = lmerControl(optimizer="bobyqa", optCtrl=list(maxfun=2e5))` |
| `Hessian is numerically singular` | Random structure too complex for the data | Drop slopes, then drop intercepts at smaller groupings |
| `degenerate Hessian with X negative eigenvalues` | Same as above | Same |

**Hierarchical simplification rule**: when the model fails to converge,
drop terms in this order:
1. Correlation between random intercept and slope (`(x || animal)` instead
   of `(x | animal)`).
2. Random slopes (`(1 | animal)` only).
3. Lowest-level random intercept (e.g. drop `slice`, keep `animal`).

If even `(1 | animal)` won't converge, the data are too sparse for a
mixed model — fall back to averaging within animal and t-testing on
the means.

### §10.5 GLMM — When Outcomes Aren't Continuous

```r
library(lme4)
# Counts (cells per field, cells nested in animals)
glmer(count ~ treatment + (1 | animal), data = df, family = poisson,
      offset = log(area_mm2))

# Overdispersed counts → negative binomial via glmmTMB
library(glmmTMB)
glmmTMB(count ~ treatment + (1 | animal), data = df,
        family = nbinom2, offset = log(area_mm2))

# Binary (positive yes/no per cell)
glmer(positive ~ treatment + (1 | animal), data = df, family = binomial)

# Proportions with denominator (k positive of n_total per field)
glmer(cbind(k, n - k) ~ treatment + (1 | animal), data = df, family = binomial)
```

GLMMs estimate fixed effects on the **link scale** (log for Poisson, logit
for binomial). To report effects on the natural scale:

```r
emmeans(fit, ~ treatment, type = "response")  # back-transformed means + CI
```

---

## §11 Power Analysis — Practical Sample-Size Planning

**Cross-link**: concept-level table in `references/statistics-reference.md
§12`. This section walks through *how to actually do* a power calculation
for an imaging experiment.

### §11.1 The Four-Way Trade-off

Sample size n, effect size, α (Type I), and power (1-β, Type II) are linked:
fix any three, the fourth follows.

| Effect-size convention (Cohen 1988) | Small | Medium | Large |
|---|---|---|---|
| Cohen's d (two means) | 0.2 | 0.5 | 0.8 |
| Pearson r (correlation) | 0.1 | 0.3 | 0.5 |
| Cohen's f (ANOVA) | 0.1 | 0.25 | 0.4 |
| Partial η² (ANOVA) | 0.01 | 0.06 | 0.14 |
| Odds ratio | 1.5 | 2.5 | 4.0 |

These are *social-science* conventions. In imaging, a 30% intensity
difference often corresponds to d ≈ 0.7–1.0, KO/WT comparisons d ≈ 1.5–2.5.
Use **prior data** if available; Cohen's bands are last-resort defaults.

```r
library(pwr)
# Two-group t-test
pwr.t.test(d = 0.8, power = 0.80, sig.level = 0.05, type = "two.sample")
# n = 26 per group

# One-way ANOVA with k groups
pwr.anova.test(k = 4, f = 0.25, power = 0.80, sig.level = 0.05)

# Correlation
pwr.r.test(r = 0.4, power = 0.80, sig.level = 0.05)
```

```python
from statsmodels.stats.power import TTestIndPower, FTestAnovaPower
TTestIndPower().solve_power(effect_size=0.8, alpha=0.05, power=0.80)  # ~26
FTestAnovaPower().solve_power(effect_size=0.25, k_groups=4,
                               alpha=0.05, power=0.80)
```

**G*Power** (https://www.psychologie.hhu.de/arbeitsgruppen/allgemeine-psychologie-und-arbeitspsychologie/gpower)
is a free GUI for the same calculations, with menu-driven test selection
and curves. Use it for one-off planning and screenshot the result for
grants.

### §11.2 Power for Mixed Models — Use `simr`

Closed-form power formulas don't apply to `lmer`/`glmer`. The right
approach is **simulation**: assume a hypothesised effect size and variance
structure, simulate datasets, fit the model, count significant outcomes.

```r
library(simr)

# Start from a small pilot model (real or hand-built)
fit_pilot <- lmer(value ~ treatment + (1 | animal), data = pilot_df)

# Set the fixed effect of interest to the hypothesised difference
fixef(fit_pilot)["treatmentdrug"] <- 0.5    # in units of the outcome

# Power for the existing N
powerSim(fit_pilot, nsim = 500)              # 500 simulated datasets

# Extend to more animals and recompute
fit_ext <- extend(fit_pilot, along = "animal", n = 10)   # 10 animals/group
powerSim(fit_ext, nsim = 500)

# Power curve over a range of N
pc <- powerCurve(fit_ext, along = "animal", breaks = c(3, 5, 7, 10, 15))
plot(pc)
```

**Key insight from `simr` simulations**: doubling cells per animal usually
gains < 5% power; doubling animals gains 20–40%. Cells are cheap; animals
are the lever.

### §11.3 ICC and the "Effective Sample Size"

For nested data, the *effective* sample size (ESS) is smaller than the
total cell count by a *design effect*:

```
DEFF = 1 + (cells_per_animal - 1) * ICC
ESS  = total_cells / DEFF
```

Example: 5 animals × 50 cells = 250 cells. With ICC = 0.3:
`DEFF = 1 + 49 * 0.3 = 15.7`, so ESS ≈ 16. Adding more cells per animal
only helps if ICC is small. If ICC > 0.5, you essentially have N = number
of animals; cells are nearly redundant.

### §11.4 Anti-Patterns

- **Post-hoc / "observed" power** — calculating power from your own p-value
  is circular and uninformative. Report effect size + CI instead.
- **Round n up to whole animals** — power tables give fractional n; always
  round UP, never down.
- **Power = 0.80 is not sacred** — for irreversible interventions
  (transgenic line) aim higher (0.90–0.95); for cheap pilots 0.70 is fine.
- **Picking effect size to make the n feasible** — backwards. Decide the
  smallest effect of biological interest first, then the n follows.

---

## §12 Multiple Comparisons — From Pairwise to Image-Wise

**Cross-link**: pairwise correction methods (Holm, Tukey, BH) and decision
table in `references/statistics-reference.md §10`. This section adds the
*image-wise* corrections that arise when the test is run at every voxel/
pixel/region of an image.

### §12.1 Pairwise: FWER vs FDR

```
What is the cost of a false positive?
│
├── Confirmatory / mechanistic claim → control FAMILY-WISE error rate (FWER)
│       Holm (always; uniformly more powerful than Bonferroni)
│       Tukey HSD (built into ANOVA all-pairwise)
│       Dunnett (built into ANOVA vs control; more powerful than Tukey
│                when only vs-control comparisons are needed)
│
└── Exploratory / screening → control FALSE DISCOVERY RATE (FDR)
        Benjamini-Hochberg (BH) — the default for genomics, high-content
                                  screening, and any "many tests, few
                                  expected hits" setting
        Benjamini-Yekutieli — more conservative; use if tests are
                              positively dependent and you need that proof
```

| Setting | Tests | Method |
|---|---|---|
| 3 pre-planned group comparisons | 3 | Holm |
| All-pairwise after one-way ANOVA | k(k-1)/2 | Tukey HSD |
| All-vs-control after one-way ANOVA | k-1 | Dunnett |
| 200 protein candidates | 200 | BH at q = 0.05 |
| 60,000 voxels in a brain map | 60,000 | Cluster correction or TFCE (§12.3), not BH |

### §12.2 Holm vs Bonferroni

Bonferroni: divide α by m. Holm: sort p-values ascending, then test the
smallest against α/m, the next against α/(m-1), … the largest against α.
Holm is **uniformly more powerful** than Bonferroni and controls FWER under
the same conditions. There is no scenario where Bonferroni is preferable —
use Holm.

### §12.3 Image-wise Corrections (Per-Voxel Testing)

When you run a per-pixel/per-voxel test (e.g. a t-map across a brain
section, or a per-pixel two-channel correlation), pairwise corrections
fail because:
- m is huge (tens of thousands).
- Tests are spatially correlated (neighbouring pixels are not independent),
  so Bonferroni is overly conservative AND BH's independence assumption is
  violated.
- The biology of interest is *clusters* of significant pixels, not
  individual pixels.

**Cluster-extent correction**: pick an uncorrected pixel-wise threshold
(usually p < 0.001), then keep only contiguous clusters of significant
pixels larger than a critical size. Critical size derived from random
permutation of group labels:

```
1. Run the per-pixel test on real labels → get a t-map.
2. Threshold at p < 0.001 → get observed clusters (size = pixel count).
3. Permute group labels 1000 times. Each permutation:
     re-run the per-pixel test, threshold at p < 0.001,
     record the SIZE of the largest cluster.
4. Critical cluster size = 95th percentile of the null distribution
   from step 3. Real clusters bigger than that survive correction.
```

**TFCE — threshold-free cluster enhancement** (Smith & Nichols 2009): a
voxel's score is enhanced by integrating evidence across all possible
threshold heights weighted by cluster size. Avoids the arbitrary p < 0.001
choice. Implementations: FSL `randomise`, `nilearn`, MNE-Python
`mne.stats.permutation_cluster_test`.

```python
# Cluster-permutation correction in MNE (works for any 2D/3D image stack)
from mne.stats import permutation_cluster_test
T_obs, clusters, p_cluster, H0 = permutation_cluster_test(
    [group_a_stack, group_b_stack],   # arrays: (n_subjects, H, W)
    n_permutations=1000, threshold=None,         # threshold=None → TFCE
    tail=0, n_jobs=-1, out_type="indices")
```

These corrections are standard in fMRI/EEG and increasingly in light-sheet
brain mapping. For 2D fluorescence images, cluster correction is
appropriate when you compute a per-pixel statistic across animals at the
same anatomical position (after registration — see
`references/brain-atlas-registration-reference.md`).

### §12.4 High-Content Screening

In a 384-well screen with thousands of compound–phenotype combinations, the
relevant correction is FDR, but with two refinements:
- **Z-prime / robust z-score per plate** to absorb plate-batch effects
  before testing.
- **BH with q = 0.05** as the discovery cut-off, not p < 0.05.
- Per-plate normalisation (median absolute deviation) before pooling.

Hit-list reporting must include the q-value (BH-adjusted p) alongside the
raw p so downstream readers can judge the discovery rate.

---

## §13 Effect Sizes and CIs — Why p Alone Is Insufficient

### §13.1 The ASA 2016 Statement

Wasserstein & Lazar 2016 (American Statistical Association) listed six
principles. The two that matter most for imaging:

1. *p-values do not measure the size of an effect or the importance of a
   result.* A 0.0001% difference in nuclear area can have p < 0.001 with
   n = 10,000 cells. It still doesn't matter biologically.
2. *Proper inference requires full reporting and transparency.* Always
   report effect size + CI in addition to p.

| What p tells you | What it doesn't tell you |
|---|---|
| Are the data unlikely under H₀? | How big is the difference? |
| Should I reject H₀? | How precise is my estimate? |
| Discrete decision at α | The biological importance |

### §13.2 Effect Size — Pick by Test

| Test | Effect size | Function |
|---|---|---|
| Two-group t / Welch | Cohen's d, Hedges' g | `effectsize::hedges_g` (R), `pingouin.compute_effsize(eftype='hedges')` (py) |
| Paired t | Cohen's dz | `mean(diffs) / sd(diffs)` |
| One-way ANOVA | Partial η², ω² | `effectsize::eta_squared(model, partial=TRUE)` |
| Two-way ANOVA | Partial η² per factor | same |
| Mixed model | R² (marginal/conditional) | `performance::r2(fit)` (Nakagawa-Schielzeth) |
| Mann-Whitney | Cliff's δ, rank-biserial | `effectsize::cliffs_delta`, `pingouin.compute_effsize(eftype='r')` |
| Correlation | r itself | already an effect size |
| 2×2 contingency | Odds ratio, Cohen's h, φ | `epitools::oddsratio`, `effectsize::oddsratio` |
| Logistic / Poisson GLM | OR / IRR with 95% CI | `exp(confint(fit))` |
| Survival | Hazard ratio | `summary(coxph_fit)$conf.int` |

### §13.3 Confidence Intervals

Always report a 95% CI for the effect size. If the CI crosses zero, the
direction of the effect is uncertain regardless of p. Width tells you
precision: a wide CI with a small p often means n is small AND variability
is large — a single replication could change everything.

```r
library(effectsize)
hedges_g(value ~ group, data = df, ci = 0.95)
# returns g and 95% CI

confint(lmer_fit, method = "profile", parm = "beta_")
# CIs for fixed effects in a mixed model
```

```python
import pingouin as pg
pg.ttest(a, b)  # returns columns: T, dof, p-val, CI95% (for the difference),
                # cohen-d, BF10, power
```

### §13.4 Bayesian Alternatives

Frequentist NHST can't quantify evidence *for* the null. Bayes factors
can:

```r
library(BayesFactor)
bf <- ttestBF(x = group_a, y = group_b)
# BF10 > 3 = moderate evidence for difference
# BF10 < 1/3 = moderate evidence for null
1 / bf  # BF01 = evidence for null
```

For "are these two methods equivalent?" use TOST (two one-sided tests) —
see `references/hypothesis-testing-microscopy-reference.md §3` for the
formula.

---

## §14 Imaging-Specific Statistical Gotchas

### §14.1 Saturated-Pixel Truncation

When pixel intensity hits the ADC ceiling (255 in 8-bit, 4095 in 12-bit,
65535 in 16-bit), the camera reports the ceiling, not the true value. Any
distribution mean / IntDen / CTCF computed from saturated regions is
**biased downward** for the saturated condition.

**Detection**:
```python
# Before measurement, check saturation rate per channel
import numpy as np
def saturation_rate(arr, max_val=None):
    if max_val is None: max_val = np.iinfo(arr.dtype).max
    return float((arr >= max_val).mean())
```
```javascript
// ImageJ macro
getStatistics(area, mean, min, max);
n_saturated = 0;
for (y=0; y<getHeight(); y++) for (x=0; x<getWidth(); x++)
    if (getPixel(x,y) >= 65535) n_saturated++;
print("Saturation: " + (100*n_saturated/area) + "%");
```

**Rules**:
- > 1% of foreground pixels saturated → re-acquire with lower exposure.
  Censor or exclude is a workaround, not a fix.
- Do NOT compare a saturated bright group to a non-saturated dim group.
  The bright group's mean is artificially compressed.
- Set acquisition exposure on the *brightest* sample, not the dimmest, and
  lock it for the whole batch.

### §14.2 Spatial Autocorrelation

Adjacent pixels are not independent — they share point-spread function
support, share fixation/staining, share whatever organelle or cell they
belong to. Treating per-pixel measurements as N independent samples
inflates n by 10–1000×.

| Symptom | Cause | Fix |
|---|---|---|
| Pearson r between channels p < 10⁻²⁰ | Spatial autocorrelation; n_pixels is not n_independent | Use Coloc 2 (per-cell PCC, Costes-thresholded), test on per-cell values |
| Per-pixel t-map gives "everything is significant" | Same | Cluster correction / TFCE (§12.3) |
| Low r but tight scatter cloud | Pixel non-independence inflated significance | Aggregate to ROI/cell, then test |

**Quantifying it** — Moran's I gives a 1-number autocorrelation index for
spatial data; values near 0 are random, near 1 are highly autocorrelated.
Most fluorescence images have Moran's I in the range 0.4–0.9 at the pixel
scale.

### §14.3 Time-Series Autocorrelation

Frame-to-frame measurements (intensity traces, tracking) are autocorrelated:
the value at frame t is similar to the value at frame t+1. Treating frames
as independent observations inflates n by the autocorrelation length
(typically 5–50 frames for live-cell imaging).

**Detection**: ACF (autocorrelation function) plot. If the lag-1 ACF is
> 0.5, the series is strongly autocorrelated.

```python
from statsmodels.tsa.stattools import acf
ac = acf(trace, nlags=50)
print("lag-1 ACF =", ac[1])
import matplotlib.pyplot as plt
from statsmodels.graphics.tsaplots import plot_acf
plot_acf(trace, lags=50)
```

**Fixes**:
- Test summary statistics per trace (peak amplitude, peak frequency, slope)
  rather than every frame.
- Use ARIMA / state-space models that include the autocorrelation term.
- Use mixed model with `(time | cell)` random slope to absorb the slow
  drift.

### §14.4 Censored and Below-Detection Data

Fluorescence below the detection limit is *left-censored* — you know it's
< X but not what it actually is. Substituting 0 or LOD/2 biases means and
SDs.

| Situation | Censoring | Fix |
|---|---|---|
| Cell intensity < background, set to 0 | Left-censored at LOD | Tobit regression, or `survival::survreg` with `Surv(value, event=as.numeric(value > LOD), type="left")` |
| Time-to-division but experiment ended | Right-censored | Survival analysis (§7.5) |
| Pixel saturation | Right-censored at ADC max | Robust statistics (median, trimmed mean) or re-acquire |

The `NADA` R package implements maximum-likelihood estimators (`cenfit`,
`cenken`) for left-censored environmental-style data, applicable to
detection-limited fluorescence.

### §14.5 Post-Threshold Variance Distortion

After thresholding (Otsu, manual), all sub-threshold pixels are forced to
0 and supra-threshold pixels are kept (or forced to 255). The resulting
distribution is *not* the underlying biological distribution — variance is
artificially inflated (bimodal) and means are biased upward for the
positive class.

**Rule**: do statistics on **the original intensity values inside the
ROI**, not on the thresholded mask. The mask defines *where* to measure;
the intensity image provides *what* to measure. The `redirect=` keyword
in `Set Measurements` is the canonical way to do this — see §2.5.

### §14.6 Selection on the Outcome

If you decide which cells to include based on the outcome variable
(*"this cell is too dim to count"*, *"this cell is dying, exclude"*),
you've selected on the dependent variable and biased every test that
follows. Selection criteria must use **independent channels or
pre-defined morphology**, never the channel being quantified.

### §14.7 Calibration and Unit Mismatch

A measurement of "Mean = 1842" is meaningless without:
- bit depth (8/12/16-bit?)
- calibration (raw DN, photons, fluorophore concentration?)
- background subtraction state (raw, rolling-ball, image-min?)

Imaging stats must report units. `python ij.py metadata` exposes the
calibration; copy it into the figure caption.

---

## §15 Tools — R vs Python vs Fiji vs Prism

### §15.1 R / Python Package Map

| Need | R | Python |
|---|---|---|
| Data wrangling | `tidyverse` (`dplyr`, `tidyr`, `readr`) | `pandas` |
| Tidy-style stats wrapper | `rstatix` | `pingouin` |
| Mixed models | `lme4`, `lmerTest`, `glmmTMB` | `statsmodels.mixedlm` (random intercepts only); `pymer4` (full lme4 via rpy2) |
| Post-hoc / EMMs | `emmeans`, `multcomp::glht` | `pingouin.pairwise_tests`, `scikit-posthocs` |
| Effect sizes | `effectsize`, `psych::cohen.d` | `pingouin.compute_effsize` |
| Tidy model outputs | `broom`, `broom.mixed` | `statsmodels.summary().tables`, `pandas` round-trip |
| Power | `pwr`, `simr`, `WebPower` | `statsmodels.stats.power`, `pingouin.power_*` |
| Survival | `survival`, `survminer` | `lifelines` |
| Bayesian | `BayesFactor`, `brms`, `rstanarm` | `pymc`, `bambi` |
| Plotting (publication) | `ggplot2`, `ggpubr`, `cowplot` | `matplotlib`, `seaborn`, `plotnine` |
| SuperPlots | custom (see §9.3) or `superb` package | custom (see §9.2) |

**Recommended default stack**:
- *Quick exploration / scripting in agent* → Python with `scipy` + `pingouin`.
- *Mixed models / publication figures* → R with `lme4` + `lmerTest` +
  `emmeans` + `ggplot2`.
- *Crossing the boundary* → save tidy CSVs from one and read in the other;
  do not try to mirror `lmer` output directly in `statsmodels` — the df
  approximations differ (see §10.2).

### §15.2 Fiji — What It Can and Can't Do

Fiji's macro language (`Array.getStatistics`, `nResults`, `getResult`)
gives you:
- mean, SD, min, max, median, percentiles per array
- the Results table for export

The **BAR (Broadly Applicable Routines)** plugin
(https://imagej.net/plugins/bar/) adds:
- `BAR > Data Analysis > Distribution Plotter` — histograms of any column
- `BAR > Data Analysis > Find Peaks` — peak detection on traces
- `BAR > Snippets > IJ Robot` — automation helpers

**What Fiji can't do**: any hypothesis test, regression, mixed model, FDR,
power, bootstrap, or effect size. Export the Results table
(`saveAs("Results", path)` or `python ij.py results > stats.csv`) and use
R / Python.

### §15.3 GraphPad Prism — Strengths and Traps

**Prism is enough when**:
- One- or two-way ANOVA, t-test, Mann-Whitney, Wilcoxon, Friedman, RM-ANOVA
- Two-factor designs (no nesting)
- Non-linear regression (4PL, Hill, Michaelis-Menten) with EC50/IC50 and
  curve comparison via F-test or AIC
- Dose-response and standard curve fitting
- Survival (Kaplan-Meier, log-rank)
- Publication-quality figures with stats annotations baked in

**Prism is a trap when**:
- **Nested / hierarchical data** — Prism's "nested t-test" (since v9) is
  helpful for two-group nested comparisons but does *not* generalise.
  Anything more complex (3+ groups + nesting, random slopes, GLMM,
  crossed effects) needs `lmer`.
- **Programmatic pipelines** — Prism is GUI-only; you can't call it from
  a TCP-controlled agent. For batch analysis use R or Python.
- **Reproducibility** — Prism files are binary, not diff-friendly, not
  scriptable. R/Python scripts in git are.
- **Permutation / bootstrap / Bayesian** — Prism doesn't ship these.
- **Multiple testing across thousands of genes/regions/wells** — Prism's
  multiple-comparison adjustments stop at all-pairwise. Use BH in R/Python.

**Pragmatic stance for the agent**: Prism is the standard for many wet labs
because it produces clean figures fast. For exploratory pipelines and
nested designs, prefer scripts. For final figures, either save the script
plot or replicate it in Prism — but the *test* should already be done in
script.

---

## §16 Worked End-to-End Example — 3 Conditions × 5 Animals × ~10 Slices × ~50 Cells

A canonical imaging design and the wrong/right paths through it. The data
table layout, code, and reporting language map directly onto what the
agent's `python ij.py results` produces.

### §16.1 The Setup

- **Conditions** (treatment): `vehicle`, `low_dose`, `high_dose`
- **Animals**: 5 per condition = 15 total. Each animal is randomised to
  one condition.
- **Slices**: ~10 per animal = ~150 slices total.
- **Cells**: ~50 per slice = ~7,500 cells. Outcome: per-cell CTCF for a
  marker.

### §16.2 The Wrong Way — Pooled Two-Group Test

```python
# WRONG: treat every cell as independent
import pandas as pd
from scipy import stats
df = pd.read_csv("AI_Exports/cells_labeled.csv")   # ~7500 rows

# Comparing high_dose vs vehicle as a "two-group t-test"
high = df.loc[df.treatment == "high_dose", "ctcf"].values
veh  = df.loc[df.treatment == "vehicle",  "ctcf"].values

stats.ttest_ind(high, veh, equal_var=False)
# → t ~ 18.4, p ~ 1e-70
# Reads as wildly significant. It is wrong. n = 2500 + 2500 cells, but
# only 5 + 5 animals were independently assigned. The test inflates n
# 500-fold and the false-positive rate accordingly.
```

What's wrong:
- `ttest_ind` treats all 5,000 cells as independent observations.
- Animal-level variance is ignored — every cell from animal m01 is treated
  as informative as a fresh animal.
- Repeating the analysis with simulated null data (see §8.2 demo)
  reproduces the ~30–40% false-positive rate.

### §16.3 The Right Way — Mixed Model + SuperPlot

**Step 1**: produce a labelled tidy CSV from the agent.

```bash
python ij.py results > .tmp/raw_results.csv
# Add columns: animal, slice, treatment (parsed from Label / filename)
python -c "
import csv, re, pathlib
out = []
with open('.tmp/raw_results.csv') as f:
    for r in csv.DictReader(f):
        lbl = r.get('Label','')
        m_a = re.search(r'(m\d{2})', lbl)        # m01, m02, ...
        m_s = re.search(r'(s\d{2})', lbl)        # s01, s02, ...
        m_t = re.search(r'(vehicle|low_dose|high_dose)', lbl)
        if not (m_a and m_s and m_t): continue
        r['animal']    = m_a.group(1)
        r['slice']     = f'{m_a.group(1)}_{m_s.group(1)}'   # globally unique
        r['treatment'] = m_t.group(1)
        out.append(r)
with open('.tmp/cells_labeled.csv','w',newline='') as f:
    w = csv.DictWriter(f, fieldnames=list(out[0].keys()))
    w.writeheader(); w.writerows(out)
"
```

The resulting `cells_labeled.csv` has columns:
```
ctcf,area,treatment,animal,slice
1842.3,156.0,vehicle,m01,m01_s01
1937.5,142.0,vehicle,m01,m01_s01
...
```

**Step 2**: fit the mixed model in R.

```r
library(lme4); library(lmerTest); library(emmeans); library(performance)
df <- read.csv(".tmp/cells_labeled.csv")
df$treatment <- factor(df$treatment, levels = c("vehicle","low_dose","high_dose"))

fit <- lmer(ctcf ~ treatment + (1 | animal) + (1 | slice), data = df, REML = TRUE)

summary(fit)                         # fixed effects with Satterthwaite df
anova(fit)                           # Type-III F-test for treatment
icc(fit)                             # ICC by animal (and by slice)

# Post-hoc: all pairwise with Tukey
em <- emmeans(fit, ~ treatment)
pairs(em, adjust = "tukey")
# Effect sizes on the response scale
eff <- contrast(em, method = "pairwise", adjust = "none")
```

Typical output (illustrative — actual values depend on data):

```
Random effects:
 Groups   Name        Variance Std.Dev.
 slice    (Intercept)   84321    290.4
 animal   (Intercept)  152768    390.9
 Residual               44102    210.0
Number of obs: 7521, groups:  slice, 148; animal, 15

Fixed effects:
                       Estimate Std. Error      df t value Pr(>|t|)
(Intercept)             1812.3      181.4   12.1    9.99   <0.001 ***
treatmentlow_dose        180.5       89.2   12.0    2.02    0.066
treatmenthigh_dose       420.7       91.1   12.0    4.62   <0.001 ***

ICC (animal) = 0.62      ← most variation is between animals
ICC (slice)  = 0.34      ← substantial slice-to-slice
```

p ~ 0.001 for high-dose vs vehicle on the *animal-level* test — almost
20 orders of magnitude weaker than the pooled t-test gave, and *correct*.

**Step 3**: SuperPlot for the figure (R or Python from §9).

```r
library(ggplot2); library(dplyr)
rep_means <- df %>% group_by(treatment, animal) %>%
  summarise(ctcf = mean(ctcf), .groups = "drop")

ggplot(df, aes(treatment, ctcf)) +
  geom_jitter(width=0.18, alpha=0.25, size=0.8, colour="grey60") +
  geom_point(data=rep_means, aes(colour=animal), size=4) +
  stat_summary(data=rep_means, fun=mean, geom="crossbar",
               width=0.5, colour="black") +
  stat_summary(data=rep_means, fun.data=mean_se,
               geom="errorbar", width=0.2, colour="black") +
  theme_classic() + labs(y="CTCF (a.u.)", x=NULL, colour="Animal")
```

### §16.4 The Reporting Sentence

> CTCF was compared across treatments using a linear mixed-effects model
> with treatment as a fixed effect and animal and slice as crossed random
> intercepts (`lmer(ctcf ~ treatment + (1|animal) + (1|slice))`, REML
> estimation, Satterthwaite-approximated degrees of freedom). High-dose
> increased CTCF over vehicle by 421 a.u. (95% CI [222, 620], t(12.0) =
> 4.62, p < 0.001, Hedges' g = 1.07); low-dose was indistinguishable from
> vehicle (181 a.u., 95% CI [-14, 376], t(12.0) = 2.02, p = 0.066). N = 5
> animals per group (10 ± 2 slices/animal, 50 ± 8 cells/slice; 7,521 cells
> total). ICC for animal = 0.62, ICC for slice = 0.34, indicating
> substantial between-animal variability that justifies the mixed model.

### §16.5 What Changes If…

| If… | Then… |
|---|---|
| Each animal received all three doses (cross-over) | `lmer(ctcf ~ treatment + (treatment | animal) + (1 | slice))` — random slope by animal |
| Outcome is "fraction positive" | `glmer(positive ~ treatment + (1 | animal) + (1 | slice), family=binomial)` |
| Outcome is per-field cell count | `glmer.nb(count ~ treatment + offset(log(area)) + (1|animal/slice))` |
| Only 3 animals per group, model fails to converge | Drop `(1 | slice)`, or drop the mixed model entirely and t-test on per-animal means |
| Slice IDs are not unique across animals (e.g. "s01" appears in m01 and m02) | Use `(1 | animal/slice)` instead of `(1 | animal) + (1 | slice)` |

---

## §17 Reviewer Pushback — Catalogue and Responses

### §17.1 Common Comments and How to Answer

| Reviewer comment | Response strategy |
|---|---|
| **"Your n is too low (n=3)."** | Report effect size + 95% CI. If CI excludes the null with margin, the small n is sufficient *for that effect size*. If it doesn't, agree and either (a) collect more or (b) frame as a pilot. Do NOT defend n=3 as adequate without the CI. |
| **"You should have used [parametric/non-parametric]."** | Run BOTH. If they agree, report the more interpretable one and note "consistent with Mann-Whitney" / "consistent with Welch's t". If they disagree, the data are doing something that needs investigation (extreme outlier? bi-modal distribution?). |
| **"You did not correct for multiple comparisons."** | For 2–10 pre-planned comparisons, apply Holm and re-report. For exploratory screens, apply BH and report q-values. State which framework (FWER vs FDR) and why. |
| **"Show individual data points."** | Replace bar charts with SuperPlots / box+jitter / violin+points. Always show points if n ≤ 50 per group. |
| **"Report effect sizes."** | Add Hedges' g (two-group), partial η² (ANOVA), HR (survival) with 95% CI. Update tables and text. |
| **"Why not parametric — your data look normal."** | Show QQ-plots (in supplement). If clearly normal, switch. If borderline, report both — it's an honest position, not a weakness. |
| **"The treatment × time interaction is significant — your main effects are uninterpretable."** | Switch to simple-effects analysis: `emmeans(fit, ~ treatment | time)`. Discuss the interaction first, main effects only if biologically meaningful within levels. |
| **"You pooled cells — this is pseudoreplication."** | Re-analyse with mixed model with animal as random effect, OR aggregate to per-animal means. Both are correct; report the one that matches the design. Cite Lazic 2010 / Aarts 2014 (§8.4). |
| **"You should report a p-value."** | If the model already reports a t/F with df, derive p from `lmerTest`/Satterthwaite or KR (`summary(fit, ddf="Kenward-Roger")`). For Bayesian models, report the BF. |
| **"Your ANOVA assumed equal variance, but Levene is significant."** | Switch to Welch's ANOVA (`oneway.test(y ~ g, var.equal=FALSE)` in R; `pingouin.welch_anova` in py); follow with Games-Howell. |
| **"Are these technical or biological replicates?"** | Define explicitly: "n = 5 animals (biological replicates); each contributed 10 ± 2 slices (technical) and 50 ± 8 cells per slice." Use the imaging hierarchy in §8.1 as a template. |
| **"You report SEM but not SD — your error bars look smaller than they should."** | Switch to SD or 95% CI of the replicate-level mean for the figure. SEM bars on raw cell data hide the actual variability. |
| **"This effect is significant but only 5% — biologically meaningful?"** | Cite the smallest meaningful effect for the biology (literature, mechanism). Discuss CI relative to that threshold — a 5% effect with [3%, 7%] CI is precisely small; with [-2%, 12%] CI it's uninformative. |

### §17.2 Reporting Checklist (Expanded)

For every test in a paper:

- [ ] **Test name in full** — "Welch's two-sample t-test", not just "t-test".
- [ ] **n with units** — "n = 5 mice/group, 10 ± 2 slices/mouse, 50 ± 8 cells/slice".
- [ ] **Test statistic with df** — "t(12.0) = 4.62", "F(2, 12) = 14.3",
      "U = 312", "χ²(2) = 8.4".
- [ ] **Exact p-value** — "p = 0.013", "p < 0.001", not "p < 0.05".
- [ ] **Effect size with 95% CI** — "Hedges' g = 1.07, 95% CI [0.32, 1.83]".
- [ ] **Descriptive stats** — mean ± SD or median [IQR] per group.
- [ ] **What n is** — biological replicate, not "all cells".
- [ ] **Multiple-comparison method** — "Holm-adjusted", "Tukey HSD",
      "BH-adjusted, q = 0.05".
- [ ] **Data + code** — deposited (Zenodo, OSF, figshare); analysis script
      in repository; raw CSV alongside.
- [ ] **Pre-registration status** — confirmatory pre-registered / exploratory;
      where the registration lives (OSF, AsPredicted).
- [ ] **Software with versions** — "R 4.3.1, lme4 1.1-35, lmerTest 3.1-3,
      emmeans 1.10.0".

### §17.3 Pre-Registration and Open Data

- **OSF** (https://osf.io) — free, accepts pre-registrations and data,
  generates DOI.
- **AsPredicted** (https://aspredicted.org) — minimal pre-registration form,
  short.
- **Zenodo** (https://zenodo.org) — DOI for the analysis repository on
  release; integrates with GitHub.
- **Pre-registration of a microscopy experiment should specify**:
  - the imaging hypothesis and primary outcome variable
  - the experimental unit (animal/well)
  - target n per group and stopping rule
  - inclusion/exclusion criteria
  - the planned statistical test (and back-up if assumption fails)
  - figure mock-up

Distinguishing confirmatory from exploratory analyses in the manuscript is
the single biggest credibility lever — a single confirmatory finding from
a pre-registered analysis is worth more than ten exploratory ones from
the same data.

---
