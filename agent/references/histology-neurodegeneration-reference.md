# Histology for Neurodegenerative Diseases — Reference

Actionable reference for an ImageJ/Fiji agent processing IHC/IF images of
neurodegenerative disease tissue. Antibody tables, staging systems, DAB
analysis commands, brain regions, mouse models, quantification standards.

Staging systems covered: Braak NFT stages (AT8 tau), CERAD neuritic plaque
density (Bielschowsky/ThioS), Thal amyloid phases (6E10/4G8), Braak PD
stages (alpha-synuclein), McKeith (DLB), Vonsattel (HD), LATE-NC, McKee
(CTE), and FTLD-TDP types A–E. Combined NIA-AA "ABC" score integrates
Thal + Braak + CERAD.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python probe_plugin.py "Plugin..."` — discover plugin parameters at runtime.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Which antibody for plaques / tangles / Lewy bodies / TDP-43?" | §2.1–§2.4, §12 "Which Stain?" |
| "What's the Braak / CERAD / Thal staging scheme?" | §6.1–§6.3 |
| "How do I quantify DAB area fraction?" | §8.1, §8.4 |
| "Which threshold method for DAB?" | §13.4, §12 "Which DAB Threshold?" |
| "What mouse model has plaques + tangles?" | §9.1 |
| "Which regions for AD / PD / FTD ROIs?" | §7.1–§7.4 |
| "How do I retrieve Abeta / aSyn / PrP antigens?" | §13.1 |
| "Why is my IF background awful in aged tissue?" | §13.2 lipofuscin |
| "What's the NIA-AA ABC score?" | §6.1 |
| "Antibody dilution / catalogue quick lookup?" | §11 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '<term>' histology-neurodegeneration-reference.md` to jump.

### A
`4G8` §2.1, §11 · `5G4` §2.3 · `5xFAD` §9.1 · `6E10` §2.1, §11 · `82E1` §9.1 ·
`ABC score (NIA-AA)` §6.1 · `Abeta / amyloid-beta` §2.1, §5.1, §9.1, §11, §13.1 ·
`Abeta40 / Abeta42` §2.1 · `ALDH1L1` §2.6 · `ALS` §5.5, §7.1, §9.1 ·
`alpha-synuclein` §2.3, §5.2, §6.4, §11 · `Alzheimer's disease` §5.1, §6.1–§6.3, §7.1 ·
`amoeboid microglia` §2.6, §8.5 · `amygdala` §5.5, §6.4, §7.1 ·
`Analyze Particles` §8.1, §8.2, §8.3 · `anti-HTT (MW8, EM48)` §2.9, §11 ·
`antibody controls` §13.3 · `antigen retrieval` §13.1 ·
`APP/PS1` §9.1 · `area fraction` §8.1, §8.4 · `astrocytes` §2.6, §8.4 ·
`AT8` §2.2, §6.3, §8.3, §11 · `AT100 / AT180` §9.1 · `autofluorescence` §13.2

### B
`ballooned neurons` §5.4 · `batch processing` §8.9 · `BBB (blood-brain barrier)` §2.8 ·
`Betz cells` §5.5, §7.1, §7.3 · `Bielschowsky silver` §3, §6.1, §12 ·
`Bio-Formats` §13.7 · `biological vs technical replicates` §10.3 ·
`Braak NFT stages` §6.3 · `Braak PD stages` §6.4 · `brainstem` §7.4 ·
`bvFTD` §5.3, §7.1

### C
`C3 (astrocyte)` §2.6 · `C9orf72` §5.3 · `CA1 / CA2 / CA3` §5.1, §7.2 ·
`CAA (cerebral amyloid angiopathy)` §2.1, §2.8, §5.1 · `Campbell-Switzer` §3 ·
`caudate` §7.1 · `CBD` §2.2, §5.4 · `CD31 (PECAM-1)` §2.8, §11 ·
`CD68` §2.6, §11 · `cell counter plugin` §8.3 · `cell counting` §8.7 ·
`CERAD` §6.1 · `ChAT` §9.1 · `circadian phase` §13.8 · `ChAT` §9.1 ·
`Claudin-5` §2.8 · `Cellpose / StarDist` §8.7 · `Cell morphology (microglia)` §2.6, §8.5 ·
`CE (coefficient of error)` §10.1 · `cerebellum` §6.2, §7.1 · `cingulate` §7.1 ·
`coefficient of error (CE)` §10.1 · `Colour Deconvolution` §8.1, §8.4 ·
`collagen IV` §2.8, §11 · `Congo Red` §2.1, §5.1, §12 · `controls (IHC)` §13.3 ·
`co-pathology` §13.6 · `cortical layers` §7.3 · `cortical LB` §5.2 ·
`CP13` §2.2 · `Cresyl Violet (Nissl)` §2.5, §12 · `CTE` §5.5, §6.5 ·
`cytoplasmic inclusions (TDP-43)` §2.4

