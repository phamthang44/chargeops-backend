package com.thang.chargeops.station.staff.repository;

import com.thang.chargeops.station.entity.ChargePointStatusEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface StaffChargePointHistoryRepository extends JpaRepository<ChargePointStatusEvent, UUID> {
    @EntityGraph(attributePaths = "performedBy")
    Page<ChargePointStatusEvent> findByChargePoint_Id(UUID chargePointId, Pageable pageable);
}
