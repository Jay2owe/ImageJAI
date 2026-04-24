# Step 10 — Phantom Dialog Detector

## Motivation

The friction log `ghost-dialog-queued-macros.md` describes a
recurring failure: an agent fires ten macros, none of them
actually run because a modal dialog nobody can see (an invisible
"Overwrite file?" or "Save changes?" prompt) is blocking the
event queue. Every subsequent macro queues behind it silently.

The server already has `get_dialogs` and `close_dialogs`, but
they only fire when the agent remembers to call them. Making
phantom-dialog detection *automatic* after every macro — with an
optional auto-dismiss for safe buttons — closes the class.

## End goal

After every `execute_macro` (and `run_pipeline`,
`interact_dialog`, `run_script`), a 30 ms AWT focus inspection
runs. If any modal window exists in the foreground that wasn't
there at the start of the call, the reply gains:

```json
{"phantomDialog": {
  "title": "Overwrite file?",
  "body": "The file ... already exists. Overwrite?",
  "buttons": ["Yes", "No", "Cancel"],
  "likelyCause": "saveAs without unique filename; setBatchMode not active",
  "autoDismissed": false
}}
```

If the agent's `caps.autoDismissPhantoms` is true **and** the
button set matches an allow-list (`["No", "Cancel"]` kinds —
never `Yes` or `Overwrite`), the server clicks the safe button
and sets `autoDismissed: true`.

## Scope

In:
- New `PhantomDialogDetector` utility: inspects AWT active
  windows, diffs against a pre-command snapshot, classifies.
- Likely-cause heuristics: simple pattern match on title/body.
- Auto-dismiss allow-list.
- Capability gate: `caps.autoDismissPhantoms` — default `false`.
  Reporting is always on.

Out:
- Detecting non-modal dialogs. Those are already covered by
  `get_dialogs` and don't block macros.
- Dismissing destructive buttons. Never — this is explicitly
  restricted to safe actions.

## Read first

- `src/main/java/imagejai/engine/TCPCommandServer.java`
  — `handleGetDialogs`, `handleCloseDialogs`, the existing
  dialog inspection logic.
- `src/main/java/imagejai/engine/DialogWatcher.java`
  — existing dialog observation code; reuse its component
  walkers.
- `docs/ollama_COMPLETED/improvement-loop/conversations/ghost-dialog-queued-macros.md`
  — the motivating pathology.

## Mechanics

```java
public final class PhantomDialogDetector {
    public static Optional<JsonObject> detect(
            Set<Window> preCommandModals,
            AgentCaps caps) {
        Set<Window> nowModal = currentModalWindows();
        nowModal.removeAll(preCommandModals);
        if (nowModal.isEmpty()) return Optional.empty();

        // Pick the front-most new modal
        Window w = frontMostOf(nowModal);
        String title = titleOf(w);
        String body = extractBody(w);
        List<String> buttons = extractButtons(w);

        JsonObject out = new JsonObject();
        out.addProperty("title", title);
        if (body != null) out.addProperty("body", body);
        out.add("buttons", toJsonArray(buttons));
        out.addProperty("likelyCause", guessCause(title, body));

        boolean dismissed = false;
        if (caps.autoDismissPhantoms) {
            String safe = pickSafeButton(buttons);
            if (safe != null) {
                clickButton(w, safe);
                dismissed = true;
            }
        }
        out.addProperty("autoDismissed", dismissed);
        return Optional.of(out);
    }
}
```

Snapshot the modal set at the start of `handleExecuteMacro`:

```java
Set<Window> modalBefore = PhantomDialogDetector.currentModalWindows();
// ... run macro ...
PhantomDialogDetector.detect(modalBefore, caps)
    .ifPresent(d -> result.add("phantomDialog", d));
```

`likelyCause` heuristics (again first-match):

- Title contains "overwrite" or body contains "already exists"
  → `"saveAs without unique filename; setBatchMode not active"`
- Title contains "save" and buttons contain "Don't Save"
  → `"unsaved changes; close() without flattening"`
- Title is "Macro Error" → `"macro error not captured in
  response; detectIjMacroError should have caught this"`
- Fallback → `null`

## Auto-dismiss allow-list

Only buttons whose label is exactly one of:
- `"No"`
- `"Cancel"`
- `"Don't Save"`
- `"Skip"`

Never:
- `"Yes"`, `"OK"`, `"Overwrite"`, `"Save"`, `"Delete"`, `"Proceed"`,
  or any locale variant.

Dismissal is for breaking deadlocks safely, not for making
decisions on the agent's behalf.

## Interaction with the existing `dialogs` reply key

Today's `handleExecuteMacro` already detects open dialogs and
reports them under `dialogs`. Keep that behaviour unchanged.
`phantomDialog` is an additional, more specific signal for the
"this dialog was NOT there before the macro ran, and it's modal"
subset.

## Capability gate

`caps.autoDismissPhantoms`:

| Agent | Default |
|---|---|
| Gemma 31B | `true` (spirals on phantoms, benefits from auto-clear) |
| Claude Code | `false` (human-in-loop; let the agent decide) |
| Codex | `false` |
| Gemini | `false` |

Overridable per-call via a new field on `execute_macro` if
needed:

```json
{"command": "execute_macro",
 "code": "...",
 "autoDismissPhantoms": true}
```

## Tests

- Macro that triggers an "Overwrite?" dialog → detected, not
  auto-dismissed by default.
- Same macro with `caps.autoDismissPhantoms: true` → dismissed
  with "No".
- Macro producing "Save changes?" dialog with `caps.autoDismissPhantoms:
  true` but buttons are `["Save", "Don't Save", "Cancel"]` →
  dismissed with "Don't Save".
- Macro producing a dialog whose buttons are `["Yes", "No"]` with
  body "Delete all files?" → auto-dismissed with "No" (note: the
  word "delete" in the body does not change the allow-list —
  button label is the only criterion).
- Macro with no phantom dialogs → no `phantomDialog` field.
- Modal dialog that was open BEFORE the macro ran → not
  reported.

## Failure modes

- Fast-dismissing dialogs race with the 30 ms inspection.
  Acceptable; they're gone by the time we look. Harmless.
- A plugin's own dialog that the user *wants* to see during a
  pipeline gets auto-dismissed if it's on the allow-list. Caps
  gate is the escape hatch; default is report-only.
- `extractBody`/`extractButtons` reflection-walk might miss
  custom Swing components. Fall back to button-label-only
  detection when body extraction fails.
- Locale-specific button labels (`"Annuler"` vs `"Cancel"`).
  Allow-list is English-only initially; extend as telemetry
  shows localisation in the wild.
