# Step 07 — New TCP Commands: ROI, Display, Console

## Motivation

Three concrete Fiji observability gaps that the server currently
doesn't expose at all:

1. **RoiManager state.** Zero references in the server codebase.
   Every agent currently writes a macro to query ROI state instead
   of asking over TCP.
2. **Display cursor and LUT state.** `ImagePlus.getC/Z/T()`, LUT
   name, display range min/max — agents guess or write macros.
3. **Fiji Console (distinct from IJ Log).** SciJava `OutputService`
   captures Groovy/Jython stack traces that `IJ.getLog()` never
   sees. Transcripts show Gemma reflex-calling `get_log` on a
   Groovy compile error and getting an empty string.

Closing these gaps server-side makes every agent wrapper better
in one pass.

## End goal

Three new TCP commands:

### `get_roi_state`

```json
{"command": "get_roi_state"}
// Response
{"ok": true, "result": {
  "count": 42,
  "selectedIndex": 3,
  "rois": [
    {"index": 0, "name": "Cell_001", "type": "polygon",
     "bounds": [12, 34, 56, 78]},
    ...
  ]
}}
```

### `get_display_state`

```json
{"command": "get_display_state"}
// Response
{"ok": true, "result": {
  "activeImage": "blobs",
  "c": 1, "z": 1, "t": 1,
  "channels": 3, "slices": 1, "frames": 1,
  "compositeMode": "composite",
  "activeChannels": "111",
  "displayRange": {"min": 0.0, "max": 4095.0},
  "lut": "Grays"
}}
```

### `get_console`

```json
{"command": "get_console", "tail": 2000}
// Response
{"ok": true, "result": {
  "stdout": "...",
  "stderr": "java.lang.NullPointerException at ...",
  "combined": "...",
  "truncated": false
}}
```

`tail` param defaults to 2000 bytes; `-1` for full (capped at 10k
tokens per reply ceiling).

## Scope

In:
- Three handlers in `TCPCommandServer.java`.
- Dispatch table entries.
- A small `ConsoleCapture` helper that installs a `PrintStream`
  wrapper around `System.out` and `System.err` at server start,
  buffering the last N KB.
- Capability gating: not strictly needed — these are read-only
  queries — but add to `enabled` list for discoverability.

Out:
- Python wrapper bindings. That's step 08.
- SciJava `OutputService` subscription. Worth doing but adds a
  dependency on the SciJava module loader; keep it to
  `System.out`/`err` capture for this step, which catches the
  same Groovy stack traces.

## Read first

- `src/main/java/imagejai/engine/TCPCommandServer.java`
  — handler pattern and dispatch.
- `ij.plugin.frame.RoiManager` Javadoc — `getInstance()`,
  `getCount()`, `getRoisAsArray()`, `getSelectedIndex()`,
  `getName(i)`.
- `ij.ImagePlus` — `getC()`, `getZ()`, `getT()`,
  `getDisplayRangeMin()`, `getDisplayRangeMax()`, `getProcessor().
  getLut().getName()`.
- `ij.CompositeImage` — `getMode()`, `getActiveChannels()`.

## Handlers

### `get_roi_state`

```java
private JsonObject handleGetRoiState(JsonObject req, AgentCaps caps) {
    RoiManager rm = RoiManager.getInstance();
    JsonObject out = new JsonObject();
    if (rm == null) {
        out.addProperty("count", 0);
        out.add("rois", new JsonArray());
        return okReply(out);
    }
    out.addProperty("count", rm.getCount());
    out.addProperty("selectedIndex", rm.getSelectedIndex());
    JsonArray arr = new JsonArray();
    Roi[] rois = rm.getRoisAsArray();
    for (int i = 0; i < rois.length; i++) {
        JsonObject r = new JsonObject();
        r.addProperty("index", i);
        r.addProperty("name", rm.getName(i));
        r.addProperty("type", roiTypeName(rois[i]));
        Rectangle b = rois[i].getBounds();
        JsonArray bounds = new JsonArray();
        bounds.add(b.x); bounds.add(b.y);
        bounds.add(b.width); bounds.add(b.height);
        r.add("bounds", bounds);
        arr.add(r);
    }
    out.add("rois", arr);
    return okReply(out);
}
```

