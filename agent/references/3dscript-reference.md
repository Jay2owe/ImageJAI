# 3Dscript Animation Reference

Agent-oriented reference for 3Dscript, the animation language for Fiji's
Batch Animation / Interactive Animation renderers. Covers syntax, camera /
transform keywords, per-channel and global rendering properties, animation
patterns (rotation, tumble, zoom, reveal, Z-slice peel, 4D sweep), script
functions, tuple syntax, and gotchas.

Sources: [Wiki](https://github.com/bene51/3Dscript/wiki/The-animation-language),
[KeywordFactory.java](https://github.com/bene51/3Dscript/blob/master/3D_Animation/src/main/java/animation3d/renderer3d/KeywordFactory.java)

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code that writes an
animation text file and calls `run("Batch Animation", ...)`.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "How do I run 3Dscript from the agent?" | §2 |
| "What's the difference between Batch and Interactive Animation?" | §2 |
| "What's the syntax for a keyframe / transition / easing?" | §3 |
| "How do I rotate, zoom, or translate the view?" | §4 |
| "How do I change lighting / colour / alpha / intensity per channel?" | §5.1 |
| "How do I hide the bounding box, scalebar, or background?" | §5.2 |
| "What rendering algorithms are available?" | §5.2 |
| "How do I write a simple rotation animation?" | §6.1 |
| "How do I make a tumbling / gyroscope orbit?" | §6.2 |
| "How do I zoom into a region of interest?" | §6.3 |
| "How do I hide or fade a channel during the animation?" | §6.4 |
| "How do I peel away Z-slices over time?" | §6.5 |
| "How do I sweep through timepoints in a 4D stack?" | §6.6 |
| "How do I use a math expression (sin / cos) for motion?" | §7 |
| "What's the order inside a tuple like `intensity to (...)`?" | §8 |
| "Why does my animation only have 101 frames / look striped / hidden?" | §9 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each 3Dscript term. Use
`grep -n '`<term>`' 3dscript-reference.md` to jump.

### A
`alpha` §5.1 · `alpha gamma` §5.1 · `algorithm (rendering)` §5.2 · `all channels'` §5.1, §6.1 · `At frame N` §3, §6.1 · `AVI` §2

### B
`Batch Animation` §2, §9 · `background color` §5.2 · `bounding box` §5.1, §5.2, §6.3, §6.5 · `bounding box visibility` §5.2, §6.1 · `bounding box min/max x/y/z` §5.1, §6.3, §6.5

### C
`channel N` §5.1 · `change ...` §5.1, §5.2 · `clipping (front)` §5.1, §6.4, §9 · `color` §5.1, §5.2 · `colors (named)` §5.2 · `combined transparency` §5.2 · `cos` §7 · `custom axis rotate` §4

### D
`diffuse` §5.1 · `degrees` §4

### E
`ease-in` §3 · `ease-in-out` §3, §6.2, §6.3 · `ease-out` §3, §6.2

### F
`factor (zoom)` §4, §6.3 · `frame` §3 · `From frame A to frame B` §3, §6.1 · `front clipping` §5.1, §6.4, §9

### G
`gamma (alpha)` §5.1 · `gamma (intensity)` §5.1 · `global properties` §5.2

### H
`horizontally (rotate)` §4, §6.1 · `horizontally (translate)` §4, §6.3

### I
`independent transparency` §5.2 · `Interactive Animation` §2 · `intensity` §5.1, §8 · `intensity gamma` §5.1

### K
`KeywordFactory.java` (source) header

### L
`light (tuple)` §8 · `lighting` §5.1, §6.1 · `Lissajous orbit` §6.2 · `looping (multiple of 360)` §6.2

### M
`maximum intensity projection` §5.2

### O
`object light` §5.1, §6.1 · `on/off (lighting)` §5.1

### P
`PI` §7 · `pixels (translate)` §4 · `per-channel properties` §5.1

### R
`reset transformation` §4 · `rendering algorithm` §5.2 · `rotate by N degrees` §4, §6.1, §6.2 · `(R,G,B)` §5.1, §5.2, §8

### S
`scalebar visibility` §5.2, §6.1 · `script function` §7 · `shininess` §5.1, §8 · `simultaneous rotations` §4, §6.2 · `sin` §7 · `specular` §5.1, §8 · `syntax (block / transition / instant)` §3

### T
`t (script parameter)` §7 · `timepoint` §5.2, §6.6 · `translate horizontally/vertically` §4, §6.3 · `tumble` §6.2 · `tuple syntax` §8

### V
`vertically (rotate)` §4, §6.2, §6.6 · `vertically (translate)` §4

### W
`weight (visibility)` §5.1, §6.4

### X
`X-axis tilt` §4, §6.2

### Y
`Y-axis (horizontal rotate)` §4

### Z
`Z-axis roll` §6.2 · `zoom by a factor of N` §4, §6.3 · `Z-interpolation (avoid)` §9 · `Z-slice peeling` §6.5

---

## §2 Running from Agent

```bash
echo 'From frame 0 to frame 100 rotate by 360 degrees horizontally' > .tmp/rotate.animation.txt
python ij.py macro 'selectWindow("img"); run("Batch Animation", "animation=[/path/.tmp/rotate.animation.txt]");'
```

- **Batch Animation**: headless → AVI stack (101 frames, output size = input size)
- **Interactive Animation**: GUI + 3D canvas (linked pair — closing one breaks the other)
- Menu command: `run("Interactive Animation")` (NOT "Interactive Raycaster")
- Batch creates its own renderer — use animation text for ALL settings

---

## §3 Syntax

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

## §4 Camera / Transform

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

## §5 Rendering Properties

### §5.1 Per-Channel (use "channel N" or "all channels'")

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

### §5.2 Global

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

## §6 Animation Patterns

### §6.1 Simple Rotation

```
At frame 0:
- change all channels' lighting to on
- change all channels' object light to 1
- change bounding box visibility to off
- change scalebar visibility to off
From frame 0 to frame 100 rotate by 360 degrees horizontally
```

### §6.2 Tumble (gyroscope orbit)

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

### §6.3 Zoom to Object

```
From frame 400 to frame 500:
- zoom by a factor of 5 ease-in-out
- translate horizontally by -350
- change all channels' bounding box min x to 710
- change all channels' bounding box max x to 840
```

### §6.4 Channel Reveal (hide/show)

```
# Hide via clipping
From frame 720 to frame 900:
- change channel 1 front clipping to 1000

# Or fade via weight
From frame 80 to frame 90:
- change channel 2 weight to 0
```

### §6.5 Z-Slice Peeling

```
At frame 0:
- change all channels' bounding box max z to 500
From frame 90 to frame 450:
- change all channels' bounding box max z to 0
```

### §6.6 Time-Lapse Sweep (4D)

```
From frame 0 to frame 560: change timepoint to 480
From frame 200 to frame 520: rotate by -360 degrees vertically
```

---

## §7 Script Functions

Custom math for parametric animations:

```
From frame 0 to frame 360: rotate by rot degrees horizontally
script function rot(t) { return 30 * sin(2 * PI * t / 120); }
```

Available: `sin`, `cos`, `abs`, `PI`, arithmetic. Parameter `t` = current frame.

---

## §8 Tuple Syntax

```
change channel 1 intensity to (130.0, 2000.0, 0.2)     # (min, max, gamma)
change channel 1 light to (1.0, 0.5, 0.3, 10.0)         # (object, diffuse, specular, shininess)
change channel 1 color to (255, 0, 0)                    # (R, G, B)
```

---

## §9 Tips

- Batch Animation outputs 101 frames — frame numbers are proportional
- Scale XY before rendering (output size = input size)
- Avoid Z-interpolation (masked data falls below alpha threshold)
- 8-bit required
- Front clipping to large value (1000) fully hides a channel
- Bounding box tightening works as spatial crop
- Example scripts: `Macros and Scripts/3D Scripts/`
