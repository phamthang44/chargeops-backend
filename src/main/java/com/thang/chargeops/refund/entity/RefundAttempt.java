package com.thang.chargeops.refund.entity;

import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.refund.model.PendingRefundAttemptSpec;
import com.thang.chargeops.refund.model.RefundAttemptStatus;
import com.thang.chargeops.refund.model.RefundExecutionMode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.regex.Pattern;

/** One auditable execution of a Refund obligation. */
@Entity
@Table(name = "refund_attempts", uniqueConstraints = {
        @UniqueConstraint(name = "ux_refund_attempts_request", columnNames = {"refund_id", "request_key"}),
        @UniqueConstraint(name = "ux_refund_attempts_sequence", columnNames = {"refund_id", "sequence_no"}),
        @UniqueConstraint(name = "ux_refund_attempts_id_refund", columnNames = {"id", "refund_id"})
}, indexes = {
        @Index(name = "idx_refund_attempts_refund_started", columnList = "refund_id, started_at, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundAttempt extends AuditableEntity {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refund_id", nullable = false, updatable = false)
    private Refund refund;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", nullable = false, length = 30, updatable = false)
    private RefundExecutionMode executionMode;

    @Column(name = "request_key", nullable = false, updatable = false)
    private java.util.UUID requestKey;

    @Column(name = "payload_hash", nullable = false, length = 64, updatable = false)
    private String payloadHash;

    @Column(name = "idempotency_key", nullable = false, length = 255, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundAttemptStatus status;

    @Column(name = "provider_refund_id", length = 255)
    private String providerRefundId;

    @Column(name = "transfer_reference", length = 255)
    private String transferReference;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performed_by", nullable = false, updatable = false)
    private UserProfile performedBy;

    public static RefundAttempt start(PendingRefundAttemptSpec spec) {
        if (spec == null || spec.refund() == null || spec.executionMode() == null
                || spec.requestKey() == null || spec.performedBy() == null || spec.startedAt() == null
                || spec.sequenceNo() <= 0 || spec.payloadHash() == null
                || !SHA_256.matcher(spec.payloadHash()).matches()) {
            throw conflict("Complete refund attempt data and a lowercase SHA-256 payload hash are required");
        }

        RefundAttempt attempt = new RefundAttempt();
        attempt.refund = spec.refund();
        attempt.sequenceNo = spec.sequenceNo();
        attempt.executionMode = spec.executionMode();
        attempt.requestKey = spec.requestKey();
        attempt.payloadHash = spec.payloadHash();
        attempt.idempotencyKey = requiredText(spec.idempotencyKey(), 255, "Idempotency key");
        attempt.status = RefundAttemptStatus.STARTED;
        attempt.startedAt = spec.startedAt();
        attempt.performedBy = spec.performedBy();
        return attempt;
    }

    public void completeSucceeded(String providerRefundId, String transferReference, Instant completedAt) {
        ensureStarted(completedAt);
        this.providerRefundId = optionalText(providerRefundId, 255, "Provider refund ID");
        this.transferReference = requiredText(transferReference, 255, "Transfer reference");
        this.status = RefundAttemptStatus.SUCCEEDED;
        this.completedAt = completedAt;
    }

    public void completeFailed(String failureCode, Instant completedAt) {
        ensureStarted(completedAt);
        this.failureCode = requiredText(failureCode, 100, "Failure code");
        this.status = RefundAttemptStatus.FAILED;
        this.completedAt = completedAt;
    }

    public boolean isSucceeded() {
        return status == RefundAttemptStatus.SUCCEEDED;
    }

    private void ensureStarted(Instant terminalAt) {
        if (status != RefundAttemptStatus.STARTED || terminalAt == null || terminalAt.isBefore(startedAt)) {
            throw conflict("Only a started attempt can receive a terminal outcome");
        }
    }

    private static String requiredText(String value, int maxLength, String label) {
        String normalized = optionalText(value, maxLength, label);
        if (normalized == null) {
            throw conflict(label + " is required");
        }
        return normalized;
    }

    private static String optionalText(String value, int maxLength, String label) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw conflict(label + " must be non-blank and fit " + maxLength + " characters");
        }
        return normalized;
    }

    private static AppException conflict(String message) {
        return new AppException(RefundErrorCode.EXECUTION_CONFLICT, message);
    }
}
