# Stage 02 — Launcher / session refactor

## Why this stage exists

Today's `AgentLauncher.launchAgent(AgentInfo)` returns `boolean`. That
interface cannot represent an embedded session with a live PTY
handle. Before the terminal primitive (stage 05) or plugin-frame
integration (stage 06) can land, the launcher has to return an
`AgentSession` interface, and command-building / cwd / env
construction must be hoisted into a shared `AgentLaunchSpec` so
external and embedded launch paths agree on one source of truth.
This stage is pure refactor — no new user-visible behaviour; every
existing agent still launches externally.

## Prerequisites

- Stage 01 `_COMPLETED`.

## Read first

- `docs/embedded-agent-widget/00_overview.md`
- `docs/embedded-agent-widget/PLAN.md` §2.2 ("Launcher / session
  abstraction")
- `src/main/java/imagejai/engine/AgentLauncher.java`
  (whole file — especially `:46-56` `KNOWN_AGENTS`, `:112-167`
  `launchAgent`, `syncContextFiles`, `findLinuxTerminal`)
- `src/main/java/imagejai/ui/ChatPanel.java:905-952`
  (`showAgentLaunchMenu`, the only current caller)
- `src/main/java/imagejai/ImageJAIPlugin.java:40-135`
  (understands how `ChatPanel` and launcher wire together)
- `src/main/java/imagejai/ConversationLoop.java`
  (depends on concrete `ChatPanel`; needs the `ChatSurface` facade)

## Scope

- New interface `AgentSession` with `info()`, `writeInput(String)`,
  `output()`, `isAlive()`, `exitValue()`, `interrupt()`, `destroy()`.
- New class `AgentLaunchSpec` — plain data: `shellCommand`,
  `workingDir`, `env`, plus a `shellPortableCommand()` helper that
  normalises shell-sensitive entries (Open Interpreter's POSIX
  `$(cat CLAUDE.md)` etc.) so Windows `cmd.exe` doesn't choke.
- New class `ExternalAgentSession` wrapping today's
  `cmd.exe /c start` invocation; `output()` returns an always-empty
  stream, `writeInput` is a no-op, lifecycle methods are
  explicitly best-effort / unsupported on detached terminals.
- Add `Mode` enum: `EXTERNAL`, `EMBEDDED`.
- Refactor `AgentLauncher.launch(AgentInfo, Mode)` to return
  `AgentSession`. For `Mode.EMBEDDED` return a clear
  `UnsupportedOperationException("not yet implemented (stage 05)")`.
- New `ChatSurface` facade in `imagejai.ui` — the minimal
  interface `ConversationLoop` and `ImageJAIPlugin.startTcpServer`
  need from `ChatPanel`. Keeps stage 06's `ChatView` extraction
  from cascading into unrelated files.
- Migrate `ChatPanel.showAgentLaunchMenu` to accept the returned
  `AgentSession`; it can discard the handle for now.
- Normalise `KNOWN_AGENTS` command strings so Windows-sensitive
  fragments (`gh copilot` compound command, Open Interpreter POSIX
  quoting) go through `AgentLaunchSpec.shellPortableCommand()`.

## Out of scope

- Embedded session implementation — stage 05.
- Settings toggle between external and embedded — stage 03.
- `ChatView` extraction — stage 06 (this stage introduces only the
  facade, not the split).

## Files touched

| Path                                                                          | Action | Reason                                          |
|-------------------------------------------------------------------------------|--------|-------------------------------------------------|
| `src/main/java/imagejai/engine/AgentSession.java`                  | NEW    | Interface                                       |
| `src/main/java/imagejai/engine/AgentLaunchSpec.java`               | NEW    | Shared shell-command / cwd / env contract       |
| `src/main/java/imagejai/engine/ExternalAgentSession.java`          | NEW    | Detached-session stub for external terminals    |
| `src/main/java/imagejai/engine/AgentLauncher.java`                 | MODIFY | Return `AgentSession`, accept `Mode`, hoist spec|
| `src/main/java/imagejai/ui/ChatSurface.java`                       | NEW    | Facade for `ConversationLoop` + TCP wiring      |
| `src/main/java/imagejai/ui/ChatPanel.java`                         | MODIFY | Implement `ChatSurface`; accept `AgentSession`  |
| `src/main/java/imagejai/ConversationLoop.java`                     | MODIFY | Depend on `ChatSurface`, not concrete panel     |
| `src/main/java/imagejai/ImageJAIPlugin.java`                       | MODIFY | Pass `ChatSurface` where `ChatPanel` was passed |

## Implementation sketch

```java
public interface AgentSession {
    AgentInfo info();
    void writeInput(String s);          // append \r handled inside
    InputStream output();                // stdout+stderr bytes
    boolean isAlive();
    int exitValue();                     // -1 while alive
    void interrupt();                    // \x03 into PTY
    void destroy();                      // EOF → grace → destroyForcibly
}

public final class AgentLaunchSpec {
    public final List<String> shellCommand;
    public final Path workingDir;
    public final Map<String, String> env;
    public List<String> shellPortableCommand(OperatingSystem os) { ... }
}

public enum Mode { EXTERNAL, EMBEDDED }

public AgentSession launch(AgentInfo info, Mode mode) { ... }
```

`ExternalAgentSession` must honour the detached-terminal reality:
`isAlive()` returns `false` immediately after `cmd.exe /c start`
returns; `exitValue()` returns 0; `interrupt()` / `destroy()`
log a warning and no-op.

## Exit gate

1. `mvn package` succeeds.
2. Every agent in `KNOWN_AGENTS` still launches externally on a fresh
   Fiji build, including Open Interpreter on Windows (which previously
   contained a POSIX-only `$(cat CLAUDE.md)` fragment).
3. `ChatPanel.showAgentLaunchMenu` compiles against the new
   `launch(Info, Mode)` signature.
4. Passing `Mode.EMBEDDED` throws `UnsupportedOperationException`
   with the "stage 05" message.
5. `ConversationLoop` and `ImageJAIPlugin.startTcpServer` type-check
   against `ChatSurface` (not the concrete `ChatPanel`).

## Known risks

- Blast radius (risk #1 in PLAN.md §7). The current `boolean` return
  is read from at least three call sites; audit the full `src/`
  tree with `grep -R "launchAgent\|launch(AgentInfo" src/` before
  deleting the old signature. Keep the old method as a deprecated
  one-liner that delegates if the migration is partial; remove in
  a follow-up commit only once green.
- Windows detached-launch: ensure `ExternalAgentSession` doesn't
  lie — if `isAlive()` returns `true` for a detached cmd window
  that the OS has already orphaned, stage 06's shutdown hook will
  hang waiting. Return `false` immediately.
