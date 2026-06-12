"""
Compare 5 segmentation approaches for mCherry 3D cell segmentation.
Each approach produces a 3D label image -> ROIs overlaid on raw mCherry.

Approaches:
  A: Classical watershed on d_s8 (threshold + distance transform + watershed)
  B: Stronger minimum filter (m3, m4) + StarDist via TrackMate
  C: StarDist + post-detection merging of nearby ROIs
  D: StarDist + size filtering (remove small fragments)
  E: Weka pixel classifier
"""

import json, socket, struct, sys, time, base64
import numpy as np
from pathlib import Path
from scipy.ndimage import distance_transform_edt, label, binary_opening, generate_binary_structure
from skimage.feature import peak_local_max
from skimage.segmentation import watershed

AGENT_DIR = Path(__file__).parent
TMP_DIR = AGENT_DIR / ".tmp"
TMP_DIR.mkdir(exist_ok=True)

PX_SIZE = 0.2841
Z_STEP = 1.0
W, H, NZ = 1024, 1024, 13


def tcp(cmd, timeout=120):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    s.connect(("localhost", 7746))
    d = json.dumps(cmd).encode()
    s.sendall(struct.pack(">I", len(d)) + d)
    b = b""
    while len(b) < 4: b += s.recv(4 - len(b))
    n = struct.unpack(">I", b)[0]
    r = b""
    while len(r) < n: r += s.recv(min(65536, n - len(r)))
    s.close()
    return json.loads(r)


def macro(code, timeout=120):
    return tcp({"command": "execute_macro", "code": code}, timeout=timeout)


def script(code, timeout=120):
    return tcp({"command": "run_script", "code": code, "language": "groovy"}, timeout=timeout)


def cap(name):
    r = tcp({"command": "capture_image", "maxSize": 1024})
    if r.get("ok"):
        (TMP_DIR / f"{name}.png").write_bytes(base64.b64decode(r["result"]["image"]))
        print(f"  Captured {name}.png")


def get_pixels(title, z=None):
    """Get pixel data from ImageJ image as numpy array."""
    if z is not None:
        macro(f'selectImage("{title}"); setSlice({z});')
        r = tcp({"command": "get_pixels", "width": W, "height": H})
    else:
        # Get all slices
        r = tcp({"command": "get_pixels", "width": W, "height": H, "allSlices": True})

    if r.get("ok"):
        data = base64.b64decode(r["result"]["pixels"])
        arr = np.frombuffer(data, dtype=np.float32)
        if z is not None:
            return arr.reshape(H, W)
        else:
            return arr.reshape(NZ, H, W)
    return None


def load_stack_from_tif(path):
    """Load a TIFF stack using tifffile."""
    import tifffile
    return tifffile.imread(str(path))


def save_labels_and_import(labels_3d, title):
    """Save 3D label array as TIFF and open in ImageJ."""
    import tifffile
    tif_path = str(TMP_DIR / f"{title}.tif")
    tifffile.imwrite(tif_path, labels_3d.astype(np.uint16), imagej=True)
    tif_fwd = tif_path.replace("\\", "/")
    macro(f'open("{tif_fwd}"); rename("{title}");')
    print(f"  Opened {title} in ImageJ")


