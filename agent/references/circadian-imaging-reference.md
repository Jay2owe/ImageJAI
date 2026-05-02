# Circadian Imaging Reference

Bioluminescence/fluorescence imaging of circadian rhythms in SCN and brain tissue:
acquisition, analysis methods, software tools, pitfalls, and statistics.

Covers bioluminescence (PER2::LUC, Bmal1-ELuc, Per1-luc) on PMT and EMCCD,
fluorescence (GCaMP6, Per2-Venus, R-GECO), Incucyte fibroblast rhythm assays,
and downstream rhythm analysis — detrending, period estimation (FFT,
Lomb-Scargle, wavelet/pyBOAT, cosinor), phase analysis (Hilbert, peak
detection, pixel-wise phase maps), synchrony metrics (Kuramoto, PLV), and
circular/rhythmicity statistics (Rayleigh, JTK_CYCLE, CircaCompare).

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code for ROI
extraction, registration (StackReg), bleach correction, kymographs.
Python analysis (detrending, periodogram, Hilbert, circular stats) runs
locally; R tools (CircaCompare, MetaCycle) run in R.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Which bioluminescent reporter should I use?" | §2 reporter systems |
| "EMCCD vs PMT vs Incucyte — which system?" | §3 acquisition systems |
| "How do I extract ROI time series in ImageJ?" | §4.1 preprocessing |
| "How do I detrend a bioluminescence baseline?" | §4.1 preprocessing |
| "How do I estimate period (FFT / Lomb-Scargle / wavelet / cosinor)?" | §4.2 period estimation |
| "How do I compute phase / phase maps?" | §4.3 phase analysis |
| "How do I measure synchrony across cells?" | §4.5 synchrony metrics |
| "Which R / Python package for rhythm analysis?" | §5 software tools |
| "Which statistical test for rhythmicity / group comparison?" | §6 statistics |
| "What's the end-to-end workflow?" | §7 quick-reference workflow |
| "What does acrophase / MESOR / CT / ZT / TTFL / Kuramoto R mean?" | §8 glossary |
| "Key papers on PER2::LUC / JTK_CYCLE / pyBOAT / CircaCompare?" | §9 key papers |
| "Why did my rhythm analysis give spurious oscillations?" | §10 pitfalls |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each reporter, instrument,
method, or concept. Use `grep -n '<term>' circadian-imaging-reference.md`
to jump.

### A
`Acrophase` §8 · `Amplitude analysis` §4.4 · `Andor iXon3` §3 · `ARSER` §5 · `astropy.timeseries` §5 · `Autocorrelation` §4.2

### B
`Background subtraction` §4.1 · `Baseline detrending` §4.1 · `Binning` §3 · `BioDare2` §5 · `Bleach correction` §4.1 · `Bmal1-dLuc` §2 · `Bmal1-ELuc` §2

### C
`Calcium imaging (GCaMP6, R-GECO)` §2 · `CCD parameters` §3 · `CircaCompare` §5, §6 · `Circular mean` §4.3 · `Circular statistics` §4.3, §6 · `Cooling (camera)` §3 · `Cosinor fitting` §4.2, §5, §6 · `CosinorPy` §5 · `Correct 3D Drift` §5 · `CT (Circadian Time)` §8

### D
`Damping` §4.4, §8, §10 · `Detrending` §4.1, §10 · `DiscoRhythm` §5 · `Dual biolum+fluor` §3

### E
`EM gain` §3 · `EMCCD` §3 · `Evaporation (medium)` §10 · `Exponential fit` §4.1 · `Exposure` §3

### F
`FAST (SCN bioluminescence plugin)` §5 · `FFT` §4.2 · `Fluorescence reporters` §2

### G
`GCaMP6` §2 · `Glossary` §8

### H
`Half-life (damping)` §4.4 · `Hilbert transform` §4.3 · `Hyperstack` — see ImageJ operations §5

### I
`ImageJ operations (StackReg / Multi Measure / Bleach Correction / Kymograph)` §5 · `Incucyte` §3 · `Instantaneous phase` §4.3

