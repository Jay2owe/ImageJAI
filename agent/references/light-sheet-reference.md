# Light-Sheet Microscopy Reference

Merged reference for light-sheet (SPIM/LSFM) data handling: clearing
chemistry, sample prep, acquisition systems, file formats, BigStitcher/BDV,
stitching, destriping, deconvolution, whole-organ pipelines, Python
ecosystem, HPC, and Fiji performance strategies.

Covers LSFM / SPIM acquisition outputs from common commercial and
open-source platforms (Z.1/Z.2, Lightsheet 7, Ultramicroscope II/Blaze,
ClearScope, mesoSPIM v1/v2, MuVi-SPIM, lattice light-sheet, Mesolens),
tissue clearing protocols (solvent / aqueous / hydrogel families plus
expansion microscopy), BigStitcher multi-tile/multi-view reconstruction
including BigStitcher-Spark on clusters, BigDataViewer (BDV) XML/HDF5,
N5, Zarr v2/v3, the OME-Zarr / NGFF spec at 0.4–0.5, conversion via
bioformats2raw + raw2ometiff, destriping (PyStripe / VSNR / wavelet /
FFT), deconvolution (CPU/GPU/multi-view/Wiener), whole-organ pipelines
(BrainGlobe, ClearMap2 CellMap + TubeMap, VesselExpress, TeraStitcher,
CUBIC-Cloud), napari + napari-ome-zarr + dask, dask-image, pyclesperanto,
CLIJ2, HPC strategies (SLURM, Apptainer/Singularity, S3/GCS Zarr), and
Fiji memory/performance strategies for TB-scale data.

