# Cell Proliferation, Apoptosis & Cell Cycle Analysis Reference

Agent-oriented reference for scoring proliferation markers (Ki67, EdU, BrdU,
PCNA, pHH3, FUCCI), apoptosis markers (TUNEL, Cleaved Caspase-3 / CC3,
Annexin V, morphological), colony assays, DNA-content cell cycle, multi-marker
classification, high-content screening, and IHC chromogenic scoring.

Sources: Ki67 / MIB-1 literature and clinical pathology cutoffs; EdU vs BrdU
click-chemistry comparisons; TUNEL and Cleaved Caspase-3 (CC3) apoptosis
detection guidelines; Dean-Jett-Fox DNA-content fitting; Z-factor and SSMD
screening statistics; `imagej.net` StarDist and Colour Deconvolution
documentation.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript.
Use `python probe_plugin.py "Plugin..."` to discover any installed plugin's
parameters at runtime.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Formula for proliferation / apoptotic / mitotic index?" | §2 |
| "How many cells do I need to count?" | §2 |
| "Which marker should I use — Ki67, EdU, BrdU, PCNA, pHH3, FUCCI?" | §3 |
| "Cell cycle timeline / decision tree for marker choice?" | §3 |
| "Generic workflow for any marker-positive scoring?" | §4 |
| "StarDist nuclear segmentation macro?" | §4 |
| "How do I pick the intensity threshold?" | §4 |
| "Marker-specific notes (Ki67 hot-spot, EdU/BrdU pulse, PCNA texture, pHH3, FUCCI)?" | §5 |
| "How do I detect apoptosis (TUNEL / CC3 / Annexin V / morphology)?" | §6 |
| "Annexin V / PI quadrant analysis?" | §6 |
| "Morphological apoptosis from DAPI alone?" | §6 |
| "Colony formation / plating efficiency / surviving fraction?" | §7 |
| "Cell cycle from DNA content (IntDen, Dean-Jett-Fox)?" | §8 |
| "Doublet discrimination?" | §8 |
| "Double-labeling interpretation (EdU+Ki67, Ki67+CC3, pHH3+Ki67)?" | §9 |
| "Spectral bleedthrough correction?" | §9 |
| "Batch processing a plate, Z-factor / SSMD, normalization?" | §10 |
| "IHC colour deconvolution (H DAB) / H-score / area fraction?" | §11 |
| "Which statistical test do I use? Unit of replication?" | §12 |
| "What does a standard agent scoring session look like?" | §13 |
| "Why did my index / fit / count look wrong?" | §14 gotchas |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section where each marker, assay, or concept is
defined. Use `grep -n '<term>' proliferation-apoptosis-reference.md` to jump.

### A
`Analyze Particles` §5.3, §7.1, §11.1 · `Annexin V / PI` §6.1, §6.4 ·
`Apoptotic Index` §2 · `Apoptosis` §6 · `area fraction (IHC)` §11.2 ·
`autofluorescence` §14 ·

### B
`B-score` §10.3 · `background-based threshold` §4.3 ·
`batch processing` §10.1 · `bleedthrough` §9.3 · `BrdU` §3, §5.2 ·

### C
`Calcein-AM` §6.1, §6.5 · `Cellpose` §14 · `cell cycle (DNA content)` §8 ·
`cell cycle timeline` §3.1 · `chromatin margination` §6.6 ·
`Chi-squared` §12.2 · `circularity filter` §7.2 · `clinical cutoffs (Ki67)` §5.1 ·
`Cleaved Caspase-3 (CC3)` §6.1, §6.3 · `click chemistry` §3, §5.2 ·
`Colony Formation Assay` §7 · `ColonyArea plugin` §7.3 ·
`Colour Deconvolution` §11.1 · `cumulative labeling` §5.2 ·

### D
`DAB` §11.1, §11.4 · `DAPI segmentation` §4.1 ·
`Dean-Jett-Fox` §8.3 · `Decision tree (marker choice)` §3.2 ·
`denaturation (BrdU)` §5.2 · `DNA content` §8 · `doublet discrimination` §8.5 ·
`dual-pulse` §5.2 · `Dunn's post-hoc` §12.2 ·

### E
`EdU` §3, §5.2 · `edge effect` §10.4 · `EthD-1` §6.5 ·

### F
`Fisher's exact` §12.2 · `FUCCI` §3, §5.5 · `focus quality filter` §14.2 ·
`fragment clustering` §14.3 ·

### G
`G0 / G1 / S / G2 / M` §3.1, §8.1 · `G1 CV` §8.4 · `G2/G1 ratio` §8.4 ·

