package com.thang.chargeops.payment.service;

import com.thang.chargeops.payment.dto.request.SimulationRequest;
import com.thang.chargeops.payment.dto.response.SimulationResultResponse;

import java.util.UUID;

public interface PaymentSimulationService {

    SimulationResultResponse simulate(
            UUID bookingId,
            UUID requestKey,
            SimulationRequest request
    );

}
