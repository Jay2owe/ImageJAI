# Image Integrity & Scientific Validity Reference

Standards for microscopy image integrity: journal policies, allowed/forbidden
manipulations, detection methods, reproducibility, and agent workflows.

Sources: Nature, JCB (RUP), Cell Press, Science, eLife, PLOS author policies;
JCB screening guidelines (Rossner & Yamada 2004); QUAREP-LiMi checklists
(Jambor et al., Nature Methods 2023); REMBI metadata framework (Sarkans et al.,
Nature Methods 2021); OME-TIFF / OME-Zarr / Bio-Formats specifications;
ORI forensic guidance; Proofig AI / Imagetwin detection tooling.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py histogram` / `python ij.py metadata` / `python ij.py capture`
— integrity checks before, during, and after processing.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "What does Nature / JCB / Cell Press / Science / eLife / PLOS require?" | §2 |
| "Is linear B/C adjustment allowed? With what conditions?" | §3 allowed |
| "Is Enhance Contrast (normalize) forbidden on quantitative data?" | §3 forbidden, §6 |
| "Is selective region enhancement / clone stamp / splicing allowed?" | §3 forbidden |
| "Is JPEG allowed for microscopy?" | §3 forbidden, §6, §11 |
| "Are AI-generated images allowed?" | §3 forbidden |
| "What integrity checks should I run when opening an image?" | §4 |
| "How do I detect JPEG compression / saturation / histogram gaps / prior Enhance Contrast?" | §4 detection signatures |
| "Macro to detect histogram gaps?" | §4 histogram gap detection macro |
| "How do I check bit depth safely?" | §4 bit depth check |
| "What acquisition settings must match across conditions?" | §5 acquisition consistency |
| "What controls does an IF / live imaging / FRET / calcium / colocalization experiment need?" | §5 required controls |
| "Rules for raw data?" | §5 raw data rules |
| "What triggers journal rejection?" | §6 |
| "How do I prevent red/green overlays / missing scale bars / inconsistent processing?" | §6 |
| "What is QUAREP-LiMi / REMBI / FAIR / OME?" | §7 |
| "What do I report in Methods for acquisition / processing?" | §8 |
| "Figure preparation checklist?" | §8 figure preparation checklist |
| "What forensic tools do journals use?" | §9 journal-side |
| "Pre-submission self-check?" | §9 pre-submission self-check |
| "Full integrity-check macro?" | §10 |
| "Agent workflow before / during / after processing?" | §11 |
| "Documentation template for Methods?" | §11 documentation template |
| "The three non-negotiable rules?" | Three Golden Rules (footer) |
| "Journal's fundamental test for manipulation?" | §3 (JCB test) |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '<term>' image-integrity-reference.md` to jump.

### A

`acquisition consistency` §5 · `acquisition reporting` §8 · `AI-generated images` §3 forbidden ·
`Analyze Particles (see macro section)` §10 · `auto-exposure / auto-gain` §5 ·

### B

`background subtraction` §3 allowed, §8 · `binning` §5 · `Bio-Formats` §7 OME standards ·
`BioImage Archive` §7 FAIR, §7 REMBI · `bit depth` §4 bit depth check, §5 · `black clipping` §4 ·
`brightness/contrast (B/C)` §2, §3 allowed, §6 · `Brightness & Contrast between conditions` §6 ·

### C

`calcium imaging controls` §5 · `calibration` §4, §10 · `Cell Press policy` §2 ·
`channel labels` §6, §8 · `channel merging` §3 allowed · `clone stamp / healing / erasing` §3 forbidden ·
`colocalization controls` §5 · `colorblind-safe palettes` §3 allowed, §6, §8 ·
`comb pattern (histogram)` §4 detection · `compositing / montage` §3 allowed, §8 ·
`cropping` §3 allowed, §6 ·

### D

`deconvolution` §3 allowed, §8 · `detection signatures` §4 · `display range` §6, §8, §11 ·
`documentation template` §11 · `drug treatment controls` §5 · `duplicate image detection` §6, §9 ·
`dynamic range` §4 detection, §10 ·

### E

`eLife policy` §2 · `Enhance Contrast (normalize)` §3 forbidden, §6, §11 · `equipment documentation` §2, §8 ·
`excitation power` §5 · `exposure` §5 · `extension check (.jpg/.jpeg)` §4 detection, §10 ·

