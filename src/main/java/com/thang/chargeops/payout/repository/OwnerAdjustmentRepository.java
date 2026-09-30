package com.thang.chargeops.payout.repository;

import com.thang.chargeops.payout.entity.OwnerAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OwnerAdjustmentRepository extends JpaRepository<OwnerAdjustment, UUID> {
    List<OwnerAdjustment> findByOwnerIdOrderByRecordedAtDesc(UUID ownerId);
    boolean existsByRefundIdAndSourcePayoutItemId(UUID refundId, UUID sourcePayoutItemId);
}
