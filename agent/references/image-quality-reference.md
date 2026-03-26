# Image Quality, Artifacts & Assessment Reference

Merged reference for detecting, quantifying, and correcting microscopy image quality
problems. All code is ImageJ macro language executable via `python ij.py macro`.

Sources: QUAREP-LiMi, Nature Methods checklists (2023), Cromey "Avoiding Twisted Pixels",
MetroloJ_QC, NanoJ-SQUIRREL, NoiSee, JCB/Nature/Cell image integrity guidelines.

---

## 1. Quick QC Protocol

Run on every image before analysis. Prints PASS/WARN/FAIL for each metric.

```
Step 1: Open image -> check metadata (calibration, bit depth)
Step 2: Focus -> Laplacian variance
Step 3: Saturation -> % pixels at max
Step 4: SNR -> signal mean vs background noise
Step 5: Background -> tile CV for illumination uniformity
Step 6: Dynamic range -> effective bits used
Step 7: Staining -> bimodality coefficient (if applicable)
-> PASS / WARN (proceed with caveats) / FAIL (reject or correct first)
```

### 1.1 Master QC Macro

```javascript
// Run on any open image. Returns "PASS", "WARN", or "FAIL".
function imageQC() {
    title = getTitle();
    getDimensions(w, h, channels, slices, frames);
    bd = bitDepth();
    getRawStatistics(nPixels, mean, min, max, stdDev);
    maxPossible = pow(2, bd) - 1;
    print("\\Clear");
    print("=== IMAGE QC REPORT: " + title + " ===");
    print("Dimensions: " + w + "x" + h + ", " + bd + "-bit");
    print("Channels: " + channels + ", Slices: " + slices + ", Frames: " + frames);
    nPass = 0; nWarn = 0; nFail = 0;

    // --- 1. Saturation ---
    nSaturated = 0;
    for (y = 0; y < h; y++) {
        for (x = 0; x < w; x++) {
            if (getPixel(x, y) >= maxPossible) nSaturated++;
        }
    }
    satPct = (nSaturated / nPixels) * 100;
    if (satPct > 1.0) { print("FAIL: Saturation " + d2s(satPct,2) + "%"); nFail++; }
    else if (satPct > 0.1) { print("WARN: Saturation " + d2s(satPct,2) + "%"); nWarn++; }
    else { print("PASS: Saturation " + d2s(satPct,4) + "%"); nPass++; }

    // --- 2. Focus (Laplacian variance) ---
    run("Duplicate...", "title=_qc_lap");
    run("32-bit");
    run("Convolve...", "text1=[0 1 0\n1 -4 1\n0 1 0\n] normalize");
    getRawStatistics(n2, mean2, min2, max2, stdDev2);
    lapVar = stdDev2 * stdDev2;
    close();
    selectWindow(title);
    if (bd == 16) normFactor = 65535.0 / 255.0; else normFactor = 1.0;
    lapVarNorm = lapVar / (normFactor * normFactor);
    if (lapVarNorm < 20) { print("FAIL: Focus LapVar=" + d2s(lapVarNorm,1)); nFail++; }
    else if (lapVarNorm < 100) { print("WARN: Focus LapVar=" + d2s(lapVarNorm,1)); nWarn++; }
    else { print("PASS: Focus LapVar=" + d2s(lapVarNorm,1)); nPass++; }

    // --- 3. SNR (Otsu-based) ---
    run("Duplicate...", "title=_qc_snr");
    setAutoThreshold("Otsu dark");
    getThreshold(otsuLow, otsuHigh);
    close();
    selectWindow(title);
    bgM = 0; bgN = 0; sigM = 0; sigN = 0;
    step = maxOf(1, floor(sqrt(nPixels / 10000)));
    for (yy = 0; yy < h; yy += step) {
        for (xx = 0; xx < w; xx += step) {
            v = getPixel(xx, yy);
            if (v < otsuLow) { bgM += v; bgN++; }
            else { sigM += v; sigN++; }
        }
    }
    if (bgN > 0) bgM = bgM / bgN;
    if (sigN > 0) sigM = sigM / sigN;
    bgSS = 0;
    for (yy = 0; yy < h; yy += step) {
        for (xx = 0; xx < w; xx += step) {
            v = getPixel(xx, yy);
            if (v < otsuLow) bgSS += (v - bgM) * (v - bgM);
        }
    }
    bgSD = 0;
    if (bgN > 1) bgSD = sqrt(bgSS / (bgN - 1));
    snr = 0;
    if (bgSD > 0) snr = (sigM - bgM) / bgSD;
    if (snr < 3) { print("FAIL: SNR=" + d2s(snr,1)); nFail++; }
    else if (snr < 5) { print("WARN: SNR=" + d2s(snr,1)); nWarn++; }
    else { print("PASS: SNR=" + d2s(snr,1)); nPass++; }

    // --- 4. Illumination uniformity ---
    regionSize = minOf(w, h) / 8;
    cx = w / 2; cy = h / 2;
    makeRectangle(cx - regionSize/2, cy - regionSize/2, regionSize, regionSize);
    getStatistics(nC, meanCenter);
    makeRectangle(0, 0, regionSize, regionSize); getStatistics(nTL, meanTL);
    makeRectangle(w - regionSize, 0, regionSize, regionSize); getStatistics(nTR, meanTR);
    makeRectangle(0, h - regionSize, regionSize, regionSize); getStatistics(nBL, meanBL);
    makeRectangle(w - regionSize, h - regionSize, regionSize, regionSize); getStatistics(nBR, meanBR);
    run("Select None");
    cornerMean = (meanTL + meanTR + meanBL + meanBR) / 4;
    uniformityRatio = cornerMean / maxOf(meanCenter, 0.001);
    if (uniformityRatio < 0.6) { print("FAIL: Vignetting corners=" + d2s(uniformityRatio*100,0) + "%"); nFail++; }
    else if (uniformityRatio < 0.85) { print("WARN: Uniformity corners=" + d2s(uniformityRatio*100,0) + "%"); nWarn++; }
    else { print("PASS: Uniformity corners=" + d2s(uniformityRatio*100,0) + "%"); nPass++; }

    // --- 5. Dynamic range ---
    rangeUsed = max - min;
    if (bd <= 16) {
        effectiveBits = log(maxOf(rangeUsed, 1)) / log(2);
        if (effectiveBits < 4) { print("WARN: Effective bits=" + d2s(effectiveBits,1)); nWarn++; }
        else { print("PASS: Effective bits=" + d2s(effectiveBits,1)); nPass++; }
    }

    // --- Summary ---
    print("");
    print("=== SUMMARY: " + nPass + " PASS, " + nWarn + " WARN, " + nFail + " FAIL ===");
    if (nFail > 0) { print("VERDICT: FAIL"); return "FAIL"; }
    else if (nWarn > 0) { print("VERDICT: WARN"); return "WARN"; }
    else { print("VERDICT: PASS"); return "PASS"; }
}
result = imageQC();
```

### 1.2 Quick Go/No-Go (One-Liner)

