package com.thang.chargeops.station.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal domain event published (in-process) when an equipment status transition has been
 * recorded by {@link com.thang.chargeops.station.service.EquipmentStatusHistoryService}.
 *
 * <p>Contract:
 * <ul>
 *   <li>Scalar snapshot only — plain {@code UUID}/{@code String}/{@code Instant} values resolved
 *       inside the business transaction. Never carries entities or proxies, because the sole
 *       consumer is an {@code AFTER_COMMIT} listener running outside that transaction
 *       ({@code open-in-view} is disabled).</li>
 *   <li>{@code connectorId} is {@code null} when only the charge point changed.</li>
 *   <li>{@code from}/{@code to} are enum names of the dimension named by {@code changeType}.</li>
 *   <li>No actor, reason, or booking information — authorization stays with REST.</li>
 *   <li>Delivery is best-effort: a lost event only delays the realtime hint, never the data
 *       itself (REST remains the source of truth and clients keep a polling safety net).</li>
 * </ul>
 */
public record EquipmentStateChangedEvent(
        UUID stationId,
        UUID chargePointId,
        UUID connectorId,
        String from,
        String to,
        EquipmentChangeType changeType,
        Instant occurredAt
) {
}
