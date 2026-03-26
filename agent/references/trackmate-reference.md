# TrackMate Reference

Particle/cell tracking in Fiji. Modular: Detection → Spot filtering → Tracking → Track filtering → Export.

**Links:** [Wiki](https://imagej.net/plugins/trackmate/) | [GitHub](https://github.com/trackmate-sc/TrackMate) | [Scripting keys](https://imagej.net/plugins/trackmate/scripting/trackmate-detectors-trackers-keys)

---

## Core Classes

| Class | Role |
|-------|------|
| `Model` | Stores spots, edges, tracks, features |
| `Settings` | Config: image, detector, tracker, filters, analyzers |
| `TrackMate` | Orchestrator — runs detection + tracking |
| `FeatureModel` | Edge/track feature storage |
| `Spot` | Single detection with features |
| `SpotCollection` | All spots organized by frame |

---

## All Detectors

### Spot Detectors

| Detector | Factory | Best For | Key Params |
|----------|---------|----------|------------|
| **LoG** (builtin) | `LogDetectorFactory` | Roundish objects, general-purpose | `RADIUS`, `THRESHOLD`, `DO_SUBPIXEL_LOCALIZATION`, `DO_MEDIAN_FILTERING` |
| **DoG** (builtin) | `DogDetectorFactory` | Faster LoG approx, very small objects | Same as LoG |
| **Hessian** (builtin) | `HessianDetectorFactory` | Anisotropic 3D objects | `RADIUS`, `RADIUS_Z`, `THRESHOLD`, `NORMALIZE` |
| **Spotiflow** | trackmate-spotiflow | AI spot detection, robust to defects | `SPOTIFLOW_MODEL` ("general", "hybiss", "synth_complex", "fluo_live", "synth_3d", "smfish_3d") |
| **YOLO** | trackmate-yolo | Custom-trained object detection | `YOLO_MODEL_FILEPATH` (no pretrained models included) |
| **Manual** (builtin) | `ManualDetectorFactory` | User-created spots | `RADIUS` |

All spot detectors share `TARGET_CHANNEL` (int, 1-based).

### Segmenters (produce contours)

| Detector | Factory / Update Site | Key Params |
|----------|----------------------|------------|
| **Thresholding** | `ThresholdDetectorFactory` | `INTENSITY_THRESHOLD`, `SIMPLIFY_CONTOURS` |
| **Mask** | trackmate-mask-detector | `SIMPLIFY_CONTOURS` |
| **Label Image** | trackmate-label-image-detector | `SIMPLIFY_CONTOURS` |
| **StarDist** (builtin model) | TrackMate-StarDist | `TARGET_CHANNEL` only. Requires: CSBDeep, Tensorflow, StarDist update sites |
| **StarDist** (custom) | TrackMate-StarDist | `MODEL_FILEPATH`, `SCORE_THRESHOLD`, `OVERLAP_THRESHOLD` |
| **Cellpose** | TrackMate-Cellpose | `CELLPOSE_PYTHON_FILEPATH`, `CELLPOSE_MODEL` (CYTO/CYTO2/CYTO3/NUCLEI/CUSTOM), `CELL_DIAMETER`, `USE_GPU`, `OPTIONAL_CHANNEL_2` |
| **Omnipose** | TrackMate-Cellpose | Bacteria/filaments |
| **ilastik** | trackmate-ilastik | `CLASSIFIER_FILEPATH`, `CLASS_INDEX`, `PROBA_THRESHOLD` |
| **MorphoLibJ** | trackmate-morpholibj | `TOLERANCE`, `CONNECTIVITY` (6 or 26) |
| **Weka** | trackmate-weka | `CLASSIFIER_FILEPATH`, `CLASS_INDEX`, `PROBA_THRESHOLD` |

**Cellpose install:** `conda create -n cellpose-3 python=3.10 && pip install 'cellpose[gui]==3.1.1.2'`
Variants on same site: Cellpose Advanced, Cellpose-SAM, Omnipose.

---

## All Trackers

| Tracker | Factory | Best For | Key Params |
|---------|---------|----------|------------|
| **Simple LAP** | `SimpleSparseLAPTrackerFactory` | Brownian, no division | `LINKING_MAX_DISTANCE`, `GAP_CLOSING_MAX_DISTANCE`, `MAX_FRAME_GAP` |
| **LAP (Jaqaman)** | `SparseLAPTrackerFactory` | Division/merging, feature penalties | Above + `ALLOW_TRACK_SPLITTING/MERGING`, `*_MAX_DISTANCE`, `*_FEATURE_PENALTIES`, `ALTERNATIVE_LINKING_COST_FACTOR`, `CUTOFF_PERCENTILE` |
| **Kalman** | `KalmanTrackerFactory` | Directed/constant velocity | `LINKING_MAX_DISTANCE`, `KALMAN_SEARCH_RADIUS`, `MAX_FRAME_GAP` |
| **Overlap** | `OverlapTrackerFactory` | Slow segmented cells (IoU) | `SCALE_FACTOR`, `MIN_IOU`, `IOU_CALCULATION` ("FAST"/"PRECISE") |
| **Nearest Neighbor** | (builtin) | Simplest, no gap closing | `LINKING_MAX_DISTANCE` |
| **Trackastra** | trackmate-trackastra | AI tracking (v8, 2025) | — |
| **Manual** | (builtin) | User links spots | — |

**LAP cost function:** C = (D * (1 + sum(penalties)))^2, where penalty p = 3 * W * |f1-f2|/(f1+f2). Algorithm: Munkres-Kuhn (Hungarian), O(n^3). Two-step: frame-to-frame linking, then segment linking (gaps/merges/splits).

---

## All Features

### Spot Features

| Category | Keys | Notes |
|----------|------|-------|
| **Core** | `QUALITY`, `POSITION_X/Y/Z`, `POSITION_T`, `FRAME`, `RADIUS` | Always present |
| **Intensity** (per-ch) | `MEAN/MEDIAN/MIN/MAX/TOTAL/STD_INTENSITY_CHn` | Inside contour or radius |
| **Contrast/SNR** (per-ch) | `CONTRAST_CHn`, `SNR_CHn` | Michelson contrast, ring from r to 2r |
| **Morphology** (contour) | `AREA`, `PERIMETER`, `CIRCULARITY`, `SOLIDITY`, `SHAPE_INDEX` | Requires segmenter |
| **3D ellipsoid** | `ELLIPSOIDFIT_SEMIAXISLENGTH_A/B/C`, `ELLIPSOIDFIT_AXISPHI/THETA_A/B/C` | Shape: OBLATE/PROLATE/SCALENE |

### Edge Features

| Key | Description |
|-----|-------------|
| `SPEED`, `DISPLACEMENT` | Instantaneous velocity, distance between spots |
| `EDGE_TIME`, `EDGE_X/Y/Z_LOCATION` | Midpoint time and position |
| `SPOT_SOURCE_ID`, `SPOT_TARGET_ID`, `LINK_COST` | Link info |
| `DIRECTIONAL_CHANGE_RATE` | Angle between consecutive links |

### Track Features

| Key | Description |
|-----|-------------|
| `TRACK_DURATION`, `TRACK_START`, `TRACK_STOP`, `TRACK_DISPLACEMENT` | Timing and net displacement |
| `TRACK_MEAN/MAX/MIN/MEDIAN/STD_SPEED` | Speed statistics |
| `NUMBER_SPOTS/GAPS/SPLITS/MERGES/COMPLEX`, `LONGEST_GAP` | Branching topology |
| `TOTAL_DISTANCE_TRAVELED`, `MAX_DISTANCE_TRAVELED`, `CONFINEMENT_RATIO` | Motility |
| `MEAN_STRAIGHT_LINE_SPEED`, `LINEARITY_OF_FORWARD_PROGRESSION` | Directionality |
| `TRACK_INDEX`, `TRACK_ID`, `TRACK_MEAN_QUALITY`, `TRACK_X/Y/Z_LOCATION` | Index/quality |

---

## Scripting API

### Complete Jython Example

```python
from fiji.plugin.trackmate import Model, Settings, TrackMate, Logger, SelectionModel
from fiji.plugin.trackmate.detection import LogDetectorFactory
from fiji.plugin.trackmate.tracking.jaqaman import SparseLAPTrackerFactory
from fiji.plugin.trackmate.tracking import LAPUtils
from fiji.plugin.trackmate.features import FeatureFilter
from fiji.plugin.trackmate.providers import SpotAnalyzerProvider, EdgeAnalyzerProvider, TrackAnalyzerProvider
from fiji.plugin.trackmate.visualization.hyperstack import HyperStackDisplayer
from ij import IJ
import sys

imp = IJ.getImage()
model = Model()
model.setLogger(Logger.IJ_LOGGER)

settings = Settings(imp)
settings.dt = 0.05  # frame interval if not in metadata

# Detector
settings.detectorFactory = LogDetectorFactory()
settings.detectorSettings = settings.detectorFactory.getDefaultSettings()
settings.detectorSettings['RADIUS'] = 2.5
settings.detectorSettings['TARGET_CHANNEL'] = 1
settings.detectorSettings['THRESHOLD'] = 0.0

# Tracker
settings.trackerFactory = SparseLAPTrackerFactory()
settings.trackerSettings = LAPUtils.getDefaultLAPSettingsMap()
settings.trackerSettings['LINKING_MAX_DISTANCE'] = 15.0
settings.trackerSettings['GAP_CLOSING_MAX_DISTANCE'] = 15.0
settings.trackerSettings['MAX_FRAME_GAP'] = 2

# Add ALL analyzers
for key in SpotAnalyzerProvider(1).getKeys():
    settings.addSpotAnalyzerFactory(SpotAnalyzerProvider(1).getFactory(key))
for key in EdgeAnalyzerProvider().getKeys():
    settings.addEdgeAnalyzer(EdgeAnalyzerProvider().getFactory(key))
for key in TrackAnalyzerProvider().getKeys():
    settings.addTrackAnalyzer(TrackAnalyzerProvider().getFactory(key))

# Filters
settings.addSpotFilter(FeatureFilter('QUALITY', 30.0, True))
settings.addTrackFilter(FeatureFilter('TRACK_DURATION', 5.0, True))

# Execute
trackmate = TrackMate(model, settings)
if not trackmate.checkInput():
    sys.exit("CheckInput failed: " + trackmate.getErrorMessage())
if not trackmate.process():
    sys.exit("Processing failed: " + trackmate.getErrorMessage())

# Access results
fm = model.getFeatureModel()
for trackID in model.getTrackModel().trackIDs(True):
    duration = fm.getTrackFeature(trackID, 'TRACK_DURATION')
    speed = fm.getTrackFeature(trackID, 'TRACK_MEAN_SPEED')
    for spot in model.getTrackModel().trackSpots(trackID):
        x = spot.getFeature('POSITION_X')
        y = spot.getFeature('POSITION_Y')
        t = int(spot.getFeature('FRAME'))

# Display
sm = SelectionModel(model)
displayer = HyperStackDisplayer(model, sm, imp)
displayer.render()
displayer.refresh()
```

### Step-by-Step Execution

```python
trackmate.checkInput()
trackmate.execDetection()
trackmate.execInitialSpotFiltering()
trackmate.computeSpotFeatures(False)
trackmate.execSpotFiltering()
trackmate.execTracking()
trackmate.computeEdgeFeatures(False)
trackmate.computeTrackFeatures(False)
trackmate.execTrackFiltering()
```

### Using Different Detectors/Trackers in Scripts

```python
# Cellpose
from fiji.plugin.trackmate.cellpose import CellposeDetectorFactory
settings.detectorFactory = CellposeDetectorFactory()
settings.detectorSettings = settings.detectorFactory.getDefaultSettings()
settings.detectorSettings['CELLPOSE_PYTHON_FILEPATH'] = '/path/to/python'
settings.detectorSettings['CELLPOSE_MODEL'] = 'CYTO3'  # see Known Issues for enum caveat
settings.detectorSettings['CELL_DIAMETER'] = 30.0

# StarDist (custom model)
from fiji.plugin.trackmate.stardist import StarDistDetectorFactory
settings.detectorFactory = StarDistDetectorFactory()
settings.detectorSettings['MODEL_FILEPATH'] = '/path/to/model.zip'
settings.detectorSettings['SCORE_THRESHOLD'] = 0.5

# Kalman Tracker
from fiji.plugin.trackmate.tracking.kalman import KalmanTrackerFactory
settings.trackerFactory = KalmanTrackerFactory()
settings.trackerSettings = settings.trackerFactory.getDefaultSettings()
settings.trackerSettings['KALMAN_SEARCH_RADIUS'] = 20.0

# Overlap Tracker
from fiji.plugin.trackmate.tracking.overlap import OverlapTrackerFactory
settings.trackerFactory = OverlapTrackerFactory()
settings.trackerSettings = settings.trackerFactory.getDefaultSettings()
settings.trackerSettings['MIN_IOU'] = 0.3
```

### Feature Penalties (LAP Tracker)

```python
# Weight of 1 -> penalty=1 when one value is double the other
settings.trackerSettings['LINKING_FEATURE_PENALTIES'] = {'MEAN_INTENSITY_CH1': 1.0}
```

### XML I/O

```python
# Load
from fiji.plugin.trackmate.io import TmXmlReader
from java.io import File
reader = TmXmlReader(File('/path/to/trackmate.xml'))
model = reader.getModel()
settings = reader.readSettings(reader.readImage())

# Save
from fiji.plugin.trackmate.io import TmXmlWriter
writer = TmXmlWriter(File('/path/to/output.xml'), model.getLogger())
writer.appendModel(model)
writer.appendSettings(settings)
writer.writeToFile()
```

### Model Editing

```python
model.beginUpdate()
try:
    spot1 = Spot(10.0, 20.0, 0.0, 2.5, -1.0)
    model.addSpotTo(spot1, 0)  # frame 0
    spot2 = Spot(15.0, 25.0, 0.0, 2.5, -1.0)
    model.addSpotTo(spot2, 1)
    model.addEdge(spot1, spot2, -1.0)
finally:
    model.endUpdate()
```

---

## Export Options

```python
# TrackMate XML
from fiji.plugin.trackmate.io import TmXmlWriter
writer = TmXmlWriter(File("/path/out.xml"), model.getLogger())
writer.appendModel(model); writer.appendSettings(settings); writer.writeToFile()

# Simplified tracks XML
from fiji.plugin.trackmate.action import ExportTracksToXML
ExportTracksToXML.export(model, settings, File("/path/tracks.xml"))

# CSV (spots)
from fiji.plugin.trackmate.io import CSVExporter
CSVExporter.exportSpots("/path/spots.csv", model, True)

# CSV (spots, edges, tracks via tables)
from fiji.plugin.trackmate.visualization.table import TrackTableView
TrackTableView.createSpotTable(model, ds).exportToCsv(File("/path/spots.csv"))
TrackTableView.createEdgeTable(model, ds).exportToCsv(File("/path/edges.csv"))
TrackTableView.createTrackTable(model, ds).exportToCsv(File("/path/tracks.csv"))
```

**TrackMate-CSVImporter** (update site): import CSV detections/tracks into TrackMate.

---

## Batch Processing

### TrackMate-Batcher (Plugin)

Update site: TrackMate-Helper. Configure on one image, apply to folder. Launch: **Plugins > Tracking > TrackMate Batcher**.

### TrackMate-Helper (Parameter Optimization)

Update site: TrackMate-Helper + CellTrackingChallenge. Sweep detector/tracker combinations against ground truth.

| Metric | Meaning |
|--------|---------|
| SEG | Segmentation accuracy (IoU) |
| TRA | Tracking accuracy |
| DET | Detection quality |
| CT | Complete track reconstruction |
| TF | Track fraction continuity |
| BC | Branching correctness |

### Batch Jython Script

```python
import os
from ij import IJ
from fiji.plugin.trackmate import Model, Settings, TrackMate, Logger
from fiji.plugin.trackmate.detection import LogDetectorFactory
from fiji.plugin.trackmate.tracking.jaqaman import SparseLAPTrackerFactory
from fiji.plugin.trackmate.io import TmXmlWriter
from java.io import File

for filename in os.listdir("/path/to/images/"):
    if not filename.endswith(".tif"): continue
    imp = IJ.openImage("/path/to/images/" + filename)
    model = Model()
    model.setLogger(Logger.VOID_LOGGER)
    settings = Settings(imp)
    settings.detectorFactory = LogDetectorFactory()
    settings.detectorSettings = settings.detectorFactory.getDefaultSettings()
    settings.detectorSettings['RADIUS'] = 2.5
    settings.detectorSettings['THRESHOLD'] = 10.0
    settings.trackerFactory = SparseLAPTrackerFactory()
    settings.trackerSettings = settings.trackerFactory.getDefaultSettings()
    settings.trackerSettings['LINKING_MAX_DISTANCE'] = 15.0
    settings.trackerSettings['MAX_FRAME_GAP'] = 2
    trackmate = TrackMate(model, settings)
    if trackmate.checkInput() and trackmate.process():
        writer = TmXmlWriter(File("/path/out/" + filename.replace('.tif', '.xml')), model.getLogger())
        writer.appendModel(model); writer.appendSettings(settings); writer.writeToFile()
    imp.close()
```

---

## Extensions

| Extension | Update Site | Purpose |
|-----------|-------------|---------|
| TrackMate-StarDist | TrackMate-StarDist | Nuclei detection |
| TrackMate-Cellpose | TrackMate-Cellpose | Cell segmentation (also Omnipose, Cellpose-SAM) |
| TrackMate-Weka | TrackMate-Weka | Pixel classifier detection |
| TrackMate-ilastik | TrackMate-ilastik | ilastik classifier |
| TrackMate-MorphoLibJ | TrackMate-MorphoLibJ | Morphological segmentation |
| TrackMate-Spotiflow | trackmate-spotiflow | AI spot detection |
| TrackMate-YOLO | trackmate-yolo | YOLO object detection |
| TrackMate-Trackastra | trackmate-trackastra | AI tracking |
| TrackMate-Helper | TrackMate-Helper | Parameter optimization + Batcher |
| TrackMate-CSVImporter | TrackMate-CSVImporter | Import CSV data |
| TrackMate-Lacss | trackmate-lacss | Label-free cell segmentation |

---

## Known Issues & Workarounds

| Issue | Fix |
|-------|-----|
| **Z/T confusion** — stack defaults to Z, TrackMate finds nothing | `run("Properties...", "slices=1 frames=N");` |
| **Memory exhaustion** | Increase heap (Edit > Options > Memory), use "Trim non-visible data", v8 fixed memory leak |
| **RAM not freed on close** | Close image window + GC, or restart Fiji |
| **Jagged tracks** | Enable sub-pixel localization, verify blob diameter |
| **ClassDefFoundError** | Update Fiji, enable Java8 update site |
| **Calibration wrong** | Always verify Image > Properties before running. All measurements in physical units |

**Cellpose model enum in Jython:**
```python
# String 'CYTO3' silently fails in Jython. Use Java enum:
from fiji.plugin.trackmate.cellpose.CellposeSettings import PretrainedModel
settings.detectorSettings['CELLPOSE_MODEL'] = PretrainedModel.CYTO3
# Groovy auto-coerces strings — 'CYTO3' works there.
```

**ROI export workaround:**
```python
from ij.plugin.frame import RoiManager
from ij.gui import OvalRoi
rm = RoiManager.getInstance() or RoiManager()
for spot in model.getSpots().iterable(True):
    x = spot.getFeature('POSITION_X') / cal.pixelWidth
    y = spot.getFeature('POSITION_Y') / cal.pixelHeight
    r = spot.getFeature('RADIUS') / cal.pixelWidth
    roi = OvalRoi(x - r, y - r, 2*r, 2*r)
    roi.setPosition(int(spot.getFeature('FRAME')) + 1)
    rm.addRoi(roi)
```

---

## Biological Parameter Ranges

| Parameter | Adherent cells (10-20x) | Nuclei (20-40x) | Particles (60-100x) | Bacteria | SCN slices |
|-----------|------------------------|-----------------|---------------------|----------|------------|
| Spot radius | 10-30 um | 3-8 um | 0.2-1 um | 0.5-2 um | 5-10 um |
| Quality threshold | 10-50 | 10-100 | 5-50 | 5-30 | 20-80 |
| Linking max dist | 15-40 um | 5-20 um | 1-5 um | 2-10 um | 5-15 um |
| Gap-closing dist | 20-50 um | 10-30 um | 2-10 um | 5-15 um | 10-20 um |
| Max frame gap | 2-4 | 2-3 | 1-2 | 1-3 | 2-4 |
| Best detector | Cellpose cyto | StarDist/LoG | LoG/DoG | LoG | StarDist/LoG |
| Best tracker | Overlap/LAP | LAP | Simple LAP | Kalman | LAP |

---

## TCP Agent Usage

TrackMate is NOT macro-recordable. Always use `python ij.py script` (Groovy), never `execute_macro`.

```bash
python ij.py script '
import fiji.plugin.trackmate.*
import fiji.plugin.trackmate.detection.*
import fiji.plugin.trackmate.tracking.jaqaman.*
import fiji.plugin.trackmate.providers.*

def imp = ij.IJ.getImage()
def model = new Model()
def settings = new Settings(imp)
settings.detectorFactory = new LogDetectorFactory()
settings.detectorSettings = settings.detectorFactory.getDefaultSettings()
settings.detectorSettings["RADIUS"] = 2.5d
settings.detectorSettings["THRESHOLD"] = 50.0d
settings.trackerFactory = new SparseLAPTrackerFactory()
settings.trackerSettings = settings.trackerFactory.getDefaultSettings()
settings.trackerSettings["LINKING_MAX_DISTANCE"] = 15.0d
settings.trackerSettings["MAX_FRAME_GAP"] = 2
def sap = new SpotAnalyzerProvider(imp.getNChannels())
sap.getKeys().each { settings.addSpotAnalyzerFactory(sap.getFactory(it)) }
def trackmate = new TrackMate(model, settings)
trackmate.process()
println "Spots: " + model.getSpots().getNSpots(true)
println "Tracks: " + model.getTrackModel().nTracks(true)
'
```

**Getting results back:** Print CSV to stdout (parsed from ij.py response), export XML/CSV file, or push to ResultsTable then `python ij.py results`.

---

## Factory Import Reference

```
# Detectors
fiji.plugin.trackmate.detection.LogDetectorFactory
fiji.plugin.trackmate.detection.DogDetectorFactory
fiji.plugin.trackmate.detection.HessianDetectorFactory
fiji.plugin.trackmate.detection.ThresholdDetectorFactory
fiji.plugin.trackmate.detection.MaskDetectorFactory
fiji.plugin.trackmate.detection.LabelImageDetectorFactory
fiji.plugin.trackmate.cellpose.CellposeDetectorFactory
fiji.plugin.trackmate.stardist.StarDistDetectorFactory
fiji.plugin.trackmate.ilastik.IlastikDetectorFactory
fiji.plugin.trackmate.weka.WekaDetectorFactory

# Trackers
fiji.plugin.trackmate.tracking.jaqaman.SparseLAPTrackerFactory
fiji.plugin.trackmate.tracking.jaqaman.SimpleSparseLAPTrackerFactory
fiji.plugin.trackmate.tracking.kalman.KalmanTrackerFactory
fiji.plugin.trackmate.tracking.overlap.OverlapTrackerFactory

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
fiji.plugin.trackmate.visualization.table.TrackTableView
fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer
```
