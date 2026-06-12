# Setup Status UI

## Why this stage exists

Jar-only installation must not leave users guessing why launchers are disabled. This stage exposes the sidecar setup state in the existing ImageJAI UI and gives users a clear repair path for missing Python, failed dependency installs, or stale workspaces.

## Prerequisites

- `01_bundle-agent-resources.md` must be completed.
- `02_workspace-bootstrap.md` must be completed.
- `03_python-discovery.md` must be completed.
- `04_venv-dependency-install.md` must be completed.
- `05_launcher-integration.md` must be completed.

## Read first

- `docs/python-sidecar-setup/00_overview.md`
- `AGENTS.md`
- `src/main/java/imagejai/ui/AiRootPanel.java:235-330` - current agent selector refresh path.
- `src/main/java/imagejai/ui/AiRootPanel.java:435-532` - header/model picker construction.
- `src/main/java/imagejai/ui/AiRootPanel.java:795-823` - async launcher refresh failure behavior.
- `src/main/java/imagejai/ui/AiRootPanel.java:869-984` - launch messaging.
- `src/main/java/imagejai/ui/AiRootPanel.java:1287-1305` - settings dialog opening.
- `src/main/java/imagejai/ui/SettingsDialog.java:170-176` - existing Models & Agents and Multi-Provider tabs.
- `src/main/java/imagejai/ui/SettingsDialog.java:651-677` - open dialog to provider.
- `src/main/java/imagejai/ui/installer/MultiProviderPanel.java:134-180` and `MultiProviderPanel.java:305-352` - existing installer pattern.
- `src/main/java/imagejai/install/PythonEnvironmentManager.java` - created in stages 03/04.

## Scope

- Add a sidecar setup/status surface to Settings.
- Show concise setup state in the main panel when Python setup is incomplete.
- Add a repair/retry action that runs extraction/venv/dependency setup in the background.
- Ensure launch failures point to the setup surface.
- Keep UI text practical and non-technical where possible.

## Out of scope

- Do not redesign the whole picker or settings dialog.
- Do not add model/provider credential flows beyond linking to existing installer surfaces.
- Do not programmatically install Fiji plugins.
- Do not block Java/TCP usage when Python setup is incomplete.

## Files touched

| path | action | reason |
| --- | --- | --- |
| `src/main/java/imagejai/ui/SettingsDialog.java` | MODIFY | Add or embed Python sidecar setup controls. |
| `src/main/java/imagejai/ui/PythonSidecarPanel.java` | NEW | Display setup status and repair controls, unless folded into an existing installer panel. |
| `src/main/java/imagejai/ui/AiRootPanel.java` | MODIFY | Show status/repair entry points and better launch failure messages. |
| `src/main/java/imagejai/install/PythonEnvironmentManager.java` | MODIFY | Provide background-safe progress/status callbacks if needed. |
| `src/test/java/imagejai/ui/PythonSidecarPanelTest.java` | NEW | Test status rendering and action wiring where feasible. |
| `docs/USER_GUIDE.md` | MODIFY | Document first-run setup behavior. |
| `docs/python-sidecar-setup/06_setup-status-ui.md` | MODIFY | Mark completion notes when done. |

## Implementation sketch

Use the existing settings dialog rather than a separate first-run wizard unless the implementation proves the dialog is too crowded. A tab name such as `Python Setup` is explicit and discoverable.

Panel states:

```text
READY
  Python sidecar ready
  Workspace: <path>
  Python: <path>

NOT_FOUND
  Python 3 was not found
  Button: Choose Python...
  Button: Retry

DEPENDENCIES_MISSING or INSTALL_FAILED
  Python found, helper packages are not ready
  Button: Install / Repair
  Link/button: Show log

WORKSPACE_FAILED
  Could not extract helper workspace
  Button: Retry extraction
```

Avoid long paragraphs in the UI. Put detailed pip output behind a log/details button.

Suggested panel API:

```java
public final class PythonSidecarPanel extends JPanel {
    public PythonSidecarPanel(PythonEnvironmentManager manager) { ... }
    public void refreshAsync() { ... }
    public void repairAsync() { ... }
}
```

The repair action must run off the Swing event dispatch thread:

```java
new SwingWorker<PythonEnvironment, String>() {
    protected PythonEnvironment doInBackground() {
        return manager.ensureReady(workspaceResult);
    }

    protected void done() {
        // update labels/buttons
    }
}.execute();
```

Main panel integration:

- When `agentLauncher == null` because workspace setup failed, replace the old silent empty selector with a message and a Settings link.
- When Python setup is incomplete but Java/TCP works, keep the panel usable and show a small status indicator near the model picker/header.
- On launch failure due to Python setup, append a chat message with the specific action: open Settings > Python Setup.

Documentation update should replace the current "external command-line agents, when an ImageJAI agent workspace is available" wording with the managed sidecar behavior and override notes.

## Exit gate

1. `mvn test -Dtest=PythonSidecarPanelTest test` passes, or if Swing tests are impractical, document why and add focused tests around status formatting/action models.
2. `mvn test` passes for existing UI/settings tests touched by the new tab.
3. Manual: with Python missing, plugin opens and clearly shows how to configure Python.
4. Manual: with Python available but dependencies absent, clicking repair creates/repairs the venv without freezing the UI.
5. Manual: a launch attempt while setup is incomplete produces an actionable message instead of a generic failure.
6. `docs/USER_GUIDE.md` explains first-run Python setup and developer overrides.

## Known risks

- Long dependency installation can make the UI feel broken if progress is not visible. Surface a spinner/progress text and keep logs accessible.
- Too much setup text in the main panel will crowd the working interface. Keep persistent UI compact and put details in Settings.
- Tests for Swing can be brittle in headless CI. Keep business logic in small non-Swing classes where possible.
