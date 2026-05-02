# CLIJ2 GPU Image Processing — Reference

GPU-accelerated image processing in Fiji/ImageJ via OpenCL. ~300 ops in the
CLIJ2 tier, ~500 across the union of CLIJ + CLIJ2 + CLIJx. Speedups range
from break-even on small 2D single-ops up to 100×+ on chained 3D pipelines.
Read this when an analysis is GPU-eligible: large stacks, repeated ops on
the same buffer, or a batch sweep over many files.

Sources: CLIJ2 docs (`clij.github.io/clij2-docs/`), CLIJ2 reference
(`clij.github.io/clij2-docs/reference`), API intro
(`clij.github.io/clij2-docs/api_intro`), benchmarking page, install guide,
GitHub `clij/clij2` (last release 2.5.3.4 on 2024-02-27),
`clEsperanto/pyclesperanto`, `clEsperanto/pyclesperanto_prototype`,
forum.image.sc CLIJ2 threads (Apple Silicon, OOM, multi-GPU, statistics
table). Project-side: `agent/references/3d-spatial-reference.md §12`,
`agent/references/large-dataset-optimization-reference.md §7`,
`agent/references/deconvolution-reference.md §6`,
`src/main/java/imagejai/engine/TCPCommandServer.java` (handlers
`handleExecuteMacro` and `handleRunScript`).

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro with `Ext.CLIJ2_*` calls.
`python ij.py script '<groovy>'` — run Groovy with the CLIJ2 Java API.
`python ij.py log` / `python ij.py console` — read CLIJ2 errors (split between
the two; see §13.6).

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Is CLIJ2 still maintained? What replaces it?" | §2 |
| "How do I install / verify CLIJ2 in Fiji?" | §3 |
| "Which category has the op I need?" | §4 |
| "ImageJ command X — what's the CLIJ2 equivalent?" | §5 |
| "Where does CLIJ2 have NO equivalent?" | §5.2 |
| "Is GPU worth it for my image size?" | §6 |
| "How big is my buffer on the GPU?" | §6.4 |
| "How does push / pull / release work?" | §7 |
| "What is the macro API skeleton?" | §8 |
| "How do I call the CLIJ2 Java API from Groovy?" | §9 |
| "Same pipeline in Python — pyclesperanto?" | §10 |
| "Copy-paste pipeline for filter→threshold→label→measure?" | §11.1 |
| "GPU max-Z projection on a big stack?" | §11.2 |
| "Voronoi-Otsu cell labelling one-shot?" | §11.3 |
| "Per-frame timelapse with reused buffers?" | §11.4 |
| "Drift correction on the GPU?" | §11.5 |
| "I need an op CLIJ2 doesn't have — custom kernel?" | §12 |
| "No OpenCL device found / driver setup?" | §13.1 |
| "CL_OUT_OF_RESOURCES / clBuffer alloc failed?" | §13.3 |
| "Why don't my pixel counts match ImageJ exactly?" | §13.4 |
| "How do I pick a specific GPU when several are present?" | §13.2 |
| "How does the TCP server handle CLIJ2 calls?" | §14 |
| "Should I migrate to pyclesperanto?" | §15 |
| "What's the most common mistake?" | §16 |
| "Which other reference covers part of this?" | §17 |
| "Why did my pipeline fail / leak GPU memory?" | §13, §16 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term's full definition.
Use `grep -n '\`<term>' clij2-gpu-reference.md` to jump.

### A
`absolute` §4.7 · `addImages` §4.7 · `addImageAndScalar` §4.7 ·
`affineTransform2D / 3D` §4.6 · `Apple Silicon` §13.1, §15 · `argMaximumZProjection` §4.5 ·
`automaticThreshold` §4.3

### B
`batch processing` §11.6 · `benchmarks` §6.1 · `binaryAnd / Or / Not / XOr` §4.2 ·
`binaryEdgeDetection` §4.2 · `binaryFillHoles` §4.2 · `boundingBox` §4.4 ·
`buffer lifecycle` §7.4 · `buffer naming` §8.3

### C
`cl_device=` §3.3, §13.2 · `ClearCLBuffer` §7.5, §9 · `ClearCLImage` §9 ·
`clEsperanto` §15 · `CLIJ2.getInstance()` §9.1 · `CLIJ2 Macro Extensions` §3.3, §8 ·
`CLIJx` §2, §15 · `clij2.clear()` §7.3 · `clij2.create(...)` §7.5 ·
`clij2.execute(...)` §12 · `clij2.pull(...)` §7.2 · `clij2.push(...)` §7.1 ·
`clij2.reportMemory()` §7.3, §13.3 · `closingBox / Diamond` §4.2 ·
`connectedComponentsLabelingBox / Diamond` §4.4, §11.1 · `copySlice` §11.4 ·
`custom kernels` §12 · `customOperation` §12.4

### D
`detectMaxima2DBox / 3DBox` §4.8 · `differenceOfGaussian2D / 3D` §4.1, §11.5 ·
`dilateBox / Sphere / Labels` §4.2, §4.4 · `distanceMap` §4.6, §11.7 ·
`drawSphere / Box / Line` §4.9 · `drift correction` §11.5

### E
`equalizeMeanIntensitiesOfSlices` §16 · `erodeBox / Sphere / Labels` §4.2, §4.4 ·
`Ext.CLIJ2_*` syntax §8 · `excludeLabelsOnEdges` §4.4, §11.1 ·
`excludeLabelsOutsideSizeRange` §4.4 · `executor / threading` §14.2

### F
`FFT — no equivalent` §5.2 · `Fiji install` §3 · `flip / flop ping-pong` §16 ·
`float32 internal` §13.4

### G
`gaussianBlur2D / 3D` §4.1, §11.1 · `getGPUProperties` §3.4, §13.2 ·
`getMaximumOfAllPixels` §4.4 · `globalsizes` (custom kernel) §12.2 ·
`gradientX / Y / Z` §4.1 · `Groovy API` §9 · `glasbey LUT` §11.3

### H
`HalfFloat` §7.5 · `Huang threshold` §4.3

### I
`IMAGE_*_TYPE` macros §12.3 · `ImageJ → CLIJ2 mapping` §5 ·
`installation` §3 · `intensity per label` §4.4 · `invalidateKernelCache` §12.4 ·
`isotropic voxels` §16 · `Iterating macros` §11.6

### L
`labelling` §4.4 · `labelToMask` §4.4 · `laplaceBox / Diamond` §4.1 ·
`Li threshold` §4.3 · `local memory` §13.3 · `lookup map` §0

### M
`MACRO_MUTEX` §14.2 · `macro extension naming` §8.4 ·
`maskedVoronoiLabeling` §4.4 · `matrix Voronoi` §4.4 · `maximum2DBox / 3DBox` §4.2 ·
`maximumZProjection / Bounded` §4.5, §11.2 · `mean2DBox / 3DBox` §4.1 ·
`meanIntensityMap` §4.4, §11.3 · `meanZProjection` §4.5 ·
`median2DBox / 3DBox` §4.1 · `mexicanHatFilter` §4.1 ·
`mergeTouchingLabels` §4.4 · `minimum2DBox / 3DBox` §4.2 · `minimumZProjection` §4.5

### N
`NativeTypeEnum` §7.5 · `naming convention` §8.3 · `no equivalent` §5.2

### O
`OpenCL` §2, §13.1 · `openingBox / Diamond` §4.2 · `Otsu threshold` §4.3 ·
`out-of-memory` §13.3 · `output column list (statisticsOfLabelledPixels)` §4.4

### P
`Performance hierarchy` §6.2 · `pixelCountMap` §4.4 · `power / square / sqrt` §4.7 ·
`precision (float32)` §13.4 · `probe_plugin.py` §3.5 ·
`projection ops` §4.5 · `pull` §7.2 · `pullBinary` §7.2 · `push` §7.1 ·
`pushCurrentSlice` §7.1 · `pushCurrentZStack` §7.1 ·
`pyclesperanto` §10, §15

### R
`relabelSequential` §4.4 · `release` §7.3 · `replaceIntensities` §4.4, §4.7 ·
`reportMemory` §7.3, §13.3 · `resliceTop / Bottom / Left / Right` §4.6 ·
`Results table` §4.4 · `rigidTransform` §4.6 · `rotate2D / 3D` §4.6 ·
`run_script` §9, §14.1.2

### S
`scale2D / 3D` §4.6, §16 · `Skeletonize — no equivalent` §5.2 ·
`Sobel` §4.1 · `spot detection` §11.5 · `standardDeviationBox / ZProjection` §4.1, §4.5 ·
`statisticsOfLabelledPixels` §4.4, §11.1 · `subtractBackground2D — deprecated` §5.1 ·
`subtractImages` §4.7, §11.7

### T
`TCP server integration` §14 · `threading model` §14.2 · `threshold (manual)` §4.3 ·
`thresholdOtsu / Triangle / Yen / Li / Default / etc` §4.3 ·
`topHatBox / Sphere` §4.1, §11.7 · `touchingNeighborCountMap` §4.4 ·
`Triangle threshold` §4.3 · `troubleshooting` §13

### U
`UnsignedByte / Short` §7.5 · `update sites` §3.2

### V
`varianceBox` §4.1 · `voronoiLabeling` §4.2 · `voronoiOctagon` §4.2 ·
`voronoiOtsuLabeling` §4.4, §11.3

### W
`watershed` §4.2 · `workflow patterns` §16