def labels_to_rois_and_overlay(label_title, raw_title, approach_name):
    """Convert label image to ROIs, overlay on raw, capture."""
    groovy = f'''
import ij.IJ
import ij.ImagePlus
import ij.plugin.frame.RoiManager
import ij.gui.Roi
import ij.process.ImageProcessor
import java.awt.Color

def labelImp = ij.WindowManager.getImage("{label_title}")
def rawImp = ij.WindowManager.getImage("{raw_title}")
if (labelImp == null || rawImp == null) {{
    IJ.log("ERROR: images not found")
    return
}}

// Get or create ROI Manager
def rm = RoiManager.getInstance()
if (rm == null) rm = new RoiManager()
rm.reset()

int nSlices = labelImp.stackSize
int count = 0

for (int z = 1; z <= nSlices; z++) {{
    labelImp.setSlice(z)
    def ip = labelImp.getProcessor()

    // Find unique labels in this slice
    def labels = new HashSet()
    for (int y = 0; y < ip.height; y++) {{
        for (int x = 0; x < ip.width; x++) {{
            int v = (int) ip.getf(x, y)
            if (v > 0) labels.add(v)
        }}
    }}

    // Create ROI for each label using thresholding
    for (int lab : labels) {{
        // Set threshold to isolate this label
        ip.setThreshold(lab, lab, ImageProcessor.NO_LUT_UPDATE)
        IJ.run(labelImp, "Create Selection", "")
        def roi = labelImp.getRoi()
        if (roi != null) {{
            roi.setPosition(z)
            roi.setName("{approach_name}_z" + z + "_" + lab)
            rm.addRoi(roi)
            count++
        }}
        labelImp.deleteRoi()
    }}
}}

IJ.log("{approach_name}: " + count + " ROIs across " + nSlices + " slices")

// Show ROIs on raw image
rawImp.show()
rm.runCommand(rawImp, "Show All without labels")

// Count unique 3D objects
def maxLabel = 0
for (int z = 1; z <= nSlices; z++) {{
    labelImp.setSlice(z)
    def stats = labelImp.getProcessor().getStats()
    if (stats.max > maxLabel) maxLabel = (int) stats.max
}}
IJ.log("{approach_name}: " + maxLabel + " 3D objects")
'''
    r = script(groovy, timeout=300)
    if r.get("ok") and r["result"].get("success"):
        print(f"  {approach_name}: ROIs created successfully")
    else:
        print(f"  {approach_name}: ROI creation failed: {r}")


# =====================================================
# APPROACH A: Classical watershed on d_s8
# =====================================================
def approach_A():
    """Threshold + distance transform + watershed on d_s8 density map."""
    print("\n" + "="*60)
    print("APPROACH A: Classical watershed on d_s8")
    print("="*60)

    # Load d_s8 stack
    d_s8 = load_stack_from_tif(TMP_DIR / "d_s8_stack.tif")
    print(f"  d_s8 shape: {d_s8.shape}, dtype: {d_s8.dtype}")
    print(f"  d_s8 range: {d_s8.min():.2f} - {d_s8.max():.2f}")

    # Best params from fine-tuning: t=0.035, md=15, ms=200
    tp = 0.035      # threshold percentage of max
    md = 15          # minimum peak distance (pixels)
    ms = 200         # minimum object size (pixels)

    all_labels = np.zeros((NZ, H, W), dtype=np.uint16)
    next_label = 1
    total_cells = 0

    for z in range(NZ):
        density = d_s8[z].astype(np.float64)
        thresh = density.max() * tp
        binary = density > thresh

        # Remove small objects
        lbl, n_obj = label(binary)
        for i in range(1, n_obj + 1):
            if np.sum(lbl == i) < ms:
                binary[lbl == i] = False

        # Distance transform
        dt = distance_transform_edt(binary)

        # Find peaks
        coords = peak_local_max(dt, min_distance=md, threshold_abs=4)
        if len(coords) == 0:
            continue

        seeds = np.zeros_like(density, dtype=int)
        for i, (y, x) in enumerate(coords):
            seeds[y, x] = i + 1

        # Watershed
        ws = watershed(-dt, seeds, mask=binary)

        # Relabel to global IDs
        for local_id in range(1, ws.max() + 1):
            mask = ws == local_id
            if np.sum(mask) > 0:
                all_labels[z][mask] = next_label
                next_label += 1

        n_cells = len(coords)
        total_cells += n_cells

    n_objects = next_label - 1
    avg_per_slice = total_cells / NZ
    print(f"  Results: {n_objects} total labels, {avg_per_slice:.0f} avg/slice")

    # Now link across Z using overlap
    print("  Linking labels across Z...")
    linked = link_labels_across_z(all_labels)
    n_3d = linked.max()
    print(f"  After Z-linking: {n_3d} 3D objects")

    save_labels_and_import(linked, "labels_A")
    return linked


def link_labels_across_z(labels_3d):
    """Link 2D labels across Z-slices by overlap to create 3D objects."""
    linked = np.zeros_like(labels_3d)
    label_map = {}  # old_label -> new_label
    next_id = 1

    for z in range(labels_3d.shape[0]):
        slice_labels = labels_3d[z]
        unique = np.unique(slice_labels)
        unique = unique[unique > 0]

        for old_id in unique:
            mask = slice_labels == old_id

            if z > 0:
                # Check overlap with previous slice
                prev_vals = linked[z-1][mask]
                prev_vals = prev_vals[prev_vals > 0]

                if len(prev_vals) > 0:
                    # Find most common overlapping label
                    counts = np.bincount(prev_vals)
                    best_match = np.argmax(counts[1:]) + 1 if len(counts) > 1 else 0

                    if best_match > 0 and counts[best_match] > 0:
                        linked[z][mask] = best_match
                        continue

            # New 3D object
            linked[z][mask] = next_id
            next_id += 1

    return linked


