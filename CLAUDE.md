# ImageJAI — Claude Context

## What This Is
A Fiji/ImageJ plugin (single JAR) that adds an AI assistant. Natural language → ImageJ macro execution → results. Free via Google Gemini or local via Ollama. No terminal, no CLI, no subscription required.

## Location
`C:\Users\jamie\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Experiments\ImageJAI`

## Fiji Location
`C:\Users\jamie\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Fiji.app`

## Tech Stack
- Java 8 (Fiji's Zulu JDK 8) — ALL code must be Java 8 compatible
- Maven with pom-scijava parent (v37.0.0)
- Swing for GUI (inside ImageJ's JVM)
- Gson 2.10.1 (provided by Fiji — scope: provided)
- java.net.HttpURLConnection for all HTTP (no external HTTP libs)
- System Java: JDK 25 (for compilation, but source/target = 1.8)

## Build & Deploy
```bash
mvn clean package -q
bash build.sh          # builds + copies JAR to Fiji plugins/
```

## Project Structure
```
src/main/java/imagejai/
  ImageJAIPlugin.java          Entry point (@Plugin, menu: Plugins > AI Assistant)
  ConversationLoop.java        Main orchestrator: user msg → LLM → macro exec → result

  config/
    Constants.java              API URLs, timeouts, defaults
    Settings.java               Persisted config (~/.imagej-ai/config.json)

  ui/
    ChatPanel.java              Dark-themed Swing chat panel, ChatListener interface
    SettingsDialog.java         Provider/key/model config dialog
    ImagePreview.java           Inline thumbnail renderer

  llm/
    LLMBackend.java             Interface: chat(), chatWithVision(), testConnection()
    GeminiBackend.java          Google Gemini (free tier)
    OllamaBackend.java          Local Ollama
    OpenAICompatibleBackend.java Any OpenAI-compatible endpoint
    BackendFactory.java         Creates backend from Settings
    Message.java                Chat message model (role, content, image)
    LLMResponse.java            Response model (content, success, error, tokens)
    HttpUtil.java               Shared HTTP POST/GET utility

  engine/
    CommandEngine.java          Execute macros on EDT, capture results/errors
    ExecutionResult.java        Macro result (success, output, resultsTable, newImages)
    StateInspector.java         Query images, ROIs, ResultsTable, memory
    ImageInfo.java              Image metadata (title, dims, type, calibration)
    MemoryInfo.java             JVM memory state
    ImageCapture.java           PNG thumbnail capture for vision
    PipelineBuilder.java        Multi-step <pipeline> parsing + execution with progress
    ExplorationEngine.java      Try N thresholds/params, measure, recommend best
    RecorderHook.java           CommandListener hook, pattern detection, macro export
    ImageMonitor.java           Background polling: saturation, calibration, memory warnings
    AnnotationHelper.java       Overlay annotations (text, arrows, scale bars, warnings)
    ScriptGenerator.java        Generate Groovy/Jython/macro scripts with GUI, install to Fiji
    CrossToolRunner.java        Run Python/R scripts externally
    TCPCommandServer.java       Optional TCP server (port 7746) for Claude CLI / external agents

  agents/
    AgentOrchestrator.java      Keyword-based intent classification → route to specialist
    SegmentationAgent.java      Thresholding, watershed, StarDist/Cellpose knowledge
    MeasurementAgent.java       CTCF, colocalization, particle analysis, per-cell
    VisualizationAgent.java     LUTs, projections, montages, publication figures
    StatsAgent.java             Statistical test selection, export, biological replicates
    HypothesisAgent.java        Hypothesis → complete analysis pipeline design

  knowledge/
    PromptTemplates.java        System prompts, <macro>/<pipeline> extraction, vision addendum
    MacroReference.java         ~90 indexed macro functions with keyword search
    DocIndex.java               ~30 tips/recipes (CTCF, colocalization, neuroscience workflows)
```

## Critical Constraints
- **Java 8**: No var, no records, no HttpClient, no text blocks, no switch expressions, no lambdas (use anonymous inner classes)
- **Zero external deps**: Only Fiji-shipped libraries (Gson, ImageJ API, Swing)
- **EDT safety**: All Swing/ImageJ operations on EDT via `SwingUtilities.invokeAndWait()`. All LLM calls on background threads.
- **Plugin entry**: `@Plugin(type = Command.class, menuPath = "Plugins>AI Assistant")`
- **Response format**: LLM returns `<macro>code</macro>` for single operations, `<pipeline><step>` blocks for multi-step workflows

## Key Patterns

### Conversation Loop Flow
```
User message → AgentOrchestrator.classifyIntent() → route to specialist or general
→ Build prompt: system prompt + [STATE] context + [RELEVANT MACRO FUNCTIONS] + history
→ LLM call on background thread (SwingWorker)
→ Parse response: extractMacros() / extractPipelineBlock()
→ Execute via CommandEngine.executeMacro() on EDT
→ Capture results, feed back for self-correction if failed (up to 3 retries)
→ Display in ChatPanel
```

### Agent Routing (keyword-based, no LLM call)
- HYPOTHESIS: "hypothesi", "test whether", "compare...between", "is...increased"
- SEGMENTATION: "segment", "threshold", "detect cells", "watershed"
- MEASUREMENT: "measure", "intensity", "CTCF", "count", "colocalization"
- VISUALIZATION: "LUT", "projection", "montage", "figure", "scale bar"
- STATISTICS: "t-test", "ANOVA", "p-value", "significant", "compare groups"
- GENERAL: everything else

### TCP Server (Optional, off by default)
- Port 7746 (AgentConsole uses 7745)
- JSON protocol: `{"command": "execute_macro", "code": "..."}` → `{"ok": true, "result": {...}}`
- Commands: ping, execute_macro, get_state, get_image_info, get_results_table, capture_image, run_pipeline, explore_thresholds, get_state_context, batch
- Enable in Settings > Advanced > "Enable TCP command server"
- Shares CommandEngine/StateInspector with chat panel but has independent conversation (no shared LLM)
- EDT dispatch via CountDownLatch pattern (same as AgentConsole's QTimer.singleShot + Event)

### Vision
- Triggered by keywords: "see", "look", "check", "describe", "image"
- Captures PNG via ImageCapture.captureActiveImage() (max 1024px)
- Sends to LLM via chatWithVision() (Gemini Flash vision is free)
- Optional auto-screenshot after macro execution (settings.autoScreenshot)

## Context Hook (Auto-Injected Fiji State)
A context hook (`context_hook.py`) runs automatically via `.claude/settings.local.json` to inject live Fiji state into conversations.

**Session start:** Fiji connection status, installed plugins/commands list, available reference docs.
**Every message:** Open images (titles, dims, type, calibration, ROI), results table (rows + columns), JVM memory, open dialogs (errors/warnings/prompts), IJ log (last 10 lines).

All queries go through the TCP server on port 7746. If Fiji isn't running or TCP is disabled, the hook reports "not connected" and skips Fiji queries gracefully.

## User Preferences
- **No Co-Authored-By lines on git commits** — never add Claude co-author tags
- Home directory has a git repo — always use project-level repos
