# Color Science for Microscopy Reference

Reference for LUT selection, composites, colorblind accessibility, display vs data,
calibration bars, RGB conversion, and journal color requirements. Covers built-in
and Fiji-extra LUTs (Grays, Fire, mpl-viridis/inferno/magma/plasma, HiLo, glasbey,
blue_orange_icb, phase), colourblind-safe palette combinations, display-range
commands, composite/RGB conversion, pseudocolor + calibration bars, CLAHE/gamma,
and publication requirements for Nature, JCB, Cell, eLife, PLOS, Science.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code.
`python ij.py script '<code>'` — run Groovy (default), Jython, or JavaScript.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "Is this operation safe or destructive?" | §2, §11.2 |
| "Which LUT should I use for intensity / composites / labels?" | §3, §11.1 |
| "What built-in and Fiji-extra LUTs exist?" | §3 |
| "How do I pick colourblind-safe channel combinations?" | §4 |
| "How do I simulate deuteranopia / protanopia?" | §4, §12.6 |
| "How do I build and navigate a multi-channel composite?" | §5, §12.3 |
| "How do I convert composite to RGB for export?" | §6, §12.4 |
| "How do I add a calibration bar to a pseudocolor image?" | §7, §12.5 |
| "How do I make a ratio or fold-change map?" | §7 |
| "How do I color objects by a measurement?" | §7 |
| "What's safe vs destructive for brightness/contrast?" | §8 |
| "What does Nature / JCB / Cell / eLife / PLOS / Science require?" | §9 |
| "What are the common colour-science mistakes?" | §10 |
| "How do I convert to HSB / Lab / deconvolve stains?" | §12.5 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '`<term>`' color-science-reference.md` to jump.

### A
`Apply LUT` §2, §8, §10, §11.2 · `area_map` §7

### B
`Bit depth` §2 · `blue_orange_icb` §3, §7, §11.1 · `Brightness and contrast` §8

### C
`Calibration Bar` §7, §12.5 · `CLAHE` §8 · `Colour Deconvolution` §12.5 ·
`Composite (mode)` §5, §11.2, §12.3 · `Composite vs RGB` §5 ·
`CVD simulation` §4, §12.6 · `Cyan` §3 · `Cyan Hot` §3 ·
`Custom LUTs` §3

### D
`DAPI` §3, §4 · `Depth colour coding` §7 · `Deuteranopia` §4, §12.6 ·
`Display range` §2, §8, §11.2, §12.2 · `Destructive operations` §2, §11.2

### E
`edges` §3 · `Enhance Contrast` §2, §8, §10, §11.2 · `Export (RGB)` §6, §12.4

### F
`Fire` §3, §10, §11.1 · `FITC` §3 · `Flatten` §2, §6, §11.2 ·
`Fold-change` §7, §11.1

### G
`Gamma` §8 · `gem` §3 · `glasbey` §3, §11.1 · `glasbey_on_dark` §3, §11.1 ·
`glow` §3 · `Grays` §3, §11.1 · `Green` §3, §4 · `Green Fire Blue` §3

### H
`Histogram (saturation check)` §10 · `HiLo` §3, §10, §11.1 · `Hoechst` §3 ·
`HSB Stack` §12.5

### I
`ICA / ICA2 / ICA3` §3 · `Invert LUT` §3, §11.2, §12.2

### J
`JCB` §9 · `JPEG (avoid)` §2, §9, §10 · `Journal requirements` §9

### L
`Label maps` §3, §11.1 · `Lab Stack` §12.5 · `LUTs (built-in)` §3 ·
`LUTs (Fiji extras)` §3 · `Linear adjustments` §9

### M
`Magenta` §3, §4 · `Magenta Hot` §3 · `Make Composite` §5, §12.3 ·
`Measurement maps` §7 · `Merge Channels` §5, §12.3 · `Methods section template` §9 ·
`mpl-inferno` §3, §11.1 · `mpl-magma` §3, §11.1 · `mpl-plasma` §3 ·
`mpl-viridis` §3, §7, §11.1

### O
`Orange Hot` §3 · `Overlay.addSelection` §2

### P
`phase (LUT)` §3, §11.1 · `physics (LUT)` §3 · `Protanopia` §4, §12.6 ·
`Pseudocolor` §7, §11.1

### R
`Rainbow RGB (avoid)` §3, §10 · `Ratio images` §7 · `Red` §3, §4, §10 ·
`Red Hot` §3 · `resetMinAndMax` §2, §8, §11.2, §12.2 · `RGB Color` §2, §6, §11.2, §12.4 ·
`royal` §3

### S
`Safe operations` §2, §11.2 · `saveAs("Jpeg")` §2, §10, §11.2 ·
`saveAs("Tiff")` §10 · `Saturation check` §10 · `Sequential LUTs` §3 ·
`setLut` §3, §12.2 · `setMinAndMax` §2, §7, §8, §11.2, §12.2 · `sepia` §3 ·
`Simulate Color Blindness` §4, §12.6 · `Split Channels` §5, §6, §12.3 ·
`Stack.setChannel` §5, §12.3 · `Stack.setDisplayMode` §2, §5, §12.3 ·
`Stack.setActiveChannels` §5, §12.3 · `Summary rules` §14

### T
`Temporal-Color Code` §7, §12.5 · `Texas Red` §3 · `thal / thallium` §3 ·
`Thermal` §3 · `Tritanopia` §4

### Y
`Yellow` §3 · `Yellow Hot` §3

---

## §2 Display vs Data

A LUT maps pixel values to screen colors. Changing the LUT changes the display; the
numbers stay the same.

### Safe (Display-Only) Operations

| Operation | Macro | Effect |
|-----------|-------|--------|
| Apply LUT | `run("Fire");` | Changes color mapping |
| Set display range | `setMinAndMax(50, 3000);` | Changes brightness/contrast mapping |
| Reset display | `resetMinAndMax();` | Auto-adjusts display range |
| Composite mode | `Stack.setDisplayMode("composite");` | Changes channel display |
| Overlay add | `Overlay.addSelection();` | Adds non-destructive annotation |

### Destructive Operations (Permanently Modify Pixels)

| Operation | Macro | Risk |
|-----------|-------|------|
| Enhance Contrast (normalize) | `run("Enhance Contrast...", "saturated=0.3 normalize");` | Clips and rescales pixels |
| Apply LUT | `run("Apply LUT");` | Bakes display colors into data |
| Convert to RGB | `run("RGB Color");` | Locks display into 8-bit RGB |
| Flatten | `run("Flatten");` | Burns overlays into pixels |
| Save as JPEG | `saveAs("Jpeg", path);` | Lossy compression destroys data |
| 8-bit conversion | `run("8-bit");` | Reduces dynamic range permanently |

### When Display Becomes Destructive

```
run("Fire");              // SAFE: just a LUT change
run("RGB Color");         // DESTRUCTIVE: Fire colors are now the pixel data

