# Voxel-ratio local-*k* gate — methods-section explanation

This document explains the **voxel-ratio local-*k* gate** used to identify amyloid-positive voxels in 4-channel confocal z-stacks. It is written so that sections can be lifted almost verbatim into a paper methods section with only light editing for target-journal voice and to fill in experimental specifics (fixation, mounting, microscope model).

Two audiences are served:

1. **Methods-section prose** (§ "Methods — paper-ready draft"). Self-contained; references nothing outside the section; uses symbols rather than column names or file paths.
2. **Rationale and implementation notes** (§ "Why this gate and how it works" onward). Expanded justification, parameter provenance, and limitations for reviewer rebuttal and supplementary material.

---

## Overview

The gate separates real fluorophore signal in the anti-amyloid channel from two confounds that contaminate it: tissue **autofluorescence (AF)** bleed from a spectrally adjacent channel, and **mCherry bleedthrough** from a reporter expressed in the same tissue. It does so at the voxel level rather than the object level, so that downstream size or morphology filters operate on a spatially honest mask.

Its key move is to replace the usual *single scalar* AF correction factor with a **spatially-varying local correction**, estimated in a 3-D neighbourhood around each voxel, so that the correction tracks the different AF spectra contributed by different tissue structures (lipofuscin in cell bodies, collagen and elastin in vasculature, broadband AF from pericellular matrix) rather than averaging them into a single per-image constant.

In plain terms: for each voxel we ask "how much signal in the anti-amyloid channel is *in excess of* what would be predicted from autofluorescence at this location?" and accept the voxel only if that excess is large enough to be unlikely under Poisson shot noise.

---

## Methods — paper-ready draft

### Image acquisition and preprocessing

Four-channel confocal z-stacks were acquired at 16-bit depth, 1024 × 1024 pixels in-plane and 16 optical sections per stack, with an in-plane voxel size of 0.284 μm and an axial step of 1 μm. Channels comprised DAPI (ch1), a spectrally adjacent filter into which no fluorophore was deliberately excited (ch2; used to report tissue autofluorescence), mCherry (ch3), and the anti-β-amyloid antibody MOAB-2 (ch4).

Each z-stack was preprocessed slice-wise as follows: a 2-D Gaussian blur (σ = 2 px), rolling-ball background subtraction (radius = 20 px), and a 2-D median filter (radius = 2 px). Preprocessed channels were exported as single-channel 16-bit TIFF stacks for downstream analysis. All subsequent operations use these preprocessed intensities.

### Biological premise

No fluorophore was excited into ch2 in this experiment; intensity in ch2 therefore reflects broadband tissue autofluorescence only. Because AF is broadband, it also contributes to the anti-amyloid channel (ch4). Locally, AF contributes to ch4 in approximately fixed proportion to its contribution to ch2, i.e. ch4<sub>AF</sub>(x) ≈ *k*(x)·ch2(x), where *k* is a tissue-dependent ratio. A scalar *k* per image assumes a single AF emission spectrum across the whole field of view; this is violated wherever the field contains multiple AF species (e.g. lipofuscin-rich cell bodies alongside collagen-rich vessels), which have different ch4:ch2 ratios. A spatially-varying estimate *k*<sub>local</sub>(x) addresses this.

Genuine MOAB-2 binding produces a ch4 signal *in excess of* the AF-predicted baseline; that excess — termed the *residual* — is the quantity the gate evaluates.

### Local *k* estimation

For each voxel x we computed

> *k*<sub>local</sub>(x) = ⟨ch4·*w*⟩(x) / (⟨ch2·*w*⟩(x) + ε)

where ⟨·⟩(x) denotes an unweighted mean over a 3-D box of 3 × 51 × 51 voxels centred on x (approximately 3 μm axial × 14.5 μm × 14.5 μm in-plane) implemented as a separable uniform filter, and *w*(x) is a binary weight that excludes candidate amyloid voxels from the correction:

> *w*(x) = 1 if ch4(x) ≤ *P*<sub>95</sub>(ch4 over the image), else 0.

The regularisation ε = 1 (in 16-bit intensity units) stabilises the ratio in regions where ch2 is near zero; its exact value has a negligible effect on voxels of interest, which have ch2 well above ε.

The footprint was chosen to be (i) large enough in-plane (~14.5 μm) to average out voxel-level shot noise and to span a contiguous region of any single AF species, and (ii) short enough axially (3 slices ≈ 3 μm) to track changes in AF spectrum between tissue layers. The amyloid-exclusion weight *w* prevents bright ch4 voxels — the very ones the gate is intended to detect — from pulling their own local *k* upward and so eliminating themselves from the residual.

