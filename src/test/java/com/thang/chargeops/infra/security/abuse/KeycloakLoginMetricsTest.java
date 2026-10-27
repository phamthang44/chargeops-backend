package com.thang.chargeops.infra.security.abuse;

import com.thang.chargeops.infra.identity.keycloak.KeycloakAdminProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.assertj.core.api.Assertions.*;

class KeycloakLoginMetricsTest {
    @Test void onlyReportsHealthyCountsWhenLoginEventsAreEnabled() {
        var builder = RestClient.builder().baseUrl("https://keycloak.fixture");
        var server = MockRestServiceServer.bindTo(builder).build();
        String base="https://keycloak.fixture";
        for (boolean enabled : new boolean[]{false,true}) {
            server.expect(requestTo(base+"/realms/chargeops/protocol/openid-connect/token"))
                    .andRespond(withSuccess("{\"access_token\":\"fixture\"}",MediaType.APPLICATION_JSON));
            server.expect(requestTo(base+"/admin/realms/chargeops/events/config")).andRespond(withSuccess(
                    "{\"eventsEnabled\":"+enabled+",\"enabledEventTypes\":[\"LOGIN_ERROR\"]}",MediaType.APPLICATION_JSON));
            if (enabled) server.expect(requestTo(base+"/admin/realms/chargeops/events?type=LOGIN_ERROR&first=0&max=1000"))
                    .andRespond(withSuccess("[{\"time\":"+System.currentTimeMillis()+"},{\"time\":1}]",MediaType.APPLICATION_JSON));
        }
        var metrics=new SimpleMeterRegistry();
        var collector=new KeycloakLoginMetrics(new KeycloakAdminProperties(),metrics,builder);
        collector.poll();
        assertThat(metrics.get("chargeops.auth.events.healthy").gauge().value()).isZero();
        assertThat(metrics.get("chargeops.auth.login.errors.recent").gauge().value()).isEqualTo(-1);
        collector.poll();
        assertThat(metrics.get("chargeops.auth.events.healthy").gauge().value()).isEqualTo(1);
        assertThat(metrics.get("chargeops.auth.login.errors.recent").gauge().value()).isEqualTo(1);
        assertThat(metrics.get("chargeops.auth.events.last.success").gauge().value()).isPositive();
        assertThat(metrics.getMeters()).allSatisfy(m -> assertThat(m.getId().getTags()).isEmpty());
        server.verify();
    }
}
