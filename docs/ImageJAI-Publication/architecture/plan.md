# Architecture Plan

Aggregated bullets for the architecture topic across the four pair-plans.
Source-pair tags: `(req)` requirements, `(arch)` architecture, `(risk)`
risk, `(creative)` creative, `(joint)` multi-pair.

Verified ground-truth (re-confirmed 2026-05-08 by the architecture pair):

- `TCPCommandServer.java` is **7125 LOC** with **53 commands** dispatched
  at lines `1109-1216` (NOT "~40 commands, ~4300 LOC" as in `CLAUDE.md`).
- `agent/recipes/` has **27** YAML recipes + 1 README (synthesis said 26).
- `agent/references/` has **61** markdown reference docs (synthesis said 64).
- `src/main/java/imagejai/engine/safeMode/` ships 7 Java files.
- ImageJAI menu path is `Plugins>AI Assistant`
  (`src/main/java/imagejai/ImageJAIPlugin.java:34`); `fiji-llm` ships at
  `Help > Assistants > Fiji Chat...` -> no install-path collision.

## Phase A - code corrections (today)

- [ ] Correct stale numbers in publication `overview.md`: `~40 commands,
  4300 LOC` -> `53 commands, 7125 LOC`. Source: `TCPCommandServer.java:1109-1216`.
  Effort: S. (arch)
- [ ] Correct stale numbers in project `CLAUDE.md`. Same edit, same source.
  Effort: S. (arch)
- [ ] Correct `agent/CLAUDE.md` recipe and reference counts to `27 recipes`
  and `61 references`. Source: `ls agent/recipes/`, `ls agent/references/`.
  Effort: S. (arch)
- [ ] Add a `status_labels.md` at the publication root listing the 5 status
  labels and the 8 unresolved decisions from `synthesis.md:77`. Effort: S.
  (arch)
- [ ] Replace `~40` everywhere it appears under `docs/imagejai-publication/`.
  Effort: S. (arch)
- [x] Rename GsdDetector → AgentPlannerDetector (D4, 2026-05-09). Status: Implemented.

## Phase B - normative protocol spec

- [ ] **Authoritative TCP command catalog (53 commands grouped by family)**
  -> `architecture/command_catalog.md`. Source: `TCPCommandServer.java:1109-1216`.
  Effort: L. (arch)
- [ ] **53-command TCP protocol spec (normative)** -> `architecture/tcp_protocol.md`.
  Source: `TCPCommandServer.java:603` (socket bind), `:1109-1216` (dispatch).
  Effort: L. (arch)
- [ ] **Capability handshake (`hello`)** -> `architecture/capability_handshake.md`.
  Source: `TCPCommandServer.java:1109` + `handleHello`. Synthesis flagged
  client-asserted `safe_mode` as a security risk - cross-ref [`security/`](../security/).
  Effort: M. (arch, risk)
- [ ] **Architecture overview diagram (Fiji JVM <-> TCP <-> Python clients)**
  -> `architecture/system_overview.md` + figure source. Effort: M. (arch)
- [ ] **Capability negotiation surface** -> `architecture/capability_surface.md`.
  Source: `TCPCommandServer.java:1102-1107` (caps lookup), `handleHello`.
  Effort: S. (arch)
- [ ] **Install-path collision check** -> `architecture/install_path_collision.md`.
  Source: `ImageJAIPlugin.java:34` (`Plugins>AI Assistant`) vs fiji-llm
  `Help > Assistants > Fiji Chat...`. Conclusion: no collision. Effort: S.
  (arch)
- [ ] **Protocol RFC (wire-format spec, public)** -> `architecture/protocol_rfc.md`.
  Anchor: `dispatchCore` switch `TCPCommandServer.java:1113-1216`. Effort: M.
  (creative)
- [ ] **Wire-protocol replay tool spec** -> `architecture/wire_replay.md`.
  Anchor: `session_log.py`, `dispatchCore`. Effort: M. (creative)

## Phase C - subsystem deep-dives

- [ ] **JVM <-> Python boundary** -> `architecture/jvm_python_boundary.md`.
  Source: `TCPCommandServer.java:603`, `agent/ij.py`. Effort: M. (arch)
- [ ] **Engine helper class graph (~60 classes)** ->
  `architecture/engine_class_graph.md`. Source: `ls src/main/java/imagejai/engine/*.java`.
  Effort: M. (arch)
- [ ] **Threading model (EDT, server thread, async jobs)** ->
  `architecture/threading_model.md`. Source: `engine/JobRegistry.java`,
  `TCPCommandServer.java` server loop. Effort: M. (arch)
