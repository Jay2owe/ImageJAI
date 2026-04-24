# Stage 11 — Session history rail section

## Why this stage exists

Stage 04 has been silently writing every agent-run macro to
`AI_Exports/.session/code/` for weeks. This stage surfaces that
journal as a rail section so the biologist can re-run any macro
the agent produced — with one click, no copy-paste, no LLM
round-trip. Right-click lets them promote a good macro to
`agent/macro_sets/` (picked up by stage 10's button), so
"something the agent made that worked" becomes a permanent part
of the user's toolkit.

This is the feature the user originally asked for. Everything
prior was infrastructure.

## Prerequisites

- Stage 04 `_COMPLETED` (journal writing data).
- Stage 07 `_COMPLETED` (`TcpHotline`, rail scaffolding).

## Read first

- `docs/embedded-agent-widget/00_overview.md`
- `docs/embedded-agent-widget/PLAN.md` §2.10 (full UI spec)
- `src/main/java/imagejai/engine/SessionCodeJournal.java`
  (from stage 04) — especially the `addListener` contract and
  `Entry` shape
- `src/main/java/imagejai/ui/LeftRail.java` (from
  stages 07, 09, 10)
- `src/main/java/imagejai/ui/TcpHotline.java` (from
  stage 07) — the re-run channel

## Scope

- New `SessionHistoryPanel` in the `LeftRail`, placed **above**
  the Macro sets button in the Fiji-hotlines section.
- Collapsible section; collapse state persisted via `ij.Prefs`
  (`ai.assistant.history.collapsed`).
- Row format (newest first): `HH:MM:SS  <name>  ×<run_count>  <N>L`
  where `<N>L` is line count. Failed entries render in red with ⚠
  prefix; tooltip shows `failureMessage`.
- Subscribes to `SessionCodeJournal` via `addListener` — updates
  rows on every `record()` call.
- **Single click** — copy code to OS clipboard; 1 s toast "Copied
  `<name>`".
- **Double click** — re-run via `TcpHotline.executeMacro(code,
  "rail:history")`. `source=rail:history` is how the journal's
  dedup logic in stage 04 bumps `runCount` instead of creating a
  new entry.
- **Right click** → popup menu:
  - *Open in editor* — writes to a temp `.ijm`, opens via
    `Desktop.open()`.
  - *Save to Macro sets…* — `JOptionPane` input for a name;
    copies the stored code to `agent/macro_sets/<name>.ijm`.
    Entry then appears in stage 10's Macro sets popup.
  - *Show file* — reveals the entry's
    `AI_Exports/.session/code/<file>` via `Desktop.open()` on its
    parent folder.
  - *Remove from history* — removes from the ring (does NOT
    delete the disk file — audit trail preserved).
- **Section-header menu** (⋯ button):
  - *Clear history* — empties ring; optional "also delete files"
    checkbox (confirm-destructive).
  - *Filter: exclude plumbing* — toggle that hides entries whose
    name was produced by the "first plumbing op" fallback in
    strategy 2 of the auto-namer.
  - *Persist across restarts* — toggle that, when on, reads
    `AI_Exports/.session/code/INDEX.json` on plugin start and
    repopulates the ring.
- All toggles persisted via `ij.Prefs`.

## Out of scope

- Any change to `SessionCodeJournal` logic — stage 04 owns that.
  If the UI needs data the journal doesn't expose, add a method
  to the journal and document it.
- Adding new TCP commands to surface the journal — never. The UI
  reads the journal singleton directly (in-process Java call); it
  does not go through TCP.
- Exporting history as CSV / shoring up the `INDEX.json` schema
  for public consumption — later milestone if requested.

## Files touched

| Path                                                                     | Action | Reason                                             |
|--------------------------------------------------------------------------|--------|----------------------------------------------------|
| `src/main/java/imagejai/ui/SessionHistoryPanel.java`          | NEW    | Rail section, renderer, click handlers             |
| `src/main/java/imagejai/ui/LeftRail.java`                     | MODIFY | Mount the panel above Macro sets                   |

## Implementation sketch

```java
public final class SessionHistoryPanel extends JPanel
        implements SessionCodeJournal.Listener {

    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> list = new JList<>(model);

    public SessionHistoryPanel() {
        list.setCellRenderer(new HistoryRowRenderer());
        list.addMouseListener(new ClickDispatcher());
        SessionCodeJournal.INSTANCE.addListener(this);
        refresh();
    }

    @Override public void onChange() { SwingUtilities.invokeLater(this::refresh); }

    private void refresh() {
        model.clear();
        for (Entry e : SessionCodeJournal.INSTANCE.snapshot()) {
            if (excludePlumbing && e.isPlumbingOnly()) continue;
            model.addElement(e);
        }
    }

    private void onDoubleClick(Entry e) {
        TcpHotline.executeMacro(e.code, "rail:history");
    }

    private void onSaveAsMacroSet(Entry e) {
        String name = JOptionPane.showInputDialog(this,
            "Save as macro set:", e.name);
        if (name == null || name.isBlank()) return;
        Path dest = Paths.get("agent/macro_sets", name + ".ijm");
        Files.writeString(dest, e.code);
        toast("Saved " + name + ".ijm");
    }
}
```

Row renderer (rough):
```
09:42:17  gaussian_blur__auto_threshold__analyze_particles  ×3  12L
09:40:02  count_dapi_nuclei_in_field_3                       ×1   7L
09:39:45  ⚠ duplicate__close_all                             ×1   3L   (red)
```

## Exit gate

1. Launch an agent, ask it to run three distinct macros. The
   panel updates in real time with three rows, newest at top.
2. Click any row once → code is on the clipboard; toast shown.
3. Double-click any row → macro re-runs via TCP; Fiji responds
   as expected; row's `run_count` increments (visible in the
   panel).
4. Right-click → *Save to Macro sets…* → saves to
   `agent/macro_sets/`; the macro then appears in stage 10's
   Macro sets popup.
5. Right-click → *Remove from history* removes the row but the
   file on disk stays.
6. *Filter: exclude plumbing* hides entries whose name is
   plumbing-only (e.g. `duplicate__close_all`).
7. *Persist across restarts* toggle: with it on, close Fiji,
   reopen → panel repopulates from `INDEX.json`.
8. Re-running a journal entry via double-click does NOT create a
   duplicate journal entry (stage 04's `rail:history`-source
   dedup rule is wired).

## Known risks

- Listener back-pressure: if the agent runs 100 macros in a
  burst, Swing repaints will pile up. Debounce `onChange()` to
  at most 4 Hz using a `SwingTimer`.
- Right-click on Linux / GTK LAF sometimes eats popup triggers.
  Use `MouseEvent.isPopupTrigger()` on both `mousePressed` and
  `mouseReleased` to catch both platforms' conventions.
- Cross-restart persistence races with stage 04 still writing.
  The journal must load `INDEX.json` once on first use and fold
  the entries **before** accepting any new `record()` calls.
  Handle via a lazy-init flag inside the journal.