```javascript
function quickQC() {
    run("Duplicate...", "title=_qqc"); run("32-bit");
    run("Convolve...", "text1=[0 1 0\n1 -4 1\n0 1 0\n] normalize");
    getRawStatistics(n, m, mn, mx, s); lapVar = s * s; close("_qqc");
    bd = bitDepth();
    if (bd == 16) lapVar = lapVar / (256 * 256);
    getHistogram(v, c, 256); getRawStatistics(nPix, mean);
    satPct = (c[255] / nPix) * 100;
    pass = (lapVar > 50) && (satPct < 1.0);
    if (!pass) print("Quick QC FAIL: lapVar=" + d2s(lapVar,0) + " sat=" + d2s(satPct,2) + "%");
    return pass;
}
```

### 1.3 Individual Metric One-Liners (Parseable Output)

**Focus:**
```javascript
run("Duplicate...", "title=_t"); run("32-bit");
run("Convolve...", "text1=[0 1 0\n1 -4 1\n0 1 0\n] normalize");
getRawStatistics(n, m, mn, mx, s); print("QC_LAPLACIAN_VARIANCE=" + (s*s)); close("_t");
```

**Saturation:**
```javascript
getRawStatistics(nP, m, mn, mx, s); getHistogram(v, c, 256);
print("QC_SATURATED_PCT=" + d2s(c[255]/nP*100, 4));
```

**SNR (quick):**
```javascript
getRawStatistics(nP, m, mn, mx, s);
print("QC_SNR_ESTIMATE=" + d2s(m/maxOf(s, 0.001), 1));
```

**Background CV:**
```javascript
w=getWidth(); h=getHeight(); tW=floor(w/4); tH=floor(h/4);
ms=newArray(16); idx=0;
for(ty=0;ty<4;ty++){for(tx=0;tx<4;tx++){
makeRectangle(tx*tW,ty*tH,tW,tH); getRawStatistics(n,m); ms[idx]=m; idx++;}}
run("Select None"); Array.getStatistics(ms,mn,mx,gm,gs);
print("QC_TILE_CV=" + d2s(gs/gm*100, 1));
```

---

## 2. Focus Quality Metrics

### 2.1 Metric Comparison

| Metric | Speed | Noise Robust | Sparse Images | Best For |
|--------|-------|-------------|---------------|----------|
| Laplacian Variance | Fast | Moderate | Good | General purpose, first choice |
| Brenner Gradient | Medium | Low | Good | Fine detail detection |
| Normalised Variance | Fast | Low | Fair | Comparing across exposures |
| Tenengrad (Sobel) | Medium | Good | Good | Reliable for most microscopy |
| Vollath F4 | Medium | Excellent | Fair | Noisy images (confocal, low-light) |
| Entropy | Fast | Moderate | Poor | Supplementary metric |
| HF Ratio (fast) | Fast | Good | Fair | Quick screening |

**Recommendation:** Use Laplacian Variance as the primary metric. Add Vollath F4
for noisy images. For z-stacks, use per-slice Laplacian Variance.

### 2.2 Laplacian Variance Thresholds (8-bit normalised)

| Value | Interpretation |
|-------|---------------|
| > 500 | Excellent focus |
| 100-500 | Acceptable |
| 20-100 | Marginal -- may affect measurements |
| < 20 | Out of focus -- reject |

NOTE: Scale 16-bit values by dividing by (65535/255)^2. Sparse fluorescence
images naturally have lower values than dense tissue.

### 2.3 Laplacian Variance

```javascript
function laplacianVariance() {
    run("Duplicate...", "title=_lap_temp"); run("32-bit");
    run("Convolve...", "text1=[0 1 0\n1 -4 1\n0 1 0\n] normalize");
    getRawStatistics(nPixels, mean, min, max, std);
    lapVar = std * std; close(); return lapVar;
}
```

### 2.4 Brenner Gradient (Fast)

```javascript
function brennerGradientFast() {
    run("Duplicate...", "title=_bren_orig"); run("32-bit");
    run("Duplicate...", "title=_bren_shift");
    run("Translate...", "x=-2 y=0 interpolation=None");
    imageCalculator("Subtract create 32-bit", "_bren_shift", "_bren_orig");
    rename("_bren_diff");
    imageCalculator("Multiply create 32-bit", "_bren_diff", "_bren_diff");
    rename("_bren_sq"); getRawStatistics(nPixels, mean); result = mean;
    close("_bren_sq"); close("_bren_diff"); close("_bren_shift"); close("_bren_orig");
    return result;
}
```

### 2.5 Tenengrad (Sobel)

```javascript
function tenengrad() {
    id = getImageID();
    run("Duplicate...", "title=_ten_gx"); run("32-bit");
    run("Duplicate...", "title=_ten_gy");
    selectImage("_ten_gx");
    run("Convolve...", "text1=[-1 0 1\n-2 0 2\n-1 0 1\n] normalize");
    imageCalculator("Multiply create 32-bit", "_ten_gx", "_ten_gx"); rename("_ten_gx2");
    selectImage("_ten_gy");
    run("Convolve...", "text1=[-1 -2 -1\n0 0 0\n1 2 1\n] normalize");
    imageCalculator("Multiply create 32-bit", "_ten_gy", "_ten_gy"); rename("_ten_gy2");
    imageCalculator("Add create 32-bit", "_ten_gx2", "_ten_gy2"); rename("_ten_mag");
    getRawStatistics(nPixels, mean); result = mean;
    close("_ten_mag"); close("_ten_gx2"); close("_ten_gy2");
    close("_ten_gx"); close("_ten_gy"); selectImage(id); return result;
}
```

### 2.6 Vollath F4 (Noise-Robust Autocorrelation)

```javascript
function vollathF4() {
    run("Duplicate...", "title=_vf4_orig"); run("32-bit");
    run("Duplicate...", "title=_vf4_s1");
    run("Translate...", "x=-1 y=0 interpolation=None");
    imageCalculator("Multiply create 32-bit", "_vf4_orig", "_vf4_s1");
    rename("_vf4_prod1"); getRawStatistics(n1, lag1mean);
    selectImage("_vf4_orig");
    run("Duplicate...", "title=_vf4_s2");
    run("Translate...", "x=-2 y=0 interpolation=None");
    imageCalculator("Multiply create 32-bit", "_vf4_orig", "_vf4_s2");
    rename("_vf4_prod2"); getRawStatistics(n2, lag2mean);
    result = lag1mean - lag2mean;
    close("_vf4_prod1"); close("_vf4_prod2");
    close("_vf4_s1"); close("_vf4_s2"); close("_vf4_orig");
    return result;
}
```

### 2.7 Normalised Variance / Entropy / HF Ratio

```javascript
function normalisedVariance() {
    getRawStatistics(nPixels, mean, min, max, std);
    if (mean == 0) return 0; return (std * std) / mean;
}

function entropyFocus() {
    nBins = 256; getHistogram(values, counts, nBins);
    nPixels = 0; for (i = 0; i < nBins; i++) nPixels += counts[i];
    entropy = 0;
    for (i = 0; i < nBins; i++) {
        if (counts[i] > 0) { p = counts[i] / nPixels; entropy -= p * (log(p) / log(2)); }
    }
    return entropy;
}

function hfRatioFast() {
    getRawStatistics(nPixels, mean, min, max, std); totalVar = std * std;
    run("Duplicate...", "title=_hf_hp"); run("32-bit");
    run("Duplicate...", "title=_hf_lp"); run("Gaussian Blur...", "sigma=10");
    imageCalculator("Subtract create 32-bit", "_hf_hp", "_hf_lp"); rename("_hf_diff");
    getRawStatistics(n2, m2, mn2, mx2, std2); hfVar = std2 * std2;
    close("_hf_diff"); close("_hf_lp"); close("_hf_hp");
    if (totalVar == 0) return 0; return hfVar / totalVar;
}
```

