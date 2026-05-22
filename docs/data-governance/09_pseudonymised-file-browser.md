# 09 — Pseudonymised File Browser + Selection Broker

## Why this stage exists

The `PseudonymisationFilter` from stage 02 governs *responses*. The
gap it leaves is the user's own input: typing
`open /MOAB2/subject_017_visit3.lif` into the agent chat sends the
identifiable string to the LLM verbatim, regardless of posture.

The Browse Files dialog closes that gap by moving file selection
into a local UI. The user picks files (and series-within-files) by
their real labels in a dialog that never talks to any LLM; ImageJAI
mints pseudonyms; only pseudonyms + a user-chosen tag reach the
agent.

The series-level use case ("open all 8-week timepoints in this
lif") is the killer feature. Selecting series is something the
agent fundamentally cannot do anyway — it has no view into your
file structure or labelling convention. Moving the work to a local
dialog turns the privacy gate into a power tool.

This stage is also where ImageJAI's CLI-agnostic advantage shines.
The brief broker delivers selections to *any* agent via three
mechanisms (embedded-PTY auto-type, external-CLI clipboard, TCP
polling) — no agent CLI needs to know ImageJAI exists; they all
work.

## Prerequisites

- Stage 02 (`PathTokenMap.tokenForSeries`, `open_image_by_token`
  reverse resolution, the filter itself).

## Read first

- `docs/data-governance/00_overview.md`.
- `docs/data-governance/02_pseudonymisation-filter.md` — token
  format, reverse resolution API.
- `src/main/java/imagejai/engine/TCPCommandServer.java` — to add
  the `browse_pending_brief` / `get_pending_brief` commands.
- `src/main/java/imagejai/terminal/EmbeddedPty.java` — for the
  auto-type nudge mechanism.
- `src/main/java/imagejai/engine/AgentLauncher.java` — Browse
  Files button slot from stage 03.
- Bio-Formats docs for `IFormatReader` series-metadata peek (no
  pixel reads).

## Scope

### 9.1 Browse Files dialog (Swing)

Triggered by the launcher's `[Browse Files…]` button (slot
reserved in stage 03). Dialog flow:

1. On open, scan current folder for image files
   (`.lif`, `.czi`, `.nd2`, `.tif`, `.tiff`, `.ome.tif`).
2. For each multi-series file (`.lif`, `.czi`, `.nd2`), use
   Bio-Formats `IFormatReader.setId` + `getSeriesCount` /
   `getSeriesMetadata` to enumerate series. **Metadata only — no
   pixel reads.**
3. Build a table:
   ```
   ☐ image-7a3f.lif  :1   wt_8w_male_001     8w  wt  4ch  4096³
   ☐ image-7a3f.lif  :2   wt_8w_male_002     8w  wt  4ch  4096³
   ☐ image-7a3f.lif  :3   ko_4w_female_001   4w  ko  4ch  4096³
   ```
   Token column already populated (left); user-visible original
   label column (middle) shown locally only.
4. Search box filters rows by real label + filename (local match,
   no LLM).
5. User multi-selects, sees a tag suggestion derived from regex
   parse of the selected labels (configurable per-folder via
   `.imagejai-tags.yml`).
6. **What the agent will see** preview at the bottom updates live
   showing tokens + tag only.
7. Click "Send to agent" → `SelectionBroker` enqueues; nudge
   delivered via best-available mechanism (see 9.3).

### 9.2 `SelectionBroker`

In-JVM queue of pending briefs, keyed by session.

```java
public record Brief(
    String sessionId,
    List<String> tokens,        // e.g. ["image-7a3f.lif:1", ":2", ":11"]
    String tag,                 // "8 weeks, wild-type"
    Map<String, Object> metadata // channels, dims, suggested workflow
) {}

public final class SelectionBroker {
    public void enqueue(Brief b);
    public Optional<Brief> peek(String sessionId);
    public Optional<Brief> consume(String sessionId);
    public boolean hasPending(String sessionId);
}
```

### 9.3 Brief delivery — three mechanisms, agent-agnostic

#### 9.3.1 Embedded PTY (smoothest)

If the active session uses ImageJAI's embedded terminal, type the
nudge directly into the PTY stdin:

```
I've selected 3 series for analysis: image-7a3f.lif:1, image-7a3f.lif:2,
image-7a3f.lif:11 (tagged "8 weeks, wild-type", 4-channel, 4096×4096×35).
Please call get_pending_brief for details.
```

#### 9.3.2 External CLI (Claude Code, Aider, Gemini in a separate terminal)

