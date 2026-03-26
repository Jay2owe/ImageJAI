# File Formats & Saving Reference

Every macro below works with: `python ij.py macro 'CODE_HERE'`

---

## 1. Native Formats (Read & Write)

| Format | Ext | Bit Depths | Stacks | Calibration | Compression | Notes |
|--------|-----|-----------|--------|-------------|-------------|-------|
| **TIFF** | `.tif` | 8/16/32/RGB | Yes (5D XYCZT) | **Preserved** | Uncompressed (native write); LZW/ZIP read | Preserves ROIs, overlays, LUTs, properties. Max ~4 GB |
| **ZIP** | `.zip` | All (=TIFF) | Yes | **Preserved** | Lossless Deflate | TIFF inside ZIP. Smaller but slower I/O |
| **JPEG** | `.jpg` | 8-bit/RGB only | No | Lost | **Lossy** | NEVER for scientific data. Quality: Edit > Options > Input/Output |
| **PNG** | `.png` | 8-bit only (native) | No | Lost | Lossless | 16-bit downconverted natively. Use Bio-Formats for 16-bit PNG |
| **GIF** | `.gif` | 8-bit only | Animated GIF | Lost | Lossless LZW | RGB must convert to 8-bit colour first |
| **BMP** | `.bmp` | 8-bit/RGB | No | Lost | None | Rarely useful |
| **Raw** | `.raw` | All | Yes | Lost (no header) | None | Must know dims/type to reimport |
| **AVI** | `.avi` | 8-bit/RGB | Yes (frames) | Lost | None/JPEG/PNG | Max ~2 GB. Most MJPG not readable |
| **FITS** | `.fits` | 8→16, signed16→float, 32 | Yes | Partial | None | Astronomical format |
| **PGM** | `.pgm` | 8/16-bit | No | Lost | None | Rarely used |
| **Text Image** | `.txt` | Any→32-bit float | No | Lost | None | Tab-delimited pixel values |

**TIFF ImageDescription tag** (how hyperstacks are encoded):
```
ImageJ=1.54f
images=24  channels=2  slices=4  frames=3
hyperstack=true  mode=composite  unit=um  spacing=1.5  finterval=0.5
```

```javascript
// AVI compression options
run("AVI... ", "compression=Uncompressed frame=10");  // or JPEG or PNG
```

### Read-Only: DICOM (`.dcm`) — native uncompressed; use Bio-Formats for compressed.

---

## 2. Bio-Formats (163 Formats)

### Key Microscopy Formats

| Format | Ext | Write | Metadata Quality |
|--------|-----|-------|-----------------|
| Nikon ND2 | `.nd2` | No | Very good |
| Leica LIF | `.lif` | No | Outstanding |
| Zeiss CZI | `.czi` | No | Outstanding |
| Zeiss LSM | `.lsm` | No | Outstanding |
| Olympus | `.oib`/`.oif`/`.oir` | No | Very good |
| DeltaVision | `.dv`/`.r3d` | No | Outstanding |
| MetaMorph | `.stk`/`.nd` | No | Very good |
| Micro-Manager | `.tif`+`.txt` | No | Outstanding |
| Imaris | `.ims` | No | Very good |
| OME-TIFF | `.ome.tif` | **Yes** | Outstanding |

### Bio-Formats Writable Formats

OME-TIFF, OME-XML, TIFF, JPEG, JPEG-2000, PNG, Animated PNG, AVI, QuickTime, EPS, ICS, BMP, PCX, PICT, NRRD, CellH5, DICOM, Text, SlideBook 7, Nuance.

### Import Syntax

