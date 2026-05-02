# Stage 05 — FrictionLog JSONL persistence

## Why this stage exists

`FrictionLog` is the first place a Local Assistant miss lands —
v1 wired it as the place to record "I don't recognise" events.
Right now it's a 100-entry in-memory ring buffer that vanishes
when the JVM dies, so misses from yesterday's session are gone
when the developer wants to mine them. v2's `/improve` flow (stage
06) needs durable miss data.

This stage adds an async JSONL writer (`FrictionLogJournal`) that
appends every `record()` call to `~/.imagej-ai/friction.jsonl` and
rotates the file at ~10 MB. The in-memory ring buffer stays as
the fast-access overlay for `get_friction_log` TCP queries.

## Prerequisites

None. Independent of stages 01-04.

## Read first

- `docs/local_assistant_v2/00_overview.md`
- `docs/local_assistant_v2/PLAN.md` §9 ("Stage 05 — FrictionLog
  JSONL persistence")
- `docs/local_assistant_v2/BRAINSTORM.md` §6
- `src/main/java/imagejai/engine/FrictionLog.java` —
  the entire file. Note especially:
  - `record(String agentId, String command, String argsSummary,
    String error)` at line 82 (primary write path)
  - `record(String command, String argsSummary, String error)`
    at line 99 (back-compat overload)
  - `FailureEntry` at line 32 — fields `ts`, `agentId`, `command`,
    `argsSummary`, `error`, `normalisedError`
- `src/main/java/imagejai/engine/TCPCommandServer.java`
  — find the `get_friction_log`, `get_friction_patterns`,
  `clear_friction_log` handlers. Stage 05 must not break these.
- The existing path of `intent_mappings.json` writes in
  `IntentRouter` — same `~/.imagej-ai/` directory pattern. Look
  at how that file's directory is created so the journal uses the
  same idiom.

## Scope

- New `FrictionLogJournal.java` in
  `imagejai.engine`. Owns:
  - A single-thread `ExecutorService` (`Executors.newSingleThreadExecutor`)
    with a daemon thread factory.
  - A bounded write queue (the executor's task queue, capped via
    a `RejectedExecutionHandler` that drops oldest on overflow —
    we'd rather drop a friction entry than block the matcher).
  - A `Gson` instance for serialisation.
  - The path `~/.imagej-ai/friction.jsonl`.
- `FrictionLogJournal.append(FailureEntry e)` — non-blocking.
  Submits a task that writes one JSON line, flushing per write.
- `FrictionLogJournal.streamEntries()` — synchronous read of all
  rows across `friction.jsonl` and the rotated generations.
  Returns a `Stream<FailureEntry>` so stage 06 can lazily
  consume.
- Rotation: file ≥ 10 MB → rename to `friction.jsonl.1`. Existing
  `.1` shifts to `.2`, etc. Cap at 5 generations; drop the
  oldest. Rotation runs on the writer thread only (single
  threaded by construction).
- Modify `FrictionLog` to accept an injected journal:
  - `setJournal(FrictionLogJournal j)` (or constructor injection
    with a default null).
  - On every `record(...)` call, after the in-memory write, call
    `journal.append(entry)` if non-null.
  - The two-arg / three-arg / four-arg `record()` overloads all
    converge into one place that does the journal call — don't
    duplicate.
- Wire the journal at startup: in the place where `FrictionLog`
  is constructed for the running plugin (likely
  `TCPCommandServer` or `ImageJAIPlugin`), construct a
  `FrictionLogJournal` and call `setJournal(...)`. Unit tests
  continue to construct `FrictionLog()` without a journal — same
  back-compat path that exists today.
- `FrictionLog.clear()` should also clear the on-disk journal
  (truncate `friction.jsonl` and delete rotated generations) when
  a journal is wired. Or — alternative — `clear()` only touches
  the ring buffer, and the journal is treated as an append-only
  audit log. **Pick the audit-log behaviour**: don't truncate the
  journal on `clear()`. Justify in a code comment.

## Out of scope

- Writing to JSONL format other than UTF-8 LF. No CRLF, no BOM.
- Pretty-printing. One JSON object per line.
- Compression of rotated files. Plain text — they're small.
- Querying the journal via TCP. `get_friction_log` stays on the
  ring buffer; new queries are stage 06's `/improve` slash
  command, which reads the journal directly.
- Network sync, telemetry, or any path that leaves the local
  machine. Privacy is a feature.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/imagejai/engine/FrictionLogJournal.java` | NEW | Async JSONL writer with rotation |
| `src/main/java/imagejai/engine/FrictionLog.java` | MODIFY | Inject journal, hook on `record(...)` |
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Wire journal at construction (or wherever `FrictionLog` is owned) |
| `src/test/java/imagejai/engine/FrictionLogJournalTest.java` | NEW | Append, rotate, read-back, durability |

## Implementation sketch

```java
// FrictionLogJournal.java
public class FrictionLogJournal {
    public static final long ROTATE_BYTES = 10L * 1024 * 1024;
    public static final int  MAX_GENERATIONS = 5;
    public static final Path ROOT = Paths.get(
            System.getProperty("user.home"), ".imagej-ai");
    public static final Path FILE = ROOT.resolve("friction.jsonl");

    private final ExecutorService writer;
    private final Gson gson = new Gson();

    public FrictionLogJournal() {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "imagej-ai-friction-journal");
            t.setDaemon(true);
            return t;
        };
        this.writer = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1024),
                tf,
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    public void append(FrictionLog.FailureEntry entry) {
        writer.execute(() -> writeLine(entry));
    }

    public Stream<FrictionLog.FailureEntry> streamEntries() throws IOException {
        // Read FILE plus FILE.1 through FILE.5 (newest file first
        // so older entries appear later in the stream — or reverse
        // the order; either is fine, but document the choice).
        ...
    }

    private void writeLine(FrictionLog.FailureEntry e) {
        try {
            Files.createDirectories(ROOT);
            if (Files.exists(FILE) && Files.size(FILE) >= ROTATE_BYTES) {
                rotate();
            }
            try (BufferedWriter w = Files.newBufferedWriter(FILE,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                w.write(gson.toJson(toMap(e)));
                w.write('\n');
            }
        } catch (IOException ignored) {
            // disk full / permission denied — fall back silently
        }
    }

    private void rotate() throws IOException {
        for (int i = MAX_GENERATIONS - 1; i >= 1; i--) {
            Path src = ROOT.resolve("friction.jsonl." + i);
            Path dst = ROOT.resolve("friction.jsonl." + (i + 1));
            if (Files.exists(src)) {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(FILE, ROOT.resolve("friction.jsonl.1"),
                   StandardCopyOption.REPLACE_EXISTING);
    }

    private Map<String, Object> toMap(FrictionLog.FailureEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", e.ts);
        m.put("agent_id", e.agentId);
        m.put("command", e.command);
        m.put("args_summary", e.argsSummary);
        m.put("error", e.error);
        m.put("normalised_error", e.normalisedError);
        return m;
    }
}
```

```java
// FrictionLog.java additions
private FrictionLogJournal journal;

public synchronized void setJournal(FrictionLogJournal j) {
    this.journal = j;
}

// In record(String agentId, String command, String argsSummary, String error):
FailureEntry e = new FailureEntry(System.currentTimeMillis(), agentId,
                                  command, argsSummary, error);
if (entries.size() >= CAPACITY) entries.removeFirst();
entries.addLast(e);
if (journal != null) journal.append(e);   // NEW
```

```java
// TCPCommandServer (or wherever FrictionLog lives)
this.frictionLog = new FrictionLog();
this.frictionLog.setJournal(new FrictionLogJournal());
```

## Exit gate

1. `mvn -q test` passes.
2. `IntentMatcherBenchmarkTest` accuracy ≥97.4% (no regression).
3. `FrictionLogJournalTest` covers:
   - `append(...)` is non-blocking — measure that the call
     returns within ~1 ms (use a synchronisation latch in the
     writer task).
   - Rotation: write enough entries to cross 10 MB, verify
     `friction.jsonl.1` appears and the live file is back under
     1 MB.
   - Five-generation cap: write enough to force rotation 7 times,
     verify `friction.jsonl.6` and `.7` don't exist; only `.1`-
     `.5` remain.
   - Read-back: write 100 entries via `append`, await executor
     idle, call `streamEntries()` → see all 100.
   - Crash safety: write 50 entries, simulate JVM stop by
     re-creating the journal in a new instance, verify
     `streamEntries()` still sees the 50.
   - Permission denied: point `ROOT` at a non-writable path via a
     test seam → `append()` does not throw.
4. After running 30+ misses through `LocalAssistant`,
   `~/.imagej-ai/friction.jsonl` exists and contains valid JSONL
   (one parseable JSON object per line).
5. `FrictionLog.clear()` clears the in-memory ring buffer but
   does NOT delete `friction.jsonl` (audit-log behaviour). Verify
   with a test.
6. The TCP `get_friction_log` and `get_friction_patterns`
   handlers continue to return ring-buffer data only (do not
   query the journal). Existing tests for these handlers must
   pass.

## Known risks

- **Disk-full / permission-denied.** The writer must not crash
  the matcher hot path. Catch IOException in `writeLine`, drop
  the entry, log nothing (we're inside friction logging — don't
  log a friction event about friction logging). The in-memory
  ring buffer continues to work.
- **Daemon thread doesn't flush at JVM exit.** A daemon thread
  can be killed mid-write. Two mitigations:
  - `flush()` after each line (BufferedWriter try-with-resources
    closes per line — already done).
  - On `LocalAssistant` shutdown (or via a JVM shutdown hook),
    call `writer.shutdown()` and `awaitTermination(2, SECONDS)`
    so pending writes drain. Add a `close()` method on the
    journal and wire it.
- **Rotation under contention.** Single-thread executor — no
  contention by construction. Don't try to add concurrent
  writers later without redoing the rotation logic.
- **Unbounded queue growth.** Capped at 1024 with
  `DiscardOldestPolicy`. If misses pile up faster than disk can
  absorb, we lose oldest queued entries — the user will see this
  as a small undercount in `/improve`. Better than blocking the
  matcher.
- **Path on Windows.** `~/.imagej-ai` resolves to
  `C:\Users\<user>\.imagej-ai` via `System.getProperty("user.home")`.
  Verify on the dev machine; the existing `IntentRouter` already
  uses this path successfully (see
  `~/.imagej-ai/intent_mappings.json` in
  `IntentRouter.java:38`).
- **Test-only path injection.** The `ROOT` constant should be
  override-able for tests (constructor-arg `Path root` defaulting
  to `Paths.get(System.getProperty("user.home"), ".imagej-ai")`).
  Don't pollute the developer's real friction log with test
  data.
