package com.thang.chargeops.infra.identity.keycloak;

import com.thang.chargeops.exception.errorcode.StaffInvitationErrorCode;

import com.thang.chargeops.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.http.HttpStatus.CREATED;

class KeycloakStaffIdentityProvisionerTest {
    @Test
    void createsOnlyTaggedStaffIdentityAndSendsRequiredActions() {
        UUID invitationId = UUID.randomUUID();
        KeycloakAdminProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KeycloakStaffIdentityProvisioner provisioner =
                new KeycloakStaffIdentityProvisioner(properties, builder.build());
        token(server);
        server.expect(once(), requestTo(containsString("/admin/realms/chargeops/users?")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost:8080/admin/realms/chargeops/users"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(CREATED));
        server.expect(once(), requestTo(containsString("/admin/realms/chargeops/users?")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":\"kc-staff\",\"email\":\"staff@example.com\","
                        + "\"attributes\":{\"chargeops_staff_invitation_id\":[\"" + invitationId + "\"]}}]",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost:8080/admin/realms/chargeops/roles/STAFF"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"staff-role\",\"name\":\"STAFF\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost:8080/admin/realms/chargeops/users/kc-staff/role-mappings/realm"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":\"default-role\",\"name\":\"default-roles-chargeops\"}]",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost:8080/admin/realms/chargeops/users/kc-staff/role-mappings/realm"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(content().json("[{\"id\":\"default-role\",\"name\":\"default-roles-chargeops\"}]"))
                .andRespond(withSuccess());
        server.expect(once(), requestTo("http://localhost:8080/admin/realms/chargeops/users/kc-staff/role-mappings/realm"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());
        server.expect(once(), requestTo("http://localhost:8080/admin/realms/chargeops/users/kc-staff/role-mappings/realm/composite"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":\"staff-role\",\"name\":\"STAFF\"}]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost:8080/admin/realms/chargeops/users/kc-staff/execute-actions-email"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());

        assertThat(provisioner.ensureStaffIdentity(invitationId, "staff@example.com")).isEqualTo("kc-staff");
        provisioner.sendActivationEmail("kc-staff");
        server.verify();
    }

    @Test
    void rejectsStaffWhenDriverRemainsEffectiveAfterRemovingDefaultRole() {
        UUID invitationId = UUID.randomUUID();
        KeycloakAdminProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KeycloakStaffIdentityProvisioner provisioner =
                new KeycloakStaffIdentityProvisioner(properties, builder.build());
        token(server);
        server.expect(once(), requestTo(containsString("/admin/realms/chargeops/users?")))
                .andRespond(withSuccess("[{\"id\":\"kc-staff\",\"email\":\"staff@example.com\","
                        + "\"attributes\":{\"chargeops_staff_invitation_id\":[\"" + invitationId + "\"]}}]",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost:8080/admin/realms/chargeops/roles/STAFF"))
                .andRespond(withSuccess("{\"id\":\"staff-role\",\"name\":\"STAFF\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost:8080/admin/realms/chargeops/users/kc-staff/role-mappings/realm"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost:8080/admin/realms/chargeops/users/kc-staff/role-mappings/realm"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess());
        server.expect(once(), requestTo("http://localhost:8080/admin/realms/chargeops/users/kc-staff/role-mappings/realm/composite"))
                .andRespond(withSuccess("[{\"name\":\"STAFF\"},{\"name\":\"DRIVER\"}]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provisioner.ensureStaffIdentity(invitationId, "staff@example.com"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(StaffInvitationErrorCode.IDENTITY_SETUP_FAILED);
        server.verify();
    }

    @Test
    void refusesToAdoptAnExistingUnrelatedAccount() {
        KeycloakAdminProperties properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KeycloakStaffIdentityProvisioner provisioner =
                new KeycloakStaffIdentityProvisioner(properties, builder.build());
        token(server);
        server.expect(once(), requestTo(containsString("/admin/realms/chargeops/users?")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":\"unrelated\",\"email\":\"staff@example.com\"}]",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provisioner.ensureStaffIdentity(UUID.randomUUID(), "staff@example.com"))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(StaffInvitationErrorCode.EMAIL_IN_USE);
        server.verify();
    }

    private static KeycloakAdminProperties properties() {
        KeycloakAdminProperties properties = new KeycloakAdminProperties();
        properties.setBaseUrl("http://localhost:8080");
        properties.setRealm("chargeops");
        properties.setClientId("chargeops-backend");
        properties.setClientSecret("test-secret");
        return properties;
    }

    private static void token(MockRestServiceServer server) {
        server.expect(once(), requestTo("http://localhost:8080/realms/chargeops/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"test-token\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }
}
