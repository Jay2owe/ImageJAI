# Stage 03 — Tier 1 hash matcher and first real intent

## Why this stage exists

Stage 02 wired the chat panel to a `LocalAssistant` that returns
"don't recognise" for everything except a hard-coded "help". This
stage replaces that stub with the real Tier 1 matcher backed by a
phrasebook JSON file, ships the first read-only intent end to end
(`pixel_size`), and starts recording misses into `FrictionLog` for
later library growth.

## Prerequisites

- Stage 02 (`LocalAssistant`, `Intent`, `IntentLibrary`,
  `IntentMatcher`, `AssistantReply`, `FijiBridge` all exist as
  stubs).

## Read first

- `docs/local_assistant/00_overview.md`
- `docs/local_assistant/PLAN.md` §6 ("Architecture") and §7
  ("Phrasebook and Handler Format")
- `src/main/java/uk/ac/ucl/imagej/ai/engine/FrictionLog.java` —
  `record(...)` signature, `CAPACITY`, `WINDOW_MS`
- `src/main/java/uk/ac/ucl/imagej/ai/local/IntentMatcher.java` (stub)
- `src/main/java/uk/ac/ucl/imagej/ai/local/IntentLibrary.java` (stub)
- ImageJ `ij.measure.Calibration` and `ij.ImagePlus.getCalibration()`

## Scope

- Define the phrasebook JSON schema (PLAN §7.1) and ship a starter
  `src/main/resources/phrasebook.json` with a single intent:
  `image.pixel_size`, ~10 phrasings ("pixel size", "px size",
  "calibration", "what is the pixel size", "what's the pixel size",
  "spatial resolution", "how big are the pixels", "show pixel
  size", "image calibration", "pixel dimensions").
- Implement `IntentLibrary.load()` to parse the JSON at startup
  into:
  - `Map<String, String> phraseToIntentId` (normalised phrase →
    intent id)
  - `Map<String, Intent> handlers` (intent id → handler instance)
- Implement `IntentMatcher.normalise(String)` — lowercase, strip
  punctuation (`[^a-z0-9 ]`), collapse whitespace.
- Implement `IntentMatcher.match()` as a Tier 1 hash lookup against
  `phraseToIntentId`. No fuzzy match yet (stage 05).
- Implement `PixelSizeIntent` reading
  `IJ.getImage().getCalibration()`. State check: if
  `WindowManager.getCurrentImage() == null`, return
  `AssistantReply.text("No image is open.")`.
- Wire `LocalAssistant.handle()` to call
  `frictionLog.record("local_assistant", "miss", "", input)` when
  `IntentMatcher.match()` returns empty.
- Scaffold `tests/benchmark/biologist_phrasings.jsonl` with ~20
  seed lines (phrase, expected intent id) drawn from the §5 list,
  even if most do not yet have handlers. Add a small JUnit test
  that loads the phrasebook and the benchmark and reports top-1
  accuracy as a printed metric (no assertion threshold yet).

## Out of scope

- Tier 2 fuzzy matcher and autocomplete chips — stage 05.
- Phrasebook compiler tool — stage 04 (this stage hand-writes the
  starter file).
- Other intents besides `pixel_size` and `help` — stages 06–07.
- `IntentRouter` fallback — stage 08.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/resources/phrasebook.json` | NEW | Starter phrasebook (`pixel_size` only) |
| `src/main/java/uk/ac/ucl/imagej/ai/local/IntentLibrary.java` | MODIFY | JSON loader, registry of phrase → id and id → handler |
| `src/main/java/uk/ac/ucl/imagej/ai/local/IntentMatcher.java` | MODIFY | `normalise()` and Tier 1 hash lookup |
| `src/main/java/uk/ac/ucl/imagej/ai/local/LocalAssistant.java` | MODIFY | FrictionLog miss recording |
| `src/main/java/uk/ac/ucl/imagej/ai/local/intents/PixelSizeIntent.java` | NEW | First real read-only intent |
| `tests/benchmark/biologist_phrasings.jsonl` | NEW | Seed accuracy benchmark |
| `src/test/java/uk/ac/ucl/imagej/ai/local/IntentMatcherBenchmarkTest.java` | NEW | Loads benchmark, prints top-1 accuracy |

## Implementation sketch

```json
// src/main/resources/phrasebook.json
{
  "version": 1,
  "intents": [
    {
      "id": "image.pixel_size",
      "description": "Report the active image's pixel size",
      "phrases": [
        "pixel size", "px size", "calibration",
        "what is the pixel size", "what's the pixel size",
        "spatial resolution", "how big are the pixels",
        "show pixel size", "image calibration", "pixel dimensions"
      ]
    }
  ]
}
```

```java
// IntentMatcher.java
private static final Pattern PUNCT = Pattern.compile("[^a-z0-9 ]");
private static final Pattern WS = Pattern.compile("\\s+");

public static String normalise(String input) {
  String s = input.toLowerCase(Locale.ROOT);
  s = PUNCT.matcher(s).replaceAll(" ");
  return WS.matcher(s).replaceAll(" ").trim();
}

public Optional<MatchedIntent> match(String input) {
  String key = normalise(input);
  String id = library.phraseToIntentId().get(key);
  return id == null
    ? Optional.empty()
    : Optional.of(new MatchedIntent(id, Map.of()));
}
```

```java
// PixelSizeIntent.java
public class PixelSizeIntent implements Intent {
  public String id() { return "image.pixel_size"; }
  public String description() {
    return "Report the active image's pixel size";
  }
  public AssistantReply execute(Map<String,String> slots,
                                FijiBridge fiji) {
    ImagePlus imp = WindowManager.getCurrentImage();
    if (imp == null) return AssistantReply.text("No image is open.");
    Calibration cal = imp.getCalibration();
    return AssistantReply.text(String.format(
      "Pixel size: %.4f × %.4f %s",
      cal.pixelWidth, cal.pixelHeight, cal.getUnit()));
  }
}
```

```java
// LocalAssistant.handle() — add the miss-record line
matcher.match(input).ifPresentOrElse(
  m -> { /* run intent */ },
  () -> frictionLog.record("local_assistant", "miss", "",
                           IntentMatcher.normalise(input))
);
```

## Exit gate

1. `mvn -q test` runs the new benchmark test and prints a top-1
   accuracy line to stdout.
2. With an image open in Fiji and Local Assistant selected:
   - "what is the pixel size" → returns the calibration.
   - "px size" → returns the calibration.
   - "calibration" → returns the calibration.
3. With no image open: any pixel-size phrasing returns "No image is
   open."
4. Typing "potato salad" returns the "don't recognise" reply AND
   appears in `FrictionLog.snapshot()` with command="local_assistant"
   error="miss" arg="potato salad".

## Known risks

- **JSON parser choice.** Use Gson if the project already depends
  on it (check `pom.xml`); otherwise a tiny built-in parser. Do
  not add Jackson if Gson is already present.
- **Resource loading.** `IntentLibrary.load()` must use
  `getClass().getResourceAsStream("/phrasebook.json")`, not a
  filesystem path. Verify the JSON ends up under
  `target/classes/phrasebook.json` after `mvn package`.
- **`Calibration.getUnit()` may be empty** for unscaled images.
  The reply should say "pixels" in that case rather than printing
  an empty unit.
- **FrictionLog persistence is in-memory only** (`CAPACITY=100`).
  This is acceptable for v1; stage notes carry over to PLAN §14.4.
