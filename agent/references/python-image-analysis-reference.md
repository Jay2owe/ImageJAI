# Python Image Analysis Libraries Reference

Libraries: scikit-image 0.26, scipy.ndimage, numpy, tifffile 2025.1.10, Pillow 11.1, aicsimageio 3.3.1, matplotlib 3.10, imageio 2.37

---

## 1. scikit-image 0.26

> Docs: https://scikit-image.org/docs/stable/api/api.html

### 1.1 Module Overview

| Module | Purpose |
|--------|---------|
| `skimage.filters` | Thresholding, edge detection, smoothing |
| `skimage.segmentation` | Watershed, random walker, SLIC, clear_border |
| `skimage.morphology` | Erosion, dilation, opening, closing, structuring elements |
| `skimage.measure` | regionprops, label, contours, moments |
| `skimage.feature` | Blob detection, corner detection, template matching |
| `skimage.exposure` | Histogram eq, gamma, rescale_intensity |
| `skimage.transform` | Resize, rotate, warp, affine, Hough |
| `skimage.registration` | Phase cross-correlation, optical flow |
| `skimage.restoration` | Denoising, deconvolution, rolling ball |
| `skimage.io` | Image reading/writing (thin wrapper) |
| `skimage.color` | Color space conversions |
| `skimage.draw` | Drawing primitives |
| `skimage.graph` | Graph-based operations |
| `skimage.util` | dtype conversion, padding, noise |

### 1.2 New in 0.26

- `intensity_median` added to `regionprops`
- `coords_scaled` property (uses calibration)
- Binary morphology via `morphology.dilation()` etc. (not `binary_dilation` -- deprecated)

### 1.3 Thresholding & Filters

```python
from skimage import filters

# Thresholding
thresh = filters.threshold_otsu(image)          # Global
thresh = filters.threshold_li(image)            # Li's iterative
thresh = filters.threshold_triangle(image)      # Triangle
thresh = filters.threshold_yen(image)           # Yen
thresh = filters.threshold_isodata(image)       # ISODATA
thresh = filters.threshold_minimum(image)       # Minimum
binary = image > thresh

# Local/adaptive
local = filters.threshold_local(image, block_size=51, method='gaussian')
binary = image > local

# Compare all methods at once
results = filters.try_all_threshold(image, figsize=(10, 8), verbose=False)

# Smoothing
smoothed = filters.gaussian(image, sigma=2)
smoothed = filters.median(image, footprint=morphology.disk(3))

# Edge detection
edges = filters.sobel(image)
edges = filters.scharr(image)
edges = filters.laplace(image)
from skimage.feature import canny
edges = canny(image, sigma=1.5, low_threshold=0.1, high_threshold=0.2)

# Other
ridges = filters.frangi(image)                  # Vesselness
dog = filters.difference_of_gaussians(image, low_sigma=1, high_sigma=5)
enhanced = filters.unsharp_mask(image, radius=5, amount=2)
```

### 1.4 Segmentation

```python
from skimage.segmentation import (
    watershed, clear_border, random_walker, expand_labels,
    mark_boundaries, find_boundaries, chan_vese, felzenszwalb, slic
)
from scipy import ndimage as ndi
from skimage.feature import peak_local_max

# Watershed (most common for cell segmentation)
distance = ndi.distance_transform_edt(binary_mask)
coords = peak_local_max(distance, min_distance=20, labels=binary_mask)
mask = np.zeros(distance.shape, dtype=bool)
mask[tuple(coords.T)] = True
markers = ndi.label(mask)[0]
labels = watershed(-distance, markers, mask=binary_mask)

cleaned = clear_border(labels)
expanded = expand_labels(labels, distance=5)
overlay = mark_boundaries(image, labels, color=(1, 0, 0))
```

### 1.5 Morphology

```python
from skimage import morphology

# Structuring elements: disk, ball, square, cube, diamond, octahedron, rectangle
# Binary & greyscale morphology (unified API, replaces binary_ variants)
dilated = morphology.dilation(binary, footprint=morphology.disk(3))
eroded = morphology.erosion(binary, footprint=morphology.disk(3))
opened = morphology.opening(binary, footprint=morphology.disk(3))
closed = morphology.closing(binary, footprint=morphology.disk(3))

# Size filtering
cleaned = morphology.remove_small_objects(labels, min_size=64)
cleaned = morphology.remove_small_holes(binary, area_threshold=64)

# Skeletonization
skeleton = morphology.skeletonize(binary)
skeleton = morphology.skeletonize_3d(binary_3d)

# Reconstruction / area operations
recon = morphology.reconstruction(seed, image, method='dilation')
area_opened = morphology.area_opening(image, area_threshold=64)

from skimage.measure import label
labels, num = label(binary, return_num=True)
```