Cross-links: see `large-dataset-optimization-reference.md` for generic
JVM tuning, virtual stacks, headless mode, and OME-TIFF/N5/Zarr deep
comparisons; see `brain-atlas-registration-reference.md` for atlas
registration of cleared whole-brain volumes (BrainGlobe brainreg /
ClearMap registration / ABBA for serial 2D sections); see
`deconvolution-reference.md` for PSF measurement and DeconvolutionLab2
algorithm choice; see `3d-visualisation-reference.md` for rendering
choices; see `clij2-gpu-reference.md` for the full CLIJ2 op catalogue.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript.
Use `python probe_plugin.py "Plugin..."` to discover any installed plugin's
parameters at runtime.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "How do I open a .czi / .nd2 / .lif / XML+HDF5 / N5 / Zarr file?" | §2 quick start, §5.1, §13.1 |
| "Which chunked format should I use — HDF5 vs N5 vs Zarr vs OME-Zarr vs TIFF?" | §2.1 |
| "Which compressor for zarr?" | §2.1 compression options |
| "How do I convert TIFF to BDV / N5 / OME-Zarr?" | §2.1 converting between formats |
| "What are the BDV keyboard shortcuts / macro commands / Groovy API?" | §3 |
| "How do I stitch tiles with BigStitcher?" | §4 workflow macro |
| "Which multi-view fusion method should I use?" | §4 methods table |
| "How do I read/write N5 from macro or Groovy? Build an OME-Zarr pyramid?" | §5 |
| "How do I remove stripe artifacts?" | §6 (PyStripe, FFT destripe) |
| "How do I deconvolve a light-sheet volume (CPU vs GPU vs multi-view)?" | §7 |
| "Flatfield / bleach correction?" | §8 |
| "Which clearing protocol suits FP preservation / speed / tissue?" | §9 |
| "What objective / WD / NA / RI for cleared tissue?" | §9 imaging parameters |
| "Whole-brain cell counting / registration / 3D viz?" | §10 (brainglobe, ClearMap2) |
| "How do I view data in napari with dask lazy loading?" | §11 |
| "Python libraries for big image data (tifffile, aicsimageio, dask-image, pyclesperanto)?" | §12 |
| "How much RAM do I need? Virtual stacks? CLIJ2 GPU calls?" | §13 |
| "Decision tree: how do I pick a strategy for dataset size X?" | §13 strategy decision tree |
| "Cleared brain cell count / multi-view time-lapse / organoid pipeline?" | §14 |
| "Which Fiji plugins are relevant for light-sheet?" | §15 |
| "Why did stitching/stripes/registration fail? Z-resolution poor?" | §16 gotchas |
| "How do I do atlas/multi-view registration in Python (SimpleITK)?" | §17 |
| "Solvent vs aqueous vs hydrogel clearing — what's the trade-off?" | §9, §18 |
| "How much shrinkage/expansion does my protocol cause?" | §18.4, §16 |
| "Expansion microscopy — proExM / U-ExM / X10 — which one?" | §18.5 |
| "How do I embed in agarose / orient the sample / pick a reference channel?" | §19 |
| "Which LSFM hardware platform produces my data and what's the native format?" | §20 |
| "What does an OME-Zarr 0.4 / 0.5 store actually look like on disk?" | §21 |
| "How do I convert .czi/.nd2/.lif to OME-Zarr with bioformats2raw?" | §21.4 |
| "BigStitcher: virtual fusion vs fuse-to-disk vs Spark — when do I pick which?" | §22 |
| "How do I run BigStitcher on SLURM / cluster / cloud?" | §22.4, §25 |
| "TeraStitcher vs BigStitcher — when to pick TeraStitcher?" | §22.5 |
| "Vasculature analysis — TubeMap vs VesselExpress?" | §23 |
| "Imaris IMS / syGlass / Vesalius3D / Arivis — what works at TB scale?" | §24 |
| "End-to-end pipeline from raw to atlas-registered counts?" | §26 |
| "OOM in BigStitcher Fuse Image — how do I fix it?" | §22.3, §27 |
| "Common end-to-end failure modes and recovery strategy?" | §27 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '`<term>`' light-sheet-reference.md` to jump.

### A
`ABBA` §3 · `aicsimageio` §11, §12 · `affine` §4 · `allen_mouse_25um` §10 · `ANTs` §10 · `autofluorescence` §16

### B
`Bandpass Filter...` §6 · `BDV` §3, §15 · `BigDataProcessor2` §2.1, §15 · `BigDataViewer` §2, §3, §15 · `BigStitcher` §4, §15 · `BigTIFF` §2.1, §12 · `Bio-Formats` §2, §13, §15, §16 · `bleach correction` §8 · `Blosc (lz4 / zstd)` §2.1, §5 · `brainglobe` §10 · `brainreg` §10 · `brainrender` §10 · `Born-Wolf PSF` §7

### C
`Ce3D` §9 · `CellMap` §10 · `Cellpose` §15 · `cellfinder` §10 · `chunked formats` §2.1 · `CLARITY` §9 · `ClearMap2` §10 · `ClearVolume` §15 · `CLIJ2` §7, §13, §15 · `CLIJ2 GPU deconvolution` §7 · `CLIJ2 macro ops` §13 · `cloud-native` §2.1 · `compression options` §2.1 · `Content-based multiview fusion` §4 · `CUBIC` §9

### D
`dask-image` §12 · `dask.from_zarr` §11 · `dataset.xml / dataset.h5` §3 · `DBE (1.56)` §9 · `DeconvolutionLab2` §7 · `deconvolution` §4, §7 · `Diffraction PSF 3D` §7 · `3DISCO` §9 · `DoG (interest points)` §4, §16 · `downscale_local_mean` §5

### E
`ECI` §9 · `Export Current Image as XML/HDF5` §2, §3 · `Export Current Image as XML/N5` §3 · `Export N5` §5

### F
`FFT destriping` §6 · `Fiji memory` §13 · `flatfield correction` §6, §8 · `Fuse dataset...` §4, §14

### G
`Gaussian Blur 3D...` §8 · `GzipCompression` §2.1, §5 · `gzip (N5)` §5

### H
`HDF5` §2.1, §3 · `HDF5/N5/Zarr/OME-NGFF ...` §2, §5

### I
`iDISCO+` §9 · `illumination correction` §4, §16 · `ImageJFunctions.wrap` §3 · `ImgLib2` §3 · `interest points` §4, §16 · `Iterative Deconvolve 3D` §7

### L
`LaVision 4x/12x` §9 · `lazy loading` §2.1, §15 · `light-sheet specifics` §16

### M
`MaMuT` §3 · `Mastodon` §3 · `memmap (tifffile)` §12 · `Multiview Reconstruction` §15 · `multi-view deconvolution` §4, §7, §16 · `multi-view fusion` §4

### N
`N5` §2.1, §3, §5, §15 · `N5FSReader / N5FSWriter` §5 · `N5Utils` §5 · `n5-ij` §15 · `napari` §11 · `napari-aicsimageio` §11 · `napari-animation` §11 · `napari-lattice` §11 · `napari-ome-zarr` §11 · `niftyreg` §10 · `numpy` §5

### O
`OME-NGFF` §2.1, §5 · `OME-TIFF` §11, §12 · `OME-Zarr` §2.1, §5, §11 · `OMERO` §2.1

### P
`PACT` §9 · `parallel write` §2.1 · `PEGASOS` §9 · `phase correlation` §4, §16 · `Points (brainrender)` §10 · `PSF` §7 · `pyclesperanto` §12 · `PyStripe` §6

### R
`random access` §2.1 · `refractive index (RI)` §9, §16 · `rigid` §4 · `Richardson-Lucy` §7 · `RIMS / FocusClear` §9

### S
`Scale...` §16 · `SCIFIO lazy loading` §15 · `SHIELD` §9 · `SimpleITK` §17 · `SpimData2` §4 · `SPIM/LSFM` §1, §2 · `StarDist` §14, §15 · `setBatchMode` §6, §13 · `Stitching` (see BigStitcher §4) · `Subtract Background...` §14

### T
`tifffile` §12 · `tifffile.imwrite` §12 · `TIFF Virtual Stack...` §2, §13 · `tile stitching` §4 · `TubeMap` §10 · `translation` §4

### U
`uDISCO` §9

### V
`virtual stacks` §13 · `VSNR` §6

### W
`WobblyStitcher` §10 · `Wiener filter` §7

### X
`XML/HDF5` §2.1, §3, §4, §15 · `XML/N5` §3

### Z
`z5py` §2.1 · `Zarr` §2.1, §5, §11 · `zarr.Blosc` §5 · `zarr.DirectoryStore` §5

---

## §2 Quick Start

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

## §2.1 File Format Comparison

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

## §3 BigDataViewer (BDV)

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

## §4 BigStitcher

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

## §5 N5 and Zarr in Fiji

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

## §6 Stripe Artifact Removal

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

## §7 Deconvolution

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

## §8 Intensity Correction

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

## §9 Tissue Clearing Methods

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

## §10 Whole-Brain Analysis Pipelines

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

## §11 napari for Light-Sheet

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

## §12 Python Libraries for Big Image Data

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

## §13 Fiji Memory and Performance

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

## §14 Domain-Specific Workflows

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

## §15 Fiji Plugins for Light-Sheet

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

## §16 Gotchas and Tips

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

## §17 SimpleITK Registration (Python)

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

---

## §18 Tissue Clearing — Analyst's View

§9 lists the protocols at a glance. This section is the deeper view that
matters when you have to *analyse* cleared data: what the chemistry does
to fluorescence, geometry, and downstream measurement.

### §18.1 Three chemistry families

| Family | Mechanism | RI | Speed | FP preservation | Antibody penetration | Sample shrinkage |
|--------|-----------|----|-------|----------------|----------------------|------------------|
| Solvent (DISCO) | Dehydrate + delipidate + RI-match in BABB / DBE / ECi | 1.55–1.56 | Fast (1–14 d) | Poor (eGFP often quenched in days, except FDISCO/uDISCO) | Good (small molecule) | Significant (≈10–30 % linear) |
| Aqueous (CUBIC, ScaleA2/SF, SeeDB, RTF, FRUIT) | Hyper-hydration + lipid removal + sucrose / urea / formamide | 1.46–1.52 | Slow–medium (3–14 d) | Excellent (eGFP, mCherry, td, tags retained) | Moderate (large IgG slow) | Mild expansion (≈10 % CUBIC, > with CUBIC-X) |
| Hydrogel (CLARITY, PACT, PARS, SHIELD) | Acrylamide infusion + lipid removal + RI-match in RIMS / FocusClear / EasyIndex | 1.45–1.47 | Slow (2–4 wk for whole organ) | Good–excellent (SHIELD best) | Good after lipid removal | Reversible swelling, ≈5 % shrink at imaging |

### §18.2 Solvent (DISCO family) — depth

| Variant | Year | Fixative + dehydration | RI medium | Notes |
|---------|------|------------------------|-----------|-------|
| BABB / Spalteholz | 1914/2007 | Methanol → BABB (benzyl alcohol + benzyl benzoate, 1:2) | 1.559 | Original; quenches FP fast |
| 3DISCO | 2012 | THF dehydration → BABB / DBE | 1.56 | Quenches eGFP within days; OK for IF on fixed tissue |
| iDISCO+ | 2016 | Methanol → DCM → DBE; ROCK-incubation step for IgG | 1.56 | Whole adult mouse brain in 7–14 d; standard for c-Fos |
| uDISCO | 2016 | tert-butanol dehydration → BABB-D4 (DPE / BABB) | 1.56 | Better FP preservation than 3DISCO |
| FDISCO / sDISCO | 2018/2019 | THF + low-pH adjustments | 1.56 | Fluorescence-preserving DISCO; eGFP holds for weeks |
| vDISCO | 2019 | iDISCO + nanobody boost (anti-GFP / anti-RFP) | 1.56 | Whole-body adult mouse with FP signal retention |
| ECi (ethyl cinnamate) | 2017 | Ethanol → ECi | 1.558 | Fast (1–2 d), preserves FP, organic but less toxic; popular for organs |
| MASH (MAcromolecular Staining + Histopathology) | 2019 | Methanol-based with deep histochemical stains | ≈1.55 | Whole human brain hemispheres |
| PEGASOS | 2018 | Decalcify + decolorise + tert-butanol → BB-PEG | 1.54 | Bone, hard tissues |

**Rules of thumb:**
- DISCO solvents (DCM, DBE, BABB) **dissolve plastics** — store in glass, use PTFE / FEP tubing, remove plastic chamber inserts. Most LSFM chambers ship with chemically resistant glass walls; double-check the manufacturer's compatibility list.
- DBE (dibenzyl ether) oxidises with light/air; store under argon or buy fresh — yellowed DBE causes diffuse autofluorescence and high background in 488/561.
- For agarose-embedded samples in solvent: do NOT use standard low-melt agarose; it dissolves. Use **phytagel** or directly mount the cleared tissue in a heat-shrink tube / glue / quartz capillary.
- iDISCO+ Renier protocol expects **fixation first, then permeabilisation/blocking, then primary 4–7 d, secondary 4–7 d, then dehydration + clearing**. Antibody penetration depth is the bottleneck, not clearing time.

### §18.3 Aqueous (CUBIC family + Scale + SeeDB)

| Variant | Year | Composition | RI | Notes |
|---------|------|-------------|----|-------|
| Scale A2 | 2011 | Urea + glycerol + Triton | 1.38 | Slow (months for whole brain); FP-friendly |
| SeeDB | 2013 | Saturated fructose | 1.49 | Fast surface clearing, poor depth |
| CUBIC-1 / Reagent-1 | 2014 | Urea + Triton + quadrol (delipidation) | 1.38 (immersion) | Whole brain in 7–14 d |
| CUBIC-2 / Reagent-2 | 2014 | Sucrose + urea + triethanolamine | 1.49 (RI match) | Final imaging medium |
| CUBIC-X | 2018 | Urea + imidazole + antipyrine, expansion variant | 1.46 | 2× linear isotropic expansion + RI match |
| CUBIC-HV | 2020 | Optimised 3D antibody staining for whole organ | matches CUBIC-2 | Whole adult marmoset hemisphere; needs 1–2 wk staining |
| CUBIC-HistoVIsion | 2021 | Stabilised tissue + accelerated staining | matches CUBIC-2 | Reduces antibody dose |
| ScaleS / ScaleSF | 2015/2018 | Sorbitol + urea + DMSO | 1.467 | Mild on FP, good for vibratome 200–500 µm slabs |
| RTF | 2017 | Triethanolamine + formamide + Tween | 1.45 | Ultrafast (hours) for thin slabs |
| FRUIT | 2018 | Fructose + urea | 1.48 | Cheap, mild |
| Ce3D | 2017 | N-methylacetamide + Histodenz | 1.49 | Lymphoid / immune organs |

**Rules of thumb:**
- CUBIC reagents are viscous and absorb water — keep capped, freshly mix CUBIC-1 every few days.
- All FP signals **decay slowly** even in CUBIC; image within ≈4 weeks of clearing if quantitative photometry matters.
- Aqueous-cleared tissue is **soft and fragile** — agarose embedding (low-melt 1.5–2 %) is essential for stable LSFM imaging.

### §18.4 Hydrogel (CLARITY / PACT / SHIELD)

| Variant | Year | Acrylamide + crosslink | Delipidation | RI medium | Notes |
|---------|------|-----------------------|--------------|-----------|-------|
| CLARITY (active) | 2013 | 4 % acrylamide + 0.05 % bis | Active electrophoretic SDS at 37–50 °C | FocusClear (RI 1.45) | Fast (days) but harsh on tissue; FP loss possible |
| Passive CLARITY | 2014 | 4 % acrylamide | Passive SDS at 37 °C | FocusClear / RIMS | Slow (2–4 wk whole brain) but no electrophoresis rig |
| PACT | 2014 | 4 % acrylamide, perfusion | Passive SDS | RIMS (refractive index matching solution) | Standard, broad use |
| PARS | 2014 | PACT + perfusion via vasculature | Whole-body | RIMS | Whole-body rodents |
| SHIELD | 2018/2019 | P3PE (polyglycerol-3-polyglycidyl ether) replaces acrylamide | Passive SDS at 55 °C, 3–4 d | EasyIndex / sRIMS / dipped media (RI 1.46) | Best protein, FP, mRNA preservation; tissue is heat-resistant; LifeCanvas commercial kit |
| MAP (Magnified Analysis of Proteome) | 2016 | High acrylamide, heat-denatures | SDS | RI match | Combines clearing + 4× expansion |

**SHIELD specifics (Park 2018):**
- Three solutions: SHIELD-Perfusion (transcardial in vivo / immersion), SHIELD-OFF (24 h at 4 °C), SHIELD-ON (1 d at RT shaking) — covalently links biomolecules through epoxide rings.
- Clearing then proceeds with passive SDS (200 mM SDS + 20 mM sodium sulfite + 20 mM boric acid, pH 9, 55 °C, 3–4 d for thick slabs; 1–2 wk for whole adult mouse brain).
- After clearing, immunolabel using SmartBatch or Stochastic Electrotransport (SE) to push antibody in 1–3 d instead of weeks.
- Imaging in EasyIndex (LifeCanvas) or sRIMS (sucrose-based RIMS), RI 1.46.

### §18.5 Expansion microscopy (when LSFM and ExM combine)

| Variant | Year | Expansion | Anchor / digestion | FP preservation | Resolution gain |
|---------|------|-----------|--------------------|-----------------|-----------------|
| ExM (original) | 2015 | ~4× iso | Trifunctional methacrylamide + proteinase K | Fluorophores re-stained after digest (FP lost) | ≈70 nm effective |
| proExM | 2016 | ~4× iso | Acryloyl-X anchors proteins, proteinase K mild digest | FPs retain >50 % intensity after expansion | ≈70 nm |
| MAP | 2016 | ~4× iso | High monomer + heat denaturation | Good | ≈70 nm |
| iExM | 2017 | ~16–25× | Re-embedding gels in second swellable matrix | Poor | ≈25 nm |
| X10 | 2017 | ~10× iso | DMAA + sodium acrylate | Antibodies after expansion | ≈25 nm |
| U-ExM | 2019 | ~4× iso | Formaldehyde + acrylamide pre-fix; preserves ultrastructure | Antibodies after expansion (FP partially) | ≈70 nm |
| TREx | 2021 | ~10× iso | Single-step ~10×, simpler than iExM | Antibodies after | ≈25 nm |
| pan-ExM | 2022 | ~16–24× | Two-step gel, pan-stain proteome | Antibodies after | ≈20–30 nm |

**Practical analyst notes:**
- Expanded gels are mostly water — **autofluorescence is dramatically reduced** (signal-to-background often improves) but absolute intensity per object drops by the cube of the expansion factor; integrate longer or sum slices.
- Voxel sizes in metadata refer to the *expanded* coordinate. To report biological scale, divide by the expansion factor — measure expansion factor empirically on each gel (e.g., gel diameter pre- vs post-expansion, or fiducial bead spacing).
- Light-sheet imaging of 4×-expanded mouse brain slabs is feasible on mesoSPIM-class systems with low-NA dipping objectives in water.

### §18.6 Decision matrix

| Priority | Pick |
|----------|------|
| Speed (≤2 days), small organ | ECi, RTF, ScaleSF |
| Endogenous FP preservation, whole brain | CUBIC, FDISCO, vDISCO, SHIELD |
| Maximum antibody penetration (whole adult brain IHC) | iDISCO+, SHIELD + SmartBatch / SE |
| Hard tissue (bone, calcified) | PEGASOS |
| Whole-body adult rodent | vDISCO, PEGASOS, PARS |
| Lowest tissue distortion (geometry-critical) | SHIELD, ECi, FDISCO |
| Super-resolution-like detail without STED/SMLM | proExM / U-ExM / TREx + LSFM |
| Long-term storage of cleared sample | SHIELD-stabilised in EasyIndex (months–years) |

### §18.7 What clearing does to your measurements

| Phenomenon | Effect on analysis | Mitigation |
|-----------|-------------------|------------|
| Tissue shrinkage (DISCO) | Cell density appears inflated; ROIs smaller than fresh tissue | Measure linear shrinkage on fiducials, scale results, or report density per cleared volume |
| Tissue expansion (CUBIC-X, ExM) | Density appears reduced; ROIs larger | Same — apply expansion factor; never compare across different clearing protocols without normalisation |
| RI mismatch at sample/medium boundary | Spherical aberration, axial PSF elongation, focus drift with depth | Match dipping objective to clearing RI; use dipping-cap correction collars; deconvolve with depth-dependent PSF |
| Residual lipid pockets / under-clearing | Local opacity, dark "shadows" propagating in z | Extend clearing time; check by holding sample over text |
| Autofluorescence (lipofuscin, blood) | False positives in 488/561 channels | Acquire a dedicated autofluorescence channel; PBS-perfuse to remove blood; quench (CuSO4, NaBH4, Sudan Black) before clearing |
| Antibody penetration gradient | Stained "shell" only — false negative deep | Test 200–500 µm vibratome slab as positive control; image autofluorescence to detect gradient; use SmartBatch / SE |
| Photobleaching during stitching | First tile dimmer than later tiles after correction; or vice-versa | Acquire in low-power mode; use BigStitcher illumination correction; consider acquisition order randomisation |

### §18.8 Quick autofluorescence subtraction (Fiji macro)

```javascript
// Two-channel: signal (e.g. Cy5) and autofluo (e.g. 488 from same blood/lipofuscin)
// Estimate autofluo coefficient k from a "no signal" region, then subtract
selectWindow("C1-signal");
sigID = getImageID();
selectWindow("C2-autofluo");
autoID = getImageID();

