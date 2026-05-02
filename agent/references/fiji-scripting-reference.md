# Fiji Scripting Reference (Groovy & Jython)

Reference for scripting Fiji/ImageJ via the agent's `run_script` TCP command.
All scripts are copy-paste ready for `python ij.py script`.

Sources: `imagej.net/scripting/`, `imagej.net/scripting/groovy`,
`imagej.net/scripting/jython`, `imagej.net/develop/scripting-parameters`,
`javadoc.scijava.org/ImageJ1/` (IJ1 API), Fiji plugin javadocs (TrackMate,
SNT, CLIJ2, MorphoLibJ, Bio-Formats).

Invoke from the agent:
`python ij.py script '<code>'` — run Groovy (default).
`python ij.py script --lang jython '<code>'` — run Jython (Python 2.7).
`python ij.py script --file <path>` — run a script file.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "How do I run a Groovy / Jython script?" | §2 |
| "When should I use a script instead of a macro?" | §3 |
| "Which language should I pick — Groovy, Jython, JavaScript?" | §4 |
| "How do SciJava `#@` parameters work?" | §5 |
| "What services can I auto-inject (OpService, LogService, etc.)?" | §5 |
| "What's the IJ1 API for `ImagePlus` / `ImageProcessor`?" | §6 |
| "How do I read / write pixel values directly?" | §6 |
| "How do I manage ROIs from a script?" | §6 |
| "How do I read / write the Results table from a script?" | §6 |
| "How do I draw overlays programmatically?" | §6 |
| "How do I convert between IJ1 `ImagePlus` and IJ2 `Img`?" | §7 |
| "What's the OpService for (filter.gauss, threshold.otsu, etc.)?" | §7 |
| "Groovy closure / collection / regex syntax?" | §8 |
| "How do I read/write a file in Groovy?" | §8 |
| "Jython signed-byte / integer-division gotchas?" | §9 |
| "How do I find a Swing window by title?" | §10 |
| "How do I walk a Swing component tree / click a button / toggle a checkbox?" | §10 |
| "How do I build a `GenericDialog` from a script?" | §10 |
| "How do I drive TrackMate from a script?" | §11 |
| "How do I call CLIJ2 (GPU), MorphoLibJ, Weka, SNT, Bio-Formats?" | §11 |
| "How do I pass data between a macro and a script?" | §12 |
| "How do I return structured JSON from a script?" | §12 |
| "CTCF measurement / batch processing / Pearson colocalization recipes?" | §13 |
| "Multi-threaded stack processing?" | §13 |
| "Why does my script say `ClassNotFoundException` / `Must run on EDT`?" | §14 |
| "What's the correct import path for `RoiManager` / `ResultsTable`?" | §14, §15 |
| "Language names for `run_script`?" | §14 |
| "One-liner to close all open images safely?" | §16 |
| "Why is my per-pixel loop slow?" | §17 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to every function, class, plugin, keyword, and concept
that appears below. Use `grep -n '`<term>`' fiji-scripting-reference.md` to
jump.

### A

`abs` §6 · `add` §6 · `addAllAnalyzers` §11 · `addChoice` §10 · `addCheckbox` §10 · `addNumericField` §10 · `addRoi` §6 · `addSlice` §6 · `addSlider` §10 · `addValue` §6 · `append` §8 · `applyClassifier` §11 · `array` (Jython) §9, §10 · `Arrow` §6 · `autoThreshold` §6 · `awaitTermination` §13

### B

`Batch processing` §13 · `BF.openImagePlus` §11 · `Bio-Formats` §11, §15 · `blurGaussian` §6, §13 · `Boolean` (`#@`) §5 · `Bounding` (checkbox toggle) §10 · `ByteProcessor` §6, §15

### C

`Calibration` §6, §15 · `call` (macro) §3 · `ChannelSeparator` §11 · `ChannelSplitter` §13, §14, §15 · `checkInput` §11 · `ClassNotFoundException` §14 · `CLIJ2` §11 · `clij2.create` §11 · `clij2.pull` §11 · `clij2.push` §11 · `clij2.release` §11 · `Closures` §8 · `collect` §8 · `ColorRGB` (`#@`) §5 · `COLOR_MODE_COMPOSITE` §11 · `CommandService` §5 · `Common problems` §14 · `computeSpotFeatures` §11 · `computeTrackFeatures` §11 · `Colocalization recipe` §13 · `connectedComponentsLabelingBox` §11 · `Container` §10 · `convertToByte` §6 · `convertToFloat` §6, §13 · `convolve` §6 · `crop` §6 · `CTCF` §13

### D

`Data passing` §12 · `deleteRow` §6 · `deleteSlice` §6 · `DisplayService` §5 · `dilate` §6 · `doClick` §10, §16 · `drawLine` §6 · `drawString` §6 · `Duplicator` §14, §15 · `duplicate` (`ImagePlus`) §6

### E

`each` §8 · `eachFileRecurse` §8 · `eachWithIndex` §8, §13 · `EDT` §14 · `erode` §6 · `Error handling (script)` §12 · `eval` (macro) §3 · `execDetection` §11 · `execTracking` §11 · `Executors` §13 · `Exception handling` (Groovy) §8

### F

`File` (`#@`) §5 · `File I/O` (Groovy) §8 · `FileSaver` §6, §13, §15 · `find` §8 · `findAll` §8, §10 · `findEdges` §6 · `FloatProcessor` §6, §15 · `Float` (`#@`) §5 · `flatten` §6 · `flipHorizontal` §6 · `flipVertical` §6 · `fps` §6 · `frameInterval` §6 · `fromJson` §8

### G

