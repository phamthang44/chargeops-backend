package com.thang.chargeops.legal.dto;

import com.thang.chargeops.legal.domain.LegalDocType;
import com.thang.chargeops.legal.domain.TargetAudience;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.time.Instant;

@Builder
public record CreateLegalDocumentRequest(
        @NotBlank(message = "Slug cannot be blank")
        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Slug must be lowercase alphanumeric with hyphens")
        String slug,

        @NotNull(message = "Document type cannot be null")
        LegalDocType docType,

        TargetAudience targetAudience,

        @NotBlank(message = "Title cannot be blank")
        String title,

        String eyebrow,

        String summary,

        @NotBlank(message = "Content cannot be blank")
        String content,

        @NotBlank(message = "Version cannot be blank")
        String version,

        String locale,

        Boolean active,

        Instant effectiveFrom,

        java.util.List<String> keywords
) {
    public TargetAudience resolveAudience() {
        return targetAudience != null ? targetAudience : TargetAudience.ALL;
    }

    public String resolveLocale() {
        return locale != null && !locale.trim().isEmpty() ? locale.trim() : "vi";
    }

    public boolean resolveActive() {
        return active != null ? active : true;
    }

    public Instant resolveEffectiveFrom() {
        return effectiveFrom != null ? effectiveFrom : Instant.now();
    }
}
