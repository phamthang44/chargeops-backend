package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.Connector;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ConnectorRepository extends JpaRepository<Connector, UUID> {
}