`gamma` §6 · `gaussianBlur3D` §11 · `GenericDialog` §10, §15 · `getBitDepth` §6 · `getCalibration` §6, §16 · `getColumn` §6 · `getColumnIndex` §6 · `getComponents` §10 · `getCount` (RoiManager) §6 · `getCurrentImage` §6 · `getDataset` (error) §14 · `getDefaultSettings` §11 · `getErrorMessage` §11 · `getf / setf` §6 · `getFeature` §11 · `getHeight` §6, §8 · `getIDList` §6, §16 · `getImage` §6 · `getImageTitles` §6 · `getInstance` (RoiManager) §6 · `getInstance` (CLIJ2) §11 · `getMax` (Sholl) §11 · `getNBranches` §11 · `getNChannels / getNSlices / getNFrames` §6 · `getName` §6 · `getNextChoice` §10 · `getNextNumber` §10 · `getNonImageTitles` §6 · `getOverlay` §6 · `getPixel` §6 · `getPixels` §6, §13, §17 · `getPixelValue` §6, §17 · `getProcessor` §6 · `getResultsTable` §6 · `getRoi` §6 · `getRoisAsArray` §6, §13 · `getSelectedItem` §10 · `getSeriesCount` §11 · `getSize` (stack) §6 · `getSizeC / getSizeZ / getSizeT` §11 · `getStack` §6 · `getStackIndex` §13 · `getStackSize` §6 · `getStatistics` §6, §13 · `getStringValue` §6 · `getText` §10, §16 · `getTips` §11 · `getTitle` §6, §8, §16 · `getType` §6 · `getUnit` §6 · `getValue` §6 · `getWidth` §6, §8 · `Gson` §8, §12, §16 · `groupBy` §8 · `GStrings` §4, §8

### H

`help` (ops) §7 · `Hyperstack` §6, §13

### I

`IJ` (class) §6, §15 · `IJ.createImage` §6 · `IJ.freeMemory` §14 · `IJ.getImage` §6 · `IJ.log` §2, §6 · `IJ.open` §6 · `IJ.openImage` §6, §13 · `IJ.run` §6, §13, §17 · `IJ.save` §6 · `IJ.saveAs` §6 · `IJ.showProgress` §6, §13 · `IJ.showStatus` §6 · `IJ1 <-> IJ2 conversion` §7 · `IJ1 API` §6 · `ij.plugin.frame.RoiManager` §6, §14, §15 · `ij.measure.ResultsTable` §6, §14, §15 · `ImageCalculator` §14, §15 · `ImageJFunctions.wrap` §7 · `ImagePlus` §6, §15 · `ImageProcessor` §6, §15 · `ImageStack` §6, §15 · `import paths` §14, §15 · `incrementCounter` §6, §13 · `Integer` (`#@`) §5 · `inject` §8 · `Interpolation` §6 · `IntrinsicVolumes2D` §11 · `invokeAndWait` §14 · `IOService` §5 · `ImporterOptions` §11, §15

### J

`jarray` §9, §15 · `javascript` §4, §14 · `jython` §4, §9, §14, §15 · `JButton` §10 · `JCheckBox` §10, §16 · `JComboBox` §10 · `JLabel` §10 · `JSlider` §10 · `JTextField` §10

### L

`LabelImages` §11 · `labelAllParticles` §11 · `Language comparison` §4 · `Line` (roi) §6, §15 · `Lists` (Groovy) §8 · `LogDetectorFactory` §11 · `LogService` §5

### M

`Maps` §8 · `Math.sqrt` §13 · `Measurements` (constants) §6, §13, §15 · `medianFilter` §6 · `MorphoLibJ` §11 · `Morphology.gradient` §11 · `Morphology.opening` §11 · `MouseLightLoader` §11 · `mergeChannels` §13 · `Multi Measure` §6 · `Multi-threaded processing` §13 · `multiply` §6

### N

`new ImagePlus` §6 · `new ImageStack` §6 · `new ResultsTable` §6, §13 · `newFixedThreadPool` §13 · `NoSuchMethodError` §14

### O

`OpService` §5, §7 · `ops.filter.gauss` §7 · `ops.math.add` §7 · `ops.morphology.erode` §7 · `ops.stats.mean` §7 · `ops.threshold.otsu` §7 · `Overlay` §6, §15 · `OvalRoi` §6, §15

### P

`ParticleAnalyzer` §14, §15 · `Parameters (SciJava)` §5 · `Pearson R` §13 · `Performance tips` §17 · `pixelDepth` §6 · `pixelHeight` §6 · `pixelWidth` §6 · `Plot` §15 · `Plugin scripting APIs` §11 · `Pool` (thread) §13

### Q

`Quick Start` §2

### R

`Raycaster` §10 · `Regex` (Groovy) §8 · `rename` (RoiManager) §6 · `reset` (RoiManager) §6 · `resize` §6 · `ResultsTable` §6, §13, §15 · `RGBStackMerge` §13, §15 · `Roi` §6, §15 · `RoiManager` §6, §13, §14, §15 · `rotate` §6 · `runCommand` (RoiManager) §6 · `run_script` (TCP) §2, §12 · `run` (IJ) §6

### S

`save` (RoiManager) §6 · `saveAs` (IJ) §6 · `saveAsJpeg` §6 · `saveAsPng` §6 · `saveAsTiff` §6, §13 · `saveAsTiffStack` §6 · `saveClassifier` §11 · `SciJava script parameters` §5 · `SciJava services` §5 · `ScriptEngine not found` §14 · `setC / setZ / setT` §6 · `setCalibration` §6 · `setColor` §6 · `setDisplayRange` §6 · `setId` §11 · `setInterpolationMethod` §6 · `setLineWidth` §6 · `setOpenAllSeries` §11 · `setOverlay` §6 · `setPosition` §6 · `setProcessor` §6 · `setRoi` §6, §13 · `setSeries` §11 · `setSeriesOn` §11 · `setStrokeColor` §6 · `setStrokeWidth` §6 · `setText` §10 · `setThreshold` §6 · `setUnit` §6 · `setValue` §6 · `Sets` (Groovy) §8 · `Settings` (TrackMate) §11 · `sharpen` §6 · `ShollAnalyzer` §11 · `show` §6 · `size` (ResultsTable) §6 · `smooth` §6 · `SNT` §11 · `sort` §6 · `SparseLAPTrackerFactory` §11 · `sqrt` §6, §13 · `stack_stats` (pixels) — see agent CLAUDE · `Stack.*` §6 · `Strel.Shape.DISK` §11 · `StrahlerAnalyzer` §11 · `String` (`#@`) §5 · `Structured data return` §12 · `SwingUtilities.invokeAndWait` §14

### T

`TextRoi` §6, §15 · `thresholdOtsu` §11 · `times` §6 · `TmXmlWriter` §11 · `toJson` §8, §12 · `TrackMate` §11 · `Trainable Weka Segmentation` §11 · `trackIDs` §11 · `TreeStatistics` §11 · `try / catch / finally` §8, §12, §14

### U

`unable to resolve class` §14 · `updateAndDraw` §6, §13, §17

