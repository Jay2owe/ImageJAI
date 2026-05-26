# 02 — PseudonymisationFilter (the technical core)

## Why this stage exists

This is the single load-bearing stage of the whole project. Every
claim the supervisor, REC reviewer, or methods section makes
("identifiable strings do not leave the machine") rests on this class
behaving correctly. The audit log, the PDF, the badges and the file
browser are all credibility theatre layered on top of what this class
does.

The filter must be technically correct in Pseudonymised and
On-premises modes, transparent in Standard mode, and never destroy
data the agent genuinely needs to function (numeric statistics, macro
syntax, plugin parameter names, downsampled microscopy pixels). It
also exposes the new `open_image_by_token` reverse-resolution
primitive that stage 09's file browser depends on.

## Prerequisites

- Stage 01 (`PrivacyPosture`, `PostureController.current()`).

## Read first

- `docs/data-governance/00_overview.md` — house rules and naming.
- `src/main/java/imagejai/engine/security/AgentContextSanitizer.java`
  — sibling class for prompt-injection mitigation. Mirror its style
  and packaging.
- `src/main/java/imagejai/engine/TCPCommandServer.java` — Grep for
  `dispatchCore` and `dispatchInternal` to locate the response
  serialisation point.
- `agent/ij.py`, `agent/pixels.py`, `agent/CLAUDE.md` — the agent's
  contract with each TCP command. The filter must not break this
  contract.
- `src/main/java/imagejai/terminal/EmbeddedPty.java` — the embedded
  agent terminal, where the outbound prompt scrubber attaches.

## Scope

A new class `imagejai.engine.security.PseudonymisationFilter` plus
support classes. Seven mechanisms:

1. **Path + series tokenisation** (`PathTokenMap`).
   - Whole-file paths: `/MOAB2/subject_017.lif` → `image-7a3f.lif`.
   - Series-within-file: `image-7a3f.lif:4` for series 4 of that
     file. Same map; same in-JVM lifetime.
   - Stable per session, randomised between sessions. Mapping never
     leaves the JVM.

2. **OME-XML PHI scrubbing** (`OmeXmlScrubber`). Strip
   `Experimenter`, `Description`, `StageLabel`, `AcquisitionDate`,
   `InstrumentRef`, `InstrumentSerialNumber`, and `Annotation` tag
   content. Preserve `Pixels`, `Channel`, calibration.

3. **Differentiated `CaptureHandler`** for `capture_image` calls.
   The honest threat model: microscopy pixels are low-PII;
   GUI/dialog screenshots are high-PII (window titles burn filenames
   in); some microscope frames have text burn-ins (Incucyte
   timestamps, Aperio labels).

   | `CaptureSource` | Pseudonymised | On-premises |
   |---|---|---|
   | `ACTIVE_IMAGE_CONTENT` (the science) | Downsample to ≤512×512, mask detected burn-ins, return base64 PNG | Same handling (local model still benefits from the burn-in mask) |
   | `ACTIVE_IMAGE_WITH_OVERLAY` (ROIs) | Allowed if overlay text passes burn-in check | Allowed |
   | `DIALOG_SCREENSHOT` | Refused → hash placeholder | Refused |
   | `WINDOW_SCREENSHOT` (with title bar) | Refused | Refused |
   | `DESKTOP_SCREENSHOT` | Refused | Refused |

4. **`request_visual` override.** A one-call escape hatch. Agent
   asks for full-resolution pixels of the active image; user sees a
   non-modal "Allow once?" toast; on approval, the next
   `capture_image` of `ACTIVE_IMAGE_CONTENT` returns full-resolution
   base64 (still with burn-in mask applied). The override expires
   after one call. Refused in On-premises.

5. **Results-table column tokenisation.** `Label` always tokenised.
   `Slice` tokenised only if non-numeric or contains a path-like
   substring. Numeric columns pass through.

6. **Free-text scrub.** Walk all string values in the JSON
   response; replace any substring matching the `PathTokenMap`'s
   keys (longest-first, single pass, no re-scan). Catches paths
   embedded in error messages, dialog titles, `print()` output.

7. **Outbound prompt scrubber** (`OutboundPromptScrubber`,
   embedded-PTY only). Intercept user keystrokes in the embedded
   terminal; on Enter, replace any known-sensitive substring with
   its token; surface a non-modal toast *"Pseudonymised: 'MOAB2…' →
   'image-7a3f' in your message."* Optional Ctrl+Shift+Enter sends
   raw (audited).

Plus:

- **`_governance` self-describing block.** Every redacted response
  gains a top-level `_governance` field:
  ```json
  { "posture": "Pseudonymised",
    "fields_pseudonymised": ["path", "ome_xml", "image"] }
  ```
  Makes every outbound payload self-auditing — visible in the
  Receipts pane and in any debugging session.

- **`open_image_by_token` reverse-resolution.** A new helper used by
  the TCP server's `open_image` command (and by stage 09): if the
  caller passes `image-7a3f.lif` or `image-7a3f.lif:4`, the filter
  reverses to the real path/series locally and hands to Bio-Formats.
  Real path never leaves the JVM.

