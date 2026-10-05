package com.thang.chargeops.payment.gateway;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.model.OrderCheckout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulatorPaymentGatewayTest {

    private SimulatorPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new SimulatorPaymentGateway();
    }

    @Test
    @DisplayName("SimulatorPaymentGateway is restricted to non-production simulator profiles")
    void gatewayHasNonProductionSimulatorProfiles() {
        Profile profile = SimulatorPaymentGateway.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder("dev", "demo", "test");
    }

    @Test
    @DisplayName("SimulatorPaymentGateway supports only SIMULATOR method")
    void supportsOnlySimulatorMethod() {
        assertThat(gateway.supports(PaymentMethod.SIMULATOR)).isTrue();
        assertThat(gateway.supports(PaymentMethod.BANK_TRANSFER)).isFalse();
    }

    @Test
    @DisplayName("SimulatorPaymentGateway profile specifies SIMULATOR environment")
    void profileHasSimulatorEnvironment() {
        PaymentGatewayProfile profile = gateway.profile();
        assertThat(profile).isNotNull();
        assertThat(profile.provider()).isEqualTo("SIMULATOR");
        assertThat(profile.receivingAccountRef()).isEqualTo("SIMULATOR");
        assertThat(profile.currency()).isEqualTo("VND");
        assertThat(profile.environment()).isEqualTo(PaymentEnvironment.SIMULATOR);
    }

    @Test
    @DisplayName("Legacy three-argument simulator profile also defaults to SIMULATOR environment")
    void convenienceProfileDoesNotCollapseSimulatorIntoProviderTest() {
        PaymentGatewayProfile profile = new PaymentGatewayProfile("simulator", "SIMULATOR", "VND");

        assertThat(profile.environment()).isEqualTo(PaymentEnvironment.SIMULATOR);
    }

    @Test
    @DisplayName("createCheckout produces internal simulation artifacts and no public VietQR or bank data")
    void createCheckoutProducesSafeSimulatorArtifacts() {
        String paymentCode = "BK-20260921-001";
        BigDecimal amount = new BigDecimal("150000");
        Instant expiresAt = Instant.parse("2026-09-21T10:15:00Z");

        Booking mockBooking = mock(Booking.class);
        when(mockBooking.getExpiresAt()).thenReturn(expiresAt);

        Payment mockPayment = mock(Payment.class);
        when(mockPayment.getPaymentCode()).thenReturn(paymentCode);
        when(mockPayment.getAmount()).thenReturn(amount);
        when(mockPayment.getBooking()).thenReturn(mockBooking);

        OrderCheckout checkout = gateway.createCheckout(mockPayment, Instant.parse("2026-09-21T10:05:00Z"));

        assertThat(checkout).isNotNull();
        assertThat(checkout.orderRef()).isEqualTo("SIM-" + paymentCode);
        assertThat(checkout.orderCode()).isEqualTo(paymentCode);
        assertThat(checkout.amount()).isEqualByComparingTo(amount);
        assertThat(checkout.expiresAt()).isEqualTo(expiresAt);

        // Security check: VA number must be clearly simulated and never look like a real bank account
        assertThat(checkout.vaNumber()).isEqualTo("VA-SIM-" + paymentCode);
        assertThat(checkout.vaNumber()).doesNotContain("96247");

        // Offline simulator exposes no network checkout URL or public VietQR endpoint.
        assertThat(checkout.qrCode()).isEqualTo("SIM_QR_" + paymentCode);
        assertThat(checkout.qrCodeUrl()).isNull();
    }
}
