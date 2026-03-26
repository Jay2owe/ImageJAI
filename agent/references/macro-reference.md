# ImageJ Macro Reference for Claude Agent

Quick-reference for ImageJ macro commands via `python ij.py macro 'COMMAND'`

---

## Image Operations

| Command | Syntax |
|---------|--------|
| Open file | `open("/path/to/image.tif");` |
| Open sample | `run("Blobs (25K)");` |
| Close active / all | `close();` / `close("*");` |
| Save / Save As | `save("/path.tif");` / `saveAs("Tiff", "/path.tif");` |
| Duplicate | `run("Duplicate...", "title=copy");` |
| Rename | `rename("new_name");` |
| Select window | `selectWindow("blobs.gif");` |
| Get title/dims | `title = getTitle(); w = getWidth(); h = getHeight();` |

## Filters

| Command | Syntax |
|---------|--------|
| Gaussian Blur | `run("Gaussian Blur...", "sigma=2");` |
| Median | `run("Median...", "radius=2");` |
| Unsharp Mask | `run("Unsharp Mask...", "radius=3 mask=0.6");` |
| Subtract Background | `run("Subtract Background...", "rolling=50");` |
| Enhance Contrast | `run("Enhance Contrast...", "saturated=0.35");` |
| Minimum / Maximum | `run("Minimum...", "radius=1");` / `run("Maximum...", "radius=1");` |

## Threshold & Binary

| Command | Syntax |
|---------|--------|
| Auto threshold | `setAutoThreshold("Otsu");` |
| Manual threshold | `setThreshold(50, 255);` |
| Convert to Mask | `run("Convert to Mask");` |
| Watershed | `run("Watershed");` |
| Fill Holes | `run("Fill Holes");` |

### Threshold method guide
- **Otsu**: Bimodal histograms (general-purpose)
- **Triangle**: Small foreground relative to background
- **Huang**: Fuzzy edges, low contrast
- **Li**: Fluorescence microscopy (consider for cross-entropy minimization)
- **MaxEntropy**: Faint objects
- **Default**: IsoData method

## Analysis & Measurement

| Command | Syntax |
|---------|--------|
| Measure | `run("Measure");` |
| Set Measurements | `run("Set Measurements...", "area mean integrated display redirect=None decimal=3");` |
| Analyze Particles | `run("Analyze Particles...", "size=50-Infinity circularity=0.5-1.0 show=Outlines summarize");` |
| Get result | `area = getResult("Area", 0);` |
| Results count | `n = nResults;` |
| Set Scale | `run("Set Scale...", "distance=100 known=10 unit=um");` |
| Quick stats | `getStatistics(area, mean, min, max);` |

### Analyze Particles options
- `size=MIN-MAX` — area filter (calibrated units)
- `circularity=MIN-MAX` — 0=line, 1=circle
- `show=Outlines` / `Masks` / `Nothing`
- `display` — add to Results; `summarize` — summary row; `exclude` — skip edge objects

## ROI

| Command | Syntax |
|---------|--------|
| Rectangle / Oval | `makeRectangle(x,y,w,h);` / `makeOval(x,y,w,h);` |
| Line / Polygon | `makeLine(x1,y1,x2,y2);` / `makePolygon(x1,y1,x2,y2,...);` |
| Deselect | `run("Select None");` |
| ROI Manager | `roiManager("Add");` / `roiManager("Select", 0);` / `roiManager("Measure");` / `roiManager("Count");` |

## Stack & Hyperstack

| Command | Syntax |
|---------|--------|
| Z Project | `run("Z Project...", "projection=[Max Intensity]");` — also Sum, Mean, Min, Median, SD |
| Set slice/channel/frame | `Stack.setSlice(5);` / `Stack.setChannel(2);` / `Stack.setFrame(10);` |
| Split / Merge Channels | `run("Split Channels");` / `run("Merge Channels...", "c1=C1 c2=C2 create");` |
| Get dimensions | `getDimensions(w, h, ch, sl, fr);` |

## Math & Pixel Operations

| Command | Syntax |
|---------|--------|
| Subtract / Add / Multiply | `run("Subtract...", "value=50");` / `run("Add...", "value=25");` / `run("Multiply...", "value=1.5");` |
| Image Calculator | `imageCalculator("Subtract create", "img1", "img2");` |
| Invert | `run("Invert");` |

## Color & Display

| Command | Syntax |
|---------|--------|
| Convert | `run("8-bit");` / `run("16-bit");` / `run("RGB Color");` |
| Apply LUT | `run("Fire");` / `run("Green");` / `run("Cyan");` etc. |
| Display range | `setMinAndMax(0, 200);` / `resetMinAndMax();` |

## Drawing & Annotation

| Command | Syntax |
|---------|--------|
| Scale bar | `run("Scale Bar...", "width=50 height=5 font=18 color=White background=None location=[Lower Right] bold");` |
| Draw text | `setFont("SansSerif", 18, "bold"); drawString("text", x, y);` |
| Draw line/rect | `drawLine(x1,y1,x2,y2);` / `fillRect(x,y,w,h);` |

## Batch & File Operations

| Command | Syntax |
|---------|--------|
| Batch mode | `setBatchMode(true);` / `setBatchMode(false);` |
| List files | `list = getFileList("/path/");` |
| File ops | `File.exists(path)` / `File.makeDirectory(path)` |
| Bio-Formats | `run("Bio-Formats Importer", "open=/path/to/file.nd2");` |

---

## Recipes

### Cell counting
```
run("Duplicate...", "title=mask");
run("Gaussian Blur...", "sigma=1");
setAutoThreshold("Otsu");
run("Convert to Mask");
run("Watershed");
run("Analyze Particles...", "size=50-5000 circularity=0.5-1.0 show=Outlines display summarize");
```

### CTCF
```
roiManager("Select", cellIdx); run("Measure");
intDen = getResult("IntDen", nResults-1);
area = getResult("Area", nResults-1);
roiManager("Select", bgIdx); run("Measure");
bgMean = getResult("Mean", nResults-1);
ctcf = intDen - (area * bgMean);
```

### Batch process folder
```
dir = getDirectory("Choose folder");
list = getFileList(dir);
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

---

## Best Practices

1. Consider duplicating before destructive ops (threshold, filters modify pixel data)
2. Set scale before measuring — otherwise results are in pixels
3. Save as TIFF, not JPEG (JPEG alters pixel values)
4. Use batch mode for loops (10-100x speed)
5. Watershed typically helps separate touching round objects
6. For intensity quantification, consider Sum projection rather than Max
7. N = biological replicates, not cells from one sample
8. Background subtract before intensity measurements
9. Avoid RGB for quantification — use original composite/split channels
10. Close images in loops to manage memory
