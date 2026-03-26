# Method Validation & Agreement Statistics Reference

Validates automated image analysis against manual ground truth: segmentation metrics,
agreement statistics, inter-observer variability, power analysis, reporting standards.

---

## 1. Quick Start: Validate Automated Cell Count vs Manual

```python
import numpy as np
from scipy import stats

manual = np.array(manual_counts, dtype=float)
auto = np.array(auto_counts, dtype=float)

# Bland-Altman
diff = auto - manual
bias = np.mean(diff)
sd_diff = np.std(diff, ddof=1)
loa_upper = bias + 1.96 * sd_diff
loa_lower = bias - 1.96 * sd_diff

# ICC(2,1) — two-way random, absolute agreement
ratings = np.column_stack([manual, auto])
n, k = ratings.shape
gm = np.mean(ratings)
rm, cm_arr = np.mean(ratings, axis=1), np.mean(ratings, axis=0)
ss_r = k * np.sum((rm - gm)**2)
ss_c = n * np.sum((cm_arr - gm)**2)
ss_e = np.sum((ratings - gm)**2) - ss_r - ss_c
ms_r, ms_c, ms_e = ss_r/(n-1), ss_c/(k-1), ss_e/((n-1)*(k-1))
icc_val = (ms_r - ms_e) / (ms_r + (k-1)*ms_e + k*(ms_c - ms_e)/n)

# Lin's CCC
r = np.corrcoef(manual, auto)[0,1]
sx, sy = np.std(manual, ddof=1), np.std(auto, ddof=1)
ccc = (2*r*sx*sy) / (np.var(manual, ddof=1) + np.var(auto, ddof=1) + (np.mean(manual)-np.mean(auto))**2)

print(f"Bias: {bias:.2f}, LoA: [{loa_lower:.2f}, {loa_upper:.2f}]")
print(f"ICC(2,1) = {icc_val:.3f}, CCC = {ccc:.3f}")
```

---

## 2. When to Validate

| Trigger | Reason |
|---------|--------|
| New pipeline | No prior accuracy evidence |
| New image type (stain, microscope, magnification) | Parameters may not transfer |
| New biological condition | Morphology changes may break assumptions |
| Changed parameters | Different settings alter results |
| Before publication | Reviewers increasingly demand validation |
| Multi-site study | Ensure cross-instrument consistency |

**Validation levels:** Qualitative (looks right) < Quantitative (metrics on 10-20 images)
< Rigorous (power analysis, multiple annotators, held-out data) < Multi-site.
For publication, quantitative is the minimum; rigorous for methods papers.

---

## 3. Ground Truth Preparation

### 3.1 Ground Truth Types

| Task | GT Type | Tool |
|------|---------|------|
| Cell counting | Point annotations | Cell Counter plugin |
| Segmentation | Binary mask / label image | ROI Manager, Labkit |
| Object detection | Bounding boxes / points | ROI Manager |
| Classification | Category labels | Spreadsheet |
| Measurement | Expert measurements | Manual measurement in ImageJ |

### 3.2 Key ImageJ Macros

```
// ROIs to label image
newImage("Labels", "16-bit black", getWidth(), getHeight(), 1);
n = roiManager("count");
for (i = 0; i < n; i++) {
    roiManager("select", i);
    setColor(i + 1);
    fill();
}
run("Select None");
saveAs("Tiff", "/path/to/ground_truth_labels.tif");

// ROIs to binary mask
newImage("Mask", "8-bit black", getWidth(), getHeight(), 1);
n = roiManager("count");
for (i = 0; i < n; i++) {
    roiManager("select", i);
    setColor(255);
    fill();
}
run("Select None");
```

**Connected components from binary (requires MorphoLibJ):**
```
run("Connected Components Labeling", "connectivity=4 type=[16 bits]");
```

### 3.3 How Many Images to Annotate

| Metric | Minimum | Recommended | Notes |
|--------|---------|-------------|-------|
| Bland-Altman | 40 pairs | 100+ | Stable LoA estimates |
| ICC | 30 subjects | 50-100 | Depends on expected ICC |
| Dice/IoU | 20 images | 50+ | Report mean and SD |
| Counting | 30 images | 50+ | More if high variability |
| Cohen's kappa | 50 ratings | 100+ | More categories need more |
| Object detection AP | 100+ objects | 500+ | Per category |

**Guidance:** Typically 50 images is a reasonable standard. Objects matter more than
images: 20 images with 50 cells each (1000 objects) is more informative than 50 images
with 5 cells each.

### 3.4 Sampling

Use stratified random sampling to ensure the validation set includes images from all
experimental conditions. Consider at least 10 images per condition group, or 20% of each.

### 3.5 Inter-Annotator Ground Truth

For rigorous validation: 2-3 independent annotators with written guidelines.
Create consensus by majority vote or STAPLE algorithm (`sitk.STAPLE()`).
Inter-annotator agreement establishes the **ceiling** for automated performance.

---

## 4. Segmentation Metrics (Pixel-Level)

### Confusion Matrix

```
                Ground Truth
              Positive  Negative
Predicted Pos   TP         FP
          Neg   FN         TN
```

### 4.1 Metric Formulas and Interpretation

| Metric | Formula | Range | Interpretation Thresholds |
|--------|---------|-------|--------------------------|
| **Dice (F1)** | 2*TP / (2*TP+FP+FN) | 0-1 | >0.9 excellent, 0.8-0.9 good, 0.7-0.8 acceptable |
| **Jaccard (IoU)** | TP / (TP+FP+FN) | 0-1 | >0.8 excellent, 0.65-0.8 good, 0.5-0.65 moderate |
| **Precision** | TP / (TP+FP) | 0-1 | High = few false positives |
| **Recall** | TP / (TP+FN) | 0-1 | High = few false negatives |
| **Specificity** | TN / (TN+FP) | 0-1 | Useful with class imbalance |
| **Balanced Accuracy** | (Sensitivity+Specificity)/2 | 0-1 | Better than accuracy when imbalanced |