### Gate

For every voxel:

> residual(x) = ch4(x) − *k*<sub>local</sub>(x)·ch2(x)
>
> SNR(x) = residual(x) / √max(ch4(x), 1)

> A voxel is classified **amyloid-positive** ⇔ ch3(x) < *m*<sub>3</sub> ∧ SNR(x) > τ<sub>SNR</sub>,

where *m*<sub>3</sub> is the per-image median of ch3. The √ch4 denominator converts the residual from raw intensity units into a Poisson-shot-noise-scaled SNR, consistent with photon-limited detection. The ch3 veto removes voxels dominated by mCherry bleedthrough.

### Calibrating τ<sub>SNR</sub>

τ<sub>SNR</sub> was calibrated cohort-wide from amyloid-free control tissue, restricted to the mCherry-quiet subspace to avoid contaminating the null distribution with bleedthrough:

> pool = { SNR(x) : image ∈ controls, ch3(x) < *m*<sub>3</sub> }

Across the control series this pool contained approximately 6.7 × 10⁷ voxels. τ<sub>SNR</sub> was set at the 99.99<sup>th</sup> percentile of this pool (τ<sub>SNR</sub> = 70.49 on this cohort), yielding a nominal voxel-level false-positive rate of 10⁻⁴ under the control null. The threshold is lower than the corresponding scalar-*k* threshold on the same cohort (83.79), because the local correction subtracts AF-driven tail mass that inflates the scalar-*k* pool.

### Quantification and statistics

Per-image amyloid-positive volume was computed as (count of passing voxels) × (calibrated voxel volume). Where two hemispheres of the same animal were imaged, hemisphere volumes were averaged within animal before group comparison, to respect biological independence. Group differences were assessed by Kruskal–Wallis non-parametric one-way ANOVA with Dunn's post-hoc test (tied-rank-corrected, jointly ranked across all groups) and Holm step-down correction for multiple comparisons at α = 0.05.

### Reproducibility

The pipeline is deterministic: given the preprocessed cohort, rerunning the gate reproduces τ<sub>SNR</sub>, all per-image masks, and all reported statistics bit-exactly. Code is archived with the manuscript.

---

## Why this gate and how it works

### What problem it solves

In thin-section fluorescence imaging of β-amyloid, the dominant source of false-positive voxels is not thermal or electronic noise — it is **tissue autofluorescence that happens to emit in the anti-amyloid channel**. Simply thresholding ch4 at a high level does not remove these false positives, because AF blobs can be as bright in ch4 as real plaques. Thresholding on ch4 minus a single global AF estimate (scalar *k*) removes much of the AF, but leaves behind a spatially structured residual because different tissue microdomains have different AF spectra.

The local-*k* gate works because it lets the correction factor *follow* the tissue. In a region dominated by lipofuscin, *k*<sub>local</sub> settles on the lipofuscin ratio; a few hundred voxels away in a region dominated by vessel-wall AF, it settles on the collagen/elastin ratio. The residual after subtraction is therefore close to zero wherever AF dominates — regardless of which AF species — and deviates from zero only where a real amyloid signal exists on top of the AF baseline.

### What each design choice buys

**Why weight out the bright-ch4 tail (*w*).** Without exclusion, a large amyloid plaque would pull its own local *k* upward, producing a residual of approximately zero inside the plaque and removing it from the mask. Excluding voxels above the ch4 95<sup>th</sup> percentile of the image decouples the correction from the signal it is meant to detect. The 95<sup>th</sup> percentile is a conservative choice: it is well above the distribution of genuinely AF voxels in these images (which are concentrated in the body of ch4) and well below the bright core of any real plaque.

**Why a 3 × 51 × 51 footprint.** The in-plane extent (~14.5 μm × 14.5 μm) exceeds the scale of shot-noise correlation and of typical single AF features but is smaller than the spatial scale on which the dominant AF species changes (cell body to cell body, vessel-to-interstitium). The axial extent (3 slices) is the minimum that still provides out-of-plane averaging without mixing laminae that have biologically distinct AF profiles.

**Why a uniform (box) filter rather than a Gaussian.** A separable uniform filter is O(n) in image size regardless of footprint radius, which is essential here because the footprint is large (≈ 7 800 voxels per voxel). A Gaussian of matched FWHM would give nearly identical *k*<sub>local</sub> on the scales of interest but at substantially higher cost. The box filter's slightly stronger edge response does not bias the ratio *k*, only its spatial smoothness.

