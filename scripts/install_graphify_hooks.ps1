[CmdletBinding(SupportsShouldProcess = $true)]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gitDirText = (& git -C $repoRoot rev-parse --git-dir 2>$null | Select-Object -First 1)
if (-not $gitDirText) {
    throw "Not a Git repository: $repoRoot"
}
$gitDir = if ([System.IO.Path]::IsPathRooted($gitDirText)) {
    $gitDirText
} else {
    Join-Path $repoRoot $gitDirText
}
$hooksDir = Join-Path $gitDir 'hooks'
[System.IO.Directory]::CreateDirectory($hooksDir) | Out-Null
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$postCommit = @'
#!/bin/sh
# imagejai-graphify-hook
# Installed by scripts/install_graphify_hooks.ps1; edit the tracked runner instead.
ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
if command -v python >/dev/null 2>&1; then PYTHON=python
elif command -v python3 >/dev/null 2>&1; then PYTHON=python3
else exit 0
fi
git diff-tree --root --no-commit-id --name-only -r HEAD 2>/dev/null \
  | "$PYTHON" "$ROOT/scripts/graphify_hook.py" --event post-commit --paths-from-stdin
exit 0
'@

$postCheckout = @'
#!/bin/sh
# imagejai-graphify-hook
# Installed by scripts/install_graphify_hooks.ps1; edit the tracked runner instead.
[ "$3" = "1" ] || exit 0
ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
if command -v python >/dev/null 2>&1; then PYTHON=python
elif command -v python3 >/dev/null 2>&1; then PYTHON=python3
else exit 0
fi
git diff --name-only "$1" "$2" 2>/dev/null \
  | "$PYTHON" "$ROOT/scripts/graphify_hook.py" --event post-checkout --paths-from-stdin
exit 0
'@

foreach ($hook in @{
    'post-commit' = $postCommit
    'post-checkout' = $postCheckout
}.GetEnumerator()) {
    $path = Join-Path $hooksDir $hook.Key
    $content = $hook.Value.Replace("`r`n", "`n").TrimEnd() + "`n"
    if ($PSCmdlet.ShouldProcess($path, 'Install detached Graphify hook shim')) {
        [System.IO.File]::WriteAllText($path, $content, $utf8NoBom)
        Write-Host "Installed $path"
    }
}
