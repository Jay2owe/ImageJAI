# Spatial Statistics & Point Pattern Analysis Reference

Reference for spatial point pattern analysis in microscopy. For ImageJ-side 3D
spatial plugins, see `3d-spatial-reference.md`. For pixel-level colocalization,
see `colocalization-reference.md`.

Methodology sources: Clark & Evans (1954) nearest-neighbor test; Ripley (1976,
1977) second-order K/L functions with isotropic edge correction; Diggle (2003)
*Statistical Analysis of Spatial and Spatio-Temporal Point Patterns*; Baddeley,
Rubak & Turner (2015) *Spatial Point Patterns: Methodology and Applications with
R*; Ester et al. (1996) DBSCAN; Campello et al. (2013) HDBSCAN; Ankerst et al.
(1999) OPTICS; Voronoi (1908) tessellation; Moran (1950) `I` and Getis-Ord
(1992) `Gi*` autocorrelation; Myllymaki et al. (2017) global envelope tests;
Stoyan & Stoyan (1994) pair correlation function. Python libraries: `pointpats`
(PySAL), `esda` + `libpysal`, `astropy.stats.RipleysKEstimator`, `scipy.spatial`,
`scikit-learn`, `hdbscan`, `shapely`.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code to extract
coordinates.
`python ij.py results` — fetch measurements table as CSV.
Spatial analysis itself runs in Python (scipy, scikit-learn, pointpats) against
exported centroid coordinates, not inside ImageJ.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "How do I extract centroid coordinates from ImageJ?" | §4 |
| "Are my cells clustered, random, or regular?" | §7 Clark-Evans, §8 Ripley's K |
| "What's the formula for expected NND under CSR?" | §6, §28 formula summary |
| "How do I pick DBSCAN `eps`?" | §13 k-distance plot |
| "Two cell types — are they spatially associated?" | §11 cross-K, random labeling, toroidal shift |
| "How far is each cell from a vessel / boundary?" | §14 distance transform |
| "Where are the hot spots of high local density?" | §15.2 Getis-Ord Gi* |
| "How do I handle edge effects?" | §8 edge correction, §28 pitfalls |
| "What's the minimum object count for method X?" | §3 minimum counts table |
| "How many Monte Carlo simulations do I need?" | §18 |
| "My tissue doesn't fill the image — what now?" | §19 inhomogeneous patterns |
| "3D spatial statistics — how do formulas change?" | §17 |
| "What figure / template sentence goes in the paper?" | §21 statistical reporting |
| "Which test should I use for my question?" | §24 decision trees |
| "Why does my analysis give wrong answers?" | §28 common pitfalls |
| "What Python library implements X?" | §22 Python library reference |

---

## §1 Term Index (A–Z)

Alphabetical pointer to every statistic, method, and concept with `§X.Y`
pointer. Use `grep -n '<term>' spatial-statistics-reference.md` to jump.

### A

`Analyze Particles` §4 · `anisotropic voxels` §17 · `astropy` §22 · `autocorrelation` §15

### B

`bandwidth (Stoyan's rule)` §10 · `bivariate analysis` §11 · `border edge correction` §8

### C

`Cellpose` §4 · `centroid extraction` §4 · `chi-square (quadrat)` §16 · `Clark-Evans test` §7 · `clustering (DBSCAN)` §13 · `clustering (HDBSCAN)` §13 · `clustering (OPTICS)` §13 · `coefficient of variation (Voronoi)` §12 · `confidence envelope` §18 · `coordinate system` §28 · `cross-K function` §11 · `cross-NND` §11 · `CSR (Complete Spatial Randomness)` §5 · `CV of Voronoi areas` §12

### D

`DBSCAN` §13 · `density (lambda)` §28 · `Diggle` (header) · `distance map` §14 · `distance transform` §14 · `distance-to-feature` §14 · `Donnelly edge correction` §7

### E

`edge correction (Ripley isotropic)` §8 · `edge correction (translation)` §8 · `edge correction (border)` §8 · `edge effects (gotcha)` §28 · `eps (DBSCAN)` §13 · `esda` §22 · `expected NND` §7, §28 · `expected NND (3D)` §17, §28

### F

`F function (empty-space CDF)` §25 · `Fiji` (header)

### G

`G function (NND CDF)` §25 · `g(r) pair correlation` §10 · `Getis-Ord Gi*` §15.2 · `global envelope` §18 · `grid size (quadrat)` §16 · `guard area` §8

### H

`H(r) function` §9 · `hardcore process` §19, §28 · `HDBSCAN` §13 · `hexagonal lattice` §7, §12 · `hot spot analysis` §15.2 · `homogeneous Poisson` §5

### I

`immune infiltration` §3 · `independent populations (null)` §11 · `inhomogeneous K-function` §19 · `inhomogeneous point pattern` §19 · `isotropic edge correction` §8

### J

`J function` §25

### K

`K function (Ripley)` §8 · `k-distance plot` §13 · `k-th nearest neighbor` §6 · `KDTree (scipy)` §6, §22 · `kernel smoothing` §19

### L

`L function` §9 · `lambda (density)` §28 · `LISA` §22 · `local autocorrelation` §15.2

### M

`mark correlation` §26 · `Monte Carlo envelope` §18 · `Moran's I` §15.1 · `mosaic (retinal)` §3, §12 · `multiple testing` §28

### N

`nearest-neighbor distance (NND)` §6 · `NND histogram shapes` §6 · `null model (random labeling)` §11 · `null model (toroidal shift)` §11 · `null model (independent)` §11

### O

`Otsu threshold` §2, §4 · `OPTICS` §13

### P

`pair correlation function` §10 · `PCF` §10 · `perimeter (Donnelly)` §7 · `pixel calibration` §4, §17 · `pointpats` §22 · `pointwise envelope` §18 · `Poisson process` §5 · `pseudoreplication` §28 · `publication figures` §21 · `PySAL` §22

### Q

`quadrat analysis` §16

### R

`R (Clark-Evans ratio)` §7 · `random labeling` §11, §19 · `randomness (CSR)` §5 · `reachability (OPTICS)` §13 · `regularity index` §12 · `reporting template sentences` §21 · `retinal mosaics` §3, §12 · `Ripley isotropic correction` §8 · `Ripley's K function` §8 · `RipleysKEstimator (astropy)` §22 · `r_max choice` §8

