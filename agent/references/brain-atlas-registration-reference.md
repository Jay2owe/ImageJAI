# Brain Atlas Registration & Anatomy Reference

Register 2D histological brain sections to standardised atlases (Allen CCFv3, Waxholm)
for reproducible, region-specific quantification. Primary tool: ABBA in Fiji, with
QuPath for downstream analysis.

Sources: ABBA (`biop.epfl.ch/Fiji_ABBA.html`), Allen CCFv3 (`atlas.brain-map.org`),
BigWarp (`imagej.net/plugins/bigwarp`), QuPath (`qupath.github.io`), BrainGlobe
(`brainglobe.info`), Elastix (`elastix.lumc.nl`).

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "How do I register a section to Allen CCFv3?" | §2, §5 |
| "What are bregma coordinates for SCN?" | §7.1, §7.3 |
| "How do I install ABBA / elastix?" | §4 |
| "What's the ABBA registration workflow?" | §5 |
| "Allen ID for SCN / CA1 / hippocampus?" | §8.2, §8.3, §8.4 |
| "How do I identify a coronal level by landmarks?" | §7.2 |
| "Which tool: ABBA vs brainreg vs BigWarp?" | §9 |
| "How do I quantify per region after registration?" | §6 |
| "Can the agent automate ABBA?" | §3, §11 |
| "What do I cite for ABBA / CCFv3 / elastix?" | §12 |
| "Why did my registration fail?" | §13 |
| "Hippocampal subfields at a glance?" | §7.4, §8.4 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '`<term>`' brain-atlas-registration-reference.md` to jump.

### A
`ABBA` §2, §4, §5, §9, §12 · `Affine (Elastix)` §5.3 · `Allen CCFv3` §3, §8, §12 · `Anterior commissure` §7.1, §7.2 · `Aqueduct` §7.2 · `ARH (Arcuate)` §8.3 · `AHN (Anterior hypothalamic)` §8.3 · `AP (bregma AP)` §3, §7.1

### B
`Batch registration` §5.4 · `Bio-Formats Importer` §5.1 · `BigWarp` §3, §5.3, §9, §12 · `BigDataViewer (BDV)` §4.1, §13 · `BIOP` §2, §4.1 · `brainglobe` §8.5, §12 · `brainreg` §9 · `Bregma` §3, §7.1, §7.2

### C
`CA1 / CA2 / CA3` §7.4, §8.2, §8.4, §12 · `CB (Cerebellum)` §8.1 · `CCFv3` §3, §8, §12 · `Cell detection per region` §6.1 · `Cerebrum (CH)` §8.1 · `ClearMap` §9 · `Coronal landmarks` §7.2 · `Corpus callosum` §7.2

### D
`DAPI (registration channel)` §5.1, §13 · `DeepSlice` §3, §5.2, §9, §12 · `DG (Dentate gyrus)` §7.4, §8.2, §8.4 · `DMH` §8.3

### E
`Elastix` §3, §4.2, §5.3, §12 · `ENTl / ENTm (Entorhinal)` §8.2, §8.4 · `Export (Regions to QuPath / ImageJ)` §5.5

### F
`Fiji Update Sites` §4.1

### H
`Hippocampal formation (HPF)` §8.1 · `Hippocampal subfields` §7.4, §8.4 · `Hypothalamus (HY)` §8.1, §8.3

### I
`IHC-DAB workflow` §10.1 · `Installation status` §4.3

### L
`Landmarks (coronal level)` §7.2 · `Lateral ventricle` §7.2 · `LHA` §8.3

### M
`MB (Midbrain)` §8.1 · `ME (Median eminence)` §8.3 · `Methods template (citation)` §12 · `Mouse bregma coordinates` §7.1 · `Multi Measure (per-region)` §6.2, §11

### O
`Olfactory bulb` §7.1 · `Ontology (Allen)` §3, §8 · `Optic chiasm / tract` §7.1, §7.2

### P
`Pipeline (agent / human split)` §3 · `Prefrontal cortex` §7.1 · `PTBIOP` §4.1, §4.3 · `PVH / PVN` §7.1, §7.3, §8.2, §8.3 · `Python post-processing` §6.3

### Q
`QuPath` §2, §5.5, §6.1, §11, §12

### R
`Register > Elastix > Affine / Spline` §5.3 · `ROI Manager (post-export)` §6.2

### S
`SCH (SCN)` §7.1, §7.3, §8.2, §8.3, §13 · `SCN identification` §7.3 · `SCN-specific workflow` §10.3 · `Section positioning` §5.2 · `Set and Check Wrappers` §4.2 · `Set Measurements` §6.2 · `Slice positioning` §5.2 · `SO (Supraoptic)` §7.1, §8.2, §8.3 · `Spline (Elastix)` §5.3 · `StarDist (per region)` §10.2, §11 · `Striatum` §7.1 · `SUB (Subiculum)` §7.4, §8.4 · `Substantia nigra` §7.1

### T
`Tau/Amyloid per region` §10.1 · `TH (Thalamus)` §7.1, §8.1 · `Third ventricle` §7.2, §7.3 · `Transform file (export)` §5.5 · `Transformix` §4.2

### V
`VMH` §8.3

### W
`Watershed Cell Detection` §6.1 · `Waxholm` header · `Whole slide images (WSI)` §13

---

## §2 Quick Start

```
1. Fiji → Plugins > BIOP > Atlas > ABBA - Start
2. Select atlas: Allen Mouse Brain CCFv3 (25 um)
3. Import slices: drag images or import from QuPath project
4. Register: right-click → Elastix > Affine, then Spline
5. Export: right-click → Export Regions to QuPath (or ImageJ ROIs)
```

```bash
# Verify ABBA is available
grep -i "abba\|atlas.*start" .tmp/commands.md
```

---

## §3 Core Concepts

| Term | Definition |
|------|-----------|
| CCFv3 | Allen Mouse Brain Common Coordinate Framework v3 (25 um isotropic, ~1300 regions) |
| Bregma | Skull landmark; sections identified by AP distance from bregma |
| Elastix | Open-source registration toolkit (affine + B-spline); used by ABBA |
| BigWarp | Fiji plugin for manual landmark-based registration (thin-plate spline) |
| Ontology | Hierarchical tree of brain region names/IDs in the atlas |
| DeepSlice | ML tool to auto-determine bregma coordinate of a section |

**Pipeline:**
```
[AGENT: Automated]  →  [HUMAN: Interactive]  →  [AGENT: Automated]
  Pre-processing          ABBA registration        Quantification
  - Open/split channels   - Slice positioning      - Load atlas ROIs
  - Background sub        - Run elastix            - Cell detection
  - QC (histogram,        - BigWarp corrections    - Per-region measurement
    saturation)           - Visual QC              - CSV export