### J
`JTK_CYCLE` §5, §6

### K
`Key papers` §9 · `Kuramoto order parameter` §4.5, §8 · `Kymograph (Multi Kymograph)` §5

### L
`LomB-Scargle` §4.2, §6 · `Lomb-Scargle FAP (false alarm probability)` §4.2, §6 · `lme4 (mixed-effects)` §6 · `LumiCycle 32` §3 · `Luciferin (0.1 mM / depletion)` §3, §10

### M
`MESOR` §4.2, §8 · `MetaCycle` §5, §6 · `Mixed-effects models` §6 · `Morlet wavelet` §4.2 · `Moving average` §4.1 · `Multi Kymograph` §5 · `Multi Measure` §5 · `Multiple comparisons` §10

### O
`Objective (NA)` §3 · `ORCA-Flash4.0 (sCMOS)` §3

### P
`Pairwise phase coherence (PLV)` §4.5, §8 · `Peak detection` §4.3, §4.4 · `Peak-trough amplitude` §4.4 · `PER2::LUC` §2, §9 · `PER2::LUC-SV40` §2 · `Per1-luc` §2 · `Per2-Venus` §2 · `Period estimation` §4.2 · `Phase analysis` §4.3 · `Phase map (pixel-wise)` §4.3 · `Phototoxicity` §2, §10 · `pingouin` §5 · `PMT` §3 · `Polynomial detrending` §4.1 · `Preprocessing` §4.1 · `pyBOAT (wavelet)` §4.2, §5, §9

### Q
`Quick-Reference Workflow` §7

### R
`RAIN (asymmetric waveforms)` §5, §6 · `Rayleigh test` §4.3, §6 · `Reporter systems` §2 · `R-GECO` §2 · `Rhythmicity testing` §6 · `RLU (Relative Light Units)` §8 · `ROI extraction (ImageJ)` §4.1

### S
`Sample size` §6 · `Savitzky-Golay smoothing` §4.1 · `sCMOS` §3 · `SCN coupling (VIP)` §8 · `Sealing dishes (vacuum grease)` §3, §10 · `Set Measurements` §4.1 · `Sinc filter (pyBOAT)` §4.1, §10 · `Smoothing` §4.1 · `Software tools` §5 · `Spatial resolution (systems)` §3 · `Stability (temperature)` §3, §10 · `StackReg / TurboReg` §5 · `Statistics` §6 · `Synchrony metrics` §4.5

### T
`Temperature (+/- 0.5C)` §3, §10 · `Time Series Analyzer` §5 · `TrackMate` §5 · `TTFL (Transcription-Translation Feedback Loop)` §8

### V
`Vibration` §10 · `VIP (Vasoactive Intestinal Peptide)` §8

### W
`Watson-Williams test` §4.3, §6 · `Wavelet analysis (Morlet)` §4.2, §5 · `Workflow (end-to-end)` §7

### Z
`Z Project` §5 · `ZT (Zeitgeber Time)` §8

---

## §2 Reporter Systems

### Bioluminescence

| Reporter | Gene | Notes |
|----------|------|-------|
| **PER2::LUC** | mPer2 knockin fusion | Gold standard (Yoo et al. 2004). Requires luciferin. No excitation light. |
| **PER2::LUC-SV40** | mPer2 + SV40 polyA | Higher signal variant |
| **Bmal1-ELuc** | Bmal1 promoter | Anti-phase to PER2. Brighter than firefly Luc. |
| **Bmal1-dLuc** | Bmal1 promoter (destabilised) | Sharper peaks, better phase resolution |
| **Per1-luc** | Per1 promoter | Older transgenic system |

**Advantages:** zero phototoxicity, zero autofluorescence, weeks-long recording, excellent SNR.
**Limitations:** weak signal (needs PMT or cooled CCD, long exposures), luciferin depletion, ATP-dependent, single-colour.

### Fluorescence

