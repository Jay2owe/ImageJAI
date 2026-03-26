# Image Registration & Stitching Reference

API reference for registration/stitching in Fiji and Python.

---

## 1. Grid/Collection Stitching

> Plugins > Stitching > Grid/Collection stitching

### Grid Types (`type=`)

| Index | Type String | Description |
|-------|-------------|-------------|
| 0 | `Grid: row-by-row` | Regular grid, sequential rows |
| 1 | `Grid: column-by-column` | Regular grid, sequential columns |
| 2 | `Grid: snake by rows` | Serpentine row scanning |
| 3 | `Grid: snake by columns` | Serpentine column scanning |
| 4 | `Filename defined position` | Positions from `{xxx}` `{yyy}` in filenames |
| 5 | `Unknown position` | Compute from overlap |
| 6 | `Positions from file` | TileConfiguration.txt or metadata |
| 7 | `Sequential Images` | Pairwise sequential |

### Order Options

| Grid Type | Valid Orders |
|-----------|-------------|
| Row-by-row / Snake rows | `Right & Down`, `Left & Down`, `Right & Up`, `Left & Up` |
| Column / Snake columns | `Down & Right`, `Down & Left`, `Up & Right`, `Up & Left` |
| Filename defined | `Defined by filename` |
| Unknown / Sequential | `All files in directory` |
| Positions from file | `Defined by TileConfiguration`, `Defined by image metadata` |

### Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `grid_size_x` / `grid_size_y` | Int | 3 | Tiles in X/Y |
| `tile_overlap` | Float | 20 | % overlap (typically 10-20) |
| `first_file_index_i` / `_x` / `_y` | Int | 0 | Starting index |
| `directory` | String | - | Tile directory path |
| `file_names` | String | - | Pattern (e.g. `tile_{iii}.tif`) |
| `output_textfile_name` | String | `TileConfiguration.txt` | Registration output |
| `fusion_method` | Choice | `Linear Blending` | See below |
| `regression_threshold` | Float | 0.30 | R-value threshold |
| `max/avg_displacement_threshold` | Float | 2.50 | Displacement ratio threshold |
| `absolute_displacement_threshold` | Float | 3.50 | Absolute displacement (px) |
| `compute_overlap` | Bool | true | Compute vs use given coords |
| `subpixel_accuracy` | Bool | true | Subpixel registration |
| `use_virtual_input_images` | Bool | false | Saves RAM |
| `computation_parameters` | Choice | - | `Save computation time (but use more RAM)` or `Save memory (but be slower)` |
| `image_output` | Choice | `Fuse and display` | `Fuse and display` or `Write to disk` |

**Fusion methods:** `Linear Blending`, `Max. Intensity`, `Min. Intensity`, `Average`, `Median`, `Do not fuse images`

### Filename Placeholders

`{i}` / `{ii}` / `{iii}` = sequential index (1/2/3 digits). `{x}`/`{xx}`/`{xxx}`, `{y}`/`{yy}`/`{yyy}` = position indices.

### Macro Examples

```javascript
// Row-by-row grid
run("Grid/Collection stitching",
    "type=[Grid: row-by-row] order=[Right & Down] " +
    "grid_size_x=4 grid_size_y=3 tile_overlap=15 " +
    "first_file_index_i=1 " +
    "directory=[/path/to/tiles] file_names=tile_{iii}.tif " +
    "output_textfile_name=TileConfiguration.txt " +
    "fusion_method=[Linear Blending] " +
    "regression_threshold=0.30 max/avg_displacement_threshold=2.50 " +
    "absolute_displacement_threshold=3.50 " +
    "compute_overlap subpixel_accuracy " +
    "image_output=[Fuse and display]");

// From TileConfiguration file
run("Grid/Collection stitching",
    "type=[Positions from file] order=[Defined by TileConfiguration] " +
    "directory=[/path/to/tiles] layout_file=TileConfiguration.txt " +
    "fusion_method=[Linear Blending] regression_threshold=0.30 " +
    "max/avg_displacement_threshold=2.50 absolute_displacement_threshold=3.50 " +
    "compute_overlap subpixel_accuracy image_output=[Fuse and display]");
```

### TileConfiguration.txt Format

```
dim = 2
tile_001.tif; ; (0.0, 0.0)
tile_002.tif; ; (512.0, 0.0)
tile_003.tif; ; (1024.0, 0.0)
```

After stitching, `TileConfiguration.registered.txt` is written with refined positions.

---

## 2. Pairwise Stitching

> Plugins > Stitching > Pairwise stitching

Registers exactly two images via phase correlation.

