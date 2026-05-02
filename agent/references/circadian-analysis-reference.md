# Circadian Analysis of Organotypic Slices — Reference

Agent-oriented reference for circadian rhythm analysis of organotypic slice
recordings: period/phase/amplitude estimation (FFT, cosinor, wavelet,
JTK_CYCLE, Lomb-Scargle, autocorrelation, chi-square), detrending, phase
maps, Hilbert transform, Kuramoto synchrony, rhythmicity and group-comparison
statistics, and the CHRONOS Fiji pipeline.

Sources: Welsh 2004, Yoo 2004, Hughes 2010, Parsons 2019, Torrence & Compo 1998

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code for CHRONOS
modules and related processing.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript
for deeper plugin control.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Which method should I use to estimate period?" | §4 |
| "How do I detrend bioluminescence decay?" | §5 |
| "How do I compute phase maps / dorsal-ventral gradients?" | §6 |
| "What is the Kuramoto order parameter and how do I interpret R?" | §7 |
| "Which test for rhythmicity (JTK, cosinor, RAIN, Rayleigh)?" | §8.1 |
| "How do I compare rhythms between groups (CircaCompare, Watson-Williams)?" | §8.2 |
| "What are the CHRONOS pre-processing / rhythm-analysis defaults?" | §9 |
| "What's a typical analysis pattern (period, D/V, wavelet, treatment, synchrony)?" | §10 |
| "Which external tool (BioDare2, pyBOAT, CosinorPy, ClockLab)?" | §11 |
| "Why did my detrend / Hilbert / CircaCompare go wrong?" | §12 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '`<term>`' circadian-analysis-reference.md` to jump.

### A
`Actograms (chi-square)` §4 · `Amplitude (A)` §3 · `astropy.timeseries (Lomb-Scargle)` §11 · `Autocorrelation` §4

### B
`Backgroundmethod` §9.1 · `Bandpass (20-28h)` §6, §12 · `BioDare2` §11 · `Bioluminescence decay` §9.1, §12 · `bleachMethod` §9.1

### C
`Chi-Square` §4 · `CHRONOS Pipeline` §2 · `CHRONOS Configuration` §9 · `CircaCompare` §8.2, §11, §12 · `Circular mean` §6 · `Circular statistics` §6 · `Circular variance` §6 · `circular (R package)` §11 · `ClockLab` §11 · `COI (cone of influence)` §4 · `Common Patterns` §10 · `Core Measurements` §3 · `Cosinor F-test` §8.1 · `Cosinor Model (standard, damped)` §3 · `cosinor / cosinor2 (R)` §11 · `cosinorModel` §9.2 · `CosinorPy` §11 · `Cross-correlation (motion)` §9.1 · `CWT (Morlet wavelet)` §4

### D
`Damping (lambda)` §3 · `Detrending` §5 · `detrendingMethod` §9.2 · `dF/F` §2, §10 · `Dorsal vs ventral` §6, §10

### E
`EMD (Empirical Mode Decomposition)` §5, §10, §12 · `External Tools` §11

### F
`FFT` §4, §10 · `Fluorescent reporter default` §9.1 · `frame_intervals.txt` §12

### G
`Gotchas` §12 · `Group Comparison` §8.2

### H
`Hilbert transform` §6, §10, §12

### I
`IMF (intrinsic mode function)` §12

### J
`JTK_CYCLE` §4, §8.1

### K
`Kuramoto order parameter (R)` §7, §10

### L
`Linear / Quadratic detrending` §5 · `LOESS` §5 · `Lomb-Scargle` §4, §11 · `LumiCycle Analysis` §11

### M
`Mardia-Watson-Wheeler` §8.2 · `Max projection warning` §12 · `Mean resultant (R)` §6 · `MESOR (M)` §3, §8.2 · `MetaCycle (R)` §11 · `Module 1 Pre-processing` §2 · `Module 2 ROI Definition` §2 · `Module 3 Signal Extraction` §2 · `Module 4 Rhythm Analysis` §2 · `Module 5 Visualization` §2 · `Module 6 Export` §2 · `Module 7 Cell Tracking` §2 · `motionCorrectionMethod` §9.1 · `Moving Average (24h)` §5, §12

### O
`Online tools` §11

### P
`Period (tau)` §3 · `Period Estimation Methods` §4 · `periodMinHours / periodMaxHours` §9.2 · `Phase (phi)` §3 · `Phase Analysis` §6 · `Phase maps` §6 · `pingouin` §11 · `Polar plots` §2 · `Population synchrony` §10 · `Pre-processing` §9.1 · `pyBOAT` §11 · `Python packages` §11

### Q
`Quick Start (CHRONOS)` §2

### R
`RAIN` §8.1 · `rain (R package)` §11 · `Rayleigh test` §8.1, §10, §12 · `Reporter defaults` §9.1 · `Rhythm Analysis` §9.2 · `Rhythmicity tests` §8.1 · `RollingBall` §9.1 · `R packages` §11

### S
`Scalogram` §2, §10 · `Session data (.circadian/)` §2 · `SIFT` §9.1 · `significanceThreshold` §9.2 · `SimpleRatio` §9.1 · `SlidingPercentile` §9.1 · `scipy.signal` §11 · `Stationarity assumption (FFT)` §4 · `Statistical Tests` §8 · `Symbol table` §3 · `Synchrony` §7 · `Synchrony (R interpretation)` §7

### T
`Treatment effect design` §10

### W
`Watson-Williams` §8.2 · `Wavelet (Morlet CWT)` §4, §10

---

## §2 Quick Start (CHRONOS Pipeline)

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

## §3 Core Measurements

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

## §4 Period Estimation Methods

| Method | Best For | Strengths | Weaknesses |
|--------|----------|-----------|------------|
| **FFT** | First-pass, regular oscillations | Fast, standard | Assumes stationarity, needs >=3 cycles |
| **Lomb-Scargle** | Uneven sampling, gaps | Handles missing data | |
| **Autocorrelation** | Quick check | Intuitive | Low precision |
| **Wavelet (Morlet CWT)** | Time-varying period, rhythm onset/loss | Scalogram shows dynamics | Edge effects (COI) |
| **JTK_CYCLE** | Robust rhythmicity test | Non-parametric, standard for screens | Assumes symmetry |
| **Chi-Square** | Non-sinusoidal (actograms) | Shape-robust | Susceptible to harmonics |

---

## §5 Detrending

| Method | Best For |
|--------|----------|
| Linear/Quadratic | Constant/accelerating drift |
| Moving Average (24h) | General purpose |
| EMD | Non-stationary bioluminescence decay |
| LOESS | Adaptive |

**Moving average window should equal one period (24h).** Wrong window distorts the waveform.

---

## §6 Phase Analysis

**Hilbert transform**: Bandpass filter to 20-28h first, then instantaneous phase = atan2(H[x], x).

**Phase maps**: Phase per pixel/ROI → colour wheel (hue = phase). Shows spatial gradients (dorsal typically leads ventral by 1-4h).

### Circular Statistics

| Metric | Range | Meaning |
|--------|-------|---------|
| Circular mean | 0-2pi | Mean peak time |
| Mean resultant (R) | 0-1 | Phase concentration |
| Circular variance (1-R) | 0-1 | Phase spread |

---

## §7 Synchrony

### Kuramoto Order Parameter

R(t) = |(1/N) * sum(exp(i*phi_j(t)))|

| R | Interpretation |
|---|---------------|
| > 0.7 | Strong synchrony (intact SCN) |
| 0.3-0.7 | Partial |
| < 0.3 | Desynchronized |

---

## §8 Statistical Tests

### §8.1 Rhythmicity

| Test | Type | Output |
|------|------|--------|
| Rayleigh | Circular | p-value, R (phases non-uniform?) |
| Cosinor F-test | Parametric | p, R^2 (rhythm > flat?) |
| JTK_CYCLE | Non-parametric | p, period, phase, amplitude |
| RAIN | Non-parametric | p (asymmetric rhythms) |

### §8.2 Group Comparison

| Test | Compares |
|------|----------|
| **CircaCompare** | MESOR, amplitude, phase between groups |
| **Watson-Williams** | Mean phases (circular ANOVA) |
| **Mardia-Watson-Wheeler** | Full phase distributions |

---

## §9 CHRONOS Configuration

### §9.1 Pre-processing
| Key | Default | Description |
|-----|---------|-------------|
| motionCorrectionMethod | SIFT | SIFT or CrossCorrelation |
| bleachMethod | (reporter-dependent) | None/MonoExp/BiExp/SlidingPercentile/SimpleRatio |
| backgroundMethod | None | RollingBall, FixedROI, MinProjection |

**Reporter defaults**: Bioluminescence → Sliding Percentile; Fluorescent → Bi-exponential.

### §9.2 Rhythm Analysis
| Key | Default | Description |
|-----|---------|-------------|
| periodMinHours / Max | 18 / 30 | Search range |
| detrendingMethod | Linear | None/Linear/Quadratic/Cubic/SincFilter/EMD/LOESS |
| cosinorModel | Standard | Standard or Damped |
| significanceThreshold | 0.05 | p-value cutoff |

---

## §10 Common Patterns

1. **Basic period**: Pre-process → ROIs → dF/F → detrend → FFT → cosinor fit → report period ± SEM
2. **Dorsal vs ventral**: Draw D/V ROIs → CircaCompare (MESOR, amplitude, phase)
3. **Wavelet stability**: EMD detrend → CWT → inspect scalogram ridge at ~24h
4. **Treatment effect**: Baseline (2-3d) → treat → record (3-4d) → CircaCompare pre vs post
5. **Population synchrony**: Multi-ROI traces → Hilbert → Kuramoto R(t) → Rayleigh test

---

## §11 External Tools

### Online
**BioDare2** (biodare2.ed.ac.uk) — free, upload CSV, get period/phase/amplitude.

### R Packages
CircaCompare, MetaCycle, cosinor/cosinor2, rain, circular

### Python
pyBOAT (wavelet), CosinorPy, astropy.timeseries (Lomb-Scargle), scipy.signal (Hilbert/FFT), pingouin (circular stats)

### Standalone
ClockLab (commercial), LumiCycle Analysis (PMT data)

---

## §12 Gotchas

- Bioluminescence decay is expected (luciferin depletion) — detrend, don't worry
- Moving average window should equal 24h exactly
- Avoid max projections for phase analysis
- EMD can mode-mix — inspect each IMF
- Hilbert transform requires narrow-band signal (bandpass first)
- Rayleigh test assumes unimodal distribution
- CircaCompare assumes fixed period — compare periods separately first
- Check actual frame timing in `frame_intervals.txt` vs config
