# Step 04 — Plugin Registry and Fuzzy Match

## Motivation

Transcripts show agents inventing plugin names constantly:
`Laplacian...`, `Difference of Gaussians...`, `DoG...`,
`Variance` (no ellipsis), `Band-pass Filter...` (hyphen), `Band
Pass Filter...` (space), `Median 3x3...`, `Median 5x5...`. Each
one produces the same unhelpful `"Unrecognized command"` error
and burns a turn or three before the agent pivots.

Fiji already knows every plugin name via
`ij.Menus.getCommands()`. The server should consult that table,
run a Jaro-Winkler match against what the agent sent, and either
auto-correct (high confidence, single candidate) or reject with
suggestions (ambiguous) or reject outright (no close match).

## End goal

Two outcomes:

1. **New TCP command `list_commands`** returning the canonical
   plugin list (name → class path). Useful for agents that want
   to browse; replaces the client-side `scan_plugins.py` → `.tmp`
   round-trip.
2. **Pre-validation inside `execute_macro`**: any `run("NAME",
   ...)` call is matched against the registry before handing to
   the interpreter. Three branches:
   - Exact match → proceed unchanged.
   - Jaro-Winkler ≥ 0.95 against exactly one candidate, and that
     candidate's score is ≥ 0.05 higher than the second-best →
     auto-correct, add `autocorrected` to the reply.
   - Score 0.85–0.95, OR multiple candidates within 0.05 of each
     other → reject with `PLUGIN_NOT_FOUND`, `suggested[]` holds
     top 3, `recovery_hint` names them.
   - Below 0.85 → reject with top 3 suggestions but lower
     confidence phrasing in the hint.

## Scope

In:
- New `MenuCommandRegistry` class. Loads from
  `Menus.getCommands()` at server start; caches. Provides
  `allCommands()` and `findClosest(name)`.
- New `FuzzyMatcher` class with a Jaro-Winkler implementation
  (single file, no external deps).
- New `list_commands` TCP handler.
- Pre-validation helper invoked from `handleExecuteMacro` and
  `handleRunPipeline`. Parses submitted macro for `run("NAME",
  ...)` patterns; checks each against registry.
- Auto-correction: when conditions met, rewrite the macro before
  handing to the interpreter and record the correction in the
  reply.

Out:
- Parsing arbitrary JS-style macro constructs. The regex is
  deliberately narrow: `run\s*\(\s*"([^"]+)"` and the single-quote
  variant. Macros that obfuscate plugin names (string concat,
  variable substitution) skip validation silently.
- Validating argument strings. That's what `probe_command` is
  for, and this step doesn't replace it.

## Read first

- `src/main/java/imagejai/engine/TCPCommandServer.java`
  — `handleExecuteMacro`, `handleRunPipeline`.
- `ij.Menus` Javadoc — `getCommands()` returns
  `Hashtable<String, String>` (name → class).
- `agent/scan_plugins.py` — current client-side scraper. Its
  `.tmp/commands.raw.txt` output is what `list_commands` replaces.
- `docs/tcp_upgrade/02_structured_errors.md` — the `suggested[]`
  shape.

## Jaro-Winkler

A similarity score, 0..1, that weights matching prefix characters
higher than interior ones. Good for catching typos because most
typos are interior.

Pseudocode (full implementation in `FuzzyMatcher.java`):

```
jaro(s1, s2):
    matchDistance = max(len(s1), len(s2)) // 2 - 1
    matches = count chars that match within matchDistance
    transpositions = count of matched chars that are out of order
    if matches == 0: return 0
    j = (matches/len(s1) + matches/len(s2) + (matches - transpositions/2)/matches) / 3
    return j

jaroWinkler(s1, s2):
    j = jaro(s1.lower(), s2.lower())
    prefix = common prefix length, capped at 4
    return j + 0.1 * prefix * (1 - j)
```

Thresholds this plan uses:
- ≥ 0.95 and unique → auto-correct.
- 0.85–0.95 or ambiguous → suggest.
- < 0.85 → low-confidence suggest.

Conservative thresholds chosen because the `nonexistent-
laplacian-dog.md` transcript shows what happens when fuzzy
matching is too permissive: `Laplacian...` pointed the agent at
`Gaussian...`, the agent followed blindly, outputs corrupted.

## Command shape

```json
{"command": "list_commands"}
```

Response:

```json
{"ok": true, "result": {
  "count": 5123,
  "commands": ["3D Objects Counter...",
               "3D Project...",
               "Analyze Particles...",
               ...]
}}
```

For agents that want details: `{"command": "list_commands",
"include_classes": true}` returns `[{name, class}, ...]`.

Response for `execute_macro` when an auto-correction happened:

