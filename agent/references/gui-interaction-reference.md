# ImageJ Macro — GUI & Interaction API Reference (Condensed)

Complete function reference for Dialog, Plot, Fit, Color, Ext, drawing, pixel access,
math, strings, arrays, and automation. All work via `python ij.py macro 'CODE'`.

Sources: `imagej.net/ij/developer/macro/functions.html` (built-in macro function
reference), `imagej.net/ij/docs/` (menus and plugins), and the *Macro Reference
Guide* PDF. Use `python probe_plugin.py "Plugin..."` to discover any installed
plugin's parameters at runtime. For dialog widget interaction from outside a
macro, use `python ij.py ui ...` (see `agent/CLAUDE.md`).

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "How do I build a modal / non-blocking dialog?" | §2 |
| "How do I prompt the user for a yes/no / string / number?" | §3 |
| "How do I pause the macro until the user clicks OK?" | §3 |
| "How do I find / activate / close open windows?" | §4 |
| "How do I draw a rectangle / oval / polygon / freehand ROI?" | §5 |
| "What does `selectionType()` return for each ROI shape?" | §5 |
| "How do I store many ROIs and measure them all?" | §5 |
| "How do I add non-destructive text / shapes on top of an image?" | §6 |
| "How do I read or write the Results table (or a custom named table)?" | §7 |
| "How do I query image width / height / bit depth / calibration?" | §8 |
| "How do I read a raw pixel value (and decompose an RGB int)?" | §9 |
| "How do I burn text / lines into pixels (destructive)?" | §10 |
| "How do I set a drawing colour or apply a custom LUT?" | §11 |
| "How do I get image statistics (area, mean, min, max, histogram)?" | §12 |
| "What are the available auto-threshold methods?" | §12 |
| "How do I create a plot with a line, legend, log axis, or fixed axis range?" | §13 |
| "How do I fit a curve (straight line, gaussian, exponential)?" | §14 |
| "How do I speed up a macro or run OS / JS / Python from it?" | §15 |
| "How do I use the key-value `List` store?" | §16 |
| "How do I create, sort, filter, or take FFT of an array?" | §17 |
| "How do I split, match, replace, or pad strings?" | §18 |
| "How do I compute log / trig / random / rescale a number?" | §19 |
| "How do I call Bio-Formats or CLIJ2 extension functions?" | §20 |
| "What's the keyboard shortcut for X / how do I bind my own?" | §21 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Every identifier
mentioned in §2+ is listed here; multi-section terms get comma-separated
pointers. Use `grep -n '<term>'` inside this file for exact location.

### A

`abs` §19 · `acos` §19 · `Array.concat` §17 · `Array.copy` §17 · `Array.deleteIndex` §17 · `Array.deleteValue` §17 · `Array.fill` §17 · `Array.filter` §17 · `Array.findMaxima` §17 · `Array.findMinima` §17 · `Array.fourier` §17 · `Array.getSequence` §17 · `Array.getStatistics` §17 · `Array.rankPositions` §17 · `Array.reverse` §17 · `Array.slice` §17 · `Array.sort` §17 · `Array.trim` §17 · `arr.length` §17 · `asin` §19 · `atan2` §19

### B

`bar (plot type)` §13 · `bitDepth` §8 · `BlackBackground` §15 · `box (plot type)` §13 · `batch mode` §15 · `Bio-Formats (Ext)` §20

### C

`call` §15 · `ceil` §19 · `circle (plot type)` §13 · `CLIJ2 (Ext)` §20 · `close` §4 · `close("\\Others")` §4 · `Color.background` §11 · `Color.foreground` §11 · `Color.getLut` §11 · `Color.set` §11 · `Color.setBackground` §11 · `Color.setForeground` §11 · `Color.setForegroundValue` §11 · `Color.setLut` §11 · `Color.toArray` §11 · `Color.wavelengthToColor` §11 · `connected circle (plot type)` §13 · `cos` §19 · `cross (plot type)` §13 · `custom key shortcuts` §21

### D

