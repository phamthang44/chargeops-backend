package com.thang.chargeops.location.dto;

import com.thang.chargeops.location.entity.AdministrativeProvince;
import com.thang.chargeops.location.i18n.LocationNames;

public record AdministrativeProvinceResponse(
        String code,
        String name,
        String fullName
) {
    public static AdministrativeProvinceResponse from(AdministrativeProvince province) {
        return new AdministrativeProvinceResponse(
                province.getCode(),
                LocationNames.provinceName(province),
                LocationNames.provinceFullName(province)
        );
    }
}