### H
`H DAB vectors` §11.1, §11.4 · `Hematoxylin` §11.1 · `hot-spot counting (Ki67)` §5.1 ·
`H-score` §14.1 ·

### I
`IC50 / EC50` §12.2 · `IHC (chromogenic)` §11 · `IHC Profiler` §11.5 ·
`IntDen (integrated density)` §8.2 · `isotype control` §14 ·

### K
`karyorrhexis` §6.6 · `Ki67` §3, §5.1 · `Kruskal-Wallis` §12.2 ·

### L
`live/dead viability` §6.5 ·

### M
`Mann-Whitney U` §12.2 · `MIB-1` §3 · `Mitotic Index` §2, §5.4 ·
`mixture model` §4.3 · `morphological apoptosis` §6.1, §6.6 ·
`multi-marker classification` §9 ·

### N
`negative control` §4.3 · `nmsThresh` §4.1, §14 · `normalization` §10.3 ·

### O
`Otsu` §4.3, §11.1 ·

### P
`paired t-test` §12.2 · `PCNA` §3, §5.3 · `percent activity` §10.3 ·
`percent of control` §10.3 · `pHH3` §3, §5.4 · `plating efficiency` §7 ·
`probThresh` §4.1, §14 · `Proliferation Index` §2 · `pulse-chase` §5.2 ·
`pyknosis` §6.6 ·

### Q
`quadrant analysis (Annexin V / PI)` §6.4 ·

### R
`replication (unit of)` §12.1 · `reporting checklist` §12.3 ·
`robust Z-score` §10.3 ·

### S
`saturation check` §8.2 · `S-phase` §3, §5.2, §8 · `sensitivity analysis` §14.1 ·
`spectral bleedthrough` §9.3 · `SSMD` §10.2 · `StarDist` §4.1, §14 ·
`statistical tests` §12.2 · `sub-G1` §8.1, §8.4 ·

### T
`texture (StdDev)` §5.3 · `Triangle threshold` §5.4, §11.1 ·
`TUNEL` §6.1, §6.2 ·

### V
`viability` §6.5 ·

### W
`Watershed` §7.1 ·

### Z
`Z-factor (Z')` §10.2 · `Z-score` §10.3 ·

---

## §2 Key Formulas & Counting Standards

```
Proliferation Index (%) = (marker-positive nuclei / total nuclei) x 100
Apoptotic Index (%)     = (apoptotic nuclei / total nuclei) x 100
Mitotic Index (%)       = (mitotic figures / total nuclei) x 100
```

| Application | Minimum cells | Recommended |
|-------------|--------------|-------------|
| Ki67 index (clinical) | 500 | 1000+ |
| Ki67 index (research) | 200 | 500+ |
| Mitotic index | 10 HPF | 30+ HPF |
| Apoptotic index | 500 | 1000+ |
| Colony assay | 3 replicates | 6+ replicates |
| Cell cycle (imaging) | 500+ | 1000+ |

---

## §3 Proliferation Marker Comparison

| Marker | Phase Detected | Detection | Advantages | Limitations |
|--------|---------------|-----------|------------|-------------|
| **Ki67** (MIB-1) | All non-G0 | IF or IHC | Marks all cycling cells; single timepoint | Cannot distinguish phases; varies with fixation |
| **EdU** | S-phase (during pulse) | Click chemistry | No DNA denaturation; multiplexable | Only labels during pulse; cytotoxic at high doses |
| **BrdU** | S-phase (during pulse) | Anti-BrdU Ab (after denaturation) | Well-established; cheap | Harsh DNA denaturation damages epitopes |
| **PCNA** | Primarily S-phase | IF or IHC | Endogenous; no labeling needed | Also labels repair sites; diffuse G1 staining confounds |
| **pHH3** (Ser10) | M-phase only | IF or IHC | Highly specific for mitosis; bright signal | Rare events (1-5%); needs large areas |
| **FUCCI** | G1 (red) vs S/G2/M (green) | Live fluorescence | Real-time phase tracking | Requires transgenic/transfected cells |

### §3.1 Cell cycle timeline with markers
```
  G0 (quiescent)  →  G1  →  S  →  G2  →  M  →  G1
Ki67:  -            ++++++  ++++++  ++++++  ++++++
EdU:   -               -   ++++++     -      -
PCNA:  -              (+)  ++++++    (+)     -
pHH3:  -               -      -      -   ++++++
FUCCI: -            red→→→  →green  green  green
```

