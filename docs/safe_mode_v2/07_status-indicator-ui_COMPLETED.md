# Stage 07 — Status indicator UI

A coloured dot on the Fiji toolbar (always visible) plus a coloured
indicator in Fiji's main status bar. Hover or click either to see the
last 3 safe-mode events in plain English.

## Why this stage exists

Stages 03–06 emit safe-mode events into `FrictionLog` but the
biologist has no way to see them without grep'ing the JSONL file.
A single-glance signal — green / amber / red — tells them at any
moment whether the agent has done anything risky in this session,
without requiring a new panel or popup mid-work.

## Prerequisites

- Stage 02 (`_COMPLETED`) — needs `enabledSafeModeOptions(caps)`
- Stages 03, 04, 05, 06 (`_COMPLETED`) — these are the event sources

## Read first

- `docs/safe_mode_v2/00_overview.md`
- `src/main/java/imagejai/engine/EventBus.java` — pub/sub
  pattern; subscribe to `safe_mode.*` topics
- `src/main/java/imagejai/engine/FrictionLog.java` —
  in-memory ring (capacity 100); for the tooltip we read the most
  recent N entries
- `src/main/java/imagejai/engine/AgentLauncher.java` —
  may host an alternative panel-based fallback if user opts in
- ImageJ APIs:
  - `IJ.getInstance()` returns the main `ImageJ` frame; its toolbar
    is `Toolbar.getInstance()`
  - Custom toolbar tools: `Toolbar.addPlugInTool()` for clickable
    icons (used elsewhere in this project — search for examples)
  - Status bar: `IJ.showStatus(msg)` writes the strip; we should
    NOT clobber Fiji's own messages — paint a small coloured square
    in the bottom-right corner via a custom `JComponent` overlay

## Scope

- **Toolbar dot.** Add a small colored-circle tool to Fiji's toolbar:
  green / amber / red. Updates within 100 ms of any new safe-mode
  event. Click → opens a small floating panel showing the last 3
  events with timestamps and plain-English descriptions.
- **Status-bar indicator.** Paint a 12×12 coloured square in the
  bottom-right corner of Fiji's main window status strip. Same
  colour as the toolbar dot. Tooltip on hover shows the most recent
  event title.
- **Event source.** Subscribe to `safe_mode.*` topics on the
  `EventBus`. Each event determines colour:
  - green: `safe_mode.passed`, `safe_mode.snapshot.released`
  - amber: `safe_mode.warned`, `safe_mode.roi_auto_backup`,
    `safe_mode.calibration_warning`
  - red: `safe_mode.blocked`, `safe_mode.queue_storm_blocked`,
    `safe_mode.snapshot.committed` (i.e. macro failed and we kept
    the snapshot)
- **Decay.** After 60 seconds with no new event, both indicators
  decay back to green (last red event is still visible in the
  click-out panel).
- **Master-switch awareness.** When `safe_mode = false`, paint a
  grey dot with tooltip "Safe Mode OFF — no checks running."

## Out of scope

- A full safety dashboard (Tier 2 brainstorm idea — defer to v3).
- Audio cues (deferred from brainstorm).
- A separate AgentLauncher tab — the master toggle in AgentLauncher
  (Stage 02) is enough; the indicator lives in Fiji proper.

## Files touched

| Path | Change | Reason |
|------|--------|--------|
| `src/main/java/imagejai/engine/safeMode/SafeModeIndicator.java` | NEW | Toolbar tool implementation; `EventBus` subscriber; colour state machine |
| `src/main/java/imagejai/engine/safeMode/SafeModeStatusBarOverlay.java` | NEW | Bottom-right corner JComponent attached to Fiji's main frame |
| `src/main/java/imagejai/engine/safeMode/SafeModeEventPanel.java` | NEW | Small floating panel (`JFrame` non-modal) showing last 3 events |
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Publish `safe_mode.*` events to `EventBus` (concentrate this in one helper) |
| `src/main/java/imagejai/engine/AgentLauncher.java` | MODIFY | Wire `SafeModeIndicator.installOnStartup()` once Fiji is ready |
| `src/test/java/imagejai/engine/safeMode/SafeModeIndicatorTest.java` | NEW | Headless: verify colour transitions on simulated events |

## Implementation sketch

```java
public final class SafeModeIndicator implements EventBus.Listener {
    private volatile Color color = Color.GREEN;
    private final Deque<Event> recent = new ArrayDeque<>(); // last 3
    private long lastEventNanos = System.nanoTime();

    public void installOnStartup() {
        EventBus.get().subscribe("safe_mode.*", this);
        Toolbar.addPlugInTool(this); // shows as a coloured circle
        SafeModeStatusBarOverlay.install();
        // Decay timer
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
        ses.scheduleAtFixedRate(this::checkDecay, 1, 1, TimeUnit.SECONDS);
    }

    @Override public void onEvent(String topic, Map<String, Object> data) {
        recent.offerFirst(new Event(topic, data, System.currentTimeMillis()));
        while (recent.size() > 3) recent.pollLast();
        color = colorFor(topic);
        lastEventNanos = System.nanoTime();
        repaintAll();
    }

    private Color colorFor(String topic) {
        if (topic.contains("blocked") || topic.endsWith(".committed")) return Color.RED;
        if (topic.contains("warned") || topic.contains("backup")) return Color.ORANGE;
        return Color.GREEN;
    }
}
```

Plain-English event lines (used in the tooltip / event panel):
- `safe_mode.queue_storm_blocked` → "Blocked: agent tried to queue a
  second macro on `<image>` while one was paused on a dialog."
- `safe_mode.calibration_warning` → "Warned: macro would reset
  calibration on `<image>` to 1 px/μm."
- `safe_mode.roi_auto_backup` → "Backed up `<n>` ROIs to
  `AI_Exports/.safemode_roi_<ts>.zip` before agent reset them."

## Exit gate

1. `mvn compile` clean.
2. `SafeModeIndicatorTest` passes (headless): inject events through
   `EventBus`, assert `color()` transitions and `recent()` contents.
3. Manual: launch Fiji with the plugin, look for:
   - Toolbar circle visible after Fiji startup
   - Status-bar square visible in bottom-right
   - Both green by default
   - Trigger any safe-mode block (e.g. send a `File.delete` macro via
     agent) → both flip red within 1 second
   - Click toolbar circle → small panel shows the block event
   - Wait 60 s with no new event → both decay to green
   - Toggle Safe Mode off in AgentLauncher → both flip grey
4. Existing Fiji toolbar layout still works; no other tools displaced.

## Known risks

- Adding a custom toolbar tool may conflict with users who already
  fill all toolbar slots. Use `Toolbar.addPlugInTool` (which appends
  rather than replaces) and document.
- Status-bar overlay paints over `IJ.showStatus` output if positioned
  wrong — keep the overlay in the bottom-right corner and small
  enough that Fiji's normal messages remain readable.
- Headless Fiji has no toolbar — `SafeModeIndicator.installOnStartup`
  must early-return without error in `IJ.isHeadless()`.
- The event panel is a non-modal `JFrame`; user may close it. That's
  fine; reopens on next click.
