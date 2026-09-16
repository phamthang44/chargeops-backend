package com.thang.chargeops.booking.mapper;


import com.thang.chargeops.booking.dto.response.CreateBookingResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.CheckoutStatus;
import com.thang.chargeops.payment.entity.Payment;
import org.mapstruct.Mapper;

import java.time.Duration;
import java.util.Objects;

@Mapper(componentModel = "spring")
public interface BookingMapper {

    default CreateBookingResponse toCreateBookingResponse(Booking booking, Payment payment) {
        Objects.requireNonNull(booking, "booking must not be null");
        Objects.requireNonNull(payment, "payment must not be null");

        return CreateBookingResponse.builder()
                .bookingId(booking.getId())
                .bookingCode(booking.getBookingCode())
                .status(booking.getStatus())
                .version(booking.getVersion())
                .connectorId(booking.getConnector().getId())
                .startAt(booking.getStartAt())
                .endAt(booking.getEndAt())
                .durationMin(Math.toIntExact(Duration.between(
                        booking.getStartAt(), booking.getEndAt()).toMinutes()))
                .totalAmount(booking.getTotalAmount().longValueExact())
                .currency(payment.getCurrency())
                .paymentHoldExpiresAt(booking.getExpiresAt())
                .payment(new CreateBookingResponse.PaymentSummary(
                        payment.getId(),
                        payment.getStatus(),
                        payment.getMethod()
                ))
                .checkout(new CreateBookingResponse.CheckoutSummary(
                        CheckoutStatus.NOT_CREATED
                ))
                .build();
    }
}