`d2s` §18 · `Dialog.addCheckbox` §2 · `Dialog.addCheckboxGroup` §2 · `Dialog.addChoice` §2 · `Dialog.addDirectory` §2 · `Dialog.addFile` §2 · `Dialog.addHelp` §2 · `Dialog.addImageChoice` §2 · `Dialog.addMessage` §2 · `Dialog.addNumber` §2 · `Dialog.addRadioButtonGroup` §2 · `Dialog.addSlider` §2 · `Dialog.addString` §2 · `Dialog.addToSameRow` §2 · `Dialog.create` §2 · `Dialog.createNonBlocking` §2 · `Dialog.getCheckbox` §2 · `Dialog.getChoice` §2 · `Dialog.getImageChoice` §2 · `Dialog.getNumber` §2 · `Dialog.getRadioButton` §2 · `Dialog.getString` §2 · `Dialog.setInsets` §2 · `Dialog.setLocation` §2 · `Dialog.show` §2 · `diamond (plot type)` §13 · `dot (plot type)` §13 · `doCommand` §15 · `doWand` §5 · `drawLine` §10 · `drawOval` §10 · `drawRect` §10 · `drawString` §10

### E

`error bars (plot type)` §13 · `eval` §15 · `ExpandableArrays` §15 · `exec` §15 · `exit` §3 · `exp` §19 · `Ext.CLIJ2_gaussianBlur3D` §20 · `Ext.CLIJ2_pull` §20 · `Ext.CLIJ2_push` §20 · `Ext.getSeriesCount` §20 · `Ext.setId` §20 · `Ext.setSeries` §20

### F

`filled (plot type)` §13 · `fillOval` §10 · `fillRect` §10 · `Fit.doFit` §14 · `Fit.f` §14 · `Fit.logResults` §14 · `Fit.p` §14 · `Fit.plot` §14 · `Fit.rSquared` §14 · `floodFill` §10 · `floor` §19 · `Font` §10

### G

`getBoolean` §3 · `getDimensions` §8 · `getImageID` §4 · `getInfo` §8 · `getList` §4 · `getLocationAndSize` §4 · `getMetadata` §8 · `getNumber` §3 · `getPixel` §9 · `getRawStatistics` §12 · `getResult` §7 · `getResultString` §7 · `getSelectionBounds` §5 · `getStatistics` §12 · `getString` §3 · `getTime` §19 · `getTitle` §4 · `getValue` §9 · `getWidth` §8 · `getHeight` §8

### I

`IJ.deleteRows` §7 · `IJ.pad` §18 · `IJ.redirectErrorMessages` §15 · `indexOf` §18 · `isNaN` §19 · `isOpen` §4

### L

`lastIndexOf` §18 · `Limit to Threshold` §15 · `line (plot type)` §13 · `List.clear` §16 · `List.fromArrays` §16 · `List.get` §16 · `List.getValue` §12, §16 · `List.set` §16 · `List.setCommands` §16 · `List.setMeasurements` §12, §16 · `List.size` §16 · `List.toArrays` §16 · `log` §19

### M

`makeArrow` §5 · `makeLine` §5 · `makeOval` §5 · `makePoint` §5 · `makePolygon` §5 · `makeRectangle` §5 · `makeRotatedRectangle` §5 · `makeSelection` §5 · `matches` §18 · `Math.constrain` §19 · `Math.log10` §19 · `Math.map` §19 · `Math.max` §19 · `Math.min` §19 · `Math.toDegrees` §19 · `Math.toRadians` §19 · `maxOf` §19 · `minOf` §19

### N

`NaN` §19 · `newArray` §17 · `newImage` §8 · `nImages` §4 · `nResults` §7 · `nSlices` §8

### O

