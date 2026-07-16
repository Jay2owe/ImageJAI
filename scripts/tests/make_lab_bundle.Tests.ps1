$script:ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$script:Bundler = Join-Path $script:ProjectRoot "scripts\make_lab_bundle.ps1"
$script:AllowlistPath = Join-Path $script:ProjectRoot "scripts\lab_bundle_allowlist.psd1"
$script:Version = "0.3.0"

function Write-FixtureText {
    param([string]$Path, [string]$Text = "safe fixture content")
    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false))
}

function New-BundleFixture {
    $root = Join-Path ([System.IO.Path]::GetTempPath()) ("imagejai-bundle-test-" + [Guid]::NewGuid().ToString("N"))
    $project = Join-Path $root "project"
    $agent = Join-Path $project "agent"
    $target = Join-Path $project "target"
    $shared = Join-Path $root "shared"
    $local = Join-Path $root "local-plugins"
    $staging = Join-Path $root "staging"
    New-Item -ItemType Directory -Force -Path $agent, $target, $shared, $local, $staging | Out-Null
    $allowlist = Import-PowerShellDataFile -LiteralPath $script:AllowlistPath
    foreach ($relative in @($allowlist.Files)) {
        Write-FixtureText -Path (Join-Path $agent $relative)
    }
    $jar = Join-Path $target "imagej-ai-$($script:Version).jar"
    [System.IO.File]::WriteAllBytes($jar, [System.Text.Encoding]::UTF8.GetBytes("new verified fixture jar"))
    return [PSCustomObject]@{
        Root = $root
        Project = $project
        Agent = $agent
        Jar = $jar
        Shared = $shared
        Local = $local
        Staging = $staging
    }
}

function Remove-BundleFixture {
    param([object]$Fixture)
    if ($null -eq $Fixture -or -not (Test-Path -LiteralPath $Fixture.Root)) {
        return
    }
    $full = [System.IO.Path]::GetFullPath($Fixture.Root)
    $temp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($temp, [System.StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path -Leaf $full) -notmatch '^imagejai-bundle-test-[0-9a-f]{32}$') {
        throw "Refusing to remove unexpected test path: $full"
    }
    Remove-Item -LiteralPath $full -Recurse -Force
}

