# License–Station Eligibility Policy

> **Status:** Accepted business decision  
> **Effective date:** 2026-08-17  
> **Audience:** Backend, Frontend, Product/BA and QA  
> **Scope:** The relationship between Station status, License entitlement, Driver discovery and new bookings.
> **Decision precedence:** This document supersedes the Station–License synchronization conclusion in `docs/race-condition-analysis.md`. The remaining technical observations in that analysis still apply.

## 1. Purpose

This document is the single reference for deciding whether a station may accept **new business through ChargeOps**. It resolves the ambiguity between a station being approved by the platform and its owner having a valid platform license.

It does not define charger availability, operating hours, payment processing, refunds, or safety incidents. Those concerns may add further eligibility rules later.

## 2. Decision history: how this policy was reached

This section intentionally records the full reasoning path, including ideas that were later rejected. The purpose is not only to remember the final rule, but also to remember the business and technical problems that caused the design to change.

### 2.1 Starting point: a cross-entity race condition

The discussion started from a concurrency review, not from a product-policy discussion. The concrete scenario was:

```text
Admin A approves Station S1
Admin B suspends/cancels License L1 for S1 at nearly the same time
```

A possible interleaving is:

```text
T1  Admin A reads Station = PENDING_APPROVAL
T2  Admin A checks License = ACTIVE
T3  Admin B changes License ACTIVE -> SUSPENDED
T4  Admin A commits Station PENDING_APPROVAL -> ACTIVE
```

The final database state can therefore be:

```text
Station.status = ACTIVE
License.status = SUSPENDED
```

At first this looked like an obvious business inconsistency. The natural initial interpretation was: if the License is the right to operate through ChargeOps, a suspended License should make the Station suspended/unavailable as well. Under that interpretation, `Station ACTIVE + License SUSPENDED` would be invalid and the cross-entity race would need to be prevented.

This led to an early technical direction: lock the Station row pessimistically during approval and during License state changes, re-check the state after acquiring the lock, and reject the losing administrative action with a conflict message such as “the resource was changed by another administrator; reload and try again.”

That solution was technically defensible, but it depended on a business assumption that had not yet been confirmed.

#### Why optimistic locking alone did not answer the original race

The review also clarified an important technical point. `@Version` is effective when two transactions compete to update the **same persisted row**. For example, two administrators both loading the same License version and then attempting `suspend` versus `cancel` can be detected because both updates compete on `licenses.version`.

The Station-approval race is different:

```text
Transaction A updates Station
Transaction B updates License
```

Each transaction can successfully pass the version check on the entity it updates. There is no shared version column across the two rows, so independent optimistic locks do not prove that the combined Station+License business condition is still valid. This is why pessimistic locking on a shared row was initially considered.

The same reasoning also explains why putting `@Version` on append-only `LicenseStatusEvent` rows would not solve the problem. New event rows do not compete to update the same existing row. The concurrency decision belongs on the mutable business state/root, while the event is only the audit record of the result.

This distinction became important later: the project should choose a concurrency mechanism from the **specific race and business rule**, rather than assuming that pessimistic locking, optimistic locking, or atomic updates are universally required.

#### A separate race had a different solution: concurrent License issue

Another race found during the same review was two administrators issuing a License for the same Station at the same time. Both requests can pass an application-level `exists(...)` check before either transaction commits.

Unlike the Station/License cross-entity question, this rule already had a clear database invariant: at most one `ACTIVE` License per Station. The partial unique index is therefore the final correctness guard. The application may pre-check for a friendlier fast failure, but correctness does not depend on that check.

The chosen response for that race is:

```text
DB partial unique constraint
+ translate the relevant constraint violation
-> HTTP 409 / ACTIVE_LICENSE_ALREADY_EXISTS
```

This path was preferred over adding pessimistic locking everywhere. It also reinforced the broader design rule: different races can legitimately have different protections.

### 2.2 First business model considered: synchronize Station and License status

The first simple model was effectively:

```text
License ACTIVE       -> Station may be ACTIVE
License SUSPENDED    -> Station becomes SUSPENDED/INACTIVE
License EXPIRED      -> Station becomes SUSPENDED/INACTIVE
License reactivated  -> Station becomes ACTIVE again
```

This model was attractive because it was easy to explain, easy to visualize, and initially seemed easy to defend: a Station without a usable License should not operate on the platform.

However, once the model was followed through, several business ambiguities appeared:

