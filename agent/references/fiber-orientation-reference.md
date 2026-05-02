# Fiber & Orientation Analysis Reference

Orientation, alignment, and morphometry of fibrillar structures in ImageJ/Fiji.
Covers OrientationJ (structure tensor), Directionality (FFT), FibrilTool,
AnalyzeSkeleton, DiameterJ, circular statistics, and agent workflows.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript.
Probe any plugin's dialog with `python probe_plugin.py "Plugin Name..."`.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Which tool should I use for direction / alignment / diameter?" | §2 |
| "What does `tensor=` (sigma) do in OrientationJ?" | §3.2 |
| "What gradient index should I pass to OrientationJ?" | §3.3 |
| "What's the macro syntax for `OrientationJ Analysis` / `Distribution` / `Measure` / `Vector Field` / `Dominant Direction` / `Corner Harris`?" | §3.4 |
| "What coherency value counts as 'aligned'?" | §3.5 |
| "How do I run OrientationJ on a z-stack?" | §3.6 |
| "How do I run `Directionality` from a macro?" | §4 |
| "FibrilTool vs OrientationJ — which per-ROI tool?" | §5 |
| "How do I measure fiber diameter and length?" | §7 |
| "How do I compute tortuosity, fiber density, inter-fiber spacing?" | §7 |
| "Which alignment metric should I report (coherency, nematic S, dispersion…)?" | §8 |
| "Preprocessing decision tree / colour deconvolution for histology" | §9 |
| "End-to-end agent workflow for fiber orientation" | §10 |
| "How do I batch OrientationJ over a folder?" | §11 |
| "Why can't I use linear mean on angles?" | §12 |
| "Rayleigh / Watson-Williams / von Mises test code" | §12 |
| "SHG-specific analysis notes and TACS classification" | §13 |
| "What do I need to put in the methods section?" | §14 |
| "Feature-by-feature comparison of all five tools" | §15 |
| "Copy-paste macro snippets for every plugin" | §16 |
| "Sigma lookup by structure and magnification" | §16 |
| "Before / parameters / after checklist" | §17 |
| "Background dominates histogram / coherency looks like edge detection / etc." | §18 |
| "Real argument keys for an installed plugin" | `python probe_plugin.py "Plugin Name..."` — not here |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '`<term>`' fiber-orientation-reference.md` to jump.

### A

`alignment metrics` §8 · `AnalyzeSkeleton` §7 · `Analyze Particles` §10 · `angle (axial)` §12 · `anisotropy` §3.1, §5, §8 · `astropy.stats` §12 · `axial data conversion` §12

### B

`Batch OrientationJ` §11 · `BIG-EPFL (update site)` §3, §18 · `Boudaoud` §5 · `branch length` §7 · `branching` §8, §15

### C

`circular mean / SD / variance` §12 · `circular statistics` §12 · `CLAHE` §18 · `coherency (definition)` §3.1 · `coherency (interpretation)` §3.5 · `coherency (comparing groups)` §12 · `coherency map` §3.4, §15 · `colour deconvolution` §9 · `collagen` §3.2, §13, §15, §16 · `color survey` §3.4, §14 · `Corner Harris` §3.4 · `CT-FIRE` §13 · `cubic spline (gradient)` §3.3 · `CurveAlign` §13

### D

`decision tree (preprocessing)` §9 · `decision tree (tool selection)` §15 · `DiameterJ` §6, §15 · `diameter (fiber)` §7, §8, §15 · `Directionality` §4, §15 · `dispersion` §4, §8 · `display_table` §4, §18 · `Distance Map` §7 · `Dominant Direction` §3.4 · `Duplicate` §10

### E

`electrospun nanofibers` §3.2 · `Enhance Local Contrast` §18 · `energy (definition)` §3.1 · `energy map (masking)` §9 · `Euclidean distance` §7 · `exclude edges` §9

### F

`FFT (Fourier components)` §4 · `fiber density` §7, §8 · `fiber diameter` §7, §8, §15 · `fiber length` §7, §8, §15 · `FibrilTool` §5, §15 · `figure guidelines` §14 · `finite difference (gradient)` §3.3 · `FIRE` §13 · `Fourier (gradient)` §3.3

### G

`Gaussian Blur` §7, §9 · `gradient methods` §3.3 · `goodness of fit (R-squared)` §4

### H

`H&E` §9 · `harris-index` §3.4 · `histogram entropy` §8 · `histogram (orientation)` §3.4, §4, §14 · `hue / sat / bri` §3.4 · `hypothesis tests (circular)` §12

### I

`installation (OrientationJ)` §3 · `installation (DiameterJ)` §6 · `intersection density` §6, §15 · `inter-fiber spacing` §7, §8, §15 · `Invert` §9 · `isotropic` §3.1, §3.5, §8

### J

`junctions` §7, §8

### K

`kappa (von Mises)` §12 · `Kuiper's V` §12

### L

`Local gradient orientation` §4 · `Local Thickness` §2, §6, §7, §8, §10 · `Longest Shortest Path` §7

### M