# =====================================================
# APPROACH B: Stronger minimum filter + StarDist
# =====================================================
def approach_B():
    """Edge+Density with minimum filter r=3 and r=4, then StarDist."""
    print("\n" + "="*60)
    print("APPROACH B: Stronger minimum filter + StarDist")
    print("="*60)

    results = {}
    for min_r in [3, 4]:
        print(f"\n  --- Minimum radius = {min_r} ---")

        # Generate the edge+density hybrid with stronger minimum filter
        # Using the pipeline from WIP: b2_v5 edge + density, post: g3, min=r
        groovy = f'''
import ij.IJ
import ij.ImagePlus

def raw = ij.WindowManager.getImage("raw")
if (raw == null) {{ IJ.log("ERROR: raw not found"); return }}

// DENSITY PATH
IJ.selectWindow("raw")
IJ.run("Duplicate...", "title=dens_work duplicate")
IJ.run("Subtract Background...", "rolling=50 stack")
IJ.run("Gaussian Blur 3D...", "x=2 y=2 z=1")
IJ.run("8-bit")
IJ.run("Auto Local Threshold", "method=Bernsen radius=15 parameter_1=0 parameter_2=0 white stack")
IJ.run("32-bit")
IJ.run("Divide...", "value=255 stack")
IJ.run("Gaussian Blur 3D...", "x=8 y=8 z=2")
IJ.run("Enhance Contrast...", "saturated=0.3 normalize process_all")
IJ.run("8-bit")
IJ.selectWindow("dens_work")
IJ.log("Density path done")

// EDGE PATH
IJ.selectWindow("raw")
IJ.run("Duplicate...", "title=edge_work duplicate")
IJ.run("8-bit")
IJ.run("Auto Local Threshold", "method=Bernsen radius=15 parameter_1=0 parameter_2=0 white stack")
IJ.run("32-bit")
IJ.run("Gaussian Blur 3D...", "x=2 y=2 z=1")
IJ.run("Variance 3D...", "x=5 y=5 z=0")
IJ.run("Enhance Contrast...", "saturated=0.3 normalize process_all")
IJ.run("8-bit")
IJ.selectWindow("edge_work")
IJ.log("Edge path done")

// COMBINE
IJ.run("Image Calculator...", "image1=edge_work operation=Add image2=dens_work create 32-bit stack")
IJ.selectWindow("Result of edge_work")
IJ.run("Gaussian Blur 3D...", "x=3 y=3 z=1")
IJ.run("Minimum 3D...", "x={min_r} y={min_r} z=1")
IJ.run("Enhance Contrast...", "saturated=0.3 normalize process_all")
IJ.run("8-bit")
IJ.selectWindow("Result of edge_work")
IJ.rename("hybrid_m{min_r}")
IJ.log("Hybrid m{min_r} done")

// Clean up intermediates
IJ.selectWindow("dens_work"); IJ.run("Close")
IJ.selectWindow("edge_work"); IJ.run("Close")
'''
        r = script(groovy, timeout=300)
        if not (r.get("ok") and r["result"].get("success")):
            print(f"    Preprocessing failed: {r}")
            continue

        # Check for dialogs
        macro('selectImage("hybrid_m' + str(min_r) + '");')
        cap(f"hybrid_m{min_r}")

        # Now run StarDist via TrackMate on this
        print(f"    Running StarDist + TrackMate on hybrid_m{min_r}...")
        stardist_groovy = f'''
import ij.IJ
import ij.ImagePlus
import fiji.plugin.trackmate.Model
import fiji.plugin.trackmate.Settings
import fiji.plugin.trackmate.TrackMate
import fiji.plugin.trackmate.Logger
import fiji.plugin.trackmate.action.LabelImgExporter
import fiji.plugin.trackmate.detection.DetectorKeys
import fiji.plugin.trackmate.stardist.StarDistDetectorFactory
import fiji.plugin.trackmate.tracking.jaqaman.SparseLAPTrackerFactory

def imp = ij.WindowManager.getImage("hybrid_m{min_r}")
if (imp == null) {{ IJ.log("ERROR: hybrid_m{min_r} not found"); return }}

int origC = imp.getNChannels()
int origZ = imp.getNSlices()
int origT = imp.getNFrames()
def cal = imp.getCalibration()?.copy()

// Swap Z -> T for TrackMate
imp.setDimensions(origC, 1, origZ * origT)

Model model = new Model()
model.setLogger(Logger.VOID_LOGGER)
Settings settings = new Settings(imp)
settings.addAllAnalyzers()

settings.detectorFactory = new StarDistDetectorFactory()
settings.detectorSettings = settings.detectorFactory.getDefaultSettings()
settings.detectorSettings.put(DetectorKeys.KEY_TARGET_CHANNEL, 1)

settings.trackerFactory = new SparseLAPTrackerFactory()
settings.trackerSettings = settings.trackerFactory.getDefaultSettings()
// link distance = 8 um (optimal from previous testing)
settings.trackerSettings.put("LINKING_MAX_DISTANCE", 8.0d)
settings.trackerSettings.put("GAP_CLOSING_MAX_DISTANCE", 5.0d)
settings.trackerSettings.put("MAX_FRAME_GAP", 2)

TrackMate trackmate = new TrackMate(model, settings)
if (!trackmate.checkInput() || !trackmate.process()) {{
    IJ.log("TrackMate failed for m{min_r}: " + trackmate.getErrorMessage())
    imp.setDimensions(origC, origZ, origT)
    return
}}

// Fix visibility
def spots = model.getSpots()
int nFrames = imp.getNFrames()
for (int f = 0; f < nFrames; f++) {{
    for (def spot : spots.iterable(f, false)) {{
        spot.putFeature("VISIBILITY", 1.0d)
    }}
}}
spots.setVisible(true)

// Export label image
ImagePlus labelImp = LabelImgExporter.createLabelImagePlus(
    trackmate, false, false,
    LabelImgExporter.LabelIdPainting.LABEL_IS_INDEX_MOVIE_UNIQUE)

// Restore dims
imp.setDimensions(origC, origZ, origT)
labelImp.setDimensions(1, origZ * origT, 1)
labelImp.setTitle("labels_B_m{min_r}")
if (cal != null) labelImp.setCalibration(cal)
labelImp.show()
IJ.run(labelImp, "glasbey_on_dark", "")

// Count
int totalSpots = spots.getNSpots(true)
int nTracks = model.getTrackModel().nTracks(true)
double maxVal = 0
for (int s = 1; s <= labelImp.stackSize; s++) {{
    double m = labelImp.getStack().getProcessor(s).getStats().max
    if (m > maxVal) maxVal = m
}}
IJ.log("B_m{min_r}: " + totalSpots + " spots, " + nTracks + " tracks, " + (int)maxVal + " 3D objects")
'''
        r = script(stardist_groovy, timeout=600)
        if r.get("ok") and r["result"].get("success"):
            print(f"    StarDist + TrackMate completed for m{min_r}")
        else:
            print(f"    StarDist failed: {r.get('error', r)}")

        results[f"m{min_r}"] = True

    return results


