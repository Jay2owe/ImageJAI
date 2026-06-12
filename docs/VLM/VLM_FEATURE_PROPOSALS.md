# VLM Feature Proposals for ImageJAI

Each proposal includes a feasibility rating:
- **HIGH** = Achievable with existing architecture, minimal new code
- **MEDIUM** = Requires moderate new infrastructure but no fundamental changes
- **LOW** = Requires significant new systems or external dependencies

Effort estimates assume a single developer familiar with the codebase.


## Feature 1: Natural Language Image Analysis Queries

### Description
Users ask questions about the current image in plain English. The VLM sees the image and
responds with a description, assessment, or analysis suggestion.

Examples:
- "Describe the morphology of cells in this field of view"
- "What type of microscopy image is this?"
- "Is this image suitable for quantitative analysis?"
- "What channels are visible and what might they represent?"

### Current State
ImageJAI ALREADY has this capability via `ConversationLoop.shouldUseVision()` and
`LLMBackend.chatWithVision()`. When vision keywords are detected ("see", "look", "describe",
"what do you see"), the active image is captured and sent alongside the prompt.

### What's Missing
1. Vision is only triggered by keyword matching -- users may expect it to always work when
   an image is open
2. Only the active slice/channel is captured, not a composite or multi-channel view
3. No structured output format for downstream use (just free text)
4. The vision addendum in `PromptTemplates` is generic, not microscopy-specialized

### Proposed Enhancements

#### 1a. Always-On Vision Context
When an image is open and vision is enabled, automatically include a low-res thumbnail
(256px) with EVERY prompt, not just vision-keyword prompts. This lets the VLM reference
the image even when the user doesn't explicitly ask.

**Implementation**: Modify `ConversationLoop.processUserMessage()` to always capture when
`settings.visionEnabled && IJ.getImage() != null`, regardless of keyword match. Use a
smaller thumbnail (256px) for non-vision queries to reduce token cost.

**Effort**: 1-2 hours
**Feasibility**: HIGH

#### 1b. Multi-Channel Capture
Capture each channel as a separate image and send as a composite grid, or send the
composite view with channel labels annotated.

**Implementation**: Extend `ImageCapture` with a `captureComposite()` method that:
1. Checks if the image is a multi-channel stack
2. Creates a montage of individual channels with LUT labels
3. Encodes as a single PNG

**Effort**: 4-8 hours
**Feasibility**: HIGH

#### 1c. Microscopy-Specialized Vision Prompt
Replace the generic `VISION_ADDENDUM` with a detailed microscopy-aware system prompt that
instructs the VLM to:
- Identify imaging modality and staining type
- Assess signal-to-noise ratio
- Note saturation, artifacts, and quality issues
- Describe cell morphology using standard terminology
- Suggest appropriate ImageJ analysis workflows

**Implementation**: New constant in `PromptTemplates.java`, ~50 lines of prompt text.

**Effort**: 2-4 hours
**Feasibility**: HIGH

#### 1d. Structured Vision Response
Parse VLM's image description into structured data (modality, quality score, structures
detected, suggested workflow) that other agents can consume.

**Implementation**: Add `<analysis>` XML tags to the vision prompt format, parse with regex
(same pattern as `<macro>` extraction).

**Effort**: 4-8 hours
**Feasibility**: MEDIUM


## Feature 2: VLM-Guided ROI Suggestion

### Description
The user asks the VLM to identify regions of interest in natural language:
- "Find all cells that appear activated"
- "Mark the tissue boundary"
- "Highlight areas with high DAPI signal"
- "Select the cells that show co-localization"

The VLM interprets the request and either:
(a) Generates macro code that creates ROIs using ImageJ's built-in tools
(b) Provides coordinates/descriptions that guide a segmentation pipeline
(c) Integrates with SAM/SAMJ for visual prompting

### Approach A: VLM-to-Macro ROI Generation (Easiest)

