package com.thang.chargeops.notification.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/** Durable email backlog can drain independently of the Kafka transport flag. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.notification.email", name = "enabled", havingValue = "true")
public class BookingEmailDeliveryConfiguration {
    @Bean
    BookingEmailRenderer bookingEmailRenderer() {
        return new BookingEmailRenderer();
    }

    @Bean
    BookingNotificationEmailWorker bookingNotificationEmailWorker(
            NotificationEmailDeliveryStore store, BookingEmailRenderer renderer, JavaMailSender mail,
            @Value("${app.notification.email.from}") String from,
            @Value("${app.frontend-url}") String frontendUrl,
            @Value("${app.notification.email.support-contact:}") String supportContact,
            @Value("${app.notification.email.batch-size:10}") int batchSize,
            @Value("${app.notification.email.max-attempts:5}") int maxAttempts) {
        return new BookingNotificationEmailWorker(store, renderer, mail, from, frontendUrl, supportContact,
                batchSize, maxAttempts);
    }
}