### §3.2 Decision tree: which marker to use
```
Fixed tissue?
├── YES → Need phase info?
│   ├── YES → Ki67 + EdU double-label (or Ki67 + pHH3)
│   └── NO  → IHC? → Ki67 DAB | IF? → Ki67 IF
├── NO → Live imaging?
│   ├── YES → FUCCI available? → Yes: FUCCI | No: EdU pulse-chase
│   └── NO → Cell culture (fixed)? → EdU (cleanest) or BrdU (cheaper)
```

---

## §4 General Workflow: Marker-Positive Scoring (Fluorescence)

All proliferation/apoptosis markers follow the same pattern:
1. Segment nuclei from DAPI (StarDist recommended)
2. Transfer ROIs to marker channel
3. Measure mean intensity per nucleus
4. Classify positive/negative using threshold
5. Calculate index

### §4.1 StarDist nuclear segmentation (reusable for all markers)

```javascript
// Segment nuclei — adjust probThresh and nmsThresh per image quality
selectWindow(dapi);
run("Command From Macro", "command=[de.csbdresden.stardist.StarDist2D], " +
    "args=[\"input\":\"" + dapi + "\", " +
    "\"modelChoice\":\"Versatile (fluorescent nuclei)\", " +
    "\"normalizeInput\":\"true\", " +
    "\"percentileBottom\":\"1.0\", " +
    "\"percentileTop\":\"99.8\", " +
    "\"probThresh\":\"0.5\", " +   // Lower (0.3-0.4) for fragmented/apoptotic nuclei
    "\"nmsThresh\":\"0.4\", " +     // Higher (0.5-0.6) for dense/overlapping tissue
    "\"outputType\":\"ROI Manager\", " +
    "\"nTiles\":\"1\", " +
    "\"excludeBoundary\":\"2\", " +
    "\"roiPosition\":\"Automatic\", " +
    "\"verbose\":\"false\", " +
    "\"showCsbdeepProgress\":\"false\", " +
    "\"showProbAndDist\":\"false\"], " +
    "process=[false]");
nNuclei = roiManager("count");
```

### §4.2 Measure marker and classify

```javascript
// Measure marker intensity per nucleus
selectWindow(markerChannel);
run("Set Measurements...", "area mean min max integrated redirect=None decimal=3");
roiManager("Deselect");
roiManager("Measure");

// Classify positive/negative
nPositive = 0;
for (i = 0; i < nResults; i++) {
    if (getResult("Mean", i) > threshold) {
        nPositive++;
    }
}
index = (nPositive / nNuclei) * 100;
print("Index: " + d2s(index, 1) + "%");
```

### §4.3 How to choose the intensity threshold

| Strategy | When to use | Method |
|----------|------------|--------|
| **Negative control** | Gold standard | Measure marker in known-negative tissue; threshold = mean + 2*SD |
| **Otsu on intensities** | No control; bimodal distribution | Build histogram of per-nucleus means; apply Otsu |
| **Background-based** | EdU (clean bimodal signal) | 3x median of bottom quartile of intensity values |
| **Visual calibration** | Initial setup | Mark ~50 nuclei manually as +/-, find matching threshold |
| **Mixture model** | Publication-quality | Fit two Gaussians; threshold at intersection |

---

## §5 Marker-Specific Notes

### §5.1 Ki67

- Heterogeneous intensity: dim in early G1, bright in M-phase
- Clinical cutoffs vary by tissue (breast cancer typically 14-20%)
- For hot-spot counting (clinical pathology): scan in tiles, find tiles with highest mean Ki67 intensity, count in top 3-5 tiles
- Consider H-score for semi-quantitative grading (see Section 10)

### §5.2 EdU vs BrdU

| Feature | EdU | BrdU |
|---------|-----|------|
| DNA denaturation | Not required | Required (HCl/DNase) |
| Epitope preservation | Excellent | Poor |
| Signal quality | Clean, low background | Higher background |
| Multiplexing | Easy | Limited |

**Pulse duration guidance:**
- Short pulse (30 min-1 hr): instantaneous S-phase fraction
- Long pulse (12-24 hr): cumulative labeling, higher percentage
- Pulse-chase: cell cycle transit times, labeled mitoses
- Dual-pulse (EdU then BrdU): replication dynamics

### §5.3 PCNA

PCNA is harder to threshold — intensity is continuous, not bimodal. Use **both intensity AND texture** (StdDev) to identify S-phase cells:
- S-phase: high mean AND high StdDev (punctate replication foci)
- G1/G2: moderate mean, low StdDev (diffuse)
- G0: low mean, low StdDev