**Dice vs Jaccard:** Monotonically related (J = D/(2-D)). Dice is always >= Jaccard.
They rank methods identically. Report whichever is conventional in your field
(Dice for biomedical, IoU for computer vision).

### 4.2 ImageJ Macro — Dice/Jaccard/Precision/Recall

```
// Assumes "Predicted" and "GroundTruth" open as binary (0/255)
selectWindow("Predicted"); run("Divide...", "value=255");
selectWindow("GroundTruth"); run("Divide...", "value=255");

imageCalculator("Multiply create", "Predicted", "GroundTruth");
rename("TP_img");
selectWindow("TP_img"); getRawStatistics(nPx, mn); tp = nPx * mn;
selectWindow("Predicted"); getRawStatistics(nPx, mn); pred_pos = nPx * mn;
selectWindow("GroundTruth"); getRawStatistics(nPx, mn); gt_pos = nPx * mn;

fp = pred_pos - tp;
fn = gt_pos - tp;
dice = 2.0 * tp / (pred_pos + gt_pos);
jaccard = tp / (pred_pos + gt_pos - tp);
precision = tp / (tp + fp);
recall = tp / (tp + fn);

print("Dice=" + dice + " Jaccard=" + jaccard);
print("Precision=" + precision + " Recall=" + recall);
selectWindow("TP_img"); close();
```

### 4.3 Python — Core Metrics

```python
import numpy as np

def dice_coefficient(pred, gt):
    pred, gt = pred.astype(bool), gt.astype(bool)
    if pred.sum() == 0 and gt.sum() == 0: return 1.0
    return 2.0 * np.logical_and(pred, gt).sum() / (pred.sum() + gt.sum())

def jaccard_index(pred, gt):
    pred, gt = pred.astype(bool), gt.astype(bool)
    if pred.sum() == 0 and gt.sum() == 0: return 1.0
    inter = np.logical_and(pred, gt).sum()
    return float(inter) / float(np.logical_or(pred, gt).sum())

def precision_recall_f1(pred, gt):
    pred, gt = pred.astype(bool), gt.astype(bool)
    tp = np.logical_and(pred, gt).sum()
    fp = np.logical_and(pred, ~gt).sum()
    fn = np.logical_and(~pred, gt).sum()
    prec = tp/(tp+fp) if (tp+fp) > 0 else 0.0
    rec = tp/(tp+fn) if (tp+fn) > 0 else 0.0
    f1 = 2*prec*rec/(prec+rec) if (prec+rec) > 0 else 0.0
    return {'precision': prec, 'recall': rec, 'f1': f1, 'tp': int(tp), 'fp': int(fp), 'fn': int(fn)}
```

### 4.4 Hausdorff Distance

Captures worst-case boundary error. Use HD95 (95th percentile) for robustness to outliers.

```python
from scipy.spatial.distance import directed_hausdorff, cdist
from scipy.ndimage import binary_erosion

def hausdorff_distance(pred, gt, percentile=100):
    pred, gt = pred.astype(bool), gt.astype(bool)
    pred_b = pred ^ binary_erosion(pred)
    gt_b = gt ^ binary_erosion(gt)
    pc = np.array(np.where(pred_b)).T
    gc = np.array(np.where(gt_b)).T
    if len(pc) == 0 or len(gc) == 0: return float('inf')
    if percentile == 100:
        return max(directed_hausdorff(pc, gc)[0], directed_hausdorff(gc, pc)[0])
    dists = cdist(pc, gc)
    return max(np.percentile(np.min(dists, axis=1), percentile),
               np.percentile(np.min(dists, axis=0), percentile))
```

### 4.5 Boundary F1 Score

Precision/recall on boundary pixels within a tolerance distance. Tolerance: consider
starting at 2 pixels, adjusting based on expected boundary uncertainty.

```python
from scipy.ndimage import binary_erosion, binary_dilation

def boundary_f1(pred, gt, tolerance=2):
    pred, gt = pred.astype(bool), gt.astype(bool)
    pred_b = pred ^ binary_erosion(pred)
    gt_b = gt ^ binary_erosion(gt)
    gt_b_d = binary_dilation(gt_b, iterations=tolerance)
    pred_b_d = binary_dilation(pred_b, iterations=tolerance)
    bp = np.logical_and(pred_b, gt_b_d).sum() / pred_b.sum() if pred_b.sum() > 0 else (1.0 if gt_b.sum() == 0 else 0.0)
    br = np.logical_and(gt_b, pred_b_d).sum() / gt_b.sum() if gt_b.sum() > 0 else (1.0 if pred_b.sum() == 0 else 0.0)
    bf = 2*bp*br/(bp+br) if (bp+br) > 0 else 0.0
    return {'bf1': bf, 'boundary_precision': bp, 'boundary_recall': br}
```

### 4.6 Panoptic Quality (Instance Segmentation)

PQ = SQ * RQ, where SQ = mean IoU of matched pairs, RQ = detection F1.
Matching: IoU > 0.5 (typically). Always report SQ and RQ separately.

```python
def panoptic_quality(pred_labels, gt_labels, iou_threshold=0.5):
    pred_ids = set(np.unique(pred_labels)) - {0}
    gt_ids = set(np.unique(gt_labels)) - {0}
    matched_gt, matched_pred, matched_ious = set(), set(), []
    for gt_id in gt_ids:
        gt_mask = gt_labels == gt_id
        overlapping = set(np.unique(pred_labels[gt_mask])) - {0}
        best_iou, best_pred = 0.0, None
        for pred_id in overlapping:
            if pred_id in matched_pred: continue
            pred_mask = pred_labels == pred_id
            inter = np.logical_and(pred_mask, gt_mask).sum()
            union = np.logical_or(pred_mask, gt_mask).sum()
            iou = inter/union if union > 0 else 0.0
            if iou > best_iou: best_iou, best_pred = iou, pred_id
        if best_iou > iou_threshold and best_pred is not None:
            matched_gt.add(gt_id); matched_pred.add(best_pred); matched_ious.append(best_iou)
    tp, fp, fn = len(matched_ious), len(pred_ids-matched_pred), len(gt_ids-matched_gt)
    sq = np.mean(matched_ious) if tp > 0 else 0.0
    rq = (2.0*tp)/(2.0*tp+fp+fn) if (2*tp+fp+fn) > 0 else 0.0
    return {'pq': sq*rq, 'sq': sq, 'rq': rq, 'tp': tp, 'fp': fp, 'fn': fn}
```

