package com.thang.chargeops.infra.security;

import com.thang.chargeops.infra.persistence.CurrentActorContext;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentActorFilterTest {

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private CurrentActorContext currentActorContext;

    @Mock
    private FilterChain filterChain;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesInternalProfileIdAndClearsContextAfterRequest() throws Exception {
        UUID keycloakId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        authenticateAs(keycloakId);
        when(userProfileRepository.findIdByKeycloakId(keycloakId.toString()))
                .thenReturn(Optional.of(profileId));

        CurrentActorFilter filter =
                new CurrentActorFilter(userProfileRepository, currentActorContext);

        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                filterChain
        );

        InOrder order = inOrder(userProfileRepository, currentActorContext, filterChain);
        order.verify(userProfileRepository).findIdByKeycloakId(keycloakId.toString());
        order.verify(currentActorContext).set(profileId);
        order.verify(filterChain).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        order.verify(currentActorContext).clear();
    }

    private void authenticateAs(UUID subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject.toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }
}
