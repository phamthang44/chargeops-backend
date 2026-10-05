package com.thang.chargeops.payment.gateway;

import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.model.OrderCheckout;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class PaymentGatewayRegistry {

    private final List<PaymentGateway> gateways;

    public PaymentGatewayRegistry(List<PaymentGateway> gateways) {
        this.gateways = List.copyOf(gateways);
    }

    public boolean supports(PaymentMethod method) {
        return gateways.stream().anyMatch(gateway -> gateway.supports(method));
    }

    public PaymentGatewayProfile profile(PaymentMethod method) {
        return requireGateway(method).profile();
    }

    public OrderCheckout createCheckout(Payment payment, Instant now) {
        PaymentGateway gateway = requireGateway(payment.getMethod());
        try {
            return gateway.createCheckout(payment, now);
        } catch (PaymentGatewayUnavailableException exception) {
            throw new AppException(PaymentErrorCode.CHECKOUT_UNAVAILABLE);
        }
    }

    private PaymentGateway requireGateway(PaymentMethod method) {
        return gateways.stream()
                .filter(candidate -> candidate.supports(method))
                .findFirst()
                .orElseThrow(() -> new AppException(PaymentErrorCode.METHOD_INVALID));
    }
}
