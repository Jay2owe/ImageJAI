# ImageJ Macro Language — Exhaustive Reference

Complete reference for the ImageJ/Fiji macro language (`.ijm`). Sourced from
`imagej.net/ij/developer/macro/functions.html` and the *Macro Reference Guide*
PDF. All function signatures and overloads are preserved; duplication has been
trimmed. Section order is optimised for `grep` / `type` lookup by an agent.

Run macro code from the agent with `python ij.py macro '<code>'`. For things
macros can't do (Swing, Java APIs), use `python ij.py script ...`.

---

## 0. Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "What's the signature of `makeLine`?" | §1 function index (A–Z) → points to §3.4 |
| "What options does `run(\"Gaussian Blur...\")` take?" | §2.2 filters |
| "What menu commands are under Process > Binary?" | §2.10 menu tree |
| "Real argument keys for an installed plugin?" | `python ij.py probe "Plugin Name..."` — not here |
| "How do I loop / branch / declare a function?" | §9 language fundamentals |
| "What went wrong?" | §7 gotchas, `python ij.py log` |

---

## 1. Function Index (A–Z)

Alphabetical pointer to the category section containing the full signature.
Use `grep -n` inside that section for the exact overload.

### A
`abs` §3.10 · `acos` §3.10 · `asin` §3.10 · `atan` §3.10 · `atan2` §3.10 ·
`autoUpdate` §3.3 · `Array.*` §3.11

### B
`beep` §5 · `bitDepth` §3.2

### C
`calibrate` §3.2 · `call` §3.22 · `changeValues` §3.3 · `charCodeAt` §3.12 ·
`close` §3.1 · `Color.*` §3.9 · `cos` §3.10

### D
`d2s` §3.12 · `debug` §3.22 · `Dialog.*` §3.15 · `doCommand` §3.22 · `doWand` §3.4 ·
`drawLine / drawOval / drawRect / drawString` §3.8 · `dump` §5

### E
`endsWith` §3.12 · `eval` §3.22 · `exec` §3.22 · `exit` §3.22 · `exp` §3.10 ·
`Ext.*` §3.23

### F
`File.*` §3.16 · `fill / fillOval / fillRect` §3.8 · `Fit.*` §3.18 · `floodFill` §3.8 ·
`floor` §3.10 · `fromCharCode` §3.12

### G
`getArgument` §3.22 · `getBoolean / getNumber / getString` §3.15 ·
`getDateAndTime` §3.21 · `getDimensions` §3.2 · `getDir / getDirectory` §3.1 ·
`getDisplayedArea` §3.1 · `getFileList` §3.1 · `getFontList` §3.21 ·
`getHeight / getWidth` §3.2 · `getHistogram` §3.13 · `getImageID / getImageInfo` §3.1 ·
`getInfo` §3.21 · `getLine` §3.4 · `getList` §3.21 · `getLocationAndSize` §3.1 ·
`getLut` §3.3 · `getMetadata` §3.20 · `getMinAndMax` §3.3 · `getPixel` §3.3 ·
`getPixelSize` §3.2 · `getProfile` §3.17 · `getRawStatistics` §3.13 ·
`getResult / getResultString / getResultLabel` §3.13 · `getSelectionBounds / getSelectionCoordinates` §3.4 ·
`getSliceNumber` §3.6 · `getStatistics` §3.13 · `getStringWidth` §3.8 · `getThreshold` §2.3 ·
`getTime` §3.21 · `getTitle` §3.1 · `getValue` §3.3 · `getVersion` §3.1 ·
`getVoxelSize` §3.2 · `getZoom` §3.1

### I
`IJ.*` §3.22 · `imageCalculator` §2.4 · `indexOf` §3.12 · `is` §3.21 ·
`isActive / isOpen` §3.1 · `isKeyDown` §3.21 · `isNaN` §3.10

### L
`lastIndexOf` §3.12 · `lengthOf` §3.11/§3.12 · `lineTo` §3.8 · `List.*` §3.19

### M
`makeArrow / makeEllipse / makeLine / makeOval / makePoint / makePolygon / makeRectangle / makeRotatedRectangle / makeSelection / makeText` §3.4 ·
`Math.*` §3.10 · `matches` §3.12 · `maxOf / minOf` §3.10 · `moveTo` §3.8

### N
`NaN` §3.10 · `newArray` §3.11 · `newImage` §3.1 · `newMenu` §5 ·
`nImages / nResults / nSlices` §3.1/§3.6/§3.13

### O
`open` §3.1 · `Overlay.*` §3.7

### P
`parseFloat / parseInt` §3.10 · `PI` §3.10 · `Plot.*` §3.17 · `pow` §3.10 ·
`print` §5 · `Property.*` §3.20

### R
`random` §3.10 · `rename` §3.1 · `requires` §3.22 · `reset / resetMinAndMax / resetThreshold` §3.3/§2.3 ·
`restoreSettings` §5 · `Roi.*` §3.4 · `roiManager` §3.5 · `round` §3.10 ·
`run` §2 · `runMacro` §3.22

### S
`save / saveAs` §3.1 · `saveSettings` §5 · `selectImage / selectWindow` §3.1 ·
`selectionContains / selectionType` §3.4 · `setAutoThreshold` §2.3 ·
`setBackgroundColor / setForegroundColor` §3.9 · `setBatchMode` §4 ·
`setColor / setFont / setJustification / setLineWidth` §3.8 ·
`setKeyDown` §3.21 · `setLocation` §3.1 · `setLut` §3.3 · `setMetadata` §3.20 ·
`setMinAndMax` §3.3 · `setOption` §3.11 · `setPasteMode` §3.8 · `setPixel` §3.3 ·
`setResult / setResultLabel` §3.13 · `setSelection / setSelectionLocation / setSelectionName` §3.4 ·
`setSlice / setZCoordinate` §3.6 · `setThreshold` §2.3 · `setTool` §5 ·
`setVoxelSize` §3.2 · `showMessage / showMessageWithCancel / showProgress / showStatus` §3.15 ·
`sin` §3.10 · `snapshot` §3.3 · `split` §3.12 · `sqrt` §3.10 · `Stack.*` §3.6 ·
`startsWith` §3.12 · `String.length` §3.12 · `substring` §3.12

### T
`Table.*` §3.14 · `tan` §3.10 · `toBinary` §3.2 · `toLowerCase / toUpperCase` §3.12 ·
`toScaled / toUnscaled` §3.2 · `updateResults` §3.13

### V / W
`var` §9 · `wait / waitForUser` §3.15

---

## 2. `run(...)` — Running ImageJ Commands

`run()` is the workhorse; it invokes any menu command or plugin.

```ijm
run("Command name");                       // no options
run("Command name", "opt1=val opt2=[two words] flag");
```

Options syntax:
- `key=value` — simple value
- `key=[multi word value]` — wrap multi-word values in `[...]`
- `flag` — boolean flag (presence = true). Append `stack` to apply point ops across slices.

Command names are case-sensitive. Discover unknown plugin args with
`python probe_plugin.py "Plugin Name..."`.

### 2.1 Image I/O
```ijm
run("Bio-Formats Importer", "open=/path/file.nd2");
run("Duplicate...", "title=copy");
run("Duplicate...", "title=substack duplicate range=5-20");   // stack
run("Scale...", "x=0.5 y=0.5 interpolation=Bilinear");
run("Canvas Size...", "width=1024 height=1024 position=Center");
run("Crop");
```

