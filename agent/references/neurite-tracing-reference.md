# Neurite Tracing & Neuronal Morphometry — Reference

Covers SNT (Neuroanatomy Toolbox), AnalyzeSkeleton, Sholl/Strahler analysis,
SWC files, vessel analysis, batch processing, and agent integration.

---

## 1. Quick Decision Tree

```
Is the neuron clearly separable from background?
├── YES: Single neuron or sparse? → SNT semi-auto (best accuracy)
│         Sparse field? → SNT auto-trace or Tubeness → AnalyzeSkeleton
├── NO:  2D? → NeuronJ (simple) or SNT
│        3D? → SNT with Frangi secondary layer
└── SPECIAL:
    ├── Vessels/tubes → Frangi → AnalyzeSkeleton or Angiogenesis Analyzer
    ├── Dense networks → AnalyzeSkeleton (topology only)
    └── Pre-traced SWC files → SNT scripting (TreeStatistics)
```

## 2. Method Comparison

| Method | Dims | Accuracy | Speed | Scripting | Best For |
|--------|------|----------|-------|-----------|----------|
| SNT semi-auto | 2D/3D | Highest | Slow | Groovy/Jython | Single neurons, publication-quality |
| SNT auto-trace | 2D/3D | High | Medium | Groovy/Jython | Clear neurons, batch |
| AnalyzeSkeleton | 2D/3D | Medium | Fast | Macro (`run()`) | Dense networks, topology |
| NeuronJ | 2D | High | Slow | None | Simple 2D length (legacy) |
| Tubeness + Skeleton | 2D/3D | Medium | Fast | Macro | Preprocessing pipeline |

| Scenario | Recommended |
|----------|-------------|
| Single neuron, high accuracy | SNT semi-automated |
| Batch SWC files | SNT scripting (TreeStatistics) |
| Dense network, topology only | AnalyzeSkeleton |
| Vessel network / angiogenesis | Frangi + AnalyzeSkeleton |
| Automated screening assay | Tubeness + AnalyzeSkeleton |
| Golgi-stained neurons | SNT with Frangi secondary layer |

---

## 3. Preprocessing

### Choosing Filters

| Image Type | Recommended Preprocessing |
|-----------|--------------------------|
| Confocal fluorescence, good SNR | Subtract Background only |
| Confocal, noisy | Gaussian Blur (sigma=0.5-1) + Subtract Background |
| Widefield fluorescence | Subtract Background (large radius) + Gaussian Blur |
| Golgi stain (brightfield) | Invert + Subtract Background (light) + Tubeness |
| Two-photon, deep tissue | Gaussian Blur 3D + Subtract Background + Tubeness |
| GFP-expressing neurons, sparse | Minimal or none |
| Dense network | Frangi multi-scale + aggressive threshold |

### Tubeness Filter

Computes Hessian-based "tubeness" score. **Sigma should approximate neurite radius in pixels.**

```javascript
// Single scale
run("Tubeness", "sigma=2");

// Multi-scale: combine max across scales for varied neurite thickness
run("Duplicate...", "title=tube_s1 duplicate"); run("Tubeness", "sigma=1");
selectWindow("original");
run("Duplicate...", "title=tube_s2 duplicate"); run("Tubeness", "sigma=2");
selectWindow("original");
run("Duplicate...", "title=tube_s3 duplicate"); run("Tubeness", "sigma=4");
imageCalculator("Max create stack", "tube_s1", "tube_s2");
rename("tube_max_12");
imageCalculator("Max create stack", "tube_max_12", "tube_s3");
rename("tubeness_multiscale");
```

### Frangi Vesselness (Groovy)

More sophisticated than Tubeness. SNT uses Frangi internally as secondary layer.

```groovy
import net.imagej.ops.OpService
import net.imglib2.img.display.imagej.ImageJFunctions
import ij.IJ
def imp = IJ.getImage()
def ops = new net.imagej.ImageJ().op()
def img = ImageJFunctions.wrap(imp)
// scales: radii to detect; spacing: pixel dimensions
def result = ops.filter().frangiVesselness(img, [1.0, 2.0, 4.0] as double[], [1.0, 1.0] as double[])
ImageJFunctions.show(result, "Frangi Vesselness")
```

### Standard Preprocessing Pipeline

