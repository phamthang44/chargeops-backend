package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class ApprovalErrorMessage {

    public static final String STATION_NOT_PENDING_APPROVAL_KEY =
            "error.approval.stationNotPending";
    public static final String REJECTION_REASON_REQUIRED_KEY =
            "error.approval.rejectionReasonRequired";
    public static final String ACTIVE_LICENSE_REQUIRED_KEY =
            "error.approval.activeLicenseRequired";
    public static final String REJECTION_REASON_VALIDATION_REQUIRED_KEY =
            "validation.approval.reason.required";
    public static final String REJECTION_REASON_MAX_LENGTH_KEY =
            "validation.approval.reason.maxLength";

    public static final ErrorMessage.Template STATION_NOT_PENDING_APPROVAL =
            template(
                    STATION_NOT_PENDING_APPROVAL_KEY,
                    "Station {0} is not pending approval; current status is {1}"
            );
    public static final ErrorMessage.Template REJECTION_REASON_REQUIRED =
            template(
                    REJECTION_REASON_REQUIRED_KEY,
                    "A rejection reason is required"
            );
    public static final ErrorMessage.Template ACTIVE_LICENSE_REQUIRED =
            template(
                    ACTIVE_LICENSE_REQUIRED_KEY,
                    "Station {0} requires an active license before approval"
            );
    public static final ErrorMessage.Template REJECTION_REASON_VALIDATION_REQUIRED =
            template(
                    REJECTION_REASON_VALIDATION_REQUIRED_KEY,
                    "A rejection reason is required"
            );
    public static final ErrorMessage.Template REJECTION_REASON_MAX_LENGTH =
            template(
                    REJECTION_REASON_MAX_LENGTH_KEY,
                    "Rejection reason cannot exceed 500 characters"
            );

    private ApprovalErrorMessage() {
    }

    static List<ErrorMessage.Template> templates() {
        return List.of(
                STATION_NOT_PENDING_APPROVAL,
                REJECTION_REASON_REQUIRED,
                ACTIVE_LICENSE_REQUIRED,
                REJECTION_REASON_VALIDATION_REQUIRED,
                REJECTION_REASON_MAX_LENGTH
        );
    }
}