### 2.2 Filters
```ijm
run("Gaussian Blur...",      "sigma=2");
run("Gaussian Blur 3D...",   "x=2 y=2 z=1");
run("Median...",             "radius=2");
run("Median 3D...",          "x=2 y=2 z=1");
run("Mean...",               "radius=2");
run("Minimum...",            "radius=1");
run("Maximum...",            "radius=1");
run("Variance...",           "radius=2");
run("Unsharp Mask...",       "radius=3 mask=0.6");
run("Subtract Background...","rolling=50");
run("Subtract Background...","rolling=50 light");        // dark objects on bright
run("Subtract Background...","rolling=50 sliding");      // paraboloid
run("Convolve...",           "text1=[-1 -1 -1\n-1 8 -1\n-1 -1 -1]");
run("Bandpass Filter...",    "filter_large=40 filter_small=3 suppress=None tolerance=5");
run("Smooth");
run("Sharpen");
run("Find Edges");
run("Find Maxima...",        "prominence=10 output=[Single Points]");
run("FFT");   run("Inverse FFT");
```

### 2.3 Threshold and binary
```ijm
setThreshold(lower, upper);
setThreshold(lower, upper, "raw");          // raw pixel values, ignore calibration
setThreshold(lower, upper, "red");          // display style
setAutoThreshold("Otsu");
setAutoThreshold("Otsu dark");              // dark background
setAutoThreshold("Otsu stack");             // stack-wide histogram
resetThreshold();
getThreshold(lower, upper);                 // -1, -1 if none
run("Threshold...");                        // open interactive adjuster

setOption("BlackBackground", true);
run("Convert to Mask");
run("Convert to Mask", "method=Otsu background=Dark black");
run("Make Binary");

run("Erode");   run("Dilate");   run("Open");   run("Close-");
run("Options...", "iterations=3 count=1 black do=Nothing");   // configure binary ops
run("Fill Holes");
run("Watershed");
run("Skeletonize");
run("Outline");
run("Distance Map");
run("Ultimate Points");
run("Voronoi");
run("Invert");                              // also works on grayscale
run("Invert LUT");

run("Analyze Particles...",
    "size=50-Infinity circularity=0.5-1.0 show=Outlines display summarize exclude clear");
```

`Analyze Particles` options:
- `size=MIN-MAX` — calibrated units unless `pixel` flag
- `circularity=MIN-MAX` — 0 (line) to 1 (circle)
- `show=` — `Nothing Outlines Masks Bare_Outlines Ellipses Count_Masks Overlay Overlay_Masks`
- flags: `display summarize clear add exclude include record in_situ pixel composite`

Threshold methods (from `getList("threshold.methods")`):
`Default Huang Intermodes IsoData Li MaxEntropy Mean MinError Minimum Moments Otsu Percentile RenyiEntropy Shanbhag Triangle Yen`.
Rules of thumb: `Otsu` bimodal general; `Li` fluorescence; `MaxEntropy` faint objects;
`Triangle` small foreground; `Huang` fuzzy edges.

### 2.4 Math / pixel ops / Image Calculator
```ijm
run("Add...",       "value=50");
run("Subtract...",  "value=50");
run("Multiply...",  "value=2");
run("Divide...",    "value=2");
run("AND...",       "value=0x0F");
run("OR...",        "value=0x80");
run("XOR...",       "value=0xFF");
run("Min...",       "value=10");
run("Max...",       "value=240");
run("Gamma...",     "value=0.5");
run("Log");     run("Exp");
run("Square");  run("Square Root");
run("Reciprocal");
run("Abs");
run("NaN Background");
run("Macro...", "code=v=v*2+10");           // arbitrary per-pixel expression

// Append " stack" to any above to apply across all slices.

imageCalculator("Subtract create 32-bit", "img1.tif", "img2.tif");
// Ops: Add Subtract Multiply Divide AND OR XOR Min Max Average Difference Copy
// Modifiers: "create" (new image) · "stack" (all slices) · "32-bit" (float result)
```

### 2.5 Enhance contrast — **caution**
```ijm
run("Enhance Contrast...", "saturated=0.35");              // display only — SAFE
run("Enhance Contrast...", "saturated=0.35 normalize");    // REWRITES PIXELS — never on measurement data
setMinAndMax(0, 200);                                       // display-only, preferred
```

### 2.6 Stacks and hyperstacks
```ijm
run("Z Project...", "projection=[Max Intensity]");         // also Sum, Min, Mean, Median, Standard Deviation
run("Z Project...", "start=1 stop=10 projection=[Max Intensity]");
run("Make Substack...", "channels=1-2 slices=5-20 frames=1");
run("Split Channels");
run("Merge Channels...", "c1=red c2=green create");
run("Stack to Hyperstack...", "order=xyczt(default) channels=2 slices=10 frames=5 display=Composite");
run("Hyperstack to Stack");
run("Reslice [/]...", "output=1.0 start=Top");
run("3D Project...", "projection=[Brightest Point] axis=Y-Axis slice=1 initial=0 total=360 rotation=10");
```

### 2.7 Measurements
```ijm
run("Set Measurements...",
    "area mean standard modal min centroid center perimeter bounding fit shape feret integrated median skewness kurtosis area_fraction stack display redirect=None decimal=3");
run("Set Scale...", "distance=100 known=10 pixel=1 unit=um");
run("Properties...", "unit=um pixel_width=0.5 pixel_height=0.5 voxel_depth=1");
run("Measure");
run("Clear Results");
run("Summarize");
run("Distribution...");
run("Histogram");
run("Plot Profile");
```

Full column list: `area mean standard modal min max center centroid perimeter
bounding fit shape feret's integrated median skewness kurtosis area_fraction
stack limit display invert slice`.

### 2.8 Color / LUT / bit depth
```ijm
run("8-bit");                   // grayscale 0..255
run("16-bit");                  // 0..65535
run("32-bit");                  // float
run("RGB Color");               // 24-bit packed
run("RGB Stack");               // 3-slice stack per channel
run("HSB Stack");
run("8-bit Color", "number=256");
run("Apply LUT");               // bake LUT into pixels (destructive)
run("Grays"); run("Fire"); run("Ice"); run("Spectrum"); run("HiLo");
run("Green"); run("Red"); run("Blue"); run("Cyan"); run("Magenta"); run("Yellow");
// getList("LUTs") returns the full installed set.
```

### 2.9 Drawing / overlay burn-in
```ijm
run("Scale Bar...", "width=50 height=5 font=18 color=White background=None location=[Lower Right] bold");
run("Calibration Bar...", "location=[Upper Right] fill=White label=Black number=5 decimal=0 font=12 zoom=1");
run("Flatten");                 // overlay → RGB image
run("Add Selection...");        // selection → overlay
run("Draw");                    // draws selection outline into pixels
run("Fill");                    // fills selection with foreground
```

### 2.10 Menu tree (discovery — for command names you don't already know)

