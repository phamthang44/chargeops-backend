package com.thang.chargeops.infra.security;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.common.constant.SecurityConstants;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import com.thang.chargeops.infra.persistence.CurrentActorContext;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Optional;

/**
 * Resolves the authenticated Keycloak subject to ChargeOps' internal profile
 * ID before controller transactions begin.
 *
 * <p>UM-02: doubles as the account-status gate. When a profile exists and is
 * SUSPENDED or soft-deleted, non-public requests are rejected with
 * {@code AUTH_006} before any controller runs. Public (method, path) pairs are
 * exempt per {@link SecurityConstants#isPublicRequest}; a missing profile
 * (first-login bootstrap) passes without actor context so {@code /me/profile}
 * can create it.
 */
public class CurrentActorFilter extends OncePerRequestFilter {

    private final UserProfileRepository userProfileRepository;
    private final CurrentActorContext currentActorContext;
    private final ObjectMapper objectMapper;

    public CurrentActorFilter(
            UserProfileRepository userProfileRepository,
            CurrentActorContext currentActorContext,
            ObjectMapper objectMapper
    ) {
        this.userProfileRepository = userProfileRepository;
        this.currentActorContext = currentActorContext;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken authentication
                && authentication.isAuthenticated()) {
            String subject = authentication.getToken().getSubject();
            Optional<UserProfile> profile = userProfileRepository.findByKeycloakId(subject);
            if (profile.isPresent()) {
                UserProfile user = profile.get();
                boolean blocked = user.getDeletedAt() != null || user.getStatus() == UserStatus.SUSPENDED;
                boolean isPublic = SecurityConstants.isPublicRequest(request.getMethod(), request.getRequestURI());
                if (blocked && !isPublic) {
                    SecurityErrorWriter.write(objectMapper, request, response, AuthErrorCode.ACCOUNT_SUSPENDED);
                    return;
                }
                currentActorContext.set(user.getId());
            }
            // Profile chưa tồn tại (bootstrap lần đầu): cho qua không actor context.
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Servlet threads are reused; always remove request-specific state.
            currentActorContext.clear();
        }
    }
}
