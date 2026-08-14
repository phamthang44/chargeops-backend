package com.thang.chargeops.location.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.location.dto.AdministrativeProvinceResponse;
import com.thang.chargeops.location.dto.AdministrativeWardResponse;
import com.thang.chargeops.location.service.AdministrativeLocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping(SystemConstant.API_URL_PATTERN + "administrative-units")
public class AdministrativeLocationController {

    private final AdministrativeLocationService administrativeLocationService;

    @GetMapping("/provinces")
    public ResponseEntity<ApiResult<List<AdministrativeProvinceResponse>>> getProvinces() {
        return ResponseEntity.ok(ApiResult.success(
                administrativeLocationService.getProvinces()
        ));
    }

    @GetMapping("/provinces/{provinceCode}/wards")
    public ResponseEntity<ApiResult<List<AdministrativeWardResponse>>> getWards(
            @PathVariable String provinceCode
    ) {
        return ResponseEntity.ok(ApiResult.success(
                administrativeLocationService.getWards(provinceCode)
        ));
    }
}
