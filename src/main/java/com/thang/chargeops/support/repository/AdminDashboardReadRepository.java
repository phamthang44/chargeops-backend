package com.thang.chargeops.support.repository;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.support.dto.response.AdminDashboardApprovalItem;
import com.thang.chargeops.support.dto.response.AdminDashboardTicketItem;
import com.thang.chargeops.support.model.AdminDashboardSort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/** Bounded read projections; every queue and its counter share the same source predicate. */
@Repository
public class AdminDashboardReadRepository {
    private static final String PLATFORM_SOURCE = """
            from SupportTicket t join t.reporter reporter left join t.assignedHandler handler
            where t.station is null and t.status in (
              com.thang.chargeops.support.model.TicketStatus.OPEN,
              com.thang.chargeops.support.model.TicketStatus.IN_PROGRESS)
            """;
    private static final String ESCALATED_SOURCE = """
            from TicketEscalation e join e.ticket t join t.reporter reporter
            join t.station station left join t.assignedHandler handler
            where e.resolvedAt is null and t.status in (
              com.thang.chargeops.support.model.TicketStatus.OPEN,
              com.thang.chargeops.support.model.TicketStatus.IN_PROGRESS)
              and not exists (
                select newer.id from TicketEscalation newer
                where newer.ticket = t and newer.resolvedAt is null
                  and (newer.requestedAt > e.requestedAt
                    or (newer.requestedAt = e.requestedAt and newer.id > e.id)))
            """;

    @PersistenceContext
    private EntityManager entityManager;

    public long countStations(StationStatus status) {
        return entityManager.createQuery("select count(s) from Station s where s.status = :status", Long.class)
                .setParameter("status", status).getSingleResult();
    }

    public long countPlatform() {
        return countTickets(PLATFORM_SOURCE);
    }

    public long countEscalated() {
        return countTickets(ESCALATED_SOURCE);
    }

    public Page<AdminDashboardApprovalItem> approvals(Pageable pageable, AdminDashboardSort sort, String language) {
        String direction = sort == AdminDashboardSort.OLDEST_FIRST ? "asc" : "desc";
        var rows = entityManager.createQuery("""
                select new com.thang.chargeops.support.dto.response.AdminDashboardApprovalItem(
                  s.id, s.stationCode, s.name, coalesce(nullif(owner.displayName, ''), owner.email),
                  case when :language = 'en'
                    then coalesce(nullif(province.fullNameEn, ''), province.fullName)
                    else province.fullName end,
                  s.status, s.createdAt)
                from Station s join s.owner owner left join s.ward ward left join ward.province province
                where s.status = :status
                order by s.createdAt %s, s.id %s
                """.formatted(direction, direction), AdminDashboardApprovalItem.class)
                .setParameter("language", language).setParameter("status", StationStatus.PENDING_APPROVAL)
                .setFirstResult(Math.toIntExact(pageable.getOffset())).setMaxResults(pageable.getPageSize())
                .getResultList();
        return new PageImpl<>(rows, pageable, countStations(StationStatus.PENDING_APPROVAL));
    }

    public Page<AdminDashboardTicketItem> platform(Pageable pageable, AdminDashboardSort sort) {
        String order = sort == AdminDashboardSort.OLDEST_FIRST
                ? "t.createdAt asc, t.id asc" : "t.createdAt desc, t.id desc";
        String select = """
                select new com.thang.chargeops.support.dto.response.AdminDashboardTicketItem(
                  t.id, t.ticketCode, t.subject, t.status, t.createdAt, reporter.displayName,
                  null, null, null, null, null, handler.id, handler.displayName)
                """;
        return loadTickets(select, PLATFORM_SOURCE, order, pageable);
    }

    public Page<AdminDashboardTicketItem> escalated(Pageable pageable, AdminDashboardSort sort) {
        // NEWEST_FIRST deliberately retains the legacy ticket-created ordering.
        String order = sort == AdminDashboardSort.OLDEST_FIRST
                ? "e.requestedAt asc, t.id asc" : "t.createdAt desc, t.id desc";
        String select = """
                select new com.thang.chargeops.support.dto.response.AdminDashboardTicketItem(
                  t.id, t.ticketCode, t.subject, t.status, t.createdAt, reporter.displayName,
                  station.id, station.name, e.reason, e.id, e.requestedAt, handler.id, handler.displayName)
                """;
        return loadTickets(select, ESCALATED_SOURCE, order, pageable);
    }

    private Page<AdminDashboardTicketItem> loadTickets(String select, String source, String order, Pageable pageable) {
        var rows = entityManager.createQuery("%s\n%s\norder by %s".formatted(select, source, order),
                        AdminDashboardTicketItem.class)
                .setFirstResult(Math.toIntExact(pageable.getOffset())).setMaxResults(pageable.getPageSize())
                .getResultList();
        return new PageImpl<>(rows, pageable, countTickets(source));
    }

    private long countTickets(String source) {
        return entityManager.createQuery("select count(distinct t.id)\n" + source, Long.class).getSingleResult();
    }
}
