# AI Image Analysis Reference

Agent-oriented reference for deep-learning and machine-learning image
segmentation, denoising, and classification tools available in Fiji.
Dominant content covers StarDist (star-convex nuclei) and Cellpose
(generalist instance segmentation, including Cellpose-SAM in
Cellpose 4); the rest covers WEKA Trainable Segmentation, Labkit,
CSBDeep/CARE, DeepImageJ, TrackMate AI detectors, and the surrounding
Python ecosystem (foundation models, ZeroCostDL4Mic).

Sources: [StarDist Fiji](https://imagej.net/plugins/stardist),
[StarDist FAQ](https://stardist.net/faq/),
[stardist GitHub](https://github.com/stardist/stardist),
[stardist-imagej](https://github.com/stardist/stardist-imagej),
[Cellpose docs](https://cellpose.readthedocs.io/en/latest/),
[Cellpose API](https://cellpose.readthedocs.io/en/latest/api.html),
[Cellpose-SAM preprint](https://www.biorxiv.org/content/10.1101/2025.04.28.651001v1),
[BIOP wrappers](https://github.com/BIOP/ijl-utilities-wrappers),
[BIOP Cellpose page](https://imagej.net/plugins/cellpose),
[TrackMate-Cellpose-SAM](https://imagej.net/plugins/trackmate/detectors/trackmate-cellpose-sam),
[TWS](https://imagej.net/plugins/tws/), [Labkit](https://imagej.net/plugins/labkit/),
[CSBDeep](https://csbdeep.bioimagecomputing.com),
[DeepImageJ](https://deepimagej.github.io)

Citations: Schmidt et al. 2018 (StarDist 2D, MICCAI),
Weigert et al. 2020 (StarDist 3D, WACV),
Stringer et al. 2021 (Cellpose, *Nat. Methods*),
Pachitariu & Stringer 2022 (Cellpose 2.0 HITL, *Nat. Methods*),
Stringer & Pachitariu 2024 (Cellpose 3 restoration, *Nat. Methods*),
Pachitariu, Rariden & Stringer 2025 (Cellpose-SAM, bioRxiv
10.1101/2025.04.28.651001).

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript inside Fiji's JVM.
`python probe_plugin.py "Plugin Name..."` — discover plugin parameters at runtime.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Which tool for nuclei segmentation?" | §2 (decision tree) → §3 StarDist |
| "Which tool for irregular cells / cytoplasm?" | §2 → §4 Cellpose |
| "Cellpose-SAM — what is it, when do I use it?" | §4.1, §4.5 |
| "How do I run Cellpose from a macro?" | §4.6 PTBIOP wrapper, §4.7 via Python |
| "How do I run Cellpose from TrackMate?" | §9 + §4.10 |
| "How do I tune StarDist parameters?" | §3.4 |
| "How do I tune Cellpose parameters?" | §4.4 |
| "What's the decision tree for picking a tool?" | §2 |
| "StarDist vs Cellpose — which one?" | §3.6 / §4.11 comparison |
| "How do I train a custom StarDist or Cellpose?" | §3.5, §4.9 |
| "How do I validate against manual ground truth?" | §13 reproducibility |
| "How do I train a custom pixel classifier?" | §5 WEKA, §6 Labkit |
| "Labkit vs WEKA — which one?" | §6 comparison table |
| "How do I denoise images with deep learning?" | §7 CSBDeep/CARE |
| "How do I run BioImage Model Zoo models?" | §8 DeepImageJ |
| "Which AI detectors does TrackMate support?" | §9 TrackMate AI |
| "What Python DL libraries are relevant?" | §10 Python ecosystem |
| "Foundation models (SAM etc.) for microscopy?" | §10 foundation models |
| "How do I train models on free GPU?" | §10 ZeroCostDL4Mic |
| "Why did my StarDist/Cellpose/WEKA run fail?" | §11 common errors |
| "Is plugin X installed? What update site?" | §12 installation summary |
| "Real argument keys for an installed plugin?" | `python probe_plugin.py "Plugin Name..."` |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term.
Use `grep -n '<term>' ai-image-analysis-reference.md` to jump.

### A
`additional_flags` §4.6 · `apoc` §10 · `Available (not installed)` §12

### B
`bact_phase / bact_fluor` §4.1 · `bfloat16` §4.5 · `BIOP wrapper` §4.6 · `BioImage Model Zoo` §8 · `BioImage.IO` §10 · `bsize` §4.4

### C
`CARE` §7 · `Cellpose` §2, §4 · `Cellpose-SAM` §4.1, §4.5 · `Cellpose 3 restoration` §4.5 · `Cellpose 4 changes` §4.5 · `cellproba_threshold` §4.4, §4.6 · `cellprob_threshold` §4.4 · `CellposeModel` §4.7 · `CellSAM` §10 · `channels (Cellpose)` §4.4 · `CLIJ2` §12 · `classical threshold` §2 · `Colab` §10, §3.5 · `Command not found` §11 · `conda` §4.6, §4.10 · `cpsam` §4.1, §4.5 · `CSBDeep` §7, §11, §12 · `cyto2/cyto3` §4.1 · `cytoplasm` §2, §4

### D
`DAPI` §2, §3.1 · `decision tree` §2 · `DeepImageJ` §8, §12 · `Deep-STORM` §10 · `denoise_cyto3` §4.5 · `denoising` §2, §7 · `DIC` §4.1 · `diameter (Cellpose)` §4.4 · `Dice / IoU` §13 · `dimensionmode` §4.6 · `do_3D` §4.4 · `DoG` §5.1 · `DSB 2018` §3.1

### E
`env_path / env_type` §4.6 · `excludeBoundary` §3.2 · `edge cases (training)` §5.3

### F
`feature selection (WEKA)` §5.1 · `Fiji integration` §3.7, §4.6 · `flow_threshold` §4.4 · `fluorescent nuclei` §3.1

### G
`Gabor` §5.1 · `Gaussian blur (WEKA feature)` §5.1 · `glasbey LUT` §3.7 · `GPU` §3.7, §4.5, §6, §10 · `gradient flow` §4 · `ground truth` §13

### H
`H&E` §2, §3.1 · `Hessian` §5.1 · `Hoechst` §2, §3.1 · `human-in-the-loop (HITL)` §4.9

### I
`ilastik` §9 · `Installed` §12 · `instance segmentation` §3, §4 · `Interactive ML` §2, §6 · `irregular cells` §2, §4

### K
`Kuwahara` §5.1

### L
`Label Image` §3.3 · `label collisions (3D)` §4.4, §11 · `Labkit` §2, §6, §12 · `Laplacian` §5.1 · `Lipschitz` §5.1 · `livecell` §4.1

### M
`Macro syntax (StarDist)` §3.3 · `Macro syntax (Cellpose)` §4.6 · `Mean (WEKA)` §5.1 · `Median (WEKA)` §5.1 · `memory-hungry` §5.3 · `Membrane projections` §5.1 · `MicroSAM` §10 · `min_size` §4.4 · `model downloads` §3.5, §4.9 · `modelChoice` §3.3 · `modelFile` §3.5 · `model_path (Cellpose)` §4.6 · `MoNuSeg` §3.1 · `moving cells` §9 · `MPS (Apple Silicon)` §4.5

### N
`N2V` §10 · `napari` §10 · `n_rays` §3.5 · `Noise2Void` §10, §12 · `nmsThresh` §3.2, §3.4 · `normalizeInput` §3.2 · `nTiles` §3.2, §3.4, §11 · `nuclei` §2, §3, §4.1 · `niter` §4.4

### O
`object classification (apoc)` §10 · `Omnipose` §4.6 · `OOM` §3.4, §5.3, §11 · `outputType` §3.2, §11

### P
`PTBIOP` §4.6, §12 · `paired data` §7 · `percentileBottom / percentileTop` §3.2 · `phase contrast` §2, §4.1 · `pix2pix` §10 · `pixel classification` §2, §5 · `Polygons (StarDist output)` §3.3 · `probability maps` §5.2, §5.3 · `probThresh` §3.2, §3.4 · `probe_plugin` §0 · `Python ecosystem` §10

### R
`random forest` §5 · `resample` §4.4 · `restoration (Cellpose 3)` §4.5 · `Run your network` §7 · `roiPosition` §3.2 · `ROI Manager (StarDist output)` §3.3, §11 · `round nuclei` §2

### S
`SAM` §4.5, §10 · `scikit-image` §10 · `Segment Image With Labkit` §6 · `Segment Images in Directory with Labkit` §6 · `self-supervised denoising` §10 · `Sobel` §5.1 · `standalone (Cellpose)` §11 · `StarDist 2D` §2, §3, §9, §12 · `StarDist 3D (Python only)` §3.5 · `star-convex` §3, §3.6 · `stardist-napari` §3.5, §10 · `stitch_threshold` §4.4 · `Structure (feature)` §5.1 · `supervised denoising` §2, §7

### T
`TF version mismatch` §11 · `TensorFlow` §11, §12 · `thresholds.json` §3.5, §13 · `tile_norm_blocksize` §4.4 · `time-lapse` §3.5 · `tissuenet` §4.1 · `touching nuclei` §2, §3.4 · `TrackMate` §2, §4.10, §9, §12 · `TrackMate-Cellpose` §4.10, §9 · `TrackMate-Cellpose-SAM` §4.10, §9 · `training data (paired)` §7 · `training crops (WEKA)` §11 · `Tribolium` §7 · `tuning (StarDist)` §3.4 · `tuning (Cellpose)` §4.8 · `TWS` §5

### U
`U-Net` §4.5, §10 · `Update Site` §3.7, §4.6, §11, §12

### V
`Versatile (fluorescent nuclei)` §3.1 · `Versatile (H&E nuclei)` §3.1 · `ViT backbone` §4.5

### W
`watershed` §2 · `WEKA` §2, §5, §11, §12 · `WekaSegmentation (API)` §5.2

### Z
`ZeroCostDL4Mic` §3.5, §4.9, §10 · `z-stack (3D)` §3.5, §4.4

---

## §2 Decision Tree

Pick the right tool *before* you start clicking. Wrong tool = wasted hours.

```
What are you segmenting?

├─ Round / convex nuclei (DAPI, Hoechst, H&E)?
│   ├─ Well-separated, simple background → Otsu/Triangle + watershed (§2)
│   ├─ Dense / touching → StarDist 2D (§3) — fastest, no GPU needed
│   ├─ H&E section → StarDist 2D, model "Versatile (H&E nuclei)" (§3.1)
│   └─ 3D z-stack → Cellpose-SAM with do_3D=True (§4.4) OR StarDist Python 3D
│
├─ Irregular cells / cytoplasm / membranes?
│   ├─ Default first try → Cellpose-SAM (cpsam) — generalist, no diameter needed (§4.5)
│   ├─ Phase contrast / DIC time-lapse → cyto3 or livecell_cp3 (§4.1)
│   ├─ Bacteria → bact_phase_cp3 / bact_fluor_cp3 / deepbacs_cp3 (§4.1)
│   ├─ Tissue (multiplexed IF) → tissuenet_cp3 (§4.1)
│   └─ Cellpose-SAM disagrees with you on diameter → fall back cyto3, set diameter (§4.4)
│
├─ Region/tissue type (not instance segmentation)?
│   ├─ A few classes, drawn examples → Labkit (GPU, fast) (§6)
│   ├─ Need fine feature control or batch scripting → WEKA TWS (§5)
│   └─ Texture-defined regions (H&E zones, EM ultrastructure) → WEKA TWS
│
├─ Denoise / restore?
│   ├─ Paired clean+noisy data → CARE / CSBDeep (§7)
│   ├─ Cellpose-3 specialist data → denoise_cyto3 / deblur_cyto3 (§4.5)
│   └─ No paired data → Noise2Void (§10), or classical Gaussian/Median
│
└─ Track moving objects?
    └─ TrackMate + StarDist or TrackMate-Cellpose-SAM detector (§9)
```

**Tie-breakers (StarDist vs Cellpose):**
- All your objects are convex blobs and you have CPU only → StarDist.
- Anything not strictly convex (cytoplasm, fibroblast, neuron, dividing cell mid-anaphase) → Cellpose.
- You don't know object size and don't want to fiddle → Cellpose-SAM.
- You need ROI Manager output for downstream Fiji macros → StarDist (native ROI output).

---

## §3 StarDist 2D — INSTALLED

Detects star-convex polygons. Each pixel inside a nucleus predicts radial
distances to the nucleus boundary in 32 directions; non-maximum suppression
picks the best non-overlapping polygons. Output is true instance
segmentation (unique label per nucleus). Trained models for fluorescent
nuclei and H&E ship with the plugin.

Plugin command: `Plugins > StarDist > StarDist 2D`. Java class:
`de.csbdresden.stardist.StarDist2D`. The plugin is **2D-only** —
the StarDist FAQ explicitly states *"The plugin currently only
supports 2D image and time lapse data."* For 3D, use Python or
`stardist-napari` (§3.5, §10).

### §3.1 Bundled Models

Exact `modelChoice` strings (case- and punctuation-sensitive):

| `modelChoice` string | Training data | Use case |
|----|----|----|
| `Versatile (fluorescent nuclei)` | DSB 2018 + heavy augmentation | DAPI, Hoechst, SYTOX, NucBlue, any fluorescent nuclear stain. **Default first try.** |
| `Versatile (H&E nuclei)` | MoNuSeg 2018 + TNBC | H&E brightfield histology only — fails on fluorescence. |
| `DSB 2018 (from StarDist 2D paper)` | DSB 2018 (lighter aug) | Reproduces the 2018 paper. Versatile (fluo) generalises better in practice. |
| `Model (.zip) from File` | Custom (yours) | Set `modelFile` to a path to a TF SavedModel `.zip` exported from Python (§3.5). |

**Failure modes by model:**
- `Versatile (fluorescent nuclei)` over-segments very large nuclei
  (object > network receptive field). Workaround: downsample 2× before
  StarDist, then upsample the label image with nearest-neighbour.
- `Versatile (H&E nuclei)` is colour-sensitive — apply colour
  deconvolution first if H is faint (see
  `colour-deconvolution-histology-reference.md`).
- All three fail on dense overlapping bacterial-style nuclei (use
  Cellpose `bact_fluor_cp3` instead) and on hollow nuclei with strong
  nucleolar contrast (StarDist fills holes; Cellpose preserves them).

### §3.2 Parameters

| Parameter | Default | Range | Description |
|----|----|----|----|
| `input` | — | — | Window title of the source image |
| `modelChoice` | Versatile (fluorescent nuclei) | (table above) | Built-in or custom |
| `modelFile` | — | path | Required when `modelChoice = Model (.zip) from File` |
| `normalizeInput` | true | bool | Apply percentile normalisation pre-network |
| `percentileBottom` | 1.0 | 0–100 | Lower percentile clip |
| `percentileTop` | 99.8 | 0–100 | Upper percentile clip |
| `probThresh` | 0.48–0.5* | 0–1 | Detection confidence (lower = more detections) |
| `nmsThresh` | 0.4 | 0–1 | Overlap tolerance (lower = less overlap allowed) |
| `outputType` | Both | (table §3.3) | Label Image / ROI Manager / Both / Polygons |
| `nTiles` | 1 | int | Tile count (raise for large/OOM images) |
| `excludeBoundary` | 2 | px | Drop objects within N px of the image edge |
| `roiPosition` | Automatic | Automatic / Stack / Hyperstack | How to assign ROIs to slices |
| `verbose` | false | bool | Log per-tile progress |
| `showCsbdeepProgress` | false | bool | Show the CSBDeep progress dialog |

*Default `probThresh` and `nmsThresh` come from the model's
`thresholds.json` (set by `model.optimize_thresholds()` during training).
The dialog values override them.

### §3.3 Macro Syntax

```javascript
run("Command From Macro",
  "command=[de.csbdresden.stardist.StarDist2D], " +
  "args=[" +
    "'input':'IMAGE_TITLE', " +
    "'modelChoice':'Versatile (fluorescent nuclei)', " +
    "'normalizeInput':'true', " +
    "'percentileBottom':'1.0', " +
    "'percentileTop':'99.8', " +
    "'probThresh':'0.5', " +
    "'nmsThresh':'0.4', " +
    "'outputType':'Both', " +
    "'nTiles':'1', " +
    "'excludeBoundary':'2', " +
    "'roiPosition':'Automatic', " +
    "'verbose':'false', " +
    "'showCsbdeepProgress':'false'" +
  "], process=[false]");
```

`process=[false]` tells the SciJava `CommandFromMacro` runner not to
auto-iterate over every open image — it disables batch over the image
list and runs once on `input` only.

**Custom model variant** — set `modelChoice` and add `modelFile`:

```javascript
'modelChoice':'Model (.zip) from File',
'modelFile':'C:/Users/me/models/my_stardist_2d.zip',
```

**Output types:**

| `outputType` | Result |
|----|----|
| `Label Image` | Single 16-bit image, each nucleus has a unique integer ID, 0=background |
| `ROI Manager` | One polygon ROI per nucleus added to the ROI Manager |
| `Both` | Label image **and** ROIs (most common — use this) |
| `Polygons` | Returns Java polygon objects (scripting only, not for macro) |

### §3.4 Tuning

| Symptom | Adjustment | Rationale |
|----|----|----|
| Missing faint nuclei | Lower `probThresh` to 0.3 | More candidates pass confidence gate |
| Faint nuclei still missed | Tighten `percentileBottom`/`percentileTop` to 0.5/99.8 or 0/100 | Stretches contrast pre-network |
| Noise picked up as objects | Raise `probThresh` to 0.6–0.7 | Higher confidence required |
| Touching nuclei merged into one | Lower `nmsThresh` to 0.2–0.3 | Overlapping candidates compete harder |
| Single nucleus split into pieces | Raise `nmsThresh` to 0.5 + raise `probThresh` | Allow more overlap, fewer marginal hits |
| OOM on large images | Raise `nTiles` to 2, 4, 8 | Splits image into N×N tiles |
| Edge nuclei missed/wrong | Reduce `excludeBoundary` to 0; pad image first | Default drops edge cells |
| Output is non-nuclear blobs | Wrong tool | StarDist is nuclei-only — use Cellpose §4 |

**Workflow for tuning:** start with defaults, run on one representative
image, capture (`python ij.py capture stardist_default`), inspect
visually, then change ONE parameter, re-run, compare. Save a
ground-truth annotation for 2–3 images and quantify Dice/IoU before
locking parameters (§13).

### §3.5 Custom Models — Training & Loading

**Training (Python or ZeroCostDL4Mic Colab):**

```python
from stardist.models import StarDist2D, Config2D
from stardist import gputools_available

config = Config2D(
    n_rays=32,                    # default; 64 for elongated objects
    grid=(2, 2),                  # default; (1,1) for tiny objects
    n_channel_in=1,
    train_patch_size=(256, 256),
    train_batch_size=4,
    train_epochs=400,
    use_gpu=gputools_available(),
)
model = StarDist2D(config, name='my_model', basedir='models')
model.train(X_train, Y_train, validation_data=(X_val, Y_val))
model.optimize_thresholds(X_val, Y_val)   # writes thresholds.json
model.export_TF()                         # writes TF_SavedModel.zip — load this in Fiji
```

- Labels: integer instance masks, uint16, 0 = background, each object
  a unique ID (NOT binary, NOT outlines).
- Place the exported `.zip` anywhere readable; point `modelFile` at it.
- The bundled `thresholds.json` sets the dialog default `probThresh`
  and `nmsThresh` — but Fiji values override, so record what you used.

**ZeroCostDL4Mic** ([github](https://github.com/HenriquesLab/ZeroCostDL4Mic))
provides a free-GPU Colab notebook for StarDist 2D training that
exports directly to the Fiji-compatible format. Recommended path for
non-Python users.

**3D StarDist:** the Fiji plugin does **not** support 3D; the Java port
is blocked on the C++ NMS code. Options:
1. Use the Python `stardist` package with a 3D pretrained model
   (`Versatile (fluorescent_nuclei_3d)` is Python-only).
2. Use `stardist-napari` for an interactive viewer with 3D.
3. Z-project (max-intensity) and run StarDist 2D — loses Z information
   but recovers most counts for sparse objects.
4. Use Cellpose-SAM `do_3D=True` instead (§4.4).

### §3.6 Star-Convex Failure Modes

StarDist's geometric assumption is that any object can be described as
a polygon traced from a centroid by N radial rays. This **breaks** for:

- **Concave shapes** — kidney-bean nuclei, mitotic cells with cleavage
  furrow. StarDist rounds them off or splits them.
- **Elongated cells** — neurons, fibroblasts, smooth muscle. Rays
  sampled from a centroid clip the long axis. Increase `n_rays` to 64
  during training, or use Cellpose.
- **Hollow rings / nucleoli holes** — StarDist fills them in;
  no support for objects with holes.
- **Bright cytoplasmic autofluorescence** — biases the centroid off
  the nucleus, leading to wrong polygons. Fix by background-subtracting
  pre-StarDist or using Cellpose nuclei model with cytoplasmic input.

### §3.7 Fiji Integration

**Update sites required:** `CSBDeep` + `StarDist` + `TensorFlow` (all
three; check via `Help > Update > Manage Update Sites`).
- `CSBDeep` provides the CNN inference runtime + normalisation.
- `StarDist` provides the plugin + bundled model files.
- `TensorFlow` provides the native TF JNI library.

**GPU TensorFlow:** Fiji ships CPU TF by default. To swap to GPU TF,
use `Edit > Options > TensorFlow...` and pick a CUDA-enabled
build matching your driver. StarDist 2D on a 2048×2048 fluorescent
nuclei image runs roughly **5–20 s on CPU**, **<1 s on GPU**.

**Output handling:** with `outputType='Both'` you get both a label
image (titled "Label Image") and ROIs in the ROI Manager. Apply a
glasbey LUT for visualisation:
```javascript
selectWindow("Label Image");
run("glasbey on dark");  // requires "Glasbey" update site for the LUT
```

### §3.8 Worked Example — Count nuclei in a 2D image

```bash
python ij.py macro '
  open("/path/to/dapi.tif");
  title = getTitle();
  run("Command From Macro",
    "command=[de.csbdresden.stardist.StarDist2D], " +
    "args=[" +
      "\"input\":\"" + title + "\", " +
      "\"modelChoice\":\"Versatile (fluorescent nuclei)\", " +
      "\"normalizeInput\":\"true\", " +
      "\"percentileBottom\":\"1.0\", " +
      "\"percentileTop\":\"99.8\", " +
      "\"probThresh\":\"0.5\", " +
      "\"nmsThresh\":\"0.4\", " +
      "\"outputType\":\"Both\", " +
      "\"nTiles\":\"1\", " +
      "\"excludeBoundary\":\"2\", " +
      "\"roiPosition\":\"Automatic\", " +
      "\"verbose\":\"false\", " +
      "\"showCsbdeepProgress\":\"false\"" +
    "], process=[false]");
  selectWindow("Label Image");
  saveAs("Tiff", "/path/to/AI_Exports/dapi_labels.tif");
  run("Set Measurements...", "area mean centroid display redirect=None decimal=3");
  roiManager("Measure");
  saveAs("Results", "/path/to/AI_Exports/dapi_measurements.csv");
'
python ij.py results
```

---

## §4 Cellpose — INSTALLED via PTBIOP wrapper + TrackMate

Generalist instance segmentation via gradient-flow prediction. Handles
arbitrarily-shaped objects: cytoplasm, irregular cells, bacteria,
phase-contrast, DIC. Cellpose 4 (May 2025) replaced the U-Net/ResNet
backbone with a SAM-derived ViT — the result is **Cellpose-SAM**, the
recommended default.

### §4.1 Models

Chronological lineage. Pretrained models download automatically on
first use (require internet).

| Model | Cellpose version | Use case |
|----|----|----|
| `cyto`, `nuclei` | 1 (2020) | Original — superseded |
| `cyto2` | 2 (2022) | HITL-trained generalist — superseded |
| `cyto3` | 3 (Feb 2024) | Strong cytoplasm generalist; recommend for non-SAM workflows |
| `nuclei` | (still bundled) | Nuclear-only segmentation |
| `tissuenet_cp3` | 3 specialist | Multiplexed IF tissue |
| `livecell_cp3` | 3 specialist | Phase contrast time-lapse |
| `bact_phase_cp3` | 3 specialist | Bacteria, phase contrast |
| `bact_fluor_cp3` | 3 specialist | Bacteria, fluorescence |
| `deepbacs_cp3` | 3 specialist | DeepBacs benchmark, mixed bacterial morphologies |
| `denoise_cyto3` / `deblur_cyto3` / `upsample_cyto3` | 3 restoration | Pre-segmentation image restoration |
| **`cpsam`** | **4 (May 2025)** | **Cellpose-SAM. Default. Handles diameters 7.5–120 px without setting `diameter`.** |

`cpsam` is the default in `CellposeModel`. Legacy v3 names still load
explicitly but are not the recommended path going forward.

### §4.2 Cellpose Lineage At A Glance

| Year | Release | Key change |
|----|----|----|
| 2020 | Cellpose 1 | Original gradient-flow U-Net (`cyto`, `nuclei`) |
| 2022 | Cellpose 2.0 | Human-in-the-loop training in GUI (`cyto2`) |
| Feb 2024 | Cellpose 3 | Image restoration suite (denoise/deblur/upsample) + specialists (`cyto3`, `tissuenet_cp3`, `livecell_cp3`, bacterial models) |
| May 2025 | Cellpose 4 / Cellpose-SAM | ViT backbone (`cpsam`), `diameter` no longer required, bfloat16 weights, removed `Cellpose` and `SizeModel` classes — only `CellposeModel` remains |

### §4.3 Quick Start (Python)

```python
from cellpose import models, io
import tifffile

img = tifffile.imread('image.tif')
model = models.CellposeModel(gpu=True, pretrained_model='cpsam')
masks, flows, styles = model.eval(img)         # diameter not needed for cpsam
tifffile.imwrite('masks.tif', masks.astype('uint16'))
```

For legacy v3-style code:
```python
model = models.CellposeModel(gpu=True, pretrained_model='cyto3')
masks, flows, styles = model.eval(img, diameter=30, channels=[0, 0])
```

### §4.4 Parameters (`model.eval` defaults, current v4 API)

| Parameter | Default | Description |
|----|----|----|
| `diameter` | None | Expected object diameter in pixels. Ignored by `cpsam` (handles 7.5–120 px internally). For legacy models, set explicitly or pass 0 to auto-estimate. |
| `flow_threshold` | 0.4 | Maximum allowed flow error per mask. **Raise** to recover more cells, **lower** for higher precision. |
| `cellprob_threshold` | 0.0 | Pixel inclusion threshold (range −6 to +6). **Lower** to capture dimmer cells; **raise** to reject noise. |
| `channels` | None (auto) | Legacy: `[cyto_channel, nuclear_channel]` with 0=grayscale, 1=R, 2=G, 3=B. Largely ignored by `cpsam`. |
| `do_3D` | False | True 3D segmentation via 3D flows (memory-hungry, accurate). |
| `stitch_threshold` | 0.0 | If >0, runs 2D per-slice and stitches by IoU between adjacent slices. Cheaper than `do_3D=True`. |
| `min_size` | 15 | Drop masks smaller than N pixels (in 2D) or voxels (in 3D). |
| `niter` | None | Flow integration steps; None = auto. Raise for highly elongated cells. |
| `resample` | True | Resample to diameter scale before inference (better for objects far from training scale). |
| `tile_norm_blocksize` | 0 | Per-tile percentile normalisation block size. >0 helps uneven illumination. |

**3D recipe:**
- `do_3D=True` for true 3D — best quality, requires lots of GPU RAM.
- `stitch_threshold=0.25–0.5` (with `do_3D=False`) for cheaper 2D-per-slice
  + IoU stitching. Set higher (0.5) if labels collide across z; lower
  if same cell breaks into multiple IDs.

**Channel handling (legacy / `cyto3`):**
- Grayscale single-channel: `channels=[0,0]`
- Cytoplasm = green, no nucleus reference: `channels=[2,0]`
- Cytoplasm = green, nucleus = blue: `channels=[2,3]`

**Cellpose-SAM (`cpsam`):**
- `channels` is essentially ignored — the ViT backbone handles
  multi-channel input directly. Pass the image as-is.
- `diameter` is also ignored. Setting it does no harm but doesn't help.

### §4.5 What Changed in Cellpose 3 → 4

**Cellpose 3 (Feb 2024):**
- Added image-restoration models: `denoise_cyto3`, `deblur_cyto3`,
  `upsample_cyto3`. These pre-process noisy/blurry/low-resolution images
  to restore conditions seen in training.
- Specialist segmentation models: `tissuenet_cp3`, `livecell_cp3`,
  `bact_phase_cp3`, `bact_fluor_cp3`, `deepbacs_cp3`.
- MPS (Apple Silicon GPU) support added in v3.1+.

**Cellpose 4 / Cellpose-SAM (May 2025):**
- New backbone: SAM-derived Vision Transformer (ViT) replacing the
  U-Net/ResNet of v1–v3.
- Single model `cpsam` replaces the cyto/nuclei split for most use
  cases. Trained on cells with diameters 7.5–120 px so `diameter` is
  no longer required.
- Default weights stored as **bfloat16** (~50% smaller, ~40% faster).
  Revert with `use_bfloat16=False` if you need fp32 reproducibility.
- `cellpose.models.Cellpose` and `SizeModel` classes **removed** —
  only `CellposeModel` remains. Existing v3 code that imports `Cellpose`
  must be updated.
- `channels` argument largely ignored by `cpsam`.

**When to still use `cyto3` instead of `cpsam`:**
- You have a v3-trained custom model that you don't want to retrain.
- You're stuck with an older PTBIOP wrapper build that doesn't expose
  `Cellpose SAM ...` yet.
- You're benchmarking against published v3 results.

### §4.6 Fiji Integration — PTBIOP Wrapper

**Plugin:** `Plugins > BIOP > Cellpose/Omnipose >` ...

| Menu entry | When to use |
|----|----|
| `Cellpose ...` | Cellpose ≤ 3.1.1.1 in env, basic args |
| `Cellpose Advanced ...` | Cellpose ≤ 3.1.1.1, full arg list (this is the macro-recordable name) |
| `Cellpose SAM ...` | Cellpose ≥ 4.0.0 in env, default model `cpsam` |
| `Omnipose ...` | Omnipose-specific (separate package) |

**Update site:** `PTBIOP` (https://biop.epfl.ch/Fiji-Update/).
**Source:** [BIOP/ijl-utilities-wrappers](https://github.com/BIOP/ijl-utilities-wrappers).
**Mechanism:** wrapper saves the active image to a temp dir, activates
the configured Python env, shells out to the `cellpose` CLI, reads
the resulting label image back into Fiji.

**First-time setup (the wrapper does NOT install Cellpose):**

1. Create a Python env (conda or venv) and `pip install cellpose`
   plus `torch` (CUDA wheel if you want GPU).
2. Verify: `python -m cellpose --help`
3. In Fiji, open `Plugins > BIOP > Cellpose/Omnipose > Cellpose ...`.
4. Set `env_path` to the env **directory** (not `python.exe`), e.g.
   `C:\Users\me\miniconda3\envs\cellpose` or `/opt/cellpose-venv`.
5. Set `env_type` = `conda` or `venv`.
6. Settings persist via SciJava prefs (`IJ_Prefs.txt`) keyed by parameter name.

**Windows extra:** run `conda init powershell` once so the wrapper
can call `conda activate` from outside the Anaconda Prompt.

**Macro syntax (recordable name = "Cellpose Advanced"):**

```javascript
run("Cellpose Advanced",
  "env_path=C:/Users/me/miniconda3/envs/cellpose " +
  "env_type=conda " +
  "model=cyto3 " +
  "model_path=null " +
  "diameter=30 " +
  "cellproba_threshold=0.0 " +    // NOTE: cellproba, not cellprob
  "flow_threshold=0.4 " +
  "nuclei_channel=1 " +
  "cyto_channel=2 " +
  "dimensionmode=2D " +
  "stitch_threshold=-1 " +
  "additional_flags=[--use_gpu]");
```

**Argument keys (from `CellposeAbstractCommand.java`):**

| Key | Meaning | Notes |
|----|----|----|
| `env_path` | Absolute path to env directory | Persistent across runs |
| `env_type` | `conda` \| `venv` | |
| `model` | One of §4.1 model names | `cyto3` for v3 entry, `cpsam` for SAM entry |
| `model_path` | Path to custom `.pth` model | `null` for built-in |
| `diameter` | Expected diameter (px) | 0 for auto. Ignored by `cpsam` |
| `cellproba_threshold` | Cellprob threshold | **Note misspelling — `cellproba`, not `cellprob`** |
| `flow_threshold` | Flow error threshold | |
| `nuclei_channel` | 1-indexed channel for nuclei | 0 = grayscale |
| `cyto_channel` | 1-indexed channel for cytoplasm | |
| `dimensionmode` | `2D` \| `3D` | `3D` triggers `do_3D` |
| `stitch_threshold` | IoU threshold for 2D-stitched 3D | -1 disables; 0–1 enables stitching |
| `additional_flags` | Comma-separated extra CLI flags | e.g. `[--use_gpu, --do_3D]` |
| `ch1` / `ch2` | Channel indices passed as `--chan` / `--chan2` | Used internally |

**Cellpose SAM macro variant:**
- Same wrapper, separate menu entry `Cellpose SAM ...`
- `model` defaults to `cpsam`, channels forced to -1, -1
- Recordable args may differ slightly between wrapper builds — record
  with `Plugins > Macros > Record...` to confirm exact keys for your
  installed version.

**Output:**
- Window title: `<shortTitle>-cellpose` (e.g. `myimage-cellpose`)
- Type: 32-bit label image, each cell a unique integer ID
- LUT: `3-3-2 RGB` (NOT glasbey by default)
- Convert to ROIs: `Plugins > BIOP > Image Analysis > ROIs > Label Image to ROIs`

### §4.7 Direct Python Use (when the wrapper is too restrictive)

Run via `python ij.py script` (Groovy in JVM) is **not** how to invoke
Cellpose — Cellpose is Python only. Run Python externally and import
the resulting mask back into Fiji.

```python
# segment_with_cellpose.py
import sys, tifffile
from cellpose import models

img_path, out_path = sys.argv[1], sys.argv[2]
img = tifffile.imread(img_path)
model = models.CellposeModel(gpu=True, pretrained_model='cpsam')
masks, flows, styles = model.eval(img, do_3D=False)
tifffile.imwrite(out_path, masks.astype('uint16'))
```

```bash
# Run from agent
conda run -n cellpose python segment_with_cellpose.py input.tif masks.tif
python ij.py macro 'open("masks.tif"); run("glasbey on dark");'
```

### §4.8 Tuning

| Symptom | Adjustment |
|----|----|
| Missed dim cells | Lower `cellprob_threshold` to −1 to −3 |
| Spurious detections in noise | Raise `cellprob_threshold` to +1 to +3 |
| Cells split into pieces | Raise `flow_threshold` to 0.6–0.8 |
| Multiple cells merged | Lower `flow_threshold` to 0.2–0.3 |
| Wrong size estimation (cyto3) | Set `diameter` explicitly; don't trust auto on uneven illumination |
| `cpsam` mis-segments very large or very small objects | Outside the 7.5–120 px training range — resize first or fall back to `cyto3` with explicit `diameter` |
| 3D over-merges across slices | Raise `stitch_threshold` to 0.4–0.5 |
| 3D one cell breaks into multiple IDs | Lower `stitch_threshold` to 0.1–0.25 |
| OOM on GPU with large image | Add `tile_norm_blocksize=128`, lower `bsize`, set explicit smaller `diameter` |
| OOM on `do_3D=True` | Switch to `stitch_threshold` 2D mode; or crop |

### §4.9 Custom Training (Human-in-the-Loop)

**GUI workflow (recommended for non-coders):**
1. `python -m cellpose` to launch the GUI
2. Open an image, run a base model (`cpsam` or `cyto3`)
3. Correct masks: paint missing cells, delete false positives
4. Save masks (`Ctrl+S` → writes `_seg.npy`)
5. Repeat across 5–20 representative images
6. `Models > Train new model with image+masks in folder` — pick a
   pretrained backbone, runs ~10–30 minutes on GPU.

**CLI training:**
```bash
python -m cellpose --train \
  --dir /path/to/training_folder \
  --pretrained_model cpsam \
  --n_epochs 100 \
  --learning_rate 1e-5 \
  --weight_decay 0.1 \
  --train_batch_size 1 \
  --bsize 256 \
  --min_train_masks 5 \
  --mask_filter _masks
```

- Labels: integer instance masks (background=0, each cell=unique ID).
  uint16 supported (and required when label count > 255).
- Naming: `image.tif` + `image_masks.tif`, OR `_seg.npy` from the GUI.
- Training set: 5–20 well-annotated images is usually enough when
  fine-tuning from `cpsam` or `cyto3`.

**ZeroCostDL4Mic** also has a Cellpose 2 notebook (§10) for free-GPU
training, though for newer models the local GUI workflow is faster.

### §4.10 TrackMate Cellpose Detector

Distinct plugin from PTBIOP — uses the same Cellpose env but its own
configuration UI inside TrackMate.

| Plugin | Update site | Notes |
|----|----|----|
| `TrackMate-Cellpose` | TrackMate-Cellpose | For Cellpose 3.x, picks `cyto3` etc. |
| `TrackMate-Cellpose-SAM` | TrackMate-Cellpose-SAM | Dedicated SAM detector, requires Cellpose ≥ 4 |

Both ask for:
- Conda env path (same form as PTBIOP)
- Pretrained model name
- Optional custom `.pth` model
- Channel selection
- GPU toggle
- "Simplify contours" (reduces ROI vertex count for downstream speed)

Use TrackMate when you need linking across time. For one-shot per-frame
segmentation prefer the PTBIOP wrapper (faster pipeline, no tracker
overhead).

### §4.11 StarDist vs Cellpose

| Feature | StarDist 2D (Fiji) | Cellpose-SAM | Cellpose `cyto3` |
|----|----|----|----|
| Object shapes | Star-convex only | Any | Any |
| Best for | Round/convex nuclei | Cytoplasm, irregular cells, generalist | Cytoplasm with explicit diameter |
| Diameter parameter | N/A | Not needed (auto 7.5–120 px) | Required (or auto-estimate) |
| Speed (2048², CPU) | 5–20 s | Slower (~30–120 s) | Slower (~30–60 s) |
| Speed (2048², GPU) | <1 s | ~1–5 s | ~1–3 s |
| GPU required | Optional | Strongly recommended | Strongly recommended |
| Fiji integration | Native plugin | PTBIOP wrapper (shells out) | PTBIOP wrapper |
| Output | Label + ROIs | Label image | Label image |
| Custom training | Python / ZeroCostDL4Mic | Python GUI HITL or CLI | Python GUI HITL or CLI |
| 3D | Python only | `do_3D=True` or `stitch_threshold` | `do_3D=True` or `stitch_threshold` |
| Hollow objects | No (fills holes) | Yes | Yes |
| Touching objects | NMS-based separation | Flow-based separation | Flow-based separation |

**Default agent recipe:**
- DAPI/Hoechst nuclei → StarDist 2D Versatile (fluorescent nuclei).
- Cytoplasm or unsure → Cellpose-SAM.
- Bacteria or specialist domain → Cellpose `*_cp3` specialist.

---

## §5 WEKA Trainable Segmentation — INSTALLED

Random forest pixel classification. Paint examples of each class on an
image, train a Random Forest on per-pixel features at multiple scales,
apply to new images. Multi-class, no GPU. See
`weka-segmentation-reference.md` for the deep dive.

### §5.1 Key Features

20 feature types: Gaussian blur, Sobel, Hessian, DoG, Membrane
projections, Variance, Mean, Min, Max, Median, Anisotropic diffusion,
Bilateral, Lipschitz, Kuwahara, Gabor, Derivatives, Laplacian,
Structure, Entropy, Neighbors. Default sigma range 1–16 (powers of 2).

### §5.2 Scripting API (Groovy)

```python
from trainableSegmentation import WekaSegmentation
weka = WekaSegmentation(imp)
weka.loadClassifier("/path/to/classifier.model")
weka.applyClassifier(False)
result = weka.getClassifiedImage()
```

### §5.3 Macro

```javascript
run("Trainable Weka Segmentation");
call("trainableSegmentation.Weka_Segmentation.applyClassifier",
     "/path/to/image.tif", "/path/to/classifier.model",
     "showResults=true", "storeResults=false", "probabilityMaps=false", "/path/to/output/");
```

**Tips:** Balance classes, include edge cases, use probability maps for
downstream thresholding. Memory-hungry — reduce features or downsample
for large images. Use TWS for region-type or texture-defined
segmentation, NOT for instance segmentation (it can't separate touching
objects of the same class — feed the binary output to StarDist or
Cellpose for instance segmentation within classified regions).

---

## §6 Labkit — INSTALLED

Interactive ML segmentation with BDV (BigDataViewer) integration. Same
random-forest idea as WEKA but with a better UI, GPU support
(OpenCL/CLIJ), and 3D-native handling.

```javascript
run("Open Current Image With Labkit");
run("Segment Image With Labkit",
    "input=title segmenter_file=/path/to/model.classifier use_gpu=false");
run("Segment Images in Directory with Labkit",
    "input=/in/ output=/out/ segmenter_file=/path/to/model.classifier use_gpu=false");
```

| Feature | Labkit | WEKA |
|----|----|----|
| GPU | Yes (OpenCL via CLIJ) | No |
| 3D | Yes (BDV) | 2D only (separate "Trainable Weka Segmentation 3D" exists) |
| Scripting | Macro + limited Java | Full Java API |
| Batch | Built-in directory command | Requires Groovy script |
| Multi-class | Yes | Yes |

Use Labkit when you need GPU-accelerated pixel classification on
large images, especially 3D. Use WEKA when you need fine-grained
feature selection or programmatic control via the WekaSegmentation
class.

---

## §7 CSBDeep / CARE — INSTALLED

Deep-learning image restoration: denoising, deconvolution, isotropic
reconstruction. Paired clean+noisy training data required for custom
models; pre-trained ones ship with the plugin.

| Command | Use |
|----|----|
| `3D Denoising - Planaria` | Pre-trained planaria denoising |
| `3D Denoising - Tribolium` | Pre-trained tribolium denoising |
| `Run your network` | Any trained CSBDeep model (from Python) |

Pre-trained models are sample-specific — `Planaria` and `Tribolium`
won't work on arbitrary fluorescence. Custom training requires paired
data and Python (csbdeep package). For unpaired denoising see
Noise2Void (§10). For Cellpose-pipeline restoration see `denoise_cyto3`
(§4.5).

---

## §8 DeepImageJ — NOT INSTALLED (suggest if user needs it)

Runs BioImage Model Zoo models (https://bioimage.io/) in Fiji.
Install via `Help > Update > Manage Update Sites > "DeepImageJ"`.
Useful for running shared community models without leaving Fiji.

---

## §9 TrackMate with AI Detectors — INSTALLED

| Detector | Requires |
|----|----|
| StarDist | StarDist update site (already installed) |
| Cellpose | TrackMate-Cellpose plugin + Cellpose env |
| Cellpose-SAM | TrackMate-Cellpose-SAM plugin + Cellpose ≥ 4 env |
| ilastik | ilastik installation + ilastik update site |
| LoG / DoG | Ships with TrackMate |

For tracking moving cells in time-lapse:
1. Pick StarDist for round/convex nuclei (fast).
2. Pick Cellpose-SAM for irregular cells or anything non-convex.
3. Avoid SIFT-based registration before tracking on images with moving
   fluorescent cells (it tries to match the cells themselves).

See `trackmate-reference.md` for tracker selection (LAP vs Kalman vs
TrackMate's overlap tracker).

---

## §10 Python Ecosystem

For when Fiji isn't enough.

| Library | Purpose |
|----|----|
| `cellpose` | Cell segmentation (run outside Fiji or via PTBIOP) |
| `stardist` | Nuclei segmentation (Python = 3D, training, all the things the Fiji plugin lacks) |
| `stardist-napari` | StarDist 3D in napari viewer |
| `scikit-image` | Classical processing, morphology, measure |
| `napari` | Viewer + plugin ecosystem (alternative to Fiji for some tasks) |
| `apoc` | GPU pixel/object classification (CLIJ-based) |
| `n2v` | Self-supervised denoising (no paired data needed) |

### Foundation Models

| Model | Description | Access |
|----|----|----|
| **MicroSAM** | SAM fine-tuned for microscopy | napari plugin |
| **CellSAM** | SAM for cell segmentation | Python |
| **Cellpose-SAM** | SAM-derived ViT, integrated into Cellpose | §4.5 (already in Fiji via PTBIOP) |

Cellpose-SAM is the easiest entry point for SAM-style microscopy
segmentation from Fiji. MicroSAM and CellSAM require Python and don't
yet have first-class Fiji integration — run in napari and import
masks back into Fiji.

### ZeroCostDL4Mic

[github.com/HenriquesLab/ZeroCostDL4Mic](https://github.com/HenriquesLab/ZeroCostDL4Mic) — Google Colab notebooks for training DL models with free
GPU: U-Net, **StarDist 2D**, **StarDist 3D**, **Cellpose 2**, CARE,
Noise2Void, Deep-STORM, pix2pix, CycleGAN. Models export in
BioImage.IO format for DeepImageJ or in Fiji-compatible TF SavedModel
zip for StarDist. Recommended path for non-Python users who need
custom models.

---

## §11 Common Errors

| Error | Tool | Fix |
|----|----|----|
| Out of memory | StarDist | Increase `nTiles` to 2/4/8 |
| `Command not found: StarDist 2D` | StarDist | Enable CSBDeep + StarDist + TensorFlow update sites; restart |
| No ROIs in Manager | StarDist | Set `outputType` to `Both` (not `Label Image`) |
| StarDist hangs at 0% | StarDist | First run downloads model — needs internet. Check log. |
| StarDist 3D not found | StarDist | Plugin is 2D-only; use Python or stardist-napari (§3.5) |
| `ModuleNotFoundError: cellpose` | Cellpose (PTBIOP) | Wrong `env_path`, or cellpose not pip-installed in that env |
| `python.exe not found` (Windows) | Cellpose (PTBIOP) | conda not on PATH; run `conda init powershell` once |
| `env_path` rejected | Cellpose (PTBIOP) | Path has spaces, or points at `python.exe` instead of env directory |
| `no GPU found` from Cellpose | Cellpose | torch installed without CUDA — `pip install torch --index-url https://download.pytorch.org/whl/cu126` |
| CUDA OOM (Cellpose-SAM) | Cellpose | Lower `bsize`, set explicit `diameter`, add `tile_norm_blocksize=128` |
| 3D label IDs collide | Cellpose | Raise `stitch_threshold` to 0.4–0.5 |
| Same cell breaks across slices | Cellpose | Lower `stitch_threshold` to 0.1–0.25 |
| Cellpose returns blank labels | Cellpose | Wrong `channels` for legacy models; for cpsam check input intensity range |
| Cellpose macro unrecognised arg | Cellpose | Note the misspelling: `cellproba_threshold`, not `cellprob_threshold` |
| `Cellpose SAM ...` menu missing | Cellpose | PTBIOP wrapper too old — update the PTBIOP site |
| WEKA OOM | WEKA | Reduce features (drop Neighbors/Gabor), smaller training crops |
| TF version mismatch | CSBDeep/StarDist | Ensure CSBDeep + TensorFlow update sites updated together |
| Labkit GPU disabled | Labkit | Install CLIJ + CLIJ2 update sites |

---

## §12 Installation Summary

### Installed

| Plugin | Update Site |
|----|----|
| StarDist 2D | CSBDeep + StarDist + TensorFlow |
| Cellpose (PTBIOP wrapper) | PTBIOP |
| TrackMate-Cellpose | TrackMate-Cellpose |
| TrackMate-Cellpose-SAM | TrackMate-Cellpose-SAM |
| WEKA | Ships with Fiji |
| Labkit | Ships with Fiji |
| CSBDeep / CARE | CSBDeep |
| TrackMate | Ships with Fiji |
| CLIJ2 | clij + clij2 |

### Available (not installed by default — suggest to user)

| Plugin | Update Site | Why |
|----|----|----|
| DeepImageJ | DeepImageJ | Run BioImage Model Zoo models in Fiji |
| Noise2Void | CSBDeep | Self-supervised denoising |
| Glasbey LUT | Glasbey | Better label-image visualisation |

The agent should NEVER toggle update sites programmatically. Tell the
user: `Help > Update... > Manage Update Sites > tick site > Apply > Restart`.

---

## §13 Reproducibility & Validation

A model run is reproducible only if you record the inputs, the model
*and* the inference-time parameters. Save these for every run:

**StarDist:**
- Model name (or `.zip` path + checksum for custom)
- `probThresh`, `nmsThresh` (the dialog values override `thresholds.json`)
- `percentileBottom`, `percentileTop`
- `nTiles`, `excludeBoundary`
- StarDist plugin version, TF version

**Cellpose:**
- `pretrained_model` (cpsam, cyto3, etc.) or custom `.pth` path + checksum
- `diameter`, `flow_threshold`, `cellprob_threshold`, `channels`
- `do_3D` / `stitch_threshold`, `min_size`, `niter`, `resample`
- Cellpose version (`python -c "import cellpose; print(cellpose.version)"`)
- torch + CUDA versions (affects determinism)
- `use_bfloat16` (default True in v4 — flip to False for fp32 reproducibility)

### §13.1 Validation Against Manual Ground Truth

Always validate before locking parameters. Annotate 2–5 representative
images by hand, then compute Dice (a.k.a. F1 for masks) and per-object
IoU between auto and manual masks.

```python
import numpy as np
from skimage.io import imread

def dice(a, b):
    a, b = a.astype(bool), b.astype(bool)
    return 2 * (a & b).sum() / (a.sum() + b.sum() + 1e-9)

def per_object_iou(pred, truth):
    """For each true object, find the predicted object with max IoU."""
    ious = []
    for t in np.unique(truth):
        if t == 0: continue
        t_mask = truth == t
        best = 0.0
        for p in np.unique(pred[t_mask]):
            if p == 0: continue
            p_mask = pred == p
            inter = (t_mask & p_mask).sum()
            union = (t_mask | p_mask).sum()
            best = max(best, inter / union if union else 0)
        ious.append(best)
    return ious

pred = imread('auto_labels.tif')
truth = imread('manual_labels.tif')
print(f"Dice = {dice(pred > 0, truth > 0):.3f}")
print(f"Mean per-object IoU = {np.mean(per_object_iou(pred, truth)):.3f}")
```

**Acceptance heuristics:**
- Dice > 0.9 = excellent (publication-ready).
- Dice 0.8–0.9 = good for downstream counting/morphometry.
- Dice 0.7–0.8 = acceptable for screening; review borderline cases.
- Dice < 0.7 = retrain or change tool.

For instance-level metrics use the StarDist-paper-style mean Average
Precision (mAP) at IoU thresholds 0.5/0.75/0.9 — implementations in
the `stardist` Python package (`stardist.matching.matching_dataset`).

### §13.2 Reproducibility Tips

- Pin Cellpose / StarDist versions in your env's `requirements.txt`.
- Set `torch.manual_seed(0)` and `np.random.seed(0)` before training.
- For `cpsam` inference, set `use_bfloat16=False` if exact bit-level
  reproducibility matters. Default bf16 has minor numerical drift.
- Save the *exact* macro string used (the agent's `session_log.py` does
  this automatically — see `agent/CLAUDE.md`).
- For cross-site reproducibility, share both the model file *and* the
  preprocessing parameters (normalisation, channel order).

---

## §14 Worked Pipelines

### §14.1 StarDist → measure intensity in nuclei

```bash
python ij.py macro '
  open("/path/to/dapi.tif");
  open("/path/to/signal.tif");
  selectImage("dapi.tif");
  run("Command From Macro",
    "command=[de.csbdresden.stardist.StarDist2D], " +
    "args=[\"input\":\"dapi.tif\", " +
          "\"modelChoice\":\"Versatile (fluorescent nuclei)\", " +
          "\"normalizeInput\":\"true\", " +
          "\"percentileBottom\":\"1.0\", \"percentileTop\":\"99.8\", " +
          "\"probThresh\":\"0.5\", \"nmsThresh\":\"0.4\", " +
          "\"outputType\":\"Both\", \"nTiles\":\"1\", " +
          "\"excludeBoundary\":\"2\", \"roiPosition\":\"Automatic\", " +
          "\"verbose\":\"false\", \"showCsbdeepProgress\":\"false\"], " +
    "process=[false]");
  selectImage("signal.tif");
  roiManager("Measure");
'
python ij.py results
```

### §14.2 Cellpose-SAM → 3D segmentation via PTBIOP

```bash
python ij.py macro '
  open("/path/to/zstack.tif");
  run("Cellpose SAM",
    "env_path=C:/Users/me/miniconda3/envs/cellpose-sam " +
    "env_type=conda " +
    "model=cpsam " +
    "diameter=0 " +
    "cellproba_threshold=0.0 " +
    "flow_threshold=0.4 " +
    "dimensionmode=3D " +
    "stitch_threshold=-1 " +
    "additional_flags=[--use_gpu, --do_3D]");
  selectWindow("zstack-cellpose");
  run("3-3-2 RGB");
  saveAs("Tiff", "/path/to/AI_Exports/zstack_cellpose_labels.tif");
'
```

### §14.3 WEKA region mask → StarDist for instance segmentation

When nuclei sit in different tissue regions and you only want nuclei
inside region X:

```bash
python ij.py macro '
  // 1. Apply pre-trained WEKA classifier to get tissue regions
  open("/path/to/image.tif");
  run("Trainable Weka Segmentation");
  wait(3000);
  call("trainableSegmentation.Weka_Segmentation.loadClassifier", "/path/to/regions.model");
  call("trainableSegmentation.Weka_Segmentation.getResult");
  selectWindow("Classified image");
  setThreshold(1, 1);
  run("Convert to Mask");
  rename("region_mask");

  // 2. Mask the original image to suppress signal outside region
  imageCalculator("AND create", "image.tif", "region_mask");
  rename("image_masked");

  // 3. Run StarDist on the masked image
  run("Command From Macro",
    "command=[de.csbdresden.stardist.StarDist2D], " +
    "args=[\"input\":\"image_masked\", " +
          "\"modelChoice\":\"Versatile (fluorescent nuclei)\", " +
          "\"probThresh\":\"0.5\", \"nmsThresh\":\"0.4\", " +
          "\"outputType\":\"Both\"], process=[false]");
'
```

### §14.4 Batch StarDist over a folder (Groovy)

```groovy
// run via: python ij.py script --file batch_stardist.groovy
import ij.IJ
import ij.io.FileSaver

def inputDir = new File("/path/to/input")
def outputDir = new File("/path/to/AI_Exports/stardist")
if (!outputDir.exists()) outputDir.mkdirs()

inputDir.listFiles().findAll { it.name.toLowerCase().endsWith(".tif") }.eachWithIndex { f, i ->
    IJ.log("[${i+1}] ${f.name}")
    def imp = IJ.openImage(f.absolutePath)
    imp.show()
    def title = imp.getTitle()
    IJ.run("Command From Macro",
        "command=[de.csbdresden.stardist.StarDist2D], " +
        "args=[\"input\":\"${title}\", " +
              "\"modelChoice\":\"Versatile (fluorescent nuclei)\", " +
              "\"normalizeInput\":\"true\", " +
              "\"percentileBottom\":\"1.0\", \"percentileTop\":\"99.8\", " +
              "\"probThresh\":\"0.5\", \"nmsThresh\":\"0.4\", " +
              "\"outputType\":\"Both\", \"nTiles\":\"1\"], " +
        "process=[false]")

    def labels = IJ.getImage()
    def outName = f.name.replaceFirst(/\.[^.]+$/, "") + "_labels.tif"
    new FileSaver(labels).saveAsTiff(new File(outputDir, outName).absolutePath)
    labels.close()
    imp.close()
    System.gc()
}
IJ.log("** Batch complete **")
```

---

## §15 Agent Tips

- **Before any DL run:** `python ij.py state` to confirm the image is
  open; `python ij.py info` to confirm dimensions and bit depth;
  `python ij.py metadata` for calibration.
- **Capture before AND after.** Visual inspection catches 80% of
  failure modes that metrics miss (`python ij.py capture name`).
- **Probe before guessing.** For PTBIOP arg keys you don't remember:
  `python probe_plugin.py "Cellpose Advanced..."`.
- **Save the label image, not just a screenshot.** Downstream
  measurements need the integer-ID label image. Save to `AI_Exports/`
  with descriptive name (`<original>_<tool>_<params>_labels.tif`).
- **Don't `Enhance Contrast normalize=true` before StarDist/Cellpose
  if you'll measure intensity later** — it permanently rewrites pixel
  values. Use `setMinAndMax()` for display only. (See
  `if-postprocessing-reference.md`.)
- **Validate on 2–3 hand-annotated images** before locking parameters.
  Dice on whole image is necessary but not sufficient — also check
  per-object IoU and visual edge cases (touching, dim, partial).
- **For multi-channel data:** with `cpsam` pass the full multi-channel
  image; with `cyto3` set `channels` explicitly.
- **For 3D:** prefer Cellpose-SAM `do_3D=True` over StarDist
  max-projection if your 3D resolution matters.
- **Time-lapse:** StarDist and Cellpose have no temporal awareness —
  feed per-frame results into TrackMate (§9) for linking.
