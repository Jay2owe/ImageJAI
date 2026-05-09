# Methods Table — QUAREP-LiMi WG11 aligned

<!--
Skeleton aligned with the QUAREP-LiMi WG11 "Bare Minimum Microscopy
Methods Reporting" field outline (Microscope, Optics, Illumination,
Detection, Acquisition, Sample, Analysis, Software). Each field is a
human-readable label followed by a `[unknown]` placeholder. The
`methods_table.py` exporter walks this skeleton field-by-field and
substitutes values it can pull from Bio-Formats metadata (`get_metadata`),
the provenance graph (`get_image_graph`), and the recorded session log.
Fields with no available data stay as `[unknown]` — that is the correct
output, NOT an error. Reviewers/authors edit before journal submission.
This is a draft tool, not an oracle.
-->

## Microscope

- Manufacturer & model: `[unknown]`
  <!-- source: Bio-Formats OME-XML Instrument/Microscope (Manufacturer, Model) -->
- Microscope type (widefield / confocal / spinning-disk / lightsheet / TIRF / STED / SIM): `[unknown]`
  <!-- source: Bio-Formats Instrument/Microscope Type, fall back to OME-XML annotation -->
- Stage / sample holder: `[unknown]`
  <!-- source: Bio-Formats Instrument/Stage if present; otherwise human-only -->

## Optics

- Objective lens: `[unknown]`
  <!-- source: Bio-Formats Objective Model + Manufacturer + nominal magnification -->
- Numerical aperture (NA): `[unknown]`
  <!-- source: Bio-Formats Objective LensNA -->
- Immersion medium: `[unknown]`
  <!-- source: Bio-Formats Objective Immersion -->
- Working distance: `[unknown]`
  <!-- source: Bio-Formats Objective WorkingDistance (rare; usually [unknown]) -->

## Illumination

- Light source(s): `[unknown]`
  <!-- source: Bio-Formats LightSource (Laser/Arc/Filament/LightEmittingDiode) -->
- Excitation wavelength(s) (nm): `[unknown]`
  <!-- source: Bio-Formats Channel ExcitationWavelength per channel -->
- Excitation power / setting: `[unknown]`
  <!-- source: Bio-Formats LightSource Power; rarely populated -->

## Detection

- Detector(s): `[unknown]`
  <!-- source: Bio-Formats Detector (CCD / EMCCD / sCMOS / PMT / HyD) Model + Manufacturer -->
- Emission wavelength(s) / filter(s) (nm): `[unknown]`
  <!-- source: Bio-Formats Channel EmissionWavelength + Filter, per channel -->
- Detector gain / offset: `[unknown]`
  <!-- source: Bio-Formats DetectorSettings Gain/Offset; rarely populated -->

## Acquisition

- Pixel size (calibrated): `[unknown]`
  <!-- source: Bio-Formats Pixels PhysicalSizeX/Y (+ unit) — also surfaced as Calibration in ImageJ -->
- Z step / voxel depth: `[unknown]`
  <!-- source: Bio-Formats Pixels PhysicalSizeZ -->
- Frame interval (time-series): `[unknown]`
  <!-- source: Bio-Formats Pixels TimeIncrement / ImageJ Calibration frameInterval -->
- Exposure time: `[unknown]`
  <!-- source: Bio-Formats Plane ExposureTime per channel -->
- Image dimensions (X × Y × Z × C × T): `[unknown]`
  <!-- source: ij.py info width/height/nSlices/nChannels/nFrames -->
- Bit depth: `[unknown]`
  <!-- source: ij.py info bitDepth -->
- Acquisition rationale (why these settings): `[unknown — human only]`
  <!-- source: NOT auto-filled — author must explain why -->

## Sample

- Specimen description: `[unknown]`
  <!-- source: Bio-Formats Image Description if present; otherwise human-only -->
- Fluorescent labels / stains per channel: `[unknown]`
  <!-- source: Bio-Formats Channel Name / Fluor; sometimes embedded in image title -->
- Mounting medium: `[unknown]`
  <!-- source: Bio-Formats Image Description; usually [unknown] -->
- Fixation: `[unknown]`
  <!-- source: not in OME-XML — human-only -->

## Analysis

- Analysis tool: `ImageJAI vX.Y / Fiji (ImageJ build)`
  <!-- source: hard-coded ImageJAI version + ij.py info imagejVersion -->
- Plugins / commands invoked: `[unknown]`
  <!-- source: session log — distinct execute_macro `run("...")` calls + plugin versions where available -->
- Parameters: `[unknown]`
  <!-- source: session log — argument string passed to each run("...") call -->
- Number of images processed: `[unknown]`
  <!-- source: provenance graph node count or session log distinct images -->
- Statistical-test protocol: `[unknown — human only]`
  <!-- source: NOT auto-filled — author must explain test choice and rationale -->

## Software

- ImageJAI version: `[unknown]`
  <!-- source: hard-coded build constant in methods_table.py -->
- Fiji / ImageJ version: `[unknown]`
  <!-- source: ij.py state imagejVersion / get_state -->
- Operating system: `[unknown]`
  <!-- source: platform.platform() -->
- Replayable macro export: `[unknown]`
  <!-- source: session_log.export_macro() path -->

---

<!--
Field-coverage statistic appended at end of generated file:
"Field coverage: N/M WG11 fields populated; (M-N) marked [unknown]."
-->
