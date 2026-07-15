# ImageJAI full-codebase hardening

## End goal

ImageJAI should let a biologist give an AI agent controlled, observable access to Fiji without risking silent image mutation, data leakage, or false success. When this plan is complete, the TCP protocol, Java plugin, Python clients, provider runtimes, recipes, user interface, build, and lab bundle will share one tested security and execution model. The repaired client will also support a browser-use-style `imagej-use-auto` command over the existing Fiji server, not a second automation daemon.

## Why we're doing this

The current unit tests mostly pass, but the review found release-blocking defects in session authentication, event privacy, mutation cancellation, cloud tool exposure, recipes, undo, exploration, and posture enforcement. Several APIs report success when work was skipped or state was not restored. Closing these gaps makes AI-driven microscopy analysis predictable enough for supervised lab use.

## Architecture overview

Fiji hosts the Java plugin and a loopback TCP server. Python clients and provider loops send JSON commands to that server; Swing panels launch agents and show sessions. Durable sessions land first, followed by governed events and one mutation coordinator. Independent tracks harden providers, recipes, persistence, scientific helpers, contexts, UI, build, and distribution before the stable Python transport becomes `imagej-use-auto` and the command manifest becomes canonical.

```text
tests/hooks --> session/auth --> subscriptions --> mutation coordinator --> execution surfaces
                    |                 |                    |
                    |                 +--> provider runtime +--> undo/exploration
                    |                                      +--> image commands
providers/privacy --> UI/terminal --> accessibility
recipes/helpers/contexts -----------------------------> release gates
persistence/build/distribution ----------------------> release gates
```

## Stage map

| NN | Name | One-line goal | Rough size | Depends on |
|---:|---|---|---|---|
| 01 | Safe test baseline | Make default test discovery offline, side-effect-free, and honest | Medium | none |
| 02 | Graphify hooks | Install one detached, locked, public-API graph updater | Medium | 01 |
| 03 | Durable sessions | Carry authentication and immutable capabilities across connections | Large | 01 |
| 04 | Governed events | Put subscriptions through the session, privacy, and audit envelope | Large | 03 |
| 05 | Mutation coordinator | Own admission, timeout, cancellation, undo, and provenance | Large | 03 |
| 06 | Provider tool policy | Remove unrestricted host-code tools from cloud schemas | Medium | 01 |
| 07 | Provider runtime | Repair vision, events, schemas, budgets, loop bounds, and proxy runtime | Large | 04, 06 |
| 08 | Privacy launch policy | Centralize egress/posture checks and remove credential leaks | Large | 01 |
| 09 | Engine mutation migration | Route macros, scripts, jobs, and pipelines through the coordinator | Large | 05 |
| 10 | Assistants/settings | Govern assistant mutations and align visible/backend state | Large | 05, 08 |
| 11 | Undo/exploration transactions | Make checkpoints atomic and exploration isolated | Large | 09 |
| 12 | Image/state commands | Make image opening, pixels, batches, probes, and provenance truthful | Large | 04, 09 |
| 13 | Reactive/event services | Bound reactive work and make events identity-safe and observable | Large | 04, 05 |
| 14 | Persistence/provenance | Quarantine corrupt stores and bind records deterministically | Large | 12 |
| 15 | Recipe contract/assets | Execute or reject every retained recipe step | Large | 01 |
| 16 | Scientific helpers | Correct numeric validation, pixel statistics, and colour comparisons | Medium | 01 |
| 17 | Python workflow safety | Harden caches/logs and isolate training/practice | Large | 03, 16 |
| 18 | Context runtime | Resolve every model family and make heartbeat freshness real | Medium | 01 |
| 19 | Terminal/model UI | Surface terminal failures and make discovery/credentials safe | Large | 08, 10 |
| 20 | Keyboard accessibility | Make chat, model, receipt, and history actions keyboard operable | Medium | 10, 19 |
| 21 | Visual/dialog lifecycle | Fix contrast/focus and dispose single-use dialogs | Medium | 19, 20 |
| 22 | Phrasebook contract/performance | Make intent coverage exact and matching bounded/off-EDT | Large | 10 |
| 23 | Resource/performance bounds | Bound protocol, jobs, captures, buffers, deltas, and privacy scans | Large | 12, 13, 14, 22 |
| 24 | imagej-use-auto | Add the preloaded runner, diagnostics, screenshots, and event waits | Large | 04, 12 |
| 25 | Command manifest/API docs | Generate descriptors, client coverage, methods, contexts, and docs | Large | 18, 24 |
| 26 | Build/distribution/release gates | Produce reproducible, scrubbed artifacts and verify the release | Large | 02-25 |

Stages without a dependency relationship are intentionally parallel. In particular, stages 06, 08, 15, 16, and 18 can begin after stage 01; stages 07, 10, 11, 13, 14, 17, 19, and 22 form separate later branches. Stages that touch `TCPCommandServer.java` or `agent/ij.py` are ordered to prevent shared-file collisions.

## House rules

- Query `graphify-out/graph.json` before broad architecture searches; fall back to `rg` only if results are stale or empty and report that fact.
- Check Fiji state before acting; never assume an image is open.
- Probe unfamiliar plugins before use, while preserving the probe-side-effect warning.
- Analysis outputs go only to `AI_Exports/` beside the opened image.
- Never use `Enhance Contrast normalize=true` on data that will be measured; use display-only ranges.
- Fix underlying defects and add a regression test for each reviewed failure path.
- Compile for Java 11 bytecode using the project JDK/Maven setup.
- Do not close Fiji, deploy a JAR, publish, push, or upload to an update site unless explicitly asked.
- Preserve unrelated user changes in the worktree.

## Known open questions

- Live Fiji tests need a running local Fiji instance and remain opt-in; stage 26 reports them separately when unavailable.
- The review requires a focused session registry and command manifest but leaves their final internal formats to their owning stages.
- Stage 07 makes experimental/obsolete providers non-importable; stage 26 excludes them from the bundle.

## Source record

The canonical long-form review remains [`docs/codebase-review-2026-07-15.md`](../codebase-review-2026-07-15.md). This split is additive and does not replace it.

## How to run a stage

Run `/do-step docs/full-codebase-hardening-2026-07/` to execute the next incomplete stage in numeric order.
