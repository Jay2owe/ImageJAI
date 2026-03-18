# Circadian Rhythm Analysis of Organotypic Brain Slice Cultures

A comprehensive reference for bioluminescence/fluorescence imaging of circadian
rhythms in SCN and brain tissue, covering biology, acquisition, analysis methods,
software tools, key papers, pitfalls, and statistics.

---

## 1. Circadian Biology Fundamentals for Imaging

### 1.1 The Suprachiasmatic Nucleus (SCN)

The SCN is the master circadian pacemaker in mammals, located bilaterally above
the optic chiasm in the anterior hypothalamus. Each nucleus contains approximately
10,000 neurons. The SCN receives direct photic input from intrinsically
photosensitive retinal ganglion cells (ipRGCs) via the retinohypothalamic tract,
synchronising internal time to the external light-dark cycle.

Key features:
- **Autonomous oscillation**: SCN neurons are cell-autonomous circadian oscillators
- **Network synchrony**: intercellular coupling (VIP, GABA, gap junctions) produces
  a coherent, high-amplitude tissue-level rhythm far more robust than any single cell
- **Photoperiodic encoding**: dorsomedial ("shell") and ventrolateral ("core")
  subregions encode day length through differential phasing of neuronal populations
- **Output**: SCN drives rhythms in behaviour, hormone secretion, body temperature,
  and peripheral organ clocks via neural and humoral signals

### 1.2 Organotypic Slice Cultures

Organotypic cultures are thin (150-400 um) brain sections maintained alive on
semi-permeable membranes (Millicell inserts) at the air-liquid interface, bathed
in serum-containing medium. They preserve cytoarchitecture, synaptic connections,
and circuit-level properties while permitting long-term (days to weeks) optical
recording.

**Preparation protocol (standard)**:
1. Sacrifice mouse (typically postnatal day 6-10, or adult)
2. Rapidly dissect brain into ice-cold Gey's balanced salt solution (GBSS)
   supplemented with glucose (0.5%) and antibiotic
3. Cut 250-300 um coronal sections on a vibrating microtome (Vibratome/Leica VT1200S)
   - NOT a tissue chopper for acute recordings (disrupts rhythms)
4. Identify SCN-containing slices under dissecting microscope
5. Trim to <15 mm^2 for long-term survival on static membrane cultures
6. Place on Millicell-CM membrane insert (Millipore PICM0RG50)
7. Culture in recording medium: DMEM (no phenol red) + 10 mM HEPES, B27 supplement,
   penicillin/streptomycin, 0.1 mM D-luciferin (for bioluminescence)
8. Seal dish with vacuum grease or parafilm to prevent evaporation
9. Place in light-tight, temperature-controlled (36-37C) incubation chamber

**Why use slices?**
- Intact neural circuit preserved (vs dissociated neurons which desynchronize)
- Spatial information maintained (regional phase differences visible)
- Single-cell resolution achievable with CCD/EMCCD imaging
- Can sustain rhythms for 1-3+ weeks ex vivo
- Amenable to pharmacological manipulation, optogenetics, viral transduction
- SCN slices oscillate indefinitely; peripheral tissue slices damp after ~3-7 days

### 1.3 Reporter Systems

#### Bioluminescence reporters

| Reporter | Gene | Emission | Notes |
|----------|------|----------|-------|
| **PER2::LUC** | mPer2 knockin fusion | ~560 nm (green-yellow) | Gold standard. Yoo et al. 2004. Requires luciferin substrate. No excitation light needed. |
| **PER2::LUC-SV40** | mPer2 knockin + SV40 polyA | ~560 nm | Higher signal variant from Northwestern |
| **Bmal1-ELuc** | Bmal1 promoter driving enhanced luciferase | ~540 nm | Anti-phase to PER2. Brighter than firefly Luc. Nakajima et al. 2010 |
| **Bmal1-dLuc** | Bmal1 promoter driving destabilised Luc | ~560 nm | Destabilised = sharper peaks, better phase resolution |
| **Per1-luc** | Per1 promoter driving luciferase | ~560 nm | Transgenic (random integration). Older system. |
| **Cry1-luc** | Cry1 promoter driving luciferase | ~560 nm | Used for CRY1 expression dynamics |

Bioluminescence advantages:
- Zero phototoxicity (no excitation light)
- Zero autofluorescence background
- Can record continuously for weeks
- Signal-to-noise excellent for circadian timescales (long integration)

Bioluminescence limitations:
- Weak signal (requires sensitive PMT or cooled CCD, long exposures)
- Luciferin consumption/depletion over days (0.1-0.2 mM standard)
- ATP-dependent (signal drops if cells are unhealthy)
- Single colour limits multiplexing (but see dual-colour with NanoLuc)

#### Fluorescence reporters

| Reporter | Type | Excitation | Emission | Use |
|----------|------|-----------|----------|-----|
| **GCaMP6** | Calcium indicator | 488 nm | 510 nm | Circadian calcium rhythms in SCN |
| **Per2-Venus** | Clock gene-fluorescent protein | 515 nm | 528 nm | Dual-colour with bioluminescence |
| **GFP/mCherry fusions** | Various promoters | 488/561 nm | 510/610 nm | Cell-type markers, Cre-reporters |
| **R-GECO** | Red calcium indicator | 561 nm | 600 nm | Less phototoxic than GCaMP |

Fluorescence advantages:
- Brighter signal, shorter exposures
- Multiple colours for multiplexing
- Calcium/cAMP reporters reveal physiology, not just transcription

Fluorescence limitations:
- **Phototoxicity**: major concern for multi-day imaging. Blue light (470 nm)
  suppresses circadian rhythms in cultured SCN slices
- Photobleaching accumulates over days
- Autofluorescence from culture medium/membrane
- Must balance temporal resolution vs light dose

#### Dual-colour imaging
Brancaccio et al. (Neuron 2020) combined PER2::LUC bioluminescence with
Cry1-promoter-driven GFP to simultaneously image clock gene expression and
cell-type identity at single-cell resolution across the SCN. Alternating
30-min bioluminescence exposures with brief (<1s) fluorescence snapshots.

### 1.4 The Molecular Clock (TTFL)

The transcription-translation feedback loop (TTFL):

```
         CLOCK:BMAL1 (activators)
              |
              v
    E-box --> Per1/2, Cry1/2 transcription
              |
              v
         PER:CRY proteins accumulate in cytoplasm
              |
              v
         PER:CRY translocate to nucleus
              |
              v
    PER:CRY --| CLOCK:BMAL1  (negative feedback)
              |
              v
         PER:CRY degraded (CK1d/e phosphorylation -> ubiquitination)
              |
              v
         CLOCK:BMAL1 released --> new cycle (~24h)

    Secondary loop: ROR/REV-ERB --> Bmal1 transcription
```

PER2::LUC peaks correspond to PER2 protein peaks, typically in circadian
evening/early night (CT12-16 in vivo, or equivalent ex vivo phase).

---

## 2. Image Acquisition Systems

### 2.1 Photomultiplier Tube (PMT) Systems

**LumiCycle 32 (Actimetrics)**:
- 4 PMTs selected for low dark counts, high green-spectrum sensitivity
- 32-channel carousel: 8 dishes share each PMT via turntable
- Each dish counted for ~18s per cycle, sampling interval ~10 min
- Fits inside standard CO2 incubator
- Built-in analysis software: automatic baseline correction, period/phase extraction
- Best for: high-throughput whole-tissue luminometry (no spatial resolution)

**Kronos (ATTO)**:
- Similar PMT-based luminometer, 96-well format
- Temperature-controlled, no incubator needed
- Lower sensitivity than LumiCycle but higher throughput

**Custom PMT setups**:
- Hamamatsu H7360 or H7421 photon-counting heads
- Light-tight dark box, heated to 36-37C
- Photon counts integrated over 1-10 min bins
- Single-channel but extremely sensitive

**PMT acquisition parameters**:
- Integration time: 1-10 min per sample (typically 6s per dish x 32 dishes = ~3 min cycle)
- Duration: 5-14 days typical
- Temperature: 36-37C
- No imaging optics needed (whole-dish photon counting)
- Data output: photon counts vs time (1D time series per sample)

