# Keycloak setup for ChargeOps Observability

Code and Docker configuration are already prepared. Complete these steps in the
`chargeops` realm before opening Grafana.

## 1. Create the observability role

1. Open **Realm roles** → **Create role**.
2. Set the role name to `OBSERVABILITY_ADMIN` (case-sensitive).
3. Open your user → **Role mapping** and assign `OBSERVABILITY_ADMIN`.
4. Do not assign this role to other platform administrators.

## 2. Create the Grafana OIDC client

Create an OpenID Connect client with:

| Setting | Value |
|---|---|
| Client ID | `chargeops-observability` |
| Client authentication | On |
| Standard flow | On |
| Direct access grants | Off |
| Implicit flow | Off |
| Service accounts roles | Off |

Add the exact redirect URIs used by the environment:

- Local gateway: `http://localhost:8088/grafana/login/generic_oauth`
- Remote demo: `https://thang.tail704409.ts.net/grafana/login/generic_oauth`

Add the matching Web Origins:

- `http://localhost:8088`
- `https://thang.tail704409.ts.net`

Do not use `*` redirect URIs or Web Origins.

## 3. Make the realm role available to Grafana

Keep Keycloak's default `roles` client scope assigned to the client. After login,
the ID token or access token must contain:

```json
{
  "realm_access": {
    "roles": ["OBSERVABILITY_ADMIN"]
  }
}
```

If the claim is absent, add a **User Realm Role** protocol mapper with:

- Token claim name: `realm_access.roles`
- Multivalued: On
- Add to ID token: On
- Add to access token: On
- Add to userinfo: On

## 4. Copy the client secret

Open **Clients** → `chargeops-observability` → **Credentials**, then place the
secret in the uncommitted runtime environment file:

```dotenv
GRAFANA_OAUTH_CLIENT_ID=chargeops-observability
GRAFANA_OAUTH_CLIENT_SECRET=<copy-secret-here>
```

For the remote demo also set:

```dotenv
KEYCLOAK_PUBLIC_URL=https://thang.tail704409.ts.net
GRAFANA_ROOT_URL=https://thang.tail704409.ts.net/grafana/
GRAFANA_COOKIE_SECURE=true
```

Never commit the real client secret.

## 5. Acceptance check

1. Sign in to the Web Portal using your assigned account.
2. Open **Admin → Observability**.
3. Confirm Grafana loads inside the page without another password prompt.
4. Remove `OBSERVABILITY_ADMIN` temporarily and confirm Grafana denies access.
5. Confirm ports `9090`, `3100`, and `12345` are not reachable from the host or LAN.
