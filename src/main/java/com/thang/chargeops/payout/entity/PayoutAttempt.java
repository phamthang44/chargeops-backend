package com.thang.chargeops.payout.entity;

import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.payout.model.PayoutAttemptStatus;
import com.thang.chargeops.payout.model.PayoutExecutionMode;
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
@Table(name = "payout_attempts", uniqueConstraints = {
        @UniqueConstraint(name = "ux_payout_attempts_sequence", columnNames = {"payout_id", "sequence_no"}),
        @UniqueConstraint(name = "ux_payout_attempts_request", columnNames = {"payout_id", "request_key"}),
        @UniqueConstraint(name = "ux_payout_attempts_id_payout", columnNames = {"id", "payout_id"})
}, indexes = {
        @Index(name = "idx_payout_attempts_payout_sequence", columnList = "payout_id, sequence_no")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutAttempt extends AuditableEntity {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payout_id", nullable = false, updatable = false)
    private OwnerPayout payout;
    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;
    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", nullable = false, length = 30, updatable = false)
    private PayoutExecutionMode executionMode;
    @Column(name = "request_key", nullable = false, updatable = false)
    private UUID requestKey;
    @Column(name = "payload_hash", nullable = false, length = 64, updatable = false)
    private String payloadHash;
    @Column(name = "idempotency_key", nullable = false, length = 255, updatable = false)
    private String idempotencyKey;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private PaymentEnvironment environment;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutAttemptStatus status;
    @Column(name = "transfer_reference", length = 255)
    private String transferReference;
    @Column(name = "failure_code", length = 100)
    private String failureCode;
    @Column(length = 2000)
    private String note;
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;
    @Column(name = "performed_at")
    private Instant performedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performed_by", nullable = false, updatable = false)
    private UserProfile performedBy;

    public static PayoutAttempt start(OwnerPayout payout, int sequenceNo, PayoutExecutionMode mode,
                                      UUID requestKey, String payloadHash, String idempotencyKey,
                                      UserProfile actor, Instant startedAt) {
        if (payout == null || payout.getStatus() != PayoutStatus.PENDING || sequenceNo <= 0 || mode == null
                || requestKey == null || payloadHash == null || !SHA_256.matcher(payloadHash).matches()
                || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255
                || actor == null || startedAt == null) {
            throw new IllegalArgumentException("Complete payout attempt data is required");
        }
        PayoutAttempt attempt = new PayoutAttempt();
        attempt.payout = payout;
        attempt.sequenceNo = sequenceNo;
        attempt.executionMode = mode;
        attempt.requestKey = requestKey;
        attempt.payloadHash = payloadHash;
        attempt.idempotencyKey = idempotencyKey;
        attempt.amount = payout.getAmount();
        attempt.currency = payout.getCurrency();
        attempt.environment = payout.getEnvironment();
        attempt.status = PayoutAttemptStatus.STARTED;
        attempt.startedAt = startedAt;
        attempt.performedBy = actor;
        return attempt;
    }

    public void succeed(String reference, String note, Instant performedAt, Instant completedAt) {
        ensureStarted(performedAt, completedAt);
        if (reference == null || reference.isBlank() || reference.length() > 255) {
            throw new IllegalArgumentException("Successful payout needs a transfer reference");
        }
        status = PayoutAttemptStatus.SUCCEEDED;
        transferReference = reference.trim();
        this.note = checkedNote(note);
        this.performedAt = performedAt;
        this.completedAt = completedAt;
    }

    public void fail(String code, String note, Instant performedAt, Instant completedAt) {
        ensureStarted(performedAt, completedAt);
        if (code == null || code.isBlank() || code.length() > 100) {
            throw new IllegalArgumentException("Failed payout needs a failure code");
        }
        status = PayoutAttemptStatus.FAILED;
        failureCode = code.trim();
        this.note = checkedNote(note);
        this.performedAt = performedAt;
        this.completedAt = completedAt;
    }

    private void ensureStarted(Instant performedAt, Instant completedAt) {
        if (status != PayoutAttemptStatus.STARTED || performedAt == null || completedAt == null
                || performedAt.isAfter(completedAt) || completedAt.isBefore(startedAt)) {
            throw new IllegalStateException("Only a started payout attempt can receive an outcome");
        }
    }

    private static String checkedNote(String note) {
        if (note != null && note.length() > 2000) {
            throw new IllegalArgumentException("Payout note is too long");
        }
        return note;
    }
}
