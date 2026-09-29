---
name: long-running-batch-analysis
description: Run long Fiji batch analyses with bounded jobs, checkpoints, per-image provenance, failure isolation, and resumable outputs.
---

# Long-running batch analysis

1. Validate the workflow on a small representative subset before the batch.
2. Freeze the recipe version, plugins, parameters, input manifest, and output schema.
3. Use asynchronous Fiji jobs for operations longer than a few seconds. Do not block the chat with polling loops.
4. Checkpoint after each image or bounded batch: input token/hash, recipe version, outcome, outputs, warnings, duration, and next index.
5. Write outputs incrementally to `AI_Exports/`; never hold the only copy in model context.
6. Isolate failures by input. Stop on systematic failures, calibration changes, schema drift, memory pressure, or repeated identical errors.
7. Resume only after verifying existing artifact hashes and the frozen environment.
8. At completion, audit counts of succeeded, failed, skipped, and retried inputs and sample output quality across the run.

A completed process is not automatically a scientifically valid batch.
