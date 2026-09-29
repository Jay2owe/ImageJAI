---
name: segmentation-validation
description: Validate microscopy segmentation with numeric, visual, object-level, and held-out checks before accepting counts or morphology.
metadata:
  recipe: cell_counting
---

# Segmentation validation

Before choosing a method, record image type, dimensionality, channels, calibration, expected object scale, and whether objects touch.

For a candidate mask:

- Check foreground fraction, empty/full-mask failure, border objects, object count, and size distribution.
- Inspect an overlay on representative fields, including difficult and negative regions.
- For labelled ground truth, use Dice/IoU plus object precision/recall, split errors, merge errors, and count bias. Pixel overlap alone is insufficient.
- Stratify performance by image, acquisition batch, instrument, and biological condition.
- Compare against the previous approved recipe on held-out images and preserve both parameter sets.
- Treat thresholds and size cut-offs as image-specific until representative multi-image and multi-batch evidence supports promotion.
- Record plugin/model versions and reject silent changes in preprocessing or calibration.

Do not promote a method because one mask looks attractive or its count falls in a plausible range.
