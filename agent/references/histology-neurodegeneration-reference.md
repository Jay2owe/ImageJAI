# Histology for Neurodegenerative Diseases — Reference

Actionable reference for an ImageJ/Fiji agent processing IHC/IF images of
neurodegenerative disease tissue. Antibody tables, staging systems, DAB
analysis commands, brain regions, mouse models, quantification standards.

---

## 1. Antibody & Marker Tables

### 1.1 Amyloid-Beta Detection

| Antibody/Stain | Target | Notes |
|----------------|--------|-------|
| **6E10** | Abeta 1-16 | Most sensitive; all plaque types + APP. Formic acid 88% 5 min retrieval. |
| **4G8** | Abeta 17-24 | All plaque types + APP. Formic acid retrieval. |
| **Anti-Abeta42** | C-terminus Abeta42 | Specific for aggregation-prone species. |
| **Anti-Abeta40** | C-terminus Abeta40 | Predominant in CAA vessels. |
| **ThioS** | Beta-sheet amyloid | Fluorescent (FITC filter). Neuritic plaques, NFTs, CAA. NOT diffuse plaques. High lipofuscin background in aged brain. |
| **Congo Red** | Amyloid | Apple-green birefringence under polarised light. Less background than ThioS but lower sensitivity. |

6E10 detects more plaque deposition than Gallyas or ThioS because it binds all Abeta conformations including diffuse (non-fibrillar).

### 1.2 Tau / Neurofibrillary Tangle Detection

| Antibody | Epitope | Best For |
|----------|---------|----------|
| **AT8** | pSer202/pThr205 | **Gold standard for Braak staging.** Pretangles through ghost tangles. HIER citrate pH 6.0. |
| **PHF-1** | pSer396/pSer404 | Mature + ghost tangles. Weak on pretangles. |
| **MC1** | Conformational (7-9 + 312-322) | Earliest misfolded tau. |
| **RD3** | 3-repeat tau | Pick bodies. |
| **RD4** | 4-repeat tau | CBD, PSP inclusions. |
| **CP13** | pSer202 | Earlier phosphorylation than AT8. |

**Tangle maturity (AT8 vs PHF-1):** Pretangles: AT8+++/PHF-1+. Mature: AT8+++/PHF-1+++. Ghost: AT8+/PHF-1++.

### 1.3 Alpha-Synuclein Detection

| Antibody | Target | Notes |
|----------|--------|-------|
| **pSer129-alphaSyn (EP1536Y)** | Phospho-Ser129 | **Gold standard.** ~90% pathological aSyn is pSer129. EP1536Y most specific (others cross-react with neurofilament). FA 80% 5 min + citrate. |
| **LB509** | aSyn 115-122 | Human-specific. Less sensitive than pSer129. |
| **5G4** | Aggregation-specific | Conformation-specific for aggregated aSyn. |

### 1.4 TDP-43 Detection

| Antibody | Target | Notes |
|----------|--------|-------|
| **pTDP-43 (pSer409/410)** | Phospho-TDP-43 | **Gold standard.** Pathological TDP-43 is cytoplasmic, hyperphosphorylated, ubiquitinated. HIER citrate pH 6.0. |
| **Anti-TDP-43 (total)** | All TDP-43 | Shows nuclear clearing + cytoplasmic inclusions. |

**Key diagnostic feature:** Loss of normal nuclear TDP-43 + cytoplasmic inclusions. Assess both.

### 1.5 Neuronal Markers

| Marker | Target | Notes |
|--------|--------|-------|
| **NeuN (RBFOX3)** | Mature neuron nuclei | Pan-neuronal. Does NOT label Purkinje or inferior olivary neurons. |
| **MAP2** | Dendrites + soma | NOT axons. Dendritic arborisation. |
| **SMI-32** | Non-phospho NF-H | Large pyramidal neurons (layers III, V). Loss = degeneration. |
| **Cresyl Violet (Nissl)** | Rough ER | Histochemical. Neuronal counts and cytoarchitecture. |
| **Fluoro-Jade B/C** | Degenerating neurons | Green fluorescence. More sensitive than H&E. |