### F

`FAIR principles` §7 · `figure preparation checklist` §8 · `filter specs` §8 · `flipping` §8 ·
`forensic tools` §9 · `FRET controls` §5 ·

### G

`gain` §5, §8 · `gamma` §3 forbidden, §4 detection · `Gaussian filter` §3 allowed ·
`getHistogram` §4 · `getPixelSize` §10 · `getRawStatistics` §10 · `Golden Rules` footer ·

### H

`histogram equalization` §3 forbidden, §4 detection · `histogram gaps` §4, §10 ·
`histogram gap detection macro` §4 · `histogram saturation spike` §4 ·

### I

`IF controls` §5 · `Imagetwin` §9 · `immersion` §8 · `integrity verification macro` §10 ·
`intensity data validity (saturation)` §4 · `isotype control` §5 ·

### J

`Jambor et al. 2023` §7 · `JCB policy / RUP` §2, §3, §9 · `JCB test ("is the result still accurate?")` §3 ·
`journal policies` §2 · `JPEG compression` §3 forbidden, §4 detection, §6, §9 ·

### L

`laser wavelengths/power` §8 · `light source` §8 · `linear adjustments` §2, §3 allowed, §8 ·
`linear intensity-concentration relationship` §6 · `live imaging controls` §5 ·
`low dynamic range` §4 detection · `LUT (pseudo-coloring)` §3 allowed ·

### M

`Magenta/Green` §6, §8 · `median filter` §3 allowed · `metadata (REMBI)` §7 · `methods reporting` §8 ·
`microscope manufacturer/model` §8 · `MIP / Max Intensity / Sum / Average / Median projection` §3 allowed, §8 ·
`mounting medium` §5 ·

### N

`Nature policy` §2 · `no-primary control` §5 · `non-linear adjustments` §2, §3 forbidden, §8 ·
`non-representative images` §6 · `non-transfected control` §5 ·

### O

`objective (mag/NA/immersion)` §5, §8 · `OME-TIFF / OME-Zarr` §5, §7 · `ORI Forensic Droplets` §9 ·
`Otsu / thresholding (reporting)` §8 · `overlays (red/green)` §6, §8 ·

### P

`panel labels` §11 · `PLOS policy` §2 · `pinhole` §5, §8 · `pixel size` §5, §10 ·
`Proofig AI` §2, §9 · `pre-submission self-check` §9 · `pseudo-coloring` §3 allowed ·
`PSF (deconvolution)` §3 allowed, §8 ·

### Q

`QUAREP-LiMi` §7 · `quantification data (Enhance Contrast forbidden)` §3 forbidden, §6 ·
`quantification controls` §5 ·

### R

`raw data preservation` §2, §5, §7 · `red/green overlays` §6, §8 · `REMBI` §7 ·
`reusing same field in different figures` §6 · `rotation` §3 allowed · `rolling ball background` §3 allowed ·
`Rossner & Yamada (JCB test)` §3 ·

### S

`saturation` §4 detection, §5, §10 · `Sarkans et al. 2021` §7 · `scale bar` §2, §6, §8, §9 ·
`Science policy` §2 · `secondary-only control` §5 · `selective cropping` §6 ·
`selective region enhancement` §3 forbidden · `setMinAndMax` §3 allowed, §6, §11 ·
`single-labeled control (bleed-through)` §5 · `splicing without demarcation` §3 forbidden ·
`splicing with demarcation` §3 allowed, §6 · `stitching` §6, §7 · `supplement (custom code)` §8 ·

### T

`TIFF (archival / export)` §5, §6, §8, §9, §11 · `thresholding reporting` §8 ·
`time-lapse (intervals, duration)` §8 · `troubleshooting rejection` §6 ·

### U

`uniform adjustments` §2, §3 allowed, §6 · `universal requirements` §2 ·

### V

`vehicle-only control` §5 · `visibility boundaries (composite)` §8 ·

### Z

`Z-projection / Z-stack / z-step` §3 allowed, §5, §8 · `Zero controls (Rmin/Rmax)` §5 ·

---

## §2 Journal Policies Summary