```javascript
// Basic
run("Bio-Formats Importer", "open=/path/to/file.nd2 autoscale color_mode=Default view=Hyperstack stack_order=XYCZT");

// Windowless (uses last settings)
run("Bio-Formats (Windowless)", "open=/path/to/file.nd2");

// Specific series | virtual stack | split channels | crop
run("Bio-Formats Importer", "open=/path/to/file.lif autoscale color_mode=Default view=Hyperstack stack_order=XYCZT series_1");
run("Bio-Formats Importer", "open=/path/to/file.nd2 color_mode=Default view=Hyperstack stack_order=XYCZT use_virtual_stack");
run("Bio-Formats Importer", "open=/path/to/file.nd2 autoscale color_mode=Default split_channels view=Hyperstack stack_order=XYCZT");
```

**Key options:** `autoscale`, `color_mode=Default|Composite|Colorized|Grayscale|Custom`, `view=Standard|Hyperstack|Browser`, `stack_order=XYCZT`, `split_channels`, `split_timepoints`, `split_focal`, `use_virtual_stack`, `concatenate_series`, `open_all_series`, `series_N`, `quiet`.

### Macro Extensions

```javascript
run("Bio-Formats Macro Extensions");
Ext.setId("/path/to/file.nd2");
Ext.getSeriesCount(seriesCount);
Ext.getSizeX(sizeX); Ext.getSizeY(sizeY); Ext.getSizeZ(sizeZ);
Ext.getSizeC(sizeC); Ext.getSizeT(sizeT);
Ext.getPixelsPhysicalSizeX(pixelWidth);
Ext.getMetadataValue("key", value);
for (s = 0; s < seriesCount; s++) {
    Ext.setSeries(s); Ext.getSeriesName(name);
    Ext.getSizeX(w); Ext.getSizeY(h);
    print("Series " + s + ": " + name + " (" + w + "x" + h + ")");
}
Ext.close();
```

---

## 3. Modern Chunked Formats

| Feature | HDF5 | N5 | OME-Zarr |
|---------|------|-----|----------|
| Structure | Single file | Directory tree | Directory tree |
| Cloud-native | No | Partial | **Yes** |
| Parallel write | No | Yes | Yes |
| Scale pyramids | Via BDV | Yes | Yes |
| Best for | Local large data | Fiji pipelines | Cloud/OMERO sharing |

```javascript
// HDF5
run("HDF5 (new or replace)...");
run("Export Current Image as XML/HDF5");  // BigDataViewer

// N5 / Zarr / OME-NGFF (unified dialog)
run("HDF5/N5/Zarr/OME-NGFF ...");
run("Export Current Image as XML/N5");    // BigDataViewer
```

---

## 4. saveAs() -- Complete Reference

```javascript
saveAs(format, path)    // Save to specific path
saveAs(format)          // Opens Save dialog
```

| Format String | Output | Notes |
|--------------|--------|-------|
| `"Tiff"` | `.tif` | All types, preserves calibration/ROI/overlay/LUT |
| `"Jpeg"` | `.jpg` | Lossy, 8-bit/RGB only |
| `"Gif"` | `.gif` | 8-bit only, stacks→animated |
| `"ZIP"` | `.zip` | Lossless, preserves everything TIFF does |
| `"Raw Data"` | binary | No metadata |
| `"AVI"` | `.avi` | Stacks only, uncompressed |
| `"BMP"` | `.bmp` | 8-bit/RGB only |
| `"PNG"` | `.png` | Lossless, 16-bit downconverted |
| `"PGM"` | `.pgm` | 8-bit grayscale |
| `"FITS"` | `.fits` | 8-bit→16-bit, signed16→float |
| `"Text Image"` | `.txt` | Tab-delimited pixels |
| `"LUT"` | `.lut` | 768 bytes (256 R+G+B) |
| `"Selection"` | `.roi` | Current selection only |
| `"Measurements"` / `"Results"` | `.csv`/`.txt`/`.xls` | Results table |
| `"XY Coordinates"` | `.txt` | Selection boundary pairs |
| `"Text"` | `.txt` | Active text window |

### save() and File Save Commands