```javascript
run("Subtract Background...", "rolling=50 stack");  // radius > largest neurite
run("Gaussian Blur...", "sigma=0.7 stack");          // sigma < smallest neurite radius
// Optional: Tubeness enhancement for SNT secondary layer or threshold input
run("Duplicate...", "title=tubeness duplicate");
run("Tubeness", "sigma=2");
```

**Choosing parameters:**
- **Rolling ball radius:** Larger than the largest structure to preserve. Typically 50-100.
- **Gaussian sigma:** Less than the smallest neurite radius to detect. Typically 0.5-1.0.
- **Tubeness sigma:** Match approximate neurite radius in pixels. Try multiple scales if varied.
- **Contrast:** NEVER use Enhance Contrast with normalize. Use `setMinAndMax()` for display only.

---

## 4. SNT (Neuroanatomy Toolbox)

**Critical: SNT cannot be driven by `run()` macros.** Use Groovy via `python ij.py script`.

### Key Packages and Classes

| Class | Purpose |
|-------|---------|
| `SNTService` | SciJava service: initialize SNT, access paths/trees |
| `Tree` | Collection of Paths representing a neuron; load/save SWC/TRACES |
| `Path` | Single traced path (ordered 3D nodes with radii) |
| `TreeAnalyzer` | Summary morphometric measurements on a Tree |
| `TreeStatistics` | Statistical analysis (histograms, distributions) on Tree metrics |
| `TreeParser` (Sholl) | Performs Sholl analysis on a Tree |
| `PathAndFillManager` | Manages paths during tracing sessions |
| `Viewer3D` | 3D reconstruction viewer |

**Trees vs Paths:** A Path is an ordered sequence of 3D nodes. A Tree is connected Paths with parent-child branching topology. TreeStatistics/TreeAnalyzer operate on Trees.

### Interactive Tracing (User Guide)

Launch: **Plugins > Neuroanatomy > SNT...**

| Action | Shortcut |
|--------|----------|
| Confirm path | Y |
| Cancel path | N |
| Toggle cursor auto-snapping | S |
| Select nearest path | G |
| Hide/show paths | H |
| Edit mode | Shift+E |
| Connect paths | C |
| Command palette | Ctrl+Shift+P |
| Save tracings | Ctrl+S |
| Start branch | Alt+Click on existing path |

**Tips:** Enable Tubeness/Frangi secondary layer for better A* pathfinding on noisy images. Use Refine/Fit after tracing for accurate radii. Save as .traces (rich metadata) or .swc (standard).

### Scripting API (Groovy)

#### Load and Inspect a Tree

```groovy
import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.TreeAnalyzer

def tree = Tree.fromFile("/path/to/neuron.swc")
def analyzer = new TreeAnalyzer(tree)

println("Cable length: " + analyzer.getCableLength() + " " + tree.getUnit())
println("Branches: " + analyzer.getNBranches())
println("Tips: " + analyzer.getTips().size())
println("Branch points: " + analyzer.getBranchPoints().size())
println("Strahler number: " + analyzer.getStrahlerNumber())
println("Avg branch length: " + analyzer.getAvgBranchLength())
println("Avg contraction: " + analyzer.getAvgContraction())
println("Width/Height/Depth: " + analyzer.getWidth() + "/" + analyzer.getHeight() + "/" + analyzer.getDepth())
println("Valid topology: " + analyzer.isValid())
```

#### TreeStatistics — Distribution Analysis

```groovy
import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.TreeStatistics

def tree = Tree.fromFile("/path/to/neuron.swc")
def stats = new TreeStatistics(tree)

// Get summary for any metric constant
def summary = stats.getSummaryStats(TreeStatistics.BRANCH_LENGTH)
println("N: " + summary.getN() + " Mean: " + summary.getMean() +
        " StdDev: " + summary.getStandardDeviation() +
        " Min: " + summary.getMin() + " Max: " + summary.getMax())

// Generate histogram
stats.getHistogram(TreeStatistics.BRANCH_LENGTH).show()
```

#### Filter by Neurite Type

```groovy
// Load by compartment (SWC type codes: 1=soma, 2=axon, 3=basal dendrite, 4=apical dendrite)
def axon = new Tree("/path/to/neuron.swc", "axon")       // or type code 2
def dendrite = new Tree("/path/to/neuron.swc", "dendrite") // or type code 3

// Or restrict analyzer
def analyzer = new TreeAnalyzer(tree)
analyzer.restrictToSWCType(2)  // axon only
println("Axon cable: " + analyzer.getCableLength())
analyzer.resetRestrictions()
```