### S

`scikit-learn` §22 · `shapely` §22 · `spatial autocorrelation` §15 · `Spatial Statistics 2D/3D (ImageJ plugin)` §17 · `StarDist` §4 · `study area definition` §4, §28 · `synaptic correlation` §3

### T

`tessellation (Voronoi)` §12 · `tissue mask` §19, §28 · `toroidal shift` §11 · `translation edge correction` §8 · `3D Manager` §4 · `3D Objects Counter` §4 · `3D spatial statistics` §17

### V

`variance-to-mean ratio (VMR)` §16, §28 · `Voronoi tessellation` §12 · `Voronoi CV (2D)` §12, §28 · `Voronoi CV (3D)` §17, §28

### W

`Watershed` §2, §4

### Z

`Z score (Clark-Evans)` §7

---

## §2 Quick Start

```bash
# Detect and extract centroids
python ij.py macro '
  run("Set Measurements...", "centroid area redirect=None decimal=3");
  setAutoThreshold("Otsu dark");
  run("Convert to Mask");
  run("Watershed");
  run("Analyze Particles...", "size=50-Infinity circularity=0.3-1.0 show=Nothing display clear");
'
python ij.py results
# -> "Label,Area,X,Y\n1,523.0,128.5,64.2\n..."
```

```python
import numpy as np
from scipy.spatial import KDTree
from scipy.stats import norm

data = np.loadtxt('.tmp/centroids.csv', delimiter=',', skiprows=1)
coords = data[:, 2:4]  # X, Y columns
n = len(coords)
width, height = 1024, 1024  # from get_image_info, in calibrated units
area = width * height
density = n / area

# Nearest-neighbor distances
tree = KDTree(coords)
nnd = tree.query(coords, k=2)[0][:, 1]

# Clark-Evans ratio
mean_nnd = np.mean(nnd)
expected_nnd = 1.0 / (2.0 * np.sqrt(density))
R = mean_nnd / expected_nnd
se = 0.26136 / np.sqrt(n * density)
z = (mean_nnd - expected_nnd) / se
p = 2 * norm.sf(abs(z))

print(f"N={n}, Clark-Evans R={R:.3f}, Z={z:.3f}, p={p:.4f}")
print(f"{'Clustered' if R < 1 else 'Regular/Dispersed' if R > 1 else 'Random'}")
```

---

## §3 When to Use Spatial Statistics

Spatial statistics asks whether the **pattern** of objects is meaningful, beyond
simple counts and measurements.

| Standard Analysis | Spatial Analysis |
|---|---|
| "142 cells in this field" | "Are those 142 cells clustered, random, or evenly spaced?" |
| "45% of cells are positive" | "Are positive cells concentrated in specific regions?" |
| "20 plaques present" | "Do plaques cluster near vessels?" |

### Common biological applications

| Application | Methods |
|---|---|
| Retinal mosaics (regular spacing) | NND, Clark-Evans, Voronoi regularity |
| Protein aggregates near nuclei | Distance-to-feature, Ripley's K |
| Immune infiltration clusters | DBSCAN, hot spot analysis (Gi*) |
| Pre/post-synaptic spatial correlation | Cross-K, bivariate analysis |
| Pathological inclusions near vessels | Distance-to-feature, bivariate K |
| Neuronal mosaic organization | NND, Voronoi regularity index |
| Cell type segregation | Cross-K, quadrat analysis |

### Minimum object counts

| Method | Minimum N |
|---|---|
| Clark-Evans | ~30 |
| K-function | ~50 (ideally 100+) |
| DBSCAN | ~30 |
| Voronoi CV | ~20 |
| Quadrat | enough for ~2 objects/quadrat average |

---

## §4 Extracting Spatial Data from ImageJ

### From Analyze Particles

```bash
python ij.py macro '
  run("Set Measurements...", "centroid area shape redirect=None decimal=3");
  setAutoThreshold("Otsu dark");
  run("Convert to Mask");
  run("Watershed");
  run("Analyze Particles...", "size=50-Infinity circularity=0.2-1.0 show=Nothing display clear");
'
python ij.py results
# Columns: X, Y (centroid), Area, Circ., etc.
```

### From StarDist / Cellpose

```bash
# After StarDist or Cellpose produces ROIs/labels:
python ij.py macro '
  run("Set Measurements...", "centroid area redirect=None decimal=3");
  roiManager("Deselect");
  roiManager("Measure");
'
python ij.py results
```

### From 3D Objects Counter

```bash
python ij.py macro '
  run("3D OC Options", "centroid centre_of_mass statistics");
  run("3D Objects Counter", "threshold=128 min.=100 max.=9999999 centroids statistics summary");
'
python ij.py results
```

### From 3D Manager

```bash
python ij.py macro '
  run("3D Manager");
  Ext.Manager3D_AddImage();
  Ext.Manager3D_Count(nb);
  coords = "";
  for (i = 0; i < nb; i++) {
      Ext.Manager3D_Centroid3D(i, cx, cy, cz);
      coords = coords + cx + "," + cy + "," + cz + "\n";
  }
  print("[Coordinates]", "X,Y,Z\n" + coords);
'
```

### Saving coordinates and getting study area

```python
import csv, io
csv_text = results_response  # from python ij.py results
reader = csv.DictReader(io.StringIO(csv_text))
coords = [(float(row.get('X', row.get('XM', 0))),
           float(row.get('Y', row.get('YM', 0)))) for row in reader]
with open('.tmp/centroids.csv', 'w', newline='') as f:
    writer = csv.writer(f)
    writer.writerow(['X', 'Y'])
    writer.writerows(coords)
```

```bash
python ij.py info       # image dimensions
python ij.py metadata   # calibration (pixelWidth, unit)
# Study area: width_um = width_pixels * pixelWidth
# For irregular ROIs, use ROI area, not full image
```

---

## §5 Complete Spatial Randomness (CSR) -- The Null Model

All spatial statistics test against CSR (homogeneous Poisson process):
constant density everywhere, independent point locations.

| Observed vs CSR | Pattern |
|---|---|
| Points closer than CSR predicts | Clustered |
| Points farther than CSR predicts | Regular/Dispersed |
| Consistent with CSR | Random |

