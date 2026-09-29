# Live smoke test for the ImageJ-AI test automation bridge.
#
# Runs test-scripts/automation_bridge_smoke.java in a throwaway JVM with:
#   * a fresh isolated user.home, so the installation token is generated in a
#     temp directory and the user's ~/.imagejai is never read or written;
#   * a fresh isolated automation workspace and ready file;
#   * the real startup system properties, so AutomationPolicy is parsed for
#     real rather than through a test seam.
#
# It never touches Fiji.app, never deploys a JAR, and never connects to a Fiji
# that is already running: it starts its own TCP server on port 0.
#
#   powershell -ExecutionPolicy Bypass -File test-scripts\automation_bridge_smoke.ps1
#
# Requires: mvn test-compile has been run, and JAVA_HOME points at a JDK 11+.

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
Set-Location $project

if (-not $env:JAVA_HOME) { throw 'JAVA_HOME is not set' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) { throw "no java at $java" }

$mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mavenCommand) { throw 'Maven is not on PATH' }
$mvn = $mavenCommand.Source

$run = Join-Path ([System.IO.Path]::GetTempPath()) ("imagejai-smoke-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$home_ = Join-Path $run 'home'
$workspace = Join-Path $run 'workspace'
New-Item -ItemType Directory -Force -Path $home_, $workspace | Out-Null
$ready = Join-Path $workspace 'imagejai-ready.json'

$cpFile = Join-Path $run 'cp.txt'
& $mvn -q dependency:build-classpath "-Dmdep.outputFile=$cpFile" '-Denforcer.skip=true' | Out-Null
$classpath = (Get-Content $cpFile -Raw).Trim()
$classpath = "target\classes;target\test-classes;$classpath"

try {
    & $java `
        "-Duser.home=$home_" `
        '-Dimagejai.testAutomation.enabled=true' `
        "-Dimagejai.testAutomation.workspace=$workspace" `
        "-Dimagejai.testAutomation.readyFile=$ready" `
        '-Dimagejai.testAutomation.port=0' `
        '-cp' $classpath `
        'test-scripts\automation_bridge_smoke.java'
    $code = $LASTEXITCODE
} finally {
    Remove-Item -Recurse -Force $run -ErrorAction SilentlyContinue
}

exit $code
