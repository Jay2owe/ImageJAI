# ImageJ/Fiji GUI Reference (Condensed)

Agent-oriented reference for ImageJ/Fiji GUI, menus, dialogs, and macro API.
Use `probe_plugin.py` to discover any plugin's parameters at runtime.

---

## 1. Core Concepts

- **ImageJ** = base app; **Fiji** = ImageJ + ~300 plugins + updater
- **run()** invokes any menu command: `run("Command", "key1=val1 key2=val2")`
- Values with spaces use brackets: `key=[My Value]`
- **Active image** = frontmost window; verify before commands
- **EDT**: GUI ops on Event Dispatch Thread; TCP server uses `invokeAndWait()`

---

## 2. Toolbar Tools

| Tool | setTool name | Notes |
|------|-------------|-------|
| Rectangle | `rectangle` | Double-click: rounded corners, fixed size |
| Oval | `oval` | Elliptical selection |
| Polygon | `polygon` | Click vertices, double-click to close |
| Freehand | `freehand` | Free drawing selection |
| Straight Line | `line` | Double-click: line width |
| Multi-point | `multipoint` | Multiple point markers |
| Wand | `wand` | Auto-trace edges (tolerance, 4/8-connected) |
| Text | `text` | Place text annotation |
| Zoom | `zoom` | Click=in, Alt+click=out |
| Hand | `hand` | Pan zoomed images |

---

## 3. Key Menu Commands

Agents can probe any command; below are the most commonly needed.

### File

| Macro | Notes |
|-------|-------|
| `open("/path/to/file")` | Any supported format |
| `run("Bio-Formats Importer", "open=/path color_mode=Composite")` | .nd2/.lif/.czi; options: autoscale, virtual, split_channels, series_N |
| `run("Image Sequence...", "open=/dir/ sort")` | Open folder as stack |
| `close()` / `close("*")` / `close("\\Others")` | Active / all / all except active |
| `saveAs("Tiff", "/path")` | Always TIFF for science; also PNG, Zip, Results |
| `run("AVI... ", "compression=JPEG frame=7 save=/path")` | Stack as video |

### Image Types

| Type | Range | Macro |
|------|-------|-------|
| 8-bit | 0-255 | `run("8-bit")` |
| 16-bit | 0-65535 | `run("16-bit")` — typical microscope output |
| 32-bit | float | `run("32-bit")` — for calculations |
| RGB | 3x8-bit | `run("RGB Color")` — required before colour save |

### Adjust

| Macro | Notes |
|-------|-------|
| `setMinAndMax(lo, hi)` | **Safe** — display only |
| `resetMinAndMax()` | Reset display range |
| `setAutoThreshold("Otsu dark")` | Display threshold; add "stack" for all slices |
| `setThreshold(lo, hi)` | Manual threshold |
| `run("Convert to Mask")` | Apply threshold to binary |

### Stacks & Hyperstacks

```javascript
Stack.getDimensions(w, h, ch, sl, fr);
Stack.setChannel(2); Stack.setSlice(10); Stack.setFrame(5);
Stack.setDisplayMode("composite");  // also "color", "grayscale"
Stack.setActiveChannels("110");     // binary: 1=visible
run("Z Project...", "projection=[Max Intensity]");  // also Sum, Average, Median
run("Make Montage...", "columns=3 rows=2 scale=1 border=2");
```

### Transform / Scale

| Macro | Notes |
|-------|-------|
| `run("Duplicate...", "title=copy duplicate")` | "duplicate" = all slices |
| `run("Crop")` | Requires selection |
| `run("Scale...", "x=2 y=2 interpolation=Bilinear create")` | Resize |
| `run("Rotate...", "angle=45 interpolation=Bilinear")` | Arbitrary rotation |
| `run("Flatten")` | Burns overlays to RGB — non-reversible |

### Process

| Macro | Notes |
|-------|-------|
| `run("Gaussian Blur...", "sigma=2")` | Smoothing |
| `run("Median...", "radius=2")` | Edge-preserving denoise |
| `run("Subtract Background...", "rolling=50")` | Rolling ball |
| `run("Find Maxima...", "prominence=10 output=Count")` | Peak detection |
| `run("Enhance Contrast...", "saturated=0.35")` | Safe display; **with "normalize" = DESTRUCTIVE** |

### Binary Operations

`Erode`, `Dilate`, `Open`, `Close-` (note hyphen!), `Fill Holes`, `Watershed`, `Skeletonize`, `Distance Map`

