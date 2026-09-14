package com.thang.chargeops.payment.model;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class PaymentValues {
    private PaymentValues() {}

    public static String requiredText(String value, int max, String label) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, label + " is required and must fit " + max + " characters");
        }
        return value.trim();
    }

    public static BigDecimal exactVnd(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(new BigDecimal("999999999999")) > 0) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Order amount must be positive VND, at most 12 digits");
        }
        try { return value.setScale(0, RoundingMode.UNNECESSARY).setScale(2); }
        catch (ArithmeticException ex) {
            throw new AppException(PaymentErrorCode.AMOUNT_INVALID, "Order amount must be whole VND");
        }
    }
}
