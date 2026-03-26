# Publication Figures Reference

Journal-quality scientific figures in ImageJ/Fiji: requirements, macro commands, assembly, export.

---

## Journal Requirements

### Resolution (DPI)

| Content Type | Min DPI | Recommended |
|-------------|---------|-------------|
| Micrographs | 300 | 300-600 |
| Line art / diagrams | 600 | 1000-1200 |
| Combination (photo + text) | 600 | 600 |

### Pixel Dimensions at 300 DPI

| Width | mm | Pixels (300 DPI) | Pixels (600 DPI) |
|-------|-----|-----------------|-----------------|
| Single column | 85-90 | 1005-1063 | 2010-2126 |
| 1.5 columns | 114-140 | 1346-1654 | 2693-3307 |
| Double column | 170-183 | 2008-2161 | 4016-4323 |

### File Formats

| Format | Use | Notes |
|--------|-----|-------|
| **TIFF** | Submission (preferred) | Lossless, 8/16-bit |
| **PNG** | Acceptable alternative | Lossless, 8-bit only |
| **EPS/PDF** | Vector figures, graphs | Scalable |
| **JPEG** | **NEVER for microscopy** | Lossy, destroys data |

### Journal Column Widths

| Journal | Single col | Double col | Notes |
|---------|-----------|-----------|-------|
| Nature | 89 mm | 183 mm | Max 10 MB/fig, TIFF/EPS/PDF, RGB |
| Science | 85 mm | 174 mm | 300 DPI min, TIFF/EPS |
| Cell | 85 mm | 174 mm | 300 DPI min, 600 recommended |
| eLife | 136 mm (max) | — | TIFF/PNG/EPS/PDF |
| PLOS ONE | 83 mm | 173 mm | TIFF/EPS only, 600 for line art |

### Font Requirements

General rule: **Sans-serif (Arial/Helvetica), 6-8 pt minimum at final print size.**

```javascript
// Font size: pixels = pointSize * DPI / 72
// 8pt at 300 DPI = 33 pixels; 8pt at 600 DPI = 67 pixels
dpi = 300;
pixelSize = Math.round(8 * dpi / 72);
setFont("SansSerif", pixelSize, "bold");
```

---

## Cardinal Rules

1. **NEVER modify pixels for display.** Use `setMinAndMax()` only. `run("Enhance Contrast", "normalize")` permanently destroys data.
2. **Identical display settings** across all compared panels (same min/max per channel).
3. **Measure raw data FIRST**, then process a COPY for display.
4. **Linear adjustments only.** Gamma must be disclosed.
5. **No selective enhancement.** Whole-image adjustments only.
6. **Scale bars on all micrographs.**
7. **Colorblind-safe palette.** Green/Magenta or Cyan/Yellow, never Red/Green.
8. **Disclose everything** in Methods.

---

## Display Adjustment (setMinAndMax)

```javascript
setMinAndMax(lower, upper);    // display only, pixels unchanged
resetMinAndMax();              // auto range
getMinAndMax(min, max);        // query current

// Apply SAME range to compared panels
selectWindow("control");  setMinAndMax(100, 3500);
selectWindow("treated");  setMinAndMax(100, 3500);
```

**NEVER use:** `run("Enhance Contrast", "saturated=0.35 normalize");` (destroys data) or `run("Apply LUT");` (converts display to pixels).

---

## LUTs and Color

### Colorblind-Safe Assignments

| Channels | Recommended | Avoid |
|----------|------------|-------|
| 2-channel | Green + Magenta, or Cyan + Yellow | Red + Green |
| 3-channel | Blue + Green + Magenta | RGB direct |
| Intensity map | Grays, Fire, mpl-inferno, mpl-viridis | Rainbow, Jet, Spectrum |

```javascript
run("Green"); run("Magenta"); run("Cyan"); run("Blue"); run("Grays");
run("mpl-inferno");  // perceptually uniform, colorblind-safe
```

