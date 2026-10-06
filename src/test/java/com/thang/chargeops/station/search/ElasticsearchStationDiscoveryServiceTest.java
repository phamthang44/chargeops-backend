package com.thang.chargeops.station.search;

import com.thang.chargeops.station.dto.station.filter.StationDiscoveryFilter;
import com.thang.chargeops.station.dto.station.response.StationDiscoveryItemResponse;
import com.thang.chargeops.station.service.impl.StationDiscoveryServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ElasticsearchStationDiscoveryServiceTest {
    private final StationDiscoveryServiceImpl db = mock(StationDiscoveryServiceImpl.class);
    private final StationSearchIndex index = mock(StationSearchIndex.class);
    private final ElasticsearchStationDiscoveryService service = new ElasticsearchStationDiscoveryService(db, index);

    @Test
    void blankKeywordKeepsExistingDiscovery() {
        var filter = new StationDiscoveryFilter();
        when(db.searchStations(filter, 1, 12)).thenReturn(Page.empty());
        assertThat(service.searchStations(filter, 1, 12)).isEmpty();
        verifyNoInteractions(index);
    }

    @Test
    void rechecksDatabaseVisibilityAndFiltersBeforePagingInRelevanceOrder() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), hidden = UUID.randomUUID();
        var filter = new StationDiscoveryFilter();
        filter.setQuery("charging");
        filter.setProvinceCode("79");
        filter.setOpenOnly(true);
        when(index.searchIds("charging")).thenReturn(List.of(hidden, second, first));
        // DB row order differs from ES; the highest hit is currently hidden by DB rules.
        when(db.searchStations(any(), eq(1), eq(100))).thenReturn(new PageImpl<>(
                List.of(card(first), card(second)), PageRequest.of(0, 100), 2));
        var result = service.searchStations(filter, 2, 1);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(StationDiscoveryItemResponse::id).containsExactly(first);
        verify(db).searchStations(argThat(f -> f.getQuery() == null && "79".equals(f.getProvinceCode())
                && Boolean.TRUE.equals(f.getOpenOnly())), eq(1), eq(100));
        assertThat(filter.getQuery()).isEqualTo("charging");
    }

    @Test
    void noHitsReturnsRealEmptyPage() {
        var filter = new StationDiscoveryFilter();
        filter.setQuery("missing");
        when(index.searchIds("missing")).thenReturn(List.of());
        var result = service.searchStations(filter, 1, 12);
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(db);
    }

    @Test
    void searchFailureIsNotDisguisedAsDatabaseSearch() {
        var filter = new StationDiscoveryFilter();
        filter.setQuery("charging");
        when(index.searchIds("charging")).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> service.searchStations(filter, 1, 12))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(db);
    }

    private StationDiscoveryItemResponse card(UUID id) {
        return new StationDiscoveryItemResponse(id, "Station", "Address", null, null, null, null,
                null, null, null, java.util.Set.of(), 0, 0, null, null, false, null, false);
    }
}
