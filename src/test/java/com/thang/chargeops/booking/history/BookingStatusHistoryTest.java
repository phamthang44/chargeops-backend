package com.thang.chargeops.booking.history;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandPayloadHasher;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.entity.Booking.PendingBookingSpec;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Connector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class BookingStatusHistoryTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

    @Test
    void createsUserAndSystemTransitionsWithConsistentActorData() {
        UserProfile actor = mock(UserProfile.class);
        Booking booking = pendingBooking(actor);
        BookingCommand command = BookingCommand.successful(
                actor,
                BookingCommandOperation.CREATE_BOOKING,
                UUID.randomUUID(),
                BookingCommandPayloadHasher.sha256("create"),
                booking,
                NOW
        );

        BookingStatusHistory creation = BookingStatusHistory.userTransition(
                command,
                BookingStatusActorType.DRIVER,
                new BookingStatusChange(null, BookingStatus.PENDING, null, NOW)
        );
        BookingStatusHistory expiration = BookingStatusHistory.systemTransition(
                booking,
                new BookingStatusChange(
                        BookingStatus.PENDING,
                        BookingStatus.EXPIRED,
                        BookingStatusReason.HOLD_EXPIRED,
                        NOW.plusSeconds(600)
                )
        );

        assertThat(creation.getFromStatus()).isNull();
        assertThat(creation.getToStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(creation.getActor()).isSameAs(actor);
        assertThat(creation.getCommand()).isSameAs(command);
        assertThat(expiration.getActorType()).isEqualTo(BookingStatusActorType.SYSTEM);
        assertThat(expiration.getActor()).isNull();
        assertThat(expiration.getCommand()).isNull();
        assertThat(expiration.getReason()).isEqualTo("HOLD_EXPIRED");
    }

    @Test
    void rejectsInvalidLifecycleTransition() {
        Booking booking = pendingBooking(mock(UserProfile.class));

        assertThatThrownBy(() -> BookingStatusHistory.systemTransition(
                booking,
                new BookingStatusChange(
                        BookingStatus.PENDING,
                        BookingStatus.COMPLETED,
                        null,
                        NOW
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Booking status transition");
    }

    private static Booking pendingBooking(UserProfile driver) {
        return Booking.createPending(PendingBookingSpec.builder()
                .driver(driver)
                .connector(mock(Connector.class))
                .startAt(NOW.plusSeconds(3600))
                .endAt(NOW.plusSeconds(7200))
                .totalAmount(new BigDecimal("126000"))
                .expiresAt(NOW.plusSeconds(600))
                .stationNameSnapshot("Station")
                .stationAddressSnapshot("Address")
                .chargePointCodeSnapshot("CP-01")
                .connectorCodeSnapshot("C-01")
                .build());
    }
}
