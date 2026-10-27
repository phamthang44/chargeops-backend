---
status: verified-local-pending-rollout
approved: 2026-10-27
---
# Rate limiting and abuse protection V1

Problem: API/authentication abuse can exhaust demo resources; IP-only account bans can lock innocent users behind shared networks.

Outcome: trusted client IP attribution, gateway limits, atomic Redis quotas and scoped cooldowns, temporary Keycloak login protection, actionable private observability and client wait feedback. Account suspension remains an Admin decision under UM-00.

Constraints: existing NGINX/Redis/Keycloak, one demo backend, no paid edge dependency, no schema migration, no automated account suspension, preserve public domains and ongoing booking/payment/charging work.

Approved by the user's implementation request. The authoritative Vietnamese plan and rollout runbook are maintained in the separate Vault repository under `Security/`. Runtime rollout is gated on the observation window and live smoke tests, not on code completion.