### D
`DAB quantification` §8.1, §8.4, §13.4 · `DAB threshold selection` §13.4, §12 ·
`DAM (disease-associated microglia)` §2.6 · `DAPI` §8.7 · `DAT` §9.1 ·
`DA neurons (dopaminergic)` §7.4, §9.1, §11 · `dentate gyrus` §5.4, §7.2 ·
`DLB` §5.2, §6.5, §7.1 · `DMV (dorsal motor nucleus vagus)` §6.4, §7.4 ·
`digital pathology / WSI` §13.7 · `diffuse plaques` §5.1, §8.2 ·
`dopaminergic neurons` §7.4, §9.1 · `dystrophic neurites` §5.3

### E
`EDTA retrieval (Tris-EDTA pH 9.0)` §13.1 · `EM48` §2.9, §11 ·
`endothelial cells` §2.8 · `entorhinal cortex` §6.3, §7.1, §7.2 ·
`EP1536Y` §2.3, §11, §12 · `epilepsy (mossy fibre sprouting)` §7.2 ·
`excitatory synapses` §2.7

### F
`fibrinogen` §2.8, §11 · `FITC filter (ThioS)` §2.1 · `florid plaques (vCJD)` §5.5 ·
`Fluoro-Jade B/C` §2.5, §4, §12 · `formic acid retrieval` §2.1, §2.3, §13.1 ·
`4G8` §2.1, §11 · `4R tau` §2.2, §3, §5.4 · `FracLac` §8.5 · `FTLD-tau` §5.4 ·
`FTLD-TDP` §5.3

### G
`Gallyas silver` §3, §5.4 · `GFAP` §2.6, §8.4, §11 · `ghost tangles` §2.2, §8.3 ·
`globose NFTs` §5.4 · `globus pallidus` §7.1 · `grain (FTLD-TDP type E)` §5.3 ·
`granulovacuolar degeneration` §5.1 · `grey matter astrocytes` §2.6 ·
`GRN` §5.3 · `guard zones (stereology)` §10.1

### H
`H&E` §12 · `H-DAB vectors` §8.1 · `HD (Huntington's)` §2.9, §5.5, §6.5, §7.1 ·
`hemosiderin` §4 · `HIER (heat-induced epitope retrieval)` §13.1 ·
`Hirano bodies` §5.1 · `hippocampus / CA subfields` §5.1, §7.1, §7.2 ·
`HT7` §9.1, §9.3 · `huntingtin` §2.9, §11 · `hypertrophic microglia` §2.6

### I
`Iba1 (AIF1)` §2.6, §8.5, §11 · `IBMPFD` §5.3 · `IHC reporting standards` §10.4 ·
`immunoreactive area` §8.4 · `inferior olivary neurons (NeuN negative)` §2.5 ·
`inhibitory synapses` §2.7 · `insula` §7.1 · `IsoData threshold` §13.4, §12

### K
`KP1 (CD68 clone)` §11

### L
`Labkit / Weka` §13.7 · `LATE` §5.5, §6.5 · `Lewy body (LB)` §5.2 ·
`Lewy neurite` §5.2, §7.2 · `lipofuscin` §2.1, §13.2 · `Li threshold` §13.4, §12 ·
`LB509` §2.3 · `locus coeruleus` §6.4, §7.4 · `LFB (Luxol Fast Blue)` §4, §12 ·
`lysosomal marker (CD68)` §2.6

### M
`MAP2` §2.5, §8.6, §11 · `MBP` §2.6, §11, §12 · `MC1` §2.2, §9.1 ·
`McKee (CTE)` §6.5 · `McKeith (DLB)` §6.5 · `medium spiny neurons` §5.5, §7.1 ·
`Meynert (nucleus basalis)` §7.4 · `microglia` §2.6, §8.5, §11 ·
`microhaemorrhages` §4 · `MOAB-2` §9.1 · `Moments threshold` §13.4, §12 ·
`motor neurons` §7.1, §9.1 · `motor cortex` §7.1 · `mouse models` §9 ·
`MW8` §2.9, §11 · `multiplex IF (TSA / OPAL)` §13.5 · `myelin` §2.6, §4

### N
`NCI (neuronal cytoplasmic inclusion)` §5.3 · `NeuN (RBFOX3)` §2.5, §8.7, §11 ·
`neocortex` §5.2, §6.2, §6.3, §6.4, §7.1 · `neuritic plaques` §2.1, §5.1, §8.2, §12 ·
`neuroinflammation` §8.4 · `neuromelanin` §7.4 · `neuropil threads` §3, §5.1 ·
`neuronal loss` §9.1, §12 · `newCAST` §10.1 · `nfvPPA` §5.3 · `NFTs` §2.2, §3, §5.1, §6.3, §8.3 ·
`NIA-AA ABC score` §6.1 · `NG2` §2.6 · `NII (neuronal intranuclear inclusion)` §2.9, §5.3 ·
`NPY / NISSL` §2.5