- A Station can be unavailable for reasons that have nothing to do with the License: rejection, manual suspension, withdrawal, maintenance, safety, or other future operational reasons.
- If License expiry automatically changes Station status, the system later needs to know *why* the Station is not active. Otherwise a License renewal could incorrectly reactivate a Station that was suspended for another reason.
- If a License becomes valid again, it is unclear whether Station status should automatically be restored, restored only in some cases, or require another administrator action.
- Automatic synchronization creates coupling between two state machines that represent different concepts.
- Time-based License expiry would require a scheduler/event/reconciliation mechanism to mutate Station state purely to keep the two tables synchronized.

The model was therefore simple only at the first transition. The reverse transitions and exceptional cases made it progressively harder to reason about.

### 2.3 Business doubt: should an expired License immediately disable the Station?

The next question was more practical: an owner may simply forget to renew, may be late paying, may be resolving an administrative issue, or may have another temporary reason for the License to lapse. It felt unnecessarily destructive for the Station itself to become deactivated as if the Station had failed an operational or safety requirement.

This revealed an important distinction:

```text
Station status
= the approval/operational state of the Station itself

License status
= the owner's current entitlement to accept new business through ChargeOps
```

Once these meanings are separated, the following combination is no longer inherently corrupt:

```text
Station.status = ACTIVE
License.status = SUSPENDED
```

The Station can remain an approved Station in the platform database while the owner temporarily lacks entitlement to take new Driver business. Owner/Admin users still need to see and manage the Station; only Driver-facing commercial eligibility changes.

This was the point where the original cross-entity race changed meaning. The race still exists technically, but the resulting pair of statuses is no longer automatically a business bug.

### 2.4 Grace period was considered, then deliberately removed from scope

A grace period was considered as a softer business policy for accidental expiry:

```text
License expires
-> allow several extra days
-> warn the owner
-> only later hide/block the Station if still not renewed
```

This would potentially improve owner experience, but it immediately introduced more unanswered policy questions:

- How many grace days are allowed?
- Does the Station accept new bookings during grace?
- What happens to a booking scheduled after the grace period ends?
- When and how often should the owner be warned?
- Is grace available after `SUSPENDED`, or only after natural `EXPIRED`?
- Does grace affect payment, renewal dates, or reporting?

These are valid product questions, but they are beyond the certainty needed for the current final-year project. Grace period was therefore kept as an explicit non-goal rather than inventing arbitrary business policy.

### 2.5 Second design direction: keep statuses independent and check eligibility at point of use

After separating the concepts, a simpler design emerged:

```text
Station remains in its own status.
License remains in its own status.
The system calculates whether the Station may accept new Driver business.
```

For the License–Station gate:

```text
Driver eligible
= Station is ACTIVE
AND License is effectively active now
```

This moves the important protection to the places where the business is actually consumed:

```text
Driver search/map
Create booking
Other future entry points that create new Driver business
```

Hiding a Station on the frontend is not sufficient, because a client can call the booking API directly. The server-side booking use case must re-check the same policy.

This approach avoids maintaining a derived Station status solely because the License changed. It also removes the need for pessimistic locking whose only purpose would be to keep the two status fields synchronized.

### 2.6 Trade-off discovered: point-of-use validation is more work

The independent-status model has a cost: checking only

```text
station.status == ACTIVE
```

is no longer enough to answer “can a Driver use this Station for new business?” The system must also evaluate License usability.

That creates a risk if the rule is copied manually into many services or queries: search may check one rule, booking may check another, and one endpoint may forget the License entirely.

The mitigation is to treat eligibility as one reusable business policy/query instead of scattering boolean checks throughout the codebase. Conceptually:

```text
Station status     -> one input
License usability  -> one input
Driver eligibility -> shared policy
```

Search/map and booking creation must use the same definition. This is intentionally a small increase in validation complexity in exchange for avoiding much larger lifecycle synchronization complexity.

### 2.7 A related inconsistency exposed during the review: what does “ACTIVE License” mean?

The concurrency analysis also exposed two different meanings of “active License” in the code:

```text
Query A: status == ACTIVE

Query B: status == ACTIVE
         AND startAt <= now
         AND now < expiresAt
```

For example:

```text
status = ACTIVE
expiresAt = yesterday
```

A status-only check says the License is active, while the time-aware check says it is already unusable. This can make one use case reject a replacement License while another use case treats the same old License as expired.

The decision was therefore to distinguish persisted state from effective business validity. A License is usable only when:

```text
license.status == ACTIVE
AND license.startAt <= now
AND now < license.expiresAt
```

This rule is used even if a scheduler has not yet persisted `status = EXPIRED`. A scheduler remains useful for reconciliation, audit history, and keeping persisted state tidy, but Driver protection must not depend on scheduler timing.

