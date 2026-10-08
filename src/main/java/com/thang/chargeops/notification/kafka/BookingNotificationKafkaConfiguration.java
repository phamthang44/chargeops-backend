package com.thang.chargeops.notification.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.notification.kafka", name = "enabled", havingValue = "true")
public class BookingNotificationKafkaConfiguration {
    public static final String TOPIC = "chargeops.booking.notifications.v1";
    public static final String EMAIL_DLT = "chargeops.booking.notifications.email.dlt";

    @Bean
    @ConditionalOnProperty(prefix = "app.notification.kafka.consumer", name = "enabled", havingValue = "true", matchIfMissing = true)
    BookingNotificationEmailConsumer bookingNotificationEmailConsumer(
            com.thang.chargeops.notification.email.NotificationEmailDeliveryStore deliveries, ObjectMapper json) {
        return new BookingNotificationEmailConsumer(deliveries, json);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.notification.kafka.consumer", name = "enabled", havingValue = "true", matchIfMissing = true)
    org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Object, Object> bookingEmailKafkaListenerContainerFactory(
            org.springframework.kafka.core.ConsumerFactory<Object, Object> consumers,
            KafkaTemplate<String, String> kafka,
            @Value("${app.notification.kafka.dead-letter-topic:" + EMAIL_DLT + "}") String deadLetterTopic) {
        var factory = new org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Object, Object>();
        factory.setConsumerFactory(consumers);
        // Enforce ingestion's contract locally without mutating the shared ConsumerFactory.
        var ingestionProperties = new java.util.Properties();
        ingestionProperties.setProperty(org.apache.kafka.clients.consumer.ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        ingestionProperties.setProperty(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringDeserializer.class.getName());
        ingestionProperties.setProperty(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringDeserializer.class.getName());
        factory.getContainerProperties().setKafkaConsumerProperties(ingestionProperties);
        factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setMissingTopicsFatal(true);
        var recoverer = new org.springframework.kafka.listener.DeadLetterPublishingRecoverer(kafka,
                (record, failure) -> new org.apache.kafka.common.TopicPartition(deadLetterTopic, record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(java.time.Duration.ofSeconds(30));
        var handler = new org.springframework.kafka.listener.DefaultErrorHandler(recoverer,
                new org.springframework.util.backoff.FixedBackOff(1000, 2));
        handler.addNotRetryableExceptions(InvalidBookingNotificationException.class);
        handler.setBackOffFunction((record, failure) -> {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.springframework.dao.DataAccessException
                        || cause instanceof org.springframework.transaction.TransactionException) {
                    return new org.springframework.util.backoff.FixedBackOff(5000,
                            org.springframework.util.backoff.FixedBackOff.UNLIMITED_ATTEMPTS);
                }
            }
            return new org.springframework.util.backoff.FixedBackOff(1000, 2);
        });
        factory.setCommonErrorHandler(handler);
        return factory;
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.notification.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
    com.thang.chargeops.notification.outbox.BookingNotificationOutboxWorker bookingNotificationOutboxWorker(
            com.thang.chargeops.notification.outbox.NotificationOutboxStore store,
            BookingNotificationPublisher publisher, ObjectMapper json,
            @Value("${app.notification.outbox.batch-size:10}") int batchSize,
            @Value("${app.notification.outbox.ack-timeout-seconds:30}") int ackTimeoutSeconds) {
        return new com.thang.chargeops.notification.outbox.BookingNotificationOutboxWorker(
                store, publisher, json, batchSize, ackTimeoutSeconds);
    }

    @Bean
    KafkaAdmin.NewTopics bookingNotificationTopics(
            @Value("${app.notification.kafka.topic:" + TOPIC + "}") String topic,
            @Value("${app.notification.kafka.dead-letter-topic:" + EMAIL_DLT + "}") String deadLetterTopic,
            @Value("${app.notification.kafka.partitions:3}") int partitions,
            @Value("${app.notification.kafka.replication-factor:1}") int replicas) {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(topic).partitions(partitions).replicas(replicas)
                        .config("retention.ms", "604800000").build(),
                TopicBuilder.name(deadLetterTopic).partitions(partitions).replicas(replicas)
                        .config("retention.ms", "604800000").build());
    }

    @Bean
    BookingNotificationPublisher bookingNotificationPublisher(
            KafkaTemplate<String, String> kafka, ObjectMapper json,
            @Value("${app.notification.kafka.topic:" + TOPIC + "}") String topic) {
        return new BookingNotificationPublisher(kafka, json, topic);
    }
}
