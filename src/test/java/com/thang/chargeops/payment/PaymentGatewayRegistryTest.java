package com.thang.chargeops.payment;

import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.gateway.PaymentGateway;
import com.thang.chargeops.payment.gateway.PaymentGatewayRegistry;
import com.thang.chargeops.payment.gateway.PaymentGatewayUnavailableException;
import com.thang.chargeops.payment.model.OrderCheckout;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentGatewayRegistryTest {

    @Test
    void exposesOnlyMethodsBackedByAnAdapter() {
        PaymentGateway simulator = mock(PaymentGateway.class);
        when(simulator.supports(PaymentMethod.SIMULATOR)).thenReturn(true);
        PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(simulator));

        assertThat(registry.supports(PaymentMethod.SIMULATOR)).isTrue();
        assertThat(registry.supports(PaymentMethod.VNPAY)).isFalse();
        assertThat(registry.supports(PaymentMethod.MOMO)).isFalse();
        assertThat(registry.supports(PaymentMethod.ZALOPAY)).isFalse();
        assertThat(registry.supports(PaymentMethod.BANK_TRANSFER)).isFalse();
    }

    @Test
    void translatesAdapterNetworkFailureToCheckoutUnavailable() {
        Payment payment = mock(Payment.class);
        PaymentGateway simulator = mock(PaymentGateway.class);
        Instant now = Instant.parse("2026-09-18T02:00:00Z");
        when(payment.getMethod()).thenReturn(PaymentMethod.SIMULATOR);
        when(simulator.supports(PaymentMethod.SIMULATOR)).thenReturn(true);
        when(simulator.createCheckout(payment, now))
                .thenThrow(new PaymentGatewayUnavailableException("network down"));
        PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(simulator));

        assertThatThrownBy(() -> registry.createCheckout(payment, now))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(PaymentErrorCode.CHECKOUT_UNAVAILABLE)
                );
    }
}
