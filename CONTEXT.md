# ImageJAI — Condensed Context

Fiji plugin (single JAR, 196KB). AI assistant for ImageJ. NL → macro execution. Free (Gemini) or local (Ollama).

**Java 8 only. No lambdas. No var. Anonymous inner classes. Gson provided. Zero external deps.**

## Build
```
mvn clean package -q && bash build.sh
```

## Layout
- `ImageJAIPlugin.java` — @Plugin entry, creates ChatPanel + ConversationLoop
- `ConversationLoop.java` — user msg → orchestrator → LLM → parse <macro>/<pipeline> → execute → display
- `agents/AgentOrchestrator.java` — keyword routing: HYPOTHESIS > SEGMENTATION > MEASUREMENT > VISUALIZATION > STATISTICS > GENERAL
- `agents/{Segmentation,Measurement,Visualization,Stats,Hypothesis}Agent.java` — specialist LLM prompts
- `llm/{Gemini,Ollama,OpenAICompatible}Backend.java` — HTTP-based LLM calls, BackendFactory creates from Settings
- `engine/CommandEngine.java` — `executeMacro()` on EDT, captures results/errors
- `engine/PipelineBuilder.java` — `<pipeline><step>` parsing + sequential execution
- `engine/ExplorationEngine.java` — try N thresholds, measure, recommend best
- `engine/RecorderHook.java` — CommandListener, pattern detection
- `engine/ImageMonitor.java` — background polling (saturation, memory, calibration)
- `engine/ScriptGenerator.java` — LLM generates Groovy/Jython scripts, installs to Fiji
- `engine/CrossToolRunner.java` — run Python/R via ProcessBuilder
- `knowledge/MacroReference.java` — ~90 entries, keyword search
- `knowledge/DocIndex.java` — ~30 tips/recipes
- `knowledge/PromptTemplates.java` — system prompts, extractMacros(), extractPipelineBlock()
- `ui/ChatPanel.java` — dark Swing panel, ChatListener interface, appendMessage(), setThinking()
- `config/Settings.java` — ~/.imagej-ai/config.json

## Rules
- EDT: all Swing + ImageJ ops. Background: all LLM calls.
- Response format: `<macro>code</macro>` or `<pipeline><step desc="...">code</step></pipeline>`
- Self-correction: feed error back to LLM, retry up to 3x
- No Co-Authored-By on commits