### 1.6 Measure (regionprops)

```python
from skimage.measure import regionprops, regionprops_table, label, find_contours, profile_line
import pandas as pd

labels, n = label(binary, return_num=True)
props = regionprops(labels, intensity_image=original)

# As pandas DataFrame (preferred)
table = regionprops_table(labels, intensity_image=original,
    properties=['label', 'area', 'centroid', 'eccentricity',
                'intensity_mean', 'intensity_median', 'perimeter',
                'solidity', 'axis_major_length', 'axis_minor_length'])
df = pd.DataFrame(table)

# Custom properties
def intensity_cv(region, intensity_image):
    return region.intensity_std / region.intensity_mean
table = regionprops_table(labels, intensity_image=original,
    properties=['label', 'area'], extra_properties=[intensity_cv])

# Contours and profiles
contours = find_contours(binary, level=0.5)     # list of Nx2 (row,col) arrays
profile = profile_line(image, src=(0, 0), dst=(100, 100), linewidth=3)
```

### 1.7 Feature Detection

```python
from skimage.feature import (
    peak_local_max, blob_log, blob_dog, blob_doh,
    corner_harris, corner_peaks, match_template,
    graycomatrix, graycoprops, multiscale_basic_features
)

# Peak detection (for watershed seeds)
coords = peak_local_max(image, min_distance=10, threshold_abs=100, num_peaks=500)

# Blob detection — returns Nx3 (row, col, sigma); radius ~ sqrt(2)*sigma
blobs = blob_log(image, min_sigma=1, max_sigma=30, num_sigma=10, threshold=0.1)

# Template matching
result = match_template(image, template, pad_input=True)

# Texture (GLCM)
glcm = graycomatrix(image, distances=[1, 3], angles=[0, np.pi/4], levels=256)
contrast = graycoprops(glcm, 'contrast')        # also: homogeneity, energy, correlation
```

### 1.8 Exposure

```python
from skimage import exposure

# Rescale intensity (DISPLAY ONLY for scientific data!)
rescaled = exposure.rescale_intensity(image, in_range=(p2, p98))

# CLAHE
equalized = exposure.equalize_adapthist(image, clip_limit=0.03)

# Gamma
adjusted = exposure.adjust_gamma(image, gamma=0.5)   # <1 brightens

# Histogram matching
matched = exposure.match_histograms(source, reference)

p2, p98 = np.percentile(image, (2, 98))
```

### 1.9 Transform

```python
from skimage import transform

resized = transform.resize(image, (512, 512), preserve_range=True)
rescaled = transform.rescale(image, scale=0.5, preserve_range=True)
rotated = transform.rotate(image, angle=45, resize=True, preserve_range=True)
# IMPORTANT: preserve_range=True keeps original intensity values

# Hough
h, theta, d = transform.hough_line(edges)
hough_res = transform.hough_circle(edges, np.arange(20, 50, 2))

# Pyramid
pyramid = list(transform.pyramid_gaussian(image, max_layer=4, downscale=2))
```

### 1.10 Registration

```python
from skimage.registration import phase_cross_correlation, optical_flow_tvl1

shift, error, phasediff = phase_cross_correlation(reference, moving, upsample_factor=10)
from scipy.ndimage import shift as ndi_shift
registered = ndi_shift(moving, shift)

flow = optical_flow_tvl1(reference, moving)   # flow[0]=row, flow[1]=col displacement
```

### 1.11 Restoration

```python
from skimage.restoration import (
    denoise_bilateral, denoise_tv_chambolle, denoise_wavelet, denoise_nl_means,
    estimate_sigma, rolling_ball, richardson_lucy, wiener
)

sigma_est = estimate_sigma(image)
denoised = denoise_nl_means(image, h=1.15*sigma_est, patch_size=5, fast_mode=True)

background = rolling_ball(image, radius=50)
foreground = image - background

deconvolved = richardson_lucy(image, psf, num_iter=30)
```

---

## 2. scipy.ndimage

> Docs: https://docs.scipy.org/doc/scipy/reference/ndimage.html

### 2.1 Filters