### W

`walkComponents` §10 · `wasOKed` §10 · `WekaSegmentation` §11, §14 · `WindowManager` §6, §15, §16

### Z

`ZProjector` §14, §15

---

## §2 Quick Start

```bash
python ij.py script 'import ij.IJ; IJ.log("Hello from Groovy")'
python ij.py script --lang jython 'from ij import IJ; IJ.log("Hello")'
python ij.py script --file .tmp/my_analysis.groovy
```

The TCP command: `{"command": "run_script", "code": "...", "language": "groovy"}`
Response: `{"ok": true, "result": {"success": true, "language": "groovy", "output": "...", "executionTimeMs": 42}}`

Scripts run inside Fiji's JVM with full access to all Java APIs, plugins, and the classpath.

---

## §3 When to Script (vs Macros)

| Capability | Macro | Script |
|-----------|-------|--------|
| Java API access | Limited (`call()`) | Full, native |
| Collections/maps | Arrays only | Lists, maps, sets |
| Error handling | None | try/catch/finally |
| GUI manipulation | GenericDialog only | Full Swing/AWT |
| Threading | No | Full Java threading |
| Regex | No | Full support |
| Plugin internals | No | Direct class access |
| File I/O | Basic | Full (JSON, CSV, etc.) |
| Classes/OOP | No | Full support |

**Use macros** for simple threshold-measure-save workflows. **Use scripts** for plugin API access, GUI manipulation, complex data structures, or batch processing with error handling.

---

## §4 Language Comparison

| Feature | Groovy | Jython | JavaScript |
|---------|--------|--------|------------|
| Speed | Fast (bytecode) | Slower (interpreted) | Medium |
| Syntax | Java-like, concise | Python 2 | ECMAScript 5 |
| Strengths | GStrings, closures, collections | Familiar to Python users | Quick one-offs |
| Limitations | None significant | No NumPy/SciPy, Python 2 only | Less commonly used |
| Best for | Default choice for all scripting | Python users, quick prototyping | Simple automation |

---

## §5 SciJava Script Parameters

Syntax: `#@ Type (properties) variableName`

**Agent note**: Parameters requesting user input create dialogs. For automation, typically hardcode values. Service injection parameters work without dialogs and are useful.

### §5.1 Parameter Types

| Type | Example | Notes |
|------|---------|-------|
| ImagePlus | `#@ ImagePlus imp` | Auto-injects active image |
| Integer | `#@ Integer (label="Sigma", min=1, max=20, value=3, style="slider") sigma` | |
| Float/Double | `#@ Float (label="Threshold", min=0.0, max=1.0, stepSize=0.01, value=0.5) t` | |
| String | `#@ String (label="Method", choices={"Otsu","Triangle","Li"}) method` | styles: radioButtonHorizontal, listBox, text area |
| Boolean | `#@ Boolean (label="Exclude edges", value=true) excludeEdges` | |
| File | `#@ File (label="Input", style="open") inputFile` | styles: open, save, directory |
| ColorRGB | `#@ ColorRGB (label="Color") color` | |
| Output | `#@output String summary` | ImagePlus outputs auto-display |

### §5.2 Properties

| Property | Description |
|----------|-------------|
| `label` | Display label |
| `value` | Default value |
| `min`, `max`, `stepSize` | Numeric range |
| `style` | Widget style (slider, spinner, etc.) |
| `choices` | Dropdown/radio options |
| `persist` | Remember between runs (default true) |
| `visibility` | NORMAL, MESSAGE, INVISIBLE |

### §5.3 SciJava Services (auto-injected, no dialog)

```groovy
#@ ij.ImagePlus imp                              // current image
#@ org.scijava.command.CommandService command     // run IJ2 commands
#@ org.scijava.log.LogService log                // structured logging
#@ net.imagej.ops.OpService ops                  // operations framework
#@ org.scijava.io.IOService io                   // file I/O
#@ org.scijava.display.DisplayService display     // show results
#@ org.scijava.Context context                   // SciJava context
```

---

## §6 IJ1 API Reference

### §6.1 Core Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `IJ` | `ij` | Static utilities (open, run, log, save) |
| `ImagePlus` | `ij` | Image object (wraps processors, stacks, metadata) |
| `ImageProcessor` | `ij.process` | Pixel access and manipulation |
| `ImageStack` | `ij` | Z-stack/time-series of processors |
| `WindowManager` | `ij` | Track open image windows |
| `RoiManager` | `ij.plugin.frame` | Region of interest management |
| `ResultsTable` | `ij.measure` | Measurement results |
| `Overlay` | `ij.gui` | Non-destructive annotations |
| `Calibration` | `ij.measure` | Pixel-to-physical unit mapping |
| `FileSaver` | `ij.io` | Save in various formats |

### §6.2 IJ Static Methods

```groovy
import ij.IJ

// I/O
def imp = IJ.openImage("/path/to/image.tif")     // Open without showing
IJ.open("/path/to/image.tif")                     // Open and show
def imp2 = IJ.createImage("blank", "16-bit black", 512, 512, 1)

// Run commands (same as macro run())
IJ.run("Blobs (25K)")
IJ.run(imp, "Gaussian Blur...", "sigma=2")

// Current image
def imp3 = IJ.getImage()                           // Throws if none open

// Logging & progress
IJ.log("Message")
IJ.log("\\Clear")
IJ.showStatus("Processing...")
IJ.showProgress(5, 100)

// Save
IJ.saveAs(imp, "Tiff", "/path/to/output.tif")
IJ.save(imp, "/path/to/output.tif")               // Auto-detect format
```

### §6.3 ImagePlus

