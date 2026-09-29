# ImageJAI Console

ImageJAI Console is a standalone terminal interface for the ImageJAI Fiji
plugin. It can be launched from any directory and keeps the session list on
the left, chat in the centre, and live Fiji state and events on the right.

## Install

Install the Fiji plugin through **Help > Update > Manage update sites**:
add **ImageJ-AI** with URL `https://sites.imagej.net/ImageJ-AI/`, enable it,
apply the changes, and restart Fiji. This update site distributes the plugin
and its bundled console together.

Recommended: in Fiji, open **AI Assistant > Settings > Models & Agents**, find
**ImageJAI Console**, and click **Install**. This downloads the declared
dependencies into a private, versioned environment and registers `imagejai`
for the current user only. The same row can launch, repair, update, or
uninstall it. Uninstalling the console keeps provider credentials and chat
history. If Python 3.10–3.13 is installed, setup uses it. Otherwise it
downloads a verified Python 3.12 runtime into the current user's ImageJAI
folder. First-time setup needs internet access but not administrator access;
an interrupted download can be retried with **Install** or **Repair / update**.
This setup does not sign in to a model provider.

For development from an ImageJAI source checkout:

```powershell
python -m pip install -e .
```

This installs an editable global `imagejai` command. The Fiji installer uses
the dependency set bundled in the plugin, including the LiteLLM proxy route.

## Launch

```text
imagejai                         launch from any directory
imagejai login                  open the provider picker
imagejai login codex            official ChatGPT/Codex subscription login
imagejai login claude           official Claude subscription login
imagejai status                 check the workspace, Fiji, and provider
imagejai use --doctor           diagnose the built-in ImageJ Use connection
imagejai harness doctor         diagnose the sandboxed physical test harness
imagejai --session SESSION_ID    reopen a saved ImageJAI chat
imagejai --port 8000            use a non-default Fiji TCP port
imagejai --fiji DIR             choose and save a Fiji installation
imagejai --effort high           choose reasoning effort for the selected model
imagejai --workspace DIR        use an explicit agent workspace
```

The launcher exits with a non-zero status when startup fails, so scripts do
not mistake a missing dependency or workspace for a successful launch.

`imagejai use` is bundled with the console and runs one authenticated ImageJ
Use program against the working Fiji. `imagejai harness` forwards to the
separately installed ImageJ Plugin Test Harness. The runner stays separate by
design: it provisions and controls a disposable Fiji sandbox and must never
drive or terminate the working Fiji installation.

## Authentication

There are three supported routes:

1. ChatGPT or Claude subscriptions use the provider's official command-line
   client. ImageJAI runs `codex login` or `claude auth login`, then reuses that
   client's authenticated session. Browser tokens are never copied by
   ImageJAI.
2. Cloud API providers accept an API key in the provider picker. On Windows,
   the Fiji plugin and terminal share `~/.imagej-ai/secrets/<provider>.cred`;
   its contents are encrypted for the current Windows user with the Data
   Protection API (DPAPI).
3. Ollama, LM Studio, Jan, llama.cpp, and vLLM use their local server and need
   no cloud key.

Prototype plaintext files (`console_secrets.json` and `<provider>.env`) are
migrated to the protected Windows store after they are read successfully.
Environment variables remain supported and take precedence for automation.

The subscription adapters intentionally use official provider clients instead
of reverse-engineering consumer website tokens. A provider without an official
client still uses its documented API-key route.

## Fiji connection

1. Run `imagejai`. By default it checks that a listening server belongs to the selected
   Fiji installation before sending commands.
2. If the server is offline, the console asks the installed plugin to open it.
   If Fiji is closed, the console launches the detected or saved installation.
3. Use `/fiji` to choose a different installation or enter its path. `/fiji status`
   shows the current choice; `/fiji start` retries the connection.

If automatic startup is switched off in `/settings`, the console still checks
the connection, and `/fiji start` can start Fiji when needed.

`/settings` opens the console's Agent, Fiji, Privacy, Display, and Budget tabs. The Fiji
tab can save an installation path or leave it blank for detection, and can turn
automatic startup on or off. On Windows, the console automatically closes the
ImageJ error dialog that blocks startup for some installations, but only while
it launches the selected Fiji. "Close startup errors" is on by default and
can be switched off to inspect those dialogs. The
Agent tab opens the existing model and login
pickers; keys remain in the protected credential store. Cancel discards edits.
The console saves its own choices atomically in `~/.imagej-ai/console.json`.

