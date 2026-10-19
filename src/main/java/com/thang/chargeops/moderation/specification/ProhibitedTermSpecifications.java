package com.thang.chargeops.moderation.specification;

import com.thang.chargeops.moderation.entity.ProhibitedTerm;
import com.thang.chargeops.moderation.model.*;
import org.springframework.data.jpa.domain.Specification;

public final class ProhibitedTermSpecifications {
    private ProhibitedTermSpecifications() { }
    public static Specification<ProhibitedTerm> filter(String q, ContentScope scope, Boolean enabled) {
        return (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (q != null && !q.isBlank()) {
                String value = ContentNormalizer.normalize(q).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                predicate = cb.and(predicate, cb.like(root.get("normalizedTerm"), "%" + value + "%", '\\'));
            }
            if (scope != null) predicate = cb.and(predicate, cb.isMember(scope, root.get("scopes")));
            if (enabled != null) predicate = cb.and(predicate, cb.equal(root.get("enabled"), enabled));
            return predicate;
        };
    }
}
