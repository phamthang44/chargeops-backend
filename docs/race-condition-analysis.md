# Race Condition Analysis — License & Station State Transitions

> **Scope:** `LicenseServiceImpl.issueLicense`, `StationServiceImpl.approveStation/rejectStation`,
> and related entities (`License`, `Station`, `LicenseStatusEvent`).
>
> **Date:** 2026-08-17
>
> **Status:** Reconciled with `solution-license-station-active.md` and the current code on 2026-08-17.
> Section 0 is the authoritative conclusion. The original findings below are retained as historical reasoning; do not implement their superseded pessimistic-lock recommendations.

---

## 0. Final reconciliation

| Original issue | Final status | Current decision / remaining work |
|---|---|---|
| #1 — `License @Version` without DB column | **Resolved** | V8 adds `version bigint NOT NULL DEFAULT 0`. If an older V8 has already run in a shared DB, add a new migration rather than editing its checksum. |
| #2 — Concurrent issue requests | **Mitigated** | The partial unique index is the final guard. Only `ux_licenses_one_active_per_station` maps to `409 / LICENSE_002`; unrelated integrity errors are not mislabeled. No pessimistic lock is required. |
| #3 — Approve Station versus suspend/cancel License | **Not a data inconsistency** | `Station ACTIVE + License SUSPENDED` is a valid combination. Station and License statuses are independent; Driver discovery/new booking enforce the combined eligibility rule. |
| #4 — Two meanings of active License | **Application check resolved; persistence gap remains** | Approval and issue use `status ACTIVE AND startAt <= now < expiresAt`. A stale persisted ACTIVE row still needs scheduler/issue-time reconciliation to become `EXPIRED` before a replacement can activate. |
| #5 — Raw or mislabeled DB error | **Resolved** | The service translates only the named active-license unique constraint. |

Same-License mutations (`suspend`, `cancel`, `reactivate`, `expire`) must rely on `License.version`. One transaction wins; a stale update returns `409 / LICENSE_003`, and its `LicenseStatusEvent` rolls back with the failed transaction.

Current priority order:

1. Add expiry reconciliation with a SYSTEM status event.
2. Implement mutation APIs using optimistic locking and stable `LICENSE_003`–`LICENSE_006` errors.
3. Implement Station ACTIVE + effectively-active License checks in Driver discovery and new-booking creation.
4. Keep cross-entity pessimistic locking out unless measured contention or a new business invariant justifies it.

---

## 1. State Machine Reference

```
License status transitions:
  PENDING   → ACTIVE, CANCELLED
  ACTIVE    → SUSPENDED, CANCELLED, EXPIRED
  SUSPENDED → ACTIVE, CANCELLED, EXPIRED
  EXPIRED   → terminal
  CANCELLED → terminal

Station status transitions:
  PENDING_APPROVAL → ACTIVE (approve), REJECTED (reject)
  ACTIVE            → terminal (current impl)
  REJECTED          → terminal (current impl)
```

---

## 2. Current Protection Mechanisms

| Mechanism | Entity | DB Support |
|-----------|--------|------------|
| `@Version` optimistic lock | `Station` | `V7` adds `version bigint` column |
| `@Version` optimistic lock | `License` | `V8` now adds `version bigint NOT NULL DEFAULT 0` |
| Partial unique index | `License` | `V1:139-141` — one ACTIVE per station |
| State guard methods | `License` entity | Java-level checks in `suspend()`, `activate()`, `cancel()`, `markExpired()` |
| DB CHECK constraints | `license_status_events` | `V8:34-54` — validates transition pairs |

---

## 3. Issues Found

### Issue #1 — `License @Version` Without DB Column (CRITICAL)

**Location:** `License.java:89-90`

```java
@Version
private long version;
```

**Problem:** The `licenses` table is created in `V1__initial_core_schema.sql:120-135` **without a `version` column**. No subsequent migration (V2–V8) adds it. Hibernate will fail at runtime when it tries to include `version` in SELECT/UPDATE statements.

**Impact:** Every write to a `License` entity will throw a SQL error. Optimistic locking is completely non-functional.

**Fix:**

```sql
-- New migration
ALTER TABLE licenses ADD COLUMN version bigint NOT NULL DEFAULT 0;
```

