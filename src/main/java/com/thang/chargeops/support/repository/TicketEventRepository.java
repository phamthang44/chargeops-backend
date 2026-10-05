package com.thang.chargeops.support.repository;

import com.thang.chargeops.support.entity.TicketEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

public interface TicketEventRepository extends JpaRepository<TicketEvent, UUID> {
    Optional<TicketEvent> findFirstByTicketIdAndEventTypeOrderByCreatedAtDescIdDesc(UUID ticketId, String eventType);
    Page<TicketEvent> findByTicketId(UUID ticketId, Pageable pageable);

    @Query("""
        select e from TicketEvent e where e.ticketId in :ticketIds and e.eventType in :eventTypes
        order by e.createdAt desc, e.id desc
        """)
    List<TicketEvent> findWorkflowFacts(@Param("ticketIds") Collection<UUID> ticketIds,
                                        @Param("eventTypes") Collection<String> eventTypes);
}