### 2.2 CCD / EMCCD Camera Systems

**For spatial bioluminescence imaging of SCN slices**:

| Parameter | Typical value | Notes |
|-----------|---------------|-------|
| Camera | EMCCD (Andor iXon3, Hamamatsu ImagEM) or cooled CCD (Hamamatsu ORCA-II ER) | EMCCD preferred for single-cell |
| Cooling | -70C to -90C | Minimises dark current noise |
| EM gain | 100-500 (EMCCD only) | Higher gain = more noise at extreme values |
| Objective | 4x-10x, high NA (0.3-0.5) | Low magnification to capture whole SCN |
| Exposure | 25-59 min per frame | Long exposures needed for weak bioluminescence |
| Interval | 30-60 min (limited by exposure time) | 30 min common: 29 min exposure + 1 min readout |
| Binning | 4x4 or 8x8 | Increases signal at cost of spatial resolution |
| Duration | 3-10 days | Limited by luciferin depletion, culture health |
| Filter | None (bioluminescence) or appropriate emission filter (fluorescence) | |
| Pixel array | 512x512 or 1024x1024 (after binning) | |

**Specific published configurations**:
- Brancaccio et al.: EMCCD (Andor iXon3 888), 10x objective, 29-min exposure,
  1-min interval for readout, 4x4 binning, EM gain 300, -70C cooling
- ORCA-II ER setups: 59-min exposure, 1-min readout interval, 3 consecutive days
- Enoki et al.: 10x objective, 4x4 binning of 1344x1024 array

### 2.3 sCMOS Cameras

Modern sCMOS (Hamamatsu ORCA-Flash4.0, Andor Zyla) offer:
- Larger sensor (2048x2048)
- Lower read noise than CCD (1-2 e- vs 5-10 e-)
- No EM gain artifacts
- Higher frame rates (not needed for circadian)
- Good for fluorescence-based circadian imaging where exposures are shorter
- For bioluminescence: still inferior to EMCCD at very low light levels

### 2.4 Incucyte System (Sartorius)

- Housed inside incubator (stable 37C, 5% CO2)
- Automated phase-contrast + fluorescence imaging
- Scheduled scanning: every 15 min to hours
- Multi-well plate format (96-well, 24-well)
- Good for: fibroblast circadian rhythms with fluorescent reporters
- Limitations: not optimized for bioluminescence (no photon-counting mode),
  not ideal for organotypic slices (designed for adherent monolayers)

### 2.5 Brightfield vs Fluorescence vs Bioluminescence

| Modality | Use | Phototoxicity | Duration | Resolution |
|----------|-----|---------------|----------|------------|
| Brightfield/phase | Morphology, confluence, slice health | None | Unlimited | Cellular |
| Bioluminescence | Clock gene expression (PER2::LUC) | None | 1-3 weeks | ~50-100 um (whole-tissue PMT) or ~20 um (EMCCD) |
| Fluorescence | Calcium (GCaMP), cell identity (GFP) | HIGH risk | 3-7 days max | Subcellular possible |
| Dual biolum+fluor | Clock + cell type simultaneously | Low (brief fluor) | 5-10 days | Single-cell |

### 2.6 Acquisition Checklist

```
Pre-recording:
[ ] Vibratome blades fresh, ice-cold cutting solution
[ ] Recording medium pre-warmed, luciferin freshly added (0.1 mM)
[ ] Millicell membranes pre-wet with medium
[ ] Culture dish sealed (vacuum grease) to prevent evaporation
[ ] Dark box / incubation chamber at 36-37C
[ ] Camera cooled to target temperature (-70C+)
[ ] Focus set and locked (anti-drift if available)
[ ] Test exposure to verify signal above background
[ ] Background ROI identified for later subtraction

During recording:
[ ] No vibration sources nearby (close doors gently)
[ ] Temperature stable (+/- 0.5C)
[ ] No light leaks (check door seals)
[ ] Monitor disk space (30-min exposures x 7 days = ~300-700 frames)

Post-recording:
[ ] Export as TIFF stack or individual frames with timestamps
[ ] Record exact start time (for phase reference to ZT/CT)
[ ] Note any interruptions (medium changes, focus adjustments)
```

---

## 3. Analysis Methods

### 3.1 Preprocessing

#### 3.1.1 ROI extraction (ImageJ/Fiji)

```javascript
// ImageJ macro: extract mean intensity from ROIs over time-lapse stack
// Assumes a time-lapse stack is open and ROIs are in the ROI Manager

run("Set Measurements...", "mean integrated redirect=None decimal=4");
nROIs = roiManager("count");
nSlices = nSlices();

// Create results array
for (r = 0; r < nROIs; r++) {
    for (s = 1; s <= nSlices; s++) {
        setSlice(s);
        roiManager("select", r);
        run("Measure");
    }
}

// Save results
saveAs("Results", "/path/to/roi_timeseries.csv");
```

For background subtraction, add a background ROI and subtract its mean from
each signal ROI at each timepoint.

#### 3.1.2 Background subtraction

```python
import numpy as np
import pandas as pd

# Load ROI time series (columns: Time, ROI_1, ROI_2, ..., Background)
df = pd.read_csv("roi_timeseries.csv")

# Subtract background from each ROI
bg = df["Background"]
for col in df.columns:
    if col not in ["Time", "Background"]:
        df[col] = df[col] - bg

# Save corrected data
df.to_csv("roi_timeseries_bgcorr.csv", index=False)
```

#### 3.1.3 Baseline detrending

Bioluminescence signals typically show a declining baseline (exponential decay
due to luciferin consumption and reporter decay). This must be removed before
period/phase analysis.

**Method 1: Polynomial detrending**
```python
import numpy as np
from numpy.polynomial import polynomial as P

def detrend_polynomial(signal, time, degree=3):
    """Remove polynomial baseline (typically degree 2-3)."""
    coeffs = P.polyfit(time, signal, degree)
    baseline = P.polyval(time, coeffs)
    return signal - baseline, baseline
```

**Method 2: Moving average (24h window)**
```python
def detrend_moving_average(signal, window_hours=24, sampling_interval_hours=0.5):
    """Remove 24h moving average baseline."""
    window = int(window_hours / sampling_interval_hours)
    if window % 2 == 0:
        window += 1  # ensure odd window
    baseline = np.convolve(signal, np.ones(window)/window, mode='same')
    # Handle edges
    half = window // 2
    baseline[:half] = baseline[half]
    baseline[-half:] = baseline[-half-1]
    return signal - baseline, baseline
```

**Method 3: Exponential baseline (for bioluminescence)**
```python
from scipy.optimize import curve_fit

def exp_baseline(t, a, b, c):
    return a * np.exp(-b * t) + c

def detrend_exponential(signal, time):
    """Remove exponential decay baseline (ideal for bioluminescence)."""
    popt, _ = curve_fit(exp_baseline, time, signal,
                        p0=[signal[0], 0.01, signal[-1]], maxfev=10000)
    baseline = exp_baseline(time, *popt)
    return signal - baseline, baseline
```

**Method 4: Sinc filter (recommended by pyBOAT)**
```python
# pyBOAT implements optimal sinc-filter detrending
# Removes low-frequency components while minimizing spurious oscillations
# Better than polynomial for non-stationary baselines
import pyboat
signal_detrended = pyboat.sinc_detrend(signal, T_c=2*24)  # cutoff at 2x period
```

#### 3.1.4 Bleach correction (fluorescence)

For fluorescence reporters, photobleaching causes additional signal decay:

```python
def bleach_correct_exponential(signal, time):
    """Fit and remove exponential photobleaching."""
    # Fit to peaks only (avoid fitting the oscillation)
    from scipy.signal import argrelmax
    peaks = argrelmax(signal, order=10)[0]
    if len(peaks) < 3:
        peaks = np.arange(len(signal))
    popt, _ = curve_fit(exp_baseline, time[peaks], signal[peaks],
                        p0=[signal[0], 0.001, 0], maxfev=10000)
    bleach = exp_baseline(time, *popt)
    # Normalize rather than subtract (preserves amplitude ratios)
    return signal / bleach * np.mean(bleach), bleach
```

