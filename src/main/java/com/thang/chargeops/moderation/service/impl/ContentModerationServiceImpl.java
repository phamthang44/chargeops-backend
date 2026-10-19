package com.thang.chargeops.moderation.service.impl;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ModerationErrorCode;
import com.thang.chargeops.moderation.dto.ModerationCheckResponse;
import com.thang.chargeops.moderation.model.*;
import com.thang.chargeops.moderation.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ContentModerationServiceImpl implements ContentModerationService {
    private final ModerationRuleCache cache;

    @Override
    public void validate(List<Field> fields) {
        var rules = cache.rules();
        List<String> rejected = fields.stream().filter(f -> {
            String normalized = ContentNormalizer.normalize(f.text());
            return rules.stream().anyMatch(rule -> rule.matches(f.scope(), normalized));
        }).map(Field::name).distinct().toList();
        if (!rejected.isEmpty()) throw AppException.withDetails(ModerationErrorCode.CONTENT_PROHIBITED,
                Map.of("fields", rejected));
    }

    @Override
    public String safeImportedDisplayName(String name) {
        return check(ContentScope.DISPLAY_NAME, name).allowed() ? name : "Người dùng";
    }

    @Override
    public ModerationCheckResponse check(ContentScope scope, String text) {
        String normalized = ContentNormalizer.normalize(text);
        var matches = cache.rules().stream().filter(rule -> rule.matches(scope, normalized))
                .map(rule -> new ModerationCheckResponse.Match(rule.id(), rule.term(), rule.matchType())).toList();
        return new ModerationCheckResponse(matches.isEmpty(), matches);
    }
}