```
File     Open... · Open Next · Open Sequence... · Open Recent · Close · Close All ·
         Save · Save As > [Tiff, PNG, JPEG, Text Image, ZIP, Raw, AVI] · Revert · Print...
         Import > [Bio-Formats, Text Image, URL, Stack From List, Raw...]

Edit     Undo · Cut · Copy · Paste · Clear · Clear Outside · Fill · Draw · Invert
         Selection > [Select All, Select None, Restore Selection, Create Mask,
                      Create Selection, Make Inverse]
         Options > [Input/Output, Colors, Appearance, Conversions, Memory & Threads]

Image    Type > [8-bit, 16-bit, 32-bit, 8-bit Color, RGB Color, RGB Stack, HSB Stack]
         Adjust > [Brightness/Contrast, Window/Level, Color Balance, Threshold,
                   Color Threshold, Size, Canvas Size, Line Width]
         Show Info · Properties · Crop · Duplicate · Rename · Scale
         Color > [Channels Tool, Merge Channels, Split Channels, Arrange Channels,
                  Edit LUT, Show LUT]
         Stacks > [Add/Delete Slice, Set Slice, Images to Stack, Stack to Images,
                   Plot Z-axis Profile, Z Project, 3D Project, Reslice, Orthogonal
                   Views, Make Substack, Stack to Hyperstack, Hyperstack to Stack,
                   Tools > (Combine, Concatenate, Insert, Plot Histograms, Label),
                   Animation > (Start, Stop, Options)]
         Hyperstacks > [New Hyperstack, Stack to Hyperstack, Hyperstack to Stack,
                        Reduce Dimensionality]
         Transform > [Flip Horizontally/Vertically/Z, Rotate 90° Left/Right, Rotate,
                      Translate, Bin]
         Overlay > [Add Selection, Add Image, Hide/Show Overlay, From/To ROI Manager,
                    Flatten, Remove Overlay, Labels]
         Lookup Tables > [all LUTs]

Process  Smooth · Sharpen · Find Edges · Find Maxima · Enhance Contrast · Subtract Background
         Image Calculator
         Noise > [Add Noise, Add Specified Noise, Salt and Pepper, Despeckle,
                  Remove Outliers, Remove NaNs]
         Shadows > [North, NE, East, ...]
         Binary > [Make Binary, Convert to Mask, Options, Erode, Dilate, Open, Close-,
                   Outline, Fill Holes, Skeletonize, Distance Map, Ultimate Points,
                   Watershed, Voronoi]
         Math > [Add, Subtract, Multiply, Divide, Min, Max, Gamma, Log, Exp, Square,
                 Square Root, Reciprocal, NaN Background, Abs, AND, OR, XOR, Macro]
         FFT > [FFT, Inverse FFT, Redisplay Power Spectrum, FFT Options, Bandpass
                Filter, Custom Filter, FD Math, Swap Quadrants]
         Filters > [Convolve, Gaussian Blur, Median, Mean, Minimum, Maximum, Unsharp
                    Mask, Variance, Show Circular Masks]
         Batch > [Macro, Virtual Stack Opener, Convert]

Analyze  Measure · Analyze Particles · Summarize · Distribution · Label · Clear Results
         Set Measurements · Set Scale · Calibrate · Histogram · Plot Profile · Surface Plot
         Tools > [Analyze Line Graph, Calibration Bar, ROI Manager, Save XY Coordinates,
                  Fractal Box Count, Curve Fitting, Scale Bar, Grid, Synchronize Windows,
                  Macro Recorder]

Plugins  Bio-Formats > [Importer, Exporter, ...]
         Registration > [SIFT, Descriptor-based, StackReg, MultiStackReg]
         Stitching > [Pairwise, Grid/Collection, 3D]
         Segmentation > [Labkit, Trainable Weka, StarDist 2D/3D, Cellpose]
         Tracking > [TrackMate, MTrack2, Manual Tracking]
         3D > [3D Viewer, 3D Objects Counter, 3D Manager, 3D Fast Filters, 3D Suite]
         BigDataViewer · BigStitcher · ABBA
```

---

## 3. Built-in Functions by Category

Signatures and overloads preserved verbatim from the official function index.

### 3.1 Image I/O and Window Management

| Function | Purpose |
|---|---|
| `open(path)` | Open image file |
| `open(path, n)` | Open slice `n` of a stack |
| `open()` | Show Open dialog |
| `newImage(title, type, w, h, depth)` | Blank image — type: `"8-bit black"`, `"16-bit ramp"`, `"32-bit white"`, `"RGB random"`, etc. |
| `close()` | Close active image |
| `close("title")` | Close named window |
| `close("*")` | Close all images |
| `close("\\Others")` | Close all except active |
| `close("Results" \| "Log" \| "ROI Manager")` | Close named non-image windows |
| `save(path)` | Save in format inferred from extension |
| `saveAs(format, path)` | `"Tiff"`, `"PNG"`, `"Jpeg"`, `"Text Image"`, `"ZIP"`, `"Raw"`, `"Results"`, `"Measurements"`, `"Selection"` (.roi), `"XY Coordinates"` |
| `rename(newName)` | Rename active image |
| `selectWindow(title)` | Activate window by title |
| `selectImage(id \| title)` | Activate image by ID (negative int) or title |
| `getTitle()` | Title of active image |
| `getImageID()` | Unique negative ID |
| `getImageInfo()` | Formatted info string |
| `getDirectory(prompt \| "image" \| "home" \| "startup" \| "imagej" \| "plugins" \| "macros" \| "luts" \| "temp" \| "current")` | Directory paths |
| `getDir(...)` | Alias for `getDirectory` |
| `getFileList(dir)` | Array of filenames |
| `getVersion()` | ImageJ version string |
| `isOpen(title \| id)` | Boolean |
| `isActive(id)` | Boolean |
| `nImages` | Count of open images |
| `getLocationAndSize(x, y, w, h)` | Window bounds |
| `setLocation(x, y)` | Move window |
| `getDisplayedArea(x, y, w, h)` | Visible canvas region |
| `getZoom()` | Magnification factor |

### 3.2 Geometry, Dimensions, Calibration

| Function | Purpose |
|---|---|
| `getWidth()` / `getHeight()` | Pixel dims |
| `getDimensions(w, h, ch, sl, fr)` | Full dims (by reference) |
| `bitDepth()` | 8, 16, 24 (RGB), or 32 |
| `getPixelSize(unit, pw, ph)` | Returns unit + pixel width/height |
| `getVoxelSize(w, h, d, unit)` | 3D calibration |
| `setVoxelSize(w, h, d, unit)` | Set 3D calibration |
| `calibrate(raw)` | Raw → calibrated pixel value |
| `toScaled(x, y)` / `toUnscaled(x, y)` | Pixel ↔ calibrated coords |
| `toBinary(string)` | Convert string to 8-bit binary image |

### 3.3 Pixel Access and Display Range

| Function | Purpose |
|---|---|
| `getPixel(x, y)` | Raw value (int for 8/16-bit, float for 32-bit, packed RGB int) |
| `getValue(x, y)` | Calibrated value |
| `setPixel(x, y, value)` | Write pixel |
| `changeValues(v1, v2, v3)` | Replace pixels in `[v1, v2]` with `v3` |
| `getMinAndMax(min, max)` | Current display range |
| `setMinAndMax(min, max)` | Display range (safe — no data change) |
| `resetMinAndMax()` | Reset to image min/max |
| `getLut(r, g, b)` | Read LUT as three 256-element arrays |
| `setLut(r, g, b)` | Set LUT |
| `snapshot()` | Save state for `reset()` |
| `reset()` | Restore last snapshot |
| `autoUpdate(boolean)` | Disable redraw during pixel loops |
| `floodFill(x, y)` / `floodFill(x, y, "8-connected")` | Flood fill (destructive) |

Bulk pixel work: prefer TCP `get_pixels` + numpy.

### 3.4 Selections (ROIs)

**Create:**
| Function | Selection |
|---|---|
| `makeRectangle(x, y, w, h)` | Rectangle |
| `makeRectangle(x, y, w, h, arcSize)` | Rounded rectangle |
| `makeOval(x, y, w, h)` | Oval |
| `makeEllipse(x1, y1, x2, y2, aspectRatio)` | Ellipse via major axis |
| `makeLine(x1, y1, x2, y2)` | Straight line |
| `makeLine(x1, y1, x2, y2, width)` | Line with width |
| `makeLine(x1, y1, x2, y2, x3, y3, ...)` | Segmented line |
| `makePolygon(x1, y1, x2, y2, x3, y3, ...)` | Polygon |
| `makePoint(x, y)` | Point |
| `makePoint(x, y, "small yellow hybrid")` | Point with options |
| `makeArrow(x1, y1, x2, y2, "notched large outline")` | Arrow |
| `makeRotatedRectangle(x1, y1, x2, y2, width)` | Rotated rectangle |
| `makeText(text, x, y)` | Text selection |
| `makeSelection(type, xs, ys)` | From arrays (type: `"polygon"`, `"freehand"`, `"polyline"`, `"freeline"`, `"angle"`, `"point"`) |
| `doWand(x, y)` | Wand tool (default) |
| `doWand(x, y, tol, mode)` | Wand — mode: `"Legacy"`, `"4-connected"`, `"8-connected"` |

