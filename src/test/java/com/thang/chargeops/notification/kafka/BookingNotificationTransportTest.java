package com.thang.chargeops.notification.kafka;

import com.thang.chargeops.notification.model.NotificationAudience;
import com.thang.chargeops.notification.model.NotificationEventType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingNotificationTransportTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void wireRoundTripKeepsIdentityAndUtcTimeWithoutEmailOrReceiptPayload() {
        var event = event();
        String payload = json.writeValueAsString(event);
        assertThat(json.readValue(payload, BookingNotificationEvent.class)).isEqualTo(event);
        assertThat(json.readTree(payload).properties()).hasSize(9);
        assertThat(payload).doesNotContain("email", "receipt", "title", "body");
    }

    @Test
    void rejectsMissingOrUnsupportedSchemaOnTheWire() {
        String payload = json.writeValueAsString(event());
        assertThatThrownBy(() -> json.readValue(payload.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                BookingNotificationEvent.class)).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> json.readValue(payload.replace("\"schemaVersion\":1,", ""),
                BookingNotificationEvent.class)).isInstanceOf(tools.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    void rejectsOtherAudiencesAndEventTypesUntilTheirSliceIsImplemented() {
        var e = event();
        assertThatThrownBy(() -> new BookingNotificationEvent(e.eventId(), 1, e.eventType(), e.sourceEventKey(),
                e.bookingId(), e.stationId(), e.recipientId(), NotificationAudience.OWNER, e.occurredAt()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BookingNotificationEvent(e.eventId(), 1, NotificationEventType.TICKET_ASSIGNED,
                e.sourceEventKey(), e.bookingId(), e.stationId(), e.recipientId(), e.audience(), e.occurredAt()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellationWireUsesTheSameConsumerWithItsOwnTrustedSource() {
        var e = event();
        var cancelled = new BookingNotificationEvent(e.eventId(), 1, NotificationEventType.BOOKING_CANCELLED,
                UUID.randomUUID() + ":BOOKING_CANCELLED", e.bookingId(), e.stationId(), e.recipientId(), e.audience(), e.occurredAt());
        var store = mock(com.thang.chargeops.notification.email.NotificationEmailDeliveryStore.class);
        new BookingNotificationEmailConsumer(store, json).receiveConfirmed(new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                "test", 0, 0, e.bookingId().toString(), json.writeValueAsString(cancelled)));
        verify(store).captureCancelled(cancelled);
        verify(store, never()).captureConfirmed(any());
        assertThat(json.readTree(json.writeValueAsString(cancelled)).size()).isEqualTo(9);
    }

    @Test
    void rejectsMissingDomainIdentityOrBlankDedupeKey() {
        var e = event();
        assertThatThrownBy(() -> new BookingNotificationEvent(e.eventId(), 1, e.eventType(), " ", e.bookingId(),
                e.stationId(), e.recipientId(), e.audience(), e.occurredAt())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BookingNotificationEvent(e.eventId(), 1, e.eventType(), e.sourceEventKey(), null,
                e.stationId(), e.recipientId(), e.audience(), e.occurredAt())).isInstanceOf(NullPointerException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishUsesBookingKeyAndPreservesAsyncFailureForOutboxRetry() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var failure = new IllegalStateException("broker unavailable");
        CompletableFuture<SendResult<String, String>> failed = CompletableFuture.failedFuture(failure);
        when(kafka.send(eq("test-topic"), anyString(), anyString())).thenReturn(failed);
        var publisher = new BookingNotificationPublisher(kafka, json, "test-topic");
        var e = event();
        var result = publisher.publish(e);
        assertThat(result).isSameAs(failed);
        assertThatThrownBy(result::join).isInstanceOf(CompletionException.class).hasCause(failure);
        verify(kafka).send("test-topic", e.bookingId().toString(), json.writeValueAsString(e));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusesNetworkPublishInsideDatabaseTransaction() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var publisher = new BookingNotificationPublisher(kafka, json, "test-topic");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> publisher.publish(event())).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(kafka);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void transportIsDisabledWhenFeatureFlagIsAbsentOrFalse() {
        var context = new ApplicationContextRunner().withUserConfiguration(BookingNotificationKafkaConfiguration.class);
        context.run(c -> assertThat(c).doesNotHaveBean(BookingNotificationPublisher.class).doesNotHaveBean("bookingNotificationTopics"));
        context.withPropertyValues("app.notification.kafka.enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(BookingNotificationPublisher.class));
    }

    private BookingNotificationEvent event() {
        UUID receipt = UUID.randomUUID();
        return new BookingNotificationEvent(UUID.randomUUID(), 1, NotificationEventType.BOOKING_CONFIRMED,
                receipt + ":BOOKING_CONFIRMED", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                NotificationAudience.DRIVER, Instant.parse("2026-10-07T10:00:00Z"));
    }

    @Test
    void consumerRejectsMalformedContractAndKeyBeforeTouchingDeliveryStore() {
        var deliveries = mock(com.thang.chargeops.notification.email.NotificationEmailDeliveryStore.class);
        var consumer = new BookingNotificationEmailConsumer(deliveries, json);
        var event = event();
        String payload = json.writeValueAsString(event);
        for (String bad : java.util.List.of("{}", "not-json", payload + " {}",
                payload.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                payload.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                payload.substring(0, payload.length() - 1) + ",\"email\":\"private\"}")) {
            assertThatThrownBy(() -> consumer.receiveConfirmed(new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                    "topic", 0, 0L, event.bookingId().toString(), bad))).isInstanceOf(InvalidBookingNotificationException.class)
                    .hasMessage("INVALID_EVENT_CONTRACT_OR_KEY");
        }
        assertThatThrownBy(() -> consumer.receiveConfirmed(new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                "topic", 0, 0L, "wrong-booking", payload))).isInstanceOf(InvalidBookingNotificationException.class);
        verifyNoInteractions(deliveries);
    }

    @Test
    void consumerPropagatesDatabaseFailureToRetainOffset() {
        var deliveries = mock(com.thang.chargeops.notification.email.NotificationEmailDeliveryStore.class);
        var event = event();
        var failure = new org.springframework.dao.DataAccessResourceFailureException("unavailable");
        when(deliveries.captureConfirmed(event)).thenThrow(failure);
        var consumer = new BookingNotificationEmailConsumer(deliveries, json);
        assertThatThrownBy(() -> consumer.receiveConfirmed(new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                "topic", 0, 0L, event.bookingId().toString(), json.writeValueAsString(event)))).isSameAs(failure);
    }

    @Test
    void consumerFlagDoesNotDisableProducerOrStartIngestionWhenPaused() {
        var context = new ApplicationContextRunner().withUserConfiguration(BookingNotificationKafkaConfiguration.class)
                .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .withBean(org.springframework.kafka.core.ConsumerFactory.class, () -> mock(org.springframework.kafka.core.ConsumerFactory.class))
                .withBean(ObjectMapper.class, () -> json)
                .withBean(com.thang.chargeops.notification.email.NotificationEmailDeliveryStore.class,
                        () -> mock(com.thang.chargeops.notification.email.NotificationEmailDeliveryStore.class))
                .withPropertyValues("app.notification.kafka.enabled=true", "app.notification.outbox.enabled=false");
        context.withPropertyValues("app.notification.kafka.consumer.enabled=false").run(c -> assertThat(c)
                .hasSingleBean(BookingNotificationPublisher.class).doesNotHaveBean(BookingNotificationEmailConsumer.class)
                .doesNotHaveBean("bookingEmailKafkaListenerContainerFactory"));
        context.withPropertyValues("app.notification.kafka.consumer.enabled=true").run(c -> {
            assertThat(c).hasSingleBean(BookingNotificationEmailConsumer.class).hasBean("bookingEmailKafkaListenerContainerFactory");
            var factory = c.getBean("bookingEmailKafkaListenerContainerFactory",
                    org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory.class);
            assertThat(factory.getContainerProperties().getKafkaConsumerProperties()).containsEntry("enable.auto.commit", "false");
        });
    }

    @Test
    void outboxWorkerCanBePausedWithoutDisablingKafkaTransport() {
        var context = new ApplicationContextRunner().withUserConfiguration(BookingNotificationKafkaConfiguration.class)
                .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .withBean(ObjectMapper.class, () -> json)
                .withBean(com.thang.chargeops.notification.outbox.NotificationOutboxStore.class,
                        () -> mock(com.thang.chargeops.notification.outbox.NotificationOutboxStore.class))
                .withPropertyValues("app.notification.kafka.enabled=true", "app.notification.kafka.consumer.enabled=false");
        context.withPropertyValues("app.notification.outbox.enabled=false").run(c -> assertThat(c)
                .hasSingleBean(BookingNotificationPublisher.class)
                .doesNotHaveBean(com.thang.chargeops.notification.outbox.BookingNotificationOutboxWorker.class));
        context.withPropertyValues("app.notification.outbox.enabled=true").run(c -> assertThat(c)
                .hasSingleBean(com.thang.chargeops.notification.outbox.BookingNotificationOutboxWorker.class));
    }
}
