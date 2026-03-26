# Statistical Analysis Workflow Reference

ImageJ measurements, Python statistical scripts, and integration workflow for the ImageJAI agent.

---

## 1. ImageJ Measurement Commands

### 1.1 Set Measurements — Keyword Reference

```javascript
// Enable all measurements
run("Set Measurements...",
    "area mean standard_deviation modal_gray min centroid "
  + "center_of_mass perimeter bounding_box fit_ellipse "
  + "shape_descriptors feret's integrated_density median "
  + "skewness kurtosis area_fraction stack_position "
  + "display redirect=None decimal=3");
```

| Keyword | Columns | Notes |
|---------|---------|-------|
| `area` | Area | Calibrated units if scale set |
| `mean` | Mean | Mean gray value in selection |
| `standard_deviation` | StdDev | SD of gray values |
| `modal_gray` | Mode | Most frequent gray value |
| `min` | Min, Max | Min/max gray values |
| `centroid` | X, Y | Geometric center |
| `center_of_mass` | XM, YM | Brightness-weighted center |
| `perimeter` | Perim. | Calibrated boundary length |
| `bounding_box` | BX, BY, Width, Height | Bounding rectangle |
| `fit_ellipse` | Major, Minor, Angle | Best-fit ellipse |
| `shape_descriptors` | Circ., AR, Round, Solidity | Shape metrics |
| `feret's` | Feret, FeretAngle, MinFeret, FeretX, FeretY | Caliper diameters |
| `integrated_density` | IntDen, RawIntDen | Sum of pixel values |
| `median` | Median | Median gray value |
| `skewness` | Skew | Distribution skewness |
| `kurtosis` | Kurt | Excess kurtosis |
| `area_fraction` | %Area | % non-zero pixels |
| `stack_position` | Slice | Slice number |
| `display` | Label | Image name as label |
| `redirect=ImageName` | — | Measure from a different image |
| `decimal=N` | — | Decimal places |

### 1.2 Morphometric Measurements

| Measurement | Formula / Range | Typical biological use |
|-------------|----------------|----------------------|
| **Area** | Calibrated (um^2) | Cell/nucleus/plaque size |
| **Perimeter** | Calibrated length | Boundary complexity |
| **Circularity** | 4*pi*Area/Perim^2; 0-1 | Round (0.7-0.9 neurons) vs elongated (0.1-0.3 microglia processes) |
| **Aspect Ratio** | Major/Minor; 1-inf | Cell elongation, fiber alignment |
| **Roundness** | 4*Area/(pi*Major^2); 0-1 | Like circularity, less boundary-sensitive |
| **Solidity** | Area/ConvexHull; 0-1 | Process complexity (low = many concavities) |
| **Feret's** | Max caliper diameter | Longest dimension; MinFeret = narrowest |

### 1.3 Intensity Measurements

| Measurement | What it is | Notes |
|-------------|-----------|-------|
| **Mean** | Average pixel intensity | Relative protein abundance; compare to controls |
| **StdDev** | SD of intensities | Texture heterogeneity (punctate vs diffuse) |
| **Min/Max** | Extremes | Max near saturation = unreliable data |
| **Median** | Median intensity | More robust than mean for skewed distributions |
| **Mode** | Most frequent value | Typically background intensity |
| **IntDen** | Mean * Area (calibrated) | Total fluorescence; affected by spatial calibration |
| **RawIntDen** | Sum of pixel values | Total fluorescence; not affected by calibration |

### 1.4 CTCF (Corrected Total Cell Fluorescence)

```javascript
// Measure cell ROI
run("Set Measurements...", "area mean integrated_density display redirect=None decimal=3");
roiManager("Select", cellIndex);
run("Measure");
intDen = getResult("IntDen", nResults-1);
area = getResult("Area", nResults-1);
// Measure background ROI
roiManager("Select", bgIndex);
run("Measure");
bgMean = getResult("Mean", nResults-1);
// CTCF = IntDen - (Area * bgMean)
ctcf = intDen - (area * bgMean);
setResult("CTCF", nResults-1, ctcf);
updateResults();
```

Use Sum Intensity Z-projection (not Max) for 3D. Never enhance contrast before measuring.

### 1.5 Multi-Channel Measurement (Redirect)

```javascript
// Segment on channel 1, measure intensity from channel 2
selectWindow("C1-mask");
run("Set Measurements...", "mean integrated_density display redirect=C2-imageName decimal=3");
run("Analyze Particles...", "size=50-Infinity display");
```

### 1.6 Stack/Time-Lapse Measurements

```javascript
// Per-slice
for (s = 1; s <= nSlices(); s++) { Stack.setSlice(s); run("Measure"); }
// Per-frame
getDimensions(w, h, channels, slices, frames);
for (f = 1; f <= frames; f++) { Stack.setFrame(f); run("Measure"); }
```

### 1.7 Coloc 2

```javascript
run("Split Channels");
run("Coloc 2",
    "channel_1=C1-image channel_2=C2-image roi_or_mask=<None> "
  + "threshold_regression=Costes psf=3 costes_randomisations=10 "
  + "display_images_in_result li_histogram_channel_1 li_histogram_channel_2 li_icq");
```

Output (Log window): Pearson's R, Manders' M1/M2, Li's ICQ, Costes p-value.

### 1.8 Analyze Particles

```javascript
run("Set Measurements...",
    "area mean centroid shape_descriptors feret's integrated_density display redirect=None decimal=3");
run("Analyze Particles...",
    "size=50-Infinity circularity=0.3-1.0 show=Outlines display summarize exclude");
```

| Option | Effect |
|--------|--------|
| `size=MIN-MAX` | Area filter (calibrated). `50-Infinity` skips noise |
| `circularity=MIN-MAX` | Shape filter. `0.0-1.0` = all shapes |
| `show=Outlines\|Masks\|Overlay` | Visualization |
| `display` | Individual measurements to Results |
| `summarize` | Summary row |
| `exclude` | Exclude edge-touching objects |
| `add` | Add ROIs to ROI Manager |

