package com.thang.chargeops.support.repository;

import com.thang.chargeops.support.entity.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID>, JpaSpecificationExecutor<SupportTicket> {
    Optional<SupportTicket> findByTicketCode(String ticketCode);

    boolean existsByTicketCode(String ticketCode);

    @Query(value = "SELECT nextval('support_ticket_code_seq')", nativeQuery = true)
    Long nextTicketCodeSequence();

    interface TicketScope {
        UUID getStationId();
        UUID getOwnerId();
    }

    @Query("""
        select s.id as stationId, stationOwner.id as ownerId
        from SupportTicket t left join t.station s left join s.owner stationOwner
        where t.id = :id
        """)
    Optional<TicketScope> findScope(@Param("id") UUID id);

    @Query("""
        select t.id from SupportTicket t
        where t.station.id = :stationId and t.assignedHandler.id = :handlerId
          and t.status = com.thang.chargeops.support.model.TicketStatus.IN_PROGRESS
        order by t.id
        """)
    List<UUID> findAssignedInProgress(@Param("stationId") UUID stationId, @Param("handlerId") UUID handlerId);

    @EntityGraph(attributePaths = {"reporter", "assignedHandler", "station", "station.owner", "booking"})
    @Query("select t from SupportTicket t where t.id in :ids")
    List<SupportTicket> findAllWithContext(@Param("ids") List<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SupportTicket t where t.id = :id")
    Optional<SupportTicket> findByIdForUpdate(UUID id);

    @Query("""
        select t from SupportTicket t
        where t.status = com.thang.chargeops.support.model.TicketStatus.RESOLVED
          and t.autoCloseAt <= :now
        order by t.autoCloseAt, t.id
        """)
    List<SupportTicket> findDueFirst(@Param("now") Instant now, Pageable pageable);

    @Query("""
        select t from SupportTicket t
        where t.status = com.thang.chargeops.support.model.TicketStatus.RESOLVED
          and t.autoCloseAt <= :now
          and (t.autoCloseAt > :cursorAt
               or (t.autoCloseAt = :cursorAt and t.id > :cursorId))
        order by t.autoCloseAt, t.id
        """)
    List<SupportTicket> findDueAfter(@Param("now") Instant now, @Param("cursorAt") Instant cursorAt,
                                    @Param("cursorId") UUID cursorId, Pageable pageable);
}