`magnification (sigma lookup)` §16 · `Masson Trichrome` §9 · `Measure (OrientationJ)` §3.4 · `Median` §9 · `methods section checklist` §14 · `microtubules` §3.2 · `min-coherency` §3.4, §18 · `min-energy` §3.4, §18 · `morphology metrics` §8 · `muscle fibers` §3.2, §15

### N

`nanofiber` §3.2, §6, §15 · `nbins` §4 · `nematic order S` §8 · `nematic tensor` §5

### O

`OJ-Coherency-1 / OJ-Orientation-1 / OJ-Energy-1 / OJ-Color-survey-1` §3.4 · `Open (binary)` §7 · `orientation (definition)` §3.1 · `OrientationJ` §3, §15 · `OrientationJ Analysis` §3.4 · `OrientationJ Corner Harris` §3.4 · `OrientationJ Distribution` §3.4 · `OrientationJ Dominant Direction` §3.4 · `OrientationJ Measure` §3.4 · `OrientationJ Vector Field` §3.4 · `Otsu` §7, §9

### P

`per-branch table` §7 · `per-skeleton table` §7 · `pore analysis` §6, §15 · `prune` §7 · `pycircstat2` §12

### Q

`Quick Macro Reference` §16 · `quick start (coherency map)` §2 · `quick start (diameter / length)` §2 · `quick start (orientation histogram)` §2

### R

`R-bar (mean resultant length)` §12 · `Rayleigh test` §12 · `reporting standards` §14 · `Riesz (gradient)` §3.3 · `ROI (edge exclusion)` §9 · `roiManager` §3.4 · `R-squared (goodness)` §4

### S

`s-color-survey / s-distribution / s-mask` §3.4 · `scipy.stats` §12 · `SEM` §3.2 · `Set Measurements` §3.4, §7 · `SHG (second harmonic generation)` §13 · `Shapiro-Wilk` §12 · `sigma (choosing)` §3.2 · `sigma lookup table` §16 · `Skeletonize` §2, §7 · `SNR` §2, §9, §18 · `Split Channels` §9 · `stacks (OrientationJ)` §3.6 · `stress fibers` §3.2, §16 · `structure tensor` §3, §3.1 · `Subtract Background` §7, §9

### T

`TACS` §13 · `tendons` §3.5 · `tensor=` §3.4 · `tortuosity` §7, §8, §15 · `Triple/Quadruple points` §7 · `tumor boundary` §13

### U

`update site (BIG-EPFL)` §3 · `update site (DiameterJ)` §6

### V

`Vector Field` §3.4, §14 · `vector-color / vector-grid / vector-scale / vector-type` §3.4 · `vectors=[Masson Trichrome]` §9 · `von Mises` §4, §12

### W

`Watson's U-squared` §12 · `Watson-Williams F-test` §12 · `white matter tracts` §3.2 · `wound healing` §15

### Z

`z-stacks` §3.6

---

## §2 Tool Selection

| Question | Tool |
|----------|------|
| What direction are fibers? | OrientationJ Distribution |
| How aligned are they? | OrientationJ Analysis (coherency) |
| Dominant angle? | OrientationJ Dominant Direction |
| Spatial alignment variation? | OrientationJ Vector Field |
| FFT-based direction histogram with fit? | Directionality |
| Mean orientation per cell/ROI? | FibrilTool |
| Fiber diameter? | Skeletonize + Local Thickness |
| Fiber length and branching? | AnalyzeSkeleton |
| Complete nanofiber characterization? | DiameterJ |

### Quick start: orientation histogram

```
python ij.py macro 'open("/path/to/fibers.tif"); run("8-bit");'
python ij.py macro 'run("OrientationJ Distribution", "tensor=3.0 gradient=0 histogram=on min-coherency=10.0 min-energy=5.0 ");'
python ij.py capture orientation_histogram
```

### Quick start: coherency map

```
python ij.py macro 'open("/path/to/fibers.tif"); run("8-bit");'
python ij.py macro 'run("OrientationJ Analysis", "tensor=3.0 gradient=0 color-survey=on hue=Orientation sat=Coherency bri=Original-Image orientation=on coherency=on energy=on ");'
python ij.py capture coherency_map
```

### Quick start: fiber diameter and length

```
python ij.py macro 'open("/path/to/fibers.tif"); run("8-bit");'
python ij.py macro 'setAutoThreshold("Otsu"); run("Convert to Mask");'
python ij.py macro 'run("Skeletonize"); run("Analyze Skeleton (2D/3D)", "prune=[none] calculate show");'
python ij.py results
```

### Prerequisites

- Single-channel grayscale (split channels if multi-channel)
- Fibers typically at least 2-3 px wide for structure tensor; use FFT for thinner
- Adequate SNR (denoise first if noisy)
- Reasonably flat illumination (subtract background if uneven)

---

## §3 OrientationJ Complete Reference

**Installation:** Fiji > Help > Update > Manage Update Sites > check "BIG-EPFL" > Apply > Restart.

### §3.1 Structure tensor outputs

OrientationJ computes the structure tensor at each pixel using a Gaussian window (sigma parameter):

