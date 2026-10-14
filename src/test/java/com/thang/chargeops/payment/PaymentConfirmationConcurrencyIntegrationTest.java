package com.thang.chargeops.payment;

import com.thang.chargeops.notification.email.BookingNotificationEmailWorker;
import com.thang.chargeops.notification.email.BookingEmailRenderer;
import com.thang.chargeops.notification.email.BookingConfirmedEmailModel;
import com.thang.chargeops.notification.email.NotificationEmailDeliveryStore;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.MailSendException;
import jakarta.mail.internet.MimeMessage;
import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusHistoryRepository;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.impl.BookingExpirationServiceImpl;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import com.thang.chargeops.payment.model.PaymentReceiptResult;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.payment.service.impl.PaymentConfirmationServiceImpl;
import com.thang.chargeops.payment.service.impl.OwnerFinanceServiceImpl;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.station.repository.ConnectorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import({BookingStatusHistoryRecorder.class, com.thang.chargeops.notification.email.BookingEventEmailSource.class, com.thang.chargeops.notification.outbox.BookingConfirmationOutboxWriter.class,
        com.thang.chargeops.notification.outbox.NotificationOutboxStore.class,
        com.thang.chargeops.notification.email.NotificationEmailDeliveryStore.class})
@org.springframework.boot.autoconfigure.ImportAutoConfiguration(org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentConfirmationConcurrencyIntegrationTest {

    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-21T03:05:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("120000.00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired
    private ConnectorRepository connectorRepository;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @MockitoSpyBean
    private PaymentTransactionRepository transactionRepository;
    @Autowired
    private BookingStatusHistoryRepository historyRepository;
    @Autowired
    private BookingStatusHistoryRecorder historyRecorder;
    @Autowired
    private com.thang.chargeops.notification.outbox.BookingConfirmationOutboxWriter confirmationOutboxWriter;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private com.thang.chargeops.notification.outbox.NotificationOutboxStore outboxStore;
    @Autowired
    private ObjectMapper outboxJson;

    @MockitoSpyBean
    private com.thang.chargeops.notification.email.NotificationEmailDeliveryStore emailDeliveries;

    private Fixture smtpFixture() {
        jdbcTemplate.update("UPDATE notification_email_deliveries SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE status = 'PENDING'");
        jdbcTemplate.update("UPDATE notification_email_deliveries SET lease_until = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE status = 'PROCESSING'");
        Fixture fixture = emailFixture();
        jdbcTemplate.update("UPDATE bookings SET booking_code = ? WHERE id = ?", "BK-SMTP-" + UUID.randomUUID().toString().substring(0, 16), fixture.bookingId());
        emailDeliveries.captureConfirmed(storedEmailEvent(fixture));
        return fixture;
    }

    private UUID deliveryId(Fixture fixture) {
        return jdbcTemplate.queryForObject("SELECT d.delivery_id FROM notification_email_deliveries d JOIN notification_outbox o ON o.event_id = d.event_id WHERE o.booking_id = ?", UUID.class, fixture.bookingId());
    }

    private java.util.Map<String, Object> deliveryState(Fixture fixture) {
        return jdbcTemplate.queryForMap("SELECT status, attempt_count, send_started_at, sent_at, sanitized_last_error, skip_reason, next_attempt_at > CURRENT_TIMESTAMP AS delayed FROM notification_email_deliveries WHERE delivery_id = ?", deliveryId(fixture));
    }

    private BookingNotificationEmailWorker smtpWorker(JavaMailSenderImpl sender, BookingEmailRenderer renderer, int attempts) {
        return new BookingNotificationEmailWorker(emailDeliveries, renderer, sender, "noreply@chargeops.test",
                "http://localhost:5173", "support@chargeops.test", 1, attempts);
    }

    @Test
    void smtpSuccessUsesLatestProfileAndMultipartTemplateAfterDurableStart() throws Exception {
        var fixture = smtpFixture();
        jdbcTemplate.update("UPDATE user_profile SET email = ?, display_name = ? WHERE id = (SELECT driver_id FROM bookings WHERE id = ?)",
                "latest-" + UUID.randomUUID() + "@example.test", "Nguyễn An <script>", fixture.bookingId());
        var sender = org.mockito.Mockito.spy(new JavaMailSenderImpl());
        var messages = new java.util.ArrayList<MimeMessage>();
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(deliveryState(fixture)).containsEntry("status", "PROCESSING");
            assertThat(deliveryState(fixture).get("send_started_at")).isNotNull();
            assertThat(deliveryState(fixture).get("sent_at")).isNull();
            messages.add(invocation.getArgument(0));
            return null;
        }).when(sender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
        var worker = smtpWorker(sender, new BookingEmailRenderer(), 5);
        try { worker.dispatchPending(); worker.dispatchPending(); } finally { worker.shutdown(); }
        assertThat(messages).hasSize(1);
        var message = messages.getFirst();
        var expectedEmail = jdbcTemplate.queryForObject("SELECT u.email FROM user_profile u JOIN bookings b ON b.driver_id = u.id WHERE b.id = ?", String.class, fixture.bookingId());
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(expectedEmail);
        assertThat(message.getSubject()).contains("Đã xác nhận booking");
        assertThat(message.getContentType()).contains("multipart/");
        var encoded = new java.io.ByteArrayOutputStream();
        message.writeTo(encoded);
        assertThat(encoded.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("text/plain", "text/html", "Message-ID: <booking-email." + deliveryId(fixture));
        assertThat(deliveryState(fixture)).containsEntry("status", "SENT").containsEntry("attempt_count", 1);
        assertThat(deliveryState(fixture).get("sent_at")).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_notifications WHERE booking_id = ? AND read_at IS NULL AND dismissed_at IS NULL", Long.class, fixture.bookingId())).isOne();
    }

    @Test
    void smtpConnectionFailureRetriesWithStableMessageIdAndBoundedBackoff() {
        var fixture = smtpFixture();
        var sender = org.mockito.Mockito.spy(new JavaMailSenderImpl());
        var ids = new java.util.ArrayList<String>();
        org.mockito.Mockito.doAnswer(invocation -> {
            MimeMessage message = invocation.getArgument(0);
            ids.add(message.getMessageID());
            if (ids.size() == 1) throw new MailSendException("private address", new java.net.ConnectException("credential"));
            return null;
        }).when(sender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
        var worker = smtpWorker(sender, new BookingEmailRenderer(), 5);
        try {
            worker.dispatchPending();
            assertThat(deliveryState(fixture)).containsEntry("status", "PENDING").containsEntry("attempt_count", 1)
                    .containsEntry("sanitized_last_error", "SMTP_UNAVAILABLE").containsEntry("delayed", true).containsEntry("send_started_at", null);
            worker.dispatchPending();
            assertThat(ids).hasSize(1);
            jdbcTemplate.update("UPDATE notification_email_deliveries SET next_attempt_at = CURRENT_TIMESTAMP WHERE delivery_id = ?", deliveryId(fixture));
            worker.dispatchPending();
            assertThat(ids).hasSize(2);
            assertThat(ids.getFirst()).isEqualTo(ids.getLast());
            assertThat(deliveryState(fixture)).containsEntry("status", "SENT").containsEntry("attempt_count", 2);
        } finally { worker.shutdown(); }
    }

    @Test
    void smtpPermanentRejectionFailsAndTimeoutRemainsUnknownWithoutResend() {
        for (boolean timeout : List.of(false, true)) {
            var fixture = smtpFixture();
            var sender = org.mockito.Mockito.spy(new JavaMailSenderImpl());
            var cause = timeout ? new java.net.SocketTimeoutException("private data")
                    : new org.eclipse.angus.mail.smtp.SMTPSendFailedException("DATA", 550, "private address", null, null, null, null);
            org.mockito.Mockito.doThrow(new MailSendException("private payload", cause)).when(sender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
            var worker = smtpWorker(sender, new BookingEmailRenderer(), 5);
            try { worker.dispatchPending(); worker.dispatchPending(); } finally { worker.shutdown(); }
            assertThat(deliveryState(fixture)).containsEntry("status", timeout ? "UNKNOWN" : "FAILED")
                    .containsEntry("sanitized_last_error", timeout ? "SMTP_OUTCOME_UNKNOWN" : "SMTP_PERMANENT_REJECTION")
                    .containsEntry("sent_at", null);
            org.mockito.Mockito.verify(sender, org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
        }
    }

    @Test
    void smtpPreStartLeaseRecoversButStartedLeaseBecomesUnknownAndRejectsStaleToken() {
        var fixture = smtpFixture();
        var first = emailDeliveries.claimDelivery(180, 5).orElseThrow();
        assertThat(emailDeliveries.claimDelivery(180, 5)).isEmpty();
        jdbcTemplate.update("UPDATE notification_email_deliveries SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE delivery_id = ?", first.deliveryId());
        var second = emailDeliveries.claimDelivery(180, 5).orElseThrow();
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(emailDeliveries.markSent(first)).isFalse();
        var model = emailDeliveries.prepare(second, "http://localhost:5173", "").orElseThrow();
        assertThat(emailDeliveries.beginSend(second, model, "http://localhost:5173", "", 5)).isTrue();
        jdbcTemplate.update("UPDATE notification_email_deliveries SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE delivery_id = ?", second.deliveryId());
        assertThat(emailDeliveries.claimDelivery(180, 5)).isEmpty();
        assertThat(deliveryState(fixture)).containsEntry("status", "UNKNOWN").containsEntry("sanitized_last_error", "SMTP_LEASE_EXPIRED_AFTER_START");
        assertThat(emailDeliveries.markSent(second)).isFalse();
    }

    @Test
    void smtpRechecksSuspensionCancellationAndRecipientScopeBeforeSending() {
        for (String change : List.of("suspended", "cancelled", "recipient")) {
            var fixture = smtpFixture();
            if (change.equals("suspended")) jdbcTemplate.update("UPDATE user_profile SET status = 'SUSPENDED' WHERE id = (SELECT driver_id FROM bookings WHERE id = ?)", fixture.bookingId());
            if (change.equals("cancelled")) jdbcTemplate.update("UPDATE bookings SET status = 'CANCELLED' WHERE id = ?", fixture.bookingId());
            if (change.equals("recipient")) jdbcTemplate.update("UPDATE bookings SET driver_id = (SELECT s.owner_id FROM stations s JOIN charge_points cp ON cp.station_id = s.id JOIN connectors c ON c.charge_point_id = cp.id WHERE c.id = ?) WHERE id = ?", fixture.connectorId(), fixture.bookingId());
            var sender = org.mockito.Mockito.spy(new JavaMailSenderImpl());
            var worker = smtpWorker(sender, new BookingEmailRenderer(), 5);
            try { worker.dispatchPending(); } finally { worker.shutdown(); }
            assertThat(deliveryState(fixture)).containsEntry("status", "SKIPPED").containsEntry("send_started_at", null);
            org.mockito.Mockito.verify(sender, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
        }
    }

    @Test
    void smtpScopeRevokedDuringRenderingNeverStartsTransport() {
        var fixture = smtpFixture();
        var renderer = org.mockito.Mockito.spy(new BookingEmailRenderer());
        org.mockito.Mockito.doAnswer(invocation -> {
            jdbcTemplate.update("UPDATE user_profile SET status = 'SUSPENDED' WHERE id = (SELECT driver_id FROM bookings WHERE id = ?)", fixture.bookingId());
            return invocation.callRealMethod();
        }).when(renderer).renderBookingConfirmed(org.mockito.ArgumentMatchers.any(BookingConfirmedEmailModel.class));
        var sender = org.mockito.Mockito.spy(new JavaMailSenderImpl());
        var worker = smtpWorker(sender, renderer, 5);
        try { worker.dispatchPending(); } finally { worker.shutdown(); }
        assertThat(deliveryState(fixture)).containsEntry("status", "SKIPPED").containsEntry("skip_reason", "RECIPIENT_INACTIVE");
        org.mockito.Mockito.verify(sender, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
    }

    @Test
    void smtpRetryLimitEndsInFailedAndInvalidAddressNeverStartsSmtp() {
        var fixture = smtpFixture();
        var sender = org.mockito.Mockito.spy(new JavaMailSenderImpl());
        org.mockito.Mockito.doThrow(new MailSendException("unavailable", new java.net.ConnectException())).when(sender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
        var worker = smtpWorker(sender, new BookingEmailRenderer(), 2);
        try {
            worker.dispatchPending();
            jdbcTemplate.update("UPDATE notification_email_deliveries SET next_attempt_at = CURRENT_TIMESTAMP WHERE delivery_id = ?", deliveryId(fixture));
            worker.dispatchPending(); worker.dispatchPending();
        } finally { worker.shutdown(); }
        assertThat(deliveryState(fixture)).containsEntry("status", "FAILED").containsEntry("attempt_count", 2).containsEntry("sanitized_last_error", "SMTP_RETRY_EXHAUSTED");
        var invalid = smtpFixture();
        jdbcTemplate.update("UPDATE user_profile SET email = 'bad,email' WHERE id = (SELECT driver_id FROM bookings WHERE id = ?)", invalid.bookingId());
        var invalidSender = org.mockito.Mockito.spy(new JavaMailSenderImpl());
        var invalidWorker = smtpWorker(invalidSender, new BookingEmailRenderer(), 5);
        try { invalidWorker.dispatchPending(); } finally { invalidWorker.shutdown(); }
        assertThat(deliveryState(invalid)).containsEntry("status", "FAILED").containsEntry("send_started_at", null).containsEntry("sanitized_last_error", "SMTP_INVALID_MESSAGE");
        org.mockito.Mockito.verify(invalidSender, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
    }

    @Test
    void smtpClaimSkipsLockedDeliveryAndConcurrentClaimsHaveOneOwner() throws Exception {
        var fixture = smtpFixture();
        try (var executor = Executors.newFixedThreadPool(2)) {
            inNewTransaction(() -> {
                jdbcTemplate.queryForObject("SELECT delivery_id FROM notification_email_deliveries WHERE delivery_id = ? FOR UPDATE", UUID.class, deliveryId(fixture));
                try { assertThat(executor.submit(() -> emailDeliveries.claimDelivery(180, 5)).get(3, TimeUnit.SECONDS)).isEmpty(); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
                return null;
            });
            var gate = new CountDownLatch(1);
            var first = executor.submit(() -> { gate.await(); return emailDeliveries.claimDelivery(180, 5); });
            var second = executor.submit(() -> { gate.await(); return emailDeliveries.claimDelivery(180, 5); });
            gate.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS).isPresent(), second.get(5, TimeUnit.SECONDS).isPresent()))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void smtpAcceptedButDatabaseMarkFailsBecomesUnknownAfterLeaseWithoutResending() {
        var fixture = smtpFixture();
        var sender = org.mockito.Mockito.spy(new JavaMailSenderImpl());
        org.mockito.Mockito.doNothing().when(sender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("DB outage"))
                .when(emailDeliveries).markSent(org.mockito.ArgumentMatchers.any(NotificationEmailDeliveryStore.Claim.class));
        var worker = smtpWorker(sender, new BookingEmailRenderer(), 5);
        try {
            assertThatThrownBy(worker::dispatchPending).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
            assertThat(deliveryState(fixture)).containsEntry("status", "PROCESSING");
            assertThat(deliveryState(fixture).get("send_started_at")).isNotNull();
            jdbcTemplate.update("UPDATE notification_email_deliveries SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE delivery_id = ?", deliveryId(fixture));
            worker.dispatchPending();
        } finally { worker.shutdown(); }
        assertThat(deliveryState(fixture)).containsEntry("status", "UNKNOWN");
        org.mockito.Mockito.verify(sender, org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
    }

    @Test
    void smtpAddressChangedDuringRenderingDefersAndNextAttemptUsesCurrentAddress() {
        var fixture = smtpFixture();
        var changed = "new-recipient-" + UUID.randomUUID() + "@example.test";
        var renderer = org.mockito.Mockito.spy(new BookingEmailRenderer());
        var renders = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (renders.incrementAndGet() == 1) jdbcTemplate.update("UPDATE user_profile SET email = ? WHERE id = (SELECT driver_id FROM bookings WHERE id = ?)", changed, fixture.bookingId());
            return invocation.callRealMethod();
        }).when(renderer).renderBookingConfirmed(org.mockito.ArgumentMatchers.any(BookingConfirmedEmailModel.class));
        var sender = org.mockito.Mockito.spy(new JavaMailSenderImpl());
        org.mockito.Mockito.doAnswer(invocation -> {
            MimeMessage message = invocation.getArgument(0);
            assertThat(message.getAllRecipients()[0].toString()).isEqualTo(changed);
            return null;
        }).when(sender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
        var worker = smtpWorker(sender, renderer, 5);
        try {
            worker.dispatchPending();
            assertThat(deliveryState(fixture)).containsEntry("status", "PENDING").containsEntry("send_started_at", null)
                    .containsEntry("sanitized_last_error", "EMAIL_CONTEXT_CHANGED");
            org.mockito.Mockito.verify(sender, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
            jdbcTemplate.update("UPDATE notification_email_deliveries SET next_attempt_at = CURRENT_TIMESTAMP WHERE delivery_id = ?", deliveryId(fixture));
            worker.dispatchPending();
        } finally { worker.shutdown(); }
        assertThat(deliveryState(fixture)).containsEntry("status", "SENT").containsEntry("attempt_count", 2);
    }

    @Test
    void smtpScheduledPollDoesNotQueueWhileSendBlocksAndShutdownLeavesUnknown() throws Exception {
        var fixture = smtpFixture();
        var entered = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var sender = org.mockito.Mockito.spy(new JavaMailSenderImpl());
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new MailSendException("interrupted transport", new java.io.IOException());
            } finally { completed.countDown(); }
            return null;
        }).when(sender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
        var worker = smtpWorker(sender, new BookingEmailRenderer(), 5);
        try {
            worker.poll();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.poll(); worker.poll();
            org.mockito.Mockito.verify(sender, org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
        } finally { worker.shutdown(); }
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            // If interrupted DB bookkeeping fails, lease recovery is the fallback.
            jdbcTemplate.update("UPDATE notification_email_deliveries SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE delivery_id = ? AND status = 'PROCESSING'", deliveryId(fixture));
            emailDeliveries.claimDelivery(180, 5);
            assertThat(deliveryState(fixture)).containsEntry("status", "UNKNOWN");
        });
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "chargeops.smtp.integration", matches = "true")
    void confirmedBookingOutboxKafkaDeliveryAndRealMailpitSendKeepOneEmail() throws Exception {
        jdbcTemplate.update("UPDATE notification_email_deliveries SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE status = 'PENDING'");
        jdbcTemplate.update("UPDATE notification_email_deliveries SET lease_until = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE status = 'PROCESSING'");
        var fixture = emailFixture();
        var event = storedEmailEvent(fixture);
        var email = jdbcTemplate.queryForObject("SELECT email FROM user_profile WHERE id = ?", String.class, event.recipientId());
        String bookingCode = "BK-SMTP-" + UUID.randomUUID().toString().substring(0, 16);
        jdbcTemplate.update("UPDATE bookings SET booking_code = ? WHERE id = ?", bookingCode, fixture.bookingId());
        try (var kafka = emailKafkaHarness(template -> {})) {
            var publisher = new com.thang.chargeops.notification.kafka.BookingNotificationPublisher(kafka.template(), outboxJson, kafka.topic());
            var outboxWorker = new com.thang.chargeops.notification.outbox.BookingNotificationOutboxWorker(outboxStore, publisher, outboxJson, 1, 5);
            try { outboxWorker.dispatchPending(); } finally { outboxWorker.shutdown(); }
            awaitEmailGroupOffset(kafka, 1L);
            var sender = new JavaMailSenderImpl();
            sender.setHost("localhost"); sender.setPort(1025);
            sender.getJavaMailProperties().putAll(java.util.Map.of("mail.smtp.connectiontimeout", "3000", "mail.smtp.timeout", "10000", "mail.smtp.writetimeout", "10000"));
            var worker = smtpWorker(sender, new BookingEmailRenderer(), 5);
            try {
                worker.dispatchPending();
                // Kafka replay after SMTP success must neither reset SENT nor trigger another send.
                kafka.template().send(kafka.topic(), fixture.bookingId().toString(), outboxJson.writeValueAsString(event)).get(5, TimeUnit.SECONDS);
                awaitEmailGroupOffset(kafka, 2L);
                worker.dispatchPending();
            } finally { worker.shutdown(); }
            assertThat(deliveryState(fixture)).containsEntry("status", "SENT").containsEntry("attempt_count", 1);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM notification_outbox WHERE event_id = ?", String.class, event.eventId())).isEqualTo("PUBLISHED");
            var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
            var query = java.net.URLEncoder.encode("to:" + email, java.nio.charset.StandardCharsets.UTF_8);
            var search = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:8025/api/v1/search?query=" + query)).timeout(java.time.Duration.ofSeconds(5)).GET().build();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
                var response = client.send(search, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                var messages = outboxJson.readTree(response.body()).get("messages");
                assertThat(messages.size()).isEqualTo(1);
                var id = messages.get(0).get("ID").asText();
                var detailRequest = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:8025/api/v1/message/" + id)).timeout(java.time.Duration.ofSeconds(5)).GET().build();
                var detail = outboxJson.readTree(client.send(detailRequest, java.net.http.HttpResponse.BodyHandlers.ofString()).body());
                assertThat(detail.get("HTML").asText()).contains("Race Station", bookingCode);
                assertThat(detail.get("Text").asText()).contains("Race Station", "120.000");
                assertThat(detail.get("MessageID").asText()).contains(deliveryId(fixture).toString());
            });
        }
    }

    private void awaitEmailGroupOffset(EmailKafkaHarness kafka, long expected) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            var offsets = kafka.admin().listConsumerGroupOffsets(kafka.group()).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            assertThat(offsets.values().stream().mapToLong(org.apache.kafka.clients.consumer.OffsetAndMetadata::offset).sum())
                    .isGreaterThanOrEqualTo(expected);
        });
    }

    @Test
    void ingestionDedupePreservesDeliveryStatusAndNeverChangesInbox() {
        Fixture fixture = emailFixture();
        var event = storedEmailEvent(fixture);
        var consumer = new com.thang.chargeops.notification.kafka.BookingNotificationEmailConsumer(emailDeliveries, outboxJson);
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<>("test", 0, 0L, fixture.bookingId().toString(), outboxJson.writeValueAsString(event));
        consumer.receiveConfirmed(record);
        jdbcTemplate.update("UPDATE notification_email_deliveries SET status = 'SENT', attempt_count = 2, send_started_at = CURRENT_TIMESTAMP, sent_at = CURRENT_TIMESTAMP WHERE event_id = ?", event.eventId());
        jdbcTemplate.update("UPDATE app_notifications SET read_at = CURRENT_TIMESTAMP, dismissed_at = CURRENT_TIMESTAMP WHERE booking_id = ?", fixture.bookingId());
        consumer.receiveConfirmed(record);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_email_deliveries WHERE event_id = ?", Long.class, event.eventId())).isOne();
        assertThat(jdbcTemplate.queryForMap("SELECT status, attempt_count FROM notification_email_deliveries WHERE event_id = ?", event.eventId()))
                .containsEntry("status", "SENT").containsEntry("attempt_count", 2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_notifications WHERE booking_id = ? AND read_at IS NOT NULL AND dismissed_at IS NOT NULL", Long.class, fixture.bookingId())).isOne();
    }

    @Test
    void concurrentIngestionCreatesOneDurableDelivery() throws Exception {
        Fixture fixture = emailFixture();
        var event = storedEmailEvent(fixture);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return emailDeliveries.captureConfirmed(event); });
            var second = executor.submit(() -> { start.await(); return emailDeliveries.captureConfirmed(event); });
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_email_deliveries WHERE event_id = ?", Long.class, event.eventId())).isOne();
    }

    @Test
    void inactiveRecipientIsDurablySkippedAndReplayDoesNotResetIt() {
        Fixture fixture = emailFixture();
        var event = storedEmailEvent(fixture);
        jdbcTemplate.update("UPDATE user_profile SET status = 'SUSPENDED' WHERE id = ?", event.recipientId());
        assertThat(emailDeliveries.captureConfirmed(event)).isTrue();
        assertThat(jdbcTemplate.queryForMap("SELECT status, skip_reason FROM notification_email_deliveries WHERE event_id = ?", event.eventId()))
                .containsEntry("status", "SKIPPED").containsEntry("skip_reason", "RECIPIENT_INACTIVE");
        jdbcTemplate.update("UPDATE user_profile SET status = 'ACTIVE' WHERE id = ?", event.recipientId());
        assertThat(emailDeliveries.captureConfirmed(event)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM notification_email_deliveries WHERE event_id = ?", String.class, event.eventId())).isEqualTo("SKIPPED");
    }

    @Test
    void changedBookingRecipientIsSkippedAndForgedEventIsRejected() {
        Fixture fixture = emailFixture();
        var event = storedEmailEvent(fixture);
        UUID other = ownerOf(fixture);
        jdbcTemplate.update("UPDATE bookings SET driver_id = ? WHERE id = ?", other, fixture.bookingId());
        assertThat(emailDeliveries.captureConfirmed(event)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT skip_reason FROM notification_email_deliveries WHERE event_id = ?", String.class, event.eventId())).isEqualTo("RECIPIENT_SCOPE_CHANGED");
        var forged = new com.thang.chargeops.notification.kafka.BookingNotificationEvent(event.eventId(), 1, event.eventType(), event.sourceEventKey(),
                event.bookingId(), event.stationId(), other, event.audience(), event.occurredAt());
        assertThatThrownBy(() -> emailDeliveries.captureConfirmed(forged))
                .isInstanceOf(com.thang.chargeops.notification.kafka.InvalidBookingNotificationException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_email_deliveries WHERE event_id = ?", Long.class, event.eventId())).isOne();
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO notification_email_deliveries(delivery_id,event_id,recipient_id) VALUES (?,?,?)",
                UUID.randomUUID(), event.eventId(), other)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private Fixture emailFixture() {
        Fixture fixture = outboxFixture();
        jdbcTemplate.update("UPDATE user_profile SET status = 'ACTIVE' WHERE id = (SELECT driver_id FROM bookings WHERE id = ?)", fixture.bookingId());
        return fixture;
    }

    @Test
    void deletedBookingResourceProducesSkippedDelivery() {
        Fixture fixture = emailFixture();
        var event = storedEmailEvent(fixture);
        jdbcTemplate.update("UPDATE connectors SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", fixture.connectorId());
        assertThat(emailDeliveries.captureConfirmed(event)).isTrue();
        assertThat(jdbcTemplate.queryForMap("SELECT status, skip_reason FROM notification_email_deliveries WHERE event_id = ?", event.eventId()))
                .containsEntry("status", "SKIPPED").containsEntry("skip_reason", "RESOURCE_UNAVAILABLE");
    }

    private com.thang.chargeops.notification.kafka.BookingNotificationEvent storedEmailEvent(Fixture fixture) {
        return outboxJson.readValue(jdbcTemplate.queryForObject("SELECT payload::text FROM notification_outbox WHERE booking_id = ?", String.class, fixture.bookingId()),
                com.thang.chargeops.notification.kafka.BookingNotificationEvent.class);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "chargeops.kafka.integration", matches = "true")
    void kafkaOffsetWaitsForDeliveryCommitAndReplayKeepsOneDelivery() throws Exception {
        Fixture fixture = emailFixture();
        var event = storedEmailEvent(fixture);
        try (var kafka = emailKafkaHarness(null)) {
            var partition = new org.apache.kafka.common.TopicPartition(kafka.topic(), 0);
            inNewTransaction(() -> {
                // A strong FK parent lock holds the consumer INSERT until this transaction commits.
                jdbcTemplate.queryForObject("SELECT event_id FROM notification_outbox WHERE event_id = ? FOR UPDATE", UUID.class, event.eventId());
                try {
                    kafka.template().send(kafka.topic(), 0, event.bookingId().toString(), outboxJson.writeValueAsString(event)).get(5, TimeUnit.SECONDS);
                    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
                        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%INSERT INTO notification_email_deliveries%'", Long.class)).isGreaterThanOrEqualTo(1);
                        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_email_deliveries WHERE event_id = ?", Long.class, event.eventId())).isZero();
                        assertEmailOffsetNotConsumed(kafka);
                    });
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return null;
            });
            awaitEmailOffset(kafka, 1L);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_email_deliveries WHERE event_id = ?", Long.class, event.eventId())).isOne();
            kafka.template().send(kafka.topic(), 0, event.bookingId().toString(), outboxJson.writeValueAsString(event)).get(5, TimeUnit.SECONDS);
            awaitEmailOffset(kafka, 2L);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_email_deliveries WHERE event_id = ?", Long.class, event.eventId())).isOne();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_notifications WHERE booking_id = ?", Long.class, fixture.bookingId())).isOne();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "chargeops.kafka.integration", matches = "true")
    void databaseOutageRetainsKafkaOffsetPastFiniteRetryLimitThenRecovers() throws Exception {
        Fixture fixture = emailFixture();
        var event = storedEmailEvent(fixture);
        var available = new java.util.concurrent.atomic.AtomicBoolean(false);
        var attempts = new AtomicInteger();
        doAnswer(invocation -> {
            attempts.incrementAndGet();
            if (!available.get()) throw new org.springframework.dao.DataAccessResourceFailureException("Simulated DB outage");
            return invocation.callRealMethod();
        }).when(emailDeliveries).captureConfirmed(event);
        try (var kafka = emailKafkaHarness(null)) {
            kafka.template().send(kafka.topic(), 0, event.bookingId().toString(), outboxJson.writeValueAsString(event)).get(5, TimeUnit.SECONDS);
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(25)).untilAsserted(() -> assertThat(attempts.get()).isGreaterThanOrEqualTo(4));
            assertEmailOffsetNotConsumed(kafka);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_email_deliveries WHERE event_id = ?", Long.class, event.eventId())).isZero();
            try (var probe = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(java.util.Map.of("bootstrap.servers", kafka.broker()),
                    new org.apache.kafka.common.serialization.StringDeserializer(), new org.apache.kafka.common.serialization.StringDeserializer())) {
                assertThat(probe.endOffsets(List.of(new org.apache.kafka.common.TopicPartition(kafka.dlt(), 0))).values()).containsExactly(0L);
            }
            available.set(true);
            awaitEmailOffset(kafka, 1L);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_email_deliveries WHERE event_id = ?", Long.class, event.eventId())).isOne();
        } finally {
            available.set(true);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "chargeops.kafka.integration", matches = "true")
    void malformedRecordWaitsForDltAckAndFailedDltPublishIsRetried() throws Exception {
        var entered = new CountDownLatch(1);
        var pending = new java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>();
        var recoveries = new AtomicInteger();
        try (var kafka = emailKafkaHarness(template -> doAnswer(invocation -> {
            var record = (org.apache.kafka.clients.producer.ProducerRecord<String, String>) invocation.getArgument(0);
            if (record.topic().endsWith(".dlt") && recoveries.incrementAndGet() == 1) {
                entered.countDown();
                return pending;
            }
            return invocation.callRealMethod();
        }).when(template).send(org.mockito.ArgumentMatchers.any(org.apache.kafka.clients.producer.ProducerRecord.class)))) {
            kafka.template().send(kafka.topic(), 0, "bad-key", "{\"schemaVersion\":99}").get(5, TimeUnit.SECONDS);
            assertThat(entered.await(8, TimeUnit.SECONDS)).isTrue();
            assertEmailOffsetNotConsumed(kafka);
            pending.completeExceptionally(new IllegalStateException("DLT broker ACK unavailable"));
            awaitEmailOffset(kafka, 1L);
            assertThat(recoveries.get()).isGreaterThanOrEqualTo(2);
            try (var probe = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(java.util.Map.of("bootstrap.servers", kafka.broker()),
                    new org.apache.kafka.common.serialization.StringDeserializer(), new org.apache.kafka.common.serialization.StringDeserializer())) {
                var partition = new org.apache.kafka.common.TopicPartition(kafka.dlt(), 0);
                probe.assign(List.of(partition));
                probe.seekToBeginning(List.of(partition));
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
                    var records = probe.poll(java.time.Duration.ofSeconds(1));
                    assertThat(records.count()).isOne();
                    assertThat(records.iterator().next().value()).isEqualTo("{\"schemaVersion\":99}");
                });
            }
        } finally {
            pending.completeExceptionally(new IllegalStateException("Test cleanup"));
        }
    }

    private void awaitEmailOffset(EmailKafkaHarness kafka, long expected) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            var offsets = kafka.admin().listConsumerGroupOffsets(kafka.group()).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            assertThat(offsets).containsKey(new org.apache.kafka.common.TopicPartition(kafka.topic(), 0));
            assertThat(offsets.get(new org.apache.kafka.common.TopicPartition(kafka.topic(), 0)).offset()).isGreaterThanOrEqualTo(expected);
        });
    }

    private void assertEmailOffsetNotConsumed(EmailKafkaHarness kafka) throws Exception {
        var offsets = kafka.admin().listConsumerGroupOffsets(kafka.group()).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
        var position = offsets.get(new org.apache.kafka.common.TopicPartition(kafka.topic(), 0));
        // Kafka may commit the initial position 0; only position 1 consumes our record at offset 0.
        if (position != null) assertThat(position.offset()).isZero();
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @org.springframework.kafka.annotation.EnableKafka
    static class EmailKafkaListenerTestContext {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    private EmailKafkaHarness emailKafkaHarness(java.util.function.Consumer<org.springframework.kafka.core.KafkaTemplate<String, String>> configure) throws Exception {
        String broker = System.getProperty("chargeops.kafka.bootstrap-servers", "localhost:9092");
        String prefix = "chargeops.lab.email." + UUID.randomUUID();
        String topic = prefix + ".events", dlt = prefix + ".dlt", group = prefix + ".reader";
        var admin = org.apache.kafka.clients.admin.AdminClient.create(java.util.Map.of("bootstrap.servers", broker));
        admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(topic, 3, (short) 1),
                new org.apache.kafka.clients.admin.NewTopic(dlt, 3, (short) 1))).all().get(10, TimeUnit.SECONDS);
        var producer = new org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String>(java.util.Map.of(
                "bootstrap.servers", broker, "acks", "all", "enable.idempotence", true,
                "max.block.ms", 5000, "delivery.timeout.ms", 10000, "request.timeout.ms", 5000),
                new org.apache.kafka.common.serialization.StringSerializer(), new org.apache.kafka.common.serialization.StringSerializer());
        var template = org.mockito.Mockito.spy(new org.springframework.kafka.core.KafkaTemplate<>(producer));
        if (configure != null) configure.accept(template);
        var consumers = new org.springframework.kafka.core.DefaultKafkaConsumerFactory<Object, Object>(java.util.Map.of(
                "bootstrap.servers", broker, "enable.auto.commit", true, "auto.offset.reset", "earliest",
                "key.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class,
                "value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class));
        var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("email-test", java.util.Map.of(
                "app.notification.kafka.enabled", true, "app.notification.outbox.enabled", false,
                "app.notification.kafka.consumer.enabled", true, "app.notification.kafka.consumer.group-id", group,
                "app.notification.kafka.topic", topic, "app.notification.kafka.dead-letter-topic", dlt)));
        context.registerBean(ObjectMapper.class, () -> outboxJson);
        context.registerBean(com.thang.chargeops.notification.email.NotificationEmailDeliveryStore.class, () -> emailDeliveries);
        context.registerBean(org.springframework.kafka.core.KafkaTemplate.class, () -> template);
        context.registerBean(org.springframework.kafka.core.ConsumerFactory.class, () -> consumers);
        context.register(EmailKafkaListenerTestContext.class, com.thang.chargeops.notification.kafka.BookingNotificationKafkaConfiguration.class);
        try {
            context.refresh();
            var registry = context.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class);
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(registry.getListenerContainer("booking-email-ingestion").getAssignedPartitions()).hasSize(3));
            return new EmailKafkaHarness(context, producer, template, admin, broker, topic, dlt, group);
        } catch (Exception failure) {
            context.close();
            producer.destroy();
            admin.deleteTopics(List.of(topic, dlt));
            admin.close();
            throw failure;
        }
    }

    private record EmailKafkaHarness(org.springframework.context.annotation.AnnotationConfigApplicationContext context,
                                    org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String> producer,
                                    org.springframework.kafka.core.KafkaTemplate<String, String> template,
                                    org.apache.kafka.clients.admin.AdminClient admin,
                                    String broker, String topic, String dlt, String group) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            try {
                context.close();
                template.destroy();
                producer.destroy();
                admin.deleteTopics(List.of(topic, dlt)).all().get(10, TimeUnit.SECONDS);
            } finally {
                admin.close();
            }
        }
    }

    @Test
    void workerWaitsForAckOutsideTransactionBeforeMarkingPublished() throws Exception {
        Fixture fixture = outboxFixture();
        var publisher = mock(com.thang.chargeops.notification.kafka.BookingNotificationPublisher.class);
        var ack = new java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>();
        CountDownLatch sent = new CountDownLatch(1);
        when(publisher.publish(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sent.countDown();
            return ack;
        });
        var worker = new com.thang.chargeops.notification.outbox.BookingNotificationOutboxWorker(outboxStore, publisher, outboxJson, 1, 5);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var task = executor.submit(worker::dispatchPending);
            assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM notification_outbox WHERE booking_id = ?", String.class, fixture.bookingId()))
                    .isEqualTo("PROCESSING");
            assertThat(jdbcTemplate.queryForObject("SELECT published_at FROM notification_outbox WHERE booking_id = ?", Timestamp.class, fixture.bookingId())).isNull();
            ack.complete(mock(org.springframework.kafka.support.SendResult.class));
            task.get(5, TimeUnit.SECONDS);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM notification_outbox WHERE booking_id = ?", String.class, fixture.bookingId()))
                .isEqualTo("PUBLISHED");
        assertThat(outboxStore.claimNext(180)).isEmpty();
    }

    @Test
    void workerRetriesFailureWithSameIdentityAndSanitizedBackoff() {
        Fixture fixture = outboxFixture();
        var publisher = mock(com.thang.chargeops.notification.kafka.BookingNotificationPublisher.class);
        when(publisher.publish(org.mockito.ArgumentMatchers.any())).thenReturn(
                java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("secret broker credential")),
                java.util.concurrent.CompletableFuture.completedFuture(mock(org.springframework.kafka.support.SendResult.class)));
        var worker = new com.thang.chargeops.notification.outbox.BookingNotificationOutboxWorker(outboxStore, publisher, outboxJson, 1, 5);
        worker.dispatchPending();
        var state = jdbcTemplate.queryForMap("SELECT status, sanitized_last_error, next_attempt_at > CURRENT_TIMESTAMP AS delayed FROM notification_outbox WHERE booking_id = ?", fixture.bookingId());
        assertThat(state).containsEntry("status", "PENDING").containsEntry("sanitized_last_error", "KAFKA_PUBLISH_FAILED").containsEntry("delayed", true);
        assertThat(outboxStore.claimNext(180)).isEmpty();
        jdbcTemplate.update("UPDATE notification_outbox SET next_attempt_at = CURRENT_TIMESTAMP WHERE booking_id = ?", fixture.bookingId());
        worker.dispatchPending();
        var events = org.mockito.ArgumentCaptor.forClass(com.thang.chargeops.notification.kafka.BookingNotificationEvent.class);
        org.mockito.Mockito.verify(publisher, org.mockito.Mockito.times(2)).publish(events.capture());
        assertThat(events.getAllValues().get(0)).isEqualTo(events.getAllValues().get(1));
        assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM notification_outbox WHERE booking_id = ?", Integer.class, fixture.bookingId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM notification_outbox WHERE booking_id = ?", String.class, fixture.bookingId())).isEqualTo("PUBLISHED");
    }

    @Test
    void expiredLeaseCanBeReclaimedAndOldTokenCannotOverwriteNewClaim() {
        Fixture fixture = outboxFixture();
        var old = outboxStore.claimNext(180).orElseThrow();
        assertThat(outboxStore.claimNext(180)).isEmpty();
        jdbcTemplate.update("UPDATE notification_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE booking_id = ?", fixture.bookingId());
        var recovered = outboxStore.claimNext(180).orElseThrow();
        assertThat(recovered.eventId()).isEqualTo(old.eventId());
        assertThat(recovered.payload()).isEqualTo(old.payload());
        assertThat(recovered.token()).isNotEqualTo(old.token());
        assertThat(outboxStore.markPublished(old)).isFalse();
        assertThat(outboxStore.retryLater(old, "KAFKA_ACK_TIMEOUT")).isFalse();
        assertThat(outboxStore.markPublished(recovered)).isTrue();
    }

    @Test
    void workerTimeoutRetainsPendingIntentAndNeverMarksPublished() {
        Fixture fixture = outboxFixture();
        var publisher = mock(com.thang.chargeops.notification.kafka.BookingNotificationPublisher.class);
        when(publisher.publish(org.mockito.ArgumentMatchers.any())).thenReturn(new java.util.concurrent.CompletableFuture<>());
        new com.thang.chargeops.notification.outbox.BookingNotificationOutboxWorker(outboxStore, publisher, outboxJson, 1, 1).dispatchPending();
        var state = jdbcTemplate.queryForMap("SELECT status, sanitized_last_error, published_at FROM notification_outbox WHERE booking_id = ?", fixture.bookingId());
        assertThat(state).containsEntry("status", "PENDING").containsEntry("sanitized_last_error", "KAFKA_ACK_TIMEOUT").containsEntry("published_at", null);
    }

    @Test
    void claimSkipsRowsLockedByAnotherWorker() throws Exception {
        Fixture fixture = outboxFixture();
        try (var executor = Executors.newSingleThreadExecutor()) {
            inNewTransaction(() -> {
                jdbcTemplate.queryForObject("SELECT event_id FROM notification_outbox WHERE booking_id = ? FOR UPDATE", UUID.class, fixture.bookingId());
                try {
                    assertThat(executor.submit(() -> outboxStore.claimNext(180)).get(3, TimeUnit.SECONDS)).isEmpty();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return null;
            });
        }
        assertThat(outboxStore.claimNext(180)).isPresent();
    }

    private Fixture outboxFixture() {
        // Other payment tests retain independent rows. Defer them to isolate the scheduler's due set.
        jdbcTemplate.update("UPDATE notification_outbox SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE status = 'PENDING'");
        jdbcTemplate.update("UPDATE notification_outbox SET lease_until = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE status = 'PROCESSING'");
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        inNewTransaction(() -> serviceAt(ACCEPTED_AT).processReceipt(receipt(fixture, "WORKER-" + UUID.randomUUID(), ACCEPTED_AT)));
        return fixture;
    }

    @Test
    void scheduledPollDoesNotQueueDuplicateRunsAndShutdownLeavesRecoverableLease() throws Exception {
        Fixture fixture = outboxFixture();
        var publisher = mock(com.thang.chargeops.notification.kafka.BookingNotificationPublisher.class);
        var entered = new CountDownLatch(1);
        when(publisher.publish(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            entered.countDown();
            return new java.util.concurrent.CompletableFuture<>();
        });
        var worker = new com.thang.chargeops.notification.outbox.BookingNotificationOutboxWorker(outboxStore, publisher, outboxJson, 1, 30);
        try {
            worker.poll();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.poll();
            worker.poll();
            org.mockito.Mockito.verify(publisher, org.mockito.Mockito.times(1)).publish(org.mockito.ArgumentMatchers.any());
        } finally {
            worker.shutdown();
        }
        var row = jdbcTemplate.queryForMap("SELECT status, lease_until, published_at FROM notification_outbox WHERE booking_id = ?", fixture.bookingId());
        assertThat(row).containsEntry("status", "PROCESSING").containsEntry("published_at", null);
        assertThat(row.get("lease_until")).isNotNull();
    }

    @Test
    void actualKafkaClientConnectionFailureKeepsConfirmedBookingAndPendingOutbox() {
        Fixture fixture = outboxFixture();
        var factory = new org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String>(java.util.Map.of(
                "bootstrap.servers", "127.0.0.1:1", "acks", "all", "enable.idempotence", true,
                "max.block.ms", 1000, "delivery.timeout.ms", 2000, "request.timeout.ms", 1000),
                new org.apache.kafka.common.serialization.StringSerializer(), new org.apache.kafka.common.serialization.StringSerializer());
        var template = new org.springframework.kafka.core.KafkaTemplate<>(factory);
        try {
            var publisher = new com.thang.chargeops.notification.kafka.BookingNotificationPublisher(template, outboxJson, "chargeops.lab.unavailable");
            new com.thang.chargeops.notification.outbox.BookingNotificationOutboxWorker(outboxStore, publisher, outboxJson, 1, 3).dispatchPending();
            var state = jdbcTemplate.queryForMap("SELECT status, sanitized_last_error, published_at FROM notification_outbox WHERE booking_id = ?", fixture.bookingId());
            assertThat(state).containsEntry("status", "PENDING").containsEntry("sanitized_last_error", "KAFKA_PUBLISH_FAILED").containsEntry("published_at", null);
            assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
        } finally {
            template.destroy();
            factory.destroy();
        }
    }

    @Test
    void claimCannotSeeUncommittedConfirmationIntent() throws Exception {
        jdbcTemplate.update("UPDATE notification_outbox SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE status = 'PENDING'");
        jdbcTemplate.update("UPDATE notification_outbox SET lease_until = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE status = 'PROCESSING'");
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        var inserted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var callback = executor.submit(() -> inNewTransaction(() -> {
                var result = serviceAt(ACCEPTED_AT).processReceipt(receipt(fixture, "UNCOMMITTED-" + UUID.randomUUID(), ACCEPTED_AT));
                inserted.countDown();
                try {
                    assertThat(commit.await(8, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return result;
            }));
            try {
                assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(outboxStore.claimNext(180)).isEmpty();
            } finally {
                commit.countDown();
            }
            assertThat(callback.get(5, TimeUnit.SECONDS).status()).isEqualTo(PaymentReceiptResult.Status.APPLIED);
        }
        assertThat(outboxStore.claimNext(180)).isPresent();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "chargeops.kafka.integration", matches = "true")
    void realKafkaAckCrashWindowRepublishesSameEventBeforeMarkingPublished() throws Exception {
        Fixture fixture = outboxFixture();
        String topic = "chargeops.lab.booking.outbox.v1";
        String broker = System.getProperty("chargeops.kafka.bootstrap-servers", "localhost:9092");
        try (var admin = org.apache.kafka.clients.admin.AdminClient.create(java.util.Map.of("bootstrap.servers", broker))) {
            try {
                admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(topic, 3, (short) 1))).all().get(15, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) throw e;
            }
        }
        var factory = new org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String>(java.util.Map.of(
                "bootstrap.servers", broker, "acks", "all", "enable.idempotence", true,
                "max.block.ms", 5000, "delivery.timeout.ms", 10000, "request.timeout.ms", 5000),
                new org.apache.kafka.common.serialization.StringSerializer(), new org.apache.kafka.common.serialization.StringSerializer());
        var template = new org.springframework.kafka.core.KafkaTemplate<>(factory);
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(java.util.Map.of(
                "bootstrap.servers", broker, "group.id", "chargeops-lab-outbox-" + UUID.randomUUID(),
                "enable.auto.commit", false, "auto.offset.reset", "earliest"),
                new org.apache.kafka.common.serialization.StringDeserializer(), new org.apache.kafka.common.serialization.StringDeserializer())) {
            var publisher = new com.thang.chargeops.notification.kafka.BookingNotificationPublisher(template, outboxJson, topic);
            var abandoned = outboxStore.claimNext(180).orElseThrow();
            var event = outboxJson.readValue(abandoned.payload(), com.thang.chargeops.notification.kafka.BookingNotificationEvent.class);
            publisher.publish(event).get(15, TimeUnit.SECONDS); // Simulate crash after ACK, before DB mark.
            jdbcTemplate.update("UPDATE notification_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE booking_id = ?", fixture.bookingId());
            new com.thang.chargeops.notification.outbox.BookingNotificationOutboxWorker(outboxStore, publisher, outboxJson, 1, 15).dispatchPending();
            assertThat(outboxStore.markPublished(abandoned)).isFalse();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM notification_outbox WHERE booking_id = ?", String.class, fixture.bookingId())).isEqualTo("PUBLISHED");
            var partitions = List.of(new org.apache.kafka.common.TopicPartition(topic, 0),
                    new org.apache.kafka.common.TopicPartition(topic, 1), new org.apache.kafka.common.TopicPartition(topic, 2));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var received = new java.util.ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (received.size() < 2 && System.nanoTime() < deadline) {
                for (var record : consumer.poll(java.time.Duration.ofSeconds(1))) {
                    if (event.eventId().equals(outboxJson.readValue(record.value(), com.thang.chargeops.notification.kafka.BookingNotificationEvent.class).eventId())) received.add(record);
                }
            }
            assertThat(received).hasSize(2).allSatisfy(record -> {
                assertThat(record.key()).isEqualTo(fixture.bookingId().toString());
                assertThat(outboxJson.readValue(record.value(), com.thang.chargeops.notification.kafka.BookingNotificationEvent.class)).isEqualTo(event);
            });
            assertThat(received.get(0).partition()).isEqualTo(received.get(1).partition());
        } finally {
            template.destroy();
            factory.destroy();
        }
    }

    @Autowired
    private com.thang.chargeops.profile.repository.UserProfileRepository profileRepository;

    @Test
    void confirmedIntentAndOutboxStayStableAcrossReceiptAndWriterReplay() {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        var input = receipt(fixture, "OUTBOX-" + UUID.randomUUID(), ACCEPTED_AT);
        var applied = inNewTransaction(() -> serviceAt(ACCEPTED_AT).processReceipt(input));
        var original = jdbcTemplate.queryForMap(
                "SELECT event_id, payload::text AS payload FROM notification_outbox WHERE booking_id = ?", fixture.bookingId());
        inNewTransaction(() -> {
            jdbcTemplate.update("UPDATE app_notifications SET read_at = ?, dismissed_at = ? WHERE booking_id = ?",
                    Timestamp.from(ACCEPTED_AT), Timestamp.from(ACCEPTED_AT), fixture.bookingId());
            confirmationOutboxWriter.recordConfirmed(bookingRepository.findById(fixture.bookingId()).orElseThrow(),
                    transactionRepository.findById(applied.transactionId()).orElseThrow(), ACCEPTED_AT);
            return null;
        });
        assertThat(inNewTransaction(() -> serviceAt(ACCEPTED_AT).processReceipt(input)).status())
                .isEqualTo(PaymentReceiptResult.Status.DUPLICATE);
        assertThat(inNewTransaction(() -> serviceAt(ACCEPTED_AT).processReceipt(
                receipt(fixture, "EXCESS-" + UUID.randomUUID(), ACCEPTED_AT))).status())
                .isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT event_id, payload::text AS payload FROM notification_outbox WHERE booking_id = ?", fixture.bookingId()))
                .isEqualTo(original);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_notifications WHERE booking_id = ? AND read_at IS NOT NULL AND dismissed_at IS NOT NULL",
                Long.class, fixture.bookingId())).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM notification_outbox WHERE booking_id = ?",
                String.class, fixture.bookingId())).isEqualTo("PENDING");
        var event = new ObjectMapper().readValue((String) original.get("payload"),
                com.thang.chargeops.notification.kafka.BookingNotificationEvent.class);
        assertThat(event.eventId()).isEqualTo(original.get("event_id"));
        assertThat(event.bookingId()).isEqualTo(fixture.bookingId());
        assertThat(event.sourceEventKey()).isEqualTo(applied.transactionId() + ":BOOKING_CONFIRMED");
        assertThat(event.occurredAt()).isEqualTo(ACCEPTED_AT);
        assertThat((String) original.get("payload")).doesNotContain("email", "rawPayload", fixture.accountRef());
    }

    @Test
    void rollbackRemovesPaymentTransitionNoticeAndOutboxTogether() {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        assertThatThrownBy(() -> inNewTransaction(() -> {
            serviceAt(ACCEPTED_AT).processReceipt(receipt(fixture, "ROLLBACK-" + UUID.randomUUID(), ACCEPTED_AT));
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox WHERE booking_id = ?",
                    Long.class, fixture.bookingId())).isOne();
            throw new IllegalStateException("Simulated failure before transaction commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        for (String table : List.of("notification_outbox", "app_notifications", "booking_status_history")) {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE booking_id = ?",
                    Long.class, fixture.bookingId())).isZero();
        }
        assertThat(transactionRepository.findByPaymentIdOrderByReceivedAtAscIdAsc(fixture.paymentId())).isEmpty();
    }

    @Test
    void outboxConstraintFailureRollsBackTheBusinessTransaction() {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        assertThatThrownBy(() -> inNewTransaction(() -> {
            serviceAt(ACCEPTED_AT).processReceipt(receipt(fixture, "INVALID-" + UUID.randomUUID(), ACCEPTED_AT));
            jdbcTemplate.update("UPDATE notification_outbox SET payload = '{}'::jsonb WHERE booking_id = ?", fixture.bookingId());
            return null;
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_notifications WHERE booking_id = ?",
                Long.class, fixture.bookingId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox WHERE booking_id = ?",
                Long.class, fixture.bookingId())).isZero();
    }

    @Test
    void confirmationDoesNotWaitForRecipientWriteLockOrReverseDomainLockOrder() throws Exception {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        UUID driver = jdbcTemplate.queryForObject("SELECT driver_id FROM bookings WHERE id = ?", UUID.class, fixture.bookingId());
        CountDownLatch profileLocked = new CountDownLatch(1);
        CountDownLatch paymentCompleted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> inNewTransaction(() -> {
                profileRepository.findByIdWithLock(driver).orElseThrow();
                profileLocked.countDown();
                try {
                    assertThat(paymentCompleted.await(8, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                connectorRepository.findByIdWithLock(fixture.connectorId()).orElseThrow();
                return null;
            }));
            assertThat(profileLocked.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                var callback = executor.submit(() -> inNewTransaction(() -> {
                    jdbcTemplate.execute("SET LOCAL lock_timeout = '3s'");
                    return serviceAt(ACCEPTED_AT).processReceipt(receipt(fixture, "LOCK-" + UUID.randomUUID(), ACCEPTED_AT));
                }));
                assertThat(callback.get(6, TimeUnit.SECONDS).status()).isEqualTo(PaymentReceiptResult.Status.APPLIED);
            } finally {
                paymentCompleted.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentUnmatchedCallbacksRetainOneReceiptAndReplayTheSameEvidence() throws Exception {
        NormalizedReceipt receipt = new NormalizedReceipt("SIMULATOR", "UNKNOWN-ACCOUNT",
                "UNMATCHED-" + UUID.randomUUID(), AMOUNT, "VND", ACCEPTED_AT, ACCEPTED_AT,
                null, null, "technical exception", "{\"secret\":\"private\"}");
        PaymentConfirmationServiceImpl service = serviceAt(ACCEPTED_AT);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch initialLookups = new CountDownLatch(2);
        var lookupCount = new AtomicInteger();
        // Force both requests to observe no receipt before either tries to insert.
        doAnswer(invocation -> {
            List<UUID> ids = jdbcTemplate.queryForList("""
                    SELECT id FROM payment_transactions
                    WHERE provider = ? AND receiving_account_ref = ? AND transaction_ref = ?
                    """, UUID.class, receipt.provider(), receipt.receivingAccountRef(), receipt.transactionRef());
            var result = ids.isEmpty() ? java.util.Optional.empty() : transactionRepository.findById(ids.getFirst());
            if (lookupCount.incrementAndGet() <= 2) {
                assertThat(ids).isEmpty();
                initialLookups.countDown();
                assertThat(initialLookups.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(transactionRepository).findByProviderAndReceivingAccountRefAndTransactionRef(
                receipt.provider(), receipt.receivingAccountRef(), receipt.transactionRef());
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<PaymentReceiptResult> first = executor.submit(() -> processAfterBarrier(service, receipt, ready, start));
            Future<PaymentReceiptResult> second = executor.submit(() -> processAfterBarrier(service, receipt, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results).extracting(PaymentReceiptResult::status).containsExactlyInAnyOrder(
                    PaymentReceiptResult.Status.UNMATCHED, PaymentReceiptResult.Status.DUPLICATE);
            assertThat(results).extracting(PaymentReceiptResult::transactionId).doesNotContainNull().containsOnly(results.getFirst().transactionId());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payment_transactions WHERE transaction_ref = ?",
                Long.class, receipt.transactionRef())).isOne();
        var stored = transactionRepository.findByProviderAndReceivingAccountRefAndTransactionRef(
                receipt.provider(), receipt.receivingAccountRef(), receipt.transactionRef()).orElseThrow();
        assertThat(stored.getPayment()).isNull();
        assertThat(stored.getApplicationClassification().name()).isEqualTo("UNAPPLIED");
        assertThat(stored.getRawPayload()).contains("private");
    }

    @Test
    void replayWithPaddedIdentityUsesThePersistedCanonicalReceipt() {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        NormalizedReceipt clean = receipt(fixture, "PADDED-" + UUID.randomUUID(), ACCEPTED_AT);
        var first = inNewTransaction(() -> serviceAt(ACCEPTED_AT).processReceipt(clean));
        NormalizedReceipt padded = new NormalizedReceipt(" SIMULATOR ", " " + fixture.accountRef() + " ",
                " " + clean.transactionRef() + " ", AMOUNT, "VND", ACCEPTED_AT, ACCEPTED_AT,
                fixture.vaNumber(), fixture.paymentCode(), "replay", "{}");
        var replay = inNewTransaction(() -> serviceAt(ACCEPTED_AT).processReceipt(padded));
        assertThat(first.status()).isEqualTo(PaymentReceiptResult.Status.APPLIED);
        assertThat(replay.status()).isEqualTo(PaymentReceiptResult.Status.DUPLICATE);
        assertThat(first.transactionId()).isNotNull().isEqualTo(replay.transactionId());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payment_transactions WHERE payment_id = ?",
                Long.class, fixture.paymentId())).isOne();
    }

    @Test
    void underAndOverReceiptsStayOutsideThePackageLedgerAndNeverCreateRefunds() {
        for (String amount : List.of("119999", "120001")) {
            Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
            NormalizedReceipt exception = new NormalizedReceipt("SIMULATOR", fixture.accountRef(),
                    "AMOUNT-" + UUID.randomUUID(), new BigDecimal(amount), "VND", ACCEPTED_AT, ACCEPTED_AT,
                    fixture.vaNumber(), fixture.paymentCode(), "amount exception", "{}");
            var result = inNewTransaction(() -> serviceAt(ACCEPTED_AT).processReceipt(exception));
            assertThat(result.status()).isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
            assertThat(result.transactionId()).isNotNull();
            assertThat(result.reason()).isEqualTo(amount.equals("119999") ? "UNDERPAYMENT" : "OVERPAYMENT");
            assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
            UUID ownerId = ownerOf(fixture);
            var totals = paymentRepository.summarizeOwnerSimulatorLedger(ownerId);
            assertThat(totals.getGrossAmount()).isZero();
            assertThat(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(fixture.bookingId())).isEmpty();
        }
    }

    @Test
    void differentReceiptReferencesRetainExcessWithoutCountingThePackageTwice() throws Exception {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        var firstReceipt = receipt(fixture, "FIRST-" + UUID.randomUUID(), ACCEPTED_AT);
        var secondReceipt = receipt(fixture, "EXCESS-" + UUID.randomUUID(), ACCEPTED_AT);
        var service = serviceAt(ACCEPTED_AT);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> processAfterBarrier(service, firstReceipt, ready, start));
            var second = executor.submit(() -> processAfterBarrier(service, secondReceipt, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .extracting(PaymentReceiptResult::status).containsExactlyInAnyOrder(
                            PaymentReceiptResult.Status.APPLIED, PaymentReceiptResult.Status.UNAPPLIED);
        }
        var evidence = transactionRepository.findByPaymentIdOrderByReceivedAtAscIdAsc(fixture.paymentId());
        assertThat(evidence).hasSize(2);
        assertThat(evidence).filteredOn(tx -> tx.getApplicationClassification().name().equals("UNAPPLIED"))
                .singleElement().satisfies(tx -> assertThat(tx.getApplicationReason()).isEqualTo("ALREADY_PAID"));
        assertThat(paymentRepository.summarizeOwnerSimulatorLedger(ownerOf(fixture)).getGrossAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(fixture.bookingId())).isEmpty();
    }

    @Test
    void ownerReadsOnlyOwnSimulatorAppliedReceiptsAndNeverSerializesPrivateEvidence() {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        inNewTransaction(() -> serviceAt(ACCEPTED_AT).processReceipt(receipt(fixture, "OWNER-" + UUID.randomUUID(), ACCEPTED_AT)));
        // A second monetary receipt is evidence, not an additional package collection.
        inNewTransaction(() -> serviceAt(ACCEPTED_AT).processReceipt(receipt(fixture, "EXTRA-" + UUID.randomUUID(), ACCEPTED_AT)));
        var profile = mock(CurrentProfileProvider.class);
        UUID ownerId = ownerOf(fixture);
        when(profile.requireProfileId()).thenReturn(ownerId);
        var finance = new OwnerFinanceServiceImpl(
                profile, paymentRepository, transactionRepository, refundRepository);
        inNewTransaction(() -> {
            var detail = finance.get(fixture.bookingId());
            assertThat(detail.collectedAmount()).isEqualTo(120000);
            assertThat(detail.receipts()).hasSize(1);
            String json = new ObjectMapper().writeValueAsString(detail);
            assertThat(json).doesNotContain("rawPayload", "raw_payload", "receivingAccountRef", "receiving_account_ref", fixture.accountRef());
            assertThat(finance.list(1, 20).getTotalElements()).isOne();
            return null;
        });
        when(profile.requireProfileId()).thenReturn(UUID.randomUUID());
        inNewTransaction(() -> {
            assertThat(finance.list(1, 20)).isEmpty();
            assertThatThrownBy(() -> finance.get(fixture.bookingId()))
                    .isInstanceOfSatisfying(AppException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
            return null;
        });
        when(profile.requireProfileId()).thenReturn(ownerId);
        for (String environment : List.of("TEST", "LIVE", "LEGACY")) {
            inNewTransaction(() -> {
                jdbcTemplate.update("UPDATE payments SET environment = ? WHERE id = ?", environment, fixture.paymentId());
                return null;
            });
            inNewTransaction(() -> {
                assertThat(finance.summary().grossVnd()).isZero();
                assertThat(finance.list(1, 20)).isEmpty();
                assertThatThrownBy(() -> finance.get(fixture.bookingId()))
                        .isInstanceOfSatisfying(AppException.class,
                                error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
                return null;
            });
        }
    }

    @Test
    void additionalReceiptAfterPackageRefundCannotChangeRefundOrReviveBooking() {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        inNewTransaction(() -> serviceAt(ACCEPTED_AT).processReceipt(receipt(fixture, "PAID-" + UUID.randomUUID(), ACCEPTED_AT)));
        inNewTransaction(() -> {
            paymentRepository.findByIdWithLock(fixture.paymentId()).orElseThrow().recordFullRefund(AMOUNT);
            return null;
        });
        var extra = inNewTransaction(() -> serviceAt(ACCEPTED_AT).processReceipt(receipt(fixture, "AFTER-REFUND-" + UUID.randomUUID(), ACCEPTED_AT)));
        assertThat(extra.status()).isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
        var payment = paymentRepository.findById(fixture.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    private UUID ownerOf(Fixture fixture) {
        return jdbcTemplate.queryForObject("""
                SELECT s.owner_id FROM stations s JOIN charge_points cp ON cp.station_id = s.id
                JOIN connectors c ON c.charge_point_id = cp.id WHERE c.id = ?
                """, UUID.class, fixture.connectorId());
    }

    @Test
    void concurrentCallbacksWithSameReferenceCreateExactlyOneReceipt() throws Exception {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        NormalizedReceipt receipt = receipt(
                fixture,
                "TX-SAME-" + UUID.randomUUID(),
                ACCEPTED_AT.minusSeconds(30)
        );
        PaymentConfirmationServiceImpl service = serviceAt(ACCEPTED_AT);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PaymentReceiptResult> first = executor.submit(
                    () -> processAfterBarrier(service, receipt, ready, start));
            Future<PaymentReceiptResult> second = executor.submit(
                    () -> processAfterBarrier(service, receipt, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<PaymentReceiptResult.Status> statuses = List.of(
                    first.get(10, TimeUnit.SECONDS).status(),
                    second.get(10, TimeUnit.SECONDS).status()
            );

            assertThat(statuses).containsExactlyInAnyOrder(
                    PaymentReceiptResult.Status.APPLIED,
                    PaymentReceiptResult.Status.DUPLICATE
            );
        } finally {
            executor.shutdownNow();
        }

        assertThat(transactionRepository
                .findByProviderAndReceivingAccountRefAndTransactionRef(
                        "SIMULATOR", fixture.accountRef(), receipt.transactionRef()))
                .isPresent();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_transactions WHERE payment_id = ?",
                Long.class,
                fixture.paymentId()
        )).isOne();
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PAID);
        assertThat(historyRepository.findByBooking_IdOrderByOccurredAtAscIdAsc(fixture.bookingId()))
                .hasSize(1);
    }

    @Test
    void providerPaidBeforeExpiryButProcessedAfterExpiryRemainsUnappliedAndSlotIsFree() {
        Instant holdExpiry = ACCEPTED_AT.minusSeconds(1);
        Fixture fixture = createFixture(holdExpiry);
        NormalizedReceipt receipt = receipt(
                fixture,
                "TX-LATE-" + UUID.randomUUID(),
                holdExpiry.minusSeconds(30)
        );

        PaymentReceiptResult result = inNewTransaction(
                () -> serviceAt(ACCEPTED_AT).processReceipt(receipt));

        assertThat(result.status()).isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
        assertThat(result.reason()).isEqualTo("LATE");
        assertThat(transactionRepository
                .findByProviderAndReceivingAccountRefAndTransactionRef(
                        "SIMULATOR", fixture.accountRef(), receipt.transactionRef())
                .orElseThrow()
                .getApplicationReason()).isEqualTo("LATE");
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING);
        assertThat(bookingRepository.existsOverlappingBooking(
                fixture.connectorId(), fixture.startAt(), fixture.endAt(), ACCEPTED_AT))
                .isFalse();
    }

    @Test
    void confirmationUsesServerAcceptanceTimeAndReplayDoesNotResetIt() {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        NormalizedReceipt receipt = receipt(
                fixture,
                "TX-TIME-" + UUID.randomUUID(),
                ACCEPTED_AT.minusSeconds(120)
        );
        PaymentConfirmationServiceImpl service = serviceAt(ACCEPTED_AT);

        PaymentReceiptResult first = inNewTransaction(() -> service.processReceipt(receipt));
        PaymentReceiptResult replay = inNewTransaction(() -> service.processReceipt(receipt));

        assertThat(first.status()).isEqualTo(PaymentReceiptResult.Status.APPLIED);
        assertThat(replay.status()).isEqualTo(PaymentReceiptResult.Status.DUPLICATE);
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getPaidAt())
                .isEqualTo(ACCEPTED_AT);
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow()
                .getPaymentConfirmedAt()).isEqualTo(ACCEPTED_AT);
        assertThat(historyRepository.findByBooking_IdOrderByOccurredAtAscIdAsc(fixture.bookingId()))
                .singleElement()
                .satisfies(history -> assertThat(history.getOccurredAt()).isEqualTo(ACCEPTED_AT));
    }

    @Test
    void paymentAcceptedBeforeExpiry_thenExpirationCannotDowngrade() throws Exception {
        Instant holdExpiry = ACCEPTED_AT.plusSeconds(300);
        Fixture fixture = createFixture(holdExpiry);
        NormalizedReceipt receipt = receipt(
                fixture,
                "TX-RACE-PAY-FIRST-" + UUID.randomUUID(),
                ACCEPTED_AT.minusSeconds(30)
        );
        PaymentConfirmationServiceImpl paymentService = serviceAt(ACCEPTED_AT);
        BookingExpirationServiceImpl expirationService = new BookingExpirationServiceImpl(
                connectorRepository,
                bookingRepository,
                paymentRepository,
                historyRecorder
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<PaymentReceiptResult> paymentFuture = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not start in time");
                }
                return inNewTransaction(() -> paymentService.processReceipt(receipt));
            });

            Instant evalTime = holdExpiry.plusSeconds(10);
            Future<Boolean> expireFuture = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not start in time");
                }
                return inNewTransaction(() ->
                        expirationService.expireIfDue(fixture.bookingId(), fixture.connectorId(), evalTime));
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            PaymentReceiptResult paymentResult = paymentFuture.get(10, TimeUnit.SECONDS);
            Boolean expired = expireFuture.get(10, TimeUnit.SECONDS);

            if (paymentResult.status() == PaymentReceiptResult.Status.APPLIED) {
                assertThat(expired).isFalse();
                assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                        .isEqualTo(BookingStatus.CONFIRMED);
                assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus())
                        .isEqualTo(PaymentStatus.PAID);
            } else {
                assertThat(expired).isTrue();
                assertThat(paymentResult.status()).isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
                assertThat(paymentResult.reason()).isEqualTo("LATE");
                assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                        .isEqualTo(BookingStatus.EXPIRED);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expirationCommitsFirst_thenLateReceiptIsUnappliedAndDoesNotReviveBooking() {
        Instant holdExpiry = ACCEPTED_AT.minusSeconds(10);
        Fixture fixture = createFixture(holdExpiry);
        NormalizedReceipt receipt = receipt(
                fixture,
                "TX-EXPIRE-FIRST-" + UUID.randomUUID(),
                ACCEPTED_AT.minusSeconds(5)
        );

        BookingExpirationServiceImpl expirationService = new BookingExpirationServiceImpl(
                connectorRepository,
                bookingRepository,
                paymentRepository,
                historyRecorder
        );

        boolean expired = inNewTransaction(() ->
                expirationService.expireIfDue(fixture.bookingId(), fixture.connectorId(), ACCEPTED_AT));
        assertThat(expired).isTrue();
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);

        PaymentReceiptResult result = inNewTransaction(
                () -> serviceAt(ACCEPTED_AT).processReceipt(receipt));

        assertThat(result.status()).isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
        assertThat(result.reason()).isEqualTo("LATE");
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void stationPaused_receiptIsUnappliedWithStationUnavailable() {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        inNewTransaction(() -> {
            jdbcTemplate.update("UPDATE stations SET operational_status = 'PAUSED' WHERE id = (SELECT cp.station_id FROM charge_points cp JOIN connectors c ON c.charge_point_id = cp.id WHERE c.id = ?)", fixture.connectorId());
            return null;
        });

        NormalizedReceipt receipt = receipt(
                fixture,
                "TX-UNAVAILABLE-" + UUID.randomUUID(),
                ACCEPTED_AT.minusSeconds(30)
        );

        PaymentReceiptResult result = inNewTransaction(
                () -> serviceAt(ACCEPTED_AT).processReceipt(receipt));

        assertThat(result.status()).isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
        assertThat(result.reason()).isEqualTo("STATION_UNAVAILABLE");
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void hardwareServiceable_confirmsPaymentUnderBR_STA_05() {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        NormalizedReceipt receipt = receipt(
                fixture,
                "TX-SERVICEABLE-" + UUID.randomUUID(),
                ACCEPTED_AT.minusSeconds(30)
        );

        PaymentReceiptResult result = inNewTransaction(
                () -> serviceAt(ACCEPTED_AT).processReceipt(receipt));

        assertThat(result.status()).isEqualTo(PaymentReceiptResult.Status.APPLIED);
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PAID);
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
    }

    private PaymentReceiptResult processAfterBarrier(
            PaymentConfirmationServiceImpl service,
            NormalizedReceipt receipt,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent payment test did not start in time");
        }
        return inNewTransaction(() -> service.processReceipt(receipt));
    }

    private PaymentConfirmationServiceImpl serviceAt(Instant now) {
        return new PaymentConfirmationServiceImpl(
                connectorRepository,
                bookingRepository,
                paymentRepository,
                transactionRepository,
                historyRecorder,
                BookingPolicyConfig.defaults(),
                confirmationOutboxWriter,
                Clock.fixed(now, ZoneOffset.UTC)
        );
    }

    private NormalizedReceipt receipt(Fixture fixture, String transactionRef, Instant providerPaidAt) {
        return new NormalizedReceipt(
                "SIMULATOR",
                fixture.accountRef(),
                transactionRef,
                AMOUNT,
                "VND",
                providerPaidAt,
                ACCEPTED_AT,
                fixture.vaNumber(),
                fixture.paymentCode(),
                "Payment " + fixture.paymentCode(),
                "{\"provider\":\"SIMULATOR\"}"
        );
    }

    private Fixture createFixture(Instant holdExpiry) {
        return inNewTransaction(() -> {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            UUID ownerId = UUID.randomUUID();
            UUID driverId = UUID.randomUUID();
            UUID stationId = UUID.randomUUID();
            UUID chargePointId = UUID.randomUUID();
            UUID connectorId = UUID.randomUUID();
            UUID bookingId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            String paymentCode = "CO" + suffix.toUpperCase();
            String vaNumber = "VA" + suffix.toUpperCase();
            String accountRef = "ACC-" + suffix.toUpperCase();
            Instant startAt = ACCEPTED_AT.plusSeconds(3600);
            Instant endAt = startAt.plusSeconds(3600);

            jdbcTemplate.update("""
                    INSERT INTO user_profile(id, keycloak_id, email)
                    VALUES (?, ?, ?), (?, ?, ?)
                    """,
                    ownerId, "owner-" + suffix, "owner-" + suffix + "@example.test",
                    driverId, "driver-" + suffix, "driver-" + suffix + "@example.test");
            jdbcTemplate.update("""
                    INSERT INTO stations(id, owner_id, name, address_line, contact_phone, status, operational_status)
                    VALUES (?, ?, ?, 'Test Address', '0123456789', 'ACTIVE', 'OPERATING')
                    """, stationId, ownerId, "Payment Race Station " + suffix);
            jdbcTemplate.update("""
                    INSERT INTO charge_points(id, station_id, charge_point_code,
                                              provisioning_status, operational_status)
                    VALUES (?, ?, ?, 'ACTIVE', 'AVAILABLE')
                    """, chargePointId, stationId, "CP-RACE-" + suffix);
            jdbcTemplate.update("""
                    INSERT INTO connectors(id, charge_point_id, connector_code, connector_type,
                                           power_kw, charger_type, runtime_status)
                    VALUES (?, ?, ?, 'CCS2', 60.0, 'DC', 'AVAILABLE')
                    """, connectorId, chargePointId, "CON-RACE-" + suffix);
            jdbcTemplate.update("""
                    INSERT INTO bookings(id, driver_id, connector_id, start_at, end_at, status,
                                         total_amount, station_name_snapshot, station_address_snapshot,
                                         charge_point_code_snapshot, connector_code_snapshot, expires_at)
                    VALUES (?, ?, ?, ?, ?, 'PENDING', ?, 'Race Station', 'Test Address',
                            'CP-RACE', 'CON-RACE', ?)
                    """,
                    bookingId,
                    driverId,
                    connectorId,
                    Timestamp.from(startAt),
                    Timestamp.from(endAt),
                    AMOUNT,
                    Timestamp.from(holdExpiry));
            jdbcTemplate.update("""
                    INSERT INTO payments(id, booking_id, amount, status, method, environment, refund_amount,
                                         provider, receiving_account_ref, currency, version,
                                         needs_reconciliation, payment_code, provider_order_ref,
                                         va_number, provider_expires_at)
                    VALUES (?, ?, ?, 'PENDING', 'SIMULATOR', 'SIMULATOR', 0, 'SIMULATOR', ?, 'VND', 0,
                            false, ?, ?, ?, ?)
                    """,
                    paymentId,
                    bookingId,
                    AMOUNT,
                    accountRef,
                    paymentCode,
                    "ORDER-" + suffix,
                    vaNumber,
                    Timestamp.from(holdExpiry));

            return new Fixture(
                    connectorId,
                    bookingId,
                    paymentId,
                    paymentCode,
                    vaNumber,
                    accountRef,
                    startAt,
                    endAt
            );
        });
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> work.get());
    }

    private record Fixture(
            UUID connectorId,
            UUID bookingId,
            UUID paymentId,
            String paymentCode,
            String vaNumber,
            String accountRef,
            Instant startAt,
            Instant endAt
    ) {
    }
}
