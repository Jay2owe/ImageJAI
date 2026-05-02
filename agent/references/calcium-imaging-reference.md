# Calcium Imaging Analysis Reference

Lab-agnostic reference for analysing calcium-indicator time-lapse data —
neurons, glia, cardiomyocytes, smooth muscle, organoids, cultured cells,
acute slices, in vivo two-photon, miniscope, light-sheet. Covers
indicator selection, acquisition modalities, motion correction
(StackReg, Correct 3D Drift, NoRMCorre, suite2p built-in), ROI/source
extraction (manual, mean-projection threshold, suite2p, CaImAn
CNMF/CNMF-E/OnACID, EXTRACT, StarDist/Cellpose for static), dF/F
pipelines, neuropil correction, photobleaching, event detection
(threshold + deconvolution: OASIS, MLspike, CASCADE), population /
network metrics (correlation, cross-correlogram, Kuramoto order
parameter, functional graphs), and visualisation.

Sources: GCaMP6 — Chen et al. 2013 *Nature*; jGCaMP7 — Dana et al. 2019
*Nat. Methods*; jGCaMP8 — Zhang et al. 2023 *Nature*; jRGECO/jRCaMP —
Dana et al. 2016 *eLife*; CaMPARI — Fosque et al. 2015 *Science*;
Fura-2 chemistry & Grynkiewicz equation — Grynkiewicz, Poenie & Tsien
1985 *J. Biol. Chem.*; chemical-indicator properties — ThermoFisher /
Molecular Probes Handbook; NoRMCorre — Pnevmatikakis & Giovannucci 2017
*J. Neurosci. Methods*; suite2p — Pachitariu et al. 2017 *bioRxiv*;
CaImAn — Giovannucci et al. 2019 *eLife*; EXTRACT — Inan et al. 2021
*Nat. Methods*; OASIS — Friedrich et al. 2017 *PLoS Comput. Biol.*;
MLspike — Deneux et al. 2016 *Nat. Comm.*; CASCADE — Rupprecht et al.
2021 *Nat. Neurosci.*; ImageJ plugin behaviour — `imagej.net/plugins/`,
Fiji wiki.

The Python pipeline (dF/F, event detection, synchrony, FFT,
correlation) is self-contained in `calcium_analysis.py` (§18), driven
off Multi-Measure CSV exports from ImageJ. ImageJ-side macros do
motion correction, ROI detection, and trace extraction. For dense
populations, in-vivo data, or when source separation matters, switch
to suite2p / CaImAn (§5.5) and skip the ImageJ-side trace step.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code for Fiji steps.
`python ij.py script '<code>'` — run Groovy/Jython/JavaScript when macros
can't reach a Java API.
`python calcium_analysis.py traces.csv --frame-rate <hz> --output <dir>` —
run the Python pipeline on exported traces.
`python -m suite2p` / `import caiman` — for source-extracted pipelines (§5.5).

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Which calcium indicator should I use?" | §3.4 decision tree, §3.1–§3.3 |
| "What's the brightest / fastest / most sensitive GCaMP?" | §3.1 GCaMP family table |
| "Should I use a synthetic dye or a GECI?" | §3.3 |
| "How do I image at depth in vivo?" | §3.5 acquisition modalities |
| "What frame rate do I need to resolve single APs?" | §3.5, §17 |
| "What's the dF/F equation and how do I pick a baseline?" | §6 |
| "How do I compute dF/F as an ImageJ stack?" | §6 dF/F Image Stack block |
| "How do I compute dF/F in Python?" | §6 Python block, §18 `compute_dff` |
| "How do I process Fura-2 ratiometric data?" | §7, §13.2 pipeline |
| "What's the Grynkiewicz equation for [Ca2+]?" | §7 |
| "How do I correct photobleaching?" | §8 |
| "Which motion-correction tool, when?" | §4.0 decision matrix |
| "Full NoRMCorre parameter list (max_shift, patches, overlaps)?" | §4.3 |
| "Suite2p built-in registration parameters?" | §4.4 |
| "Why must I avoid SIFT on calcium imaging?" | §4.6 |
| "How do I run suite2p end-to-end?" | §5.5.1 |
| "How do I run CaImAn CNMF / CNMF-E?" | §5.5.2 |
| "Which source-extraction tool — suite2p, CaImAn, EXTRACT?" | §5.5 decision tree |
| "How do I detect calcium events / transients?" | §9.1, §18 `detect_events` |
| "What SD threshold should I use for event detection?" | §9.1 threshold table, §17 |
| "How do I run OASIS deconvolution?" | §9.2 |
| "Which deconvolution algorithm — OASIS, MLspike, CASCADE?" | §9 decision matrix |
| "How do I validate spike inference against ephys?" | §9.5 |
| "How do I compute event frequency / IEI / FFT?" | §10 |
| "How do I measure population synchrony / correlation / lag?" | §11.1–§11.4 |
| "What's the Kuramoto order parameter?" | §11.5 |
| "How do I build a functional connectivity graph?" | §11.6 |
| "How do I cluster cells by activity?" | §11.7 |
| "How do I make a heatmap / raster / sync matrix?" | §11a |
| "Which ImageJ plugin does X?" | §12 |
| "Full single-channel GCaMP pipeline (ImageJ)?" | §13.1 |
| "Full Python pipeline (tifffile → suite2p → OASIS)?" | §13.4 |
| "How do I batch-process a folder of recordings?" | §13.3 |
| "Why are all my cells correlated artificially?" | §14 neuropil, §21 |
| "My StackReg fails / motion artefacts remain — what now?" | §4, §14 |
| "What frame rate / indicator for my application?" | §17 Acquisition by Application |
| "What decay tau should I expect for jGCaMP8f?" | §17 Indicator-Specific Detection |
| "Full Python pipeline script?" | §18 |
| "Custom ImageJ macros I can install as shortcuts?" | §19 |
| "Pseudoreplication — is the cell or the animal the unit?" | §21 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to every indicator, method, function, plugin, and
concept. Use `grep -n '<term>' calcium-imaging-reference.md` to jump to
the referenced section.

### A
`amplitude` §9 · `Analyze Particles` §5, §13.1, §13.3 · `AUC` §9, §18 ·
`auto cell detection` §5 · `autofocus` §14 · `acquisition modality` §3.5

### B
`background ROI` §5, §17 · `baseline` §6 · `baseline method` §6, §16 ·
`Bio-Formats Importer` §2, §13.1, §13.2 · `Bleach Correction` §8, §15, §21 ·
`bleaching` §8, §14, §21 · `bursting` §10 · `block-wise registration (suite2p)` §4.4

### C
`Cal-520 AM` §3, §17 · `calibration (Grynkiewicz)` §7 ·
`CaImAn` §4, §5.5.2, §9, §15, §16 · `CaManager` §12 ·
`CaMPARI (integrator)` §3.2 · `CASCADE (deconvolution)` §9.4, §16 ·
`Cellpose` §5.5.4, §14 · `cell clustering` §11.7 · `chemical indicators` §3.3 ·
`CNMF` §5.5.2 · `CNMF-E` §5.5.2 · `coherence` §11.4 ·
`Convert to Mask` §5, §13.1, §13.3 · `Correct 3D Drift` §4.5, §16 ·
`correlation matrix` §11.1, §11a · `cross-correlation / lag` §11.3 ·
`cross-correlogram` §11.3 · `CV (coefficient of variation)` §10, §19 ·
`CV (activity map)` §19

### D
`decay tau / decay time` §9, §17 · `Deinterleave` §7, §13.2 ·
`DeepCINAC` §9.4, §16 · `deconvolution (spike)` §9, §16 ·
`dF/F / dF/F0` §6, §18 · `dF/F (with background)` §6.4 ·
`dFoF-imagej` §12 · `dendrite (ROI)` §5 · `dense labeling` §14, §17 ·
`detection (events)` §9, §18 · `discriminability index (d')` §9.5 ·
`duration` §9

### E
`event detection` §9, §16, §18 · `event frequency` §10, §18 ·
`event raster` §11a · `event-triggered average` §11.6 ·
`Exponential Fit (bleach)` §8, §15 · `exponential fit baseline` §6 ·
`EXTRACT (source extraction)` §5.5.3

### F
`F0` §6 · `FAST` §12 · `FFT / power spectrum` §10 ·
`find_peaks` §9, §18 · `Fire (LUT)` §6, §7, §13.2 · `fixed window baseline` §6, §19 ·
`Fluo-3 / Fluo-4 AM` §3.3, §17 · `focus drift` §14 · `freely moving (motion)` §4 ·
`Fura-2 AM` §3.3, §7, §13.2, §17 · `functional connectivity (graph)` §11.6

### G
`GCaMP3` §3.1 · `GCaMP6f / GCaMP6m / GCaMP6s` §3.1, §17 ·
`GCaMP7c / GCaMP7f / GCaMP7s / GCaMP7b` §3.1 ·
`Gaussian Blur` §2, §5, §13.1, §13.3, §19 ·
`GECI (genetically encoded calcium indicator)` §3, §3.1, §8 ·
`Gradient projection (motion QC)` §4 · `Grouped Z Project` §14 ·
`Grynkiewicz calibration` §7

### H
`halos (motion QC)` §4 · `head-fixed (motion)` §4 · `heatmap (cells × time)` §11a ·
`Histogram Matching (bleach)` §8 · `Hyperstack` §7, §13.2

### I
`imageCalculator` §6, §7, §13.2, §19 · `indicator saturation` §14, §21 ·
`Indo-1` §3.3 · `inter-event interval (IEI)` §10, §17 · `in vivo (motion)` §4, §15 ·
`ionomycin (calibration)` §7

### J
`jGCaMP7c / jGCaMP7f / jGCaMP7s / jGCaMP7b` §3.1, §17 ·
`jGCaMP8f / jGCaMP8m / jGCaMP8s` §3.1, §17 ·
`jRCaMP1a / jRCaMP1b` §3.1 · `jRGECO1a / jRGECO1b` §3.1, §17 ·
`juxtacellular (validation)` §9.5

### K
`Kd (dissociation constant)` §3.3, §7 · `kinetics (rise / decay)` §3.1, §3.6 ·
`Kuramoto order parameter` §11.5

### L
`LeICA / Leica live` §3.5 · `Li (threshold)` §5 ·
`light-sheet (acquisition)` §3.5 · `line profile` (see `pixels.py` in agent CLAUDE.md) ·
`loading (indicator)` §21 · `Lucas–Kanade (motion)` §4.6

### M
`MCA (plugin)` §12 · `median baseline` §6 · `min duration` §9, §17 ·
`min interval` §9, §17 · `miniscope (UCLA / Inscopix)` §3.5 ·
`MLspike` §9.3, §16 · `mode-based F0` §6.3 · `modularity (network)` §11.6 ·
`moco` §4, §14, §16, §21 · `motion correction` §4, §16 ·
`Multi Measure` §2, §5, §12, §13.1, §13.3 ·
`multiplexing (optogenetics)` §3.1

### N
`networkx (graph analysis)` §11.6 · `neuropil` §5, §14, §17, §21 ·
`neuropil subtraction` §5.5.1, §6.5, §14, §21 · `noise SD` §9, §18 ·
`NoRMCorre` §4.3 · `Nyquist (kinetic)` §3.5, §17

### O
`OASIS (deconvolution)` §9.2, §16 · `OGB-1 / OGB-AM (Oregon Green BAPTA)` §3.3 ·
`one-photon (1P widefield)` §3.5 · `OnACID (online CNMF)` §5.5.2 ·
`organelle-targeted GCaMP` §3.2 · `organotypic slice (motion)` §4 ·
`overlap (cells)` §14

### P
`pacemaker-like activity` §10 · `peak-finding (`scipy.signal.find_peaks`)` §9.1, §18 ·
`percentile (sliding)` §6, §16 · `percentile_filter` §6, §18 ·
`photobleaching` §8, §14 · `piecewise rigid (NoRMCorre)` §4.3 ·
`Plot Z-axis Profile` §8 · `Poisson-like firing` §10 ·
`population coupling` §11.6 · `population synchrony` §11.5, §18 ·
`population vector decoding` §11.7 · `power spectrum` §10 ·
`probe (plugin args)` — see agent CLAUDE.md · `pseudoreplication` §21

### Q
`Quick Start` §2

### R
`raster plot` §11a · `ratiometric imaging` §3, §7, §13.2 ·
`ratio (F340/F380)` §7 · `reference region (bleach)` §8 · `Rhod-2 AM` §3.3, §17 ·
`rigid body (StackReg)` §4, §13.1, §13.3, §16 ·
`rigid translation (NoRMCorre)` §4.3 · `rise time` §9 ·
`Rmin / Rmax (Fura-2)` §7 · `ROI (soma/dendrite/neuropil/background)` §5, §17 ·
`ROI Manager (Multi Measure / Save / Deselect)` §2, §5, §13.1, §13.3

### S
`saturation (indicator)` §14, §21 · `sd_threshold` §9, §17, §18 ·
`Sf2 / Sb2 (Fura-2)` §7 · `SIFT (don't use on cells)` §4.6 ·
`Simple Ratio (bleach)` §8 · `sliding percentile` §6, §8, §16 ·
`soma (ROI)` §5 · `source separation (NMF)` §14 · `spectral analysis` §10 ·
`spike deconvolution / inference` §9, §16 ·
`StackReg Translation / Rigid Body` §4, §13.1, §13.3, §14, §16, §21 ·
`StarDist` §5, §5.5.4, §14 · `Stimulation artifact` §14 ·
`Subtract (background)` §7, §13.2 · `Suite2p` §4.4, §5.5.1, §9, §14, §15, §16 ·
`SynActJ` §12, §15 · `synchronisation matrix` §11a · `synchrony index` §11, §18 ·
`synthetic dye (AM ester)` §3.3

### T
`TACI` §12, §15 · `template (NoRMCorre)` §4.3 · `template matching (events)` §16 ·
`temporal SD projection (motion QC)` §4, §13.1, §16 ·
`threshold (event SD)` §9, §17, §18 · `tifffile (Python load)` §13.4, §15 ·
`Time Series Analyzer` §12 · `trace extraction` §5, §13.1, §13.3 ·
`Triangle (threshold)` §5, §13.1, §13.3 · `two-photon (2P)` §3.5, §8, §15, §17 ·
`TurboReg / StackReg` §4.2

### U
`ultrafast kinetics (jGCaMP8f)` §3.1

### V
`viral / transgenic (GECI delivery)` §3.1, §3.6 · `voltage indicator (GEVI)` §3.6

### W
`wavelet (CWT, event detection)` §10, §16 · `Watershed` §5, §13.1, §13.3 ·
`widefield (acquisition)` §3.5, §17 · `window size (sliding percentile)` §6, §17

### X
`x-correlation / xcorr` §11.3

### Y
`(no entries)`

### Z
`Z Project (Average / Max / Standard Deviation / Median / Sum)` §4, §5, §7,
§13.1, §13.3, §19 · `z-drift (TACI)` §12 · `z-scored dF/F` §6.6

---

## §2 Quick Start

```
Open recording (Bio-Formats)
  -> Motion correct (StackReg rigid body / NoRMCorre / suite2p)
    -> Draw ROIs on mean projection (soma + background + neuropil)
      -> Multi Measure all ROIs across all frames
        -> Export CSV
          -> Python: subtract background, compute dF/F0, detect events
```

```bash
# 1. Open and inspect
python ij.py macro 'run("Bio-Formats Importer", "open=/path/to/recording.tif color_mode=Default view=Hyperstack stack_order=XYCZT");'
python ij.py info
python ij.py metadata

# 2. Motion correct
python ij.py macro 'run("StackReg", "transformation=[Rigid Body]");'

# 3. Mean projection for ROI placement
python ij.py macro 'run("Z Project...", "projection=[Average Intensity]"); rename("AVG_projection");'
python ij.py capture after_projection

# 4. Auto-detect cells
python ij.py macro '
  selectWindow("AVG_projection");
  run("Duplicate...", "title=for_detection");
  run("Gaussian Blur...", "sigma=3");
  run("Find Maxima...", "prominence=500 output=[Point Selection]");
  run("ROI Manager..."); roiManager("Add");
'

# 5. Multi Measure on original stack
python ij.py macro '
  selectWindow("recording.tif");
  run("Set Measurements...", "mean redirect=None decimal=6");
  roiManager("Multi Measure");
'
python ij.py results
```

