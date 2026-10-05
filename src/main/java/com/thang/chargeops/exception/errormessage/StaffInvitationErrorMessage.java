package com.thang.chargeops.exception.errormessage;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class StaffInvitationErrorMessage {
    public static final ErrorMessage.Template NOT_FOUND =
            template("error.staffInvite.notFound", "Staff invitation was not found");
    public static final ErrorMessage.Template EMAIL_IN_USE =
            template("error.staffInvite.emailInUse", "Email is already linked to an account or invitation");
    public static final ErrorMessage.Template STATE_CONFLICT =
            template("error.staffInvite.stateConflict", "Staff invitation is not available for this action");
    public static final ErrorMessage.Template IDENTITY_CONFLICT =
            template("error.staffInvite.identityConflict", "Identity does not match the staff invitation");
    public static final ErrorMessage.Template IDENTITY_UNAVAILABLE =
            template("error.staffInvite.identityUnavailable", "Identity provider is unavailable");
    public static final ErrorMessage.Template IDENTITY_SETUP_FAILED =
            template("error.staffInvite.identitySetupFailed", "Staff account setup failed");
    public static final ErrorMessage.Template STATION_UNAVAILABLE =
            template("error.staffInvite.stationUnavailable", "Station is not active for staff activation");

    private StaffInvitationErrorMessage() { }
}