### O
`OD (optical density)` §2.7, §8.1 · `oligodendrocytes` §2.6 · `Olig2` §2.6, §11 ·
`olfactory bulb` §6.4, §7.1 · `OPAL (TSA)` §13.5 · `optical disector` §10.1 ·
`optical fractionator` §10.1 · `Otsu threshold` §8.3, §13.4, §12 · `orbitofrontal` §7.1

### P
`P2RY12` §2.6, §13.5 · `p62/SQSTM1` §2.9, §5.5 · `PAS` §4 · `PDGFR-beta` §2.8 ·
`PD (Parkinson's)` §5.2, §6.4, §7.1 · `pericytes` §2.8 · `perivascular tau` §5.5, §6.5 ·
`PET tracer (SV2A)` §2.7 · `PFF (preformed fibril)` §9.1 · `PHF-1` §2.2, §9.1, §11 ·
`Pick bodies` §2.2, §3, §5.4, §7.2 · `Pick's disease` §5.4 · `plaques` §2.1, §5.1, §8.2, §12 ·
`polarised light (Congo red)` §2.1 · `positive control` §13.3 · `pre-amyloid` §5.1 ·
`pretangles` §2.2, §8.3 · `PrP (prion protein)` §2.9, §11 · `prion disease` §5.5, §7.1 ·
`proteinase K (PK)` §2.9, §13.1 · `Prussian Blue` §4 · `PSD-95` §2.7, §8.6, §11 ·
`PS19` §9.1 · `pSer129-alphaSyn` §2.3, §5.2, §11, §12 · `pTau` §2.2, §8.4, §12 ·
`pTDP-43` §2.4, §11, §12 · `PSP` §2.2, §5.4 · `putamen` §7.1 · `pyramidal neurons` §2.5

### Q
`QuPath` §13.7

### R
`ramified microglia` §2.6, §8.5 · `RD3` §2.2, §5.4 · `RD4` §2.2, §5.4 ·
`reactive astrocytes` §2.6, §8.4 · `reference trap (stereology)` §10.2 ·
`replicates (biological vs technical)` §10.3 · `reporting standards` §10.4 ·
`ROI analysis` §8.8 · `RRID` §10.4 · `rTg4510` §9.1

### S
`S100beta` §2.6 · `sCJD` §5.5 · `section thickness (stereology)` §10.1 ·
`sensor / sensory cortex` §6.3 · `Sholl analysis` §8.5 · `silver stains` §3 ·
`skeletonize` §8.5 · `SMI-32` §2.5, §11 · `SN (substantia nigra)` §5.2, §6.4, §7.1, §7.4 ·
`SOD1-G93A` §9.1 · `SOX10` §2.6 · `species-specific antibodies` §9.3 ·
`spectral unmixing` §13.2 · `spheroid/synapse colocalization` §8.6 ·
`spinal anterior horn` §7.1 · `Spongiform change` §5.5 · `StarDist` §8.7 ·
`stereology` §10.1 · `Stereo Investigator` §10.1 · `stratum / striatum` §5.5, §6.2, §7.1 ·
`subiculum` §7.1, §7.2 · `SUB / subpopulation (Iba1 states)` §2.6, §8.5 ·
`substantia nigra` §5.2, §6.4, §7.4 · `Sudan Black B` §13.2 · `SURS` §10.1 ·
`SV2A` §2.7 · `svPPA` §5.3 · `synapses / synaptic density` §2.7, §8.6 ·
`Synaptophysin` §2.7, §8.6, §11

### T
`tangles (NFTs)` §2.2, §5.1, §6.3, §8.3 · `tau isoforms (3R / 4R)` §2.2, §3, §5.4 ·
`TDP-43` §2.4, §5.3, §6.5, §11 · `temporal cortex` §5.4, §7.1 ·
`TH (tyrosine hydroxylase)` §9.1, §11 · `Thal phases` §6.2 · `Thio S / ThioS` §2.1, §5.1, §12 ·
`3R tau` §2.2, §3, §5.4 · `3xTg-AD` §9.1 · `TMEM119` §2.6, §13.5 · `total TDP-43` §2.4 ·
`Triangle threshold` §8.1, §13.4, §12 · `TREM2` §2.6 · `trueBlack (Biotium)` §13.2 ·
`tufted astrocytes` §5.4 · `TSA / OPAL` §13.5 · `TUNEL` §4, §12

### U
`Ubiquitin` §2.9, §5.5 · `update sites / Fiji plugins` §13.7

### V
`VCP` §5.3 · `vCJD` §5.5 · `VGAT` §2.7 · `VGLUT1/2` §2.7 ·
`Vonsattel (HD)` §6.5 · `vulnerability (hippocampal subfields)` §7.2

