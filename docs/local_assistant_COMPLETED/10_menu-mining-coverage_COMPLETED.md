# Stage 10 — Menu mining (coverage multiplier)

## Why this stage exists

Hand-curating ~50 intents covers the boring 80 % but Fiji ships
~400 menu commands and most users have third-party plugins on top
(StarDist, TrackMate, Cellpose, CLIJ2, Bio-Formats, AnalyzeSkeleton
…). Mining `ij.Menus.getCommands()` lets us auto-generate intent
stubs for every command in the user's actual install with no per-
plugin work, then run the phrasebook compiler over the command
names so they are findable in plain English.

## Prerequisites

- Stage 03 (`IntentLibrary`, `IntentMatcher`, phrasebook loader).
- Stage 04 (`tools/phrasebook_build.py` — extended in this stage to
  accept menu-command names as input).

## Read first

- `docs/local_assistant/00_overview.md`
- `docs/local_assistant/PLAN.md` §6.4 (TCP command landscape — to
  understand the relationship between menu commands and TCP
  commands), §9 Phase C
- `src/main/java/imagejai/engine/MenuCommandRegistry.java`
  — the existing in-JVM snapshot of `ij.Menus.getCommands()`.
  Verify its API; reuse rather than reimplement.
- `agent/scan_plugins.py` — the older Python-side scraper. **Do
  not call it from Java**; the Java path is canonical for this
  stage.
- `tools/phrasebook_build.py` from stage 04

## Scope

### MenuIntentImporter (Java side)

At Local Assistant startup (after `IntentLibrary.load()`), iterate
`MenuCommandRegistry` (or `ij.Menus.getCommands()` if the registry
does not exist yet) and for each command not already covered by a
hand-written intent ID, register a synthetic intent under the ID
namespace `menu.<menu-path>` (e.g. `menu.image.adjust.threshold`).

Each synthetic intent's `execute()` simply opens the command's
dialog with no args:

```java
IJ.run(commandName);   // e.g. IJ.run("Threshold...")
```

It does not guess parameters (per house rule). The reply is
`AssistantReply.withMacro("Opened dialog: <name>", "run(\"<name>\");")`.

**Avoid duplicates.** If a hand-written intent already maps to the
same `IJ.run(...)` call, prefer the hand-written one (it likely
has slot handling and a richer description). Match by command
name, not by phrasebook overlap.

### Phrasebook expansion

Extend `tools/phrasebook_build.py` with a `--menu-dump <path>` flag
that reads a file produced by Java at startup (one command per
line) and generates phrasings for each. Output is merged into
`src/main/resources/phrasebook.json` under the namespaced IDs.

Two paths:

1. **Build-time menu dump.** In CI / before release, the developer
   starts Fiji with the plugin, lets the menu registry populate,
   exports the snapshot to `tools/menu-commands.txt`, and reruns
   `tools/phrasebook_build.py --menu-dump tools/menu-commands.txt`.
   The expanded phrasebook is committed.
2. **Runtime extension (optional, off by default).** A user-
   facing setting `Settings.expandMenuPhrasebook` enables a one-
   shot `IntentLibrary.expandFromMenu()` call at startup that
   creates phrasings *locally* (without an LLM) by copying the
   command name and its lowercased variants into the phrasebook.
   Lower coverage than the LLM path but free at runtime.

### Defaults

- The synthetic intents themselves register every time (cheap; just
  Java objects). Not toggleable.
- The LLM-distilled phrasings for menu commands ship in the JAR
  if the developer has run path 1. Otherwise users still get the
  command-name-only phrasings via path 2.

## Out of scope

- Parameter-aware intents for menu commands. That requires probing
  each plugin's dialog (`probe_command` TCP path) — defer to v2.
- Auto-running menu commands (always opens the dialog and stops,
  per house rule).