- **Fail-closed policy.** If any step throws in Pseudonymised /
  On-premises mode, the response is replaced with
  `{ "ok": false, "error": "redaction_failed", "_governance": {...} }`
  and the original is dropped. Fail-open is allowed only in Standard.

## Out of scope

- The audit log row write (stage 04) — filter exposes
  `RedactionReport`; stage 04 records it.
- Receipts pane / Configuration Pane (stage 05).
- Browse Files dialog and `SelectionBroker` (stage 09).
- Inbound text sanitisation — `AgentContextSanitizer` already owns
  that surface; leave it alone.

## Files touched

| Path | NEW/MODIFY | Reason |
|------|------------|--------|
| `src/main/java/imagejai/engine/security/PseudonymisationFilter.java` | NEW | The filter |
| `src/main/java/imagejai/engine/security/PathTokenMap.java` | NEW | Path + series tokenisation, reverse lookup |
| `src/main/java/imagejai/engine/security/OmeXmlScrubber.java` | NEW | OME-XML field stripper |
| `src/main/java/imagejai/engine/security/CaptureHandler.java` | NEW | Differentiated capture pipeline |
| `src/main/java/imagejai/engine/security/CaptureSource.java` | NEW | Enum for capture classification |
| `src/main/java/imagejai/engine/security/BurnInDetector.java` | NEW | Cheap heuristic (border high-contrast text detection) + optional configurable masks |
| `src/main/java/imagejai/engine/security/VisualOverrideRegistry.java` | NEW | One-shot `request_visual` consent tracker |
| `src/main/java/imagejai/engine/security/RedactionReport.java` | NEW | Value object: fields touched, byte counts, governance block payload |
| `src/main/java/imagejai/engine/security/OutboundPromptScrubber.java` | NEW | Embedded-PTY prompt interceptor |
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Wire filter into response path; add `request_visual` + reverse-resolution path on `open_image` |
| `src/main/java/imagejai/terminal/EmbeddedPty.java` | MODIFY | Attach `OutboundPromptScrubber` to keystroke pipeline |
| `src/test/java/imagejai/engine/security/*Test.java` | NEW | Per-mechanism unit tests |

## Implementation sketch

### Filter entry point

```java
public RedactionReport apply(JSONObject response, String command, PrivacyPosture posture) {
    if (posture == PrivacyPosture.STANDARD) return RedactionReport.passthrough();
    try {
        RedactionReport.Builder r = RedactionReport.builder()
            .command(command).bytesBefore(response.toString().length());

        if ("capture_image".equals(command)) captureHandler.apply(response, posture, r);
        if (response.has("ome_xml"))         omeScrubber.apply(response, r);
        if (response.has("results"))         tokeniseResultsTable(response, r);
        tokenisePathTypedFields(response, r);
        freeTextScrub(response, r);

        response.put("_governance", new JSONObject()
            .put("posture", posture.label)
            .put("fields_pseudonymised", new JSONArray(r.fieldsRedacted())));

        return r.bytesAfter(response.toString().length()).build();
    } catch (Exception e) {
        return failClosed(response, posture, e);
    }
}
```

### `PathTokenMap` with series support

```java
public String tokenForPath(Path p) { ... } // image-7a3f.lif
public String tokenForSeries(Path p, int seriesIndex) {
    String base = tokenForPath(p);
    return base + ":" + seriesIndex; // image-7a3f.lif:4
}

// Reverse:
public record ResolvedTarget(Path realPath, int series) {}
public Optional<ResolvedTarget> resolve(String token) {
    // Used by open_image_by_token; tokens never escape this method
}
```

Tokens preserve file extensions so Bio-Formats / plugin routing still
works. Stable within a session; randomised across sessions.

### `CaptureHandler`

```java
public void apply(JSONObject response, PrivacyPosture posture, RedactionReport.Builder r) {
    CaptureSource source = CaptureSource.from(response.optString("source", "ACTIVE_IMAGE_CONTENT"));

    if (source == CaptureSource.DIALOG_SCREENSHOT
     || source == CaptureSource.WINDOW_SCREENSHOT
     || source == CaptureSource.DESKTOP_SCREENSHOT) {
        replaceWithPlaceholder(response, r, "refused_" + source);
        return;
    }

    if (visualOverrideRegistry.consumeIfPresent(currentSession())) {
        // user has approved one-shot full-res; still mask burn-ins
        byte[] full = base64Decode(response.getString("image_base64"));
        byte[] masked = burnInDetector.mask(full);
        response.put("image_base64", base64Encode(masked));
        r.fieldRedacted("image_base64", "visual_override_burn_in_masked");
        return;
    }

    // default Pseudonymised microscopy path: downsample + mask
    byte[] raw = base64Decode(response.getString("image_base64"));
    byte[] downsampled = downsampleTo(raw, 512);
    byte[] masked = burnInDetector.mask(downsampled);
    response.put("image_base64", base64Encode(masked));
    response.put("downsampled_to", "512");
    r.fieldRedacted("image_base64", "downsampled_and_masked");
}
```

