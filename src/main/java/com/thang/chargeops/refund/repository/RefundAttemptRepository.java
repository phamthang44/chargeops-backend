package com.thang.chargeops.refund.repository;

import com.thang.chargeops.refund.entity.RefundAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundAttemptRepository extends JpaRepository<RefundAttempt, UUID> {

    Optional<RefundAttempt> findByRefundIdAndRequestKey(UUID refundId, UUID requestKey);

    List<RefundAttempt> findByRefundIdOrderBySequenceNoAsc(UUID refundId);

    long countByRefundId(UUID refundId);
}
