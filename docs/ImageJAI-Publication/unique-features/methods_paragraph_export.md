# Methods Paragraph Export — QUAREP-LiMi WG11 auto-emission

**Status:** Implemented (modest, draft-tool) — 2026-05-09. Decision D8 in
`docs/imagejai-publication/decisions.md`.

## What it is

`agent/methods_table.py` walks three structured data sources the agent
already has — the session log (`agent/session_log.py`), the active
image's Bio-Formats metadata (`get_metadata` TCP command), and the
provenance graph (`get_image_graph`) — and emits a Markdown methods
table aligned with QUAREP-LiMi WG11 *Bare Minimum Microscopy Methods
Reporting* fields. The output goes to `<image-dir>/AI_Exports/methods.md`
per the agent's house rule on output location.

The agent can also trigger the exporter from the TCP side via
`emit_methods_table`, which shells out to the Python script and returns
`{"path": "...", "fieldCoverage": "21/33"}` so the agent can confirm
how much it auto-populated.

## Field map (33 WG11-aligned fields)

| Section | Auto-populates from | Always `[unknown]` until human |
|---|---|---|
| Microscope | Bio-Formats `<Microscope>` Manufacturer / Model / Type | Stage / sample holder |
| Optics | Bio-Formats `<Objective>` Manufacturer / Model / LensNA / Immersion / NominalMagnification | Working distance |
| Illumination | Bio-Formats `<Channel>` ExcitationWavelength + `<LightSource>` (Laser/Arc/LED) | Power / setting |
| Detection | Bio-Formats `<Detector>` Manufacturer / Model / Type + `<Channel>` EmissionWavelength | Gain / offset |
| Acquisition | Calibration (pixelWidth/Height/Depth/unit) + `<Pixels>` PhysicalSize\* + TimeIncrement + ExposureTime + image dims/bit depth | Acquisition rationale (human-only) |
| Sample | Bio-Formats `<Channel>` Name / Fluor | Specimen / mounting / fixation |
| Analysis | Session log distinct `run("plugin", "args")` calls + provenance graph node count + ImageJ build | Statistical-test protocol (human-only) |
| Software | Hard-coded ImageJAI version + `imagejVersion` from `get_state` + `platform.platform()` + `session_log.export_macro()` path | — |

## The draft-tool framing

This is **not** an oracle. The exporter does *not* claim journal
compliance; it produces a draft that a reviewer or author edits before
submission. The framing is deliberate — three concrete reasons:

1. **Most acquisition-side metadata is absent on most images.** Public
   benchmark images (BBBC020, DSB2018, IDR samples) ship minimal
   OME-XML; the 33-field table will frequently be 13/33 or 15/33
   populated. `[unknown]` is the *correct* output for any field the
   data does not cover.
2. **Two fields are explicitly human-only.** Statistical-test
   protocol and acquisition rationale carry an `[unknown - human only]`
   marker that no automation will ever fill. The exporter does not
   guess; the author writes.
3. **Citation harvesting is out of scope.** Decision D8 explicitly
   defers Option D (DOI lookup, BibTeX assembly) to v2. This avoids
   the worst-case overclaim ("automated journal compliance") while
   still landing the strongest single concrete differentiator.

## The field-coverage statistic

Every `methods.md` ends with one line:

```
Field coverage: N/M WG11 fields populated; (M-N) marked [unknown].
```

This is the honest summary. The three example outputs in
`supplements/exported_macros/methods_examples/` report 15/33, 13/33,
and 13/33 against synthetic public-benchmark inputs. On a real
microscope-acquired dataset with rich OME-XML the coverage typically
climbs to ~22/33; the human author closes the rest.

## What this is NOT

- Not a citation harvester. No DOI lookup, no BibTeX generation.
  Deferred (D8 Option D).
- Not a statistical-test rationale generator. No data source supports
  this; the field stays `[unknown - human only]`.
- Not an acquisition-rationale generator. Same reasoning.
- Not a substitute for the OMERO ROCRATE export some labs use; that
  is a different tool with a different scope.

## Local sources

- `agent/methods_table.py` (~270 LOC, stdlib-only)
- `agent/methods_table_template.md` (WG11 skeleton with sourcing notes)
- `src/main/java/imagejai/engine/TCPCommandServer.java` —
  `handleEmitMethodsTable` + dispatch entry for `emit_methods_table`
- `docs/imagejai-publication/supplements/exported_macros/methods_examples/`
  — three synthetic example outputs (count + measure, segment +
  classify, time-series + tracking)
