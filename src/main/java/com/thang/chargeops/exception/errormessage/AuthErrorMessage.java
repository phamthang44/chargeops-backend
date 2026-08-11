package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class AuthErrorMessage {
    public static final ErrorMessage.Template UNAUTHENTICATED =
            template("error.auth.unauthenticated", "Authentication is required");
    public static final ErrorMessage.Template TOKEN_EXPIRED =
            template("error.auth.tokenExpired", "Token has expired");
    public static final ErrorMessage.Template TOKEN_INVALID =
            template("error.auth.tokenInvalid", "Invalid token");
    public static final ErrorMessage.Template ACCESS_DENIED =
            template("error.auth.accessDenied", "You do not have permission to access this resource");
    public static final ErrorMessage.Template EMAIL_CLAIM_MISSING =
            template("error.auth.emailClaimMissing", "Email is missing from the authenticated token");

    private AuthErrorMessage() {
    }

    static List<ErrorMessage.Template> templates() {
        return List.of(
                UNAUTHENTICATED,
                TOKEN_EXPIRED,
                TOKEN_INVALID,
                ACCESS_DENIED,
                EMAIL_CLAIM_MISSING
        );
    }
}
