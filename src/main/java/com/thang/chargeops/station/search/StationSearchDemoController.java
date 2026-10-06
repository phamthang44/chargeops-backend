package com.thang.chargeops.station.search;

import com.thang.chargeops.common.response.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@ConditionalOnProperty(prefix = "app.search", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/admin/stations/search-index")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class StationSearchDemoController {
    private final StationSearchIndex index;

    @PostMapping("/reindex")
    public ApiResult<Map<String, Integer>> reindex() throws IOException {
        return ApiResult.success(Map.of("indexedStations", index.reindex()));
    }
}
