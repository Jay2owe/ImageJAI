# Image Deconvolution Reference

Practical guide to PSF measurement and generation, deconvolution algorithms,
validation, and workflows in Fiji/ImageJ. Lab-agnostic — generic fluorescence
microscopy framing throughout. Cross-links to `if-postprocessing-reference.md`
(filter/threshold pipelines), `super-resolution-reference.md` (FRC, NanoJ-SQUIRREL,
SR modalities), `clij2-gpu-reference.md` (GPU RL details), and
`ai-image-analysis-reference.md` (CARE/CSBDeep, Noise2Void).

Sources:
- DeconvolutionLab2: Sage et al. 2017 *Methods* 115:28–41
  (`bigwww.epfl.ch/deconvolution/deconvolutionlab2/`); paper PDF
  `bigwww.epfl.ch/deconvolution/deconvolutionlab2/sage1701p.pdf`.
- PSF Generator: Kirshner, Aguet, Sage, Unser 2013 *J. Microsc.* 249:13–25
  (`bigwww.epfl.ch/algorithms/psfgenerator/`).
- Diffraction PSF 3D & Iterative Deconvolve 3D (Bob Dougherty/OptiNav,
  `imagej.net/plugins/diffraction-psf-3d`,
  `imagej.net/plugins/iterative-deconvolve-3d`).
- CLIJ2 GPU RL (`clij.github.io/clij2-docs/`).
- Deconwolf: Wernersson et al. 2024 *Nat. Methods* 21:1245–1254
  (`github.com/elgw/deconwolf`).
- CARE / Content-Aware Restoration: Weigert et al. 2018 *Nat. Methods* 15:1090–1097
  (`csbdeep.bioimagecomputing.com`).
- Noise2Void: Krull et al. 2019 *CVPR* (`github.com/juglab/n2v`); Probabilistic
  Noise2Void: Krull et al. 2020 *Front. Comput. Sci.*
- Richardson–Lucy + TV: Dey, Blanc-Féraud, Zerubia et al. 2006
  *Microsc. Res. Tech.* 69:260–266.
- Huygens (commercial): SVI (`svi.nl/Huygens-Deconvolution`).
- Microvolution: `microvolution.com`.
- AutoQuant: Media Cybernetics.
- FlowDec: `github.com/hammerlab/flowdec`.
- RedLionfish: Wellcome Open Res. 2024 9:296 (`github.com/rosalindfranklininstitute/RedLionfish`).
- pyDecon: `github.com/david-hoffman/pyDecon`.
- MetroloJ-QC: Royer et al. 2022 *J. Cell Biol.* 221:e202107093.
- PSFj: Theer, Mongis, Knop 2014 *Nat. Methods* 11:981–982.
- NanoJ-SQUIRREL: Culley et al. 2018 *Nat. Methods* 15:263–266.
- Nature Portfolio image integrity guidelines
  (`nature.com/nature-portfolio/editorial-policies/image-integrity`).

Use `python probe_plugin.py "Plugin..."` to discover any installed plugin's
parameters at runtime.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy/Jython/JS in Fiji's JVM.
`python ij.py metadata` — Bio-Formats NA, wavelength, pixel size.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Which algorithm for widefield?" | §4.4, §8.1 |
| "Which algorithm for confocal / spinning disk / light sheet?" | §4.4, §8.1 |
| "How do I generate a PSF?" | §3.3, §3.4, §3.5 |
| "What refractive index for water / oil / glycerol / mounting medium?" | §3.1 |
| "What emission wavelength for GFP / DAPI / Cy5?" | §3.1 |
| "Why is my deconvolution ringing / noisy / diverging?" | §9, §2.1 |
| "How many iterations should I use?" | §7.1 |
| "How do I pick lambda / regularization?" | §7.2 |
| "When should I NOT deconvolve?" | §2.1 |
| "What does the `-pad` flag do?" | §4.2, §7.3 |
| "How do I run DeconvolutionLab2 from a macro?" | §4.1 |
| "How do I use the GPU (CLIJ2)?" | §6 |
| "How much RAM / VRAM do I need?" | §10 |
| "How do I handle multiple channels?" | §3.6, §7.5 |
| "Is my image suitable for deconvolution?" | §2.1, §13 |
| "How do I check if the deconvolution worked?" | §8, §2.2 |
| "How do I extract PSF parameters from metadata?" | §12.1 |
| "When should I suggest deconvolution to the user?" | §12.3 |
| "What's the gotcha with `width,` and `height,`?" | §3.3 |
| "Why is the title with spaces failing?" | §9 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '`<term>`' deconvolution-reference.md` to jump.

### A
`Airy radius` §3.3 · `Algorithm selection` §4.4, §8.1 · `Anti-ringing (Iterative Deconvolve 3D)` §5 · `Apodization (-apo)` §4.2 · `AI_Exports` house rule

### B
`BVLS (Stark-Parker)` §4.3 · `Bit depth (32-bit)` §7.4 · `Born-Wolf (scalar PSF)` §3.1, §3.3 · `Bead-measured PSF` §3.5

### C
`CARE / CSBDeep` §11 · `CLARITY-cleared RI` §3.1 · `CLIJ2 GPU deconvolution` §6 · `Confocal (modality)` §2.1, §4.4, §7.1 · `Constraint (-constraint)` §4.2 · `Convergence (-epsilon, terminate)` §4.2, §5 · `Cover glass RI` §3.1 · `Cy3 / Cy5` §3.1

### D
`DAPI / Hoechst` §3.1 · `DeconvolutionLab2 (DL2)` §4 · `Diffraction PSF 3D` §3.3 · `Dimensions (PSF)` §3.3 · `Divergence detection` §5, §9

### E
`Edge artifacts` §9 · `Emission wavelength` §3.1, §3.3 · `EPFL PSF Generator` §3.4 · `Epsilon (-epsilon)` §4.2 · `EXPANDED (padding)` §4.2, §7.3 · `EXTENDED (padding)` §4.2, §7.3

### F
`FFT implementation (-fft)` §4.2 · `FISTA (Fast Shrinkage-Threshold)` §4.3 · `Fixed tissue RI` §3.1 · `Flowdec` §11 · `Fluorophore emission table` §3.1 · `FWHM (line profile)` §8.1

### G
`Gamma (step size)` §4.3, §7.2 · `Gibson-Lanni (stratified PSF)` §3.1, §3.4 · `Glycerol RI` §3.1 · `GPU (CLIJ2)` §6 · `GFP` §3.1

### H
`Hamming / Hann / Tukey (apodization)` §4.2 · `Huygens` §11

### I
`ICTM (Constrained Tikhonov-Miller)` §4.3 · `Immersion RI` §3.1, §3.3 · `Input (-image)` §4.2 · `Intensity conservation` §8.1, §9 · `ISTA (Shrinkage-Threshold)` §4.3 · `Iteration count` §7.1 · `Iterative Deconvolve 3D` §5

### J
`Java FFT (default)` §4.2 · `JPEG (do not deconvolve)` §2.1, §9

### L
`Lambda (regularization)` §4.3, §7.2 · `Landweber (LW)` §4.3 · `Light sheet (modality)` §2.1, §4.4, §7.1 · `Low pass filter (Iterative Deconvolve 3D)` §5

### M
`Measured PSF (from beads)` §3.1, §3.5 · `Memory estimation` §10 · `MIP (-out mip)` §4.2 · `Modality table` §2.1, §4.4, §7.1 · `Mounting medium RI` §3.1 · `Multi-channel workflow` §3.6, §7.5

### N
`NA (numerical aperture)` §3.3 · `Naive Inverse (NIF)` §4.3 · `Negative values` §2.2, §8.1 · `NNLS (Non-Neg Least Squares)` §4.3 · `Nonnegativity (constraint)` §4.2, §13 · `Normalization (-norm, sum=1)` §4.2, §3.3 · `Nyquist sampling` §3.2

### O
`Oil immersion RI` §3.1 · `Ortho (-out ortho)` §4.2 · `Out-of-focus haze` §12.3 · `Output (-out)` §4.2 · `Over-iteration` §2.2, §7.1

### P
`Padding (-pad)` §4.2, §7.3 · `PBS RI` §3.1 · `Point scan confocal` §2.1 · `ProLong Glass RI` §3.1 · `PSF Generator (EPFL)` §3.4 · `PSF model choice` §3.1 · `PSF dimensions` §3.3

### Q
`Quality checklist` §8.1, §13

### R
`RAM estimation` §10 · `RedLionfish` §11 · `Refractive index table` §3.1 · `Regularization (lambda)` §4.3, §7.2 · `Regularized Inverse (RIF)` §4.3 · `Resolution (FWHM check)` §8.1 · `Richards-Wolf (vectorial PSF)` §3.1 · `Richardson-Lucy (RL)` §4.3 · `Ringing` §2.2, §7.1, §9 · `RLTV (RL + Total Variation)` §4.3 · `Rolling ball (background for bead PSF)` §3.5

### S
`Saturation (do not deconvolve)` §2.1, §12.2 · `Scikit-image (Python)` §11 · `Series (-out series)` §4.2 · `Shortening to power-of-2` §3.3, §7.3, §9 · `SNR threshold` §2.1 · `Spinning disk (modality)` §2.1, §4.4, §7.1 · `Stark-Parker (BVLS)` §4.3 · `Stats (-stats)` §4.2 · `Super-resolution (modality)` §2.1 · `Sum of Pixels = 1 (normalization)` §3.3, §13

### T
`Tikhonov Regularized Inverse (TRIF)` §4.3 · `Tikhonov-Miller (TM)` §4.3 · `TIF (saveAs Tiff)` §3.5 · `Title with spaces (gotcha)` §9 · `Troubleshooting checklist` §13 · `Tukey (apodization)` §4.2 · `Two-photon (modality)` §2.1, §4.4, §7.1

### U
`Undersampled (pixel > Nyquist)` §2.1, §3.2

### V
`Van Cittert (VC)` §4.3 · `Vectashield RI` §3.1 · `Vectorial PSF (Richards-Wolf)` §3.1 · `VRAM (GPU memory)` §6, §10

### W
`Water immersion RI` §3.1 · `Wavelength` §3.1, §3.3 · `Wavelet scales (FISTA/ISTA)` §4.3 · `Wiener filter (Iterative Deconvolve 3D)` §5 · `Widefield (modality)` §2.1, §4.4, §7.1

### X
`XY pixel size (image=)` §3.3

### Z
`Z-step (slice=)` §3.3 · `Z-stack requirement` §12.2

---

## Quick Start

```javascript
// 1. Open z-stack and get properties
open("/path/to/your/zstack.tif");
original = getTitle();
getPixelSize(unit, pixelWidth, pixelHeight);
Stack.getDimensions(w, h, channels, slices, frames);

// 2. Generate PSF — adjust NA, wavelength, RI, pixel/slice spacing to YOUR microscope
run("Diffraction PSF 3D",
    "type=32-bit" +
    " index=1.518" +           // immersion RI: 1.0=air, 1.33=water, 1.518=oil
    " numerical=1.4" +         // objective NA
    " wavelength=510" +        // emission wavelength in nm
    " longitudinal=0" +
    " image=10" +              // XY pixel size in nm (match your image)
    " slice=300" +             // z-step in nm (match your image)
    " width,=128 height,=128" +
    " depth,=" + slices +
    " normalization=[Sum of Pixels=1] title=PSF");

// 3. Deconvolve
selectWindow(original);
run("DeconvolutionLab2 Run",
    " -image platform " + original +
    " -psf platform PSF" +
    " -algorithm RL 15" +
    " -out stack deconvolved");