**Why the Poisson-shot-noise denominator √ch4.** In photon-limited fluorescence, the variance of a background subtraction is dominated by the variance of the minuend; dividing by √ch4 turns the residual into an approximately variance-stabilised statistic that can be thresholded uniformly across the dynamic range. Without this step, τ<sub>SNR</sub> would have to vary with local brightness.

**Why ch3 < *m*<sub>3</sub> rather than ch3 < constant.** mCherry expression varies across animals and sections; any fixed ch3 threshold would be over-permissive in some images and over-strict in others. The per-image median is a robust, distribution-free summary that places the veto at the same percentile of the reporter distribution in every image.

**Why the 99.99<sup>th</sup>-percentile calibration.** The pool contains ~6.7 × 10⁷ control voxels. At the 99.99<sup>th</sup> percentile, ~6 700 control voxels per cohort are nominally above threshold, concentrated in the upper AF extremes — a budget small enough that object-level false-positive counts are dominated by genuine signal after the size filter, and large enough that the threshold is not sensitive to single outlier voxels.

### Limitations to state honestly

1. **Assumes approximately linear AF bleedthrough.** The model ch4 = *k*·ch2 + signal is linear. Saturated voxels, or regions where one channel is clipped, violate this; they are rare in these stacks but should be flagged if present.
2. **Control null is finite.** τ<sub>SNR</sub> is a percentile of a finite pool; its upper tail is estimated with limited precision. Sensitivity analysis at the 99<sup>th</sup>, 99.9<sup>th</sup>, and 99.99<sup>th</sup> percentiles should accompany the headline result.
3. **Footprint is fixed.** The 3 × 51 × 51 box works for this XY resolution and axial sampling; it is not transferable to other microscopes without re-justification in physical units (μm).
4. **Single AF reporter channel.** The gate generalises to any single-channel AF surrogate; with two or more AF surrogates, *k*<sub>local</sub> would become a vector and the residual a multi-regression.
5. **Object-level interpretation still requires a size filter.** Voxel-level gating leaves small clusters of passing voxels that are statistically real but biologically uninterpretable; downstream 3-D connected-component filtering (not described here) is needed before plaque counts are reported.

### Symbol glossary

| Symbol | Meaning |
|---|---|
| ch2(x), ch3(x), ch4(x) | Preprocessed 16-bit intensities at voxel x in the AF-reporter, mCherry, and anti-amyloid channels |
| *w*(x) | Binary amyloid-exclusion weight used in the *k*<sub>local</sub> estimate |
| *k*<sub>local</sub>(x) | Local AF bleedthrough ratio, estimated in a 3-D box around x |
| residual(x) | AF-corrected ch4 at voxel x |
| SNR(x) | Shot-noise-scaled residual |
| *m*<sub>3</sub> | Per-image median of ch3 |
| τ<sub>SNR</sub> | Cohort-wide SNR threshold, calibrated on control tissue |
| *P*<sub>95</sub>(ch4) | 95<sup>th</sup> percentile of ch4 within the image |

### Algorithmic summary

```
INPUT:   preprocessed stacks ch2, ch3, ch4 (one image)
PARAMS:  footprint F = (3, 51, 51) voxels
         amyloid-exclusion percentile p_excl = 95
         eps = 1
         tau_SNR  (cohort-level, calibrated once)

1. t_excl ← percentile(ch4, p_excl)                   # per-image
   w      ← (ch4 ≤ t_excl)                             # binary weight
2. num4   ← uniform_filter(ch4 * w, size = F)
   num2   ← uniform_filter(ch2 * w, size = F)
   denw   ← uniform_filter(w,        size = F)
   k_loc  ← (num4 / max(denw, 1e-3)) /
            ((num2 / max(denw, 1e-3)) + eps)
3. resid  ← ch4 - k_loc * ch2
   SNR    ← resid / sqrt(max(ch4, 1))
4. m3     ← median(ch3)                               # per-image
   mask   ← (ch3 < m3) AND (SNR > tau_SNR)
OUTPUT:  binary mask (amyloid-positive voxels)
```

Cohort-level calibration of τ<sub>SNR</sub>:

```
POOL ← concatenate( SNR(x) : image ∈ controls, ch3(x) < m3 )
tau_SNR ← percentile(POOL, 99.99)
```
