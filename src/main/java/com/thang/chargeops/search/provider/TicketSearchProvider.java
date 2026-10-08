package com.thang.chargeops.search.provider;

import com.thang.chargeops.search.GlobalSearchType;
import com.thang.chargeops.search.SearchContext;
import com.thang.chargeops.search.SearchLike;
import com.thang.chargeops.search.SearchProvider;
import com.thang.chargeops.search.SearchRole;
import com.thang.chargeops.search.dto.SearchHit;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketEscalation;
import com.thang.chargeops.support.repository.SupportTicketRepository;
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
 * Scope ticket theo vai trò — cùng ranh giới với danh sách ticket hiện có:
 * <ul>
 *   <li>ADMIN: platform ticket ({@code station IS NULL}) + ticket có escalation
 *       <b>chưa resolved</b> (định nghĩa (b), khớp hàng đợi admin đang thấy —
 *       KHÔNG dùng nhánh "từng escalate bất kể đã resolved" của
 *       {@code SupportTicketSpecifications.forActor} vì hàng đợi không hiển thị cái đó);</li>
 *   <li>OWNER: reporter = mình HOẶC trạm thuộc về mình;</li>
 *   <li>STAFF: reporter = mình HOẶC trạm đang có assignment ACTIVE.</li>
 * </ul>
 * Endpoint ticket cũ không bị đụng tới — search đi route aggregate riêng.
 */
@Component
@RequiredArgsConstructor
public class TicketSearchProvider implements SearchProvider {

    private final SupportTicketRepository ticketRepository;
    private final StationRepository stationRepository;
    private final StationStaffAssignmentRepository staffAssignmentRepository;

    @Override
    public GlobalSearchType type() {
        return GlobalSearchType.TICKET;
    }

    @Override
    public boolean supports(SearchRole role) {
        return true;
    }

    @Override
    public List<SearchHit> search(SearchContext context, String query, int limit) {
        String pattern = SearchLike.pattern(query);
        UUID uuid = SearchLike.parseUuid(query);

        Specification<SupportTicket> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> text = new ArrayList<>();
            text.add(cb.like(cb.lower(root.get("subject")), pattern, '\\'));
            text.add(cb.like(cb.lower(root.get("ticketCode")), pattern, '\\'));
            if (uuid != null) {
                text.add(cb.equal(root.get("id"), uuid));
            }
            return cb.and(
                    cb.or(text.toArray(Predicate[]::new)),
                    scope(root, criteriaQuery, cb, context)
            );
        };

        PageRequest page = PageRequest.of(0, limit,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return ticketRepository.findAll(spec, page).getContent().stream()
                .map(this::toHit)
                .collect(Collectors.toList());
    }

    private Predicate scope(jakarta.persistence.criteria.Root<SupportTicket> root,
                            jakarta.persistence.criteria.CriteriaQuery<?> criteriaQuery,
                            jakarta.persistence.criteria.CriteriaBuilder cb,
                            SearchContext context) {
        return switch (context.role()) {
            case ADMIN -> {
                // Correlated subquery: an toàn cả với count query (không dựa vào result type).
                var escalation = criteriaQuery.subquery(UUID.class);
                var ticketEscalation = escalation.from(TicketEscalation.class);
                escalation.select(ticketEscalation.get("ticket").get("id"));
                escalation.where(
                        cb.equal(ticketEscalation.get("ticket"), root),
                        cb.isNull(ticketEscalation.get("resolvedAt"))
                );
                yield cb.or(cb.isNull(root.get("station")), cb.exists(escalation));
            }
            case OWNER -> ownOrStation(root, cb, context.actorId(),
                    ownedStationIds(context.actorId()));
            case STAFF -> ownOrStation(root, cb, context.actorId(),
                    activeStaffStationIds(context.actorId()));
        };
    }

    private Predicate ownOrStation(jakarta.persistence.criteria.Root<SupportTicket> root,
                                   jakarta.persistence.criteria.CriteriaBuilder cb,
                                   UUID actorId,
                                   Set<UUID> stationIds) {
        var ownTicket = cb.equal(root.get("reporter").get("id"), actorId);
        if (stationIds.isEmpty()) {
            return ownTicket;
        }
        return cb.or(ownTicket, root.get("station").get("id").in(stationIds));
    }

    private Set<UUID> ownedStationIds(UUID actorId) {
        return stationRepository.findAllByOwner_Id(actorId).stream()
                .map(Station::getId)
                .collect(Collectors.toSet());
    }

    private Set<UUID> activeStaffStationIds(UUID actorId) {
        return staffAssignmentRepository.findAllByStaff_IdAndStatus(actorId, StaffAssignmentStatus.ACTIVE)
                .stream()
                .map(assignment -> assignment.getStation().getId())
                .collect(Collectors.toSet());
    }

    private SearchHit toHit(SupportTicket ticket) {
        Station station = ticket.getStation();
        return new SearchHit(
                ticket.getId(),
                ticket.getSubject(),
                station != null ? station.getName() : null,
                ticket.getTicketCode()
        );
    }
}
