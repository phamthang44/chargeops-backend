package com.thang.chargeops.infra.security;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.thang.chargeops.common.enums.Role;
import com.thang.chargeops.profile.model.PrimaryRole;


@Component
public class JwtClaimExtractor {

    private static final String EMAIL_CLAIM = "email";
    private static final String NAME_CLAIM = "name";
    private static final String PREFERRED_USERNAME_CLAIM = "preferred_username";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^(.+)@(\\S+)$");

    public String requireSubject(Jwt jwt) {
        String keycloakId = jwt.getSubject();
        if (!hasText(keycloakId)) {
            throw new AppException(AuthErrorCode.TOKEN_INVALID);
        }
        return keycloakId;
    }


    public String requireEmail(Jwt jwt) {
        String email = jwt.getClaimAsString(EMAIL_CLAIM);
        if (isEmail(email)) {
            return email;
        }

        String preferredUsername = jwt.getClaimAsString(PREFERRED_USERNAME_CLAIM);
        if (isEmail(preferredUsername)) {
            return preferredUsername;
        }
        throw new AppException(AuthErrorCode.EMAIL_CLAIM_MISSING);
    }

    public String getDisplayName(Jwt jwt) {
        String name = jwt.getClaimAsString(NAME_CLAIM);
        if (hasText(name)) {
            return name;
        }

        String preferredUsername = jwt.getClaimAsString(PREFERRED_USERNAME_CLAIM);
        return hasText(preferredUsername) && !isEmail(preferredUsername)
                ? preferredUsername
                : null;
    }

    public PrimaryRole resolvePrimaryRole(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return PrimaryRole.UNKNOWN;
        }
        Set<Role> platformRoles = roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::toUpperCase)
                .map(value -> {
                    try { return Role.valueOf(value); }
                    catch (IllegalArgumentException ignored) { return null; }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (platformRoles.isEmpty()) return PrimaryRole.UNKNOWN;
        if (platformRoles.size() > 1) {
            if (platformRoles.contains(Role.ADMIN)) {
                return PrimaryRole.ADMIN;
            }
            if (platformRoles.contains(Role.OWNER)) {
                return PrimaryRole.OWNER;
            }
            if (platformRoles.contains(Role.STAFF)) {
                return PrimaryRole.STAFF;
            }
            return PrimaryRole.CONFLICT;
        }
        return PrimaryRole.valueOf(platformRoles.iterator().next().name());
    }

    private boolean isEmail(String value) {
        return hasText(value) && EMAIL_PATTERN.matcher(value).matches();
    }
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
