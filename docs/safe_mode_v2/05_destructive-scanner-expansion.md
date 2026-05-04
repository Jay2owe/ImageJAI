# Stage 05 — Scientific-integrity scanner expansion

Add seven new rules to the destructive-op scanner / post-exec checks
covering the silent measurement-killers that the original plan misses
(it watches the filesystem; this stage watches the science).

## Why this stage exists

The current `DestructiveScanner` (per `docs/safe_mode/02_destructive_block.md`)
catches `File.delete`, `saveAs` over original, `close("*")` with unsaved
work. None of these address the worst real damage in microscopy:
bit-depth narrowing turning intensity measurements to nonsense,
calibration loss turning area measurements to nonsense, ROI Manager
wipes losing hand-drawn user labour, Z-project overwriting volumetric
stacks. This stage closes that gap.

## Prerequisites

- Stage 01 (`_COMPLETED`)
- Stage 02 (`_COMPLETED`) — needs `caps.safeModeOptions.scientificIntegrityScan` plus the two opt-in flags

## Read first

- `docs/safe_mode_v2/00_overview.md`
- `docs/safe_mode/02_destructive_block.md` — existing scanner spec;
  this stage extends, doesn't replace
- `agent/references/image-integrity-reference.md` — biologist
  authoritative source on what's destructive
- `agent/references/if-postprocessing-reference.md` §9 (DESTROYS vs
  PRESERVES table)
- `src/main/java/imagejai/engine/MacroAnalyser.java` —
  existing `PostExec` runtime-state hook
- `src/main/java/imagejai/engine/UndoFrame.java:355` —
  `macroHasDiskWrites`, the existing destructive heuristic to subsume
- `src/main/java/imagejai/engine/TCPCommandServer.java` —
  `handleExecuteMacro` ≈ 2678 (insert scan call), `RoiManager`
  access ≈ 1886

## Scope — seven rules

Default-on:

| Rule | Detection | Action |
|------|-----------|--------|
| **Calibration loss** | `run("Properties..."` AND args set `pixel_width=1` OR `setVoxelSize(_,_,_,"pixel")` while prior `cal.pixelWidth != 1.0` | **Reject** |
| **ROI wipe + auto-backup** | `roiManager("reset")` OR `roiManager("delete")` while `RoiManager.getInstance().getCount() > 0` | **Auto-backup to `AI_Exports/.safemode_roi_<ts>.zip`, then allow** |
| **Z-project overwrite** | `run("Z Project...")` followed in same macro by `saveAs(...)` whose target equals `imp.getOriginalFileInfo()` path | **Reject** |
| **Microscopy → PNG/JPEG overwrite** | `saveAs("PNG"/"JPEG"/"JPG", path)` AND `path` either matches the active image's original microscopy path OR matches an existing on-disk file with `.lif/.czi/.nd2/.ome.tif/.ome.tiff/.tif` extension | **Reject** (but: new files in `AI_Exports/` always allowed) |

Opt-in (gated on `caps.safeModeOptions.blockBitDepthNarrowing` /
`blockNormalizeContrast`):

| Rule | Detection | Action |
|------|-----------|--------|
| **Bit-depth narrowing** | `run("8-bit")` / `run("16-bit")` (when current bitDepth > target) on an image with pending Results-table measurements | **Reject** |
| **Enhance Contrast normalize** | `run("Enhance Contrast", "...normalize...")` | **Reject** |

Stage 06 owns the seventh related rule (`Source_Image` column auto-injection).

Plus: subsume `UndoFrame.macroHasDiskWrites` into the new scanner so
the regex isn't duplicated.

## Out of scope

- AST/lexer-based scanner (Tier 2 brainstorm idea — defer; regex is
  good enough for v2).
- Apply LUT detection (4b — explicitly dropped per user decision).
- Cross-image Results contamination warning (handled by Stage 06's
  `Source_Image` column instead — eliminates ambiguity).
- Per-call `destructive.scope` confirmation — already in the existing
  plan, not changed here.
- `interact_dialog` button-label gating (defer to v3).

## Files touched