### W
`Watershed` §8.1, §8.2 · `Weka (Trainable Segmentation)` §13.7 · `WSI` §13.7 ·
`white matter astrocytes` §2.6

### Y
`Yen threshold` §3, §13.4, §12

### Z
`ZT (zeitgeber time)` §13.8

---

## §2. Antibody & Marker Tables

### §2.1 Amyloid-Beta Detection

| Antibody/Stain | Target | Notes |
|----------------|--------|-------|
| **6E10** | Abeta 1-16 | Most sensitive; all plaque types + APP. Formic acid 88% 5 min retrieval. |
| **4G8** | Abeta 17-24 | All plaque types + APP. Formic acid retrieval. |
| **Anti-Abeta42** | C-terminus Abeta42 | Specific for aggregation-prone species. |
| **Anti-Abeta40** | C-terminus Abeta40 | Predominant in CAA vessels. |
| **ThioS** | Beta-sheet amyloid | Fluorescent (FITC filter). Neuritic plaques, NFTs, CAA. NOT diffuse plaques. High lipofuscin background in aged brain. |
| **Congo Red** | Amyloid | Apple-green birefringence under polarised light. Less background than ThioS but lower sensitivity. |

6E10 detects more plaque deposition than Gallyas or ThioS because it binds all Abeta conformations including diffuse (non-fibrillar).

### §2.2 Tau / Neurofibrillary Tangle Detection

| Antibody | Epitope | Best For |
|----------|---------|----------|
| **AT8** | pSer202/pThr205 | **Gold standard for Braak staging.** Pretangles through ghost tangles. HIER citrate pH 6.0. |
| **PHF-1** | pSer396/pSer404 | Mature + ghost tangles. Weak on pretangles. |
| **MC1** | Conformational (7-9 + 312-322) | Earliest misfolded tau. |
| **RD3** | 3-repeat tau | Pick bodies. |
| **RD4** | 4-repeat tau | CBD, PSP inclusions. |
| **CP13** | pSer202 | Earlier phosphorylation than AT8. |

**Tangle maturity (AT8 vs PHF-1):** Pretangles: AT8+++/PHF-1+. Mature: AT8+++/PHF-1+++. Ghost: AT8+/PHF-1++.

### §2.3 Alpha-Synuclein Detection

| Antibody | Target | Notes |
|----------|--------|-------|
| **pSer129-alphaSyn (EP1536Y)** | Phospho-Ser129 | **Gold standard.** ~90% pathological aSyn is pSer129. EP1536Y most specific (others cross-react with neurofilament). FA 80% 5 min + citrate. |
| **LB509** | aSyn 115-122 | Human-specific. Less sensitive than pSer129. |
| **5G4** | Aggregation-specific | Conformation-specific for aggregated aSyn. |

### §2.4 TDP-43 Detection

| Antibody | Target | Notes |
|----------|--------|-------|
| **pTDP-43 (pSer409/410)** | Phospho-TDP-43 | **Gold standard.** Pathological TDP-43 is cytoplasmic, hyperphosphorylated, ubiquitinated. HIER citrate pH 6.0. |
| **Anti-TDP-43 (total)** | All TDP-43 | Shows nuclear clearing + cytoplasmic inclusions. |

**Key diagnostic feature:** Loss of normal nuclear TDP-43 + cytoplasmic inclusions. Assess both.

### §2.5 Neuronal Markers

| Marker | Target | Notes |
|--------|--------|-------|
| **NeuN (RBFOX3)** | Mature neuron nuclei | Pan-neuronal. Does NOT label Purkinje or inferior olivary neurons. |
| **MAP2** | Dendrites + soma | NOT axons. Dendritic arborisation. |
| **SMI-32** | Non-phospho NF-H | Large pyramidal neurons (layers III, V). Loss = degeneration. |
| **Cresyl Violet (Nissl)** | Rough ER | Histochemical. Neuronal counts and cytoarchitecture. |
| **Fluoro-Jade B/C** | Degenerating neurons | Green fluorescence. More sensitive than H&E. |

### §2.6 Glial Markers

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

### §2.7 Synaptic Markers

| Marker | Compartment | Notes |
|--------|------------|-------|
| **Synaptophysin** | Presynaptic vesicles | Most widely used. Reduced in AD. |
| **PSD-95** | Postsynaptic density | Gold standard postsynaptic. Reduced in AD. |
| **SV2A** | Presynaptic vesicles | PET tracer target ([11C]UCB-J). |
| **VGLUT1/2** | Presynaptic (glutamatergic) | Excitatory synapse-specific. |
| **VGAT** | Presynaptic (GABAergic) | Inhibitory synapse-specific. |

Quantify by optical density/mean grey value in neuropil, NOT puncta counting (too small for light microscopy).

### §2.8 Vascular Markers