Particle count: `n = nResults;`

### 1.9 AnalyzeSkeleton

```javascript
run("Skeletonize (2D/3D)");
run("Analyze Skeleton (2D/3D)", "prune=[shortest branch] show display");
```

Output: # Branches, # Junctions, # End-point voxels, Average/Maximum Branch Length, Triple/Quadruple points.

### 1.10 Nearest Neighbor Distances

Best done in Python after exporting centroids:
```python
from scipy.spatial import distance
dists = distance.cdist(coords, coords)
np.fill_diagonal(dists, np.inf)
nn_dists = dists.min(axis=1)
```

### 1.11 ROI-Based and Batch Measurements

```javascript
// Measure all ROIs
roiManager("Deselect");
roiManager("Measure");

// Batch: measure all images in a folder
dir = "/path/to/images/"; list = getFileList(dir);
run("Set Measurements...", "area mean shape_descriptors integrated_density display redirect=None decimal=3");
setBatchMode(true);
for (i = 0; i < list.length; i++) {
    if (endsWith(list[i], ".tif")) {
        open(dir + list[i]);
        run("Duplicate...", "title=mask");
        run("Gaussian Blur...", "sigma=1");
        setAutoThreshold("Otsu");
        run("Convert to Mask");
        run("Watershed");
        run("Analyze Particles...", "size=50-Infinity circularity=0.3-1.0 display exclude");
        close("mask"); close();
    }
}
setBatchMode(false);
saveAs("Results", "/path/to/output/all_results.csv");
```

---

## 2. Python Statistical Scripts

### Package Detection (use at top of every script)

```python
import numpy as np
from scipy import stats
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

try:
    import pandas as pd; HAS_PANDAS = True
except ImportError: HAS_PANDAS = False
try:
    import statsmodels.api as sm; from statsmodels.stats.multitest import multipletests; HAS_STATSMODELS = True
except ImportError: HAS_STATSMODELS = False
try:
    import pingouin as pg; HAS_PINGOUIN = True
except ImportError: HAS_PINGOUIN = False
```

Check availability at session start:
```bash
python -c "
import sys; print(f'Python {sys.version}')
for p in ['numpy','scipy','matplotlib','pandas','statsmodels','pingouin','scikit_posthocs']:
    try: mod=__import__(p); print(f'  {p}: {getattr(mod,\"__version__\",\"?\")}'  )
    except: print(f'  {p}: NOT INSTALLED')
"
```

---

### Script 1: Two-Group Comparison

Usage: `python stats_two_group.py results.csv "Area" "Group" --output plot.png`

```python
#!/usr/bin/env python
"""Two-group comparison: t-test or Mann-Whitney U with effect size and plot."""
import sys, csv, os, math
import numpy as np
from scipy import stats
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt
try: import pingouin as pg; HAS_PINGOUIN = True
except ImportError: HAS_PINGOUIN = False

def read_csv_groups(csv_path, value_col, group_col):
    groups = {}
    with open(csv_path, 'r') as f:
        for row in csv.DictReader(f):
            g = row[group_col].strip()
            try: groups.setdefault(g, []).append(float(row[value_col].strip()))
            except (ValueError, KeyError): continue
    names = sorted(groups.keys())
    if len(names) != 2: raise ValueError(f"Expected 2 groups, found {len(names)}: {names}")
    return names[0], np.array(groups[names[0]]), names[1], np.array(groups[names[1]])

def hedges_g(a, b):
    na, nb = len(a), len(b)
    pooled_sd = math.sqrt(((na-1)*np.var(a,ddof=1)+(nb-1)*np.var(b,ddof=1))/(na+nb-2))
    if pooled_sd == 0: return 0.0
    d = (np.mean(a)-np.mean(b))/pooled_sd
    return d * (1 - 3/(4*(na+nb)-9))

def two_group_test(csv_path, value_col, group_col, output_png=None):
    name1, g1, name2, g2 = read_csv_groups(csv_path, value_col, group_col)
    n1, n2 = len(g1), len(g2)
    results = [f"=== Two-Group Comparison: {value_col} by {group_col} ===",
               f"'{name1}': n={n1}, mean={np.mean(g1):.4f}, SD={np.std(g1,ddof=1):.4f}",
               f"'{name2}': n={n2}, mean={np.mean(g2):.4f}, SD={np.std(g2,ddof=1):.4f}", ""]

    # Normality + variance checks
    use_parametric = True
    if n1 >= 3 and n2 >= 3:
        _, p1 = stats.shapiro(g1) if n1 <= 5000 else stats.normaltest(g1)
        _, p2 = stats.shapiro(g2) if n2 <= 5000 else stats.normaltest(g2)
        results.append(f"Shapiro-Wilk: '{name1}' p={p1:.4f}, '{name2}' p={p2:.4f}")
        if p1 < 0.05 or p2 < 0.05: use_parametric = False
    else: use_parametric = False
    lev_stat, lev_p = stats.levene(g1, g2)
    results.append(f"Levene's: F={lev_stat:.4f}, p={lev_p:.4f}\n")

    # Tests
    if use_parametric:
        t_stat, t_p = stats.ttest_ind(g1, g2, equal_var=False)
        results.append(f"Welch's t-test: t={t_stat:.4f}, p={t_p:.6f}")
    u_stat, u_p = stats.mannwhitneyu(g1, g2, alternative='two-sided')
    results.append(f"Mann-Whitney U: U={u_stat:.1f}, p={u_p:.6f}")
    g_val = hedges_g(g1, g2)
    mag = "negligible" if abs(g_val)<0.2 else "small" if abs(g_val)<0.5 else "medium" if abs(g_val)<0.8 else "large"
    results.append(f"Hedges' g = {g_val:.4f} ({mag})")

    if HAS_PINGOUIN:
        try:
            r = pg.ttest(g1, g2, paired=False)
            results.append(f"BF10={r['BF10'].values[0]}, power={r['power'].values[0]:.4f}")
        except: pass

    primary_p = t_p if use_parametric else u_p
    primary_test = "Welch's t-test" if use_parametric else "Mann-Whitney U"
    results += ["", "=== SUMMARY ===", f"Test: {primary_test}, p = {primary_p:.6f}",
                f"Effect: g = {g_val:.4f} ({mag})"]

    # Plot: box + jitter
    if output_png is None: output_png = ".tmp/two_group_comparison.png"
    fig, ax = plt.subplots(figsize=(5,6))
    ax.boxplot([g1,g2], positions=[0,1], widths=0.5, patch_artist=True, showfliers=False,
               boxprops=dict(facecolor='lightblue',alpha=0.7), medianprops=dict(color='black',linewidth=2))
    for data, pos in [(g1,0),(g2,1)]:
        ax.scatter(pos+np.random.uniform(-0.15,0.15,len(data)), data, alpha=0.6, color='#2c3e50', s=30, zorder=3)
    ax.set_xticks([0,1]); ax.set_xticklabels([f"{name1}\n(n={n1})",f"{name2}\n(n={n2})"])
    ax.set_ylabel(value_col); ax.set_title(f"{value_col} by {group_col}")
    y_max=max(np.max(g1),np.max(g2)); y_range=y_max-min(np.min(g1),np.min(g2))
    by=y_max+0.05*y_range
    ax.plot([0,0,1,1],[by,by+0.02*y_range,by+0.02*y_range,by],'k-',linewidth=1.5)
    sig="***" if primary_p<0.001 else "**" if primary_p<0.01 else "*" if primary_p<0.05 else "n.s."
    ax.text(0.5,by+0.03*y_range,sig,ha='center',va='bottom',fontsize=14)
    ax.spines['top'].set_visible(False); ax.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if len(sys.argv) < 4: print("Usage: python stats_two_group.py <csv> <value_col> <group_col> [--output plot.png]"); sys.exit(1)
    out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
    two_group_test(sys.argv[1], sys.argv[2], sys.argv[3], out)
```

