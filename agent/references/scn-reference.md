# Suprachiasmatic Nucleus (SCN) — Reference

Concise reference for SCN biology, markers, quantification methods, and experimental
approaches. For image analysis workflows, see `scn-analysis-reference.md`.

Sources: Paxinos & Franklin mouse atlas; Allen CCFv3 (SCH = ID 286); primary
literature on SCN anatomy, neuropeptides, coupling, and circadian quantification
(Welsh 1995; Aton 2005; Brancaccio 2013, 2017, 2019, 2024; Hastings/Maywood/Brancaccio
2018; Hughes 2010; Parsons 2020; Patton & Hastings 2023). Markers and antibody
catalogue numbers as listed per-row.

Invoke-adjacent commands the agent uses when applying this reference:
`python ij.py macro '<code>'` for macro operations (thresholding, ROI work,
measurement), `python ij.py script '<code>'` for Groovy/Jython when macros
cannot reach a plugin, and `python ij.py capture` to inspect mask / label
results visually. Analysis-specific recipes live in `scn-analysis-reference.md`
and `circadian-analysis.md`.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Where is the SCN in the mouse brain — stereotaxic coordinates?" | §2.1 |
| "What's the difference between SCN core and shell?" | §2.3 |
| "Which neuropeptide marks the core vs the shell?" | §3.1 |
| "What antibody should I use for VIP / AVP / PER2?" | §3.3 |
| "Which reporter lines exist for live imaging?" | §3.4 |
| "How do I estimate circadian period from a time series?" | §5.1 |
| "How do I measure synchrony between oscillators?" | §5.3 |
| "What's the Kuramoto R / Rayleigh test formula?" | §5.3 |
| "What period should a mouse SCN slice have?" | §8 |
| "What's the SCN doing in Alzheimer's / Parkinson's?" | §7 |
| "What does abbreviation X stand for?" | §10 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each marker, antibody,
anatomical structure, or concept. Use `grep -n '<term>' scn-reference.md`
to jump.

### A
`Abeta` §7 · `ALDH1L1` §3.2 · `Allen CCFv3 (SCH=286)` §2.1 · `Alpha-synuclein` §7 ·
`Alzheimer's` §7 · `anterior hypothalamic area` §2.1 · `Aton 2005` §9 ·
`Autocorrelation` §5.1 · `AVP` §3.1, §3.3, §4.1, §8 · `astrocytes` §3.2, §4.1

### B
`BB2 (receptor)` §4.1, §8 · `Bio-luminescence / luciferin` §6.1 ·
`BMAL1` §3.3, §7 · `Brancaccio 2013` §9 · `Brancaccio 2017` §3.2, §9 ·
`Brancaccio 2019` §3.2, §9 · `Brancaccio 2024` §3.2, §9

### C
`C1q / C3` §3.2, §7 · `c-Fos` §3.3 · `Calbindin (Calb1)` §3.1, §3.3 ·
`Calretinin (Calb2)` §3.1 · `CCDA/EMCCD` §6.2 · `CircaCompare` §5.5, §9 ·
`Circular variance` §5.3 · `clock protein` §3.3 · `commissural tract` §2.4 ·
`core (ventrolateral)` §2.3 · `Cosinor` §5.4 · `CREB` §4.1, §8 ·
`Cry1-luc` §3.4 · `CT / ZT` §10 · `CX3CR1` §3.2 · `CX3CR1-GFP` §3.4 ·
`Cx36 (gap junctions)` §4.1

### D
`DD (constant darkness)` §8 · `DMH` §2.5, §10 · `DREADDs` §6.3

### E
`efferent projections` §2.5 · `EMBO J` §9

### F
`Fiber photometry` §6.3 · `FFT` §5.1

### G
`GABA` §3.1, §3.2, §4.1 · `GABA-A` §4.1 · `GABAergic` §3.1 ·
`gap junctions` §4.1 · `GCaMP6f/s` §3.4, §6.3 · `GFAP` §3.2, §3.3 ·
`GHT` §2.4 · `glia` §3.2 · `Gq` §4.1, §8 · `Gs` §4.1, §8 ·
`GRP (Grp)` §3.1, §3.3, §4.1, §8 · `glymphatic` §7

