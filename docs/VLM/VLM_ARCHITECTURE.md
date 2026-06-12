# VLM Architecture Integration for ImageJAI

## 1. Current Architecture Overview

```
ImageJAIPlugin.java
  |
  +-- ConversationLoop.java  (main orchestrator)
  |     |
  |     +-- AgentOrchestrator  (keyword routing to specialists)
  |     |     +-- SegmentationAgent
  |     |     +-- MeasurementAgent
  |     |     +-- VisualizationAgent
  |     |     +-- StatsAgent
  |     |     +-- HypothesisAgent
  |     |
  |     +-- LLMBackend (interface)
  |     |     +-- GeminiBackend       (cloud, free tier, vision)
  |     |     +-- OllamaBackend       (local, private, vision)
  |     |     +-- OpenAICompatibleBackend (cloud, paid)
  |     |
  |     +-- CommandEngine             (macro execution on EDT)
  |     +-- PipelineBuilder           (multi-step workflows)
  |     +-- ExplorationEngine         (parameter optimization)
  |     +-- StateInspector            (query ImageJ state)
  |     +-- ImageCapture              (PNG thumbnails for vision)
  |     +-- ImageMonitor              (background quality checks)
  |     +-- RecorderHook              (macro recording, teaching)
  |     +-- ScriptGenerator           (Groovy/Jython generation)
  |     +-- CrossToolRunner           (Python/R subprocess)
  |     +-- AnnotationHelper          (overlays, warnings)
  |
  +-- TCPCommandServer                (external agent access)
  +-- ChatPanel                       (Swing UI)
  +-- Settings                        (~/.imagej-ai/config.json)
```

### Key Integration Points for VLM

The architecture already has the foundation for VLM integration:

1. **LLMBackend.chatWithVision()** -- All backends support image input
2. **ImageCapture** -- Captures images as PNG byte arrays
3. **ConversationLoop.shouldUseVision()** -- Keyword-based vision triggering
4. **AgentOrchestrator** -- Routes to specialists (add VLM-specific agent)
5. **TCPCommandServer** -- External agents can capture images and run queries
6. **StateInspector** -- Provides context (images, results, memory)
7. **ExplorationEngine** -- Parameter optimization (add VLM validation)
8. **CrossToolRunner** -- Can invoke Python for specialized VLM models


## 2. Proposed Architecture Changes

### 2.1 Enhanced Vision Pipeline

```
CURRENT FLOW:
  User message --> shouldUseVision() keyword check
                     |
                   [yes] --> ImageCapture.captureActiveImage() --> chatWithVision()
                     |
                   [no]  --> chat() (text only)

PROPOSED FLOW:
  User message --> VisionRouter.shouldCaptureImage()
                     |
                   [full] --> ImageCapture.captureMultiChannel() --> chatWithVision()
                     |          + StructuredVisionPrompt
                   [light] --> ImageCapture.captureThumbnail(256px) --> chatWithVision()
                     |          + brief context only
                   [none] --> chat() (text only, no image open)
```

The `VisionRouter` replaces the simple keyword regex with a smarter decision:
- **Full vision**: User explicitly asks about the image (current behavior, enhanced)
- **Light vision**: Image is open, user asks something that might benefit from visual
  context (always-on low-res thumbnail)
- **No vision**: No image open, or user is asking about settings/help

### 2.2 New Classes

#### VisionRouter.java
```
package imagejai.engine;

/**
 * Decides whether and how to include vision context with each message.
 * Replaces the simple VISION_KEYWORDS regex in ConversationLoop.
 */
public class VisionRouter {

    public enum VisionMode {
        FULL,    // High-res capture, microscopy-specific vision prompt
        LIGHT,   // Low-res thumbnail for passive context
        NONE     // Text only
    }

    /**
     * Determine vision mode based on user message and current state.
     */
    public static VisionMode classify(String userMessage, boolean imageOpen,
                                       boolean visionEnabled) {
        if (!visionEnabled || !imageOpen) return VisionMode.NONE;
        if (hasExplicitVisionKeyword(userMessage)) return VisionMode.FULL;
        return VisionMode.LIGHT;  // Always include low-res when image is open
    }
}
```