**Query / manipulate:**
| Function | Returns |
|---|---|
| `selectionType()` | 0=rect, 1=oval, 2=polygon, 3=freehand, 4=line, 5=segmented, 6=freeline, 7=polyline, 8=none, 9=composite, 10=point |
| `getSelectionBounds(x, y, w, h)` | Bounding rect |
| `getSelectionCoordinates(xs, ys)` | Vertex arrays |
| `getLine(x1, y1, x2, y2, lineWidth)` | Current line selection (by reference) |
| `selectionContains(x, y)` | Boolean |
| `setSelection(x, y, w, h)` | Rectangle selection |
| `setSelectionLocation(x, y)` | Move selection |
| `setSelectionName(name)` | Label current selection |
| `Roi.size` | Vertex/point count |
| `Roi.contains(x, y)` | Boolean |
| `Roi.getBounds(x, y, w, h)` | Int bounds |
| `Roi.getFloatBounds(x, y, w, h)` | Float bounds |
| `Roi.getCoordinates(xs, ys)` | Vertex coords |
| `Roi.getContainedPoints(xs, ys)` | All pixel coords inside |
| `Roi.getName()` | Current name |
| `Roi.getProperty(key)` / `Roi.setProperty(key, value)` | Custom props |
| `Roi.getDefaultColor / getStrokeColor / getFillColor` | Colors |
| `is("area")` / `is("line")` | Selection-type checks |

### 3.5 ROI Manager

```ijm
roiManager("Add");
roiManager("Add", color, lineWidth);
roiManager("Select", index);
roiManager("Select", newArray(0, 2, 5));        // multi-select
roiManager("Deselect");
roiManager("Rename", "new name");
roiManager("Delete");
roiManager("Reset");                             // clear all
roiManager("Measure");
roiManager("Count");                             // returns count
roiManager("Save", "/path/rois.zip");
roiManager("Open", "/path/rois.zip");
roiManager("Show All");   roiManager("Show None");
roiManager("Sort");
roiManager("Remove Channel Info");
roiManager("Remove Slice Info");
roiManager("Remove Frame Info");
roiManager("Associate", "true");
roiManager("Set Color", "red");
roiManager("Set Line Width", 2);
roiManager("UseNames", "true");
```

### 3.6 Stacks and Hyperstacks — `Stack.*`

| Function | Purpose |
|---|---|
| `Stack.getDimensions(w, h, ch, sl, fr)` | Full hyperstack dims |
| `Stack.getPosition(ch, sl, fr)` | Current position |
| `Stack.setPosition(ch, sl, fr)` | Jump to position |
| `Stack.setSlice(n)` / `setSlice(n)` | Set slice (1..nSlices) |
| `Stack.setChannel(c)` | Set channel |
| `Stack.setFrame(t)` | Set frame |
| `Stack.getActiveChannels()` | String of 1s/0s, e.g. `"1100"` |
| `Stack.setActiveChannels("1100")` | Toggle channels |
| `Stack.setDisplayMode("composite" \| "color" \| "grayscale")` | Display mode |
| `Stack.getDisplayMode()` | Current display mode |
| `Stack.isHyperstack` | Boolean |
| `Stack.setXUnit / setYUnit / setZUnit / setTUnit(unit)` | Per-axis units |
| `Stack.setUnits(x, y, z, t, value)` | All units |
| `Stack.getUnits(x, y, z, t, value)` | Read units |
| `Stack.setFrameRate(fps)` / `Stack.getFrameRate()` | Animation speed |
| `Stack.setFrameInterval(sec)` / `Stack.getFrameInterval()` | Physical interval |
| `Stack.setTDimension(n)` / `Stack.setZDimension(n)` | Re-dimension |
| `Stack.setDimensions(c, z, t)` | Set all three |
| `Stack.getStatistics(n, mean, min, max, std)` | Whole-stack stats |
| `Stack.getOrthoViewsID()` / `Stack.getOrthoViewsIDs()` | Ortho view IDs |
| `Stack.startOrthoViews()` / `Stack.stopOrthoViews()` | Ortho views |
| `Stack.setOrthoViews(x, y, z)` | Set ortho position |
| `Stack.swap(n1, n2)` | Swap two slices |
| `setZCoordinate(z)` | Set Z in hyperstack |
| `getSliceNumber()` | Current slice (1-based) |
| `nSlices` | Slice count |

### 3.7 Overlay — `Overlay.*`

Non-destructive vector layer over the image.

| Function | Purpose |
|---|---|
| `Overlay.moveTo(x, y)` / `Overlay.lineTo(x, y)` | Path building |
| `Overlay.drawLine(x1, y1, x2, y2)` | Line |
| `Overlay.drawRect(x, y, w, h)` | Rectangle outline |
| `Overlay.drawEllipse(x, y, w, h)` | Ellipse outline |
| `Overlay.drawString(text, x, y)` | Text |
| `Overlay.drawString(text, x, y, angle)` | Rotated text |
| `Overlay.add()` | Commit path to overlay |
| `Overlay.addSelection()` | Add current selection |
| `Overlay.addSelection(strokeColor)` | With stroke color |
| `Overlay.addSelection(strokeColor, strokeWidth)` | With color + width |
| `Overlay.addSelection("", 0, fillColor)` | With fill color |
| `Overlay.setPosition(n)` | Slice for last item |
| `Overlay.setPosition(c, z, t)` | Hyperstack position |
| `Overlay.show()` / `Overlay.hide()` / `Overlay.hidden()` | Visibility |
| `Overlay.clear()` / `Overlay.remove()` | Clear / delete |
| `Overlay.size()` | Item count |
| `Overlay.activateSelection(i)` / `Overlay.activateSelectionAndWait(i)` | Re-activate item |
| `Overlay.removeSelection(i)` / `Overlay.update(i)` / `Overlay.moveSelection(i, x, y)` | Manipulate |
| `Overlay.indexAt(x, y)` | Item at point |
| `Overlay.getBounds(i, x, y, w, h)` | Item bounds |
| `Overlay.removeRois(name)` | Remove by name |
| `Overlay.copy()` / `Overlay.paste()` | Clipboard |
| `Overlay.drawLabels(boolean)` | Labels on/off |
| `Overlay.setLabelFontSize(size, options)` | Label font |
| `Overlay.setLabelColor(color)` | Label color |
| `Overlay.useNamesAsLabels(boolean)` | Names vs indexes |
| `Overlay.selectable(false)` | Lock |
| `Overlay.measure()` | Measure every item → Results |
| `Overlay.setStrokeColor(color)` / `Overlay.setStrokeWidth(w)` | Style all |
| `Overlay.fill(color)` / `Overlay.fill(outline, fill)` | Fill all |
| `Overlay.flatten()` | Burn into RGB image |
| `Overlay.cropAndSave(dir, format)` | Save each item as cropped image |
| `Overlay.xor(indexArray)` | XOR selected items |

### 3.8 Drawing on the Image (Destructive)

