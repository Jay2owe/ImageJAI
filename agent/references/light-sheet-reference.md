# Light-Sheet Microscopy Reference

Merged reference for light-sheet (SPIM/LSFM) data handling: file formats,
BigStitcher/BDV, stitching, destriping, deconvolution, tissue clearing,
whole-brain pipelines, Python ecosystem, and Fiji performance strategies.

---

## Quick Start

```bash
# Open Zeiss .czi light-sheet file
python ij.py macro 'run("Bio-Formats Importer", "open=/path/to/lightsheet.czi color_mode=Default view=Hyperstack");'

# Open HDF5/XML BigDataViewer dataset
python ij.py macro 'run("BigDataViewer", "open=/path/to/dataset.xml");'

# Open N5/Zarr dataset
python ij.py macro 'run("HDF5/N5/Zarr/OME-NGFF ...", "open=/path/to/dataset.n5");'

# Open TIFF folder as virtual stack (lazy, low RAM)
python ij.py macro 'run("TIFF Virtual Stack...", "open=/path/to/folder/");'

# Convert to BDV format
python ij.py macro 'run("Export Current Image as XML/HDF5");'
```

---

## 1. File Format Comparison

| Feature | HDF5 | N5 | Zarr | OME-Zarr (NGFF) | TIFF |
|---------|------|-----|------|-----------------|------|
| Chunked | Yes | Yes | Yes | Yes | Optional (tiles) |
| Compressed | Yes | Yes | Yes | Yes | Optional |
| Multi-resolution | Manual | Manual | Manual | Standard | No |
| Cloud-native | No (single file) | Yes | Yes | Yes | No |
| Parallel write | Limited | Yes | Yes | Yes | No |
| Fiji support | Native (BDV) | n5-ij | n5-ij | n5-ij | Native |
| Python support | h5py | z5py | zarr | zarr+ome-zarr | tifffile |
| Max file size | TB+ (single file) | No limit (dir) | No limit (dir) | No limit (dir) | 4 GB (BigTIFF unlimited) |
| Random access | Excellent | Excellent | Excellent | Excellent | Poor (unless tiled) |

**When to use what:**

| Workflow | Recommended format |
|----------|--------------------|
| Fiji/BigStitcher | XML/HDF5 or N5 |
| Python/napari/cloud | OME-Zarr |
| Archival/sharing | OME-Zarr (community standard) |
| Cross-tool | OME-Zarr (Fiji + Python + napari + OMERO) |

### Compression options (zarr/Python)

| Compressor | Speed | Ratio | Use case |
|------------|-------|-------|----------|
| `Blosc(cname="lz4")` | Fastest | Lower | Real-time, streaming |
| `Blosc(cname="zstd", clevel=3)` | Fast | Good | General purpose |
| `GZip(level=6)` | Slow | High | Archival, N5/HDF5 compat |

### Converting between formats

```bash
# TIFF stack -> BDV HDF5 (in Fiji)
python ij.py macro 'open("/path/to/stack.tif"); run("Export Current Image as XML/HDF5");'

# TIFF -> N5 (in Fiji, via BigDataProcessor2)
# Plugins > BigDataProcessor2 > open > export as N5

# TIFF -> OME-Zarr (Python)
python -c "
import tifffile, zarr
data = tifffile.imread('/path/to/stack.tif')
z = zarr.open('/path/to/out.ome.zarr', mode='w', shape=data.shape, chunks=(32,256,256), dtype=data.dtype)
z[:] = data
"
```

---

## 2. BigDataViewer (BDV)

ImgLib2-based re-slicing browser for TB-sized multi-view image sequences.
Backend for BigStitcher, Mastodon, MaMuT, ABBA.

### BDV file format (XML + HDF5)

| File | Purpose |
|------|---------|
| `dataset.xml` | Metadata: transforms, channels, angles, timepoints, voxel sizes |
| `dataset.h5` | Image data in multi-resolution chunks |