```javascript
run("Pairwise stitching",
    "first_image=left.tif second_image=right.tif " +
    "fusion_method=[Linear Blending] fused_image=result " +
    "check_peaks=5 compute_overlap subpixel_accuracy");
```

Key parameters: `check_peaks` (default 5), `registration_channel_image_1`/`_2` for multi-channel, manual offsets `x`/`y`/`z` if `compute_overlap` is off.

---

## 3. StackReg / TurboReg

> Plugins > Registration > StackReg / TurboReg (BIG, EPFL)

### StackReg

Registers all slices to first slice. Modifies stack in-place.

| Transform | DOF | Macro |
|-----------|-----|-------|
| `Translation` | 2 | `run("StackReg", "transformation=Translation");` |
| `Rigid Body` | 3 | `run("StackReg", "transformation=[Rigid Body]");` |
| `Scaled Rotation` | 4 | `run("StackReg", "transformation=[Scaled Rotation]");` |
| `Affine` | 6 | `run("StackReg", "transformation=Affine");` |

**Note:** StackReg does NOT support Bilinear (TurboReg does, but StackReg cannot propagate it).

**Variants:** HyperStackReg (multi-channel hyperstacks), MultiStackReg (register one channel, apply to all), pystackreg (Python port, section 16).

### TurboReg

Low-level registration engine. Registers one image to another.

| Transform | Landmarks | DOF |
|-----------|-----------|-----|
| `translation` | 1 pair | 2 |
| `rigidBody` | 3 pairs | 3 |
| `scaledRotation` | 2 pairs | 4 |
| `affine` | 3 pairs | 6 |
| `bilinear` | 4 pairs | 8 |

**-align mode** (compute registration, output landmarks to Results):
```javascript
// Translation: 1 landmark pair (center of image)
run("TurboReg ",
    "-align -window source_title 0 0 " + (w-1) + " " + (h-1) + " "
    + "-window target_title 0 0 " + (w-1) + " " + (h-1) + " "
    + "-translation " + (w/2) + " " + (h/2) + " " + (w/2) + " " + (h/2) + " "
    + "-hideOutput");
```

**-transform mode** (apply known landmarks):
```javascript
run("TurboReg ",
    "-transform -window source_title " + w + " " + h + " "
    + "-translation " + srcX + " " + srcY + " " + tgtX + " " + tgtY + " "
    + "-showOutput");
```

**Extracting transforms from Results:**
```javascript
sourceX0 = getResult("sourceX", 0);
sourceY0 = getResult("sourceY", 0);
targetX0 = getResult("targetX", 0);
targetY0 = getResult("targetY", 0);
dx = targetX0 - sourceX0;
dy = targetY0 - sourceY0;
```

**Gotcha:** The macro command is `run("TurboReg ", ...)` with a trailing space.

---

## 4. Linear Stack Alignment with SIFT

> Plugins > Registration > Linear Stack Alignment with SIFT

Feature-based stack registration. More robust than TurboReg for large inter-frame changes.

```javascript
run("Linear Stack Alignment with SIFT",
    "initial_gaussian_blur=1.60 steps_per_scale_octave=3 " +
    "minimum_image_size=64 maximum_image_size=1024 " +
    "feature_descriptor_size=4 feature_descriptor_orientation_bins=8 " +
    "closest/next_closest_ratio=0.92 maximal_alignment_error=25 " +
    "inlier_ratio=0.05 expected_transformation=Affine interpolate");

// Multi-channel variant
run("Linear Stack Alignment with SIFT MultiChannel",
    "registration_channel=1 " +
    "initial_gaussian_blur=1.60 steps_per_scale_octave=3 " +
    "minimum_image_size=64 maximum_image_size=1024 " +
    "feature_descriptor_size=4 feature_descriptor_orientation_bins=8 " +
    "closest/next_closest_ratio=0.92 maximal_alignment_error=25 " +
    "inlier_ratio=0.05 expected_transformation=Affine interpolate");
```

**Transforms:** `Translation`, `Rigid`, `Similarity`, `Affine`

### Tuning

| Issue | Adjust |
|-------|--------|
| Too few features | Decrease `closest/next_closest_ratio` (e.g. 0.80) |
| Alignment fails | Increase `maximal_alignment_error` or decrease `inlier_ratio` |
| Slow on large images | Decrease `maximum_image_size` |
| Miss fine features | Increase `steps_per_scale_octave` (e.g. 7) |
| Noisy images | Increase `initial_gaussian_blur` |

---

## 5. bUnwarpJ (Elastic Registration)

