# ImageJAI 0.3.0 release verification

This is the final offline/private-distribution gate record, not a public-release
claim. The optional live-Fiji check was not authorized and is recorded as not
run rather than inferred from the headless gates.

## Source and environment

| Item | Recorded value |
|---|---|
| Git revision | `a231d70ded4dd2c873d2af3ac4d316fb8f680bba` |
| Verification timestamp and timezone | 2026-07-16T13:41:14+01:00 (Europe/London) |
| Operating system | Microsoft Windows 11 Home, amd64, NT 10.0.26200 |
| Java and Maven versions | Oracle JDK 25.0.2; Apache Maven 3.9.9 |
| Python version | CPython 3.13.14 |
| Working tree clean before gate | Code/tests clean at the recorded revision; this verification file was intentionally untracked while results were recorded |

Record the environment with:

```powershell
git rev-parse HEAD
git status --short
java -version
mvn -version
python --version
```

## Gate results

| Gate | Result | Count, hash, or evidence |
|---|---|---|
| Java unit suite and main JAR | PASS | 1,129 tests in 146 suites; 0 failures/errors/skips; exact main JAR built |
| Java integration profile | PASS | 3 tests in 2 suites; 0 failures/errors/skips |
| Direct lifecycle suite | PASS | 31 tests; 0 failures/errors/skips |
| Offline Python suite | PASS | 673 tests |
| Real `ij.py`/`imagej-use-auto` loopback tests | PASS | 61 tests, including event-close, plane-metadata, and confirmation race/cleanup coverage |
| Recipe contract | PASS | 8 tests |
| Context, manifest, and generated docs | PASS | 134 tests; both generators byte-current |
| Pixel metadata and documentation regressions | PASS | 34 tests; strict C/Z/T identity and corrected public signatures covered |
| Graphify hook tests and rebuild | PASS | 16 tests; the 38-path incremental event and post-build public full-update event exited 0, drained all queue/lock/handoff state, and produced 29,040 nodes, 50,640 edges, 2,056 communities, and 15 hyperedges at the exact code commit; all 17 checked changed identities occur exactly once at current locations with no stale-node resurrection |
| Build and simulated-install tests | PASS | 3 pytest build/install tests and 13 Pester bundle transaction tests |
| Tested non-deploy build script | PASS | Git for Windows `build.sh --no-deploy`; 1,129 tests; JAR hash matched the reproducibility pair; Graphify hook exited 0 |
| Two-build JAR reproducibility | PASS | build 1: `4DE609DD7919C56C0321F5CC56507B38D995A9E52A5B51BDEB4DA370071127AD`; build 2 and `build.sh`: same |
| JAR content/policy inspection | PASS | 14,807,578 bytes and 5,643 entries; 0 `META-INF/maven/**`; exactly 1 packaged command manifest; source/package manifest SHA-256 `167A395696F5464BE9C7428530BDD25549B59A0823FA25261CEB7C0AEB1A388E` |
| Temporary-root lab-bundle inspection | PASS | 186 allowlisted agent files; exactly 190 unique ZIP members; secret scan emitted no warning or failure; 15,195,336-byte ZIP SHA-256 `78B8D2C2B69723422321BF6DD584480B357816D2141E59CBDA7E0F33BD90D9C2`; source and ZIP-contained JAR hashes matched; 5 shared files, local Fiji publication deliberately skipped, staging residue 0; published and removed only under the disposable root |
| Live Fiji doctor/smoke test | NOT RUN | Run only when explicitly authorized |

## Exact verification commands

Run from the repository root in PowerShell. Stop at the first non-zero exit.

### Java unit, integration, and lifecycle gates

```powershell
mvn -q clean package "-Denforcer.skip=true"
mvn -q "-Denforcer.skip=true" "-Pintegration" verify
mvn -q "-Denforcer.skip=true" "-Dtest=CommandEngineLifecycleTest,JobRegistryResourceBoundsTest,PipelineBuilderTest,StateInspectorTest,ReactiveEngineTest,TerminalReliabilityTest" test
```

Record Surefire and Failsafe test, failure, error, and skipped totals. The
integration profile is not interchangeable with the default unit suite.

### Offline Python and generated-contract gates

