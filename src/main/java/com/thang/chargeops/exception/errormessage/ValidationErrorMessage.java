package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class ValidationErrorMessage {
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
    public static final String NAME_REQUIRED_KEY = "validation.name.required";
    public static final String NAME_MIN_LENGTH_KEY = "validation.name.minLength";
    public static final String NAME_MAX_LENGTH_KEY = "validation.name.maxLength";
    public static final String NAME_HTML_NOT_ALLOWED_KEY = "validation.name.htmlNotAllowed";
    public static final String NAME_SCRIPT_NOT_ALLOWED_KEY = "validation.name.scriptNotAllowed";
    public static final String NAME_INVALID_CHARACTERS_KEY = "validation.name.invalidCharacters";
    public static final String DESCRIPTION_MAX_LENGTH_KEY = "validation.description.maxLength";
    public static final String DESCRIPTION_SCRIPT_NOT_ALLOWED_KEY = "validation.description.scriptNotAllowed";

    public static final ErrorMessage.Template FAILED =
            template(FAILED_KEY, "Validation failed");
    public static final ErrorMessage.Template INVALID_INPUT =
            template(INVALID_INPUT_KEY, "Invalid input data");
    public static final ErrorMessage.Template INVALID_PARAMETERS =
            template(INVALID_PARAMETERS_KEY, "Invalid parameters");
    public static final ErrorMessage.Template REQUIRED_PARAMETER =
            template(REQUIRED_PARAMETER_KEY, "Missing required parameter: {0}");
    public static final ErrorMessage.Template REQUEST_FORMAT_INVALID =
            template(REQUEST_FORMAT_INVALID_KEY, "Invalid request format");
    public static final ErrorMessage.Template FIELD_FORMAT_INVALID =
            template(FIELD_FORMAT_INVALID_KEY, "Invalid format for field: {0}");
    public static final ErrorMessage.Template FIELD_RANGE_INVALID =
            template(FIELD_RANGE_INVALID_KEY, "Value out of range for field: {0}");
    public static final ErrorMessage.Template JSON_MALFORMED =
            template(JSON_MALFORMED_KEY, "Malformed JSON request");
    public static final ErrorMessage.Template PHONE_INVALID =
            template(PHONE_INVALID_KEY, "Invalid phone number");
    public static final ErrorMessage.Template ENUM_VALUE_INVALID =
            template(ENUM_VALUE_INVALID_KEY, "Invalid enum value");
    public static final ErrorMessage.Template ENUM_PATTERN_INVALID =
            template(ENUM_PATTERN_INVALID_KEY, "Invalid enum pattern");
    public static final ErrorMessage.Template REGEX_INVALID =
            template(REGEX_INVALID_KEY, "Given regex is invalid");
    public static final ErrorMessage.Template NAME_REQUIRED =
            template(NAME_REQUIRED_KEY, "Name cannot be empty");
    public static final ErrorMessage.Template NAME_MIN_LENGTH =
            template(NAME_MIN_LENGTH_KEY, "Name must be at least 2 characters");
    public static final ErrorMessage.Template NAME_MAX_LENGTH =
            template(NAME_MAX_LENGTH_KEY, "Name cannot exceed 255 characters");
    public static final ErrorMessage.Template NAME_HTML_NOT_ALLOWED =
            template(NAME_HTML_NOT_ALLOWED_KEY, "Name cannot contain HTML tags");
    public static final ErrorMessage.Template NAME_SCRIPT_NOT_ALLOWED =
            template(NAME_SCRIPT_NOT_ALLOWED_KEY, "Name cannot contain script or executable code");
    public static final ErrorMessage.Template NAME_INVALID_CHARACTERS =
            template(NAME_INVALID_CHARACTERS_KEY, "Name contains invalid characters");
    public static final ErrorMessage.Template DESCRIPTION_MAX_LENGTH =
            template(DESCRIPTION_MAX_LENGTH_KEY, "Description cannot exceed 10000 characters");
    public static final ErrorMessage.Template DESCRIPTION_SCRIPT_NOT_ALLOWED =
            template(DESCRIPTION_SCRIPT_NOT_ALLOWED_KEY, "Description cannot contain script or executable code");

    private ValidationErrorMessage() {
    }

    static List<ErrorMessage.Template> templates() {
        return List.of(
                FAILED,
                INVALID_INPUT,
                INVALID_PARAMETERS,
                REQUIRED_PARAMETER,
                REQUEST_FORMAT_INVALID,
                FIELD_FORMAT_INVALID,
                FIELD_RANGE_INVALID,
                JSON_MALFORMED,
                PHONE_INVALID,
                ENUM_VALUE_INVALID,
                ENUM_PATTERN_INVALID,
                REGEX_INVALID,
                NAME_REQUIRED,
                NAME_MIN_LENGTH,
                NAME_MAX_LENGTH,
                NAME_HTML_NOT_ALLOWED,
                NAME_SCRIPT_NOT_ALLOWED,
                NAME_INVALID_CHARACTERS,
                DESCRIPTION_MAX_LENGTH,
                DESCRIPTION_SCRIPT_NOT_ALLOWED
        );
    }
}