### H
`Hastings/Maywood/Brancaccio 2018` §9 · `Hilbert transform` §5.2 ·
`Hughes 2010` §9 · `human` §2.2, §8 · `Huntington's` §7

### I
`Iba1` §3.2, §3.3 · `IGL` §2.4 · `IML` §2.5 · `Incucyte` §6.2 ·
`ipRGCs (melanopsin, ~480 nm)` §2.4, §10 · `IP3 → Ca2+` §8

### J
`JTK_CYCLE` §5.5, §9

### K
`KCC2` §3.1 · `Kuramoto R` §5.3

### L
`LumiCycle` §6.2 · `Lomb-Scargle` §5.1

### M
`median raphe` §2.4 · `melanopsin` §2.4, §7 · `melatonin` §2.5 ·
`Miniscope` §6.3 · `microglia` §3.2, §7 · `Morlet wavelet` §5.1 ·
`mouse stereotaxic (Paxinos & Franklin)` §2.1

### N
`NeuN` §3.3 · `NFTs` §7 · `NKCC1` §3.1 · `NMDA (NR2C)` §4.1 ·
`NMS (Nms)` §3.1, §10 · `NPY` §2.4

### O
`optic chiasm` §2.1 · `Optogenetics` §6.3 · `organotypic slice` §6.1

### P
`P2RY12` §3.2 · `PACAP` §2.4 · `Parkinson's` §7 · `Parsons 2020` §5.5, §9 ·
`Patton & Hastings 2023` §9 · `Peak detection (phase)` §5.2 · `Peninsula T-4563` §3.3 ·
`PER2` §3.3 · `PER2::LUC` §3.4, §4.2, §8 · `PER2::VENUS` §3.4 ·
`Per1-luc` §3.4 · `Per1::GFP` §3.4 · `PK2 (Prok2)` §3.1, §2.5, §10 ·
`PKA` §8 · `photoperiod encoding` §4.3 · `photoentrainment` §2.3, §2.4 ·
`PMT` §6.2 · `POA` §2.5 · `PRC` §10 · `PVN` §2.5, §10

### R
`RAIN` §5.5 · `rat` §2.2 · `Rayleigh test` §5.3 · `RBD` §7 ·
`REV-ERBa` §3.2, §7 · `retinorecipient zone` §3.1 · `RHT` §2.4, §10

### S
`S100beta` §3.2 · `SCG` §2.5 · `SCH (Allen CCFv3 ID 286)` §2.1 ·
`SCN` §10 · `serotonergic / 5-HT` §2.4 · `shell (dorsomedial)` §2.3 ·
`SPZ` §2.1, §2.5, §10 · `SON` §2.1 · `sundowning` §7 ·
`synchrony` §5.3 · `synaptic phagocytosis` §3.2

### T
`third ventricle (3V)` §2.1, §10 · `TMEM119` §3.2 · `TTFL` §3.2, §10

### V
`V1a/V1b (receptor)` §4.1, §8 · `VENUS` §3.4 · `VIP (Vip)` §3.1, §3.3, §4.1, §8, §10 ·
`VPAC2` §4.1, §8 · `vibratome` §6.1

### W
`Wavelet (Morlet)` §5.1 · `Welsh 1995` §9

### Z
`ZT` §10

---

## §2. Anatomy & Structure

### §2.1 Location & Coordinates

Paired bilateral structure in anterior hypothalamus, above optic chiasm, flanking ventral 3V.

| Boundary | Landmark |
|----------|----------|
| Ventral | Optic chiasm |
| Medial | Third ventricle (3V) |
| Dorsal | SPZ / PVN |
| Lateral | SON / anterior hypothalamic area |

**Mouse stereotaxic (Paxinos & Franklin):** AP bregma -0.34 to -0.70 mm (mid ~-0.46 to -0.58), ML +/- 0.0–0.3 mm, DV ~-5.3 to -5.7 mm. Allen CCFv3 ID: 286 (SCH).

### §2.2 Size

| Species | Neurons/side | Dimensions |
|---------|-------------|------------|
| Mouse | ~10,000 | ~300 x 300 x 600 um |
| Rat | ~10,000 | ~400 x 400 x 800 um |
| Human | ~10,000 | 1.4–1.8 mm A-P |