For data with significant motion, dense populations, or in-vivo
two-photon, prefer the Python source-extraction pipeline (§13.4)
over manual ROI tracing.

---

## §3 Indicator Selection

Calcium indicators fall into three families: genetically encoded
(GECIs — GCaMP, jGCaMP, jRGECO, jRCaMP, GECO), synthetic dyes
(Fura-2, Indo-1, Fluo-4, Cal-520, Rhod-2, OGB) loaded as AM esters,
and integrators (CaMPARI). Sub-cellular targeting variants exist for
mitochondria, ER, plasma membrane, nucleus.

### §3.1 GCaMP / jGCaMP / GECO Family (single-fluorophore GECIs)

GCaMPs are circularly permuted EGFP fused to calmodulin and the M13
peptide of myosin light-chain kinase. Calcium binding triggers a
conformational change that brightens the fluorophore. Successor
families (jGCaMP7, jGCaMP8) used directed evolution with screening on
neurons to improve kinetics, brightness, and dF/F.

| Variant | Year | Rise t1/2 (ms) | Decay t1/2 (ms) | dF/F (1AP) | Kd (nM) | Notes |
|---|---|---|---|---|---|---|
| GCaMP3 | 2009 | ~80 | ~600 | ~5% | ~660 | Legacy. Slow, dim. Avoid for new work. |
| GCaMP5G | 2012 | ~50 | ~300 | ~10% | ~460 | Improved on GCaMP3 — superseded by GCaMP6. |
| GCaMP6s | 2013 | 179 | 550 | 22–25% | 144 | Slow, brightest 6-series. Sums APs. |
| GCaMP6m | 2013 | 80 | 250 | 14–18% | 167 | General purpose middle-ground. |
| GCaMP6f | 2013 | 45 | 142 | 11–18% | 375 | Fast 6-series. Standard for in-vivo 2P. |
| jGCaMP7c | 2019 | 50 | 250 | ~30% | ~250 | High contrast, low resting brightness. |
| jGCaMP7f | 2019 | 27 | 265 | ~22% | ~174 | Fast, comparable to 6f but brighter resting. |
| jGCaMP7s | 2019 | 75 | 300 | ~70% | ~68 | Highest dF/F of the 7 series. Slow. |
| jGCaMP7b | 2019 | 50 | 360 | ~30% | ~82 | Bright at rest — spine / dendritic imaging. |
| jGCaMP8f | 2023 | 2 | 56 | ~18% | 334 | Ultrafast. Resolves single APs at >100 Hz. |
| jGCaMP8m | 2023 | 5 | 80 | ~24% | 108 | Fast + sensitive. Good general-purpose. |
| jGCaMP8s | 2023 | 10 | 180 | ~38% | 46 | Maximum single-AP sensitivity. |

Red-shifted GECIs — useful for dual-colour imaging with optogenetic
activators (e.g. ChR2 blue-shifted excitation), or imaging through
hemoglobin-rich tissue:

| Variant | Ex/Em (nm) | Decay t1/2 (ms) | dF/F (1AP) | Kd (nM) | Notes |
|---|---|---|---|---|---|
| jRGECO1a | 561/595 | ~140 | ~22% | ~148 | Standard red GECI. Photoswitching artifact at 405. |
| jRGECO1b | 561/595 | ~150 | ~33% | ~180 | Higher dF/F than 1a. Slightly slower. |
| jRCaMP1a | 565/620 | ~190 | ~13% | ~180 | More red-shifted than jRGECO. Lower dF/F. |
| jRCaMP1b | 565/620 | ~160 | ~21% | ~712 | Lower-affinity (good for high-Ca compartments). |
| K-GECO1 | 568/594 | ~180 | ~12% | ~165 | Alternative red. Less photoswitching than jRGECO. |
| XCaMP-R | 593/623 | ~80 | ~30% | ~97 | Far-red, single-AP sensitive. |

**Trade-off (universal across families):** slow / high-affinity /
high-Kd-low variants (s) give the brightest response per spike and
sum well over bursts, but smooth over rapid firing and saturate at
high Ca²⁺. Fast / low-affinity (f) variants resolve individual spikes
but with smaller per-spike dF/F. Medium (m) is a default starting point.

### §3.2 Organelle / Compartment-Targeted GCaMPs

Calmodulin-based GECIs are mistargeted by default to any compartment
through fusion with localisation tags. Common variants:

| Target | Tag (typical) | Variants | Rationale |
|---|---|---|---|
| Cytosol | (none) | All GCaMP/jGCaMP, jRGECO/jRCaMP | Default — bulk cytosolic Ca²⁺. |
| Mitochondrial matrix | 2× COX8 presequence | mito-GCaMP6, mito-GCaMP6m, 4mtGCaMP | Mitochondrial Ca²⁺ uptake (MCU activity). |
| ER lumen | calreticulin / KDEL retention | erGCaMP6 (CEPIA), G-CEPIAer | ER Ca²⁺ stores; needs **low-affinity** indicator. |
| Plasma membrane | Lyn / palmitoyl / GPI | Lck-GCaMP, mem-GCaMP | Sub-membrane microdomains. |
| Nucleus | NLS (SV40) | NLS-GCaMP6 | Nuclear Ca²⁺ — slower than cytosolic. |
| Synaptic terminal | synaptophysin / synapsin | SyGCaMP6 | Pre-synaptic Ca²⁺. |
| Post-synaptic density | PSD-95 | PSD-GCaMP | Post-synaptic Ca²⁺. |

**ER indicators must be low-affinity** (CEPIA1er Kd ≈ 565 µM, GEM-CEPIA1er
Kd ≈ 11 µM) because resting ER Ca²⁺ is 100 µM–1 mM. Standard GCaMP6
is saturated at rest in the ER and useless.

### §3.2 CaMPARI — Photoconvertible Integrator

CaMPARI (Ca²⁺-modulated photoactivatable ratiometric integrator) and
CaMPARI2 are *not* used like real-time GECIs. Photoconversion from
green to red is gated by simultaneous high [Ca²⁺] **and** 405 nm light:

```
[Ca2+]high  +  405nm UV  ->  green-to-red photoconversion (irreversible)
[Ca2+]low   +  405nm UV  ->  no conversion
[Ca2+]high  + no UV       ->  no conversion
```

You expose the sample to UV during a behaviour window or stimulation
epoch, then image at any time afterward. Red/green ratio is a permanent
record of integrated activity during the UV window. Useful for tagging
populations of active cells in freely-moving animals or thick samples
that resist real-time imaging.

### §3.3 Synthetic (Chemical) Indicators

AM-ester forms (e.g. Fluo-4 AM) are membrane-permeant; cytosolic
esterases cleave the ester to trap the polar dye inside cells.
"Bulk loading" stains all cells indiscriminately; "pressure injection"
or single-cell electroporation gives sparse loading.

| Indicator | Ex/Em (nm) | Kd (nM) | Type | Notes |
|---|---|---|---|---|
| Fura-2 AM | 340, 380 / 510 | 224 | Ratiometric (excitation) | Gold standard for absolute [Ca²⁺]. UV ex. |
| Indo-1 AM | 346 / 405, 485 | 230 | Ratiometric (emission) | Better for confocal (single ex. line). |
| Fluo-3 AM | 506 / 526 | 390 | Single-λ | Older. Fluo-4 has same Kd, brighter. |
| Fluo-4 AM | 494 / 516 | 345 | Single-λ | Standard green dye for 488 nm. |
| Cal-520 AM | 494 / 514 | 320 | Single-λ | Brightest green dye; superior SNR vs Fluo-4. |
| Cal-590 AM | 574 / 588 | 561 | Single-λ | Red dye, multiplexable with green optogenetics. |
| Rhod-2 AM | 552 / 581 | 570 | Single-λ | Red. Loads preferentially into mitochondria. |
| OGB-1 AM | 488 / 525 | 170 | Single-λ | Oregon Green BAPTA-1. Higher affinity than Fluo-4. |
| Mag-Fluo-4 | 493 / 517 | 22000 | Single-λ | Low affinity for ER Ca²⁺ stores. |
| GCaMP-equivalent low-Kd | (varies) | 5–20 µM | (see §3.2) | For ER lumen. |

**AM-loading caveats:**
- DMSO + Pluronic F-127 to disperse AM ester (typical: 0.02% Pluronic).
- Loading 30–60 min at 37 °C; longer for thick tissue.
- De-esterification time matters — dye can compartmentalise into
  organelles if you image too early.
- Probenecid (1–2 mM) blocks anion-transporter dye extrusion.

### §3.3 GECI vs Synthetic Dye — Decision Matrix

| Question | GECI | Synthetic |
|---|---|---|
| Can you express transgenes? (viral / transgenic / transfection) | ✓ | — |
| Need cell-type specificity (Cre-driven)? | ✓ | ✗ |
| Need long-term / chronic imaging (weeks)? | ✓ | ✗ |
| Acute primary culture / slice — same day? | possible | ✓ |
| Cardiomyocyte / smooth-muscle workhorse? | possible (cardiac-specific GCaMP exists) | ✓ (Fluo-4, Cal-520, Fura-2) |
| Need absolute [Ca²⁺] (calibrated nM)? | difficult | ✓ Fura-2 / Indo-1 (Grynkiewicz) |
| Need organelle targeting? | ✓ trivial | ✗ (Rhod-2 mitochondria only) |
| Don't have access to virus / genetics? | ✗ | ✓ |
| Sub-cellular Ca²⁺ buffering matters? | exogenous calmodulin can buffer | dye buffers Ca²⁺ too — keep low |

### §3.4 Indicator Decision Tree

```
Need absolute [Ca2+] in nM? -> Fura-2 (ratiometric, calibratable via Grynkiewicz)
  NO -> Cell-type specificity needed (Cre line, viral targeting)?
    YES -> GECI (GCaMP / jGCaMP / jRGECO depending on speed)
  NO -> In vivo deep imaging (>200 um)?
    YES -> GCaMP + two-photon (or jGCaMP8, far-red XCaMP-R for >500 um)
  NO -> Need to resolve individual spikes at high firing rates?
    YES -> jGCaMP8f (or 8m) — kinetics fast enough for >50 Hz
  NO -> Need maximum sensitivity (subthreshold / sparse activity)?
    YES -> jGCaMP8s, GCaMP6s, or jGCaMP7s
  NO -> Multiplex with optogenetics?
    Blue-light stim (ChR2) -> jRGECO1a / jRCaMP1b (red GECI)
    Red-light stim (Chrimson) -> GCaMP (green GECI)
  NO -> Acute experiment, no genetics? -> Chemical (Fluo-4 AM, Cal-520 AM)
  NO -> Chronic imaging, transgenic line available? -> GECI (transgenic / viral)
  NO -> Tag population that was active during window X? -> CaMPARI
  NO -> Organelle Ca2+ (mito, ER, nucleus)? -> Targeted GCaMP (low-Kd for ER)
```

### §3.5 Acquisition Modalities

The optical system constrains achievable frame rate, depth, sample
volume, and indicator brightness budget. Choose modality first, then
indicator.

| Modality | Depth | FOV | Frame rate | Sample motion | Bleaching | Best indicators |
|---|---|---|---|---|---|---|
| 1P widefield (epifluorescence) | <50 µm | mm² | 10–100 Hz (CCD/CMOS) | low (in vitro) | high | Fluo-4, GCaMP for sparse |
| 1P confocal (laser scan) | <100 µm | <500 µm | 1–30 Hz | low | high | GCaMP, Fura-2 |
| 1P spinning-disk | <50 µm | <500 µm | 30–100 Hz | low | medium | GCaMP, Fluo-4 |
| 2P scanning | up to 800 µm | 200–500 µm | 5–30 Hz (galvo), 30–500 Hz (resonant) | medium (in vivo) | low | GCaMP6/7/8, jRGECO |
| 2P + remote focusing / multiplexed | up to 800 µm | 100–200 µm × multi-plane | 5–15 Hz/plane | medium | low | GCaMP6/7/8 |
| Light-sheet (SPIM, lattice, mesoSPIM) | mm-scale (cleared / transparent) | mm² | 1–10 Hz/plane volumetric | low | low | GCaMP, panneuronal |
| Miniscope (UCLA, Inscopix) | <300 µm (GRIN lens) | <1 mm² | 10–60 Hz | high (freely moving) | medium | GCaMP6/7/8 |
| Endomicroscopy (fiber) | deep (mm) | <500 µm | 5–30 Hz | high | medium | GCaMP |

**Frame rate vs photobleaching tradeoff.** Higher frame rate ⇒ shorter
per-frame integration ⇒ usually higher excitation power per frame ⇒
more photobleaching and phototoxicity. The constraints couple: a
target SNR sets a minimum photons/frame; a target temporal resolution
sets a maximum exposure time. Together they pin a minimum acceptable
indicator brightness × dF/F.

**Nyquist on event kinetics.** To resolve a transient with rise time
τ_r and decay time τ_d, sample at ≥2/τ_min. For jGCaMP8f
(τ_d ≈ 56 ms), you need >36 Hz. For GCaMP6s (τ_d ≈ 550 ms),
≥4 Hz suffices. **Critical**: under-sampling a fast indicator
*aliases* fast events into slow apparent traces — events look like
broad oscillations. Better to use a slower indicator at low frame rate
than a fast indicator under-sampled.

| Sample frame rate | Resolves indicator with τ_d ≥ ... | Suitable for ... |
|---|---|---|
| 1 Hz | 2 s | Calcium waves (astrocyte, organoid), slow oscillations |
| 5 Hz | 0.4 s | GCaMP6s, Fura-2 (ratiometric loop), bursting |
| 10 Hz | 0.2 s | GCaMP6s, GCaMP6m, GCaMP7s, Cal-520 |
| 30 Hz | 70 ms | GCaMP6f, jGCaMP8m, jRGECO1a |
| 100 Hz | 20 ms | jGCaMP8f, cardiac myocytes (Fluo-4) |
| 500 Hz | 4 ms | Cardiac action potentials with voltage indicators |

### §3.6 Beyond Calcium — Kinetic Caveat

Calcium indicators are **proxies for spiking**, not voltage. The
calcium transient lags the action potential by 5–50 ms (rise) and
decays over 50–500 ms; bursts that fire faster than the indicator
decay sum into a single hump. If you need millisecond-scale voltage,
use a genetically-encoded voltage indicator (GEVI: ASAP3, Voltron,
JEDI-2P) — different reference document.

---

## §4 Motion Correction

Calcium imaging is exquisitely sensitive to motion: a 1-pixel shift at
a cell edge produces an apparent dF/F that mimics a real transient.
Motion correction is the single most important preprocessing step.

### §4.0 Decision Matrix

| Preparation | Typical motion | First-line method | Fall-back |
|---|---|---|---|
| Cultured cells (coverslip) | ≤1 px drift over hours | StackReg Translation, or skip | Manual translation |
| Acute brain slice | slow drift, 1–3 px / 10 min | StackReg Rigid Body | Correct 3D Drift |
| Organotypic slice (long) | slow drift over hours | StackReg Rigid Body | Correct 3D Drift, Fast4DReg |
| In vivo (head-fixed, awake) | breathing/heartbeat, 1–5 px | NoRMCorre rigid, or suite2p built-in | Piecewise rigid (NoRMCorre) |
| In vivo (anaesthetised, head-fixed) | slow drift, 1–3 px | StackReg Rigid Body or NoRMCorre rigid | suite2p |
| In vivo (freely moving, miniscope) | >10 px, non-rigid | NoRMCorre piecewise rigid | Suite2p block-wise + bidirectional offset |
| Light-sheet volumetric | 3D drift | Correct 3D Drift, Fast4DReg | NoRMCorre 3D |
| Cardiac myocyte (contracting) | full-cell deformation | non-rigid (NoRMCorre piecewise / bUnwarpJ) | Phase-rebinning before correction |

### §4.1 Methods Overview

