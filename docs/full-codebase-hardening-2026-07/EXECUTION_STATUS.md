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
| 12 | completed | `eb451ca` | 61 focused image/state/integration tests passed; the 890-test run had only four then-in-progress Stage 11 undo failures, all subsequently green in Stage 11's integrated rerun |
| 13 | completed | `b0a5617` | 37/37 focused reactive, event-bus, and stable duplicate-title identity tests passed; admission/deadlines, policy/coordinator/provenance, capture containment, quarantine, retention, diagnostics, and stable image/dialog lifecycles are implemented |
| 14 | completed | `23a2417` | 69/69 expanded focused Java tests, 29/29 final affected Java tests, and 15/15 Python provenance/session tests passed; the 951-test run had only five Stage 22 failures queued for correction |
| 15 | completed | `ff6bf39` | 16 contract/legacy tests passed; all 28 recipes validate and whole-directory dry-run; unsafe file traversal/symlink escapes and unsupported later steps block before Fiji mutation |
| 16 | completed | `6ad41c7` | 37 helper regressions passed; compact 4M-pixel buffer and randomized exact-median check passed |
| 17 | completed | `20ca91d` | 24 workflow/cache/lint tests passed; practice dry-run and Python compilation passed; trainer/practice restore images, active cursor, Results, ROIs, measurements, redirect, and BlackBackground on success/failure |
| 18 | completed | `cdf13d1` | 120 context/runtime tests passed; zero-polling integration passed twice; generated contexts byte-stable; full offline suite exceeded the 120 s stage check and is deferred to convergence |
| 19 | completed | `40ffd0c` | Latest sources compiled directly and 81/81 focused terminal/discovery/credential tests passed; an earlier 929-test Maven run had only six concurrent Stage 22 intent failures |
| 20 | completed | `40958a5` | 5/5 headless accessibility and 20/20 focused Stage 19/20 regression tests passed; the 951-test run had only five Stage 22 intent failures queued for correction |
| 21 | completed | `04928c0` | 4/4 visual lifecycle, 57/57 focused UI, and 957/957 full Maven tests passed; theme contrast, focus, ProviderCard accessibility, and one-shot dialog disposal are covered |
| 22 | completed | `528dc30`, `0f94425` | 22 focused Java and 3 generator tests passed; 398 IDs/16,860 phrases load exactly, repeat generation is byte-stable, the bounded matcher benchmark passed at 112/115 (97.4%), and the corrective full Maven run passed |
| 23 | completed | `d3cbe77`, `24ecd6b`, `98c6906` | 123/123 security companion tests, 40/40 bounded scan/cache tests, and 1,039/1,039 full Maven tests passed; cross-cutting socket, parser, job, capture, audit, event, UI, filesystem, token, path, and visual-scope bounds are enforced |
| 24 | completed | `91ffbaa` | 24/24 runner/session and 120/120 context tests passed; AgentLauncher integration, Python compilation, whitespace audit, and offline structured doctor behavior passed |
| 25 | completed | `a5a66a5` | Generated command docs and reference index are current; 166 Python tests, 34 focused Java tests, and 1,045/1,045 full Maven package tests passed; the packaged schema-1 manifest has 61 commands and matches the source hash |
| 26 | completed | `fef61bd` | 1,055 unit, 3 integration, 31 lifecycle, 483 offline Python, 3 build/install, and 10 bundle-transaction tests passed; two committed-source JAR builds matched SHA-256 `41516E2B...BAB8`; disposable bundle verification passed with 186 allowlisted agent files |

## Corrective passes

Accepted verifier findings, coordinator fixes, rejected findings, and uncertain items
will be recorded here after implementation. Every verifier is fresh and review-only.

- `0f94425`: accepted integrated Stage 22 correction. Contextual repeat syntax now fails closed when conversation memory is absent/stale, and the reviewed slot-less channel alias avoids a menu-color ambiguity. All five unchanged regressions and the full Maven suite passed.
- Sequential verifier iteration 1 accepted 9 findings, rejected 0, and left 0 uncertain. Commit `ff9d0df` closes cloud host-code bypasses, gives Gemma one authenticated strict session, masks and validates cloud credentials, bounds prompt/context processing, releases exploration images, makes setup rollback transactional, and makes Graphify burst admission race-safe. The stale Python release count was corrected from 483 to the authoritative 520-test total in this record.
- The repeated clean-build gate then exposed two additional accepted lifecycle defects, with 0 rejected and 0 uncertain: `ae6bc44` batches durable audit writes behind atomic writer/notifier fences and closes drain-exit/shutdown races; `e61ec9d` removes the nested-JVM readiness race and terminates multi-level process trees deepest-first.
- Corrective convergence at `e61ec9d`: 1,078 Java unit tests, 3 integration tests, 31 direct lifecycle tests, 520 offline Python tests, 11 Pester bundle tests, and all focused contract/generator/hook checks pass. Two clean JAR builds match SHA-256 `3898CD1B97BCBCEB205BB56593BF300AE7511B40661BED6840D9204395C65D35`; the disposable bundle contains 186 allowlisted agent files and matching source/shared/local JARs. This was the input to sequential verifier iteration 2.
- Sequential verifier iteration 2 accepted 7 findings, rejected 0, and left 0 uncertain. `8773ac5` makes TCP token authentication default-on, limits explicit compatibility to read-only commands, and binds audit rows to their admission dataset. `0fcf246` closes the bundled legacy cloud host-code path, makes saved recipes non-elevatable from cloud models, and bounds/coalesces Gemma events. `d21bb3f` removes misleading Ollama token verification/storage and caps all Python TCP frames before decode. A load-induced 2-second Reactive test-harness miss was also accepted as a low reliability issue and hardened in `8172555` without changing production expiry semantics.
- Corrective convergence at `8172555`: 1,083 Java unit tests, 3 integration tests, 531 offline Python tests, and 11 Pester bundle tests pass. Two clean JAR builds match SHA-256 `8FD4F465FE99D96D2C61E39451F5EDB8A9353BD9BC03013AD22624F430A63631`; the packaged manifest matches source, forbidden Maven metadata is absent, and a disposable 186-file agent bundle has matching source/shared/local JARs. This was the input to sequential verifier iteration 3.
- Sequential verifier iteration 3 accepted 6 findings, rejected 0, and left 0 uncertain. `b06b54d` makes token persistence failure fatal. `be6676d` and `8298cac` block macro host-filesystem escapes and fail closed on unknown ImageJ file primitives. `e1a82da` authenticates and bounds all agent transports and lifecycle paths. `19c1a5f` grants host-code execution only to explicitly trusted local agents. `af6e43f` confines ring-render input and output to governed host-code operations and the active image's `AI_Exports` directory.
- A final compatibility audit found two additional tracked Ollama wrappers that could still contact AgentConsole without a usable token. `feeff2f` routes both through one fail-closed authenticated, bounded client and adds direct wrapper regressions; stale documentation for the raw fallback was removed.
- Corrective convergence at `feeff2f`: 1,095 Java unit tests, 3 integration tests, 31 direct lifecycle tests, and 595 authoritative Python tests pass. Both clean JARs match SHA-256 `EF497B509F1B68148B082249DCACB65FF70B27AB44A86E99205DCB1DBD7D1FE1`; the 14,789,634-byte JAR has 5,637 entries, no `META-INF/maven/**`, and one packaged manifest matching source. The disposable bundle has 186 allowlisted agent files, exactly 190 unique ZIP members, no secret-scan findings, and matching source/shared/local/ZIP JAR hashes. The non-deploy build path and Graphify post-build hook both completed successfully. This is the input to fresh sequential verifier iteration 4.
