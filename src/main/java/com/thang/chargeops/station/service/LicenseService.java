package com.thang.chargeops.station.service;

import com.thang.chargeops.station.dto.license.request.IssueLicenseRequest;
import com.thang.chargeops.station.dto.license.request.RenewLicenseRequest;
import com.thang.chargeops.station.dto.license.response.AdminLicenseDetailResponse;
import com.thang.chargeops.station.dto.license.response.AdminLicenseListItemResponse;
import com.thang.chargeops.station.dto.license.response.IssueLicenseResponse;
import com.thang.chargeops.station.dto.license.filter.LicenseFilter;
import com.thang.chargeops.station.dto.license.response.RenewLicenseResponse;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

public interface LicenseService {
    IssueLicenseResponse issueLicense(UUID stationId, IssueLicenseRequest request);

    void suspendLicense(UUID licenseId, String reason);
    void cancelLicense(UUID licenseId, String reason);
    void reactivateLicense(UUID licenseId, String reason);

    RenewLicenseResponse renewLicense(UUID sourceLicenseId, RenewLicenseRequest request);

    Page<AdminLicenseListItemResponse> searchLicenses(int pageNo, int pageSize, LicenseFilter filter);

    AdminLicenseDetailResponse getLicenseDetail(UUID licenseId);

    List<AdminLicenseListItemResponse> getStationLicenseHistory(UUID stationId);

}