| Reporter | Excitation | Emission | Use |
|----------|-----------|----------|-----|
| **GCaMP6** | 488 nm | 510 nm | Circadian calcium rhythms |
| **Per2-Venus** | 515 nm | 528 nm | Dual-colour with bioluminescence |
| **R-GECO** | 561 nm | 600 nm | Less phototoxic than GCaMP |

**Advantages:** brighter, shorter exposures, multiplexing.
**Limitations:** phototoxicity (blue light suppresses SCN rhythms), photobleaching, autofluorescence.

---

## §3 Acquisition Systems

### System Comparison

| System | Spatial Resolution | Throughput | Best For |
|--------|-------------------|-----------|----------|
| PMT (LumiCycle 32) | None (whole-dish) | 32 channels | High-throughput luminometry |
| EMCCD (Andor iXon3) | ~20 um (single-cell) | 1 dish | Spatial bioluminescence imaging |
| sCMOS (ORCA-Flash4.0) | Subcellular | 1 dish | Fluorescence-based circadian |
| Incucyte | Cellular | Multi-well | Fibroblast rhythms with fluorescent reporters |

### Typical CCD/EMCCD Parameters

| Parameter | Typical Value |
|-----------|---------------|
| Cooling | -70C to -90C |
| EM gain | 100-500 |
| Objective | 4x-10x, NA 0.3-0.5 |
| Exposure | 25-59 min per frame |
| Interval | 30-60 min |
| Binning | 4x4 or 8x8 |
| Duration | 3-10 days |

### Modality Comparison

| Modality | Phototoxicity | Duration | Resolution |
|----------|---------------|----------|------------|
| Bioluminescence | None | 1-3 weeks | ~20 um (EMCCD) |
| Fluorescence | HIGH risk | 3-7 days max | Subcellular |
| Dual biolum+fluor | Low (brief fluor) | 5-10 days | Single-cell |

### Acquisition Checklist

- Recording medium pre-warmed, luciferin freshly added (typically 0.1 mM)
- Culture dish sealed (vacuum grease) to prevent evaporation
- Camera cooled to target temperature
- Focus set and locked
- Background ROI identified
- No vibration sources, no light leaks, temperature stable (+/- 0.5C)
- Record exact start time (for ZT/CT reference)

---

## §4 Analysis Methods

### §4.1 Preprocessing

#### ROI Extraction (ImageJ)

```javascript
run("Set Measurements...", "mean integrated redirect=None decimal=4");
nROIs = roiManager("count");
for (r = 0; r < nROIs; r++) {
    for (s = 1; s <= nSlices; s++) {
        setSlice(s);
        roiManager("select", r);
        run("Measure");
    }
}
saveAs("Results", "/path/to/roi_timeseries.csv");
```

#### Background Subtraction (Python)

```python
import pandas as pd
df = pd.read_csv("roi_timeseries.csv")
bg = df["Background"]
for col in df.columns:
    if col not in ["Time", "Background"]:
        df[col] = df[col] - bg
```

#### Baseline Detrending

Bioluminescence signals typically show declining baseline (luciferin consumption). Remove before period/phase analysis.

| Method | When to Use | Code |
|--------|-------------|------|
| Polynomial (degree 2-3) | Simple, stationary baseline | `numpy.polynomial.polynomial.polyfit/polyval` |
| Moving average (24h window) | Moderate non-stationarity | `np.convolve(signal, np.ones(window)/window, 'same')` |
| Exponential fit | Bioluminescence (ideal) | `scipy.optimize.curve_fit` with `a*exp(-b*t)+c` |
| Sinc filter (recommended) | Non-stationary, avoids spurious oscillations | `pyboat.sinc_detrend(signal, T_c=2*24)` |

#### Bleach Correction (fluorescence)

```python
# Fit exponential to peaks only, then normalize (not subtract)
from scipy.signal import argrelmax
from scipy.optimize import curve_fit
peaks = argrelmax(signal, order=10)[0]
popt, _ = curve_fit(lambda t,a,b,c: a*np.exp(-b*t)+c, time[peaks], signal[peaks])
bleach = popt[0]*np.exp(-popt[1]*time)+popt[2]
corrected = signal / bleach * np.mean(bleach)
```