### 1.6 Glial Markers

**Astrocytes:**

| Marker | Notes |
|--------|-------|
| **GFAP** | Gold standard. Strong in reactive astrocytes and white matter. Misses protoplasmic grey matter astrocytes. |
| **S100beta** | More sensitive for grey matter astrocytes. Also some oligodendrocytes. |
| **ALDH1L1** | Pan-astrocyte. More comprehensive than GFAP. |
| **C3** | A1/neurotoxic reactive astrocytes. |

**Microglia:**

| Marker | Notes |
|--------|-------|
| **Iba1 (AIF1)** | All microglia + macrophages. NOT microglia-specific. Labels entire cell. |
| **CD68** | Lysosomal. Upregulated in activated/phagocytic microglia. |
| **TMEM119** | Homeostatic microglia-specific. Lost in DAM. |
| **P2RY12** | Homeostatic microglia-specific. Lost on activation. |
| **TREM2** | DAM/lipid-responsive. AD risk gene. |

**Morphological states (Iba1):** Ramified (small soma, long branching processes) -> Hypertrophic (enlarged soma, thick processes) -> Amoeboid (large round soma, no processes).

**Oligodendrocytes:** Olig2 (lineage), MBP (myelin sheaths), SOX10 (pan-lineage), NG2 (OPC-specific).

### 1.7 Synaptic Markers

| Marker | Compartment | Notes |
|--------|------------|-------|
| **Synaptophysin** | Presynaptic vesicles | Most widely used. Reduced in AD. |
| **PSD-95** | Postsynaptic density | Gold standard postsynaptic. Reduced in AD. |
| **SV2A** | Presynaptic vesicles | PET tracer target ([11C]UCB-J). |
| **VGLUT1/2** | Presynaptic (glutamatergic) | Excitatory synapse-specific. |
| **VGAT** | Presynaptic (GABAergic) | Inhibitory synapse-specific. |

Quantify by optical density/mean grey value in neuropil, NOT puncta counting (too small for light microscopy).

### 1.8 Vascular Markers

| Marker | Target | Notes |
|--------|--------|-------|
| **Collagen IV** | Basement membrane | All vessels. CAA assessment. |
| **CD31 (PECAM-1)** | Endothelial cells | Pan-endothelial. Vessel density. |
| **Claudin-5** | Tight junctions | BBB integrity. Lost in neurodegeneration. |
| **Fibrinogen** | Plasma protein | Extravascular = BBB leakage. |
| **PDGFR-beta** | Pericytes | Pericyte loss = BBB breakdown. |

### 1.9 Other Markers

| Marker | Target | Notes |
|--------|--------|-------|
| **Huntingtin (MW8, EM48)** | Mutant HTT | NIIs in neocortex layers V-VI and III. |
| **PrP (12F10, 3F4)** | Prion protein | Harsh retrieval: FA 98% + autoclave + PK. |
| **Ubiquitin** | Ubiquitinated inclusions | Non-specific. Labels NFTs, TDP-43, LBs. |
| **p62/SQSTM1** | Autophagy receptor | Labels most protein aggregates. |

---

## 2. Silver Stains

| Method | Targets | Key Properties |
|--------|---------|---------------|
| **Gallyas** | NFTs, neuropil threads | Most reproducible. Predilection for 4R tau. Does NOT stain diffuse plaques. Used for Braak staging. |
| **Bielschowsky** | NFTs, plaques (neuritic + some diffuse), axons | More sensitive for plaques. Less reproducible. Used for CERAD scoring. |
| **Campbell-Switzer** | Plaques + NFTs | Preferentially stains 3R tau. Complementary to Gallyas. |

**Differential staining:** AD NFTs (3R+4R): Gallyas+ AND CS+. Pick bodies (3R): Gallyas-, CS+. CBD/PSP (4R): Gallyas+, CS-.

ImageJ: Silver-stained structures are dark brown/black on light background. Use greyscale + dark threshold (Yen, Triangle).

---

## 3. Special Stains

