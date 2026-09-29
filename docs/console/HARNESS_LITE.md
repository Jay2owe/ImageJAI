# ImageJAI harness-lite

Status: **foundation implemented; scientific promotion CI and TCP facade remain future work.**

## Purpose

The harness gives every ImageJAI agent a small, relevant packet of reviewed
knowledge without turning one successful image into an unverified general
rule. It
coordinates existing recipes, references, failure fixes, evidence and model
contexts. It does not replace them.

## Safety boundary

- New knowledge is always a `candidate`.
- Candidates never enter model prompts.
- `/memory approve` advances exactly one stage and requires written evidence.
- Model refinement only drafts candidates. It cannot approve, change scope or
  grant capabilities.
- Protected postures redact known paths before prompt injection. Unknown
  absolute paths are refused from durable-memory commands.
- Skills are guidance. A recipe reference in a skill never executes the recipe.
- Compaction rewrites model context only. The append-only evidence journal and
  artifacts remain unchanged.

Scopes are `session`, `project`, `instrument`, `user` and `shared`.
Nothing in this package ships one user's learned facts; every store is
created locally on the machine that uses it.

Promotion stages:

```text
candidate -> session_confirmed -> project_approved -> validated
```

## Storage

| Scope | Store |
|---|---|
| Session | `~/.imagej-ai/sessions/<session>/harness/` |
| Project / instrument | `<image-folder>/AI_Exports/.imagejai-harness/` |
| User / shared | `~/.imagej-ai/harness/` |
| Scientific evidence | `~/.imagej-ai/sessions/<session>/evidence.jsonl` |
| Large artifacts | `~/.imagej-ai/sessions/<session>/artifacts/` |

All harness state uses schema 1, atomic replacement, optimistic versions and an
append-only refinement event log. Evidence JSONL has monotonic sequence numbers.
Artifacts have generated names, byte bounds and SHA-256 references.

## Entry types

`fact`, `preference`, `constraint`, `failure_fix`, `procedure`,
`validation_rule`, `environment_capability`, and `prompt_policy`.

Each entry carries scope, status, applicability, evidence, conflicts, expiry,
source, timestamps and version. Retrieval filters incompatible state and ranks
status, applicability, query overlap and recency deterministically. Conflicts
are disclosed rather than merged.

## Console commands

```text
/remember <scope> <kind> | <title> | <content>
/memory
/memory search <query>
/memory approve <id> | <evidence>
/memory deprecate <id> | <reason>
/memory rollback <id> <version> | <reason>
/refine                         # separate no-tools candidate drafting call
/refine list
/refine accept <n> <scope>      # still saves only a candidate
/refine discard
/compact
/skills [query]
/skill <name>
/skill clear
```

The console refreshes the harness digest before each main turn.
Low-reliability or small-context models receive fewer and shorter entries.
Subscription CLI agents retain their vendor-owned history and receive the same
reviewed digest, selected skills and folder instructions in their wrapped prompt.

## Progressive skills

Discovery order is:

1. Project `.imagejai/skills/`
2. Project and ancestor `.agents/skills/`
3. User `~/.imagej-ai/skills/`
4. Bundled `agent/skills/`

Only validated `name`, `description`, disable flag and optional recipe reference
enter the catalog. Absolute source paths are hidden from cloud prompts. The body
is loaded through the model's `load_skill` tool or `/skill`; skills marked `disable-model-invocation` still
require that explicit user command.

Bundled skills currently cover closed-loop analysis, safe measurement,
segmentation validation and resumable batch analysis.

## Evidence and compaction

Each turn records user/assistant messages, correlated tool call/result pairs,
approvals, decisions, checkpoints and compactions. Tool results larger than the
inline bound become hashed artifacts with head/tail disclosure in JSONL.

Compaction never splits a tool call from its result. It removes inline image,
pixel and duplicate screenshot payloads before logs or prose. Exact macros,
parameters, units, calibration, image revisions, C/Z/T, ROI IDs, results,
errors, decisions, approvals, pending jobs, hashes and lineage bypass prose
summarisation.

## Shared access for other agents

`agent/harness_access.py` gives Claude, Gemini, Gemma and any other Python
ImageJAI agent the same reviewed knowledge and skills, as a library or a JSON
CLI:

```bash
python agent/harness_access.py context --query "count nuclei" --project <folder>
python agent/harness_access.py skills  --project <folder>
python agent/harness_access.py skill   --name safe-image-measurement --project <folder>
python agent/harness_access.py propose --kind failure_fix --scope session \
    --title "..." --content "..." --session-id <id>
```

`context` returns approved entries only. `propose` always writes a candidate.
The facade has no promote, update, deprecate or rollback command, so a model
cannot approve its own memory. Absolute paths and sample filenames are refused
after sanitising, while pseudonym tokens are allowed.

## Review and validation controls

`/validate check|run` verifies hashed procedure inputs and separate development
and held-out images against declared trusted expectations. Running requires
review of the complete macro; receipts are saved beside the images. Procedure
promotion checks receipt freshness, measurements, source hashes and entry content.
`/memory-review` exposes expiry/conflicts, evidence-based renewal, deprecation,
receipt attachment and instrument revalidation. `/instrument` detects configuration
changes and withholds affected knowledge. No procedure becomes validated automatically.

See the [completed Prime Agent port stages](../prime-agent-ports_COMPLETED/00_overview.md).
Scientific validity requires independently trusted cases and expectations.

## Outside this port

- Automatically running private reference datasets in continuous integration.
- A plugin-side TCP harness API is outside the agreed Python wrapper scope.
- Importing a legacy Java `LedgerStore` as reviewed memories.
- Automatic promotion. This is intentionally not planned.