| Output | Range | Meaning |
|--------|-------|---------|
| Orientation | -90 to +90 deg | Dominant local direction (0=horizontal, 90=vertical). Axial (undirected). |
| Coherency | 0 to 1 | Anisotropy: 0=isotropic, 1=perfectly aligned |
| Energy | 0+ | Gradient magnitude: high=structure, low=background |

### §3.2 Choosing sigma (critical)

Sigma controls the Gaussian window size. Set it to approximately the fiber width in pixels.

| Sigma vs fiber width | Effect |
|---------------------|--------|
| Much smaller | Detects fiber edges, not direction |
| Approximately equal | Optimal for fiber direction |
| 2-3x larger | Smooths over gaps; good for noisy images |
| Much larger | Over-smooths, loses angular detail |

**How to choose:** Measure a fiber width with the line tool, start with sigma equal to that width, then try sigma/2 and sigma*2. Pick whichever gives high coherency on fibers and low coherency on background.

**Typical starting points by application:**

| Application | Typical fiber width (px) | Starting sigma |
|-------------|------------------------|----------------|
| Collagen (SHG, 20x) | 3-8 | 3-5 |
| Actin stress fibers | 2-5 | 2-3 |
| Microtubules | 1-3 | 1-2 |
| Muscle fibers | 10-50 | 10-20 |
| Electrospun nanofibers (SEM) | 5-20 | 5-10 |
| White matter tracts | 10-30 | 10-15 |

### §3.3 Gradient methods

| Index | Method | When to use |
|-------|--------|-------------|
| 0 | Cubic Spline | Default, good for most images |
| 1 | Finite Difference | Fast, high-SNR images |
| 2 | Fourier | Periodic structures, large images |
| 3 | Riesz | Isotropic gradient |
| 4 | Gaussian | Noisy images |

### §3.4 All OrientationJ plugins — macro syntax

#### OrientationJ Analysis

Produces per-pixel orientation, coherency, energy maps and color survey.

```
run("OrientationJ Analysis",
    "tensor=SIGMA gradient=GRADIENT_INDEX "
    + "color-survey=on "
    + "hue=Orientation sat=Coherency bri=Original-Image "
    + "orientation=on coherency=on energy=on "
    + "s-distribution=on s-color-survey=on s-mask=on ");
```

**Parameters:**
- `tensor=SIGMA` — Gaussian window sigma (float)
- `gradient=GRADIENT_INDEX` — 0-4 (see table above)
- `color-survey=on` — HSB color survey (hue=orientation, sat=coherency, bri=original)
- `hue/sat/bri` options: `Orientation`, `Coherency`, `Energy`, `Constant`, `Original-Image`
- `orientation=on`, `coherency=on`, `energy=on` — output maps (32-bit)
- `s-distribution=on`, `s-color-survey=on`, `s-mask=on` — separate windows

**Output windows:** `OJ-Orientation-1` (degrees), `OJ-Coherency-1` (0-1), `OJ-Energy-1`, `OJ-Color-survey-1` (RGB)

**Measuring mean coherency:**
```
python ij.py macro '
  selectWindow("OJ-Coherency-1");
  run("Set Measurements...", "mean standard min redirect=None decimal=4");
  run("Measure");
'
python ij.py results
```

#### OrientationJ Distribution

Weighted orientation histogram, filtered by coherency and energy thresholds.

```
run("OrientationJ Distribution",
    "tensor=SIGMA gradient=GRADIENT_INDEX "
    + "radian=on histogram=on "
    + "min-coherency=MIN_COH min-energy=MIN_EN ");
```

- `min-coherency` (0-100%): Starting point 10-20 (most structures); 30-50 (strict)
- `min-energy` (0-100%): Starting point 5-10 (excludes background); 20-30 (strict)
- Increase thresholds if background dominates the histogram

#### OrientationJ Measure

Per-ROI orientation/coherency/energy. Results in Log window (tab-separated).

```
run("OrientationJ Measure", "sigma=SIGMA");
```

For multiple ROIs:
```
python ij.py macro '
  count = roiManager("count");
  for (i = 0; i < count; i++) {
      roiManager("select", i);
      run("OrientationJ Measure", "sigma=3.0");
  }
'
python ij.py log
```

#### OrientationJ Vector Field

Overlays direction arrows on a grid.

```
run("OrientationJ Vector Field",
    "tensor=SIGMA gradient=GRADIENT_INDEX "
    + "vector-type=VTYPE vector-grid=GRID vector-scale=SCALE "
    + "vector-color=COLOR ");
```

- `vector-type`: 0=lines, 1=filled arrows
- `vector-grid`: spacing in pixels
- `vector-scale`: length as percentage
- `vector-color`: 0=orientation-coded, 1=white, 2=black, 3=red, 4=green, 5=blue, 6=yellow, 7=cyan

**Note:** Known macro bug in some versions may produce empty overlay. Probe first: `python probe_plugin.py "OrientationJ Vector Field"`

#### OrientationJ Dominant Direction

Single dominant angle for the image/ROI. Result in Log.

```
run("OrientationJ Dominant Direction", "tensor=SIGMA gradient=GRADIENT_INDEX ");
```

#### OrientationJ Corner Harris

Detects fiber crossings/intersections.

