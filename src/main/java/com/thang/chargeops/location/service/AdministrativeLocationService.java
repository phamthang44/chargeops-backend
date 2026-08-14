package com.thang.chargeops.location.service;

import com.thang.chargeops.location.dto.AdministrativeProvinceResponse;
import com.thang.chargeops.location.dto.AdministrativeWardResponse;
import com.thang.chargeops.location.entity.AdministrativeWard;

import java.util.List;

public interface AdministrativeLocationService {

    List<AdministrativeProvinceResponse> getProvinces();

    List<AdministrativeWardResponse> getWards(String provinceCode);

    AdministrativeWard requireWard(String wardCode);

    void requireWardBelongsToProvince(AdministrativeWard ward, String provinceCode);
}

