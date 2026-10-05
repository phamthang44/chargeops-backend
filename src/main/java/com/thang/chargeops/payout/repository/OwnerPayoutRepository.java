package com.thang.chargeops.payout.repository;

import com.thang.chargeops.payout.entity.OwnerPayout;
import com.thang.chargeops.payout.model.PayoutStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OwnerPayoutRepository extends JpaRepository<OwnerPayout, UUID> {
    Optional<OwnerPayout> findByPayoutCode(String payoutCode);
    List<OwnerPayout> findByOwnerIdAndStatus(UUID ownerId, PayoutStatus status);
}