### 4.7 Metric Comparison Table

| Metric | Type | Range | Strengths | Weaknesses |
|--------|------|-------|-----------|------------|
| Dice | Overlap | 0-1 | Most widely used in biomedical | No boundary quality |
| Jaccard | Overlap | 0-1 | CV standard, stricter than Dice | Same as Dice |
| Hausdorff/HD95 | Boundary | 0-inf (lower=better) | Worst-case boundary error | Sensitive to outliers (use HD95) |
| BF Score | Boundary | 0-1 | Boundary with tolerance | Depends on tolerance param |
| PQ | Instance | 0-1 | Unified detection+segmentation | Instance segmentation only |

**Recommended reporting:** Dice + HD95 or BF for semantic segmentation.
PQ (with SQ, RQ) for instance segmentation. Report mean, SD, range.

### 4.8 Batch Dice (ImageJ Macro)

```
pred_dir = "/path/to/predictions/";
gt_dir = "/path/to/ground_truth/";
output_csv = "/path/to/dice_results.csv";
pred_list = getFileList(pred_dir);
File.append("Image,Dice,Precision,Recall", output_csv);

for (i = 0; i < pred_list.length; i++) {
    if (!endsWith(pred_list[i], ".tif")) continue;
    open(pred_dir + pred_list[i]); rename("Predicted");
    open(gt_dir + pred_list[i]); rename("GroundTruth");
    selectWindow("Predicted"); run("Divide...", "value=255");
    selectWindow("GroundTruth"); run("Divide...", "value=255");
    imageCalculator("Multiply create", "Predicted", "GroundTruth");
    rename("TP_img");
    selectWindow("TP_img"); getRawStatistics(nPx, mn); tp = nPx * mn;
    selectWindow("Predicted"); getRawStatistics(nPx, mn); pa = nPx * mn;
    selectWindow("GroundTruth"); getRawStatistics(nPx, mn); ga = nPx * mn;
    fp = pa - tp; fn = ga - tp;
    dice = (pa+ga > 0) ? 2.0*tp/(pa+ga) : 1.0;
    prec = (tp+fp > 0) ? tp/(tp+fp) : 0;
    rec = (tp+fn > 0) ? tp/(tp+fn) : 0;
    File.append(pred_list[i]+","+dice+","+prec+","+rec, output_csv);
    selectWindow("TP_img"); close();
    selectWindow("Predicted"); close();
    selectWindow("GroundTruth"); close();
}
```

---

## 5. Object Detection Metrics

### 5.1 Object Matching

Match predicted objects to ground truth using IoU-based Hungarian algorithm.
IoU threshold: typically 0.5 for detection, higher for strict evaluation.

```python
from scipy.optimize import linear_sum_assignment

def match_objects(pred_labels, gt_labels, iou_threshold=0.5):
    pred_ids = [i for i in np.unique(pred_labels) if i > 0]
    gt_ids = [i for i in np.unique(gt_labels) if i > 0]
    if not pred_ids or not gt_ids:
        return {'tp': 0, 'fp': len(pred_ids), 'fn': len(gt_ids), 'matches': []}
    iou_matrix = np.zeros((len(gt_ids), len(pred_ids)))
    for i, gid in enumerate(gt_ids):
        gm = gt_labels == gid
        for j, pid in enumerate(pred_ids):
            pm = pred_labels == pid
            inter = np.logical_and(pm, gm).sum()
            union = np.logical_or(pm, gm).sum()
            iou_matrix[i,j] = inter/union if union > 0 else 0.0
    gi, pi = linear_sum_assignment(1.0 - iou_matrix)
    matches = [(gt_ids[g], pred_ids[p], iou_matrix[g,p])
               for g,p in zip(gi,pi) if iou_matrix[g,p] >= iou_threshold]
    tp = len(matches)
    return {'tp': tp, 'fp': len(pred_ids)-tp, 'fn': len(gt_ids)-tp, 'matches': matches}
```

**Object-level F1** is NOT pixel-level F1 (Dice). Merging adjacent cells gives high
pixel overlap but poor object-level F1.

### 5.2 Average Precision (AP)

Sort detections by confidence, compute cumulative precision-recall curve, AP = area under it.
**AP@0.5** (lenient), **AP@0.75** (strict), **mAP** (COCO: 0.5-0.95 in 0.05 steps).

### 5.3 Counting Metrics

| Metric | Formula |
|--------|---------|
| MAE | mean(\|predicted - GT\|) |
| MAPE | mean(\|predicted - GT\| / GT * 100) |
| Mean signed error | mean(predicted - GT) — shows systematic over/undercounting |

### 5.4 Point-Based Detection Matching

When GT is point annotations (Cell Counter), match by Euclidean distance instead of IoU.
Distance threshold: consider using mean cell diameter as starting point.

```python
from scipy.spatial.distance import cdist
from scipy.optimize import linear_sum_assignment

def match_points(pred_points, gt_points, max_distance=20):
    """max_distance: choose based on typical cell diameter."""
    if len(pred_points) == 0 or len(gt_points) == 0:
        return {'tp': 0, 'fp': len(pred_points), 'fn': len(gt_points)}
    dist_matrix = cdist(gt_points, pred_points)
    gi, pi = linear_sum_assignment(dist_matrix)
    matches = [(g,p,dist_matrix[g,p]) for g,p in zip(gi,pi) if dist_matrix[g,p] <= max_distance]
    tp = len(matches)
    fp, fn = len(pred_points)-tp, len(gt_points)-tp
    prec = tp/(tp+fp) if (tp+fp) > 0 else 0.0
    rec = tp/(tp+fn) if (tp+fn) > 0 else 0.0
    f1 = 2*prec*rec/(prec+rec) if (prec+rec) > 0 else 0.0
    return {'tp': tp, 'fp': fp, 'fn': fn, 'precision': prec, 'recall': rec, 'f1': f1}
```

