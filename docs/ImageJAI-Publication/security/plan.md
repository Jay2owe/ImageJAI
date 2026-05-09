# Security Plan

Aggregated bullets for security across the four pair-plans. Source-pair
tags: `(req)`, `(arch)`, `(risk)`, `(creative)`, `(joint)`.

Verified ground truth (re-confirmed 2026-05-08):

- `TCPCommandServer.java:603` originally `serverSocket = new ServerSocket(port);`
  (wildcard 0.0.0.0 bind, no auth). Patched to loopback in
  [`patches/2026-05-08.md`](../patches/2026-05-08.md).
- Grep for `Origin|CSRF|nonce|secret|Bearer|Authorization|X-Requested`
  in `TCPCommandServer.java` -> 1 hit (a code comment); zero security-token
  / handshake-auth code prior to patch. The 6 `token` matches were
  `tokenBudget` cost-accounting only.
- `TCPCommandServer.java:1642` -> `c.safeMode = optBool(caps, "safe_mode", false);`
  (client-asserted, default false). Same default at L221. Patched server-side
  to default true.
- `TCPCommandServer.java:2850` -> `IJ.runMacro(codeToRun);` direct call,
  no sandbox.
- `TCPCommandServer.java:63-65` -> `import javax.script.ScriptEngine;` -
  `run_script` exposes Groovy/Jython/JS in-JVM with full reflection +
  ProcessBuilder.
- Grep `getCanonicalPath|toRealPath` in `TCPCommandServer.java` -> 0 matches.
- `handleGetMetadata` at L4328 returns `imp.getProperty("Info")` raw OME-XML
  to the agent -> indirect-prompt-injection surface.
- `docs/safe_mode_v2/00_overview.md` L20-22 verbatim: *"agents can
  trivially route around the guard via `batch`, `run` (chain), `intent`,
  `run_pipeline`, `run_script`, `interact_dialog`, `branch_delete`,
  `rewind`."*

## Phase 0 - code patches (in [`patches/2026-05-08.md`](../patches/2026-05-08.md))

- [x] **Bind to loopback** - `TCPCommandServer.java:603`. Effort: S. (risk)
- [x] **Default `safe_mode=true` server-side** - `TCPCommandServer.java:221`
  and `:1642`. Effort: S. (risk)
- [x] **Shared-token auth in `hello` handshake** - opt-in via env var; token
  written to `~/.imagejai/server-token`. `agent/ij.py` updated to read it.
  Effort: M. (risk)
- [ ] **`getCanonicalPath` guard for `AI_Exports/` writes (DEFERRED)** -
  scoped, NOT shipped 2026-05-08. Touches 12+ Java sources. Will land as a
  standalone PR once Patch 5 cleans the conflicted-copy duplicates. Proposed
  helper: `imagejai.engine.io.AiExportsPaths.resolveSafe(...)`. Regression
  test plants `relative = "../../etc/x"` and asserts the helper throws.
  Effort: M. (risk)
- [x] **Repo-clean script** - `scripts/clean_for_public_release.ps1` and
  `.sh`; default dry-run; `-Apply` deletes conflicted-copy markers, JVM
  crash dumps, OS clutter. Effort: S. (risk)
- [ ] **Default-deny `run_script`** - gate behind explicit env-var
  `IMAGEJAI_ALLOW_RUN_SCRIPT=1` until sandboxing lands. Imports remain;
  handler returns `SCRIPT_DISABLED` unless flag set. Effort: S. (risk)
- [ ] **Default-deny non-loopback `bind`** - even if `--bind` is added later,
  refuse non-loopback unless `--i-accept-the-risk-of-rce` flag passed and
  token-with-rotation policy configured. Effort: S. (risk)
- [x] **Agent-context sanitiser at 5 boundary points** - **Implemented
  2026-05-09**. `imagejai.engine.security.AgentContextSanitizer` wraps
  externally-sourced text in tagged envelopes at `handleGetMetadata`
  (info + per-key properties), `handleGetLog`, `handleGetDialogs`, and
  `handleGetConsole`. Strips C0/C1, caps at 8 KB UTF-8. 27-test corpus at
  `src/test/java/imagejai/engine/security/AgentContextSanitizerTest.java`.
  Design doc: [`agent_context_sanitization.md`](agent_context_sanitization.md);
  paper supplement: [`../supplements/adversarial_corpus.md`](../supplements/adversarial_corpus.md).
  Lands D7 from [`../decisions.md`](../decisions.md). Effort: M. (risk)

## Phase 1 - submission-blocking docs

- [ ] `security/threat_model.md` - STRIDE table; OWASP LLM Top 10 mapping
  (LLM01 prompt injection, LLM02 insecure output handling, LLM06 sensitive
  info disclosure, LLM07 insecure plugin design, LLM08 excessive agency).
  Code anchors: `TCPCommandServer.java:603`, `:2850`, `:1642`, `:4328`.
  Cite Quarkus CVE-2022-4116 (dev-mode unauth localhost RCE), MixPHP
  CVE-2026-37552, Anthropic MCP localhost-RCE advisory, OWASP A07:2021.
  Effort: L. (risk)
- [ ] `security/drive_by_localhost.md` - attack walkthrough: malicious
  webpage POSTs to `http://127.0.0.1:7746` while user browses unrelated
  site. Cite Tavis Ormandy 2019 Trend Micro password-manager RCE; Project
  Zero "drive-by attacks against local services". Mitigation: token +
  Origin pin + connect-rate-limit. Effort: M. (risk)
