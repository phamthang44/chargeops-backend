package com.thang.chargeops.legal.dto;

import com.thang.chargeops.legal.domain.TargetAudience;
import lombok.Builder;

import java.time.Instant;

@Builder
public record UpdateLegalDocumentRequest(
        String title,
        String eyebrow,
        String summary,
        String content,
        String version,
        TargetAudience targetAudience,
        Boolean active,
        Instant effectiveFrom,
        java.util.List<String> keywords
) {
}