// Pick autofluo-only region with a rectangle BEFORE running:
// run("Specify..."); → set ROI on a known background region with no real signal

selectImage(sigID); getStatistics(area, sigMean);
selectImage(autoID); getStatistics(area, autoMean);
k = sigMean / autoMean;
print("Autofluorescence coefficient k = " + d2s(k, 4));

imageCalculator("Subtract create 32-bit stack", "C1-signal", "C2-autofluo");
// k != 1 → multiply autofluo first:
// run("Multiply...", "value=" + k + " stack"); imageCalculator("Subtract create 32-bit stack", ...);
rename("signal_minus_AF");
```

---

## §19 Sample Preparation for Analysis

The single biggest determinant of stitching/registration success is what
happened on the bench, not what you do in software.

### §19.1 Embedding

| Material | Solvents tolerated | Typical concentration | Notes |
|----------|-------------------|------------------------|-------|
| Low-melt agarose | Aqueous (CUBIC, water, RIMS) | 1.5–2 % | Standard; melts in DISCO solvents |
| Phytagel / Gelrite | Aqueous + many solvents | 1 % w/v in water | Tougher than agarose, survives ECi/uDISCO short term |
| Photopolymerised resin (e.g., NOA 81) | All solvents | n/a | Permanent mount; for archival reference samples |
| Quartz capillary + plug | All solvents | n/a | Z.1/Z.2 capillaries (size 1–5); plug ends with agarose or phytagel |
| FEP tube | All solvents | inner Ø 1–10 mm | Mesospim/Ultramicroscope-friendly; RI ≈ 1.34 (won't disappear in solvent) |
| Glass bottom dish + glue | Limited (aqueous) | n/a | Cyanoacrylate / nail polish for fast pinning |

**Workflow tips:**
- Embed in the **same medium** the sample will image in (or an RI-compatible step-up: agarose-in-PBS → equilibrate in clearing → final medium).
- Bubble-free embedding: degas agarose under vacuum for 5 min before pouring; orient sample with a hot needle while still warm.
- For Z.1/Z.2 capillaries: extrude only 1–2 mm of agarose plug per imaging session; mark orientation with a notch.

### §19.2 Orientation conventions

| Convention | Used by | Meaning |
|------------|---------|---------|
| RAS / SLA / ASL | BrainGlobe, ITK | First letter = +X direction (R=right, A=anterior, S=superior). Mouse brain in CCFv3 is typically `asl` |
| LPS | DICOM, ANTs | left, posterior, superior |
| Standard mouse coronal | Most atlases | Dorsal up, anterior away from viewer |

```bash
# Always tell brainreg the orientation explicitly — wrong orientation is
# the #1 cause of registration failure.
brainreg /signal.tif /output/ -v 5 5 5 --atlas allen_mouse_25um --orientation asl
```

Use a **fiducial mark** at acquisition: a notch on the lateral cortex, an
asymmetric scratch in the agarose, or a labeled bead on one hemisphere.
This is invaluable when later code needs to know L vs R after a rotation
during stitching.

### §19.3 Reference channel for registration

Atlas registration (BrainGlobe, ClearMap, ABBA) needs an image whose
contrast resembles the atlas's reference template. For Allen CCFv3 the
reference is essentially **autofluorescence at 488 nm** + Nissl-like
contrast.

| Reference channel | When it works | Notes |
|-------------------|--------------|-------|
| Autofluorescence at 488 nm | DISCO, CUBIC, SHIELD all good | Standard for cleared brain → CCFv3 |
| DAPI / TO-PRO-3 / DRAQ5 | Thin-tissue or single-section | Allen reference is Nissl-like, so DAPI alone can work poorly; register to the Allen Nissl atlas instead of CCFv3 average template |
| NeuroTrace | Nissl analogue, retains in CUBIC and SHIELD | Good for FP-preserving protocols |
| Lectin / vasculature | When registering vasculature data | Use a vessel-aware atlas (rare); usually still register on a co-acquired autofluorescence channel |

**Acquisition guidance:**
- Always acquire a dedicated autofluorescence channel even if you "just want the signal" — registration requires it later. 488 nm at low power is usually sufficient.
- Match Z step to atlas resolution (e.g., 25 µm CCFv3 → image at ≤10 µm Z and downsample to 25 µm isotropic for registration).
- For cleared-tissue registration, skull/extracranial tissue **must** be removed before clearing or it will dominate autofluorescence and drag the registration.

### §19.4 Acquisition order for analysis-friendly data

1. **PBS-perfuse** to remove blood (haem autofluorescence dominates 488–561).
2. **Block / quench autofluorescence** if relevant (CuSO₄, NaBH₄, Sudan Black, photobleaching pre-stain).
3. **Fix → clearing** as per protocol.
4. **Image highest-priority channel first** (most photobleach-sensitive).
5. **Image autofluorescence channel last** — it bleaches less and is needed for registration even if your sample has been hammered.
6. Acquire **bidirectional Z** if your stage supports it: cuts acquisition time but introduces a half-step shift between odd/even slices that BigStitcher can correct.

---

## §20 LSFM Hardware Platforms

You can't fully separate analysis from acquisition. Knowing what produced
the data tells you the native format, voxel anisotropy, expected
artefacts, and which Bio-Formats reader to expect.

### §20.1 Commercial systems

| System | Vendor | Sheet thickness | XY pixel | Native format | Common cleared use |
|--------|--------|-----------------|----------|--------------|--------------------|
| Lightsheet Z.1 / Z.2 | Zeiss | 4–7 µm | 0.3–0.6 µm | `.czi` (CZI XY-tiles + multi-view) | Live SPIM, Zebrafish, organoids; chambers are aqueous-only by default |
| Lightsheet 7 | Zeiss | 4–7 µm dual-side | 0.2–0.6 µm | `.czi` | Cleared-friendly cuvette options (DBE, ECi, CUBIC RI sets) |
| Ultramicroscope II | LaVision/Miltenyi | 4–10 µm | 0.6–4 µm | `.tif` series + `.txt` metadata | iDISCO/uDISCO whole-brain workhorse; static sample |
| Ultramicroscope Blaze | Miltenyi | 4–8 µm dual-side | 0.6–2 µm | `.tif` (configurable) or proprietary | Faster Ultramicroscope II successor; tile-stitched |
| ClearScope | MBF Bioscience | 5 µm | 0.5–1 µm | `.tif` series | Cleared brain; integrates with Neurolucida 360 |
| Mesolens | Edinburgh | (epifluo, not LSFM) | 0.7 µm over 6×6 mm FOV | `.tif` | Listed because mesoscale workflows often combine Mesolens + LSFM |
| Lattice Lightsheet 7 | Zeiss / orig. Janelia | 0.4–1 µm | 0.1 µm | `.czi` | Live thin samples; not whole-organ |
| Triple-View Lattice (Janelia 3i) | 3i | 0.4 µm | 0.1 µm | OME-TIFF + custom | High-NA live |
| CFLSM / Andor Dragonfly LS | Andor | spinning-disk hybrid | 0.2 µm | `.ims` / `.tif` | Live, smaller samples |

### §20.2 Open-source / academic platforms

| System | Sheet | XY pixel | Format | Notes |
|--------|-------|----------|--------|-------|
| mesoSPIM v1 (Voigt 2019) | 4–10 µm | ≈3 µm | `.tif` series + JSON | Mostly tile-scanned cleared mouse brain |
| mesoSPIM v2 / Benchtop (Negwer 2024) | 1.5 µm lateral / 3.3 µm axial | 0.5–3 µm | OME-TIFF + Zarr export | Smaller footprint, drop-in BigStitcher path |
| openSPIM / openSPIN | 5–10 µm | 0.6 µm | `.tif` | DIY single-view SPIM |
| MuVi-SPIM (Krzic 2012) | 5 µm | 0.4 µm | `.h5` BDV | Multi-view, embryo development |
| diSPIM | 0.4–1 µm dual-view | 0.2 µm | `.tif` or BDV | Janelia diSPIM live thin samples |
| Cleared Tissue Axially Swept LSFM (CTASLM) | 0.4 µm axial | 0.4 µm | OME-TIFF | High axial resolution cleared brain |
| Adaptive imaging dome / SCAPE | swept | varies | OME-TIFF | Live brain in vivo |
| ExA-SPIM (Fang 2023) | 2.5 µm | 0.75 µm | OME-Zarr direct | Designed for ExM-cleared cm-scale samples; pushes ≥30 TB datasets |

**mesoSPIM v2 specifics (Negwer 2024):** stationary 40×40×75 mm fire-fused
glass chamber on kinematic mount; ASI LS-50 z-stage with 50 mm travel and
sub-µm encoder; 1.5 µm lateral / 3.3 µm axial across full FOV; supports
all common clearing media RI 1.33–1.56; standard output is a folder of
single-tile OME-TIFFs (one per channel × tile × stack) plus a JSON
metadata sidecar — feed straight into BigStitcher's "Auto-Loader
(Bioformats)" import.

### §20.3 Voxel anisotropy and what it means downstream

| Setup | Typical XY × Z | Anisotropy | Implications |
|-------|----------------|------------|--------------|
| Z.1 4× clearing | 1.5 × 1.5 × 5 µm | ≈3.3× | Z-resampling needed before isotropic 3D segmentation; PSF dominantly elongated in Z |
| Ultramicroscope II 4× | 1.6 × 1.6 × 5 µm | ≈3× | As above |
| mesoSPIM v2 | 0.5 × 0.5 × 3 µm | 6× | Strong anisotropy; multi-view fusion not available — accept it or deconvolve axial PSF |
| Lattice 0.7× zoom | 0.1 × 0.1 × 0.4 µm | 4× | High res but huge files; usually crop to ROIs |
| ExA-SPIM 4× expanded | 0.75 × 0.75 × 1 µm | 1.3× | Near-isotropic in cm³ samples |

**For 3D segmentation rules of thumb:**
- StarDist 3D, Cellpose 3D, ClearMap CellMap: train or pick a model with the same anisotropy as your data, OR resample to isotropic first.
- `cle.scale(volume, factor_x=1, factor_y=1, factor_z=anisotropy)` to upsample Z to isotropic — but this multiplies file size by `anisotropy` and does not actually add information.
- Deconvolution (multi-view or single-view PSF) recovers some axial detail but cannot fully compensate for sheet thickness.

### §20.4 Bidirectional scan offset

Many LSFM stages capture odd and even Z slices in alternating directions
to halve acquisition time. The scan turnaround introduces a sub-pixel
phase shift between the two interleaved sub-stacks. BigStitcher detects
this when "interleaved" or "bidirectional" is declared in the dataset
definition; a manual fix is to register even-only against odd-only
sub-stacks via phase correlation.

```groovy
// Diagnose with the BDV time projection: if alternating Z slices look like
// a rolling shutter, you have an unhandled bidirectional shift.
```

---

## §21 OME-Zarr / NGFF — The Detail You Need

The community is converging on OME-Zarr (NGFF, Next-Generation File
Format) as the long-term archival format. Worth knowing the spec.

### §21.1 Spec versions

| Version | Year | Key changes |
|---------|------|-------------|
| 0.1 | 2020 | Initial multiscales |
| 0.2 | 2021 | Multiscales metadata refined |
| 0.3 | 2021 | `axes` introduced |
| 0.4 | 2022 | Default for most readers; HCS plate/well; labels; stable |
| 0.5 | 2024 | Switch to **Zarr v3**; sharding support; storage transformers |

Most current Fiji/napari tools default to **0.4** with Zarr v2. Read v3
support is rolling out — verify your reader before producing v3.

### §21.2 On-disk layout (0.4)

```
my_dataset.ome.zarr/
├── .zattrs                            # multiscales, omero, axes
├── .zgroup
├── 0/                                 # full-res pyramid level
│   ├── .zarray                        # shape, chunks, dtype, compressor
│   └── 0.0.0.0.0  0.0.0.0.1  ...      # individual chunk files
├── 1/                                 # 2× downsampled
├── 2/                                 # 4× downsampled
├── ...
└── labels/                            # optional segmentation masks
    ├── .zgroup
    └── nuclei/
        ├── .zattrs                    # image-label metadata, colors
        ├── 0/  1/  2/                 # multiscales, same shape per level
