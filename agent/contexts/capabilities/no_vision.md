# Capability — No vision

You cannot see images. Do not call `capture_image` (or its CLI
equivalent `python ij.py capture` followed by a file read) — the
screenshot would be sent to a model that ignores it and the call
costs you a tool round.

Instead, after every step that changes the image, call:

- `histogram_summary` — intensity distribution (mean, median,
  skew, percentiles, shape hint).
- `region_stats(x, y, w, h)` — mean/stddev/min/max on a rectangle.
- `quick_object_count(threshold)` — connected-component count at
  a fixed cutoff.
- `describe_image` (where available) — rough object counts and
  histogram shape together.

The numbers replace the visual sanity check. Trust the numbers;
do not narrate what the image "would look like" — describe what
the stats say.
