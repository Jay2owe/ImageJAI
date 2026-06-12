# Pre-public-release hygiene sweep.
# Removes Dropbox/OneDrive conflicted-copy artefacts, JVM crash dumps,
# OS clutter, and per-run scratch caches that must not appear in a public
# repository or release tag. Idempotent: safe to run repeatedly.
#
# Usage (from repo root):
#   pwsh ./scripts/clean_for_public_release.ps1            # dry run, lists candidates
#   pwsh ./scripts/clean_for_public_release.ps1 -Apply     # delete for real
#
# The script never touches files staged or tracked by git unless their name
# matches one of the explicit patterns below.

[CmdletBinding()]
param(
    [switch]$Apply,
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "ImageJAI public-release cleanup" -ForegroundColor Cyan
Write-Host ("Repo root: " + $RepoRoot)
Write-Host ("Mode:      " + $(if ($Apply) { "APPLY (deleting)" } else { "DRY RUN (no changes)" })) -ForegroundColor $(if ($Apply) { "Yellow" } else { "Green" })
Write-Host ""

# Patterns that must never ship.
# Globs are matched against full paths, case-insensitively.
$DeleteGlobs = @(
    "*conflicted copy*",          # Dropbox conflict markers
    "*conflicted-copy*",          # OneDrive variant
    "*-Conflict.*",               # Sync.com / generic
    "hs_err_pid*.log",            # JVM crash dumps
    "replay_pid*.log",            # JIT replay companions
    "*.stackdump",                # cygwin / MSYS stack dumps
    "*.hprof",                    # JVM heap dumps
    ".DS_Store",                  # macOS Finder cruft
    "Thumbs.db",                  # Windows Explorer cruft
    "Desktop.ini",                # Windows folder metadata
    "*.swp",                      # vim swap
    "*.swo"                       # vim swap (alt)
)

# Top-level scratch directories that should never be in the public tree.
# Removed wholesale (NOT pattern-matched against children) when present.
$DeleteDirs = @(
    "agent/.tmp",
    ".tmp/brainstorm-reports"
)

# Collect candidates.
$candidates = New-Object System.Collections.Generic.List[System.IO.FileSystemInfo]

foreach ($glob in $DeleteGlobs) {
    Get-ChildItem -Path $RepoRoot -Recurse -Force -File -Filter $glob -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.FullName -notmatch '\\\.git\\') {
            $candidates.Add($_)
        }
    }
}

foreach ($dir in $DeleteDirs) {
    $abs = Join-Path $RepoRoot $dir
    if (Test-Path $abs) {
        $candidates.Add((Get-Item $abs))
    }
}

if ($candidates.Count -eq 0) {
    Write-Host "Nothing to clean. Repo is already release-ready." -ForegroundColor Green
    exit 0
}

# Print candidates.
Write-Host ("Found " + $candidates.Count + " item(s) to remove:") -ForegroundColor Yellow
foreach ($c in $candidates) {
    $rel = $c.FullName.Substring($RepoRoot.Length).TrimStart('\','/')
    $tag = if ($c.PSIsContainer) { "DIR " } else { "FILE" }
    Write-Host ("  [" + $tag + "] " + $rel)
}

if (-not $Apply) {
    Write-Host ""
    Write-Host "Dry run complete. Re-run with -Apply to delete." -ForegroundColor Cyan
    exit 0
}

Write-Host ""
$failed = 0
foreach ($c in $candidates) {
    try {
        if ($c.PSIsContainer) {
            Remove-Item -LiteralPath $c.FullName -Recurse -Force -ErrorAction Stop
        } else {
            Remove-Item -LiteralPath $c.FullName -Force -ErrorAction Stop
        }
    } catch {
        $failed++
        Write-Warning ("Failed to delete: " + $c.FullName + " (" + $_.Exception.Message + ")")
    }
}

if ($failed -gt 0) {
    Write-Host ""
    Write-Host ("Cleanup completed with " + $failed + " failure(s). Investigate before tagging.") -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Clean. Re-run before each tag/release." -ForegroundColor Green
