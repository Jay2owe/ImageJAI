# Self-Improving ImageJ Agent — Technical Reference

## 1. Plugin Ecosystem

~330 update sites (22 enabled), 1966 installed commands. Discover with:
```bash
python scan_plugins.py          # → .tmp/commands.md + .tmp/plugins_summary.txt
grep -i "keyword" .tmp/commands.md
```

### Key Installed Plugins

| Plugin | Macro-Recordable | Headless |
|--------|-----------------|----------|
| StarDist 2D/3D | Yes | Yes |
| Cellpose | Yes | Partial (needs Python) |
| TrackMate | Limited (wizard) | No |
| Weka Segmentation | Yes (scriptable) | Yes |
| CLIJ2 (504 ops) | Yes (Ext.) | Yes |
| Bio-Formats | Yes | Yes |
| 3D ImageJ Suite (64 cmds) | Yes | Mostly |
| Coloc 2, AnalyzeSkeleton, MorphoLibJ | Yes | Yes |
| ABBA | Limited | No |
| Labkit | No (interactive) | No |

### Plugin API Patterns

| Pattern | Example | When |
|---------|---------|------|
| **GenericDialog** | `run("Plugin", "param1=val1 param2=val2")` | Most plugins |
| **MacroExtension** | `Ext.CLIJ2_gaussianBlur3D(in, out, 2, 2, 2)` | CLIJ2, Bio-Formats |
| **call()** | `call("ClassName.staticMethod", "arg")` | Direct Java (String→String only) |
| **eval()** | `eval("script", "IJ.run('Blobs')");` | Escape hatch to full Java API |

Discovery: `python probe_plugin.py "Plugin Name"` (opens dialog, reads all fields, cancels).

---

## 2. Scripting Language Comparison

| Feature | IJ Macro | Groovy | Jython |
|---------|----------|--------|--------|
| Java API access | Limited | Full | Full |
| Speed | Slowest | Fastest | Medium |
| Script params | No | Yes | Yes |
| Error handling | Minimal | try/catch | try/except |

**Agent strategy**: IJ Macro as primary (largest example corpus, simplest execution via TCP). Use `eval("script", "...")` for operations macro language cannot reach. Groovy/Jython for complex standalone scripts.

### Bio-Formats

```javascript
run("Bio-Formats Importer", "open=/path/to/file.nd2 autoscale color_mode=Default view=Hyperstack");
run("Bio-Formats Importer", "open=/path/to/file.lif series_1");  // specific series
run("Bio-Formats Importer", "open=/path/to/file.nd2 use_virtual_stack");  // memory-efficient
```

| Format | Gotchas |
|--------|---------|
| .nd2 (Nikon) | Multi-position: check series count |
| .lif (Leica) | Often multi-series, specify `series_N` |
| .czi (Zeiss) | Scenes = series, large tiles may need crop |

---

## 3. Self-Improvement Architecture

### Recipe Database (YAML)

```yaml
name: "Fluorescence Nuclei Count"
parameters:
  blur_sigma: { default: 1.5, range: [0.5, 5.0] }
  threshold_method: { default: "Otsu", options: ["Otsu", "Triangle", "Li"] }
  min_particle_size: { default: 50, range: [10, 500] }
steps:
  - macro: 'run("Gaussian Blur...", "sigma={blur_sigma}");'
  - macro: 'setAutoThreshold("{threshold_method} dark"); run("Convert to Mask");'
  - macro: 'run("Watershed");'
  - macro: 'run("Analyze Particles...", "size={min_particle_size}-Infinity ...");'
validation:
  expected_count_range: [10, 500]
  failure_indicators:
    - count < 3: "Threshold too aggressive or min_size too large"
    - count > 1000: "Noise detected, increase blur or min_size"
```

### Verification by Analysis Type

| Analysis | Verification | Typical Range |
|----------|-------------|---------------|
| Object counting | Count in range | 10-500 per FOV |
| Segmentation | Circularity distribution | Mean > 0.5 for round objects |
| Intensity | Non-zero, non-saturated | Within bit depth |
| Thresholding | Foreground fraction | 5-60% |

### Parameter Optimization

1. **Grid search** (simple, use `explore_thresholds` pattern)
2. **Bayesian optimization** (fewest evaluations, for >3 parameters — scikit-optimize, Optuna)

### Error Recovery

| Error | Auto-Fix |
|-------|----------|
| "No image open" | Check state; open image |
| "Not a binary image" | Auto-threshold + Convert to Mask |
| "Selection required" | `run("Select All")` or detect objects |
| "Macro execution timed out" | Check for dialogs; increase timeout |
| "Unknown command" | Search commands.md; suggest update site |
| Dialog appears | Read dialog, extract params, close, re-run |

### Benchmark Images

```javascript
run("Blobs (25K)");         // ~65 objects
run("Fluorescent Cells");   // 3-channel
run("T1 Head (2.4M)");     // 3D stack (129 slices)
run("Bat Cochlea Volume");  // 3D (141 slices)
run("Embryos");             // Touching objects
```

### Segmentation Method Selection

| Scenario | Method |
|----------|--------|
| High-contrast, well-separated | Otsu/Triangle + Watershed |
| Touching round nuclei | StarDist 2D |
| Irregular cells | Cellpose |
| Noisy, known structure | Weka |
| Large dataset (>1000 images) | StarDist or CLIJ2+Threshold |
| 3D volumes | StarDist 3D or 3D Objects Counter |

### Comparison: Filtering (CPU vs GPU)

| Filter | CPU (256x256) | CLIJ2 GPU |
|--------|--------------|-----------|
| Gaussian Blur | ~5ms | ~0.1ms |
| Median | ~10ms | ~0.2ms |
| Rolling Ball BG | ~20ms | ~0.5ms |
| 3D Gaussian | ~200ms | ~2ms |

CLIJ2: 10-30x speedup per operation, 15-33x for full pipelines kept on GPU.

---

## 4. Self-Evaluation Pipeline

1. **Segmentation**: Count objects (1 = under-segmented, >10000 = noise)
2. **Measurement sanity**: Cell area typically 100-5000px, circularity 0.3-0.9
3. **Histogram**: Check saturation, bimodality, noise floor
4. **Visual**: Capture and inspect after each step
5. **Cross-validation**: Same analysis with different parameters