### `BurnInDetector` — cheap heuristic first

```java
public byte[] mask(byte[] png) {
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
    // Scan top, bottom, left, right border strips (10% of dim).
    // High-contrast text-like region (variance + edge density) → fill black.
    // Plus configured per-format masks from .imagejai-burn-in.yml if present.
    ...
}
```

Tesseract via tess4j is the optional second pass; ship without it
initially and add later if false negatives appear.

### `VisualOverrideRegistry`

```java
public final class VisualOverrideRegistry {
    private final ConcurrentHashMap<String, Instant> pending = new ConcurrentHashMap<>();

    public void grant(String sessionId, String reason) {
        pending.put(sessionId, Instant.now());
        // emit audit row (stage 04 listens)
    }
    public boolean consumeIfPresent(String sessionId) {
        Instant t = pending.remove(sessionId);
        return t != null && Duration.between(t, Instant.now()).toSeconds() < 60;
    }
}
```

UI for the grant: a non-modal toast at the launcher panel. *"Agent
requested visual inspection of image-7a3f.lif. Reason: '<agent's
stated reason>'. [Allow once] [Deny]"* On Allow, registry is
populated and the next `capture_image` consumes it.

### `OutboundPromptScrubber`

```java
public String scrubOutgoing(String userTyped) {
    Map<String,String> map = pathMap.snapshot();
    String result = userTyped;
    // longest-first replacement to avoid partial matches
    for (var e : map.entrySet().stream()
            .sorted((a,b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
            .toList()) {
        result = result.replace(e.getKey(), e.getValue());
    }
    if (!result.equals(userTyped)) toast("Pseudonymised before send.");
    return result;
}
```

Only attached when the user launches an embedded-PTY agent. External
CLIs bypass this — documented in stage 07 as the user-typed-filename
limitation.

### Wiring point in `TCPCommandServer`

```java
// inside dispatchInternal, just before writing the response:
RedactionReport report = filter.apply(response, command, postureController.current());
// stage 04 reads `report`
out.write(response.toString());
```

Also: extend the `open_image` command handler to call
`pathMap.resolve(arg)` first; if it returns a `ResolvedTarget`, use
that; otherwise treat as a real path.

## Exit gate

1. `mvn compile` and `mvn test` pass.
2. New tests assert each mechanism. Minimum scenarios:
   - Path tokenisation: same path twice → same token; new map →
     different token; series token format `image-XXXX.ext:N`.
   - OME-XML scrub: `<Experimenter>Jane</Experimenter>` removed,
     `<Pixels>` preserved.
   - Capture handler `ACTIVE_IMAGE_CONTENT` → base64 present but
     ≤512px and burn-in regions masked.
   - Capture handler `DIALOG_SCREENSHOT` → placeholder, no base64.
   - `request_visual` granted then `capture_image` called once →
     full-res returned; second call without re-grant → downsampled.
   - Results table `Label` "MOAB2_subject_017_cell_3" tokenised;
     numeric `Slice=12` left alone; `Slice="image.lif"` tokenised.
   - Error message containing a registered path → token appears,
     original does not.
   - `_governance` block present on every redacted response.
   - Free-text scrub: longest-first, no re-scan.
   - Fail-closed: filter throwing in Pseudonymised → safe error
     response, original dropped.
   - Reverse resolution: `open_image_by_token("image-XXXX.lif:3")`
     yields the correct real path + series 3 to Bio-Formats.
3. Coverage: every TCP command that can return path-typed,
   OME-XML, base64-pixel, or results-table data has at least one
   redaction test.
4. Manual: in Pseudonymised mode, run `get_state` on an image from
   `/anything/sensitive_name.lif` — response shows
   `image-XXXX.lif`; `_governance.fields_pseudonymised` includes
   `path`.
5. Manual: in Standard mode, same call returns the original path
   and no `_governance` block.

## Known risks

- **Free-text scrub on very large strings.** Bio-Formats verbose
  dumps can be 100KB+. Skip the walk-all-strings pass for responses
  >200KB and log a warning row.
- **`Slice` column.** Tokenise only if non-numeric or contains
  `/`, `\`, or known extensions (`.lif`, `.tif`, `.czi`, `.nd2`).
- **JSON nesting.** Walk recursively. Some commands return
  graph/friction payloads.
- **Burn-in detection false negatives.** Cheap heuristic catches
  ~90% of cases. Document the limitation in stages 06 / 07 and
  ship Tesseract follow-up if needed.
- **Outbound prompt scrubber edge cases.** Multi-line paste with
  partial tokens, Unicode normalisation, terminal control codes.
  Apply scrubber before sending to PTY; if the user's prompt
  contains nothing in the map, pass through untouched.
- **`request_visual` race.** The grant must be tied to a session;
  granting in one session does not allow another. Test concurrency.
- **Performance.** Aim for filter overhead <10 ms on a 50KB
  realistic payload. Benchmark in stage 08.
