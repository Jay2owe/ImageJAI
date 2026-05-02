# Stage 05 — Tier 2 Jaro-Winkler matcher and autocomplete chips

## Why this stage exists

Tier 1 hash lookup catches exact phrasings only. Real users mistype
("pixle siz"), reorder words ("size pixel"), and drop articles
("the calibration"). Tier 2 catches those with fuzzy matching, and
the same fuzzy index drives a chip row under the chat textbox so
users can discover known phrasings as they type — which is the
primary reason a deterministic two-tier matcher is enough.

## Prerequisites

- Stage 03 (`IntentMatcher`, `IntentLibrary`, phrasebook loader).
- Stage 04 is helpful (full phrasebook makes chips much more
  useful) but not strictly required.

## Read first

- `docs/local_assistant/00_overview.md`
- `docs/local_assistant/PLAN.md` §4.3 ("Autocomplete chips"), §6.2
  ("Two-tier matcher")
- `src/main/java/imagejai/engine/FuzzyMatcher.java` —
  the existing `jaroWinkler(...)` implementation. Verify its
  signature and threshold semantics. **Do not add Apache Commons
  Text as a dependency**; use the in-repo matcher.
- `src/main/java/imagejai/ui/ChatView.java` — find the
  input textbox, its `KeyListener` registration site, and the area
  immediately below it where a chip row can fit

## Scope

- Extend `IntentMatcher.match()` to fall through to Jaro-Winkler
  when Tier 1 misses. Threshold 0.90 (tunable in `Settings`).
  Compute against every phrase in the phrasebook; return the best
  score above threshold; tie-break on shorter phrase.
- Add `IntentMatcher.topK(String input, int k) -> List<RankedPhrase>`
  for the chip row. Returns the k best-scoring phrasings (any
  intent), sorted by score descending, regardless of threshold.
  Apply a margin filter: if the best score is below 0.7, return an
  empty list (do not show useless chips).
- Add `AutocompleteChipRow.java` — a small `JPanel` rendering up to
  three clickable chips (`JButton` styled flat). `setCandidates(...)`
  swaps the chips in place.
- In `ChatView`, register a debounced (~100 ms) `DocumentListener`
  on the input field. On each text change, call `matcher.topK(text,
  3)` and pass the result to the chip row. On chip click, set the
  input field text to the chip's phrasing; do **not** auto-submit.
  Tab key while focused on the input field accepts the first chip.
- Make the chip row visible only when (a) Local Assistant is the
  active agent and (b) the input is non-empty.

## Out of scope

- Disambiguation chips on submit (different surface, different
  trigger) — Phase D backlog.
- Slash command recognition in `IntentMatcher` — stage 08.
- Real intents beyond what stage 03 ships — stages 06–07.
- MiniLM semantic tier — opt-in download via stage 09; not part of
  the Tier 2 path.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/imagejai/local/IntentMatcher.java` | MODIFY | Tier 2 fallback + `topK(...)` |
| `src/main/java/imagejai/local/RankedPhrase.java` | NEW | `(phrase, intentId, score)` record |
| `src/main/java/imagejai/local/AutocompleteChipRow.java` | NEW | Swing chip panel |
| `src/main/java/imagejai/ui/ChatView.java` | MODIFY | Debounced listener + chip row in layout |
| `src/main/java/imagejai/config/Settings.java` | MODIFY | Add `localAssistantFuzzyThreshold` (default 0.90) |

## Implementation sketch

```java
// IntentMatcher.java
public Optional<MatchedIntent> match(String input) {
  String key = normalise(input);
  String id = library.phraseToIntentId().get(key);          // Tier 1
  if (id != null) return Optional.of(new MatchedIntent(id, Map.of()));

  RankedPhrase best = topK(input, 1).stream().findFirst().orElse(null);
  if (best != null && best.score() >= settings.getLocalAssistantFuzzyThreshold()) {
    return Optional.of(new MatchedIntent(best.intentId(), Map.of()));
  }
  return Optional.empty();
}

public List<RankedPhrase> topK(String input, int k) {
  String key = normalise(input);
  return library.allPhrases().stream()
      .map(p -> new RankedPhrase(
          p.phrase(), p.intentId(),
          FuzzyMatcher.jaroWinkler(key, p.phrase())))
      .sorted(Comparator.comparingDouble(RankedPhrase::score).reversed())
      .limit(k)
      .filter(r -> r.score() >= 0.7)   // margin floor
      .toList();
}
```

```java
// ChatView — pseudocode for the listener
inputField.getDocument().addDocumentListener(new DebouncedListener(100, () -> {
  if (!isLocalAssistantActive()) { chipRow.setCandidates(List.of()); return; }
  String text = inputField.getText();
  List<RankedPhrase> chips = text.isBlank()
    ? List.of()
    : matcher.topK(text, 3);
  chipRow.setCandidates(chips);
}));
```

## Exit gate

1. With Local Assistant active and an image open:
   - Typing "pixle siz" → "Pixel size: ..." reply (Tier 2 hit).
   - Typing "size pixel" → same reply (Tier 2 hit on word
     reordering).
2. As the user types, up to three chips appear under the input
   updating live (within 200 ms of last keystroke).
3. Clicking a chip fills the input but does not auto-submit.
4. Tab key with focus on the input field accepts the first chip.
5. Switching the dropdown to a non-Local-Assistant agent hides the
   chip row.
6. Benchmark test top-1 accuracy improves over stage 03 baseline.

## Known risks

- **Slow `topK` on a large phrasebook.** ~2,500 phrases × Jaro-
  Winkler is fine on a laptop (well under 5 ms in practice), but
  do not call it on every keystroke without debouncing — typing
  fast at 80 wpm is ~10 keystrokes/sec.
- **Chip churn.** Chips shifting on every keystroke is visually
  noisy. Either animate transitions or only update the chip row
  when the candidate set actually changes.
- **Antonym confusion.** Jaro-Winkler is character-based, so
  "open all" vs "close all" do not collide (unlike embeddings).
  Sanity-check this against the benchmark.
- **Threshold 0.90 may be too strict** for typo-heavy users. Make
  it a `Settings` field so power users can dial it down.
