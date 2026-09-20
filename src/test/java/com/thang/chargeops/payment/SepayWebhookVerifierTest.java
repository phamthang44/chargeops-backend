package com.thang.chargeops.payment;

import com.thang.chargeops.payment.config.SepayTestModeProperties;
import com.thang.chargeops.payment.utils.SepayWebhookVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class SepayWebhookVerifierTest {

    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");
    private static final String SECRET = "sandbox-webhook-secret";
    private SepayWebhookVerifier verifier;

    @BeforeEach
    void setUp() {
        SepayTestModeProperties properties = validProperties();
        verifier = new SepayWebhookVerifier(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void acceptsFreshValidSignature() throws Exception {
        byte[] body = "{\"id\":123}".getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(NOW.getEpochSecond());

        assertThat(verifier.verify(body, timestamp, sign(timestamp, body))).isTrue();
    }

    @Test
    void rejectsSignatureOlderThanFiveMinutes() throws Exception {
        byte[] body = "{\"id\":123}".getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(NOW.minusSeconds(301).getEpochSecond());

        assertThat(verifier.verify(body, timestamp, sign(timestamp, body))).isFalse();
    }

    static SepayTestModeProperties validProperties() {
        SepayTestModeProperties properties = new SepayTestModeProperties();
        properties.setEnabled(true);
        properties.setApiToken("sandbox-token");
        properties.setBankAccountXid("ba_test_123");
        properties.setReceivingAccountRef("0123456789");
        properties.setVaPrefix("TEST");
        properties.setWebhookSecret(SECRET);
        return properties;
    }

    private String sign(String timestamp, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '.');
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
