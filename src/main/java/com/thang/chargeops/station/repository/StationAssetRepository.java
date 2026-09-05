package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.StationAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StationAssetRepository extends JpaRepository<StationAsset, UUID> {
    List<StationAsset> findByStationIdOrderByDisplayOrderAsc(UUID stationId);

    Optional<StationAsset> findByIdAndStationId(UUID id, UUID stationId);

    List<StationAsset> findByStationIdAndPrimaryAssetTrue(UUID stationId);

    int countByStationId(UUID stationId);
}
