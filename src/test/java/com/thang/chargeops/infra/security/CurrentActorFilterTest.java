package com.thang.chargeops.infra.security;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.infra.persistence.CurrentActorContext;
import com.thang.chargeops.profile.entity.UserProfile;
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
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentActorFilterTest {

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private CurrentActorContext currentActorContext;

    @Mock
    private FilterChain filterChain;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesInternalProfileIdAndClearsContextAfterRequest() throws Exception {
        UUID keycloakId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        authenticateAs(keycloakId);
        UserProfile profile = mock(UserProfile.class);
        when(profile.getId()).thenReturn(profileId);
        when(profile.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(profile.getDeletedAt()).thenReturn(null);
        when(userProfileRepository.findByKeycloakId(keycloakId.toString()))
                .thenReturn(Optional.of(profile));

        CurrentActorFilter filter =
                new CurrentActorFilter(userProfileRepository, currentActorContext, objectMapper);

        filter.doFilter(
                request("GET", "/api/v1/me/profile"),
                new MockHttpServletResponse(),
                filterChain
        );

        InOrder order = inOrder(userProfileRepository, currentActorContext, filterChain);
        order.verify(userProfileRepository).findByKeycloakId(keycloakId.toString());
        order.verify(currentActorContext).set(profileId);
        order.verify(filterChain).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        order.verify(currentActorContext).clear();
    }

    @Test
    void suspendedProfileIsRejectedWithAccountSuspendedOnNonPublicRequest() throws Exception {
        UUID keycloakId = UUID.randomUUID();
        authenticateAs(keycloakId);
        UserProfile profile = mock(UserProfile.class);
        when(profile.getStatus()).thenReturn(UserStatus.SUSPENDED);
        when(profile.getDeletedAt()).thenReturn(null);
        when(userProfileRepository.findByKeycloakId(keycloakId.toString()))
                .thenReturn(Optional.of(profile));

        CurrentActorFilter filter =
                new CurrentActorFilter(userProfileRepository, currentActorContext, objectMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("GET", "/api/v1/me/profile"), response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("AUTH_006");
        verify(filterChain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(currentActorContext, never()).set(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void suspendedProfileStillPassesPublicStationRead() throws Exception {
        UUID keycloakId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        authenticateAs(keycloakId);
        UserProfile profile = mock(UserProfile.class);
        when(profile.getId()).thenReturn(profileId);
        when(profile.getStatus()).thenReturn(UserStatus.SUSPENDED);
        when(profile.getDeletedAt()).thenReturn(null);
        when(userProfileRepository.findByKeycloakId(keycloakId.toString()))
                .thenReturn(Optional.of(profile));

        CurrentActorFilter filter =
                new CurrentActorFilter(userProfileRepository, currentActorContext, objectMapper);

        filter.doFilter(
                request("GET", "/api/v1/stations/" + UUID.randomUUID()),
                new MockHttpServletResponse(),
                filterChain
        );

        verify(filterChain).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(currentActorContext).set(profileId);
    }

    @Test
    void missingProfilePassesWithoutActorContext() throws Exception {
        UUID keycloakId = UUID.randomUUID();
        authenticateAs(keycloakId);
        when(userProfileRepository.findByKeycloakId(keycloakId.toString()))
                .thenReturn(Optional.empty());

        CurrentActorFilter filter =
                new CurrentActorFilter(userProfileRepository, currentActorContext, objectMapper);

        filter.doFilter(
                request("POST", "/api/v1/me/profile"),
                new MockHttpServletResponse(),
                filterChain
        );

        verify(filterChain).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(currentActorContext, never()).set(org.mockito.ArgumentMatchers.any());
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
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
