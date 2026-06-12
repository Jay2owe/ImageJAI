# Step 03 — Canonical Macro Echo (`ranCode`)

## Motivation

When Gemma sends `setAutoThreshold("Otzu")`, ImageJ silently
normalises it to `setAutoThreshold("Default")` because "Otzu"
isn't a valid method. Gemma has no way to know — the reply says
success, and she carries on with a mental model that doesn't
match reality.

The ImageJ Recorder holds the canonical macro string for anything
the interpreter just ran. We're currently ignoring it. Echoing
that string back — only when it differs from what the agent sent,
or when the call failed — makes every normalisation visible on
the very next turn without inflating context in the common case.

## End goal

Reply for `execute_macro` gains an optional field:

```json
{"ok": true, "result": {
  "success": true,
  "ranCode": "setAutoThreshold(\"Default\");",
  ...
}}
```

Present only when one of:
- The canonical macro differs from the submitted macro (after
  whitespace normalisation), OR
- `success: false` (failure cases always include it for debugging).

Absent otherwise — no cost on the common case where what was sent
equals what ran.

## Scope

In:
- A new server helper that activates the Recorder in script mode,
  runs the macro through ImageJ, captures the recorded text, and
  restores the Recorder's prior state.
- Integration in `handleExecuteMacro`: compute `ranCode`, compare
  to submitted code, decide whether to include.
- Integration in `handleInteractDialog`: dialog OKs should also
  produce a `ranCode` so UI clicks become reproducible macros.
- Capability gate: `caps.ranCode` (default true).

Out:
- Recording for Groovy/Jython (`run_script`) — IJ Recorder handles
  macro language only. Script canonicalisation is a separate
  problem not addressed here.

## Read first

- `src/main/java/imagejai/engine/TCPCommandServer.java`
  — `handleExecuteMacro` and `handleInteractDialog`.
- ImageJ Recorder API:
  https://imagej.net/ij/developer/api/ij/ij/plugin/frame/Recorder.html
  — specifically `Recorder.setCommand()`, `Recorder.getText()`,
  `Recorder.scriptMode()`, `Recorder.setScriptMode(String)`.
- `docs/tcp_upgrade/01_hello_handshake.md` — how caps reach here.

## Mechanics

The Recorder is a Fiji-wide singleton. Activating it for every
macro run is fine — it's already on during normal user sessions.
The trick is capturing the *delta* of its text between the start
and end of one command.

```java
private String captureCanonicalMacro(String submitted,
                                     Runnable execute) {
    Recorder rec = Recorder.getInstance();
    boolean alreadyOpen = rec != null;
    if (!alreadyOpen) {
        new Recorder(/*showFrame=*/false);
        rec = Recorder.getInstance();
    }
    int textLenBefore = rec.getText() == null ? 0 : rec.getText().length();
    String priorScriptMode = Recorder.scriptMode();
    Recorder.setScriptMode("Macro");  // canonical macro language form

    try {
        execute.run();
    } finally {
        String full = rec.getText() == null ? "" : rec.getText();
        String delta = full.length() > textLenBefore
            ? full.substring(textLenBefore) : "";
        Recorder.setScriptMode(priorScriptMode);
        // if we opened it, close it to avoid a trailing frame
        if (!alreadyOpen) rec.close();
        return delta.strip();
    }
}
```

Diff rule:

```java
String ran = captureCanonicalMacro(code, () -> IJ.runMacro(code));
boolean differs = !normalise(ran).equals(normalise(code));
if (differs || !success) {
    result.addProperty("ranCode", ran);
}
```

`normalise` here is whitespace + trailing-semicolon collapse. It
shouldn't be aggressive — "Otzu" vs "Default" must survive as a
diff, but "run('X');\n" vs "run('X');" should not.

## Edge cases to handle

- **Recorder already open and recording a user session.** Don't
  stomp on their text. The `textLenBefore` snapshot isolates our
  delta. Don't close the Recorder if the user opened it.
- **Recorder disabled globally** (some Fiji configurations). If
  `Recorder.getInstance()` stays null after `new Recorder`, skip
  canonical capture and omit `ranCode`.
- **Multi-line submitted macros.** Some lines may not record
  (pure Javascript-style statements don't always emit). Include
  whatever the Recorder got; a partial `ranCode` is still useful.
- **Dialog-driven macros from `interact_dialog`.** Dialog OK
  events fire through `Command` and do record. The same wrapper
  works — just apply it around the dialog click.

## Capability gating

`caps.ranCode: true` by default. An agent on a tight token budget
(Gemma with trouble) might disable it:

```json
{"capabilities": {"ranCode": false}}
```

Then the field never appears even on diffs. Claude, Codex, Gemini
all default to enabled.

## Tests

- Submit `run("Gaussian Blur...", "sigma=2")`: no diff, `ranCode`
  absent.
- Submit `setAutoThreshold("Otzu")`: diff, `ranCode` present with
  `setAutoThreshold("Default")`.
- Submit malformed macro: `success: false`, `ranCode` present
  with whatever the Recorder captured before the fail.
- Interact with a GenericDialog and click OK: `ranCode` present
  with the equivalent `run("Plugin...", "args")` call.
- User has Recorder open with their own text: our delta only
  reads their new additions; their text is untouched.

## Failure modes

- Recorder lag: on some builds `getText()` flushes on EDT tick.
  Wrap the execute with `EventQueue.invokeAndWait` or add a
  short synchronous flush. Measure the overhead; if > 20ms, gate
  behind `caps.verbose`.
- False diffs from whitespace differences cluttering the reply.
  Keep `normalise` conservative and tested.
- Memory growth if Recorder text is never cleared on long
  sessions. Not our problem to solve — Fiji itself manages this —
  but worth a sanity note if the Recorder frame size explodes in
  tests.