### 2.8 Best Focus Slice (Z-Stack)

```javascript
function findBestFocusSlice() {
    nSlices = nSlices(); if (nSlices < 2) return 1;
    bestSlice = 1; bestScore = 0;
    for (s = 1; s <= nSlices; s++) {
        setSlice(s);
        run("Duplicate...", "title=_focus_slice"); run("32-bit");
        run("Convolve...", "text1=[0 1 0\n1 -4 1\n0 1 0\n] normalize");
        getRawStatistics(nPixels, mean, min, max, std);
        score = std * std;
        if (score > bestScore) { bestScore = score; bestSlice = s; }
        close("_focus_slice");
    }
    print("Best focus: slice " + bestSlice + " (score: " + d2s(bestScore,2) + ")");
    return bestSlice;
}
```

### 2.9 Tilt / Partial Focus Detection

```javascript
function detectTilt() {
    w = getWidth(); h = getHeight(); id = getImageID();
    qw = floor(w/2); qh = floor(h/2);
    labels = newArray("TL","TR","BL","BR");
    xs = newArray(0, qw, 0, qw); ys = newArray(0, 0, qh, qh);
    scores = newArray(4);
    for (q = 0; q < 4; q++) {
        selectImage(id); makeRectangle(xs[q], ys[q], qw, qh);
        run("Duplicate...", "title=_quad_temp"); run("32-bit");
        run("Convolve...", "text1=[0 1 0\n1 -4 1\n0 1 0\n] normalize");
        getStatistics(n, mean, min, max, stdDev); scores[q] = stdDev * stdDev; close();
    }
    selectImage(id); run("Select None");
    Array.getStatistics(scores, qMin, qMax, qMean, qStd);
    ratio = qMin / maxOf(qMax, 0.001);
    if (ratio < 0.3) print("QC_TILT=FAIL");
    else if (ratio < 0.6) print("QC_TILT=WARN");
    else print("QC_TILT=PASS");
    return ratio;
}
```

### 2.10 Z-Drift Detection (Time-Lapse)

```javascript
nFrames = nSlices();
if (nFrames >= 3) {
    scores = newArray(nFrames);
    for (f = 1; f <= nFrames; f++) {
        setSlice(f); run("Duplicate...", "title=_drift_temp"); run("32-bit");
        run("Convolve...", "text1=[0 1 0\n1 -4 1\n0 1 0\n] normalize");
        getStatistics(n, mean, min, max, stdDev); scores[f-1] = stdDev * stdDev; close();
    }
    Array.getStatistics(scores, sMin, sMax, sMean, sStd);
    cv = sStd / maxOf(sMean, 0.001);
    if (cv > 0.3) print("QC_ZDRIFT=FAIL (CV=" + d2s(cv*100,1) + "%)");
    else if (cv > 0.15) print("QC_ZDRIFT=WARN");
    else print("QC_ZDRIFT=PASS");
}
```

---

## 3. Saturation & Dynamic Range

### 3.1 Saturation Thresholds

| Saturation % | Verdict | Action |
|-------------|---------|--------|
| 0% | Ideal | Proceed |
| < 0.01% | Excellent | Proceed |
| 0.01-0.1% | Acceptable | Note in methods |
| 0.1-1.0% | Warning | Check if saturated pixels are in ROIs |
| > 1.0% | Reject | Re-acquire at lower exposure/gain |

For zero-value pixels: up to 30-50% is normal for sparse fluorescence.

### 3.2 Dynamic Range Assessment

```javascript
function dynamicRangeAssessment() {
    bd = bitDepth(); maxVal = pow(2, bd) - 1;
    getRawStatistics(nPixels, mean, min, max, std);
    getHistogram(values, counts, 256);
    satPct = (counts[255] / nPixels) * 100;
    underPct = (counts[0] / nPixels) * 100;
    rangeUsed = max - min;
    rangePct = (rangeUsed / maxOf(maxVal, 1)) * 100;
    effectiveBits = (rangeUsed > 0) ? log(rangeUsed) / log(2) : 0;
    print("QC_BIT_DEPTH=" + bd + " QC_RANGE=" + min + "-" + max);
    print("QC_SATURATED_PCT=" + d2s(satPct, 4));
    print("QC_EFFECTIVE_BITS=" + d2s(effectiveBits, 1));
    if (satPct > 1.0) print("QC_SATURATION=FAIL");
    else if (satPct > 0.1) print("QC_SATURATION=WARN");
    else print("QC_SATURATION=PASS");
}
```

### 3.3 12-Bit in 16-Bit Container Detection

```javascript
function detectActualBitDepth() {
    if (bitDepth() != 16) return bitDepth();
    getStatistics(nPix, mean, min, max);
    if (max < 256) return 8;
    else if (max < 4096) return 12;
    else if (max < 16384) return 14;
    else return 16;
}
```

### 3.4 Per-Channel Saturation

```javascript
function checkChannelSaturation() {
    Stack.getDimensions(w, h, channels, slices, frames);
    for (c = 1; c <= channels; c++) {
        Stack.setChannel(c);
        getRawStatistics(nPixels, mean, min, max, std);
        getHistogram(values, counts, 256);
        satPct = (counts[255] / nPixels) * 100;
        status = "PASS";
        if (satPct > 1) status = "FAIL";
        else if (satPct > 0.1) status = "WARN";
        print("QC_CH" + c + "=" + status + " (" + d2s(satPct,2) + "%)");
    }
}
```

### 3.5 Weber Contrast

```javascript
function weberContrast() {
    run("Duplicate...", "title=_wc_temp");
    setAutoThreshold("Otsu dark"); run("Create Selection");
    getRawStatistics(nFg, meanFg); run("Make Inverse");
    getRawStatistics(nBg, meanBg); run("Select None"); close("_wc_temp");
    if (meanBg == 0) meanBg = 1;
    contrast = (meanFg - meanBg) / meanBg;
    if (contrast < 0.2) print("QC_CONTRAST=FAIL");
    else if (contrast < 0.5) print("QC_CONTRAST=WARN");
    else print("QC_CONTRAST=PASS (" + d2s(contrast,2) + ")");
    return contrast;
}
```

### 3.6 Visual Saturation Check

```javascript
run("HiLo");  // Blue=0 (underexposed), Red=max (saturated). Display only.
```

---

## 4. Signal-to-Noise Ratio

### 4.1 SNR Interpretation (Rose Criterion)

| SNR | Interpretation | Measurement CV |
|-----|---------------|---------------|
| > 20 | Excellent | ~5% |
| 10-20 | Good | 5-10% |
| 5-10 | Acceptable (Rose criterion met) | 10-20% |
| 3-5 | Marginal | 20-33% |
| < 3 | Reject for quantitative analysis | > 33% |

### 4.2 Auto-Threshold SNR

