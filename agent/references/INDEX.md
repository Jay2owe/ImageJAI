# Reference Documents Index

49 reference documents, ~31,700 lines total.
Read the relevant reference before starting any analysis in that domain.

---

## Core ImageJ

| Document | Lines | Topics |
|----------|------:|--------|
| [macro-reference.md](macro-reference.md) | 251 | ImageJ macro command syntax, functions, examples |
| [imagej-gui-reference.md](imagej-gui-reference.md) | 372 | GUI structure, dialog field types, keyboard shortcuts, ROI/Overlay/Table API |
| [gui-interaction-reference.md](gui-interaction-reference.md) | 491 | Plot/Fit/Color API, pixel access, math functions, drawing, setOption |
| [fiji-scripting-reference.md](fiji-scripting-reference.md) | 1078 | Groovy/Jython, IJ1/IJ2 API, SciJava params, plugin scripting, GUI manipulation |
| [batch-processing-reference.md](batch-processing-reference.md) | 1029 | setBatchMode, Bio-Formats API, batch templates, memory management |
| [file-formats-saving-reference.md](file-formats-saving-reference.md) | 521 | All formats, saveAs(), File.* functions, Bio-Formats Exporter, TIFF deep dive |
| [bioformats-multiseries-reference.md](bioformats-multiseries-reference.md) | 220 | .lif/.czi/.nd2: list series without opening, open by index, filter by name, batch |
| [pipeline-construction-reference.md](pipeline-construction-reference.md) | 862 | Pipeline patterns, TCP protocol, parameter sweeps, recipe YAML |

## Image Analysis Methods

| Document | Lines | Topics |
|----------|------:|--------|
| [fluorescence-microscopy-reference.md](fluorescence-microscopy-reference.md) | 510 | Modalities, CTCF, FRAP, FRET, acquisition checklist, artifacts |
| [fluorescence-theory-reference.md](fluorescence-theory-reference.md) | 352 | Fluorophore tables, resolution formulas, SNR, spectral overlap, panel selection |
| [if-postprocessing-reference.md](if-postprocessing-reference.md) | 521 | Filter comparison, thresholding decision tree, deconvolution, pipeline patterns |
| [deconvolution-reference.md](deconvolution-reference.md) | 521 | DL2 algorithms, PSF generation, CLIJ2 GPU, Iterative Deconvolve 3D |
| [colocalization-reference.md](colocalization-reference.md) | 383 | PCC, Manders, Costes, Coloc 2 commands, method comparison, reporting |
| [ai-image-analysis-reference.md](ai-image-analysis-reference.md) | 266 | StarDist, Cellpose, WEKA, Labkit, CSBDeep, DeepImageJ, decision tree |
| [weka-segmentation-reference.md](weka-segmentation-reference.md) | 723 | TWS call() API, Groovy scripting, features, batch, memory management |
| [python-image-analysis-reference.md](python-image-analysis-reference.md) | 747 | scikit-image, scipy.ndimage, tifffile, regionprops, colocalization |
| [fiber-orientation-reference.md](fiber-orientation-reference.md) | 891 | OrientationJ, Directionality, AnalyzeSkeleton, circular statistics |
| [registration-stitching-reference.md](registration-stitching-reference.md) | 894 | Grid/Pairwise stitching, StackReg, bUnwarpJ, BigWarp, SimpleITK, OpenCV |
| [super-resolution-reference.md](super-resolution-reference.md) | 791 | ThunderSTORM, NanoJ-SRRF, FRC, cluster analysis, nanoscale colocalization |
| [colour-deconvolution-histology-reference.md](colour-deconvolution-histology-reference.md) | 719 | CD1/CD2, stain vectors, DAB, H-score, Allred, Ki67, scoring systems |
| [color-science-reference.md](color-science-reference.md) | 611 | LUTs, colorblind-safe palettes, composites, journal color requirements |

## Validation & Statistics

| Document | Lines | Topics |
|----------|------:|--------|
| [method-validation-reference.md](method-validation-reference.md) | 1001 | Bland-Altman, ICC, CCC, Dice/IoU, Hausdorff, PQ, kappa, power analysis |
| [statistics-reference.md](statistics-reference.md) | 577 | Test selection, R/Python/ImageJ commands, decision trees, gotchas |
| [hypothesis-testing-microscopy-reference.md](hypothesis-testing-microscopy-reference.md) | 381 | 36 research questions → tests, pseudoreplication, blinding, effect sizes |
| [statistical-analysis-workflow-reference.md](statistical-analysis-workflow-reference.md) | 993 | ImageJ → Python → figures: 8 copy-paste scripts, integration workflow |

## Quality Assessment