---

## 6. Measurement Agreement (Continuous Variables)

### 6.1 Bland-Altman Analysis

**What it answers:** If I replace method A with method B, how much could results differ?

| Statistic | Formula | Meaning |
|-----------|---------|---------|
| Bias | mean(d) | Systematic difference |
| SD of differences | SD(d) | Random variability |
| Upper LoA | bias + 1.96*SD | Upper 95% bound |
| Lower LoA | bias - 1.96*SD | Lower 95% bound |
| CI of bias | bias +/- t * SD/sqrt(n) | Precision of bias |
| CI of LoA | LoA +/- t * SD*sqrt(3/n) | Precision of LoA |

**Interpretation:** Define acceptable difference BEFORE analysis. If LoA fall within
that range, methods are interchangeable.

**Assumptions to check:**
1. Differences approximately normal (Shapiro-Wilk test)
2. No proportional bias (regress difference on mean; if slope != 0, use log-transform or % differences)
3. Constant spread across measurement range

```python
import numpy as np
import matplotlib.pyplot as plt
from scipy import stats

def bland_altman_analysis(method1, method2, method1_name="Method 1",
                          method2_name="Method 2", acceptable_range=None, savepath=None):
    m1, m2 = np.array(method1, float), np.array(method2, float)
    n = len(m1)
    mean_both = (m1 + m2) / 2.0
    diff = m2 - m1
    bias, sd = np.mean(diff), np.std(diff, ddof=1)
    loa_up, loa_lo = bias + 1.96*sd, bias - 1.96*sd
    t_crit = stats.t.ppf(0.975, n-1)
    se_bias, se_loa = sd/np.sqrt(n), sd*np.sqrt(3.0/n)

    # Test proportional bias
    slope, _, _, p_prop, _ = stats.linregress(mean_both, diff)

    fig, ax = plt.subplots(figsize=(8, 6))
    ax.scatter(mean_both, diff, alpha=0.6, edgecolors='k', linewidths=0.5)
    ax.axhline(bias, color='red', label=f'Bias={bias:.2f}')
    ax.axhline(loa_up, color='blue', ls='--', label=f'LoA={loa_up:.2f}')
    ax.axhline(loa_lo, color='blue', ls='--', label=f'LoA={loa_lo:.2f}')
    ax.axhline(0, color='grey', ls=':', alpha=0.5)
    if acceptable_range:
        ax.axhline(acceptable_range[0], color='green', ls='-.', alpha=0.5, label='Acceptable')
        ax.axhline(acceptable_range[1], color='green', ls='-.', alpha=0.5)
    ax.set_xlabel(f'Mean of {method1_name} and {method2_name}')
    ax.set_ylabel(f'{method2_name} - {method1_name}')
    ax.legend(fontsize=8)
    plt.tight_layout()
    if savepath: plt.savefig(savepath, dpi=150)
    plt.show()

    return {'n': n, 'bias': bias, 'sd_diff': sd, 'loa_upper': loa_up, 'loa_lower': loa_lo,
            'ci_bias': (bias-t_crit*se_bias, bias+t_crit*se_bias),
            'proportional_bias': p_prop < 0.05, 'proportional_bias_p': p_prop}
```

**For proportional bias:** Use percentage differences: `pct_diff = (m2-m1)/((m1+m2)/2)*100`

**For repeated measures:** Use image-level means as the unit, not individual cells.

### 6.2 Intraclass Correlation Coefficient (ICC)

**What it measures:** Proportion of total variance due to true between-subject differences.

**Choosing ICC form:**

| Scenario | ICC Form |
|----------|----------|
| Automated vs manual (absolute agreement) | **ICC(2,1)** — most common for validation |
| Automated vs manual (consistency only) | ICC(3,1) |
| Multiple annotators | ICC(2,1) absolute agreement |
| Test-retest same method | ICC(3,1) consistency |
| Average of k raters | ICC(2,k) or ICC(3,k) |

**Interpretation (Koo & Li 2016):**

| ICC | Interpretation |
|-----|----------------|
| < 0.50 | Poor |
| 0.50-0.75 | Moderate |
| 0.75-0.90 | Good |
| > 0.90 | Excellent |

```python
def icc(ratings, icc_type='ICC(2,1)'):
    """ratings: (n_subjects, n_raters) array."""
    n, k = ratings.shape
    gm = np.mean(ratings)
    rm, cm_arr = np.mean(ratings, axis=1), np.mean(ratings, axis=0)
    ss_r = k * np.sum((rm - gm)**2)
    ss_c = n * np.sum((cm_arr - gm)**2)
    ss_e = np.sum((ratings - gm)**2) - ss_r - ss_c
    ms_r = ss_r/(n-1); ms_c = ss_c/(k-1); ms_e = ss_e/((n-1)*(k-1))
    ms_w = (np.sum((ratings-gm)**2) - ss_r) / (n*(k-1))

    if icc_type == 'ICC(1,1)': val = (ms_r-ms_w)/(ms_r+(k-1)*ms_w)
    elif icc_type == 'ICC(2,1)': val = (ms_r-ms_e)/(ms_r+(k-1)*ms_e+k*(ms_c-ms_e)/n)
    elif icc_type == 'ICC(3,1)': val = (ms_r-ms_e)/(ms_r+(k-1)*ms_e)
    elif icc_type == 'ICC(2,k)': val = (ms_r-ms_e)/(ms_r+(ms_c-ms_e)/n)
    elif icc_type == 'ICC(3,k)': val = (ms_r-ms_e)/ms_r
    else: raise ValueError(f"Unknown: {icc_type}")

    # CI via F distribution
    f_val = ms_r/ms_e
    df1, df2 = n-1, (n-1)*(k-1)
    f_lo = f_val / stats.f.ppf(0.975, df1, df2)
    f_hi = f_val * stats.f.ppf(0.975, df2, df1)
    ci_lo = (f_lo-1)/(f_lo+k-1) if icc_type in ['ICC(2,1)','ICC(3,1)'] else None
    ci_hi = (f_hi-1)/(f_hi+k-1) if icc_type in ['ICC(2,1)','ICC(3,1)'] else None
    p_val = 1 - stats.f.cdf(f_val, df1, df2)
    return {'icc': val, 'ci_lower': ci_lo, 'ci_upper': ci_hi, 'p_value': p_val, 'type': icc_type}
```

