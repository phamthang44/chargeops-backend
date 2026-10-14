# ChargeOps public URL cutover

## Public hostnames

| Hostname | Destination | Cloudflare configuration |
| --- | --- | --- |
| `chargeops.online` | Marketing site on Vercel | Add as Vercel custom domain; use the exact DNS target Vercel displays. |
| `www.chargeops.online` | Redirect to `chargeops.online` | Add to Vercel and configure a canonical redirect. |
| `console.chargeops.online` | Admin/Owner/Staff Vite app on Cloudflare Pages | Pages custom domain for the Console project. |
| `driver.chargeops.online` | Expo Web on Cloudflare Pages | Pages custom domain for the Driver project. |
| `api.chargeops.online` | Backend REST and WebSocket via Cloudflare Tunnel | Published application route to `http://gateway:80`. |
| `auth.chargeops.online` | Keycloak via Cloudflare Tunnel | Published application route to `http://gateway:80`. |
| `grafana.chargeops.online` | Grafana via Cloudflare Tunnel | Optional published application route to `http://gateway:80`. |

Do not guess Vercel or Pages DNS targets; copy the values shown by each project after adding its custom domain. Keep Hostinger MX, SPF, DKIM, DMARC, and mail discovery records unchanged and DNS-only. The existing Tunnel owns the root and driver hostnames, so remove those two Tunnel routes/records as part of switching those DNS records to Vercel/Pages. Add API/Auth/Grafana Tunnel routes separately.

## Cloudflare Pages build settings

### Console (`chargeops-frontend` repository)

- Root directory: `chargeops-web`
- Build command: `npm ci && npm run build --workspace apps/web`
- Build output directory: `apps/web/dist` relative to the configured `chargeops-web` root.
- Build environment:

```text
VITE_API_URL=https://api.chargeops.online/api/v1
VITE_KEYCLOAK_ENABLED=true
VITE_KEYCLOAK_URL=https://auth.chargeops.online
VITE_KEYCLOAK_REALM=chargeops
VITE_KEYCLOAK_CLIENT_ID=chargeops-web
VITE_DRIVER_APP_URL=https://driver.chargeops.online
VITE_GRAFANA_URL=https://grafana.chargeops.online/
VITE_USE_MOCKS=false
```

Build locally from `chargeops-web` with `npm ci` and `npm run build --workspace apps/web`; upload `apps/web/dist` to the Console Pages project. The Vite public `_redirects` file preserves browser-router routes on Pages.

### Driver Web (same `chargeops-frontend` repository)

- Root directory: `chargeops-driver-mobile`
- Build command: `npm ci && npx expo export -p web`
- Build output directory: `dist`
- Build environment:

```text
EXPO_PUBLIC_KEYCLOAK_ISSUER_URL=https://auth.chargeops.online/realms/chargeops
EXPO_PUBLIC_KEYCLOAK_DRIVER_CLIENT_ID=chargeops-driver-mobile
EXPO_PUBLIC_KEYCLOAK_REDIRECT_SCHEME=chargeops
EXPO_PUBLIC_KEYCLOAK_ACCOUNT_URL=https://auth.chargeops.online/realms/chargeops/account
EXPO_PUBLIC_OWNER_PORTAL_URL=https://console.chargeops.online
EXPO_PUBLIC_API_BASE_URL=https://api.chargeops.online
EXPO_PUBLIC_WS_URL=wss://api.chargeops.online/ws
EXPO_PUBLIC_USE_MOCKS=false
```

Expo `EXPO_PUBLIC_*` values are bundled into browser code; never put passwords, client secrets, or tunnel tokens in them.

Build locally from `chargeops-driver-mobile` with `npm ci` and `npx expo export -p web`; upload its `dist` directory to the Driver Pages project.

### Marketing (Vercel)

- Project root: `chargeops-marketing`
- Framework: Next.js (auto-detect)
- Environment variable: `NEXT_PUBLIC_API_URL=https://api.chargeops.online`
- Add `chargeops.online` and optionally `www.chargeops.online` as custom domains. Configure the preferred canonical redirect in Vercel.

## Keycloak and OAuth

- Public hostname / issuer: `https://auth.chargeops.online` / `https://auth.chargeops.online/realms/chargeops`.
- Client `chargeops-web`: public web origin `https://console.chargeops.online`, redirect `https://console.chargeops.online/*`.
- Client `chargeops-driver-mobile`: public web origin `https://driver.chargeops.online`, redirect `https://driver.chargeops.online/*`; preserve native `chargeops://auth/callback`, local development callbacks, and Expo callbacks.
- Client `chargeops-observability`: redirect `https://grafana.chargeops.online/login/generic_oauth`.
- Google broker callback registered in Google Cloud Console: `https://auth.chargeops.online/realms/chargeops/broker/google/endpoint`.
- SePay webhook, if enabled: `https://api.chargeops.online/api/v1/webhooks/sepay`.

After the DNS/Tunnel routes resolve, start the backend demo stack with the updated `compose.domain.yml`, then apply the backed-up realm/client URL changes with:

```powershell
.\scripts\configure-keycloak-domain.ps1 -Demo
```

The script backs up the current realm/client URLs before changing them. Google Cloud Console and provider webhook settings are external and must be updated separately.

## Cutover order

1. Create the Vercel and Pages projects and build using preview domains.
2. Add `api`, `auth`, and optional `grafana` Tunnel routes to `http://gateway:80`.
3. At cutover, remove the old root/driver Tunnel routes and records, then attach the exact Vercel/Pages DNS records to root, Console, and Driver. Keep all Hostinger mail DNS records intact.
4. Update Keycloak hostname and client redirect/origin settings; add the Google broker callback above.
5. Build the backend with the new issuer/CORS settings, then test Console login, Google login, Driver Web login, API calls, WebSocket, booking emails, and the payment webhook.
6. After all flows work, remove old Keycloak redirect URIs that are no longer needed.

## Low-memory demo profile

Elasticsearch is disabled by default in the demo deployment and is available only with the Compose `search` profile. The backend's station discovery then uses its PostgreSQL implementation; Elasticsearch-backed ranking/indexing is unavailable. Local development Compose remains unchanged. To enable the demo search service intentionally, set `ELASTICSEARCH_ENABLED=true` and `ELASTICSEARCH_REINDEX_ON_STARTUP=true`, and start Compose with `--profile search`.
