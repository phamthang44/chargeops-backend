package com.thang.chargeops.infra.security;

import com.thang.chargeops.profile.model.PrimaryRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtClaimExtractorTest {
    private final JwtClaimExtractor extractor = new JwtClaimExtractor();

    @Test
    void resolvesOnePlatformRoleAndIgnoresKeycloakInternalRoles() {
        assertThat(extractor.resolvePrimaryRole(jwt("ADMIN", "offline_access")))
                .isEqualTo(PrimaryRole.ADMIN);
    }

    @Test
    void marksMissingAndMultiplePlatformRolesExplicitly() {
        assertThat(extractor.resolvePrimaryRole(jwt("offline_access")))
                .isEqualTo(PrimaryRole.UNKNOWN);
        assertThat(extractor.resolvePrimaryRole(jwt("OWNER", "DRIVER")))
                .isEqualTo(PrimaryRole.CONFLICT);
        assertThat(extractor.resolvePrimaryRole(jwt("ADMIN", "OWNER", "DRIVER")))
                .isEqualTo(PrimaryRole.ADMIN);
        assertThat(extractor.resolvePrimaryRole(jwt("OWNER", "STAFF", "DRIVER")))
                .isEqualTo(PrimaryRole.OWNER);
        assertThat(extractor.resolvePrimaryRole(jwt("STAFF", "DRIVER")))
                .isEqualTo(PrimaryRole.STAFF);
    }

    private Jwt jwt(String... roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject")
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
    }
}