### BDV keyboard shortcuts

| Key | Action |
|-----|--------|
| `F` | Toggle source visibility |
| `1-9` | Toggle source 1-9 |
| `Shift+1-9` | Add source to current display |
| `S` | Brightness/contrast dialog |
| `F6` | Toggle 3D mode |
| `X/Y/Z` | Align to axis |
| `Shift+X/Y/Z` | Rotate 90 around axis |
| `[/]` | Previous/next timepoint |
| `I` | Show/hide interpolation |
| `Numpad 1/2/3` | Set display resolution level |

### BDV macro commands

```javascript
// Open BDV
run("BigDataViewer", "open=/path/to/dataset.xml");

// Export current image as BDV HDF5
run("Export Current Image as XML/HDF5");

// Export to N5 (faster parallel writes)
run("Export Current Image as XML/N5");
```

### BDV Groovy scripting

```groovy
import bdv.util.BdvFunctions
import net.imglib2.img.display.imagej.ImageJFunctions

def imp = ij.IJ.getImage()
def img = ImageJFunctions.wrap(imp)
def bdv = BdvFunctions.show(img, imp.getTitle())
bdv.setDisplayRange(0, 4000)
```

---

## 3. BigStitcher

Fiji plugin for stitching and multi-view fusion of large datasets. Operates
on XML/HDF5 or N5 — never loads entire dataset into RAM.

### Key capabilities

| Feature | Details |
|---------|---------|
| Tile stitching | Phase correlation, ICP refinement |
| Multi-view fusion | Weighted average, content-based, deconvolution |
| Interest point detection | DoG, automated for tile overlap |
| Transformations | Translation, rigid, affine, regularized |
| Illumination correction | Intensity adjustment between tiles |
| Output | Fused volume as TIFF, N5, or HDF5 |

### BigStitcher workflow (macro)

```javascript
// Step 1: Define dataset
run("BigStitcher", "define_dataset=[Automatic Loader (Bioformats based)] " +
    "project_filename=dataset.xml " +
    "path=/path/to/tiles/ " +
    "exclude=10 " +
    "move_tiles_to_grid=[Move Tile to Grid (Rows, Columns)] " +
    "grid_type=[Row-by-Row (Right & Down)] " +
    "tiles_x=3 tiles_y=3 " +
    "overlap_x_[%]=10 overlap_y_[%]=10");

// Step 2: Calculate pairwise shifts
run("Calculate pairwise shifts...",
    "select=dataset.xml " +
    "process_angle=[All angles] " +
    "process_channel=[All channels] " +
    "process_illumination=[All illuminations] " +
    "process_tile=[All tiles] " +
    "process_timepoint=[All Timepoints] " +
    "method=[Phase Correlation]");

// Step 3: Filter bad links
run("Filter pairwise shifts...",
    "select=dataset.xml " +
    "filter_by_link_quality " +
    "min_r=0.7 " +
    "max_shift_in_each_dimension=50");

// Step 4: Global optimization
run("Optimize globally and apply shifts...",
    "select=dataset.xml " +
    "process_angle=[All angles] " +
    "process_channel=[All channels] " +
    "process_illumination=[All illuminations] " +
    "process_tile=[All tiles] " +
    "process_timepoint=[All Timepoints] " +
    "relative=2.500 absolute=3.500 " +
    "global_optimization_strategy=[Two-Round: Metadata then Relative then Fixed tiles]");

// Step 5: Fuse
run("Fuse dataset...",
    "select=dataset.xml " +
    "process_angle=[All angles] " +
    "process_channel=[All channels] " +
    "process_illumination=[All illuminations] " +
    "process_tile=[All tiles] " +
    "process_timepoint=[All Timepoints] " +
    "bounding_box=[Currently Selected Views] " +
    "downsampling=1 " +
    "interpolation=[Linear Interpolation] " +
    "pixel_type=[16-bit unsigned integer] " +
    "interest_points_for_non_rigid=[-= Disable Non-Rigid =-] " +
    "blend produce=[Each timepoint & channel]");
```

