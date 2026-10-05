package com.thang.chargeops.refund.entity;

import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.refund.model.RefundAutoDispatchStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "refund_auto_dispatches", uniqueConstraints = {
        @UniqueConstraint(name = "ux_refund_auto_dispatches_refund", columnNames = "refund_id")
}, indexes = {
        @Index(name = "idx_refund_auto_dispatches_status_created", columnList = "status, created_at, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundAutoDispatch extends AuditableEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refund_id", nullable = false, updatable = false)
    private Refund refund;

    @Column(name = "request_key", nullable = false, updatable = false)
    private UUID requestKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundAutoDispatchStatus status;

    @Column(name = "processed_at")
    private Instant processedAt;

    public static RefundAutoDispatch pending(Refund refund, UUID requestKey) {
        RefundAutoDispatch dispatch = new RefundAutoDispatch();
        dispatch.refund = Objects.requireNonNull(refund, "refund must not be null");
        dispatch.requestKey = Objects.requireNonNull(requestKey, "requestKey must not be null");
        dispatch.status = RefundAutoDispatchStatus.PENDING;
        return dispatch;
    }

    public void markProcessed(Instant at) {
        if (status == RefundAutoDispatchStatus.PENDING) {
            status = RefundAutoDispatchStatus.PROCESSED;
            processedAt = Objects.requireNonNull(at, "processedAt must not be null");
        }
    }
}
