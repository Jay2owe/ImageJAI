# Hypothesis Testing for Microscopy — Reference

---

## 1. Research Question → Test Mappings

### 1.1 Expression / Intensity

| # | Question pattern | Measurement | Experimental unit | Test |
|---|-----------------|-------------|-------------------|------|
| Q1 | "Is protein X upregulated in disease vs control?" | CTCF per cell or Mean intensity in ROI | Animal (average cells per animal) | Unpaired t-test / Mann-Whitney |
| Q2 | "Does drug reduce inflammatory marker Y?" | CTCF per cell, averaged per well/animal | Animal (in vivo) or well (in vitro) | Unpaired t-test (one-tailed) |
| Q3 | "Is GFP different across 3+ genotypes?" | Mean intensity per cell, averaged per animal | Animal | One-way ANOVA + Tukey/Dunnett; Kruskal-Wallis if non-normal |
| Q4 | "Is c-Fos higher in stimulated vs resting neurons?" | Mean nuclear intensity (DAPI-defined ROI) | Animal | Unpaired t-test (one-tailed) |

### 1.2 Morphology

| # | Question pattern | Measurement | Experimental unit | Test |
|---|-----------------|-------------|-------------------|------|
| Q5 | "Are microglia more activated in AD brain?" | Circularity, Solidity, branch count (AnalyzeSkeleton) | Animal | Unpaired t-test / Mann-Whitney |
| Q6 | "Does KO shorten dendrites?" | Total branch length, Sholl profile | Animal | t-test (length); RM two-way ANOVA (Sholl: genotype x distance) |
| Q7 | "Are tumour cells larger at invasive front vs core?" | Area (Analyze Particles) | Patient (paired regions) | Paired t-test / Wilcoxon signed-rank |
| Q8 | "Do astrocytes become more stellate after injury?" | Endpoints, Feret's diameter, Solidity | Animal | Unpaired t-test / Mann-Whitney |

### 1.3 Counts / Density

| # | Question pattern | Measurement | Experimental unit | Test |
|---|-----------------|-------------|-------------------|------|
| Q9 | "More Ki67+ cells in treated tumours?" | Ki67+ cells/mm^2 | Animal/tumour | Unpaired t-test / Mann-Whitney |
| Q10 | "Neuronal density reduced in aged hippocampus?" | NeuN+ count per ROI area | Animal | Unpaired t-test (one-tailed) |
| Q11 | "Does drug reduce amyloid plaques?" | Plaque count (size-filtered, thresholded) | Animal (avg across sections) | Unpaired t-test (one-tailed) |
| Q12 | "More synaptic puncta per neuron in KO?" | Puncta count per neuron or dendritic length | Animal | Unpaired t-test / Mann-Whitney |

### 1.4 Colocalization

| # | Question pattern | Measurement | Experimental unit | Test |
|---|-----------------|-------------|-------------------|------|
| Q13 | "Do X and Y interact more after stimulation?" | PCC from Coloc 2 + Costes P | Animal/experiment | Unpaired t-test on PCC values |
| Q14 | "Does mutation disrupt receptor-ligand coloc?" | Manders' M1/M2 (Costes threshold) | Animal | Unpaired t-test / Mann-Whitney |
| Q15 | "Is mito-ER contact increased during stress?" | M1/M2 or distance-based (DiAna) | Experiment/well | Unpaired t-test (one-tailed) |

### 1.5 Spatial Distribution

| # | Question pattern | Measurement | Experimental unit | Test |
|---|-----------------|-------------|-------------------|------|
| Q16 | "Does drug cause NF-kB nuclear translocation?" | Nuclear/cytoplasmic intensity ratio | Well (in vitro) or animal | Unpaired t-test (one-tailed) |
| Q17 | "Is protein mislocalized from membrane to cytoplasm?" | Membrane/cytoplasm intensity ratio | Animal/patient | Unpaired t-test / Mann-Whitney |
| Q18 | "Is chromatin more peripheral in senescent cells?" | Radial intensity profile or peripheral/central ratio | Experiment | Two-way ANOVA (condition x bin) or t-test on ratio |