| Stain | Target | Use |
|-------|--------|-----|
| **Luxol Fast Blue (LFB)** | Myelin | Demyelination. Loss of blue = myelin loss. Often + Cresyl Violet. |
| **TUNEL** | Fragmented DNA | Apoptotic cells. |
| **Fluoro-Jade B/C** | Degenerating neurons | Green fluorescence. Any death mechanism. |
| **Prussian Blue** | Hemosiderin (iron) | Old microhaemorrhages (CAA). |
| **PAS** | Glycogen, basement membrane | Corpora amylacea. |

---

## 4. Pathological Features by Disease (Summary)

### 4.1 Alzheimer's Disease

**Plaque types:**

| Type | ThioS/Congo Red | 6E10/4G8 | Significance |
|------|----------------|----------|-------------|
| Diffuse | Negative | Positive | Pre-amyloid, not counted for CERAD |
| Neuritic | Positive | Positive | **Diagnostic.** Counted for CERAD. |
| Cored | Positive | Positive | Subset of neuritic with dense core |

**Other AD pathology:** Neuropil threads (AT8+), CAA (Abeta in vessel walls — type 1: capillary + arterioles; type 2: arterioles only), granulovacuolar degeneration (CA1/CA2, 2-4 um vacuoles), Hirano bodies (eosinophilic rods in CA1).

### 4.2 PD / DLB

| Type | Location | Morphology |
|------|----------|-----------|
| Classical LB | SN, locus coeruleus | Dense eosinophilic core + pale halo. 8-30 um. pSer129+ |
| Cortical LB | Neocortex, limbic | Less defined, often requires IHC. pSer129+ |
| Lewy neurites | Neuropil | Thread-like pSer129+. Often more numerous than LBs. |

### 4.3 FTLD-TDP Subtypes

| Type | Morphology | Clinical | Genetics |
|------|-----------|---------|----------|
| A | Short dystrophic neurites + NCIs | bvFTD, nfvPPA | GRN |
| B | Moderate NCIs, few DNs | bvFTD + ALS | C9orf72 |
| C | Long thick DNs, sparse NCIs | svPPA | Sporadic |
| D | Neuronal intranuclear inclusions | IBMPFD | VCP |
| E | Granulofilamentous NCIs, grains | Rapid FTD | Rare |

### 4.4 FTLD-Tau

- **Pick bodies:** Round cytoplasmic inclusions. 3R tau (RD3+). Gallyas-. Dentate gyrus, frontal/temporal cortex.
- **CBD:** Astrocytic plaques, ballooned neurons. 4R tau (RD4+). Gallyas+.
- **PSP:** Tufted astrocytes, globose NFTs in brainstem/basal ganglia. 4R tau. Gallyas+.

### 4.5 Other Diseases

- **Huntington's:** Striatal neuronal loss (Vonsattel grade 0-4). NIIs (anti-huntingtin, ubiquitin, p62). Medium spiny neurons most vulnerable.
- **Prion:** Spongiform change + neuronal loss + gliosis. PrP deposition patterns vary by subtype (synaptic in sCJD, florid plaques in vCJD).
- **CTE:** Perivascular neuronal p-tau (AT8+) at sulcal depths. McKee stages I-IV.
- **LATE:** TDP-43 in amygdala (stage 1) -> + hippocampus (2) -> + frontal cortex (3). Common in >80 years. Mimics AD.

---

## 5. Staging Systems

### 5.1 NIA-AA "ABC" Score (Alzheimer's)

| Score | Assessment | Method |
|-------|-----------|--------|
| **A** (Amyloid) | Thal phase 0-5 -> A0-A3 | Abeta IHC (6E10/4G8) on standard regions |
| **B** (Braak) | Braak NFT stage I-VI -> B0-B3 | AT8 IHC on standard regions |
| **C** (CERAD) | Neuritic plaque density -> C0-C3 | Bielschowsky silver or ThioS. Sparse 1-5, Moderate 6-19, Frequent 20+ per 100x field. |

**Combined:** Not / Low / Intermediate / High AD neuropathological change. "Intermediate" or "High" sufficient explanation for dementia.