ImageJ bleach correction:
```javascript
// Fiji: Plugins > Registration > Bleach Correction
// Or: Image > Adjust > Bleach Correction (if installed)
run("Bleach Correction", "correction=[Exponential Fit]");
```

#### 3.1.5 Smoothing

```python
from scipy.signal import savgol_filter

def smooth_signal(signal, window_hours=2, sampling_interval_hours=0.5, polyorder=3):
    """Savitzky-Golay smoothing preserving peak shape."""
    window = int(window_hours / sampling_interval_hours)
    if window % 2 == 0:
        window += 1
    return savgol_filter(signal, window, polyorder)
```

### 3.2 Period Estimation

#### 3.2.1 FFT / Periodogram

```python
from scipy.fft import rfft, rfftfreq

def estimate_period_fft(signal, sampling_interval_hours):
    """Estimate dominant period using FFT."""
    n = len(signal)
    yf = np.abs(rfft(signal))
    xf = rfftfreq(n, d=sampling_interval_hours)

    # Exclude DC component and very low/high frequencies
    valid = (xf > 1/48) & (xf < 1/16)  # 16-48h range for circadian
    peak_freq = xf[valid][np.argmax(yf[valid])]
    period = 1.0 / peak_freq

    return period, xf, yf
```

#### 3.2.2 Lomb-Scargle Periodogram

Best for unevenly sampled or gapped data:

```python
from astropy.timeseries import LombScargle

def estimate_period_lombscargle(time, signal, min_period=16, max_period=32):
    """Lomb-Scargle periodogram for circadian period estimation."""
    frequency = np.linspace(1/max_period, 1/min_period, 10000)
    ls = LombScargle(time, signal)
    power = ls.power(frequency)

    best_freq = frequency[np.argmax(power)]
    best_period = 1.0 / best_freq

    # False alarm probability
    fap = ls.false_alarm_probability(power.max())

    return best_period, fap, frequency, power
```

#### 3.2.3 Chi-Square Periodogram (Sokolove-Bushell)

Classic method for activity rhythms, robust to waveform shape:

```python
def chi_square_periodogram(signal, sampling_interval_hours,
                           min_period=18, max_period=30, step=0.1):
    """Sokolove-Bushell chi-square periodogram."""
    n = len(signal)
    test_periods = np.arange(min_period, max_period, step)
    qp_values = []

    for period in test_periods:
        p_samples = int(round(period / sampling_interval_hours))
        if p_samples < 2:
            continue
        n_complete = (n // p_samples) * p_samples
        if n_complete < p_samples * 2:
            qp_values.append(0)
            continue

        data = signal[:n_complete].reshape(-1, p_samples)
        K = data.shape[0]  # number of complete cycles
        N = n_complete
        mean_profile = data.mean(axis=0)
        grand_mean = signal[:n_complete].mean()

        Qp = (N * K * np.sum((mean_profile - grand_mean)**2)) / \
             np.sum((signal[:n_complete] - grand_mean)**2)
        qp_values.append(Qp)

    best_idx = np.argmax(qp_values)
    return test_periods[best_idx], test_periods, np.array(qp_values)
```

#### 3.2.4 Autocorrelation

```python
def estimate_period_autocorrelation(signal, sampling_interval_hours,
                                     min_period=18, max_period=32):
    """Period estimation via autocorrelation peak detection."""
    from scipy.signal import find_peaks

    # Normalise
    sig = (signal - np.mean(signal)) / np.std(signal)

    # Compute autocorrelation
    n = len(sig)
    acf = np.correlate(sig, sig, mode='full')[n-1:]
    acf = acf / acf[0]
    lags = np.arange(len(acf)) * sampling_interval_hours

    # Find first major peak in circadian range
    min_lag = int(min_period / sampling_interval_hours)
    max_lag = int(max_period / sampling_interval_hours)
    peaks, properties = find_peaks(acf[min_lag:max_lag], height=0.1)

    if len(peaks) > 0:
        best_peak = peaks[np.argmax(properties['peak_heights'])]
        period = (best_peak + min_lag) * sampling_interval_hours
    else:
        period = np.nan

    return period, lags, acf
```

#### 3.2.5 Wavelet Analysis (Morlet) -- pyBOAT

```python
import pyboat

def wavelet_analysis(signal, dt_hours, period_range=(16, 32)):
    """Time-frequency analysis using continuous Morlet wavelet transform."""
    # Detrend first
    signal_detrended = pyboat.sinc_detrend(signal, T_c=2*32)

    # Compute wavelet transform
    periods = np.linspace(period_range[0], period_range[1], 200)
    modulus, transform = pyboat.compute_spectrum(signal_detrended, dt_hours, periods)

    # Extract ridge (instantaneous period over time)
    ridge = pyboat.get_maxRidge_ys(modulus)
    ridge_periods = periods[ridge]

    # Get instantaneous amplitude along ridge
    ridge_amplitudes = np.array([modulus[t, ridge[t]] for t in range(len(ridge))])

    return {
        'periods': periods,
        'modulus': modulus,  # time x period power spectrum
        'ridge_periods': ridge_periods,  # instantaneous period at each timepoint
        'ridge_amplitudes': ridge_amplitudes,  # instantaneous amplitude
        'mean_period': np.nanmean(ridge_periods),
    }
```

**pyBOAT GUI usage**:
```bash
pip install pyboat
pyboat     # launches GUI -- load CSV, set sampling interval, click Analyze
```

#### 3.2.6 Cosinor Fitting

```python
from scipy.optimize import curve_fit

def cosinor_model(t, mesor, amplitude, acrophase, period):
    """Standard cosinor: y = mesor + amplitude * cos(2*pi*t/period + acrophase)"""
    return mesor + amplitude * np.cos(2 * np.pi * t / period + acrophase)

def fit_cosinor(time, signal, period_guess=24.0, fit_period=False):
    """Fit cosinor model. If fit_period=True, period is also estimated."""
    mesor_guess = np.mean(signal)
    amp_guess = (np.max(signal) - np.min(signal)) / 2

    if fit_period:
        p0 = [mesor_guess, amp_guess, 0, period_guess]
        bounds = ([mesor_guess - 3*amp_guess, 0, -np.pi, 16],
                  [mesor_guess + 3*amp_guess, 3*amp_guess, np.pi, 32])
        popt, pcov = curve_fit(cosinor_model, time, signal, p0=p0, bounds=bounds)
        mesor, amplitude, acrophase, period = popt
    else:
        # Fix period, fit mesor/amplitude/acrophase only
        def model_fixed(t, mesor, amplitude, acrophase):
            return cosinor_model(t, mesor, amplitude, acrophase, period_guess)
        p0 = [mesor_guess, amp_guess, 0]
        popt, pcov = curve_fit(model_fixed, time, signal, p0=p0)
        mesor, amplitude, acrophase = popt
        period = period_guess

    # Ensure positive amplitude
    if amplitude < 0:
        amplitude = -amplitude
        acrophase += np.pi

    # Wrap acrophase to [0, 2*pi)
    acrophase = acrophase % (2 * np.pi)

    # Convert acrophase to hours (time of peak)
    peak_time = (period * acrophase) / (2 * np.pi)

    # Goodness of fit
    fitted = cosinor_model(time, mesor, amplitude, acrophase, period)
    ss_res = np.sum((signal - fitted)**2)
    ss_tot = np.sum((signal - np.mean(signal))**2)
    r_squared = 1 - ss_res / ss_tot

    return {
        'mesor': mesor,
        'amplitude': amplitude,
        'acrophase_rad': acrophase,
        'acrophase_hours': peak_time,
        'period': period,
        'r_squared': r_squared,
        'fitted': fitted,
    }
```

**R cosinor packages**:
```r
# Method 1: cosinor package
install.packages("cosinor")
library(cosinor)
fit <- cosinor.lm(Y ~ time(Time) + 1, data = df, period = 24)
summary(fit)

# Method 2: CosinorPy equivalent in R
install.packages("card")
library(card)
cosinor_fit <- cosinor(Y ~ 1, data = df, tau = 24)

# Method 3: psych package
library(psych)
result <- cosinor(time, signal, period = 24)
```

