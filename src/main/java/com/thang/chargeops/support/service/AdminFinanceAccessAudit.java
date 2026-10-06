package com.thang.chargeops.support.service;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketEvent;
import com.thang.chargeops.support.repository.TicketEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.Clock;
import java.util.UUID;

/** Append-only evidence of Admin financial reads, using the existing ticket event store. */
@Service
@RequiredArgsConstructor
public class AdminFinanceAccessAudit {
    private final TicketEventRepository events;
    private final Clock clock;

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void appendAccessEvent(SupportTicket ticket, UserProfile admin, UUID escalationId, String eventType) {
        if (admin.getStatus() != UserStatus.ACTIVE) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        events.saveAndFlush(TicketEvent.financialAccess(ticket, admin.getId(), escalationId, eventType, clock.instant()));
    }
}
