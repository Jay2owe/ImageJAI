# ImageJAI User Guide

ImageJAI is a Fiji/ImageJ plugin that opens an AI assistant panel from
Plugins > AI Assistant.

## Install

1. Download `imagej-ai-0.2.0.jar` from the GitHub Actions artifact or a
   GitHub release.
2. Copy the jar into `Fiji.app/plugins/`.
3. Restart Fiji.
4. Open Plugins > AI Assistant.

## First Run

The plugin can work with:

- Google Gemini, using a Gemini API key.
- Ollama, running locally on your computer.
- OpenAI-compatible services, using a provider URL and API key.
- External command-line agents, when an ImageJAI agent workspace is available.

API keys are stored on your own machine under the ImageJAI config directory.
They are not committed to this repository and are not bundled into the jar.

## TCP Server

The TCP server is optional and off by default. Enable it from the ImageJAI
settings panel when you want another program or command-line agent to control
Fiji.

Default address: `localhost:7746`

Common commands include:

- `ping`
- `execute_macro`
- `get_state`
- `capture_image`
- `probe_command`
- `get_dialogs`
- `interact_dialog`
- `get_results_table`

The server is intended for local use. Do not expose the port to a network.

## Output Files

Generated analysis outputs should go into `AI_Exports/` next to the image being
analysed. This keeps measurements and derived images beside the source data.

## Agent Workspace

The jar includes the Java plugin and selected provider configuration files. The
full Python helper workspace is used when launching external command-line
agents. ImageJAI looks for an `agent/` directory containing `ij.py` in these
places:

- The current working directory.
- `~/ImageJAI/agent`.
- Paths near the loaded plugin jar.
- The path set by `IMAGEJAI_AGENT_WORKSPACE`.
- The path set by `-Dimagejai.agent.workspace=...`.

If no workspace is found, the plugin still opens but external CLI launchers are
disabled.
