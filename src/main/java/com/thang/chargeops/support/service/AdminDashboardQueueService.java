package com.thang.chargeops.support.service;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.dto.response.AdminDashboardTicketItem;
import com.thang.chargeops.support.model.TicketStatus;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDashboardQueueService {
    private static final String ITEM_TYPE = AdminDashboardTicketItem.class.getName();
    private static final List<TicketStatus> ACTIVE = List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS);

    private final EntityManager entityManager;
    private final CurrentProfileProvider currentProfile;

    @Transactional(readOnly = true)
    public Page<AdminDashboardTicketItem> platform(int page, int size) {
        requireActiveAdminProfile();
        String source = " from SupportTicket t join t.reporter r "
                + "where t.station is null and t.status in :statuses ";
        String select = "select new " + ITEM_TYPE
                + "(t.id, t.ticketCode, t.subject, t.status, t.createdAt, r.displayName, "
                + "null, null, null) ";
        return load(select, source, page, size);
    }

    @Transactional(readOnly = true)
    public Page<AdminDashboardTicketItem> escalated(int page, int size) {
        requireActiveAdminProfile();
        String source = " from TicketEscalation e join e.ticket t join t.reporter r "
                + "join t.station s where e.resolvedAt is null and t.status in :statuses ";
        String select = "select new " + ITEM_TYPE
                + "(t.id, t.ticketCode, t.subject, t.status, t.createdAt, r.displayName, "
                + "s.id, s.name, e.reason) ";
        return load(select, source, page, size);
    }

    private Page<AdminDashboardTicketItem> load(String select, String source, int page, int size) {
        var pageable = PageRequest.of(page - 1, size);
        var items = entityManager.createQuery(select + source + "order by t.createdAt desc, t.id desc",
                        AdminDashboardTicketItem.class)
                .setParameter("statuses", ACTIVE)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(size)
                .getResultList();
        Long total = entityManager.createQuery("select count(t.id) " + source, Long.class)
                .setParameter("statuses", ACTIVE)
                .getSingleResult();
        return new PageImpl<>(items, pageable, total);
    }

    private void requireActiveAdminProfile() {
        if (currentProfile.requireProfile().getStatus() != UserStatus.ACTIVE) {
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        }
    }
}
