package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.exception.errormessage.ApprovalErrorMessage;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalErrorCodeTest {

    @Test
    void exposesStableCodesStatusesAndRegisteredMessages() {
        assertThat(ApprovalErrorCode.STATION_NOT_PENDING_APPROVAL.getCode())
                .isEqualTo("APPROVAL_001");
        assertThat(ApprovalErrorCode.STATION_NOT_PENDING_APPROVAL.getHttpStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(ApprovalErrorCode.REJECTION_REASON_REQUIRED.getHttpStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ApprovalErrorCode.ACTIVE_LICENSE_REQUIRED.getHttpStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        for (ApprovalErrorCode errorCode : ApprovalErrorCode.values()) {
            assertThat(ErrorMessage.defaultMessage(errorCode.getMessageKey()))
                    .isEqualTo(errorCode.getMessage());
        }
    }

    @Test
    void formatsApprovalContext() {
        UUID stationId = UUID.randomUUID();

        assertThat(ApprovalErrorCode.STATION_NOT_PENDING_APPROVAL.format(
                stationId,
                StationStatus.ACTIVE
        )).isEqualTo(
                "Station %s is not pending approval; current status is ACTIVE".formatted(stationId)
        );
        assertThat(ApprovalErrorCode.ACTIVE_LICENSE_REQUIRED.format(stationId))
                .isEqualTo("Station %s requires an active license before approval".formatted(stationId));
    }

    @Test
    void registersRejectionReasonValidationMessages() {
        assertThat(ErrorMessage.defaultMessage(
                ApprovalErrorMessage.REJECTION_REASON_VALIDATION_REQUIRED_KEY
        )).isEqualTo("A rejection reason is required");
        assertThat(ErrorMessage.defaultMessage(
                ApprovalErrorMessage.REJECTION_REASON_MAX_LENGTH_KEY
        )).isEqualTo("Rejection reason cannot exceed 500 characters");
    }
}
