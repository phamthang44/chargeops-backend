package com.thang.chargeops.payment.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.payment.dto.request.SimulationRequest;
import com.thang.chargeops.payment.dto.response.SimulationResultResponse;
import com.thang.chargeops.payment.service.PaymentSimulationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@Profile({"dev", "demo", "test"})
@RequiredArgsConstructor
@RequestMapping(SystemConstant.API_URL_PATTERN + "bookings")
@PreAuthorize("hasRole('ADMIN')")
public class PaymentSimulatorController {

    private final PaymentSimulationService paymentSimulationService;

    @PostMapping("/{bookingId}/simulate-payment")
    public ResponseEntity<ApiResult<SimulationResultResponse>> simulatePayment(
            @PathVariable UUID bookingId,
            @RequestHeader("Idempotency-Key") UUID requestKey,
            @Valid @RequestBody SimulationRequest request
    ) {
        SimulationResultResponse response =
                paymentSimulationService.simulate(
                        bookingId,
                        requestKey,
                        request
                );

        return ResponseEntity.ok(ApiResult.success(response));
    }


}