### Composite/Merge

```javascript
run("Split Channels");  // creates C1-title, C2-title, ...

// Apply LUTs + display range to each, then merge
run("Merge Channels...", "c1=C1-title c2=C2-title c3=C3-title create");
// c1=red, c2=green, c3=blue, c4=gray, c5=cyan, c6=magenta, c7=yellow

// Convert for export
run("RGB Color");   // composite to RGB
run("Flatten");     // burns overlays AND converts to RGB
```

---

## Scale Bars

```javascript
run("Scale Bar...", "width=50 height=5 font=18 color=White background=None location=[Lower Right] bold overlay");
// width=N: bar width in calibrated units (um, mm)
// height=N: thickness in pixels
// location: [Lower Right], [Lower Left], [Upper Right], [Upper Left], [At Selection]
// overlay: non-destructive (omit to burn into pixels)
// hide: bar only, no text
```

### Size Guidelines

| Magnification | Typical Bar Width |
|---------------|-------------------|
| Low (4-5x) | 500 um - 1 mm |
| Medium (10-20x) | 100-200 um |
| High (40-63x) | 20-50 um |
| Oil (100x) | 5-10 um |
| EM / super-res | 1-5 um or nm |

Rule of thumb: bar ~10-20% of image width. For multi-panel same-magnification figures, consider one scale bar on the last panel with a legend note.

```javascript
// Verify calibration before adding
getPixelSize(unit, pw, ph);
if (unit == "pixels") exit("Image NOT calibrated!");
```

---

## Calibration Bars (Intensity Legends)

For pseudocolored images (Fire, Inferno, etc.) where color represents intensity.

```javascript
run("Calibration Bar...", "location=[Upper Right] fill=Black label=White number=5 decimal=0 font=14 zoom=1 overlay");
```

Not needed for standard fluorescence channels (Green, Magenta) where color = channel identity.

---

## Overlays and Annotations

### Non-Destructive (Preferred)

```javascript
setFont("SansSerif", 24, "bold");
setColor("white");
Overlay.drawString("GFP", 10, 30);     // text at (x, baseline_y)
Overlay.drawLine(x1, y1, x2, y2);
Overlay.drawRect(x, y, w, h);

// Arrow
makeArrow(x1, y1, x2, y2, "filled small");
Overlay.addSelection;

// ROI to overlay
makeRectangle(100, 100, 200, 200);
run("Add Selection...", "stroke=Yellow width=2 fill=None");

Overlay.show; Overlay.hide; Overlay.remove;
```

### Destructive (burns into pixels — use on COPY only)

```javascript
setColor("white"); setFont("SansSerif", 24, "bold");
drawString("Label", x, y);
drawLine(x1, y1, x2, y2);
fillRect(x, y, w, h);
```

**Flatten:** `run("Flatten");` burns all overlays into RGB. Required before saving if using overlays.

---

## Panel Labels (A, B, C)

```javascript
// On single image
setFont("SansSerif", 36, "bold"); setColor("white");
Overlay.drawString("A", 8, 36);

// On montage — calculate panel positions
panelW = getWidth() / cols;
panelH = getHeight() / rows;
labels = newArray("A", "B", "C", "D");
for (r = 0; r < rows; r++)
    for (c = 0; c < cols; c++)
        Overlay.drawString(labels[r*cols+c], c*panelW+8, r*panelH+36);
```

| Style | When |
|-------|------|
| White bold on dark | Fluorescence micrographs |
| Black bold on light | H&E, brightfield |
| Top-left of each panel | Universal convention |

Font size: typically 10-14 pt at final print size (36-48 px for single-column 300 DPI figure).

---

## Montages

