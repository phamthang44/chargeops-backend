# Security abuse V1 — independent review

Review date: 2026-10-27. Scope: backend/gateway implementation, Redis Lua, HTTP/STOMP integration, deployment wiring, Keycloak protection/aggregate metrics, Grafana/Alloy provisioning and frontend courtesy guards. This is an independent source review with one executable path-matching probe, not a production penetration test. A follow-up pass checked the fixes and found no new critical trust-boundary, cooldown-attribution, privacy or live-session race defect.

## Follow-up observations

No remaining blocking findings from this review. Runtime rollout gates below remain unverified; source review approval is not production deployment acceptance.

## Findings already corrected during review

- **P2 — frontend account isolation.** Console and Driver courtesy-cache keys now include issuer/subject identity, preventing one account's cached response from suppressing a different account's request after login. Same-subject token refresh retains the cache. The separate context reviewer reports eight frontend fixtures passing, including the identity scenarios; this reviewer checked the final helper/key construction.
- **Noncritical — first cooldown alert.** Backend now exposes `chargeops.abuse.last.cooldown` and the Grafana alert checks `max((time() - chargeops_abuse_last_cooldown) < bool 300)`. The first observed cooldown is visible without requiring a counter delta after an earlier scrape.
- **P2 — Redis outage latency.** Production limiter now uses private `AbuseRedisStore` with 500ms command and socket-connect deadlines; shared booking/hold Redis settings remain unchanged. Added Java test listens on a socket that never answers the Redis handshake and requires `SEC_003` in under three seconds; implementation test results are recorded separately.
- **P2 — correctly attributed OBSERVE evidence.** `acquire` now returns the actual decision in every mode; HTTP/STOMP callers gate enforcement on ENFORCE. The HTTP filter records profile context in OBSERVE and ENFORCE with explicit `scope`, `subjectEvidence` and mode. IP-affected profile context is distinguishable from subject evidence.
- **P2 — Keycloak read-back.** Script now compares every allowlisted written/restored protection field against its requested value before reporting successful configuration read-back. The implementer reports mocked apply/restore/mismatch checks passing; this does not establish the live realm state.
- **P1 — encoded route spelling bypass.** Initially the filter classified raw `getRequestURI()` strings, allowing an encoded ordinary letter to miss the group while MVC matched the decoded route. Executed against cached Spring Web 7.0.8: `PathPatternParser.defaultInstance.parse("/api/v1/bookings").matches(PathContainer.parsePath("/api/v1/%62ookings"))` printed `ENCODED_ROUTE_MATCH=true`. The implementer added URI decoding in `AbuseGroup.match`; retain a regression test covering this exact spelling and message route aliases.
- **P2 — Docker environment forwarding.** The demo backend originally did not forward `ABUSE_MODE`, `ABUSE_HMAC_KEY` or `ABUSE_TRUSTED_PROXIES`; setting a Compose interpolation environment would not configure the container. Explicit variables have now been added to the demo backend service. Validate the rendered service environment without printing secrets.
- **P2 — stale WebSocket slot after a close/CONNECT race.** Binding after the profile lookup could resurrect a session-subject entry after transport cleanup, exhausting the new cap. Binding now checks for a live tracked session and uses the same monitor as close/error removals. Retain a disconnect-during-authentication regression test and verify subsequent CONNECT can reclaim capacity.

## Controls inspected

- NGINX uses the connector's isolated static ingress address as the only `CF-Connecting-IP` assertion source, then overwrites forwarded client headers.
- Demo gateway uses explicit API network peer `172.30.241.2`; backend is `172.30.241.3` and trusts only that gateway `/32` by default. The connector network is separate, avoiding broad default-network proxy trust.
- Auth request keys are selected at server level, avoiding the existing `^~ /realms/` prefix bypassing regex locations. Broker callbacks and static assets do not match POST login-action keys.
- The six-key Redis script reserves IP/subject quotas atomically, assigns no subject strike on an IP rejection, counts distinct violation minutes, and does not extend a live cooldown on rejected retries.
- Creation/export/message routes are isolated from ongoing booking/payment/charging actions; three ticket message aliases share one group.
- STOMP abuse interceptor follows existing auth/authorization; lifecycle/heartbeat frames are not charged. Registry count-and-bind is synchronized and rejects identity rebinding.
- Issuer substring acceptance was removed and replaced by equality with the configured issuer. Baseline blame places the permissive predicate in commit `52b796a8`; no original validation was deleted without a stricter replacement. Registry binding baseline came from `0b249929`.
- Keycloak login polling is opt-in and bounded to 1000 events; only aggregate values enter metrics. Raw event identities/token/provider response bodies are not logged, and disabled/misconfigured event storage reports unhealthy/unknown rather than a trustworthy zero.

## Validation limits

No live Cloudflare header chain, Keycloak realm apply or public OAuth/native callback was exercised by this reviewer. The implementer reports 11 isolated real Redis/NGINX runtime checks passing; this reviewer inspected the fixture/source but did not rerun those checks. Final Java/WS and frontend regression results are supplied by the implementation verification. The last follow-up source pass confirmed resolved status for the frontend identity partition and first-event alert changes. No existing source files were changed by the reviewer; only this review artifact was added/updated.