- [ ] `security/dns_rebinding.md` - `*.localhost.attacker.com` -> 127.0.0.1
  rebind after DNS TTL flip; bypasses same-origin if server doesn't
  validate `Host:` header. ImageJAI is line-delimited-JSON not HTTP, so
  reduced surface, but document the analysis. Cite Singularity tool, NCC
  Group "DNS Rebinding Headless Browsers". Effort: S. (risk)
- [ ] `security/auth_design.md` - Phase 0 token scheme spec; rotation
  policy; per-session salt; FAQ on why we did not pick mTLS /
  Unix-domain-socket / SO_PEERCRED. Effort: M. (risk)
- [ ] `security/client_capability_assertion.md` - describe the L1642 bug:
  client-asserted `safe_mode` is authentication theatre. Document the fix
  (server default-true) and the remaining problem (the SafeMode v2
  bypass list). Effort: S. (risk)
- [ ] `security/imaging_metadata_injection.md` - code anchor
  `handleGetMetadata` L4328 returns `imp.getProperty("Info")` raw OME-XML.
  Threat: attacker-supplied `.tif`/`.czi` carries crafted XML / Markdown
  in image description that becomes indirect prompt injection. Cite
  Greshake et al. arXiv 2302.12173, Microsoft "Indirect prompt injection
  in agentic AI" (Apr 2025), Simon Willison's series. Mitigation: strip
  control chars, length-cap, JSON-escape, surface as `metadata_summary`
  not raw blob. Effort: M. (risk)
- [ ] `security/run_macro_capabilities.md` - enumerate everything ImageJ
  macro language can do via `eval("script", ...)`, `exec()`, `call()`,
  file I/O, network I/O. "Macro = full process power as user". Effort: M.
  (risk)
- [ ] `security/sandboxing_options.md` - Java SecurityManager removed in
  JDK 24+ (JEP 411 deprecated, JEP 486 finalised removal). Survey: process
  isolation (separate JVM), AppArmor / Windows AppContainer / macOS
  sandbox-exec, seccomp-bpf, Docker rootless, RestrictedSecurity (IBM
  Semeru). Decision: process isolation roadmap, not v1. Effort: M. (risk)
- [ ] `security/agentlauncher_argv_audit.md` - audit `AgentLauncher.java`
  argv assembly for shell-injection (any `Runtime.exec(String)` vs
  `String[]`, env-var passthrough, working-dir traversal). Effort: M. (risk)
- [ ] `security/path_traversal_audit.md` - code-anchor every
  save/read/load/export call site. Specify guard pattern + add unit tests
  `test/security/PathTraversalTest.java`. Effort: M. (risk)
- [ ] `security/screenshot_scope.md` - what does the screenshot handler
  capture? Whole desktop vs Fiji window only? PII / other-app exposure
  risk. Recommend window-bounded capture only. Effort: S. (risk)
- [ ] `security/recipe_provenance.md` - `agent/recipes/` 27 YAML files.
  Are they signed? Hash-pinned? Source-trusted? Decision: ship sha256
  manifest, refuse unknown recipes by default. Effort: S. (risk)
- [ ] `security/plugin_trust_levels.md` - Fiji has 330+ update sites;
  agent-installable plugins == arbitrary code. Spec: explicit trust list,
  no `install-tools-from-prompt`. Cite Trail of Bits "Tool poisoning /
  line-jumping" (Apr 2025). Effort: S. (risk)
- [ ] `security/clinical_data_policy.md` - explicit ban on PHI through
  cloud LLMs. **BAA matrix** for Anthropic, OpenAI, Google, Azure OpenAI;
  status as of 2026-05; coverage gaps; recommended local-only path
  (Ollama gemma:31b-cloud is *not* HIPAA-covered - clarify). Cite HHS
  HIPAA Security Rule, OCR guidance on AI. Effort: M. (risk)
- [ ] `security/local_model_audit.md` - Ollama digest pinning, model card
  hash, weights provenance, `ollama show --modelfile` output committed,
  supply-chain attestation. Effort: S. (risk)
- [ ] `security/process_isolation.md` - roadmap for v2: separate JVM per
  agent session, kill-switch via watchdog, FD limits, ulimit -t. Effort: M.
  (risk)
- [ ] `security/responsible_disclosure.md` - `SECURITY.md` at repo root +
  content here: contact email, PGP key, 90-day window, scope, hall-of-fame.
  Effort: S. (risk)
- [ ] `security/cve_inventory.md` - running list of vulns we found and
  fixed in our own code. Start with: `IMAGEJAI-2026-001` wildcard-bind,
  `IMAGEJAI-2026-002` client-asserted safe-mode, `IMAGEJAI-2026-003` no
  auth, `IMAGEJAI-2026-004` raw OME-XML to agent, `IMAGEJAI-2026-005` no
  path canonicalisation. Effort: M. (risk)
- [ ] `security/safemode_routability_gap.md` - verbatim quote from
  `docs/safe_mode_v2/00_overview.md` L20-22; map each bypass route
  (`batch`, `run`, `intent`, `run_pipeline`, `run_script`, `interact_dialog`,
  `branch_delete`, `rewind`) to current status. Effort: S. (risk)
- [ ] `security/bug_bounty.md` - public bounty program; deps on loopback
  + auth shipped first. Effort: S. (creative)

## Cross-folder dependencies

- Path-traversal helper class blocks the deferred Patch 4; cross-ref
  [`patches/`](../patches/).
- Clinical-data BAA matrix mirrored summarily in
  [`limitations/limitations_for_clinical_use.md`](../limitations/) and
  [`ethics/hipaa_disclosure.md`](../ethics/).
- Safe-mode efficacy red-team measurements are in
  [`bench/`](../bench/) - this folder owns the threat model, the bench
  folder owns the measurements.
