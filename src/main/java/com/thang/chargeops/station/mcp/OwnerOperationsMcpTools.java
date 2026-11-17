package com.thang.chargeops.station.mcp;

import com.thang.chargeops.station.dto.dashboard.OwnerDashboardAttentionPageResponse;
import com.thang.chargeops.station.dto.dashboard.OwnerDashboardStationPageResponse;
import com.thang.chargeops.station.dto.station.response.OwnerOperationsSummaryResponse;
import com.thang.chargeops.station.service.OwnerDashboardService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
public class OwnerOperationsMcpTools {

    private final OwnerDashboardService dashboardService;

    public OwnerOperationsMcpTools(OwnerDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @McpTool(name = "get_owner_operations_summary",
            description = "Summarize the authenticated owner's stored station and equipment status. Does not include live charger heartbeat telemetry.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public OperationsSummary getOwnerOperationsSummary() throws IOException {
        try {
            OwnerOperationsSummaryResponse source = dashboardService.summary();
            return new OperationsSummary(source.generatedAt(), source.date(), source.timezone(),
                    source.stations().totalStations(), source.stations().activeStations(),
                    source.stations().visibleToDrivers(), source.stations().pendingApproval(),
                    source.stations().onlineChargePoints(), source.stations().totalChargePoints(),
                    source.hardware().totalConnectors(), source.hardware().availableConnectors(),
                    source.hardware().chargingConnectors(), source.hardware().offlineConnectors(),
                    source.hardware().unavailableConnectors(), source.hardware().sessionsToday(),
                    source.hardware().averageUtilizationPercent());
        } catch (RuntimeException failure) {
            throw new IOException("Owner operations data is temporarily unavailable");
        }
    }

    @McpTool(name = "list_owner_stations",
            description = "List up to 20 stations owned by the authenticated owner with stored operating and equipment counts for the last 7 days. No station IDs are returned.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public StationList listOwnerStations() throws IOException {
        try {
            OwnerDashboardStationPageResponse source = dashboardService.stations(null, "7d", 1, 20);
            List<Station> items = source.items().stream()
                    .map(row -> new Station(row.stationCode(), row.stationName(), row.status(),
                            row.totalConnectors(), row.availableConnectors(), row.chargingConnectors(),
                            row.connectorsNeedingCheck(), row.sessionsStarted()))
                    .toList();
            return new StationList(source.generatedAt(), source.period(), source.total(), items.size() < source.total(), items);
        } catch (RuntimeException failure) {
            throw new IOException("Owner operations data is temporarily unavailable");
        }
    }

    @McpTool(name = "list_owner_equipment_attention",
            description = "List up to 20 equipment alerts for the authenticated owner's stations. Excludes tickets, license data, customer details and internal identifiers.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public EquipmentAttentionList listOwnerEquipmentAttention() throws IOException {
        try {
            OwnerDashboardAttentionPageResponse source = dashboardService.attention(null, "EQUIPMENT", 1, 20);
            List<EquipmentAttention> items = source.items().stream()
                    .map(row -> new EquipmentAttention(row.code(), row.stationName(), row.reasonCode(),
                            row.status(), row.occurredAt()))
                    .toList();
            return new EquipmentAttentionList(source.generatedAt(), source.total(), items.size() < source.total(), items);
        } catch (RuntimeException failure) {
            throw new IOException("Owner operations data is temporarily unavailable");
        }
    }

    public record OperationsSummary(Instant generatedAt, LocalDate date, String timezone,
                                    long totalStations, long activeStations, long visibleToDrivers,
                                    long pendingApproval, long onlineChargePoints, long totalChargePoints,
                                    long totalConnectors, long availableConnectors, long chargingConnectors,
                                    long offlineConnectors, long unavailableConnectors, long sessionsToday,
                                    BigDecimal averageUtilizationPercent) {}

    public record StationList(Instant generatedAt, String period, long total,
                              boolean truncated, List<Station> items) {}

    public record Station(String stationCode, String stationName, String status,
                          long totalConnectors, long availableConnectors, long chargingConnectors,
                          long connectorsNeedingCheck, long sessionsStarted) {}

    public record EquipmentAttentionList(Instant generatedAt, long total,
                                         boolean truncated, List<EquipmentAttention> items) {}

    public record EquipmentAttention(String code, String stationName, String reasonCode,
                                     String status, Instant occurredAt) {}
}