```python
def generate_csr(n, xmin, xmax, ymin, ymax, seed=None):
    rng = np.random.default_rng(seed)
    return np.column_stack([rng.uniform(xmin, xmax, n), rng.uniform(ymin, ymax, n)])
```

**When CSR is NOT appropriate:** tissue does not fill the image, natural density
gradients exist (e.g., cortical layers), or objects have finite size (hard-core).
Use inhomogeneous Poisson or random labeling instead (Section 19).

---

## §6 Nearest-Neighbor Distance (NND)

| Statistic | Meaning |
|---|---|
| Mean NND | Average spacing |
| Median NND | Robust center |
| SD NND | Spread of spacings |
| Min/Max NND | Closest pair / most isolated |

**NND histogram shapes:** narrow peak near zero = clustering; broad = random;
bimodal = two populations (clustered + isolated).

```python
from scipy.spatial import KDTree

def compute_nnd(coords):
    """Returns (nnd_array, nearest_neighbor_indices)."""
    tree = KDTree(coords)
    distances, indices = tree.query(coords, k=2)  # k=1 is self
    return distances[:, 1], indices[:, 1]
```

**ImageJ macro** (O(n^2), consider Python KDTree for >1000 objects):

```javascript
n = nResults;
nnd = newArray(n);
for (i = 0; i < n; i++) {
    xi = getResult("X", i); yi = getResult("Y", i);
    minDist = 1e30;
    for (j = 0; j < n; j++) {
        if (j == i) continue;
        d = sqrt(pow(xi - getResult("X", j), 2) + pow(yi - getResult("Y", j), 2));
        if (d < minDist) minDist = d;
    }
    nnd[i] = minDist;
}
Array.getStatistics(nnd, min, max, mean, std);
print("Mean NND: " + mean);
```

K-th nearest neighbor for multi-scale structure:

```python
def compute_knn(coords, k=5):
    tree = KDTree(coords)
    return tree.query(coords, k=k+1)[0][:, 1:]  # exclude self
```

---

## §7 Clark-Evans Test

Compares observed mean NND to expected under CSR. Single-number summary.

| R value | Pattern |
|---|---|
| R = 0 | Maximum clustering |
| R < 1 | Clustered |
| R = 1 | Random (CSR) |
| R > 1 | Dispersed/Regular |
| R = 2.15 | Perfect hexagonal lattice |

**Formulas:**
- Expected NND: `E(NND) = 1 / (2 * sqrt(density))`
- SE: `0.26136 / sqrt(N * density)`
- Z: `(observed_mean - expected) / SE`
- Donnelly edge correction: `E_corr = 0.5/sqrt(density) + (0.0514 + 0.041/sqrt(N)) * P/N`

```python
def clark_evans_test(coords, area, perimeter=None):
    n = len(coords)
    density = n / area
    tree = KDTree(coords)
    nnd = tree.query(coords, k=2)[0][:, 1]
    mean_nnd = np.mean(nnd)

    if perimeter is not None:  # Donnelly edge correction
        expected_nnd = (0.5 / np.sqrt(density)) + \
                       (0.0514 + 0.041 / np.sqrt(n)) * (perimeter / n)
        se = 0.070 / np.sqrt(n * density) + 0.035 * (perimeter / (n * np.sqrt(area)))
    else:
        expected_nnd = 1.0 / (2.0 * np.sqrt(density))
        se = 0.26136 / np.sqrt(n * density)

    R = mean_nnd / expected_nnd
    z = (mean_nnd - expected_nnd) / se
    p_value = 2.0 * norm.sf(abs(z))

    interpretation = ("Random" if p_value > 0.05
                      else "Significantly clustered" if R < 1
                      else "Significantly dispersed/regular")
    return {'R': R, 'z': z, 'p_value': p_value, 'mean_nnd': mean_nnd,
            'expected_nnd': expected_nnd, 'interpretation': interpretation}
```

**Limitations:** single-number summary misses multi-scale structure; sensitive
to study area definition; consider ~30+ points minimum.

---

## §8 Ripley's K Function

Describes spatial structure at multiple scales. K(r) = expected neighbors within
distance r of a typical point, divided by density.

- Under CSR: `K(r) = pi * r^2`
- K(r) > pi*r^2: clustering at scale r
- K(r) < pi*r^2: dispersion at scale r

### Edge correction

| Method | When to use |
|---|---|
| Ripley isotropic | Default, most cases |
| Translation | Rectangular windows |
| Border/Guard area | Simple but wastes data |

### Choosing r_max

A typical starting point: `r_max = min(width, height) / 4`

```python
def ripley_k_rectangular(coords, xmin, xmax, ymin, ymax, r_values):
    """K with translation edge correction for rectangular windows."""
    n = len(coords)
    width, height = xmax - xmin, ymax - ymin
    area = width * height
    tree = KDTree(coords)
    K = np.zeros(len(r_values))
    for idx, r in enumerate(r_values):
        count = 0.0
        for i in range(n):
            for j in tree.query_ball_point(coords[i], r):
                if j == i: continue
                dx = abs(coords[j, 0] - coords[i, 0])
                dy = abs(coords[j, 1] - coords[i, 1])
                overlap = (width - dx) * (height - dy)
                if overlap > 0:
                    count += area / overlap
        K[idx] = count / (n * n)
    return K
```

**Fast version** for N < 5000 (precomputed distance matrix):

```python
def ripley_k_fast(coords, xmin, xmax, ymin, ymax, r_values):
    from scipy.spatial.distance import pdist, squareform
    n = len(coords)
    width, height = xmax - xmin, ymax - ymin
    area = width * height
    dist_matrix = squareform(pdist(coords))
    np.fill_diagonal(dist_matrix, np.inf)
    dx_matrix = np.abs(coords[:, 0][:, None] - coords[:, 0][None, :])
    dy_matrix = np.abs(coords[:, 1][:, None] - coords[:, 1][None, :])
    overlap = np.maximum((width - dx_matrix) * (height - dy_matrix), 1e-10)
    weights = area / overlap
    K = np.zeros(len(r_values))
    for idx, r in enumerate(r_values):
        K[idx] = np.sum(weights[dist_matrix <= r]) / (n * n)
    return K
```

---

## §9 L Function and H Function

Transformations of K for easier interpretation.

