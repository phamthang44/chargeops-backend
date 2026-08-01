package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.ChargePoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChargePointRepository extends JpaRepository<ChargePoint, UUID> {
}