**Python CosinorPy**:
```bash
pip install CosinorPy
```
```python
from CosinorPy import file_parser, cosinor, cosinor1

# Fit single-component cosinor
results = cosinor1.fit_me(time, signal, period=24)

# Population-mean cosinor
results = cosinor.fit_group(df, period=24)

# Differential rhythmicity between groups
results = cosinor.compare_pairs(df_group1, df_group2, period=24)
```

### 3.3 Phase Analysis

#### 3.3.1 Peak Detection

Simple but effective for well-defined oscillations:

```python
from scipy.signal import find_peaks

def extract_phases_peaks(signal, time, sampling_interval_hours,
                         min_distance_hours=18):
    """Extract phase from peak positions."""
    min_distance = int(min_distance_hours / sampling_interval_hours)
    peaks, properties = find_peaks(signal, distance=min_distance, prominence=0.1)

    peak_times = time[peaks]

    # Interpolate for sub-sample precision
    refined_peaks = []
    for p in peaks:
        if p > 0 and p < len(signal) - 1:
            # Parabolic interpolation
            y0, y1, y2 = signal[p-1], signal[p], signal[p+1]
            delta = 0.5 * (y0 - y2) / (y0 - 2*y1 + y2)
            refined_peaks.append(time[p] + delta * sampling_interval_hours)
        else:
            refined_peaks.append(time[p])

    return np.array(refined_peaks)
```

#### 3.3.2 Hilbert Transform (Instantaneous Phase)

```python
from scipy.signal import hilbert

def hilbert_phase(signal, time):
    """Extract instantaneous phase and amplitude envelope via Hilbert transform.

    IMPORTANT: Signal must be narrow-band (detrended and bandpass-filtered
    around circadian frequency) for meaningful results.
    """
    from scipy.signal import butter, filtfilt

    # Bandpass filter around circadian range (18-30h = 0.033-0.056 cycles/h)
    sampling_rate = 1.0 / (time[1] - time[0])  # samples per hour
    nyq = sampling_rate / 2
    low = (1/30) / nyq
    high = (1/18) / nyq
    b, a = butter(3, [low, high], btype='band')
    filtered = filtfilt(b, a, signal)

    # Hilbert transform
    analytic = hilbert(filtered)
    amplitude_envelope = np.abs(analytic)
    instantaneous_phase = np.unwrap(np.angle(analytic))

    # Convert to circadian time (0-24h)
    phase_hours = (instantaneous_phase % (2 * np.pi)) * 24 / (2 * np.pi)

    return {
        'phase_rad': instantaneous_phase,
        'phase_hours': phase_hours,
        'amplitude_envelope': amplitude_envelope,
        'filtered_signal': filtered,
    }
```

#### 3.3.3 Phase Maps (Spatial Phase Across SCN)

```python
def compute_phase_map(stack, time, sampling_interval_hours):
    """Compute pixel-wise phase map from time-lapse image stack.

    stack: 3D array (time, y, x)
    Returns: 2D phase map (y, x) in hours
    """
    from scipy.signal import hilbert

    ny, nx = stack.shape[1], stack.shape[2]
    phase_map = np.zeros((ny, nx))
    amplitude_map = np.zeros((ny, nx))

    for y in range(ny):
        for x in range(nx):
            pixel_ts = stack[:, y, x].astype(float)

            # Skip low-signal pixels
            if np.std(pixel_ts) < np.mean(pixel_ts) * 0.05:
                phase_map[y, x] = np.nan
                continue

            # Detrend
            pixel_detrended = pixel_ts - np.polyval(
                np.polyfit(time, pixel_ts, 2), time)

            # Hilbert transform
            analytic = hilbert(pixel_detrended)
            phase = np.angle(analytic)

            # Take phase at a reference timepoint (e.g., middle of recording)
            ref_t = len(time) // 2
            phase_map[y, x] = (phase[ref_t] % (2*np.pi)) * 24 / (2*np.pi)
            amplitude_map[y, x] = np.abs(analytic[ref_t])

    return phase_map, amplitude_map
```

ImageJ macro for extracting pixel time series:
```javascript
// Extract time-series for each pixel in an ROI from a stack
// Then export for analysis in Python/R

setBatchMode(true);
getSelectionBounds(rx, ry, rw, rh);
n = nSlices;

// For each pixel in the selection
for (y = ry; y < ry + rh; y++) {
    for (x = rx; x < rx + rw; x++) {
        line = "" + x + "," + y;
        for (s = 1; s <= n; s++) {
            setSlice(s);
            v = getPixel(x, y);
            line = line + "," + v;
        }
        print(line);  // goes to Log window
    }
}
setBatchMode(false);
// Save Log window: File > Save As...
```

#### 3.3.4 Circular Statistics

```python
from scipy import stats

def circular_mean(phases_rad):
    """Compute circular mean of phases (in radians)."""
    return np.arctan2(np.mean(np.sin(phases_rad)),
                      np.mean(np.cos(phases_rad)))

def circular_std(phases_rad):
    """Circular standard deviation."""
    R = np.sqrt(np.mean(np.cos(phases_rad))**2 +
                np.mean(np.sin(phases_rad))**2)
    return np.sqrt(-2 * np.log(R))

def rayleigh_test(phases_rad):
    """Rayleigh test for non-uniformity (significant rhythmicity).

    H0: phases are uniformly distributed (no preferred phase)
    H1: phases are clustered (significant rhythmicity)

    Returns: z_statistic, p_value
    """
    n = len(phases_rad)
    R = np.sqrt(np.sum(np.cos(phases_rad))**2 +
                np.sum(np.sin(phases_rad))**2) / n
    z = n * R**2
    # Approximate p-value
    p = np.exp(-z) * (1 + (2*z - z**2) / (4*n) -
                       (24*z - 132*z**2 + 76*z**3 - 9*z**4) / (288*n**2))
    return z, p

def watson_williams_test(phases_group1, phases_group2):
    """Watson-Williams F-test: compare mean directions of two groups.

    Assumes von Mises distribution with equal concentration.
    Returns: F_statistic, p_value
    """
    from scipy.stats import f as f_dist

    n1, n2 = len(phases_group1), len(phases_group2)
    N = n1 + n2

    # Resultant lengths
    R1 = np.sqrt(np.sum(np.cos(phases_group1))**2 +
                 np.sum(np.sin(phases_group1))**2)
    R2 = np.sqrt(np.sum(np.cos(phases_group2))**2 +
                 np.sum(np.sin(phases_group2))**2)

    all_phases = np.concatenate([phases_group1, phases_group2])
    R_total = np.sqrt(np.sum(np.cos(all_phases))**2 +
                      np.sum(np.sin(all_phases))**2)

    # Concentration parameter estimate (kappa)
    R_bar = (R1 + R2) / N
    if R_bar < 0.53:
        kappa = 2 * R_bar + R_bar**3 + 5 * R_bar**5 / 6
    elif R_bar < 0.85:
        kappa = -0.4 + 1.39 * R_bar + 0.43 / (1 - R_bar)
    else:
        kappa = 1 / (R_bar**3 - 4*R_bar**2 + 3*R_bar)

    # Correction factor
    if kappa > 2:
        correction = 1 - 1/(2*kappa)
    else:
        correction = max(kappa - 2/(N*kappa), 0.01)

    F = correction * (N - 2) * (R1 + R2 - R_total) / (N - R1 - R2)
    p = 1 - f_dist.cdf(F, 1, N-2)

    return F, p
```

**R CircStat / circular package**:
```r
install.packages("circular")
library(circular)

# Convert hours to radians (24h -> 2*pi)
phases_rad <- circular(phases_hours * 2 * pi / 24, type = "angles",
                        units = "radians", rotation = "clock")

# Circular mean
mean.circular(phases_rad)

# Rayleigh test
rayleigh.test(phases_rad)

# Watson-Williams test (two-sample)
watson.williams.test(list(group1_rad, group2_rad))
```

