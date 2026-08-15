package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class ProfileErrorMessage {
    public static final String DISPLAY_NAME_REQUIRED_KEY = "validation.profile.displayName.required";
    public static final String DISPLAY_NAME_MAX_LENGTH_KEY = "validation.profile.displayName.maxLength";
    public static final String PHONE_REQUIRED_KEY = "validation.profile.phone.required";
    public static final String PHONE_MAX_LENGTH_KEY = "validation.profile.phone.maxLength";

    public static final ErrorMessage.Template DISPLAY_NAME_REQUIRED =
            template(DISPLAY_NAME_REQUIRED_KEY, "Display name is required");
    public static final ErrorMessage.Template DISPLAY_NAME_MAX_LENGTH =
            template(DISPLAY_NAME_MAX_LENGTH_KEY, "Display name cannot exceed 255 characters");
    public static final ErrorMessage.Template PHONE_REQUIRED =
            template(PHONE_REQUIRED_KEY, "Phone number is required");
    public static final ErrorMessage.Template PHONE_MAX_LENGTH =
            template(PHONE_MAX_LENGTH_KEY, "Phone number cannot exceed 20 characters");
    public static final ErrorMessage.Template EMAIL_ALREADY_LINKED =
            template("error.profile.emailAlreadyLinked", "Email is already linked to another profile");
    public static final ErrorMessage.Template PROFILE_BOOTSTRAP_FAILED =
            template("error.profile.profileBootstrapFailed", "Bootstrap failed");
    public static final ErrorMessage.Template PROFILE_NOT_FOUND =
            template("error.profile.notFound", "Profile not found");
    public static final ErrorMessage.Template PROFILE_NOT_ACTIVE =
            template("error.profile.notActive", "Profile is not active");

    private ProfileErrorMessage() {
    }

    static List<ErrorMessage.Template> templates() {
        return List.of(
                DISPLAY_NAME_REQUIRED,
                DISPLAY_NAME_MAX_LENGTH,
                PHONE_REQUIRED,
                PHONE_MAX_LENGTH,
                EMAIL_ALREADY_LINKED,
                PROFILE_BOOTSTRAP_FAILED,
                PROFILE_NOT_FOUND,
                PROFILE_NOT_ACTIVE
        );
    }
}