### Multi-view fusion methods

| Method | When to use |
|--------|-------------|
| Weighted average blending | Default, fast, smooth transitions |
| Content-based (multiview) | Multiple angles, picks sharpest content |
| Multiview deconvolution | Best quality, slow, needs PSF |

### BigStitcher Groovy API

```groovy
import net.preibisch.mvrecon.fiji.spimdata.SpimData2
import mpicbg.spim.data.SpimDataException

// Load project
def data = new SpimData2.createFromXML(new File("/path/to/dataset.xml"))
def viewSetups = data.getSequenceDescription().getViewSetupsOrdered()
println("Views: ${viewSetups.size()}")
```

---

## 4. N5 and Zarr in Fiji

### Reading/writing N5

```javascript
// Open N5
run("HDF5/N5/Zarr/OME-NGFF ...", "open=/path/to/dataset.n5");

// Save as N5
run("Export N5", "n5RootPath=/path/to/output.n5 " +
    "n5DatasetName=raw " +
    "blockSizeX=128 blockSizeY=128 blockSizeZ=64 " +
    "n5Compression=gzip");
```

### Groovy N5 API

```groovy
import org.janelia.saalfeldlab.n5.*
import org.janelia.saalfeldlab.n5.imglib2.*

// Read
def n5 = new N5FSReader("/path/to/data.n5")
def attrs = n5.getDatasetAttributes("raw")
println("Dimensions: ${attrs.getDimensions()}")
println("Block size: ${attrs.getBlockSize()}")
def img = N5Utils.open(n5, "raw")

// Write
def n5w = new N5FSWriter("/path/to/output.n5")
N5Utils.save(img, n5w, "processed", new int[]{128,128,64},
    new GzipCompression())
```

### Creating OME-Zarr multi-scale pyramids (Python)

```python
import zarr, numpy as np
from skimage.transform import downscale_local_mean

data = np.load("/path/to/volume.npy")  # full-res
store = zarr.DirectoryStore("/path/to/output.ome.zarr")
root = zarr.group(store)

for level in range(4):
    factor = 2 ** level
    ds = downscale_local_mean(data, (factor, factor, factor)) if level > 0 else data
    root.create_dataset(str(level), data=ds, chunks=(32, 256, 256),
                        compressor=zarr.Blosc(cname="zstd", clevel=3))

# OME-NGFF metadata
root.attrs["multiscales"] = [{
    "version": "0.4",
    "datasets": [{"path": str(i), "coordinateTransformations": [
        {"type": "scale", "scale": [2**i * 2.0, 2**i * 0.325, 2**i * 0.325]}
    ]} for i in range(4)],
    "axes": [{"name": "z", "type": "space", "unit": "micrometer"},
             {"name": "y", "type": "space", "unit": "micrometer"},
             {"name": "x", "type": "space", "unit": "micrometer"}]
}]
```

---

## 5. Stripe Artifact Removal

Light-sheet images commonly have horizontal or vertical stripes from
absorption, scattering, or uneven illumination.

| Method | When to use | Command |
|--------|-------------|---------|
| FFT bandpass | Periodic stripes | `run("Bandpass Filter...", "filter_large=10000 filter_small=5 suppress=Horizontal")` |
| Wavelet destriping | Non-periodic | PyStripe or VSNR plugin |
| Median subtraction | Mild stripes | Subtract column/row median from each slice |
| Flatfield correction | Illumination gradient | Divide by blurred blank image |

### PyStripe (Python, recommended for batch)

```bash
pip install pystripe
pystripe --input /path/to/raw/ --output /path/to/destriped/ \
    --sigma1 256 --sigma2 256 --workers 8
```

### FFT destriping in Fiji macro