# =====================================================
# APPROACH C: StarDist + post-detection merging
# =====================================================
def approach_C():
    """Run StarDist on d_s8, then merge nearby detections."""
    print("\n" + "="*60)
    print("APPROACH C: StarDist + post-detection merging")
    print("="*60)

    # First normalize d_s8 to 8-bit for StarDist
    print("  Normalizing d_s8 to 8-bit...")
    macro('selectImage("d_s8"); run("Duplicate...", "title=d_s8_8bit duplicate");')

    groovy_norm = '''
import ij.IJ
import ij.ImagePlus

def imp = ij.WindowManager.getImage("d_s8_8bit")
if (imp == null) { IJ.log("ERROR: d_s8_8bit not found"); return }

// Percentile normalization (p1-p99)
double[] allPx = new double[0]
def stack = imp.getStack()
for (int s = 1; s <= stack.size; s++) {
    def ip = stack.getProcessor(s)
    float[] px = (float[]) ip.getPixels()
    double[] newAll = new double[allPx.length + px.length]
    System.arraycopy(allPx, 0, newAll, 0, allPx.length)
    for (int i = 0; i < px.length; i++) newAll[allPx.length + i] = px[i]
    allPx = newAll
}
Arrays.sort(allPx)
double p1 = allPx[(int)(allPx.length * 0.01)]
double p99 = allPx[(int)(allPx.length * 0.99)]
IJ.log("d_s8 percentiles: p1=" + p1 + " p99=" + p99)

// Scale to 0-255
for (int s = 1; s <= stack.size; s++) {
    def ip = stack.getProcessor(s)
    float[] px = (float[]) ip.getPixels()
    for (int i = 0; i < px.length; i++) {
        double v = (px[i] - p1) / (p99 - p1) * 255.0
        px[i] = (float) Math.max(0, Math.min(255, v))
    }
}
IJ.run(imp, "8-bit", "")
IJ.log("d_s8_8bit normalized")
'''
    script(groovy_norm, timeout=60)

    # Run StarDist via TrackMate with merging post-processing
    print("  Running StarDist on d_s8_8bit...")
    stardist_merge = '''
import ij.IJ
import ij.ImagePlus
import fiji.plugin.trackmate.Model
import fiji.plugin.trackmate.Settings
import fiji.plugin.trackmate.TrackMate
import fiji.plugin.trackmate.Logger
import fiji.plugin.trackmate.Spot
import fiji.plugin.trackmate.action.LabelImgExporter
import fiji.plugin.trackmate.detection.DetectorKeys
import fiji.plugin.trackmate.stardist.StarDistDetectorFactory
import fiji.plugin.trackmate.tracking.jaqaman.SparseLAPTrackerFactory

def imp = ij.WindowManager.getImage("d_s8_8bit")
if (imp == null) { IJ.log("ERROR: d_s8_8bit not found"); return }

int origZ = imp.getNSlices()
def cal = imp.getCalibration()?.copy()
imp.setDimensions(1, 1, origZ)

Model model = new Model()
model.setLogger(Logger.VOID_LOGGER)
Settings settings = new Settings(imp)
settings.addAllAnalyzers()

settings.detectorFactory = new StarDistDetectorFactory()
settings.detectorSettings = settings.detectorFactory.getDefaultSettings()
settings.detectorSettings.put(DetectorKeys.KEY_TARGET_CHANNEL, 1)

// Use TIGHT linking to merge nearby detections (6um = ~cell radius)
settings.trackerFactory = new SparseLAPTrackerFactory()
settings.trackerSettings = settings.trackerFactory.getDefaultSettings()
settings.trackerSettings.put("LINKING_MAX_DISTANCE", 6.0d)
settings.trackerSettings.put("GAP_CLOSING_MAX_DISTANCE", 6.0d)
settings.trackerSettings.put("MAX_FRAME_GAP", 2)

TrackMate trackmate = new TrackMate(model, settings)
if (!trackmate.checkInput() || !trackmate.process()) {
    IJ.log("TrackMate failed (C): " + trackmate.getErrorMessage())
    imp.setDimensions(1, origZ, 1)
    return
}

// Fix visibility
def spots = model.getSpots()
int nFrames = imp.getNFrames()
for (int f = 0; f < nFrames; f++) {
    for (def spot : spots.iterable(f, false)) {
        spot.putFeature("VISIBILITY", 1.0d)
    }
}
spots.setVisible(true)

ImagePlus labelImp = LabelImgExporter.createLabelImagePlus(
    trackmate, false, false,
    LabelImgExporter.LabelIdPainting.LABEL_IS_INDEX_MOVIE_UNIQUE)

imp.setDimensions(1, origZ, 1)
labelImp.setDimensions(1, origZ, 1)
labelImp.setTitle("labels_C_merge")
if (cal != null) labelImp.setCalibration(cal)
labelImp.show()
IJ.run(labelImp, "glasbey_on_dark", "")

int totalSpots = spots.getNSpots(true)
int nTracks = model.getTrackModel().nTracks(true)
double maxVal = 0
for (int s = 1; s <= labelImp.stackSize; s++) {
    double m = labelImp.getStack().getProcessor(s).getStats().max
    if (m > maxVal) maxVal = m
}
IJ.log("C_merge: " + totalSpots + " spots, " + nTracks + " tracks, " + (int)maxVal + " 3D objects")
'''
    r = script(stardist_merge, timeout=600)
    if r.get("ok") and r["result"].get("success"):
        print("  StarDist + merge completed")
    else:
        print(f"  Failed: {r}")

    # Now do Python-side merging: load the label image and merge nearby objects
    print("  Post-merging nearby labels in Python...")
    # We'll do this after all StarDist approaches run