#### Load Multiple Trees

```groovy
def trees = Tree.listFromDir("/path/to/swc_directory", "*.swc")
println("Loaded " + trees.size() + " trees")
```

### File Format Support

| Format | Ext | Read | Write | Notes |
|--------|-----|------|-------|-------|
| TRACES | .traces | Yes | Yes | SNT native; XML; stores metadata, tags, channels |
| SWC | .swc | Yes | Yes | Universal standard; plain text; one tree per file |
| NDF | .ndf | Yes | No | NeuronJ legacy format |
| JSON | .json | Yes | No | MouseLight format |

### Reconstruction Viewer (3D)

```groovy
import sc.fiji.snt.Tree
import sc.fiji.snt.viewer.Viewer3D
import sc.fiji.snt.analysis.TreeColorMapper

def tree = Tree.fromFile("/path/to/neuron.swc")
def viewer = new Viewer3D()
viewer.addTree(tree)
viewer.show()
viewer.setViewMode("side")  // "top", "front"

// Color-code by metric
def mapper = new TreeColorMapper()
viewer.colorCode(tree.getLabel(), TreeColorMapper.STRAHLER_NUMBER, mapper.getColorTable("Ice.lut"))
```

---

## 5. Sholl Analysis

Counts neurite intersections with concentric circles/spheres at increasing radii from soma.

### Parameters

| Parameter | Description | How to choose |
|-----------|-------------|---------------|
| **Step size** | Distance between shells | Typically 5-20 um; 1-2x avg inter-branch distance; keep identical across groups |
| **Start radius** | Smallest sampling radius | 0 or soma radius |
| **End radius** | Largest sampling distance | NaN = full extent |

### Key Metrics

| Metric | Description |
|--------|-------------|
| **Critical radius (rc)** | Distance at peak branching. Shifts outward with growth, inward with atrophy |
| **Critical value (Nm)** | Peak intersection count. Higher = more complex |
| **Enclosing radius** | Farthest extent of arbor |
| **Sholl decay (k)** | Rate of branching decrease with distance (semi-log regression slope) |
| **Ramification index** | Max intersections / primary branches |
| **Sum intersections** | Total across all radii; useful as single summary for group comparisons |

**Profile shape:** Bell-shaped (typical healthy neuron), right-skewed (distal branching), left-skewed (proximal), flat (sparse), multi-peaked (multipolar).

### From Traces (Groovy — Recommended)

```groovy
import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.sholl.TreeParser
import sc.fiji.snt.analysis.sholl.math.LinearProfileStats
import sc.fiji.snt.analysis.sholl.math.NormalizedProfileStats

def tree = Tree.fromFile("/path/to/neuron.swc")
def parser = new TreeParser(tree)
parser.setCenter(TreeParser.ROOT)
parser.setStepSize(10)  // choose based on arbor size and inter-branch distance
parser.parse()
def profile = parser.getProfile()

println("Max intersections: " + profile.max())
println("Sum intersections: " + profile.sum())
println("Enclosing radius: " + profile.enclosingRadius())

// Individual data points
def radii = profile.radii()
def counts = profile.counts()
for (int i = 0; i < radii.length; i++) {
    println("  r=" + radii[i] + "  N=" + counts[i])
}

// Polynomial fitting
def linearStats = new LinearProfileStats(profile)
linearStats.fitPolynomial()
println("Critical radius: " + linearStats.getCriticalRadius())
println("Critical value: " + linearStats.getCriticalValue())
println("Polynomial R2: " + linearStats.getPolynomialRSquared())
println("Ramification index: " + linearStats.getRamificationIndex())

// Sholl decay (semi-log regression)
def normStats = new NormalizedProfileStats(profile)
println("Sholl decay (k): " + normStats.getShollDecay())
println("Regression R2: " + normStats.getRegressionRSquared())
```

### From Image (Macro)

Requires binary/thresholded image with point ROI at soma.

```javascript
open("/path/to/neuron_binary.tif");
makePoint(256, 256);  // soma coordinates
run("Sholl Analysis (From Image)...",
    "datamodechoice=Intersections " +
    "startradius=10 " +
    "stepsize=10 " +
    "endradius=400 " +
    "polynomialchoice=[Best fitting degree] " +
    "normalizerchoice=[Default (Area/Volume)] " +
    "save=false");
```