---

### Script 2: Multi-Group Comparison (ANOVA / Kruskal-Wallis)

Usage: `python stats_multi_group.py results.csv "Area" "Treatment" --output plot.png`

```python
#!/usr/bin/env python
"""Multi-group comparison with post-hoc tests and grouped plot."""
import sys, csv, os, math, itertools
import numpy as np
from scipy import stats
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt
try: from statsmodels.stats.multicomp import pairwise_tukeyhsd; HAS_STATSMODELS = True
except ImportError: HAS_STATSMODELS = False

def read_csv_multi(csv_path, value_col, group_col):
    groups = {}
    with open(csv_path, 'r') as f:
        for row in csv.DictReader(f):
            try: groups.setdefault(row[group_col].strip(), []).append(float(row[value_col].strip()))
            except: continue
    return {k: np.array(v) for k, v in groups.items()}

def dunn_test(groups_dict):
    """Dunn's test with Holm correction."""
    all_data, all_labels = [], []
    for name, vals in groups_dict.items():
        all_data.extend(vals); all_labels.extend([name]*len(vals))
    all_data = np.array(all_data); all_labels = np.array(all_labels)
    ranks = stats.rankdata(all_data); N = len(all_data)
    names = sorted(groups_dict.keys())
    mean_ranks = {n: np.mean(ranks[all_labels==n]) for n in names}
    group_ns = {n: np.sum(all_labels==n) for n in names}
    _, counts = np.unique(all_data, return_counts=True)
    tie_corr = 1 - np.sum(counts**3-counts)/(N**3-N) if N > 1 else 1
    pairs = list(itertools.combinations(names, 2))
    p_values = []
    for a, b in pairs:
        se = math.sqrt((N*(N+1)/12)*(1/group_ns[a]+1/group_ns[b])*tie_corr)
        z = abs(mean_ranks[a]-mean_ranks[b])/se if se > 0 else 0
        p_values.append(2*(1-stats.norm.cdf(abs(z))))
    # Holm correction
    m = len(p_values); order = np.argsort(p_values); p_adj = np.ones(m)
    for rank, idx in enumerate(order): p_adj[idx] = min(p_values[idx]*(m-rank), 1.0)
    for i in range(1,m):
        if p_adj[order[i]] < p_adj[order[i-1]]: p_adj[order[i]] = p_adj[order[i-1]]
    return [{'group1':a,'group2':b,'p_adj':p_adj[i]} for i,(a,b) in enumerate(pairs)]

def multi_group_test(csv_path, value_col, group_col, output_png=None):
    groups = read_csv_multi(csv_path, value_col, group_col)
    names = sorted(groups.keys()); k = len(names)
    if k < 3: print("Only {k} groups — use two-group script."); return
    arrays = [groups[n] for n in names]
    results = [f"=== Multi-Group: {value_col} by {group_col} ({k} groups) ==="]
    for n in names:
        g = groups[n]; results.append(f"  '{n}': n={len(g)}, mean={np.mean(g):.4f}, SD={np.std(g,ddof=1):.4f}")

    # Normality + omnibus
    all_normal = all(stats.shapiro(groups[n])[1] >= 0.05 for n in names if len(groups[n]) >= 3)
    if all_normal:
        f_stat, f_p = stats.f_oneway(*arrays)
        results.append(f"\nANOVA: F={f_stat:.4f}, p={f_p:.6f}"); omnibus_p=f_p; omnibus_test="ANOVA"
        grand = np.mean(np.concatenate(arrays))
        eta = sum(len(g)*(np.mean(g)-grand)**2 for g in arrays)/sum(np.sum((g-grand)**2) for g in arrays)
        results.append(f"Eta-squared = {eta:.4f}")
    else:
        h_stat, h_p = stats.kruskal(*arrays)
        results.append(f"\nKruskal-Wallis: H={h_stat:.4f}, p={h_p:.6f}"); omnibus_p=h_p; omnibus_test="Kruskal-Wallis"

    # Post-hoc
    if omnibus_p < 0.05:
        results.append("\n--- Post-hoc ---")
        if HAS_STATSMODELS and all_normal:
            all_v = np.concatenate(arrays); all_l = sum([[n]*len(groups[n]) for n in names],[])
            results.append(str(pairwise_tukeyhsd(all_v, all_l, alpha=0.05)))
        else:
            for d in dunn_test(groups):
                sig = "***" if d['p_adj']<0.001 else "**" if d['p_adj']<0.01 else "*" if d['p_adj']<0.05 else "n.s."
                results.append(f"  {d['group1']} vs {d['group2']}: p_adj={d['p_adj']:.6f} {sig}")

    # Plot
    if output_png is None: output_png = ".tmp/multi_group_comparison.png"
    fig, ax = plt.subplots(figsize=(max(5,k*1.5),6))
    bp = ax.boxplot(arrays, positions=range(k), widths=0.5, patch_artist=True, showfliers=False,
                    medianprops=dict(color='black',linewidth=2))
    colors = plt.cm.Set2(np.linspace(0,1,k))
    for i, p in enumerate(bp['boxes']): p.set_facecolor(colors[i])
    for i, n in enumerate(names):
        d = groups[n]; ax.scatter(i+np.random.uniform(-0.15,0.15,len(d)), d, alpha=0.5, color='#2c3e50', s=20, zorder=3)
    ax.set_xticks(range(k)); ax.set_xticklabels([f"{n}\n(n={len(groups[n])})" for n in names], fontsize=9)
    ax.set_ylabel(value_col); ax.set_title(f"{value_col} by {group_col}\n{omnibus_test}: p={omnibus_p:.4f}")
    ax.spines['top'].set_visible(False); ax.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if len(sys.argv) < 4: print("Usage: python stats_multi_group.py <csv> <value_col> <group_col> [--output plot.png]"); sys.exit(1)
    out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
    multi_group_test(sys.argv[1], sys.argv[2], sys.argv[3], out)
```

