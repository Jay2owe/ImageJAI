# ImageJAI — Implementation Plan

## Project Overview
A single Fiji plugin (.jar) that adds an AI assistant to ImageJ. Users install by dragging a JAR into `plugins/`. The assistant understands natural language, executes ImageJ operations, sees images via vision models, and progressively unlocks advanced capabilities (pipeline building, parameter exploration, hypothesis-driven analysis).

**Location**: `C:\Users\jamie\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Experiments\ImageJAI`
**Fiji Location**: `C:\Users\jamie\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Fiji.app`
**Fiji Java**: Zulu JDK 8 (bundled) — plugin must target Java 8
**Build**: Maven with pom-scijava parent
**System Java**: JDK 25 (for compilation — must set source/target to 1.8)
**System Maven**: 3.9.9

---

## Architecture

```
imagej-ai/
├── pom.xml                          # Maven build (pom-scijava parent)
├── src/
│   ├── main/java/imagejai/
│   │   ├── ImageJAIPlugin.java      # Entry point: @Plugin, creates panel
│   │   ├── ui/
│   │   │   ├── ChatPanel.java       # Swing chat panel (dockable)
│   │   │   ├── SettingsDialog.java  # First-run config (API key, provider)
│   │   │   └── ImagePreview.java    # Thumbnail renderer for chat
│   │   ├── llm/
│   │   │   ├── LLMBackend.java      # Interface: sendMessage, sendVision
│   │   │   ├── GeminiBackend.java   # Google Gemini (free tier)
│   │   │   ├── OllamaBackend.java   # Local Ollama
│   │   │   ├── OpenAIBackend.java   # OpenAI-compatible (covers Anthropic, etc.)
│   │   │   └── Message.java         # Chat message model
│   │   ├── engine/
│   │   │   ├── CommandEngine.java   # Macro execution, result capture, error handling
│   │   │   ├── StateInspector.java  # Query ImageJ state (images, ROIs, tables, memory)
│   │   │   ├── ImageCapture.java    # Screenshot/thumbnail generation for vision
│   │   │   ├── RecorderHook.java    # Hook into ImageJ's macro recorder
│   │   │   └── PipelineBuilder.java # Multi-step pipeline orchestration
│   │   ├── knowledge/
│   │   │   ├── MacroReference.java  # Indexed macro function reference
│   │   │   ├── PromptTemplates.java # System prompts, tool schemas
│   │   │   └── DocIndex.java        # Simple keyword search over embedded docs
│   │   ├── agents/
│   │   │   ├── AgentOrchestrator.java  # Multi-agent routing
│   │   │   ├── SegmentationAgent.java  # Specialist: segmentation
│   │   │   ├── MeasurementAgent.java   # Specialist: measurement/quantification
│   │   │   ├── VisualizationAgent.java # Specialist: LUTs, projections, figures
│   │   │   └── StatsAgent.java         # Specialist: statistics, export
│   │   └── config/
│   │       ├── Settings.java        # Persist config to ~/.imagej-ai/config.json
│   │       └── Constants.java       # API URLs, defaults, version
│   └── main/resources/
│       ├── macro-reference.json     # Pre-built ImageJ macro function index
│       ├── prompt-templates/        # System prompts for each agent/mode
│       │   ├── system.txt           # Core system prompt
│       │   ├── segmentation.txt
│       │   ├── measurement.txt
│       │   ├── visualization.txt
│       │   └── stats.txt
│       └── META-INF/json/
│           └── org.scijava.plugin.Plugin  # SciJava plugin discovery
├── src/test/java/imagejai/
│   ├── llm/
│   │   ├── GeminiBackendTest.java
│   │   └── OllamaBackendTest.java
│   ├── engine/
│   │   ├── CommandEngineTest.java
│   │   ├── StateInspectorTest.java
│   │   └── PipelineBuilderTest.java
│   └── integration/
│       ├── HeadlessIntegrationTest.java  # Full loop: prompt → macro → result
│       └── VisionIntegrationTest.java    # Screenshot → LLM → response
├── test-scripts/
│   ├── test_headless.py             # PyImageJ-based automated testing
│   ├── test_vision.py               # Vision model testing with sample images
│   ├── test_pipelines.py            # Pipeline execution testing
│   └── sample_images/               # Test images (small TIFFs)
│       ├── blobs.tif
│       ├── confocal_stack.tif
│       └── multichannel.tif
└── docs/
    ├── TESTING.md                   # Testing protocol
    └── USER_GUIDE.md               # End-user documentation
```

