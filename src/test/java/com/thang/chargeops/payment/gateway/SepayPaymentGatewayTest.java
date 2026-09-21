package com.thang.chargeops.payment.gateway;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.payment.config.SepayTestModeProperties;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.model.OrderCheckout;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SepayPaymentGatewayTest {

    @Test
    void createsItsOwnRestClientBuilder() {
        SepayPaymentGateway gateway = new SepayPaymentGateway(validProperties());

        assertThat(gateway.profile().provider()).isEqualTo("SEPAY");
        assertThat(gateway.profile().environment()).isEqualTo(PaymentEnvironment.TEST);
    }

    @Test
    void createsSacombankOrderVaThroughSandboxV2() {
        SepayTestModeProperties properties = validProperties();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SepayPaymentGateway gateway = new SepayPaymentGateway(properties, builder);

        Instant now = Instant.parse("2026-09-20T10:00:00Z");
        Booking booking = mock(Booking.class);
        when(booking.getExpiresAt()).thenReturn(Instant.parse("2026-09-20T10:10:00Z"));
        Payment payment = mock(Payment.class);
        when(payment.getBooking()).thenReturn(booking);
        when(payment.getPaymentCode()).thenReturn("COABC123");
        when(payment.getAmount()).thenReturn(new BigDecimal("120000"));

        server.expect(requestTo(
                        "https://userapi-sandbox.sepay.vn/v2/bank-accounts/ba_test_123/orders"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sandbox-token"))
                .andRespond(withSuccess("""
                        {"data":{"id":"order-test-id","order_code":"COABC123",
                        "va_number":"TEST000001","amount":120000,
                        "expired_at":"2026-09-20 17:10:00",
                        "qr_code":"sandbox-qr","qr_code_url":"https://sandbox.example/qr"}}
                        """, MediaType.APPLICATION_JSON));

        OrderCheckout checkout = gateway.createCheckout(payment, now);

        assertThat(checkout.orderRef()).isEqualTo("order-test-id");
        assertThat(checkout.vaNumber()).isEqualTo("TEST000001");
        assertThat(checkout.amount()).isEqualByComparingTo("120000");
        assertThat(checkout.expiresAt()).isEqualTo(booking.getExpiresAt());
        assertThat(gateway.profile().receivingAccountRef()).isEqualTo("0123456789");
        server.verify();
    }

    private SepayTestModeProperties validProperties() {
        SepayTestModeProperties properties = new SepayTestModeProperties();
        properties.setEnabled(true);
        properties.setApiToken("sandbox-token");
        properties.setBankAccountXid("ba_test_123");
        properties.setReceivingAccountRef("0123456789");
        properties.setVaPrefix("TEST");
        properties.setWebhookSecret("sandbox-webhook-secret");
        return properties;
    }
}