`Otsu` §12 · `Overlay.activateSelection` §6 · `Overlay.add` §6 · `Overlay.addSelection` §6 · `Overlay.cropAndSave` §6 · `Overlay.drawEllipse` §6 · `Overlay.drawLine` §6 · `Overlay.drawPolygon` §6 · `Overlay.drawRect` §6 · `Overlay.drawString` §6 · `Overlay.flatten` §6 · `Overlay.hide` §6 · `Overlay.lineTo` §6 · `Overlay.measure` §6 · `Overlay.moveTo` §6 · `Overlay.remove` §6 · `Overlay.removeRois` §6 · `Overlay.removeSelection` §6 · `Overlay.setFillColor` §6 · `Overlay.setFont` §6 · `Overlay.setPosition` §6 · `Overlay.setStrokeColor` §6 · `Overlay.setStrokeWidth` §6 · `Overlay.show` §6 · `Overlay.size` §6

### P

`parseFloat` §18 · `parseInt` §18 · `PI` §19 · `Plot.add` §13 · `Plot.addHistogram` §13 · `Plot.addText` §13 · `Plot.create` §13 · `Plot.drawLine` §13 · `Plot.getValues` §13 · `Plot.makeHighResolution` §13 · `Plot.setBackgroundColor` §13 · `Plot.setColor` §13 · `Plot.setFontSize` §13 · `Plot.setFrameSize` §13 · `Plot.setLegend` §13 · `Plot.setLimits` §13 · `Plot.setLimitsToFit` §13 · `Plot.setLineWidth` §13 · `Plot.setLogScaleX` §13 · `Plot.setLogScaleY` §13 · `Plot.setXYLabels` §13 · `Plot.show` §13 · `pow` §19 · `print` §3 · `Property.get` §8 · `Property.set` §8

### R

`random` §19 · `rename` §4 · `replace` §18 · `Roi.getBounds` §5 · `Roi.getContainedPoints` §5 · `Roi.getCoordinates` §5 · `Roi.getGroup` §5 · `Roi.getName` §5 · `Roi.move` §5 · `Roi.setFillColor` §5 · `Roi.setGroup` §5 · `Roi.setName` §5 · `Roi.setPosition` §5 · `Roi.setProperty` §5 · `Roi.setStrokeColor` §5 · `Roi.setStrokeWidth` §5 · `Roi.size` §5 · `roiManager("Add")` §5 · `roiManager("AND")` §5 · `roiManager("Combine")` §5 · `roiManager("Count")` §5 · `roiManager("Delete")` §5 · `roiManager("Measure")` §5 · `roiManager("Multi Measure")` §5 · `roiManager("Open")` §5 · `roiManager("Reset")` §5 · `roiManager("Save")` §5 · `roiManager("Select")` §5 · `roiManager("Set Color")` §5 · `roiManager("Set Line Width")` §5 · `roiManager("Show All")` §5 · `roiManager("Show None")` §5 · `roiManager("Translate")` §5 · `roiManager("XOR")` §5 · `round` §19 · `run` §15 · `runMacro` §15

### S

`ScaleConversions` §15 · `selectImage` §4 · `selectionContains` §5 · `selectionType` §5 · `selectWindow` §4 · `setBatchMode` §15 · `setColor` §10 · `setFont` §10 · `setJustification` §10 · `setLineWidth` §10 · `setOption` §15 · `setPixel` §9 · `setResult` §7 · `showMessage` §3 · `showMessageWithCancel` §3 · `showProgress` §3 · `showStatus` §3 · `sin` §19 · `split` §18 · `sqrt` §19 · `Stack.setActiveChannels` §8 · `Stack.setChannel` §8 · `Stack.setDisplayMode` §8 · `Stack.setFrame` §8 · `Stack.setSlice` §8 · `startsWith` §18 · `endsWith` §18 · `String.copy` §18 · `String.join` §18 · `String.paste` §18 · `substring` §18

### T

`Table.applyMacro` §7 · `Table.create` §7 · `Table.deleteColumn` §7 · `Table.deleteRows` §7 · `Table.get` §7 · `Table.getColumn` §7 · `Table.headings` §7 · `Table.open` §7 · `Table.save` §7 · `Table.set` §7 · `Table.setColumn` §7 · `Table.size` §7 · `Table.sort` §7 · `Table.title` §7 · `tan` §19 · `toLowerCase` §18 · `toUpperCase` §18 · `triangle (plot type)` §13 · `Triangle (threshold)` §12 · `String.trim` §18