```groovy
def imp = IJ.getImage()

// Properties
imp.getTitle()                                    // String
imp.getWidth(); imp.getHeight()                   // int
imp.getBitDepth()                                 // 8, 16, 24, 32
imp.getType()                                     // GRAY8=0, GRAY16=1, GRAY32=2, COLOR_256=3, COLOR_RGB=4
imp.getStackSize()                                // Total slices

// Hyperstack dimensions (1-based navigation)
imp.getNChannels(); imp.getNSlices(); imp.getNFrames()
imp.setPosition(channel, slice, frame)
imp.setC(2); imp.setZ(5); imp.setT(3)

// Pixel access
ImageProcessor ip = imp.getProcessor()            // Current slice
def stack = imp.getStack()                        // All slices
ImageProcessor ip5 = stack.getProcessor(5)        // 1-based

// Calibration
def cal = imp.getCalibration()
cal.pixelWidth; cal.pixelHeight; cal.pixelDepth; cal.getUnit()
cal.scaled()                                      // Has real calibration?

// Statistics (respects current ROI)
def stats = imp.getStatistics()                   // area, mean, stdDev, min, max
import ij.measure.Measurements
def stats2 = imp.getStatistics(Measurements.MEAN | Measurements.MEDIAN | Measurements.AREA)

// Display
imp.show(); imp.close(); imp.updateAndDraw()
imp.setDisplayRange(0, 4095)                      // Display only, not pixel values
imp.changes = false                               // No save prompt on close

// Duplicate
def dup = imp.duplicate()
def crop = imp.crop()                             // Crop to ROI
```

### §6.4 ImageProcessor

```groovy
def ip = imp.getProcessor()

// Pixel access (choose based on speed needs)
int v = ip.getPixel(x, y)                        // Raw value
float fv = ip.getPixelValue(x, y)                // Calibrated
ip.getf(x, y); ip.setf(x, y, val)               // Fast float access
Object pixelArray = ip.getPixels()               // byte[]/short[]/float[]/int[] — direct reference

// Type conversion
FloatProcessor fp = ip.convertToFloat()
ByteProcessor bp = ip.convertToByte(true)         // true = with scaling

// Arithmetic (modifies in place)
ip.add(50); ip.multiply(1.5); ip.gamma(0.5)
ip.log(); ip.sqrt(); ip.abs()

// Filters (modifies in place)
ip.smooth(); ip.sharpen(); ip.findEdges()
ip.erode(); ip.dilate(); ip.medianFilter()
ip.blurGaussian(2.0)

// Convolution
float[] kernel = [-1,-1,-1, -1,8,-1, -1,-1,-1] as float[]
ip.convolve(kernel, 3, 3)

// Threshold
ip.setThreshold(50, 255, ImageProcessor.RED_LUT)
ip.autoThreshold()

// Transform
ip.resize(newW, newH); ip.rotate(45.0)
ip.flipHorizontal(); ip.flipVertical()
ip.setInterpolationMethod(ImageProcessor.BILINEAR)

// Drawing
ip.setColor(java.awt.Color.RED); ip.setLineWidth(2)
ip.drawLine(0, 0, 100, 100); ip.drawString("Hello", 10, 30)
```

### §6.5 ImageStack

```groovy
def stack = imp.getStack()

// Access (1-based)
stack.getSize(); stack.getProcessor(3); stack.getPixels(3)

// Modify
stack.addSlice("label", new FloatProcessor(w, h))
stack.deleteSlice(5); stack.setProcessor(newIp, 3)

// Create
def newStack = new ImageStack(512, 512)
10.times { newStack.addSlice("", new FloatProcessor(512, 512)) }
def newImp = new ImagePlus("my stack", newStack)
```

### §6.6 WindowManager

```groovy
import ij.WindowManager

int[] ids = WindowManager.getIDList()             // null if none open
def imp = WindowManager.getCurrentImage()          // null if none
def imp2 = WindowManager.getImage("blobs.gif")    // By title
String[] titles = WindowManager.getImageTitles()
String[] nonImage = WindowManager.getNonImageTitles()
```

### §6.7 RoiManager

```groovy
import ij.plugin.frame.RoiManager

def rm = RoiManager.getInstance() ?: new RoiManager()

// Add/access
rm.addRoi(imp.getRoi())
rm.getCount(); rm.getRoisAsArray(); rm.getRoi(0); rm.getName(0)

// Select & measure
rm.select(imp, 0)
rm.runCommand(imp, "Measure")
rm.runCommand(imp, "Multi Measure")

// Manage
rm.rename(0, "cell_1"); rm.reset()
rm.runCommand("Save", "/path/to/rois.zip")
rm.runCommand("Open", "/path/to/rois.zip")

// Per-ROI measurement
rm.getRoisAsArray().eachWithIndex { roi, i ->
    imp.setRoi(roi)
    def stats = imp.getStatistics()
    println "ROI ${rm.getName(i)}: mean=${stats.mean}, area=${stats.area}"
}
```

### §6.8 ResultsTable

```groovy
import ij.measure.ResultsTable

def rt = ResultsTable.getResultsTable()           // Main table (may be null)
def rt2 = new ResultsTable()                      // New table

// Write
rt2.incrementCounter()
rt2.addValue("Area", 523.0)
rt2.addValue("Label", "cell_1")

// Read
rt.size(); rt.getValue("Area", 0); rt.getStringValue("Label", 0)
rt.getHeadings(); rt.getColumn(rt.getColumnIndex("Area"))

// Modify/sort
rt.setValue("CTCF", 0, 12345.67); rt.sort("Area"); rt.deleteRow(5)

// Display/save
rt.show("Results"); rt.save("/path/to/results.csv")
```

### §6.9 Calibration

```groovy
def cal = imp.getCalibration()
cal.pixelWidth; cal.pixelHeight; cal.pixelDepth
cal.getUnit(); cal.scaled()
cal.fps; cal.frameInterval; cal.getTimeUnit()

// Set
cal.pixelWidth = 0.325; cal.setUnit("um")
imp.setCalibration(cal)
```

### §6.10 Overlay

```groovy
import ij.gui.*
import java.awt.Color

def overlay = imp.getOverlay() ?: new Overlay()

def rect = new Roi(100, 100, 200, 200)
rect.setStrokeColor(Color.RED); rect.setStrokeWidth(2)
overlay.add(rect)

overlay.add(new OvalRoi(150, 150, 100, 100))
overlay.add(new Line(0, 0, 255, 255))
overlay.add(new Arrow(50, 50, 150, 150))

def text = new TextRoi(10, 10, "Label")
text.setStrokeColor(Color.WHITE)
overlay.add(text)

imp.setOverlay(overlay)
def flat = imp.flatten()                          // Burn overlay to pixels (destructive)
```

### §6.11 FileSaver

```groovy
import ij.io.FileSaver
def fs = new FileSaver(imp)
fs.saveAsTiff("/path/out.tif"); fs.saveAsPng("/path/out.png")
fs.saveAsJpeg("/path/out.jpg"); fs.saveAsTiffStack("/path/stack.tif")
```

---

## §7 IJ2/SciJava API

