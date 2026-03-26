# Fluorescence Microscopy — Scientific Foundations Reference

Reference for an AI agent controlling ImageJ/Fiji: fluorophore properties, resolution,
SNR, spectral overlap, and quantitative methods.

---

## 1. Key Fluorescence Concepts

**Quantum yield (QY)**: photons emitted / photons absorbed. Higher = brighter per molecule.

**Brightness** = extinction coefficient (e) x QY. The single best number for comparing fluorophores.

**Stokes shift** = emission peak - excitation peak (nm). Larger shifts ease filter separation.

**Photobleaching**: irreversible destruction via triplet-state chemistry. Alexa dyes are
typically 5-10x more photostable than FITC/Cy dyes. Mitigate with: minimum excitation power,
anti-fade mounting media (Vectashield, ProLong Gold), photostable fluorophore choices.
In ImageJ: Bleach Correction plugin for time-lapse correction.

---

## 2. Fluorophore Encyclopedia

### 2.1 Nuclear Stains

| Fluorophore | Ex/Em (nm) | Brightness | Permeant? | Use |
|---|---|---|---|---|
| **DAPI** | 360/460 | 15,660 | Fixed only | Standard nuclear counterstain (405 nm laser) |
| **Hoechst 33342** | 350/461 | 26,680 | Live+fixed | Preferred for live nuclear staining |
| **DRAQ5** | 646/681 | 360 | Live+fixed | Far-red, compatible with GFP/RFP channels |
| **Propidium iodide** | 535/617 | 531 | Dead only | Cell-impermeant dead cell stain |
| **SYTOX Green** | 504/523 | 37,500 | Dead only | Bright dead cell stain |
| **TO-PRO-3** | 642/661 | — | Dead only | Very bright far-red dead cell stain |

### 2.2 Chemical Dyes

| Fluorophore | Ex/Em (nm) | Brightness | Laser | Notes |
|---|---|---|---|---|
| **FITC** | 494/519 | 67,890 | 488 | Bright but bleaches fast, pH-sensitive |
| **Cy3** | 550/570 | 22,500 | 561 | High e but low QY |
| **Cy5** | 649/670 | 67,500 | 633 | High e, bleaches faster than Alexa 647 |
| **Texas Red** | 596/615 | 40,800 | 594 | Good photostability |

### 2.3 Alexa Fluor Series

| Fluorophore | Ex/Em (nm) | e (M-1cm-1) | QY | Brightness | Laser | Notes |
|---|---|---|---|---|---|---|
| **Alexa 488** | 495/519 | 73,000 | 0.92 | 67,160 | 488 | Gold standard green. 5-10x more photostable than FITC |
| **Alexa 546** | 556/573 | 104,000 | 0.79 | 82,160 | 561 | Excellent orange, very bright |
| **Alexa 555** | 555/565 | 155,000 | 0.10 | 15,500 | 561 | High e but QY=0.10 -- surprisingly dim |
| **Alexa 568** | 578/603 | 91,300 | 0.69 | 63,000 | 561 | Excellent orange-red |
| **Alexa 594** | 590/617 | 90,000 | 0.66 | 59,400 | 594 | Excellent red |
| **Alexa 647** | 650/665 | 270,000 | 0.33 | 89,100 | 633 | Brightest Alexa, also used in dSTORM |

**Agent rule**: Alexa 555 is inherently dim (QY=0.10). If users report weak orange signal,
recommend Alexa 546 (5x brighter) or Alexa 568 for future experiments.

### 2.4 Fluorescent Proteins

| Protein | Ex/Em (nm) | Brightness | Oligo. | Notes |
|---|---|---|---|---|
| **mTurquoise2** | 434/474 | 27,900 | Mono | Highest QY of any FP. FRET donor |
| **EGFP** | 488/507 | 33,600 | Mono | Standard green reference |
| **mNeonGreen** | 506/517 | 92,800 | Mono | 2.8x EGFP brightness |
| **Venus** | 515/528 | 52,554 | Weak dimer | Fast-folding YFP, FRET acceptor |
| **tdTomato** | 554/581 | 95,220 | Tandem dimer | Brightest red-range, but 54 kDa tag |
| **mScarlet** | 569/593 | 70,000 | Mono | Best monomeric red (4.5x brighter than mCherry) |
| **mCherry** | 587/610 | 15,840 | Mono | Widely used but dim. Photostable |