### 5.2 Thal Amyloid Phases

| Phase | Distribution |
|-------|-------------|
| 1 | Neocortex |
| 2 | + Allocortex (entorhinal, hippocampus) |
| 3 | + Striatum, diencephalon, basal forebrain |
| 4 | + Brainstem |
| 5 | + Cerebellum |

### 5.3 Braak NFT Stages (AT8)

| Stage | Distribution | Clinical |
|-------|-------------|---------|
| I-II | Transentorhinal / entorhinal cortex | Preclinical |
| III-IV | Hippocampus, amygdala, inferior temporal | MCI |
| V-VI | Association cortex -> primary sensory/motor | Dementia |

### 5.4 Braak PD Stages (Alpha-Synuclein)

| Stage | Distribution |
|-------|-------------|
| 1-2 | DMV, olfactory bulb, locus coeruleus (premotor) |
| 3-4 | SN, amygdala, temporal mesocortex (motor symptoms) |
| 5-6 | Neocortex (cognitive decline) |

### 5.5 Other Staging Systems

| System | Disease | Scale |
|--------|---------|-------|
| McKeith | DLB | Brainstem / amygdala / limbic / diffuse neocortical |
| Vonsattel | Huntington's | Grade 0-4 (striatal neuronal loss) |
| LATE-NC | LATE | Stage 0-3 (TDP-43: amygdala -> hippocampus -> frontal) |
| McKee | CTE | Stage I-IV (perivascular tau: focal -> widespread) |
| FTLD-TDP types | FTLD | Types A-E (inclusion morphology) |

---

## 6. Brain Anatomy for ROI Selection

### 6.1 Regions Affected by Disease

| Disease | Primary Regions | Secondary |
|---------|----------------|-----------|
| AD | Entorhinal cortex, hippocampus (CA1, subiculum), amygdala | Temporal, frontal, parietal association cortex |
| PD | SN pars compacta, locus coeruleus, DMV | Amygdala, hippocampus (CA2-3), olfactory bulb |
| DLB | Amygdala, cingulate, temporal cortex, SN | Widespread neocortex |
| bvFTD | Orbitofrontal, anterior cingulate, anterior temporal | Insula, striatum |
| ALS | Motor cortex (Betz cells), spinal anterior horn | Hippocampus (ALS-FTD) |
| HD | Caudate, putamen (medium spiny neurons) | Globus pallidus, cortex layers III/V-VI |
| Prion | Cortex, striatum, thalamus, cerebellum | Variable by subtype |

### 6.2 Hippocampal Subfield Vulnerability

| Subfield | Vulnerability | Key Pathology |
|----------|-------------|---------------|
| **CA1** | Very high (AD, LATE) | NFTs, neuronal loss, TDP-43 |
| **CA2** | Low (AD), high (DLB/PD) | Lewy neurites |
| **CA3** | Relatively resistant | Mossy fibre sprouting (epilepsy) |
| **Dentate Gyrus** | Variable | Pick bodies (FTD-tau) |
| **Subiculum** | High (AD) | NFTs, neuronal loss |
| **Entorhinal Cortex** | Very high (AD) | Layer II stellate neurons: earliest NFTs |

### 6.3 Cortical Layer Vulnerability

| Layer | Vulnerability |
|-------|---------------|
| II | Entorhinal: earliest AD NFTs |
| III | AD (association cortex), HD |
| V | ALS (Betz cells), HD, AD (late), CTE |
| V-VI | HD huntingtin inclusions |

### 6.4 Key Brainstem Structures

- **SN pars compacta:** Dopaminergic + neuromelanin. Primary PD target.
- **Locus coeruleus:** Noradrenergic. Early in AD and PD.
- **DMV:** Earliest PD alpha-synuclein (Braak stage 1).
- **Nucleus basalis of Meynert:** Cholinergic. Major loss in AD.

---

## 7. ImageJ Analysis Pipelines

### 7.1 DAB Quantification (Colour Deconvolution)

