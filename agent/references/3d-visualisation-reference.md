# 3D Visualisation & Animation — Reference

Sources: [3Dscript GitHub](https://github.com/bene51/3Dscript),
[3D Viewer Docs](https://imagej.net/plugins/3d-viewer/),
[KeywordFactory.java](https://github.com/bene51/3Dscript/blob/master/3D_Animation/src/main/java/animation3d/renderer3d/KeywordFactory.java)

---

## Quick Start

```bash
cat > .tmp/rotate.animation.txt << 'EOF'
At frame 0:
- change all channels' lighting to on
- change all channels' object light to 1
- change bounding box visibility to off
- change scalebar visibility to off
From frame 0 to frame 100 rotate by 360 degrees horizontally
EOF

python ij.py macro 'selectWindow("my_image"); run("8-bit");'
python ij.py macro 'run("Scale...", "x=10 y=10 z=1.0 interpolation=Bicubic process create title=big");'
python ij.py macro 'selectWindow("big"); run("Batch Animation", "animation=[C:/full/path/.tmp/rotate.animation.txt]");'
python ij.py macro 'run("AVI... ", "compression=None frame=30 save=[C:/path/output.avi]");'
ffmpeg -i output.avi -c:v libx264 -crf 18 -preset slow -pix_fmt yuv444p output.mp4
```

---

## Tool Comparison

| Tool | Type | Quality | Scriptable | GPU | Status |
|------|------|---------|------------|-----|--------|
| **3Dscript** | Volume raycasting | Best | Natural language | OpenCL | Active |
| **3D Viewer** | OpenGL (Java3D) | Good | Macro + Java API | Java3D | Legacy |
| **3D Project** | Built-in MIP | Basic | Full macro | No | Stable |
| **ClearVolume** | Real-time volume | Good | Limited | OpenCL | Low activity |
| **BigDataViewer** | Re-slicing browser | 2D slicing | Java/macro | No | Very active |
| **Volume Viewer** | Slice/volume | Good | Macro params | No | Mature |
| **sciview** | Vulkan/OpenGL | Excellent | Kotlin/Groovy | Vulkan | Beta |
| **napari** | Python 3D | Good | Python | OpenGL | Very active |

**When to use**: Publication video → 3Dscript or 3D Project (fallback). Interactive → 3D Viewer. Large data (TB+) → BDV. Python pipeline → napari. Reliable macro automation → 3D Project.

---

## Cell Isolation Pipeline (Prerequisite for All 3D Renders)

Isolate the target object before rendering — cropping alone is insufficient.

```bash
python pixels.py find_cells
python ij.py macro '
  run("Duplicate...", "title=seg duplicate");
  run("Gaussian Blur 3D...", "x=2 y=2 z=1");
  run("3D Objects Counter", "threshold=37000 min.=100 max.=999999 objects");
'
# Find label at target (X,Y), create 0/1 mask, then:
python ij.py macro '
  imageCalculator("Multiply create stack", "ORIGINAL_TITLE", "mask");
  rename("isolated");
  makeRectangle(X, Y, W, H); run("Crop");
'
```

**Gotchas:**
- Use **Multiply not AND** for masking 16-bit with binary masks (AND corrupts through 8-bit)
- Consider avoiding Enhance Contrast — raw intensity is the data
- 8-bit required for all renderers
- Scale XY before 3Dscript (output size = input size)
- Avoid Z-interpolation for 3Dscript (interpolated masked data falls below alpha threshold)

---

## 1. 3Dscript

### Entry Points

| Command | What | Use case |
|---------|------|----------|
| `run("Interactive Animation")` | GUI + 3D canvas (linked pair) | Preview |
| `run("Batch Animation", "animation=[/path/to/file.txt]")` | Headless → AVI stack | Automated |

"Interactive Raycaster" is NOT a valid command. The dialog and canvas are a linked pair — closing one breaks the other. Batch Animation creates its own renderer and does not inherit Interactive settings.

### Animation Language

See **`3dscript-reference.md`** for the complete language reference (keywords, properties, easing, patterns).

### Agent Workflow

```bash
python ij.py macro 'selectWindow("isolated"); run("8-bit");'
python ij.py macro 'run("Scale...", "x=10 y=10 z=1.0 interpolation=Bicubic process create title=cell_big");'
# Write animation script to .tmp/rotate.animation.txt
python ij.py macro 'selectWindow("cell_big"); run("Batch Animation", "animation=[C:/full/path/.tmp/rotate.animation.txt]");'
python ij.py macro 'run("AVI... ", "compression=None frame=30 save=[C:/path/to/output.avi]");'
```

### Toggling UI Checkboxes via Groovy

```groovy
import javax.swing.JCheckBox; import java.awt.Window; import java.awt.Container
def walk(Container c) {
    c.getComponents().each { x ->
        if (x instanceof JCheckBox) {
            if (x.getText()?.contains('Bounding') && x.isSelected()) x.doClick()
            if (x.getText()?.contains('light') && !x.isSelected()) x.doClick()
        }
        if (x instanceof Container) walk(x)
    }
}
Window.getWindows().findAll { it.class.name.contains('animation3d') }.each { walk(it) }
```

---

## 2. 3D Viewer (ij3d / Java3D)

### Rendering Modes

| Mode | Value | Description |
|------|-------|-------------|
| Volume | 0 | Texture-based volume (addVoltex) |
| Orthoslice | 1 | Three orthogonal planes |
| Surface | 2 | Isosurface mesh (addMesh) |
| Surface Plot | 3 | Height map from single slice |

### Agent TCP API (preferred)

```bash
python ij.py 3d status                    # check if open
python ij.py 3d add IMAGE_TITLE volume 50 # add volume
python ij.py 3d list                      # list content
python ij.py 3d fit                       # fit view
python ij.py 3d capture output_name       # screenshot → .tmp/
python ij.py 3d close
```

Prefer `3d capture` over `3d snapshot` (snapshot has a sizing bug).

### Macro API (call() syntax)

```java
call("ij3d.ImageJ3DViewer.add", "image", "None", "name", "50", "true", "true", "true", "2", "0");
call("ij3d.ImageJ3DViewer.resetView");
call("ij3d.ImageJ3DViewer.select", "name");
call("ij3d.ImageJ3DViewer.setColor", "255", "0", "0");
call("ij3d.ImageJ3DViewer.setTransparency", "0.5");
call("ij3d.ImageJ3DViewer.snapshot", "1024", "1024");
call("ij3d.ImageJ3DViewer.record360");
call("ij3d.ImageJ3DViewer.exportContent", "STL", "/path/to/output.stl");
```

**TCP limitations:** No rotation/transform control, no remove action, no post-add lighting/color.

---

## 3. 3D Project (Built-in)

```java
run("3D Project...",
    "projection=[Brightest Point] axis=Y-Axis slice=0.50
     initial=0 total=360 rotation=10 lower=1 upper=255
     opacity=0 surface=100 interior=50 interpolate");
```

| Parameter | Values | Description |
|-----------|--------|-------------|
| `projection` | `[Brightest Point]`, `[Nearest Point]`, `[Mean Value]` | Method |
| `axis` | `X-Axis`, `Y-Axis`, `Z-Axis` | Rotation axis |
| `total` / `rotation` | degrees | Total rotation / per frame |
| `lower` / `upper` | 0-255 | Transparency thresholds |
| `opacity` | 0-100 | 0=pure projection, 100=nearest blend |

Frame count = total / rotation. Limitations: not true volume rendering, no multi-channel, Z-striping at side views.

---

## 4. Other Tools

| Tool | Notes |
|------|-------|
| **ClearVolume** | GPU real-time volume. NOT installed. |
| **BigDataViewer** | Re-slicing for TB+ datasets. Backbone for BigStitcher, BigWarp, Labkit, ABBA. v10.6.4 installed. |
| **Volume Viewer** | `run("Volume Viewer", "display_mode=4 interpolation=2 scale=1.5 angle_x=30 angle_y=45");` |
| **sciview** | Modern Vulkan. NOT installed. Update site: "SciView". |
| **napari** | Python 3D viewer via napari-imagej bridge. |

---

## 5. Video Export

### AVI + FFmpeg

```java
run("AVI... ", "compression=None frame=30 save=[/path/to/output.avi]");
```

```bash
ffmpeg -i input.avi -c:v libx264 -crf 18 -preset slow -pix_fmt yuv444p output.mp4  # publication
ffmpeg -i input.avi -c:v libx264 -crf 23 -preset slow -pix_fmt yuv420p output.mp4  # web
ffmpeg -i input.avi -vf "fps=15,scale=512:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse" output.gif
```

| CRF | Quality | Use |
|-----|---------|-----|
| 0 | Lossless | Archival |
| 15-18 | Visually lossless | Publication |
| 23-28 | Good | Web/presentation |

Journal requirements: MP4 H.264, 720p+, 24-30fps, `yuv444p` for colour accuracy.

---

## 3D Rendering Gotchas

1. **Voxel anisotropy** — confocal Z-steps typically 3-5x coarser than XY. Set calibration before rendering.
2. **MIP misinterpretation** — 3D Project creates discontinuous layers, not true volume.
3. **Z-interpolation** — bicubic on masked data (0-signal-0) produces dim slices below alpha threshold.
4. **AND corruption** — `AND` on 16-bit with 8-bit mask keeps only low byte. Use Multiply with 0/1 mask.
5. **Confocal bleed-through** — residual adjacent-channel signal creates halos.

---

## Decision Tree

```
Need 3D visualisation?
├── Video/animation?
│   ├── Best quality → 3Dscript (Batch Animation)
│   ├── Reliable fallback → 3D Project
│   └── Interactive then record → 3D Viewer (record360)
├── Interactive exploration?
│   ├── Fiji → 3D Viewer or Volume Viewer
│   ├── Python → napari
│   └── Live microscope → ClearVolume
├── TB+ dataset? → BigDataViewer / BigVolumeViewer
└── Programmatic?
    ├── Macro → 3D Project or 3D Viewer call()
    ├── Animation script → 3Dscript
    └── Python → napari
```