setMinAndMax(100, 3000);  // SAFE: display-only
run("Apply LUT");         // DESTRUCTIVE: pixels outside 100-3000 clipped
```

### Bit Depth and Display Range

| Bit Depth | Value Range | Notes |
|-----------|-------------|-------|
| 8-bit | 0-255 | Limited dynamic range |
| 16-bit | 0-65535 | Most microscopy data; display range adjustable |
| 32-bit float | Any float | Ratio images, calculations |
| RGB | 3 x 0-255 | Display format, not for analysis |

```javascript
// Check data range vs display range
getStatistics(area, mean, min, max);
getMinAndMax(dispMin, dispMax);
setMinAndMax(min, max);  // Display-only: shows full dynamic range
```

---

## §3 Lookup Tables (LUTs)

### Built-in LUTs

| LUT | Macro | Uniform | CB-Safe | Use |
|-----|-------|---------|---------|-----|
| **Grays** | `run("Grays");` | Yes | Yes | Default; raw data inspection |
| **Red** | `run("Red");` | No | No* | RFP, Texas Red, Alexa 594 |
| **Green** | `run("Green");` | No | No* | GFP, FITC, Alexa 488 |
| **Blue** | `run("Blue");` | No | Yes | DAPI, Hoechst |
| **Cyan** | `run("Cyan");` | No | Yes | CFP, Alexa 405 |
| **Magenta** | `run("Magenta");` | No | Yes | Alternative to Red (CB-safe) |
| **Yellow** | `run("Yellow");` | No | Yes | YFP |
| **Fire** | `run("Fire");` | No | No | Intensity display (not ideal for publication) |

*Red and Green are individually fine but problematic when combined for colorblind viewers.

### Fiji Extra LUTs

| LUT | Macro | Uniform | CB-Safe | Use |
|-----|-------|---------|---------|-----|
| **mpl-viridis** | `run("mpl-viridis");` | Yes | Yes | Best general-purpose scientific LUT |
| **mpl-inferno** | `run("mpl-inferno");` | Yes | Yes | Heatmap, wide luminance range |
| **mpl-magma** | `run("mpl-magma");` | Yes | Yes | Heatmap, good for sparse signals |
| **mpl-plasma** | `run("mpl-plasma");` | Yes | Yes | Heatmap, vibrant |
| **Cyan Hot** | `run("Cyan Hot");` | Approx | Yes | Single-channel intensity |
| **Magenta Hot** | `run("Magenta Hot");` | Approx | Yes | Single-channel intensity |
| **Orange Hot** | `run("Orange Hot");` | Approx | Mostly | Intensity map |
| **HiLo** | `run("HiLo");` | N/A | N/A | QC: blue=zero, red=saturated, gray=mid |
| **physics** | `run("physics");` | Yes | Yes | Physics-community standard |
| **glasbey** | `run("glasbey");` | N/A | Varies | Label maps: max-contrast distinct colors |
| **glasbey_on_dark** | `run("glasbey_on_dark");` | N/A | Varies | Label maps on dark background |
| **blue_orange_icb** | `run("blue_orange_icb");` | No | No | Diverging: blue-white-orange |
| **phase** | `run("phase");` | No | No | Cyclic/phase data |

Other available: Red Hot, Yellow Hot, Green Fire Blue, ICA, ICA2, ICA3, cool, gem,
glow, royal, smart, Thermal, sepia, edges, thal, thallium, Rainbow RGB (avoid),
Spectrum (avoid), 5_ramps, 6_shades, 16_colors.

### LUT Categories — When to Use

| Category | Recommended | Avoid |
|----------|-------------|-------|
| **Sequential** (intensity) | mpl-viridis, mpl-inferno, mpl-magma, physics, Grays | Fire (non-uniform), Spectrum/Rainbow (misleading) |
| **Channel** (composites) | Green+Magenta, Cyan+Yellow, Blue+Green+Magenta | Red+Green (colorblind-hostile) |
| **Diverging** (ratio/fold-change) | blue_orange_icb | |
| **Label** (segmentation) | glasbey, glasbey_on_dark | Never on intensity data |
| **QC** (diagnostic) | HiLo (blue=zero, red=saturated) | |

### Custom LUTs

```javascript
// Create custom 256-entry LUT
reds = newArray(256); greens = newArray(256); blues = newArray(256);
for (i = 0; i < 256; i++) {
    reds[i] = i; greens[i] = i; blues[i] = 255;  // blue-to-white ramp
}
setLut(reds, greens, blues);

