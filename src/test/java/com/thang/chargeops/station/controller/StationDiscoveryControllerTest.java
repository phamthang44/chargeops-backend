package com.thang.chargeops.station.controller;

import com.thang.chargeops.booking.service.StationAvailabilityService;
import com.thang.chargeops.common.enums.ChargerType;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.common.enums.StationOperatingState;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.station.dto.station.filter.StationDiscoveryFilter;
import com.thang.chargeops.station.dto.station.filter.StationDiscoverySort;
import com.thang.chargeops.station.dto.station.response.StationDiscoveryDetailResponse;
import com.thang.chargeops.station.dto.station.response.StationAvailabilityResponse;
import com.thang.chargeops.station.dto.station.response.StationDiscoveryItemResponse;
import com.thang.chargeops.station.service.StationDetailService;
import com.thang.chargeops.station.service.StationDiscoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StationDiscoveryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalHandlerError.class)
class StationDiscoveryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StationDiscoveryService stationDiscoveryService;

    @MockitoBean
    private StationDetailService stationDetailService;

    @MockitoBean
    private StationAvailabilityService stationAvailabilityService;

    @Test
    void returnsStationDetailFromDedicatedDetailService() throws Exception {
        UUID stationId = UUID.randomUUID();
        StationDiscoveryDetailResponse response = new StationDiscoveryDetailResponse(
                stationId,
                "ST-0001",
                "Central Fast Charge",
                "Trạm sạc trung tâm",
                "1 Lê Lợi",
                "Phường Bến Nghé",
                "Thành phố Hồ Chí Minh",
                new BigDecimal("10.776900"),
                new BigDecimal("106.700900"),
                "0900000000",
                List.of(),
                new BigDecimal("3500.00"),
                StationOperationalStatus.OPERATING,
                null,
                false,
                true,
                StationOperatingState.OPEN,
                true,
                List.of(),
                null,
                List.of()
        );
        when(stationDetailService.getStationDetail(stationId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/stations/{stationId}", stationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(stationId.toString()))
                .andExpect(jsonPath("$.data.stationCode").value("ST-0001"))
                .andExpect(jsonPath("$.data.currentPriceVndPerKwh").value(3500.00))
                .andExpect(jsonPath("$.data.openNow").value(true))
                .andExpect(jsonPath("$.data.operatingState").value("OPEN"))
                .andExpect(jsonPath("$.data.scheduleConfigured").value(true));

        verify(stationDetailService).getStationDetail(stationId);
    }

    @Test
    void delegatesAvailabilityToBookingOwnedService() throws Exception {
        UUID stationId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        java.time.Instant generatedAt = java.time.Instant.parse("2026-09-01T05:00:00Z");
        java.time.Instant earliestStartAt = java.time.Instant.parse("2026-09-01T06:00:00Z");
        java.time.Instant coverageStartAt = java.time.Instant.parse("2026-09-02T00:00:00Z");
        java.time.Instant coverageEndAt = java.time.Instant.parse("2026-09-03T03:00:00Z");
        com.thang.chargeops.booking.pricing.PriceBasis priceBasis =
                com.thang.chargeops.booking.pricing.PriceBasis.fixedPackage(new java.math.BigDecimal("60.00"));
        StationAvailabilityResponse response = new StationAvailabilityResponse(
                stationId,
                connectorId,
                java.time.LocalDate.parse("2026-09-02"),
                "Asia/Ho_Chi_Minh",
                generatedAt,
                earliestStartAt,
                coverageStartAt,
                coverageEndAt,
                30,
                30,
                180,
                List.of(),
                List.of(),
                List.of(),
                "booking-v4.9",
                priceBasis
        );
        when(stationAvailabilityService.getAvailability(eq(stationId), any()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/stations/{stationId}/availability", stationId)
                        .param("connectorId", connectorId.toString())
                        .param("date", "2026-09-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stationId").value(stationId.toString()))
                .andExpect(jsonPath("$.data.connectorId").value(connectorId.toString()))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.data.earliestStartAt").value("2026-09-01T06:00:00Z"))
                .andExpect(jsonPath("$.data.coverageStartAt").value("2026-09-02T00:00:00Z"))
                .andExpect(jsonPath("$.data.coverageEndAt").value("2026-09-03T03:00:00Z"))
                .andExpect(jsonPath("$.data.policyVersion").value("booking-v4.9"))
                .andExpect(jsonPath("$.data.pricingEstimateParameters.powerKw").value(60.0));

        verify(stationAvailabilityService).getAvailability(eq(stationId), any());
    }

    @Test
    void bindsFiltersAndReturnsOneBasedPageJsonContract() throws Exception {
        UUID stationId = UUID.randomUUID();
        StationDiscoveryItemResponse item = new StationDiscoveryItemResponse(
                stationId,
                "Central Fast Charge",
                "1 Le Loi",
                "Thành phố Hồ Chí Minh",
                new BigDecimal("10.776900"),
                new BigDecimal("106.700900"),
                new BigDecimal("1.25"),
                null,
                new BigDecimal("2800.00"),
                new BigDecimal("150.00"),
                Set.of(ConnectorType.CCS2),
                2,
                1,
                StationOperationalStatus.OPERATING,
                null,
                true,
                StationOperatingState.OPEN,
                true
        );
        var page = new PageImpl<>(List.of(item), PageRequest.of(1, 12), 13);
        when(stationDiscoveryService.searchStations(any(), eq(2), eq(12)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/stations")
                        .param("page", "2")
                        .param("size", "12")
                        .param("query", "central")
                        .param("provinceCode", "79")
                        .param("connectorTypes", "CCS2", "TYPE2")
                        .param("chargerType", "DC")
                        .param("availableOnly", "true")
                        .param("sort", "CHEAPEST")
                        .param("latitude", "10.776900")
                        .param("longitude", "106.700900"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(stationId.toString()))
                .andExpect(jsonPath("$.data[0].provinceName").value("Thành phố Hồ Chí Minh"))
                .andExpect(jsonPath("$.data[0].connectorTypes[0]").value("CCS2"))
                .andExpect(jsonPath("$.data[0].operationalStatus").value("OPERATING"))
                .andExpect(jsonPath("$.data[0].operatingState").value("OPEN"))
                .andExpect(jsonPath("$.data[0].scheduleConfigured").value(true))
                .andExpect(jsonPath("$.meta.page").value(2))
                .andExpect(jsonPath("$.meta.size").value(12))
                .andExpect(jsonPath("$.meta.totalElements").value(13))
                .andExpect(jsonPath("$.meta.totalPages").value(2));

        ArgumentCaptor<StationDiscoveryFilter> filterCaptor =
                ArgumentCaptor.forClass(StationDiscoveryFilter.class);
        verify(stationDiscoveryService).searchStations(
                filterCaptor.capture(),
                eq(2),
                eq(12)
        );

        StationDiscoveryFilter boundFilter = filterCaptor.getValue();
        assertThat(boundFilter.getQuery()).isEqualTo("central");
        assertThat(boundFilter.getProvinceCode()).isEqualTo("79");
        assertThat(boundFilter.getConnectorTypes())
                .containsExactlyInAnyOrder(ConnectorType.CCS2, ConnectorType.TYPE2);
        assertThat(boundFilter.getChargerType()).isEqualTo(ChargerType.DC);
        assertThat(boundFilter.getAvailableOnly()).isTrue();
        assertThat(boundFilter.getSort()).isEqualTo(StationDiscoverySort.CHEAPEST);
        assertThat(boundFilter.getLatitude()).isEqualByComparingTo("10.776900");
        assertThat(boundFilter.getLongitude()).isEqualByComparingTo("106.700900");
    }

    @ParameterizedTest
    @CsvSource({
            "0, 12",
            "1, 0",
            "1, 101"
    })
    void rejectsInvalidPageOrSize(int page, int size) throws Exception {
        mockMvc.perform(get("/api/v1/stations")
                        .param("page", Integer.toString(page))
                        .param("size", Integer.toString(size)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").exists());

        verifyNoInteractions(stationDiscoveryService);
    }

    @Test
    void rejectsIncompleteCoordinatePairAtHttpBindingBoundary() throws Exception {
        mockMvc.perform(get("/api/v1/stations")
                        .param("latitude", "10.776900"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.longitude").exists());

        verifyNoInteractions(stationDiscoveryService);
    }

    @Test
    void rejectsDistanceFilterWithoutCoordinates() throws Exception {
        mockMvc.perform(get("/api/v1/stations")
                        .param("maxDistanceKm", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.latitude").exists())
                .andExpect(jsonPath("$.error.details.longitude").exists());

        verifyNoInteractions(stationDiscoveryService);
    }

    @Test
    void rejectsInvalidEnumQueryParameter() throws Exception {
        mockMvc.perform(get("/api/v1/stations")
                        .param("sort", "FASTEST"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verifyNoInteractions(stationDiscoveryService);
    }
}
