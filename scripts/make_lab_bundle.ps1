[CmdletBinding()]
param(
    [string]$Version = "0.3.0",
    [string]$SharedRoot = "C:\Users\Owner\UK Dementia Research Institute Dropbox\Brancaccio Lab\LabAdmin\Software\ImageJ\Plugins\Jamie's ImageJAI Assistant",
    [string]$LocalFijiPlugins = "C:\Users\Owner\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Fiji.app\plugins",
    [string]$ProjectRoot = "",
    [string]$StagingParent = "",
    [switch]$DryRun,
    [hashtable]$TestHooks = @{}
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function Get-FullPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    return [System.IO.Path]::GetFullPath($Path)
}

function Assert-PathUnderRoot {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$Description = "Path"
    )
    $fullPath = Get-FullPath $Path
    $fullRoot = (Get-FullPath $Root).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($fullRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description escapes its allowed root: $fullPath"
    }
    return $fullPath
}

function Assert-NotRedirected {
    param(
        [Parameter(Mandatory = $true)][System.IO.FileSystemInfo]$Item,
        [string]$Description = "Path"
    )
    $linkType = $Item.PSObject.Properties["LinkType"]
    $target = $Item.PSObject.Properties["Target"]
    $hasLinkType = $null -ne $linkType -and -not [string]::IsNullOrWhiteSpace([string]$linkType.Value)
    $hasTarget = $false
    if ($null -ne $target -and $null -ne $target.Value) {
        $targetValues = @(@($target.Value) | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
        $hasTarget = $targetValues.Count -gt 0
    }
    # Dropbox placeholders carry ReparsePoint/SparseFile without redirecting
    # to another path. Actual symlinks and junctions expose LinkType/Target.
    if ($hasLinkType -or $hasTarget) {
        throw "$Description must not be a symlink or junction: $($Item.FullName)"
    }
}

function Assert-SafeSourceFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root
    )
    $fullPath = Assert-PathUnderRoot -Path $Path -Root $Root -Description "Allowlisted source"
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "Required allowlisted agent file is missing: $fullPath"
    }
    $rootFull = (Get-FullPath $Root).TrimEnd('\', '/')
    $current = Get-Item -LiteralPath $fullPath -Force
    while ($null -ne $current) {
        Assert-NotRedirected -Item $current -Description "Allowlisted source"
        if ($current.FullName.TrimEnd('\', '/') -ieq $rootFull) {
            break
        }
        if ($current -is [System.IO.DirectoryInfo]) {
            $current = $current.Parent
        } else {
            $current = $current.Directory
        }
    }
    if ($null -eq $current) {
        throw "Allowlisted source could not be traced to the agent root: $fullPath"
    }
    return $fullPath
}

function Invoke-CopyFile {
    param([string]$Source, [string]$Destination)
    if ($TestHooks.ContainsKey("CopyFile")) {
        & $TestHooks["CopyFile"] $Source $Destination
        return
    }
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

function Invoke-MoveFile {
    param([string]$Source, [string]$Destination)
    if ($TestHooks.ContainsKey("MoveFile")) {
        & $TestHooks["MoveFile"] $Source $Destination
        return
    }
    Move-Item -LiteralPath $Source -Destination $Destination -Force
}

function Get-HashHex {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ($TestHooks.ContainsKey("HashFile")) {
        $hookResult = & $TestHooks["HashFile"] $Path
        if ($null -ne $hookResult -and $null -ne $hookResult.PSObject.Properties["Hash"]) {
            return ([string]$hookResult.Hash).ToUpperInvariant()
        }
        return ([string]$hookResult).ToUpperInvariant()
    }
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToUpperInvariant()
}

function Get-StreamHashHex {
    param([Parameter(Mandatory = $true)][System.IO.Stream]$Stream)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha.ComputeHash($Stream)
        return ([System.BitConverter]::ToString($bytes)).Replace("-", "")
    } finally {
        $sha.Dispose()
    }
}

function Write-Utf8 {
    param([string]$Path, [string]$Text)
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false))
}