```javascript
// Remove horizontal stripes from each slice
setBatchMode(true);
for (s = 1; s <= nSlices; s++) {
    setSlice(s);
    run("Bandpass Filter...", "filter_large=10000 filter_small=3 suppress=Horizontal tolerance=5");
}
setBatchMode(false);
```

---

## 6. Deconvolution

| Method | Plugin | Speed | Quality |
|--------|--------|-------|---------|
| Richardson-Lucy (CPU) | Iterative Deconvolve 3D | Slow | Good |
| Richardson-Lucy (GPU) | CLIJ2 + DeconvolutionLab2 | Fast | Good |
| Wiener filter | DeconvolutionLab2 | Fast | Fair |
| Multi-view fusion decon | BigStitcher | Very slow | Best for multi-view |

### Iterative Deconvolve 3D (Fiji macro)

```javascript
// Generate PSF (Born & Wolf model)
run("Diffraction PSF 3D", "type=Born-Wolf " +
    "wavelength=510 na=1.0 refractive_index=1.33 " +
    "image_size_x=64 image_size_y=64 number_of_slices=32 " +
    "pixel_size_xy=325 pixel_size_z=2000");  // sizes in nm
rename("PSF");

// Deconvolve
run("Iterative Deconvolve 3D",
    "image=volume psf=PSF " +
    "iterations=15 normalize detect");
```

### CLIJ2 GPU deconvolution

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_push("volume");
Ext.CLIJ2_push("PSF");
Ext.CLIJx_deconvolveRichardsonLucyFFT("volume", "PSF", "deconvolved", 15);
Ext.CLIJ2_pull("deconvolved");
Ext.CLIJ2_clear();
```

---

## 7. Intensity Correction

### Flatfield correction

```javascript
// If you have a blank/background image:
imageCalculator("Divide create 32-bit stack", "raw", "flatfield");

// Approximate flatfield from data (large Gaussian blur):
run("Duplicate...", "title=flat duplicate");
run("Gaussian Blur 3D...", "x=50 y=50 z=10");
imageCalculator("Divide create 32-bit stack", "raw", "flat");
```

### Bleach correction (time-lapse)

```javascript
run("Bleach Correction", "correction=[Simple Ratio]");
// or
run("Bleach Correction", "correction=[Histogram Matching]");
```

---

## 8. Tissue Clearing Methods

| Method | Time | RI | Best for | Fluorescence |
|--------|------|-----|----------|-------------|
| iDISCO+ | 7-14 days | 1.56 | Whole brain, IHC | IF labels, not endogenous FP |
| uDISCO | 3-4 days | 1.56 | Whole body, small organs | Preserves some FP |
| 3DISCO | 2-3 days | 1.56 | Fast screening | Quenches FP in days |
| CUBIC | 7-14 days | 1.49-1.52 | Endogenous FP, whole brain | Excellent FP preservation |
| CLARITY/PACT | 7-14 days | 1.45-1.47 | Thick tissue, IHC | Good FP preservation |
| ECI | 1-2 days | 1.56 | Rapid clearing, screening | Preserves most FP |
| Ce3D | 1-2 days | 1.49 | Thin organs, immune tissue | Good FP preservation |
| PEGASOS | 5-7 days | 1.54 | Hard tissues, bones | Moderate FP preservation |
| SHIELD | 3-7 days | 1.47 | Protein preservation | Excellent preservation |

### Key imaging parameters after clearing

| Cleared tissue | Typical objective | Working distance | NA | RI medium |
|----------------|-------------------|------------------|----|-----------|
| iDISCO/3DISCO/uDISCO | LaVision 4x/12x | 5.7-17.6 mm | 0.28-0.53 | DBE (1.56) |
| CUBIC | Olympus 25x | 8 mm | 1.0 | CUBIC-Mount |
| CLARITY | Nikon 16x | 8 mm | 0.8 | RIMS/FocusClear |

---

## 9. Whole-Brain Analysis Pipelines

### brainglobe ecosystem (Python)

| Tool | Function |
|------|----------|
| `brainreg` | Atlas registration (elastix/ANTs) |
| `cellfinder` | Deep-learning cell detection |
| `brainrender` | 3D brain visualization |
| `brainglobe-atlas-api` | Programmatic atlas access (Allen, Waxholm, etc.) |

```bash
pip install brainglobe
```

**Registration:**
```bash
brainreg /path/to/signal/ /path/to/output/ \
    -v 5 2 2 \                      # voxel sizes (z, y, x) in um
    --atlas allen_mouse_25um \
    --orientation asl \              # anterior-superior-left
    --backend niftyreg
