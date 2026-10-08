package com.thang.chargeops.booking;

import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.command.BookingCommandRepository;
import com.thang.chargeops.booking.dto.request.CancelBookingRequest;
import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.enums.CancellationRefundTier;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.policy.BookingCancellationPolicy;
import com.thang.chargeops.booking.policy.model.CancellationRefundDecision;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.DriverBookingDetailAssembler;
import com.thang.chargeops.booking.service.impl.BookingCancellationServiceImpl;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.notification.model.NotificationAudience;
import com.thang.chargeops.notification.model.NotificationEventType;
import com.thang.chargeops.notification.repository.AppNotificationRepository;
import com.thang.chargeops.notification.service.NotificationPersistenceService;
import com.thang.chargeops.notification.service.NotificationService;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.model.RefundStatus;
import com.thang.chargeops.refund.model.RefundAutoDispatchStatus;
import com.thang.chargeops.refund.model.RefundExecutionPolicy;
import com.thang.chargeops.refund.model.RefundExecutionTrigger;
import com.thang.chargeops.refund.executor.RefundExecutorRegistry;
import com.thang.chargeops.refund.executor.RefundExecutor;
import com.thang.chargeops.refund.executor.RefundExecutionCommand;
import com.thang.chargeops.refund.executor.RefundExecutionResult;
import com.thang.chargeops.refund.executor.SimulatorRefundExecutor;
import com.thang.chargeops.refund.dto.request.RefundExecutionOutcome;
import com.thang.chargeops.refund.model.RefundExecutionMode;
import com.thang.chargeops.refund.repository.RefundAttemptRepository;
import com.thang.chargeops.refund.repository.RefundAutoDispatchRepository;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.refund.service.RefundObligationService;
import com.thang.chargeops.refund.service.RefundExecutionResultHandler;
import com.thang.chargeops.refund.service.impl.AutomaticRefundExecutionServiceImpl;
import com.thang.chargeops.refund.service.impl.RefundObligationServiceImpl;
import com.thang.chargeops.station.repository.ConnectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import({
        BookingCommandRegistry.class,
        BookingStatusHistoryRecorder.class,
        RefundObligationServiceImpl.class,
        NotificationService.class,
        NotificationPersistenceService.class,
        com.thang.chargeops.notification.outbox.BookingConfirmationOutboxWriter.class,
        com.thang.chargeops.notification.outbox.NotificationOutboxStore.class,
        com.thang.chargeops.notification.email.NotificationEmailDeliveryStore.class,
        com.thang.chargeops.notification.email.BookingEventEmailSource.class,
        BookingCancellationPostgresTest.EmailTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingCancellationPostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("126000.00");
    private static final String POLICY_VERSION = "booking-v4.9";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserProfileRepository userProfileRepository;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentTransactionRepository paymentTransactionRepository;
    @Autowired private BookingCommandRegistry bookingCommandRegistry;
    @Autowired private BookingCommandRepository bookingCommandRepository;
    @Autowired private BookingStatusHistoryRecorder bookingStatusHistoryRecorder;
    @Autowired private RefundObligationService refundObligationService;
    @Autowired private RefundRepository refundRepository;
    @Autowired private RefundAttemptRepository refundAttemptRepository;
    @Autowired private RefundAutoDispatchRepository autoDispatchRepository;
    @Autowired private com.thang.chargeops.notification.outbox.BookingEventOutboxWriter eventOutboxWriter;
    @Autowired private NotificationService notificationService;
    @Autowired private AppNotificationRepository notificationRepository;
    @Autowired private com.thang.chargeops.notification.outbox.BookingConfirmationOutboxWriter notificationOutboxWriter;
    @Autowired private com.thang.chargeops.notification.outbox.NotificationOutboxRepository outboxRepository;
    @Autowired private com.thang.chargeops.notification.outbox.NotificationOutboxStore outboxStore;
    @Autowired private com.thang.chargeops.notification.email.NotificationEmailDeliveryStore emailStore;
    @Autowired private com.thang.chargeops.notification.email.NotificationEmailDeliveryRepository emailRepository;
    @Autowired private tools.jackson.databind.ObjectMapper json;

    @org.springframework.boot.test.context.TestConfiguration
    static class EmailTestConfiguration {
        @org.springframework.context.annotation.Bean
        tools.jackson.databind.ObjectMapper jsonMapper() { return tools.jackson.databind.json.JsonMapper.builder().build(); }
    }

    private BookingCancellationServiceImpl service;
    private BookingCancellationPolicy policy;
    private UUID driverId;
    private UUID bookingId;

    @BeforeEach
    void setUp() {
        Fixture fixture = insertConfirmedPaidFixture();
        driverId = fixture.driverId();
        bookingId = fixture.bookingId();

        CurrentProfileProvider currentProfileProvider = mock(CurrentProfileProvider.class);
        UserProfile currentDriver = mock(UserProfile.class);
        when(currentDriver.getId()).thenReturn(driverId);
        when(currentProfileProvider.requireProfile()).thenReturn(currentDriver);

        policy = mock(BookingCancellationPolicy.class);
        when(policy.calculateRefund(any(), eq(NOW), eq(false)))
                .thenReturn(new CancellationRefundDecision(
                        CancellationRefundTier.GRACE,
                        100,
                        AMOUNT,
                        BigDecimal.ZERO,
                        NOW.plusSeconds(600)
                ));

        DriverBookingDetailAssembler assembler = mock(DriverBookingDetailAssembler.class);
        when(assembler.assemble(any(), any(), eq(NOW))).thenAnswer(invocation -> {
            var booking = (com.thang.chargeops.booking.entity.Booking) invocation.getArgument(0);
            return BookingDetailResponse.builder()
                    .bookingId(booking.getId())
                    .status(booking.getStatus())
                    .persistedStatus(booking.getStatus())
                    .version(booking.getVersion())
                    .build();
        });

        service = new BookingCancellationServiceImpl(
                currentProfileProvider,
                userProfileRepository,
                connectorRepository,
                bookingRepository,
                paymentRepository,
                paymentTransactionRepository,
                bookingCommandRegistry,
                bookingStatusHistoryRecorder,
                policy,
                refundObligationService,
                assembler,
                notificationService,
                notificationOutboxWriter,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "chargeops.smtp.integration", matches = "true")
    void cancelledBookingTravelsThroughRealKafkaAndMailpitOnce() throws Exception {
        cancelInTransaction(UUID.randomUUID());
        var event = cancellationEvent();
        isolatePendingEmail();
        jdbc.update("UPDATE notification_outbox SET next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '1 day' "
                + "WHERE status='PENDING' AND event_id<>?", event.eventId());
        String topic = "chargeops.lab.cancel." + UUID.randomUUID();
        String group = "chargeops-lab-cancel-" + UUID.randomUUID();
        String broker = System.getProperty("chargeops.kafka.bootstrap-servers", "localhost:9092");
        var producerProperties = new java.util.HashMap<String, Object>();
        producerProperties.put("bootstrap.servers", broker);
        producerProperties.put("key.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
        producerProperties.put("value.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
        producerProperties.put("acks", "all");
        var producer = new org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String>(producerProperties);
        var kafka = new org.springframework.kafka.core.KafkaTemplate<String, String>(producer);
        var consumerProperties = new java.util.HashMap<String, Object>();
        consumerProperties.put("bootstrap.servers", broker);
        consumerProperties.put("group.id", group);
        consumerProperties.put("key.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
        consumerProperties.put("value.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
        consumerProperties.put("enable.auto.commit", false);
        consumerProperties.put("auto.offset.reset", "earliest");
        var factory = new org.springframework.kafka.core.DefaultKafkaConsumerFactory<String, String>(consumerProperties);
        var properties = new org.springframework.kafka.listener.ContainerProperties(topic);
        properties.setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        var listener = new com.thang.chargeops.notification.kafka.BookingNotificationEmailConsumer(emailStore, json);
        properties.setMessageListener((org.springframework.kafka.listener.MessageListener<String, String>) listener::receiveConfirmed);
        var container = new org.springframework.kafka.listener.ConcurrentMessageListenerContainer<>(factory, properties);
        var sender = new org.springframework.mail.javamail.JavaMailSenderImpl();
        sender.setHost("localhost"); sender.setPort(1025);
        sender.getJavaMailProperties().putAll(java.util.Map.of("mail.smtp.connectiontimeout", "3000",
                "mail.smtp.timeout", "10000", "mail.smtp.writetimeout", "10000"));
        var smtp = emailWorker(sender);
        var publisher = new com.thang.chargeops.notification.kafka.BookingNotificationPublisher(kafka, json, topic);
        var worker = new com.thang.chargeops.notification.outbox.BookingNotificationOutboxWorker(outboxStore, publisher, json, 1, 10);
        try (var admin = org.apache.kafka.clients.admin.AdminClient.create(java.util.Map.of("bootstrap.servers", broker))) {
            admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(topic, 3, (short) 1))).all().get(10, TimeUnit.SECONDS);
            try {
                container.start();
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).untilAsserted(() ->
                        assertThat(container.getAssignedPartitions()).hasSize(3));
                worker.dispatchPending();
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
                        assertThat(emailState()).containsEntry("status", "PENDING"));
                smtp.dispatchPending();
                kafka.send(topic, bookingId.toString(), json.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> {
                    var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                    assertThat(offsets.values().stream().mapToLong(org.apache.kafka.clients.consumer.OffsetAndMetadata::offset).sum()).isEqualTo(2);
                });
                smtp.dispatchPending();
                assertThat(emailState()).containsEntry("status", "SENT").containsEntry("attempt_count", 1);
                assertThat(outboxRepository.findById(event.eventId()).orElseThrow().getStatus()).isEqualTo("PUBLISHED");
                String email = jdbc.queryForObject("SELECT email FROM user_profile WHERE id=?", String.class, driverId);
                var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
                String search = "http://localhost:8025/api/v1/search?query=" + java.net.URLEncoder.encode("to:" + email,
                        java.nio.charset.StandardCharsets.UTF_8);
                var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(search))
                        .timeout(java.time.Duration.ofSeconds(5)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                var result = json.readTree(response.body());
                assertThat(result.get("messages").size()).isEqualTo(1);
                assertThat(result.get("messages").get(0).get("Subject").asString()).contains("Đã hủy booking");
                var mail = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "http://localhost:8025/api/v1/message/" + result.get("messages").get(0).get("ID").asString()))
                        .timeout(java.time.Duration.ofSeconds(5)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                var content = json.readTree(mail.body());
                assertThat(content.get("HTML").asString()).contains("đang chờ xử lý", bookingId.toString());
                assertThat(content.get("Text").asString()).contains("đang chờ xử lý", "126.000");
            } finally {
                container.stop();
                admin.deleteTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS);
            }
        } finally {
            worker.shutdown(); smtp.shutdown(); kafka.destroy(); producer.destroy();
        }
    }

    @Test
    void cancellationOutboxKeepsCommandIdentityOnWriterReplay() {
        UUID requestKey = UUID.randomUUID();
        cancelInTransaction(requestKey);
        var event = cancellationEvent();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> notificationOutboxWriter.recordCancelled(
                bookingCommandRepository.findByActor_IdAndOperationAndRequestKey(driverId,
                        BookingCommandOperation.CANCEL_BOOKING, requestKey).orElseThrow()));
        assertThat(cancellationEvent()).isEqualTo(event);
        var row = outboxRepository.findById(event.eventId()).orElseThrow();
        assertThat(row.getSourceReceiptId()).isNull();
        assertThat(row.getSourceCommandId()).isNotNull();
        assertThat(row.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void cancelledConsumerRejectsForgedPayloadAndDedupePreservesSent() throws Exception {
        cancelInTransaction(UUID.randomUUID());
        var event = cancellationEvent();
        var forged = new com.thang.chargeops.notification.kafka.BookingNotificationEvent(event.eventId(), 1,
                event.eventType(), event.sourceEventKey(), event.bookingId(), event.stationId(), event.recipientId(),
                event.audience(), event.occurredAt().plusSeconds(1));
        assertThatThrownBy(() -> emailStore.captureCancelled(forged))
                .isInstanceOf(com.thang.chargeops.notification.kafka.InvalidBookingNotificationException.class);
        assertThat(emailStore.captureCancelled(event)).isTrue();
        isolatePendingEmail();
        jdbc.update("UPDATE user_profile SET email=?, display_name=? WHERE id=?",
                "latest-" + UUID.randomUUID() + "@example.test", "An <script>", driverId);
        var sender = org.mockito.Mockito.spy(new org.springframework.mail.javamail.JavaMailSenderImpl());
        var sent = new java.util.ArrayList<jakarta.mail.internet.MimeMessage>();
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(emailState().get("send_started_at")).isNotNull();
            sent.add(invocation.getArgument(0));
            return null;
        }).when(sender).send(org.mockito.ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class));
        var worker = emailWorker(sender);
        try {
            worker.dispatchPending();
            assertThat(emailStore.captureCancelled(event)).isFalse();
            worker.dispatchPending();
        } finally { worker.shutdown(); }
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().getSubject()).contains("Đã hủy booking");
        assertThat(sent.getFirst().getAllRecipients()[0].toString()).startsWith("latest-");
        assertThat(sent.getFirst().getHeader("Message-ID", null)).contains(emailState().get("delivery_id").toString());
        var claimModel = new com.thang.chargeops.notification.email.BookingEmailRenderer().renderBookingCancelled(
                cancellationPreparedBeforeSendForPreview(event));
        assertThat(claimModel.html()).contains("An &lt;script&gt;", "đang chờ xử lý").doesNotContain("An <script>", "đã hoàn tất");
        assertThat(claimModel.text()).contains("đang chờ xử lý", "/bookings/" + bookingId);
        assertThat(emailState()).containsEntry("status", "SENT").containsEntry("attempt_count", 1);
    }

    private com.thang.chargeops.notification.email.BookingCancelledEmailModel cancellationPreparedBeforeSendForPreview(
            com.thang.chargeops.notification.kafka.BookingNotificationEvent event) {
        return new TransactionTemplate(transactionManager).execute(status ->
                com.thang.chargeops.notification.email.BookingCancelledEmailModel.cancelled(
                        emailRepository.findCancelledContext(event.eventId()).orElseThrow(), "http://localhost:5173", ""));
    }

    @Test
    void unpaidCancellationEmailHasNoReceiptAndNoRefundClaim() {
        jdbc.update("DELETE FROM payment_transactions WHERE payment_id=(SELECT id FROM payments WHERE booking_id=?)", bookingId);
        jdbc.update("UPDATE bookings SET status='PENDING',payment_confirmed_at=NULL,free_cancellation_deadline=NULL,"
                + "check_in_deadline=NULL,expires_at=? WHERE id=?", Timestamp.from(NOW.plusSeconds(600)), bookingId);
        jdbc.update("UPDATE payments SET status='PENDING',paid_at=NULL WHERE booking_id=?", bookingId);
        cancelWithoutRefund();
        var event = cancellationEvent();
        assertThat(emailStore.captureCancelled(event)).isTrue();
        isolatePendingEmail();
        var claim = emailStore.claimDelivery(180, 5).orElseThrow();
        var model = emailStore.prepare(claim, "http://localhost:5173", "").orElseThrow().cancelled();
        assertThat(model.refundText()).contains("trước khi thanh toán", "không phát sinh hoàn tiền");
        assertThat(outboxRepository.findById(event.eventId()).orElseThrow().getSourceReceiptId()).isNull();
    }

    @Test
    void cancellationSendRechecksRecipientAfterPreparation() {
        cancelInTransaction(UUID.randomUUID());
        emailStore.captureCancelled(cancellationEvent());
        isolatePendingEmail();
        var claim = emailStore.claimDelivery(180, 5).orElseThrow();
        var model = emailStore.prepare(claim, "http://localhost:5173", "").orElseThrow();
        jdbc.update("UPDATE user_profile SET status='SUSPENDED' WHERE id=?", driverId);
        assertThat(emailStore.beginSend(claim, model, "http://localhost:5173", "", 5)).isFalse();
        assertThat(emailState()).containsEntry("status", "SKIPPED").containsEntry("skip_reason", "RECIPIENT_INACTIVE");
    }

    @Test
    void cancellationSendRechecksRefundOutcomeAfterRendering() {
        cancelInTransaction(UUID.randomUUID());
        emailStore.captureCancelled(cancellationEvent());
        isolatePendingEmail();
        var claim = emailStore.claimDelivery(180, 5).orElseThrow();
        var prepared = emailStore.prepare(claim, "http://localhost:5173", "").orElseThrow();
        assertThat(prepared.cancelled().refundText()).contains("đang chờ xử lý");
        var refund = refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId).getFirst();
        var execution = new AutomaticRefundExecutionServiceImpl(connectorRepository, bookingRepository,
                paymentRepository, paymentTransactionRepository, refundRepository, refundAttemptRepository,
                autoDispatchRepository, new RefundExecutorRegistry(List.of(new SimulatorRefundExecutor())),
                new RefundExecutionResultHandler(refundAttemptRepository, refundRepository, paymentRepository, eventOutboxWriter),
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        new TransactionTemplate(transactionManager).execute(status -> execution.processFirstAttempt(refund.getId()));
        assertThat(emailStore.beginSend(claim, prepared, "http://localhost:5173", "", 5)).isFalse();
        assertThat(emailState()).containsEntry("status", "PENDING").containsEntry("sanitized_last_error", "EMAIL_CONTEXT_CHANGED");
        jdbc.update("UPDATE notification_email_deliveries SET next_attempt_at=CURRENT_TIMESTAMP WHERE delivery_id=?", emailState().get("delivery_id"));
        var next = emailStore.claimDelivery(180, 5).orElseThrow();
        var refreshed = emailStore.prepare(next, "http://localhost:5173", "").orElseThrow();
        assertThat(refreshed.cancelled().refundText()).contains("mô phỏng", "đã hoàn tất").doesNotContain("tài khoản ngân hàng");
        assertThat(emailStore.beginSend(next, refreshed, "http://localhost:5173", "", 5)).isTrue();
    }

    private com.thang.chargeops.notification.kafka.BookingNotificationEvent cancellationEvent() {
        var rows = outboxRepository.findAll().stream().filter(row -> row.getBookingId().equals(bookingId)
                && "BOOKING_CANCELLED".equals(row.getEventType())).toList();
        assertThat(rows).hasSize(1);
        return json.readValue(rows.getFirst().getPayload(), com.thang.chargeops.notification.kafka.BookingNotificationEvent.class);
    }

    private void isolatePendingEmail() {
        jdbc.update("UPDATE notification_email_deliveries SET next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '1 day' "
                + "WHERE status='PENDING' AND event_id NOT IN (SELECT event_id FROM notification_outbox WHERE booking_id=?)", bookingId);
        jdbc.update("UPDATE notification_email_deliveries SET lease_until=CURRENT_TIMESTAMP+INTERVAL '1 day' "
                + "WHERE status='PROCESSING' AND event_id NOT IN (SELECT event_id FROM notification_outbox WHERE booking_id=?)", bookingId);
    }

    private java.util.Map<String, Object> emailState() {
        return jdbc.queryForMap("SELECT d.* FROM notification_email_deliveries d JOIN notification_outbox o ON o.event_id=d.event_id WHERE o.booking_id=?", bookingId);
    }

    private com.thang.chargeops.notification.email.BookingNotificationEmailWorker emailWorker(
            org.springframework.mail.javamail.JavaMailSenderImpl sender) {
        return new com.thang.chargeops.notification.email.BookingNotificationEmailWorker(emailStore,
                new com.thang.chargeops.notification.email.BookingEmailRenderer(), sender, "noreply@chargeops.test",
                "http://localhost:5173", "", 1, 5);
    }

    @Test
    void cancellationNoticeTargetsOnlyDriverAndReplayPreservesReadState() {
        UUID requestKey = UUID.randomUUID();
        cancelInTransaction(requestKey);
        var notices = cancellationNotices();
        assertThat(notices).singleElement().satisfies(notice -> {
            assertThat(notice.getRecipientId()).isEqualTo(driverId);
            assertThat(notice.getAudience()).isEqualTo(NotificationAudience.DRIVER);
            assertThat(notice.getCategory()).isEqualTo("booking");
            assertThat(notice.getActionUrl()).isEqualTo("/bookings/" + bookingId);
            var command = bookingCommandRepository.findByActor_IdAndOperationAndRequestKey(
                    driverId, BookingCommandOperation.CANCEL_BOOKING, requestKey).orElseThrow();
            assertThat(notice.getSourceEventKey()).isEqualTo(command.getId() + ":BOOKING_CANCELLED");
        });
        UUID noticeId = notices.getFirst().getId();
        Instant readAt = NOW.plusSeconds(2);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var notice = notificationRepository.findById(noticeId).orElseThrow();
            notice.markRead(readAt);
            notice.dismiss(readAt);
        });
        cancelInTransaction(requestKey);
        assertThat(cancellationNotices()).singleElement().satisfies(notice -> {
            assertThat(notice.getId()).isEqualTo(noticeId);
            assertThat(notice.getReadAt()).isEqualTo(readAt);
            assertThat(notice.getDismissedAt()).isEqualTo(readAt);
        });
    }

    @Test
    void rollbackAfterNoticeInsertLeavesNoCancellationHistoryOrRefund() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.cancelBooking(bookingId, UUID.randomUUID(),
                    new CancelBookingRequest(0L, AMOUNT.longValueExact(), POLICY_VERSION));
            assertThat(cancellationNotices()).hasSize(1);
            status.setRollbackOnly();
        });
        assertThat(cancellationNotices()).isEmpty();
        assertThat(countRows("notification_outbox", "booking_id", bookingId)).isZero();
        assertThat(bookingRepository.findById(bookingId)).get()
                .extracting(com.thang.chargeops.booking.entity.Booking::getStatus)
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(countRows("booking_commands", "booking_id", bookingId)).isZero();
        assertThat(countRows("booking_status_history", "booking_id", bookingId)).isZero();
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId)).isEmpty();
    }

    @Test
    void unpaidCancellationStillCreatesNoticeWithoutRefund() {
        jdbc.update("UPDATE bookings SET status='PENDING', payment_confirmed_at=NULL, "
                + "free_cancellation_deadline=NULL, check_in_deadline=NULL, expires_at=? WHERE id=?",
                Timestamp.from(NOW.plusSeconds(600)), bookingId);
        jdbc.update("UPDATE payments SET status='PENDING', paid_at=NULL WHERE booking_id=?", bookingId);
        cancelWithoutRefund();
        assertThat(cancellationNotices()).hasSize(1);
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId)).isEmpty();
    }

    @Test
    void paidCancellationOutsideGraceCreatesNoticeWithoutClaimingRefundSucceeded() {
        jdbc.update("UPDATE bookings SET free_cancellation_deadline=? WHERE id=?",
                Timestamp.from(NOW.minusSeconds(1)), bookingId);
        cancelWithoutRefund();
        assertThat(cancellationNotices()).singleElement().satisfies(notice ->
                assertThat(notice.getEventType()).isEqualTo(NotificationEventType.BOOKING_CANCELLED));
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId)).isEmpty();
    }

    private void cancelWithoutRefund() {
        when(policy.calculateRefund(any(), eq(NOW), eq(false))).thenReturn(
                new CancellationRefundDecision(CancellationRefundTier.NONE, 0, BigDecimal.ZERO,
                        AMOUNT, NOW.minusSeconds(1)));
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.cancelBooking(bookingId, UUID.randomUUID(),
                        new CancelBookingRequest(0L, 0L, POLICY_VERSION)));
    }

    private List<com.thang.chargeops.notification.entity.AppNotification> cancellationNotices() {
        return notificationRepository.findAll((root, query, cb) -> cb.and(
                cb.equal(root.get("bookingId"), bookingId),
                cb.equal(root.get("eventType"), NotificationEventType.BOOKING_CANCELLED)));
    }

    @Test
    void successfulCancellationCommitsVersionHistoryCommandAndPendingRefund() {
        UUID requestKey = UUID.randomUUID();

        BookingDetailResponse response = cancelInTransaction(requestKey);

        var savedBooking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(response.version()).isEqualTo(1L);
        assertThat(savedBooking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(savedBooking.getVersion()).isEqualTo(1L);
        assertThat(bookingCommandRepository.findByActor_IdAndOperationAndRequestKey(
                driverId,
                BookingCommandOperation.CANCEL_BOOKING,
                requestKey
        )).isPresent();
        assertThat(countRows("booking_status_history", "booking_id", bookingId)).isEqualTo(1);
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId))
                .singleElement()
                .satisfies(refund -> {
                    assertThat(refund.getAmount()).isEqualByComparingTo(AMOUNT);
                    assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING);
                    assertThat(refund.getExecutionPolicy()).isEqualTo(RefundExecutionPolicy.AUTO_FIRST_ATTEMPT);
                    assertThat(refund.isRequiresOwnerAction()).isFalse();
                    assertThat(autoDispatchRepository.findRefundIdsByStatus(
                            RefundAutoDispatchStatus.PENDING,
                            org.springframework.data.domain.PageRequest.of(0, 10)
                    )).contains(refund.getId());
                });
    }

    @Test
    void graceCancellationDispatchesOneAutomaticSystemAttemptAfterCommit() {
        cancelInTransaction(UUID.randomUUID());
        UUID refundId = refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId)
                .getFirst().getId();
        AutomaticRefundExecutionServiceImpl autoExecution = new AutomaticRefundExecutionServiceImpl(
                connectorRepository,
                bookingRepository,
                paymentRepository,
                paymentTransactionRepository,
                refundRepository,
                refundAttemptRepository,
                autoDispatchRepository,
                new RefundExecutorRegistry(List.of(new SimulatorRefundExecutor())),
                new RefundExecutionResultHandler(refundAttemptRepository, refundRepository, paymentRepository, eventOutboxWriter),
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC)
        );

        Boolean processed = new TransactionTemplate(transactionManager)
                .execute(status -> autoExecution.processFirstAttempt(refundId));
        Boolean replayed = new TransactionTemplate(transactionManager)
                .execute(status -> autoExecution.processFirstAttempt(refundId));

        assertThat(processed).isTrue();
        assertThat(replayed).isFalse();
        assertThat(refundRepository.findById(refundId)).get().satisfies(refund -> {
            assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
            assertThat(refund.isRequiresOwnerAction()).isFalse();
        });
        assertThat(paymentRepository.findByBookingId(bookingId)).get()
                .extracting(com.thang.chargeops.payment.entity.Payment::getStatus)
                .isEqualTo(com.thang.chargeops.common.enums.PaymentStatus.REFUNDED);
        assertThat(refundAttemptRepository.findByRefundIdOrderBySequenceNoAsc(refundId))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getExecutionTrigger()).isEqualTo(RefundExecutionTrigger.SYSTEM_POLICY);
                    assertThat(attempt.getPerformedBy()).isNull();
                    assertThat(attempt.getSequenceNo()).isEqualTo(1);
                });
        assertThat(autoDispatchRepository.findRefundIdsByStatus(
                RefundAutoDispatchStatus.PENDING,
                org.springframework.data.domain.PageRequest.of(0, 10)
        )).doesNotContain(refundId);
    }

    @Test
    void failedAutomaticAttemptRequiresAdminAndIsNeverAutomaticallyRetried() {
        cancelInTransaction(UUID.randomUUID());
        UUID refundId = refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId)
                .getFirst().getId();
        RefundExecutor failingExecutor = new RefundExecutor() {
            @Override
            public RefundExecutionMode mode() {
                return RefundExecutionMode.SIMULATOR;
            }

            @Override
            public RefundExecutionResult execute(RefundExecutionCommand command) {
                return new RefundExecutionResult(
                        RefundExecutionOutcome.FAILED,
                        "SIMULATOR",
                        null,
                        "SIMULATED_FAILURE",
                        command.executionAt(),
                        "Automatic attempt failed"
                );
            }
        };
        AutomaticRefundExecutionServiceImpl autoExecution = new AutomaticRefundExecutionServiceImpl(
                connectorRepository, bookingRepository, paymentRepository, paymentTransactionRepository,
                refundRepository, refundAttemptRepository, autoDispatchRepository,
                new RefundExecutorRegistry(List.of(failingExecutor)),
                new RefundExecutionResultHandler(refundAttemptRepository, refundRepository, paymentRepository, eventOutboxWriter),
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC)
        );

        new TransactionTemplate(transactionManager).execute(status -> autoExecution.processFirstAttempt(refundId));
        Boolean replayed = new TransactionTemplate(transactionManager)
                .execute(status -> autoExecution.processFirstAttempt(refundId));

        assertThat(replayed).isFalse();
        assertThat(refundRepository.findById(refundId)).get().satisfies(refund -> {
            assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING);
            assertThat(refund.isRequiresOwnerAction()).isTrue();
        });
        assertThat(refundAttemptRepository.findByRefundIdOrderBySequenceNoAsc(refundId))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getStatus().name()).isEqualTo("FAILED");
                    assertThat(attempt.getExecutionTrigger()).isEqualTo(RefundExecutionTrigger.SYSTEM_POLICY);
                });
    }

    @Test
    void refundFailureRollsBackBookingCommandHistoryAndRefund() {
        jdbc.update("UPDATE payments SET environment='LEGACY' WHERE booking_id=?", bookingId);

        assertThatThrownBy(() -> cancelInTransaction(UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(RefundErrorCode.EXECUTION_CONFLICT)
                );

        var savedBooking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(savedBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(savedBooking.getVersion()).isZero();
        assertThat(countRows("booking_commands", "booking_id", bookingId)).isZero();
        assertThat(countRows("booking_status_history", "booking_id", bookingId)).isZero();
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId)).isEmpty();
        assertThat(cancellationNotices()).isEmpty();
    }

    @Test
    void concurrentSameRequestKeyCommitsExactlyOneDecision() throws Exception {
        UUID requestKey = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<BookingDetailResponse> first = pool.submit(
                    () -> cancelAfterBarrier(requestKey, ready, start)
            );
            Future<BookingDetailResponse> second = pool.submit(
                    () -> cancelAfterBarrier(requestKey, ready, start)
            );
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<BookingDetailResponse> responses = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
            assertThat(responses).allSatisfy(response -> {
                assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
                assertThat(response.version()).isEqualTo(1L);
            });
        }

        assertThat(countRows("booking_commands", "booking_id", bookingId)).isEqualTo(1);
        assertThat(countRows("booking_status_history", "booking_id", bookingId)).isEqualTo(1);
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId)).hasSize(1);
        assertThat(cancellationNotices()).hasSize(1);
        assertThat(countRows("notification_outbox", "booking_id", bookingId)).isEqualTo(2);
        assertThat(outboxRepository.findAll().stream().filter(row -> row.getBookingId().equals(bookingId)))
                .extracting(com.thang.chargeops.notification.outbox.NotificationOutbox::getEventType)
                .containsExactlyInAnyOrder("BOOKING_CANCELLED", "REFUND_PENDING");
    }

    private BookingDetailResponse cancelAfterBarrier(
            UUID requestKey,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return cancelInTransaction(requestKey);
    }

    private BookingDetailResponse cancelInTransaction(UUID requestKey) {
        return new TransactionTemplate(transactionManager).execute(status -> service.cancelBooking(
                bookingId,
                requestKey,
                new CancelBookingRequest(0L, AMOUNT.longValueExact(), POLICY_VERSION)
        ));
    }

    private long countRows(String table, String column, UUID value) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Long.class,
                value
        );
    }

    private Fixture insertConfirmedPaidFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        UUID fixtureDriverId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UUID chargePointId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID fixtureBookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String paymentCode = "C" + suffix.toUpperCase();

        jdbc.update("""
                INSERT INTO user_profile(id, keycloak_id, email, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, fixtureDriverId, "cancel-" + suffix, "cancel-" + suffix + "@example.test");
        jdbc.update("""
                INSERT INTO stations(id, owner_id, name, address_line, contact_phone, status)
                VALUES (?, ?, ?, 'Test Address', '0123456789', 'ACTIVE')
                """, stationId, fixtureDriverId, "Cancellation Station " + suffix);
        jdbc.update("""
                INSERT INTO charge_points(id, station_id, charge_point_code,
                                          provisioning_status, operational_status)
                VALUES (?, ?, ?, 'ACTIVE', 'AVAILABLE')
                """, chargePointId, stationId, "CP-CAN-" + suffix);
        jdbc.update("""
                INSERT INTO connectors(id, charge_point_id, connector_code, connector_type,
                                       power_kw, charger_type, runtime_status)
                VALUES (?, ?, ?, 'CCS2', 60.0, 'DC', 'AVAILABLE')
                """, connectorId, chargePointId, "CON-CAN-" + suffix);
        jdbc.update("""
                INSERT INTO bookings(
                    id, driver_id, connector_id, booking_code, start_at, end_at, status,
                    total_amount, station_name_snapshot, station_address_snapshot,
                    charge_point_code_snapshot, connector_code_snapshot, expires_at,
                    policy_version, payment_confirmed_at, free_cancellation_deadline,
                    check_in_deadline, version
                )
                VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, 'Cancellation Station',
                        'Test Address', 'CP-CAN', 'CON-CAN', ?, ?, ?, ?, ?, 0)
                """,
                fixtureBookingId,
                fixtureDriverId,
                connectorId,
                "BK-CAN-" + suffix,
                Timestamp.from(NOW.plusSeconds(3600)),
                Timestamp.from(NOW.plusSeconds(7200)),
                AMOUNT,
                Timestamp.from(NOW.minusSeconds(600)),
                POLICY_VERSION,
                Timestamp.from(NOW.minusSeconds(300)),
                Timestamp.from(NOW.plusSeconds(600)),
                Timestamp.from(NOW.plusSeconds(5400))
        );
        jdbc.update("""
                INSERT INTO payments(
                    id, booking_id, amount, status, method, refund_amount, paid_at, provider,
                    receiving_account_ref, currency, version, needs_reconciliation,
                    payment_code, environment
                )
                VALUES (?, ?, ?, 'PAID', 'SIMULATOR', 0, ?, 'SIMULATOR', 'SIMULATOR',
                        'VND', 0, false, ?, 'SIMULATOR')
                """, paymentId, fixtureBookingId, AMOUNT, Timestamp.from(NOW.minusSeconds(300)), paymentCode);
        jdbc.update("""
                INSERT INTO payment_transactions(
                    payment_id, provider, receiving_account_ref, transaction_ref, amount,
                    currency, received_at, application_classification, payment_code,
                    va_number, version
                )
                VALUES (?, 'SIMULATOR', 'SIMULATOR', ?, ?, 'VND', ?, 'APPLIED', ?, ?, 0)
                """,
                paymentId,
                "TX-" + paymentCode,
                AMOUNT,
                Timestamp.from(NOW.minusSeconds(300)),
                paymentCode,
                "VA-" + paymentCode
        );

        return new Fixture(fixtureDriverId, fixtureBookingId);
    }

    private record Fixture(UUID driverId, UUID bookingId) {
    }
}
