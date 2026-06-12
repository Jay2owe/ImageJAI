# ImageJAI infrastructure v1 map

Plain-English prompt to output, showing how the app is connected right now.

## Main prompt paths

```text
User prompt
    |
    v
Fiji menu: Plugins > AI Assistant
ImageJAIPlugin
    |
    v
AiRootPanel
    |
    +-------------------------------+-------------------------------+
    |                               |                               |
    v                               v                               v
Local assistant chat          API-backed in-app chat          Agent launch UI
ChatView handles directly     ChatView notifies               legacy selector or
LocalAssistant                ConversationLoop                ModelPickerButton
    |                               |                               |
    v                               v                               v
IntentLibrary                 BackendFactory / LLMBackend     AgentLaunchOrchestrator
IntentMatcher                 StateInspector                  ProviderRegistry
SlashCommandRegistry          ImageCapture                    ProviderDiscovery
IntentRouter                  PromptTemplates                 ModelsCache
    |                          AgentOrchestrator              UsageTracker
    v                               |                               |
FijiBridge                         v                               v
CommandEngine                 macro or pipeline text          CLI transport is wired
    |                               |                         native/proxy are skeletons
    v                               v                               |
Fiji/ImageJ runtime           CommandEngine                         v
                              PipelineBuilder                 AgentLauncher
                              ExplorationEngine                    |
                                   |                               +--> EmbeddedAgentSession
                                   v                               |    TerminalView in plugin
                              Fiji/ImageJ runtime                  |
                                                                   +--> ExternalAgentSession
                                                                        OS terminal window
```

## Launched agent path

```text
AgentLauncher
    |
    v
Launched CLI process in agent/ workspace
Claude / Codex / Gemini CLI / Aider / Gemma tool agent
    |
    v
Agent context and tools
agent/CLAUDE.md, AGENTS.md, GEMINI.md
agent/gemma4_31b/GEMMA.md
    |
    +-------------------------------+-------------------------------+
    |                               |                               |
    v                               v                               v
TCP clients                    Agent-side helpers             Gemma tool package
ij.py                          recipe_search.py               agent/gemma4_31b/
pixels.py                      macro_lint.py                  loop.py, registry.py
probe_plugin.py                results_parser.py              tools_fiji.py
auditor.py                     image_diff.py                  tools_python.py
                               session_log.py                 tools_dialogs.py
                               adviser.py                     tools_plugins.py
                               autopsy.py                     tools_jobs.py
                               methods_table.py               safety.py, lint.py
                               scan_plugins.py                auto_probe.py
                               train_agent.py                 events.py
                               run_recipe.py                  active_image.py
                                                               visual_diff.py
                                                               describe_image.py
                                                               triage_image.py
                                                               threshold_shootout.py
                                                               tools_recipes.py
                                                               harvest_recipe.py
    |
    v
TCP JSON bridge
configured loopback port
default 7746, fallback 7747-7750
TCPCommandServer
```

## TCP bridge and Fiji control

```text
TCPCommandServer
    |
    +---------------------------------------------------------------+
    |                                                               |
    v                                                               v
Read current Fiji state                                      Change or create data
get_state                                                    execute_macro -> TCP macro runner
get_image_info                                               run_script -> script runner
get_metadata                                                 run_pipeline -> PipelineBuilder
get_results_table                                            explore_thresholds -> threshold comparison
get_roi_state                                                execute_macro_async
get_display_state                                            job_status / job_cancel / job_list
get_open_windows                                             open_image / open_image_by_token
get_log / get_console                                        close_dialogs / interact_dialog
get_progress                                                 3d_viewer
get_histogram                                                gui_action
capture_image                                                emit_methods_table
get_pixels
probe_command
list_commands
subscribe events
intent / intent_teach / intent_list / intent_forget
reactive_enable / reactive_disable / reactive_reload
    |                                                               |
    +-------------------------------+-------------------------------+
                                    |
                                    v
Execution engines inside Fiji
CommandEngine -> direct in-app and LocalAssistant macro runner
TCP macro runner -> external-agent execute_macro path
PipelineBuilder -> ordered multi-step workflows
ExplorationEngine -> threshold comparison over TCP, broader comparisons in Java
JobRegistry -> async macro jobs
ReactiveEngine -> optional event-triggered rules
    |
    v
Fiji/ImageJ runtime
Images, stacks, channels, ROIs, overlays, Results table, dialogs,
Bio-Formats, installed ImageJ/Fiji plugins, Groovy/Jython scripts
```

