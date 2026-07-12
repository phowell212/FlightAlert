[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PackageRoot,

    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [string]$FinalResult,

    [switch]$ValidateOnly,
    [switch]$Execute,

    [string]$LeaseHelper = 'C:\Users\Phineas\Documents\FlightAlert-test-artifacts\coordination\device-lease.ps1',
    [string]$ThreadId,
    [string]$EvidenceDirectory,
    [string]$Adb = 'adb'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([bool]$ValidateOnly -eq [bool]$Execute) {
    throw 'Select exactly one of -ValidateOnly or -Execute.'
}
if ($Execute) {
    if ([string]::IsNullOrWhiteSpace($ThreadId)) {
        throw '-ThreadId is required with -Execute.'
    }
    if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
        throw '-EvidenceDirectory is required with -Execute.'
    }
    if (-not (Test-Path -LiteralPath $LeaseHelper -PathType Leaf)) {
        throw "Device lease helper is missing: $LeaseHelper"
    }
}

$repository = Split-Path -Parent $PSScriptRoot
$launcher = (Get-Command py.exe -ErrorAction Stop).Source
$versionOutput = @(& $launcher -3.11 --version 2>&1)
if ($LASTEXITCODE -ne 0 -or (($versionOutput -join "`n").Trim() -notmatch '^Python 3\.11(?:\.|$)')) {
    throw 'CPython 3.11 is required for the Experiment 8 installer.'
}

$arguments = [Collections.Generic.List[string]]::new()
foreach ($value in @(
        '-3.11',
        '-m',
        'tools.experiment8.reference_package_install',
        '--package',
        $PackageRoot,
        '--apk',
        $ApkPath,
        '--final-result',
        $FinalResult
    )) {
    $arguments.Add([string]$value)
}
if ($ValidateOnly) {
    $arguments.Add('--validate-only')
} else {
    foreach ($value in @(
            '--execute',
            '--adb',
            $Adb,
            '--powershell',
            (Get-Command powershell.exe -ErrorAction Stop).Source,
            '--lease-helper',
            $LeaseHelper,
            '--thread-id',
            $ThreadId,
            '--evidence-directory',
            $EvidenceDirectory
        )) {
        $arguments.Add([string]$value)
    }
}

Push-Location -LiteralPath $repository
try {
    & $launcher $arguments.ToArray()
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($exitCode -ne 0) {
    throw "Experiment 8 installer exited with code $exitCode."
}
