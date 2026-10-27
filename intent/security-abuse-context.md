# Security abuse V1 — implementation context

Read-only source baseline, 2026-10-27. This dossier records assumptions and boundaries, not exploit verdicts. No running realm, external firewall or live proxy headers were inspected.

## Trust boundaries

- Browser/native → Cloudflare → cloudflared → NGINX → REST/Keycloak. The domain overlay currently adds no dedicated ingress network (`compose.domain.yml:22`). Forwarded client IP is the gateway socket peer, not a restored visitor (`gateway/domain-proxy_params:3-4`). `CF-Connecting-IP` must only be trusted on the connector-only path; trusting the entire default Compose subnet also trusts unrelated containers.
- NGINX → backend: normalize and overwrite all identity-bearing proxy headers. A backend resolver must decide trust from the actual socket peer before interpreting forwarded headers; global forwarded-header rewriting can erase that evidence.
- Keycloak → Spring JWT decoder: JWT signature keys are resolved from the configured JWK URI, but current issuer validation accepts any issuer containing `/realms/chargeops` (`SecurityConfig.java:107`). HTTP and STOMP share that decoder.
- Validated JWT → ChargeOps profile: `CurrentActorFilter` loads account state before controllers; missing profile passes for bootstrap, existing suspended/deleted profile is rejected only on non-public method/path pairs (`CurrentActorFilter.java:61-64`). Cooldown should not alter account status or bypass this gate.

## Callers and lifecycle assumptions

- GitNexus context confirms `CurrentActorFilter` is constructed by `SecurityConfig.filterChain`; it reports no process membership. Graph sparsity does not reduce request-pipeline reach.
- GitNexus context confirms `WebSocketSessionRegistry.bindSubject` is called by `StompAuthChannelInterceptor.authenticate`. Subject binding currently overwrites the session mapping without a cap (`WebSocketSessionRegistry.java:39-41`). A cap requires atomic count+bind, idempotence for the same session, and rejection of CONNECT rebinding to another subject.
- HTTP `/ws` handshake is anonymous; identity becomes authenticated at STOMP CONNECT (`WebSocketConfig.java:70`, `StompAuthChannelInterceptor.java:108-123`). An HTTP-only subject limiter cannot protect SUBSCRIBE frames.
- Heartbeat (`command == null`), UNSUBSCRIBE and DISCONNECT are lifecycle traffic (`StompAuthChannelInterceptor.java:94-103`). Do not count them. SUBSCRIBE authorization/account checks precede any accepted subscription; retain those checks around rate limiting.
- Registry removes bindings on close, transport error and explicit session close (`WebSocketSessionRegistry.java:63-64,97-105`). Any new per-subject counter must be released on all paths, including handler establishment failure; count only live bound sessions.

## Exact route scope

- Protected mutations: POST `/api/v1/bookings` and POST `/api/v1/tickets`; message POST routes exist under `/api/v1/tickets/{id}/messages`, `/api/v1/owner/tickets/{id}/messages`, and `/api/v1/admin/tickets/{id}/messages`. These aliases must share the same subject/group budget.
- Export is GET `/api/v1/owner/bookings/export`; assistant paid call is POST `/api/v1/assistant/ask`. Assistant conversation reads are separate and must not consume paid-call quota.
- Do not prefix-block `/bookings/**` or `/tickets/**`: check-in, checkout, cancel, charging completion, ticket assignment and workflow reads remain available during a creation/message cooldown.
- Public station reads, administrative units/legal docs, actuator scrape, webhook POST `/api/v1/webhooks/sepay`, and bootstrap semantics remain separate from abuse-sensitive mutation groups. Keep public actuator routes out of NGINX public exposure.
- Authentication throttling must distinguish POST login-actions and token routes from resources, discovery, broker callback and authorize redirects. Gateway locations currently use `^~` `/realms/` and `/api/`; regex locations nested incorrectly under competing prefixes may never execute (`gateway/domain-templates/default.conf.template:18,39`).

## Unenforced assumptions / implementation checks

