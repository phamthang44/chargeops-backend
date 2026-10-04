package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.mapper.BookingMapper;
import com.thang.chargeops.booking.policy.DriverBookingReadPolicy;
import com.thang.chargeops.booking.service.model.BookingReadSnapshot;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.refund.repository.RefundRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * Shared assembler for Driver-facing booking details.
 * Used by read services and cancellation services to ensure identical projection
 * and capability evaluations, including persisted refunds.
 */
@Component
public class DriverBookingDetailAssembler {
    private final BookingFinancialReadAssembler financialReadAssembler;
    private final BookingMapper bookingMapper;
    private final ServiceFailureDecisionReader decisionReader;

    @Autowired
    public DriverBookingDetailAssembler(BookingFinancialReadAssembler financialReadAssembler, BookingMapper bookingMapper,
                                       ServiceFailureDecisionReader decisionReader) {
        this.financialReadAssembler = financialReadAssembler;
        this.bookingMapper = bookingMapper;
        this.decisionReader = decisionReader;
    }

    /** Compatibility constructor retained for focused unit tests and downstream modules. */
    public DriverBookingDetailAssembler(
            DriverBookingReadPolicy policy,
            PaymentTransactionRepository paymentTransactionRepository,
            RefundRepository refundRepository,
            BookingMapper bookingMapper
    ) {
        this(new BookingFinancialReadAssembler(policy, paymentTransactionRepository, refundRepository), bookingMapper, null);
    }

    public BookingDetailResponse assemble(
            Booking booking,
            Payment payment,
            Instant evaluatedAt
    ) {
        Objects.requireNonNull(booking, "booking must not be null");
        Objects.requireNonNull(payment, "payment must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");

        BookingReadSnapshot snapshot = buildBookingReadSnapshot(booking, payment, evaluatedAt);
        var detail = bookingMapper.toBookingDetailResponse(booking, payment, snapshot);
        return decisionReader == null ? detail : detail.toBuilder()
                .serviceFailureDecisions(decisionReader.summaries(booking.getId())).build();
    }

    public BookingReadSnapshot buildBookingReadSnapshot(
            Booking booking,
            Payment payment,
            Instant evaluatedAt
    ) {
        return financialReadAssembler.assemble(booking, payment, evaluatedAt);
    }
}
