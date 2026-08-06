package com.thang.chargeops.exception.errormessage;

import java.text.MessageFormat;
import java.util.Map;
import java.util.Optional;

public final class ErrorMessage {
    private ErrorMessage() {
    }

    public record Template(String key, String defaultMessage) {
        public String format(Object... args) {
            if (args == null || args.length == 0) {
                return defaultMessage;
            }
            try {
                return MessageFormat.format(defaultMessage, args);
            } catch (Exception e) {
                return defaultMessage;
            }
        }
    }

    public static final class Common {
        private Common() {
        }

        public static final Template UNKNOWN_ERROR = template("error.common.unknown", "Unknown error");
        public static final Template INTERNAL_ERROR = template("error.common.internal", "An unexpected error occurred");
        public static final Template DATABASE_ERROR = template("error.common.database", "A database error occurred");
        public static final Template INVALID_REQUEST = template("error.common.invalidRequest", "Invalid request");
        public static final Template METHOD_NOT_ALLOWED = template("error.common.methodNotAllowed", "HTTP method not allowed");
        public static final Template OAUTH_ERROR = template("error.common.oauth", "OAuth processing error");
        public static final Template DATA_INTEGRITY_ERROR = template("error.common.dataIntegrity", "Data integrity violation");
        public static final Template RESOURCE_NOT_FOUND = template("error.common.resourceNotFound", "Resource not found: {0}");
        public static final Template RESOURCE_CONFLICT = template("error.common.resourceConflict", "Resource conflict");
    }

    public static final class Auth {
        private Auth() {
        }

        public static final Template UNAUTHENTICATED = template("error.auth.unauthenticated", "Authentication is required");
        public static final Template TOKEN_EXPIRED = template("error.auth.tokenExpired", "Token has expired");
        public static final Template TOKEN_INVALID = template("error.auth.tokenInvalid", "Invalid token");
        public static final Template ACCESS_DENIED = template("error.auth.accessDenied", "You do not have permission to access this resource");
    }

    public static final class Validation {
        private Validation() {
        }

        public static final String FAILED_KEY = "validation.failed";
        public static final String INVALID_INPUT_KEY = "validation.input.invalid";
        public static final String INVALID_PARAMETERS_KEY = "validation.parameters.invalid";
        public static final String REQUIRED_PARAMETER_KEY = "validation.parameter.required";
        public static final String REQUEST_FORMAT_INVALID_KEY = "validation.request.formatInvalid";
        public static final String FIELD_FORMAT_INVALID_KEY = "validation.field.formatInvalid";
        public static final String FIELD_RANGE_INVALID_KEY = "validation.field.rangeInvalid";
        public static final String JSON_MALFORMED_KEY = "validation.json.malformed";
        public static final String PHONE_INVALID_KEY = "validation.phone.invalid";
        public static final String ENUM_VALUE_INVALID_KEY = "validation.enum.valueInvalid";
        public static final String ENUM_PATTERN_INVALID_KEY = "validation.enum.patternInvalid";
        public static final String REGEX_INVALID_KEY = "validation.regex.invalid";
        public static final String PROFILE_FULL_NAME_REQUIRED_KEY = "validation.profile.fullName.required";
        public static final String PROFILE_PHONE_REQUIRED_KEY = "validation.profile.phone.required";
        public static final String PROFILE_PHONE_MAX_LENGTH_KEY = "validation.profile.phone.maxLength";
        public static final String NAME_REQUIRED_KEY = "validation.name.required";
        public static final String NAME_MIN_LENGTH_KEY = "validation.name.minLength";
        public static final String NAME_MAX_LENGTH_KEY = "validation.name.maxLength";
        public static final String NAME_HTML_NOT_ALLOWED_KEY = "validation.name.htmlNotAllowed";
        public static final String NAME_SCRIPT_NOT_ALLOWED_KEY = "validation.name.scriptNotAllowed";
        public static final String NAME_INVALID_CHARACTERS_KEY = "validation.name.invalidCharacters";
        public static final String DESCRIPTION_MAX_LENGTH_KEY = "validation.description.maxLength";
        public static final String DESCRIPTION_SCRIPT_NOT_ALLOWED_KEY = "validation.description.scriptNotAllowed";

