package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.Connector;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConnectorRepository extends JpaRepository<Connector, UUID> {

    interface OwnerOperationsTotals {
        long getTotalConnectors();
        long getAvailableConnectors();
        long getChargingConnectors();
        long getOfflineConnectors();
        long getUnavailableConnectors();
    }

    @Query("""
            SELECT COUNT(c) AS totalConnectors,
                   COALESCE(SUM(CASE WHEN c.runtimeStatus = com.thang.chargeops.common.enums.RuntimeStatus.AVAILABLE
                       AND cp.operationalChargePointStatus = com.thang.chargeops.common.enums.OperationalChargePointStatus.AVAILABLE
                       THEN 1 ELSE 0 END), 0) AS availableConnectors,
                   COALESCE(SUM(CASE WHEN c.runtimeStatus = com.thang.chargeops.common.enums.RuntimeStatus.IN_USE
                       THEN 1 ELSE 0 END), 0) AS chargingConnectors,
                   COALESCE(SUM(CASE WHEN c.runtimeStatus = com.thang.chargeops.common.enums.RuntimeStatus.OFFLINE
                       THEN 1 ELSE 0 END), 0) AS offlineConnectors,
                   COALESCE(SUM(CASE WHEN c.runtimeStatus = com.thang.chargeops.common.enums.RuntimeStatus.AVAILABLE
                       AND cp.operationalChargePointStatus <> com.thang.chargeops.common.enums.OperationalChargePointStatus.AVAILABLE
                       THEN 1 ELSE 0 END), 0) AS unavailableConnectors
            FROM Connector c JOIN c.chargePoint cp JOIN cp.station s
            WHERE s.owner.id = :ownerId AND s.deletedAt IS NULL AND cp.deletedAt IS NULL AND c.deletedAt IS NULL
                AND cp.provisioningStatus = com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
            """)
    OwnerOperationsTotals summarizeOwnerOperations(@Param("ownerId") UUID ownerId);

    boolean existsByChargePointIdAndConnectorCode(UUID chargePointId, String connectorCode);

    long countByChargePointId(UUID chargePointId);

    List<Connector> findByChargePointIdOrderByConnectorCodeAsc(UUID chargePointId);

    Optional<Connector> findByIdAndChargePointId(UUID id, UUID chargePointId);

    Optional<Connector> findByChargePointIdAndConnectorCode(UUID chargePointId, String connectorCode);

    @Override
    @EntityGraph(attributePaths = {
            "chargePoint",
            "chargePoint.station"
    })
    Optional<Connector> findById(UUID id);

    @Query("SELECT c FROM Connector c JOIN FETCH c.chargePoint cp JOIN FETCH cp.station WHERE c.id = :id")
    Optional<Connector> findByIdWithChargePointAndStation(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Connector c WHERE c.id = :id")
    Optional<Connector> findByIdWithLock(@Param("id") UUID id);

}
