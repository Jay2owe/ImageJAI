# Local Assistant Plan

## 1. Goal

Add a built-in "Local Assistant" to the ImageJAI chat panel. It
accepts plain-English requests such as "what is the pixel size",
"close all images", and "list ROIs", then matches them against a
curated intent library. There is no LLM, API key, internet access, or
external process at runtime. LLMs are used **only at dev time** to
generate the phrasebook (see §6).

The Local Assistant is deterministic and should respond in under
100 ms for read-only intents. It is selected on first launch because
it is the only agent-like option guaranteed to exist. Once the user
chooses an external CLI agent, that choice is persisted.

Scope is **simple task automation, not complex analysis**. The user
verifies results visually. Capture-and-look style verification is the
external-agent path, not the Local Assistant path.

Current code note: external agents are not currently shown in a
persistent dropdown. `AiRootPanel.createHeader()` creates a
model-profile `JComboBox<Settings.ModelConfig>` and a play button. The
play button opens `AiRootPanel.showAgentLaunchMenu()`, which populates a
popup from `AgentLauncher.detectAgents()` and launches the selected CLI
in an external or embedded terminal according to
`Settings.agentEmbeddedTerminal`. Phase A must add the actual agent
selector UI or convert this popup into a persistent selector.

## 2. Why It Exists

- **No setup:** works when Fiji loads the plugin.
- **Free and private:** no tokens and no data leaves the machine.
- **Deterministic:** the same phrase always maps to the same action.
- **Discoverable:** autocomplete chips show the top-3 matching
  phrasings as the user types; `/help` lists all supported phrases.
- **Useful floor:** users get a basic assistant even when Claude Code,
  Codex, Aider, Gemini CLI, Gemma, and other CLIs are not installed.

## 3. Verified Current Code

- External CLI definitions live in
  `src/main/java/imagejai/engine/AgentLauncher.java`.
  `KNOWN_AGENTS` includes Claude Code, Aider, GitHub Copilot CLI,
  Gemini CLI, Open Interpreter, Cline, Codex CLI, Gemma 4 31B, and a
  Gemma Claude-style variant. `detectAgents()` only returns CLIs found
  on PATH or common install locations. Current `KNOWN_AGENTS` passes
  `--dangerously-skip-permissions` to Claude Code unconditionally; the
  GSD-aware behaviour in §12 needs to replace that hard-coded flag with
  a conditional launch-time flag.
- The chat UI is implemented in
  `src/main/java/imagejai/ui/ChatView.java`. `ChatPanel.java`
  is a compatibility wrapper around `ChatView`. `ChatView.sendMessage()`
  appends the user message, then calls registered
  `ChatPanel.ChatListener` instances. It does not call `LLMBackend`
  directly.
- LLM calls happen in
  `src/main/java/imagejai/ConversationLoop.java`.
  `ConversationLoop.onUserMessage()` starts a worker thread and
  `processUserMessage()` calls `LLMBackend.chat()` or
  `LLMBackend.chatWithVision()`.
- `ChatView.refreshInputState()`, `ChatView.setEnabled()`, and
  `ChatView.sendMessage()` currently gate chat on
  `Settings.hasApiKey()`. Local Assistant mode must bypass that gate.
- `ImageJAIPlugin.run()` currently opens `SettingsDialog` on first run
  when no API key is configured. Local Assistant mode must avoid forcing
  an API-key setup dialog before the built-in assistant can be used.
- `imagejai.config.Settings` persists model profiles
  (`configs`, `activeConfigId`), `tcpServerEnabled`, and
  `agentEmbeddedTerminal`. It does not persist a last-selected external
  agent; `selectedAgentName` does not exist yet. Add a separate field
  such as `selectedAgentName` or `selectedAgentCommand`.