**MATLAB CircStat toolbox**:
```matlab
% Install: https://github.com/circstat/circstat-matlab
addpath('circstat-matlab');

% Rayleigh test
[p, z] = circ_rtest(phases_rad);

% Watson-Williams test
[p, table] = circ_wwtest(phases_group1, phases_group2);

% Circular mean and std
mu = circ_mean(phases_rad);
s = circ_std(phases_rad);
```

### 3.4 Amplitude Analysis

#### 3.4.1 Peak-to-Trough Amplitude

```python
def peak_trough_amplitude(signal, time, sampling_interval_hours,
                           min_distance_hours=18):
    """Compute amplitude as half peak-to-trough distance for each cycle."""
    from scipy.signal import find_peaks

    min_dist = int(min_distance_hours / sampling_interval_hours)

    peaks, _ = find_peaks(signal, distance=min_dist)
    troughs, _ = find_peaks(-signal, distance=min_dist)

    amplitudes = []
    for i, pk in enumerate(peaks):
        # Find nearest trough
        nearest_tr = troughs[np.argmin(np.abs(troughs - pk))]
        amp = (signal[pk] - signal[nearest_tr]) / 2
        amplitudes.append({
            'cycle': i,
            'peak_time': time[pk],
            'trough_time': time[nearest_tr],
            'amplitude': amp,
            'peak_value': signal[pk],
            'trough_value': signal[nearest_tr],
        })

    return amplitudes
```

#### 3.4.2 Detrended Amplitude (from Cosinor)

The amplitude parameter from cosinor fitting gives the detrended amplitude
directly. See section 3.2.6.

#### 3.4.3 Damping Rate

```python
def estimate_damping(signal, time, sampling_interval_hours):
    """Estimate amplitude damping rate (exponential decay constant).

    Useful for: peripheral tissue slices (which damp) vs SCN (which don't).
    """
    from scipy.signal import find_peaks

    min_dist = int(18 / sampling_interval_hours)
    peaks, _ = find_peaks(signal, distance=min_dist)

    if len(peaks) < 3:
        return np.nan, np.nan

    peak_times = time[peaks]
    peak_values = signal[peaks]

    # Fit exponential envelope to peaks
    def exp_decay(t, a, tau):
        return a * np.exp(-t / tau)

    try:
        popt, _ = curve_fit(exp_decay, peak_times - peak_times[0],
                            peak_values, p0=[peak_values[0], 72])
        damping_rate = 1.0 / popt[1]  # 1/tau (per hour)
        half_life = popt[1] * np.log(2)  # hours
        return damping_rate, half_life
    except:
        return np.nan, np.nan
```

### 3.5 Synchrony and Network Metrics

#### 3.5.1 Kuramoto Order Parameter

The Kuramoto order parameter R(t) measures the degree of phase coherence
among a population of oscillators. R=1 means perfect synchrony, R=0 means
completely desynchronized.

```python
def kuramoto_order_parameter(phases_matrix):
    """Compute Kuramoto order parameter R(t) from matrix of phases.

    phases_matrix: (n_cells, n_timepoints) array of instantaneous phases (radians)
    Returns: R(t) array, mean_phase(t) array
    """
    n_cells = phases_matrix.shape[0]

    # Complex order parameter: Z(t) = (1/N) * sum(exp(i * theta_j(t)))
    Z = np.mean(np.exp(1j * phases_matrix), axis=0)
    R = np.abs(Z)            # synchrony index (0 to 1)
    mean_phase = np.angle(Z) # mean population phase

    return R, mean_phase
```

#### 3.5.2 Phase Coherence (Pairwise)

```python
def pairwise_phase_coherence(phases_matrix):
    """Compute mean phase coherence between all pairs of cells.

    Returns: n_cells x n_cells coherence matrix (0-1 for each pair)
    """
    n_cells = phases_matrix.shape[0]
    coherence = np.zeros((n_cells, n_cells))

    for i in range(n_cells):
        for j in range(i+1, n_cells):
            # Phase locking value (PLV)
            phase_diff = phases_matrix[i] - phases_matrix[j]
            plv = np.abs(np.mean(np.exp(1j * phase_diff)))
            coherence[i, j] = plv
            coherence[j, i] = plv

    np.fill_diagonal(coherence, 1.0)
    return coherence
```

#### 3.5.3 Standard Deviation of Phases

```python
def phase_dispersion(phases_at_timepoint):
    """Circular standard deviation of phases at a single timepoint.

    Lower = more synchronous.
    """
    return circular_std(phases_at_timepoint)

def synchrony_index(phases_matrix):
    """Time-averaged synchrony: 1 - (circular_std / max_possible_std)."""
    sync_over_time = []
    for t in range(phases_matrix.shape[1]):
        R = np.abs(np.mean(np.exp(1j * phases_matrix[:, t])))
        sync_over_time.append(R)
    return np.mean(sync_over_time)
```

#### 3.5.4 Raster Plot (Phase-ordered Heatmap)

```python
import matplotlib.pyplot as plt

def plot_raster(signals_matrix, time, sampling_interval_hours, period=24):
    """Plot circadian raster: each row = one cell, colour = intensity.

    Sort cells by phase for visual assessment of synchrony.
    """
    # Estimate phase of each cell (from first peak position)
    from scipy.signal import find_peaks
    phases = []
    for i in range(signals_matrix.shape[0]):
        peaks, _ = find_peaks(signals_matrix[i], distance=int(18/sampling_interval_hours))
        if len(peaks) > 0:
            phases.append(time[peaks[0]] % period)
        else:
            phases.append(0)

    # Sort by phase
    order = np.argsort(phases)
    sorted_signals = signals_matrix[order]

    fig, ax = plt.subplots(figsize=(12, 8))
    ax.imshow(sorted_signals, aspect='auto', cmap='inferno',
              extent=[time[0], time[-1], 0, sorted_signals.shape[0]])
    ax.set_xlabel('Time (hours)')
    ax.set_ylabel('Cell (sorted by phase)')
    ax.set_title('Circadian Raster Plot')
    plt.tight_layout()
    return fig
```

---

## 4. Software Tools

### 4.1 Online / Web-based

#### BioDare2 (https://biodare2.ed.ac.uk/)
- **Free, no installation**
- Upload CSV time series, get period/phase/amplitude estimates
- Methods: FFT-NLLS, MESA, mFourFit, Enright periodogram, Lomb-Scargle,
  Spectrum Resampling, JTK_CYCLE, eJTK
- Publication-quality visualisation
- Data sharing and DOI assignment
- **Best for**: quick period estimation, comparing methods, publication figures

#### DiscoRhythm (https://bioconductor.org/packages/DiscoRhythm/)
- R/Bioconductor package with Shiny web app
- JTK_CYCLE, cosinor, ARSER integration
- Interactive parameter selection
- Designed for omics-scale data

### 4.2 Standalone Software

#### BRASS (Biological Rhythms Analysis Software Suite)
- Excel workbook (VBA macros)
- FFT-NLLS, mFourFit algorithms
- Imports data from LumiCycle, Metamorph, TopCount, NightOwl
- **Superseded by BioDare2** but still functional
- Download: millar.bio.ed.ac.uk

#### ClockLab (Actimetrics)
- Companion to LumiCycle hardware
- Actogram display, periodogram, phase analysis
- Commercial software (bundled with LumiCycle)
- Best for: activity rhythm analysis, running-wheel data

#### LumiCycle Analysis (Actimetrics)
- Dedicated to LumiCycle luminometer data
- Automatic baseline subtraction
- Damped cosine fitting for period, phase, amplitude
- Export to ClockLab or BioDare2 for further analysis

### 4.3 R Packages

