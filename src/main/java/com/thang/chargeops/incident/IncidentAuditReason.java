package com.thang.chargeops.incident;

import java.util.Optional;
import java.util.UUID;

/**
 * Canonical codec for the audit strings written into {@code connector_status_events.reason}
 * whenever an incident transitions a connector:
 * {@code <ACTION>:<incidentId>[:<bookingId>] - <detail>}.
 *
 * <p>The readable incident text lives in {@code connector_incidents} (report reason,
 * recovery reason) and {@code incident_bookings.resolution_reason}. The copy embedded in the
 * status event is capped at 500 chars, so readers must resolve the full text through
 * {@link IncidentReasonResolver} instead of parsing the string themselves.
 */
public final class IncidentAuditReason {

    public enum Action { INCIDENT_REPORT, INCIDENT_RECOVER, INCIDENT_SESSION_STOP }

    /** A parsed audit string; {@code embeddedDetail} is the (possibly truncated) fallback copy. */
    public record Reference(Action action, UUID incidentId, UUID bookingId, String embeddedDetail) { }

    private static final int MAX_LENGTH = 500;

    private IncidentAuditReason() { }

    /** Builds the write-once audit string stored in {@code connector_status_events.reason}. */
    public static String format(Action action, UUID incidentId, UUID bookingId, String detail) {
        StringBuilder base = new StringBuilder(action.name()).append(':').append(incidentId);
        if (bookingId != null) base.append(':').append(bookingId);
        if (detail == null || detail.isBlank()) return base.toString();
        String full = base.append(" - ").append(detail.strip()).toString();
        return full.length() > MAX_LENGTH ? full.substring(0, MAX_LENGTH) : full;
    }

    /** Parses a stored audit string; empty for plain (non-incident) reasons. */
    public static Optional<Reference> parse(String reason) {
        if (reason == null || reason.isBlank()) return Optional.empty();
        for (Action action : Action.values()) {
            String prefix = action.name() + ":";
            if (!reason.startsWith(prefix)) continue;
            String rest = reason.substring(prefix.length());
            String head = rest;
            String embeddedDetail = null;
            int dash = rest.indexOf(" - ");
            if (dash >= 0) {
                head = rest.substring(0, dash);
                String detail = rest.substring(dash + 3).trim();
                embeddedDetail = detail.isEmpty() ? null : detail;
            }
            String[] parts = head.split(":");
            UUID incidentId = uuid(parts[0]);
            if (incidentId == null) return Optional.empty();
            UUID bookingId = parts.length > 1 ? uuid(parts[1]) : null;
            return Optional.of(new Reference(action, incidentId, bookingId, embeddedDetail));
        }
        return Optional.empty();
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }
}
