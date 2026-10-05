package com.thang.chargeops.payout.repository;

import com.thang.chargeops.payout.entity.OwnerPayoutItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OwnerPayoutItemRepository extends JpaRepository<OwnerPayoutItem, UUID> {
    List<OwnerPayoutItem> findByPayoutIdOrderByIdAsc(UUID payoutId);
}