```javascript
save("/path/to/output.tif");     // = saveAs("Tiff", path)
run("Save");                      // saves to original path (or dialog)
run("Image Sequence... ", "format=TIFF name=slice_ start=0 digits=4 dir=/path/to/output/");
```

---

## 5. File.* Macro Functions

### Writing

```javascript
f = File.open("/path/to/file.txt");
print(f, "Line 1"); print(f, "Line 2\twith\ttabs"); File.close(f);

File.saveString("content", "/path/to/file.txt");   // one-shot write
File.append("new line", "/path/to/file.txt");       // append
```

### Reading

```javascript
contents = File.openAsString("/path/to/file.txt");
raw = File.openAsRawString("/path/to/file.bin", 1024);  // first N bytes
contents = File.openUrlAsString("https://example.com/data.txt");
```

### File Info & Path Manipulation

| Function | Returns |
|----------|---------|
| `File.exists(path)` | boolean |
| `File.length(path)` | bytes |
| `File.lastModified(path)` | ms since epoch |
| `File.dateLastModified(path)` | human-readable string |
| `File.getName(path)` | `"image.tif"` |
| `File.getNameWithoutExtension(path)` | `"image"` |
| `File.getDirectory(path)` | `"/path/to/"` |
| `File.getParent(path)` | `"/path/to"` |
| `File.isFile(path)` | boolean |
| `File.isDirectory(path)` | boolean |
| `File.separator` | `"/"` or `"\"` |

### Directory & File Management

```javascript
File.makeDirectory("/path/to/new_folder");
File.setDefaultDir("/path/to/working");
path = File.openDialog("Choose a file");
File.openSequence("/path/to/folder/", "virtual filter=.tif");
File.copy("/source/file.tif", "/dest/file.tif");
File.rename("/old/path.tif", "/new/path.tif");
File.delete("/path/to/file.txt");  // returns "1" if successful

// After opening a file:
dir = File.directory;  name = File.name;  nameNoExt = File.nameWithoutExtension;
```

---

## 6. Saving Non-Image Data

### Results Table

```javascript
saveAs("Results", "/path/to/results.csv");           // tab-delimited (.csv/.txt/.xls)
run("Input/Output...", "file=.csv copy_column copy_row save_column save_row");  // configure format
selectWindow("Summary"); saveAs("Results", "/path/to/summary.csv");             // summary table

// Custom CSV
csv = "Image,Cells,Area\n";
csv += title + "," + n + "," + area + "\n";
File.saveString(csv, "/path/to/results.csv");
```

### ROIs

```javascript
saveAs("Selection", "/path/to/my_roi.roi");           // single ROI
roiManager("Deselect"); roiManager("Save", "/path/to/rois.zip");  // all ROIs as ZIP
roiManager("Open", "/path/to/rois.zip");              // load ROIs
```

### Overlays, LUTs, Log

```javascript
// Overlays preserved automatically in TIFF. For non-TIFF:
run("Flatten"); saveAs("PNG", "/path/to/flattened.png");  // WARNING: destructive — burns overlay into pixels

// LUT
saveAs("LUT", "/path/to/custom.lut");
run("LUT... ", "open=/path/to/custom.lut");

// Log
selectWindow("Log"); saveAs("Text", "/path/to/log.txt");
// or: File.saveString(getInfo("log"), "/path/to/log.txt");
```

---

## 7. Bio-Formats Exporter

```javascript
// OME-TIFF (basic / LZW / zlib / JPEG / JPEG-2000)
run("Bio-Formats Exporter", "save=/path/to/output.ome.tif compression=Uncompressed");
run("Bio-Formats Exporter", "save=/path/to/output.ome.tif compression=LZW");
run("Bio-Formats Exporter", "save=/path/to/output.ome.tif compression=zlib");

// BigTIFF (>4 GB) — use .btf or .ome.btf extension
run("Bio-Formats Exporter", "save=/path/to/output.ome.btf compression=LZW");

// Split on export
run("Bio-Formats Exporter", "save=/path/to/out.ome.tif write_each_channel compression=Uncompressed");
```

