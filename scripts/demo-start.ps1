param(
  [string]$PublicHost = "thang.tail704409.ts.net",
  [int]$KeycloakPort = 8080
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Realm = "chargeops"
$PublicBaseUrl = "https://$PublicHost"
$DiscoveryUrl = "http://localhost:$KeycloakPort/realms/$Realm/.well-known/openid-configuration"

function Write-Step($Message) {
  Write-Host ""
  Write-Host "==> $Message" -ForegroundColor Cyan
}

function Assert-Command($Name) {
  if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
    throw "Missing required command: $Name"
  }
}

Push-Location $ProjectRoot
try {
  Assert-Command docker
  Assert-Command tailscale

  Write-Step "Starting Docker services"
  docker compose up -d postgres redis keycloak mailpit

  Write-Step "Waiting for Keycloak on localhost:$KeycloakPort"
  $ready = $false
  for ($i = 1; $i -le 60; $i++) {
    try {
      $response = Invoke-WebRequest -UseBasicParsing -Uri $DiscoveryUrl -TimeoutSec 3
      if ($response.StatusCode -eq 200) {
        $ready = $true
        break
      }
    } catch {
      Start-Sleep -Seconds 2
    }
  }

  if (-not $ready) {
    throw "Keycloak did not become ready at $DiscoveryUrl"
  }

  Write-Step "Publishing Keycloak with Tailscale Funnel"
  tailscale funnel --bg --yes $KeycloakPort

  Write-Step "Demo endpoints"
  Write-Host "Keycloak public:     $PublicBaseUrl/realms/$Realm" -ForegroundColor Green
  Write-Host "OIDC discovery:      $PublicBaseUrl/realms/$Realm/.well-known/openid-configuration" -ForegroundColor Green
  Write-Host "Keycloak admin:      $PublicBaseUrl/admin" -ForegroundColor Green
  Write-Host "Mailpit local only:  http://localhost:8025" -ForegroundColor Green

  Write-Host ""
  Write-Host "When the demo is done, run:" -ForegroundColor Yellow
  Write-Host "  scripts\demo-stop.cmd" -ForegroundColor Yellow
} finally {
  Pop-Location
}
