# Bundle Agent Resources

## Why this stage exists

The publishable plugin cannot depend on a checked-out repository for Python helper files. This stage makes the build produce a JAR that carries the distributable parts of the `agent/` workspace, while preserving the current Java-only plugin behavior. Later stages will extract and execute these resources.

## Prerequisites

None.

## Read first

- `docs/python-sidecar-setup/00_overview.md`
- `AGENTS.md`
- `pom.xml:211-287` - current resources and shade plugin configuration.
- `src/main/java/imagejai/ImageJAIPlugin.java:133-142` - current startup comment that says only selected provider resources are bundled.
- `src/main/java/imagejai/engine/LiteLlmProxyService.java:162-183` - current resource extraction for only `agent/providers`.
- `agent/CLAUDE.md`
- `agent/providers/requirements.txt`
- `docs/USER_GUIDE.md:41-59` - current documentation of the separate workspace requirement.

## Scope

- Define the distributable subset of `agent/`.
- Add a build-time resource bundle for that subset.
- Exclude development-only, generated, private, and cache material.
- Add tests or build checks proving the resource is present in the packaged JAR.
- Keep the existing `agent/providers` resource paths working until later stages migrate consumers.

## Out of scope

- Do not extract the workspace at runtime; stage 02 owns extraction.
- Do not create or install a Python virtual environment; stage 04 owns that.
- Do not modify launcher behavior; stage 05 owns launcher integration.
- Do not add user-facing setup UI; stage 06 owns UI.

## Files touched

| path | action | reason |
| --- | --- | --- |
| `pom.xml` | MODIFY | Package the distributable agent workspace as a JAR resource. |
| `src/main/resources/agent/` or generated equivalent | NEW | Hold the bundled workspace archive or manifest resource. |
| `src/test/java/imagejai/install/AgentResourceBundleTest.java` | NEW | Assert required bundled resources exist and excluded files are absent. |
| `docs/python-sidecar-setup/01_bundle-agent-resources.md` | MODIFY | Mark completion notes when done. |

## Implementation sketch

Prefer a single archive resource over copying the entire `agent/` tree as loose classpath resources. Loose resources make exclusion harder and can collide with the existing `agent/providers` resource block. A good target resource name is:

```text
/imagejai/agent-workspace.zip
/imagejai/agent-workspace-manifest.json
```

Minimum files to include:

```text
agent/ij.py
agent/pixels.py
agent/probe_plugin.py
agent/recipe_search.py
agent/auditor.py
agent/autopsy.py
agent/scan_plugins.py
agent/sync_context.py
agent/AGENTS.md
agent/CLAUDE.md
agent/GEMINI.md
agent/.aider.conventions.md
agent/providers/**
agent/contexts/**
agent/gemma4_31b/**
agent/ollama_agent/**
agent/recipes/**
agent/references/**
agent/macro_sets/**
```

Exclude at least:

```text
agent/__pycache__/**
agent/.tmp/**
agent/work_in_progress/**
agent/**/*.pyc
agent/**/*.pyo
agent/*.stackdump
agent/hs_err_*.log
agent/OpenCL-log.txt
agent/*.bak
agent/*.premerge.bak
agent/*.tmp*
agent/test_*.py
```

The exact Maven mechanism is left to the executor, but two acceptable shapes are:

1. Generate `target/generated-resources/imagejai/agent-workspace.zip` during `generate-resources`, then include `target/generated-resources` as a resource.
2. Use a small checked-in build helper under `tools/` to create the zip, called from Maven.

The archive should include a manifest with at least:

```json
{
  "schema": 1,
  "plugin_version": "0.2.0",
  "workspace_version": "0.2.0",
  "root": "agent",
  "created_by": "maven package",
  "required_files": [
    "agent/ij.py",
    "agent/providers/agent_cli.py",
    "agent/providers/requirements.txt",
    "agent/gemma4_31b/__main__.py"
  ]
}
```

Keep the existing `pom.xml:222-229` resource block for `agent/providers` for this stage unless all current tests prove no existing classpath lookup depends on it.

Suggested JUnit check:

```java
@Test
public void bundledAgentWorkspaceArchiveExists() throws Exception {
    assertNotNull(getClass().getResourceAsStream("/imagejai/agent-workspace.zip"));
    assertNotNull(getClass().getResourceAsStream("/imagejai/agent-workspace-manifest.json"));
}
```

If the test opens the zip, assert that `agent/ij.py` exists and `agent/work_in_progress/` does not.

## Exit gate

1. `mvn test -Dtest=AgentResourceBundleTest test` passes.
2. `mvn package` produces a JAR containing `/imagejai/agent-workspace.zip` and `/imagejai/agent-workspace-manifest.json`.
3. Inspecting the JAR confirms `agent/ij.py`, `agent/providers/agent_cli.py`, and `agent/providers/requirements.txt` are present inside the archive.
4. Inspecting the JAR confirms `agent/work_in_progress`, `__pycache__`, stack dumps, and local error logs are absent.
5. Existing `LiteLlmProxyServiceTest` still passes.

## Known risks

- The archive may accidentally include lab-private material. Run explicit exclusion checks and search the final archive for stack dumps, logs, cache directories, and absolute Dropbox paths.
- The JAR may become too large for convenient update-site distribution. If so, keep the helper workspace in the JAR but defer wheels/offline dependencies to a later optional download.
- Maven resource generation can be brittle on Windows paths. Prefer Java/Maven-native path handling or a small cross-platform Java/Python helper invoked predictably by Maven.