### U

`updateResults` §7

### W

`WaitForCompletion` §15 · `waitForUser` §3 · `wavelengthToColor` §11

### X

`x (plot type)` §13

---

## §2 Dialog API

### Create & Show

```javascript
Dialog.create("Title");              // modal
Dialog.createNonBlocking("Title");   // non-modal
Dialog.setLocation(x, y);
Dialog.show();                       // blocks; Cancel exits macro
```

### Add Fields

| Function | Signature |
|----------|-----------|
| String | `Dialog.addString("Label", "default")` — optional 3rd arg: columns |
| Number | `Dialog.addNumber("Label", default, decimals, columns, "units")` |
| Checkbox | `Dialog.addCheckbox("Label", true)` |
| Checkbox group | `Dialog.addCheckboxGroup(rows, cols, labelsArr, defaultsArr)` |
| Choice | `Dialog.addChoice("Label", itemsArr, "default")` |
| Radio buttons | `Dialog.addRadioButtonGroup("Label", itemsArr, rows, cols, "default")` |
| Slider | `Dialog.addSlider("Label", min, max, default)` |
| File/Dir | `Dialog.addFile("Label", "path")` / `Dialog.addDirectory(...)` |
| Image | `Dialog.addImageChoice("Label")` — dropdown of open images |
| Message | `Dialog.addMessage("text", fontSize, "color")` |
| Help | `Dialog.addHelp("url")` or `Dialog.addHelp("<html>...</html>")` |

### Layout

`Dialog.addToSameRow()` — next item same row
`Dialog.setInsets(top, left, bottom)` — margins in pixels

### Retrieve (must match add order)

| Type | Getter |
|------|--------|
| String/Dir | `Dialog.getString()` |
| Number/Slider | `Dialog.getNumber()` |
| Checkbox | `Dialog.getCheckbox()` |
| Choice | `Dialog.getChoice()` |
| Radio | `Dialog.getRadioButton()` |
| Image | `Dialog.getImageChoice()` |

---

## §3 User Interaction

| Function | Notes |
|----------|-------|
| `getBoolean("msg")` | Yes/No/Cancel; custom labels: `getBoolean("msg", "Yes", "No")` |
| `getString("prompt", "default")` | Text input |
| `getNumber("prompt", default)` | Numeric input |
| `waitForUser("Title", "message")` | Pauses until OK |
| `showMessage("Title", "msg")` | OK dialog |
| `showMessageWithCancel("Title", "msg")` | OK+Cancel |
| `showStatus("text")` | Status bar |
| `showProgress(i, n)` | Progress bar (0-1) |
| `print("text")` | Log window; `print("\\Clear")` clears |
| `exit("error msg")` | Stop with dialog |

---

## §4 Window Management

| Function | Notes |
|----------|-------|
| `selectWindow("title")` | Activate by title |
| `selectImage(id)` | Activate by ID (more reliable) |
| `id = getImageID()` | Unique negative integer |
| `title = getTitle()` | Active image title |
| `rename("new")` | Rename active |
| `close()` / `close("pattern")` | Wildcards: `*`, `?` |
| `close("\\Others")` | All except active |
| `titles = getList("image.titles")` | All image titles |
| `isOpen("Title")` | Check existence |
| `n = nImages` | Count open images |
| `getLocationAndSize(x, y, w, h)` | Window geometry |

---

## §5 ROI Functions

### Creation

| Function | Notes |
|----------|-------|
| `makeRectangle(x, y, w, h)` | Optional 5th arg: arcSize |
| `makeOval(x, y, w, h)` | Ellipse bounding box |
| `makeLine(x1, y1, x2, y2)` | Optional width; extra points = segmented |
| `makePoint(x, y, "cross red small")` | Optional style string |
| `makePolygon(x1,y1, x2,y2, x3,y3)` | Min 3 vertices |
| `makeSelection("type", xArr, yArr)` | polygon/freehand/polyline/freeline/angle/point |
| `makeArrow(x1, y1, x2, y2, "style")` | filled/notched/open/headless/bar |
| `makeRotatedRectangle(x1, y1, x2, y2, w)` | Along a line |
| `doWand(x, y, tol, "8-connected")` | Auto-trace |