```javascript
// From stack/hyperstack
run("Make Montage...", "columns=3 rows=2 scale=1 border=2 font=14 label");
// border=N: gap in pixels. "use": foreground color for borders. "label": slice labels.

// From separate images (must be same size/type)
run("Images to Stack", "use");
run("Make Montage...", "columns=3 rows=1 scale=1 border=2");

// Side-by-side (2 images)
run("Combine...", "stack1=left stack2=right");        // horizontal
run("Combine...", "stack1=top stack2=bottom combine"); // vertical
```

### Channel Panel Montage (common pattern)

```javascript
// Split, apply LUT+range to each, convert to RGB, stack, montage
run("Split Channels");
selectWindow("C1-fig"); run("Blue"); setMinAndMax(50, 2000); run("RGB Color"); rename("DAPI");
selectWindow("C2-fig"); run("Green"); setMinAndMax(100, 3500); run("RGB Color"); rename("GFP");
// Create merge from originals before RGB conversion
run("Images to Stack", "name=Figure use");
run("Make Montage...", "columns=4 rows=1 scale=1 border=2");
```

---

## Insets and Zooms

```javascript
// Mark region on original
makeRectangle(x, y, w, h);
run("Add Selection...", "stroke=Yellow width=2 fill=None");

// Create zoomed inset
run("Duplicate...", "title=inset");
makeRectangle(x, y, w, h); run("Crop");
run("Size...", "width=" + (getWidth()*3) + " height=" + (getHeight()*3) + " interpolation=None");
// interpolation=None for pixel-perfect; Bilinear for smooth

// Place as overlay on main image
selectWindow("original");
run("Add Image...", "image=inset x=350 y=10 opacity=100");
```

---

## Combining Images (Canvas)

```javascript
// Add margins
setBackgroundColor(255, 255, 255);
run("Canvas Size...", "width=" + (getWidth()+80) + " height=" + (getHeight()+80) + " position=Center");

// Manual assembly on blank canvas
newImage("Figure", "RGB Color", 2400, 1800, 1);
selectWindow("panelA"); run("Select All"); run("Copy");
selectWindow("Figure"); makeRectangle(0, 0, w, h); run("Paste"); run("Select None");
```

---

## Figure Export and DPI

```javascript
// Size to target pixel dimensions
targetWidthPx = 1004;  // 85mm at 300 DPI
scale = targetWidthPx / getWidth();
run("Scale...", "x=" + scale + " y=" + scale + " interpolation=Bilinear create title=export");

// Export checklist
if (bitDepth() != 24) run("RGB Color");
if (Overlay.size > 0) run("Flatten");
saveAs("Tiff", "/path/to/Figure1.tif");
```

---

## Batch Figure Generation

```javascript
input = "/path/to/raw/"; output = "/path/to/figures/";
list = getFileList(input);
ch1_min = 50; ch1_max = 2000;  // SAME for all images
ch2_min = 100; ch2_max = 3500;

for (i = 0; i < list.length; i++) {
    if (!endsWith(list[i], ".tif")) continue;
    open(input + list[i]);
    run("Duplicate...", "title=fig duplicate");
    run("Split Channels");
    selectWindow("C1-fig"); run("Green"); setMinAndMax(ch1_min, ch1_max); run("RGB Color"); rename("ch1");
    selectWindow("C2-fig"); run("Magenta"); setMinAndMax(ch2_min, ch2_max); run("RGB Color"); rename("ch2");
    run("Images to Stack", "name=panel use");
    run("Make Montage...", "columns=2 rows=1 scale=1 border=2");
    run("Scale Bar...", "width=50 height=5 font=18 color=White background=None location=[Lower Right] bold overlay");
    run("Flatten");
    saveAs("Tiff", output + replace(list[i], ".tif", "_figure.tif"));
    run("Close All");
}
```

---

## Quantification Panels

| Chart Type | When to Use | Avoid When |
|-----------|-------------|------------|
| Dot plot / strip chart | N < 20 per group | Very large N |
| Box plot | 10-50 per group | N < 5 |
| Violin plot | Large N, show distribution | Small N |
| Bar + individual points | Field convention | — |
| **Bar chart alone** | **AVOID** | Hides distribution |
| **Mean +/- SEM bar** | **AVOID** | Misleads about variability |

