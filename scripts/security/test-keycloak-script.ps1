$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'configure-keycloak-abuse.ps1'
$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ('chargeops-keycloak-fixture-' + [guid]::NewGuid())
$fixtureScript = Join-Path $fixture 'scripts/security/configure-keycloak-abuse.ps1'
New-Item -ItemType Directory -Force -Path (Split-Path $fixtureScript) | Out-Null
Copy-Item -LiteralPath $source -Destination $fixtureScript
$global:chargeOpsRealmFixture = @{bruteForceProtected=$false;permanentLockout=$false;failureFactor=30;waitIncrementSeconds=60;maxFailureWaitSeconds=900;maxDeltaTimeSeconds=43200;quickLoginCheckMilliSeconds=1000;minimumQuickLoginWaitSeconds=60}
$global:chargeOpsMismatchFixture = $false
function global:docker {
    $global:LASTEXITCODE = 0
    if (($args -join ' ') -match ' get realms/chargeops ') { return ($global:chargeOpsRealmFixture | ConvertTo-Json) }
    if (($args -join ' ') -match ' update realms/chargeops') {
        for ($i=0;$i -lt $args.Length-1;$i++) {
            if ($args[$i] -eq '-s') {
                $pair = ([string]$args[$i+1]).Split('=',2)
                if ($global:chargeOpsMismatchFixture -and $pair[0] -eq 'maxDeltaTimeSeconds') { continue }
                $global:chargeOpsRealmFixture[$pair[0]] = ($pair[1] | ConvertFrom-Json)
            }
        }
    }
}
try {
    $output = & $fixtureScript -Container fixture -Apply
    if (-not $global:chargeOpsRealmFixture.bruteForceProtected -or $global:chargeOpsRealmFixture.failureFactor -ne 5) { throw 'Apply fixture failed' }
    $baseline = (Get-ChildItem (Join-Path $fixture 'logs/security') -Filter '*.json' | Sort-Object Name | Select-Object -First 1).FullName
    & $fixtureScript -Container fixture -Apply -RestoreSnapshot $baseline | Out-Null
    if ($global:chargeOpsRealmFixture.bruteForceProtected -or $global:chargeOpsRealmFixture.failureFactor -ne 30) { throw 'Restore fixture failed' }
    $global:chargeOpsMismatchFixture = $true
    $caught = $false
    try { & $fixtureScript -Container fixture -Apply | Out-Null } catch {
        if ($_.Exception.Message -match 'maxDeltaTimeSeconds') { $caught = $true } else { throw }
    }
    if (-not $caught) { throw 'Read-back mismatch was not rejected' }
    Write-Output 'PASS Keycloak script: apply all fields, restore baseline, reject mismatched read-back (mock only; no Docker/realm access).'
} finally { Remove-Item Function:\docker }