### §2.3 Core vs Shell Subdivisions

| Property | Core (Ventrolateral) | Shell (Dorsomedial) |
|----------|---------------------|---------------------|
| Key neuropeptides | VIP, GRP, calretinin | AVP, met-enkephalin |
| Light-responsive | Yes (direct RHT input) | No |
| Intrinsic rhythm | Weak ("gate" cells) | Strong (autonomous oscillators) |
| Phase | Lags shell by 1–4 h | Leads |
| Function | Photoentrainment, phase resetting | Endogenous pacemaker, output |

Core → shell connectivity is dense (~90% AVP neurons receive >=3 VIP contacts); shell → core is sparse (<10%).

### §2.4 Afferent Pathways

| Source | Tract | Transmitters | Function |
|--------|-------|-------------|----------|
| ipRGCs (melanopsin, ~480 nm) | RHT | Glutamate, PACAP | Photic entrainment |
| IGL | GHT | NPY, GABA | Non-photic entrainment |
| Median raphe | Serotonergic | 5-HT | Non-photic phase shifting |
| Contralateral SCN | Commissural | GABA, VIP | Bilateral coordination |

### §2.5 Efferent Projections

| Target | Function | Key mediators |
|--------|----------|---------------|
| SPZ | Primary behavioural output relay | PK2, AVP |
| PVN | Autonomic/melatonin pathway | AVP |
| DMH | Sleep-wake, feeding, corticosteroids | — |
| POA | Thermoregulation | — |

Melatonin: SCN → PVN → IML → SCG → pineal (duration encodes photoperiod).

---

## §3. Cell Types & Markers

### §3.1 Neurons

All SCN neurons are GABAergic (~95%), subdivided by co-expressed neuropeptides.

| Marker | Gene | Region | Proportion | Key role |
|--------|------|--------|-----------|----------|
| VIP | *Vip* | Core | ~10% | Essential synchronisation via VPAC2 |
| GRP | *Grp* | Core | ~5–10% | Light-responsive, phase resetting |
| Calretinin | *Calb2* | Core | Variable | Core marker |
| Calbindin | *Calb1* | Caudal core | Variable | Retinorecipient zone |
| AVP | *Avp* | Shell | ~20% | Rhythmic output, shell oscillator |
| NMS | *Nms* | Cross-regional | ~40% | Essential pacemaker population |
| PK2 | *Prok2* | Primarily shell | — | Major circadian output molecule |

GABA dual polarity: excitatory (day, NKCC1-dominant) / inhibitory (night, KCC2-dominant).

### §3.2 Glia

| Cell type | Markers | Circadian role |
|-----------|---------|---------------|
| Astrocytes | GFAP, ALDH1L1, S100beta | Anti-phasic Ca2+ vs neurons; glutamate/GABA oscillations drive neuronal synchrony (Brancaccio 2017, 2019, 2024) |
| Microglia | Iba1, CX3CR1, CD68, P2RY12, TMEM119 | Self-sustained clocks; REV-ERBa-regulated activation; diurnal synaptic phagocytosis via C1q/C3 |

### §3.3 Common IHC Antibodies

| Target | Host | Catalogue | Application |
|--------|------|-----------|-------------|
| VIP | Rabbit | ImmunoStar 20077; Abcam ab272920 | Core identification |
| AVP | Rabbit/GP | Peninsula T-4563; Millipore AB1565 | Shell identification |
| GRP | Rabbit | ImmunoStar 20073 | Core neurons |
| Calbindin | Mouse/Rabbit | Swant 300; Sigma C9848 | Caudal core |
| NeuN | Mouse | Millipore MAB377 | Pan-neuronal |
| GFAP | Rabbit/Chicken | Dako Z0334; Abcam ab4674 | Astrocytes |
| Iba1 | Rabbit/Goat | Wako 019-19741; Abcam ab5076 | Microglia |
| PER2 | Rabbit | Alpha Diagnostic PER21-A | Clock protein, phase |
| BMAL1 | Rabbit | Novus NB100-2288 | Clock protein |
| c-Fos | Rabbit | Cell Signaling 2250; Santa Cruz sc-52 | IEG, light induction |

### §3.4 Fluorescent Reporters