---

### Script 3: Paired Comparison

Usage: `python stats_paired.py results.csv "Before" "After" --output plot.png`

```python
#!/usr/bin/env python
"""Paired comparison: paired t-test or Wilcoxon signed-rank."""
import sys, csv, os, math
import numpy as np
from scipy import stats
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt

def read_paired(csv_path, col1, col2):
    v1, v2 = [], []
    with open(csv_path, 'r') as f:
        for row in csv.DictReader(f):
            try: v1.append(float(row[col1].strip())); v2.append(float(row[col2].strip()))
            except: continue
    return np.array(v1), np.array(v2)

def paired_test(csv_path, col1, col2, output_png=None):
    g1, g2 = read_paired(csv_path, col1, col2)
    n = len(g1); diffs = g1 - g2
    results = [f"=== Paired: {col1} vs {col2}, n={n} ===",
               f"Diffs: mean={np.mean(diffs):.4f}, SD={np.std(diffs,ddof=1):.4f}", ""]

    use_parametric = n >= 3 and (stats.shapiro(diffs)[1] if n <= 5000 else stats.normaltest(diffs)[1]) >= 0.05
    if use_parametric:
        t, p = stats.ttest_rel(g1, g2)
        results.append(f"Paired t-test: t({n-1})={t:.4f}, p={p:.6f}")
        primary_p, primary_test = p, f"Paired t-test (df={n-1})"
    if n >= 6:
        try:
            w, wp = stats.wilcoxon(g1, g2, alternative='two-sided')
            results.append(f"Wilcoxon signed-rank: W={w:.1f}, p={wp:.6f}")
            if not use_parametric: primary_p, primary_test = wp, "Wilcoxon"
        except ValueError: pass
    if np.std(diffs,ddof=1) > 0:
        dz = np.mean(diffs)/np.std(diffs,ddof=1)
        results.append(f"Cohen's dz = {dz:.4f}")

    # Plot: paired lines + difference histogram
    if output_png is None: output_png = ".tmp/paired_comparison.png"
    fig, (ax1,ax2) = plt.subplots(1,2,figsize=(10,6))
    for i in range(n):
        ax1.plot([0,1],[g1[i],g2[i]],'-o',color='#e74c3c' if g2[i]>g1[i] else '#3498db',alpha=0.5,markersize=6)
    ax1.set_xticks([0,1]); ax1.set_xticklabels([col1,col2]); ax1.set_ylabel("Value")
    ax2.hist(diffs,bins=max(5,n//3),color='steelblue',alpha=0.7,edgecolor='black')
    ax2.axvline(0,color='red',ls='--',lw=2); ax2.axvline(np.mean(diffs),color='green',ls='-',lw=2)
    ax2.set_xlabel(f"Difference ({col1} - {col2})")
    for a in [ax1,ax2]: a.spines['top'].set_visible(False); a.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if len(sys.argv) < 4: print("Usage: python stats_paired.py <csv> <col1> <col2> [--output plot.png]"); sys.exit(1)
    out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
    paired_test(sys.argv[1], sys.argv[2], sys.argv[3], out)
```

---

### Script 4: Correlation Analysis

Usage: `python stats_correlation.py results.csv "Area" "Mean" --output plot.png`

