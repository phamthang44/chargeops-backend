param(
  [switch]$KeepTailscaleOnline
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot

function Write-Step($Message) {
  Write-Host ""
  Write-Host "==> $Message" -ForegroundColor Cyan
}

Push-Location $ProjectRoot
try {
  Write-Step "Disabling public Tailscale Funnel"
  try {
    tailscale funnel --https=443 off
  } catch {
    Write-Warning "Could not disable Funnel, or it was already off: $($_.Exception.Message)"
  }

  Write-Step "Stopping Docker services"
  docker compose down

  if ($KeepTailscaleOnline) {
    Write-Step "Leaving Tailscale connected because -KeepTailscaleOnline was passed"
  } else {
    Write-Step "Disconnecting this machine from Tailscale"
    try {
      tailscale down
    } catch {
      Write-Warning "Could not disconnect Tailscale: $($_.Exception.Message)"
    }
  }

  Write-Host ""
  Write-Host "Demo services are stopped. Public Funnel is off." -ForegroundColor Green
} finally {
  Pop-Location
}
