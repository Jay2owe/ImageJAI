# ImageJAI infrastructure v2

A linear reader-friendly view of how a user request becomes output.

## Main Flow

```text
1. User prompt
   "Count the cells", "threshold this image", "make a figure"
        |
        v
2. ImageJAI plugin window
   ImageJAIPlugin opens AiRootPanel and ChatView inside Fiji.
        |
        v
3. Choose how the prompt is handled
        |
        +--> A. Local Assistant
        |       ChatView handles the message directly.
        |       LocalAssistant matches known phrases and slash commands.
        |       FijiBridge sends known local actions to CommandEngine.
        |
        +--> B. In-app AI chat
        |       ChatView sends the message to ConversationLoop.
        |       ConversationLoop builds current Fiji context.
        |       A model replies with text plus macro or pipeline blocks.
        |
        +--> C. Launched CLI agent
                AgentLauncher starts Claude, Codex, Gemini CLI, Aider, or Gemma.
                The agent runs in the agent/ workspace.
                The agent uses Python helpers and talks to Fiji over TCP.
        |
        v
4. Gather context before acting
        |
        +--> Current Fiji state
        |       StateInspector / TCP get_state:
        |       open images, active image, Results table, ROIs, memory.
        |
        +--> Image or pixel view
        |       ImageCapture / capture_image for screenshots.
        |       get_pixels / pixels.py for raw pixel measurements.
        |
        +--> Workflow knowledge
        |       agent/recipes/ for reusable analysis recipes.
        |       agent/references/ for microscopy and ImageJ guidance.
        |
        +--> Plugin dialog knowledge
                probe_command discovers plugin parameters.
                interact_dialog reads or controls live dialog widgets.
        |
        v
5. Turn the request into Fiji actions
        |
        +--> Local Assistant path
        |       IntentLibrary + IntentMatcher + SlashCommandRegistry
        |       -> FijiBridge (local-assistant Java facade)
        |       -> CommandEngine
        |
        +--> In-app AI chat path
        |       PromptTemplates + AgentOrchestrator
        |       -> CommandEngine for single macros
        |       -> PipelineBuilder for multi-step workflows
        |       -> ExplorationEngine for threshold comparisons
        |
        +--> Launched CLI path
                agent/ij.py, pixels.py, probe_plugin.py, auditor.py,
                recipe_search.py, and agent/gemma4_31b/tools_*.py
                -> TCP JSON commands
                -> TCPCommandServer
        |
        v
6. Command dispatch
        |
        +--> Direct Java execution
        |       CommandEngine runs ImageJ macros for LocalAssistant
        |       and API-backed in-app chat.
        |
        +--> TCPCommandServer execution
                default port 7746, fallback 7747-7750.
                execute_macro uses the TCP server macro runner.
                run_script runs Groovy/Jython/JavaScript.
                run_pipeline uses PipelineBuilder, which uses CommandEngine.
                explore_thresholds uses ExplorationEngine, which uses CommandEngine.
                execute_macro_async and job_* use JobRegistry.
                subscribe streams EventBus updates to agents.
                reactive_* uses ReactiveEngine.
        |
        v
7. Fiji/ImageJ does the work
        |
        v
   Images, stacks, ROIs, overlays, dialogs, Results table,
   Bio-Formats, installed Fiji plugins, macro language, Groovy/Jython.
        |
        v
8. Raw results and events are produced
        |
        +--> Command replies
        |       JSON results, structured errors, progress, job status.
        |
        +--> Event stream
        |       EventBus frames from macro, image, result, dialog,
        |       job, reactive, and safety events.
        |
        +--> Visual and file outputs
                screenshots, captures, Results table changes,
                saved images, CSV files, recipes, and logs.
        |
        v
9. Safety and governance gateway
   This is an outbound gate, not a sidecar. Raw results/events pass
   through here before they return to chat, CLI agents, or files.
        |
        +--> Safe Mode
        |       DestructiveScanner checks risky macros.
        |       RoiAutoBackup and snapshots preserve recoverable state.
        |       SafeModeIndicator shows recent safety events.
        |
        +--> Privacy posture
        |       PostureController tracks Standard, Pseudonymised, or On-premises.
        |       PseudonymisationFilter redacts outbound TCP command replies
        |       before launched CLI agents receive them.
        |       OutboundPromptScrubber pseudonymises embedded terminal prompts.
        |
        +--> Visual access
        |       CaptureHandler and BurnInDetector guard image captures.
        |       request_visual and VisualOverrideRegistry handle approved full views.
        |
        +--> Audit trail
                AuditLog writes Java-side governance rows.
                Gemma safety.py writes agent-side audit.log and friction.log.
        |
        v
10. Redacted and approved feedback returns
        |
        +--> Direct in-app paths return approved results to ChatView / ConversationLoop.
        +--> TCPCommandServer returns pseudonymised JSON command replies
             to launched CLI agents.
        +--> TCP subscribe sends governed EventBus frames back to launched agents.
        +--> The launched agent reads replies/events and decides the next command
             or writes the final terminal answer.
        +--> GuiActionDispatcher and ChatPanelController show toasts,
             inline previews, markdown, confirmations, and ROI highlights.
        |
        v
11. Output
        |
        +--> Updated Fiji image windows.
        +--> New masks, overlays, projections, channels, or 3D views.
        +--> Measurements in the Results table.
        +--> CSV, TIFF, PNG, recipes, methods files, and logs.
        +--> Java audit CSV at AI_Exports/imagejai_audit.csv.
        +--> Gemma outputs in AI_Exports/ when the active image has a file path.
        +--> Claude helper captures in agent/.tmp/.
        +--> Plain-language answer back in chat or the launched agent terminal.
```

## Return Flow For Launched Agents

```text
Launched CLI agent
  -> TCP JSON command
  -> TCPCommandServer
  -> Fiji/ImageJ work or state read
  -> raw JSON reply / event frame
  -> safety + governance gateway
  -> pseudonymised JSON reply / governed event frame
  -> launched CLI agent
  -> next command, audit/check, or final answer to user

Event stream runs alongside this:
EventBus
  -> safety + governance gateway
  -> TCP subscribe stream
  -> launched CLI agent
  -> progress-aware next step or user-facing update
```

## Separate Path Files

- `local_assistant_workflow.md` - deterministic in-app local assistant path.
- `in_app_chat_workflow.md` - API-backed in-app AI chat path.
- `cli_agent_workflow.md` - launched CLI agent and TCP bridge path.

## Useful Mental Model

```text
The Java plugin owns Fiji integration.
The Python helpers give launched agents a clean remote-control surface.
The TCP server is the two-way bridge between launched agents and Fiji.
Outbound TCP replies and event streams pass through safety/governance first.
FijiBridge is only the local-assistant Java facade, not the launched-agent path.
The output always lands in Fiji, chat/terminal feedback, and where possible AI_Exports/.
```
