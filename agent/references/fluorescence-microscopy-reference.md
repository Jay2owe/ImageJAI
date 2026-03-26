# Fluorescence Microscopy Reference

Practical reference for fluorescence microscopy analysis in ImageJ/Fiji.

---

## 1. Fluorophore Quick Reference

| Fluorophore | Ex (nm) | Em (nm) | QY | Notes |
|---|---|---|---|---|
| **UV / Blue** | | | | |
| Hoechst 33342 | 350 | 461 | 0.38 | DNA, live cells, cell-permeable |
| DAPI | 358 | 461 | 0.58 | DNA, fixed only |
| **Green** | | | | |
| FITC | 494 | 519 | 0.95 | pH sensitive, bleaches fast |
| Alexa Fluor 488 | 495 | 519 | 0.92 | Gold standard green, photostable |
| EGFP | 488 | 507 | 0.60 | Genetically encoded |
| mNeonGreen | 506 | 517 | 0.80 | Brightest monomeric green FP |
| **Orange / Red** | | | | |
| Alexa Fluor 546 | 556 | 573 | 0.79 | Bright, photostable |
| tdTomato | 554 | 581 | 0.69 | Brightest red FP (tandem dimer) |
| mCherry | 587 | 610 | 0.22 | Most common red FP |
| mScarlet | 569 | 593 | 0.70 | Brightest monomeric red FP |
| Alexa Fluor 594 | 590 | 617 | 0.66 | Far-red secondary antibodies |
| **Far-red / NIR** | | | | |
| Cy5 | 650 | 670 | 0.27 | Low autofluorescence background |
| Alexa Fluor 647 | 650 | 671 | 0.33 | Best far-red, excellent for STORM |

**Brightness** = extinction coefficient x quantum yield. Relative to EGFP (1.0):
Alexa 488 ~2.4, tdTomato ~2.8, mCherry 0.47, Cy5 0.68.

### Choosing Fluorophores for Multi-Color

**Standard 4-color panel:**
1. DAPI/Hoechst -- nuclei (UV/blue)
2. Alexa Fluor 488 -- marker 1 (green)
3. Alexa Fluor 555/568 -- marker 2 (orange/red)
4. Alexa Fluor 647 -- marker 3 (far-red)

