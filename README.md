# ImageJAI — AI Assistant for ImageJ/Fiji

A single Fiji plugin that adds a conversational AI assistant to ImageJ. Install by dragging a JAR into `plugins/`. Free to use with Google Gemini or local with Ollama.

## Features

- **Natural language control** — "Open the blobs image", "Apply a Gaussian blur with sigma 2", "Count all cells"
- **Vision reasoning** — AI can see your images and suggest appropriate analyses
- **Multi-step pipelines** — "Analyze all neurons in this confocal stack" generates and executes a complete workflow
- **6 specialist agents** — Segmentation, Measurement, Visualization, Statistics, Hypothesis-Driven Analysis, and General
- **Parameter exploration** — Tries multiple thresholds/parameters, measures quantitatively, recommends the best
- **Teaching mode** — Watches your manual actions, detects patterns, suggests automation
- **Knowledge base** — 90+ indexed macro functions and 30+ tips/recipes including neuroscience workflows
- **Script generator** — Creates installable Groovy/Jython plugins with GUI dialogs on demand
- **Live monitoring** — Warns about saturated pixels, uncalibrated images, memory pressure
- **Hypothesis-driven analysis** — State a scientific hypothesis, AI designs the complete analysis plan
- **Cross-tool integration** — Optional Python/R script execution for advanced statistics

## Install

1. Download `imagej-ai-0.1.0-SNAPSHOT.jar` (196KB)
2. Copy to your `Fiji.app/plugins/` directory
3. Restart Fiji
4. Go to **Plugins > AI Assistant**
5. On first run, choose your AI backend:
   - **Google Gemini** (free) — get a key at [ai.google.dev](https://aistudio.google.com/apikey)
   - **Ollama** (free, local, private) — install from [ollama.ai](https://ollama.ai)
   - **OpenAI / Compatible** — any OpenAI-compatible endpoint
6. Start chatting

## Requirements

- Fiji (ImageJ2) with Java 8+
- An LLM backend (Gemini API key, Ollama, or OpenAI-compatible endpoint)
- No other dependencies — everything else ships with Fiji

## Usage Examples

```
"Open the sample blobs image"
"Apply a Gaussian blur with sigma 3"
"Segment the nuclei using the best threshold method"
"Measure mean fluorescence intensity per cell"
"Make a max projection of this z-stack"
"Add a 50 micron scale bar"
"I hypothesize that AT8 staining is increased in the SCN of CK1-mutant mice"
"Find the best threshold method for this image"
"Save this pipeline as a macro"
"What do you see in this image?"
"Make a publication-quality figure with scale bar"
"Export the results as CSV"
```

## Build from Source

```bash
# Requires Maven 3.6+ and JDK 8+
mvn clean package -q

# Build and deploy to local Fiji
bash build.sh
```

## Architecture

```
ImageJAIPlugin.java          Entry point (Plugins > AI Assistant)
├── ui/                      Swing chat panel + settings dialog
├── llm/                     LLM backends (Gemini, Ollama, OpenAI)
├── engine/                  Macro execution, state inspection, image capture
│   ├── CommandEngine        Execute macros, capture results
│   ├── StateInspector       Query open images, results, ROIs, memory
│   ├── PipelineBuilder      Multi-step workflow orchestration
│   ├── ExplorationEngine    Parameter optimization (try N methods, compare)
│   ├── ImageMonitor         Background monitoring for issues
│   ├── ScriptGenerator      Generate installable Fiji scripts
│   └── CrossToolRunner      Run Python/R externally
├── agents/                  Multi-agent specialist system
│   ├── AgentOrchestrator    Intent classification + routing
│   ├── SegmentationAgent    Thresholding, watershed, StarDist/Cellpose
│   ├── MeasurementAgent     CTCF, colocalization, particle analysis
│   ├── VisualizationAgent   LUTs, projections, montages, figures
│   ├── StatsAgent           Statistical tests, data export
│   └── HypothesisAgent      Hypothesis → analysis plan
├── knowledge/               RAG knowledge base
│   ├── MacroReference       90+ indexed ImageJ macro functions
│   ├── DocIndex             30+ tips, recipes, best practices
│   └── PromptTemplates      System prompts, response parsing
└── config/                  Settings persistence (~/.imagej-ai/)
```

## How It Works

1. You type a message in the chat panel
2. The **AgentOrchestrator** classifies your intent and routes to the right specialist
3. The specialist builds a prompt with your message + current ImageJ state + relevant knowledge
4. The LLM generates a response with `<macro>` or `<pipeline>` blocks
5. The **CommandEngine** executes the macros on ImageJ's EDT thread
6. Results (new images, measurements, errors) are captured and fed back
7. If a macro fails, the AI self-corrects (up to 3 retries)
8. The response and any results are displayed in the chat

## License

BSD-2-Clause
