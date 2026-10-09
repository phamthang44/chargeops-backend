package com.thang.chargeops.notification.outbox;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.notification.entity.AppNotification;
import com.thang.chargeops.notification.event.NotificationHintEvent;
import com.thang.chargeops.notification.kafka.BookingNotificationEvent;
import com.thang.chargeops.notification.model.NotificationAudience;
import com.thang.chargeops.notification.model.NotificationContent;
import com.thang.chargeops.notification.model.NotificationEventType;
import com.thang.chargeops.notification.model.NotificationI18nKey;
import com.thang.chargeops.notification.repository.AppNotificationRepository;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Writes durable intents only. Never calls Kafka or SMTP inside the core transaction. */
@Service
@RequiredArgsConstructor
public class BookingConfirmationOutboxWriter {
    private final AppNotificationRepository notifications;
    private final NotificationOutboxRepository outbox;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordStationCancelled(BookingCommand command) {
        var booking = command.getBooking();
        boolean owner = command.getOperation() == BookingCommandOperation.OWNER_CANCEL_BOOKING
                && Objects.equals(command.getActor().getId(), booking.getConnector().getChargePoint().getStation().getOwner().getId());
        boolean admin = command.getOperation() == BookingCommandOperation.ADMIN_REVIEW_STATION_FAILURE;
        if (command.getId() == null || (!owner && !admin) || booking.getStatus() != BookingStatus.CANCELLED
                || !"STATION_FAILURE".equals(booking.getCancellationReason())
                || !Objects.equals(command.getCreatedAt(), booking.getCancelledAt())) {
            throw new IllegalArgumentException("Station cancellation requires its persisted transition command");
        }
        String key = command.getId() + ":BOOKING_CANCELLED";
        var content = new NotificationContent(NotificationI18nKey.BOOKING_CANCELLED_TITLE,
                NotificationI18nKey.format(NotificationI18nKey.BOOKING_STATE_MESSAGE,
                        Map.of("code", booking.getBookingCode() == null ? booking.getId().toString() : booking.getBookingCode())), command.getCreatedAt());
        var notice = AppNotification.driverBooking(booking, NotificationEventType.BOOKING_CANCELLED, key, content, null);
        // Reuse the atomic inbox insert: do not acquire a Driver write lock after domain/actor locks.
        if (notifications.insertBookingIntentIfAbsent(notice) > 0) {
            emitCreatedHint(booking, notice);
        }
        if (outbox.existsBySourceEventKeyAndRecipientIdAndAudienceAndChannel(key, notice.getRecipientId(), "DRIVER", "EMAIL")) return;
        var event = new BookingNotificationEvent(UUID.randomUUID(), BookingNotificationEvent.SCHEMA_VERSION,
                NotificationEventType.BOOKING_CANCELLED, key, booking.getId(), notice.getStationId(),
                notice.getRecipientId(), NotificationAudience.DRIVER, command.getCreatedAt());
        outbox.save(NotificationOutbox.cancelled(event, command.getId(), json.writeValueAsString(event)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCancelled(BookingCommand command) {
        var booking = command.getBooking();
        if (command.getId() == null
                || command.getOperation() != BookingCommandOperation.CANCEL_BOOKING
                || booking.getStatus() != BookingStatus.CANCELLED
                || !"DRIVER_CANCELLED".equals(booking.getCancellationReason())
                || !Objects.equals(command.getActor().getId(), booking.getDriver().getId())) {
            throw new IllegalArgumentException("Cancellation intent requires the committed Driver cancellation command");
        }
        String key = command.getId() + ":BOOKING_CANCELLED";
        UUID recipient = booking.getDriver().getId();
        // Driver is already locked by cancelBooking. Ordinary JPA save is serialized with retries.
        if (outbox.existsBySourceEventKeyAndRecipientIdAndAudienceAndChannel(key, recipient, "DRIVER", "EMAIL")) return;
        var event = new BookingNotificationEvent(UUID.randomUUID(), BookingNotificationEvent.SCHEMA_VERSION,
                NotificationEventType.BOOKING_CANCELLED, key, booking.getId(),
                booking.getConnector().getChargePoint().getStation().getId(), recipient,
                NotificationAudience.DRIVER, command.getCreatedAt());
        outbox.save(NotificationOutbox.cancelled(event, command.getId(), json.writeValueAsString(event)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordConfirmed(Booking booking, PaymentTransaction receipt, Instant occurredAt) {
        if (receipt == null || receipt.getId() == null
                || receipt.getApplicationClassification() != PaymentApplicationClassification.APPLIED
                || receipt.getPayment() == null || booking == null || booking.getId() == null
                || booking.getStatus() != BookingStatus.CONFIRMED
                || !Objects.equals(receipt.getPayment().getBooking().getId(), booking.getId())) {
            throw new IllegalArgumentException("Confirmation intent requires the applied receipt for this confirmed booking");
        }
        String sourceKey = receipt.getId() + ":BOOKING_CONFIRMED";
        var content = new NotificationContent(NotificationI18nKey.BOOKING_CONFIRMED_TITLE,
                NotificationI18nKey.format(NotificationI18nKey.BOOKING_STATE_MESSAGE,
                        Map.of("code", booking.getBookingCode() == null ? booking.getId().toString() : booking.getBookingCode())), occurredAt);
        var notice = AppNotification.driverBooking(booking, NotificationEventType.BOOKING_CONFIRMED,
                sourceKey, content, null);
        // Atomic insert avoids taking a recipient write lock after Connector -> Booking -> Payment.
        // PostgreSQL FK key-share locks remain compatible with profile FOR NO KEY UPDATE locks.
        if (notifications.insertBookingIntentIfAbsent(notice) > 0) {
            emitCreatedHint(booking, notice);
        }
        var event = new BookingNotificationEvent(UUID.randomUUID(), BookingNotificationEvent.SCHEMA_VERSION,
                notice.getEventType(), sourceKey, booking.getId(), notice.getStationId(), notice.getRecipientId(),
                NotificationAudience.DRIVER, occurredAt);
        outbox.enqueueIfAbsent(event, receipt.getId(), json.writeValueAsString(event));
    }

    /**
     * Publishes the insert hint inside the caller's transaction (delivered AFTER_COMMIT).
     * Every notice built here is DRIVER-audience, so the keycloak id comes from the driver
     * already sitting in the persistence context — no extra query, no extra lock.
     */
    private void emitCreatedHint(Booking booking, AppNotification notice) {
        events.publishEvent(NotificationHintEvent.created(
                booking.getDriver().getKeycloakId(), notice.getSourceEventKey()));
    }
}
