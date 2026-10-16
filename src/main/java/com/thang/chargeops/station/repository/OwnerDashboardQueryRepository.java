package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.projection.dashboard.OwnerDashboardAttentionProjection;
import com.thang.chargeops.station.projection.dashboard.OwnerDashboardStationProjection;
import com.thang.chargeops.station.projection.dashboard.OwnerDashboardStatusCount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class OwnerDashboardQueryRepository {
    @PersistenceContext
    private EntityManager entityManager;

    public long countSessions(UUID ownerId, UUID stationId, Instant from, Instant to) {
        return count("b.chargingStartedAt", ownerId, stationId, from, to);
    }

    public long countScheduledBookings(UUID ownerId, UUID stationId, Instant from, Instant to) {
        return count("b.startAt", ownerId, stationId, from, to);
    }

    public List<OwnerDashboardStatusCount> countBookingsByStatus(UUID ownerId, UUID stationId,
                                                                  Instant from, Instant to) {
        return entityManager.createQuery("""
                select new com.thang.chargeops.station.projection.dashboard.OwnerDashboardStatusCount(
                    cast(b.status as string), count(b))
                from Booking b
                where b.startAt >= :from and b.startAt < :to
                  and b.connector.chargePoint.station.owner.id = :ownerId
                  and (:stationId is null or b.connector.chargePoint.station.id = :stationId)
                group by b.status
                """, OwnerDashboardStatusCount.class)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("ownerId", ownerId).setParameter("stationId", stationId)
                .getResultList();
    }

    public List<Object[]> countSessionsByLocalDate(UUID ownerId, UUID stationId, Instant from, Instant to) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                select (b.charging_started_at at time zone 'Asia/Ho_Chi_Minh')::date as local_day, count(*)
                from bookings b
                join connectors c on c.id = b.connector_id
                join charge_points cp on cp.id = c.charge_point_id
                join stations s on s.id = cp.station_id
                where s.owner_id = :ownerId and s.deleted_at is null
                  and (cast(:stationId as uuid) is null or s.id = cast(:stationId as uuid))
                  and b.charging_started_at >= :from and b.charging_started_at < :to
                group by local_day order by local_day
                """).setParameter("ownerId", ownerId).setParameter("stationId", stationId)
                .setParameter("from", from).setParameter("to", to).getResultList();
        return rows;
    }

    public boolean isOwnedActiveStation(UUID ownerId, UUID stationId) {
        return entityManager.createQuery("""
                select count(s) from Station s
                where s.id = :stationId and s.owner.id = :ownerId and s.deletedAt is null
                """, Long.class).setParameter("stationId", stationId)
                .setParameter("ownerId", ownerId).getSingleResult() > 0;
    }

    public long countStations(UUID ownerId, UUID stationId) {
        return entityManager.createQuery("""
                select count(s) from Station s
                where s.owner.id = :ownerId and s.deletedAt is null
                  and (:stationId is null or s.id = :stationId)
                """, Long.class).setParameter("ownerId", ownerId)
                .setParameter("stationId", stationId).getSingleResult();
    }

    public List<OwnerDashboardStationProjection> findStations(UUID ownerId, UUID stationId,
                                                                Instant from, Instant to,
                                                                int offset, int size) {
        return entityManager.createQuery("""
                select new com.thang.chargeops.station.projection.dashboard.OwnerDashboardStationProjection(
                  s.id, s.stationCode, s.name, cast(s.status as string),
                  (select count(c.id) from Connector c join c.chargePoint cp where cp.station = s
                    and cp.provisioningStatus = com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
                    and cp.deletedAt is null and c.deletedAt is null),
                  (select count(c.id) from Connector c join c.chargePoint cp where cp.station = s
                    and cp.provisioningStatus = com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
                    and cp.operationalChargePointStatus = com.thang.chargeops.common.enums.OperationalChargePointStatus.AVAILABLE
                    and c.runtimeStatus = com.thang.chargeops.common.enums.RuntimeStatus.AVAILABLE
                    and cp.deletedAt is null and c.deletedAt is null),
                  (select count(c.id) from Connector c join c.chargePoint cp where cp.station = s
                    and cp.provisioningStatus = com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
                    and c.runtimeStatus = com.thang.chargeops.common.enums.RuntimeStatus.IN_USE
                    and cp.deletedAt is null and c.deletedAt is null),
                  ((select count(cp.id) from ChargePoint cp where cp.station = s
                    and cp.provisioningStatus = com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
                    and cp.operationalChargePointStatus in (com.thang.chargeops.common.enums.OperationalChargePointStatus.OFFLINE,
                      com.thang.chargeops.common.enums.OperationalChargePointStatus.MAINTENANCE) and cp.deletedAt is null)
                   + (select count(c.id) from Connector c join c.chargePoint cp where cp.station = s
                    and cp.provisioningStatus = com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
                    and c.runtimeStatus = com.thang.chargeops.common.enums.RuntimeStatus.OFFLINE
                    and cp.operationalChargePointStatus = com.thang.chargeops.common.enums.OperationalChargePointStatus.AVAILABLE
                    and cp.deletedAt is null and c.deletedAt is null)),
                  (select count(b.id) from Booking b where b.connector.chargePoint.station = s
                    and b.chargingStartedAt >= :from and b.chargingStartedAt < :to))
                from Station s
                where s.owner.id = :ownerId and s.deletedAt is null
                  and (:stationId is null or s.id = :stationId)
                order by s.name asc, s.id asc
                """, OwnerDashboardStationProjection.class)
                .setParameter("ownerId", ownerId).setParameter("stationId", stationId)
                .setParameter("from", from).setParameter("to", to)
                .setFirstResult(offset).setMaxResults(size).getResultList();
    }

    public AttentionPage findAttention(UUID ownerId, UUID stationId, String kind,
                                       Instant now, int page, int size) {
        int offset = (page - 1) * size;
        return switch (kind) {
            case "TICKET" -> tickets(ownerId, stationId, offset, size);
            case "LICENSE" -> licenses(ownerId, stationId, now, offset, size);
            default -> equipment(ownerId, stationId, offset, size);
        };
    }

    private AttentionPage equipment(UUID ownerId, UUID stationId, int offset, int size) {
        String scope = "s.owner.id = :ownerId and s.deletedAt is null and cp.deletedAt is null "
                + "and cp.provisioningStatus = com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE "
                + "and cp.operationalChargePointStatus in (com.thang.chargeops.common.enums.OperationalChargePointStatus.OFFLINE, "
                + "com.thang.chargeops.common.enums.OperationalChargePointStatus.MAINTENANCE) "
                + "and (:stationId is null or s.id = :stationId)";
        long pointTotal = entityManager.createQuery("select count(cp) from ChargePoint cp join cp.station s where " + scope, Long.class)
                .setParameter("ownerId", ownerId).setParameter("stationId", stationId).getSingleResult();
        String connectorScope = "s.owner.id = :ownerId and s.deletedAt is null and c.deletedAt is null and cp.deletedAt is null "
                + "and cp.provisioningStatus = com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE "
                + "and c.runtimeStatus = com.thang.chargeops.common.enums.RuntimeStatus.OFFLINE "
                + "and cp.operationalChargePointStatus = com.thang.chargeops.common.enums.OperationalChargePointStatus.AVAILABLE "
                + "and (:stationId is null or s.id = :stationId)";
        long connectorTotal = entityManager.createQuery("select count(c) from Connector c join c.chargePoint cp join cp.station s where " + connectorScope, Long.class)
                .setParameter("ownerId", ownerId).setParameter("stationId", stationId).getSingleResult();

        List<OwnerDashboardAttentionProjection> items = new ArrayList<>();
        int pointOffset = Math.min(offset, (int) Math.min(Integer.MAX_VALUE, pointTotal));
        int pointSize = offset < pointTotal ? (int) Math.min(size, pointTotal - offset) : 0;
        if (pointSize > 0) {
            items.addAll(entityManager.createQuery("""
                    select new com.thang.chargeops.station.projection.dashboard.OwnerDashboardAttentionProjection(
                      cp.id, cp.chargePointCode, s.id, s.name, cast(cp.operationalChargePointStatus as string),
                      cast(cp.operationalChargePointStatus as string), null)
                    from ChargePoint cp join cp.station s
                    where s.owner.id = :ownerId and s.deletedAt is null and cp.deletedAt is null
                      and cp.provisioningStatus = com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
                      and cp.operationalChargePointStatus in (com.thang.chargeops.common.enums.OperationalChargePointStatus.OFFLINE,
                        com.thang.chargeops.common.enums.OperationalChargePointStatus.MAINTENANCE)
                      and (:stationId is null or s.id = :stationId)
                    order by s.name, cp.chargePointCode, cp.id
                    """, OwnerDashboardAttentionProjection.class)
                    .setParameter("ownerId", ownerId).setParameter("stationId", stationId)
                    .setFirstResult(pointOffset).setMaxResults(pointSize).getResultList());
        }
        int remaining = size - items.size();
        int connectorOffset = Math.max(0, offset - (int) Math.min(offset, pointTotal));
        int connectorSize = remaining > 0 && offset + size > pointTotal
                ? (int) Math.min(remaining, Math.max(0, connectorTotal - connectorOffset)) : 0;
        if (connectorSize > 0) {
            items.addAll(entityManager.createQuery("""
                    select new com.thang.chargeops.station.projection.dashboard.OwnerDashboardAttentionProjection(
                      c.id, c.connectorCode, s.id, s.name, 'CONNECTOR_OFFLINE', cast(c.runtimeStatus as string), null)
                    from Connector c join c.chargePoint cp join cp.station s
                    where s.owner.id = :ownerId and s.deletedAt is null and c.deletedAt is null and cp.deletedAt is null
                      and cp.provisioningStatus = com.thang.chargeops.common.enums.ProvisioningStatus.ACTIVE
                      and c.runtimeStatus = com.thang.chargeops.common.enums.RuntimeStatus.OFFLINE
                      and cp.operationalChargePointStatus = com.thang.chargeops.common.enums.OperationalChargePointStatus.AVAILABLE
                      and (:stationId is null or s.id = :stationId)
                    order by s.name, c.connectorCode, c.id
                    """, OwnerDashboardAttentionProjection.class)
                    .setParameter("ownerId", ownerId).setParameter("stationId", stationId)
                    .setFirstResult(connectorOffset).setMaxResults(connectorSize).getResultList());
        }
        return new AttentionPage(items, pointTotal + connectorTotal);
    }

    private AttentionPage tickets(UUID ownerId, UUID stationId, int offset, int size) {
        String scope = "s.owner.id = :ownerId and s.deletedAt is null "
                + "and t.status in (com.thang.chargeops.support.model.TicketStatus.OPEN, com.thang.chargeops.support.model.TicketStatus.IN_PROGRESS) "
                + "and (:stationId is null or s.id = :stationId)";
        long total = entityManager.createQuery("select count(t) from SupportTicket t join t.station s where " + scope, Long.class)
                .setParameter("ownerId", ownerId).setParameter("stationId", stationId).getSingleResult();
        List<OwnerDashboardAttentionProjection> items = entityManager.createQuery("""
                select new com.thang.chargeops.station.projection.dashboard.OwnerDashboardAttentionProjection(
                  t.id, t.ticketCode, s.id, s.name, 'OPEN_TICKET', cast(t.status as string), t.createdAt)
                from SupportTicket t join t.station s
                where s.owner.id = :ownerId and s.deletedAt is null
                  and t.status in (com.thang.chargeops.support.model.TicketStatus.OPEN, com.thang.chargeops.support.model.TicketStatus.IN_PROGRESS)
                  and (:stationId is null or s.id = :stationId)
                order by t.createdAt asc, t.id asc
                """, OwnerDashboardAttentionProjection.class).setParameter("ownerId", ownerId)
                .setParameter("stationId", stationId).setFirstResult(offset).setMaxResults(size).getResultList();
        return new AttentionPage(items, total);
    }

    private AttentionPage licenses(UUID ownerId, UUID stationId, Instant now, int offset, int size) {
        Instant soon = now.plusSeconds(7 * 24 * 60 * 60);
        String scope = "l.owner.id = :ownerId and s.deletedAt is null "
                + "and l.status in (com.thang.chargeops.common.enums.LicenseStatus.ACTIVE, com.thang.chargeops.common.enums.LicenseStatus.EXPIRED) "
                + "and l.expiresAt <= :soon and not exists (select newer.id from License newer where newer.station = s and newer.createdAt > l.createdAt) "
                + "and (:stationId is null or s.id = :stationId)";
        long total = entityManager.createQuery("select count(l) from License l join l.station s where " + scope, Long.class)
                .setParameter("ownerId", ownerId).setParameter("soon", soon)
                .setParameter("stationId", stationId).getSingleResult();
        List<OwnerDashboardAttentionProjection> items = entityManager.createQuery("""
                select new com.thang.chargeops.station.projection.dashboard.OwnerDashboardAttentionProjection(
                  l.id, l.licenseCode, s.id, s.name,
                  case when l.expiresAt < :now then 'LICENSE_EXPIRED' else 'LICENSE_EXPIRING' end,
                  cast(l.status as string), l.expiresAt)
                from License l join l.station s
                where l.owner.id = :ownerId and s.deletedAt is null
                  and l.status in (com.thang.chargeops.common.enums.LicenseStatus.ACTIVE, com.thang.chargeops.common.enums.LicenseStatus.EXPIRED)
                  and l.expiresAt <= :soon
                  and not exists (select newer.id from License newer where newer.station = s and newer.createdAt > l.createdAt)
                  and (:stationId is null or s.id = :stationId)
                order by l.expiresAt asc, l.id asc
                """, OwnerDashboardAttentionProjection.class).setParameter("ownerId", ownerId)
                .setParameter("now", now).setParameter("soon", soon).setParameter("stationId", stationId)
                .setFirstResult(offset).setMaxResults(size).getResultList();
        return new AttentionPage(items, total);
    }

    private long count(String timestampField, UUID ownerId, UUID stationId, Instant from, Instant to) {
        return entityManager.createQuery("""
                select count(b) from Booking b
                where %s >= :from and %s < :to
                  and b.connector.chargePoint.station.owner.id = :ownerId
                  and (:stationId is null or b.connector.chargePoint.station.id = :stationId)
                """.formatted(timestampField, timestampField), Long.class)
                .setParameter("ownerId", ownerId).setParameter("stationId", stationId)
                .setParameter("from", from).setParameter("to", to).getSingleResult();
    }

    public record AttentionPage(List<OwnerDashboardAttentionProjection> items, long total) {}
}