Write the same nudge to the system clipboard and show a non-modal
toast: *"Selection ready — Ctrl+V into your agent terminal."*

#### 9.3.3 Auto-polling (most polished)

Agent CLAUDE.md instruction: at start of every turn, call
`browse_pending_brief`. If `pending: true`, call
`get_pending_brief`. No user action required.

All three coexist; user picks.

### 9.4 New TCP commands

- `browse_pending_brief` → `{ "pending": true|false }`. Cheap
  poll. Audit row appended only if `pending=true` (avoid log
  spam).
- `get_pending_brief` → full `Brief` payload, then consumes from
  broker. Audit row with `notes="tokens=[image-7a3f.lif:1,...] tag='8 weeks, wild-type'"`.
- `open_image_by_token(token)` — already shipped in stage 02 as
  part of `open_image` overload. Stage 09 only documents agent
  usage.

### 9.5 Tag suggestion engine

Local regex-based label parser, no LLM. Default rules cover
common bioimaging conventions:

```yaml
# default suggestion patterns
- name: timepoint
  pattern: '(\d+)\s*[wd](?:k|eeks?|ays?)?'
  format: '{1} {unit}'
- name: genotype
  pattern: '\b(wt|ko|het|cre|flox|tg)\b'
  format: '{1}'
- name: sex
  pattern: '\b(male|female|m|f)\b'
- name: condition
  pattern: '\b(control|treated|vehicle|drug)\b'
```

Per-folder `.imagejai-tags.yml` overrides. When the user selects
rows, suggestion engine extracts common tokens across all
selected labels and proposes a tag. User edits before sending.

### 9.6 Bio-Formats series scanner

Lazy, cached per folder. Cache key: `(path, mtime)` so file
modifications invalidate. Scan happens on dialog open; progress
indicator for large folders.

## Out of scope

- A standalone "Tag Editor" UI for the .imagejai-tags.yml file.
  Power users edit YAML directly; document this in stage 07.
- Cross-folder browse (one folder at a time).
- Saved selection sets / templates.
- Image preview thumbnails in the dialog (could add later; not
  needed for the use case).

## Files touched

| Path | NEW/MODIFY | Reason |
|------|------------|--------|
| `src/main/java/imagejai/ui/BrowseFilesDialog.java` | NEW | The dialog |
| `src/main/java/imagejai/engine/security/SelectionBroker.java` | NEW | In-JVM brief queue |
| `src/main/java/imagejai/engine/security/Brief.java` | NEW | Brief record |
| `src/main/java/imagejai/engine/security/SeriesScanner.java` | NEW | Bio-Formats metadata peek |
| `src/main/java/imagejai/engine/security/TagSuggestionEngine.java` | NEW | Regex-based label parser |
| `src/main/java/imagejai/engine/security/BriefNudger.java` | NEW | Three-mechanism delivery |
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | `browse_pending_brief`, `get_pending_brief` handlers |
| `src/main/java/imagejai/engine/AgentLauncher.java` | MODIFY | Wire Browse Files button (slot from stage 03) |
| `src/test/java/imagejai/engine/security/SelectionBrokerTest.java` | NEW | Queue semantics, consume-once |
| `src/test/java/imagejai/engine/security/SeriesScannerTest.java` | NEW | Bio-Formats peek correctness |
| `src/test/java/imagejai/engine/security/TagSuggestionEngineTest.java` | NEW | Regex parsing + override |
| `src/test/java/imagejai/engine/security/BriefNudgerTest.java` | NEW | All three mechanisms |

## Implementation sketch

### Dialog layout

```
┌─── Browse Files — MOAB2_AF488/ ────────────────────── Pseudonymised ─┐
│                                                                      │
│  Search: [ 8w wt                              ]   ☑ Show tokens     │
│                                                                      │
│ ┌─────────────────────────────────────────────────────────────────┐ │
│ │ ☑  image-7a3f.lif:1   wt_8w_male_001    8w  wt  4ch  4096³      │ │
│ │ ☑  image-7a3f.lif:2   wt_8w_male_002    8w  wt  4ch  4096³      │ │
│ │ ☐  image-7a3f.lif:3   ko_4w_female_001  4w  ko  4ch  4096³      │ │
│ │ ☑  image-7a3f.lif:11  wt_8w_female_003  8w  wt  4ch  4096³      │ │
│ │ ...                                                             │ │
│ └─────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  Suggested tag: [ 8 weeks, wild-type                        ] [✎]   │
│                                                                      │
│  The agent will see:                                                │
│   • image-7a3f.lif:1, image-7a3f.lif:2, image-7a3f.lif:11           │
│   • tagged "8 weeks, wild-type"                                     │
│   • 4-channel, 4096×4096×35                                         │
│                                                                      │
│  Delivery: (●) Embedded terminal  ( ) Clipboard  ( ) TCP polling    │
│                                                                      │
│                       [ Cancel ]                  [ Send to agent ▶]│
└──────────────────────────────────────────────────────────────────────┘
```

