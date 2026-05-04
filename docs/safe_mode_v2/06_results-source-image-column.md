# Stage 06 — Auto-add `Source_Image` column to Results table

Whenever the active image changes, automatically add (or backfill) a
`Source_Image` column on every new row written to the Results table.
Eliminates cross-image contamination ambiguity by making provenance
intrinsic to every measurement row.

## Why this stage exists

Biologists legitimately measure across many images and aggregate into
one Results table for downstream stats. The naive "warn on
cross-contamination" approach blocks this real workflow. Better:
guarantee that every row carries its source-image label, so an
aggregated table is filterable and a wrong-image-mixup is visible
rather than silent. Turns a guard into a free affordance.

## Prerequisites

- Stage 01 (`_COMPLETED`)
- Stage 02 (`_COMPLETED`) — needs `caps.safeModeOptions.autoSourceImageColumn`

## Read first

- `docs/safe_mode_v2/00_overview.md`
- `agent/references/image-integrity-reference.md` (provenance section)
- `src/main/java/imagejai/engine/TCPCommandServer.java`:
  - `handleGetResultsTable` — find the existing Results read path
  - `MacroAnalyser.PostExec` — the post-exec hook from Stage 05
- ImageJ `ij.measure.ResultsTable` API — `setValue(String column, int
  row, String value)`, `getColumn(String)`, `getCounter()`

## Scope

- Hook the post-exec path (`MacroAnalyser.PostExec` or equivalent) to:
  1. Compare `ResultsTable.getCounter()` before vs after the macro.
  2. For every row added (`rowsAfter > rowsBefore`), call
     `ResultsTable.setValue("Source_Image", rowIdx, activeImageTitle)`.
  3. Update ResultsWindow display (`ResultsTable.show("Results")`).
- Also handle the case where the active image changed mid-macro
  (rare — the macro called `selectImage` between two `Measure` calls).
  For v2, tag every new row with the *final* active image after the
  macro completes; document the limitation.
- Agent escape hatch: macro comment `// @safe_mode allow:
  legacy_results_format` skips the column injection for that call.

## Out of scope

- Backfilling pre-existing rows that were measured before this stage
  shipped (they get null in the new column — fine).
- Rich provenance (call ID, timestamp, agent ID) — that's
  Tier 2/3 work; the v2 column is just `Source_Image` string.
- Validation that the user's downstream analysis tooling handles
  the new column gracefully — they can ignore it; it's just a
  trailing column.

## Files touched

| Path | Change | Reason |
|------|--------|--------|
| `src/main/java/imagejai/engine/safeMode/SourceImageTagger.java` | NEW | Encapsulates the rowsBefore/After bookkeeping + column write |
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Wire `SourceImageTagger` into `handleExecuteMacro` (pre/post hooks around the existing call) |
| `src/main/java/imagejai/engine/MacroAnalyser.java` | MODIFY (if needed) | Expose `rowsBefore` in `PostExec` so the tagger has the delta |
| `src/test/java/imagejai/engine/safeMode/SourceImageTaggerTest.java` | NEW | Verify column added, escape hatch honoured |

## Implementation sketch

```java
public final class SourceImageTagger {
    private int rowsBefore;
    private String tagTitle;

    public void preExec(ImagePlus activeImp) {
        ResultsTable rt = ResultsTable.getResultsTable();
        rowsBefore = (rt != null) ? rt.getCounter() : 0;
        tagTitle = (activeImp != null) ? activeImp.getTitle() : "<none>";
    }

    public void postExec(ImagePlus activeImpAfter) {
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt == null) return;
        int rowsAfter = rt.getCounter();
        if (rowsAfter <= rowsBefore) return;
        // Active image may have changed during the macro;
        // for v2, tag with the FINAL active image's title.
        String label = (activeImpAfter != null)
            ? activeImpAfter.getTitle() : tagTitle;
        for (int r = rowsBefore; r < rowsAfter; r++) {
            rt.setValue("Source_Image", r, label);
        }
        rt.show("Results");
    }
}
```

Wired in `handleExecuteMacro`:
```java
SourceImageTagger tagger = (caps.safeMode
        && caps.safeModeOptions.autoSourceImageColumn
        && !macroOptsOut(code, "legacy_results_format"))
    ? new SourceImageTagger() : null;
if (tagger != null) tagger.preExec(activeImp());
try {
    // ... existing macro execution ...
} finally {
    if (tagger != null) tagger.postExec(activeImp());
}
```

## Exit gate

1. `mvn compile` clean.
2. `SourceImageTaggerTest` passes:
   - Macro that adds 3 rows on `imageA` → all 3 rows have
     `Source_Image == "imageA"`.
   - Macro on `imageB` immediately after → next 3 rows have
     `Source_Image == "imageB"`; first 3 unchanged.
   - Macro with `// @safe_mode allow: legacy_results_format` → no
     column added.
   - Guard off → no column added.
3. Manual: in Fiji, measure two images via the agent, open
   Results window, confirm `Source_Image` column present and
   correctly populated. Save Results as CSV, confirm column persists.

## Known risks

- Some plugins (e.g. `Analyze Particles`) write directly to
  `ResultsTable` from native code — the column write happens AFTER
  the macro returns, so all rows added during the macro get the
  same `Source_Image` label. If the agent switched images mid-macro,
  attribution is approximate. Document this as a v2 limitation.
- Existing user macros that write CSV with a fixed column order may
  break if they assume the trailing column is always `Mean`/`Area`.
  Mitigate via the `legacy_results_format` opt-out annotation; flag
  in `agent/CLAUDE.md` so agents know it exists.
- Performance: one column write per added row. Negligible (~µs per
  row), no measurable hit even on 10k-row tables.