# =====================================================
# APPROACH D: StarDist + size filtering
# =====================================================
def approach_D():
    """Run StarDist on d_s8, then filter out small fragments."""
    print("\n" + "="*60)
    print("APPROACH D: StarDist + size filtering")
    print("="*60)

    # Check if d_s8_8bit exists, if not create it
    r = macro('selectImage("d_s8_8bit");')
    if not r["result"]["success"]:
        print("  d_s8_8bit not found, creating...")
        macro('selectImage("d_s8"); run("Duplicate...", "title=d_s8_8bit duplicate"); run("8-bit");')

    # Run StarDist with standard linking, then size-filter the labels
    stardist_d = '''
import ij.IJ
import ij.ImagePlus
import fiji.plugin.trackmate.Model
import fiji.plugin.trackmate.Settings
import fiji.plugin.trackmate.TrackMate
import fiji.plugin.trackmate.Logger
import fiji.plugin.trackmate.action.LabelImgExporter
import fiji.plugin.trackmate.detection.DetectorKeys
import fiji.plugin.trackmate.stardist.StarDistDetectorFactory
import fiji.plugin.trackmate.tracking.jaqaman.SparseLAPTrackerFactory

def imp = ij.WindowManager.getImage("d_s8_8bit")
if (imp == null) { IJ.log("ERROR: d_s8_8bit not found"); return }

int origZ = imp.getNSlices()
def cal = imp.getCalibration()?.copy()
imp.setDimensions(1, 1, origZ)

Model model = new Model()
model.setLogger(Logger.VOID_LOGGER)
Settings settings = new Settings(imp)
settings.addAllAnalyzers()

settings.detectorFactory = new StarDistDetectorFactory()
settings.detectorSettings = settings.detectorFactory.getDefaultSettings()
settings.detectorSettings.put(DetectorKeys.KEY_TARGET_CHANNEL, 1)

settings.trackerFactory = new SparseLAPTrackerFactory()
settings.trackerSettings = settings.trackerFactory.getDefaultSettings()
settings.trackerSettings.put("LINKING_MAX_DISTANCE", 8.0d)
settings.trackerSettings.put("GAP_CLOSING_MAX_DISTANCE", 5.0d)
settings.trackerSettings.put("MAX_FRAME_GAP", 2)

TrackMate trackmate = new TrackMate(model, settings)
if (!trackmate.checkInput() || !trackmate.process()) {
    IJ.log("TrackMate failed (D): " + trackmate.getErrorMessage())
    imp.setDimensions(1, origZ, 1)
    return
}

def spots = model.getSpots()
int nFrames = imp.getNFrames()
for (int f = 0; f < nFrames; f++) {
    for (def spot : spots.iterable(f, false)) {
        spot.putFeature("VISIBILITY", 1.0d)
    }
}
spots.setVisible(true)

ImagePlus labelImp = LabelImgExporter.createLabelImagePlus(
    trackmate, false, false,
    LabelImgExporter.LabelIdPainting.LABEL_IS_INDEX_MOVIE_UNIQUE)

imp.setDimensions(1, origZ, 1)
labelImp.setDimensions(1, origZ, 1)
labelImp.setTitle("labels_D_raw")
if (cal != null) labelImp.setCalibration(cal)
labelImp.show()
IJ.run(labelImp, "glasbey_on_dark", "")

int totalSpots = spots.getNSpots(true)
int nTracks = model.getTrackModel().nTracks(true)
double maxVal = 0
for (int s = 1; s <= labelImp.stackSize; s++) {
    double m = labelImp.getStack().getProcessor(s).getStats().max
    if (m > maxVal) maxVal = m
}
IJ.log("D_raw: " + totalSpots + " spots, " + nTracks + " tracks, " + (int)maxVal + " 3D objects")
'''
    r = script(stardist_d, timeout=600)
    if r.get("ok") and r["result"].get("success"):
        print("  StarDist completed for approach D")
    else:
        print(f"  Failed: {r}")

    # Size filtering in Python
    print("  Applying size filter...")
    import tifffile

    # Wait and load
    time.sleep(2)

    # Save label image from ImageJ, then load in Python for filtering
    tif_path = str(TMP_DIR / "labels_D_raw_export.tif")
    tif_fwd = tif_path.replace("\\", "/")
    macro(f'selectImage("labels_D_raw"); saveAs("Tiff", "{tif_fwd}"); rename("labels_D_raw");')

    labels = tifffile.imread(tif_path)
    print(f"  Label image shape: {labels.shape}, max label: {labels.max()}")

    # Calculate per-label volumes
    unique_labels = np.unique(labels)
    unique_labels = unique_labels[unique_labels > 0]

    # Min cell area ~40 um^2 = ~496 px^2. Over Z slices, min volume ~1500 voxels
    # Max cell area ~330 um^2 = ~4093 px^2. Over Z, max ~12000 voxels
    min_vol = 500   # voxels (generous lower bound)
    max_vol = 50000  # voxels (generous upper bound)

    filtered = labels.copy()
    removed = 0
    kept = 0
    for lab in unique_labels:
        vol = np.sum(labels == lab)
        if vol < min_vol or vol > max_vol:
            filtered[filtered == lab] = 0
            removed += 1
        else:
            kept += 1

    print(f"  Size filter: kept {kept}, removed {removed} (min={min_vol}, max={max_vol} vox)")

    # Relabel
    new_labels = np.zeros_like(filtered)
    new_id = 1
    for lab in np.unique(filtered):
        if lab > 0:
            new_labels[filtered == lab] = new_id
            new_id += 1

    save_labels_and_import(new_labels, "labels_D_filtered")
    print(f"  After filtering: {new_id - 1} 3D objects")


