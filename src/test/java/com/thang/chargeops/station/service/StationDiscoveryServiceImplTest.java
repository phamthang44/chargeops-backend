package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.station.dto.station.filter.StationDiscoveryFilter;
import com.thang.chargeops.station.dto.station.response.StationDiscoveryItemResponse;
import com.thang.chargeops.station.projection.StationDiscoveryConnectorTypeProjection;
import com.thang.chargeops.station.projection.StationDiscoveryItemProjection;
import com.thang.chargeops.station.repository.StationDiscoveryQueryParameters;
import com.thang.chargeops.station.repository.StationDiscoveryQueryRepository;
import com.thang.chargeops.station.service.impl.StationDiscoveryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationDiscoveryServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-31T05:00:00Z");
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 12);

    @Mock
    private StationDiscoveryQueryRepository discoveryQueryRepository;

    private StationDiscoveryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StationDiscoveryServiceImpl(
                discoveryQueryRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void fallsBackToAvailableWithoutCoordinatesAndMapsConnectorTypes() {
        UUID stationId = UUID.randomUUID();
        StationDiscoveryFilter filter = new StationDiscoveryFilter();
        Page<StationDiscoveryItemProjection> stationPage = pageOf(
                stationProjection(stationId, null)
        );
        StationDiscoveryConnectorTypeProjection connectorType =
                mock(StationDiscoveryConnectorTypeProjection.class);

        when(discoveryQueryRepository.findStations(any(), eq(FIRST_PAGE)))
                .thenReturn(stationPage);
        when(connectorType.getStationId()).thenReturn(stationId);
        when(connectorType.getConnectorType()).thenReturn(ConnectorType.CCS2);
        when(discoveryQueryRepository.findConnectorTypes(List.of(stationId)))
                .thenReturn(List.of(connectorType));

        Page<StationDiscoveryItemResponse> result =
                service.searchStations(filter, 1, 12);

        assertThat(result.getContent()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(stationId);
            assertThat(item.distanceKm()).isNull();
            assertThat(item.connectorTypes()).containsExactly(ConnectorType.CCS2);
            assertThat(item.totalConnectorCount()).isEqualTo(2);
            assertThat(item.availableConnectorCount()).isEqualTo(1);
            assertThat(item.openNow()).isTrue();
        });

        StationDiscoveryQueryParameters parameters = capturedParameters();
        assertThat(parameters.sort()).isEqualTo("AVAILABLE");
        assertThat(parameters.connectorTypesEmpty()).isTrue();
        assertThat(parameters.connectorTypes())
                .containsExactly("__NO_CONNECTOR_TYPE_FILTER__");
        assertThat(parameters.latitude()).isNull();
        assertThat(parameters.longitude()).isNull();

        verify(discoveryQueryRepository).findConnectorTypes(List.of(stationId));
    }

    @Test
    void escapesLikeWildcardsKeepsNearestSortAndRoundsDistance() {
        UUID stationId = UUID.randomUUID();
        StationDiscoveryFilter filter = new StationDiscoveryFilter();
        filter.setQuery(" 100%_Fast!\\ ");
        filter.setLatitude(new BigDecimal("10.776900"));
        filter.setLongitude(new BigDecimal("106.700900"));

        StationDiscoveryItemProjection station = stationProjection(stationId, 1.235);
        when(discoveryQueryRepository.findStations(any(), eq(FIRST_PAGE)))
                .thenReturn(pageOf(station));
        when(discoveryQueryRepository.findConnectorTypes(List.of(stationId)))
                .thenReturn(List.of());

        Page<StationDiscoveryItemResponse> result =
                service.searchStations(filter, 1, 12);

        StationDiscoveryQueryParameters parameters = capturedParameters();
        assertThat(parameters.queryPattern()).isEqualTo("%100!%!_fast!!\\%");
        assertThat(parameters.sort()).isEqualTo("NEAREST");
        assertThat(parameters.latitude()).isEqualByComparingTo("10.776900");
        assertThat(parameters.longitude()).isEqualByComparingTo("106.700900");
        assertThat(result.getContent()).singleElement()
                .extracting(StationDiscoveryItemResponse::distanceKm)
                .isEqualTo(new BigDecimal("1.24"));
    }

    @Test
    void skipsConnectorTypeQueryWhenTheRequestedPageIsEmpty() {
        StationDiscoveryFilter filter = new StationDiscoveryFilter();
        when(discoveryQueryRepository.findStations(any(), eq(FIRST_PAGE)))
                .thenReturn(Page.empty(FIRST_PAGE));

        Page<StationDiscoveryItemResponse> result =
                service.searchStations(filter, 1, 12);

        assertThat(result).isEmpty();
        verify(discoveryQueryRepository, never()).findConnectorTypes(any());
    }

    private StationDiscoveryQueryParameters capturedParameters() {
        ArgumentCaptor<StationDiscoveryQueryParameters> captor =
                ArgumentCaptor.forClass(StationDiscoveryQueryParameters.class);

        verify(discoveryQueryRepository).findStations(captor.capture(), eq(FIRST_PAGE));
        return captor.getValue();
    }

    private Page<StationDiscoveryItemProjection> pageOf(
            StationDiscoveryItemProjection station
    ) {
        return new PageImpl<>(List.of(station), FIRST_PAGE, 1);
    }

    private StationDiscoveryItemProjection stationProjection(
            UUID stationId,
            Double distanceKm
    ) {
        StationDiscoveryItemProjection station =
                mock(StationDiscoveryItemProjection.class);

        when(station.getId()).thenReturn(stationId.toString());
        when(station.getName()).thenReturn("Central Fast Charge");
        when(station.getAddressLine()).thenReturn("1 Le Loi");
        when(station.getLatitude()).thenReturn(new BigDecimal("10.776900"));
        when(station.getLongitude()).thenReturn(new BigDecimal("106.700900"));
        when(station.getDistanceKm()).thenReturn(distanceKm);
        when(station.getPrimaryImageUrl()).thenReturn("https://cdn.test/station-1.jpg");
        when(station.getPriceFromVndPerKwh()).thenReturn(new BigDecimal("2800.00"));
        when(station.getMaxPowerKw()).thenReturn(new BigDecimal("150.00"));
        when(station.getTotalConnectorCount()).thenReturn(2L);
        when(station.getAvailableConnectorCount()).thenReturn(1L);
        when(station.getOpenNow()).thenReturn(true);
        return station;
    }
}