#### MicroscopyVisionPrompt.java
```
package imagejai.knowledge;

/**
 * Microscopy-specialized system prompt additions for vision queries.
 * Instructs the VLM to analyze images using microscopy-specific terminology
 * and identify domain-relevant features.
 */
public final class MicroscopyVisionPrompt {

    public static String getFullVisionPrompt() {
        // Detailed microscopy analysis instructions
        // - Identify modality, staining, structures
        // - Assess quality (SNR, saturation, artifacts)
        // - Suggest analysis approach
        // - Use standard morphology terminology
    }

    public static String getLightVisionPrompt() {
        // Brief context-only prompt
        // "A microscopy image is shown for reference. Use it if relevant
        //  to the user's question but do not describe it unprompted."
    }
}
```

#### AnalysisSession.java
```
package imagejai.engine;

/**
 * Tracks the analysis session state across conversation turns.
 * Records what has been done, what results exist, and what images
 * are in play.
 */
public class AnalysisSession {
    private List<AnalysisRecord> analyses;  // type, params, key results
    private List<String> imagesCreated;
    private Map<String, String> resultsSummaries;

    /**
     * Build a session context string for prompt injection.
     */
    public String buildSessionContext() { ... }
}
```

#### ReportGenerator.java
```
package imagejai.engine;

/**
 * Generates narrative analysis reports by combining quantitative results
 * from ImageJ with VLM visual assessment and biological interpretation.
 */
public class ReportGenerator {

    /**
     * Generate a full analysis report.
     * Collects current state, captures images, sends to VLM.
     */
    public String generateReport(LLMBackend backend,
                                  StateInspector inspector,
                                  AnalysisSession session) { ... }

    /**
     * Generate a figure caption suitable for a manuscript.
     */
    public String generateCaption(LLMBackend backend,
                                   byte[] figureImage,
                                   String analysisDescription) { ... }
}
```

### 2.3 Enhanced ImageCapture

Add these methods to the existing `ImageCapture.java`:

```java
/**
 * Capture a multi-channel composite as a labeled montage.
 * Each channel is rendered with its LUT and labeled.
 */
public static byte[] captureMultiChannel(ImagePlus imp, int maxSize) { ... }

/**
 * Capture a small thumbnail for passive vision context.
 * Uses 256px max dimension to minimize token cost.
 */
public static byte[] captureThumbnail(int maxSize) {
    return captureActiveImage();  // with smaller maxSize parameter
}

/**
 * Capture a side-by-side comparison of two images.
 * Used for before/after comparisons in parameter exploration.
 */
public static byte[] captureComparison(ImagePlus before, ImagePlus after,
                                        int maxSize) { ... }
```

### 2.4 New Agent: VisionAnalysisAgent

```
package imagejai.agents;

/**
 * Specialist agent for vision-intensive analysis tasks.
 * Routes to this agent when the user asks for visual assessment,
 * quality control, or morphological description.
 */
public class VisionAnalysisAgent {

    /**
     * Process a vision-specific request.
     * Always includes the image, uses microscopy vision prompt,
     * and can produce structured output.
     */
    public String process(String userMessage, String stateContext,
                          List<Message> history, byte[] imageBytes) { ... }
}
```

Add to `AgentOrchestrator.AgentType`:
```java
/** Vision-intensive analysis (morphology description, quality assessment). */
VISION_ANALYSIS
```

Add routing pattern:
```java
private static final Pattern VISION_ANALYSIS_PATTERN = Pattern.compile(
    "\\b(morpholog|describe.*cell|describe.*image|quality.*assessment"
    + "|what.*see|artifact|focus|saturation|noise|blur|stain)\\b",
    Pattern.CASE_INSENSITIVE);
```


