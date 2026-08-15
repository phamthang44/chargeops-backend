package com.thang.chargeops.infra.security;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;


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

    private boolean isEmail(String value) {
        return hasText(value) && EMAIL_PATTERN.matcher(value).matches();
    }
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
