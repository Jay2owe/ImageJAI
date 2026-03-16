# ImageJ Macro Reference for Claude Agent

Quick-reference for all ImageJ macro commands available via `execute_macro`.
Every command below works with: `python ij.py macro 'COMMAND_HERE'`

---

## Image Operations

| Command | Syntax | What it does |
|---------|--------|-------------|
| Open file | `open("/path/to/image.tif");` | Open image from disk |
| Open sample | `run("Blobs (25K)");` | Open built-in sample |
| Close active | `close();` | Close active image |
| Close all | `close("*");` | Close all image windows |
| Save | `save("/path/to/output.tif");` | Save active image |
| Save As | `saveAs("Tiff", "/path/to/output.tif");` | Save in specific format (Tiff, PNG, Jpeg) |
| Duplicate | `run("Duplicate...", "title=copy");` | Duplicate active image |
| Rename | `rename("new_name");` | Rename active image |
| New image | `newImage("blank", "8-bit black", 512, 512, 1);` | Create blank image |
| Select window | `selectWindow("blobs.gif");` | Bring window to front by title |
| Select by ID | `selectImage(1);` | Select image by numeric ID |
| Get title | `title = getTitle();` | Get active image title |
| Get ID | `id = getImageID();` | Get active image ID |
| Get width | `w = getWidth();` | Width in pixels |
| Get height | `h = getHeight();` | Height in pixels |

## Filters

| Command | Syntax | What it does |
|---------|--------|-------------|
| Gaussian Blur | `run("Gaussian Blur...", "sigma=2");` | Smooth/denoise (adjust sigma) |
| Median | `run("Median...", "radius=2");` | Remove salt-and-pepper noise |
| Unsharp Mask | `run("Unsharp Mask...", "radius=3 mask=0.6");` | Sharpen image |
| Subtract Background | `run("Subtract Background...", "rolling=50");` | Remove uneven illumination |
| Enhance Contrast | `run("Enhance Contrast...", "saturated=0.35");` | Stretch histogram |
| Smooth | `run("Smooth");` | 3x3 mean filter |
| Sharpen | `run("Sharpen");` | 3x3 sharpening |
| Minimum (erode) | `run("Minimum...", "radius=1");` | Morphological erosion |
| Maximum (dilate) | `run("Maximum...", "radius=1");` | Morphological dilation |

## Threshold & Binary

| Command | Syntax | What it does |
|---------|--------|-------------|
| Auto threshold | `setAutoThreshold("Otsu");` | Apply auto threshold (Otsu, Triangle, Li, Huang, MaxEntropy, Default, Yen, IsoData) |
| Manual threshold | `setThreshold(50, 255);` | Set specific range |
| Convert to Mask | `run("Convert to Mask");` | Make binary (black/white) |
| Auto Threshold | `run("Auto Threshold", "method=Otsu white");` | Alternative auto threshold |
| Reset threshold | `resetThreshold();` | Remove threshold |
| Watershed | `run("Watershed");` | Separate touching objects |
| Fill Holes | `run("Fill Holes");` | Fill holes in binary objects |

### Threshold method guide:
- **Otsu**: General-purpose, good for bimodal histograms
- **Triangle**: Good when foreground is small relative to background
- **Huang**: Good for fuzzy edges and low contrast
- **Li**: Good for fluorescence microscopy (minimizes cross-entropy)
- **MaxEntropy**: Good for detecting faint objects
- **Default**: ImageJ's built-in IsoData method

## Analysis & Measurement

| Command | Syntax | What it does |
|---------|--------|-------------|
| Measure | `run("Measure");` | Measure current selection or whole image |
| Set Measurements | `run("Set Measurements...", "area mean integrated display redirect=None decimal=3");` | Configure what to measure |
| Analyze Particles | `run("Analyze Particles...", "size=50-Infinity circularity=0.5-1.0 show=Outlines summarize");` | Find and measure objects |
| Summarize | `run("Summarize");` | Add summary stats to Results |
| Get result value | `area = getResult("Area", 0);` | Read from Results table |
| Results count | `n = nResults;` | Number of results rows |
| Set result | `setResult("CTCF", 0, value);` | Write to Results table |
| Update Results | `updateResults();` | Refresh Results display |
| Set Scale | `run("Set Scale...", "distance=100 known=10 unit=um");` | Set pixel calibration |
| Get statistics | `getStatistics(area, mean, min, max);` | Quick stats for selection |