```

ABBA registration is **interactive** — cannot be fully automated via macro.

---

## §4 Installation & Setup

### §4.1 Fiji Update Sites

| Update Site | Purpose |
|------------|---------|
| PTBIOP | ABBA and BIOP tools |
| 3D ImageJ Suite | ABBA dependency |
| BigDataViewer | Large image viewer (ABBA backbone) |
| ImageScience | Image processing library |

Enable via `Help > Update... > Manage Update Sites`, then restart Fiji.

### §4.2 Elastix

External binary (not a Fiji plugin):
1. Download from https://elastix.lumc.nl/
2. Extract (e.g., `C:\elastix`)
3. In Fiji: `Plugins > BIOP > Set and Check Wrappers` → set paths to `elastix.exe` and `transformix.exe`

### §4.3 Current Installation Status

- ABBA JARs present but menu commands may not appear — re-enable PTBIOP
- `elastix_registration_server-0.0.0-STUB.jar` — **STUB only**, elastix not installed
- BigWarp is installed and functional
- QuPath is **not installed** on this system

---

## §5 ABBA Registration Workflow

### §5.1 Image Preparation (agent-automatable)

```bash
python ij.py macro 'run("Bio-Formats Importer", "open=/path/to/section.nd2 color_mode=Composite");'
python ij.py state
python ij.py metadata
python ij.py histogram
python ij.py capture brain_section_overview
```

**Checklist:** image opens, channels identified (DAPI for registration), no DAPI saturation, pixel size calibrated, orientation correct.

### §5.2 Slice Positioning

Each slice must be placed at its correct AP position. Use anatomical landmarks (§7) or DeepSlice (automated, typically within 100–200 um accuracy).

### §5.3 Registration Steps

| Step | Action | Time |
|------|--------|------|
| 1. Affine | Right-click → Register > Elastix > Affine (rotation, translation, scaling) | ~5–10 s/slice |
| 2. Spline | Right-click → Register > Elastix > Spline (local deformation, 15 control points) | ~30 s/slice |
| 3. BigWarp (if needed) | Right-click → Register > BigWarp (manual landmarks for tears/failures) | Manual |

**Always affine first, then spline.** Spline alone produces poor results.

### §5.4 Batch Registration

Select all slices (Ctrl+A) → run Affine on all → run Spline on all → review each, BigWarp-correct failures (~5–10 typically need fixing). ~30–60 min for 80 sections.

### §5.5 Export

| Target | Method |
|--------|--------|
| QuPath (recommended) | Right-click → Export Regions to QuPath |
| ImageJ ROI Manager | Right-click → Export Regions to ImageJ |
| Transform file | For applying same registration to other channels |

---

## §6 Post-Registration Quantification

### §6.1 In QuPath

**Cell detection per region:**
```groovy
def annotations = getAnnotationObjects()
for (annotation in annotations) {
    selectObjects(annotation)
    runPlugin('qupath.imagej.detect.cells.WatershedCellDetection',
        '{"detectionImage": "DAPI", "requestedPixelSizeMicrons": 0.5, ' +
        '"backgroundRadiusMicrons": 8, "sigmaMicrons": 1.5, ' +
        '"minAreaMicrons2": 10, "maxAreaMicrons2": 400, ' +
        '"threshold": 100, "watershedPostProcess": true, ' +
        '"cellExpansionMicrons": 5, "makeMeasurements": true}')
}
```

**Export per-region CSV:**
```groovy
def file = new File(buildFilePath(PROJECT_BASE_DIR, "atlas_measurements.csv"))
def writer = file.newWriter()
writer.writeLine("Region,CellCount,MeanIntensity,Area_um2")
for (ann in getAnnotationObjects()) {
    def cells = ann.getChildObjects()
    def count = cells.size()
    def meanInt = cells.isEmpty() ? 0 :
        cells.collect { it.getMeasurementList().getMeasurementValue("DAB: Mean") }.sum() / count
    writer.writeLine("${ann.getName()},${count},${meanInt},${ann.getROI().getArea()}")
}
writer.close()
```

### §6.2 In ImageJ (via ROI Manager)

```bash
python ij.py macro '
roiManager("Open", "/path/to/atlas_rois.zip");
run("Set Measurements...", "area mean standard min integrated display redirect=None decimal=3");
roiManager("Multi Measure");
'
python ij.py results
python auditor.py
```

### §6.3 Python Post-Processing

```python
import pandas as pd
from scipy import stats