### X / Y / Z
`X / Y / Z projection ops` §4.5 · `Z chunking` §13.3 · `zPositionOfMaximumZProjection` §4.5

---

## §2 What CLIJ2 is

CLIJ2 is a Fiji/ImageJ plugin that runs image-processing kernels on the GPU
through OpenCL. The user writes ImageJ macro, Groovy, Jython, or Java; pixel
data is moved into a GPU buffer (`ClearCLBuffer`), processed by one or many
operations on-device, and pulled back as an `ImagePlus`. OpenCL is hidden
behind the API.

### §2.1 Lineage

| Generation | Years | Status |
|---|---|---|
| **CLIJ** (1.x) | 2016–2019 | Superseded; some `Ext.CLIJ_*` calls still live for back-compat. |
| **CLIJ2** (2.x) | 2020–present | **Stable / maintenance-only.** Last release 2.5.3.4 on 2024-02-27. Bug fixes only; no new features expected. **This is the right answer for Fiji macro / Groovy users today.** |
| **CLIJx** | 2020–2022 | Experimental tier alongside CLIJ2 (`Ext.CLIJx_*`). Incubator for ops later promoted to CLIJ2 or dropped. Optional update site. |
| **clEsperanto / pyclesperanto / CLIc** | 2022– | **Active development**, multi-backend (OpenCL + CUDA + Metal). Python and C++ first, Java in progress. Successor line, but no Fiji update site yet. |

There is **no project named "CLIJ2x"** on GitHub — the name in casual usage
either means CLIJx (the experimental tier) or the next-gen successor
clEsperanto. Treat both possibilities.

### §2.2 Maintenance status

CLIJ2 is in maintenance mode. Forum threads through 2025 confirm bug-fix
support but no new features. New work is happening in clEsperanto. For an
agent driving Fiji over TCP today, CLIJ2 macro extensions are stable,
recordable, and present in every Fiji install with the update site enabled
— stick with them. See §15 for migration guidance.

---

## §3 Installation and verification

### §3.1 Update sites (Fiji)

```
1. Help > Update...
2. (Apply standard Fiji updates first)
3. Click "Manage Update Sites"
4. Tick:    clij      clij2
   Optional:
              clijx
              clij2-assistant
              clij2-assistant-extensions
5. Close, Apply Changes, Restart Fiji.
```

`clij2` requires `clij` (provides `clij-core`, `clij-clearcl`, `clij-coremem`).
`clijx` is needed only if you call `Ext.CLIJx_*` ops.

### §3.2 What you should see after restart

- Menu: `Plugins > ImageJ on GPU (CLIJ2)` exists.
- Toolbar: a CLIJx-Assistant button (if assistant site enabled).
- Plugin scan reports: `### GPU Accelerated (CLIJ2) (504)` in
  `.tmp/commands.md` after running `python scan_plugins.py`.

### §3.3 Initialise from a macro

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");      // empty = first / default device
```

This **MUST** be the first line of every macro that uses `Ext.CLIJ2_*`. It does
not persist across `execute_macro` calls (each call is a fresh `IJ.runMacro`
context). See §14.2.

### §3.4 List devices and properties

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_getGPUProperties(name, mem, ocl);
print(name);                                       // e.g. "NVIDIA GeForce RTX 3090"
print(mem);                                        // bytes available
print(ocl);                                        // OpenCL version string
```

In Groovy: `CLIJ.getAvailableDeviceNames()` returns a `List<String>`;
`CLIJ2.getInstance().getGPUName()` returns the active device.

### §3.5 Probe support

CLIJ2 menu wrappers (e.g. `Plugins > ImageJ on GPU (CLIJ2) > Gaussian Blur on GPU`)
open a `GenericDialog`, so `python probe_plugin.py "Gaussian Blur on GPU"` works
for that path. The canonical `Ext.CLIJ2_*` macro extension is **not** discoverable
via probe — use this reference and the upstream docs for argument names.

---

## §4 Command catalogue by category

CLIJ2 has roughly 300 ops in its core tier. The categories below cover the
ones an agent will reach for first; see `clij.github.io/clij2-docs/reference`
for the full list.

### §4.1 Filters

```javascript
Ext.CLIJ2_gaussianBlur2D(src, dst, sigma_x, sigma_y);
Ext.CLIJ2_gaussianBlur3D(src, dst, sigma_x, sigma_y, sigma_z);    // sigma=0 skips an axis
Ext.CLIJ2_mean2DBox(src, dst, rx, ry);
Ext.CLIJ2_mean3DBox(src, dst, rx, ry, rz);
Ext.CLIJ2_median2DBox(src, dst, rx, ry);                          // radius capped ~4 (kernel ≤9×9)
Ext.CLIJ2_median3DBox(src, dst, rx, ry, rz);                      // radius capped ~2
Ext.CLIJ2_topHatBox(src, dst, rx, ry, rz);                        // background subtraction
Ext.CLIJ2_topHatSphere(src, dst, rx, ry, rz);
Ext.CLIJ2_bottomHatBox(src, dst, rx, ry, rz);
Ext.CLIJ2_differenceOfGaussian2D(src, dst, s1x, s1y, s2x, s2y);
Ext.CLIJ2_differenceOfGaussian3D(src, dst, s1x, s1y, s1z, s2x, s2y, s2z);
Ext.CLIJ2_mexicanHatFilter(src, dst, sigma_min, sigma_max);
Ext.CLIJ2_gradientX(src, dst);   Ext.CLIJ2_gradientY(src, dst);   Ext.CLIJ2_gradientZ(src, dst);
Ext.CLIJ2_sobel(src, dst);
Ext.CLIJ2_laplaceBox(src, dst);   Ext.CLIJ2_laplaceDiamond(src, dst);
Ext.CLIJ2_varianceBox(src, dst, rx, ry, rz);
Ext.CLIJ2_standardDeviationBox(src, dst, rx, ry, rz);
Ext.CLIJ2_entropyBox(src, dst, rx, ry, rz);
```

### §4.2 Morphology

```javascript
Ext.CLIJ2_erodeBox(src, dst);          // single-pixel erosion — iterate for larger radius
Ext.CLIJ2_erodeSphere(src, dst);
Ext.CLIJ2_dilateBox(src, dst);
Ext.CLIJ2_dilateSphere(src, dst);
Ext.CLIJ2_openingBox(src, dst, n);     // n erosions then n dilations
Ext.CLIJ2_closingBox(src, dst, n);
Ext.CLIJ2_openingDiamond(src, dst, n);
Ext.CLIJ2_closingDiamond(src, dst, n);
Ext.CLIJ2_binaryAnd(a, b, dst);   Ext.CLIJ2_binaryOr(a, b, dst);
Ext.CLIJ2_binaryNot(src, dst);    Ext.CLIJ2_binaryXOr(a, b, dst);
Ext.CLIJ2_binaryEdgeDetection(src, dst);
Ext.CLIJ2_binaryFillHoles(src, dst);
Ext.CLIJ2_binarySubtract(a, b, dst);
Ext.CLIJ2_watershed(binary, dst);                  // limited quality (per docs); see §16
Ext.CLIJ2_voronoiLabeling(binary, dst);
Ext.CLIJ2_voronoiOctagon(binary, dst);
Ext.CLIJ2_maskedVoronoiLabeling(seeds, mask, dst);
```

CLIJ2's docs explicitly note `watershed` "delivers results of limited quality" —
for high-quality watershed, fall back to ImageJ stock or `MorphoLibJ`, or use
the `detectMaxima` + `maskedVoronoiLabeling` recipe in §11.

### §4.3 Thresholding

```javascript
Ext.CLIJ2_threshold(src, dst, t);                  // manual: pixels >= t -> 1 else 0
Ext.CLIJ2_thresholdOtsu(src, dst);                 // 8-bit binary (0/1) on GPU
Ext.CLIJ2_thresholdTriangle(src, dst);
Ext.CLIJ2_thresholdHuang(src, dst);
Ext.CLIJ2_thresholdYen(src, dst);
Ext.CLIJ2_thresholdLi(src, dst);
Ext.CLIJ2_thresholdMean(src, dst);
Ext.CLIJ2_thresholdMinimum(src, dst);
Ext.CLIJ2_thresholdDefault(src, dst);
Ext.CLIJ2_thresholdMaxEntropy(src, dst);
Ext.CLIJ2_thresholdRenyiEntropy(src, dst);
Ext.CLIJ2_thresholdShanbhag(src, dst);
Ext.CLIJ2_thresholdMoments(src, dst);
Ext.CLIJ2_thresholdPercentile(src, dst);
Ext.CLIJ2_thresholdIsoData(src, dst);
Ext.CLIJ2_thresholdIJ_IsoData(src, dst);
Ext.CLIJ2_thresholdMinError(src, dst);
Ext.CLIJ2_thresholdIntermodes(src, dst);
Ext.CLIJ2_automaticThreshold(src, dst, "Otsu");    // single entry point for all 17
```

CLIJ2's auto-thresholders are ports of ImageJ's `AutoThresholder` — results
match ImageJ stock to within float-precision rounding.

### §4.4 Labelling and per-label statistics