> Plugins > Registration > bUnwarpJ

Elastic (non-rigid) 2D registration using B-spline deformations.

**Modes:** `Accurate` (bidirectional, strict), `Fast` (bidirectional, relaxed), `Mono` (unidirectional)

**Deformation levels:** `Very Coarse` (1x1) → `Coarse` (2x2) → `Fine` (4x4) → `Very Fine` (8x8) → `Super Fine` (16x16)

### Macro Syntax

```javascript
run("bUnwarpJ",
    "source_image=moving.tif target_image=fixed.tif " +
    "registration=Accurate image_subsample_factor=0 " +
    "initial_deformation=[Very Coarse] final_deformation=Fine " +
    "divergence_weight=0 curl_weight=0 landmark_weight=0 " +
    "image_weight=1 consistency_weight=10 stop_threshold=0.01 " +
    "save_transformations " +
    "save_direct_transformation=/path/direct_transf.txt " +
    "save_inverse_transformation=/path/inverse_transf.txt");
```

### call() API for Transform I/O

```javascript
call("bunwarpj.bUnwarpJ_.loadElasticTransform", "/path/transform.txt", "target_title", "source_title");
call("bunwarpj.bUnwarpJ_.loadRawTransform", "/path/raw.txt", "target_title", "source_title");
call("bunwarpj.bUnwarpJ_.elasticTransformImageMacro", "source_title", "target_title", "/path/transform.txt", "/path/output.tif");
call("bunwarpj.bUnwarpJ_.convertToRawTransformation", "/path/elastic.txt", "/path/raw_output.txt", "target_title", "source_title");
call("bunwarpj.bUnwarpJ_.composeElasticTransformations", "/path/t1.txt", "/path/t2.txt", "/path/out.txt", "target_title", "source_title");
call("bunwarpj.bUnwarpJ_.composeRawTransformations", "/path/r1.txt", "/path/r2.txt", "/path/out.txt", "target_title", "source_title");
call("bunwarpj.bUnwarpJ_.compareOppositeElasticTransforms", "/path/inv.txt", "/path/direct.txt", "target_title", "source_title");
call("bunwarpj.bUnwarpJ_.adaptCoefficientsMacro", "/path/transform.txt", "2.0", "/path/adapted.txt", "target_title", "source_title");
```

### Tuning

| Goal | Adjust |
|------|--------|
| Smoother deformation | Increase `divergence_weight` / `curl_weight` (0.1-1.0) |
| Stricter consistency | Increase `consistency_weight` (20-30) |
| Faster | Use `Fast` mode, increase `image_subsample_factor` |
| More detail | `Super Fine` final deformation |
| Landmark-guided | Set `landmark_weight` > 0, place landmarks first |

**Gotcha:** Source and target must have identical dimensions.

---

## 6. BigWarp (Landmark Registration)

> Plugins > BigDataViewer > Big Warp Command

Interactive landmark-based deformable registration (Thin Plate Splines). Built on BigDataViewer.

**Transforms:** `Thin Plate Spline`, `Affine`, `Similarity`, `Rotation`, `Translation` (select with F2)

### Key Controls

| Key | Action |
|-----|--------|
| `Space` | Toggle landmark mode |
| `Left click` | Place/select landmark |
| `Ctrl + click` | "Pin" same point in both spaces |
| `Ctrl + S` / `Ctrl + O` | Save / Load landmarks |
| `Ctrl + E` | Export warped image |
| `F2` | Change transform type |
| `T` | Toggle warped/raw view |
| `Ctrl + Z` / `Ctrl + Y` | Undo / Redo |

### Landmark CSV Format

```csv
,Landmark-0,Landmark-1
moving-x,100.0,200.0
moving-y,150.0,250.0
target-x,102.5,198.3
target-y,148.1,252.7
```

For 3D, add `moving-z` and `target-z` rows.

**Note:** BigWarp is fully interactive -- the agent cannot place landmarks. Prepare images and guide the user.

---

## 7. Correct 3D Drift

> Plugins > Registration > Correct 3D drift

Corrects XYZ drift in time-lapse hyperstacks via phase correlation.

```javascript
run("Correct 3D drift", "channel=1");

// Full parameterisation
run("Correct 3D drift",
    "channel=2 multi_time_scale sub_pixel edge_enhance " +
    "lowest_z=5 highest_z=20 max_shift_x=20 max_shift_y=20 max_shift_z=5");
```

Key parameters: `only_compute` (drift vectors only), `use_virtualstack` (saves RAM), `min_pixel_value`.