| Package | Purpose | Install |
|---------|---------|---------|
| **CircaCompare** | Compare mesor/amplitude/phase between groups | `devtools::install_github("RWParsons/circacompare")` |
| **MetaCycle** | Meta-analysis of multiple period methods (ARSER, JTK, LS) | `install.packages("MetaCycle")` |
| **cosinor** | Single/population cosinor regression | `install.packages("cosinor")` |
| **cosinor2** | Extended cosinor with covariates | `install.packages("cosinor2")` |
| **cosinoRmixedeffects** | Mixed-effects cosinor models | via Bioconductor |
| **rain** | Rhythmicity detection (rising/falling flanks) | Bioconductor: `BiocManager::install("rain")` |
| **circular** | Circular statistics (Rayleigh, Watson-Williams, etc.) | `install.packages("circular")` |
| **DiscoRhythm** | Interactive rhythmicity workflow | `BiocManager::install("DiscoRhythm")` |
| **CircadiPy** | Python-like interface for circadian analysis | CRAN |
| **card** | Cosinor analysis of recurrent data | `install.packages("card")` |
| **psych** | Contains cosinor() function | `install.packages("psych")` |

**CircaCompare example**:
```r
library(circacompare)

# Compare rhythms between two conditions
result <- circacompare(
  x = df,
  col_time = "time",
  col_group = "group",
  col_outcome = "expression",
  period = 24  # or set period = NA to estimate
)

# Result contains:
# - Estimated mesor, amplitude, acrophase for each group
# - Differences and p-values for each parameter
# - Plots
summary(result)
```

**MetaCycle example**:
```r
library(MetaCycle)

# Run multiple period detection methods
meta2d(
  infile = "expression_data.csv",
  filestyle = "csv",
  outdir = "results/",
  timepoints = seq(0, 48, by=4),
  cycMethod = c("ARS", "JTK", "LS"),
  minper = 20,
  maxper = 28
)
# Produces integrated p-value from all methods
```

**JTK_CYCLE** (standalone R script):
```r
source("JTK_CYCLEv3.1.R")
# Designed for short, regularly-sampled omics data
# Tests each gene/feature for rhythmicity
# Returns: period, phase, amplitude, p-value (Bonferroni-corrected)
```

### 4.4 Python Libraries

| Library | Purpose | Install |
|---------|---------|---------|
| **pyBOAT** | Wavelet-based time-frequency analysis | `pip install pyboat` |
| **CosinorPy** | Cosinor regression, differential rhythmicity | `pip install CosinorPy` |
| **astropy.timeseries** | Lomb-Scargle periodogram | `pip install astropy` |
| **scipy.signal** | Hilbert transform, FFT, peak detection, filtering | `pip install scipy` |
| **pycircstat** / **pingouin** | Circular statistics | `pip install pingouin` |
| **pyActigraphy** | Actigraphy + cosinor (circadian rest-activity) | `pip install pyActigraphy` |
| **CircadiPy** (Python) | Chronobiology time series analysis | `pip install circadipy` |

**pyBOAT full workflow**:
```python
import pyboat
import numpy as np

# Load data
time = np.loadtxt("time.csv")      # hours
signal = np.loadtxt("signal.csv")
dt = time[1] - time[0]             # sampling interval in hours

# Step 1: Detrend with sinc filter (cutoff = 2 * max_period)
detrended = pyboat.sinc_detrend(signal, T_c=64)

# Step 2: Compute wavelet spectrum
periods = np.linspace(16, 32, 200)
modulus, transform = pyboat.compute_spectrum(detrended, dt, periods)

# Step 3: Extract ridge (dominant period over time)
ridge_ys = pyboat.get_maxRidge_ys(modulus)
ridge_periods = periods[ridge_ys]

# Step 4: Get amplitude and phase along ridge
ridge_results = pyboat.eval_ridge(ridge_ys, transform, signal, periods, dt)

print(f"Mean period: {np.mean(ridge_periods):.2f} h")
print(f"Period range: {np.min(ridge_periods):.2f} - {np.max(ridge_periods):.2f} h")
```

### 4.5 MATLAB Tools

| Toolbox | Purpose |
|---------|---------|
| **CircStat** | Circular statistics (Rayleigh, Watson-Williams, V-test, Kuiper) |
| **Wavelet Toolbox** | CWT, Morlet wavelet, time-frequency analysis |
| **Signal Processing Toolbox** | FFT, Hilbert, filtering, periodogram |

**MATLAB CircStat** (Berens 2009):
```matlab
% https://github.com/circstat/circstat-matlab
addpath('circstat-matlab');

% Convert hours to radians
phases = hours * 2 * pi / 24;

% Test for significant rhythmicity
[p, z] = circ_rtest(phases);
fprintf('Rayleigh test: z=%.3f, p=%.6f\n', z, p);

% Compare two groups
[p, table] = circ_wwtest(group1_phases, group2_phases);
fprintf('Watson-Williams: p=%.6f\n', p);

% Circular mean and confidence interval
mu = circ_mean(phases);
[mu_hat, ul, ll] = circ_mean(phases, [], [], 'ci', 0.95);
```

**MATLAB wavelet for circadian**:
```matlab
% Continuous wavelet transform
[wt, f] = cwt(signal, 'amor', 1/dt_hours);  % Morlet wavelet
periods_h = 1 ./ f;

% Plot scalogram
imagesc(time, periods_h, abs(wt));
ylim([16 32]);
ylabel('Period (hours)');
xlabel('Time (hours)');
colorbar;
title('Wavelet Power Spectrum');
```

### 4.6 ImageJ/Fiji for Time-Lapse

ImageJ is used primarily for **image preprocessing** before exporting time
series data to R/Python/BioDare2 for circadian analysis.

**Key ImageJ operations for circadian imaging**:

```javascript
// 1. Open time-lapse TIFF stack
open("/path/to/timelapse.tif");

// 2. Background subtraction (rolling ball or ROI-based)
run("Subtract Background...", "rolling=50 stack");

// 3. Registration (correct drift over multi-day recording)
run("StackReg", "transformation=Translation");
// Requires StackReg plugin (Thevenaz et al.)
// Alternative: TurboReg, Correct 3D Drift

// 4. Define ROIs for individual cells or regions
// Draw ROI, then: Analyze > Tools > ROI Manager > Add
// Repeat for each cell / SCN subregion / background

// 5. Multi-Measure: extract mean intensity per ROI per frame
roiManager("Multi Measure");
// Results table: columns = ROIs, rows = timepoints
// Save as CSV for analysis in R/Python

// 6. Bleach correction (if fluorescence)
run("Bleach Correction", "correction=[Exponential Fit]");

// 7. Z-project maximum intensity (if confocal z-stack at each timepoint)
run("Z Project...", "projection=[Max Intensity] all");

// 8. Create kymograph along line ROI through SCN
run("Multi Kymograph", "linewidth=5");
// Shows spatial oscillation pattern over time
```

**Fiji plugins relevant to circadian imaging**:
- **StackReg / TurboReg**: image registration (motion correction)
- **Correct 3D Drift**: for z-stack time series
- **Time Series Analyzer**: ROI intensity extraction over time
- **FAST (Fluorescence Analysis of SCN Tissue)**: specifically designed for
  SCN bioluminescence analysis (see imagej.net/plugins/fast)
- **MTrackJ / TrackMate**: for tracking moving cells over time
- **Bio-Formats**: importing proprietary formats (.nd2, .lif, .czi)

---

## 5. Key Papers

### 5.1 Foundational

- **Yoo et al. 2004** "PERIOD2::LUCIFERASE real-time reporting of circadian
  dynamics reveals persistent circadian oscillations in mouse peripheral
  tissues." *PNAS* 101:5339-46.
  - Created the PER2::LUC knockin mouse, enabling real-time circadian
    bioluminescence from any tissue. Showed peripheral tissues maintain
    autonomous oscillations ex vivo.

- **Welsh et al. 2004** "Bioluminescence imaging of individual fibroblasts
  reveals persistent, independently phased circadian rhythms of clock gene
  expression." *Current Biology* 14:2289-95.
  - First single-cell bioluminescence imaging of circadian rhythms.
    Demonstrated individual fibroblasts are autonomous oscillators but
    desynchronize without coupling (unlike SCN neurons).

### 5.2 SCN Network and Circuit

