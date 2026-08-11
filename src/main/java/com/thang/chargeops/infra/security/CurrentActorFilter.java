package com.thang.chargeops.infra.security;

import com.thang.chargeops.infra.persistence.CurrentActorContext;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves the authenticated Keycloak subject to ChargeOps' internal profile
 * ID before controller transactions begin.
 */
public class CurrentActorFilter extends OncePerRequestFilter {

    private final UserProfileRepository userProfileRepository;
    private final CurrentActorContext currentActorContext;

    public CurrentActorFilter(
            UserProfileRepository userProfileRepository,
            CurrentActorContext currentActorContext
    ) {
        this.userProfileRepository = userProfileRepository;
        this.currentActorContext = currentActorContext;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            if (SecurityContextHolder.getContext().getAuthentication()
                    instanceof JwtAuthenticationToken authentication
                    && authentication.isAuthenticated()) {
                userProfileRepository.findIdByKeycloakId(authentication.getToken().getSubject())
                        .ifPresent(currentActorContext::set);
            }

            filterChain.doFilter(request, response);
        } finally {
            // Servlet threads are reused; always remove request-specific state.
            currentActorContext.clear();
        }
    }
}
