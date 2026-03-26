# Statistics for Biological Microscopy — Agent Reference

Quick-reference for choosing, running, and interpreting statistical tests on microscopy data.
Covers decision trees, R/Python/ImageJ syntax, and common pitfalls.

---

## 1. Distributions in Microscopy

| Distribution | Typical data | Key property | How to recognise |
|---|---|---|---|
| Normal | Cell diameters, measurement error | Symmetric, mean = centre | Histogram is bell-shaped |
| Log-normal | **Fluorescence intensities**, cell areas, protein conc. | Right-skewed, always positive | Log(data) looks normal |
| Poisson | Photon counts, cells per FOV | Mean = variance | Count data, rare events |
| Binomial | Ki67+/total, responders/total | Fixed n, two outcomes | Proportion from counted denominator |
| Negative binomial | Overdispersed counts (clustered plaques) | Variance > mean | Count data, clustering |
| Exponential | Inter-event intervals (calcium transients) | Memoryless | Waiting times |

**Choosing guidance**: Log-normal is the most important for intensity data. If variance roughly equals mean, consider Poisson. If variance >> mean, consider negative binomial.

---

## 2. Hypothesis Testing Essentials

### Error types

|  | H0 true | H0 false |
|---|---|---|
| Reject H0 | Type I (alpha, typically 0.05) | Correct (power) |
| Fail to reject | Correct | Type II (beta) |

### p-value: what it is and is not

- **IS**: P(data this extreme | H0 true)
- **IS NOT**: P(H0 true), probability of replication, or measure of effect size
- Report exact values (p = 0.032, not p < 0.05)
- Two-tailed unless strong a priori directional hypothesis

### SD vs SEM

| Statistic | Describes | Changes with n? | Use for |
|---|---|---|---|
| SD | Spread of individual measurements | No | Describing variability |
| SEM = SD/sqrt(n) | Precision of the mean | Yes (decreases) | Inference about mean |

Best practice: show individual data points + 95% CI rather than SEM bars.

---

## 3. Parametric Tests

| Test | Purpose | Key assumption | Default recommendation |
|---|---|---|---|
| One-sample t | Mean vs known value | ~Normal (or n >= 30) | — |
| Unpaired t (Welch) | Two independent groups | ~Normal | **Use Welch's by default** (robust to unequal variance) |
| Paired t | Same subjects, two conditions | Differences ~normal | Pairs are independent |
| z-test for proportions | Compare proportions | n*p > 5 | Use Fisher's exact for small counts |

---

## 4. Non-Parametric Tests

Consider when: n < 10, clearly non-normal, ordinal data, extreme outliers.

| Test | Replaces | Purpose |
|---|---|---|
| Mann-Whitney U | Unpaired t | Two independent groups (tests stochastic dominance, not medians) |
| Wilcoxon signed-rank | Paired t | Two related measurements |
| Kruskal-Wallis | One-way ANOVA | 3+ independent groups → follow with Dunn's test |
| Friedman | RM ANOVA | 3+ related groups → follow with Nemenyi/Conover |
| Fisher's exact | Chi-squared | 2x2 tables, any sample size (always valid) |
| Chi-squared | — | Contingency tables (expected counts >= 5 per cell) |

---

## 5. ANOVA Family

| Variant | Design | Key consideration |
|---|---|---|
| One-way | 3+ independent groups | Use Welch's ANOVA when variances unequal |
| Two-way | Two factors + interaction | Use Type III SS with sum contrasts for unbalanced data |
| Repeated measures | Same subjects, multiple conditions | Check sphericity (Mauchly); correct with Greenhouse-Geisser |
| Mixed ANOVA | Between + within factors | e.g., Genotype (between) x Time (within) |
| ANCOVA | Groups + continuous covariate | Assumes homogeneity of regression slopes |

**When NOT to use ANOVA**: nested/hierarchical data (use mixed models), count data (use Poisson/NB regression), proportions (use logistic regression).

Check variance equality with Levene's test: `car::leveneTest(y ~ g, data=df)`.
R Type III: `options(contrasts = c("contr.sum","contr.poly")); car::Anova(model, type="III")`.

---

## 6. Post-Hoc Test Decision Tree

```
ANOVA significant? → All pairwise needed?
  Equal var: Tukey HSD
  Unequal var: Games-Howell
  vs control only: Dunnett's (more powerful)
  Pre-planned contrasts: Holm correction

Kruskal-Wallis significant?
  Dunn's test (standard, with Holm)
  Conover test (more powerful)
```

