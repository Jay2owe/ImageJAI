# Manuscript Plan

Aggregated bullets for manuscript-level submission docs across the four
pair-plans. Source-pair tags: `(req)`, `(arch)`, `(risk)`, `(creative)`,
`(joint)`.

## Submission docs

- [ ] **`manuscript/manuscript_outline.md`** - section-by-section skeleton
  aligned with `paper_storyline.md` "Target Manuscript Structure".
  Blocked-by: D3 (lead-framing). Effort: M. (req)
- [ ] **`manuscript/claims_status_table.md`** - single source of truth:
  every claim, status (Implemented / Demo-ready / Needs benchmark /
  Needs verification / Planned), evidence file, code anchor. Blocked-by:
  D7, D8. Effort: M. (req)
- [ ] **`manuscript/reproducibility_statement.md`** - JDK 25 vs Fiji JDK 21
  disclosure, deterministic-seed protocol, dataset list, tagged release.
  Blocked-by: D1, D5. Effort: M. (req)
- [ ] **`manuscript/data_availability.md`** - dataset list, accession
  numbers (BBBC020/038/IDR if D5 chooses public), Zenodo DOI plan.
  Blocked-by: D2, D5. Effort: M. (req)
- [ ] **`manuscript/fair4rs_audit.md`** - FAIR for Research Software
  self-assessment (findable, accessible, interoperable, reusable).
  Source: external FAIR4RS principles. Blocked-by: D1, D2. Effort: M. (req)
- [ ] **`manuscript/ml_reproducibility_checklist.md`** - REFORMS (Kapoor
  et al. 2024) + Pineau v2.0 filled. Effort: M. (risk)
- [ ] **`manuscript/ai_use_disclosure_system.md`** - system-level: which
  models we exposed, which were used to write code/manuscript,
  prompt-injection mitigation status. Blocked-by: D7. Effort: S. (req)
- [ ] **`manuscript/ai_use_disclosure_manuscript.md`** - manuscript-level
  (separate from system-level), per-section claim of human authorship.
  Effort: S. (req)
- [ ] **`manuscript/ethical_ai_disclosure.md`** - composite Nature-Portfolio
  disclosure pulling both AI-use statements above. Effort: S. (joint)
- [ ] **`manuscript/reviewer_rebuttal.md`** - pre-emptive responses to
  expected reviewer questions (Why TCP not MCP? Why not Computer Use? Why
  not napari? What does safe mode actually guarantee?). Blocked-by: D3.
  Effort: M. (req)
- [ ] **`manuscript/license_decision_record.md`** - capture D1 outcome
  and rationale (GPL-2-or-later vs BSD-2 + runtime-only Bio-Formats).
  Blocked-by: D1. Effort: S. (req, risk)
- [ ] **`manuscript/quarep_limi_emission_status.md`** - planned, not
  implemented; no `MethodsTableExporter` shipped. Blocked-by: D8.
  Effort: S. (req, risk)

## Cross-cutting edits to existing docs

- [ ] Edit `paper_storyline.md` - promote "What Not To Overclaim" into
  the abstract / first paragraph. Effort: S. (risk)
- [ ] Edit `paper_storyline.md` - add a "Workspace as the contribution"
  subsection (53 commands + 27 recipes + 61 references + intent router +
  safe-mode + provenance + branching + friction log + reactive rules +
  cost telemetry, not just the TCP protocol). Anchor: synthesis.md:49-50.
  Blocked-by: D3. Effort: S. (req)
- [ ] Edit `paper_storyline.md` - rewrite around offline-first /
  failure-aware framing. Effort: M. (arch)
- [ ] Edit top-level `README.md` and `overview.md` - drop Fiji sample
  images (Blobs.gif, T1-Head, MRI Stack); use BBBC020/BBBC038/IDR.
  Effort: S. (risk)
- [ ] Edit project `CLAUDE.md` and `overview.md` - fix "~40 commands,
  4300 LOC" to **53 commands, ~7125 LOC**. Effort: S. (risk, arch)
- [ ] Audit `agent/references/` (61 markdown files) for unverified
  claims; mark each with `verified-by: <citation>` or remove. Effort: L.
  (risk)
- [ ] Decision record: drop voice-input video #55 (synthesis #6).
  Effort: S. (risk)
- [ ] Decision record: AgentPlannerDetector (formerly GsdDetector)
  cut from public manuscript (D4, synthesis #4). Effort: S. (risk)

## Cross-folder dependencies

- Two AI-use disclosure files exist by design (system + manuscript) -
  also mirrored in [`ethics/`](../ethics/) and [`supplements/`](../supplements/).
- Claims status table references every other folder's `plan.md` for
  evidence; this is the integration document.
