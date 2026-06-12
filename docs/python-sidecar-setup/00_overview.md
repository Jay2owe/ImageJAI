# ImageJAI Python Sidecar Setup

## End goal

ImageJAI should be installable by a normal Fiji user as a published plugin, without cloning this repository. The user installs the plugin JAR or follows an ImageJ update site, opens Plugins > AI Assistant, and the plugin either works immediately or guides them through a first-run Python setup. External AI agent launchers, the LiteLLM proxy, and provider agents should use the same managed Python helper workspace instead of requiring a separate checkout.

## Why we're doing this

Today the Java plugin can open from a JAR, but the full agent experience depends on a separate `agent/` directory containing `ij.py`. That makes the plugin feel unfinished for public distribution: a biologist can install the JAR and still end up with disabled launchers or missing Python dependencies. This work turns the Python helper tree into an installed sidecar managed by the plugin, so installation becomes predictable and supportable.

## Architecture overview

The JAR remains the Fiji plugin entry point. It carries a versioned `agent/` workspace archive plus selected provider resources. On startup, Java extracts that workspace into the user's ImageJAI data directory, discovers or configures a Python 3 interpreter, creates a managed virtual environment, installs pinned helper dependencies, and then points all Python-backed launch paths at that managed environment.

```text
Fiji plugin JAR
  -> ImageJAIPlugin startup
  -> AgentWorkspaceManager extracts ~/.imagejai/agent/<version>/
  -> PythonEnvironmentManager validates Python and ~/.imagejai/venv/
  -> AgentLauncher / LiteLlmProxyService / provider launchers use managed paths
```

## Stage map

| NN | name | one-line goal | rough size | depends on |
| --- | --- | --- | --- | --- |
| 01 | `bundle-agent-resources` | Package the distributable Python helper workspace into the plugin build. | 1 day | none |
| 02 | `workspace-bootstrap` | Extract and resolve a versioned managed agent workspace at first run. | 1-2 days | 01 |
| 03 | `python-discovery` | Add a central Python interpreter discovery, validation, and settings model. | 1 day | 02 |
| 04 | `venv-dependency-install` | Create the managed virtual environment and install pinned provider/helper requirements. | 1-2 days | 03 |
| 05 | `launcher-integration` | Route AgentLauncher, provider agents, and LiteLLM proxy through the managed workspace and Python executable. | 1-2 days | 04 |
| 06 | `setup-status-ui` | Surface first-run setup state, repair actions, and actionable errors in the UI. | 1-2 days | 05 |
| 07 | `clean-install-release` | Verify clean Fiji install behavior and update public install/release docs. | 1 day | 06 |

## Known open questions

- Exact persistent location: use the existing ImageJAI config directory if one is already established, otherwise choose a user data path such as `~/.imagejai/`.
- Offline dependency support: decide whether the first public release only supports online `pip install`, or also ships a wheelhouse for locked offline installs.
- Workspace update policy: decide whether each plugin version gets a fresh extracted `agent/<version>/` folder or an in-place managed workspace with migrations.
- Python minimum version: confirm the lowest supported Python 3 version from the pinned provider dependencies before enforcing it in Java.
- Update-site size tolerance: confirm whether bundling the full helper workspace and optional wheels is acceptable for an ImageJ update site, or whether wheels should be a separate download.

## House rules

- Keep JAR-only Java/TCP functionality working even when Python is absent or setup fails.
- Do not silently fall back to a half-configured Python environment. Fail with a clear repair path.
- Keep user overrides: `IMAGEJAI_AGENT_WORKSPACE`, `-Dimagejai.agent.workspace`, and `IMAGEJAI_PYTHON` should remain supported for development and lab installs.
- Do not write analysis outputs outside `AI_Exports/` next to the opened image.
- Never use `Enhance Contrast normalize=true` on data that will be measured.
- Probe unfamiliar Fiji plugins before using them.
- Do not install Fiji plugins programmatically; suggest update-site/plugin installs by name.
- Treat provider credentials and API keys as local secrets. Never bundle them in the JAR or extracted workspace.

## How to run a stage

After the per-stage files are generated, run `/do-step docs/python-sidecar-setup/` to execute the first incomplete stage.