| Function | Formula | CSR value | Interpretation |
|---|---|---|---|
| L(r) | `sqrt(K(r) / pi)` | L(r) = r | Above diagonal = clustering |
| H(r) | `L(r) - r` | H(r) = 0 | Positive = clustering, negative = regularity |

H(r) peak indicates scale of maximum clustering. **H(r) with Monte Carlo
envelope is the recommended publication plot.**

```python
def k_to_l(K, r_values): return np.sqrt(np.maximum(K, 0) / np.pi)
def k_to_h(K, r_values): return k_to_l(K, r_values) - r_values
```

---

## §10 Pair Correlation Function g(r)

Non-cumulative alternative to K(r): density of points at distance r relative to
CSR. `g(r) = K'(r) / (2*pi*r)`.

| g(r) | Meaning |
|---|---|
| g(r) = 1 | Random at distance r |
| g(r) > 1 | Clustering at distance r |
| g(r) < 1 | Inhibition at distance r |

**Advantage over K:** K is cumulative (small-r clustering inflates all larger r).
g(r) is local -- peaks show exact clustering distance.

**Bandwidth:** Stoyan's rule `h = 0.15 / sqrt(density)` is a typical starting point.

```python
def pair_correlation(coords, xmin, xmax, ymin, ymax, r_values, bandwidth=None):
    from scipy.spatial.distance import pdist
    n = len(coords)
    width, height = xmax - xmin, ymax - ymin
    area = width * height
    density = n / area
    if bandwidth is None:
        bandwidth = 0.15 / np.sqrt(density)
    dists = pdist(coords)
    # Edge correction weights
    dx_pairs, dy_pairs = [], []
    for i in range(n):
        for j in range(i+1, n):
            dx_pairs.append(abs(coords[i, 0] - coords[j, 0]))
            dy_pairs.append(abs(coords[i, 1] - coords[j, 1]))
    dx_pairs, dy_pairs = np.array(dx_pairs), np.array(dy_pairs)
    weights = area / np.maximum((width - dx_pairs) * (height - dy_pairs), 1e-10)

    def kernel(x, h):
        u = x / h
        return np.where(np.abs(u) <= 1, 0.75 * (1 - u**2) / h, 0.0)

    g = np.zeros(len(r_values))
    for idx, r in enumerate(r_values):
        if r < 1e-10: continue
        g[idx] = (area * np.sum(kernel(dists - r, bandwidth) * weights)) / \
                 (n * (n - 1) * np.pi * r)
    return g
```

**Biological interpretation:** sharp peak at r=d suggests characteristic spacing d;
oscillating g(r) suggests lattice-like regularity; rising g(r) suggests density
inhomogeneity.

---

## §11 Cross-Type Analysis (Bivariate)

For two object types (e.g., microglia and plaques): "Are type A objects closer
to type B than expected by chance?"

### Cross-nearest-neighbor distance

```python
def cross_nnd(coords_a, coords_b):
    tree_b = KDTree(coords_b)
    nnd_a_to_b, nn_indices = tree_b.query(coords_a, k=1)
    return nnd_a_to_b, nn_indices
```

### Cross-K function

```python
def cross_k(coords_a, coords_b, xmin, xmax, ymin, ymax, r_values):
    n_a, n_b = len(coords_a), len(coords_b)
    width, height = xmax - xmin, ymax - ymin
    area = width * height
    tree_b = KDTree(coords_b)
    K_ab = np.zeros(len(r_values))
    for idx, r in enumerate(r_values):
        count = 0.0
        for i in range(n_a):
            for j in tree_b.query_ball_point(coords_a[i], r):
                dx = abs(coords_a[i, 0] - coords_b[j, 0])
                dy = abs(coords_a[i, 1] - coords_b[j, 1])
                overlap = (width - dx) * (height - dy)
                if overlap > 0:
                    count += area / overlap
        K_ab[idx] = count / (n_a * n_b / area)
    return K_ab
```

### Null models for bivariate analysis

| Null model | What it tests | When to use |
|---|---|---|
| Random labeling | Are labels (A/B) assigned randomly given the combined pattern? | Total pattern fixed; asking if type assignment is spatial |
| Independent populations | Are A and B generated independently? | Different biological processes |
| Toroidal shift | Shift one pattern with wrap-around | Preserves within-pattern structure |

### Random labeling test

```python
def random_labeling_test(coords_a, coords_b, xmin, xmax, ymin, ymax,
                          r_values, n_simulations=199):
    K_obs = cross_k(coords_a, coords_b, xmin, xmax, ymin, ymax, r_values)
    all_coords = np.vstack([coords_a, coords_b])
    n_a = len(coords_a)
    K_sims = np.zeros((n_simulations, len(r_values)))
    for sim in range(n_simulations):
        perm = np.random.permutation(len(all_coords))
        K_sims[sim] = cross_k(all_coords[perm[:n_a]], all_coords[perm[n_a:]],
                               xmin, xmax, ymin, ymax, r_values)
    return K_obs, np.percentile(K_sims, 2.5, axis=0), np.percentile(K_sims, 97.5, axis=0)
```

### Toroidal shift test

```python
def toroidal_shift_test(coords_a, coords_b, xmin, xmax, ymin, ymax,
                         r_values, n_simulations=199):
    width, height = xmax - xmin, ymax - ymin
    K_obs = cross_k(coords_a, coords_b, xmin, xmax, ymin, ymax, r_values)
    K_sims = np.zeros((n_simulations, len(r_values)))
    for sim in range(n_simulations):
        dx, dy = np.random.uniform(0, width), np.random.uniform(0, height)
        shifted_b = coords_b.copy()
        shifted_b[:, 0] = (shifted_b[:, 0] - xmin + dx) % width + xmin
        shifted_b[:, 1] = (shifted_b[:, 1] - ymin + dy) % height + ymin
        K_sims[sim] = cross_k(coords_a, shifted_b, xmin, xmax, ymin, ymax, r_values)
    return K_obs, np.percentile(K_sims, 2.5, axis=0), np.percentile(K_sims, 97.5, axis=0)
```

---

## §12 Voronoi Tessellation

Partitions the plane into territories (one per point). Each Voronoi cell
contains all locations closer to that point than to any other.

### CV of Voronoi areas (regularity index)