```
run("OrientationJ Corner Harris",
    "tensor=SIGMA gradient=GRADIENT_INDEX "
    + "harris-index=0.05 min-coherency=MIN_COH min-energy=MIN_EN ");
```

### §3.5 Interpreting coherency values

| Coherency | Interpretation |
|-----------|---------------|
| 0.0 - 0.1 | Isotropic / no alignment |
| 0.1 - 0.3 | Weak alignment |
| 0.3 - 0.5 | Moderate alignment |
| 0.5 - 0.7 | Strong alignment |
| 0.7 - 1.0 | Very strong (tendons, aligned scaffolds) |

### §3.6 Stacks

OrientationJ is 2D only. For z-stacks, either project first or iterate slices:

```
python ij.py macro '
  getDimensions(w, h, c, z, t);
  for (s = 1; s <= z; s++) {
      setSlice(s);
      run("OrientationJ Measure", "sigma=3.0");
  }
'
python ij.py log
```

---

## §4 Directionality Plugin

**Menu:** Analyze > Directionality (bundled with Fiji)

### Methods

| Method | Macro syntax | Best for |
|--------|-------------|----------|
| FFT | `method=[Fourier components]` | Periodic/regular patterns, noisy images |
| Local gradient | `method=[Local gradient orientation]` | Clear fiber edges |

### Macro syntax

```
run("Directionality", "method=[Fourier components] nbins=90 display_table");
```

- `nbins`: angular bins (typically 60, 90, or 180)
- `display_table`: required to get results table output

### Output

| Column | Meaning |
|--------|---------|
| Direction (deg) | Peak direction from Gaussian/von Mises fit |
| Dispersion (deg) | Width of fitted peak (SD) |
| Amount | Proportion of signal in the peak |
| Goodness | R-squared of fit |

### Directionality vs OrientationJ

| Feature | Directionality | OrientationJ |
|---------|---------------|--------------|
| Spatial info | No (whole-image) | Yes (per-pixel maps) |
| Coherency map | No | Yes |
| Peak fitting | Built-in | Manual |
| Speed | Fast | Moderate |

Consider Directionality for quick global assessment with statistical fit.
Consider OrientationJ for spatial analysis, coherency maps, or vector fields.
Consider using both: OrientationJ for spatial maps, Directionality for fitted summary.

---

## §5 FibrilTool

Nematic tensor method giving one orientation + anisotropy per ROI. No parameters to tune (no sigma). Originally for plant cellulose microfibrils (Boudaoud et al., Nature Protocols 2014).

| Feature | FibrilTool | OrientationJ |
|---------|-----------|--------------|
| Scope | One value per ROI | Per-pixel maps |
| Parameters | None | Sigma, gradient method |
| Best for | Comparing many ROIs (e.g., cells) | Spatial analysis |

**Workflow:** Draw ROIs (one per cell) in ROI Manager, run FibrilTool macro. Results: angle + anisotropy per ROI.

Not available via update sites — download from the Nature Protocols supplementary materials.

---

## §6 DiameterJ

NIST-validated nanofiber characterization plugin. Measures fiber diameter, orientation, intersection density, pore size, porosity.

**Installation:** Fiji > Help > Update > Manage Update Sites > check "DiameterJ"

DiameterJ requires dialog interaction. For automated analysis, replicate core steps:

```
python ij.py macro '
  open("/path/to/nanofiber_SEM.tif"); run("8-bit");
  setAutoThreshold("Otsu"); run("Convert to Mask");
  // Diameter via Local Thickness
  run("Local Thickness (complete process)", "threshold=128");
  // Orientation via OrientationJ on the original
  // Pores via Analyze Particles on inverted mask
'
```

---

## §7 Fiber Morphometry with AnalyzeSkeleton

**Menu:** Analyze > Skeleton > Analyze Skeleton (2D/3D) (bundled with Fiji)

### Preprocessing: creating a good skeleton

```
python ij.py macro '
  open("/path/to/fibers.tif"); run("8-bit");
  run("Gaussian Blur...", "sigma=1");
  run("Subtract Background...", "rolling=50");
  setAutoThreshold("Otsu"); run("Convert to Mask");
  run("Open");    // erosion+dilation removes small noise
  run("Close-");  // dilation+erosion fills small gaps
  run("Skeletonize");
'
python ij.py capture skeleton
```

Choose blur sigma smaller than fiber width. Choose rolling ball radius several times larger than fiber width.

### Running AnalyzeSkeleton

```
run("Analyze Skeleton (2D/3D)", "prune=[PRUNE_METHOD] calculate show");
```

**Prune options:** `[none]`, `[shortest branch]`, `[lowest intensity voxel]`, `[lowest intensity branch]`

### Output tables

**Per-skeleton table:**

| Column | Meaning |
|--------|---------|
| # Branches | Total branches |
| # Junctions | Branch points |
| # End-point voxels | Terminal points |
| Average Branch Length | Mean branch length (calibrated) |
| Maximum Branch Length | Longest branch |
| Longest Shortest Path | Geodesic diameter |
| # Triple/Quadruple points | Junction degree |

**Per-branch table** (with `show`):