---

## 7. Correlation

| Method | Relationship | When to use |
|---|---|---|
| Pearson r | Linear | Both ~normal, no outliers |
| Spearman rho | Monotonic | Non-normal, outliers, intensity data |
| Kendall tau | Monotonic | Small n (< 10), many ties |

Interpretation (Cohen): |r| < 0.1 negligible, 0.1-0.3 small, 0.3-0.5 medium, > 0.5 large.

For non-monotonic relationships: use distance correlation or mutual information.

---

## 8. Mixed-Effects Models

**The most common microscopy statistics error is pseudoreplication.**

Example: 3 mice/group, 5 fields/mouse, 20 cells/field.
- WRONG: n = 300 cells
- RIGHT: n = 3 mice (biological replicate), with cells/fields as nested observations

### How to choose random effects

1. Identify the biological replicate (unit independently assigned to condition)
2. Model nesting: `lmer(intensity ~ treatment + (1|animal/field), data=df)`
3. If convergence fails, simplify: drop random slopes first, then lowest-level intercepts
4. Report: N biological replicates AND n total observations

### When to use

- Multiple cells per animal (nested)
- Multiple fields per slide (nested)
- Multiple time points per subject (repeated)
- Any hierarchical data structure

---

## 9. Multiple Comparisons

| Method | Controls | When to use |
|---|---|---|
| **Holm** (step-down Bonferroni) | FWER | **Default recommendation** — always more powerful than Bonferroni |
| Bonferroni | FWER | No advantage over Holm; avoid |
| Benjamini-Hochberg | FDR | Exploratory, many tests (10+), imaging |
| Tukey HSD | FWER | Built into all-pairwise after ANOVA |
| Dunnett | FWER | Built into vs-control comparisons |

With 10 tests at alpha = 0.05, P(at least one false positive) = 40% without correction.

---

## 10. Effect Sizes

| Measure | Formula | Small / Medium / Large |
|---|---|---|
| Cohen's d | (mean1 - mean2) / SD_pooled | 0.2 / 0.5 / 0.8 |
| Hedges' g | d * (1 - 3/(4(n1+n2)-9)) | Same (preferred for n < 20) |
| Eta-squared | SS_between / SS_total | 0.01 / 0.06 / 0.14 |
| CLES | P(X1 > X2) | Most intuitive for non-statisticians |
| Odds ratio | (a*d)/(b*c) | Report with 95% CI |

Cohen's conventions are from social science. In biology, what is "meaningful" depends on context — a d = 0.3 in neurodegeneration may be important.

---

## 11. Power Analysis

**Four linked quantities** (know 3 to find the 4th): sample size, effect size, alpha, power.

| Effect size (d) | n per group (alpha=0.05, power=0.80) |
|---|---|
| 0.5 (medium) | 64 |
| 0.8 (large) | 26 |
| 1.0 | 17 |
| 1.5 | 9 |
| 2.0 | 6 |

With n = 3-5 animals/group (typical microscopy), you typically only detect d > 1.5-2.0.

For mixed models: also specify variance at each level + ICC. As ICC increases, you need more animals; adding cells has diminishing returns. Use R package `simr` for simulation-based power.

---

## 12. Resampling Methods

| Method | Use for | Key detail |
|---|---|---|
| Bootstrap (BCa) | CI for any statistic | 1000-10000 resamples; good for CTCF, Manders' coefficients |
| Permutation test | p-value, small n | Shuffles group labels; used in Costes' colocalization test |
| Jackknife | Identifying influential observations | Leave-one-out |

---

## 13. Circular Statistics

For angles, phases, directions (circadian phase, migration direction, spindle angle).

- **Circular mean**: `atan2(sum(sin(theta)), sum(cos(theta)))`
- **Mean resultant length (R-bar)**: 0 = uniform, 1 = all same direction
- **Rayleigh test**: is there a preferred direction?
- **Watson-Williams test**: circular analogue of one-way ANOVA

---

## 14. Data Transformation Decision Tree

```
Fluorescence intensities? → log transform (almost always right-skewed)
Count data?
  Mean ~ variance → sqrt or Poisson regression
  Variance >> mean → log or NB regression
Proportions? → logistic regression (preferred) or arcsine-sqrt
Areas/volumes? → try log transform
Ratios/fold-change? → log transform (natural scale for ratios)

After transforming: check QQ-plot + Shapiro-Wilk.
If still non-normal → non-parametric on original data.
```

---

## 15. Software — R

