package com.thang.chargeops.station.repository.specs;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.station.dto.station.filter.StationFilter;
import com.thang.chargeops.station.entity.Station;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.Locale;


public final class StationSpecification {

    private StationSpecification() {}

    // Each method below describes one optional condition. allOf combines
    // those conditions with AND; only the fields inside search use OR.
    public static Specification<Station> filter(StationFilter filter) {
        if (filter == null) {
            return Specification.unrestricted();
        }

        return Specification.allOf(
                containsSearchText(filter.getSearch()),
                hasStatus(filter.getStatus()),
                belongsToProvince(filter.getProvinceCode())
        );
    }

    private static Specification<Station> containsSearchText(String search) {
        return (root, query, criteriaBuilder) -> {
            if (!StringUtils.hasText(search)) {
                return criteriaBuilder.conjunction();
            }

            String pattern = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
            var owner = root.join("owner", JoinType.INNER);

            return criteriaBuilder.or(
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("stationCode")), pattern),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), pattern),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("addressLine")), pattern),
                    criteriaBuilder.like(criteriaBuilder.lower(owner.get("displayName")), pattern),
                    criteriaBuilder.like(criteriaBuilder.lower(owner.get("email")), pattern)
            );
        };
    }

    private static Specification<Station> hasStatus(StationStatus status) {
        return (root, query, criteriaBuilder) -> status == null
                ? criteriaBuilder.conjunction()
                : criteriaBuilder.equal(root.get("status"), status);
    }

    private static Specification<Station> belongsToProvince(String provinceCode) {
        return (root, query, criteriaBuilder) -> {
            if (!StringUtils.hasText(provinceCode)) {
                return criteriaBuilder.conjunction();
            }

            var ward = root.join("ward", JoinType.INNER);
            var province = ward.join("province", JoinType.INNER);
            return criteriaBuilder.equal(province.get("code"), provinceCode.trim());
        };
    }
}