The VLM analyzes the image and generates ImageJ macro code that:
1. Applies appropriate preprocessing (filter, threshold)
2. Creates ROIs via Analyze Particles or manual selection
3. Adds ROIs to the ROI Manager

This is what ImageJAI's specialist agents (SegmentationAgent, MeasurementAgent) already do.
The VLM adds visual understanding of WHAT to segment.

**Implementation**: Enhanced system prompt for SegmentationAgent that includes the image,
plus new prompt instructions for ROI-specific outputs.

**Effort**: 4-8 hours
**Feasibility**: HIGH

### Approach B: VLM Coordinate Extraction (Moderate)

Ask the VLM to output approximate coordinates of objects it identifies:
```
"I can see approximately 15 cells. The largest cluster is in the upper-left quadrant
centered around (200, 150). Individual bright cells are at approximately:
(100, 80), (350, 200), (400, 300), (180, 450)..."
```

Parse these coordinates and use them as seed points for:
- Wand tool auto-selection
- Watershed seeds
- SAM/SAMJ point prompts

**Implementation**: New response format `<roi_seeds>` with coordinate parsing. Feed seeds
into SAMJ or ImageJ's FindMaxima + Watershed pipeline.

**Effort**: 2-4 days
**Feasibility**: MEDIUM

### Approach C: Grounding DINO + SAM Pipeline (Most Powerful)

Full visual grounding pipeline:
1. User says "find all activated microglia"
2. VLM interprets this as visual search criteria
3. Grounding DINO performs open-vocabulary detection -> bounding boxes
4. SAM refines each box into precise segmentation masks
5. Masks become ImageJ ROIs

**Implementation**: Requires Python environment with Grounding DINO + SAM. Connect via
`CrossToolRunner` or TCP server. SAMJ plugin provides the Fiji-side SAM integration.

**Effort**: 1-2 weeks
**Feasibility**: LOW (complex dependency chain, requires Python + GPU)

### Recommendation
Start with **Approach A** (VLM generates macro code with visual context) as it works
today with no new infrastructure. Plan **Approach B** for v2 as it adds genuine VLM-guided
spatial awareness. Defer **Approach C** until SAMJ is more mature and users request it.


## Feature 3: Conversational Image Analysis

### Description
Multi-turn dialogue where the VLM maintains context across an analysis session:
- User: "What percentage of cells show co-localization?"
- AI: Designs and executes a colocalization pipeline, reports Manders' coefficients
- User: "Is that statistically significant?"
- AI: Runs Costes significance test
- User: "Show me which cells are co-localized"
- AI: Creates overlay highlighting co-localized regions

### Current State
ImageJAI ALREADY supports multi-turn conversation via `ConversationLoop.history` with
configurable `maxHistory` (default 20 messages). Specialist agents handle specific domains.
The `HypothesisAgent` already designs multi-step analysis plans.

### Proposed Enhancements

#### 3a. Analysis Session Memory
Track what analyses have been performed, what results were obtained, and what images are
in play across the conversation.

**Implementation**: New `AnalysisSession` class that records:
- Images opened/created (name, type, dimensions)
- Analyses performed (type, parameters, key results)
- ROIs created/modified
- Results tables generated

Inject this session summary into every prompt alongside `[STATE]` context.

**Effort**: 1-2 days
**Feasibility**: HIGH

#### 3b. Result-Aware Follow-Up
When the user asks a follow-up about results, automatically include the current
ResultsTable data in the prompt so the VLM can reason about the numbers.

**Implementation**: Extend `StateInspector.buildStateContext()` to include ResultsTable
summary (mean, std, min, max, N for each column) when a table is present.

**Effort**: 4-8 hours
**Feasibility**: HIGH

#### 3c. Visual Follow-Up
After executing an analysis, automatically capture the result image and include it in
the next conversation turn so the VLM can visually assess the outcome.

