# 04 — Audit log CSV

## Why this stage exists

The audit log is the single most convincing artefact for a PI or REC
reviewer. It turns "we have a privacy mode" into "here is the log of
exactly what left this machine, when, and how it was protected." It
is the trust signal that makes the supervisor's concern answerable
in writing.

It is also load-bearing for stage 06's PDF generator — the Data
Handling Statement summarises audit metadata — and is consumed by
stage 05's Receipts pane.

## Prerequisites

- Stage 02 (`RedactionReport`, `_governance` block).

## Read first

- `docs/data-governance/00_overview.md`.
- `src/main/java/imagejai/engine/TCPCommandServer.java` — the
  dispatch site, where `RedactionReport` is already in scope.
- `src/main/java/imagejai/config/Constants.java` — where
  `AI_Exports/` lives.
- Stage 02's `RedactionReport` definition.

## Scope

- `AuditLog` writer appending one row per outbound TCP response to
  `AI_Exports/imagejai_audit.csv`.
- Append-only with `FileLock` to handle two Fiji instances on a
  shared microscope PC.
- Async write — does not block the response path.
- "View Audit Log" button in the launcher panel opens the CSV via
  `Desktop.open`.
- New row types beyond plain TCP commands:
  - `request_visual` grants / denies / consumes
  - `browse_pending_brief` polls (volume sanity check)
  - `get_pending_brief` retrievals (records token list + tag)
  - `open_image_by_token` resolutions
  - Posture downshift events + logged overrides
- Columns (exact wording — these are the user-visible CSV header):

  ```
  timestamp_utc, session_id, command, posture, model_endpoint,
  capture_source, bytes_out, bytes_in, image_hash, redaction_applied,
  fields_redacted, notes
  ```

Column semantics:

- `timestamp_utc`: ISO-8601, e.g. `2026-05-21T14:32:18Z`.
- `session_id`: short opaque token minted when launcher starts an
  agent (UUID4 truncated to 8 hex).
- `command`: TCP command name OR governance event name
  (`posture.downshift`, `visual.granted`, `visual.denied`,
  `visual.consumed`).
- `posture`: `Standard` / `Pseudonymised` / `On-premises`.
- `model_endpoint`: best-effort vendor.product string —
  `anthropic.claude-code`, `openai.codex`, `google.gemini-cli`,
  `ollama.local:gemma3:27b`, `ollama.cloud:gemma4:31b-cloud`.
- `capture_source`: blank for non-capture commands; otherwise
  `active_image_content` / `active_image_with_overlay` /
  `dialog_screenshot` / `window_screenshot` / `desktop_screenshot`.
- `bytes_out` / `bytes_in`: response / request body byte counts.
- `image_hash`: sha256 of front image (or blank).
- `redaction_applied`: `true` / `false`.
- `fields_redacted`: semicolon-separated list (matches the
  `_governance.fields_pseudonymised` block from stage 02).
- `notes`: free text — override reasons, posture-violation refusals,
  visual-override stated reason, brief token lists.

## Out of scope

- The PDF generator (stage 06).
- The Receipts pane UI (stage 05) — it subscribes to `AuditLog`
  but is not built here.
- Rotation / archival of old audit logs.

## Files touched

| Path | NEW/MODIFY | Reason |
|------|------------|--------|
| `src/main/java/imagejai/engine/security/AuditLog.java` | NEW | CSV writer |
| `src/main/java/imagejai/engine/security/AuditRow.java` | NEW | Row value object |
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Append rows after every dispatch |
| `src/main/java/imagejai/engine/AgentLauncher.java` | MODIFY | Mint `session_id`, pass to server; add "View Audit Log" button |
| `src/main/java/imagejai/engine/security/VisualOverrideRegistry.java` | MODIFY (from stage 02) | Emit grant/deny/consume audit rows |
| `src/main/java/imagejai/engine/PostureController.java` | MODIFY (from stage 01) | Emit downshift audit rows |
| `src/test/java/imagejai/engine/security/AuditLogTest.java` | NEW | Header, format, concurrency tests |

