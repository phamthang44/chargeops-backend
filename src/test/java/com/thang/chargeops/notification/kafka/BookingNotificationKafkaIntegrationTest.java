package com.thang.chargeops.notification.kafka;

import com.thang.chargeops.notification.model.NotificationAudience;
import com.thang.chargeops.notification.model.NotificationEventType;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Opt-in real broker test: reuses Kafka in the existing Compose stack. No DB or SMTP. */
@EnabledIfSystemProperty(named = "chargeops.kafka.integration", matches = "true")
@SpringBootTest(classes = BookingNotificationKafkaIntegrationTest.TransportContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.name=chargeops-kafka-transport-test",
        "app.notification.kafka.enabled=true",
        "app.notification.outbox.enabled=false",
        "app.notification.kafka.consumer.enabled=false",
        "app.notification.kafka.topic=chargeops.lab.booking.spring.v1",
        "app.notification.kafka.dead-letter-topic=chargeops.lab.booking.spring.dlt",
        "spring.kafka.admin.fail-fast=true",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.acks=all",
        "spring.kafka.producer.properties.enable.idempotence=true",
        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.enable-auto-commit=false",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.listener.ack-mode=record"
})
class BookingNotificationKafkaIntegrationTest {
    private static final String TOPIC = "chargeops.lab.booking.spring.v1";
    private static final String GROUP = "chargeops-lab-spring-" + UUID.randomUUID();

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers",
                () -> System.getProperty("chargeops.kafka.bootstrap-servers", "localhost:9092"));
        properties.add("spring.kafka.consumer.group-id", () -> GROUP);
    }

    @Autowired BookingNotificationPublisher publisher;
    @Autowired KafkaAdmin kafkaAdmin;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired Probe probe;

    @Test
    void springCreatesTopicsPublishesByBookingAndCommitsAfterListenerReturns() throws Exception {
        try (var admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            var descriptions = admin.describeTopics(List.of(TOPIC, "chargeops.lab.booking.spring.dlt"))
                    .allTopicNames().get(15, TimeUnit.SECONDS);
            assertThat(descriptions.values()).allSatisfy(topic -> assertThat(topic.partitions()).hasSize(3));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(listeners.getListenerContainer("booking-transport-probe").getAssignedPartitions()).hasSize(3));

            UUID bookingId = UUID.randomUUID();
            UUID recipientId = UUID.randomUUID();
            UUID stationId = UUID.randomUUID();
            var first = event(bookingId, stationId, recipientId);
            var second = event(bookingId, stationId, recipientId);
            var firstResult = publisher.publish(first).get(15, TimeUnit.SECONDS);
            var secondResult = publisher.publish(second).get(15, TimeUnit.SECONDS);
            assertThat(secondResult.getRecordMetadata().partition()).isEqualTo(firstResult.getRecordMetadata().partition());
            assertThat(secondResult.getRecordMetadata().offset()).isGreaterThan(firstResult.getRecordMetadata().offset());

            // Older synthetic records may exist on this shared lab topic. Match only this run's identities.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(probe.received).contains(first, second));
            assertThat(probe.keys).contains(bookingId.toString());
            var partition = new TopicPartition(TOPIC, secondResult.getRecordMetadata().partition());
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS);
                assertThat(offsets).containsKey(partition);
                assertThat(offsets.get(partition).offset()).isGreaterThan(secondResult.getRecordMetadata().offset());
            });
        }
    }

    private BookingNotificationEvent event(UUID booking, UUID station, UUID recipient) {
        return new BookingNotificationEvent(UUID.randomUUID(), 1, NotificationEventType.BOOKING_CONFIRMED,
                UUID.randomUUID() + ":BOOKING_CONFIRMED", booking, station, recipient, NotificationAudience.DRIVER,
                Instant.parse("2026-10-07T10:00:00Z"));
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({KafkaAutoConfiguration.class, JacksonAutoConfiguration.class})
    @Import(BookingNotificationKafkaConfiguration.class)
    static class TransportContext {
        @Bean Probe probe(ObjectMapper json) { return new Probe(json); }
    }

    static class Probe {
        private final ObjectMapper json;
        final BlockingQueue<BookingNotificationEvent> received = new LinkedBlockingQueue<>();
        final BlockingQueue<String> keys = new LinkedBlockingQueue<>();

        Probe(ObjectMapper json) { this.json = json; }

        @KafkaListener(id = "booking-transport-probe", topics = "${app.notification.kafka.topic}",
                groupId = "${spring.kafka.consumer.group-id}")
        void receive(ConsumerRecord<String, String> record) {
            var event = json.readValue(record.value(), BookingNotificationEvent.class);
            if (!event.bookingId().toString().equals(record.key())) {
                throw new IllegalArgumentException("Kafka key must match bookingId");
            }
            keys.add(record.key());
            received.add(event);
        }
    }
}
