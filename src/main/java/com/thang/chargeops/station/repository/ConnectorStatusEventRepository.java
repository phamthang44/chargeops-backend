package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.ConnectorStatusEvent;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConnectorStatusEventRepository extends JpaRepository<ConnectorStatusEvent, UUID> {

    @EntityGraph(attributePaths = "performedBy")
    List<ConnectorStatusEvent> findAllByConnectorIdOrderByPerformedAtAscIdAsc(UUID connectorId);
}