---

### Issue #2 — Check-Then-Act Race in `issueLicense` (HIGH)

**Location:** `LicenseServiceImpl.java:49-60`

```java
// Both Request A and Request B read at the same time
if (licenseRepository.existsByStation_IdAndStatus(station.getId(), LicenseStatus.ACTIVE)) {
    throw new AppException(LicenseErrorCode.ACTIVE_LICENSE_ALREADY_EXISTS, stationId);
}
// ... both pass the check → both create PENDING → both activate
```

**Race timeline:**

| Time | Request A | Request B |
|------|-----------|-----------|
| T1 | `existsByStation_IdAndStatus(X, ACTIVE)` → false | |
| T2 | | `existsByStation_IdAndStatus(X, ACTIVE)` → false |
| T3 | `save(PENDING)` | |
| T4 | `activate()` → ACTIVE | |
| T5 | | `save(PENDING)` |
| T6 | | `activate()` → ACTIVE → **UNIQUE INDEX VIOLATION** |

**DB safety net:** The partial unique index `ux_licenses_one_active_per_station` prevents duplicate ACTIVE licenses. Request B's transaction rolls back.

**Remaining problem:** The error thrown is a raw `DataIntegrityViolationException`, not the friendly `ACTIVE_LICENSE_ALREADY_EXISTS` business error. The user sees a 500 instead of a meaningful message.

**Fix options:**

- **Option A (pessimistic lock):** Add `@Lock(LockModeType.PESSIMISTIC_WRITE)` on the Station query used before the check, so the second request blocks until the first commits.
- **Option B (catch constraint violation):** Wrap the save in a try-catch for `DataIntegrityViolationException` and convert to `AppException(ACTIVE_LICENSE_ALREADY_EXISTS)`.
- **Recommended:** Both — pessimistic lock for correctness, catch as fallback for safety.

---

### Issue #3 — Cross-Entity Race: Approve Station vs. Suspend/Cancel License (HIGH)

**Locations:**
- `StationServiceImpl.java:113-129` (`approveStation`)
- Future: any service calling `License.suspend()` or `License.cancel()`

**Race timeline:**

| Time | Admin A (Approve Station S1) | Admin B (Suspend License L1 for S1) |
|------|------------------------------|-------------------------------------|
| T1 | `approveStation(S1)` → read station (PENDING_APPROVAL) | |
| T2 | `requireCanBeApproved()` → check ACTIVE license → **true** | |
| T3 | | `suspendLicense(L1)` → read license (ACTIVE) |
| T4 | | `license.suspend()` → SUSPENDED |
| T5 | `station.setStatus(ACTIVE)` | |
| T6 | **Station = ACTIVE, License = SUSPENDED** | |

**Impact:** Station is ACTIVE but its license is SUSPENDED. Business rule violated — an active station should always have an active license.

**Why `@Version` doesn't help:** Optimistic locking on individual entities cannot detect cross-entity state inconsistency. Each entity's version check passes independently.

**Fix options:**

- **Option A (pessimistic lock on Station):** Lock the `stations` row with `SELECT ... FOR UPDATE` in both `approveStation` and any license status change that affects station validity. The second transaction blocks until the first commits, then re-reads and re-validates.
- **Option B (application-level state machine):** Before any license status change, check if the station is in a compatible state. Before station approval, re-check license status after acquiring the lock.
- **Option C (event-driven):** When license status changes, publish an event that re-evaluates station validity. Station transitions to a degraded state if license becomes invalid.

**Recommended:** Option A for immediate safety, Option C for long-term architecture.

**Sketch:**

```java
@Transactional
public void approveStation(UUID id) {
    // Lock station row to prevent concurrent license changes from interleaving
    Station station = stationRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, id));
    stationApprovalPolicy.requireCanBeApproved(station);
    // ... approve
}

@Transactional
public void suspendLicense(UUID licenseId) {
    License license = licenseRepository.findByIdForUpdate(licenseId)
            .orElseThrow(...);
    Station station = station.getStation();
    // Re-check: is station still in a state that allows this transition?
    license.suspend();
    // ... record event
}
```

