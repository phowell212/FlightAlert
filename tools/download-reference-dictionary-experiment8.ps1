[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ManifestUrl,

    [Parameter(Mandatory = $true)]
    [string]$Output
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repository = Split-Path -Parent $PSScriptRoot
$launcher = (Get-Command py.exe -ErrorAction Stop).Source
$versionOutput = @(& $launcher -3.11 --version 2>&1)
$versionText = ($versionOutput -join "`n").Trim()
if ($LASTEXITCODE -ne 0 -or $versionText -notmatch '^Python 3\.11(?:\.|$)') {
    throw 'CPython 3.11 is required for the Experiment 8 reference downloader.'
}

$arguments = [Collections.Generic.List[string]]::new()
foreach ($value in @(
        '-3.11',
        '-m',
        'tools.experiment8.reference_release_assets',
        'fetch',
        '--manifest-url',
        $ManifestUrl,
        '--output',
        $Output
    )) {
    $arguments.Add([string]$value)
}

Push-Location -LiteralPath $repository
try {
    & $launcher $arguments.ToArray()
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($exitCode -ne 0) {
    throw "Experiment 8 reference downloader exited with code $exitCode."
}
