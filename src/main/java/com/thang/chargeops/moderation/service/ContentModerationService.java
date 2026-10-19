package com.thang.chargeops.moderation.service;

import com.thang.chargeops.moderation.dto.ModerationCheckResponse;
import com.thang.chargeops.moderation.model.ContentScope;
import java.util.List;

public interface ContentModerationService {
    record Field(String name, ContentScope scope, String text) { }
    void validate(List<Field> fields);
    default void validate(String field, ContentScope scope, String text) {
        validate(List.of(new Field(field, scope, text)));
    }
    String safeImportedDisplayName(String name);
    ModerationCheckResponse check(ContentScope scope, String text);
}
