package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.License;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LicenseRepository extends JpaRepository<License, UUID> {
}