| Function | Purpose |
|---|---|
| `setColor(color)` / `setColor(r, g, b)` / `setColor(value)` | Drawing color |
| `setFont(name, size, style)` | style: `"plain"`, `"bold"`, `"italic"`, `"bold italic"`, `"antialiased"` |
| `setJustification("left" \| "center" \| "right")` | Text alignment |
| `setLineWidth(px)` | Line thickness |
| `drawLine(x1, y1, x2, y2)` | Line |
| `drawRect(x, y, w, h)` / `drawOval(x, y, w, h)` | Outlines |
| `drawString(text, x, y)` | Text |
| `drawString(text, x, y, background)` | Text with background |
| `fillRect(x, y, w, h)` / `fillOval(x, y, w, h)` | Filled shapes |
| `fill()` | Fill selection with foreground |
| `lineTo(x, y)` / `moveTo(x, y)` | Path drawing |
| `getStringWidth(text)` | Pixel width of text |
| `setPasteMode(mode)` | `"Copy"`, `"Blend"`, `"Average"`, `"Difference"`, `"Transparent"`, `"AND"`, `"OR"`, `"XOR"`, `"Add"`, `"Subtract"`, `"Multiply"`, `"Divide"` |

### 3.9 Color — `Color.*`

| Function | Purpose |
|---|---|
| `Color.set(name \| hex)` | Drawing color by name/hex |
| `Color.set(value)` | Drawing color by pixel value |
| `Color.setForeground(name \| hex)` | Foreground by name/hex |
| `Color.setForeground(r, g, b)` | Foreground by RGB |
| `Color.setForegroundValue(v)` | Foreground grayscale value |
| `Color.setBackground(...)` / `Color.setBackgroundValue(v)` | Background (same overloads) |
| `setForegroundColor(r, g, b)` / `setBackgroundColor(r, g, b)` | Top-level aliases |
| `Color.foreground` / `Color.background` | Current FG/BG strings |
| `Color.toString(r, g, b)` | RGB → hex string |
| `Color.toArray(string)` | Hex/name → `[r, g, b]` |
| `Color.getLut(r, g, b)` / `Color.setLut(r, g, b)` | LUT arrays |
| `Color.wavelengthToColor(nm)` | Wavelength → colour |

Names: `black white red green blue yellow cyan magenta orange pink gray darkGray lightGray`.
Hex: `"#rrggbb"` or `"#aarrggbb"`.

### 3.10 Math — `Math.*` (and top-level aliases)

Top-level forms (unprefixed) are identical to the `Math.*` version where both
exist: `abs acos asin atan atan2 cos exp floor log pow round sin sqrt tan`.
`Math.*` includes additional functions:

| Function | Purpose |
|---|---|
| `Math.abs(n)` | Absolute value |
| `Math.acos(n)` / `Math.asin(n)` / `Math.atan(n)` | Inverse trig (radians) |
| `Math.atan2(y, x)` | Two-arg arctan |
| `Math.ceil(n)` | Smallest integer ≥ n |
| `Math.cos(angle)` / `Math.sin(angle)` / `Math.tan(angle)` | Trig (radians) |
| `Math.erf(x)` | Error function |
| `Math.exp(n)` | e^n |
| `Math.floor(n)` | Largest integer ≤ n |
| `Math.log(n)` | Natural log |
| `Math.log10(n)` | Base-10 log |
| `Math.min(a, b)` / `Math.max(a, b)` | Extremes (top-level: `minOf` / `maxOf`) |
| `Math.pow(base, exp)` | Exponentiation (also `^`) |
| `Math.round(n)` | Nearest integer |
| `Math.sqr(n)` | n² |
| `Math.sqrt(n)` | √n |
| `Math.toRadians(deg)` / `Math.toDegrees(rad)` | Angle conversion |
| `Math.constrain(n, min, max)` | Clamp to range |
| `Math.map(n, lo1, hi1, lo2, hi2)` | Linear remap |
| `parseFloat(s)` / `parseInt(s)` / `parseInt(s, radix)` | String → number |
| `parseFloat("Infinity" \| "-Infinity" \| "NaN")` | Special literals |
| `random()` | Uniform `[0, 1)` |
| `random("gaussian")` | N(0, 1) |
| `random("seed", seed)` | Set seed |
| `isNaN(n)` | Test NaN |
| `NaN` / `PI` | Constants |

### 3.11 Arrays — `Array.*`

```ijm
a = newArray(5);                        // zero-filled length 5
b = newArray(1, 2, 3, 4);               // literal
c = Array.getSequence(10);              // [0..9]
setOption("ExpandableArrays", true);    // allow a[a.length] = x
```

| Function | Purpose |
|---|---|
| `a.length` | Length |
| `lengthOf(a)` | Length (works for arrays and strings) |
| `Array.copy(a)` | Duplicate |
| `Array.concat(a, b, ...)` | Join arrays or values |
| `Array.slice(a, start, end)` | Subarray `[start, end)` |
| `Array.trim(a, n)` | First n elements |
| `Array.reverse(a)` | Reverse in place |
| `Array.rotate(a, d)` | Rotate by d |
| `Array.fill(a, value)` | Fill |
| `Array.sort(a)` | Ascending sort |
| `Array.sort(a, b, c, ...)` | Sort first, permute others to match |
| `Array.rankPositions(a)` | Indices that would sort `a` |
| `Array.resample(a, len)` | Linear resample |
| `Array.filter(a, regex)` | Keep matching strings |
| `Array.deleteValue(a, v)` | Remove all `v` |
| `Array.deleteIndex(a, i)` | Remove element `i` |
| `Array.findMaxima(a, tol)` / `Array.findMinima(a, tol)` | Peak indices |
| `Array.fourier(a, windowType)` | Power spectrum — windowType: `"none"`, `"Hamming"`, `"Hann"`, `"flat-top"` |
| `Array.getStatistics(a, min, max, mean, std)` | Stats by reference |
| `Array.getVertexAngles(xs, ys, arm)` | Vertex angles along contour |
| `Array.print(a)` | Log one line |
| `Array.show(a)` | Display in window |
| `Array.show("title", a1, a2, ...)` | Multiple arrays as table |

`a[-1]` is NOT valid — use `a[a.length-1]`.

### 3.12 Strings

| Function | Purpose |
|---|---|
| `lengthOf(s)` / `String.length` (≥ 1.52t) | Length |
| `toUpperCase(s)` / `toLowerCase(s)` | Case |
| `indexOf(s, sub)` / `indexOf(s, sub, fromIndex)` | First occurrence |
| `lastIndexOf(s, sub)` | Last occurrence |
| `substring(s, start, end)` | `[start, end)` |
| `startsWith(s, prefix)` / `endsWith(s, suffix)` | Boolean |
| `replace(s, old, new)` | Replace (regex if multi-char) |
| `split(s, delimiter)` | Array |
| `charCodeAt(s, i)` | Unicode value |
| `fromCharCode(v1, ..., vN)` | Codes → string |
| `parseInt(s)` / `parseFloat(s)` | String → number |
| `d2s(n, decimalPlaces)` | Number → string |
| `matches(s, regex)` | Regex match |

`"..."` only; no single quotes. Concatenation with `+`. `==` is case-sensitive.

### 3.13 Results Table

```ijm
run("Measure");                          // active selection / whole image
roiManager("Measure");                   // every ROI
getStatistics(area, mean, min, max, std, histogram);    // calibrated, current selection
getRawStatistics(n, mean, min, max, std, histogram);    // uncalibrated, raw counts
getHistogram(values, counts, nBins, histMin, histMax);  // custom histogram
```

| Function | Purpose |
|---|---|
| `nResults` | Row count (no parens) |
| `getResult("Column", row)` | Numeric value |
| `getResult("Column")` | Last row |
| `getResultString("Column", row)` | String value |
| `getResultLabel(row)` | Row label |
| `setResult("Column", row, value)` | Write value |
| `setResultLabel(label, row)` | Write label |
| `updateResults()` | Refresh table |
| `String.copyResults()` | Copy Results CSV to clipboard |
| `IJ.renameResults(name)` | Rename current Results window |
| `IJ.renameResults(old, new)` | Rename named table |
| `IJ.deleteRows(first, last)` | Delete rows |

