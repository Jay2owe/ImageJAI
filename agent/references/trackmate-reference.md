# TrackMate Reference

Particle / cell tracking in Fiji. Modular pipeline:
**Detection → Initial spot filter → Spot features → Spot filters → Tracking
→ Edge features → Track features → Track filters → Display / Export.**

Sources: [TrackMate wiki](https://imagej.net/plugins/trackmate/) ·
[GitHub](https://github.com/trackmate-sc/TrackMate) ·
[Scripting keys](https://imagej.net/plugins/trackmate/scripting/trackmate-detectors-trackers-keys) ·
[Detectors index](https://imagej.net/plugins/trackmate/detectors) ·
[Algorithms](https://imagej.net/plugins/trackmate/algorithms).
Citations: Tinevez et al. 2017 *Methods*; Ershov et al. 2022 *Nat Methods*
(TrackMate 7); Jaqaman et al. 2008 *Nat Methods* (LAP); Matula et al. 2015
*PLoS ONE* (CTC); Maška et al. 2014 *Bioinformatics*, 2023 *Nat Methods*
(Cell Tracking Challenge); Ristani et al. 2016 (IDF1); Bernardin &
Stiefelhagen 2008 (MOTA / CLEAR-MOT).

**TrackMate is NOT macro-recordable.** The only macro entry points are
`run("TrackMate")` (opens GUI) and `run("Load a TrackMate file")`. All
detector / tracker / filter / analyzer configuration lives on Java map
objects. The agent must always invoke TrackMate via:

```bash
python ij.py script '<groovy>'                  # default Groovy
python ij.py script --file /tmp/track.groovy    # for long scripts
python ij.py script --lang jython '<jython>'    # when the official examples are Jython
```

Never use `execute_macro` for TrackMate. See §16 for ready-to-send TCP recipes.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Which detector for small/roundish spots?" | §3.1 LoG / DoG |
| "Which detector for sub-resolved spots in noisy / drifting background?" | §3.1 Spotiflow |
| "Which detector for segmenting cell contours (general)?" | §3.2 Cellpose-SAM |
| "Which detector for nuclei?" | §3.2 StarDist |
| "Which detector for membrane / junction-labelled tissue?" | §3.2 MorphoLibJ |
| "I already have a label image / Cellpose output — feed it in?" | §3.2 LabelImage / Mask |
| "Which detector to pick for image phenotype X?" | §3.4 decision table |
| "Which tracker for dividing cells?" | §4 LAP (Jaqaman) with `ALLOW_TRACK_SPLITTING` |
| "Which tracker for directed motion?" | §4 Kalman |
| "Which tracker for slow segmented cells?" | §4 Overlap |
| "How does the LAP cost matrix work?" | §4.2 |
| "What features does TrackMate compute by default vs require an analyzer?" | §5 |
| "How do I run TrackMate from Jython?" | §7.1 |
| "Groovy equivalent?" | §7.2 |
| "How do I swap detectors/trackers in a script?" | §7.4 |
| "How do I add LAP feature penalties?" | §7.5 |
| "How do I save/load TrackMate XML?" | §8 |
| "How do I export CSV / MaMuT / Mastodon / label image?" | §9 |
| "How do I batch-process a folder?" | §10 |
| "Why are my tracks wrong / why won't it detect anything?" | §11 (diagnostic flow) |
| "How do I validate against ground truth (CTC, MOTA, IDF1)?" | §12 |
| "What update sites add extra detectors/trackers?" | §13 |
| "What parameter values are sensible for my biology?" | §15 |
| "How do I call TrackMate from the TCP agent?" | §16 (4 recipes) |
| "What's the import path for a factory/class?" | §17 |
| "Cellpose enum coercion in Jython failed" | §3.2 Cellpose, §14 |
| "Jython UnicodeEncodeError when running TrackMate" | §7.1 (`sys.setdefaultencoding`) |

---

## §1 Term Index (A–Z)

`grep -n '<term>' trackmate-reference.md` to jump.

### A
`addAllAnalyzers` §7 · `addEdge` §8 · `addSpotTo` §8 · `addSpotFilter` §6, §7 · `addTrackFilter` §6, §7 · `AdvancedCellposeDetectorFactory` §3.2 · `AdvancedSpotiflowDetectorFactory` §3.1 · `ALLOW_GAP_CLOSING` §4 · `ALLOW_TRACK_MERGING` §4 · `ALLOW_TRACK_SPLITTING` §4 · `ALTERNATIVE_LINKING_COST_FACTOR` §4 · `AOGM` §12 · `AREA` §5 · `ARFF` §3.2 (Weka)

### B
`Batcher` §10 · `beginUpdate` §8 · `BLOCKING_VALUE` §4

### C
`call()` — N/A · `CaptureOverlayAction` §6 · `CELL_DIAMETER` §3.2 · `CELL_MIN_SIZE` §3.2 · `CELL_PROB_THRESHOLD` §3.2 · `CELLPOSE_MODEL` §3.2 · `CELLPOSE_MODEL_FILEPATH` §3.2 · `CELLPOSE_PYTHON_FILEPATH` §3.2 · `CellposeDetectorFactory` §3.2, §17 · `CellposeSAMDetectorFactory` §3.2, §17 · `checkInput` §7 · `CIRCULARITY` §5 · `CLASS_INDEX` §3.2 · `CLASSIFIER_FILEPATH` §3.2 · `CLEAR-MOT` §12 · `ColorByFeatureAction` §6 · `computeEdgeFeatures` §7 · `computeSpotFeatures` §7 · `computeTrackFeatures` §7 · `CONFINEMENT_RATIO` §5 · `CONNECTIVITY` §3.2 · `CONTRAST_CHn` §5 · `Conda env (Cellpose)` §3.2 · `convertToMask` — N/A · `Core classes` §2 · `CSVExporter` §9, §17 · `CT (CTC metric)` §12 · `CTC` §12 · `CUTOFF_PERCENTILE` §4

### D
`DET (CTC metric)` §12 · `DIRECTIONAL_CHANGE_RATE` §5 · `DISPLACEMENT` §5 · `DisplaySettings` §6 · `DisplaySettingsIO` §6, §7 · `DO_MEDIAN_FILTERING` §3.1, §3.3 · `DO_SUBPIXEL_LOCALIZATION` §3.1, §3.3 · `DO2DZ` §3.2 · `DogDetectorFactory` §3.1, §17 · `dt` §7

### E
`EDGE_TIME` §5 · `EDGE_X/Y/Z_LOCATION` §5 · `EdgeAnalyzerProvider` §7, §17 · `endUpdate` §8 · `ELLIPSE_ASPECTRATIO` §5 · `ELLIPSE_MAJOR/MINOR/THETA/X0/Y0` §5 · `ELLIPSOIDFIT_*` §5 · `execDetection / execInitialSpotFiltering / execSpotFiltering / execTracking / execTrackFiltering` §7 · `ExportTracksToXML` §9, §17 · `ExportSpotsAsLabelImageAction` §9

### F
`FastRandomForest` §3.2 (Weka) · `FeatureFilter` §6, §7, §17 · `FeatureModel` §2 · `FLOW_THRESHOLD` §3.2 · `FRAME` §5

### G
`GAP_CLOSING_FEATURE_PENALTIES` §4 · `GAP_CLOSING_MAX_DISTANCE` §4, §7 · `getDefaultSettings` §7 · `getFactory` §7 · `getFeature` §7 · `getKeys` §7 · `getTrackFeature` §7 · `Ground-truth XML (CTC)` §12

### H
`HessianDetectorFactory` §3.1, §17 · `HyperStackDisplayer` §6, §17

### I
`IDF1` §12 · `IlastikDetectorFactory` §3.2, §17 · `INTENSITY_THRESHOLD` §3.2 · `IoU` §4 · `IOU_CALCULATION` §4

### J
`Jaqaman LAP` §4 · `JOptimal Pane (Jonker-Volgenant)` §4

### K
`KALMAN_SEARCH_RADIUS` §4, §7 · `KalmanTrackerFactory` §4, §7, §17

### L
`Label image (input)` §3.2 · `LabelImageDetectorFactory` §3.2, §17 · `LabelImgExporter` §9 · `Lacss` §13 · `LAPUtils` §7, §17 · `LINEARITY_OF_FORWARD_PROGRESSION` §5 · `LINKING_FEATURE_PENALTIES` §4, §7 · `LINKING_MAX_DISTANCE` §4, §7 · `LINK_COST` §5 · `LogDetectorFactory` §3.1, §7, §17 · `Logger` §7, §10 · `LONGEST_GAP` §5

### M
`ManualDetectorFactory` §3.1 · `ManualTrackerFactory` §4 · `MaskDetectorFactory` §3.2, §17 · `Mastodon (export)` §9 · `MAX_DISTANCE_TRAVELED` §5 · `MAX_FRAME_GAP` §4, §7 · `MAX_INTENSITY_CHn` §5 · `MEAN_INTENSITY_CHn` §5, §7 · `MEAN_STRAIGHT_LINE_SPEED` §5 · `MEAN_DIRECTIONAL_CHANGE_RATE` §5 · `MEDIAN_INTENSITY_CHn` §5 · `MERGING_FEATURE_PENALTIES` §4 · `MERGING_MAX_DISTANCE` §4 · `MIN_INTENSITY_CHn` §5 · `MIN_IOU` §4, §7 · `Model` §2, §7 · `MODEL_FILEPATH` §3.2 · `MorphoLibJDetectorFactory` §3.2, §17 · `MOTA / MOTP` §12 · `MSD` §5

### N
`NearestNeighborTrackerFactory` §4 · `Nearest-neighbour spot analyzer` §5 · `NORMALIZE` §3.1 · `NUMBER_COMPLEX/GAPS/MERGES/SPLITS/SPOTS` §5

### O
`Omnipose` §3.2, §13 · `OPTIONAL_CHANNEL_2` §3.2 · `OVERLAP_THRESHOLD` §3.2 · `OverlapTrackerFactory` §4, §7, §17

### P
`PERIMETER` §5 · `PerEdgeFeatureColorGenerator` §6 · `PerTrackFeatureColorGenerator` §6 · `POSITION_T` §5 · `POSITION_X/Y/Z` §5, §7 · `PROBA_THRESHOLD` §3.2 · `PROBABILITY_THRESHOLD` §3.1 · `PRETRAINED_OR_CUSTOM` §3.2, §3.1 · `PretrainedModel (legacy enum)` §3.2, §14 · `process` §7

### Q
`QUALITY` §5, §7

### R
`RADIUS` §3.1, §5, §7 · `RADIUS_Z` §3.1 · `readImage / readSettings` §8 · `RoiManager (export)` §9, §14 · `RunMacro` — see §0

### S
`SCALE_FACTOR` §4 · `SCORE_THRESHOLD` §3.2, §7 · `SEG (CTC metric)` §12 · `SelectionModel` §6, §7 · `Settings` §2, §7 · `SHAPE_INDEX` §5 · `SIMPLIFY_CONTOURS` §3.2 · `SimpleSparseLAPTrackerFactory` §4, §17 · `SNR_CHn` §5 · `SOLIDITY` §5 · `SparseLAPTrackerFactory` §4, §7, §17 · `SPEED` §5 · `Spot (class)` §2, §8 · `SpotAnalyzerProvider` §7, §17 · `SpotCollection` §2, §7 · `SpotDisplayer3D` §6 · `SpotIntensityMultiCAnalyzer` §5 · `SPOT_SOURCE_ID / TARGET_ID` §5 · `SPOTIFLOW_PRETRAINED_MODEL` §3.1 · `SpotiflowDetectorFactory` §3.1, §17 · `SPLITTING_FEATURE_PENALTIES` §4 · `SPLITTING_MAX_DISTANCE` §4 · `StarDistCustomDetectorFactory` §3.2, §17 · `StarDistDetectorFactory` §3.2, §7, §17 · `STD_INTENSITY_CHn` §5

### T
`TARGET_CHANNEL` §3.1, §3.2, §3.3, §7 · `THRESHOLD` §3.1, §7 · `ThresholdDetectorFactory` §3.2, §17 · `TmXmlReader / TmXmlWriter` §8, §17 · `TOLERANCE` §3.2 · `TOTAL_DISTANCE_TRAVELED` §5 · `TOTAL_INTENSITY_CHn` §5 · `TRA (CTC metric)` §12 · `TRACK_DISPLACEMENT` §5 · `TRACK_DURATION` §5, §7 · `TRACK_ID / TRACK_INDEX` §5 · `TRACK_MEAN_QUALITY` §5 · `TRACK_MEAN/MAX/MIN/MEDIAN/STD_SPEED` §5 · `TRACK_START / TRACK_STOP` §5 · `TRACK_X/Y/Z_LOCATION` §5 · `TrackAnalyzerProvider` §7, §17 · `Trackastra` §4, §13 · `TrackMate (class)` §2, §7 · `TrackMate-Batcher` §10 · `TrackMate-CSVImporter` §13 · `TrackMate-Helper` §10, §12 · `trackIDs` §7 · `trackSpots` §7 · `TrackScheme` §6 · `TrackTableView` §9, §17

### U
`USE_GPU` §3.2

### V
`VISIBILITY` §5

### W
`WekaDetectorFactory` §3.2, §17

### X / Y
`XML I/O` §8 · `YOLO_CONF_THRESHOLD` §3.2 · `YOLO_IOU_THRESHOLD` §3.2 · `YOLO_MODEL_FILEPATH` §3.2 · `YOLODetectorFactory` §3.2, §17

---

## §2 Core Classes

| Class | Role |
|-------|------|
| `Model` | Owns `SpotCollection`, edge graph, tracks, `FeatureModel`. Listeners fire on edits inside `beginUpdate/endUpdate`. |
| `Settings` | Config bundle: image, detector, detectorSettings map, tracker, trackerSettings map, list of analyzer factories, list of `FeatureFilter`s. |
| `TrackMate` | Orchestrator. `process()` runs the full pipeline; `exec*()` / `compute*()` step it manually. |
| `FeatureModel` | Edge & track feature storage on the Model. Spot features live on each `Spot`. |
| `Spot` | Single detection. `Spot(x, y, z, radius, quality)` constructor. Holds feature map. |
| `SpotCollection` | Spots indexed by frame. `iterable(boolean visibleOnly)`, `getNSpots(boolean visibleOnly)`. |
| `SelectionModel` | Shared cursor across views (HyperStackDisplayer / TrackScheme / 3D). |
| `DisplaySettings` | Colormap, line width, color-by-feature target, track display mode. Persistence via `DisplaySettingsIO`. |

---

## §3 All Detectors

Detectors are `SpotDetectorFactory` (per-frame, multithreaded, all built-ins
plus Threshold/Mask/LabelImage) or `SpotGlobalDetectorFactory` (single-threaded
per-frame, all DL detectors that shell out to a Python CLI).

**Output category** determines which features become available:

- **Spots-only** (LoG / DoG / Hessian / Manual / Spotiflow / YOLO):
  `POSITION_X/Y/Z`, `RADIUS`, `QUALITY`. No contour. Morphology features
  (`AREA`, `PERIMETER`, ellipse fit) are absent.
- **Contour-bearing** (Threshold / Mask / LabelImage / StarDist / Cellpose /
  Cellpose-SAM / Omnipose / Ilastik / Weka / MorphoLibJ): contour stored on
  the Spot. Enables 2D morphology + ellipse + ellipsoid features. Also
  enables `OverlapTrackerFactory` (which needs polygons).

**`TARGET_CHANNEL` semantics differ by detector family**:

- Built-in (LoG / DoG / Hessian / Threshold / Mask / LabelImage / StarDist /
  Ilastik / Weka / MorphoLibJ): **1-based**, range `1..nChannels`. `0` is
  invalid.
- CLI-based (Cellpose / Cellpose-SAM / Omnipose / Spotiflow / YOLO):
  **`0` means "all channels / grayscale composite"**, `1..N` selects a
  channel. Cellpose v3 also has `OPTIONAL_CHANNEL_2` for the nuclei
  reference channel (also 0 = none).

---

### §3.1 Spot Detectors (no contour)

#### LogDetectorFactory — Laplacian of Gaussian

`fiji.plugin.trackmate.detection.LogDetectorFactory` · KEY `"LOG_DETECTOR"` ·
built-in.

**When:** roundish bright blobs (puncta, vesicles, single molecules,
nuclei) on darker background. The default first choice for sub-resolved /
moderate-size objects (<50 k spots/frame).
**When NOT:** densely touching objects (no contour separation), spots near
strong edges (Hessian responds less to edges), >100 k spots/frame on slow
hardware (use DoG).

| Key | Type | Default | Units | Meaning |
|---|---|---|---|---|
| `TARGET_CHANNEL` | int | 1 | 1-based | channel to detect on |
| `RADIUS` | double | 5.0 | physical (μm) | expected blob radius |
| `THRESHOLD` | double | 0.0 | quality units | drop spots below this |
| `DO_MEDIAN_FILTERING` | bool | false | — | 3×3 median pre-filter (~2× slower) |
| `DO_SUBPIXEL_LOCALIZATION` | bool | true | — | quadratic-fit sub-pixel X/Y/Z |

3D: yes, isotropic radius (use Hessian instead for anisotropic Z).
Failure: wrong `RADIUS` halves recall; leaving `THRESHOLD=0` returns
thousands of spurious detections — preview, then set threshold from the
quality histogram.

```groovy
import fiji.plugin.trackmate.detection.LogDetectorFactory
settings.detectorFactory = new LogDetectorFactory()
settings.detectorSettings = ['TARGET_CHANNEL':1, 'RADIUS':5.0d, 'THRESHOLD':10.0d,
    'DO_MEDIAN_FILTERING':false, 'DO_SUBPIXEL_LOCALIZATION':true]
```

#### DogDetectorFactory — Difference of Gaussians

`fiji.plugin.trackmate.detection.DogDetectorFactory` · KEY `"DOG_DETECTOR"` ·
built-in.

Same parameter set + defaults as LoG. ~3× faster than LoG at small radii.

**When:** small spots (radius ≤ 5 px), high-throughput SMLM-style frames.
**When NOT:** spots larger than ~10 px radius — DoG approximation degrades
and quality values become unreliable.

#### HessianDetectorFactory

`fiji.plugin.trackmate.detection.HessianDetectorFactory` · KEY `"HESSIAN_DETECTOR"` ·
built-in (TrackMate v7+).

**When:** bright blobs adjacent to strong edges (vasculature, debris)
where LoG over-responds; anisotropic 3D where XY and Z spot sizes differ;
when you want quality normalised to [0, 1] per-frame for cross-frame
threshold portability.

| Key | Type | Default | Units | Meaning |
|---|---|---|---|---|
| `TARGET_CHANNEL` | int | 1 | 1-based | — |
| `RADIUS` | double | 5.0 | physical | XY radius |
| `RADIUS_Z` | double | 8.0 | physical | Z radius (independent) |
| `THRESHOLD` | double | 0.0 | quality | — |
| `NORMALIZE` | bool | false | — | rescale quality to [0,1] per time-point |
| `DO_SUBPIXEL_LOCALIZATION` | bool | true | — | — |

Single-threaded per frame (`forbidMultithreading` returns true). No
median-filter knob. `RADIUS_Z` defaults 8 even on 2D — set explicitly.
`NORMALIZE=true` masks brightness drift across frames (use deliberately).

#### ManualDetectorFactory

`fiji.plugin.trackmate.detection.ManualDetectorFactory` · KEY `"MANUAL_DETECTOR"`.
For ground-truth annotation only. Just `RADIUS` parameter.

#### SpotiflowDetectorFactory — DL spot detection

`fiji.plugin.trackmate.spotiflow.SpotiflowDetectorFactory` · KEY `"SPOTIFLOW_DETECTOR"` ·
update site `TrackMate-Spotiflow`. Conda env required.

**When:** sub-resolved fluorescent spots (single-molecule FISH, live RNA,
particles) under noisy or varying-background conditions where LoG/DoG
miss faint spots. Auto-estimates spot size, stronger noise robustness.
**When NOT:** large structured objects (use Cellpose), no conda available.

| Key | Type | Default | Notes |
|---|---|---|---|
| `SPOTIFLOW_PRETRAINED_MODEL` | String | `"fluo_live"` | one of: `general`, `fluo_live`, `hybiss`, `synth_complex`, `synth_3d`, `smfish_3d` |
| `TARGET_CHANNEL` | int | 1 | 1-based |

Spots-only (no contour). 3D via `synth_3d` / `smfish_3d`. Wrong model →
silent recall drop; `fluo_live` over-detects on smFISH (use `smfish_3d`).

`AdvancedSpotiflowDetectorFactory` (KEY `"ADVANCED_SPOTIFLOW_DETECTOR"`)
adds `SPOTIFLOW_MODEL_FILEPATH`, `PRETRAINED_OR_CUSTOM`,
`PROBABILITY_THRESHOLD` (0.5), `MIN_DISTANCE`, `DO_SUBPIXEL_LOCALIZATION`.

Install:
```bash
conda create -n spotiflow python=3.10 -y
conda activate spotiflow
pip install spotiflow
# Windows GPU: pip install torch torchvision --index-url https://download.pytorch.org/whl/cu126
```

#### YOLODetectorFactory — bounding-box detection

`fiji.plugin.trackmate.yolo.YOLODetectorFactory` · KEY `"YOLO_DETECTOR"` ·
update site `TrackMate-YOLO`. Conda env required.

**When:** multi-class object detection on natural-image-like microscopy
(brightfield, organisms), or when you've trained an Ultralytics YOLO model
(`.pt`) for your domain.
**When NOT:** sub-resolved spots; pixel-precise contours (YOLO returns
boxes only; spot radius = half bbox diagonal).

| Key | Type | Default | Notes |
|---|---|---|---|
| `YOLO_MODEL_FILEPATH` | String | `""` | path to `.pt` (no built-in zoo) |
| `YOLO_CONF_THRESHOLD` | double | 0.25 | min detection confidence |
| `YOLO_IOU_THRESHOLD` | double | 0.7 | NMS IoU |
| `USE_GPU` | bool | true | sends `device=cuda` / `mps` / `cpu` |

2D only. Install: `conda create -n yolo python=3.10; pip install ultralytics`.

---

### §3.2 Segmenters (contour-bearing)

#### ThresholdDetectorFactory

`fiji.plugin.trackmate.detection.ThresholdDetectorFactory` · KEY `"THRESHOLD_DETECTOR"` ·
built-in.

| Key | Type | Default | Notes |
|---|---|---|---|
| `TARGET_CHANNEL` | int | 1 | 1-based |
| `INTENSITY_THRESHOLD` | double | 0.0 | absolute pixel value |
| `SIMPLIFY_CONTOURS` | bool | true | Douglas-Peucker simplification |

Quality = mean intensity inside contour. Touching objects merge into one
spot — pre-watershed with `Process > Binary > Watershed` if needed.
Threshold is in raw image units (different scale per bit-depth).

#### MaskDetectorFactory

`fiji.plugin.trackmate.detection.MaskDetectorFactory` · KEY `"MASK_DETECTOR"`.
Subclasses ThresholdDetectorFactory. Treats every connected non-zero region
as one object. Use when input is already a binary mask.

#### LabelImageDetectorFactory

`fiji.plugin.trackmate.detection.LabelImageDetectorFactory` · KEY `"LABEL_IMAGE_DETECTOR"`.
Each unique integer label = one spot. Preserves separation even where
labels touch — the right choice for label images from external Cellpose /
StarDist runs.

```groovy
import fiji.plugin.trackmate.detection.LabelImageDetectorFactory
settings.detectorFactory = new LabelImageDetectorFactory()
settings.detectorSettings = ['TARGET_CHANNEL':1, 'SIMPLIFY_CONTOURS':true]
```

#### StarDistDetectorFactory — built-in 2D nuclei model

`fiji.plugin.trackmate.stardist.StarDistDetectorFactory` · KEY `"STARDIST_DETECTOR"` ·
update sites `CSBDeep`, `TensorFlow`, `StarDist`, `TrackMate-StarDist`.
Restart Fiji after enabling all four.

**When:** fluorescent nuclei in 2D / 2D+T. Bundled "Versatile (fluorescent
nuclei)" model. Zero parameters to tune.
**When NOT:** 3D, brightfield, non-nuclear shapes — use the custom variant
with a domain-trained model.

Only key: `TARGET_CHANNEL` (int, 1). Score / overlap thresholds are baked
into the bundled model.

Failure: TensorFlow native-library mismatch on Apple Silicon → use the
`TensorFlow` update-site CPU build. GPU only on CUDA Linux/Windows.

#### StarDistCustomDetectorFactory — user-trained model

KEY `"STARDIST_CUSTOM_DETECTOR"`.

| Key | Type | Default | Notes |
|---|---|---|---|
| `TARGET_CHANNEL` | int | 1 | 1-based |
| `MODEL_FILEPATH` | String | `""` | path to *zipped* `.zip` model (NOT folder) |
| `SCORE_THRESHOLD` | double | 0.41 | object-probability threshold |
| `OVERLAP_THRESHOLD` | double | 0.5 | non-max-suppression IoU |

`MODEL_FILEPATH` must be the zipped model directory exactly as exported by
Python `model.export_TF()`. Pointing to the unzipped folder fails with a
confusing TF error. Anisotropy must match training data — TrackMate does
not rescale Z.

#### CellposeDetectorFactory — Cellpose 3.x

`fiji.plugin.trackmate.cellpose.CellposeDetectorFactory` · KEY `"CELLPOSE_DETECTOR"` ·
update site `TrackMate-Cellpose` (v3.1.1.2 of plugin, pinned to Cellpose 3).

**When:** cells / nuclei / bacteria where one of the bundled Cellpose 3
models fits, or you have a custom Cellpose `.pth`. Best general-purpose
contour detector when you need specialist models.
**When NOT:** sub-resolved spots; when Cellpose-SAM gives better
generalisation; no conda installation.

Current TrackMate-Cellpose v3 uses **plain string model names** matching
the Cellpose CLI's `--pretrained_model`:

| Key | Type | Default | Notes |
|---|---|---|---|
| `CELLPOSE_MODEL` | String | `"cyto3"` | one of: `cyto3`, `nuclei`, `tissuenet_cp3`, `livecell_cp3`, `yeast_PhC_cp3`, `yeast_BF_cp3`, `bact_phase_cp3`, `bact_fluor_cp3`, `deepbacs_cp3`, `cyto2`, `cyto` |
| `PRETRAINED_OR_CUSTOM` | String | `"CELLPOSE_MODEL"` | flip to `"CELLPOSE_MODEL_FILEPATH"` for custom |
| `CELLPOSE_MODEL_FILEPATH` | String | `""` | path to custom `.pth` |
| `TARGET_CHANNEL` | int | 0 | **0 = grayscale composite**, ≥1 = single channel |
| `OPTIONAL_CHANNEL_2` | int | 0 | nuclei reference channel for cyto* models; 0 = none |
| `CELL_DIAMETER` | double | 30.0 | physical units; 0 = let Cellpose auto-estimate (slow) |

**Legacy enum (pre-2024 builds only).** Older TrackMate-Cellpose used a
`PretrainedModel` Java enum (`CYTO`, `CYTO2`, `CYTO3`, `NUCLEI`,
`CYTO2_OMNI`, `BACT_OMNI`, `CUSTOM`). Jython could not coerce strings, so
scripts had to import the enum:
```python
# Legacy plugin builds only:
from fiji.plugin.trackmate.cellpose.CellposeSettings import PretrainedModel
settings_map['CELLPOSE_MODEL'] = PretrainedModel.CYTO2
```
If a current script raises `ClassCastException: String cannot be cast to
PretrainedModel`, the user is on a legacy build — coerce via the enum.
Otherwise pass strings directly. Groovy auto-coerces in both eras.

`AdvancedCellposeDetectorFactory` (KEY `"ADVANCED_CELLPOSE_DETECTOR"`)
adds: `FLOW_THRESHOLD` (0.4), `CELL_PROB_THRESHOLD` (0.0),
`NO_RESAMPLE` (false), `CELL_MIN_SIZE` (15.0 px), `DO2DZ` (false; enables
2D+stitch 3D), `IOUTHRESHOLD` (0.25, stitch IoU when `DO2DZ=true`).
Increase `FLOW_THRESHOLD` to find more cells; decrease `CELL_PROB_THRESHOLD`
to find more cells. For 3D timelapses prefer `DO2DZ=true` over Cellpose's
true 3D mode (faster, more stable).

Install:
```bash
conda create -n cellpose-3 python=3.10 -y
conda activate cellpose-3
pip install 'cellpose[gui]==3.1.1.2'
# Windows GPU only:
pip install torch torchvision --index-url https://download.pytorch.org/whl/cu126
```

Configure once via *Edit > Options > Configure TrackMate Conda path…*. The
preference key is `trackmate.conda.path`. Env name is what the user types
in the dialog (`cellpose-3`).

```groovy
import fiji.plugin.trackmate.cellpose.CellposeDetectorFactory
settings.detectorFactory = new CellposeDetectorFactory()
settings.detectorSettings = settings.detectorFactory.getDefaultSettings()
settings.detectorSettings['CELLPOSE_MODEL']           = 'cyto3'
settings.detectorSettings['PRETRAINED_OR_CUSTOM']     = 'CELLPOSE_MODEL'
settings.detectorSettings['TARGET_CHANNEL']           = 0
settings.detectorSettings['OPTIONAL_CHANNEL_2']       = 0
settings.detectorSettings['CELL_DIAMETER']            = 30.0d
```

#### CellposeSAMDetectorFactory — Cellpose 4 / cpsam

`fiji.plugin.trackmate.cellpose.sam.CellposeSAMDetectorFactory` · KEY
`"CELLPOSE_SAM_DETECTOR"` · update site `TrackMate-Cellpose`.
Backed by Cellpose 4 (`cellpose[gui] >= 4.0`).

**When:** generalist segmentation across diverse modalities (brightfield,
fluorescence, EM, H&E) without picking a specialist model. Better
cross-domain than Cellpose 3. **Default first choice for unknown / mixed
data.**
**When NOT:** GPU-poor machines — CPU inference can be 10–30 s/frame.

| Key | Type | Default | Notes |
|---|---|---|---|
| `CELLPOSE_MODEL` | String | `"cpsam"` | only built-in name |
| `PRETRAINED_OR_CUSTOM` | String | `"CELLPOSE_MODEL"` | flip for custom |
| `CELLPOSE_MODEL_FILEPATH` | String | `""` | path to custom cpsam-fine-tuned `.pth` |
| `TARGET_CHANNEL` | int | 0 | 0 = all channels, ≥1 = single |

No diameter, no second channel — cpsam ingests all channels.

Install: separate env, `pip install 'cellpose[gui]'` (≥4.x ships cpsam).

#### Omnipose

Not a separate factory in current TrackMate-Cellpose. Use the dedicated
`TrackMate-Omnipose` update site → `OmniposeDetectorFactory`
(`fiji.plugin.trackmate.omnipose.OmniposeDetectorFactory`,
KEY `"OMNIPOSE_DETECTOR"`), which mirrors Cellpose's settings against an
`omnipose` conda env. Best for rod-shaped bacteria and filaments.

#### IlastikDetectorFactory

`fiji.plugin.trackmate.ilastik.IlastikDetectorFactory` · KEY `"ILASTIK_DETECTOR"` ·
update sites `ilastik`, `TrackMate-Ilastik`. Plus a separate ilastik
desktop install (configure via *Plugins > ilastik > Configure ilastik
executable location*).

| Key | Type | Default | Notes |
|---|---|---|---|
| `TARGET_CHANNEL` | int | 1 | 1-based |
| `CLASSIFIER_FILEPATH` | String | `""` | absolute path to `.ilp` (Pixel Classification project) |
| `CLASS_INDEX` | int | 0 | **0-based**; which class is foreground |
| `PROBA_THRESHOLD` | double | 0.5 | threshold on probability map |

`.ilp` must be a Pixel Classification project (not Object Classification).
`CLASS_INDEX` is 0-based even though `TARGET_CHANNEL` is 1-based. Heavy
RAM — set Fiji `-Xmx` ≥ 8 GB.

#### WekaDetectorFactory

`fiji.plugin.trackmate.weka.WekaDetectorFactory` · KEY `"WEKA_DETECTOR"` ·
update site `TrackMate-Weka`. Same key set as ilastik with
`CLASSIFIER_FILEPATH` pointing to a `.model` file from
*Plugins > Segmentation > Trainable Weka Segmentation*.

Failure: `.model` must include the exact feature stack used at training.
A typical 3D classifier on 50 frames runs ~8 minutes single-core.

#### MorphoLibJDetectorFactory

`fiji.plugin.trackmate.morpholibj.MorphoLibJDetectorFactory` · KEY
`"MORPHOLIBJ_DETECTOR"` · update sites `IJPB-plugins`, `TrackMate-MorphoLibJ`.

**When:** **border-labelled** images — fluorescent membranes, cell-cell
junctions, tissue boundaries — where each cell appears as a closed dark
region surrounded by a bright contour. Performs morphological watershed
across the timelapse.
**When NOT:** noisy images (oversegments badly), filled fluorescent
objects, >10 % per-frame motion (watershed boundaries shift).

| Key | Type | Default | Notes |
|---|---|---|---|
| `TARGET_CHANNEL` | int | 1 | 1-based |
| `TOLERANCE` | double | 30.0 | grey-level h-min for regional minima; 8-bit ~10, 16-bit ~2000 |
| `CONNECTIVITY` | int | 26 | 4 or 8 in 2D; 6 or 26 in 3D (`STRAIGHT`/`DIAGONAL`) |
| `SIMPLIFY_CONTOURS` | bool | false | — |

`TOLERANCE` is bit-depth dependent — copying an 8-bit value onto 16-bit
gives ~6500 oversegments. Always preview.

---

### §3.3 Detector-tuning workflow

**Preview cycle.** Every detector dialog has a Preview button — runs on the
current visible frame and overlays detected spots immediately. Pick a
representative frame, set parameters → Preview → adjust:

| Symptom | Knob to turn |
|---|---|
| too few spots | lower `THRESHOLD` / `INTENSITY_THRESHOLD` / `PROBA_THRESHOLD` / `CELL_PROB_THRESHOLD` / `YOLO_CONF_THRESHOLD` / `PROBABILITY_THRESHOLD`; increase Cellpose `FLOW_THRESHOLD` |
| too many / merged | opposite; for Cellpose decrease `FLOW_THRESHOLD`; for MorphoLibJ raise `TOLERANCE` |
| wrong size (LoG/DoG/Hessian) | adjust `RADIUS` |
| wrong size (Cellpose) | adjust `CELL_DIAMETER`; `0` = auto-estimate (slow) |

**Quality histogram.** TrackMate's *Initial thresholding* page plots
per-spot quality. Look for a bimodal distribution: left peak = noise,
right peak = signal, threshold at the trough. Unimodal → either re-run
with `THRESHOLD=0` to see the full spread, or there's no separation (try
a different detector).

`HessianDetectorFactory` with `NORMALIZE=true` rescales quality to [0,1]
per frame — makes a single threshold portable across photobleaching.
Otherwise quality scale is detector-specific (LoG ≈ filter response;
Threshold ≈ mean intensity; contour-based ≈ area or probability).

**`DO_SUBPIXEL_LOCALIZATION`.** True by default for LoG / DoG / Hessian /
AdvancedSpotiflow. Quadratic-fit the 3×3×3 neighbourhood; refines X/Y/Z
to ~0.1 px. ~5 % runtime cost. **Always leave on for tracking** — sub-pixel
jitter dominates short-track displacement statistics if disabled.

**`DO_MEDIAN_FILTERING`** (LoG / DoG only). 3×3 median pre-filter inside the
detector. Useful for shot-noise-dominated images; ~2× cost. Not equivalent
to running `Process > Filters > Median…` first — applied to raw, not yet
Gaussian-smoothed pixels.

**3D support:**

| Detector | 2D | 3D | Anisotropic Z |
|---|---|---|---|
| LoG / DoG | yes | yes | no — uses `RADIUS` for both |
| Hessian | yes | yes | **yes — `RADIUS_Z`** |
| Threshold / Mask / LabelImage | yes | yes | n/a |
| StarDist (built-in) | yes | **no** | — |
| StarDistCustom | yes | yes (model-bound) | model-bound |
| Cellpose v3 (basic) | yes | slice-by-slice | — |
| AdvancedCellpose | yes | yes (`DO2DZ`) | — |
| Cellpose-SAM | yes | slice-by-slice | — |
| Ilastik / Weka / MorphoLibJ | yes | yes | — |
| Spotiflow basic | yes | model-dependent | — |
| AdvancedSpotiflow | yes | yes (`synth_3d`, `smfish_3d`) | — |
| YOLO | yes | no (slice-by-slice) | — |

Only Hessian decouples XY/Z radius. For LoG/DoG with anisotropic voxels,
calibrate Z to match XY in *Image > Properties* before running.

---

### §3.4 Decision table — which detector?

| Image phenotype | First choice | Fallback | Why |
|---|---|---|---|
| Sub-resolved fluorescent puncta, 2D | DoG | LoG, Spotiflow | speed + sub-pixel; Spotiflow if SNR poor |
| Sub-resolved puncta, varying background | Spotiflow (`general`) | LoG `DO_MEDIAN_FILTERING=true` | DL handles drift |
| Fluorescent nuclei, 2D | StarDist (built-in) | Cellpose-SAM | StarDist is zero-config |
| Fluorescent nuclei, 3D | StarDistCustom (3D model) | Cellpose-SAM slice-by-slice | StarDist built-in is 2D only |
| Cell bodies (cyto + nuclei stain), 2D | Cellpose v3 `cyto3` | Cellpose-SAM | SAM if cyto3 misses |
| Mixed / unknown morphology | Cellpose-SAM | Cellpose v3 `cyto3` | SAM generalises better cross-domain |
| Bacteria, brightfield / phase | Cellpose v3 `bact_phase_cp3` | Omnipose `bact_omni` | rod-shape friendly |
| Yeast, brightfield | Cellpose v3 `yeast_BF_cp3` | Cellpose-SAM | trained model |
| Membrane / junction-labelled tissue | MorphoLibJ | Cellpose-SAM | watershed designed for this case |
| Histology (H&E, IHC), 2D | Cellpose-SAM | YOLO (custom) | brightfield generalisation |
| Pre-segmented binary mask | Mask | — | trivially correct |
| Pre-segmented label image | LabelImage | — | preserves separation |
| Existing `.ilp` Ilastik project | Ilastik | — | reuse training |
| Existing `.model` Weka classifier | Weka | — | reuse training |
| Spots near bright edges | Hessian | LoG `DO_MEDIAN_FILTERING` | edge-response suppression |
| Anisotropic Z, blob detection | Hessian (`RADIUS_Z`) | DoG | only Hessian decouples XY/Z |
| Multi-class object boxes | YOLO | — | only YOLO does multi-class |

When unsure: **Cellpose-SAM** (any modality, contour) or **DoG**
(sub-resolved spots, fast preview).

---

## §4 All Trackers

`fiji.plugin.trackmate.tracking.*`. Tracker factories produce edges in the
Model graph; track features are computed by registered TrackAnalyzers
afterwards.

### §4.1 Selection table

| Motion type | Tracker | Reason |
|---|---|---|
| Brownian, no division | `SimpleSparseLAPTrackerFactory` | distance-only LAP, fewest knobs |
| Brownian + divisions / fusion / dropouts | `SparseLAPTrackerFactory` | splits / merges / gap-closing |
| Directed motion, ~constant velocity | `KalmanTrackerFactory` | predicts position, robust to crowding |
| Slow segmented cells (epithelia, organoids, mitochondria) | `OverlapTrackerFactory` | IoU on contours; immune to centroid jitter |
| Sparse, well-separated, simple | `NearestNeighborTrackerFactory` | greedy frame-to-frame |
| Dividing cells, dense culture, motion+appearance | `TrackastraTrackerFactory` | transformer model |
| Manual curation | `ManualTrackerFactory` | no automatic linking |

Pitfalls:
- `LINKING_MAX_DISTANCE` < typical step → spots drop out, false fragments.
- `LINKING_MAX_DISTANCE` > inter-cell spacing → identity swaps.
- `MAX_FRAME_GAP` too high → false bridges between unrelated cells.
- Splits + merges in non-dividing data → spurious topology.
- Kalman with `LINKING_MAX_DISTANCE` < first-frame step → never bootstraps.
- Overlap with `SCALE_FACTOR=1` on fast movers → tracks break (no IoU).

### §4.2 SparseLAPTrackerFactory — the canonical LAP

`fiji.plugin.trackmate.tracking.jaqaman.SparseLAPTrackerFactory` · KEY
`"SPARSE_LAP_TRACKER"`.

Full Jaqaman et al. 2008 LAP. **Two-step** optimisation:

1. **Frame-to-frame linking.** For consecutive frames, build an
   `(n+m) × (n+m)` cost matrix:
   - top-left: real link costs `(spot_i^t → spot_j^{t+1})`,
   - top-right (`n × n` diagonal): "end segment" alternative cost,
   - bottom-left (`m × m` diagonal): "start segment" alternative cost,
   - bottom-right: lower-right transposed top-left (auxiliary block
     required by the Jonker-Volgenant / Munkres-Kuhn solver).
   Solved by sparse LAP; cells with `D > LINKING_MAX_DISTANCE` are skipped.

2. **Track-segment linking.** Track segments produced in step 1 are
   connected via a second `(N+M) × (N+M)` cost matrix with sub-blocks
   for gap-closing, splitting, and merging (each enabled by its `ALLOW_*`
   flag). Same alternative-cost trick.

**Cost formula (linking).** For two spots at distance `D` and feature
differences `f1, f2` weighted by `W`:

```
p_feat = 3 * W * |f1 - f2| / (f1 + f2)        # ∈ [0, 3W]
P      = 1 + sum(p_feat)
C      = (D * P)^2                             # squared
```

Distances dominate when no penalty is set; features matter only via
`*_FEATURE_PENALTIES`.

**Alternative cost** = `ALTERNATIVE_LINKING_COST_FACTOR × max(C_real)`
(default 1.05 — real links preferred but only if cheaper than 1.05× the
worst real link).

**`CUTOFF_PERCENTILE`** (default 0.9). Used to set finite blocking from
the cost distribution when `BLOCKING_VALUE` isn't finite — protects against
pathological max-cost outliers.

| Key | Type | Default | Units | Meaning |
|---|---|---|---|---|
| `LINKING_MAX_DISTANCE` | double | 15.0 | physical | frame-to-frame radius |
| `LINKING_FEATURE_PENALTIES` | Map<String,Double> | `{}` | — | spot feature key → weight `W` ≥ 0 |
| `ALLOW_GAP_CLOSING` | bool | true | — | enable gap-closing block |
| `GAP_CLOSING_MAX_DISTANCE` | double | 15.0 | physical | bridge distance |
| `MAX_FRAME_GAP` | int | 2 | frames | max missed frames |
| `GAP_CLOSING_FEATURE_PENALTIES` | Map | `{}` | — | — |
| `ALLOW_TRACK_SPLITTING` | bool | false | — | one cell → two tracks |
| `SPLITTING_MAX_DISTANCE` | double | 15.0 | physical | — |
| `SPLITTING_FEATURE_PENALTIES` | Map | `{}` | — | — |
| `ALLOW_TRACK_MERGING` | bool | false | — | two tracks fuse |
| `MERGING_MAX_DISTANCE` | double | 15.0 | physical | — |
| `MERGING_FEATURE_PENALTIES` | Map | `{}` | — | — |
| `ALTERNATIVE_LINKING_COST_FACTOR` | double | 1.05 | × | — |
| `CUTOFF_PERCENTILE` | double | 0.9 | 0..1 | — |
| `BLOCKING_VALUE` | double | Infinity | — | cost for forbidden cells |

Helper: `LAPUtils.getDefaultLAPSettingsMap()` returns a populated map.

### §4.3 SimpleSparseLAPTrackerFactory

KEY `"SIMPLE_SPARSE_LAP_TRACKER"`. Same backend, distance only, no splits/
merges, no feature penalties exposed. Smaller settings dict — same
`LINKING_MAX_DISTANCE`, `GAP_CLOSING_MAX_DISTANCE`, `MAX_FRAME_GAP`,
`ALTERNATIVE_LINKING_COST_FACTOR`, `BLOCKING_VALUE`, `CUTOFF_PERCENTILE`
keys.

### §4.4 KalmanTrackerFactory

`fiji.plugin.trackmate.tracking.kalman.KalmanTrackerFactory` · KEY
`"KALMAN_TRACKER"`.

Constant-velocity Kalman filter per track. State `[x, y, z, vx, vy, vz]`.
Each frame: predict → search within `KALMAN_SEARCH_RADIUS` → link by LAP
→ update state.

**Bootstrap.** No velocity at frame 0. Frames 0 → 1 link by
`LINKING_MAX_DISTANCE` (Brownian assumption); from frame 2 onward
`KALMAN_SEARCH_RADIUS` is used. Set `LINKING_MAX_DISTANCE` ≥ the largest
plausible single-frame step at rest.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `LINKING_MAX_DISTANCE` | double | 15.0 | initial radius (frames 0→1) |
| `KALMAN_SEARCH_RADIUS` | double | 15.0 | radius around predicted next position |
| `MAX_FRAME_GAP` | int | 2 | max missed frames |

No feature penalties, no splits/merges. Use when objects have inertia and
direction (motile cells, swimming organisms, flow). Predicted positions
disambiguate label swaps in crowds.

### §4.5 OverlapTrackerFactory

`fiji.plugin.trackmate.tracking.overlap.OverlapTrackerFactory` · KEY
`"OVERLAP_TRACKER"`.

Links 2D contours whose IoU between consecutive frames exceeds `MIN_IOU`.
For slow segmented objects whose displacement < their size — confluent
epithelia, organoids, mitochondria.

```
IoU = |A ∩ B| / |A ∪ B|         in [0, 1]
```

| Key | Type | Default | Meaning |
|---|---|---|---|
| `SCALE_FACTOR` | double | 1.0 | dilate contours by this before IoU; >1 helps fast movers |
| `MIN_IOU` | double | 0.3 | threshold for linking |
| `IOU_CALCULATION` | String | `"PRECISE"` | `"FAST"` (bbox approx) or `"PRECISE"` (true polygon) |

Requires contour-bearing detector output. LoG/DoG/Hessian point spots
have no contour and the tracker degenerates. No splits / merges /
gap-closing.

### §4.6 NearestNeighborTrackerFactory

`fiji.plugin.trackmate.tracking.kdtree.NearestNeighborTrackerFactory` ·
KEY `"NEAREST_NEIGHBOR_TRACKER"`.

Greedy: each spot in frame `t+1` linked to closest spot in `t` within
`LINKING_MAX_DISTANCE`. No global optimum, no gap-closing. Notorious for
label swaps when distances cross. Use only for sanity checks or extremely
sparse data.

Single key: `LINKING_MAX_DISTANCE` (15.0, physical).

### §4.7 TrackastraTrackerFactory — AI tracker

`fiji.plugin.trackmate.tracking.trackastra.TrackastraTrackerFactory` · KEY
`"TRACKASTRA_TRACKER"` · update site `TrackMate-Trackastra`.

Wraps the Trackastra transformer model (Gallusser et al. 2024, ECCV).
End-to-end appearance + motion association, robust to dense culture and
divisions.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `TRACKASTRA_PYTHON_FILEPATH` | String | — | python in env with `trackastra` |
| `TRACKASTRA_MODEL` | enum | `"general_2d"` | pretrained model |
| `TRACKASTRA_CUSTOM_MODEL_FILEPATH` | String | — | path to checkpoint |
| `TRACKASTRA_DEVICE` | enum | `"automatic"` | `cuda` / `cpu` / `mps` / `automatic` |
| `TRACKASTRA_USE_GPU` | bool | true | — |

No `LINKING_MAX_DISTANCE` — model-driven. Pair with Cellpose / StarDist
detector for full mask pipeline.

### §4.8 ManualTrackerFactory

`fiji.plugin.trackmate.tracking.manual.ManualTrackerFactory`. No parameters.
User-built links only.

---

## §5 All Features

Always-on (set by detector): `QUALITY`, `POSITION_X`, `POSITION_Y`,
`POSITION_Z`, `POSITION_T`, `FRAME`, `RADIUS`, `VISIBILITY`. Everything
else requires the relevant analyzer to be added — `settings.addAllAnalyzers()`
loads them all, or register selectively via `settings.addSpotAnalyzerFactory(...)`,
`settings.addEdgeAnalyzer(...)`, `settings.addTrackAnalyzer(...)` (see §7).

### Spot features

| Key | Default? | Provider | Units | Meaning |
|---|---|---|---|---|
| `QUALITY` | yes | detector | a.u. | detector confidence (LoG response peak, etc.) |
| `POSITION_X` / `_Y` / `_Z` | yes | detector | physical | sub-pixel centroid |
| `POSITION_T` | yes | detector | physical (s) | time |
| `FRAME` | yes | detector | int | frame index, 0-based |
| `RADIUS` | yes | detector | physical | detection radius |
| `VISIBILITY` | yes | detector | 0/1 | filtered out vs kept |
| `MEAN_INTENSITY_CHn` | no | `SpotIntensityMultiCAnalyzer` | image units | mean inside spot, channel n (1-based) |
| `MEDIAN_INTENSITY_CHn` | no | same | image units | — |
| `MIN_INTENSITY_CHn` | no | same | image units | — |
| `MAX_INTENSITY_CHn` | no | same | image units | — |
| `TOTAL_INTENSITY_CHn` | no | same | image units · px | sum of pixel values |
| `STD_INTENSITY_CHn` | no | same | image units | std-dev |
| `CONTRAST_CHn` | no | `SpotContrastAndSNRAnalyzer` | unitless | (mean_in − mean_ring) / (mean_in + mean_ring); ring r..2r |
| `SNR_CHn` | no | same | unitless | contrast / std_ring |
| `AREA` | when contour | `Spot2DMorphologyAnalyzer` | physical² | 2D area |
| `PERIMETER` | when contour | same | physical | contour length |
| `CIRCULARITY` | when contour | same | unitless [0,1] | 4πA / P² |
| `ELLIPSE_X0` / `_Y0` | when contour | `SpotFitEllipseAnalyzer` | physical | best-fit ellipse centre |
| `ELLIPSE_MAJOR` / `_MINOR` | when contour | same | physical | semi-axes |
| `ELLIPSE_THETA` | when contour | same | rad | major-axis angle |
| `ELLIPSE_ASPECTRATIO` | when contour | same | unitless ≥1 | major / minor |
| `SHAPE_INDEX` | when contour | `SpotShapeAnalyzer` | unitless | P / sqrt(A); ~3.545 for circle |
| `SOLIDITY` | when contour | same | unitless [0,1] | A / convex-hull area |
| `CONVEXITY` | when contour | same | unitless [0,1] | convex-perimeter / perimeter |
| `ELLIPSOIDFIT_SEMIAXISLENGTH_A/B/C` | 3D contour | ellipsoid-fit | physical | 3D semi-axes |
| `ELLIPSOIDFIT_AXISPHI_A/B/C` | 3D contour | same | rad | azimuth |
| `ELLIPSOIDFIT_AXISTHETA_A/B/C` | 3D contour | same | rad | polar angle |
| `NUM_NEIGHBORS`, `MEAN_NEIGHBOR_DIST` | optional | `NearestNeighborSpotAnalyzer` | physical | within-frame density |

Channel-indexed features are duplicated per channel (`_CH1`, `_CH2`, …).
Morphology / ellipse / shape features fail on point spots — they require
a contour. 3D ellipsoid is computed only when the spot is true 3D and the
analyzer is registered.

### Edge features

One per directed edge between two consecutive spots.

| Key | Provider | Units | Meaning |
|---|---|---|---|
| `SPOT_SOURCE_ID` / `SPOT_TARGET_ID` | core | int | endpoints |
| `LINK_COST` | tracker | depends | tracker-assigned cost (e.g. squared distance for LAP) |
| `EDGE_TIME` | `EdgeTimeLocationAnalyzer` | physical (s) | mean time of source/target |
| `EDGE_X_LOCATION` / `_Y_` / `_Z_` | same | physical | edge midpoint |
| `SPEED` | `EdgeVelocityAnalyzer` | physical / s | distance / Δt |
| `DISPLACEMENT` | same | physical | source → target |
| `DIRECTIONAL_CHANGE_RATE` | `DirectionalChangeAnalyzer` | rad / s | angle change vs previous edge / Δt |

### Track features

One per track. Always require their analyzer.

| Key | Provider | Units | Meaning |
|---|---|---|---|
| `TRACK_INDEX` | `TrackIndexAnalyzer` | int | UI display index |
| `TRACK_ID` | same | int | stable internal ID |
| `NUMBER_SPOTS` | `TrackBranchingAnalyzer` | int | spot count |
| `NUMBER_GAPS` | same | int | missed-detection bridges |
| `NUMBER_SPLITS` | same | int | split events |
| `NUMBER_MERGES` | same | int | merge events |
| `NUMBER_COMPLEX` | same | int | ≥3-way junctions |
| `LONGEST_GAP` | same | frames | max gap length |
| `TRACK_DURATION` | `TrackDurationAnalyzer` | physical (s) | stop − start |
| `TRACK_START` / `TRACK_STOP` | same | physical (s) | first/last frame time |
| `TRACK_DISPLACEMENT` | same | physical | net start → end |
| `TRACK_X_LOCATION` / `_Y_` / `_Z_` | `TrackLocationAnalyzer` | physical | mean position |
| `TRACK_MEAN_QUALITY` | `TrackSpotQualityFeatureAnalyzer` | a.u. | mean of spot quality |
| `TRACK_MEAN_SPEED` | `TrackSpeedStatisticsAnalyzer` | physical / s | mean of edge speeds |
| `TRACK_MAX_SPEED` / `_MIN_` / `_MEDIAN_` / `_STD_` | same | physical / s | edge-speed stats |
| `TOTAL_DISTANCE_TRAVELED` | `TrackMotilityAnalyzer` | physical | sum of edge displacements |
| `MAX_DISTANCE_TRAVELED` | same | physical | max distance from origin reached |
| `CONFINEMENT_RATIO` | same | unitless [0,1] | net / total displacement |
| `MEAN_STRAIGHT_LINE_SPEED` | same | physical / s | net displacement / duration |
| `LINEARITY_OF_FORWARD_PROGRESSION` | same | unitless | mean speed × confinement ratio |
| `MEAN_DIRECTIONAL_CHANGE_RATE` | same | rad / s | mean over edges |

**MSD.** Mean Squared Displacement is not stored per track by default.
Plot in the Grapher view (per-track MSD vs Δt) or compute via the
`TrackMate-MSD` extension (adds the α-exponent and curves).

---

## §6 Filters & Display

### FeatureFilter API

```java
import fiji.plugin.trackmate.features.FeatureFilter;

// FeatureFilter(key, value, isAbove)
// isAbove=true  → keep value ≥ threshold
// isAbove=false → keep value ≤ threshold
settings.addSpotFilter(new FeatureFilter("MEAN_INTENSITY_CH1", 200.0, true));
settings.addSpotFilter(new FeatureFilter("AREA",              1500.0, false));
settings.addTrackFilter(new FeatureFilter("NUMBER_SPOTS",        5.0, true));
settings.addTrackFilter(new FeatureFilter("CONFINEMENT_RATIO",   0.4, true));
```

Filter pipeline order:

1. **Initial spot filter** — single threshold on `QUALITY` only, applied
   immediately after detection. Set `settings.initialSpotFilterValue`.
   Coarse, fast cull before any analyzer runs.
2. **Spot feature filters** — `settings.spotFilters`. Applied after spot
   analyzers. Spots failing any filter get `VISIBILITY=0` and are excluded
   from tracking.
3. **Track filters** — `settings.trackFilters`. Applied after tracking +
   track analyzers. Whole tracks pass or fail.

The list is ANDed. Apply: `trackmate.execSpotFiltering(false)`,
`trackmate.execTrackFiltering(false)`.

### Display

**HyperStackDisplayer** —
`fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer`.
Overlays spots and edges on the active `ImagePlus`. Refreshes on Model
events.

```java
SelectionModel selection = new SelectionModel(model);
DisplaySettings ds = DisplaySettingsIO.readUserDefault();
HyperStackDisplayer displayer = new HyperStackDisplayer(model, selection, imp, ds);
displayer.render();
displayer.refresh();
```

`DisplaySettings` controls colormap, line width, spot fill, track-display
mode (`LOCAL_BACKWARD`, `LOCAL_FORWARD`, `WHOLE_TRACK`, …), draw spots/
labels/tracks toggles. Persistence via `DisplaySettingsIO`.

**TrackScheme** — `fiji.plugin.trackmate.visualization.trackscheme.TrackScheme`.
JGraphX DAG: time on Y, lineage on X. Splits/merges show as bifurcations.
Click selects (synced with HyperStackDisplayer through `SelectionModel`).
Edit lineage by dragging edges, deleting nodes.

```java
TrackScheme ts = new TrackScheme(model, selection, ds);
ts.render();
```

**SpotDisplayer3D** —
`fiji.plugin.trackmate.visualization.threedviewer.SpotDisplayer3D`.
Hooks ImageJ 3D Viewer (Java3D); renders spots as spheres, tracks as
polylines. Requires `3D_Viewer` plugin loaded. Does not work with SciView
(use `SciView-TrackMate` for that).

### Color-by-feature

```java
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettings.TrackMateObject;
ds.setSpotColorBy(TrackMateObject.SPOTS, "MEAN_INTENSITY_CH1");
ds.setTrackColorBy(TrackMateObject.TRACKS, "TRACK_DISPLACEMENT");
```

Legacy color generators still present:
`PerTrackFeatureColorGenerator(model, "TRACK_MEAN_SPEED")`,
`PerEdgeFeatureColorGenerator(model, "SPEED")`,
`SpotColorGenerator(model, "MEAN_INTENSITY_CH1")`.

### Useful actions

| Action | Class | Purpose |
|---|---|---|
| Export stats to IJ | `ExportStatsToIJAction` | push spot/edge/track tables to Results |
| Capture overlay | `CaptureOverlayAction` | RGB stack with overlay baked in |
| Color by feature | `ColorByFeatureAction` | drives `DisplaySettings` |
| Plot N spots vs time | `PlotNSpotsVsTimeAction` | sanity-check |
| Track length histogram | `TrackBranchingHistogramAction` | sanity-check |
| Export tracks to XML | `ExportTracksToXML` | simple-tracks XML output |

---

## §7 Scripting API

### §7.1 Complete Jython Template

LoG + Sparse LAP, all analyzers, quality + duration filters, display.

```python
import sys
from ij import IJ

from fiji.plugin.trackmate import Model, Settings, TrackMate, SelectionModel, Logger
from fiji.plugin.trackmate.detection import LogDetectorFactory
from fiji.plugin.trackmate.tracking.jaqaman import SparseLAPTrackerFactory
from fiji.plugin.trackmate.gui.displaysettings import DisplaySettingsIO
from fiji.plugin.trackmate.gui.displaysettings.DisplaySettings import TrackMateObject
from fiji.plugin.trackmate.features.track import TrackIndexAnalyzer
import fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer as HyperStackDisplayer
import fiji.plugin.trackmate.features.FeatureFilter as FeatureFilter

# Avoid UTF-8 errors when TrackMate logs through Fiji Jython.
reload(sys); sys.setdefaultencoding('utf-8')

imp = IJ.getImage()                          # or IJ.openImage('/path/to/movie.tif'); imp.show()

model = Model()
model.setLogger(Logger.IJ_LOGGER)

settings = Settings(imp)

# --- Detector ---
settings.detectorFactory = LogDetectorFactory()
settings.detectorSettings = {
    'DO_SUBPIXEL_LOCALIZATION' : True,
    'RADIUS'                   : 2.5,        # CALIBRATED units (μm if calibrated)
    'TARGET_CHANNEL'           : 1,
    'THRESHOLD'                : 0.0,
    'DO_MEDIAN_FILTERING'      : False,
}

# --- Spot filter ---
settings.addSpotFilter(FeatureFilter('QUALITY', 30.0, True))   # keep QUALITY > 30

# --- Tracker ---
settings.trackerFactory = SparseLAPTrackerFactory()
settings.trackerSettings = settings.trackerFactory.getDefaultSettings()
settings.trackerSettings['LINKING_MAX_DISTANCE']     = 15.0
settings.trackerSettings['GAP_CLOSING_MAX_DISTANCE'] = 15.0
settings.trackerSettings['MAX_FRAME_GAP']            = 2
settings.trackerSettings['ALLOW_TRACK_SPLITTING']    = True
settings.trackerSettings['ALLOW_TRACK_MERGING']      = True

# --- Analyzers (REQUIRED for any non-default feature filter) ---
# Without addAllAnalyzers(), filters that reference uncomputed features
# silently drop everything.
settings.addAllAnalyzers()

# --- Track filter ---
settings.addTrackFilter(FeatureFilter('TRACK_DURATION', 10.0, True))

# --- Run ---
trackmate = TrackMate(model, settings)
if not trackmate.checkInput(): sys.exit(str(trackmate.getErrorMessage()))
if not trackmate.process():    sys.exit(str(trackmate.getErrorMessage()))

# --- Display ---
sm = SelectionModel(model)
ds = DisplaySettingsIO.readUserDefault()
ds.setTrackColorBy(TrackMateObject.TRACKS, TrackIndexAnalyzer.TRACK_INDEX)
ds.setSpotColorBy(TrackMateObject.TRACKS,  TrackIndexAnalyzer.TRACK_INDEX)
HyperStackDisplayer(model, sm, imp, ds).render()

print 'Spots:  %d' % model.getSpots().getNSpots(True)
print 'Tracks: %d' % model.getTrackModel().nTracks(True)
```

### §7.2 Complete Groovy Template (Cellpose + LAP)

Groovy auto-coerces strings → enums; settings dict accepts plain strings.

```groovy
import fiji.plugin.trackmate.Model
import fiji.plugin.trackmate.Settings
import fiji.plugin.trackmate.TrackMate
import fiji.plugin.trackmate.SelectionModel
import fiji.plugin.trackmate.Logger
import fiji.plugin.trackmate.cellpose.CellposeDetectorFactory
import fiji.plugin.trackmate.tracking.jaqaman.SparseLAPTrackerFactory
import fiji.plugin.trackmate.features.FeatureFilter
import fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettingsIO
import ij.IJ

def imp = IJ.getImage()
def model = new Model()
model.setLogger(Logger.IJ_LOGGER)

def settings = new Settings(imp)

settings.detectorFactory = new CellposeDetectorFactory()
settings.detectorSettings = settings.detectorFactory.getDefaultSettings()
settings.detectorSettings['CELLPOSE_MODEL']           = 'cyto3'
settings.detectorSettings['PRETRAINED_OR_CUSTOM']     = 'CELLPOSE_MODEL'
settings.detectorSettings['TARGET_CHANNEL']           = 0           // 0 = grayscale composite
settings.detectorSettings['OPTIONAL_CHANNEL_2']       = 0
settings.detectorSettings['CELL_DIAMETER']            = 30.0
settings.detectorSettings['USE_GPU']                  = true
settings.detectorSettings['SIMPLIFY_CONTOURS']        = true

settings.trackerFactory  = new SparseLAPTrackerFactory()
settings.trackerSettings = settings.trackerFactory.getDefaultSettings()
settings.trackerSettings['LINKING_MAX_DISTANCE']     = 25.0
settings.trackerSettings['GAP_CLOSING_MAX_DISTANCE'] = 25.0
settings.trackerSettings['MAX_FRAME_GAP']            = 2

settings.addAllAnalyzers()
settings.addTrackFilter(new FeatureFilter('TRACK_DURATION', 5.0, true))

def trackmate = new TrackMate(model, settings)
trackmate.checkInput() && trackmate.process()

def sm = new SelectionModel(model)
def ds = DisplaySettingsIO.readUserDefault()
new HyperStackDisplayer(model, sm, imp, ds).render()

println "Spots:  ${model.getSpots().getNSpots(true)}"
println "Tracks: ${model.getTrackModel().nTracks(true)}"
```

### §7.3 Step-by-Step Execution

`trackmate.process()` runs the full pipeline. Step manually when you want
to recompute features without redetecting, or swap trackers on an existing
spot collection:

```python
trackmate.checkInput()                  # validate Settings
trackmate.execDetection()               # detector → SpotCollection
trackmate.execInitialSpotFiltering()    # apply settings.initialSpotFilterValue
trackmate.computeSpotFeatures(True)     # all spot analyzers
trackmate.execSpotFiltering(True)       # apply settings.getSpotFilters()
trackmate.execTracking()                # tracker → graph of edges
trackmate.computeEdgeFeatures(True)     # per-edge features
trackmate.computeTrackFeatures(True)    # per-track features
trackmate.execTrackFiltering(True)      # apply settings.getTrackFilters()
```

The boolean is "do log progress". Use cases:
- **Re-track without redetecting**: skip `execDetection` and
  `computeSpotFeatures`.
- **Add/remove spot filters**: re-run `execSpotFiltering` only.
- **Add an analyzer after the fact**: register on `settings`, then call the
  matching `compute*Features(True)`.
- **Kalman tracking on saved spots**: load XML, swap `trackerFactory`,
  call `execTracking` + `computeEdge/TrackFeatures` + `execTrackFiltering`.

### §7.4 Swap Detector / Tracker

```groovy
// LoG
import fiji.plugin.trackmate.detection.LogDetectorFactory
settings.detectorFactory = new LogDetectorFactory()
settings.detectorSettings = ['DO_SUBPIXEL_LOCALIZATION':true, 'RADIUS':2.5d,
                             'TARGET_CHANNEL':1, 'THRESHOLD':0.0d, 'DO_MEDIAN_FILTERING':false]

// DoG
import fiji.plugin.trackmate.detection.DogDetectorFactory
settings.detectorFactory = new DogDetectorFactory()
settings.detectorSettings = ['DO_SUBPIXEL_LOCALIZATION':true, 'RADIUS':2.0d,
                             'TARGET_CHANNEL':1, 'THRESHOLD':0.0d, 'DO_MEDIAN_FILTERING':false]

// Hessian
import fiji.plugin.trackmate.detection.HessianDetectorFactory
settings.detectorFactory = new HessianDetectorFactory()
settings.detectorSettings = ['DO_SUBPIXEL_LOCALIZATION':true, 'RADIUS':2.0d, 'RADIUS_Z':2.0d,
                             'TARGET_CHANNEL':1, 'THRESHOLD':0.0d, 'NORMALIZE':true]

// StarDist (built-in)
import fiji.plugin.trackmate.stardist.StarDistDetectorFactory
settings.detectorFactory = new StarDistDetectorFactory()
settings.detectorSettings = ['TARGET_CHANNEL':1]

// StarDist (custom)
import fiji.plugin.trackmate.stardist.StarDistCustomDetectorFactory
settings.detectorFactory = new StarDistCustomDetectorFactory()
settings.detectorSettings = ['TARGET_CHANNEL':1, 'MODEL_FILEPATH':'/path/to/model.zip',
                             'SCORE_THRESHOLD':0.5d, 'OVERLAP_THRESHOLD':0.4d]

// Threshold / Mask / LabelImage — see §3.2 examples

// Kalman
import fiji.plugin.trackmate.tracking.kalman.KalmanTrackerFactory
settings.trackerFactory = new KalmanTrackerFactory()
settings.trackerSettings = settings.trackerFactory.getDefaultSettings()
settings.trackerSettings['KALMAN_SEARCH_RADIUS'] = 20.0d

// Overlap
import fiji.plugin.trackmate.tracking.overlap.OverlapTrackerFactory
settings.trackerFactory = new OverlapTrackerFactory()
settings.trackerSettings = settings.trackerFactory.getDefaultSettings()
settings.trackerSettings['MIN_IOU']         = 0.3d
settings.trackerSettings['IOU_CALCULATION'] = 'PRECISE'
```

### §7.5 LAP Feature Penalties

Higher weight → that feature must match more closely. 0 = ignored,
1 = moderate, ~5 = dominant.

```python
settings.trackerSettings['LINKING_FEATURE_PENALTIES']     = {'MEAN_INTENSITY_CH1': 1.0}
settings.trackerSettings['GAP_CLOSING_FEATURE_PENALTIES'] = {'MEAN_INTENSITY_CH1': 2.0,
                                                             'AREA':                1.0}
settings.trackerSettings['SPLITTING_FEATURE_PENALTIES']   = {'MEAN_INTENSITY_CH1': 1.0}
settings.trackerSettings['MERGING_FEATURE_PENALTIES']     = {'MEAN_INTENSITY_CH1': 1.0}
```

Penalty contributes `p = 3 * W * |f1-f2| / (f1+f2)` per feature; squared
distance × `(1 + sum(p))^2` is the final cost (§4.2).

---

## §8 XML I/O & Model Editing

### Round-trip via TmXmlReader / TmXmlWriter

```python
from fiji.plugin.trackmate.io import TmXmlReader, TmXmlWriter
from fiji.plugin.trackmate.util import TMUtils
from java.io import File

# WRITE
saveFile = TMUtils.proposeTrackMateSaveFile(settings, model.getLogger())
writer = TmXmlWriter(saveFile, model.getLogger())
writer.appendLog(model.getLogger().toString())
writer.appendModel(trackmate.getModel())
writer.appendSettings(trackmate.getSettings())
writer.appendDisplaySettings(ds)         # optional
writer.appendGUIState('ConfigureViews')  # optional, GUI reopens at this step
writer.writeToFile()

# READ
reader = TmXmlReader(File('/path/to/session.xml'))
if not reader.isReadingOk(): sys.exit(reader.getErrorMessage())
model    = reader.getModel()
imp      = reader.readImage()            # opens original image (must be ImageJ TIFF)
settings = reader.readSettings(imp)      # all detector/tracker/filter params
ds       = reader.getDisplaySettings()
log      = reader.getLog()
```

### Re-track with a different tracker

```python
reader = TmXmlReader(File('/path/to/session.xml'))
model    = reader.getModel()
imp      = reader.readImage()
settings = reader.readSettings(imp)

settings.trackerFactory  = KalmanTrackerFactory()
settings.trackerSettings = settings.trackerFactory.getDefaultSettings()
settings.trackerSettings['KALMAN_SEARCH_RADIUS'] = 20.0

trackmate = TrackMate(model, settings)
trackmate.execTracking()
trackmate.computeEdgeFeatures(False)
trackmate.computeTrackFeatures(False)
trackmate.execTrackFiltering(False)
```

### Just export CSV from a saved XML

```python
from fiji.plugin.trackmate.io import CSVExporter
reader = TmXmlReader(File('/path/to/session.xml'))
CSVExporter.exportSpots('/path/to/spots.csv', reader.getModel(), True)  # only_visible
```

### Programmatic model editing

```python
from fiji.plugin.trackmate import Model, Spot, Logger

model = Model()
model.setLogger(Logger.IJ_LOGGER)
model.beginUpdate()
try:
    s0 = Spot(10.0, 20.0, 0.0, 2.5, 100.0)   # Spot(x, y, z, radius, quality) calibrated
    s1 = Spot(11.5, 21.0, 0.0, 2.5,  98.0)
    s2 = Spot(13.0, 22.0, 0.0, 2.5,  95.0)
    model.addSpotTo(s0, 0); model.addSpotTo(s1, 1); model.addSpotTo(s2, 2)
    model.addEdge(s0, s1, -1.0)              # cost = -1 (mandatory link)
    model.addEdge(s1, s2, -1.0)
    s2.putFeature('RADIUS', 3.0)             # mutate
    # model.removeSpot(s_old); model.removeEdge(s_a, s_b)
finally:
    model.endUpdate()
```

`beginUpdate / endUpdate` ensure listeners and feature recomputation fire
once per batch, not per edit.

---

## §9 Export Options

```python
from fiji.plugin.trackmate.io import CSVExporter
from fiji.plugin.trackmate.visualization.table import TrackTableView
from fiji.plugin.trackmate.action import ExportTracksToXML
from java.io import File

# Headless-safe spots CSV (one row per spot)
CSVExporter.exportSpots('/out/spots.csv', model, True)   # True = visible only

# GUI-table CSVs (require Display, work in normal Fiji JVM)
TrackTableView.createSpotTable(model, ds).exportToCsv(File('/out/spots-table.csv'))
TrackTableView.createEdgeTable(model, ds).exportToCsv(File('/out/edges.csv'))
TrackTableView.createTrackTable(model, ds).exportToCsv(File('/out/tracks.csv'))

# Simplified tracks XML (track-id → list of (frame, x, y, z))
ExportTracksToXML.export(model, settings, File('/out/simple-tracks.xml'))
```

### MaMuT / Mastodon / label image

```groovy
import fiji.plugin.trackmate.action.ExportTracksToXML
import fiji.plugin.trackmate.action.LabelImgExporter
import fiji.plugin.trackmate.action.LabelImgExporter.LabelIdPainting
import ij.IJ

// MaMuT-compatible simple tracks XML
ExportTracksToXML.export(model, settings, new File('/out/mamut-tracks.xml'))

// Label image stack — one label per spot, frames preserved
def labelImp = LabelImgExporter.createLabelImagePlus(
    trackmate, false, false, LabelIdPainting.LABEL_IS_TRACK_ID)
labelImp.show()
IJ.saveAsTiff(labelImp, '/out/labels.tif')
```

Mastodon import path: save TrackMate XML, open Mastodon, *File > Import >
TrackMate XML*. Mastodon converts to its own project format
(`.mastodon` folder); no scripted bridge in TrackMate itself.

`TrackMate-CSVImporter` (update site) reads CSV detections / tracks back
into a TrackMate Model.

### ROI export workaround

TrackMate stores spots as `Spot` objects, not ROIs. To push to RoiManager
keyed by frame — handles both `OvalRoi` (LoG/DoG/Hessian point spots) and
`PolygonRoi` (SpotRoi from Cellpose / StarDist contour spots):

```groovy
import fiji.plugin.trackmate.SpotRoi
import ij.gui.OvalRoi
import ij.gui.PolygonRoi
import ij.gui.Roi
import ij.plugin.frame.RoiManager

def rm = RoiManager.getRoiManager(); rm.reset()
def cal = imp.getCalibration()

model.getSpots().iterable(true).each { spot ->
    def frame = spot.getFeature('FRAME').intValue() + 1   // RoiManager is 1-based
    def shape = spot.getRoi()                              // SpotRoi when contour-bearing
    def roi
    if (shape instanceof SpotRoi) {
        def xs = shape.getXcoords(); def ys = shape.getYcoords()
        def xPx = new float[xs.length]; def yPx = new float[ys.length]
        for (int i = 0; i < xs.length; i++) {
            xPx[i] = (float)((xs[i] + spot.getDoublePosition(0)) / cal.pixelWidth)
            yPx[i] = (float)((ys[i] + spot.getDoublePosition(1)) / cal.pixelHeight)
        }
        roi = new PolygonRoi(xPx, yPx, Roi.POLYGON)
    } else {
        def r  = spot.getFeature('RADIUS') / cal.pixelWidth
        def cx = spot.getDoublePosition(0) / cal.pixelWidth
        def cy = spot.getDoublePosition(1) / cal.pixelHeight
        roi = new OvalRoi(cx - r, cy - r, 2*r, 2*r)
    }
    roi.setPosition(frame)
    rm.addRoi(roi)
}
println "Pushed ${rm.getCount()} ROIs"
```

---

## §10 Batch & Headless

### Batch over a folder (Groovy)

```groovy
import fiji.plugin.trackmate.*
import fiji.plugin.trackmate.detection.LogDetectorFactory
import fiji.plugin.trackmate.tracking.jaqaman.SparseLAPTrackerFactory
import fiji.plugin.trackmate.io.TmXmlWriter
import fiji.plugin.trackmate.io.CSVExporter
import fiji.plugin.trackmate.action.ExportTracksToXML
import ij.IJ

def inDir  = new File('/data/movies')
def outDir = new File('/data/movies/AI_Exports'); outDir.mkdirs()

inDir.listFiles({ f -> f.name.endsWith('.tif') } as FileFilter).each { f ->
    def imp = IJ.openImage(f.absolutePath)
    def model = new Model(); model.setLogger(Logger.VOID_LOGGER)
    def s = new Settings(imp)
    s.detectorFactory = new LogDetectorFactory()
    s.detectorSettings = s.detectorFactory.getDefaultSettings()
    s.detectorSettings['RADIUS'] = 2.5
    s.trackerFactory  = new SparseLAPTrackerFactory()
    s.trackerSettings = s.trackerFactory.getDefaultSettings()
    s.addAllAnalyzers()

    def tm = new TrackMate(model, s)
    if (!tm.checkInput() || !tm.process()) {
        println "FAIL ${f.name}: ${tm.getErrorMessage()}"; imp.close(); return
    }
    def base = f.name.replace('.tif', '')
    new TmXmlWriter(new File(outDir, "${base}.xml")).with {
        appendModel(model); appendSettings(s); writeToFile() }
    CSVExporter.exportSpots(new File(outDir, "${base}-spots.csv").absolutePath, model, true)
    ExportTracksToXML.export(model, s, new File(outDir, "${base}-tracks.xml"))
    println "OK ${f.name} -> ${model.getTrackModel().nTracks(true)} tracks"
    imp.close()
    System.gc()
}
```

### Memory management for long timelapses

- Increase Fiji heap: *Edit > Options > Memory & Threads* (≥ 16 GB for
  thousand-frame movies); or `-Xmx32g` in `Fiji.app/Fiji.cfg`.
- **Trim non-visible data first**: `imp.setDimensions(C, Z, T)` to drop
  unused channels/slices; *Image > Adjust > Tools > Trim non-visible data*
  permanently strips them.
- For >5 GB movies use Bio-Formats virtual stacks
  (`run("Bio-Formats Importer", "open=[/path] use_virtual_stack")`).
  Intensity analyzers slow down on virtual stacks.
- `System.gc()` between files in batch loops.

### True headless mode

```bash
ImageJ-linux64 --headless --console --run track.groovy
```

`HyperStackDisplayer.render()` and `TrackTableView.createSpotTable()`
require a GUI thread → wrap with `if (!GraphicsEnvironment.isHeadless()) {...}`.
Bio-Formats import works headless. `CSVExporter.exportSpots` and
`TmXmlWriter` are headless-safe.

### TrackMate-Batcher

*Plugins > Tracking > TrackMate > Batcher*. Update site `TrackMate-Helper`.
Configure on one image, apply to folder. Drop a folder + a saved TrackMate
XML "recipe" (settings template); it loops every file with the same
parameters and writes XML+CSV+overlay-capture per movie.

### TrackMate-Helper (parameter sweep)

Update sites `TrackMate-Helper` + `CellTrackingChallenge`. Sweep
detector/tracker combinations against ground truth. Plugin path:
*Plugins > Tracking > TrackMate Helper*. See §12 for ground-truth format
and metric meaning.

---

## §11 Diagnostic Flow & Failure Modes

When tracking output is bad, run this order. Don't skip — every later
step depends on the earlier one being correct.

1. **Verify Image > Properties.** Pixel size, time interval, slices vs
   frames. Wrong calibration silently corrupts every distance-based
   parameter.
2. **Single-frame preview.** Set frames = 1, run detection, capture
   screenshot, count spots manually. If frame 1 is wrong, every later
   frame is wrong.
3. **Quality histogram.** Iterate `model.getSpots().iterable(false)` and
   tally `QUALITY` into a histogram. Look for a bimodal distribution.
   The valley is the threshold.
4. **Cap quality threshold above noise mode.** Re-run detection with that
   threshold. Spot count should drop into expected biological range.
5. **Tracker dry-run with loose params.** `LINKING_MAX_DISTANCE = 2× expected
   displacement`, `MAX_FRAME_GAP = 2`, no splitting/merging. Confirm
   tracks form. Tighten only after this works.
6. **Inspect TrackScheme.** Identity swaps appear as crossing tracks;
   fragmentation as orphaned tracklets; spurious splits as false
   Y-junctions.
7. **Color-by-feature.** Color spots by `MEAN_INTENSITY_CH1` to spot
   bleaching. Color tracks by `TRACK_DURATION` to spot fragmentation.
   Color by `TRACK_INDEX` to spot ID swaps visually.

### Detection failures

| Symptom | Likely cause | Diagnostic | Fix |
|---|---|---|---|
| 0 spots | Quality threshold above all spot qualities | Re-run with `THRESHOLD=0`, tally `QUALITY` | Threshold near bimodal valley |
| 0 spots | Wrong `TARGET_CHANNEL` (1-indexed for built-ins; 0 valid for CLI detectors) | `imp.getNChannels()` vs `detectorSettings['TARGET_CHANNEL']` | Match channel |
| 0 spots | Z and T axes swapped | *Image > Properties* shows slices > 1 when it should be the reverse | *Image > Hyperstacks > Re-order Hyperstacks* |
| 0 spots | `RADIUS` (LoG/DoG) wildly off | Compare RADIUS in physical units to a known cell radius | Set RADIUS = 0.5–1.0× true radius (μm) |
| Garbage at edges | `DO_MEDIAN_FILTERING=false` on noisy images | Toggle on, re-run preview | Enable; or pre-process `Process > Filters > Median (3D)` |
| Edge-only false positives | Vignetting / uneven illumination | Project flatfield image, look for radial gradient | `BaSiC` or `Process > Subtract Background...` rolling-ball before TrackMate |
| Way too many tiny spots | Threshold below noise mode | Quality histogram shows continuous low-quality cloud | Threshold above noise mode; enable `DO_SUBPIXEL_LOCALIZATION` |
| Oversegmentation (1 cell → many spots) | Cellpose `CELL_DIAMETER` too small / StarDist `OVERLAP_THRESHOLD` too low / LoG `RADIUS` too small | Preview on one frame | Increase diameter/RADIUS; raise `OVERLAP_THRESHOLD` toward 0.5 |
| Undersegmentation (multi-cell merge) | Diameter too big / `SCORE_THRESHOLD` too high / watershed dam missing | Preview shows blobs spanning two nuclei | Decrease diameter; lower `SCORE_THRESHOLD`; insert `Process > Binary > Watershed` before label-image detector |

### Tracking failures

| Symptom | Likely cause | Diagnostic | Fix |
|---|---|---|---|
| Tracks drop off mid-timelapse | Photobleaching pushes intensity below threshold | Color spots by `MEAN_INTENSITY_CH1`; intensity decays | Lower quality threshold; LAP `LINKING_FEATURE_PENALTIES` `MEAN_INTENSITY_CH1=1.0` |
| Tracks fragment | `MAX_FRAME_GAP` too small / `GAP_CLOSING_MAX_DISTANCE` too small | TrackScheme: many short tracklets in same XY neighbourhood | Raise `MAX_FRAME_GAP` to 2–3; raise `GAP_CLOSING_MAX_DISTANCE` to ~`LINKING_MAX_DISTANCE` |
| Identity swaps under crowding | `LINKING_MAX_DISTANCE` > inter-cell spacing | Plot nearest-neighbour distance; if NN < `LINKING_MAX_DISTANCE`, swaps inevitable | Reduce `LINKING_MAX_DISTANCE`; add intensity penalty; switch to Kalman if directed; use Overlap if segmented |
| False splits/merges everywhere | `ALLOW_TRACK_SPLITTING/MERGING` enabled when biology forbids | TrackScheme shows spurious Y-junctions | Disable; raise `*_MAX_DISTANCE` cost factor (`ALTERNATIVE_LINKING_COST_FACTOR`) |
| Out of memory during tracking | LAP n² cost matrix; >50 k spots/frame breaks | `Edit > Options > Memory` heap exhaust | Pre-filter spots aggressively; downsample temporally; chunk + stitch; switch to Overlap tracker for label-image inputs |

### Engine / detector breakage

| Symptom | Cause | Fix |
|---|---|---|
| Cellpose hangs / errors | Wrong `CELLPOSE_PYTHON_FILEPATH` / env not activated / GPU OOM | Run Cellpose CLI manually in conda shell; `nvidia-smi`; set `USE_GPU=false`; reduce `CELL_DIAMETER` |
| StarDist returns empty | TF backend not initialized; model path wrong; image is float | ImageJ Log shows TF init error; convert to 16-bit; install CSBDeep |
| Trackastra errors | Python env missing `trackastra` | TrackMate log shows ModuleNotFoundError; `pip install trackastra` |

### Modality-specific gotchas

- **Calcium imaging / low SNR.** ΔF/F or rolling-median subtract before
  detection. LoG with small radius and very low `THRESHOLD`, then
  aggressively filter post-hoc on `MEAN_INTENSITY_CH1`, `CONTRAST_CH1`,
  `SNR_CH1`. Pre-detection median (`Process > Filters > Median (3D)`,
  radius 1) helps. Alternative: StarDist on a denoised version then
  import as label-image detector.
- **Live-cell motion blur.** Shorten frame interval if acquisition allows.
  Otherwise raise `LINKING_MAX_DISTANCE` to ~1.5× expected per-frame
  displacement and switch to Kalman (`KALMAN_SEARCH_RADIUS`).
  Deconvolution preprocessing reduces blur amplitude.
- **3D tracking.** Classic LoG/DoG assume isotropic radius — anisotropic Z
  needs either rescaling to isotropic voxels (*Image > Scale...*) or
  using the Hessian detector with `RADIUS_Z`. Verify Z calibration in
  *Properties...* — wrong Z spacing produces correct-looking but
  quantitatively wrong tracks.
- **Moving fluorescent cells.** Never use SIFT-based stabilization —
  features lock onto cells and zero out their motion. Use *Plugins >
  Registration > Correct 3D Drift* on a cropped non-cell region.

---

## §12 Validation Against Ground Truth

### CTC (Cell Tracking Challenge) Metrics

| Metric | Definition | Range | Punishes | Use for |
|---|---|---|---|---|
| **SEG** | Mean Jaccard (IoU) over matched objects, IoU > 0.5 | [0, 1] | bad pixel-level segmentation | comparing detectors |
| **DET** | Detection-only AOGM; node add/delete/wrong-class normalized by AOGM-of-empty | [0, 1] | missed/spurious detections | tracker-agnostic detection scoring |
| **TRA** | Full AOGM (Acyclic Oriented Graph Matching); node ops + edge ops normalized | [0, 1] | wrong topology, ID swaps, missed divisions | overall tracking quality |
| **CT** | Complete Tracks — fraction of GT tracks reconstructed end-to-end | [0, 1] | fragmentation | long-track applications |
| **TF** | Track Fraction — average fraction of each GT track recovered | [0, 1] | partial recovery | bleaching-prone data |
| **BC** | Branching Correctness at depth i — division detection accuracy | [0, 1] | missed/spurious divisions | mitosis-heavy data |
| **MOTA** | CLEAR-MOT: 1 − (FN + FP + IDSW) / GT | (−∞, 1] | FN + FP + ID switches | frame-level accuracy |
| **MOTP** | Mean IoU (or distance) of matched detections | [0, 1] | localization error | sub-pixel precision |
| **IDF1** | Identity F1 — harmonic mean of identity-precision and identity-recall over the longest consistent tracklet | [0, 1] | identity inconsistency over time | long-timelapse re-ID |

AOGM default weights: node add/delete = 1, wrong class = 1, edge add/
delete = 1, edge wrong-semantic = 1.5 (mitosis edge errors weighted
higher).

### Ground-truth XML format (CTC)

```
dataset/
  01/                       # raw input frames
    t000.tif ... tNNN.tif
  01_GT/
    SEG/                    # segmentation GT
      man_seg000.tif        # 16-bit label image; 0 = bg, n = object n
      ...
    TRA/                    # tracking GT
      man_track.txt         # lineage table: ID start_frame end_frame parent_ID
      man_track000.tif      # per-frame label images; same label = same track
      ...
```

`man_track.txt` rows: `L B E P` — label `L` exists frame `B..E` inclusive,
parent `P` (0 = no parent). Divisions = two new labels with same parent.

### Convert TrackMate output → CTC format

TrackMate-Helper does this end-to-end. Manually:

1. `LabelImgExporter` (or *TrackMate > Export label image*) writes per-frame
   label TIFFs with `label = TRACK_INDEX + 1`.
2. Walk the model: for each track, write one `L B E P` row.
   Parent comes from `model.getTrackModel().trackEdges()` for edges
   originating at a splitting spot.
3. Save raw frames into `01/`, label images into `01_RES/`, run the
   CTC-supplied `TRAMeasure` / `SEGMeasure` / `DETMeasure` binaries
   against `01_GT/`.

### TrackMate-Helper workflow

1. Prepare ground truth as `01_GT/SEG/` and `01_GT/TRA/` next to the raw
   stack.
2. Open the raw stack; *Plugins > Tracking > TrackMate Helper*.
3. Configure ranges: e.g. LoG `RADIUS ∈ {2,3,4,5}`, quality threshold
   `∈ {0.1..1.0}`, tracker `∈ {LAP, Kalman}`, `LINKING_MAX_DISTANCE
   ∈ {5,10,15,20}`.
4. Run sweep. Time scales as |detector params| × |tracker params| ×
   frames; expect hours on long stacks.
5. Inspect score table; pick highest-TRA combination and re-run TrackMate
   with those exact settings.

### Manual visual validation (no GT)

- Pick 5 random frames; visually count cells; compare to
  `model.getSpots().getNSpots(frame, true)`. Discrepancy >10 % → detection off.
- Pick 3 random tracks; scrub through frames in TrackScheme; verify the
  highlighted spot stays on the same biological cell.
- Color-by-`TRACK_INDEX`; visually scan for places where one track
  switches between two cells (ID swap) or two tracks suddenly share a
  cell (merge artefact).
- Plot track displacement histograms; outliers at very high displacement
  are usually swap artefacts.

### TrackMate vs alternatives

| Tool | Strength | Weakness | Pick when |
|---|---|---|---|
| **TrackMate** | General-purpose, GUI inspection, ~all-vs-all LAP, many detectors, scriptable | Memory-heavy on long crowded stacks; LAP not motion-aware by default | first choice for most cell/particle data |
| **ilastik Tracking Workflow** | Probability-based, integrates pixel classifier with GP-CRF tracker | Slower, separate UI, harder to script | pixel-classifier workflows where ilastik already segments |
| **Mastodon** | Massive datasets (>1M spots), interactive lineage editing, BigDataViewer integration | Steeper learning curve | long-term lineage tracing in light-sheet / development data |
| **BTrack** (Python) | Bayesian filter, motion model, fast | Python-side only; needs separate segmentation | scripted pipelines outside Fiji |
| **TrackMate-Trackastra** | Transformer-based AI tracker; crowded, high-motion cases | Requires Python env, GPU recommended | hard cases where LAP/Kalman fail |
| **Ultrack** | Hierarchical segmentation + ILP tracking, dense tissue | Python pipeline, steeper setup | dense tissue / organoid imaging |

---

## §13 Extensions / Update Sites

| Extension | Update Site | Purpose |
|---|---|---|
| TrackMate-StarDist | `TrackMate-StarDist` | Nuclei detection (built-in + custom models). Requires `CSBDeep`, `TensorFlow`, `StarDist`. |
| TrackMate-Cellpose | `TrackMate-Cellpose` | Cell segmentation (Cellpose 3 + Cellpose-SAM). Conda env required. |
| TrackMate-Omnipose | `TrackMate-Omnipose` | Bacteria / filament segmentation. Separate conda env. |
| TrackMate-Weka | `TrackMate-Weka` | Pixel-classifier detection from `.model` files |
| TrackMate-ilastik | `TrackMate-Ilastik` | Headless ilastik classifier from `.ilp` |
| TrackMate-MorphoLibJ | `TrackMate-MorphoLibJ` | Morphological watershed for border-labelled |
| TrackMate-Spotiflow | `TrackMate-Spotiflow` | DL spot detection (FISH, single molecules) |
| TrackMate-YOLO | `TrackMate-YOLO` | Bounding-box detection (.pt models) |
| TrackMate-Trackastra | `TrackMate-Trackastra` | Transformer AI tracker |
| TrackMate-Helper | `TrackMate-Helper` | Parameter sweep + Batcher |
| TrackMate-CSVImporter | `TrackMate-CSVImporter` | Import CSV detections / tracks back into TrackMate |
| TrackMate-Lacss | `trackmate-lacss` | Label-free cell segmentation |
| TrackMate-MSD | (plugin) | MSD curves and α-exponent per track |
| SciView-TrackMate | `SciView-TrackMate` | Replaces 3D Viewer with SciView |

---

## §14 Known Issues & Workarounds

| Issue | Fix |
|---|---|
| **Z/T confusion** — stack defaults to Z, TrackMate finds nothing | `run("Properties...", "slices=1 frames=N");` |
| **Memory exhaustion** | Increase heap (*Edit > Options > Memory*); use *Trim non-visible data*; v8 fixed memory leak |
| **RAM not freed on close** | Close image window + `System.gc()`, or restart Fiji |
| **Jagged tracks** | Enable `DO_SUBPIXEL_LOCALIZATION`; verify blob diameter |
| **ClassDefFoundError** | Update Fiji; enable Java8 update site |
| **Calibration wrong** | Always verify *Image > Properties* before running. All measurements are in physical units |
| **TARGET_CHANNEL semantics differ** | Built-ins are 1-based; CLI detectors (Cellpose / Spotiflow / YOLO) accept 0 = "all channels" |
| **Cellpose model enum vs string** | Current TrackMate-Cellpose: pass `"cyto3"` etc. as string. Legacy plugin: `from CellposeSettings import PretrainedModel; settings_map['CELLPOSE_MODEL'] = PretrainedModel.CYTO3` |
| **Jython UnicodeEncodeError** | `reload(sys); sys.setdefaultencoding('utf-8')` at script top |
| **Headless `HyperStackDisplayer` crash** | Wrap with `if (!GraphicsEnvironment.isHeadless()) { ... }` |
| **`MODEL_FILEPATH` to unzipped StarDist folder fails** | Must point to the `.zip` exported via Python `model.export_TF()` |
| **Ilastik `CLASS_INDEX` is 0-based** | While `TARGET_CHANNEL` is 1-based |
| **MorphoLibJ TOLERANCE wrong scale** | Bit-depth-dependent; 8-bit ~10, 16-bit ~2000 |

---

## §15 Biological Parameter Ranges

| Parameter | Adherent cells (10–20×) | Nuclei (20–40×) | Particles (60–100×) | Bacteria | Tissue (slices) |
|---|---|---|---|---|---|
| Spot radius | 10–30 μm | 3–8 μm | 0.2–1 μm | 0.5–2 μm | 5–10 μm |
| Quality threshold | 10–50 | 10–100 | 5–50 | 5–30 | 20–80 |
| Linking max dist | 15–40 μm | 5–20 μm | 1–5 μm | 2–10 μm | 5–15 μm |
| Gap-closing dist | 20–50 μm | 10–30 μm | 2–10 μm | 5–15 μm | 10–20 μm |
| Max frame gap | 2–4 | 2–3 | 1–2 | 1–3 | 2–4 |
| Best detector | Cellpose `cyto3` / SAM | StarDist / LoG | LoG / DoG / Spotiflow | Cellpose `bact_phase_cp3` / Omnipose | StarDist / LoG |
| Best tracker | Overlap / LAP | LAP | Simple LAP | Kalman | LAP |

Always start at the centre of the range and tune from there. All distances
in physical (calibrated) units — set `Image > Properties` first.

---

## §16 TCP Agent Recipes

Each recipe is a ready-to-send command. Stdout returns through `ij.py`'s
reply.

### Recipe 1 — LoG + Simple LAP, return spot/track count

```bash
python ij.py script '
import fiji.plugin.trackmate.Model as Model
import fiji.plugin.trackmate.Settings as Settings
import fiji.plugin.trackmate.TrackMate as TrackMate
import fiji.plugin.trackmate.detection.LogDetectorFactory as LogDetectorFactory
import fiji.plugin.trackmate.tracking.jaqaman.SimpleSparseLAPTrackerFactory as SimpleLAP
import ij.IJ as IJ
def imp = IJ.getImage()
def model = new Model(); def s = new Settings(imp)
s.detectorFactory  = new LogDetectorFactory()
s.detectorSettings = s.detectorFactory.getDefaultSettings()
s.detectorSettings["RADIUS"]    = 2.5
s.detectorSettings["THRESHOLD"] = 5.0
s.trackerFactory   = new SimpleLAP()
s.trackerSettings  = s.trackerFactory.getDefaultSettings()
s.addAllAnalyzers()
def tm = new TrackMate(model, s); tm.checkInput(); tm.process()
println "spots=" + model.getSpots().getNSpots(true)
println "tracks=" + model.getTrackModel().nTracks(true)
'
```

### Recipe 2 — Cellpose + Overlap, save XML + tracks CSV (file-based)

Long heredocs get awkward; write Groovy to disk first.

```bash
cat > /tmp/track_cellpose.groovy <<'EOF'
import fiji.plugin.trackmate.*
import fiji.plugin.trackmate.cellpose.CellposeDetectorFactory
import fiji.plugin.trackmate.tracking.overlap.OverlapTrackerFactory
import fiji.plugin.trackmate.io.TmXmlWriter
import fiji.plugin.trackmate.visualization.table.TrackTableView
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettingsIO
import ij.IJ

def imp = IJ.getImage()
def imgPath = imp.getOriginalFileInfo().directory
def out = new File(imgPath, 'AI_Exports'); out.mkdirs()

def model = new Model(); def s = new Settings(imp)
s.detectorFactory  = new CellposeDetectorFactory()
s.detectorSettings = s.detectorFactory.getDefaultSettings()
s.detectorSettings['CELLPOSE_MODEL']       = 'cyto3'    // string in current plugin
s.detectorSettings['PRETRAINED_OR_CUSTOM'] = 'CELLPOSE_MODEL'
s.detectorSettings['CELL_DIAMETER']        = 30.0
s.detectorSettings['USE_GPU']              = true
s.detectorSettings['TARGET_CHANNEL']       = 0          // 0 = grayscale composite

s.trackerFactory  = new OverlapTrackerFactory()
s.trackerSettings = s.trackerFactory.getDefaultSettings()
s.trackerSettings['MIN_IOU'] = 0.3
s.addAllAnalyzers()
def tm = new TrackMate(model, s)
tm.checkInput() && tm.process()

def xml = new File(out, imp.getTitle().replace('.tif','') + '.xml')
new TmXmlWriter(xml).with { appendModel(model); appendSettings(s); writeToFile() }
def ds = DisplaySettingsIO.readUserDefault()
TrackTableView.createTrackTable(model, ds).exportToCsv(new File(out, 'tracks.csv'))
println "saved=${xml.absolutePath}; tracks=${model.getTrackModel().nTracks(true)}"
EOF
python ij.py script --file /tmp/track_cellpose.groovy
```

### Recipe 3 — Reload saved XML, refilter on TRACK_DURATION > 30, export CSV (Jython)

```bash
python ij.py script --lang jython '
from fiji.plugin.trackmate.io import TmXmlReader, CSVExporter
from fiji.plugin.trackmate import TrackMate
from fiji.plugin.trackmate.features import FeatureFilter
from java.io import File
import sys
reload(sys); sys.setdefaultencoding("utf-8")

reader = TmXmlReader(File("/data/session.xml"))
model    = reader.getModel()
imp      = reader.readImage()
settings = reader.readSettings(imp)

settings.getTrackFilters().clear()
settings.addTrackFilter(FeatureFilter("TRACK_DURATION", 30.0, True))

tm = TrackMate(model, settings)
tm.computeTrackFeatures(False)
tm.execTrackFiltering(False)

CSVExporter.exportSpots("/data/session-filtered-spots.csv", model, True)
print "kept=%d tracks" % model.getTrackModel().nTracks(True)
'
```

### Recipe 4 — Detect spots only (no tracking), push to ROI Manager

```bash
python ij.py script '
import fiji.plugin.trackmate.Model as Model
import fiji.plugin.trackmate.Settings as Settings
import fiji.plugin.trackmate.TrackMate as TrackMate
import fiji.plugin.trackmate.detection.LogDetectorFactory as Log
import ij.IJ as IJ
import ij.gui.OvalRoi as OvalRoi
import ij.plugin.frame.RoiManager as RoiManager

def imp = IJ.getImage(); def cal = imp.getCalibration()
def model = new Model(); def s = new Settings(imp)
s.detectorFactory  = new Log()
s.detectorSettings = s.detectorFactory.getDefaultSettings()
s.detectorSettings["RADIUS"]    = 2.0
s.detectorSettings["THRESHOLD"] = 10.0
s.addAllAnalyzers()
def tm = new TrackMate(model, s)
tm.checkInput(); tm.execDetection(); tm.execInitialSpotFiltering()
tm.computeSpotFeatures(false); tm.execSpotFiltering(false)

def rm = RoiManager.getRoiManager(); rm.reset()
model.getSpots().iterable(true).each { sp ->
    def r  = sp.getFeature("RADIUS") / cal.pixelWidth
    def cx = sp.getDoublePosition(0) / cal.pixelWidth
    def cy = sp.getDoublePosition(1) / cal.pixelHeight
    def roi = new OvalRoi(cx - r, cy - r, 2*r, 2*r)
    roi.setPosition((int)(sp.getFeature("FRAME") + 1))
    rm.addRoi(roi)
}
println "spots=" + model.getSpots().getNSpots(true)
println "rois="  + rm.getCount()
'
```

**Returning results.** Print CSV / summary to stdout — the TCP server
returns Groovy stdout in the ij.py reply. Or write to `AI_Exports/`
next to the source image, then `python ij.py state` to confirm the file.

---

## §17 Factory Import Reference

```
# Detectors — built-in
fiji.plugin.trackmate.detection.LogDetectorFactory
fiji.plugin.trackmate.detection.DogDetectorFactory
fiji.plugin.trackmate.detection.HessianDetectorFactory
fiji.plugin.trackmate.detection.ManualDetectorFactory
fiji.plugin.trackmate.detection.ThresholdDetectorFactory
fiji.plugin.trackmate.detection.MaskDetectorFactory
fiji.plugin.trackmate.detection.LabelImageDetectorFactory

# Detectors — extensions
fiji.plugin.trackmate.cellpose.CellposeDetectorFactory
fiji.plugin.trackmate.cellpose.advanced.AdvancedCellposeDetectorFactory
fiji.plugin.trackmate.cellpose.sam.CellposeSAMDetectorFactory
fiji.plugin.trackmate.omnipose.OmniposeDetectorFactory
fiji.plugin.trackmate.stardist.StarDistDetectorFactory
fiji.plugin.trackmate.stardist.StarDistCustomDetectorFactory
fiji.plugin.trackmate.ilastik.IlastikDetectorFactory
fiji.plugin.trackmate.weka.WekaDetectorFactory
fiji.plugin.trackmate.morpholibj.MorphoLibJDetectorFactory
fiji.plugin.trackmate.spotiflow.SpotiflowDetectorFactory
fiji.plugin.trackmate.spotiflow.AdvancedSpotiflowDetectorFactory
fiji.plugin.trackmate.yolo.YOLODetectorFactory

# Trackers
fiji.plugin.trackmate.tracking.jaqaman.SparseLAPTrackerFactory
fiji.plugin.trackmate.tracking.jaqaman.SimpleSparseLAPTrackerFactory
fiji.plugin.trackmate.tracking.kalman.KalmanTrackerFactory
fiji.plugin.trackmate.tracking.overlap.OverlapTrackerFactory
fiji.plugin.trackmate.tracking.kdtree.NearestNeighborTrackerFactory
fiji.plugin.trackmate.tracking.trackastra.TrackastraTrackerFactory
fiji.plugin.trackmate.tracking.manual.ManualTrackerFactory

# Utilities
fiji.plugin.trackmate.tracking.LAPUtils
fiji.plugin.trackmate.features.FeatureFilter
fiji.plugin.trackmate.providers.SpotAnalyzerProvider
fiji.plugin.trackmate.providers.EdgeAnalyzerProvider
fiji.plugin.trackmate.providers.TrackAnalyzerProvider
fiji.plugin.trackmate.io.TmXmlReader
fiji.plugin.trackmate.io.TmXmlWriter
fiji.plugin.trackmate.io.CSVExporter
fiji.plugin.trackmate.action.ExportTracksToXML
fiji.plugin.trackmate.action.LabelImgExporter
fiji.plugin.trackmate.action.LabelImgExporter.LabelIdPainting
fiji.plugin.trackmate.visualization.table.TrackTableView
fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer
fiji.plugin.trackmate.visualization.trackscheme.TrackScheme
fiji.plugin.trackmate.visualization.threedviewer.SpotDisplayer3D
fiji.plugin.trackmate.gui.displaysettings.DisplaySettings
fiji.plugin.trackmate.gui.displaysettings.DisplaySettingsIO
fiji.plugin.trackmate.SelectionModel
fiji.plugin.trackmate.SpotRoi
```