- [ ] **JVM event-dispatch-thread safety** ->
  `architecture/jvm_event_dispatch_thread_safety.md`. Effort: M. (risk)
- [ ] **Settings + first-run** -> `architecture/settings_and_first_run.md`.
  Source: `imagejai/config/`,
  `docs/local_assistant_COMPLETED/01_settings-and-agent-selector_COMPLETED.md`.
  Effort: S. (arch)
- [ ] **Recipe execution path** -> `architecture/recipe_pipeline.md`. Source:
  `engine/PipelineBuilder.java`, `agent/recipes/*.yaml` (27 files). Effort: M.
  (arch)
- [ ] **Reference retrieval / RAG-on-disk** ->
  `architecture/reference_retrieval.md`. Source: `agent/recipe_search.py`,
  `agent/references/INDEX.md`. Effort: M. (arch)
- [ ] **Auditor + correctness gate** -> `architecture/auditor.md`. Source:
  `agent/auditor.py`. Effort: M. (arch)
- [ ] **Agent workspace contract** -> `architecture/agent_workspace.md`.
  Source: `agent/CLAUDE.md`, `agent/ij.py`, `agent/pixels.py`. Effort: M.
  (arch)
- [ ] **Agent dropdown taxonomy** -> `architecture/agent_dropdown_taxonomy.md`.
  Source: `engine/AgentLauncher.java:62-72` (KNOWN_AGENTS). Effort: S. (arch)
- [ ] **Embedded vs external agent terminal modes** ->
  `architecture/agent_terminal_modes.md`. Source: `engine/AgentLauncher.java:27-32`,
  `engine/EmbeddedAgentSession.java`, `engine/ExternalAgentSession.java`,
  `engine/terminal/embedded/`. Effort: M. (arch)
- [ ] **Native vs Proxy AgentLauncher split** -> add a section to this folder.
  Source: `src/main/java/imagejai/engine/picker/NativeAgentLauncher.java`,
  `ProxyAgentLauncher.java`, `AgentLaunchOrchestrator.java`. Effort: S. (req)
- [ ] **Event bus + async jobs** -> `architecture/event_bus_and_jobs.md`.
  Source: `engine/EventBus.java`, `engine/JobRegistry.java`,
  `TCPCommandServer.java:1177-1184`. Effort: M. (arch, req)

## Phase D - state and provenance

- [ ] **State delta encoding** -> `architecture/state_delta_encoding.md`.
  Source: `engine/StateInspector.java`,
  `docs/tcp_upgrade_COMPLETED/05_state_delta_and_pulse_COMPLETED.md`. Effort: M.
  (arch)
- [ ] **Histogram delta math** -> `architecture/histogram_delta_math.md`.
  Source: `engine/HistogramDelta.java`,
  `docs/tcp_upgrade_COMPLETED/09_histogram_delta_COMPLETED.md`. Effort: S. (arch)
- [ ] **Pulse compaction** -> `architecture/pulse_compaction.md`. Source:
  `engine/PulseBuilder.java`. Effort: S. (arch)
- [ ] **Dedup fast path** -> `architecture/dedup_fast_path.md`. Source:
  `engine/ResponseDedupCache.java`,
  `docs/tcp_upgrade_COMPLETED/11_dedup_response_COMPLETED.md`. Effort: S. (arch)
- [ ] **Dialog introspection (`probe_command`, `list_commands`,
  `interact_dialog`)** -> `architecture/dialog_introspection.md`. Source:
  `TCPCommandServer.java:1151-1158`, `engine/DialogWatcher.java`,
  `engine/PhantomDialogDetector.java`, `agent/probe_plugin.py`. Effort: M.
  (arch)
- [ ] **Macro recorder canonicalization** ->
  `architecture/macro_canonicalization.md`. Source: `engine/RecorderHook.java`,
  `engine/RecorderCapture.java`, `engine/MacroAnalyser.java`,
  `docs/tcp_upgrade_COMPLETED/03_canonical_macro_echo_COMPLETED.md`. Effort: M.
  (arch)
- [ ] **Phantom dialog detection** ->
  `architecture/phantom_dialog_detection.md`. Source:
  `engine/PhantomDialogDetector.java`,
  `docs/tcp_upgrade_COMPLETED/10_phantom_dialog_detector_COMPLETED.md`.
  Effort: S. (arch)
- [ ] **Plugin name validator + 330 update sites scan** ->
  `architecture/plugin_name_validator.md`. Source:
  `engine/PluginNameValidator.java`, `agent/scan_plugins.py`,
  `docs/tcp_upgrade_COMPLETED/04_fuzzy_plugin_registry_COMPLETED.md`. Effort: M.
  (arch, req)