// Read current LUT
getLut(reds, greens, blues);

// Load from file (place .lut files in Fiji.app/luts/ for permanent install)
open("/path/to/custom.lut");

// Invert LUT (display only — useful for dark-on-light printing)
run("Invert LUT");
```

---

## §4 Colorblind-Safe Microscopy

Approximately 8% of males have red-green color vision deficiency. Traditional
red-green overlays are indistinguishable to these viewers.

### Safe Channel Combinations

| Channels | Instead of | Use |
|----------|-----------|-----|
| 2 | Red + Green | **Magenta + Green** or **Cyan + Yellow** |
| 3 | Red + Green + Blue | **Blue + Green + Magenta** |
| 4 | — | **Blue + Green + Magenta + Yellow** |
| 5+ | — | Blue + Green + Magenta + Yellow + Cyan + Orange Hot (or show individual grayscale panels) |

```javascript
// Two-channel colorblind-safe
Stack.setChannel(1); run("Green");
Stack.setChannel(2); run("Magenta");

// Three-channel colorblind-safe
Stack.setChannel(1); run("Blue");       // DAPI
Stack.setChannel(2); run("Green");      // Marker 1
Stack.setChannel(3); run("Magenta");    // Marker 2
```

Why Magenta + Green works: magenta = red + blue. Deuteranopes who cannot
distinguish red from green can still see the blue component of magenta.

### Colorblind Simulation

```javascript
// Creates a NEW image simulating how original appears to CVD viewers
run("Simulate Color Blindness", "type=Deuteranopia");  // most common (~6% males)
run("Simulate Color Blindness", "type=Protanopia");     // ~2% males
run("Simulate Color Blindness", "type=Tritanopia");     // very rare
```

Consider testing figures before submission. If channels become indistinguishable
in the simulation, choose different LUTs.

---

## §5 Channel Composites

### Composite vs RGB

| Property | Composite | RGB |
|----------|-----------|-----|
| Channels | Independent data + independent LUT | 3x 8-bit baked together |
| Editable | Toggle channels, adjust each | Display IS the data |
| Reversible | Yes | No — original intensities lost |

Consider working in composite mode and converting to RGB only as the final export step.

### Creating Composites

```javascript
// From multi-channel file
run("Bio-Formats Importer", "open=/path/to/file.nd2 color_mode=Composite");
// Or convert existing
run("Make Composite");

