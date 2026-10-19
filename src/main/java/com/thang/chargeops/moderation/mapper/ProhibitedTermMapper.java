package com.thang.chargeops.moderation.mapper;

import com.thang.chargeops.moderation.dto.*;
import com.thang.chargeops.moderation.entity.*;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class ProhibitedTermMapper {
    public ProhibitedTermResponse toResponse(ProhibitedTerm term) {
        return new ProhibitedTermResponse(term.getId(), term.getTerm(), term.getNormalizedTerm(), term.getMatchType(),
                Set.copyOf(term.getScopes()), term.isEnabled(), term.getNote(), term.getVersion(), term.getCreatedAt(),
                term.getUpdatedAt(), term.getCreatedBy(), term.getUpdatedBy());
    }
    public TermChangeResponse toResponse(ProhibitedTermChange change) {
        return new TermChangeResponse(change.getId(), change.getTermId(), change.getAction(), change.getActorId(),
                change.getChangedAt(), change.getBeforeSnapshot(), change.getAfterSnapshot());
    }
}
