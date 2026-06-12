# Launcher Integration

## Why this stage exists

The managed workspace and venv are only useful if every Python-backed launch path uses them. Today `AgentLauncher`, `LiteLlmProxyService`, and `ProviderAgentLaunch` each choose Python or workspace paths independently. This stage makes them consume the managed sidecar consistently while preserving developer overrides.

## Prerequisites

- `01_bundle-agent-resources.md` must be completed.
- `02_workspace-bootstrap.md` must be completed.
- `03_python-discovery.md` must be completed.
- `04_venv-dependency-install.md` must be completed.

## Read first

- `docs/python-sidecar-setup/00_overview.md`
- `AGENTS.md`
- `src/main/java/imagejai/ImageJAIPlugin.java:133-142` and `ImageJAIPlugin.java:276-315` - current startup and proxy service accessors.
- `src/main/java/imagejai/engine/AgentLauncher.java:25-108` - known agent definitions.
- `src/main/java/imagejai/engine/AgentLauncher.java:351-476` - workspace getter, command building, Gemma module resolution, Python command.
- `src/main/java/imagejai/engine/AgentLauncher.java:691-705` - context sync subprocess.
- `src/main/java/imagejai/engine/LiteLlmProxyService.java:135-183` and `LiteLlmProxyService.java:363-370` - proxy startup and Python command.
- `src/main/java/imagejai/engine/picker/ProviderAgentLaunch.java:40-121` - provider agent plan, `PYTHONPATH`, and Python command.
- `src/test/java/imagejai/engine/picker/ProviderAgentLaunchTest.java:20-65`
- `src/test/java/imagejai/engine/LiteLlmProxyServiceTest.java`
- `src/test/java/imagejai/engine/AgentLauncherEmbeddedFallbackTest.java`

## Scope

- Pass the managed workspace and managed Python executable through startup.
- Route LiteLLM proxy startup through the managed venv Python.
- Route provider-agent launch plans through the managed venv Python.
- Route bundled Gemma launch through the managed venv Python.
- Preserve explicit override behavior for development and lab installs.

## Out of scope

- Do not add new setup UI; stage 06 owns setup/status UI.
- Do not remove legacy CLI agent detection; users still need external CLI tools for Claude Code, Codex, Aider, etc.
- Do not change provider credential handling.

## Files touched

| path | action | reason |
| --- | --- | --- |
| `src/main/java/imagejai/ImageJAIPlugin.java` | MODIFY | Construct and pass sidecar environment results during startup. |
| `src/main/java/imagejai/engine/AgentLauncher.java` | MODIFY | Use injected Python executable and managed workspace. |
| `src/main/java/imagejai/engine/LiteLlmProxyService.java` | MODIFY | Use managed workspace/providers and venv Python instead of temp-only extraction. |
| `src/main/java/imagejai/engine/picker/ProviderAgentLaunch.java` | MODIFY | Build provider launch plans with managed Python. |
| `src/test/java/imagejai/engine/picker/ProviderAgentLaunchTest.java` | MODIFY | Assert managed Python and PYTHONPATH behavior. |
| `src/test/java/imagejai/engine/AgentLauncher*Test.java` | MODIFY | Update constructors/tests for injected Python where needed. |
| `src/test/java/imagejai/engine/LiteLlmProxyServiceTest.java` | MODIFY | Add sidecar path/python behavior tests if practical. |
| `docs/python-sidecar-setup/05_launcher-integration.md` | MODIFY | Mark completion notes when done. |

## Implementation sketch

Introduce a small value object if stage 03/04 did not already:

```java
public final class PythonSidecar {
    public final Path agentWorkspace;
    public final String pythonExecutable;
    public final boolean ready;
    public final String statusMessage;
}
```

`AgentLauncher` should accept the Python executable explicitly:

```java
public AgentLauncher(String agentWorkspace, int tcpPort, Settings settings,
                     String pythonExecutable) {
    this.agentWorkspace = agentWorkspace;
    this.tcpPort = tcpPort;
    this.settings = settings == null ? Settings.load() : settings;
    this.pythonExecutable = pythonExecutable;
}
```

Keep old constructors delegating to current behavior for tests and compatibility:

```java
public AgentLauncher(String agentWorkspace, int tcpPort, Settings settings) {
    this(agentWorkspace, tcpPort, settings, PythonEnvironmentManager.defaultPythonCommand());
}
```

Replace static `pythonCommand()` calls in `AgentLauncher` with the injected value, especially for:

```java
python -m gemma4_31b
python sync_context.py
```

`ProviderAgentLaunch.plan(...)` currently has no Python parameter. Add an overload rather than breaking tests abruptly:

```java
static Plan plan(String workspace, ModelEntry entry, Map<String, String> extraEnv,
                 String pythonExecutable)
```

The provider command remains:

```text
<venv-python> -m agent.providers.agent_cli --provider <provider> --model <model>
```

The `PYTHONPATH` should still contain the parent of the `agent/` workspace so `agent.providers.agent_cli` resolves. For managed workspace `~/.imagej-ai/agent/0.2.0/agent`, the parent is `~/.imagej-ai/agent/0.2.0`.

`LiteLlmProxyService` should accept both workspace and Python:

```java
public LiteLlmProxyService(String agentWorkspace, String pythonExecutable)
```

It should prefer `<agentWorkspace>/providers` when valid. The old temp extraction fallback may remain only for degraded mode; after stage 02 it should rarely be used.

Startup order in `ImageJAIPlugin`:

```java
AgentWorkspaceManager.Result workspace = AgentWorkspaceManager.resolveDefault();
PythonEnvironment env = PythonEnvironmentManager.inspectOrEnsure(workspace);

String agentWorkspace = workspace.usable() ? workspace.workspace.toString() : null;
String python = env.ready() ? env.venvPythonOrExecutable() : env.executable;

startLiteLlmProxy(agentWorkspace, settings, python);
if (agentWorkspace != null) {
    rootPanel.setAgentLauncher(new AgentLauncher(agentWorkspace, settings.tcpPort, settings, python));
}
```

If Python is not ready, Java/TCP/plugin UI should still start. Python-backed launches should fail with a clear message rather than a `ProcessBuilder` exception.

## Exit gate

1. `mvn test -Dtest=ProviderAgentLaunchTest,AgentLauncherEmbeddedFallbackTest,LiteLlmProxyServiceTest test` passes.
2. Existing external CLI detection still works for commands on `PATH`.
3. Provider agent plan uses the managed Python executable when supplied.
4. Bundled Gemma command uses the managed Python executable when supplied.
5. LiteLLM proxy uses the managed Python executable and managed providers directory when available.
6. With no Python ready, plugin startup does not crash and launch attempts produce actionable messages.

## Known risks

- Shell quoting is fragile on Windows because external terminal launches build command strings. Keep existing command-list boundaries where possible and add tests for paths with spaces.
- The embedded terminal and external terminal paths may diverge. Verify both paths receive the same environment.
- Developer overrides must remain useful. `IMAGEJAI_AGENT_WORKSPACE` and `IMAGEJAI_PYTHON` should still let a developer run from a checkout and a custom venv.