```
// Colour Deconvolution for H-DAB
open("/path/to/image.tif");
run("Colour Deconvolution", "vectors=[H DAB]");
// Creates: (Colour_1)=Hematoxylin, (Colour_2)=DAB, (Colour_3)=Residual

selectWindow("*-(Colour_2)");
// DAB appears DARK — lower values = stronger staining
setAutoThreshold("Triangle dark");

// Area fraction
run("Set Measurements...", "area area_fraction limit display redirect=None decimal=3");
run("Measure");

// Object counting (plaques, cells)
run("Convert to Mask");
run("Watershed");
run("Analyze Particles...", "size=100-Infinity circularity=0.2-1.0 show=Outlines display summarize");
```

**Gotchas:**
- Default "H DAB" vectors are approximate — measure custom vectors from pure stain regions for best results.
- **DAB intensity is NOT quantitative for antigen expression** (non-linear, depends on section thickness, incubation time). Use area fraction instead.
- Optical density: `OD = log10(255 / mean_grey_value)`

### 7.2 Plaque Counting and Sizing

```
// After colour deconvolution + threshold (see 7.1):
run("Convert to Mask");
run("Despeckle");
run("Watershed");
run("Set Measurements...", "area perimeter shape feret's display redirect=None decimal=2");
run("Analyze Particles...", "size=100-100000 circularity=0.3-1.0 show=[Overlay Outlines] display summarize");
```

Typical sizes: Diffuse 20-100 um, Neuritic 30-80 um, Core 10-30 um.

### 7.3 Tangle Counting (AT8)

```
// Semi-automated (manual review recommended):
selectWindow("*-(Colour_2)");
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Analyze Particles...", "size=50-5000 circularity=0.1-0.8 show=Outlines display summarize");
```

For precision: use Cell Counter plugin. Separate counter types for pretangles, mature tangles, ghost tangles.

### 7.4 Area Fraction (% Immunoreactive Area)

```
run("Colour Deconvolution", "vectors=[H DAB]");
selectWindow("*-(Colour_2)");
setAutoThreshold("Triangle dark");
run("Set Measurements...", "area area_fraction limit display");
run("Measure");
// "%Area" = percentage DAB-positive
```

Typical values (very approximate): Abeta in severe AD cortex 5-20%. pTau (AT8) in AD cortex 1-10%. GFAP in reactive astrogliosis 10-40%. Iba1 in normal cortex 2-5%, neuroinflammation 5-20%.

### 7.5 Microglial Morphology Analysis

```
// 1. Isolate individual Iba1+ microglia (threshold + particle analysis)
// 2. Per cell:
//    a. Soma area + circularity: Analyze Particles
//    b. Skeleton analysis: run("Skeletonize"); run("Analyze Skeleton (2D/3D)");
//    c. Sholl analysis: line from soma -> run("Sholl Analysis...");
//    d. Fractal dimension: FracLac plugin
```

Reference values: Ramified soma ~50-100 um2 (low circularity), Activated ~150-400 um2 (higher circularity), Amoeboid ~200-400+ um2 (circularity ~1.0).

### 7.6 Synapse Density (Puncta Colocalization)

```
// Multi-channel (e.g., synaptophysin + PSD-95):
run("Split Channels");
// Per channel:
run("Subtract Background...", "rolling=10");
setAutoThreshold("MaxEntropy");
run("Convert to Mask");
// Colocalize:
imageCalculator("AND create", "pre_mask", "post_mask");
run("Analyze Particles...", "size=0.05-2.0 summarize");
// Normalize per area or per dendrite length (MAP2 channel)
```

### 7.7 Cell Counting

```
// StarDist (best for round nuclei like NeuN, DAPI):
run("StarDist 2D", "model=[Versatile (fluorescent nuclei)] probThresh=0.5 nmsThresh=0.4 outputType=Label Image");

// For DAB: invert DAB channel to pseudo-fluorescent before StarDist.
// Validate against manual counts (F1 > 0.85).
```

### 7.8 ROI Analysis by Brain Region

```
roiManager("Open", "/path/to/ROI_set.zip");
for (i = 0; i < roiManager("count"); i++) {
    roiManager("Select", i);
    run("Measure");
}
```

