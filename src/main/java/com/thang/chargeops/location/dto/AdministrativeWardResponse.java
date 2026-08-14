package com.thang.chargeops.location.dto;

import com.thang.chargeops.location.entity.AdministrativeWard;

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
                ward.getName(),
                ward.getFullName()
        );
    }
}