```javascript
// PCNA: classify using mean + StdDev
run("Set Measurements...", "area mean standard_deviation redirect=None decimal=3");
roiManager("Deselect");
roiManager("Measure");
for (i = 0; i < nResults; i++) {
    meanI = getResult("Mean", i);
    stdI = getResult("StdDev", i);
    if (meanI > intensityThresh && stdI > textureThresh) nPositive++;
}
```

### §5.4 pHH3 (Mitotic Index)

pHH3+ cells are typically 5-20x brighter than background. Two approaches:

**Approach A — Direct threshold on pHH3 channel** (simpler, works when mitotic figures are well-separated):
```javascript
selectWindow(phh3);
run("Duplicate...", "title=phh3_mask");
run("Gaussian Blur...", "sigma=1");
setAutoThreshold("Triangle dark");  // Triangle typically works well for bright sparse objects
run("Convert to Mask");
run("Analyze Particles...", "size=50-2000 circularity=0.3-1.00 show=Nothing summarize add");
```

**Approach B — Intensity scoring within DAPI nuclei** (better when nuclei overlap):
Score pHH3 mean intensity per DAPI-defined nucleus; pHH3+ nuclei will have very high values.

### §5.5 FUCCI

```javascript
// FUCCI classification per cell per timepoint
// C1 = mKO2/mCherry (red = G1), C2 = mAG/mVenus (green = S/G2/M)
// For each nucleus ROI, measure mean in both channels:
//   red > thresh AND green < thresh → G1
//   red > thresh AND green > thresh → G1/S transition
//   red < thresh AND green > thresh → S/G2/M
//   red < thresh AND green < thresh → M or early G1
```

| Metric | Formula |
|--------|---------|
| G1 fraction | n(red-only) / n(total) |
| S/G2/M fraction | n(green-only) / n(total) |
| G1/S transition | n(yellow) / n(total) |
| G1 duration | Time from red onset to green onset |
| Total cycle time | Time between two mitoses |

For tracking FUCCI over time, consider TrackMate (LoG or StarDist detector, Simple LAP tracker).

---

## §6 Apoptosis Detection

### §6.1 Marker comparison

| Marker | Detects | Specificity | Notes |
|--------|---------|-------------|-------|
| **TUNEL** | DNA strand breaks | Moderate — also detects necrosis, repair | Most widely used; correlate with morphology or CC3 |
| **Cleaved Caspase-3** | Effector caspase activation | High — specific to apoptosis | Earlier stage than TUNEL; nuclear + cytoplasmic staining |
| **Annexin V / PI** | PS exposure + membrane integrity | Good for live cells | Quadrant analysis distinguishes viable/early/late apoptotic/necrotic |
| **Morphological** | Pyknosis, karyorrhexis | Variable — needs validation | DAPI-only; no additional staining needed |
| **Calcein-AM / PI** | Live vs dead (viability) | Good | Not specific to apoptosis vs necrosis |

### §6.2 TUNEL considerations
- Lower StarDist probThresh (0.3-0.4) to catch fragmented apoptotic nuclei
- TUNEL signal is typically strongly bimodal — positive cells are much brighter
- Not 100% specific for apoptosis — always correlate with morphology or second marker

### §6.3 Cleaved Caspase-3 (CC3)
- Staining can be nuclear AND cytoplasmic — consider expanding ROIs slightly:
```javascript
for (i = 0; i < totalNuclei; i++) {
    roiManager("select", i);
    run("Enlarge...", "enlarge=3");  // Capture cytoplasmic CC3; adjust for magnification
    roiManager("update");
}
```

### §6.4 Annexin V / PI quadrant analysis

```
                  PI-negative          PI-positive
Annexin V-neg  │ VIABLE (Q3)        │ NECROTIC (Q1)          │
Annexin V-pos  │ EARLY APOPTOTIC    │ LATE APOPTOTIC /       │
               │ (Q4)               │ SECONDARY NECROTIC (Q2)│
```

Segment cells from combined fluorescence, measure each marker per cell, classify into quadrants using thresholds from unstained control.

### §6.5 Live/Dead viability (Calcein-AM + PI/EthD-1)

| Dye | Stains | Mechanism | Color |
|-----|--------|-----------|-------|
| Calcein-AM | Live cells | Esterase cleavage → fluorescence | Green |
| EthD-1 / PI | Dead cells | Membrane-permeable → DNA binding | Red |

```
Viability (%) = calcein+ cells / (calcein+ + dead+ cells) x 100
```

Threshold and count each channel separately. Check for double-positive cells (compromised/dying).

