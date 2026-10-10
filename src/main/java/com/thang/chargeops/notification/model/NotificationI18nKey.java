package com.thang.chargeops.notification.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Standard i18n keys and formatting helper for notification titles and bodies.
 * Content stored in this format allows frontends to resolve localized copy dynamically.
 */
public final class NotificationI18nKey {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Booking notifications
    public static final String BOOKING_CONFIRMED_TITLE = "notification.booking.confirmed.title";
    public static final String BOOKING_CANCELLED_TITLE = "notification.booking.cancelled.title";
    public static final String BOOKING_NO_SHOW_TITLE = "notification.booking.no_show.title";
    public static final String BOOKING_COMPLETED_TITLE = "notification.booking.completed.title";
    public static final String BOOKING_REMINDER_TITLE = "notification.booking.reminder.title";
    public static final String BOOKING_STATE_MESSAGE = "notification.booking.state_message";

    // Refund notifications
    public static final String REFUND_PENDING_TITLE = "notification.refund.pending.title";
    public static final String REFUND_PENDING_BODY = "notification.refund.pending.body";
    public static final String REFUND_ATTEMPT_FAILED_TITLE = "notification.refund.attempt_failed.title";
    public static final String REFUND_ATTEMPT_FAILED_BODY = "notification.refund.attempt_failed.body";
    public static final String REFUND_SUCCEEDED_TITLE = "notification.refund.succeeded.title";
    public static final String REFUND_SUCCEEDED_BODY = "notification.refund.succeeded.body";

    // Ticket notifications
    public static final String TICKET_CREATED_TITLE = "notification.ticket.created.title";
    public static final String TICKET_CREATED_BODY = "notification.ticket.created.body";
    public static final String TICKET_RESOLVED_TITLE = "notification.ticket.resolved.title";
    public static final String TICKET_RESOLVED_BODY = "notification.ticket.resolved.body";
    public static final String TICKET_ASSIGNED_TITLE = "notification.ticket.assigned.title";
    public static final String TICKET_ASSIGNED_BODY = "notification.ticket.assigned.body";
    public static final String TICKET_ESCALATED_TITLE = "notification.ticket.escalated.title";
    public static final String TICKET_ESCALATED_BODY = "notification.ticket.escalated.body";
    public static final String TICKET_CLOSED_TITLE = "notification.ticket.closed.title";
    public static final String TICKET_CLOSED_BODY = "notification.ticket.closed.body";
    public static final String TICKET_REOPENED_TITLE = "notification.ticket.reopened.title";
    public static final String TICKET_REOPENED_BODY = "notification.ticket.reopened.body";

    // Station approval notifications
    public static final String STATION_SUBMITTED_TITLE = "notification.station.submitted.title";
    public static final String STATION_SUBMITTED_BODY = "notification.station.submitted.body";
    public static final String STATION_APPROVED_TITLE = "notification.station.approved.title";
    public static final String STATION_APPROVED_BODY = "notification.station.approved.body";
    public static final String STATION_REJECTED_TITLE = "notification.station.rejected.title";
    public static final String STATION_REJECTED_BODY = "notification.station.rejected.body";
    public static final String STATION_CHANGES_REQUESTED_TITLE = "notification.station.changes_requested.title";
    public static final String STATION_CHANGES_REQUESTED_BODY = "notification.station.changes_requested.body";

    private NotificationI18nKey() {
    }

    /**
     * Formats an i18n key with parameters using a structured string format (e.g. "key|{\"param\":\"val\"}").
     * When parameters are empty or null, returns the raw key string.
     */
    public static String format(String key, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return key;
        }
        try {
            return key + "|" + MAPPER.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            return key;
        }
    }
}
