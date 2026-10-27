package com.thang.chargeops.infra.security.abuse;

import com.thang.chargeops.infra.identity.keycloak.KeycloakAdminProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in aggregate collector; event bodies/identities never leave memory or enter logs. */
@Component
@ConditionalOnProperty(name = "app.abuse.keycloak-metrics-enabled", havingValue = "true")
public class KeycloakLoginMetrics {
    private final KeycloakAdminProperties properties;
    private final RestClient client;
    private final AtomicInteger errors = new AtomicInteger(-1);
    private final AtomicInteger healthy = new AtomicInteger(0);
    private final AtomicInteger truncated = new AtomicInteger(0);
    private final AtomicLong lastSuccess = new AtomicLong(0);
    @org.springframework.beans.factory.annotation.Autowired
    public KeycloakLoginMetrics(KeycloakAdminProperties properties, MeterRegistry metrics) {
        this(properties, metrics, clientBuilder(properties));
    }
    private static RestClient.Builder clientBuilder(KeycloakAdminProperties properties) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().baseUrl(properties.getBaseUrl()).requestFactory(factory);
    }
    KeycloakLoginMetrics(KeycloakAdminProperties properties, MeterRegistry metrics, RestClient.Builder builder) {
        this.properties = properties;
        client = builder.build();
        metrics.gauge("chargeops.auth.login.errors.recent", errors);
        metrics.gauge("chargeops.auth.events.healthy", healthy);
        metrics.gauge("chargeops.auth.events.truncated", truncated);
        metrics.gauge("chargeops.auth.events.last.success", lastSuccess);
    }
    @Scheduled(fixedDelayString = "${app.abuse.keycloak-metrics-delay-ms:60000}")
    public void poll() {
        try {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "client_credentials"); form.add("client_id", properties.getClientId());
            form.add("client_secret", properties.getClientSecret());
            Map<?, ?> token = client.post().uri("/realms/{realm}/protocol/openid-connect/token", properties.getRealm())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
            if (token == null || !(token.get("access_token") instanceof String access)) throw new IllegalStateException();
            Map<?, ?> settings = client.get().uri("/admin/realms/{realm}/events/config", properties.getRealm())
                    .headers(h -> h.setBearerAuth(access)).retrieve().body(Map.class);
            if (settings == null || !Boolean.TRUE.equals(settings.get("eventsEnabled")) ||
                    !(settings.get("enabledEventTypes") instanceof List<?> types) || !types.contains("LOGIN_ERROR"))
                throw new IllegalStateException("Login event collection is not configured");
            List<?> events = client.get().uri("/admin/realms/{realm}/events?type=LOGIN_ERROR&first=0&max=1000", properties.getRealm())
                    .headers(h -> h.setBearerAuth(access)).retrieve().body(List.class);
            long cutoff = System.currentTimeMillis() - 300_000;
            int count = 0;
            if (events != null) for (Object event : events) {
                if (event instanceof Map<?, ?> data && data.get("time") instanceof Number time && time.longValue() >= cutoff) count++;
            }
            errors.set(count); truncated.set(events != null && events.size() == 1000 ? 1 : 0);
            healthy.set(1); lastSuccess.set(System.currentTimeMillis() / 1000);
        } catch (RuntimeException ignored) {
            // Never log provider response bodies, access tokens or event identities.
            healthy.set(0); errors.set(-1);
        }
    }
}
