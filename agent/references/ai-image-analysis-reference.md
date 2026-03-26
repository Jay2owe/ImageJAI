# AI Image Analysis Reference

Sources: [StarDist](https://imagej.net/plugins/stardist), [Cellpose](https://cellpose.readthedocs.io),
[TWS](https://imagej.net/plugins/tws/), [Labkit](https://imagej.net/plugins/labkit/),
[CSBDeep](https://csbdeep.bioimagecomputing.com), [DeepImageJ](https://deepimagej.github.io)

---

## Decision Tree

```
What are you segmenting?
├─ Round nuclei (DAPI, Hoechst, H&E)?
│   ├─ Well-separated → Classical threshold + watershed
│   ├─ Dense/touching → StarDist 2D (INSTALLED)
│   └─ 3D z-stack → StarDist 3D (Python) or 3D Objects Counter
├─ Irregular cells (cytoplasm, phase contrast)?
│   ├─ Via TrackMate → Cellpose detector (INSTALLED via TrackMate)
│   └─ Via Python → Cellpose + CrossToolRunner
├─ Custom features (need training)?
│   ├─ Pixel classification → WEKA (INSTALLED)
│   └─ Interactive ML → Labkit (INSTALLED)
├─ Denoising?
│   ├─ Supervised (paired data) → CARE/CSBDeep (INSTALLED)
│   └─ Classical → Gaussian/Median
└─ Tracking? → TrackMate + StarDist/Cellpose (INSTALLED)
```

---

## StarDist 2D — INSTALLED

Detects nuclei via star-convex polygon prediction. Instance segmentation (unique label per nucleus).

### Models

| Model | Use Case |
|-------|----------|
| `Versatile (fluorescent nuclei)` | DAPI, Hoechst, any fluorescent nuclear stain |
| `Versatile (H&E nuclei)` | H&E histology sections |
| `DSB 2018 (from StarDist 2D paper)` | Fluorescent nuclei (original) |

### Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `probThresh` | 0.5 | Detection confidence (lower = more detections) |
| `nmsThresh` | 0.4 | Overlap tolerance (lower = less overlap) |
| `normalizeInput` | true | Percentile normalization |
| `percentileBottom` / `Top` | 1.0 / 99.8 | Normalization range |
| `nTiles` | 1 | Tile for memory (increase to 4/8/16 for large images) |
| `excludeBoundary` | 2 | Exclude objects within N pixels of edge |
| `outputType` | Both | Label Image, ROI Manager, or Both |

### Macro Syntax

```javascript
run("Command From Macro", "command=[de.csbdresden.stardist.StarDist2D], args=['input':'IMAGE_TITLE', 'modelChoice':'Versatile (fluorescent nuclei)', 'normalizeInput':'true', 'percentileBottom':'1.0', 'percentileTop':'99.8', 'probThresh':'0.5', 'nmsThresh':'0.4', 'outputType':'Both', 'nTiles':'1', 'excludeBoundary':'2', 'roiPosition':'Automatic', 'verbose':'false', 'showCsbdeepProgress':'false'], process=[false]");
```

### Tuning

| Problem | Adjustment |
|---------|------------|
| Missing faint nuclei | Lower `probThresh` to 0.3 |
| Noise detected as objects | Raise `probThresh` to 0.7 |
| Merged touching nuclei | Lower `nmsThresh` to 0.2 |
| OOM on large images | Increase `nTiles` |
| Non-nuclear objects | StarDist is nuclei-only — use Cellpose |

### Known Issues
- 3D not supported in Fiji plugin — use Python or max-project first
- First run downloads model (needs internet)
- Time-lapse: processes each frame independently (no tracking)

---

## Cellpose — PARTIAL (via TrackMate)

Segments irregular cells via gradient flow prediction. Handles cytoplasm, phase contrast, diverse morphology.

### Models

| Model | Use Case |
|-------|----------|
| `cyto3` | General cytoplasm (Cellpose 3.0, best general) |
| `nuclei` | Nuclear segmentation |
| `livecell` | Phase contrast, DIC |

### Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `diameter` | 30 | Expected cell diameter (0 = auto) |
| `flow_threshold` | 0.4 | Flow error threshold |
| `cellprob_threshold` | 0.0 | Cell probability (-6 to 6) |
| `channels` | [0,0] | [cytoplasm, nucleus] (0 = grayscale) |

### Access Methods

**Via TrackMate:** Configure conda path first: `run("Configure TrackMate Conda path...");`

**Via Python:**
```python
from cellpose import models; import tifffile
model = models.Cellpose(model_type='cyto3')
masks, _, _, _ = model.eval(img, diameter=30, channels=[0,0])
tifffile.imwrite('masks.tif', masks.astype('uint16'))
```

**Via BIOP wrapper** (not installed — enable PTBIOP update site):
```javascript
run("Cellpose Advanced", "diameter=30 cellprob_threshold=0.0 flow_threshold=0.4 model=cyto3 nuclei_channel=0 cyto_channel=1 dimensionmode=2D");
```

### StarDist vs Cellpose

| Feature | StarDist | Cellpose |
|---------|----------|----------|
| Shape | Star-convex (round) | Any shape |
| Best for | Nuclei | Cytoplasm, irregular |
| Speed | Fast | Slower |
| Fiji integration | Native | Wrapper or Python |
| GPU required | No | Recommended |

---

## WEKA Trainable Segmentation — INSTALLED

Random forest pixel classification. Paint examples, train, apply.

### Key Features (20 types)

Gaussian blur, Sobel, Hessian, DoG, Membrane projections, Variance, Mean, Min, Max, Median, Anisotropic diffusion, Bilateral, Lipschitz, Kuwahara, Gabor, Derivatives, Laplacian, Structure, Entropy, Neighbors.

### Scripting API

```python
from trainableSegmentation import WekaSegmentation
weka = WekaSegmentation(imp)
weka.loadClassifier("/path/to/classifier.model")
weka.applyClassifier(False)
result = weka.getClassifiedImage()
```

### Macro

```javascript
run("Trainable Weka Segmentation");
call("trainableSegmentation.Weka_Segmentation.applyClassifier",
     "/path/to/image.tif", "/path/to/classifier.model",
     "showResults=true", "storeResults=false", "probabilityMaps=false", "/path/to/output/");
```

**Tips:** Balance classes, include edge cases, use probability maps for downstream thresholding. Memory-hungry (reduce features or downsample for large images).

---

## Labkit — INSTALLED (7 commands)

Interactive ML segmentation with BDV integration. Similar to WEKA with better UI and GPU support.

```javascript
run("Open Current Image With Labkit");
run("Segment Image With Labkit", "input=title segmenter_file=/path/to/model.classifier use_gpu=false");
run("Segment Images in Directory with Labkit", "input=/in/ output=/out/ segmenter_file=/path/to/model.classifier use_gpu=false");
```

| Feature | Labkit | WEKA |
|---------|--------|------|
| GPU | Yes (OpenCL) | No |
| 3D | Yes (BDV) | 2D only |
| Scripting | Macro only | Full Java API |
| Batch | Built-in directory cmd | Requires scripting |

---

## CSBDeep / CARE — INSTALLED

Deep learning image restoration (denoising, deconvolution, isotropic reconstruction).

| Command | Use |
|---------|-----|
| `3D Denoising - Planaria` | Pre-trained planaria denoising |
| `3D Denoising - Tribolium` | Pre-trained tribolium denoising |
| `Run your network` | Any trained CSBDeep model |

Pre-trained models are sample-specific. Custom training requires paired data (Python).

---

## DeepImageJ — NOT INSTALLED

Runs BioImage Model Zoo models. Install: Help > Update > Manage Update Sites > "DeepImageJ".

---

## TrackMate with AI Detectors — INSTALLED

| Detector | Requires |
|----------|----------|
| StarDist | StarDist update site |
| Cellpose | Conda env with cellpose |
| ilastik | ilastik installation |

---

## Python Ecosystem

| Library | Purpose |
|---------|---------|
| `cellpose` | Cell segmentation |
| `stardist` | Nuclei segmentation |
| `scikit-image` | Classical processing |
| `napari` | Viewer + plugins |
| `apoc` | GPU pixel/object classification |
| `n2v` | Self-supervised denoising |

### Foundation Models

| Model | Description | Access |
|-------|-------------|--------|
| **MicroSAM** | SAM fine-tuned for microscopy | napari plugin |
| **CellSAM** | SAM for cell segmentation | Python |

No direct Fiji integration yet — use Python and import masks.

### ZeroCostDL4Mic

Google Colab notebooks for training DL models with free GPU: U-Net, StarDist, CARE, Noise2Void, Deep-STORM, pix2pix, CycleGAN. Models export in BioImage.IO format for DeepImageJ.

---

## Common Errors

| Error | Tool | Fix |
|-------|------|-----|
| Out of memory | StarDist | Increase `nTiles` |
| Command not found | StarDist | Enable CSBDeep + StarDist + TensorFlow sites |
| No ROIs in Manager | StarDist | Set `outputType` to "Both" |
| Cellpose not found | Cellpose | Not standalone; use BIOP wrapper or Python |
| WEKA OOM | WEKA | Reduce features, smaller training crops |
| TF version mismatch | CSBDeep | Ensure CSBDeep and TensorFlow sites match |

---

## Installation Summary

### Installed

| Plugin | Update Site |
|--------|------------|
| StarDist 2D | CSBDeep + StarDist + TensorFlow |
| WEKA | Ships with Fiji |
| Labkit | Ships with Fiji |
| CSBDeep/CARE | CSBDeep |
| TrackMate | Ships with Fiji |
| CLIJ2 | clij, clij2 |

### Available (not installed)

| Plugin | Update Site |
|--------|------------|
| DeepImageJ | DeepImageJ |
| Cellpose (BIOP) | PTBIOP |
| Noise2Void | CSBDeep |
