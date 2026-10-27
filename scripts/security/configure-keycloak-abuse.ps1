[CmdletBinding()]
param(
    [string]$Container = 'chargeops-keycloak-demo',
    [switch]$Apply,
    [string]$RestoreSnapshot
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$snapshotDirectory = Join-Path $root 'logs/security'
$fields = 'bruteForceProtected,permanentLockout,failureFactor,waitIncrementSeconds,maxFailureWaitSeconds,maxDeltaTimeSeconds,quickLoginCheckMilliSeconds,minimumQuickLoginWaitSeconds'
# Values are expanded only inside the container. Never echo credentials or tokens.
& docker exec $Container sh -c 'exec /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"' *> $null
if ($LASTEXITCODE -ne 0) { throw 'Keycloak admin authentication failed; no configuration changed.' }
$baseline = & docker exec $Container /opt/keycloak/bin/kcadm.sh get realms/chargeops --fields $fields
if ($LASTEXITCODE -ne 0) { throw 'Cannot read current realm protection; no configuration changed.' }
$baselineObject = ($baseline -join "`n") | ConvertFrom-Json
New-Item -ItemType Directory -Force -Path $snapshotDirectory | Out-Null
$snapshotPath = Join-Path $snapshotDirectory ('keycloak-abuse-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.json')
$baselineObject | ConvertTo-Json | Set-Content -LiteralPath $snapshotPath -Encoding utf8
Write-Output "Security-only baseline saved: $snapshotPath"
if (-not $Apply) { Write-Output 'Read-only baseline complete. Use -Apply after the demo observation window.'; return }
if ($RestoreSnapshot) {
    $restore = Get-Content -LiteralPath $RestoreSnapshot -Raw | ConvertFrom-Json
    # Only allow known protection fields; never import an entire realm or credentials.
    $arguments = @('exec', $Container, '/opt/keycloak/bin/kcadm.sh', 'update', 'realms/chargeops')
    foreach ($name in $fields.Split(',')) {
        if ($restore.PSObject.Properties.Name -contains $name) {
            $arguments += @('-s', ($name + '=' + ([string]$restore.$name).ToLowerInvariant()))
        }
    }
} else {
    $arguments = @('exec', $Container, '/opt/keycloak/bin/kcadm.sh', 'update', 'realms/chargeops',
        '-s','bruteForceProtected=true','-s','permanentLockout=false','-s','failureFactor=5',
        '-s','waitIncrementSeconds=60','-s','maxFailureWaitSeconds=900','-s','maxDeltaTimeSeconds=900',
        '-s','quickLoginCheckMilliSeconds=1000','-s','minimumQuickLoginWaitSeconds=60')
}
& docker @arguments *> $null
if ($LASTEXITCODE -ne 0) { throw 'Keycloak protection update failed. Baseline is retained.' }
$after = & docker exec $Container /opt/keycloak/bin/kcadm.sh get realms/chargeops --fields $fields
if ($LASTEXITCODE -ne 0) { throw 'Update returned success but read-back failed; runtime state is UNVERIFIED.' }
$afterObject = ($after -join "`n") | ConvertFrom-Json
$expected = if ($RestoreSnapshot) { $restore } else {
    [pscustomobject]@{bruteForceProtected=$true;permanentLockout=$false;failureFactor=5;waitIncrementSeconds=60;maxFailureWaitSeconds=900;maxDeltaTimeSeconds=900;quickLoginCheckMilliSeconds=1000;minimumQuickLoginWaitSeconds=60}
}
foreach ($name in $fields.Split(',')) {
    if ($expected.PSObject.Properties.Name -contains $name) {
        if ([string]$afterObject.$name -cne [string]$expected.$name) {
            throw "Keycloak read-back mismatch for setting $name; runtime protection is UNVERIFIED."
        }
    }
}
Write-Output 'Keycloak protection written and security-only settings read back. Run login/callback smoke tests before acceptance.'