```javascript
Ext.CLIJ2_connectedComponentsLabelingBox(binary, labels);     // 8-conn 2D / 26-conn 3D
Ext.CLIJ2_connectedComponentsLabelingDiamond(binary, labels); // 4-conn 2D / 6-conn 3D
Ext.CLIJ2_voronoiOtsuLabeling(src, dst, spot_sigma, outline_sigma);   // one-shot nuclei segmentation
Ext.CLIJ2_excludeLabelsOnEdges(in, out);
Ext.CLIJ2_excludeLabelsOutsideSizeRange(in, out, min, max);
Ext.CLIJ2_relabelSequential(in, out);
Ext.CLIJ2_closeIndexGapsInLabelMap(in, out);
Ext.CLIJ2_extendLabelingViaVoronoi(in, out);
Ext.CLIJ2_mergeTouchingLabels(in, out);
Ext.CLIJ2_dilateLabels(in, out, radius);
Ext.CLIJ2_erodeLabels(in, out, radius, relabel_islands);
Ext.CLIJ2_centroidsOfLabels(labels, dst);
Ext.CLIJ2_labelToMask(labels, dst, label_id);
Ext.CLIJ2_reduceLabelsToCentroids(in, out);
Ext.CLIJ2_reduceLabelsToLabelEdges(in, out);
Ext.CLIJ2_touchingNeighborCountMap(labels, dst);

Ext.CLIJ2_statisticsOfLabelledPixels(input, labels);    // per-label stats -> ImageJ Results table
Ext.CLIJ2_statisticsOfBackgroundAndLabelledPixels(input, labels);   // includes label 0 = background

Ext.CLIJ2_pixelCountMap(labels, dst);                   // per-label area as parametric image
Ext.CLIJ2_meanIntensityMap(input, labels, dst);
Ext.CLIJ2_maximumIntensityMap(input, labels, dst);
Ext.CLIJ2_minimumIntensityMap(input, labels, dst);
Ext.CLIJ2_standardDeviationIntensityMap(input, labels, dst);

Ext.CLIJ2_getMaximumOfAllPixels(image, max_value);      // by-ref scalar return
Ext.CLIJ2_getMeanOfMaskedPixels(src, mask);             // -> Results table row
```

#### `statisticsOfLabelledPixels` columns (in order)

```
IDENTIFIER
BOUNDING_BOX_X            BOUNDING_BOX_Y            BOUNDING_BOX_Z
BOUNDING_BOX_END_X        BOUNDING_BOX_END_Y        BOUNDING_BOX_END_Z
BOUNDING_BOX_WIDTH        BOUNDING_BOX_HEIGHT       BOUNDING_BOX_DEPTH
MINIMUM_INTENSITY         MAXIMUM_INTENSITY
MEAN_INTENSITY            SUM_INTENSITY             STANDARD_DEVIATION_INTENSITY
PIXEL_COUNT
SUM_INTENSITY_TIMES_X     SUM_INTENSITY_TIMES_Y     SUM_INTENSITY_TIMES_Z
MASS_CENTER_X             MASS_CENTER_Y             MASS_CENTER_Z
SUM_X                     SUM_Y                     SUM_Z
CENTROID_X                CENTROID_Y                CENTROID_Z
SUM_DISTANCE_TO_MASS_CENTER       MEAN_DISTANCE_TO_MASS_CENTER
MAX_DISTANCE_TO_MASS_CENTER       MAX_MEAN_DISTANCE_TO_MASS_CENTER_RATIO
SUM_DISTANCE_TO_CENTROID          MEAN_DISTANCE_TO_CENTROID
MAX_DISTANCE_TO_CENTROID          MAX_MEAN_DISTANCE_TO_CENTROID_RATIO
```

`CENTROID_*` is geometric (mean coordinates); `MASS_CENTER_*` is intensity-
weighted. `MAX_MEAN_DISTANCE…_RATIO` is a roundness/elongation proxy
(round ≈ 1, elongated > 1). `BOUNDING_BOX_END_*` is inclusive
(`WIDTH = END_X − BOUNDING_BOX_X + 1`). The Java form returns
`double[][]` indexed `[label_index][column_index]`; the macro form fills
the IJ Results table directly.

`Ext.CLIJ2_statisticsOfLabelledPixels` **excludes label 0 (background)**.
Use `statisticsOfBackgroundAndLabelledPixels` to include it (CPU-side path).

### §4.5 Projections

```javascript
Ext.CLIJ2_maximumZProjection(src, dst);             // standard MIP
Ext.CLIJ2_meanZProjection(src, dst);
Ext.CLIJ2_minimumZProjection(src, dst);
Ext.CLIJ2_sumZProjection(src, dst);
Ext.CLIJ2_standardDeviationZProjection(src, dst);
Ext.CLIJ2_medianZProjection(src, dst);
Ext.CLIJ2_argMaximumZProjection(src, dst_max, dst_argmax);    // also returns Z-index of max
Ext.CLIJ2_zPositionOfMaximumZProjection(src, dst);
Ext.CLIJ2_maximumZProjectionBounded(src, dst, z_min, z_max);  // sub-range
Ext.CLIJ2_maximumXProjection(src, dst);             // side view (along X)
Ext.CLIJ2_maximumYProjection(src, dst);             // top view (along Y)
```

### §4.6 Transforms

```javascript
Ext.CLIJ2_affineTransform2D(src, dst, "matrix=[m00 m01 m02 m10 m11 m12]");
Ext.CLIJ2_affineTransform3D(src, dst, "rotateZ=45 scaleX=2 translateX=10 -center");
Ext.CLIJ2_rotate2D(src, dst, angle_deg, around_center);
Ext.CLIJ2_rotate3D(src, dst, ax, ay, az, around_center);
Ext.CLIJ2_translate2D(src, dst, tx, ty);
Ext.CLIJ2_translate3D(src, dst, tx, ty, tz);
Ext.CLIJ2_scale2D(src, dst, sx, sy);
Ext.CLIJ2_scale3D(src, dst, sx, sy, sz, scale_to_center);
Ext.CLIJ2_rigidTransform(src, dst, tx, ty, tz, ax_deg, ay_deg, az_deg);
Ext.CLIJ2_resample(src, dst, fx, fy, fz, linear);
Ext.CLIJ2_downsample2D(src, dst, fx, fy);
Ext.CLIJ2_downsample3D(src, dst, fx, fy, fz);
Ext.CLIJ2_makeIsotropic(src, dst, new_voxel_size);
Ext.CLIJ2_distanceMap(binary_src, dst);              // city-block distance map
Ext.CLIJ2_resliceTop(src, dst);    Ext.CLIJ2_resliceBottom(src, dst);
Ext.CLIJ2_resliceLeft(src, dst);   Ext.CLIJ2_resliceRight(src, dst);
Ext.CLIJ2_rotateClockwise(src, dst);
Ext.CLIJ2_rotateCounterClockwise(src, dst);
```

**No FFT** in CLIJ2 — round-trip via ImageJ FHT or use clEsperanto.

### §4.7 Math / arithmetic

```javascript
Ext.CLIJ2_addImages(a, b, dst);
Ext.CLIJ2_addImagesWeighted(a, b, dst, wa, wb);
Ext.CLIJ2_subtractImages(a, b, dst);                   // dst = a - b (clipped to >=0 if dst is unsigned)
Ext.CLIJ2_multiplyImages(a, b, dst);
Ext.CLIJ2_divideImages(a, b, dst);
Ext.CLIJ2_addImageAndScalar(src, dst, s);
Ext.CLIJ2_multiplyImageAndScalar(src, dst, s);
Ext.CLIJ2_subtractImageFromScalar(src, dst, s);        // dst = s - src
Ext.CLIJ2_divideScalarByImage(src, dst, s);
Ext.CLIJ2_power(src, dst, exponent);
Ext.CLIJ2_powerImages(base, exponent, dst);
Ext.CLIJ2_exponential(src, dst);     Ext.CLIJ2_logarithm(src, dst);
Ext.CLIJ2_squareRoot(src, dst);
Ext.CLIJ2_absolute(src, dst);
Ext.CLIJ2_invert(src, dst);
Ext.CLIJ2_sinus(src, dst);   Ext.CLIJ2_cosinus(src, dst);
Ext.CLIJ2_maximumImages(a, b, dst);     Ext.CLIJ2_minimumImages(a, b, dst);
Ext.CLIJ2_equalConstant(src, dst, c);   Ext.CLIJ2_notEqualConstant(src, dst, c);
Ext.CLIJ2_greaterConstant(src, dst, c); Ext.CLIJ2_smallerConstant(src, dst, c);
Ext.CLIJ2_mask(src, mask, dst);                       // dst = src where mask != 0 else 0
Ext.CLIJ2_replaceIntensities(src, dst, mapping);      // LUT-style: dst[i] = mapping[src[i]]
```

### §4.8 Detection

```javascript
Ext.CLIJ2_detectMaxima2DBox(src, dst, rx, ry);
Ext.CLIJ2_detectMaxima3DBox(src, dst, rx, ry, rz);
Ext.CLIJ2_detectMinima2DBox(src, dst, rx, ry);
Ext.CLIJ2_detectMinima3DBox(src, dst, rx, ry, rz);
Ext.CLIJ2_detectLabelEdges(labels, dst);
```

Output is a binary maxima mask, not a point list. To get coordinates, label
the maxima and pull centroids.

### §4.9 Drawing / fill

```javascript
Ext.CLIJ2_set(dst, value);                           // fill whole buffer
Ext.CLIJ2_setRandom(dst, min, max, seed);
Ext.CLIJ2_setColumn(dst, col, value);
Ext.CLIJ2_setRow(dst, row, value);
Ext.CLIJ2_setPlane(dst, z, value);
Ext.CLIJ2_drawLine(dst, x1, y1, z1, x2, y2, z2, thickness, value);
Ext.CLIJ2_drawSphere(dst, cx, cy, cz, rx, ry, rz, value);
Ext.CLIJ2_drawBox(dst, x, y, z, w, h, d, value);
Ext.CLIJ2_drawMeshBetweenTouchingLabels(labels, dst);
```

