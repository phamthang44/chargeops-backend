package com.thang.chargeops.payout.entity;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.payout.model.OwnerAdjustmentReason;
import com.thang.chargeops.payout.model.PayoutItemStatus;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.profile.entity.UserProfile;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable signed recovery fact. Settlement commands are outside BKG-059. */
@Entity
@Table(name = "owner_adjustments", indexes = {
        @Index(name = "idx_owner_adjustments_owner_environment", columnList = "owner_id, environment, recorded_at"),
        @Index(name = "idx_owner_adjustments_booking", columnList = "booking_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnerAdjustment extends AuditableEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private UserProfile owner;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", updatable = false)
    private Booking booking;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_id", updatable = false)
    private Refund refund;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_payout_item_id", updatable = false)
    private OwnerPayoutItem sourcePayoutItem;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private PaymentEnvironment environment;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private OwnerAdjustmentReason reason;
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recorded_by", nullable = false, updatable = false)
    private UserProfile recordedBy;

    public static OwnerAdjustment recovery(Refund refund, OwnerPayoutItem paidItem,
                                           UserProfile actor, Instant recordedAt) {
        if (refund == null || paidItem == null || actor == null || recordedAt == null
                || paidItem.getStatus() != PayoutItemStatus.SUCCEEDED
                || refund.getBooking() != paidItem.getBooking()) {
            throw new IllegalArgumentException("Recovery needs a refund and its paid payout item");
        }
        OwnerAdjustment adjustment = new OwnerAdjustment();
        adjustment.owner = paidItem.getPayout().getOwner();
        adjustment.booking = paidItem.getBooking();
        adjustment.refund = refund;
        adjustment.sourcePayoutItem = paidItem;
        adjustment.amount = paidItem.getAmount().negate();
        adjustment.currency = paidItem.getCurrency();
        adjustment.environment = paidItem.getEnvironment();
        adjustment.reason = OwnerAdjustmentReason.REFUND_AFTER_PAYOUT;
        adjustment.recordedAt = recordedAt;
        adjustment.recordedBy = actor;
        return adjustment;
    }
}
