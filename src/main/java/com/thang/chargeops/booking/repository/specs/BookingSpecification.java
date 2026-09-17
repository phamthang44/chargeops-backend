package com.thang.chargeops.booking.repository.specs;

import com.thang.chargeops.booking.dto.filter.DriverBookingHistoryFilter;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.BookingStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class BookingSpecification {

    private BookingSpecification() {
    }

    public static Specification<Booking> historyForDriver(
            UUID driverId,
            DriverBookingHistoryFilter filter,
            Instant evaluatedAt
    ) {
        Objects.requireNonNull(driverId, "driverId must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        DriverBookingHistoryFilter normalizedFilter = filter == null
                ? new DriverBookingHistoryFilter(
                        "",
                        DriverBookingHistoryFilter.HistoryStatus.ALL
                )
                : filter;

        return Specification.allOf(
                belongsToDriver(driverId),
                isHistoryAt(evaluatedAt),
                matchesHistoryStatus(
                        normalizedFilter.status(),
                        evaluatedAt
                ),
                matchesQuery(normalizedFilter.query())
        );
    }

    private static Specification<Booking> belongsToDriver(UUID driverId) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("driver").get("id"), driverId);
    }

    private static Specification<Booking> isHistoryAt(Instant evaluatedAt) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.or(
                completed(root, criteriaBuilder),
                cancelled(root, criteriaBuilder, evaluatedAt)
        );
    }

    private static Specification<Booking> matchesHistoryStatus(
            DriverBookingHistoryFilter.HistoryStatus status,
            Instant evaluatedAt
    ) {
        return (root, query, criteriaBuilder) -> switch (status) {
            case COMPLETED -> completed(root, criteriaBuilder);
            case CANCELLED -> cancelled(
                    root,
                    criteriaBuilder,
                    evaluatedAt
            );
            case ALL -> criteriaBuilder.conjunction();
        };
    }

    private static Specification<Booking> matchesQuery(String query) {
        return (root, criteriaQuery, criteriaBuilder) -> {
            if (query == null || query.isBlank()) {
                return criteriaBuilder.conjunction();
            }
            String likePattern = containsPattern(query);
            return criteriaBuilder.or(
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("bookingCode")), likePattern, '\\'),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("chargePointCodeSnapshot")), likePattern, '\\'),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("connectorCodeSnapshot")), likePattern, '\\'),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("stationNameSnapshot")), likePattern, '\\'),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("stationAddressSnapshot")), likePattern, '\\')
            );
        };
    }

    private static Predicate completed(
            Root<Booking> root,
            CriteriaBuilder criteriaBuilder
    ) {
        return criteriaBuilder.equal(
                root.get("status"),
                BookingStatus.COMPLETED
        );
    }

    private static Predicate cancelled(
            Root<Booking> root,
            CriteriaBuilder criteriaBuilder,
            Instant evaluatedAt
    ) {
        Predicate persistedTerminal = root.get("status").in(
                BookingStatus.CANCELLED,
                BookingStatus.EXPIRED
        );
        Predicate expiredPending = criteriaBuilder.and(
                criteriaBuilder.equal(
                        root.get("status"),
                        BookingStatus.PENDING
                ),
                criteriaBuilder.lessThanOrEqualTo(
                        root.get("expiresAt"),
                        evaluatedAt
                )
        );
        Predicate missedCheckIn = criteriaBuilder.and(
                criteriaBuilder.equal(
                        root.get("status"),
                        BookingStatus.CONFIRMED
                ),
                criteriaBuilder.lessThanOrEqualTo(
                        root.get("checkInDeadline"),
                        evaluatedAt
                )
        );
        return criteriaBuilder.or(
                persistedTerminal,
                expiredPending,
                missedCheckIn
        );
    }

    private static String containsPattern(String query) {
        String escaped = query.trim()
                .toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
