# Implementation contract — security abuse V1

User-approved plan: Vault `Security/rate-limit-abuse-v1-plan.vi.md`.

Changes, in dependency order:

1. `gateway/domain-*`, Compose: single trusted cloudflared ingress peer; separate API ingress in container demo; allowlisted CORS/errors; rate zones and observe defaults; loopback service publishing.
2. `infra/security/abuse`, `SecurityConfig`, CORS: private Redis connection with finite deadlines, atomic Lua quotas/strikes/cooldowns, normalized route/IP matching, standard errors, strict issuer/subject validation, private telemetry.
3. WebSocket registry/config: atomic live-session reservation, identity immutability, post-auth subscription quota; cleanup after disconnect/error.
4. Frontend shared client/error feedback and Driver booking/ticket adapters: bounded local wait guards, Retry-After parsing, no mutation replay/logout, VI/EN copy.
5. Keycloak baseline/apply/restore script; aggregate event collector; Grafana dashboard/alerts; Alloy gateway ingestion; Vault rollout/rollback instructions.

Risks: shared NAT, encoded path bypass, forged proxy headers, concurrent quota acquisition, stale WebSocket slots, Redis latency/outage, generic frontend 503 handling. Independent review: `security-abuse-context.md` and `security-abuse-review.md`.

Proof:

- Backend targeted Maven security/auth/account/WebSocket/Assistant suites; Docker-dependent integration tests must be reported as skipped when unavailable.
- `scripts/security/start-isolated-test-runtime.sh` then `test-abuse-runtime.py <isolated-runtime>`: real Redis Lua/concurrency and real NGINX config/HTTP contracts.
- Frontend `node scripts/security-rate-limit.test.cjs`, Console and Driver `tsc --noEmit`, Console build.
- Compose `config --quiet` against example environment only; PowerShell parser/mock read-back tests; JSON/YAML validation; GitNexus scope check and diff/secret review.

Code completion does not imply the live Cloudflare connector, Keycloak realm, Grafana provisioning or enforcement has been deployed. Record exact local evidence and remaining live checks in Vault before committing/pushing its documentation.