```python
from scipy.ndimage import (
    gaussian_filter, median_filter, minimum_filter, maximum_filter,
    uniform_filter, convolve, rank_filter, percentile_filter
)

smoothed = gaussian_filter(image, sigma=2)
smoothed = gaussian_filter(stack, sigma=(1, 2, 2))    # anisotropic for z-stacks
filtered = median_filter(image, size=5)
local_min = minimum_filter(image, size=10)
local_max = maximum_filter(image, size=10)
```

### 2.2 Morphology & Distance

```python
from scipy.ndimage import (
    binary_dilation, binary_erosion, binary_opening, binary_closing,
    binary_fill_holes, generate_binary_structure, iterate_structure,
    distance_transform_edt, grey_dilation, white_tophat
)

struct = generate_binary_structure(2, 1)       # 2D cross
struct3d = generate_binary_structure(3, 1)     # 3D face connectivity
big_struct = iterate_structure(struct, 3)

filled = binary_fill_holes(binary)
distances = distance_transform_edt(binary)
distances = distance_transform_edt(binary, sampling=(0.5, 0.325, 0.325))  # calibrated
```

### 2.3 Measurements

```python
from scipy.ndimage import (
    label, find_objects, sum, mean, variance, standard_deviation,
    minimum, maximum, median, center_of_mass, labeled_comprehension, value_indices
)

labels, n_features = label(binary)
slices = find_objects(labels)       # list of (row_slice, col_slice) tuples
means = mean(image, labels, index=range(1, n_features+1))
coms = center_of_mass(image, labels, index=range(1, n_features+1))
idx = value_indices(labels)         # dict: label -> (row_array, col_array)
```

### 2.4 Interpolation

```python
from scipy.ndimage import shift, rotate, zoom, affine_transform, map_coordinates

shifted = shift(image, shift=(2.5, -1.3), order=3)
rotated = rotate(image, angle=45, reshape=True, order=3)
zoomed = zoom(stack, zoom=(1, 2, 2), order=1)   # keep Z, double XY
values = map_coordinates(image, coords, order=3)
```

### 2.5 scipy.ndimage vs skimage

| Task | scipy.ndimage | skimage | Use |
|------|---------------|---------|-----|
| Region properties | `find_objects` + manual | `regionprops_table` | **skimage** |
| Distance transform | `distance_transform_edt` | N/A | **scipy** |
| Fill holes | `binary_fill_holes` | N/A | **scipy** |
| Thresholding | N/A | `filters.threshold_*` | **skimage** |
| Watershed | N/A | `segmentation.watershed` | **skimage** |
| Simple interpolation | `shift`, `rotate`, `zoom` | `transform.warp` | scipy simple, skimage complex |

### 2.6 scipy.signal & FFT

```python
from scipy import signal
from scipy.fft import fft, fft2, fftfreq, fftshift, ifft2

# 1D power spectrum
spectrum = fft(signal_1d)
power = np.abs(spectrum)**2

# 2D spatial frequency
ft_shifted = fftshift(fft2(image))
magnitude = np.log1p(np.abs(ft_shifted))

# Welch PSD (circadian / time-series)
freqs, psd = signal.welch(time_series, fs=sampling_rate, nperseg=256)

# Peak finding
peaks, properties = signal.find_peaks(profile, height=100, distance=10, prominence=50)
```

---

## 3. NumPy for Images

### 3.1 Array Basics

```python
import numpy as np

# Type conversion (CRITICAL)
img_float = image.astype(np.float64) / 65535.0   # 16-bit to [0,1]
img_16 = (image_float * 65535).astype(np.uint16)
# CAUTION: astype truncates without clipping -- use np.clip first if needed

# Reshape
transposed = stack.transpose(1, 2, 0)            # (Z,Y,X) -> (Y,X,Z)
expanded = image[np.newaxis, ...]                 # add axis
stack = np.stack([img1, img2, img3], axis=0)
channels = [stack[c] for c in range(stack.shape[0])]
```

### 3.2 Masking

```python
mask = (image > low) & (image < high)
mean_fg = image[mask].mean()
masked = np.where(mask, image, 0)
clipped = np.clip(image, 100, 50000)
```

### 3.3 Statistics

```python
hist, bin_edges = np.histogram(image, bins=256, range=(0, 65535))
mean, std, median = np.mean(image), np.std(image), np.median(image)
p2, p98 = np.percentile(image, [2, 98])
max_proj = np.max(stack, axis=0)                  # MIP along Z

# Normalisation (DISPLAY ONLY -- destroys data for measurement!)
normalised = (image - image.min()) / (image.max() - image.min())
```

### 3.4 Memory-Efficient Patterns

