package com.thang.chargeops.moderation.entity;

import com.thang.chargeops.common.entity.BaseEntity;
import com.thang.chargeops.moderation.model.ContentScope;
import com.thang.chargeops.moderation.model.TermMatchType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "prohibited_terms")
@Getter
@Setter
public class ProhibitedTerm extends BaseEntity {
    @Column(nullable = false, length = 100) private String term;
    @Column(name = "normalized_term", nullable = false, length = 400) private String normalizedTerm;
    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 30) private TermMatchType matchType;
    @ElementCollection
    @CollectionTable(name = "prohibited_term_scopes", joinColumns = @JoinColumn(name = "term_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 30)
    private Set<ContentScope> scopes = new HashSet<>();
    @Column(nullable = false) private boolean enabled;
    @Column(length = 500) private String note;
    @Version @Column(nullable = false) private long version;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "created_by") private UUID createdBy;
    @Column(name = "updated_by") private UUID updatedBy;
}
