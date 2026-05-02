# Family — Gemma

## Single-argument tool calls

You hallucinate multi-argument tool calls. **A tool with two or
more parameters will be called with wrong-slot arguments.** The
toolset is designed so that each tool takes a single semantic
argument; if you find yourself emitting a second argument, you
are calling the wrong tool. Pick a more specific one.

## Vocabulary

You historically confuse these. They are not interchangeable.

- **Filter** — intensity operation on the image (Gaussian,
  Median, Mean, Unsharp, Convolve, Variance, Bandpass).
- **Threshold** — binarisation method (Otsu, Li, Default).
- **Segmentation** — threshold + mask + Analyze Particles.

"10 filters" never means "10 threshold methods" — do not
substitute `threshold_shootout` for "compare 10 filters". Use
`run_macro` with the actual filters.

## `threshold_shootout` is the answer, not reconnaissance

When the user asked for a count, `threshold_shootout`'s
`recommended` + its `count` IS the final answer — report it as a
table and stop. Only re-segment if the user asked for per-object
measurements, a labelled mask, a size-filtered subset, or ROI
overlays. Do not call shootout for non-thresholding tasks.

## Single-line `run_shell`

`run_shell` accepts ONE shell command per call (cmd.exe on
Windows). Multi-line scripts, command chains with `&&`, and
piped sequences may be rejected by the safety layer; use
sequential `run_shell` calls instead.

## Triage banners

Read `[triage] PLUGIN OUTPUT` banners. Titles like
`Objects map of X` / `Summary of X` / `Labels` / `Mask of X` /
`Skeleton of X` mean the last plugin SUCCEEDED. Call
`get_results` and stop retrying.

## Lint

Lint will block common Fiji macro mistakes (see base context).
Trust the lint; do not work around it. If lint rejects a macro,
read the rejection and fix the macro.

## Reading reference docs

`agent/references/` holds reference docs. Read them with
`run_shell("type agent\\references\\<name>-reference.md")` —
Windows uses `type`, not `cat`. Do not try a file-read tool;
you do not have one.
