package com.thang.chargeops.moderation.service;

import com.thang.chargeops.moderation.model.ModerationRule;
import com.thang.chargeops.moderation.repository.ProhibitedTermRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ModerationRuleLoader {
    private final ProhibitedTermRepository repository;

    /** A separate transaction prevents caller snapshots/uncommitted writes leaking into the cache. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public List<ModerationRule> load() {
        return repository.findByEnabledTrue().stream().map(t -> new ModerationRule(t.getId(), t.getTerm(),
                t.getNormalizedTerm(), t.getMatchType(), t.getScopes())).toList();
    }
}
