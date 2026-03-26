# Large Dataset Optimization Reference

Efficient processing, storage, and analysis of large microscopy datasets in ImageJ/Fiji, Python, and distributed systems.

---

## Quick Start: Batch Template

```javascript
setBatchMode(true);
inputDir = "/path/to/images/";
outputDir = "/path/to/output/";
list = getFileList(inputDir);

for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif")) {
        open(inputDir + list[i]);
        // --- processing ---
        run("Gaussian Blur...", "sigma=2");
        run("Measure");
        // ---
        saveAs("Tiff", outputDir + "processed_" + list[i]);
        close();
    }
    if (i % 20 == 0) run("Collect Garbage");
    showProgress(i, list.length);
}
setBatchMode(false);
saveAs("Results", outputDir + "all_results.csv");
```

Via TCP agent:
```bash
python ij.py macro 'setBatchMode(true); inputDir="/path/"; list=getFileList(inputDir);
for(i=0;i<list.length;i++){if(endsWith(list[i],".tif")){open(inputDir+list[i]);run("Measure");close();}
if(i%20==0) run("Collect Garbage");} setBatchMode(false);'
python ij.py results
```

---

## Memory Management

### Configuration

| Setting | Recommendation |
|---------|---------------|
| Max memory (`-Xmx`) | Typically 75% of system RAM |
| GC for large heaps (>8 GB) | `-XX:+UseG1GC` |
| Default GC | `-Xincgc` (fine for most use) |
| Thread count | Consider setting to CPU core count |

**Note:** Java never releases memory back to the OS. Since Feb 2025, Fiji uses the **Jaunch** launcher with TOML-based config (`jvm.cfg`).

```javascript
// Check memory from macro
usedMB = parseInt(IJ.currentMemory()) / (1024 * 1024);
maxMB = parseInt(IJ.maxMemory()) / (1024 * 1024);
print("Used: " + d2s(usedMB, 0) + "/" + d2s(maxMB, 0) + " MB");
```

### Image Memory Footprint

| Image Type | Dimensions | Memory |
|-----------|-----------|--------|
| 8-bit | 1024x1024 | 1 MB |
| 16-bit | 1024x1024 | 2 MB |
| 16-bit z-stack | 1024x1024x50 | 100 MB |
| 16-bit multi-ch z-stack | 1024x1024x3ch x50z | 300 MB |
| Whole-slide (20x) | 50000x50000 (8-bit) | ~2.5 GB |

**Formula:** `bytes = width x height x channels x slices x frames x (bitDepth / 8)`

Compressed files (JPEG, LZW-TIFF) decompress to full size in memory.

### Garbage Collection

```javascript
run("Collect Garbage");            // force GC
call("java.lang.System.gc");       // alternative

// In loops: periodic GC every 10-20 images
if (i % 10 == 0) run("Collect Garbage");
```

