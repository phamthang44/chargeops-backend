package com.thang.chargeops.support.repository;

import com.thang.chargeops.support.entity.TicketEvent;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface TicketKpiRepository extends Repository<TicketEvent, UUID> {
    @Query("""
        select count(distinct e.ticketId) from TicketEvent e
        where e.ticketId in (select t.id from SupportTicket t where t.station.id = :stationId)
          and e.actorKind = 'STAFF' and e.eventType = :eventType
          and (:allStaff = true or e.actorId = :staffId)
          and e.createdAt >= :from and e.createdAt < :to
        """)
    long countActorEvents(@Param("stationId") UUID stationId, @Param("staffId") UUID staffId,
                          @Param("allStaff") boolean allStaff,
                          @Param("eventType") String eventType, @Param("from") Instant from,
                          @Param("to") Instant to);

    @Query("""
        select count(distinct e.ticketId) from TicketEvent e
        where e.ticketId in (select t.id from SupportTicket t where t.station.id = :stationId)
          and e.toHandlerKind = 'STAFF' and e.eventType in ('ASSIGNED', 'REASSIGNED')
          and (:allStaff = true or e.toHandlerId = :staffId)
          and e.createdAt >= :from and e.createdAt < :to
        """)
    long countAssigned(@Param("stationId") UUID stationId, @Param("staffId") UUID staffId,
                       @Param("allStaff") boolean allStaff,
                       @Param("from") Instant from, @Param("to") Instant to);

    @Query("""
        select count(distinct e.ticketId) from TicketEvent e
        where e.ticketId in (select t.id from SupportTicket t where t.station.id = :stationId)
          and e.eventType = :closeEventType
          and e.createdAt >= :from and e.createdAt < :to
          and exists (select r.id from TicketEvent r
              where r.ticketId = e.ticketId and r.resolutionCycle = e.resolutionCycle
                and r.eventType = 'RESOLVED' and r.actorKind = 'STAFF'
                and (:allStaff = true or r.actorId = :staffId))
        """)
    long countCompleted(@Param("stationId") UUID stationId, @Param("staffId") UUID staffId,
                        @Param("allStaff") boolean allStaff,
                        @Param("closeEventType") String closeEventType, @Param("from") Instant from,
                        @Param("to") Instant to);
}
