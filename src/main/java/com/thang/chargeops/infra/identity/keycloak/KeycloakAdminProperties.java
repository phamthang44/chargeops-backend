package com.thang.chargeops.infra.identity.keycloak;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.identity.keycloak-admin")
public class KeycloakAdminProperties {

    private String baseUrl = "http://localhost:8080";
    private String realm = "chargeops";
    private String clientId = "chargeops-backend";
    private String clientSecret = "";
}