### §6.6 Morphological apoptosis detection (DAPI-only)

| Feature | Measurement |
|---------|-------------|
| Pyknosis (condensed) | Small area + high DAPI mean |
| Karyorrhexis (fragmented) | Multiple small bright objects |
| Blebbing | Low circularity |
| Chromatin margination | High StdDev (ring pattern) |

```javascript
// Score: small (< 0.5x median area) + bright (> mean + 1.5*SD) + irregular (circ < 0.5)
// Classify as apoptotic if 2+ criteria met
run("Set Measurements...", "area mean standard_deviation shape redirect=None decimal=3");
roiManager("Deselect");
roiManager("Measure");
// ... compute median area, mean intensity, stdDev across population ...
for (i = 0; i < n; i++) {
    score = 0;
    if (getResult("Area", i) < medianArea * 0.5) score++;
    if (getResult("Mean", i) > meanM + 1.5 * stdM) score++;
    if (getResult("Circ.", i) < 0.5) score++;
    if (score >= 2) nApoptotic++;
}
```

---

## §7 Colony Formation Assay

```
Plating Efficiency (PE) = colonies counted / cells seeded
Surviving Fraction (SF) = colonies after treatment / (cells seeded x PE_control)
```

### §7.1 Workflow
```javascript
// Input: grayscale/RGB image of a single well
run("8-bit");
run("Subtract Background...", "rolling=50");  // Adjust rolling ball to image scale
run("Gaussian Blur...", "sigma=2");
setAutoThreshold("Otsu");
run("Convert to Mask");
run("Fill Holes");
run("Watershed");
// Size filter: adjust minimum based on resolution; colonies typically >50 cells
run("Analyze Particles...", "size=100-Infinity circularity=0.10-1.00 " +
    "show=Outlines display summarize add");
```

### §7.2 Handling touching colonies
- **Watershed on distance map:** `run("Distance Map"); run("Find Maxima...", "prominence=10 output=[Segmented Particles]");`
- **Size filter:** Reject objects >5x mean colony area as likely merged
- **Circularity filter:** Merged colonies typically have circularity <0.3

### §7.3 ColonyArea plugin
Install via Help > Update > Manage Update Sites > ColonyArea. Processes scanned multi-well plates automatically.

---

## §8 Cell Cycle from DNA Content

### §8.1 Principle
DAPI integrated density is proportional to DNA content:
- G0/G1 peak at 2N, S-phase between 2N-4N, G2/M peak at 4N
- Sub-G1 = apoptotic (fragmented DNA), >4N = polyploid or doublets

### §8.2 Critical requirements
- Measure **IntDen** (integrated density), NOT mean — total fluorescence = DNA content
- No saturation — check max pixel value before measuring
- Consistent DAPI staining (same concentration, time)

```javascript
// Check saturation, segment, measure IntDen
getStatistics(area, mean, min, max);
if (max >= 4095 || max >= 65535 || max >= 255)
    print("WARNING: Saturated — invalid for DNA content!");

// After StarDist segmentation:
run("Set Measurements...", "area mean integrated redirect=None decimal=3");
roiManager("Deselect");
roiManager("Measure");
// Export IntDen column for Python histogram fitting
```

### §8.3 Python: Dean-Jett-Fox cell cycle fitting

