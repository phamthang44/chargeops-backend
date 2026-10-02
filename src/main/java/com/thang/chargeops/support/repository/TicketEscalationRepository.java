package com.thang.chargeops.support.repository;

import com.thang.chargeops.support.entity.TicketEscalation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TicketEscalationRepository extends JpaRepository<TicketEscalation, UUID> {
    boolean existsByTicket_Id(UUID ticketId);
    Optional<TicketEscalation> findByTicket_Id(UUID ticketId);
}