| Column | Meaning |
|--------|---------|
| Branch length | Calibrated length |
| V1/V2 x,y,z | Endpoint coordinates |
| Euclidean distance | Straight-line distance between endpoints |

### Tortuosity

Tortuosity = branch length / Euclidean distance (1.0 = straight, higher = more curved). Both values are in the branch table.

### Fiber density and spacing

```
python ij.py macro '
  // Fiber density from skeleton
  run("Set Measurements...", "area area_fraction redirect=None decimal=4");
  run("Measure");
  // Inter-fiber spacing from distance transform on inverted binary
  selectWindow("binary_mask"); run("Invert");
  run("Distance Map");
  run("Set Measurements...", "mean standard max redirect=None decimal=4");
  run("Measure");
'
```

---

## §8 Fiber Metrics Summary

### Alignment metrics

| Metric | Source | Range | Notes |
|--------|--------|-------|-------|
| Coherency | OrientationJ | 0 (isotropic) to 1 (aligned) | Most common; spatially resolved; depends on sigma |
| Circular variance | Orientation histogram | 0 (aligned) to 1 (uniform) | Anisotropy index = 1 - circ_var |
| Nematic order S | Orientation histogram | -0.5 to 1 | S=1 aligned, S=0 isotropic |
| Histogram entropy | Orientation histogram | 0 (one direction) to 1 (uniform, normalised) | |
| Dispersion | Directionality fit | degrees | Width of fitted peak |

### Morphology metrics

| Metric | Tool |
|--------|------|
| Fiber length | AnalyzeSkeleton (branch length) |
| Fiber diameter | Local Thickness on binary mask |
| Tortuosity | AnalyzeSkeleton (branch length / Euclidean) |
| Branch points | AnalyzeSkeleton (junction count) |
| Inter-fiber spacing | Distance Map on inverted binary |
| Fiber density | Area fraction of binary mask |

---

## §9 Preprocessing

### Decision tree

```
Single-channel grayscale? → NO: split channels first
8-bit or 16-bit? → NO: run("8-bit")
Uneven background? → YES: run("Subtract Background...", "rolling=R")
                          R = several times fiber width
Noisy (low SNR)? → YES: run("Median...", "radius=1") or run("Gaussian Blur...", "sigma=1")
                        Filter kernel must be smaller than fiber width
Non-fiber debris? → YES: create ROI or mask to isolate fibers
Color histology? → YES: colour deconvolution first (see below)
Ready for orientation analysis
```

### Colour deconvolution for histology

```
// Masson Trichrome (collagen = blue channel)
run("Colour Deconvolution", "vectors=[Masson Trichrome]");
selectWindow("Colour Deconvolution-(Colour_3)");
run("8-bit"); run("Invert");

// H&E (eosin channel for cytoplasm/collagen)
run("Colour Deconvolution", "vectors=[H&E]");
selectWindow("Colour Deconvolution-(Colour_2)");
run("8-bit"); run("Invert");
```

### Exclude edges

Structure tensor is unreliable at image borders. Shrink analysis ROI by 2-3x sigma:

```
python ij.py macro '
  sigma = 3; margin = 3 * sigma;
  getDimensions(w, h, c, z, t);
  makeRectangle(margin, margin, w - 2*margin, h - 2*margin);
  run("OrientationJ Measure", "sigma=" + sigma);
'
```

### Mask non-fiber regions using energy

```
python ij.py macro '
  run("OrientationJ Analysis", "tensor=3.0 gradient=0 energy=on ");
  selectWindow("OJ-Energy-1");
  setAutoThreshold("Otsu"); run("Convert to Mask");
  run("Create Selection");
  selectWindow("OJ-Coherency-1");
  run("Restore Selection");
  run("Set...", "value=NaN");
  run("Set Measurements...", "mean standard redirect=None decimal=4");
  run("Measure");
'
```

---

## §10 Agent Workflow: Fiber Orientation Analysis

```
# 1. Open and inspect
python ij.py macro 'open("/path/to/fibers.tif");'
python ij.py info
python ij.py capture 01_original

# 2. Preprocess
python ij.py macro 'run("8-bit"); run("Median...", "radius=1");'
python ij.py histogram

# 3. OrientationJ Analysis
python ij.py macro '
  run("OrientationJ Analysis",
      "tensor=3.0 gradient=0 color-survey=on "
      + "hue=Orientation sat=Coherency bri=Original-Image "
      + "orientation=on coherency=on energy=on ");
'
python ij.py capture 02_color_survey

# 4. Measure coherency
python ij.py macro '
  selectWindow("OJ-Coherency-1");
  run("Set Measurements...", "mean standard min max redirect=None decimal=4");
  run("Measure");
'
python ij.py results

# 5. Orientation histogram
python ij.py macro '
  selectWindow("original_title");
  run("OrientationJ Distribution",
      "tensor=3.0 gradient=0 histogram=on "
      + "min-coherency=10.0 min-energy=5.0 ");
'

# 6. Dominant direction
python ij.py macro '
  selectWindow("original_title");
  run("OrientationJ Dominant Direction", "tensor=3.0 gradient=0 ");
'
python ij.py log

# 7. Save outputs
python ij.py macro '
  selectWindow("OJ-Coherency-1"); saveAs("Tiff", "/path/to/output/coherency_map.tif");
  selectWindow("OJ-Orientation-1"); saveAs("Tiff", "/path/to/output/orientation_map.tif");
'
```