Naming convention: `CA1_left`, `DG_granular`, `EC_layer_II`, `SN_pars_compacta`.

### 7.9 Batch Processing Template

```
input = "/path/to/input/";
output = "/path/to/output/";
list = getFileList(input);
setBatchMode(true);
for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif")) {
        open(input + list[i]);
        run("Colour Deconvolution", "vectors=[H DAB]");
        selectWindow(list[i] + "-(Colour_2)");
        setAutoThreshold("Triangle dark");
        run("Set Measurements...", "area area_fraction limit display");
        run("Measure");
        close("*");
    }
}
setBatchMode(false);
saveAs("Results", output + "batch_results.csv");
```

---

## 8. Mouse Models

### 8.1 Key Models Summary

| Model | Type | Onset | Plaques | Tangles | Neuronal Loss | Key Antibodies |
|-------|------|-------|---------|---------|--------------|----------------|
| APP/PS1 | Amyloid | 3-4 mo | Yes | No | Minimal | 6E10, 4G8 |
| 5xFAD | Amyloid | 2 mo | Yes (aggressive) | No | Some (layer V) | 82E1, MOAB-2, ThioS |
| 3xTg-AD | Amyloid+Tau | 3 mo (Ab), 12 mo (tau) | Yes | Yes | Moderate | AT8, HT7, 6E10 |
| rTg4510 | Tau | 2-3 mo | No | Yes (robust) | Severe | AT8, PHF-1, MC1 |
| PS19 | Tau | 6 mo | No | Yes | Moderate | AT8, AT180, AT100 |
| aSyn PFF (WT) | Synuclein | 1-2 mo | No | No | ~30% DA (6 mo) | pSer129, TH, DAT |
| SOD1-G93A | ALS | 8 wk | No | No | ~50% MN (end) | ChAT, GFAP, Iba1 |

### 8.2 Mouse vs Human Differences

| Feature | Mouse Models | Human Disease |
|---------|-------------|---------------|
| Neuronal loss | Minimal (most amyloid models) | Severe, progressive |
| Tangles | Absent in amyloid-only models | Core pathology |
| Co-pathology | Usually single transgene product | Multiple proteinopathies |
| Time course | Weeks to months | Decades |
| Genetic basis | Familial mutations overexpressed | >95% sporadic |

### 8.3 Species-Specific Antibody Notes

- **6E10, 4G8, HT7:** Human-specific. Only work in mice with human transgene.
- **Iba1, GFAP, AT8, NeuN, Synaptophysin, PSD-95:** Work in both mouse and human.
- Mouse Abeta does not form plaques (sequence differs at 3 residues).
- Mouse tau is 4R only (humans have 3R + 4R).

---

## 9. Quantitative Standards

### 9.1 Stereology (Unbiased Cell Counting)

**Optical fractionator:** Gold standard. Systematic uniform random sampling (SURS) + optical disector rules.

```
Total count = sum(cells counted) x 1/ssf x 1/asf x 1/tsf
(ssf = section sampling fraction, asf = area sampling fraction, tsf = thickness sampling fraction)
```

- CE (coefficient of error) should be < 0.10
- Count 100-200 cells per region
- Measure actual mounted section thickness (not nominal cut thickness)
- Guard zones 2-3 um at top and bottom

**ImageJ:** Grid overlay (Analyze > Tools > Grid) for systematic sampling. Cell Counter for counting. Not a dedicated stereology platform — for full stereology use Stereo Investigator (MBF) or newCAST.

### 9.2 The Reference Trap

If a brain region atrophies, cell density (cells/mm2) can INCREASE even when total cells DECREASE. Always report total counts (fractionator) or measure and report reference volume alongside density.

### 9.3 Statistical Gotchas

- **Biological vs technical replicates:** Multiple sections from same animal are technical replicates. Average per animal before group comparison. Treating sections as independent n inflates significance.
- **Typical n:** 5-12 animals per group.
- **Tests:** 2 groups: t-test or Mann-Whitney. Multiple groups: ANOVA + Tukey. Braak stages: non-parametric (ordinal).