Cap the `rois` array at 500 entries with `truncated: true` if over
— a session with 10000 ROIs would otherwise blow the 10k-token
ceiling.

### `get_display_state`

```java
private JsonObject handleGetDisplayState(JsonObject req, AgentCaps caps) {
    ImagePlus imp = WindowManager.getCurrentImage();
    JsonObject out = new JsonObject();
    if (imp == null) {
        out.add("activeImage", JsonNull.INSTANCE);
        return okReply(out);
    }
    out.addProperty("activeImage", imp.getTitle());
    out.addProperty("c", imp.getC());
    out.addProperty("z", imp.getZ());
    out.addProperty("t", imp.getT());
    out.addProperty("channels", imp.getNChannels());
    out.addProperty("slices", imp.getNSlices());
    out.addProperty("frames", imp.getNFrames());
    if (imp instanceof CompositeImage ci) {
        out.addProperty("compositeMode", compositeModeName(ci.getMode()));
        out.addProperty("activeChannels", ci.getActiveChannels());
    }
    JsonObject dr = new JsonObject();
    dr.addProperty("min", imp.getDisplayRangeMin());
    dr.addProperty("max", imp.getDisplayRangeMax());
    out.add("displayRange", dr);
    LUT lut = imp.getProcessor() != null ? imp.getProcessor().getLut() : null;
    out.addProperty("lut", lut != null ? lutNameOrHeuristic(lut) : null);
    return okReply(out);
}
```

LUT naming is a known Fiji pain: the LUT doesn't always carry its
source name. Heuristic: match common palettes (Grays, Fire, Ice,
Red, Green, Blue, 16_colors, glasbey) by byte signature; fall
back to "custom".

### `get_console`

```java
// Installed once at server start:
public static void installConsoleCapture() {
    RingBuffer stdoutBuf = new RingBuffer(64 * 1024);
    RingBuffer stderrBuf = new RingBuffer(64 * 1024);
    System.setOut(new TeePrintStream(System.out, stdoutBuf));
    System.setErr(new TeePrintStream(System.err, stderrBuf));
    ConsoleCapture.STDOUT = stdoutBuf;
    ConsoleCapture.STDERR = stderrBuf;
}

private JsonObject handleGetConsole(JsonObject req, AgentCaps caps) {
    int tail = req.has("tail") ? req.get("tail").getAsInt() : 2000;
    String stdout = ConsoleCapture.STDOUT.readTail(tail);
    String stderr = ConsoleCapture.STDERR.readTail(tail);
    JsonObject out = new JsonObject();
    out.addProperty("stdout", stdout);
    out.addProperty("stderr", stderr);
    out.addProperty("combined", interleave(stdout, stderr));
    out.addProperty("truncated", tail < ConsoleCapture.STDOUT.size());
    return okReply(out);
}
```

`TeePrintStream` forwards to the original stream so Fiji's own UI
still sees everything, while the ring buffer keeps a bounded
recent tail.

Size budget: 64 KB per stream covers the last several Groovy
errors comfortably without bloating heap. Bigger buffers aren't
worth it — older stderr is rarely relevant.

## Capability declaration

These commands always work once installed. Advertise them in the
`hello` response's `enabled` list so agents can feature-detect:

```json
"enabled": ["get_roi_state", "get_display_state", "get_console", ...]
```

## Tests

- `get_roi_state` with no RoiManager open → `count: 0, rois: []`.
- Add 3 ROIs programmatically, then query → 3 entries with
  correct types and bounds.
- Multi-channel composite image → `get_display_state` returns
  `compositeMode: "composite"`, `activeChannels: "111"`.
- LUT applied → name surfaces for known palettes.
- Groovy script that throws → `get_console.stderr` contains the
  stack trace.
- Console capture doesn't duplicate to original stream (Fiji UI
  still shows text).

## Failure modes

- `System.out`/`err` hijacking clashes with another plugin that
  also wraps them. Use `Tee`, not `replace`, so both co-exist.
- Ring-buffer truncation loses older stack traces. Document
  64 KB cap; agent that wants more must have been watching
  live via the event bus.
- LUT name heuristic mislabels a custom palette. Acceptable —
  label as "custom" when unsure rather than guessing wrong.
- RoiManager not open but ROIs exist on the image as overlays.
  Out of scope here; `get_display_state` already surfaces the
  image, and overlays are a separate query.