        public static final Template FAILED = template(FAILED_KEY, "Validation failed");
        public static final Template INVALID_INPUT = template(INVALID_INPUT_KEY, "Invalid input data");
        public static final Template INVALID_PARAMETERS = template(INVALID_PARAMETERS_KEY, "Invalid parameters");
        public static final Template REQUIRED_PARAMETER = template(REQUIRED_PARAMETER_KEY, "Missing required parameter: {0}");
        public static final Template REQUEST_FORMAT_INVALID = template(REQUEST_FORMAT_INVALID_KEY, "Invalid request format");
        public static final Template FIELD_FORMAT_INVALID = template(FIELD_FORMAT_INVALID_KEY, "Invalid format for field: {0}");
        public static final Template FIELD_RANGE_INVALID = template(FIELD_RANGE_INVALID_KEY, "Value out of range for field: {0}");
        public static final Template JSON_MALFORMED = template(JSON_MALFORMED_KEY, "Malformed JSON request");
        public static final Template PHONE_INVALID = template(PHONE_INVALID_KEY, "Invalid phone number");
        public static final Template ENUM_VALUE_INVALID = template(ENUM_VALUE_INVALID_KEY, "Invalid enum value");
        public static final Template ENUM_PATTERN_INVALID = template(ENUM_PATTERN_INVALID_KEY, "Invalid enum pattern");
        public static final Template REGEX_INVALID = template(REGEX_INVALID_KEY, "Given regex is invalid");
        public static final Template PROFILE_FULL_NAME_REQUIRED = template(PROFILE_FULL_NAME_REQUIRED_KEY, "Full name is required");
        public static final Template PROFILE_PHONE_REQUIRED = template(PROFILE_PHONE_REQUIRED_KEY, "Phone number is required");
        public static final Template PROFILE_PHONE_MAX_LENGTH = template(PROFILE_PHONE_MAX_LENGTH_KEY, "Phone number cannot exceed 20 characters");
        public static final Template NAME_REQUIRED = template(NAME_REQUIRED_KEY, "Name cannot be empty");
        public static final Template NAME_MIN_LENGTH = template(NAME_MIN_LENGTH_KEY, "Name must be at least 2 characters");
        public static final Template NAME_MAX_LENGTH = template(NAME_MAX_LENGTH_KEY, "Name cannot exceed 255 characters");
        public static final Template NAME_HTML_NOT_ALLOWED = template(NAME_HTML_NOT_ALLOWED_KEY, "Name cannot contain HTML tags");
        public static final Template NAME_SCRIPT_NOT_ALLOWED = template(NAME_SCRIPT_NOT_ALLOWED_KEY, "Name cannot contain script or executable code");
        public static final Template NAME_INVALID_CHARACTERS = template(NAME_INVALID_CHARACTERS_KEY, "Name contains invalid characters");
        public static final Template DESCRIPTION_MAX_LENGTH = template(DESCRIPTION_MAX_LENGTH_KEY, "Description cannot exceed 10000 characters");
        public static final Template DESCRIPTION_SCRIPT_NOT_ALLOWED = template(DESCRIPTION_SCRIPT_NOT_ALLOWED_KEY, "Description cannot contain script or executable code");
    }

