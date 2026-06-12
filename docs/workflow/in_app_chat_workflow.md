# In-App AI Chat Workflow

API-backed chat inside the ImageJAI panel.

```text
1. User prompt in ImageJAI chat
   "segment the nuclei", "count cells", "make a max projection"
        |
        v
2. ChatView
   Local Assistant is not selected.
   API-backed chat is enabled.
   ChatView notifies registered chat listeners.
        |
        v
3. ConversationLoop
   Runs on a background thread.
   Maintains message history.
   Decides whether the prompt needs image vision.
        |
        v
4. Gather current Fiji context
        |
        +--> StateInspector
        |       open images, active image, Results table, ROIs, memory
        |
        +--> ImageCapture
        |       active-image screenshot when vision is enabled and useful
        |
        +--> PromptTemplates
                system prompt, context block, macro/pipeline parsing rules
        |
        v
5. Select model and routing
        |
        +--> BackendFactory / LLMBackend
        |       Gemini, OpenAI-compatible, Ollama, or configured backend
        |
        +--> AgentOrchestrator
                optional specialist routing:
                segmentation, measurement, visualisation, statistics,
                hypothesis, or adviser mode
        |
        v
6. Model response
   The model returns plain text and optionally:
        |
        +--> <macro> blocks
        |
        +--> <pipeline> blocks
        |
        v
7. Execute requested action
        |
        +--> Macro path
        |       CommandEngine runs macro code.
        |       On failure, ConversationLoop asks the model for a fix and retries.
        |
        +--> Pipeline path
                PipelineBuilder parses ordered steps.
                PipelineBuilder executes each step through CommandEngine.
                Progress is shown in ChatView.
        |
        +--> Threshold exploration path
                ExplorationEngine compares threshold methods when requested.
        |
        v
8. Fiji/ImageJ runtime
   Images, stacks, ROIs, overlays, Results table, plugins, dialogs.
        |
        v
9. Safety and governance gateway
   Raw execution results, progress, captures, and saved outputs pass here first.
   Safe Mode records risky macro/script actions and recoverable state.
   CaptureHandler and BurnInDetector guard post-execution image captures.
   AuditLog and posture UI record governance-relevant events.
        |
        v
10. Return to chat
   ConversationLoop summarizes execution.
   New images and Results table output are reported.
   Optional post-execution capture is shown if enabled.
        |
        v
11. Output
   Updated Fiji windows, measurements, pipeline progress, chat reply,
   optional saved output from macros.
```

## Short Version

```text
User prompt
  -> ChatView
  -> ConversationLoop
  -> StateInspector / ImageCapture / PromptTemplates
  -> LLMBackend and optional AgentOrchestrator specialist
  -> macro or pipeline
  -> CommandEngine / PipelineBuilder / ExplorationEngine
  -> Fiji/ImageJ
  -> safety/governance gateway
  -> execution summary back to ChatView
```
