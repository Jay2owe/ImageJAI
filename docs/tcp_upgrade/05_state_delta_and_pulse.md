# Step 05 — `stateDelta` and `pulse`

## Motivation

`handleExecuteMacro` already tracks what changed between
pre-macro and post-macro state — it emits `newImages`,
`resultsTable` (only when CSV length changed), `logDelta`,
`dismissedDialogs`. These are scattered top-level keys with
inconsistent presence.

Gemma also burns turns calling `get_state` just to answer "what
image is active now? how many ROIs? did results grow?" That
question has a 20-token answer, not a 700-token one.

This step does two small things:
1. Groups the existing diff output into a single `stateDelta`
   sub-object.
2. Adds a `pulse` string — a one-line readout of session state,
   always present for agents that asked for it.

## End goal

Reply envelope:

```json
{"ok": true, "result": {
  "success": true,
  "ranCode": "...",
  "stateDelta": {
    "activeImage": {"title": "blobs-1", "from": "blobs"},
    "windows": {"added": ["blobs-1"], "removed": []},
    "resultsRows": {"added": 3, "total": 12},
    "openDialogs": {"opened": [], "closed": ["Macro Error"]},
    "logBytes": 412,
    "logTruncated": false
  },
  "pulse": "1 img (blobs-1 8-bit 512x512) · ROI: 0 · results: 12 rows · last thresh: Otsu=142"
}}
```

`stateDelta` keys appear only if they changed. `pulse` is
present only when `caps.pulse == true`.

## Scope

In:
- Refactor `handleExecuteMacro` to collect diffs into a
  `StateDelta` struct, then serialise.
- Same for `handleRunPipeline`, `handleInteractDialog`,
  `handleRunScript` — any command that mutates state.
- New `PulseBuilder` that produces the one-line string from
  current `StateInspector` output.
- Capability gating: `caps.stateDelta` (default true),
  `caps.pulse` (default per-agent, **false for Claude**).

Out:
- No new diff sources. This is a re-homing exercise for existing
  diffs plus one string.

## Read first

- `src/main/java/imagejai/engine/TCPCommandServer.java`
  — `handleExecuteMacro`, `handleRunPipeline`,
  `handleInteractDialog`, `handleRunScript`. Identify every
  `newImages`, `resultsTable`, `logDelta`, `dismissedDialogs`
  emitter.
- `src/main/java/imagejai/engine/StateInspector.java`
  — for pulse's data sources.
- `docs/tcp_upgrade/01_hello_handshake.md` — caps.

## `stateDelta` field reference

Every field is optional; present only when it changed.

| Field | Type | Meaning |
|---|---|---|
| `activeImage` | `{title, from}` | Active image title changed; `from` is previous title. |
| `windows` | `{added[], removed[]}` | Any window change including non-image windows. |
| `resultsRows` | `{added, total}` | Rows appended to the Results table (not total replacement). Negative `added` means cleared. |
| `resultsCleared` | bool | True when the table was wiped entirely. |
| `roiManager` | `{added, total}` | ROIs added/removed. |
| `openDialogs` | `{opened[], closed[]}` | Modal dialogs opened or closed mid-command. |
| `logBytes` | int | Bytes appended to Log. |
| `logTruncated` | bool | True if the logDelta string was cut at cap. |
| `logDelta` | string | Capped at 16 KB; gated behind `caps.verbose` for small-context agents. |

The top-level keys that currently exist (`newImages`,
`resultsTable`, `logDelta`, `dismissedDialogs`) remain for
legacy clients via the caps gate — if `caps.stateDelta == false`,
emit the flat shape. No double emission when the gate is on.

## `pulse` format

Compact, fixed-ish structure so agents can parse or skim:

```
1 img (TITLE BITDEPTH WxH[xN]) · ROI: N · results: N rows · last thresh: METHOD=VALUE
```

Missing parts collapse:

```
0 imgs
```

```
2 imgs (active=blobs 8-bit 512x512) · ROI: 42 · results: 0 rows
```

Token cost: ~20–40 tokens. Generated once per reply from
`StateInspector`.

`PulseBuilder` sketch:

```java
public final class PulseBuilder {
    public static String build(StateInspector s) {
        StringBuilder sb = new StringBuilder();
        int n = s.openImageCount();
        sb.append(n).append(n == 1 ? " img" : " imgs");
        ImagePlus a = s.activeImage();
        if (a != null) {
            sb.append(" (").append(a.getTitle()).append(" ")
              .append(a.getBitDepth()).append("-bit ")
              .append(a.getWidth()).append("x").append(a.getHeight());
            if (a.getStackSize() > 1) sb.append("x").append(a.getStackSize());
            sb.append(")");
        }
        int rois = RoiManager.getInstance() != null
            ? RoiManager.getInstance().getCount() : 0;
        sb.append(" · ROI: ").append(rois);
        int rows = ResultsTable.getResultsTable() != null
            ? ResultsTable.getResultsTable().size() : 0;
        sb.append(" · results: ").append(rows).append(" rows");
        String thresh = s.lastThreshold();  // tracked elsewhere
        if (thresh != null) sb.append(" · last thresh: ").append(thresh);
        return sb.toString();
    }
}
```

`s.lastThreshold()` is new bookkeeping — track the most recent
`setAutoThreshold(...)` or `setThreshold(...)` call by hooking
into macro execution. Optional; pulse works without it, just with
one fewer field.

## Capability gates

Default behaviour by agent:

| Cap | Gemma | Claude | Codex | Gemini |
|---|---|---|---|---|
| `stateDelta` | true | true | true | true |
| `pulse` | true | **false** | true | true |
| `logDelta` (full) | false (logBytes only) | true | true | true |

Claude gets no pulse because Claude Code hooks already inject
per-turn state. Duplication would waste context.

## Tests

- Macro that creates an image → `stateDelta.activeImage` and
  `stateDelta.windows.added` present, log unchanged so `logBytes`
  absent.
- Macro that appends to Results → `stateDelta.resultsRows.added:
  N, total: M`.
- Macro that does nothing observable → `stateDelta` present but
  empty (or absent entirely; pick one and be consistent).
- `caps.pulse: false` → no `pulse` field regardless of what
  happened.
- `caps.stateDelta: false` → legacy flat keys (`newImages`,
  `resultsTable`, `logDelta`, `dismissedDialogs`) emitted
  instead.

## Failure modes

- `lastThreshold` tracking misses scripted thresholds via Java
  APIs. Best-effort; skip if no data.
- `pulse` in a session with 40 images open becomes long. Cap the
  image-list portion (`N imgs` with active name only, never list
  all titles inline).
- Legacy clients fed new shape crash. Mitigated by caps gate and
  the no-hello fallback.
- Two simultaneous `execute_macro` calls (if that ever happens)
  racing on `StateInspector`. The existing `MACRO_MUTEX` already
  serialises execute paths; pulse reads happen post-mutex so
  state is consistent.
