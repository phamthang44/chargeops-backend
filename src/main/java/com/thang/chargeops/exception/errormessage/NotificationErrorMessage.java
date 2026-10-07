package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class NotificationErrorMessage {
    private NotificationErrorMessage() {
    }

    public static final ErrorMessage.Template NOT_FOUND = template(
            "error.notification.notFound", "Notification was not found"
    );
    public static final ErrorMessage.Template UNKNOWN_RECIPIENT = template(
            "error.notification.unknownRecipient", "Notification recipient profile was not found"
    );
    public static final ErrorMessage.Template UNKNOWN_TICKET_SOURCE = template(
            "error.notification.unknownTicketSource", "Source ticket for notification was not found"
    );
    public static final ErrorMessage.Template TICKET_TARGET_MISMATCH = template(
            "error.notification.ticketTargetMismatch", "Notice must target the ticket reporter and current resolution cycle"
    );
    public static final ErrorMessage.Template UNSUPPORTED_EVENT = template(
            "error.notification.unsupportedEvent", "Unsupported notification event type"
    );
    public static final ErrorMessage.Template INVALID_EXPIRY = template(
            "error.notification.invalidExpiry", "Notification expiry timestamp is invalid"
    );
    public static final ErrorMessage.Template INVALID_CONTENT = template(
            "error.notification.invalidContent", "Notification title or body content is invalid"
    );
    public static final ErrorMessage.Template INVALID_INTENT = template(
            "error.notification.invalidIntent", "Notification intent payload is invalid"
    );
    public static final ErrorMessage.Template ACCESS_DENIED = template(
            "error.notification.accessDenied", "You do not have permission to view or modify this notification"
    );

    public static List<ErrorMessage.Template> templates() {
        return List.of(
                NOT_FOUND,
                UNKNOWN_RECIPIENT,
                UNKNOWN_TICKET_SOURCE,
                TICKET_TARGET_MISMATCH,
                UNSUPPORTED_EVENT,
                INVALID_EXPIRY,
                INVALID_CONTENT,
                INVALID_INTENT,
                ACCESS_DENIED
        );
    }
}
