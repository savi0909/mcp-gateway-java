[CmdletBinding()]
param(
    [string]$Repository = (Split-Path -Parent $PSScriptRoot),
    [string]$RunId = ('verify-' + [guid]::NewGuid().ToString('N'))
)
$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^verify-[a-zA-Z0-9-]+$') { throw 'Invalid verification run ID.' }
$gatewayRoot = (Resolve-Path -LiteralPath $Repository).Path
$gatewayBuild = Join-Path $gatewayRoot "target/harness/$RunId"
if (Test-Path -LiteralPath $gatewayBuild) { throw 'Verification output must be fresh.' }
Push-Location -LiteralPath $gatewayRoot
try {
    & (Join-Path $gatewayRoot 'mvnw.cmd') -B -ntp "-Dgateway.build-directory=target/harness/$RunId" verify
    $gatewayVerifyExit = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $gatewayVerifyExit
