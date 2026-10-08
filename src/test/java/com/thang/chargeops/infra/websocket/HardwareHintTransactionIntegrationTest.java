package com.thang.chargeops.infra.websocket;

import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.event.EquipmentChangeType;
import com.thang.chargeops.station.event.EquipmentStateChangedEvent;
import com.thang.chargeops.station.service.EquipmentStatusHistoryService;
import com.thang.chargeops.station.service.impl.EquipmentStatusHistoryServiceImpl;
import jakarta.persistence.EntityManager;
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
import org.springframework.messaging.Message;
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

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * AFTER_COMMIT semantics of the hardware hint (WebSocket plan #02 §9):
 * history change inside a real transaction must not send before commit, must send the exact
 * topic/body on commit, and must not send on rollback or outside a transaction.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import({
        EquipmentStatusHistoryServiceImpl.class,
        com.thang.chargeops.incident.IncidentReasonResolver.class,
        StationHardwareHintPublisher.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HardwareHintTransactionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-01T04:00:00Z");
    private static final String HINT_BODY = "{\"v\":1,\"type\":\"HARDWARE_CHANGED\"}";

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
            // In-memory channel with no subscribers: send() returns immediately,
            // which is exactly the no-session broker behaviour under test.
            return new SimpMessagingTemplate(new ExecutorSubscribableChannel());
        }
    }

    @Autowired
    private EquipmentStatusHistoryService historyService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockitoSpyBean
    private SimpMessagingTemplate messagingTemplate;

    private UUID ownerId;
    private UUID stationId;
    private UUID chargePointId;
    private UUID connectorId;

    @BeforeEach
    void insertFixture() {
        ownerId = UUID.randomUUID();
        stationId = UUID.randomUUID();
        chargePointId = UUID.randomUUID();
        connectorId = UUID.randomUUID();
        String shortSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String provinceCode = "P" + shortSuffix;
        String wardCode = "W" + shortSuffix;

        jdbcTemplate.update(
                "INSERT INTO provinces(code, name, full_name) VALUES (?, ?, ?)",
                provinceCode, "Province " + shortSuffix, "Test Province " + shortSuffix
        );
        jdbcTemplate.update(
                "INSERT INTO wards(code, name, full_name, province_code) VALUES (?, ?, ?, ?)",
                wardCode, "Ward " + shortSuffix, "Test Ward " + shortSuffix, provinceCode
        );
        jdbcTemplate.update("""
                INSERT INTO user_profile
                    (id, keycloak_id, email, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, '', 'ACTIVE', ?, ?)
                """,
                ownerId,
                UUID.randomUUID().toString(),
                "hw-" + shortSuffix + "@chargeops.test",
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
        jdbcTemplate.update("""
                INSERT INTO stations(
                    id, station_code, owner_id, name, address_line, latitude, longitude,
                    contact_phone, planned_charge_point_count, status, version, ward_code,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, ?, 'Test Address', 10.0, 106.0,
                        '0123456789', 1, 'ACTIVE', 0, ?, ?, ?)
                """,
                stationId, "ST-" + shortSuffix, ownerId, "HW Station " + shortSuffix,
                wardCode, Timestamp.from(NOW), Timestamp.from(NOW)
        );
        jdbcTemplate.update("""
                INSERT INTO charge_points(
                    id, station_id, charge_point_code, max_power_kw,
                    provisioning_status, operational_status, version, created_at, updated_at
                )
                VALUES (?, ?, ?, 60.0, 'ACTIVE', 'AVAILABLE', 0, ?, ?)
                """,
                chargePointId, stationId, "CP-" + shortSuffix, Timestamp.from(NOW), Timestamp.from(NOW)
        );
        jdbcTemplate.update("""
                INSERT INTO connectors(
                    id, charge_point_id, connector_code, connector_type,
                    power_kw, charger_type, runtime_status, version, created_at, updated_at
                )
                VALUES (?, ?, ?, 'CCS2', 60.0, 'DC', 'AVAILABLE', 0, ?, ?)
                """,
                connectorId, chargePointId, "CON-" + shortSuffix, Timestamp.from(NOW), Timestamp.from(NOW)
        );
    }

    @Test
    void commitAfterHistoryChangeSendsExactHardwareHint() {
        TransactionTemplate transaction = newTransaction();

        transaction.executeWithoutResult(status -> {
            Connector connector = entityManager.find(Connector.class, connectorId);
            historyService.transitionConnectorRuntimeAsSystem(
                    connector, RuntimeStatus.IN_USE, Instant.now(), "Booking check-in");
            // Still uncommitted: AFTER_COMMIT listener must not have fired yet.
            verify(messagingTemplate, never()).send(anyString(), any());
        });

        ArgumentCaptor<Message<byte[]>> captor = MessagePayloadCaptor.create();
        verify(messagingTemplate).send(
                eq("/topic/stations/" + stationId + "/hardware"),
                captor.capture()
        );
        Message<?> message = captor.getValue();
        byte[] payload = (byte[]) message.getPayload();
        assertThat(payload).isEqualTo(HINT_BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(new String(payload, StandardCharsets.UTF_8)).hasSizeLessThan(100);
    }

    @Test
    void rollbackAfterHistoryChangeSendsNothing() {
        TransactionTemplate transaction = newTransaction();

        transaction.executeWithoutResult(status -> {
            Connector connector = entityManager.find(Connector.class, connectorId);
            historyService.transitionConnectorRuntimeAsSystem(
                    connector, RuntimeStatus.IN_USE, Instant.now(), "Will roll back");
            status.setRollbackOnly();
        });

        verifyNoInteractions(messagingTemplate);
        String persistedStatus = jdbcTemplate.queryForObject(
                "SELECT runtime_status FROM connectors WHERE id = ?",
                String.class, connectorId);
        assertThat(persistedStatus).isEqualTo("AVAILABLE");
    }

    @Test
    void eventPublishedOutsideTransactionSendsNothing() {
        eventPublisher.publishEvent(new EquipmentStateChangedEvent(
                stationId, chargePointId, connectorId,
                "AVAILABLE", "IN_USE", EquipmentChangeType.CONNECTOR_RUNTIME, Instant.now()));

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void failedTransitionInsideTransactionSendsNothing() {
        TransactionTemplate transaction = newTransaction();

        assertThat(catchThrowable(() ->
                transaction.executeWithoutResult(status -> {
                    Connector connector = entityManager.find(Connector.class, connectorId);
                    // AVAILABLE -> AVAILABLE is an invalid no-op transition: throws before publish.
                    historyService.transitionConnectorRuntimeAsSystem(
                            connector, RuntimeStatus.AVAILABLE, Instant.now(), "Duplicate");
                })
        )).isNotNull();

        verifyNoInteractions(messagingTemplate);
    }

    private TransactionTemplate newTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * Message payloads need an unchecked captor cast because
     * {@code ArgumentCaptor.forClass(Message.class)} yields the raw type.
     */
    private static final class MessagePayloadCaptor {
        @SuppressWarnings("unchecked")
        static ArgumentCaptor<Message<byte[]>> create() {
            return ArgumentCaptor.forClass((Class<Message<byte[]>>) (Class<?>) Message.class);
        }
    }
}