**Agent note**: `#@` injection may not work reliably via TCP `run_script`. Prefer IJ1 API for reliability.

### §7.1 OpService

```groovy
#@ net.imagej.ops.OpService ops
ops.help("filter.gauss")
// ops.math().add/multiply/subtract/divide(result, input, value)
// ops.filter().gauss/median(output, input, params)
// ops.threshold().otsu/triangle/li(output, input)
// ops.morphology().erode/dilate/open/close(output, input, strel)
// ops.stats().mean/stdDev/size(input)
```

### §7.2 IJ1 <-> IJ2 Conversion

```groovy
import net.imglib2.img.display.imagej.ImageJFunctions
def img = ImageJFunctions.wrap(imp)              // ImagePlus -> Img (shares pixels)
def imp2 = ImageJFunctions.wrap(img, "title")    // Img -> ImagePlus
```

---

## §8 Groovy Patterns

### §8.1 Strings

```groovy
// Double quotes = interpolation, single quotes = literal
println "Processing ${imp.getTitle()} (${imp.getWidth()}x${imp.getHeight()})"
def macro = """run("Gaussian Blur...", "sigma=${sigma}");"""
def pattern = ~/\d+\.\d+/                        // Regex literal
```

### §8.2 Collections

```groovy
def list = [1, 2, 3]; list << 4                   // Append
def map = [area: 523.0, mean: 128.5]             // LinkedHashMap
map.area; map["area"]                             // Both work
def set = [1, 2, 3] as Set
def range = 1..10                                 // Inclusive
```

### §8.3 Closures & Functional

```groovy
[1,2,3].each { println it }
["a","b"].eachWithIndex { val, i -> println "${i}: ${val}" }
def doubled = [1,2,3].collect { it * 2 }          // [2,4,6]
def big = [10,50,200].findAll { it > 20 }         // [50,200]
def first = [10,50,200].find { it > 20 }          // 50
def sum = [1,2,3,4].inject(0) { acc, v -> acc + v } // 10
boolean any = [1,2,100].any { it > 50 }
def grouped = [1,2,3,4].groupBy { it % 2 == 0 ? "even" : "odd" }
```

### §8.4 File I/O

```groovy
def text = new File("/path/file.txt").text        // Read entire file
new File("/path/out.txt").text = "content"        // Write
new File("/path/log.txt").append("line\n")        // Append

new File("/path/folder").eachFileRecurse { f ->
    if (f.name.endsWith(".tif")) println f.absolutePath
}

// JSON via Gson (available in Fiji)
import com.google.gson.Gson
def json = new Gson().toJson([name: "exp1", cells: 42])
def parsed = new Gson().fromJson('{"x": 1}', Map.class)
```

### §8.5 Regex

```groovy
def text = "blobs_ch1_z05.tif"
if (text =~ /ch\d+/) { /* partial match */ }
def matcher = (text =~ /ch(\d+)_z(\d+)/)
if (matcher.find()) { def ch = matcher.group(1) }
```

### §8.6 Exception Handling

```groovy
try {
    def imp = ij.IJ.getImage()
} catch (java.io.FileNotFoundException e) {
    println "File not found: ${e.message}"
} catch (Exception e) {
    println "Error: ${e.message}"
} finally {
    println "Cleanup"
}
```

---

## §9 Jython Patterns

Jython = Python 2.7. No NumPy/SciPy/external packages.

### §9.1 Java Interop

```python
from ij import IJ, ImagePlus, WindowManager
from ij.process import FloatProcessor
from ij.measure import ResultsTable, Measurements
from ij.gui import Roi, GenericDialog
from ij.plugin.frame import RoiManager
from java.awt import Color, Font
from jarray import zeros, array
from java.lang import String

# Java arrays (needed for some APIs like GenericDialog.addChoice)
options = array(["Option 1", "Option 2"], String)
bytes = zeros(100, 'b')                           # byte array
doubles = array([1.0, 2.0], 'd')                  # double array
```

### §9.2 Gotchas

| Issue | Problem | Fix |
|-------|---------|-----|
| Signed bytes | `pixels[i]` gives -128..127 | `pixels[i] & 0xFF` |
| Integer division | `7/2` = 3 | `7/2.0` = 3.5 |
| Java arrays | Lists don't work for some APIs | `array(["A","B"], String)` |
| No `with` statement | Early Jython versions | Use try/finally |
| Module path | Can't find modules | `sys.path.append("/path")` |

### §9.3 Common Pattern

```python
from ij import IJ
from ij.measure import Measurements

imp = IJ.openImage("https://imagej.net/ij/images/blobs.gif")
imp.show()
ip = imp.getProcessor()
pixels = ip.getPixels()

# IMPORTANT: mask bytes to unsigned
for i in range(len(pixels)):
    val = pixels[i] & 0xFF
```

---

## §10 GUI Manipulation

### §10.1 Finding Windows

```groovy
import java.awt.Window
import java.awt.Frame

// Find by title
def findWindow(String titlePart) {
    Window.getWindows().find { w ->
        w.isVisible() && w instanceof Frame && ((Frame)w).getTitle().contains(titlePart)
    }
}
```

### §10.2 Walking Component Trees

```groovy
import java.awt.Container
import javax.swing.*

def walkComponents(Container c, Closure action, int depth = 0) {
    c.getComponents().each { comp ->
        action(comp, depth)
        if (comp instanceof Container) walkComponents(comp, action, depth + 1)
    }
}

// Print all components in a window
walkComponents(win) { comp, depth ->
    def indent = "  " * depth
    def text = ""
    if (comp instanceof JLabel) text = " text='${comp.getText()}'"
    if (comp instanceof JCheckBox) text = " '${comp.getText()}' checked=${comp.isSelected()}"
    if (comp instanceof JComboBox) text = " selected='${comp.getSelectedItem()}'"
    if (comp instanceof JTextField) text = " value='${comp.getText()}'"
    if (comp instanceof JSlider) text = " value=${comp.getValue()} [${comp.getMinimum()}-${comp.getMaximum()}]"
    println "${indent}${comp.class.simpleName}${text}"
}
```

### §10.3 Widget Manipulation Helpers