| Method | Where it lives | Speed | Rigid? | Non-rigid? | Notes |
|---|---|---|---|---|---|
| StackReg Translation / Rigid Body / Affine | Fiji (BIG-EPFL) | fast | ✓ | (Affine ≈ shear-tolerant only) | Good default for in-vitro. |
| TurboReg | Fiji | fast | ✓ | — | Underlies StackReg. Same algorithm. |
| HyperStackReg | Fiji | fast | ✓ | — | StackReg over hyperstack channels/frames. |
| Correct 3D Drift | Fiji (Manders lab) | medium | ✓ (3D) | — | Volumetric drift over time. |
| moco | Fiji (Dubbs et al.) | fast | ✓ | — | FFT-based, large search range. |
| Fast4DReg | Fiji (BioImaging Group) | fast | ✓ (4D) | — | Optimised 4D, GPU-accelerated. |
| NoRMCorre | Python (CaImAn), MATLAB | medium | ✓ | ✓ | The standard for in-vivo 2P. §4.3. |
| Suite2p built-in | Python | medium | ✓ | block-wise | Tightly integrated with suite2p ROI step. §4.4. |
| CaImAn pipeline | Python | medium | ✓ | ✓ | Wraps NoRMCorre + CNMF. |
| bUnwarpJ | Fiji | slow | — | ✓ (B-spline) | Static, not great for time series. |
| Lucas–Kanade tracker | Fiji | fast | ✓ | — | Patch tracking. Local rigid. |
| Manual frame-by-frame | Fiji | slow | ✓ | — | Last-resort, freely-moving fixes. |

### §4.2 StackReg / TurboReg (Fiji)

StackReg aligns each frame to the previous frame (or to a reference)
using TurboReg's pyramid-based intensity registration.

```javascript
// Translation only (fastest, XY shifts only)
run("StackReg", "transformation=Translation");

// Rigid body (XY translation + rotation — standard default)
run("StackReg", "transformation=[Rigid Body]");

// Affine (rotation, scaling, shear — careful, can over-fit and warp data)
run("StackReg", "transformation=Affine");
```

**Gotcha**: StackReg modifies the active stack in-place. Always
duplicate first if you need the raw data. StackReg uses 32-bit pivot;
output is 32-bit even from 16-bit input. Memory-cost runs about 2×
input.

**When StackReg fails** (rejects the alignment, garbled output): the
common cause is sudden brightness changes (e.g. uncaging flash) that
break the intensity-based correlation. Mask or interpolate over the
flash frames first.

### §4.3 NoRMCorre (CaImAn / Python)

NoRMCorre (Pnevmatikakis & Giovannucci 2017) is the standard motion
correction for 2P in-vivo data. It does both rigid and **piecewise
rigid** (image divided into overlapping patches, each shifted
independently, shifts smoothed across patches). Key design choice:
a template image is iteratively refined from a running mean of
already-corrected frames.

```python
from caiman.motion_correction import MotionCorrect
import caiman as cm

# Start a local cluster (NoRMCorre parallelises over chunks)
c, dview, n_processes = cm.cluster.setup_cluster(
    backend='local', n_processes=None, single_thread=False)

mc = MotionCorrect(
    fnames='recording.tif',
    dview=dview,
    max_shifts=(6, 6),                # max rigid shift in (y, x) pixels
    strides=(48, 48),                 # patch step (pixels). Larger = fewer patches
    overlaps=(24, 24),                # overlap between patches
    max_deviation_rigid=3,            # max non-rigid deviation from rigid shift
    pw_rigid=True,                    # piecewise-rigid (False = rigid only)
    shifts_opencv=True,               # apply shifts via OpenCV (faster)
    nonneg_movie=True,                # enforce non-negative output (for CNMF)
    border_nan='copy',                # how to fill border (0, 'copy', 'min')
    use_cuda=False,
    niter_rig=1,                      # rigid iterations before piecewise
)

mc.motion_correct(save_movie=True)
fname_mc = mc.fname_tot_els if mc.pw_rigid else mc.fname_tot_rig

# Inspect shifts
import numpy as np
shifts_rig = np.array(mc.shifts_rig)              # (n_frames, 2)
print("Mean rigid shift:", np.mean(np.abs(shifts_rig), axis=0))
if mc.pw_rigid:
    print("Piecewise shifts shape:", np.array(mc.x_shifts_els).shape)
```

**Parameter cheat sheet** (FOV-relative — adjust for your pixel size):

| Parameter | Description | Starting value | When to change |
|---|---|---|---|
| `max_shifts` | Max rigid translation (y, x) pixels | (6, 6) for 512² in vivo | Increase to (10, 20) for awake / freely-moving. |
| `strides` | Patch step size | (48, 48) for 512² | Smaller (e.g. 24, 24) for highly non-rigid motion. |
| `overlaps` | Patch overlap | (24, 24) | Half of strides typically. |
| `max_deviation_rigid` | Max patch deviation from rigid | 3 | Increase to 5–10 for severe non-rigid distortion. |
| `pw_rigid` | Piecewise rigid on/off | True for in-vivo | False if rigid-only is enough (saves time). |
| `niter_rig` | Rigid iterations | 1 | 2–3 if first-pass template is noisy. |
| `gSig_filt` | Gaussian for template | None (2P), (7, 7) (1P) | Set for 1P data with low-frequency background. |

**For 1P / miniscope data**, set `gSig_filt = (7, 7)` (or roughly the
neuron radius in pixels) so the template-matching ignores the
slowly-varying background fluorescence that dominates 1P recordings.

**Diagnosing a bad NoRMCorre run.**
- Plot `mc.shifts_rig` over time. A few-pixel oscillation = real
  breathing/heartbeat. Monotonic drift = real slow drift. Spikes that
  exactly track image-content changes = registration is locking onto
  the bright cells (template starvation).
- `np.std(corrected_movie, axis=0)` (temporal SD): clean cell outlines
  are good; comet-tail halos around cells = residual motion.
- If piecewise patches show wildly different shifts (`mc.x_shifts_els`
  variance > a few px patch-to-patch), patches are too small — increase
  strides.

### §4.4 Suite2p Built-In Registration

Suite2p (`ops.do_registration = 1`) does block-wise rigid registration
in a single pass before ROI extraction. It is tightly integrated with
the rest of the suite2p pipeline.

```python
from suite2p.run_s2p import run_s2p, default_ops

ops = default_ops()
ops.update({
    'do_registration': 1,
    'two_step_registration': True,    # rigid first, then non-rigid (default for non-2P data)
    'nonrigid': True,                  # block-wise non-rigid
    'block_size': [128, 128],          # block size in px (must divide image evenly)
    'snr_thresh': 1.2,                 # SNR threshold above which to apply block shifts
    'maxregshift': 0.1,                # max rigid shift as fraction of FOV
    'maxregshiftNR': 5,                # max block-wise shift in px on top of rigid
    'reg_tif': True,                   # save the registered TIFF
    'reg_tif_chan2': False,
    'smooth_sigma': 1.15,              # Gaussian smoothing of template (px)
    'smooth_sigma_time': 0,            # temporal smoothing (frames). >0 for low-SNR.
    'subpixel': 10,                    # subpixel precision (10 = 0.1 px)
    'do_bidiphase': False,             # correct line-scan bidirectional offset (resonant 2P)
    'th_badframes': 1.0,                # frames with displacement > th_badframes × stdev are flagged
})

db = {'data_path': ['/path/to/recording_dir'], 'tiff_list': ['recording.tif']}
output_ops = run_s2p(ops=ops, db=db)
```

**Bidirectional offset (`do_bidiphase`)** — resonant-galvo 2P
microscopes scan alternating lines in opposite directions; small
phase offsets between forward and backward lines cause a sawtooth
artifact that registration cannot fix. Set `do_bidiphase=True` once
to detect and correct.

### §4.5 Correct 3D Drift (Fiji)

```javascript
// channel=N: which channel to use as registration reference
// only=0: do not exclude any frames (1 = use only one frame as reference)
run("Correct 3D Drift", "channel=1 only=0 lowest=1 highest=1");
```

For volumetric (z-stack over time) data. Underlies the registration
in TACI's pipeline. Slow but robust on z-drift. Use it on the
brightest, most stable channel; apply the resulting transforms to
other channels.

### §4.6 Why **Not** SIFT on Calcium Imaging

SIFT (Scale-Invariant Feature Transform) detects feature points by
local extrema in scale-space and matches them across frames. On
fluorescent data with **moving cells** (active calcium events,
brightness changes), SIFT pairs feature points that are *not the same
object* — bright cell at frame t pairs to a different bright cell at
frame t+1, the algorithm "corrects" by warping the field, and the
remaining cells are mangled.

**Use crop-based Correct 3D Drift instead** for active fluorescent
data: pick a stable structural reference (cell body outline at
average projection) and register on that. Or use intensity-based
methods (StackReg, NoRMCorre) which do not rely on feature matching.

| Use SIFT for ... | Don't use SIFT for ... |
|---|---|
| Static structural images (fixed tissue, EM) | Live calcium imaging (any indicator) |
| Stitching tile mosaics | Time-lapse with active reporters |
| Whole-slide alignment | Voltage / Ca²⁺ / kinase / FRET sensors |

### §4.7 Quality Checks

```bash
# Temporal SD projection — halos around cells = residual motion
python ij.py macro '
  run("Z Project...", "projection=[Standard Deviation]");
  rename("temporal_SD");
'
python ij.py capture temporal_SD
```

Clean cell outlines in the temporal SD = good correction. Halos
(comet tails) = incomplete. **Mean projection** is also useful: cells
should be sharp, with the same edge profile they had in any individual
frame. If the mean projection has soft, washed-out cells but the
single-frame edges are crisp, motion correction failed.

```python
# Diagnostic: residual shift estimate via temporal cross-correlation
import numpy as np
def residual_motion(stack):
    """Returns max-displacement between consecutive frames in pixels."""
    from scipy.ndimage import shift as nd_shift
    from skimage.registration import phase_cross_correlation
    n = stack.shape[0]
    shifts = []
    for t in range(1, n):
        s, _, _ = phase_cross_correlation(stack[t-1], stack[t], upsample_factor=10)
        shifts.append(s)
    return np.array(shifts)
```

If `np.max(np.abs(shifts))` > 0.5 px after motion correction,
something is residual.

---

## §5 ROI Selection & Trace Extraction (Manual / Threshold)

### §5.1 ROI Types

| ROI Type | Typical size | SNR | Notes |
|---|---|---|---|
| Soma | 8–20 µm (neurons), 5–10 µm (glia) | high | Main cytosolic signal source. |
| Nucleus | 5–10 µm | medium-high | Slower kinetics than soma — Ca²⁺ diffusion-delayed. |
| Dendrite | 1–3 µm wide | low (small ROI = few photons) | Average over a long stretch to recover SNR. |
| Spine | <1 µm | very low (2P only) | Use jGCaMP8 or jGCaMP7b. |
| Axon / bouton | 1–2 µm | low | SyGCaMP for pre-synaptic. |
| Neuropil annulus | 20–40 µm outer | varies | For background contamination correction. |
| Background | any rectangle in cell-free area | (offset / dark current) | For absolute baseline subtraction. |

### §5.2 Automated Cell Detection (mean-projection threshold)

```javascript
// Threshold-based detection on mean projection
selectWindow("mean_proj");
run("Duplicate...", "title=for_detection");
run("Gaussian Blur...", "sigma=3");  // merge pixels into cell-sized blobs
setAutoThreshold("Triangle dark");    // consider Triangle, Otsu, or Li
run("Convert to Mask");
run("Watershed");                     // separate touching cells
run("Analyze Particles...", "size=50-2000 circularity=0.3-1.0 add");
// Adjust size range based on cell diameter and pixel scale
```

**When this works:** stable cells (no motion through the focal plane,
no significant lateral drift), reasonable cell-density (no extensive
overlap), uniform brightness. When cells fire actively, the *mean*
projection captures the average soma — usually fine.

**When this fails:** silent cells (no signal in any frame), highly
overlapping cells, gradient illumination, dense labeling.

**Standard deviation projection** picks up only *active* cells —
silent cells disappear. Use it when you only want active cells, not
when you want *all* candidate cells.

### §5.3 Static Segmentation (StarDist / Cellpose)

For densely packed cells, deep-learning-based segmentation (StarDist
or Cellpose) on the mean / max-intensity projection often beats
threshold-based detection.

```javascript
// StarDist on mean projection (cross-link: ai-image-analysis-reference.md)
selectWindow("mean_proj");
run("Command From Macro", "command=[de.csbdresden.stardist.StarDist2D], "
    + "args=['input':'mean_proj', 'modelChoice':'Versatile (fluorescent nuclei)', "
    + "'normalizeInput':'true', 'probThresh':'0.5', 'nmsThresh':'0.3', "
    + "'outputType':'ROI Manager']");
```

```python
# Cellpose 2D from Python (after exporting mean projection)
from cellpose import models
import tifffile
img = tifffile.imread("mean_proj.tif")
mdl = models.Cellpose(model_type='cyto2')
masks, flows, styles, diams = mdl.eval(img, diameter=None, flow_threshold=0.4)
# masks is a label image; convert to ImageJ ROIs via labels-to-ROIs
```

**Both work best on a mean / median projection rather than a single
frame** — averaging suppresses noise, and a stable cell boundary is
more reliable than any one frame.

### §5.4 Background ROI

Always include a background ROI for subtraction:

```javascript
makeRectangle(10, 10, 30, 30);  // adjust to cell-free area
roiManager("Add");
roiManager("Select", roiManager("count") - 1);
roiManager("Rename", "Background");
```

**Validation**: plot the background trace. It should be flat (no
events). If it shows transients, you accidentally placed it on a
cell — move it. If it has the same slow drift as the cell traces,
it's capturing illumination flicker / detector drift — good, that's
exactly what you want to subtract.

### §5.5 Source-Extracted Pipelines (suite2p, CaImAn, EXTRACT)

For dense populations, in-vivo data, or when threshold-based
detection misses cells, switch to source-extraction tools that
detect cells *and* extract de-mixed traces in one step.

#### §5.5.1 suite2p (Pachitariu et al., 2017)

Suite2p is the modern de-facto standard for 2P data. The pipeline is:

```
TIFFs -> registration (rigid + block-wise non-rigid)
       -> ROI detection (sparse-PCA-based footprint extraction)
       -> trace extraction (ROI mean - r * neuropil)
       -> spike inference (OASIS)
       -> GUI for manual curation
```

```python
from suite2p.run_s2p import run_s2p, default_ops

ops = default_ops()
ops.update({
    # Acquisition
    'fs': 30.0,                        # frame rate Hz (CRITICAL — sets all temporal params)
    'tau': 0.7,                        # decay time constant of indicator (s). 0.7 GCaMP6f, 1.25 GCaMP6m, 2.0 GCaMP6s
    'nplanes': 1,                      # number of imaging planes
    'nchannels': 1,                    # number of channels
    'functional_chan': 1,              # which channel is the functional (1-indexed)

    # Registration
    'do_registration': 1, 'nonrigid': True, 'block_size': [128, 128],

    # ROI detection
    'sparse_mode': True,               # default: True for 2P, False for 1P
    'spatial_scale': 0,                # 0 = auto. Else 1/2/3/4 corresponds to 6/12/24/48 px diameter
    'threshold_scaling': 1.0,          # higher = stricter ROI (fewer cells)
    'max_overlap': 0.75,               # max overlap between ROIs (fraction)
    'high_pass': 100,                  # high-pass filter window (frames)
    'smooth_masks': True,
    'max_iterations': 20,
    'nbinned': 5000,                   # number of binned frames for ROI detection

    # Neuropil
    'allow_overlap': False,
    'inner_neuropil_radius': 2,         # px gap between cell and neuropil annulus
    'min_neuropil_pixels': 350,        # min px in neuropil annulus
    'neucoeff': 0.7,                   # F_corrected = F - neucoeff * F_neuropil

    # Spike inference (OASIS)
    'spikedetect': True,
    'baseline': 'maximin',             # 'maximin', 'constant', 'prctile'
    'win_baseline': 60.0,              # baseline window (s)
    'sig_baseline': 10.0,              # gaussian filter for baseline (s)
    'prctile_baseline': 8,             # percentile if baseline='prctile'

    # Misc
    'save_mat': False,                 # save also as .mat
    'combined': True,                  # combine planes
    'save_NWB': False,
})

db = {'data_path': ['/path/to/recording_dir'], 'tiff_list': ['recording.tif']}
output_ops = run_s2p(ops=ops, db=db)

# Outputs in suite2p/plane0/:
#   F.npy          (n_rois, n_frames) raw fluorescence
#   Fneu.npy       (n_rois, n_frames) neuropil fluorescence
#   spks.npy       (n_rois, n_frames) deconvolved spike trace
#   stat.npy       per-ROI dict (ypix, xpix, footprint, npix, ...)
#   ops.npy        ops dict + image arrays
#   iscell.npy     (n_rois, 2) [is_cell_bool, classifier_probability]
```

