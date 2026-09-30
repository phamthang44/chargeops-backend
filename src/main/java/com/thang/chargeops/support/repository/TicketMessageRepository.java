package com.thang.chargeops.support.repository;

import com.thang.chargeops.support.entity.TicketMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, UUID> {
    List<TicketMessage> findByTicketIdOrderByCreatedAtAscIdAsc(UUID ticketId);

    Optional<TicketMessage> findByAuthor_IdAndClientMessageId(UUID authorId, UUID clientMessageId);

    List<TicketMessage> findByTicket_IdInOrderByCreatedAtAscIdAsc(Collection<UUID> ticketIds);
}