The console startup request needs a Fiji installation with the current
ImageJAI plugin. Older installed JARs do not watch for the request.
If another Fiji owns the chosen TCP port, the console reports the conflict;
start it with `--port` to use a different port for the selected installation.

The top-right indicator turns green when Fiji is reachable. The right panel
then shows open images, the active image, memory, and the event stream. The
console can still open and manage chats while Fiji is offline; Fiji tool calls
will report a connection error.

## Additional console controls

### Correcting work and continuing later

While an answer is running, Enter sends a correction at the next tool boundary.
Use `/steer <correction>` explicitly, or `/queue <next task>` to wait for the
current answer to finish. A correction received before a pending action prevents
that old action from running. An action already running must finish or be stopped;
steering does not undo changes in Fiji.

`/queue` lists pending messages; `/queue clear` removes them. Pending messages
are saved with their chat. Escape stops active work and clears pending input.
A task stays queued if login, privacy, or a cost choice postpones sending it.
Follow-up tasks run after a successful reply; failed or interrupted turns do not
automatically start the next task.

`/fork` copies the conversation and recorded evidence into a new chat.
`/fork <message count>` branches at a completed reply, preserving complete tool
request/result pairs. The branch starts a fresh provider conversation and Python
workspace. It does not restore Fiji pixels, windows, queued messages or running
jobs. The original chat stays available.

`/btw <question>` starts an independent side conversation with the selected model
and effort. Its answer and available thinking appear in the chat and saved
evidence, but never enter the main agent's conversation. Direct Fiji tools are
disabled for the side conversation. Subscription clients retain their native
capabilities; the side instructions prohibit external actions. Privacy, cost
notices, usage accounting and spending pauses also apply to side questions.

### Numerical Python and background macros

Models can call `python_cell(code, timeout)` through the same direct-tool wrapper
used by API, local and installed agents. Host-code approval is required. The
separate Python process starts on demand and retains arrays between turns.
`np`, `math`, and `pixels(x,y,width,height)` are available; SciPy is optional if
installed in the console's Python environment. Pixel reads use the console's
authenticated Fiji connection. `image_meta` explains the value domain and plane;
packed RGB values must not be treated as grayscale intensities.

`/python status` lists variables, `/python reset` clears them, and `/python <code>`
asks the agent to run a cell. Image identity, pixel/display revisions and channel,
slice and frame are checked before reusing image-bound arrays. Changed image
state clears the scratchpad. Timeouts (up to 120 seconds), interruption and a
512 MiB process memory guard also clear it. Variables are not restored after
closing the console or switching chats. File, shell and network helpers are
excluded from the reduced language; approved Python is not an operating-system
security sandbox.

`/jobs` shows background macro jobs submitted through `run_macro_async`.
The console checks only those owned job IDs. Completion, failure and cancellation
become saved notices delivered to the main agent once, so it can read results
without repeatedly asking for state. A different Fiji installation cannot supply
the completion. Fiji may take time to acknowledge cancellation of a plugin that
is already executing.

### Folder guidance, skills and reload

Folder `AGENTS.md` (or `CLAUDE.md` when absent) and `IMAGEJAI.md` guide every
model route. Repository guidance loads from broader folders to the active image
folder; workspace guidance loads first. Outside a repository, ancestor guidance
requires an explicit `IMAGEJAI.md`. Sources and total size are bounded, and
protected privacy modes withhold unresolved absolute paths.

The model can call `load_skill(name)` to fetch an eligible skill body from the
catalog. Skills marked `disable-model-invocation` require the user's `/skill`
command. Loading guidance never executes a recipe. `/reload` refreshes skills,
custom commands and folder guidance; during a reply, the refresh waits for the
next tool boundary. An unavailable active skill is cleared visibly.

Long model-facing tool returns retain their beginning and end, with the omitted
character count. Complete returns remain in session evidence and the full-result
view. The preview may omit intermediate results; it is not the complete table.

### Procedure validation and memory review

`/validate check <manifest.json>` verifies a frozen procedure and distinct
development and held-out images. `/validate run <manifest.json>` shows the full
macro for confirmation, opens declared images, runs on duplicates, compares
measurements and writes a receipt under `AI_Exports/validation/` beside them.
The input images and procedure are checked before and after the run. Escape
cancels only the validation's own asynchronous macro job. Finish or stop validation
before starting another console analysis or changing Fiji. Images remain open
for inspection; validation is not a rollback of Fiji's global tables or settings.