**The GUI is essential.** Run `python -m suite2p` and load the output
folder. Manually flag false-positive ROIs (vasculature, dendritic
crossings, overly-bright pixels) and false negatives (missed cells in
the mean projection). The classifier improves with manual labels.

```python
# Load suite2p outputs into Python
import numpy as np
F = np.load('suite2p/plane0/F.npy')           # (n_rois, n_frames)
Fneu = np.load('suite2p/plane0/Fneu.npy')
iscell = np.load('suite2p/plane0/iscell.npy') # (n_rois, 2)
spks = np.load('suite2p/plane0/spks.npy')     # deconvolved (a.u.)

# Filter to curated cells, apply neuropil correction
keep = iscell[:, 0].astype(bool)
F_corr = F[keep] - 0.7 * Fneu[keep]
```

**Tau selection by indicator** (suite2p's `ops['tau']`):

| Indicator | tau (s) |
|---|---|
| jGCaMP8f | 0.06 |
| jGCaMP8m | 0.1 |
| GCaMP6f | 0.4 |
| jGCaMP8s | 0.18 |
| GCaMP6m | 1.0 |
| GCaMP6s | 2.0 |
| jRGECO1a | 0.7 |

#### §5.5.2 CaImAn — CNMF, CNMF-E, OnACID

CaImAn (Calcium Imaging Analysis) is more flexible than suite2p,
supporting 1P (CNMF-E) and online (OnACID), and integrates NoRMCorre
motion correction. The trade-off: more parameters to tune.

**CNMF (2P / 1P with little background)** factorises the movie as
`Y(t) = A · C(t) + B + ε`, where `A` is a sparse spatial footprint
matrix, `C` is the temporal trace, `B` is background.

```python
import caiman as cm
from caiman.source_extraction.cnmf import cnmf as cnmf
from caiman.source_extraction.cnmf.params import CNMFParams

# Start cluster
c, dview, n_processes = cm.cluster.setup_cluster(backend='local', n_processes=None)

opts_dict = {
    'fr': 30,                    # frame rate Hz
    'decay_time': 0.4,           # indicator decay (s) — like suite2p tau
    'fnames': ['mc_recording.tif'],
    # Motion correction (NoRMCorre — see §4.3)
    'pw_rigid': True, 'max_shifts': (6, 6),
    'strides': (48, 48), 'overlaps': (24, 24), 'max_deviation_rigid': 3,
    # Spatial
    'p': 1,                      # AR order for deconvolution (1 or 2)
    'gnb': 2,                    # number of background components
    'merge_thr': 0.85,           # ROI merge threshold (correlation)
    'rf': 15,                    # patch half-size (px). Smaller = more parallel, more boundaries.
    'stride': 6,                 # patch overlap
    'K': 4,                      # candidate ROIs per patch
    'gSig': [4, 4],              # half-size of expected neuron (px) — CRITICAL parameter
    'method_init': 'greedy_roi', # 'greedy_roi' (2P), 'corr_pnr' (1P CNMF-E)
    # Quality
    'min_SNR': 2.0,
    'rval_thr': 0.85,            # space correlation threshold
    'use_cnn': True,
    'min_cnn_thr': 0.99,
}
opts = CNMFParams(params_dict=opts_dict)

# Fit
images = cm.load(opts.data['fnames'][0])
cnm = cnmf.CNMF(n_processes, params=opts, dview=dview)
cnm = cnm.fit(images)

# Refit on full FOV (post-patch) for cleanup
cnm = cnm.refit(images, dview=dview)

# Quality
cnm.estimates.evaluate_components(images, cnm.params, dview=dview)

print(f"Accepted: {len(cnm.estimates.idx_components)}, "
      f"Rejected: {len(cnm.estimates.idx_components_bad)}")

# Outputs:
# cnm.estimates.A                     spatial footprints (sparse, n_pixels x n_components)
# cnm.estimates.C                     denoised temporal traces (n_components x n_frames)
# cnm.estimates.S                     deconvolved spike trace
# cnm.estimates.f                     background temporal
# cnm.estimates.b                     background spatial
# cnm.estimates.YrA                   residual signal (raw - denoised)
# cnm.estimates.idx_components        accepted ROI indices
```

**CNMF-E (1P / miniscope / fiber endoscopy)** — uses `corr_pnr`
initialisation and a ring-shaped background model that handles the
slowly-varying out-of-focus fluorescence dominating 1P recordings.

```python
opts_1p = {
    # ... base params ...
    'method_init': 'corr_pnr',
    'min_corr': 0.8,             # correlation threshold for seed pixels
    'min_pnr': 10,               # peak-to-noise ratio threshold
    'ring_size_factor': 1.4,     # background ring radius / cell radius
    'center_psf': True,
    'normalize_init': False,
    'gnb': 0,                     # NO global background for 1P
    'nb_patch': 0,
    'low_rank_background': None,
    'p_ssub': 1,                  # spatial down-sample (CNMF-E often uses 1)
    'p_tsub': 2,                  # temporal down-sample
}
```

**OnACID (online CNMF)** — processes frames as they arrive, useful for
real-time / streaming analysis or for very long recordings that don't
fit in memory.

```python
from caiman.source_extraction.cnmf.online_cnmf import OnACID

opts.change_params({'init_method': 'bare', 'init_batch': 200, 'K': 5})
cnm = OnACID(params=opts)
cnm.fit_online()
```

#### §5.5.3 EXTRACT (Inan et al., 2021)

EXTRACT (a robust matrix-factorisation cell extractor, MATLAB primary,
Python port available) is designed to handle **crosstalk** from
overlapping or out-of-focus cells better than CNMF/CNMF-E. It uses
robust statistics (one-sided Huber loss) to ignore contamination.

```matlab
% MATLAB syntax (primary implementation)
config = get_defaults([]);
config.avg_cell_radius = 6;             % px
config.num_partitions_x = 1;
config.num_partitions_y = 1;
config.use_gpu = 1;
config.cellfind_min_snr = 1.5;
config.kappa_std_ratio = 1.0;
config.spatial_lowpass_cutoff = 5;

output = extractor(M, config);          % M is the motion-corrected stack
% output.spatial_weights, output.temporal_weights
```

**When to use EXTRACT:** dense populations where CNMF mixes
neighbours, or when the imaging quality varies (out-of-focus
fluorescence, blood vessels, partial cell visibility). The robust
loss makes it less sensitive to non-Gaussian contamination than
CNMF's L2.

#### §5.5.4 StarDist / Cellpose for Quasi-Static Cells

When cells barely move and lateral overlap is the main challenge,
deep-learning instance segmentation on the mean projection is
simpler and faster than CNMF. See `references/ai-image-analysis-reference.md`.

#### Source-Extraction Decision Tree

```
2P data, dense neurons, want spike inference, willing to curate?
  -> suite2p (with GUI curation)

1P / miniscope / fiber endoscopy?
  -> CaImAn CNMF-E

Streaming / online / very long recording?
  -> CaImAn OnACID

Dense overlap, expected crosstalk?
  -> EXTRACT (or CaImAn with manual merging)

Cells barely move, you want footprints quickly?
  -> StarDist / Cellpose on mean projection -> Multi Measure (§13.1)

Quick exploratory analysis, one ROI per cell, prefer GUI?
  -> ImageJ (mean projection -> Triangle threshold -> Watershed -> Particles)
```

### §5.6 Trace Extraction (ImageJ Multi Measure)

```javascript
// Multi Measure: extracts mean intensity per ROI per frame
selectWindow("registered_stack");
run("Set Measurements...", "mean redirect=None decimal=6");
roiManager("Deselect");
roiManager("Multi Measure");
// Result: columns Mean1, Mean2, ..., MeanN; one row per frame
```

### §5.7 Save / Load ROIs

```javascript
roiManager("Deselect");
roiManager("Save", "/path/to/ROIs.zip");
// Load: roiManager("Open", "/path/to/ROIs.zip");
```

---

## §6 Baseline & dF/F Calculation

### §6.1 The Equation

```
dF/F0 = (F(t) - F0) / F0                              # standard
dF/F0 = (F(t) - F0_cell) / (F0_cell - F0_background)  # alternative (§6.4)
z(t)  = (F(t) - μ_baseline) / σ_baseline               # z-scored (§6.6)
```

Where F(t) is **background-subtracted** fluorescence and F0 is the
baseline. Choice of F0 is the single biggest source of variability
in published calcium analyses.

### §6.2 Baseline (F0) Methods

| Method | Formula | Best For | Avoid When |
|---|---|---|---|
| Fixed window | mean(F[1:N]) | Stimulation with defined quiet pre-stim | Spontaneous activity |
| Sliding window mean | mean(F[t-W/2:t+W/2]) | Smooth slow trends | Active periods bias upward |
| Sliding percentile (8th) | percentile_8(F[t-W/2:t+W/2]) | Spontaneous, long recordings | Cell active >80% of time |
| Sliding percentile (20th) | percentile_20(window) | Moderate activity | Very high or very low activity |
| Median | median(F) | Infrequent events (<50% active) | Frequent activity, bleaching |
| Mode (KDE) | argmax(KDE(F)) | Histograms with clean baseline peak | Bimodal histograms (off-on cells) |
| Exponential fit | A·exp(-t/τ) + C | Chemical indicators with bleaching | GECIs with minimal bleaching |
| Polynomial detrend | polyfit(t, F, deg) | Smooth long-term drift | Sharp transients suppressed |
| Maximin (suite2p `'maximin'`) | min(gauss(F)) → max | Spontaneous, robust | Recordings with very long quiet periods |

**Window-size heuristic** for sliding percentile: typically 20–60 s,
or 5–10× the longest expected inter-event interval (IEI). The window
must span enough quiet time for the percentile to land below event
peaks.

**Percentile heuristic**: 8th percentile is the most common default
for active neurons. If cells are active >80% of the time, drop to 5th
percentile and increase window to 60–120 s. If cells fire only
occasionally, 20th–50th percentile is fine.

### §6.3 Mode-Based F0

When the histogram of F has a clear baseline peak (most common case
for sparsely-active cells), the mode of the distribution is a robust
F0 — better than mean, better than median for skewed distributions.

```python
from scipy.stats import gaussian_kde
import numpy as np

def mode_f0(trace, n_grid=200):
    """Mode via Gaussian KDE."""
    kde = gaussian_kde(trace)
    grid = np.linspace(trace.min(), trace.max(), n_grid)
    return grid[np.argmax(kde(grid))]
```

For long recordings with bleaching, apply a sliding mode (the same
function over a sliding window).

### §6.4 dF/F With Background Subtraction

A more conservative form properly handles the case where F0_cell
includes a background offset (autofluorescence, dark current):

```
dF/F = (F_cell(t) - F_cell_baseline) / (F_cell_baseline - F_background)
```

If `F_background` is well-measured, this gives a *photon-budget-faithful*
dF/F. The denominator collapses to F0 if background is subtracted
upstream, making the two forms equivalent — but only if you actually
subtracted the background first.

### §6.5 Neuropil Correction

For in-vivo or dense populations, light from surrounding neuropil
contaminates the soma signal. Correct by:

```
F_corrected(t) = F_soma(t) - r · F_neuropil(t)
F0 from F_corrected(t)
```

`r` typical values:
- 0.7 — suite2p default. Works for general dense GCaMP populations.
- 0.5–0.6 — sparse labeling (less neuropil contribution).
- 0.8–0.9 — dense pan-neuronal labeling.

The neuropil ROI is an annulus around the soma (typical: outer 15–20
px from soma centre, inner gap of 2 px to exclude the soma itself).
Suite2p computes neuropil per-ROI automatically. CaImAn folds it into
the CNMF model as a low-rank background term.

```python
def neuropil_subtraction(f_cell, f_neuropil, r=0.7):
    """r=0.7 (suite2p default). Range: 0.5 sparse, 0.8-0.9 dense labeling."""
    return f_cell - r * f_neuropil
```

**Warning:** if r is chosen too high, neuropil correction *over-subtracts*
and creates artefactual negative dF/F dips coincident with population
events. Visual check: the corrected trace should not go strongly
negative when the population is active.

### §6.6 z-Scored dF/F

```python
z_dff = (F - F0) / σ_noise
```

where σ_noise is estimated from a quiet baseline period (lowest-N
percentile of F). Use when you want events on a unit-free scale
comparable across cells with different absolute brightness, or when
feeding into ROC / classifier pipelines. **Do not** use z-scored
dF/F for cross-day quantitative comparisons (σ_noise depends on
acquisition).

### §6.7 Python dF/F

```python
import numpy as np
from scipy.ndimage import percentile_filter

def compute_dff(traces, frame_rate, method='sliding_percentile',
                baseline_frames=30, window_s=30.0, percentile=8):
    """
    Compute dF/F. traces shape: (n_cells, n_frames).
    Returns dff same shape.
    """
    if method == 'fixed_window':
        f0 = np.mean(traces[:, :baseline_frames], axis=1, keepdims=True)
    elif method == 'sliding_percentile':
        win = int(window_s * frame_rate)
        if win % 2 == 0: win += 1
        f0 = np.zeros_like(traces)
        for i in range(traces.shape[0]):
            f0[i] = percentile_filter(traces[i], percentile, size=win)
    elif method == 'median':
        f0 = np.median(traces, axis=1, keepdims=True)
    elif method == 'mode':
        from scipy.stats import gaussian_kde
        f0 = np.zeros((traces.shape[0], 1))
        for i in range(traces.shape[0]):
            kde = gaussian_kde(traces[i])
            grid = np.linspace(traces[i].min(), traces[i].max(), 200)
            f0[i, 0] = grid[np.argmax(kde(grid))]
    elif method == 'exponential':
        from scipy.optimize import curve_fit
        f0 = np.zeros_like(traces)
        for i in range(traces.shape[0]):
            t = np.arange(traces.shape[1]) / frame_rate
            try:
                popt, _ = curve_fit(lambda t,A,tau,C: A*np.exp(-t/tau)+C, t, traces[i],
                                    p0=[traces[i,0]-traces[i,-1], 1000.0, traces[i,-1]],
                                    maxfev=2000)
                f0[i] = popt[0]*np.exp(-t/popt[1]) + popt[2]
            except RuntimeError:
                f0[i] = np.mean(traces[i])  # fall back
    f0 = np.maximum(f0, 1e-6)
    return (traces - f0) / f0
```

### §6.8 dF/F Image Stack in ImageJ

```javascript
title = getTitle();
run("Z Project...", "start=1 stop=30 projection=[Average Intensity]");
rename("F0");
selectWindow(title); run("32-bit");
selectWindow("F0"); run("32-bit");
selectWindow(title);
run("Duplicate...", "title=dFF duplicate");
imageCalculator("Subtract stack", "dFF", "F0");
imageCalculator("Divide stack", "dFF", "F0");
selectWindow("dFF");
setMinAndMax(-0.1, 2.0);
run("Fire");
```

**Gotcha**: Do NOT clip negative dF/F values to zero — they contain
information (noise below baseline, hyperpolarisation-driven Ca²⁺
decreases, indicator off-rate dynamics).

---

## §7 Ratiometric Imaging (Fura-2 / Indo-1)

### §7.1 Ratio Calculation

Fura-2: R = F340/F380 increases with [Ca²⁺], independent of dye
concentration and bleaching (because both wavelengths bleach
equivalently, ratio is preserved).

Indo-1: R = F405/F485 (single-excitation, dual-emission — easier on
confocal).

```javascript
// For interleaved stack (340, 380, 340, 380, ...)
title = getTitle();
run("Deinterleave", "how=2");
selectWindow(title + " #1"); rename("F340"); run("32-bit");
selectWindow(title + " #2"); rename("F380"); run("32-bit");

// Background subtract (measure bg in cell-free region first)
bg340 = 100;  // REPLACE with measured value
bg380 = 120;  // REPLACE with measured value
selectWindow("F340"); run("Subtract...", "value=" + bg340 + " stack");
selectWindow("F380"); run("Subtract...", "value=" + bg380 + " stack");

// Ratio
imageCalculator("Divide create 32-bit stack", "F340", "F380");
rename("Ratio_340_380");
setMinAndMax(0.2, 3.0);  // adjust to data
run("Fire");
```

For 2-channel hyperstacks, use `run("Split Channels");` instead of
Deinterleave.

### §7.2 Grynkiewicz Calibration to [Ca²⁺]

```
[Ca2+] = Kd * ((R - Rmin) / (Rmax - R)) * (Sf2 / Sb2)
```

| Parameter | Definition | How to obtain |
|---|---|---|
| Kd | Dissociation constant | ~224 nM at 37 °C, ~135 nM at 22 °C |
| Rmin | Ratio at zero Ca²⁺ | EGTA + ionomycin at end of experiment |
| Rmax | Ratio at saturating Ca²⁺ | High Ca²⁺ + ionomycin at end of experiment |
| Sf2 | F380 at zero Ca²⁺ | Measured during Rmin calibration |
| Sb2 | F380 at saturating Ca²⁺ | Measured during Rmax calibration |

```python
def grynkiewicz(ratio, Kd=224, Rmin=0.3, Rmax=5.0, Sf2=800, Sb2=200):
    ratio_clipped = np.clip(ratio, Rmin + 1e-6, Rmax - 1e-6)
    return Kd * ((ratio_clipped - Rmin) / (Rmax - ratio_clipped)) * (Sf2 / Sb2)
```

**Calibration pitfalls.** In-cell calibration with ionomycin can fail
in cells with strong sequestration (e.g. cardiac myocytes load
heavily into SR), giving artificially low Rmax. Try thapsigargin +
ionomycin to deplete SR + plasma membrane stores. Always quote the
calibration method used (in-cell vs in-cuvette).

---

## §8 Photobleaching Correction

### §8.1 When It Matters

| Factor | Effect on bleaching |
|---|---|
| Chemical dyes vs GECIs | Chemical dyes bleach faster (no replenishment) |
| UV excitation (Fura-2, Indo-1) | More bleaching than visible |
| Higher excitation intensity | More bleaching (super-linear at high powers) |
| Two-photon | Less bleaching than confocal (focal volume only) |
| Wide-field vs confocal | Wide-field illuminates entire stack; bleaches more |

**Gotcha**: Correct bleaching BEFORE computing dF/F — but the sliding
percentile method partially handles bleaching automatically because
the percentile tracks a slowly-changing baseline.

### §8.2 Correction Methods

| Method | Command / approach | Best for |
|---|---|---|
| Exponential Fit | `run("Bleach Correction", "correction=[Exponential Fit]");` | Most cases |
| Simple Ratio | `run("Bleach Correction", "correction=[Simple Ratio] background=0");` | Uniform bleaching |
| Histogram Matching | `run("Bleach Correction", "correction=[Histogram Matching]");` | **Visualisation only** — NOT quantitative |
| Sliding-percentile dF/F | (inherent in baseline method) | Combined bleaching + baseline |
| Reference region | Normalise to stable non-responsive region | When stable reference exists |
| Polynomial detrend (Python) | `numpy.polyfit(t, F, deg)` | Smooth drift, no sharp transients |

**Critical pitfall:** Histogram Matching is *not* quantitative. It
normalises the histogram to the first frame, which both removes
bleaching *and* removes any genuine slow change in mean signal. Use
for visual presentation only.

### §8.3 Quick Bleach Check

```javascript
// Plot mean intensity over time
run("Plot Z-axis Profile");
// Downward slope = bleaching is significant
```

```python
# Quantitative bleach check
def bleach_fraction(trace):
    """Fraction of initial mean lost over recording (positive = bleached)."""
    n = len(trace); window = max(10, n // 20)
    initial = np.mean(trace[:window])
    final = np.mean(trace[-window:])
    return 1.0 - final / initial
```

If `bleach_fraction > 0.05` (>5%), apply correction. If
`bleach_fraction > 0.5` (>50%), correction won't recover the late
data — reduce excitation and re-run.

### §8.4 Reducing Bleaching at the Source

- Lower excitation intensity (longer exposure to compensate, only if
  motion budget allows).
- Anti-fade additives (Trolox, ascorbate, oxyrase) for chronic
  imaging — though for in-vivo work and many cell types this is
  impractical.
- Two-photon — restricts excitation to the focal point.
- Lower-Ca²⁺-affinity indicator (less time in saturated state, less
  photobleaching from triplet state buildup).

---

## §9 Event Detection & Spike Inference

### §9.1 Threshold-Based Event Detection (Standard)

| Parameter | Definition | Unit |
|---|---|---|
| Amplitude | Peak dF/F − baseline | dF/F |
| Rise time | 10% to 90% of peak | seconds |
| Decay time (τ) | Peak to 50% (or 1/e) of peak | seconds |
| Duration | Time above threshold (FWHM) | seconds |
| AUC | Integral of dF/F during event | dF/F·seconds |

| Threshold | Character | When to consider |
|---|---|---|
| 2 SD | Sensitive, more false positives | Low SNR, don't want to miss events |
| 3 SD | Standard balance | General-purpose starting point |
| 4–5 SD | Conservative, few false positives | High SNR, only large events |
| 7+ SD | Very strict | Only the biggest "real" events; ignores small |

```python
from scipy.signal import find_peaks

def detect_events(dff_trace, frame_rate, sd_threshold=3.0,
                  min_duration_s=0.1, min_interval_s=0.5):
    """Detect calcium transients via threshold crossing."""
    min_duration = int(min_duration_s * frame_rate)
    min_interval = int(min_interval_s * frame_rate)

    baseline_vals = dff_trace[dff_trace < np.percentile(dff_trace, 50)]
    if len(baseline_vals) < 10: baseline_vals = dff_trace
    noise_sd = np.std(baseline_vals)
    noise_mean = np.mean(baseline_vals)
    threshold = noise_mean + sd_threshold * noise_sd

    peaks, _ = find_peaks(dff_trace, height=threshold, distance=min_interval,
                          prominence=sd_threshold * noise_sd * 0.5)

    events = []
    for peak_idx in peaks:
        # Find onset (backward search for threshold crossing)
        onset = peak_idx
        for j in range(peak_idx - 1, -1, -1):
            if dff_trace[j] < threshold:
                onset = j + 1; break
        # Find offset (forward search)
        offset = peak_idx
        for j in range(peak_idx + 1, len(dff_trace)):
            if dff_trace[j] < threshold:
                offset = j; break
        else:
            offset = len(dff_trace) - 1

        if offset - onset < min_duration: continue

        amplitude = dff_trace[peak_idx] - noise_mean
        events.append({
            'onset_frame': onset, 'peak_frame': peak_idx,
            'offset_frame': offset,
            'onset_time_s': onset / frame_rate,
            'amplitude': amplitude,
            'rise_time_s': (peak_idx - onset) / frame_rate,
            'decay_time_s': (offset - peak_idx) / frame_rate,
            'duration_s': (offset - onset) / frame_rate,
            'auc': np.trapz(dff_trace[onset:offset+1] - noise_mean) / frame_rate
        })
    return events
```

### §9.2 OASIS — Online Active Set Spike Inference (Friedrich et al. 2017)

OASIS deconvolves the calcium trace into an underlying spike train
under an autoregressive (AR1 or AR2) model:

```
F(t) = sum_k a_k * h(t - t_k) + noise
```

where `h(t)` is the indicator's impulse response (a sum of decaying
exponentials) and `t_k` are inferred spike times. OASIS is the
deconvolution backend in suite2p (`ops['spikedetect'] = True`).

