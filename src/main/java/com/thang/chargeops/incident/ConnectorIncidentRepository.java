package com.thang.chargeops.incident;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface ConnectorIncidentRepository extends JpaRepository<ConnectorIncident, UUID> {
    boolean existsByConnectorIdAndStatus(UUID connectorId, ConnectorIncident.Status status);
    @Query("SELECT i.connector.id FROM ConnectorIncident i WHERE i.id = :id")
    Optional<UUID> findConnectorId(@Param("id") UUID id);
}