// From separate images (c1-c7 slots have default colors; override with LUTs after)
run("Merge Channels...", "c2=[gfp_image] c6=[cy5_image] create");
// c1=Red, c2=Green, c3=Blue, c4=Gray, c5=Cyan, c6=Magenta, c7=Yellow

// Keep source images
run("Merge Channels...", "c1=[ch1] c2=[ch2] c3=[ch3] create keep");
```

### Channel Navigation

```javascript
Stack.setChannel(1);                         // Switch channel
ch = Stack.getChannel();                     // Get current
Stack.getDimensions(w, h, channels, z, t);   // Get dimensions
Stack.setActiveChannels("110");              // Toggle visibility (1=show, 0=hide)
Stack.setDisplayMode("composite");           // composite / color / grayscale
```

### Setting LUTs Per Channel

```javascript
Stack.setChannel(1); run("Blue");    setMinAndMax(50, 2000);
Stack.setChannel(2); run("Green");   setMinAndMax(100, 3500);
Stack.setChannel(3); run("Magenta"); setMinAndMax(80, 3000);
Stack.setDisplayMode("composite");
```

### Splitting Channels

```javascript
run("Split Channels");  // Creates C1-title, C2-title, C3-title, ...
// To keep original: duplicate first, then split the copy
```

---

## §6 RGB Conversion

RGB is appropriate for figure export, presentations, and web display.
RGB is NOT appropriate for quantitative analysis or further processing.

### Workflow

Set display range BEFORE converting — RGB permanently captures the current display state.

```javascript
// 1. Set up display on composite
Stack.setChannel(1); run("Blue");    setMinAndMax(50, 2000);
Stack.setChannel(2); run("Green");   setMinAndMax(100, 3500);
Stack.setChannel(3); run("Magenta"); setMinAndMax(80, 3000);
Stack.setDisplayMode("composite");

// 2. Convert
run("RGB Color");    // Without overlays
// or
run("Flatten");      // With overlays (scale bars, text, ROIs) baked in
```

| Command | Overlays | Result |
|---------|----------|--------|
| `run("RGB Color");` | NOT included | 8-bit RGB |
| `run("Flatten");` | Baked into pixels | 8-bit RGB |

RGB = 8-bit per channel, always. 16-bit dynamic range is lost. Consider keeping
the original composite alongside any RGB export.

RGB cannot be reversed to original intensities. `run("Split Channels")` on RGB
gives 8-bit display approximations, not original data.

---

## §7 Pseudocolor and Calibration Bars

### Calibration Bars

Pseudocolor images typically need a calibration bar showing the value-to-color mapping.

```javascript
run("Calibration Bar...", "location=[Upper Right] fill=Black label=White number=5 decimal=0 font=14 zoom=1 overlay");
```

| Parameter | Options |
|-----------|---------|
| `location` | `[Upper Right]`, `[Lower Right]`, `[Upper Left]`, `[Lower Left]`, `[At Selection]` |
| `fill` | `White`, `Black`, `Dark Gray`, `Gray`, `Light Gray`, `None` |
| `label` | `White`, `Black`, `Dark Gray`, `Gray`, `Light Gray`, `None` |
| `number` | Integer (number of labels) |
| `decimal` | Integer (decimal places) |
| `font` | Integer (font size) |
| `zoom` | Float (bar size multiplier) |
| `overlay` | Flag (add as overlay, not pixels) |
| `bold` | Flag |

```javascript
// Custom position
makeRectangle(500, 50, 30, 200);
run("Calibration Bar...", "location=[At Selection] fill=None label=White number=5 decimal=0 font=12 overlay");
run("Select None");
```

### Ratio Images

```javascript
imageCalculator("Divide create 32-bit", "channel_340", "channel_380");
rename("ratio_340_380");
run("mpl-viridis");
setMinAndMax(0.5, 3.0);  // Set meaningful range for the ratio
run("Calibration Bar...", "location=[Upper Right] fill=Black label=White number=5 decimal=1 font=14 zoom=1.2 overlay");

