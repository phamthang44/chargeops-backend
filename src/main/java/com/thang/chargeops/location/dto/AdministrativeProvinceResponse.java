package com.thang.chargeops.location.dto;

import com.thang.chargeops.location.entity.AdministrativeProvince;

public record AdministrativeProvinceResponse(
        String code,
        String name,
        String fullName
) {
    public static AdministrativeProvinceResponse from(AdministrativeProvince province) {
        return new AdministrativeProvinceResponse(
                province.getCode(),
                province.getName(),
                province.getFullName()
        );
    }
}