### 6.3 Lin's Concordance Correlation Coefficient (CCC)

CCC = Pearson r * bias correction. Captures both precision AND accuracy.
**Why CCC > Pearson r:** r=1.0 is possible even when methods systematically differ.
CCC correctly penalises systematic bias.

| CCC | Interpretation |
|-----|----------------|
| > 0.99 | Almost perfect |
| 0.95-0.99 | Substantial |
| 0.90-0.95 | Moderate |
| < 0.90 | Poor |

```python
def lins_ccc(x, y, confidence=0.95):
    x, y = np.array(x, float), np.array(y, float)
    n = len(x)
    r = np.corrcoef(x, y)[0,1]
    sx, sy = np.std(x, ddof=1), np.std(y, ddof=1)
    vx, vy = np.var(x, ddof=1), np.var(y, ddof=1)
    mx, my = np.mean(x), np.mean(y)
    c_b = (2*sx*sy)/(vx+vy+(mx-my)**2)
    ccc = r * c_b
    # CI via Fisher z
    z = np.arctanh(ccc)
    se_z = 1.0/np.sqrt(n-3)
    z_crit = stats.norm.ppf(1-(1-confidence)/2)
    return {'ccc': ccc, 'ci_lower': np.tanh(z-z_crit*se_z),
            'ci_upper': np.tanh(z+z_crit*se_z), 'pearson_r': r, 'bias_correction': c_b}
```

### 6.4 Correlation (Pearson/Spearman)

**Correlation measures association, NOT agreement.** Two methods can correlate perfectly
(r=1.0) while completely disagreeing (one always double the other). Report only alongside
ICC/CCC/Bland-Altman, never alone.

### 6.5 Passing-Bablok Regression

Non-parametric method comparison regression. If 95% CI of intercept includes 0 AND
slope CI includes 1, methods are equivalent. Intercept != 0 = constant bias;
slope != 1 = proportional bias.

```python
def passing_bablok(x, y, confidence=0.95):
    x, y = np.array(x, float), np.array(y, float)
    n = len(x)
    slopes = sorted([(y[j]-y[i])/(x[j]-x[i])
                     for i in range(n) for j in range(i+1,n) if x[i] != x[j]])
    slopes = np.array(slopes)
    n_neg = np.sum(slopes < -1)
    k = n_neg
    ns = len(slopes)
    slope_est = slopes[k + ns//2] if ns%2==0 else slopes[k + (ns+1)//2 - 1]
    intercept_est = np.median(y - slope_est * x)
    # CI
    z = stats.norm.ppf(1-(1-confidence)/2)
    c = z * np.sqrt(n*(n-1)*(2*n+5)/18.0)
    m1 = max(0, int(np.round((ns-c)/2.0)))
    m2 = min(ns-1, int(np.round((ns+c)/2.0))+1)
    slope_ci = (slopes[m1+k], slopes[min(m2+k, ns-1)])
    intercept_ci = (np.median(y-slope_ci[1]*x), np.median(y-slope_ci[0]*x))
    return {'slope': slope_est, 'slope_ci': slope_ci,
            'intercept': intercept_est, 'intercept_ci': intercept_ci}
```

### 6.6 Which Agreement Metric?

| Question | Metric |
|----------|--------|
| Are two methods interchangeable? | **Bland-Altman** (always do first) |
| How reliable is the measurement? | **ICC** |
| Single agreement number? | **CCC** |
| Systematic bias type? | **Passing-Bablok** |
| Values associated? | Pearson/Spearman (supplement only) |

**Minimum for publication:** Bland-Altman plot + ICC or CCC with confidence intervals.

---

## 7. Categorical Agreement

### 7.1 Cohen's Kappa

Agreement corrected for chance. K = (p_observed - p_chance) / (1 - p_chance).

| Kappa | Interpretation |
|-------|----------------|
| < 0.00 | No agreement |
| 0.00-0.20 | Slight |
| 0.21-0.40 | Fair |
| 0.41-0.60 | Moderate |
| 0.61-0.80 | Substantial |
| 0.81-1.00 | Almost perfect |

**Weighted kappa for ordinal data** (e.g., IHC grades 0/1+/2+/3+): use `weights='quadratic'`
to give partial credit for near misses.

```python
from sklearn.metrics import cohen_kappa_score, confusion_matrix

kappa = cohen_kappa_score(rater1, rater2, weights=None)        # nominal
kappa_w = cohen_kappa_score(rater1, rater2, weights='quadratic')  # ordinal
```

### 7.2 Fleiss' Kappa (3+ raters)

```python
def fleiss_kappa(ratings_matrix):
    """ratings_matrix: (n_subjects, n_categories), cell = count of raters assigning that category."""
    n, k = ratings_matrix.shape
    N = ratings_matrix[0].sum()
    p_j = np.sum(ratings_matrix, axis=0) / (n*N)
    p_i = (np.sum(ratings_matrix**2, axis=1) - N) / (N*(N-1))
    p_bar, p_e = np.mean(p_i), np.sum(p_j**2)
    return (p_bar - p_e) / (1 - p_e) if p_e != 1.0 else 1.0
```

### 7.3 Percent Agreement

Misleading alone (does not correct for chance). Report only alongside kappa.

---

## 8. Inter-Observer Variability

IOV establishes the **ceiling** for automated performance. If experts agree at Dice=0.85,
an automated method at Dice=0.83 is essentially human-level.

**Study design:** Typically 3+ independent annotators, 30-50+ images, written guidelines,
no consultation during annotation. Include training phase on 5-10 practice images.

