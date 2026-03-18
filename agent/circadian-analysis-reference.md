# Circadian Analysis of Organotypic Slices — Complete Reference

Comprehensive guide to circadian rhythm analysis from bioluminescence/fluorescence
time-lapse imaging of organotypic brain slice cultures (SCN focus).

Sources: CHRONOS project codebase, Brancaccio Lab protocols, published guidelines
(Welsh 2004, Yoo 2004, Hughes 2010, Parsons 2019, Torrence & Compo 1998)

---

## Quick Start (CHRONOS Pipeline)

```bash
# In Fiji: Plugins > CHRONOS
# Module 1: Pre-processing (crop, align, motion correct, bleach correct)
# Module 2: ROI Definition (draw regions on projection)
# Module 3: Signal Extraction (mean intensity per ROI → dF/F traces)
# Module 4: Rhythm Analysis (FFT, cosinor, wavelet → period/phase/amplitude)
# Module 5: Visualization (kymographs, phase maps, polar plots, scalograms)
# Module 6: Export (Excel workbook with all results)
# Module 7: Cell Tracking (optional — TrackMate + StarDist)
```

Session data persists in `.circadian/` directory. Can resume from any module.

---

## Core Concepts

### What We're Measuring

Organotypic SCN slices express clock gene reporters (PER2::LUC bioluminescence,
CRY1-GFP fluorescence, etc.) that oscillate with ~24h period. We image these
over 3-7 days and extract:

- **Period** (τ): time for one complete oscillation (~24h in SCN)
- **Phase** (φ): timing of the peak relative to a reference (acrophase)
- **Amplitude** (A): strength of the oscillation (peak-to-trough / 2)
- **MESOR** (M): rhythm-adjusted mean (baseline level)
- **Damping** (λ): rate of amplitude decay over time
- **Synchrony** (R): how well different regions oscillate together

### The Cosinor Model

```
y(t) = M + A · cos(2πt/τ + φ)           # standard
y(t) = M + A · exp(-λt) · cos(2πt/τ + φ) # damped
```

Linearization: y = M + β·cos(2πt/τ) + γ·sin(2πt/τ), where A = √(β²+γ²), φ = atan2(-γ, β)

### Circadian Range

| Parameter | Value | Note |
|-----------|-------|------|
| Circadian period | 20–28h | Strict definition |
| Extended search | 18–32h | For initial screening |
| Minimum recording | 3 full cycles | Absolute minimum |
| Recommended | 5–7 days | Reliable estimates |
| Sampling rate | 10–60 min | Typical for imaging |
| Nyquist limit | 2× sampling interval | Can't resolve faster periods |

---

## Period Estimation Methods

### FFT (Fast Fourier Transform)

**When to use:** First-pass period estimation, clean regular oscillations.

```
Frequency resolution = 1 / (N × Δt)
```

For 5-day recording at 30min intervals: resolution = 1/240h ≈ 0.6h near 24h period.

**Strengths:** Fast, standard, easy to interpret.
**Weaknesses:** Assumes stationarity, needs ≥3 cycles, poor for time-varying periods.

### Lomb-Scargle Periodogram

**When to use:** Unevenly sampled data, gaps in recording.

Handles missing timepoints without interpolation. Includes significance testing
(p-value based on number of independent frequencies).

### Autocorrelation

**When to use:** Quick period check, intuitive interpretation.

Lag at first positive peak after R(0) = period estimate. Parabolic interpolation
for sub-sample accuracy.

### Wavelet Analysis (Morlet CWT)

**When to use:** Time-varying period/amplitude, detecting rhythm onset/loss, phase drift.

```
Morlet wavelet: ψ(η) = π^(-1/4) · exp(iω₀η) · exp(-η²/2), ω₀ = 6
Period ≈ 1.03 × scale (for ω₀ = 6)
```

Produces time-frequency **scalogram** showing power at each period across time.
Cone of influence (COI) marks unreliable edge regions.

**Interpretation:** Ridge of high power at ~24h = sustained rhythm. Ridge drift = period change. Break in ridge = rhythm loss.

### JTK_CYCLE (Hughes et al. 2010)

**When to use:** Non-parametric rhythmicity test, robust to outliers.