### Comparing Groups

Sholl data is repeated-measures (multiple radii per neuron). Appropriate approaches:
1. **Single summary metrics:** Compare rc, max intersections, AUC between groups (t-test/ANOVA)
2. **Full profile:** Two-way RM-ANOVA (group x radius) or mixed-effects model
3. **Specific radii:** Compare at individual radii with multiple comparisons correction

```groovy
// Export for external statistics
def csv = new StringBuilder("radius,intersections\n")
def radii = profile.radii()
def counts = profile.counts()
for (int i = 0; i < radii.length; i++) {
    csv.append(radii[i] + "," + counts[i] + "\n")
}
new File("/path/to/sholl_profile.csv").text = csv.toString()
```

---

## 6. Strahler Analysis

Classifies branches by hierarchical order: terminal branches = order 1; when two order-i children merge, parent = order i+1.

### Metrics

| Metric | Description |
|--------|-------------|
| **Strahler number** | Root order; overall complexity (simple: 2-3, moderate: 4-6, complex: 7+) |
| **Bifurcation ratio** | Branch count ratio between consecutive orders (typically 3-5) |
| **Branch length per order** | Terminal branches typically shortest |

### From Traces (Groovy)

```groovy
import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.TreeAnalyzer

def tree = Tree.fromFile("/path/to/neuron.swc")
def analyzer = new TreeAnalyzer(tree)
println("Strahler number: " + analyzer.getStrahlerNumber())
println("Bifurcation ratio: " + analyzer.getStrahlerBifurcationRatio())
```

### From Images (Macro)

Uses progressive pruning of skeletonized image. Place ROI around soma to protect root.

```javascript
open("/path/to/neuron_binary.tif");
makeRectangle(240, 240, 30, 30);  // around soma
run("Strahler Analysis (Image)...");
```

**Path Order vs Strahler Order:** Strahler is bottom-up (tips=1, root=highest). Path Order is top-down (root=1, tips=highest). Strahler requires connected tree; Path Order works on disconnected paths.

---

## 7. Morphometric Measurements

### Complete SNT Metrics

**Length:** Cable length, branch/path/primary/terminal/inner length, longest shortest path, internode distance

**Counts:** Branches, branch points, tips, paths, nodes, primary/terminal/inner branches, spines/varicosities

**Shape:** Contraction (Euclidean/path ratio, 0-1), fractal dimension (1-2), partition asymmetry (0-1), remote bifurcation angles, internode angles

**Radius:** Node radius, branch/path mean radius, branch surface area, branch volume (frustum model)

**Spatial:** Width, height, depth, X/Y/Z coordinates, node intensity values

**Convex Hull:** Size (area/volume), boundary size, boxivity, compactness, eccentricity, elongation, roundness, centroid-root distance

**Angles:** Extension angle (absolute/relative), azimuth, elevation, principal axes (PCA)

**Complexity:** ACI (Axonal Complexity Index), DCI (Dendritic Complexity Index)

### Key Formulas

- **Contraction** = Euclidean_distance / Path_length (0=tortuous, 1=straight)
- **Fractal dimension:** 1.0=straight, 1.0-1.5=mild complexity, 1.5-2.0=space-filling
- **Partition asymmetry** = |n1-n2| / (n1+n2-2) where n1,n2 = tips per subtree (0=symmetric, 1=asymmetric)

---

## 8. AnalyzeSkeleton

| Criterion | AnalyzeSkeleton | SNT |
|-----------|----------------|-----|
| Input | Binary/skeletonized | Image + tracing or SWC |
| Scripting | Macro `run()` | Groovy/Jython only |
| Output | Topology (branches, junctions) | Full morphometrics |
| Branch identity | No | Yes (parent-child) |
| Sholl/Strahler | Separate step | Integrated |
| Dense networks | Good | Difficult |
| Radius estimation | No | Yes |

### Complete Pipeline

```javascript
open("/path/to/neurons.tif");
run("Subtract Background...", "rolling=50");
setAutoThreshold("Otsu dark");  // consider: Triangle, Li, Huang
run("Convert to Mask");
run("Despeckle");
// Optional: remove small objects
run("Analyze Particles...", "size=20-Infinity show=Masks");
rename("cleaned");
run("Skeletonize (2D/3D)");
run("Analyze Skeleton (2D/3D)", "prune=none show display");
```