### 1.6 Temporal / Dynamic

| # | Question pattern | Measurement | Experimental unit | Test |
|---|-----------------|-------------|-------------------|------|
| Q19 | "Is circadian period longer in mutant slices?" | Period (cosinor/FFT) | Slice (from independent animals) | Unpaired t-test or CircaCompare |
| Q20 | "Does drug alter circadian amplitude?" | Amplitude (cosinor fit or peak-trough) | Slice | Unpaired t-test or CircaCompare |
| Q21 | "Does phase shift after stimulus?" | Acrophase (cosinor) | Slice/culture (paired) | Paired t-test or Watson-Williams (circular) |
| Q22 | "Does calcium frequency change with age?" | Peak frequency (FFT/peak detection) | Animal | Unpaired t-test / Mann-Whitney |

### 1.7 Proportions

| # | Question pattern | Measurement | Experimental unit | Test |
|---|-----------------|-------------|-------------------|------|
| Q23 | "Higher proportion apoptotic in treated?" | TUNEL+ / DAPI+ per field, avg per well | Well or animal | Chi-square/Fisher's (counts) or t-test (proportions per replicate) |
| Q24 | "Transgene+ fraction differs between brain regions?" | GFP+ NeuN+ / total NeuN+ per region | Animal (paired regions) | Paired t-test or RM-ANOVA |
| Q25 | "Treatment changes M1/M2 microglia ratio?" | iNOS+ / (iNOS+ + Arg1+) per section | Animal | Unpaired t-test on proportions |

### 1.8 Correlation / Dose-Response

| # | Question pattern | Measurement | Experimental unit | Test |
|---|-----------------|-------------|-------------------|------|
| Q26 | "Does amyloid correlate with tau?" | Mean intensity per ROI (matched regions) | Animal/region | Pearson r or Spearman rho |
| Q27 | "Does cell size predict migration speed?" | Area + displacement per interval | Experiment | Pearson/Spearman or mixed model |
| Q28 | "Dose-dependent viability reduction?" | Live/dead or CTCF per well at each dose | Experiment | 4PL nonlinear regression; compare IC50 with F-test |
| Q29 | "EC50 differs between WT and KO?" | Response at each concentration | Experiment | Separate 4PL curves; F-test or AIC comparison |

### 1.9 Paired / Regional / Developmental

| # | Question pattern | Measurement | Experimental unit | Test |
|---|-----------------|-------------|-------------------|------|
| Q30 | "Treatment changed spine density (same neuron)?" | Spines per dendritic length, before/after | Animal (paired) | Paired t-test / Wilcoxon |
| Q31 | "FRAP recovery rate changed after drug?" | t1/2 from exponential fit | Cell/ROI (paired) | Paired t-test |
| Q32 | "c-Fos higher in core vs shell?" | Intensity or count in anatomical ROIs | Animal (paired regions) | Paired t-test / Wilcoxon |
| Q33 | "Plaque density differs across cortical layers?" | Count per area per layer | Animal (repeated measures) | RM one-way ANOVA / Friedman |
| Q34 | "Dorsal-ventral gradient of expression?" | Intensity at defined positions | Animal | Linear regression or RM-ANOVA with trend |
| Q35 | "Myelination increases during development?" | MBP intensity or area fraction | Animal (cross-sectional) | One-way ANOVA + trend contrast |
| Q36 | "Lipofuscin accumulates with age?" | Area fraction of autofluorescence | Animal | One-way ANOVA + trend or linear regression |

---

## 2. Key Statistical Concepts

### What p-values are and are not

| Common belief | Reality |
|---|---|
| p = probability H0 is true | p = probability of data this extreme given H0 is true |
| p < 0.05 means effect is real | p < 0.05 means data are unlikely under H0 |
| p = 0.06 means no effect | Inconclusive; report effect size and CI |
| Significant = important | Statistical significance says nothing about biological importance |

