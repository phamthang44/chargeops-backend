param([switch]$Demo)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot
$BaseCompose = if ($Demo) { 'docker-compose.demo.yml' } else { 'docker-compose.yml' }
$ComposeArgs = @('compose', '--env-file', '.env', '-f', $BaseCompose, '-f', 'compose.domain.yml')
$BackupDir = Join-Path $ProjectRoot ('.codex-tmp/keycloak-domain-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $BackupDir | Out-Null

# Credentials are expanded inside the local Keycloak container, never printed.
& docker @ComposeArgs exec -T keycloak sh -c '/opt/keycloak/bin/kcadm.sh config credentials --server http://127.0.0.1:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1'
if ($LASTEXITCODE -ne 0) { throw 'Keycloak login failed. Start the local Keycloak service first.' }

$RealmJson = & docker @ComposeArgs exec -T keycloak /opt/keycloak/bin/kcadm.sh get realms/chargeops --fields realm,attributes
if ($LASTEXITCODE -ne 0) { throw 'Could not read the chargeops realm.' }
$RealmJson | Set-Content -LiteralPath (Join-Path $BackupDir 'realm.json') -Encoding utf8

$ClientsJson = & docker @ComposeArgs exec -T keycloak /opt/keycloak/bin/kcadm.sh get clients -r chargeops --fields id,clientId,redirectUris,webOrigins,rootUrl,baseUrl,adminUrl
if ($LASTEXITCODE -ne 0) { throw 'Could not read realm clients.' }
$Clients = ($ClientsJson -join "`n") | ConvertFrom-Json
foreach ($ClientId in @('chargeops-web', 'chargeops-driver-mobile', 'chargeops-observability')) {
    $Client = $Clients | Where-Object { $_.clientId -eq $ClientId }
    if (-not $Client) { throw "Missing client $ClientId. Do not create it with guessed settings." }
    $Client | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $BackupDir ($ClientId + '.json')) -Encoding utf8
    $Origin = switch ($ClientId) {
        'chargeops-web' { 'https://console.chargeops.online' }
        'chargeops-driver-mobile' { 'https://driver.chargeops.online' }
        'chargeops-observability' { 'https://grafana.chargeops.online' }
        default { throw "Unknown public client $ClientId. Do not guess its redirect URI." }
    }
    $Redirect = if ($ClientId -eq 'chargeops-observability') { "$Origin/login/generic_oauth" } else { "$Origin/*" }
    $OldPublicOrigins = 'https://thang\.tail704409\.ts\.net(?::(?:8443|10000))?|https://chargeops\.online'
    $Redirects = @($Client.redirectUris | ForEach-Object { $_ -replace $OldPublicOrigins, $Origin })
    $Origins = @($Client.webOrigins | ForEach-Object { $_ -replace $OldPublicOrigins, $Origin })
    # Preserve native app schemes and localhost redirects. Add the new public domain.
    $Patch = @{
        redirectUris = @(@($Redirects) + @($Redirect) | Select-Object -Unique)
        webOrigins = @(@($Origins) + @($Origin) | Select-Object -Unique)
    }
    $PublicRoot = if ($ClientId -eq 'chargeops-observability') { "$Origin/" } else { $Origin }
    $Patch['rootUrl'] = $PublicRoot
    $Patch['baseUrl'] = $PublicRoot
    foreach ($Property in @('rootUrl', 'baseUrl', 'adminUrl')) {
        if ($Property -eq 'adminUrl' -and $Client.$Property) {
            $Patch[$Property] = $Client.$Property -replace $OldPublicOrigins, $Origin
        }
    }
    ($Patch | ConvertTo-Json -Depth 20) | & docker @ComposeArgs exec -T keycloak sh -c 'cat > /tmp/chargeops-domain-client.json'
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare client patch.' }
    & docker @ComposeArgs exec -T keycloak /opt/keycloak/bin/kcadm.sh update "clients/$($Client.id)" -r chargeops -f /tmp/chargeops-domain-client.json
    if ($LASTEXITCODE -ne 0) { throw "Client update failed: $ClientId. Backup: $BackupDir" }
    Write-Host "Updated public URLs: $ClientId"
}
Write-Host "Keycloak URL backups: $BackupDir"
Write-Host 'Google Cloud OAuth must separately include https://auth.chargeops.online/realms/chargeops/broker/google/endpoint'