### Analyze Particles options:
- `size=MIN-MAX` — area filter in calibrated units (use `50-Infinity` to skip tiny noise)
- `circularity=MIN-MAX` — 0=line, 1=circle (use `0.5-1.0` for round objects)
- `show=Outlines` or `show=Masks` or `show=Nothing`
- `display` — add to Results table
- `summarize` — add summary row
- `exclude` — exclude objects touching edges

## ROI (Regions of Interest)

| Command | Syntax | What it does |
|---------|--------|-------------|
| Rectangle | `makeRectangle(x, y, w, h);` | Create rectangle selection |
| Oval | `makeOval(x, y, w, h);` | Create oval selection |
| Polygon | `makePolygon(x1,y1, x2,y2, ...);` | Create polygon |
| Line | `makeLine(x1, y1, x2, y2);` | Create line selection |
| Remove selection | `run("Select None");` | Deselect |
| ROI Manager Add | `roiManager("Add");` | Save selection to manager |
| ROI Manager Select | `roiManager("Select", 0);` | Select ROI by index |
| ROI Manager Delete | `roiManager("Delete");` | Delete selected ROI |
| ROI Manager Measure | `roiManager("Measure");` | Measure all ROIs |
| ROI Manager Count | `n = roiManager("Count");` | Count ROIs |
| Set ROI name | `setSelectionName("cell_1");` | Name current selection |
| Get ROI name | `name = Roi.getName;` | Get selection name |

## Stack & Hyperstack

| Command | Syntax | What it does |
|---------|--------|-------------|
| Z Project | `run("Z Project...", "projection=[Max Intensity]");` | Max, Mean, Sum, Min, Median, SD |
| Set slice | `Stack.setSlice(5);` | Go to slice N |
| Set channel | `Stack.setChannel(2);` | Set active channel |
| Set frame | `Stack.setFrame(10);` | Set time frame |
| Get slice count | `n = nSlices;` | Total slices |
| Split Channels | `run("Split Channels");` | Separate channels |
| Merge Channels | `run("Merge Channels...", "c1=C1 c2=C2 create");` | Combine channels |
| Stack to Images | `run("Stack to Images");` | Stack → individual windows |
| Images to Stack | `run("Images to Stack");` | Windows → stack |
| Get dimensions | `getDimensions(w, h, ch, sl, fr);` | Full hyperstack dims |

## Math & Pixel Operations

| Command | Syntax | What it does |
|---------|--------|-------------|
| Subtract value | `run("Subtract...", "value=50");` | Subtract constant |
| Add value | `run("Add...", "value=25");` | Add constant |
| Multiply | `run("Multiply...", "value=1.5");` | Multiply pixels |
| Image Calculator | `imageCalculator("Subtract create", "img1", "img2");` | Pixel math between images |
| Invert | `run("Invert");` | Invert values |
| Log transform | `run("Log");` | Apply log |

## Color & Display

| Command | Syntax | What it does |
|---------|--------|-------------|
| Convert to 8-bit | `run("8-bit");` | 8-bit grayscale |
| Convert to 16-bit | `run("16-bit");` | 16-bit grayscale |
| Convert to RGB | `run("RGB Color");` | RGB color |
| Apply LUT | `run("Fire");` | LUTs: Fire, Green, Grays, Cyan, Magenta, etc. |
| Set display range | `setMinAndMax(0, 200);` | Adjust brightness/contrast |
| Reset display | `resetMinAndMax();` | Auto brightness/contrast |

## Drawing & Annotation

