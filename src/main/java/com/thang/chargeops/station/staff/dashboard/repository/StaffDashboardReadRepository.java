package com.thang.chargeops.station.staff.dashboard.repository;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.station.staff.dashboard.dto.StaffDashboardBookingItem;
import com.thang.chargeops.station.staff.dashboard.dto.StaffDashboardEquipmentItem;
import com.thang.chargeops.station.staff.dashboard.dto.StaffDashboardSummaryResponse;
import com.thang.chargeops.station.staff.dashboard.dto.StaffDashboardTicketItem;
import com.thang.chargeops.incident.ConnectorIncident;
import com.thang.chargeops.station.staff.dashboard.StaffDashboardBookingView;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class StaffDashboardReadRepository {
    private static final ZoneId ZONE = ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE);

    @PersistenceContext
    private EntityManager entityManager;

    public StaffDashboardSummaryResponse summary(UUID stationId, Instant generatedAt) {
        LocalDate date = generatedAt.atZone(ZONE).toLocalDate();
        Instant dayStart = date.atStartOfDay(ZONE).toInstant();
        Instant nextDay = date.plusDays(1).atStartOfDay(ZONE).toInstant();
        return entityManager.createQuery("""
                select new com.thang.chargeops.station.staff.dashboard.dto.StaffDashboardSummaryResponse(
                  s.id, s.name, s.addressLine, s.status, s.operationalStatus, :generatedAt, :date,
                  'Asia/Ho_Chi_Minh',
                  (select count(b) from Booking b where b.connector.chargePoint.station = s
                    and b.startAt >= :dayStart and b.startAt < :nextDay),
                  (select count(b) from Booking b where b.connector.chargePoint.station = s
                    and b.status = com.thang.chargeops.common.enums.BookingStatus.CHARGING),
                  (select count(c) from Connector c join c.chargePoint cp where cp.station = s
                    and cp.provisioningStatus = com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
                    and cp.operationalChargePointStatus = com.thang.chargeops.common.enums.OperationalChargePointStatus.AVAILABLE
                    and c.runtimeStatus = com.thang.chargeops.common.enums.RuntimeStatus.AVAILABLE),
                  (select count(t) from SupportTicket t where t.station = s
                    and t.status in (com.thang.chargeops.support.model.TicketStatus.OPEN,
                      com.thang.chargeops.support.model.TicketStatus.IN_PROGRESS)
                    and t.assignedHandler is null))
                from Station s where s.id = :stationId
                """, StaffDashboardSummaryResponse.class)
                .setParameter("stationId", stationId).setParameter("generatedAt", generatedAt)
                .setParameter("date", date).setParameter("dayStart", dayStart).setParameter("nextDay", nextDay)
                .getSingleResult();
    }

    public long countBookings(UUID stationId, StaffDashboardBookingView view, Instant now, Instant dayEnd) {
        var query = entityManager.createQuery(
                "select count(b) from Booking b where " + bookingPredicate(view), Long.class)
                .setParameter("stationId", stationId);
        if (view == StaffDashboardBookingView.UPCOMING) {
            query.setParameter("now", now).setParameter("dayEnd", dayEnd);
        }
        return query.getSingleResult();
    }

    public List<StaffDashboardBookingItem> bookings(UUID stationId, StaffDashboardBookingView view,
                                                     Instant now, Instant dayEnd, int offset, int size) {
        String sort = view == StaffDashboardBookingView.CHARGING
                ? "b.endAt asc, b.id asc" : "b.startAt asc, b.id asc";
        var query = entityManager.createQuery("""
                select new com.thang.chargeops.station.staff.dashboard.dto.StaffDashboardBookingItem(
                  b.id, b.bookingCode, b.status, c.id, c.connectorCode, driver.displayName,
                  b.startAt, b.endAt, b.checkedInAt, b.checkInDeadline, b.chargingStartedAt)
                from Booking b join b.connector c join c.chargePoint cp join b.driver driver
                where """ + " " + bookingPredicate(view) + " order by " + sort,
                StaffDashboardBookingItem.class)
                .setParameter("stationId", stationId);
        if (view == StaffDashboardBookingView.UPCOMING) {
            query.setParameter("now", now).setParameter("dayEnd", dayEnd);
        }
        return query.setFirstResult(offset).setMaxResults(size).getResultList();
    }

    public long countEquipmentChargePoints(UUID stationId) {
        return entityManager.createQuery("""
                select count(cp) from ChargePoint cp
                where cp.station.id = :stationId
                  and cp.provisioningStatus = com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
                  and cp.operationalChargePointStatus in (
                    com.thang.chargeops.common.enums.OperationalChargePointStatus.OFFLINE,
                    com.thang.chargeops.common.enums.OperationalChargePointStatus.MAINTENANCE)
                """, Long.class).setParameter("stationId", stationId).getSingleResult();
    }

    public long countEquipmentConnectors(UUID stationId) {
        return entityManager.createQuery("""
                select count(c) from Connector c join c.chargePoint cp
                where cp.station.id = :stationId
                  and cp.provisioningStatus = com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
                  and cp.operationalChargePointStatus = com.thang.chargeops.common.enums.OperationalChargePointStatus.AVAILABLE
                  and c.runtimeStatus = com.thang.chargeops.common.enums.RuntimeStatus.OFFLINE
                """, Long.class).setParameter("stationId", stationId).getSingleResult();
    }

    public List<StaffDashboardEquipmentItem> equipmentChargePoints(UUID stationId, int offset, int size) {
        return entityManager.createQuery("""
                select new com.thang.chargeops.station.staff.dashboard.dto.StaffDashboardEquipmentItem(
                  'CHARGE_POINT', cp.id, cp.chargePointCode, cp.id, null,
                  cast(cp.operationalChargePointStatus as string), null,
                  case when cp.operationalChargePointStatus = com.thang.chargeops.common.enums.OperationalChargePointStatus.OFFLINE
                    then 'CHARGE_POINT_OFFLINE' else 'CHARGE_POINT_MAINTENANCE' end, null)
                from ChargePoint cp
                where cp.station.id = :stationId
                  and cp.provisioningStatus = com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
                  and cp.operationalChargePointStatus in (
                    com.thang.chargeops.common.enums.OperationalChargePointStatus.OFFLINE,
                    com.thang.chargeops.common.enums.OperationalChargePointStatus.MAINTENANCE)
                order by cp.chargePointCode asc, cp.id asc
                """, StaffDashboardEquipmentItem.class).setParameter("stationId", stationId)
                .setFirstResult(offset).setMaxResults(size).getResultList();
    }

    public List<StaffDashboardEquipmentItem> equipmentConnectors(UUID stationId, int offset, int size) {
        return entityManager.createQuery("""
                select new com.thang.chargeops.station.staff.dashboard.dto.StaffDashboardEquipmentItem(
                  'CONNECTOR', c.id, c.connectorCode, cp.id, c.id, null,
                  cast(c.runtimeStatus as string), 'CONNECTOR_OFFLINE',
                  (select i.id from ConnectorIncident i
                    where i.connector = c and i.status = :openIncidentStatus
                      and i.reportedAt = (select max(i2.reportedAt) from ConnectorIncident i2
                        where i2.connector = c and i2.status = :openIncidentStatus)))
                from Connector c join c.chargePoint cp
                where cp.station.id = :stationId
                  and cp.provisioningStatus = com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
                  and cp.operationalChargePointStatus = com.thang.chargeops.common.enums.OperationalChargePointStatus.AVAILABLE
                  and c.runtimeStatus = com.thang.chargeops.common.enums.RuntimeStatus.OFFLINE
                order by c.connectorCode asc, c.id asc
                """, StaffDashboardEquipmentItem.class).setParameter("stationId", stationId)
                .setParameter("openIncidentStatus", ConnectorIncident.Status.OPEN)
                .setFirstResult(offset).setMaxResults(size).getResultList();
    }

    public long countTickets(UUID stationId, UUID handlerId, boolean unclaimed) {
        return entityManager.createQuery("select count(t) from SupportTicket t where " + ticketPredicate(), Long.class)
                .setParameter("stationId", stationId).setParameter("handlerId", handlerId)
                .setParameter("unclaimed", unclaimed).getSingleResult();
    }

    public List<StaffDashboardTicketItem> tickets(UUID stationId, UUID handlerId, boolean unclaimed,
                                                   int offset, int size) {
        return entityManager.createQuery("""
                select new com.thang.chargeops.station.staff.dashboard.dto.StaffDashboardTicketItem(
                  t.id, t.ticketCode, t.subject, t.status, t.createdAt, handler.id, handler.displayName)
                from SupportTicket t left join t.assignedHandler handler
                where """ + " " + ticketPredicate() + " order by t.createdAt asc, t.id asc",
                StaffDashboardTicketItem.class)
                .setParameter("stationId", stationId).setParameter("handlerId", handlerId)
                .setParameter("unclaimed", unclaimed).setFirstResult(offset).setMaxResults(size).getResultList();
    }

    private static String bookingPredicate(StaffDashboardBookingView view) {
        String base = "b.connector.chargePoint.station.id = :stationId ";
        return view == StaffDashboardBookingView.CHARGING
                ? base + "and b.status = com.thang.chargeops.common.enums.BookingStatus.CHARGING"
                : base + "and b.status in (com.thang.chargeops.common.enums.BookingStatus.CONFIRMED, "
                + "com.thang.chargeops.common.enums.BookingStatus.CHECKED_IN) "
                + "and b.startAt >= :now and b.startAt < :dayEnd";
    }

    private static String ticketPredicate() {
        return "t.station.id = :stationId and t.status in "
                + "(com.thang.chargeops.support.model.TicketStatus.OPEN, "
                + "com.thang.chargeops.support.model.TicketStatus.IN_PROGRESS) "
                + "and ((:unclaimed = true and t.assignedHandler is null) "
                + "or (:unclaimed = false and t.assignedHandler.id = :handlerId))";
    }

    public List<StaffDashboardEquipmentItem> equipment(UUID stationId, int offset, int size) {
        long cpTotal = countEquipmentChargePoints(stationId);
        long connectorTotal = countEquipmentConnectors(stationId);
        int cpOffset = Math.min(offset, (int) Math.min(Integer.MAX_VALUE, cpTotal));
        int cpSize = offset < cpTotal ? (int) Math.min(size, cpTotal - offset) : 0;
        List<StaffDashboardEquipmentItem> items = cpSize == 0
                ? new ArrayList<>() : new ArrayList<>(equipmentChargePoints(stationId, cpOffset, cpSize));
        int remaining = size - items.size();
        int connectorOffset = Math.max(0, offset - (int) Math.min(offset, cpTotal));
        int connectorSize = remaining > 0 && (long) offset + size > cpTotal
                ? (int) Math.min(remaining, Math.max(0, connectorTotal - connectorOffset)) : 0;
        if (connectorSize > 0) items.addAll(equipmentConnectors(stationId, connectorOffset, connectorSize));
        return items;
    }
}