### Effect Sizes — Always Report

| Effect size | Formula | Used for |
|------------|---------|----------|
| Cohen's d | (M1 - M2) / SD_pooled | Two-group comparisons |
| Hedges' g | d corrected for small n | Two-group, small n |
| eta-squared | SS_effect / SS_total | ANOVA |
| r | Pearson or Spearman | Correlation |

Conventions: d = 0.2 small, 0.5 medium, 0.8 large. In microscopy, intensity changes >20% are typically meaningful (d ~ 0.5-1.0); overexpression/KO effects often d > 1.5.

**Always report confidence intervals.** A wide CI with n=3 means the estimate is unreliable even if p < 0.05.

### Power — Why n=3 Is Often Insufficient

| n per group | d=0.5 | d=0.8 | d=1.0 | d=1.5 | d=2.0 |
|:-----------:|:-----:|:-----:|:-----:|:-----:|:-----:|
| 3 | 7% | 10% | 17% | 40% | 64% |
| 5 | 11% | 20% | 29% | 61% | 86% |
| 10 | 18% | 39% | 56% | 88% | 99% |
| 20 | 34% | 69% | 84% | 99% | >99% |
| 30 | 48% | 86% | 94% | >99% | >99% |

With n=3, you can typically only detect effects d >= 2.5. For d=0.8 at 80% power, you need ~26 per group.

**Power analysis:**
```r
library(pwr)
pwr.t.test(d = 0.8, sig.level = 0.05, power = 0.80, type = "two.sample")  # n = 26
```
```python
from statsmodels.stats.power import TTestIndPower
TTestIndPower().solve_power(effect_size=0.8, alpha=0.05, power=0.80)  # n = 26
```

### Multiple Testing

| Tests run | P(>= 1 false positive) |
|:---------:|:----------------------:|
| 1 | 5% |
| 5 | 23% |
| 10 | 40% |
| 20 | 64% |

**Corrections:** Holm (step-down) for small planned comparisons. Benjamini-Hochberg for large exploratory analyses. Pre-registration separates exploratory from confirmatory.

### Common Fallacies

1. **"p > 0.05 = no effect"** — Could be underpowered. Report CI.
2. **"n = 300 cells from 3 mice"** — Pseudoreplication. n = 3.
3. **"Removed outliers to get significance"** — p-hacking unless pre-specified.
4. **"One-tailed because we predicted direction"** — Only valid if opposite direction is impossible AND pre-registered.
5. **"p=0.04 in group A, p=0.06 in B, therefore they differ"** — Wrong. Test the interaction directly.
6. **"Trended toward significance"** — Not a concept. Report effect size and CI.

### Equivalence Testing (TOST)

Use when the question is "are these two methods the same?" (not "are they different?"). Non-significant superiority test does NOT demonstrate equivalence.

```python
# TOST
diff = group1.mean() - group2.mean()
se = np.sqrt(group1.var(ddof=1)/len(group1) + group2.var(ddof=1)/len(group2))
df = len(group1) + len(group2) - 2
delta = 10  # equivalence margin
t1 = (diff - (-delta)) / se; t2 = (diff - delta) / se
p1 = stats.t.sf(t1, df); p2 = stats.t.cdf(t2, df)
p_tost = max(p1, p2)
```

### Bayesian Alternative

Useful when arguing for the null (no difference). BF01 > 3 = moderate evidence for no difference.
```r
library(BayesFactor)
bf <- ttestBF(x = treated, y = control)
```

---

## 3. From Question to Measurement

### Intensity Measurements

| Measurement | ImageJ name | When to use |
|---|---|---|
| CTCF | IntDen - (Area x Mean_background) | Gold standard per-cell intensity |
| Mean intensity | Mean | Quick; sensitive to ROI size and background |
| Integrated Density | IntDen | When cell size IS the variable |
| Median | Median | Skewed distributions (punctate staining) |