```python
from oasis.functions import deconvolve, estimate_parameters

# Single trace deconvolution. trace is dF/F or raw F.
c, s, b, g, lam = deconvolve(trace.astype(np.float64),
                              penalty=1,         # 1 = L1 penalty (sparse spikes)
                              optimize_g=5,      # 5 = optimise tau over 5 iterations
                              max_iter=5)
# c: denoised trace, s: inferred spike trace (continuous a.u.)
# g: AR coefficients (related to tau via tau = -1/log(g))
# lam: optimised L1 penalty
```

Or specify the indicator decay manually:

```python
# Convert tau (seconds) to AR1 g
fr = 30.0  # frame rate Hz
tau = 0.4  # GCaMP6f decay
g = np.exp(-1.0 / (fr * tau))

c, s, b, _, _ = deconvolve(trace, g=(g,), penalty=1, optimize_g=0)
```

**OASIS outputs are *not* spike counts**, they're a continuous "spike
trace". Threshold to get binary events:

```python
spike_threshold = np.std(s[s < np.percentile(s, 90)]) * 2.5
spike_events = s > spike_threshold
spike_count_per_frame = (s > spike_threshold).astype(int)
```

The suite2p output `spks.npy` is exactly the OASIS s-trace per ROI.

### §9.3 MLspike — Maximum-Likelihood Spike Inference (Deneux et al. 2016)

MLspike (MATLAB primarily) is a Viterbi-decoded spike-inference under
a more biophysical model — saturating indicator, baseline drift, and
calibrated per-indicator parameters. Widely cited as the most
accurate spike inference for medium-to-high-SNR calcium data when an
indicator's biophysical parameters are well-known.

```matlab
% MATLAB syntax (primary implementation)
par = tps_mlspikes('par');
par.dt = 1/30;                          % frame interval (s)
par.a = 0.07;                           % dF/F per spike (indicator-dependent)
par.tau = 0.4;                          % decay time constant (s)
par.finetune.sigma = 0.02;              % noise sigma
par.drift.parameter = 0.01;             % drift smoothness
par.algo.estimate_drift = true;
[spk, fit, drift] = spk_est(trace, par);
```

When to use MLspike over OASIS: when you need calibrated spike counts
(not relative spike trace), and you know the indicator a, τ, σ
parameters from independent experiments.

### §9.4 CASCADE — Deep-Learning Spike Inference (Rupprecht et al. 2021)

CASCADE is a CNN trained on a corpus of paired ephys + calcium
recordings, learns to map dF/F traces to spike rates without
indicator-specific tuning. Good when:
- You don't know indicator kinetics precisely.
- Data is heterogeneous (multiple imaging conditions).
- You want spike *rates* in Hz, not relative spike trace.

```python
# CASCADE installation: pip install cascade2p
from cascade2p import cascade

# Pre-trained models trained on different indicator + frame rate combinations
spike_rates = cascade.predict(model_name='Global_EXC_30Hz_smoothing50ms',
                              traces=dff_array)   # (n_neurons, n_frames)
# spike_rates: (n_neurons, n_frames) instantaneous spike rate (Hz)
```

Available models cover GCaMP6f/m/s, jGCaMP7, jGCaMP8 at common frame
rates. Match the model to your data closely.

### §9.5 Validation with Electrophysiology

The gold standard for validating any spike-inference algorithm is
simultaneous calcium imaging + cell-attached patch (loose-patch /
juxtacellular) recording on the same cell.

| Validation metric | Definition |
|---|---|
| Spike-matching F1 | True positives / (TP + 0.5(FP + FN)) within Δt window (e.g. ±50 ms) |
| Pearson correlation | corr(inferred spike rate, ground-truth spike rate, smoothed) |
| Discriminability d' | (μ_event − μ_quiet) / sqrt(0.5(σ_event² + σ_quiet²)) |
| Sensitivity / specificity | At a chosen threshold |

Typical published OASIS / MLspike F1 scores for GCaMP6f:
- Single APs: 0.4–0.6 (hard — many missed at low SNR).
- Bursts ≥ 3 APs: 0.8–0.9.
- Population events: >0.95.

**Critical caveat**: spike inference performance falls off a cliff
below ~3 SD per-spike SNR. If your dF/F per AP is below this, even
the best algorithm gives a noisy estimate.

### §9.6 Method Choice Matrix

```
High SNR, single-cell precision matters? -> MLspike (calibrated) or OASIS (denoised)
Population analysis, accept relative spike trace? -> OASIS (suite2p built-in)
Heterogeneous data, no calibration? -> CASCADE
Low SNR, mostly need event detection? -> Threshold (§9.1) on dF/F
Need per-spike timing for STA (spike-triggered average)? -> MLspike or CASCADE
Real-time / online? -> OASIS (online algorithm)
```

---

## §10 Frequency & Spectral Analysis

### §10.1 Event Frequency

```python
def event_frequency(events, total_duration_s):
    n = len(events)
    return n / total_duration_s, n / total_duration_s * 60  # Hz, per min
```

### §10.2 Inter-Event Interval Interpretation

| IEI pattern | Indicates |
|---|---|
| Regular (low CV ~0.1) | Pacemaker-like activity |
| Random (CV ~1, exponential distribution) | Poisson-like firing |
| Bimodal (short + long peaks) | Burst firing |
| CV > 1 | Clustered/bursty activity |

```python
def iei_stats(events):
    onsets = np.array([e['onset_time_s'] for e in events])
    iei = np.diff(np.sort(onsets))
    return {'mean_iei_s': float(np.mean(iei)),
            'cv_iei': float(np.std(iei) / (np.mean(iei) + 1e-9)),
            'median_iei_s': float(np.median(iei))}
```

### §10.3 FFT Power Spectrum

```python
def compute_power_spectrum(dff, frame_rate):
    """Returns (freqs_hz, power_spectral_density)."""
    n = len(dff)
    dff_centered = dff - np.mean(dff)
    window = np.hanning(n)
    fft_vals = np.fft.rfft(dff_centered * window)
    psd = (2.0 / n) * np.abs(fft_vals) ** 2
    freqs = np.fft.rfftfreq(n, d=1.0/frame_rate)
    return freqs, psd
```

For non-stationary oscillations (frequency changes over time), use
continuous wavelet transform (`scipy.signal.cwt` with `morlet2`
wavelet) or short-time Fourier transform (`scipy.signal.stft`).

```python
from scipy.signal import stft
f, t_seg, Z = stft(dff_centered, fs=frame_rate, nperseg=int(20*frame_rate))
# |Z|^2 is the time-frequency power.
```

---

## §11 Population & Network Analysis

### §11.1 Pearson Correlation Matrix

```python
corr_matrix = np.corrcoef(dff_all)  # dff_all shape: (n_cells, n_frames)
```

Pearson correlation captures linear co-fluctuation. For event-driven
data with skewed distributions, prefer Spearman (rank-based):

```python
from scipy.stats import spearmanr
spearman_matrix, _ = spearmanr(dff_all, axis=1)
```

**Validation**: shuffle one trace's frames, compute correlation —
this is the chance level. Real correlations should be well above
this. For *N* cells, the expected chance Pearson r ≈ 1/sqrt(*T*−1)
where *T* is the number of frames.

### §11.2 Synchrony Index (Variance-Ratio)

A common, simple synchrony metric: variance of population mean over
mean of single-cell variances. 0 = independent, 1 = perfectly
synchronous.

```python
def population_synchrony(dff_all):
    """0 = independent, 1 = perfectly synchronous."""
    n_cells = dff_all.shape[0]
    var_pop = np.var(np.mean(dff_all, axis=0))
    var_ind = np.mean(np.var(dff_all, axis=1))
    sync = var_pop / (var_ind + 1e-10)
    sync_norm = (sync - 1.0/n_cells) / (1.0 - 1.0/n_cells)
    return np.clip(sync_norm, 0, 1)
```

### §11.3 Cross-Correlogram & Lag

The cross-correlogram measures correlation as a function of time
lag — cell A leading cell B by τ peaks the correlation at lag τ.

```python
def cross_correlogram(trace1, trace2, frame_rate, max_lag_s=2.0):
    """Returns lags (s) and Pearson cross-correlation values."""
    max_lag = int(max_lag_s * frame_rate)
    t1 = (trace1 - np.mean(trace1)) / (np.std(trace1) + 1e-10)
    t2 = (trace2 - np.mean(trace2)) / (np.std(trace2) + 1e-10)
    xcorr = np.correlate(t1, t2, mode='full') / len(t1)
    center = len(t1) - 1
    lags = np.arange(-max_lag, max_lag + 1)
    segment = xcorr[center + lags[0]:center + lags[-1] + 1]
    return lags / frame_rate, segment

def peak_lag(trace1, trace2, frame_rate, max_lag_s=2.0):
    """Lag (s) at which xcorr peaks. Positive = trace2 leads."""
    lags_s, xcorr = cross_correlogram(trace1, trace2, frame_rate, max_lag_s)
    peak_idx = np.argmax(xcorr)
    return lags_s[peak_idx], xcorr[peak_idx]
```

For **all pairs** in a population, compute the lag matrix. Hierarchies
emerge as consistent positive / negative lag patterns.