**Alternative:** Fast4DReg (Plugins > Registration > Fast4DReg) -- faster, corrects XY and Z separately.

**Gotcha:** This is a Jython script -- consider increasing TCP timeout for large datasets.

---

## 8. Register Virtual Stack Slices

> Plugins > Registration > Register Virtual Stack Slices

Registers a directory of 2D images. Good for serial sections, histology.

**Models:** `Translation`, `Rigid`, `Similarity`, `Affine`, `Elastic` (bUnwarpJ), `Moving least squares`

Uses same SIFT parameters as section 4. Additional parameters: `reference_slice`, regularization (`shear`, `scaling`, `isotropy`, `lambda`). Elastic model adds bUnwarpJ parameters.

Input: directory of 2D images. Output: TIFF files with enlarged canvas.

---

## 9. Descriptor-based Registration (2D/3D)

> Plugins > Registration > Descriptor-based registration (2d/3d)

Registers images using detected point features (beads, spots). Designed for SPIM bead registration.

**2D models:** Translation, Rigid, Similarity, Affine, Homography
**3D models:** Translation, Rigid, Similarity, Affine

Key detection parameters: `detection_brightness` (Very low→Very strong), `detection_size` (2-10 px), `detection_type` (Minima/Maxima), `subpixel_localization`, `number_of_neighbors`, `redundancy`.

Matching: `RANSAC_threshold`, `num_iterations`, `regularization`, `lambda`.

---

## 10. scikit-image Registration

### phase_cross_correlation

Subpixel translation detection via Fourier phase correlation.

```python
from skimage.registration import phase_cross_correlation
from scipy.ndimage import shift as ndi_shift

result = phase_cross_correlation(reference, moving, upsample_factor=100)
# Returns: (shift, error, phasediff)
corrected = ndi_shift(moving, -result[0])
```

Parameters: `upsample_factor` (precision = 1/N pixel), `space` ('real'/'fourier'), `reference_mask`/`moving_mask`, `normalization` ('phase'/None).

### optical_flow_tvl1 (dense non-rigid)

```python
from skimage.registration import optical_flow_tvl1
from skimage.transform import warp

flow = optical_flow_tvl1(reference, moving)
nr, nc = reference.shape
row_coords, col_coords = np.meshgrid(np.arange(nr), np.arange(nc), indexing='ij')
warped = warp(moving, np.array([row_coords + flow[0], col_coords + flow[1]]), mode='nearest')
```

Parameters: `attachment=15`, `tightness=0.3`, `num_warp=5`, `num_iter=10`.

### optical_flow_ilk (Lucas-Kanade, dense)

```python
from skimage.registration import optical_flow_ilk
flow = optical_flow_ilk(reference, moving, radius=7, num_warp=10)
```

---

## 11. scikit-image Transforms

### Transform Classes

| Class | DOF | Key Parameters |
|-------|-----|----------------|
| `EuclideanTransform` | 3 | `rotation`, `translation` |
| `SimilarityTransform` | 4 | `scale`, `rotation`, `translation` |
| `AffineTransform` | 6 | `scale`, `rotation`, `shear`, `translation` |
| `ProjectiveTransform` | 8 | `matrix` |
| `PolynomialTransform` | var | `order` |
| `PiecewiseAffineTransform` | var | Triangulated piecewise affine |

All share: `from_estimate(src, dst)`, `inverse`, `params`, `residuals(src, dst)`.

### estimate_transform

```python
from skimage.transform import estimate_transform
tform = estimate_transform('affine', src_points, dst_points)
```

Types: `'euclidean'`, `'similarity'`, `'affine'`, `'piecewise-affine'`, `'projective'`, `'polynomial'`

### warp

```python
from skimage.transform import warp
warped = warp(image, tform.inverse, output_shape=image.shape,
              order=1, mode='constant', cval=0.0, preserve_range=False)
```

Modes: `'constant'`, `'edge'`, `'symmetric'`, `'reflect'`, `'wrap'`

---

## 12. scikit-image Feature Matching

### ORB + RANSAC Registration

```python
from skimage.feature import ORB, match_descriptors
from skimage.measure import ransac
from skimage.transform import AffineTransform, warp

orb = ORB(n_keypoints=500)
orb.detect_and_extract(image1)
kp1, desc1 = orb.keypoints, orb.descriptors
orb.detect_and_extract(image2)
kp2, desc2 = orb.keypoints, orb.descriptors

matches = match_descriptors(desc1, desc2, cross_check=True, max_ratio=0.8)
src = kp1[matches[:, 0]][:, ::-1]  # (row,col) → (x,y)
dst = kp2[matches[:, 1]][:, ::-1]

model, inliers = ransac((src, dst), AffineTransform,
                         min_samples=3, residual_threshold=2, max_trials=100)
warped = warp(image1, model.inverse, output_shape=image2.shape)
```

