# Stage 04 — Session code journal (server side)

## Why this stage exists

Every macro or script the agent runs should be silently captured,
auto-named, and saved to disk so the user can re-run or save any
of it with one click later (UI lands in stage 11). "Silently" is
load-bearing: the agent must not see the journal, must not emit
tokens about it, must not discover it via any TCP command or event
stream. This stage delivers the capture engine and auto-namer only
— there is no UI yet, and an agent running externally today will
immediately start leaving a file trail in `AI_Exports/.session/code/`
once this stage lands.

## Prerequisites

- Stage 01 `_COMPLETED`. (This stage does **not** depend on stage
  02 or 03 — it can run in parallel with them.)

## Read first

- `docs/embedded-agent-widget/00_overview.md`
- `docs/embedded-agent-widget/PLAN.md` §2.10 (Session code journal)
- `src/main/java/imagejai/engine/TCPCommandServer.java`:
  - `:116` — `MACRO_ID_SEQ` counter (reuse for journal IDs)
  - `:1153-1175` — `handleExecuteMacro` entry; hook point
    immediately after `final String code = codeElement.getAsString();`
  - `:1777-1800` — `handleRunScript` entry; same hook pattern
  - `:1190` — the `macro.started` event publish (for reference,
    journal entries do **not** go through this)
- `CLAUDE.md` project rule: "Write outputs to `AI_Exports/` next
  to the opened image, never elsewhere."

## Scope

- New `SessionCodeJournal` singleton (thread-safe).
- New `CodeAutoNamer` with cascading strategies:
  1. Leading comment (first 5 non-blank lines, `//` / `#` / `/* */`),
     skip-list for boilerplate framing comments.
  2. Composite `run(...)` signature — collect all `run("X"…)` calls,
     drop plumbing (`Close`, `Duplicate...`, `Select None`, …), take
     first 3 distinct plugin names, join with `__`.
  3. First defined symbol for Groovy / Jython / JS.
  4. Fallback `macro_<HHMMSS>` / `script_<HHMMSS>`.
- In-memory ring of last 200 entries.
- Per-entry append-only file at
  `AI_Exports/.session/code/<HHMMSS>_<name>.<ext>`
  where `<ext>` is `ijm` / `groovy` / `py` / `js`.
- Single `AI_Exports/.session/code/INDEX.json` manifest updated
  atomically per entry.
- Filters: length ≤20 chars skipped, adjacent-duplicate
  suppressed (bump `run_count`), non-adjacent duplicate promoted
  with `run_count++`.
- Collision handling: same slug + identical canonical code →
  dedup. Same slug + different code → numeric suffix `_2`, `_3`.
- Two one-line hooks added to `TCPCommandServer`: one in
  `handleExecuteMacro` right after `code` is read, one in
  `handleRunScript` in the same position. Each calls
  `SessionCodeJournal.INSTANCE.record(language, code, source,
  macroId, startedAtMs, durationMs, success, failureMessage)` after
  success / failure is known.
- `source` field reuses the existing `macro.started` source strings
  (`"tcp"`, later `"rail:history"`, `"rail:macro-set"`).

## Out of scope

- Any UI for browsing / re-running entries — stage 11.
- Any new TCP command exposing the journal — never. Agent-silence.
- Cross-session persistence (reading `INDEX.json` on Fiji restart)
  — stage 11 (UI has the toggle; journal just writes the file).
- Scrollback / terminal-output persistence — stage 12.

## Files touched

| Path                                                                     | Action | Reason                                       |
|--------------------------------------------------------------------------|--------|----------------------------------------------|
| `src/main/java/imagejai/engine/SessionCodeJournal.java`       | NEW    | Singleton, ring, disk writes, listeners      |
| `src/main/java/imagejai/engine/CodeAutoNamer.java`            | NEW    | Cascading naming strategies                  |
| `src/main/java/imagejai/engine/TCPCommandServer.java`         | MODIFY | Two `record(...)` call sites, nothing else   |
| `AI_Exports/.session/code/`                                              | NEW    | Runtime-created; do not commit               |

## Implementation sketch

```java
public final class SessionCodeJournal {
    public static final SessionCodeJournal INSTANCE = new SessionCodeJournal();
    private final Deque<Entry> ring = new ArrayDeque<>();  // guarded
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void record(String language, String code, String source,
                       long macroId, long startedAtMs, long durationMs,
                       boolean success, String failureMessage) {
        if (code == null || code.trim().length() <= 20) return;
        String canonical = canonicalise(code);
        synchronized (this) {
            Entry head = ring.peekFirst();
            if (head != null && head.canonical.equals(canonical)) {
                head.runCount++;
                head.lastRunAt = startedAtMs;
                notifyListeners();
                return;
            }
            // non-adjacent duplicate promotion
            for (Iterator<Entry> it = ring.iterator(); it.hasNext(); ) {
                Entry e = it.next();
                if (e.canonical.equals(canonical)) {
                    it.remove();
                    e.runCount++;
                    e.lastRunAt = startedAtMs;
                    ring.addFirst(e);
                    notifyListeners();
                    return;
                }
            }
            String slug = CodeAutoNamer.nameFor(language, code, existingSlugs());
            Entry e = new Entry(nextId(), slug, language, code, canonical,
                                source, macroId, startedAtMs, durationMs,
                                success, failureMessage);
            ring.addFirst(e);
            while (ring.size() > 200) ring.pollLast();
            writeToDisk(e);     // file + INDEX.json manifest update
            notifyListeners();
        }
    }
    // snapshot(), get(id), addListener(...) as in PLAN.md §2.10
}
```

Auto-namer plumbing block-list (seed):
```
Close, Close All, Duplicate..., Select None, Select All,
Make Inverse, Clear Results, Set Measurements, Properties...,
Set Scale..., Rename
```

Canonicalisation = trim, collapse runs of whitespace, normalise
line endings. Used for dedup only; disk file keeps the agent's
exact original code.

## Exit gate

1. `mvn package` succeeds.
2. Unit tests (new test class `SessionCodeJournalTest`):
   - length filter drops `getTitle()` one-liner
   - leading-`//` comment becomes the slug
   - composite `run(...)` signature joins plugin names with `__`
   - adjacent duplicate bumps `runCount` to 2, doesn't add entry
   - non-adjacent duplicate promotes + bumps `runCount`
   - same slug + different code gets `_2` suffix
3. Manual smoke test: launch Claude Code externally (stage 02's
   external path), ask it to run three distinct macros. Inspect
   `AI_Exports/.session/code/` — three files, sensibly named, with
   an `INDEX.json` listing all three.
4. `grep -R "execute_macro\|run_script\|subscribe\|macro\\." src/` —
   confirm no new command string, no new event name, no new
   response field introduced by this stage. Hooks are pure
   outbound calls into `SessionCodeJournal`.

## Known risks

- Disk I/O inside the TCP handler's hot path. Use a non-blocking
  append pattern (offload file write to a single-thread executor
  inside `SessionCodeJournal`) so a full disk never blocks a
  macro execution. Ring + in-memory listener notification stays
  synchronous (cheap).
- Auto-namer false positives on macros whose first `//` line is a
  copyright banner. The boilerplate skip-list handles that — keep
  it small and data-driven so the stage-11 UI can surface
  "rename" if the user needs it.
- Thread safety: `TCPCommandServer` may call `record()` from
  multiple worker threads. Synchronise on the journal itself; do
  not lock on `ring` alone (ArrayDeque isn't thread-safe).
