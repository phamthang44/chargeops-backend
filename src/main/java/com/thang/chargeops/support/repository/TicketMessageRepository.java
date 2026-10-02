package com.thang.chargeops.support.repository;

import com.thang.chargeops.support.entity.TicketMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, UUID> {
    List<TicketMessage> findByTicketIdOrderByCreatedAtAscIdAsc(UUID ticketId);

    @EntityGraph(attributePaths = "author")
    Optional<TicketMessage> findByAuthor_IdAndClientMessageId(UUID authorId, UUID clientMessageId);

    @EntityGraph(attributePaths = "author")
    List<TicketMessage> findByTicket_IdInOrderByCreatedAtAscIdAsc(Collection<UUID> ticketIds);

    @Modifying
    @Query(value = """
            INSERT INTO ticket_messages (ticket_id, author_id, author_kind, body, created_at, client_message_id)
            VALUES (:ticketId, :authorId, :authorKind, :body, :createdAt, :clientMessageId)
            ON CONFLICT (author_id, client_message_id) WHERE client_message_id IS NOT NULL DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreDuplicate(@Param("ticketId") UUID ticketId, @Param("authorId") UUID authorId,
                              @Param("authorKind") String authorKind, @Param("body") String body,
                              @Param("createdAt") Instant createdAt, @Param("clientMessageId") UUID clientMessageId);
}