- **An intent system already exists.**
  `src/main/java/imagejai/engine/IntentRouter.java` plus the
  TCP commands `intent`, `intent_teach`, `intent_list`, `intent_forget`
  in `TCPCommandServer.dispatchCore()`. It is a regex -> macro router
  with stat-on-call mtime reload, atomic writes where the filesystem
  supports them, regex quarantine, and per-mapping hit telemetry. Its
  public mutators are `teach(...)` and `forget(...)`, not `add(...)` and
  `remove(...)`. It persists to `~/.imagej-ai/intent_mappings.json`. The
  Local Assistant **layers on top of it**, not in place of it (see §6).
- `FrictionLog` is an in-memory ring buffer (`FrictionLog.java`,
  CAPACITY=100, WINDOW_MS=600000) of failed TCP responses, with
  pattern detection at ≥3 within 10 minutes. It is **not persistent**
  today. Local Assistant misses are recorded into `FrictionLog`; a
  persistent JSONL writer for cross-session learning is a v2 task.

## 4. UX

### 4.1 Agent selector

Add an agent selector in the chat header, distinct from the existing
model-profile dropdown:

```text
Local Assistant   <- first-run default, always present
----------------
Claude Code
Aider
Gemini CLI
Gemma 4 31B
...
```

The external entries come from `AgentLauncher.detectAgents()`. Local
Assistant is synthetic and always present. Selecting it does not launch
a process. Selecting an external agent records the choice; pressing the
play button launches that selected external agent with
`AgentLauncher.launch(agent, mode)`, where `mode` is `EMBEDDED` when
`Settings.agentEmbeddedTerminal` is true and `EXTERNAL` otherwise.

The existing CLI agents were never the default chat backend. They are
available launch targets for the play button and use the TCP server
from their terminal session.

### 4.2 Chat submission

When Local Assistant is active:

- `ChatView.sendMessage()` routes the text to
  `LocalAssistant.handle(String)` instead of notifying
  `ConversationLoop`.
- The input field remains enabled without an API key.
- Replies are appended through the active `ChatSurface`
  (`AiRootPanel`/`ChatView`) with `appendMessage("assistant", ...)`.
- Mutating intents echo the macro or action they executed.

When an LLM profile is active, keep the current path:
`ChatView.sendMessage()` → `ChatListener` → `ConversationLoop` →
`LLMBackend`.

### 4.3 Autocomplete chips (live, as the user types)

A debounced (~100 ms) listener on the chat textbox runs the same
Jaro-Winkler fuzzy matcher used post-submit (§6) against the
phrasebook and renders the top-3 candidate phrasings as clickable
chips below the input. Click or tab-to-accept fills the chat input.
This is the primary discovery mechanism — it lets users converge on
known phrases before submitting and is why a deterministic two-tier
matcher is enough.

### 4.4 Misses

If no intent matches:

```text
I don't recognise "<input>". Did you mean:
  [pixel size?]   [image dimensions?]   [calibration?]
Or type /help to see what I can do.
```

The three suggestions come from the same Jaro-Winkler index, ranked
by descending score with a margin filter so they are not shown if all
are uniformly low.

## 5. Initial Intent Library

Aim for ~50 curated intents at v1, covering the boring-but-frequent
questions a biologist asks an open Fiji session. Each intent ships
with ~50 phrasings produced by `tools/phrasebook_build.py` (§6). The
intents below are grouped by category. Sources for the additions
beyond the original 50: `agent/recipes/` (27 YAML workflows),
`agent/practice.py` TASKS, `agent/references/macro-reference.md` §2.1–
§2.10.

### 5.1 Image inspection (read-only)
- pixel size / calibration
- image dimensions (W × H × C × Z × T)
- bit depth
- number of channels / slices / frames
- active channel / slice / frame number
- image title / file path
- min/max/mean intensity of active channel
- list open images
- is anything saturated

### 5.2 Image control (mutating)
- close all images
- close active image
- close all but active
- duplicate active image
- revert
- save as TIFF / PNG / JPEG (prompt for filename; write under
  `AI_Exports/` next to the opened image)
