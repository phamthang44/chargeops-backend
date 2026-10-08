package com.thang.chargeops.search.provider;

import com.thang.chargeops.search.GlobalSearchType;
import com.thang.chargeops.search.SearchContext;
import com.thang.chargeops.search.SearchProvider;
import com.thang.chargeops.search.SearchRole;
import com.thang.chargeops.search.dto.SearchHit;
import com.thang.chargeops.station.dto.license.filter.LicenseFilter;
import com.thang.chargeops.station.entity.License;
import com.thang.chargeops.station.repository.LicenseRepository;
import com.thang.chargeops.station.repository.specs.LicenseSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * License trạm. Text match reuse nguyên {@link LicenseSpecification} (đã escape
 * LIKE, match licenseCode/stationCode/tên trạm/owner). Scope:
 * <ul>
 *   <li>ADMIN: platform-wide (không predicate) — đúng như màn license admin;</li>
 *   <li>OWNER: {@code license.owner.id = actor} (FK denormalized trên license);</li>
 *   <li>STAFF: không support.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class LicenseSearchProvider implements SearchProvider {

    private final LicenseRepository licenseRepository;

    @Override
    public GlobalSearchType type() {
        return GlobalSearchType.LICENSE;
    }

    @Override
    public boolean supports(SearchRole role) {
        return role == SearchRole.ADMIN || role == SearchRole.OWNER;
    }

    @Override
    public List<SearchHit> search(SearchContext context, String query, int limit) {
        LicenseFilter textFilter = new LicenseFilter(query, null, null, null, null, null, null, null);
        Specification<License> spec = LicenseSpecification.filter(textFilter);
        if (context.role() == SearchRole.OWNER) {
            spec = Specification.allOf(spec,
                    (root, criteriaQuery, cb) -> cb.equal(root.get("owner").get("id"), context.actorId()));
        }

        PageRequest page = PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt")));
        return licenseRepository.findAll(spec, page).getContent().stream()
                .map(this::toHit)
                .collect(Collectors.toList());
    }

    private SearchHit toHit(License license) {
        return new SearchHit(
                license.getId(),
                license.getLicenseCode(),
                license.getStation() != null ? license.getStation().getName() : null,
                license.getStatus() != null ? license.getStatus().name() : null
        );
    }
}