## 3. Integration Points with Existing Systems

### 3.1 ExplorationEngine + VLM Validation

```
CURRENT ExplorationEngine FLOW:
  1. Duplicate image
  2. Try method A -> measure quantitatively
  3. Try method B -> measure quantitatively
  4. ...try N methods
  5. Compare metrics, recommend best

ENHANCED FLOW:
  1. [NEW] Send image to VLM -> get parameter range suggestion
  2. Duplicate image
  3. Try methods in VLM-suggested range -> measure quantitatively
  4. [NEW] Capture top-3 results as comparison image
  5. [NEW] Send comparison to VLM for visual validation
  6. Combine quantitative + VLM assessment -> final recommendation
```

**Implementation**: Add `VLMAdvisor` methods to `ExplorationEngine`:
```java
/**
 * Ask VLM for parameter range before exploration.
 */
private String getVLMParameterSuggestion(byte[] imageBytes, String taskType) {
    String prompt = MicroscopyVisionPrompt.getExplorationPrompt(taskType);
    LLMResponse response = backend.chatWithVision(
        Collections.singletonList(Message.user(prompt)),
        MicroscopyVisionPrompt.getSystemPrompt(),
        imageBytes);
    return response.getContent();
}
```

### 3.2 ImageMonitor + VLM Quality Check

```
CURRENT ImageMonitor FLOW:
  Poll every 5s:
    - Check pixel statistics for saturation
    - Check calibration
    - Check memory pressure
    - Show warnings via AnnotationHelper

ENHANCED FLOW:
  On new image opened (one-time, not every poll):
    - [NEW] Capture 256px thumbnail
    - [NEW] Send to VLM with quality assessment prompt
    - [NEW] Parse response for warning flags
    - Show VLM warnings via AnnotationHelper alongside existing checks
```

**Key constraint**: Only trigger VLM check ONCE per image, not on every poll cycle.
Use a `Set<String>` of already-checked image titles to prevent repeat queries.

### 3.3 RecorderHook + VLM Teaching Enhancement

```
CURRENT RecorderHook FLOW:
  CommandListener captures user actions:
    - Buffer recent commands (last 50)
    - Detect patterns (same sequence repeated N times)
    - Suggest automation

ENHANCED FLOW:
  On pattern detection:
    - [NEW] Capture before/after images of the repeated workflow
    - [NEW] Send to VLM: "User repeated this workflow N times: [commands].
             Here is a before/after. Suggest improvements."
    - Display VLM suggestions in chat
```

### 3.4 TCPCommandServer + VLM Access

Add new TCP commands for VLM features:

```json
{"command": "vlm_describe", "prompt": "Describe the cells in this image"}
```

```json
{"command": "vlm_assess_quality"}
```

```json
{"command": "vlm_suggest_params", "task": "threshold"}
```

```json
{"command": "generate_report"}
```

These enable external agents (Claude CLI, Python scripts) to leverage VLM features
through the existing TCP protocol.


## 4. Data Flow Diagrams

### 4.1 Vision-Enhanced Conversation Flow

```
User types message
        |
        v
VisionRouter.classify()
        |
   +----+----+-----+
   |         |      |
  FULL     LIGHT   NONE
   |         |      |
   v         v      v
Capture    Capture  (skip)
1024px     256px
   |         |      |
   v         v      v
MicroscopyVisionPrompt  +  PromptTemplates
   .getFullVisionPrompt()   .getSystemPrompt()
        |
        v
AgentOrchestrator.classifyIntent()
        |
   +----+----+----+----+----+----+
   |    |    |    |    |    |    |
  SEG  MEAS VIS  STAT HYP  VADV GEN
   |    |    |    |    |    |    |
   v    v    v    v    v    v    v
Specialist agents process with image context
        |
        v
LLMBackend.chatWithVision() or chat()
        |
        v
Parse response: <macro>, <pipeline>, <analysis>, text
        |
        v
Execute + capture result + feed back
```