| CV value | Pattern |
|---|---|
| CV = 0 | Perfect hexagonal lattice |
| CV ~ 0.33-0.36 | Regular mosaic (e.g., retinal ganglion cells) |
| CV = 0.529 | CSR (Poisson process) |
| CV > 0.529 | Clustered |

Alternative regularity index: `RI = mean_NND / SD_NND` (Poisson RI ~ 1.91).

### ImageJ Voronoi

```bash
python ij.py macro 'run("Voronoi");'
# Produces distance map with Voronoi edges bright -- does NOT give cell areas
# For quantitative areas, use Python below
```

### Python Voronoi analysis

```python
from scipy.spatial import Voronoi
from shapely.geometry import Polygon, box

def voronoi_analysis(coords, xmin, xmax, ymin, ymax):
    # Mirror points to handle boundaries
    mirrored = []
    for p in coords:
        mirrored.extend([[2*xmin - p[0], p[1]], [2*xmax - p[0], p[1]],
                         [p[0], 2*ymin - p[1]], [p[0], 2*ymax - p[1]]])
    vor = Voronoi(np.vstack([coords, np.array(mirrored)]))
    bounding_box = box(xmin, ymin, xmax, ymax)
    areas = []
    for i in range(len(coords)):
        region = vor.regions[vor.point_region[i]]
        if -1 in region or len(region) == 0: continue
        try:
            areas.append(Polygon(vor.vertices[region]).intersection(bounding_box).area)
        except: continue
    areas = np.array(areas)
    cv = np.std(areas) / np.mean(areas) if np.mean(areas) > 0 else np.inf
    interpretation = ("Regular/mosaic (CV < 0.40)" if cv < 0.40
                      else "Approximately random (CV near 0.53)" if cv < 0.60
                      else "Clustered (CV > 0.60)")
    return {'areas': areas, 'cv': cv, 'regularity_index': 1.0/cv if cv > 0 else float('inf'),
            'interpretation': interpretation}
```

---

## §13 DBSCAN Clustering

Groups nearby points into clusters without specifying cluster count. Points not
near any cluster are labeled noise.

| Parameter | How to choose |
|---|---|
| **eps** | Use k-distance plot: compute k-th NN distance for each point, sort, plot; the "elbow" suggests eps |
| **min_samples** | 2D: typically 4-5; 3D: typically 6; noisy data: consider 7-10 |

### Choosing eps with k-distance plot

```python
def k_distance_plot(coords, k=5, save_path='.tmp/kdist.png'):
    import matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
    tree = KDTree(coords)
    k_dist = np.sort(tree.query(coords, k=k+1)[0][:, k])
    fig, ax = plt.subplots(figsize=(8, 5))
    ax.plot(range(len(k_dist)), k_dist, 'b-')
    ax.set_xlabel('Points (sorted)'); ax.set_ylabel(f'{k}-th NN Distance')
    if len(k_dist) > 10:
        elbow_idx = np.argmax(np.diff(k_dist, 2)) + 1
        ax.axhline(y=k_dist[elbow_idx], color='r', linestyle='--',
                    label=f'Suggested eps = {k_dist[elbow_idx]:.2f}')
        ax.legend()
    plt.tight_layout(); plt.savefig(save_path, dpi=150); plt.close()
    return k_dist
```

### Running DBSCAN

```python
from sklearn.cluster import DBSCAN

def run_dbscan(coords, eps, min_samples=5):
    labels = DBSCAN(eps=eps, min_samples=min_samples).fit(coords).labels_
    n_clusters = len(set(labels)) - (1 if -1 in labels else 0)
    cluster_sizes = {cid: int(np.sum(labels == cid))
                     for cid in set(labels) if cid != -1}
    return {'labels': labels, 'n_clusters': n_clusters,
            'n_noise': int(np.sum(labels == -1)),
            'noise_fraction': float(np.sum(labels == -1)) / len(labels),
            'cluster_sizes': cluster_sizes}
```

### HDBSCAN (no eps parameter needed)

```python
import hdbscan

def run_hdbscan(coords, min_cluster_size=5, min_samples=None):
    clusterer = hdbscan.HDBSCAN(min_cluster_size=min_cluster_size, min_samples=min_samples)
    labels = clusterer.fit_predict(coords)
    n_clusters = len(set(labels)) - (1 if -1 in labels else 0)
    return {'labels': labels, 'n_clusters': n_clusters,
            'n_noise': int(np.sum(labels == -1)),
            'probabilities': clusterer.probabilities_}
```

### OPTICS (reachability-based, reveals multi-density structure)

```python
from sklearn.cluster import OPTICS

def run_optics(coords, min_samples=5, xi=0.05):
    cl = OPTICS(min_samples=min_samples, xi=xi).fit(coords)
    return {'labels': cl.labels_,
            'n_clusters': len(set(cl.labels_)) - (1 if -1 in cl.labels_ else 0),
            'reachability': cl.reachability_, 'ordering': cl.ordering_}
```

---

## §14 Distance to Features

"How far is each cell from the nearest vessel / boundary / lesion?"

### ImageJ distance transform approach

```bash
# 1. Create distance map from feature mask
python ij.py macro '
  selectWindow("vessel_mask");
  run("Invert");
  run("Distance Map");
  rename("vessel_dist");
'
# 2. Measure cell distances via redirect
python ij.py macro '
  selectWindow("cell_mask");
  run("Set Measurements...", "centroid mean redirect=vessel_dist decimal=3");
  run("Analyze Particles...", "size=50-Infinity show=Nothing display clear");
'
python ij.py results
# "Mean" column = mean distance within cell outline to nearest vessel
```

### Python distance-to-feature

```python
from scipy.ndimage import distance_transform_edt

def distance_to_feature(cell_coords, feature_mask, pixel_size=1.0):
    """cell_coords in pixel units (row, col). Returns calibrated distances."""
    dist_map = distance_transform_edt(~feature_mask.astype(bool)) * pixel_size
    rows = np.clip(cell_coords[:, 1].astype(int), 0, dist_map.shape[0] - 1)
    cols = np.clip(cell_coords[:, 0].astype(int), 0, dist_map.shape[1] - 1)
    return dist_map[rows, cols]
```

### Testing against random expectation