**ROI rules:** Define ROIs from a separate channel (DAPI/membrane marker), never from the target protein channel (circular reasoning). Use rolling ball background subtraction or CTCF method. Never use Enhance Contrast before quantification.

### Morphology Measurements

| Measurement | ImageJ name | Meaning |
|---|---|---|
| Circularity | Circ. | 4*pi*Area/Perimeter^2; 1.0 = circle |
| Solidity | Solidity | Area/ConvexArea; lower = more branched |
| Aspect Ratio | AR | Major/Minor axis; elongation |
| Feret's Diameter | Feret | Longest dimension |

**Branching:** AnalyzeSkeleton (branches, length, endpoints, junctions), Sholl analysis (intersections vs distance from soma), FracLac (fractal dimension).

**Threshold method is critical for morphology** — different thresholds give different shapes. Always report method and apply identically across conditions.

### Counting Methods

| Method | When to use | Command |
|---|---|---|
| Analyze Particles | 2D, well-separated objects | `run("Analyze Particles...", "size=50-Infinity circularity=0.3-1.0 summarize");` |
| Find Maxima | Small bright puncta | `run("Find Maxima...", "prominence=10 output=Count");` |
| StarDist 2D | Dense nuclei | `run("StarDist 2D", "...");` |
| 3D Objects Counter | Z-stacks | `run("3D Objects Counter", "threshold=... min.=50 objects");` |

**Always normalize** to area (cells/mm^2) or reference population (Ki67+/DAPI+).

### Spatial Distribution

| Approach | Measurement | Method |
|---|---|---|
| Nuclear/cytoplasmic ratio | Mean_nuclear / Mean_cytoplasmic | DAPI mask = nucleus; cell mask - DAPI mask = cytoplasm |
| Radial profile | Intensity vs distance from centroid | Concentric shell ROIs |
| Membrane vs cytoplasm | Mean_membrane / Mean_cytoplasm | Dilated boundary ring as membrane ROI |

### Temporal Parameters

| Parameter | Extraction method |
|---|---|
| Period | FFT, autocorrelation, or cosinor regression |
| Amplitude | Peak-trough / 2 or cosinor fit |
| Phase | Cosinor acrophase or time of max in smoothed data |
| FRAP t1/2 | Exponential fit to recovery curve |

Extract time series: `run("Plot Z-axis Profile");` or loop with `setSlice(i); run("Measure");`
Detrend before period analysis (subtract running average, window ~1.5x expected period).

---

## 4. Experimental Design Pitfalls

### Pseudoreplication — The Most Common Error

Cells from the same animal are not independent. If you image 100 cells from 3 mice, n = 3, not 100.

**Hierarchy:**
```
Biological replicate (animal/patient/experiment)  ← this is n
  └─ Technical replicate (sections, wells)
       └─ Field of view
            └─ Cell
```

**Impact:** Treating cells as independent with n_animals=3, n_cells=50/animal inflates false-positive rate from 5% to >50%.

**Correct approaches:**

| Approach | When |
|---|---|
| Average per animal, then test | Simple, always valid |
| Mixed-effects model (animal = random effect) | Best — uses all data, correct nesting |
| Nested ANOVA | When nesting structure is of interest |

**Always report:** "n = X animals per group (Y cells per animal)" with averaging or mixed-model approach.

### Selection Bias

| Stage | Risk |
|---|---|
| Choosing sections | Picking "best" staining |
| Choosing fields | Moving to "interesting" areas |
| Choosing cells | Selecting "representative" cells |
| Including images | Excluding "failed" images post-hoc |

**Solutions:** Systematic random sampling (random grid start, image every intersection). Define ROIs on DAPI before looking at target channel. Use automated segmentation. Pre-define inclusion/exclusion criteria.

### Batch Effects

Fluorescence intensity depends on lamp power, exposure, antibody lot, staining time, temperature. If conditions are batched separately, technical variation masquerades as treatment effect.

