# ImageJAI full-codebase review — 2026-07-15

## Scope and baseline

This review covered the Java plugin, TCP protocol, Swing application, Python agent
helpers, provider runtimes, recipes, contexts, tests, build and distribution tools,
and user/developer documentation. Specialist passes covered architecture, security,
correctness, performance, observability, reproducibility, UI accessibility, test
quality, and public API/documentation accuracy. The final pass reproduced the
highest-risk claims and rejected or narrowed overstatements.

Baseline commit: `653f6108eee82de75ed13ca01334b3192ecd8de6`

- `mvn clean test -Denforcer.skip=true`: 782 tests passed.
- `python -m pytest agent -q`: 358 passed, 3 failed.
- The Python failures are two missing `families/other.md` context cases and one
  live-Fiji script accidentally collected as a unit test.
- `python -m pytest --collect-only -q` is unsafe at the repository root because
  `test-scripts/zero_polling_test.py` performs process termination, file deletion,
  sleeps, and Java launch during module import.
- The two Java `*IntegrationTest` suites are excluded by default.
- The existing graph is non-empty but stale and reports graphify metadata 0.4.11
  while the installed package is 0.8.33.

## Release blockers

### 1. TCP authentication and capabilities have no durable session

`agent/ij.py` opens a new socket for every command. `hello` therefore negotiates on
one socket and closes it, while `TCPCommandServer` stores capabilities by socket and
removes them on disconnect. Strict tokens reject the next command; without strict
tokens, safe mode, undo, structured errors, pulse and deduplication silently revert
to defaults.

Required outcome:

- `hello` creates a random, expiring server-side session and returns its identifier.
- Every later request carries the session identifier and authentication token.
- Authentication is validated per request, not inferred from a transient socket.
- Capabilities are immutable for a session, bounded, expired, and revoked on server
  stop.
- Legacy clients get a deliberately restricted compatibility session, not silent
  privileged defaults.
- Live loopback tests cover strict tokens, missing/invalid sessions, negotiated safe
  mode, structured replies, reconnects and expiry.

Primary files: `TCPCommandServer.java`, a focused session registry class,
`agent/ij.py`, and their tests.

### 2. Event subscriptions bypass the normal governance boundary

Subscription detection is a raw substring check before normal dispatch. It bypasses
authentication, token detokenisation, pseudonymisation, and request auditing. Raw
image titles/paths, dialog text, macro previews and job previews can be streamed.

Required outcome:

- Parse JSON first and require exact `command == "subscribe"`.
- Authenticate and authorize subscription creation using the same session contract.
- Apply the same detokenisation/pseudonymisation envelope to every frame.
- Audit subscription creation/closure without logging sensitive payloads.
- Preserve the existing caps of 8 subscribers and 256 queued frames.
- Coalesce only explicitly coalescible topics, never distinct job/image identities.

### 3. Mutation safety, serialization, timeout and cancellation are fragmented

Synchronous macros, async jobs, scripts, pipelines, exploration, reactive rules,
ConversationLoop and Local Assistant actions use different paths. Async macros can
bypass safe-mode scanning, undo/provenance capture, the macro mutex, timeout and
in-flight accounting. Job threads are created before capacity checks. Cancelling a
`Future` makes `isDone()` true before the ImageJ worker has necessarily stopped, so
the global mutation lock can be released while a zombie interpreter still mutates
state. `IJ.Macro.abort()` is global and can cancel the wrong concurrent job.

Required outcome:

- One mutation coordinator owns admission, safety scan, serialization, ownership,
  timeout, cancellation, undo capture, provenance and completion.
- Capacity is acquired before creating a worker.
- A cancelled/timed-out job is terminal only after the worker exits.
- No job can be submitted after shutdown.
- Job identifiers are cryptographically unpredictable and scoped to their session.
- Pipelines, scripts, exploration, reactive actions and both assistant paths use the
  same coordinator.
- Tests prove mutations cannot overlap and timed-out workers cannot mutate later.

### 4. Cloud providers receive unrestricted host-code tools

The rich provider loop exposes the full registry to every provider. `run_shell` uses
`shell=True`; `run_script` is similarly unrestricted. The pre-dispatch safety hook
currently always permits execution. There is no cloud/local gate, posture check,
per-call consent, or scoped allowlist.