### Comparing conditions

Process all images with **identical parameters**. Mean coherency (0-1) can be compared with standard t-test or Mann-Whitney. Direction comparisons require circular statistics (Section 11).

### Fiber network analysis workflow

```
# 1. Threshold and clean
python ij.py macro '
  open("/path/to/fibers.tif"); run("8-bit");
  run("Gaussian Blur...", "sigma=1");
  run("Subtract Background...", "rolling=30");
  setAutoThreshold("Otsu"); run("Convert to Mask");
  run("Open"); run("Close-");
  run("Analyze Particles...", "size=20-Infinity show=Masks");
  rename("clean_mask");
'

# 2. Skeleton analysis
python ij.py macro '
  selectWindow("clean_mask");
  run("Duplicate...", "title=skeleton"); run("Skeletonize");
  run("Analyze Skeleton (2D/3D)", "prune=[shortest branch] calculate show");
'
python ij.py results

# 3. Fiber diameter
python ij.py macro '
  selectWindow("clean_mask");
  run("Local Thickness (complete process)", "threshold=128");
  run("Set Measurements...", "mean standard min max redirect=None decimal=4");
  run("Measure");
'
python ij.py results
```

---

## §11 Batch Processing

### Batch OrientationJ

```
python ij.py macro '
  input_dir = "/path/to/input/";
  output_dir = "/path/to/output/";
  sigma = 3.0;
  list = getFileList(input_dir);
  for (i = 0; i < list.length; i++) {
      if (endsWith(list[i], ".tif") || endsWith(list[i], ".png")) {
          open(input_dir + list[i]);
          title = getTitle(); run("8-bit"); run("Median...", "radius=1");
          run("OrientationJ Analysis", "tensor=" + sigma + " gradient=0 coherency=on ");
          selectWindow("OJ-Coherency-1");
          run("Set Measurements...", "mean standard redirect=None decimal=4");
          run("Measure");
          saveAs("Tiff", output_dir + "coherency_" + title);
          selectWindow(title);
          run("OrientationJ Dominant Direction", "tensor=" + sigma + " gradient=0 ");
          run("Close All");
      }
  }
'
python ij.py results
python ij.py log
```

Export accumulated results:
```
python ij.py macro 'saveAs("Results", "/path/to/output/all_results.csv");'
```

---

## §12 Circular Statistics for Orientation Data

### Why circular statistics?

Orientation data is axial (0 and 180 degrees are the same). Standard linear statistics give wrong answers. Example: linear mean of 170 and -170 = 0 (wrong); circular mean = 180 (correct).

**Rule:** Use circular statistics for angles. Use linear statistics for coherency/anisotropy (bounded 0-1).

### Axial data conversion

Double angles before circular computation, halve the result:

```python
import numpy as np

axial_deg = np.array([10, 15, 170, 175, 8, 12])
doubled = 2 * np.deg2rad(axial_deg)
C = np.mean(np.cos(doubled))
S = np.mean(np.sin(doubled))
axial_mean_deg = np.rad2deg(0.5 * np.arctan2(S, C))
R_bar = np.sqrt(C**2 + S**2)  # mean resultant length
circ_var = 1 - R_bar           # circular variance
circ_sd = np.rad2deg(np.sqrt(-2 * np.log(R_bar)) / 2)
```

### Hypothesis tests

| Test | Question | Assumption |
|------|----------|------------|
| Rayleigh | Is there a preferred direction? (vs uniform) | Unimodal alternative |
| Watson-Williams | Do two groups have the same mean direction? | Von Mises, equal kappa |
| Kuiper's V | Do two distributions differ in any way? | Non-parametric |
| Watson's U-squared | Do two distributions differ? | Non-parametric |

#### Rayleigh test

```python
def rayleigh_test(angles_deg):
    n = len(angles_deg)
    doubled = 2 * np.deg2rad(angles_deg)
    C, S = np.sum(np.cos(doubled)), np.sum(np.sin(doubled))
    R_bar = np.sqrt(C**2 + S**2) / n
    z = n * R_bar**2
    p = np.exp(-z) * (1 + (2*z - z**2)/(4*n)
                       - (24*z - 132*z**2 + 76*z**3 - 9*z**4)/(288*n**2))
    return z, p
# p < 0.05 → fibers have a preferred direction
```

#### Watson-Williams F-test

```python
def watson_williams_test(group1_deg, group2_deg):
    from scipy.stats import f as f_dist
    groups = [np.deg2rad(g) * 2 for g in [group1_deg, group2_deg]]
    ns = [len(g) for g in groups]
    N = sum(ns)
    Rs = [np.sqrt(np.sum(np.cos(g))**2 + np.sum(np.sin(g))**2) for g in groups]
    all_a = np.concatenate(groups)
    R_total = np.sqrt(np.sum(np.cos(all_a))**2 + np.sum(np.sin(all_a))**2)
    F = (sum(Rs) - R_total) / ((N - sum(Rs)) / (N - 2))
    p = 1 - f_dist.cdf(F, 1, N - 2)
    return F, p
# p < 0.05 → groups have different preferred directions
```