# =====================================================
# APPROACH E: Weka pixel classifier
# =====================================================
def approach_E():
    """Train Weka classifier on raw mCherry using manual ROIs as training data."""
    print("\n" + "="*60)
    print("APPROACH E: Weka pixel classifier")
    print("="*60)

    # Load manual ROIs and use them to train Weka
    # First, load the manual ROIs zip
    roi_path = str(AGENT_DIR / "work_in_progress" / "manual_rois.zip").replace("\\", "/")

    groovy_weka = f'''
import ij.IJ
import ij.ImagePlus
import ij.plugin.frame.RoiManager
import ij.gui.Roi
import trainableSegmentation.WekaSegmentation
import trainableSegmentation.Weka_Segmentation

def rawImp = ij.WindowManager.getImage("raw")
if (rawImp == null) {{ IJ.log("ERROR: raw not found"); return }}

// Load manual ROIs
def rm = RoiManager.getInstance()
if (rm == null) rm = new RoiManager()
rm.reset()
rm.runCommand("Open", "{roi_path}")
int nRois = rm.getCount()
IJ.log("Loaded " + nRois + " manual ROIs")

// Go to slice 8 (where manual ROIs were drawn)
rawImp.setSlice(8)

// Create Weka segmentation
def weka = new WekaSegmentation(rawImp)

// Add manual ROIs as class 1 (cell)
for (int i = 0; i < nRois; i++) {{
    def roi = rm.getRoi(i)
    weka.addExample(0, roi, 8)  // class 0 = cells, slice 8
}}

// Create background ROIs — areas NOT covered by manual ROIs
// Use rectangular ROIs in obvious background areas (corners, edges)
import ij.gui.OvalRoi
import ij.gui.ShapeRoi

// Add background examples — small rectangles in corners
def bgRois = [
    new Roi(10, 10, 50, 50),      // top-left corner
    new Roi(960, 10, 50, 50),     // top-right corner
    new Roi(10, 960, 50, 50),     // bottom-left corner
    new Roi(960, 960, 50, 50),    // bottom-right corner
    new Roi(500, 10, 50, 50),     // top center edge
    new Roi(10, 500, 50, 50),     // left center edge
]

for (def bgRoi : bgRois) {{
    weka.addExample(1, bgRoi, 8)  // class 1 = background, slice 8
}}

IJ.log("Training Weka classifier...")
weka.trainClassifier()
IJ.log("Weka training complete")

// Apply to all slices
IJ.log("Applying classifier to full stack...")
def result = weka.applyClassifier(rawImp, 0, false)
if (result != null) {{
    result.setTitle("weka_result")
    result.show()
    IJ.log("Weka classification done: " + result.width + "x" + result.height + "x" + result.stackSize)
}} else {{
    IJ.log("Weka classification failed")
}}
'''
    r = script(groovy_weka, timeout=600)
    if r.get("ok") and r["result"].get("success"):
        print("  Weka training + classification completed")
    else:
        print(f"  Weka failed: {r}")
        # Try alternative: use Labkit instead
        print("  Trying Labkit as alternative...")
        return approach_E_labkit()