// For diverging data (fold-change, etc.)
run("blue_orange_icb");
setMinAndMax(-2.0, 2.0);  // Symmetric around zero
```

### Temporal and Depth Color Coding

```javascript
// Temporal color code (time as color)
run("Temporal-Color Code", "lut=mpl-viridis start=1 end=" + frames);

// Depth (z) color code (same command applied to z-slices)
run("Temporal-Color Code", "lut=mpl-viridis start=1 end=" + nSlices);

// If frames==1 but slices>1, convert first:
run("Properties...", "channels=1 slices=1 frames=" + slices);
```

### Measurement Maps

```javascript
// Color objects by a measurement value
run("Analyze Particles...", "size=50-Infinity display add");
newImage("area_map", "32-bit black", getWidth(), getHeight(), 1);
n = roiManager("count");
for (i = 0; i < n; i++) {
    roiManager("select", i);
    setColor(getResult("Area", i));
    fill();
}
run("mpl-viridis");
resetMinAndMax();
run("Calibration Bar...", "location=[Upper Right] fill=Black label=White number=5 decimal=1 font=14 overlay");
```

---

## §8 Brightness and Contrast

### Display Range (Non-Destructive)

```javascript
setMinAndMax(lower, upper);  // Pixels <= lower → black, >= upper → white
resetMinAndMax();            // Auto-range
getMinAndMax(min, max);      // Read current range
```

### Consistent Range Across Conditions

Use the same `setMinAndMax()` values for all images being compared. Using
`resetMinAndMax()` per image gives different ranges, making visual comparison
meaningless.

```javascript
// Find shared range across images
images = newArray("control", "treated_low", "treated_high");
globalMin = 65535; globalMax = 0;
for (i = 0; i < images.length; i++) {
    selectWindow(images[i]);
    getStatistics(area, mean, min, max);
    if (min < globalMin) globalMin = min;
    if (max > globalMax) globalMax = max;
}
for (i = 0; i < images.length; i++) {
    selectWindow(images[i]);
    setMinAndMax(globalMin, globalMax);
}
```

### Enhance Contrast

```javascript
// SAFE (display-only):
run("Enhance Contrast...", "saturated=0.35");

// DESTRUCTIVE (avoid on data):
run("Enhance Contrast...", "saturated=0.35 normalize");  // Permanently clips+rescales
```

### CLAHE and Gamma

Both are destructive. Apply only to copies. Disclose in Methods if used for publication.

```javascript
// CLAHE — modifies pixel values
run("Enhance Local Contrast (CLAHE)", "blocksize=127 histogram=256 maximum=3 mask=*None*");