Where `findByIdForUpdate` is:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT s FROM Station s WHERE s.id = :id")
Optional<Station> findByIdForUpdate(@Param("id") UUID id);
```

---

### Issue #4 — Inconsistent Active License Check (MEDIUM)

**Locations:**
- `LicenseServiceImpl.java:49` — uses `existsByStation_IdAndStatus(stationId, ACTIVE)`
- `StationApprovalPolicyImpl.java:25` — uses `existsActiveLicenseForStation(stationId, Instant.now())`

**Problem:** Two different queries with different semantics:

| Query | Checks | Used by |
|-------|--------|---------|
| `existsByStation_IdAndStatus` | `status = ACTIVE` (no time bounds) | `issueLicense` |
| `existsActiveLicenseForStation` | `status = ACTIVE AND startAt <= now < expiresAt` | `StationApprovalPolicy` |

**Scenario:** License L1 for station S1 is ACTIVE but past `expiresAt` (time expired but status not yet updated to EXPIRED — no scheduled job exists).

- `issueLicense` → **rejects** (sees ACTIVE license, even though expired)
- `StationApprovalPolicy` → **allows** approval (time-bounded check says not active)

**Impact:** Inconsistent business rules. Admin cannot issue a new license for a station whose license has effectively expired.

**Fix:** `issueLicense` should use the time-bounded query:

```java
if (licenseRepository.existsActiveLicenseForStation(station.getId(), Instant.now())) {
    throw new AppException(LicenseErrorCode.ACTIVE_LICENSE_ALREADY_EXISTS, stationId);
}
```

---

### Issue #5 — `issueLicense` Error Handling for Constraint Violation (LOW)

**Location:** `LicenseServiceImpl.java:60`

When Issue #2 triggers and the partial unique index fires, the raw exception bubbles up as a 500 Internal Server Error.

**Fix:** Catch and convert:

```java
try {
    License savedLicense = licenseRepository.save(license);
    // ... activate, record events
} catch (DataIntegrityViolationException e) {
    throw new AppException(LicenseErrorCode.ACTIVE_LICENSE_ALREADY_EXISTS, stationId);
}
```

Or rely on the pessimistic lock from Issue #2 fix to prevent this path entirely.

---

## 4. Summary Table

| # | Issue | Severity | Currently exploitable? | Fix |
|---|-------|----------|----------------------|-----|
| 1 | `License @Version` without DB column | **CRITICAL** | Yes — all License writes fail | Add migration for `version` column |
| 2 | Check-then-act race in `issueLicense` | **HIGH** | Yes — concurrent issue requests | Pessimistic lock + catch constraint |
| 3 | Cross-entity race: approve station vs. license change | **HIGH** | Partially (only approve exists today; suspend/cancel not wired yet) | Pessimistic lock on Station row |
| 4 | Inconsistent active license check | **MEDIUM** | Yes — when license is expired but status not updated | Use time-bounded query in `issueLicense` |
| 5 | Raw DB error on constraint violation | **LOW** | Yes — users see 500 instead of business error | Catch `DataIntegrityViolationException` |

---

## 5. Recommended Fix Order

1. **Issue #1** — Add `version` column migration (unblocks everything)
2. **Issue #4** — Align `issueLicense` to use `existsActiveLicenseForStation`
3. **Issue #2** — Add pessimistic lock in `issueLicense` flow
4. **Issue #3** — Add pessimistic lock in `approveStation` and future license state change services
5. **Issue #5** — Add constraint violation error handling as defensive layer

---

## 6. Files Involved

| File | Role |
|------|------|
| `LicenseServiceImpl.java` | `issueLicense` — creates and activates license |
| `StationServiceImpl.java` | `approveStation`, `rejectStation` — station state transitions |
| `StationApprovalPolicyImpl.java` | Validates license exists before station approval |
| `LicenseRepository.java` | Two active-license existence queries |
| `License.java` | Entity with `@Version` (no DB column) and state machine methods |
| `Station.java` | Entity with `@Version` (has DB column) |
| `V1__initial_core_schema.sql` | Creates `licenses` table without `version` column |
| `V7__add_station_optimistic_lock_version.sql` | Adds `version` to `stations` only |
| `V8__add_license_status_events_table.sql` | Audit trail with DB-level transition constraints |