```python
#!/usr/bin/env python
"""Correlation analysis with scatter plot, regression line, and confidence band."""
import sys, csv, os, math
import numpy as np
from scipy import stats
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt

def read_two_cols(csv_path, col_x, col_y):
    x, y = [], []
    with open(csv_path, 'r') as f:
        for row in csv.DictReader(f):
            try: x.append(float(row[col_x].strip())); y.append(float(row[col_y].strip()))
            except: continue
    return np.array(x), np.array(y)

def correlation_analysis(csv_path, col_x, col_y, output_png=None):
    x, y = read_two_cols(csv_path, col_x, col_y)
    n = len(x)
    results = [f"=== Correlation: {col_x} vs {col_y}, n={n} ==="]

    # Both correlations
    r_p, p_p = stats.pearsonr(x, y)
    r_s, p_s = stats.spearmanr(x, y)
    results.append(f"Pearson's r = {r_p:.4f}, p = {p_p:.6f}, R²={r_p**2:.4f}")
    if n > 3:
        z = np.arctanh(r_p); se = 1/math.sqrt(n-3)
        results.append(f"  95% CI: [{np.tanh(z-1.96*se):.4f}, {np.tanh(z+1.96*se):.4f}]")
    results.append(f"Spearman's rho = {r_s:.4f}, p = {p_s:.6f}")

    # Normality-based recommendation
    both_normal = all((stats.shapiro(v)[1] if n<=5000 else stats.normaltest(v)[1]) >= 0.05 for v in [x,y]) if n>=3 else False
    primary_r, primary_p = (r_p, p_p) if both_normal else (r_s, p_s)
    primary_name = "Pearson's r" if both_normal else "Spearman's rho"
    strength = "negligible" if abs(primary_r)<0.1 else "weak" if abs(primary_r)<0.3 else "moderate" if abs(primary_r)<0.5 else "strong" if abs(primary_r)<0.7 else "very strong"
    results.append(f"\nRecommended: {primary_name} = {primary_r:.4f} ({strength} {'positive' if primary_r>0 else 'negative'})")

    slope, intercept, _, _, se_slope = stats.linregress(x, y)
    results.append(f"Regression: y = {slope:.4f}*x + {intercept:.4f}")

    # Plot
    if output_png is None: output_png = ".tmp/correlation_analysis.png"
    fig, ax = plt.subplots(figsize=(7,6))
    ax.scatter(x, y, alpha=0.5, color='#2c3e50', s=30, zorder=3)
    xl = np.linspace(np.min(x), np.max(x), 100)
    ax.plot(xl, slope*xl+intercept, 'r-', lw=2, label=f'y={slope:.3f}x+{intercept:.3f}')
    # 95% CI band
    xm = np.mean(x); ssx = np.sum((x-xm)**2); mse = np.sum((y-(slope*x+intercept))**2)/(n-2) if n>2 else 0
    tc = stats.t.ppf(0.975,n-2) if n>2 else 1.96
    se_l = np.sqrt(mse*(1/n+(xl-xm)**2/ssx)) if ssx>0 else np.zeros_like(xl)
    ax.fill_between(xl, slope*xl+intercept-tc*se_l, slope*xl+intercept+tc*se_l, alpha=0.15, color='red')
    ax.set_xlabel(col_x); ax.set_ylabel(col_y)
    ax.set_title(f"{primary_name} = {primary_r:.3f}, p = {primary_p:.4f}")
    ax.text(0.05,0.95,f"$R^2$={r_p**2:.3f}\nn={n}",transform=ax.transAxes,fontsize=10,va='top',
            bbox=dict(boxstyle='round',facecolor='wheat',alpha=0.5))
    ax.spines['top'].set_visible(False); ax.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if len(sys.argv) < 4: print("Usage: python stats_correlation.py <csv> <x_col> <y_col> [--output plot.png]"); sys.exit(1)
    out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
    correlation_analysis(sys.argv[1], sys.argv[2], sys.argv[3], out)
```

---

### Script 5: Mixed-Effects Model (Hierarchical Data)

Usage: `python stats_mixed.py results.csv "Mean" "Treatment" "Animal" --output plot.png`

```python
#!/usr/bin/env python
"""Mixed-effects model for hierarchical data (cells nested in animals)."""
import sys, csv, os, math
import numpy as np
from scipy import stats
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt
try: import statsmodels.formula.api as smf; import pandas as pd; HAS_MIXED = True
except ImportError: HAS_MIXED = False

def mixed_analysis(csv_path, value_col, group_col, rep_col, output_png=None):
    rows = []
    with open(csv_path, 'r') as f:
        for row in csv.DictReader(f):
            try: rows.append({'value':float(row[value_col].strip()),'group':row[group_col].strip(),'replicate':row[rep_col].strip()})
            except: continue

    groups, rep_vals = {}, {}
    for r in rows:
        groups.setdefault(r['group'],[]).append(r['value'])
        rep_vals.setdefault((r['group'],r['replicate']),[]).append(r['value'])
    names = sorted(groups.keys())
    rep_level = {g: np.array([np.mean(rep_vals[(g,r)]) for g2,r in rep_vals if g2==g]) for g in names}
    # Fix: build rep_level properly
    rep_level = {}
    for g in names:
        rep_level[g] = np.array([np.mean(v) for (gg,r),v in rep_vals.items() if gg==g])

    results = [f"=== Mixed-Effects: {value_col} ~ {group_col}, random: {rep_col} ==="]
    for g in names:
        nr = len(rep_level[g]); no = len(groups[g])
        results.append(f"  '{g}': {nr} bio reps, {no} obs, mean={np.mean(groups[g]):.4f}")

    # Mixed model (if available)
    if HAS_MIXED:
        try:
            df = pd.DataFrame(rows)
            fit = smf.mixedlm('value ~ C(group)', data=df, groups=df['replicate']).fit(reml=True)
            results.append(f"\n{fit.summary().as_text()}")
            vb = fit.cov_re.iloc[0,0]; vw = fit.scale
            results.append(f"ICC = {vb/(vb+vw):.4f}")
        except Exception as e:
            results.append(f"Mixed model failed: {e}")

    # Replicate-averaged fallback (always run)
    results.append("\n--- Replicate-Averaged ---")
    if len(names) == 2:
        g1m, g2m = rep_level[names[0]], rep_level[names[1]]
        if len(g1m)>=2 and len(g2m)>=2:
            t, p = stats.ttest_ind(g1m, g2m, equal_var=False)
            results.append(f"Welch's t on rep means: t={t:.4f}, p={p:.6f}")
    else:
        arrays = [rep_level[g] for g in names]
        if min(len(a) for a in arrays) >= 2:
            f, p = stats.f_oneway(*arrays)
            results.append(f"ANOVA on rep means: F={f:.4f}, p={p:.6f}")

    # SuperPlot
    if output_png is None: output_png = ".tmp/mixed_model_plot.png"
    fig, ax = plt.subplots(figsize=(max(5,len(names)*2),6))
    colors = plt.cm.Set2(np.linspace(0,1,8)); cidx = 0; cmap = {}
    for i, g in enumerate(names):
        ax.scatter(i+np.random.uniform(-0.2,0.2,len(groups[g])), groups[g], alpha=0.2, color='gray', s=10, zorder=2)
        for (gg,r),v in rep_vals.items():
            if gg != g: continue
            if r not in cmap: cmap[r]=colors[cidx%len(colors)]; cidx+=1
            ax.scatter(i, np.mean(v), color=cmap[r], s=120, zorder=4, edgecolors='black', linewidth=1.5)
        gm = np.mean(rep_level[g])
        gsem = np.std(rep_level[g],ddof=1)/math.sqrt(len(rep_level[g])) if len(rep_level[g])>1 else 0
        ax.errorbar(i, gm, yerr=gsem, fmt='_', color='black', linewidth=3, markersize=20, capsize=8, zorder=5)
    ax.set_xticks(range(len(names)))
    ax.set_xticklabels([f"{g}\nN={len(rep_level[g])},n={len(groups[g])}" for g in names], fontsize=9)
    ax.set_ylabel(value_col); ax.set_title(f"SuperPlot: {value_col} by {group_col}")
    ax.spines['top'].set_visible(False); ax.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    results.append("N = biological replicates; n = total observations")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if len(sys.argv) < 5: print("Usage: python stats_mixed.py <csv> <value> <group> <replicate> [--output plot.png]"); sys.exit(1)
    out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
    mixed_analysis(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], out)
```