```

### §21.3 `multiscales` metadata block

```json
{
  "multiscales": [{
    "version": "0.4",
    "name": "brain_GFP_autofluo",
    "axes": [
      {"name": "c", "type": "channel"},
      {"name": "z", "type": "space", "unit": "micrometer"},
      {"name": "y", "type": "space", "unit": "micrometer"},
      {"name": "x", "type": "space", "unit": "micrometer"}
    ],
    "datasets": [
      {"path": "0", "coordinateTransformations": [
        {"type": "scale", "scale": [1.0, 5.0, 0.5, 0.5]}]},
      {"path": "1", "coordinateTransformations": [
        {"type": "scale", "scale": [1.0, 5.0, 1.0, 1.0]}]},
      {"path": "2", "coordinateTransformations": [
        {"type": "scale", "scale": [1.0, 5.0, 2.0, 2.0]}]}
    ],
    "type": "gaussian"
  }],
  "omero": {
    "channels": [
      {"label": "GFP", "color": "00FF00",
       "window": {"start": 0, "end": 4000, "min": 0, "max": 65535},
       "active": true},
      {"label": "Autofluo", "color": "FFFFFF",
       "window": {"start": 0, "end": 2000, "min": 0, "max": 65535},
       "active": true}
    ]
  }
}
```

**Rules:**
- `axes` length = array dimensionality, 2–5 entries.
- Must have 2 or 3 `space` axes; may add one `time` and/or one `channel`.
- Order in `axes` matches dimension order in the zarr arrays. Common orders: `tczyx`, `czyx`, `zyx`.
- `coordinateTransformations` per level encodes voxel size; reader uses this to display correct physical scale.

### §21.4 Conversion: bioformats2raw + raw2ometiff

The Glencoe two-step pipeline is the most reliable route from native
formats to OME-Zarr / OME-TIFF.

```bash
# 1. Native (.czi/.lif/.nd2/.svs) → OME-Zarr (multi-resolution, chunked)
bioformats2raw \
    --max_workers 8 \
    --resolutions 5 \
    --tile_width 512 --tile_height 512 \
    --compression blosc --compression-properties cname=zstd \
    --compression-properties clevel=3 \
    /path/to/raw/dataset.czi \
    /path/to/output.ome.zarr

