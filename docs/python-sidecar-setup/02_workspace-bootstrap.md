# Workspace Bootstrap

## Why this stage exists

Once the JAR carries the helper workspace, the plugin needs a stable runtime location where Python can import and execute it. This stage creates the managed sidecar workspace under the ImageJAI user directory and teaches startup code to resolve that workspace without requiring a repository checkout.

## Prerequisites

- `01_bundle-agent-resources.md` must be completed.

## Read first

- `docs/python-sidecar-setup/00_overview.md`
- `AGENTS.md`
- `src/main/java/imagejai/config/Constants.java:10-14` - plugin version and config directory name.
- `src/main/java/imagejai/config/Settings.java:197-215` and `Settings.java:549-550` - settings load and config directory location.
- `src/main/java/imagejai/ImageJAIPlugin.java:133-142` - startup currently finds an agent workspace before creating `AgentLauncher`.
- `src/main/java/imagejai/ImageJAIPlugin.java:553-615` - current workspace discovery and `ij.py` validation.
- `src/main/java/imagejai/engine/LiteLlmProxyService.java:162-183` - current temporary provider extraction path.
- `src/test/java/imagejai/engine/AgentLauncherEmbeddedFallbackTest.java:18-60` - tests create minimal temp workspaces.

## Scope

- Add a Java runtime component that resolves the effective agent workspace.
- Extract `/imagejai/agent-workspace.zip` to a versioned directory under the ImageJAI config directory.
- Preserve developer overrides through `IMAGEJAI_AGENT_WORKSPACE` and `-Dimagejai.agent.workspace`.
- Validate the managed workspace by checking required files, not only `ij.py`.
- Make extraction idempotent and safe on repeated Fiji starts.

## Out of scope

- Do not detect Python or create a venv; stages 03 and 04 own that.
- Do not rewrite AgentLauncher internals beyond accepting the resolved workspace; stage 05 owns launcher behavior.
- Do not add setup UI; stage 06 owns setup/status surfacing.

## Files touched

| path | action | reason |
| --- | --- | --- |
| `src/main/java/imagejai/install/AgentWorkspaceManager.java` | NEW | Resolve, extract, validate, and report the managed workspace. |
| `src/main/java/imagejai/ImageJAIPlugin.java` | MODIFY | Use the manager instead of raw `findAgentWorkspace()` for normal startup. |
| `src/test/java/imagejai/install/AgentWorkspaceManagerTest.java` | NEW | Cover extraction, idempotency, overrides, and validation failures. |
| `docs/python-sidecar-setup/02_workspace-bootstrap.md` | MODIFY | Mark completion notes when done. |

## Implementation sketch

Create a small class with no Swing dependencies:

```java
package imagejai.install;

public final class AgentWorkspaceManager {
    public static final String WORKSPACE_ARCHIVE = "/imagejai/agent-workspace.zip";
    public static final String WORKSPACE_MANIFEST = "/imagejai/agent-workspace-manifest.json";

    public static final class Result {
        public final java.nio.file.Path workspace;
        public final boolean managed;
        public final boolean extracted;
        public final String version;
        public final String warning;
        public final String error;

        public boolean usable() {
            return workspace != null && error == null;
        }
    }

    public Result resolve() {
        // 1. Check system property imagejai.agent.workspace.
        // 2. Check IMAGEJAI_AGENT_WORKSPACE.
        // 3. Check existing dev candidates if needed.
        // 4. Extract bundled archive to managed location.
    }
}
```

Managed location should be derived from `Settings.getConfigDir()`:

```text
~/.imagej-ai/agent/<workspace_version>/agent/
~/.imagej-ai/agent/current.json
```

Validation should require:

```text
ij.py
providers/agent_cli.py
providers/requirements.txt
gemma4_31b/__main__.py
CLAUDE.md
```

Extraction rules:

- Extract to a temp sibling directory first, then atomically move or replace the final version directory.
- Never delete user override workspaces.
- If the final managed workspace already validates and manifest version matches, do not re-extract.
- Write a small marker file such as `.imagejai-managed-workspace.json` inside the extracted workspace.
- Prevent zip-slip by normalizing every zip entry and rejecting entries that escape the target directory.

`ImageJAIPlugin` should eventually do:

```java
AgentWorkspaceManager.Result workspace = AgentWorkspaceManager.resolveDefault();
String agentWorkspace = workspace.usable() ? workspace.workspace.toString() : null;
startLiteLlmProxy(agentWorkspace, settings);
if (agentWorkspace != null) {
    rootPanel.setAgentLauncher(new AgentLauncher(agentWorkspace, settings.tcpPort, settings));
}
```

Keep the existing private `findAgentWorkspace()` during this stage if tests still reference it, but route production startup through the new manager.

## Exit gate

1. `mvn test -Dtest=AgentWorkspaceManagerTest test` passes.
2. Tests prove a bundled archive extracts to a temp config root and validates required files.
3. Tests prove a valid override path is used instead of the managed workspace.
4. Tests prove a malicious `../` zip entry is rejected.
5. Starting the plugin from a development checkout still wires `AgentLauncher`.
6. Starting with no external `agent/` checkout still produces a usable managed workspace path.

## Known risks

- Extracting over a workspace that Python is currently using can cause import failures. Use versioned directories and avoid mutating an active version in place.
- `Constants.VERSION` may drift from `pom.xml`. If the manager uses version strings, make the source of truth explicit or test the values.
- Antivirus or Dropbox-like sync tools may lock files under the user directory. Extraction should fail cleanly and leave the previous valid workspace intact.
