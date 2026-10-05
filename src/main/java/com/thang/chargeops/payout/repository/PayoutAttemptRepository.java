package com.thang.chargeops.payout.repository;

import com.thang.chargeops.payout.entity.PayoutAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayoutAttemptRepository extends JpaRepository<PayoutAttempt, UUID> {
    Optional<PayoutAttempt> findByPayoutIdAndRequestKey(UUID payoutId, UUID requestKey);
    List<PayoutAttempt> findByPayoutIdOrderBySequenceNoAsc(UUID payoutId);
}
