package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.LicenseErrorMessage;
import com.thang.chargeops.common.enums.LicenseStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseErrorCodeTest {

    @Test
    void exposesStableCodesStatusesAndRegisteredMessages() {
        assertThat(LicenseErrorCode.LICENSE_NOT_FOUND.getCode())
                .isEqualTo("LICENSE_001");
        assertThat(LicenseErrorCode.LICENSE_NOT_FOUND.getHttpStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(LicenseErrorCode.ACTIVE_LICENSE_ALREADY_EXISTS.getCode())
                .isEqualTo("LICENSE_002");
        assertThat(LicenseErrorCode.ACTIVE_LICENSE_ALREADY_EXISTS.getHttpStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(LicenseErrorCode.LICENSE_WAS_MODIFIED.getCode())
                .isEqualTo("LICENSE_003");
        assertThat(LicenseErrorCode.INVALID_STATUS_TRANSITION.getCode())
                .isEqualTo("LICENSE_004");
        assertThat(LicenseErrorCode.LICENSE_OUTSIDE_EFFECTIVE_PERIOD.getCode())
                .isEqualTo("LICENSE_005");
        assertThat(LicenseErrorCode.LICENSE_NOT_EXPIRED.getCode())
                .isEqualTo("LICENSE_006");
        assertThat(LicenseErrorCode.LICENSE_NOT_RENEWABLE.getCode())
                .isEqualTo("LICENSE_007");
        assertThat(LicenseErrorCode.LICENSE_NOT_LATEST_PERIOD.getCode())
                .isEqualTo("LICENSE_008");
        assertThat(LicenseErrorCode.LICENSE_ALREADY_RENEWED.getCode())
                .isEqualTo("LICENSE_009");
        assertThat(List.of(
                LicenseErrorCode.LICENSE_NOT_RENEWABLE,
                LicenseErrorCode.LICENSE_NOT_LATEST_PERIOD,
                LicenseErrorCode.LICENSE_ALREADY_RENEWED
        )).allSatisfy(errorCode ->
                assertThat(errorCode.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT)
        );

        for (LicenseErrorCode errorCode : LicenseErrorCode.values()) {
            assertThat(ErrorMessage.defaultMessage(errorCode.getMessageKey()))
                    .isEqualTo(errorCode.getMessage());
        }
    }

    @Test
    void formatsLicenseContext() {
        UUID licenseId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();

        assertThat(LicenseErrorCode.LICENSE_NOT_FOUND.format(licenseId))
                .isEqualTo("License not found: " + licenseId);
        assertThat(LicenseErrorCode.ACTIVE_LICENSE_ALREADY_EXISTS.format(stationId))
                .isEqualTo("An active license already exists for station " + stationId);
        assertThat(LicenseErrorCode.INVALID_STATUS_TRANSITION.format(
                licenseId,
                LicenseStatus.ACTIVE,
                LicenseStatus.PENDING
        )).isEqualTo("License " + licenseId + " cannot transition from ACTIVE to PENDING");
        assertThat(LicenseErrorCode.LICENSE_OUTSIDE_EFFECTIVE_PERIOD.format(licenseId))
                .isEqualTo("License " + licenseId + " cannot transition outside its effective period");
        assertThat(LicenseErrorCode.LICENSE_NOT_EXPIRED.format(licenseId, "2026-09-17T00:00:00Z"))
                .isEqualTo("License " + licenseId + " cannot be marked expired before 2026-09-17T00:00:00Z");
        assertThat(LicenseErrorCode.LICENSE_NOT_RENEWABLE.format(
                licenseId,
                LicenseStatus.SUSPENDED
        )).isEqualTo("License " + licenseId + " with status SUSPENDED is not eligible for renewal");
        assertThat(LicenseErrorCode.LICENSE_NOT_LATEST_PERIOD.format(licenseId, stationId))
                .isEqualTo("License " + licenseId + " is not the latest license period for station " + stationId);
        assertThat(LicenseErrorCode.LICENSE_ALREADY_RENEWED.format(licenseId))
                .isEqualTo("License " + licenseId + " already has a renewal successor");
    }

    @Test
    void registersLicenseValidationMessages() {
        assertThat(ErrorMessage.defaultMessage(LicenseErrorMessage.PLAN_REQUIRED_KEY))
                .isEqualTo("License plan is required");
    }
}