```python
# Memory-mapped arrays
mmap = np.memmap('large_stack.dat', dtype=np.uint16, mode='r', shape=(100, 2048, 2048))

# In-place operations (no temporary array)
np.subtract(image, background, out=image)

# Views vs copies
roi = image[100:200, 100:200]              # VIEW -- shares memory
roi_copy = image[100:200, 100:200].copy()  # COPY -- independent

print(f"{image.nbytes / 1e6:.1f} MB")
```

---

## 4. tifffile 2025.1.10

> Docs: https://github.com/cgohlke/tifffile

### 4.1 Reading

```python
import tifffile

image = tifffile.imread('image.tif')                   # numpy array
stack = tifffile.imread('stack.tif')                    # all pages as 3D
image = tifffile.imread('big.tif', key=range(0, 10))   # first 10 pages

with tifffile.TiffFile('image.tif') as tif:
    print(len(tif.pages), tif.pages[0].shape, tif.pages[0].dtype)
    for series in tif.series:
        print(series.shape, series.axes)               # e.g. (10,3,512,512) 'ZCYX'
        data = series.asarray()
```

### 4.2 Metadata

```python
with tifffile.TiffFile('image.tif') as tif:
    if tif.is_imagej:
        ij_meta = tif.imagej_metadata
        # Keys: spacing, unit, min, max, Info, Labels, Ranges, LUTs
    if tif.is_ome:
        ome_xml = tif.ome_metadata         # XML string

    page = tif.pages[0]
    xres = page.tags['XResolution'].value  # (numerator, denominator)
    pixel_size = xres[1] / xres[0]         # unit per pixel
```

### 4.3 Memory-Mapped & Zarr

```python
mmap = tifffile.memmap('large.tif', mode='r')   # uncompressed only

store = tifffile.imread('large.tif', aszarr=True)
import zarr
z = zarr.open(store, mode='r')
slice_10 = z[10]                                 # lazy read
```

### 4.4 Writing

```python
tifffile.imwrite('output.tif', image, compression='zlib')
tifffile.imwrite('big.tif', stack, bigtiff=True)

# ImageJ-compatible hyperstack
tifffile.imwrite('hyperstack.tif', stack, imagej=True,
    resolution=(1/0.325, 1/0.325),
    metadata={'spacing': 0.5, 'unit': 'um', 'axes': 'TZCYX'})

# OME-TIFF
tifffile.imwrite('output.ome.tif', stack, ome=True, photometric='minisblack',
    metadata={'axes': 'TCZYX', 'Channel': {'Name': ['GFP', 'DAPI']},
              'PhysicalSizeX': 0.325, 'PhysicalSizeXUnit': 'um',
              'PhysicalSizeZ': 0.5, 'PhysicalSizeZUnit': 'um'})

# Streaming write (low memory)
with tifffile.TiffWriter('output.tif', bigtiff=True) as tif:
    for frame in generate_frames():
        tif.write(frame, contiguous=True)

# Tiled / pyramidal
tifffile.imwrite('tiled.tif', image, tile=(256, 256), compression='zlib')
tifffile.imwrite('pyramid.tif', [full_res, half_res, quarter_res],
    subifds=[2], tile=(256, 256))
```

---

## 5. Pillow 11.1

```python
from PIL import Image, ImageDraw, ImageFont

img = Image.open('photo.png')          # size=(W,H), mode='L'/'RGB'/'RGBA'
arr = np.array(img)                    # PIL -> NumPy
img = Image.fromarray(arr.astype(np.uint8))  # NumPy -> PIL

resized = img.resize((256, 256), resample=Image.LANCZOS)
cropped = img.crop((left, upper, right, lower))
grey = img.convert('L')

draw = ImageDraw.Draw(img)
draw.rectangle([10, 10, 100, 100], outline='red', width=2)
draw.text((10, 10), "Label", fill='white')
img.save('output.png')
```

---

## 6. aicsimageio 3.3.1

**NOTE:** v3.3 uses STCZYX (6D). v4 uses TCZYX (5D) with xarray.

```python
from aicsimageio import AICSImage

img = AICSImage('/path/to/file.nd2')   # also .czi, .lif, .ome.tif
print(img.shape)                       # 6D (S,T,C,Z,Y,X)
data = img.get_image_data("ZYX", S=0, T=0, C=0)
dask_data = img.get_image_dask_data("ZYX", S=0, T=0, C=0)  # lazy
pixel_sizes = img.get_physical_pixel_size()  # (Z, Y, X) in microns
```