| Command | Syntax | What it does |
|---------|--------|-------------|
| Set color | `setColor(255, 0, 0);` | RGB drawing color |
| Line width | `setLineWidth(2);` | Drawing line width |
| Draw line | `drawLine(x1, y1, x2, y2);` | Line on image |
| Draw rectangle | `drawRect(x, y, w, h);` | Rectangle outline |
| Fill rectangle | `fillRect(x, y, w, h);` | Filled rectangle |
| Draw text | `drawString("text", x, y);` | Text on image |
| Set font | `setFont("SansSerif", 18, "bold");` | Font for text |
| Scale bar | `run("Scale Bar...", "width=50 height=5 font=18 color=White background=None location=[Lower Right] bold");` | Add scale bar |

## Batch & File Operations

| Command | Syntax | What it does |
|---------|--------|-------------|
| Batch mode on | `setBatchMode(true);` | Suppress display (10-100x faster) |
| Batch mode off | `setBatchMode(false);` | Restore display |
| List files | `list = getFileList("/path/to/folder/");` | Get directory listing |
| File exists | `File.exists("/path/to/file")` | Check if file exists |
| Is directory | `File.isDirectory("/path/")` | Check if directory |
| Make directory | `File.makeDirectory("/path/to/output/");` | Create folder |
| Get filename | `name = File.getName("/path/to/image.tif");` | Extract filename |
| Choose folder | `dir = getDirectory("Choose input folder");` | Folder dialog |

---

## Common Recipes

### Cell counting (nuclei)
```
run("Duplicate...", "title=mask");
run("Gaussian Blur...", "sigma=1");
setAutoThreshold("Otsu");
run("Convert to Mask");
run("Watershed");
run("Analyze Particles...", "size=50-5000 circularity=0.5-1.0 show=Outlines display summarize");
```

### CTCF (Corrected Total Cell Fluorescence)
```
// For each cell ROI:
roiManager("Select", cellIdx);
run("Measure");
intDen = getResult("IntDen", nResults-1);
area = getResult("Area", nResults-1);
// Measure background:
roiManager("Select", bgIdx);
run("Measure");
bgMean = getResult("Mean", nResults-1);
ctcf = intDen - (area * bgMean);
```

### Colocalization (two channels)
```
run("Split Channels");
selectWindow("C1-image");
setAutoThreshold("Otsu");
run("Convert to Mask");
selectWindow("C2-image");
setAutoThreshold("Otsu");
run("Convert to Mask");
imageCalculator("AND create", "C1-image", "C2-image");
```

### Batch process folder
```
dir = getDirectory("Choose folder");
list = getFileList(dir);
File.makeDirectory(dir + "output/");
setBatchMode(true);
for (i = 0; i < list.length; i++) {
  if (endsWith(list[i], ".tif")) {
    open(dir + list[i]);
    // ... process ...
    saveAs("Tiff", dir + "output/" + list[i]);
    close();
  }
}
setBatchMode(false);
```

### Line profile
```
makeLine(10, 50, 200, 50);
run("Plot Profile");
```

### Montage
```
run("Make Montage...", "columns=4 rows=3 scale=0.5 border=2");
```

### Bio-Formats import (Nikon .nd2, Leica .lif, Zeiss .czi)
```
run("Bio-Formats Importer", "open=/path/to/file.nd2");
```

---

## Important Best Practices

1. **Always duplicate before destructive ops** (threshold, filters destroy intensity data)
2. **Set scale before measuring** — otherwise everything is in pixels
3. **Save as TIFF, not JPEG** — JPEG destroys intensity data
4. **Use batch mode** for loops (10-100x speed boost)
5. **Watershed** is essential for separating touching round objects
6. **Don't measure intensity from max projections** — use Sum projection instead
7. **N = biological replicates**, not cells (cells from one animal = technical replicates)
8. **Background subtract** before intensity measurements (CTCF or rolling ball)
9. **Avoid RGB** for quantification — use original composite/split channels
10. **Close images in loops** to avoid running out of memory
