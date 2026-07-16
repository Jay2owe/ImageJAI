# Execution status

Base commit: `a2d2594`

Inline-agent rule: agents edit and test only their assigned files; the coordinator
owns the shared Git index and creates path-scoped commits after each stage. No push,
deployment, Fiji shutdown, or public release is authorized.

## Swarm DAG

| Wave | Parallel atoms | Dependency/conflict note |
|---:|---|---|
| 1 | 01 | Establish safe test discovery before all implementation. |
| 2 | 02, 03, 06, 16 | Disjoint hooks, protocol, provider-policy, and scientific-helper files. |
| 3 | 04, 08, 15, 18 | Event/TCP work is isolated from Java launch, recipes, and contexts. |
| 4 | 05, 07, 17 | Coordinator Java work is disjoint from provider and Python workflow branches. |
| 5 | 09, 10, 13 | Engine, assistant/settings, and reactive files are disjoint. |
| 6 | 11, 12, 19, 22 | Undo, TCP/image, terminal UI, and phrasebook branches are disjoint. |
| 7 | 14, 20, 24 | Persistence, keyboard UI, and Python runner are disjoint. |
| 8 | 21, 23, 25 | Visual UI, resource bounds, and manifest/docs are disjoint. |
| 9 | 26 | Final convergence and release verification. |

Critical path: `01 -> 03 -> 04/05 -> 09 -> 12 -> 14 -> 23 -> 26`.

Conflict watchlist:

- `TCPCommandServer.java`: 03 -> 04 -> 05 -> 09 -> 12 -> 23 -> 25.
- `agent/ij.py`: 03 -> 04 -> 24 -> 25.
- `EventBus.java`/`OutboundEvent.java`: 04 -> 13.
- `ImageGraph.java`: 12 -> 14.
- `AgentLauncher.java`: 08 -> 19 -> 24.
- `ChatView.java`: 10 -> 20 -> 23.
- `pom.xml`/`build.sh`: 01/02 -> 26.

## Stage ledger

| Stage | Status | Commit | Verification |
|---:|---|---|---|
| 01 | completed | `f201e7b` | 360 offline Python passed; 2 known stage-18 context failures; 781 Java unit + 3 integration passed |
| 02 | completed | `41ab3b4`, `a0dfb36` | 6 hook tests passed; installer idempotent; post-commit returned in 81.9 ms; public CLI rebuilt 25,707 nodes |
| 03 | completed | `30aa947` | 9 Python loopback + 27 focused Java passed; Maven package 789; offline Python 401 passed with 2 stage-18 failures |
| 04 | completed | `2f4b81f` | 12 focused Java + 11 Python API tests passed; Maven package green; authenticated privacy-governed streams retain 8/256 bounds; live Fiji stream deferred |
| 05 | completed | `1dde5a6` | 829 Maven tests plus 33 focused coordinator, queue-storm, pipeline-lock, timeout, and two-session event-isolation tests passed; terminal completion waits for worker exit |
| 06 | completed | `0e51e0f` | 15 policy + 207 Gemma/provider tests passed; bundled import passed; Maven package 789 tests passed |
| 07 | completed | `8002adb` | 114 provider tests, 104 Gemma tests, 43 audit-focused Python tests, 9 Java proxy lifecycle tests, Maven compile, and authenticated real-proxy smoke/cleanup passed |
| 08 | completed | `0120743` | 48 focused privacy/launch tests passed; integrated Maven clean package passed 802 tests; credential-bearing metadata and unsafe launches rejected |
| 09 | completed | `0f3f10f` | 863 Maven tests and focused coordinator/lifecycle/pipeline/batch/subprocess/source-tag regressions passed; no duplicate TCP mutation lock or per-handler executor remains |
| 10 | completed | `1c6684d` | Focused Stage 10 tests and the full Maven suite passed; assistant mutations now share the coordinator, launch policy is enforced, settings save/cancel is transactional, and clear/listener lifecycle races are covered |
| 11 | completed | `4d43c8a` | 67/67 focused undo/exploration tests passed; all Stage 11 tests passed inside the 911-test integrated run, with only six concurrent Stage 22 intent failures remaining |
| 12 | pending | - | - |
| 13 | completed | `b0a5617` | 37/37 focused reactive, event-bus, and stable duplicate-title identity tests passed; admission/deadlines, policy/coordinator/provenance, capture containment, quarantine, retention, diagnostics, and stable image/dialog lifecycles are implemented |
| 14 | pending | - | - |
| 15 | completed | `ff6bf39` | 16 contract/legacy tests passed; all 28 recipes validate and whole-directory dry-run; unsafe file traversal/symlink escapes and unsupported later steps block before Fiji mutation |
| 16 | completed | `6ad41c7` | 37 helper regressions passed; compact 4M-pixel buffer and randomized exact-median check passed |
| 17 | completed | `20ca91d` | 24 workflow/cache/lint tests passed; practice dry-run and Python compilation passed; trainer/practice restore images, active cursor, Results, ROIs, measurements, redirect, and BlackBackground on success/failure |
| 18 | completed | `cdf13d1` | 120 context/runtime tests passed; zero-polling integration passed twice; generated contexts byte-stable; full offline suite exceeded the 120 s stage check and is deferred to convergence |
| 19 | pending | - | - |
| 20 | pending | - | - |
| 21 | pending | - | - |
| 22 | pending | - | - |
| 23 | pending | - | - |
| 24 | pending | - | - |
| 25 | pending | - | - |
| 26 | pending | - | - |

## Corrective passes

Accepted verifier findings, coordinator fixes, rejected findings, and uncertain items
will be recorded here after implementation. Every verifier is fresh and review-only.
