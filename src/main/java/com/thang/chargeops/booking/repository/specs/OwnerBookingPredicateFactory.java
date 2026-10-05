package com.thang.chargeops.booking.repository.specs;

import com.thang.chargeops.booking.dto.filter.OwnerActiveBookingFilter;
import com.thang.chargeops.booking.dto.filter.OwnerBookingFilter;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.BookingStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class OwnerBookingPredicateFactory {

    public Specification<Booking> forList(UUID ownerId, OwnerBookingFilter filter, Instant evaluatedAt) {
        return (root, query, cb) -> {
            List<Predicate> predicates = common(root, cb, ownerId, filter);
            if (filter.status() != null) predicates.add(effectiveStatus(root, cb, filter.status(), evaluatedAt));
            query.distinct(true);
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    public Specification<Booking> forActive(UUID ownerId, OwnerActiveBookingFilter filter, Instant evaluatedAt) {
        return (root, query, cb) -> {
            var connector = root.join("connector");
            var chargePoint = connector.join("chargePoint");
            var station = chargePoint.join("station");
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(station.get("owner").get("id"), ownerId));
            predicates.add(cb.equal(station.get("id"), filter.stationId()));
            if (filter.chargePointId() != null) predicates.add(cb.equal(chargePoint.get("id"), filter.chargePointId()));
            if (filter.connectorId() != null) predicates.add(cb.equal(connector.get("id"), filter.connectorId()));
            Predicate livePending = cb.and(cb.equal(root.get("status"), BookingStatus.PENDING),
                    cb.or(cb.isNull(root.get("expiresAt")), cb.greaterThan(root.get("expiresAt"), evaluatedAt)));
            Predicate liveConfirmed = cb.and(cb.equal(root.get("status"), BookingStatus.CONFIRMED),
                    cb.or(cb.isNull(root.get("checkInDeadline")), cb.greaterThan(root.get("checkInDeadline"), evaluatedAt)));
            predicates.add(cb.or(livePending, liveConfirmed,
                    root.get("status").in(BookingStatus.CHECKED_IN, BookingStatus.CHARGING)));
            query.distinct(true);
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private List<Predicate> common(jakarta.persistence.criteria.Root<Booking> root,
                                   jakarta.persistence.criteria.CriteriaBuilder cb,
                                   UUID ownerId, OwnerBookingFilter filter) {
        var connector = root.join("connector");
        var station = connector.join("chargePoint").join("station");
        List<Predicate> result = new ArrayList<>();
        result.add(cb.equal(station.get("owner").get("id"), ownerId));
        if (filter.stationId() != null) result.add(cb.equal(station.get("id"), filter.stationId()));
        if (filter.connectorId() != null) result.add(cb.equal(connector.get("id"), filter.connectorId()));
        if (filter.from() != null) result.add(cb.greaterThan(root.get("endAt"), filter.from()));
        if (filter.to() != null) result.add(cb.lessThan(root.get("startAt"), filter.to()));
        return result;
    }

    private Predicate effectiveStatus(jakarta.persistence.criteria.Root<Booking> root,
                                      jakarta.persistence.criteria.CriteriaBuilder cb,
                                      BookingStatus status, Instant at) {
        return switch (status) {
            case PENDING -> cb.and(cb.equal(root.get("status"), BookingStatus.PENDING),
                    cb.or(cb.isNull(root.get("expiresAt")), cb.greaterThan(root.get("expiresAt"), at)));
            case EXPIRED -> cb.or(cb.equal(root.get("status"), BookingStatus.EXPIRED),
                    cb.and(cb.equal(root.get("status"), BookingStatus.PENDING),
                            cb.lessThanOrEqualTo(root.get("expiresAt"), at)));
            case CONFIRMED -> cb.and(cb.equal(root.get("status"), BookingStatus.CONFIRMED),
                    cb.or(cb.isNull(root.get("checkInDeadline")), cb.greaterThan(root.get("checkInDeadline"), at)));
            case CANCELLED -> cb.or(cb.equal(root.get("status"), BookingStatus.CANCELLED),
                    cb.and(cb.equal(root.get("status"), BookingStatus.CONFIRMED),
                            cb.lessThanOrEqualTo(root.get("checkInDeadline"), at)));
            default -> cb.equal(root.get("status"), status);
        };
    }
}
