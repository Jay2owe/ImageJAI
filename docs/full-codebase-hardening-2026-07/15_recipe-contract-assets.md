# Executable recipe contract and migrated assets

## Why this stage exists

The runner executes only `macro`/`code` while shipped recipes use script, Groovy, file, conditions, preconditions, capture, and validation. Unsupported work can be skipped while the runner reports success, and validation currently exits zero for invalid recipes.

## Prerequisites

- Stage 01 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, release blocker 5.
- `agent/run_recipe.py`, `recipe_search.py`, and `recipes/README.md`.
- All YAML under `agent/recipes/`; focus first on `install_toolbar_tool.yaml`, `normalise_timeseries_video.yaml`, `remove_drift_borders.yaml`, and 3D render recipes.
- `agent/gemma4_31b/tools_recipes.py`.

## Scope

- Define one formal versioned recipe schema.
- Validate the complete recipe before the first mutation.
- Implement every retained step/condition or reject it explicitly.
- Execute preconditions, postconditions, captures, and validation as contracts.
- Make `recipe_search.py --validate` robust to malformed YAML and exit non-zero on any invalid file.
- Combine ranking bonuses instead of overwriting them.
- Migrate affected bundled recipes and dry-run every shipped recipe.

## Out of scope

- Mutation coordinator behavior is owned by stages 05/09; the runner should call it through the repaired client.
- General scientific-result validation functions belong to stage 16.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `agent/recipes/schema.json` | NEW | Canonical versioned recipe contract. |
| `agent/run_recipe.py` | MODIFY | Prevalidate and execute all retained step types. |
| `agent/recipe_search.py` | MODIFY | Honest validation/robust search/ranking. |
| `agent/gemma4_31b/tools_recipes.py` | MODIFY | Share the canonical runner/schema. |
| `agent/recipes/install_toolbar_tool.yaml` | MODIFY if required | Remove skipped semantics under the schema. |
| `agent/recipes/normalise_timeseries_video.yaml` and `remove_drift_borders.yaml` | MODIFY if required | Migrate reviewed script/Groovy/file steps. |
| `agent/test_recipe_contract.py` | NEW | Validate and dry-run the complete recipe directory. |

## Implementation sketch

Parse and schema-validate the entire YAML before dispatch. Build a typed step dispatcher with an exhaustive mapping; unknown types/fields/conditions are fatal. `when` evaluates only documented safe predicates. Preconditions/postconditions return named failures. Dry-run resolves files, templates, handlers, and conditions without mutating Fiji. Include all shipped YAML in a parametrized test, editing only files the validator proves ambiguous/invalid.

## Exit gate

1. An unsupported step anywhere prevents every mutation and exits non-zero.
2. Each retained step type has success/failure tests; pre/post/validation failures are surfaced.
3. The four reviewed workflows no longer skip required work.
4. `python agent/recipe_search.py --validate` exits zero only when all bundled recipes validate.
5. A full-directory dry-run dispatch test covers every recipe and malformed YAML never crashes search.

## Known risks

- Do not silently reinterpret ambiguous legacy YAML; migrate it explicitly or reject it.
- Dry-run must not open images, run plugins, or write outside temporary test space.