### RANSAC min_samples by model

| Model | min_samples |
|-------|-------------|
| Euclidean / Similarity | 2 |
| Affine | 3 |
| Projective | 4 |
| FundamentalMatrix | 8 |

### Other Detectors

```python
skimage.feature.corner_harris(image, method='k', k=0.05, sigma=1)
skimage.feature.corner_peaks(response, min_distance=1, num_peaks=inf)
skimage.feature.corner_subpix(image, corners, window_size=11)
skimage.feature.BRIEF(descriptor_size=256, patch_size=49)
```

---

## 13. SimpleITK Registration

> `pip install SimpleITK`

Intensity-based registration framework with multi-resolution optimization.

### Transforms

| Transform | DOF | Use Case |
|-----------|-----|----------|
| `TranslationTransform(dim)` | 2/3 | Translation only |
| `Euler2DTransform` / `Euler3DTransform` | 3/6 | Rigid |
| `Similarity2D/3DTransform` | 4/7 | Rigid + uniform scale |
| `AffineTransform(dim)` | 6/12 | Full affine |
| `BSplineTransform(dim, order)` | Variable | Non-rigid |
| `DisplacementFieldTransform(dim)` | Variable | Dense deformation |

### Metrics

```python
R = sitk.ImageRegistrationMethod()
R.SetMetricAsMeanSquares()                              # same modality
R.SetMetricAsCorrelation()                              # same modality, intensity variation
R.SetMetricAsMattesMutualInformation(numberOfHistogramBins=50)  # multi-modal
R.SetMetricAsANTSNeighborhoodCorrelation(radius=5)      # local, robust
R.SetMetricAsDemons(intensityDifferenceThreshold=0.001)  # deformable
```

### Optimizers

```python
# Gradient descent (most common)
R.SetOptimizerAsGradientDescent(learningRate=1.0, numberOfIterations=100,
    convergenceMinimumValue=1e-6, convergenceWindowSize=10)
# Regular step gradient descent
R.SetOptimizerAsRegularStepGradientDescent(learningRate=1.0, minStep=0.001,
    numberOfIterations=100, relaxationFactor=0.5)
# L-BFGS-B (for B-spline)
R.SetOptimizerAsLBFGSB(gradientConvergenceTolerance=1e-5, numberOfIterations=500,
    maximumNumberOfCorrections=5, maximumNumberOfFunctionEvaluations=2000)
# Powell (derivative-free)
R.SetOptimizerAsPowell(numberOfIterations=100, stepTolerance=1e-6, valueTolerance=1e-6)
```

Other optimizers: `GradientDescentLineSearch`, `ConjugateGradientLineSearch`, `LBFGS2`, `Amoeba`, `Exhaustive`, `OnePlusOneEvolutionary`.

### Configuration

```python
R.SetInterpolator(sitk.sitkLinear)        # or sitkNearestNeighbor, sitkBSpline
R.SetOptimizerScalesFromPhysicalShift()   # recommended
R.SetShrinkFactorsPerLevel([4, 2, 1])
R.SetSmoothingSigmasPerLevel([2, 1, 0])
R.SmoothingSigmasAreSpecifiedInPhysicalUnitsOn()
R.SetMetricSamplingStrategy(R.RANDOM)
R.SetMetricSamplingPercentage(0.01)
```

### Complete Example

```python
import SimpleITK as sitk

fixed = sitk.ReadImage("fixed.nii", sitk.sitkFloat32)
moving = sitk.ReadImage("moving.nii", sitk.sitkFloat32)

initial_transform = sitk.CenteredTransformInitializer(
    fixed, moving, sitk.Euler3DTransform(),
    sitk.CenteredTransformInitializerFilter.GEOMETRY)

R = sitk.ImageRegistrationMethod()
R.SetMetricAsMattesMutualInformation(50)
R.SetMetricSamplingStrategy(R.RANDOM)
R.SetMetricSamplingPercentage(0.01)
R.SetInterpolator(sitk.sitkLinear)
R.SetOptimizerAsGradientDescent(1.0, 200, 1e-6, 10)
R.SetOptimizerScalesFromPhysicalShift()
R.SetShrinkFactorsPerLevel([4, 2, 1])
R.SetSmoothingSigmasPerLevel([2, 1, 0])
R.SmoothingSigmasAreSpecifiedInPhysicalUnitsOn()
R.SetInitialTransform(initial_transform, inPlace=False)

final_transform = R.Execute(fixed, moving)
resampled = sitk.Resample(moving, fixed, final_transform, sitk.sitkLinear, 0.0, moving.GetPixelID())
sitk.WriteTransform(final_transform, "transform.tfm")
```

