package com.thang.chargeops.booking;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.entity.Booking.PendingBookingSpec;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingHoldCoordinator;
import com.thang.chargeops.booking.service.model.HoldPreparationContext;
import com.thang.chargeops.profile.entity.UserProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@TestComponent
@RequiredArgsConstructor
public class TestHoldTransactionalHarness {

    private final BookingHoldCoordinator coordinator;
    private final BookingRepository bookingRepository;

    @Transactional
    public Booking attemptHold(
            UUID driverId,
            UUID connectorId,
            Instant startAt,
            Instant endAt,
            String bookingCode
    ) {
        UserProfile lockedDriver = coordinator.lockDriver(driverId);
        HoldPreparationContext context = coordinator.prepareUnderLock(
                lockedDriver, connectorId, startAt, endAt);
        Booking booking = Booking.createPending(new PendingBookingSpec(
                context.driver(),
                context.connector(),
                startAt,
                endAt,
                BigDecimal.valueOf(100_000),
                context.decisionAt().plus(Duration.ofMinutes(10)),
                bookingCode,
                null,
                null,
                "Test Station",
                "Test Address",
                "CP-TEST",
                "CON-TEST",
                null
        ));
        return bookingRepository.saveAndFlush(booking);
    }
}