- [ ] **Reactive engine** -> `architecture/reactive_engine.md`. Source:
  `engine/ReactiveEngine.java`, `TCPCommandServer.java:1185-1194`. Effort: M.
  (arch, req)
- [ ] **Friction log loop** -> `architecture/friction_log_loop.md`. Source:
  `engine/FrictionLog.java`, `engine/FrictionLogJournal.java`,
  `engine/PatternDetector.java`, `TCPCommandServer.java:1161-1166`. Effort: M.
  (arch)
- [ ] **Source-image tagging** -> `architecture/source_image_tagging.md`.
  Source: `engine/safeMode/SourceImageTagger.java`,
  `docs/safe_mode_v2/06_results-source-image-column_COMPLETED.md`. Effort: S.
  (arch)
- [ ] **Provenance + ledger model** -> `architecture/provenance_and_ledger.md`.
  Source: `engine/LedgerStore.java`, `engine/ImageGraph.java`,
  `docs/tcp_upgrade_COMPLETED/13_provenance_graph_COMPLETED.md`,
  `14_federated_ledger_COMPLETED.md`. Effort: M. (arch)
- [ ] **Undo / branch model (`rewind`, `branch`, `branch_list`,
  `branch_switch`, `branch_delete`)** -> `architecture/undo_and_branch_model.md`.
  Source: `TCPCommandServer.java:1207-1216`, `engine/UndoStack.java`,
  `engine/SessionUndo.java`,
  `docs/tcp_upgrade_COMPLETED/15_undo_stack_api_COMPLETED.md`. Effort: M.
  (arch)
- [ ] **Intent router pipeline (Tier 1 phrasebook -> Tier 2 fuzzy ->
  built-ins -> menu mining)** -> `architecture/intent_router.md`. Source:
  `engine/IntentRouter.java`, `engine/FuzzyMatcher.java`,
  `engine/MenuCommandRegistry.java`,
  `docs/local_assistant_COMPLETED/03..10_*_COMPLETED.md`. Effort: M. (arch, req)
- [ ] **Multi-provider routing (LiteLLM proxy + ProviderRegistry)** ->
  `architecture/multi_provider_routing.md`. Source:
  `engine/LiteLlmProxyService.java`, `engine/picker/ProviderRegistry.java`,
  `engine/picker/ProviderDiscovery.java`, `engine/picker/ModelsCache.java`.
  Effort: M. (arch, req)
- [ ] **Cost + budget telemetry** -> `architecture/cost_and_budget.md`.
  Source: `engine/CostHeaderListener.java`,
  `engine/budget/BudgetCeilingTracker.java`, `engine/usage/UsageTracker.java`.
  Effort: M. (arch, req)
- [ ] **SafeMode v2 architecture** -> `architecture/safe_mode_v2.md`. Source:
  `engine/safeMode/DestructiveScanner.java`, `RoiAutoBackup.java`,
  `SafeModeIndicator.java`, `SafeModeEventPanel.java`, `SourceImageTagger.java`,
  `docs/safe_mode_v2/00_overview.md`. With routability caveat (cross-ref
  [`security/`](../security/) and [`limitations/`](../limitations/)).
  Effort: M. (arch, risk)
- [ ] **Lessons learned: TCP vs MCP** -> `architecture/lessons_tcp_vs_mcp.md`.
  Effort: M. (arch)
- [ ] **Lessons learned: state-delta vs full state** ->
  `architecture/lessons_state_delta.md`. Source:
  `docs/tcp_upgrade_COMPLETED/05_state_delta_and_pulse_COMPLETED.md`. Effort: S.
  (arch)
- [ ] **Lessons learned: SafeMode batch-bypass debt** ->
  `architecture/lessons_safemode_routing_debt.md`. Source:
  `docs/safe_mode_v2/00_overview.md`. Effort: S. (arch)

## Cross-folder dependencies

- Bench, dogfood, devcontainer, browser-Fiji items in
  [`bench/`](../bench/), [`infrastructure/`](../infrastructure/),
  [`interactive/`](../interactive/) all depend on **arch-jdk-pin** (synthesis
  JDK 21 vs 25 mismatch) being filed in [`infrastructure/`](../infrastructure/).
- All `why-not-X` essays in [`essays/`](../essays/) depend on the
  comparator-feature-column matrix being decided here so that
  [`comparisons/`](../comparisons/) and the essays present a consistent
  feature axis.
- The full ledger schema and event-bus schema declared here unblock the
  failure-corpus and trace-viewer items in [`bench/`](../bench/) and
  [`interactive/`](../interactive/).