function Copy-AllowlistedAgent {
    param(
        [string]$SourceRoot,
        [string]$DestinationRoot,
        [string[]]$RelativeFiles,
        [System.Collections.Generic.HashSet[string]]$ExpectedFiles
    )
    New-Item -ItemType Directory -Force -Path $DestinationRoot | Out-Null
    foreach ($relative in $RelativeFiles) {
        if ([string]::IsNullOrWhiteSpace($relative) -or [System.IO.Path]::IsPathRooted($relative)) {
            throw "Invalid agent allowlist entry: $relative"
        }
        $normal = $relative.Replace('\', '/')
        if ($normal.Split('/') -contains '..') {
            throw "Agent allowlist entry contains traversal: $relative"
        }
        $source = Assert-SafeSourceFile -Path (Join-Path $SourceRoot $relative) -Root $SourceRoot
        $destination = Join-Path $DestinationRoot $relative
        $destination = Assert-PathUnderRoot -Path $destination -Root $DestinationRoot -Description "Bundle destination"
        $parent = Split-Path -Parent $destination
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
        Invoke-CopyFile -Source $source -Destination $destination
        [void]$ExpectedFiles.Add("agent/$normal")
    }
}

function Test-SafeBundleTree {
    param(
        [string]$Root,
        [System.Collections.Generic.HashSet[string]]$ExpectedFiles,
        [string]$JarRelativePath
    )
    $forbiddenSegments = @(
        '.git', '.claude', '.codex', '.env', '.secrets', 'secrets', 'private',
        '.tmp', '__pycache__', '.pytest_cache', '.mypy_cache', '.plugin_cache',
        '.venv', 'venv', 'site-packages', 'dist', 'build', 'work_in_progress',
        '_spike', 'tests'
    )
    $forbiddenNames = @(
        '*.pyc', '*.pyo', '*.log', '*.stackdump', 'hs_err_*', 'OpenCL-log.txt',
        '*.bak', '*.premerge.*', '*.tmp', '*.tmp.*', 'proxy.auth', 'proxy.port',
        '*.pem', '*.key', '*.pfx', '*.p12', 'id_rsa*', 'credentials*', 'token*'
    )
    $textExtensions = @('.py', '.ps1', '.md', '.txt', '.toml', '.yaml', '.yml', '.json', '.groovy', '.clinerules', '.cursorrules')
    $highConfidenceSecrets = @(
        '(?<![A-Z0-9])AKIA[0-9A-Z]{16}(?![A-Z0-9])',
        '(?<![A-Za-z0-9])sk-ant-[A-Za-z0-9_-]{16,}',
        '(?<![A-Za-z0-9])sk-[A-Za-z0-9]{20,}',
        '(?<![A-Za-z0-9])gh[pousr]_[A-Za-z0-9_]{20,}',
        '(?<![A-Za-z0-9])xox[baprs]-[A-Za-z0-9-]{16,}',
        '(?<![A-Za-z0-9])hf_[A-Za-z0-9]{20,}',
        '-----BEGIN (?:RSA |OPENSSH |EC |DSA )?PRIVATE KEY-----'
    )
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($item in Get-ChildItem -LiteralPath $Root -Force -Recurse) {
        Assert-NotRedirected -Item $item -Description "Staged bundle path"
        if ($item.PSIsContainer) {
            continue
        }
        $relative = $item.FullName.Substring((Get-FullPath $Root).TrimEnd('\', '/').Length + 1).Replace('\', '/')
        if (-not $ExpectedFiles.Contains($relative)) {
            throw "Bundle contains a file outside the allowlist: $relative"
        }
        [void]$seen.Add($relative)
        foreach ($segment in $relative.Split('/')) {
            if ($forbiddenSegments -contains $segment.ToLowerInvariant() -or $segment.ToLowerInvariant().EndsWith('.egg-info')) {
                throw "Bundle path is forbidden by distribution policy: $relative"
            }
        }
        foreach ($pattern in $forbiddenNames) {
            if ($item.Name -like $pattern) {
                throw "Bundle runtime/private file is forbidden: $relative"
            }
        }
        if ($relative -ieq $JarRelativePath) {
            continue
        }
        $extension = [System.IO.Path]::GetExtension($item.Name).ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($extension)) {
            $extension = $item.Name.ToLowerInvariant()
        }
        if ($textExtensions -notcontains $extension) {
            throw "Unexpected binary or unsupported bundle file type: $relative"
        }
        if ($item.Length -gt 8MB) {
            throw "Text bundle file exceeds the 8 MiB scan limit: $relative"
        }
        $content = [System.IO.File]::ReadAllText($item.FullName, [System.Text.Encoding]::UTF8)
        foreach ($pattern in $highConfidenceSecrets) {
            if ([System.Text.RegularExpressions.Regex]::IsMatch($content, $pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
                throw "Secret-like content found in $relative"
            }
        }
        $hostPathPattern = '(?i)(?:[A-Z]:[\\/]Users[\\/]|(?<![A-Za-z0-9._-])/(?:Users|home)/)([^\\/\s`"'']+)'
        foreach ($hostPathMatch in [System.Text.RegularExpressions.Regex]::Matches($content, $hostPathPattern)) {
            $userSegment = $hostPathMatch.Groups[1].Value.Trim()
            if ($userSegment -notmatch '^(?:\.{3}|me|example|data|user|username|<[^>]+>|%[^%]+%)$') {
                throw "Private absolute host path found in $relative"
            }
        }
        if ([System.Text.RegularExpressions.Regex]::IsMatch($content, '(?i)[A-Z]:[\\/][^\r\n`"'']*Dropbox[\\/]')) {
            throw "Private Dropbox path found in $relative"
        }
        $assignmentPattern = '(?im)^\s*(?:api[_-]?key|access[_-]?token|secret|password|client[_-]?secret|authorization)\s*[:=]\s*["'']([^"'']{8,})["'']'
        foreach ($match in [System.Text.RegularExpressions.Regex]::Matches($content, $assignmentPattern)) {
            $value = $match.Groups[1].Value.Trim()
            if ($value -notmatch '(?i)(\$|%|<|your|example|change.?me|replace.?me|none|null|env|getenv|placeholder)') {
                throw "Literal credential assignment found in $relative"
            }
        }
    }
    foreach ($expected in $ExpectedFiles) {
        if (-not $seen.Contains($expected)) {
            throw "Expected bundle file is missing after assembly: $expected"
        }
    }
}

function Invoke-CreateZip {
    param([string]$SourceDirectory, [string]$DestinationZip)
    if ($TestHooks.ContainsKey("CompressBundle")) {
        & $TestHooks["CompressBundle"] $SourceDirectory $DestinationZip
        return
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $SourceDirectory,
        $DestinationZip,
        [System.IO.Compression.CompressionLevel]::Optimal,
        $true)
}

function Test-ZipBundle {
    param(
        [string]$ZipPath,
        [string]$BundleName,
        [System.Collections.Generic.HashSet[string]]$BundleFiles,
        [string]$JarLeaf,
        [string]$ExpectedJarHash
    )
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $expectedEntries = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($relative in $BundleFiles) {
        [void]$expectedEntries.Add("$BundleName/$relative")
    }
    $seenEntries = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        foreach ($entry in $archive.Entries) {
            $name = $entry.FullName.Replace('\', '/')
            if ($name.EndsWith('/')) {
                continue
            }
            if (-not $expectedEntries.Contains($name)) {
                throw "ZIP contains an unexpected entry: $name"
            }
            [void]$seenEntries.Add($name)
            if ($name -eq "$BundleName/$JarLeaf") {
                $stream = $entry.Open()
                try {
                    $entryHash = Get-StreamHashHex $stream
                } finally {
                    $stream.Dispose()
                }
                if ($entryHash -ne $ExpectedJarHash) {
                    throw "Bundled JAR hash does not match the built artifact."
                }
            }
        }
    } finally {
        $archive.Dispose()
    }
    foreach ($expected in $expectedEntries) {
        if (-not $seenEntries.Contains($expected)) {
            throw "ZIP is missing expected entry: $expected"
        }
    }
}

function New-VerifiedCandidate {
    param(
        [string]$Source,
        [string]$Destination,
        [string]$ExpectedHash,
        [string]$TransactionId
    )
    $directory = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $leaf = Split-Path -Leaf $Destination
    $candidate = Join-Path $directory ".$leaf.imagejai-candidate-$TransactionId"
    if (Test-Path -LiteralPath $candidate) {
        throw "Refusing to reuse destination candidate: $candidate"
    }
    Invoke-CopyFile -Source $Source -Destination $candidate
    $candidateHash = Get-HashHex $candidate
    if ($candidateHash -ne $ExpectedHash) {
        Remove-Item -LiteralPath $candidate -Force -ErrorAction SilentlyContinue
        throw "Destination candidate hash mismatch for $Destination"
    }
    return [PSCustomObject]@{
        Source = $Source
        Candidate = $candidate
        Destination = $Destination
        ExpectedHash = $ExpectedHash
    }
}

function Get-ImageJAIJars {
    param([string]$Directory)
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) {
        return @()
    }
    return @(Get-ChildItem -LiteralPath $Directory -Force -File | Where-Object {
        $_.Name -match '^imagej-ai-.*\.jar$'
    } | ForEach-Object { $_.FullName })
}

function Publish-VerifiedTransaction {
    param(
        [object[]]$Candidates,
        [string[]]$AdditionalExistingPaths,
        [string]$TransactionId
    )
    $backups = @()
    $published = @()
    $existing = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($path in @($AdditionalExistingPaths) + @($Candidates | ForEach-Object { $_.Destination })) {
        if (-not [string]::IsNullOrWhiteSpace($path) -and (Test-Path -LiteralPath $path -PathType Leaf)) {
            [void]$existing.Add((Get-FullPath $path))
        }
    }
    try {
        foreach ($path in $existing) {
            $item = Get-Item -LiteralPath $path -Force
            Assert-NotRedirected -Item $item -Description "Existing destination"
            $backup = Join-Path $item.DirectoryName ".$($item.Name).imagejai-backup-$TransactionId"
            if (Test-Path -LiteralPath $backup) {
                throw "Refusing to overwrite transaction backup: $backup"
            }
            Invoke-MoveFile -Source $path -Destination $backup
            $backups += [PSCustomObject]@{ Original = $path; Backup = $backup }
        }
        foreach ($candidate in $Candidates) {
            Invoke-MoveFile -Source $candidate.Candidate -Destination $candidate.Destination
            $published += $candidate.Destination
        }
        foreach ($candidate in $Candidates) {
            $publishedHash = Get-HashHex $candidate.Destination
            if ($publishedHash -ne $candidate.ExpectedHash) {
                throw "Published artifact hash mismatch: $($candidate.Destination)"
            }
        }
    } catch {
        $failure = $_
        for ($i = $published.Count - 1; $i -ge 0; $i--) {
            Remove-Item -LiteralPath $published[$i] -Force -ErrorAction SilentlyContinue
        }
        for ($i = $backups.Count - 1; $i -ge 0; $i--) {
            if (Test-Path -LiteralPath $backups[$i].Backup -PathType Leaf) {
                Invoke-MoveFile -Source $backups[$i].Backup -Destination $backups[$i].Original
            }
        }
        throw $failure
    }
    foreach ($backup in $backups) {
        Remove-Item -LiteralPath $backup.Backup -Force -ErrorAction SilentlyContinue
    }
}

function Remove-OwnedStagingTree {
    param([string]$Path, [string]$Parent)
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $full = Assert-PathUnderRoot -Path $Path -Root $Parent -Description "Staging cleanup"
    if ((Split-Path -Leaf $full) -notmatch '^imagejai-lab-bundle-[0-9a-f]{32}$') {
        throw "Refusing recursive cleanup of an unowned path: $full"
    }
    Remove-Item -LiteralPath $full -Recurse -Force
}

if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$') {
    throw "Version must be a simple semantic version, not a path or wildcard: $Version"
}

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}
$ProjectRoot = Get-FullPath $ProjectRoot
if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "Project root not found: $ProjectRoot"
}