| Marker | Target | Notes |
|--------|--------|-------|
| **Collagen IV** | Basement membrane | All vessels. CAA assessment. |
| **CD31 (PECAM-1)** | Endothelial cells | Pan-endothelial. Vessel density. |
| **Claudin-5** | Tight junctions | BBB integrity. Lost in neurodegeneration. |
| **Fibrinogen** | Plasma protein | Extravascular = BBB leakage. |
| **PDGFR-beta** | Pericytes | Pericyte loss = BBB breakdown. |

### §2.9 Other Markers

| Marker | Target | Notes |
|--------|--------|-------|
| **Huntingtin (MW8, EM48)** | Mutant HTT | NIIs in neocortex layers V-VI and III. |
| **PrP (12F10, 3F4)** | Prion protein | Harsh retrieval: FA 98% + autoclave + PK. |
| **Ubiquitin** | Ubiquitinated inclusions | Non-specific. Labels NFTs, TDP-43, LBs. |
| **p62/SQSTM1** | Autophagy receptor | Labels most protein aggregates. |

---

## §3. Silver Stains

| Method | Targets | Key Properties |
|--------|---------|---------------|
| **Gallyas** | NFTs, neuropil threads | Most reproducible. Predilection for 4R tau. Does NOT stain diffuse plaques. Used for Braak staging. |
| **Bielschowsky** | NFTs, plaques (neuritic + some diffuse), axons | More sensitive for plaques. Less reproducible. Used for CERAD scoring. |
| **Campbell-Switzer** | Plaques + NFTs | Preferentially stains 3R tau. Complementary to Gallyas. |

**Differential staining:** AD NFTs (3R+4R): Gallyas+ AND CS+. Pick bodies (3R): Gallyas-, CS+. CBD/PSP (4R): Gallyas+, CS-.

ImageJ: Silver-stained structures are dark brown/black on light background. Use greyscale + dark threshold (Yen, Triangle).

---

## §4. Special Stains

| Stain | Target | Use |
|-------|--------|-----|
| **Luxol Fast Blue (LFB)** | Myelin | Demyelination. Loss of blue = myelin loss. Often + Cresyl Violet. |
| **TUNEL** | Fragmented DNA | Apoptotic cells. |
| **Fluoro-Jade B/C** | Degenerating neurons | Green fluorescence. Any death mechanism. |
| **Prussian Blue** | Hemosiderin (iron) | Old microhaemorrhages (CAA). |
| **PAS** | Glycogen, basement membrane | Corpora amylacea. |

---

## §5. Pathological Features by Disease (Summary)

### §5.1 Alzheimer's Disease

**Plaque types:**

| Type | ThioS/Congo Red | 6E10/4G8 | Significance |
|------|----------------|----------|-------------|
| Diffuse | Negative | Positive | Pre-amyloid, not counted for CERAD |
| Neuritic | Positive | Positive | **Diagnostic.** Counted for CERAD. |
| Cored | Positive | Positive | Subset of neuritic with dense core |

**Other AD pathology:** Neuropil threads (AT8+), CAA (Abeta in vessel walls — type 1: capillary + arterioles; type 2: arterioles only), granulovacuolar degeneration (CA1/CA2, 2-4 um vacuoles), Hirano bodies (eosinophilic rods in CA1).

### §5.2 PD / DLB

| Type | Location | Morphology |
|------|----------|-----------|
| Classical LB | SN, locus coeruleus | Dense eosinophilic core + pale halo. 8-30 um. pSer129+ |
| Cortical LB | Neocortex, limbic | Less defined, often requires IHC. pSer129+ |
| Lewy neurites | Neuropil | Thread-like pSer129+. Often more numerous than LBs. |

### §5.3 FTLD-TDP Subtypes

| Type | Morphology | Clinical | Genetics |
|------|-----------|---------|----------|
| A | Short dystrophic neurites + NCIs | bvFTD, nfvPPA | GRN |
| B | Moderate NCIs, few DNs | bvFTD + ALS | C9orf72 |
| C | Long thick DNs, sparse NCIs | svPPA | Sporadic |
| D | Neuronal intranuclear inclusions | IBMPFD | VCP |
| E | Granulofilamentous NCIs, grains | Rapid FTD | Rare |

### §5.4 FTLD-Tau

- **Pick bodies:** Round cytoplasmic inclusions. 3R tau (RD3+). Gallyas-. Dentate gyrus, frontal/temporal cortex.
- **CBD:** Astrocytic plaques, ballooned neurons. 4R tau (RD4+). Gallyas+.
- **PSP:** Tufted astrocytes, globose NFTs in brainstem/basal ganglia. 4R tau. Gallyas+.

### §5.5 Other Diseases

