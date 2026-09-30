package com.thang.chargeops.support.repository;

import com.thang.chargeops.support.entity.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {
    Optional<SupportTicket> findByTicketCode(String ticketCode);

    boolean existsByTicketCode(String ticketCode);
}
