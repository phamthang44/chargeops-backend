package com.thang.chargeops.incident;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves raw {@code connector_status_events.reason} audit strings into structured,
 * full-length incident references for the status-history DTOs (owner/admin and staff).
 *
 * <p>The status event only stores a 500-char capped reference; the authoritative text comes
 * from {@code connector_incidents.reason}, {@code connector_incidents.recovery_reason} or
 * {@code incident_bookings.resolution_reason}.
 */
@Component
@RequiredArgsConstructor
public class IncidentReasonResolver {

    private final ConnectorIncidentRepository incidents;
    private final IncidentBookingRepository affectedBookings;

    /** Full human-readable text for one incident-sourced status event. */
    public record Resolved(IncidentAuditReason.Action action, UUID incidentId, UUID bookingId, String detail) { }

    /**
     * @param rawReasons raw {@code reason} values as stored on the status events
     * @return map keyed by the raw reason string; plain (non-incident) reasons are absent
     */
    @Transactional(readOnly = true)
    public Map<String, Resolved> resolve(Collection<String> rawReasons) {
        Map<String, IncidentAuditReason.Reference> parsed = new HashMap<>();
        Set<UUID> incidentIds = new HashSet<>();
        if (rawReasons != null) {
            for (String raw : rawReasons) {
                if (raw == null) continue;
                IncidentAuditReason.parse(raw).ifPresent(ref -> {
                    if (parsed.putIfAbsent(raw, ref) == null) incidentIds.add(ref.incidentId());
                });
            }
        }
        // HashMap (not Map.of) so callers may safely look up events whose reason is null.
        if (parsed.isEmpty()) return new HashMap<>();

        Map<UUID, ConnectorIncident> incidentById = incidents.findAllById(incidentIds).stream()
                .collect(Collectors.toMap(ConnectorIncident::getId, Function.identity(), (left, right) -> left));

        Map<String, Resolved> resolved = new HashMap<>();
        parsed.forEach((raw, ref) -> resolved.put(raw, resolve(ref, incidentById.get(ref.incidentId()))));
        return resolved;
    }

    private Resolved resolve(IncidentAuditReason.Reference ref, ConnectorIncident incident) {
        String detail = ref.embeddedDetail();
        if (incident != null) {
            detail = switch (ref.action()) {
                case INCIDENT_REPORT -> firstNonBlank(incident.getReason(), detail);
                case INCIDENT_RECOVER -> firstNonBlank(incident.getRecoveryReason(), detail);
                case INCIDENT_SESSION_STOP -> firstNonBlank(resolutionReason(ref), detail);
            };
        }
        return new Resolved(ref.action(), ref.incidentId(), ref.bookingId(), detail);
    }

    private String resolutionReason(IncidentAuditReason.Reference ref) {
        if (ref.bookingId() == null) return null;
        return affectedBookings.findByIncidentIdAndBookingId(ref.incidentId(), ref.bookingId())
                .map(IncidentBooking::getResolutionReason)
                .orElse(null);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