ImageJ: `run("Bleach Correction", "correction=[Exponential Fit]");`

#### Smoothing

```python
from scipy.signal import savgol_filter
smoothed = savgol_filter(signal, window_length=5, polyorder=3)  # adjust window to ~2h
```

### §4.2 Period Estimation

| Method | Best For | Python |
|--------|----------|--------|
| FFT | Evenly sampled, stationary | `scipy.fft.rfft/rfftfreq` |
| Lomb-Scargle | Unevenly sampled / gapped | `astropy.timeseries.LombScargle` |
| Chi-square (Sokolove-Bushell) | Robust to waveform shape | Custom (see below) |
| Autocorrelation | Simple, intuitive | `np.correlate` + `scipy.signal.find_peaks` |
| Wavelet (Morlet) | Time-varying period (non-stationary) | `pyboat` |
| Cosinor | Parametric fit, gives amplitude/phase too | `CosinorPy`, `scipy.optimize.curve_fit` |

#### FFT

```python
from scipy.fft import rfft, rfftfreq
yf = np.abs(rfft(signal))
xf = rfftfreq(len(signal), d=sampling_interval_hours)
valid = (xf > 1/48) & (xf < 1/16)  # circadian range
period = 1.0 / xf[valid][np.argmax(yf[valid])]
```

#### Lomb-Scargle

```python
from astropy.timeseries import LombScargle
frequency = np.linspace(1/32, 1/16, 10000)
ls = LombScargle(time, signal)
power = ls.power(frequency)
best_period = 1.0 / frequency[np.argmax(power)]
fap = ls.false_alarm_probability(power.max())
```

#### Wavelet (pyBOAT)

```python
import pyboat
detrended = pyboat.sinc_detrend(signal, T_c=64)
periods = np.linspace(16, 32, 200)
modulus, transform = pyboat.compute_spectrum(detrended, dt, periods)
ridge_ys = pyboat.get_maxRidge_ys(modulus)
ridge_periods = periods[ridge_ys]
print(f"Mean period: {np.mean(ridge_periods):.2f} h")
```

#### Cosinor Fitting

```python
from scipy.optimize import curve_fit

def cosinor_model(t, mesor, amplitude, acrophase, period):
    return mesor + amplitude * np.cos(2 * np.pi * t / period + acrophase)

p0 = [np.mean(signal), (np.max(signal)-np.min(signal))/2, 0, 24.0]
bounds = ([p0[0]-3*p0[1], 0, -np.pi, 16], [p0[0]+3*p0[1], 3*p0[1], np.pi, 32])
popt, pcov = curve_fit(cosinor_model, time, signal, p0=p0, bounds=bounds)
mesor, amplitude, acrophase, period = popt

# R-squared
fitted = cosinor_model(time, *popt)
r_squared = 1 - np.sum((signal-fitted)**2) / np.sum((signal-np.mean(signal))**2)
```

### §4.3 Phase Analysis

#### Peak Detection

```python
from scipy.signal import find_peaks
min_distance = int(18 / sampling_interval_hours)
peaks, _ = find_peaks(signal, distance=min_distance, prominence=0.1)
peak_times = time[peaks]
```

#### Hilbert Transform (instantaneous phase)

```python
from scipy.signal import hilbert, butter, filtfilt

# Bandpass around circadian range first
sampling_rate = 1.0 / (time[1] - time[0])
nyq = sampling_rate / 2
b, a = butter(3, [(1/30)/nyq, (1/18)/nyq], btype='band')
filtered = filtfilt(b, a, signal)

analytic = hilbert(filtered)
amplitude_envelope = np.abs(analytic)
phase_hours = (np.unwrap(np.angle(analytic)) % (2*np.pi)) * 24 / (2*np.pi)
```

**Important:** Signal must be narrow-band (detrended + bandpass-filtered) for meaningful results.

#### Phase Maps (pixel-wise across SCN)

