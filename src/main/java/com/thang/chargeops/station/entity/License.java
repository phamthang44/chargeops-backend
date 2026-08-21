package com.thang.chargeops.station.entity;

import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.Plan;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.exception.LicenseDomainException;
import com.thang.chargeops.station.exception.LicenseViolation;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 *   State Transition đề xuất <br>
 *     PENDING   -> ACTIVE, CANCELLED <br>
 *     ACTIVE    -> SUSPENDED, CANCELLED, EXPIRED <br>
 *     SUSPENDED -> ACTIVE, CANCELLED, EXPIRED <br>
 *     EXPIRED   -> terminal <br>
 *     CANCELLED -> terminal <br>
 *     PENDING <br>
 *     License đã tồn tại nhưng chưa có hiệu lực. <br>
 *     Thông thường now < startAt.
 * <p>
 *     ACTIVE <br>
 *     License đang được phép sử dụng. <br>
 *     startAt <= now < expiresAt.
 * <p>
 *     SUSPENDED <br>
 *     Tạm vô hiệu hoá thủ công. <br>
 *     Thời hạn vẫn tiếp tục chạy.
 * <p>
 *     CANCELLED <br>
 *     Bị chấm dứt thủ công. <br>
 *     Không thể khôi phục.
 * <p>
 *     EXPIRED <br>
 *     Đã hết thời hạn. <br>
 *     Không thể khôi phục.
 * <p>
 *     RENEW <br>
 *     Không phải state transition. <br>
 *     Tạo một License row mới. <br>
 *     Active license còn hạn <br>
 *             ↓ renew <br>
 *     New license = PENDING <br>
 *     startAt = old.expiresAt <br>
 *             ↓ tới startAt <br>
 *     Old -> EXPIRED <br>
 *     New -> ACTIVE <br>
 * <p>
 *  “Trạng thái chưa terminal
 *   PENDING, ACTIVE, SUSPENDED
 * <p>
 * */


@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Entity
@Table(
        name = "licenses",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_licenses_renewed_from",
                columnNames = "renewed_from_license_id"
        )
)
public class License extends AuditableEntity {