Use a manifest with schema `1`, a named `reviewer`, a `procedure` containing its
relative `path` and SHA-256 hash, and 2–128 `cases`. Each case supplies a unique
`id`, relative image `path`, SHA-256 hash, `split` (`development` or `held_out`),
and trusted `expected` scalar measurements. Each expected field has `value` and
optional nonnegative numerical `tolerance`. Both splits must be represented,
without duplicate image hashes. Fields use dotted paths such as
`image.width`, `results.count` or `results.rows.0.Mean`. The runner returns parsed
CSV measurements and resulting image information. The reviewed macro must
produce the named measurements. Expected values come from human review, not the
agent's own output.

`/memory-review` lists expired, conflicting and instrument-dependent entries;
`/memory-review <id>` opens one. Renew with written evidence and 1–365 days,
deprecate with a reason, attach a passing validation receipt, or revalidate an
instrument assumption. `/memory validation <id> <receipt.json>` also attaches a
receipt. Promoting a procedure to `validated` requires a current passing receipt
with both splits, unchanged inputs and unchanged entry content. Attaching a
receipt never promotes automatically; `/memory approve` remains a separate human
decision. Receipt freshness is limited to 30 days.
Editing a validated procedure's content or applicability returns it to candidate
status, requiring a fresh human review and validation.

`/instrument` checks the selected installation's JAR hashes and full voxel/time calibration.
`/instrument approve | <written evidence>` reviews that configuration. Changed
configuration withholds dependent knowledge until the configuration and each
affected assumption have been reviewed. Cached file hashes reduce repeated work;
configuration is rechecked before main turns. Profiles and review evidence stay
in the user's local configuration, outside the public package.

The [completed implementation stages](../prime-agent-ports_COMPLETED/00_overview.md)
and their verification records describe this port. Generated fixtures verify the
software controls; they do not validate a biological procedure or certify every
external model account.

### Thinking and live replies