`drawSphere` etc add to the buffer rather than clearing it — `set(dst, 0)`
first if you want a clean canvas.

---

## §5 ImageJ → CLIJ2 mapping

### §5.1 Direct equivalents

| ImageJ command | CLIJ2 macro | Notes |
|---|---|---|
| `Gaussian Blur...` σ (2D) | `Ext.CLIJ2_gaussianBlur2D(src, dst, σ, σ);` | |
| `Gaussian Blur 3D...` σx,σy,σz | `Ext.CLIJ2_gaussianBlur3D(src, dst, σx, σy, σz);` | sigma=0 skips an axis |
| `Median...` r | `Ext.CLIJ2_median2DBox(src, dst, r, r);` | radius capped ~4 |
| `Mean...` r | `Ext.CLIJ2_mean2DBox(src, dst, r, r);` | |
| `Minimum...` r | `Ext.CLIJ2_minimum2DBox(src, dst, r, r);` | |
| `Maximum...` r | `Ext.CLIJ2_maximum2DBox(src, dst, r, r);` | |
| `Variance...` r | `Ext.CLIJ2_varianceBox(src, dst, r, r, 0);` | |
| `Subtract Background...` rolling=R | `Ext.CLIJ2_topHatBox(src, dst, R, R, 0);` | morphological, NOT identical to rolling-ball. Old `Ext.CLIJx_subtractBackground2D` is deprecated. |
| `Auto Threshold` Otsu | `Ext.CLIJ2_thresholdOtsu(src, dst);` | dst is 0/1 — use `pullBinary` for IJ's 0/255 |
| `Auto Threshold` Default | `Ext.CLIJ2_thresholdDefault(src, dst);` | |
| `Convert to Mask` (manual t) | `Ext.CLIJ2_threshold(src, dst, t);` | |
| `Watershed` (binary) | `Ext.CLIJ2_watershed(binary, dst);` | quality limited; prefer §11.6 recipe |
| `Z Project... Max` | `Ext.CLIJ2_maximumZProjection(src, dst);` | |
| `Z Project... Mean` | `Ext.CLIJ2_meanZProjection(src, dst);` | |
| `Z Project... Sum` | `Ext.CLIJ2_sumZProjection(src, dst);` | |
| `Z Project... StdDev` | `Ext.CLIJ2_standardDeviationZProjection(src, dst);` | |
| `Fill Holes` | `Ext.CLIJ2_binaryFillHoles(src, dst);` | not in every CLIJ2 build; round-trip via IJ if missing |
| `Erode` (×N) | `Ext.CLIJ2_erodeBox(src, dst);` ×N (one pixel per call) | |
| `Dilate` (×N) | `Ext.CLIJ2_dilateBox(src, dst);` ×N | |
| `Open` (binary, n) | `Ext.CLIJ2_openingBox(src, dst, n);` | |
| `Close-` (binary, n) | `Ext.CLIJ2_closingBox(src, dst, n);` | |
| `Distance Map` | `Ext.CLIJ2_distanceMap(binary_src, dst);` | city-block |
| `Find Maxima...` | `Ext.CLIJ2_detectMaxima2DBox(src, dst, r, r);` | output is binary mask, not point list |
| `Add...` (constant) | `Ext.CLIJ2_addImageAndScalar(src, dst, c);` | |
| `Subtract...` (constant) | `Ext.CLIJ2_addImageAndScalar(src, dst, -c);` | |
| `Multiply...` (constant) | `Ext.CLIJ2_multiplyImageAndScalar(src, dst, c);` | |
| `Divide...` (constant) | `Ext.CLIJ2_multiplyImageAndScalar(src, dst, 1/c);` | |
| `Image Calculator` Add/Sub/Mul/Div | `Ext.CLIJ2_addImages` / `subtractImages` / `multiplyImages` / `divideImages` | |
| `Smooth` (3×3 mean) | `Ext.CLIJ2_mean2DBox(src, dst, 1, 1);` | |
| `Voronoi` | `Ext.CLIJ2_voronoiLabeling(binary, dst);` | |
| `3D Objects Counter` | `Ext.CLIJ2_connectedComponentsLabelingBox(binary, labels);` + `statisticsOfLabelledPixels` | |
| `Analyze Particles...` | (see §5.2 — multi-step, no single replacement) | |
| `Set Measurements...` | (see §5.2 — `statisticsOfLabelledPixels` always emits its full set) | |

### §5.2 Where CLIJ2 has NO direct equivalent

| ImageJ command | What to do |
|---|---|
| `FFT` / `FHT` | Pull, run IJ FFT, push back. clEsperanto has FFT in progress. |
| `Skeletonize` (2D/3D) | Pull, run IJ skeletonize, push back. `cle.skeletonize` exists in pyclesperanto (Lee 1994). |
| `Analyze Particles` | Compose: `connectedComponentsLabelingBox(binary, labels)` + `excludeLabelsOutsideSizeRange(labels, ok, min, max)` + `excludeLabelsOnEdges(ok, clean)` + `statisticsOfLabelledPixels(input, clean)`. |
| `Set Measurements` | No toggle — `statisticsOfLabelledPixels` always emits its full column list (see §4.4). |
| `Subtract Background` (rolling-ball) | Closest is `topHatBox`; not pixel-equivalent. |
| `Sharpen` / `Unsharp Mask` | Compose: `gaussianBlur` + `subtractImages` to get high-pass, `addImages` back. |
| `Find Maxima → point list` | Output is a binary mask; `connectedComponentsLabelingBox` it then `centroidsOfLabels` for coordinates. |
| `Plot Profile` | No GPU equivalent; use ImageJ. |
| `Bandpass Filter` (FFT-based) | No equivalent (no FFT). Use spatial DoG as a band-pass approximation: `differenceOfGaussian2D`. |

---

## §6 When GPU is worth it

### §6.1 Documented benchmark

Test image: T1 Head (2.4 MB, 16-bit, 129×129×129). 3D mean filter 3×3×3, runs
2–10 averaged.

| Path | Time |
|---|---|
| ImageJ CPU 3D mean | ~3471 ms (warm) / ~2381 ms (cold) |
| CLIJ2 `mean3DBox` 3×3×3 | ~12 ms (warm) / ~62 ms (cold) |
| Push (one 16-bit 4 MB image) | ~28 ms one-time |
| Pull (one image) | ~62 ms one-time |

≈280× per-op once warm; ≈30× including push+pull as one-shots. Source:
`clij.github.io/clij2-docs/md/benchmarking/`.

Lab-/community-reported speedups for typical pipelines: **laptop GPU
~15×**, **workstation GPU ~33×**, **optimised pipeline up to 100×** (cross-
checked with `agent/references/large-dataset-optimization-reference.md §7`).

### §6.2 Performance hierarchy

From slowest to fastest, holding pipeline constant:

1. ImageJ macro on 32-bit float (slowest).
2. ImageJ macro on 8-/16-bit.
3. CLIJ2 with **per-call push + pull** (push/pull dominates — only marginally
   faster than CPU for one op).
4. CLIJ2 with **one push, N ops, one pull** (the canonical pattern).
5. CLIJ2 in batch with **one `getInstance()`, reused buffers across files**
   (fastest).

### §6.3 Decision rules

- **2D op, image < 512×512**: GPU often **slower** once push+pull are counted.
  CPU L1/L2 cache wins.
- **2D op, image ≥ 1024×1024**: GPU usually wins.
- **Any 3D op on a stack**: GPU almost always wins.
- **Multiple chained ops on the same buffer**: GPU's advantage scales with
  the chain length. This is the canonical CLIJ2 pattern.
- **Batch over many files**: keep `CLIJ2.getInstance()` alive between iterations
  to avoid re-initialising the OpenCL context.

The single biggest gain in real-world pipelines is removing unnecessary
`pull → push` cycles between operations: an optimised port of a workflow
runs ~5.5× faster than the same workflow with intermediate pulls.

### §6.4 Memory footprint rule of thumb

Bytes = `width × height × depth × bytes_per_pixel`. With CLIJ2 promoting many
intermediates to float32:

| Image | Bytes/pixel | 2048×2048 | 1024×1024×100 | 2048×2048×100 |
|---|---:|---:|---:|---:|
| 8-bit | 1 | 4 MB | 100 MB | 400 MB |
| 16-bit | 2 | 8 MB | 200 MB | 800 MB |
| float32 | 4 | 16 MB | 400 MB | 1.6 GB |

A 4 GB GPU comfortably holds one 16-bit stack + one float result; an 8 GB
card lets you chain 4–6 intermediates; 24 GB+ is rarely memory-bound for
typical microscopy. See §13.3 for OOM strategies.

---

## §7 Push, pull, release, lifecycle

### §7.1 Push (CPU → GPU)

```javascript
Ext.CLIJ2_push(image_title);                  // by IJ window title
Ext.CLIJ2_pushCurrentSlice(image_title);      // current Z slice as a 2D buffer
Ext.CLIJ2_pushCurrentZStack(image_title);     // active C/T as a 3D stack
```

The argument is the **IJ window title string**. CLIJ2 maintains an internal
`title → ClearCLBuffer` map. If no image with that title is open, the call
does nothing — always confirm with `getTitle()` first.

### §7.2 Pull (GPU → CPU)