### Output Tables

**Main Results (per skeleton):** # Branches, # Junctions, # End-points, # Slab voxels, Average Branch Length, # Triple/Quadruple points, Maximum Branch Length, Longest Shortest Path

**Branch Information (per branch, with `show`):** Skeleton ID, Branch length, V1/V2 coordinates (x,y,z), Euclidean distance

**Tagged skeleton:** End-points=blue (30), Slabs=orange (127), Junctions=purple (70)

### Macro Parameters

```
run("Analyze Skeleton (2D/3D)",
    "prune=[METHOD] " +    // none | Shortest branch | Lowest intensity voxel | Lowest intensity branch
    "prune_0 " +           // prune ends (optional)
    "calculate " +         // shortest path via Floyd-Warshall (optional)
    "show " +              // branch info table (optional)
    "display");            // labelled skeleton image (optional)
```

### Pruning

| Method | Use When |
|--------|----------|
| None | Keep all branches |
| Shortest branch | Remove noise spurs |
| Lowest intensity voxel | Break false connections (needs original image) |
| Lowest intensity branch | Remove dimmest branches (needs original image) |
| `prune_0` (prune ends) | Remove terminal spurs |

### Groovy API (Silent Mode for Batch)

```groovy
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_
import ij.IJ

def imp = IJ.getImage()
def skel = new AnalyzeSkeleton_()
skel.setup("", imp)

// run(pruneIndex, pruneEnds, shortPath, origIP, silent, verbose)
def result = skel.run(AnalyzeSkeleton_.NONE, false, true, null, true, false)

def branches = result.getBranches()
def junctions = result.getJunctions()
def endpoints = result.getEndPoints()
def avgLength = result.getAverageBranchLength()
def graphs = result.getGraph()

for (int i = 0; i < branches.length; i++) {
    println("Skeleton $i: ${branches[i]} branches, ${junctions[i]} junctions")
}
```

### Custom Pruning by Length (Groovy)

```groovy
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_
import ij.IJ

def imp = IJ.getImage()
def minLength = 10.0  // calibrated units — choose based on expected neurite size

def skel = new AnalyzeSkeleton_()
skel.setup("", imp)
def result = skel.run(AnalyzeSkeleton_.NONE, false, false, null, true, false)

def stack = imp.getStack()
result.getGraph().each { graph ->
    graph.getEdges().each { edge ->
        if (edge.getLength() < minLength) {
            edge.getSlabs().each { pt -> stack.setVoxel((int)pt.x, (int)pt.y, (int)pt.z, 0) }
        }
    }
}
imp.updateAndDraw()
```

---

## 9. SWC File Format

Plain-text format: `ID TYPE X Y Z RADIUS PARENT` (one node per line, `#` for comments).

**Type codes:** 0=Undefined, 1=Soma, 2=Axon, 3=Basal dendrite, 4=Apical dendrite, 5+=Custom

```
# Example
  1   1    100.00   200.00   50.00   5.00     -1    # soma (root)
  2   3    105.00   195.00   50.00   2.50      1    # dendrite from soma
  3   3    112.00   188.00   51.00   2.00      2
  4   3    120.00   180.00   52.00   1.80      3    # branch point
  5   3    128.00   175.00   52.00   1.50      4    # child 1
  6   3    125.00   170.00   53.00   1.20      4    # child 2
  7   2     95.00   205.00   49.00   1.00      1    # axon from soma
```

### Loading/Saving in SNT

```groovy
import sc.fiji.snt.Tree

// Load
def tree = Tree.fromFile("/path/to/neuron.swc")
def axon = new Tree("/path/to/neuron.swc", "axon")       // compartment filter
def trees = Tree.listFromDir("/path/to/dir", "*.swc")    // batch load

// Save
tree.saveAsSWC("/path/to/output.swc")

// Convert TRACES to SWC
def tracesTrees = Tree.listFromFile("/path/to/neuron.traces")
tracesTrees.eachWithIndex { t, i -> t.saveAsSWC("/path/to/output_${i}.swc") }
```

### SWC Databases

- **NeuroMorpho.org** — 200k+ reconstructions; SNT can load via NeuroMorphoLoader
- **Allen Cell Types** — Mouse/human neurons with electrophysiology
- **MouseLight (Janelia)** — Full-brain projections; SNT has MouseLightLoader

