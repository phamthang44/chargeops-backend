package com.thang.chargeops.exception.errormessage;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class StaffOperationErrorMessage {
    public static final ErrorMessage.Template VERSION_CONFLICT =
            template("error.staffOperation.version_conflict", "Equipment version has changed");
    public static final ErrorMessage.Template ACTIVE_SERVICE =
            template("error.staffOperation.active_service", "Equipment has an active booking or charging session");
    public static final ErrorMessage.Template STATION_UNAVAILABLE =
            template("error.staffOperation.station_unavailable", "Station is not operating");
    public static final ErrorMessage.Template INVALID_TRANSITION =
            template("error.staffOperation.invalid_transition", "Equipment status transition is unavailable");
    public static final ErrorMessage.Template REASON_REQUIRED =
            template("error.staffOperation.reason_required", "A reason is required for this operation");

    private StaffOperationErrorMessage() { }
}
