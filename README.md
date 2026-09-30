# ImageJAI — AI Assistant for ImageJ/Fiji

ImageJAI is an ImageJ/Fiji plugin for describing image analysis in plain language. Use the assistant inside Fiji or its standalone console to inspect images, run analyses, interact with plugin dialogs and save reusable macros. Choose a cloud model, a supported agent subscription or a local model through Ollama.

Version **0.6.1** is available from the [ImageJ-AI update site](https://sites.imagej.net/ImageJ-AI/). Console setup installs Python privately when needed.

## Data Governance

ImageJAI includes a per-folder Privacy Posture for labs using external agent CLIs. First use starts in Standard; supervisors can set sensitive folders to Pseudonymised or On-premises. Saved folder choices are retained. On-premises filters the agent list to local binaries and refuses cloud-hosted Ollama tags.

Pseudonymised mode masks supported identifiers in outbound responses and restricts GUI screenshots. On-premises mode restricts supported launch routes to local models. These controls and their limits are described in the data-governance guide; choose a model provider appropriate for the data you are working with.

The audit trail is written to `AI_Exports/imagejai_audit.csv`, and the launcher can generate a Data Handling Statement PDF for ethics or grant paperwork. See [`docs/data-governance/README.md`](docs/data-governance/README.md) for the biologist-facing workflow and limits.

## Features

- **Natural language control** — "Open the blobs image", "Apply a Gaussian blur with sigma 2", "Count all cells"
- **Standalone console** — Choose the model and reasoning effort with `/model`; work with streamed replies, readable tool summaries, expandable details and collapsible side panels
- **Automatic Fiji connection** — Detect or select a Fiji installation, launch it when needed and start its TCP server from the console
- **Reusable work** — Save session macros, resume conversations and inspect analysis history
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
- **TCP command server** — Optional TCP server (port 7746) for external agent access (CLI agents and scripts). Off by default.
- **Plugin argument discovery** — Probe any plugin's dialog to learn exact macro syntax before using it
- **Dialog interaction** — Read, fill, and click any open dialog (buttons, checkboxes, dropdowns, text fields, sliders)
- **Progress monitoring** — Track progress bar state and status line from external agents
- **Groovy/Jython scripting** — Run scripts inside Fiji's JVM for full Java API access

## Install

1. In Fiji, open **Help > Update > Manage update sites**.
2. Click **Add update site**, name it **ImageJ-AI**, and enter
   `https://sites.imagej.net/ImageJ-AI/` as its URL. Enable its checkbox.
3. Close the sites window, click **Apply changes**, and restart Fiji.
4. Open **Plugins > AI Assistant**.
5. For the standalone console, open **Settings > Models & Agents**, find
   **ImageJAI Console**, and click **Install**. Python and its packages are
   installed privately when needed; you do not need to install Python yourself.
6. Launch the console, choose a model, and sign in to its provider or connect
   to a local model server.

The [ImageJ-AI update site](https://sites.imagej.net/ImageJ-AI/) serves version
0.6.1. The site can be added by URL before it appears in Fiji's default list.
The Fiji panel also supports Gemini, Ollama, and OpenAI-compatible backends.
For a direct install, copy `imagej-ai-0.6.1.jar` into `Fiji.app/plugins/` while Fiji
is closed, restart it, and follow steps 4–6.

## Requirements

- Fiji (ImageJ2) running Java 8 or newer
- A model connection: a supported provider API key or agent subscription, or a local model server. Provider access and usage charges are separate from ImageJAI.
- The Fiji panel runs from the plugin JAR. The optional standalone console
  downloads Python and its packages into a private user folder if needed.

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

## ImageJAI Console — the standalone terminal

Prefer a terminal to the Fiji panel? Run `imagejai` to open the standalone
console. It connects to Fiji over TCP, shows streamed replies and tool activity,
and saves conversations and session macros. The console detects Fiji, lets you
choose another installation and starts Fiji and its server when needed.

Install it from Fiji: open **AI Assistant > Settings > Models & Agents**, find
**ImageJAI Console**, and click **Install**. The plugin creates a private,
versioned Python environment, adds `imagejai` to the current user's PATH, and
checks the command before making it active. Repair, update, launch, and
uninstall are available from the same row. If Python 3.10–3.13 is already
installed, the setup uses it; otherwise it downloads a private Python 3.12.
First-time setup needs internet access but not administrator access. Choosing
or signing in to an AI model is a separate step.

```bash
imagejai            # login on first run, then chat; drives Fiji over TCP
imagejai login codex  # use a ChatGPT/Codex subscription
imagejai login claude # use a Claude subscription
imagejai status       # check installation and Fiji connectivity
```

Inside the console:

| Command | Action |
| --- | --- |
| `/model` | Choose the model and its available reasoning effort levels |
| `/fiji` | Detect or choose the Fiji installation |
| `/settings` | Configure the agent, Fiji startup, privacy, display and budget |
| **Session Macros** | Browse and reuse the current session's macros |
| `/help` | Show the available commands and controls |

Tool calls show short, readable summaries; open a summary to inspect the full
result. Side panels can collapse to give the conversation more space. Standard
privacy posture is the default; saved choices for individual folders are kept.
See the [console guide](docs/console/README.md) for authentication, controls,
sessions and automation. The physical plugin test harness is an optional
developer tool and is installed separately.

## Build from Source

```bash
# Requires Maven and JDK 25; the plugin targets Java 8 bytecode
mvn clean package -Denforcer.skip=true

# Build and deploy to local Fiji
bash build.sh
```

## Versioning

Versions use MAJOR.MINOR.PATCH: breaking changes, compatible features, then fixes.

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

For power users who want to control ImageJ from CLI agents or custom scripts:

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

<!-- BEGIN GENERATED COMMAND SUMMARY -->
ImageJAI 0.6.1 exposes **71 TCP commands** (70 request/response plus 1 live stream). `agent/ij.py` provides convenience helpers for 54; the other 17 are explicitly available through `imagej_command({...})`. See the generated [`docs/COMMAND_API.md`](docs/COMMAND_API.md) or the canonical [`agent/command_manifest.json`](agent/command_manifest.json).
<!-- END GENERATED COMMAND SUMMARY -->

The `run_script` command executes Groovy/Jython/JavaScript code directly inside Fiji's JVM — enabling access to any Java API, Swing component manipulation, and plugin internals that macros can't reach.

The `probe_command` command opens a plugin's dialog, reads every field (numeric, string, checkbox, dropdown with all options), derives macro argument keys, generates example macro syntax, and cancels without executing.

The `interact_dialog` command reads and interacts with any open dialog — list components, click buttons, set checkboxes, fill text fields, select dropdowns, adjust sliders.

The `get_progress` command reads the Fiji progress bar state and status line text — useful for monitoring long-running operations from external agents.

This is completely optional — the plugin works fully without it.

## Agent Directory

The `agent/` directory contains a complete AI agent toolkit for controlling ImageJ via the TCP server:

- **`ij.py`** — Python CLI helper with convenience wrappers plus a documented raw-command escape hatch
- **`pixels.py`** — Python-side pixel analysis (stats, cell detection, line profiles)
- **`scan_plugins.py`** — Discover all installed Fiji commands and update sites
- **`probe_plugin.py`** — Probe plugin dialogs for parameters, cache results, batch-probe
- **`adviser.py`** — Research-only analysis consultant (no TCP needed)
- **`auditor.py`** — Validate measurement results for sanity
- **`practice.py`** — Autonomous self-improvement on sample images
- **`train_agent.py`** — Train the agent on a lab's specific images
- **`recipes/`** — YAML analysis recipes (colocalization, cell counting, CTCF, 3D rendering, etc.)
- **`references/`** — 60 expert reference documents covering microscopy, analysis methods, plugins, statistics, and neuroscience workflows

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

## Citing ImageJAI

If you use ImageJAI in published work, please cite it. Citation metadata is in [`CITATION.cff`](CITATION.cff) (use GitHub's "Cite this repository" button). A Zenodo DOI will be added here once the first tagged release is archived.

When you use ImageJAI to invoke specific tools, also cite the underlying methods (StarDist, Cellpose, TrackMate, etc.) as you would when using them directly.

## License

ImageJAI 0.6.1 is licensed under the **BSD 3-Clause License** (`BSD-3-Clause`).
See [`LICENSE`](LICENSE) for the full terms. Third-party dependencies retain
their own licences. Earlier versions retain the licence distributed with them.

## Acknowledgements

Developed by Jamie Malcolm in the [Brancaccio Lab](https://www.ukdri.ac.uk/labs/brancaccio-lab) at the [UK Dementia Research Institute](https://ukdri.ac.uk/centres/imperial), Imperial College London.

This work was supported by the UK Dementia Research Institute, which receives its core funding from the UK Medical Research Council, the Alzheimer's Society, and Alzheimer's Research UK.

Built on the [Fiji](https://fiji.sc/) / [ImageJ](https://imagej.net/) ecosystem and on third-party language-model providers (Google Gemini, OpenAI, Ollama). Users running ImageJAI with cloud LLM backends should be aware that image content and prompts are transmitted to the chosen provider; see the provider's privacy policy.

Note: this plugin contains AI components that interact with third-party LLM services. Before institutional or commercial use, confirm your data-handling requirements with your local information-governance contact.