### Base stats
```r
# t-tests
t.test(y ~ g, data=df)                          # Welch's (default)
t.test(x, y, paired=TRUE)                       # paired
t.test(values, mu=100)                           # one-sample

# Non-parametric
wilcox.test(y ~ g, data=df)                      # Mann-Whitney
wilcox.test(x, y, paired=TRUE)                   # Wilcoxon signed-rank
kruskal.test(y ~ g, data=df)                     # Kruskal-Wallis

# ANOVA
model <- aov(y ~ treatment, data=df); summary(model)
model <- aov(y ~ genotype * treatment, data=df)  # two-way

# Contingency
chisq.test(table(df$trt, df$response))
fisher.test(tbl)

# Correlation
cor.test(x, y)                                   # Pearson
cor.test(x, y, method="spearman")

# Normality / variance
shapiro.test(values)
car::leveneTest(y ~ g, data=df)

# Proportions
prop.test(x=c(45,30), n=c(100,100))
```

### lme4 (mixed models)
```r
library(lme4); library(lmerTest)
model <- lmer(y ~ treatment + (1|animal/field), data=df)
summary(model)   # p-values via Satterthwaite
anova(model)     # Type III tests
# (1|animal) = random intercept; (1+time|animal) = random intercept + slope
# REML=TRUE (default) for variance estimates; REML=FALSE for model comparison

# Count data
glmer(count ~ trt + (1|animal), data=df, family=poisson)
# Binary data
glmer(positive ~ trt + (1|animal), data=df, family=binomial)
```

### emmeans (post-hoc)
```r
library(emmeans)
emm <- emmeans(model, ~ treatment)
pairs(emm, adjust="tukey")                       # all pairwise
contrast(emm, method="trt.vs.ctrl", ref="control") # vs control
emmeans(model, ~ treatment, type="response")     # back-transform GLMMs
```

### multcomp
```r
library(multcomp)
glht(model, linfct=mcp(treatment="Tukey"))       # Tukey
glht(model, linfct=mcp(treatment="Dunnett"))     # Dunnett
```

### Power (pwr)
```r
library(pwr)
pwr.t.test(d=0.8, sig.level=0.05, power=0.80, type="two.sample")
pwr.anova.test(k=4, f=0.25, sig.level=0.05, power=0.80)
```

### Effect sizes
```r
library(effectsize)
hedges_g(y ~ g, data=df)
eta_squared(aov_model, partial=TRUE)
omega_squared(aov_model)
```

### Circular
```r
library(circular)
angles <- circular(c(350,10,15,5,355), units="degrees")
mean(angles); rho.circular(angles); rayleigh.test(angles)
watson.two.test(a1, a2)
```

### ggplot annotations
```r
library(ggpubr)
ggplot(df, aes(x=group, y=y)) + geom_boxplot() +
  stat_compare_means(comparisons=list(c("A","B"),c("A","C")),
                     method="t.test", p.adjust.method="holm")
```

---

## 16. Software — Python

### scipy.stats
```python
from scipy import stats

# t-tests
stats.ttest_ind(a, b)                  # Welch's (default)
stats.ttest_ind(a, b, equal_var=True)  # Student's
stats.ttest_rel(before, after)         # paired
stats.ttest_1samp(values, popmean=100)

# Non-parametric
stats.mannwhitneyu(a, b, alternative='two-sided')
stats.wilcoxon(before, after)
stats.kruskal(a, b, c)

# ANOVA
stats.f_oneway(a, b, c)               # no built-in post-hoc

# Contingency
stats.chi2_contingency(table)
stats.fisher_exact(table_2x2)

# Normality
stats.shapiro(values)
stats.normaltest(values)               # D'Agostino-Pearson, n >= 8

# Correlation
stats.pearsonr(x, y)
stats.spearmanr(x, y)
stats.kendalltau(x, y)

# Bootstrap (scipy >= 1.7)
from scipy.stats import bootstrap
result = bootstrap((values,), np.mean, n_resamples=9999, method='BCa')

# Permutation (scipy >= 1.8)
from scipy.stats import permutation_test
def stat_func(x, y, axis):
    return np.mean(x, axis=axis) - np.mean(y, axis=axis)
result = permutation_test((a, b), stat_func, n_resamples=9999)
```