```

Agent workflow:
```bash
python ij.py state
python ij.py macro 'open("/path/to/zstack.tif");'
python ij.py info           # get pixel size, z-step
python ij.py metadata       # get NA, wavelength, immersion from Bio-Formats
python ij.py histogram      # check for saturation
# Generate PSF with parameters from metadata, then deconvolve (see above)
python ij.py capture after_deconv
```

---

## §2 When to Deconvolve (and When Not To)

### §2.1 Suitability by Modality

| Modality | Benefit | Consider? |
|----------|---------|-----------|
| Widefield | High (removes out-of-focus blur) | Strongly yes |
| Spinning disk | Moderate-High | Yes |
| Confocal (point scan) | Moderate | Yes for quantitation |
| Light sheet | Moderate | Yes |
| Two-photon | Low-Moderate | Sometimes |
| Super-resolution | Low | Usually no |

**Do NOT deconvolve when:**
- Image is JPEG-compressed (artifacts amplified)
- Image has saturated pixels (violates linear model)
- PSF parameters are unknown and cannot be estimated
- Image is severely undersampled (pixel size > Nyquist)
- SNR < 3 (noise dominates)
- Non-linear contrast adjustments were applied (gamma, histogram eq, normalize)
- Single thin plane only (minimal benefit)

### §2.2 Warning Signs After Deconvolution

| Sign | Meaning |
|------|---------|
| Ringing (bright/dark halos) | Over-iteration or wrong PSF |
| Checkerboard patterns | Noise amplification in frequency domain |
| Negative values | Unstable algorithm or too many iterations |
| "Crisper" noise | Noise sharpened with signal (over-iteration) |
| Total intensity change | PSF normalization issue |

---

## §3 PSF Generation

### §3.1 Choosing a PSF Model

| Model | Accounts For | Use When |
|-------|-------------|----------|
| Born-Wolf (scalar) | Uniform RI | Oil objective at coverslip, RI well-matched |
| Gibson-Lanni (stratified) | RI mismatch across 3 layers | Most practical situations (recommended) |
| Richards-Wolf (vectorial) | Polarization | High-NA (>1.2), usually overkill |
| Measured (from beads) | Actual aberrations | Maximum accuracy needed |

#### Refractive Index Table

| Medium | RI | Notes |
|--------|-----|-------|
| Air | 1.000 | Air objectives |
| Water / PBS | 1.333-1.335 | Water immersion, live imaging |
| Glycerol | 1.473 | Glycerol immersion |
| Oil (Type F) | 1.518 | Standard immersion oil |
| Cover glass | 1.522 | Standard #1.5 |
| Mounting medium (aqueous) | 1.34-1.40 | ProLong Glass = 1.52 |
| Mounting medium (hardening) | 1.49-1.52 | Vectashield = 1.45 |
| Fixed tissue | 1.38-1.45 | Varies by tissue |
| CLARITY-cleared | 1.45-1.47 | Matched to solution |

RI mismatch tolerance: roughly +/-0.02 before noticeable degradation. Beyond +/-0.05 the PSF is significantly distorted.

#### Common Fluorophore Emission Wavelengths

| Fluorophore | Emission (nm) | Fluorophore | Emission (nm) |
|-------------|--------------|-------------|--------------|
| DAPI / Hoechst | 461 | Alexa 594 / mCherry | 617 / 610 |
| Alexa 488 / GFP | 519 / 509 | Alexa 647 / Cy5 | 668 / 670 |
| Alexa 546 / Cy3 | 573 / 570 | Alexa 750 | 775 |

### §3.2 Nyquist Sampling Requirements

For deconvolution to work, the image should be adequately sampled:
- Lateral: pixel size <= lambda / (4 * NA)
- Axial: z-step <= lambda / (2 * n * (1 - cos(arcsin(NA/n))))

If undersampled, deconvolution may introduce aliasing artifacts.

### §3.3 Diffraction PSF 3D (Built into Fiji)

Generates a Born-Wolf scalar diffraction PSF. Always available, full macro support.

```javascript
run("Diffraction PSF 3D",
    "type=32-bit" +
    " index=1.518" +        // immersion RI
    " numerical=1.4" +      // objective NA
    " wavelength=509" +     // emission wavelength nm
    " longitudinal=0" +     // spherical aberration um (0 if well-matched)
    " image=65" +           // XY pixel size nm — MUST match your image
    " slice=300" +          // z-step nm — MUST match your image
    " width,=128" +         // PSF width px (note trailing comma in key)
    " height,=128" +        // PSF height px (note trailing comma in key)
    " depth,=64" +          // number of slices — match your z-stack
    " normalization=[Sum of Pixels=1]" +
    " title=PSF");
```

**Key gotcha**: The `width,` and `height,` keys include a trailing comma — this is an ImageJ quirk.

**How to choose PSF dimensions:**
- XY size: typically 64-256 px (capture full diffraction pattern, at least 5x Airy radius)
- Z depth: match or exceed z-stack depth
- Pixel/voxel size: MUST match specimen image exactly
- Power-of-2 dimensions (64, 128, 256) are fastest for FFT

### §3.4 PSF Generator Plugin (EPFL)

Supports Gibson-Lanni and Richards-Wolf models. Install via Help > Update > Manage Update Sites > "PSF Generator". Uses custom GUI (no macro recording) — generate interactively and save as TIFF for reuse in automated workflows.

### §3.5 Measured PSF from Beads

For maximum accuracy, image sub-diffraction beads (100-200 nm) under specimen conditions:

```javascript
// Crop around isolated bead, subtract background, normalize sum to 1
open("/path/to/bead_zstack.tif");
makeRectangle(x - 32, y - 32, 64, 64);
run("Crop");
run("Subtract Background...", "rolling=20 stack");
run("32-bit");
getRawStatistics(nPixels, mean);
run("Divide...", "value=" + (nPixels * mean) + " stack");
saveAs("Tiff", "/path/to/measured_PSF.tif");
```

### §3.6 Multi-Channel PSFs

Each channel needs its own PSF (different emission wavelength = different PSF shape). Generate one per channel with the appropriate wavelength, then deconvolve channels independently.

---

## §4 DeconvolutionLab2

The most complete deconvolution plugin for Fiji. 14 algorithms, flexible I/O, padding, apodization.

**Installation**: Typically pre-installed. If not: Help > Update > Manage Update Sites > "DeconvolutionLab2".

### §4.1 Complete Macro Syntax

```javascript
run("DeconvolutionLab2 Run",
    " -image platform ImageTitle" +    // REQUIRED: input image
    " -psf platform PSFTitle" +        // REQUIRED: PSF image
    " -algorithm RL 15" +              // REQUIRED: algorithm + params
    " -out stack OutputName" +         // output specification
    " -constraint nonnegativity" +     // value constraint
    " -norm 1" +                       // PSF normalization (sum=1)
    " -pad EXTENDED EXTENDED 0 0" +    // padding strategy
    " -monitor no" +                   // progress monitoring
    " -stats no");                     // statistics display
```

### §4.2 Flag Reference

| Flag | Syntax | Options / Notes |
|------|--------|----------------|
| `-image` | `-image platform Title` or `-image file /path` | Input image (required) |
| `-psf` | `-psf platform Title` or `-psf file /path` | PSF image (required) |
| `-algorithm` | `-algorithm SHORT [params...]` | See algorithm table (required) |
| `-out` | `-out VIEW [@freq] [name] [type] [dynamic]` | VIEW: `stack`, `mip`, `ortho`, `series`, `figure`. type: `float`/`short`/`byte`. dynamic: `intact`/`rescaled`. Multiple `-out` flags allowed |
| `-pad` | `-pad PADXY PADZ EXTXY EXTZ` | `NO`, `EXTENDED` (recommended), `EXPANDED` (power-of-2), `CUSTOM` |
| `-apo` | `-apo APOXY APOZ` | `NO`, `TUKEY`, `HAMMING`, `HANN` |
| `-norm` | `-norm VALUE` | `1` recommended (PSF sum=1). `0` = no normalization |
| `-constraint` | `-constraint TYPE` | `no`, `nonnegativity` (recommended for fluorescence), `clipped` |
| `-stats` | `-stats OPTION` | `no`, `show`, `save`, `showsave` |
| `-monitor` | `-monitor OPTION` | `no`/`0`, `console`/`1`, `table`/`2`, `3` (both) |
| `-path` | `-path /dir` | Output directory. `current`, `home`, `desktop` |
| `-epsilon` | `-epsilon VALUE` | Convergence threshold (typically 1e-6) |
| `-fft` | `-fft LIBRARY` | FFT implementation (default Java FFT works) |

### §4.3 All 14 Algorithms

| Short | Name | Type | Parameters | When to Consider |
|-------|------|------|-----------|-----------------|
| `NIF` | Naive Inverse | Direct | (none) | Never on real data (educational only) |
| `RIF` | Regularized Inverse | Direct | lambda | Quick preview, speed critical |
| `TRIF` | Tikhonov Reg. Inverse | Direct | lambda | Quick preview, slightly better than RIF for noise |
| `RL` | Richardson-Lucy | Iterative | iterations | **Primary choice** for most fluorescence data |
| `RLTV` | RL + Total Variation | Iterative | iterations, lambda | **Noisy data** — TV suppresses noise, preserves edges |
| `LW` | Landweber | Iterative | iterations, gamma | Gaussian noise model; generally prefer RL |
| `NNLS` | Non-Neg Least Squares | Iterative | iterations, gamma | Constrained linear alternative to RL |
| `VC` | Van Cittert | Iterative | iterations, gamma | Historical; outperformed by RL |
| `TM` | Tikhonov-Miller | Iterative | iterations, gamma, lambda | Regularized iterative, Gaussian noise model |
| `ICTM` | Constrained TM | Iterative | iterations, gamma, lambda | TM + non-negativity; good alternative to RLTV |
| `BVLS` | Stark-Parker | Iterative | iterations, gamma | Bounded-variable solutions |
| `FISTA` | Fast Shrinkage-Threshold | Iterative | iterations, gamma, lambda, scale | Wavelet sparsity; good for puncta/filaments |
| `ISTA` | Shrinkage-Threshold | Iterative | iterations, gamma, lambda, scale | Same as FISTA but slower convergence |

**Algorithm syntax examples:**
```javascript
"-algorithm RL 15"                // RL, 15 iterations
"-algorithm RLTV 20 0.001"       // RLTV, 20 iterations, lambda=0.001
"-algorithm RIF 0.1"             // RIF, lambda=0.1
"-algorithm TM 15 1.0 0.1"      // TM, 15 iter, gamma=1.0, lambda=0.1
"-algorithm FISTA 20 1.0 0.001 3" // FISTA, 20 iter, gamma=1.0, lambda=0.001, 3 wavelet scales
```

### §4.4 Algorithm Selection Guide

```
Noisy data?
  YES → Best quality needed?
    YES → RLTV
    NO  → TM or ICTM
  NO (clean) → Confocal?
    YES → RL
    NO (widefield) → RL or RLTV
Quick preview only? → RIF or TRIF
```

| Modality | Primary | Secondary |
|----------|---------|-----------|
| Confocal (good SNR) | RL | ICTM |
| Confocal (noisy) | RLTV | ICTM |
| Widefield | RLTV | RL |
| Spinning disk | RL | RLTV |
| Two-photon | RL | TM |
| Light sheet | RL | RLTV |
| Speed critical | RIF | TRIF |

---

## §5 Iterative Deconvolve 3D

Simpler alternative to DL2. Single algorithm (constrained iterative RL variant). Built into Fiji.

```javascript
run("Iterative Deconvolve 3D",
    "image=myImage.tif" +
    " point=PSF.tif" +
    " output=Deconvolved" +
    " show perform detect" +
    " wiener=0.01" +
    " low=0 z_direction=1" +
    " maximum=15 terminate=0.01");
