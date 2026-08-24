package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.StationBookingSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StationBookingSettingsRepository extends JpaRepository<StationBookingSettings, UUID> {

    Optional<StationBookingSettings> findByStationId(UUID stationId);
}