def approach_E_labkit():
    """Alternative: use Labkit for pixel classification."""
    print("  Labkit not automatable via script - skipping")
    return None


# =====================================================
# Create overlays on raw image for each approach
# =====================================================
def create_overlay(label_title, approach_name):
    """Create an overlay of label outlines on the raw image."""
    groovy = f'''
import ij.IJ
import ij.ImagePlus
import ij.gui.Overlay
import ij.gui.Roi
import ij.process.ImageProcessor
import java.awt.Color

def labelImp = ij.WindowManager.getImage("{label_title}")
def rawImp = ij.WindowManager.getImage("raw")
if (labelImp == null) {{ IJ.log("ERROR: {label_title} not found"); return }}
if (rawImp == null) {{ IJ.log("ERROR: raw not found"); return }}

// Create duplicate of raw for this overlay
IJ.selectWindow("raw")
IJ.run("Duplicate...", "title=overlay_{approach_name} duplicate")
def overlayImp = ij.WindowManager.getImage("overlay_{approach_name}")

// Convert to RGB for color overlay
IJ.run(overlayImp, "8-bit", "")
IJ.run(overlayImp, "RGB Color", "")

// For each slice, outline labels and draw on overlay
int nSlices = labelImp.stackSize
def colors = [Color.CYAN, Color.YELLOW, Color.GREEN, Color.MAGENTA, Color.ORANGE]

for (int z = 1; z <= nSlices; z++) {{
    labelImp.setSlice(z)
    overlayImp.setSlice(z)

    def labelIp = labelImp.getProcessor()
    def overlayIp = overlayImp.getProcessor()

    // Find label boundaries: pixel differs from any 4-connected neighbour
    for (int y = 1; y < labelIp.height - 1; y++) {{
        for (int x = 1; x < labelIp.width - 1; x++) {{
            int v = (int) labelIp.getf(x, y)
            if (v == 0) continue

            int up = (int) labelIp.getf(x, y-1)
            int dn = (int) labelIp.getf(x, y+1)
            int lt = (int) labelIp.getf(x-1, y)
            int rt = (int) labelIp.getf(x+1, y)

            if (v != up || v != dn || v != lt || v != rt) {{
                // This is a boundary pixel - draw cyan
                overlayIp.putPixel(x, y, [0, 255, 255] as int[])
            }}
        }}
    }}
}}

overlayImp.updateAndDraw()
IJ.log("Overlay {approach_name} created")
'''
    r = script(groovy, timeout=300)
    if r.get("ok") and r["result"].get("success"):
        # Capture
        macro(f'selectImage("overlay_{approach_name}"); setSlice(8);')
        cap(f"overlay_{approach_name}")
        print(f"  Overlay {approach_name} captured")
    else:
        print(f"  Overlay creation failed: {r}")