| Journal | Key Rules | Screening |
|---|---|---|
| **Nature** | Linear B/C uniform to entire image + controls. Non-linear requires disclosure. Editors can request raw data. Full equipment documentation in Methods. | Manual expert review |
| **JCB (RUP)** | Pioneer: screens ALL images since 2002. Test: "Is the result still accurate?" Raw data required on request. | ORI tools + expert review |
| **Cell Press** | B/C applied to ENTIRE image equally. Scale bar on ALL images. No AI-generated/altered images. | Manual review |
| **Science** | Linear adjustments uniform to entire image/plate. Non-linear must be in figure legend. Composites need lines between parts. | Proofig AI (all 6 journals) |
| **eLife** | Uniform adjustments across image + controls. Screens all submissions. | Routine screening since ~2020 |
| **PLOS** | No background "cleaning". Spliced fragments only from same image with demarcation. 300+ DPI. | Manual review |

**Universal requirements**: disclose all processing in Methods, preserve raw data,
identical processing across compared conditions.

---

## §3 Allowed vs Forbidden Manipulations

### Allowed (with disclosure)

| Operation | Conditions | ImageJ Notes |
|---|---|---|
| Linear B/C adjustment | Uniform to entire image + all controls | `setMinAndMax()` (display only) |
| Cropping | Context preserved, sufficient resolution retained | Document in legend |
| Pseudo-coloring (LUT) | Document LUT, include calibration bar if quantitative | Use colorblind-safe palettes |
| Channel merging | Show individual channels alongside merge | Label channels by name, not colour |
| Compositing (montage) | Visible lines between separate acquisitions | Never imply continuity |
| Background subtraction | Uniform method, same parameters across conditions | Document method + radius |
| Gaussian/median filter | Justified, uniform, documented | Disclose sigma/radius |
| Deconvolution | Proper PSF, documented algorithm + iterations | Report software, PSF source |
| Z-projection | Document type (MIP/average/sum) and slice range | State which slices |
| Rotation | For presentation clarity | Nearest-neighbour for quantitative data |

### Forbidden

| Operation | Why |
|---|---|
| Selective region enhancement | Misrepresents data |
| Clone stamp / healing / erasing | Creates/removes data elements |
| Splicing without demarcation | Implies false continuity |
| Enhance Contrast (normalize) on quantitative data | Permanently destroys pixel values |
| JPEG for microscopy data | Lossy compression alters pixel values |
| AI-generated image content | Explicitly forbidden by Cell Press, discouraged by all |
| Non-linear adjustments without disclosure | Gamma, log, histogram equalization |
| Different processing between compared conditions | Invalidates comparison |

**The fundamental test (JCB)**: "Is the resulting image still an accurate
representation of the original data?"

---

## §4 Integrity Checks the Agent Should Perform

### On every image open

```bash
python ij.py metadata   # Flag: JPEG format, missing calibration, RGB
python ij.py histogram   # Flag: saturation, clipping, comb pattern
python ij.py capture     # Visual inspection
```

### Detection signatures

| Issue | Detection Method |
|---|---|
| **JPEG compression** | Comb pattern in histogram (periodic empty bins), 8x8 block artifacts at high zoom, .jpg/.jpeg extension |
| **Saturation** | Spike at max value (255/4095/65535). Intensity data INVALID if >0.1% saturated |
| **Black clipping** | Spike at 0 beyond expected background |
| **Prior Enhance Contrast** | Histogram gaps ("comb" from value interpolation) |
| **Non-linear adjustment** | Histogram compressed one side / stretched other (gamma), artificially flat (equalization) |
| **Clone/copy regions** | Repeated noise patterns, unnaturally uniform backgrounds, edge discontinuities |
| **Low dynamic range** | Histogram uses <10% of available range -- likely underexposed |

### Histogram gap detection macro

```javascript
getHistogram(values, counts, 256);
gapCount = 0;
for (i = 1; i < 254; i++) {
    if (counts[i] == 0 && counts[i-1] > 0 && counts[i+1] > 0) gapCount++;
}
if (gapCount > 10) print("WARNING: " + gapCount + " histogram gaps -- prior processing suspected");
```

### Bit depth check

```javascript
bd = bitDepth();
if (bd == 8) print("WARNING: 8-bit -- verify not converted from 16-bit");
if (bd == 24) print("WARNING: RGB -- not suitable for quantification");
```

---

