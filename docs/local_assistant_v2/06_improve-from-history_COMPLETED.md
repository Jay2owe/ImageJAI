# Stage 06 — Improve from chat history

## Why this stage exists

The friction log fills up with phrases the assistant didn't
understand. Stage 05 makes those misses durable. Stage 06 turns
them into a controlled flow for growing the phrasebook: a new
`/improve` slash command summarises top miss buckets, walks the
developer (or curious user) through accept/skip decisions, and
emits a YAML diff against `tools/intents.yaml` for explicit
acceptance.

Crucially, **`/improve` does not call any LLM.** Phrasebook
regeneration (running `tools/phrasebook_build.py`) is a separate
manual step the user does at the terminal. Same rule as v1: no
runtime LLM, ever.

## Prerequisites

- Stage 05 (`FrictionLogJournal` writes durable misses).
- Strongly recommended: stage 01 (`PendingTurn` machinery) for
  the interactive accept/skip prompts.

## Read first

- `docs/local_assistant_v2/00_overview.md`
- `docs/local_assistant_v2/PLAN.md` §10 ("Stage 06 — Improve from
  chat history")
- `docs/local_assistant_v2/BRAINSTORM.md` §7
- `tools/intents.yaml` — full file. The format is a flat list of
  `{ id, description, seeds: [...] }` blocks.
- `tools/phrasebook_build.py` — read the header comment so the
  user-facing instructions in `/improve`'s output line up with the
  actual CLI invocation (default provider is `gemini`, default
  model is `gemini-3.1-pro-preview`).
- `src/main/java/imagejai/local/SlashCommandRegistry.java`
  — registration pattern for slash commands
- `src/main/java/imagejai/local/slash/TeachSlashCommand.java`
  — example of a slash command with arguments. Stage 06's
  `/improve` is interactive (multi-turn), so it'll thread the
  pending-turn machinery through `SlashCommandContext`.
- `src/main/java/imagejai/engine/FrictionLog.java`
  — `FailureEntry` shape

## Scope

- New `ImproveSlashCommand.java` registered in
  `SlashCommandRegistry`. Slash name: `improve`. Intent ID:
  `slash.improve`.
- New `ImproveAnalysis.java` helper:
  - `static List<MissBucket> fromJournal(FrictionLogJournal j)` —
    reads all miss entries (`command == "local_assistant"`,
    `error == "miss"`), normalises by phrase, buckets, sorts by
    count descending.
  - `MissBucket(String phrase, int count, long lastSeenMs,
    Optional<RankedPhrase> closestIntent)` — closest intent comes
    from `IntentMatcher.topK(phrase, 1)` with a 0.7 floor.
- `/improve` flow:
  1. Read the journal. If empty, reply "No misses to improve from."
  2. Compute top 20 buckets by count.
  3. For each bucket (in order), prompt with a numbered choice via
     a `PendingTurn` (PARAMETER kind). Show count, last-seen,
     closest intent. Choices: `a` alias to closest intent, `n` new
     intent, `s` skip, `q` quit and review accumulated changes.
  4. After the loop ends (or `q`), present the YAML diff. Park a
     final pending turn asking `apply? [y/n]`.
  5. On `y`, write the updated `tools/intents.yaml`. On `n`, no-op.
  6. Output the user-facing message: "Updated tools/intents.yaml.
     To regenerate the phrasebook, run `python
     tools/phrasebook_build.py` and rebuild the plugin."
- New intent flow: when the user picks `n` for a bucket, the next
  pending turn asks for a description ("describe this intent in
  one line"), then for at least one extra seed phrase if the
  user wants. The new intent ID is auto-derived as
  `user.<slugified-description>` so it doesn't collide with
  built-in IDs (review BRAINSTORM §1: "no new ID namespaces" —
  prefer `user.` since it's already conceptually distinct from
  `builtin.` and `menu.`, and v1 already has user-taught entries
  go through `IntentRouter` rather than the YAML; if we want
  these to land in `tools/intents.yaml` for phrasebook
  regeneration, `user.` is the right prefix and that's a *new*
  decision worth flagging in the implementation comment).

## Out of scope

- Calling `tools/phrasebook_build.py` from the plugin. Manual
  step. Document the command in the user-facing message.
- Auto-applying changes to `src/main/resources/phrasebook.json`.
  That requires a re-build; the plugin doesn't write to its own
  JAR.
- Calling any LLM. The whole point is the developer reviews the
  diff. If a future stage wants LLM-assisted bucket clustering,
  that's a v3 conversation.
- Mutating `tools/intents.yaml` outside the controlled `/improve`
  flow. No CLI flag, no auto-suggest.
- Parsing partial YAML or handling YAML-in-the-wild edge cases.
  Use SnakeYAML (already on the classpath via Maven? — verify; if
  not, hand-write the append since the file is structurally
  simple).

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/imagejai/local/slash/ImproveSlashCommand.java` | NEW | The slash command |
| `src/main/java/imagejai/local/ImproveAnalysis.java` | NEW | Bucket and rank journal entries |
| `src/main/java/imagejai/local/IntentsYamlWriter.java` | NEW | Append seeds / new blocks to `tools/intents.yaml` |
| `src/main/java/imagejai/local/SlashCommandRegistry.java` | MODIFY | Register `ImproveSlashCommand` |
| `src/test/java/imagejai/local/ImproveSlashCommandTest.java` | NEW | End-to-end happy path + a couple of edge cases |
| `src/test/java/imagejai/local/IntentsYamlWriterTest.java` | NEW | Round-trip YAML mutations against fixture files |

## Implementation sketch

```java
// MissBucket data
public record MissBucket(String phrase, int count, long lastSeenMs,
                          Optional<RankedPhrase> closestIntent) { }
```

```java
// ImproveAnalysis.fromJournal
public static List<MissBucket> fromJournal(FrictionLogJournal journal,
                                           IntentMatcher matcher) throws IOException {
    Map<String, int[]> counts = new HashMap<>();
    Map<String, long[]> lastSeen = new HashMap<>();
    try (Stream<FrictionLog.FailureEntry> entries = journal.streamEntries()) {
        entries.filter(e -> "local_assistant".equals(e.command))
               .filter(e -> "miss".equals(e.error))
               .forEach(e -> {
                   String key = e.argsSummary;       // user input was here
                   counts.computeIfAbsent(key, k -> new int[1])[0]++;
                   long[] ls = lastSeen.computeIfAbsent(key, k -> new long[]{0});
                   if (e.ts > ls[0]) ls[0] = e.ts;
               });
    }
    List<MissBucket> buckets = new ArrayList<>();
    for (Map.Entry<String, int[]> ent : counts.entrySet()) {
        String phrase = ent.getKey();
        Optional<RankedPhrase> closest = matcher.topK(phrase, 1)
                .stream().findFirst();
        buckets.add(new MissBucket(phrase, ent.getValue()[0],
                                   lastSeen.get(phrase)[0], closest));
    }
    buckets.sort(Comparator.comparingInt(MissBucket::count).reversed());
    return buckets;
}
```

```java
// ImproveSlashCommand — orchestration sketch
public AssistantReply execute(SlashCommandContext ctx) {
    List<MissBucket> buckets = ImproveAnalysis.fromJournal(
            ctx.frictionJournal(), ctx.matcher());
    if (buckets.isEmpty()) return AssistantReply.text("No misses to improve from.");

    // Park an "improve session" pending turn that holds the bucket list,
    // current index, and accumulated decisions. Each subsequent user
    // input advances the session by one step.
    ImproveSession session = ImproveSession.start(buckets.subList(0,
            Math.min(20, buckets.size())));
    ctx.localAssistant().parkImproveSession(session);
    return AssistantReply.text(session.firstPrompt());
}
```

`ImproveSession` is a small state machine with phases
`BUCKET_DECISION`, `NEW_INTENT_DESCRIPTION`, `NEW_INTENT_SEED`,
`DIFF_CONFIRM`, `DONE`. It generates the next prompt and
processes the next user input. `LocalAssistant` checks for an
improve-session sub-state alongside the regular `PendingTurn`
(or, more cleanly, `PendingTurn` gains a `Kind.IMPROVE` variant
that carries the session). Pick the cleanest of the two; document
the choice.

```java
// IntentsYamlWriter
public class IntentsYamlWriter {
    public void addAlias(Path yaml, String intentId, String phrase) { ... }
    public void addNewIntent(Path yaml, String id, String description,
                             List<String> seeds) { ... }
    public String diff(Path yaml, List<Change> pending) { ... }
}
```

If SnakeYAML is on the classpath, prefer round-tripping through
the parser to avoid corrupting comments/order. If it isn't,
append-only string mutation against the simple `id/description/
seeds` block format is acceptable — `tools/intents.yaml` is
machine-managed, the comment-preservation problem is small.

## Exit gate

1. `mvn -q test` passes.
2. `ImproveSlashCommandTest` end-to-end:
   - Seed a fake journal with 5 misses for "how many frames" and
     2 for "potato salad".
   - Run `/improve`.
   - Reply `a` (alias to closest intent for the first bucket).
   - Reply `s` (skip the second bucket).
   - Reply `y` (apply diff).
   - Verify `tools/intents.yaml` (in a temp test fixture) gained
     a `- "how many frames"` line under the closest intent's
     `seeds:`.
3. `IntentsYamlWriterTest` round-trips a known-good fixture:
   adding an alias preserves comment lines, intent order, and
   indentation.
4. Empty-journal case: `/improve` with no misses replies "No
   misses to improve from."
5. The user-facing post-apply message instructs the user to run
   `python tools/phrasebook_build.py` and rebuild. Verify by
   string match in the test.
6. Manual smoke: with a populated friction log, run `/improve`,
   walk the flow, accept the diff, verify `tools/intents.yaml`
   on disk.
7. **No outbound network calls.** Inspect the new code; there
   should be zero network-client references in the v2-stage-06
   diff.

## Known risks

- **`SlashCommandContext` doesn't expose `LocalAssistant` or the
  matcher today.** Stage 01 likely already fixed this for the
  pending-turn integration. If not, plumb the references through.
- **Journal read-back performance.** A million-line journal would
  take time to read. Cap the analysis at the most recent N
  entries (e.g. 50_000) to keep the slash command responsive.
  Document the cap in `ImproveAnalysis`.
- **YAML write atomicity.** The plugin lives in Fiji; user might
  be running tests concurrently. Use a temp file + atomic rename
  pattern, same as `IntentRouter`'s `Files.move(tmp, target,
  ATOMIC_MOVE)`.
- **`tools/intents.yaml` location.** The plugin's working
  directory at runtime is the Fiji install dir, not the project
  source. If the user runs `/improve` from a deployed Fiji, the
  YAML they want to mutate is the *source-tree* one, not a copy
  in the JAR. Detect: if `tools/intents.yaml` doesn't exist in a
  predictable location, refuse to write and tell the user
  explicitly: "Run /improve from a development Fiji install with
  the ImageJAI source checked out at `<expected path>`." Don't
  silently no-op.
- **User-typed YAML content.** New-intent description and seeds
  go straight into YAML. Quote them safely (escape `:`, `#`,
  multi-line); use SnakeYAML's emitter if available, or hand-quote
  defensively.
- **Bucket de-duplication.** Two misses for "how many frames" and
  "How many frames?" should bucket together. Use the same
  `IntentMatcher.normalise(...)` path so capitalisation /
  punctuation collapse. Test this.
- **The "no LLM" line.** It's tempting to add "tap a button to
  regenerate the phrasebook from inside the chat". Don't. v1's
  BRAINSTORM §6 line "the shipped plugin makes zero outbound LLM
  calls" applies. v2 inherits it.