### 9.4 Reporting Standards

**Minimum for IHC publication:**
- Antibody: target, host, clone, manufacturer, catalogue #, RRID, dilution
- Antigen retrieval method (buffer, pH, temperature, duration)
- Detection system and chromogen/fluorophore
- Positive and negative controls
- Quantification method and software version
- Region(s) analysed, number of fields/sections
- Statistical methods, distinguish biological vs technical replicates

**For digital analysis additionally report:** Colour deconvolution vectors, threshold method/values, particle size/circularity parameters, pixel size.

---

## 10. Best Practices and Pitfalls

### 10.1 Antigen Retrieval

| Method | Agent | Best For |
|--------|-------|----------|
| HIER - Citrate pH 6.0 | 10 mM citrate, 95-100C, 10-30 min | **Default.** Most antigens (tau, GFAP, Iba1, NeuN). |
| HIER - Tris-EDTA pH 9.0 | 10 mM Tris + 1 mM EDTA, 95-100C | More aggressive. Some antigens respond better. |
| Formic acid | 70-98%, RT, 5-20 min | **Essential for Abeta.** Also aSyn. Disrupts beta-sheets. |
| Proteinase K | 10-20 ug/mL, 37C, 5-15 min | Aggregated aSyn, PrP. Reduces normal protein background. |

**Combinations:** Abeta: FA 88% 5 min -> HIER citrate. aSyn: FA 80% 5 min -> citrate (or PK 20 ug/mL 10 min). PrP: FA 98% -> autoclave -> PK. Old formalin tissue: PK -> autoclave EDTA -> FA.

### 10.2 Lipofuscin Autofluorescence (Aged Brain)

Lipofuscin autofluoresces across broad spectrum (green > red > far-red). Major problem for IF in aged tissue.

| Solution | Effectiveness |
|----------|-------------|
| **TrueBlack (Biotium)** | Excellent. Apply after IF, 30 sec-5 min in 70% ethanol. |
| **Sudan Black B** | Good for green/red. Adds far-red background. |
| **Spectral unmixing** | Good. Requires spectral/lambda-scan confocal. |
| **DAB instead of IF** | Eliminates problem. Loses multiplexing. |

ImageJ workaround: Image unstained section, use Image Calculator > Subtract.

### 10.3 Controls

| Control | Purpose |
|---------|---------|
| Positive tissue | Confirms antibody works (e.g., AD brain for Abeta/tau) |
| No primary antibody | Non-specific secondary binding |
| Isotype control | Non-specific primary binding |
| Known negative tissue | Confirms specificity (e.g., young normal brain) |

### 10.4 DAB Threshold Method Selection

| Staining Quality | Recommended Threshold |
|-----------------|----------------------|
| Strong, clear, low background | Otsu |
| Variable intensity, gradual transition | **Triangle (default)** |
| Weak signal, need sensitivity | Moments or Yen |
| Very clean, bimodal | Li or IsoData |
| Batch (consistency critical) | Fixed manual threshold |

### 10.5 Multiplex IF Panels

**TSA/OPAL system:** Up to 8 markers per section. Primary -> HRP-secondary -> TSA fluorophore (covalent) -> strip -> repeat. Same-species primaries OK.

Common panels: Abeta + pTau + NeuN + GFAP + Iba1 + Olig2. pTau + pSer129 + pTDP-43 (co-pathology). GFAP + Iba1 + CD68 + TMEM119 + P2RY12 (glial states).

### 10.6 Co-Pathology

"Pure" single proteinopathy is the exception. AD + Lewy bodies: 30-50%. AD + TDP-43 (LATE): 30-57%. Must characterize ALL proteinopathies, not just the "primary" one.

### 10.7 WSI / Digital Pathology

- **QuPath:** Leading open-source for WSI. DAB quantification, cell detection, pixel classification, batch processing.
- **Fiji:** Bio-Formats imports .svs/.ndpi/.scn. Use virtual stacks for large images.
- **Trainable Weka Segmentation:** Pixel classification when colour deconvolution alone insufficient.

### 10.8 Circadian Considerations