`getValue("X")` shortcuts (last measurement or state): `"results.count"`, `"Area"`,
`"Mean"`, `"IntDen"`, `"selection.size"`, `"selection.width"`, `"rotation.angle"`,
`"image.size"`, `"font.size"`, `"font.height"`, `"color.foreground"`,
`"color.background"`, `"rgb.foreground"`, `"rgb.background"`.

### 3.14 Tables — `Table.*`

Works with the Results window or any named table. Omit the title argument to
target the active table.

```ijm
Table.create("Cells");                    // new empty table
Table.setColumn("Name", namesArray, "Cells");
Table.getColumn("Name", "Cells");
Table.set("Name", row, value, "Cells");
Table.get("Name", row, "Cells");
Table.getString("Name", row, "Cells");
Table.size("Cells");
Table.headings;                           // CSV headings string
Table.title;                              // current table title
Table.rename("Cells", "Cells_v2");
Table.update();                           // redraw
Table.reset("Cells");                     // clear rows
Table.deleteColumn("Name", "Cells");
Table.deleteRows(first, last, "Cells");
Table.save("/path/out.csv");
Table.save("/path/out.csv", "Cells");
Table.applyMacro("Ratio = Mean / Area", "Cells");
Table.sort("Area", "Cells");
Table.showRowIndexes(true);
Table.showRowNumbers(true);
Table.showArrays("Combined", a1, a2, a3);
```

### 3.15 Dialogs and User Input

**One-shot prompts:**
```ijm
n  = getNumber("Sigma:", 2);
ok = getBoolean("Proceed?");                  // yes/no/cancel — cancel aborts
ok = getBoolean("Proceed?", "Yes", "No");     // custom labels
s  = getString("Filename:", "out.tif");
showMessage("Done.");
showMessage("Title", "body text");
showMessageWithCancel("Title", "body");       // aborts if Cancel
```

**Status / progress:**
```ijm
showStatus("Processing slice " + i);
showProgress(i, n);
beep();
wait(500);                                    // ms
waitForUser("Click OK when ready");
waitForUser("Title", "Multi-line\nmessage");
```

**Composite `Dialog.*`:**

| Function | Purpose |
|---|---|
| `Dialog.create("Title")` | Modal dialog |
| `Dialog.createNonBlocking("Title")` | Non-modal dialog |
| `Dialog.addMessage(string)` | Message text |
| `Dialog.addMessage(string, fontSize, fontColor)` | Formatted message |
| `Dialog.addString(label, initialText)` | Text field |
| `Dialog.addString(label, initialText, columns)` | Sized text field |
| `Dialog.addNumber(label, default)` | Numeric field |
| `Dialog.addNumber(label, default, decimalPlaces, columns, units)` | Formatted number |
| `Dialog.addSlider(label, min, max, default)` | Slider |
| `Dialog.addCheckbox(label, default)` | Checkbox |
| `Dialog.addCheckboxGroup(rows, cols, labels, defaults)` | Grid |
| `Dialog.addRadioButtonGroup(label, items, rows, cols, default)` | Radio buttons |
| `Dialog.addChoice(label, items)` | Dropdown |
| `Dialog.addChoice(label, items, default)` | Dropdown w/ default |
| `Dialog.addDirectory(label, defaultPath)` | Directory picker |
| `Dialog.addDirectory(label, defaultPath, columns)` | Sized directory picker |
| `Dialog.addFile(label, defaultPath)` | File picker |
| `Dialog.addFile(label, defaultPath, columns)` | Sized file picker |
| `Dialog.addImage(pathOrURL)` | Image to dialog |
| `Dialog.addImageChoice(label)` | Image selection |
| `Dialog.addImageChoice(label, defaultImage)` | With default |
| `Dialog.addHelp(url)` | Help button |
| `Dialog.addToSameRow()` | Next item inline |
| `Dialog.setInsets(top, left, bottom)` | Component margins |
| `Dialog.setLocation(x, y)` / `Dialog.getLocation(x, y)` | Window position |
| `Dialog.show()` | Display (blocks) |
| `Dialog.getString / getNumber / getCheckbox / getChoice / getRadioButton / getImageChoice` | Read in add-order |

### 3.16 File I/O — `File.*`

