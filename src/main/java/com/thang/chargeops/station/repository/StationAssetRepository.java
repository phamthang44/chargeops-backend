package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.StationAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StationAssetRepository extends JpaRepository<StationAsset, UUID> {
}