**Gotchas:**
- `run("Collect Garbage")` may not free memory during macro execution (Fiji bug #819)
- SCIFIO can prevent memory release in batch mode -- disable if not needed
- `close()` does not always release memory in batch mode

### Closing Images

```javascript
close("temp_*");           // wildcard
close("\\Others");         // close everything except front image
close("*");                // close ALL

// After channel split -- close unwanted immediately
run("Split Channels");
selectWindow("C3-" + title);
close("C1-" + title);
close("C2-" + title);
```

---

## Batch Mode

```javascript
setBatchMode(true);                    // ~10-20x faster, no display
setBatchMode("show");                  // show current image without exiting
setBatchMode("exit and display");      // show all images created in batch
setBatchMode(false);                   // exit
```

**Gotchas:**
- Some plugins need a visible window -- test first
- ROI Manager may behave differently
- Error dialogs may be hidden -- check for silent failures
- `setBatchMode(true)` must come BEFORE opening images

### Chunked Processing (avoid memory exhaustion)

```javascript
setBatchMode(true);
list = getFileList(inputDir);
chunkSize = 50;
for (chunk = 0; chunk < list.length; chunk += chunkSize) {
    for (i = chunk; i < minOf(chunk + chunkSize, list.length); i++) {
        if (endsWith(list[i], ".tif")) {
            open(inputDir + list[i]);
            // ... process ...
            close();
        }
    }
    if (nResults > 0) {
        saveAs("Results", outputDir + "results_chunk" + chunk + ".csv");
        run("Clear Results");
    }
    run("Collect Garbage");
}
setBatchMode(false);
```

---

## Virtual Stacks and Lazy Loading

Virtual stacks load only the displayed slice into memory.

```javascript
// Open as virtual stack
run("Image Sequence...", "open=/path/to/folder/ sort use");

// Bio-Formats virtual import
run("Bio-Formats Importer", "open=/path/to/file.nd2 view=Hyperstack stack_order=XYCZT use_virtual_stack");
```

**Limitations:** read-only, cannot crop directly, some operations fail.

### Process Virtual Stack Pattern

```javascript
id = getImageID();
n = nSlices;
setBatchMode(true);
for (i = 1; i <= n; i++) {
    showProgress(i, n);
    setSlice(i);
    run("Duplicate...", "title=temp");
    run("Gaussian Blur...", "sigma=2");
    saveAs("Tiff", outputDir + "slice_" + IJ.pad(i, 4) + ".tif");
    close();
}
setBatchMode(false);
```

### BigDataViewer

Handles terabyte-scale data via multi-resolution pyramids and lazy loading.

```javascript
run("BDV N5 Viewer", "open=/path/to/dataset.n5");
run("Export Current Image as XML/HDF5", "output=/path/to/output");
```

---

## File Format Comparison

| Format | Compression | Chunked | Cloud-Ready | Multi-Resolution |
|--------|------------|---------|-------------|-----------------|
| TIFF (uncompressed) | None | No | No | No |
| OME-TIFF | Lossless | Tiled | No | Yes (pyramidal) |
| HDF5 | Configurable | Yes | No | No |
| N5 | Configurable | Yes | Yes | Yes |
| OME-Zarr | Configurable | Yes | Yes | Yes |

### When to Use Which

| Scenario | Format |
|----------|--------|
| Standard sizes (typically <4 GB) | TIFF or OME-TIFF |
| Large z-stacks/time-lapses (4-50 GB) | OME-TIFF tiled or HDF5 |
| Very large / whole-slide (>50 GB) | N5 or OME-Zarr |
| Cloud/remote access | OME-Zarr |
| Python + Dask | Zarr |
| Sharing with collaborators | OME-TIFF |

### Converting Formats

```javascript
// Export to OME-TIFF
run("Bio-Formats Exporter", "save=/path/to/output.ome.tif compression=LZW");

// Export to N5
run("Export N5", "n5path=/path/to/output.n5 blocksize=128,128,64 compression=gzip");
```

```bash
# bioformats2raw + raw2ometiff pipeline
bioformats2raw /path/to/source.nd2 /path/to/intermediate.zarr
raw2ometiff /path/to/intermediate.zarr /path/to/output.ome.tif
```

```python
# Python: TIFF to Zarr
import zarr, tifffile
img = tifffile.imread("large_stack.tif")
z = zarr.open("output.zarr", mode="w", shape=img.shape,
              chunks=(64, 256, 256), dtype=img.dtype)
z[:] = img
```

---

## GPU Acceleration with CLIJ2

| Scenario | Typical Speedup |
|----------|----------------|
| Consumer laptop GPU | ~15x |
| Workstation GPU vs laptop CPU | ~33x |
| Optimised CLIJ2 pipeline | up to 100x |
| Small images (<10 MB) | Not worth it (push/pull overhead) |

### Basic Pattern

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");
open("/path/to/image.tif");
input = getTitle();

Ext.CLIJ2_push(input);

// GPU pipeline -- all operations stay on GPU
Ext.CLIJ2_gaussianBlur2D(input, blurred, 2, 2);
Ext.CLIJ2_release(input);

Ext.CLIJ2_topHatBox(blurred, background_subtracted, 15, 15, 0);
Ext.CLIJ2_release(blurred);

Ext.CLIJ2_thresholdOtsu(background_subtracted, binary);
Ext.CLIJ2_release(background_subtracted);

Ext.CLIJ2_connectedComponentsLabelingBox(binary, labels);
Ext.CLIJ2_release(binary);

Ext.CLIJ2_statisticsOfLabelledPixels(input, labels);

Ext.CLIJ2_pull(labels);
Ext.CLIJ2_release(labels);
```

### CLIJ2 Memory Management

```javascript
Ext.CLIJ2_reportMemory();         // check GPU memory
Ext.CLIJ2_release(imageName);     // release individual image
Ext.CLIJ2_clear();                // release ALL (do at end of macro)
```

**Critical rules:**
- Release GPU images as soon as done -- GPU memory is typically 2-8 GB
- Keep pipeline on GPU -- minimize push/pull (each is slow)
- Always call `Ext.CLIJ2_clear()` at end

### Key CLIJ2 Operations

| Category | Functions |
|----------|----------|
| Filtering | `gaussianBlur2D/3D`, `mean2DBox/3D`, `median2DBox/3D`, `topHatBox` |
| Threshold | `thresholdOtsu`, `threshold`, `automaticThreshold` |
| Segmentation | `connectedComponentsLabelingBox`, `voronoiOtsuLabeling` |
| Morphology | `erodeBox`, `dilateBox`, `binaryFillHoles` |
| Measurement | `statisticsOfLabelledPixels`, `countNonZeroPixels2DSphere` |
| Math | `addImages`, `subtractImages`, `multiplyImages` |
| Projection | `maximumZProjection`, `meanZProjection` |
| Transform | `scale2D/3D`, `rotate2D/3D`, `affineTransform2D/3D` |

### CLIJ2 Batch Processing

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");
setBatchMode(true);
list = getFileList(inputDir);

for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif")) {
        open(inputDir + list[i]);
        input = getTitle();
        Ext.CLIJ2_push(input);
        close();  // close CPU copy

        Ext.CLIJ2_gaussianBlur2D(input, blurred, 2, 2);
        Ext.CLIJ2_release(input);
        Ext.CLIJ2_thresholdOtsu(blurred, binary);
        Ext.CLIJ2_release(blurred);
        Ext.CLIJ2_connectedComponentsLabelingBox(binary, labels);
        Ext.CLIJ2_release(binary);
        Ext.CLIJ2_statisticsOfLabelledPixels(input, labels);
        Ext.CLIJ2_release(labels);
    }
}
setBatchMode(false);
Ext.CLIJ2_clear();
```

---

## Macro Optimization

### Performance Hierarchy (fastest to slowest)

1. CLIJ2 GPU pipeline
2. `setBatchMode(true)` (~10-20x)
3. Built-in operations (`run("Multiply...", "value=2")`)
4. Pixel array access (`getLine()`)
5. Per-pixel `getPixel`/`setPixel` (slowest)

### Tips

```javascript
// Measure all ROIs at once (fast) vs one-by-one (slow)
roiManager("Deselect");
roiManager("Multi Measure");

// Cache values outside loops
title = getTitle();

// Save Results periodically for large datasets
if (nResults > 1000) {
    saveAs("Results", outputFile);
    run("Clear Results");
}
```

---

## Python Large Data Processing

### Dask for Out-of-Core

```python
import dask.array as da
import zarr

data = da.from_zarr("large_dataset.zarr")  # lazy -- no memory yet

from scipy.ndimage import gaussian_filter
filtered = data.map_blocks(lambda block: gaussian_filter(block, sigma=2), dtype=data.dtype)
filtered.to_zarr("filtered_output.zarr")   # processes chunk by chunk
```

### tifffile for Large TIFFs

```python
import tifffile

# Memory-mapped access (doesn't load into RAM)
data = tifffile.memmap("large_image.tif")

# Write tiled BigTIFF
tifffile.imwrite("output.tif", data, tile=(256, 256), compression="lzw", bigtiff=True)
```

### Multiprocessing for Independent Images

```python
from multiprocessing import Pool
from pathlib import Path
import tifffile, pandas as pd
from skimage.filters import gaussian, threshold_otsu
from skimage.measure import label, regionprops_table

def process_single_image(filepath):
    img = tifffile.imread(str(filepath))
    smoothed = gaussian(img, sigma=2)
    binary = smoothed > threshold_otsu(smoothed)
    labeled = label(binary)
    props = regionprops_table(labeled, intensity_image=img,
                              properties=["area", "mean_intensity", "eccentricity"])
    df = pd.DataFrame(props)
    df["filename"] = Path(filepath).name
    return df

files = list(Path("/path/to/images").glob("*.tif"))
with Pool(processes=8) as pool:
    results = pool.map(process_single_image, files)
pd.concat(results).to_csv("batch_results.csv", index=False)
```

---

## Bio-Formats for Large Files

```javascript
// Virtual stack
run("Bio-Formats Importer", "open=/path/to/file.nd2 view=Hyperstack stack_order=XYCZT use_virtual_stack");

// Crop on import
run("Bio-Formats Importer", "open=/path/to/file.nd2 view=Hyperstack stack_order=XYCZT crop x_coordinate_1=100 y_coordinate_1=100 width_1=512 height_1=512");

// Specific series from multi-series file
run("Bio-Formats Importer", "open=/path/to/file.lif view=Hyperstack stack_order=XYCZT series_3");

// Open all series
run("Bio-Formats Importer", "open=/path/to/file.lif view=Hyperstack stack_order=XYCZT open_all_series");
```

### Series Iteration

```javascript
run("Bio-Formats Macro Extensions");
Ext.setId("/path/to/file.nd2");
Ext.getSeriesCount(seriesCount);

setBatchMode(true);
for (s = 0; s < seriesCount; s++) {
    run("Bio-Formats Importer", "open=/path/to/file.nd2 view=Hyperstack stack_order=XYCZT series_" + (s + 1));
    // ... process ...
    close();
    if (s % 5 == 0) run("Collect Garbage");
}
setBatchMode(false);
```

**Memo files:** Bio-Formats creates `.bfmemo` caches. First open of large files may take 30-60s; subsequent opens take 1-2s. Delete if stale.

---

## Headless Mode

```bash
# Run macro headless
./ImageJ-linux64 --ij2 --headless --console --run script.ijm \
    "input='/path/to/images',output='/path/to/results',suffix='.tif'"

# Run Groovy headless
./ImageJ-linux64 --ij2 --headless --console --run script.groovy
```

### Script Parameters (work both as GUI dialog and CLI args)

```javascript
#@ File (label = "Input directory", style = "directory") input
#@ File (label = "Output directory", style = "directory") output
#@ String (label = "File suffix", value = ".tif") suffix

processFolder(input);
```

**Limitations:** Some plugins require GUI; `waitForUser()` hangs; consider `Xvfb` on Linux for stubborn plugins.

---

## Distributed / Cluster Processing

### SLURM + Fiji

```bash
#!/bin/bash
#SBATCH --job-name=fiji_batch
#SBATCH --array=0-99
#SBATCH --mem=16G
#SBATCH --cpus-per-task=4

FILES=(${INPUT_DIR}/*.tif)
FILE=${FILES[$SLURM_ARRAY_TASK_ID]}
/path/to/Fiji.app/ImageJ-linux64 --ij2 --headless --console \
    --run /path/to/process_single.ijm "input='${FILE}',output='${OUTPUT_DIR}'"
```

### Other Tools

| Tool | Best For |
|------|----------|
| CellProfiler | High-throughput well-plate analysis; `cellprofiler -c -r -p pipeline.cppipe` |
| Nextflow/Snakemake | Reproducible, scalable pipelines |
| OMERO | Centralised image server with API (Python, Java, R) |
| QuPath | Whole-slide image analysis, tile-based |

---

## Whole-Slide / Tiled Processing

```javascript
run("Bio-Formats Macro Extensions");
Ext.setId("/path/to/slide.svs");
Ext.getSizeX(width);
Ext.getSizeY(height);

tileSize = 1024;
overlap = 50;

setBatchMode(true);
for (y = 0; y < height; y += tileSize - overlap) {
    for (x = 0; x < width; x += tileSize - overlap) {
        tw = minOf(tileSize, width - x);
        th = minOf(tileSize, height - y);
        Ext.openSubImage("tile", x, y, tw, th);
        // ... process tile ...
        close();
    }
    run("Collect Garbage");
}
setBatchMode(false);
```

---

## Agent-Specific Optimization

### TCP Server Limitations

| Limitation | Workaround |
|-----------|------------|
| 30s macro timeout | Break batch into ~50-image TCP calls |
| No progress query | Use `showProgress()` + check Log window |
| No streaming results | Save results periodically within macro |
| 1 MB message limit | Save to file, read from file |

### Agent Workflow for Large Datasets

1. **Discover:** `python ij.py macro 'list=getFileList("/path/"); print(list.length+" files");'`
2. **Test on 1:** open, check info/histogram/capture, develop pipeline
3. **Test on 5:** run pipeline in batch mode on small subset
4. **Run full batch:** self-contained macro with GC + periodic saves
5. **Audit:** `python ij.py results` then `python auditor.py`

**Best practices:**
- Keep batch macro self-contained (setBatchMode, GC, save logic inside macro)
- Save results to file inside macro (do not rely on TCP pull after long batch)
- Break into chunks for >100 images
- Monitor memory between chunks: `python ij.py state`

---

## Decision Trees

### Processing Strategy

```
How many images?
+-- 1-10: Manual or simple macro
+-- 10-100: setBatchMode macro
|   +-- Images >1 GB each? Virtual stacks + per-slice
+-- 100-1000: setBatchMode + CLIJ2 GPU + chunks
|   +-- Total > RAM? Process in chunks, save periodically
+-- 1000-10000: CellProfiler or Fiji headless on cluster
+-- 10000+: Distributed (Nextflow, SLURM + Fiji, Dask)
```

### Memory Strategy

```
Image fits in RAM?
+-- Yes: Standard open/process/close
+-- Barely: setBatchMode, close aggressively, GC every N images
+-- No (single image too large):
|   +-- Can crop? Bio-Formats crop-on-import
|   +-- Tiled format? Process tile-by-tile
|   +-- Stack? Virtual stack + per-slice
+-- No (too many images):
    +-- Process one at a time, save, close, repeat
    +-- Python + Dask for out-of-core
    +-- Distribute across cluster nodes
```

### File Format

```
+-- Simple archival: OME-TIFF
+-- Fast random access: N5 or Zarr
+-- Cloud storage: OME-Zarr
+-- BigDataViewer: N5 or BDV XML/HDF5
+-- Python + Dask: Zarr
```

---

## Troubleshooting

| Problem | Cause | Solution |
|---------|-------|----------|
| OutOfMemoryError | Images exceed RAM | Increase `-Xmx`, use virtual stacks, or chunk |
| Memory not released | Java GC lag / SCIFIO | `run("Collect Garbage")`; disable SCIFIO |
| Batch mode slow | Plugin not batch-compatible | Test if plugin needs visible window |
| Results table extremely slow | >50k rows | Save and clear every ~1000 rows |
| Bio-Formats import hangs | Corrupt file / memo cache | Delete `.bfmemo`; try `use_virtual_stack` |
| CLIJ2 out of GPU memory | GPU VRAM full | Release intermediates; `Ext.CLIJ2_reportMemory()` |
| Fiji crashes on large files | >4 GB TIFF / insufficient heap | Use BigTIFF; increase `-Xmx` |
| Network drive very slow | I/O bottleneck | Copy data to local SSD |
| Headless plugin errors | Plugin requires display | Use `Xvfb` on Linux |

### Memory Leak Detection

```javascript
startMem = parseInt(IJ.currentMemory()) / (1024 * 1024);
// ... processing ...
endMem = parseInt(IJ.currentMemory()) / (1024 * 1024);
print("Leaked: " + d2s(endMem - startMem, 0) + " MB");
// Common causes: unclosed images, growing Results table, ROI Manager accumulation
```

---

## Optimization Checklist

- [ ] Set Fiji memory to typically 75% of system RAM
- [ ] Copy data to local SSD
- [ ] Test pipeline on 3-5 images first
- [ ] Use `setBatchMode(true)`
- [ ] Close images immediately after extracting data
- [ ] `run("Collect Garbage")` every 10-20 images
- [ ] Save Results periodically and clear
- [ ] Consider CLIJ2 for GPU acceleration on large images
- [ ] Use virtual stacks for images larger than RAM
- [ ] Consider Dask/Python for out-of-core processing
- [ ] Use OME-Zarr/N5 for datasets >50 GB
- [ ] Use headless mode for server/cluster
