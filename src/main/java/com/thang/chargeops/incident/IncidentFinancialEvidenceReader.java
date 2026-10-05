package com.thang.chargeops.incident;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Internal audit references, never a grant of financial authority or a public financial DTO. */
@Component
@RequiredArgsConstructor
public class IncidentFinancialEvidenceReader {
    private final IncidentBookingRepository affectedBookings;
    public List<Map<String, Object>> forBooking(UUID bookingId) {
        return affectedBookings.findByBookingIdOrderByIdAsc(bookingId).stream().map(row -> {
            Map<String, Object> evidence = new java.util.LinkedHashMap<>();
            evidence.put("incidentId", row.getIncident().getId().toString());
            evidence.put("incidentOccurredAt", row.getIncident().getOccurredAt().toString());
            evidence.put("incidentReportedAt", row.getIncident().getReportedAt().toString());
            evidence.put("snapshotStatus", row.getSnapshotStatus().name());
            evidence.put("snapshotVersion", row.getSnapshotVersion());
            evidence.put("safetyState", row.getState().name());
            if (row.getResolvedAt() != null) evidence.put("sessionResolvedAt", row.getResolvedAt().toString());
            return evidence;
        }).toList();
    }
}
