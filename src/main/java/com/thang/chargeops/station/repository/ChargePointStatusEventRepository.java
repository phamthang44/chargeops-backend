package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.ChargePointStatusEvent;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChargePointStatusEventRepository extends JpaRepository<ChargePointStatusEvent, UUID> {

    @EntityGraph(attributePaths = "performedBy")
    List<ChargePointStatusEvent> findAllByChargePointIdOrderByPerformedAtAscIdAsc(UUID chargePointId);
}
