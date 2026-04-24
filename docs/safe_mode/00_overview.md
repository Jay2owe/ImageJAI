# Safe Mode

A safety layer in the TCP server that catches risky agent actions before
they touch real data. Two pieces:

1. **Silent macro rehearsal** — every macro dry-runs on a thumbnail copy of
   the current image before running on the real one. If rehearsal fails, the
   real run is aborted.
2. **Destructive-op interceptor** — macros that delete files, overwrite
   originals, or wipe results are blocked by default. The agent must
   explicitly opt in to destructive mode, per-call.

Both are opt-in via the `hello` capability handshake, so existing
Claude/Codex/Gemini sessions behave exactly as today unless they ask for
safe mode.

## Why

Transcripts show agents (especially Gemma) writing macros that:
- Save over the input image with `saveAs("Tiff", path)` using the original
  path
- Call `File.delete(...)` on a file they just created as a side effect,
  intending to delete a different one
- Overwrite a results table that took twenty minutes to populate
- Run destructive ops on images they didn't mean to be active
  (see `ghost-dialog-queued-macros.md` — macro #16 fired against
  `ch2_final-0001` instead of `ch2_final`)

The server is the only place that can stop this universally — client-side
linting in the Python wrappers misses Groovy and Jython, and is
per-wrapper anyway.

## Files

- `01_rehearsal.md` — how silent thumbnail rehearsal works
- `02_destructive_block.md` — how file-deletion and overwrite blocking works
- `03_integration.md` — how both hook into `TCPCommandServer.handleExecuteMacro`