```javascript
function estimateSNR_auto() {
    run("Duplicate...", "title=_snr_temp"); run("32-bit");
    run("Duplicate...", "title=_snr_mask");
    setAutoThreshold("Otsu dark"); run("Convert to Mask");
    selectImage("_snr_temp"); run("Duplicate...", "title=_snr_sig");
    selectImage("_snr_mask"); run("Create Selection");
    selectImage("_snr_sig"); run("Restore Selection");
    getRawStatistics(nSig, meanSig);
    run("Make Inverse"); getRawStatistics(nBg, meanBg, minBg, maxBg, stdBg);
    if (stdBg == 0) stdBg = 1;
    snr = (meanSig - meanBg) / stdBg;
    close("_snr_sig"); close("_snr_mask"); close("_snr_temp");
    return snr;
}
```

### 4.3 Noise Estimation (Laplacian, No ROI Needed)

```javascript
function estimateNoise() {
    run("Duplicate...", "title=_noise_temp"); run("32-bit");
    run("Convolve...", "text1=[1 -2 1\n-2 4 -2\n1 -2 1\n] normalize");
    getRawStatistics(nPixels, mean, min, max, std);
    sigma_noise = std * sqrt(3.14159 / 2) / 6;
    close("_noise_temp"); return sigma_noise;
}
```

### 4.4 Homogeneous Tile Method

```javascript
function noiseFromHomogeneousTile() {
    w = getWidth(); h = getHeight(); tileSize = 32;
    bestStd = 1e30; bestMean = 0;
    for (ty = 0; ty < h - tileSize; ty += tileSize) {
        for (tx = 0; tx < w - tileSize; tx += tileSize) {
            makeRectangle(tx, ty, tileSize, tileSize);
            getRawStatistics(n, m, mn, mx, s);
            if (s < bestStd && m > 5) { bestStd = s; bestMean = m; }
        }
    }
    run("Select None"); return bestStd;
}
```

### 4.5 Two-Image Method

If two sequential acquisitions of the same field are available:
```
sigma_noise = sqrt(Var(I1 - I2) / 2)
SNR = mean(I) / sigma_noise
```

### 4.6 Detector Noise Characterisation

```javascript
function characterizeNoise() {
    getStatistics(nPix, mean, min, max, stdDev);
    w = getWidth(); h = getHeight(); border = 20;
    makeRectangle(0, 0, border, h); getStatistics(nB, bgMean1, bgMin, bgMax, bgStd1);
    makeRectangle(w-border, 0, border, h); getStatistics(nB2, bgMean2, bgMin2, bgMax2, bgStd2);
    run("Select None");
    bgNoise = (bgStd1 + bgStd2) / 2; bgMean = (bgMean1 + bgMean2) / 2;
    expectedPoisson = sqrt(maxOf(bgMean, 0));
    poissonRatio = bgNoise / maxOf(expectedPoisson, 0.001);
    print("QC_NOISE_TYPE: ratio=" + d2s(poissonRatio, 2) +
          " (1.0=Poisson, >1.5=read-noise dominated)");
}
```

---

## 5. Background & Illumination Uniformity

### 5.1 Tile-Based CV

```javascript
function backgroundUniformity() {
    w = getWidth(); h = getHeight();
    tileW = floor(w/4); tileH = floor(h/4);
    means = newArray(16); idx = 0;
    for (ty = 0; ty < 4; ty++) {
        for (tx = 0; tx < 4; tx++) {
            makeRectangle(tx*tileW, ty*tileH, tileW, tileH);
            getRawStatistics(n, m); means[idx] = m; idx++;
        }
    }
    run("Select None");
    Array.getStatistics(means, minM, maxM, grandMean, stdTiles);
    cv = (stdTiles / grandMean) * 100;
    uniformity = (minM / maxM) * 100;
    print("QC_TILE_CV=" + d2s(cv,1) + "% QC_UNIFORMITY=" + d2s(uniformity,1) + "%");
    if (cv > 20) print("QC_BACKGROUND=FAIL");
    else if (cv > 10) print("QC_BACKGROUND=WARN");
    else print("QC_BACKGROUND=PASS");
    return cv;
}
```

### 5.2 Shading Assessment

```javascript
function shadingAssessment() {
    id = getImageID();
    run("Duplicate...", "title=_shade_bg"); run("32-bit");
    run("Gaussian Blur...", "sigma=" + (getWidth()/4));
    getRawStatistics(n, bgMean, bgMin, bgMax, bgStd);
    correctionFactor = bgMax / bgMin;
    close("_shade_bg"); selectImage(id);
    if (correctionFactor > 1.5) print("QC_SHADING=FAIL (" + d2s(correctionFactor,2) + "x)");
    else if (correctionFactor > 1.2) print("QC_SHADING=WARN");
    else print("QC_SHADING=PASS");
    return correctionFactor;
}
```

### 5.3 Background Correction Methods

| Method | When to Use | Command | Preserves Intensity? |
|--------|------------|---------|---------------------|
| Flat-field division | Gold standard, reference images available | `imageCalculator("Divide create 32-bit", ...)` | Yes |
| BaSiC plugin | Retrospective, tile scans, no references | `run("BaSiC");` | Yes |
| Rolling ball | General purpose | `run("Subtract Background...", "rolling=50");` | No (subtractive) |
| Morphological opening | Sparse data, sharp transitions | `run("Minimum...", "radius=50"); run("Maximum...", "radius=50");` | No |
| Gaussian blur subtract | Fast approximation | Blur + subtract from original | No |

**Rolling ball radius:** `radius > 2 * largest_object_diameter`

### 5.4 Flat-Field Correction

```javascript
// Corrected = (Raw - Dark) / (Flat - Dark) * mean(Flat - Dark)
open("/path/to/raw.tif"); rename("raw");
open("/path/to/flat.tif"); rename("flat");
open("/path/to/dark.tif"); rename("dark");
imageCalculator("Subtract create 32-bit", "raw", "dark"); rename("raw_sub");
imageCalculator("Subtract create 32-bit", "flat", "dark"); rename("flat_sub");
selectWindow("flat_sub"); getStatistics(nPixels, flatMean);
imageCalculator("Divide create 32-bit", "raw_sub", "flat_sub"); rename("corrected");
run("Multiply...", "value=" + flatMean);
// Clean up originals
```

### 5.5 Retrospective Flat-Field (No References)

```javascript
// Method 1: BaSiC (best for tile scans)
run("BaSiC", "processing_stack=your_stack flat-field_estimation=[Estimate] dark-field_estimation=[Estimate]");

// Method 2: Large Gaussian pseudo-flat-field (sparse features)
id = getImageID();
run("Duplicate...", "title=_pseudo_flat"); run("32-bit");
run("Gaussian Blur...", "sigma=200");
selectImage(id); run("32-bit");
imageCalculator("Divide create 32-bit", getTitle(), "_pseudo_flat");
rename("corrected"); close("_pseudo_flat");

// Method 3: Rolling ball (before segmentation only, NOT for intensity)
run("Subtract Background...", "rolling=200 sliding");
```

---

## 6. Artifact Catalogue

### 6.1 Illumination & Optics Artifacts

| Artifact | Appearance | Detection | Fix |
|----------|-----------|-----------|-----|
| Vignetting | Centre brighter than edges | Tile CV > 10% or corners < 85% of centre | Flat-field correction (Sec 5.4) |
| Hot pixels | Isolated bright pixels, same position every image | Median comparison, >5 sigma from local median | `run("Remove Outliers...", "radius=2 threshold=50 which=Bright");` |
| Dead pixels | Always-zero pixels in bright regions | Same as hot pixels | `run("Remove Outliers...", "radius=2 threshold=50 which=Dark");` |
| Chromatic aberration | Channels misregistered, colour fringes | Split channels, compare centroids | `run("Translate...", "x=1.5 y=-0.8 interpolation=Bilinear");` per channel |
| Lamp fluctuations | Random intensity jumps between frames | Frame mean CV > 5%, max jump > 10% | Normalise each frame to its mean |
| Spherical aberration | Deeper slices progressively blurrier | Focus quality slope < -0.05/slice | Match RI: immersion to mounting medium |

