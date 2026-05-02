# Bio-Formats Multi-Series Reference (LIF / CZI / ND2 / LSM)

Container formats hold many images ("series") in one file. A Leica `.lif`
from one imaging session commonly has 50–200 series. Opening all of them
is almost never what you want — peek at the metadata, pick the indices
you need, open just those.

All three gotchas in one line: **`Ext.setSeries` is 0-indexed, the
`series_N=true` macro token is 1-indexed, and that macro token is
unreliable for selecting multiple series — prefer the Groovy
`ImporterOptions` API below.**

Sources: Bio-Formats docs (`bio-formats.readthedocs.io`), OME-XML
metadata spec (`ome-model.readthedocs.io`), Bio-Formats macro
extensions (`imagej.net/formats/bio-formats`), and the
`loci.plugins` / `loci.formats` Javadoc bundled with Fiji. Use
`python probe_plugin.py "Bio-Formats Importer"` to inspect the
importer dialog's parameters at runtime.

Invoke from the agent:
`python ij.py macro '<code>'` — run ImageJ macro (.ijm) code with
`Ext.*` Bio-Formats extensions.
`python ij.py script '<code>'` — run Groovy (default) for the
`loci.formats` / `loci.plugins` Java APIs.

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "How do I list series in a `.lif` without opening pixels?" | §2 |
| "How do I open a specific series by index?" | §3 |
| "How do I open several series at once reliably?" | §3 (Groovy) |
| "How do I filter series by name / regex?" | §4 |
| "How do I read µm/pixel or Z-step from OME metadata?" | §5 |
| "How do I get per-channel names (mCherry/DAPI)?" | §6 |
| "How do I batch over many series (iterate, measure, close)?" | §7 |
| "What quirks does `.lif` / `.nd2` / `.czi` / `.lsm` / `.oib` have?" | §8 |
| "Which Bio-Formats Java classes do what?" | §9 |
| "What macro import-string tokens exist?" | §9 |
| "Why did my multi-series macro open series 1 only?" | §10 |
| "Why do Windows paths break in the importer?" | §10 |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term. Use
`grep -n '`<term>`' bioformats-multiseries-reference.md` to jump.

### A
`autoscale` §3, §9 · `AutoScale (ImporterOptions)` §3

### B
`BF.openImagePlus` §3, §4, §7, §9 · `Bio-Formats Importer` §3 · `Bio-Formats Macro Extensions` §2 · `batch pattern` §7

### C
`channel name` §6 · `color_mode` §3, §9 · `concatenate_series` §9 · `createOMEXMLMetadata` §2, §4, §5, §7, §9 · `.czi` (Zeiss) §8

### E
`excitation wavelength` §6 · `emission wavelength` §6 · `Ext.close` §2, §10 · `Ext.getSeriesCount` §2 · `Ext.getSeriesName` §2 · `Ext.getSizeC` §2 · `Ext.getSizeX` §2 · `Ext.getSizeY` §2 · `Ext.getSizeZ` §2 · `Ext.setId` §2 · `Ext.setSeries` §2

### F
`filter by name` §4 · `frameInterval` §5

### G
`getCalibration` §5 · `getChannelEmissionWavelength` §6 · `getChannelExcitationWavelength` §6 · `getChannelName` §6 · `getImageName` §2, §4, §7 · `getPixelsPhysicalSizeX` §5 · `getPixelsPhysicalSizeZ` §5 · `getPixelsTimeIncrement` §5 · `getSeriesCount` §2, §4, §7 · `getSizeC` §2, §6 · `getSizeT` §2 · `getSizeX` §2 · `getSizeY` §2 · `getSizeZ` §2 · `Groovy (ImporterOptions)` §3, §4, §7, §9

### I
`ImageReader` §2, §4, §7, §9 · `ImporterOptions` §3, §4, §7, §9

### L
`.lif` (Leica) §8 · `listing series` §2 · `loci.formats.ImageReader` §9 · `loci.formats.MetadataTools` §9 · `loci.plugins.BF` §9 · `loci.plugins.in.ImporterOptions` §9 · `.lsm` (Zeiss legacy) §8

### M
`macro importer` §3 · `MetadataTools` §2, §4, §5, §7, §9 · `multi-series selection` §3, §10

### N
`.nd2` (Nikon) §8

### O
`.oib` / `.oif` (Olympus) §8 · `OME metadata` §2, §4, §5, §6, §7 · `open_all_series` §3, §9, §10 · `openImagePlus` §3, §4, §7, §9

### P
`pattern filtering` §4 · `physical calibration` §5 · `pixelDepth` §5 · `pixelWidth` §5 · `PixelsPhysicalSizeX/Z` §5

### R
`reader.close` §2, §4, §5, §7, §10 · `reader.setId` §2, §4, §5, §7 · `reader.setSeries` §2

