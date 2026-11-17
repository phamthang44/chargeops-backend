package com.thang.chargeops.infra.mcp;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.infra.persistence.CurrentActorContext;
import com.thang.chargeops.infra.security.JwtRoleConverter;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.abuse.AbuseRateLimiter;
import com.thang.chargeops.infra.security.abuse.ClientIpResolver;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(McpSecurityConfigurationTest.McpEndpointProbe.class)
@Import({McpSecurityConfiguration.class, McpProperties.class, McpTokenRequirements.class,
        JwtRoleConverter.class, RestAccessDeniedHandler.class, McpSecurityConfigurationTest.McpEndpointProbe.class})
@TestPropertySource(properties = {
        "spring.ai.mcp.server.enabled=true",
        "app.mcp.resource-url=https://api.chargeops.online/mcp",
        "app.mcp.authorization-server-url=https://auth.chargeops.online/realms/chargeops",
        "app.mcp.read-scope=chargeops:mcp:read"
})
class McpSecurityConfigurationTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    UserProfileRepository userProfileRepository;

    @MockitoBean
    CurrentActorContext currentActorContext;

    @MockitoBean
    AbuseRateLimiter limiter;

    @MockitoBean
    ClientIpResolver ipResolver;

    @BeforeEach
    void setUpRateLimit() {
        when(ipResolver.resolve(any())).thenReturn("127.0.0.1");
        when(limiter.acquire(any(), anyString(), anyString())).thenReturn(AbuseRateLimiter.Decision.allow());
    }

    @Test
    void unauthenticatedRequestsReceiveProtectedResourceChallenge() throws Exception {
        mvc.perform(post("/mcp").header("Host", "api.chargeops.online"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        "Bearer resource_metadata=\"https://api.chargeops.online/.well-known/oauth-protected-resource/mcp\", scope=\"chargeops:mcp:read\""))
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(userProfileRepository, never()).findByKeycloakId(anyString());
    }

    @Test
    void wrongHostOrBrowserOriginIsRejectedBeforeTokenProcessing() throws Exception {
        mvc.perform(post("/mcp").header("Host", "attacker.example"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/mcp")
                        .header("Host", "api.chargeops.online")
                        .header("Origin", "https://attacker.example"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(jwtDecoder, userProfileRepository);
    }

    @Test
    void ownerWithCorrectAudienceAndScopeCanReachProtectedEndpoint() throws Exception {
        UUID profileId = UUID.randomUUID();
        UserProfile profile = UserProfile.builder()
                .keycloakId("owner-subject")
                .email("owner@example.test")
                .status(UserStatus.ACTIVE)
                .build();
        profile.setId(profileId);
        when(jwtDecoder.decode("owner-token")).thenReturn(jwt("owner-subject", "OWNER",
                "https://api.chargeops.online/mcp", "chargeops:mcp:read"));
        when(userProfileRepository.findByKeycloakId("owner-subject")).thenReturn(Optional.of(profile));
        mvc.perform(post("/mcp")
                        .header("Host", "api.chargeops.online")
                        .header("Origin", "https://api.chargeops.online")
                        .header("Authorization", "Bearer owner-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"ok\":true}"));

        verify(currentActorContext).set(profileId);
        verify(limiter).acquire(any(), org.mockito.ArgumentMatchers.eq("owner-subject"), anyString());
    }

    @Test
    void nonOwnerCannotInvokeEndpointEvenWithMcpScope() throws Exception {
        when(jwtDecoder.decode("staff-token")).thenReturn(jwt("staff-subject", "STAFF",
                "https://api.chargeops.online/mcp", "chargeops:mcp:read"));
        when(userProfileRepository.findByKeycloakId("staff-subject")).thenReturn(Optional.empty());

        mvc.perform(post("/mcp")
                        .header("Host", "api.chargeops.online")
                        .header("Authorization", "Bearer staff-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void eachOwnerSubjectResolvesToItsOwnChargeOpsProfile() throws Exception {
        UUID firstProfileId = UUID.randomUUID();
        UUID secondProfileId = UUID.randomUUID();
        UserProfile firstProfile = profile("first-owner");
        UserProfile secondProfile = profile("second-owner");
        firstProfile.setId(firstProfileId);
        secondProfile.setId(secondProfileId);
        when(jwtDecoder.decode("first-owner-token")).thenReturn(jwt("first-owner", "OWNER",
                "https://api.chargeops.online/mcp", "chargeops:mcp:read"));
        when(jwtDecoder.decode("second-owner-token")).thenReturn(jwt("second-owner", "OWNER",
                "https://api.chargeops.online/mcp", "chargeops:mcp:read"));
        when(userProfileRepository.findByKeycloakId("first-owner")).thenReturn(Optional.of(firstProfile));
        when(userProfileRepository.findByKeycloakId("second-owner")).thenReturn(Optional.of(secondProfile));

        for (String token : List.of("first-owner-token", "second-owner-token")) {
            mvc.perform(post("/mcp")
                            .header("Host", "api.chargeops.online")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }

        verify(currentActorContext).set(firstProfileId);
        verify(currentActorContext).set(secondProfileId);
    }

    @Test
    void wrongAudienceOrScopeIsRejectedBeforeProfileLookup() throws Exception {
        when(jwtDecoder.decode("wrong-audience")).thenReturn(jwt("owner-subject", "OWNER",
                "chargeops-console", "chargeops:mcp:read"));
        when(jwtDecoder.decode("wrong-scope")).thenReturn(jwt("owner-subject", "OWNER",
                "https://api.chargeops.online/mcp", "profile"));

        for (String token : List.of("wrong-audience", "wrong-scope")) {
            mvc.perform(post("/mcp")
                            .header("Host", "api.chargeops.online")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }

        verify(userProfileRepository, never()).findByKeycloakId(anyString());
    }

    private static Jwt jwt(String subject, String role, String audience, String scope) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .issuer("https://auth.chargeops.online/realms/chargeops")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .audience(List.of(audience))
                .claim("scope", scope)
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
    }

    private static UserProfile profile(String subject) {
        return UserProfile.builder()
                .keycloakId(subject)
                .email(subject + "@example.test")
                .status(UserStatus.ACTIVE)
                .build();
    }

    @RestController
    static class McpEndpointProbe {

        @PostMapping("/mcp")
        Map<String, Boolean> post() {
            return Map.of("ok", true);
        }
    }
}