| Compression | Lossless? | Notes |
|-------------|-----------|-------|
| `Uncompressed` | Yes | Fastest, largest |
| `LZW` | Yes | Good compression, widely compatible |
| `zlib` | Yes | Better than LZW |
| `JPEG` | **No** | Artifacts |
| `J2K` / `JPEG-2000` | Both | Configurable |

---

## 8. Batch Save Patterns

```javascript
// Basic batch
input = "/path/to/input/"; output = "/path/to/output/";
File.makeDirectory(output);
list = getFileList(input);
setBatchMode(true);
for (i = 0; i < list.length; i++) {
    if (endsWith(toLowerCase(list[i]), ".tif")) {
        open(input + list[i]);
        // ... processing ...
        name = File.getNameWithoutExtension(list[i]);
        saveAs("Tiff", output + name + "_processed.tif");
        close();  // ALWAYS close — prevents memory leak
    }
}
setBatchMode(false);

// Overwrite protection
if (!File.exists(outpath)) saveAs("Tiff", outpath);

// Crash-safe checkpoints
if ((i + 1) % 10 == 0) saveAs("Results", output + "results_checkpoint.csv");

// Batch ND2 → OME-TIFF
run("Bio-Formats Importer", "open=[" + input + list[i] + "] autoscale color_mode=Default view=Hyperstack stack_order=XYCZT");
run("Bio-Formats Exporter", "save=[" + output + name + ".ome.tif] compression=LZW");

// Save all open images
for (i = 1; i <= nImages; i++) { selectImage(i); saveAs("Tiff", output + getTitle()); }

// Path separators: ALWAYS use forward slashes in macros (backslashes are escape chars)
saveAs("Tiff", "C:/Users/data/output.tif");  // CORRECT
```

---

## 9. TIFF Deep Dive

### Standard vs ImageJ vs OME-TIFF vs BigTIFF

| Feature | Standard TIFF | ImageJ TIFF | OME-TIFF | BigTIFF |
|---------|--------------|-------------|----------|---------|
| Max size | 4 GB | ~4 GB (non-standard >4 GB) | 4 GB / unlimited (.btf) | 18,000 PB |
| Metadata | Basic tags | ImageDescription + private tags | Full OME-XML | Depends on variant |
| Calibration | No | **Yes** | **Yes** | Depends |
| Channels/Z/T | No | **Yes** | **Yes** | Depends |
| ROI/Overlay | No | **Yes** (private tags 50838-50839) | Partial | Depends |
| Compression (write) | Various | **None** (native) | LZW/zlib/JPEG/None | Same as base |
| Interoperability | Universal | ImageJ/Fiji only | Any Bio-Formats app | Bio-Formats, libTIFF |

### What ImageJ TIFF Private Tags Store
Active ROI, overlay ROIs, per-channel LUTs, slice labels, plot data, custom properties, display ranges.

### Compression Strategies
- **Native `saveAs("Tiff")`**: always uncompressed
- **`saveAs("ZIP")`**: TIFF inside ZIP container
- **Bio-Formats**: `compression=LZW` or `compression=zlib`

### Large Files (>4 GB)
1. ImageJ non-standard extension (may not open elsewhere)
2. BigTIFF via Bio-Formats (`.btf`/`.ome.btf`) — widely compatible
3. Virtual stacks for disk-resident processing
4. Chunked formats (HDF5/N5/OME-Zarr) for >100 GB

---

## 10. Data Integrity

### Lossless Formats

| Format | All Bit Depths? | Calibration? |
|--------|-----------------|--------------|
| TIFF (any compression) | Yes | **Yes** |
| ZIP (ImageJ) | Yes | **Yes** |
| OME-TIFF | Yes | **Yes** |
| PNG | 8-bit only (native) | No |
| Raw | Yes | No |
| HDF5 / N5 | Yes | Partial |