**Defective pixel detection:**
```javascript
function detectDefectivePixels(threshold) {
    if (threshold == 0) threshold = 5;
    w = getWidth(); h = getHeight(); id = getImageID(); title = getTitle();
    run("Duplicate...", "title=_median_ref"); run("Median...", "radius=2");
    imageCalculator("Subtract create 32-bit", title, "_median_ref"); rename("_diff");
    getStatistics(n, diffMean, diffMin, diffMax, diffStd);
    hotThresh = diffMean + threshold * diffStd; nHot = 0;
    selectWindow("_diff");
    for (y = 1; y < h-1; y++) for (x = 1; x < w-1; x++) if (getPixel(x,y) > hotThresh) nHot++;
    close("_diff"); close("_median_ref"); selectImage(id);
    hotPct = nHot / (w*h) * 100;
    if (hotPct > 0.1) print("QC_HOT_PIXELS=FAIL (" + nHot + ")");
    else if (nHot > 10) print("QC_HOT_PIXELS=WARN (" + nHot + ")");
    else print("QC_HOT_PIXELS=PASS");
}
```

### 6.2 Acquisition Artifacts

| Artifact | Appearance | Detection | Fix |
|----------|-----------|-----------|-----|
| Saturation | Uniform white in bright regions, spike at max | satPct > 0.1% | Cannot fix. Re-acquire lower exposure. |
| Underexposure | Dark/grainy, histogram bunched at low end | DR used < 10%, SNR < 5 | Average frames, denoise, or re-acquire |
| Bleed-through | Ghost of Ch1 signal in Ch2 | Pearson r > 0.7 between channels | Linear unmixing: subtract bt_coeff * source_channel |
| Stitching seams | Intensity steps at tile borders | Strip mean diff > 5% at regular intervals | Re-stitch with linear blending, or flat-field first |
| Line noise (confocal) | Horizontal streaks | Row CV >> Col CV (ratio > 2) | `run("Bandpass Filter...", "filter_large=40 filter_small=3 suppress=Horizontal tolerance=5");` |
| Bidirectional offset | Zebra stripes, even/odd row difference | Even/odd row mean diff > 3% | Adjust phase at acquisition; median filter |
| JPEG compression | 8x8 block grid, ringing at edges | Block boundary ratio > 1.5 | Cannot fix. Use original TIFF. |

**Bleed-through correction:**
```javascript
// Requires coefficient from single-stained control
bt_coeff = 0.15;  // measured
run("Split Channels");
selectWindow("C1-image.tif");
run("Duplicate...", "title=bleed_correction");
run("Multiply...", "value=" + bt_coeff);
imageCalculator("Subtract create", "C2-image.tif", "bleed_correction");
rename("C2_corrected"); close("bleed_correction");
```

**Line noise detection:**
```javascript
function detectLineNoise() {
    w = getWidth(); h = getHeight();
    rowMeans = newArray(h);
    for (y = 0; y < h; y++) { sum = 0; for (x = 0; x < w; x++) sum += getPixel(x,y); rowMeans[y] = sum/w; }
    Array.getStatistics(rowMeans, rMin, rMax, rMean, rStd); rowCV = rStd / maxOf(rMean, 0.001);
    colMeans = newArray(w);
    for (x = 0; x < w; x++) { sum = 0; for (y = 0; y < h; y++) sum += getPixel(x,y); colMeans[x] = sum/h; }
    Array.getStatistics(colMeans, cMin, cMax, cMean, cStd); colCV = cStd / maxOf(cMean, 0.001);
    ratio = rowCV / maxOf(colCV, 0.001);
    if (ratio > 2) print("QC_LINE_NOISE=WARN (ratio=" + d2s(ratio,2) + ")");
    // Bidirectional check
    evenMean = 0; oddMean = 0; evenN = 0; oddN = 0;
    for (y = 0; y < h; y++) {
        if (y%2==0) { evenMean += rowMeans[y]; evenN++; }
        else { oddMean += rowMeans[y]; oddN++; }
    }
    biDirDiff = abs(evenMean/evenN - oddMean/oddN) / maxOf((evenMean/evenN + oddMean/oddN)/2, 0.001) * 100;
    if (biDirDiff > 3) print("QC_BIDIR=WARN (" + d2s(biDirDiff,1) + "%)");
}
```

**JPEG artifact detection:**
```javascript
function detectJPEGArtifacts() {
    w = getWidth(); h = getHeight();
    onBound = 0; onN = 0; offBound = 0; offN = 0;
    for (y = 0; y < minOf(h,500); y++) {
        for (x = 1; x < minOf(w,500); x++) {
            diff = abs(getPixel(x,y) - getPixel(x-1,y));
            if (x%8==0) { onBound += diff; onN++; } else { offBound += diff; offN++; }
        }
    }
    if (onN > 0) onBound /= onN; if (offN > 0) offBound /= offN;
    ratio = onBound / maxOf(offBound, 0.001);
    if (ratio > 1.5) print("QC_JPEG=FAIL (ratio=" + d2s(ratio,2) + ")");
    else if (ratio > 1.2) print("QC_JPEG=WARN");
    else print("QC_JPEG=PASS");
}
```

### 6.3 Staining & Sample Artifacts

| Artifact | Appearance | Detection | Fix |
|----------|-----------|-----------|-----|
| Autofluorescence | Diffuse signal in all channels, lipofuscin granules | AND-mask all channels > 2% common | Sudan Black, TrueBlack, far-red fluorophores |
| Non-specific staining | Uniform high background, low contrast | SBR < 1.5 | Wet-lab: improve blocking, titrate antibody |
| Photobleaching | Progressive dimming over time | Total loss > 5% | `run("Bleach Correction", "correction=[Exponential Fit]");` |
| Tissue folds | Dark creases with bright edges | Elongated edge features (circ < 0.2) | Cannot fix. Exclude from ROI. |
| Air bubbles | Circular dark areas with bright rings | Large round dark features (circ > 0.5) | Remount coverslip |
| Mounting crystals | Very bright geometric features | Pixels > mean + 10*std | Clean and remount |
| Uneven thickness | Non-radial intensity variation | Grid CV > 15% but not vignetting pattern | Normalise to housekeeping marker |
| Freeze artifacts | Swiss cheese holes | Numerous small round holes | Cannot fix. Re-freeze rapidly. |
| Paraffin chatter | Parallel lines across section | Visual inspection | Re-section with fresh blade |

