package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

/** UM-02: error messages cho admin user management (UM-00 §6.2 error codes). */
public final class UserErrorMessage {
    public static final ErrorMessage.Template TARGET_PROTECTED =
            template("error.user.targetProtected", "Target account cannot be managed from this console");
    public static final ErrorMessage.Template REASON_INVALID =
            template("error.user.reasonInvalid", "Reason is required and must be at most 500 characters");
    public static final ErrorMessage.Template VERSION_CONFLICT =
            template("error.user.versionConflict", "Account was modified concurrently; reload and retry");
    public static final ErrorMessage.Template SUSPEND_BLOCKED =
            template("error.user.suspendBlocked", "Account still has open obligations and cannot be suspended");
    public static final ErrorMessage.Template ROLE_UNVERIFIED =
            template("error.user.roleUnverified", "Authoritative role could not be verified");
    public static final ErrorMessage.Template OWNER_MANAGED_OFFLINE =
            template("error.user.ownerManagedOffline", "Owner accounts are managed outside the app");

    private UserErrorMessage() {
    }

    static List<ErrorMessage.Template> templates() {
        return List.of(
                TARGET_PROTECTED,
                REASON_INVALID,
                VERSION_CONFLICT,
                SUSPEND_BLOCKED,
                ROLE_UNVERIFIED,
                OWNER_MANAGED_OFFLINE
        );
    }
}
