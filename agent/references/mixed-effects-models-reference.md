# Mixed-Effects Models for Microscopy — Agent Reference

For imaging data with hierarchy (animal -> slice -> cell), repeated measures, or nested replicates. Use whenever there is >1 cell/field per animal, or one animal in multiple conditions.

Sources: Bates et al. (2015) JSS 67(1) https://www.jstatsoft.org/article/view/v067i01 · lme4 vignette https://cran.r-project.org/web/packages/lme4/vignettes/lmer.pdf · lmerTest, emmeans, glmmTMB, simr (CRAN) · pymer4 https://eshinjolly.com/pymer4/ · Westfall, Kenny, Judd (2014) JEP:Gen 143(5):2020.

---

## §1 When to use a mixed model

Same animal contributing >1 cell/slice = **pseudoreplication** if you t-test on cell-level data (inflated n, false positives). Two valid responses:

1. Average per animal, t-test on animal means (always defensible, loses within-animal info).
2. Mixed model with animal as random effect (uses all cells, accounts for correlation).

Use a mixed model when cells per animal are unbalanced, you need within-animal slopes (dose response per animal), or you want to partition variance.

---

## §2 lme4::lmer — formula syntax

```r
library(lme4); library(lmerTest)   # lmerTest MUST be loaded for p-values
df <- read.csv("AI_Exports/measurements.csv")
```

**Random intercept by animal** — each animal has its own baseline; treatment effect is fixed:
```r
fit <- lmer(intensity ~ treatment + (1 | animal), data = df)
```

**Nested random intercepts** (slice within animal). Use `/` only if slice IDs are NOT unique across animals (e.g. every animal has "slice1"):
```r
fit <- lmer(intensity ~ treatment + (1 | animal/slice), data = df)
# Expands to: (1 | animal) + (1 | animal:slice)
```
If slice IDs are globally unique (`m1_s1`, `m2_s1`, ...) use the explicit form `(1|animal) + (1|slice)` — this is safer and identical in practice.

**Crossed random effects** — two non-nested grouping factors (e.g. animals were processed in different batches and animal !⊂ batch):
```r
fit <- lmer(intensity ~ treatment + (1 | animal) + (1 | batch), data = df)
```

**Random slope** — each animal has its own treatment effect (only valid if treatment varies WITHIN animal, e.g. before/after, or multiple doses per animal):
```r
fit <- lmer(intensity ~ dose + (1 + dose | animal), data = df)
# Equivalent: (dose | animal). Correlated intercept and slope.
# Uncorrelated: (1 | animal) + (0 + dose | animal), or use ||
fit <- lmer(intensity ~ dose + (dose || animal), data = df)
```

Treatment varying only BETWEEN animals (each animal is one group)? Random slope is unidentifiable — use intercept only.

---

## §3 Reading `summary(fit)`

```r
summary(fit)
```

Three blocks:

1. **Random effects**: variance + SD per group. Compute ICC by hand:
   `ICC = sigma2_animal / (sigma2_animal + sigma2_residual)`
   ICC ~0.1 = mild clustering; >0.3 = strong; ~0 = animal doesn't matter (reconsider model).
   Or use `performance::icc(fit)`.
2. **Fixed effects**: estimate, SE, df, t, **Pr(>|t|)** (only if `lmerTest` is loaded — Satterthwaite df by default; use `summary(fit, ddf="Kenward-Roger")` for the more conservative KR approximation, recommended for small samples).
3. **Correlation of fixed effects**: ignore unless very high (>0.9 -> collinearity).

Get CIs: `confint(fit, method="profile")` (slow but best) or `method="Wald"` (fast).

---

## §4 Post-hoc with emmeans

For >2 treatment levels, don't read multiple t-statistics from `summary` — use estimated marginal means with proper multiple-comparison adjustment:

```r
library(emmeans)
emm <- emmeans(fit, ~ treatment)
pairs(emm, adjust = "tukey")            # all pairwise, Tukey HSD
emmeans(fit, pairwise ~ treatment, adjust = "tukey")   # one-shot
emmeans(fit, trt.vs.ctrl ~ treatment)   # vs control only (Dunnett)
contrast(emm, list(drug_vs_ctrl = c(-1, 1, 0)))         # custom contrast
```

For interactions: `emmeans(fit, pairwise ~ treatment | timepoint)`.

---

## §5 Common warnings — what they mean and how to fix