### §11.4 Coherence (Frequency-Resolved Coupling)

Magnitude-squared coherence between two traces at each frequency:

```python
from scipy.signal import coherence
f, Cxy = coherence(trace1, trace2, fs=frame_rate, nperseg=int(20*frame_rate))
# Cxy[k] in [0, 1]: coupling strength at frequency f[k]
```

Useful for distinguishing cells coupled in slow oscillations versus
fast bursts, even when total Pearson correlation is similar.

### §11.5 Kuramoto Order Parameter

For oscillatory populations, the Kuramoto order parameter R ∈ [0, 1]
quantifies phase synchrony, independent of amplitude. R = 1 means all
cells phase-locked; R = 0 means uniformly distributed phases.

```python
from scipy.signal import hilbert

def kuramoto_order(traces):
    """traces: (n_cells, n_frames). Returns R(t), shape (n_frames,)."""
    # Hilbert transform per cell to get instantaneous phase
    analytic = hilbert(traces - np.mean(traces, axis=1, keepdims=True), axis=1)
    phases = np.angle(analytic)              # (n_cells, n_frames)
    R = np.abs(np.mean(np.exp(1j * phases), axis=0))  # (n_frames,)
    return R, phases
```

For meaningful Kuramoto, the traces should be band-pass-filtered to
the oscillatory frequency of interest first (otherwise the Hilbert
phase is dominated by the highest-amplitude frequency, which may not
be the oscillation you care about).

```python
from scipy.signal import butter, filtfilt
def bandpass(trace, lo_hz, hi_hz, fr, order=4):
    nyq = 0.5 * fr
    b, a = butter(order, [lo_hz/nyq, hi_hz/nyq], btype='band')
    return filtfilt(b, a, trace)
```

### §11.6 Functional Connectivity Graph

Treat the cell-pair correlation matrix as an adjacency matrix,
threshold on significance, and extract graph metrics.

```python
import numpy as np
import networkx as nx

def functional_graph(dff_all, p_thresh=0.01, n_shuffle=200):
    """Build a graph: edge if correlation exceeds shuffle null."""
    from scipy.stats import pearsonr
    n_cells = dff_all.shape[0]
    G = nx.Graph()
    G.add_nodes_from(range(n_cells))

    # Empirical correlation
    corr = np.corrcoef(dff_all)

    # Shuffle null: shift each trace by random amount, recompute
    null_corrs = []
    for _ in range(n_shuffle):
        shifted = np.array([np.roll(t, np.random.randint(t.size)) for t in dff_all])
        null_corrs.append(np.corrcoef(shifted))
    null_corrs = np.array(null_corrs)
    null_mean = null_corrs.mean(axis=0)
    null_sd = null_corrs.std(axis=0)
    z = (corr - null_mean) / (null_sd + 1e-10)

    from scipy.stats import norm
    p = 2 * (1 - norm.cdf(np.abs(z)))
    for i in range(n_cells):
        for j in range(i+1, n_cells):
            if p[i, j] < p_thresh and corr[i, j] > 0:
                G.add_edge(i, j, weight=corr[i, j])
    return G

# Network metrics
G = functional_graph(dff_all)
metrics = {
    'n_edges': G.number_of_edges(),
    'mean_degree': np.mean([d for _, d in G.degree()]),
    'clustering': nx.average_clustering(G),
    'modularity': nx.community.modularity(G, nx.community.greedy_modularity_communities(G)),
    'mean_path_length': nx.average_shortest_path_length(G) if nx.is_connected(G) else None,
}
```

### §11.7 Population Coupling

Population coupling = correlation of each cell's activity with the
population mean (excluding itself).

```python
def population_coupling(dff_all):
    """Per-cell coupling to population mean (leave-one-out)."""
    n = dff_all.shape[0]
    coupling = np.zeros(n)
    for i in range(n):
        rest = np.delete(dff_all, i, axis=0)
        pop_mean = np.mean(rest, axis=0)
        coupling[i] = np.corrcoef(dff_all[i], pop_mean)[0, 1]
    return coupling
```

High population-coupled cells track the global state; low-coupled
cells fire on their own schedule.

### §11.8 Cell Clustering by Activity

```python
from scipy.cluster.hierarchy import linkage, fcluster
from scipy.spatial.distance import pdist

distances = pdist(dff_all, metric='correlation')   # 1 − r
Z = linkage(distances, method='ward')
labels = fcluster(Z, n_clusters, criterion='maxclust')

# Reorder correlation matrix by cluster for visualisation
order = np.argsort(labels)
corr_sorted = np.corrcoef(dff_all[order])
```

For unknown number of clusters, use silhouette score across a range
or Louvain community detection on the functional graph (§11.6).

### §11.9 Population Vector & Decoding (briefly)

Per-frame, each cell contributes one component of an n-dimensional
"population vector". Decoding asks: given a population vector, what
state was the system in?

```python
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import cross_val_score

# X: (n_frames, n_cells), y: behavioural label per frame
clf = LogisticRegression(max_iter=2000, multi_class='multinomial')
acc = cross_val_score(clf, X, y, cv=5, scoring='accuracy')
print(f"Decoding accuracy: {acc.mean():.3f} ± {acc.std():.3f}")
```

Decoding is the standard test for "does the population carry the
information about X?" — full treatment beyond the scope of this
reference.

---

## §11a Visualisation

### §11a.1 Heatmap (cells × time)

```python
import matplotlib.pyplot as plt
import numpy as np

fig, ax = plt.subplots(figsize=(10, 6))
# Sort cells by time of peak (or by cluster from §11.8) for visual structure
peak_times = np.argmax(dff_all, axis=1)
order = np.argsort(peak_times)
im = ax.imshow(dff_all[order], aspect='auto', cmap='viridis',
               vmin=0, vmax=np.percentile(dff_all, 99),
               extent=[0, dff_all.shape[1]/frame_rate, len(order), 0])
ax.set_xlabel('Time (s)'); ax.set_ylabel('Cell # (sorted)')
plt.colorbar(im, ax=ax, label='dF/F')
plt.tight_layout()
plt.savefig('heatmap.png', dpi=200)
```

### §11a.2 Event Raster

```python
fig, ax = plt.subplots(figsize=(10, 6))
for cell_idx, events in enumerate(ev_list):
    onset_times = [ev['onset_time_s'] for ev in events]
    ax.eventplot(onset_times, lineoffsets=cell_idx, linelengths=0.8,
                 colors='black', linewidths=0.5)
ax.set_xlabel('Time (s)'); ax.set_ylabel('Cell #')
ax.set_xlim(0, total_duration_s); ax.set_ylim(-1, n_cells)
plt.savefig('raster.png', dpi=200)
```

### §11a.3 Synchronisation Matrix

```python
from scipy.cluster.hierarchy import linkage, leaves_list

corr = np.corrcoef(dff_all)
Z = linkage(1 - corr[np.triu_indices_from(corr, k=1)], method='ward')
order = leaves_list(Z)

fig, ax = plt.subplots(figsize=(7, 6))
im = ax.imshow(corr[np.ix_(order, order)], cmap='RdBu_r', vmin=-1, vmax=1)
ax.set_xlabel('Cell #'); ax.set_ylabel('Cell #')
plt.colorbar(im, ax=ax, label='Pearson r')
plt.savefig('sync_matrix.png', dpi=200)
```

### §11a.4 Color-Coded Mean Projection

Map per-pixel temporal feature (peak time, amplitude, frequency) into
a colour overlay on the structural mean projection.

```javascript
// In ImageJ: create a temporal-colour-code RGB
selectWindow("registered");
run("Temporal-Color Code", "lut=mpl-inferno start=1 end=" + nSlices);
```

```python
# Or in Python — color code each pixel by time-of-max
peak_t = np.argmax(stack, axis=0).astype(float) / frame_rate
mean_proj = stack.mean(axis=0)
```

### §11a.5 Mean ± SD Trace Plot

```python
fig, ax = plt.subplots(figsize=(10, 3))
t = np.arange(dff_all.shape[1]) / frame_rate
mean_trace = np.mean(dff_all, axis=0)
sd_trace = np.std(dff_all, axis=0)
ax.fill_between(t, mean_trace - sd_trace, mean_trace + sd_trace, alpha=0.3)
ax.plot(t, mean_trace, 'k-')
ax.set_xlabel('Time (s)'); ax.set_ylabel('Mean dF/F ± SD')
plt.savefig('mean_trace.png', dpi=200)
```

---

## §12 ImageJ Plugins

| Plugin | ROI type | dF/F | Events | Batch | 3D | Best for |
|---|---|---|---|---|---|---|
| Multi Measure (built-in) | Manual | No | No | No | No | Quick trace extraction |
| Time Series Analyzer | Manual | No | No | No | No | Beginners |
| TACI | TrackMate | Yes | No | Yes | Yes | 3D calcium imaging |
| SynActJ | Auto (watershed) | Yes | Yes (R) | Yes | No | Synaptic activity |
| CaManager | Manual (line) | Yes | No | No | No | Dendritic kymographs |
| dFoF-imagej | Manual | Yes | No | No | No | Quick dF/F stack |
| FAST | Skeleton | Yes | Yes | No | No | Dendritic / axonal |
| MCA | Auto | Yes | Yes | Yes | No | Population analysis |

**TACI**: 3D calcium imaging (TrackMate for detection, dF/F, z-drift
tracking). Install from GitHub.
**SynActJ**: Synaptic-bouton analysis. Enable "Cellular Imaging"
update site.

For dense populations, in-vivo data, or anything requiring source
separation, prefer suite2p or CaImAn (§5.5) — none of the ImageJ
plugins above do source separation.

---

## §13 Agent Workflows

### §13.1 Single-Wavelength (GCaMP / Fluo-4) Complete Pipeline (ImageJ)

```bash
# Step 1: Open
python ij.py macro 'run("Bio-Formats Importer", "open=/path/to/recording.tif color_mode=Default view=Hyperstack stack_order=XYCZT");'
python ij.py info
python ij.py capture step1_opened

# Step 2: Check metadata for frame rate and calibration
python ij.py metadata

# Step 3: Motion correct
python ij.py macro 'run("32-bit"); run("StackReg", "transformation=[Rigid Body]"); rename("registered");'
python ij.py macro 'selectWindow("registered"); run("Z Project...", "projection=[Standard Deviation]"); rename("temporal_SD");'
python ij.py capture step3_temporal_SD

# Step 4: Detect cells
python ij.py macro '
  selectWindow("registered");
  run("Z Project...", "projection=[Average Intensity]"); rename("mean_projection");
  run("Duplicate...", "title=for_detection");
  run("Gaussian Blur...", "sigma=3");
  setAutoThreshold("Triangle dark"); run("Convert to Mask"); run("Watershed");
  run("Analyze Particles...", "size=30-5000 circularity=0.3-1.0 add");
  close("for_detection");
  print("Detected " + roiManager("count") + " cells");
'

# Step 5: Add background ROI
python ij.py macro '
  selectWindow("mean_projection");
  makeRectangle(5, 5, 20, 20);
  roiManager("Add");
  roiManager("Select", roiManager("count") - 1);
  roiManager("Rename", "Background");
  roiManager("Show All");
'
python ij.py capture step5_rois

# Step 6: Extract traces
python ij.py macro '
  run("Set Measurements...", "mean redirect=None decimal=6");
  selectWindow("registered");
  roiManager("Deselect"); roiManager("Multi Measure");
'
python ij.py results

# Step 7: Save ROIs
python ij.py macro 'roiManager("Deselect"); roiManager("Save", "/path/to/analysis/calcium_ROIs.zip");'
```

### §13.2 Fura-2 Ratiometric Pipeline

```bash
# Open -> Split channels -> StackReg on F380 (more stable) -> Background subtract -> Ratio
python ij.py macro '
  run("Bio-Formats Importer", "open=/path/to/fura2.tif color_mode=Default view=Hyperstack stack_order=XYCZT");
  title = getTitle();
  getDimensions(w, h, channels, slices, frames);
  if (channels == 2) {
    run("Split Channels");
    selectWindow("C1-" + title); rename("F340");
    selectWindow("C2-" + title); rename("F380");
  } else {
    run("Deinterleave", "how=2");
    selectWindow(title + " #1"); rename("F340");
    selectWindow(title + " #2"); rename("F380");
  }
  // Motion correct on 380 channel
  selectWindow("F380"); run("32-bit"); run("StackReg", "transformation=[Rigid Body]");
  selectWindow("F340"); run("32-bit"); run("StackReg", "transformation=[Rigid Body]");
'

# Measure background, subtract, compute ratio
python ij.py macro '
  selectWindow("F380"); setSlice(1);
  makeRectangle(5, 5, 20, 20); getRawStatistics(n, bg380);
  selectWindow("F340"); setSlice(1);
  makeRectangle(5, 5, 20, 20); getRawStatistics(n, bg340);
  selectWindow("F340"); run("Subtract...", "value=" + bg340 + " stack");
  selectWindow("F380"); run("Subtract...", "value=" + bg380 + " stack");
  imageCalculator("Divide create 32-bit stack", "F340", "F380");
  rename("Ratio_340_380"); setMinAndMax(0.3, 3.0); run("Fire");
'
python ij.py capture ratio_image

# Draw ROIs on F380 mean projection, then Multi Measure on ratio stack
```

### §13.3 Batch Processing (ImageJ macro loop)

```bash
python ij.py macro '
  input_dir = "/path/to/recordings/";
  output_dir = "/path/to/analysis/";
  list = getFileList(input_dir);
  for (f = 0; f < list.length; f++) {
    if (!endsWith(list[f], ".tif")) continue;
    open(input_dir + list[f]);
    title = getTitle();
    base = replace(title, ".tif", "");
    run("32-bit"); run("StackReg", "transformation=[Rigid Body]");
    run("Z Project...", "projection=[Average Intensity]");
    run("Duplicate...", "title=temp");
    run("Gaussian Blur...", "sigma=3");
    setAutoThreshold("Triangle dark"); run("Convert to Mask");
    run("Watershed");
    run("Analyze Particles...", "size=30-5000 circularity=0.3-1.0 add");
    close("temp");
    run("Set Measurements...", "mean redirect=None decimal=6");
    selectWindow(title); roiManager("Deselect"); roiManager("Multi Measure");
    selectWindow("Results"); saveAs("Results", output_dir + base + "_traces.csv");
    roiManager("Deselect"); roiManager("Save", output_dir + base + "_ROIs.zip");
    roiManager("Reset"); run("Clear Results"); run("Close All");
  }
  print("Batch complete");
'
```

### §13.4 End-to-End Python Pipeline (tifffile → suite2p → OASIS → events → correlations)

For dense / in-vivo data, this is the "default modern" pipeline.

