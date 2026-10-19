package com.thang.chargeops.moderation.model;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record ModerationRule(UUID id, String term, String normalizedTerm, TermMatchType matchType,
                             Set<ContentScope> scopes, Pattern pattern) {
    public ModerationRule(UUID id, String term, String normalizedTerm, TermMatchType matchType,
                          Set<ContentScope> scopes) {
        this(id, term, normalizedTerm, matchType, Set.copyOf(scopes),
                Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}])" + Pattern.quote(normalizedTerm)
                        + "(?![\\p{L}\\p{M}\\p{N}])"));
    }

    public boolean matches(ContentScope scope, String normalizedContent) {
        if (!scopes.contains(scope)) return false;
        return matchType == TermMatchType.EXACT ? normalizedTerm.equals(normalizedContent)
                : pattern.matcher(normalizedContent).find();
    }
}
