# Stage 02 — Master switch + per-guard caps

Add the master `caps.safe_mode` toggle (default-on per agent),
per-guard option flags for opt-in items, and a checkbox in
AgentLauncher so the user can flip the whole layer off for a fast,
unguarded session.

## Why this stage exists

Every default-on guard in stages 03–06 reads `caps.safe_mode` to
decide whether to fire. Opt-in guards (bit-depth narrowing,
Enhance-Contrast-normalize hard block) read per-option flags. This
stage is the single source of truth that downstream stages query —
build it once, build it right.

## Prerequisites

- Stage 01 (`_COMPLETED`) — caps must already thread through nested
  dispatch, otherwise the master switch lies.

## Read first

- `docs/safe_mode_v2/00_overview.md`
- `docs/safe_mode/03_integration.md` — existing handshake spec, the
  `safe_mode`/`rehearsal`/`destructive_policy` fields
- `src/main/java/imagejai/engine/TCPCommandServer.java`
  - `AgentCaps` inner class ≈ lines 204–312 (verify)
  - `handleHello` ≈ line 1632 (verify) and `optBool(caps, "safe_mode",
    false)` already wired
- `src/main/java/imagejai/engine/AgentLauncher.java` —
  the play-button panel; we'll add the toggle near the agent dropdown

## Scope

- Flip `AgentCaps.safeMode` default to **true** for any agent that
  doesn't explicitly set it false.
- Add `AgentCaps.SafeModeOptions` sub-struct with these booleans:
  - `blockBitDepthNarrowing` (default false — opt-in, Stage 05)
  - `blockNormalizeContrast` (default false — opt-in, Stage 05)
  - `autoBackupRoiOnReset` (default true — Stage 05)
  - `autoSnapshotRescue` (default true — Stage 03)
  - `queueStormGuard` (default true — Stage 04)
  - `autoSourceImageColumn` (default true — Stage 06)
  - `scientificIntegrityScan` (default true — Stage 05)
- Parse these from `caps.safe_mode_options.*` in `handleHello`.
- Add a `Safe Mode` checkbox to the AgentLauncher panel that, when
  unchecked, sets `safeMode = false` for the next agent launch (the
  Python wrapper reads it from an env var or config).
- Add an `enabledSafeModeOptions(AgentCaps c)` helper that returns
  the set of currently-active guard names — used by Stage 07 for the
  status-indicator tooltip.

## Out of scope

- Wiring any specific guard to read these flags — each downstream
  stage owns its own check.
- A persistent per-agent caps file (Tier 2 brainstorm idea, deferred).
- A trust-score system (deferred to v3).

## Files touched

| Path | Change | Reason |
|------|--------|--------|
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | `AgentCaps` extension, `handleHello` parsing, `enabledSafeModeOptions` helper |
| `src/main/java/imagejai/engine/AgentLauncher.java` | MODIFY | Add `Safe Mode` checkbox, persist preference |
| `agent/ij.py` | MODIFY | New `--no-safe-mode` flag plumbed into the `hello` payload |
| `agent/CLAUDE.md` | MODIFY | One-paragraph note on the safe-mode toggle and per-option flags |
| `src/test/java/imagejai/engine/TCPCommandServerHelloTest.java` | MODIFY | Cover the new fields and default values |

## Implementation sketch

```java
// In AgentCaps (inner class):
public boolean safeMode = true;          // <-- flipped to true
public SafeModeOptions safeModeOptions = new SafeModeOptions();

public static final class SafeModeOptions {
    public boolean blockBitDepthNarrowing  = false;  // opt-in
    public boolean blockNormalizeContrast  = false;  // opt-in
    public boolean autoBackupRoiOnReset    = true;
    public boolean autoSnapshotRescue      = true;
    public boolean queueStormGuard         = true;
    public boolean autoSourceImageColumn   = true;
    public boolean scientificIntegrityScan = true;
}
```

Hello payload:
```json
{"command": "hello", "agent": "claude",
 "capabilities": {
    "safe_mode": true,
    "safe_mode_options": {
        "block_bit_depth_narrowing": true,
        "block_normalize_contrast": true
    }
 }}
```

`handleHello` parsing:
```java
JsonObject opts = caps.has("safe_mode_options")
    ? caps.getAsJsonObject("safe_mode_options") : new JsonObject();
agentCaps.safeModeOptions.blockBitDepthNarrowing =
    optBool(opts, "block_bit_depth_narrowing", false);
// ... repeat for each field
```

AgentLauncher: a `JCheckBox("Safe Mode", true)` next to the agent
dropdown. On state change, write the boolean to
`~/.imagejai/launcher_prefs.json` so it survives restart. Python
wrappers read that file at agent-launch time and add `--no-safe-mode`
when the box is unchecked.

## Exit gate

1. `mvn compile` clean.
2. Updated `TCPCommandServerHelloTest` passes:
   - Hello with no `safe_mode_options` → all fields at documented
     defaults; `safeMode = true`.
   - Hello with `safe_mode: false` → `safeMode = false` and all
     option fields ignored.
   - Hello with explicit `block_bit_depth_narrowing: true` → that
     option flips on; others stay default.
3. Manual: launch AgentLauncher, toggle the checkbox, restart Fiji,
   confirm preference persisted.
4. `python agent/ij.py capabilities` shows the new fields in the
   server's reply.

## Known risks

- Flipping the default from `false` to `true` is a breaking change
  for existing wrappers that don't send `hello`. The fallback for
  no-handshake clients should remain `safeMode = false` (legacy
  behaviour) — only agents that DO handshake get the new default.
  Document clearly.
- AgentLauncher pref file may not exist on first run — handle
  gracefully (treat missing as "checked / safe mode on").
- Python wrapper changes are cross-cutting; coordinate with all four
  agent wrappers (`agent/ij.py` is shared).
