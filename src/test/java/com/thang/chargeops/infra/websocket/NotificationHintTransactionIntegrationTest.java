package com.thang.chargeops.infra.websocket;

import com.thang.chargeops.notification.entity.AppNotification;
import com.thang.chargeops.notification.event.NotificationHintEvent;
import com.thang.chargeops.notification.model.NotificationContent;
import com.thang.chargeops.notification.service.NotificationPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * AFTER_COMMIT boundary of the notification hint (BKG-071 plan #69 §4/§7): a committed insert
 * sends exactly one private hint with the contract v1 body, a duplicate insert and a rollback
 * send nothing, and an event outside a transaction is never delivered.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import({NotificationPersistenceService.class, NotificationHintPublisher.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationHintTransactionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-01T04:00:00Z");
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @TestConfiguration(proxyBeanMethods = false)
    static class MessagingTestConfig {

        @Bean
        SimpMessagingTemplate simpMessagingTemplate() {
            // In-memory channel with no subscribers: send() returns immediately.
            return new SimpMessagingTemplate(new ExecutorSubscribableChannel());
        }
    }

    @Autowired
    private NotificationPersistenceService persistence;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private SimpMessagingTemplate messagingTemplate;

    private UUID recipientId;
    private UUID ticketId;
    private String keycloakId;

    @BeforeEach
    void insertFixture() {
        recipientId = UUID.randomUUID();
        ticketId = UUID.randomUUID();
        keycloakId = UUID.randomUUID().toString();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update("""
                INSERT INTO user_profile
                    (id, keycloak_id, email, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, '', 'ACTIVE', ?, ?)
                """,
                recipientId,
                keycloakId,
                "hint-" + suffix + "@chargeops.test",
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        // The notice references its source ticket: the FK must resolve.
        jdbcTemplate.update("""
                INSERT INTO support_tickets(id,ticket_code,category,status,priority,reporter_id,
                    subject,description,resolution_cycle)
                VALUES (?,?,?,'OPEN','MEDIUM',?,?,'Safe reporter response',1)
                """,
                ticketId,
                // ck_support_tickets_code: ^TKT-[0-9]{8}-[0-9]{4,}$
                "TKT-20261001-" + ThreadLocalRandom.current().nextInt(10000, 99999),
                "BOOKING", recipientId, "Support");
    }

    @Test
    void commitAfterInsertSendsExactlyOneContractV1Hint() {
        TransactionTemplate transaction = newTransaction();
        AppNotification notice = notice();

        transaction.executeWithoutResult(status -> {
            assertThat(persistence.saveIfAbsent(notice)).isTrue();
            // Still uncommitted: the AFTER_COMMIT listener must not have fired yet.
            verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any(), any(Map.class));
        });

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq(keycloakId), eq(NotificationHintPublisher.USER_QUEUE_DESTINATION),
                payload.capture(), any(Map.class));
        byte[] bytes = (byte[]) payload.getValue();
        assertThat(bytes).hasSizeLessThan(NotificationHintEvent.MAX_PAYLOAD_BYTES);

        Map<String, Object> body = JSON.readValue(
                new String(bytes, java.nio.charset.StandardCharsets.UTF_8), Map.class);
        assertThat(body.keySet()).containsExactlyInAnyOrder("v", "eventId", "type", "ref");
        assertThat(body.get("v")).isEqualTo(1);
        assertThat(body.get("type")).isEqualTo("NOTIFICATION_CREATED");
        assertThat(body.get("ref")).isEqualTo(notice.getSourceEventKey());
    }

    @Test
    void duplicateInsertSendsNoSecondHint() {
        TransactionTemplate transaction = newTransaction();
        AppNotification notice = notice();
        transaction.executeWithoutResult(status -> assertThat(persistence.saveIfAbsent(notice)).isTrue());
        clearInvocations(messagingTemplate);

        transaction.executeWithoutResult(status -> assertThat(persistence.saveIfAbsent(notice)).isFalse());

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void rollbackAfterInsertSendsNothingAndPersistsNothing() {
        TransactionTemplate transaction = newTransaction();

        transaction.executeWithoutResult(status -> {
            assertThat(persistence.saveIfAbsent(notice())).isTrue();
            status.setRollbackOnly();
        });

        verifyNoInteractions(messagingTemplate);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_notifications WHERE recipient_id = ?", Long.class, recipientId))
                .isZero();
    }

    @Test
    void eventPublishedOutsideTransactionSendsNothing() {
        eventPublisher.publishEvent(NotificationHintEvent.created(keycloakId, "source-1"));

        verifyNoInteractions(messagingTemplate);
    }

    // --- helpers ---

    private TransactionTemplate newTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private AppNotification notice() {
        return AppNotification.ticket(recipientId, ticketId + ":1:RESOLVED",
                new NotificationContent("Resolved", "Safe reporter response", NOW),
                "/tickets/" + ticketId);
    }
}