```python
def distance_to_feature_test(cell_distances, feature_mask, n_cells,
                              pixel_size=1.0, n_simulations=999):
    dist_map = distance_transform_edt(~feature_mask.astype(bool)) * pixel_size
    available = np.argwhere(feature_mask == 0)
    observed_mean = np.mean(cell_distances)
    sim_means = []
    for _ in range(n_simulations):
        idx = np.random.choice(len(available), size=n_cells, replace=False)
        sim_means.append(np.mean(dist_map[available[idx, 0], available[idx, 1]]))
    sim_means = np.array(sim_means)
    p_closer = np.mean(sim_means <= observed_mean)
    p_farther = np.mean(sim_means >= observed_mean)
    return {'observed_mean': observed_mean, 'simulated_mean': np.mean(sim_means),
            'p_closer': p_closer, 'p_farther': p_farther,
            'p_two_sided': min(2 * min(p_closer, p_farther), 1.0)}
```

---

## §15 Spatial Autocorrelation

### §15.1 Moran's I (global)

Tests whether nearby locations have similar values. Choose neighborhood by
k-nearest-neighbors (typical k=8) or distance threshold.

| I value | Interpretation |
|---|---|
| I > E(I) | Positive autocorrelation (similar values cluster) |
| I = E(I) | No spatial pattern (E(I) ~ -1/(N-1) ~ 0 for large N) |
| I < E(I) | Negative autocorrelation (checkerboard) |

```python
def morans_i(coords, values, k=8):
    n = len(values)
    x_bar = np.mean(values)
    deviations = values - x_bar
    tree = KDTree(coords)
    W = np.zeros((n, n))
    _, indices = tree.query(coords, k=k+1)
    for i in range(n):
        for j_idx in indices[i, 1:]:
            W[i, j_idx] = 1
    row_sums = W.sum(axis=1); row_sums[row_sums == 0] = 1
    W = W / row_sums[:, None]
    S0 = W.sum()
    I = n * np.sum(W * np.outer(deviations, deviations)) / (S0 * np.sum(deviations**2))
    expected_I = -1.0 / (n - 1)
    # Variance computation omitted for brevity -- see scipy or esda libraries
    return {'I': I, 'expected_I': expected_I}
```

### §15.2 Getis-Ord Gi* (local hot/cold spots)

For each point, identifies whether it is surrounded by unusually high (hot spot)
or low (cold spot) values. Gi* > 1.96 = hot spot (p<0.05), Gi* < -1.96 = cold spot.

```python
def getis_ord_gi_star(coords, values, k=8):
    n = len(values)
    x_bar, S = np.mean(values), np.std(values)
    tree = KDTree(coords)
    gi_star = np.zeros(n)
    for i in range(n):
        _, neighbors = tree.query(coords[i], k=k+1)
        w_sum = len(neighbors)
        wx_sum = np.sum(values[neighbors])
        denom = S * np.sqrt((n * w_sum - w_sum**2) / (n - 1))
        gi_star[i] = (wx_sum - x_bar * w_sum) / denom if denom > 0 else 0
    p_values = 2 * norm.sf(np.abs(gi_star))
    return gi_star, p_values, (gi_star > 0) & (p_values < 0.05), (gi_star < 0) & (p_values < 0.05)
```

---

## §16 Quadrat Analysis

Divides study area into grid, counts objects per cell, compares to Poisson.

| VMR (variance/mean) | Pattern |
|---|---|
| VMR = 1 | Random (Poisson) |
| VMR > 1 | Clustered |
| VMR < 1 | Regular |

**Choosing grid size:** aim for ~1-5 objects per quadrat on average.

```python
from scipy.stats import chi2

def quadrat_analysis(coords, xmin, xmax, ymin, ymax, nx=10, ny=10):
    x_bins = np.linspace(xmin, xmax, nx + 1)
    y_bins = np.linspace(ymin, ymax, ny + 1)
    counts = np.zeros((ny, nx), dtype=int)
    for x, y in coords:
        col = np.clip(np.searchsorted(x_bins, x, side='right') - 1, 0, nx - 1)
        row = np.clip(np.searchsorted(y_bins, y, side='right') - 1, 0, ny - 1)
        counts[row, col] += 1
    flat = counts.flatten()
    mean_c, var_c = np.mean(flat), np.var(flat, ddof=1)
    vmr = var_c / mean_c if mean_c > 0 else 0
    n_q = nx * ny
    chi2_stat = (n_q - 1) * vmr
    chi2_p = 1 - chi2.cdf(chi2_stat, n_q - 1)
    return {'vmr': vmr, 'chi2_p': chi2_p, 'counts': counts,
            'interpretation': ("Random" if chi2_p > 0.05
                               else f"Clustered (VMR={vmr:.2f})" if vmr > 1
                               else f"Regular (VMR={vmr:.2f})")}
```

**Gotcha:** Results depend heavily on grid size. Consider supplementing with
NND or K-function analysis.

---

## §17 3D Spatial Statistics

### 2D vs 3D formulas

| Quantity | 2D | 3D |
|---|---|---|
| Study region | Area A = w*h | Volume V = w*h*d |
| K(r) under CSR | pi * r^2 | (4/3) * pi * r^3 |
| L(r) | sqrt(K/pi) | (3K / 4pi)^(1/3) |
| E(NND) | 1 / (2*sqrt(density)) | 0.554 * (V/N)^(1/3) |
| Voronoi CV (Poisson) | 0.529 | 0.422 |

**Anisotropic voxels:** convert to physical coordinates before analysis.

```python
def calibrate_3d_coords(coords_voxel, pixel_xy, pixel_z):
    return coords_voxel * np.array([pixel_xy, pixel_xy, pixel_z])
```

3D Clark-Evans, K-function, and DBSCAN use the same code as 2D -- just pass
(n, 3) coordinate arrays and use volume instead of area. KDTree and
sklearn.cluster.DBSCAN work in any dimension.

ImageJ 3D spatial statistics:

```bash
python ij.py macro '
  run("Spatial Statistics 2D/3D",
    "mask=mask spots=centroids nb_evaluations=1000 nb_random=100 "
    + "hardcore=5 confidence=0.95");
'
```

---

## §18 Monte Carlo Simulation Envelopes

Analytical significance formulas may not hold. Monte Carlo envelopes provide
non-parametric significance testing.