```

**Cell detection:**
```bash
cellfinder -s /path/to/signal/ -b /path/to/background/ \
    -o /path/to/output/ \
    -v 5 2 2 \
    --atlas allen_mouse_25um \
    --trained-model /path/to/model.h5
```

**3D visualization:**
```python
from brainrender import Scene
scene = Scene(atlas_name="allen_mouse_25um")
scene.add_brain_region("SCN", alpha=0.3, color="salmon")
scene.add_brain_region("root", alpha=0.05)
from brainrender.actors import Points
scene.add(Points(coords, radius=30, colors="red"))
scene.render(camera="sagittal", zoom=1.5)
scene.screenshot(name="my_brain", scale=2)
```

### ClearMap2 (Python, Linux only)

| Pipeline | Function |
|----------|----------|
| CellMap | Cell detection (spot detection or Ilastik-based) |
| TubeMap | Vasculature extraction and graph analysis |
| WobblyStitcher | Large tiled acquisition stitching |

```bash
git clone https://github.com/ChristophKirst/ClearMap2.git
conda env create --name clearmap --file=ClearMap_stable.yml
pip install -e .
```

```python
import ClearMap.ImageProcessing.Experts.Cells as cells
result = cells.detect_cells(
    source="/path/to/signal/",
    sink="/path/to/detected.npy",
    cell_detection_parameter={"maxima_detection": {"threshold": 700, "shape": 5}},
    processing_parameter={"processes": 10, "size_max": 100}
)
```

---

## 10. napari for Light-Sheet

Python-based multi-dimensional viewer with dask lazy loading and OpenGL rendering.

```bash
pip install "napari[all]"
pip install napari-ome-zarr napari-aicsimageio
```

| Plugin | Function |
|--------|----------|
| `napari-ome-zarr` | Multi-resolution OME-Zarr |
| `napari-aicsimageio` | CZI, ND2, LIF, OME-TIFF |
| `napari-lattice` | Lattice light-sheet analysis |
| `napari-animation` | Camera animations |

```python
import napari, dask.array as da, zarr
z = zarr.open("/path/to/dataset.zarr", mode="r")
dask_arr = da.from_zarr(z["data"])
viewer = napari.Viewer(ndisplay=3)
viewer.add_image(dask_arr, name="volume", rendering="mip",
                 scale=[5, 1, 1], contrast_limits=[0, 4000])
napari.run()
```

---

## 11. Python Libraries for Big Image Data

### tifffile -- Large TIFF I/O

```python
import tifffile
data = tifffile.memmap("/path/to/stack.tif", mode="r")  # memory-mapped, no RAM
store = tifffile.imread("/path/to/stack.tif", aszarr=True)  # lazy zarr access

# Write OME-TIFF
tifffile.imwrite("/path/to/out.ome.tif", data, tile=(256,256),
    compression="zlib", metadata={"axes":"ZYX", "PhysicalSizeX":0.325})

