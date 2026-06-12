# Clean Install Release

## Why this stage exists

The feature is only successful if a clean Fiji install behaves like a publishable plugin, not a development checkout. This stage verifies the end-to-end install path and updates release documentation so users and maintainers know exactly what is bundled, what is downloaded, and how to troubleshoot it.

## Prerequisites

- `01_bundle-agent-resources.md` must be completed.
- `02_workspace-bootstrap.md` must be completed.
- `03_python-discovery.md` must be completed.
- `04_venv-dependency-install.md` must be completed.
- `05_launcher-integration.md` must be completed.
- `06_setup-status-ui.md` must be completed.

## Read first

- `docs/python-sidecar-setup/00_overview.md`
- `AGENTS.md`
- `docs/USER_GUIDE.md`
- `docs/ImageJAI-Publication/release/public_release_checklist.md`
- `pom.xml:13-17` and `pom.xml:211-287` - artifact identity and packaging.
- `src/main/resources/plugins.config:1-3` - classic ImageJ menu registration.
- `src/main/java/imagejai/ImageJAIPlugin.java:40-44` - SciJava command registration.
- `src/main/java/imagejai/config/Constants.java:10-14` - visible plugin version and config directory.

## Scope

- Verify a clean Fiji install can open the plugin from the packaged JAR with no repository checkout.
- Verify managed workspace extraction and Python setup behavior on a clean user config directory.
- Update user docs, release checklist, and troubleshooting notes.
- Add a repeatable smoke-test checklist or script for maintainers.
- Decide and document update-site readiness constraints.

## Out of scope

- Do not publish to an update site in this stage unless the user explicitly asks.
- Do not change public licensing/citation metadata unless release checks show it is missing and directly needed.
- Do not add new AI-provider features.

## Files touched

| path | action | reason |
| --- | --- | --- |
| `docs/USER_GUIDE.md` | MODIFY | Replace old workspace install limitation with managed sidecar instructions. |
| `docs/ImageJAI-Publication/release/public_release_checklist.md` | MODIFY | Add sidecar-specific clean install checks. |
| `docs/python-sidecar-setup/clean_install_smoke.md` | NEW | Step-by-step maintainer smoke test for a clean Fiji install. |
| `scripts/` or `tools/` smoke helper | NEW or MODIFY | Optional helper to inspect JAR contents and sidecar resources. |
| `pom.xml` | MODIFY only if needed | Final metadata/version/resource corrections found during smoke testing. |
| `docs/python-sidecar-setup/07_clean-install-release.md` | MODIFY | Mark completion notes when done. |

## Implementation sketch

Clean install smoke test should be written so a maintainer can run it manually:

```text
1. Build: mvn clean package.
2. Create or download a clean Fiji.app.
3. Copy target/imagej-ai-<version>.jar to Fiji.app/plugins/.
4. Start Fiji with a temporary user home or clear ~/.imagej-ai.
5. Open Plugins > AI Assistant.
6. Confirm no repository checkout is required.
7. Confirm ~/.imagej-ai/agent/<version>/agent/ij.py exists.
8. Confirm Settings > Python Setup shows a meaningful state.
9. If Python is available, run repair/install and confirm READY.
10. Enable TCP server and run `python ij.py ping` from the managed workspace using the managed venv Python.
11. Launch a provider or local Gemma path that is safe for the test environment.
```

Add a JAR inspection command:

```powershell
jar tf target/imagej-ai-*.jar | Select-String "imagejai/agent-workspace"
```

Documentation should clearly distinguish:

- Bundled by the JAR: Java plugin, TCP server, managed Python helper workspace, provider config.
- Created on first run: extracted workspace under `~/.imagej-ai/agent/`, venv under `~/.imagej-ai/python/`.
- Still external/optional: Python installation itself, external CLI tools such as Claude Code/Codex/Aider/Gemini CLI, Ollama daemon/models, provider credentials.

Troubleshooting topics to add:

```text
Python not found
venv creation failed
pip install failed / no internet
managed workspace corrupted
developer override with IMAGEJAI_AGENT_WORKSPACE
developer override with IMAGEJAI_PYTHON
how to reset ~/.imagej-ai/python/venv
```

If update-site publication is planned later, note the package size after `mvn package` and whether the bundled archive is acceptable for update-site distribution.

## Exit gate

1. `mvn clean test package` passes.
2. A clean Fiji install opens Plugins > AI Assistant from only the built JAR.
3. With no repository checkout, the plugin extracts a managed workspace and no longer logs "Agent workspace not found" for the normal bundled path.
4. With no Python installed or configured, the plugin still opens and shows a clear setup state.
5. With Python installed, repair/setup reaches READY and at least `ij.py ping` works against the TCP server.
6. `docs/USER_GUIDE.md` and the public release checklist describe the new install model.
7. `docs/python-sidecar-setup/clean_install_smoke.md` exists and is specific enough for another maintainer to repeat.

## Known risks

- A smoke test on the developer machine can accidentally use local checkout paths. Use a temporary user home/config root or explicitly unset `IMAGEJAI_AGENT_WORKSPACE`.
- Update-site users may expect no network activity. Be explicit that dependency installation may use `pip` unless an offline wheelhouse is added later.
- Version mismatches between `pom.xml`, `Constants.VERSION`, and workspace manifest can confuse upgrades. Include a final check for version consistency.
