# License–Station Eligibility Policy

> **Status:** Accepted business decision  
> **Effective date:** 2026-08-17  
> **Audience:** Backend, Frontend, Product/BA and QA  
> **Scope:** The relationship between Station status, License entitlement, Driver discovery and new bookings.
> **Decision precedence:** This document supersedes the Station–License synchronization conclusion in `docs/race-condition-analysis.md`. The remaining technical observations in that analysis still apply.

## 1. Purpose

This document is the single reference for deciding whether a station may accept **new business through ChargeOps**. It resolves the ambiguity between a station being approved by the platform and its owner having a valid platform license.

It does not define charger availability, operating hours, payment processing, refunds, or safety incidents. Those concerns may add further eligibility rules later.

## 2. Core decision

`Station.status` and `License.status` are independent. They must **not** be synchronized automatically.

| Concept | Meaning |
| --- | --- |
| `Station.status` | The approval and operational status of the station itself on the platform. |
| `License` | The station owner's entitlement to accept new business through ChargeOps. |
| Driver eligibility | A policy calculated from both Station and License; it is not stored as another status. |

Therefore, this is a valid state:

```text
Station.status = ACTIVE
License.status = SUSPENDED
```

The station remains approved and remains in the database. Its owner and administrators can still view and manage it. It simply cannot accept new Driver business until its license is usable again.

## 3. Definitions

### 3.1 Station active

A station is active when:

```text
station.status == ACTIVE
```

In the current model, every other Station status (`PENDING_APPROVAL`, `REJECTED`, `SUSPENDED`, `WITHDRAWN`) is not Driver-eligible. The model does not have a generic `INACTIVE` status; use `station.status != ACTIVE` in business rules.

### 3.2 Effectively active License

A License is usable at time `now` only when:

```text
license.status == ACTIVE
AND license.startAt <= now
AND now < license.expiresAt
```

This rule is named `isEffectivelyActiveAt(now)` in the License domain model.

The following licenses are **not usable** for new business:

- `PENDING`
- `SUSPENDED`
- `CANCELLED`
- `EXPIRED`
- `ACTIVE` but outside its effective time window

An `ACTIVE` license with an elapsed `expiresAt` is already unusable, even before a scheduler changes its persisted status to `EXPIRED`.

### 3.3 Driver eligibility

For the License–Station gate alone:

```text
driverEligible(station, now)
  = station.status == ACTIVE
  AND station has an effectively active License at now
```

Driver discovery and creation of a new booking must enforce this same rule. Hiding a station in the UI is only user experience; the booking endpoint is the business-protection boundary.

## 4. Decision table

| Station status | License usability | Driver search/map | New booking | Owner/Admin management |
| --- | --- | --- | --- | --- |
| `ACTIVE` | Usable | Show | Allow | Allow |
| `ACTIVE` | Not usable | Hide | Reject | Allow |
| Not `ACTIVE` | Usable or not usable | Hide | Reject | Allow, subject to role/policy |

Other rules such as connector runtime status, operating hours and booking-slot availability are additional gates. They do not replace this policy.

## 5. Station approval and later License changes

Approving a station means:

```text
At the time of approval, the station is pending approval
and has an effectively active License.
```

It does **not** mean that the License must remain active forever. If an administrator later suspends, cancels or lets the License expire:

- The Station remains in its own status, normally `ACTIVE`.
- The Station becomes ineligible for Driver discovery and new bookings through the policy in section 3.3.
- No Station-status mutation, cross-entity event, or scheduled synchronization is required solely because of the License change.

This deliberately allows the valid combination `Station ACTIVE + License SUSPENDED`.

## 6. Existing bookings and charging sessions

Loss of License usability stops **new** business only.

| Situation when the License becomes suspended, cancelled or expired | Decision for this project |
| --- | --- |
| Driver search/map after the change | Do not show the station |
| New booking after the change | Reject |
| Existing confirmed/paid booking created before the change | Preserve; do not auto-cancel or auto-refund |
| Charging session already in progress | Allow it to finish |

This policy prevents a Driver who already paid from being penalized for a dispute between the platform and the station owner.

> This exception applies to **License** changes only. A future Station suspension for safety, legal, or physical-operational reasons needs its own booking/session policy and may require cancellation.

## 7. Concurrency policy

### 7.1 Station approval versus License change

No pessimistic lock is required merely to synchronize Station and License. The final state below is valid:

```text
Station ACTIVE + License SUSPENDED
```

The Driver-facing search and new-booking checks decide eligibility at their point of use.

### 7.2 Concurrent changes to the same License

Changes to one License (`suspend`, `cancel`, `reactivate`, `expire`) must use optimistic locking through `License.version`.

If two administrators act on stale copies of the same License:

1. One update succeeds and records its status event.
2. The other update fails with a conflict response.
3. The failed transaction must not persist a LicenseStatusEvent.

This protects terminal states such as `CANCELLED` from being overwritten by an outdated `SUSPENDED` request. Pessimistic locking is not the default solution.

### 7.3 Concurrent License issue

At most one `ACTIVE` License may exist per Station. The database partial unique index is the final guard against two administrators issuing simultaneously.

The expected API behaviour is:

```text
Winner: License is issued and activated.
Loser: HTTP 409 / ACTIVE_LICENSE_ALREADY_EXISTS.
```

The implementation must translate only the relevant unique-constraint violation into this business error. It must not convert unrelated database errors into a duplicate-license message.

## 8. Persistence and lifecycle rules

### 8.1 Optimistic lock column

`@Version` requires a physical `version bigint NOT NULL DEFAULT 0` column in `licenses`.

If the migration that adds this column has already run in a shared environment, create a new Flyway migration; never edit an applied migration because Flyway validates its checksum.

### 8.2 Expiry reconciliation

Authorization must always use the effective-active rule from section 3.2. A scheduler is therefore not required for Driver protection.

A future scheduler, or a reconciliation step before issuing a replacement License, must eventually persist:

```text
ACTIVE/SUSPENDED/PENDING → EXPIRED
```

and record a `LicenseStatusEvent` with actor `SYSTEM`. This keeps persisted state, audit history and the one-active-license database constraint aligned after time passes.

### 8.3 License status events

Every License state transition is append-only audit data. It records the transition, actor, time and optional reason.

`RENEW` is not a transition on the existing License. It creates a new License row, which starts with its own `ISSUED` event.

## 9. Implementation contract

The following behaviours are mandatory when the corresponding APIs are implemented:

1. Driver station search/map queries filter by Station `ACTIVE` and effective License validity.
2. `createBooking()` re-checks the same rule server-side before creating a booking.
3. Owner/Admin dashboards may show stations regardless of License usability, subject to their normal authorization rules.
4. Approval checks effective License validity at the time of approval only.
5. Same-License mutation commands use optimistic locking and return a conflict for stale updates.
6. License expiry never automatically changes Station status under this policy.

## 10. Explicit non-goals

The following are intentionally outside the current final-year-project scope:

- Grace periods after expiry.
- Automatic cancellation/refund caused only by a License change.
- In-platform purchase or payment processing for License fees.
- Automatic synchronization of Station and License statuses.
- Pessimistic locking across Station approval and License updates.
- A Station safety/operational-suspension policy for existing bookings and charging sessions.

## 11. One-sentence rule

> A License controls whether an otherwise active Station may accept **new business** through ChargeOps; it does not alter the Station itself or retroactively invalidate bookings already confirmed before the License became unusable.
