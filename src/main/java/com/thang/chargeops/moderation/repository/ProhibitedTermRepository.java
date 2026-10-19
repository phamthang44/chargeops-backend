package com.thang.chargeops.moderation.repository;

import com.thang.chargeops.moderation.entity.ProhibitedTerm;
import com.thang.chargeops.moderation.model.TermMatchType;
import org.springframework.data.jpa.repository.*;
import java.util.List;
import java.util.UUID;

public interface ProhibitedTermRepository extends JpaRepository<ProhibitedTerm, UUID>, JpaSpecificationExecutor<ProhibitedTerm> {
    @EntityGraph(attributePaths = "scopes")
    List<ProhibitedTerm> findByEnabledTrue();
    boolean existsByNormalizedTermAndMatchTypeAndIdNot(String normalizedTerm, TermMatchType matchType, UUID id);
}
