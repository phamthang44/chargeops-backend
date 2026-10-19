package com.thang.chargeops.moderation.entity;

import com.thang.chargeops.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "prohibited_term_changes")
@Immutable
@Getter
@Setter
public class ProhibitedTermChange extends BaseEntity {
    @Column(name = "term_id", nullable = false) private UUID termId;
    @Column(nullable = false, length = 20) private String action;
    @Column(name = "actor_id") private UUID actorId;
    @Column(name = "changed_at", nullable = false) private Instant changedAt;
    @Column(name = "before_snapshot", columnDefinition = "text") private String beforeSnapshot;
    @Column(name = "after_snapshot", nullable = false, columnDefinition = "text") private String afterSnapshot;
}