### Query

| Function | Returns |
|----------|---------|
| `selectionType()` | -1=none, 0=rect, 1=oval, 2=polygon, 3=freehand, 5=line, 10=point |
| `getSelectionBounds(x, y, w, h)` | Bounding rectangle |
| `selectionContains(x, y)` | true/false |

### Roi.* Properties

| Function | Notes |
|----------|-------|
| `Roi.getName()` / `Roi.setName("n")` | Name |
| `Roi.getBounds(x, y, w, h)` | Integer bounds |
| `Roi.getCoordinates(xArr, yArr)` | Vertices |
| `Roi.getContainedPoints(xArr, yArr)` | All pixels inside |
| `Roi.setStrokeColor("red")` | Name or "#ff0000" |
| `Roi.setFillColor("00ff0044")` | RRGGBBAA |
| `Roi.setStrokeWidth(2)` | Line width |
| `Roi.setPosition(ch, sl, fr)` | Stack association; 0 = all |
| `Roi.setGroup(n)` / `Roi.getGroup()` | Group assignment |
| `Roi.setProperty("key", "val")` | Custom metadata |
| `Roi.move(dx, dy)` | Translate |
| `Roi.size` | Point count |

### ROI Manager

| Function | Notes |
|----------|-------|
| `roiManager("Add")` | Add current selection |
| `roiManager("Count")` | Number of ROIs |
| `roiManager("Select", i)` | 0-based; array for multi |
| `roiManager("Delete")` / `roiManager("Reset")` | Selected / all |
| `roiManager("Measure")` / `roiManager("Multi Measure")` | All ROIs / across slices |
| `roiManager("Combine")` / `roiManager("AND")` / `roiManager("XOR")` | Boolean ops |
| `roiManager("Save", path)` / `roiManager("Open", path)` | .zip files |
| `roiManager("Show All")` / `roiManager("Show None")` | Display |
| `roiManager("Set Color", "red")` / `roiManager("Set Line Width", 2)` | Styling |
| `roiManager("Translate", dx, dy)` | Move selected |

---

## §6 Overlay Functions

### Drawing (non-destructive)

| Function | Notes |
|----------|-------|
| `Overlay.drawString("text", x, y)` | y = baseline; optional angle arg |
| `Overlay.drawLine(x1, y1, x2, y2)` | Line |
| `Overlay.drawRect(x, y, w, h)` | Rectangle |
| `Overlay.drawEllipse(x, y, w, h)` | Ellipse |
| `Overlay.drawPolygon(xArr, yArr)` | Polygon |
| `Overlay.moveTo(x, y)` / `Overlay.lineTo(x, y)` | Path drawing |
| `Overlay.addSelection("red", 2)` | Add current ROI; optional colour, width |
| `Overlay.add` | Commit pending drawing |

### Styling (set before adding)

`Overlay.setStrokeColor("yellow")` / `Overlay.setStrokeWidth(2)` / `Overlay.setFillColor("00ff0044")` / `Overlay.setFont("SansSerif", 14, "bold")`

### Management

| Function | Notes |
|----------|-------|
| `Overlay.size` | Count |
| `Overlay.show` / `Overlay.hide` / `Overlay.remove` | Display control |
| `Overlay.removeSelection(i)` / `Overlay.removeRois("name")` | Remove specific |
| `Overlay.setPosition(ch, z, t)` | Stack position; 0 = all |
| `Overlay.activateSelection(i)` | Make overlay item active ROI |
| `Overlay.flatten` | Burn to RGB |
| `Overlay.measure` | Measure all overlay selections |
| `Overlay.cropAndSave(dir, "png")` | Export each selection |

---

## §7 Results Table

### Built-in Results