```json
{"ok": true, "result": {
  "success": true,
  "autocorrected": [
    {"from": "Gausian Blur", "to": "Gaussian Blur...",
     "score": 0.97, "rule": "fuzzy_unique_95"}
  ],
  "ranCode": "run(\"Gaussian Blur...\", \"sigma=2\");",
  ...
}}
```

Response when rejected:

```json
{"ok": true, "result": {
  "success": false,
  "error": {
    "code": "PLUGIN_NOT_FOUND",
    "category": "not_found",
    "retry_safe": false,
    "message": "'Laplacian' is not a registered Fiji command.",
    "recovery_hint": "Closest matches: 'Laplacian of Gaussian'? or 'Gaussian Blur...'? Probe with probe_command before using.",
    "suggested": [
      {"command": "probe_command",
       "args": {"plugin": "Laplacian of Gaussian"}},
      {"command": "list_commands"}
    ]
  }
}}
```

(Note: `Laplacian of Gaussian` may itself not exist — the
suggested probe call surfaces that fact cheaply.)

## Code sketch

`MenuCommandRegistry.java`:

```java
public final class MenuCommandRegistry {
    private static final MenuCommandRegistry INSTANCE =
        new MenuCommandRegistry();
    private final List<String> names;
    private final Map<String, String> byName;  // name -> class

    private MenuCommandRegistry() {
        Hashtable<String, String> cmds = Menus.getCommands();
        this.byName = new HashMap<>(cmds);
        this.names = new ArrayList<>(cmds.keySet());
    }

    public static MenuCommandRegistry get() { return INSTANCE; }

    public boolean exists(String name) { return byName.containsKey(name); }

    public List<Match> findClosest(String query, int topN) {
        return names.stream()
            .map(n -> new Match(n, FuzzyMatcher.jaroWinkler(query, n)))
            .sorted(Comparator.comparingDouble((Match m) -> -m.score))
            .limit(topN)
            .toList();
    }

    public record Match(String name, double score) {}
}
```

Invalidation: if an update site is installed mid-session, new
plugins won't appear in `names` until server restart. Acceptable
tradeoff for Phase 1. Phase 2 can add a `reload_commands` TCP
handler if the friction shows up.

Pre-validation helper:

```java
static final Pattern RUN_CALL =
    Pattern.compile("run\\s*\\(\\s*[\"']([^\"']+)[\"']");

static ValidationResult validateRunCalls(String code) {
    List<Correction> corrections = new ArrayList<>();
    List<Rejection> rejections = new ArrayList<>();
    Matcher m = RUN_CALL.matcher(code);
    String patched = code;
    while (m.find()) {
        String used = m.group(1);
        if (MenuCommandRegistry.get().exists(used)) continue;
        List<Match> top = MenuCommandRegistry.get().findClosest(used, 3);
        double best = top.get(0).score();
        double second = top.size() > 1 ? top.get(1).score() : 0.0;
        if (best >= 0.95 && (best - second) >= 0.05) {
            patched = patched.replace(used, top.get(0).name());
            corrections.add(new Correction(used, top.get(0).name(), best));
        } else {
            rejections.add(new Rejection(used, top, best));
        }
    }
    return new ValidationResult(patched, corrections, rejections);
}
```

If any rejection is present, the macro never runs — the server
replies with the structured error directly. If only corrections
are present, the patched macro runs and the reply carries an
`autocorrected` field.

## Python side

`ij.py list-commands` — new subcommand, optional `--classes`
flag, prints the list or pipes to a file.

`agent/gemma4_31b/tools_fiji.py` — new `@tool list_commands()`
returning the array, cached for the session (lists are stable
per-process). Docstring names it as the canonical way to discover
plugins.

## Tests

- `run("Gaussian Blur...")` → passes untouched.
- `run("Gausian Blur")` → auto-corrected to `Gaussian Blur...`,
  reply includes `autocorrected` entry.
- `run("Laplacian")` → rejected, top 3 suggestions, scores
  surfaced.
- `run("Blobs (25K)")` vs `run("Blobs (2K)")` — one exists in the
  File > Open Samples menu, one does not; the rejection must name
  both variants.
- Single-quote syntax `run('Threshold')` handled identically.
- Macros that build names with concatenation (`run("Plug" + "in")`)
  skip validation (intended).

## Failure modes

- A plugin legitimately named something fuzzy-similar to a
  hallucination (e.g., `Gaussian Blur...` and `Gaussian Blur 3D
  Filter`) — auto-correct could hit the wrong one. Conservative
  thresholds mitigate but won't eliminate. The `ranCode` echo
  (step 03) provides the second line of defence — if the wrong
  plugin ran, the agent sees the canonical macro and can react.
- Regex misses nested quote escapes. Accept; log as telemetry
  so we see if it happens in practice.
- `Menus.getCommands()` snapshot stale after update-site install.
  Restart Fiji to refresh. Document in `agent/CLAUDE.md`.
