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
- **TCP command server** — Optional TCP server (port 7746) for external agent access (Claude CLI, AgentConsole, scripts). Off by default.
- **Plugin argument discovery** — Probe any plugin's dialog to learn exact macro syntax before using it
- **Dialog interaction** — Read, fill, and click any open dialog (buttons, checkboxes, dropdowns, text fields, sliders)
- **Progress monitoring** — Track progress bar state and status line from external agents
- **Groovy/Jython scripting** — Run scripts inside Fiji's JVM for full Java API access

## Install

1. Download `imagej-ai-0.2.0.jar`
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

## Versioning

MAJOR.MINOR.PATCH — first digit for new features, second for big refactors/improvements, third for bug fixes.

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
│   ├── CrossToolRunner      Run Python/R externally
│   └── TCPCommandServer     Optional TCP server for external agents (port 7746)
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

## TCP Command Server (Advanced)

For power users who want to control ImageJ from Claude CLI, AgentConsole, or custom scripts:

1. Open Settings > check "Enable TCP command server"
2. Default port: 7746 (configurable)
3. Send JSON commands over TCP:

```bash
# Test connection
echo '{"command": "ping"}' | nc localhost 7746

# Execute a macro
echo '{"command": "execute_macro", "code": "run(\"Blobs (25K)\");"}' | nc localhost 7746

# Get current state
echo '{"command": "get_state"}' | nc localhost 7746

# Capture image as base64 PNG
echo '{"command": "capture_image"}' | nc localhost 7746

# Probe a plugin's parameters
echo '{"command": "probe_command", "plugin": "Gaussian Blur..."}' | nc localhost 7746

# Check progress bar
echo '{"command": "get_progress"}' | nc localhost 7746
```

Available commands: `ping`, `execute_macro`, `get_state`, `get_image_info`, `get_results_table`, `capture_image`, `run_pipeline`, `explore_thresholds`, `get_state_context`, `batch`, `get_log`, `get_histogram`, `get_open_windows`, `get_metadata`, `get_pixels`, `3d_viewer`, `get_dialogs`, `close_dialogs`, `probe_command`, `run_script`, `interact_dialog`, `get_progress`

The `run_script` command executes Groovy/Jython/JavaScript code directly inside Fiji's JVM — enabling access to any Java API, Swing component manipulation, and plugin internals that macros can't reach.

The `probe_command` command opens a plugin's dialog, reads every field (numeric, string, checkbox, dropdown with all options), derives macro argument keys, generates example macro syntax, and cancels without executing.

The `interact_dialog` command reads and interacts with any open dialog — list components, click buttons, set checkboxes, fill text fields, select dropdowns, adjust sliders.

The `get_progress` command reads the Fiji progress bar state and status line text — useful for monitoring long-running operations from external agents.

This is completely optional — the plugin works fully without it.

## Agent Directory

The `agent/` directory contains a complete AI agent toolkit for controlling ImageJ via the TCP server:

- **`ij.py`** — Python CLI helper for all TCP commands (macro, capture, state, script, probe, UI interaction, progress, etc.)
- **`pixels.py`** — Python-side pixel analysis (stats, cell detection, line profiles)
- **`scan_plugins.py`** — Discover all installed Fiji commands and update sites
- **`probe_plugin.py`** — Probe plugin dialogs for parameters, cache results, batch-probe
- **`adviser.py`** — Research-only analysis consultant (no TCP needed)
- **`auditor.py`** — Validate measurement results for sanity
- **`practice.py`** — Autonomous self-improvement on sample images
- **`train_agent.py`** — Train the agent on a lab's specific images
- **`recipes/`** — YAML analysis recipes (colocalization, cell counting, CTCF, 3D rendering, etc.)
- **`references/`** — 50+ expert reference documents covering microscopy, analysis methods, plugins, statistics, and neuroscience workflows

## Context Hook (Claude Code Integration)

When using Claude Code in this project directory, a context hook (`context_hook.py`) automatically injects live Fiji state into the conversation via the TCP server (port 7746).

**Session start (once):**
- Fiji connection status
- Full list of installed Fiji commands/plugins
- Available reference documents (agent/references/)

**Every message (dynamic):**
- Open images — titles, dimensions, bit depth, stack info, calibration, ROI
- Results table — row count and column names
- JVM memory — used/max/free + open image count
- Progress bar — active state, percent complete, status line text
- Open dialogs — errors, warnings, prompts with text and buttons
- IJ Log — last 10 lines

Requires the TCP command server to be enabled in Fiji (Settings > Advanced > "Enable TCP command server"). Gracefully degrades when Fiji is not running.

## License

BSD-2-Clause