df = pd.read_csv("atlas_measurements.csv")
df["Density"] = df["CellCount"] / (df["Area_um2"] / 1e6)  # cells/mm^2

for region in df["Region"].unique():
    wt = df[(df["Region"] == region) & (df["Genotype"] == "WT")]["Density"]
    mut = df[(df["Region"] == region) & (df["Genotype"] == "CK1d")]["Density"]
    if len(wt) >= 3 and len(mut) >= 3:
        stat, p = stats.mannwhitneyu(wt, mut, alternative="two-sided")
        print(f"{region}: WT={wt.mean():.1f} vs CK1d={mut.mean():.1f}, p={p:.4f}")
```

---

## §7 Neuroanatomy Quick Reference

### §7.1 Bregma Coordinates (Mouse)

| Structure | Bregma (AP, mm) | Notes |
|-----------|----------------|-------|
| Olfactory bulb | +4.0 to +2.5 | Most anterior |
| Prefrontal cortex | +2.5 to +1.5 | Prelimbic, infralimbic |
| Anterior commissure | +0.5 to +0.1 | Key landmark — shape changes dramatically |
| Striatum | +1.5 to -0.5 | Largest subcortical at this level |
| **SCN** | **-0.34 to -0.82** | Small, bilateral, above optic chiasm |
| PVN | -0.58 to -1.22 | Dorsal to SCN |
| SON | -0.46 to -0.82 | Lateral to optic chiasm |
| Hippocampus (anterior) | -0.9 to -1.5 | Dorsal hippocampus appears |
| Hippocampus (mid) | -1.5 to -2.5 | Full trisynaptic circuit |
| Thalamus | -0.8 to -2.5 | Medial, between hippocampi |
| Substantia nigra | -2.9 to -3.6 | Ventral midbrain |
| Cerebellum | -5.5 to -7.5 | Most posterior |

### §7.2 Landmarks for Coronal Level Identification

| Landmark | What to look for | Bregma level |
|----------|-----------------|--------------|
| Lateral ventricle shape | Thin slit → large triangle → narrow | Varies |
| Corpus callosum | Appears ~+1.0, thickens, splits ~-2.0 | +1.0 to -2.0 |
| Anterior commissure | Small oval → large round → disappears | +0.5 to -0.2 |
| Hippocampal formation | First thin strip → full → ventral expansion | -0.9 to -3.5 |
| Third ventricle | Narrow midline slit, widens near hypothalamus | -0.3 to -2.5 |
| Optic chiasm/tract | Midline (chiasm) → lateral (tract) | -0.1 to -2.0 |
| Aqueduct | Replaces 3V at midbrain level | -3.0 to -5.0 |

### §7.3 SCN Identification

```
CORONAL at bregma ~ -0.46 mm

         Cortex
    ┌───────────────────┐
    │    3rd ventricle   │
    │         │          │
    │    ┌────┤────┐     │
    │    │PVN │PVN │     │    ← Dorsal
    │    └────┤────┘     │
    │    ┌────┤────┐     │
    │    │SCN │SCN │     │    ← Bilateral, flanking 3V
    │    └────┤────┘     │
    │    ═════╧═════     │    ← Optic chiasm (ventral)
    └───────────────────┘