| Function | Notes |
|----------|-------|
| `nResults` | Row count |
| `getResult("Col", row)` | Numeric value |
| `setResult("Col", row, val)` | Set/create column |
| `getResultString("Col", row)` | String value |
| `updateResults()` | Refresh display |
| `IJ.deleteRows(i1, i2)` | Delete range |

### Table.* (any named table)

| Function | Notes |
|----------|-------|
| `Table.create("Name")` | New table |
| `Table.set("Col", row, val)` / `Table.get("Col", row)` | Read/write |
| `Table.getColumn("Col")` / `Table.setColumn("Col", arr)` | Entire column |
| `Table.sort("Col")` | Sort by column |
| `Table.applyMacro("NewCol = Mean / 255;")` | Per-row expression |
| `Table.save(path)` / `Table.open(path)` | CSV I/O |
| `Table.deleteRows(i1, i2)` / `Table.deleteColumn("Col")` | Remove data |
| `Table.size` / `Table.title` / `Table.headings` | Info |

---

## §8 Image Properties & Navigation

| Function | Notes |
|----------|-------|
| `getWidth()` / `getHeight()` / `nSlices` | Dimensions |
| `bitDepth()` | 8, 16, 24 (RGB), 32 |
| `getDimensions(w, h, ch, sl, fr)` | Full hyperstack dims |
| `getPixelSize(unit, pw, ph)` | Calibration |
| `getInfo("image.title")` / `getInfo("image.filename")` | Metadata |
| `Property.set("key", "val")` / `Property.get("key")` | Key-value metadata |
| `getMetadata("Info")` / `setMetadata("Info", "text")` | Image info |
| `Stack.setSlice(n)` / `Stack.setChannel(n)` / `Stack.setFrame(n)` | Navigate |
| `Stack.setDisplayMode("composite")` | composite/color/grayscale |
| `Stack.setActiveChannels("110")` | Binary visibility |
| `newImage("title", "16-bit", w, h, ch, sl, fr)` | Create image |

---

## §9 Pixel Access

```javascript
v = getPixel(x, y);        // raw value; bilinear for non-integer
setPixel(x, y, value);
v = getValue(x, y);        // calibrated; NaN outside bounds
// RGB decomposition:
red = (v >> 16) & 0xff; green = (v >> 8) & 0xff; blue = v & 0xff;
```

---

## §10 Drawing (Destructive)

For non-destructive annotations, use Overlay instead.

| Function | Notes |
|----------|-------|
| `drawLine(x1, y1, x2, y2)` / `drawRect(x, y, w, h)` / `drawOval(x, y, w, h)` | Outlines |
| `fillRect(x, y, w, h)` / `fillOval(x, y, w, h)` | Filled shapes |
| `drawString("text", x, y)` | Optional background colour arg |
| `floodFill(x, y, "4-connected")` | Fill from point |
| `setColor(r, g, b)` or `setColor("red")` | Drawing colour |
| `setFont("SansSerif", 18, "bold")` | Font |
| `setLineWidth(2)` | Line width |
| `setJustification("center")` | Text alignment |

---

## §11 Color API

| Function | Notes |
|----------|-------|
| `Color.set("red")` | Drawing colour (name or hex) |
| `Color.setForeground("red")` / `Color.setBackground("blue")` | Also RGB args |
| `Color.setForegroundValue(128)` | Grayscale |
| `Color.foreground` / `Color.background` | Get current |
| `Color.getLut(reds, greens, blues)` | 3 x 256 arrays |
| `Color.setLut(reds, greens, blues)` | Apply custom LUT |
| `Color.toArray("red")` | Returns [r, g, b] |
| `Color.wavelengthToColor(550)` | nm (380-750) to hex |

---

## §12 Statistics & Threshold

```javascript
getStatistics(area, mean, min, max, std, histogram);  // trailing args optional
getRawStatistics(nPix, mean, min, max, std, histogram); // uncalibrated
List.setMeasurements;  // populates List with all measurement values
area = List.getValue("Area");
```