- **Huntington's:** Striatal neuronal loss (Vonsattel grade 0-4). NIIs (anti-huntingtin, ubiquitin, p62). Medium spiny neurons most vulnerable.
- **Prion:** Spongiform change + neuronal loss + gliosis. PrP deposition patterns vary by subtype (synaptic in sCJD, florid plaques in vCJD).
- **CTE:** Perivascular neuronal p-tau (AT8+) at sulcal depths. McKee stages I-IV.
- **LATE:** TDP-43 in amygdala (stage 1) -> + hippocampus (2) -> + frontal cortex (3). Common in >80 years. Mimics AD.

---

## §6. Staging Systems

### §6.1 NIA-AA "ABC" Score (Alzheimer's)

| Score | Assessment | Method |
|-------|-----------|--------|
| **A** (Amyloid) | Thal phase 0-5 -> A0-A3 | Abeta IHC (6E10/4G8) on standard regions |
| **B** (Braak) | Braak NFT stage I-VI -> B0-B3 | AT8 IHC on standard regions |
| **C** (CERAD) | Neuritic plaque density -> C0-C3 | Bielschowsky silver or ThioS. Sparse 1-5, Moderate 6-19, Frequent 20+ per 100x field. |

**Combined:** Not / Low / Intermediate / High AD neuropathological change. "Intermediate" or "High" sufficient explanation for dementia.

### §6.2 Thal Amyloid Phases

| Phase | Distribution |
|-------|-------------|
| 1 | Neocortex |
| 2 | + Allocortex (entorhinal, hippocampus) |
| 3 | + Striatum, diencephalon, basal forebrain |
| 4 | + Brainstem |
| 5 | + Cerebellum |

### §6.3 Braak NFT Stages (AT8)

| Stage | Distribution | Clinical |
|-------|-------------|---------|
| I-II | Transentorhinal / entorhinal cortex | Preclinical |
| III-IV | Hippocampus, amygdala, inferior temporal | MCI |
| V-VI | Association cortex -> primary sensory/motor | Dementia |

### §6.4 Braak PD Stages (Alpha-Synuclein)

| Stage | Distribution |
|-------|-------------|
| 1-2 | DMV, olfactory bulb, locus coeruleus (premotor) |
| 3-4 | SN, amygdala, temporal mesocortex (motor symptoms) |
| 5-6 | Neocortex (cognitive decline) |

### §6.5 Other Staging Systems

| System | Disease | Scale |
|--------|---------|-------|
| McKeith | DLB | Brainstem / amygdala / limbic / diffuse neocortical |
| Vonsattel | Huntington's | Grade 0-4 (striatal neuronal loss) |
| LATE-NC | LATE | Stage 0-3 (TDP-43: amygdala -> hippocampus -> frontal) |
| McKee | CTE | Stage I-IV (perivascular tau: focal -> widespread) |
| FTLD-TDP types | FTLD | Types A-E (inclusion morphology) |

---

## §7. Brain Anatomy for ROI Selection

### §7.1 Regions Affected by Disease

| Disease | Primary Regions | Secondary |
|---------|----------------|-----------|
| AD | Entorhinal cortex, hippocampus (CA1, subiculum), amygdala | Temporal, frontal, parietal association cortex |
| PD | SN pars compacta, locus coeruleus, DMV | Amygdala, hippocampus (CA2-3), olfactory bulb |
| DLB | Amygdala, cingulate, temporal cortex, SN | Widespread neocortex |
| bvFTD | Orbitofrontal, anterior cingulate, anterior temporal | Insula, striatum |
| ALS | Motor cortex (Betz cells), spinal anterior horn | Hippocampus (ALS-FTD) |
| HD | Caudate, putamen (medium spiny neurons) | Globus pallidus, cortex layers III/V-VI |
| Prion | Cortex, striatum, thalamus, cerebellum | Variable by subtype |

### §7.2 Hippocampal Subfield Vulnerability

| Subfield | Vulnerability | Key Pathology |
|----------|-------------|---------------|
| **CA1** | Very high (AD, LATE) | NFTs, neuronal loss, TDP-43 |
| **CA2** | Low (AD), high (DLB/PD) | Lewy neurites |
| **CA3** | Relatively resistant | Mossy fibre sprouting (epilepsy) |
| **Dentate Gyrus** | Variable | Pick bodies (FTD-tau) |
| **Subiculum** | High (AD) | NFTs, neuronal loss |
| **Entorhinal Cortex** | Very high (AD) | Layer II stellate neurons: earliest NFTs |

### §7.3 Cortical Layer Vulnerability

| Layer | Vulnerability |
|-------|---------------|
| II | Entorhinal: earliest AD NFTs |
| III | AD (association cortex), HD |
| V | ALS (Betz cells), HD, AD (late), CTE |
| V-VI | HD huntingtin inclusions |

### §7.4 Key Brainstem Structures

- **SN pars compacta:** Dopaminergic + neuromelanin. Primary PD target.
- **Locus coeruleus:** Noradrenergic. Early in AD and PD.
- **DMV:** Earliest PD alpha-synuclein (Braak stage 1).
- **Nucleus basalis of Meynert:** Cholinergic. Major loss in AD.