| Feature | aicsimageio 3.3 | Bio-Formats (Java) |
|---------|-----------------|---------------------|
| Formats | .nd2, .czi, .lif, .tif | 150+ formats |
| Language | Pure Python | Java (JVM) |
| Lazy loading | Yes (dask) | Limited |
| Install | `pip install aicsimageio[nd2]` | Complex |

---

## 7. matplotlib for Microscopy

### 7.1 Display

```python
import matplotlib.pyplot as plt
import matplotlib.colors as mcolors
from matplotlib.colors import LinearSegmentedColormap

fig, ax = plt.subplots(figsize=(8, 8))
p2, p98 = np.percentile(image, [2, 98])
ax.imshow(image, cmap='gray', vmin=p2, vmax=p98)
ax.axis('off')

# Custom fluorescence LUT
green_lut = LinearSegmentedColormap.from_list('green', ['black', 'green'])
ax.imshow(image, cmap=green_lut)

# Log scale
ax.imshow(image, cmap='gray', norm=mcolors.LogNorm(vmin=1, vmax=image.max()))

# Colorbar
im = ax.imshow(image, cmap='viridis')
plt.colorbar(im, ax=ax, label='Intensity (AU)', fraction=0.046, pad=0.04)
```

Recommended cmaps: `gray`, `inferno`, `viridis`, `magma`, `cividis` (colorblind-safe)

### 7.2 Multi-Channel Composite

```python
def make_composite(channels, colors, vmin=None, vmax=None):
    """Merge fluorescence channels into RGB composite."""
    composite = np.zeros((*channels[0].shape, 3), dtype=np.float64)
    color_map = {'red': [1,0,0], 'green': [0,1,0], 'blue': [0,0,1],
                 'cyan': [0,1,1], 'magenta': [1,0,1], 'yellow': [1,1,0]}
    for ch, col in zip(channels, colors):
        ch_float = ch.astype(np.float64)
        lo = vmin if vmin is not None else ch_float.min()
        hi = vmax if vmax is not None else ch_float.max()
        ch_norm = np.clip((ch_float - lo) / (hi - lo + 1e-10), 0, 1)
        for i in range(3):
            composite[:, :, i] += ch_norm * color_map[col][i]
    return np.clip(composite, 0, 1)

# Segmentation overlay
from skimage.color import label2rgb
overlay = label2rgb(labels, image=image, bg_label=0, alpha=0.3)
```

### 7.3 Publication Settings

```python
plt.rcParams.update({
    'font.family': 'Arial', 'font.size': 10,
    'axes.linewidth': 1, 'figure.dpi': 150,
    'savefig.dpi': 300, 'savefig.bbox': 'tight', 'savefig.transparent': True
})
plt.savefig('figure.pdf', format='pdf')      # vector
plt.savefig('figure.png', dpi=600)           # raster
```

---

## 8. imageio 2.37

```python
import imageio.v3 as iio

image = iio.imread('image.png')
iio.imwrite('output.png', image)

# Video
frames = iio.imread('video.mp4')             # (T, H, W, C)
iio.imwrite('output.mp4', frames, fps=30)

# GIF
iio.imwrite('animation.gif', frames, duration=50, loop=0)

# Memory-efficient iteration
for frame in iio.imiter('video.mp4'):
    process(frame)
```

---

## Colocalization in scikit-image

```python
from skimage.measure import (
    pearson_corr_coeff, manders_coloc_coeff, manders_overlap_coeff, intersection_coeff
)

pcc, p_value = pearson_corr_coeff(channel1, channel2)

mask_a = channel1 > threshold_a
mask_b = channel2 > threshold_b
m1 = manders_coloc_coeff(channel1, mask_b)   # fraction of ch1 in ch2 mask
m2 = manders_coloc_coeff(channel2, mask_a)   # fraction of ch2 in ch1 mask
moc = manders_overlap_coeff(channel1, channel2)
ic = intersection_coeff(mask_a, mask_b)
```

---

## Complete regionprops Properties (61 total)

**Geometric (no intensity image needed):**
`area`, `area_bbox`, `area_convex`, `area_filled`, `axis_major_length`,
`axis_minor_length`, `bbox`, `centroid`, `centroid_local`, `coords`, `coords_scaled`,
`eccentricity`, `equivalent_diameter_area`, `euler_number`, `extent`,
`feret_diameter_max`, `image`, `image_convex`, `image_filled`,
`inertia_tensor`, `inertia_tensor_eigvals`, `label`, `moments`,
`moments_central`, `moments_hu`, `moments_normalized`, `num_pixels`,
`orientation`, `perimeter`, `perimeter_crofton`, `slice`, `solidity`

