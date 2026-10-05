package com.thang.chargeops.infra.identity.keycloak;

import com.thang.chargeops.exception.errorcode.StaffInvitationErrorCode;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.station.staff.service.StaffIdentityProvisioner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Provisions only identities tagged with this ChargeOps invitation. Never adopts another account. */
@Service
public class KeycloakStaffIdentityProvisioner implements StaffIdentityProvisioner {
    private final KeycloakAdminProperties properties;
    private final RestClient client;
    private String cachedToken;
    private Instant tokenExpiresAt = Instant.EPOCH;

    @Autowired
    public KeycloakStaffIdentityProvisioner(KeycloakAdminProperties properties) {
        this(properties, RestClient.create(properties.getBaseUrl()));
    }

    KeycloakStaffIdentityProvisioner(KeycloakAdminProperties properties, RestClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public String ensureStaffIdentity(UUID invitationId, String email) {
        try {
            KeycloakUser existing = findExact(email);
            if (existing == null) {
                try {
                    client.post().uri("/admin/realms/{realm}/users", properties.getRealm())
                            .headers(headers -> headers.setBearerAuth(token()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of(
                                    "username", email,
                                    "email", email,
                                    "enabled", true,
                                    "emailVerified", false,
                                    "requiredActions", List.of("VERIFY_EMAIL", "UPDATE_PASSWORD"),
                                    "attributes", Map.of("chargeops_staff_invitation_id", List.of(invitationId.toString()))
                            )).retrieve().toBodilessEntity();
                } catch (RestClientResponseException conflict) {
                    if (conflict.getStatusCode().value() != 409) throw conflict;
                }
                existing = findExact(email);
            }
            if (existing == null || existing.id() == null || !email.equalsIgnoreCase(existing.email())
                    || !List.of(invitationId.toString()).equals(
                            existing.attributes() == null ? null : existing.attributes().get("chargeops_staff_invitation_id"))) {
                throw new AppException(StaffInvitationErrorCode.EMAIL_IN_USE);
            }

            KeycloakRole staffRole = client.get().uri("/admin/realms/{realm}/roles/STAFF", properties.getRealm())
                    .headers(headers -> headers.setBearerAuth(token()))
                    .retrieve().body(KeycloakRole.class);
            if (staffRole == null || staffRole.id() == null || !"STAFF".equals(staffRole.name())) {
                throw new AppException(StaffInvitationErrorCode.IDENTITY_SETUP_FAILED);
            }
            removeInheritedDriverDefault(existing.id());
            client.post().uri("/admin/realms/{realm}/users/{userId}/role-mappings/realm",
                            properties.getRealm(), existing.id())
                    .headers(headers -> headers.setBearerAuth(token()))
                    .contentType(MediaType.APPLICATION_JSON).body(List.of(staffRole))
                    .retrieve().toBodilessEntity();
            KeycloakRole[] effectiveRoles = client.get()
                    .uri("/admin/realms/{realm}/users/{userId}/role-mappings/realm/composite",
                            properties.getRealm(), existing.id())
                    .headers(headers -> headers.setBearerAuth(token()))
                    .retrieve().body(KeycloakRole[].class);
            if (effectiveRoles == null || Arrays.stream(effectiveRoles).filter(Objects::nonNull)
                    .map(KeycloakRole::name).noneMatch("STAFF"::equals)
                    || Arrays.stream(effectiveRoles).filter(Objects::nonNull)
                    .map(KeycloakRole::name).anyMatch(role -> "DRIVER".equalsIgnoreCase(role)
                            || "OWNER".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role))) {
                throw new AppException(StaffInvitationErrorCode.IDENTITY_SETUP_FAILED);
            }
            return existing.id();
        } catch (AppException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw translate(exception);
        }
    }

    @Override
    public void sendActivationEmail(String identityUserId) {
        try {
            client.put().uri("/admin/realms/{realm}/users/{userId}/execute-actions-email",
                            properties.getRealm(), identityUserId)
                    .headers(headers -> headers.setBearerAuth(token()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of("VERIFY_EMAIL", "UPDATE_PASSWORD"))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException exception) {
            throw translate(exception);
        }
    }

    private KeycloakUser findExact(String email) {
        KeycloakUser[] users = client.get()
                .uri("/admin/realms/{realm}/users?email={email}&exact=true", properties.getRealm(), email)
                .headers(headers -> headers.setBearerAuth(token()))
                .retrieve().body(KeycloakUser[].class);
        if (users == null || users.length == 0) return null;
        if (users.length != 1) throw new AppException(StaffInvitationErrorCode.EMAIL_IN_USE);
        return users[0];
    }

    private void removeInheritedDriverDefault(String identityUserId) {
        KeycloakRole[] directRoles = client.get()
                .uri("/admin/realms/{realm}/users/{userId}/role-mappings/realm",
                        properties.getRealm(), identityUserId)
                .headers(headers -> headers.setBearerAuth(token()))
                .retrieve().body(KeycloakRole[].class);
        if (directRoles == null) throw new AppException(StaffInvitationErrorCode.IDENTITY_SETUP_FAILED);
        String defaultRoleName = "default-roles-" + properties.getRealm();
        KeycloakRole defaultRole = Arrays.stream(directRoles).filter(Objects::nonNull)
                .filter(role -> defaultRoleName.equals(role.name())).findFirst().orElse(null);
        if (defaultRole == null) return;
        if (defaultRole.id() == null) throw new AppException(StaffInvitationErrorCode.IDENTITY_SETUP_FAILED);
        client.method(HttpMethod.DELETE)
                .uri("/admin/realms/{realm}/users/{userId}/role-mappings/realm",
                        properties.getRealm(), identityUserId)
                .headers(headers -> headers.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON).body(List.of(defaultRole))
                .retrieve().toBodilessEntity();
    }

    private synchronized String token() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) return cachedToken;
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        TokenResponse response = client.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", properties.getRealm())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(TokenResponse.class);
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new AppException(StaffInvitationErrorCode.IDENTITY_UNAVAILABLE);
        }
        cachedToken = response.accessToken();
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(1, response.expiresIn() - 30));
        return cachedToken;
    }

    private AppException translate(RestClientException exception) {
        if (exception instanceof RestClientResponseException response && response.getStatusCode().is4xxClientError()) {
            return new AppException(StaffInvitationErrorCode.IDENTITY_SETUP_FAILED);
        }
        return new AppException(StaffInvitationErrorCode.IDENTITY_UNAVAILABLE);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(@JsonProperty("access_token") String accessToken,
                                 @JsonProperty("expires_in") long expiresIn) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KeycloakUser(String id, String email, Map<String, List<String>> attributes) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KeycloakRole(String id, String name) { }
}
