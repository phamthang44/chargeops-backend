package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.StationOperatingPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StationOperatingPeriodRepository extends JpaRepository<StationOperatingPeriod, UUID> {
}
