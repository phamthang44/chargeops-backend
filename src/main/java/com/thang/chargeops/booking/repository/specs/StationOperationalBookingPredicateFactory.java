package com.thang.chargeops.booking.repository.specs;

import com.thang.chargeops.booking.dto.filter.StationOperationalBookingFilter;
import com.thang.chargeops.booking.entity.Booking;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class StationOperationalBookingPredicateFactory {

    public Specification<Booking> forStation(UUID stationId, StationOperationalBookingFilter filter) {
        return (root, query, cb) -> {
            var connector = root.join("connector");
            var station = connector.join("chargePoint").join("station");

            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(station.get("id"), stationId));

            if (filter != null) {
                if (filter.connectorId() != null) {
                    predicates.add(cb.equal(connector.get("id"), filter.connectorId()));
                }
                if (filter.from() != null) {
                    predicates.add(cb.greaterThan(root.get("endAt"), filter.from()));
                }
                if (filter.to() != null) {
                    predicates.add(cb.lessThan(root.get("startAt"), filter.to()));
                }
            }

            query.distinct(true);
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