**Procedure:** Compute observed function -> simulate N CSR patterns -> compute
function for each -> construct 2.5/97.5 percentile envelope -> observed outside
envelope = significant deviation.

| Purpose | Simulations |
|---|---|
| Rough screening | 99 |
| Standard analysis | 199-499 |
| Publication | 999 |
| Global envelope | 2499+ |

P-value resolution ~ 1/(n_simulations + 1).

### Pointwise envelope

```python
def monte_carlo_envelope(coords, xmin, xmax, ymin, ymax, r_values,
                          n_simulations=199, function='H'):
    n = len(coords)
    obs_K = ripley_k_fast(coords, xmin, xmax, ymin, ymax, r_values)
    if function == 'K': observed = obs_K
    elif function == 'L': observed = k_to_l(obs_K, r_values)
    else: observed = k_to_h(obs_K, r_values)

    sims = np.zeros((n_simulations, len(r_values)))
    for s in range(n_simulations):
        sim_K = ripley_k_fast(generate_csr(n, xmin, xmax, ymin, ymax),
                               xmin, xmax, ymin, ymax, r_values)
        if function == 'K': sims[s] = sim_K
        elif function == 'L': sims[s] = k_to_l(sim_K, r_values)
        else: sims[s] = k_to_h(sim_K, r_values)

    return {'observed': observed, 'csr_mean': np.mean(sims, axis=0),
            'envelope_lo': np.percentile(sims, 2.5, axis=0),
            'envelope_hi': np.percentile(sims, 97.5, axis=0),
            'significant': (observed < np.percentile(sims, 2.5, axis=0)) |
                           (observed > np.percentile(sims, 97.5, axis=0))}
```

**Gotcha (pointwise):** with 50 r values, ~2.5 will appear "significant" by
chance. Use a **global envelope** (Myllymaki et al. 2017) for proper
simultaneous inference -- computes maximum absolute deviation across all r
values and ranks against simulations. Returns a single global p-value.

---

## §19 Inhomogeneous Point Patterns

CSR assumes uniform density. In tissue with natural density gradients, testing
against CSR will always find "clustering" that is merely density variation.

### Solutions

1. **Restrict to tissue mask:** compute area from mask, not full image.
   `tissue_area = np.sum(mask > 0) * pixel_size**2`

2. **Inhomogeneous K-function:** compare to inhomogeneous Poisson (spatially
   varying density). Weight pairs by inverse density product.

3. **Estimate local density** with kernel smoothing (Silverman's rule for
   bandwidth: `h = mean(std_x, std_y) * (4/(3N))^0.2`).

4. **Random labeling:** for bivariate patterns, preserve combined pattern and
   randomize type labels only. Tests whether type assignment is spatial.

---

## §20 Complete Agent Workflows

### Workflow 1: Cell Clustering Analysis

```bash
python ij.py state
python ij.py macro 'open("/path/to/image.tif");'
python ij.py metadata    # get pixel_size
python ij.py capture original
# Segment, extract centroids, save to .tmp/centroids.csv
# Then run Clark-Evans + K/H with envelope + quadrat analysis in Python
```

### Workflow 2: Distance to Feature

```bash
# Segment cells (channel 1) and features (channel 2) separately
# Create distance map from feature mask
# Measure cell centroids redirected to distance map
# In Python: test against random expectation
```

### Workflow 3: Bivariate Spatial Analysis

```bash
# Segment two populations from different channels
# Compute cross-NND and cross-K with random labeling or toroidal shift test
# K_obs above envelope = spatial attraction
```

### Workflow 4: Hot Spot Identification

```python
voronoi = voronoi_analysis(coords, xmin, xmax, ymin, ymax)
local_density = 1.0 / np.array(voronoi['areas'])
gi_star, p_vals, hot, cold = getis_ord_gi_star(coords, local_density)
```

---

## §21 Statistical Reporting

| Element | Example |
|---|---|
| Sample size | N = 142 cells |
| Study area | 332 x 332 um (1024 x 1024 px, 0.325 um/px) |
| Segmentation | Otsu threshold, watershed |
| Edge correction | Ripley isotropic |
| Simulations | 999 CSR simulations |
| Test statistic | Clark-Evans R = 0.72 |
| P-value | p < 0.001 |
| Effect direction | Significantly clustered |

### Template sentences

**Clark-Evans:** "Spatial distribution was assessed using the Clark-Evans
nearest-neighbor test with Donnelly edge correction. [Cell type] (N=[n]) showed
[significant clustering/regularity/no deviation from randomness] (R=[val],
Z=[val], p=[val])."

**Ripley's K:** "Multi-scale analysis using Ripley's K function revealed
[clustering/regularity] at scales of [range] um. Significance assessed using
Monte Carlo envelopes from [n] CSR simulations (95% confidence)."

**DBSCAN:** "DBSCAN (epsilon=[val] um, minPts=[val]) identified [n] clusters
containing [n] objects ([%]%), with [n] noise points."

**Bivariate:** "Cross-K function with [random labeling/toroidal shift] null
model showed [significant attraction/repulsion/no association] ([n] simulations,
p=[val])."

### Recommended figures

| Analysis | Figure |
|---|---|
| K-function | H(r) with Monte Carlo envelope |
| PCF | g(r) with CSR line at 1 |
| Voronoi | Tessellation colored by area |
| DBSCAN | Point map with cluster colors |
| Hot spots | Map colored by Gi* score |
| Distance | Histogram + CDF |

---

## §22 Python Library Reference

### Core (typically pre-installed)

| Module | Use |
|---|---|
| `scipy.spatial.KDTree` | Fast nearest-neighbor queries |
| `scipy.spatial.Voronoi` | Voronoi tessellation |
| `scipy.spatial.distance.pdist` | Pairwise distances |
| `scipy.ndimage.distance_transform_edt` | Euclidean distance transform |
| `scipy.stats.norm`, `chi2` | Statistical distributions |
| `matplotlib.pyplot` | Plotting |

### Specialized (pip install)

| Library | Use |
|---|---|
| `scikit-learn` | DBSCAN, OPTICS |
| `hdbscan` | HDBSCAN (auto eps) |
| `pointpats` (PySAL) | K, G, F, L, J functions with envelopes |
| `esda` + `libpysal` | Moran's I, LISA, Gi* |
| `shapely` | Polygon operations (Voronoi clipping) |
| `astropy` | Ripley's K with proper edge correction |

