# 3Dscript Animation Reference

Complete reference for 3Dscript animation scripting in ImageJ/Fiji.
Built from API inspection, user example scripts, and official documentation.

Sources: [3Dscript Wiki](https://github.com/bene51/3Dscript/wiki/The-animation-language),
[Gallery](https://bene51.github.io/3Dscript/gallery.html),
[KeywordFactory.java](https://github.com/bene51/3Dscript/blob/master/3D_Animation/src/main/java/animation3d/renderer3d/KeywordFactory.java)

## Quick Start

```
# Simple Y-axis rotation with lighting and no bounding box
At frame 0:
- change all channels' lighting to on
- change all channels' object light to 1
- change bounding box visibility to off
- change scalebar visibility to off
From frame 0 to frame 100 rotate by 360 degrees vertically
```

## Running from Agent

```bash
# Write animation file then run Batch Animation
echo 'From frame 0 to frame 100 rotate by 360 degrees horizontally' > .tmp/rotate.animation.txt
python ij.py macro 'selectWindow("my_image"); run("Batch Animation", "animation=[/path/to/.tmp/rotate.animation.txt]");'
```

- **Batch Animation** renders headless → outputs `.avi` stack window
- **Interactive Animation** opens GUI + 3D canvas (linked pair — closing one breaks the other)
- Menu command: `run("Interactive Animation");` (NOT "Interactive Raycaster")
- Batch Animation creates its OWN renderer — use animation text for ALL settings

---

## Syntax

### Frame Specification

```
At frame N [action]                           # instantaneous at frame N

At frame N:                                    # multi-line block at frame N
- [action1]
- [action2]

From frame A to frame B [action]              # transition over frames A→B
From frame A to frame B [action] ease-in      # with easing
From frame A to frame B [action] ease-out
From frame A to frame B [action] ease-in-out

From frame A to frame B:                       # multi-line transition
- [action1]
- [action2] ease-in-out
```

---

## Camera / Transform

### Rotation

```
rotate by N degrees horizontally              # X-axis (left-right spin)
rotate by N degrees vertically                # Y-axis (up-down tilt)
rotate by N degrees around (X,Y,Z)            # custom axis rotation
```

Negative values reverse direction. Multiple rotations compose (run simultaneously).

**Tumble pattern** (oscillating tilt while spinning):
```
# Continuous horizontal spin
From frame 0 to frame 1080 rotate by 1080 degrees horizontally

# Overlaid tilt oscillation around X-axis
From frame 0 to frame 180 rotate by -45 degrees around (1,0,0) ease-out
From frame 180 to frame 360 rotate by 45 degrees around (1,0,0) ease-in-out
From frame 360 to frame 540 rotate by -45 degrees around (1,0,0) ease-in-out
# ... repeat pattern

# Overlaid roll oscillation around Z-axis
From frame 0 to frame 90 rotate by 22.5 degrees around (0,0,1) ease-out
From frame 90 to frame 270 rotate by -45 degrees around (0,0,1) ease-in-out
# ... repeat pattern
```

**Initial orientation** (set before animation):
```
At frame 0 rotate by -67.5 degrees vertically    # common starting tilt
```

### Zoom

```
zoom by a factor of N                           # N > 1 zooms in, N < 1 zooms out
zoom by a factor of N ease-in-out               # with easing
```

### Translation

```
translate horizontally by N                     # pixels, negative = left
translate vertically by N                       # pixels, negative = up
```

---

## Rendering Properties

### Lighting

```
change all channels' lighting to on             # enable shading/depth
change all channels' lighting to off
change all channels' object light to 1.0        # light intensity (0.0-2.0+)
change channel N object light to 0.8            # per-channel intensity
```

Available light properties (per channel):
| Property | Description | Range |
|----------|-------------|-------|
| `lighting` / `use light` | Enable/disable lighting | on/off |
| `object light` | Object light intensity | 0.0 - 2.0+ |
| `diffuse` | Diffuse light weight | 0.0 - 1.0 |
| `specular` | Specular light weight | 0.0 - 1.0 |
| `shininess` | Specular shininess | 1.0 - 100+ |

### Bounding Box

```
change bounding box visibility to off           # hide wireframe
change bounding box visibility to on
```

Bounding box position (per channel or all):
```
change all channels' bounding box x to (0, 1024)
change all channels' bounding box y to (0, 1024)
change all channels' bounding box z to (0, 13)
change all channels' bounding box min x to 710
change all channels' bounding box max x to 840
change channel 3 bounding box x to (759, 783)
change channel 3 bounding box y to (496, 515)
```

### Scalebar

```
change scalebar visibility to off
change scalebar visibility to on
```

### Clipping (Front/Back)

Clip channels to show/hide parts of the volume:
```
change all channels' front clipping to -725     # show all (large negative)
change channel 1 front clipping to 0            # clip at z=0
change channel 1 front clipping to 1000         # clip away (hide channel)
change channel 2 front clipping to 1000         # hide channel 2
```

Use this to progressively reveal or hide channels during animation.

### Channel Weight (Visibility)

```
change all channels' weight to 1                # full visibility
change channel 4 weight to 0.5                  # dim channel 4
change channel 4 weight to 0                    # hide channel 4
```

### Intensity / Alpha

Per-channel contrast and transparency:
```
change channel N intensity to (min, max)
change channel N alpha to (min, max)
change channel N intensity gamma to 1.0
change channel N alpha gamma to 1.0
```

### Background Color

```
change background color to (R, G, B)            # 0-255 per component
```

### Rendering Algorithm

```
change rendering algorithm to Combined transparency
change rendering algorithm to Independent transparency
```

---

## All Available Keywords

### Channel Keywords (per-channel, use "channel N" or "all channels'")

| Keyword | Animation text | Description |
|---------|---------------|-------------|
| INTENSITY | intensity | Intensity display range |
| INTENSITY_MIN | intensity min | Min intensity |
| INTENSITY_MAX | intensity max | Max intensity |
| INTENSITY_GAMMA | intensity gamma | Intensity gamma |
| ALPHA | alpha | Alpha/transparency |
| ALPHA_MIN | alpha min | Min alpha |
| ALPHA_MAX | alpha max | Max alpha |
| ALPHA_GAMMA | alpha gamma | Alpha gamma |
| WEIGHT | weight | Channel visibility weight |
| COLOR | color | Channel color |
| USE_LIGHT | lighting / use light | Enable lighting |
| LIGHT | object light | Object light intensity |
| OBJECT_LIGHT_WEIGHT | object light | Object light weight |
| DIFFUSE_LIGHT_WEIGHT | diffuse | Diffuse light |
| SPECULAR_LIGHT_WEIGHT | specular | Specular light |
| SHININESS | shininess | Specular shininess |
| FRONT_CLIPPING | front clipping | Front clip plane |
| BACK_CLIPPING | back clipping | Back clip plane |
| BOUNDING_BOX_X | bounding box x | Bounding box X range |
| BOUNDING_BOX_Y | bounding box y | Bounding box Y range |
| BOUNDING_BOX_Z | bounding box z | Bounding box Z range |
| IMAGE_LUT | LUT | Lookup table |

### Non-Channel Keywords (global)

| Keyword | Animation text | Description |
|---------|---------------|-------------|
| BOUNDINGBOX_VISIBILITY | bounding box visibility | Show/hide wireframe |
| BOUNDINGBOX_COLOR | bounding box color | Wireframe color |
| BOUNDINGBOX_WIDTH | bounding box width | Wireframe line width |
| SCALEBAR_VISIBILITY | scalebar visibility | Show/hide scalebar |
| SCALEBAR_COLOR | scalebar color | Scalebar color |
| SCALEBAR_LENGTH | scalebar length | Scalebar length |
| SCALEBAR_WIDTH | scalebar width | Scalebar line width |
| SCALEBAR_POSITION | scalebar position | Scalebar position |
| SCALEBAR_OFFSET | scalebar offset | Scalebar offset |
| BG_COLOR | background color | Background color |
| RENDERING_ALGORITHM | rendering algorithm | Transparency mode |
| TIMEPOINT | timepoint | For time-lapse data |

---

## Easing Functions

```
ease-in          # slow start, fast end
ease-out         # fast start, slow end
ease-in-out      # slow start and end
ease             # alias for ease-in-out (?)
```

Apply to any `From frame A to frame B` command.

---

## Common Animation Patterns

### 1. Simple Rotation (publication figure)

```
At frame 0:
- change all channels' lighting to on
- change all channels' object light to 1
- change bounding box visibility to off
- change scalebar visibility to off
From frame 0 to frame 100 rotate by 360 degrees horizontally
```

### 2. Tumble (orbiting view like a gyroscope)

```
At frame 0 rotate by -67.5 degrees vertically

# Main horizontal spin
From frame 0 to frame 1080 rotate by 1080 degrees horizontally

# X-axis oscillation (tilt)
From frame 0 to frame 180 rotate by -45 degrees around (1,0,0) ease-out
From frame 180 to frame 360 rotate by 45 degrees around (1,0,0) ease-in-out
From frame 360 to frame 540 rotate by -45 degrees around (1,0,0) ease-in-out
From frame 540 to frame 720 rotate by 45 degrees around (1,0,0) ease-in-out

# Z-axis oscillation (roll)
From frame 0 to frame 90 rotate by 22.5 degrees around (0,0,1) ease-out
From frame 90 to frame 270 rotate by -45 degrees around (0,0,1) ease-in-out
From frame 270 to frame 450 rotate by 45 degrees around (0,0,1) ease-in-out
```

### 3. Zoom to Object

```
From frame 400 to frame 500:
- zoom by a factor of 5 ease-in-out
- translate horizontally by -350
- translate vertically by 50
```

### 4. Progressive Channel Reveal (hide DAPI, show marker)

```
# Start with all channels
At frame 0:
- change channel 1 front clipping to 0
# Gradually clip away channel 1 (DAPI)
At frame 720:
- change channel 1 front clipping to 100
# Fully hide channel 1
From frame 800 to frame 1030:
- change channel 1 front clipping to 1000
```

### 5. Spotlight on Single Object (zoom + crop bounding box)

```
# Fly into specific object by tightening bounding box
From frame 1300 to frame 1400:
- change all channels' bounding box min x to 710
- change all channels' bounding box max x to 840
- change all channels' bounding box min y to 455
- change all channels' bounding box max y to 580
- zoom by a factor of 5 ease-in-out
- translate horizontally by -350
```

### 6. Per-Channel Lighting (highlight specific stain)

```
At frame 0:
- change all channels' lighting to on
- change all channels' object light to 1
- change channel 1 object light to 0.8     # dim DAPI slightly
- change channel 3 object light to 1.5     # brighten marker
- change channel 4 object light to 0.3     # dim background channel
```

---

## Script Functions (Parametric Animations)

Use custom math functions anywhere a numeric value is expected:

```
From frame 0 to frame 360: rotate by rot degrees horizontally

script function rot(t) { return 30 * sin(2 * PI * t / 120); }
```

Available math: `sin`, `cos`, `abs`, `PI`, standard arithmetic.
Parameter `t` receives the current frame number.

**Oscillating zoom:**
```
From frame 0 to frame 100: zoom by a factor of z
script function z(t) { return 0.75 + 0.25 * cos(2 * PI * t / 100); }
```

**Pulsing translation:**
```
From frame 0 to frame 100: translate horizontally by tr
script function tr(t) { return 128 * cos(2 * PI * (t+25) / 100); }
```

---

## Compound Tuple Syntax

Several properties accept tuple shorthand to set multiple values at once:

```
change channel 1 intensity to (130.0, 2000.0, 0.2)     # (min, max, gamma)
change channel 1 alpha to (130.0, 2000.0, 0.2)          # (min, max, gamma)
change channel 1 light to (1.0, 0.5, 0.3, 10.0)         # (object, diffuse, specular, shininess)
change channel 1 color to (255, 0, 0)                    # (R, G, B)
change all channels' front/back clipping to (-5000, 5000)
```

Predefined color names: `red`, `green`, `blue`, `yellow`, `cyan`, `magenta`, `white`

---

## Additional Transform Commands

```
reset transformation                    # restore default position/rotation/zoom
translate by (X, Y, Z)                  # 3D translation
change lens to (p1, p2, p3)            # lens distortion/perspective
```

---

## Time-Lapse (4D) Data

```
change timepoint to N                   # set which timepoint to display
```

Example — sweep through timepoints while rotating:
```
At frame 0:
- change timepoint to 0
- reset transformation
From frame 0 to frame 560: change timepoint to 480
From frame 200 to frame 520: rotate by -360 degrees vertically
```

---

## Rendering Algorithms

```
change rendering algorithm to independent transparency    # default — channels independent
change rendering algorithm to combined transparency       # channels affect each other's opacity
change rendering algorithm to maximum intensity projection # MIP — no depth, fastest
```

---

## Advanced Examples

### Channel Switching (fade between channels)
```
From frame 80 to frame 90:
- change channel 2 weight to 0
- change channel 3 weight to 0
From frame 140 to frame 150:
- change channel 1 weight to 0
- change channel 2 weight to 1
```

### Progressive Clipping Reveal
```
At frame 0:
- change all channels' front clipping to 0
From frame 0 to frame 400:
- rotate by 360 degrees horizontally
- change all channels' front clipping to 200
From frame 400 to frame 600:
- change all channels' front clipping to 600
```

### Animated Bounding Box Crop (Z-slice peeling)
```
At frame 0:
- change all channels' bounding box max z to 500
From frame 90 to frame 450:
- change all channels' bounding box max z to 0
```

---

## Tips

- **Batch Animation always outputs 101 frames** — frame numbers in the script are proportional (frame 720 in script maps to frame ~100 in output). Use Interactive Animation for exact frame counts.
- **Multiple rotations compose**: horizontal spin + X-axis tilt + Z-axis roll all run simultaneously.
- **Negative degrees**: reverse rotation direction.
- **Easing on last segment**: use `ease-in` on the final rotation segment for a smooth stop.
- **Bounding box as crop**: tighten bounding box to isolate a region of interest.
- **Front clipping to hide**: set front clipping to a large value (e.g., 1000) to fully clip away a channel.
- **Scale XY before rendering**: 3Dscript output size = input image size. Scale up 10x for HD output.
- **Do NOT Z-interpolate**: interpolated masked data falls below alpha threshold, dimming the signal.
- **8-bit required**: convert to 8-bit before rendering.
- **Script functions** enable oscillating, pulsing, and arbitrarily complex parametric motions.
- **Tuple syntax** sets multiple properties in one line (intensity, alpha, light, color, clipping).

## Example Scripts Location

`~/UK Dementia Research Institute Dropbox/Brancaccio Lab/Jamie/Macros and Scripts/3D Scripts/`
