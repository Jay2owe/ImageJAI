# Destructive-Operation Interceptor

## Idea

Before executing a macro or script, scan it for operations that
delete, overwrite, or irreversibly mutate files and protected
state. If any are found and the agent hasn't explicitly confirmed
destructive mode for this call, reject with a structured error
naming the operations found.

## Why

Every transcript round includes at least one moment where an
agent could have clobbered user data — saved over the original
with a file dialog it didn't understand, deleted a temp file it
got confused about, or called `run("Revert")` on an image with
unsaved analysis. The TCP server is the single checkpoint every
agent flows through — the block has to live there to cover
Claude, Codex, Gemma, and Gemini uniformly.

## What counts as destructive

Grouped by severity. Levels escalate: `warn` → prompt → reject.

### Level 1 — Reject by default

- File deletion: `File.delete(...)`, `new File(...).delete()` in
  Groovy, `os.remove` / `os.unlink` / `shutil.rmtree` in Jython
- Shell deletion: any `exec("rm ...")`, `exec("del ...")`,
  `IJ.runPlugIn("Shell", "rm ...")`
- Saving over the original: `saveAs` where the target path equals
  the active image's `getOriginalFileInfo().directory + fileName`
- `close("*")` / `run("Close All")` while the active image has
  unsaved measurements

### Level 2 — Prompt (reject unless confirmed this call)

- `saveAs` where the target path is inside the Fiji installation
  directory, the Dropbox root, or the current working directory
  root (as opposed to `AI_Exports/`, per project house rules)
- `run("Revert")` on an image with pending ROIs, results rows, or
  overlays
- `IJ.runMacroFile(...)` pointing outside the project tree
- Wiping `RoiManager`, `Results`, or `Log` windows mid-session

### Level 3 — Warn only

- `Enhance Contrast normalize=true` on an image that will be
  measured (per house rules — it rewrites pixel values)
- Renaming the active image to a name already held by another
  open image

## The per-call override

Destructive mode is requested per `execute_macro` call, not per
session:

```json
{"command": "execute_macro",
 "code": "File.delete('/tmp/old_mask.tif');",
 "destructive": {"confirmed": true,
                 "scope": ["File.delete:/tmp/old_mask.tif"]}}
```

The `scope` is a whitelist of specific operations the agent is
authorising *for this one call*. Anything in the macro that
matches a destructive pattern but isn't in `scope` still trips
the block. Scope entries are exact matches, not wildcards.

This forces the agent to name what it's about to destroy — which
both exposes accidents and creates a clean audit trail.

## Response shape when blocked

```json
{"ok": true, "result": {
  "success": false,
  "error": {
    "code": "DESTRUCTIVE_OP_BLOCKED",
    "category": "blocked",
    "retry_safe": false,
    "message": "Macro contains destructive operations not in confirmed scope.",
    "operations": [
      {"op": "File.delete", "target": "/home/user/raw/original.tif",
       "line": 3, "severity": "reject"},
      {"op": "saveAs_overwrite_original", "target": "/home/user/raw/original.tif",
       "line": 7, "severity": "reject"}
    ],
    "recovery_hint": "If these are intended, resend with destructive.scope listing each target explicitly."
  }
}}
```

The operations array is the actionable part — the agent sees
exactly what it did wrong and can either fix the macro or resend
with a narrow scope.

## Implementation sketch

New file `engine/safeMode/DestructiveScanner.java`:

```java
public final class DestructiveScanner {
    public static List<DestructiveOp> scan(String code, String language,
                                           Session session) {
        List<DestructiveOp> ops = new ArrayList<>();
        // Regex + AST-lite matches per language
        // (macro language, Groovy, Jython)
        scanDeletes(code, ops);
        scanSaveAsOverwrites(code, ops, session.activeImagePath());
        scanReverts(code, ops, session);
        scanCloseAll(code, ops, session);
        return ops;
    }
}
```

Called from `handleExecuteMacro` before `rehearse`:

```java
List<DestructiveOp> ops = DestructiveScanner.scan(code, lang, session);
List<DestructiveOp> unconfirmed = ops.stream()
    .filter(o -> o.severity != WARN && !confirmedScope.contains(o.signature()))
    .toList();
if (!unconfirmed.isEmpty()) {
    return destructiveBlockedReply(unconfirmed);
}
// proceed to rehearse, then execute
```

## Logging

Every destructive-op attempt — whether blocked, confirmed, or
warned — is written to `FrictionLog` tagged with `agent_id`,
`session_id`, the operation, the target path, and the outcome.
Creates an audit trail and surfaces patterns per-agent (e.g.
"Gemma attempts `File.delete` 3× more often than Claude").

## Boundaries

- Not a sandbox. Agents that bypass this via a custom plugin call
  (`IJ.runPlugIn("some.destructive.Plugin")`) can still delete
  files. Out of scope; Fiji itself has no plugin sandbox.
- The scanner is regex + keyword, not a full parser. Obfuscated
  code paths (`runMacro("File.del" + "ete('...');")`) will slip
  through. We accept this — destructive mode is a guardrail for
  honest mistakes, not a security boundary.
- A user running the Fiji GUI directly can still do anything.
  This scanner only sees macros arriving over TCP.

## Files

- Server: `src/main/java/imagejai/engine/safeMode/DestructiveScanner.java`
  (new), `TCPCommandServer.handleExecuteMacro` (integration), new
  error code `DESTRUCTIVE_OP_BLOCKED` in the structured-error table
- Python wrappers: `ij.py` learns a `--destructive` flag that
  maps to the `destructive` field; `agent/CLAUDE.md` and
  `agent/gemma4_31b/GEMMA.md` gain a "Destructive operations"
  section explaining the scope mechanism
- Tests: one per destructive category in transcripts; one test
  confirming `AI_Exports/` writes pass through without prompting

## Failure modes

- False positives on legitimate overwrites. Every
  save-to-`AI_Exports/` call must be on the allow-list so agents
  aren't asking permission to do the thing they're supposed to do.
- Agent confirms too liberally (sends `scope: ["*"]`). Reject
  wildcard scopes at the protocol layer — scope entries must be
  exact operation signatures.
- User-driven manual operations in the Fiji GUI bypass this
  entirely. Document this clearly so no one thinks it's a
  security feature.