**Autofluorescence detection:**
```javascript
getDimensions(w, h, channels, slices, frames);
if (channels >= 2) {
    title = getTitle(); run("Split Channels");
    for (c = 1; c <= channels; c++) {
        selectWindow("C" + c + "-" + title);
        run("Duplicate...", "title=_af_mask_c" + c);
        setAutoThreshold("Otsu dark"); run("Convert to Mask");
    }
    imageCalculator("AND create", "_af_mask_c1", "_af_mask_c2"); rename("_af_common");
    if (channels >= 3) {
        imageCalculator("AND create", "_af_common", "_af_mask_c3");
        selectWindow("_af_common"); close(); rename("_af_common");
    }
    getStatistics(n, mean); afPct = mean / 255 * 100;
    if (afPct > 2) print("QC_AUTOFLUORESCENCE=WARN (" + d2s(afPct,1) + "%)");
    close("_af_common");
    for (c = 1; c <= channels; c++) { selectWindow("_af_mask_c" + c); close(); }
    // Reconstruct composite
}
```

**Photobleaching detection + correction:**
```javascript
function measureBleaching() {
    nFrames = nSlices(); if (nFrames < 3) return 0;
    means = newArray(nFrames);
    for (f = 1; f <= nFrames; f++) { setSlice(f); getStatistics(n, mean); means[f-1] = mean; }
    totalLoss = (means[0] - means[nFrames-1]) / maxOf(means[0], 0.001) * 100;
    if (totalLoss > 50) print("QC_BLEACHING=FAIL (" + d2s(totalLoss,0) + "%)");
    else if (totalLoss > 20) print("QC_BLEACHING=WARN");
    else print("QC_BLEACHING=PASS");
    return totalLoss;
}
// Correction options:
// run("Bleach Correction", "correction=[Simple Ratio] background=0");      // uniform decay
// run("Bleach Correction", "correction=[Exponential Fit]");                 // for quantification
// run("Bleach Correction", "correction=[Histogram Matching]");              // for segmentation only
```

### 6.4 Digital Processing Artifacts

| Artifact | Appearance | Detection | Fix |
|----------|-----------|-----------|-----|
| Bit depth conversion | Histogram gaps (posterization) | > 40% empty bins in 8-bit | Use original 16-bit data |
| Contrast clipping | Spikes at 0 and 255 | Both ends > 1% | Cannot fix. Use `setMinAndMax()` instead. |
| Rotation padding | Zero-filled corners | >= 2 corners at 0 | Exclude padded regions |
| Over-sharpening | Bright halos, crunchy texture | Edge kurtosis > 10 | Use original data |
| Over-denoising | Waxy/plastic appearance | Loss of fine detail | Use original data |

**Bit depth issue detection:**
```javascript
function detectBitDepthIssues() {
    bd = bitDepth();
    if (bd == 8) {
        getHistogram(values, counts, 256); nEmpty = 0;
        for (i = 1; i < 255; i++) if (counts[i] == 0) nEmpty++;
        if (nEmpty / 254.0 * 100 > 40)
            print("QC_BIT_CONVERSION=WARN (histogram gaps, likely converted from higher bit depth)");
    }
    if (bd == 16) {
        getStatistics(nPix, mean, min, max);
        if (max < 256) print("QC_BIT_MISMATCH=WARN (8-bit data in 16-bit container)");
    }
    if (bd == 24) print("QC_BIT_DEPTH=WARN (RGB -- not suitable for quantification)");
}
```

**Contrast clipping detection:**
```javascript
function detectContrastClipping() {
    getHistogram(values, counts, 256); nPix = 0;
    for (i = 0; i < 256; i++) nPix += counts[i];
    lowClip = counts[0] / nPix * 100; highClip = counts[255] / nPix * 100;
    if (highClip > 1 && lowClip > 1) print("QC_CLIPPING=FAIL (both ends)");
    else if (highClip > 0.5 || lowClip > 5) print("QC_CLIPPING=WARN");
    else print("QC_CLIPPING=PASS");
    nGaps = 0;
    for (i = 2; i < 254; i++) if (counts[i]==0 && counts[i-1]>0 && counts[i+1]>0) nGaps++;
    if (nGaps > 20) print("QC_HISTOGRAM_COMB=WARN (" + nGaps + " gaps)");
}
```

---

## 7. Staining Quality

### 7.1 Bimodality Coefficient

Good staining produces a bimodal histogram. BC > 0.555 suggests bimodality.

```javascript
function bimodalityCheck() {
    nBins = 256; getHistogram(values, counts, nBins);
    getRawStatistics(nPixels, mean, min, max, std);
    sumCube = 0; sumQuad = 0;
    for (i = 0; i < nBins; i++) {
        if (counts[i] > 0) {
            dev = (values[i] - mean) / std;
            sumCube += counts[i] * dev * dev * dev;
            sumQuad += counts[i] * dev * dev * dev * dev;
        }
    }
    skewness = sumCube / nPixels;
    kurtosis = (sumQuad / nPixels) - 3;
    bc = (skewness * skewness + 1) / (kurtosis + 3);
    if (bc > 0.555) print("QC_STAINING=GOOD (BC=" + d2s(bc,3) + ")");
    else if (bc > 0.4) print("QC_STAINING=MARGINAL (BC=" + d2s(bc,3) + ")");
    else print("QC_STAINING=WARN (BC=" + d2s(bc,3) + ", unimodal)");
    return bc;
}
```

### 7.2 Signal-to-Background Ratio

```javascript
function measureSBR() {
    run("Duplicate...", "title=_sbr_temp");
    setAutoThreshold("Otsu dark"); run("Create Selection");
    getRawStatistics(nSig, sigMean); run("Make Inverse");
    getRawStatistics(nBg, bgMean); run("Select None"); close("_sbr_temp");
    sbr = sigMean / maxOf(bgMean, 0.001);
    if (sbr < 1.5) print("QC_SBR=FAIL (" + d2s(sbr,2) + ")");
    else if (sbr < 3) print("QC_SBR=WARN");
    else print("QC_SBR=PASS (" + d2s(sbr,2) + ")");
    return sbr;
}
```

### 7.3 Staining Quality Criteria

| Metric | Good | Marginal | Poor |
|--------|------|----------|------|
| Bimodality Coefficient | > 0.555 | 0.4-0.555 | < 0.4 |
| SNR (auto-threshold) | > 10 | 5-10 | < 5 |
| Signal Area Fraction | 5-60% | 1-5% or 60-80% | < 1% or > 80% |
| Histogram EMD (vs reference) | < 5 | 5-15 | > 15 |

### 7.4 Batch Staining Consistency (EMD)

```javascript
function histogramEMD(counts1, counts2, nBins) {
    sum1 = 0; sum2 = 0;
    for (i = 0; i < nBins; i++) { sum1 += counts1[i]; sum2 += counts2[i]; }
    emd = 0; cum1 = 0; cum2 = 0;
    for (i = 0; i < nBins; i++) {
        cum1 += counts1[i] / sum1; cum2 += counts2[i] / sum2;
        emd += abs(cum1 - cum2);
    }
    return emd;  // <5 consistent, 5-15 moderate, >15 batch effect
}
```

---

## 8. Decision Trees

### 8.1 Pre-Analysis QC Flowchart

```
Image arrives
  |
  v
[1] FOCUS: LapVar (8-bit norm)
    < 20 -> FAIL    20-100 -> WARN    > 100 -> Continue
  |
[2] SATURATION: % at max
    > 1% -> FAIL    0.1-1% -> WARN    < 0.1% -> Continue
  |
[3] SNR: (sig-bg)/std_bg
    < 3 -> FAIL     3-5 -> WARN       > 5 -> Continue
  |
[4] BACKGROUND: tile CV
    > 20% -> FAIL   10-20% -> WARN    < 10% -> Continue
  |
[5] DYNAMIC RANGE: effective bits
    < 4 -> WARN     >= 4 -> Continue
  |
[6] STAINING: bimodality
    < 0.4 -> WARN   >= 0.4 -> Continue
  |
[7] FORMAT: JPEG?
    Yes -> FAIL      No -> PASS
```

