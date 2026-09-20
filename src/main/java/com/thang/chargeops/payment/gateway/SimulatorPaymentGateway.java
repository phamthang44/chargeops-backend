package com.thang.chargeops.payment.gateway;

import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.model.OrderCheckout;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SimulatorPaymentGateway implements PaymentGateway {

    private static final PaymentGatewayProfile PROFILE =
            new PaymentGatewayProfile("SIMULATOR", "SIMULATOR", "VND");

    @Override
    public boolean supports(PaymentMethod method) {
        return method == PaymentMethod.SIMULATOR;
    }

    @Override
    public PaymentGatewayProfile profile() {
        return PROFILE;
    }

    @Override
    public OrderCheckout createCheckout(Payment payment, Instant now) {
        String paymentCode = payment.getPaymentCode();
        return new OrderCheckout(
                "SIM-" + paymentCode,
                paymentCode,
                "VA-SIM-" + paymentCode,
                payment.getAmount(),
                payment.getBooking().getExpiresAt(),
                "SIMULATOR_QR_" + paymentCode,
                "https://simulator.chargeops.local/checkout/" + paymentCode
        );
    }
}