- Current domain config assumes every accepted request arrived through the TLS tunnel (`domain-proxy_params:7`). Direct local calls must not gain trusted proxy privileges from spoofed Host or headers.
- Keycloak, Redis and PostgreSQL currently publish host ports in base Compose (`docker-compose.yml:30,49,86`). Dedicated ingress alone does not close bypass ports; inspect merged Compose and host binding/firewall behavior without changing existing dev connectivity accidentally.
- Domain template `location ^~ /ws` matches more than the registered exact `/ws`; a handshake limiter must classify the real endpoint and avoid accidental path aliases.
- NGINX `add_header` inheritance is replaced when a child location declares headers. Error/CORS/Retry-After directives must be tested on actual 429 responses, including OPTIONS and untrusted Origin.
- Redis failure and observe mode need explicit semantics: observe must not deny, enforce must not silently allow sensitive operations on storage failure. Outage errors are not abuse strikes.
- Rejecting a shared-IP bucket must not add subject strikes. On subject rejection, avoid consuming unrelated IP quota unless the chosen atomic reservation policy intentionally does so. Cooldown TTL must not extend when rejected traffic continues.
- Avoid raw IP, JWT subject, query/body, password, token or arbitrary destination values in new security logs. Low-cardinality metric labels must exclude user/IP/trace identifiers.
- Realm configuration is runtime state, not proven by files. A script/runbook is not evidence that brute-force protection is enabled; baseline export must redact secrets and verification must report whether apply occurred.

## Required evidence

Gateway spoofed-header tests with trusted/untrusted peers; exact route classifier tests; concurrent Redis token/cooldown/TTL tests; shared-IP isolation and alias budgets; WebSocket concurrent CONNECT cap, repeated CONNECT, disconnect cleanup and heartbeat tests; fail-open observe versus fail-closed enforce; critical workflow/callback regressions. No live deployment or security scan claim is supported by this baseline.

## Independent frontend differential review — 2026-10-27

Scope: uncommitted Console transport/helper, query retry policy, error localization/card/ticket adapter, Driver security helper and booking/ticket adapters. Read-only review; no source edits. Parent reported six helper/transport fixture scenarios and both frontend typechecks passing; those results alone did not exercise the full booking error adapter.

Findings sent to implementer:

- **P1 — Driver booking adapter loses transport metadata.** `createBooking` generic failed-response branch still constructs `BookingApiError` without HTTP status or parsed Retry-After, while the intended new metadata code was inserted in `createCheckout`. A gateway 429 without a recognized SEC code becomes a generic booking failure; cached SEC errors can display the original body duration. Preserve status, trace and `securityRetryDetails` in the actual creation branch and test that branch.
- **P2 — Driver local cooldown crosses account changes.** The module-global `securityFetch` map is keyed only by method/URL. A second account on the same app instance inherits the first account's cached rejection without a network call. Partition by auth identity or clear on account transition; add a two-identity fixture. The server remains authoritative, but client courtesy state must not impersonate a different account's restriction.
- **P2 — Console ticket wait text can be stale.** `getTicketErrorMeta` uses the unchanged `details.retryAfterSeconds` from a reused ApiError rather than its stable deadline. Repeated rejected attempts display the original duration. Use remaining deadline seconds, with the ready state after expiry.

Positive checks: the new transports only cache rejected security responses, never cache successful mutation results or replay a mutation; the Console deadline uses WeakMap identity and is stable across rerenders; query retry excludes 429 and SEC codes; SEC_003 handling precedes broad moderation-503 handling in both ticket adapters; no new logout/refresh-on-429 branch found. Generic QueryClient retry overrides are contained by the transport guard for the same route.

### Resolution re-review

All three findings above are resolved in the reviewed working tree:

- Actual `createBooking` now preserves HTTP status, parsed wait details and trace ID; unintended checkout-only change was reverted.
- Console and Driver courtesy-cache keys now include JWT issuer+subject (decoded only for local cache partitioning, never server authorization), with opaque-credential fallback. Same-subject token refresh retains the local cooldown; a different account reaches the backend independently.
- Console ticket wait text now computes remaining seconds from the stable `retryDeadline` helper.

Independently ran `node scripts/security-rate-limit.test.cjs` from the frontend root: **8 scenarios PASS**, including actual `createBooking` handling a non-JSON gateway 429, identity isolation/refresh, no mutation replay, stable deadlines, and SEC_003 separation from moderation. Code re-review found no further actionable frontend security defects in scope. Console's token supplier is called before checking its identity-partitioned courtesy cache; a cached ApiError is rethrown without becoming a network/authentication failure. No automatic replay or logout was introduced.

Frontend review findings are closed. No native-device or live-browser acceptance was performed in this review; full UI acceptance remains a rollout activity.