```

### §7.4 Hippocampal Subfields

| Subfield | Identification | Cell layer |
|----------|---------------|------------|
| CA1 | Largest, thin pyramidal layer (4–5 cells), near subiculum | Stratum pyramidale |
| CA3 | Large pyramidal neurons (6–8 cells thick), near DG | Stratum pyramidale, thicker |
| DG | V/C-shaped band of small granule cells | Stratum granulosum |
| Hilus (CA4) | Inside DG concavity, scattered large neurons | Polymorphic layer |
| Subiculum | Between CA1 and entorhinal cortex, broader | Less organised pyramidal |

---

## §8 Allen CCFv3 Ontology

### §8.1 Major Divisions

| ID | Abbrev | Name | Key sub-regions |
|----|--------|------|-----------------|
| 567 | CH | Cerebrum | Cortex, hippocampus, amygdala, striatum |
| 1089 | HPF | Hippocampal formation | CA1, CA2, CA3, DG, subiculum, entorhinal |
| 1097 | HY | Hypothalamus | SCH (SCN), PVH, SO, LHA, DMH, VMH |
| 549 | TH | Thalamus | Relay nuclei, reticular, habenula |
| 313 | MB | Midbrain | Colliculi, SN, VTA |
| 512 | CB | Cerebellum | Molecular, Purkinje, granular layers |

### §8.2 Lab-Relevant Regions

| Allen ID | Abbrev | Name | Relevance |
|---------|--------|------|-----------|
| **286** | **SCH** | Suprachiasmatic nucleus | Primary circadian pacemaker |
| 149 | PVH | Paraventricular hypothalamic nucleus | Circadian output target |
| 163 | SO | Supraoptic nucleus | Near SCN — avoid confusing |
| 382 | CA1 | CA1 hippocampus | AD primary target |
| 463 | CA3 | CA3 hippocampus | Mossy fibre input |
| 726 | DG | Dentate gyrus | Neurogenesis |
| 909 | ENT | Entorhinal cortex | Earliest AD cortical pathology |

### §8.3 Hypothalamic Nuclei (Complete)

| Allen ID | Abbrev | Name | Function |
|---------|--------|------|----------|
| 286 | SCH | Suprachiasmatic nucleus | Master circadian clock |
| 149 | PVH | Paraventricular hypothalamic nucleus | CRH, AVP, oxytocin |
| 163 | SO | Supraoptic nucleus | AVP, oxytocin |
| 126 | DMH | Dorsomedial hypothalamic nucleus | Feeding, circadian output |
| 141 | VMH | Ventromedial hypothalamic nucleus | Satiety, defence |
| 194 | LHA | Lateral hypothalamic area | Arousal, feeding (orexin) |
| 223 | ARH | Arcuate hypothalamic nucleus | Feeding (AgRP/POMC) |
| 88 | AHN | Anterior hypothalamic nucleus | Thermoregulation |
| 599 | ME | Median eminence | Neuroendocrine secretion |

### §8.4 Hippocampal Subfields

| Allen ID | Abbrev | Name | Layers |
|---------|--------|------|--------|
| 382 | CA1 | CA1 field | so, sp, sr, slm |
| 423 | CA2 | CA2 field | so, sp, sr, slm |
| 463 | CA3 | CA3 field | so, sp, sl, sr |
| 726 | DG | Dentate gyrus | mo, sg, po (hilus) |
| 502 | SUB | Subiculum | mo, sp, sr |
| 909 | ENTl | Entorhinal (lateral) | Layers II–VI |
| 918 | ENTm | Entorhinal (medial) | Layers II–VI |

### §8.5 Programmatic Access

```python
# brainglobe (recommended)
from brainglobe_atlasapi import BrainGlobeAtlas
atlas = BrainGlobeAtlas("allen_mouse_25um")
scn = atlas.structures["SCH"]
scn_mask = atlas.get_structure_mask("SCH")

