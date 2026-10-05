package com.thang.chargeops.station.staff.repository;

import com.thang.chargeops.station.entity.ConnectorStatusEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface StaffConnectorHistoryRepository extends JpaRepository<ConnectorStatusEvent, UUID> {
    @EntityGraph(attributePaths = "performedBy")
    Page<ConnectorStatusEvent> findByConnector_Id(UUID connectorId, Pageable pageable);
}