- next slice / previous slice
- switch to channel N
- jump to slice N / frame N
- merge channels
- split channels
- z-project (max / mean / sum / SD)
- make substack (channels / slices / frames)
- crop to selection
- scale image by N
- invert image / invert LUT
- convert to 8-bit / 16-bit / 32-bit / RGB / composite
- set scale (e.g. "set scale 100 px = 10 µm")

### 5.3 Preprocessing
- subtract background (rolling ball N)
- gaussian blur (sigma N)
- median filter (radius N)
- mean / variance / unsharp mask
- bandpass filter

### 5.4 Segmentation and counting
- auto-threshold (Otsu / Li / Triangle / Huang / MaxEntropy /
  default), with optional "dark background"
- compare thresholds (routes to `explore_thresholds` TCP command)
- convert to mask (with `BlackBackground` pre-applied — see
  `agent/gemma4_31b/GEMMA.md` lines 63–65)
- count cells / count particles / count nuclei
- fill holes / watershed / skeletonize / distance map / Voronoi
- find maxima (prominence N)

### 5.5 Measurement and quantification
- measure intensity / measure CTCF (corrected total cell fluorescence)
- measure ROIs
- summarise / clear results
- set measurements (area / mean / integrated / …)
- line profile
- histogram
- nearest-neighbour distance

### 5.6 ROIs and results I/O
- list ROIs / count ROIs
- clear ROI manager
- save ROIs to AI_Exports
- show results table
- save results to CSV in AI_Exports

### 5.7 Display
- auto contrast (display only — uses `setMinAndMax`, never
  `Enhance Contrast normalize=true`; this is a hard rule per the
  project guardrails)
- reset display
- fit window to image
- set zoom to N %

### 5.8 Common dialogs (open and let the user fill in)
- threshold
- analyze particles
- find maxima
- gaussian blur
- subtract background

### 5.9 State and diagnostics
- what plugins do I have (uses `ij.Menus.getCommands()` through the
  Java-side command registry / `list_commands`; `agent/scan_plugins.py`
  is the legacy Python scraper)
- open the macro recorder / ROI manager / channels tool
- show log / show console / any open dialogs
- memory used / garbage collect

### 5.10 Help and meta
- help / what can you do / commands
- which agent am I using
- version

Slash commands listed in §13 are first-class members of the library
and matched the same way; "/" is just one of several phrasings for
each.

## 6. Architecture

### 6.1 Classes

Add these under `imagejai.local`:

```text
LocalAssistant.java
  handle(String userInput) -> AssistantReply
  owns IntentLibrary, IntentMatcher, FijiBridge

IntentMatcher.java
  match(String input) -> Optional<MatchedIntent>
  topK(String input, int k) -> List<RankedPhrase>   // for chips
  normalises input: lowercase, strip punctuation, collapse whitespace

IntentLibrary.java
  loads `/phrasebook.json` from `src/main/resources` at startup
  exposes: byId(id), all(), allPhrases()
  in-memory: HashMap<phrase -> intentId> + List<RankedPhrase>

Intent.java                                          // Java interface
  String id()
  String description()
  AssistantReply execute(Map<String,String> slots, FijiBridge fiji)

AssistantReply.java
  String text
  String macroEcho                                   // optional
  List<String> attachments                           // optional

FijiBridge.java
  thin Java facade over CommandEngine, StateInspector,
  WindowManager, RoiManager, ImagePlus. Does NOT go through TCP —
  Settings.tcpServerEnabled is false by default and Local Assistant
  must work regardless.
```

### 6.2 Two-tier matcher

```
input
  │
  ▼
normalise (lowercase, strip punctuation, collapse whitespace)
  │
  ▼
Tier 1: phrasebook hash lookup ───── hit ──► run intent
  │
  miss
  ▼
Tier 2: Jaro-Winkler over phrasebook (score ≥ 0.90) ── hit ──► run intent
  │
  miss
  ▼
IntentRouter.resolve(input)        ────────── hit ──► run macro
  │
  miss
  ▼
"I don't recognise that. Did you mean: [chip] [chip] [chip]?"
```