- **Hastings, Maywood & Brancaccio 2018** "Generation of circadian rhythms
  in the suprachiasmatic nucleus." *Nature Reviews Neuroscience* 19:453-469.
  - Comprehensive review of SCN molecular, cellular and circuit mechanisms.
    Covers intersectional genetics, multidimensional imaging, network theory.
    Highlights astrocyte contribution to timekeeping.

- **Brancaccio et al. 2017** "Astrocytes control circadian timekeeping in
  the suprachiasmatic nucleus via glutamatergic signaling." *Neuron* 93:1420-1435.
  - Demonstrated that SCN astrocytes are active circadian oscillators that
    regulate neuronal rhythms through extracellular glutamate and NMDA
    receptor NR2C subunits. Overturned the neuron-only model.

- **Brancaccio et al. 2019** "Cell-autonomous clock of astrocytes drives
  circadian behavior in mammals." *Science* 363:187-192.
  - Showed that restoring the clock in astrocytes alone (in an otherwise
    clockless mouse) is sufficient to drive circadian behaviour.

- **Brancaccio et al. 2020** "Dual-color single-cell imaging of the
  suprachiasmatic nucleus reveals a circadian role in network synchrony."
  *Neuron* 108:164-179.
  - Combined PER2::LUC bioluminescence with Cry1-GFP fluorescence for
    simultaneous dual-colour single-cell imaging. Revealed
    neuropeptide-specific contributions to network synchrony.

### 5.3 SCN Imaging Methods

- **Maywood et al. 2006** "A diversity of paracrine signals sustains
  molecular circadian cycling in suprachiasmatic nucleus circuits."
  *PNAS* 103:17060-5.
  - Established organotypic SCN slice culture with CCD imaging methods.

- **Abel et al. 2016** "Functional network inference of the suprachiasmatic
  nucleus." *PNAS* 113:4512-7.
  - Single-cell imaging + network analysis of SCN. Used Granger causality
    and transfer entropy to infer functional connectivity.

- **Enoki et al. 2012** "Topological specificity and hierarchical network
  of the circadian calcium rhythm in the suprachiasmatic nucleus."
  *PNAS* 109:21498-503.
  - GCaMP imaging of calcium rhythms in SCN with spatial analysis.

### 5.4 Analysis Methods

- **Zielinski et al. 2014** "Strengths and limitations of period estimation
  methods for circadian data." *PLOS ONE* 9:e96462.
  - Systematic comparison of period estimation methods (FFT-NLLS, MESA,
    Lomb-Scargle, autocorrelation). Essential reading for method selection.

- **Hughes et al. 2010** "JTK_CYCLE: an efficient nonparametric algorithm
  for detecting rhythmic components in genome-scale data sets."
  *Journal of Biological Rhythms* 25:372-80.
  - The JTK_CYCLE algorithm for rhythmicity detection.

- **Wu et al. 2016** "MetaCycle: an integrated R package to evaluate
  periodicity in large scale data." *Bioinformatics* 32:3351-3.
  - MetaCycle R package integrating ARSER, JTK, Lomb-Scargle.

- **Parsons et al. 2020** "CircaCompare: a method to estimate and
  statistically support differences in mesor, amplitude and phase, between
  circadian rhythms." *Bioinformatics* 36:1208-1212.
  - CircaCompare method and R package.

- **Moeneclaey et al. 2022** "pyBOAT: A Biological Oscillations Analysis
  Toolkit." *Methods in Molecular Biology* 2482.
  - pyBOAT wavelet toolkit for time-varying circadian analysis.

### 5.5 Synchrony and Network Analysis

- **Myung et al. 2018** "Measuring coupling strength in the SCN."
  *Journal of Biological Rhythms* 33:17-30.
  - Review of methods for quantifying coupling between SCN neurons.
    Covers Kuramoto modeling, phase response curves, cross-correlation.

- **VanderLeest et al. 2007** "Seasonal encoding by the circadian pacemaker
  of the SCN." *Current Biology* 17:468-73.
  - Phase coherence changes with photoperiod (long days = dispersed phases,
    short days = tight synchrony). Fundamental for understanding plasticity.

---

## 6. Common Pitfalls

### 6.1 Phototoxicity (Fluorescence Only)

- **Blue light (470 nm for GCaMP/GFP)** suppresses circadian rhythms in SCN
- **UV excitation** (DAPI, Hoechst) should NEVER be used during circadian recording
- **Mitigation**:
  - Use red-shifted reporters (R-GECO, mCherry) when possible
  - Minimize exposure time and intensity
  - Use spinning-disk confocal (less phototoxic than widefield or LSCM)
  - Nipkow disk systems (Yokogawa CSU) shown to work for circadian fluorescence
  - For bioluminescence: zero phototoxicity (no excitation light)

### 6.2 Medium Evaporation

- Multi-day recording at 37C in unsealed dishes leads to medium loss
- **Increased osmolarity** kills cells or alters rhythms
- **Mitigation**:
  - Seal dishes with vacuum grease + glass coverslip
  - Or use parafilm wrap
  - Use 40 mm dish within larger humidified chamber
  - Water reservoir in incubation chamber
  - Check medium volume and colour at end of recording

### 6.3 Vibration Artifacts

- Mechanical vibrations cause frame-to-frame jitter
- Especially problematic with 30-60 min exposures (single bump = bright streak)
- **Mitigation**:
  - Anti-vibration table
  - Avoid foot traffic near microscope during recording
  - Isolate from building HVAC vibrations
  - Use StackReg/registration to correct residual drift post-hoc

### 6.4 Aliasing / Nyquist

- **Nyquist theorem**: must sample at least 2x per cycle to detect oscillation
- For ~24h circadian rhythm: must sample at least every 12 hours
- **In practice**: sample every 10-60 min (well above Nyquist)
  - 30 min intervals are standard for CCD imaging
  - 10 min intervals for PMT recordings
- **Aliasing risk is LOW** for circadian but relevant for:
  - Ultradian rhythms (~4-8h) nested within circadian
  - If sampling interval approaches 4h, ultradian components alias

### 6.5 Damping

- **Peripheral tissue slices** (liver, lung, kidney): amplitude damps over
  3-7 days. This is REAL biology, not an artifact. Peripheral clocks lack
  the intercellular coupling that sustains SCN rhythms.
- **SCN slices**: should NOT damp significantly. If they do, culture conditions
  are suboptimal (medium, temperature, slice quality, contamination).
- **Analysis pitfall**: damping biases period estimates from FFT (spectral
  broadening). Use time-domain methods (peak-to-peak, wavelet) for damped signals.

### 6.6 Motion Artifacts in Slices

- Organotypic slices can shift slightly over days (settling, medium changes)
- Small ROIs (single cells) are affected more than large ROIs (whole SCN)
- **Mitigation**:
  - Image registration (StackReg, TurboReg)
  - Use relative intensity changes rather than absolute values
  - Recalculate ROI positions per frame if using tracking
  - Avoid medium changes during recording if possible

### 6.7 Luciferin Depletion

- Standard 0.1 mM luciferin in DMEM
- Signal declines ~10-20% per day due to luciferin consumption
- **Mitigation**:
  - Baseline detrend (see section 3.1.3)
  - Use 0.2 mM for longer recordings (>7 days)
  - Medium change at day 5-7 (but disrupts recording and may reset phase)
  - CycLuc1 (synthetic luciferin): brighter, more stable, but expensive

### 6.8 Temperature Sensitivity

- Circadian period is temperature-compensated (~24h at 25-37C)
- BUT: acute temperature changes reset phase
- Temperature fluctuations >1C add noise to bioluminescence
  (luciferase activity is temperature-dependent enzymatically)
- **Mitigation**: stable incubation to +/- 0.5C

### 6.9 Analysis Pitfalls

- **Detrending artifacts**: aggressive polynomial detrending can introduce
  spurious oscillations. Use sinc filter (pyBOAT) or exponential fit.
- **Short recordings**: <3 complete cycles make period estimation unreliable.
  Aim for 5+ cycles (5-7 days at 24h period).
- **Edge effects**: FFT and wavelet have edge artifacts. Trim first/last
  half-cycle from analysis.