    private static final Map<String, Template> TEMPLATES_BY_KEY = Map.ofEntries(
            Map.entry(Common.UNKNOWN_ERROR.key(), Common.UNKNOWN_ERROR),
            Map.entry(Common.INTERNAL_ERROR.key(), Common.INTERNAL_ERROR),
            Map.entry(Common.DATABASE_ERROR.key(), Common.DATABASE_ERROR),
            Map.entry(Common.INVALID_REQUEST.key(), Common.INVALID_REQUEST),
            Map.entry(Common.METHOD_NOT_ALLOWED.key(), Common.METHOD_NOT_ALLOWED),
            Map.entry(Common.OAUTH_ERROR.key(), Common.OAUTH_ERROR),
            Map.entry(Common.DATA_INTEGRITY_ERROR.key(), Common.DATA_INTEGRITY_ERROR),
            Map.entry(Common.RESOURCE_NOT_FOUND.key(), Common.RESOURCE_NOT_FOUND),
            Map.entry(Common.RESOURCE_CONFLICT.key(), Common.RESOURCE_CONFLICT),
            Map.entry(Auth.UNAUTHENTICATED.key(), Auth.UNAUTHENTICATED),
            Map.entry(Auth.TOKEN_EXPIRED.key(), Auth.TOKEN_EXPIRED),
            Map.entry(Auth.TOKEN_INVALID.key(), Auth.TOKEN_INVALID),
            Map.entry(Auth.ACCESS_DENIED.key(), Auth.ACCESS_DENIED),
            Map.entry(Validation.FAILED.key(), Validation.FAILED),
            Map.entry(Validation.INVALID_INPUT.key(), Validation.INVALID_INPUT),
            Map.entry(Validation.INVALID_PARAMETERS.key(), Validation.INVALID_PARAMETERS),
            Map.entry(Validation.REQUIRED_PARAMETER.key(), Validation.REQUIRED_PARAMETER),
            Map.entry(Validation.REQUEST_FORMAT_INVALID.key(), Validation.REQUEST_FORMAT_INVALID),
            Map.entry(Validation.FIELD_FORMAT_INVALID.key(), Validation.FIELD_FORMAT_INVALID),
            Map.entry(Validation.FIELD_RANGE_INVALID.key(), Validation.FIELD_RANGE_INVALID),
            Map.entry(Validation.JSON_MALFORMED.key(), Validation.JSON_MALFORMED),
            Map.entry(Validation.PHONE_INVALID.key(), Validation.PHONE_INVALID),
            Map.entry(Validation.ENUM_VALUE_INVALID.key(), Validation.ENUM_VALUE_INVALID),
            Map.entry(Validation.ENUM_PATTERN_INVALID.key(), Validation.ENUM_PATTERN_INVALID),
            Map.entry(Validation.REGEX_INVALID.key(), Validation.REGEX_INVALID),
            Map.entry(Validation.PROFILE_FULL_NAME_REQUIRED.key(), Validation.PROFILE_FULL_NAME_REQUIRED),
            Map.entry(Validation.PROFILE_PHONE_REQUIRED.key(), Validation.PROFILE_PHONE_REQUIRED),
            Map.entry(Validation.PROFILE_PHONE_MAX_LENGTH.key(), Validation.PROFILE_PHONE_MAX_LENGTH),
            Map.entry(Validation.NAME_REQUIRED.key(), Validation.NAME_REQUIRED),
            Map.entry(Validation.NAME_MIN_LENGTH.key(), Validation.NAME_MIN_LENGTH),
            Map.entry(Validation.NAME_MAX_LENGTH.key(), Validation.NAME_MAX_LENGTH),
            Map.entry(Validation.NAME_HTML_NOT_ALLOWED.key(), Validation.NAME_HTML_NOT_ALLOWED),
            Map.entry(Validation.NAME_SCRIPT_NOT_ALLOWED.key(), Validation.NAME_SCRIPT_NOT_ALLOWED),
            Map.entry(Validation.NAME_INVALID_CHARACTERS.key(), Validation.NAME_INVALID_CHARACTERS),
            Map.entry(Validation.DESCRIPTION_MAX_LENGTH.key(), Validation.DESCRIPTION_MAX_LENGTH),
            Map.entry(Validation.DESCRIPTION_SCRIPT_NOT_ALLOWED.key(), Validation.DESCRIPTION_SCRIPT_NOT_ALLOWED)
    );

    public static Template template(String key, String defaultMessage) {
        return new Template(key, defaultMessage);
    }

    public static Optional<Template> findByKey(String key) {
        return Optional.ofNullable(TEMPLATES_BY_KEY.get(stripBeanValidationBraces(key)));
    }

    public static String defaultMessage(String key) {
        return findByKey(key)
                .map(Template::defaultMessage)
                .orElse(key);
    }

    public static String stripBeanValidationBraces(String message) {
        if (message == null || message.length() < 2) {
            return message;
        }
        if (message.startsWith("{") && message.endsWith("}")) {
            return message.substring(1, message.length() - 1);
        }
        return message;
    }
}
