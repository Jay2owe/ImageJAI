# TCP Command Server Code Review

**Review Date:** 2026-05-03
**File:** `src/main/java/imagejai/engine/TCPCommandServer.java`
**File Size:** ~7100 lines
**Commands:** ~40 main handlers + variants

---

## Findings Summary

- **BUG:** 2
- **ISSUE:** 8
- **INEFFICIENCY:** 3
- **NIT:** 4

**Most Serious:**
1. **[BUG]** `TCPCommandServer.java:2750` — `pushSuppress` never balanced on exception paths; client can orphan event bus suppression state indefinitely.
2. **[BUG]** `TCPCommandServer.java:1994` — Unchecked `getAsInt()` on user-supplied JSON without type validation; NumberFormatException crashes handler.
3. **[ISSUE]** `TCPCommandServer.java:2848` — New `ExecutorService` per request; thread pool never reused, leaking threads under load.

---

## Detailed Findings

### BUG

**[BUG]** `TCPCommandServer.java:2750–3218` — Missing finally block for `pushSuppress("image.*")`

- **Detail:** `handleExecuteMacro` calls `eventBus.pushSuppress("image.*")` at line 2750 but the corresponding `popSuppress` at line 3218 is only reached if no exception fires in the giant try block. If fuzzy validation fails (2695), snapshot fails (2821–2825), or undo capture fails (2738), the exception unwinds without popping. The bus remains suppressed for all subsequent macros on any socket, permanently silencing image.* events until server restart.
- **Fix:** Wrap the entire handler logic including pushSuppress in try, move popSuppress into guaranteed finally.

**[BUG]** `TCPCommandServer.java:1994` — Unvalidated `getAsInt()` on user input

- **Detail:** `handleGetConsole` calls `request.get("tail").getAsInt()` directly without type checking. Malformed input like `{"tail":"not_a_number"}` or `{"tail":null}` throws uncaught NumberFormatException or IllegalStateException, crashing the handler and returning no response.
- **Fix:** Use safe extraction helper: `optInt(request, "tail", GET_CONSOLE_DEFAULT_TAIL)` matching lines 1828–1832.

### ISSUE

**[ISSUE]** `TCPCommandServer.java:2848` — New `ExecutorService` per macro; no thread pool reuse

- **Detail:** `handleExecuteMacro` and `handleRunScript` (3617) create `Executors.newSingleThreadExecutor()` per request, then `shutdownNow()`. Under load (10+ macros/sec), threads spawn faster than reaped, exhausting JVM pool. Idle timeout ~60s keeps threads alive, causing peak thread count 100+. Standard practice is shared bounded pool.
- **Fix:** Use class-level `ExecutorService executor = Executors.newFixedThreadPool(2)` initialized at startup, reused across handlers, shut down in `stop()`.

**[ISSUE]** `TCPCommandServer.java:2653–2670` — No retry logic on 5-second EDT timeout

- **Detail:** `handleGetState` awaits EDT worker up to 5 seconds. On timeout, returns error immediately with no retry or escalation. Under Fiji GC pauses (common), first call fails and agent must manually retry. No exponential backoff or circuit breaker.
- **Fix:** Log warning to friction log; implement retry-with-backoff for EDT operations.

**[ISSUE]** `TCPCommandServer.java:1040–1063` — Swallowed exceptions in post-dispatch telemetry

- **Detail:** Friction logging (1040–1044) and pattern detection (1053–1063) catch exceptions silently. If those subsystems crash, failure is buried and handler appears to succeed, breaking observability.
- **Fix:** Log all caught exceptions: `catch (Exception e) { System.err.println("[ImageJAI-Dedup] " + e); }`.

**[ISSUE]** `TCPCommandServer.java:2933–2937` — Flattened exception type information in ExecutionException

- **Detail:** Worker thread exceptions get wrapped as `"Macro error: " + getMessage()`, losing the exception type (compile error vs runtime error). Structured errors need type information for client branching logic.
- **Fix:** Extract `cause.getClass().getSimpleName()` and include in message or ErrorReply code field.