## §5 Best Practices for Quantitative Microscopy

### Acquisition consistency

All images in an experiment must match:

| Must match | Also recommended |
|---|---|
| Objective, excitation power, exposure, gain, offset, binning, bit depth, pinhole, z-step, pixel size | Temperature, mounting medium |

Lock ALL settings. No auto-exposure/auto-gain. Verify no saturation on brightest
sample and signal visible on dimmest. Acquire all conditions in same session.

### Required controls

| Experiment | Controls |
|---|---|
| IF | No-primary, isotype, secondary-only |
| Live imaging | Non-transfected, empty vector |
| Drug treatment | Vehicle-only (same solvent/volume) |
| Colocalization | Single-labeled (bleed-through check) |
| FRET | Donor-only, acceptor-only |
| Calcium imaging | Rmin/Rmax calibration, background ROI |
| Quantification | Calibration beads/slides |

### Raw data rules

- NEVER modify originals. Work on duplicates.
- Preserve original format (contains irreplaceable metadata).
- Save OME-TIFF for archival.

---

## §6 Common Mistakes That Trigger Rejection

| Mistake | Why caught | Prevention |
|---|---|---|
| **Different B/C between conditions** | Screeners compare raw histograms | Set display once: `setMinAndMax(min, max)` for all panels |
| **Reusing same field in different figures** | Proofig/Imagetwin detect duplicates (rotated/cropped) | Use independent images; state if reused |
| **Selective cropping** | Reviewers request full field | Choose representative fields; provide full-field as supplement |
| **JPEG compression** | Comb histogram, 8x8 blocks trivially detected | Always TIFF or PNG |
| **Missing scale bars** | 10-29% of papers lack scale info | `run("Scale Bar...", "width=50 height=5 color=White location=[Lower Right] bold overlay");` |
| **Missing channel labels** | Journals require individual channels shown | Show channels alongside merge, label by name |
| **Inconsistent processing** | Experienced microscopists spot visual inconsistencies | Write macro, apply to ALL images |
| **Enhance Contrast on quantification data** | Destroys linear intensity-concentration relationship | Use `setMinAndMax()` for display only |
| **Non-representative images** | Should match average of quantitative data | If quant shows 1.5x, image should suggest ~1.5x |
| **Red/green overlays** | ~8% of males cannot distinguish | Use Magenta/Green or Cyan/Yellow |
| **Undisclosed stitching** | Must state in legend | "Stitched composite of N fields using [method]" |

---

## §7 Reproducibility Standards

### QUAREP-LiMi

Community initiative (700+ members) for microscopy QC standards. Published checklists
(Jambor et al., Nature Methods 2023) covering: image formatting, colour selection,
annotations, analysis workflows, data availability. Three levels: Minimal, Recommended, Ideal.

### REMBI Metadata Framework

35 metadata items (Sarkans et al., Nature Methods 2021): study, specimen, acquisition,
image, processing, analysis. Used by BioImage Archive (EMBL-EBI).

### FAIR Principles

| Principle | Implementation |
|---|---|
| Findable | Public archive (BioImage Archive, IDR), DOI, rich metadata |
| Accessible | Open access, persistent identifiers |
| Interoperable | OME-TIFF or OME-Zarr, REMBI metadata |
| Reusable | Complete metadata, shared pipelines, raw data preserved |

### OME Standards

- **OME-TIFF**: open format with metadata in XML header
- **OME-Zarr**: cloud-native chunked format for large datasets
- **Bio-Formats**: reads 150+ proprietary formats, extracts OME metadata

---

## §8 Methods Reporting Checklists

### Acquisition (report ALL)

- Microscope manufacturer/model, light source, objective (mag/NA/immersion)
- Filter specs (manufacturer, catalogue numbers)
- Detector type, exposure, gain, offset, binning
- Laser wavelengths/power, pinhole (confocal), scan speed, averaging
- Z-stack: step size, volume, slices. Time-lapse: intervals, duration
- Sequential vs simultaneous. Environmental conditions. Software + version.

### Processing (report ALL steps)

- Software + version (cite open-source tools)
- Background subtraction: algorithm, parameters
- Deconvolution: software, algorithm, PSF source, iterations
- Thresholding: method or manual value
- Display: min/max per channel, state if linear
- Non-linear adjustments: MUST appear in figure legend
- Custom code: provide as supplement

