package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class TicketErrorMessage {
    private TicketErrorMessage() {
    }

    public static final ErrorMessage.Template NOT_FOUND = template(
            "error.ticket.notFound", "Support ticket was not found"
    );
    public static final ErrorMessage.Template ACCESS_DENIED = template(
            "error.ticket.accessDenied", "You do not have access to this support ticket"
    );
    public static final ErrorMessage.Template INVALID_SCOPE = template(
            "error.ticket.invalidScope", "The booking or station context is invalid for this ticket"
    );
    public static final ErrorMessage.Template STATE_CONFLICT = template(
            "error.ticket.stateConflict", "The support ticket cannot be changed in its current state"
    );
    public static final ErrorMessage.Template VERSION_CONFLICT = template(
            "error.ticket.versionConflict", "Support ticket data changed; refresh before continuing"
    );
    public static final ErrorMessage.Template CLOSED = template(
            "error.ticket.closed", "A closed support ticket cannot receive new messages"
    );
    public static final ErrorMessage.Template ASSIGNMENT_INVALID = template(
            "error.ticket.assignmentInvalid", "The selected handler cannot process this support ticket"
    );
    public static final ErrorMessage.Template FINDING_INVALID = template(
            "error.ticket.findingInvalid", "The station-failure finding is invalid for this support ticket"
    );
    public static final ErrorMessage.Template CODE_CONFLICT = template(
            "error.ticket.codeConflict", "The support ticket code already exists"
    );

    static List<ErrorMessage.Template> templates() {
        return List.of(
                NOT_FOUND,
                ACCESS_DENIED,
                INVALID_SCOPE,
                STATE_CONFLICT,
                VERSION_CONFLICT,
                CLOSED,
                ASSIGNMENT_INVALID,
                FINDING_INVALID,
                CODE_CONFLICT
        );
    }
}
