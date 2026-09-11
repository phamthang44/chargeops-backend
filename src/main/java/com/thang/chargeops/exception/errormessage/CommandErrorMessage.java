package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class CommandErrorMessage {
    private CommandErrorMessage() {
    }

    public static final ErrorMessage.Template KEY_REUSED = template("error.command.keyReused", "The idempotency key was reused with a different request");
    public static final ErrorMessage.Template IN_PROGRESS = template("error.command.inProgress", "The command with this idempotency key is still processing");

    static List<ErrorMessage.Template> templates() {
        return List.of(KEY_REUSED, IN_PROGRESS);
    }
}