```python
import numpy as np
from scipy.optimize import curve_fit
from scipy.stats import norm
import csv, sys

def load_intden(csv_path):
    values = []
    with open(csv_path, 'r') as f:
        for row in csv.DictReader(f):
            for col in ['IntDen', 'RawIntDen', 'Integrated Density']:
                if col in row:
                    values.append(float(row[col])); break
    return np.array(values)

def dean_jett_fox(x, a1, mu1, sigma1, a2, sigma2, s0, s1, s2):
    mu2 = 2.0 * mu1
    g1 = a1 * norm.pdf(x, mu1, sigma1)
    g2 = a2 * norm.pdf(x, mu2, sigma2)
    s_phase = np.zeros_like(x)
    s_mask = (x > mu1 + sigma1) & (x < mu2 - sigma2)
    if np.any(s_mask):
        x_norm = (x[s_mask] - mu1) / (mu2 - mu1)
        s_phase[s_mask] = np.maximum(0, s0 + s1 * x_norm + s2 * x_norm**2)
    return g1 + g2 + s_phase

def fit_cell_cycle(intden_values, n_bins=128):
    q25, q75 = np.percentile(intden_values, [25, 75])
    iqr = q75 - q25
    data = intden_values[(intden_values > q25 - 3*iqr) & (intden_values < q75 + 3*iqr)]
    counts, bin_edges = np.histogram(data, bins=n_bins)
    bin_centers = (bin_edges[:-1] + bin_edges[1:]) / 2
    bin_width = bin_edges[1] - bin_edges[0]

    g1_idx = np.argmax(counts)
    mu1_init = bin_centers[g1_idx]
    sigma1_init = np.std(data) * 0.15
    a1_init = counts[g1_idx] * bin_width
    a2_init = a1_init * 0.3
    sigma2_init = sigma1_init * 1.4

    try:
        p0 = [a1_init, mu1_init, sigma1_init, a2_init, sigma2_init,
              np.max(counts)*0.1, 0, 0]
        popt, _ = curve_fit(dean_jett_fox, bin_centers, counts, p0=p0, maxfev=10000)
        a1, mu1, sigma1, a2, sigma2 = popt[:5]
        g1_total, g2_total = a1, a2
        s_total = max(0, len(data) * bin_width - g1_total - g2_total)
        total = g1_total + s_total + g2_total
        sub_g1 = np.sum(data < mu1 - 3*sigma1)
        return {
            'G1%': g1_total/total*100, 'S%': s_total/total*100,
            'G2M%': g2_total/total*100, 'subG1%': sub_g1/len(data)*100,
            'G2/G1_ratio': 2*mu1/mu1, 'G1_CV': sigma1/mu1*100
        }
    except RuntimeError:
        print("Fitting failed — try simple gating or manual analysis")
        return None

if __name__ == '__main__':
    data = load_intden(sys.argv[1])
    results = fit_cell_cycle(data)
    if results:
        for k, v in results.items(): print(f"  {k}: {v:.1f}")
```

### §8.4 Quality control

| Check | Good | Bad | Action |
|-------|------|-----|--------|
| G2/G1 ratio | 1.95-2.05 | <1.8 or >2.2 | Check segmentation; merged nuclei shift G1 up |
| G1 CV | <8% | >15% | Inconsistent staining or focus variation |
| Sub-G1 | <5% (normal) | >20% (unexpected) | May be apoptosis or debris |
| Super-G2 | <3% | >10% | Doublets — filter by Area vs IntDen |

### §8.5 Doublet discrimination
G2/M nuclei have same area as G1 but 2x IntDen. Doublets have 2x area AND 2x IntDen. Exclude nuclei with Area >1.5x median AND IntDen >1.5x median.

---

## §9 Multi-Marker Classification

### §9.1 Double-labeling interpretation matrices

**EdU + Ki67:**

|  | Ki67+ | Ki67- |
|--|-------|-------|
| **EdU+** | Active cycling | Recently exited cycle |
| **EdU-** | In cycle (G1/G2) | Quiescent (G0) |

**Ki67 + CC3:**

|  | CC3+ | CC3- |
|--|------|------|
| **Ki67+** | Mitotic crisis | Proliferating |
| **Ki67-** | Quiescent death | Quiescent viable |

**pHH3 + Ki67:**

|  | pHH3+ | pHH3- |
|--|-------|-------|
| **Ki67+** | In mitosis | Cycling (G1/S/G2) |
| **Ki67-** | Rare (artifact) | Quiescent |

### §9.2 Multi-marker scoring pattern

```javascript
// General pattern: measure N markers per nucleus, classify by thresholds
// After StarDist segmentation on DAPI:
for (ch = 0; ch < nMarkers; ch++) {
    selectWindow(markerChannels[ch]);
    run("Set Measurements...", "mean redirect=None decimal=3");
    roiManager("Deselect");
    roiManager("Measure");
    // Store means in array
}
// Classify each nucleus based on marker combination
```

### §9.3 Spectral bleedthrough correction
If X% of channel A bleeds into channel B: `Corrected_B = Raw_B - (X/100) * Raw_A`

Recommended well-separated fluorophore combinations:
- DAPI (405) + AF488 (488) + AF594 (594) + AF647 (647)
- Hoechst + GFP + mCherry + AF647

---

## §10 High-Content Screening

### §10.1 Batch processing pattern

```javascript
inputDir = "/path/to/plate_images/";
list = getFileList(inputDir);
for (f = 0; f < list.length; f++) {
    if (!endsWith(list[f], ".tif")) continue;
    open(inputDir + list[f]);
    wellID = substring(getTitle(), 0, 3);
    // ... segment, measure, classify (any workflow above) ...
    row = nResults;
    setResult("Well", row, wellID);
    setResult("TotalNuclei", row, totalNuclei);
    setResult("Positive", row, nPositive);
    setResult("Index", row, prolifIndex);
    updateResults();
    close("*");
}
saveAs("Results", outputDir + "plate_summary.csv");
```

