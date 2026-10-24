package com.thang.chargeops.infra.websocket;

import com.thang.chargeops.notification.event.NotificationHintEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Acceptance for the private notification hint channel (BKG-071 plan #69 §7): the raw
 * {@code SUBSCRIBE /user/queue/notifications} is allowed, other user queues stay denied, and —
 * the point of the whole exercise — a hint published for one principal reaches only that
 * principal's session. This is the proof that {@code convertAndSendToUser} and Spring's
 * user-destination resolution line up with the {@code JwtAuthenticationToken} set at CONNECT.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.flyway.enabled=true",
                "spring.docker.compose.enabled=false",
                "spring.task.scheduling.enabled=false"
        }
)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = NONE)
class WebSocketNotificationHintIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Set<String> CONTRACT_KEYS = Set.of("v", "eventId", "type", "ref");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    /** Two deterministic principals so cross-user delivery can actually be observed. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TwoPrincipalJwtConfig {

        @Bean
        @Primary
        JwtDecoder wsHintJwtDecoder() {
            return token -> switch (token) {
                case "token-a" -> jwt(token, "hint-user-a");
                case "token-b" -> jwt(token, "hint-user-b");
                default -> throw new JwtException("unknown test token");
            };
        }

        private static Jwt jwt(String token, String subject) {
            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject(subject)
                    .issuedAt(Instant.now().minusSeconds(60))
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
        }
    }

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private StandardWebSocketClient webSocketClient;
    private WebSocketStompClient stompClient;
    private ThreadPoolTaskScheduler taskScheduler;

    @BeforeEach
    void setUpClient() {
        // UM-02: CONNECT fail-closed — seed ACTIVE profiles for both test principals.
        for (String subject : List.of("hint-user-a", "hint-user-b")) {
            jdbcTemplate.update("""
                    INSERT INTO user_profile(id, keycloak_id, email, display_name, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'ACTIVE', now(), now())
                    ON CONFLICT (keycloak_id) DO NOTHING
                    """,
                    UUID.randomUUID(), subject, subject + "@test.local", subject);
        }
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(1);
        taskScheduler.setDaemon(true);
        taskScheduler.initialize();
        webSocketClient = new StandardWebSocketClient();
        stompClient = new WebSocketStompClient(webSocketClient);
        stompClient.setMessageConverter(new SimpleMessageConverter());
        stompClient.setTaskScheduler(taskScheduler);
        stompClient.setDefaultHeartbeat(new long[]{10_000L, 10_000L});
    }

    @AfterEach
    void tearDownClient() {
        stompClient.stop();
        taskScheduler.shutdown();
    }

    @Test
    void hintReachesOnlyTheRecipientSession() throws Exception {
        StompSession sessionA = connect(new RecordingHandler(), "token-a");
        StompSession sessionB = connect(new RecordingHandler(), "token-b");
        CapturingFrameHandler inboxA = new CapturingFrameHandler();
        CapturingFrameHandler inboxB = new CapturingFrameHandler();
        sessionA.subscribe("/user/queue/notifications", inboxA);
        sessionB.subscribe("/user/queue/notifications", inboxB);

        // Retry committed hints until A's subscription is live; B must stay empty the whole time.
        AtomicInteger sequence = new AtomicInteger();
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            publishHintFor("hint-user-a", "a-" + sequence.incrementAndGet());
            assertThat(inboxA.payloads)
                    .as("inboxA must receive hint for user A")
                    .anyMatch(p -> parse(p).get("eventId").toString().startsWith("CREATED:hint-user-a:"));
            assertThat(inboxB.payloads)
                    .as("session B must never see session A's hints")
                    .isEmpty();
        });

        Map<String, Object> body = parse(inboxA.payloads.getFirst());
        assertThat(body.keySet()).isSubsetOf(CONTRACT_KEYS);
        assertThat(body.get("type")).isEqualTo("NOTIFICATION_CREATED");

        // And the reverse direction: B receives its own hint, A receives no hint for user B.
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            publishHintFor("hint-user-b", "b-" + sequence.incrementAndGet());
            assertThat(inboxB.payloads)
                    .as("inboxB must receive hint for user B")
                    .anyMatch(p -> parse(p).get("eventId").toString().startsWith("CREATED:hint-user-b:"));
            assertThat(inboxA.payloads)
                    .as("session A must not receive session B's hints")
                    .noneMatch(p -> parse(p).get("eventId").toString().startsWith("CREATED:hint-user-b:"));
        });

        assertThat(sessionA.isConnected()).isTrue();
        assertThat(sessionB.isConnected()).isTrue();
        sessionA.disconnect();
        sessionB.disconnect();
    }

    @Test
    void subscribeToForeignUserQueueIsRejected() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        StompSession session = connect(handler, "token-a");

        session.subscribe("/user/queue/bookings", new CapturingFrameHandler());

        await().atMost(6, TimeUnit.SECONDS)
                .until(() -> handler.failure.getCount() == 0 || !session.isConnected());
        assertThat(handler.failure.getCount() == 0 || !session.isConnected())
                .as("only /user/queue/notifications may be subscribed (errors: %s)", handler.errors)
                .isTrue();
        await().atMost(6, TimeUnit.SECONDS).until(() -> !session.isConnected());
    }

    // --- helpers ---

    private String wsUri() {
        return "ws://localhost:" + environment.getRequiredProperty("local.server.port") + "/ws";
    }

    private StompSession connect(RecordingHandler handler, String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        WebSocketHttpHeaders httpHeaders = new WebSocketHttpHeaders();
        httpHeaders.set(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);
        CompletableFuture<StompSession> future =
                stompClient.connectAsync(wsUri(), httpHeaders, connectHeaders, handler);
        return future.get(5, TimeUnit.SECONDS);
    }

    private void publishHintFor(String keycloakId, String sourceKey) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(NotificationHintEvent.created(keycloakId, sourceKey)));
    }

    private Map<String, Object> parse(byte[] payload) {
        return JSON.readValue(new String(payload, StandardCharsets.UTF_8), Map.class);
    }

    private static final class RecordingHandler extends StompSessionHandlerAdapter {
        private final CountDownLatch failure = new CountDownLatch(1);
        private final List<String> errors = new CopyOnWriteArrayList<>();

        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                                    byte[] payload, Throwable exception) {
            errors.add(String.valueOf(exception));
            failure.countDown();
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            errors.add(String.valueOf(exception));
            failure.countDown();
        }
    }

    private static final class CapturingFrameHandler implements StompFrameHandler {
        private final List<byte[]> payloads = new CopyOnWriteArrayList<>();

        @Override
        public Class<?> getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            if (payload instanceof byte[] bytes) {
                payloads.add(bytes);
            }
        }
    }
}
