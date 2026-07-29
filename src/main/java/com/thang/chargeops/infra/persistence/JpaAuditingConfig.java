package com.thang.chargeops.infra.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables Spring Data JPA auditing.
 *
 * <p>{@code @CreatedBy}/{@code @LastModifiedBy} fields (on {@code BaseAuditEntity})
 * are populated from {@link #auditorProvider()}, which reads the current user from
 * the Keycloak JWT. ({@code createdAt}/{@code updatedAt} are filled by the entity's
 * own {@code @PrePersist}/{@code @PreUpdate} hooks, so they don't depend on this.)
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<String> auditorProvider() {
        return new AuditorAwareImpl();
    }
}