# Write BigTIFF (>4 GB)
tifffile.imwrite("/path/to/out.tif", data, bigtiff=True, tile=(512,512))
```

### aicsimageio -- Unified reader

```python
from aicsimageio import AICSImage
img = AICSImage("/path/to/file.czi")  # or .nd2, .lif, .ome.tif
dask_data = img.dask_data              # lazy 6D (STCZYX)
print(img.physical_pixel_sizes)        # PhysicalPixelSizes(Z=2.0, Y=0.325, X=0.325)
print(img.channel_names)               # ["DAPI", "GFP"]
ch0 = img.get_image_dask_data("ZYX", C=0, T=0)
```

### dask-image -- Lazy processing

```python
import dask_image.ndfilters, dask_image.ndmeasure
smoothed = dask_image.ndfilters.gaussian_filter(dask_arr, sigma=[1, 2, 2])
binary = smoothed > 500
labels, n = dask_image.ndmeasure.label(binary)
areas = dask_image.ndmeasure.area(dask_arr, labels, index=range(1, n+1))
areas_val = areas.compute()  # triggers actual computation
```

### pyclesperanto -- GPU processing (Python)

```python
import pyclesperanto as cle
cle.select_device("RTX")
gpu_vol = cle.push(volume)
blurred = cle.gaussian_blur(gpu_vol, sigma_x=2, sigma_y=2, sigma_z=1)
labels = cle.voronoi_otsu_labeling(gpu_vol, spot_sigma=2, outline_sigma=2)
stats = cle.statistics_of_labelled_pixels(gpu_vol, labels)
result = cle.pull(labels)
```

---

## 12. Fiji Memory and Performance

### Memory estimation

| Data type | Voxels per GB RAM |
|-----------|------------------|
| 8-bit | 1.07 billion |
| 16-bit | 537 million |
| 32-bit float | 268 million |

**Example: 2048 x 2048 x 500 x 16-bit = 3.8 GB** (allocate 2x for processing)

### Virtual stacks

```javascript
// Open TIFF as virtual (disk-backed, low RAM)
run("TIFF Virtual Stack...", "open=/path/to/large_stack.tif");

// Bio-Formats with virtual loading
run("Bio-Formats Importer",
    "open=/path/to/large.nd2 color_mode=Default view=[Standard ImageJ] use_virtual_stack");

// Open specific sub-region (Bio-Formats crop)
run("Bio-Formats Importer",
    "open=/path/to/large.czi crop " +
    "x_coordinate_1=0 y_coordinate_1=0 width_1=1024 height_1=1024");
```

### setBatchMode for speed

```javascript
setBatchMode(true);
list = getFileList("/path/to/input/");
for (i = 0; i < list.length; i++) {
    open("/path/to/input/" + list[i]);
    // processing...
    saveAs("Tiff", "/path/to/output/" + list[i]);
    close();
}
setBatchMode(false);
```

### CLIJ2 GPU acceleration (Fiji macro)

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_clear();
Ext.CLIJ2_push(input);
Ext.CLIJ2_gaussianBlur3D(input, "blurred", 2, 2, 1);
Ext.CLIJ2_thresholdOtsu("blurred", "binary");
Ext.CLIJ2_connectedComponentsLabelingBox("binary", "labels");
Ext.CLIJ2_statisticsOfLabelledPixels(input, "labels");
Ext.CLIJ2_pull("labels");
Ext.CLIJ2_clear();
```

| CLIJ2 Operation | Macro call |
|-----------------|-----------|
| Push to GPU | `Ext.CLIJ2_push(img)` |
| Pull from GPU | `Ext.CLIJ2_pull(img)` |
| 3D Gaussian | `Ext.CLIJ2_gaussianBlur3D(src, dst, sx, sy, sz)` |
| 3D Mean | `Ext.CLIJ2_mean3DBox(src, dst, rx, ry, rz)` |
| 3D Median | `Ext.CLIJ2_median3DBox(src, dst, rx, ry, rz)` |
| 3D Top Hat | `Ext.CLIJ2_topHatBox(src, dst, rx, ry, rz)` |
| Threshold Otsu | `Ext.CLIJ2_thresholdOtsu(src, dst)` |
| Connected components | `Ext.CLIJ2_connectedComponentsLabelingBox(src, dst)` |
| Voronoi-Otsu label | `Ext.CLIJx_voronoiOtsuLabeling(src, dst, spot_s, outline_s)` |
| Max Z projection | `Ext.CLIJ2_maximumZProjection(src, dst)` |
| Statistics | `Ext.CLIJ2_statisticsOfLabelledPixels(intensity, labels)` |
| Check GPU memory | `Ext.CLIJ2_reportMemory()` |
| Clear GPU | `Ext.CLIJ2_clear()` |