### 4.2 VLM-Guided ROI Flow

```
User: "Find all activated microglia"
        |
        v
AgentOrchestrator -> SegmentationAgent (with image)
        |
        v
VLM sees image + understands "activated microglia":
  - Identifies channel likely containing microglia marker (Iba1)
  - Notes morphological criteria for activation (amoeboid shape,
    thickened processes, larger soma)
  - Generates macro code:
    <macro>
    // Select Iba1 channel
    Stack.setChannel(2);
    // Threshold for bright cell bodies (activated morphology)
    setAutoThreshold("Li dark");
    run("Convert to Mask");
    // Size filter: activated microglia are larger than resting
    run("Analyze Particles...", "size=150-5000 circularity=0.3-1.0 add");
    </macro>
        |
        v
CommandEngine executes -> ROIs in ROI Manager
        |
        v
[Optional] VLM sees result image with ROIs:
  "I identified 23 potential activated microglia. The segmentation
   captured the larger, more rounded cell bodies characteristic of
   activated morphology. 3 ROIs in the lower-right may be debris
   rather than cells -- consider manual verification."
```

### 4.3 Report Generation Flow

```
User: "Generate analysis report"
        |
        v
ReportGenerator collects:
  1. AnalysisSession.buildSessionContext()
     -> List of all analyses performed, parameters, key results
  2. StateInspector.buildStateContext()
     -> Current images, ResultsTable, ROIs, memory
  3. ImageCapture.captureActiveImage()
     -> Current image state
  4. ImageCapture.captureMultiChannel() (if multi-channel)
     -> All channels labeled
        |
        v
Build report prompt:
  "Generate a microscopy analysis report. Include:
   - Image quality assessment (from the image shown)
   - Methods summary (from session context)
   - Quantitative results (from the data below)
   - Biological interpretation
   - Recommendations for follow-up

   [SESSION CONTEXT]
   ... analyses performed, parameters used ...
   [/SESSION CONTEXT]

   [RESULTS DATA]
   ... ResultsTable summary statistics ...
   [/RESULTS DATA]"
        |
        v
LLMBackend.chatWithVision() with image + prompt
        |
        v
Format response:
  - Display in chat as HTML
  - Optionally save as .md file alongside the data
```


## 5. Settings Integration

### New Settings Fields

Add to `Settings.java`:
```java
// VLM-specific settings
public boolean alwaysOnVision = true;        // Include image context with every message
public boolean vlmQualityCheck = true;       // Auto-assess new images
public boolean vlmExplorationValidation = false;  // VLM validates exploration results
public int lightVisionMaxSize = 256;         // Thumbnail size for passive context
public int fullVisionMaxSize = 1024;         // Full capture size (existing behavior)
```

Add to `SettingsDialog.java`:
```
[Vision Settings]
  [x] Always include image context when available
  [x] Auto-assess image quality on open
  [ ] VLM validation for parameter exploration
  Light thumbnail size: [256] px
  Full capture size: [1024] px
```

### Settings Per-Model Configuration

The existing `ModelConfig` system supports multiple model profiles. Users can configure:
- A fast/cheap model for light vision context (e.g., Gemini 2.5 Flash-Lite)
- A powerful model for full vision analysis (e.g., Gemini 3 Flash or Qwen3-VL:72B)
- A local model for sensitive data (e.g., Qwen3-VL:8B via Ollama)

Future enhancement: allow per-query model selection based on the VisionMode.


## 6. External VLM Integration via Python

For specialized models not available as APIs or via Ollama (BiomedCLIP, Grounding DINO,
CellViT++, MedSAM), use ImageJAI's existing `CrossToolRunner` or the TCP server:

### Option A: CrossToolRunner (Subprocess)