Threshold methods: Default, Huang, IsoData, Li, MaxEntropy, Mean, MinError, Minimum, Moments, **Otsu**, Percentile, RenyiEntropy, Shanbhag, **Triangle**, Yen. Add `dark` for dark backgrounds.

---

## §13 Plot API

### Create & Add Data

```javascript
Plot.create("Title", "X Label", "Y Label", xArr, yArr);
Plot.add("line", xArr, yArr, "Legend label");
// Types: line, connected circle, filled, bar, circle, box, triangle, diamond, cross, x, dot, error bars
Plot.addText("annotation", normX, normY);  // 0-1 coords
Plot.addHistogram(dataArr);
Plot.show();
```

### Styling

| Function | Notes |
|----------|-------|
| `Plot.setColor("red", "blue")` | Line, fill |
| `Plot.setLineWidth(2)` | For next elements |
| `Plot.setBackgroundColor("white")` | Background |
| `Plot.setLegend("A\tB", "top-right")` | transparent option |
| `Plot.setFontSize(14)` | Default font |

### Axes & Limits

| Function | Notes |
|----------|-------|
| `Plot.setLimits(xMin, xMax, yMin, yMax)` | NaN = auto |
| `Plot.setLimitsToFit()` | Auto-fit all data |
| `Plot.setXYLabels("X", "Y")` | Change labels |
| `Plot.setLogScaleX()` / `Plot.setLogScaleY()` | Log axes |

### Output

| Function | Notes |
|----------|-------|
| `Plot.getValues(xArr, yArr)` | Extract data |
| `Plot.setFrameSize(400, 300)` | Plot area pixels |
| `Plot.makeHighResolution("Title", scale)` | High-DPI copy |
| `Plot.drawLine(x1, y1, x2, y2)` | Annotation line |

---

## §14 Curve Fitting (Fit.*)

```javascript
Fit.doFit("Straight Line", xArr, yArr);  // optional initial params
r2 = Fit.rSquared;
a = Fit.p(0); b = Fit.p(1);  // fitted parameters
yPred = Fit.f(x);             // evaluate
Fit.plot();                    // show fit
Fit.logResults();              // to Log window
```

Equations: "Straight Line", "2nd/3rd/4th Degree Polynomial", "Exponential", "Power", "Log", "Gaussian", "Rodbard", "Gamma Variate"

---

## §15 Batch & Automation

| Function | Notes |
|----------|-------|
| `setBatchMode(true)` | 10-100x faster |
| `setBatchMode("exit and display")` | Show results |
| `run("Cmd", "args")` | Synchronous |
| `doCommand("Cmd")` | Asynchronous |
| `runMacro(path, arg)` | Run macro file |
| `eval("js", code)` / `eval("python", code)` | Script eval |
| `call("class.method", args)` | Static Java method |
| `exec("command", "arg1")` | OS command |
| `setOption("name", bool)` | ImageJ options |
| `IJ.redirectErrorMessages()` | Errors to Log |

### setOption Names

| Option | Effect |
|--------|--------|
| BlackBackground | Binary ops assume black bg |
| ExpandableArrays | Arrays auto-grow |
| ScaleConversions | Scale on bit depth change |
| Limit to Threshold | Restrict measurements |
| Changes | Mark image changed (false prevents "Save?" dialog) |
| WaitForCompletion | If false, exec() returns immediately |

---

## §16 List (Key-Value Store)

| Function | Notes |
|----------|-------|
| `List.set("key", val)` / `List.get("key")` / `List.getValue("key")` | String / number |
| `List.setMeasurements` | Populate with image measurements |
| `List.setCommands` | All menu commands |
| `List.clear()` / `List.size` | Management |
| `List.toArrays(keys, vals)` / `List.fromArrays(keys, vals)` | Conversion |

---

## §17 Array Functions