| Reporter | Type | Reports |
|----------|------|---------|
| PER2::LUC | Knock-in | PER2 protein (bioluminescence) |
| PER2::VENUS | Knock-in | PER2 protein (YFP) |
| Cry1-luc / Per1-luc | Transgene | Promoter activity (biolum) |
| Per1::GFP | Transgene | Per1 promoter (GFP) |
| CX3CR1-GFP | Knock-in | Microglia |
| GCaMP6f/s | Viral/Tg | Calcium |

---

## §4. Network Properties & Coupling

### §4.1 Coupling Mechanisms

| Mechanism | Receptor | Importance |
|-----------|----------|-----------|
| VIP → VPAC2 (Gs → cAMP → CREB → Per1/2) | VPAC2 | **Essential** — KO = arrhythmic |
| AVP → V1a/V1b (Gq → Ca2+) | V1a/V1b | Shell-to-shell |
| GRP → BB2 (Gq → Ca2+) | BB2 | Phase resetting in core |
| Astrocytic glutamate → NR2C-NMDA | NMDA | Nocturnal neuron silencing |
| Astrocytic GABA → GABA-A | GABA-A | Rhythmic synchronisation |
| Gap junctions (Cx36) | — | ~25% pairs; refines, not essential |

Hierarchy: VIP/VPAC2 >> AVP/V1a > GRP/BB2.

### §4.2 Phase Relationships

- Dorsal leads ventral by 1–4 hours
- Long photoperiod → caudal leads by 4–12 h (phase dispersal encodes day length)
- PER2::LUC reveals daily spatiotemporal expression wave

### §4.3 Photoperiod Encoding

Day length encoded via network-level phase distribution (compressed = short day, dispersed = long day). Individual neurons maintain identical patterns regardless. ~50 neurons needed for detectable photoperiodic differences.

---

## §5. Quantification Methods

### §5.1 Period Estimation

| Method | Best for | Notes |
|--------|----------|-------|
| Lomb-Scargle | Irregular sampling | False alarm: p = 1 - (1 - exp(-P_N))^M |
| FFT | Regular sampling | Resolution limited by record length |
| Wavelet (Morlet) | Time-resolved period/amplitude | Cone of influence at edges |
| Autocorrelation | Model-free | Period = lag of first positive peak |

### §5.2 Phase Estimation

| Method | Approach |
|--------|----------|
| Hilbert transform | phi(t) = arctan(H[x(t)] / x(t)). **Must bandpass filter first** (~0.8–1.2 cycles/day). |
| Peak detection | Phase 0 at maxima; interpolate. Smooth first (3–5 point MA). |

### §5.3 Synchrony Metrics

| Metric | Formula | SCN values |
|--------|---------|-----------|
| Kuramoto R | \|mean(exp(i*phi_j))\| | WT: 0.6–0.9; VIP-/-: 0.1–0.3 |
| Rayleigh test | z = N*R^2; p ~ exp(-z) | Reject uniform if p < 0.05 |
| Circular variance | V = 1 - R | — |

### §5.4 Cosinor Analysis

y(t) = M + A*cos(2*pi*t/tau + phi) + epsilon. Linearised: fit beta*cos + gamma*sin. A = sqrt(beta^2 + gamma^2), phi = atan2(-gamma, beta). Rhythm test: F(2, N-3).

### §5.5 Rhythm Detection Tools

| Method | Type | Notes |
|--------|------|-------|
| JTK_CYCLE | Nonparametric | Kendall's tau-b; fast, robust; assumes symmetric waveform |
| RAIN | Nonparametric | Extends JTK for asymmetric waveforms |
| CircaCompare (R) | Regression | Tests differences in MESOR, amplitude, phase between groups |

---

## §6. Experimental Preparations

### §6.1 Organotypic Slice Cultures

Typically P2–P7, 250–350 um coronal sections, vibratome → Millicell inserts, DMEM + B27 + luciferin (0.1–1 mM). 35–37C, CO2-free sealed (biolum) or 5% CO2 (fluorescence). Stable oscillations for weeks–months.

### §6.2 Recording Methods