### 2.8 New question created by the policy: what happens to bookings already paid for?

Once License loss was defined as blocking new business, another edge case appeared:

```text
10:00 Driver books and pays for 18:00
14:00 License becomes SUSPENDED/EXPIRED
18:00 Driver arrives to charge
```

The first possible rule was to block the Driver from starting, cancel the booking, and refund it. Technically this is consistent with “License is unusable now,” but it creates poor Driver experience and a much larger workflow:

```text
License problem
-> cancel existing booking
-> refund
-> notify Driver
-> handle possible dispute/failure
```

More importantly, the Driver already entered into a confirmed/paid commitment while the Station was eligible. Making the Driver bear the consequence of a later issue between Platform and Owner was considered undesirable for this project.

The policy was therefore refined from “License invalid means block everything” to:

```text
License loss stops NEW business only.
```

That means:

```text
New discovery after License loss          -> hide
New booking after License loss            -> reject
Existing confirmed/paid booking           -> preserve
Charging session already in progress      -> allow to finish
```

This is a deliberate customer-protection decision, not an accidental exception.

### 2.9 Important boundary: License problems are not the same as safety/operational suspension

The decision above applies specifically to License entitlement. It must not be generalized to every reason a Station becomes unavailable.

A future Station suspension caused by safety, fraud, legal prohibition, hardware danger, or another physical-operational problem may need to cancel bookings or prevent an already-booked Driver from charging. That is a different policy and is intentionally outside the current scope.

This distinction prevents the License policy from becoming a catch-all lifecycle rule.

### 2.10 What happened to the original pessimistic-lock proposal?

The original pessimistic-lock proposal was useful while the assumed invariant was:

```text
Station ACTIVE requires License ACTIVE at all times.
```

After the business model changed, this synchronization invariant was intentionally removed. Therefore, no pessimistic lock is required merely to prevent:

```text
Station ACTIVE + License SUSPENDED
```

because that combination is now valid.

Concurrency protection still exists where it has a concrete purpose:

- Same-License state changes use optimistic locking through `License.version` so two administrators cannot overwrite each other's state transition.
- Concurrent License issuance relies on the database partial unique index as the final guard and maps the relevant constraint violation to `ACTIVE_LICENSE_ALREADY_EXISTS`.
- Booking/payment concurrency remains a separate concern because those domains have direct races over scarce resources or money.

The general lesson recorded by this decision is: do not add a locking technique before confirming the business rule that the lock is supposed to protect.

### 2.11 Final reasoning chain

The decision can be reconstructed as:

```text
Cross-entity race discovered
        ↓
Initial assumption: Station and License statuses must stay synchronized
        ↓
Initial idea: suspend/expire License -> suspend/deactivate Station
        ↓
Questions appear: owner forgets renewal, reverse transition, other Station suspension reasons
        ↓
Grace period considered, but creates more undefined business policy
        ↓
Separate meanings of Station status and License entitlement
        ↓
Accept Station ACTIVE + License SUSPENDED as a valid stored state
        ↓
Use point-of-use eligibility for Driver discovery + new booking
        ↓
Accept extra validation complexity, centralize the rule
        ↓
Define effective License validity as status + time window
        ↓
Consider already-paid bookings
        ↓
Do not retroactively cancel/refund solely because License later becomes unusable
        ↓
Final rule: License controls NEW ChargeOps business, not the Station entity itself
```

This history explains why the final design is intentionally different from the first concurrency-oriented recommendation.

## 3. Core decision

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

## 4. Definitions

### 4.1 Station active

A station is active when:

```text
station.status == ACTIVE
```

In the current model, every other Station status (`PENDING_APPROVAL`, `REJECTED`, `SUSPENDED`, `WITHDRAWN`) is not Driver-eligible. The model does not have a generic `INACTIVE` status; use `station.status != ACTIVE` in business rules.

### 4.2 Effectively active License

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

### 4.3 Driver eligibility

For the License–Station gate alone:

```text
driverEligible(station, now)
  = station.status == ACTIVE
  AND station has an effectively active License at now
```

Driver discovery and creation of a new booking must enforce this same rule. Hiding a station in the UI is only user experience; the booking endpoint is the business-protection boundary.

## 5. Decision table

| Station status | License usability | Driver search/map | New booking | Owner/Admin management |
| --- | --- | --- | --- | --- |
| `ACTIVE` | Usable | Show | Allow | Allow |
| `ACTIVE` | Not usable | Hide | Reject | Allow |
| Not `ACTIVE` | Usable or not usable | Hide | Reject | Allow, subject to role/policy |