---

### Script 6: Proportion Comparison (Fisher's Exact / Chi-Squared)

Usage: `python stats_proportion.py --counts 45 55 30 70` or `python stats_proportion.py results.csv "Positive" "Group"`

```python
#!/usr/bin/env python
"""Proportion comparison: Fisher's exact or chi-squared."""
import sys, csv, os, math
import numpy as np
from scipy import stats
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt

def proportion_test(a, b, c, d, name1="Group1", name2="Group2", output_png=None):
    """2x2: Group1 a+/b-, Group2 c+/d-"""
    table = np.array([[a,b],[c,d]]); n1,n2 = a+b,c+d
    p1,p2 = a/n1 if n1>0 else 0, c/n2 if n2>0 else 0
    results = [f"=== Proportions: {name1} {a}/{n1}={p1:.1%}, {name2} {c}/{n2}={p2:.1%} ==="]

    odds_ratio, fisher_p = stats.fisher_exact(table)
    results.append(f"Fisher's exact: OR={odds_ratio:.4f}, p={fisher_p:.6f}")
    if a>0 and b>0 and c>0 and d>0:
        se = math.sqrt(1/a+1/b+1/c+1/d)
        results.append(f"  OR 95% CI: [{math.exp(math.log(odds_ratio)-1.96*se):.4f}, {math.exp(math.log(odds_ratio)+1.96*se):.4f}]")

    expected = np.outer(table.sum(axis=1), table.sum(axis=0))/table.sum()
    if np.all(expected >= 5):
        chi2, chi_p, dof, _ = stats.chi2_contingency(table)
        results.append(f"Chi-squared: chi2={chi2:.4f}, df={dof}, p={chi_p:.6f}")

    # Plot
    if output_png is None: output_png = ".tmp/proportion_comparison.png"
    fig, ax = plt.subplots(figsize=(5,6))
    ax.bar([0,1],[p1,p2],color='#3498db',label='Positive',width=0.5)
    ax.bar([0,1],[1-p1,1-p2],bottom=[p1,p2],color='#ecf0f1',edgecolor='#bdc3c7',label='Negative',width=0.5)
    ax.set_xticks([0,1]); ax.set_xticklabels([f"{name1}\n({a}/{n1})",f"{name2}\n({c}/{n2})"])
    ax.set_ylabel("Proportion"); ax.set_title(f"Fisher's: p={fisher_p:.4f}")
    ax.text(0,p1/2,f"{p1:.1%}",ha='center',va='center',fontweight='bold')
    ax.text(1,p2/2,f"{p2:.1%}",ha='center',va='center',fontweight='bold')
    ax.spines['top'].set_visible(False); ax.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if "--counts" in sys.argv:
        idx = sys.argv.index("--counts")
        a,b,c,d = int(sys.argv[idx+1]),int(sys.argv[idx+2]),int(sys.argv[idx+3]),int(sys.argv[idx+4])
        out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
        proportion_test(a,b,c,d,output_png=out)
    elif len(sys.argv) >= 4:
        groups = {}
        with open(sys.argv[1],'r') as f:
            for row in csv.DictReader(f):
                g = row[sys.argv[3]].strip(); pos = row[sys.argv[2]].strip().lower() in ('1','1.0','yes','true','positive')
                groups.setdefault(g, {'pos':0,'neg':0}); groups[g]['pos' if pos else 'neg'] += 1
        gn = sorted(groups.keys())
        if len(gn)==2:
            out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
            proportion_test(groups[gn[0]]['pos'],groups[gn[0]]['neg'],groups[gn[1]]['pos'],groups[gn[1]]['neg'],gn[0],gn[1],out)
```

---

### Script 7: Dose-Response Curve

Usage: `python stats_dose_response.py results.csv "Concentration" "Response" --output plot.png`

