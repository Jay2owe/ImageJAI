# Codebase Review - 2026-04-20

## Scope

Reviewed the main executable Java code under `src/main/java`, runtime wiring in the plugin/UI layer, the TCP server and event system, and representative Python/Groovy automation under `agent/` and `agent/scripts/`.

I did not exhaustively review generated artefacts under `target/`, `graphify-out/`, or binary/data outputs under `agent/work_in_progress/**/output`.

## Validation Notes

- Static review only. I attempted a Maven compile, but this sandbox could not access Maven's default local repository path, so I could not use a full build as a validator here.
- Python bytecode compilation was also unavailable in this sandbox, so Python findings are from source inspection and path/search validation.

## Critical

1. **Chat/pipeline macro timeouts can leave zombie macros running, and those paths still allow overlapping `IJ.runMacro()` calls.**  
   Refs: `src/main/java/uk/ac/ucl/imagej/ai/engine/CommandEngine.java:76-87`, `src/main/java/uk/ac/ucl/imagej/ai/engine/CommandEngine.java:196-205`, `src/main/java/uk/ac/ucl/imagej/ai/engine/CommandEngine.java:225-299`, `src/main/java/uk/ac/ucl/imagej/ai/engine/TCPCommandServer.java:94-105`, `src/main/java/uk/ac/ucl/imagej/ai/engine/TCPCommandServer.java:1199-1319`  
   `CommandEngine.executeMacroWithTimeout()` only calls `future.cancel(true)` on timeout. The TCP path already documents that this is not enough for ImageJ macros and added `ij.Macro.abort()` plus a JVM-wide mutex for exactly that reason. The chat, pipeline, reactive, and async-job paths still use the older implementation, so a timed-out or dialog-blocked macro can continue mutating global ImageJ state while later macros start.

2. **The non-TCP macro runner still reports some real ImageJ failures as success.**  
   Refs: `src/main/java/uk/ac/ucl/imagej/ai/engine/CommandEngine.java:80-130`, `src/main/java/uk/ac/ucl/imagej/ai/engine/CommandEngine.java:266-299`, `src/main/java/uk/ac/ucl/imagej/ai/engine/TCPCommandServer.java:1167-1180`, `src/main/java/uk/ac/ucl/imagej/ai/engine/TCPCommandServer.java:1239-1244`, `src/main/java/uk/ac/ucl/imagej/ai/engine/TCPCommandServer.java:1311-1317`, `src/main/java/uk/ac/ucl/imagej/ai/engine/TCPCommandServer.java:1491-1617`  
   `CommandEngine` treats a normal return from `IJ.runMacro()` as success. The TCP path already has extra checks for `Interpreter` error state, `IJ.getErrorMessage()`, log tails, and error dialogs because ImageJ often signals failure without throwing. The chat/pipeline/job/reactive paths still miss those signals, so the assistant can keep reasoning from a false-success state.

## High

3. **Runtime settings changes are saved, but not applied to the live backend/server/launcher.**  
   Refs: `src/main/java/uk/ac/ucl/imagej/ai/ui/ChatPanel.java:721-724`, `src/main/java/uk/ac/ucl/imagej/ai/ui/ChatPanel.java:861-864`, `src/main/java/uk/ac/ucl/imagej/ai/ui/ChatPanel.java:885-892`, `src/main/java/uk/ac/ucl/imagej/ai/ConversationLoop.java:74-85`, `src/main/java/uk/ac/ucl/imagej/ai/ImageJAIPlugin.java:71-83`, `src/main/java/uk/ac/ucl/imagej/ai/ImageJAIPlugin.java:85-87`  
   The profile switcher only updates `settings.activeConfigId`, saves the file, and changes UI text. `ConversationLoop.refreshBackend()` exists but is never called from the UI. `openSettings()` also only saves and refreshes the dropdown. If the plugin starts in TCP-only mode, adding an API key later leaves chat unwired and messages still hit the placeholder branch. TCP enable/port changes are also not applied until restart.

