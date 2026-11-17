package com.thang.chargeops.station.mcp;

import com.thang.chargeops.station.dto.dashboard.OwnerDashboardAttentionPageResponse;
import com.thang.chargeops.station.dto.dashboard.OwnerDashboardStationPageResponse;
import com.thang.chargeops.station.dto.station.response.OwnerOperationsSummaryResponse;
import com.thang.chargeops.station.service.OwnerDashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OwnerOperationsMcpToolsTest {

    private final OwnerDashboardService dashboard = mock(OwnerDashboardService.class);
    private final OwnerOperationsMcpTools tools = new OwnerOperationsMcpTools(dashboard);

    @Test
    void summaryReturnsAggregatesWithoutInternalIdentifiers() throws Exception {
        when(dashboard.summary()).thenReturn(new OwnerOperationsSummaryResponse(
                Instant.parse("2026-11-17T10:00:00Z"), LocalDate.of(2026, 11, 17), "Asia/Ho_Chi_Minh",
                new OwnerOperationsSummaryResponse.Stations(3, 2, 2, 1, 5, 6),
                new OwnerOperationsSummaryResponse.Hardware(12, 6, 2, 1, 3, 4, BigDecimal.valueOf(17.5))));

        OwnerOperationsMcpTools.OperationsSummary result = tools.getOwnerOperationsSummary();

        assertEquals(3, result.totalStations());
        assertEquals(12, result.totalConnectors());
        assertEquals(BigDecimal.valueOf(17.5), result.averageUtilizationPercent());
        assertFalse(recordHasField(OwnerOperationsMcpTools.OperationsSummary.class, "ownerId"));
    }

    @Test
    void stationAndEquipmentToolsApplyFixedOwnerScopedBoundsAndRemoveIds() throws Exception {
        UUID stationId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-17T10:00:00Z");
        when(dashboard.stations(null, "7d", 1, 20)).thenReturn(new OwnerDashboardStationPageResponse(
                now, "7d", now.minusSeconds(604800), now, List.of(new OwnerDashboardStationPageResponse.Station(
                stationId, "ST-01", "Station 01", "ACTIVE", 4, 2, 1, 1, 3)), 1, 20, 1));
        when(dashboard.attention(null, "EQUIPMENT", 1, 20)).thenReturn(new OwnerDashboardAttentionPageResponse(
                now, "EQUIPMENT", List.of(new OwnerDashboardAttentionPageResponse.Item("EQUIPMENT", entityId,
                "CP-01", stationId, "Station 01", "CONNECTOR_OFFLINE", "OPEN", now)), 1, 20, 1));

        OwnerOperationsMcpTools.StationList stations = tools.listOwnerStations();
        OwnerOperationsMcpTools.EquipmentAttentionList attention = tools.listOwnerEquipmentAttention();

        assertEquals("ST-01", stations.items().getFirst().stationCode());
        assertFalse(stations.truncated());
        assertFalse(recordHasField(OwnerOperationsMcpTools.Station.class, "stationId"));
        assertEquals("CONNECTOR_OFFLINE", attention.items().getFirst().reasonCode());
        assertFalse(recordHasField(OwnerOperationsMcpTools.EquipmentAttention.class, "entityId"));
        verify(dashboard).stations(null, "7d", 1, 20);
        verify(dashboard).attention(null, "EQUIPMENT", 1, 20);
    }

    @Test
    void serviceErrorsBecomeGenericToolFailures() {
        when(dashboard.summary()).thenThrow(new IllegalStateException("sensitive internal detail"));

        java.io.IOException failure = assertThrows(java.io.IOException.class, tools::getOwnerOperationsSummary);

        assertEquals("Owner operations data is temporarily unavailable", failure.getMessage());
        assertFalse(failure.getMessage().contains("sensitive internal detail"));
    }

    @Test
    void exposesExactlyThreeArgumentFreeReadOnlyTools() {
        List<Method> methods = Arrays.stream(OwnerOperationsMcpTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(McpTool.class))
                .toList();

        assertEquals(List.of("get_owner_operations_summary", "list_owner_equipment_attention", "list_owner_stations"),
                methods.stream().map(method -> method.getAnnotation(McpTool.class).name()).sorted().toList());
        assertTrue(methods.stream().allMatch(method -> method.getParameterCount() == 0));
        assertTrue(methods.stream().allMatch(method -> method.getAnnotation(McpTool.class)
                .annotations().readOnlyHint()));
    }

    private static boolean recordHasField(Class<?> type, String fieldName) {
        return List.of(type.getRecordComponents()).stream()
                .anyMatch(component -> fieldName.equals(component.getName()));
    }
}