// Gamma — no built-in command; consider setMinAndMax() as non-destructive alternative
```

---

## §9 Journal Requirements

### Universal Requirements

All major journals typically require:
1. Disclose all adjustments in Methods/figure legend
2. Linear adjustments only (unless disclosed and justified)
3. Apply uniformly to entire image and all conditions
4. Same display range across comparison conditions
5. Colorblind accessibility (increasingly required)
6. Calibration bars for pseudocolor
7. Individual channels shown alongside composites

### Journal Comparison

| Requirement | Nature | JCB | Cell | eLife | PLOS | Science |
|-------------|--------|-----|------|-------|------|---------|
| Linear adjustments only | Required | Required | Required | Required | Required | Required |
| Disclose processing | Required | Required | Required | Required | Required | Required |
| Same range across conditions | Required | Required | Required | Recommended | Required | Required |
| Colorblind-safe colors | Encouraged | Encouraged | Recommended | Strongly encouraged | Encouraged | Encouraged |
| Individual channels shown | Recommended | Required | Recommended | Recommended | Encouraged | Recommended |
| Calibration bar (pseudocolor) | Required | Required | Recommended | Required | Recommended | Recommended |
| Non-linear adjustments | Disclose | Prohibited | Disclose | Disclose | Disclose | Disclose |
| JPEG for microscopy | Discouraged | Prohibited | Discouraged | Discouraged | Allowed* | Discouraged |
| Minimum DPI | 300 | 300 | 300 | 300 | 300 | 300 |

*PLOS allows JPEG only for photographic images, not microscopy.

JCB is the strictest: linear adjustments only, no gamma, identical settings across
conditions, individual grayscale channels required alongside composites.

### Methods Section Template

```
Microscopy images were processed using Fiji/ImageJ (version X.X.X).
Display adjustments (brightness/contrast) were applied linearly and
uniformly to entire images using setMinAndMax(). All images within
a comparison group were displayed with identical settings
(min=X, max=Y). Pseudocolor images use the [LUT name] lookup table.
Multi-channel composites use [Blue/Green/Magenta] for
[DAPI/marker1/marker2]. Individual channels are shown in
[Supplementary Figure X]. No non-linear adjustments were applied.
Scale bars indicate [X] micrometers.
```

---

## §10 Common Mistakes

| Mistake | Problem | Fix |
|---------|---------|-----|
| `run("Spectrum");` on intensity data | Creates false features, CB-hostile | `run("mpl-viridis");` |
| Red + Green merge | 8% of males cannot distinguish | Green + Magenta |
| `resetMinAndMax()` per image | Different ranges = unfair comparison | Same `setMinAndMax()` for all |
| `run("RGB Color");` then `run("Measure");` | Measuring display, not data | Measure on original first, then convert copy |
| `saveAs("Jpeg", ...)` | Lossy compression alters pixels | `saveAs("Tiff", ...)` |
| `run("Enhance Contrast...", "... normalize");` | Permanently clips+rescales | `setMinAndMax()` or omit `normalize` |
| `run("Apply LUT");` | Bakes display into pixels permanently | Just apply LUT (no `Apply LUT`) |
| Pseudocolor without calibration bar | Colors uninterpretable | Add `run("Calibration Bar...", ...)` |
| Only showing composite, no individual channels | Cannot assess channel quality | Include grayscale single-channel panels |
| Fire for publication heatmaps | Non-uniform luminance | mpl-viridis or mpl-inferno |
| Different LUT across same-type figure panels | Misleading comparison | Same LUT for same data type |

### Saturation Check

```javascript
getStatistics(area, mean, min, max);
nBins = 256;
getHistogram(values, counts, nBins);
if (counts[nBins-1] > area * 0.001) {
    print("WARNING: Possible saturation — " + counts[nBins-1] + " pixels at max");
}
run("HiLo");  // Visual QC: blue=zero, red=saturated
```

---

## §11 Quick Decision Trees

### §11.1 Which LUT?

```
Single-channel intensity?
├── Publication → mpl-viridis / mpl-inferno / mpl-magma
├── Quick inspection → Grays / Fire
└── QC check → HiLo

Multi-channel composite?
├── 2ch → Green + Magenta (or Cyan + Yellow)
├── 3ch → Blue + Green + Magenta
├── 4ch → Blue + Green + Magenta + Yellow
└── 5+ → Add Cyan, Orange Hot, or show grayscale panels

Label/segmentation map? → glasbey / glasbey_on_dark
Ratio/diverging data? → blue_orange_icb
Phase/cyclic data? → phase
Default → mpl-viridis
```

### §11.2 Is This Operation Safe?

```
SAFE (display only):
  run("Fire"), setMinAndMax(), resetMinAndMax(), run("Invert LUT"),
  Stack.setDisplayMode(), run("Enhance Contrast...", "saturated=0.35")

