package com.thang.chargeops.infra.persistence;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Objects;
import java.util.Optional;


/**
 * Supplies the current user id for JPA auditing ({@code @CreatedBy} /
 * {@code @LastModifiedBy} on {@code BaseAuditEntity}).
 *
 * <p>As a Keycloak resource server, the authenticated principal is a {@link Jwt},
 * so we read the user from the token ({@code preferred_username}, falling back to
 * the subject {@code sub}). Outside a request — scheduled jobs, async tasks,
 * startup data — there is no authentication, so we record {@code "system"}.
 */
public class AuditorAwareImpl implements AuditorAware<String> {

    private static final String SYSTEM = "system";

    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()
            ||  authentication instanceof AnonymousAuthenticationToken) {
            return Optional.of(SYSTEM);
        }

        if (authentication.getPrincipal() instanceof Jwt jwt) {
            String username = jwt.getClaim("preferred_username");
            return Optional.of(username != null ? username : Objects.requireNonNull(jwt.getSubject()));
        }
        return Optional.of(authentication.getName());
    }
}
