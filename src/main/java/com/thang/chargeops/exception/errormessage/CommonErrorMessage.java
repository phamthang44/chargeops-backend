package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class CommonErrorMessage {
    public static final ErrorMessage.Template UNKNOWN_ERROR =
            template("error.common.unknown", "Unknown error");
    public static final ErrorMessage.Template INTERNAL_ERROR =
            template("error.common.internal", "An unexpected error occurred");
    public static final ErrorMessage.Template DATABASE_ERROR =
            template("error.common.database", "A database error occurred");
    public static final ErrorMessage.Template INVALID_REQUEST =
            template("error.common.invalidRequest", "Invalid request");
    public static final ErrorMessage.Template METHOD_NOT_ALLOWED =
            template("error.common.methodNotAllowed", "HTTP method not allowed");
    public static final ErrorMessage.Template OAUTH_ERROR =
            template("error.common.oauth", "OAuth processing error");
    public static final ErrorMessage.Template DATA_INTEGRITY_ERROR =
            template("error.common.dataIntegrity", "Data integrity violation");
    public static final ErrorMessage.Template RESOURCE_NOT_FOUND =
            template("error.common.resourceNotFound", "Resource not found: {0}");
    public static final ErrorMessage.Template RESOURCE_CONFLICT =
            template("error.common.resourceConflict", "Resource conflict");

    private CommonErrorMessage() {
    }

    static List<ErrorMessage.Template> templates() {
        return List.of(
                UNKNOWN_ERROR,
                INTERNAL_ERROR,
                DATABASE_ERROR,
                INVALID_REQUEST,
                METHOD_NOT_ALLOWED,
                OAUTH_ERROR,
                DATA_INTEGRITY_ERROR,
                RESOURCE_NOT_FOUND,
                RESOURCE_CONFLICT
        );
    }
}