### Von Mises distribution

Circular equivalent of the normal distribution. Concentration parameter kappa:

| kappa | Interpretation |
|-------|---------------|
| 0 | Uniform |
| ~1 | Weakly concentrated |
| ~5 | Moderate |
| ~10 | Strong |
| >20 | Very strong |

### Comparing coherency between groups

Coherency is a linear metric (0-1) — use standard tests:

```python
from scipy import stats
# Shapiro-Wilk for normality, then t-test or Mann-Whitney
t, p = stats.ttest_ind(coherency_control, coherency_treated)
# or: U, p = stats.mannwhitneyu(coherency_control, coherency_treated)
```

### Python libraries for circular statistics

| Library | Key functions |
|---------|--------------|
| pycircstat2 | `descriptive.mean()`, `hypothesis.rayleigh()`, `hypothesis.watson_williams()` |
| astropy.stats | `circmean()`, `circstd()`, `rayleightest()`, `vonmisesmle()` |
| scipy.stats | `circmean()`, `circstd()`, `circvar()` (circular, not axial — double first) |

---

## §13 SHG Image Analysis Notes

SHG (second harmonic generation) images of collagen are inherently fibrillar with clean signal (no autofluorescence, no photobleaching). Typically need minimal preprocessing:

```
python ij.py macro '
  open("/path/to/shg.tif"); run("8-bit");
  run("Median...", "radius=1");  // gentle denoise
  // Background subtraction usually NOT needed for SHG
  run("OrientationJ Analysis", "tensor=3.0 gradient=0 color-survey=on hue=Orientation sat=Coherency bri=Original-Image orientation=on coherency=on energy=on ");
'
```

**SHG intensity** is proportional to collagen concentration squared — not a simple linear measure.

**TACS (tumor-associated collagen signatures):** TACS-1 = increased density, TACS-2 = fibers parallel to tumor boundary, TACS-3 = fibers perpendicular (poor prognosis). Measure fiber angles relative to tumor boundary using the orientation map.

**External tools** (MATLAB, not ImageJ): CT-FIRE, CurveAlign, FIRE for individual fiber extraction.

---

## §14 Reporting Standards

### Methods section checklist

- Software and version (OrientationJ vX.X, Fiji vX.X)
- Analysis method (structure tensor / FFT / local gradient)
- Sigma and how it was chosen
- Min-coherency and min-energy thresholds
- Preprocessing steps
- ROI selection criteria
- N (images, fields of view, biological replicates)
- Statistical tests (circular vs linear specified)

### Results to report

- Orientation histogram (plot, not just summary)
- Mean direction +/- circular SD
- Coherency (mean +/- SD)
- Statistical test results (statistic, p-value, n)
- Representative images: original, color survey, vector field

### Figure guidelines

- Color survey: include color wheel legend, state sigma
- Coherency map: perceptually uniform colormap (not jet), include colorbar (0-1)
- Vector field: state grid spacing and color coding
- Orientation histogram: angle on x-axis, fitted curve overlaid, report fit parameters

---

## §15 Feature Comparison

| Feature | OrientationJ | Directionality | FibrilTool | DiameterJ | AnalyzeSkeleton |
|---------|-------------|---------------|-----------|----------|----------------|
| Orientation map | Per-pixel | No | No | No | No |
| Coherency map | Per-pixel | No | Per-ROI | No | No |
| Direction histogram | Yes | Yes (fitted) | No | Yes | No |
| Vector field | Yes | No | No | No | No |
| Fiber diameter | No | No | No | Yes | No |
| Fiber length | No | No | No | No | Yes |
| Branching | No | No | No | No | Yes |
| Tortuosity | No | No | No | No | Yes |
| Pore analysis | No | No | No | Yes | No |
| Statistical fit | No | Gaussian/von Mises | No | No | No |
| Requires binary | No | No | No | Yes | Yes |

### Decision tree

```
DIRECTION (what angle?)
  Global histogram with fit → Directionality
  Coherency-weighted histogram → OrientationJ Distribution
  Per-pixel map → OrientationJ Analysis
  Per-ROI mean → FibrilTool or OrientationJ Measure

ALIGNMENT (how aligned?)
  Per-pixel → OrientationJ coherency map
  Per-ROI → OrientationJ Measure or FibrilTool

MORPHOLOGY (length, diameter, branching?)
  Diameter → Local Thickness on binary
  Length/branching/tortuosity → AnalyzeSkeleton
  Complete nanofiber → DiameterJ

NETWORK properties
  Connectivity → AnalyzeSkeleton
  Inter-fiber spacing → Distance Map on inverted binary
  Pore size → Analyze Particles on inverted binary

SPATIAL variation
  Where aligned vs random? → OrientationJ coherency map
  Direction variation? → OrientationJ Vector Field
```

### Recommended combinations