```python
#!/usr/bin/env python3
"""End-to-end calcium imaging pipeline.
   tifffile -> suite2p -> dF/F -> OASIS spikes -> events -> correlations.
"""
import os
import numpy as np
import tifffile
from suite2p.run_s2p import run_s2p, default_ops

# ---- 0. Inputs ----
TIFF = '/path/to/recording.tif'
OUTPUT = '/path/to/analysis/'
FR = 30.0                    # frame rate (Hz)
TAU = 0.4                    # GCaMP6f decay (s) — set to your indicator
INDICATOR_DECAY = 0.4
os.makedirs(OUTPUT, exist_ok=True)

# ---- 1. Inspect ----
data = tifffile.memmap(TIFF)  # (T, H, W) — does not load all into RAM
print(f"Shape: {data.shape}, dtype: {data.dtype}")

# ---- 2. Suite2p (registration + ROI detection + neuropil) ----
ops = default_ops()
ops.update({'fs': FR, 'tau': TAU, 'do_registration': 1, 'nonrigid': True,
            'reg_tif': True, 'spikedetect': True, 'allow_overlap': False,
            'neucoeff': 0.7, 'baseline': 'maximin', 'win_baseline': 60.0})
db = {'data_path': [os.path.dirname(TIFF)], 'tiff_list': [os.path.basename(TIFF)]}
output_ops = run_s2p(ops=ops, db=db)

# ---- 3. Load curated outputs ----
out_dir = os.path.join(os.path.dirname(TIFF), 'suite2p', 'plane0')
F = np.load(os.path.join(out_dir, 'F.npy'))           # (n_rois, n_frames)
Fneu = np.load(os.path.join(out_dir, 'Fneu.npy'))
spks = np.load(os.path.join(out_dir, 'spks.npy'))     # OASIS spike trace
iscell = np.load(os.path.join(out_dir, 'iscell.npy'))
keep = iscell[:, 0].astype(bool)
F_corr = F[keep] - 0.7 * Fneu[keep]
spks_corr = spks[keep]
n_cells, n_frames = F_corr.shape
print(f"After curation: {n_cells} cells, {n_frames} frames")

# ---- 4. dF/F ----
from scipy.ndimage import percentile_filter
win = int(30 * FR); win += 1 - win % 2
f0 = np.array([percentile_filter(t, 8, size=win) for t in F_corr])
f0 = np.maximum(f0, 1e-6)
dff = (F_corr - f0) / f0

# ---- 5. Events from OASIS spike trace ----
def spike_events(s, fr, sd_factor=2.5):
    """Threshold OASIS s-trace into binary events."""
    bl = s[s < np.percentile(s, 90)]
    thresh = (np.mean(bl) + sd_factor * np.std(bl))
    above = s > thresh
    # Find contiguous runs
    runs = np.diff(above.astype(int))
    onsets = np.where(runs == 1)[0] + 1
    return onsets / fr  # event times in seconds

ev_list = [spike_events(spks_corr[i], FR) for i in range(n_cells)]
total_events = sum(len(e) for e in ev_list)
print(f"Total events: {total_events}")

# ---- 6. Correlations ----
corr = np.corrcoef(dff)
mean_pairwise = np.mean(corr[np.triu_indices_from(corr, k=1)])
sync = np.var(np.mean(dff, 0)) / (np.mean(np.var(dff, 1)) + 1e-10)
sync_norm = float(np.clip((sync - 1/n_cells) / (1 - 1/n_cells), 0, 1))
print(f"Mean pairwise r: {mean_pairwise:.3f}, synchrony: {sync_norm:.3f}")

# ---- 7. Save outputs ----
np.save(os.path.join(OUTPUT, 'dff.npy'), dff)
np.save(os.path.join(OUTPUT, 'spks.npy'), spks_corr)
np.save(os.path.join(OUTPUT, 'corr.npy'), corr)
import json
json.dump({'n_cells': int(n_cells), 'n_frames': int(n_frames), 'frame_rate': FR,
           'tau_s': TAU, 'total_events': int(total_events),
           'mean_pairwise_r': float(mean_pairwise),
           'synchrony': sync_norm},
          open(os.path.join(OUTPUT, 'metadata.json'), 'w'), indent=2)
```

### §13.5 End-to-End ImageJ Pipeline via TCP (when source separation is overkill)

For sparse populations, in-vitro coverslips, slow imaging — the
ImageJ macro pipeline is faster to set up and more transparent.

```bash
# Full pipeline: open -> motion correct -> ROI -> traces -> CSV -> Python analysis
python ij.py macro '
  open("/path/to/recording.tif");
  run("32-bit");
  run("StackReg", "transformation=[Rigid Body]");
  rename("registered");
  run("Z Project...", "projection=[Average Intensity]"); rename("MEAN");
  run("Duplicate...", "title=detect");
  run("Gaussian Blur...", "sigma=3");
  setAutoThreshold("Triangle dark"); run("Convert to Mask"); run("Watershed");
  run("Analyze Particles...", "size=30-2000 circularity=0.3-1.0 add");
  close("detect");
  // Background ROI
  selectWindow("MEAN");
  makeRectangle(5, 5, 30, 30);
  roiManager("Add");
  roiManager("Select", roiManager("count")-1);
  roiManager("Rename", "Background");
  // Multi Measure
  selectWindow("registered");
  run("Set Measurements...", "mean redirect=None decimal=6");
  roiManager("Deselect"); roiManager("Multi Measure");
  selectWindow("Results"); saveAs("Results", "/path/to/traces.csv");
'

# Hand to Python
python calcium_analysis.py /path/to/traces.csv --frame-rate 10 \
    --output /path/to/results/ --has-background
```

---

## §14 Common Problems & Solutions

| Problem | Symptom | Solution |
|---|---|---|
| Motion artifacts | Intensity fluctuations at cell edges | StackReg Rigid Body; in-vivo: NoRMCorre piecewise-rigid |
| Photobleaching | Downward drift in raw traces | Bleach Correction (Exponential Fit) or sliding percentile baseline |
| Low SNR | Can't distinguish events from noise | Temporal bin (`run("Grouped Z Project...", "projection=[Average Intensity] group=4");`), spatial smooth (sigma=1), larger ROIs, or switch to a brighter indicator (jGCaMP8s, Cal-520) |
| Indicator saturation | Flat-top traces, max pixel values | Check histogram; reduce exposure / gain or use lower-affinity indicator (GCaMP6f instead of 6s; jRCaMP1b instead of 1a) |
| Focus drift | Gradual blurring, decreasing SD | Hardware autofocus; discard bad frames |
| Out-of-focus contamination (1P) | All cells correlated, smooth global drift | Switch to 2P, or use CNMF-E with ring background, or subtract a smoothed background image |
| Bleed-through (multi-channel) | Red trace mirrors green trace | Cross-link: `references/colocalization-reference.md` for correction; spectral unmixing or narrower filters |
| Overlapping cells | Mixed signals, double peaks | Smaller ROIs, StarDist/Cellpose, or source separation (CaImAn / suite2p) |
| Neuropil contamination | All cells correlate artificially | Neuropil subtraction: F_corr = F_cell − r·F_neuropil (r ≈ 0.7 default) |
| Stimulation artifact | Flash in all ROIs + background | Subtract background trace; interpolate over stim frames |
| Too many false events | Events in background ROI | Increase SD threshold to 4–5 |
| Missing real events | Known responses not detected | Decrease SD threshold to 2; check dF/F baseline; check indicator saturation |
| StackReg fails | Error or garbled output | Try Translation only, or use NoRMCorre / moco |
| Suite2p missed cells | Visible cells absent from ROI list | Lower `threshold_scaling`; check `spatial_scale`; manually add via GUI |
| OASIS produces too many spikes | Tonic noise scored as spikes | Increase L1 penalty; use higher SNR threshold on s-trace |
| Selection bias | Reporting only "active" cells | Pre-register ROI selection criterion before analysis; report n_total / n_active |
| Pseudoreplication | Inflated p-values | Use mixed-effects model; cell as random effect within preparation. See §21 |

### §14.1 Detecting When Motion Correction Failed

After motion correction, three quick diagnostic projections:

```bash
python ij.py macro '
  selectWindow("registered");
  run("Z Project...", "projection=[Standard Deviation]"); rename("temporal_SD");
  run("Z Project...", "projection=[Average Intensity]"); rename("MEAN");
'
python ij.py capture mc_check
```

**Failure signatures:**
- Cell outlines have comet-tail halos in temporal SD → residual lateral drift.
- Mean projection is washed-out / soft despite sharp single frames → pixel-level shifts adding up over time.
- Streaks across the FOV oriented along a single axis → uncorrected breathing/heartbeat.
- Diffuse "ghost cells" near real cells → cells moving in/out of focus (z-drift, not lateral).

For z-drift, lateral motion correction won't help. Use **Correct 3D
Drift** if z information is in the data, or accept the loss and
analyse only the in-focus subset.

### §14.2 Catching Motion-Induced Artefacts as Events

A 1-pixel lateral shift at a cell-edge gradient produces a transient
indistinguishable from a real event. Two checks:

1. **Background ROI** placed in cell-free area should *not* show
   correlated transients during cell events.
2. **Phase-correlation residual** between consecutive frames after
   motion correction should be < 0.2 px (see §4.7 `residual_motion`).

If either fails, redo motion correction with a stricter algorithm
(NoRMCorre piecewise rigid, or shrink suite2p block size).

### §14.3 Neuropil Correction Recipe

```python
def neuropil_subtraction(f_cell, f_neuropil, r=0.7):
    """r=0.7 (Suite2p default). Range: 0.5 sparse, 0.8-0.9 dense labeling."""
    return np.maximum(f_cell - r * f_neuropil, 0)
```

For ImageJ-side workflows, draw an annulus around each cell as the
neuropil ROI:

```javascript
// For each cell ROI: create an annulus 2 px outside cell boundary,
// 15 px wide, excluding any other cells.
roiManager("Select", cellIdx);
run("Enlarge...", "enlarge=2");      // outer cell + buffer
run("Make Inverse");                  // not implemented this way — see ROI Manager API
```

In practice, for ImageJ, neuropil is often approximated by a *single*
large background ROI (cell-free region) and applied uniformly. For
per-cell neuropil annuli, use suite2p (which does it automatically).

---

## §15 Exporting Data

### §15.1 From ImageJ

```javascript
// Save traces CSV
selectWindow("Results"); saveAs("Results", "/path/to/traces.csv");

// Save registered stack for Python pipelines
selectWindow("registered"); saveAs("Tiff", "/path/to/registered.tif");
```

### §15.2 Load in Python

```python
import pandas as pd
import numpy as np

# From ImageJ Multi Measure CSV
df = pd.read_csv("traces.csv")
mean_cols = [c for c in df.columns if c.startswith('Mean')]
traces = df[mean_cols].values.T  # shape: (n_rois, n_frames)

# From TIFF stack — tifffile is fast, supports memory-mapping
import tifffile
data = tifffile.imread("registered.tif")           # loads into RAM: (T, H, W)
mm   = tifffile.memmap("registered.tif", mode='r')  # memory-map: lazy load
```

### §15.3 For CaImAn / Suite2p

Both expect TIFF stacks (or HDF5 / multi-page TIFF). Export
registered stack from ImageJ as TIFF:
- **CaImAn**: `cm.load("registered.tif")` or use motion correction +
  CNMF (§5.5.2).
- **Suite2p**: place TIFF in directory, run suite2p (§5.5.1). Output
  in `suite2p/plane0/`: `F.npy`, `Fneu.npy`, `spks.npy`, `iscell.npy`,
  `stat.npy`, `ops.npy`.

### §15.4 NWB (Neurodata Without Borders)

Standard format for sharing calcium imaging + behavioural data.
suite2p has `save_NWB=True`; CaImAn can export via `pynwb`.

---

## §16 Decision Trees

### §16.1 Analysis Pipeline Selection

```
Single-wavelength (GCaMP, Fluo-4)?
  In vitro / sparse / quick? -> ImageJ: Open -> StackReg -> ROI -> Multi Measure -> Python dF/F -> Events
  In vivo two-photon? -> Suite2p (§5.5.1) — preferred for dense populations, includes OASIS
  In vivo one-photon / miniscope? -> CaImAn CNMF-E (§5.5.2)
  Long-term (hours)? -> Add Bleach Correction before ROI step
  Crosstalk-heavy / out-of-focus dominant? -> EXTRACT (§5.5.3)

Ratiometric (Fura-2)?
  -> Split channels -> StackReg -> Background subtract -> Ratio -> ROI -> Traces -> Grynkiewicz

3D volumetric? -> Light-sheet acquisition + suite2p (multi-plane) or TACI plugin
Synaptic boutons? -> SynActJ
```

### §16.2 Baseline Method

```
Defined quiet baseline period? -> Fixed window (mean of first N frames)
Spontaneous activity?
  Significant bleaching? -> Exponential fit, or bleach correct then sliding percentile
  Cell active <50%? -> Median or mode
  Cell active 50-80%? -> Sliding 8th percentile (20-60s window)
  Cell active >80%? -> Sliding 5th percentile (60-120s window)
Suite2p-style robust? -> 'maximin' baseline (60s window, gauss σ 10s, then min then max)
```

### §16.3 Event-Detection / Spike-Inference Method

```
High SNR per spike (≥5 SD)? -> Threshold: 3 SD above baseline (§9.1)
Calibrated indicator parameters known? -> MLspike (§9.3) for spike counts
Population analysis, relative spikes OK? -> OASIS (§9.2) — suite2p built-in
Heterogeneous data, no calibration? -> CASCADE (§9.4)
Real-time / online? -> OASIS (online mode)
Moderate SNR, stereotyped waveform? -> Template matching (cross-correlation)
Moderate SNR, variable waveform? -> Threshold 2.5 SD + min duration filter
Low SNR, can bin? -> Bin 2-4 frames, then 3 SD threshold
Low SNR, full resolution required? -> CASCADE or DeepCINAC (deep-learning)
```

### §16.4 Motion Correction

```
No visible motion? -> Skip (but check temporal SD to confirm, §4.7)
Small translation (<5 px)? -> StackReg Translation
Translation + rotation? -> StackReg Rigid Body
Large rigid motion (>10 px)? -> moco (FFT-based) or NoRMCorre rigid
In-vivo head-fixed (breathing/heartbeat)? -> NoRMCorre piecewise rigid OR suite2p built-in
In-vivo freely-moving / miniscope? -> NoRMCorre piecewise rigid (gSig_filt for 1P)
Volumetric (z-drift)? -> Correct 3D Drift, Fast4DReg
Active fluorescent reporters and SIFT seems tempting? -> NO. §4.6.
```

---

## §17 Parameter Quick-Reference

### §17.1 dF/F Parameters

| Parameter | Starting point | How to choose |
|---|---|---|
| Baseline frames (fixed) | 10–50 | Must cover a quiet period before stimulation |
| Sliding window | 20–60 s | Longer than several inter-event intervals |
| Percentile | 8th | Lower (5th) for very active cells; higher (20th) for moderate |
| Background ROI | 20×20 px | Place in cell-free area; verify no signal |
| Neuropil r coefficient | 0.7 | 0.5–0.6 sparse, 0.8–0.9 dense labeling |

### §17.2 Event Detection

| Parameter | Starting point | How to choose |
|---|---|---|
| SD threshold | 3 | Decrease if missing events; increase if false positives in background ROI |
| Min duration | 0.1–0.5 s | Match to indicator kinetics (table below) |
| Min interval | 0.3–1.0 s | Match to indicator decay time |
| Min amplitude | 0.05–0.5 dF/F | Depends on indicator and expected signal |

### §17.3 Indicator-Specific Detection Parameters

| Indicator | Min duration (s) | Min IEI (s) | Expected decay τ (s) | Suggested fr (Hz) |
|---|---|---|---|---|
| jGCaMP8f | 0.05 | 0.1 | 0.06 | ≥30 (ideally 60+) |
| jGCaMP8m | 0.07 | 0.15 | 0.10 | ≥20 |
| GCaMP6f | 0.1 | 0.3 | 0.14 | ≥15 |
| jGCaMP8s | 0.1 | 0.3 | 0.18 | ≥10 |
| jGCaMP7f | 0.15 | 0.3 | 0.27 | ≥10 |
| jGCaMP7s | 0.2 | 0.5 | 0.30 | ≥10 |
| GCaMP6m | 0.2 | 0.5 | 0.25 | ≥10 |
| GCaMP6s | 0.3 | 1.0 | 0.55 | ≥5 |
| jRGECO1a | 0.15 | 0.3 | 0.14 | ≥15 |
| jRCaMP1b | 0.2 | 0.5 | 0.16 | ≥10 |
| Fluo-4 | 0.1 | 0.3 | ~0.3 | ≥10 |
| Cal-520 | 0.1 | 0.3 | ~0.2 | ≥10 |
| Fura-2 | 0.2 | 0.5 | ~0.3 | ≥5 |

### §17.4 Motion Correction

| Parameter | StackReg | moco | NoRMCorre (rigid) | NoRMCorre (PW) | Suite2p |
|---|---|---|---|---|---|
| Max shift (px) | Auto | 10–30 | (6, 6) for 512² | (10, 10) | maxregshift = 0.1 (frac.) |
| Transformation | Rigid Body | Translation | Rigid translation | Piecewise rigid | Block-wise (128 px) |
| Patch / strides | — | — | — | (48, 48) | block_size = 128 |
| Overlaps | — | — | — | (24, 24) | — |
| 1P background filter | — | — | — | gSig_filt = (7, 7) | sparse_mode=False |
| Speed (512², 1k frames) | ~30 s | ~10 s | ~30 s | ~60 s | ~45 s |

### §17.5 Acquisition by Application

