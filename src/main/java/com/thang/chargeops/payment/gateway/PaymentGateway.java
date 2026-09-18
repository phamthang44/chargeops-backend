package com.thang.chargeops.payment.gateway;

import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.model.OrderCheckout;

import java.time.Instant;

public interface PaymentGateway {

    boolean supports(PaymentMethod method);

    OrderCheckout createCheckout(Payment payment, Instant now);
}
