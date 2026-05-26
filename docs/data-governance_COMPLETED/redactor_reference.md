# Redactor Reference

This is the developer reference for extending ImageJAI's outbound
pseudonymisation filter. It describes what the current code does and
where to add tests when the data shape changes.

## Entry point

`PseudonymisationFilter.apply(JsonObject response, String command,
PrivacyPosture posture, String sessionId)` is the outbound checkpoint
before JSON leaves the JVM.

Standard posture is pass-through. Pseudonymised and On-premises
postures apply the filter and attach a top-level `_governance` block:

```json
{
  "posture": "Pseudonymised",
  "fields_pseudonymised": ["path", "image"]
}
```

If filtering throws in Pseudonymised or On-premises mode, the filter
fails closed. The original payload is dropped and the response becomes
`redaction_failed`.

## What gets tokenised

`PathTokenMap` owns process-local tokens. It preserves image
extensions so downstream tools can still infer file type:

```text
C:\study\MOAB2_subject_017.lif -> image-7a3f.lif
C:\study\MOAB2_subject_017.lif series 4 -> image-7a3f.lif:4
```

The map is in JVM memory only. Do not serialise it, log original
strings, or write it to the audit CSV.

The filter tokenises:

- Path-typed JSON fields: `path`, `*_path`, `filepath`,
  `filename`, `file`, `directory`, `folder`, `sourcefile`,
  `source_file`, `title`, and `windowtitle`.
- Path-like substrings in ordinary strings when they include common
  microscopy image extensions.
- Registered sensitive strings during the free-text pass, longest
  first, without rescanning inserted tokens.
- Results-table `Label` values.
- Results-table `Slice` values when non-numeric or path-like.

Binary fields are deliberately skipped by string passes: `base64`,
`image_base64`, `data`, `pixels`, `thumbnail`, and keys ending in
`_base64`.

## Adding a new path-typed field

Prefer existing field names when adding TCP responses. If a new
response must expose a path under a new key, update
`PseudonymisationFilter.isPathTypedKey`.

Add or update tests in
`src/test/java/imagejai/engine/security/PseudonymisationFilterTest.java`
that assert:

- The original filename or path is absent from the serialised JSON.
- The replacement token starts with the expected token family, usually
  `image-`.
- `_governance.fields_pseudonymised` contains `path`.

Do not add ad hoc string replacement at the command handler. The
handler should return honest local state; the outbound filter owns
pseudonymisation.

## OME-XML scrubbing

`OmeXmlScrubber` removes whole XML elements that commonly carry
people, dates, instrument identifiers, or free-text annotations. The
current tag list includes:

```text
Experimenter, Description, StageLabel, AcquisitionDate, InstrumentRef,
InstrumentSerialNumber, Annotation, XMLAnnotation, CommentAnnotation,
FileAnnotation, ListAnnotation, LongAnnotation, MapAnnotation,
TagAnnotation, TermAnnotation, TimestampAnnotation
```

Pixel structure and calibration metadata should remain usable. When
adding a tag, add a test that proves the sensitive value disappears
and a neighbouring `<Pixels ...>` element survives.

## CaptureSource taxonomy

`CaptureHandler` classifies `capture_image` responses with
`CaptureSource`:

| Source | Pseudonymised / On-premises behaviour |
|---|---|
| `ACTIVE_IMAGE_CONTENT` | Downsample to at most 512 x 512 and apply burn-in mask |
| `ACTIVE_IMAGE_WITH_OVERLAY` | Allowed if burn-in detection is clean; refused if overlay text is detected |
| `DIALOG_SCREENSHOT` | Refused; base64 removed and hash placeholder returned |
| `WINDOW_SCREENSHOT` | Refused; base64 removed and hash placeholder returned |
| `DESKTOP_SCREENSHOT` | Refused; base64 removed and hash placeholder returned |

Any new source must be classified explicitly. If it can include window
chrome, filenames, user names, desktop content, or dialog text, treat
it as refused until there is a narrower source type.

Add tests in `CaptureHandlerTest` for every new source.

## request_visual

`request_visual` is a one-shot visual override for Pseudonymised mode.
The TCP command records a pending request with
`VisualOverrideRegistry`, publishes a UI event, and returns
`pending_user_consent`. On approval, the next
`ACTIVE_IMAGE_CONTENT` capture for that session can return full
resolution pixels, still with the burn-in mask applied. The grant
expires after 60 seconds and is consumed once.

On-premises refuses `request_visual`. Standard reports that the
override is not required.

Agent guidance should require a one-sentence reason. Do not request
full-resolution vision speculatively.

## Burn-in masks

`BurnInDetector` is currently a fast border heuristic. It scans top,
bottom, left, and right strips for high-contrast, text-like edge
density and masks suspicious strips black. It uses no OCR and has no
external dependency.

To add a known vendor mask, extend `BurnInDetector.detectMasks` or add
a small configuration reader that produces `Rectangle` masks before
the heuristic result is returned. Tests should use synthetic PNGs
with visible border text and assert that the masked pixels become
black.

Keep this conservative. False negatives are possible and must remain
documented; false positives should not destroy the active image,
because masking happens only on outbound PNG bytes.

## Outbound prompt scrubber

`OutboundPromptScrubber` covers the embedded PTY only. It buffers a
line until Enter, replaces registered sensitive substrings with their
tokens, emits a toast/notifier, and writes a `prompt.outbound` audit
row. It does not protect external terminals.

If a new launcher path bypasses the embedded PTY, document it as an
external CLI path and steer users to Browse Files rather than typed
filenames.

## Test patterns

Use the stage 02 tests as the baseline:

- Same path twice gives the same token; a new map gives a different
  token.
- Series token format is `image-XXXX.ext:N`.
- OME-XML sensitive elements are removed while `<Pixels>` survives.
- `ACTIVE_IMAGE_CONTENT` captures are at most 512 px on the longest
  side and burn-in masked.
- Dialog, window, and desktop screenshots remove base64 and return a
  placeholder.
- Visual override is consumed once.
- Results-table `Label` and non-numeric/path-like `Slice` values are
  tokenised.
- Free-text errors containing registered paths are scrubbed.
- Longest-first replacement does not rescan inserted tokens.
- `_governance` appears on every redacted-mode response.
- Redaction failure in Pseudonymised and On-premises modes fails
  closed.
- A realistic 50 KB payload filters under the documented performance
  budget.