### statsmodels
```python
import statsmodels.api as sm
from statsmodels.formula.api import ols, mixedlm
from statsmodels.stats.anova import anova_lm
from statsmodels.stats.multitest import multipletests
from statsmodels.stats.multicomp import pairwise_tukeyhsd

# ANOVA (Type II)
model = ols('y ~ C(treatment)', data=df).fit()
anova_lm(model, typ=2)

# Two-way with Type III
model = ols('y ~ C(g, Sum) * C(trt, Sum)', data=df).fit()
anova_lm(model, typ=3)

# Mixed model
model = mixedlm('y ~ treatment', data=df, groups='animal').fit()

# Multiple comparisons
reject, p_adj, _, _ = multipletests(pvals, method='holm')
# methods: 'bonferroni', 'holm', 'hochberg', 'fdr_bh', 'fdr_by'

# Tukey HSD
pairwise_tukeyhsd(df['y'], df['group'], alpha=0.05)
```

### pingouin
```python
import pingouin as pg

# t-test (returns T, dof, p-val, cohen-d, BF10, power)
pg.ttest(a, b, paired=False)

# ANOVA / RM ANOVA / Mixed ANOVA
pg.anova(data=df, dv='y', between='treatment')
pg.rm_anova(data=df, dv='y', within='time', subject='animal')
pg.mixed_anova(data=df, dv='y', between='genotype', within='time', subject='animal')

# Post-hoc (padjust: 'bonf', 'holm', 'fdr_bh', 'none')
pg.pairwise_tests(data=df, dv='y', between='treatment', padjust='holm')

# Assumption checks
pg.normality(data=df, dv='y', group='treatment')
pg.homoscedasticity(data=df, dv='y', group='treatment')

# Correlation
pg.corr(x, y, method='spearman')
pg.partial_corr(data=df, x='intensity', y='size', covar='age')

# Effect sizes
pg.compute_effsize(a, b, eftype='hedges')
```

### scikit-posthocs
```python
import scikit_posthocs as sp
sp.posthoc_dunn(df, val_col='y', group_col='g', p_adjust='holm')
sp.posthoc_conover(df, val_col='y', group_col='g', p_adjust='holm')
```

### Power
```python
from statsmodels.stats.power import TTestIndPower, FTestAnovaPower
TTestIndPower().solve_power(effect_size=0.8, alpha=0.05, power=0.80)
FTestAnovaPower().solve_power(effect_size=0.25, k_groups=4, alpha=0.05, power=0.80)
```

---

## 17. Software — ImageJ

### What ImageJ can do
```javascript
// Basic array stats
Array.getStatistics(values, min, max, mean, stdDev);

// Manual stats from Results table
n = nResults;
for (i = 0; i < n; i++) { sum += getResult("Area", i); }

// Export for external analysis
saveAs("Results", "/path/to/results.csv");
```

### What ImageJ cannot do
All hypothesis tests, regression, mixed models, multiple comparison corrections, power analysis, bootstrap, effect sizes — export CSV and use R or Python.

### Fiji plugins with built-in statistics
Coloc 2 (Costes significance), TrackMate (track stats), 3D Objects Counter (basic stats).

### Agent workflow
After measuring: `python ij.py results` → get CSV → analyse in Python with scipy/pingouin.

---

## 18. GraphPad Prism

| Scenario | Prism path |
|---|---|
| Two groups, unpaired | Column > t and nonparametric > Unpaired t test |
| Two groups, paired | Column > t and nonparametric > Paired t test |
| 3+ groups | Column > One-way ANOVA |
| Two factors | Grouped > Two-way ANOVA |
| 2x2 table | Contingency > Fisher's exact |
| Correlation | XY > Correlation |
| Dose-response | XY > Nonlinear regression > Dose-response |

**Limitations**: No flexible mixed models for nested designs, no scripting/automation, cannot be called from agent. For automated pipelines, use R or Python.

---

## 19. Master Decision Tree — Choosing the Right Test

```
How many groups?

ONE (vs known value):
  Continuous, normal → one-sample t-test
  Continuous, non-normal → Wilcoxon signed-rank
  Proportion → binom.test

TWO groups:
  Independent:
    Continuous, normal → Welch's t [DEFAULT]
    Continuous, non-normal → Mann-Whitney U
    Binary outcome, large n → chi-squared / z-test for proportions
    Binary outcome, small n → Fisher's exact
    Count data → Poisson regression
  Paired:
    Continuous, normal diffs → paired t-test
    Continuous, non-normal diffs → Wilcoxon signed-rank
    Binary → McNemar's test

THREE+ groups:
  Independent:
    Normal, equal var → ANOVA → Tukey/Dunnett
    Normal, unequal var → Welch's ANOVA → Games-Howell
    Non-normal → Kruskal-Wallis → Dunn's
    Count data → Poisson/NB regression
    Binary → chi-squared / Fisher's
  Repeated measures:
    Normal → RM ANOVA (check sphericity)
    Non-normal → Friedman test
  Two factors → two-way ANOVA / mixed ANOVA
  Nested (cells within animals) → MIXED-EFFECTS MODEL
```

