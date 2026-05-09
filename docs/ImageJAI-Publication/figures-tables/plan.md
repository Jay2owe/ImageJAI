# Figures-and-Tables Plan

Aggregated bullets for per-figure / per-table files across the four
pair-plans. Source-pair tags: `(req)`, `(arch)`, `(risk)`, `(creative)`,
`(joint)`.

## Promote existing figures_tables.md content

- [ ] **`figures-tables/system_overview.md`** (Fig 1: Fiji <-> TCP <->
  agent). + SVG source. Effort: M. (arch)
- [ ] **`figures-tables/closed_loop.md`** (Fig 2: closed-loop analysis
  panel sequence). Effort: S. (req)
- [ ] **`figures-tables/reliability_grid.md`** (Fig 3 placeholder).
  Effort: M. (req)
- [ ] **`figures-tables/benchmark_results_4a.md`** (split into 4a-d).
  Effort: M. (joint)
- [ ] **`figures-tables/benchmark_results_4b.md`**. Effort: M. (joint)
- [ ] **`figures-tables/benchmark_results_4c.md`**. Effort: M. (joint)
- [ ] **`figures-tables/benchmark_results_4d.md`**. Effort: M. (joint)
- [ ] **`figures-tables/capstone.md`** (Fig 5 capstone). Effort: M. (joint)
- [ ] **`figures-tables/token_waterfall.md`** (Fig 6: per-task token
  budget). Anchor: `tokenBudget`, `CostHeaderListener`. Effort: M. (creative)
- [ ] **`figures-tables/threat_surface.md`** (Fig 7: STRIDE +
  attack-surface map). Cross-ref [`security/`](../security/). Effort: M.
  (risk)
- [ ] **`figures-tables/agent_agnostic_matrix.md`** (Fig 8: agents x
  capabilities). Effort: M. (arch)
- [ ] **`figures-tables/command_class_taxonomy.md`** (Fig 9: 53 commands
  grouped by family). Source: `TCPCommandServer.java:1109-1216`. Effort: M.
  (arch)
- [ ] **`figures-tables/intent_router_decision_tree.md`** (Fig 10).
  Effort: M. (arch)
- [ ] **`figures-tables/provenance_graph_example.md`** (Fig 11). Source:
  `ImageGraph.java`. Effort: M. (arch)
- [ ] **`figures-tables/dialog_interaction_sequence.md`** (Fig 12).
  Effort: M. (arch)
- [ ] **`figures-tables/command_frequency_heatmap.md`** (Fig 13).
  Effort: S. (creative)
- [ ] **`figures-tables/failure_mode_table.md`** (Table 11 / Fig 13: the
  killer table - failure mode | citation | mitigation | code anchor |
  status). Source: synthesis Table 11 lines 53-67. Effort: L. (arch, risk)
- [ ] **`figures-tables/extended_comparison.md`** - extended comparator
  matrix. Effort: M. (risk)

## Tables

- [ ] **`figures-tables/command_inventory.md`** - full 53-command table.
  Effort: M. (arch)
- [ ] **`figures-tables/recipe_table.md`** - 27 recipes x domain. Source:
  `ls agent/recipes/*.yaml`. Effort: S. (arch)
- [ ] **`figures-tables/reference_table.md`** - 61 references x domain.
  Source: `agent/references/INDEX.md`. Effort: S. (arch)
- [ ] **`figures-tables/competitor_matrix.md`** - ImageJAI vs fiji-llm
  vs Fiji_imageJ_mcp vs SteffenPL/fiji-mcp vs Omega vs napari-MCP.
  Effort: L. (arch)
- [ ] **`figures-tables/cost_per_task.md`** - cost per task across
  providers. Source: `engine/budget/BudgetCeilingTracker.java`. Effort: M.
  (arch)
- [ ] **`figures-tables/build_runtime_matrix.md`** - build/runtime
  version matrix. Source: `pom.xml`. Effort: S. (arch)
- [ ] **`figures-tables/safety_register.md`** - safety register table.
  Effort: M. (risk). Sanitiser row landed **Implemented 2026-05-09** —
  `AgentContextSanitizer` at five boundary points in
  `TCPCommandServer.java` (D7 from [`../decisions.md`](../decisions.md));
  cross-ref [`../security/agent_context_sanitization.md`](../security/agent_context_sanitization.md).
- [ ] **`figures-tables/protocol_efficiency_vs_mcp.md`** - bytes-per-task
  comparison. Effort: M. (creative)
- [ ] **`figures-tables/agent_dropdown_matrix.md`** - per-agent x
  per-capability matrix. Effort: S. (arch)

## Stylised / unusual figures (creative pair)

- [ ] **`figures-tables/cover_provenance.md`** - cover-art provenance
  graph. Anchor: `ImageGraph`,
  `get_image_graph` `TCPCommandServer.java:1213`. Effort: M. (creative)
- [ ] **`figures-tables/command_mandala.md`** - command-frequency
  mandala SVG. Effort: S. (creative)
- [ ] **`figures-tables/recipe_voronoi.md`** - Voronoi tiling of recipe
  space. Effort: M. (creative)
- [ ] **`figures-tables/failure_treemap.md`** - failure-mode treemap.
  Effort: S. (creative)
- [ ] **`figures-tables/session_sankey.md`** - session-as-Sankey diagram.
  Effort: M. (creative)
- [ ] **`figures-tables/port7746_day.md`** - day-in-the-life of port 7746.
  Effort: S. (creative)
- [ ] **`figures-tables/storyboard.md`** - top-3-figures storyboard.
  Effort: M. (arch)

## Cross-folder dependencies

- Every figure cites a code anchor in [`architecture/`](../architecture/)
  or a result in [`bench/`](../bench/) / [`evaluation/`](../evaluation/).
- The `failure_mode_table.md` is the cross-cutting integration document -
  cited from [`security/`](../security/),
  [`limitations/`](../limitations/), and [`for-reviewers/`](../for-reviewers/).
