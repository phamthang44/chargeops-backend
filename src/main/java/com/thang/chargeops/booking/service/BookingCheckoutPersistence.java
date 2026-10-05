package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.dto.response.CheckoutResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.CheckoutStatus;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.model.OrderCheckout;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingCheckoutPersistence {

    private static final String SIMULATOR_INSTRUCTION =
            "Complete payment in the simulator before the hold expires.";
    private static final String SEPAY_TEST_INSTRUCTION =
            "SePay Test Mode only — simulated payment; do not transfer real money.";

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final BookingCommandRegistry bookingCommandRegistry;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public Payment prepareCheckout(UUID bookingId, UUID driverId, Instant now) {
        Booking booking = requireOwnedBooking(bookingId, driverId);
        Payment payment = requirePayment(bookingId);
        validateCheckoutAllowed(booking, payment, now);

        // Initialize the lazy association while this read transaction is open.
        payment.getBooking().getExpiresAt();
        return payment;
    }

    @Transactional(readOnly = true)
    public CheckoutResponse loadReplay(UUID bookingId, UUID driverId, Instant now) {
        requireOwnedBooking(bookingId, driverId);
        return toResponse(requirePayment(bookingId), now);
    }

    @Transactional
    public CheckoutResponse completeCheckout(
            UUID bookingId,
            UUID driverId,
            UUID requestKey,
            String payloadHash,
            OrderCheckout checkout,
            Instant completedAt
    ) {
        Booking booking = requireOwnedBookingWithLock(bookingId, driverId);
        Payment payment = paymentRepository.findByBookingIdWithLock(bookingId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.NOT_FOUND));
        validateCheckoutAllowed(booking, payment, completedAt);

        var replay = bookingCommandRegistry.findReplay(
                driverId,
                BookingCommandOperation.CREATE_CHECKOUT,
                requestKey,
                payloadHash
        );
        if (replay.isPresent()) {
            return toResponse(payment, completedAt);
        }

        if (payment.getProviderOrderRef() == null) {
            payment.bindOrder(checkout, completedAt);
        }

        UserProfile actor = entityManager.getReference(UserProfile.class, driverId);
        bookingCommandRegistry.recordSuccess(
                actor,
                BookingCommandOperation.CREATE_CHECKOUT,
                requestKey,
                payloadHash,
                booking,
                completedAt
        );
        return toResponse(payment, completedAt);
    }

    private Booking requireOwnedBooking(UUID bookingId, UUID driverId) {
        return bookingRepository.findByIdAndDriverId(bookingId, driverId)
                .orElseGet(() -> throwBookingAccessError(bookingId));
    }

    private Booking requireOwnedBookingWithLock(UUID bookingId, UUID driverId) {
        return bookingRepository.findByIdAndDriverIdWithLock(bookingId, driverId)
                .orElseGet(() -> throwBookingAccessError(bookingId));
    }

    private Booking throwBookingAccessError(UUID bookingId) {
        if (bookingRepository.existsById(bookingId)) {
            throw new AppException(BookingErrorCode.BOOKING_NOT_ACCESS);
        }
        throw new AppException(
                CommonErrorCode.RESOURCE_NOT_FOUND,
                "Booking not found: " + bookingId
        );
    }

    private Payment requirePayment(UUID bookingId) {
        return paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.NOT_FOUND));
    }

    private void validateCheckoutAllowed(Booking booking, Payment payment, Instant now) {
        if (payment.getStatus() != PaymentStatus.PENDING
                && payment.getStatus() != PaymentStatus.FAILED) {
            throw new AppException(PaymentErrorCode.STATE_CONFLICT);
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new AppException(BookingErrorCode.STATE_CONFLICT);
        }
        if (booking.getExpiresAt() == null || !now.isBefore(booking.getExpiresAt())) {
            throw new AppException(BookingErrorCode.HOLD_EXPIRED);
        }
    }

    private CheckoutResponse toResponse(Payment payment, Instant evaluatedAt) {
        CheckoutStatus status;
        if (payment.getProviderOrderRef() == null) {
            status = CheckoutStatus.NOT_CREATED;
        } else if (payment.getProviderExpiresAt() == null) {
            status = CheckoutStatus.UNAVAILABLE;
        } else if (!evaluatedAt.isBefore(payment.getProviderExpiresAt())) {
            status = CheckoutStatus.EXPIRED;
        } else {
            status = CheckoutStatus.READY;
        }
        return new CheckoutResponse(
                status,
                payment.getMethod(),
                payment.getProviderExpiresAt(),
                checkoutInstruction(payment),
                payment.getProviderOrderRef(),
                payment.getQrCodeUrl()
        );
    }

    private String checkoutInstruction(Payment payment) {
        if (payment.getMethod() == PaymentMethod.SIMULATOR) return SIMULATOR_INSTRUCTION;
        if (payment.getMethod() == PaymentMethod.BANK_TRANSFER
                && "SEPAY".equals(payment.getProvider())) return SEPAY_TEST_INSTRUCTION;
        return null;
    }
}