### Parametric vs non-parametric guidance
- n >= 30: parametric typically robust to non-normality
- n = 10-29: check normality (Shapiro-Wilk + QQ-plot); consider transformation
- n < 10: non-parametric preferred unless clearly normal
- n = 3-5 (typical microscopy): both approaches have low power; consider bootstrap CIs
- n < 3: no formal test meaningful; report individual values

---

## 20. Common Microscopy Statistics Mistakes

| Mistake | Example | Fix |
|---|---|---|
| **Pseudoreplication** | n=300 cells from 3 mice reported as n=300 | Mixed model with mouse as random effect, or average per mouse first |
| Multiple comparisons | 5 t-tests at alpha=0.05 (~23% false positive) | ANOVA + post-hoc, or Holm/BH correction |
| Wrong n | Reporting cells instead of biological replicates | n = biological replicates (animals, wells, slices) |
| Normality obsession | Shapiro-Wilk on n=3 (no power to detect) | Run both parametric + non-parametric; if they agree, report one |
| p-hacking | Trying tests until p < 0.05 | Pre-register plan; report all tests; use FDR if exploratory |
| Ignoring data structure | Simple ANOVA on time-series or nested data | Model structure explicitly (mixed models, GEE) |
| Confusing statistical/practical significance | p < 0.05 with n=10000 cells and 0.1% difference | Report effect sizes with CIs; discuss biological significance |

---

## 21. Reporting Checklist

For every test, report:
1. Full test name ("Welch's two-sample t-test")
2. Test statistic + df: t(14) = 2.83
3. Exact p-value: p = 0.013
4. Effect size + CI: Hedges' g = 1.12, 95% CI [0.31, 1.91]
5. Sample size: n = 8 mice/group (300 cells total)
6. Descriptive stats: mean +/- SD or median [IQR]
7. What n represents

Figure best practices: show individual data points, use 95% CI or SD (not SEM without stating it), consider SuperPlots (Lord et al. 2020).

---

## Quick Reference — Common Microscopy Scenarios

| Scenario | Test | R | Python |
|---|---|---|---|
| Two groups, unpaired, normal | Welch's t | `t.test(y~g, data=df)` | `stats.ttest_ind(a,b)` |
| Two groups, unpaired, non-normal | Mann-Whitney | `wilcox.test(y~g, data=df)` | `stats.mannwhitneyu(a,b)` |
| Two groups, paired, normal | Paired t | `t.test(x,y, paired=TRUE)` | `stats.ttest_rel(a,b)` |
| Two groups, paired, non-normal | Wilcoxon | `wilcox.test(x,y, paired=TRUE)` | `stats.wilcoxon(a,b)` |
| 3+ groups, normal | ANOVA | `aov(y~g, data=df)` | `stats.f_oneway(a,b,c)` |
| 3+ groups, non-normal | Kruskal-Wallis | `kruskal.test(y~g, data=df)` | `stats.kruskal(a,b,c)` |
| 2x2, small n | Fisher's exact | `fisher.test(tbl)` | `stats.fisher_exact(tbl)` |
| Correlation, normal | Pearson | `cor.test(x,y)` | `stats.pearsonr(x,y)` |
| Correlation, non-normal | Spearman | `cor.test(x,y, method="spearman")` | `stats.spearmanr(x,y)` |
| Nested data | Mixed model | `lmer(y~trt+(1\|animal), df)` | `mixedlm('y~trt', df, groups='animal')` |
| All pairwise post-hoc | Tukey HSD | `TukeyHSD(aov_result)` | `pairwise_tukeyhsd(y,g)` |
| vs control post-hoc | Dunnett | `glht(m, mcp(g="Dunnett"))` | `sp.posthoc_dunn(df,...)` |
| p-value correction | Holm | `p.adjust(pvals, "holm")` | `multipletests(pvals, method='holm')` |
| Effect size | Hedges' g | `effectsize::hedges_g(y~g)` | `pg.compute_effsize(a,b,'hedges')` |
| Power | Sample size | `pwr.t.test(d=.8, power=.8)` | `TTestIndPower().solve_power(.8, power=.8)` |
