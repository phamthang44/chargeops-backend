package com.thang.chargeops.station.search;

import com.thang.chargeops.station.dto.station.filter.StationDiscoveryFilter;
import com.thang.chargeops.station.dto.station.response.StationDiscoveryItemResponse;
import com.thang.chargeops.station.service.StationDiscoveryService;
import com.thang.chargeops.station.service.impl.StationDiscoveryServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/** Demo adapter: reuse DB gates/filters, then order eligible cards by ES relevance. */
@Service
@Primary
@ConditionalOnProperty(prefix = "app.search", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ElasticsearchStationDiscoveryService implements StationDiscoveryService {
    private final StationDiscoveryServiceImpl databaseDiscovery;
    private final StationSearchIndex index;

    @Override
    public Page<StationDiscoveryItemResponse> searchStations(StationDiscoveryFilter filter, int page, int size) {
        if (filter.getQuery() == null || filter.getQuery().isBlank()) {
            return databaseDiscovery.searchStations(filter, page, size);
        }
        List<UUID> rankedIds = index.searchIds(filter.getQuery());
        var pageable = PageRequest.of(page - 1, size);
        if (rankedIds.isEmpty()) {
            return Page.empty(pageable);
        }
        StationDiscoveryFilter dbFilter = new StationDiscoveryFilter();
        BeanUtils.copyProperties(filter, dbFilter);
        dbFilter.setQuery(null);
        var eligible = new HashMap<UUID, StationDiscoveryItemResponse>();
        // Deliberately simple for a small demo dataset; no changes to the native discovery SQL.
        for (int dbPage = 1; ; dbPage++) {
            Page<StationDiscoveryItemResponse> batch = databaseDiscovery.searchStations(dbFilter, dbPage, 100);
            if (batch.getTotalElements() > StationSearchIndex.DEMO_LIMIT) {
                throw new IllegalStateException("Elasticsearch demo supports at most 10000 visible stations");
            }
            for (var station : batch) {
                eligible.put(station.id(), station);
            }
            if (!batch.hasNext()) break;
        }
        var ordered = new ArrayList<StationDiscoveryItemResponse>();
        for (UUID id : rankedIds) {
            if (eligible.containsKey(id)) ordered.add(eligible.get(id));
        }
        int start = (int) Math.min(pageable.getOffset(), ordered.size());
        return new PageImpl<>(ordered.subList(start, Math.min(start + size, ordered.size())),
                pageable, ordered.size());
    }
}
