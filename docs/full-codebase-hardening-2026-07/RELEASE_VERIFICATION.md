# ImageJAI 0.3.0 release verification

This is the final offline/private-distribution gate record, not a public-release
claim. The optional live-Fiji check was not authorized and is recorded as not
run rather than inferred from the headless gates.

## Source and environment

| Item | Recorded value |
|---|---|
| Git revision | `888137b8ea2bef02b8196fb18be66ca1fb3b4ff7` |
| Verification timestamp and timezone | 2026-07-16T09:23:56+01:00 (Europe/London) |
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
| Java unit suite and main JAR | PASS | 1,095 tests in 144 suites; 0 failures/errors/skips; exact main JAR built |
| Java integration profile | PASS | 3 tests in 2 suites; 0 failures/errors/skips |
| Direct lifecycle suite | PASS | 31 tests; 0 failures/errors/skips |
| Offline Python suite | PASS | 603 tests |
| Real `ij.py`/`imagej-use-auto` loopback tests | PASS | 28 tests |
| Recipe contract | PASS | 8 tests |
| Context, manifest, and generated docs | PASS | 134 tests; both generators byte-current |
| Graphify hook tests and rebuild | PASS | 10 tests; post-commit rebuild exited 0 with 30,168 nodes, 51,595 edges, and 2,194 communities |
| Build and simulated-install tests | PASS | 3 pytest build/install tests and 11 Pester bundle transaction tests |
| Tested non-deploy build script | PASS | Git Bash `build.sh --no-deploy`; 1,095 tests; JAR hash matched the reproducibility pair; Graphify hook exited 0 |
| Two-build JAR reproducibility | PASS | build 1: `EF497B509F1B68148B082249DCACB65FF70B27AB44A86E99205DCB1DBD7D1FE1`; build 2: same |
| JAR content/policy inspection | PASS | 14,789,634 bytes and 5,637 entries; 0 `META-INF/maven/**`; exactly 1 packaged command manifest; source/package manifest SHA-256 `58B7A7ACD747AC79859A1209B46F90CF5D1BA70383A06325EE2221E0D684D426` |
| Temporary-root lab-bundle inspection | PASS | 186 allowlisted agent files; exactly 190 unique ZIP members; secret scan passed; source/shared/local/ZIP-contained JAR hashes matched; 5 shared files and 1 local JAR published only under the disposable root |
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
- Verifier: root coordinator plus sequential verifier iterations 1, 2, and 3; fresh iteration 4 follows this record