### Math & Image Calculator

```javascript
run("Subtract...", "value=25");
run("Multiply...", "value=1.5");
run("Macro...", "code=v=v*2");  // per-pixel expression
imageCalculator("Subtract create", "img1", "img2");  // add "stack", "32-bit"
// Operations: Add, Subtract, Multiply, Divide, AND, OR, Min, Max, Average, Difference
```

### LUTs

| LUT | Use |
|-----|-----|
| Grays | Default |
| Green/Red/Blue/Cyan/Magenta/Yellow | Channel colours |
| Fire, Cyan Hot | Intensity heatmaps |
| mpl-viridis, mpl-inferno | Perceptually uniform, colourblind safe |
| HiLo | Under/over-exposure check (blue=0, red=max) |

---

## 4. Key Dialogs

### Brightness & Contrast (Ctrl+Shift+C)

| Button | Safe? | Notes |
|--------|-------|-------|
| Auto | Yes | Stretches display to 0.35% saturation |
| Reset | Yes | Full range |
| Set | Yes | Exact min/max |
| Apply | **NO** | Permanently modifies pixels |

For 16/32-bit: B&C is always display-only until Apply. For RGB: always modifies pixels.

### Threshold (Ctrl+Shift+T)

Methods: Default, Huang, IsoData, Li, MaxEntropy, Mean, MinError, Minimum, Moments, **Otsu** (general-purpose), Triangle (sparse cells), Yen

### Analyze Particles

```javascript
run("Analyze Particles...", "size=50-Infinity circularity=0.5-1.00 show=Outlines display exclude summarize add");
```
Checkboxes: display results, clear results, summarize, add to manager, exclude on edges, include holes

### Set Measurements

```javascript
run("Set Measurements...", "area mean standard min integrated median display redirect=None decimal=3");
```

| Measurement | Columns |
|-------------|---------|
| area | Area |
| mean | Mean |
| standard | StdDev |
| min | Min, Max |
| integrated | IntDen, RawIntDen |
| centroid | X, Y |
| shape | Circ., AR, Round., Solidity |
| feret | Feret, FeretAngle, MinFeret |
| limit | Restricts to thresholded pixels |

---

## 5. ROIs and Selections

### Creation

```javascript
makeRectangle(x, y, w, h);
makeOval(x, y, w, h);
makeLine(x1, y1, x2, y2);
makePoint(x, y);
makePolygon(x1,y1, x2,y2, x3,y3);
makeSelection("polygon", xArray, yArray);  // also "freehand", "polyline", "point"
```

### Query

```javascript
type = selectionType();  // -1=none, 0=rect, 1=oval, 2=polygon, 5=line, 10=point
getSelectionBounds(x, y, w, h);
Roi.getName(); Roi.getBounds(x, y, w, h);
```

### Properties

```javascript
Roi.setName("name"); Roi.setStrokeColor("yellow"); Roi.setStrokeWidth(2);
Roi.setFillColor("00ffff44");  // RRGGBBAA
Roi.setPosition(channel, slice, frame);
Roi.setGroup(3); Roi.setProperty("key", "value");
run("Enlarge...", "enlarge=10");  // negative = shrink
```

---

## 6. ROI Manager

```javascript
roiManager("Add");                          // add current selection
n = roiManager("Count");
roiManager("Select", i);                   // 0-based index
roiManager("Select", newArray(0, 2, 4));   // multi-select
roiManager("Delete"); roiManager("Reset");
roiManager("Measure");                      // measure all
roiManager("Multi Measure");                // all ROIs x all slices
roiManager("Combine");                      // union
roiManager("AND");                          // intersection
roiManager("Show All"); roiManager("Show None");
roiManager("Save", "/path/rois.zip"); roiManager("Open", "/path/rois.zip");
```

---

## 7. Overlays

Non-destructive annotations. Preserved in TIFF metadata; flatten to burn into RGB.

```javascript
Overlay.drawString("text", x, y);           // y = baseline
Overlay.drawLine(x1, y1, x2, y2);
Overlay.drawRect(x, y, w, h);
Overlay.drawEllipse(x, y, w, h);
Overlay.addSelection("yellow", 2);          // add current ROI
Overlay.setStrokeColor("white"); Overlay.setStrokeWidth(2);
Overlay.setPosition(channel, slice, frame); // 0 = all slices
Overlay.show; Overlay.hide; Overlay.remove;
Overlay.removeSelection(index);
n = Overlay.size;
Overlay.flatten;                            // burns to RGB
setFont("SansSerif", 24, "bold"); setColor("white");
Overlay.drawString("label", x, y);
```

