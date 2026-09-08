package com.thang.chargeops.legal.dto;

import com.thang.chargeops.legal.domain.LegalDocType;
import com.thang.chargeops.legal.domain.LegalDocument;
import com.thang.chargeops.legal.domain.TargetAudience;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record LegalDocumentDetailResponse(
        UUID id,
        String slug,
        LegalDocType docType,
        TargetAudience targetAudience,
        String title,
        String eyebrow,
        String summary,
        String content,
        String version,
        String locale,
        java.util.List<String> keywords,
        boolean active,
        Instant effectiveFrom,
        Instant createdAt,
        Instant updatedAt
) {
    public static LegalDocumentDetailResponse fromEntity(LegalDocument doc) {
        return LegalDocumentDetailResponse.builder()
                .id(doc.getId())
                .slug(doc.getSlug())
                .docType(doc.getDocType())
                .targetAudience(doc.getTargetAudience())
                .title(doc.getTitle())
                .eyebrow(doc.getEyebrow())
                .summary(doc.getSummary())
                .content(doc.getContent())
                .version(doc.getVersion())
                .locale(doc.getLocale())
                .keywords(doc.getKeywords())
                .active(doc.isActive())
                .effectiveFrom(doc.getEffectiveFrom())
                .createdAt(doc.getCreatedAt())
                .updatedAt(doc.getUpdatedAt())
                .build();
    }
}