Required outcome:

- Shell/script/process tools are absent from cloud provider schemas by default.
- Local providers receive them only through an explicit capability setting.
- Cloud elevation requires visible, per-call approval with an exact command/script
  preview and cannot be remembered silently.
- Provider model/CLI identifiers are validated before reaching a shell command.
- The total model/tool round count is bounded; interrupt joins the worker cleanly.
- LiteLLM proxy startup uses authentication even on loopback and secure runtime
  files.

### 5. Recipe execution can skip required work and still report success

The runner executes only `macro` and `code`, while shipped recipes use `script`,
`groovy`, `file`, `when`, preconditions, capture and validation. Unsupported steps
are skipped and the run can still succeed. Affected shipped workflows include
toolbar installation, time-series normalization, drift-border removal and 3D
rendering recipes.

Required outcome:

- Define and validate one formal recipe schema.
- Implement every retained step type and condition, or reject the recipe before any
  mutation.
- Preconditions, postconditions and validation are executable contracts.
- `recipe_search.py --validate` exits non-zero on any invalid recipe.
- Every bundled recipe passes schema and dry-run dispatch tests.

### 6. Undo and branch semantics can lose recovery state or corrupt images

Undo pops its frame before restoration succeeds. Pixel restoration checks only width
and height, not bit depth, stack/channel/slice/frame dimensions or raw length. A
failed restore loses the only recovery frame. Results are not restored although the
reply says they are; an empty ROI snapshot does not clear current ROIs. Branches are
labels over history rather than restorable image states and `from_call_id` is ignored.

Required outcome:

- Fully validate the target image and complete snapshot before mutating or popping.
- Failed restoration leaves the frame available.
- Restore all captured planes, image type, stack dimensions, calibration, ROIs and
  Results exactly, or explicitly exclude a field and never report it restored.
- Branch creation records a restorable checkpoint; checkout is atomic.
- Tests cover closed/replaced/same-size-different-type images and partial failures.

### 7. Exploration can modify or close user data and contaminate measurements

Exploration uses predictable temporary titles and later finds/closes images by title,
so a user's pre-existing image can be selected or closed. It overwrites global
Results and measurement preferences and does not transactionally restore current
image/ROI state. Generic coverage uses `mean / 255`, valid only for verified 8-bit
binary masks. Rewind removes frames before successful restoration.

Required outcome:

- Track temporary images by object identity plus an unguessable internal ID.
- Never select/close a pre-existing image during cleanup.
- Snapshot and restore current image, ROI Manager, Results and measurement settings
  in a `finally` path.
- Restrict binary coverage to validated binary masks; label other metrics accurately.
- Rewind is atomic and retains a frame on failure.

### 8. Privacy and posture enforcement is inconsistent

Gemini credential acquisition can send a key-bearing URL through the browser and
store it in plaintext model cache. On-prem launches can bypass visible provider
policy; legacy conversation paths and Local Assistant bypass posture/safe-mode
checks. Posture changes may leave stale sidecar configuration. Default Claude launch
adds `--dangerously-skip-permissions`.

Required outcome:

- Never place credentials in URLs, logs, model caches or process arguments.
- Make dangerous permission skipping opt-in per launch, default false.
- Centralize launch posture/egress policy for every provider and legacy path.
- Rewrite/revoke sidecar posture state when settings change.
- Scrub bundled workspaces for credentials, private data and provider runtime files.

## High-priority correctness and reliability work

### Image and state commands

- `open_image` must prove that the requested image opened, rather than accepting an
  already-open current image.
- `get_pixels` must restore the original position and bound allocation before reading.
- `newImages` and image-graph provenance must use object identity and deterministic
  ordering, including in-place operations.
- Pipeline resume must not rerun completed stages; batch failure replies must include
  the failing index without discarding prior results.
- Plugin probes execute real commands and can have side effects; restrict probes to
  cancellable dialogs and document the risk accurately.
- Queued Swing actions must be invalidated after a timeout so they cannot execute late.

### Reactive and event services

