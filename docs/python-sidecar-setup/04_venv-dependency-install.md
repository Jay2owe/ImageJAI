# Venv Dependency Install

## Why this stage exists

The bundled workspace still depends on Python packages such as LiteLLM and provider SDKs. This stage creates and maintains a managed virtual environment so the plugin has a predictable Python runtime without asking users to hand-run `pip install`.

## Prerequisites

- `01_bundle-agent-resources.md` must be completed.
- `02_workspace-bootstrap.md` must be completed.
- `03_python-discovery.md` must be completed.

## Read first

- `docs/python-sidecar-setup/00_overview.md`
- `AGENTS.md`
- `agent/providers/requirements.txt`
- `agent/providers/proxy.py:36-43` and `proxy.py:432` - current missing dependency messages.
- `src/main/java/imagejai/install/PythonEnvironmentManager.java` - created in stage 03.
- `src/main/java/imagejai/install/ProcessRunner.java`
- `src/test/java/imagejai/install/ProcessRunnerTest.java`
- `src/main/java/imagejai/engine/LiteLlmProxyService.java:135-183` - proxy startup will later consume the venv Python.

## Scope

- Create a managed venv under the ImageJAI config directory.
- Install pinned requirements from the managed workspace.
- Record installation status and requirement hash.
- Make repeated startup checks fast and idempotent.
- Provide a non-Swing API that stage 06 can call from a background worker.

## Out of scope

- Do not build a fully offline wheelhouse unless explicitly chosen while implementing this stage; if deferred, document it.
- Do not launch provider agents through the venv yet; stage 05 owns launcher integration.
- Do not design UI; stage 06 owns status and repair controls.

## Files touched

| path | action | reason |
| --- | --- | --- |
| `src/main/java/imagejai/install/PythonEnvironmentManager.java` | MODIFY | Add venv creation and dependency install operations. |
| `src/main/java/imagejai/install/PythonEnvironment.java` | MODIFY | Represent venv and dependency status. |
| `src/main/java/imagejai/install/PythonSetupState.java` | NEW | Persist requirement hash, install time, and failure message. |
| `src/test/java/imagejai/install/PythonEnvironmentManagerTest.java` | MODIFY | Cover venv planning and status transitions. |
| `src/test/java/imagejai/install/PythonSetupStateTest.java` | NEW | Cover state read/write if separated. |
| `docs/python-sidecar-setup/04_venv-dependency-install.md` | MODIFY | Mark completion notes when done. |

## Implementation sketch

Managed paths:

```text
~/.imagej-ai/python/venv/
~/.imagej-ai/python/setup-state.json
```

Core API:

```java
public final class PythonEnvironmentManager {
    public PythonEnvironment inspect(AgentWorkspaceManager.Result workspace);

    public PythonEnvironment ensureReady(AgentWorkspaceManager.Result workspace) {
        // discover base Python
        // create venv if missing
        // install requirements if hash changed or imports fail
        // return READY or actionable failure
    }
}
```

Venv commands:

```text
<base-python> -m venv <config-dir>/python/venv
<venv-python> -m pip install --upgrade pip
<venv-python> -m pip install -r <managed-agent>/providers/requirements.txt
```

Compute a SHA-256 hash of `providers/requirements.txt` and store it in setup state after a successful install:

```json
{
  "schema": 1,
  "base_python": "C:/Path/Python/python.exe",
  "venv_python": "C:/Users/.../.imagej-ai/python/venv/Scripts/python.exe",
  "requirements_sha256": "...",
  "status": "READY",
  "last_error": "",
  "updated_at": "2026-06-11T00:00:00Z"
}
```

Validation imports should be cheap and aligned with current requirements:

```text
<venv-python> -c "import litellm, openai, anthropic, google.genai, yaml; print('ok')"
```

If `respx` and `pytest-httpx` remain test-only dependencies, the executor may split runtime and test requirements. Do not remove packages from `agent/providers/requirements.txt` unless tests and the proxy still pass.

Failure behavior:

- Return a structured status; do not throw out of plugin startup for expected missing-Python or pip failures.
- Capture the last 100-200 lines of pip output for the setup UI.
- Do not retry installation on every startup after a failure unless the user requests repair or inputs change.

## Exit gate

1. `mvn test -Dtest=PythonEnvironmentManagerTest,PythonSetupStateTest test` passes.
2. On a machine with Python available, a manual call or test fixture can create a venv and install `agent/providers/requirements.txt`.
3. Running `inspect()` twice after a successful install does not run `pip install` again when the requirements hash is unchanged.
4. Changing the requirements hash causes the manager to mark dependencies stale.
5. Failure to create a venv or install packages returns an actionable status and does not crash plugin startup.

## Known risks

- `pip install` can take long enough to look hung. Stage 06 must run it in the background and show progress.
- Corporate/lab machines may block internet access. Capture pip output clearly and leave room for a future offline wheelhouse.
- Provider SDK versions are pinned. Do not loosen pins in this stage unless a test or install proves the pins are broken.
