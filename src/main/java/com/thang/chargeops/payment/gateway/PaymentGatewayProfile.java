package com.thang.chargeops.payment.gateway;

import java.util.Objects;

/** Merchant identity persisted before the external checkout call is made. */
public record PaymentGatewayProfile(
        String provider,
        String receivingAccountRef,
        String currency
) {
    public PaymentGatewayProfile {
        provider = requireText(provider, "provider");
        receivingAccountRef = requireText(receivingAccountRef, "receivingAccountRef");
        currency = requireText(currency, "currency");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return Objects.requireNonNull(value).trim();
    }
}