### Figure preparation checklist

- [ ] Scale bar on every micrograph (value + unit in legend)
- [ ] Individual channels shown alongside merge
- [ ] Channels labeled by name (not just colours)
- [ ] Colorblind-safe colour scheme (Magenta/Green, not Red/Green)
- [ ] Visible boundaries between composited parts
- [ ] Display range stated
- [ ] TIFF or PNG format, 300+ DPI at print size
- [ ] No inadvertent flipping

---

## §9 Forensic Tools

### Journal-side

| Tool | Used by | Detects |
|---|---|---|
| **Proofig AI** | Science (all 6), Wiley | Duplication, manipulation, splicing |
| **Imagetwin** | ASM Journals | Duplication, AI-generated content |
| **ORI Forensic Droplets** | JCB | Erasures, edge anomalies |

### Pre-submission self-check

1. Smooth histogram per panel (no comb, no unexpected clipping)
2. All microscopy as TIFF/PNG
3. Identical display ranges across compared conditions
4. No reused images across figures
5. Every step documented in Methods
6. Scale bar on every micrograph
7. Individual channels shown for fluorescence
8. No red/green-only combinations

---

## §10 Integrity Verification Macro

```javascript
macro "Image Integrity Check" {
    title = getTitle();
    print("=== INTEGRITY CHECK: " + title + " ===");

    // Format
    info = getInfo("image.filename");
    if (endsWith(toLowerCase(info), ".jpg") || endsWith(toLowerCase(info), ".jpeg"))
        print("FAIL: JPEG format");
    else print("PASS: Format OK");

    // Bit depth
    bd = bitDepth();
    if (bd == 8) print("WARNING: 8-bit -- verify original");
    if (bd == 24) print("WARNING: RGB -- not for quantification");

    // Saturation
    getRawStatistics(nPixels, mean, min, max, std);
    getHistogram(values, counts, 256);
    if (bd == 8) {
        satPct = (counts[255] / nPixels) * 100;
        if (satPct > 0.1) print("WARNING: " + d2s(satPct,2) + "% saturated");
        else print("PASS: No saturation");
    }

    // Histogram gaps
    gapCount = 0;
    for (i = 1; i < 254; i++)
        if (counts[i] == 0 && counts[i-1] > 0 && counts[i+1] > 0) gapCount++;
    if (gapCount > 10) print("WARNING: " + gapCount + " histogram gaps");
    else print("PASS: Histogram OK");

    // Calibration
    getPixelSize(unit, pw, ph);
    if (unit == "pixels") print("WARNING: Not spatially calibrated");
    else print("PASS: " + d2s(pw,4) + " " + unit + "/pixel");

    // Dynamic range
    maxPossible = pow(2, bd) - 1;
    if (bd <= 16) {
        rangePct = ((max - min) / maxPossible) * 100;
        if (rangePct < 10) print("WARNING: Low dynamic range (" + d2s(rangePct,1) + "%)");
    }
    print("=== END ===");
}
```

---

## §11 Agent Integrity Workflow

### Before processing
```bash
python ij.py histogram  # record original min/max/mean
python ij.py info        # record dimensions, bit depth
# NEVER: Enhance Contrast with normalize, 16->8 bit without documentation, gamma without consent
```

### During processing
- Apply identical parameters to all compared images
- Record every parameter
- Capture + check histogram after each major step

### Before figure export
```bash
# Verify: identical display ranges, scale bars, channel labels, panel labels
# Export as TIFF or PNG (NEVER JPEG)
python ij.py macro 'saveAs("PNG", "/path/to/Figure1.png");'
```

### Documentation template
```
PROCESSING SUMMARY
Software: Fiji/ImageJ v[X.Y.Z], Plugins: [list]
Pixel size: [from calibration], Bit depth: [from info]
Steps: 1. [step]: [params]  2. [step]: [params] ...
Display: Channel [X]: min=[N], max=[N] (linear), identical across panels
Measurements on: [raw/processed] data. No non-linear adjustments applied.
```

---

## Three Golden Rules

1. **Never modify originals.** Work on copies. Preserve raw data.
2. **Apply everything uniformly.** Same settings, same processing, same display range.
3. **Document everything.** Every step, parameter, and version in Methods.
