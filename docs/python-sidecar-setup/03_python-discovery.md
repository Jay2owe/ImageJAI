# Python Discovery

## Why this stage exists

The managed workspace only helps if Java can launch a compatible Python interpreter consistently. Today Python command selection is duplicated in `AgentLauncher`, `LiteLlmProxyService`, and `ProviderAgentLaunch`. This stage centralizes interpreter discovery and records enough setup state for later venv installation and UI repair.

## Prerequisites

- `01_bundle-agent-resources.md` must be completed.
- `02_workspace-bootstrap.md` must be completed.

## Read first

- `docs/python-sidecar-setup/00_overview.md`
- `AGENTS.md`
- `src/main/java/imagejai/engine/AgentLauncher.java:469-476` - current static Python command.
- `src/main/java/imagejai/engine/LiteLlmProxyService.java:363-370` - current proxy Python command.
- `src/main/java/imagejai/engine/picker/ProviderAgentLaunch.java:114-121` - current provider-agent Python command.
- `src/main/java/imagejai/config/Settings.java:19-190` and `Settings.java:449-478` - settings fields and save path.
- `src/main/java/imagejai/engine/CrossToolRunner.java:77-90` and `CrossToolRunner.java:164-196` - existing Python lookup helper for script execution.
- `src/test/java/imagejai/install/ProcessRunnerTest.java`
- `src/main/java/imagejai/install/ProcessRunner.java`

## Scope

- Add a central Python discovery/validation class.
- Support existing `IMAGEJAI_PYTHON` override.
- Probe common commands without blocking Fiji startup for long.
- Validate Python version and basic capabilities.
- Add settings fields or a small state file for discovered interpreter path and validation status.

## Out of scope

- Do not install Python itself.
- Do not create the virtual environment; stage 04 owns venv creation.
- Do not wire all launchers to the discovered interpreter yet; stage 05 owns final integration.
- Do not add UI controls; stage 06 owns UI.

## Files touched

| path | action | reason |
| --- | --- | --- |
| `src/main/java/imagejai/install/PythonEnvironment.java` | NEW | Immutable description of interpreter, version, venv, and status. |
| `src/main/java/imagejai/install/PythonEnvironmentManager.java` | NEW | Discover and validate Python. |
| `src/main/java/imagejai/config/Settings.java` | MODIFY | Persist optional Python path override/status if needed. |
| `src/main/java/imagejai/engine/CrossToolRunner.java` | MODIFY | Reuse or delegate existing Python lookup to avoid divergent behavior. |
| `src/test/java/imagejai/install/PythonEnvironmentManagerTest.java` | NEW | Cover command probing, overrides, version parsing, and failures. |
| `docs/python-sidecar-setup/03_python-discovery.md` | MODIFY | Mark completion notes when done. |

## Implementation sketch

Central model:

```java
package imagejai.install;

public final class PythonEnvironment {
    public enum Status {
        READY,
        NOT_FOUND,
        TOO_OLD,
        PROBE_FAILED
    }

    public final Status status;
    public final String executable;
    public final String version;
    public final String message;

    public boolean ready() {
        return status == Status.READY;
    }
}
```

Manager shape:

```java
public final class PythonEnvironmentManager {
    public PythonEnvironment discover() {
        // 1. IMAGEJAI_PYTHON, if set.
        // 2. Settings override, if added.
        // 3. Windows: python, py -3, python3.
        // 4. macOS/Linux: python3, python.
        // 5. Existing CrossToolRunner locations if useful.
    }

    PythonEnvironment probe(java.util.List<String> command) {
        // Run: <python> -c "import sys; print(sys.version_info[:3])"
        // Use ProcessRunner or ProcessBuilder with timeout.
    }
}
```

Minimum supported version should be confirmed from `agent/providers/requirements.txt` and provider SDK metadata during implementation. Until confirmed, the proposed gate is Python 3.10+ because the pinned modern provider SDKs may drop older versions. If the executor verifies Python 3.9 is still supported, document that decision in this file before completing the stage.

Settings options:

```java
public String pythonExecutable = "";
public String pythonLastStatus = "";
public String pythonLastVersion = "";
```

Do not store the venv Python path here yet unless stage 04 needs it. Keep user override semantics:

- `IMAGEJAI_PYTHON` wins over saved settings.
- Saved settings win over automatic discovery.
- Automatic discovery is used only when neither override exists.

## Exit gate

1. `mvn test -Dtest=PythonEnvironmentManagerTest test` passes.
2. Tests cover `IMAGEJAI_PYTHON` winning over discovery.
3. Tests cover version parsing for valid Python 3 output.
4. Tests cover missing command and too-old version as non-throwing statuses.
5. No production code path still contains its own new Python discovery logic; duplicated methods should either delegate or remain untouched only until stage 05.

## Known risks

- Windows `py -3` is a compound command and cannot be passed as one executable string. Use argument lists, not shell-joined strings.
- The Microsoft Store Python launcher can exist but fail interactively. The probe must reject non-working commands.
- Running discovery synchronously on the Swing event dispatch thread would freeze the UI. Keep discovery cheap here and use background work in stage 06 for UI status.