---

## §8. ImageJ Analysis Pipelines

### §8.1 DAB Quantification (Colour Deconvolution)

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

### §8.2 Plaque Counting and Sizing

```
// After colour deconvolution + threshold (see 7.1):
run("Convert to Mask");
run("Despeckle");
run("Watershed");
run("Set Measurements...", "area perimeter shape feret's display redirect=None decimal=2");
run("Analyze Particles...", "size=100-100000 circularity=0.3-1.0 show=[Overlay Outlines] display summarize");
```

Typical sizes: Diffuse 20-100 um, Neuritic 30-80 um, Core 10-30 um.

### §8.3 Tangle Counting (AT8)

```
// Semi-automated (manual review recommended):
selectWindow("*-(Colour_2)");
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Analyze Particles...", "size=50-5000 circularity=0.1-0.8 show=Outlines display summarize");
```

For precision: use Cell Counter plugin. Separate counter types for pretangles, mature tangles, ghost tangles.

### §8.4 Area Fraction (% Immunoreactive Area)

```
run("Colour Deconvolution", "vectors=[H DAB]");
selectWindow("*-(Colour_2)");
setAutoThreshold("Triangle dark");
run("Set Measurements...", "area area_fraction limit display");
run("Measure");
// "%Area" = percentage DAB-positive
```

Typical values (very approximate): Abeta in severe AD cortex 5-20%. pTau (AT8) in AD cortex 1-10%. GFAP in reactive astrogliosis 10-40%. Iba1 in normal cortex 2-5%, neuroinflammation 5-20%.

### §8.5 Microglial Morphology Analysis

```
// 1. Isolate individual Iba1+ microglia (threshold + particle analysis)
// 2. Per cell:
//    a. Soma area + circularity: Analyze Particles
//    b. Skeleton analysis: run("Skeletonize"); run("Analyze Skeleton (2D/3D)");
//    c. Sholl analysis: line from soma -> run("Sholl Analysis...");
//    d. Fractal dimension: FracLac plugin
```

Reference values: Ramified soma ~50-100 um2 (low circularity), Activated ~150-400 um2 (higher circularity), Amoeboid ~200-400+ um2 (circularity ~1.0).

### §8.6 Synapse Density (Puncta Colocalization)

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

### §8.7 Cell Counting

```
// StarDist (best for round nuclei like NeuN, DAPI):
run("StarDist 2D", "model=[Versatile (fluorescent nuclei)] probThresh=0.5 nmsThresh=0.4 outputType=Label Image");

// For DAB: invert DAB channel to pseudo-fluorescent before StarDist.
// Validate against manual counts (F1 > 0.85).
```

### §8.8 ROI Analysis by Brain Region

```
roiManager("Open", "/path/to/ROI_set.zip");
for (i = 0; i < roiManager("count"); i++) {
    roiManager("Select", i);
    run("Measure");
}
```

Naming convention: `CA1_left`, `DG_granular`, `EC_layer_II`, `SN_pars_compacta`.

### §8.9 Batch Processing Template

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

## §9. Mouse Models

### §9.1 Key Models Summary

| Model | Type | Onset | Plaques | Tangles | Neuronal Loss | Key Antibodies |
|-------|------|-------|---------|---------|--------------|----------------|
| APP/PS1 | Amyloid | 3-4 mo | Yes | No | Minimal | 6E10, 4G8 |
| 5xFAD | Amyloid | 2 mo | Yes (aggressive) | No | Some (layer V) | 82E1, MOAB-2, ThioS |
| 3xTg-AD | Amyloid+Tau | 3 mo (Ab), 12 mo (tau) | Yes | Yes | Moderate | AT8, HT7, 6E10 |
| rTg4510 | Tau | 2-3 mo | No | Yes (robust) | Severe | AT8, PHF-1, MC1 |
| PS19 | Tau | 6 mo | No | Yes | Moderate | AT8, AT180, AT100 |
| aSyn PFF (WT) | Synuclein | 1-2 mo | No | No | ~30% DA (6 mo) | pSer129, TH, DAT |
| SOD1-G93A | ALS | 8 wk | No | No | ~50% MN (end) | ChAT, GFAP, Iba1 |

### §9.2 Mouse vs Human Differences

| Feature | Mouse Models | Human Disease |
|---------|-------------|---------------|
| Neuronal loss | Minimal (most amyloid models) | Severe, progressive |
| Tangles | Absent in amyloid-only models | Core pathology |
| Co-pathology | Usually single transgene product | Multiple proteinopathies |
| Time course | Weeks to months | Decades |
| Genetic basis | Familial mutations overexpressed | >95% sporadic |

### §9.3 Species-Specific Antibody Notes