```python
def compute_phase_map(stack, time):
    """stack: (time, y, x). Returns 2D phase map in hours."""
    from scipy.signal import hilbert
    ny, nx = stack.shape[1], stack.shape[2]
    phase_map = np.full((ny, nx), np.nan)
    for y in range(ny):
        for x in range(nx):
            ts = stack[:, y, x].astype(float)
            if np.std(ts) < np.mean(ts) * 0.05:
                continue
            detrended = ts - np.polyval(np.polyfit(time, ts, 2), time)
            analytic = hilbert(detrended)
            ref_t = len(time) // 2
            phase_map[y, x] = (np.angle(analytic[ref_t]) % (2*np.pi)) * 24 / (2*np.pi)
    return phase_map
```

#### Circular Statistics

```python
def circular_mean(phases_rad):
    return np.arctan2(np.mean(np.sin(phases_rad)), np.mean(np.cos(phases_rad)))

def circular_std(phases_rad):
    R = np.sqrt(np.mean(np.cos(phases_rad))**2 + np.mean(np.sin(phases_rad))**2)
    return np.sqrt(-2 * np.log(R))

def rayleigh_test(phases_rad):
    """H0: uniform (no rhythm). Returns z, p."""
    n = len(phases_rad)
    R = np.sqrt(np.sum(np.cos(phases_rad))**2 + np.sum(np.sin(phases_rad))**2) / n
    z = n * R**2
    p = np.exp(-z) * (1 + (2*z - z**2)/(4*n))
    return z, p
```

**R:**
```r
library(circular)
phases_rad <- circular(phases_hours * 2 * pi / 24)
mean.circular(phases_rad)
rayleigh.test(phases_rad)
watson.williams.test(list(group1_rad, group2_rad))
```

### §4.4 Amplitude Analysis

```python
def peak_trough_amplitude(signal, time, sampling_interval_hours):
    from scipy.signal import find_peaks
    min_dist = int(18 / sampling_interval_hours)
    peaks, _ = find_peaks(signal, distance=min_dist)
    troughs, _ = find_peaks(-signal, distance=min_dist)
    amplitudes = []
    for pk in peaks:
        nearest_tr = troughs[np.argmin(np.abs(troughs - pk))]
        amplitudes.append((signal[pk] - signal[nearest_tr]) / 2)
    return amplitudes
```

For damping rate estimation, fit exponential envelope to peaks: `a * exp(-t/tau)`.
Half-life = `tau * ln(2)`. Useful for peripheral vs SCN comparison.

### §4.5 Synchrony Metrics

#### Kuramoto Order Parameter

```python
def kuramoto_order_parameter(phases_matrix):
    """phases_matrix: (n_cells, n_timepoints) in radians. R=1 perfect sync, R=0 desync."""
    Z = np.mean(np.exp(1j * phases_matrix), axis=0)
    return np.abs(Z), np.angle(Z)
```

#### Pairwise Phase Coherence

```python
def pairwise_phase_coherence(phases_matrix):
    n = phases_matrix.shape[0]
    coherence = np.zeros((n, n))
    for i in range(n):
        for j in range(i+1, n):
            phase_diff = phases_matrix[i] - phases_matrix[j]
            plv = np.abs(np.mean(np.exp(1j * phase_diff)))
            coherence[i, j] = coherence[j, i] = plv
    np.fill_diagonal(coherence, 1.0)
    return coherence
```

---

## §5 Software Tools

### Web-Based

| Tool | Purpose |
|------|---------|
| **BioDare2** (biodare2.ed.ac.uk) | Upload CSV, get period/phase/amplitude. Multiple methods. Publication figures. Free. |
| **DiscoRhythm** (Bioconductor) | JTK_CYCLE, cosinor, ARSER. Shiny web app. Omics-scale. |

### R Packages

| Package | Purpose | Key Function |
|---------|---------|-------------|
| **CircaCompare** | Compare mesor/amplitude/phase between groups | `circacompare()` |
| **MetaCycle** | Meta-analysis: ARSER + JTK + LS | `meta2d()` |
| **cosinor** | Cosinor regression | `cosinor.lm()` |
| **circular** | Rayleigh, Watson-Williams | `rayleigh.test()`, `watson.williams.test()` |
| **rain** | Asymmetric waveforms | Bioconductor |

