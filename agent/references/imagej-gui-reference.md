# ImageJ/Fiji GUI Reference

Agent-oriented reference for ImageJ/Fiji GUI, menus, dialogs, and macro API.
Covers toolbar tools, menu commands, dialogs, ROIs, overlays, results tables,
file I/O, batch mode, and keyboard shortcuts.

Sources: `imagej.net/ij/docs/`, `imagej.net/plugins/`, Fiji wiki
(`imagej.net/software/fiji`). Use `python probe_plugin.py "Plugin..."` to
discover any installed plugin's parameters at runtime.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "What does `setTool` take?" | §1 → §3 |
| "How do I open a .nd2 / .lif / .czi file?" | §4.1 |
| "What image types does ImageJ support?" | §4.2 |
| "How do I threshold / binarize?" | §4.3, §5.2 |
| "What options does Analyze Particles take?" | §5.3 |
| "What columns does Set Measurements produce?" | §5.4 |
| "How do I draw a polygon / line / rectangle ROI?" | §6.1 |
| "How do I query the active selection?" | §6.2 |
| "How do I manage many ROIs?" | §7 |
| "How do I add non-destructive annotations?" | §8 |
| "How do I read / write the Results table?" | §9.1 |
| "How do I make a custom table?" | §9.2 |
| "What's the `File.*` API?" | §10 |
| "How do I speed up / batch a macro?" | §11 |
| "Keyboard shortcut for X?" | §12 |
| "How does the agent interact with dialogs?" | §13 |
| "Why did my macro fail / destroy my data?" | §14 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '`<term>`' imagej-gui-reference.md` to jump.

### A
`Analyze Particles` §5.3 · `AND (binary)` §7 · `Apply LUT` §4.8 · `Apply (B&C)` §5.1 · `autoUpdate` §4 · `AVI` §4.1

### B
`Batch Mode` §11 · `Bio-Formats Importer` §4.1 · `B&C` §5.1 · `binary ops` §4.6

### C
`call` §11 · `Circ.` §5.4 · `circularity` §5.3 · `Clear Results` §9.1 · `close / close("*") / close("\\Others")` §4.1 · `Close-` (binary) §4.6, §14 · `Convert to Mask` §4.3 · `Crop` §4.4

### D
`Dialog handling (agent)` §13 · `Dilate` §4.6 · `Distance Map` §4.6 · `Duplicate` §4.4

### E
`Enhance Contrast` §4.5, §14 · `eval` §11 · `exec` §11 · `Erode` §4.6

### F
`feret` §5.4 · `File.*` §10 · `Fill Holes` §4.6 · `Find Maxima` §4.5 · `Flatten` §4.4, §14 · `Font` §8

### G
`Gaussian Blur` §4.5 · `getDirectory` §10 · `getFileList` §10 · `getResult / getResultString` §9.1 · `getSelectionBounds` §6.2

### H
`HiLo` §4.8 · `Histogram` §12 · `home (getDirectory)` §10 · `Hyperstack` §4.3

### I
`imageCalculator` §4.7 · `Image Sequence` §4.1 · `integrated` §5.4 · `invokeAndWait` §2

### L
`Li (threshold)` §5.2 · `limit` §5.4 · `List.*` §9.3 · `LUTs` §4.8

### M
`makeRectangle / makeOval / makeLine / makePoint / makePolygon / makeSelection` §6.1 · `Measure` §12 · `Median` §4.5 · `Merge Channels` §4.3 · `Make Montage` §4.3 · `Multi Measure` §7

### N
`nResults` §9.1

### O
`open` §4.1 · `Open (binary)` §4.6 · `Otsu` §5.2 · `Overlay.*` §8

### P
`polygon` §3, §6.1 · `probe_plugin` §13 · `Process menu` §4.5

### R
`Rectangle (tool)` §3 · `resetMinAndMax` §4.3 · `Results table` §9.1 · `RGB Color` §4.2 · `Roi.*` §6.3 · `roiManager` §7 · `Rotate` §4.4 · `run (command invocation)` §2, §4 · `runMacro` §11

### S
`saveAs` §4.1 · `Scale` §4.4 · `selectionType` §6.2 · `Set Measurements` §5.4 · `setAutoThreshold` §4.3, §5.2 · `setBatchMode` §11 · `setMinAndMax` §4.3, §14 · `setThreshold` §4.3 · `setTool` §3 · `setResult` §9.1 · `Skeletonize` §4.6 · `Stack.*` §4.3 · `Subtract Background` §4.5 · `Summarize` §5.3

### T
`Table.*` §9.2 · `Threshold` §4.3, §5.2 · `Toolbar tools` §3 · `Triangle (threshold)` §5.2

### U
`Undo` §12, §14 · `updateResults` §9.1

### W
`Wand` §3 · `Watershed` §4.6

### Y
`Yen (threshold)` §5.2

### Z
`Z Project` §4.3 · `Zoom` §3

---

## §2 Core Concepts

- **ImageJ** = base app; **Fiji** = ImageJ + ~300 plugins + updater
- **run()** invokes any menu command: `run("Command", "key1=val1 key2=val2")`
- Values with spaces use brackets: `key=[My Value]`
- **Active image** = frontmost window; verify before commands
- **EDT**: GUI ops on Event Dispatch Thread; TCP server uses `invokeAndWait()`

---

## §3 Toolbar Tools

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

## §4 Key Menu Commands

Agents can probe any command; below are the most commonly needed.

### §4.1 File

| Macro | Notes |
|-------|-------|
| `open("/path/to/file")` | Any supported format |
| `run("Bio-Formats Importer", "open=/path color_mode=Composite")` | .nd2/.lif/.czi; options: autoscale, virtual, split_channels, series_N |
| `run("Image Sequence...", "open=/dir/ sort")` | Open folder as stack |
| `close()` / `close("*")` / `close("\\Others")` | Active / all / all except active |
| `saveAs("Tiff", "/path")` | Always TIFF for science; also PNG, Zip, Results |
| `run("AVI... ", "compression=JPEG frame=7 save=/path")` | Stack as video |

### §4.2 Image Types

| Type | Range | Macro |
|------|-------|-------|
| 8-bit | 0-255 | `run("8-bit")` |
| 16-bit | 0-65535 | `run("16-bit")` — typical microscope output |
| 32-bit | float | `run("32-bit")` — for calculations |
| RGB | 3x8-bit | `run("RGB Color")` — required before colour save |

### §4.3 Adjust

| Macro | Notes |
|-------|-------|
| `setMinAndMax(lo, hi)` | **Safe** — display only |
| `resetMinAndMax()` | Reset display range |
| `setAutoThreshold("Otsu dark")` | Display threshold; add "stack" for all slices |
| `setThreshold(lo, hi)` | Manual threshold |
| `run("Convert to Mask")` | Apply threshold to binary |

### §4.3.1 Stacks & Hyperstacks

```javascript
Stack.getDimensions(w, h, ch, sl, fr);
Stack.setChannel(2); Stack.setSlice(10); Stack.setFrame(5);
Stack.setDisplayMode("composite");  // also "color", "grayscale"
Stack.setActiveChannels("110");     // binary: 1=visible
run("Z Project...", "projection=[Max Intensity]");  // also Sum, Average, Median
run("Make Montage...", "columns=3 rows=2 scale=1 border=2");
```

### §4.4 Transform / Scale

| Macro | Notes |
|-------|-------|
| `run("Duplicate...", "title=copy duplicate")` | "duplicate" = all slices |
| `run("Crop")` | Requires selection |
| `run("Scale...", "x=2 y=2 interpolation=Bilinear create")` | Resize |
| `run("Rotate...", "angle=45 interpolation=Bilinear")` | Arbitrary rotation |
| `run("Flatten")` | Burns overlays to RGB — non-reversible |

### §4.5 Process

| Macro | Notes |
|-------|-------|
| `run("Gaussian Blur...", "sigma=2")` | Smoothing |
| `run("Median...", "radius=2")` | Edge-preserving denoise |
| `run("Subtract Background...", "rolling=50")` | Rolling ball |
| `run("Find Maxima...", "prominence=10 output=Count")` | Peak detection |
| `run("Enhance Contrast...", "saturated=0.35")` | Safe display; **with "normalize" = DESTRUCTIVE** |

### §4.6 Binary Operations

`Erode`, `Dilate`, `Open`, `Close-` (note hyphen!), `Fill Holes`, `Watershed`, `Skeletonize`, `Distance Map`

### §4.7 Math & Image Calculator

```javascript
run("Subtract...", "value=25");
run("Multiply...", "value=1.5");
run("Macro...", "code=v=v*2");  // per-pixel expression
imageCalculator("Subtract create", "img1", "img2");  // add "stack", "32-bit"
// Operations: Add, Subtract, Multiply, Divide, AND, OR, Min, Max, Average, Difference
```

### §4.8 LUTs

| LUT | Use |
|-----|-----|
| Grays | Default |
| Green/Red/Blue/Cyan/Magenta/Yellow | Channel colours |
| Fire, Cyan Hot | Intensity heatmaps |
| mpl-viridis, mpl-inferno | Perceptually uniform, colourblind safe |
| HiLo | Under/over-exposure check (blue=0, red=max) |

---

## §5 Key Dialogs

### §5.1 Brightness & Contrast (Ctrl+Shift+C)

| Button | Safe? | Notes |
|--------|-------|-------|
| Auto | Yes | Stretches display to 0.35% saturation |
| Reset | Yes | Full range |
| Set | Yes | Exact min/max |
| Apply | **NO** | Permanently modifies pixels |

For 16/32-bit: B&C is always display-only until Apply. For RGB: always modifies pixels.

### §5.2 Threshold (Ctrl+Shift+T)

Methods: Default, Huang, IsoData, Li, MaxEntropy, Mean, MinError, Minimum, Moments, **Otsu** (general-purpose), Triangle (sparse cells), Yen

### §5.3 Analyze Particles

```javascript
run("Analyze Particles...", "size=50-Infinity circularity=0.5-1.00 show=Outlines display exclude summarize add");
```
Checkboxes: display results, clear results, summarize, add to manager, exclude on edges, include holes

### §5.4 Set Measurements

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

## §6 ROIs and Selections

### §6.1 Creation

```javascript
makeRectangle(x, y, w, h);
makeOval(x, y, w, h);
makeLine(x1, y1, x2, y2);
makePoint(x, y);
makePolygon(x1,y1, x2,y2, x3,y3);
makeSelection("polygon", xArray, yArray);  // also "freehand", "polyline", "point"
```

### §6.2 Query

```javascript
type = selectionType();  // -1=none, 0=rect, 1=oval, 2=polygon, 5=line, 10=point
getSelectionBounds(x, y, w, h);
Roi.getName(); Roi.getBounds(x, y, w, h);
```

### §6.3 Properties

```javascript
Roi.setName("name"); Roi.setStrokeColor("yellow"); Roi.setStrokeWidth(2);
Roi.setFillColor("00ffff44");  // RRGGBBAA
Roi.setPosition(channel, slice, frame);
Roi.setGroup(3); Roi.setProperty("key", "value");
run("Enlarge...", "enlarge=10");  // negative = shrink
```

---

## §7 ROI Manager

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

## §8 Overlays

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

## §9 Results Table

### §9.1 Built-in Results

```javascript
n = nResults;
val = getResult("Area", row);
setResult("NewCol", row, value);            // creates column if needed
str = getResultString("Label", row);
updateResults();
run("Clear Results");
saveAs("Results", "/path/results.csv");
```

### §9.2 Custom Tables (Table.*)

```javascript
Table.create("My Table");
Table.set("Col", row, value);
val = Table.get("Col", row);
Table.getColumn("Col");                     // entire column as array
Table.sort("Col"); Table.deleteRows(0, 5);
Table.save("/path.csv"); Table.open("/path.csv");
Table.applyMacro("Norm = Mean / 255;");     // per-row expression
```

### §9.3 List (Key-Value Store)

```javascript
List.setMeasurements;                       // quick measure to key-value
area = List.getValue("Area");
List.set("key", "value"); List.get("key");
```

---

## §10 File & I/O

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

## §11 Batch & Automation

```javascript
setBatchMode(true);                 // 10-100x faster, suppresses display
setBatchMode("exit and display");   // show results at end
runMacro("/path/to/macro.ijm", "arg");
call("ij.Prefs.set", "key", "value");
exec("python", "/path/script.py");
eval("js", "IJ.getVersion()");
```

---

## §12 Keyboard Shortcuts

### §12.1 Essential

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

### §12.2 Navigation

| Key | Action |
|-----|--------|
| +/- | Zoom in/out |
| >/< | Next/prev slice |
| Ctrl+Arrow | Next/prev channel |
| Alt+Arrow | Next/prev frame |
| Space (hold) | Temporary hand tool |
| Shift (hold) | Constrain to square/circle/45deg |

---

## §13 Agent Integration

### §13.1 Dialog Handling

1. Every `execute_macro` response auto-includes open dialogs
2. TCP timeout triggers auto `get_dialogs` fallback
3. `close_dialogs` never closes: main window, image windows, AI Assistant

### §13.2 Probe Flow

```
scan_plugins.py → .tmp/commands.md (what exists, with lookup map)
probe_plugin.py "Plugin..." → field types, defaults, macro syntax (how to use)
ij.py macro 'run(...)' → result + auto-attached dialogs
ij.py close_dialogs → dismiss error/info dialogs
```

probe_command reads from GenericDialog: numeric fields, strings, checkboxes, choices (all options), sliders (range).

---

## §14 Gotchas / Pitfalls

1. Only **one level of Undo** — always work on duplicates.
2. `close("*")` can close toolbar — use `close("\\Others")`.
3. `run("Flatten")` converts to RGB permanently.
4. **Apply in B&C destroys data** — use `setMinAndMax()` for display (§4.3, §5.1).
5. Fiji caches classes — must restart after JAR deployment.
6. Use Bio-Formats for microscopy files, not plain `open()` (§4.1).
7. Binary `Close-` has a hyphen: `run("Close-")` != `close()` (§4.6).
8. `Dialog.get*` order must match `Dialog.add*` order.
9. `run("Enhance Contrast...", "saturated=0.35")` safe; with `normalize` destructive (§4.5).
10. ROI Manager indices are 0-based (§7).
11. Overlay text `y` = baseline (bottom), not top (§8).
12. No try/catch in macro language — use agent retry loop.
13. Font size in macros is pixels, not points.
14. Some plugins need an image open to show their dialog for probing.