### S
`setAutoscale` §3, §7, §10 · `setColorMode` §3, §4 · `setId` §2, §3, §4, §5, §7 · `setMetadataStore` §2, §4, §5, §7 · `setOpenAllSeries` §3, §4, §7 · `setSeries` §2 · `setSeriesOn` §3, §4, §7 · `setVirtual` §3 · `series (concept)` §1 header, §2, §3 · `series_N` (1-indexed macro token) §3, §9, §10 · `split_channels` §9 · `stack_order` §3, §9

### T
`TimeIncrement` §5

### U
`use_virtual_stack` §3, §9

### V
`virtual stack` §3, §9 · `view=Hyperstack` §3, §9

### W
`Windows path slashes` §10

---

## §2. List series without opening the file

Two ways. Prefer the Groovy one — the macro one can't return a string to
the caller, only to the Log window.

### Groovy (returns a string to the tool reply)

```groovy
import loci.formats.ImageReader
import loci.formats.MetadataTools

def path = "C:/.../Cas3.All.Time.Points.lif"
def reader = new ImageReader()
def omeMeta = MetadataTools.createOMEXMLMetadata()
reader.setMetadataStore(omeMeta)
reader.setId(path)
def n = reader.getSeriesCount()
def sb = new StringBuilder()
sb.append("Series count: ").append(n).append("\n")
for (int i = 0; i < n; i++) {
    reader.setSeries(i)
    sb.append(i).append(": ").append(omeMeta.getImageName(i))
      .append(" [").append(reader.getSizeX()).append("x").append(reader.getSizeY())
      .append(" C=").append(reader.getSizeC())
      .append(" Z=").append(reader.getSizeZ())
      .append(" T=").append(reader.getSizeT())
      .append(" ").append(reader.getPixelType()).append("]\n")
}
reader.close()
return sb.toString()
```

No image is opened; no pixels are read. This is cheap — a 78-series LIF
returns in under a second.

### Macro (logs to the Log window)

```javascript
run("Bio-Formats Macro Extensions");
Ext.setId("C:/.../experiment.lif");
Ext.getSeriesCount(n);
for (s = 0; s < n; s++) {
    Ext.setSeries(s);
    Ext.getSeriesName(name);
    Ext.getSizeX(w); Ext.getSizeY(h); Ext.getSizeC(c); Ext.getSizeZ(z);
    print(s + ": " + name + " [" + w + "x" + h + " C=" + c + " Z=" + z + "]");
}
Ext.close();
```

Read with `get_log` afterward. `Ext.setSeries` is **0-indexed**.

---

## §3. Open specific series by index

### Groovy — reliable, handles any number of series

```groovy
import loci.plugins.BF
import loci.plugins.in.ImporterOptions

def path = "C:/.../experiment.lif"
def opts = new ImporterOptions()
opts.setId(path)
opts.setOpenAllSeries(false)
opts.setAutoscale(false)                                 // preserve pixel values
opts.setColorMode(ImporterOptions.COLOR_MODE_DEFAULT)
opts.setVirtual(false)

def wanted = [49, 50, 51, 52, 53, 54, 55]                // 0-indexed
wanted.each { opts.setSeriesOn(it, true) }

def imps = BF.openImagePlus(opts)
imps.each { it.show() }
return "Opened " + imps.length + " images"
```

`setAutoscale(false)` is the default for scientific data — autoscale
remaps display range based on per-series min/max and can disguise the
real intensity distribution across a batch.

### Macro — single series only, reliable

```javascript
run("Bio-Formats Importer",
    "open=[C:/.../experiment.lif] autoscale=false " +
    "color_mode=Default view=Hyperstack stack_order=XYCZT series_50");
```

`series_50` means the 50th series (**1-indexed** here). Do NOT mix
styles — `series_N=true` with multiple tokens in one macro call has
been observed to ignore all but the first selection and open series 1
instead. Use Groovy for multi-series selection.

### Macro — open every series (rarely what you want)

```javascript
run("Bio-Formats Importer",
    "open=[C:/.../experiment.lif] open_all_series " +
    "color_mode=Default view=Hyperstack stack_order=XYCZT");
```

A 78-series LIF at 1024×1024×13×4 opens ~16 GB of pixels. Check memory
with `get_state` first or use virtual stacks (`use_virtual_stack`).

---

## §4. Filter series by name pattern

Typical use: "give me all the 8-week Syn-mCherry series."

```groovy
import loci.formats.ImageReader
import loci.formats.MetadataTools
import loci.plugins.BF
import loci.plugins.in.ImporterOptions

def path = "C:/.../experiment.lif"
def pattern = ~/(?i)syn.*week8/        // case-insensitive regex

def reader = new ImageReader()
def omeMeta = MetadataTools.createOMEXMLMetadata()
reader.setMetadataStore(omeMeta)
reader.setId(path)
def matches = []
for (int i = 0; i < reader.getSeriesCount(); i++) {
    if (omeMeta.getImageName(i) =~ pattern) matches << i
}
reader.close()

def opts = new ImporterOptions()
opts.setId(path)
opts.setOpenAllSeries(false)
opts.setAutoscale(false)
opts.setColorMode(ImporterOptions.COLOR_MODE_DEFAULT)
matches.each { opts.setSeriesOn(it, true) }
def imps = BF.openImagePlus(opts)
imps.each { it.show() }
return "Matched " + matches.size() + " series: " + matches
```