**[ISSUE]** `TCPCommandServer.java:667–700` — No per-client rate limiting or connection pooling

- **Detail:** Every incoming socket spawns a thread immediately with no client IP rate limits or connection cap. Slowloris attacks can exhaust thread pool; `serverSocket.accept()` has no backlog configured.
- **Fix:** Add per-IP rate limit (max 10 concurrent sockets per subnet) using `ConcurrentHashMap<String, AtomicInteger>` keyed on IP; reject excess with error.

**[ISSUE]** `TCPCommandServer.java:851–855` — Unsubscribe not atomic with ACK frame write

- **Detail:** If `writeFrame` throws on subscribe ACK, listener is unsubscribed (854) asynchronously but client may read partial/corrupted frame and hang waiting for heartbeats that never arrive.
- **Fix:** Wrap ack write and subscription atomically; unsubscribe only after successful write.

**[ISSUE]** `TCPCommandServer.java:3550–3618` — Inconsistent timeout handling in run_script vs execute_macro

- **Detail:** `handleRunScript` has `final long scriptTimeoutMs = resolveTimeoutMs(...)` but the variable never assigned in method body; falls back to hardcoded PIPELINE_TIMEOUT_MS. Per-request timeout override silently ignored.
- **Fix:** Explicitly resolve timeout at method start: `scriptTimeoutMs = resolveTimeoutMs(request, PIPELINE_TIMEOUT_MS)`.

### INEFFICIENCY

**[INEFFICIENCY]** `TCPCommandServer.java:1465–1476` — TreeMap allocation in every readonly dedup hash

- **Detail:** `canonicalise()` allocates a `TreeMap` per JsonObject at every recursion level, called on every readonly poll. For large objects (histograms, results tables), 10–100 allocations per hash computation. Called repeatedly in dedup checks.
- **Fix:** Cache canonicalized form as transient field in result, or use single pre-sorted serialization.

**[INEFFICIENCY]** `TCPCommandServer.java:3270–3276` — Repeated unchecked try/catch in dialog snapshots

- **Detail:** `safeDetectOpenDialogs()` called 5+ times per macro, silently swallows all AWT exceptions without logging. Transient bugs (window off-screen) fail silently, degrading robustness.
- **Fix:** Log exceptions on first occurrence per request.

**[INEFFICIENCY]** `TCPCommandServer.java:448` — Volatile field `cached3DUniverse` never invalidated

- **Detail:** Holds 3D Viewer universe across TCP calls, indefinitely preventing GC if universe deleted in UI. No weak reference or TTL.
- **Fix:** Use weak reference or add TTL cache evicting after 1 hour of last use.

### NIT

**[NIT]** `TCPCommandServer.java:108` — Silent failure in timeout_ms parsing

- **Detail:** Exception swallowing in `resolveTimeoutMs()` returns default without logging. Should warn if parse fails.
- **Fix:** Log diagnostic: `catch (Exception e) { System.err.println("[ImageJAI-TCP] timeout_ms parse failed: " + e); }`.

**[NIT]** `TCPCommandServer.java:2684` — Misleading error message

- **Detail:** `"Missing 'code' field for execute_macro"` is ambiguous for other contexts.
- **Fix:** Clarify: `"execute_macro: missing 'code' field (JSON string with macro source)"`.

**[NIT]** `TCPCommandServer.java:1617` — Silent hash failure in md5Hex

- **Detail:** Exception silently returns empty string, causing dedup to fail silently without warning.
- **Fix:** Return sentinel like `"ERROR"` and log failure so dedup layer can detect and skip.

**[NIT]** `TCPCommandServer.java:2985–3016` — Inconsistent result shape (output vs error fields)

- **Detail:** Success returns `output: <string>`, error returns `error: <string|object>`. Client must handle two shapes.
- **Fix:** Always emit both fields; leave one empty on opposite condition.
