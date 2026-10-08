package com.thang.chargeops.notification.email;

import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import static com.thang.chargeops.notification.email.NotificationEmailDeliveryStore.Failure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class BookingEmailDeliveryWorkerTest {
    @Test
    void classifiesDefiniteRejectionsAndAmbiguousTimeoutsWithoutParsingMessages() throws Exception {
        var failures = Map.of(
                new MailSendException("secret", new ConnectException()), Failure.SMTP_UNAVAILABLE,
                new MailAuthenticationException("secret"), Failure.SMTP_AUTH_REJECTED,
                new MailSendException("secret", new SocketTimeoutException()), Failure.SMTP_OUTCOME_UNKNOWN,
                new MailSendException("secret", new SMTPSendFailedException("DATA", 451, "secret", null, null, null, null)), Failure.SMTP_TRANSIENT_REJECTION,
                new MailSendException("secret", new SMTPSendFailedException("RCPT", 550, "secret", null, null, null, null)), Failure.SMTP_PERMANENT_REJECTION);
        failures.forEach((error, expected) -> assertThat(BookingNotificationEmailWorker.classifyFailure(error)).isEqualTo(expected));
        var accepted = new SMTPSendFailedException("QUIT", 421, "secret", null,
                new InternetAddress[]{new InternetAddress("accepted@example.test")}, null, null);
        assertThat(BookingNotificationEmailWorker.classifyFailure(new MailSendException("secret", accepted)))
                .isEqualTo(Failure.SMTP_OUTCOME_UNKNOWN);
        // JavaMailSender keeps per-message failures separately from Throwable.cause.
        assertThat(BookingNotificationEmailWorker.classifyFailure(new MailSendException(Map.of("synthetic", failures.keySet().stream()
                .filter(e -> e.getCause() instanceof ConnectException).findFirst().orElseThrow())))).isEqualTo(Failure.SMTP_UNAVAILABLE);
    }

    @Test
    void workerRejectsAmbientTransactionBeforeClaimOrMail() {
        var store = mock(NotificationEmailDeliveryStore.class);
        var sender = mock(JavaMailSender.class);
        var worker = new BookingNotificationEmailWorker(store, mock(BookingEmailRenderer.class), sender,
                "noreply@chargeops.test", "http://localhost:5173", "", 1, 5);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(worker::dispatchPending).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(store, sender);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            worker.shutdown();
        }
    }

    @Test
    void emailFlagIsIndependentOfKafkaAndMissingFlagDoesNotStartWorker() {
        var context = new ApplicationContextRunner().withUserConfiguration(BookingEmailDeliveryConfiguration.class)
                .withBean(NotificationEmailDeliveryStore.class, () -> mock(NotificationEmailDeliveryStore.class))
                .withBean(JavaMailSender.class, JavaMailSenderImpl::new)
                .withPropertyValues("app.frontend-url=http://localhost:5173", "app.notification.email.from=noreply@chargeops.test",
                        "app.notification.kafka.enabled=false");
        context.run(c -> assertThat(c).doesNotHaveBean(BookingNotificationEmailWorker.class));
        context.withPropertyValues("app.notification.email.enabled=false").run(c -> assertThat(c).doesNotHaveBean(BookingNotificationEmailWorker.class));
        context.withPropertyValues("app.notification.email.enabled=true").run(c -> assertThat(c)
                .hasSingleBean(BookingNotificationEmailWorker.class).hasSingleBean(BookingEmailRenderer.class));
    }
}