- Update site discovery / auto-install of plugins — forbidden by
  house rule.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/imagejai/local/MenuIntentImporter.java` | NEW | Iterates registry, creates synthetic intents |
| `src/main/java/imagejai/engine/MenuCommandRegistry.java` | MODIFY (or NEW if absent) | Expose the menu-command map for Local Assistant |
| `src/main/java/imagejai/local/IntentLibrary.java` | MODIFY | Call `MenuIntentImporter.importInto(this)` after loading the JSON phrasebook |
| `src/main/java/imagejai/config/Settings.java` | MODIFY | Add `expandMenuPhrasebook` flag (default false) |
| `tools/phrasebook_build.py` | MODIFY | `--menu-dump` flag |
| `tools/menu-commands.txt` | NEW (committed) | Snapshot of canonical Fiji install for the dev's machine |
| `src/main/resources/phrasebook.json` | MODIFY | Add namespaced `menu.*` intents and phrasings |

## Implementation sketch

```java
// MenuIntentImporter.java
public static void importInto(IntentLibrary lib) {
  Hashtable<String, String> commands = ij.Menus.getCommands();
  for (String name : commands.keySet()) {
    String id = "menu." + slugify(name);
    if (lib.byId(id) != null) continue;          // already hand-written
    lib.register(new MenuIntent(id, name));
  }
}

private static String slugify(String s) {
  return s.toLowerCase(Locale.ROOT)
          .replaceAll("[^a-z0-9]+", ".")
          .replaceAll("^\\.|\\.$", "");
}
```

```java
// MenuIntent.java
public class MenuIntent implements Intent {
  private final String id, command;
  public MenuIntent(String id, String command) {
    this.id = id; this.command = command;
  }
  public String id() { return id; }
  public String description() { return "Open dialog: " + command; }
  public AssistantReply execute(Map<String,String> slots, FijiBridge fiji) {
    String macro = String.format("run(\"%s\");", command.replace("\"", "\\\""));
    fiji.runMacro(macro);
    return AssistantReply.withMacro("Opened: " + command, macro);
  }
}
```

```python
# tools/phrasebook_build.py — fragment
if args.menu_dump:
    with open(args.menu_dump) as f:
        commands = [l.strip() for l in f if l.strip()]
    for cmd in commands:
        intent = {"id": "menu." + slugify(cmd),
                  "description": f"Open dialog: {cmd}",
                  "seeds": [cmd.lower()]}
        merge(phrasebook, build_intent(intent))
```

## Exit gate

1. Starting the plugin against a default Fiji install registers
   ~400 synthetic `menu.*` intents in `IntentLibrary.all()`.
2. Typing the canonical name of any menu command (e.g. "FFT",
   "Properties...") opens its dialog.
3. After running `tools/phrasebook_build.py --menu-dump`, fuzzy-
   matching against partial phrasings ("fast fourier", "image
   info") also resolves to the right menu intent.
4. No hand-written intent is shadowed by a synthetic one with the
   same canonical command (verify by ID prefix: `builtin.*` always
   wins over `menu.*` when both would match the same input).
5. Phrasebook size after merging stays under 5 MB on disk so the
   JAR does not balloon.
6. `mvn -q test` passes.

## Known risks

- **JAR bloat.** ~400 menu commands × ~50 phrasings = 20,000
  extra entries. Each ~30 bytes → ~600 KB. Acceptable. If the
  user has 100s of plugins installed, monitor.
- **Menu command names with quotes or weird characters.** Quote-
  escape inside `IJ.run("...")`. Some commands also need the
  trailing `...` to avoid running headlessly with default args
  (e.g. `Threshold...`). Preserve the trailing dots from the
  registry exactly as found.
- **Plugin presence at build time vs runtime.** The committed
  `tools/menu-commands.txt` is the dev's Fiji snapshot. Users with
  more plugins still get the synthetic intents (path 1 covers the
  base set; their plugin's commands are added at startup with
  command-name-only phrasings via path 2 if enabled).
- **Stage 06 / 07 collisions.** Hand-written intents from stages
  06–07 are typically named e.g. `builtin.image.threshold` vs
  menu-mined `menu.image.adjust.threshold`. Different IDs, so no
  collision in the registry — but the same plain-English
  phrasings can match both. The matcher returns the shorter ID
  on ties (or the lex-first); document and test.
- **`agent/scan_plugins.py` divergence.** The Python scraper
  produces a different snapshot. Note in `tools/README.md` that
  `tools/menu-commands.txt` is the canonical input; the Python
  output is for legacy agent paths only.
