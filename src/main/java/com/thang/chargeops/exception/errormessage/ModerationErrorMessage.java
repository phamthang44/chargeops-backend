package com.thang.chargeops.exception.errormessage;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class ModerationErrorMessage {
    private ModerationErrorMessage() { }
    public static final ErrorMessage.Template PROHIBITED = template("error.content.prohibited", "Content contains prohibited language. Please revise it.");
    public static final ErrorMessage.Template UNAVAILABLE = template("error.moderation.unavailable", "Content validation is temporarily unavailable. Please try again.");
    public static final ErrorMessage.Template NOT_FOUND = template("error.moderation.notFound", "Prohibited term was not found.");
    public static final ErrorMessage.Template DUPLICATE = template("error.moderation.duplicate", "This normalized term and match type already exist.");
    public static final ErrorMessage.Template CONFLICT = template("error.moderation.versionConflict", "The rule has changed. Reload and try again.");
    public static final ErrorMessage.Template INVALID = template("error.moderation.invalidRule", "The moderation rule is invalid.");
}