**Intensity (requires `intensity_image`):**
`intensity_max`, `intensity_mean`, `intensity_median` (new in 0.26),
`intensity_min`, `intensity_std`, `image_intensity`,
`moments_weighted`, `moments_weighted_central`, `moments_weighted_hu`,
`moments_weighted_normalized`, `centroid_weighted`, `centroid_weighted_local`

---

## roifile -- ImageJ ROI Transfer

```python
import roifile
from roifile import ImagejRoi, ROI_TYPE

# Read
rois = roifile.roiread('RoiSet.zip')
for roi in rois:
    print(roi.name, roi.roitype, roi.coordinates())

# Write ROIs from Python segmentation
rois = []
for region in regionprops(labels):
    minr, minc, maxr, maxc = region.bbox
    rois.append(ImagejRoi(roitype=ROI_TYPE.RECT,
        top=minr, left=minc, bottom=maxr, right=maxc, name=f'cell_{region.label}'))
roifile.roiwrite('detected_cells.zip', rois)
```

---

## Integration with ImageJAI Agent

### Data Flow

```
ImageJ (Fiji JVM)                    Python (agent)
     |--- get_pixels (TCP) -------> numpy array (via pixels.py)
     |--- get_results_table (TCP) -> pandas DataFrame
     |--- capture_image (TCP) -----> PNG file -> Read tool
     |<-- execute_macro (TCP) ------ macro code string
     |<-- run_script (TCP) --------- Groovy/Jython code
```

### When to Use Python vs ImageJ

| Task | Winner | Why |
|------|--------|-----|
| Threshold comparison | **Python** | `try_all_threshold()` -- one call |
| Object measurement | **Python** | 61 properties, DataFrame output |
| Colocalization | **Python** | Simpler, scriptable |
| Statistical tests | **Python** | scipy.stats |
| Publication plots | **Python** | matplotlib |
| Interactive segmentation | **ImageJ** | Weka, Labkit, manual ROI |
| GPU processing | **ImageJ** | CLIJ2 (504 commands) |
| 3D rendering | **ImageJ** | 3Dscript, 3D Viewer |
| Batch file processing | **Python** | glob + tifffile |
| Deep learning seg | Both | StarDist/Cellpose available in both |

### Getting Pixels into Python

```python
# Via TCP (running ImageJ session)
import base64, numpy as np
raw = base64.b64decode(result['data'])
arr = np.frombuffer(raw, dtype=np.float32).reshape(result['height'], result['width'])

# Direct file read (no ImageJ needed)
import tifffile
stack = tifffile.imread('image.tif')

# Vendor formats
from aicsimageio import AICSImage
data = AICSImage('image.nd2').get_image_data('ZYX', C=0)
```

---

## Complete Microscopy Workflow Example

```python
import numpy as np
import tifffile
from skimage import filters, morphology, measure, segmentation
from skimage.feature import peak_local_max
from scipy import ndimage as ndi
import pandas as pd

stack = tifffile.imread('experiment.tif')
mip = np.max(stack, axis=0)

from skimage.restoration import rolling_ball
corrected = mip.astype(np.float64) - rolling_ball(mip.astype(np.float64), radius=50)

smoothed = filters.gaussian(corrected, sigma=2)
binary = smoothed > filters.threshold_otsu(smoothed)
binary = morphology.opening(binary, footprint=morphology.disk(2))
binary = ndi.binary_fill_holes(binary)
binary = morphology.remove_small_objects(binary, min_size=100)

distance = ndi.distance_transform_edt(binary)
coords = peak_local_max(distance, min_distance=15, labels=binary)
mask = np.zeros(distance.shape, dtype=bool)
mask[tuple(coords.T)] = True
labels = segmentation.watershed(-distance, ndi.label(mask)[0], mask=binary)
labels = segmentation.clear_border(labels)

df = pd.DataFrame(measure.regionprops_table(labels, intensity_image=mip,
    properties=['label', 'area', 'centroid', 'eccentricity',
                'intensity_mean', 'intensity_median', 'perimeter', 'solidity']))
df = df[(df['area'] > 50) & (df['area'] < 5000) & (df['solidity'] > 0.7)]
df.to_csv('measurements.csv', index=False)
tifffile.imwrite('labels.tif', labels.astype(np.uint16), imagej=True)
```