```powershell
python -m pytest agent scripts/tests -q
python -m pytest agent/test_ij_api.py agent/test_imagej_use.py -q
python -m pytest agent/test_recipe_contract.py -q
python -m pytest agent/contexts/test_contexts.py agent/test_command_manifest.py agent/test_docs_generation.py -q
python -m pytest agent/test_graphify_hook.py -q
python agent/generate_command_docs.py --check
python agent/generate_reference_index.py --check
```

The first command is the authoritative offline total. The narrower commands
provide separately auditable evidence for release-critical contracts.

### Tested local build script

```powershell
bash build.sh --no-deploy
```

If no working Bash runtime is available, record this gate as `NOT RUN` with
the reason. Do not infer a pass from the Maven command.

### Two-build reproducibility and JAR policy

Run both builds in the same PowerShell process so the first hash remains in
memory across the second `clean`:

```powershell
mvn -q clean package "-Denforcer.skip=true"
$artifact = "target/imagej-ai-0.3.0.jar"
$first = (Get-FileHash -Algorithm SHA256 -LiteralPath $artifact).Hash
mvn -q clean package "-Denforcer.skip=true"
$second = (Get-FileHash -Algorithm SHA256 -LiteralPath $artifact).Hash
if ($first -ne $second) { throw "Non-reproducible JAR: $first != $second" }
"build_1_sha256=$first"
"build_2_sha256=$second"

$main = @(Get-ChildItem -LiteralPath target -File -Filter "imagej-ai-*.jar" |
    Where-Object { $_.Name -notmatch "(-sources|-tests|^original-)" })
if ($main.Count -ne 1) { throw "Expected one main JAR, found $($main.Count)" }
$javaHomeLine = (& mvn -version | Select-String '^Java home:').Line
$javaHome = $javaHomeLine.Substring($javaHomeLine.IndexOf(':') + 1).Trim()
$jarTool = Join-Path $javaHome 'bin\jar.exe'
$entries = @(& $jarTool tf $main[0].FullName)
if ($LASTEXITCODE -ne 0) { throw "jar inspection failed" }
if ($entries | Where-Object { $_ -like "META-INF/maven/*" }) {
    throw "Prohibited META-INF/maven content found"
}
if ($entries -notcontains "imagejai/command_manifest.json") {
    throw "Packaged command manifest missing"
}
```

Record both SHA-256 values verbatim even when they match.

### Simulated install and private lab bundle

The automated failure-preservation and allowlist tests must pass first:

```powershell
python -m pytest scripts/tests -q
powershell -NoProfile -ExecutionPolicy Bypass -Command `
    "Invoke-Pester -Script 'scripts/tests/make_lab_bundle.Tests.ps1' -EnableExit"
```

Then exercise the real bundler only against disposable temporary roots. This
must not point at the shared lab folder or a real Fiji installation:

```powershell
$gateRoot = Join-Path ([IO.Path]::GetTempPath()) ("imagejai-release-gate-" + [guid]::NewGuid())
$shared = Join-Path $gateRoot "shared"
$fakeFiji = Join-Path $gateRoot "Fiji.app/plugins"
New-Item -ItemType Directory -Force -Path $shared, $fakeFiji | Out-Null
try {
    powershell -ExecutionPolicy Bypass -File scripts/make_lab_bundle.ps1 `
        -Version 0.3.0 -SharedRoot $shared -LocalFijiPlugins $fakeFiji
    if ($LASTEXITCODE -ne 0) { throw "temporary-root bundle gate failed" }
} finally {
    if (Test-Path -LiteralPath $gateRoot) {
        Remove-Item -LiteralPath $gateRoot -Recurse -Force
    }
}
```

Record the bundle filename, JAR hash comparison, allowlist/secret scan result,
Python 3.10-3.13 environment evidence, and simulated copy-failure evidence.

## Optional live-Fiji gate

Run only with explicit authorization and a deliberately opened test image:

```powershell
Push-Location agent
try {
    python -m imagej_use.run --doctor
} finally {
    Pop-Location
}
```

Record Fiji/ImageJ version, Java runtime, plugin version, test-image identity,
and doctor output. If Fiji is unavailable or the test is not authorized, leave
the result `NOT RUN`; do not write `PASS`.

## Final decision

- Release gate decision: PASS for offline/private lab-distribution gates
- Blocking failures: none
- Explicitly unrun optional checks: live Fiji doctor/smoke test
- Verifier: root coordinator plus sequential verifier iterations 1 through 7; fresh iteration 8 follows this record