The chat shows provider-exposed thinking and reasoning summaries under a purple
**Thinking** label as they arrive. Codex and Claude subscription sessions also
show their messages and tool activity during the turn. The console consumes the
vendors' documented [Codex event stream](https://developers.openai.com/codex/noninteractive)
and [Claude partial messages](https://code.claude.com/docs/en/agent-sdk/streaming-output).
Models differ in what thinking they expose; the console displays the available
text, without treating it as the final answer.

Scroll the live section to read earlier text while the agent works. Thinking
stays in the chat when an answer or tool call follows, and is saved in the
session evidence. Escape preserves the text already received and stops the
turn. The input stays editable throughout.

Tool calls use the Gemma agent's original icons, yellow call text, expanded
multiline script arguments, and indented result arrows. Histogram icons retain
their multicolour bars. Tool returns appear as short descriptions such as
**Opened blobs.gif**, **Found 12 measurements**, or **Image is opening**.
Errors are red; pending operations and warnings are yellow. Click a result
or its **details** link to inspect the full return in a scrollable window.
Capture previews also appear in this window when a saved image is available.
JSON is formatted for reading; **Show original** reveals the exact saved text.
Escape closes this window and returns to the draft without interrupting the
agent. Large returns load from the session artifact only when opened.
The activity line uses the same moving symbols and
highlight wave for **Thinking**, **Inspecting image**, **Writing macro/script**,
and **Running in Fiji**. It follows tool preparation and execution events;
shell commands containing explicit Fiji calls use the corresponding Fiji style.

All console providers share the existing Fiji tool executor. API, local and supported installed agents send the same named action messages; the console validates them, executes
them through its selected authenticated Fiji connection, and sends the result
back into the same vendor conversation. Fiji calls do not need PowerShell,
`ij.py` subprocesses, or Model Context Protocol. The wrapper supplies the tool
catalogue as text, so models do not need native function calling support.

Action messages stay out of the chat display. Tool names, full macro arguments,
readable results and provider-exposed thinking keep their Gemma-style display.
The evidence journal records the actual request and result. Every nonempty
executed macro appears in Session Macros, including one-line calls such as
`run("Blobs");`. Its history is saved with the session and restored on reopening.
Request identifiers are saved with the conversation:
repeating the same request returns its earlier result without executing again.
Reusing an identifier for different arguments is refused.

The wrapper accepts only a complete assistant message of this form:

```text
<imagejai-action>{"id":"open-blobs-1","tool":"run_macro","arguments":{"code":"run(\"Blobs\");"}}</imagejai-action>
```

Prose, fenced examples and incomplete streamed messages cannot execute tools.
The tool catalogue comes from the same Python functions used by Gemma. Script
tools still require console approval; image attachments follow the posture and
`/images` setting. Escape stops the wrapper promptly and prevents further
commands; an operation already running inside a desktop plugin may finish later.
Opening a supplied file uses `open_image`. A pending open or dialog operation
is checked with `poll_operation` and its returned identifier, without repeating
the original command.

The Events panel turns Fiji notifications into short colored descriptions.
Its controls filter the last 1,000 events received during this console run by
type, time, or text. Heartbeats are hidden.

The privacy badge follows Fiji's live posture. `/posture standard`,
`/posture pseudonymised`, and `/posture on-premises` change Fiji's posture
through its controller; the console updates its badge only after Fiji confirms.
Fiji must be connected to change it. Changes made in Fiji are reflected in the
console through the event stream. Standard is the first-use default; a saved
choice for a folder still applies when an image from that folder opens.

## Sessions

Each conversation is stored atomically as one provider-neutral JSON file:

```text
~/.imagej-ai/sessions/<session-id>.json
```

The file records the provider, model, messages, and the vendor session ID used
to resume Codex or Claude. Older sessions embedded in `console.json` migrate
automatically. `/export` writes a readable Markdown transcript to
`~/.imagej-ai/console/exports/`.

`/resume` opens a picker of saved console chats. `/resume <session-id>` opens a
specific one; `/resume latest` opens the newest saved chat. A resumed Codex or
Claude chat continues using its saved vendor session ID. `/resume vendor`
starts a new console chat that continues the official Codex or Claude client's
latest conversation in the agent workspace on its first reply. `/clear` resets
the current console conversation, its provider context, draft input, and
pending choices while retaining the session's evidence and artifacts.

## Controls

The model picker draws the rows visible on screen, so large discovered registries
do not create hundreds of separate interface widgets. Scroll with the mouse or
use arrows and Page Up/Down while filtering. Pinning keeps the highlighted model
in view. Model discovery only runs when you request a refresh.

| Key | Action |
|---|---|
| `ctrl+l` | Log in or change provider |
| `ctrl+p` | Choose a model and reasoning effort |
| `ctrl+,` | Open console settings |
| `ctrl+n` | Start a new session |
| `ctrl+o` | Open an image in Fiji |
| `ctrl+f` | Toggle the session rail |
| `ctrl+g` | Toggle the Fiji panel |
| `ctrl+t` | List available Fiji tools |
| `ctrl+space` | Insert the highlighted suggestion |
| `esc` | Interrupt the running turn |
| `f1` | Open help |
| `ctrl+q` | Quit |

The **Sessions** and **Fiji** buttons in the top bar independently collapse or
expand the two side panels. Collapse both to give the chat the full width;
the buttons remain visible so either panel can be restored. The layout is
saved for the next console launch. The keyboard shortcuts above do the same.

Slash commands include `/commands`, `/help`, `/login`, `/model`, `/effort`,
`/fiji`, `/settings`, `/new`, `/resume`, `/state`,
`/capture`, `/results`, `/rois`, `/tools`, `/budget`, `/export`, `/clear`, and
`/quit`.

Typing `/` opens command completion, including matching local command files.
Tab inserts the highlighted command; Enter runs an exact command. You can
keep typing a draft while the agent replies. Pressing Enter during that reply
keeps the draft in place until the reply finishes. Escape marks the reply as
interrupted immediately and requests cancellation of its active model call.

Put `.md` or `.txt` prompt files in `~/.imagej-ai/console/commands/` to add
commands for every provider. `/commands` shows them alongside the built-in
actions; choose a file to run it, or type `/filename optional arguments`.
`$ARGUMENTS` in the file is replaced by what you type after the command.
Otherwise, arguments are appended to the prompt. Claude command files in the
agent workspace's `.claude/commands/` and Gemma command files in
`~/.config/imagej-ai/gemma4_31b/.ccommands/` are also listed when those models
are selected. Files are read as prompts; vendor-specific terminal directives
are not executed.

Host-code tools such as shell or script execution pause for explicit approval.
The Codex subscription adapter uses Codex's reviewed, workspace-write mode;
the Claude adapter uses Claude Code's automatic permission mode.

## Macros

The left rail has **My Macros**, **Session Macros**, **All Macros**, and
**Save Macro**. Select a macro and press Enter or **Run** to execute it in
Fiji. Choose **Edit** to open it in Fiji's Script Editor, or **Folder** to open
its containing folder. Right-click or press Shift+F10 on a macro row for the same Edit and Folder
actions. Editing opens the macro as a document; it does not execute it.

**Save Macro** copies a session macro into the selected Fiji installation's
`ImageJAI/macros/` folder, asking before it overwrites an existing file.
Session Macros includes code run through the console's Fiji actions, native
model tools, and wrapped subscription-agent requests.

## Completed parity controls

The left rail now has a live **Macro history** list with timestamps, run counts and failure status. Open a row for Copy, Run, Edit, Save and Remove. Clear history asks first; the housekeeping filter and collapsed state are remembered. Saved macro source and full analysis evidence remain separate.

Reopening a chat restores complete saved messages, provider-exposed thinking, tool calls and readable result summaries. Click a result, or use **Ctrl+R** / **`/tool-results`**, to inspect its full return. Large returns are loaded when opened. Earlier conversations can only replay the evidence that was recorded at the time.

**`/safety`** or the right-panel safe-mode button shows the negotiated Fiji tool connection status and the three latest safety events. The indicator's event colour settles after a minute. This reports the tool connection; it does not change Fiji's master switch. The view can remain open while new events arrive.

**`/model`** includes installed Gemini CLI, Aider, GitHub Copilot CLI, Cline and current Open Interpreter programs. Codex and Claude retain their native conversation resume. Other adapters resend the saved console conversation and use the program's own login/model configuration. The console does not install programs. Old `gh copilot` and older Python Open Interpreter builds are not the supported headless programs. Gemma continues through the existing Ollama route. Extra command-line adapters currently send text; choose an API, Codex or Claude route to attach images. All these routes use the same direct Fiji action executor, without Model Context Protocol or a Fiji shell command.

## Costs and spending limits

Paid, subscription and unverified models show a notice before first use. Continue accepts this model; the checkbox accepts the provider for this saved session. Choose free opens the filtered model picker and keeps your request in the typing box. Cancel keeps the request unsent.

**`/cost-notices`** reviews registry tier/price changes for used or pinned models. Dismissing one change does not hide a later change. These notices compare registry observations; they are not provider invoices or a live pricing check.

**`/budget`** shows accumulated usage saved with the session, including separate requests after Fiji tools, compaction and refinement. Provider-reported tokens/costs are identified separately from registry/character estimates. Missing prices remain unknown. Older sessions without accounting can show conversation-size estimates only. A command-line program may report usage for its whole invocation rather than each internal request.

Set a session spending pause with **`/budget 1`** (US dollars), or use **Settings > Budget**. **`/budget off`** disables it. Once the known total reaches the limit, the next request waits for Raise and continue, Choose free, or Stop. Unknown costs/prices require an explicit Continue once/free/stop choice. Escape interrupts the pause immediately. A request already sent can exceed the limit; this is a console pause, not a provider-side dollar cap.

Billing, login and rate-limit failures offer Choose model, Choose free, Open account where known, and Close. Requests are not retried automatically. Switching providers preserves portable conversation text, macro history, usage and completed action receipts; old image attachments are not forwarded to the new provider. Send Continue after choosing a new model or fixing the account.

## Architecture

```text
terminal UI
  -> wrapped agent action message (any console provider)
  -> shared ImageJAI Fiji tool executor
  -> selected authenticated TCP server :7746
  -> result returned to the same model/agent conversation
```

The command is packaged by the repository root `pyproject.toml`. The runtime
code lives in `agent/console/`; provider metadata remains in
`agent/providers/models.yaml`.

## Test

```powershell
python -m pytest agent/console/tests -q
```

The suite covers workspace discovery, protected credential migration,
file-per-session persistence, provider routing, the Fiji tool loop, streaming,
and terminal-interface smoke tests without contacting a live provider.


## Harness-lite

See [`HARNESS_LITE.md`](HARNESS_LITE.md) for the reviewed-memory, evidence, skills and compaction architecture.