$agentRoot = Join-Path $ProjectRoot "agent"
$allowlistPath = Join-Path $PSScriptRoot "lab_bundle_allowlist.psd1"
if (-not (Test-Path -LiteralPath $allowlistPath -PathType Leaf)) {
    throw "Bundle allowlist not found: $allowlistPath"
}
$allowlist = Import-PowerShellDataFile -LiteralPath $allowlistPath
if ($null -eq $allowlist.Files -or @($allowlist.Files).Count -eq 0) {
    throw "Bundle allowlist is empty."
}

$jarLeaf = "imagej-ai-$Version.jar"
$jar = Join-Path $ProjectRoot "target\$jarLeaf"
$jar = Assert-PathUnderRoot -Path $jar -Root (Join-Path $ProjectRoot "target") -Description "Deployable JAR"
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Deployable JAR not found: $jar"
}
$jarItem = Get-Item -LiteralPath $jar -Force
Assert-NotRedirected -Item $jarItem -Description "Deployable JAR"
$sourceJarHash = Get-HashHex $jar

if ([string]::IsNullOrWhiteSpace($StagingParent)) {
    $StagingParent = [System.IO.Path]::GetTempPath()
}
$StagingParent = Get-FullPath $StagingParent
New-Item -ItemType Directory -Force -Path $StagingParent | Out-Null
$transactionId = [Guid]::NewGuid().ToString("N")
$stagingRoot = Join-Path $StagingParent "imagejai-lab-bundle-$transactionId"
$bundleName = "ImageJAI-lab-$Version"
$bundleDir = Join-Path $stagingRoot $bundleName
$stagedZip = Join-Path $stagingRoot "$bundleName.zip"
$destinationCandidates = @()

