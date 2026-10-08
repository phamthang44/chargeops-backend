package com.thang.chargeops.booking;

import com.thang.chargeops.booking.command.BookingCommandInFlightLock;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.service.PaymentConfirmationService;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.service.AutomaticRefundExecutionService;
import com.thang.chargeops.refund.service.RefundObligationService;
import com.thang.chargeops.refund.dto.request.RefundExecutionOutcome;
import com.thang.chargeops.refund.executor.RefundExecutionResult;
import com.thang.chargeops.refund.executor.SimulatorRefundExecutor;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false", "spring.task.scheduling.enabled=false",
        "app.refund.auto-execution.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class OwnerCancellationPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-26T03:00:00Z");
    private static final Instant START_AT = NOW.plusSeconds(7200);
    private static final String REASON = "Trạm mất điện, không thể phục vụ đặt chỗ.";
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private UserProfileRepository userProfileRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentConfirmationService paymentConfirmationService;
    @Autowired private AutomaticRefundExecutionService automaticRefundExecutionService;
    @MockitoSpyBean private RefundObligationService obligations;
    @MockitoSpyBean private SimulatorRefundExecutor simulatorRefundExecutor;
    @MockitoSpyBean private com.thang.chargeops.support.service.AdminFinanceAccessAudit accessAudit;
    @MockitoBean private CurrentProfileProvider currentProfileProvider;
    @MockitoBean private Clock applicationClock;
    @MockitoSpyBean private com.thang.chargeops.booking.config.BookingPolicyConfig reminderBookingPolicy;
    @MockitoBean private BookingCommandInFlightLock bookingCommandInFlightLock;
    private final AtomicReference<UUID> currentActorId = new AtomicReference<>();
    private UUID ownerId;
    private UUID driverId;
    private UUID connectorId;
    @Autowired private com.thang.chargeops.notification.email.NotificationEmailDeliveryStore emailDeliveries;
    @Autowired private com.thang.chargeops.notification.outbox.NotificationOutboxRepository notificationOutbox;
    private final com.thang.chargeops.notification.email.BookingEmailRenderer emailRenderer = new com.thang.chargeops.notification.email.BookingEmailRenderer();
    @Autowired private com.thang.chargeops.notification.outbox.BookingReminderService reminders;
    @Autowired private com.thang.chargeops.booking.service.BookingNoShowService noShows;
    @Autowired private com.thang.chargeops.booking.service.BookingAutomaticCompletionService completions;
    @Autowired private com.thang.chargeops.notification.outbox.NotificationOutboxStore outboxStore;

    @ParameterizedTest @ValueSource(strings = {"CANCEL_OWNER", "CANCEL_ADMIN", "NO_SHOW", "COMPLETED", "REMINDER", "DECISION", "PENDING", "FAILED", "SUCCEEDED_DRIVER", "SUCCEEDED_OWNER"})
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="chargeops.smtp.integration",matches="true")
    void extendedEventsTravelThroughRealKafkaAndMailpitOnce(String scenario) throws Exception {
        var paid = createAndPayBooking(START_AT);
        String type;
        String audience = "DRIVER";
        if (scenario.equals("CANCEL_ADMIN")) {
            var policyCase = createPolicyCase(paid.bookingId());
            adminReview(policyCase, UUID.randomUUID(), policyPayload(1,0,true),200);
            type="BOOKING_CANCELLED";
        } else if (scenario.equals("NO_SHOW")) {
            var at=START_AT.plusSeconds(2700);
            when(applicationClock.instant()).thenReturn(at);
            assertThat(noShows.markNoShowIfDue(paid.bookingId(),connectorId,at)).isTrue();
            type="BOOKING_NO_SHOW";
        } else if (scenario.equals("COMPLETED")) {
            jdbc.update("UPDATE bookings SET status='CHECKED_IN', checked_in_at=? WHERE id=?",Timestamp.from(START_AT),paid.bookingId());
            when(applicationClock.instant()).thenReturn(START_AT.plusSeconds(60));
            currentActorId.set(driverId);
            mockMvc.perform(post("/api/v1/bookings/{id}/complete",paid.bookingId()).with(user("driver").roles("DRIVER"))
                    .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json(Map.of("expectedVersion",1))))
                    .andExpect(status().isOk());
            type="BOOKING_COMPLETED";
        } else if (scenario.equals("REMINDER")) {
            var at=START_AT.minusSeconds(1800);
            when(applicationClock.instant()).thenReturn(at);
            assertThat(reminders.recordIfDue(paid.bookingId(),at)).isTrue();
            type="BOOKING_REMINDER";
        } else {
            currentActorId.set(ownerId);
            var result=ownerCancel(paid.bookingId(),1,UUID.randomUUID(),REASON,200);
            var refundId=UUID.fromString(result.at("/data/refund/refundId").asText());
            if (scenario.equals("FAILED")) {
                doReturn(new RefundExecutionResult(RefundExecutionOutcome.FAILED,"SIMULATOR",null,"SIMULATED_FAILURE",NOW,"Internal private evidence"))
                        .when(simulatorRefundExecutor).execute(any());
                assertThat(automaticRefundExecutionService.processFirstAttempt(refundId)).isTrue();
                type="REFUND_ATTEMPT_FAILED"; audience="OWNER";
            } else if (scenario.startsWith("SUCCEEDED")) {
                assertThat(automaticRefundExecutionService.processFirstAttempt(refundId)).isTrue();
                type="REFUND_SUCCEEDED"; audience=scenario.endsWith("OWNER") ? "OWNER" : "DRIVER";
            } else type=scenario.equals("DECISION") ? "RESPONSIBILITY_DECIDED" : scenario.equals("PENDING") ? "REFUND_PENDING" : "BOOKING_CANCELLED";
        }
        var event=eventFor(paid.bookingId(),type,audience);
        String email="bkg066-"+UUID.randomUUID()+"@example.test";
        jdbc.update("UPDATE user_profile SET email=? WHERE id=?",email,event.recipientId());
        jdbc.update("UPDATE notification_outbox SET next_attempt_at=CURRENT_TIMESTAMP+interval '1 day' WHERE status='PENDING' AND event_id<>?",event.eventId());
        jdbc.update("UPDATE notification_email_deliveries SET next_attempt_at=CURRENT_TIMESTAMP+interval '1 day' WHERE status='PENDING'");
        String topic="chargeops.lab.extended."+UUID.randomUUID();
        String group=topic+".reader";
        String broker=System.getProperty("chargeops.kafka.bootstrap-servers","localhost:9092");
        var producer=new org.springframework.kafka.core.DefaultKafkaProducerFactory<String,String>(Map.of("bootstrap.servers",broker,"acks","all","enable.idempotence",true),
                new org.apache.kafka.common.serialization.StringSerializer(),new org.apache.kafka.common.serialization.StringSerializer());
        var kafka=new org.springframework.kafka.core.KafkaTemplate<>(producer);
        var consumers=new org.springframework.kafka.core.DefaultKafkaConsumerFactory<String,String>(Map.of("bootstrap.servers",broker,"group.id",group,
                "enable.auto.commit",false,"auto.offset.reset","earliest"),new org.apache.kafka.common.serialization.StringDeserializer(),new org.apache.kafka.common.serialization.StringDeserializer());
        var properties=new org.springframework.kafka.listener.ContainerProperties(topic);
        properties.setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        var listener=new com.thang.chargeops.notification.kafka.BookingNotificationEmailConsumer(emailDeliveries,objectMapper);
        properties.setMessageListener((org.springframework.kafka.listener.MessageListener<String,String>)listener::receiveConfirmed);
        var container=new org.springframework.kafka.listener.ConcurrentMessageListenerContainer<>(consumers,properties);
        var publisher=new com.thang.chargeops.notification.kafka.BookingNotificationPublisher(kafka,objectMapper,topic);
        var worker=new com.thang.chargeops.notification.outbox.BookingNotificationOutboxWorker(outboxStore,publisher,objectMapper,1,10);
        var sender=new org.springframework.mail.javamail.JavaMailSenderImpl(); sender.setHost("localhost");sender.setPort(1025);
        sender.getJavaMailProperties().putAll(Map.of("mail.smtp.connectiontimeout","3000","mail.smtp.timeout","10000","mail.smtp.writetimeout","10000"));
        var smtp=new com.thang.chargeops.notification.email.BookingNotificationEmailWorker(emailDeliveries,emailRenderer,sender,"noreply@chargeops.test","http://localhost:5173","support",1,5);
        try(var admin=org.apache.kafka.clients.admin.AdminClient.create(Map.of("bootstrap.servers",broker))) {
            admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(topic,3,(short)1))).all().get(10,TimeUnit.SECONDS);
            try {
                container.start();
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertThat(container.getAssignedPartitions()).hasSize(3));
                worker.dispatchPending();
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(jdbc.queryForObject("SELECT status FROM notification_email_deliveries WHERE event_id=?",String.class,event.eventId())).isEqualTo("PENDING"));
                smtp.dispatchPending();
                kafka.send(topic,event.bookingId().toString(),objectMapper.writeValueAsString(event)).get(10,TimeUnit.SECONDS);
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS).values().stream().mapToLong(org.apache.kafka.clients.consumer.OffsetAndMetadata::offset).sum()).isEqualTo(2));
                smtp.dispatchPending();
                assertThat(jdbc.queryForObject("SELECT status FROM notification_email_deliveries WHERE event_id=?",String.class,event.eventId())).isEqualTo("SENT");
                assertThat(notificationOutbox.findById(event.eventId()).orElseThrow().getStatus()).isEqualTo("PUBLISHED");
                var client=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
                String url="http://localhost:8025/api/v1/search?query="+java.net.URLEncoder.encode("to:"+email,java.nio.charset.StandardCharsets.UTF_8);
                var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                var mails=objectMapper.readTree(response.body()).get("messages");
                assertThat(mails.size()).isEqualTo(1);
                var content=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:8025/api/v1/message/"+mails.get(0).get("ID").asString())).timeout(Duration.ofSeconds(5)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
                var body=objectMapper.readTree(content.body());
                String bookingCode=jdbc.queryForObject("SELECT booking_code FROM bookings WHERE id=?",String.class,paid.bookingId());
                assertThat(body.get("HTML").asString()).contains(bookingCode).doesNotContain(REASON,"Internal private evidence");
                assertThat(body.get("Text").asString()).contains("Booking").doesNotContain("Internal private evidence");
            } finally { container.stop();admin.deleteTopics(List.of(topic)).all().get(10,TimeUnit.SECONDS); }
        } finally { worker.shutdown();smtp.shutdown();kafka.destroy();producer.destroy(); }
    }

    @ParameterizedTest @ValueSource(longs = {-1801, -1800, -1, 0, 1})
    void reminderDueBoundariesAndReplay(long offset) throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        Instant at = START_AT.plusSeconds(offset);
        when(applicationClock.instant()).thenReturn(at);
        boolean due = offset >= -1800 && offset < 0;
        assertThat(reminders.recordIfDue(paid.bookingId(), at)).isEqualTo(due);
        assertThat(reminders.recordIfDue(paid.bookingId(), at)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE booking_id=? AND event_type='BOOKING_REMINDER'", Long.class, paid.bookingId())).isEqualTo(due ? 1 : 0);
        if (due) {
            var event = eventFor(paid.bookingId(), "BOOKING_REMINDER", "DRIVER");
            var model = prepareEvent(event);
            assertThat(model.event().message()).contains("check-in đúng hạn");
            assertThat(jdbc.queryForObject("SELECT expires_at FROM app_notifications WHERE booking_id=? AND event_type='BOOKING_REMINDER'", Timestamp.class, paid.bookingId()).toInstant()).isEqualTo(START_AT);
            jdbc.update("UPDATE bookings SET status='CHECKED_IN', checked_in_at=? WHERE id=?", Timestamp.from(at), paid.bookingId());
            assertThat(emailDeliveries.beginSend(activeEmailClaim(event.eventId()), model, "http://localhost:5173", "support", 5)).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM notification_email_deliveries WHERE event_id=?", String.class, event.eventId())).isEqualTo("SKIPPED");
        }
    }

    @Test void reminderConcurrentWorkersAndLateConfirmationCreateOneIntent() throws Exception {
        // Exercise catch-up with a supported policy allowing a booking at the reminder boundary.
        doReturn(30).when(reminderBookingPolicy).getMinimumAdvanceMinutes();
        var start = NOW.plusSeconds(1800);
        var paid = createPendingBookingWithCheckout(start);
        var at = NOW.plusSeconds(60);
        when(applicationClock.instant()).thenReturn(at);
        var receipt = paymentConfirmationService.processReceipt(new NormalizedReceipt("SIMULATOR", "SIMULATOR",
                "REMINDER-LATE-" + UUID.randomUUID(), java.math.BigDecimal.valueOf(paid.amount()), "VND",
                at, at, paid.vaNumber(), paid.paymentCode(), "Late confirmation reminder", "{}"));
        assertThat(receipt.status()).isEqualTo(com.thang.chargeops.payment.model.PaymentReceiptResult.Status.APPLIED);
        assertThat(reminders.candidates(at, 50)).contains(paid.bookingId());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> reminders.recordIfDue(paid.bookingId(), at));
            var b = pool.submit(() -> reminders.recordIfDue(paid.bookingId(), at));
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(reminders.candidates(at, 50)).doesNotContain(paid.bookingId());
        var event = eventFor(paid.bookingId(), "BOOKING_REMINDER", "DRIVER");
        var model = prepareEvent(event);
        when(applicationClock.instant()).thenReturn(start);
        assertThat(emailDeliveries.beginSend(activeEmailClaim(event.eventId()), model, "http://localhost:5173", "support", 5)).isFalse();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void lifecycleTransitionsCreateDurableEmailFromHistory(boolean noShow) throws Exception {
        var paid = createAndPayBooking(START_AT);
        if (noShow) {
            jdbc.update("UPDATE bookings SET check_in_deadline=? WHERE id=?", Timestamp.from(NOW), paid.bookingId());
            assertThat(noShows.markNoShowIfDue(paid.bookingId(), connectorId, NOW)).isTrue();
            assertThat(noShows.markNoShowIfDue(paid.bookingId(), connectorId, NOW)).isFalse();
        } else {
            jdbc.update("UPDATE bookings SET status='CHECKED_IN', checked_in_at=? WHERE id=?", Timestamp.from(START_AT), paid.bookingId());
            Instant completedAt = START_AT.plusSeconds(3600);
            assertThat(completions.completeIfDue(paid.bookingId(), connectorId, completedAt)).isTrue();
            assertThat(completions.completeIfDue(paid.bookingId(), connectorId, completedAt)).isFalse();
        }
        var event = eventFor(paid.bookingId(), noShow ? "BOOKING_NO_SHOW" : "BOOKING_COMPLETED", "DRIVER");
        var model = prepareEvent(event);
        assertThat(model.event().message()).contains(noShow ? "không xác lập khoản hoàn" : "không xác nhận dung lượng pin");
        var rendered = emailRenderer.renderBookingEvent(model.event());
        assertThat(rendered.html()).contains(model.event().heading());
        assertThat(rendered.text()).contains(model.event().bookingCode());
        var claim = activeEmailClaim(event.eventId());
        assertThat(emailDeliveries.beginSend(claim, model, "http://localhost:5173", "support", 5)).isTrue();
        assertThat(emailDeliveries.markSent(claim)).isTrue();
        assertThat(emailDeliveries.captureEvent(event)).isFalse();
    }

    @Test void refundEventsKeepRecipientMatrixAndSkipSupersededPendingFailure() throws Exception {
        var paid = createAndPayBooking(START_AT);
        currentActorId.set(ownerId);
        var result = ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), REASON, 200);
        UUID refundId = UUID.fromString(result.at("/data/refund/refundId").asText());
        var decision = eventFor(paid.bookingId(), "RESPONSIBILITY_DECIDED", "DRIVER");
        assertThat(prepareEvent(decision).event().message()).contains("chấp thuận hoàn").doesNotContain(REASON);
        var pending = eventFor(paid.bookingId(), "REFUND_PENDING", "DRIVER");
        var pendingModel = prepareEvent(pending);
        doReturn(new RefundExecutionResult(RefundExecutionOutcome.FAILED, "SIMULATOR", null, "SIMULATED_FAILURE", NOW, "Internal sensitive note"))
                .doCallRealMethod().when(simulatorRefundExecutor).execute(any());
        assertThat(automaticRefundExecutionService.processFirstAttempt(refundId)).isTrue();
        var failed = eventFor(paid.bookingId(), "REFUND_ATTEMPT_FAILED", "OWNER");
        var failedModel = prepareEvent(failed);
        assertThat(failed.recipientId()).isEqualTo(ownerId);
        assertThat(failedModel.event().actionUrl()).isEqualTo("http://localhost:5173/owner/revenue?tab=refunds");
        assertThat(failedModel.event().message()).doesNotContain("Internal sensitive note", "SIMULATED_FAILURE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE booking_id=? AND event_type='REFUND_ATTEMPT_FAILED' AND audience<>'OWNER'", Long.class, paid.bookingId())).isZero();
        long version = jdbc.queryForObject("SELECT version FROM refunds WHERE id=?", Long.class, refundId);
        UUID retryKey = UUID.randomUUID();
        for (int i=0;i<2;i++) mockMvc.perform(post("/api/v1/owner/refunds/{id}/retry", refundId).with(user("owner").roles("OWNER"))
                .header("Idempotency-Key", retryKey).contentType("application/json").content(json(Map.of("expectedVersion",version))))
                .andExpect(status().isOk());
        assertThat(emailDeliveries.beginSend(activeEmailClaim(pending.eventId()), pendingModel, "http://localhost:5173", "support", 5)).isFalse();
        assertThat(emailDeliveries.beginSend(activeEmailClaim(failed.eventId()), failedModel, "http://localhost:5173", "support", 5)).isFalse();
        for (String audience : List.of("DRIVER","OWNER")) {
            var success = eventFor(paid.bookingId(), "REFUND_SUCCEEDED", audience);
            var model = prepareEvent(success);
            assertThat(model.event().message()).contains("đã hoàn tất trong Simulator", "không phải chuyển tiền ngân hàng");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE booking_id=? AND event_type='REFUND_SUCCEEDED'", Long.class, paid.bookingId())).isEqualTo(2);
    }

    @Test void cancellationRollbackRemovesAllNewIntentsAndNotices() throws Exception {
        var paid = createAndPayBooking(START_AT);
        currentActorId.set(ownerId);
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(tx -> {
            try { ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), REASON, 200); }
            catch (Exception e) { throw new RuntimeException(e); }
            throw new IllegalStateException("rollback after all intents");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, paid.bookingId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE booking_id=? AND event_type<>'BOOKING_CONFIRMED'", Long.class, paid.bookingId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_notifications WHERE booking_id=? AND event_type<>'BOOKING_CONFIRMED'", Long.class, paid.bookingId())).isZero();
    }

    private com.thang.chargeops.notification.kafka.BookingNotificationEvent eventFor(UUID bookingId, String type, String audience) {
        var eventId = jdbc.queryForObject("SELECT event_id FROM notification_outbox WHERE booking_id=? AND event_type=? AND audience=?", UUID.class, bookingId,type,audience);
        return objectMapper.readValue(notificationOutbox.findById(eventId).orElseThrow().getPayload(), com.thang.chargeops.notification.kafka.BookingNotificationEvent.class);
    }

    private com.thang.chargeops.notification.email.NotificationEmailDeliveryStore.PreparedEmail prepareEvent(com.thang.chargeops.notification.kafka.BookingNotificationEvent event) {
        assertThat(emailDeliveries.captureEvent(event)).isTrue();
        jdbc.update("UPDATE notification_email_deliveries SET next_attempt_at=CURRENT_TIMESTAMP+interval '1 day' WHERE status='PENDING' AND event_id<>?", event.eventId());
        var claim = emailDeliveries.claimDelivery(180,5).orElseThrow();
        return emailDeliveries.prepare(claim,"http://localhost:5173","support").orElseThrow();
    }

    private com.thang.chargeops.notification.email.NotificationEmailDeliveryStore.Claim activeEmailClaim(UUID eventId) {
        return jdbc.queryForObject("SELECT delivery_id,claim_token,attempt_count FROM notification_email_deliveries WHERE event_id=?", (rs,n) ->
                new com.thang.chargeops.notification.email.NotificationEmailDeliveryStore.Claim(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getInt(3)),eventId);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void stationCancellationEmailUsesAuthoritativeSourceAndSafeCopy(boolean admin) throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        UUID key = UUID.randomUUID();
        if (admin) {
            PolicyCase policyCase = createPolicyCase(paid.bookingId());
            adminReview(policyCase, key, policyPayload(1, 0, true), 200);
            adminReview(policyCase, key, policyPayload(1, 0, true), 200);
        } else {
            currentActorId.set(ownerId);
            ownerCancel(paid.bookingId(), 1, key, REASON, 200);
            ownerCancel(paid.bookingId(), 1, key, REASON, 200);
        }
        UUID eventId = jdbc.queryForObject("SELECT event_id FROM notification_outbox WHERE booking_id=? AND event_type='BOOKING_CANCELLED'", UUID.class, paid.bookingId());
        var event = objectMapper.readValue(notificationOutbox.findById(eventId).orElseThrow().getPayload(),
                com.thang.chargeops.notification.kafka.BookingNotificationEvent.class);
        assertThat(event.recipientId()).isEqualTo(driverId);
        assertThat(emailDeliveries.captureCancelled(event)).isTrue();
        assertThat(emailDeliveries.captureCancelled(event)).isFalse();
        // Isolate this delivery from intents created by the other tests in this shared database.
        jdbc.update("UPDATE notification_email_deliveries SET next_attempt_at=CURRENT_TIMESTAMP + interval '1 day' WHERE event_id<>?", eventId);
        var claim = emailDeliveries.claimDelivery(180, 5).orElseThrow();
        var prepared = emailDeliveries.prepare(claim, "http://localhost:5173", "support").orElseThrow();
        var rendered = emailRenderer.renderBookingCancelled(prepared.cancelled());
        assertThat(rendered.text()).contains("trạm không thể phục vụ", "đang chờ xử lý").doesNotContain("Bạn đã hủy", REASON);
        assertThat(rendered.html()).contains("trạm không thể phục vụ");
        assertThat(emailDeliveries.beginSend(claim, prepared, "http://localhost:5173", "support", 5)).isTrue();
        assertThat(emailDeliveries.markSent(claim)).isTrue();
        assertThat(emailDeliveries.captureCancelled(event)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_email_deliveries WHERE event_id=?", String.class, eventId)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_notifications WHERE booking_id=? AND event_type='BOOKING_CANCELLED'", Long.class, paid.bookingId())).isEqualTo(1);
    }

    @BeforeEach void setUp() {
        when(applicationClock.instant()).thenReturn(NOW);
        when(applicationClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(applicationClock.withZone(any())).thenReturn(applicationClock);
        doAnswer(i -> ((Supplier<?>) i.getArgument(3)).get()).when(bookingCommandInFlightLock).executeWithLock(any(), any(), any(), any());
        when(currentProfileProvider.requireProfile()).thenAnswer(i -> userProfileRepository.findById(currentActorId.get()).orElseThrow());
        when(currentProfileProvider.requireProfileId()).thenAnswer(i -> currentActorId.get());
        Fixture fixture = insertBookableStationFixture();
        driverId = fixture.driverId(); connectorId = fixture.connectorId();
        ownerId = jdbc.queryForObject("SELECT s.owner_id FROM stations s JOIN charge_points cp ON cp.station_id=s.id JOIN connectors c ON c.charge_point_id=cp.id WHERE c.id=?", UUID.class, connectorId);
        currentActorId.set(driverId);
    }

    @Test void adminGrantCancelsAndReplaysAfterCaseClosure() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        UUID key = UUID.randomUUID();
        JsonNode granted = adminReview(policyCase, key, policyPayload(1, 0, true), 200);
        assertThat(granted.at("/data/decision/actorKind").asText()).isEqualTo("ADMIN");
        assertThat(granted.at("/data/refund/amount").asLong()).isEqualTo(paid.amount());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_status_history WHERE booking_id=? AND actor_type='ADMIN' AND reason='ADMIN_POLICY_CANCELLED'", Long.class, paid.bookingId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM support_tickets WHERE id=?", String.class, policyCase.ticketId())).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT resolved_at FROM ticket_escalations WHERE id=?", Timestamp.class, policyCase.escalationId())).isNull();
        jdbc.update("UPDATE support_tickets SET status='CLOSED', version=version+1 WHERE id=?", policyCase.ticketId());
        assertThat(adminReview(policyCase, key, policyPayload(1, 0, true), 200).at("/data/decision/decisionId"))
                .isEqualTo(granted.at("/data/decision/decisionId"));
        adminReview(policyCase, UUID.randomUUID(), policyPayload(2, 1, true), 409);
        mockMvc.perform(get(policyUrl(policyCase) + "/refund-policy-context").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.grant.allowed").value(false))
                .andExpect(jsonPath("$.data.insufficient.allowed").value(false));
        assertCount("refunds", paid.bookingId(), 1);
    }

    @Test void bkg063LegacyFinanceRoutesHaveNoRuntimeMapping() throws Exception {
        String id = UUID.randomUUID().toString();
        for (String route : List.of("refunds", "refunds/summary", "refunds/" + id,
                "refunds/" + id + "/execute", "payouts", "payouts/" + id,
                "payouts/" + id + "/execute", "finance/bookings", "finance/summary",
                "receipts", "receipts/" + id, "payments", "payments/summary")) {
            for (var request : List.of(get("/api/v1/admin/" + route), post("/api/v1/admin/" + route))) {
                mockMvc.perform(request.with(user("admin").roles("ADMIN")))
                        .andExpect(status().isNotFound());
            }
        }
        for (String route : List.of("/api/v1/owner/finance/bookings", "/api/v1/owner/finance/summary",
                "/api/v1/owner/refunds", "/api/v1/owner/refunds/summary")) {
            mockMvc.perform(get(route).param("ownerId", UUID.randomUUID().toString())
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isForbidden());
        }
    }

    @Test void bkg063ReadAuditIsAppendOnlyAndWrongCaseOrRoleCannotRead() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase first = createPolicyCase(paid.bookingId());
        PolicyCase second = createPolicyCase(paid.bookingId());
        currentActorId.set(first.adminId());
        mockMvc.perform(get(policyUrl(first) + "/refund-policy-context").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ticket_events
                WHERE actor_id=? AND ticket_id=? AND reason=?
                  AND event_type='REFUND_POLICY_CONTEXT'
                """, Long.class, first.adminId(), first.ticketId(), "bookingId=" + paid.bookingId() + ";escalationId=" + first.escalationId())).isEqualTo(1L);
        String wrong = policyUrl(new PolicyCase(first.ticketId(), second.escalationId(), first.adminId()));
        mockMvc.perform(get(wrong + "/refund-policy-context").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
        jdbc.update("DELETE FROM ticket_escalations WHERE id=?", second.escalationId());
        mockMvc.perform(get(policyUrl(second) + "/refund-policy-context").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
        for (String role : List.of("STAFF", "OWNER", "DRIVER")) {
            mockMvc.perform(get(policyUrl(first) + "/refund-policy-context").with(user(role).roles(role)))
                    .andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_events WHERE ticket_id=?",
                Long.class, first.ticketId())).isEqualTo(1L);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ticket_events WHERE ticket_id=?", first.ticketId()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE ticket_events SET event_type='TICKET_REFUND_REFERENCES' WHERE ticket_id=?", first.ticketId()))
                .hasMessageContaining("append-only");
    }

    @Test void bkg063ReturnedCaseKeepsReadHistoryButCannotGrantNewRefund() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        jdbc.update("UPDATE ticket_escalations SET resolved_at=?, resolved_by=?, resolution_type='RETURN_TO_STATION', resolution_note='Return for investigation' WHERE id=?",
                Timestamp.from(NOW), policyCase.adminId(), policyCase.escalationId());
        mockMvc.perform(get(policyUrl(policyCase) + "/refund-policy-context").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.grant.allowed").value(false));
        adminReview(policyCase, UUID.randomUUID(), policyPayload(1, 0, true), 409);
        assertCount("refunds", paid.bookingId(), 0);
        assertCount("booking_service_failure_decisions", paid.bookingId(), 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_events WHERE ticket_id=?",
                Long.class, policyCase.ticketId())).isEqualTo(1L);
    }

    @Test void bkg063AnyActiveAdminCanReadCaseAndBothTicketRoutesHaveAudit() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        UUID anotherAdmin = UUID.randomUUID();
        insertUser(anotherAdmin, "another-admin-" + anotherAdmin, "Another Admin");
        currentActorId.set(anotherAdmin);
        mockMvc.perform(get(policyUrl(policyCase) + "/refund-policy-context").with(user("another").roles("ADMIN")))
                .andExpect(status().isOk());
        for (String route : List.of("/api/v1/admin/tickets/", "/api/v1/tickets/")) {
            mockMvc.perform(get(route + policyCase.ticketId()).with(user("another").roles("ADMIN")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.resolution.financialResolution").exists());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_events WHERE ticket_id=? AND actor_id=?",
                Long.class, policyCase.ticketId(), anotherAdmin)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT version FROM support_tickets WHERE id=?", Long.class,
                policyCase.ticketId())).isZero();
        jdbc.update("UPDATE user_profile SET status='SUSPENDED' WHERE id=?", anotherAdmin);
        mockMvc.perform(get(policyUrl(policyCase) + "/refund-policy-context").with(user("another").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test void bkg063StaffDetailDoesNotExposeFinanceAndExecutionPayloadIsRejected() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        for (String field : List.of("amount", "executionOutcome", "bookingId")) {
            Map<String, Object> payload = new LinkedHashMap<>(policyPayload(1, 0, true));
            payload.put(field, field.equals("amount") ? paid.amount() : "SUCCEEDED");
            adminReview(policyCase, UUID.randomUUID(), payload, 400);
        }
        adminReview(policyCase, UUID.randomUUID(), policyPayload(1, 0, true), 200);
        UUID staffId = UUID.randomUUID();
        insertUser(staffId, "finance-staff-" + staffId, "Staff");
        jdbc.update("INSERT INTO station_staff_assignments(id,station_id,user_id,assigned_by,status,assigned_at,version) VALUES (?,?,?,?, 'ACTIVE',?,0)",
                UUID.randomUUID(), incidentStationId(), staffId, ownerId, Timestamp.from(NOW));
        currentActorId.set(staffId);
        mockMvc.perform(get("/api/v1/tickets/" + policyCase.ticketId()).with(user("staff").roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolution.financialResolution").doesNotExist())
                .andExpect(jsonPath("$.data.resolution.refundIds").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_events WHERE ticket_id=? AND actor_id=?",
                Long.class, policyCase.ticketId(), staffId)).isZero();
    }

    @Test void bkg063FailedAuditPreventsFinancialReadResponse() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        com.thang.chargeops.support.service.AdminFinanceAccessAudit auditTarget =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(accessAudit);
        doThrow(new IllegalStateException("Audit unavailable")).when(auditTarget)
                .appendAccessEvent(any(), any(), any(), any());
        mockMvc.perform(get(policyUrl(policyCase) + "/refund-policy-context").with(user("admin").roles("ADMIN")))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.data").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_events WHERE ticket_id=?",
                Long.class, policyCase.ticketId())).isZero();
    }

    @Test void incidentStaffReportSnapshotsAndBlocksNewBookingsWithoutGrantingRefund() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        UUID stationId = incidentStationId();
        UUID staffId = UUID.randomUUID();
        insertUser(staffId, "incident-staff-" + staffId, "Incident Staff");
        jdbc.update("INSERT INTO station_staff_assignments(id,station_id,user_id,assigned_by,status,assigned_at,version) VALUES (?,?,?,?, 'ACTIVE',?,0)",
                UUID.randomUUID(), stationId, staffId, ownerId, Timestamp.from(NOW));
        currentActorId.set(staffId);
        UUID key = UUID.randomUUID();
        JsonNode reported = incidentReport(key, 0, "staff", 200);
        UUID incidentId = UUID.fromString(reported.at("/data/incidentId").asText());
        assertThat(reported.at("/data/runtimeStatus").asText()).isEqualTo("OFFLINE");
        assertThat(reported.at("/data/affectedBookingIds/0").asText()).isEqualTo(paid.bookingId().toString());
        assertThat(reported.toString()).doesNotContain("paymentId", "receiptId", "amount", "payloadHash", "keycloakId");
        assertThat(incidentReport(key, 0, "staff", 200).at("/data/incidentId")).isEqualTo(reported.at("/data/incidentId"));
        incidentReport(key, 1, "staff", 409);
        assertCount("refunds", paid.bookingId(), 0);
        assertThat(jdbc.queryForObject("SELECT actor_type FROM connector_incidents WHERE id=?", String.class, incidentId)).isEqualTo("STAFF");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_commands WHERE incident_id=?", Long.class, incidentId)).isEqualTo(1L);
        mockMvc.perform(post("/api/v1/bookings/price-preview").with(user("driver").roles("DRIVER"))
                .contentType("application/json").content(json(Map.of("connectorId",connectorId,"startAt",START_AT.plusSeconds(7200),"durationMin",60))))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE station_staff_assignments SET status='REVOKED',revoked_by=?,revoked_at=? WHERE user_id=?",ownerId,Timestamp.from(NOW),staffId);
        mockMvc.perform(get("/api/v1/stations/{s}/incidents/{i}",stationId,incidentId).with(user("staff").roles("DRIVER")))
                .andExpect(status().isForbidden());
    }

    @Test void incidentSafeStopThenOwnerRefundRecoveryPreservesHistoryAndPendingObligation() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        activateIncidentSession(paid.bookingId(), "CHARGING");
        currentActorId.set(ownerId);
        JsonNode reported = incidentReport(UUID.randomUUID(), 0, "owner", 200);
        UUID incidentId = UUID.fromString(reported.at("/data/incidentId").asText());
        assertThat(reported.at("/data/handlingState").asText()).isEqualTo("PENDING");
        UUID key = UUID.randomUUID();
        JsonNode resolved = incidentResolve(incidentId,paid.bookingId(),1,key,true,200);
        assertThat(resolved.at("/data/handlingState").asText()).isEqualTo("COMPLETED");
        assertThat(resolved.at("/data/runtimeStatus").asText()).isEqualTo("OFFLINE");
        assertThat(resolved.at("/data/affectedBookings/0/currentStatus").asText()).isEqualTo("COMPLETED");
        incidentResolve(incidentId,paid.bookingId(),1,key,true,200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_status_history WHERE booking_id=? AND actor_type='OWNER' AND reason='INCIDENT_SESSION_STOPPED'",Long.class,paid.bookingId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT end_at FROM bookings WHERE id=?",Timestamp.class,paid.bookingId()).toInstant()).isEqualTo(START_AT.plusSeconds(3600));
        assertCount("refunds",paid.bookingId(),0);
        mockMvc.perform(post("/api/v1/owner/bookings/{id}/station-failure-refund",paid.bookingId()).with(user("owner").roles("OWNER"))
                .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content(json(Map.of("expectedVersion",2,"expectedDecisionVersion",0,"affectedAt",START_AT.plusSeconds(60),"reason",REASON + " Incident " + incidentId))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.refund.status").value("PENDING"));
        JsonNode recovered = incidentRecover(incidentId,0,UUID.randomUUID(),200);
        assertThat(recovered.at("/data/runtimeStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT status FROM refunds WHERE booking_id=?",String.class,paid.bookingId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,paid.bookingId())).isEqualTo("COMPLETED");
        assertCount("refunds",paid.bookingId(),1);
        JsonNode evidence = objectMapper.readTree(jdbc.queryForObject("SELECT evidence_snapshot::text FROM booking_service_failure_decisions WHERE booking_id=?",String.class,paid.bookingId()));
        assertThat(evidence.at("/incidents/0/incidentId").asText()).isEqualTo(incidentId.toString());
        assertThat(evidence.at("/incidents/0/safetyState").asText()).isEqualTo("SESSION_RESOLVED");
    }

    @Test void incidentTwoOpenIncidentsRecoveryAndOrdinaryRuntimeGuard() throws Exception {
        currentActorId.set(ownerId);
        UUID first = UUID.fromString(incidentReport(UUID.randomUUID(),0,"owner",200).at("/data/incidentId").asText());
        incidentReport(UUID.randomUUID(),1,"owner",409);
        UUID second = UUID.randomUUID();
        jdbc.update("INSERT INTO connector_incidents(id,connector_id,status,reason,occurred_at,reported_at,reported_by,actor_type,previous_runtime_status) VALUES (?,?,'OPEN',?,?,?,?,'ADMIN','OFFLINE')",
                second,connectorId,REASON,Timestamp.from(NOW),Timestamp.from(NOW),ownerId);
        UUID key = UUID.randomUUID();
        assertThat(incidentRecover(first,0,key,200).at("/data/runtimeStatus").asText()).isEqualTo("OFFLINE");
        UUID cpId = jdbc.queryForObject("SELECT charge_point_id FROM connectors WHERE id=?",UUID.class,connectorId);
        mockMvc.perform(patch("/api/v1/owner/stations/{s}/charge-points/{cp}/connectors/{c}/runtime-status",incidentStationId(),cpId,connectorId)
                .with(user("owner").roles("OWNER")).contentType("application/json").content(json(Map.of("runtimeStatus","AVAILABLE","reason",REASON))))
                .andExpect(status().isConflict());
        assertThat(incidentRecover(first,0,key,200).at("/data/status").asText()).isEqualTo("RECOVERED");
        incidentRecover(first,0,UUID.randomUUID(),409);
        assertThat(incidentRecover(second,0,UUID.randomUUID(),200).at("/data/runtimeStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM connector_incidents WHERE connector_id=?",Long.class,connectorId)).isEqualTo(2L);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM connector_incidents WHERE id=?",first)).hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> jdbc.update("UPDATE connector_incidents SET reason='rewritten evidence' WHERE id=?",first)).hasMessageContaining("immutable");
    }

    @Test void incidentRecoveryRetainsInUseForActiveSessionAndOfflineForMaintenance() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        activateIncidentSession(paid.bookingId(),"CHECKED_IN");
        currentActorId.set(ownerId);
        UUID first = UUID.fromString(incidentReport(UUID.randomUUID(),0,"owner",200).at("/data/incidentId").asText());
        assertThat(incidentRecover(first,0,UUID.randomUUID(),200).at("/data/runtimeStatus").asText()).isEqualTo("IN_USE");
        incidentResolve(first,paid.bookingId(),1,UUID.randomUUID(),true,200);
        UUID second = UUID.fromString(incidentReport(UUID.randomUUID(),3,"owner",200).at("/data/incidentId").asText());
        jdbc.update("UPDATE charge_points SET operational_status='MAINTENANCE' WHERE id=(SELECT charge_point_id FROM connectors WHERE id=?)",connectorId);
        assertThat(incidentRecover(second,0,UUID.randomUUID(),200).at("/data/runtimeStatus").asText()).isEqualTo("OFFLINE");
    }

    @Test void incidentRejectsStaleVersionWrongScopeFutureTimeUnknownMoneyAndUnsafeStop() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        activateIncidentSession(paid.bookingId(),"CHARGING");
        currentActorId.set(ownerId);
        incidentReport(UUID.randomUUID(),99,"owner",409);
        mockMvc.perform(post("/api/v1/stations/{s}/connectors/{c}/incidents",incidentStationId(),connectorId)
                .with(user("owner").roles("OWNER")).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content(json(Map.of("expectedConnectorVersion",0,"reason",REASON,"occurredAt",START_AT.plusSeconds(61)))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/stations/{s}/connectors/{c}/incidents",incidentStationId(),connectorId)
                .with(user("owner").roles("OWNER")).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content(json(Map.of("expectedConnectorVersion",0,"reason",REASON,"occurredAt",START_AT,"amount",100))))
                .andExpect(status().isBadRequest());
        UUID incidentId = UUID.fromString(incidentReport(UUID.randomUUID(),0,"owner",200).at("/data/incidentId").asText());
        incidentResolve(incidentId,paid.bookingId(),1,UUID.randomUUID(),false,400);
        incidentResolve(incidentId,paid.bookingId(),99,UUID.randomUUID(),true,409);
        mockMvc.perform(get("/api/v1/stations/{s}/incidents/{i}",UUID.randomUUID(),incidentId).with(user("owner").roles("OWNER")))
                .andExpect(status().isNotFound());
        currentActorId.set(driverId);
        mockMvc.perform(get("/api/v1/stations/{s}/incidents/{i}",incidentStationId(),incidentId).with(user("driver").roles("DRIVER")))
                .andExpect(status().isForbidden());
        assertCount("refunds",paid.bookingId(),0);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,paid.bookingId())).isEqualTo("CHARGING");
    }

    @Test void incidentSnapshotFailureRollsBackOfflineAndCommand() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        currentActorId.set(ownerId);
        UUID key = UUID.randomUUID();
        jdbc.execute("CREATE FUNCTION fail_incident_snapshot_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected snapshot crash'; END $$");
        jdbc.execute("CREATE TRIGGER fail_incident_snapshot_test BEFORE INSERT ON incident_bookings FOR EACH ROW EXECUTE FUNCTION fail_incident_snapshot_test()");
        try {
            incidentReport(key,0,"owner",500);
            assertThat(jdbc.queryForObject("SELECT runtime_status FROM connectors WHERE id=?",String.class,connectorId)).isEqualTo("AVAILABLE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM connector_incidents WHERE connector_id=?",Long.class,connectorId)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_incident_snapshot_test ON incident_bookings");
            jdbc.execute("DROP FUNCTION fail_incident_snapshot_test()");
        }
        incidentReport(key,0,"owner",200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_bookings WHERE booking_id=?",Long.class,paid.bookingId())).isEqualTo(1L);
    }

    private UUID incidentStationId() {
        return jdbc.queryForObject("SELECT cp.station_id FROM charge_points cp JOIN connectors c ON c.charge_point_id=cp.id WHERE c.id=?",UUID.class,connectorId);
    }

    @Test void incidentUnpaidOwnerCancellationAndRecoveryNeverResurrectBooking() throws Exception {
        PendingBooking pending = createPendingBookingWithCheckout(START_AT);
        currentActorId.set(ownerId);
        UUID incidentId = UUID.fromString(incidentReport(UUID.randomUUID(),0,"owner",200).at("/data/incidentId").asText());
        ownerCancel(pending.bookingId(),0,UUID.randomUUID(),REASON,200);
        incidentRecover(incidentId,0,UUID.randomUUID(),200);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,pending.bookingId())).isEqualTo("CANCELLED");
        assertCount("refunds",pending.bookingId(),0);
    }

    @Test void incidentSnapshotIncludesMultipleBookingsAndCannotBeRewritten() throws Exception {
        PaidBooking first = createAndPayBooking(START_AT);
        PaidBooking second = createAndPayBooking(START_AT.plusSeconds(7200));
        currentActorId.set(ownerId);
        JsonNode response = incidentReport(UUID.randomUUID(),0,"owner",200);
        assertThat(response.at("/data/affectedBookingIds").size()).isEqualTo(2);
        UUID incidentId = UUID.fromString(response.at("/data/incidentId").asText());
        ownerCancel(first.bookingId(),1,UUID.randomUUID(),REASON,200);
        incidentRecover(incidentId,0,UUID.randomUUID(),200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_bookings WHERE incident_id=?",Long.class,incidentId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,second.bookingId())).isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> jdbc.update("UPDATE incident_bookings SET snapshot_version=99 WHERE incident_id=?",incidentId))
                .hasMessageContaining("immutable");
    }

    @Test void incidentAdminOperationalAuthorityDoesNotCreateFinancialDecision() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        UUID adminId = UUID.randomUUID();
        insertUser(adminId,"incident-admin-" + adminId,"Incident Admin");
        currentActorId.set(adminId);
        JsonNode reported = body(mockMvc.perform(post("/api/v1/stations/{s}/connectors/{c}/incidents",incidentStationId(),connectorId)
                .with(user("admin").roles("ADMIN")).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content(json(Map.of("expectedConnectorVersion",0,"reason",REASON,"occurredAt",NOW))))
                .andExpect(status().isOk()).andReturn());
        UUID incidentId = UUID.fromString(reported.at("/data/incidentId").asText());
        assertThat(jdbc.queryForObject("SELECT actor_type FROM connector_incidents WHERE id=?",String.class,incidentId)).isEqualTo("ADMIN");
        assertCount("refunds",paid.bookingId(),0);
        assertCount("booking_service_failure_decisions",paid.bookingId(),0);
        jdbc.update("UPDATE user_profile SET status='SUSPENDED' WHERE id=?",adminId);
        mockMvc.perform(get("/api/v1/stations/{s}/incidents/{i}",incidentStationId(),incidentId).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test void incidentConcurrentReportSameKeyCreatesOneSnapshotAndOneCommand() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        currentActorId.set(ownerId);
        UUID key = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<JsonNode> call = () -> { ready.countDown(); start.await(10,TimeUnit.SECONDS); return incidentReport(key,0,"owner",200); };
            Future<JsonNode> a = pool.submit(call), b = pool.submit(call);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(a.get(30,TimeUnit.SECONDS).at("/data/incidentId")).isEqualTo(b.get(30,TimeUnit.SECONDS).at("/data/incidentId"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM connector_incidents WHERE connector_id=?",Long.class,connectorId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_bookings WHERE booking_id=?",Long.class,paid.bookingId())).isEqualTo(1L);
    }

    @Test void incidentRecoveryWaitingOnConnectorSeesNewIncidentAndKeepsOffline() throws Exception {
        currentActorId.set(ownerId);
        UUID first = UUID.fromString(incidentReport(UUID.randomUUID(),0,"owner",200).at("/data/incidentId").asText());
        UUID otherOwner = UUID.randomUUID();
        insertUser(otherOwner,"incident-race-" + otherOwner,"Incident race admin");
        AtomicReference<Future<Integer>> response = new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT version FROM connectors WHERE id=? FOR UPDATE",Long.class,connectorId);
                response.set(executor.submit(() -> mockMvc.perform(post("/api/v1/stations/{s}/incidents/{i}/recover",incidentStationId(),first)
                        .with(user("owner").roles("OWNER")).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                        .content(json(Map.of("expectedVersion",0,"reason",REASON)))).andReturn().getResponse().getStatus()));
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                        jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%connectors%'",Long.class) > 0);
                jdbc.update("INSERT INTO connector_incidents(id,connector_id,status,reason,occurred_at,reported_at,reported_by,actor_type,previous_runtime_status) VALUES (?,?,'OPEN',?,?,?,?,'ADMIN','OFFLINE')",
                        UUID.randomUUID(),connectorId,REASON,Timestamp.from(NOW),Timestamp.from(NOW),otherOwner);
            });
            assertThat(response.get().get(30,TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT runtime_status FROM connectors WHERE id=?",String.class,connectorId)).isEqualTo("OFFLINE");
    }
    private JsonNode incidentReport(UUID key,long version,String role,int expectedStatus) throws Exception {
        return body(mockMvc.perform(post("/api/v1/stations/{s}/connectors/{c}/incidents",incidentStationId(),connectorId)
                .with(user(role).roles(role.toUpperCase()))
                .header("Idempotency-Key",key).contentType("application/json")
                .content(json(Map.of("expectedConnectorVersion",version,"reason",REASON,"occurredAt",NOW))))
                .andExpect(status().is(expectedStatus)).andReturn());
    }
    private JsonNode incidentRecover(UUID incidentId,long version,UUID key,int expectedStatus) throws Exception {
        return body(mockMvc.perform(post("/api/v1/stations/{s}/incidents/{i}/recover",incidentStationId(),incidentId)
                .with(user("owner").roles("OWNER")).header("Idempotency-Key",key).contentType("application/json")
                .content(json(Map.of("expectedVersion",version,"reason",REASON))))
                .andExpect(status().is(expectedStatus)).andReturn());
    }
    private JsonNode incidentResolve(UUID incidentId,UUID bookingId,long version,UUID key,boolean confirmed,int expectedStatus) throws Exception {
        return body(mockMvc.perform(post("/api/v1/stations/{s}/incidents/{i}/bookings/{b}/resolve-session",incidentStationId(),incidentId,bookingId)
                .with(user("owner").roles("OWNER")).header("Idempotency-Key",key).contentType("application/json")
                .content(json(Map.of("expectedBookingVersion",version,"reason",REASON,"safetyConfirmed",confirmed))))
                .andExpect(status().is(expectedStatus)).andReturn());
    }
    private void activateIncidentSession(UUID bookingId,String status) {
        jdbc.update("UPDATE bookings SET status=?,checked_in_at=?,charging_started_at=? WHERE id=?",status,Timestamp.from(START_AT),
                status.equals("CHARGING") ? Timestamp.from(START_AT.plusSeconds(30)) : null,bookingId);
        jdbc.update("UPDATE connectors SET runtime_status='IN_USE' WHERE id=?",connectorId);
        when(applicationClock.instant()).thenReturn(START_AT.plusSeconds(60));
    }

    @Test void adminInsufficientDoesNotPreventOwnerAdmissionAndReplayShowsCurrentRefund() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        jdbc.update("UPDATE bookings SET status='COMPLETED' WHERE id=?", paid.bookingId());
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        UUID key = UUID.randomUUID();
        JsonNode insufficient = adminReview(policyCase, key, policyPayload(1, 0, false), 200);
        assertThat(insufficient.at("/data/decision/result").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertCount("refunds", paid.bookingId(), 0);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, paid.bookingId())).isEqualTo("COMPLETED");
        currentActorId.set(ownerId);
        mockMvc.perform(post("/api/v1/owner/bookings/{id}/station-failure-refund", paid.bookingId())
                .with(user("owner").roles("OWNER")).header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json").content(json(Map.of("expectedVersion", 1, "expectedDecisionVersion", 1,
                        "affectedAt", NOW.toString(), "reason", REASON)))).andExpect(status().isOk());
        currentActorId.set(policyCase.adminId());
        JsonNode replay = adminReview(policyCase, key, policyPayload(1, 0, false), 200);
        assertThat(replay.at("/data/decision/result").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(replay.at("/data/refund/amount").asLong()).isEqualTo(paid.amount());
        assertThat(adminReview(policyCase, UUID.randomUUID(), policyPayload(1, 2, false), 409).at("/error/code").asText())
                .isEqualTo("RFD_REFUND_ALREADY_GRANTED");
    }

    @Test void adminLinksExistingRefundWithoutNewDispatchOrChangingBasis() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        currentActorId.set(ownerId);
        JsonNode ownerGrant = ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), REASON, 200);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        JsonNode linked = adminReview(policyCase, UUID.randomUUID(), policyPayload(2, 1, true), 200);
        assertThat(linked.at("/data/refund/refundId")).isEqualTo(ownerGrant.at("/data/refund/refundId"));
        UUID refundId = UUID.fromString(linked.at("/data/refund/refundId").asText());
        assertThat(jdbc.queryForObject("SELECT basis_type FROM refunds WHERE id=?", String.class, refundId)).isEqualTo("BOOKING_CANCELLATION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_auto_dispatches WHERE refund_id=?", Long.class, refundId)).isEqualTo(1L);
        assertCount("refunds", paid.bookingId(), 1);
    }

    @Test void adminGrantReconcilesNoShowAndSnapshotsInputState() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        Instant deadline = jdbc.queryForObject("SELECT check_in_deadline FROM bookings WHERE id=?", Timestamp.class, paid.bookingId()).toInstant();
        when(applicationClock.instant()).thenReturn(deadline);
        adminReview(policyCase, UUID.randomUUID(), policyPayload(1, 0, true), 200);
        assertThat(jdbc.queryForObject("SELECT cancellation_reason FROM bookings WHERE id=?", String.class, paid.bookingId())).isEqualTo("NO_SHOW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_status_history WHERE booking_id=? AND actor_type='SYSTEM' AND reason='NO_SHOW'", Long.class, paid.bookingId())).isEqualTo(1L);
        JsonNode snapshot = objectMapper.readTree(jdbc.queryForObject("SELECT evidence_snapshot::text FROM booking_service_failure_decisions WHERE booking_id=?", String.class, paid.bookingId()));
        assertThat(snapshot.path("persistedStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(snapshot.path("bookingVersion").asLong()).isEqualTo(1);
    }

    @Test void adminPolicyRejectsWrongCaseStaleVersionsAndUnknownAmount() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        PolicyCase wrongCase = new PolicyCase(policyCase.ticketId(), UUID.randomUUID(), policyCase.adminId());
        adminReview(wrongCase, UUID.randomUUID(), policyPayload(1, 0, true), 404);
        adminReview(policyCase, UUID.randomUUID(), policyPayload(0, 0, true), 409);
        adminReview(policyCase, UUID.randomUUID(), policyPayload(1, 1, true), 409);
        Map<String, Object> payload = new LinkedHashMap<>(policyPayload(1, 0, true));
        payload.put("amount", paid.amount());
        adminReview(policyCase, UUID.randomUUID(), payload, 400);
        for (String role : List.of("OWNER", "STAFF", "DRIVER")) {
            mockMvc.perform(post(policyUrl(policyCase) + "/refund-policy-decisions").with(user("other").roles(role))
                    .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                    .content(json(policyPayload(1, 0, true)))).andExpect(status().isForbidden());
        }
        assertCount("booking_service_failure_decisions", paid.bookingId(), 0);
    }

    @Test void adminContextHidesOtherCaseDecisionsAndNeverOffersOwnerActions() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase first = createPolicyCase(paid.bookingId());
        adminReview(first, UUID.randomUUID(), policyPayload(1, 0, false), 200);
        PolicyCase second = createPolicyCase(paid.bookingId());
        mockMvc.perform(get(policyUrl(second) + "/refund-policy-context").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.booking.decisionVersion").value(1))
                .andExpect(jsonPath("$.data.booking.serviceFailureDecisions").isEmpty())
                .andExpect(jsonPath("$.data.booking.cancel.allowed").value(false))
                .andExpect(jsonPath("$.data.booking.acceptResponsibility.allowed").value(false));
    }

    @Test void adminAndOwnerConcurrentGrantCreateOneObligation() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        when(currentProfileProvider.requireProfile()).thenAnswer(i -> {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            return userProfileRepository.findById(admin ? policyCase.adminId() : ownerId).orElseThrow();
        });
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> owner = pool.submit(() -> concurrentCancel(paid, UUID.randomUUID(), ready, start));
            Future<Integer> admin = pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return mockMvc.perform(post(policyUrl(policyCase) + "/refund-policy-decisions").with(user("admin").roles("ADMIN"))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                        .content(json(policyPayload(1, 0, true)))).andReturn().getResponse().getStatus();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(owner.get(30, TimeUnit.SECONDS), admin.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertCount("refunds", paid.bookingId(), 1);
        assertCount("booking_service_failure_decisions", paid.bookingId(), 1);
        UUID refundId = jdbc.queryForObject("SELECT id FROM refunds WHERE booking_id=?", UUID.class, paid.bookingId());
        String basis = jdbc.queryForObject("SELECT basis_type || ':' || basis_id::text FROM refunds WHERE id=?", String.class, refundId);
        String decisionAudit = jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(d))::text FROM booking_service_failure_decisions d WHERE booking_id=?", String.class, paid.bookingId());
        // Whichever actor lost may refresh and link the obligation, never dispatch a second refund.
        String actorKind = jdbc.queryForObject("SELECT actor_kind FROM booking_service_failure_decisions WHERE booking_id=?", String.class, paid.bookingId());
        if (actorKind.equals("OWNER")) {
            currentActorId.set(policyCase.adminId());
            adminReview(policyCase, UUID.randomUUID(), policyPayload(2, 1, true), 200);
        } else {
            currentActorId.set(ownerId);
            mockMvc.perform(post("/api/v1/owner/bookings/{id}/station-failure-refund", paid.bookingId())
                    .with(user("owner").roles("OWNER")).header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json").content(json(Map.of("expectedVersion", 2,
                            "expectedDecisionVersion", 1, "affectedAt", NOW.toString(), "reason", REASON))))
                    .andExpect(status().isOk());
        }
        assertCount("refunds", paid.bookingId(), 1);
        assertCount("booking_service_failure_decisions", paid.bookingId(), 2);
        assertThat(jdbc.queryForObject("SELECT basis_type || ':' || basis_id::text FROM refunds WHERE id=?", String.class, refundId)).isEqualTo(basis);
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(d))::text FROM booking_service_failure_decisions d WHERE booking_id=? AND sequence_no=1", String.class, paid.bookingId())).isEqualTo(decisionAudit);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_auto_dispatches WHERE refund_id=?", Long.class, refundId)).isEqualTo(1L);
        assertThat(automaticRefundExecutionService.processFirstAttempt(refundId)).isTrue();
        assertThat(automaticRefundExecutionService.processFirstAttempt(refundId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_attempts WHERE refund_id=?", Long.class, refundId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM payments WHERE id=?", Long.class, paid.paymentId())).isEqualTo(paid.amount());
    }

    @Test void bkg075ClosedCaseKeepsFailedRefundAndOwnerRetryWithSafeError() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        JsonNode grant = adminReview(policyCase, UUID.randomUUID(), policyPayload(1, 0, true), 200);
        UUID refundId = UUID.fromString(grant.at("/data/refund/refundId").asText());
        doReturn(new RefundExecutionResult(RefundExecutionOutcome.FAILED, "SIMULATOR", null,
                "PRIVATE_FAILURE", NOW, "internal.example token=PRIVATE_MARKER"))
                .doCallRealMethod().when(simulatorRefundExecutor).execute(any());
        automaticRefundExecutionService.processFirstAttempt(refundId);
        String audit = jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(d))::text FROM booking_service_failure_decisions d WHERE booking_id=?", String.class, paid.bookingId());
        long ticketVersion = jdbc.queryForObject("SELECT version FROM support_tickets WHERE id=?", Long.class, policyCase.ticketId());
        mockMvc.perform(patch("/api/v1/admin/tickets/{id}/escalation", policyCase.ticketId())
                .with(user("admin").roles("ADMIN")).contentType("application/json")
                .content(json(Map.of("expectedVersion", ticketVersion, "action", "CLOSE_SUPPORT_CASE",
                        "closureReason", "OTHER", "note", REASON))))
                .andExpect(status().isOk());
        currentActorId.set(ownerId);
        mockMvc.perform(get("/api/v1/owner/refunds/{id}", refundId).with(user("owner").roles("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.attempts[0].failureCode").value("REFUND_EXECUTION_FAILED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE_MARKER"))));
        long version = jdbc.queryForObject("SELECT version FROM refunds WHERE id=?", Long.class, refundId);
        UUID key = UUID.randomUUID();
        for (String role : List.of("ADMIN", "STAFF", "DRIVER")) {
            mockMvc.perform(post("/api/v1/owner/refunds/{id}/retry", refundId).with(user(role).roles(role))
                    .header("Idempotency-Key", key).contentType("application/json")
                    .content(json(Map.of("expectedVersion", version)))).andExpect(status().isForbidden());
        }
        for (String field : List.of("amount", "outcome", "executionMode", "transferReference")) {
            Map<String, Object> payload = new LinkedHashMap<>(); payload.put("expectedVersion", version); payload.put(field, "SUCCEEDED");
            mockMvc.perform(post("/api/v1/owner/refunds/{id}/retry", refundId).with(user("owner").roles("OWNER"))
                    .header("Idempotency-Key", key).contentType("application/json").content(json(payload)))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/v1/owner/refunds/{id}/retry", refundId).with(user("owner").roles("OWNER"))
                .header("Idempotency-Key", key).contentType("application/json").content(json(Map.of("expectedVersion", version))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        assertThat(jdbc.queryForObject("SELECT status FROM support_tickets WHERE id=?", String.class, policyCase.ticketId())).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(d))::text FROM booking_service_failure_decisions d WHERE booking_id=?", String.class, paid.bookingId())).isEqualTo(audit);
        assertThat(jdbc.queryForObject("SELECT note FROM refund_attempts WHERE refund_id=? AND sequence_no=1", String.class, refundId)).contains("PRIVATE_MARKER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_attempts WHERE refund_id=?", Long.class, refundId)).isEqualTo(2L);
    }

    @Test void adminCannotGrantWhenCaseClosesWhileWaitingForFinancialLock() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try (var pool = Executors.newSingleThreadExecutor()) {
            AtomicReference<Future<Integer>> response = new AtomicReference<>();
            transaction.executeWithoutResult(state -> {
                jdbc.queryForObject("SELECT id FROM connectors WHERE id=? FOR UPDATE", UUID.class, connectorId);
                response.set(pool.submit(() -> mockMvc.perform(post(policyUrl(policyCase) + "/refund-policy-decisions")
                        .with(user("admin").roles("ADMIN")).header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content(json(policyPayload(1, 0, true))))
                        .andReturn().getResponse().getStatus()));
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                        jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%connectors%'", Long.class) > 0);
                jdbc.update("UPDATE support_tickets SET status='CLOSED', version=version+1 WHERE id=?", policyCase.ticketId());
            });
            assertThat(response.get().get(30, TimeUnit.SECONDS)).isEqualTo(409);
        }
        assertCount("refunds", paid.bookingId(), 0);
        assertCount("booking_service_failure_decisions", paid.bookingId(), 0);
    }

    private PolicyCase createPolicyCase(UUID bookingId) {
        UUID adminId = UUID.randomUUID(), ticketId = UUID.randomUUID(), escalationId = UUID.randomUUID();
        String suffix = adminId.toString().replace("-", "");
        insertUser(adminId, "admin-policy-" + suffix, "Policy admin");
        UUID stationId = jdbc.queryForObject("SELECT cp.station_id FROM charge_points cp JOIN connectors c ON c.charge_point_id=cp.id WHERE c.id=?", UUID.class, connectorId);
        jdbc.update("""
                INSERT INTO support_tickets(id,ticket_code,category,status,priority,reporter_id,station_id,booking_id,subject,description)
                VALUES (?, 'TKT-20260926-' || lpad(nextval('support_ticket_code_seq')::text, 4, '0'), 'CHARGING_ISSUE','OPEN','MEDIUM',?,?,?,'Station failure','Investigate station failure')
                """, ticketId, driverId, stationId, bookingId);
        jdbc.update("INSERT INTO ticket_escalations(id,ticket_id,requested_by,requested_at,reason) VALUES (?,?,?,?,?)",
                escalationId, ticketId, driverId, Timestamp.from(NOW), REASON);
        currentActorId.set(adminId);
        return new PolicyCase(ticketId, escalationId, adminId);
    }

    private Map<String, Object> policyPayload(long bookingVersion, long decisionVersion, boolean grant) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("expectedVersion", 0);
        payload.put("expectedBookingVersion", bookingVersion);
        payload.put("expectedDecisionVersion", decisionVersion);
        payload.put("outcome", grant ? "GRANT_FULL_REFUND" : "INSUFFICIENT_EVIDENCE");
        payload.put("reason", REASON);
        if (grant) payload.put("affectedAt", NOW.toString());
        return payload;
    }

    private JsonNode adminReview(PolicyCase policyCase, UUID key, Map<String, Object> payload, int expectedStatus) throws Exception {
        return body(mockMvc.perform(post(policyUrl(policyCase) + "/refund-policy-decisions").with(user("admin").roles("ADMIN"))
                .header("Idempotency-Key", key).contentType("application/json").content(json(payload)))
                .andExpect(status().is(expectedStatus)).andReturn());
    }

    private String policyUrl(PolicyCase policyCase) {
        return "/api/v1/admin/tickets/" + policyCase.ticketId() + "/escalations/" + policyCase.escalationId();
    }

    private record PolicyCase(UUID ticketId, UUID escalationId, UUID adminId) {}

    @Test void unpaidCancelRecordsReasonAndNoRefundAndReplay() throws Exception {
        PendingBooking pending = createPendingBookingWithCheckout(START_AT);
        currentActorId.set(ownerId); UUID key = UUID.randomUUID();
        JsonNode result = ownerCancel(pending.bookingId(), 0, key, "  " + REASON + "  ", 200);
        assertThat(result.at("/data/decision/result").asText()).isEqualTo("NO_APPLIED_PAYMENT");
        assertThat(result.at("/data/decision/reason").asText()).isEqualTo(REASON);
        assertThat(result.at("/data/refund").isNull() || result.at("/data/refund").isMissingNode()).isTrue();
        assertThat(ownerCancel(pending.bookingId(), 0, key, REASON, 200).at("/data/decision/decisionId")).isEqualTo(result.at("/data/decision/decisionId"));
        assertCount("refunds", pending.bookingId(), 0); assertCount("booking_service_failure_decisions", pending.bookingId(), 1);
        assertOwnerHistory(pending.bookingId());
        assertThat(jdbc.queryForObject("SELECT runtime_status FROM connectors WHERE id=?", String.class, connectorId)).isEqualTo("AVAILABLE");
        mockMvc.perform(get("/api/v1/owner/bookings/{id}", pending.bookingId()).with(user("owner").roles("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.serviceFailureDecisions[0].reason").value(REASON));
    }

    @ParameterizedTest @ValueSource(strings = {"OWNER", "ADMIN", "GRACE"})
    void bkg065SourceAndAllOwnerReadsAgreeThroughFailureRetryAndLateReceipt(String decisionKind) throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        bkg065AssertLedger(paid, 0, 0);
        UUID refundId;
        if (decisionKind.equals("GRACE")) {
            currentActorId.set(driverId);
            String policyVersion = jdbc.queryForObject("SELECT policy_version FROM bookings WHERE id=?", String.class, paid.bookingId());
            JsonNode cancelled = body(mockMvc.perform(post("/api/v1/bookings/{id}/cancel", paid.bookingId())
                    .with(user("driver").roles("DRIVER")).header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json").content(json(Map.of("expectedVersion", 1,
                            "expectedRefundAmount", paid.amount(), "acceptedPolicyVersion", policyVersion))))
                    .andExpect(status().isOk()).andReturn());
            refundId = UUID.fromString(cancelled.at("/data/refunds/0/refundId").asText());
        } else if (decisionKind.equals("ADMIN")) {
            PolicyCase policyCase = createPolicyCase(paid.bookingId());
            refundId = UUID.fromString(adminReview(policyCase, UUID.randomUUID(),
                    policyPayload(1, 0, true), 200).at("/data/refund/refundId").asText());
        } else {
            currentActorId.set(ownerId);
            refundId = UUID.fromString(ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), REASON, 200)
                    .at("/data/refund/refundId").asText());
        }
        bkg065AssertLedger(paid, 0, paid.amount());
        doReturn(new RefundExecutionResult(RefundExecutionOutcome.FAILED, "SIMULATOR", null,
                "SIMULATED_FAILURE", NOW, "Temporary failure"))
                .doCallRealMethod().when(simulatorRefundExecutor).execute(any());
        assertThat(automaticRefundExecutionService.processFirstAttempt(refundId)).isTrue();
        bkg065AssertLedger(paid, 0, paid.amount());
        mockMvc.perform(get("/api/v1/owner/refunds/{id}", refundId).with(user("owner").roles("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.requiresOwnerAction").value(true));
        long version = jdbc.queryForObject("SELECT version FROM refunds WHERE id=?", Long.class, refundId);
        UUID retryKey = UUID.randomUUID();
        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(post("/api/v1/owner/refunds/{id}/retry", refundId)
                    .with(user("owner").roles("OWNER")).header("Idempotency-Key", retryKey)
                    .contentType("application/json").content(json(Map.of("expectedVersion", version))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
            bkg065AssertLedger(paid, paid.amount(), 0);
        }
        String paymentCode = jdbc.queryForObject("SELECT payment_code FROM payments WHERE id=?", String.class, paid.paymentId());
        String vaNumber = jdbc.queryForObject("SELECT va_number FROM payments WHERE id=?", String.class, paid.paymentId());
        var late = paymentConfirmationService.processReceipt(new NormalizedReceipt("SIMULATOR", "SIMULATOR",
                "BKG065-LATE-" + UUID.randomUUID(), java.math.BigDecimal.valueOf(paid.amount()), "VND",
                NOW, NOW.plusSeconds(60), vaNumber, paymentCode, "Late additional receipt", "{}"));
        assertThat(late.status()).isEqualTo(com.thang.chargeops.payment.model.PaymentReceiptResult.Status.UNAPPLIED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE payment_id=? AND application_classification='UNAPPLIED'", Long.class, paid.paymentId())).isEqualTo(1L);
        bkg065AssertLedger(paid, paid.amount(), 0);
        assertCount("refunds", paid.bookingId(), 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_attempts WHERE refund_id=?", Long.class, refundId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_attempts WHERE refund_id=? AND status='SUCCEEDED'", Long.class, refundId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, paid.bookingId())).isEqualTo("CANCELLED");
    }

    private void bkg065AssertLedger(PaidBooking paid, long refunded, long pending) throws Exception {
        currentActorId.set(ownerId);
        long sourceGross = jdbc.queryForObject("SELECT coalesce(sum(amount),0) FROM payment_transactions WHERE payment_id=? AND application_classification='APPLIED'", Long.class, paid.paymentId());
        long sourceRefunded = jdbc.queryForObject("SELECT coalesce(sum(amount),0) FROM refunds WHERE payment_id=? AND status='SUCCEEDED'", Long.class, paid.paymentId());
        long sourcePending = jdbc.queryForObject("SELECT coalesce(sum(amount),0) FROM refunds WHERE payment_id=? AND status='PENDING'", Long.class, paid.paymentId());
        assertThat(sourceGross).isEqualTo(paid.amount());
        assertThat(sourceRefunded).isEqualTo(refunded);
        assertThat(sourcePending).isEqualTo(pending);
        mockMvc.perform(get("/api/v1/owner/finance/summary").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.grossVnd").value(sourceGross))
                .andExpect(jsonPath("$.data.refundedVnd").value(sourceRefunded))
                .andExpect(jsonPath("$.data.pendingRefundVnd").value(sourcePending))
                .andExpect(jsonPath("$.data.totalBookings").value(1))
                .andExpect(jsonPath("$.data.paidBookings").value(refunded > 0 ? 0 : 1))
                .andExpect(jsonPath("$.data.netVnd").value(sourceGross - sourceRefunded));
        for (String route : List.of("/api/v1/owner/finance/bookings/" + paid.bookingId(),
                "/api/v1/owner/finance/bookings?page=1&size=20")) {
            JsonNode payload = body(mockMvc.perform(get(route).with(user("owner").roles("OWNER")))
                    .andExpect(status().isOk()).andReturn()).get("data");
            JsonNode row = payload.isArray() ? payload.get(0) : payload;
            assertThat(row.get("bookingId").asText()).isEqualTo(paid.bookingId().toString());
            assertThat(row.get("collectedAmount").asLong()).isEqualTo(sourceGross);
            assertThat(row.get("refundedAmount").asLong()).isEqualTo(sourceRefunded);
            assertThat(row.get("pendingRefundAmount").asLong()).isEqualTo(sourcePending);
            assertThat(row.get("netRecordedAmount").asLong()).isEqualTo(sourceGross - sourceRefunded);
            assertThat(row.get("receipts").size()).isEqualTo(payload.isArray() ? 0 : 1);
        }
        mockMvc.perform(get("/api/v1/owner/refunds/summary").with(user("owner").roles("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pendingRefundAmountVnd").value(sourcePending))
                .andExpect(jsonPath("$.data.totalRefundAmountVnd").value(sourcePending + sourceRefunded));
        if (sourcePending + sourceRefunded > 0) {
            UUID refundId = jdbc.queryForObject("SELECT id FROM refunds WHERE payment_id=?", UUID.class, paid.paymentId());
            String expectedStatus = sourceRefunded > 0 ? "SUCCEEDED" : "PENDING";
            mockMvc.perform(get("/api/v1/owner/refunds").with(user("owner").roles("OWNER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].refundId").value(refundId.toString()))
                    .andExpect(jsonPath("$.data[0].amount").value(sourcePending + sourceRefunded))
                    .andExpect(jsonPath("$.data[0].status").value(expectedStatus));
            mockMvc.perform(get("/api/v1/owner/refunds/{id}", refundId).with(user("owner").roles("OWNER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.amount").value(sourcePending + sourceRefunded))
                    .andExpect(jsonPath("$.data.status").value(expectedStatus));
        }
    }

    @Test void failedSimulatorAttemptStaysPendingAndOwnerRetrySucceeds() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        currentActorId.set(ownerId);
        JsonNode cancelled = ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), REASON, 200);
        UUID refundId = UUID.fromString(cancelled.at("/data/refund/refundId").asText());
        doReturn(new RefundExecutionResult(
                RefundExecutionOutcome.FAILED,
                "SIMULATOR", null, "SIMULATED_FAILURE", NOW, "Temporary failure"))
                .doCallRealMethod().when(simulatorRefundExecutor).execute(any());
        assertThat(automaticRefundExecutionService.processFirstAttempt(refundId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM refunds WHERE id=?", String.class, refundId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paid.paymentId())).isEqualTo("PAID");
        UUID retryKey = UUID.randomUUID();
        long version = jdbc.queryForObject("SELECT version FROM refunds WHERE id=?", Long.class, refundId);
        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(post("/api/v1/owner/refunds/{id}/retry", refundId)
                    .with(user("owner").roles("OWNER")).header("Idempotency-Key", retryKey)
                    .contentType("application/json").content(json(Map.of("expectedVersion", version))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_attempts WHERE refund_id=?", Long.class, refundId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM payments WHERE id=?", java.math.BigDecimal.class, paid.paymentId()).longValueExact()).isEqualTo(paid.amount());
        assertOwnerHistory(paid.bookingId());
    }

    @Test void fullPackageAfterGraceAndReplayTracksSucceededRefund() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        when(applicationClock.instant()).thenReturn(NOW.plusSeconds(120)); currentActorId.set(ownerId);
        UUID key = UUID.randomUUID(); JsonNode result = ownerCancel(paid.bookingId(), 1, key, REASON, 200);
        assertThat(result.at("/data/refund/amount").asLong()).isEqualTo(paid.amount());
        assertThat(result.at("/data/refund/status").asText()).isEqualTo("PENDING");
        UUID refundId = UUID.fromString(result.at("/data/refund/refundId").asText());
        assertThat(jdbc.queryForObject("SELECT basis_type FROM refunds WHERE id=?", String.class, refundId)).isEqualTo("BOOKING_CANCELLATION");
        assertThat(jdbc.queryForObject("SELECT reason FROM refunds WHERE id=?", String.class, refundId)).isEqualTo("STATION_FAILURE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_auto_dispatches WHERE refund_id=?", Long.class, refundId)).isEqualTo(1L);
        assertThat(automaticRefundExecutionService.processFirstAttempt(refundId)).isTrue();
        assertThat(ownerCancel(paid.bookingId(), 1, key, REASON, 200).at("/data/refund/status").asText()).isEqualTo("SUCCEEDED");
        assertCount("refunds", paid.bookingId(), 1); assertOwnerHistory(paid.bookingId());
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM payments WHERE id=?", java.math.BigDecimal.class, paid.paymentId()).longValueExact()).isEqualTo(paid.amount());
        currentActorId.set(driverId);
        mockMvc.perform(get("/api/v1/bookings/{id}", paid.bookingId()).with(user("driver").roles("DRIVER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.serviceFailureDecisions[0].reason").value(REASON))
                .andExpect(jsonPath("$.data.serviceFailureDecisions[0].actorProfileId").doesNotExist())
                .andExpect(jsonPath("$.data.serviceFailureDecisions[0].evidenceSnapshot").doesNotExist());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void concurrentCommandsCreateOneDecisionAndRefund(boolean sameKey) throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT); currentActorId.set(ownerId);
        UUID key = UUID.randomUUID(); CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> a = pool.submit(() -> concurrentCancel(paid, key, ready, start));
            Future<Integer> b = pool.submit(() -> concurrentCancel(paid, sameKey ? key : UUID.randomUUID(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, sameKey ? 200 : 409);
        }
        assertCount("refunds", paid.bookingId(), 1); assertCount("booking_service_failure_decisions", paid.bookingId(), 1);
    }

    @Test void wrongOwnerIsHiddenAndStaffDriverAdminCannotCancel() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        for (String role : List.of("STAFF", "DRIVER", "ADMIN"))
            mockMvc.perform(post("/api/v1/owner/bookings/{id}/cancel", paid.bookingId()).with(user("other").roles(role))
                    .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                    .content(json(Map.of("expectedVersion", 1, "reason", REASON)))).andExpect(status().isForbidden());
        currentActorId.set(driverId); ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), REASON, 404);
        assertCount("refunds", paid.bookingId(), 0);
    }

    @Test void refusesClientAmountAndMissingKeyAndShortReason() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT); currentActorId.set(ownerId);
        mockMvc.perform(post("/api/v1/owner/bookings/{id}/cancel", paid.bookingId()).with(user("owner").roles("OWNER"))
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                .content(json(Map.of("expectedVersion", 1, "reason", REASON, "amount", paid.amount())))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/owner/bookings/{id}/cancel", paid.bookingId()).with(user("owner").roles("OWNER"))
                .contentType("application/json").content(json(Map.of("expectedVersion", 1, "reason", REASON)))).andExpect(status().isBadRequest());
        ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), "  a  ", 400); assertCount("refunds", paid.bookingId(), 0);
    }

    @Test void changedKeyPayloadAndStalePaymentVersionConflict() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT); currentActorId.set(ownerId);
        assertThat(ownerCancel(paid.bookingId(), 0, UUID.randomUUID(), REASON, 409).at("/error/code").asText()).isEqualTo("BKG_VERSION_CONFLICT");
        UUID key = UUID.randomUUID(); ownerCancel(paid.bookingId(), 1, key, REASON, 200);
        assertThat(ownerCancel(paid.bookingId(), 1, key, REASON + " đổi", 409).at("/error/code").asText()).isEqualTo("CMD_KEY_REUSED");
    }

    @ParameterizedTest @ValueSource(strings = {"CHECKED_IN", "CHARGING", "COMPLETED"})
    void doesNotCancelSessionsOrTerminalBooking(String state) throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        jdbc.update("UPDATE bookings SET status=?, checked_in_at=? WHERE id=?", state, Timestamp.from(NOW), paid.bookingId());
        currentActorId.set(ownerId); ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), REASON, 409);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, paid.bookingId())).isEqualTo(state);
        assertCount("refunds", paid.bookingId(), 0);
    }

    @Test void deadlineEqualityAndExpiredHoldRejectWithoutMutation() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        jdbc.update("UPDATE bookings SET check_in_deadline=? WHERE id=?", Timestamp.from(NOW), paid.bookingId());
        currentActorId.set(ownerId); ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), REASON, 409);
        jdbc.update("UPDATE bookings SET status='CANCELLED', cancellation_reason='NO_SHOW' WHERE id=?", paid.bookingId());
        PendingBooking pending = createPendingBookingWithCheckout(START_AT.plusSeconds(7200));
        jdbc.update("UPDATE bookings SET expires_at=? WHERE id=?", Timestamp.from(NOW), pending.bookingId());
        currentActorId.set(ownerId); ownerCancel(pending.bookingId(), 0, UUID.randomUUID(), REASON, 409);
        assertCount("booking_service_failure_decisions", pending.bookingId(), 0);
    }

    @Test void refundFailureRollsBackBookingCommandAndDecision() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT); currentActorId.set(ownerId);
        RefundObligationService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(obligations);
        doThrow(new AppException(RefundErrorCode.EXECUTION_CONFLICT)).when(target).createObligation(any());
        ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), REASON, 409);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, paid.bookingId())).isEqualTo("CONFIRMED");
        assertCount("booking_service_failure_decisions", paid.bookingId(), 0); assertCount("refunds", paid.bookingId(), 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_commands WHERE booking_id=? AND operation='OWNER_CANCEL_BOOKING'", Long.class, paid.bookingId())).isZero();
    }

    @Test void admissionCapturesInputStateBeforeNoShowReconciliation() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        Instant deadline = jdbc.queryForObject("SELECT check_in_deadline FROM bookings WHERE id=?",
                Timestamp.class, paid.bookingId()).toInstant();
        when(applicationClock.instant()).thenReturn(deadline);
        currentActorId.set(ownerId);

        mockMvc.perform(post("/api/v1/owner/bookings/{id}/station-failure-refund", paid.bookingId())
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json")
                        .content(json(Map.of("expectedVersion", 1, "expectedDecisionVersion", 0,
                                "affectedAt", START_AT.toString(), "reason", REASON))))
                .andExpect(status().isOk());

        JsonNode snapshot = objectMapper.readTree(jdbc.queryForObject(
                "SELECT evidence_snapshot::text FROM booking_service_failure_decisions WHERE booking_id=?",
                String.class, paid.bookingId()));
        assertThat(snapshot.path("persistedStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(snapshot.path("bookingVersion").asLong()).isEqualTo(1L);
        assertThat(snapshot.has("cancellationReason")).isFalse();
        assertThat(jdbc.queryForObject("SELECT cancellation_reason FROM bookings WHERE id=?",
                String.class, paid.bookingId())).isEqualTo("NO_SHOW");
        assertThat(jdbc.queryForObject("SELECT version FROM bookings WHERE id=?",
                Long.class, paid.bookingId())).isEqualTo(2L);
        assertCount("refunds", paid.bookingId(), 1);
    }

    @Test void responsibilityIsAppendOnlyAtDatabaseLevel() throws Exception {
        PendingBooking pending = createPendingBookingWithCheckout(START_AT); currentActorId.set(ownerId);
        ownerCancel(pending.bookingId(), 0, UUID.randomUUID(), REASON, 200);
        assertThatThrownBy(() -> jdbc.update("UPDATE booking_service_failure_decisions SET reason='changed reason' WHERE booking_id=?", pending.bookingId())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM booking_service_failure_decisions WHERE booking_id=?", pending.bookingId())).hasMessageContaining("append-only");
    }

    private JsonNode ownerCancel(UUID bookingId, long version, UUID key, String reason, int expectedStatus) throws Exception {
        var result = mockMvc.perform(post("/api/v1/owner/bookings/{id}/cancel", bookingId).with(user("owner").roles("OWNER"))
                .header("Idempotency-Key", key).contentType("application/json").content(json(Map.of("expectedVersion", version, "reason", reason))))
                .andExpect(status().is(expectedStatus));
        if (expectedStatus == 200) result.andExpect(header().string("Cache-Control", "no-store"));
        return body(result.andReturn());
    }
    private int concurrentCancel(PaidBooking paid, UUID key, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
        return mockMvc.perform(post("/api/v1/owner/bookings/{id}/cancel", paid.bookingId()).with(user("owner").roles("OWNER"))
                .header("Idempotency-Key", key).contentType("application/json").content(json(Map.of("expectedVersion", 1, "reason", REASON))))
                .andReturn().getResponse().getStatus();
    }
    private void assertCount(String table, UUID bookingId, long count) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE booking_id=?", Long.class, bookingId)).isEqualTo(count);
    }
    private void assertOwnerHistory(UUID bookingId) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_status_history WHERE booking_id=? AND actor_type='OWNER' AND reason='OWNER_CANCELLED'", Long.class, bookingId)).isEqualTo(1L);
    }

    private PaidBooking createAndPayBooking(Instant startAt) throws Exception {
        PendingBooking pending = createPendingBookingWithCheckout(startAt);
        JsonNode simulated = body(mockMvc.perform(post(
                                "/api/v1/bookings/{bookingId}/simulate-payment", pending.bookingId())
                        .with(user("driver").roles("DRIVER"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json")
                        .content(json(Map.of(
                                "transactionRef", "TX-" + UUID.randomUUID(),
                                "outcome", "SUCCESS",
                                "amount", pending.amount(),
                                "currency", "VND",
                                "providerPaidAt", NOW.minusSeconds(1).toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.booking.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.booking.payment.status").value("PAID"))
                .andReturn());

        assertThat(simulated.at("/data/receipt/classification").asText()).isEqualTo("APPLIED");
        return new PaidBooking(
                pending.bookingId(), pending.paymentId(), pending.amount(), 1L
        );
    }

    private PendingBooking createPendingBookingWithCheckout(Instant startAt) throws Exception {
        currentActorId.set(driverId);
        JsonNode preview = body(mockMvc.perform(post("/api/v1/bookings/price-preview")
                        .with(user("driver").roles("DRIVER"))
                        .contentType("application/json")
                        .content(json(Map.of(
                                "connectorId", connectorId,
                                "startAt", startAt,
                                "durationMin", 60
                        ))))
                .andExpect(status().isOk())
                .andReturn());
        long amount = preview.at("/data/totalAmount").asLong();
        String pricingVersion = preview.at("/data/pricingVersion").asText();
        String policyVersion = preview.at("/data/policy/policyVersion").asText();

        JsonNode created = body(mockMvc.perform(post("/api/v1/bookings")
                        .with(user("driver").roles("DRIVER"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json")
                        .content(json(Map.of(
                                "connectorId", connectorId,
                                "startAt", startAt,
                                "durationMin", 60,
                                "acceptedTotalAmount", amount,
                                "acceptedPricingVersion", pricingVersion,
                                "acceptedPolicyVersion", policyVersion,
                                "paymentMethod", "SIMULATOR"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn());
        UUID bookingId = UUID.fromString(created.at("/data/bookingId").asText());
        UUID paymentId = UUID.fromString(created.at("/data/payment/paymentId").asText());

        mockMvc.perform(post(
                                "/api/v1/bookings/{bookingId}/checkout", bookingId)
                        .with(user("driver").roles("DRIVER"))
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andReturn();
        var payment = paymentRepository.findById(paymentId).orElseThrow();

        return new PendingBooking(
                bookingId,
                paymentId,
                amount,
                payment.getPaymentCode(),
                payment.getVaNumber()
        );
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private Fixture insertBookableStationFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        UUID ownerId = UUID.randomUUID();
        UUID fixtureDriverId = UUID.randomUUID();
        UUID fixtureAdminId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UUID chargePointId = UUID.randomUUID();
        UUID fixtureConnectorId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();

        insertUser(ownerId, "owner-" + suffix, "Owner " + suffix);
        insertUser(fixtureDriverId, "driver-" + suffix, "Driver " + suffix);
        insertUser(fixtureAdminId, "admin-" + suffix, "Admin " + suffix);

        jdbc.update("""
                INSERT INTO stations
                    (id, station_code, owner_id, name, address_line,
                     latitude, longitude, contact_phone, planned_charge_point_count,
                     status, operational_status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'BKG-038 Test Address',
                        10.7769, 106.7009, '0900000000', 1,
                        'ACTIVE', 'OPERATING', 0, ?, ?)
                """, stationId, "ST-BKG38-" + suffix, ownerId, "BKG-038 Station " + suffix,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO licenses
                    (id, license_code, station_id, owner_id, plan, fee_amount,
                     start_at, expires_at, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'MONTHLY', 0, ?, ?, 'ACTIVE', 0, ?, ?)
                """, UUID.randomUUID(), "LIC-BKG38-" + suffix, stationId, ownerId,
                Timestamp.from(NOW.minusSeconds(3600)), Timestamp.from(NOW.plusSeconds(172800)),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO station_booking_settings
                    (id, station_id, min_duration_minutes, duration_step_minutes,
                     max_duration_minutes, base_price_vnd, version, created_at, updated_at)
                VALUES (?, ?, 30, 30, 180, 3400, 0, ?, ?)
                """, UUID.randomUUID(), stationId, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO station_operating_schedules
                    (id, station_id, open_24_hours, effective_from, created_at, updated_at)
                VALUES (?, ?, true, ?, ?, ?)
                """, scheduleId, stationId, Timestamp.from(NOW.minusSeconds(3600)),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO charge_points
                    (id, station_id, charge_point_code, max_power_kw,
                     provisioning_status, operational_status, version, created_at, updated_at)
                VALUES (?, ?, ?, 60, 'ACTIVE', 'AVAILABLE', 0, ?, ?)
                """, chargePointId, stationId, "CP-BKG38-" + suffix,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO connectors
                    (id, charge_point_id, connector_code, connector_type, power_kw,
                     charger_type, runtime_status, version, created_at, updated_at)
                VALUES (?, ?, ?, 'CCS2', 60, 'DC', 'AVAILABLE', 0, ?, ?)
                """, fixtureConnectorId, chargePointId, "CON-BKG38-" + suffix,
                Timestamp.from(NOW), Timestamp.from(NOW));

        return new Fixture(fixtureDriverId, fixtureAdminId, fixtureConnectorId);
    }

    private void insertUser(UUID id, String keycloakId, String displayName) {
        jdbc.update("""
                INSERT INTO user_profile
                    (id, keycloak_id, email, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, id, keycloakId, keycloakId + "@example.test", displayName,
                Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private record Fixture(UUID driverId, UUID adminId, UUID connectorId) {
    }

    private record PendingBooking(
            UUID bookingId,
            UUID paymentId,
            long amount,
            String paymentCode,
            String vaNumber
    ) {
    }

    private record PaidBooking(
            UUID bookingId,
            UUID paymentId,
            long amount,
            long confirmedVersion
    ) {
    }
}
