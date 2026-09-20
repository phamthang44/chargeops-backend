package com.thang.chargeops.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thang.chargeops.payment.controller.SepayWebhookController;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import com.thang.chargeops.payment.service.PaymentConfirmationService;
import com.thang.chargeops.payment.utils.SepayWebhookVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SepayWebhookControllerTest {

    private SepayWebhookVerifier verifier;
    private PaymentConfirmationService confirmationService;
    private SepayWebhookController controller;

    @BeforeEach
    void setUp() {
        verifier = mock(SepayWebhookVerifier.class);
        confirmationService = mock(PaymentConfirmationService.class);
        controller = new SepayWebhookController(
                verifier,
                confirmationService,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"), ZoneOffset.UTC));
        when(verifier.verify(any(), anyString(), anyString())).thenReturn(true);
    }

    @Test
    void nullTransferType_isRejectedAndNotAcknowledged() {
        var response = controller.handleWebhook("sig", "1", bytes(validPayload().replace("\"in\"", "null")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("success", false);
        verifyNoInteractions(confirmationService);
    }

    @Test
    void infrastructureFailure_returnsRetryable503() {
        when(confirmationService.processReceipt(any(NormalizedReceipt.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        var response = controller.handleWebhook("sig", "1", bytes(validPayload()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("success", false);
    }

    @Test
    void validIncomingTransfer_isAcknowledgedAfterServiceReturns() {
        var response = controller.handleWebhook("sig", "1", bytes(validPayload()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("success", true);
        verify(confirmationService).processReceipt(argThat(receipt ->
                receipt.transactionRef().equals("123")
                        && receipt.paymentCode().equals("COABC123")
                        && receipt.vaNumber().equals("TEST000001")));
    }

    private String validPayload() {
        return """
                {"id":123,"transactionDate":"2026-09-20 17:00:00",
                "accountNumber":"0123456789","subAccount":"TEST000001",
                "code":"COABC123","content":"COABC123","transferType":"in",
                "transferAmount":120000,"referenceCode":"REF123"}
                """;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
