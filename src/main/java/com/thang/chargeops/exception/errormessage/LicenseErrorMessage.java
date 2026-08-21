package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class LicenseErrorMessage {

    public static final String PLAN_REQUIRED_KEY =
            "validation.license.plan.required";
    public static final String STATUS_CHANGE_REASON_REQUIRED_KEY =
            "validation.license.reason.required";
    public static final String STATUS_CHANGE_REASON_SIZE_KEY =
            "validation.license.reason.size";
    public static final String LICENSE_WAS_MODIFIED_KEY =
            "error.license.wasModified";
    public static final String INVALID_STATUS_TRANSITION_KEY =
            "error.license.invalidStatusTransition";
    public static final String LICENSE_OUTSIDE_EFFECTIVE_PERIOD_KEY =
            "error.license.outsideEffectivePeriod";
    public static final String LICENSE_NOT_EXPIRED_KEY =
            "error.license.notExpired";
    public static final String LICENSE_NOT_RENEWABLE_KEY =
            "error.license.notRenewable";
    public static final String LICENSE_NOT_LATEST_PERIOD_KEY =
            "error.license.notLatestPeriod";
    public static final String LICENSE_ALREADY_RENEWED_KEY =
            "error.license.alreadyRenewed";

    public static final ErrorMessage.Template PLAN_REQUIRED =
            template(PLAN_REQUIRED_KEY, "License plan is required");
    public static final ErrorMessage.Template STATUS_CHANGE_REASON_REQUIRED =
            template(
                    STATUS_CHANGE_REASON_REQUIRED_KEY,
                    "A reason is required for this license operation"
            );
    public static final ErrorMessage.Template STATUS_CHANGE_REASON_SIZE =
            template(
                    STATUS_CHANGE_REASON_SIZE_KEY,
                    "License operation reason must be between 5 and 500 characters"
            );

    public static final ErrorMessage.Template LICENSE_NOT_FOUND =
            template("error.license.notFound", "License not found: {0}");
    public static final ErrorMessage.Template ACTIVE_LICENSE_ALREADY_EXISTS =
            template(
                    "error.license.activeLicenseAlreadyExists",
                    "An active license already exists for station {0}"
            );
    public static final ErrorMessage.Template LICENSE_WAS_MODIFIED =
            template(
                    LICENSE_WAS_MODIFIED_KEY,
                    "The license was modified by another administrator. Reload and try again"
            );
    public static final ErrorMessage.Template INVALID_STATUS_TRANSITION =
            template(
                    INVALID_STATUS_TRANSITION_KEY,
                    "License {0} cannot transition from {1} to {2}"
            );
    public static final ErrorMessage.Template LICENSE_OUTSIDE_EFFECTIVE_PERIOD =
            template(
                    LICENSE_OUTSIDE_EFFECTIVE_PERIOD_KEY,
                    "License {0} cannot transition outside its effective period"
            );
    public static final ErrorMessage.Template LICENSE_NOT_EXPIRED =
            template(
                    LICENSE_NOT_EXPIRED_KEY,
                    "License {0} cannot be marked expired before {1}"
            );
    public static final ErrorMessage.Template LICENSE_NOT_RENEWABLE =
            template(
                    LICENSE_NOT_RENEWABLE_KEY,
                    "License {0} with status {1} is not eligible for renewal"
            );
    public static final ErrorMessage.Template LICENSE_NOT_LATEST_PERIOD =
            template(
                    LICENSE_NOT_LATEST_PERIOD_KEY,
                    "License {0} is not the latest license period for station {1}"
            );
    public static final ErrorMessage.Template LICENSE_ALREADY_RENEWED =
            template(
                    LICENSE_ALREADY_RENEWED_KEY,
                    "License {0} already has a renewal successor"
            );

    private LicenseErrorMessage() {
    }

    static List<ErrorMessage.Template> templates() {
        return List.of(
                PLAN_REQUIRED,
                STATUS_CHANGE_REASON_REQUIRED,
                STATUS_CHANGE_REASON_SIZE,
                LICENSE_NOT_FOUND,
                ACTIVE_LICENSE_ALREADY_EXISTS,
                LICENSE_WAS_MODIFIED,
                INVALID_STATUS_TRANSITION,
                LICENSE_OUTSIDE_EFFECTIVE_PERIOD,
                LICENSE_NOT_EXPIRED,
                LICENSE_NOT_RENEWABLE,
                LICENSE_NOT_LATEST_PERIOD,
                LICENSE_ALREADY_RENEWED
        );
    }
}
