param(
    [switch]$DryRun
)

$ErrorActionPreference = "Continue"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Write-Warn([string]$Message) {
    Write-Warning $Message
}

function Convert-HelpToCommands([string]$Text) {
    $seen = @{}
    $commands = @()
    foreach ($line in ($Text -split "`r?`n")) {
        $clean = [regex]::Replace($line, "`e\[[0-9;?]*[A-Za-z]", "").Trim()
        $match = [regex]::Match($clean, "^(/[A-Za-z][A-Za-z0-9_-]*)(?:\s+[-:]\s*|\s{2,})(.*)$")
        if (-not $match.Success) {
            $match = [regex]::Match($clean, "^(/[A-Za-z][A-Za-z0-9_-]*)(?:\s+([A-Za-z].*))?$")
        }
        if (-not $match.Success) { continue }
        $command = $match.Groups[1].Value
        if ($seen.ContainsKey($command)) { continue }
        $seen[$command] = $true
        $desc = ""
        if ($match.Groups.Count -gt 2) {
            $desc = $match.Groups[2].Value.Trim()
        }
        $commands += [pscustomobject]@{
            command = $command
            description = $desc
        }
    }
    return $commands
}

function Refresh-Agent([string]$Id, [string]$Cli, [string[]]$Flags = @()) {
    $exe = ($Cli -split "\s+")[0]
    $found = Get-Command $exe -ErrorAction SilentlyContinue
    if (-not $found) {
        Write-Warn "${Id}: missing '$exe'; skipping"
        return
    }

    $args = @()
    foreach ($part in ($Cli -split "\s+" | Select-Object -Skip 1)) {
        if ($part) { $args += $part }
    }
    $args += $Flags
    $args += "/help"
    $cmdLine = @($found.Source) + $args | ForEach-Object {
        if ($_ -match "\s") { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    }
    $cmdLine = $cmdLine -join " "

    $job = Start-Job -ScriptBlock {
        param($CommandLine, $WorkingDirectory)
        Set-Location $WorkingDirectory
        cmd.exe /d /c $CommandLine 2>&1 | Out-String
        "IMAGEJAI_EXIT=$LASTEXITCODE"
    } -ArgumentList $cmdLine, $Root
    if (-not (Wait-Job $job -Timeout 20)) {
        Write-Warn "${Id}: '$Cli /help' timed out; killing process and parsing captured output"
        Stop-Job $job -ErrorAction SilentlyContinue
    }
    $received = Receive-Job $job -ErrorAction SilentlyContinue
    Remove-Job $job -Force -ErrorAction SilentlyContinue
    $output = ($received | Where-Object { $_ -notmatch "^IMAGEJAI_EXIT=" }) -join "`n"
    $exitLine = $received | Where-Object { $_ -match "^IMAGEJAI_EXIT=" } | Select-Object -Last 1
    if ($exitLine -and $exitLine -ne "IMAGEJAI_EXIT=0") {
        Write-Warn "${Id}: '$Cli /help' exited with $($exitLine -replace '^IMAGEJAI_EXIT=', ''); parsing any captured output"
    }

    $commands = @(Convert-HelpToCommands $output)
    if ($commands.Count -lt 2) {
        Write-Warn "${Id}: no slash command list parsed; leaving registry unchanged"
        return
    }

    $target = Join-Path $Root "src/main/resources/agents/$Id/commands.json"
    if ($DryRun) {
        Write-Host "${Id}: parsed $($commands.Count) commands (dry run)"
    } else {
        New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
        $commands | ConvertTo-Json -Depth 3 | Set-Content -Encoding UTF8 -Path $target
        Write-Host "${Id}: wrote $target"
    }
}

Refresh-Agent "gemma4_31b" "gemma4_31b_agent"
Refresh-Agent "gemma4_31b_claude" "gemma4_31b_agent" @("--style", "claude")
Refresh-Agent "claude" "claude"
Refresh-Agent "aider" "aider"
Refresh-Agent "gemini" "gemini"
Refresh-Agent "codex" "codex"