function Get-TestHash {
    param([string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash
}

function Invoke-FixtureBundle {
    param(
        [object]$Fixture,
        [hashtable]$Hooks = @{},
        [switch]$DryRun
    )
    $parameters = @{
        Version = $script:Version
        ProjectRoot = $Fixture.Project
        SharedRoot = $Fixture.Shared
        LocalFijiPlugins = $Fixture.Local
        StagingParent = $Fixture.Staging
        TestHooks = $Hooks
    }
    if ($DryRun) {
        $parameters.DryRun = $true
    }
    return & $script:Bundler @parameters
}

function Add-OldDistribution {
    param([object]$Fixture)
    Write-FixtureText -Path (Join-Path $Fixture.Shared "imagej-ai-0.2.0.jar") -Text "old shared jar"
    Write-FixtureText -Path (Join-Path $Fixture.Shared "ImageJAI-lab-0.3.0.zip") -Text "old shared zip"
    Write-FixtureText -Path (Join-Path $Fixture.Shared "README.md") -Text "old readme"
    Write-FixtureText -Path (Join-Path $Fixture.Shared "setup-python.ps1") -Text "old setup"
    Write-FixtureText -Path (Join-Path $Fixture.Shared "How to Install.txt") -Text "old install"
    Write-FixtureText -Path (Join-Path $Fixture.Local "imagej-ai-0.2.0.jar") -Text "old local jar"
    Write-FixtureText -Path (Join-Path $Fixture.Local "unrelated-plugin.jar") -Text "unrelated"
}

function Get-DistributionHashes {
    param([object]$Fixture)
    $result = @{}
    foreach ($path in Get-ChildItem -LiteralPath $Fixture.Shared, $Fixture.Local -File) {
        $result[$path.FullName] = Get-TestHash $path.FullName
    }
    return $result
}

function Assert-HashesUnchanged {
    param([hashtable]$Before)
    foreach ($path in $Before.Keys) {
        (Test-Path -LiteralPath $path -PathType Leaf) | Should Be $true
        (Get-TestHash $path) | Should Be $Before[$path]
    }
}

Describe "make_lab_bundle verify-first distribution" {
    It "performs a full dry run without creating destination artifacts" {
        $fixture = New-BundleFixture
        try {
            $result = Invoke-FixtureBundle -Fixture $fixture -DryRun
            $result.DryRun | Should Be $true
            $result.AllowlistedAgentFiles | Should Be 186
            @(Get-ChildItem -LiteralPath $fixture.Shared -Force).Count | Should Be 0
            @(Get-ChildItem -LiteralPath $fixture.Local -Force).Count | Should Be 0
            @(Get-ChildItem -LiteralPath $fixture.Staging -Force).Count | Should Be 0
        } finally {
            Remove-BundleFixture $fixture
        }
    }

    It "preserves every old artifact when destination copy fails" {
        $fixture = New-BundleFixture
        try {
            Add-OldDistribution $fixture
            $before = Get-DistributionHashes $fixture
            $hooks = @{
                CopyFile = {
                    param($source, $destination)
                    if ($destination -like '*.imagejai-candidate-*') {
                        throw "simulated destination copy failure"
                    }
                    Copy-Item -LiteralPath $source -Destination $destination -Force
                }
            }
            { Invoke-FixtureBundle -Fixture $fixture -Hooks $hooks } | Should Throw "simulated destination copy failure"
            Assert-HashesUnchanged $before
        } finally {
            Remove-BundleFixture $fixture
        }
    }

    It "preserves every old artifact when a destination candidate hash mismatches" {
        $fixture = New-BundleFixture
        try {
            Add-OldDistribution $fixture
            $before = Get-DistributionHashes $fixture
            $hooks = @{
                HashFile = {
                    param($path)
                    if ($path -like '*.imagejai-candidate-*') {
                        return ('0' * 64)
                    }
                    return (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash
                }
            }
            { Invoke-FixtureBundle -Fixture $fixture -Hooks $hooks } | Should Throw "candidate hash mismatch"
            Assert-HashesUnchanged $before
        } finally {
            Remove-BundleFixture $fixture
        }
    }

    It "preserves every old artifact when ZIP creation fails" {
        $fixture = New-BundleFixture
        try {
            Add-OldDistribution $fixture
            $before = Get-DistributionHashes $fixture
            $hooks = @{
                CompressBundle = { param($source, $destination) throw "simulated ZIP failure" }
            }
            { Invoke-FixtureBundle -Fixture $fixture -Hooks $hooks } | Should Throw "simulated ZIP failure"
            Assert-HashesUnchanged $before
        } finally {
            Remove-BundleFixture $fixture
        }
    }

    It "rolls back all shared artifacts when publication fails partway through" {
        $fixture = New-BundleFixture
        try {
            Add-OldDistribution $fixture
            $before = Get-DistributionHashes $fixture
            $state = @{ CandidateMoves = 0 }
            $hooks = @{
                MoveFile = {
                    param($source, $destination)
                    if ($source -like '*.imagejai-candidate-*') {
                        $state.CandidateMoves++
                        if ($state.CandidateMoves -eq 2) {
                            throw "simulated mid-publication move failure"
                        }
                    }
                    Move-Item -LiteralPath $source -Destination $destination -Force
                }
            }
            { Invoke-FixtureBundle -Fixture $fixture -Hooks $hooks } | Should Throw "mid-publication move failure"
            Assert-HashesUnchanged $before
            (Test-Path -LiteralPath (Join-Path $fixture.Shared "imagej-ai-0.3.0.jar")) | Should Be $false
        } finally {
            Remove-BundleFixture $fixture
        }
    }

    It "publishes verified shared and local artifacts and preserves unrelated plugins" {
        $fixture = New-BundleFixture
        try {
            Add-OldDistribution $fixture
            $unrelated = Join-Path $fixture.Local "unrelated-plugin.jar"
            $unrelatedHash = Get-TestHash $unrelated
            $result = Invoke-FixtureBundle -Fixture $fixture
            $result.LocalDeployWarning | Should Be ""
            $sourceHash = Get-TestHash $fixture.Jar
            (Get-TestHash (Join-Path $fixture.Shared "imagej-ai-0.3.0.jar")) | Should Be $sourceHash
            (Get-TestHash (Join-Path $fixture.Local "imagej-ai-0.3.0.jar")) | Should Be $sourceHash
            (Test-Path (Join-Path $fixture.Shared "imagej-ai-0.2.0.jar")) | Should Be $false
            (Test-Path (Join-Path $fixture.Local "imagej-ai-0.2.0.jar")) | Should Be $false
            (Get-TestHash $unrelated) | Should Be $unrelatedHash
            $setup = Get-Content -LiteralPath (Join-Path $fixture.Shared "setup-python.ps1") -Raw
            $setup | Should Match '3\.10.*3\.11.*3\.12.*3\.13'
            $setup | Should Match '\.venv'
            $setup | Should Match 'pip install -e \$targetAgent'
            $setup | Should Match 'pip install -e \(Join-Path \$targetAgent "gemma4_31b"\)'
            $setup | Should Match 'SetEnvironmentVariable\("IMAGEJAI_PYTHON"'
            $zip = Join-Path $fixture.Shared "ImageJAI-lab-0.3.0.zip"
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            $archive = [System.IO.Compression.ZipFile]::OpenRead($zip)
            try {
                @($archive.Entries | Where-Object { $_.FullName.Replace('\', '/') -like '*providers/_spike/*' }).Count | Should Be 0
                @($archive.Entries | Where-Object { $_.FullName.Replace('\', '/') -eq 'ImageJAI-lab-0.3.0/agent/ij.py' }).Count | Should Be 1
            } finally {
                $archive.Dispose()
            }
        } finally {
            Remove-BundleFixture $fixture
        }
    }

    It "reports a locked local JAR and preserves its exact bytes" {
        $fixture = New-BundleFixture
        $lock = $null
        try {
            Add-OldDistribution $fixture
            $oldLocal = Join-Path $fixture.Local "imagej-ai-0.2.0.jar"
            $oldHash = Get-TestHash $oldLocal
            $lock = [System.IO.File]::Open($oldLocal, [System.IO.FileMode]::Open,
                [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
            $result = Invoke-FixtureBundle -Fixture $fixture
            [string]::IsNullOrWhiteSpace($result.LocalDeployWarning) | Should Be $false
            (Test-Path -LiteralPath $oldLocal) | Should Be $true
            (Test-Path -LiteralPath (Join-Path $fixture.Local "imagej-ai-0.3.0.jar")) | Should Be $false
        } finally {
            if ($null -ne $lock) {
                $lock.Dispose()
            }
            if ($null -ne $fixture -and (Test-Path -LiteralPath (Join-Path $fixture.Local "imagej-ai-0.2.0.jar"))) {
                (Get-TestHash (Join-Path $fixture.Local "imagej-ai-0.2.0.jar")) | Should Be $oldHash
            }
            Remove-BundleFixture $fixture
        }
    }

    It "rejects secret content in an allowlisted file before destination mutation" {
        $fixture = New-BundleFixture
        try {
            Add-OldDistribution $fixture
            $before = Get-DistributionHashes $fixture
            Write-FixtureText -Path (Join-Path $fixture.Agent "ij.py") -Text 'api_key = "sk-123456789012345678901234567890"'
            { Invoke-FixtureBundle -Fixture $fixture -DryRun } | Should Throw "Secret-like content"
            Assert-HashesUnchanged $before
        } finally {
            Remove-BundleFixture $fixture
        }
    }

    It "rejects an allowlisted path that traverses a junction" {
        $fixture = New-BundleFixture
        try {
            $imagejUse = Join-Path $fixture.Agent "imagej_use"
            $external = Join-Path $fixture.Root "redirected-imagej-use"
            Copy-Item -LiteralPath $imagejUse -Destination $external -Recurse
            Remove-Item -LiteralPath $imagejUse -Recurse -Force
            New-Item -ItemType Junction -Path $imagejUse -Target $external | Out-Null
            { Invoke-FixtureBundle -Fixture $fixture -DryRun } | Should Throw "symlink or junction"
        } finally {
            Remove-BundleFixture $fixture
        }
    }

    It "rejects classifier-only output when the exact main JAR is absent" {
        $fixture = New-BundleFixture
        try {
            Remove-Item -LiteralPath $fixture.Jar -Force
            Write-FixtureText -Path (Join-Path $fixture.Project "target\imagej-ai-0.3.0-sources.jar")
            { Invoke-FixtureBundle -Fixture $fixture -DryRun } | Should Throw "Deployable JAR not found"
        } finally {
            Remove-BundleFixture $fixture
        }
    }
}