DESTRUCTIVE:
  run("Enhance Contrast...", "... normalize"), run("Apply LUT"),
  run("RGB Color"), run("Flatten"), run("8-bit"), saveAs("Jpeg")
```

---

## §12 Macro Command Reference

### §12.1 LUT Commands

```javascript
// Built-in
run("Grays"); run("Fire"); run("Green"); run("Red"); run("Blue");
run("Cyan"); run("Magenta"); run("Yellow");

// Fiji extras
run("mpl-viridis"); run("mpl-inferno"); run("mpl-magma"); run("mpl-plasma");
run("Cyan Hot"); run("Magenta Hot"); run("Orange Hot"); run("HiLo");
run("physics"); run("glasbey"); run("glasbey_on_dark"); run("blue_orange_icb");
run("phase");

// Custom LUT
getLut(reds, greens, blues);         // Read (3 x 256 arrays)
setLut(reds, greens, blues);         // Apply (3 x 256 arrays)
run("Invert LUT");                   // Invert display
open("/path/to/custom.lut");         // Load file
```

### §12.2 Display Range

```javascript
setMinAndMax(lower, upper);                              // Non-destructive
resetMinAndMax();                                        // Auto-range
getMinAndMax(min, max);                                  // Read current
run("Enhance Contrast...", "saturated=0.35");            // Auto-range (safe)
// DESTRUCTIVE: run("Enhance Contrast...", "saturated=0.35 normalize");
// DESTRUCTIVE: run("Apply LUT");
```

### §12.3 Composite and Channel

```javascript
run("Make Composite");
Stack.setDisplayMode("composite");   // composite / color / grayscale
Stack.setChannel(n);
Stack.setActiveChannels("110");      // Toggle visibility
run("Merge Channels...", "c1=[img1] c2=[img2] c3=[img3] create");
run("Split Channels");
```

### §12.4 RGB and Export

```javascript
run("RGB Color");                    // Composite → RGB (no overlays)
run("Flatten");                      // Composite → RGB (with overlays)
run("Split Channels");               // RGB → R, G, B (8-bit each)
```

### §12.5 Pseudocolor

```javascript
run("Temporal-Color Code", "lut=mpl-viridis start=1 end=100");
run("Calibration Bar...", "location=[Upper Right] fill=Black label=White number=5 decimal=0 font=14 zoom=1 overlay");
imageCalculator("Divide create 32-bit", "numerator", "denominator");
```

### Color Space Conversion

```javascript
run("HSB Stack");                    // RGB → Hue/Saturation/Brightness
run("Lab Stack");                    // RGB → L*/a*/b*
run("Colour Deconvolution", "vectors=[H DAB]");  // Histology stain separation
```

### §12.6 CVD Simulation

```javascript
run("Simulate Color Blindness", "type=Deuteranopia");
run("Simulate Color Blindness", "type=Protanopia");
```

---

## §13 Agent Notes

- No macro function to get LUT name. Read arrays with `getLut()` and compare patterns.
- LUTs apply to entire stacks automatically — no need to iterate slices.
- After split and re-merge, apply LUTs explicitly (do not rely on default slot colors).
- For batch processing, determine display range from a representative subset before
  applying to all images.
- For 3D rendering, set LUT and display range BEFORE rendering. See
  `references/3d-visualisation-reference.md`.

---

## §14 Summary Rules

1. LUT changes and `setMinAndMax()` are safe. `Apply LUT`, `normalize`, `RGB Color`,
   `Flatten`, `saveAs("Jpeg")` are destructive.
2. Use Green + Magenta (not Red + Green). Use viridis (not Rainbow) for heatmaps.
3. Same `setMinAndMax()` for all images in a comparison. Document in figure legend.
4. Include calibration bars for pseudocolor displays.
5. Show grayscale single-channel panels alongside composites.
6. Prefer mpl-viridis/inferno/magma for intensity heatmaps.
7. Save as TIFF, not JPEG, for microscopy.
8. Measure on raw data first, then create display copies.
9. Document LUT name, display range, and all processing in Methods.
10. When in doubt, Grays is always accessible and honest.
