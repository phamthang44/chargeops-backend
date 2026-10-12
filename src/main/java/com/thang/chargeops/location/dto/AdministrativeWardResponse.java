package com.thang.chargeops.location.dto;

import com.thang.chargeops.location.entity.AdministrativeWard;
import com.thang.chargeops.location.i18n.LocationNames;

public record AdministrativeWardResponse(
        String code,
        String provinceCode,
        String name,
        String fullName
) {
    public static AdministrativeWardResponse from(AdministrativeWard ward) {
        return new AdministrativeWardResponse(
                ward.getCode(),
                ward.getProvinceCode(),
                LocationNames.wardName(ward),
                LocationNames.wardFullName(ward)
        );
    }
}