### §10.2 Assay quality: Z-factor

```
Z' = 1 - (3*SD_pos + 3*SD_neg) / |mean_pos - mean_neg|

Z' > 0.5: Excellent    0 < Z' < 0.5: Marginal    Z' < 0: Poor
```

```python
import numpy as np

def z_factor(pos_values, neg_values):
    sep = abs(np.mean(pos_values) - np.mean(neg_values))
    if sep == 0: return float('-inf')
    return 1.0 - (3*np.std(pos_values, ddof=1) + 3*np.std(neg_values, ddof=1)) / sep

def ssmd(pos_values, neg_values):
    """SSMD >= 3: excellent; 2-3: good; 1-2: fair; <1: poor"""
    return (np.mean(pos_values) - np.mean(neg_values)) / \
           np.sqrt(np.var(pos_values, ddof=1) + np.var(neg_values, ddof=1))
```

### §10.3 Normalization methods

| Method | Formula | Use case |
|--------|---------|----------|
| Percent of control | sample / mean_neg x 100 | Simple, intuitive |
| Percent activity | (sample - mean_neg) / (mean_pos - mean_neg) x 100 | Normalized to range |
| Z-score | (sample - mean_plate) / SD_plate | Plate-to-plate comparison |
| Robust Z-score | (sample - median_plate) / MAD_plate | Resistant to outliers |
| B-score | Median polish residuals | Corrects row/column effects |

### §10.4 Edge effect detection
Compare edge wells to interior wells. If difference >10%, consider B-score normalization or excluding edge wells.

---

## §11 IHC-Specific Methods (Chromogenic)

### §11.1 Colour deconvolution

```javascript
// Standard IHC Ki67-DAB workflow
run("Colour Deconvolution", "vectors=[H DAB]");
// Colour_1 = Hematoxylin (all nuclei), Colour_2 = DAB (positive), Colour_3 = Residual

// Count ALL nuclei from hematoxylin
selectWindow(title + "-(Colour_1)");
run("8-bit"); run("Invert");
run("Gaussian Blur...", "sigma=1");
setAutoThreshold("Otsu dark");
run("Convert to Mask"); run("Fill Holes"); run("Watershed");
run("Analyze Particles...", "size=50-2000 circularity=0.3-1.00 show=Nothing summarize add");
totalNuclei = roiManager("count");

// Count DAB+ nuclei — Triangle or MaxEntropy typically work better than Otsu for DAB
roiManager("Deselect"); roiManager("Delete");
selectWindow(title + "-(Colour_2)");
run("8-bit"); run("Invert");
run("Gaussian Blur...", "sigma=1");
setAutoThreshold("Triangle dark");
run("Convert to Mask"); run("Fill Holes"); run("Watershed");
run("Analyze Particles...", "size=50-2000 circularity=0.3-1.00 show=Nothing summarize add");
dabPositive = roiManager("count");
```

### §11.2 Area fraction method (when individual nuclei are hard to segment)

```javascript
// Measure % tissue area that is DAB-positive (relative comparisons only)
run("Colour Deconvolution", "vectors=[H DAB]");
selectWindow(title + "-(Colour_2)");
run("8-bit"); run("Invert");
setAutoThreshold("Triangle dark");
run("Set Measurements...", "area area_fraction redirect=None decimal=3");
run("Measure");
```

### §11.3 Common vector sets

| Vector set | Use for |
|-----------|---------|
| H DAB | Standard IHC (Ki67, CC3, TUNEL, PCNA) |
| H&E | H&E stained tissue |
| H AEC | AEC-based IHC |
| FastRed FastBlue DAB | Triple chromogenic |
| User values | Lab-specific optimization (measure OD from single-stain regions) |

### §11.4 IHC Profiler plugin
Automated IHC scoring — provides H-score and percentage breakdown. Install via update sites.

---

## §12 Statistical Considerations

### §12.1 Unit of replication (critical)

The biological replicate is the independent experimental unit, NOT individual cells.

| Study design | Biological replicate | Technical replicate |
|-------------|---------------------|---------------------|
| Cell culture | Independent flask/well | Fields within a well |
| Animal study | Individual animal | Sections from one animal |
| Patient study | Individual patient | Cores from one tumour |

Calculate index per biological replicate, then use replicate-level values for statistics.

### §12.2 Test selection