### B-Spline (Non-Rigid)

```python
bspline_transform = sitk.BSplineTransformInitializer(fixed, [8,8,8], order=3)
R.SetInitialTransformAsBSpline(bspline_transform, inPlace=True, scaleFactors=[1,2,4])
R.SetOptimizerAsLBFGSB(gradientConvergenceTolerance=1e-5, numberOfIterations=100)
final_bspline = R.Execute(fixed, moving)
```

---

## 14. OpenCV Registration & Stitching

> `pip install opencv-python` or `opencv-contrib-python`

### Feature Detectors

```python
cv2.SIFT_create(nfeatures=0, nOctaveLayers=3, contrastThreshold=0.04, edgeThreshold=10, sigma=1.6)
cv2.ORB_create(nfeatures=500, scaleFactor=1.2, nlevels=8, fastThreshold=20)
cv2.AKAZE_create(threshold=0.001, nOctaves=4, nOctaveLayers=4)

keypoints, descriptors = detector.detectAndCompute(gray_image, mask=None)
```

### Feature Matchers

```python
# BFMatcher: NORM_L2 for SIFT, NORM_HAMMING for ORB/AKAZE
bf = cv2.BFMatcher(cv2.NORM_L2)
matches = bf.knnMatch(desc1, desc2, k=2)
good = [m for m, n in matches if m.distance < 0.75 * n.distance]  # Lowe's ratio test

# FlannBasedMatcher for SIFT
flann = cv2.FlannBasedMatcher(dict(algorithm=1, trees=5), dict(checks=50))
```

### Transform Estimation

```python
# Homography (perspective, 4+ points)
H, mask = cv2.findHomography(src_pts, dst_pts, cv2.RANSAC, 5.0)
result = cv2.warpPerspective(img, H, (w, h))

# Affine (6 DOF, 3+ points)
M, inliers = cv2.estimateAffine2D(src_pts, dst_pts, method=cv2.RANSAC)
result = cv2.warpAffine(img, M, (w, h))

# Partial affine (4 DOF: translation + rotation + uniform scale)
M, inliers = cv2.estimateAffinePartial2D(src_pts, dst_pts, method=cv2.RANSAC)
```

**Methods:** `0` (least squares), `cv2.RANSAC`, `cv2.LMEDS`, `cv2.RHO` (findHomography only)

### Stitcher (High-Level)

```python
stitcher = cv2.Stitcher.create(mode=cv2.Stitcher_SCANS)  # SCANS for microscopy, PANORAMA for photos
status, result = stitcher.stitch(images)
# status: 0=OK, 1=need more imgs, 2=homography fail, 3=camera params fail
```

---

## 15. pystackreg

> `pip install pystackreg` — Python port of Fiji StackReg/TurboReg

### Transforms

| Constant | DOF | Note |
|----------|-----|------|
| `StackReg.TRANSLATION` | 2 | |
| `StackReg.RIGID_BODY` | 3 | |
| `StackReg.SCALED_ROTATION` | 4 | |
| `StackReg.AFFINE` | 6 | |
| `StackReg.BILINEAR` | 8 | Cannot use `reference='previous'` |

### Core API

```python
from pystackreg import StackReg

sr = StackReg(StackReg.RIGID_BODY)

# Single pair
tmat = sr.register(ref, mov)            # → (3,3) matrix
result = sr.transform(mov)              # apply stored transform
result = sr.register_transform(ref, mov) # register + transform

# Stack
tmats = sr.register_stack(stack, reference='previous')  # → (T,3,3)
result = sr.transform_stack(stack, tmats=tmats)
result = sr.register_transform_stack(stack, reference='previous')
```

**Reference strategies:** `'previous'` (default), `'first'`, `'mean'`

Additional: `n_frames` (frames to average for ref), `moving_average` (temporal smoothing), `axis` (time axis).

### Multi-Channel Example

```python
sr = StackReg(StackReg.RIGID_BODY)
tmats = sr.register_stack(stack[:, 0, :, :], reference='previous')  # register ch0
for ch in range(stack.shape[1]):
    stack[:, ch, :, :] = sr.transform_stack(stack[:, ch, :, :], tmats=tmats)
```

---

## 16. MIST (NIST Stitching)