---

## 8. Results Table

### Built-in Results

```javascript
n = nResults;
val = getResult("Area", row);
setResult("NewCol", row, value);            // creates column if needed
str = getResultString("Label", row);
updateResults();
run("Clear Results");
saveAs("Results", "/path/results.csv");
```

### Custom Tables (Table.*)

```javascript
Table.create("My Table");
Table.set("Col", row, value);
val = Table.get("Col", row);
Table.getColumn("Col");                     // entire column as array
Table.sort("Col"); Table.deleteRows(0, 5);
Table.save("/path.csv"); Table.open("/path.csv");
Table.applyMacro("Norm = Mean / 255;");     // per-row expression
```

### List (Key-Value Store)

```javascript
List.setMeasurements;                       // quick measure to key-value
area = List.getValue("Area");
List.set("key", "value"); List.get("key");
```

---

## 9. File & I/O

```javascript
path = File.openDialog("Select"); dir = getDirectory("Choose a Directory");
home = getDirectory("home"); temp = getDirectory("temp");
File.exists(path); File.isDirectory(path); File.length(path);
File.makeDirectory(path); File.delete(path); File.copy(src, dest);
content = File.openAsString(path);
File.append("text\n", path);
list = getFileList(dir);  // array of filenames
```

---

## 10. Batch & Automation

```javascript
setBatchMode(true);                 // 10-100x faster, suppresses display
setBatchMode("exit and display");   // show results at end
runMacro("/path/to/macro.ijm", "arg");
call("ij.Prefs.set", "key", "value");
exec("python", "/path/script.py");
eval("js", "IJ.getVersion()");
```

---

## 11. Keyboard Shortcuts

### Essential

| Key | Action |
|-----|--------|
| Ctrl+O / Ctrl+W / Ctrl+S | Open / Close / Save |
| Ctrl+Z | Undo (single level only!) |
| Ctrl+A / Ctrl+Shift+A | Select All / None |
| Ctrl+Shift+D | Duplicate |
| Ctrl+M | Measure |
| Ctrl+T | Add to ROI Manager |
| Ctrl+H | Histogram |
| Ctrl+L | Command Finder |
| Ctrl+Shift+C / T | B&C / Threshold |
| Ctrl+B | Add to Overlay |

### Navigation

| Key | Action |
|-----|--------|
| +/- | Zoom in/out |
| >/< | Next/prev slice |
| Ctrl+Arrow | Next/prev channel |
| Alt+Arrow | Next/prev frame |
| Space (hold) | Temporary hand tool |
| Shift (hold) | Constrain to square/circle/45deg |

---

## 12. Agent Integration

### Dialog Handling

1. Every `execute_macro` response auto-includes open dialogs
2. TCP timeout triggers auto `get_dialogs` fallback
3. `close_dialogs` never closes: main window, image windows, AI Assistant

### Probe Flow

```
scan_plugins.py → .tmp/commands.md (what exists, with lookup map)
probe_plugin.py "Plugin..." → field types, defaults, macro syntax (how to use)
ij.py macro 'run(...)' → result + auto-attached dialogs
ij.py close_dialogs → dismiss error/info dialogs
```

probe_command reads from GenericDialog: numeric fields, strings, checkboxes, choices (all options), sliders (range).

---

## 13. Tips & Gotchas

1. Only **one level of Undo** — always work on duplicates
2. `close("*")` can close toolbar — use `close("\\Others")`
3. `run("Flatten")` converts to RGB permanently
4. **Apply in B&C destroys data** — use `setMinAndMax()` for display
5. Fiji caches classes — must restart after JAR deployment
6. Use Bio-Formats for microscopy files, not plain `open()`
7. Binary `Close-` has a hyphen: `run("Close-")` != `close()`
8. Dialog.get* order must match Dialog.add* order
9. `run("Enhance Contrast...", "saturated=0.35")` safe; with `normalize` destructive
10. ROI Manager indices are 0-based
11. Overlay text y = baseline (bottom), not top
12. No try/catch in macro language — use agent retry loop
13. Font size in macros is pixels, not points
14. Some plugins need an image open to show their dialog for probing
