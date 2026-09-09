package com.thang.chargeops.exception.errormessage;

import java.util.List;
import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class SystemConfigErrorMessage {
    private SystemConfigErrorMessage() { }

    public static final ErrorMessage.Template KEY_REQUIRED =
            template("error.config.keyRequired", "Configuration key is required.");
    public static final ErrorMessage.Template VALUE_REQUIRED =
            template("error.config.valueRequired", "Configuration value is required.");
    public static final ErrorMessage.Template NOT_FOUND =
            template("error.config.notFound", "Configuration was not found.");
    public static final ErrorMessage.Template INVALID_FORMAT =
            template("error.config.invalidFormat", "Configuration value has an invalid format.");
    public static final ErrorMessage.Template OUT_OF_RANGE =
            template("error.config.outOfRange", "Configuration value is outside the allowed range.");
    public static final ErrorMessage.Template UNSUPPORTED_KEY =
            template("error.config.unsupportedKey", "Configuration key is not supported.");
    public static final ErrorMessage.Template REQUIRED_MISSING =
            template("error.config.requiredMissing", "Required system configuration is unavailable.");
    public static final ErrorMessage.Template STORED_INVALID =
            template("error.config.storedInvalid", "Stored system configuration is invalid.");

    public static List<ErrorMessage.Template> templates() {
        return List.of(KEY_REQUIRED, VALUE_REQUIRED, NOT_FOUND, INVALID_FORMAT, OUT_OF_RANGE, UNSUPPORTED_KEY, REQUIRED_MISSING, STORED_INVALID);
    }
}