**Choosing red FPs**: mScarlet for new constructs (bright, monomeric). tdTomato if brightness
is critical and tag size is acceptable. mCherry only when existing constructs require it.

### 2.5 Calcium Indicators

| Indicator | Type | Ex/Em (nm) | Kd (nM) | Notes |
|---|---|---|---|---|
| **Fura-2** | Ratiometric | 340,380/510 | 140-224 | Gold standard ratiometric. UV excitation |
| **Fluo-4** | Single-l | 494/516 | 345 | >100x increase Ca-free to Ca-bound. 488 nm |
| **GCaMP6s** | GECI | 497/515 | 144 | Slow, highest sensitivity. Standard for 2P |
| **GCaMP6f** | GECI | 497/515 | 375 | Fast, resolves individual spikes |
| **jGCaMP8s** | GECI | 497/515 | ~125 | Ultra-sensitive, best single-spike detection |
| **jGCaMP8f** | GECI | 497/515 | ~350 | Ultra-fast, high-frequency firing |
| **jRGECO1a** | GECI | 560/590 | 148 | Red GECI, compatible with GFP/optogenetics |

All GCaMPs: 488 nm (1P) or ~920 nm (2P). GCaMP6s for slow dynamics (circadian);
jGCaMP8f for fast spike resolution.

### 2.6 Two-Photon Brightness

Two-photon brightness = cross-section (sigma2, in GM) x QY. Key values:

| Fluorophore | sigma2 (GM) | Peak 2P (nm) | Notes |
|---|---|---|---|
| **EGFP** | ~40 | ~920 | 2P workhorse |
| **tdTomato** | 100-200 | ~1050 | Brightest red under 2P |
| **mCherry** | 5-15 | ~1050 | Poor 2P -- consider mScarlet or tdTomato |
| **GCaMP6s/f** | 25-50 | ~920 | Standard for in vivo calcium |

2P excitation spectra are NOT simply 1P spectra shifted by 2x. Check measured spectra.

### 2.7 Membrane, Viability & Proliferation Dyes

| Category | Key Dyes | Notes |
|---|---|---|
| **Membrane** | DiI/DiO (tracing), CellMask (quick stain) | DiI: TRITC channel. DiO: FITC channel |
| **Live/Dead** | Calcein AM (green=live) + EthD-1 (red=dead) | Standard LIVE/DEAD kit |
| **Apoptosis** | Annexin V (early), TUNEL (late), Caspase 3/7 reporter | Annexin requires Ca2+ |
| **Proliferation** | EdU + click azide (S-phase), Ki-67 antibody (all cycling) | EdU superior to BrdU |

---

## 3. Optical Resolution

### 3.1 Resolution Formulas

| Formula | Equation | Use |
|---|---|---|
| **Abbe limit** | d = l / (2 x NA) | Fundamental lateral limit |
| **Rayleigh** | d = 0.61 x l / NA | Two-point resolution criterion |
| **Axial (widefield)** | dz = 2 x n x l / NA^2 | Axial resolution |
| **Axial (confocal)** | dz ~ 1.4 x n x l / NA^2 | ~1.4x improvement |
| **Nyquist pixel** | pixel <= d / 2.3 | Minimum sampling |

Where: l = emission wavelength, NA = numerical aperture, n = refractive index of medium.

**Resolution depends on NA, not magnification.** A 100x/1.40 NA has identical resolution
to a 63x/1.40 NA.

### 3.2 Practical Resolution (l=520 nm green)

| Objective | NA | Lateral (nm) | Nyquist pixel (nm) |
|---|---|---|---|
| 10x air | 0.30 | 867 | <=377 |
| 20x air | 0.50 | 520 | <=226 |
| 40x oil | 1.30 | 200 | <=87 |
| 63x oil | 1.40 | 186 | <=81 |
| 100x oil (TIRF) | 1.49 | 174 | <=76 |

