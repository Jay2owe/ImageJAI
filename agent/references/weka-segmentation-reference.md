# Trainable Weka Segmentation (TWS) Reference

Pixel classification using machine learning in Fiji/ImageJ. Interactive training,
macro/scripting automation, batch processing, probability maps, feature selection.

Sources: [TWS wiki](https://imagej.net/plugins/tws/),
[TWS scripting](https://imagej.net/plugins/tws/scripting),
[WekaSegmentation Javadoc](https://javadoc.scijava.org/Fiji/trainableSegmentation/WekaSegmentation.html)

Citation: Arganda-Carreras et al. (2017) doi:10.1093/bioinformatics/btx180

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code calling TWS via `call(...)`.
`python ij.py script '<code>'` — run Groovy against the `WekaSegmentation` Java API.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "How do I call TWS from a macro?" | §6 macro `call()` interface |
| "How do I script TWS from Groovy?" | §7 Groovy scripting API |
| "Which features should I enable for my image?" | §5.1 feature selection by image type |
| "What does each of the 20 features detect?" | §4 features table |
| "How do I train from binary / multi-class label images?" | §7 (Train from Binary / Multi-Class) |
| "How do I batch-process a folder?" | §7 (Batch Processing) |
| "What is a probability map and how do I threshold one?" | §8 |
| "Which classifier should I pick?" | §9.1 classifier selection |
| "What sigma range for my structure size?" | §9.2 sigma range |
| "How do I use TWS in 3D?" | §9.3 |
| "How do I extract a binary mask and measure objects?" | §10 post-processing |
| "How do I chain TWS into StarDist / Cellpose / 3D Objects Counter?" | §11 integration |
| "TWS vs StarDist / Cellpose / Labkit / Threshold?" | §3 when to use, §12 comparison |
| "How do I estimate / reduce memory usage?" | §13 memory management |
| "My macro/script failed — why?" | §14 common problems |
| "What's the full `WekaSegmentation` Java API?" | §15 quick reference |
| "What do .model / .arff / feature stack files contain?" | §16 file formats |
| "What do I save for reproducibility?" | §17 checklist |
| "Quick tips before / during / batch training?" | §18 agent tips |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '`<term>`' weka-segmentation-reference.md` to jump.

### A

`addBinaryData` §7, §15 · `addClass` §7, §15 · `addExample` §7, §15 ·
`addLabeledData` §15 · `addRandomBalancedBinaryData` §7, §13, §15 ·
`addRandomBalancedLabeledData` §7, §15 · `addTrace` §6 ·
`Anisotropic_diffusion` §4, §5.1 · `applyClassifier` §2, §7, §15 ·
`ARFF` §2, §16 · `Attribute Selection (selectAttributes)` §5.2, §7, §14, §15

### B

`Batch Processing` §7 · `Bilateral` §4, §5.1 · `Binary label images` §7 ·
`boolean[] features` (setEnabledFeatures) §7, §15

### C

`call()` syntax §6 · `changeClassName` §6 · `Class balancing (setDoClassBalance /
setClassBalance)` §6, §7, §14, §15 · `Classes (addClass / setClassLabel /
getClassLabel / getNumOfClasses)` §7, §15 · `Classification map` §2 ·
`Classifier selection` §9.1 · `Classifier configuration` §7 ·
`Confusion matrix (getTestConfusionMatrix)` §7, §15 ·
`Constructors (WekaSegmentation)` §15 · `Custom features (FeatureStack /
FeatureStackArray)` §7

### D

`DAB-stained IHC` §5.1 · `deleteTrace` §6 · `Derivatives` §4, §5.1, §14 ·
`Difference_of_gaussians (DoG)` §4, §5.1 · `doClassBalance` §17 ·
`Downscale` §13

### E

`EM ultrastructure` §5.1 · `Entropy` §4, §5.1 · `Evaluate classifier` §7 ·
`Evaluation (getTrainingError / getTestError / getTestConfusionMatrix)` §7, §15

### F

`FastRandomForest` §7, §9.1 · `Feature counts` §4 · `Feature name strings`
§6 · `Feature selection by image type` §5.1 · `Feature selection strategy`
§5.2 · `FeatureStack / FeatureStackArray` §7 · `Fiber/neurite tracing` §5.1 ·
`File formats` §16 · `Fluorescent nuclei / puncta` §5.1

### G

`Gabor` §4, §5.1, §13, §14 · `Gaussian_blur` §4, §5.1 · `getClassifier` §15 ·
`getClassifiedImage` §15 · `getClassLabel` §7, §15 · `getEnabledFeatures` §15, §17 ·
`getMaximumSigma / getMinimumSigma` §17 · `getNumOfClasses` §7, §10, §15 ·
`getProbability` §6 · `getResult` §2, §6 · `getTestConfusionMatrix` §7, §15 ·
`getTestError` §7, §15 · `getTrainingError` §7, §15

### H

`H&E histology` §5.1 · `Headless batch` §2 · `Hessian` §4, §5.1, §13

### I

`ImageScience update site` §4, §9.3, §14 · `Instance segmentation (limitation)`
§3, §12 · `Integration with StarDist / Cellpose / 3D Objects Counter` §11 ·
`I/O (saveClassifier / loadClassifier / saveData / loadTrainingData)` §7, §15, §16

### J

`J48` §9.1

### K

`Kuwahara` §4

### L

`Labkit` §3, §12 · `Laplacian` §4, §5.1, §14 · `launchWeka` §6 ·
`Lipschitz` §4, §5.1 · `loadClassifier` §2, §6, §7, §15, §17 ·
`loadData` §6 · `loadTrainingData` §15 · `Low-SNR fluorescence` §5.1

### M

`Macro call() reference` §6 · `Maximum` §4, §5.1 · `Mean` §4, §5.1 ·
`Median` §4, §5.1 · `Membrane_projections` §4, §5.1 · `Memory estimation`
§13 · `Memory reduction strategies` §13 · `Memory usage per image size`
§13 · `Minimum` §4, §5.1 · `Multi-class label images` §7 · `Multi-class
object extraction` §10

### N

`Naive Bayes` §9.1 · `Neighbors` §4, §5.2, §13, §14

### O

`Opacity (setOpacity)` §6 · `OOB error (getTrainingError)` §7, §15, §17 ·
`Outputs of TWS` §2

### P

`Pipeline overview` §2 · `Phase contrast` §5.1 · `plotResultGraphs` §6 ·
`Post-processing (extract mask, measure)` §10 · `Precision / recall / F1` §7 ·
`Probability maps` §2, §6, §8, §14 · `Probe TWS window` §14

### Q

`Quick Start` §2

### R

`Random Forest (FastRandomForest)` §7, §9.1 · `Reproducibility checklist` §17 ·
`ROC / evaluation plots (plotResultGraphs)` §6 · `Roi.addExample` §7

### S

`saveClassifier` §2, §6, §7, §15, §17 · `saveData` §6, §15 · `saveFeatureStack`
§6 · `selectAttributes` §5.2, §7, §14, §15 · `setClassBalance` §6 ·
`setClassifier` §6, §7, §15 · `setClassLabel` §7, §15 · `setDoClassBalance`
§7, §15 · `setEnabledFeatures` §7, §15 · `setFeature` §6 · `setFeatureStackArray`
§7 · `setMaximumSigma` §6, §7, §15 · `setMembranePatchSize` §6, §7, §15 ·
`setMembraneThickness` §6, §7, §15 · `setMinimumSigma` §6, §7, §15 ·
`setNumFeatures` §7 · `setNumTrees` §7 · `setMaxDepth` §7, §14 · `setOpacity`
§6 · `setSeed` §7 · `Sigma range` §9.2 · `SMO (SVM)` §9.1 · `Sobel_filter`
§4, §5.1 · `StarDist (integration / comparison)` §3, §11, §12 ·
`Structure` §4, §5.1, §14

### T

`Tile-based processing (tilesPerDim)` §7, §13, §14, §15 · `Tissue
classification` §5.1 · `toggleOverlay` §6 · `trainClassifier` §6, §7, §15 ·
`Training data (addExample / addBinaryData / addLabeledData)` §7, §15 ·
`Training from binary labels` §7 · `Training from multi-class labels` §7 ·
`Training time` §13 · `Tile boundary artifacts` §14 · `TWS vs alternatives`
§12

### U

`updateClassifier` §15 · `useAllFeatures` §15 · `Utils.getGoldenAngleLUT` §7

### V

`Variance` §4, §5.1

### W

`Weka Explorer (launchWeka)` §6 · `WekaSegmentation (class)` §2, §7, §15 ·
`When to use TWS` §3 · `White/black names (addBinaryData)` §7, §15

### X / Y / Z

`3D mode (Trainable Weka Segmentation 3D)` §9.3 · `3D Objects Counter
(integration)` §11

---

## §2. Quick Start

```bash
# Open image, launch TWS, load classifier, get result
python ij.py macro '
  open("/path/to/image.tif");
  run("Trainable Weka Segmentation");
  wait(3000);
  call("trainableSegmentation.Weka_Segmentation.loadClassifier", "/path/to/classifier.model");
  call("trainableSegmentation.Weka_Segmentation.getResult");
'
python ij.py capture tws_result

# Or headless via Groovy (preferred for batch)
python ij.py script '
import trainableSegmentation.WekaSegmentation
import ij.IJ
def image = IJ.openImage("/path/to/image.tif")
def seg = new WekaSegmentation()
seg.loadClassifier("/path/to/classifier.model")
def result = seg.applyClassifier(image, 0, false)  // false=labels, true=probabilities
result.show()
'
```

---

## §3. When to Use TWS

| Situation | Recommended Tool |
|-----------|-----------------|
| Simple intensity separation (bright vs dark) | Threshold (Otsu, Triangle, etc.) |
| Round nuclei (DAPI, Hoechst) | StarDist |
| Irregular cells with visible boundaries | Cellpose |
| Complex textures, custom structures, multiple tissue types | **TWS** |
| GPU-accelerated pixel classification | Labkit |
| Regions defined by appearance, not just intensity | **TWS** |

**TWS excels at:** tissue type classification, texture-defined structures, H&E/DAB histology,
membrane detection in EM, multi-class pixel classification with fine feature control.

**TWS is not ideal for:** instance segmentation (use StarDist/Cellpose), very large images
without tiling, real-time processing, simple intensity-based tasks.

### Pipeline Overview

```
Input Image → Feature Extraction (per pixel, multiple scales)
→ Feature Vector [f1, f2, ..., fN] → ML Classifier (Random Forest default)
→ Per-pixel class prediction → Classification Map or Probability Maps
```

### TWS Outputs

| Output | Type | Description |
|--------|------|-------------|
| Classification | 8-bit color | Each pixel assigned to a class (0, 1, 2...) |
| Probability maps | 32-bit hyperstack | Per-pixel probability for each class (0.0-1.0) |
| ARFF data | Text file | Feature vectors + labels for Weka analysis |
| Classifier | .model file | Serialized Weka classifier for reuse |

---

## §4. The 20 Training Features

Features are extracted at multiple scales (sigma values). Default sigma range: 1-16 pixels
(powers of 2: 1, 2, 4, 8, 16 = 5 scales).

| Idx | Name (setFeature string) | What It Detects | Count | Notes |
|-----|--------------------------|-----------------|-------|-------|
| 0 | `Gaussian_blur` | Multi-scale intensity | 5 | Fundamental baseline; almost always enable |
| 1 | `Sobel_filter` | Edges/boundaries | 5 | Enable when boundaries matter |
| 2 | `Hessian` | Ridges, blobs, orientation | 40 | 8 outputs/scale (eigenvalues, trace, det, etc.) |
| 3 | `Difference_of_gaussians` | Size-specific blobs | 10 | Band-pass; n(n-1)/2 pairs |
| 4 | `Membrane_projections` | Elongated membrane-like structures | 6 | 30 directional kernels; config via thickness/patchSize |
| 5 | `Variance` | Texture roughness | 5 | Excellent for tissue type classification |
| 6 | `Mean` | Local average intensity | 5 | Complementary to Gaussian |
| 7 | `Minimum` | Dark structures (erosion) | 5 | Absorption staining, dark holes |
| 8 | `Maximum` | Bright structures (dilation) | 5 | Fluorescent puncta, bright inclusions |
| 9 | `Median` | Edge-preserved smoothing | 5 | Good for salt-and-pepper noise; slower |
| 10 | `Anisotropic_diffusion` | Edge-preserved texture | 2 | Perona-Malik; good for EM, phase contrast |
| 11 | `Bilateral` | Boundary-preserved smoothing | 4 | 2 spatial x 2 range combinations |
| 12 | `Lipschitz` | Background vs foreground | 5 | 5 slope values; uneven illumination correction |
| 13 | `Kuwahara` | Edge-preserved local average | ~5 | Emphasises structural boundaries |
| 14 | `Gabor` | Oriented texture, periodic patterns | 22 | Fibers, collagen, muscle; computationally expensive |
| 15 | `Derivatives`* | Fine structural details | ~20 | High-order partial derivatives via FeatureJ |
| 16 | `Laplacian`* | Blobs and edges | 5 | Complementary to Hessian |
| 17 | `Structure`* | Local orientation, coherency | ~15 | Fiber orientation, flow patterns |
| 18 | `Entropy` | Texture complexity | ~5 | "Busy" vs "smooth" regions |
| 19 | `Neighbors` | Spatial context (8 directions) | 40 | Powerful but adds many features |

*Requires ImageScience update site. Silently skipped if unavailable.

### Feature Counts

```
Default (Gaussian+Sobel+Hessian+DoG+Membrane): ~66 features
All features enabled:                           ~200-220 features
```

---

## §5. Feature Selection Guide

### §5.1 By Image Type

| Image Type | Recommended Features |
|------------|---------------------|
| Fluorescent nuclei | Gaussian, Hessian, DoG |
| Membrane staining | Membrane, Structure, Gabor, Sobel |
| H&E histology | Gaussian, Hessian, Variance, Entropy, Mean |
| DAB-stained IHC | Gaussian, Hessian, DoG, Lipschitz |
| EM ultrastructure | Most features (skip Derivatives/Laplacian/Structure if no ImageScience) |
| Phase contrast | Gaussian, Hessian, Anisotropic, Bilateral |
| Fluorescent puncta | Gaussian, DoG, Hessian, Min, Max |
| Tissue classification | Gaussian, Variance, Entropy, Gabor, Mean |
| Fiber/neurite tracing | Hessian, Gabor, Structure, Sobel |
| Low-SNR fluorescence | Gaussian, Median, Bilateral, Variance |

### §5.2 Selection Strategy

1. Start with defaults (Gaussian + Sobel + Hessian + DoG + Membrane)
2. If results are poor, add Variance + Entropy (texture-sensitive)
3. For oriented structures, add Gabor + Structure tensor
4. For edge-heavy images, add Anisotropic diffusion + Bilateral
5. Avoid Neighbors unless spatial context is critical (adds 40 features)
6. Use `selectAttributes()` to automatically prune irrelevant features
7. Diminishing returns typically above ~80-100 features

---

## §6. Macro Interface (call() Syntax)

TWS must be open via `run("Trainable Weka Segmentation")` before calling these.
All parameters are strings. Full class path: `trainableSegmentation.Weka_Segmentation`.

### Complete call() Reference

| Method | Parameters | Description |
|--------|-----------|-------------|
| `addTrace` | classIndex, sliceNumber | Add current ROI as training sample |
| `deleteTrace` | classIndex, sliceNumber, traceIndex | Remove a training trace |
| `trainClassifier` | (none) | Train the classifier |
| `getResult` | (none) | Create classification image |
| `getProbability` | (none) | Create probability maps |
| `toggleOverlay` | (none) | Toggle classification overlay |
| `plotResultGraphs` | (none) | Show ROC/evaluation plots |
| `loadClassifier` | path | Load .model file |
| `saveClassifier` | path | Save .model file |
| `loadData` | path | Load ARFF training data |
| `saveData` | path | Save ARFF training data |
| `saveFeatureStack` | dir, filename | Save computed features |
| `applyClassifier` | dir, file, show, store, probs, outDir | Apply to file in directory |
| `createNewClass` | name | Add a new class |
| `changeClassName` | index, name | Rename a class |
| `setFeature` | "Name=true/false" | Enable/disable feature |
| `setMinimumSigma` | value | Set min sigma |
| `setMaximumSigma` | value | Set max sigma |
| `setMembraneThickness` | value | Set membrane width |
| `setMembranePatchSize` | value | Set membrane FOV |
| `setClassifier` | className, options | Set Weka classifier |
| `setClassBalance` | "true"/"false" | Enable class balancing |
| `setOpacity` | value (0-100) | Set overlay opacity |
| `launchWeka` | (none) | Open Weka Explorer |

### Feature name strings for setFeature()

```
Gaussian_blur, Sobel_filter, Hessian, Difference_of_gaussians,
Membrane_projections, Variance, Mean, Minimum, Maximum, Median,
Anisotropic_diffusion, Bilateral, Lipschitz, Kuwahara, Gabor,
Derivatives, Laplacian, Structure, Entropy, Neighbors
```

### Complete Macro Example: Configure + Train + Save

```bash
python ij.py macro '
  open("/path/to/image.tif");
  run("Trainable Weka Segmentation");
  wait(3000);

  // Configure features
  call("trainableSegmentation.Weka_Segmentation.setFeature", "Variance=true");
  call("trainableSegmentation.Weka_Segmentation.setFeature", "Entropy=true");
  call("trainableSegmentation.Weka_Segmentation.setFeature", "Membrane_projections=false");
  call("trainableSegmentation.Weka_Segmentation.setMaximumSigma", "8.0");

  // Add training samples (ROI must exist first)
  makeRectangle(10, 10, 100, 100);
  call("trainableSegmentation.Weka_Segmentation.addTrace", "0", "1");
  makeOval(200, 150, 40, 40);
  call("trainableSegmentation.Weka_Segmentation.addTrace", "1", "1");

  // Train and save
  call("trainableSegmentation.Weka_Segmentation.trainClassifier");
  call("trainableSegmentation.Weka_Segmentation.saveClassifier", "/path/to/classifier.model");
  call("trainableSegmentation.Weka_Segmentation.getResult");
'
```

---

## §7. Scripting API (Groovy)

Direct access to `WekaSegmentation` Java class, bypassing the GUI. More powerful
and flexible than macro `call()`. Run via `python ij.py script`.

### Train from ROIs

```groovy
import trainableSegmentation.WekaSegmentation
import ij.IJ
import ij.gui.Roi

def image = IJ.openImage("/path/to/image.tif")
def seg = new WekaSegmentation(image)

// addExample(classIndex, roi, sliceNumber)
seg.addExample(0, new Roi(10, 10, 80, 80), 1)      // background
seg.addExample(1, new Roi(100, 80, 40, 40), 1)     // foreground

if (!seg.trainClassifier()) throw new RuntimeException("Training failed")

def result = seg.applyClassifier(image, 0, false)   // labels
def probs = seg.applyClassifier(image, 0, true)     // probability maps
result.show()
seg.saveClassifier("/path/to/classifier.model")
```

### Configure Features Programmatically

```groovy
// 20-element boolean array in order:
// Gaussian, Sobel, Hessian, DoG, Membrane, Variance, Mean,
// Minimum, Maximum, Median, AnisDiff, Bilateral, Lipschitz,
// Kuwahara, Gabor, Derivatives, Laplacian, Structure, Entropy, Neighbors
def features = new boolean[]{
    true, true, true, true, false,    // Gaussian, Sobel, Hessian, DoG, -
    true, false, false, false, false, // Variance, -, -, -, -
    false, false, false, false, true, // -, -, -, -, Gabor
    false, false, false, true, false  // -, -, -, Entropy, -
}
seg.setEnabledFeatures(features)
seg.setMinimumSigma(1.0f)
seg.setMaximumSigma(16.0f)
seg.setMembraneThickness(1)    // only if Membrane enabled
seg.setMembranePatchSize(19)
```

### Configure Classifier

```groovy
import hr.irb.fastRandomForest.FastRandomForest

def rf = new FastRandomForest()
rf.setNumTrees(200)       // starting point 200; consider 100 (fast) to 500 (marginal gains)
rf.setNumFeatures(0)      // 0 = auto (sqrt of total); consider 2-10 for manual control
rf.setMaxDepth(0)         // 0 = unlimited; consider 20 to reduce overfitting
rf.setSeed(42)
seg.setClassifier(rf)
seg.setDoClassBalance(true)   // enable when classes are imbalanced
```

### Train from Binary Label Images

```groovy
def image = IJ.openImage("/path/to/training_image.tif")
def labels = IJ.openImage("/path/to/binary_labels.tif")
def seg = new WekaSegmentation(image)

// White pixels = foreground class, black = background
seg.addBinaryData(image, labels, "foreground", "background")
// Or memory-efficient random balanced sampling:
// seg.addRandomBalancedBinaryData(image, labels, "foreground", "background", 2000)

seg.trainClassifier()
seg.saveClassifier("/path/to/classifier.model")
```

### Train from Multi-Class Label Images

```groovy
def seg = new WekaSegmentation(image)
seg.addClass()  // now 3 classes
seg.addClass()  // now 4 classes
seg.setClassLabel(0, "background")
seg.setClassLabel(1, "neurons")
seg.setClassLabel(2, "glia")
seg.setClassLabel(3, "vessels")

def classNames = ["background", "neurons", "glia", "vessels"] as String[]
seg.addRandomBalancedLabeledData(image, labels, classNames, 1000)
seg.trainClassifier()
```

### Batch Processing (Folder)

```groovy
import trainableSegmentation.WekaSegmentation
import trainableSegmentation.utils.Utils
import ij.io.FileSaver
import ij.IJ

def inputDir = new File("/path/to/input/")
def outputDir = new File("/path/to/output/")
if (!outputDir.exists()) outputDir.mkdirs()

def seg = new WekaSegmentation()
seg.loadClassifier("/path/to/classifier.model")

def files = inputDir.listFiles().findAll {
    it.isFile() && it.name.toLowerCase() =~ /\.(tif|tiff|png|jpg)$/
}

files.eachWithIndex { f, i ->
    IJ.log("[${i+1}/${files.size()}] ${f.name}")
    def image = IJ.openImage(f.canonicalPath)
    if (image == null) return

    def result = seg.applyClassifier(image, 0, false)  // or true for probability maps
    result.setLut(Utils.getGoldenAngleLUT())

    def outName = f.name.replaceFirst(/\.[^.]+$/, "") + "_classified.tif"
    new FileSaver(result).saveAsTiff(outputDir.path + File.separator + outName)

    result.close()
    image.close()
    System.gc()
}
IJ.log("** Batch complete **")
```

### Tile-Based Processing (Large Images)

For images too large to fit in memory, pass a tiles array to `applyClassifier`:

```groovy
def seg = new WekaSegmentation(false)  // false = 2D
seg.loadClassifier(modelPath)

int[] tiles = image.getNSlices() > 1
    ? [xTiles, yTiles, zTiles] as int[]
    : [xTiles, yTiles] as int[]

def result = seg.applyClassifier(image, tiles, 0, false)
```

Choose tile count based on available memory: `Memory per tile ~ (W/xTiles) * (H/yTiles) * NumFeatures * 4 bytes`.

### Evaluate Classifier

```groovy
def error = seg.getTrainingError(true)  // OOB error for Random Forest
def testError = seg.getTestError(image, labels, 1, 0, true)

// Confusion matrix: int[2][2] = [[TN, FP], [FN, TP]]
def matrix = seg.getTestConfusionMatrix(image, labels, 1, 0)
def precision = matrix[1][1] / (double)(matrix[1][1] + matrix[0][1])
def recall = matrix[1][1] / (double)(matrix[1][1] + matrix[1][0])
def f1 = 2 * precision * recall / (precision + recall)
```

### Attribute Selection (Feature Importance)

```groovy
// After training, automatically find the most informative feature subset
seg.selectAttributes()   // uses BestFirst search
seg.trainClassifier()    // re-train with reduced set
```

### Custom Features (Bypass Default Feature Stack)

```groovy
import trainableSegmentation.FeatureStack
import trainableSegmentation.FeatureStackArray

def featuresArray = new FeatureStackArray(image.getStackSize())
for (int slice = 1; slice <= image.getStackSize(); slice++) {
    def stack = new ImageStack(image.getWidth(), image.getHeight())
    stack.addSlice("custom1", customImage1.getStack().getProcessor(slice))
    stack.addSlice("custom2", customImage2.getStack().getProcessor(slice))
    def features = new FeatureStack(stack.getWidth(), stack.getHeight(), false)
    features.setStack(stack)
    featuresArray.set(features, slice - 1)
    featuresArray.setEnabledFeatures(features.getEnabledFeatures())
}
seg.setFeatureStackArray(featuresArray)
// Then train and apply as normal; for apply: seg.applyClassifier(image, featuresArray, 0, false)
```

---

## §8. Probability Maps

Probability maps (32-bit hyperstack, one channel per class) show per-pixel confidence
(0.0-1.0). More informative than label images for downstream analysis.

```bash
# Extract class 1 probability channel and threshold at chosen confidence level
python ij.py macro '
  selectWindow("Probability maps");
  run("Duplicate...", "title=class1_prob duplicate channels=2");  // channel 2 = class 1
  setThreshold(0.7, 1.0);  // adjust threshold based on desired stringency
  run("Convert to Mask");
  rename("high_confidence_mask");
'
```

Higher threshold = fewer false positives but more false negatives. Consider
starting at 0.5 (balanced) and adjusting based on visual inspection.

---

## §9. Advanced Configuration

### §9.1 Classifier Selection

| Classifier | Weka Class | When to Consider |
|-----------|------------|-----------------|
| FastRandomForest (default) | `hr.irb.fastRandomForest.FastRandomForest` | Default choice for nearly all tasks |
| Naive Bayes | `weka.classifiers.bayes.NaiveBayes` | Quick testing, simple separations |
| SMO (SVM) | `weka.classifiers.functions.SMO` | Small datasets, high accuracy needed |
| J48 (C4.5 tree) | `weka.classifiers.trees.J48` | When you need interpretable decisions |

### §9.2 Sigma Range

Sigma values are powers of 2 from min to max. Guidelines for choosing:

| Structure Size | Suggested Sigma Range |
|---------------|----------------------|
| Small (nuclei ~10px) | 1-8 |
| Medium (cells ~50px) | 1-16 (default) |
| Large (tissue regions) | 2-32 or 4-64 |
| 3D stacks | Consider max 8 (3D convolutions are expensive) |

### §9.3 3D Mode

```bash
python ij.py macro 'run("Trainable Weka Segmentation 3D");'
```

**Available in 3D:** Gaussian, Hessian, Derivatives, Laplacian, Structure, DoG,
Mean, Variance, Median, Minimum, Maximum.

**Not available in 3D:** Membrane, Gabor, Anisotropic diffusion, Bilateral,
Lipschitz, Kuwahara, Entropy, Neighbors.

---

## §10. Post-Processing

### Extract Binary Mask and Measure

```bash
python ij.py macro '
  selectWindow("Classified image");
  run("Duplicate...", "title=mask duplicate");
  setThreshold(1, 1);  // class index to extract
  run("Convert to Mask");
  run("Fill Holes");
  run("Watershed");
  run("Set Measurements...", "area mean standard min max integrated redirect=[original.tif] decimal=3");
  run("Analyze Particles...", "size=50-Infinity circularity=0.3-1.0 show=[Overlay Masks] display summarize add");
'
python ij.py results
```

### Multi-Class Object Extraction (Groovy)

```groovy
def classified = seg.applyClassifier(image, 0, false)
for (int c = 0; c < seg.getNumOfClasses(); c++) {
    def mask = new Duplicator().run(classified)
    def ip = mask.getProcessor()
    for (int i = 0; i < ip.getPixelCount(); i++) {
        ip.set(i, ip.get(i) == c ? 255 : 0)
    }
    mask.setTitle("mask_" + seg.getClassLabel(c))
    IJ.run(mask, "Analyze Particles...", "size=20-Infinity display summarize")
}
```

---

## §11. Integration with Other Tools

### TWS then StarDist/Cellpose

Use TWS for region classification, then instance segmentation within classified regions:

```bash
# 1. Classify → 2. Extract mask of target class → 3. Mask original → 4. Run StarDist
python ij.py macro '
  selectWindow("tissue_classes");
  run("Duplicate...", "title=region_mask");
  setThreshold(1, 1);
  run("Convert to Mask");
  imageCalculator("AND create", "original.tif", "region_mask");
  rename("masked_for_stardist");
  run("Command From Macro", "command=[de.csbdresden.stardist.StarDist2D], args=[...], process=[false]");
'
```

### TWS then 3D Objects Counter

```bash
python ij.py macro '
  selectWindow("classified_3d");
  run("Duplicate...", "title=mask_3d duplicate");
  setThreshold(1, 1);
  run("Convert to Mask", "background=Dark");
  run("3D Objects Counter", "threshold=128 min.=100 max.=9999999 objects statistics");
'
```

---

## §12. Comparison: TWS vs Alternatives

| Criterion | TWS | StarDist | Cellpose | Labkit | Threshold |
|-----------|-----|----------|----------|--------|-----------|
| Training effort | Medium (minutes) | High (hours, GPU) | High (hours, GPU) | Low (minutes) | None |
| Speed | Slow (sec-min) | Fast (sec) | Medium (sec) | Fast (GPU) | Instant |
| Multi-class | Yes (unlimited) | No | No | Yes | No |
| Texture-aware | Yes (core strength) | Limited | Limited | Yes | No |
| Instance segmentation | No (pixel-level) | Yes | Yes | No | No |
| GPU support | No | Optional | Yes | Yes | No |
| 3D support | Yes | Limited | Yes | Yes | Yes |
| Scripting API | Rich | Moderate | Moderate | Limited | Full |

**TWS vs Labkit:** Labkit is typically faster (GPU) with simpler setup. Choose TWS when
you need fine-grained feature control or extensive batch scripting via the WekaSegmentation API.

---

## §13. Memory Management

### Memory Estimation

```
Memory ~ Width x Height x NumFeatures x 4 bytes x NumSlices
Example: 2048x2048, 66 features (default) = ~1.1 GB features alone, ~2-3 GB total
```

### Reduction Strategies

| Strategy | How |
|----------|-----|
| Reduce max sigma | Fewer scales = fewer features |
| Disable unneeded features | Especially Neighbors (40), Hessian (40), Gabor (22) |
| Downscale before classification | `run("Scale...", "x=0.5 y=0.5 ...")` then upscale result |
| Tile-based processing | `applyClassifier(image, tilesPerDim, 0, false)` |
| Increase Fiji memory | Edit `ImageJ.cfg`: `-Xmx8g` |
| Random balanced sampling | `addRandomBalancedBinaryData(...)` instead of `addBinaryData` |
| Close unused images + GC | `result.close(); System.gc()` between images |

### Approximate Performance

| Image Size | Feature Extraction (default) | Memory (default) | Memory (all features) |
|-----------|------------------------------|-------------------|----------------------|
| 512x512 | ~2s | ~70 MB | ~220 MB |
| 1024x1024 | ~8s | ~270 MB | ~880 MB |
| 2048x2048 | ~30s | ~1.1 GB | ~3.5 GB |
| 4096x4096 | ~2min | ~4.3 GB | ~14 GB |

Training time depends on sample count, not image size (~3s for 1000 samples, ~15s for 10000).

---

## §14. Common Problems and Solutions

| Problem | Likely Cause | Solution |
|---------|-------------|----------|
| Out of memory (training) | Too many features/large image | Reduce max sigma, disable heavy features, use random balanced sampling |
| Out of memory (applying) | Large image | Use tile-based `applyClassifier`, close other images, `System.gc()` |
| Slow training | Too many features/samples | Disable Gabor/Neighbors/Derivatives, reduce samples, reduce trees |
| Poor accuracy (underfitting) | Too few traces or wrong features | Add more diverse traces, enable relevant features, enable class balancing |
| Poor accuracy (overfitting) | Too many features, non-representative training | Use `selectAttributes()`, train on multiple images, set `maxDepth(20)` |
| TWS window does not open | No image open or hidden dialog | Check `python ij.py dialogs`, `python ij.py windows` |
| Cannot load classifier | Version/feature mismatch | Check model was saved with compatible Weka version; 2D/3D must match |
| Tile boundary artifacts | Tiles too small | Use larger tiles (fewer divisions) or post-process with light blur |
| `call()` has no effect | TWS not open or wrong method name | Must `run("Trainable Weka Segmentation")` + `wait(3000)` first; all params are strings |
| Feature not working | ImageScience not installed | Derivatives, Laplacian, Structure require ImageScience update site |
| Probability maps wrong | Untrained classifier or severe class imbalance | Verify training succeeded; enable class balancing |

---

## §15. WekaSegmentation API Quick Reference

### Constructors

```java
WekaSegmentation()                          // empty (for loading classifiers)
WekaSegmentation(ImagePlus trainingImage)   // with training image
WekaSegmentation(boolean isProcessing3D)    // specify 2D/3D mode
```

### Training Data

```java
void addExample(int classIndex, Roi roi, int sliceNumber)
boolean addBinaryData(ImagePlus input, ImagePlus labels, String whiteName, String blackName)
boolean addRandomBalancedBinaryData(ImagePlus input, ImagePlus labels, String whiteName, String blackName, int numSamples)
boolean addLabeledData(ImagePlus input, ImagePlus labels, String[] classNames, int numSamples)
boolean addRandomBalancedLabeledData(ImagePlus input, ImagePlus labels, String[] classNames, int numSamples)
```

### Training and Classification

```java
boolean trainClassifier()
ImagePlus applyClassifier(ImagePlus image, int numThreads, boolean probabilityMaps)
ImagePlus applyClassifier(ImagePlus image, int[] tilesPerDim, int numThreads, boolean probabilityMaps)
ImagePlus getClassifiedImage()
```

### Features

```java
void setEnabledFeatures(boolean[] features)   // 20-element array
boolean[] getEnabledFeatures()
void setMinimumSigma(float sigma)
void setMaximumSigma(float sigma)
void setMembraneThickness(int thickness)
void setMembranePatchSize(int patchSize)
void useAllFeatures()
boolean selectAttributes()                     // auto feature selection
```

### Classifier

```java
void setClassifier(AbstractClassifier cls)
AbstractClassifier getClassifier()
boolean updateClassifier(int numTrees, int numRandomFeatures, int maxDepth)
void setDoClassBalance(boolean balance)
```

### Classes

```java
void addClass()
void setClassLabel(int index, String name)
String getClassLabel(int index)
int getNumOfClasses()
```

### I/O

```java
boolean saveClassifier(String filename)
boolean loadClassifier(String filename)
boolean saveData(String arffPath)
boolean loadTrainingData(String arffPath)
```

### Evaluation

```java
double getTrainingError(boolean verbose)
double getTestError(ImagePlus image, ImagePlus labels, int whiteIdx, int blackIdx, boolean verbose)
int[][] getTestConfusionMatrix(ImagePlus image, ImagePlus labels, int whiteIdx, int blackIdx)
```

---

## §16. File Formats

| Format | Extension | Contents | Portability |
|--------|-----------|----------|-------------|
| Classifier | .model | Serialized Weka classifier + feature config + class names | Same Fiji/Weka version; not across major Weka upgrades (3.7 to 3.9) |
| Training data | .arff | Feature vectors + labels (plain text) | Universal; can open in Weka Explorer |
| Feature stack | .tif | Multi-channel TIFF (one channel per feature) | Standard TIFF |

---

## §17. Reproducibility Checklist

Save: classifier (.model), training data (.arff), probability maps. Record: feature
settings, sigma range, classifier type/parameters, training images used, traces per
class, evaluation metrics (OOB error, confusion matrix), Fiji/Weka versions.

```groovy
// Export classifier report
def seg = new WekaSegmentation()
seg.loadClassifier("/path/to/classifier.model")
IJ.log("Classes: " + seg.getNumOfClasses())
IJ.log("Classifier: " + seg.getClassifier().getClass().getName())
IJ.log("Balance: " + seg.doClassBalance())
IJ.log("Sigma: " + seg.getMinimumSigma() + "-" + seg.getMaximumSigma())
def featureNames = ["Gaussian","Sobel","Hessian","DoG","Membrane","Variance","Mean",
    "Minimum","Maximum","Median","AnisDiff","Bilateral","Lipschitz","Kuwahara","Gabor",
    "Derivatives","Laplacian","Structure","Entropy","Neighbors"]
seg.getEnabledFeatures().eachWithIndex { on, i -> if (on) IJ.log("  + " + featureNames[i]) }
```

---

## §18. Agent Tips

- **Before TWS:** Check memory (`python ij.py state`), image dims (`python ij.py info`),
  estimate memory needs. For images larger than ~2000x2000, plan tile processing.
- **During training:** Start with minimal features, add more if needed. Capture and
  inspect after each training round. Save classifier frequently.
- **For batch:** Use Groovy scripts (not macro `call()`). Always `System.gc()` between
  images. Close images explicitly with `.close()`. Save QC metrics alongside results.
- **Training tips:** 5-10 traces per class minimum, covering diverse examples. Small
  precise traces are better than large sloppy ones. Add traces at class boundaries
  and where classification is wrong. Start with 2 classes unless clearly needed.