**Consensus ground truth methods:**

| Method | Approach |
|--------|----------|
| Majority vote | Pixel positive if >50% of annotators agree |
| STAPLE | Probabilistic fusion (`sitk.STAPLE()`) — estimates per-annotator sensitivity/specificity |
| Arbitration | Discussion of disagreements by senior expert |

```python
def majority_vote_mask(masks):
    stacked = np.stack(masks, axis=0).astype(float)
    return (np.sum(stacked, axis=0) > len(masks)/2.0).astype(np.uint8)
```

---

## 9. Power Analysis (Sample Size)

### Quick Reference

| Metric | Minimum n | Recommended n | Units |
|--------|-----------|---------------|-------|
| Bland-Altman | 40 | 100 | Paired measurements |
| ICC | 15-25 | 50 | Subjects |
| CCC | 30 | 50 | Paired measurements |
| Cohen's kappa | 50 | 100 | Classified items |
| Dice/IoU | 20 | 50 | Images |
| Object detection | 200 objects | 500+ | Objects |

### Bland-Altman Sample Size

CI half-width for LoA ~ 1.96 * SD * sqrt(3/n). To achieve desired half-width `w`:
`n = 3 * (1.96 * expected_SD / w)^2`

| Desired CI half-width | Approximate n |
|----------------------|---------------|
| 0.5 * SD | ~47 |
| 0.4 * SD | ~73 |
| 0.3 * SD | ~130 |

### ICC Sample Size (approximate)

| Expected ICC | n for CI width 0.2 | n for CI width 0.1 |
|-------------|--------------------|--------------------|
| 0.5 | ~25 | ~100 |
| 0.7 | ~20 | ~75 |
| 0.9 | ~15 | ~50 |

### Objects vs Images

Total objects = n_images * mean_objects_per_image. For object-level metrics, typically
need 200-500+ objects. For pixel-level (Dice), 20-50 images is usually sufficient.

---

## 10. Agent Workflow: Validation via TCP

```bash
# Single-image Dice check
python ij.py macro '
  open("/path/to/ground_truth.tif"); rename("GroundTruth");
  open("/path/to/predicted.tif"); rename("Predicted");
  selectWindow("Predicted"); setThreshold(1, 255); run("Convert to Mask");
  selectWindow("GroundTruth"); setThreshold(1, 255); run("Convert to Mask");
  selectWindow("Predicted"); run("Divide...", "value=255");
  selectWindow("GroundTruth"); run("Divide...", "value=255");
  imageCalculator("Multiply create", "Predicted", "GroundTruth");
  rename("Inter"); getRawStatistics(nPx, mn); inter = nPx * mn;
  selectWindow("Predicted"); getRawStatistics(nPx, mn); pa = nPx * mn;
  selectWindow("GroundTruth"); getRawStatistics(nPx, mn); ga = nPx * mn;
  print("Dice = " + (2.0 * inter / (pa + ga)));
  selectWindow("Inter"); close();
'
python ij.py log

# For batch validation, use Python with the functions from this reference
```

---

## 11. Reporting

### Which Metrics for Which Task

| Task | Primary | Secondary | Plot |
|------|---------|-----------|------|
| Cell counting | ICC or CCC | Bland-Altman bias+LoA, MAE | Bland-Altman |
| Binary segmentation | Dice | Precision, Recall, HD95, BF1 | Example segmentations |
| Instance segmentation | PQ (SQ, RQ) | Object F1, AP | Detection overlay |
| Measurement agreement | ICC + CCC | Bland-Altman | B-A + scatter |
| Classification | Cohen's kappa | Accuracy, confusion matrix | Confusion matrix |
| Multi-rater | Fleiss' kappa / ICC | Pairwise kappa | Agreement matrix |

### Minimum Reporting Checklist

| Element | Required? |
|---------|-----------|
| Ground truth method and who created it | Yes |
| Number of images/objects | Yes |
| Sampling strategy | Yes |
| Agreement metric(s) with CIs | Yes |
| Inter-observer variability | Recommended |
| Software versions | Recommended |
| Training/test split | If applicable |

### Template Methods Sentences

**Cell counting:** "Automated counts validated against manual counts by [N] observer(s)
across [N] images. ICC(2,1) = [val] (95% CI: [lo]-[hi]). Bland-Altman bias = [val]
(LoA: [lo] to [hi]). CCC = [val]."

**Segmentation:** "Mean Dice = [val] (SD=[val], range [min]-[max]) across [N] images.
Precision=[val], Recall=[val], HD95=[val] pixels."

**Classification:** "Cohen's weighted kappa = [val] (95% CI: [lo]-[hi]), overall
accuracy [val]%, balanced accuracy [val]%."

---

## 12. Common Pitfalls

| Pitfall | Problem | Fix |
|---------|---------|-----|
| Using correlation alone | r measures association, not agreement | Use ICC, CCC, or Bland-Altman |
| Reporting bias without LoA | Small bias can hide large individual disagreement | Always report bias AND LoA |
| Validating on easy images only | Optimistic performance estimate | Random/stratified sampling |
| Circular validation | Tuning and validating on same images | Separate dev/validation sets |
| Spatial dependence | Cells from same image are not independent | Use image-level summaries or mixed models |
| Mean Dice without distribution | Hides bimodal failures | Report mean, SD, range; show histogram |
| Wrong ICC form | ICC(3,1) ignores systematic bias | Use ICC(2,1) for method comparison |
| Kappa paradox | Imbalanced categories artificially lower kappa | Report alongside % agreement and prevalence |
| Post-hoc acceptable thresholds | Biased by observed results | Define acceptable agreement BEFORE analysis |
| Dice and IoU as independent | Monotonically related: J=D/(2-D) | Report one; mention the other |
| Ignoring edge cases | Empty masks cause division by zero | Both empty = 1.0; one empty = 0.0 |
| Comparing on different datasets | Differences may reflect image difficulty | Same images, paired tests |

---

## 13. Decision Trees

### Which Agreement Metric

