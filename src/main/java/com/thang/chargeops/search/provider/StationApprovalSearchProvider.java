package com.thang.chargeops.search.provider;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.search.GlobalSearchType;
import com.thang.chargeops.search.SearchContext;
import com.thang.chargeops.search.SearchProvider;
import com.thang.chargeops.search.SearchRole;
import com.thang.chargeops.search.dto.SearchHit;
import com.thang.chargeops.station.dto.station.filter.StationFilter;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.repository.specs.StationSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Hàng đợi duyệt trạm — đúng nguồn dữ liệu với màn duyệt của admin
 * ({@code status = PENDING_APPROVAL}, cùng spec station + text search).
 * ADMIN only; các vai trò khác group biến mất lặng lẽ.
 */
@Component
@RequiredArgsConstructor
public class StationApprovalSearchProvider implements SearchProvider {

    private final StationRepository stationRepository;

    @Override
    public GlobalSearchType type() {
        return GlobalSearchType.APPROVAL;
    }

    @Override
    public boolean supports(SearchRole role) {
        return role == SearchRole.ADMIN;
    }

    @Override
    public List<SearchHit> search(SearchContext context, String query, int limit) {
        StationFilter filter = new StationFilter();
        filter.setSearch(query);
        filter.setStatus(StationStatus.PENDING_APPROVAL);

        Specification<Station> spec = Specification.allOf(
                StationSpecification.filter(filter),
                (root, criteriaQuery, cb) -> cb.isNull(root.get("deletedAt"))
        );

        PageRequest page = PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt")));
        return stationRepository.findAll(spec, page).getContent().stream()
                .map(station -> new SearchHit(
                        station.getId(),
                        station.getName(),
                        station.getAddressLine(),
                        station.getStationCode()
                ))
                .collect(Collectors.toList());
    }
}