## Context used for decisions

```text
In-app API chat
    -> StateInspector for open images, active image, Results table, ROIs, memory
    -> ImageCapture when visual context is enabled
    -> PromptTemplates for model instructions and macro or pipeline extraction
    -> AgentOrchestrator for segmentation, measurement, visualisation,
       statistics, hypothesis, and adviser routing

Local assistant
    -> IntentLibrary and IntentMatcher for built-in phrases
    -> SlashCommandRegistry for slash commands
    -> IntentRouter for user-taught intents
    -> FijiBridge for direct Fiji actions

Launched CLI agents
    -> agent context docs for rules and workflow
    -> TCP tools for live Fiji state and control
    -> agent/recipes/ for reusable YAML workflows
    -> agent/references/ for static microscopy and ImageJ guidance
    -> probe_command for plugin dialog schemas
    -> interact_dialog for live dialog widgets
```

## Events, safety, and feedback

```text
ImageJAIPlugin startup
    |
    +--> TCPCommandServer if enabled
    |       |
    |       +--> ChatView as ChatPanelController for gui_action feedback
    |       +--> GuiActionDispatcher for TCP/reactive GUI actions
    |       +--> hello handshake, capability flags, optional auth token
    |       +--> PseudonymisationFilter on outbound TCP replies
    |       +--> CaptureHandler / BurnInDetector / request_visual for visual access
    |       +--> safe-mode checks around macro and script execution
    |
    +--> Event publishers
    |       |
    |       +--> EventBus
    |       +--> ImageMonitor
    |       +--> DialogWatcher
    |       +--> TCP subscribe streams for launched agents
    |
    +--> Governance UI and controls
            |
            +--> PostureController and privacy posture
            +--> OutboundPromptScrubber for embedded terminal prompts
            +--> VisualOverrideRegistry for approved full visual access
            +--> AuditLog and DataHandlingStatementGenerator
            +--> DestructiveScanner, RoiAutoBackup, SourceImageTagger
            +--> SafeModeIndicator, status-bar overlay, and safe-mode event panel

Gemma tool-agent safety
    |
    +--> safety.py blocks save paths outside the current AI_Exports/
    +--> active_image.py derives AI_Exports/ beside the active source image
    +--> audit.log and friction.log are best-effort agent-side JSONL logs
```

## Output

```text
Output
    |
    +--> Updated Fiji image windows
    +--> New masks, overlays, projections, channels, or 3D views
    +--> Measurements in the Results table
    +--> Chat messages, progress, previews, toasts, and confirm prompts
    +--> Claude helper captures in agent/.tmp/
    +--> Gemma screenshots, threshold montages, macro outputs,
         audit.log, friction.log, and saved recipes in AI_Exports/
         when a source image path is known
    +--> Java-side audit trail at AI_Exports/imagejai_audit.csv
         beside an open image, or under the working directory if no image path exists
    +--> Data Handling Statement PDFs in AI_Exports/
```

## Short version

```text
User prompt
  -> ImageJAIPlugin / AiRootPanel / ChatView
  -> one of three paths:
       LocalAssistant direct path
       API-backed ConversationLoop
       launched CLI agent in agent/ workspace
  -> direct CommandEngine/PipelineBuilder for in-app work
     or TCPCommandServer for external-agent work
  -> Fiji/ImageJ runtime
  -> image windows, Results table, saved files, audit trail, and chat feedback
```
