package com.thang.chargeops.payment.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Trusted adapter response from Create Order, not a client payment command. */
public record OrderCheckout(String orderRef, String orderCode, String vaNumber,
                            BigDecimal amount, Instant expiresAt, String qrCode, String qrCodeUrl) {
}
