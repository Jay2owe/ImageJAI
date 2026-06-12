# ImageJAI YAML Recipes & References Review
**Date:** 2026-05-03
**Scope:** agent/recipes/*.yaml + agent/references/*.md + agent/CLAUDE.md/AGENTS.md/GEMINI.md
**Reviewed by:** AI Code Search Agent

---

## Findings Summary
- **BUG:** 2
- **ISSUE:** 5
- **INEFFICIENCY:** 1
- **NIT:** 0
- **Total:** 8 findings

---

## Critical Findings

### BUG

- **[BUG]** `agent/recipes/incucyte_gfp_extraction.yaml:14-30` — Parameters field is a dict, not a list
  - Line 14-30 defines `parameters:` as a key-value mapping instead of a YAML list. The recipe schema expects `parameters:` to be a list of objects with `name`, `type`, `default` fields. Current structure has bare keys like `paraboloid_r1:` followed by `description:`.
  - Convert to list format: `parameters: [ { name: paraboloid_r1, description: "...", default: 50, range: [25, 100] }, ... ]`

- **[BUG]** `agent/recipes/trackmate_cell_traces.yaml:15-21` — Parameters field is a dict, not a list
  - Same issue as incucyte_gfp_extraction.yaml. Lines 15-21 define `parameters:` as key-value dict (`min_frames:`, `description:`, `default:`) instead of a list of parameter objects.
  - Convert to list format: `parameters: [ { name: min_frames, description: "...", default: 50, notes: "..." }, ... ]`

### ISSUE

- **[ISSUE]** `agent/references/brain-atlas-registration-reference.md:22,25,84,284,305,318,344,355,440,442,490` — SCN references in general brain atlas doc
  - While SCN (suprachiasmatic nucleus) is a legitimate anatomical term, multiple mentions in a general brain atlas registration doc could signal contamination if the doc is meant to be domain-agnostic. Mentions: bregma coords (line 22), Allen ID (line 25), index terms (line 84), atlas entry (line 284), section (line 305), figure (line 318), Allen limitation (line 490).
  - Review context: these are legitimate anatomical references. No action needed if this doc is intentionally about brain atlas anatomy. If it should be domain-agnostic, consider splitting SCN-specific guidance to scn-reference.md or scn-analysis-reference.md (which already exist).

- **[ISSUE]** `agent/references/publication-figures-reference.md:1331-1332` — Gene names in general formatting guide
  - Lines 1331-1332 mention BMAL1 and PER2 as examples of protein names for figure labels. While these are legitimate protein names for formatting guidance, they are specifically associated with circadian biology and could bias a reader toward lab-specific language examples.
  - Consider using more neutral examples: instead of "(BMAL1, PER2)" use "(e.g., TP53, EGFR)" or just list as "protein names" without examples. Alternatively, move to circadian-imaging-reference.md or circadian-analysis-reference.md.

- **[ISSUE]** `agent/references/light-sheet-reference.md:598` — SCN example in 3D rendering scene code
  - Line 598 shows example code: `scene.add_brain_region("SCN", alpha=0.3, color="salmon")`. While valid 3D light-sheet rendering code, naming a specific brain region in a general light-sheet reference could hint at lab-specific workflows.
  - Either make the example generic (e.g., `scene.add_brain_region("region_of_interest", ...)`) or move the SCN-specific example to circadian-imaging-reference.md or scn-reference.md.

- **[ISSUE]** `agent/references/live-cell-timelapse-reference.md:166,176` — SCN drift correction recommendations in general timelapse doc
  - Lines 166 and 176 specifically mention "SCN/culture (3-path logic)" and "SCN/Tissue Slice → **Optimized Phase-Corr**" as specific recommendations in a general live-cell timelapse reference. While SCN is a valid use case, treating it as a primary example in a general doc could bias recommendations.
  - Move SCN-specific registration strategies to circadian-imaging-reference.md or scn-analysis-reference.md. Keep general timelapse doc neutral with examples like "tissue culture" or "thin tissue preparations" instead of specific brain regions.

- **[ISSUE]** `agent/references/registration-stitching-reference.md:886` — SCN mentioned in general registration workflow table
  - Line 886 table entry: "Neuronal rhythms (SCN)" listed as a use case under "Drift Correction Methods." While valid, naming a specific brain region in a general registration table could hint at lab-specific priorities.
  - Replace with generic example: "Neuronal tissue (time-lapse)" or "Oscillating cells" to keep the reference domain-agnostic.

### INEFFICIENCY

- **[INEFFICIENCY]** `agent/recipes/3d_ring_render.yaml:93,207,209` — Comment and code duplication in isolation logic
  - Steps 5b (lines 231-259) and step 8 (line 296) both set display ranges as the isolation method, with overlapping explanations. Step 5b zeros DAPI outside center, step 8 sets strict display ranges. The note at line 296 says "The display range IS the isolation method", but step 5b suggests zeroing pixels. This is logically correct (both are used together) but confusing.
  - Consolidate into a single, clearer description: "Isolation uses two methods: (1) strict display range (high min clips to black), and (2) DAPI masking outside center (for isolated mode only). Together they produce a clean render."

---

## Minor Findings

None (all findings are BUG, ISSUE, or INEFFICIENCY severity).

---

## Cross-Reference Integrity
✓ All 51 referenced .md files exist in agent/references/
✓ No broken links detected
✓ All YAML syntax valid

---

## Lab-Specific Contamination Check
✓ CLAUDE.md, AGENTS.md, GEMINI.md: No lab-specific mentions
✓ General reference docs (fluorescence-microscopy, batch-processing, etc.): Lab-agnostic
⚠ SCN mentions: Limited to brain-atlas-registration, light-sheet, live-cell-timelapse, registration-stitching — all are examples/use cases rather than defining features
⚠ BMAL1/PER2: Single mention in publication-figures-reference.md as formatting example

**Recommendation:** Treat ISSUE-level findings as non-critical. SCN and BMAL1/PER2 are legitimate anatomical/biological references; they signal biased example selection rather than actual contamination. Fix BUG-level YAML schema issues before using recipes in production.

---

## Files Reviewed
- **Recipes:** 27 YAML files in agent/recipes/ (3d_object_counting, 3d_render, ..., wound_healing)
- **References:** 61 MD files in agent/references/
- **Agent contexts:** agent/CLAUDE.md, agent/AGENTS.md, agent/GEMINI.md
