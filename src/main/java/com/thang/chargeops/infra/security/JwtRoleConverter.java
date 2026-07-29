package com.thang.chargeops.infra.security;

import com.thang.chargeops.common.constant.SecurityConstants;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Extracts Keycloak realm roles from a JWT and maps them to Spring Security
 * authorities. Keycloak places realm roles under the {@code realm_access.roles}
 * claim; each role is upper-cased and prefixed with {@code ROLE_} so it works
 * with {@code hasRole(...)} and {@code @PreAuthorize("hasRole('OWNER')")}.
 */
@Component
public class JwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS = "realm_access";
    private static final String ROLES = "roles";

    @Override
    @SuppressWarnings("unchecked")
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim(REALM_ACCESS);
        if (realmAccess == null || !(realmAccess.get(ROLES) instanceof Collection<?> roles)) {
            return List.of();
        }
        return ((Collection<String>) roles).stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(SecurityConstants.ROLE_PREFIX + role.toUpperCase()))
                .toList();
    }
}