Rank-based (Kendall's τ) comparison to reference waveforms. Reports p-value,
period, phase, amplitude. Standard for transcriptomic screens. Apply BH-FDR
correction for multiple testing.

### Chi-Square Periodogram (Enright)

**When to use:** Non-sinusoidal waveforms (activity data), actogram analysis.

Folds data modulo trial period, tests variance of bin means. Robust to waveform
shape but susceptible to harmonics.

---

## Detrending Methods

| Method | How | Best for |
|--------|-----|----------|
| **None** | Skip | Data already detrended |
| **Linear** | Subtract line fit | Slow constant drift |
| **Quadratic** | Subtract parabola | Accelerating/decelerating trends |
| **Moving Average** | Subtract 24h running mean | General purpose (removes trend + preserves oscillation) |
| **Sinc Filter** | FFT bandpass, remove below cutoff | Clean frequency-domain separation |
| **EMD** | Empirical Mode Decomposition | Non-stationary bioluminescence decay (PER2::LUC) |
| **LOESS** | Local polynomial regression | General adaptive detrending |

**Critical rule:** Moving average window MUST equal one period (24h). Wrong window distorts the waveform.

---

## Phase Analysis

### Hilbert Transform

Converts real signal to analytic signal z(t) = x(t) + i·H[x(t)]:
```
Instantaneous phase: φ(t) = atan2(H[x(t)], x(t))
Instantaneous amplitude: A(t) = |z(t)|
```

**Requirement:** Bandpass filter to circadian range (20-28h) FIRST. Without filtering, instantaneous phase is meaningless.

### Phase Maps

For imaging data: compute phase at each pixel/ROI via Hilbert transform → map to color wheel (hue = phase). Shows spatial phase gradients across SCN (dorsal leads ventral by 1-4h typically).

### Circular Statistics

Phases are circular — standard statistics don't apply.

| Metric | Formula | Range | Meaning |
|--------|---------|-------|---------|
| Circular mean | φ̄ = atan2(Σsin(φⱼ), Σcos(φⱼ)) | 0–2π | Mean peak time |
| Mean resultant (R̄) | (1/N)·√(Σcos²+Σsin²) | 0–1 | Phase concentration |
| Circular variance | V = 1 - R̄ | 0–1 | Phase spread |
| Circular SD | s = √(-2·ln(R̄)) | radians | Angular dispersion |

---

## Synchrony Metrics

### Kuramoto Order Parameter

```
R(t) · exp(iΨ(t)) = (1/N) · Σ exp(iφⱼ(t))
```

| R value | Interpretation |
|---------|---------------|
| > 0.7 | Strong synchrony (intact SCN) |
| 0.3–0.7 | Partial synchrony |
| < 0.3 | Desynchronized |

### Pairwise Phase Coherence

```
C_ij = |⟨exp(i[φᵢ(t) - φⱼ(t)])⟩_t|
```

C = 1: constant phase relationship. C = 0: no consistent relationship.
Build coherence matrix → cluster analysis reveals coupled subpopulations.

---

## Amplitude Metrics

| Metric | Formula | Use |
|--------|---------|-----|
| Peak-to-trough | max - min (per cycle) | Simple, noise-sensitive |
| Cosinor amplitude | A from fit (= half peak-to-trough) | Robust, parametric |
| Relative amplitude | A / MESOR | Compare across experiments |
| Damping rate (λ) | -ln(Aₙ₊₁/Aₙ) / τ | Quantify rhythm decay |
| Damping half-life | ln(2) / λ | Time to lose half amplitude |
| Goodness of rhythm | R² of cosinor fit | % variance explained (>0.3 = rhythmic, >0.5 = strong) |

---

## Statistical Tests

### Rhythmicity Tests

| Test | Type | Waveform | Output | Use |
|------|------|----------|--------|-----|
| **Rayleigh test** | Circular | Any | p-value, R̄ | Are phases non-uniform? |
| **Cosinor F-test** | Parametric | Cosine | p-value, R² | Is rhythm better than flat line? |
| **JTK_CYCLE** | Non-parametric | Symmetric | p, period, phase, amplitude | Robust rhythmicity screen |
| **RAIN** | Non-parametric | Asymmetric | p-value | Detects asymmetric rhythms |

### Group Comparison Tests

| Test | Compares | Use |
|------|----------|-----|
| **CircaCompare** (Parsons 2019) | MESOR, amplitude, phase between groups | WT vs KO, dorsal vs ventral |
| **Watson-Williams** | Mean phases between groups | Circular ANOVA analogue |
| **Mardia-Watson-Wheeler** | Full phase distributions | Non-parametric circular comparison |

### Multiple Comparisons

- **Bonferroni:** p_adj = p × m. Conservative. Use for few comparisons.
- **Benjamini-Hochberg (FDR):** q < 0.05. Standard for genome-wide/many-ROI screens.

---

## CHRONOS Pipeline — Complete Module Reference

### Module 1: Pre-processing

| Step | Methods | Key parameters |
|------|---------|----------------|
| Crop | Interactive rectangle | Per-file, stored in `crop_regions.txt` |
| Alignment | User-drawn midline rotation | Per-file, stored in `alignment_angles.txt` |
| Frame binning | Mean or Sum projection | `binFactor` (default: 1 = off) |
| Motion correction | SIFT (primary), FFT cross-correlation (fallback) | Reference: first/mean/median frame |
| Background subtraction | Rolling Ball, Fixed ROI, Minimum Projection | `backgroundRadius` |
| Bleach correction | Mono-exponential, Bi-exponential, Sliding Percentile, Simple Ratio | Reporter-dependent defaults |
| Spatial filter | Gaussian, Median | `spatialFilterRadius` |
| Temporal filter | Moving Average, Savitzky-Golay | `temporalFilterWindow` |

**Reporter-specific defaults:**
| Reporter | Example | Bleach method |
|----------|---------|---------------|
| Bioluminescence | PER2::LUC | Sliding Percentile |
| Fluorescent | CRY1-GFP, Iba1-GFP | Bi-exponential |
| Calcium | GCaMP, jRCaMP | Bi-exponential |

### Module 2: ROI Definition

- Interactive drawing on mean/max projections
- Grid generation, dorsal/ventral splitting, auto-boundary detection
- ROIs stored as ImageJ ZIP files in `.circadian/ROIs/`

### Module 3: Signal Extraction

- Mean intensity per ROI per frame
- Baseline (F₀) methods: Sliding Percentile, First N frames, Whole-trace mean, Exponential fit
- Normalization: **dF/F** = (F - F₀) / F₀, optional **Z-score**
- Output: CSV trace files in `.circadian/traces/`

### Module 4: Rhythm Analysis

**Period estimation:** FFT, Autocorrelation, Lomb-Scargle, Wavelet CWT, JTK_CYCLE
**Detrending:** None, Linear, Quadratic, Cubic, Sinc Filter, EMD, LOESS
**Cosinor fitting:** Standard or Damped model (Levenberg-Marquardt optimizer)
**Group comparison:** CircaCompare (tests MESOR, amplitude, phase differences)
**Significance:** F-test (cosinor vs flat), Rayleigh test (phase coherence)

Config: `periodMinHours` (default 18), `periodMaxHours` (default 30), `significanceThreshold` (default 0.05)

### Module 5: Visualization

| Plot | Shows |
|------|-------|
| Time-series | dF/F traces with cosinor overlay |
| Kymograph | Space-time intensity (ROI × frame) |
| Phase map | Spatial distribution of acrophase |
| Period map | Spatial distribution of period |
| Amplitude map | Spatial distribution of amplitude |
| Raster plot | Binary/intensity heatmap per ROI |
| Polar plot | Phase on circle + Rayleigh coherence |
| Scalogram | Wavelet power time-frequency map |
| Dashboard | Consolidated multi-panel summary |

### Module 6: Export

- Consolidated CSVs (traces, rhythm results)
- `Experiment_Parameters.csv` (session metadata)
- Excel workbook (`CHRONOS_Summary.xlsx`) with all sheets

### Module 7: Cell Tracking (Optional)

- TrackMate + StarDist detection
- Motility metrics: speed, area, displacement, MSD
- Requires TrackMate 7.14.0 + CSBDeep update sites

---

## Configuration Reference (SessionConfig)

### Recording
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| reporterType | enum | Fluorescent | Bioluminescence, Fluorescent, Calcium |
| frameIntervalMin | double | 10.0 | Minutes between frames |

### Pre-processing
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| motionCorrectionEnabled | boolean | true | Enable motion correction |
| motionCorrectionMethod | string | SIFT | SIFT or CrossCorrelation |
| backgroundMethod | string | None | None, RollingBall, FixedROI, MinProjection |
| bleachMethod | string | (reporter-dependent) | None, MonoExponential, BiExponential, SlidingPercentile, SimpleRatio |
| spatialFilterType | string | None | None, Gaussian, Median |
| temporalFilterType | string | None | None, MovingAverage, SavitzkyGolay |

### Signal Extraction
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| f0Method | string | SlidingPercentile | Baseline calculation method |
| outputDeltaFF | boolean | true | Output dF/F traces |
| outputZscore | boolean | false | Output Z-score traces |

### Rhythm Analysis
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| periodMinHours | double | 18.0 | Min period to search |
| periodMaxHours | double | 30.0 | Max period to search |
| detrendingMethod | string | Linear | None, Linear, Quadratic, Cubic, SincFilter, EMD, LOESS |
| runFFT | boolean | true | Run FFT period estimation |
| runAutocorrelation | boolean | true | Run autocorrelation |
| runLombScargle | boolean | false | Run Lomb-Scargle |
| runWavelet | boolean | false | Run wavelet CWT |
| runJTKCycle | boolean | false | Run JTK_CYCLE |
| cosinorModel | string | Standard | Standard or Damped |
| significanceThreshold | double | 0.05 | p-value cutoff |
| runCircaCompare | boolean | false | Run group comparison |

### Visualization
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| vizTimeSeries | boolean | true | Time-series plots |
| vizKymograph | boolean | true | Kymograph |
| vizPhaseMap | boolean | true | Spatial phase map |
| vizPeriodMap | boolean | true | Spatial period map |
| vizAmplitudeMap | boolean | true | Spatial amplitude map |
| vizRasterPlot | boolean | true | Raster/heatmap |
| vizPolarPlot | boolean | true | Polar phase plot |
| vizScalogram | boolean | true | Wavelet scalogram |

---

## Common Patterns

### 1. Basic Period Estimation (FFT + Cosinor)

Standard workflow for most experiments:
1. Pre-process (motion correct, bleach correct)
2. Draw ROIs on mean projection
3. Extract dF/F traces
4. Detrend (linear or EMD)
5. FFT → get dominant period
6. Cosinor fit with FFT period as initial guess → get period, phase, amplitude, R²
7. Report: period ± SEM, phase on polar plot, amplitude comparison

### 2. Dorsal vs Ventral SCN Comparison

1. Draw dorsal and ventral ROIs (use DorsalVentralSplitter)
2. Extract traces for each region
3. Run CircaCompare: tests for differences in MESOR, amplitude, phase
4. Report: phase difference (dorsal typically leads by 1-4h)

### 3. Wavelet Analysis for Rhythm Stability

1. Pre-process with EMD detrending (best for bioluminescence)
2. Run wavelet CWT with Morlet wavelet
3. Inspect scalogram: continuous ridge at ~24h = stable rhythm
4. Quantify: extract instantaneous period and amplitude from ridge

### 4. Treatment Effect on Rhythms

1. Record baseline (2-3 days) → apply treatment → record (3-4 days)
2. Analyze pre- and post-treatment separately
3. CircaCompare: test amplitude change (dampening?), phase shift, period change
4. Wavelet scalogram shows the transition

### 5. Population Synchrony Assessment

1. Multiple single-cell or small-ROI traces
2. Hilbert transform → instantaneous phase for each
3. Kuramoto order parameter R(t) → synchrony over time
4. Rayleigh test at each timepoint → significance of synchrony
5. Phase map → spatial pattern of synchrony

---

## Tips & Gotchas

- **Bioluminescence decay is NOT a problem** — it's expected (luciferin depletion). Use sliding percentile or EMD detrending to remove it.
- **Moving average window must = 24h exactly.** Wrong window distorts the circadian waveform.
- **Don't use max projections** for phase analysis — creates artificial phase flattening.
- **EMD can mode-mix** — the circadian signal may split across IMFs. Inspect each IMF before reconstruction.
- **FFT resolution depends on recording length**, not sampling rate. 5 days → can distinguish periods ~1h apart near 24h.
- **Hilbert transform requires narrow-band signal.** Always bandpass filter to 20-28h range first.
- **Rayleigh test is for unimodal distributions.** If you expect two phase clusters (e.g., dorsal/ventral), use other tests.
- **CircaCompare assumes fixed period.** If groups have different periods, compare periods separately first.
- **Frame interval discrepancy**: The .circadian/config.txt may show 10min but `frame_intervals.txt` says 30min — always check actual frame timing.
- **Incucyte file naming**: Files follow `{PREFIX}_{DD}d{HH}h{MM}m.tif` pattern. CHRONOS auto-detects and assembles.

---

## .circadian Directory Structure

```
.circadian/
├── config.txt              # Session parameters (key=value)
├── crop_regions.txt        # Per-file crop rectangles
├── frame_intervals.txt     # Actual frame intervals per image
├── alignment_angles.txt    # Per-file rotation angles
├── assembled/              # Assembled time-lapse stacks (.tif)
├── corrected/              # Pre-processed stacks
├── projections/            # Mean/max projections for ROI drawing
├── ROIs/                   # ImageJ ROI ZIP files per image
├── traces/                 # Extracted dF/F trace CSVs
├── rhythm/                 # Period/phase/amplitude results
├── tracking/               # Cell tracking results (optional)
├── visualizations/         # All generated plots (PNG/TIFF)
└── exports/                # Excel workbook + consolidated outputs
```

---

## External Software Tools

### Online
- **BioDare2** (biodare2.ed.ac.uk) — Free, upload CSV, get period/phase/amplitude. Methods: FFT-NLLS, MESA, JTK_CYCLE, Lomb-Scargle. Publication figures + DOIs.

### R Packages

| Package | Purpose | Install |
|---------|---------|---------|
| **CircaCompare** | Compare mesor/amplitude/phase between groups | `devtools::install_github("RWParsons/circacompare")` |
| **MetaCycle** | Meta-analysis of multiple period methods (ARSER+JTK+LS) | `install.packages("MetaCycle")` |
| **cosinor** / **cosinor2** | Cosinor regression + covariates | `install.packages("cosinor")` |
| **rain** | Rhythmicity detection (asymmetric waveforms) | `BiocManager::install("rain")` |
| **circular** | Circular statistics (Rayleigh, Watson-Williams) | `install.packages("circular")` |

### Python Libraries

| Library | Purpose | Install |
|---------|---------|---------|
| **pyBOAT** | Wavelet time-frequency analysis | `pip install pyboat` |
| **CosinorPy** | Cosinor regression, differential rhythmicity | `pip install CosinorPy` |
| **astropy.timeseries** | Lomb-Scargle periodogram | `pip install astropy` |
| **scipy.signal** | Hilbert transform, FFT, filtering | `pip install scipy` |
| **pingouin** | Circular statistics | `pip install pingouin` |

### Standalone
- **BRASS** — Excel workbook, FFT-NLLS (millar.bio.ed.ac.uk, superseded by BioDare2)
- **ClockLab** — Actimetrics, actogram/periodogram (commercial, bundled with LumiCycle)
- **LumiCycle Analysis** — Damped cosine fitting for PMT data

**Full code examples for all tools:** see `circadian-imaging-reference.md`

---

## Key References

1. **Welsh DK et al. (2004)** — Bioluminescence imaging of individual fibroblasts reveals persistent, independently phased circadian rhythms. *Curr Biol* 14:2289-2295.
2. **Yoo SH et al. (2004)** — PERIOD2::LUCIFERASE real-time reporting of circadian dynamics. *PNAS* 101:5339-5346.
3. **Hughes ME et al. (2010)** — JTK_CYCLE: efficient non-parametric algorithm for detecting rhythmic components. *J Biol Rhythms* 25:372-380.
4. **Parsons R et al. (2019)** — CircaCompare: a method to estimate and statistically support differences in mesor, amplitude, and phase. *Bioinformatics* 36:1208-1212.
5. **Torrence C & Compo GP (1998)** — A practical guide to wavelet analysis. *Bull Am Meteorol Soc* 79:61-78.
6. **Brancaccio M et al. (2013)** — A Gq-Ca²⁺ axis controls circuit-level encoding of circadian time in the suprachiasmatic nucleus. *Neuron* 78:714-728.
7. **Hastings MH, Maywood ES, Brancaccio M (2018)** — Generation of circadian rhythms in the suprachiasmatic nucleus. *Nat Rev Neurosci* 19:453-469.
8. **Brancaccio M et al. (2017)** — Astrocytes control circadian timekeeping in the SCN via glutamatergic signaling. *Neuron* 93:1420-1435.
9. **Brancaccio M et al. (2019)** — Cell-autonomous clock of astrocytes drives circadian behaviour. *Science* 363:187-192.
10. **Brancaccio M et al. (2020)** — Network-mediated encoding of circadian time. *Neuron* 108:116-132.
11. **Zielinski T et al. (2014)** — Strengths and limitations of period estimation methods for circadian data. *PLoS ONE* 9:e96462.
12. **Wu G et al. (2016)** — MetaCycle: an integrated R package to evaluate periodicity in large scale data. *Bioinformatics* 32:3351-3353.