```groovy
import sc.fiji.snt.io.MouseLightLoader
def loader = new MouseLightLoader("AA0100")
if (loader.isDatabaseAvailable() && loader.idExists()) {
    def axon = loader.getTree("axon", null)
    println("Axon cable: " + axon.getCableLength())
}
```

### Python Libraries for SWC

| Library | Usage |
|---------|-------|
| **NeuroM** | `nm.load_morphology("neuron.swc"); nm.get("total_length", neuron)` |
| **Navis** | `navis.read_swc("neuron.swc"); neuron.cable_length` |
| **MorphoPy** | `NeuronTree(swc_file="neuron.swc").get_topological_stats()` |

---

## 10. Batch Processing

### Batch SWC Morphometry (Groovy)

```groovy
import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.TreeAnalyzer

def trees = Tree.listFromDir("/path/to/swc_files", "*.swc")
def header = "Label,Cable_Length,N_Branches,N_Tips,N_BranchPts," +
    "Avg_Branch_Len,Contraction,Fractal_Dim,Strahler,Width,Height,Depth\n"
def csv = new StringBuilder(header)

trees.each { tree ->
    def a = new TreeAnalyzer(tree)
    csv.append([tree.getLabel(), a.getCableLength(), a.getNBranches(),
        a.getTips().size(), a.getBranchPoints().size(), a.getAvgBranchLength(),
        a.getAvgContraction(), a.getAvgFractalDimension(), a.getStrahlerNumber(),
        a.getWidth(), a.getHeight(), a.getDepth()].join(",") + "\n")
}
new File("/path/to/results.csv").text = csv.toString()
```

### Batch Sholl (Groovy)

```groovy
import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.sholl.TreeParser

def trees = Tree.listFromDir("/path/to/swc_files", "*.swc")
def summary = new StringBuilder("Label,Max_Inters,Critical_R,Enclosing_R,Sum_Inters\n")

trees.each { tree ->
    def parser = new TreeParser(tree)
    parser.setCenter(TreeParser.ROOT)
    parser.setStepSize(10)
    parser.parse()
    def p = parser.getProfile()
    summary.append([tree.getLabel(), p.max(), p.criticalRadius(),
                    p.enclosingRadius(), p.sum()].join(",") + "\n")
}
new File("/path/to/sholl_summary.csv").text = summary.toString()
```

### Batch AnalyzeSkeleton (Macro)

```javascript
inputDir = "/path/to/binary_images/";
outputDir = "/path/to/results/";
File.makeDirectory(outputDir);

list = getFileList(inputDir);
for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif")) {
        open(inputDir + list[i]);
        baseName = replace(getTitle(), ".tif", "");
        if (bitDepth() != 8) run("8-bit");
        setAutoThreshold("Otsu dark");
        run("Convert to Mask");
        run("Despeckle");
        run("Skeletonize (2D/3D)");
        run("Analyze Skeleton (2D/3D)", "prune=[Shortest branch] calculate show display");
        selectWindow("Results");
        saveAs("Results", outputDir + baseName + "_skeleton.csv");
        selectWindow("Branch information");
        saveAs("Results", outputDir + baseName + "_branches.csv");
        run("Close All");
    }
}
```

### Group Comparison (SNT Built-In)

```groovy
import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.GroupedTreeStatistics

def groupStats = new GroupedTreeStatistics()
groupStats.addGroup(Tree.listFromDir("/path/to/control", "*.swc"), "Control")
groupStats.addGroup(Tree.listFromDir("/path/to/treated", "*.swc"), "Treated")

["Cable length", "No. of branches", "No. of tips", "Strahler number"].each { metric ->
    try { println(metric + ": " + groupStats.compare(metric)) }
    catch (Exception e) { println(metric + ": failed - " + e.message) }
}
groupStats.getBoxPlot("No. of branches").show()
```

---

## 11. Vessel and Filament Analysis

Same pipeline applies: preprocess, segment, skeletonize, analyse. Key differences: vessels have loops (unlike neuronal trees), diameter matters, network topology is primary interest.

### Vessel Enhancement

```javascript
run("Subtract Background...", "rolling=100");
run("Tubeness", "sigma=3");  // sigma ~ vessel radius in pixels
setAutoThreshold("Li dark");
run("Convert to Mask");
```