### TCP handlers

```java
// in TCPCommandServer dispatch:
case "browse_pending_brief" -> {
    boolean pending = selectionBroker.hasPending(session.id());
    return new JSONObject().put("pending", pending);
}
case "get_pending_brief" -> {
    Optional<Brief> b = selectionBroker.consume(session.id());
    if (b.isEmpty()) return new JSONObject().put("pending", false);
    return new JSONObject()
        .put("pending", true)
        .put("tokens", new JSONArray(b.get().tokens()))
        .put("tag", b.get().tag())
        .put("metadata", new JSONObject(b.get().metadata()));
}
```

### Brief nudger

```java
public final class BriefNudger {
    public void deliver(Brief b, NudgeMechanism mech, AgentSession session) {
        String text = composeNudge(b);
        switch (mech) {
            case EMBEDDED_PTY  -> session.embeddedPty().sendText(text + "\n");
            case CLIPBOARD     -> { copyToSystemClipboard(text); toast(...); }
            case TCP_POLLING   -> { /* nothing — agent will call browse_pending_brief */ }
        }
    }
}
```

### Series scanner

```java
public List<SeriesInfo> scan(Path file) {
    try (IFormatReader r = new ImageReader()) {
        r.setId(file.toString());
        List<SeriesInfo> out = new ArrayList<>();
        for (int i = 0; i < r.getSeriesCount(); i++) {
            r.setSeries(i);
            out.add(new SeriesInfo(
                i,
                r.getSeriesMetadata().getOrDefault("Image name", "").toString(),
                r.getSizeC(), r.getSizeT(),
                r.getSizeX(), r.getSizeY(), r.getSizeZ()
            ));
        }
        return out;
    }
}
```

Cached on `(path, mtime)` key.

## Exit gate

1. `mvn compile`, `mvn test` pass.
2. `SeriesScannerTest`: scans a sample multi-series .lif from
   `src/test/resources/`, returns correct series count + sizes
   without reading pixels.
3. `SelectionBrokerTest`: enqueue → `hasPending=true` →
   `consume` returns brief, second `consume` returns empty.
4. `TagSuggestionEngineTest`: default patterns extract `8w wt`
   from labels `wt_8w_male_001/002/011`; override via
   `.imagejai-tags.yml` works.
5. `BriefNudgerTest`: all three mechanisms produce correct
   text/clipboard/no-op behaviour. Mock PTY assertion for
   EMBEDDED case.
6. Integration with stage 02: brief tokens reverse-resolve via
   `open_image_by_token` to correct file + series.
7. Manual: open Browse Files dialog in a real folder with a
   multi-series .lif → series populate, search filters, select 3
   rows, suggested tag appears, click Send → embedded PTY: nudge
   typed; external CLI: clipboard populated.
8. Manual: agent (Claude Code) receives nudge, calls
   `get_pending_brief`, then `open_image_by_token` for each token
   — images open in Fiji, real paths never appear in agent
   transcript.
9. Audit CSV: rows for `get_pending_brief` and each
   `open_image_by_token` call with redacted notes.

## Known risks

- Series scanning a large folder (.lif files of 1+ GB × 30 files)
  can be slow on first open. Cache aggressively; show progress;
  scan in background thread on dialog open.
- Bio-Formats `IFormatReader.setId` can fail on corrupt files —
  show the file as "unreadable" rather than crashing the dialog.
- Tag suggestion engine false positives (e.g. `M3` matched as
  "male" by overzealous regex). Default patterns are conservative;
  user can edit before send.
- Clipboard nudge can be overwritten by other clipboard activity
  in the 30-second window between Send and Paste. Document this
  as a known limitation; embedded PTY mode avoids it entirely.
- Auto-polling adds a small constant load: one
  `browse_pending_brief` per agent turn. Cheap response (24 bytes)
  and not audited unless pending=true; net cost is sub-millisecond
  per turn.
- The `.imagejai-tags.yml` override file must NOT be sent to the
  agent (it contains label patterns that may leak conventions).
  Document; keep file scope local to `BrowseFilesDialog` only.