### 3.3 Axial vs Lateral (NA=1.40, n=1.515, l=520 nm)

| Modality | Lateral (nm) | Axial (nm) | Ratio |
|---|---|---|---|
| Widefield | 226 | 807 | 3.6:1 |
| Confocal (1 AU) | 190 | 540 | 2.8:1 |
| Two-photon | 300 | 1100 | 3.7:1 |

Axial is always worse (typically 2.5-4x). Objects appear elongated in z.

### 3.4 PSF and Sampling

- **PSF**: 3D blur function. Real image = object convolved with PSF.
- Measured PSFs (sub-resolution beads) give better deconvolution than theoretical.
- **Undersampling** (pixel > Nyquist): information lost, cannot recover.
- **Oversampling** (pixel << Nyquist): more noise per pixel, wastes storage.
- Pixel size from camera: pixel_nm = camera_pixel_um x 1000 / total_magnification.

---

## 4. Signal-to-Noise Ratio

### 4.1 Noise Sources

| Source | Dominates? | Depends on | Reduce by |
|---|---|---|---|
| **Shot noise** (sqrt(N)) | Usually yes | Photon count | More photons (longer exposure, brighter dye) |
| **Read noise** | Low-light | Camera | Better camera, binning |
| **Dark current** | Long exposures | Temperature | Cool camera |
| **Background** | Thick samples | Autofluorescence | Better staining, confocal |

### 4.2 SNR Formula

```
SNR = S / sqrt(S + B + D*t + Nr^2)

Photon-limited: SNR ~ sqrt(S)  (when signal dominates)
                SNR ~ S/sqrt(B) (when background dominates)
```

To double SNR: need 4x photons.

### 4.3 Parameter Effects

| Change | SNR effect | Trade-off |
|---|---|---|
| 2x exposure | 1.4x better | More bleaching |
| 2x2 binning | ~2x better | 2x worse resolution |
| N-frame average | sqrt(N) better | Nx more time/bleaching |

### 4.4 Minimum SNR by Task

| Task | Min SNR |
|---|---|
| Object detection | 3-5 |
| Segmentation | 5-10 |
| Intensity measurement | 10-20 |
| Colocalization | 15+ |
| FRET | 20+ |
| Super-resolution | 50+ per frame |

**Estimate from image**: SNR ~ (mean_signal - mean_background) / stddev_background.

---

## 5. Spectral Overlap & Multi-Channel

### 5.1 Common Bleed-Through

| From -> Into | Bleed-through | Severity |
|---|---|---|
| DAPI -> GFP | 1-5% | Low |
| GFP -> Cy3 | 5-20% | Moderate |
| GFP -> YFP | 30-60% | Very high |
| Alexa 488 -> Alexa 647 | <0.1% | Negligible |

If signal in channel B perfectly co-localises with strong signal in channel A, suspect bleed-through.

### 5.2 Acquisition Modes

| Mode | Cross-talk | Speed |
|---|---|---|
| Simultaneous | Worst | Fastest |
| Sequential (line) | Reduced | 2-4x slower |
| Sequential (frame) | Best | 2-4x slower, drift risk |

Use sequential when fluorophores are <50 nm apart or for quantitative colocalization.

### 5.3 Recommended Panels

| Channels | Recommended Panel | Notes |
|---|---|---|
| 2 | Alexa 488 + Alexa 647 | Maximum separation |
| 3 | DAPI + Alexa 488 + Alexa 647 | Preferred for colocalization |
| 4 | DAPI + Alexa 488 + Alexa 568 + Alexa 647 | Alexa 568 bright, well-separated |
| 5+ | Requires spectral imaging + unmixing | Discuss with user |

**Decision rules**:
- Colocalization? Maximise separation, use sequential acquisition.
- Sample has GFP? Avoid Alexa 488; use Alexa 555/568 + Alexa 647.
- Sample has mCherry? Avoid Alexa 594; use Alexa 488 + Alexa 647.

