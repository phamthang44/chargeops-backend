package com.thang.chargeops.search.provider;

import com.thang.chargeops.search.GlobalSearchType;
import com.thang.chargeops.search.SearchContext;
import com.thang.chargeops.search.SearchLike;
import com.thang.chargeops.search.SearchProvider;
import com.thang.chargeops.search.SearchRole;
import com.thang.chargeops.search.dto.SearchHit;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.repository.ChargePointRepository;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
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
 * Trụ sạc. Scope:
 * <ul>
 *   <li>OWNER: {@code station.owner.id = actor};</li>
 *   <li>STAFF: {@code station.id ∈ trạm có assignment ACTIVE};</li>
 *   <li>ADMIN: không support (màn admin không có màn trụ — search trạm rồi vào detail).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ChargePointSearchProvider implements SearchProvider {

    private final ChargePointRepository chargePointRepository;
    private final StationStaffAssignmentRepository staffAssignmentRepository;

    @Override
    public GlobalSearchType type() {
        return GlobalSearchType.CHARGER;
    }

    @Override
    public boolean supports(SearchRole role) {
        return role == SearchRole.OWNER || role == SearchRole.STAFF;
    }

    @Override
    public List<SearchHit> search(SearchContext context, String query, int limit) {
        String pattern = SearchLike.pattern(query);
        Specification<ChargePoint> spec = (root, criteriaQuery, cb) -> {
            var station = root.join("station");
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.or(
                    cb.like(cb.lower(root.get("chargePointCode")), pattern, '\\'),
                    cb.like(cb.lower(root.get("name")), pattern, '\\')
            ));
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (context.role() == SearchRole.OWNER) {
                predicates.add(cb.equal(station.get("owner").get("id"), context.actorId()));
            } else {
                Set<UUID> stationIds = activeStaffStationIds(context.actorId());
                // Assignment rỗng → không thấy gì (không rơi về "toàn cục").
                predicates.add(stationIds.isEmpty()
                        ? cb.disjunction()
                        : station.get("id").in(stationIds));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        PageRequest page = PageRequest.of(0, limit, Sort.by(Sort.Order.asc("chargePointCode")));
        return chargePointRepository.findAll(spec, page).getContent().stream()
                .map(this::toHit)
                .collect(Collectors.toList());
    }

    private Set<UUID> activeStaffStationIds(UUID actorId) {
        return staffAssignmentRepository.findAllByStaff_IdAndStatus(actorId, StaffAssignmentStatus.ACTIVE)
                .stream()
                .map(assignment -> assignment.getStation().getId())
                .collect(Collectors.toSet());
    }

    private SearchHit toHit(ChargePoint chargePoint) {
        return new SearchHit(
                chargePoint.getId(),
                chargePoint.getName() != null && !chargePoint.getName().isBlank()
                        ? chargePoint.getName()
                        : chargePoint.getChargePointCode(),
                chargePoint.getStation() != null ? chargePoint.getStation().getName() : null,
                chargePoint.getChargePointCode()
        );
    }
}
