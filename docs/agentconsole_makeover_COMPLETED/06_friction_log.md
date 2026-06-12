# Phase 6 — Friction Log

## Motivation

Agents today fail silently. A macro returns `success: false`, the agent logs it locally, retries maybe three times, gives up, and the **next session starts from scratch** — making the same mistake again. `autopsy.py` on the client side captures some of this, but lives in the external agent's filesystem, not in the plugin, so it's invisible to any other Claude or external tool.

AgentConsole's commander context (`.claude/agents/ai-commander.md` §Friction-Driven Improvement) makes this explicit: *"When an action takes more than 2-3 attempts, or you resort to exec introspection to get data a command should surface, or something fails silently — the tool is broken, not you. Stop grinding."* The commander is expected to **record the friction, finish with a workaround, and propose a fix**.

For the TCP surface, the analogue is a **server-side ring buffer of recent failures** that any agent can query.

Metaphor: a black-box recorder. After a crash, someone can actually read it and learn.

## End Goal

- Server maintains a rolling log of the last N=100 failed commands: `{ts, command, args_summary, error, context_snapshot}`.
- Detects **recurring failure patterns** — same command + same error text repeated ≥3 times within 10 minutes → emits `friction.pattern_detected` on the bus (Phase 2).
- New TCP commands:
  - `get_friction_log {"limit": 20}` — last N failures, newest first.
  - `get_friction_patterns` — recurring patterns with counts.
  - `clear_friction_log` — reset.
- Agent UX: `python ij.py friction` dumps the log human-readably.

## Scope

### Server-side: `engine/FrictionLog.java` (new)

- Thread-safe bounded `ArrayDeque` of `FailureEntry` (cap 100).
- `record(command, args, error, state)` — called by `TCPCommandServer` on every non-`ok` response.
- Pattern detector: group by `(command, normalised_error)`; when count ≥3 within a 10-min window, emit event.

### Server-side: `engine/TCPCommandServer.java`

- After dispatching any command, inspect the response. If `ok == false`, call `frictionLog.record(...)`.
- New `get_friction_log`, `get_friction_patterns`, `clear_friction_log` commands — readonly fast-path (Phase 1 eligible).

### Client-side: `agent/ij.py`

- `python ij.py friction [--limit N]` — dumps last N failures.
- `python ij.py friction --patterns` — shows recurring patterns.

### Client-side: context hook

- On session start, call `get_friction_patterns`. If any exist with hit count ≥3, inject a small `[KNOWN FRICTION]` block so Claude sees "you keep failing at X" at the start of the next conversation.

## Implementation Sketch

```java
// FrictionLog.java
public class FrictionLog {
    private static final int CAPACITY = 100;
    private final Deque<FailureEntry> entries = new ArrayDeque<>();

    public synchronized void record(String cmd, String argsSummary, String error) {
        FailureEntry e = new FailureEntry(System.currentTimeMillis(), cmd, argsSummary, error);
        if (entries.size() >= CAPACITY) entries.removeFirst();
        entries.addLast(e);
        checkPattern(cmd, normaliseError(error));
    }

    public List<Pattern> patterns() {
        Map<String, Long> counts = entries.stream()
            .filter(e -> e.ts > System.currentTimeMillis() - 600_000)
            .collect(Collectors.groupingBy(
                e -> e.command + "::" + normaliseError(e.error),
                Collectors.counting()));
        return counts.entrySet().stream()
            .filter(en -> en.getValue() >= 3)
            .map(Pattern::from)
            .collect(Collectors.toList());
    }
}
```

## Impact

- A confused agent can ask its own plugin *"what have I been failing at?"* — self-diagnosis becomes trivial.
- Recurring patterns surface instead of being discovered fresh each session.
- Combined with Phase 5 (intent mappings): if a pattern like "user keeps typing X, agent keeps misinterpreting it" recurs, the fix is an intent teach — visible and actionable.
- Cross-agent visibility: a second Claude instance sees the friction from the first.

## Validation

1. Execute a known-bad macro 3 times → `get_friction_patterns` shows 1 entry with count 3.
2. `get_friction_log` entries include enough context (command, normalised error) to be useful without bloat.
3. Clear log → subsequent queries empty.
4. Context hook sees patterns on session start → `[KNOWN FRICTION]` block appears in next conversation.

## Risks

- **Error normalisation is imprecise.** Two "different" errors (with different file paths) may be the same underlying issue. Mitigation: strip paths, numbers, line numbers when computing the pattern key; keep the original error in the entry.
- **Log as telemetry leak.** If users share the plugin, the log contains their failure history. Mitigation: store in-memory only; `clear_friction_log` on plugin shutdown; never persist to disk.
- **Noise vs. signal.** Every trivial user typo counts as "friction". Mitigation: the pattern detector requires ≥3 repetitions within 10 min — one-off mistakes don't surface.