# 2a. (optional) OME-Zarr → pyramidal OME-TIFF
raw2ometiff \
    --compression LZW \
    /path/to/output.ome.zarr \
    /path/to/output.ome.tif

# 2b. OR keep OME-Zarr as the working format (cloud-friendly, parallel-write)
```

**Flags worth knowing:**

| Flag | Effect |
|------|--------|
| `--max_workers N` | Parallelism for chunk writes; tune to CPU cores |
| `--resolutions N` | Number of pyramid levels (default ≈ log2(largest dim / tile)) |
| `--tile_width / --tile_height` | Chunk size (default 1024); 256–512 better for cloud/random access |
| `--target-min-size N` | Stop downsampling when smallest dim ≤ N |
| `--compression {raw,zlib,blosc,zstd}` | blosc/zstd is fast and small |
| `--memo-directory /tmp/bf_memo` | Cache Bio-Formats parser state across runs |
| `--scale-format-string` | Customise output naming for HCS plates |
| `--no-nested` | Use flat key naming (some legacy readers) |
| `--dimension-order XYZCT` | Override default axis order |

For multi-series files (`.lif` with several samples, `.czi` Z-stack +
positions, multi-well plate `.nd2`):

```bash
# Each series becomes a numbered sub-group: output.ome.zarr/0, /1, /2, ...
bioformats2raw multi.lif output.ome.zarr
# or pick a single series
bioformats2raw --series 3 multi.lif output_s3.ome.zarr
```

### §21.5 Python OME-Zarr I/O

```python
# Read with ome-zarr-py (gives multiscale-aware lazy access)
from ome_zarr.io import parse_url
from ome_zarr.reader import Reader
import dask.array as da

reader = Reader(parse_url("/path/to/dataset.ome.zarr"))
nodes = list(reader())
# nodes[0] is the image; nodes[1:] are labels if present
img_node = nodes[0]
levels = img_node.data        # list of dask arrays, one per level
metadata = img_node.metadata
print(metadata["axes"])
print(metadata["coordinateTransformations"])
full_res = levels[0]          # full-res lazy dask array
preview = levels[-1]          # smallest level for thumbnail
```

```python
# Write a fresh OME-Zarr from a numpy/dask array
from ome_zarr.writer import write_image
from ome_zarr.io import parse_url
import zarr, numpy as np

store = parse_url("/path/to/out.ome.zarr", mode="w").store
root = zarr.group(store=store)
write_image(image=array,                       # numpy or dask, shape (c, z, y, x) etc.
            group=root,
            axes="czyx",
            chunks=(1, 32, 256, 256),
            scaler=None,                        # auto-pyramid; pass None to write level 0 only
            storage_options={"chunks": (1, 32, 256, 256)})
```

### §21.6 Labels and segmentation masks

OME-Zarr stores instance masks as a sibling group `labels/<name>/` with
its own multiscales pyramid plus an `image-label` JSON describing
colours and source.

```json
{
  "image-label": {
    "version": "0.4",
    "colors": [
      {"label-value": 1, "rgba": [255, 0, 0, 200]},
      {"label-value": 2, "rgba": [0, 255, 0, 200]}
    ],
    "source": {"image": "../../"}
  }
}
```

napari with `napari-ome-zarr` automatically picks these up as labels
layers. ClearMap2 / ClearMap 3 outputs can be written to this layout.

### §21.7 HCS / plate and well extensions (0.4)

Used for whole-slide pathology and high-content screening but also
relevant for multi-organ batches:

```
plate.ome.zarr/
├── .zattrs                            # plate metadata: rows, cols, wells
├── A/1/                               # well A1
│   ├── .zattrs                        # well metadata: images
│   └── 0/                             # field-of-view 0 inside that well
│       ├── .zattrs                    # multiscales, omero
│       └── 0/ 1/ 2/                   # pyramid
├── A/2/
│   └── 0/...
```

For LSFM whole-organ workflows the plate layout maps cleanly onto a
multi-sample batch (one sample per well-equivalent group).

### §21.8 Cloud / S3 / GCS access

```python
import s3fs, dask.array as da
import zarr

fs = s3fs.S3FileSystem(anon=True)               # or with credentials
store = zarr.storage.FSStore("s3://bucket/path/to/dataset.ome.zarr",
                              fs=fs)
