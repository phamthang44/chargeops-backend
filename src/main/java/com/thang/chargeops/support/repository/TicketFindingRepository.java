package com.thang.chargeops.support.repository;

import com.thang.chargeops.support.entity.TicketFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

public interface TicketFindingRepository extends JpaRepository<TicketFinding, UUID> {
    Optional<TicketFinding> findFirstByTicket_IdOrderByRecordedAtDescIdDesc(UUID ticketId);
    List<TicketFinding> findByTicketIdOrderByRecordedAtAscIdAsc(UUID ticketId);

    List<TicketFinding> findByTicket_IdInOrderByRecordedAtAscIdAsc(Collection<UUID> ticketIds);
}
