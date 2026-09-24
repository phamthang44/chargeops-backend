package com.thang.chargeops.payment.controller;

import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.payment.dto.request.SimulationRequest;
import com.thang.chargeops.payment.dto.response.SimulationResultResponse;
import com.thang.chargeops.payment.service.PaymentSimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentSimulatorControllerTest {

    @Mock
    private PaymentSimulationService paymentSimulationService;

    private PaymentSimulatorController controller;

    @BeforeEach
    void setUp() {
        controller = new PaymentSimulatorController(paymentSimulationService);
    }

    @Test
    @DisplayName("simulatePayment delegates to service and returns 200 OK with ApiResult")
    void simulatePayment_DelegatesToServiceAndReturns200() {
        UUID bookingId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        SimulationRequest request = new SimulationRequest(
                "TX-CTRL-001",
                SimulationRequest.Outcome.SUCCESS,
                150000L,
                "VND",
                Instant.parse("2026-09-21T10:00:00Z")
        );

        SimulationResultResponse mockResponse = new SimulationResultResponse(
                SimulationRequest.Outcome.SUCCESS,
                false,
                null,
                null
        );

        when(paymentSimulationService.simulate(bookingId, requestKey, request)).thenReturn(mockResponse);

        ResponseEntity<ApiResult<SimulationResultResponse>> result =
                controller.simulatePayment(bookingId, requestKey, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getError()).isNull();
        assertThat(result.getBody().getData()).isEqualTo(mockResponse);

        verify(paymentSimulationService).simulate(bookingId, requestKey, request);
    }

    @Test
    @DisplayName("Controller is available only in non-production simulator profiles and requires ADMIN authority")
    void controllerHasExpectedRuntimeGuards() {
        Profile profile = PaymentSimulatorController.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder("dev", "demo", "test");

        PreAuthorize preAuthorize = PaymentSimulatorController.class.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasRole('ADMIN')");
    }
}