```javascript
Ext.CLIJ2_pull(buffer_name);                  // generic — float result becomes a 32-bit IJ image
Ext.CLIJ2_pullBinary(buffer_name);            // for 0/1 GPU buffers — rescales to 0/255 for IJ
Ext.CLIJ2_pullToROIManager(buffer_name);      // label image → ROIs
Ext.CLIJ2_saveAsTIF(buffer_name, path);       // skip the IJ window entirely
```

A pulled image gets an IJ window titled with the buffer name. Title collisions
get the usual `-1`, `-2` suffixes — re-confirm via `python ij.py windows` if
in doubt.

Use `pullBinary` for masks: `pull` will give you an IJ image with values 0
and 1, not 0 and 255 — measurement plugins downstream may misbehave.

### §7.3 Release

```javascript
Ext.CLIJ2_release(buffer_name);               // free one buffer
Ext.CLIJ2_clear();                            // free ALL CLIJ2 buffers on the GPU
Ext.CLIJ2_reportMemory();                     // print buffer table to Log
```

Three idioms:

- **Release as you go** — best for tight VRAM. Free upstream buffers as soon
  as you're done with them.
- **Clear at the end** — simplest; one `Ext.CLIJ2_clear()` at the bottom of
  the pipeline. Used in the majority of demo macros.
- **Reuse across loop iterations** — overwrite the same named buffer instead
  of release-then-recreate. Measured 2–4× speed-up in tight loops over
  reallocate-each-iteration.

### §7.4 Lifetime gotcha

GPU buffers persist on the GPU between `execute_macro` calls — but you
**cannot** reach them by name from a fresh macro call, because the macro
interpreter's variable bindings are gone. **Keep a CLIJ2 pipeline self-
contained in one `execute_macro` call**, and end it with `Ext.CLIJ2_clear()`.

### §7.5 NativeTypeEnum (Java/Groovy buffer types)

```
NativeTypeEnum.UnsignedByte    1 byte/pixel    8-bit (most masks/labels)
NativeTypeEnum.UnsignedShort   2 bytes/pixel   16-bit (most raw microscopy)
NativeTypeEnum.Float           4 bytes/pixel   float32 — CLIJ2's default for derived results
NativeTypeEnum.HalfFloat       2 bytes/pixel   fp16 — supported but rarely used
NativeTypeEnum.Byte / Short / Int / UnsignedInt — also available
```

**Avoid `Double`** — most consumer GPUs lack `cl_khr_fp64` and double-precision
ops fall back to slow software emulation.

`clij2.create(template_buffer)` gives you the same dims+type as the template;
`clij2.create([w,h,d] as long[], NativeTypeEnum.Float)` is the explicit form.

**In-place ops are unsafe.** OpenCL 1.2 doesn't support in-place writes
reliably — always pass distinct `src` and `dst`.

---

## §8 Macro API (`Ext.CLIJ2_*`)

### §8.1 Skeleton

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");      // 1. init (REQUIRED at start of every macro)
Ext.CLIJ2_clear();                                 // 2. wipe leftovers
input = getTitle();                                // 3. confirm an image is open
Ext.CLIJ2_push(input);                             // 4. CPU -> GPU
// 5. chain ops using string names for buffers
Ext.CLIJ2_gaussianBlur2D(input, blurred, 2, 2);
Ext.CLIJ2_thresholdOtsu(blurred, mask);
// 6. pull final result(s)
Ext.CLIJ2_pull(mask);
// 7. release
Ext.CLIJ2_clear();
```

### §8.2 Argument types

- **Image arguments**: passed as **string identifiers** (the IJ window title
  for inputs, any unique string for intermediates). CLIJ2 allocates the
  output buffer on first use.
- **Numeric arguments**: pass through as Java `double` / `int`.
- **String arguments** (e.g. method name for `automaticThreshold`, transform
  spec for `affineTransform3D`): pass as quoted strings.
- **Boolean arguments**: pass as macro `true` / `false`.

### §8.3 Naming convention for intermediates

| Stage | Conventional name |
|---|---|
| Input | `input`, or `getTitle()` |
| Filtered | `blurred`, `denoised`, `equalized`, `background_subtracted` |
| Thresholded | `binary`, `mask`, `mask1`, `mask2` |
| Connected components | `labelmap`, `labels`, `labelled` |
| Cleaned labels | `labels_clean`, `labels_sized`, `labels_final` |
| Distance / parametric | `distance_map`, `intensity_map`, `ncount_map` |
| Final / shown | `result`, `output`, `visualization` |
| Iterative scratch | `flip` / `flop` (alternated each iteration) |

### §8.4 Macro-extension naming rule

Every Java method `<name>` on `CLIJ2` is auto-exposed as `Ext.CLIJ2_<name>(...)`.
Method-name camelCase is preserved verbatim. CLIJx ops live alongside as
`Ext.CLIJx_<name>(...)`. CLIJ (the older library) ops are still callable as
`Ext.CLIJ_<name>(...)` for back-compat.

### §8.5 Macro gotchas

- `run("CLIJ2 Macro Extensions", ...)` MUST be the first line. Re-running
  switches GPU device.
- `Ext.CLIJ2_push("title")` silently does nothing if no image with that title
  is open — confirm with `getTitle()` first.
- `Ext.CLIJ2_pull` for masks gives 0/1 IJ pixels; use `pullBinary` for 0/255.
- Output buffer name strings are case-sensitive and whitespace-sensitive.
- `Ext.CLIJ2_release(name)` accepts the buffer's exact name string.
- The macro recorder **does** record `Ext.CLIJ2_*` calls — record once, polish, re-run.

---

## §9 Groovy / Java API

```groovy
import net.haesleinhuepf.clij2.CLIJ2
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer
import net.haesleinhuepf.clij.coremem.enums.NativeTypeEnum

CLIJ2 clij2 = CLIJ2.getInstance()                          // default device
// CLIJ2 clij2 = CLIJ2.getInstance("RTX")                   // substring match for device

ClearCLBuffer src     = clij2.push(IJ.getImage())          // ImagePlus -> ClearCLBuffer
ClearCLBuffer blurred = clij2.create(src)                  // same dims+type
ClearCLBuffer mask    = clij2.create(src)
ClearCLBuffer labels  = clij2.create(src)

clij2.gaussianBlur2D(src, blurred, 2.0, 2.0)
clij2.thresholdOtsu(blurred, mask)
clij2.connectedComponentsLabelingBox(mask, labels)

double[][] stats = clij2.statisticsOfLabelledPixels(src, labels)   // also fills Results
println("Found ${stats.length} labels")

clij2.pull(labels).show()

// Idiomatic cleanup — list-each, never forgets:
[src, blurred, mask, labels].each { it.close() }
// or just: clij2.clear()
```

Top-level classes:

- `net.haesleinhuepf.clij2.CLIJ2` — main entry point.
- `net.haesleinhuepf.clij.CLIJ` — older gateway, still around for back-compat.
- `net.haesleinhuepf.clij.clearcl.ClearCLBuffer` — image type used by all ops.
- `net.haesleinhuepf.clij.clearcl.ClearCLImage` — texture variant for the few
  ops that need hardware-sampled access.
- `net.haesleinhuepf.clij.coremem.enums.NativeTypeEnum` — pixel type enum.
- `net.haesleinhuepf.clij2.macro.CLIJ2MacroExtensions` — the macro bridge
  loaded by `run("CLIJ2 Macro Extensions", ...)`.

### §9.1 Key lifecycle methods

```java
CLIJ2.getInstance();                 // singleton; init on first call
CLIJ2.getInstance("RTX");            // substring match for device

clij2.clear();                       // free all CLIJ-managed buffers
clij2.reportMemory();                // log buffer table
clij2.create(template);              // same dims+type
clij2.create(long[] dims, NativeTypeEnum type);

clij2.push(Object);                  // ImagePlus, ImgPlus, RAI -> ClearCLBuffer
clij2.pushCurrentSlice(ImagePlus);   // single 2D slice
clij2.pushCurrentZStack(ImagePlus);  // active C/T as 3D
clij2.pull(Object);                  // -> ImagePlus
clij2.pullBinary(buffer);            // 8-bit binary

clij2.getGPUName();                  // e.g. "NVIDIA GeForce RTX 3090"
clij2.getOpenCLVersion();
clij2.getCLIJ();                     // underlying CLIJ gateway

// custom kernels — see §12
clij2.execute(Class anchor, String filename, String kernelname,
              long[] globalSizes, HashMap<String, Object> params);
```

### §9.2 Why use the Groovy API vs macro

- Real variables instead of string-named buffers.
- `try { ... } finally { it.close() }` — exception-safe cleanup.
- Direct access to `ResultsTable`, `ImagePlus`, `RealRandomAccessible`.
- `clij2.statisticsOfLabelledPixels(...)` returns a `double[][]` you can
  inspect programmatically without parsing the Results table.
- Compile-time errors caught before execution.

Trade-off: Groovy errors land in `System.err`, not `IJ.getLog()` — read with
`python ij.py console`, not `python ij.py log`.

---

## §10 Python via pyclesperanto

The Python successor. Same kernel library underneath; numpy-style API.

```bash
pip install pyclesperanto              # current — multi-backend (OpenCL + CUDA + Metal)
# or, the older but more feature-complete prototype:
pip install pyclesperanto-prototype    # OpenCL-only via pyopencl
```

```python
import pyclesperanto as cle              # or: import pyclesperanto_prototype as cle
from skimage.io import imread

cle.select_device("RTX")                  # substring match; lists all if not found
img = imread("blobs.tif")