### Bit Depth Loss

| Operation | Result |
|-----------|--------|
| 16/32-bit → JPEG | **Destroyed.** Silent 8-bit conversion + lossy compression |
| 16-bit → PNG (native) | **Downconverted to 8-bit** |
| 16-bit → GIF/BMP | **Downconverted to 8-bit** |
| Composite → JPEG/PNG/GIF | **Channels merged to RGB** — individual channels lost |

### Metadata Loss

| Metadata | TIFF/ZIP | OME-TIFF | PNG/JPEG/GIF/BMP/Raw |
|----------|----------|----------|---------------------|
| Pixel values | Exact | Exact | 8-bit only (or lossy) |
| Calibration | Yes | Yes | **Lost** |
| Channel/Z/T info | Yes | Yes | **Lost** |
| ROIs/Overlays | Yes | Partial/No | **Lost** (flattened) |
| LUTs/Properties | Yes | Yes | **Lost** |

### Rules for Scientific Data
1. Keep originals in acquisition format (`.nd2`, `.czi`, `.lif`)
2. Working copies as TIFF (preserves calibration + ROIs)
3. OME-TIFF for sharing between labs/software
4. PNG for display/figures only
5. **NEVER** JPEG for quantitative data

---

## 11. Format Comparison — When to Use What

| Use Case | Format | Why |
|----------|--------|-----|
| Analysis/processing | TIFF | Preserves everything, fast |
| Archival (small) | ZIP | TIFF + compression |
| Archival (large) | OME-TIFF (LZW) | Compressed, standardized |
| Sharing | OME-TIFF | Universal, self-describing |
| Cloud / OMERO | OME-Zarr | Cloud-native, chunked |
| >4 GB | BigTIFF / OME-Zarr / N5 | No size limit |
| BigDataViewer | XML/HDF5 or XML/N5 | Native BDV |
| Publication figures | TIFF (300 DPI) or PNG | Lossless, journal-accepted |
| Animations | AVI (uncompressed/PNG) or GIF | Per-frame lossless |
| Pixel value export | Text Image | Human-readable |
| ROIs | `.roi` / `.zip` | Standard ImageJ format |
| Measurements | CSV | Universal |

---

## 12. Quick Decision Tree

```
Save scientific image data?
├── For analysis later? → TIFF (or ZIP)
├── Sharing with another lab? → OME-TIFF
├── Larger than 4 GB? → BigTIFF (.btf) / OME-Zarr / N5
├── Publication figure? → TIFF (300 DPI) or PNG
├── Presentation/web? → PNG (or JPEG if size critical)
├── Animation? → AVI (uncompressed/PNG) or GIF
├── BigDataViewer? → XML/HDF5 or XML/N5
├── Cloud/OMERO? → OME-Zarr
├── Pixel values as numbers? → Text Image or CSV
├── ROIs? → roiManager Save as .zip
└── Measurements? → saveAs("Results", path) as .csv
```

---

## 13. Common Pitfalls

| Pitfall | Problem | Fix |
|---------|---------|-----|
| 16-bit → JPEG | Silent 8-bit conversion + lossy = double data loss | Use TIFF |
| 16-bit → PNG (native) | Downconverted to 8-bit | Bio-Formats Exporter or TIFF |
| Composite → JPEG/PNG | Channels merged to RGB, can't separate | Save as TIFF first |
| Stack → JPEG/PNG | Only saves current slice | Use TIFF for full stack, or Image Sequence |
| `saveAs()` renames window | Title changes to saved filename | `rename(originalTitle)` after save |
| Windows backslashes | `\n` = newline, `\t` = tab in macro strings | Always use forward slashes |
| No `close()` in batch | Memory leak, eventual crash | Always `close()` after `saveAs()` |
| Overlays in non-TIFF | Silently flattened into pixels | Save TIFF first, then flatten for display copy |
| Bio-Formats autoscale | May hide true intensity range | Omit `autoscale` or check `getMinAndMax()` |
| Empty/corrupt output | Some plugins fail silently | Verify: `if (File.length(path) < 100) print("WARNING")` |