- **Assuming stationarity**: period and amplitude change over time (especially
  in perturbation experiments). Use wavelet, not FFT, for non-stationary data.
- **Multiple comparisons**: when testing many ROIs/cells, correct p-values
  (Bonferroni, Benjamini-Hochberg FDR).

---

## 7. Statistical Analysis

### 7.1 Testing for Rhythmicity

**Is this time series rhythmic?**

| Method | Best for | R | Python |
|--------|----------|---|--------|
| Rayleigh test | Phase data (are phases clustered?) | `circular::rayleigh.test()` | See section 3.3.4 |
| JTK_CYCLE | Omics data (short, replicated) | `MetaCycle` or standalone | N/A (R only) |
| Cosinor F-test | Single time series | `cosinor::cosinor.lm()` | `CosinorPy` |
| Lomb-Scargle FAP | Unevenly sampled data | `lomb::lsp()` | `astropy.timeseries.LombScargle` |
| RAIN | Asymmetric waveforms (non-sinusoidal) | `rain` (Bioconductor) | N/A |

**Rhythmicity criteria** (commonly used):
- JTK_CYCLE p < 0.05 (Bonferroni-corrected for omics)
- Cosinor R^2 > 0.3 AND F-test p < 0.05
- Rayleigh test p < 0.05 (for phase clustering)
- Visual confirmation of at least 2 complete cycles

### 7.2 Comparing Periods Between Conditions

```r
# Method 1: t-test on periods (if normally distributed)
t.test(periods_control, periods_treatment)

# Method 2: Wilcoxon (non-parametric)
wilcox.test(periods_control, periods_treatment)

# Method 3: Mixed-effects model (for repeated measures / multiple cells per slice)
library(lme4)
model <- lmer(period ~ treatment + (1|slice/cell), data = df)
summary(model)
```

### 7.3 Comparing Phases Between Conditions

Phases are circular data -- use circular statistics, not linear tests.

```r
library(circular)

# Watson-Williams F-test (parametric, assumes von Mises distribution)
watson.williams.test(list(phases_control, phases_treatment))

# Mardia-Watson-Wheeler test (non-parametric)
# Tests whether two samples come from the same distribution
# Good when concentration parameters differ between groups

# Watson's U^2 test (non-parametric)
watson.two.test(phases_control, phases_treatment)
```

### 7.4 Comparing Amplitudes Between Conditions

```r
# CircaCompare: tests amplitude difference directly
library(circacompare)
result <- circacompare(df, col_time = "time", col_group = "group",
                        col_outcome = "expression", period = 24)
# Reports: amplitude_1, amplitude_2, difference, p-value

# Alternative: extract amplitudes per sample, then t-test
t.test(amplitudes_control, amplitudes_treatment)
```

### 7.5 Complete Statistical Workflow

```r
# 1. Detect rhythmicity in each group
library(MetaCycle)
meta2d(infile = "control.csv", outdir = "results/",
       timepoints = seq(0, 48, 4), cycMethod = c("JTK", "LS"))

# 2. Compare rhythm parameters between groups
library(circacompare)
result <- circacompare(
  x = combined_data,
  col_time = "time_hours",
  col_group = "condition",
  col_outcome = "bioluminescence",
  period = 24,
  alpha_threshold = 0.05
)

# 3. Report:
# - Is each group rhythmic? (JTK p-value)
# - Period difference? (t-test or Wilcoxon on estimated periods)
# - Phase difference? (Watson-Williams or CircaCompare acrophase comparison)
# - Amplitude difference? (CircaCompare amplitude comparison)
# - MESOR difference? (CircaCompare mesor comparison)

# 4. Effect size for circadian differences
# Cohen's d for period/amplitude comparisons
# Circular effect size for phase comparisons (mean resultant length of difference)
```

### 7.6 Sample Size Considerations

- **Biological replicates**: slices from different animals (not technical replicates)
- Typical N: 4-8 animals per group (n=1 slice per animal for SCN)
- For single-cell analysis: cells are technical replicates nested within slices
  - Use mixed-effects models: `period ~ treatment + (1|animal/slice/cell)`
- Minimum recording duration: 3 complete cycles (72h) for reliable period estimation,
  ideally 5+ cycles (120h+)

### 7.7 Reporting Standards

When publishing circadian imaging data, report:
1. **Period**: mean +/- SEM, method used (FFT, Lomb-Scargle, cosinor)
2. **Phase**: circular mean +/- circular SD, reference time (ZT0 = lights on)
3. **Amplitude**: peak-to-trough or cosinor amplitude, units (photons/min, AU, RLU)
4. **Rhythmicity test**: method and p-value (JTK, Rayleigh, cosinor F-test)
5. **N**: number of animals, slices, cells (specify hierarchy)
6. **Recording conditions**: temperature, medium, luciferin concentration,
   camera/PMT settings, exposure time, sampling interval
7. **Preprocessing**: detrending method, smoothing, bleach correction
8. **Software**: name and version of analysis tools

---

## 8. Quick-Reference: Complete Workflow

```
DAY 0: Slice preparation
  |
  v
START RECORDING (t=0)
  - PMT: 10-min bins, or CCD: 30-min frames
  - 36-37C, sealed dish, 0.1 mM luciferin
  |
  v
DAY 1-7: Record continuously
  - Monitor signal level
  - No disturbances
  |
  v
EXPORT DATA
  - PMT: CSV from LumiCycle/Kronos software
  - CCD: TIFF stack from acquisition software
  |
  v
ImageJ/Fiji PREPROCESSING (for CCD data)
  1. Open TIFF stack
  2. StackReg (registration/drift correction)
  3. Define ROIs (ROI Manager)
  4. Multi Measure -> export CSV
  |
  v
DETREND (Python/R)
  1. Background subtract
  2. Remove baseline (exponential or sinc filter)
  3. Optional: smooth (Savitzky-Golay)
  |
  v
PERIOD ESTIMATION
  - Quick: BioDare2 (upload CSV, get results)
  - Detailed: Lomb-Scargle (astropy) or pyBOAT (wavelet)
  - Publication: report multiple methods
  |
  v
PHASE ANALYSIS
  - Peak detection (simple, robust)
  - Hilbert transform (continuous phase)
  - Phase maps (spatial, pixel-wise)
  |
  v
SYNCHRONY (if multi-cell)
  - Kuramoto order parameter
  - Phase coherence matrix
  - Raster plot (visual)
  |
  v
STATISTICS
  - Rhythmicity: JTK_CYCLE or Rayleigh test
  - Period comparison: t-test or Wilcoxon
  - Phase comparison: Watson-Williams
  - Amplitude comparison: CircaCompare
  |
  v
FIGURES
  - Raw traces (detrended)
  - Periodogram / wavelet scalogram
  - Phase map of SCN
  - Polar plot (phase distribution)
  - Raster / heatmap
```

---

## 9. Glossary

| Term | Definition |
|------|-----------|
| **Acrophase** | Time of peak in fitted cosine (phase of rhythm) |
| **MESOR** | Midline Estimating Statistic Of Rhythm (rhythm-adjusted mean) |
| **CT** | Circadian Time (CT0 = subjective dawn in constant conditions) |
| **ZT** | Zeitgeber Time (ZT0 = lights on in LD cycle) |
| **Free-running** | Rhythm in constant conditions (no zeitgeber) |
| **Entrainment** | Synchronization of clock to external cue |
| **Phase Response Curve (PRC)** | How phase shifts depend on timing of stimulus |
| **Damping** | Progressive decrease in amplitude over cycles |
| **TTFL** | Transcription-Translation Feedback Loop (core clock mechanism) |
| **VIP** | Vasoactive Intestinal Peptide (key SCN coupling neuropeptide) |
| **AVP** | Arginine Vasopressin (SCN shell neuropeptide) |
| **RLU** | Relative Light Units (bioluminescence measurement unit) |
| **Kuramoto order parameter (R)** | Measure of phase synchrony (0=desync, 1=perfect sync) |
| **PLV** | Phase Locking Value (pairwise synchrony metric) |

---

*References and further reading: see Section 5 (Key Papers) and the software
documentation links throughout this document.*