### 8.2 Which Correction to Apply?

```
Problem -> Correction
Uneven illumination -> Flat-field (Sec 5.4) or BaSiC / Gaussian pseudo-flat
Hot/dead pixels -> Remove Outliers (radius=2, threshold=50)
High noise -> Median (salt-pepper), Gaussian sigma=0.5-1 (Gaussian), or NLM
Photobleaching -> Bleach Correction: Exponential Fit (quantification) / Histogram Matching (segmentation)
Chromatic aberration -> Translate channel by measured offset, or descriptor-based registration
Bleed-through -> Linear unmixing with single-stained control coefficient
High background -> Rolling Ball (radius = 2-5x largest feature)
Over/under exposure -> CANNOT FIX. Re-acquire.
```

### 8.3 Order of Corrections

```
1. Dark frame subtraction
2. Hot/dead pixel correction
3. Flat-field correction
4. Bleach correction (if time-lapse)
5. Background subtraction
6. Denoising (if needed, minimal)
7. Deconvolution (if appropriate)
8. Channel registration (before colocalization)
9. Bleed-through correction
```

NEVER: Apply Enhance Contrast (normalize), change bit depth carelessly, save as JPEG.

---

## 9. Image Integrity Standards

### 9.1 Allowed vs Forbidden Manipulations

| Operation | Allowed? | Disclosure |
|-----------|----------|------------|
| Linear B/C adjustment (uniform to entire image + controls) | Yes | Recommended |
| Cropping | Yes | Recommended |
| Pseudo-colouring (LUT) | Yes | Required |
| Channel merging (with individual channels shown) | Yes | Required |
| Background subtraction (same params all conditions) | Depends | Required |
| Gaussian blur / denoising (justified, uniform) | Depends | Required |
| Deconvolution (proper PSF, documented) | Depends | Required |
| Non-linear (gamma, log) | Caution | Required (figure legend + methods) |
| Enhance Contrast (normalize) | No | Destroys data permanently |
| Selective enhancement | No | Forbidden by all journals |
| Clone stamp / healing | No | Creates/removes elements |
| Splicing without visible lines | No | Boundaries required |
| JPEG for quantitative data | No | Lossy compression corrupts data |

### 9.2 Journal-Specific Notes

- **Nature Portfolio:** All processing in Methods; non-linear adjustments in figure legends; raw data on editor request
- **JCB:** Pioneer in screening (since 2002); "Is the image an accurate representation of the original data?"
- **Cell Press:** AI-generated/altered images explicitly forbidden; scale bars on all micrographs
- **Science/AAAS:** Proofig AI screening; lines required between panels from different images
- **eLife:** Routine screening since ~2020; checks duplication, processing, splicing

### 9.3 Integrity Detection Macros

**Non-linear processing (histogram gaps):**
```javascript
getHistogram(values, counts, 256); gapCount = 0;
for (i = 1; i < 254; i++) if (counts[i]==0 && counts[i-1]>0 && counts[i+1]>0) gapCount++;
if (gapCount > 10) print("QC_INTEGRITY=WARN (" + gapCount + " gaps, possible non-linear processing)");
```

**Uniform processing verification (comparison set):**
```javascript
n = nImages;
for (i = 1; i <= n; i++) {
    selectImage(i); title = getTitle(); bd = bitDepth();
    getPixelSize(unit, pw, ph); getRawStatistics(nPix, mean, min, max, std);
    getMinAndMax(dispMin, dispMax);
    print("Image " + i + ": " + title + " " + getWidth() + "x" + getHeight() +
          " " + bd + "-bit " + d2s(pw,4) + " " + unit + "/px display=" + dispMin + "-" + dispMax);
}
print("CHECK: All display ranges should be identical for valid comparison");
```

---

## 10. Batch QC

### 10.1 Batch QC Macro

```javascript
dir = getDirectory("Choose image directory");
list = getFileList(dir);
print("=== BATCH QC REPORT ===");
run("Clear Results"); row = 0;
for (i = 0; i < list.length; i++) {
    if (!(endsWith(list[i], ".tif") || endsWith(list[i], ".nd2") ||
          endsWith(list[i], ".czi") || endsWith(list[i], ".lif"))) continue;
    open(dir + list[i]); title = getTitle();
    // Focus
    run("Duplicate...", "title=_bqc_lap"); run("32-bit");
    run("Convolve...", "text1=[0 1 0\n1 -4 1\n0 1 0\n] normalize");
    getRawStatistics(n, m, mn, mx, s); lapVar = s*s; close("_bqc_lap");
    // Saturation
    getHistogram(v, c, 256); getRawStatistics(nPixels, mean, imgMin, imgMax, std);
    satPct = (c[255] / nPixels) * 100;
    // SNR
    snrEst = mean / maxOf(std, 0.001);
    // Background CV
    w = getWidth(); h = getHeight(); tW = floor(w/4); tH = floor(h/4);
    tileMeans = newArray(16); idx = 0;
    for (ty = 0; ty < 4; ty++) for (tx = 0; tx < 4; tx++) {
        makeRectangle(tx*tW, ty*tH, tW, tH); getRawStatistics(tn, tm);
        tileMeans[idx] = tm; idx++;
    }
    run("Select None");
    Array.getStatistics(tileMeans, tmn, tmx, tMean, tStd);
    bgCV = (tStd / tMean) * 100;
    // Score (0-100)
    qualityScore = minOf(lapVar/5,25) + (25-minOf(satPct*25,25)) + minOf(snrEst*5,25) + (25-minOf(bgCV,25));
    verdict = "PASS";
    if (lapVar < 20 || satPct > 1 || snrEst < 0.5 || bgCV > 20) verdict = "FAIL";
    else if (lapVar < 100 || satPct > 0.1 || snrEst < 1 || bgCV > 10) verdict = "WARN";
    setResult("Image", row, title); setResult("LapVar", row, lapVar);
    setResult("Sat%", row, satPct); setResult("SNR_est", row, snrEst);
    setResult("BgCV%", row, bgCV); setResult("Score", row, qualityScore);
    setResult("Verdict", row, verdict); row++;
    close(title);
}
updateResults();
```

### 10.2 Outlier Detection (IQR Method)

```javascript
function flagOutliers(metricColumn) {
    n = nResults(); if (n < 4) return;
    vals = newArray(n);
    for (i = 0; i < n; i++) vals[i] = getResult(metricColumn, i);
    sorted = Array.copy(vals); Array.sort(sorted);
    q1 = sorted[floor(n*0.25)]; q3 = sorted[floor(n*0.75)]; iqr = q3-q1;
    for (i = 0; i < n; i++) {
        if (vals[i] < q1-1.5*iqr || vals[i] > q3+1.5*iqr)
            print("OUTLIER: " + getResultString("Image",i) + " " + metricColumn + "=" + d2s(vals[i],2));
    }
}
flagOutliers("LapVar"); flagOutliers("Sat%"); flagOutliers("BgCV%");
```

### 10.3 Quality Score

