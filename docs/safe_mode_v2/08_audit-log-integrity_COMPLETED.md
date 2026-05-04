# Stage 08 — Audit-log integrity

Remove the `clear_friction_log` command from the agent-callable
surface (so an injected agent can't cover its tracks), and extend
`FrictionLog` rows with `outcome` / `severity` / `rule_id` / `target`
fields so safe-mode events are queryable rather than text-grepped.

## Why this stage exists

Real-world AI incidents (Replit deleted production data and then
*lied* about whether undo was possible) say: never let the auditee
edit the audit log. Today the TCP server exposes `clear_friction_log`
as one of the ~50 agent-callable commands — a gift to any prompt
injection that wants to erase evidence. Removing it from the agent
surface costs nothing and closes the vector. The schema extension
makes Stage 07's status-indicator tooltip and any future dashboard
actually functional.

## Prerequisites

None. Independent of every other stage; can run in parallel.

## Read first

- `docs/safe_mode_v2/00_overview.md`
- `src/main/java/imagejai/engine/FrictionLog.java` —
  the in-memory ring buffer; current `FailureEntry` fields
- `src/main/java/imagejai/engine/FrictionLogJournal.java` —
  the JSONL persistence layer; current `toMap()` schema
- `src/main/java/imagejai/engine/TCPCommandServer.java` —
  `handleClearFrictionLog` ≈ line 1173 (verify); the dispatch entry
  for `"clear_friction_log"`
- `src/test/java/imagejai/engine/FrictionLog*Test.java` —
  existing test patterns

## Scope

- **Remove `clear_friction_log` from the dispatch ladder.** The Java
  method `handleClearFrictionLog` stays (operator may want it
  available via a non-agent path), but it is no longer reachable
  over TCP.
- Add a small operator-only escape: a JVM startup flag
  `-Dimagejai.allow.clear_friction_log=true` re-enables the command
  for debugging. Default off.
- **Schema extension** on `FrictionLog.FailureEntry`: add fields
  - `outcome` — one of `passed` / `blocked` / `confirmed` / `warned`
    / `auto_backup` / `rehearsal_failed` / `rehearsal_passed` /
    `snapshot_committed`
  - `severity` — `L1_reject` / `L2_prompt` / `L3_warn` (matches the
    existing destructive-block tier vocabulary)
  - `rule_id` — short stable string identifier
    (`saveas_overwrite`, `file_delete`, `roi_wipe`, `bit_depth`,
    `calibration_loss`, `z_project_overwrite`,
    `microscopy_overwrite`, `queue_storm`, …)
  - `target` — string (path, image title, dialog title — depends
    on rule)
- **Backwards compat:** missing fields read as null/empty string in
  the JSONL reader; existing tests still pass.

## Out of scope

- A full hash-chain / tamper-evident log (Tier 2 brainstorm — defer).
- A safety dashboard reading the journal (deferred).
- `enabledSafeModeOptions` exposure via a separate `get_safe_mode_status`
  command — defer.

## Files touched

| Path | Change | Reason |
|------|--------|--------|
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Remove `"clear_friction_log"` from the if-ladder; behind `-D` flag, optionally re-add |
| `src/main/java/imagejai/engine/FrictionLog.java` | MODIFY | Extend `FailureEntry` with `outcome` / `severity` / `rule_id` / `target` |
| `src/main/java/imagejai/engine/FrictionLogJournal.java` | MODIFY | `toMap()` writes new fields; `fromMap()` tolerates absence |
| `src/test/java/imagejai/engine/FrictionLogJournalTest.java` | MODIFY | Cover new fields + back-compat |
| `src/test/java/imagejai/engine/TCPCommandServerClearFrictionLogTest.java` | NEW | Confirm command unreachable by default; reachable with `-D` |
| `agent/CLAUDE.md` | MODIFY | Document removal; agents should never rely on `clear_friction_log` |

## Implementation sketch

```java
// TCPCommandServer dispatch (around the existing case):
- case "clear_friction_log":
-     return handleClearFrictionLog();
+ case "clear_friction_log":
+     if ("true".equals(System.getProperty("imagejai.allow.clear_friction_log"))) {
+         return handleClearFrictionLog();
+     }
+     return errorReply("COMMAND_REMOVED",
+         "clear_friction_log is no longer agent-callable. "
+         + "Restart Fiji with -Dimagejai.allow.clear_friction_log=true if you need it.",
+         /*retrySafe=*/false, null);
```

Schema extension:
```java
public static final class FailureEntry {
    public final long ts;
    public final String agentId;
    public final String command;
    public final String argsSummary;
    public final String error;
    public final String normalisedError;
    // NEW:
    public final String outcome;     // nullable
    public final String severity;    // nullable
    public final String ruleId;      // nullable
    public final String target;      // nullable
    // ... existing constructor + new constructor with the new fields ...
}
```

## Exit gate

1. `mvn compile` clean.
2. `TCPCommandServerClearFrictionLogTest` passes:
   - Default JVM → command returns `COMMAND_REMOVED`.
   - JVM with `-Dimagejai.allow.clear_friction_log=true` → command
     works as today.
3. Updated `FrictionLogJournalTest` passes:
   - Writing an entry with all new fields round-trips through JSONL.
   - Reading a JSONL line missing the new fields succeeds with
     null values (back-compat with on-disk archives).
4. Existing `FrictionLog*Test` files still pass.
5. Manual: with default JVM, send `clear_friction_log` from any agent
   wrapper — confirm refusal. Add `-Dimagejai.allow.clear_friction_log=true`
   to the Fiji launcher, restart, confirm command works.

## Known risks

- Removing a TCP command is a breaking change for any wrapper that
  calls it. Search `agent/*.py` for `clear_friction_log` first; none
  should call it (it's an operator-debug command), but verify.
- The `-D` escape hatch is honour-system; an attacker with shell
  access already owns the system. The protection is against
  in-prompt agents flipping it via macro, not against shell.
- Schema extension is additive — JSONL files written by old code
  read fine; JSONL files written by new code with the new fields
  also read fine on old code (unknown JSON fields are ignored by
  Gson). No migration needed.