**CircaCompare example:**
```r
library(circacompare)
result <- circacompare(x=df, col_time="time", col_group="group",
                        col_outcome="expression", period=24)
summary(result)  # mesor/amplitude/acrophase differences + p-values
```

**MetaCycle example:**
```r
library(MetaCycle)
meta2d(infile="data.csv", filestyle="csv", outdir="results/",
       timepoints=seq(0, 48, by=4), cycMethod=c("ARS","JTK","LS"),
       minper=20, maxper=28)
```

### Python Libraries

| Library | Purpose |
|---------|---------|
| **pyBOAT** | Wavelet time-frequency analysis (GUI + API) |
| **CosinorPy** | Cosinor regression, differential rhythmicity |
| **astropy.timeseries** | Lomb-Scargle periodogram |
| **scipy.signal** | Hilbert, FFT, peak detection, filtering |
| **pingouin** | Circular statistics |

### ImageJ/Fiji Operations

```javascript
// Registration (drift correction over multi-day recording)
run("StackReg", "transformation=Translation");

// ROI extraction
roiManager("Multi Measure");  // columns = ROIs, rows = timepoints

// Bleach correction
run("Bleach Correction", "correction=[Exponential Fit]");

// Z-project (if confocal z-stack at each timepoint)
run("Z Project...", "projection=[Max Intensity] all");

// Kymograph through SCN
run("Multi Kymograph", "linewidth=5");
```

**Relevant Fiji plugins:** StackReg/TurboReg, Correct 3D Drift, Time Series Analyzer,
FAST (SCN bioluminescence), TrackMate, Bio-Formats.

---

## §6 Statistics

### Testing for Rhythmicity

| Method | Best For | Tool |
|--------|----------|------|
| Rayleigh test | Phase clustering | `circular::rayleigh.test()` (R), see §4.3 (Python) |
| JTK_CYCLE | Omics (short, replicated) | `MetaCycle` (R) |
| Cosinor F-test | Single time series | `CosinorPy`, `cosinor` (R) |
| Lomb-Scargle FAP | Unevenly sampled | `astropy` (Python), `lomb` (R) |
| RAIN | Asymmetric waveforms | Bioconductor (R) |

**Typical criteria:** JTK p < 0.05 (Bonferroni-corrected), cosinor R^2 > 0.3 AND F-test p < 0.05, visual confirmation of 2+ cycles.

### Comparing Between Conditions

| Parameter | Test |
|-----------|------|
| Period | t-test/Wilcoxon on estimated periods; mixed-effects for nested data |
| Phase | Watson-Williams (circular); CircaCompare acrophase comparison |
| Amplitude | CircaCompare amplitude comparison; t-test on extracted amplitudes |
| Overall rhythm | CircaCompare (tests mesor + amplitude + phase simultaneously) |

```r
# Mixed-effects for cells nested within slices
library(lme4)
model <- lmer(period ~ treatment + (1|slice/cell), data = df)

# CircaCompare complete comparison
library(circacompare)
result <- circacompare(df, col_time="time_hours", col_group="condition",
                        col_outcome="bioluminescence", period=24)
```

### Sample Size Considerations

- **Biological replicates:** slices from different animals
- Typical N: 4-8 animals per group
- Cells are technical replicates nested within slices -- use mixed-effects models
- Minimum recording: 3 cycles (72h), ideally 5+ (120h+)

### Reporting Standards

Report: period (mean +/- SEM, method), phase (circular mean +/- circular SD, reference ZT),
amplitude (units), rhythmicity test + p-value, N (animals/slices/cells), recording conditions,
preprocessing methods, software versions.

---

## §7 Quick-Reference Workflow

