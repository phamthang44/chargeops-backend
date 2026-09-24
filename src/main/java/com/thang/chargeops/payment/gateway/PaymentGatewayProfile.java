package com.thang.chargeops.payment.gateway;

import com.thang.chargeops.common.enums.PaymentEnvironment;

import java.util.Objects;

/** Merchant identity persisted before the external checkout call is made. */
public record PaymentGatewayProfile(
        String provider,
        String receivingAccountRef,
        String currency,
        PaymentEnvironment environment
) {
    public PaymentGatewayProfile {
        provider = requireText(provider, "provider");
        receivingAccountRef = requireText(receivingAccountRef, "receivingAccountRef");
        currency = requireText(currency, "currency");
        environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    public PaymentGatewayProfile(
            String provider,
            String receivingAccountRef,
            String currency
    ) {
        this(
                provider,
                receivingAccountRef,
                currency,
                "SIMULATOR".equalsIgnoreCase(provider)
                        ? PaymentEnvironment.SIMULATOR
                        : PaymentEnvironment.LIVE
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return Objects.requireNonNull(value).trim();
    }
}
