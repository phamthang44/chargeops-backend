package com.thang.chargeops.infra.identity.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.thang.chargeops.common.enums.Role;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationStaffErrorCode;
import com.thang.chargeops.infra.identity.IdentityRoleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KeycloakIdentityRoleService implements IdentityRoleService {

    private static final long TOKEN_EXPIRY_SKEW_SECONDS = 30;

    private final KeycloakAdminProperties properties;
    private final RestClient restClient;
    private final AtomicReference<CachedAccessToken> cachedAccessToken = new AtomicReference<>();
    private final Object tokenRefreshLock = new Object();

    public KeycloakIdentityRoleService(KeycloakAdminProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create(properties.getBaseUrl());
    }

    @Override
    public Set<Role> getRoles(String identityUserId) {
        requireIdentityUserId(identityUserId);

        try {
            KeycloakRoleRepresentation[] representations = restClient.get()
                    .uri(
                            "/admin/realms/{realm}/users/{userId}/role-mappings/realm/composite",
                            properties.getRealm(),
                            identityUserId
                    )
                    .headers(headers -> headers.setBearerAuth(accessToken()))
                    .retrieve()
                    .body(KeycloakRoleRepresentation[].class);

            if (representations == null) {
                return Set.of();
            }

            return Arrays.stream(representations)
                    .map(KeycloakRoleRepresentation::name)
                    .filter(Objects::nonNull)
                    .map(this::toPlatformRole)
                    .flatMap(Optional::stream)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RestClientException exception) {
            throw translateException("read realm roles", exception);
        }
    }

    private Optional<Role> toPlatformRole(String keycloakRole) {
        try {
            return Optional.of(Role.valueOf(keycloakRole.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            // Keycloak có nhiều role nội bộ (offline_access, uma_authorization...).
            // Chúng không thuộc domain ChargeOps nên cố ý bỏ qua.
            return Optional.empty();
        }
    }

    private String accessToken() {
        CachedAccessToken current = cachedAccessToken.get();
        if (current != null && current.isUsable()) {
            return current.value();
        }

        synchronized (tokenRefreshLock) {
            current = cachedAccessToken.get();
            if (current != null && current.isUsable()) {
                return current.value();
            }

            TokenResponse response = requestAccessToken();
            long usableSeconds = Math.max(1, response.expiresIn() - TOKEN_EXPIRY_SKEW_SECONDS);
            CachedAccessToken refreshedToken = new CachedAccessToken(
                    response.accessToken(),
                    Instant.now().plusSeconds(usableSeconds)
            );
            cachedAccessToken.set(refreshedToken);
            return refreshedToken.value();
        }
    }

    private TokenResponse requestAccessToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());

        try {
            TokenResponse response = restClient.post()
                    .uri(
                            "/realms/{realm}/protocol/openid-connect/token",
                            properties.getRealm()
                    )
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);

            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw new AppException(StationStaffErrorCode.IDENTITY_PROVIDER_UNAVAILABLE);
            }
            return response;
        } catch (RestClientException exception) {
            throw translateException("obtain admin access token", exception);
        }
    }

    private AppException translateException(String operation, RestClientException exception) {
        if (exception instanceof RestClientResponseException responseException
                && responseException.getStatusCode().is4xxClientError()) {
            log.warn(
                    "Keycloak rejected operation '{}': status={}",
                    operation,
                    responseException.getStatusCode().value()
            );
            return new AppException(StationStaffErrorCode.IDENTITY_ROLE_OPERATION_FAILED);
        }

        log.error("Keycloak operation '{}' failed", operation, exception);
        return new AppException(StationStaffErrorCode.IDENTITY_PROVIDER_UNAVAILABLE);
    }

    private void requireIdentityUserId(String identityUserId) {
        if (identityUserId == null || identityUserId.isBlank()) {
            throw new AppException(StationStaffErrorCode.IDENTITY_ROLE_OPERATION_FAILED);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KeycloakRoleRepresentation(
            String id,
            String name,
            Boolean composite,
            Boolean clientRole,
            String containerId
    ) {
    }

    private record CachedAccessToken(String value, Instant expiresAt) {
        private boolean isUsable() {
            return Instant.now().isBefore(expiresAt);
        }
    }
}