- **Tier 1 (phrasebook):** O(1) hash lookup against
  the baked `src/main/resources/phrasebook.json` resource (see §7).
  Built from ~50 hand-curated intents × ~50 LLM-distilled phrasings =
  ~2,500 entries. Always-on, near-zero cost.
- **Tier 2 (Jaro-Winkler):** use the existing in-repo
  `engine.FuzzyMatcher.jaroWinkler(...)` algorithm, or make that
  package-private helper reusable from the Local Assistant package.
  Do not add Apache Commons Text unless there is a separate reason to
  take a new dependency. Threshold 0.90; tunable in `Settings`. Same
  index also serves the autocomplete chip row in §4.3.
- **Stage 3 (`IntentRouter`):** existing user-taught regex layer. The
  Local Assistant explicitly delegates to it after the built-in
  library misses, surfacing user-defined intents without recompiling.
- **No BM25, no Lucene index, no embeddings, no runtime LLM** in v1.
  The MiniLM ONNX semantic tier is **opt-in and downloaded on demand**
  through the installer panel (§12); when enabled, it inserts as a
  Tier 2.5 between Jaro-Winkler and `IntentRouter`.

Total runtime cost target: **~5 MB on disk, <5 ms p99** for v1
(without the optional MiniLM download).

### 6.3 Phrasebook compiler (dev-time only)

`tools/phrasebook_build.py` (Python, dev machine only) reads a
canonical intent definition file and calls Claude/GPT once per intent
with a prompt of the form:

> Generate 50 plain-English phrasings a biologist might type to ask
> for *<intent description>* in a Fiji image-analysis chat. Include
> typos, abbreviations, partial phrases, and questions disguised as
> commands. Output one phrasing per line, no numbering.

Output is merged into `src/main/resources/phrasebook.json` and baked
into the JAR at build time. **Developer pays the LLM cost once per
release; users pay zero.** Re-running the compiler after adding new
intents is the canonical way to grow coverage.

### 6.4 TCP-command landscape (for FijiBridge implementers)

`FijiBridge` does not call TCP — but its method names should mirror
the TCP command set so that intent handler logic stays consistent
with what external agents see. Verified TCP command names available
in `TCPCommandServer.dispatchCore()`:

```text
hello                      ping                       execute_macro
get_state                  get_image_info             get_results_table
capture_image              run_pipeline               explore_thresholds
get_state_context          get_log                    get_histogram
get_open_windows           get_metadata               batch
run                        get_pixels                 3d_viewer
get_dialogs                close_dialogs              close_windows
probe_command              list_commands              run_script
interact_dialog            get_progress               get_friction_log
get_friction_patterns      clear_friction_log         intent
intent_teach               intent_list                intent_forget
gui_action                 execute_macro_async        job_status
job_cancel                 job_list                   list_reactive_rules
reactive_stats             reactive_enable            reactive_disable
reactive_reload            get_roi_state              get_display_state
get_console                get_image_graph            ledger_lookup
ledger_confirm             rewind                     branch
branch_list                branch_switch              branch_delete
```

Avoid the nonexistent names `run_macro`, `get_results`, and
`list_windows`; the real names are `execute_macro`,
`get_results_table`, and `get_open_windows`.

Some v1 intents are not fully covered by one TCP command. File path,
active-channel min/max/mean, saturation checks, ROI export, and
`AI_Exports` path resolution should use direct ImageJ APIs or
explicit macros through `CommandEngine.executeMacro()`.

`get_image_info` returns calibration as a string, not separate
`pixelWidth`/`pixelHeight` fields. For the pixel-size intent use
`get_metadata` on the TCP side or `ImagePlus.getCalibration()`
locally.

## 7. Phrasebook and Handler Format

### 7.1 Phrasebook (data, baked into JAR)

`src/main/resources/phrasebook.json`:

```json
{
  "version": 1,
  "intents": [
    {
      "id": "image.pixel_size",
      "description": "Report the active image's pixel size",
      "phrases": [
        "pixel size",
        "what is the pixel size",
        "what's the pixel size",
        "px size",
        "calibration",
        "what is the calibration",
        "spatial resolution",
        "how big are the pixels",
        "pixel dimensions",
        "show pixel size"
      ]
    }
  ]
}
```

Phrasings are normalised at load time (lowercase, strip punctuation,
collapse whitespace) and indexed into a `HashMap<String, String>`
(phrase → intentId) plus a `List<String>` for fuzzy lookup. The full
~2,500-entry index loads in under 50 ms and consumes a few hundred
KB.

### 7.2 Handler (Java, one per intent)

```java
public class PixelSizeIntent implements Intent {

  public String id() { return "image.pixel_size"; }

  public String description() {
    return "Report the active image's pixel size";
  }

  public AssistantReply execute(Map<String,String> slots,
                                FijiBridge fiji) {
    Calibration cal = fiji.getActiveCalibration();
    if (cal == null) return AssistantReply.text("No image is open.");
    return AssistantReply.text(String.format(
      "Pixel size: %.4f × %.4f %s",
      cal.pixelWidth, cal.pixelHeight, cal.getUnit()));
  }
}
```

`IntentLibrary` registers handlers via a static map keyed on intent
ID, populated either explicitly in code or via `ServiceLoader` (§11).

## 8. Guardrails

- Every intent must check state before acting. If no image is open,
  reply clearly instead of running image-dependent macros.
- Exports go to `AI_Exports/` next to the opened image. Resolve via
  `ImagePlus.getOriginalFileInfo().directory`. If the active image
  has no file-backed directory, ask the user to save the image first.
- Never use `Enhance Contrast normalize=true` for data that may be
  measured. Display-only contrast intents must use `setMinAndMax()`.
- Dialog-opening intents open the dialog and stop. Do not guess
  parameters for unfamiliar plugin dialogs.
- Never close the ImageJ Log window. Intents like "close all but
  active" must whitelist the Log window
  (`feedback_never_close_log.md`).
- Display detection results as object **masks**, not overlays.
  "Analyze Particles" intents default to `show=Masks`
  (`feedback_object_mask_display.md`).

## 9. Implementation Roadmap

### Phase A — skeleton and Tier 1 (1–2 days)
- Add agent selector to `AiRootPanel.createHeader()` (or convert
  `AiRootPanel.showAgentLaunchMenu()` into selector + launch behaviour). Add
  `LOCAL_ASSISTANT` as a synthetic first entry; external entries come
  from `AgentLauncher.detectAgents()`.
- Persist last-selected agent in `Settings` (new field
  `selectedAgentName`).
- Route `ChatView.sendMessage()` to `LocalAssistant.handle()` when
  Local Assistant is active. LLM mode keeps the current
  `ChatListener` → `ConversationLoop` → `LLMBackend` path.
- Bypass the `Settings.hasApiKey()` gate and the first-run
  `SettingsDialog` from `ImageJAIPlugin.run()` when Local Assistant
  is the active mode.
- Implement `LocalAssistant`, `IntentMatcher`, `IntentLibrary`,
  `Intent`, `AssistantReply`, `FijiBridge`. Tier 1 only (phrasebook
  hash lookup).
- Wire misses into `FrictionLog`.
- Write `tests/benchmark/biologist_phrasings.jsonl` with ~150 seed
  phrasings drawn from `agent/recipes/` descriptions and
  `agent/practice.py` TASKS.
- Ship with one trivial intent (`help`) and one read-only intent
  (`pixel_size`) just to prove the wiring.

### Phase B — phrasebook + autocomplete + library (2–3 days)
- Implement `tools/phrasebook_build.py` (Python). One-shot LLM call
  per intent producing ~50 phrasings; merged into
  `src/main/resources/phrasebook.json`.