blurred = cle.gaussian_blur(img, sigma_x=2, sigma_y=2)
binary  = cle.threshold_otsu(blurred)
labels  = cle.connected_components_labeling_box(binary)
clean   = cle.exclude_labels_on_edges(labels)
stats   = cle.statistics_of_labelled_pixels(img, clean)   # pandas DataFrame in prototype

print(f"{int(clean.max())} labels")
```

Naming map: `Ext.CLIJ2_gaussianBlur2D` → `cle.gaussian_blur` (camelCase →
snake_case, dimension suffix dropped where unambiguous). Most ops map 1:1
since the kernels are shared.

Memory is automatic — assignments push, function calls return GPU buffers,
the garbage collector frees. `cle.push(arr)` and `cle.pull(buf)` are still
available for explicit control.

---

## §11 Common pipelines (copy-pasteable)

### §11.1 Filter → threshold → label → measure (2D)

```javascript
run("Blobs (25K)");
input = getTitle();
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_clear();
Ext.CLIJ2_push(input);

Ext.CLIJ2_gaussianBlur2D(input, blurred, 2, 2);
Ext.CLIJ2_thresholdOtsu(blurred, mask);
Ext.CLIJ2_connectedComponentsLabelingBox(mask, labels);
Ext.CLIJ2_excludeLabelsOnEdges(labels, labels_clean);
Ext.CLIJ2_excludeLabelsOutsideSizeRange(labels_clean, labels_final, 50, 1e9);
Ext.CLIJ2_statisticsOfLabelledPixels(input, labels_final);

Ext.CLIJ2_pull(labels_final);
run("glasbey on dark");

Ext.CLIJ2_clear();
```

3D variant: replace `gaussianBlur2D` with `gaussianBlur3D(input, blurred,
σx, σy, σz)`. Everything else is the same.

### §11.2 GPU max-Z projection

```javascript
input = getTitle();
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_clear();
Ext.CLIJ2_push(input);
Ext.CLIJ2_maximumZProjection(input, mip);
Ext.CLIJ2_pull(mip);
Ext.CLIJ2_clear();
```

For all four projections in one pass: also call `meanZProjection`,
`sumZProjection`, `standardDeviationZProjection` and pull each.

### §11.3 Voronoi-Otsu cell labelling (one-shot)

```javascript
input = getTitle();
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_clear();
Ext.CLIJ2_push(input);

// spot_sigma   ~ size of a spot       (bigger = fewer, fatter labels)
// outline_sigma ~ boundary smoothness
Ext.CLIJ2_voronoiOtsuLabeling(input, labels, 5, 1);
Ext.CLIJ2_excludeLabelsOnEdges(labels, labels_clean);
Ext.CLIJ2_statisticsOfLabelledPixels(input, labels_clean);

Ext.CLIJ2_pull(labels_clean);                       // object mask, per house rule
run("glasbey on dark");
Ext.CLIJ2_clear();
```

`voronoiOtsuLabeling` internally is `gaussianBlur` → `detectMaxima2DBox` →
`thresholdOtsu` → `binaryAnd` → `maskedVoronoiLabeling`. Knowing the
expansion lets you swap a stage when the one-shot misbehaves (e.g. Triangle
threshold instead of Otsu for sparse spots).

### §11.4 Per-frame timelapse with reused buffers

```javascript
input = getTitle();
getDimensions(w, h, channels, slices, frames);
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_clear();
Ext.CLIJ2_create3D(out_stack, w, h, frames, 32);

setBatchMode(true);
for (t = 0; t < frames; t++) {
    Stack.setFrame(t + 1);
    Ext.CLIJ2_pushCurrentZStack(input);                  // overwrites "input"

    Ext.CLIJ2_gaussianBlur2D(input, blurred, 2, 2);      // reuses "blurred"
    Ext.CLIJ2_thresholdOtsu(blurred, binary);
    Ext.CLIJ2_connectedComponentsLabelingBox(binary, labels);
    Ext.CLIJ2_copySlice(labels, out_stack, t);

    showProgress(t + 1, frames);
}
setBatchMode(false);

Ext.CLIJ2_pull(out_stack);
rename("labels_over_time");
run("glasbey on dark");
Ext.CLIJ2_clear();
```

The **never-release-mid-loop** pattern is the speed win: reusing the same
`blurred` / `binary` / `labels` buffer name overwrites in place rather than
reallocating. Measured 2–4× faster than reallocating per iteration.

### §11.5 Spot detection (3D DoG → maxima → label → stats)

```javascript
input = getTitle();
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_clear();
Ext.CLIJ2_push(input);

// DoG — (sigma2 - sigma1) ~ spot radius
Ext.CLIJ2_differenceOfGaussian3D(input, dog, 1, 1, 1, 3, 3, 3);

// Local maxima in a 3-px box, gated by an Otsu mask of the DoG response
Ext.CLIJ2_detectMaxima3DBox(dog, maxima, 1, 1, 1);
Ext.CLIJ2_thresholdOtsu(dog, gate);
Ext.CLIJ2_mask(maxima, gate, masked_maxima);

// Label and measure
Ext.CLIJ2_connectedComponentsLabelingBox(masked_maxima, label_spots);
Ext.CLIJ2_statisticsOfLabelledPixels(input, label_spots);
Ext.CLIJ2_getMaximumOfAllPixels(label_spots, n_spots);
print("Detected spots: " + n_spots);

Ext.CLIJ2_pull(label_spots);
run("glasbey on dark");
Ext.CLIJ2_clear();
```

### §11.6 Watershed via maximum-filter + masked Voronoi (better than `Ext.CLIJ2_watershed`)

```javascript
input = getTitle();
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_clear();
Ext.CLIJ2_push(input);

Ext.CLIJ2_thresholdOtsu(input, mask);                     // 1. foreground mask
Ext.CLIJ2_distanceMap(mask, dmap);                        // 2. distance map (peaks = centres)
Ext.CLIJ2_maximum2DBox(dmap, dmap_dilated, 5, 5);
Ext.CLIJ2_equalConstant(dmap, peak_test, 0);              // helper
Ext.CLIJ2_subtractImages(dmap_dilated, dmap, diff);
Ext.CLIJ2_smallerOrEqualConstant(diff, peak_binary, 0);   // pixel == its dilated max
Ext.CLIJ2_mask(peak_binary, mask, peak_inside);           // gate to mask
Ext.CLIJ2_connectedComponentsLabelingBox(peak_inside, seeds);
Ext.CLIJ2_maskedVoronoiLabeling(seeds, mask, watershed_labels);

Ext.CLIJ2_pull(watershed_labels);
run("glasbey on dark");
Ext.CLIJ2_clear();
```

### §11.7 Background-subtracted measurement

```javascript
input = getTitle();
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_clear();
Ext.CLIJ2_push(input);

// Background = heavy Gaussian (sigma >> object radius)
Ext.CLIJ2_gaussianBlur3D(input, background, 20, 20, 1);
Ext.CLIJ2_subtractImages(input, background, corrected);

// Threshold and label on the corrected image; measure on the original
Ext.CLIJ2_thresholdOtsu(corrected, binary);
Ext.CLIJ2_connectedComponentsLabelingBox(binary, labels);
Ext.CLIJ2_excludeLabelsOnEdges(labels, labels_clean);
Ext.CLIJ2_statisticsOfLabelledPixels(input, labels_clean);

Ext.CLIJ2_pull(corrected);
Ext.CLIJ2_pull(labels_clean);
Ext.CLIJ2_clear();
```

Alternative: `Ext.CLIJ2_topHatBox(input, corrected, R, R, 0)` — morphological,
faster than the blur-and-subtract pattern when R is small.

### §11.8 Batch loop — keep one CLIJ2 instance alive

```javascript
dir = getDirectory("Choose folder");
files = getFileList(dir);
run("CLIJ2 Macro Extensions", "cl_device=");           // ONCE per session
out_dir = dir + "AI_Exports/";
File.makeDirectory(out_dir);

setBatchMode(true);
for (i = 0; i < files.length; i++) {
    if (!endsWith(files[i], ".tif")) continue;
    open(dir + files[i]);
    title = getTitle();

    Ext.CLIJ2_push(title);
    Ext.CLIJ2_gaussianBlur2D(title, blurred, 2, 2);
    Ext.CLIJ2_thresholdOtsu(blurred, mask);
    Ext.CLIJ2_connectedComponentsLabelingBox(mask, labels);
    Ext.CLIJ2_pull(labels);
    saveAs("Tiff", out_dir + files[i] + "_labels.tif");

    Ext.CLIJ2_clear();
    run("Close All");
}
setBatchMode(false);
```

Do NOT call `run("CLIJ2 Macro Extensions", ...)` inside the loop — that
re-initialises the device every iteration.

---

## §12 Custom OpenCL kernels — `clij2.execute(...)`

When the built-in 300-odd ops don't cover what you need (custom per-pixel
math, novel structuring elements, project-specific arithmetic).

### §12.1 Java/Groovy signature

```java
clij2.execute(
    Class<?>            anchor,            // any class in the same JAR as the .cl file
    String              programFilename,   // e.g. "my_kernel.cl"
    String              kernelName,        // entry point in the .cl file
    long[]              globalSizes,       // OpenCL global work size, usually dst dims
    HashMap<String,Object> parameters      // "name" -> ClearCLBuffer | Float | Integer
);
```

### §12.2 Kernel skeleton (.cl)

```c
__constant sampler_t sampler =
    CLK_NORMALIZED_COORDS_FALSE |
    CLK_ADDRESS_CLAMP_TO_EDGE  |
    CLK_FILTER_NEAREST;

