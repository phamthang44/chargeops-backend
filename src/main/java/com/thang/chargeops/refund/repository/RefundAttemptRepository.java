package com.thang.chargeops.refund.repository;

import com.thang.chargeops.refund.entity.RefundAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundAttemptRepository extends JpaRepository<RefundAttempt, UUID> {

    @Query("""
        SELECT COUNT(a) FROM RefundAttempt a
        WHERE a.refund.booking.connector.chargePoint.station.owner.id = :ownerId
          AND a.refund.payment.environment = com.thang.chargeops.common.enums.PaymentEnvironment.SIMULATOR
          AND a.status = com.thang.chargeops.refund.model.RefundAttemptStatus.FAILED
    """)
    long countOwnerFailedSimulatorAttempts(@Param("ownerId") UUID ownerId);

    Optional<RefundAttempt> findByRefundIdAndRequestKey(UUID refundId, UUID requestKey);

    List<RefundAttempt> findByRefundIdOrderBySequenceNoAsc(UUID refundId);

    long countByRefundId(UUID refundId);
}
