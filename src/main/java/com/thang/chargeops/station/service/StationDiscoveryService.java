package com.thang.chargeops.station.service;

import com.thang.chargeops.station.dto.station.filter.StationDiscoveryFilter;
import com.thang.chargeops.station.dto.station.response.StationDiscoveryItemResponse;
import org.springframework.data.domain.Page;

public interface StationDiscoveryService {

    Page<StationDiscoveryItemResponse> searchStations(StationDiscoveryFilter filter, int page, int size);
}
