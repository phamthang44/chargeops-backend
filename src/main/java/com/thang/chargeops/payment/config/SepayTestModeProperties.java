package com.thang.chargeops.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.sepay")
public class SepayTestModeProperties {

    public static final String SANDBOX_BASE_URL = "https://userapi-sandbox.sepay.vn/v2";

    private boolean enabled;
    private String baseUrl = SANDBOX_BASE_URL;
    private String apiToken = "";
    private String bankAccountXid = "";
    private String receivingAccountRef = "";
    private String vaPrefix = "";
    private String webhookSecret = "";
    private long webhookToleranceSeconds = 300;

    public void validateForUse() {
        URI configured = URI.create(requireText(baseUrl, "base-url"));
        URI sandbox = URI.create(SANDBOX_BASE_URL);
        if (!sandbox.getScheme().equalsIgnoreCase(configured.getScheme())
                || !sandbox.getHost().equalsIgnoreCase(configured.getHost())
                || !sandbox.getPath().equals(configured.getPath())) {
            throw new IllegalStateException("ChargeOps only permits the SePay Test Mode sandbox URL");
        }
        requireText(apiToken, "api-token");
        requireText(bankAccountXid, "bank-account-xid");
        requireText(receivingAccountRef, "receiving-account-ref");
        requireText(vaPrefix, "va-prefix");
        requireText(webhookSecret, "webhook-secret");
        if (webhookToleranceSeconds <= 0 || webhookToleranceSeconds > 300) {
            throw new IllegalStateException("webhook-tolerance-seconds must be between 1 and 300");
        }
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("app.sepay." + name + " is required when SePay is enabled");
        }
        return value.trim();
    }
}
