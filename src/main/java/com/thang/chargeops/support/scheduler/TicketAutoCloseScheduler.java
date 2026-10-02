package com.thang.chargeops.support.scheduler;

import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.service.impl.TicketWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class TicketAutoCloseScheduler {
    private static final int BATCH_SIZE = 100;

    private final SupportTicketRepository tickets;
    private final TicketWorkflowService workflow;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${chargeops.ticket.auto-close-poll-ms:3600000}")
    public void closeDueTickets() {
        var now = clock.instant();
        Instant cursorAt = null;
        UUID cursorId = null;
        int closed = 0;
        int skipped = 0;
        int failed = 0;
        boolean hasMore = true;
        while (hasMore) {
            List<SupportTicket> batch = List.of();
            try {
                batch = loadDueBatch(now, cursorAt, cursorId);
            } catch (Exception exception) {
                failed++;
                log.error("Failed to load due tickets after deadline {} and ticket {}", cursorAt, cursorId, exception);
            }
            for (var ticket : batch) {
                cursorAt = ticket.getAutoCloseAt();
                cursorId = ticket.getId();
                try {
                    if (workflow.autoClose(ticket.getId(), now)) closed++;
                    else skipped++;
                } catch (Exception exception) {
                    failed++;
                    log.error("Failed to auto-close ticket {}", ticket.getId(), exception);
                }
            }
            hasMore = batch.size() == BATCH_SIZE;
        }
        if (closed + skipped + failed > 0) log.info("Ticket auto-close: closed={}, skipped={}, failed={}", closed, skipped, failed);
        if (failed > 0) log.error("Ticket auto-close batch had {} failures; due tickets will be retried on next poll", failed);
    }

    private List<SupportTicket> loadDueBatch(Instant now, Instant cursorAt, UUID cursorId) {
        var page = PageRequest.of(0, BATCH_SIZE);
        return cursorAt == null ? tickets.findDueFirst(now, page)
                : tickets.findDueAfter(now, cursorAt, cursorId, page);
    }
}