| Document | Lines | Topics |
|----------|------:|--------|
| [image-quality-reference.md](image-quality-reference.md) | 1232 | QC protocol, focus/SNR/saturation metrics, artifact detection, batch QC |
| [image-integrity-reference.md](image-integrity-reference.md) | 328 | Journal policies, allowed/forbidden manipulations, integrity checks |

## 3D Analysis & Visualization

| Document | Lines | Topics |
|----------|------:|--------|
| [3d-spatial-reference.md](3d-spatial-reference.md) | 885 | 3D ImageJ Suite, MorphoLibJ, CLIJ2, AnalyzeSkeleton, DiAna |
| [spatial-statistics-reference.md](spatial-statistics-reference.md) | 1150 | NND, Ripley's K, DBSCAN, Voronoi, Moran's I, 3D spatial, Monte Carlo |
| [3d-visualisation-reference.md](3d-visualisation-reference.md) | 243 | 3Dscript, 3D Viewer, 3D Project, rendering method comparison |
| [3dscript-reference.md](3dscript-reference.md) | 472 | 3Dscript animation keywords, rendering properties, easing, camera |

## Tracking, Dynamics & Time-Lapse

| Document | Lines | Topics |
|----------|------:|--------|
| [trackmate-reference.md](trackmate-reference.md) | 479 | Detectors/trackers, features, Jython/Groovy scripting API, batch |
| [live-cell-timelapse-reference.md](live-cell-timelapse-reference.md) | 921 | Drift/bleach correction, FRAP, kymographs, cell division, FRET |
| [calcium-imaging-reference.md](calcium-imaging-reference.md) | 1049 | GCaMP/Fura-2, dF/F, ratiometric, events, synchrony, Python pipeline |
| [circadian-analysis-reference.md](circadian-analysis-reference.md) | 185 | FFT/cosinor/JTK, detrending, phase maps, Kuramoto |
| [circadian-imaging-reference.md](circadian-imaging-reference.md) | 520 | Circadian imaging, bioluminescence, Incucyte, rhythm analysis |

## Biological Assays

| Document | Lines | Topics |
|----------|------:|--------|
| [wound-healing-migration-reference.md](wound-healing-migration-reference.md) | 932 | Scratch/barrier/invasion assays, chemotaxis, MSD, TrackMate |
| [proliferation-apoptosis-reference.md](proliferation-apoptosis-reference.md) | 713 | Ki67/EdU/TUNEL/CC3, cell cycle, colony assays, high-content screening |
| [neurite-tracing-reference.md](neurite-tracing-reference.md) | 895 | SNT Groovy API, Sholl, Strahler, AnalyzeSkeleton, SWC |
| [organoid-spheroid-reference.md](organoid-spheroid-reference.md) | 906 | Segmentation methods, morphometry, growth curves, drug response |

## Domain-Specific

| Document | Lines | Topics |
|----------|------:|--------|
| [scn-reference.md](scn-reference.md) | 277 | SCN anatomy, markers, antibodies, coupling, quantification methods |
| [scn-analysis-reference.md](scn-analysis-reference.md) | 531 | SCN ROI, PER2::LUC rhythms, phase maps, microglia, amyloid |
| [histology-neurodegeneration-reference.md](histology-neurodegeneration-reference.md) | 631 | Staining, pathological features, staging systems, DAB analysis |
| [brain-atlas-registration-reference.md](brain-atlas-registration-reference.md) | 402 | ABBA, Allen CCFv3, BigWarp, QuPath, bregma coordinates |
| [electron-microscopy-reference.md](electron-microscopy-reference.md) | 888 | TEM/SEM, organelle segmentation, TrakEM2, CLEM, immunogold |

## Light-Sheet & Large Data

| Document | Lines | Topics |
|----------|------:|--------|
| [light-sheet-reference.md](light-sheet-reference.md) | 807 | LSFM types, tissue clearing, BigStitcher/BDV, N5/Zarr/HDF5, whole-brain |
| [large-dataset-optimization-reference.md](large-dataset-optimization-reference.md) | 640 | Memory/JVM tuning, virtual stacks, CLIJ2 GPU, headless, OMERO |

## Publication & Output

| Document | Lines | Topics |
|----------|------:|--------|
| [publication-figures-reference.md](publication-figures-reference.md) | 436 | Journal requirements, DPI, scale bars, montages, panels, export |

## Meta / Agent

| Document | Lines | Topics |
|----------|------:|--------|
| [domain-reference.md](domain-reference.md) | 232 | Microscopy modalities, quantitative methods, quality control |
| [analysis-landscape.md](analysis-landscape.md) | 210 | Analysis tasks across research domains |
| [self-improving-agent-reference.md](self-improving-agent-reference.md) | 153 | Plugin API patterns, automation approaches, parameter optimization |
