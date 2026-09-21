package com.thang.chargeops.payment.gateway;

import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.model.OrderCheckout;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SimulatorPaymentGateway implements PaymentGateway {

    private static final PaymentGatewayProfile PROFILE =
            new PaymentGatewayProfile(
                    "SIMULATOR",
                    "SIMULATOR",
                    "VND",
                    PaymentEnvironment.TEST
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
        String safeCode = paymentCode.replaceAll("[^a-zA-Z0-9]", "");
        String vaSuffix = safeCode.length() > 8 ? safeCode.substring(safeCode.length() - 8) : safeCode;
        String vaNumber = "96247" + vaSuffix;
        long amount = payment.getAmount().longValue();
        String qrCodeUrl = "https://img.vietqr.io/image/MB-" + vaNumber + "-compact2.png?amount=" + amount + "&addInfo=" + paymentCode + "&accountName=CHARGEOPS%20DEMO";
        return new OrderCheckout(
                "SIM-" + paymentCode,
                paymentCode,
                vaNumber,
                payment.getAmount(),
                payment.getBooking().getExpiresAt(),
                "SIMULATOR_QR_" + paymentCode,
                qrCodeUrl
        );
    }
}