### Angiogenesis Analyzer

Specialized for in vitro tube formation assays. Auto-binarizes, skeletonizes, classifies. Measures: junctions, segments, branches, extremities, total length, mesh area/count.

Install from: http://image.bio.methods.free.fr/ImageJ/

### Multi-Scale Vessel Enhancement (Retinal Example)

```javascript
// Different sigma for different vessel calibres
run("Duplicate...", "title=capillaries"); run("Tubeness", "sigma=1");
selectWindow("vessels");
run("Duplicate...", "title=arterioles"); run("Tubeness", "sigma=3");
selectWindow("vessels");
run("Duplicate...", "title=arteries"); run("Tubeness", "sigma=6");
imageCalculator("Max create", "capillaries", "arterioles");
imageCalculator("Max create", "Result", "arteries");
```

---

## 12. Statistics for Morphometry

| Measurement | Test | Notes |
|-------------|------|-------|
| Cable length | t-test / ANOVA | Single value per neuron |
| N branches / tips | t-test / ANOVA | Consider Poisson for counts |
| Strahler number | Mann-Whitney | Ordinal data |
| Sholl profile | RM-ANOVA or mixed effects | Repeated measures across radii |
| Branch length distribution | K-S test or Mann-Whitney | Don't treat branches as independent |
| Bifurcation angles | Circular statistics (Watson) | Angular data |
| Convex hull area | t-test / ANOVA | Single value per neuron |