root = zarr.open_group(store, mode="r")
arr = da.from_zarr(root["0"])                    # full-res lazy
```

For GCS use `gcsfs.GCSFileSystem`; for HTTP-only Zarr use
`zarr.storage.FSStore("https://.../dataset.ome.zarr",
fs=fsspec.filesystem("https"))`.

---

## §22 BigStitcher Deep Dive

### §22.1 Input requirements

| Input variety | What BigStitcher expects |
|---------------|--------------------------|
| Folder of regular TIFFs (one per tile) | Auto-loader (Bio-Formats based) — uses filename pattern `tile_{xxx}_ch{c}_z{zzz}.tif` style |
| Single `.czi` with embedded tile positions | "Zeiss CZI" loader; tile coords read from metadata |
| Pre-existing BDV `dataset.xml + .h5` | Open directly; positions from XML |
| `.h5` with custom layout | Custom loader script (rare) |
| Folder of OME-TIFFs from mesoSPIM | Auto-loader (Bio-Formats); each file is one tile/channel |
| `.ims` (Imaris) | Open via Bio-Formats; convert to BDV first for speed |

**Crucial:** before "Define Dataset", verify *every* tile opens in
Bio-Formats by itself. A single corrupt or zero-byte file inside the
folder breaks the auto-loader.

### §22.2 Workflow stages and their CPU/RAM/disk cost

| Stage | Operation | CPU bound? | RAM | Disk write | Per-tile time |
|-------|-----------|------------|-----|------------|---------------|
| Resave to multi-resolution | If input is plain TIFF, BigStitcher rewrites to BDV-HDF5 / N5 | Yes | low | ≈2× input size | ≈30–120 s/tile (HDD) |
| Pairwise shift (phase correlation) | FFT of overlap regions | Yes (multi-thread) | moderate | none | ≈5–30 s/pair |
| Pairwise shift (interest points / DoG) | DoG detection + descriptor matching | Yes (multi-thread, GPU optional) | high (peak per tile) | small XML | ≈10–60 s/pair |
| Filter pairwise shifts | Quality + max-shift filter | Trivial | trivial | none | ≈1 s |
| Global optimisation | Solver | Single-thread per round | low | XML | ≈1–10 s/iter |
| Fuse (RAM-fused) | Whole bounding box loaded | No | very high | none | minutes–OOM |
| Fuse (virtual / cached) | Streaming | Yes | low–moderate | none | minutes |
| Fuse (write to disk N5/Zarr) | Streaming + chunked write | Yes | moderate | size of fused volume | minutes–hours |

### §22.3 Fusion strategies — when to pick which

| Strategy | Choose when | Pitfall |
|----------|-------------|---------|
| **RAM-fused** (in Fiji image) | Fused volume < 50 % of `-Xmx` | OOM on whole brain at 16-bit |
| **Cached / virtual** | Fused volume comparable to RAM | Re-computes on each view; slower |
| **Save as new XML/HDF5** | Fused volume to be browsed in BDV later | Pyramid auto-built |
| **Save as new XML/N5** | Same, faster parallel writes | Single-machine N5 |
| **Save as TIFF** | Sub-volume export, downstream Python pipeline expects TIFF | No pyramid, large TIFFs |
| **Save as OME-Zarr (BigStitcher-Spark only)** | Whole brain + downstream is napari/Python | Need Spark setup |

The "OOM in Fuse Image" failure (§16) is almost always caused by
selecting RAM-fused on a too-large bounding box. Fix by either:
1. Reduce bounding box (current selected views vs. all views).
2. Choose downsampling=2 or 4 to halve/quarter.
3. Switch to "Save as new XML/N5" output.

### §22.4 BigStitcher-Spark — distributed compute

For whole-brain LSFM (commonly 0.5–10 TB raw), the Fiji single-process
BigStitcher hits limits during **resave** and **fuse**. BigStitcher-Spark
is the canonical fix.

| Module | Purpose | CLI |
|--------|---------|-----|
| `resave` | Convert raw / TIFF tiles to N5 or OME-Zarr in parallel | `spark-submit ... net.preibisch.bigstitcher.spark.SparkResaveN5 -x dataset.xml ...` |
| `detect-interestpoints` | Distributed DoG + matching | `... SparkInterestPointDetection ...` |
| `match-interestpoints` | Geometric descriptor matching | `... SparkGeometricDescriptorMatching ...` |
| `stitching` | Pairwise stitching (phase correlation) | `... SparkPairwiseStitching ...` |
| `solver` | Global optimisation | `... Solver ...` |
| `create-fusion-container` | Empty OME-Zarr / N5 / HDF5 with metadata + multi-res pyramid | `... CreateFusionContainer ...` |
| `fuse` | Distributed fusion that writes block-by-block into the container | `... AffineFusion ...` |

Quick local (single-machine) Spark example:

```bash
spark-submit \
    --master local[8] \
    --driver-memory 32g \
    --executor-memory 32g \
    --class net.preibisch.bigstitcher.spark.SparkResaveN5 \
    /path/to/BigStitcher-Spark/target/BigStitcher-Spark-*.jar \
    -x /data/ds.xml \
    -xo /data/ds_n5.xml \
    -o /data/ds.n5 \
    --blockSize 128,128,128 \
    --compression Zstandard
```

`nf-BigStitcher-Spark` (Janelia Nextflow wrapper) is often the easiest
way to get the whole pipeline running on a SLURM / SGE / AWS / GCP
cluster — it chains resave → detect → match → solver → create-container
→ fuse with reasonable defaults and per-step memory hints.

### §22.5 TeraStitcher — the alternative

TeraStitcher (Bria 2012, Bria 2019) was developed at IIT for the
Mouse Brain Architecture project and is still the right tool for
**fixed regular grid acquisitions with no rotation**, especially when
the file layout is a deeply-nested "subvolume" tree:

| Use TeraStitcher when | Use BigStitcher when |
|----------------------|---------------------|
| Acquisition is a regular X×Y×Z tile grid (no rotation) | Multi-view, rotated, or arbitrary sample geometry |
| Output is many TBs of TIFFs in a fixed hierarchical folder | Single CZI / multi-LIF / mixed sources |
| Want UNIX command-line scripting end-to-end | Want a GUI workflow with manual QC at each step |
| Need huge whole-mouse tile counts (>10 000 tiles) | More common 100–2000 tile light-sheet brain |
| Output as a TeraFly browseable hierarchy or Vaa3D | Output as BDV / N5 / OME-Zarr for napari/BigDataViewer |

Pipeline:
```bash
terastitcher --import   --volin=/raw/  --ref1=H --ref2=V --ref3=D --vxl1=1.6 --vxl2=1.6 --vxl3=5
terastitcher --displcompute  --projin=/raw/xml_import.xml
terastitcher --displproj    --projin=/raw/xml_displcomp.xml
terastitcher --displthres   --projin=/raw/xml_displproj.xml --threshold=0.7
terastitcher --placetiles   --projin=/raw/xml_displthres.xml
terastitcher --merge        --projin=/raw/xml_merging.xml --volout=/stitched/ \
             --resolutions=0,1,2,3,4 --tilewidth=256 --tileheight=256
```

Output volumes can be opened in Vaa3D, ImageJ, or converted to OME-Zarr.

### §22.6 Multiview reconstruction (Preibisch et al)

When the dataset has multiple **views/angles** (rotation by 0/90/180/270
of the same sample, common in MuVi-SPIM, openSPIM, Z.1 multi-view
mode), BigStitcher's "Multiview Reconstruction" pipeline does:

1. **Detect interest points** per view (DoG; or use beads embedded in agarose for highest accuracy).
2. **Register views** based on bead correspondences (translation → rigid → affine → regularised affine).
3. **Time-lapse registration** if multiple timepoints — register every timepoint to the first using bead positions.
4. **Fuse** with content-based weighting or multi-view Richardson-Lucy deconvolution (PSF per view).

Bead-based registration is **the gold standard** for SPIM; without
beads, content-based DoG works for samples with strong structure (e.g.,
nuclei) but fails on diffuse samples (e.g., dense vasculature).

### §22.7 Common BigStitcher errors and fixes

| Error | Likely cause | Fix |
|-------|-------------|-----|
| "Could not load image data" on define dataset | Wrong tile pattern; file naming inconsistent | Regex check; verify each tile opens with Bio-Formats |
| Pairwise shifts all near zero | Tile overlap < 5 % or no overlap | Increase overlap when imaging; use interest points if real overlap exists but is featureless |
| Solver oscillates / huge residuals | Bad pairs included in optimisation | Filter pairwise shifts more aggressively (`min_r=0.7+`, max-shift-per-axis to ≤ acquisition tile spacing) |
| "OutOfMemoryError" in fuse | RAM-fused too large | See §22.3 |
| Fuse output has visible tile grid | Illumination correction off; or blending OFF | Enable "blending" + "use illumination correction" in fuse dialog |
| Fuse looks blurry vs raw | Linear interpolation downsampling | Use "Nearest Neighbour" only if downsampling=1; otherwise blur is expected from the resampling |
| BDV navigation slow on fused output | No multi-resolution pyramid in output TIFF | Use XML/N5 or XML/HDF5 output instead |

---

## §23 Vasculature / Tubular Structure Pipelines

| Tool | Engine | Input | Output | Strengths |
|------|--------|-------|--------|-----------|
| ClearMap2 TubeMap | Python (CPU + Cython) | iDISCO+ vasculature LSFM | Skeleton + graph + per-region density | Atlas-aware, mature, integrates with CellMap |
| VesselExpress | Snakemake + Python | Generic 3D LSFM (cleared organ) | Diameter, length, branches, tortuosity per network + skeleton + Blender render | ≈100× faster than legacy methods; multi-organ tested; reproducible |
| TubeMap (Kirst 2020 standalone) | Python | iDISCO+ | Graph | Older, subsumed by ClearMap2 |
| WobblyStitcher | Part of ClearMap2 | Tile sequences | Stitched | Specifically for "wobbly" Z-shifts in long acquisitions |
| Vaa3D plugins | C++ | TeraStitcher hierarchies | Neurite + vessel traces | Long-form neurite tracing |
| 3DSlicer + VMTK | Python+VTK | Generic 3D | Mesh + centreline + radius | Vascular tree analysis from segmented vessels |

### §23.1 VesselExpress quick start

```bash
git clone https://github.com/RUB-Bioinf/VesselExpress
cd VesselExpress
conda env create -f environment.yml
conda activate vesselexpress