- Add Tier 2 using the existing `engine.FuzzyMatcher` Jaro-Winkler
  implementation and the autocomplete chip row in `ChatView`.
- Implement the ~50 intents listed in §5. Manual smoke test for each
  against a real Fiji session.
- Add `IntentRouter.resolve()` as the post-fuzzy fallback stage.
- Add slash commands: `/help`, `/clear`, `/macros`, `/info`,
  `/close`, `/teach`, `/intents`, `/forget` (§13).
- Add the installer panel UI (§12).

### Phase C — coverage multiplier (1–2 days)
- Mine `ij.Menus.getCommands()` at startup (the Java code already
  snapshots this in `MenuCommandRegistry`; `agent/scan_plugins.py` is
  the older Python-side scraper) and auto-generate intent stubs for
  every menu command in the user's actual Fiji install, third-party
  plugins included.
- Run `tools/phrasebook_build.py` over each command name to expand
  natural-language coverage.
- Result: coverage scales from 50 hand-curated intents to all of
  Fiji with no per-plugin work.

### Phase D — ongoing polish
- Disambiguation chips on submit (when top-1 and top-2 are within
  margin).
- Conversational memory: "do that again", "the next image",
  "measure it".
- `FrictionLog` persistence (JSONL writer) for cross-session phrase
  collection.
- "Improve from my chat history" button: append corrected phrasings
  to phrasebook, or send the unresolved batch through
  `tools/phrasebook_build.py` with explicit user consent.
- Echo executed macros in chat for transparency.

Total foundation: **~4–7 focused days** for a matcher that catches
the long tail without spending a cent at runtime.

## 10. What Happens to External Agents

Nothing breaks. Claude Code, Aider, Gemini CLI, Gemma, Codex, and
other CLI agents keep using `AgentLauncher` and the TCP-server-backed
terminal workflow.

Local Assistant is an additional built-in option. It is pre-selected
only on fresh installs or when the persisted `selectedAgentName` is
missing from `AgentLauncher.detectAgents()`. If the user selects an
external agent, persist that selection and keep using it on the next
launch.

A user who wants free-form analysis, experiment design, or open-ended
image interpretation still launches an external AI agent. The
installer panel (§12) makes that one click.

## 11. Extensibility

- **Built-in intents** are Java classes implementing `Intent`,
  registered in `IntentLibrary`. `ServiceLoader` is reasonable if
  external extension JARs are expected; otherwise a static registry
  is simpler.
- **User-taught intents** go through the existing `IntentRouter`
  (regex → macro) via the `/teach` slash command (§13). Mappings
  persist to `~/.imagej-ai/intent_mappings.json`. They are surfaced
  to the chat user via `/intents` and removable via `/forget` by their
  regex pattern, matching the current `IntentRouter.forget(...)` API.
- **Phrasebook regeneration** is a developer task: edit the
  canonical intent definition file, re-run
  `tools/phrasebook_build.py`, commit the updated
  `src/main/resources/phrasebook.json`.
- **No YAML loader** in v1. Java has no YAML dependency by default
  and the existing `agent/recipes/` YAML loader is Python-side. JSON
  for built-in data; `IntentRouter` JSON file for user data.

## 12. Models and Agents Installer Panel

Add a "Models & Agents" tab in Local Assistant settings. One row per
available agent with a status column and a per-row action button.

| Agent | Status | Action |
|---|---|---|
| Local Assistant (built-in) | ✓ ready | — |
| Local Assistant — semantic boost (MiniLM, ~80 MB) | not downloaded | **[Download]** |
| Claude Code | not installed | **[Install via npm]** — needs Node.js; offers to open the Node download page if `npm` is not on `PATH` |
| Codex CLI | not installed | **[Install via npm]** |
| Ollama (for Gemma 4 31B etc., cloud-served) | not installed | **[Install Ollama CLI]** (~250 MB). No additional model pulls — Ollama serves the configured models from the cloud. |
| Aider | not installed | **[Install via pip]** — needs Python |
| Gemini CLI | not installed | **[Install via npm]** |

