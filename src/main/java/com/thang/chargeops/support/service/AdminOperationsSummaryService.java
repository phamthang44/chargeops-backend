package com.thang.chargeops.support.service;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.support.dto.response.AdminOperationsSummaryResponse;
import com.thang.chargeops.support.dto.response.AdminTicketSummaryResponse;
import com.thang.chargeops.support.model.TicketStatus;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminOperationsSummaryService {
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public AdminOperationsSummaryResponse dashboard() {
        long activeStations = stationCount(StationStatus.ACTIVE);
        long pendingApprovals = stationCount(StationStatus.PENDING_APPROVAL);
        AdminTicketSummaryResponse platform = tickets(false);
        AdminTicketSummaryResponse escalated = tickets(true);
        return new AdminOperationsSummaryResponse(activeStations, pendingApprovals,
                platform.open() + platform.inProgress(),
                escalated.open() + escalated.inProgress());
    }

    @Transactional(readOnly = true)
    public AdminTicketSummaryResponse tickets(boolean escalated) {
        String scope = escalated
                ? "t.station IS NOT NULL AND EXISTS (SELECT e.id FROM TicketEscalation e WHERE e.ticket = t)"
                : "t.station IS NULL";
        var rows = entityManager.createQuery("SELECT t.status, COUNT(t) FROM SupportTicket t WHERE "
                        + scope + " GROUP BY t.status", Object[].class).getResultList();
        Map<String, Long> counts = new LinkedHashMap<>();
        long total = 0;
        for (TicketStatus status : TicketStatus.values()) {
            counts.put(status.name().toLowerCase(java.util.Locale.ROOT), 0L);
        }
        for (Object[] row : rows) {
            String status = ((TicketStatus) row[0]).name().toLowerCase(java.util.Locale.ROOT);
            long count = ((Number) row[1]).longValue();
            counts.put(status, count);
            total += count;
        }
        return new AdminTicketSummaryResponse(total, counts, counts.get("open"),
                counts.get("in_progress"), counts.get("resolved"), counts.get("closed"));
    }

    private long stationCount(StationStatus status) {
        return entityManager.createQuery("SELECT COUNT(s) FROM Station s WHERE s.status = :status", Long.class)
                .setParameter("status", status).getSingleResult();
    }
}