**Panel design rules:**
- Consider ~100 nm+ separation between emission peaks
- Alexa Fluor dyes over older dyes (FITC, TRITC) for photostability
- Weakest signal in far-red (least autofluorescence); strongest in green
- Use [FPbase Spectra Viewer](https://www.fpbase.org/spectra/) to check overlap

---

## 2. Microscopy Modalities

| Modality | Lateral res. | Axial res. | Speed | Best for |
|---|---|---|---|---|
| **Widefield** | ~200 nm | ~500-700 nm | Fast (camera) | Thin samples (<5 um), high-throughput |
| **Confocal (LSCM)** | ~180-250 nm | ~500-700 nm | Slow (~1 fps) | 3D of fixed tissue, colocalization |
| **Spinning disk** | Similar to LSCM | Similar to LSCM | Fast (up to 1000 fps) | Live cell 3D, fast time-lapse |
| **Airyscan** | ~120 nm | ~350 nm | Moderate | Better-than-confocal without full super-res |
| **Two-photon** | ~300-500 nm | ~700-1000 nm | Slow | Deep tissue (500-1000 um), in vivo |
| **Light sheet** | ~300-500 nm | ~1-3 um | Very fast | Cleared tissue, embryos, low phototoxicity |
| **TIRF** | ~200 nm | ~100-200 nm | Fast | Membrane events, single-molecule |
| **SIM** | ~100 nm | ~250 nm | Moderate | Live cell super-resolution |
| **STED** | 20-70 nm | -- | Moderate | Fixed, high-resolution (2-3 colors) |
| **PALM/STORM** | 10-30 nm | 50-70 nm | Slow (min-hr) | Ultrastructure, protein clustering |
| **Expansion (ExM)** | ~70 nm eff. | -- | Standard scope | Super-res without super-res microscope |

### Modality Decision Tree

```
Sample <5 um thick?
  YES + speed critical? → Widefield
  YES + need optical sectioning? → Confocal
Sample 5-100 um?
  Live? → Spinning disk
  Fixed? → Confocal
Sample >100 um?
  In vivo deep? → Two-photon
  Cleared tissue? → Light sheet
Need <200 nm resolution?
  Live? → SIM | Fixed ~50nm? → STED | Fixed ~20nm? → PALM/STORM
Membrane-proximal only? → TIRF
```

---

## 3. Sample Preparation

### Fixation

| Fixative | Protocol | Preserves | Destroys |
|---|---|---|---|
| 4% PFA | 10-15 min RT or 20 min 4C | Protein structure, most FP fluorescence | Some epitopes; quenches ~10-20% |
| MeOH/acetone | -20C, 5-10 min | Cytoskeletal/nuclear epitopes, phospho-epitopes | FP fluorescence, membranes |
| Glutaraldehyde | 0.1-2.5% | Ultrastructure (EM) | Most fluorescence, many epitopes |

Post-PFA: wash 3x PBS, quench with 50 mM NH4Cl 10 min.

### Permeabilization (after PFA only)

| Agent | Concentration | Time | Notes |
|---|---|---|---|
| Triton X-100 | 0.1-0.5% | 5-15 min | Standard, strong |
| Saponin | 0.1-0.5% | 10-30 min | Reversible, keep in all buffers |
| Digitonin | 25-50 ug/mL | 5 min | Very mild |

### Blocking & Antibody Staining

- Block: 1-5% BSA or 5-10% normal serum (from secondary Ab host), 30-60 min RT
- Primary Ab: typically 1:50-1:500, overnight 4C
- Secondary Ab: typically 1:200-1:1000 (Alexa Fluor: 1:500), 1 hr RT, in dark
- Wash 3x 5-10 min PBS between steps

### Mounting Media

| Medium | RI | Anti-fade | Notes |
|---|---|---|---|
| ProLong Gold | 1.46 | Yes | Standard for fixed cells |
| ProLong Glass | 1.52 | Yes | Best RI match for oil objectives |
| ProLong Diamond | 1.47 | Best | Best anti-fade |
| Vectashield | 1.45 | Yes | Non-hardening, requires sealing |
| Fluoromount-G | 1.40 | Moderate | Aqueous, simple |

RI mismatch (mounting medium vs immersion oil 1.515) causes spherical aberration.
ProLong Glass minimizes this for oil-immersion objectives.

### Live Cell Reagents

| Reagent | Target | Ex/Em (nm) |
|---|---|---|
| Calcein AM | Viability | 494/517 |
| MitoTracker Red | Mitochondria | 579/599 |
| LysoTracker Red | Lysosomes | 577/590 |
| CellMask Deep Red | Membrane | 649/666 |
| SiR-Tubulin | Microtubules | 652/674 |
| Fluo-4 AM | Calcium | 494/506 |
| Fura-2 AM | Calcium (ratio) | 340,380/510 |
| GCaMP variants | Calcium (genetic) | 488/510 |

**Phototoxicity warning:** MitoTracker Red is particularly phototoxic. Prefer
genetically encoded reporters for long time-lapse.

---

## 4. Acquisition Checklist

### Nyquist Sampling

```
pixel_size <= lambda_em / (2 * NA) / 2.3
z_step    <= 2 * lambda_em * n / (NA^2) / 2.3
```

| Objective | NA | GFP Nyquist pixel | GFP Nyquist z-step |
|---|---|---|---|
| 20x air | 0.75 | ~150 nm | ~800 nm |
| 40x oil | 1.30 | ~87 nm | ~267 nm |
| 60x oil | 1.40 | ~81 nm | ~230 nm |
| 100x oil | 1.45 | ~78 nm | ~215 nm |

For deconvolution: oversample 4x (half the pixel sizes above).

### Bit Depth

- Acquire at highest bit depth (typically 12 or 16-bit)
- Save as 16-bit TIFF -- never JPEG for quantitative data
- Use ~50-75% of dynamic range (leave headroom)

### Saturation Detection

```javascript
// HiLo LUT: saturated=red, zero=blue
run("HiLo");
```

**Avoidance:** Set exposure on brightest sample first. Never adjust exposure
between conditions in a quantitative experiment.

### Multi-Channel Acquisition

| Mode | Speed | Bleed-through | When |
|---|---|---|---|
| Simultaneous | Fastest | Highest | Well-separated spectra |
| Sequential (line) | Moderate | Reduced | Moderate overlap |
| Sequential (frame) | Slowest | Eliminated | Overlapping spectra |

Always image single-stained controls to quantify bleed-through.

### Time-Lapse Considerations

- Budget total light exposure across experiment
- Use hardware autofocus for Z-drift
- Expect 1-5% bleaching per frame (widefield); monitor with controls
- Environmental chamber for live cells (37C, 5% CO2)

---

## 5. Artifacts & Troubleshooting

| Artifact | Cause | Prevention / Fix |
|---|---|---|
| **Photobleaching** | Photochemical destruction of fluorophores | Anti-fade media, reduce exposure, use Alexa Fluor > FITC |
| **Autofluorescence** | PFA crosslinks, lipofuscin, NADH, FAD | TrueBlack/Sudan Black, far-red fluorophores, NH4Cl quench |
| **Bleed-through** | Spectral overlap between channels | Sequential acquisition, narrow filters, spectral unmixing |
| **Chromatic aberration** | Wavelength-dependent focus shift | Plan-apochromat objectives, TetraSpeck beads to measure |
| **Spherical aberration** | RI mismatch (mounting vs immersion) | Match RI (ProLong Glass for oil), use #1.5 coverslips |
| **Uneven illumination** | Vignetting from light source | Flat-field correction, rolling ball, BaSiC plugin |
| **Out-of-focus blur** | Widefield collects all Z planes | Confocal, deconvolution, thinner samples |

### Bleach Correction (ImageJ)
```javascript
run("Bleach Correction", "correction=[Simple Ratio] background=0");
run("Bleach Correction", "correction=[Exponential Fit]");
run("Bleach Correction", "correction=[Histogram Matching]");  // heterogeneous
```

### Flat-Field Correction
```javascript
// Corrected = (sample - dark) / (flatfield - dark) * mean(flatfield - dark)
imageCalculator("Subtract create 32-bit", "sample", "dark");
rename("sample_corr");
imageCalculator("Subtract create 32-bit", "ff", "dark");
rename("ff_corr");
selectWindow("ff_corr");
getRawStatistics(n, ffMean);
imageCalculator("Divide create 32-bit", "sample_corr", "ff_corr");
rename("result");
run("Multiply...", "value=" + ffMean);
```

---

## 6. Quantitative Fluorescence

### 6.1 CTCF (Corrected Total Cell Fluorescence)

```
CTCF = IntDen - (Area_cell x Mean_background)
```

**Protocol:**
1. Set Measurements: Area, Integrated Density, Mean Gray Value
2. Draw ROI around cell -> Measure (get IntDen, Area)
3. Draw ROI in nearby background -> Measure (get Mean)
4. CTCF = IntDen - (Area x Mean_background)
5. Average 3+ background regions for accuracy

```javascript
macro "CTCF Measurement" {
    run("Set Measurements...", "area mean integrated redirect=None decimal=3");
    nROIs = roiManager("count");
    roiManager("Select", nROIs - 1);  // last ROI = background
    run("Measure");
    bgMean = getResult("Mean", nResults - 1);
    run("Clear Results");
    for (i = 0; i < nROIs - 1; i++) {
        roiManager("Select", i);
        run("Measure");
        intDen = getResult("IntDen", nResults - 1);
        area = getResult("Area", nResults - 1);
        ctcf = intDen - (area * bgMean);
        setResult("CTCF", nResults - 1, ctcf);
    }
    updateResults();
}
```

### 6.2 FRAP

- **Mobile fraction:** Mf = (F_final - F_postbleach) / (F_prebleach - F_postbleach)
- **Half-time:** t1/2 = time to 50% recovery
- **Diffusion coefficient:** D = 0.224 * r^2 / t1/2

Protocol: 5-10 pre-bleach frames -> bleach ROI -> acquire until plateau.
Normalize: F_norm(t) = (F_bleach - F_bg) / (F_ref - F_bg), double-normalize to pre-bleach.
Fit single exponential. Plugins: FRAP Profiler, FRAPCalc.

### 6.3 FRET

```
E = 1 / (1 + (r/R0)^6)
E = 1 - (F_DA / F_D)           // intensity-based
E = 1 - (tau_DA / tau_D)       // lifetime-based
```

| Donor | Acceptor | R0 (nm) |
|---|---|---|
| CFP/mCerulean | YFP/mVenus | 4.9-5.2 |
| GFP | mCherry | 5.1 |
| Alexa 488 | Alexa 546 | 6.4 |
| mTurquoise2 | mVenus | 5.7 |

Methods: sensitized emission, acceptor photobleaching, FLIM-FRET (most quantitative).

### 6.4 Calcium Imaging

**Fura-2 (ratiometric):** R = F340/F380. [Ca2+] = Kd x (Sf2/Sb2) x (R-Rmin)/(Rmax-R).
```javascript
run("Split Channels");
imageCalculator("Divide create 32-bit", "F340", "F380");
run("Fire");
```

**GCaMP (intensiometric):** dF/F0 = (F(t) - F0) / F0
```javascript
run("Z Project...", "start=1 stop=10 projection=[Average Intensity]");
rename("F0");
imageCalculator("Subtract create 32-bit stack", "time_lapse", "F0");
imageCalculator("Divide create 32-bit stack", "deltaF", "F0");
```

### 6.5 Colocalization

- **PCC:** -1 to +1. Linear correlation between channels.
- **Manders M1/M2:** 0-1. Fraction of overlap (threshold-dependent, asymmetric).
- **Costes test:** p > 0.95 = significant colocalization.

```javascript
run("Coloc 2",
    "channel_1=C1 channel_2=C2 roi_or_mask=<None>
     threshold_regression=Costes manders'_correlation
     costes'_significance_test psf=3 costes_randomisations=100");
```

See `references/colocalization-reference.md` for full guide.

### 6.6 Projection Methods

| Projection | Use |
|---|---|
| **Sum Slices** | **Quantitative intensity** -- preserves total fluorescence |
| Max Intensity | Visualization only -- finding brightest features |
| Average | Noise reduction in uniform samples |
| Median | Noise reduction, rejecting outliers |

**Never use MIP for quantitative intensity measurements.**

### 6.7 SNR Requirements

| Task | Min SNR |
|---|---|
| Detection | 3-5 |
| Segmentation | 5-10 |
| Intensity (10% accuracy) | 10 |
| Intensity (5% accuracy) | 20 |
| Colocalization | 10-20 |
| FRET | 15-20 |

For shot-noise-dominated imaging: SNR ~ sqrt(signal photons).
To double SNR, need 4x more photons.

---

## 7. ImageJ/Fiji Operations

### Channel Operations
```javascript
run("Split Channels");                           // -> C1-image, C2-image, ...
run("Merge Channels...", "c1=red c2=green c3=blue create");  // c1=R,c2=G,c3=B,c5=cyan,c6=magenta
run("Duplicate...", "title=ch2 duplicate channels=2");       // extract single channel
Stack.setChannel(2);                             // switch to channel
```

### LUTs
```javascript
run("Blue");      // DAPI
run("Green");     // GFP/488
run("Magenta");   // Cy5/647 (colorblind-friendly over red)
run("HiLo");      // QC: saturated=red, zero=blue
run("mpl-viridis"); // publication, perceptually uniform

// Display range (does NOT modify data):
setMinAndMax(100, 3000);
// NEVER use run("Enhance Contrast...", "normalize") on quantitative data!
```

### Background Subtraction
```javascript
run("Subtract Background...", "rolling=50 sliding");        // rolling ball
run("Subtract Background...", "rolling=50 sliding stack");  // for stacks
// Rule of thumb: radius typically 2-3x largest object diameter
```

### Thresholding

| Method | Best for |
|---|---|
| Otsu | Bimodal histograms (clear foreground/background) |
| Triangle | Right-skewed (sparse bright objects on dark) |
| Li | Uniform objects, minimizes cross-entropy |
| RenyiEntropy | Dim objects on noisy background |
| Moments | Punctate fluorescence |

```javascript
// Standard workflow
run("Subtract Background...", "rolling=50 sliding");
run("Gaussian Blur...", "sigma=1");
setAutoThreshold("Triangle dark");  // "dark" = bright objects on dark bg
run("Convert to Mask");
run("Fill Holes");
run("Watershed");
run("Analyze Particles...", "size=50-Infinity circularity=0.3-1.0 show=Outlines display exclude add");

// Compare all methods:
run("Auto Threshold", "method=[Try all] white");
```

### Cell Segmentation
```javascript
// StarDist 2D (deep learning nuclei)
run("Command From Macro", "command=[de.csbdresden.stardist.StarDist2D],
     args=['input':'current_image', 'modelChoice':'Versatile (fluorescent nuclei)',
     'normalizeInput':'true', 'probThresh':'0.5', 'nmsThresh':'0.4',
     'outputType':'Both'], process=[false]");

// Classical: threshold + watershed
run("Subtract Background...", "rolling=50 sliding");
run("Gaussian Blur...", "sigma=2");
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Fill Holes");
run("Watershed");
run("Analyze Particles...", "size=100-5000 circularity=0.5-1.0 display exclude add");
```

### Measurement Setup
```javascript
run("Set Measurements...",
    "area mean standard modal min integrated median area_fraction
     display redirect=None decimal=3");

// Redirect: measure intensity on original while selecting on mask
run("Set Measurements...", "area mean integrated redirect=C2-original.tif decimal=3");
```

### Bio-Formats
```javascript
run("Bio-Formats Importer",
    "open=/path/to/file.nd2 autoscale color_mode=Composite
     view=Hyperstack stack_order=XYCZT");

// Supported: .nd2 (Nikon), .lif (Leica), .czi (Zeiss), .oif (Olympus),
// .dv (DeltaVision), .lsm (Zeiss), .ome.tiff
```

### Batch Processing Template
```javascript
input = "/path/to/input/";
output = "/path/to/output/";
list = getFileList(input);
setBatchMode(true);
for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif")) {
        open(input + list[i]);
        // --- processing pipeline ---
        run("Subtract Background...", "rolling=50 sliding");
        setAutoThreshold("Triangle dark");
        run("Convert to Mask");
        run("Analyze Particles...", "size=50-Infinity display");
        // ---
        saveAs("Tiff", output + getTitle());
        close("*");
    }
}
setBatchMode(false);
```

---

## 8. Decision Trees

### Background Subtraction
```
Uneven illumination (bright center, dark edges) → Rolling ball (radius=200+)
Autofluorescence (diffuse haze) → Rolling ball (radius=50-100)
Shot noise (random bright pixels) → Median filter (radius=1-2)
Structured background (striping) → FFT bandpass filter
Have flat-field reference → Flat-field division
```

### Fluorophore Selection
```
Fixed cells → Alexa Fluor series
  Blue: DAPI | Green: AF488 | Red: AF555/568 | Far-red: AF647
Live cells → Genetically encoded FPs
  Green: EGFP/mNeonGreen | Red: mScarlet/tdTomato | Far-red: mKate2/iRFP
Super-resolution →
  STORM: AF647 | PALM: mEos3.2 | STED: STAR 580/SiR | SIM: most standard
```

---

## 9. Gotchas

- **Never adjust exposure between conditions** in quantitative experiments
- **Sum Slices** for quantification, not Max Intensity projection
- **setMinAndMax()** for display; **never normalize** pixel values
- **Sequential acquisition** for colocalization to avoid bleed-through
- **#1.5 coverslips** (170 um) always for high-NA objectives
- **Match mounting medium RI** to immersion medium to avoid spherical aberration
- **Far-red channel** (AF647/Cy5) has lowest autofluorescence -- use for weakest signals
- **12-bit in 16-bit TIFF** is fine -- no information lost
- Fura-2 requires xenon lamp (flat spectrum); mercury gaps miss 380 nm excitation

---

## 10. Cross-References

- **Colocalization**: `references/colocalization-reference.md`
- **IF post-processing**: `references/if-postprocessing-reference.md`
- **3D spatial analysis**: `references/3d-spatial-reference.md`
- **Circadian imaging**: `references/circadian-imaging-reference.md`
- **Macro syntax**: `references/macro-reference.md`
- **Calcium imaging**: `references/calcium-imaging-reference.md`