Implementation notes:

- Detect status by probing each binary on `PATH`
  (`AgentLauncher.detectAgents()` already does this) and by checking
  a new `Settings` flag for the MiniLM download state.
- Install actions shell out to the relevant package manager via
  `ProcessBuilder`. Always show the exact command being run before
  executing; capture stdout/stderr and stream into a small log dialog
  so users see progress.
- For the MiniLM download: fetch
  `all-MiniLM-L6-v2-int8.onnx` (~23 MB quantised) plus the tokenizer
  files into `~/.imagej-ai/models/all-MiniLM-L6-v2/`. Verify SHA-256.
- **GSD detection for Claude.** When the user clicks the Claude row's
  action button, also probe whether GSD is installed
  (`~/.claude/skills/gsd/` or `claude /gsd:help` exit code). If yes,
  launches of Claude Code from the play button pass
  `--dangerously-skip-permissions`. If no, show a one-time prompt:
  *"Install GSD to skip permission prompts and run Claude at full
  speed? [Install] [Skip]"*. GSD is not safer; it is faster and
  unlocks the full potential.
- Show file-size warnings before any download or install kicks off.

## 13. Slash Commands

Slash commands are first-class intents in `IntentLibrary` and match
through the same two-tier matcher. They are also recognised by their
literal `/name` form.

Ported from `agent/gemma4_31b/loop.py`:

- `/help` — list every supported phrase, grouped by §5 category.
- `/clear` — clear the chat history.

New in Local Assistant:

- `/macros` — list saved Fiji macros from `<Fiji>/macros/` plus a
  per-session `~/.imagej-ai/learned_macros/`. Each row shows
  filename, the first-line `// description` if present, and last-
  modified. Natural-language equivalent: "show my macros". Calling
  a macro by name is also matched: "run my split images macro" →
  fuzzy-match `split images` against macro filenames + descriptions.
  Two-way matches show disambiguation chips. Macros saved during the
  session land in `learned_macros/` and survive across sessions.
- `/info` — table of all open images. Columns: title, dimensions
  (W × H × C × Z × T), bit depth, pixel size + unit, file path,
  saturated?. Follow the existing codebase pattern:
  `WindowManager.getIDList()` plus `WindowManager.getImage(id)`, then
  `ImagePlus.getCalibration()` per image.
- `/close` — `/close` (active), `/close all`, `/close all but
  active`, `/close <substring>`. Always whitelists the Log window
  per the project guardrail.
- `/teach <phrase> => <macro>` — adds or updates a user-taught intent
  via `IntentRouter.teach(...)` (existing TCP `intent_teach` handler).
- `/intents` — list all user-taught intents with hit counts and
  last-used timestamps from `IntentRouter`.
- `/forget <phrase-or-pattern>` — remove a user-taught intent by its
  regex pattern via `IntentRouter.forget(...)` (existing TCP
  `intent_forget` handler).

Not ported from gemma4_31b: `/save-recipe`, `/interrupt`, `/think`,
`/mode`, `/ccommands` / `/ccommand`, `/queue` — either LLM-loop
specific or out of scope for v1.

## 14. Open Questions

1. **Naming.** "Local Assistant", "Quick Commands", "Built-in", or
   "ImageJAI Native".
2. **Single-action v1.** Keep "threshold and measure" style chaining
   out of v1 unless there is a strong user need.
3. **Parameter prompting.** Use file dialogs for filenames; use chat
   replies for missing numeric/text parameters.
4. **Local logs.** Add a persistent JSONL writer over `FrictionLog`
   so missed phrases survive across sessions and feed the
   "improve from my chat history" flow (Phase D).
5. **Intent ID namespacing.** Built-in `IntentLibrary` IDs should be
   namespaced (for example `builtin.image.pixel_size`). Current
   `IntentRouter` entries are addressed by regex pattern rather than ID;
   only add user-facing IDs if the router schema grows later.
