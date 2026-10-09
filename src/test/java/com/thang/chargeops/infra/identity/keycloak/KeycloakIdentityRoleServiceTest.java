package com.thang.chargeops.infra.identity.keycloak;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakIdentityRoleServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void missingKeycloakUserHasNoRolesInsteadOfFailingCaller() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/realms/chargeops/protocol/openid-connect/token", exchange -> {
            byte[] body = "{\"access_token\":\"admin-token\",\"expires_in\":300}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/admin/realms/chargeops/users/missing-user/role-mappings/realm/composite", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        var properties = new KeycloakAdminProperties();
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setRealm("chargeops");
        properties.setClientId("chargeops-backend");
        properties.setClientSecret("secret");

        assertThat(new KeycloakIdentityRoleService(properties).getRoles("missing-user")).isEmpty();
    }
}