- Clock protein expression varies with time of day — record time of death for post-mortem tissue.
- Microglial morphology varies with circadian phase — sacrifice at consistent ZT.
- When comparing groups, ensure tissue collected at same circadian time or account statistically.

---

## 11. Common Antibody Quick Reference

| Target | Clone | Cat# (example) | Dilution | Retrieval |
|--------|-------|----------------|----------|-----------|
| Abeta | 6E10 | BioLegend 803001 | 1:500-1:1000 | FA 88% 5 min |
| Abeta | 4G8 | BioLegend 800711 | 1:500-1:2000 | FA 88% 5 min |
| pTau | AT8 | Thermo MN1020 | 1:200-1:1000 | Citrate pH 6.0 |
| pTau | PHF-1 | Peter Davies lab | 1:500-1:1000 | Citrate pH 6.0 |
| paSyn | EP1536Y | Abcam ab51253 | 1:500-1:5000 | FA 80% + citrate |
| pTDP-43 | pS409/410 | CosmoBio TIP-PTD-M01 | 1:1000-1:5000 | Citrate pH 6.0 |
| Neurons | NeuN A60 | Millipore MAB377 | 1:100-1:500 | Citrate pH 6.0 |
| Astrocytes | GFAP poly | Dako Z0334 | 1:500-1:2000 | None or citrate |
| Microglia | Iba1 poly | Wako 019-19741 | 1:250-1:1000 | Citrate pH 6.0 |
| Microglia | CD68 KP1 | Dako M0814 | 1:50-1:200 | Citrate pH 6.0 |
| Oligodendrocytes | Olig2 poly | Millipore AB9610 | 1:200-1:500 | Citrate or EDTA |
| Myelin | MBP | Abcam ab40390 | 1:200-1:500 | Citrate pH 6.0 |
| Presynaptic | Synaptophysin | Dako M0776 | 1:50-1:200 | Citrate or EDTA |
| Postsynaptic | PSD-95 | Abcam ab18258 | 1:200-1:500 | Citrate pH 6.0 |
| Vessels | Collagen IV | Abcam ab6586 | 1:200-1:500 | PK or citrate |
| Vessels | CD31 | Various | 1:50-1:200 | Citrate pH 6.0 |
| BBB leak | Fibrinogen | Dako A0080 | 1:500-1:2000 | None |
| Huntingtin | MW8/EM48 | Various | 1:500-1:2000 | Citrate pH 6.0 |
| DA neurons | TH | Millipore AB152 | 1:1000-1:2000 | Citrate pH 6.0 |
| MAP2 | HM-2 | Sigma M4403 | 1:500-1:2000 | Citrate pH 6.0 |
| Neurons | SMI-32 | BioLegend 801701 | 1:500-1:2000 | Citrate pH 6.0 |

---

## 12. Decision Trees

### Which Stain?

```
Amyloid plaques (all types)    -> 6E10 or 4G8 IHC
Amyloid (fibrillar only)       -> ThioS or Congo Red
Neuritic plaques               -> Bielschowsky silver OR tau IHC
NFTs                           -> AT8 IHC (gold standard)
Tangle maturity                -> AT8 (early) + PHF-1 (late) + ThioS (fibrillar)
Lewy bodies/neurites           -> pSer129-aSyn (EP1536Y)
TDP-43 inclusions              -> pTDP-43 (pSer409/410)
Neuronal loss                  -> NeuN or Cresyl Violet
Astrogliosis                   -> GFAP
Microglial activation          -> Iba1 (all) + CD68 (activated)
Myelin degeneration            -> LFB or MBP IHC
Vascular / CAA                 -> Collagen IV or CD31; Abeta40 for CAA
Apoptosis                      -> TUNEL or Fluoro-Jade C
General survey                 -> H&E first, then targeted IHC
```

### Which DAB Threshold?

```
Strong + clear               -> Otsu
Variable intensity            -> Triangle (default)
Weak signal                   -> Moments or Yen
Clean bimodal                 -> Li or IsoData
Batch consistency             -> Fixed manual threshold
```
