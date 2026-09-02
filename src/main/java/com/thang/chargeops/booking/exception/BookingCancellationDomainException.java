package com.thang.chargeops.booking.exception;

import com.thang.chargeops.booking.exception.violation.BookingCancellationViolation;

/**
 * Báo lỗi invariant bên trong domain cancellation.
 * Application service triển khai lệnh cancel sau này sẽ chịu trách nhiệm dịch
 * violation sang API ErrorCode phù hợp.
 */
public class BookingCancellationDomainException extends RuntimeException {

    private final BookingCancellationViolation violation;

    public BookingCancellationDomainException(
            BookingCancellationViolation violation,
            String message
    ) {
        super(message);
        this.violation = violation;
    }

    public BookingCancellationViolation getViolation() {
        return violation;
    }
}