```python
#!/usr/bin/env python
"""4-parameter logistic (Hill equation) dose-response fitting."""
import sys, csv, os
import numpy as np
from scipy import stats
from scipy.optimize import curve_fit
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt

def four_pl(x, bottom, top, ec50, hill):
    return bottom + (top-bottom)/(1+(ec50/np.clip(x,1e-15,None))**hill)

def dose_response(csv_path, dose_col, resp_col, output_png=None):
    doses, resps = [], []
    with open(csv_path,'r') as f:
        for row in csv.DictReader(f):
            try:
                d,r = float(row[dose_col].strip()), float(row[resp_col].strip())
                if d > 0: doses.append(d); resps.append(r)
            except: continue
    doses,resps = np.array(doses),np.array(resps)
    results = [f"=== Dose-Response, n={len(doses)} ==="]

    try:
        popt, pcov = curve_fit(four_pl, doses, resps,
            p0=[np.min(resps),np.max(resps),np.median(doses),1.0],
            bounds=([0,0,0,0.01],[np.inf,np.inf,np.inf,20]), maxfev=10000)
        bottom,top,ec50,hill = popt; perr = np.sqrt(np.diag(pcov))
        y_pred = four_pl(doses,*popt)
        r2 = 1-np.sum((resps-y_pred)**2)/np.sum((resps-np.mean(resps))**2)
        results += [f"Bottom={bottom:.4f}, Top={top:.4f}", f"EC50={ec50:.4g} +/- {perr[2]:.4g}",
                    f"Hill={hill:.4f}, R²={r2:.4f}"]
        fit_ok = True
    except Exception as e:
        results.append(f"Fit failed: {e}"); fit_ok = False

    if output_png is None: output_png = ".tmp/dose_response.png"
    fig, ax = plt.subplots(figsize=(7,5))
    ax.scatter(doses,resps,color='#2c3e50',alpha=0.6,s=40,zorder=3)
    if fit_ok:
        xf = np.logspace(np.log10(np.min(doses)*0.5),np.log10(np.max(doses)*2),200)
        ax.plot(xf,four_pl(xf,*popt),'r-',lw=2)
        ax.axvline(ec50,color='gray',ls='--',alpha=0.5)
        ax.annotate(f'EC50={ec50:.3g}',xy=(ec50,four_pl(ec50,*popt)),xytext=(ec50*3,four_pl(ec50,*popt)),
                    fontsize=10,arrowprops=dict(arrowstyle='->',color='red'))
    ax.set_xscale('log'); ax.set_xlabel(dose_col); ax.set_ylabel(resp_col)
    ax.set_title("Dose-Response (4PL)"); ax.spines['top'].set_visible(False); ax.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if len(sys.argv) < 4: print("Usage: python stats_dose_response.py <csv> <dose> <response> [--output plot.png]"); sys.exit(1)
    out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
    dose_response(sys.argv[1], sys.argv[2], sys.argv[3], out)
```

---

### Script 8: Multiple Comparisons Correction

Usage: `python stats_multiple_corrections.py pvalues.csv "p_value" [effect_col] [label_col] --output plot.png`

```python
#!/usr/bin/env python
"""Multiple comparisons correction (Bonferroni, Holm, BH) with volcano plot."""
import sys, csv, os
import numpy as np
from scipy import stats
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt
try: from statsmodels.stats.multitest import multipletests; HAS_SM = True
except ImportError: HAS_SM = False

def correct_pvalues(p_values):
    p = np.array(p_values, dtype=float); m = len(p)
    if HAS_SM:
        _,p_bonf,_,_ = multipletests(p,method='bonferroni')
        _,p_holm,_,_ = multipletests(p,method='holm')
        _,p_bh,_,_ = multipletests(p,method='fdr_bh')
    else:
        p_bonf = np.minimum(p*m, 1.0)
        # Holm
        order = np.argsort(p); p_holm = np.ones(m)
        for rank, idx in enumerate(order): p_holm[idx] = min(p[idx]*(m-rank), 1.0)
        for i in range(1,m):
            if p_holm[order[i]] < p_holm[order[i-1]]: p_holm[order[i]] = p_holm[order[i-1]]
        # BH
        p_bh = np.ones(m)
        for r, idx in enumerate(order): p_bh[idx] = p[idx]*m/(r+1)
        for i in range(m-2,-1,-1):
            if p_bh[order[i]] > p_bh[order[i+1]]: p_bh[order[i]] = p_bh[order[i+1]]
        p_bh = np.minimum(p_bh, 1.0)
    return {'raw':p, 'bonferroni':p_bonf, 'holm':p_holm, 'benjamini_hochberg':p_bh}

def multiple_corrections(csv_path, p_col, effect_col=None, label_col=None, output_png=None):
    pvals, effects, labels = [], [], []
    with open(csv_path,'r') as f:
        for i, row in enumerate(csv.DictReader(f)):
            try: pvals.append(float(row[p_col].strip()))
            except: continue
            effects.append(float(row[effect_col].strip()) if effect_col and effect_col in row else 0)
            labels.append(row[label_col].strip() if label_col and label_col in row else f"Test_{i+1}")
    pvals = np.array(pvals); effects = np.array(effects); m = len(pvals)
    c = correct_pvalues(pvals)
    results = [f"=== Multiple Corrections ({m} tests) ===",
               f"{'Label':<20} {'p(raw)':>10} {'Bonf':>10} {'Holm':>10} {'BH':>10}","-"*62]
    for i in range(m):
        sig = "*" if c['benjamini_hochberg'][i]<0.05 else ""
        results.append(f"{labels[i]:<20} {pvals[i]:10.6f} {c['bonferroni'][i]:10.6f} {c['holm'][i]:10.6f} {c['benjamini_hochberg'][i]:10.6f} {sig}")
    results += ["",f"Sig at 0.05: raw={np.sum(pvals<0.05)}, Bonf={np.sum(c['bonferroni']<0.05)}, "
                f"Holm={np.sum(c['holm']<0.05)}, BH={np.sum(c['benjamini_hochberg']<0.05)}",
                "Consider Holm for confirmatory, BH for exploratory."]

    if output_png is None: output_png = ".tmp/multiple_corrections.png"
    fig, ax = plt.subplots(figsize=(8,5))
    order = np.argsort(pvals); x = np.arange(1,m+1)
    ax.scatter(x,pvals[order],label='Raw',alpha=0.7,s=30)
    ax.scatter(x,c['holm'][order],label='Holm',alpha=0.7,s=30)
    ax.scatter(x,c['benjamini_hochberg'][order],label='BH',alpha=0.7,s=30)
    ax.axhline(0.05,color='red',ls='--',alpha=0.5,label='alpha=0.05')
    ax.set_xlabel("Rank"); ax.set_ylabel("p-value"); ax.legend(fontsize=9)
    ax.spines['top'].set_visible(False); ax.spines['right'].set_visible(False)
    plt.tight_layout()
    os.makedirs(os.path.dirname(output_png) or '.', exist_ok=True)
    plt.savefig(output_png, dpi=150, bbox_inches='tight'); plt.close()
    results.append(f"\nFigure saved: {output_png}")
    print("\n".join(results)); return "\n".join(results)

if __name__ == "__main__":
    if len(sys.argv) < 3: print("Usage: python stats_multiple_corrections.py <csv> <p_col> [effect_col] [label_col] [--output plot.png]"); sys.exit(1)
    effect = sys.argv[3] if len(sys.argv)>3 and not sys.argv[3].startswith("--") else None
    label = sys.argv[4] if len(sys.argv)>4 and not sys.argv[4].startswith("--") else None
    out = sys.argv[sys.argv.index("--output")+1] if "--output" in sys.argv else None
    multiple_corrections(sys.argv[1], sys.argv[2], effect, label, out)
```

