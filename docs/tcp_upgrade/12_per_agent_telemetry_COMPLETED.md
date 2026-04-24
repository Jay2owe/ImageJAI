# Step 12 — Per-Agent Telemetry and Pattern Hints

## Motivation

The `FrictionLog` already records events, but it doesn't know
which agent caused which. We can't answer "which agent hallucinates
plugin names the most?" or "does Gemma keep hitting the nResults
trap even after the warning landed?" without that tag.

Threading `agent_id` (established in step 01) through the
telemetry layer unlocks per-agent dashboards, regression
detection, and a new reply feature: pattern-detection hints that
fire when the server notices an agent repeating a behaviour.

## End goal

Two additions:

1. **`agent_id` on every FrictionLog row.** Already flowing in
   `AgentCaps`; this step propagates it to the log writer.
2. **`PATTERN_DETECTED` hints** — when an agent repeats a
   specific behaviour within a session (e.g. calls
   `close_dialogs` four times in a row with no intervening
   `interact_dialog`, or retries the same macro three times with
   the same error), the next reply carries:

   ```json
   {"hints": [{
     "code": "PATTERN_DETECTED",
     "kind": "close_dialogs_thrashing",
     "message": "You've called close_dialogs 4 times in 30s. Dialogs likely need to be interacted with (interact_dialog), not dismissed.",
     "suggested": [{"command": "get_dialogs"}],
     "severity": "info"
   }]}
   ```

## Scope

In:
- `FrictionLog` accepts `agentId` parameter on writes. Every
  existing call site is updated to pass it through.
- A `SessionStats` map per socket (keyed by command or behaviour
  signature) tracks counts and timestamps.
- A small `PatternDetector` rule set, extensible like
  `MacroAnalyser` (step 06).
- Integration: after every response is built but before it's
  sent, check patterns; append to `hints[]` if fired.

Out:
- Persistent cross-session pattern detection. That's step 14's
  territory (federated ledger).
- Automatic behaviour changes based on patterns (e.g. switching
  to a different tool). Hints only; agent acts on them.

## Read first

- `src/main/java/imagejai/engine/FrictionLog.java`
  — all write methods.
- `src/main/java/imagejai/engine/TCPCommandServer.java`
  — every call to `FrictionLog.log*`.
- `docs/tcp_upgrade/01_hello_handshake.md` — `AgentCaps.agentId`.
- `docs/tcp_upgrade/06_nresults_trap.md` — the `MacroAnalyser`
  pattern this mirrors.

## Initial pattern rule set

Ship these four in Phase 2; add more as telemetry shows patterns:

### `close_dialogs_thrashing`
Trigger: `close_dialogs` called ≥ 4 times within 30 seconds.
Hint: use `interact_dialog` to engage with the dialog instead
of dismissing.

### `get_state_polling`
Trigger: `get_state` called ≥ 5 times within 60 seconds with
zero intervening mutating commands.
Hint: nothing changes between your calls — subscribe to events,
or consult the `pulse` field in macro responses.

### `repeat_error_identical_macro`
Trigger: same `execute_macro` code submitted ≥ 3 times
consecutively, all failing with the same error code.
Hint: this macro will not succeed as written. Inspect the error
`recovery_hint` and change the macro, or call `probe_command` on
any plugin referenced.

### `probe_before_run_missed`
Trigger: `run("Plugin...", ...)` for a plugin that was never
probed this session, called ≥ 2 times with different args, all
failing.
Hint: probe the plugin first with `probe_command` to discover
its valid argument names.

## Mechanics

`SessionStats` per socket:

```java
public final class SessionStats {
    static final class CmdLog {
        String canonicalArgs;
        long timestampMs;
        String responseErrorCode;  // null on success
    }
    final Deque<CmdLog> commandHistory = new ArrayDeque<>();
    // cap at ~50 entries per socket

    public void record(String cmd, String canonicalArgs,
                       long ts, String errCode) {
        commandHistory.addLast(new CmdLog(cmd, canonicalArgs, ts, errCode));
        while (commandHistory.size() > 50) commandHistory.removeFirst();
    }
}
```

Live in `AgentCaps` alongside `dedupCache`.

`PatternDetector`:

```java
public static List<Hint> check(SessionStats stats, long now) {
    List<Hint> hints = new ArrayList<>();
    closeDialogsThrashing(stats, now).ifPresent(hints::add);
    getStatePolling(stats, now).ifPresent(hints::add);
    repeatErrorIdenticalMacro(stats, now).ifPresent(hints::add);
    probeBeforeRunMissed(stats, now).ifPresent(hints::add);
    return hints;
}
```

Each rule pattern:

```java
static Optional<Hint> closeDialogsThrashing(SessionStats s, long now) {
    long cutoff = now - 30_000;
    long recentCount = s.commandHistory.stream()
        .filter(c -> c.cmd.equals("close_dialogs"))
        .filter(c -> c.timestampMs >= cutoff)
        .count();
    if (recentCount < 4) return Optional.empty();
    return Optional.of(new Hint(
        "PATTERN_DETECTED",
        "close_dialogs_thrashing",
        "You've called close_dialogs 4 times in 30s. Dialogs likely need to be interacted with (interact_dialog), not dismissed.",
        "info",
        List.of(Map.of("command", "get_dialogs"))));
}
```

Integration point: at the end of each handler, after the reply
body is built:

```java
List<Hint> patternHints = PatternDetector.check(caps.stats, now);
if (!patternHints.isEmpty()) appendHints(result, patternHints);
caps.stats.record(cmdName, canonicalArgs, now, errCode);
```

## Anti-nag

A pattern rule that fires every turn is noise. Each rule should
self-throttle — e.g. `close_dialogs_thrashing` fires only once
per 5-minute window per session, regardless of how many
consecutive calls. Track last-fired timestamp per rule in
`SessionStats`.

## FrictionLog propagation

Every `FrictionLog.log*` call adds `agent_id` as a field:

```java
FrictionLog.logMacroFailure(caps.agentId, code, error, ts);
```

Existing call sites get a sweep; signature change forces all of
them to be touched once. The `agent_id` also flows into any
JSON-lines log output so downstream tooling (the improvement
loop, dashboards) can group.

## Capability gate

`caps.patternHints` — default `true`. Gemma benefits most; the
hints are short and actionable. Agents can opt out by setting
`false` in `hello`.

## Tests

- Call `close_dialogs` 4× within 30s → pattern fires on 4th
  call, not 3rd.
- Call 4× within 30s, then 5th call 6 minutes later → no fire
  on 5th (throttle still active).
- Call 4× within 30s, then 5th call 35 minutes later → fires
  again (throttle expired).
- Call 3× within 30s then a different command → counter resets;
  calling `close_dialogs` 3 more times does not fire.
- Error-code repeat: same macro 3× with same `PLUGIN_NOT_FOUND`
  → fires.
- Same macro 3× with different error codes → does not fire.
- `FrictionLog` rows include `agent_id` after the sweep.

## Failure modes

- Noisy rules that fire in legitimate workflows. Mitigations:
  conservative thresholds, 5-minute throttle per rule, telemetry
  to prove rules are more helpful than annoying before shipping.
- `SessionStats` memory growth on long sessions. Capped at 50
  entries; older commands don't matter for these rules.
- Rules that fire only on tiny datasets where the behaviour is
  correct (e.g. probe_before_run_missed on a brand-new session
  with no probes yet). Grace period: don't fire the first N
  turns of a new session.