| Method | Resolution | Throughput | Use |
|--------|-----------|------------|-----|
| PMT | Whole-tissue | High (LumiCycle: 32) | Period/amplitude screening |
| CCD/EMCCD | Cellular–10 um | Low (1–4) | Spatial phase mapping |
| Incucyte | ~1–5 um/pixel | Medium (multi-well) | Long-term + morphology |

### §6.3 In Vivo Methods

| Method | Resolution | Duration |
|--------|-----------|----------|
| Fiber photometry (GCaMP6) | Population | Weeks |
| Miniscope (GRIN lens) | Cellular | Days–weeks |
| Optogenetics / DREADDs | Population | Acute / hours |

---

## §7. SCN in Disease

| Disease | SCN pathology | Key mechanism |
|---------|--------------|---------------|
| Alzheimer's | AVP/VIP neuron loss (up to 80%), NFTs, Abeta deposition, sundowning | BMAL1 KO accelerates neurodegeneration; glymphatic Abeta clearance is sleep-dependent |
| Parkinson's | Alpha-synuclein in SCN, reduced firing, RBD | Dopamine loss → weakened retinal entrainment |
| Huntington's | Disrupted electrical rhythms despite intact molecular clock | Output disruption, not cell-autonomous clock failure |
| Aging | Reduced amplitude, VIP/AVP loss, fragmented activity, phase advance | Weakened melanopsin, peripheral desynchrony |
| Neuroinflammation | REV-ERBa controls microglial activation rhythms (C1q, C3) | KO = perpetual "nighttime" activated microglia |

---

## §8. Quick Reference Values

### Period

| Preparation | Period | Variability |
|-------------|--------|-------------|
| Mouse behaviour (C57BL/6, DD) | 23.5–23.8 h | +/- 0.1–0.3 |
| SCN slice (PER2::LUC) | 23.5–25.0 h | +/- 0.5–1.0 |
| Single dissociated neuron | 20–28 h | SD ~1–2 h |
| Human free-running | 24.18 h | +/- 0.04 |

### Neuropeptide Kinetics

| Peptide | Peak | Receptor | Cascade |
|---------|------|----------|---------|
| VIP | CT6–12 | VPAC2 (Gs) | cAMP → PKA → CREB |
| AVP | CT4–8 | V1a/V1b (Gq) | IP3 → Ca2+ |
| GRP | CT4–8 | BB2 (Gq) | IP3 → Ca2+ |

---

## §9. Key Publications

| Authors | Year | Journal | Finding |
|---------|------|---------|---------|
| Welsh et al. | 1995 | Neuron | Individual SCN neurons are oscillators |
| Aton et al. | 2005 | Nat Neurosci | VIP/VPAC2 required for synchrony |
| **Brancaccio et al.** | 2013 | Neuron | Gq-Ca2+ axis; VIP neuron reprogramming |
| **Brancaccio et al.** | 2017 | Neuron | Astrocytes control timekeeping via glutamate |
| **Hastings, Maywood, Brancaccio** | 2018 | Nat Rev Neurosci | Genes → circuits review |
| **Brancaccio et al.** | 2019 | Science | Astrocytic TTFL alone drives behaviour |
| Brancaccio lab | 2024 | EMBO J | Rhythmic astrocytic GABA synchronises neurons |
| Hughes et al. | 2010 | J Biol Rhythms | JTK_CYCLE algorithm |
| Parsons et al. | 2020 | Bioinformatics | CircaCompare |
| Patton & Hastings | 2023 | Nat Rev Neurosci | SCN at 50 |

---

## §10. Glossary

| Abbrev | Full Name |
|--------|-----------|
| SCN | Suprachiasmatic nucleus |
| VIP | Vasoactive intestinal peptide |
| AVP | Arginine vasopressin |
| GRP | Gastrin-releasing peptide |
| NMS | Neuromedin S |
| PK2 | Prokineticin 2 |
| RHT | Retinohypothalamic tract |
| ipRGC | Intrinsically photosensitive retinal ganglion cell |
| TTFL | Transcription-translation feedback loop |
| CT / ZT | Circadian time / Zeitgeber time |
| PRC | Phase response curve |
| SPZ | Subparaventricular zone |
| PVN | Paraventricular nucleus |
| DMH | Dorsomedial hypothalamus |
| 3V | Third ventricle |