```java
// In a new VLMBridge class
String script = "python vlm_bridge.py --model biomedclip --image /tmp/capture.png "
              + "--query 'classify this image'";
String result = CrossToolRunner.runPython(script);
```

The Python script handles:
- Loading the specialized model
- Processing the image
- Returning structured JSON results

### Option B: TCP Server (Persistent Python Process)

Run a persistent Python process that:
1. Loads specialized models once (slow startup, fast inference)
2. Listens on a local port (e.g., 7747)
3. Accepts JSON requests with base64 images
4. Returns structured analysis results

ImageJAI connects via `HttpUtil.post()` to localhost:7747.

### Option C: SAMJ Integration (Existing Fiji Plugin)

SAMJ already bridges Java and Python via Appose for SAM models.
ImageJAI can invoke SAMJ programmatically:
```java
// Via macro command
commandEngine.executeMacro("run('SAMJ Annotator', 'model=SAM-2-tiny');");
```

### Recommended Architecture for Python Models

```
ImageJAI (Java, in Fiji JVM)
    |
    +-- Cloud VLMs: Gemini/OpenAI/Claude via LLMBackend (HTTP API)
    |
    +-- Local VLMs: Ollama via OllamaBackend (HTTP localhost:11434)
    |
    +-- Specialized VLMs: Python bridge (HTTP localhost:7747)
    |     |
    |     +-- BiomedCLIP (classification, retrieval)
    |     +-- Grounding DINO (open-vocabulary detection)
    |     +-- CellViT++ (cell segmentation)
    |     +-- MedSAM (prompted segmentation)
    |
    +-- SAM: SAMJ Fiji plugin (via Appose Java<->Python bridge)
```


## 7. Token Cost Management

### Problem
Sending images with every message increases token consumption significantly.
A 1024x1024 image is ~1290 tokens on Gemini. At the free tier (250K TPM), this
limits throughput but is rarely an issue for interactive use.

### Mitigation Strategies

1. **Adaptive resolution**: Use 256px for light context, 1024px only for explicit
   vision requests. A 256px image consumes fewer tokens.

2. **Image caching**: If the image hasn't changed since the last capture, reuse the
   previous base64 string. Track via `ImagePlus.getChanges()` or pixel hash.

3. **Selective inclusion**: Don't include the image in retry/error-correction turns
   (the VLM has already seen it in the previous turn via history).

4. **Model routing**: Use Flash-Lite (cheaper) for light vision, Flash (better) for
   full vision analysis.

5. **Rate limit awareness**: Track API calls in `ConversationLoop`. If approaching
   rate limit, fall back to text-only mode and notify the user.


## 8. Implementation Roadmap

### Phase 1: Quick Wins (1 day)
- MicroscopyVisionPrompt.java (specialized prompts)
- VisionRouter.java (always-on light vision)
- Enhanced StateInspector with ResultsTable summary
- Update Constants.java for model defaults (Gemini 2.5 Flash, Qwen3-VL:8B)

### Phase 2: Core VLM Features (1 week)
- Multi-channel ImageCapture
- AnalysisSession tracking
- VLM parameter suggestion in ExplorationEngine
- VLM quality check in ImageMonitor
- New TCP commands for VLM features

### Phase 3: Report Generation (3-5 days)
- ReportGenerator class
- Report prompt templates
- HTML formatting for chat display
- Optional markdown file export

### Phase 4: Advanced Features (2-4 weeks)
- VisionAnalysisAgent (specialist agent)
- VLM-guided ROI suggestion (coordinate extraction)
- VLM teaching mode enhancement
- Python bridge for specialized models (BiomedCLIP, Grounding DINO)
- SAMJ programmatic integration

### Phase 5: Future / Community-Driven
- Grounding DINO + SAM full pipeline
- Fine-tuned microscopy VLM (train on lab's own data)
- Multi-image comparison analysis
- Longitudinal study tracking (same sample over time)