| Comparison | Test |
|-----------|------|
| Two groups, proportions (small n) | Fisher's exact |
| Two groups, proportions (large n) | Chi-squared |
| Two groups, continuous (normal) | Unpaired t-test |
| Two groups, continuous (non-normal) | Mann-Whitney U |
| Multiple groups (normal) | One-way ANOVA + post-hoc |
| Multiple groups (non-normal) | Kruskal-Wallis + Dunn's |
| Paired samples | Paired t-test or Wilcoxon |
| Dose-response | 4-parameter logistic (IC50/EC50) |
| Proportions with covariates | Logistic regression |

### §12.3 Reporting checklist
1. Marker and antibody details (clone, supplier, dilution)
2. Detection method (IF, IHC-DAB, click chemistry)
3. Segmentation method (StarDist model, threshold method)
4. Classification threshold and how determined
5. Total cells counted per sample, number of fields
6. n = number of biological replicates
7. Statistical test and exact p-values
8. Representative images showing positive/negative
9. Sensitivity analysis (results at different thresholds)

---

## §13 Agent Workflow Summary

```bash
# Standard marker-scoring workflow (Ki67/EdU/TUNEL/CC3 — same pattern)
python ij.py macro 'open("/path/to/image.tif");'
python ij.py state
python ij.py metadata
python ij.py capture after_open

python ij.py macro 'run("Split Channels");'
python ij.py windows

# Check for saturation
python ij.py macro 'selectWindow("C1-..."); getStatistics(a,mn,mi,mx); print("Max: "+mx);'

# Segment nuclei with StarDist on DAPI
python ij.py macro 'selectWindow("C1-..."); run("Command From Macro", "command=[de.csbdresden.stardist.StarDist2D], args=[...]"); print("Nuclei: " + roiManager("count"));'

# Measure marker intensity per nucleus
python ij.py macro 'selectWindow("C2-..."); run("Set Measurements...", "area mean min max integrated redirect=None decimal=3"); roiManager("Deselect"); roiManager("Measure");'

# Get results and classify in Python
python ij.py results

# Verify visually
python ij.py macro 'selectWindow("C2-..."); roiManager("Show All");'
python ij.py capture marker_overlay

# Audit
python auditor.py
```

---

## §14 Common Problems & Solutions

| Problem | Solutions |
|---------|----------|
| **Threshold sensitivity** | Use negative control; report sensitivity at threshold +/-20%; consider H-score or mixture model |
| **Variable staining across sections** | Local (per-tile) thresholds; marker/DAPI ratio; batch correction slides |
| **Nuclear overlap (dense tissue)** | StarDist with higher nmsThresh (0.5-0.6); Cellpose cyto2; single Z-slice instead of projection |
| **Autofluorescence** | Image unstained control; use far-red fluorophores (AF647+); Sudan Black B for lipofuscin; subtract autofluorescence channel |
| **Over-counting apoptotic fragments** | Size filter (<25% median area); cluster fragments within a radius; use CC3 (whole-cell marker) |
| **Non-specific staining** | Isotype/secondary-only control; rolling ball background subtraction; SNR-based threshold |
| **Mixed cell populations** | Add cell-type marker channel; score marker only within cell-type+ cells |
| **Focus/illumination artifacts** | Variance filter to exclude low-contrast regions; flat-field correction; tile-based thresholds |

### §14.1 H-score (semi-quantitative, reduces threshold sensitivity)

```
H-score = 1*(% weak) + 2*(% moderate) + 3*(% strong)    Range: 0-300
```

```javascript
// Define intensity bins from negative control or visual calibration
t1 = 30; t2 = 80; t3 = 150;  // Starting points — adjust per experiment
nNeg = 0; nWeak = 0; nMod = 0; nStrong = 0;
for (i = 0; i < nResults; i++) {
    m = getResult("Mean", i);
    if (m < t1) nNeg++;
    else if (m < t2) nWeak++;
    else if (m < t3) nMod++;
    else nStrong++;
}
total = nNeg + nWeak + nMod + nStrong;
hScore = 1*(nWeak*100.0/total) + 2*(nMod*100.0/total) + 3*(nStrong*100.0/total);
```

### §14.2 Focus quality filter

```javascript
run("Duplicate...", "title=focus_map");
run("Variance...", "radius=5");
// Measure per nucleus — exclude those with low variance (out of focus)
```

### §14.3 Apoptotic fragment clustering

```javascript
// Cluster TUNEL+ objects within a radius into single events
// Adjust clusterRadius based on magnification (typically 15-25 pixels)
clusterRadius = 20;
// Simple greedy: for each unvisited object, mark all neighbours within radius as same cluster
```