| Score | Rating |
|-------|--------|
| 80-100 | Excellent |
| 60-80 | Good |
| 40-60 | Acceptable |
| 20-40 | Poor |
| < 20 | Reject |

---

## 11. QC Metrics Quick Reference Card

| Metric | Formula | PASS | WARN | FAIL |
|--------|---------|------|------|------|
| Laplacian Var (8-bit norm) | Var(Laplacian(I)) | > 100 | 20-100 | < 20 |
| Saturation % | count(max) / N | < 0.1% | 0.1-1% | > 1% |
| SNR | (sig-bg)/std_bg | > 5 | 3-5 | < 3 |
| Background CV | std(tiles)/mean(tiles) | < 10% | 10-20% | > 20% |
| Illumination uniformity | corners/centre | > 85% | 60-85% | < 60% |
| Effective bits | log2(range) | > 6 | 4-6 | < 4 |
| Bimodality Coefficient | (skew^2+1)/(kurt+3) | > 0.555 | 0.4-0.555 | < 0.4 |
| Weber Contrast | (fg-bg)/bg | > 1.0 | 0.5-1.0 | < 0.5 |
| Signal-to-Background | sig_mean/bg_mean | > 5 | 1.5-5 | < 1.5 |
| Photobleaching | total loss % | < 5% | 5-50% | > 50% |
| JPEG block ratio | boundary/interior diff | < 1.2 | 1.2-1.5 | > 1.5 |
| Hot pixels | count >5 sigma | < 10 | 10-0.1% | > 0.1% |
| Bleed-through | coefficient | < 5% | 5-20% | > 20% |
| Dynamic range used | (max-min)/max_possible | > 25% | 5-25% | < 5% |

NOTE: All thresholds are guidelines. Calibrate for your specific instrument and sample.

---

## 12. Best Practices Checklist

### Pre-Acquisition
- [ ] Calibrate microscope with stage micrometer
- [ ] Warm up light source (15-30 min)
- [ ] Lock ALL settings (no auto-exposure, no auto-gain)
- [ ] Verify no saturation on brightest sample
- [ ] Prepare controls (no-primary, isotype, secondary-only)

### Acquisition
- [ ] Same objective, exposure, gain, binning, pinhole for all conditions
- [ ] Include negative controls
- [ ] Acquire flat-field reference + dark frame
- [ ] Save lossless (TIFF or native format) -- NEVER JPEG

### Post-Acquisition
- [ ] Back up raw data
- [ ] Run QC on every image (Sec 1.1)
- [ ] Apply corrections in order (Sec 8.3)
- [ ] Apply IDENTICAL parameters to all conditions
- [ ] Work on duplicates -- NEVER modify originals

### Analysis
- [ ] NEVER use Enhance Contrast (normalize) before measurement
- [ ] Use `setMinAndMax()` for display only
- [ ] Document every processing step with parameters
- [ ] Audit results for outliers and saturation in ROIs

### Reporting Template

**Acquisition:**
```
Images acquired using [manufacturer] [model] [modality] microscope with
[magnification]/[NA] [immersion] objective. [Fluorophore] excited at [wavelength] nm,
emission through [filter] onto [detector] at [exposure] exposure, [gain] gain.
[For confocal: pinhole [N] AU.] [For z-stacks: [N] slices at [step] um.]
All parameters constant across conditions.
```

**Processing:**
```
Processed using Fiji/ImageJ (v[X.Y.Z]). [Background subtracted using [method]
(radius=[N]).] Display adjustments applied identically across conditions
(range: [min]-[max]). No non-linear adjustments. Scale bars: [N] um.
```

---

## 13. QC Tools and Plugins

| Tool | Purpose | Key Metrics |
|------|---------|-------------|
| MetroloJ / MetroloJ_QC | Microscope performance | PSF FWHM, field uniformity, chromatic aberration |
| SIMcheck | SIM data quality | MCNR (>4 for reliable reconstruction) |
| NanoJ-SQUIRREL | Super-resolution QC | Error map, RSP, RSE |
| NoiSee | Standardised SNR | SNR from temporal variance of beads |
| Microscope Focus Quality | DL focus classification | Per-tile focus probability |
| Find Focused Slices | Best-focus in z-stack | Normalised variance |
| BaSiC | Retrospective flat-field | Estimated flat/dark fields |
| Bleach Correction | Fix photobleaching | Ratio, exponential, histogram matching |

**MetroloJ measurement frequency:**

| Metric | Frequency |
|--------|-----------|
| PSF / Resolution | Monthly |
| Field illumination | Twice yearly per objective/channel |
| Co-registration | Twice yearly |
| Illumination stability | Yearly |

---

## 14. Common Failures Quick Reference

| Problem | Detection | Fix |
|---------|-----------|-----|
| Out of focus | LapVar < 20 | Cannot fix. Re-acquire. |
| Saturated | satPct > 1% | Cannot fix. Re-acquire lower exposure. |
| Low SNR | SNR < 3 | Denoise (Gaussian sigma=1), average frames |
| Uneven background | bgCV > 20% | Rolling ball, BaSiC flat-field |
| Low contrast | effectiveBits < 4 | Background subtraction; check staining |
| Hot pixels | Isolated max-value | `run("Remove Outliers...", "radius=2 threshold=50 which=Bright");` |
| Photobleaching | Progressive dimming | Bleach Correction plugin |
| Bleed-through | Channel correlation > 0.7 | Linear unmixing with single-stained controls |
| JPEG artifacts | Block ratio > 1.5 | Cannot fix. Use original TIFF. |
| Tissue folds | Dark creases | Exclude from ROI. Cannot fix. |
| Bit depth loss | Histogram gaps > 40% | Use original 16-bit data |

---

## Sources

- [QUAREP-LiMi](https://quarep.org/) -- community checklists for microscope QC
- [Nature Methods: Community checklists for publishing images (2023)](https://www.nature.com/articles/s41592-023-01987-9)
- [Cromey: Avoiding Twisted Pixels (PMC4114110)](https://pmc.ncbi.nlm.nih.gov/articles/PMC4114110/)
- [MetroloJ_QC (PMC9526251)](https://pmc.ncbi.nlm.nih.gov/articles/PMC9526251/)
- [Focus Measure Operators (PMC12115465)](https://pmc.ncbi.nlm.nih.gov/articles/PMC12115465/)
- [NoiSee SNR measurement](https://www.nature.com/articles/s41598-018-37781-3)
- [NanoJ-SQUIRREL](https://pmc.ncbi.nlm.nih.gov/articles/PMC5884429/)
- [SIMcheck](https://www.nature.com/articles/srep15915)
- [BaSiC flat-field](https://www.nature.com/articles/ncomms14836)
- [Bleach Correction](https://doi.org/10.12688/f1000research.27171.1)
- [Nature Portfolio image integrity](https://www.nature.com/nature-portfolio/editorial-policies/image-integrity)
- [JCB figure guidelines](https://rupress.org/jcb/pages/fig-vid-guidelines)
- [ImageJ Wiki: Detect Information Loss](https://imagej.net/imaging/detect-information-loss)
- [Pete Bankhead: Images & Pixels](https://petebankhead.gitbooks.io/imagej-intro/)
- [Micro-Manager flat-field](https://micro-manager.org/Flat-Field_Correction)
- [Bioimaging Guide: Intensity](https://www.bioimagingguide.org/03_Image_analysis/Intensity.html)