**Implementation**: This is already partially implemented via
`performPostExecutionVerification()`. Make it always-on (not gated behind
`settings.autoScreenshot`) and lighter-weight (don't ask the VLM to evaluate every time,
just include the image for context).

**Effort**: 2-4 hours
**Feasibility**: HIGH


## Feature 4: VLM-Assisted Parameter Tuning

### Description
The VLM examines an image and suggests optimal parameters for analysis:
- "Suggest threshold for this channel"
- "What sigma should I use for Gaussian blur?"
- "What minimum particle size should I set?"

### Approach: VLM + ExplorationEngine

ImageJAI already has `ExplorationEngine` which tries N parameter values, measures results
quantitatively, and recommends the best. The VLM enhancement adds:

1. **Visual pre-assessment**: VLM sees the image and suggests a starting parameter range
2. **Visual validation**: After exploration, VLM sees the results and validates the
   quantitative recommendation
3. **Contextual reasoning**: VLM explains WHY a parameter is appropriate ("The nuclei are
   well-separated so Otsu thresholding should work; Li would be better for the punctate
   staining in channel 2")

### Implementation

#### 4a. Visual Parameter Pre-Assessment
Before running ExplorationEngine, send the image to the VLM with a prompt:
"Based on this image, suggest parameter ranges for [threshold/blur/particle size].
Consider the image modality, noise level, and object characteristics."

Parse the VLM's suggestions and use them to narrow the exploration range.

**Effort**: 4-8 hours
**Feasibility**: HIGH

#### 4b. Visual Exploration Validation
After ExplorationEngine completes, capture side-by-side comparisons of top-3 results,
send to VLM for visual assessment alongside quantitative metrics.

**Effort**: 1-2 days
**Feasibility**: MEDIUM (requires multi-image capture and comparison layout)

#### 4c. Contextual Parameter Explanation
Add domain knowledge to the parameter suggestion prompt so the VLM can explain its
reasoning using microscopy-specific terminology.

**Effort**: 4-8 hours
**Feasibility**: HIGH


## Feature 5: Multi-Modal Analysis Reports

### Description
After an analysis is complete, the VLM generates a narrative report that combines:
- Quantitative results from ImageJ measurements
- Visual assessment of the processed images
- Biological interpretation in context
- Statistical summary with appropriate caveats
- Suggestions for follow-up analyses

### Example Output
```
## Analysis Report: SCN Region DAPI/AT8 Co-localization

### Image Quality Assessment
The input image is a dual-channel confocal z-stack (DAPI/AT8) of mouse SCN tissue.
Signal-to-noise ratio appears adequate in both channels. No significant saturation
detected. Minor tissue fold visible in the upper-right corner (excluded from analysis).

### Quantification Results
- Total DAPI+ nuclei detected: 847
- AT8+ objects: 123 (14.5% of total nuclei)
- Mean AT8 intensity in positive cells: 1,847 +/- 423 AU
- Manders' M1 (DAPI in AT8): 0.31
- Manders' M2 (AT8 in DAPI): 0.67
- Costes significance test: p < 0.001

### Interpretation
The co-localization analysis reveals moderate AT8 signal overlap with nuclear regions
(M2 = 0.67), consistent with nuclear tau accumulation. The Costes test confirms this
is statistically significant. The relatively low M1 (0.31) indicates that most nuclei
do NOT contain AT8, as expected for a specific pathological marker.

### Recommendations
- Consider measuring AT8 intensity per cell to identify subpopulations
- Repeat analysis excluding the tissue fold artifact region
- Compare with age-matched controls using the same threshold parameters
```

### Implementation

#### 5a. Basic Report Generation
After any pipeline completes, offer "Generate analysis report". Collect all results
(ResultsTable, images, parameters used) and send to VLM with a report template prompt.

**Implementation**: New `ReportGenerator` class that:
1. Collects current state (images, results, ROIs, parameters)
2. Captures representative images
3. Builds a report prompt with quantitative data + images
4. Sends to VLM
5. Formats response as HTML in chat or saves as markdown file

**Effort**: 2-3 days
**Feasibility**: HIGH

#### 5b. Publication Figure + Caption
Generate a composite figure with appropriate layout and a VLM-written caption suitable
for a manuscript methods/results section.

**Effort**: 3-5 days
**Feasibility**: MEDIUM (requires sophisticated figure layout logic)


## Feature 6: Smart Image Monitor with VLM

### Description
Extend the existing `ImageMonitor` (which checks saturation, calibration, memory) with
periodic VLM-based visual assessment.

### Proposed Enhancement

When the user opens a new image, automatically send a low-res thumbnail to the VLM with
a quality assessment prompt. The VLM flags:
- Saturation warnings
- Apparent bleedthrough between channels
- Tissue artifacts (folds, tears, bubbles)
- Focus issues
- Unexpected staining patterns

### Implementation
Add a `VLMQualityCheck` method to `ImageMonitor` that:
1. Triggers when a new image is opened (via existing polling loop)
2. Captures a 256px thumbnail
3. Sends to VLM with a brief quality assessment prompt
4. Parses response for warning flags
5. Shows warnings as overlay annotations via `AnnotationHelper`

**Effort**: 1-2 days
**Feasibility**: HIGH (VLM quality is good enough for obvious issues)
**Caveat**: Only trigger once per image, not on every poll cycle (rate limit aware)


## Feature 7: VLM-Powered Teaching Mode

### Description
Extend the existing `RecorderHook` teaching mode. When the user performs manual actions,
the VLM can:
1. See what they did (via recorder) AND what the image looks like (via capture)
2. Understand the biological context of their workflow
3. Suggest improvements ("You used the same threshold for all channels, but channel 2
   has much lower signal -- consider using a different method")
4. Generate a more robust automated version of their manual workflow

### Implementation
After `RecorderHook` detects a pattern (same sequence repeated N times):
1. Capture representative before/after images
2. Send to VLM: "The user performed these steps: [macro list]. Here is the before and
   after. Suggest improvements to this workflow."
3. Display suggestions in chat

**Effort**: 1-2 days
**Feasibility**: MEDIUM (requires coordination between RecorderHook and vision)


## Priority Ranking

| Priority | Feature | Effort | Impact | Feasibility |
|----------|---------|--------|--------|-------------|
| 1 | 1c: Microscopy vision prompt | 2-4 hours | High | HIGH |
| 2 | 1a: Always-on vision context | 1-2 hours | High | HIGH |
| 3 | 3b: Result-aware follow-up | 4-8 hours | High | HIGH |
| 4 | 4a: Visual parameter pre-assessment | 4-8 hours | High | HIGH |
| 5 | 5a: Basic report generation | 2-3 days | Very High | HIGH |
| 6 | 1b: Multi-channel capture | 4-8 hours | Medium | HIGH |
| 7 | 6: Smart image monitor with VLM | 1-2 days | Medium | HIGH |
| 8 | 2a: VLM-to-macro ROI generation | 4-8 hours | Medium | HIGH |
| 9 | 3a: Analysis session memory | 1-2 days | Medium | HIGH |
| 10 | 4b: Visual exploration validation | 1-2 days | Medium | MEDIUM |
| 11 | 7: VLM teaching mode | 1-2 days | Medium | MEDIUM |
| 12 | 2b: VLM coordinate extraction | 2-4 days | Medium | MEDIUM |
| 13 | 5b: Publication figure + caption | 3-5 days | High | MEDIUM |
| 14 | 1d: Structured vision response | 4-8 hours | Low | MEDIUM |
| 15 | 3c: Visual follow-up (always-on) | 2-4 hours | Low | HIGH |
| 16 | 2c: Grounding DINO + SAM | 1-2 weeks | High | LOW |

Items 1-4 can be implemented in a single day and immediately improve the VLM experience.
Item 5 (report generation) is the highest-impact individual feature and warrants 2-3 days
of focused development.