| Study type | Tools |
|-----------|-------|
| Collagen/SHG | OrientationJ + Directionality + AnalyzeSkeleton |
| Cytoskeletal alignment | OrientationJ Measure with cell ROIs, or FibrilTool |
| Nanofiber scaffold | DiameterJ, or OrientationJ + Local Thickness + AnalyzeSkeleton |
| Wound healing/fibrosis | OrientationJ at each time point (track coherency) |
| Cardiac/muscle mapping | OrientationJ with large sigma + Vector Field |

---

## §16 Appendix: Quick Macro Reference

```
// === OrientationJ ===
run("OrientationJ Analysis", "tensor=3.0 gradient=0 color-survey=on hue=Orientation sat=Coherency bri=Original-Image orientation=on coherency=on energy=on ");
run("OrientationJ Distribution", "tensor=3.0 gradient=0 histogram=on min-coherency=10.0 min-energy=5.0 ");
run("OrientationJ Measure", "sigma=3.0");
run("OrientationJ Vector Field", "tensor=3.0 gradient=0 vector-type=0 vector-grid=20 vector-scale=80 vector-color=0 ");
run("OrientationJ Dominant Direction", "tensor=3.0 gradient=0 ");
run("OrientationJ Corner Harris", "tensor=3.0 gradient=0 harris-index=0.05 min-coherency=10.0 min-energy=5.0 ");

// === Directionality ===
run("Directionality", "method=[Fourier components] nbins=90 display_table");
run("Directionality", "method=[Local gradient orientation] nbins=90 display_table");

// === AnalyzeSkeleton ===
run("Analyze Skeleton (2D/3D)", "prune=[shortest branch] calculate show");

// === Fiber diameter ===
run("Local Thickness (complete process)", "threshold=128");

// === Preprocessing ===
run("8-bit");
run("Median...", "radius=1");
run("Subtract Background...", "rolling=50");
run("Colour Deconvolution", "vectors=[Masson Trichrome]");
run("Enhance Local Contrast (CLAHE)", "blocksize=127 histogram=256 maximum=3 mask=*None*");
```

### Sigma lookup by structure and magnification

| Structure | Width (um) | 20x (~0.5 um/px) | 40x (~0.25 um/px) | 63x (~0.16 um/px) |
|-----------|-----------|-------------------|--------------------|--------------------|
| Actin stress fiber | 0.3-1.0 | 1-2 | 1-4 | 2-6 |
| Collagen fiber | 1-20 | 2-40 | 4-80 | 6-125 |
| Cardiac muscle | 10-25 | 20-50 | 40-100 | 60-160 |
| Myelinated nerve | 1-20 | 2-40 | 4-80 | 6-125 |
| Blood vessel (capillary) | 5-10 | 10-20 | 20-40 | 30-60 |

If fibers are < 2 px wide, structure tensor cannot reliably determine orientation. Consider FFT-based methods (Directionality) instead, or image at higher magnification.

---

## §17 Appendix: Analysis Checklist

**Before:**
- [ ] Single-channel grayscale
- [ ] Calibrated (for length/diameter measurements)
- [ ] Fibers >= 2-3 px wide (or use FFT)
- [ ] Adequate SNR
- [ ] Flat background

**Parameters:**
- [ ] Sigma matches fiber width (documented)
- [ ] Gradient method selected
- [ ] min-coherency and min-energy set (documented)
- [ ] ROI defined (documented)

**After:**
- [ ] Color survey visually inspected
- [ ] Coherency map: low on background, high on fibers
- [ ] Histogram peaks match visible fiber directions
- [ ] Same parameters for all images in experiment
- [ ] Circular statistics for angles, linear for coherency
- [ ] Results exported and saved

---

## §18 Common Problems and Solutions

| Problem | Cause | Fix |
|---------|-------|-----|
| Background dominates histogram | Too many background pixels counted | Increase min-coherency (20-30) and min-energy (10-20), or mask fibers using energy map |
| Coherency map looks like edge detection | Sigma too small | Increase sigma to match fiber width |
| Coherency map uniformly smooth | Sigma too large | Decrease sigma |
| Edge artifacts in coherency | Gaussian window at border | Shrink ROI by 2-3x sigma from edges |
| Low coherency at fiber crossings | Structure tensor averages crossing populations | Expected. Report mean coherency, or check histogram for multiple peaks |
| Low contrast fibers, noisy results | Insufficient SNR | Consider CLAHE (`run("Enhance Local Contrast (CLAHE)", "blocksize=127 histogram=256 maximum=3 mask=*None*")`) — changes pixel values, only for orientation, not intensity measurement |
| Color histology image | OrientationJ needs grayscale | Colour deconvolution first |
| 3D fiber orientation needed | OrientationJ is 2D | Slice-by-slice analysis, or max projection, or 3D ImageJ Suite |
| OrientationJ not found | Plugin not installed | Enable BIG-EPFL update site |
| Directionality no results | Missing argument | Include `display_table` in macro; ensure 8-bit |
| Vector Field overlay empty | Known macro bug | Probe with `probe_plugin.py`, or try interactively |
| Fiber-like artifacts | JPEG compression, deconvolution ringing | Work from raw TIFF data |