| Path | Change | Reason |
|------|--------|--------|
| `src/main/java/imagejai/engine/safeMode/DestructiveScanner.java` | NEW (or MODIFY if Stage docs/safe_mode/02 already created it) | Seven new rules + subsume `macroHasDiskWrites` |
| `src/main/java/imagejai/engine/safeMode/RoiAutoBackup.java` | NEW | `AI_Exports/.safemode_roi_<ts>.zip` writer |
| `src/main/java/imagejai/engine/MacroAnalyser.java` | MODIFY | New `PostExec` fields: `bitDepthBefore/After`, `calibrationBefore/After`, `roiCountBefore/After` |
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Wire scanner into `handleExecuteMacro` and `handleRunScript` |
| `src/main/java/imagejai/engine/UndoFrame.java` | MODIFY | Delete `macroHasDiskWrites`; route callers through new scanner |
| `src/test/java/imagejai/engine/safeMode/DestructiveScannerTest.java` | NEW | One test per rule, plus negative cases (allow `AI_Exports/` PNG saves) |
| `src/test/java/imagejai/engine/safeMode/RoiAutoBackupTest.java` | NEW | Verify backup file written before reset |

## Implementation sketch — selected rules

ROI auto-backup:
```java
if (matchesRoiReset(code) && RoiManager.getInstance() != null
        && RoiManager.getInstance().getCount() > 0) {
    String dir = AiExports.dirFor(activeImp);
    Path backup = Paths.get(dir, ".safemode_roi_" + ts() + ".zip");
    RoiManager.getInstance().runCommand("save", backup.toString());
    // log to FrictionLog with rule_id="roi_auto_backup", target=backup
    // do NOT block — allow the reset to proceed
}
```

Microscopy-overwrite (only blocks overwrite, not new-file save):
```java
Matcher m = SAVE_AS_PATTERN.matcher(code); // "saveAs\(\s*\"(PNG|JPEG|JPG)\"\s*,\s*\"([^\"]+)\""
while (m.find()) {
    String fmt = m.group(1);
    String target = m.group(2);
    if (target.startsWith(aiExportsRoot)) continue; // always allow
    String origPath = activeImp.getOriginalFileInfo() != null
        ? activeImp.getOriginalFileInfo().directory + activeImp.getOriginalFileInfo().fileName
        : null;
    boolean overwritesOriginal = origPath != null && pathEquals(target, origPath);
    boolean targetIsMicroscopy = endsWithAny(target,
        ".lif", ".czi", ".nd2", ".ome.tif", ".ome.tiff", ".tif");
    if (overwritesOriginal || (targetIsMicroscopy && Files.exists(Paths.get(target)))) {
        ops.add(new DestructiveOp("microscopy_overwrite", target, m.start(), Severity.REJECT));
    }
}
```

Calibration / bit-depth (post-exec runtime checks):
```java
// Before macro: snapshot bitDepth, calibration into PostExec.before
// After macro: compare; if narrowed/lost AND option enabled, rollback via rescue frame from Stage 03
```

## Exit gate

1. `mvn compile` clean; no new warnings.
2. `DestructiveScannerTest` passes — one test per rule (positive +
   negative). Specifically:
   - `saveAs("PNG", "AI_Exports/figure.png")` → allow
   - `saveAs("PNG", "/raw/cell.lif")` → block
   - `roiManager("reset")` with 50 ROIs → allow with backup written
   - `roiManager("reset")` with 0 ROIs → allow (no backup needed)
   - Z-project followed by saveAs to original path → block
   - Bit-depth narrowing with Results pending and option ON → block
   - Bit-depth narrowing with option OFF → allow
3. `UndoFrame.macroHasDiskWrites` deleted; all callers route through
   `DestructiveScanner.classify()`.
4. Manual: in Fiji, send each rule's positive case via the agent
   wrapper; verify the structured error reply matches the spec
   shape from `docs/safe_mode/02_destructive_block.md`.

## Known risks

- The microscopy-overwrite rule does a `Files.exists()` check on
  the target path — adds a syscall per `saveAs`. Negligible but log
  if it ever appears in `O` perf budgets.
- ROI auto-backup writes a file silently; user may be surprised.
  Surface in the FrictionLog and the Stage 07 status indicator.
- Calibration/bit-depth runtime checks need rescue-frame integration
  (Stage 03) to actually roll back. If Stage 03 isn't shipped, fall
  back to "block before execution" via macro-string scanning only.
- The ImageJ macro language is permissive — agents can construct
  `saveAs` arguments dynamically (`saveAs("PNG", path)`). Path-tracking
  symbolic execution is Tier 2; for v2, only flag literal-string
  targets and document the limitation in the agent context.