```
SLICE PREP (Day 0) -> START RECORDING
  PMT: ~10-min bins | CCD: ~30-min frames | 36-37C, sealed, 0.1 mM luciferin
    |
RECORD (Day 1-7) -> EXPORT (CSV or TIFF stack)
    |
ImageJ PREPROCESSING (CCD data)
  StackReg -> Define ROIs -> Multi Measure -> export CSV
    |
DETREND (Python/R)
  Background subtract -> Baseline removal (exponential/sinc) -> Optional smooth
    |
PERIOD: BioDare2 (quick) | Lomb-Scargle | pyBOAT (wavelet)
    |
PHASE: Peak detection | Hilbert transform | Phase maps (pixel-wise)
    |
SYNCHRONY (multi-cell): Kuramoto R | Phase coherence | Raster plot
    |
STATISTICS
  Rhythmicity: JTK / Rayleigh | Period: t-test/Wilcoxon
  Phase: Watson-Williams | Amplitude: CircaCompare
    |
FIGURES: Raw traces, periodogram, phase map, polar plot, raster heatmap
```

---

## §8 Glossary

| Term | Definition |
|------|-----------|
| **Acrophase** | Time of peak in fitted cosine |
| **MESOR** | Rhythm-adjusted mean (Midline Estimating Statistic Of Rhythm) |
| **CT** | Circadian Time (CT0 = subjective dawn in constant conditions) |
| **ZT** | Zeitgeber Time (ZT0 = lights on) |
| **Damping** | Progressive amplitude decrease over cycles |
| **TTFL** | Transcription-Translation Feedback Loop (core clock) |
| **VIP** | Vasoactive Intestinal Peptide (key SCN coupling signal) |
| **Kuramoto R** | Phase synchrony index (0 = desync, 1 = perfect sync) |
| **PLV** | Phase Locking Value (pairwise synchrony) |
| **RLU** | Relative Light Units (bioluminescence) |

---

## §9 Key Papers

- **Yoo et al. 2004** PNAS -- PER2::LUC knockin mouse (gold standard reporter)
- **Welsh et al. 2004** Curr Biol -- First single-cell bioluminescence imaging
- **Hastings, Maywood & Brancaccio 2018** Nat Rev Neurosci -- SCN mechanisms review
- **Brancaccio et al. 2017** Neuron -- Astrocytes as active circadian oscillators
- **Brancaccio et al. 2019** Science -- Astrocyte clock drives behaviour
- **Brancaccio et al. 2020** Neuron -- Dual-colour single-cell SCN imaging
- **Zielinski et al. 2014** PLOS ONE -- Comparison of period estimation methods
- **Hughes et al. 2010** J Biol Rhythms -- JTK_CYCLE algorithm
- **Parsons et al. 2020** Bioinformatics -- CircaCompare method
- **Moeneclaey et al. 2022** Methods Mol Biol -- pyBOAT wavelet toolkit

---

## §10 Pitfalls

| Pitfall | Impact | Mitigation |
|---------|--------|------------|
| **Phototoxicity** (fluorescence) | Blue light suppresses SCN rhythms | Use red-shifted reporters; minimize exposure; spinning-disk |
| **Medium evaporation** | Increased osmolarity kills cells | Seal with vacuum grease; water reservoir |
| **Vibration** | Frame jitter, streaks in long exposures | Anti-vibration table; StackReg post-hoc |
| **Luciferin depletion** | Signal declines ~10-20%/day | Detrend baseline; consider 0.2 mM for >7 days |
| **Temperature fluctuation** | Phase resets, luminescence noise | Stable to +/- 0.5C |
| **Motion in slices** | ROI drift over days | StackReg registration; relative intensity changes |
| **Damping (peripheral)** | Amplitude decays over 3-7 days | Normal biology (not artifact). Use wavelet, not FFT. |
| **Detrending artifacts** | Aggressive polynomial introduces spurious oscillations | Use sinc filter (pyBOAT) or exponential fit |
| **Short recordings** | <3 cycles makes period unreliable | Aim for 5+ cycles (120h+) |
| **Edge effects** | FFT/wavelet artifacts at boundaries | Trim first/last half-cycle |
| **Assuming stationarity** | Period changes over time in perturbation experiments | Use wavelet for non-stationary data |
| **Multiple comparisons** | When testing many ROIs/cells | Bonferroni or Benjamini-Hochberg FDR |