---

## Implementation Phases

### Phase 0: Project Skeleton & Build System
**Goal**: Maven project that compiles and produces a .jar installable in Fiji.
**Parallel track**: NONE — foundation, must come first.

**Tasks**:
1. Create `pom.xml` with `pom-scijava` parent, targeting Java 8
2. Create the `ImageJAIPlugin.java` entry point (`@Plugin(type = Command.class)`)
3. Create `Settings.java` for config persistence (`~/.imagej-ai/config.json`)
4. Create `Constants.java` with version, API URLs
5. Verify: `mvn clean package` produces a JAR
6. Verify: JAR placed in Fiji `plugins/` creates a menu entry
7. Initialize git repo

**Test**: Run `mvn package`, copy JAR to Fiji plugins, launch Fiji, confirm "AI Assistant" appears in Plugins menu.

---

### Phase 1: Core (3 parallel tracks)

#### Track 1A: Chat GUI
**Goal**: A Swing panel with message history, input field, and settings button.

**Tasks**:
1. `ChatPanel.java` — JPanel with:
   - JTextPane for message history (HTML rendering for markdown-lite)
   - JTextField for user input (Enter to send, Shift+Enter for newline)
   - Status bar (model name, token count, connection status)
   - Settings gear button → opens SettingsDialog
