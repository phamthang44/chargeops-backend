package com.thang.chargeops.payment.gateway;

import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.model.OrderCheckout;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Profile({"dev", "demo", "test"})
public class SimulatorPaymentGateway implements PaymentGateway {

    private static final PaymentGatewayProfile PROFILE =
            new PaymentGatewayProfile(
                    "SIMULATOR",
                    "SIMULATOR",
                    "VND",
                    PaymentEnvironment.SIMULATOR
            );

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
        String vaNumber = "VA-SIM-" + paymentCode;
        String qrCode = "SIM_QR_" + paymentCode;
        return new OrderCheckout(
                "SIM-" + paymentCode,
                paymentCode,
                vaNumber,
                payment.getAmount(),
                payment.getBooking().getExpiresAt(),
                qrCode,
                null
        );
    }
}
