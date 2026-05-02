# Self-Improving ImageJ Agent — Technical Reference

Agent-oriented reference for the self-improving ImageJ/Fiji agent
architecture: plugin ecosystem inventory, scripting language choice,
recipe database schema, verification and error-recovery tables,
benchmark images, segmentation method selection, CPU vs GPU filter
timings, and the self-evaluation pipeline.

Sources: `imagej.net/ij/docs/`, `imagej.net/plugins/`, Fiji wiki
(`imagej.net/software/fiji`), CLIJ2 reference
(`clij.github.io/clij2-docs/`). Use `python probe_plugin.py "Plugin..."`
to discover any installed plugin's parameters at runtime, and
`python scan_plugins.py` to enumerate installed commands.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript.
`python scan_plugins.py` — dump installed commands + update sites.
`python probe_plugin.py "Plugin Name"` — discover plugin parameters.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "How do I enumerate installed plugins / commands?" | §2 |
| "Which plugins are macro-recordable / headless-capable?" | §2 |
| "What plugin API patterns exist (GenericDialog, MacroExtension, call, eval)?" | §2 |
| "Should I use IJ Macro, Groovy, or Jython?" | §3 |
| "How do I open .nd2 / .lif / .czi with Bio-Formats?" | §3 |
| "What does a recipe YAML look like?" | §4 |
| "How do I verify a given analysis type?" | §4 |
| "How do I tune plugin parameters automatically?" | §4 |
| "What auto-fix applies to common macro errors?" | §4 |
| "Which built-in benchmark images should I test on?" | §4 |
| "Which segmentation method fits my scenario?" | §4 |
| "How much faster is CLIJ2 vs CPU filtering?" | §4 |
| "How do I self-evaluate a pipeline result?" | §5 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '`<term>`' self-improving-agent-reference.md` to jump.

### A

`ABBA` §2 · `AnalyzeSkeleton` §2 · `Analyze Particles` §4 · `Auto-Fix table` §4

### B

`Bat Cochlea Volume` §4 · `Batch Mode` §3 · `Bayesian optimization` §4 · `Benchmark Images` §4 · `Bio-Formats` §2, §3 · `Blobs (25K)` §4

### C

`call()` §2 · `Cellpose` §2, §4 · `circularity distribution` §4 · `CLIJ2` §2, §4 · `Coloc 2` §2 · `commands.md` §2 · `Convert to Mask` §3, §4 · `cross-validation` §5 · `.czi (Zeiss)` §3

### D

`Dialog appears` §4 · `Dialog handling` §4

### E

`Embryos` §4 · `Error Recovery` §4 · `eval()` §2 · `explore_thresholds` §4 · `Ext.CLIJ2_gaussianBlur3D` §2

### F

`failure_indicators` §4 · `Fluorescent Cells` §4 · `foreground fraction` §4

### G

`Gaussian Blur` §4 · `GenericDialog` §2 · `Grid search` §4 · `GPU filter speedup` §4

### H

`Headless` §2 · `histogram check` §5 · `hyperstack` §3

### I

`IJ Macro` §3 · `intensity verification` §4

### J

`Jython` §3

### K

`Key Installed Plugins` §2

### L

`Labkit` §2 · `.lif (Leica)` §3

### M

`MacroExtension` §2 · `macro-recordable` §2 · `Macro execution timed out` §4 · `measurement sanity` §5 · `Median` §4 · `MorphoLibJ` §2

### N

`.nd2 (Nikon)` §3 · `No image open` §4 · `noise detected` §4 · `Not a binary image` §4

### O

`Optuna` §4 · `Otsu` §4 · `object counting` §4

### P

`Parameter Optimization` §4 · `Plugin API Patterns` §2 · `Plugin Ecosystem` §2 · `probe_plugin` §2 · `probe_plugin.py` §2

### R

`recipe YAML` §4 · `Recipe Database` §4 · `Rolling Ball BG` §4 · `run("Plugin", ...)` §2

### S

`saturation check` §5 · `scan_plugins.py` §2 · `scikit-optimize` §4 · `segmentation` §4, §5 · `Segmentation Method Selection` §4 · `Selection required` §4 · `Self-Evaluation Pipeline` §5 · `Self-Improvement Architecture` §4 · `series_N` §3 · `StarDist 2D/3D` §2, §4 · `Stitching` §2 · `Subtract Background` §4

### T

`T1 Head (2.4M)` §4 · `threshold_method` §4 · `thresholding fraction` §4 · `TrackMate` §2 · `Triangle` §4

### U

`Unknown command` §4 · `update sites` §2 · `use_virtual_stack` §3

### V

`validation` §4 · `verification by analysis type` §4 · `virtual stack` §3 · `visual inspection` §5

### W

`Watershed` §4 · `Weka Segmentation` §2

### Y

`Yen` §4

### Z

`3D ImageJ Suite` §2 · `3D Objects Counter` §4 · `3D stack` §4

---

## §2 Plugin Ecosystem

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

## §3 Scripting Language Comparison

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

## §4 Self-Improvement Architecture

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

## §5 Self-Evaluation Pipeline

1. **Segmentation**: Count objects (1 = under-segmented, >10000 = noise)
2. **Measurement sanity**: Cell area typically 100-5000px, circularity 0.3-0.9
3. **Histogram**: Check saturation, bimodality, noise floor
4. **Visual**: Capture and inspect after each step
5. **Cross-validation**: Same analysis with different parameters