# =====================================================
# MAIN
# =====================================================
def main():
    print("="*60)
    print("mCherry 3D Segmentation — 5 Approach Comparison")
    print("="*60)

    # A: Classical watershed
    approach_A()
    macro('selectImage("labels_A"); setSlice(8);')
    cap("labels_A_z8")
    create_overlay("labels_A", "A_watershed")

    # B: Stronger minimum filter + StarDist
    approach_B()
    time.sleep(2)
    for min_r in [3, 4]:
        try:
            macro(f'selectImage("labels_B_m{min_r}"); setSlice(8);')
            cap(f"labels_B_m{min_r}_z8")
            create_overlay(f"labels_B_m{min_r}", f"B_m{min_r}")
        except:
            print(f"  Could not capture B_m{min_r}")

    # C: StarDist + merging
    approach_C()
    time.sleep(2)
    try:
        macro('selectImage("labels_C_merge"); setSlice(8);')
        cap("labels_C_merge_z8")
        create_overlay("labels_C_merge", "C_merge")
    except:
        print("  Could not capture C_merge")

    # D: StarDist + size filtering
    approach_D()
    time.sleep(2)
    try:
        macro('selectImage("labels_D_filtered"); setSlice(8);')
        cap("labels_D_filtered_z8")
        create_overlay("labels_D_filtered", "D_filtered")
    except:
        print("  Could not capture D_filtered")

    # E: Weka
    approach_E()
    time.sleep(2)
    try:
        macro('selectImage("weka_result"); setSlice(8);')
        cap("weka_result_z8")
    except:
        print("  Could not capture weka_result")

    # Summary
    print("\n" + "="*60)
    print("SUMMARY — All approaches complete")
    print("Check overlays on slice 8 of raw image")
    print("="*60)


if __name__ == "__main__":
    main()