| Function | Purpose |
|---|---|
| `File.exists(path)` | Boolean |
| `File.isFile(path)` / `File.isDirectory(path)` | Type |
| `File.length(path)` | Bytes |
| `File.lastModified(path)` | ms since epoch |
| `File.dateLastModified(path)` | String date |
| `File.getName(path)` / `File.getNameWithoutExtension(path)` | Filename |
| `File.getDirectory(path)` | Parent dir (trailing sep) |
| `File.getParent(path)` | Parent dir |
| `File.getDefaultDir` / `File.setDefaultDir(dir)` | Default dir for dialogs |
| `File.separator` | `/` or `\` |
| `File.name` / `File.nameWithoutExtension` / `File.directory` | Last opened/saved |
| `File.makeDirectory(path)` | mkdir |
| `File.rename(src, dst)` | Rename / move |
| `File.copy(src, dst)` | Copy |
| `File.delete(path)` | Delete file or empty dir |
| `f = File.open(path)` | Open for writing |
| `print(f, text)` | Write to handle |
| `File.close(f)` | Close handle |
| `File.saveString(s, path)` | One-shot write |
| `File.append(s, path)` | Append line |
| `File.openAsString(path)` | Read entire file |
| `File.openAsRawString(path)` | First 5000 bytes |
| `File.openAsRawString(path, count)` | First N bytes |
| `File.openUrlAsString(url)` | Fetch URL |
| `File.openSequence(dir, "virtual filter=.tif")` | Open dir images as stack |
| `File.openDialog(title)` | Show open dialog |

### 3.17 Plots — `Plot.*`

```ijm
Plot.create("Title", "x label", "y label", xArr, yArr);
Plot.create("Title", "{Jan,Feb,Mar}", "Count");          // categorical
profile = getProfile();                                   // array along line selection
```

| Function | Purpose |
|---|---|
| `Plot.add(type, xs, ys)` | type: `line`, `circles`, `boxes`, `cross`, `triangles`, `dot`, `x`, `bar` |
| `Plot.add(type, xs, ys, label)` | With dataset label |
| `Plot.addHistogram(values)` / `Plot.addHistogram(values, binWidth, binCenter)` | Histogram |
| `Plot.addText(text, x, y)` | Text label |
| `Plot.drawVectors(x0, y0, x1, y1)` | Arrows |
| `Plot.drawLine(x1, y1, x2, y2)` | Line in plot coords |
| `Plot.drawNormalizedLine(x1, y1, x2, y2)` | Line in 0-1 coords |
| `Plot.drawShapes("rectangles", L, T, R, B)` | Shapes |
| `Plot.drawBoxes("boxes width=30", x, y1, y2, y3, y4, y5)` | Boxplot |
| `Plot.drawGrid()` | Redraw grid |
| `Plot.setLimits(xMin, xMax, yMin, yMax)` | Axis ranges |
| `Plot.getLimits(xMin, xMax, yMin, yMax)` | Read ranges |
| `Plot.setLimitsToFit()` | Auto-scale |
| `Plot.setColor(color)` / `Plot.setColor(line, fill)` | Colors |
| `Plot.setBackgroundColor(color)` | Bg |
| `Plot.setLineWidth(w)` | Width |
| `Plot.setJustification(string)` | Text align |
| `Plot.setLegend(labels, options)` | Legend |
| `Plot.setFrameSize(w, h)` | Frame px |
| `Plot.getFrameBounds(x, y, w, h)` | Bounds |
| `Plot.setLogScaleX(boolean)` / `Plot.setLogScaleY(boolean)` | Log axes |
| `Plot.setXYLabels(x, y)` | Rename axes |
| `Plot.setFontSize(size, options)` / `Plot.setAxisLabelSize(size, options)` | Fonts |
| `Plot.setFormatFlags("11001100001111")` | 14-bit axis flags |
| `Plot.setStyle(i, "color=red,width=2,marker=circle,visible=true")` | Series style |
| `Plot.freeze(boolean)` | Freeze plot |
| `Plot.setOptions(string)` | Options |
| `Plot.replace(i, type, xs, ys)` | Replace series |
| `Plot.useTemplate(name \| id)` | Copy formatting |
| `Plot.makeHighResolution(factor)` | Enlarged copy |
| `Plot.appendToStack()` | Append to virtual stack |
| `Plot.show()` / `Plot.update()` | Display |
| `Plot.getValues(xs, ys)` | Retrieve values |
| `Plot.showValues()` / `Plot.showValuesWithLabels()` | To Results |
| `Plot.removeNaNs()` | Drop NaNs |
| `Plot.enableLive(boolean)` | Live mode |

### 3.18 Curve Fitting — `Fit.*`

| Function | Purpose |
|---|---|
| `Fit.doFit(equation, xs, ys)` | Fit |
| `Fit.doFit(equation, xs, ys, initialGuesses)` | With guesses |
| `Fit.doWeightedFit(equation, xs, ys, weights, initialGuesses)` | Weighted |
| `Fit.rSquared` | R² |
| `Fit.p(i)` | Parameter `i` |
| `Fit.nParams` | Parameter count |
| `Fit.f(x)` | Evaluate fit at x |
| `Fit.nEquations` | Built-in count |
| `Fit.getEquation(i, name, formula)` | Equation details |
| `Fit.getEquation(i, name, formula, macroCode)` | With code |
| `Fit.plot()` | Plot fit |
| `Fit.logResults()` | Write to Log |
| `Fit.showDialog()` | Simplex settings dialog |

Built-in equation names: `"Straight Line"`, `"Polynomial (2nd)"`…`"Polynomial (8th)"`,
`"Exponential"`, `"Exponential with Offset"`, `"Exponential Recovery"`, `"Power"`,
`"Gaussian"`, `"Gaussian (no offset)"`, `"Rodbard"`, `"Rodbard (NIH Image)"`,
`"Gamma Variate"`, `"Log"`, `"Log2"`, `"Chapman-Richards"`, `"Error Function"`.

### 3.19 In-memory Map — `List.*`

Not to be confused with arrays.

| Function | Purpose |
|---|---|
| `List.set(key, value)` | Set |
| `List.get(key)` | String value |
| `List.getValue(key)` | Numeric value |
| `List.size` | Count |
| `List.clear()` | Empty |
| `List.setList(string)` | Load from `"k=v\n..."` |
| `List.getList()` | Dump as string |
| `List.setMeasurements()` | Populate from last measurement |
| `List.setMeasurements("limit")` | Honour threshold limits |
| `List.setCommands()` | Menu commands → plugins |
| `List.toArrays(keys, values)` / `List.fromArrays(keys, values)` | Array interop |
| `List.indexOf(key)` | Alphabetic position |

### 3.20 Image Properties / Metadata — `Property.*`

Per-image key/value store, persisted in TIFF.

| Function | Purpose |
|---|---|
| `Property.set(key, value)` | Set |
| `Property.get(key)` | String |
| `Property.getValue(key)` | Numeric |
| `Property.getNumber(key)` | Alias |
| `Property.getInfo()` | TIFF description ("Info" property) |
| `Property.setSliceLabel(string)` | Current slice label |
| `Property.setSliceLabel(string, slice)` | Specific slice label |
| `Property.getSliceLabel()` | Current slice label |
| `Property.getList()` / `Property.setList(string)` | Dump / load all |
| `getMetadata("Info" \| "Label")` | Whole-image or per-slice |
| `setMetadata("Info" \| "Label", value)` | Write |

### 3.21 Introspection

**Booleans — `is(flag)`:**
```
"animated" "applet" "area" "Batch Mode" "binary" "Caps Lock Set"
"changes" "composite" "FFT" "global scale" "grayscale" "Inverting LUT"
"InvertY" "line" "locked" "Virtual Stack"
```

**Strings — `getInfo(key)`:**
```
"command.name"             "font.name"              "image.description"
"image.directory"          "image.filename"         "image.title"
"image.subtitle"           "log"                    "macro.filepath"
"micrometer.abbreviation"  "os.name"                "overlay"
"selection.name"           "selection.color"        "slice.label"
"threshold.method"         "threshold.mode"         "window.contents"
"window.title"             "window.type"
"DICOM_TAG"                // any DICOM tag e.g. "0028,0010"
```
Also: `getInfo(anyJavaSystemProperty)`.

**Arrays — `getList(key)`:**
`"image.titles"` · `"window.titles"` · `"java.properties"` · `"threshold.methods"` · `"LUTs"`.

**Keyboard / time / fonts:**
| Function | Purpose |
|---|---|
| `isKeyDown("shift" \| "alt" \| "space" \| "meta" \| "control")` | Boolean |
| `setKeyDown("shift" \| "alt" \| "space" \| "none")` | Simulate key |
| `getFontList()` | Installed fonts |
| `getDateAndTime(year, month, dow, dom, h, m, s, ms)` | By reference |
| `getTime()` | ms since epoch |

### 3.22 Macro Control and Cross-Language — `IJ.*`, etc.

| Function | Purpose |
|---|---|
| `exit()` / `exit("msg")` | Terminate macro |
| `requires("1.54f")` | Fail if older version |
| `runMacro(path)` / `runMacro(path, arg)` | Call another macro file |
| `getArgument()` | Argument passed to this macro |
| `doCommand("Command")` | Run in separate thread (modal dialogs in batch) |
| `eval(code)` | Macro code string |
| `eval("js" \| "script", code)` | JavaScript |
| `eval("bsh", code)` | BeanShell |
| `eval("python", code)` | Python (Jython) |
| `call("class.method", arg1, ...)` | Java static method (returns string) |
| `exec(cmd)` / `exec(cmdArray)` | Native OS command |
| `debug(arg)` | Invoke debugger |
| `dump()` | Dump symbol table to Log |
| `saveSettings()` / `restoreSettings()` | Around third-party calls |
| `IJ.log(s)` | Alias for `print` |
| `IJ.redirectErrorMessages()` | Errors → Log (headless) |
| `IJ.freeMemory()` | "used of max" string |
| `IJ.currentMemory()` / `IJ.maxMemory()` | Bytes |
| `IJ.getFullVersion()` | Version + build |
| `IJ.getToolName()` | Current tool |
| `IJ.pad(n, width)` | Zero-pad string |
| `IJ.checksum("MD5 string", s)` / `IJ.checksum("MD5 file", path)` | Hash |

### 3.23 Plugin-Contributed — `Ext.*`

Plugins exposing `MacroExtension` register functions under `Ext.`. Example
(Serial Macro Extensions):
```ijm
run("Install Macro Extensions", "");
Ext.open("COM8", 9600, "");
Ext.write("hello");
```
Discover via plugin docs or `dump()`.

---

## 4. Batch Mode and Performance

```ijm
setBatchMode(true);                         // hide all new images; 10-100× faster
setBatchMode("hide");                       // alias
setBatchMode("show");                       // show currently batched images
setBatchMode(false);                        // end batch mode
is("Batch Mode");                           // boolean
autoUpdate(false);                          // skip display refresh inside pixel loops
```

Rules:
- Always wrap batch mode: `setBatchMode(true); ... setBatchMode(false);`
- Use `selectImage(id)` inside batch — `selectWindow("title")` can be flaky.
- Call `run("Collect Garbage");` between huge images if memory is tight.

---

## 5. Logging, Messages, Windows, Tools

| Function | Purpose |
|---|---|
| `print(x)` | Log window |
| `print(f, text)` | Write to file handle |
| `print("[Results]", text)` | Append to named window (auto-creates) |
| `beep()` | Audible tone |
| `restoreSettings()` / `saveSettings()` | Around third-party calls |
| `setTool("rectangle" \| "oval" \| "polygon" \| "freehand" \| "line" \| "polyline" \| "freeline" \| "arrow" \| "angle" \| "point" \| "multipoint" \| "wand" \| "text" \| "zoom" \| "hand" \| "dropper")` | Switch toolbar |
| `newMenu(name, labels)` | Define menu for toolbar macro |
| `dump()` | Symbol table → Log |

---

## 6. Recipes

### 6.1 Cell counting (2D fluorescence)
```ijm
run("Duplicate...", "title=mask");
run("Gaussian Blur...", "sigma=1");
run("Subtract Background...", "rolling=25");
setAutoThreshold("Li dark");
run("Convert to Mask");
run("Watershed");
run("Set Measurements...", "area mean integrated display redirect=None decimal=3");
run("Analyze Particles...", "size=50-5000 circularity=0.5-1.0 show=Outlines display summarize exclude");
```

### 6.2 CTCF (corrected total cell fluorescence)
```ijm
roiManager("Select", cellIdx); run("Measure");
intDen = getResult("IntDen", nResults-1);
area   = getResult("Area",   nResults-1);
roiManager("Select", bgIdx); run("Measure");
bgMean = getResult("Mean",   nResults-1);
ctcf   = intDen - area * bgMean;
setResult("CTCF", nResults-1, ctcf);
updateResults();
```

### 6.3 Batch process a folder
```ijm
dir   = getDirectory("Input folder");
out   = dir + "processed" + File.separator;
File.makeDirectory(out);
list  = getFileList(dir);
setBatchMode(true);
for (i = 0; i < list.length; i++) {
    if (!endsWith(toLowerCase(list[i]), ".tif")) continue;
    open(dir + list[i]);
    run("Gaussian Blur...", "sigma=2");
    saveAs("Tiff", out + list[i]);
    close();
}
setBatchMode(false);
```

### 6.4 Z-max projection (skip if 2D)
```ijm
Stack.getDimensions(w, h, c, sl, fr);
if (sl > 1) run("Z Project...", "projection=[Max Intensity]");
```

### 6.5 Per-ROI intensity over time
```ijm
n = roiManager("Count");
Stack.getDimensions(w, h, c, sl, fr);
Table.create("Traces");
for (t = 1; t <= fr; t++) {
    Stack.setFrame(t);
    Table.set("Frame", t-1, t, "Traces");
    for (r = 0; r < n; r++) {
        roiManager("Select", r);
        getStatistics(a, mean);
        Table.set("ROI_"+r, t-1, mean, "Traces");
    }
}
Table.update;
```

### 6.6 Colocalization mask (intersection of two thresholded channels)
```ijm
selectImage("C1.tif"); run("Duplicate...", "title=m1");
setAutoThreshold("Otsu dark"); run("Convert to Mask");
selectImage("C2.tif"); run("Duplicate...", "title=m2");
setAutoThreshold("Otsu dark"); run("Convert to Mask");
imageCalculator("AND create", "m1", "m2");
rename("colocalized_mask");
```

### 6.7 Macro argument passing
```ijm
// runMacro("/path/tool.ijm", "sigma=2,method=Li");
args = getArgument();
parts = split(args, ",");
for (i = 0; i < parts.length; i++) {
    kv = split(parts[i], "=");
    if (kv[0] == "sigma")  sigma  = parseFloat(kv[1]);
    if (kv[0] == "method") method = kv[1];
}
```

---

## 7. Gotchas and Best Practices

1. **Check state before acting.** Use `nImages` and `isOpen(title)` — never assume.
2. **Calibrate before measuring.** Uncalibrated → pixel units. `run("Set Scale...", ...)` or inherit from Bio-Formats.
3. **Duplicate before destructive operations.** Threshold, filters, math rewrite pixels.
4. **Never `Enhance Contrast ... normalize`** on data you will measure — permanently alters pixels. Use `setMinAndMax(min, max)` for display.
5. **Use `setBatchMode(true)` for loops.** 10-100× speedup.
6. **Prefer `selectImage(id)` over `selectWindow(title)`** — IDs are unambiguous.
7. **`setOption("BlackBackground", true)` before `Convert to Mask`** for fluorescence (white on black).
8. **`close()` alone only closes images.** Use `close("Results")`, `close("ROI Manager")`, `close("Log")` for those.
9. **RGB `getPixel` returns a packed 24-bit int.** Split channels before quantitative work.
10. **Results table is global.** `run("Clear Results");` between runs, or use a named `Table.create("X")`.
11. **`nResults`, `nSlices`, `nImages`, `PI`, `NaN` have no parens.**
12. **Arrays are pass-by-reference; numbers/strings are pass-by-value.**
13. **String `==` works** and is case-sensitive. Use `toLowerCase` for case-insensitive compare.
14. **Paths on Windows use forward slashes** in macros (or escape backslashes `\\`).
15. **`setOption("ExpandableArrays", true)`** to grow arrays past initial length.
16. **Don't close the Log window** while macros are running — `print` reopens it, flickering.
17. **ROI Manager state persists.** `roiManager("Reset")` at the start of any pipeline that rebuilds ROIs.
18. **`run("Measure")`** measures the active selection or whole image; use `roiManager("Measure")` for every ROI.
19. **Avoid JPEG for quantitative work** — lossy. Save as TIFF.
20. **Check the Log after every plugin call** — warnings go there, not as errors. `python ij.py log`.

---

## 8. Agent-Specific Tips (ImageJAI)

- `python ij.py macro '<code>'` — single quotes outside, double inside. Escape `\n` as `\\n` in shell.
- `python ij.py probe "Plugin Name..."` reveals every parameter of any GenericDialog plugin.
- `python ij.py capture step_name` then Read the PNG — that's your eyes.
- `python ij.py results` returns the Results table as CSV.
- `python ij.py log` reads the Log window.
- For Swing-side things macros can't do, use `python ij.py script ...` for Groovy.
- Write outputs to `AI_Exports/` next to the source image, never the macro directory.

---

## 9. Language Fundamentals

| Feature | Syntax / Rule |
|---|---|
| Comments | `// line` · `/* block */` |
| Types | Number (double), String, Boolean (1/0), Array. No structs — use `List.*` or parallel arrays. |
| String literal | `"..."` only. `+` concatenates. `==` case-sensitive compare. |
| Declare variable | `x = 5;` (function-local). `var x = 5;` at top level for global. |
| Operators | `+ - * / % ^` · `= += -= *= /=` · `++ --` · `== != < > <= >=` · `&& \|\| !` |
| `/` is float | No integer truncation. Use `floor()` for integer. |
| `^` is exponent | e.g. `2^3 == 8`. |
| if/else | `if (c) {...} else if (c2) {...} else {...}` |
| for | `for (i = 0; i < n; i++) {...}` |
| for-in | `for (v in array) {...}` |
| while | `while (c) {...}` · `do {...} while (c);` |
| break / continue | Standard |
| return / exit | `return value;` (from function). `exit;` or `exit("msg");` (halt macro). |
| Function def | `function name(a, b) { ...; return v; }` |
| Arg passing | Numbers/strings by value; arrays by reference. |
| Escapes in strings | `\n \t \" \\ \uXXXX` |
| Constants | `NaN`, `PI`, `true`, `false` |
| Context-free counts (no parens) | `nImages`, `nSlices`, `nResults` |

---

*Last updated: 2026-04-19. Sources: `imagej.net/ij/developer/macro/functions.html`, `imagej.net/ij/docs/macro_reference_guide.pdf`.*
