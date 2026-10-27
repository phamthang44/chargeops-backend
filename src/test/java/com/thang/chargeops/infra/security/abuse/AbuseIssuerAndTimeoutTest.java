package com.thang.chargeops.infra.security.abuse;

import com.thang.chargeops.infra.security.SecurityConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AbuseIssuerAndTimeoutTest {
    private static final String ISSUER = "https://auth.chargeops.online/realms/chargeops";
    @Test void issuerMustBeExactAndSubjectMustExist() {
        var config = new SecurityConfig(mock(com.thang.chargeops.infra.security.JwtRoleConverter.class),
                mock(com.thang.chargeops.infra.security.RestAuthenticationEntryPoint.class),
                mock(com.thang.chargeops.infra.security.RestAccessDeniedHandler.class),
                mock(com.thang.chargeops.profile.repository.UserProfileRepository.class),
                mock(com.thang.chargeops.infra.persistence.CurrentActorContext.class), new ObjectMapper());
        ReflectionTestUtils.setField(config, "issuerUri", ISSUER);
        ReflectionTestUtils.setField(config, "configuredJwkSetUri", "http://127.0.0.1:1/certs");
        var decoder = config.jwtDecoder();
        @SuppressWarnings("unchecked") var validator = (OAuth2TokenValidator<Jwt>) ReflectionTestUtils.getField(decoder, "jwtValidator");
        assertThat(validator.validate(token(ISSUER, "actor")).hasErrors()).isFalse();
        assertThat(validator.validate(token("https://attacker.invalid/realms/chargeops", "actor")).hasErrors()).isTrue();
        assertThat(validator.validate(token(ISSUER + "/extra", "actor")).hasErrors()).isTrue();
        assertThat(validator.validate(token(ISSUER, null)).hasErrors()).isTrue();
    }
    private Jwt token(String issuer, String subject) {
        var builder = Jwt.withTokenValue("fixture").header("alg", "RS256").issuer(issuer)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        if (subject != null) builder.subject(subject);
        return builder.build();
    }
    @Test void unresponsiveRedisHandshakeHasFiniteLatency() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var store = new AbuseRedisStore("127.0.0.1", socket.getLocalPort(), "", "", 0)) {
            // The listening socket accepts TCP but deliberately never answers the Redis handshake.
            var properties = new AbuseProperties(); properties.setMode(AbuseProperties.Mode.ENFORCE);
            properties.setHmacKey("test-only-hmac-material-at-least-32-bytes");
            var limiter = new AbuseRateLimiter(store, properties, new SimpleMeterRegistry());
            long start = System.nanoTime();
            assertThat(limiter.acquire(AbuseGroup.EXPORT, "actor", "192.0.2.1").code()).isEqualTo("SEC_003");
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3000);
        }
    }
}
