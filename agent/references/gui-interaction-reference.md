# ImageJ Macro — GUI & Interaction API Reference (Condensed)

Complete function reference for Dialog, Plot, Fit, Color, Ext, drawing, pixel access,
math, strings, arrays, and automation. All work via `python ij.py macro 'CODE'`.

---

## 1. Dialog API

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

## 2. User Interaction

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

## 3. Window Management

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

## 4. ROI Functions

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

## 5. Overlay Functions

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

## 6. Results Table

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

## 7. Image Properties & Navigation

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

## 8. Pixel Access

```javascript
v = getPixel(x, y);        // raw value; bilinear for non-integer
setPixel(x, y, value);
v = getValue(x, y);        // calibrated; NaN outside bounds
// RGB decomposition:
red = (v >> 16) & 0xff; green = (v >> 8) & 0xff; blue = v & 0xff;
```

---

## 9. Drawing (Destructive)

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

## 10. Color API

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

## 11. Statistics & Threshold

```javascript
getStatistics(area, mean, min, max, std, histogram);  // trailing args optional
getRawStatistics(nPix, mean, min, max, std, histogram); // uncalibrated
List.setMeasurements;  // populates List with all measurement values
area = List.getValue("Area");
```

Threshold methods: Default, Huang, IsoData, Li, MaxEntropy, Mean, MinError, Minimum, Moments, **Otsu**, Percentile, RenyiEntropy, Shanbhag, **Triangle**, Yen. Add `dark` for dark backgrounds.

---

## 12. Plot API

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

## 13. Curve Fitting (Fit.*)

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

## 14. Batch & Automation

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

## 15. List (Key-Value Store)

| Function | Notes |
|----------|-------|
| `List.set("key", val)` / `List.get("key")` / `List.getValue("key")` | String / number |
| `List.setMeasurements` | Populate with image measurements |
| `List.setCommands` | All menu commands |
| `List.clear()` / `List.size` | Management |
| `List.toArrays(keys, vals)` / `List.fromArrays(keys, vals)` | Conversion |

---

## 16. Array Functions

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

## 17. String Functions

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

## 18. Math Functions

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

## 19. Extension Functions (Ext.*)

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

## 20. Keyboard Shortcuts

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