    @JoinColumn(name = "station_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Station station;

    @JoinColumn(name = "owner_id", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserProfile owner;

    @Column(name = "plan", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private Plan plan;

    @Column(name = "fee_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal feeAmount;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private LicenseStatus status;

    @Column(name = "license_code",
            nullable = false,
            unique = true,
            updatable = false,
            length = 20)
    private String licenseCode;

    /**
     * Source of this renewal period. Null means the row was issued as a new
     * subscription. The database unique constraint makes a renew retry
     * idempotent at the persistence boundary: one source can have at most one
     * direct successor.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "renewed_from_license_id", updatable = false)
    private License renewedFrom;

    @Version
    private long version;

    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("Asia/Ho_Chi_Minh");

    private static Instant calculateExpiration(Instant startAt, Plan plan) {
        if (startAt == null) {
            throw new IllegalArgumentException("License start time cannot be null");
        }
        if (plan == null) {
            throw new IllegalStateException("License plan cannot be null");
        }

        return switch (plan) {
            case MONTHLY -> startAt
                    .atZone(BUSINESS_ZONE)
                    .plusMonths(1)
                    .toInstant();

            case YEARLY -> startAt
                    .atZone(BUSINESS_ZONE)
                    .plusYears(1)
                    .toInstant();
        };
    }

    public static License issue(
            Station station,
            Plan plan,
            Instant startAt,
            String licenseCode
    ) {
        if (station == null) {
            throw new IllegalArgumentException("License station cannot be null");
        }
        if (plan == null) {
            throw new IllegalArgumentException("License plan cannot be null");
        }
        if (startAt == null) {
            throw new IllegalArgumentException("License start time cannot be null");
        }
        if (licenseCode == null || licenseCode.isBlank()) {
            throw new IllegalArgumentException("License code cannot be blank");
        }
        if (licenseCode.length() > 20) {
            throw new IllegalArgumentException("License code cannot exceed 20 characters");
        }
        License license = new License();

        license.station = station;
        license.owner = station.getOwner();
        license.plan = plan;
        license.feeAmount = plan.feeAmount();
        license.startAt = startAt;
        license.status = LicenseStatus.PENDING;
        license.expiresAt =
                calculateExpiration(startAt, plan);
        license.licenseCode = licenseCode;

        return license;
    }

    /**
     * Creates a new License row for a renewal; the source row is never mutated.
     * Cross-row rules such as "source must be latest" and "no pending successor"
     * still belong to the lifecycle policy/application service.
     */
    public static License renewFrom(
            License source,
            Plan plan,
            Instant startAt,
            String licenseCode
    ) {
        if (source == null) {
            throw new IllegalArgumentException("Renewal source license cannot be null");
        }

        License renewal = issue(source.station, plan, startAt, licenseCode);
        renewal.renewedFrom = source;
        return renewal;
    }

    public void suspend(Instant at) {
        if (this.status != LicenseStatus.ACTIVE) {
            throw new LicenseDomainException(
                    LicenseViolation.INVALID_TRANSITION,
                    "Only active license can be suspended"
            );
        }
        if (!isEffectivelyActiveAt(at)) {
            throw new LicenseDomainException(
                    LicenseViolation.OUTSIDE_EFFECTIVE_WINDOW,
                    "License is outside its effective period"
            );
        }

        this.status = LicenseStatus.SUSPENDED;
    }

    private void requireStatus(LicenseStatus expectedStatus) {
        if (this.status != expectedStatus) {
            throw new LicenseDomainException(
                    LicenseViolation.INVALID_TRANSITION,
                    "License must be " + expectedStatus + " for this transition"
            );
        }
    }

    private void requireWithinEffectiveWindow(Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("Transition time cannot be null");
        }
        if (at.isBefore(startAt) || !at.isBefore(expiresAt)) {
            throw new LicenseDomainException(LicenseViolation.OUTSIDE_EFFECTIVE_WINDOW,
                    "License is outside its effective period"
            );
        }
    }

    public void activate(Instant at) {
        requireStatus(LicenseStatus.PENDING);
        requireWithinEffectiveWindow(at);
        status = LicenseStatus.ACTIVE;
    }

    public void reactivate(Instant at) {
        requireStatus(LicenseStatus.SUSPENDED);
        requireWithinEffectiveWindow(at);
        status = LicenseStatus.ACTIVE;
    }

    public void cancel() {
        if (status == LicenseStatus.CANCELLED
                || status == LicenseStatus.EXPIRED) {
            throw new LicenseDomainException(
                    LicenseViolation.TERMINAL_LICENSE,
                    "Terminal license cannot be cancelled"
            );
        }

        status = LicenseStatus.CANCELLED;
    }

    public void markExpired(Instant now) {
        if (status != LicenseStatus.ACTIVE
                && status != LicenseStatus.SUSPENDED) {
            throw new IllegalStateException(
                    "Only active or suspended license can be marked expired"
            );
        }

        if (now.isBefore(expiresAt)) {
            throw new IllegalStateException(
                    "License has not expired yet"
            );
        }

        status = LicenseStatus.EXPIRED;
    }

    /** SRS renewal-warning window. This is derived read-side state, not a status. */
    public static final int EXPIRING_SOON_DAYS = 30;

    public boolean isEffectivelyActiveAt(Instant now) {
        return status == LicenseStatus.ACTIVE
                && !now.isBefore(startAt)
                && now.isBefore(expiresAt);
    }

    public int calculateDaysLeft() {
        return calculateDaysLeft(Instant.now());
    }

    public int calculateDaysLeft(Instant now) {
        if (this.expiresAt == null) return 0;
        LocalDate today = now.atZone(BUSINESS_ZONE).toLocalDate();
        LocalDate expDate = this.expiresAt.atZone(BUSINESS_ZONE).toLocalDate();
        return (int) ChronoUnit.DAYS.between(today, expDate);
    }

    public boolean isExpiringSoon() {
        return isExpiringSoon(Instant.now());
    }

    public boolean isExpiringSoon(Instant now) {
        if (!isEffectivelyActiveAt(now)) {
            return false;
        }
        int days = calculateDaysLeft(now);
        return days >= 0 && days <= EXPIRING_SOON_DAYS;
    }

}