### Strategy decision tree

```
Dataset size < 50% available RAM?
  YES -> Open normally, process in RAM
  NO  -> Slice-by-slice processing?
    YES -> Virtual stacks + setBatchMode(true)
    NO  -> Multi-tile stitching?
      YES -> BigStitcher (lazy loading built in)
      NO  -> Single large volume for 3D analysis?
        YES -> GPU available?
          YES -> CLIJ2 (or process in blocks if > GPU RAM)
          NO  -> BigDataProcessor2 for crop/convert, then sub-volumes
        NO  -> Convert to N5/Zarr, BDV for viz, dask/Python for analysis
```

---

## 13. Domain-Specific Workflows

### Cleared brain cell counting

```javascript
// 1. Open and preprocess
run("Bio-Formats Importer", "open=/path/to/brain.czi color_mode=Default view=Hyperstack use_virtual_stack");
run("Split Channels");

// 2. Background subtract (signal channel)
selectWindow("C1-brain.czi");
run("Subtract Background...", "rolling=50 stack");

// 3. Segment cells
run("3D Objects Counter", "threshold=500 min.=50 max.=10000 objects statistics");
// or StarDist:
run("Command From Macro", "command=[de.csbdresden.stardist.StarDist2D], " +
    "args=['input':'C1-brain.czi', 'modelChoice':'Versatile (fluorescent nuclei)']");
```

### Developmental time-lapse (multi-view fusion)

```javascript
// BigStitcher handles multi-angle time-lapse natively
// 1. Define dataset with angles and timepoints
// 2. Register views per timepoint
// 3. Content-based fusion per timepoint
// 4. Export as image sequence
run("Fuse dataset...", "select=dataset.xml " +
    "process_angle=[All angles] process_timepoint=[All Timepoints] " +
    "bounding_box=[Currently Selected Views] " +
    "fused_image=[Save as new XML/HDF5] " +
    "blend produce=[Each timepoint & channel]");
```

### Organoid light-sheet (single-cell resolution)

```javascript
// Typical: multi-channel z-stack, moderate size
run("Bio-Formats Importer", "open=/path/to/organoid.czi color_mode=Default view=Hyperstack");
run("Split Channels");

// Nuclear channel -> segment with StarDist
selectWindow("C1-organoid.czi");
run("Command From Macro", "command=[de.csbdresden.stardist.StarDist2D], " +
    "args=['input':'C1-organoid.czi', 'modelChoice':'Versatile (fluorescent nuclei)']");

// Measure other channels in those ROIs
run("Set Measurements...", "area mean integrated redirect=C2-organoid.czi decimal=3");
run("Analyze Particles...", "size=20-Infinity display");
```

---

## 14. Fiji Plugins for Light-Sheet

| Plugin | Update site | Function |
|--------|-------------|----------|
| BigStitcher | BigStitcher | Multi-tile/multi-view stitching |
| BigDataViewer | (built-in) | TB-scale viewer |
| BigDataProcessor2 | BigDataTools | Lazy crop/convert large data |
| CLIJ2 | clij, clij2 | GPU-accelerated processing |
| StarDist | StarDist | Deep learning nuclei segmentation |
| Cellpose | BIOP | Deep learning cell segmentation |
| ClearVolume | ClearVolume | Real-time 3D volume rendering |
| Multiview Reconstruction | (with BigStitcher) | Multi-view deconvolution |
| n5-ij | (built-in) | N5/Zarr/OME-Zarr I/O |
| Bio-Formats | (built-in) | Read .czi, .nd2, .lif, etc. |

