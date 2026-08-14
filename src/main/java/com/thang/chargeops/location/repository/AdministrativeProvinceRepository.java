package com.thang.chargeops.location.repository;

import com.thang.chargeops.location.entity.AdministrativeProvince;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdministrativeProvinceRepository
        extends JpaRepository<AdministrativeProvince, String> {

    List<AdministrativeProvince> findAllByOrderByCodeAsc();
}