## Implementation sketch

`AuditRow`:

```java
public record AuditRow(
    Instant timestampUtc,
    String sessionId,
    String command,
    PrivacyPosture posture,
    String modelEndpoint,
    String captureSource,        // "" for non-capture
    int bytesOut,
    int bytesIn,
    String imageHash,
    boolean redactionApplied,
    List<String> fieldsRedacted,
    String notes
) {
    public String toCsvLine() { ... }
}
```

`AuditLog`:

```java
public final class AuditLog {
    private static final String HEADER =
        "timestamp_utc,session_id,command,posture,model_endpoint," +
        "capture_source,bytes_out,bytes_in,image_hash,redaction_applied," +
        "fields_redacted,notes\n";
    private final Path csvPath;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    public void append(AuditRow row) {
        writer.submit(() -> writeSync(row));
    }
    private void writeSync(AuditRow row) {
        ensureHeader();
        try (FileChannel ch = FileChannel.open(csvPath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
             FileLock fl = ch.lock()) {
            ch.write(ByteBuffer.wrap((row.toCsvLine() + "\n").getBytes(UTF_8)));
        } catch (IOException e) {
            // retry once with a sidecar file path:
            // imagejai_audit.<hostname>.csv
        }
    }
    public void open() throws IOException {
        Desktop.getDesktop().open(csvPath.toFile());
    }
    public Stream<AuditRow> recent(int n) { ... } // for Receipts pane (stage 05)
}
```

Hook site in `TCPCommandServer.dispatchInternal`:

```java
RedactionReport report = filter.apply(response, command, postureController.current());

auditLog.append(new AuditRow(
    Instant.now(),
    session.id(),
    command,
    postureController.current(),
    session.modelEndpoint(),
    response.optString("source", ""), // for capture_image; else blank
    response.toString().length(),
    request.length(),
    currentImageHash(),
    report.applied(),
    report.fieldsRedacted(),
    ""
));
out.write(response.toString());
```

Governance event rows (from stage 01 and stage 02 emitters):

- `posture.downshift` — notes column: `"from=Standard to=Pseudonymised folder=MOAB2_AF488/"`.
- `posture.override` — notes column: `"reason='one-off check'"`.
- `visual.granted` — notes column: `"reason='check focus on image-7a3f.lif'"`.
- `visual.consumed` — notes column: `"on image-7a3f.lif"`.

## Exit gate

1. `mvn compile`, `mvn test` pass.
2. `AuditLogTest` covers:
   - First append writes header then row.
   - Subsequent appends do not re-write header.
   - 2 threads × 100 rows each → exactly 200 well-formed lines.
   - CSV escape: notes containing `,` and `"` round-trip.
   - `capture_source` populated correctly for capture rows, blank
     otherwise.
3. Manual: Pseudonymised mode, run a command → CSV gains row with
   `redaction_applied=true`, `fields_redacted` lists fields,
   `_governance` block in JSON matches `fields_redacted` in CSV.
4. Manual: Standard mode, same command → row with
   `redaction_applied=false`, blank `fields_redacted`.
5. Manual: click "View Audit Log" → CSV opens in OS default app.
6. Manual: trigger a posture downshift → `posture.downshift` row
   appears.
7. Manual: trigger a `request_visual` grant → `visual.granted` row;
   on next `capture_image`, `visual.consumed` row appears.
8. CSV header line matches the documented column order exactly.

## Known risks

- File locking on Windows Dropbox-synced folders can be flaky. On
  `FileLock` failure within 1s, retry 3×; on persistent failure,
  append to `imagejai_audit.<hostname>.csv` rather than dropping.
- Audit write must not block the TCP response — already async via
  single-threaded executor; add shutdown hook to flush on exit.
- Only `notes` should ever need CSV escaping; assert this in a
  test rather than escaping defensively.
- The Receipts pane (stage 05) must read from `AuditLog.recent(n)`,
  not from disk on every refresh — the executor model already
  supports this.
