package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.StationOperationalStatusEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StationOperationalStatusEventRepository
        extends JpaRepository<StationOperationalStatusEvent, UUID> {
}