- Bound reactive action queues and reject stale actions.
- Apply mutation safety/provenance to reactive actions and captures.
- Enforce the documented `enabled` flag and quarantine cyclic/failing rules.
- Remove unbounded EventBus topic growth and make listener failures observable.
- Use stable dialog/image identities rather than titles.

### Persistence and provenance

- Corrupt ledger/history/session files must be quarantined, not interpreted as an
  empty store that is overwritten on the next save.
- Bound ledger entry and session-history sizes; return immutable snapshots.
- Associate journal/methods output with the initiating dataset, not whichever image is
  current after an asynchronous action.
- Use atomic writes and collision-resistant IDs/filenames.
- Escape spreadsheet formula prefixes in audit CSV.
- Compute dataset hashes across all planes with stable ordering and locale.

### Provider and context runtime

- Attach captured images to every vision-capable provider adapter; native Ollama is
  already correct.
- Correct tool contract drift: `get_open_windows`, optional `close_dialogs.pattern`,
  optional `capture_image.max_size`, and real JSON-schema arrays.
- Connect token/cost budget fallbacks to the loop and bound history/pixel payloads.
- Start the event subscriber for every provider wrapper.
- Treat stale context-hook heartbeats as stale; make daemon lease/startup atomic.
- Add a generic `contexts/families/other.md` overlay and require every model family and
  harness entry to resolve.
- Quarantine experimental `_spike` and obsolete AgentConsole Ollama wrappers from lab
  bundles and importable production surfaces.

### Python scientific helpers

- `auditor.py` must reject NaN, infinity, negative counts and impossible geometry;
  unknown checks are errors, not passes.
- Fix even-length median, empty arrays, NaN and structured error handling in
  `pixels.py`; avoid converting millions of values into Python objects.
- Compare color channels in `image_diff.py` and define constant-image correlation.
- Make probe caches collision-resistant, versioned, atomic and invalidated by plugin
  changes.
- Make recipe search robust to malformed YAML and combine bonuses rather than
  overwriting them.
- Do not replay failed macros from session logs; use atomic writes and strong IDs.
- Isolate `train_agent.py`/`practice.py` from user images, Results, ROIs and settings;
  seed generated examples and verify the actual outcome.

### UI, settings and terminal behavior

- Settings Cancel must be transactional. Saving must refresh/recreate live backends.
- Clearing a conversation must clear both visible HTML and backend/local context.
- Remove posture-listener leaks and terminal session cross-talk.
- Surface PTY write failures instead of clearing the prompt and claiming success.
- Bound chat, audit and terminal buffers; avoid full phrasebook sorts on the Swing
  event thread.
- Make model refresh concurrent, cancellable and immune to stale completion races.
- Move credential validation/install work off the Swing thread and never save a
  rejected key.
- Disable terminal controls without a live session.
- Restore keyboard traversal/activation for chat, model picker, provider cards,
  receipts and session history.
- Pair custom backgrounds with explicit readable foregrounds and visible focus.
- Dispose single-use decision dialogs rather than hiding them.

### Local Assistant and phrasebook

- Thirty phrasebook intent IDs (1,410 phrases) have no Java handler.
- The documented phrasebook generator removes 314 IDs unless `--keep` is passed.
- Replace repeated full sorting/fuzzy cross-products with precomputed normalized data
  and bounded top-candidate selection off the Swing event thread.
- Make generated intent coverage a build-time equality gate.

## Build, distribution, documentation and tests

### Safe test discovery and coverage

- Add pytest configuration so the default suite discovers only offline tests.
- Move/rename `agent/test_count.py` as an explicit live-Fiji smoke test with assertions
  and cleanup.
- Put every action in `test-scripts/zero_polling_test.py` behind an explicit entry
  point; collection must have zero side effects.
- Run offline Python tests and Java integration-profile tests in CI.
- Standardize Java integration tests on one JUnit generation/tagging mechanism.
- Add live loopback coverage for the real `agent/ij.py` transport, not only mocked
  wrapper dictionaries; remove the duplicate `imagej_events` definition.
- Missing tracked context snapshots are failures; updates require an explicit command.
- Replace flaky sleeps/performance ceilings in unit tests with clocks/schedulers and
  separate benchmarks.