```

| Parameter | Key | Description |
|-----------|-----|-------------|
| Image stack | `image` | Title of blurred image (required) |
| PSF | `point` | Title of PSF image (required) |
| Output title | `output` | Name for result |
| Show iterations | `show` | Display intermediate results |
| Anti-ringing | `perform` | Anti-ringing step |
| Auto-detect divergence | `detect` | Stop if diverging |
| Wiener filter | `wiener` | Initial Wiener filter amount (0=off) |
| Low pass filter | `low` | Low-pass cutoff (0=off) |
| Z direction | `z_direction` | Z step relative to XY (1=isotropic) |
| Max iterations | `maximum` | Number of iterations |
| Convergence | `terminate` | Delta threshold to stop early |

**vs DL2**: Simpler (fewer choices), has built-in Wiener pre-filter and anti-ringing, but only one algorithm, no TV regularization, no wavelet methods, no flexible output.

---

## §6 CLIJ2 GPU-Accelerated Deconvolution

Requires update sites: clij, clij2, clijx-assistant-extensions. Compatible GPU with OpenCL.

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_push("myImage");
Ext.CLIJ2_push("PSF");
Ext.CLIJ2_getDimensions("myImage", width, height, depth);
Ext.CLIJ2_create3D("deconvolved", width, height, depth, 32);
Ext.CLIJx_imageJ2RichardsonLucyDeconvolution("myImage", "PSF", "deconvolved", 10);
Ext.CLIJ2_pull("deconvolved");
Ext.CLIJ2_release("myImage");
Ext.CLIJ2_release("PSF");
Ext.CLIJ2_release("deconvolved");
```

**GPU VRAM requirement**: ~5x image size in float32. For 1024x1024x100: ~2 GB VRAM.

**Limitations**: Only RL (no RLTV/wavelet), experimental (CLIJx), no built-in regularization, limited by VRAM. Fall back to DL2 if VRAM insufficient.

---

## §7 Practical Guidelines

### §7.1 Choosing Iteration Count

The most critical parameter. Too few = blur remains; too many = noise amplified.

**How to find the right count:**
1. Run with intermediate output (`-out stack @5 progress short rescaled`) and inspect visually
2. Compare line profiles: optimal shows sharper peaks, deeper valleys, no oscillation in flat regions
3. Check normalized residual: optimal minimizes residual without it becoming noise-dominated

