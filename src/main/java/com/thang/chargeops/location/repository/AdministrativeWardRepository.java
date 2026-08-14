package com.thang.chargeops.location.repository;

import com.thang.chargeops.location.entity.AdministrativeWard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdministrativeWardRepository
        extends JpaRepository<AdministrativeWard, String> {

    List<AdministrativeWard> findAllByProvinceCodeOrderByCodeAsc(String provinceCode);
}