```groovy
// Toggle checkbox by text
def toggleCheckbox(Container win, String text, boolean desired) {
    def found = false
    def walk
    walk = { Container c ->
        c.getComponents().each { comp ->
            if (comp instanceof JCheckBox && comp.getText()?.contains(text)) {
                if (comp.isSelected() != desired) comp.doClick()
                found = true
            }
            if (comp instanceof Container) walk(comp)
        }
    }
    walk(win)
    return found
}

// Set text field by adjacent label
def setTextField(Container c, String labelText, String newValue) {
    def components = []
    def walk
    walk = { Container cont ->
        cont.getComponents().each { comp ->
            components.add(comp)
            if (comp instanceof Container) walk(comp)
        }
    }
    walk(c)
    for (int i = 0; i < components.size() - 1; i++) {
        if (components[i] instanceof JLabel && components[i].getText()?.contains(labelText)) {
            for (int j = i + 1; j < components.size(); j++) {
                if (components[j] instanceof JTextField) {
                    components[j].setText(newValue); return true
                }
            }
        }
    }
    return false
}

// Click button by text
def clickButton(Container c, String buttonText) {
    def walk
    walk = { Container cont ->
        for (def comp : cont.getComponents()) {
            if (comp instanceof JButton && comp.getText()?.contains(buttonText)) {
                comp.doClick(); return true
            }
            if (comp instanceof Container && walk(comp)) return true
        }
        return false
    }
    return walk(c)
}
```

### §10.4 3Dscript Checkbox Toggle Example

```groovy
import javax.swing.JCheckBox
import java.awt.Window
import java.awt.Container

def walk(Container c) {
    c.getComponents().each { x ->
        if (x instanceof JCheckBox) {
            if (x.getText()?.contains("Bounding") && x.isSelected()) x.doClick()
            if (x.getText()?.contains("light") && !x.isSelected()) x.doClick()
        }
        if (x instanceof Container) walk(x)
    }
}
Window.getWindows().findAll {
    it.class.name.contains("animation3d") || it.class.name.contains("Raycaster")
}.each { walk(it) }
```

### §10.5 GenericDialog

```groovy
import ij.gui.GenericDialog
def gd = new GenericDialog("Settings")
gd.addChoice("Method:", ["Otsu","Triangle","Li"] as String[], "Otsu")
gd.addNumericField("Min size:", 50, 0)
gd.addCheckbox("Exclude edges", true)
gd.addSlider("Sigma:", 0, 10, 2)
gd.showDialog()
if (gd.wasOKed()) {
    def method = gd.getNextChoice()
    def minSize = gd.getNextNumber() as int
}
```

**Jython**: `addChoice` needs `array(["A","B"], str)` not a Python list.

---

## §11 Plugin Scripting APIs

### §11.1 TrackMate

```groovy
import fiji.plugin.trackmate.*
import fiji.plugin.trackmate.detection.LogDetectorFactory
import fiji.plugin.trackmate.tracking.jaqaman.SparseLAPTrackerFactory

def imp = ij.IJ.getImage()
def model = new Model()
def settings = new Settings(imp)

// Detector — choose radius based on object size in physical units
settings.detectorFactory = new LogDetectorFactory()
settings.detectorSettings = settings.detectorFactory.getDefaultSettings()
settings.detectorSettings.put("RADIUS", 5.0d)          // Starting point; adjust to typical object radius
settings.detectorSettings.put("THRESHOLD", 100.0d)      // Consider histogram to choose quality threshold
settings.detectorSettings.put("TARGET_CHANNEL", 1)

// Tracker — linking distance typically 1-3x object diameter
settings.trackerFactory = new SparseLAPTrackerFactory()
settings.trackerSettings = settings.trackerFactory.getDefaultSettings()
settings.trackerSettings.put("LINKING_MAX_DISTANCE", 15.0d)
settings.trackerSettings.put("GAP_CLOSING_MAX_DISTANCE", 15.0d)
settings.trackerSettings.put("MAX_FRAME_GAP", 2)

settings.addAllAnalyzers()
def trackmate = new TrackMate(model, settings)
if (!trackmate.checkInput() || !trackmate.execDetection() ||
    !trackmate.computeSpotFeatures(false) || !trackmate.execTracking() ||
    !trackmate.computeTrackFeatures(true)) { println trackmate.getErrorMessage(); return }

// Extract results
model.getTrackModel().trackIDs(true).each { id ->
    def spots = model.getTrackModel().trackSpots(id)
    println "Track ${id}: ${spots.size()} spots"
    spots.each { s -> println "  Frame ${s.getFeature('FRAME').intValue()}: (${s.getFeature('POSITION_X')}, ${s.getFeature('POSITION_Y')})" }
}

// Save XML
def writer = new fiji.plugin.trackmate.io.TmXmlWriter(new File("/path/out.xml"))
writer.appendModel(model); writer.appendSettings(settings); writer.writeToFile()
```

### §11.2 SNT (Neurite Tracing)

```groovy
import sc.fiji.snt.*
import sc.fiji.snt.analysis.*

def loader = new io.MouseLightLoader("AA0100")
def axon = loader.getTree("axon")
def stats = new TreeStatistics(axon)
println "Length: ${stats.getCableLength()}, Branches: ${stats.getNBranches()}, Tips: ${stats.getTips().size()}"

def strahler = new StrahlerAnalyzer(axon)
println "Strahler orders: ${strahler.getHighestBranchOrder()}"

def sholl = new ShollAnalyzer(axon, axon.getRoot())
println "Max intersections: ${sholl.getMax()}, Critical radius: ${sholl.getCriticalRadius()}"
```

### §11.3 Trainable Weka Segmentation

```groovy
import trainableSegmentation.WekaSegmentation
import ij.IJ

// Apply pre-trained classifier
def weka = new WekaSegmentation(IJ.getImage())
weka.loadClassifier("/path/to/classifier.model")
def result = weka.applyClassifier(IJ.getImage(), 0, false)  // false=labels, true=probability maps
result.show()

// Save classifier after training
weka.saveClassifier("/path/to/my_classifier.model")
```

### §11.4 CLIJ2 (GPU)

```groovy
import net.haesleinhuepf.clij2.CLIJ2
def clij2 = CLIJ2.getInstance()
def input = clij2.push(ij.IJ.getImage())
def blurred = clij2.create(input)
def thresholded = clij2.create(input)
def labeled = clij2.create(input)

clij2.gaussianBlur3D(input, blurred, 2, 2, 1)
clij2.thresholdOtsu(blurred, thresholded)
clij2.connectedComponentsLabelingBox(thresholded, labeled)
clij2.pull(labeled).show()

// IMPORTANT: always release GPU memory
[input, blurred, thresholded, labeled].each { clij2.release(it) }
```