**Rules:** Show individual points when N < 20. Use SD for variability, SEM for inference about mean. State exact p-values and test name. Export results as CSV (`saveAs("Results", "/path/to.csv")`) and plot in R/Python/Prism.

**Significance notation:** * p<0.05, ** p<0.01, *** p<0.001, **** p<0.0001, ns p>=0.05.

---

## Accessibility and Color

### Colorblind-Safe Combinations

| Combo | Safe? |
|-------|-------|
| Green + Magenta | YES |
| Cyan + Yellow | YES |
| Cyan + Magenta | YES |
| Blue + Yellow | YES |
| Green + Red | **NO** |

### Wong's Palette (Nature Methods)

| Color | RGB |
|-------|-----|
| Orange | (230, 159, 0) |
| Sky Blue | (86, 180, 233) |
| Bluish Green | (0, 158, 115) |
| Yellow | (240, 228, 66) |
| Blue | (0, 114, 178) |
| Vermillion | (213, 94, 0) |
| Reddish Purple | (204, 121, 167) |

Test with Fiji's **Simulate Color Blindness** plugin or online simulators.

---

## Image Integrity Checklist

- [ ] Measurements on raw data (before display adjustments)
- [ ] Linear display adjustments only (setMinAndMax, no gamma)
- [ ] Same display range for all compared panels
- [ ] No selective enhancement
- [ ] Scale bars on all micrographs (calibrated)
- [ ] Colorblind-safe LUTs
- [ ] Font readable at print size (min 6-8 pt)
- [ ] TIFF or PNG (never JPEG)
- [ ] 300+ DPI for journal
- [ ] All processing documented for Methods
- [ ] No histogram clipping

**Methods template:**
```
Images acquired on [microscope] with [objective]. [Channel] excited at [nm],
collected through [filter]. Processed in Fiji (ImageJ v[X.Y.Z]).
[Background subtracted: rolling ball radius=[N].] [Filter: Gaussian sigma=[N]
for visualization.] Brightness/contrast adjusted identically across panels
(display range: [min]-[max] for [channel]). No non-linear adjustments.
Scale bars: [N] um.
```

---

## Tips and Gotchas

| Mistake | Fix |
|---------|-----|
| JPEG for micrographs | Always TIFF or PNG |
| Different display ranges for control vs treated | Store min/max, apply identically |
| Overlays missing in saved file | `run("Flatten")` before save |
| Scale bar on uncalibrated image | Check `getPixelSize()` first |
| Font too small at print size | Calculate pixels from pt * DPI / 72 |
| RGB conversion before merge | Merge LUT channels first, then RGB/Flatten |
| `Enhance Contrast` with normalize | Use `setMinAndMax()` instead |
| Montage border color unexpected | Set foreground color + "use" flag |
| Serif fonts for labels | Always sans-serif (Arial/Helvetica) |
| Merge-only without individual channels | Most journals require individual channel panels |

### ImageJ Quirks

- `Flatten` converts to RGB permanently.
- Scale bars without `overlay` burn into pixels.
- `Make Montage` requires a stack; use `Images to Stack` first.
- `Combine` works with exactly 2 images at a time.
- Font size in `setFont`/`drawString` is pixels, not points.
- `Overlay.drawString` y coordinate is text baseline, not top.

---

## Agent Integration

```bash
python ij.py state              # check state + calibration
python ij.py metadata           # verify calibration
python ij.py histogram          # check for saturation
python ij.py macro '...'        # run figure creation commands
python ij.py capture step_name  # capture + inspect each step
```

**Agent constraints:** Never `Enhance Contrast` normalize. Always identical display settings. Always capture/inspect. Check calibration before scale bars. Save to `AI_output/` subdirectory.
