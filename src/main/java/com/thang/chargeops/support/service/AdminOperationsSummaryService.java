package com.thang.chargeops.support.service;

import com.thang.chargeops.support.dto.response.AdminOperationsSummaryResponse;
import com.thang.chargeops.support.dto.response.AdminTicketSummaryResponse;
import com.thang.chargeops.support.model.TicketStatus;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminOperationsSummaryService {
    private final EntityManager entityManager;
    private final AdminDashboardQueueService dashboardQueues;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminOperationsSummaryResponse dashboard() {
        return dashboardQueues.summary();
    }

    @Transactional(readOnly = true)
    public AdminTicketSummaryResponse tickets(boolean escalated) {
        return ticketCounts(escalated);
    }

    private AdminTicketSummaryResponse ticketCounts(boolean escalated) {
        var rows = escalated
                ? entityManager.createQuery("SELECT t.status, COUNT(t) FROM SupportTicket t WHERE t.station IS NOT NULL "
                        + "AND EXISTS (SELECT e.id FROM TicketEscalation e WHERE e.ticket = t AND e.resolvedAt IS NULL) GROUP BY t.status",
                        Object[].class).getResultList()
                : entityManager.createQuery("SELECT t.status, COUNT(t) FROM SupportTicket t WHERE t.station IS NULL GROUP BY t.status",
                        Object[].class).getResultList();
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

}
