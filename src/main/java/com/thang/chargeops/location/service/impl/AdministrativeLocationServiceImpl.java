package com.thang.chargeops.location.service.impl;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.AdministrativeLocationErrorCode;
import com.thang.chargeops.location.dto.AdministrativeProvinceResponse;
import com.thang.chargeops.location.dto.AdministrativeWardResponse;
import com.thang.chargeops.location.entity.AdministrativeWard;
import com.thang.chargeops.location.repository.AdministrativeProvinceRepository;
import com.thang.chargeops.location.repository.AdministrativeWardRepository;
import com.thang.chargeops.location.service.AdministrativeLocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdministrativeLocationServiceImpl implements AdministrativeLocationService {

    private final AdministrativeProvinceRepository provinceRepository;
    private final AdministrativeWardRepository wardRepository;

    @Override
    public List<AdministrativeProvinceResponse> getProvinces() {
        return provinceRepository.findAllByOrderByCodeAsc().stream()
                .map(AdministrativeProvinceResponse::from)
                .toList();
    }

    @Override
    public List<AdministrativeWardResponse> getWards(String provinceCode) {
        requireProvince(provinceCode);
        return wardRepository.findAllByProvinceCodeOrderByCodeAsc(provinceCode).stream()
                .map(AdministrativeWardResponse::from)
                .toList();
    }

    @Override
    public AdministrativeWard requireWard(String wardCode) {
        return wardRepository.findById(wardCode)
                .orElseThrow(() -> new AppException(
                        AdministrativeLocationErrorCode.WARD_NOT_FOUND,
                        wardCode
                ));
    }

    @Override
    public void requireWardBelongsToProvince(AdministrativeWard ward, String provinceCode) {
        requireProvince(provinceCode);
        if (!Objects.equals(ward.getProvinceCode(), provinceCode)) {
            throw new AppException(
                    AdministrativeLocationErrorCode.WARD_PROVINCE_MISMATCH,
                    ward.getCode(),
                    provinceCode
            );
        }
    }

    private void requireProvince(String provinceCode) {
        if (!provinceRepository.existsById(provinceCode)) {
            throw new AppException(
                    AdministrativeLocationErrorCode.PROVINCE_NOT_FOUND,
                    provinceCode
            );
        }
    }
}

