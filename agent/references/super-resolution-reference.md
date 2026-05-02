# Super-Resolution Microscopy Analysis Reference

Agent-oriented reference for super-resolution image analysis in Fiji/ImageJ.
Covers ThunderSTORM (SMLM), NanoJ-SRRF, SIM quality assessment, FRC resolution,
cluster analysis, nanoscale colocalization, expansion microscopy, and STED.

Sources: ThunderSTORM plugin docs (`zitmen.github.io/thunderstorm/`),
NanoJ-SRRF docs (`github.com/HenriquesLab/NanoJ-SRRF`), NanoJ-SQUIRREL,
fairSIM, SIMcheck, BIOP FRC, DeconvolutionLab2, GDSC SMLM. Use
`python probe_plugin.py "Plugin..."` to discover any installed plugin's
parameters at runtime.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Which SR method should I use?" | §2 method selection |
| "What's the diffraction limit / SMLM precision formula?" | §3 key equations |
| "How do I configure ThunderSTORM camera / run analysis?" | §4.2, §4.4 |
| "How do I filter / merge / drift-correct localizations?" | §4.5, §4.6, §4.8 |
| "What renderer should I use and what magnification?" | §4.9 |
| "How do I export / import a localization CSV?" | §4.10 |
| "How do I run SRRF / eSRRF on a time-lapse?" | §5 |
| "How do I assess SIM quality (MCR, artifacts)?" | §6 |
| "How do I measure resolution (FRC / decorrelation / SQUIRREL)?" | §7 |
| "How do I cluster localizations (DBSCAN, Ripley, Voronoi)?" | §8 |
| "How do I do nanoscale colocalization on SMLM?" | §9 |
| "How do I correct ExM measurements for expansion factor?" | §10 |
| "How do I deconvolve STED images?" | §11 |
| "What went wrong with my SMLM / SRRF reconstruction?" | §12 troubleshooting |
| "What must I report for publication?" | §13 |
| "Which fluorophore / buffer for dSTORM / PALM?" | §14 |
| "Which update site installs plugin X?" | §15 |
| "Common SR pitfalls?" | §16 gotchas |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '`<term>`' super-resolution-reference.md` to jump.

### A
`Alexa Fluor 647` §14 · `Alexa Fluor 532` §14 · `Alexa Fluor 488` §14 · `angles (SIM)` §6 · `artifacts (SIM)` §6 · `artifacts (STED)` §11 · `astigmatism (3D SMLM)` §4.4 · `Averaged shifted histograms` §4.9

### B
`Batch Processing (SMLM)` §4.11 · `bead calibration (astigmatism)` §4.4 · `bkgstd (CSV column)` §4.10 · `buffer (dSTORM MEA)` §14

### C
`Camera setup (ThunderSTORM)` §4.2 · `CBC (Coordinate-Based Colocalization)` §9 · `CF568` §14 · `CF680` §14 · `chromatic aberration` §16 · `Cluster Analysis` §8 · `colocalization (nanoscale)` §9 · `connectivity (detector)` §4.3 · `correct_vibration (SRRF)` §5 · `Cross-correlation drift` §4.8 · `Cross-pair correlation` §9 · `CSV columns (ThunderSTORM)` §4.10

### D
`DBSCAN` §8.1 · `Decorrelation Analysis` §7.2 · `DeconvolutionLab2` §11, §15 · `Density filter` §4.7 · `Dendra2` §14 · `Detectors (ThunderSTORM)` §4.3 · `Difference of Gaussians filter` §4.3 · `diffraction limit` §3 · `dist (merging)` §4.6 · `DNA-PAINT` §2 · `Drift correction` §4.8 · `dSTORM` §2, §14 · `Duty Cycle` §14

### E
`Elliptical Gaussian (3D)` §4.3, §4.4 · `epsilon (DBSCAN)` §8.1 · `eSRRF` §5 · `Estimate Resolution (Decorrelation)` §7.2 · `Expansion factor` §10 · `Expansion Microscopy (ExM)` §2, §10 · `Export results (ThunderSTORM)` §4.10

### F
`fairSIM` §6, §15 · `Feature variation (SIMcheck)` §6 · `Fiducial markers (drift)` §4.8 · `Filter (ThunderSTORM)` §4.5 · `Filtering Localizations` §4.5 · `fitradius` §4.3 · `Fitting Methods` §4.3 · `Fluorophore Quick Reference` §14 · `formula (expression filter)` §4.5 · `Fourier Ring Correlation (FRC)` §7.1 · `frames_per_timepoint (SRRF)` §5 · `FRC threshold (1/7)` §3, §7.1 · `FRC (BIOP plugin)` §7.1, §15 · `FWHM` §3, §11

### G
`gainem` §4.2 · `GDSC SMLM (PeakFit)` §15 · `g(r) (cross-pair)` §9

### H
`Histogram renderer` §4.9

### I
`Image Filters (ThunderSTORM pre-processing)` §4.3 · `Import results` §4.10 · `Installation (ThunderSTORM)` §4.1 · `intensity` §4.5, §4.10 · `Integrated Gaussian` §4.3 · `isemgain` §4.2

### L
`L(r) (Ripley's L)` §8.2 · `laser power` §13 · `Local maximum detector` §4.3 · `Localization precision` §3, §13

### M
`magnification (SMLM rendering)` §4.9 · `MCR (Modulation Contrast Ratio)` §6 · `MEA buffer` §14 · `mEos3.2` §14 · `Median filter (ThunderSTORM)` §4.3 · `Merging` §4.6 · `Method Selection` §2 · `mfaenabled (multi-emitter)` §4.4 · `minPts (DBSCAN)` §8.1 · `Monte Carlo envelope` §8.2 · `Moving average filter` §4.3 · `Multi-emitter fitting analysis` §4.4

### N
`NanoJ-Core` §5 · `NanoJ-SQUIRREL` §7.3, §15 · `NanoJ-SRRF` §5, §15 · `Nearest-Neighbor Distance` §9 · `nmax (multi-emitter)` §4.4 · `Non-maximum suppression detector` §4.3 · `Normalized Gaussian renderer` §4.9 · `Nyquist (SMLM)` §3, §16

### O
`offframes (merging)` §4.6 · `offset (camera)` §4.2 · `ontimeratio (fiducial drift)` §4.8

### P
`PA-GFP / PA-mCherry` §14 · `PALM` §2, §14 · `pattern spacing (SIM)` §6 · `phases (SIM)` §6 · `Phasor localization` §4.3 · `photons2adu` §4.2 · `Photons/event` §14 · `photobleaching` §12 · `Picasso` §2 · `Plugin Installation Summary` §15 · `probe_plugin` §1 header · `Properties... (ExM calibration)` §10 · `PSF Fitting Models` §4.3 · `pvalue (multi-emitter)` §4.4

### R
`radiality_magnification` §5 · `raw SIM data` §6 · `renderer` §4.9 · `Rendering (ThunderSTORM)` §4.9 · `Resolution Estimation` §7 · `Resolution Estimation Decision` §7.3 · `Richardson-Lucy` §11 · `ring (SRRF)` §5 · `Ripley's K/L` §8.2 · `RSE map` §7.3 · `RSP (SQUIRREL)` §7.3 · `Run analysis (ThunderSTORM)` §4.4

### S
`scale (Wavelet)` §4.3 · `sensitivity (eSRRF)` §5 · `sigma (PSF width)` §4.3, §4.5 · `SIM` §2, §6 · `SIMcheck` §6, §15 · `Single-Molecule Localization Microscopy (SMLM)` §2, §4 · `SNR` §11 · `SQUIRREL` §7.3, §15 · `SR-Tesseler` §8.3 · `SRRF (NanoJ)` §5 · `SRRF Analysis` §5 · `STED` §2, §11 · `std(Wave.F1)` §4.3 · `steps (cross-corr drift)` §4.8

### T
`Temporal Color Code` §4.9 · `threed (ThunderSTORM 3D)` §4.4 · `ThunderSTORM` §4, §15 · `TRA (Temporal Radiality Average)` §5 · `Troubleshooting` §12 · `TRM (Temporal Radiality Maximum)` §5 · `TRPPM (pairwise product mean)` §5

### U
`uncertainty` §4.5, §4.10

### V
`Visualization (ThunderSTORM)` §4.9 · `Voronoi Tessellation` §8.3

### W
`Wavelet B-Spline filter` §4.3 · `Weighted Least Squares` §4.3 · `widefield reference (SQUIRREL)` §7.3 · `Wiener parameter` §6

### Y
`Yen / other thresholds` — see `imagej-gui-reference.md` §5.2

### Z
`zrange (3D rendering)` §4.9 · `z-stack vs time series (Properties)` §12

---

## §2 Method Selection

| Method | Resolution | Live-cell? | Hardware | Key Plugin |
|--------|-----------|-----------|----------|------------|
| **dSTORM** | 10-30 nm | No | Standard + buffer | ThunderSTORM |
| **PALM** | 20-50 nm | Slow | Standard + PA-FPs | ThunderSTORM |
| **SIM** | 100-130 nm | Yes (fast) | SIM system | fairSIM |
| **STED** | 30-70 nm | Yes | STED system | Deconvolution |
| **SRRF** | 50-150 nm | Yes | Standard widefield | NanoJ-SRRF |
| **ExM** | 50-70 nm (4x) | No | Standard confocal | Standard tools |
| **DNA-PAINT** | 5-10 nm | No | TIRF + oligonucleotides | ThunderSTORM/Picasso |

### When to Choose

| Scenario | Consider |
|----------|---------|
| Max resolution, fixed cells | dSTORM with Alexa 647 |
| Live-cell, moderate resolution | SIM |
| No special hardware | SRRF (needs time-lapse, 100+ frames) |
| Thick tissue, standard equipment | Expansion Microscopy |
| Retrospective analysis of archived time-lapse | SRRF |
| Protein counting | PALM (1 FP = 1 protein) |
| Multiplexed nanoscale distances | DNA-PAINT |

---

## §3 Key Equations

```
Diffraction limit:     d = lambda / (2 * NA)           ~200 nm lateral
SMLM precision:        sigma_loc ~ s / sqrt(N)         s=PSF sigma, N=photons
SMLM Nyquist:          d_Nyquist = 2 / sqrt(rho)       rho=localizations/nm^2
SIM resolution:        d = lambda / (4 * NA)            ~100 nm
STED resolution:       d = lambda / (2*NA*sqrt(1+I/Is)) 30-70 nm practical
FRC threshold:         1/7 ~ 0.143
```

**Resolution is limited by BOTH precision AND density.** A dataset with 10 nm
precision but sparse localizations has worse effective resolution than 30 nm
precision with dense sampling. Report both localization precision AND FRC resolution.

---

## §4 ThunderSTORM (SMLM)

### §4.1 Installation

```
Help > Update... > Manage Update Sites > check "ThunderSTORM" > Apply > Restart
```

### §4.2 Camera Setup (configure before analysis)

```bash
python ij.py macro '
run("Camera setup", "offset=100 isemgain=true photons2adu=3.6 gainem=100 pixelsize=160.0");
'
```

| Parameter | How to Find |
|-----------|-------------|
| `offset` | Dark frame mean (typically 100-500 ADU) |
| `isemgain` | Camera spec: true for EMCCD, false for sCMOS |
| `photons2adu` | Camera calibration sheet (typically 1-10) |
| `gainem` | Acquisition settings (typically 100-300) |
| `pixelsize` | Microscope calibration (nm) |

Check metadata first: `python ij.py metadata`

### §4.3 Detection & Fitting Options

**Image Filters (pre-processing):**

| Filter | When to Use |
|--------|------------|
| **Wavelet B-Spline** (default) | General purpose, robust. `scale=2.0 order=3` |
| **Difference of Gaussians** | Known PSF size. `sigma1=1-2, sigma2=3-5` |
| **Median** | Heavy background. `radius=3-9 px` |
| **Moving average** | Slowly varying background. `window=10-50 frames` |

**Detectors:**

| Detector | When to Use |
|----------|------------|
| **Local maximum** (default) | General purpose. `connectivity=8-neighbourhood` |
| **Non-maximum suppression** | Dense data. Slower but handles overlap better |

**Threshold:** `std(Wave.F1)` is a good starting point. Use `1.5*std(Wave.F1)` for stricter filtering.

**PSF Fitting Models:**

| Model | When to Use |
|-------|------------|
| **Integrated Gaussian** (default) | Most robust for standard 2D SMLM |
| **Elliptical Gaussian** | 3D-STORM with cylindrical lens |
| **Phasor localization** | Ultra-fast, no fitting, lower precision |

**Fitting Methods:** Weighted Least Squares is typically the best balance of speed and robustness. Maximum Likelihood is theoretically optimal for very low photon counts but slower.

**Key parameters:**
- `sigma`: Initial PSF width estimate (pixels). Measure from isolated molecule, or PSF_FWHM / (2.35 * pixel_size). Typically ~1.6.
- `fitradius`: Fitting window (pixels). Typically ~2-3x sigma.

### §4.4 Complete Analysis Command

```bash
python ij.py macro '
run("Camera setup", "offset=100 isemgain=true photons2adu=3.6 gainem=100 pixelsize=160.0");
run("Run analysis",
    "filter=[Wavelet filter (B-Spline)] scale=2.0 order=3 " +
    "detector=[Local maximum] connectivity=8-neighbourhood threshold=std(Wave.F1) " +
    "estimator=[PSF: Integrated Gaussian] sigma=1.6 fitradius=3 method=[Weighted Least squares] full_image_fitting=false " +
    "mfaenabled=false " +
    "renderer=[Averaged shifted histograms] magnification=10 colorizez=false threed=false shifts=2 repaint=50");
'
```

**For 3D with astigmatism:** calibrate first with bead z-stack, then use `estimator=[PSF: Elliptical Gaussian (3D astigmatism)]` with `calibrationpath=`, `colorizez=true threed=true`.

**For dense data (overlapping PSFs):** add `mfaenabled=true mfamethod=[Multi-emitter fitting analysis] nmax=5 pvalue=1.0E-6`.

### §4.5 Filtering Localizations

```bash
# Range filters
python ij.py macro '
run("Show results table");
run("Filter", "sigma=50-350 intensity=500-inf uncertainty=0-50");
'

# Expression-based filter
python ij.py macro '
run("Filter", "formula=[sigma > 50 & sigma < 300 & intensity > 500 & uncertainty < 50]");
'
```

**How to choose filter ranges:**
- `sigma`: Remove values far from expected PSF width. Outliers typically indicate out-of-focus or multi-molecule events.
- `intensity`: Set lower bound to exclude dim false positives. Check the intensity histogram to find the noise floor.
- `uncertainty`: Typically keep < 50 nm. Tighter = fewer but better localizations.

### §4.6 Merging Duplicate Localizations

Molecules blinking across consecutive frames create duplicates:

```bash
python ij.py macro '
run("Merging", "zcoordweight=0.1 offframes=1 dist=20 framespermolecule=0");
'
```

- `dist`: Starting point ~2x localization precision
- `offframes`: 1-2 for fast blinking, 0 for strict merging

### §4.7 Density-Based Filtering

```bash
python ij.py macro '
run("Density filter", "radius=50 neighbors=5");
'
```

### §4.8 Drift Correction

**Cross-correlation (general purpose, no fiducials needed):**
```bash
python ij.py macro '
run("Drift correction", "method=[Cross correlation] magnification=5 save=false steps=10 showcorrelations=false");
'
```
- `steps`: More bins = finer correction, but each bin needs enough localizations. 10-20 is typical.

**Fiducial markers (most accurate, requires beads in sample):**
```bash
python ij.py macro '
run("Drift correction", "method=[Fiducial markers] ontimeratio=0.9 distance=40 showcorrelations=false");
'
```

### §4.9 Rendering

| Method | Quality | Speed | When to Use |
|--------|---------|-------|-------------|
| **Averaged shifted histograms** | Good | Medium | Default choice |
| **Normalized Gaussian** | Best | Slow | Publication figures |
| **Histogram** | Low | Fast | Quick preview |

```bash
python ij.py macro '
run("Visualization",
    "renderer=[Averaged shifted histograms] magnification=10 colorizez=false shifts=2 repaint=50");
'
```

**Choosing magnification:** `rendering_pixel = camera_pixel / magnification`. Nyquist requires pixel <= resolution/2. For 30 nm target resolution with 160 nm camera pixels, magnification >= 11.

**Color-coded rendering:** Add `colorize=true lut=[Temporal Color Code]` to colour by frame (reveals drift). For 3D: `colorizez=true zrange=-500:500`.

### §4.10 Export & Import

```bash
# Export CSV
python ij.py macro '
run("Export results",
    "filepath=/path/to/locs.csv fileformat=[CSV (comma separated)] " +
    "id=true frame=true sigma=true intensity=true offset=true bkgstd=true uncertainty=true");
'

# Import CSV
python ij.py macro '
run("Import results",
    "filepath=/path/to/locs.csv fileformat=[CSV (comma separated)] startingframe=1 append=false");
'

# Save protocol (all settings)
python ij.py macro '
run("Export results", "filepath=/path/to/protocol.txt fileformat=[Protocol]");
'
```

**CSV columns:** id, frame, x [nm], y [nm], z [nm] (3D), sigma [nm], intensity [photon], offset [photon], bkgstd [photon], uncertainty [nm].

### §4.11 Batch Processing

```bash
python ij.py macro '
input_dir = "/path/to/storm_data/";
output_dir = "/path/to/results/";
list = getFileList(input_dir);
for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif")) {
        open(input_dir + list[i]);
        run("Camera setup", "offset=100 isemgain=true photons2adu=3.6 gainem=100 pixelsize=160.0");
        run("Run analysis",
            "filter=[Wavelet filter (B-Spline)] scale=2.0 order=3 " +
            "detector=[Local maximum] connectivity=8-neighbourhood threshold=std(Wave.F1) " +
            "estimator=[PSF: Integrated Gaussian] sigma=1.6 fitradius=3 method=[Weighted Least squares] full_image_fitting=false " +
            "mfaenabled=false renderer=[No Renderer]");
        run("Filter", "sigma=50-350 intensity=500-inf uncertainty=0-50");
        run("Merging", "offframes=1 dist=20");
        run("Drift correction", "method=[Cross correlation] magnification=5 save=false steps=10");
        run("Export results",
            "filepath=" + output_dir + replace(list[i], ".tif", "_locs.csv") + " " +
            "fileformat=[CSV (comma separated)] id=true frame=true sigma=true intensity=true uncertainty=true");
        run("Visualization", "renderer=[Averaged shifted histograms] magnification=10 shifts=2");
        saveAs("Tiff", output_dir + replace(list[i], ".tif", "_SR.tif"));
        close("*");
    }
}
'
```

---

## §5 NanoJ-SRRF

SRRF extracts super-resolution from standard widefield/confocal time-lapses via temporal analysis of intensity fluctuations. Works with any fluorophore, no special hardware.

### Installation

```
Help > Update... > Manage Update Sites > check "NanoJ-SRRF" > Apply > Restart
```
Also: NanoJ-Core (dependency), NanoJ-SQUIRREL (quality assessment)

### SRRF Parameters

```bash
python ij.py macro '
run("SRRF Analysis",
    "ring=0.5 radiality_magnification=4 axes=6 " +
    "frames_per_timepoint=100 do_convergence_map correct_vibration");
'
```

| Parameter | Guidance |
|-----------|---------|
| `ring` | Radiality ring radius (px). 0.3-1.0. Smaller = higher resolution but noisier |
| `radiality_magnification` | Output magnification. 2-8. |
| `axes` | Gradient axes. 4-8. More = more accurate but slower |
| `frames_per_timepoint` | Frames combined per SR time point. 50-200. More = better SNR, less temporal resolution. 0 = all frames |
| `correct_vibration` | Enable unless already drift-corrected |

### eSRRF (Enhanced)

```bash
python ij.py macro '
run("eSRRF", "magnification=4 fwhm=2.0 sensitivity=1 nframes=100 reconstruction=[Temporal Radiality Average (TRA)]");
'
```

Temporal analysis modes:
- **TRA** (average): Good baseline
- **TRM** (maximum): Highest contrast
- **TRPPM** (pairwise product mean): Best artifact rejection

### Parameter Selection by Frame Count

| Frames | Suggested Settings |
|--------|-------------------|
| < 50 | Poor quality expected. `ring=0.5, mag=2, axes=4` |
| 50-200 | Standard. `ring=0.5, mag=4, axes=6` |
| 200-1000 | Higher quality. `ring=0.5, mag=5, axes=8`, can split timepoints |
| > 1000 | Best quality. `ring=0.3, mag=5-8, axes=8`, consider eSRRF |

### When SRRF is NOT Appropriate

- Single static images (no temporal fluctuations)
- Quantitative intensity measurements (SRRF is qualitative)
- Resolution below 50 nm needed
- Sample moves/flows during acquisition

**Critical:** SRRF can produce artifacts resembling real structures. ALWAYS validate with NanoJ-SQUIRREL and compare with the diffraction-limited image.

---

## §6 SIM Quality Assessment

### fairSIM (Reconstruction)

```
Help > Update... > Manage Update Sites > check "fairSIM" > Apply > Restart
```

fairSIM is primarily GUI-based. Key parameters to match to microscope setup:
- Number of angles (typically 3), phases (3 for 2D-SIM, 5 for 3D-SIM)
- Pattern spacing (from OTF or manufacturer specs)
- Wiener parameter: 0.001-0.1 (higher = smoother but lower resolution)

### SIMcheck (Quality Assessment)

```
Help > Update... > Manage Update Sites > check "SIMcheck" > Apply > Restart
```

```bash
python ij.py macro '
open("/path/to/raw_sim_data.tif");
run("Raw Data: Modulation Contrast");
run("Raw Data: Fourier Projection Plots");
run("Reconstructed: Fourier Plots");
run("Reconstructed: Modulation Contrast Map");
'
```

| Metric | Interpretation |
|--------|---------------|
| **MCR < 0.5** | Sample likely unsuitable for SIM (too thick/scattering) |
| **MCR 0.5-1.0** | Marginal. Increase Wiener parameter, report MCR |
| **MCR > 1.0** | Good quality, proceed |
| **Feature variation > 0.3** | Motion during acquisition, consider re-acquiring |

### SIM Artifacts

| Artifact | Cause | Fix |
|----------|-------|-----|
| Honeycomb pattern | Wrong pattern parameters | Re-estimate from raw data |
| Ghosting | Sample motion between phases | Shorter exposure |
| Haloing | Wiener too low | Increase Wiener parameter |
| Striping | Uneven illumination | Check laser alignment |

---

## §7 Resolution Estimation

### §7.1 Fourier Ring Correlation (FRC) — Gold Standard

Split data into two independent halves, render each, measure correlation decay.

```bash
# Using ThunderSTORM + BIOP FRC plugin
python ij.py macro '
run("Filter", "formula=[mod(frame,2)==0]");
run("Visualization", "renderer=[Averaged shifted histograms] magnification=10 shifts=2");
rename("even_frames");
run("Undo");
run("Filter", "formula=[mod(frame,2)==1]");
run("Visualization", "renderer=[Averaged shifted histograms] magnification=10 shifts=2");
rename("odd_frames");
run("FRC", "image_1=even_frames image_2=odd_frames");
'
```

**Python FRC** (for more control):

```python
import numpy as np
from numpy.fft import fft2, fftshift

def compute_frc(image1, image2, pixel_size_nm):
    """Compute FRC curve. Returns (frequencies, frc_values, resolution_nm)."""
    assert image1.shape == image2.shape
    ny, nx = image1.shape
    f1, f2 = fftshift(fft2(image1)), fftshift(fft2(image2))
    cy, cx = ny // 2, nx // 2
    y, x = np.ogrid[-cy:ny-cy, -cx:nx-cx]
    r = np.sqrt(x**2 + y**2).astype(int)
    max_r = min(cy, cx)

    frc_values, frequencies = np.zeros(max_r), np.zeros(max_r)
    for ri in range(1, max_r):
        ring = (r == ri)
        num = np.abs(np.sum(f1[ring] * np.conj(f2[ring])))
        den = np.sqrt(np.sum(np.abs(f1[ring])**2) * np.sum(np.abs(f2[ring])**2))
        if den > 0:
            frc_values[ri] = num / den
        frequencies[ri] = ri / (nx * pixel_size_nm)

    # Resolution at 1/7 threshold
    for ri in range(1, max_r):
        if frc_values[ri] < 1.0/7.0 and frequencies[ri] > 0:
            return frequencies[1:max_r], frc_values[1:max_r], 1.0/frequencies[ri]
    return frequencies[1:max_r], frc_values[1:max_r], None
```

### §7.2 Decorrelation Analysis (Single Image)

Estimates resolution from ONE image (no splitting needed):

```bash
python ij.py macro '
run("Estimate Resolution (Decorrelation Analysis)");
'
```

Generally agrees with FRC within ~20%. More conservative on noisy data.

### §7.3 NanoJ-SQUIRREL (Quality Assessment)

Compares SR image against diffraction-limited reference. Essential for SRRF validation.

```
Help > Update... > Manage Update Sites > check "NanoJ-SQUIRREL" > Apply > Restart
```

```bash
python ij.py macro '
open("/path/to/widefield_reference.tif"); rename("reference");
open("/path/to/sr_image.tif"); rename("sr");
run("SQUIRREL Analysis", "reference=reference super_resolution=sr");
'
```

- **RSP > 0.9**: Good reconstruction
- **RSE map**: Hot spots indicate artifacts
- Always run on SRRF results

### Resolution Estimation Decision

| Data Type | Method |
|-----------|--------|
| SMLM localization table, can split | FRC (gold standard) |
| Any SR image + widefield reference | SQUIRREL |
| Single rendered/reconstructed image | Decorrelation analysis |
| Raw SIM data | SIMcheck metrics |
| STED + bead images | FWHM from beads |
| SRRF | SQUIRREL (essential) + decorrelation |

---

## §8 Cluster Analysis of Localizations

SMLM data is fundamentally a coordinate list. Cluster analysis reveals molecular organization.

### §8.1 DBSCAN

Groups localizations by local density without pre-specifying cluster count.

**Parameter guidance:**
- `epsilon`: Starting point ~2-3x localization precision (e.g., 40-60 nm for 20 nm precision). Too small splits clusters; too large merges them.
- `minPts`: 3-10 typical. Lower = more permissive. Rule of thumb: >= dimensionality + 1.
- **Systematic approach:** Try epsilon = 20, 40, 60, 80, 100 nm; plot cluster count vs epsilon; look for a plateau.

```python
import numpy as np
import pandas as pd
from sklearn.cluster import DBSCAN

def run_dbscan(csv_path, epsilon_nm=50, min_pts=5):
    """Run DBSCAN on ThunderSTORM CSV. Returns cluster stats DataFrame."""
    df = pd.read_csv(csv_path)
    x_col = [c for c in df.columns if 'x' in c.lower() and 'nm' in c.lower()]
    y_col = [c for c in df.columns if 'y' in c.lower() and 'nm' in c.lower()]
    if not x_col: x_col, y_col = ['x [nm]'], ['y [nm]']
    coords = df[[x_col[0], y_col[0]]].values

    labels = DBSCAN(eps=epsilon_nm, min_samples=min_pts).fit_predict(coords)
    n_clusters = len(set(labels)) - (1 if -1 in labels else 0)
    n_noise = (labels == -1).sum()
    print(f"{n_clusters} clusters, {n_noise} noise ({100*n_noise/len(labels):.1f}%)")

    stats = []
    for cid in range(n_clusters):
        mask = labels == cid
        cc = coords[mask]
        centroid = cc.mean(axis=0)
        dists = np.sqrt(np.sum((cc - centroid)**2, axis=1))
        stats.append({
            'cluster_id': cid, 'n_locs': mask.sum(),
            'centroid_x': centroid[0], 'centroid_y': centroid[1],
            'rms_radius_nm': np.sqrt(np.mean(dists**2)),
        })
    return pd.DataFrame(stats)
```

### §8.2 Ripley's K/L Function

Quantifies clustering/dispersion at multiple spatial scales vs complete spatial randomness.

```
L(r) = sqrt(K(r)/pi) - r
  L(r) > 0: Clustered at scale r
  L(r) = 0: Random
  L(r) < 0: Dispersed
```

Compare against Monte Carlo envelope (99+ simulations of random points) for significance.

```python
import numpy as np

def ripleys_l(coords, radii, area):
    """Compute Ripley's L(r)-r. O(n^2) — subsample if >5000 points."""
    n = len(coords)
    k_values = np.zeros(len(radii))
    for idx, r in enumerate(radii):
        count = 0
        for i in range(n):
            dists = np.sqrt(np.sum((coords - coords[i])**2, axis=1))
            count += np.sum((dists > 0) & (dists < r))
        k_values[idx] = area * count / (n * (n - 1))
    return np.sqrt(k_values / np.pi) - radii
```

### §8.3 Voronoi Tessellation

Each localization gets a polygon; area is inversely proportional to local density. Threshold on cell area (e.g., < mean area) to segment clusters. Implemented in SR-Tesseler (Levet et al. 2015) and scipy.spatial.Voronoi.

---

## §9 Nanoscale Colocalization

**Never use pixel-based methods (PCC, Manders, Coloc 2) on rendered SMLM images.** Results change with rendering pixel size and Gaussian spread creates false overlap.

### Methods

| Method | When to Use | Key Property |
|--------|------------|-------------|
| **Nearest-neighbor distance** | Quick assessment | Simple; no scale info |
| **CBC (Coordinate-Based Coloc)** | Per-molecule score | Range: -1 (anti) to +1 (colocalized) |
| **Cross-pair correlation** | Multi-scale relationship | g(r)>1 = co-clustered at distance r |
| **DBSCAN overlap** | Cluster-level | Intuitive for cluster biology |

### ThunderSTORM CBC

```bash
python ij.py macro '
run("Import results", "filepath=/path/to/ch1.csv fileformat=[CSV (comma separated)] startingframe=1 append=false");
run("CBC", "radius=500 steps=20");
'
```

### Nearest-Neighbor Distance (Python)

```python
import numpy as np
import pandas as pd
from scipy.spatial import cKDTree

def nn_colocalization(ch1_coords, ch2_coords, max_dist_nm=200):
    """NN distance analysis between two channels with Monte Carlo significance."""
    tree2 = cKDTree(ch2_coords)
    dists, _ = tree2.query(ch1_coords)
    coloc = (dists < max_dist_nm).sum()
    print(f"Within {max_dist_nm} nm: {coloc}/{len(ch1_coords)} ({100*coloc/len(ch1_coords):.1f}%)")

    # Monte Carlo: randomize ch2 positions, repeat 100x
    xmin, ymin = ch1_coords.min(axis=0)
    xmax, ymax = ch1_coords.max(axis=0)
    random_means = []
    for _ in range(100):
        rand = np.column_stack([
            np.random.uniform(xmin, xmax, len(ch2_coords)),
            np.random.uniform(ymin, ymax, len(ch2_coords))])
        random_means.append(np.mean(cKDTree(rand).query(ch1_coords)[0]))

    obs = np.mean(dists)
    if obs < np.percentile(random_means, 2.5):
        print(f"Significantly closer than random (p<0.05) — COLOCALIZED")
    return dists
```

---

## §10 Expansion Microscopy

Physical sample expansion (~4x standard, up to ~20x iterative) achieves effective SR on a standard microscope.

### Key Operations

**Correct measurements:** Divide all measurements by the measured expansion factor.

```bash
python ij.py macro '
expansion_factor = 4.1;
getPixelSize(unit, pw, ph);
run("Properties...", "pixel_width=" + (pw/expansion_factor) + " pixel_height=" + (ph/expansion_factor) + " unit=" + unit);
'
```

**Measure expansion factor:** Compare pre- vs post-expansion distances of known structures or fiducial beads. Check isotropy (X and Y factors within 5%).

| Combination | Effective Resolution |
|------------|---------------------|
| ExM + confocal | ~65-70 nm |
| ExM + SIM | ~30-35 nm |
| ExM + STED | ~15-20 nm |

---

## §11 STED Image Analysis

STED produces direct SR images — no localization fitting or reconstruction needed. Typically benefits from deconvolution (Richardson-Lucy) since the STED PSF is well-defined.

```bash
python ij.py macro '
run("DeconvolutionLab2 Run", "algorithm=[Richardson-Lucy] image=sted psf=psf iterations=20");
'
```

Measure resolution via line profiles across sub-resolution structures (FWHM) or sub-diffraction beads (20-40 nm).

### STED Artifacts

| Artifact | Cause | Fix |
|----------|-------|-----|
| Donut/ring shapes | STED beam misalignment | Realign depletion laser |
| Bleaching shadows | Excessive depletion power | Reduce power, scan faster |
| Resolution varies across FOV | Non-uniform depletion donut | Analyse central FOV only |
| Low SNR | Fundamental (smaller volume) | Average frames, deconvolve |

---

## §12 Troubleshooting

### SMLM

| Problem | Diagnosis | Fix |
|---------|-----------|-----|
| Overlapping PSFs | Raw frames show non-isolated spots | Increase frame rate, enable multi-emitter fitting |
| Sparse localizations | Very few locs/frame | More frames, check labeling density |
| Blurred/doubled features | Uncorrected drift (render colour-coded by time) | Apply drift correction |
| Unrealistic intensities | Wrong camera parameters | Verify offset, gain, pixel size |
| Signal decreases over time | Photobleaching | Adjust laser, fresh buffer |

### SRRF

| Problem | Fix |
|---------|-----|
| Structures in SRRF not in widefield | Validate with SQUIRREL, increase frames_per_timepoint |
| Ring artifacts around bright features | Reduce ring radius |
| SRRF looks like widefield | Check for intensity fluctuations; static samples give no benefit |

### General

| Problem | Fix |
|---------|-----|
| Z-stack treated as time series | `run("Properties...", "slices=1 frames=N");` |
| Rendered image too noisy | Increase rendering pixel size or collect more localizations |
| Resolution overclaimed | Use FRC, not just localization precision |

---

## §13 Publication & Reporting

### Minimum Reporting Checklist

1. Microscope: objective, NA, camera, pixel size, laser lines/power
2. Acquisition: exposure, frame count, frame rate
3. Sample: fixation, fluorophores, buffer composition
4. Software and version (e.g., ThunderSTORM v1.3)
5. All analysis parameters: detection, fitting, filtering, drift correction
6. **FRC resolution** (not just localization precision)
7. Localization count (raw and after filtering)
8. Localization precision (mean and distribution)
9. Rendering method and pixel size

**Method-specific additions:**
- dSTORM: buffer composition, laser power at sample, activation scheme
- SIM: SIMcheck MCR, Wiener parameter, angles/phases
- SRRF: frame count, all SRRF parameters, SQUIRREL RSP score

### Metadata Template

```
# Software: ThunderSTORM v1.3
# Camera: [model], pixel [X] nm, gain [Y], offset [Z]
# Objective: [mag]x [NA]
# Laser: [wavelength] nm, [power] mW at sample
# Exposure: [X] ms, [N] frames
# Fluorophore: [name], conjugated to [target]
# Buffer: [composition]
# Detection: Wavelet B-Spline (scale 2.0), Local max (8-conn, threshold std(Wave.F1))
# Fitting: Integrated Gaussian, WLS, sigma 1.6 px, fit radius 3 px
# Filtering: sigma [range] nm, intensity [range] photons, uncertainty [range] nm
# Merging: dist [X] nm, gap [Y] frames
# Drift correction: [method], [params]
# Localizations: [N_raw] raw, [N_filtered] filtered, [N_merged] merged
# Precision: [mean] +/- [std] nm
# FRC resolution: [X] nm
# Rendering: ASH, mag [X], pixel [Y] nm
```

### Common Reviewer Concerns

| Concern | Address With |
|---------|-------------|
| Resolution not validated | FRC curve in supplement |
| Artifacts possible | SQUIRREL error map + widefield comparison |
| Insufficient data | Localization count + Nyquist calculation |
| Drift not corrected | Drift trajectory + temporal colour rendering |
| Cannot compare conditions | Identical acquisition and analysis parameters |

---

## §14 Fluorophore Quick Reference

### dSTORM

| Fluorophore | Photons/event | Duty Cycle | Notes |
|-------------|--------------|------------|-------|
| **Alexa Fluor 647** | 5000-6000 | 0.001 | Gold standard |
| CF680 | 4000+ | 0.001 | AF647 alternative |
| Alexa Fluor 532 | 2000-3000 | 0.01 | 2-colour with AF647 |
| Alexa Fluor 488 | 1000-2000 | 0.05 | Higher duty cycle, fewer photons |

**Multi-colour:** AF647 (far red) + CF568 or AF532 (green). AF488 is the weakest dSTORM performer.

### PALM

Photoactivatable: PA-GFP, PA-mCherry. Photoconvertible: mEos3.2, Dendra2.

### SIM / SRRF

Any standard fluorophore works. Brighter is better for SIM.

### dSTORM Buffer (Standard MEA)

```
50 mM Tris-HCl pH 8.0, 10 mM NaCl, 10% glucose
100 mM MEA (fresh), 0.5 mg/mL glucose oxidase, 40 ug/mL catalase
pH 7.5-8.5. Seal chamber. Use within 2-4 hours.
```

---

## §15 Plugin Installation Summary

| Plugin | Update Site | Purpose |
|--------|------------|---------|
| ThunderSTORM | ThunderSTORM | SMLM localization & analysis |
| NanoJ-SRRF | NanoJ-SRRF | SR from fluctuations |
| NanoJ-SQUIRREL | NanoJ-SQUIRREL | SR quality assessment |
| fairSIM | fairSIM | SIM reconstruction |
| SIMcheck | SIMcheck | SIM quality assessment |
| FRC | BIOP | Fourier Ring Correlation |
| DeconvolutionLab2 | DeconvolutionLab2 | Deconvolution (for STED) |
| GDSC SMLM | GDSC SMLM | Alternative SMLM (PeakFit) |

Install: `Help > Update... > Manage Update Sites > check "[name]" > Apply > Restart Fiji`

---

## §16 Gotchas

- **Never enhance contrast** on rendered SR images for quantitative work. Use `setMinAndMax()` for display only.
- **Save both rendered image AND localization CSV** — the CSV is the actual data.
- **Chromatic aberration correction is critical** for multi-colour SMLM — even 20-50 nm shift matters. Calibrate with multi-colour fiducial beads.
- **SRRF on static samples gives no benefit** — SRRF requires intensity fluctuations.
- **dSTORM buffer degrades in 2-4 hours** — prepare fresh.
- **Camera parameters must be correct** — wrong offset/gain produces unrealistic intensities and wrong precision estimates.
- **Check Nyquist** — localization density must support the claimed resolution.
- **FRC resolution is the standard** — localization precision alone overestimates resolution.