```
Continuous measurements (counts, areas, intensities)
  ├── Comparing two methods? → Bland-Altman + CCC + ICC(2,1)
  │     └── Proportional bias? → Passing-Bablok + log-transform B-A
  └── Reliability (same method repeated)? → ICC(3,1) or ICC(2,1)

Binary segmentation masks
  ├── Semantic (one class)? → Dice + HD95 or BF1
  └── Instance (individual objects)? → PQ (SQ+RQ) + object F1 + AP

Categorical classifications
  ├── Two raters, nominal? → Cohen's kappa (unweighted)
  ├── Two raters, ordinal? → Cohen's kappa (quadratic weights)
  └── 3+ raters? → Fleiss' kappa

Object detection
  ├── With confidence scores? → Average Precision (AP)
  └── Without? → Object-level Precision, Recall, F1
```

### Which ICC Form

```
Same raters for all subjects?
  YES → Absolute agreement needed? → ICC(2,1) [MOST COMMON for method comparison]
        Consistency only? → ICC(3,1)
  NO (different random raters per subject) → ICC(1,1)
Average of k raters? → ICC(2,k) or ICC(3,k)
```

### Minimum Reporting by Task

```
Cell counting:    REQUIRED: ICC(2,1)+CI, Bland-Altman+LoA  |  RECOMMENDED: CCC, MAE/MAPE
Segmentation:     REQUIRED: Dice+SD, example images        |  RECOMMENDED: Precision, Recall, HD95
Instance seg:     REQUIRED: PQ (SQ+RQ), object F1          |  RECOMMENDED: AP, overlays
Measurement:      REQUIRED: Bland-Altman+LoA, ICC/CCC+CI   |  RECOMMENDED: Passing-Bablok
Classification:   REQUIRED: Kappa+CI, confusion matrix      |  RECOMMENDED: Per-class metrics
```

---

## 14. Key References

| Topic | Reference |
|-------|-----------|
| Bland-Altman | Bland & Altman, Lancet 1986 |
| ICC | Shrout & Fleiss, Psych Bull 1979 |
| CCC | Lin, Biometrics 1989 |
| Cohen's kappa | Cohen, Ed & Psych Meas 1960 |
| Fleiss' kappa | Fleiss, Psych Bull 1971 |
| Kappa thresholds | Landis & Koch, Biometrics 1977 |
| ICC guidelines | Koo & Li, J Chiropr Med 2016 |
| Metrics framework | Maier-Hein et al., Nature Methods 2024 |
| Panoptic Quality | Kirillov et al., CVPR 2019 |
| B-A sample size | Lu et al., Int J Biostat 2016 |
| ICC sample size | Bonett, Stat Med 2002 |

**Software:** sklearn.metrics (kappa, confusion matrix), scipy.spatial.distance
(Hausdorff, cdist), pingouin (ICC), SimpleITK (STAPLE, overlap measures).

---

## Appendix: Compact Utility Functions

```python
"""validation_utils.py — copy-paste ready."""
import numpy as np
from scipy import stats
from scipy.spatial.distance import directed_hausdorff, cdist
from scipy.ndimage import binary_erosion, binary_dilation
from scipy.optimize import linear_sum_assignment

def dice_coefficient(pred, gt):
    pred, gt = pred.astype(bool), gt.astype(bool)
    if pred.sum()==0 and gt.sum()==0: return 1.0
    return 2.0*np.logical_and(pred,gt).sum()/(pred.sum()+gt.sum())

def jaccard_index(pred, gt):
    pred, gt = pred.astype(bool), gt.astype(bool)
    if pred.sum()==0 and gt.sum()==0: return 1.0
    return float(np.logical_and(pred,gt).sum())/float(np.logical_or(pred,gt).sum())

def precision_recall_f1(pred, gt):
    pred, gt = pred.astype(bool), gt.astype(bool)
    tp=np.logical_and(pred,gt).sum(); fp=np.logical_and(pred,~gt).sum(); fn=np.logical_and(~pred,gt).sum()
    p=tp/(tp+fp) if tp+fp>0 else 0.0; r=tp/(tp+fn) if tp+fn>0 else 0.0
    f=2*p*r/(p+r) if p+r>0 else 0.0
    return {'precision':p,'recall':r,'f1':f,'tp':int(tp),'fp':int(fp),'fn':int(fn)}

def hausdorff_distance(pred, gt, percentile=100):
    pred, gt = pred.astype(bool), gt.astype(bool)
    pb, gb = pred^binary_erosion(pred), gt^binary_erosion(gt)
    pc, gc = np.array(np.where(pb)).T, np.array(np.where(gb)).T
    if len(pc)==0 or len(gc)==0: return float('inf')
    if percentile==100: return max(directed_hausdorff(pc,gc)[0], directed_hausdorff(gc,pc)[0])
    d=cdist(pc,gc); return max(np.percentile(np.min(d,1),percentile), np.percentile(np.min(d,0),percentile))

def boundary_f1(pred, gt, tolerance=2):
    pred, gt = pred.astype(bool), gt.astype(bool)
    pb, gb = pred^binary_erosion(pred), gt^binary_erosion(gt)
    gbd, pbd = binary_dilation(gb,iterations=tolerance), binary_dilation(pb,iterations=tolerance)
    bp=np.logical_and(pb,gbd).sum()/pb.sum() if pb.sum()>0 else (1.0 if gb.sum()==0 else 0.0)
    br=np.logical_and(gb,pbd).sum()/gb.sum() if gb.sum()>0 else (1.0 if pb.sum()==0 else 0.0)
    return {'bf1': 2*bp*br/(bp+br) if bp+br>0 else 0.0, 'boundary_precision':bp, 'boundary_recall':br}

def panoptic_quality(pred_labels, gt_labels, iou_threshold=0.5):
    pids, gids = set(np.unique(pred_labels))-{0}, set(np.unique(gt_labels))-{0}
    mg, mp, mi = set(), set(), []
    for gid in gids:
        gm = gt_labels==gid; ov = set(np.unique(pred_labels[gm]))-{0}
        bi, bp = 0.0, None
        for pid in ov:
            if pid in mp: continue
            pm = pred_labels==pid; iou = np.logical_and(pm,gm).sum()/np.logical_or(pm,gm).sum()
            if iou > bi: bi, bp = iou, pid
        if bi > iou_threshold and bp: mg.add(gid); mp.add(bp); mi.append(bi)
    tp,fp,fn = len(mi), len(pids-mp), len(gids-mg)
    sq = np.mean(mi) if tp>0 else 0.0
    rq = 2.0*tp/(2.0*tp+fp+fn) if 2*tp+fp+fn>0 else 0.0
    return {'pq':sq*rq, 'sq':sq, 'rq':rq, 'tp':tp, 'fp':fp, 'fn':fn}

def bland_altman_stats(m1, m2):
    m1, m2 = np.array(m1,float), np.array(m2,float)
    d = m2-m1; n=len(d); b=np.mean(d); s=np.std(d,ddof=1)
    t=stats.t.ppf(0.975,n-1); se_b=s/np.sqrt(n); se_l=s*np.sqrt(3.0/n)
    return {'bias':b,'sd':s,'loa_upper':b+1.96*s,'loa_lower':b-1.96*s,
            'ci_bias':(b-t*se_b,b+t*se_b)}

def icc_2_1(ratings):
    n,k=ratings.shape; gm=np.mean(ratings)
    rm,cm_a=np.mean(ratings,1),np.mean(ratings,0)
    ssr=k*np.sum((rm-gm)**2); ssc=n*np.sum((cm_a-gm)**2)
    sse=np.sum((ratings-gm)**2)-ssr-ssc
    msr,msc,mse=ssr/(n-1),ssc/(k-1),sse/((n-1)*(k-1))
    return (msr-mse)/(msr+(k-1)*mse+k*(msc-mse)/n)

def lins_ccc_simple(x, y):
    x,y=np.array(x,float),np.array(y,float)
    r=np.corrcoef(x,y)[0,1]; sx,sy=np.std(x,ddof=1),np.std(y,ddof=1)
    return (2*r*sx*sy)/(np.var(x,ddof=1)+np.var(y,ddof=1)+(np.mean(x)-np.mean(y))**2)

def counting_metrics(pred, gt):
    p,g=np.array(pred,float),np.array(gt,float)
    ae=np.abs(p-g); pe=ae/np.where(g>0,g,1)*100
    return {'mae':np.mean(ae),'mape':np.mean(pe),'mean_signed_error':np.mean(p-g),
            'max_abs_error':np.max(ae),'pearson_r':np.corrcoef(p,g)[0,1]}
```

