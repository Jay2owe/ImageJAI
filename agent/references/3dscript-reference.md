# 3Dscript Animation Reference

Sources: [Wiki](https://github.com/bene51/3Dscript/wiki/The-animation-language),
[KeywordFactory.java](https://github.com/bene51/3Dscript/blob/master/3D_Animation/src/main/java/animation3d/renderer3d/KeywordFactory.java)

## Running from Agent

```bash
echo 'From frame 0 to frame 100 rotate by 360 degrees horizontally' > .tmp/rotate.animation.txt
python ij.py macro 'selectWindow("img"); run("Batch Animation", "animation=[/path/.tmp/rotate.animation.txt]");'
```

- **Batch Animation**: headless → AVI stack (101 frames, output size = input size)
- **Interactive Animation**: GUI + 3D canvas (linked pair — closing one breaks the other)
- Menu command: `run("Interactive Animation")` (NOT "Interactive Raycaster")
- Batch creates its own renderer — use animation text for ALL settings

---

## Syntax

```
At frame N [action]                           # instantaneous
At frame N:                                    # block
- [action1]
- [action2]
From frame A to frame B [action]              # transition
From frame A to frame B [action] ease-in-out  # with easing
```

**Easing**: `ease-in` (slow start), `ease-out` (slow end), `ease-in-out` (both).

---

## Camera / Transform

```
rotate by N degrees horizontally              # Y-axis (left-right)
rotate by N degrees vertically                # X-axis (tilt)
rotate by N degrees around (X,Y,Z)            # custom axis
zoom by a factor of N                         # N>1 in, N<1 out
translate horizontally by N                   # pixels
translate vertically by N
reset transformation
```

Negative degrees reverse direction. Multiple rotations compose simultaneously.

---

## Rendering Properties

### Per-Channel (use "channel N" or "all channels'")

| Property | Syntax | Range |
|----------|--------|-------|
| Lighting toggle | `change all channels' lighting to on` | on/off |
| Object light | `change channel N object light to 1.0` | 0.0-2.0+ |
| Diffuse | `change channel N diffuse to 0.5` | 0.0-1.0 |
| Specular | `change channel N specular to 0.3` | 0.0-1.0 |
| Shininess | `change channel N shininess to 15` | 1-100+ |
| Weight (visibility) | `change channel N weight to 0` | 0.0-1.0 |
| Intensity range | `change channel N intensity to (min, max)` | image range |
| Intensity gamma | `change channel N intensity gamma to 1.0` | 0.1-10+ |
| Alpha range | `change channel N alpha to (min, max)` | image range |
| Alpha gamma | `change channel N alpha gamma to 1.0` | 0.1-10+ |
| Color | `change channel N color to (R, G, B)` | 0-255 |
| Front clipping | `change channel N front clipping to 1000` | -5000 to 5000 |
| Bounding box | `change channel N bounding box x to (min, max)` | pixels |

**Front clipping**: large negative = show all, 0 = clip at z-origin, large positive = hidden.

### Global

| Property | Syntax |
|----------|--------|
| Bounding box | `change bounding box visibility to off` |
| Scalebar | `change scalebar visibility to off` |
| Background | `change background color to (0, 0, 0)` |
| Algorithm | `change rendering algorithm to combined transparency` |
| Timepoint | `change timepoint to N` |

**Rendering algorithms**: `independent transparency` (default), `combined transparency`, `maximum intensity projection`.

**Colors**: `red`, `green`, `blue`, `yellow`, `cyan`, `magenta`, `white`, or `(R,G,B)`.

---

## Animation Patterns

### 1. Simple Rotation

```
At frame 0:
- change all channels' lighting to on
- change all channels' object light to 1
- change bounding box visibility to off
- change scalebar visibility to off
From frame 0 to frame 100 rotate by 360 degrees horizontally
```

### 2. Tumble (gyroscope orbit)

Three simultaneous layers: horizontal spin + X-axis tilt oscillation + Z-axis roll oscillation.

```
At frame 0 rotate by -67.5 degrees vertically

From frame 0 to frame 1080 rotate by 1080 degrees horizontally

# X-axis tilt oscillation (180-frame half-period)
From frame 0 to frame 180 rotate by -45 degrees around (1,0,0) ease-out
From frame 180 to frame 360 rotate by 45 degrees around (1,0,0) ease-in-out
From frame 360 to frame 540 rotate by -45 degrees around (1,0,0) ease-in-out
# ... continue alternating

# Z-axis roll (90-frame phase offset)
From frame 0 to frame 90 rotate by 22.5 degrees around (0,0,1) ease-out
From frame 90 to frame 270 rotate by -45 degrees around (0,0,1) ease-in-out
# ... continue alternating
```

Key: X and Z oscillations are quarter-period out of phase (Lissajous orbit). For looping: TOTAL_FRAMES should be multiple of 360.

### 3. Zoom to Object

```
From frame 400 to frame 500:
- zoom by a factor of 5 ease-in-out
- translate horizontally by -350
- change all channels' bounding box min x to 710
- change all channels' bounding box max x to 840
```

### 4. Channel Reveal (hide/show)

```
# Hide via clipping
From frame 720 to frame 900:
- change channel 1 front clipping to 1000

# Or fade via weight
From frame 80 to frame 90:
- change channel 2 weight to 0
```

### 5. Z-Slice Peeling

```
At frame 0:
- change all channels' bounding box max z to 500
From frame 90 to frame 450:
- change all channels' bounding box max z to 0
```

### 6. Time-Lapse Sweep (4D)

```
From frame 0 to frame 560: change timepoint to 480
From frame 200 to frame 520: rotate by -360 degrees vertically
```

---

## Script Functions

Custom math for parametric animations:

```
From frame 0 to frame 360: rotate by rot degrees horizontally
script function rot(t) { return 30 * sin(2 * PI * t / 120); }
```

Available: `sin`, `cos`, `abs`, `PI`, arithmetic. Parameter `t` = current frame.

---

## Tuple Syntax

```
change channel 1 intensity to (130.0, 2000.0, 0.2)     # (min, max, gamma)
change channel 1 light to (1.0, 0.5, 0.3, 10.0)         # (object, diffuse, specular, shininess)
change channel 1 color to (255, 0, 0)                    # (R, G, B)
```

---

## Tips

- Batch Animation outputs 101 frames — frame numbers are proportional
- Scale XY before rendering (output size = input size)
- Avoid Z-interpolation (masked data falls below alpha threshold)
- 8-bit required
- Front clipping to large value (1000) fully hides a channel
- Bounding box tightening works as spatial crop
- Example scripts: `Macros and Scripts/3D Scripts/`