---

## 3. Integration Workflow

### 3.1 End-to-End Example

```bash
# 1. Check state
python ij.py state

# 2. Set measurements
python ij.py macro 'run("Set Measurements...", "area mean centroid shape_descriptors integrated_density display redirect=None decimal=3");'

# 3. Segment and measure each image
python ij.py macro 'open("/path/to/WT_mouse1.tif");'
python ij.py capture after_open
python ij.py macro '
  run("Duplicate...", "title=mask"); run("Gaussian Blur...", "sigma=1");
  setAutoThreshold("Otsu"); run("Convert to Mask"); run("Watershed");
  run("Analyze Particles...", "size=50-Infinity circularity=0.3-1.0 display exclude");
  close("mask"); close();
'

# 4. Save and label results
python ij.py results > .tmp/raw_results.csv
python ij.py macro 'saveAs("Results", "/path/.tmp/results.csv");'

# 5. Add group labels (parse from filename)
python -c "
import csv, re
with open('.tmp/raw_results.csv') as f: rows = list(csv.DictReader(f))
with open('.tmp/labeled_results.csv','w',newline='') as f:
    flds = list(rows[0].keys())+['Group','Animal']
    w = csv.DictWriter(f,fieldnames=flds); w.writeheader()
    for r in rows:
        lbl = r.get('Label','')
        r['Group'] = 'WT' if 'WT' in lbl else 'KO' if 'KO' in lbl else 'unknown'
        m = re.search(r'mouse(\d+)', lbl)
        r['Animal'] = m.group(0) if m else 'unknown'
        w.writerow(r)
"

# 6. Run stats
python stats_mixed.py .tmp/labeled_results.csv "Area" "Group" "Animal" --output .tmp/area_mixed.png

# 7. Read figure and report
```

### 3.2 Decision Tree

| User question | Script | Key measurement |
|--------------|--------|-----------------|
| "Compare X between 2 groups" | `stats_two_group.py` | Any |
| "Compare X across 3+ groups" | `stats_multi_group.py` | Any |
| "Before vs after (same subjects)" | `stats_paired.py` | Any |
| "Does X correlate with Y?" | `stats_correlation.py` | Two continuous |
| "What proportion are positive?" | `stats_proportion.py` | Binary count |
| "What's the EC50?" | `stats_dose_response.py` | Dose + response |
| "Multiple tests done" | `stats_multiple_corrections.py` | p-values |
| "Account for animal variability" | `stats_mixed.py` | Any + replicate ID |
| "How many cells?" | Analyze Particles (nResults) | Count |
| "How branched?" | AnalyzeSkeleton | Branches, length |
| "How close together?" | Centroids + scipy NND | Distance |
| "Colocalized?" | Coloc 2 + log parsing | PCC, M1, M2 |

### 3.3 Common Extraction Patterns

```bash
# Cell counting
python ij.py macro '... Analyze Particles ... display summarize ...'
python ij.py results   # count = number of rows

# CTCF (redirect trick)
python ij.py macro '
  run("Set Measurements...", "area mean integrated_density display redirect=C2-original decimal=3");
  selectWindow("C1-mask");
  run("Analyze Particles...", "size=50-Infinity display exclude add");
'
# Calculate CTCF in Python from IntDen, Area, background Mean

# Colocalization
python ij.py macro 'run("Split Channels"); run("Coloc 2", "channel_1=C1-image channel_2=C2-image threshold_regression=Costes psf=3 costes_randomisations=10");'
python ij.py log   # parse PCC, M1, M2, Costes p

# Skeleton
python ij.py macro 'run("Skeletonize (2D/3D)"); run("Analyze Skeleton (2D/3D)", "prune=[shortest branch] show display");'
python ij.py results
```

---

## 4. Python Package Tiers

| Tier | Packages | What they provide |
|------|----------|-------------------|
| **1 (always)** | numpy, scipy, matplotlib | All t-tests, Mann-Whitney, ANOVA, Kruskal-Wallis, chi-sq, Fisher, correlations, Shapiro-Wilk, curve_fit, manual Holm/BH, all plots |
| **2 (typical)** | pandas, csv (stdlib) | DataFrame ops, CSV I/O, groupby |
| **3 (install)** | statsmodels | Mixed-effects, Tukey HSD, multipletests, Type II/III ANOVA, power analysis |
| **3 (install)** | pingouin | Clean API, auto effect sizes, Bayes factors, ICC, repeated-measures ANOVA |

Install: `pip install statsmodels pingouin` (suggest but do not install without asking).

---

## 5. Reporting Template

```
ANALYSIS: [Brief description]
DATA: Group1 N=[bio reps], n=[obs], mean +/- SD | Group2 ...
TEST: [Full name] (e.g., Welch's t-test)
STATISTIC: [with df] (e.g., t(8) = 3.42)
P-VALUE: [exact]
EFFECT SIZE: [name = value, interpretation]
95% CI: [lower, upper]
CONCLUSION: [One sentence]
FIGURE: [path]
CAVEATS: [small n, non-normality, pseudoreplication, etc.]
```