# Edit config.yaml: input directory, voxel size, threshold method
snakemake --cores 16 --use-conda
```

Outputs include `vessel_skeleton.tif`, `graph.csv` (nodes + edges with
diameter), per-vessel statistics, and an optional rendered `.blend`
file.

### §23.2 ClearMap2 TubeMap minimal

```python
import ClearMap.ImageProcessing.Experts.Vasculature as vasculature
result = vasculature.process(
    source="/data/clearmap_input/",
    sink_binary="/data/binary.npy",
    sink_skeleton="/data/skeleton.npy",
    processing_parameter={"size_max": 200, "overlap": 32, "processes": 12}
)
```

Subsequent graph extraction:

```python
import ClearMap.Analysis.Graphs.GraphGt as ggt
graph = ggt.load("/data/graph.gt")
graph.add_vertex_property("radius", radii_array)
filtered = graph.sub_graph(vertex_filter=graph.vertex_property("radius") > 1.0)
```

---

## §24 Visualisation at Scale

| Viewer | Backend | Best for | License |
|--------|---------|----------|---------|
| BigDataViewer (Fiji) | ImgLib2 + HDF5 / N5 / OME-Zarr | Native LSFM browsing; integrates with Mastodon, MaMuT, ABBA, BigStitcher | Open |
| napari + napari-ome-zarr | Python + Vispy/OpenGL + Dask | OME-Zarr cloud or local; scriptable; plugin ecosystem | Open |
| Imaris | Custom (IMS = BDV-HDF5 derivative) | Standard biology lab viewer; surface/spot detection; tracking | Commercial |
| syGlass | VR / GPU | VR exploration of TB volumes; manual annotation in 3D | Commercial |
| Vesalius3D | GPU | Cleared-tissue exploration | Commercial |
| Arivis Vision4D / Zeiss arivis Pro | GPU streaming | Whole-brain segmentation + tracking; integrates with Imaris file | Commercial |
| Vaa3D (TeraFly) | C++ | TeraStitcher hierarchy navigation, neuron tracing | Open |
| WEBKNOSSOS | Web | Distributed annotation; OME-Zarr / N5 | Open |
| Neuroglancer | Web | Connectomics-style scrolling; supports OME-Zarr / Precomputed / N5 | Open |
| ParaView / VolView | VTK | Hero rendering on workstations | Open |

**Imaris IMS:** the IMS file format is essentially BDV-HDF5 with extra
attributes — `bioformats2raw` and BigDataViewer can both read it, and
Imaris can read XML/HDF5 BDV outputs. This makes Imaris a viable
visualisation endpoint for an open Fiji + BigStitcher pipeline.

---

## §25 HPC, Cluster, and Cloud

### §25.1 When you need a cluster

| Trigger | Reason |
|---------|--------|
| Raw dataset > 1 TB | Single-machine fuse / resave times become days |
| Total fused output > free local disk | Need distributed object store (S3, GCS, Lustre) |
| Many samples (≥ 10) | Embarrassingly parallel; one node per sample is fastest |
| Memory-hungry deconvolution | Multi-view RL on whole brain doesn't fit in 256 GB |

### §25.2 Apptainer / Singularity for reproducibility

Most clusters disallow Docker. Build an Apptainer (formerly Singularity)
image that bakes Fiji + BigStitcher, Conda envs (ClearMap2, BrainGlobe,
ome-zarr-py), and CUDA where needed:

```bash
# Build (on a workstation with sudo or via remote builder)
apptainer build clearmap.sif clearmap.def

# clearmap.def
Bootstrap: docker
From: nvidia/cuda:12.4.0-cudnn-runtime-ubuntu22.04
%post
    apt-get update && apt-get install -y wget git build-essential openjdk-21-jdk
    wget https://downloads.imagej.net/fiji/latest/fiji-linux64.zip
    unzip fiji-linux64.zip -d /opt
    pip install ome-zarr brainglobe pystripe
    git clone https://github.com/ChristophKirst/ClearMap2 /opt/ClearMap2
    cd /opt/ClearMap2 && pip install -e .
%environment
    export PATH=/opt/Fiji.app:$PATH

# Use on cluster
apptainer exec --nv clearmap.sif python /scripts/clearmap_run.py
```

### §25.3 SLURM template for BigStitcher resave

```bash
#!/bin/bash
#SBATCH --job-name=bs_resave
#SBATCH --partition=highmem
#SBATCH --cpus-per-task=16
#SBATCH --mem=256G
#SBATCH --time=24:00:00
#SBATCH --output=logs/resave_%j.out

module load apptainer
apptainer exec --bind /data:/data clearmap.sif \
    /opt/Fiji.app/ImageJ-linux64 --ij2 --headless --console \
        --run /scripts/bigstitcher_resave.ijm \
        "input='/data/raw/',output='/data/bdv/dataset.xml'"
```

For BigStitcher-Spark on SLURM, prefer `nf-BigStitcher-Spark` with a
Nextflow `slurm.config` profile.

### §25.4 S3 / GCS Zarr workflows

Storing OME-Zarr in object storage means any GPU node can pull only the
chunks it needs.

```python
# Read remote OME-Zarr; processing stays local
import dask.array as da, zarr, s3fs, fsspec
fs = fsspec.filesystem("s3", key="...", secret="...", client_kwargs={"endpoint_url": "https://s3.example"})
store = zarr.storage.FSStore("s3://bucket/sample01.ome.zarr/0", fs=fs)
arr = da.from_zarr(store)
# Trigger compute on a Dask cluster
from dask.distributed import Client
client = Client("tcp://scheduler:8786")
mean = arr.mean().compute()
```

```bash
# Direct upload from cluster scratch
aws s3 sync /scratch/output.ome.zarr s3://my-bucket/sample01.ome.zarr \
    --storage-class INTELLIGENT_TIERING
```

### §25.5 Cost / time budgeting (rough)

| Task | Whole adult mouse brain (≈1 TB raw) |
|------|-------------------------------------|
| Resave to N5 (single workstation, 16 cores, NVMe) | 2–6 h |
| Resave to OME-Zarr (BigStitcher-Spark, 8-node cluster) | 20–40 min |
| Pairwise shift (single workstation) | 30–90 min |
| Global optimisation | minutes |
| Fuse (cached, single workstation) | 4–12 h |
| Fuse (Spark, 8 nodes) | 30–60 min |
| ClearMap CellMap detection | 1–4 h on 32-core CPU; faster on GPU forks |
| BrainGlobe brainreg | 30–90 min |

---

## §26 End-to-End Pipeline Templates

### §26.1 Cleared whole brain → atlas-registered cell counts (single workstation)

```bash
# 0. Acquisition output: per-tile OME-TIFFs from mesoSPIM v2 in /raw/
# Channels: ch00 = c-Fos (or generic IEG/IF); ch01 = autofluorescence

# 1. Convert to OME-Zarr
bioformats2raw --max_workers 8 --resolutions 5 --compression blosc \
    /raw/dataset_metadata.json /work/dataset.ome.zarr

# 2. (optional) Destripe
pystripe --input /work/dataset.ome.zarr/0 --output /work/destriped.ome.zarr \
    --sigma1 256 --sigma2 256 --workers 16

# 3. Stitch with BigStitcher (Fiji, headless)
ImageJ-linux64 --ij2 --headless --console --run /scripts/bigstitcher_full.ijm \
    "input='/work/destriped.ome.zarr',output='/work/bdv/dataset.xml'"

# 4. Fuse signal channel + autofluorescence channel separately to N5
ImageJ-linux64 --ij2 --headless --console --run /scripts/fuse_to_n5.ijm \
    "input='/work/bdv/dataset.xml',channel=0,output='/work/fused/cfos.n5'"
ImageJ-linux64 --ij2 --headless --console --run /scripts/fuse_to_n5.ijm \
    "input='/work/bdv/dataset.xml',channel=1,output='/work/fused/auto.n5'"

# 5. Cell detection (ClearMap2 CellMap)
python /scripts/clearmap_celldetect.py \
    --signal /work/fused/cfos.n5 --output /work/cells.npy \
    --threshold 700 --shape 5 --processes 12

# 6. Atlas registration on autofluorescence (BrainGlobe brainreg)
brainreg /work/fused/auto.n5 /work/registered/ \
    -v 5 5 5 --atlas allen_mouse_25um --orientation asl --backend niftyreg

# 7. Apply atlas transform to cell coordinates → per-region counts
python /scripts/cells_to_atlas.py \
    --cells /work/cells.npy \
    --transform /work/registered/downsampled.tiff \
    --atlas allen_mouse_25um \
    --output /work/region_counts.csv
```

`/scripts/bigstitcher_full.ijm`:

```javascript
#@ String input
#@ String output

run("BigStitcher", "define_dataset=[Auto-Loader (Bioformats based)] " +
    "project_filename=" + output + " path=" + input);
run("Calculate pairwise shifts...",
    "select=" + output + " " +
    "process_angle=[All angles] process_channel=[All channels] " +
    "process_illumination=[All illuminations] process_tile=[All tiles] " +
    "process_timepoint=[All Timepoints] method=[Phase Correlation]");
run("Filter pairwise shifts...",
    "select=" + output + " filter_by_link_quality min_r=0.7 " +
    "max_shift_in_each_dimension=50");
run("Optimize globally and apply shifts...",
    "select=" + output + " " +
    "global_optimization_strategy=[Two-Round: Metadata then Relative then Fixed tiles]");
eval("script", "System.exit(0)");
```

### §26.2 Visualisation-only pipeline

```bash
# Convert and view in napari, no analysis
bioformats2raw /raw/dataset.czi /work/preview.ome.zarr
python -c "
import napari
from napari_ome_zarr import napari_get_reader
viewer = napari.Viewer()
napari_get_reader('/work/preview.ome.zarr')(['/work/preview.ome.zarr'])
napari.run()
"
```

### §26.3 Multi-sample batch with sample-level loop

```python
# /scripts/batch_pipeline.py
from pathlib import Path
import subprocess, json