__kernel void my_op(
    IMAGE_src_TYPE  src,    // input — name MUST contain "src" or "input"
    IMAGE_dst_TYPE  dst,    // output — name MUST contain "dst" or "output"
    float           scale   // scalar passes through
) {
    const int x = get_global_id(0);
    const int y = get_global_id(1);
    const int z = get_global_id(2);
    const int4 pos = (int4)(x, y, z, 0);

    float v = (float)(READ_src_IMAGE(src, sampler, pos).x);
    IMAGE_dst_PIXEL_TYPE out = CONVERT_dst_PIXEL_TYPE(v * scale);
    WRITE_dst_IMAGE(dst, pos, out);
}
```

### §12.3 The substitution scheme

CLIJ inspects the buffers you bind by parameter name and rewrites the `.cl`
source at load time. For each `<name>` in your parameters:

| In your .cl | Becomes (example for 16-bit `src` buffer) |
|---|---|
| `IMAGE_<name>_TYPE` | `__global const ushort*` |
| `IMAGE_<name>_PIXEL_TYPE` | `ushort` |
| `READ_<name>_IMAGE(buf, sampler, pos)` | type-correct buffer read returning `uint4` |
| `WRITE_<name>_IMAGE(buf, pos, value)` | type-correct buffer write |
| `CONVERT_<name>_PIXEL_TYPE(v)` | saturating cast: `convert_ushort_sat(v)` |

Rules:

- The parameter name **must contain** a recognisable role (`src`, `dst`,
  `input`, `output`, or any consistent `<name>` you reference in the macros).
- `READ_*` returns a 4-component vector — `.x` is the scalar value.
- `WRITE_*` takes a 4-component vector — wrap scalars with
  `CONVERT_*_PIXEL_TYPE(v)`.
- 2D-only kernels use `int2 pos` and `READ_*_IMAGE_2D` / `WRITE_*_IMAGE_2D`.
- Pass scalars via the `HashMap` as `Float` or `Integer`; they map to
  `float` / `int` parameters.

### §12.4 Worked example — Groovy call site

```groovy
import net.haesleinhuepf.clij2.CLIJ2
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer
import java.util.HashMap

clij2 = CLIJ2.getInstance()
src = clij2.push(IJ.getImage())
dst = clij2.create(src)

HashMap<String, Object> params = new HashMap<>()
params.put("src", src)
params.put("dst", dst)
params.put("scale", 2.0f)

clij2.execute(
    MyPlugin.class,                        // anchor — same JAR/resource root as my_kernel.cl
    "my_kernel.cl",
    "my_op",
    dst.getDimensions(),                    // global work size = dst shape
    params
)

