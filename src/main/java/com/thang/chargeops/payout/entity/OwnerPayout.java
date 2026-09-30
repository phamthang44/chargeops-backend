package com.thang.chargeops.payout.entity;

import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.payout.model.PayoutAttemptStatus;
import com.thang.chargeops.payout.model.PayoutMoney;
import com.thang.chargeops.payout.model.PayoutStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "owner_payouts", indexes = {
        @Index(name = "idx_owner_payouts_owner_status_environment", columnList = "owner_id, status, environment, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnerPayout extends AuditableEntity {
    private static final Pattern CODE = Pattern.compile("PO-[0-9]{8}-[0-9]{4,}");

    @Column(name = "payout_code", nullable = false, unique = true, length = 32, updatable = false)
    private String payoutCode;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private UserProfile owner;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private PaymentEnvironment environment;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutStatus status;
    @Column(name = "period_start", nullable = false, updatable = false)
    private Instant periodStart;
    @Column(name = "period_end", nullable = false, updatable = false)
    private Instant periodEnd;
    @Column(name = "successful_attempt_id")
    private UUID successfulAttemptId;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Version
    @Column(nullable = false)
    private Long version = 0L;

    public static OwnerPayout pending(String code, UserProfile owner, BigDecimal amount,
                                      PaymentEnvironment environment, Instant periodStart, Instant periodEnd) {
        if (code == null || !CODE.matcher(code).matches() || owner == null || periodStart == null
                || periodEnd == null || !periodEnd.isAfter(periodStart) || environment == null
                || environment == PaymentEnvironment.LEGACY) {
            throw new IllegalArgumentException("Complete, classified payout data is required");
        }
        OwnerPayout payout = new OwnerPayout();
        payout.payoutCode = code;
        payout.owner = owner;
        payout.amount = PayoutMoney.positiveVnd(amount);
        payout.currency = "VND";
        payout.environment = environment;
        payout.status = PayoutStatus.PENDING;
        payout.periodStart = periodStart;
        payout.periodEnd = periodEnd;
        return payout;
    }

    public void completeWith(PayoutAttempt attempt, Instant completedAt) {
        if (status != PayoutStatus.PENDING || attempt == null || attempt.getPayout() != this
                || attempt.getStatus() != PayoutAttemptStatus.SUCCEEDED || attempt.getId() == null
                || completedAt == null || !completedAt.equals(attempt.getCompletedAt())) {
            throw new IllegalStateException("Only the successful attempt can complete a pending payout");
        }
        status = PayoutStatus.SUCCEEDED;
        successfulAttemptId = attempt.getId();
        this.completedAt = completedAt;
    }

    public void cancelPending() {
        if (status != PayoutStatus.PENDING) {
            throw new IllegalStateException("Only a pending payout can be cancelled");
        }
        status = PayoutStatus.CANCELLED;
    }
}