Other rules such as connector runtime status, operating hours and booking-slot availability are additional gates. They do not replace this policy.

## 6. Station approval and later License changes

Approving a station means:

```text
At the time of approval, the station is pending approval
and has an effectively active License.
```

It does **not** mean that the License must remain active forever. If an administrator later suspends, cancels or lets the License expire:

- The Station remains in its own status, normally `ACTIVE`.
- The Station becomes ineligible for Driver discovery and new bookings through the policy in section 4.3.
- No Station-status mutation, cross-entity event, or scheduled synchronization is required solely because of the License change.

This deliberately allows the valid combination `Station ACTIVE + License SUSPENDED`.

## 7. Existing bookings and charging sessions

Loss of License usability stops **new** business only.

| Situation when the License becomes suspended, cancelled or expired | Decision for this project |
| --- | --- |
| Driver search/map after the change | Do not show the station |
| New booking after the change | Reject |
| Existing confirmed/paid booking created before the change | Preserve; do not auto-cancel or auto-refund |
| Charging session already in progress | Allow it to finish |

This policy prevents a Driver who already paid from being penalized for a dispute between the platform and the station owner.

> This exception applies to **License** changes only. A future Station suspension for safety, legal, or physical-operational reasons needs its own booking/session policy and may require cancellation.

## 8. Concurrency policy

### 8.1 Station approval versus License change

No pessimistic lock is required merely to synchronize Station and License. The final state below is valid:

```text
Station ACTIVE + License SUSPENDED
```

The Driver-facing search and new-booking checks decide eligibility at their point of use.

### 8.2 Concurrent changes to the same License

Changes to one License (`suspend`, `cancel`, `reactivate`, `expire`) must use optimistic locking through `License.version`.

If two administrators act on stale copies of the same License:

1. One update succeeds and records its status event.
2. The other update fails with a conflict response.
3. The failed transaction must not persist a LicenseStatusEvent.

This protects terminal states such as `CANCELLED` from being overwritten by an outdated `SUSPENDED` request. Pessimistic locking is not the default solution.

### 8.3 Concurrent License issue

At most one `ACTIVE` License may exist per Station. The database partial unique index is the final guard against two administrators issuing simultaneously.

The expected API behaviour is:

```text
Winner: License is issued and activated.
Loser: HTTP 409 / ACTIVE_LICENSE_ALREADY_EXISTS.
```

The implementation must translate only the relevant unique-constraint violation into this business error. It must not convert unrelated database errors into a duplicate-license message.

## 9. Persistence and lifecycle rules

### 9.1 Optimistic lock column

`@Version` requires a physical `version bigint NOT NULL DEFAULT 0` column in `licenses`.

If the migration that adds this column has already run in a shared environment, create a new Flyway migration; never edit an applied migration because Flyway validates its checksum.

### 9.2 Expiry reconciliation

Authorization must always use the effective-active rule from section 4.2. A scheduler is therefore not required for Driver protection.

A future scheduler, or a reconciliation step before issuing a replacement License, must eventually persist:

```text
ACTIVE/SUSPENDED/PENDING → EXPIRED
```

and record a `LicenseStatusEvent` with actor `SYSTEM`. This keeps persisted state, audit history and the one-active-license database constraint aligned after time passes.

### 9.3 License status events

Every License state transition is append-only audit data. It records the transition, actor, time and optional reason.

`RENEW` is not a transition on the existing License. It creates a new License row, which starts with its own `ISSUED` event.

## 10. Implementation contract

The following behaviours are mandatory when the corresponding APIs are implemented:

1. Driver station search/map queries filter by Station `ACTIVE` and effective License validity.
2. `createBooking()` re-checks the same rule server-side before creating a booking.
3. Owner/Admin dashboards may show stations regardless of License usability, subject to their normal authorization rules.
4. Approval checks effective License validity at the time of approval only.
5. Same-License mutation commands use optimistic locking and return a conflict for stale updates.
6. License expiry never automatically changes Station status under this policy.

## 11. Explicit non-goals

The following are intentionally outside the current final-year-project scope:

- Grace periods after expiry.
- Automatic cancellation/refund caused only by a License change.
- In-platform purchase or payment processing for License fees.
- Automatic synchronization of Station and License statuses.
- Pessimistic locking across Station approval and License updates.
- A Station safety/operational-suspension policy for existing bookings and charging sessions.

## 12. One-sentence rule

> A License controls whether an otherwise active Station may accept **new business** through ChargeOps; it does not alter the Station itself or retroactively invalidate bookings already confirmed before the License became unusable.