---

## §5. Physical calibration (µm/pixel, Z-step)

Bio-Formats reads these from the LIF/CZI/ND2 automatically. After open:

```groovy
def imp = IJ.getImage()
def cal = imp.getCalibration()
return "x=" + cal.pixelWidth + " " + cal.getUnit() +
       ", z=" + cal.pixelDepth + ", frameInterval=" + cal.frameInterval + "s"
```

Without opening pixels, pull from the OME metadata object built during
`reader.setId(path)`:

```groovy
omeMeta.getPixelsPhysicalSizeX(seriesIndex)   // Length object with .value().doubleValue() and .unit()
omeMeta.getPixelsPhysicalSizeZ(seriesIndex)
omeMeta.getPixelsTimeIncrement(seriesIndex)
```

---

## §6. Per-channel name / colour (LIF stores these)

```groovy
for (int c = 0; c < reader.getSizeC(); c++) {
    def name = omeMeta.getChannelName(seriesIndex, c)
    def exc = omeMeta.getChannelExcitationWavelength(seriesIndex, c)
    def emi = omeMeta.getChannelEmissionWavelength(seriesIndex, c)
    // name is often "mCherry" / "DAPI" / "Alexa 488" — decisive for channel selection
}
```

Use this to identify the "mCherry channel" programmatically instead of
assuming channel order.

---

## §7. Batch pattern — iterate, filter, measure, close

```groovy
import loci.formats.ImageReader
import loci.formats.MetadataTools
import loci.plugins.BF
import loci.plugins.in.ImporterOptions
import ij.IJ

def path = "C:/.../experiment.lif"
def nameFilter = ~/(?i)syn.*week8/

def reader = new ImageReader()
def omeMeta = MetadataTools.createOMEXMLMetadata()
reader.setMetadataStore(omeMeta)
reader.setId(path)
def targets = []
for (int i = 0; i < reader.getSeriesCount(); i++) {
    if (omeMeta.getImageName(i) =~ nameFilter) targets << i
}
reader.close()

targets.each { idx ->
    def opts = new ImporterOptions()
    opts.setId(path)
    opts.setOpenAllSeries(false)
    opts.setAutoscale(false)
    opts.setSeriesOn(idx, true)
    def imp = BF.openImagePlus(opts)[0]
    imp.show()
    // ... your per-image work here ...
    imp.changes = false
    imp.close()
}
```

One series in memory at a time. Drop `.show()` and wrap in
`IJ.setBatchMode(true/false)` for headless-style speed.

---

## §8. Format notes

| Format | Typical series = | Quirks |
|---|---|---|
| Leica `.lif` | one experiment folder, one acquisition per series | series names include the Leica project tree; ideal for filtering |
| Nikon `.nd2` | position / FOV | multipoint XY is in the OME `Plate`/`Well` hierarchy, not always in series names |
| Zeiss `.czi` | scene | tiled acquisitions can be one series per tile or one stitched series — check `getSeriesCount` vs expected |
| Zeiss `.lsm` | rarely multi-series | legacy |
| Olympus `.oib/.oif` | area | `.oif` needs its companion folder intact on disk |

---

## §9. Reader cheatsheet (Groovy)

| Class | Use |
|---|---|
| `loci.formats.ImageReader` | metadata-only; never opens pixels |
| `loci.plugins.BF` | high-level `openImagePlus(opts)` returning `ImagePlus[]` |
| `loci.plugins.in.ImporterOptions` | the full set of import flags the GUI exposes |
| `loci.formats.MetadataTools` | `createOMEXMLMetadata()` gives you the OME store |

Import string cheatsheet for the macro API:
`autoscale`, `color_mode=Default|Composite|Colorized|Grayscale|Custom`,
`view=Hyperstack|Standard|Browser`, `stack_order=XYCZT`,
`split_channels`, `use_virtual_stack`, `concatenate_series`,
`open_all_series`, `series_N` (1-indexed, single series only — see §3).

---

## §10. Failure modes seen in the wild

- **`series_50=true series_51=true ...` in one macro opens series 1 only.**
  Use the Groovy `ImporterOptions` API for multi-series selection (§3).
- **Passing a Windows path with backslashes** — Bio-Formats wants forward
  slashes even on Windows. `C:/Users/...` works; `C:\Users\...` breaks.
- **Path contains spaces** — the macro importer requires square brackets:
  `open=[C:/path with spaces/file.lif]`. The Groovy `setId(path)` doesn't.
- **Forgetting `reader.close()` / `Ext.close()`** — leaves a file handle
  that blocks the OS from deleting or overwriting the file.
- **`setAutoscale(true)` on a batch** — every series gets its own display
  range, making intensity comparisons across series misleading.
- **Opening a large LIF with `open_all_series`** — 78 series × 50 MB each
  is 4 GB of heap; Fiji will either OOM or get very slow. Peek first (§2).