| Application | Frame rate | Indicator | Modality | Duration |
|---|---|---|---|---|
| Neuronal population (widefield) | 10–30 Hz | GCaMP6m/f, jGCaMP8 | 1P widefield | 5–30 min |
| Neuronal population (2P) | 15–30 Hz | GCaMP6/7/8 | 2P scanning | 10–60 min |
| Dendritic spines (2P) | 30–100 Hz | jGCaMP8f, jGCaMP7b | 2P resonant | 5–20 min |
| Astrocyte / glial waves | 1–5 Hz | GCaMP6s, Rhod-2, GCaMP6m | 1P widefield, 2P | 10–60 min |
| Cardiac myocyte (single AP) | 50–200 Hz | Fluo-4, Cal-520, R-CaMP | 1P widefield | 1–10 min |
| Smooth muscle / pacemaker | 5–30 Hz | Fluo-4, GCaMP | 1P widefield | 5–30 min |
| Ratiometric (Fura-2) | 0.5–5 Hz | Fura-2 | 1P widefield UV | 5–30 min |
| Brain volume (light-sheet) | 1–10 Hz/plane | GCaMP6/7 panneuronal | Light-sheet | 5–60 min |
| Freely-moving (miniscope) | 10–30 Hz | GCaMP6f/m, jGCaMP8 | 1P miniscope | 10–60 min |
| Slow oscillations (minute-scale) | 1/min – 1/5min | GCaMP6s | 1P widefield | hours |
| Organoid / spheroid waves | 1–10 Hz | GCaMP6m, Cal-520 | 1P widefield, light-sheet | 10–60 min |
| Pancreatic islet (β-cell) | 5–20 Hz | GCaMP6/7, Cal-520 | 1P widefield, 2P | 10–60 min |

---

## §18 Appendix: Complete Python Pipeline Script

Self-contained script for processing ImageJ Multi-Measure CSV output:

```bash
python calcium_analysis.py traces.csv --frame-rate 10 --output results/
```

```python
#!/usr/bin/env python3
"""calcium_analysis.py — Calcium imaging analysis pipeline.
Reads Multi-Measure CSV, produces dF/F traces, events, summary, plots."""

import argparse, json, os, warnings
import numpy as np
import pandas as pd
from scipy.ndimage import percentile_filter
from scipy.signal import find_peaks
warnings.filterwarnings('ignore')

def compute_dff(traces, fr, method='sliding_percentile',
                bl_frames=30, win_s=30.0, pct=8):
    if method == 'fixed_window':
        f0 = np.mean(traces[:, :bl_frames], axis=1, keepdims=True)
    elif method == 'sliding_percentile':
        win = int(win_s * fr); win += 1 - win % 2
        f0 = np.array([percentile_filter(t, pct, size=win) for t in traces])
    elif method == 'median':
        f0 = np.median(traces, axis=1, keepdims=True)
    return (traces - np.maximum(f0, 1e-6)) / np.maximum(f0, 1e-6)

def detect_events(dff, fr, sd_thr=3.0, min_dur=0.1, min_iei=0.5):
    bl = dff[dff < np.percentile(dff, 50)]
    if len(bl) < 10: bl = dff
    sd, mu = np.std(bl), np.mean(bl)
    thr = mu + sd_thr * sd
    peaks, _ = find_peaks(dff, height=thr, distance=int(min_iei*fr),
                          prominence=sd_thr*sd*0.5)
    events = []
    for p in peaks:
        on = p
        for j in range(p-1, -1, -1):
            if dff[j] < thr: on = j+1; break
        off = p
        for j in range(p+1, len(dff)):
            if dff[j] < thr: off = j; break
        else: off = len(dff)-1
        if off - on < int(min_dur*fr): continue
        events.append({
            'onset_frame': int(on), 'peak_frame': int(p), 'offset_frame': int(off),
            'onset_time_s': round(on/fr, 3), 'peak_time_s': round(p/fr, 3),
            'amplitude': round(float(dff[p]-mu), 4),
            'rise_time_s': round((p-on)/fr, 3),
            'decay_time_s': round((off-p)/fr, 3),
            'duration_s': round((off-on)/fr, 3),
            'auc': round(float(np.trapz(dff[on:off+1]-mu)/fr), 4)
        })
    return events

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('input'); ap.add_argument('--frame-rate', type=float, required=True)
    ap.add_argument('--output', default='calcium_results')
    ap.add_argument('--baseline-method', default='sliding_percentile',
                    choices=['fixed_window', 'sliding_percentile', 'median'])
    ap.add_argument('--sd-threshold', type=float, default=3.0)
    ap.add_argument('--window-s', type=float, default=30.0)
    ap.add_argument('--percentile', type=float, default=8.0)
    ap.add_argument('--has-background', action='store_true')
    args = ap.parse_args()
    os.makedirs(args.output, exist_ok=True)

    df = pd.read_csv(args.input)
    mcols = [c for c in df.columns if c.startswith('Mean')]
    if not mcols: mcols = [c for c in df.columns if df[c].dtype in ['float64','int64']]
    traces = df[mcols].values.T
    if args.has_background:
        traces = np.maximum(traces[:-1] - traces[-1:], 0)
    names = [f'Cell_{i+1}' for i in range(traces.shape[0])]
    nc, nf = traces.shape; dur = nf / args.frame_rate
    print(f"{nc} cells, {nf} frames, {dur:.1f}s at {args.frame_rate} Hz")

    dff = compute_dff(traces, args.frame_rate, args.baseline_method,
                      win_s=args.window_s, pct=args.percentile)
    ev_list = [detect_events(dff[i], args.frame_rate, args.sd_threshold) for i in range(nc)]
    tot = sum(len(e) for e in ev_list)
    print(f"Detected {tot} events across {nc} cells")

    # Save outputs
    pd.DataFrame(dff.T, columns=names).assign(
        time_s=np.arange(nf)/args.frame_rate).to_csv(f'{args.output}/dff_traces.csv', index=False)
    rows = [dict(ev, cell=names[i], cell_index=i) for i, evs in enumerate(ev_list) for ev in evs]
    pd.DataFrame(rows).to_csv(f'{args.output}/events.csv', index=False)
    summary = [{'cell': names[i], 'n_events': len(evs),
                'freq_per_min': len(evs)/dur*60,
                'mean_amplitude': np.mean([e['amplitude'] for e in evs]) if evs else 0,
                'mean_duration_s': np.mean([e['duration_s'] for e in evs]) if evs else 0
               } for i, evs in enumerate(ev_list)]
    pd.DataFrame(summary).to_csv(f'{args.output}/summary.csv', index=False)

    corr = np.corrcoef(dff)
    sync = np.var(np.mean(dff,0)) / (np.mean(np.var(dff,1))+1e-10)
    sync_n = np.clip((sync - 1/nc)/(1 - 1/nc), 0, 1)
    json.dump({'n_cells': nc, 'n_frames': nf, 'frame_rate_hz': args.frame_rate,
               'total_events': tot, 'mean_corr': round(float(np.mean(
                   corr[np.triu_indices_from(corr,k=1)])),4),
               'synchrony_index': round(float(sync_n),4),
               'parameters': vars(args)},
              open(f'{args.output}/metadata.json','w'), indent=2)
    print(f"Results saved to {args.output}/")

if __name__ == '__main__': main()
```

---

## §19 Appendix: ImageJ Macro Library

```javascript
// MACRO: Compute dF/F Stack (Fixed Window Baseline) [F5]
macro "Compute dF/F Stack [F5]" {
    baselineFrames = getNumber("Baseline frames:", 30);
    title = getTitle();
    run("Z Project...", "start=1 stop=" + baselineFrames + " projection=[Average Intensity]");
    rename("F0_temp"); run("32-bit");
    selectWindow(title); run("Duplicate...", "title=dFF duplicate"); run("32-bit");
    imageCalculator("Subtract stack", "dFF", "F0_temp");
    imageCalculator("Divide stack", "dFF", "F0_temp");
    close("F0_temp");
    selectWindow("dFF"); setMinAndMax(-0.1, 2.0); run("Fire");
}

// MACRO: Auto-Detect Cells and Extract Traces [F6]
macro "Auto Cell Detection [F6]" {
    minArea = getNumber("Min cell area (px):", 50);
    maxArea = getNumber("Max cell area (px):", 2000);
    sigma = getNumber("Gaussian sigma:", 3);
    title = getTitle();
    run("Z Project...", "projection=[Average Intensity]"); rename("mean_det");
    run("Duplicate...", "title=temp");
    run("Gaussian Blur...", "sigma=" + sigma);
    setAutoThreshold("Triangle dark"); run("Convert to Mask"); run("Watershed");
    run("Analyze Particles...", "size=" + minArea + "-" + maxArea + " circularity=0.3-1.0 add");
    close("temp");
    selectWindow(title);
    run("Set Measurements...", "mean redirect=None decimal=6");
    roiManager("Deselect"); roiManager("Multi Measure");
    print("Detected " + roiManager("count") + " cells. Traces in Results.");
}

// MACRO: Quick Bleach Check [F8]
macro "Bleach Check [F8]" {
    n = nSlices; means = newArray(n);
    for (i = 1; i <= n; i++) { setSlice(i); getRawStatistics(nPx, mean); means[i-1] = mean; }
    bleach_pct = (1 - means[n-1] / means[0]) * 100;
    xvals = Array.getSequence(n);
    Plot.create("Bleach Check", "Frame", "Mean Intensity");
    Plot.add("line", xvals, means); Plot.show();
    print("Bleaching: " + d2s(bleach_pct, 1) + "%");
    if (bleach_pct > 5) print("WARNING: >5% bleaching. Consider correction.");
}

// MACRO: Activity Map (CV = SD/Mean) [F9]
macro "Activity Map [F9]" {
    title = getTitle();
    run("Z Project...", "projection=[Average Intensity]"); rename("MEAN_" + title);
    selectWindow(title);
    run("Z Project...", "projection=[Standard Deviation]"); rename("SD_" + title);
    imageCalculator("Divide create 32-bit", "SD_" + title, "MEAN_" + title);
    rename("Activity_CV"); run("Fire");
}
```

---

## §20 Troubleshooting Quick Reference

| Problem | Symptom | Solution |
|---|---|---|
| No signal | Dark image | Check excitation, indicator loading, focus |
| All cells saturated | Flat-top traces, max pixel values | Reduce excitation/exposure/gain |
| Motion blur | Edge artifacts | StackReg before analysis |
| Bleaching | Downward drift | Bleach Correction or sliding baseline |
| Neuropil contamination | All cells correlate | Neuropil subtraction (r=0.7) |
| Too many false events | Events in background ROI | Increase SD threshold (4–5) |
| Missing events | Known responses not detected | Decrease SD threshold (2) |
| StackReg fails | Error or garbled | Try Translation only, NoRMCorre, or moco |
| Multi Measure slow | Hangs with many ROIs | Reduce ROI count or measure in batches |
| Suite2p empty `iscell` | `iscell.npy` all zeros | Lower `min_cnn_thr`, run GUI curation |
| OASIS spurious spikes | Spikes during quiet periods | Increase L1 penalty, smooth trace first |
| CNMF over-merges cells | Two cells reported as one | Decrease `merge_thr`, decrease `gSig` |
| CNMF-E misses dim cells | Dim cells absent from output | Lower `min_corr` and `min_pnr` |
| Kuramoto R always low | No phase coherence detected | Filter to expected oscillation band first |
| Heatmap looks noisy | No structure visible | Sort cells (by peak time, or §11.8 cluster), z-score traces |

---

## §21 Pitfalls & Best Practices

### §21.1 Out-of-Focus Contamination

In one-photon imaging, fluorescence from above and below the focal
plane is collected and adds a slowly-varying background to every
in-focus cell. This makes the population trace look correlated even
when cells are firing independently.

**Detection.** Population mean trace shows slow, broad fluctuations
that are *not* visible in shuffled-control trace. Pairwise
correlations are uniformly elevated (no clear "diagonal" structure
in correlation matrix).

**Fix.**
- Switch to confocal or 2P (eliminates the background optically).
- Apply CNMF-E for 1P data (ring background model handles it).
- Apply a high-pass filter or temporal moving baseline that's
  long enough to remove slow drifts but short enough to preserve
  events.

### §21.2 Spectral Bleed-Through (Multi-Channel)

When imaging two indicators (e.g. green GCaMP + red jRGECO), a
fraction of the green emission leaks into the red channel and vice
versa. Without correction, the red trace partially mirrors the
green trace.

Cross-link: `references/colocalization-reference.md` for spectral
unmixing recipes. Briefly:

```
F_corrected = M^(-1) · F_observed,
   M = [[1, β_red->green], [β_green->red, 1]]
```

where β values come from single-indicator control images.

### §21.3 Motion-Induced "Events" (False Positives)

A 0.5–1 px lateral shift at a cell's bright edge produces a transient
*indistinguishable* from a real Ca²⁺ event without ground truth.

**Detection.**
- Background ROI shows correlated transients.
- Events align across all cells regardless of activity (rigid global
  shift artefact).
- Phase-correlation residual after motion correction > 0.5 px (§4.7).

**Fix.** Re-do motion correction with stricter algorithm (NoRMCorre
piecewise rigid). For irreducible motion, exclude affected frames or
mask cell edges in the ROI.

### §21.4 Selection Bias (the Biggest Problem)

Reporting only "responsive" or "active" cells without specifying the
denominator inflates effect sizes and fails to replicate.

**Best practice.**
- Pre-register the inclusion criterion (e.g. "all cells detected by
  suite2p with `iscell` probability ≥ 0.5") before any analysis.
- Report `n_detected`, `n_passed_QC`, `n_responsive`, `n_analysed`.
- If reporting only responsive cells, also report the responder
  fraction.

### §21.5 Pseudoreplication

Treating each cell as an independent unit when cells are sampled from
the same animal / preparation / dish over-counts effective sample
size. A claim of n=200 cells from 4 mice is closer to n=4 for
animal-level claims.

Cross-link: `references/statistical-analysis-workflow-reference.md`
for full treatment. Brief recipe:

```python
# Mixed-effects model: cell as random effect, animal as random effect
import statsmodels.formula.api as smf
md = smf.mixedlm("response ~ condition", df,
                 groups=df["animal_id"],
                 re_formula="1",                       # random intercept per animal
                 vc_formula={"cell": "0 + C(cell_id)"}) # cell as nested random effect
result = md.fit()
print(result.summary())
```

The minimal honest claim: "we sampled X cells from N animals; the
animal-level mean ± SEM is ...". Cell-level pooled means inflate
significance by orders of magnitude.

### §21.6 Indicator Saturation

At high [Ca²⁺] >> Kd, dF/F flattens. The same number of "spikes"
above saturation produces the same trace, so the dynamic range of
the report is exhausted.

**Detection.** Histogram of F has a hard upper limit (camera bit
depth, or saturation plateau). Raw F traces have flat tops during
expected high-activity epochs.

**Fix.** Lower-affinity indicator (GCaMP6f instead of 6s; jRCaMP1b
instead of 1a; for ER, use Mag-Fluo-4 or low-Kd CEPIA), or report
event counts (which saturate gracefully) rather than amplitudes.

### §21.7 dF/F Direction Convention

Negative dF/F is real and meaningful (hyperpolarisation, indicator
off-rate, baseline noise below mean). Do **not** clip to ≥0 in any
quantitative pipeline.

### §21.8 Frame Rate Consistency Across Conditions

If you compare two conditions at different frame rates (e.g. one
session at 10 Hz, another at 30 Hz), event-detection thresholds and
indicator-decay-based parameters are no longer directly comparable.
Always:
- Decimate to a common rate before pooling, OR
- Convert event counts to rates (events/min) and decay times to
  seconds, never frames.

### §21.9 Bleaching Correction Order

Always: motion correct → background subtract → bleach correct → dF/F.
Bleach correction before motion correction couples motion and
intensity in pathological ways (correlated brightness changes between
the moving cell and the algorithm's reference).

### §21.10 Reporting Checklist

For any calcium imaging paper, report:
- Indicator name + variant (specific: GCaMP6f, not "GCaMP")
- Delivery method (transgenic / virus / AM-load)
- Modality (1P widefield, 2P, miniscope, etc.) and frame rate (Hz)
- Pixel size (µm/px) and field of view
- Motion correction algorithm and key parameters
- Source extraction method (suite2p / CaImAn / manual)
- dF/F method (F0 estimation, neuropil r if used)
- Event detection / spike inference method (algorithm + parameters)
- N_animals, N_preparations, N_cells, N_events
- Statistical model accounting for nesting (mixed-effects, etc.)

---