2. `ImagePreview.java` — Renders image thumbnails inline in chat
3. `SettingsDialog.java` — JDialog with:
   - Provider dropdown (Gemini / Ollama / OpenAI / Custom)
   - API key field (password-masked)
   - Ollama URL field (default: http://localhost:11434)
   - Custom endpoint URL + key fields
   - Model name field
   - Test Connection button
   - Save/Cancel
4. Wire ChatPanel into ImageJAIPlugin as a dockable panel or JFrame

**Test protocol**:
- Launch Fiji, open AI Assistant → chat panel appears
- Type a message, press Enter → appears in message history
- Open Settings → dialog appears with all fields
- Settings persist across restarts (`~/.imagej-ai/config.json`)

#### Track 1B: LLM Backend Layer
**Goal**: Abstraction over multiple LLM providers, all via HTTP.

**Tasks**:
1. `LLMBackend.java` — Interface:
   ```java
   public interface LLMBackend {
       String chat(List<Message> messages, String systemPrompt);
       String chatWithVision(List<Message> messages, String systemPrompt, byte[] imageBytes);
       boolean testConnection();
       String getModelName();
   }
   ```
2. `Message.java` — role (system/user/assistant), content, optional image
3. `GeminiBackend.java`:
   - POST to `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`
   - Handle free tier rate limits (retry with backoff)
   - Support vision (inline_data with base64 image)
   - Default model: `gemini-2.0-flash`
4. `OllamaBackend.java`:
   - POST to `http://localhost:11434/api/chat`
   - Support vision for multimodal models (llava, etc.)
   - Model discovery: GET `/api/tags`
5. `OpenAIBackend.java`:
   - Standard OpenAI chat completions API
   - Works with any OpenAI-compatible endpoint (Anthropic via proxy, together.ai, groq, etc.)
6. All backends use `java.net.HttpURLConnection` — zero external dependencies

**Test protocol**:
- Unit test: mock HTTP responses, verify request format for each backend
- Integration test: real API call to Gemini free tier (needs API key in env var)
- Integration test: real call to Ollama localhost (if running)
- Test connection failure handling (timeout, bad key, rate limit)

#### Track 1C: Command Engine
**Goal**: Execute ImageJ macros, capture results, query state.

**Tasks**:
1. `CommandEngine.java`:
   - `String executeMacro(String macroCode)` — runs on EDT via `SwingUtilities.invokeAndWait()`
   - Captures: `IJ.log()` output, `ResultsTable` contents, error text
   - Redirects log output temporarily during execution
   - Timeout: kill after configurable seconds (default 30)
   - Returns structured result: `{success, output, error, resultsTable, newImages}`
2. `StateInspector.java`:
   - `getImageInfo()` — current image: title, dimensions, type, calibration, channels
   - `getAllImages()` — all open images with summaries
   - `getResultsTable()` — current results as CSV/JSON
   - `getRoiManager()` — all ROIs with properties
   - `getMemoryInfo()` — used/max/free memory
   - `getRecentCommands()` — from ImageJ recorder
3. `ImageCapture.java`:
   - `byte[] captureCurrentImage()` — PNG thumbnail of active image (max 1024px)
   - `byte[] captureComposite()` — all channels as composite
   - `byte[] captureWithOverlays()` — image + ROIs + overlays rendered

**Test protocol**:
- Headless test: `CommandEngine.executeMacro("run('Blobs (25K)');")` → verify blobs image opened
- Headless test: execute `run("Measure")` → verify ResultsTable captured
- Headless test: bad macro → verify error captured, no crash
- StateInspector: open image, query info, verify dimensions match
- ImageCapture: open image, capture PNG, verify non-empty bytes and valid PNG header

---

### Phase 2: The AI Loop (depends on Phase 1)
**Goal**: Wire everything together — user types prompt, AI generates macro, plugin executes, results feed back.

#### Track 2A: Prompt Engineering & Tool System
**Tasks**:
1. `PromptTemplates.java`:
   - Core system prompt: "You are an ImageJ assistant. You control ImageJ by writing macros..."
   - Tool schema definitions (for function-calling capable models)
   - Context injection format: current image info, results table, memory
2. `MacroReference.java`:
   - Load `macro-reference.json` from resources
   - Simple keyword search: user says "threshold" → return relevant functions
   - Injected into LLM context as needed (top-5 relevant functions)
3. Build `macro-reference.json`:
   - Extract from ImageJ macro functions reference (300+ functions)
   - Format: `{name, syntax, description, example, category}`
4. System prompt design:
   - Instruct AI to respond with `<macro>code</macro>` blocks for execution
   - Instruct AI to use `<question>text</question>` for clarifying questions
   - Instruct AI to use `<pipeline>` blocks for multi-step workflows
   - Include current state context in every message

#### Track 2B: Conversation Loop
**Tasks**:
1. Wire ChatPanel → LLMBackend → CommandEngine → ChatPanel:
   ```
   User input
   → build prompt (system + state context + macro reference + history)
   → LLM call (background thread)
   → parse response for <macro>, <question>, <pipeline> blocks
   → execute macros via CommandEngine (on EDT)
   → capture results
   → append results to conversation
   → display AI response in chat
   ```
2. Conversation memory (last N messages, configurable)
3. Streaming response support (show text as it arrives, for Ollama/Gemini streaming endpoints)
4. Error recovery: if macro fails, feed error back to LLM for self-correction
5. Auto-retry logic: up to 3 attempts to fix a failed macro

**Test protocol**:
- End-to-end: "Open the sample blobs image" → blobs image opens
- End-to-end: "Make a binary threshold using Otsu's method" → threshold applied
- End-to-end: "Measure all particles" → ResultsTable populated
- Error recovery: "Run a command that doesn't exist" → AI gets error, explains to user
- Multi-turn: "Open blobs" → "Threshold it" → "Measure particles" → correct sequence

---

### Phase 3: Vision + Intelligence (3 parallel tracks)

#### Track 3A: Image-Aware Reasoning
**Tasks**:
1. After each macro execution, optionally capture screenshot
2. On user request ("what do you see?", "does this look right?"), capture and send to vision
3. Auto-capture when AI wants to verify its own results
4. Format: resize to max 1024px, PNG, base64 encode
5. Add to LLM message as image content (Gemini and Ollama both support this)

**Test protocol**:
- Open confocal image → "What type of image is this?" → AI describes it reasonably
- Apply threshold → "Does this threshold look correct?" → AI evaluates
- Bad segmentation → AI identifies problems without being told

#### Track 3B: Macro Recorder Hook (Teaching Mode)
**Tasks**:
1. `RecorderHook.java`:
   - Register as `CommandListener` to intercept all user actions
   - Buffer recent commands (last 50)
   - Detect patterns: same sequence repeated N times
   - On pattern detection, emit suggestion to ChatPanel
2. NL annotation: each recorded command → LLM generates English description
3. "What did I just do?" command → summarize recent actions
4. "Make this a macro" → export recorded sequence as a runnable macro

**Test protocol**:
- User runs Gaussian Blur manually → recorder captures it
- User repeats same 3 steps on 3 images → suggestion offered
- "What did I just do?" → accurate summary

#### Track 3C: RAG / Knowledge Base
**Tasks**:
1. `DocIndex.java`:
   - Prebuilt index of ImageJ macro functions + common plugin docs
   - TF-IDF or simple keyword matching (no external deps)
   - Query: user intent → top-K relevant docs → inject into prompt
2. Build the index at compile time from scraped docs
3. Include common recipes: CTCF measurement, colocalization, batch processing, cell counting

**Test protocol**:
- "How do I measure corrected total cell fluorescence?" → retrieves CTCF formula
- "What thresholding methods are available?" → lists all methods with descriptions
- Unknown query → graceful fallback (no irrelevant docs injected)

---

### Phase 4: Pipeline Builder & Exploration (2 parallel tracks)

#### Track 4A: Pipeline Builder
**Tasks**:
1. `PipelineBuilder.java`:
   - Parse `<pipeline>` blocks from AI responses
   - Execute steps sequentially, capturing intermediate results
   - Validate each step before proceeding (check for errors, verify image exists)
   - Save pipeline as a reusable .ijm macro file
   - Resume/retry from failed step
2. Pipeline visualization in chat: numbered steps with status (✓, ▶, ✗)
3. "Run this on all images in folder" → batch mode wrapper
4. Pipeline templates for common workflows: cell counting, colocalization, etc.

**Test protocol**:
- "Count cells in this image" → multi-step pipeline executes correctly
- Pipeline with error at step 3 → stops, reports, offers to retry
- "Save this pipeline" → valid .ijm file written
- "Run on folder" → batch processes all TIFFs in a directory

#### Track 4B: Undo-Aware Exploration
**Tasks**:
1. Before trying a parameter, duplicate the image (`imp.duplicate()`)
2. Try N variations (e.g., 6 threshold methods)
3. Measure results quantitatively (object count, mean area, circularity)
4. Present comparison: "I tried 6 methods. Li gave the best results because..."
5. Apply the best one, discard duplicates
6. Memory management: limit concurrent duplicates, clean up

**Test protocol**:
- "Find the best threshold method" → tries multiple, reports comparison
- Verify: duplicate images are cleaned up after exploration
- Verify: memory doesn't leak (check Runtime.freeMemory before/after)

---

### Phase 5: Specialist Agents (parallel tracks)

#### Track 5A: Agent Orchestrator + Segmentation Agent
**Tasks**:
1. `AgentOrchestrator.java`:
   - Classify user intent → route to specialist
   - Specialist has own system prompt + domain knowledge
   - Results flow back through orchestrator to user
2. `SegmentationAgent.java`:
   - Knows: thresholding methods, watershed, StarDist, Cellpose (if installed)
   - Recommends method based on image type and biological context
   - Can run multi-method comparison (uses exploration from 4B)

#### Track 5B: Measurement + Stats Agents
**Tasks**:
1. `MeasurementAgent.java`:
   - Knows: particle analysis, intensity measurement, CTCF, colocalization
   - Validates measurement setup (correct calibration, appropriate channels)
2. `StatsAgent.java`:
   - Reads ResultsTable, suggests appropriate statistical tests
   - Can generate summary statistics, histograms, box plots
   - Exports publication-ready CSV

#### Track 5C: Visualization Agent
**Tasks**:
1. `VisualizationAgent.java`:
   - LUT recommendations based on data type
   - Publication-quality montage generation
   - Z-projection method selection
   - Scale bar addition, annotation

**Test protocol (all agents)**:
- Route test: "segment the nuclei" → SegmentationAgent
- Route test: "what's the mean intensity?" → MeasurementAgent
- Route test: "make this look good for a paper" → VisualizationAgent
- Route test: "is there a significant difference?" → StatsAgent
- Full chain: segment → measure → stats → visualize → all agents cooperate

---

### Phase 6: Advanced Features (parallel tracks)

#### Track 6A: Hypothesis-Driven Analysis
**Tasks**:
1. Parse hypothesis from natural language
2. Design analysis plan (which channels, which regions, what measurements, what stats)
3. Execute plan using pipeline builder + specialist agents
4. Present results with statistical conclusions
5. Suggest additional analyses the user might not have considered

#### Track 6B: Live Annotation + ImageJ Whisperer
**Tasks**:
1. Background monitoring thread (polls every 5s)
2. Detect issues: saturation, uneven illumination, clipping, memory pressure
3. Add overlay annotations (warnings, suggestions)
4. Memory/performance monitoring with proactive warnings

#### Track 6C: Plugin Forge + Cross-Tool (stretch)
**Tasks**:
1. Generate Groovy/Jython scripts with `#@Parameter` annotations
2. Save to `scripts/Plugins/Generated/` → auto-appears in menus
3. Optional Python integration via `Runtime.exec()`

---

## Parallel Execution Map

```
Phase 0: [Skeleton]─────────────────────────────┐
                                                  │
Phase 1: [1A: Chat GUI]────────────┐              │
         [1B: LLM Backends]────────┤  (parallel)  │
         [1C: Command Engine]──────┘              │
                                   │              │
Phase 2: [2A: Prompts/Tools]───────┤  (parallel   │
         [2B: Conv. Loop]──────────┘   then merge) │
                                   │              │
Phase 3: [3A: Vision]─────────────┐│              │
         [3B: Recorder Hook]──────┤│  (parallel)  │
         [3C: RAG/Knowledge]──────┘│              │
                                   │              │
Phase 4: [4A: Pipeline Builder]───┐│              │
         [4B: Exploration]────────┘│  (parallel)  │
                                   │              │
Phase 5: [5A: Segmentation]──────┐ │              │
         [5B: Measurement+Stats]─┤ │  (parallel)  │
         [5C: Visualization]─────┘ │              │
                                   │              │
Phase 6: [6A: Hypothesis]────────┐ │              │
         [6B: Live Annotation]───┤ │  (parallel)  │
         [6C: Plugin Forge]──────┘ │              │
```

**Subagent allocation per phase**:
- Phase 0: 1 agent (sequential, foundation)
- Phase 1: 3 agents in parallel (GUI, LLM, Engine)
- Phase 2: 1 agent (integration, must sequence after Phase 1)
- Phase 3: 3 agents in parallel
- Phase 4: 2 agents in parallel
- Phase 5: 3 agents in parallel
- Phase 6: 3 agents in parallel

---

## Testing Protocol

### Automated Testing (JUnit + Maven)

All tests run via `mvn test`. Tests are categorized:

#### Unit Tests (no ImageJ, no network)
- LLM backend request formatting (mock HTTP)
- Message serialization
- Macro reference indexing and search
- Settings persistence (read/write JSON)
- Pipeline parsing
- State inspector data formatting

#### Integration Tests (headless ImageJ, no network)
```java
@Test
public void testMacroExecution() {
    // Uses ImageJ headless context
    Context ctx = new Context();
    CommandEngine engine = new CommandEngine(ctx);

    ExecutionResult result = engine.executeMacro("newImage('test', '8-bit', 256, 256, 1);");
    assertTrue(result.isSuccess());
    assertEquals("test", IJ.getImage().getTitle());
}

@Test
public void testThresholdAndMeasure() {
    CommandEngine engine = new CommandEngine(ctx);
    engine.executeMacro("run('Blobs (25K)');");
    engine.executeMacro("setAutoThreshold('Otsu');");
    engine.executeMacro("run('Analyze Particles...', 'summarize');");

    String results = engine.getResultsTable();
    assertFalse(results.isEmpty());
}
```

#### LLM Integration Tests (requires API key, skipped in CI)
```java
@Test
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
public void testGeminiChat() {
    GeminiBackend backend = new GeminiBackend(System.getenv("GEMINI_API_KEY"));
    String response = backend.chat(
        List.of(new Message("user", "What ImageJ macro opens the sample blobs image?")),
        "You are an ImageJ expert. Respond with only the macro code."
    );
    assertTrue(response.contains("Blobs"));
}
```

### Automated End-to-End Tests (PyImageJ)

Python scripts in `test-scripts/` that drive the full plugin:

```python
# test_headless.py
import imagej
import json, socket

# Initialize Fiji with plugin
ij = imagej.init('/path/to/Fiji.app')

# Verify plugin loaded
assert 'AI Assistant' in str(ij.command().getCommands())

# Test command engine directly (via script injection)
result = ij.py.run_macro('run("Blobs (25K)");')
info = ij.py.run_script('groovy', '''
    import ij.IJ
    def imp = IJ.getImage()
    return "${imp.width}x${imp.height}"
''', {})
assert '256x254' in str(info)
```

### Manual Testing Checklist (per phase)

#### Phase 1 Checklist
- [ ] Fiji launches with AI Assistant in Plugins menu
- [ ] Chat panel opens and accepts text input
- [ ] Settings dialog opens, saves, persists across restart
- [ ] Gemini backend: send message, get response
- [ ] Ollama backend: send message, get response (if Ollama running)
- [ ] Test Connection button works for each provider
- [ ] Command engine: `run("Blobs (25K)")` opens the image
- [ ] State inspector: correctly reports open image info
- [ ] Image capture: produces valid PNG thumbnail

#### Phase 2 Checklist
- [ ] "Open the blobs sample image" → image opens
- [ ] "Apply a Gaussian blur with sigma 2" → blur applied
- [ ] "Threshold with Otsu" → threshold applied
- [ ] "Measure all particles" → results table populated
- [ ] "What image do I have open?" → correct description
- [ ] Failed macro → AI explains error and suggests fix
- [ ] Multi-turn conversation maintains context

#### Phase 3 Checklist
- [ ] "What do you see in this image?" → AI describes the image content
- [ ] "Does this segmentation look right?" → AI evaluates visually
- [ ] User performs manual actions → recorder captures them
- [ ] "What did I just do?" → accurate summary
- [ ] Macro reference queries return relevant functions

#### Phase 4 Checklist
- [ ] "Count all cells in this image" → multi-step pipeline executes
- [ ] Pipeline failure at step N → reports error, offers retry
- [ ] "Save this as a macro" → valid .ijm file
- [ ] "Find the best threshold" → tries multiple, presents comparison
- [ ] Memory is clean after exploration (no leaked image copies)

#### Phase 5 Checklist
- [ ] "Segment the nuclei" → routed to segmentation specialist
- [ ] "Measure mean intensity per cell" → measurement specialist
- [ ] "Make a nice figure" → visualization specialist
- [ ] "Is there a significant difference?" → stats specialist
- [ ] Complex request → agents cooperate correctly

#### Phase 6 Checklist
- [ ] State a hypothesis → AI designs appropriate analysis
- [ ] Live monitoring detects saturated image → warns user
- [ ] Memory pressure → proactive warning
- [ ] "Make me a plugin that does X" → Groovy script generated

### Regression Testing

After each phase, re-run ALL previous phase checklists to ensure nothing broke. The JUnit test suite should cover this automatically for engine/backend code. Manual GUI testing needed for Swing components.

---

## Build & Deploy

### Build
```bash
cd "C:\Users\jamie\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Experiments\ImageJAI"
mvn clean package -Dmaven.compiler.source=1.8 -Dmaven.compiler.target=1.8
```

### Deploy to local Fiji
```bash
cp target/imagej-ai-*.jar "C:\Users\jamie\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Fiji.app\plugins\"
```

### Deploy via Fiji Update Site (later)
Set up a hosted update site on sites.imagej.net for public distribution.

---

## Dependencies (must be zero external)

The plugin MUST NOT require any dependencies beyond what Fiji already ships. This means:
- HTTP client: `java.net.HttpURLConnection` (JDK standard)
- JSON: Gson 2.10.1 (already shipped with Fiji, scope: provided)
- GUI: javax.swing (JDK standard)
- ImageJ API: provided by Fiji at runtime (scope: provided in Maven)
- No Python, no npm, no additional downloads

The only "dependency" is an API key (for cloud providers) or Ollama installed locally.

---

## Risk Mitigation

| Risk | Mitigation |
|---|---|
| Fiji uses Java 8, modern APIs unavailable | Target Java 8 strictly. No var, no records, no HttpClient. |
| LLM generates invalid macros | Self-correction loop: feed error back, retry up to 3x |
| Large images slow down vision | Resize to max 1024px before sending. Never send raw pixel data. |
| Gemini free tier rate limits | Exponential backoff. Show "rate limited, waiting..." in chat. |
| Swing EDT blocking during LLM call | All LLM calls on background SwingWorker threads. Never block EDT. |
| JSON parsing without library | Write a minimal JSON parser or use ImageJ's bundled Gson (check Fiji classpath) |
| User has no API key and no Ollama | Show clear setup instructions. "Get a free key in 30 seconds" link. |
