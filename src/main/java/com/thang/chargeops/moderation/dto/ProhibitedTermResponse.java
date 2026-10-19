package com.thang.chargeops.moderation.dto;

import com.thang.chargeops.moderation.model.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record ProhibitedTermResponse(UUID id, String term, String normalizedTerm, TermMatchType matchType,
        Set<ContentScope> scopes, boolean enabled, String note, long version,
        Instant createdAt, Instant updatedAt, UUID createdBy, UUID updatedBy) { }
