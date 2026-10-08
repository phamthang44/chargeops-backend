package com.thang.chargeops.search.provider;

import com.thang.chargeops.search.GlobalSearchType;
import com.thang.chargeops.search.SearchContext;
import com.thang.chargeops.search.SearchLike;
import com.thang.chargeops.search.SearchProvider;
import com.thang.chargeops.search.SearchRole;
import com.thang.chargeops.search.dto.SearchHit;
import com.thang.chargeops.station.dto.station.filter.StationFilter;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.repository.specs.StationSpecification;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Trạm sạc.
 * <ul>
 *   <li>ADMIN: reuse nguyên {@link StationSpecification#filter} — spec này join
 *       owner và match cả owner displayName/email, CHỈ an toàn sau gate ADMIN
 *       (chính là lý do không được tái dùng cho nhánh owner);</li>
 *   <li>OWNER: {@code owner.id = actor} + name/code/address — KHÔNG đụng owner email;</li>
 *   <li>STAFF: không support (staff chỉ có 1 trạm đang công tác, search trạm vô nghĩa).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class StationSearchProvider implements SearchProvider {

    private final StationRepository stationRepository;

    @Override
    public GlobalSearchType type() {
        return GlobalSearchType.STATION;
    }

    @Override
    public boolean supports(SearchRole role) {
        return role == SearchRole.ADMIN || role == SearchRole.OWNER;
    }

    @Override
    public List<SearchHit> search(SearchContext context, String query, int limit) {
        Specification<Station> spec = context.role() == SearchRole.ADMIN
                ? adminSpec(query)
                : ownerSpec(context, query);

        PageRequest page = PageRequest.of(0, limit, Sort.by(Sort.Order.asc("name")));
        return stationRepository.findAll(spec, page).getContent().stream()
                .map(this::toHit)
                .collect(Collectors.toList());
    }

    private Specification<Station> adminSpec(String query) {
        StationFilter filter = new StationFilter();
        filter.setSearch(query);
        return StationSpecification.filter(filter);
    }

    private Specification<Station> ownerSpec(SearchContext context, String query) {
        String pattern = SearchLike.pattern(query);
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("owner").get("id"), context.actorId()));
            predicates.add(cb.isNull(root.get("deletedAt")));
            predicates.add(cb.or(
                    cb.like(cb.lower(root.get("stationCode")), pattern, '\\'),
                    cb.like(cb.lower(root.get("name")), pattern, '\\'),
                    cb.like(cb.lower(root.get("addressLine")), pattern, '\\')
            ));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private SearchHit toHit(Station station) {
        return new SearchHit(
                station.getId(),
                station.getName(),
                station.getAddressLine(),
                station.getStationCode()
        );
    }
}