### Quick examples

```python
# pointpats
from pointpats import PointPattern, Kfunction, Lfunction
pp = PointPattern(coords)
k = Kfunction(pp, intervals=50)  # k.d, k.K, k.Kenv

# esda / libpysal
from libpysal.weights import KNN; from esda.moran import Moran, Moran_Local
w = KNN.from_array(coords, k=8); w.transform = 'R'
mi = Moran(values, w)  # mi.I, mi.p_sim
lisa = Moran_Local(values, w)  # lisa.Is, lisa.p_sim, lisa.q (1=HH,2=LH,3=LL,4=HL)

# astropy
from astropy.stats import RipleysKEstimator
Kest = RipleysKEstimator(area=area, x_max=xmax, x_min=xmin, y_max=ymax, y_min=ymin)
K = Kest(data=coords, radii=r_values, mode='ripley')
```

---

## §23 Decision Trees

### What spatial analysis to use

```
What is the question?
|
+-- "Clustered or random?"
|   +-- Quick answer --> Clark-Evans (Sec 6)
|   +-- Multi-scale  --> Ripley's K / H(r) (Sec 7-8)
|   +-- Simple/few objects --> Quadrat (Sec 15)
|   +-- Need cluster assignments --> DBSCAN (Sec 12)
|
+-- "Two types spatially associated?"
|   +-- Same detection --> Random labeling (Sec 10)
|   +-- Independent channels --> Cross-K / toroidal shift (Sec 10)
|
+-- "How far from feature X?"
|   +-- Feature is another object type --> Cross-NND (Sec 10)
|   +-- Feature is structure (vessel) --> Distance transform (Sec 13)
|
+-- "Where are hot spots?"
|   +-- Point density --> Gi* or DBSCAN (Sec 14, 12)
|   +-- Measured variable --> Gi* or Moran's I (Sec 14)
|
+-- "Regularly spaced (mosaic)?"
|   +-- Quick --> Clark-Evans R > 1
|   +-- Detail --> Voronoi CV (Sec 11)
|   +-- Scale info --> g(r) oscillations (Sec 9)
|
+-- "Spatial gradient?" --> Moran's I or quadrat along axis
|
+-- 3D data? --> Same methods, adapted formulas (Sec 16)
```

### Which K variant to plot

```
+-- Quick internal --> K(r) vs pi*r^2
+-- Presentation --> L(r) vs diagonal
+-- Publication --> H(r) with Monte Carlo envelope (+ global envelope for significance)
```

---

## §24 Appendix: G, F, J Functions

| Function | What it is | Clustering signal | Regularity signal |
|---|---|---|---|
| G(r) | NND CDF | Rises faster than CSR | Rises slower |
| F(r) | Empty-space CDF | Rises slower (large gaps) | Rises faster (uniform fill) |
| J(r) = (1-G)/(1-F) | Combined | J < 1 | J > 1 |

```python
def g_function(coords, r_values, area):
    nnd = KDTree(coords).query(coords, k=2)[0][:, 1]
    G = np.array([np.mean(nnd <= r) for r in r_values])
    G_csr = 1 - np.exp(-np.pi * (len(coords) / area) * r_values**2)
    return G, G_csr

def f_function(coords, xmin, xmax, ymin, ymax, r_values, n_test=1000):
    tree = KDTree(coords)
    test_pts = np.column_stack([np.random.uniform(xmin, xmax, n_test),
                                 np.random.uniform(ymin, ymax, n_test)])
    dists = tree.query(test_pts, k=1)[0]
    density = len(coords) / ((xmax-xmin) * (ymax-ymin))
    F = np.array([np.mean(dists <= r) for r in r_values])
    F_csr = 1 - np.exp(-np.pi * density * r_values**2)
    return F, F_csr
```

---

## §25 Appendix: Mark Correlation

Tests if objects at distance r have correlated continuous marks (e.g.,
intensity, area). k_mm(r) = 1 for independent marks, > 1 for similar marks
clustering, < 1 for dissimilar marks clustering.

---

## §26 Appendix: Formula Summary

### 2D

| Quantity | Formula |
|---|---|
| Density | lambda = N / A |
| E(NND) | 1 / (2 * sqrt(lambda)) |
| Clark-Evans R | mean(NND) / E(NND) |
| Clark-Evans SE | 0.26136 / sqrt(N * lambda) |
| K(r) CSR | pi * r^2 |
| L(r) | sqrt(K/pi) |
| H(r) | L(r) - r |
| Voronoi CV CSR | 0.529 |
| VMR CSR | 1 |

### 3D

| Quantity | Formula |
|---|---|
| E(NND) | 0.554 * (V/N)^(1/3) |
| K(r) CSR | (4/3) * pi * r^3 |
| L(r) | (3K / 4pi)^(1/3) |
| Voronoi CV CSR | 0.422 |

---

## §27 Common Pitfalls

| Pitfall | Solution |
|---|---|
| **Edge effects** | Use edge correction (Ripley isotropic, translation). Never report uncorrected statistics. |
| **Study area = full image** | Define study area as tissue outline, not image frame. Compute area from tissue mask. |
| **Inhomogeneous density** | Use inhomogeneous K-function, restrict to homogeneous subregions, or use Gi*. |
| **Objects are not points** | Use hard-core process as null, or interpret K/g only at distances > object diameter. |
| **Multiple testing** | Bonferroni/FDR across tests. For K at multiple r, use global envelope. |
| **Pseudoreplication** | Biological replicate = animal/sample, not cell. 5000 cells from 1 mouse = N=1. Compare per-sample summary stats across replicates. |
| **Spatial association != interaction** | Spatial association is pattern, not mechanism. Consider third-variable confounders. |
| **2D analysis of 3D data** | Use 3D statistics if z-stack available. Acknowledge limitation if using projections. |
| **Too few objects** | See minimum counts table in Section 2. |
| **Quadrat size sensitivity** | Supplement with scale-independent methods (K-function, NND). |
| **DBSCAN epsilon cherry-picking** | Choose eps BEFORE seeing results via k-distance plot. Report multiple values if uncertain. |
| **Coordinate system mismatch** | Convert to calibrated physical units before analysis. Verify with `python ij.py metadata`. |