- **6E10, 4G8, HT7:** Human-specific. Only work in mice with human transgene.
- **Iba1, GFAP, AT8, NeuN, Synaptophysin, PSD-95:** Work in both mouse and human.
- Mouse Abeta does not form plaques (sequence differs at 3 residues).
- Mouse tau is 4R only (humans have 3R + 4R).

---

## §10. Quantitative Standards

### §10.1 Stereology (Unbiased Cell Counting)

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

### §10.2 The Reference Trap

If a brain region atrophies, cell density (cells/mm2) can INCREASE even when total cells DECREASE. Always report total counts (fractionator) or measure and report reference volume alongside density.

### §10.3 Statistical Gotchas

- **Biological vs technical replicates:** Multiple sections from same animal are technical replicates. Average per animal before group comparison. Treating sections as independent n inflates significance.
- **Typical n:** 5-12 animals per group.
- **Tests:** 2 groups: t-test or Mann-Whitney. Multiple groups: ANOVA + Tukey. Braak stages: non-parametric (ordinal).

### §10.4 Reporting Standards

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

## §11. Common Antibody Quick Reference

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

## §12. Decision Trees

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

---

## §13. Best Practices and Pitfalls

### §13.1 Antigen Retrieval

| Method | Agent | Best For |
|--------|-------|----------|
| HIER - Citrate pH 6.0 | 10 mM citrate, 95-100C, 10-30 min | **Default.** Most antigens (tau, GFAP, Iba1, NeuN). |
| HIER - Tris-EDTA pH 9.0 | 10 mM Tris + 1 mM EDTA, 95-100C | More aggressive. Some antigens respond better. |
| Formic acid | 70-98%, RT, 5-20 min | **Essential for Abeta.** Also aSyn. Disrupts beta-sheets. |
| Proteinase K | 10-20 ug/mL, 37C, 5-15 min | Aggregated aSyn, PrP. Reduces normal protein background. |

**Combinations:** Abeta: FA 88% 5 min -> HIER citrate. aSyn: FA 80% 5 min -> citrate (or PK 20 ug/mL 10 min). PrP: FA 98% -> autoclave -> PK. Old formalin tissue: PK -> autoclave EDTA -> FA.

### §13.2 Lipofuscin Autofluorescence (Aged Brain)

Lipofuscin autofluoresces across broad spectrum (green > red > far-red). Major problem for IF in aged tissue.

| Solution | Effectiveness |
|----------|-------------|
| **TrueBlack (Biotium)** | Excellent. Apply after IF, 30 sec-5 min in 70% ethanol. |
| **Sudan Black B** | Good for green/red. Adds far-red background. |
| **Spectral unmixing** | Good. Requires spectral/lambda-scan confocal. |
| **DAB instead of IF** | Eliminates problem. Loses multiplexing. |

ImageJ workaround: Image unstained section, use Image Calculator > Subtract.

### §13.3 Controls

| Control | Purpose |
|---------|---------|
| Positive tissue | Confirms antibody works (e.g., AD brain for Abeta/tau) |
| No primary antibody | Non-specific secondary binding |
| Isotype control | Non-specific primary binding |
| Known negative tissue | Confirms specificity (e.g., young normal brain) |

### §13.4 DAB Threshold Method Selection

| Staining Quality | Recommended Threshold |
|-----------------|----------------------|
| Strong, clear, low background | Otsu |
| Variable intensity, gradual transition | **Triangle (default)** |
| Weak signal, need sensitivity | Moments or Yen |
| Very clean, bimodal | Li or IsoData |
| Batch (consistency critical) | Fixed manual threshold |

### §13.5 Multiplex IF Panels

**TSA/OPAL system:** Up to 8 markers per section. Primary -> HRP-secondary -> TSA fluorophore (covalent) -> strip -> repeat. Same-species primaries OK.

Common panels: Abeta + pTau + NeuN + GFAP + Iba1 + Olig2. pTau + pSer129 + pTDP-43 (co-pathology). GFAP + Iba1 + CD68 + TMEM119 + P2RY12 (glial states).

### §13.6 Co-Pathology

"Pure" single proteinopathy is the exception. AD + Lewy bodies: 30-50%. AD + TDP-43 (LATE): 30-57%. Must characterize ALL proteinopathies, not just the "primary" one.

### §13.7 WSI / Digital Pathology

- **QuPath:** Leading open-source for WSI. DAB quantification, cell detection, pixel classification, batch processing.
- **Fiji:** Bio-Formats imports .svs/.ndpi/.scn. Use virtual stacks for large images.
- **Trainable Weka Segmentation:** Pixel classification when colour deconvolution alone insufficient.

### §13.8 Circadian Considerations

- Clock protein expression varies with time of day — record time of death for post-mortem tissue.
- Microglial morphology varies with circadian phase — sacrifice at consistent ZT.
- When comparing groups, ensure tissue collected at same circadian time or account statistically.
