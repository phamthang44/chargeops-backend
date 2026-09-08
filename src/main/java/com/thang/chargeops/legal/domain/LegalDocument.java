package com.thang.chargeops.legal.domain;

import com.thang.chargeops.common.entity.SoftDeletableEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@SQLRestriction("deleted_at is null")
@SQLDelete(sql = "UPDATE legal_documents SET deleted_at = now() WHERE id = ?")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "legal_documents")
public class LegalDocument extends SoftDeletableEntity {

    @Column(name = "slug", nullable = false, unique = true, length = 100)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 50)
    private LegalDocType docType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_audience", nullable = false, length = 30)
    private TargetAudience targetAudience;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "eyebrow", length = 100)
    private String eyebrow;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "version", nullable = false, length = 30)
    @Builder.Default
    private String version = "1.0.0";

    @Column(name = "locale", nullable = false, length = 10)
    @Builder.Default
    private String locale = "vi";

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "keywords", columnDefinition = "text[]", nullable = false)
    @Builder.Default
    private List<String> keywords = new ArrayList<>();

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "effective_from", nullable = false)
    @Builder.Default
    private Instant effectiveFrom = Instant.now();

    public List<String> getKeywords() {
        return keywords != null ? keywords : new ArrayList<>();
    }
}