### ImageJ Macro Quick Reference

```
// --- Dice between two open binary masks (0/255) ---
macro "Calculate_Dice" {
    selectWindow("Predicted"); run("Divide...", "value=255");
    selectWindow("GroundTruth"); run("Divide...", "value=255");
    imageCalculator("Multiply create", "Predicted", "GroundTruth");
    rename("_inter_");
    selectWindow("_inter_"); getRawStatistics(n, m); inter = n * m;
    selectWindow("Predicted"); getRawStatistics(n, m); pa = n * m;
    selectWindow("GroundTruth"); getRawStatistics(n, m); ga = n * m;
    print("Dice = " + (2.0 * inter / (pa + ga)));
    selectWindow("_inter_"); close();
}

// --- ROIs to label image ---
macro "ROIs_to_Labels" {
    w = getWidth(); h = getHeight();
    newImage("GT_Labels", "16-bit black", w, h, 1);
    n = roiManager("count");
    for (i = 0; i < n; i++) { roiManager("select", i); setColor(i+1); fill(); }
    run("Select None");
}

// --- Error map (FP=red, FN=blue, TP=green) ---
macro "Error_Map" {
    selectWindow("Predicted"); run("Divide...", "value=255");
    selectWindow("GroundTruth"); run("Divide...", "value=255");
    imageCalculator("Multiply create", "Predicted", "GroundTruth"); rename("TP");
    imageCalculator("Subtract create", "Predicted", "TP"); rename("FP");
    imageCalculator("Subtract create", "GroundTruth", "TP"); rename("FN");
    selectWindow("TP"); run("Multiply...", "value=255");
    selectWindow("FP"); run("Multiply...", "value=255");
    selectWindow("FN"); run("Multiply...", "value=255");
    run("Merge Channels...", "c1=FP c2=TP c3=FN create"); rename("Error_Map");
}
```

---

## Comprehensive Metric Comparison

| Metric | Category | Input | Range | Better | Chance-Corrected | Typical Threshold |
|--------|----------|-------|-------|--------|------------------|-------------------|
| Dice | Overlap | Binary masks | 0-1 | Higher | No | >0.8 good |
| Jaccard | Overlap | Binary masks | 0-1 | Higher | No | >0.65 good |
| Precision | Classification | Binary masks | 0-1 | Higher | No | Context |
| Recall | Classification | Binary masks | 0-1 | Higher | No | Context |
| Hausdorff/HD95 | Boundary | Binary masks | 0-inf | Lower | No | Context |
| BF Score | Boundary | Binary masks | 0-1 | Higher | No | >0.8 good |
| PQ | Instance | Label images | 0-1 | Higher | No | >0.5 acceptable |
| AP | Detection | Labels+conf | 0-1 | Higher | No | >0.5 acceptable |
| ICC | Agreement | Continuous | -1 to 1 | Higher | Partially | >0.75 good |
| CCC | Agreement | Continuous | -1 to 1 | Higher | N/A | >0.90 moderate |
| Pearson r | Association | Continuous | -1 to 1 | Higher | No | NOT for agreement |
| B-A bias | Agreement | Continuous | -inf to inf | Near 0 | N/A | Pre-specified |
| Cohen's kappa | Agreement | Categorical | -1 to 1 | Higher | Yes | >0.61 substantial |
| Fleiss' kappa | Agreement | Categorical | -1 to 1 | Higher | Yes | >0.61 substantial |
