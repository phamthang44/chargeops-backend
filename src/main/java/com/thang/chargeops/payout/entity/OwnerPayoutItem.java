package com.thang.chargeops.payout.entity;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payout.model.PayoutItemStatus;
import com.thang.chargeops.payout.model.PayoutMoney;
import com.thang.chargeops.payout.model.PayoutStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "owner_payout_items", uniqueConstraints = {
        @UniqueConstraint(name = "ux_owner_payout_items_payout_booking", columnNames = {"payout_id", "booking_id"})
}, indexes = {
        @Index(name = "idx_owner_payout_items_payout", columnList = "payout_id, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnerPayoutItem extends AuditableEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payout_id", nullable = false, updatable = false)
    private OwnerPayout payout;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, updatable = false)
    private Booking booking;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private PaymentEnvironment environment;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutItemStatus status;

    public static OwnerPayoutItem reserve(OwnerPayout payout, Booking booking, Payment payment, BigDecimal amount) {
        if (payout == null || payout.getStatus() != PayoutStatus.PENDING || booking == null || payment == null
                || payment.getBooking() != booking || payment.getEnvironment() != payout.getEnvironment()
                || !"VND".equals(payment.getCurrency())) {
            throw new IllegalArgumentException("Payout item must use the matching booking and payment");
        }
        OwnerPayoutItem item = new OwnerPayoutItem();
        item.payout = payout;
        item.booking = booking;
        item.payment = payment;
        item.amount = PayoutMoney.positiveVnd(amount);
        item.currency = "VND";
        item.environment = payout.getEnvironment();
        item.status = PayoutItemStatus.PENDING;
        return item;
    }

    public void markSucceeded() {
        if (status != PayoutItemStatus.PENDING || payout.getStatus() != PayoutStatus.SUCCEEDED) {
            throw new IllegalStateException("Only an item of a successful payout can succeed");
        }
        status = PayoutItemStatus.SUCCEEDED;
    }

    public void release() {
        if (status != PayoutItemStatus.PENDING || payout.getStatus() != PayoutStatus.CANCELLED) {
            throw new IllegalStateException("Only an item of a cancelled payout can be released");
        }
        status = PayoutItemStatus.RELEASED;
    }
}