- Add direct lifecycle tests for CommandEngine, JobRegistry, PipelineBuilder,
  StateInspector, ReactiveEngine and PromptWatcher.

### Build and lab distribution

- `build.sh` must run tests by default, show diagnostics and require an explicit
  `--skip-tests` escape hatch.
- Verify a fresh artifact and its hash before removing/replacing an installed JAR.
- Make JAR output reproducible; do not package dependency Maven metadata contrary to
  the documented artifact policy.
- Complete third-party notices for shaded dependencies.
- Require Python 3.10–3.13 and install bundle dependencies in a dedicated environment,
  not global Python.
- Build the lab workspace from an allowlist and fail on secret/private-file patterns.
- Do not deploy or close Fiji during this repair unless explicitly requested.

### Canonical API and release metadata

- Create one machine-readable command manifest that drives server descriptors, Python
  wrappers/coverage tests and generated documentation. Today the server has about 60
  commands, README lists 22, and `ij.py` wraps about 43 while claiming all commands.
- Correct `methods_table.py`'s hard-coded `1.0.0-pre` provenance.
- Synchronize Maven, `Constants`, `CITATION.cff`, README and user/developer docs on
  version `0.3.0`, artifact name and Java 11+ runtime.
- Refresh the reference index count and make generation deterministic/non-hanging.

## Graphify hook parity with FLASH/PULSE

The current post-commit/post-checkout hooks use a private graphify API, rebuild too
broadly and block Git operations. Add one shared hook runner that:

- filters code/document suffixes before scheduling work;
- uses public `python -m graphify update`/build commands;
- runs detached with UTF-8 logging;
- uses a lock, debounce and stale-lock recovery so concurrent edits/commits/deploys do
  not start competing graph writes;
- supports Git post-commit/post-checkout plus `.codex`/`.claude` post-edit triggers;
- is invoked once after a successful build/deploy, not once per copied artifact;
- records graphify-version drift and fails visibly without blocking normal Git work.

The local `.git/hooks` copies are installation state and are not committed. Tracked
scripts/configuration must be sufficient to reinstall them.

## Browser-use-style ImageJ control

The useful browser-use pattern is a persistent target plus a one-command, preloaded
stdin runner. ImageJAI already has the persistent target: Fiji and its loopback TCP
server. Add `imagej-use-auto` with:

- a session-oriented `ImageJSession` built on the repaired `agent/ij.py` transport;
- a standard-input Python runner with helpers pre-imported;
- `--doctor` diagnostics for Fiji reachability, protocol version, authentication,
  workspace and screenshot support;
- screenshot-to-path helpers and event waits;
- an optional workspace helper module for user-specific conveniences;
- packaging and AgentLauncher/context documentation.

Do not copy browser coordinate clicking, generic Robot automation, a second daemon,
automatic Fiji startup/shutdown, remote listening, or browser profile/tab concepts.
Plugin dialogs should continue through semantic dialog inspection/action commands.

## Findings explicitly rejected or narrowed

- TCP is loopback-only; remote exposure was not confirmed.
- Subscriber count and per-subscriber frames are bounded; privacy/authentication is
  the defect.
- Registry retention is bounded; concurrently running job threads are not.
- Native Ollama vision attachment works; provider-client adapters return too early.
- Abort APIs are invoked in some paths; worker termination/ownership remains wrong.
- Results are not wiped/replayed during undo; they are simply not restored while the
  reply implies otherwise.
- Maven unit tests are green; excluded integration coverage and Python failures remain.

## Implementation ordering

1. Make test discovery safe and install non-blocking graph hooks.
2. Repair the session/authentication contract and real client transport tests.
3. Put subscriptions through the governance envelope.
4. Introduce the single mutation coordinator and migrate execution surfaces.
5. Repair undo/exploration and image-state correctness.
6. Fix provider policy and recipe execution in parallel.
7. Harden persistence, helpers, contexts, UI and performance in independent waves.
8. Add `imagej-use-auto` on top of the stable session client.
9. Finish canonical API/version/build/distribution documentation and comprehensive
   release gates.

Each implementation stage must add a regression test that fails on the reviewed
defect. A stage is not complete because code compiles; its exit gate must exercise the
relevant failure path.