**Starting points** (adjust based on your data's SNR):

| Modality | Starting Point | Typical Range |
|----------|---------------|---------------|
| Confocal (good SNR) | 15 | 10-25 |
| Confocal (noisy) | 10 | 5-15 |
| Widefield | 30 | 20-50 |
| Spinning disk | 20 | 10-30 |
| Two-photon | 15 | 10-25 |
| Light sheet | 20 | 15-30 |

### §7.2 Choosing Regularization (lambda)

**RLTV lambda** — controls TV smoothing strength:

| Direction | Effect |
|-----------|--------|
| Higher lambda | Smoother, less noise, may lose fine detail |
| Lower lambda | Sharper, more noise, preserves detail |

Starting point: 0.001 for most fluorescence data. Increase to 0.01 if noisy; decrease to 0.0001 if over-smoothed.

**TM/ICTM lambda**: Similar principle, different scale. Starting point: 0.1.

**Gamma (step size)** for Landweber-family (LW, NNLS, VC, TM, ICTM, BVLS): Start at 1.0. Values >1 risk divergence; <1 slow convergence.

### §7.3 Padding

Prevents FFT wrap-around edge artifacts. Consider `EXTENDED EXTENDED 0 0` for most situations. Use `NO NO 0 0` only when speed matters and edge regions are unimportant.

Memory impact: EXTENDED adds ~30-50% per dimension.

### §7.4 Bit Depth

Convert to 32-bit before deconvolution. 8-bit data (0-255) causes quantization artifacts in the iterative process.

```javascript
if (bitDepth() != 32) run("32-bit");
```

### §7.5 Multi-Channel Workflow

Split channels, generate per-channel PSF (different wavelength), deconvolve independently, merge back:

```javascript
run("Split Channels");
// Generate PSF per channel with appropriate wavelength (see fluorophore table)
// Deconvolve each: run("DeconvolutionLab2 Run", "... -image platform C1-... -psf platform PSF_ch1 ...");
// ...repeat for each channel...
run("Merge Channels...", "c1=Deconv_ch1 c2=Deconv_ch2 c3=Deconv_ch3 create");
```

---

## §8 Quality Assessment

### §8.1 Post-Deconvolution Checklist

| Check | How | Good Result |
|-------|-----|-------------|
| Ringing | Visual: halos around structures? | None visible |
| Noise amplification | Visual: grainy background? | Background smooth |
| Edge artifacts | Visual: bright/dark bands at borders? | Clean edges |
| Negative values | `getRawStatistics(n,mean,min,max); print(min);` | min >= 0 |
| Total intensity | Compare `nPixels*mean` before/after | Ratio near 1.0 |
| Resolution | Line profile FWHM before/after | Narrower peaks |

```bash
# Agent QA workflow
python ij.py capture after_deconv

python ij.py macro '
selectWindow("original");
getRawStatistics(n1, m1); t1 = n1*m1;
selectWindow("deconvolved");
getRawStatistics(n2, m2); t2 = n2*m2;
print("Intensity ratio: " + (t2/t1));   // should be ~1.0
getRawStatistics(n, mean, min, max);
if (min < 0) print("WARNING: negative values found");
'
```

---

## §9 Common Problems and Fixes

| Problem | Symptom | Fix |
|---------|---------|-----|
| Ringing | Bright/dark halos | Reduce iterations; add RLTV regularization; verify PSF |
| Edge artifacts | Bright/dark bands at borders | Add `-pad EXTENDED EXTENDED 0 0` |
| Wrong PSF | Result worse than input, diverges | Verify pixel size, z-step, NA, wavelength, RI all match |
| Out of memory | OutOfMemoryError | Increase Fiji RAM; reduce padding; crop to ROI; use CLIJ2 GPU |
| Noise amplification | Grainy background | Reduce iterations; switch to RLTV; add nonnegativity constraint |
| Slow execution | Takes too long | Use CLIJ2 GPU (5-20x); reduce iterations; crop; pad to power-of-2 |
| DL2 not found | "Unrecognized command" | Install via Help > Update > Manage Update Sites > "DeconvolutionLab2" |
| All black/white result | PSF norm wrong or overflow | Check PSF sum ~1.0; ensure 32-bit input |
| Title has spaces | DL2 fails | Rename image: `rename("myimage");` |

---

## §10 Memory Estimation

| Algorithm | Memory Multiplier | 512x512x64 32-bit |
|-----------|-------------------|-------------------|
| NIF | ~3x | ~192 MB |
| RIF, TRIF | ~4x | ~256 MB |
| RL | ~9x | ~576 MB |
| RLTV, FISTA, ISTA | ~12x | ~768 MB |
| TM, ICTM | ~10x | ~640 MB |
| LW, NNLS, VC | ~7x | ~448 MB |

Add padding overhead: EXTENDED +30-50%, EXPANDED up to +100%.

Increase Fiji memory via Edit > Options > Memory & Threads, or edit `Fiji.app/ImageJ.cfg` `-Xmx` value.

---

## §11 Alternative Tools

| Tool | Cost | Quality | Speed | GPU | Best For |
|------|------|---------|-------|-----|----------|
| DeconvolutionLab2 | Free | High | Medium | No | Most Fiji users |
| Iterative Deconvolve 3D | Free | Good | Medium | No | Quick RL |
| CLIJ2 | Free | Good | Fast | Yes | Large stacks |
| Huygens | $$$ | Excellent | Fast | Yes | Publication quality, auto PSF |
| CSBDeep/CARE | Free | Variable | Very Fast | Yes | Trained domains (needs training data) |
| scikit-image (Python) | Free | High | Slow | No | Custom pipelines |
| flowdec / RedLionfish | Free | High | Fast | Yes | GPU batch in Python |

---

## §12 Agent Integration

### §12.1 Extracting PSF Parameters from Metadata

```bash
python ij.py metadata
# Look for: Objective LensNA, EmissionWavelength, Immersion,
# PhysicalSizeX/Y/Z, NumericalAperture
```

If metadata is incomplete, ask the user for: objective NA, immersion medium, emission wavelength per channel, pixel size XY, z-step size.

### §12.2 Pre-Deconvolution Checklist

```bash
python ij.py info          # Confirm z-stack (slices > 1)
python ij.py histogram     # No pile-up at max value (saturation)
python ij.py metadata      # Pixel size and z-step are set
python ij.py macro 'if (bitDepth() != 32) { run("32-bit"); }'
python ij.py macro '
getDimensions(w, h, c, z, t);
print("Est. RAM needed (RL): " + (w*h*z*4*9/(1024*1024)) + " MB");
print("Available: " + IJ.freeMemory());
'
```

### §12.3 When to Suggest Deconvolution

**Consider suggesting when**: fluorescence z-stack, visible out-of-focus haze, quantitative measurements on z-stacks.

**Do not suggest when**: single plane, brightfield/H&E/phase, JPEG, already super-resolution, user wants quick look.

---

## §13 Troubleshooting Checklist

```
[ ] Image is 32-bit (convert if not)
[ ] Image is not JPEG compressed
[ ] Image is not saturated (check histogram)
[ ] PSF pixel size matches image pixel size
[ ] PSF z-step matches image z-step
[ ] PSF NA matches objective NA
[ ] PSF wavelength matches fluorophore emission
[ ] PSF RI matches immersion medium
[ ] PSF normalized (sum = 1)
[ ] Padding enabled (-pad EXTENDED EXTENDED 0 0)
[ ] Non-negativity constraint set for fluorescence
[ ] Iteration count appropriate for modality/SNR
[ ] Sufficient RAM available (~9x image size for RL)
[ ] Result conserves total intensity
[ ] No ringing/edge artifacts visible
```

---

## §14 Why Deconvolve — Decision Logic in Depth

Sections §2.1 and §12.3 give the bare table. This section expands the
underlying reasoning so the agent can argue the suggestion to a user.

### §14.1 What deconvolution actually does

Forward model of any fluorescence image:

```
g(x) = (h ⊗ f)(x) + n(x)
```

where `f` is the underlying fluorophore distribution, `h` is the point
spread function (PSF) of the imaging system, `⊗` is 3D convolution, `n`
is noise (Poisson photon counting + Gaussian read noise), and `g` is
the observed image. Deconvolution inverts this — estimates `f̂` from
`g` given a model of `h` and `n`. Three things can go wrong:

1. **Wrong h**. The deconvolution acts as if blur was something it
   wasn't. Even small RI mismatches (>0.05) distort the PSF tails
   enough to produce shifted intensity and ringing.
2. **Mis-modelled n**. Richardson–Lucy assumes pure Poisson; Tikhonov–
   Miller assumes pure Gaussian. Both are wrong on a real CMOS camera
   (mixed Poisson + Gaussian read noise) but the approximation is fine
   when one term dominates.
3. **Ill-posed inverse**. Convolution is a low-pass operation; spatial
   frequencies above the cutoff (~2 NA / λ) carry zero signal. Naive
   inversion divides by zero at those frequencies. Every practical
   algorithm controls this with iteration count, regularization, or
   explicit cutoff.

### §14.2 When deconvolution actually helps — quantitative criteria

| Criterion | Threshold for benefit | Reason |
|---|---|---|
| SNR (peak signal / std of background) | ≥ 10 for stable RL; ≥ 3 marginal with RLTV | RL amplifies noise that is comparable to signal. |
| Resolution gap to PSF cutoff | image FWHM > 1.5× theoretical PSF FWHM | If you're already at the diffraction limit, deconvolution can only marginally re-sharpen. |
| PSF anisotropy | axial:lateral FWHM > 2 (almost always true for widefield) | Recovers true 3D shape from "stretched" point sources. |
| Number of z-slices | ≥ 16 sampled at Nyquist axially | Truncated PSF tails distort axial recovery. |
| Bit depth | 16-bit raw, processed as 32-bit | 8-bit quantization rounds to grid that RL iterations cannot cross. |

### §14.3 When deconvolution makes things worse

Strong don'ts (refusing here is the right call even if the user insists):

- **Saturated pixels in the structure of interest.** Saturation violates
  the linear forward model — those pixels carry no information about
  the true intensity, so RL drives their neighbourhoods to nonsense.
  Check `python ij.py histogram`; refuse if there's a pile-up at
  bitDepth_max.
- **Lossy compression artifacts** (JPEG, AVI/MP4). Compression noise is
  highly non-Poisson and structured; deconvolution amplifies it into
  pseudo-features. `python ij.py macro 'print(getInfo("file.format"));'`.
- **Already gamma-corrected, contrast-stretched, or histogram-equalized
  data.** Forward model assumes linearity. Any non-linear remap breaks it.
- **Non-fluorescence brightfield / phase / DIC.** Different forward
  model entirely — needs phase-sensitive methods (e.g. SIMBA, QPI), not
  fluorescence RL.
- **A single 2D plane from a wide-field z-stack.** Without the out-of-
  focus information in the 3D context, 2D deconvolution mostly reshuffles
  haze rather than rejecting it. If the modality is wide-field, insist
  on a stack or accept that the result is cosmetic.
- **Already at SR limit (STED, SIM, STORM).** The PSF model used by
  classical deconvolution is the diffraction-limited PSF; it doesn't
  describe the SR PSF. Use SR-aware regularization (e.g. Huygens STED
  module) or skip it (cross-link
  `super-resolution-reference.md §11`).

### §14.4 When it definitely helps — modality detail

**Wide-field (no confocal pinhole)**: every plane sees out-of-focus
contributions from every other plane. The 3D PSF is hourglass-shaped
with massive axial extent. RL on a properly sampled z-stack can give
≥3× axial contrast improvement, recover sub-resolution puncta separation,
and produce confocal-like optical sections. This is the canonical use
case.

**Confocal at the resolution limit**: pinhole already rejects most out-
of-focus light, but the residual PSF is still broader than the airy
limit. RL with 10–20 iterations sharpens by ~30–40% in lateral and
~50% in axial. Most useful for quantitative puncta counting and
colocalization (cross-link `colocalization-reference.md`).

**Spinning disk**: closer to wide-field than to point-scan confocal in
PSF properties (multi-pinhole crosstalk leaks more out-of-focus light).
RL benefit is between wide-field and confocal — typically 20–40
iterations.

**Two-photon**: small confocal-volume PSF already, but diffraction
limit at long excitation wavelengths (920 nm GFP, 1040 nm RFP) gives
larger PSF than visible confocal. Deconvolution helps mostly axially.

**Light-sheet (SPIM, lattice light-sheet, oblique plane)**: PSF strongly
anisotropic — sheet thickness sets axial resolution; detection objective
sets lateral. **Multi-view deconvolution** (Preibisch et al. 2014,
BigStitcher) fuses orthogonal views to produce isotropic resolution.
Single-view deconvolution helps when only axial blur needs reducing.

**Pre-deconvolved publication figures**: occasionally a user wants to
restore figures from a paper where deconvolution was applied. Don't —
re-deconvolving an already-deconvolved image violates linearity. Ask
for the raw data instead.

---

## §15 PSF Measurement from Beads — Practical Workflow

Measured PSFs beat theoretical PSFs *only when* the bead acquisition is
done right. A bad bead PSF is worse than a well-chosen Gibson–Lanni
theoretical PSF. This section is the full operating procedure.

### §15.1 Bead choice

Beads must be sub-resolution, i.e. smaller than the PSF FWHM you want
to measure, otherwise you measure the bead, not the PSF.

| Bead diameter | When | Notes |
|---|---|---|
| 100 nm (e.g. PS-Speck, TetraSpeck 100 nm) | High-NA confocal/wide-field, SR alignment | Below diffraction limit at every visible wavelength; brightness becomes the limit, not size. |
| 175 nm | Routine confocal QC (MetroloJ-QC default) | Compromise between size and signal; convolved with PSF gives ~5% over-estimate of FWHM. |
| 200 nm (TetraSpeck) | Multi-colour alignment, low-NA | Brighter, multi-colour alignment is the main use; size correction needed for FWHM. |
| 500 nm – 1 μm | Stage / illumination QC only | NOT for PSF measurement. |

For the FWHM bias from finite bead size: convolve a sphere of diameter
`d` with a Gaussian PSF of true FWHM `w_true` and the measured FWHM is
approximately `w_meas² ≈ w_true² + d²` (lateral) or `w_true² + d²` for
axial too — quadrature sum. So a 175 nm bead gives ~5% over-estimate
of a 250 nm true FWHM, ~30% over-estimate of a 100 nm STED PSF.

### §15.2 Sample preparation

The single biggest cause of failed bead PSFs is mounting in a medium
that doesn't match your imaging conditions:

| Imaging condition | Mount beads in |
|---|---|
| Live cells in PBS | Beads diluted in PBS, sealed under coverslip |
| Fixed in glycerol-based mountant (Vectashield, ProLong Glass) | Beads diluted in same mountant |
| ProLong Diamond / Gold | Same — wait 24 h to cure (RI changes) |
| CLARITY / iDISCO cleared | Beads embedded in clearing solution at the same RI |
| Agarose-embedded SPIM samples | Beads in same agarose at same concentration |

Bead density: ~0.05–0.1% solids final, then dilute 1:1000 to 1:10000 so
beads are far enough apart to crop a 64×64 ROI without neighbours. Too
dense → overlapping PSFs averaged together gives a fat, asymmetric
"PSF" that's nothing like real.

Coverslip: #1.5H (170 ± 5 μm), the same one your samples use.

### §15.3 Acquisition

Match every parameter to your experimental acquisition. The PSF is
specific to the optical path and conditions.

| Parameter | Rule |
|---|---|
| Objective | Same one (NA, immersion type, correction collar setting). |
| Immersion oil / water | Same batch. RI of "Type F" varies between manufacturers. |
| Wavelength | Same emission filter / excitation laser. One PSF per channel. |
| Pinhole (confocal) | Same Airy units. |
| Pixel size XY | Same. Re-zoom the same setting; don't trust nominal. |
| Z-step | ≤ Nyquist axial: `dz ≤ λ / (4·n·(1 − cos α))` where `α = arcsin(NA/n)`. Most commercial systems' "best" preset is at Nyquist already. |
| Z-range | At least ±2 μm above and below the brightest plane (PSF tails extend further than they look). |
| Laser power | Same (PSF shape is power-invariant if linear, but at very low SNR you can't fit). |
| Camera / PMT gain | Adjust for SNR ≥ 30 in the brightest bead pixel without saturation. |

Acquire 10–30 isolated beads in different field positions (centre + 8
positions toward corners) to capture field-dependent aberrations.

### §15.4 Bead averaging — recommended pipeline in Fiji

```javascript
// Assumes you have a multi-bead z-stack of bead_field.tif
open("/path/to/bead_field.tif");
run("32-bit");
run("Subtract Background...", "rolling=20 stack");

// Detect bead centroids on max projection (cross-link
// if-postprocessing-reference.md §3 for filter pipelines)
run("Z Project...", "projection=[Max Intensity]");
selectWindow("MAX_bead_field.tif");
setAutoThreshold("MaxEntropy dark");
run("Convert to Mask");
run("Analyze Particles...", "size=4-Infinity exclude clear add");
n = roiManager("count");
print("Detected beads: " + n);

// Crop a 64x64xN cube around each bead, drop ones with neighbours
selectWindow("bead_field.tif");
psf_avg = newArray();
for (i = 0; i < n; i++) {
    roiManager("select", i);
    getSelectionBounds(x, y, w, h);
    cx = x + w/2; cy = y + h/2;
    // Pad / center
    makeRectangle(cx - 32, cy - 32, 64, 64);
    run("Duplicate...", "title=bead_" + i + " duplicate");
    // Sub-pixel re-centre on max — see PSFj method
}
// Average crops into a single PSF: "Image > Stacks > Tools > Concatenate",
// then Z-project... [Average].
```

The right pipeline is via **MetroloJ-QC** (Plugins > MetroloJ_QC > PSF Profiler);
**PSFj** (drop a folder of bead crops); or **PyCalibrate** (stand-alone).
These do sub-pixel re-centring, automatic rejection of close pairs, and
gaussian fit FWHM in 3D — far more robust than hand-rolled Fiji macros.

### §15.5 PSF normalization and storage

Before deconvolution, the measured PSF must:

1. Have no negative values (subtract the median background, then floor
   to 0). RL with negative PSF entries diverges.
2. Sum to 1.0 over the whole 3D volume (preserves total intensity).
3. Have its peak at the geometric centre of the image (or pad
   asymmetrically so it does — DL2 handles centring but it's clearer
   to be explicit).
4. Have its tails fall to background — if it's truncated, pad with zeros.

Save as 32-bit TIFF. Re-use across all deconvolutions of samples
acquired with the same optical configuration.

### §15.6 PSF symmetrization — usually don't

Some pipelines (notably old AutoQuant) symmetrize the measured PSF by
azimuthal averaging before use. Two cases:

- **OK**: highly noisy bead PSF where azimuthal averaging genuinely
  reduces noise without distorting reality. Only justify when you've
  averaged across <5 beads and the optical path is rotationally
  symmetric (no field-dependent astigmatism).
- **Not OK**: when the optical path has real asymmetries (off-centre
  pupil, chromatic shifts in dual-camera systems, oblique-plane
  microscopy). Symmetrization erases the very aberrations you wanted
  to correct.

Default: don't symmetrize. Average more beads instead.

### §15.7 PSF QC checks before using

```javascript
// After loading measured_PSF.tif
run("32-bit");
getRawStatistics(n, mean, min, max);
if (min < 0) { print("PSF has negatives — subtract background again"); }
total = n * mean;
print("PSF total integral: " + total);  // should be very close to 1.0
// Get Z profile through centre to inspect tails
makePoint(width/2, height/2);
run("Plot Z-axis Profile");
// Check axial:lateral asymmetry ratio
// (cross-link MetroloJ-QC for an automated report)
```

A well-formed PSF for a 1.4 NA oil objective at 510 nm emission with
proper coverslip and oil:

- Lateral FWHM ≈ 220 nm (5 px at 40 nm/px).
- Axial FWHM ≈ 600 nm (3 z-slices at 200 nm step).
- Lateral asymmetry ratio (LAR) within 1.0 ± 0.1.
- Z asymmetry ratio (top/bottom of axial profile) within 1.0 ± 0.2 if
  RI is well-matched. Diverging from 1 → spherical aberration → fix
  the correction collar or accept Gibson–Lanni instead.

---

## §16 PSF Generation Tools — Detail

§3.3 covers Diffraction PSF 3D macros and §3.4 mentions PSF Generator.
This section is the tool-by-tool comparison.

### §16.1 Diffraction PSF 3D (Bob Dougherty / OptiNav)

- **Model**: Born–Wolf scalar.
- **Strength**: macro-recordable, always available, fast.
- **Limit**: assumes uniform refractive index (no coverslip / sample
  layer mismatch). OK for matched conditions; underestimates
  spherical aberration when imaging deep into mismatched RI.
- **Use when**: oil objective at coverslip, sample very near coverslip,
  RI matched within 0.02 of immersion. Or as a fast first pass before
  deciding measurement is needed.

### §16.2 PSF Generator (EPFL BIG)

- **Models**: Born–Wolf, Gibson–Lanni (3-layer), Richards–Wolf
  (vectorial), Variable-RI Gibson–Lanni.
- **Strength**: physically correct multi-layer PSFs; published in
  Kirshner et al. 2013 *J. Microsc.* — peer-reviewed accuracy.
- **Limit**: Swing GUI (no macro recording). To use in batch:
  generate once in the GUI, save the resulting TIFF, then load with
  `open("/path/to/PSF.tif")` from a macro.
- **Install**: Help > Update > Manage Update Sites > "PSF Generator"
  (EPFL update site).
- **Recommended model choice**:
  - Standard fluorescence on coverslip: **Gibson–Lanni**.
  - High-NA (>1.2) where polarization matters (e.g. linear dichroism
    measurements): **Richards–Wolf**.
  - Imaging deep into a sample with RI gradient: **Variable-RI
    Gibson–Lanni**.
  - Oil objective at coverslip with RI exactly matched: **Born–Wolf**
    is fine and fastest.

Typical Gibson–Lanni parameters to set in the GUI:

| Field | Value |
|---|---|
| NA | objective NA |
| Lambda | emission peak (nm) |
| nI (immersion) | 1.518 oil / 1.333 water / 1.473 glycerol |
| nS (sample) | RI of the medium the fluorophore sits in |
| nG (cover glass) | 1.522 |
| tG (cover glass thickness) | 170000 nm (#1.5) |
| ti0 (working distance) | objective WD |
| ZPos (depth into sample) | 0 nm at coverslip surface |

### §16.3 MetroloJ-QC

- **Purpose**: bead-based PSF measurement with full QC report
  (FWHM, LAR, SBR, goodness-of-fit r²).
- **Output**: PDF report with per-bead and averaged PSF metrics, plus
  the averaged PSF as a TIFF you can use directly with DL2.
- **Install**: download `MetroloJ_QC_*.jar` from
  `github.com/MontpellierRessourcesImagerie/MetroloJ_QC` and drop in
  `Fiji.app/plugins/`.
- **Use**: Plugins > MetroloJ QC > PSF Profiler; point at a bead
  z-stack (filename should encode wavelength). Output goes to a
  user-chosen folder.

### §16.4 PSFj

- **Purpose**: characterize field-dependent aberrations. Extracts
  per-bead PSFs across a field, fits a 3D Gaussian, maps FWHM and
  asymmetry as a function of position.
- **Output**: 2D maps of lateral/axial FWHM, chromatic shift,
  flatness — useful for diagnosing decentred objectives, dirty lenses,
  field-curvature issues.
- **Install**: standalone Java app + Fiji plugin from
  `kuoplab.org/psfj` (last release 2014; still works).
- **Use**: when MetroloJ-QC says "your PSF is fine on average" but
  edges of your images still look soft.

### §16.5 PyCalibrate

- **Purpose**: fully automated bead PSF measurement, no manual ROI
  selection.
- **Output**: spreadsheet of per-bead metrics + averaged PSF.
- **Best for**: routine instrument QC where you image a slide of beads
  monthly and want trend tracking.

### §16.6 Theoretical vs measured — decision

| Situation | Use |
|---|---|
| Routine confocal/wide-field with matched RI | Gibson–Lanni theoretical |
| High-NA (>1.3) oil with stained tissue | Measured PSF or Variable-RI |
| Light-sheet | Measured (system-specific anisotropy) |
| STED / SIM / SR | Measured (the "PSF" depends on reconstruction) |
| Spherical aberration suspected (deep imaging) | Measured |
| First time on a new system | Measured + compare to Gibson–Lanni |
| No bead slide available, deadline tomorrow | Theoretical, document it |

---

## §17 Algorithms in Depth

§4.3 lists DeconvolutionLab2's 14 algorithms. This section explains the
underlying noise model, expected behaviour, and when each is the right
choice. Useful when the user asks "why RL not Wiener?".

### §17.1 Inverse filter (NIF)

- **Math**: `f̂ = F⁻¹{ G/H }` — divide image FFT by PSF FFT.
- **Noise model**: none.
- **Behaviour**: explodes wherever H is near zero (which is everywhere
  outside the diffraction-limited support). Pure noise on real data.
- **Use**: educational only. Show students what "naive deconvolution"
  looks like to motivate regularization.

### §17.2 Wiener filter (RIF — Regularized Inverse)

- **Math**: `f̂ = F⁻¹{ G·H̄ / (|H|² + K) }` where K is a noise-to-signal
  power spectral density estimate (the "Wiener parameter").
- **Noise model**: zero-mean stationary Gaussian. Closed-form, one-shot.
- **Behaviour**: smooth where SNR is low, sharp where SNR is high.
  Choosing K too small → noise blow-up at high frequencies. Too large
  → blurry result, Wiener degenerates to identity.
- **Practical K**: 0.001–0.1 of the image's spectral power; in DL2,
  the `RIF lambda` plays the same role.
- **Use**: quick previews, very fast batch over thousands of fields where
  RL would take too long. Also baseline to compare iterative methods.

### §17.3 Tikhonov–Miller (TM) and ICTM

- **Math**: minimise `||g − Hf||² + λ ||L f||²` for L = identity (Tikhonov)
  or gradient (Tikhonov–Miller). Add nonnegativity → ICTM.
- **Noise model**: Gaussian.
- **Behaviour**: smoother than RL; less ringing but also less sharpening.
  Faster than RL (Landweber-style updates).
- **Use**: when noise is read-noise-dominated (very high signal CMOS
  data) rather than shot-noise-dominated. Otherwise RL or RLTV is
  usually better.

### §17.4 Richardson–Lucy (RL)

- **Math**: iterative multiplicative update
  `f^(k+1) = f^(k) · (H̄ ⊗ (g / (H ⊗ f^(k))))`.
- **Noise model**: pure Poisson (photon shot noise).
- **Properties**:
  - Conserves total intensity at every iteration (sums of `f` and `g`
    stay equal).
  - Preserves nonnegativity automatically.
  - Maximises Poisson likelihood / minimises Csiszár I-divergence
    (a.k.a. Kullback–Leibler divergence between data and model).
  - Converges in the limit, but in practice **early stopping is the
    regularization** — running too long amplifies noise.
- **Behaviour**: sharpens iteratively, then starts amplifying noise
  ("noise overshoot" after the optimal stop).
- **Use**: default first choice for any fluorescence z-stack with
  reasonable SNR (≥10).

### §17.5 Richardson–Lucy + Total Variation (RLTV)

Reference: Dey, Blanc-Féraud, Zerubia 2006 *Microsc. Res. Tech.* 69:260.

- **Math**: RL update modified by `1 / (1 − λ · div(∇f / |∇f|))` —
  a TV gradient descent step inside each RL iteration.
- **Noise model**: Poisson + edge-preserving prior.
- **Behaviour**: smooths flat regions while keeping edges sharp. The
  TV prior is the same one used in compressed sensing — it favours
  piecewise-constant images.
- **Practical λ**: 0.001 default, range 0.0001 (almost no smoothing) to
  0.01 (heavy smoothing). Tune visually.
- **Use**: noisy data (SNR 3–10) where plain RL would over-amplify.
  Also good for puncta in heavy background where you want to preserve
  bright dot edges.
- **Trade-off**: TV produces "stair-stepping" artifacts on smooth
  intensity gradients (e.g. faint cytoplasm). For stair-stepping
  problems, switch to FISTA with wavelet sparsity instead, or accept
  it and rely on RL alone.

### §17.6 Maximum-likelihood family — penalized MLE

The Huygens commercial line is built on penalized MLE (PMLE). Three
variants:

- **CMLE (Classic MLE)**: equivalent to RL with positivity. Most
  general, handles low-SNR best, slowest.
- **QMLE (Quick MLE)**: spectral-domain optimization, ~5× faster than
  CMLE on widefield. Default for clean confocal/widefield.
- **GMLE (Good's roughness MLE)**: RL with Good's roughness penalty
  (an entropic smoothness prior). 4× fewer iterations than CMLE for
  the same restoration. Best for very noisy STED/confocal.

### §17.7 Landweber, Van Cittert, BVLS, NNLS

Historical Gaussian-noise iterative methods. RL has effectively replaced
all of them for fluorescence. Worth knowing only because some legacy
pipelines still use them. In DL2, prefer RL/RLTV unless reproducing a
specific old paper.

### §17.8 ISTA / FISTA — sparsity-promoting

- **Math**: minimise `||g − Hf||² + λ ||W f||₁` where W is a wavelet
  transform. ISTA = simple proximal gradient; FISTA = Nesterov-
  accelerated, ~10× faster convergence.
- **Behaviour**: produces sparse output in the wavelet domain — favours
  sharp punctate or filamentous structures. Not ideal for smooth
  cytoplasmic stains.
- **Practical**: in DL2, `-algorithm FISTA <iter> <gamma> <lambda>
  <wavelet_scales>`. Start `iter=20 gamma=1.0 lambda=0.001 scales=3`.
- **Use**: dense puncta (FISH spots, synaptic markers); single
  molecules above background; thin filaments where edge preservation
  matters more than gradient fidelity.

### §17.9 Blind deconvolution

Joint estimation of `f̂` and `ĥ`. Without a measured PSF, the algorithm
iteratively updates both.

- **Software**: Huygens (PMLE blind mode), AutoQuant.
- **Works when**: (i) illumination is roughly homogeneous over the
  region of interest; (ii) SNR is good; (iii) the structure has enough
  "edge content" to disambiguate PSF from object.
- **Fails when**: heterogeneous illumination, low SNR, very sparse
  features (a few blobs let the algorithm absorb the PSF into the
  object), strong aberrations beyond the parametric PSF model.
- **Default recommendation**: don't use blind unless you have to.
  Measured > theoretical > blind.

### §17.10 Deep-learning restoration (CARE, 3D-RCAN)

CARE (Weigert et al. 2018 *Nat. Methods*) — U-Net regression trained on
paired low-quality / high-quality images. Demonstrated 60× fewer photons,
near-isotropic resolution from 10× axially under-sampled data, sub-
diffraction tubular structure at 20× higher frame rate. Strictly **not
deconvolution** — the network learns an inverse mapping for the trained
domain. Predictions are statistical summaries of training data, not
physically reconstructed intensities. Hallucination on OOD data is the
primary failure mode. CARE output is not photometrically faithful in
the way classical deconvolution is — quantitative claims need
calibration; always show raw + CARE side by side; disclose model and
training data. 3D-RCAN (Chen et al. 2021) is similar with channel-
attention residual blocks. Both Fiji-integrated via the CSBDeep update
site → Plugins > CSBDeep > Run your network. Cross-link
`ai-image-analysis-reference.md`.

### §17.11 Self-supervised denoising — Noise2Noise / Noise2Void

Lehtinen 2018 (N2N — pairs of independent noisy realizations of the
same scene), Krull 2019 (N2V — single noisy image with "blind-spot"
training that masks the centre pixel during training), Krull 2020 (PN2V
— per-pixel noise distributions for principled uncertainty).

**These are denoisers, not deconvolution.** They reduce noise but
don't sharpen the PSF. Pipeline order: `raw → N2V denoise → background
subtract → RL deconvolve → analysis`. Skip the denoise step if SNR is
already good (>20) — N2V can slightly bias mean intensities and obscures
the Poisson statistics RL relies on. If both are used, disclose both;
N2V outputs are content-aware predictions, not measurements.

### §17.12 Algorithm decision tree (extended)

```
Start
 │
 ├── Saturated? → STOP, refuse, or mask saturated regions before proceeding
 ├── 8-bit raw? → convert to 32-bit; warn user about quantization
 ├── PSF available? ── No → measure or simulate (§15, §16) before continuing
 │
 ├── SNR ≥ 20, well-conditioned, smooth structures dominant
 │        → Richardson–Lucy, 15–30 iterations
 │
 ├── SNR 5–20, mixed structure
 │        → Richardson–Lucy + TV, λ ≈ 0.001, 20–50 iterations
 │
 ├── SNR < 5 (very noisy, but still need 3D recovery)
 │        → N2V denoise first → RL on denoised → cap at 10 iterations
 │
 ├── Sparse puncta / FISH / single molecules
 │        → FISTA, 20 iter, λ ≈ 0.001, scales = 3
 │
 ├── Already at SR limit (STED/SIM/STORM)
 │        → Skip; or use SR-specific deconvolution (Huygens STED)
 │
 ├── Have GPU; need throughput; classical RL acceptable
 │        → Deconwolf (RL+SHB) or RedLionfish or CLIJ2 (§19, §6)
 │
 ├── Domain has paired noisy/clean training data
 │        → CARE (CSBDeep), with mandatory raw-comparison in figures
 │
 └── Heterogeneous illumination / unknown PSF / no beads possible
          → Blind deconvolution (Huygens or AutoQuant); flag uncertainty
```

---

## §18 Iteration Count Selection — Beyond §7.1

§7.1 gives starting points. This section describes how to actually
choose for a given dataset.

### §18.1 Visual criteria

The single most useful technique is intermediate-iteration output:

```javascript
run("DeconvolutionLab2 Run",
    " -image platform " + raw +
    " -psf platform " + psfTitle +
    " -algorithm RL 50" +
    " -out stack @5 progress short rescaled" +   // dump every 5 iters
    " -constraint nonnegativity -norm 1");
```

Now you have stacks at iterations 5, 10, 15, ... 50. Inspect:

- **Sharpness peaks then plateaus**: at the plateau, more iterations
  don't add resolution but start adding noise. Stop at or just before
  the plateau onset.
- **Ringing onset**: dark halos around bright structures appear when
  iterations exceed the SNR-supported information content. Stop one
  step before ringing.
- **Background grain**: as iterations climb, flat background regions
  develop salt-and-pepper grain. Optimal iteration count is just
  before grain becomes visible.

### §18.2 Quantitative criteria

**I-divergence / KL stop**: Richardson–Lucy minimises the Csiszár
I-divergence `I(g, Hf) = Σ g·log(g / Hf) − g + Hf`. Compute it each
iteration; the curve drops fast initially then flattens. The "elbow"
of the curve is the heuristic stop point.

```python
# pseudo-code, after each RL iteration k:
g_pred = convolve(f_k, h)
I_k = sum(g * log(g / g_pred) - g + g_pred)
# stop when (I_{k-1} - I_k) / I_{k-1} < 0.001
```

DL2 with `-stats showsave` exports the per-iteration energies; FlowDec
and pyDecon do this natively in Python.

**Discrepancy principle**: choose `k` so that the residual `||g − Hf_k||²
≈ N · σ²` matches the known noise variance. Useful when σ is calibrated
from camera read noise + shot noise.

**Cross-validation on held-out pixels**: Robinson & Milanfar 2008. Mask
a random 10% of pixels, deconvolve, and report iteration where MSE on
the held-out pixels is minimised. Implemented in pyDecon as
`unbiased_predictive_risk_estimate`.

### §18.3 Practical iteration counts by modality

(Update of §7.1 with rationale.)

| Modality | SNR | Iterations | Why |
|---|---|---|---|
| Confocal, bright signal | 30+ | 10–20 | PSF already small; excess iterations amplify shot noise |
| Confocal, dim signal | 10–20 | 5–10 | Shot noise dominates; stop early |
| Wide-field, bright | 30+ | 30–50 | Hourglass PSF needs many iterations to reject out-of-focus |
| Wide-field, dim | 10–20 | 20–30 | Compromise |
| Spinning disk | 20–30 | 15–25 | Between confocal and widefield |
| Two-photon | 20+ | 15–25 | Smaller PSF than 1P widefield, but axial blur still needs work |
| Light-sheet, single view | 20+ | 20–40 | Strong axial anisotropy; iterations recover Z |
| Light-sheet, multi-view | 20+ | 15–30 | Multi-view fusion already isotropises somewhat |
| RLTV (any modality) | as above | × 1.5 | TV step slows convergence |
| Deconwolf (RL + Scaled Heavy Ball) | as above | ÷ 2–3 | SHB acceleration |

### §18.4 Iteration count is data-specific — never hard-code

Given the same microscope and same protocol, different samples within
the same dataset can need different iteration counts (a thin section
needs fewer than a thick chunk; a sparse stain needs fewer than a
dense one). The agent should:

1. Prefer multi-iteration output (`@5`) and ask the user.
2. If automating, default per modality (§18.3) and produce a
   stop-criterion plot from `-stats showsave`.

---

## §19 Software Catalog — End-to-End Comparison

Updates and deepens §11.

### §19.1 Open-source, Fiji-resident

| Tool | Algorithms | GPU | Macro? | Strengths | Weaknesses |
|---|---|---|---|---|---|
| DeconvolutionLab2 | 14 (NIF, RIF, TRIF, RL, RLTV, LW, NNLS, VC, TM, ICTM, BVLS, FISTA, ISTA) | No | Yes | Algorithm coverage; published; flexible I/O | CPU-only; slower than GPU tools |
| Iterative Deconvolve 3D | RL with anti-ringing + Wiener pre-filter | No | Yes | Robust; auto-divergence detection; built into Fiji | Single algorithm; less flexible output |
| Parallel Spectral Deconvolution | Wiener (CPU multi-thread) | No | Yes | Fast Wiener for previews | One algorithm |
| CLIJ2 / CLIJx | RL | OpenCL GPU | Yes | Fast on any GPU; integrates with CLIJ2 pipelines | Experimental status; only RL; VRAM-bound (cross-link `clij2-gpu-reference.md`) |
| CSBDeep / CARE | DL inference | TF GPU | Yes (Plugins > CSBDeep) | DL restoration with pretrained models | Trained-domain only; not classical |
| Deconwolf (Fiji wrapper or CLI) | RL + Scaled Heavy Ball acceleration | OpenCL | CLI primarily | Fastest open-source RL (2024 *Nat. Methods*); chromatic correction | Not natively a Fiji plugin; install + script |

### §19.2 Open-source, Python

| Tool | Algorithms | GPU | Notes |
|---|---|---|---|
| FlowDec | RL | TensorFlow CUDA | Static graph; very fast batch; install painful (TF + CUDA versions) |
| RedLionfish | RL | PyOpenCL (any GPU) | Auto-falls-back to scipy CPU; napari plugin; Wellcome Open Res. 2024 |
| pyDecon | RL, Wiener, FISTA | numpy / cupy | Pure Python; great for prototyping; slower than FlowDec/RedLionfish |
| skimage.restoration | RL, unsupervised Wiener | No | Reference implementation; small images only |
| pyclesperanto | RL (via OpenCL) | OpenCL | Same backend family as CLIJ2; cross-link `clij2-gpu-reference.md` |

### §19.3 Commercial

| Tool | Algorithms | GPU | Strengths | Notes |
|---|---|---|---|---|
| Huygens (SVI) | CMLE, QMLE, GMLE, Skel/Penalised, blind, STED-specific, multi-view fusion | Yes | Industry standard; PSF distiller; SPIM Fusion+Decon Wizard; batch templates | Pricey; site licences common |
| AutoQuant / AutoDeblur (Media Cybernetics) | Blind iterative, AdaptivePSF | Yes | Original blind-deconvolution package | Less innovation since 2010s |
| Microvolution | RL + extras, real-time | Multi-GPU | Up to 200× faster than CPU classical; integrates with Slidebook, MetaMorph, NIS-Elements, AIVIA | Per-seat or instrument-bundle |
| Imaris (Bitplane / Oxford Instruments) | RL via "ClearView" | Yes | Embedded in 3D analysis workflow | Black-box parameters |
| Nikon NIS-Elements 3D Decon | RL, blind, AutoQuant integration | Optional | Tight integration with Nikon hardware | Vendor lock-in |
| Leica THUNDER (Computational Clearing) | Adaptive haze removal (not strictly RL) | Yes | Real-time on widefield | Vendor lock-in |

### §19.4 Choosing among the open-source options

```
Need fastest open-source on a laptop GPU? → Deconwolf (CLI) or RedLionfish
Need scriptable in Python pipelines? → FlowDec (CUDA) or RedLionfish (any GPU)
Need full algorithm zoo (FISTA/RLTV/blind via DL2)? → DeconvolutionLab2
Need to stay inside a CLIJ2 GPU pipeline? → CLIJ2 RL (one-call op)
Need DL restoration with pretrained models? → CARE / CSBDeep
Doing routine batch over thousands of files? → Deconwolf or Microvolution
First-time exploration on one image? → DL2 in the GUI for inspection, then macro
```

---

## §20 Validation — Did Deconvolution Actually Help?

§8.1 has the eyeball checklist. This section adds quantitative tests.

### §20.1 Phantom validation — beads in, ideal PSF out

The cleanest test. Image a bead slide under the same conditions as your
sample, deconvolve with your chosen PSF + algorithm + iterations, and
verify the deconvolved bead matches expectations:

- Lateral FWHM should approach `λ / (2·NA)` (Rayleigh criterion, ~0.86×
  the airy half-width).
- Axial FWHM should approach `2·λ·n / NA²` (axial Rayleigh).
- The bead should remain spherical / centred (no drift, no lateral shift).
- Total intensity should be preserved within ±5%.

A deconvolution pipeline that fails this test on beads will fail it on
any sample. Run it before trusting results.

### §20.2 Fourier Ring Correlation (FRC)

FRC measures the spatial frequency at which two independent images of
the same scene decorrelate. It gives a single resolution number that
you can compare before vs after deconvolution. Cross-link
`super-resolution-reference.md §7.1`.

In Fiji, BIOP "FRC" plugin or NanoJ-SQUIRREL "Calculate FRC Map" both
work. For deconvolution evaluation:

1. Acquire two independent noise realizations of the same field (e.g.
   two consecutive z-stacks; or split the time-lapse frames into odd vs
   even halves).
2. Deconvolve each half independently with the same PSF + algorithm.
3. Compute FRC between the two deconvolved images. Resolution = FRC
   curve crossing the 1/7 threshold.
4. Compare to FRC of raw odd vs raw even. A successful deconvolution
   pushes the resolution finer.

If the FRC after deconvolution is *worse* than before, you've over-
iterated, used the wrong PSF, or the data didn't have enough information
content for the chosen algorithm.

### §20.3 Resolution Scaled Error (RSE) — NanoJ-SQUIRREL

RSE compares a high-quality reference (the deconvolved or SR image)
against a diffraction-limited reference (the raw widefield) by re-blurring
the high-quality image with the system PSF and computing pixel-wise
absolute difference.

Workflow:

```
NanoJ-SQUIRREL > Calculate Error Map
  reference: raw widefield (or confocal at low-NA)
  super-res: deconvolved stack
```

Output: per-pixel RSE map (low = good agreement) + RSP (Resolution-
Scaled Pearson, 1.0 = perfect).

For deconvolution evaluation, RSE answers: "is the deconvolved image a
linear, photometrically-plausible enhancement of the raw, or has it
hallucinated structure?" Hallucination shows up as bright RSE patches
where the deconvolved image disagrees with the raw blurred-back.

### §20.4 SNR improvement quantification

Practical metric: pick a region of background and a region of signal,
and compute SNR = mean_signal / std_background, before and after
deconvolution. Successful classical deconvolution should:

- Increase SNR by 30–200% (because RL pushes signal into peaks while
  the model treats background as flat Poisson).
- Preserve mean_signal (intensity conservation).

If SNR drops, the deconvolution amplified background more than signal —
check iteration count and PSF normalization.

### §20.5 Eyeball test — ringing, halos, intensity scaling

After every deconvolution, look for:

- **Bright halos** around dark→bright edges (over-iteration, classical).
- **Dark halos** around bright objects on dark background (PSF too broad).
- **Periodic patterns** that weren't there in raw — usually FFT wrap-
  around (need padding) or PSF aliasing.
- **Intensity scaling**: line profile through a known bright structure
  before and after — peak heights should be linearly related (if you're
  claiming photometric accuracy). For RL with mass conservation, total
  intensity in any large ROI should match raw within ±5%.

---

## §21 Common Pitfalls — Detail

§9 has the symptom→fix table. This section is the why-it-happens.

### §21.1 PSF refractive-index mismatch

The single biggest cause of poor deconvolution. The Born–Wolf and
Gibson–Lanni models depend on `n_immersion` and `n_sample`. Using
oil RI (1.518) for a sample mounted in PBS (1.33) gives a PSF that is
narrower than the real one — RL then over-sharpens, producing ringing
and intensity errors.

**Diagnosis**: deconvolved beads from the same dataset show asymmetric
axial profiles (sharp on top, smeared on bottom or vice versa).
**Fix**: use Gibson–Lanni or Variable-RI Gibson–Lanni; supply correct
`n_sample` and the imaging depth; or measure the PSF empirically.

### §21.2 Z-stack range too small

If your stack covers only ±0.5 μm around the structure but the PSF
tails extend 3 μm axially, the algorithm is convolving with a truncated
PSF. This produces axial intensity bleed into the boundary slices.
**Fix**: extend the z-range to ±2× the expected PSF axial FWHM (so for
a typical confocal PSF of ~600 nm axial FWHM, ±1.2 μm beyond the ROI),
even if those planes are mostly empty.

### §21.3 Saturated pixels

Deconvolution assumes a linear forward model (intensity in = some
function of fluorophore density × exposure). Saturation breaks
linearity — those pixels carry no information about how bright they
*should* have been, and RL will push their neighbourhoods toward
nonsense values to "explain" the saturation.

**Detection**:
```javascript
getStatistics(area, mean, min, max);
getDimensions(w, h, c, z, t);
maxAllowed = pow(2, bitDepth()) - 1;
if (max >= maxAllowed) print("WARNING: saturation");
```
**Mitigation**: re-acquire with lower exposure if saturation is in the
ROI. For low-priority saturated regions, mask them to NaN or a large
finite floor before deconvolution; many implementations handle masked
pixels gracefully.

### §21.4 Background subtraction ordering

Two camps: subtract before, or after? In RL the assumption is Poisson
noise on top of a small offset (camera bias). The right order is:

1. **Subtract camera offset** (the dark-frame bias) — this is a
   constant, not background. Use the manufacturer's spec or measure
   from a no-light frame. Typically 100–500 ADU on a CMOS.
2. **Subtract scattered/autofluorescence background** if it's a slowly
   varying field — but do this *gently*, without rolling-ball at small
   radii (cross-link `if-postprocessing-reference.md §3`). Aggressive
   background subtraction before deconvolution removes signal that the
   algorithm needs.
3. **Deconvolve.**
4. **Then any remaining residual subtraction for analysis.**

**Wrong order**: rolling-ball at radius 5 → deconvolve. The aggressive
background removal has already eaten near-edge signal that RL can't
recover.

### §21.5 8-bit input

8-bit data quantizes intensity to 256 levels. RL multiplies by ratios
that can be tiny (`g / (Hf)` near edges). Multiplying tiny numbers by
8-bit values rounds to zero — RL's iterations stagnate or diverge.

**Fix**: convert to 32-bit *before* deconvolution: `run("32-bit");`.
Always.

### §21.6 Title with spaces — DL2 parsing

DeconvolutionLab2 splits its argument string on spaces, so an image
titled "my zstack.tif" breaks parsing.

```javascript
// Defensive: rename before deconvolution
selectWindow("my zstack.tif");
rename("input");
selectWindow("my PSF.tif");
rename("psf");
run("DeconvolutionLab2 Run", " -image platform input -psf platform psf -algorithm RL 15 -out stack output");
selectWindow("output");
rename("my zstack DECONVOLVED.tif");
```

### §21.7 Forgetting to normalize the PSF

If `Σh ≠ 1`, RL doesn't conserve total intensity — the deconvolved
image's mean drifts away from the raw mean. Quick check:

```javascript
selectWindow("PSF");
getRawStatistics(n, mean);
print("PSF integral: " + n*mean);   // should be ~1.0
```

DL2 has `-norm 1` to normalize at runtime; passing it is cheap insurance.

### §21.8 Edge artifacts — FFT wrap-around

Convolution via FFT is circular: the rightmost pixels "wrap" to the
leftmost. If a bright structure is near the edge, the wrap creates
faint copies on the opposite edge, which RL then sharpens into ringing.

**Fix**: `-pad EXTENDED EXTENDED 0 0` reflects the boundary outward
before FFT. Memory cost ~30–50% per dimension; almost always worth it.
`EXPANDED` zero-pads to power-of-2 — fastest FFT but can introduce
artificial dark borders. Default: EXTENDED.

### §21.9 Re-deconvolving an already-deconvolved image

Don't. Twice-deconvolved images explode in noise and ringing. If you
got someone else's "processed" TIFF, ask for raw before deciding what
to do.

### §21.10 Channel cross-talk leaks into "monochromatic" PSF

If your acquisition has spectral bleed (bright DAPI bleeding into the
GFP channel), deconvolving the GFP image with a GFP-wavelength PSF will
treat the DAPI bleed as if it had GFP's PSF — producing intensity
shifts in DAPI-bright regions of the GFP channel. Linear unmixing
before deconvolution is the principled fix; cross-link
`fluorescence-microscopy-reference.md`.

---

## §22 Image Integrity & Publication Disclosure

Full disclosure discipline lives in `image-integrity-reference.md`. The
deconvolution-specific essentials per Nature Portfolio / Cell Press /
EMBO / JCB guidelines:

- **Methods** must name the software, algorithm, and iteration count.
  Example: "Images were deconvolved using DeconvolutionLab2 v2.X (Sage
  et al. 2017) with Richardson–Lucy (25 iterations, EXTENDED padding)
  and a Gibson–Lanni theoretical PSF (PSF Generator v3.X) computed with
  experimental refractive indices and emission wavelengths."
- **Figure legends** must indicate when panels are deconvolved vs raw,
  and disclose any gamma / contrast adjustment.
- **Original raw data** must be available on request; ideally deposited
  to BioImage Archive or EMPIAR.
- **Resolution claims** must state both the acquired resolution and any
  post-processing that altered effective resolution.
- AI-based restoration (CARE, N2V, 3D-RCAN) requires extra disclosure:
  training data origin, model version, and **mandatory side-by-side raw
  comparison** in the figure or supplement. CARE outputs are content-
  aware predictions, not measurements — quantitative claims need
  independent calibration.

Forbidden: showing only deconvolved images and reporting raw resolution;
selective per-panel contrast; re-touching regions; aggressive
background subtraction or cropping that removes inconvenient features
without disclosure.

---

## §23 GPU Deconvolution — Detail

§6 covers CLIJ2's `imageJ2RichardsonLucyDeconvolution`. This section
adds the wider GPU landscape and when to choose what.

### §23.1 CLIJ2 (cross-link clij2-gpu-reference.md)

- One-call op `Ext.CLIJx_imageJ2RichardsonLucyDeconvolution(in, psf, out, n_iter)`.
- Pure RL, no regularization.
- Compatible with chained CLIJ2 pipelines (push raw → RL → label →
  measure all on the GPU). See `clij2-gpu-reference.md §11`.
- VRAM: ~5× image volume in float32. For 1024×1024×100 16-bit raw → 4 GB
  float32 → ~2 GB on GPU after FFT working buffers.

### §23.2 Deconwolf (Wernersson 2024)

- Open-source, OpenCL GPU-accelerated, written in C.
- Algorithm: Richardson–Lucy with Scaled Heavy Ball (SHB) acceleration —
  reaches the same image quality in ~3× fewer iterations than vanilla RL.
- Includes a PSF generation tool (`dw_bw` for Born–Wolf) bundled.
- Chromatic correction: per-channel PSF, optional cross-channel registration.
- Benchmark reported in the paper: 80 iterations in 2 s on AMD Radeon
  RX 6700 XT, 1500× faster than CPU classical RL — practical for batch
  over thousands of fields.
- **Use from agent**: install via CLI, call as subprocess from Python.
  Not (yet) a Fiji plugin, but TIFFs interchange cleanly.

```bash
# Minimal Deconwolf pipeline
dw_bw --NA 1.4 --ni 1.518 --lambda 510 --resxy 65 --resz 200 PSF.tif
dw --iter 60 input.tif PSF.tif output.tif
```

### §23.3 RedLionfish

- Python + napari plugin; PyOpenCL backend (any GPU).
- Auto-falls-back to CPU SciPy FFT if GPU fails or VRAM insufficient.
- Convenient wrapper: `RedLionfish.doRLDeconvolutionFromNpArrays(img, psf, niter)`.

```python
import RedLionfish.RLDeconv3D as rl
import tifffile
img = tifffile.imread("input.tif")
psf = tifffile.imread("psf.tif")
out = rl.doRLDeconvolutionFromNpArrays(img, psf, niter=30, method="gpu")
tifffile.imwrite("output.tif", out)
```

### §23.4 FlowDec

- TensorFlow-based; CUDA only.
- Static graph — fast for batched throughput, painful for one-off.
- Best when the rest of your pipeline is already TensorFlow.

### §23.5 GPU choice criteria

| Need | Choose |
|---|---|
| Fastest single-image classical | Deconwolf |
| Fits in CLIJ2 pipeline | CLIJ2 |
| Pure-Python integration, any GPU | RedLionfish |
| Pure-Python with TF infrastructure | FlowDec |
| Multi-GPU production throughput | Microvolution (commercial) |
| Tied to existing Huygens licence | Huygens GPU |

---

## §24 End-to-End Recipes

Copy-pasteable workflows. Each is run from the agent via `python ij.py
macro` unless noted.

### §24.1 Wide-field 3D RL with measured PSF (DL2)

Goal: classical wide-field z-stack, you have bead data acquired the
same day with the same objective. Aim: confocal-like optical
sectioning + 30% lateral resolution gain.

```javascript
// === STEP 1: prepare measured PSF from bead stack ===
open("/data/beads_widefield_488.tif");
rename("beads_raw");
run("32-bit");
run("Subtract Background...", "rolling=15 stack");
// Detect a single isolated bead. In practice, do this via MetroloJ-QC.
// Manual: pick a bead, crop to 64x64x32 around its centroid.
makeRectangle(245, 312, 64, 64);  // adjust per dataset
run("Duplicate...", "title=psf duplicate range=1-32");
selectWindow("psf");
// Re-centre at sub-pixel in XY and Z (use TrackMate or manual)
// Floor negatives, normalize sum to 1
getRawStatistics(n, mean, min);
if (min < 0) run("Subtract...", "value=" + min + " stack");
getRawStatistics(n, mean);
run("Divide...", "value=" + (n*mean) + " stack");
saveAs("Tiff", "/data/PSF_488_measured.tif");
rename("PSF");

// === STEP 2: prepare sample ===
open("/data/sample_widefield_488.tif");
rename("input");
run("32-bit");
// Optional: subtract camera offset (e.g. 100 ADU)
run("Subtract...", "value=100 stack");

// === STEP 3: deconvolve ===
run("DeconvolutionLab2 Run",
    " -image platform input" +
    " -psf platform PSF" +
    " -algorithm RL 30" +              // wide-field needs more iterations
    " -out stack deconvolved" +
    " -constraint nonnegativity" +
    " -norm 1" +
    " -pad EXTENDED EXTENDED 0 0" +
    " -monitor no -stats save -path /data/decon_logs");

// === STEP 4: quick QC ===
selectWindow("deconvolved");
getRawStatistics(n, mean, min, max);
if (min < 0) print("WARNING: negative pixels in deconvolved");
selectWindow("input"); getRawStatistics(n1, m1); raw_total = n1*m1;
selectWindow("deconvolved"); getRawStatistics(n2, m2); dec_total = n2*m2;
print("Total intensity ratio (decon / raw): " + dec_total/raw_total);
saveAs("Tiff", "/data/sample_widefield_488_decon.tif");
```

### §24.2 Light-sheet anisotropic single-view (theoretical PSF + Iterative Deconvolve 3D)

Goal: light-sheet stack with isotropic XY but blurred Z. No bead data.

```javascript
open("/data/lsfm_sample.tif");
rename("input");
run("32-bit");
getDimensions(w, h, c, z, t);

// Generate theoretical PSF — light-sheet uses two PSFs
// (excitation Gaussian sheet + detection objective). The combined
// detection PSF is approximately Born-Wolf with axial FWHM dominated
// by sheet thickness.
run("Diffraction PSF 3D",
    "type=32-bit" +
    " index=1.333" +     // water dipping
    " numerical=1.1" +   // typical 25x water dipping
    " wavelength=520" +
    " longitudinal=0" +
    " image=130" +       // pixel XY in nm
    " slice=500" +       // sheet thickness ~ Z-step
    " width,=128 height,=128 depth,=" + z +
    " normalization=[Sum of Pixels=1] title=PSF");

// Use Iterative Deconvolve 3D — built-in Wiener pre-filter helps with
// LSFM noise; auto-divergence detection is helpful when PSF is approximate
run("Iterative Deconvolve 3D",
    "image=input" +
    " point=PSF" +
    " output=decon" +
    " show perform detect" +
    " wiener=0.005" +     // mild Wiener pre-filter
    " low=0 z_direction=1" +
    " maximum=25 terminate=0.005");

selectWindow("decon");
saveAs("Tiff", "/data/lsfm_sample_decon.tif");
```

For multi-view LSFM (mvDecon), use BigStitcher's deconvolution module —
documented at `imagej.net/plugins/bigstitcher`.

### §24.3 GPU RL via CLIJ2 (cross-link `clij2-gpu-reference.md`)

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_clear();

open("/data/large_zstack.tif"); rename("input");
open("/data/PSF_measured.tif"); rename("PSF");

Ext.CLIJ2_push("input");
Ext.CLIJ2_push("PSF");
Ext.CLIJ2_getDimensions("input", w, h, d);
Ext.CLIJ2_create3D("decon", w, h, d, 32);
Ext.CLIJx_imageJ2RichardsonLucyDeconvolution("input", "PSF", "decon", 25);
Ext.CLIJ2_pull("decon");
Ext.CLIJ2_release("input");
Ext.CLIJ2_release("PSF");
Ext.CLIJ2_release("decon");

selectWindow("decon");
saveAs("Tiff", "/data/large_zstack_decon.tif");
```

### §24.4 CARE / CSBDeep with a pretrained model

Requires CSBDeep update site enabled. Pretrained models in BioImage
Model Zoo at `bioimage.io`.

```javascript
// Open input
open("/data/low_snr_acquisition.tif");
rename("input");

// Run a pretrained CARE model — this downloads the model on first run
run("CSBDeep > Run your network",
    " modelfile=[/path/to/care_model.bioimage.io.zip]" +
    " input=input" +
    " axes=ZYX" +
    " normalizeInput=true" +
    " percentileBottom=3.0 percentileTop=99.7" +
    " clip=false" +
    " nTiles=4" +
    " blockMultiple=8 overlap=32" +
    " batchSize=1" +
    " batchAxis=no_batch" +
    " channelAxis=no_channel" +
    " convertOutputToInputFormat=true" +
    " showProgressDialog=false");

// Output appears as new window with " - <modelname>" suffix
saveAs("Tiff", "/data/low_snr_acquisition_CARE.tif");
print("WARNING: CARE output is content-aware. Quantitative claims " +
      "require independent calibration. Always show raw + CARE side-by-side.");
```

### §24.5 Batch and chained pipelines

For folders of similarly-acquired files, share one PSF across all and
loop with the standard pattern from `batch-processing-reference.md`.
For very large batches prefer **Deconwolf CLI** or **RedLionfish in a
Python loop** — DL2 has Java startup overhead per call.

### §24.6 Two-step pipeline: N2V denoise → RL deconvolve

For very low-SNR data where neither pure RL nor pure denoising suffices.

1. **Noise2Void denoising** via CSBDeep (cross-link
   `ai-image-analysis-reference.md`). Train a model on the same
   acquisition (no clean targets needed) or use a generic pretrained
   N2V from the BioImage Model Zoo.
2. **Richardson–Lucy with reduced iterations** (cap at 10–15) on the
   denoised stack.

Caveat for figures: the chain is `raw → N2V → RL`. Disclose both. The
final output is two layers of restoration — photometric claims need
extra care.

---

## §25 Agent-Side Decision Helpers

### §25.1 Sniff suitability and recommend

Order of agent checks before recommending deconvolution. Hard refusals
take precedence over modality routing.

| Check | Source | Hard refuse if |
|---|---|---|
| Saturation | `python ij.py histogram` | pile-up > 0.1% at bitDepth max |
| Bit depth | `python ij.py info` → `bitDepth` | 8 (require 32-bit conversion) |
| Z-stack present | `python ij.py info` → `slices` | < 3 (warn < 16) |
| Lossy format | `python ij.py metadata` → `file.format` | jpg / mp4 / gif |
| Modality | derived from metadata or asked | already SR (STED / SIM / STORM) |
| NA / emission / immersion | `python ij.py metadata` | unknown — ask user |

Routing once admissible:

| Modality | Default algorithm | Default iterations |
|---|---|---|
| Wide-field | RL (RLTV if SNR < 10) | 30 |
| Spinning disk | RL | 20 |
| Confocal (good SNR) | RL | 15 |
| Confocal (noisy) | RLTV (λ ≈ 0.001) | 10 |
| Two-photon | RL | 15 |
| Light-sheet (single view) | RL | 25 |
| Light-sheet (multi-view) | BigStitcher mvDecon | 15 |
| Very low SNR (< 5) | N2V denoise → RL (10 iter cap) | — |

### §25.2 Output to AI_Exports next to source image

House rule: never write to arbitrary paths.

```javascript
src_dir = File.getDirectory(getDirectory("image"));
out_dir = src_dir + "AI_Exports/";
File.makeDirectory(out_dir);
saveAs("Tiff", out_dir + getTitle() + "_decon.tif");
saveAs("Text", out_dir + getTitle() + "_decon_params.txt");
```

---

## §26 Cross-References — Where Else to Look

| Topic | Reference |
|---|---|
| Filters before deconvolution (Gaussian, median, BaSiC) | `if-postprocessing-reference.md §3` |
| Auto-thresholding deconvolved output | `if-postprocessing-reference.md §4` |
| GPU deconvolution detail (CLIJ2 pipelines) | `clij2-gpu-reference.md §11` |
| FRC / decorrelation / NanoJ-SQUIRREL | `super-resolution-reference.md §7` |
| STED / SIM / STORM specifics — usually skip RL | `super-resolution-reference.md §11` |
| CARE / CSBDeep / Noise2Void install + use | `ai-image-analysis-reference.md` |
| Light-sheet basics + multi-view fusion | `light-sheet-reference.md` |
| Tissue clearing and RI matching | `light-sheet-reference.md` |
| Image-integrity disclosure rules | `image-integrity-reference.md` |
| Publication figure prep (LUTs, scale bars) | `publication-figures-reference.md` |
| Fluorophore tables, emission peaks | `fluorescence-microscopy-reference.md` |
| Colocalization on deconvolved data | `colocalization-reference.md` |

---

## §27 Sources & Further Reading

### Software
- DeconvolutionLab2 (Sage et al. 2017 *Methods*):
  `bigwww.epfl.ch/deconvolution/deconvolutionlab2/`
- PSF Generator (Kirshner et al. 2013 *J. Microsc.*):
  `bigwww.epfl.ch/algorithms/psfgenerator/`
- Iterative Deconvolve 3D (OptiNav / Dougherty):
  `imagej.net/plugins/iterative-deconvolve-3d`
- Diffraction PSF 3D: `imagej.net/plugins/diffraction-psf-3d`
- Deconwolf (Wernersson et al. 2024 *Nat. Methods*):
  `github.com/elgw/deconwolf`, `nature.com/articles/s41592-024-02294-7`
- CARE / CSBDeep (Weigert et al. 2018 *Nat. Methods*):
  `csbdeep.bioimagecomputing.com`,
  `nature.com/articles/s41592-018-0216-7`
- Noise2Void (Krull et al. 2019 *CVPR*):
  `github.com/juglab/n2v`
- Probabilistic Noise2Void (Krull et al. 2020 *Front. Comput. Sci.*):
  `frontiersin.org/articles/10.3389/fcomp.2020.00005/full`
- FlowDec: `github.com/hammerlab/flowdec`
- RedLionfish (Wellcome Open Res. 2024): `github.com/rosalindfranklininstitute/RedLionfish`,
  `wellcomeopenresearch.org/articles/9-296`
- pyDecon: `github.com/david-hoffman/pyDecon`
- CLIJ2: `clij.github.io/clij2-docs/`
- BigStitcher (multi-view fusion + decon): `imagej.net/plugins/bigstitcher`

### QC / validation
- MetroloJ-QC (Royer et al. 2022 *J. Cell Biol.*):
  `github.com/MontpellierRessourcesImagerie/MetroloJ_QC`,
  `rupress.org/jcb/article/221/11/e202107093`
- PSFj (Theer et al. 2014 *Nat. Methods*): `kuoplab.org/psfj`
- PyCalibrate: `pmc.ncbi.nlm.nih.gov/articles/PMC10651089`
- NanoJ-SQUIRREL (Culley et al. 2018 *Nat. Methods*):
  `henriqueslab.org/pages/nanoj_squirrel`,
  `nature.com/articles/nmeth.4605`

### Algorithms (theory)
- Richardson 1972 *J. Opt. Soc. Am.* 62:55 (RL).
- Lucy 1974 *Astron. J.* 79:745 (RL — same idea, independent).
- Dey, Blanc-Féraud, Zerubia 2006 *Microsc. Res. Tech.* 69:260 (RLTV).
- Beck & Teboulle 2009 *SIAM J. Imaging Sci.* 2:183 (FISTA).
- Stark & Parker 1995 (BVLS).

### Commercial
- Huygens (SVI): `svi.nl/Huygens-Deconvolution`
- AutoQuant: Media Cybernetics
- Microvolution: `microvolution.com`
- Imaris ClearView: `imaris.oxinst.com`

### Editorial / image integrity
- Nature Portfolio: `nature.com/nature-portfolio/editorial-policies/image-integrity`
- Cell Press: `cell.com/figureguidelines`
- JCB: `rupress.org/jcb/pages/editorial-policies`

