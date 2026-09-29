---
name: closed-loop-image-analysis
description: Run ImageJ analyses as inspect, plan, duplicate, execute, verify, and record cycles instead of issuing unchecked macros.
---

# Closed-loop image analysis

Use this guidance for any non-trivial Fiji analysis.

1. Read current state, image identity, revision, dimensions, type, calibration, and C/Z/T position.
2. State a one-line plan and its success checks.
3. Protect source data. Duplicate or create a derived image before destructive display or pixel changes.
4. Probe unfamiliar plugins. Do not guess dialog argument names.
5. Perform one mutation step at a time. Re-read image state after each mutation.
6. Verify numerically with Results, histogram, foreground fraction, counts, or domain metrics.
7. Verify visually with a bounded capture or overlay when the model supports vision. A screenshot is not a measurement.
8. Record exact macros, parameters, units, image revision, result artifacts, failures, and user decisions.
9. Stop and ask when calibration disappears, the active image changes unexpectedly, results violate checks, or repeated failures form a loop.

Never treat a plausible-looking output as validation.
