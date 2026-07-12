[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $PipelineArguments
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repositoryRoot
try {
    & py -3.11 -m tools.experiment8.run_osm_global_place_package @PipelineArguments
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