**`boundary (singular) fit: see ?isSingular`** — a variance component was estimated at zero (the data don't support that random effect). Diagnose with `VarCorr(fit)`. Fixes:
- Drop the offending random effect (`(1|batch)` -> remove if batch variance ~0).
- Simplify random-slope structure: replace `(1+dose|animal)` with `(1|animal)`.
- Use `(dose||animal)` to drop the intercept-slope correlation.
- Don't try to "fix" by changing optimizer — the model is correctly telling you the data lack info.

**`Model failed to converge with max|grad| = ...`** — optimiser didn't reach the minimum. Try in order:
```r
# 1. Different optimiser
fit <- lmer(y ~ x + (1|animal), data=df,
            control = lmerControl(optimizer = "bobyqa",
                                  optCtrl = list(maxfun = 2e5)))
# 2. All-optimiser check
afex::all_fit(fit)   # tries 7 optimisers, reports which converged
# 3. Centre/scale predictors (huge predictor scale -> bad gradients)
df$dose_z <- scale(df$dose)
# 4. Simplify random structure
```

**`fixed-effect model matrix is rank deficient`** — collinear predictors (e.g. `treatment` and `dose` perfectly aligned, or you accidentally included both `age` and `age_group`). Check `cor()` on the model matrix; drop the redundant predictor.

**`Some predictor variables are on very different scales: consider rescaling`** — exactly that. `scale()` continuous predictors before fitting.

---

## §6 GLMM — non-Gaussian outcomes

Cell counts, proportions, presence/absence are not Gaussian. Use `glmer`:

```r
# Counts (cells per field) — Poisson
fit <- glmer(count ~ treatment + offset(log(area_um2)) + (1|animal),
             data=df, family=poisson)

# Proportions (positive cells / total cells) — Binomial with weights = total
df$prop <- df$pos / df$total
fit <- glmer(prop ~ treatment + (1|animal), data=df,
             family=binomial, weights=total)
# Equivalent two-column form:
fit <- glmer(cbind(pos, total - pos) ~ treatment + (1|animal),
             data=df, family=binomial)
```

**Overdispersion check** (for Poisson/binomial) — variance > mean breaks SEs/p-values:
```r
# Quick ratio
sum(residuals(fit, type="pearson")^2) / df.residual(fit)   # should be ~1
# Formal test
performance::check_overdispersion(fit)
# DHARMa simulation-based diagnostics
DHARMa::simulateResiduals(fit) |> plot()
```
If ratio >>1 (commonly 2-10 in cell-count data), switch to negative binomial:
```r
fit <- lme4::glmer.nb(count ~ treatment + (1|animal), data=df)
# Better: glmmTMB handles NB, zero-inflation, dispersion modelling, AND crossed REs
library(glmmTMB)
fit <- glmmTMB(count ~ treatment + (1|animal),
               family=nbinom2, data=df)
fit <- glmmTMB(count ~ treatment + (1|animal),
               family=nbinom2, ziformula=~1, data=df)   # zero-inflated
```

`glmmTMB` is faster and more flexible than lme4 for non-Gaussian; default to it for counts unless reviewers demand lme4.

---

## §7 Python equivalents

**statsmodels** — limited but pure Python:
```python
import statsmodels.formula.api as smf
m = smf.mixedlm("intensity ~ treatment", data=df,
                groups=df["animal"]).fit(reml=True)
print(m.summary())
# Random slope:
m = smf.mixedlm("intensity ~ dose", data=df, groups=df["animal"],
                re_formula="~dose").fit()
```
**Limitations**: no crossed random effects (only one `groups`), no GLMM (Gaussian only), Wald p-values only (no Satterthwaite). For nested-only designs with normal outcomes it's fine; otherwise prefer pymer4.

**pymer4** — true lme4 via rpy2 (needs R + lme4 + lmerTest installed):
```python
from pymer4.models import Lmer
m = Lmer("intensity ~ treatment + (1|animal)", data=df)
m.fit()                   # gives Satterthwaite p-values like R
m.fixef                   # fixed effects table
m.ranef_var               # random-effects variances
m.post_hoc(marginal_vars="treatment")   # emmeans wrapper
# GLMM:
m = Lmer("count ~ treatment + (1|animal)", data=df, family="poisson")
```
Docs: https://eshinjolly.com/pymer4/

**pingouin** — for textbook repeated-measures ANOVA (not a true mixed model, no random slopes):
```python
import pingouin as pg
pg.mixed_anova(data=df, dv="intensity", within="time",
               between="treatment", subject="animal")
pg.rm_anova(data=df, dv="y", within="time", subject="animal")
```

**Decision**: complex hierarchy / GLMM -> pymer4 or call R from Python. Simple nested Gaussian -> statsmodels. Classic RM-ANOVA design -> pingouin.

---

## §8 Power analysis with simr

Simulation-based power for any lme4 model. Workflow: fit a pilot model, set the effect size you want to detect, simulate.

```r
library(simr)
fit <- lmer(intensity ~ treatment + (1|animal), data=pilot_df)
fixef(fit)["treatmentDrug"] <- 0.5      # assume effect = 0.5 SD
powerSim(fit, test=fixed("treatment"), nsim=1000)
# Vary N animals
fit2 <- extend(fit, along="animal", n=20)
pc   <- powerCurve(fit2, along="animal", nsim=200,
                   test=fixed("treatment"))
plot(pc)
```

**Westfall, Kenny, Judd (2014)** — power formulas for crossed/nested designs with multiple random factors. Key insight: with k cells per animal, effective n grows slower than k as ICC rises. Doubling cells per animal at ICC=0.3 gains <30% power; doubling animals gains ~100%. **Add animals, not cells.**

---

## §9 Random vs fixed — decision rule

| Factor | Levels | Care about specific levels? | Random or fixed |
|---|---|---|---|
| Treatment / genotype / drug | 2-4 | yes (this is your hypothesis) | **fixed** |
| Animal / mouse / patient | 5+ | no, want to generalise | **random** |
| Slice / field within animal | many | no | **random** |
| Batch / staining day | 2-3 | no but unbalanced | random if >=5 levels, else fixed nuisance |
| Sex | 2 | maybe | **fixed** (can't estimate variance from 2 levels) |
| Imaging session | varies | no | random |

**Rule**: >=5 levels and you want to generalise -> random. <5 levels OR specific levels matter -> fixed. Animal is almost always random; treatment is almost always fixed. With <5 levels you cannot reliably estimate a variance component, so fit as fixed and accept it as a nuisance.

---

## §10 Reporting checklist

For every mixed-model result, biologists must report: (a) full fixed + random formula, (b) software + version (`R.version.string`, `packageVersion("lme4")`), (c) df method (Satterthwaite/KR), (d) optimiser if non-default, (e) random-effects variances + ICC, (f) fixed-effect estimate with 95% CI and p-value, (g) any singular/convergence warnings and what was done about them. Save the script as `AI_Exports/stats_<date>.R`.