**Solutions:** Interleave conditions in every session. Lock acquisition settings. Same staining batch. Include fluorescent bead controls. Add "batch" as covariate if unavoidable.

### Photobleaching

Fluorophores degrade with illumination. Comparing first-imaged vs last-imaged fields is confounded.

**Solutions:** Minimize exposure, randomize field order, use anti-fade mountant, apply bleach correction (`run("Bleach Correction", "correction=[Exponential Fit]");`), normalize to bleach-resistant reference channel.

### Observer Bias

Unblinded researchers show 10-20% measurement shifts (Jost & Waters 2019). Manual counting variability: 10-30%.

**Solutions:** Blind filename scrambling before analysis. Use automated segmentation. Unblind only after analysis is complete.

### Blinding Macro
```javascript
dir = getDirectory("Choose folder to blind");
list = getFileList(dir);
mapping = "";
for (i = 0; i < list.length; i++) {
    code = "" + random() * 100000;
    code = substring(code, 0, 5);
    mapping += list[i] + "\t" + code + ".tif\n";
    File.rename(dir + list[i], dir + code + ".tif");
}
File.saveString(mapping, dir + "BLINDING_KEY.txt");
```

---

## 5. Decision Tree: Question → Test

```
What is your question?
|
+-- "X different between 2 groups?"
|   +-- Independent → Unpaired t-test / Mann-Whitney
|   +-- Paired (same animal) → Paired t-test / Wilcoxon signed-rank
|
+-- "X different among 3+ groups?"
|   +-- Independent → One-way ANOVA / Kruskal-Wallis + Tukey/Dunnett
|   +-- Repeated measures → RM-ANOVA / Friedman + Bonferroni
|
+-- "Interaction between A and B?" → Two-way ANOVA (or mixed model)
|
+-- "X correlates with Y?"
|   +-- Linear, normal → Pearson's r
|   +-- Non-linear/ordinal → Spearman's rho
|   +-- Predictive → Linear regression
|
+-- "Proportion of positive cells different?"
|   +-- Large n → Chi-square; small n → Fisher's exact
|   +-- Multiple predictors → Logistic regression
|
+-- "Dose-dependent?" → 4PL Hill regression; compare IC50 with F-test
+-- "Circadian rhythm different?" → CircaCompare or cosinor + t-test
+-- "Two methods equivalent?" → TOST with pre-specified delta
+-- "Cells nested in animals?" → Mixed-effects model (animal = random intercept)
+-- "Same cells over time?" → RM-ANOVA or mixed model
```

---

## 6. Rigour Checklist

**Before experiment:**
- [ ] Define hypothesis and primary outcome
- [ ] Power analysis for sample size
- [ ] Randomize assignment to groups
- [ ] Pre-define inclusion/exclusion criteria
- [ ] Plan interleaved processing

**During imaging:**
- [ ] Lock acquisition settings (no "Auto" exposure)
- [ ] Systematic random sampling for fields
- [ ] Minimize pre-viewing before quantitative acquisition

**During analysis:**
- [ ] Blind analyst to conditions
- [ ] Automated segmentation where possible
- [ ] Identical parameters across all images
- [ ] Average technical replicates before testing

**During statistics:**
- [ ] n = biological replicates
- [ ] Report p-values, effect sizes, confidence intervals
- [ ] Mixed-effects models for nested data
- [ ] Correct for multiple comparisons
- [ ] Distinguish pre-specified vs exploratory analyses

---

## Sources

- Jost & Waters 2019 (JCB 218:1452) — rigorous microscopy experiment design
- Believing is seeing (JCS 2024) — bias in quantitative microscopy
- Pseudoreplication in physiology (JGP 2021); Nature Comms 2021 — practical solutions
- BMC Neuroscience 2010 — pseudoreplication in neuroscience
- ASA Statement on p-values (2016)
- CircaCompare (Bioinformatics 2020)
- TOSTER package — equivalence testing
