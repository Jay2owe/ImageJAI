# Code Review: ImageJAI Swing UI & Agent Launcher
## Severity: BUG(4) | ISSUE(5) | INEFFICIENCY(3) | NIT(3)

## BUGS

**[BUG]** AgentLauncher.java:318 — Process.waitFor() blocks EDT during detectAgents()
  - findExecutable() calls proc.waitFor() on the EDT without background thread. Freezes UI during agent detection.
  - Move detection to background thread or use ProcessBuilder.onExit() with timeouts.

**[BUG]** AgentLauncher.java:391 — Process handle leak in commandExists()
  - commandExists() spawns a process and calls waitFor(), but never calls .destroy() on exception or timeout.
  - Wrap Process in try-finally: p.destroy() in finally, use waitFor(timeout, TimeUnit.SECONDS).

**[BUG]** AgentLauncher.java:424 — Process handle leak in syncContextFiles()
  - syncContextFiles() launches python but the Process handle is not stored, cannot be destroyed if Fiji closes during sync.
  - Store the Process in a field, provide cleanup method calling .destroy(), register JVM shutdown hook.

**[BUG]** TierSafetyPanel.java:110 — Budget ceiling is UI-only, never enforced at launch
  - TierSafetyPanel persists budgetCeilingEnabled and budgetCeilingUsd, but AgentLaunchOrchestrator never reads these values.
  - Pass budget values to launch site, check before dispatch, abort if ceiling exceeded.

## ISSUES

**[ISSUE]** AgentLauncher.java:182 — Unquoted workspace path in Windows cmd injection vulnerability
  - Line 182: cd /d "workspace" && fullCommand is vulnerable if workspace contains special shell metacharacters.
  - Always shell-escape agentWorkspace before embedding or use ProcessBuilder array arguments.

**[ISSUE]** ModelPickerButton.java:403 — Uncaught race condition in settings.save() on EDT
  - Line 403 calls settings.save() on EDT after model selection. File I/O is not synchronized, risking corruption.
  - Wrap in background task or use ReentrantReadWriteLock with atomic rename.

**[ISSUE]** AiRootPanel.java:259-270 — Dropdown ActionListener not removed when agentSelector is replaced
  - agentSelector ActionListener not cleaned up if panel disposed or combo box replaced, causing memory leaks.
  - Override removeNotify() to call agentSelector.removeActionListener() for all registered listeners.

**[ISSUE]** TerminalToolbar.java:106 — Timer not stopped if session is destroyed abruptly
  - urlTimer created with setRepeats(false) but only stopped in hideCopyUrl(). May run after toolbar disposed.
  - Call urlTimer.stop() in removeNotify() override; ensure action handler is null-safe.

**[ISSUE]** Settings.java:283-293 — Unsynchronized concurrent save() calls can corrupt config.json
  - Multiple threads can call settings.save() simultaneously without synchronization. Concurrent writes corrupt the file.
  - Synchronize save() method or use single-threaded ExecutorService; use atomic rename.

## INEFFICIENCIES

**[INEFFICIENCY]** AgentLauncher.java:315-327 — Process detection runs synchronously on EDT for every agent during startup
  - detectAgents() spawns a which process for each of 9 agents on EDT, blocking the EDT for seconds on slow systems.
  - Lazy-load or cache agents; run detection in background SwingWorker thread.

**[INEFFICIENCY]** MultiProviderPanel.java:150+ — Static META map hardcodes provider URLs without config override
  - Lines 42-104 define provider metadata in static final Map that cannot be customized. Plugin recompilation needed for URL changes.
  - Move META to external YAML/JSON resource file loaded at runtime.

**[INEFFICIENCY]** AiRootPanel.java:197-210 — Shutdown spawns a daemon thread just to avoid EDT blocking
  - Lines 199-209 spawn a daemon thread only if already on EDT. Clumsy; better to use SwingWorker with timeout.
  - Use SwingWorker with done() callback for off-EDT shutdown.

## NITS

**[NIT]** AgentLauncher.java:65-75 — Hardcoded agent command strings cannot be overridden
  - KNOWN_AGENTS has hardcoded command names ("claude", "aider"). If user has alias or wrapper, detector cannot find it.
  - Add user-configurable agent registry in settings.json and merge with KNOWN_AGENTS.

**[NIT]** AgentLauncher.java:87 — tcpPort parameter never validated against port < 0 or > 65535
  - Constructor accepts tcpPort without validation. Invalid port is set to env var, agent silently fails to bind.
  - Add validation check: if (tcpPort < 1 || tcpPort > 65535) throw new IllegalArgumentException(...).

**[NIT]** InstallerPanel.java:50-52 — GSD settings mixed with agent installer commands
  - Lines 50-52 persist GSD (Get Shit Done) settings alongside agent install commands. GSD is optional and unrelated.
  - Create separate GsdSettings class or move GSD fields to sub-section in JSON.

## SUMMARY

Critical Process Leaks: Three unbounded Process spawns in AgentLauncher can leave zombies and freeze the EDT.

Budget Ceiling Not Enforced: TierSafetyPanel displays the setting but it's never read at launch time.

Concurrent Settings Writes: No synchronization on Settings.save() risks config.json corruption.

Listener Leaks: ActionListeners and Timers not cleaned up in removeNotify() overrides.

## Top 3 Issues

1. Process leaks during agent detection — detectAgents() and commandExists() block EDT and leak processes.
2. Budget ceiling enforcement missing — TierSafetyPanel UI never prevents agent launch if ceiling exceeded.
3. Concurrent settings corruption — No synchronization on Settings.save() allows file corruption.
