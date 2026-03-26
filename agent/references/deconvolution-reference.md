# Image Deconvolution Reference

Practical guide to PSF generation, deconvolution algorithms, and workflows in Fiji/ImageJ.

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

## When to Deconvolve (and When Not To)

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

**Warning signs after deconvolution:**

| Sign | Meaning |
|------|---------|
| Ringing (bright/dark halos) | Over-iteration or wrong PSF |
| Checkerboard patterns | Noise amplification in frequency domain |
| Negative values | Unstable algorithm or too many iterations |
| "Crisper" noise | Noise sharpened with signal (over-iteration) |
| Total intensity change | PSF normalization issue |

---

## PSF Generation

### Choosing a PSF Model

| Model | Accounts For | Use When |
|-------|-------------|----------|
| Born-Wolf (scalar) | Uniform RI | Oil objective at coverslip, RI well-matched |
| Gibson-Lanni (stratified) | RI mismatch across 3 layers | Most practical situations (recommended) |
| Richards-Wolf (vectorial) | Polarization | High-NA (>1.2), usually overkill |
| Measured (from beads) | Actual aberrations | Maximum accuracy needed |

### Refractive Index Table

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

### Common Fluorophore Emission Wavelengths

| Fluorophore | Emission (nm) | Fluorophore | Emission (nm) |
|-------------|--------------|-------------|--------------|
| DAPI / Hoechst | 461 | Alexa 594 / mCherry | 617 / 610 |
| Alexa 488 / GFP | 519 / 509 | Alexa 647 / Cy5 | 668 / 670 |
| Alexa 546 / Cy3 | 573 / 570 | Alexa 750 | 775 |

### Nyquist Sampling Requirements

For deconvolution to work, the image should be adequately sampled:
- Lateral: pixel size <= lambda / (4 * NA)
- Axial: z-step <= lambda / (2 * n * (1 - cos(arcsin(NA/n))))

If undersampled, deconvolution may introduce aliasing artifacts.

### Diffraction PSF 3D (Built into Fiji)

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

### PSF Generator Plugin (EPFL)

Supports Gibson-Lanni and Richards-Wolf models. Install via Help > Update > Manage Update Sites > "PSF Generator". Uses custom GUI (no macro recording) — generate interactively and save as TIFF for reuse in automated workflows.

### Measured PSF from Beads

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

### Multi-Channel PSFs

Each channel needs its own PSF (different emission wavelength = different PSF shape). Generate one per channel with the appropriate wavelength, then deconvolve channels independently.

---

## DeconvolutionLab2

The most complete deconvolution plugin for Fiji. 14 algorithms, flexible I/O, padding, apodization.

**Installation**: Typically pre-installed. If not: Help > Update > Manage Update Sites > "DeconvolutionLab2".

### Complete Macro Syntax

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

### Flag Reference

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

### All 14 Algorithms

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

### Algorithm Selection Guide

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

## Iterative Deconvolve 3D

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

## CLIJ2 GPU-Accelerated Deconvolution

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

## Practical Guidelines

### Choosing Iteration Count

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

### Choosing Regularization (lambda)

**RLTV lambda** — controls TV smoothing strength:

| Direction | Effect |
|-----------|--------|
| Higher lambda | Smoother, less noise, may lose fine detail |
| Lower lambda | Sharper, more noise, preserves detail |

Starting point: 0.001 for most fluorescence data. Increase to 0.01 if noisy; decrease to 0.0001 if over-smoothed.

**TM/ICTM lambda**: Similar principle, different scale. Starting point: 0.1.

**Gamma (step size)** for Landweber-family (LW, NNLS, VC, TM, ICTM, BVLS): Start at 1.0. Values >1 risk divergence; <1 slow convergence.

### Padding

Prevents FFT wrap-around edge artifacts. Consider `EXTENDED EXTENDED 0 0` for most situations. Use `NO NO 0 0` only when speed matters and edge regions are unimportant.

Memory impact: EXTENDED adds ~30-50% per dimension.

### Bit Depth

Convert to 32-bit before deconvolution. 8-bit data (0-255) causes quantization artifacts in the iterative process.

```javascript
if (bitDepth() != 32) run("32-bit");
```

### Multi-Channel Workflow

Split channels, generate per-channel PSF (different wavelength), deconvolve independently, merge back:

```javascript
run("Split Channels");
// Generate PSF per channel with appropriate wavelength (see fluorophore table)
// Deconvolve each: run("DeconvolutionLab2 Run", "... -image platform C1-... -psf platform PSF_ch1 ...");
// ...repeat for each channel...
run("Merge Channels...", "c1=Deconv_ch1 c2=Deconv_ch2 c3=Deconv_ch3 create");
```

---

## Quality Assessment

### Post-Deconvolution Checklist

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

## Common Problems and Fixes

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

## Memory Estimation

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

## Alternative Tools

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

## Agent Integration

### Extracting PSF Parameters from Metadata

```bash
python ij.py metadata
# Look for: Objective LensNA, EmissionWavelength, Immersion,
# PhysicalSizeX/Y/Z, NumericalAperture
```

If metadata is incomplete, ask the user for: objective NA, immersion medium, emission wavelength per channel, pixel size XY, z-step size.

### Pre-Deconvolution Checklist

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

### When to Suggest Deconvolution

**Consider suggesting when**: fluorescence z-stack, visible out-of-focus haze, quantitative measurements on z-stacks.

**Do not suggest when**: single plane, brightfield/H&E/phase, JPEG, already super-resolution, user wants quick look.

---

## Troubleshooting Checklist

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
