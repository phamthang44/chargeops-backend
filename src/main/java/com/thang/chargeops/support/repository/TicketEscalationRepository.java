package com.thang.chargeops.support.repository;

import com.thang.chargeops.support.entity.TicketEscalation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface TicketEscalationRepository extends JpaRepository<TicketEscalation, UUID> {
    @Query("SELECT COUNT(e) FROM TicketEscalation e WHERE e.resolvedAt IS NULL")
    long countPendingArbiter();

    @Query(value = """
        SELECT COUNT(*) FROM ticket_escalations e
        JOIN support_tickets t ON t.id = e.ticket_id
        WHERE e.requested_by = t.reporter_id
          AND (SELECT MAX(m.created_at) FROM ticket_messages m
               WHERE m.ticket_id = e.ticket_id AND m.author_kind = 'REPORTER'
                 AND m.created_at <= e.requested_at) <= e.requested_at - INTERVAL '24 hours'
          AND NOT EXISTS (
              SELECT 1 FROM ticket_messages station_message
              WHERE station_message.ticket_id = e.ticket_id
                AND station_message.author_kind IN ('OWNER', 'STAFF')
                AND station_message.created_at <= e.requested_at
                AND station_message.created_at >= (
                    SELECT MAX(reporter_message.created_at) FROM ticket_messages reporter_message
                    WHERE reporter_message.ticket_id = e.ticket_id
                      AND reporter_message.author_kind = 'REPORTER'
                      AND reporter_message.created_at <= e.requested_at))
    """, nativeQuery = true)
    long countUnresponsiveAtRequest();

    @Query(value = """
        SELECT COUNT(*) FROM ticket_escalations e
        JOIN support_tickets t ON t.id = e.ticket_id
        WHERE e.requested_by = t.reporter_id
          AND (SELECT f.conclusion FROM ticket_findings f
               WHERE f.ticket_id = e.ticket_id AND f.recorded_at <= e.requested_at
               ORDER BY f.recorded_at DESC, f.id DESC LIMIT 1) = 'NOT_STATION_FAILURE'
    """, nativeQuery = true)
    long countDisputedFindingsAtRequest();

    boolean existsByTicket_Id(UUID ticketId);
    Optional<TicketEscalation> findByTicket_Id(UUID ticketId);
    boolean existsByTicket_IdAndResolvedAtIsNull(UUID ticketId);
    Optional<TicketEscalation> findByTicket_IdAndResolvedAtIsNull(UUID ticketId);
    Optional<TicketEscalation> findFirstByTicket_IdOrderByRequestedAtDescIdDesc(UUID ticketId);
    Page<TicketEscalation> findByResolvedAtIsNull(Pageable pageable);
}