---

## 14. Journal Submission

| Requirement | Value |
|-------------|-------|
| Format | TIFF (preferred), PNG, EPS, PDF |
| Resolution | 300 DPI photos, 600 DPI line art |
| Colour mode | RGB (not CMYK) |
| Compression | None or lossless only |

```javascript
// Set DPI: 300 DPI × 5 inches = 1500 pixels
run("Size...", "width=1500 height=1000 constrain interpolation=Bicubic");
run("Set Scale...", "distance=300 known=1 unit=inch");
saveAs("Tiff", "/path/to/figure.tif");
```

---

## 15. Java/Groovy Save API (run_script)

### FileSaver

```groovy
import ij.io.FileSaver
def fs = new FileSaver(ij.IJ.getImage())
fs.saveAsTiff("/path/to/output.tif")       // also: saveAsPng, saveAsJpeg, saveAsBmp,
                                             // saveAsGif, saveAsRaw, saveAsZip, saveAsLut
fs.saveAsTiffStack("/path/to/stack.tif")    // multi-page TIFF
FileSaver.setJpegQuality(95)                // 0-100
byte[] data = fs.serialize()                // for TCP/network
```

### IJ Static Methods

```groovy
IJ.save(imp, "/path/to/output.tif")        // auto-detect from extension
IJ.saveAs(imp, "Tiff", "/path/to/out.tif") // explicit format
IJ.saveAsTiff(imp, "/path/to/out.tif")     // returns boolean
IJ.saveString("data", "/path/to/file.txt")
```

### ResultsTable & ROI Manager

```groovy
ResultsTable.getResultsTable().save("/path/to/results.csv")
RoiManager.getInstance().save("/path/to/rois.zip")
```

---

## 16. Agent Integration

```javascript
// Save deliverables NEXT TO source image (not in .tmp/)
dir = getInfo("image.directory");
File.makeDirectory(dir + "AI_output");
saveAs("Tiff", dir + "AI_output/processed.tif");
saveAs("Results", dir + "AI_output/measurements.csv");
roiManager("Save", dir + "AI_output/rois.zip");

// Verify save
print(File.length(dir + "AI_output/processed.tif"));

// Bio-Formats paths with spaces need square brackets
run("Bio-Formats Importer", "open=[C:/Users/Owner/UK Dementia Research Institute Dropbox/file.lif]");
```

| Source | Format | Import Pattern |
|--------|--------|----------------|
| Leica confocal | `.lif` | Bio-Formats, multi-series |
| Nikon | `.nd2` | Bio-Formats, multi-position |
| Incucyte | `.tif` seq | CHRONOS `{PREFIX}_{DD}d{HH}h{MM}m.tif` |
| Output | `.tif` | Standard TIFF, lossless |
| Figures | `.tif`/`.png` | 300 DPI, RGB, flattened overlays |

---

## Sources

- [ImageJ Formats](https://imagej.net/formats/) | [TIFF Format](https://imagej.net/formats/tiff)
- [ImageJ File Menu](https://imagej.net/ij/docs/menus/file.html) | [Macro Functions](https://wsr.imagej.net/developer/macro/functions.html)
- [Bio-Formats Supported Formats](https://bio-formats.readthedocs.io/en/v8.3.0/supported-formats.html)
- [Bio-Formats ImageJ](https://bio-formats.readthedocs.io/en/stable/users/imagej/)
- [N5 Fiji Plugins](https://imagej.net/libs/n5) | [OME-Zarr (Nature Methods)](https://www.nature.com/articles/s41592-021-01326-w)
- [Detect Information Loss](https://imagej.net/imaging/detect-information-loss) | [Scientific Imaging Principles](https://imagej.net/imaging/principles)
