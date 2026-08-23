package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.ChargePoint;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChargePointRepository extends JpaRepository<ChargePoint, UUID> {

    boolean existsByStationIdAndChargePointCode(UUID stationId, String chargePointCode);

    long countByStationId(UUID stationId);

    List<ChargePoint> findByStationIdOrderByChargePointCodeAsc(UUID stationId);

    Optional<ChargePoint> findByIdAndStationId(UUID id, UUID stationId);

    /**
     * Serializes topology-changing commands for one charge point. Activation
     * and connector provisioning both take this lock so inventory cannot change
     * while activation validates the confirmed connector count.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select cp from ChargePoint cp where cp.id = :id and cp.station.id = :stationId")
    Optional<ChargePoint> findByIdAndStationIdForUpdate(
            @Param("id") UUID id,
            @Param("stationId") UUID stationId
    );

    Optional<ChargePoint> findByStationIdAndChargePointCode(UUID stationId, String chargePointCode);

}