### §11.5 MorphoLibJ

```groovy
import inra.ijpb.morphology.*
import inra.ijpb.label.LabelImages
import inra.ijpb.measure.IntrinsicVolumes2D

def ip = ij.IJ.getImage().getProcessor()
def strel = Strel.Shape.DISK.fromRadius(3)       // Adjust radius to feature scale
def opened = Morphology.opening(ip, strel)
def gradient = Morphology.gradient(ip, strel)

def labels = LabelImages.labelAllParticles(ip, 4) // 4-connectivity
println "Found ${LabelImages.findAllLabels(labels).length} components"
```

### §11.6 Bio-Formats

```groovy
import loci.plugins.BF
import loci.plugins.in.ImporterOptions

def opts = new ImporterOptions()
opts.setId("/path/to/file.nd2")
opts.setOpenAllSeries(false)
opts.setSeriesOn(0, true)                         // Open first series
opts.setColorMode(ImporterOptions.COLOR_MODE_COMPOSITE)
def imps = BF.openImagePlus(opts)

// List all series
import loci.formats.ChannelSeparator
def reader = new ChannelSeparator()
reader.setId("/path/to/file.lif")
for (int s = 0; s < reader.getSeriesCount(); s++) {
    reader.setSeries(s)
    println "Series ${s}: ${reader.getSizeX()}x${reader.getSizeY()}, ${reader.getSizeC()}C ${reader.getSizeZ()}Z ${reader.getSizeT()}T"
}
reader.close()
```

---

## §12 Agent Integration

### §12.1 Data Passing Between Macros and Scripts

Macros and scripts share: open images (WindowManager), Results table, ROI Manager, Log window, image properties.

```bash
# Step 1: macro
python ij.py macro 'run("Blobs (25K)"); setAutoThreshold("Otsu"); run("Convert to Mask");'
# Step 2: script (accesses same image)
python ij.py script '
import ij.IJ
def imp = IJ.getImage()
println "Working on: ${imp.getTitle()}"
'
```

### §12.2 Returning Structured Data

```groovy
import com.google.gson.Gson
def results = [particles: 65, meanArea: 432.1, cv: 43.3]
new Gson().toJson(results)   // Last expression = return value in response JSON
```

### §12.3 Error Handling

```groovy
try {
    def imp = ij.IJ.getImage()
    println "SUCCESS: processed ${imp.getTitle()}"
} catch (Exception e) {
    println "ERROR: ${e.message}"
}
```

---

## §13 Complete Examples

### §13.1 CTCF Measurement

```groovy
// CTCF = IntDen - (Area * background mean). Requires ROIs; last ROI = background.
import ij.IJ
import ij.plugin.frame.RoiManager
import ij.measure.Measurements

def imp = IJ.getImage()
def rm = RoiManager.getInstance()
def rois = rm.getRoisAsArray()

// Background from last ROI
imp.setRoi(rois[-1])
def bgMean = imp.getStatistics(Measurements.MEAN).mean

def rt = new ij.measure.ResultsTable()
for (int i = 0; i < rois.length - 1; i++) {
    imp.setRoi(rois[i])
    def s = imp.getStatistics(Measurements.AREA | Measurements.MEAN)
    def ctcf = (s.area * s.mean) - (s.area * bgMean)
    rt.incrementCounter()
    rt.addValue("Cell", i + 1)
    rt.addValue("Area", s.area)
    rt.addValue("CTCF", ctcf)
}
rt.show("CTCF Results")
```

### §13.2 Batch Processing

```groovy
import ij.IJ
import ij.io.FileSaver

def inputDir = new File("/path/to/input")
def outputDir = new File("/path/to/output"); outputDir.mkdirs()
def files = inputDir.listFiles().findAll { it.name.endsWith(".tif") }

files.eachWithIndex { file, idx ->
    IJ.showProgress(idx, files.size())
    try {
        def imp = IJ.openImage(file.absolutePath)
        if (imp == null) { println "SKIP: ${file.name}"; return }
        IJ.run(imp, "Gaussian Blur...", "sigma=2")
        IJ.run(imp, "Auto Threshold", "method=Otsu white")
        new FileSaver(imp).saveAsTiff(new File(outputDir, "proc_${file.name}").absolutePath)
        imp.close()
    } catch (Exception e) { println "ERROR ${file.name}: ${e.message}" }
}
```

### §13.3 Colocalization (No Plugin Needed)

```groovy
import ij.IJ
def imp = IJ.getImage()
def stack = imp.getStack()
def ch1 = stack.getProcessor(imp.getStackIndex(1,1,1)).convertToFloat()
def ch2 = stack.getProcessor(imp.getStackIndex(2,1,1)).convertToFloat()
def p1 = ch1.getPixels() as float[], p2 = ch2.getPixels() as float[]
int n = p1.length

double s1=0, s2=0; for (int i=0; i<n; i++) { s1+=p1[i]; s2+=p2[i] }
double m1=s1/n, m2=s2/n
double num=0, d1=0, d2=0
for (int i=0; i<n; i++) { double a=p1[i]-m1, b=p2[i]-m2; num+=a*b; d1+=a*a; d2+=b*b }
println "Pearson R: ${num / Math.sqrt(d1*d2)}"
```

### §13.4 Hyperstack Channel Split/Merge

```groovy
import ij.IJ
import ij.plugin.ChannelSplitter
import ij.plugin.RGBStackMerge

def imp = IJ.getImage()
def channels = ChannelSplitter.split(imp)
IJ.run(channels[0], "Subtract Background...", "rolling=50 stack")
IJ.run(channels[1], "Gaussian Blur...", "sigma=1 stack")
def merged = RGBStackMerge.mergeChannels(channels, false)
merged.setTitle("Processed_${imp.getTitle()}")
merged.show()
```

### §13.5 Multi-threaded Stack Processing

```groovy
import ij.IJ
import java.util.concurrent.*

def imp = IJ.getImage()
def stack = imp.getStack()
def nThreads = Runtime.getRuntime().availableProcessors()
def pool = Executors.newFixedThreadPool(nThreads)

for (int s = 1; s <= stack.getSize(); s++) {
    final int slice = s
    pool.submit({
        def ip = stack.getProcessor(slice).duplicate()
        ip.blurGaussian(2.0)
        stack.setProcessor(ip, slice)
    } as Runnable)
}
pool.shutdown()
pool.awaitTermination(5, TimeUnit.MINUTES)
imp.updateAndDraw()
```