samples = sorted(Path("/raw").glob("sample_*"))
for s in samples:
    out = Path("/work") / s.name
    out.mkdir(parents=True, exist_ok=True)
    # 1. convert
    subprocess.check_call(["bioformats2raw", str(s / "dataset.json"),
                           str(out / "data.ome.zarr"),
                           "--max_workers", "8", "--resolutions", "5"])
    # 2. stitch (Fiji headless)
    subprocess.check_call(["ImageJ-linux64", "--ij2", "--headless", "--console",
                           "--run", "/scripts/bigstitcher_full.ijm",
                           f"input='{out}/data.ome.zarr',output='{out}/bdv.xml'"])
    # 3. cell detection
    subprocess.check_call(["python", "/scripts/clearmap_celldetect.py",
                           "--signal", str(out / "bdv.xml"),
                           "--output", str(out / "cells.npy")])
    # 4. brainreg
    subprocess.check_call(["brainreg", str(out / "auto.tif"), str(out / "reg"),
                           "-v", "5", "5", "5", "--atlas", "allen_mouse_25um",
                           "--orientation", "asl"])
    print(f"Done: {s.name}")
```

### §26.4 ExA-SPIM / expansion-cleared pipeline

```bash
# Expansion factor measured: 4.1×; raw voxels 0.75 µm = effective 0.18 µm

# 1. bioformats2raw direct from acquisition
bioformats2raw /raw/expanded_brain.tif /work/expanded.ome.zarr \
    --tile_width 256 --tile_height 256 --resolutions 6

# 2. Adjust scale in the OME-Zarr metadata
python <<'PY'
import zarr, json
z = zarr.open("/work/expanded.ome.zarr", mode="r+")
exp_factor = 4.1
ms = z.attrs["multiscales"][0]
for d in ms["datasets"]:
    for ct in d["coordinateTransformations"]:
        if ct["type"] == "scale":
            ct["scale"] = [s / exp_factor for s in ct["scale"]]
z.attrs["multiscales"] = [ms]
PY

# 3. View in napari with corrected biological scale
python -c "import napari; napari.view_path('/work/expanded.ome.zarr'); napari.run()"
```

---

## §27 End-to-End Failure Modes

| Failure | Symptom | Root cause | Fix |
|---------|---------|------------|-----|
| Stripe artefacts in fused volume | Periodic horizontal/vertical bands | Destriping not applied or applied AFTER fusion | Destripe per-tile (PyStripe) BEFORE BigStitcher resave |
| Chequerboard at tile edges | Visible tile boundaries even after fusion | Illumination correction off; or huge intensity range across tiles | Enable illumination correction in BigStitcher fuse; consider per-tile flatfield correction first |
| Stitch fails on tiles with no overlap | Pairwise correlations near zero | Acquisition overlap < 5 % | Treat as known-position metadata; skip pairwise; rely on metadata-only global optimisation |
| Stitch fails on featureless tissue | DoG finds no interest points | Tissue too smooth (e.g., dense parenchyma) | Use phase correlation instead, or add fiducial beads at acquisition |
| Whole brain looks "spaghettified" after fuse | Wrong rotation in dataset definition | Tile coordinate system mismatched (XY swapped, sign flipped) | Re-define dataset with correct grid orientation; check XY/Z signs |
| OME-Zarr opens but voxel size wrong | `coordinateTransformations` missing or unit wrong | bioformats2raw didn't infer scale (some `.tif` series) | Manually patch `.zattrs` with correct scale |
| `bioformats2raw` runs but output is huge | No compression specified | Default is uncompressed Blosc | Add `--compression blosc --compression-properties cname=zstd` |
| BrainGlobe brainreg gives nonsense alignment | Orientation flag wrong | `--orientation` letters mis-set | Visualise the downsampled.tiff vs atlas; flip flags one at a time |
| ClearMap2 CellMap detects too few cells | Threshold too high; or autofluorescence dominates | Lower threshold; or use signal − autofluorescence channel as input |
| ClearMap2 CellMap detects everything | Threshold too low; chunk artefacts on chunk boundaries | Raise threshold; ensure overlap > 2× max cell radius |
| Memory blows up halfway through fuse | RAM-fused on whole bounding box | Use cached / N5 / Zarr output |
| All FP signal disappeared after clearing | DISCO chemistry on FP-sensitive sample | Switch to CUBIC / SHIELD / FDISCO / vDISCO |
| "Stained" only at tissue surface | Antibody penetration failure | Move to SmartBatch / SE; or use vibratome slabs as positive control |
| Bidirectional Z banding | Bidirectional scanner offset not handled | Acquire unidirectionally OR declare interleaved in BigStitcher |
| Atlas alignment looks fine but per-region counts implausible | Orientation correct but voxel size in registration wrong | Re-check `-v Z Y X` flags to brainreg/ClearMap (in micrometres, not pixels) |
| Spark fuse runs but output corrupt | Concurrent writes to same chunk | Confirm `--blockSize` is consistent and pre-allocated container is used |
| Pyramid levels missing in OME-Zarr after Spark fuse | `CreateFusionContainer` step skipped | Always run create-container BEFORE fuse |
| Imaris IMS won't open Fiji-exported HDF5 | IMS extra attributes missing | Use BigStitcher-Spark with IMS exporter, or convert via ImarisConvertFileBatch |

### §27.1 Sanity-check checklist before believing your numbers

- [ ] Voxel size in metadata matches what you expect (microscope log + `bioformats2raw` output + napari display).
- [ ] Orientation (RAS/ASL/LPS) is consistent across registration, atlas, and any 3D viewer.
- [ ] Reference channel for atlas registration is autofluorescence (not the signal channel).
- [ ] Cell-detection threshold has been validated on a known sub-region (e.g., a region with hand-counted ground truth, or a published density value).
- [ ] Per-region cell counts are not dominated by edge effects from registration warping.
- [ ] Density (cells / mm³) is in the right order of magnitude for the tissue (e.g., adult mouse cortex neurons ≈ 70k–90k / mm³; total adult mouse brain neurons ≈ 70 M).
- [ ] Symmetry sanity: left vs right hemisphere counts within ≈ 10 % unless biology says otherwise.
- [ ] Autofluorescence channel was used and quenching was applied where needed.
- [ ] Shrinkage / expansion factor measured per sample (or constant per protocol) and applied to physical-scale outputs.

### §27.2 Rebuild-from-scratch recovery

If a run fails late (e.g., during fuse), do not restart at step 0:

| Failed at | Restart at |
|-----------|------------|
| Resave / convert | Step 1 (bioformats2raw); intermediate Zarr is reusable |
| Destripe | Step 2; per-tile inputs unchanged |
| Define dataset | Step 3; XML is regenerable |
| Pairwise shifts | Step 3 — but interest-point XML can be saved/reused |
| Solver | Re-run solver only; don't redo pairwise |
| Fuse | Re-run fuse with smaller bounding box / different output mode |
| Cell detection | Re-run with new threshold; signal volume unchanged |
| Atlas registration | Re-run with corrected orientation; cell coordinates unchanged |
| Region tabulation | Re-run script only |

Keep all intermediate outputs (fused N5, cell coordinates, registration
transform) until the paper is published.

---

## §28 Additional Cross-References

| Topic | Where |
|-------|-------|
| JVM tuning, virtual stacks, headless mode | `large-dataset-optimization-reference.md` |
| Allen CCFv3 ontology, brainreg orientation | `brain-atlas-registration-reference.md` |
| PSF measurement, DeconvolutionLab2 algorithm choice | `deconvolution-reference.md` |
| 3D visualisation methods comparison | `3d-visualisation-reference.md` |
| Full CLIJ2 op catalogue | `clij2-gpu-reference.md` |
| ImageJ macro language | `macro-reference.md` |
| Cell segmentation models (StarDist, Cellpose) for cleared data | `ai-image-analysis-reference.md` |
| Statistical comparison of per-region densities across groups | `statistical-analysis-workflow-reference.md` |

Sources consulted while writing this reference (2024–2026 current):
mesoSPIM Initiative (Negwer 2024 *Nat Commun*), Park 2018 *Nat Biotechnol*
SHIELD, Susaki 2014/2015/2020 CUBIC family, Renier 2014/2016 iDISCO+,
Pan 2016 uDISCO / Cai 2018 vDISCO, Klingberg 2017 ECi, Chung 2013
CLARITY, Yang 2014 PACT/PARS, Tillberg 2016 proExM, Chozinski 2016 ExM,
Truckenbrodt 2018 X10, Gambarotto 2019 U-ExM, Chen 2017 iExM, Damstra
2022 TREx, Hörl 2019 BigStitcher, Bria 2012/2019 TeraStitcher, Spivak
et al. BigStitcher-Spark, Kirst 2020 ClearMap2, Spangenberg 2023
VesselExpress, OME-NGFF spec 0.4 / 0.5 (`ngff.openmicroscopy.org`),
Glencoe `bioformats2raw` / `raw2ometiff`, BrainGlobe `brainglobe.info`,
napari `napari.org`.