> Plugins > MIST — optimised for large regular grids (100s-1000s of tiles)

Faster and more memory-efficient than Grid/Collection Stitching. Multi-threaded, FFTW-based phase correlation.

```javascript
run("MIST",
    "gridwidth=5 gridheight=4 starttile=1 " +
    "imagedir=[/path/to/tiles] filenamepattern=tile_{ppp}.tif " +
    "filenamepatterntype=SEQUENTIAL gridorigin=UL " +
    "numberingpattern=HORIZONTALCOMBING " +
    "outputpath=[/path/to/output] displaystitching=true " +
    "outputfullimage=true outputmeta=true blendingmode=OVERLAY");
```

**Pattern types:** `SEQUENTIAL`, `ROWCOL`
**Numbering:** `HORIZONTALCOMBING`, `HORIZONTALCONTINUOUS`, `VERTICALCOMBING`, `VERTICALCONTINUOUS`
**Blending:** `OVERLAY`, `LINEAR`, `MAX`, `MIN`, `AVERAGE`

---

## 17. GPU Registration (CLIJ2)

```javascript
run("CLIJ2 Macro Extensions", "cl_device=");
Ext.CLIJ2_push("stack");

// Drift correction
Ext.CLIJx_driftCorrectionByCenterOfMassFixation("input", "output");
Ext.CLIJx_driftCorrectionByCentroidFixation("input", "output");
Ext.CLIJx_translationTimelapseRegistration("input", "output");

// Affine/rigid transforms
Ext.CLIJ2_affineTransform2D("input", "output", "translateX=10 translateY=5 rotate=3");
Ext.CLIJ2_affineTransform3D("input", "output", "translateX=10 rotateY=45 scaleZ=2");
Ext.CLIJ2_rigidTransform("input", "output", "translateX=5 translateY=3 rotate=10");
```

Transform string params: `translateX/Y/Z`, `rotateX/Y/Z`/`rotate` (degrees), `scaleX/Y/Z`/`scale`, `shearXY/XZ/YZ`, `center`.

Consider GPU registration for >100 frames, >4000x4000 images, or batch processing.

---

## 18. Interactive Transform Tools

```javascript
run("Interactive Affine");
run("Interactive Rigid");
run("Interactive Similarity");
run("Interactive Perspective");
run("Interactive Moving Least Squares");
run("Interactive Thin Plate Spline");
run("Landmark Correspondences");    // uses point ROI pairs
```

---

## 19. Decision Tree

```
TILE MOSAIC (multiple fields of view)?
├── Regular grid → Grid/Collection Stitching (§1) or MIST for >50 tiles (§16)
├── Two images → Pairwise Stitching (§2)
│
TIME-LAPSE STACK needing alignment?
├── Small drift → StackReg (§3) or pystackreg (§15)
├── Large drift → SIFT Alignment (§4)
├── 3D + time → Correct 3D Drift (§7) or Fast4DReg
├── Non-rigid → optical_flow_tvl1 (§10)
│
TWO IMAGES needing alignment?
├── Feature-based: many features → SIFT/ORB (§12, §14)
├── Feature-based: beads/fiducials → Descriptor-based (§9)
├── Feature-based: manual landmarks → BigWarp (§6)
├── Intensity-based, same modality → phase_cross_correlation (§10)
├── Intensity-based, multi-modal → SimpleITK mutual information (§13)
├── Non-rigid → bUnwarpJ (§5) or SimpleITK B-spline
│
SERIAL SECTIONS? → Register Virtual Stack Slices (§8)
PANORAMA/SCAN? → OpenCV Stitcher (§14)
```

### Transform Model Selection

| Scenario | Model |
|----------|-------|
| Drift / stage jitter | Translation |
| Sample movement / rotation | Rigid / Euclidean |
| Different magnifications | Similarity |
| General linear distortion | Affine |
| Tilted sample | Projective / Homography |
| Tissue / elastic deformation | B-spline, TPS, bUnwarpJ |

### Time-Lapse Registration (Optimised for Organotypic Slices)

| Use Case | Method | Transform | Reference |
|----------|--------|-----------|-----------|
| Neuronal rhythms (SCN) | Downsampled phase corr + epoch | Translation | Median |
| Microglia (tissue) | Downsampled phase corr / anchor patches | Translation* | Median |
| Cell cultures | Downsampled phase corr + median ref | Translation | Median |
| Calcium imaging | Phase corr + temporal pre-smooth | Translation | Mean |

*Use Rigid if rotation detected. Downsample: 8x if >1024px wide, 4x for 256-1024px, skip if <256px.