**Key considerations:**
- Unit of analysis is typically the neuron, not the branch
- Multiple neurons from same animal = technical replicates; use nested/multilevel stats
- Typical sample: 10-30 neurons per group
- Report effect sizes (Cohen's d, eta-squared) alongside p-values
- For Sholl profiles: consider AUC as single summary metric for simple comparisons

---

## 13. Common Problems and Solutions

| Problem | Solutions |
|---------|----------|
| **Touching/overlapping neurites** | Trace separately in SNT; instance segmentation (Cellpose); intensity pruning in AnalyzeSkeleton; 3D imaging to separate in Z |
| **Low contrast neurites** | Tubeness/Frangi preprocessing; multi-scale filtering; local threshold (Phansalkar); SNT secondary layer; deconvolution |
| **Z-stack drift** | StackReg/TurboReg registration before tracing; SNT handles mild drift |
| **Background neurites** | Trace in 3D (not projections); connected components labelling to isolate target |
| **Border neurites** | Exclude or flag neurons touching image borders |
| **Dense neuropil** | Use density metrics instead (area fraction, texture); sparse labelling; AnalyzeSkeleton for topology |
| **Inconsistent segmentation** | Local thresholding (Phansalkar); ML segmentation (Weka/Labkit); per-image threshold exploration |
| **Soma detection** | SNT uses point soma; segment soma separately (Cellpose/StarDist) if area needed |
| **Skeleton loops** | Pruning; morphological opening before skeletonization; multiple AnalyzeSkeleton passes |

```javascript
// Local threshold for variable contrast
run("Auto Local Threshold", "method=Phansalkar radius=15 parameter_1=0 parameter_2=0");

// Break loops before skeletonizing
run("Open");  // morphological opening
run("Skeletonize (2D/3D)");

// Isolate neuron by connected component
run("Connected Components Labeling", "connectivity=26 type=[16 bits]");
makePoint(256, 256);  // click target
val = getPixel(256, 256);
setThreshold(val, val);
run("Convert to Mask");
```

---

## 14. Agent Integration

### SNT via `python ij.py script` (Groovy)

```bash
# Inline
python ij.py script '
import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.TreeAnalyzer
def tree = Tree.fromFile("/path/to/neuron.swc")
def a = new TreeAnalyzer(tree)
println("Cable: " + a.getCableLength())
'

# From file (for longer scripts)
python ij.py script --file .tmp/snt_analysis.groovy
```

### AnalyzeSkeleton via `python ij.py macro`

```bash
python ij.py macro '
  open("/path/to/neuron.tif");
  run("Subtract Background...", "rolling=50");
  setAutoThreshold("Otsu dark");
  run("Convert to Mask");
  run("Despeckle");
  run("Skeletonize (2D/3D)");
  run("Analyze Skeleton (2D/3D)", "prune=[Shortest branch] calculate show display");
'
python ij.py results
```

### Launch SNT for User

```bash
python ij.py macro 'open("/path/to/neuron.tif"); run("SNT...");'
# Tell user: "SNT is open. Trace your neuron, save as .swc"
# After saving:
python ij.py script '
import sc.fiji.snt.Tree
import sc.fiji.snt.analysis.TreeAnalyzer
def tree = Tree.fromFile("/path/to/saved.swc")
def a = new TreeAnalyzer(tree)
println("Cable: " + a.getCableLength() + " Branches: " + a.getNBranches())
'
```

### Probe Before Using

```bash
python probe_plugin.py "Sholl Analysis (From Image)..."
python probe_plugin.py "Analyze Skeleton (2D/3D)"
python probe_plugin.py "Tubeness"
```

### SNT Imports Cheat Sheet

```groovy
// Core
import sc.fiji.snt.Tree
import sc.fiji.snt.Path
import sc.fiji.snt.SNTService

// Analysis
import sc.fiji.snt.analysis.TreeAnalyzer
import sc.fiji.snt.analysis.TreeStatistics
import sc.fiji.snt.analysis.GroupedTreeStatistics

// Sholl
import sc.fiji.snt.analysis.sholl.TreeParser
import sc.fiji.snt.analysis.sholl.math.LinearProfileStats
import sc.fiji.snt.analysis.sholl.math.NormalizedProfileStats

// I/O
import sc.fiji.snt.io.MouseLightLoader

// Visualization
import sc.fiji.snt.viewer.Viewer3D
import sc.fiji.snt.analysis.TreeColorMapper
```

### TreeStatistics Metric Constants

```groovy
TreeStatistics.BRANCH_LENGTH            TreeStatistics.PATH_LENGTH
TreeStatistics.TERMINAL_LENGTH          TreeStatistics.PRIMARY_LENGTH
TreeStatistics.INNER_LENGTH             TreeStatistics.INTER_NODE_DISTANCE
TreeStatistics.NODE_RADIUS              TreeStatistics.MEAN_RADIUS
TreeStatistics.CONTRACTION              TreeStatistics.FRACTAL_DIMENSION
TreeStatistics.REMOTE_BIF_ANGLES        TreeStatistics.PARTITION_ASYMMETRY
TreeStatistics.N_BRANCH_POINTS          TreeStatistics.N_NODES
TreeStatistics.N_SPINES                 TreeStatistics.PATH_ORDER
TreeStatistics.AVG_SPINE_DENSITY        TreeStatistics.VALUES
TreeStatistics.X_COORDINATES            TreeStatistics.Y_COORDINATES
TreeStatistics.Z_COORDINATES            TreeStatistics.PATH_CHANNEL
TreeStatistics.PATH_FRAME
```

### TreeAnalyzer Key Methods

```groovy
// Summary
analyzer.getCableLength()              analyzer.getNBranches()
analyzer.getTips()                     analyzer.getBranchPoints()
analyzer.getStrahlerNumber()           analyzer.getStrahlerBifurcationRatio()
analyzer.getAvgBranchLength()          analyzer.getAvgContraction()
analyzer.getAvgFractalDimension()      analyzer.getHighestPathOrder()
analyzer.getWidth() / getHeight() / getDepth()
analyzer.getPrimaryBranches() / getTerminalBranches() / getInnerBranches()
analyzer.getPrimaryLength() / getTerminalLength() / getInnerLength()
analyzer.isValid()

// Filtering
analyzer.restrictToSWCType(2)          // axon
analyzer.restrictToOrder(1)            // primary paths
analyzer.restrictToLength(10, 500)     // length range
analyzer.restrictToNamePattern("*axon*")
analyzer.resetRestrictions()

// Output
analyzer.summarize(false)
analyzer.updateAndDisplayTable()
```

### Tree Key Methods

```groovy
// Load
Tree.fromFile("/path/to/file.swc")
Tree.listFromDir("/path/to/dir", "*.swc")
Tree.listFromFile("/path/to/file.traces")

// Properties
tree.getLabel()    tree.size()      tree.getRoot()
tree.is3D()        tree.getUnit()   tree.getBoundingBox()

// Transform
tree.translate(dx, dy, dz)    tree.scale(sx, sy, sz)
tree.rotate(axis, angle)      tree.downSample(maxDeviation)

// Save
tree.saveAsSWC("/path/to/output.swc")
tree.save("/path/to/output.traces")
```
