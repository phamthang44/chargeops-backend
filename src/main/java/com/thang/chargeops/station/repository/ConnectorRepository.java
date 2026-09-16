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