| Function | Notes |
|----------|-------|
| `newArray(1, 2, 3)` / `newArray(100)` | Create |
| `Array.getSequence(n)` | 0 to n-1 |
| `Array.concat(a, b)` / `Array.copy(a)` | Join / clone |
| `Array.sort(a)` / `Array.reverse(a)` | In-place |
| `Array.slice(a, start, end)` / `Array.trim(a, n)` | Extract |
| `Array.fill(a, val)` | Set all |
| `Array.getStatistics(a, min, max, mean, std)` | Stats |
| `Array.filter(a, "text")` | Contains; "(regex)" for regex |
| `Array.findMaxima(a, tol)` / `Array.findMinima(a, tol)` | Peaks |
| `Array.rankPositions(a)` | Indices sorted by value |
| `Array.fourier(a, "Hamming")` | FFT amplitudes |
| `Array.deleteIndex(a, i)` / `Array.deleteValue(a, v)` | Remove |
| `arr.length` | Element count |

---

## §18 String Functions

| Function | Notes |
|----------|-------|
| `substring(s, start, end)` | end exclusive |
| `indexOf(s, sub)` / `lastIndexOf(s, sub)` | Position (-1 if not found) |
| `startsWith(s, pre)` / `endsWith(s, suf)` | Boolean |
| `replace(s, old, new)` | Supports regex |
| `split(s, delim)` | To array |
| `matches(s, regex)` | Full match |
| `toLowerCase(s)` / `toUpperCase(s)` | Case |
| `String.trim(s)` / `String.join(arr, ",")` | Whitespace / join |
| `d2s(num, decimals)` | Number formatting |
| `parseInt(s)` / `parseFloat(s)` | Parse |
| `IJ.pad(5, 3)` | "005" — zero-padded |
| `String.copy(s)` / `String.paste` | Clipboard |

---

## §19 Math Functions

| Function | Notes |
|----------|-------|
| `abs`, `sqrt`, `pow(b,e)`, `exp`, `log` | Standard |
| `sin`, `cos`, `tan`, `asin`, `acos`, `atan2(y,x)` | Radians |
| `round`, `floor`, `ceil` | Rounding |
| `maxOf(a,b)` / `minOf(a,b)` | Also `Math.max/min` |
| `random` | Uniform 0-1; `random("gaussian")` for N(0,1) |
| `random("seed", 42)` | Reproducibility |
| `Math.log10(n)` / `Math.toRadians(d)` / `Math.toDegrees(r)` | Conversions |
| `Math.constrain(n, min, max)` | Clamp |
| `Math.map(n, lo1, hi1, lo2, hi2)` | Rescale |
| `isNaN(n)` / `PI` / `NaN` | Constants |
| `getTime()` | Epoch milliseconds |

---

## §20 Extension Functions (Ext.*)

Plugins register macro extensions via MacroExtension interface.

```javascript
// Bio-Formats
Ext.setId("/path/to/file.nd2");
Ext.getSeriesCount(count);
Ext.setSeries(0);

// CLIJ2 GPU
Ext.CLIJ2_push("image");
Ext.CLIJ2_gaussianBlur3D("image", "blurred", 2, 2, 1);
Ext.CLIJ2_pull("blurred");
```

---

## §21 Keyboard Shortcuts

### File & Edit

| Key | Action |
|-----|--------|
| Ctrl+O/W/S/Z | Open / Close / Save / Undo |
| Ctrl+A / Ctrl+Shift+A | Select All / None |
| Ctrl+Shift+D | Duplicate |
| Ctrl+T | Add to ROI Manager |
| Ctrl+Shift+E | Restore Selection |

### Image & Analysis

| Key | Action |
|-----|--------|
| Ctrl+Shift+C / T | B&C / Threshold |
| Ctrl+M / H | Measure / Histogram |
| Ctrl+I / Ctrl+Shift+P | Info / Properties |
| Ctrl+L | Command Finder |

### Navigation

| Key | Action |
|-----|--------|
| +/- | Zoom in/out |
| >/< | Next/prev slice |
| Ctrl+Arrow | Channel; Alt+Arrow: frame |
| Space (hold) | Temporary hand tool |

### Custom

```javascript
macro "My Macro [f5]" { run("Measure"); }   // F5 shortcut
macro "Quick [q]" { run("Gaussian Blur...", "sigma=2"); }
```