clij2.pull(dst).show()
[src, dst].each { it.close() }
```

Macro callers can also use `Ext.CLIJ2_customOperation(opencl_src, "", params)`
to inline OpenCL as a string — convenient for one-off arithmetic, but the
.cl-file path is cleaner for anything you want to reuse. Call
`Ext.CLIJ2_invalidateKernelCache()` while developing to force recompile;
remove for production speed.

---

## §13 Troubleshooting

### §13.1 "No OpenCL device found"

- **Windows**: install vendor GPU drivers. Fresh Windows installs lack
  OpenCL ICDs for non-Intel GPUs.
- **Linux**: `sudo apt install ocl-icd-opencl-dev`, plus a vendor ICD
  (`intel-opencl-icd`, `nvidia-opencl-icd`, AMD ROCm). Confirm with
  `clinfo`.
- **macOS Intel**: built-in.
- **macOS Apple Silicon**: OpenCL is deprecated by Apple but still ships and
  runs CLIJ2 — performance is reduced and a few ops crash. Long-term
  answer is `pyclesperanto` with the Metal backend.
- **Windows Server 2019**: known initialization failures unless certain JVM
  flags are set; see image.sc forum.

### §13.2 Selecting a specific GPU

```javascript
run("CLIJ2 Macro Extensions", "cl_device=[NVIDIA GeForce RTX 3090]");
run("CLIJ2 Macro Extensions", "cl_device=RTX");        // substring match works
run("CLIJ2 Macro Extensions", "cl_device=AMD");
run("CLIJ2 Macro Extensions", "cl_device=");           // empty = first device the driver enumerates
```

List devices via menu `Plugins > ImageJ on GPU (CLIJ2) > Macro tools >
List available GPU devices`, or in Groovy via `CLIJ.getAvailableDeviceNames()`.

### §13.3 Out of memory (`CL_OUT_OF_RESOURCES`, `Failed to allocate clBuffer`)

In order of how often each fix helps:

1. `Ext.CLIJ2_reportMemory();` — see what's resident. Forgotten buffers are
   the most common cause.
2. `Ext.CLIJ2_release(name);` between steps; `Ext.CLIJ2_clear();` between
   batch iterations.
3. Process the stack in Z chunks: loop with `Ext.CLIJ2_copySlice(src, dst, z)`
   or `pushCurrentSlice` per slice.
4. Down-cast: avoid float intermediates when 16-bit suffices
   (`Ext.CLIJ2_copy(src_float, dst_short_buffer)`).
5. Don't run CLIJ2 alongside CUDA / PyTorch processes on a 4 GB card.
6. Update GPU drivers — `CL_OUT_OF_RESOURCES` on small images often points
   to driver bugs.

### §13.4 Precision (float32 vs IJ's double)

- CLIJ2 is **single-precision (float32)** internally. ImageJ uses double for
  measurements.
- Pixel sums and means may differ by ~1e-6 relative.
- Threshold pixel counts may differ by a handful of pixels at boundary
  values — expected, not a bug.
- Border handling: CLIJ2 typically uses `CLAMP_TO_EDGE` sampling; ImageJ
  some filters use other border policies. Edge pixels of filtered images
  may differ.

### §13.5 Class not found / version mismatch

- `Cannot find class net.haesleinhuepf.clij2.CLIJ2` — re-tick `clij` AND
  `clij2` update sites, apply, restart.
- For `@Grab` Groovy: pin to `2.5.3.4`.
- Maven: `net.haesleinhuepf:clij2_:2.5.3.4` plus `clij_:2.5.3.4`,
  `clij-clearcl:2.5.3.4`, `clij-coremem:2.5.3.4`.

### §13.6 Errors split between log and console

CLIJ2 errors arrive in different places depending on the path:

- **Macro path** (`Ext.CLIJ2_*` via `execute_macro`): `IJ.error` → `IJ.getLog()`.
  Read with `python ij.py log`. Typical: `"CLIJ2_xxx is not defined"` (forgot
  `run("CLIJ2 Macro Extensions", ...)` ), `"Buffer not found"` (typo or
  released).
- **Groovy path** (`run_script`): exceptions go to `System.err`. Read with
  `python ij.py console`. Typical: `IllegalArgumentException` from CLIJ2's
  input validation, OpenCL exceptions with stack trace.

If a `run_script` reply has a bare error and `ij.py log` is empty, **always
run `ij.py console` before retrying**.

### §13.7 Vendor / platform specifics

- **Intel HD on Linux** — black-image bug; switch to NVIDIA or use Mesa
  rusticl.
- **AMD Vega 10** — repeated init crashes; restart Fiji or pin Adrenalin
  driver version.
- **macOS auto-graphics-switching** (Intel + dGPU MacBooks) — force Fiji to
  use the dedicated GPU via `Energy Saver`.

---

## §14 Integration with this project's TCP server

The TCP server at `localhost:7746` exposes Fiji to the agent. Two commands
are the load-bearing ones for CLIJ2.

### §14.1 The two relevant commands

#### §14.1.1 `execute_macro` — primary CLIJ2 entry point

Source: `src/main/java/imagejai/engine/TCPCommandServer.java`,
handler `handleExecuteMacro`.

```python
# Recipe: any CLIJ2 pipeline via the macro path
import subprocess
macro = '''
run("CLIJ2 Macro Extensions", "cl_device=");
input = getTitle();
Ext.CLIJ2_push(input);
Ext.CLIJ2_gaussianBlur2D(input, blurred, 2, 2);
Ext.CLIJ2_thresholdOtsu(blurred, mask);
Ext.CLIJ2_pull(mask);
Ext.CLIJ2_clear();
'''
subprocess.run(["python", "ij.py", "macro", macro], check=True)
```

Reply on success: `{"ok": true, "result": {"success": true,
"executionTimeMs": ..., "stateDelta": {...}, "histogramDelta": {...}, ...}}`.

#### §14.1.2 `run_script` — for the CLIJ2 Java API

Source: same file, `handleRunScript`. Default language is Groovy; also
accepts `jython`, `javascript`.

```python
# Recipe: same pipeline via the Groovy/Java path
import subprocess
groovy = '''
import net.haesleinhuepf.clij2.CLIJ2
import ij.IJ
def clij2 = CLIJ2.getInstance()
def src = clij2.push(IJ.getImage())
def blurred = clij2.create(src)
def mask = clij2.create(src)
clij2.gaussianBlur2D(src, blurred, 2.0, 2.0)
clij2.thresholdOtsu(blurred, mask)
clij2.pull(mask).show()
[src, blurred, mask].each { it.close() }
clij2.clear()
'''
subprocess.run(["python", "ij.py", "script", groovy], check=True)
```

### §14.2 Threading model (matters for CLIJ2)

Both handlers run user code on a `Executors.newSingleThreadExecutor()` —
**not on the EDT**. The handler polls every 150 ms for blocking dialogs and
timeout. Every call serialises behind a JVM-wide `MACRO_MUTEX`, so two
overlapping `execute_macro` / `run_script` calls cannot race — they queue.

Implications:

- A 30-second CLIJ2 pipeline does NOT freeze the AWT EDT, but it does block
  every other macro/script call for that duration.
- Read-only TCP commands that don't take `MACRO_MUTEX` (`get_state`,
  `get_log`, `get_console`, `get_image_info`) return immediately.

### §14.3 Per-call init requirement

Each `execute_macro` request is a fresh `IJ.runMacro` invocation. The macro
interpreter loses `Ext.*` bindings between calls. **Every macro that uses
CLIJ2 must start with `run("CLIJ2 Macro Extensions", "cl_device=");`.**

GPU buffers themselves persist between calls — but the macro variables that
held their names are gone, so you can't reach them by name from a fresh
macro. **Best practice: keep a CLIJ2 pipeline self-contained in one
`execute_macro` call**, and end with `Ext.CLIJ2_clear()`.

### §14.4 Verification flow

After every CLIJ2 step the agent should validate with:

| Command | Checks |
|---|---|
| `python ij.py windows` | Pulled image appeared with the expected title |
| `python ij.py info` | Active image dims / bit depth as expected |
| `python ij.py results` | `statisticsOfLabelledPixels` populated the table |
| `python ij.py histogram` | Filtered intensity distribution looks right |
| `python ij.py log` | CLIJ2 macro errors / warnings |
| `python ij.py console` | Groovy / Java stack traces |
| `python ij.py capture` then Read | Visual sanity check — see `agent/CLAUDE.md` |

### §14.5 Constraints specific to the TCP path

1. **GPU memory is invisible to the TCP server.** It tracks `ImagePlus`
   windows for undo/state, not `ClearCLBuffer`s. Always end with
   `Ext.CLIJ2_clear()` or buffers leak across requests.
2. **Pulled images get auto-titled from the buffer name.**
   `Ext.CLIJ2_pull("labels")` opens a window titled `labels` (or `labels-1`
   if a window already exists with that title). Re-query with
   `python ij.py windows` if uncertain.
3. **Phantom-dialog detector and CLIJ2 menu wrappers.** Some
   `Plugins > ImageJ on GPU (CLIJ2) > ...` menu commands open a dialog. The
   server's phantom-dialog detector may auto-dismiss it. Prefer the
   `Ext.CLIJ2_*` macro-extension form, which never opens a dialog.
4. **Fuzzy plugin-name validation** is on by default for `run("Name", ...)`
   calls (and so applies to CLIJ2 menu wrappers). It does **not** apply to
   `Ext.CLIJ2_*` calls.
5. **`get_pixels` does not read GPU buffers directly.** Pull first; then
   `get_pixels` reads the resulting `ImagePlus`. The 4M-pixel safety cap
   applies — for large pulled stacks, save to TIFF and read with
   `tifffile` instead.

---

## §15 Migration to clEsperanto / pyclesperanto

CLIJ2 is the right answer **today** for Fiji macro / Groovy users. The
clEsperanto line is where new development is happening.

| Property | CLIJ2 | clEsperanto / pyclesperanto |
|---|---|---|
| Language | Java front, OpenCL kernels | Python / C++ / Java fronts; OpenCL + CUDA + Metal kernels via CLIc |
| Status | Stable, maintenance only (last 2.5.3.4 Feb 2024) | Active (releases through 2026) |
| Fiji integration | Native, via update site, `Ext.CLIJ2_*` | None directly — use Python / napari |
| Apple Silicon | Works via Apple's deprecated OpenCL shim | Native Metal backend |
| GPU vendors | NVIDIA / AMD / Intel via OpenCL only | NVIDIA (CUDA), AMD/Intel (OpenCL), Apple (Metal) |
| Function names | `gaussianBlur3D`, `thresholdOtsu` | `gaussian_blur`, `threshold_otsu` (snake_case) |
| Args | positional | kwargs in Python |
| Memory model | manual `release` / `clear` | reference-counted via numpy-like return values |
| Recommended for | **Fiji macro/Groovy users today** | **New Python/napari projects from 2024+** |

Mapping is mostly 1:1 — kernels are shared between CLIJ2 and clEsperanto via
`clEsperanto/clij-opencl-kernels`. Differences:

- pyclesperanto returns numpy-compatible arrays, not buffer handles.
- Some CLIJx-only ops are missing from pyclesperanto.
- New ops in pyclesperanto are not in CLIJ2.
- pyclesperanto's `voronoi_otsu_labeling` is the same algorithm.

For the agent: **do not migrate proactively**. Migrate when (a) the user
needs Apple-Silicon Metal, (b) you're already in Python and don't want a
round trip through Fiji, or (c) you need a CUDA-only feature.

---

## §16 Gotchas / Pitfalls

1. **Forgot to init.** `Ext.CLIJ2_xxx is not defined` → first line of the
   macro must be `run("CLIJ2 Macro Extensions", "cl_device=");`. This
   applies per `execute_macro` call (see §14.3).
2. **Pull vs pullBinary.** Use `pullBinary` for masks. `pull` gives an IJ
   image with values 0/1 instead of 0/255.
3. **In-place ops.** OpenCL 1.2 doesn't support in-place writes reliably —
   always pass distinct `src` and `dst`.
4. **Erode/dilate are single-pixel.** For radius N, iterate `Ext.CLIJ2_erodeBox`
   N times, or use `openingBox(src, dst, N)` / `closingBox`.
5. **`watershed` quality is limited** — docs say so explicitly. Use the
   distance-map + masked-Voronoi recipe in §11.6 for tighter results.
6. **No FFT, no Skeletonize.** Round-trip via ImageJ.
7. **Missing isotropy in 3D.** Before any 3D morphology / distance map,
   make voxels isotropic: `Ext.CLIJ2_makeIsotropic(src, iso, target_size)`
   or `Ext.CLIJ2_scale3D(src, iso, sx, sy, sz, false)` using the calibration.
   Otherwise dilate/erode/distance-map deliver anisotropic results.
8. **`equalizeMeanIntensitiesOfSlices`.** Before segmenting deep stacks
   where signal degrades with depth: `Ext.CLIJ2_equalizeMeanIntensitiesOfSlices
   (iso_stack, equalized, depth/2);`.
9. **`flip`/`flop` ping-pong** for iterative dilate/erode. Reuse two named
   buffers instead of release-allocate per iteration. Measured 2–4× faster.
10. **`subtractBackground2D` is deprecated.** Use `topHatBox` or
    `differenceOfGaussian2D`.
11. **Float32 internal precision.** Pixel-count comparisons against ImageJ
    stock will differ by a handful at threshold boundaries — expected.
12. **Don't `Enhance Contrast normalize=true` data you'll measure.** CLIJ2's
    `*OfAllPixels` and `statisticsOfLabelledPixels` give true intensity
    stats from the underlying buffer — that's the right way to get numbers
    post-pipeline. Display-side `setMinAndMax()` after pull is fine.
13. **3D Viewer / 3Dscript need 8-bit** but CLIJ2 outputs are float32 by
    default. `pull` then `run("8-bit")`, or `Ext.CLIJ2_create3D(name, w, h, d, 8)`
    and `Ext.CLIJ2_copy(src_float, dst_byte)` for on-GPU conversion.
14. **Drift correction via centre-of-mass + `affineTransform2D`** (a natural
    CLIJ2 fit) is **wrong** for images with moving fluorescent cells — the
    moving cells dominate the centre of mass and the correction follows
    them. For those, use crop-based ImageJ `Correct 3D Drift`.
15. **Don't re-init inside batch loops.** `run("CLIJ2 Macro Extensions", ...)`
    once per session, not per file.
16. **CLIJ2 memory is independent from the JVM heap.** Increasing
    `-Xmx` does not give you more GPU memory.

---

## §17 Cross-references

CLIJ2 is touched in several other references in this set; this document is
the canonical entry point. For specific topics, see also:

| Reference | Section | Topic |
|---|---|---|
| `3d-spatial-reference.md` | §12 | Full 3D-specific Ext catalogue, distance/neighbour ops |
| `large-dataset-optimization-reference.md` | §7 | When-is-GPU-worth-it speedup table; batch loop |
| `deconvolution-reference.md` | §6 | `Ext.CLIJx_imageJ2RichardsonLucyDeconvolution`; VRAM rule |
| `if-postprocessing-reference.md` | §3 | IF preprocessing filters |
| `light-sheet-reference.md` | §7, §13 | GPU deconv / large-stack patterns |
| `registration-stitching-reference.md` | §18 | `Ext.CLIJ2_affineTransform2D/3D`, `rigidTransform`, drift |
| `fiji-scripting-reference.md` | §11.4 | CLIJ2 Java API from Groovy |
| `pipeline-construction-reference.md` | §13 | Generic pipeline pattern |
| `macro-reference.md` | §20 | `Ext.*` macro extension syntax |
| `analysis-landscape.md` | §4 | Plugin survey, install status |

---

## §18 Cheat sheet — minimum viable CLIJ2 macro

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");      // 1
input = getTitle();                                // 2
Ext.CLIJ2_push(input);                             // 3
// ... ops on named buffers ...                   // 4
Ext.CLIJ2_pull(final_buffer);                      // 5
Ext.CLIJ2_clear();                                 // 6
```

Six lines. If your CLIJ2 macro doesn't have all six, something is wrong.

— end —
