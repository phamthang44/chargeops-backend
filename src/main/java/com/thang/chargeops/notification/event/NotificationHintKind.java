package com.thang.chargeops.notification.event;

import com.thang.chargeops.notification.model.NotificationEventType;

import java.util.Objects;

/**
 * Wire kinds of the notification realtime hint (BKG-071 plan #69 §2.2).
 *
 * <p>The hint is a pure signal: REST stays the source of truth. The wire {@code type} is a
 * fixed closed set; there is deliberately no {@code REFUND_*} or content-derived kind, so a
 * decision notice can never be replayed as a refund outcome.
 */
public enum NotificationHintKind {

    CREATED("NOTIFICATION_CREATED"),
    READ("NOTIFICATION_READ"),
    DISMISSED("NOTIFICATION_DISMISSED"),
    READ_ALL("NOTIFICATION_READ_ALL");

    private final String wireType;

    NotificationHintKind(String wireType) {
        this.wireType = wireType;
    }

    /** Value of the {@code type} key on the wire. */
    public String wireType() {
        return wireType;
    }

    /**
     * Kind of the hint emitted when a notice row is first inserted, derived from the notice's
     * own {@link NotificationEventType}. Every event type collapses to {@link #CREATED} on
     * purpose: a {@code RESPONSIBILITY_DECIDED} notice (even one whose decision text mentions a
     * full refund) must only ever produce {@code NOTIFICATION_CREATED} — refund outcomes are
     * emitted by the refund notice itself, never inferred from a decision.
     */
    public static NotificationHintKind forNotice(NotificationEventType eventType) {
        Objects.requireNonNull(eventType, "eventType is required");
        return CREATED;
    }
}
