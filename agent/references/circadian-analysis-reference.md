# Circadian Analysis of Organotypic Slices — Reference

Sources: Welsh 2004, Yoo 2004, Hughes 2010, Parsons 2019, Torrence & Compo 1998

---

## Quick Start (CHRONOS Pipeline)

```bash
# Plugins > CHRONOS
# Module 1: Pre-processing (crop, align, motion correct, bleach correct)
# Module 2: ROI Definition (draw on projection)
# Module 3: Signal Extraction (mean intensity → dF/F)
# Module 4: Rhythm Analysis (FFT, cosinor, wavelet → period/phase/amplitude)
# Module 5: Visualization (kymographs, phase maps, polar plots, scalograms)
# Module 6: Export (Excel workbook)
# Module 7: Cell Tracking (optional — TrackMate + StarDist)
```

Session data persists in `.circadian/` directory.

---

## Core Measurements

| Parameter | Symbol | Description |
|-----------|--------|-------------|
| Period | tau | Time for one cycle (~24h in SCN) |
| Phase | phi | Peak timing relative to reference |
| Amplitude | A | Peak-to-trough / 2 |
| MESOR | M | Rhythm-adjusted mean |
| Damping | lambda | Amplitude decay rate |
| Synchrony | R | Kuramoto order parameter |

### Cosinor Model

```
y(t) = M + A * cos(2*pi*t/tau + phi)           # standard
y(t) = M + A * exp(-lambda*t) * cos(...)       # damped
```

### Circadian Parameters

| Parameter | Value |
|-----------|-------|
| Circadian period | 20-28h (strict), 18-32h (screening) |
| Minimum recording | 3 cycles; recommended 5-7 days |
| Sampling rate | 10-60 min typical |

---

## Period Estimation Methods

| Method | Best For | Strengths | Weaknesses |
|--------|----------|-----------|------------|
| **FFT** | First-pass, regular oscillations | Fast, standard | Assumes stationarity, needs >=3 cycles |
| **Lomb-Scargle** | Uneven sampling, gaps | Handles missing data | |
| **Autocorrelation** | Quick check | Intuitive | Low precision |
| **Wavelet (Morlet CWT)** | Time-varying period, rhythm onset/loss | Scalogram shows dynamics | Edge effects (COI) |
| **JTK_CYCLE** | Robust rhythmicity test | Non-parametric, standard for screens | Assumes symmetry |
| **Chi-Square** | Non-sinusoidal (actograms) | Shape-robust | Susceptible to harmonics |

---

## Detrending

| Method | Best For |
|--------|----------|
| Linear/Quadratic | Constant/accelerating drift |
| Moving Average (24h) | General purpose |
| EMD | Non-stationary bioluminescence decay |
| LOESS | Adaptive |

**Moving average window should equal one period (24h).** Wrong window distorts the waveform.

---

## Phase Analysis

**Hilbert transform**: Bandpass filter to 20-28h first, then instantaneous phase = atan2(H[x], x).

**Phase maps**: Phase per pixel/ROI → colour wheel (hue = phase). Shows spatial gradients (dorsal typically leads ventral by 1-4h).

### Circular Statistics

| Metric | Range | Meaning |
|--------|-------|---------|
| Circular mean | 0-2pi | Mean peak time |
| Mean resultant (R) | 0-1 | Phase concentration |
| Circular variance (1-R) | 0-1 | Phase spread |

---

## Synchrony

### Kuramoto Order Parameter

R(t) = |(1/N) * sum(exp(i*phi_j(t)))|

| R | Interpretation |
|---|---------------|
| > 0.7 | Strong synchrony (intact SCN) |
| 0.3-0.7 | Partial |
| < 0.3 | Desynchronized |

---

## Statistical Tests

### Rhythmicity

| Test | Type | Output |
|------|------|--------|
| Rayleigh | Circular | p-value, R (phases non-uniform?) |
| Cosinor F-test | Parametric | p, R^2 (rhythm > flat?) |
| JTK_CYCLE | Non-parametric | p, period, phase, amplitude |
| RAIN | Non-parametric | p (asymmetric rhythms) |

### Group Comparison

| Test | Compares |
|------|----------|
| **CircaCompare** | MESOR, amplitude, phase between groups |
| **Watson-Williams** | Mean phases (circular ANOVA) |
| **Mardia-Watson-Wheeler** | Full phase distributions |

---

## CHRONOS Configuration

### Pre-processing
| Key | Default | Description |
|-----|---------|-------------|
| motionCorrectionMethod | SIFT | SIFT or CrossCorrelation |
| bleachMethod | (reporter-dependent) | None/MonoExp/BiExp/SlidingPercentile/SimpleRatio |
| backgroundMethod | None | RollingBall, FixedROI, MinProjection |

**Reporter defaults**: Bioluminescence → Sliding Percentile; Fluorescent → Bi-exponential.

### Rhythm Analysis
| Key | Default | Description |
|-----|---------|-------------|
| periodMinHours / Max | 18 / 30 | Search range |
| detrendingMethod | Linear | None/Linear/Quadratic/Cubic/SincFilter/EMD/LOESS |
| cosinorModel | Standard | Standard or Damped |
| significanceThreshold | 0.05 | p-value cutoff |

---

## Common Patterns

1. **Basic period**: Pre-process → ROIs → dF/F → detrend → FFT → cosinor fit → report period ± SEM
2. **Dorsal vs ventral**: Draw D/V ROIs → CircaCompare (MESOR, amplitude, phase)
3. **Wavelet stability**: EMD detrend → CWT → inspect scalogram ridge at ~24h
4. **Treatment effect**: Baseline (2-3d) → treat → record (3-4d) → CircaCompare pre vs post
5. **Population synchrony**: Multi-ROI traces → Hilbert → Kuramoto R(t) → Rayleigh test

---

## Gotchas

- Bioluminescence decay is expected (luciferin depletion) — detrend, don't worry
- Moving average window should equal 24h exactly
- Avoid max projections for phase analysis
- EMD can mode-mix — inspect each IMF
- Hilbert transform requires narrow-band signal (bandpass first)
- Rayleigh test assumes unimodal distribution
- CircaCompare assumes fixed period — compare periods separately first
- Check actual frame timing in `frame_intervals.txt` vs config

---

## External Tools

### Online
**BioDare2** (biodare2.ed.ac.uk) — free, upload CSV, get period/phase/amplitude.

### R Packages
CircaCompare, MetaCycle, cosinor/cosinor2, rain, circular

### Python
pyBOAT (wavelet), CosinorPy, astropy.timeseries (Lomb-Scargle), scipy.signal (Hilbert/FFT), pingouin (circular stats)

### Standalone
ClockLab (commercial), LumiCycle Analysis (PMT data)