**Workflow:** (1) Downsample 4x + phase correlation scan on all frames (~1s/335 frames). (2) Classify drift pattern: sparse jumps → epoch translation; continuous creep → smooth shifts; chaotic → SIFT fallback for >50px shifts.

---

## 20. Multi-Channel Registration Strategy

```javascript
// Method 1: SIFT MultiChannel (built-in, registers on chosen channel)
run("Linear Stack Alignment with SIFT MultiChannel",
    "registration_channel=1 expected_transformation=Rigid interpolate ...");

// Method 2: Split → register → apply (needs MultiStackReg or pystackreg)
run("Split Channels");
selectWindow("C1-image.tif");
run("StackReg", "transformation=Translation");
```

```python
# Method 3: pystackreg (most flexible)
sr = StackReg(StackReg.RIGID_BODY)
tmats = sr.register_stack(stack[:, 0, :, :], reference='previous')
for ch in range(stack.shape[1]):
    stack[:, ch, :, :] = sr.transform_stack(stack[:, ch, :, :], tmats=tmats)
```

---

## 21. Cross-Tool Workflows

### Parse TileConfiguration in Python

```python
import re, tifffile
positions = {}
with open("TileConfiguration.registered.txt") as f:
    for line in f:
        m = re.match(r'(\S+);\s*;\s*\(([^)]+)\)', line)
        if m:
            positions[m.group(1)] = tuple(float(x) for x in m.group(2).split(','))
```

### Fiji → Python transform transfer

```python
from skimage.transform import AffineTransform, warp
tform = AffineTransform(translation=(dx, dy))  # from Fiji Results table
warped = warp(image, tform.inverse, preserve_range=True)
```

### Batch Registration Macro

```javascript
dir = "/path/to/data/";
list = getFileList(dir);
for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif")) {
        open(dir + list[i]);
        run("StackReg", "transformation=[Rigid Body]");
        saveAs("Tiff", dir + "registered/" + list[i]);
        close();
    }
}
```

---

## 22. Gotchas

### Stitching
- Phase correlation typically fails below ~10% overlap; consider 15-20%
- Lower `regression_threshold` (e.g. 0.10) if tiles won't register; raise it if bad tiles register
- `{iii}` = 3-digit zero-padded; must match filenames exactly
- Modern microscopes typically use snake/serpentine scanning; check acquisition software
- Some .nd2/.czi/.lif files embed stage coords; try `Positions from file` + `Defined by image metadata` first
- Registered images often have black borders; crop with `run("Auto Crop");`

### Registration
- **StackReg modifies in-place** — duplicate first if you need the original
- **SIFT fails on featureless images** — use phase correlation instead
- **TurboReg trailing space** — command is `run("TurboReg ", ...)` not `run("TurboReg", ...)`
- **bUnwarpJ needs same-size images** — resize or pad first
- **StackReg + Bilinear** — not supported (only TurboReg supports Bilinear)
- Register on the channel with the best features (typically DAPI or bright structures)

### Performance
- CLIJ2 GPU registration is typically 10-100x faster for translation correction on large datasets
- MIST outperforms Grid/Collection Stitching for >50 tiles
- Register Virtual Stack Slices processes from disk without loading all into RAM
- pystackreg is typically faster than Fiji StackReg for Python-side processing

---

## 23. Installed Plugin Inventory

| Plugin | Version | Purpose |
|--------|---------|---------|
| Stitching_ | 3.1.9 | Grid/Collection + Pairwise |
| MIST_ | - | NIST large-grid stitching |
| bigwarp_fiji | 9.3.1 | Interactive landmark registration |
| bUnwarpJ_ | 2.6.13 | Elastic B-spline registration |
| Descriptor_based_registration | 2.1.8 | Feature-based 2D/3D |
| SPIM_Registration | 5.0.26 | Light-sheet multi-view |
| registration_3d | 2.0.1 | 3D GPU (CLIJx) |
| TransformJ_ | - | Affine/rotate/scale/translate |
| Correct_3D_Drift | 1.0.7 | Z-stack drift correction |
| mpicbg_ | - | SIFT, StackReg, feature matching |

### Not Installed (Available via Update Sites)

| Update Site | Plugin | Use Case |
|-------------|--------|----------|
| BigStitcher | BigStitcher | Terabyte-scale tiled datasets |
| ElastixWrapper | elastix | Automated atlas registration (for ABBA) |
| PTBIOP | ABBA tools | Brain atlas registration |
| MultiStackReg | MultiStackReg | Apply one stack's transforms to another |
