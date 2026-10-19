package com.thang.chargeops.moderation.dto;

import com.thang.chargeops.moderation.model.TermMatchType;
import java.util.List;
import java.util.UUID;

public record ModerationCheckResponse(boolean allowed, List<Match> matches) {
    public record Match(UUID id, String term, TermMatchType matchType) { }
}