# Allen REST API
# curl "http://api.brain-map.org/api/v2/data/Structure/query.json?criteria=[acronym$eqSCH]"
```

---

## §9 Alternative Registration Tools

| Tool | Type | Automation | When to use |
|------|------|-----------|-------------|
| **ABBA** | 2D section → atlas | Semi-auto | Standard choice for histological sections |
| **brainreg** | 3D volume → atlas | Fully auto | Lightsheet / serial 2-photon 3D data |
| **BigWarp** | Manual landmarks | Manual | When ABBA fails; non-standard orientations |
| **ClearMap** | 3D lightsheet → atlas | Automated | Cleared tissue + lightsheet |
| **DeepSlice** | Slice positioning | Automated (ML) | Use within ABBA for auto bregma assignment |

**BigWarp landmark file format (.csv):**
```csv
"Landmark","Active","movingX","movingY","fixedX","fixedY"
"1","true",234.5,567.8,100.2,200.3
```

---

## §10 Common Lab Workflows

### §10.1 Tau/Amyloid per Brain Region (IHC-DAB)

```
1. [AGENT] Open section, colour deconvolution (H-DAB)
2. [HUMAN] Register in ABBA → export regions to QuPath
3. [QUPATH] Per-region DAB area fraction
4. [PYTHON] Compare area fraction between genotypes
```

### §10.2 Cell Counting per Region (IF)

```
1. [AGENT] Open, split channels, identify DAPI + marker
2. [HUMAN] Register in ABBA using DAPI → export regions
3. [AGENT/QUPATH] StarDist detection within each atlas region
4. [PYTHON] Cell density (cells/mm^2) per region, compare groups
```

### §10.3 SCN-Specific (No ABBA Needed)

SCN is small and easily identified manually — full atlas registration is optional. See `scn-analysis-reference.md` for manual ROI workflows.

---

## §11 Agent Capabilities

| Step | Agent can do? | How |
|------|-------------|-----|
| Open images (Bio-Formats) | Yes | `python ij.py macro 'run("Bio-Formats Importer"...)'` |
| Channel splitting / background sub | Yes | Standard macros |
| ABBA registration | **No** | Interactive GUI, human-in-the-loop |
| Load atlas ROIs | Yes | `roiManager("Open", ...)` |
| Per-region measurement | Yes | `roiManager("Multi Measure")` |
| StarDist per region | Yes | Loop over ROIs |
| Export / audit results | Yes | `saveAs("Results", ...)` / `python auditor.py` |

---

## §12 Reporting & Citations

**Methods template:** "Sections registered to Allen CCFv3 [Wang 2020] using ABBA [Chiaruttini 2022] with elastix [Klein 2010] affine + B-spline transforms and BigWarp [Bogovic 2016] correction where needed. Regions exported to QuPath [Bankhead 2017] for quantification."

| Tool | Citation |
|------|----------|
| Allen CCFv3 | Wang Q et al. (2020) *Cell* 181(4):936-953 |
| ABBA | Chiaruttini N et al. (2022) *Front Comput Sci* 4:780 |
| Elastix | Klein S et al. (2010) *IEEE Trans Med Imaging* 29(1):196-205 |
| BigWarp | Bogovic JA et al. (2016) *IEEE ISBI* 2016:1123-1126 |
| QuPath | Bankhead P et al. (2017) *Sci Rep* 7:16878 |
| DeepSlice | Carey H et al. (2023) *Nat Commun* 14:5884 |
| BrainGlobe | Claudi F et al. (2020) *JOSS* 5(54):2668 |

---

## §13 Tips & Gotchas

| Issue | Guidance |
|-------|----------|
| DAPI quality | Registration aligns DAPI to atlas Nissl — weak/saturated DAPI = poor registration |
| Tissue damage | Tears/folds cause local failures — use BigWarp landmarks around damaged areas |
| Wrong bregma level | Use anatomical landmarks (§7.2) or DeepSlice |
| Skip affine → spline only | Always affine first |
| Not checking visually | Review every slice after registration |
| Non-DAPI for registration | Must use DAPI/autofluorescence (matches Nissl) |
| Atlas as ground truth | Boundaries are approximate — report at appropriate hierarchy level |
| Mixed left/right hemispheres | Register consistently or analyse separately |
| Atlas granularity | Too fine + small N inflates multiple comparisons without insight |
| Large files (1–10 GB WSI) | ABBA uses BDV (lazy loading); QuPath handles WSI better than ImageJ |
| Allen CCFv3 SCN limitation | Does NOT subdivide into dorsal/ventral — must do manually |
