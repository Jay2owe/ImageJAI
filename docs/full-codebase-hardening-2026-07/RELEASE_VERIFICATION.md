# ImageJAI 0.3.0 release verification

This is the final offline/private-distribution gate record, not a public-release
claim. The optional live-Fiji check was not authorized and is recorded as not
run rather than inferred from the headless gates.

## Source and environment

| Item | Recorded value |
|---|---|
| Verified code revision | `c2ebfe9229c7a59aed16b6628508c538a8671cbc` |
| Verification timestamp and timezone | 2026-07-17T00:09:29+01:00 (Europe/London) |
| Operating system | Microsoft Windows 11 Home, amd64, NT 10.0.26200 |
| Java and Maven versions | Oracle JDK 25.0.2; Apache Maven 3.9.9 |
| Python version | CPython 3.13.14 |
| Working tree clean before gate | Yes, at the recorded code revision; this evidence-only document update followed the completed gates |

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
| Java unit suite and main JAR | PASS | 1,185 tests in 150 suites; 0 failures/errors/skips in the final clean builds; exact main JAR built |
| Java integration profile | PASS | 3 tests in 2 suites; 0 failures/errors/skips |
| Direct lifecycle suite | PASS | 40 tests in 6 suites; 0 failures/errors/skips |
| Offline Python and build/install suite | PASS | 832 tests; 0 failures/skips |
| Real `ij.py`/`imagej-use-auto` loopback tests | PASS | 79 tests, including authenticated operation polling, snapshot binding, and confirmation race/cleanup coverage |
| Recipe contract | PASS | 8 tests |
| Context, manifest, and generated docs | PASS | 134 tests; both generators byte-current |
| Pixel, scientific, and transport regressions | PASS | Final focused 90-test integration pass plus the authoritative 832-test suite; exact ImageJ Triangle behavior, RGB scalarization, C/Z/T provenance, crop identity, and little-endian pixel encoding are covered |
| Graphify hook tests and rebuild | PASS | 18 tests; the post-build public full-update exited 0 at the exact code revision, drained pending requests, handoffs, worker claim, writer lock, and temporary files, and produced 29,321 nodes, 52,561 edges, 2,070 communities, and 15 hyperedges |
| Build and simulated-install tests | PASS | 3 pytest build/install tests and 13 Pester bundle transaction tests |
| Tested non-deploy build script | PASS | Git for Windows `build.sh --no-deploy`; 1,185 tests; JAR hash matched the reproducibility pair; output explicitly disabled deployment; Graphify hook exited 0 |
| Two-build JAR reproducibility | PASS | consecutive clean builds and `build.sh --no-deploy` all produced `BF9CD1C40164AE28877628C5B13DB4AA04C639C3912789D35758802924D83E1F` |
| JAR content/policy inspection | PASS | 14,866,401 bytes and 5,668 entries; 0 `META-INF/maven/**`; exactly 1 packaged command manifest; source/package manifest SHA-256 `C742CA932545BE93543C77C78E50D94E6F963B53B9A436A8DA1B4C519E18CB32` |
| Temporary-root lab-bundle inspection | PASS | 186 allowlisted agent files; exactly 190 ZIP members with 0 exact/case-insensitive duplicates; secret/path scan passed; 15,274,059-byte ZIP SHA-256 `361041F099A844D9095B69B0BFCDEC86154DC6DD0E2D910E1D945CFEA34A66CA`; source/shared/disposable-local/ZIP JAR hashes matched; 5 shared files, 1 disposable local JAR, staging residue 0; temporary root removed; real Fiji and lab share untouched |
| Undo calibration anomaly investigation | PASS / NOT REPRODUCIBLE | One early clean-package run reported `0.42` restored as `1.0`; production and ImageJ bytecode traces found no path, then 100 fresh JVM runs (1,200 tests), 30 predecessor-order JVM runs (510 tests), a full 1,185-test suite, and all later clean builds passed |
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
python -m pytest agent/test_ij_api.py agent/test_imagej_use.py agent/test_gui_confirm.py -q
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
$javaVersionLine = (& mvn -version | Select-String '^Java version:').Line
if ($javaVersionLine -notmatch 'runtime:\s*(.+)$') {
    throw "Could not resolve the Maven Java runtime"
}
$javaHome = $Matches[1].Trim()
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
$staging = Join-Path $gateRoot "staging"
New-Item -ItemType Directory -Force -Path $shared, $fakeFiji, $staging | Out-Null
try {
    powershell -ExecutionPolicy Bypass -File scripts/make_lab_bundle.ps1 `
        -Version 0.3.0 -SharedRoot $shared -LocalFijiPlugins $fakeFiji `
        -StagingParent $staging
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
- Verifier: root coordinator plus fresh sequential verifier passes through iteration 15; iteration 15 converged with 0 accepted and 0 uncertain findings