### BigDataProcessor2

Lazy crop/convert without loading into RAM:

```
Plugins > BigDataProcessor2
  - Open: TIFF sequence, HDF5, N5
  - Crop, bin, select channels (all lazy)
  - Export to BDV HDF5, N5, or TIFF
```

### SCIFIO lazy loading

```
Edit > Options > ImageJ2 > Use SCIFIO when opening files (check)
```
Automatically uses cell-based lazy loading when dataset exceeds available memory.

---

## 15. Gotchas and Tips

| Problem | Solution |
|---------|----------|
| Out of memory opening large stack | Use virtual stacks or BDV |
| Stitching fails at tile overlap | Increase overlap (15-20%), check illumination correction |
| Stripe artifacts after stitching | Destripe BEFORE stitching, not after |
| Multi-view registration poor | Use interest points (DoG), not phase correlation |
| Z-resolution much worse than XY | Expected (sheet thickness >> diffraction limit). Consider multi-view deconvolution |
| Different intensities per tile | Enable illumination correction in BigStitcher |
| Slow Fiji with TB data | Convert to N5/Zarr, use BDV for navigation |
| GPU out of memory (CLIJ2) | Process in blocks, release intermediates with `Ext.CLIJ2_release()` |
| Bio-Formats slow on .czi | Convert to OME-Zarr or N5 first |
| Tissue autofluorescence | Use autofluorescence channel for registration, separate from signal |
| Bleaching over z-stack | Acquire bidirectionally, apply bleach correction |
| Refractive index mismatch | Match immersion/mounting media to clearing protocol RI |

### Light-sheet-specific macro tips

```javascript
// Always check calibration after opening
getVoxelSize(vx, vy, vz, unit);
print("Voxel: " + vx + " x " + vy + " x " + vz + " " + unit);

// Downsample before heavy 3D processing
run("Scale...", "x=0.5 y=0.5 z=1.0 interpolation=Bilinear process create");

// Save intermediate results for large pipelines (don't re-run from start)
saveAs("Tiff", "/path/to/intermediate.tif");
```

---

## 16. SimpleITK Registration (Python)

For atlas registration or multi-view alignment beyond BigStitcher:

```python
import SimpleITK as sitk

fixed = sitk.ReadImage("/path/to/fixed.tif", sitk.sitkFloat32)
moving = sitk.ReadImage("/path/to/moving.tif", sitk.sitkFloat32)
fixed.SetSpacing([0.325, 0.325, 2.0])
moving.SetSpacing([0.325, 0.325, 2.0])

reg = sitk.ImageRegistrationMethod()
reg.SetMetricAsMattesMutualInformation(numberOfHistogramBins=50)
reg.SetMetricSamplingStrategy(reg.RANDOM)
reg.SetMetricSamplingPercentage(0.01)
reg.SetInterpolator(sitk.sitkLinear)
reg.SetOptimizerAsGradientDescent(learningRate=1.0, numberOfIterations=200)
reg.SetShrinkFactorsPerLevel([4, 2, 1])
reg.SetSmoothingSigmasPerLevel([2, 1, 0])
reg.SmoothingSigmasAreSpecifiedInPhysicalUnitsOn()

init = sitk.CenteredTransformInitializer(fixed, moving, sitk.AffineTransform(3),
    sitk.CenteredTransformInitializerFilter.GEOMETRY)
reg.SetInitialTransform(init, inPlace=False)
transform = reg.Execute(fixed, moving)

result = sitk.Resample(moving, fixed, transform, sitk.sitkLinear, 0.0, moving.GetPixelID())
sitk.WriteImage(result, "/path/to/registered.tif")
```
