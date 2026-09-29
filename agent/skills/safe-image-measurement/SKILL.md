---
name: safe-image-measurement
description: Preserve raw microscopy values, calibration, image identity, and measurement provenance while using Fiji.
metadata:
  recipe: intensity_quantification
---

# Safe image measurement

- Check that an image is open and record its image ID and revision.
- Confirm bit depth, channels, C/Z/T plane, spatial calibration, and intensity units before measuring.
- Do not run `Enhance Contrast` with `normalize=true` on data that will be measured. Use display-only min/max changes instead.
- Duplicate the source before filters, thresholding, conversion, projections, or arithmetic unless the operation is explicitly reversible.
- Bind every result to the source image/revision, ROI identity, channel, slice/frame range, calibration, background method, macro, and parameters.
- Check saturation and background assumptions. Do not report physical units when calibration is absent or ambiguous.
- Save tables and derived outputs under `AI_Exports/` next to the opened image.
- Re-read Results and state before summarising. Never infer a measurement from a screenshot.
