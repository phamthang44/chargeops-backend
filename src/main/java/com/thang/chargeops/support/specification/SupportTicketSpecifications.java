package com.thang.chargeops.support.specification;

import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.model.TicketStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class SupportTicketSpecifications {

    private SupportTicketSpecifications() {
    }

    public static Specification<SupportTicket> forActor(
            UserProfile profile,
            Set<String> roles,
            Set<UUID> ownedStationIds,
            Set<UUID> activeStaffStationIds,
            TicketStatus status,
            UUID targetStationId
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (roles == null || !roles.contains("ROLE_ADMIN")) {
                List<Predicate> roleOrPredicates = new ArrayList<>();

                // Always view tickets created by the user
                roleOrPredicates.add(cb.equal(root.get("reporter").get("id"), profile.getId()));

                // View tickets assigned directly to the user
                roleOrPredicates.add(cb.equal(root.get("assignedHandler").get("id"), profile.getId()));

                // Station Owner: view tickets of owned stations
                if (ownedStationIds != null && !ownedStationIds.isEmpty()) {
                    roleOrPredicates.add(root.get("station").get("id").in(ownedStationIds));
                }

                // Active Station Staff: view tickets of active assigned stations
                if (activeStaffStationIds != null && !activeStaffStationIds.isEmpty()) {
                    roleOrPredicates.add(root.get("station").get("id").in(activeStaffStationIds));
                }

                predicates.add(cb.or(roleOrPredicates.toArray(new Predicate[0])));
            }

            if (targetStationId != null) {
                predicates.add(cb.equal(root.get("station").get("id"), targetStationId));
            }

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
