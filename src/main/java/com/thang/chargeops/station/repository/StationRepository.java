package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.Station;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StationRepository extends JpaRepository<Station, UUID> {
}