4. **Subscribe detection can hijack non-subscribe requests before real parsing.**  
   Refs: `src/main/java/uk/ac/ucl/imagej/ai/engine/TCPCommandServer.java:350-365`, `src/main/java/uk/ac/ucl/imagej/ai/engine/TCPCommandServer.java:395-398`  
   `isSubscribeCommand()` only checks whether the raw JSON contains the substrings `"subscribe"` and `"command"`. Any request with a literal `"subscribe"` value in some other field will be routed into the streaming code path before the actual `command` value is inspected.

5. **Agent workspace discovery is developer-specific, and the fallback workspace is effectively empty.**  
   Refs: `src/main/java/uk/ac/ucl/imagej/ai/ImageJAIPlugin.java:159-181`  
   The plugin first looks in a hard-coded Jamie Dropbox path and two source-tree locations. If none exist, it silently creates `~/.imagej-ai/agent` and uses that directory even though it does not contain `ij.py`, `CLAUDE.md`, recipes, or helper scripts. Agent launch can therefore appear to work while starting in an unusable workspace.

6. **The predefined Open Interpreter launch command is POSIX-only and broken on Windows.**  
   Refs: `src/main/java/uk/ac/ucl/imagej/ai/engine/AgentLauncher.java:51`, `src/main/java/uk/ac/ucl/imagej/ai/engine/AgentLauncher.java:126-135`  
   The Open Interpreter entry injects `--system_message "$(cat CLAUDE.md)"`. Windows launch goes through `cmd.exe /k`, which does not support `$(...)` command substitution, so this profile will launch with the wrong literal argument or fail outright.

## Medium

7. **Event coalescing drops different events on the same topic, not just duplicates.**  
   Refs: `src/main/java/uk/ac/ucl/imagej/ai/engine/EventBus.java:83-96`  
   The comment says "identical-topic events" within 200 ms are dropped, but the implementation keys only on topic name. Two different `job.progress`, `results.changed`, or `image.*` payloads inside the window lose one update entirely.

8. **`job.completed` frames can publish a negative `elapsedMs`.**  
   Refs: `src/main/java/uk/ac/ucl/imagej/ai/engine/JobRegistry.java:234-255`, `src/main/java/uk/ac/ucl/imagej/ai/engine/JobRegistry.java:296-300`  
   `runJob()` publishes `job.completed` before `endedAt` is set in the `finally` block. `completedFrame()` subtracts `startedAt` from `endedAt`, which is still `0` at that moment.

9. **The TCP server creates one unbounded thread per client connection.**  
   Refs: `src/main/java/uk/ac/ucl/imagej/ai/engine/TCPCommandServer.java:282-297`  
   Every accepted socket spawns a dedicated daemon thread with no executor, queue, or backpressure. A burst of local clients or stalled clients can exhaust threads and memory.

10. **Ollama model refresh uses brittle string scanning instead of JSON parsing.**  
    Refs: `src/main/java/uk/ac/ucl/imagej/ai/ui/SettingsDialog.java:500-545`  
    `parseModelNames()` searches the raw response for every `"name"` token and slices strings by index. This will misread escaped content or any future non-model `name` field in `/api/tags`, even though the codebase already has a proper JSON parser in `OllamaBackend.listModels()`.

11. **Many bundled analysis scripts are hard-coded to one machine/user path and are not portable.**  
    Refs: `agent/scripts/build_panels_v4.py:14`, `agent/scripts/pixel_summary_v4.py:11`, `agent/scripts/verify_alignment_overlay.py:15`, `agent/scripts/apply_correction_v4.groovy:18-19`  
    There are many scripts under `agent/scripts/` that hard-code `C:\Users\Owner\...` experiment and export paths. Those scripts will fail immediately outside that exact filesystem layout and are already inconsistent with the current repository owner's username.

## Low

12. **The TCP request-size guard enforces a character limit, but the error message claims a byte limit.**  
    Refs: `src/main/java/uk/ac/ucl/imagej/ai/engine/TCPCommandServer.java:338-346`, `src/main/java/uk/ac/ucl/imagej/ai/engine/TCPCommandServer.java:590-603`  
    The request reader counts `StringBuilder.length()` and compares it to `TCP_MAX_MESSAGE_SIZE`, then reports the limit as "bytes". Non-ASCII UTF-8 input can exceed the advertised byte cap before being rejected.