---

## §14 Common Problems & Solutions

| Error | Cause | Fix |
|-------|-------|-----|
| `ClassNotFoundException: ij.plugins.RoiManager` | Wrong package | `ij.plugin.frame.RoiManager` |
| `NoSuchMethodError: ImagePlus.getDataset()` | IJ2 method on IJ1 object | Use IJ1 API or convert explicitly |
| `Must run on EDT` | Swing modified from background thread | Wrap in `SwingUtilities.invokeAndWait { }` |
| `ScriptEngine not found: python` | Wrong language name | Use `jython`, not `python` |
| Memory leak in batch | Images not closed | `imp.close()` in finally block; `System.gc()` periodically |
| `unable to resolve class WekaSegmentation` | Plugin not installed | Check via Help > Update > Manage Update Sites |
| Jython negative pixel values | Java bytes are signed | `pixels[i] & 0xFF` |
| GenericDialog choice fails | Needs String[] | `["A","B"] as String[]` (Groovy) or `array(["A","B"], str)` (Jython) |

### §14.1 Correct Import Paths

| Class | Correct Import |
|-------|---------------|
| RoiManager | `ij.plugin.frame.RoiManager` |
| ResultsTable | `ij.measure.ResultsTable` |
| ImageProcessor | `ij.process.ImageProcessor` |
| GenericDialog | `ij.gui.GenericDialog` |
| Duplicator | `ij.plugin.Duplicator` |
| ImageCalculator | `ij.plugin.ImageCalculator` |
| ChannelSplitter | `ij.plugin.ChannelSplitter` |
| ZProjector | `ij.plugin.ZProjector` |
| ParticleAnalyzer | `ij.plugin.filter.ParticleAnalyzer` |

### §14.2 Threading: IJ1 methods (IJ.run, IJ.open) handle EDT internally. Direct Swing manipulation needs EDT wrapping.

### §14.3 Memory: Close images in try/finally. Run `System.gc(); IJ.freeMemory()` every ~10 images in batch.

### §14.4 Language names: `groovy`, `jython`, `javascript` (not `python`, `py`, `js`).

---

## §15 Import Quick Reference

### §15.1 Groovy

```groovy
// Core
import ij.IJ; import ij.ImagePlus; import ij.ImageStack; import ij.WindowManager
// Processors
import ij.process.ImageProcessor; import ij.process.FloatProcessor; import ij.process.ByteProcessor
// GUI
import ij.gui.Roi; import ij.gui.OvalRoi; import ij.gui.Line; import ij.gui.Arrow
import ij.gui.TextRoi; import ij.gui.Overlay; import ij.gui.GenericDialog; import ij.gui.Plot
// Measurement
import ij.measure.ResultsTable; import ij.measure.Measurements; import ij.measure.Calibration
// Plugins
import ij.plugin.Duplicator; import ij.plugin.ImageCalculator; import ij.plugin.ChannelSplitter
import ij.plugin.RGBStackMerge; import ij.plugin.ZProjector
import ij.plugin.frame.RoiManager
import ij.plugin.filter.ParticleAnalyzer; import ij.plugin.filter.BackgroundSubtracter
// I/O
import ij.io.FileSaver
// Swing
import javax.swing.*; import java.awt.Window; import java.awt.Frame; import java.awt.Container
// Bio-Formats
import loci.plugins.BF; import loci.plugins.in.ImporterOptions
```

### §15.2 Jython

```python
from ij import IJ, ImagePlus, ImageStack, WindowManager
from ij.process import ImageProcessor, FloatProcessor, ByteProcessor
from ij.gui import Roi, OvalRoi, TextRoi, Overlay, GenericDialog
from ij.measure import ResultsTable, Measurements, Calibration
from ij.plugin import Duplicator, ImageCalculator, ChannelSplitter, RGBStackMerge, ZProjector
from ij.plugin.frame import RoiManager
from ij.plugin.filter import ParticleAnalyzer
from ij.io import FileSaver
from java.awt import Color, Font
from jarray import zeros, array
from java.lang import String
import os, sys
```

---

## §16 Agent Task Recipes

### §16.1 Get image info as JSON

```bash
python ij.py script '
import ij.IJ; import com.google.gson.Gson
def imp = IJ.getImage(); def cal = imp.getCalibration()
new Gson().toJson([title: imp.getTitle(), width: imp.getWidth(), height: imp.getHeight(),
    bitDepth: imp.getBitDepth(), channels: imp.getNChannels(), slices: imp.getNSlices(),
    frames: imp.getNFrames(), calibrated: cal.scaled(), unit: cal.getUnit()])
'
```

### §16.2 Toggle checkbox in named window

```bash
python ij.py script '
import javax.swing.JCheckBox; import java.awt.*
def walk(Container c) {
    c.getComponents().each { comp ->
        if (comp instanceof JCheckBox && comp.getText()?.contains("REPLACE_TEXT")) {
            if (comp.isSelected() != true) comp.doClick()
        }
        if (comp instanceof Container) walk(comp)
    }
}
def win = Window.getWindows().find { w ->
    w.isVisible() && w instanceof Frame && ((Frame)w).getTitle().contains("REPLACE_TITLE")
}
if (win) walk(win) else println "Window not found"
'
```

### §16.3 Safe close all images

```bash
python ij.py script '
import ij.WindowManager
def ids = WindowManager.getIDList()
if (ids != null) { ids.each { WindowManager.getImage(it).with { changes = false; close() } }; println "Closed ${ids.length}" }
'
```

---

## §17 Performance Tips

| Slow | Fast | Why |
|------|------|-----|
| `ip.getPixelValue(x,y)` in loop | `ip.getPixels()` then iterate array | Avoids per-pixel method overhead |
| `IJ.run(imp, "Add...", "value=50")` | `ip.add(50)` | Avoids string parsing and dialog creation |
| `imp.updateAndDraw()` per slice | `imp.updateAndDraw()` once at end | Avoids repeated display refresh |
| `stack.getProcessor(s)` per pixel | `stack.getPixels(s)` for bulk | Returns direct array reference, no copy |
