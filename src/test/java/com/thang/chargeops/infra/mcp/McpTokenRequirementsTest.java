package com.thang.chargeops.infra.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpTokenRequirementsTest {

    private static final String RESOURCE = "https://api.chargeops.online/mcp";
    private final McpProperties properties = properties();
    private final McpTokenRequirements requirements = new McpTokenRequirements(properties);

    @Test
    void acceptsOnlyMcpAudienceAndReadScope() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("owner-subject")
                .audience(List.of(RESOURCE))
                .claim("scope", "openid chargeops:mcp:read")
                .build();

        assertDoesNotThrow(() -> requirements.validate(jwt));
    }

    @Test
    void rejectsWrongAudience() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("owner-subject")
                .audience(List.of("chargeops-console"))
                .claim("scope", "chargeops:mcp:read")
                .build();

        assertThrows(BadJwtException.class, () -> requirements.validate(jwt));
    }

    @Test
    void rejectsMissingReadScope() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("owner-subject")
                .audience(List.of(RESOURCE))
                .claim("scope", "openid profile")
                .build();

        assertThrows(BadJwtException.class, () -> requirements.validate(jwt));
    }

    private static McpProperties properties() {
        McpProperties properties = new McpProperties();
        properties.setResourceUrl(RESOURCE);
        properties.setReadScope("chargeops:mcp:read");
        return properties;
    }
}
