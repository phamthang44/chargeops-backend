package com.thang.chargeops.station.repository.specs;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.dto.license.filter.LicenseFilter;
import com.thang.chargeops.station.entity.License;
import com.thang.chargeops.station.entity.Station;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LicenseSpecification {

    private static final char LIKE_ESCAPE_CHARACTER = '\\';
    private static final String STATION_ASSOCIATION = "station";
    private static final String OWNER_ASSOCIATION = "owner";

    private LicenseSpecification() {
    }

    public static Specification<License> filter(LicenseFilter filter) {
        if (filter == null) {
            return Specification.unrestricted();
        }

        return Specification.allOf(
                matchesSearch(filter.search()),
                matchesLicense(filter),
                matchesStation(filter),
                matchesOwner(filter)
        );
    }

    private static Specification<License> matchesLicense(LicenseFilter filter) {
        if (!hasLicenseFilter(filter)) {
            return Specification.unrestricted();
        }

        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.status() != null) {
                predicates.add(criteriaBuilder.equal(
                        root.get("status"),
                        filter.status()
                ));
            }

            if (hasText(filter.licenseCode())) {
                predicates.add(startsWithIgnoreCase(
                        criteriaBuilder,
                        root.get("licenseCode"),
                        filter.licenseCode()
                ));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Specification<License> matchesStation(LicenseFilter filter) {
        if (!hasStationFilter(filter)) {
            return Specification.unrestricted();
        }

        return (root, query, criteriaBuilder) -> {
            Join<License, Station> station = getOrCreateJoin(
                    root,
                    STATION_ASSOCIATION
            );
            List<Predicate> predicates = new ArrayList<>();

            if (filter.stationId() != null) {
                predicates.add(criteriaBuilder.equal(
                        station.get("id"),
                        filter.stationId()
                ));
            }

            if (hasText(filter.stationCode())) {
                predicates.add(startsWithIgnoreCase(
                        criteriaBuilder,
                        station.get("stationCode"),
                        filter.stationCode()
                ));
            }

            if (hasText(filter.stationName())) {
                predicates.add(containsIgnoreCase(
                        criteriaBuilder,
                        station.get("name"),
                        filter.stationName()
                ));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Specification<License> matchesOwner(LicenseFilter filter) {
        if (!hasOwnerFilter(filter)) {
            return Specification.unrestricted();
        }

        return (root, query, criteriaBuilder) -> {
            Join<License, UserProfile> owner = getOrCreateJoin(
                    root,
                    OWNER_ASSOCIATION
            );
            List<Predicate> predicates = new ArrayList<>();

            if (hasText(filter.ownerName())) {
                predicates.add(containsIgnoreCase(
                        criteriaBuilder,
                        owner.get("displayName"),
                        filter.ownerName()
                ));
            }

            if (hasText(filter.ownerEmail())) {
                predicates.add(equalsIgnoreCase(
                        criteriaBuilder,
                        owner.get("email"),
                        filter.ownerEmail()
                ));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Specification<License> matchesSearch(String search) {
        if (!hasText(search)) {
            return Specification.unrestricted();
        }

        return (root, query, criteriaBuilder) -> {
            Join<License, Station> station = getOrCreateJoin(
                    root,
                    STATION_ASSOCIATION
            );
            Join<License, UserProfile> owner = getOrCreateJoin(
                    root,
                    OWNER_ASSOCIATION
            );

            return criteriaBuilder.or(
                    startsWithIgnoreCase(
                            criteriaBuilder,
                            root.get("licenseCode"),
                            search
                    ),
                    startsWithIgnoreCase(
                            criteriaBuilder,
                            station.get("stationCode"),
                            search
                    ),
                    containsIgnoreCase(
                            criteriaBuilder,
                            station.get("name"),
                            search
                    ),
                    containsIgnoreCase(
                            criteriaBuilder,
                            owner.get("displayName"),
                            search
                    ),
                    containsIgnoreCase(
                            criteriaBuilder,
                            owner.get("email"),
                            search
                    )
            );
        };
    }

    private static Predicate startsWithIgnoreCase(
            CriteriaBuilder criteriaBuilder,
            Expression<String> expression,
            String value) {
        return criteriaBuilder.like(
                criteriaBuilder.lower(expression),
                escapeLikePattern(normalize(value)) + "%",
                LIKE_ESCAPE_CHARACTER
        );
    }

    private static Predicate containsIgnoreCase(
            CriteriaBuilder criteriaBuilder,
            Expression<String> expression,
            String value) {
        return criteriaBuilder.like(
                criteriaBuilder.lower(expression),
                "%" + escapeLikePattern(normalize(value)) + "%",
                LIKE_ESCAPE_CHARACTER
        );
    }

    private static Predicate equalsIgnoreCase(
            CriteriaBuilder criteriaBuilder,
            Expression<String> expression,
            String value) {
        return criteriaBuilder.equal(
                criteriaBuilder.lower(expression),
                normalize(value)
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> Join<License, T> getOrCreateJoin(
            Root<License> root,
            String association) {
        return root.getJoins().stream()
                .filter(join -> join.getAttribute().getName().equals(association))
                .map(join -> (Join<License, T>) join)
                .findFirst()
                .orElseGet(() -> root.join(association, JoinType.INNER));
    }

    private static boolean hasLicenseFilter(LicenseFilter filter) {
        return filter.status() != null || hasText(filter.licenseCode());
    }

    private static boolean hasStationFilter(LicenseFilter filter) {
        return filter.stationId() != null
                || hasText(filter.stationCode())
                || hasText(filter.stationName());
    }

    private static boolean hasOwnerFilter(LicenseFilter filter) {
        return hasText(filter.ownerName()) || hasText(filter.ownerEmail());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String escapeLikePattern(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