try {
    New-Item -ItemType Directory -Path $bundleDir | Out-Null
    $expectedBundleFiles = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $stagedJar = Join-Path $bundleDir $jarLeaf
    Invoke-CopyFile -Source $jar -Destination $stagedJar
    if ((Get-HashHex $stagedJar) -ne $sourceJarHash) {
        throw "Staged JAR hash does not match built artifact."
    }
    [void]$expectedBundleFiles.Add($jarLeaf)

    Copy-AllowlistedAgent -SourceRoot $agentRoot -DestinationRoot (Join-Path $bundleDir "agent") `
        -RelativeFiles @($allowlist.Files) -ExpectedFiles $expectedBundleFiles

    $setupPython = @'
[CmdletBinding()]
param(
    [string]$TargetRoot = "",
    [hashtable]$TestHooks = @{}
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$bundleRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceAgent = Join-Path $bundleRoot "agent"
$userProfile = [Environment]::GetFolderPath("UserProfile")
if ([string]::IsNullOrWhiteSpace($TargetRoot)) {
    $TargetRoot = Join-Path $userProfile "ImageJAI"
}
$targetRoot = [System.IO.Path]::GetFullPath($TargetRoot)
$targetAgent = Join-Path $targetRoot "agent"
$venvRoot = Join-Path $targetRoot ".venv"
$venvPython = Join-Path $venvRoot "Scripts\python.exe"

if (-not (Test-Path -LiteralPath (Join-Path $sourceAgent "ij.py") -PathType Leaf)) {
    throw "Run this script from the extracted ImageJAI lab bundle folder."
}

function Invoke-SetupProcess {
    param([string]$Command, [string[]]$Arguments)
    if ($TestHooks.ContainsKey("RunProcess")) {
        $hookResult = & $TestHooks["RunProcess"] $Command $Arguments
        if ($null -eq $hookResult -or $null -eq $hookResult.PSObject.Properties["ExitCode"]) {
            throw "RunProcess test hook must return ExitCode and Output."
        }
        return $hookResult
    }
    $output = @(& $Command @Arguments 2>&1 | ForEach-Object { [string]$_ })
    $exitCode = $LASTEXITCODE
    foreach ($line in $output) {
        Write-Host $line
    }
    return [PSCustomObject]@{ ExitCode = $exitCode; Output = $output }
}

function Get-SupportedPython {
    $candidates = @(
        @{ Command = "py"; Prefix = @("-3.13") },
        @{ Command = "py"; Prefix = @("-3.12") },
        @{ Command = "py"; Prefix = @("-3.11") },
        @{ Command = "py"; Prefix = @("-3.10") },
        @{ Command = "python"; Prefix = @() }
    )
    foreach ($candidate in $candidates) {
        if ($null -eq (Get-Command $candidate.Command -ErrorAction SilentlyContinue)) {
            continue
        }
        $arguments = @($candidate.Prefix) + @("-c", "import sys; print('%d.%d' % sys.version_info[:2])")
        $probe = Invoke-SetupProcess -Command $candidate.Command -Arguments $arguments
        $version = @($probe.Output) | Select-Object -Last 1
        if ($probe.ExitCode -ne 0 -or $version -notmatch '^(3)\.(10|11|12|13)$') {
            continue
        }
        return $candidate
    }
    throw "ImageJAI requires Python 3.10, 3.11, 3.12, or 3.13. Install one of those versions and rerun setup."
}

New-Item -ItemType Directory -Force -Path $targetRoot | Out-Null
$incomingAgent = Join-Path $targetRoot (".agent-incoming-" + [Guid]::NewGuid().ToString("N"))
$backupAgent = Join-Path $targetRoot (".agent-backup-" + [Guid]::NewGuid().ToString("N"))
Copy-Item -LiteralPath $sourceAgent -Destination $incomingAgent -Recurse
if (-not (Test-Path -LiteralPath (Join-Path $incomingAgent "ij.py") -PathType Leaf)) {
    throw "The staged agent workspace copy is incomplete."
}
try {
    if (Test-Path -LiteralPath $targetAgent) {
        Move-Item -LiteralPath $targetAgent -Destination $backupAgent
    }
    Move-Item -LiteralPath $incomingAgent -Destination $targetAgent

    $basePython = Get-SupportedPython
    if (-not (Test-Path -LiteralPath $venvPython -PathType Leaf)) {
        $venvArguments = @($basePython.Prefix) + @("-m", "venv", $venvRoot)
        $venvCreate = Invoke-SetupProcess -Command $basePython.Command -Arguments $venvArguments
        if ($venvCreate.ExitCode -ne 0) {
            throw "Python failed to create the dedicated ImageJAI virtual environment."
        }
    }
    $versionProbe = Invoke-SetupProcess -Command $venvPython -Arguments @(
        "-c", "import sys; print('%d.%d' % sys.version_info[:2])")
    $venvVersion = @($versionProbe.Output) | Select-Object -Last 1
    if ($versionProbe.ExitCode -ne 0 -or $venvVersion -notmatch '^3\.(10|11|12|13)$') {
        throw "The existing ImageJAI virtual environment does not use Python 3.10-3.13. Remove $venvRoot and rerun setup."
    }

    $pipSteps = @(
        [PSCustomObject]@{
            Arguments = @("-m", "pip", "install", "--upgrade", "pip")
            Error = "Could not upgrade pip in the ImageJAI environment."
        },
        [PSCustomObject]@{
            Arguments = @("-m", "pip", "install", "-r", (Join-Path $targetAgent "providers\requirements.txt"))
            Error = "Could not install ImageJAI provider dependencies."
        },
        [PSCustomObject]@{
            Arguments = @("-m", "pip", "install", "-e", $targetAgent)
            Error = "Could not install imagej-use-auto."
        },
        [PSCustomObject]@{
            Arguments = @("-m", "pip", "install", "-e", (Join-Path $targetAgent "gemma4_31b"))
            Error = "Could not install the bundled Gemma agent."
        }
    )
    foreach ($pipStep in $pipSteps) {
        $pipResult = Invoke-SetupProcess -Command $venvPython -Arguments $pipStep.Arguments
        if ($pipResult.ExitCode -ne 0) { throw $pipStep.Error }
    }

    $previousPythonPath = $env:PYTHONPATH
    try {
        $env:PYTHONPATH = if ([string]::IsNullOrWhiteSpace($previousPythonPath)) {
            $targetRoot
        } else {
            $targetRoot + [System.IO.Path]::PathSeparator + $previousPythonPath
        }
        $validation = Invoke-SetupProcess -Command $venvPython -Arguments @(
            "-c", "import agent.providers.agent_cli, gemma4_31b, imagej_use, ij; print('ImageJAI Python environment OK')")
        if ($validation.ExitCode -ne 0) { throw "ImageJAI dependency validation failed." }
    } finally {
        $env:PYTHONPATH = $previousPythonPath
    }

    [Environment]::SetEnvironmentVariable("IMAGEJAI_PYTHON", $venvPython, "User")
    $env:IMAGEJAI_PYTHON = $venvPython
} catch {
    $failure = $_
    if (Test-Path -LiteralPath $incomingAgent) {
        Remove-Item -LiteralPath $incomingAgent -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $backupAgent) {
        if (Test-Path -LiteralPath $targetAgent) {
            Remove-Item -LiteralPath $targetAgent -Recurse -Force
        }
        Move-Item -LiteralPath $backupAgent -Destination $targetAgent
    } elseif (Test-Path -LiteralPath $targetAgent) {
        Remove-Item -LiteralPath $targetAgent -Recurse -Force
    }
    throw $failure
}

# Commit the workspace replacement only after venv creation, every pip step,
# import validation, and environment configuration have all succeeded.
if (Test-Path -LiteralPath $backupAgent) {
    Remove-Item -LiteralPath $backupAgent -Recurse -Force
}

Write-Host ""
Write-Host "ImageJAI agent workspace: $targetAgent"
Write-Host "Dedicated Python: $venvPython"
Write-Host "Restart Fiji, then open Plugins > AI Assistant."
'@

    $installText = @"
ImageJAI lab install
====================

1. Extract ImageJAI-lab-$Version.zip.
2. Open PowerShell in the extracted ImageJAI-lab-$Version folder.
3. Run:  .\setup-python.ps1
4. Copy $jarLeaf into Fiji.app\plugins when Fiji is closed, replacing only
   older files named imagej-ai-*.jar. Never copy sources/tests/original JARs.
5. Restart Fiji and open Plugins > AI Assistant.

Requirements: Fiji on Java 11 or newer and Python 3.10-3.13. The setup script
creates %USERPROFILE%\ImageJAI\.venv and never installs into global Python.
Provider credentials remain on the user's machine and are not bundled.
"@

    $readme = @"
# ImageJAI lab bundle $Version

This private lab bundle contains $jarLeaf, a reviewed allowlist-only agent
workspace, setup-python.ps1, and installation instructions.

Run setup-python.ps1 with Python 3.10-3.13 available. It creates a dedicated
virtual environment at %USERPROFILE%\ImageJAI\.venv, installs imagej-use-auto
and the bundled Gemma agent there, and sets IMAGEJAI_PYTHON to that interpreter.
It does not install packages into global Python.

Fiji must use Java 11 or newer. Analysis outputs belong in AI_Exports beside
the opened image. API keys and provider credentials are never included.
"@

    Write-Utf8 -Path (Join-Path $bundleDir "setup-python.ps1") -Text $setupPython
    Write-Utf8 -Path (Join-Path $bundleDir "How to Install.txt") -Text $installText
    Write-Utf8 -Path (Join-Path $bundleDir "README.md") -Text $readme
    [void]$expectedBundleFiles.Add("setup-python.ps1")
    [void]$expectedBundleFiles.Add("How to Install.txt")
    [void]$expectedBundleFiles.Add("README.md")

    Test-SafeBundleTree -Root $bundleDir -ExpectedFiles $expectedBundleFiles -JarRelativePath $jarLeaf
    Invoke-CreateZip -SourceDirectory $bundleDir -DestinationZip $stagedZip
    if (-not (Test-Path -LiteralPath $stagedZip -PathType Leaf)) {
        throw "Bundle ZIP was not created."
    }
    Test-ZipBundle -ZipPath $stagedZip -BundleName $bundleName -BundleFiles $expectedBundleFiles `
        -JarLeaf $jarLeaf -ExpectedJarHash $sourceJarHash
    $zipHash = Get-HashHex $stagedZip

    if ($DryRun) {
        [PSCustomObject]@{
            Version = $Version
            Artifact = $jar
            Sha256 = $sourceJarHash
            SharedRoot = $SharedRoot
            SharedZip = [System.IO.Path]::Combine($SharedRoot, "$bundleName.zip")
            LocalFijiPlugins = $LocalFijiPlugins
            LocalDeployWarning = ""
            DryRun = $true
            AllowlistedAgentFiles = @($allowlist.Files).Count
        }
        return
    }

    if ([string]::IsNullOrWhiteSpace($SharedRoot)) {
        throw "SharedRoot is required unless -DryRun is used."
    }
    $SharedRoot = Get-FullPath $SharedRoot
    New-Item -ItemType Directory -Force -Path $SharedRoot | Out-Null
    $sharedRootItem = Get-Item -LiteralPath $SharedRoot -Force
    Assert-NotRedirected -Item $sharedRootItem -Description "Shared distribution root"

    $sharedMappings = @(
        @{ Source = $jar; Destination = (Join-Path $SharedRoot $jarLeaf); Hash = $sourceJarHash },
        @{ Source = $stagedZip; Destination = (Join-Path $SharedRoot "$bundleName.zip"); Hash = $zipHash },
        @{ Source = (Join-Path $bundleDir "setup-python.ps1"); Destination = (Join-Path $SharedRoot "setup-python.ps1"); Hash = (Get-HashHex (Join-Path $bundleDir "setup-python.ps1")) },
        @{ Source = (Join-Path $bundleDir "How to Install.txt"); Destination = (Join-Path $SharedRoot "How to Install.txt"); Hash = (Get-HashHex (Join-Path $bundleDir "How to Install.txt")) },
        @{ Source = (Join-Path $bundleDir "README.md"); Destination = (Join-Path $SharedRoot "README.md"); Hash = (Get-HashHex (Join-Path $bundleDir "README.md")) }
    )
    $sharedCandidates = @()
    foreach ($mapping in $sharedMappings) {
        $candidate = New-VerifiedCandidate -Source $mapping.Source -Destination $mapping.Destination `
            -ExpectedHash $mapping.Hash -TransactionId $transactionId
        $sharedCandidates += $candidate
        $destinationCandidates += $candidate.Candidate
    }

    $localCandidate = $null
    $hasLocalFijiPlugins = (-not [string]::IsNullOrWhiteSpace($LocalFijiPlugins)) -and
        (Test-Path -LiteralPath $LocalFijiPlugins -PathType Container)
    if ($hasLocalFijiPlugins) {
        $LocalFijiPlugins = Get-FullPath $LocalFijiPlugins
        Assert-NotRedirected -Item (Get-Item -LiteralPath $LocalFijiPlugins -Force) -Description "Local Fiji plugins root"
        $localCandidate = New-VerifiedCandidate -Source $jar -Destination (Join-Path $LocalFijiPlugins $jarLeaf) `
            -ExpectedHash $sourceJarHash -TransactionId $transactionId
        $destinationCandidates += $localCandidate.Candidate
    }

    $sharedStaleJars = @(Get-ImageJAIJars $SharedRoot)
    Publish-VerifiedTransaction -Candidates $sharedCandidates -AdditionalExistingPaths $sharedStaleJars `
        -TransactionId $transactionId

    $localDeployWarning = ""
    if ($null -ne $localCandidate) {
        try {
            $localStaleJars = @(Get-ImageJAIJars $LocalFijiPlugins)
            Publish-VerifiedTransaction -Candidates @($localCandidate) -AdditionalExistingPaths $localStaleJars `
                -TransactionId $transactionId
        } catch {
            $localDeployWarning = $_.Exception.Message
            Write-Warning "Local Fiji deploy skipped; existing JARs were preserved: $localDeployWarning"
        }
    }

    [PSCustomObject]@{
        Version = $Version
        Artifact = $jar
        Sha256 = $sourceJarHash
        SharedRoot = $SharedRoot
        SharedZip = [System.IO.Path]::Combine($SharedRoot, "$bundleName.zip")
        LocalFijiPlugins = $LocalFijiPlugins
        LocalDeployWarning = $localDeployWarning
        DryRun = $false
        AllowlistedAgentFiles = @($allowlist.Files).Count
    }
} finally {
    foreach ($candidate in $destinationCandidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            Remove-Item -LiteralPath $candidate -Force -ErrorAction SilentlyContinue
        }
    }
    Remove-OwnedStagingTree -Path $stagingRoot -Parent $StagingParent
}