### 5.4 Incompatible Combinations

| Combination | Problem |
|---|---|
| FITC + Alexa 488 | Same spectrum, cannot separate |
| GFP + YFP | 20 nm apart, massive overlap |
| PI + Texas Red | Heavy overlap |
| Alexa 488 + Alexa 647 | Zero overlap -- perfect pair |

---

## 6. Quantitative Methods

### 6.1 CTCF (Corrected Total Cell Fluorescence)

```
CTCF = IntDen - (Area_cell x Mean_background)
     = Area_cell x (Mean_cell - Mean_background)
```

**Appropriate**: comparing relative expression within same image or fold changes with
consistent settings. **Not appropriate**: comparing across microscopes/sessions without
calibration, or when background is spatially heterogeneous.

```javascript
run("Set Measurements...", "area mean integrated redirect=None decimal=3");
// Measure cell ROI, then background ROI
// CTCF = IntDen_cell - (Area_cell x Mean_bg)
```

### 6.2 FRAP (Fluorescence Recovery After Photobleaching)

```
F(t) = F0 + (Finf - F0) x (1 - e^(-t/tau))
Mobile fraction: Fm = (Finf - F0) / (Fi - F0)
Half-time: t1/2 = 0.693 x tau
Diffusion: D = 0.224 x w^2 / t1/2  (Axelrod, circular spot radius w)
```

| Molecule | D (um2/s) |
|---|---|
| Cytoplasmic GFP | 25-30 |
| Membrane protein | 0.01-0.1 |
| Chromatin-bound | 0.001-0.01 |

### 6.3 FRET (Forster Resonance Energy Transfer)

```
E = 1 / (1 + (r/R0)^6)    R0 = distance where E = 50%
```

| FRET Pair | R0 (nm) |
|---|---|
| CFP/YFP | 4.9 |
| mTurquoise2/Venus | 5.7 |
| GFP/mCherry | 5.1 |
| Alexa 488/Alexa 546 | 6.3 |

Measuring in ImageJ: acceptor photobleaching is simplest (bleach acceptor, measure donor
increase). E = (D_post - D_pre) / D_post.

### 6.4 Ratiometric Imaging

Ratio = F(l1) / F(l2) cancels concentration, excitation, and path length.
Applications: Fura-2 calcium (340/380), BCECF pH (490/440), roGFP redox.

Grynkiewicz equation for absolute [Ca2+]:
```
[Ca2+] = Kd x (R - Rmin) / (Rmax - R) x (F2_free / F2_bound)
```

In ImageJ: threshold background first, then Image Calculator > Divide.

### 6.5 Fold Change

Report fold change (treated/control) rather than absolute intensity. Requirements:
- Control and treated imaged in same session with identical settings
- No saturated or clipped pixels
- Background subtracted consistently
- Multiple biological replicates

---

## 7. Agent Quick Reference

### Image Quality Check
```
1. python ij.py histogram → saturation at max value? clipping at 0?
2. SNR = (signal_mean - bg_mean) / bg_stddev → <3 unreliable, >10 good
3. python ij.py metadata → pixel size vs resolution/2.3 (undersampled?)
4. Illumination uniformity: background profile varies >20% → flat-field correct
5. Time-lapse: mean intensity drops >10% → Bleach Correction plugin
```

### Fluorophore Selection
```
Green IF → Alexa 488
Red IF → Alexa 594 or Alexa 647
Dim orange? → Likely Alexa 555 (QY=0.10), suggest Alexa 546 or 568
Nuclear (fixed) → DAPI
Nuclear (live) → Hoechst 33342
Nuclear (far-red) → DRAQ5
Colocalization → Alexa 488 + Alexa 647, sequential acquisition
```

### Unit Conversions
```
Wavelength to energy: E (eV) = 1240 / l (nm)
Brightness to %EGFP: (e x QY) / 33600 x 100
Pixel size: pixel_nm = camera_pixel_um x 1000 / total_magnification
```
