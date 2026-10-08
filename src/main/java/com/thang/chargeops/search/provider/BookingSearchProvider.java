package com.thang.chargeops.search.provider;

import com.thang.chargeops.search.GlobalSearchType;
import com.thang.chargeops.search.SearchContext;
import com.thang.chargeops.search.SearchLike;
import com.thang.chargeops.search.SearchProvider;
import com.thang.chargeops.search.SearchRole;
import com.thang.chargeops.search.dto.SearchHit;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.repository.BookingRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Lịch sạc. Scope đi qua {@code booking.connector → chargePoint → station}:
 * <ul>
 *   <li>OWNER: {@code station.owner.id = actor} (cùng khóa với
 *       {@code OwnerBookingPredicateFactory.common});</li>
 *   <li>STAFF: {@code station.id ∈ trạm có assignment ACTIVE}.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class BookingSearchProvider implements SearchProvider {

    private final BookingRepository bookingRepository;
    private final StationStaffAssignmentRepository staffAssignmentRepository;

    @Override
    public GlobalSearchType type() {
        return GlobalSearchType.BOOKING;
    }

    @Override
    public boolean supports(SearchRole role) {
        return role == SearchRole.OWNER || role == SearchRole.STAFF;
    }

    @Override
    public List<SearchHit> search(SearchContext context, String query, int limit) {
        String pattern = SearchLike.pattern(query);
        UUID uuid = SearchLike.parseUuid(query);

        Specification<Booking> spec = (root, criteriaQuery, cb) -> {
            var station = root.join("connector").join("chargePoint").join("station");
            List<Predicate> text = new ArrayList<>();
            text.add(cb.like(cb.lower(root.get("bookingCode")), pattern, '\\'));
            text.add(cb.like(cb.lower(root.get("stationNameSnapshot")), pattern, '\\'));
            text.add(cb.like(cb.lower(root.get("stationAddressSnapshot")), pattern, '\\'));
            if (uuid != null) {
                text.add(cb.equal(root.get("id"), uuid));
            }

            List<Predicate> scope = new ArrayList<>();
            if (context.role() == SearchRole.OWNER) {
                scope.add(cb.equal(station.get("owner").get("id"), context.actorId()));
            } else {
                Set<UUID> stationIds = activeStaffStationIds(context.actorId());
                scope.add(stationIds.isEmpty()
                        ? cb.disjunction()
                        : station.get("id").in(stationIds));
            }

            criteriaQuery.distinct(true);
            return cb.and(cb.or(text.toArray(Predicate[]::new)), cb.and(scope.toArray(Predicate[]::new)));
        };

        PageRequest page = PageRequest.of(0, limit,
                Sort.by(Sort.Order.desc("startAt"), Sort.Order.desc("id")));
        return bookingRepository.findAll(spec, page).getContent().stream()
                .map(this::toHit)
                .collect(Collectors.toList());
    }

    private Set<UUID> activeStaffStationIds(UUID actorId) {
        return staffAssignmentRepository.findAllByStaff_IdAndStatus(actorId, StaffAssignmentStatus.ACTIVE)
                .stream()
                .map(assignment -> assignment.getStation().getId())
                .collect(Collectors.toSet());
    }

    private SearchHit toHit(Booking booking) {
        return new SearchHit(
                booking.getId(),
                booking.getBookingCode(),
                booking.getStationNameSnapshot(),
                null
        );
    }
}
