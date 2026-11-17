package com.thang.chargeops.infra.mcp;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;

@Component
public class McpTokenRequirements {

    private final McpProperties properties;

    public McpTokenRequirements(McpProperties properties) {
        this.properties = properties;
    }

    public Jwt validate(Jwt jwt) {
        if (jwt == null || jwt.getAudience() == null
                || !jwt.getAudience().contains(properties.getResourceUrl())) {
            throw new BadJwtException("MCP token audience is invalid");
        }
        if (!hasRequiredScope(jwt)) {
            throw new BadJwtException("MCP token scope is insufficient");
        }
        return jwt;
    }

    private boolean hasRequiredScope(Jwt jwt) {
        Object scopeClaim = jwt.getClaims().get("scope");
        if (scopeClaim instanceof String scopes && StringUtils.hasText(scopes)
                && List.of(scopes.trim().split("\\s+")).contains(properties.getReadScope())) {
            return true;
        }
        Object scpClaim = jwt.getClaims().get("scp");
        return scpClaim instanceof Collection<?> scopes && scopes.stream()
                .anyMatch(scope -> properties.getReadScope().equals(scope));
    }
}
