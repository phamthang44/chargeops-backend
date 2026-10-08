package com.thang.chargeops.infra.websocket;

import com.thang.chargeops.station.event.EquipmentChangeType;
import com.thang.chargeops.station.event.EquipmentStateChangedEvent;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * End-to-end acceptance for the WebSocket Hardware Path (WebSocket plan #02 Â§9):
 * handshake origin policy, CONNECT token enforcement, SUBSCRIBE allowlist, SEND denial,
 * token-expiry session close, server heart-beat, and a real commit-then-hint round trip.
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
class WebSocketHardwarePathIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String HINT_BODY = "{\"v\":1,\"type\":\"HARDWARE_CHANGED\"}";
    private static final AtomicLong TOKEN_TTL_SECONDS = new AtomicLong(300);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    /**
     * Overrides the shared test decoder (fixed 300s TTL) with a controllable TTL so the
     * expiry-close path can be exercised without waiting five minutes.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ConfigurableExpiryJwtConfig {

        @Bean
        @Primary
        JwtDecoder wsTestJwtDecoder() {
            return token -> new Jwt(
                    token,
                    Instant.now(),
                    Instant.now().plusSeconds(TOKEN_TTL_SECONDS.get()),
                    Map.of("alg", "none"),
                    Map.of("sub", "ws-test-user")
            );
        }
    }

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private StandardWebSocketClient webSocketClient;
    private WebSocketStompClient stompClient;
    private ThreadPoolTaskScheduler taskScheduler;

    @BeforeEach
    void setUpClient() {
        TOKEN_TTL_SECONDS.set(300);
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
        TOKEN_TTL_SECONDS.set(300);
    }

    // --- handshake / CONNECT policy ---

    @Test
    void handshakeWithValidOriginAndBearerConnects() throws Exception {
        RecordingHandler handler = new RecordingHandler();

        StompSession session = connect(handler, ALLOWED_ORIGIN, "any-test-token");

        assertThat(session.isConnected()).isTrue();
        session.disconnect();
    }

    @Test
    void handshakeWithDisallowedOriginIsRejected() {
        RecordingHandler handler = new RecordingHandler();

        CompletableFuture<StompSession> future =
                connectAsync(handler, "http://evil.example", "any-test-token");

        await().atMost(6, TimeUnit.SECONDS).until(future::isDone);
        assertThat(future).isCompletedExceptionally();
    }

    @Test
    void connectWithoutBearerTokenIsRejectedAndSessionNotEstablished() {
        RecordingHandler handler = new RecordingHandler();

        CompletableFuture<StompSession> future = connectAsync(handler, ALLOWED_ORIGIN, null);

        await().atMost(6, TimeUnit.SECONDS)
                .until(() -> future.isCompletedExceptionally() || handler.failure.getCount() == 0);
        assertThat(future.isCompletedExceptionally() || handler.failure.getCount() == 0)
                .as("CONNECT without a Bearer token must fail (errors: %s)", handler.errors)
                .isTrue();
    }

    // --- SUBSCRIBE allowlist / SEND denial ---

    @Test
    void subscribeToExactHardwareTopicReceivesHintAfterCommit() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        StompSession session = connect(handler, ALLOWED_ORIGIN, "any-test-token");
        UUID stationId = UUID.randomUUID();

        CapturingFrameHandler subscription = new CapturingFrameHandler();
        session.subscribe("/topic/stations/" + stationId + "/hardware", subscription);

        // The broker processes SUBSCRIBE asynchronously; retry publishing committed hints
        // until the subscription is live (exactly-once delivery is proven separately by
        // HardwareHintTransactionIntegrationTest).
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            publishHintInTransaction(stationId);
            assertThat(subscription.payloads)
                    .as("hint payload over the wire")
                    .anyMatch(payload -> HINT_BODY.equals(new String(payload, StandardCharsets.UTF_8)));
        });

        assertThat(session.isConnected()).isTrue();
        session.disconnect();
    }

    @Test
    void subscribeToWildcardOrPrivateDestinationsIsRejected() throws Exception {
        List<String> deniedDestinations = List.of(
                "/topic/stations/*/hardware",
                "/topic/**",
                "/queue/notifications",
                "/user/queue/receipts",
                "/topic/stations/" + UUID.randomUUID() + "/telemetry"
        );

        for (String destination : deniedDestinations) {
            RecordingHandler handler = new RecordingHandler();
            StompSession session = connect(handler, ALLOWED_ORIGIN, "any-test-token");

            session.subscribe(destination, new CapturingFrameHandler());

            await().atMost(6, TimeUnit.SECONDS)
                    .until(() -> handler.failure.getCount() == 0 || !session.isConnected());
            assertThat(handler.failure.getCount() == 0 || !session.isConnected())
                    .as("destination %s must be rejected (errors: %s)", destination, handler.errors)
                    .isTrue();
            await().atMost(6, TimeUnit.SECONDS).until(() -> !session.isConnected());
        }
    }

    @Test
    void sendFrameIsRejected() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        StompSession session = connect(handler, ALLOWED_ORIGIN, "any-test-token");

        session.send("/topic/stations/" + UUID.randomUUID() + "/hardware", HINT_BODY.getBytes(StandardCharsets.UTF_8));

        await().atMost(6, TimeUnit.SECONDS)
                .until(() -> handler.failure.getCount() == 0 || !session.isConnected());
        assertThat(handler.failure.getCount() == 0)
                .as("SEND must be rejected (errors: %s)", handler.errors)
                .isTrue();
    }

    // --- token expiry / heart-beat ---

    @Test
    void idleSubscribedSessionIsClosedAtTokenExpiry() throws Exception {
        TOKEN_TTL_SECONDS.set(2);
        RecordingHandler handler = new RecordingHandler();
        StompSession session = connect(handler, ALLOWED_ORIGIN, "any-test-token");
        session.subscribe("/topic/stations/" + UUID.randomUUID() + "/hardware",
                new CapturingFrameHandler());
        assertThat(session.isConnected()).isTrue();

        await().atMost(8, TimeUnit.SECONDS)
                .until(() -> !session.isConnected() || handler.failure.getCount() == 0);

        assertThat(!session.isConnected() || handler.failure.getCount() == 0)
                .as("session must be closed at token expiry (errors: %s)", handler.errors)
                .isTrue();
    }

    @Test
    void serverSendsHeartbeatWhileSessionIdle() throws Exception {
        List<String> frames = Collections.synchronizedList(new ArrayList<>());
        TextWebSocketHandler rawHandler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                frames.add(message.getPayload());
            }

            @Override
            protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
                frames.add("<binary>");
            }
        };

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.set(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);
        headers.setSecWebSocketProtocol("v12.stomp");
        CompletableFuture<WebSocketSession> handshake =
                webSocketClient.execute(rawHandler, headers, URI.create(wsUri()));

        WebSocketSession session = handshake.get(5, TimeUnit.SECONDS);
        try {
            assertThat(session.isOpen()).isTrue();

            // Full STOMP negotiation so the broker registers the session with heart-beat
            // intervals: writeInterval = max(client receive, server send) = 10s.
            String nullTerminator = String.valueOf((char) 0);
            String connectFrame = "CONNECT" + "\n"
                    + "accept-version:1.2" + "\n"
                    + "host:localhost" + "\n"
                    + "Authorization:Bearer any-test-token" + "\n"
                    + "heart-beat:10000,10000" + "\n"
                    + "\n" + nullTerminator;
            session.sendMessage(new TextMessage(connectFrame));

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(frames).anyMatch(frame -> frame.startsWith("CONNECTED")));

            // The broker heartbeat task runs on a fixed 10s delay anchored at broker start,
            // and the first heartbeat fires only once (now - lastWrite) > 10s, so the first
            // heartbeat can take up to ~20s after CONNECT (worst-case phase alignment).
            await().atMost(28, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(frames)
                            .as("server heartbeat frame (empty text/binary or LF)")
                            .anyMatch(frame -> frame.isEmpty() || frame.equals("\n")
                                    || frame.equals("<binary>")));
        } finally {
            session.close();
        }
    }

    // --- helpers ---

    private String wsUri() {
        return "ws://localhost:" + environment.getRequiredProperty("local.server.port") + "/ws";
    }

    private StompSession connect(RecordingHandler handler, String origin, String token) throws Exception {
        return connectAsync(handler, origin, token).get(5, TimeUnit.SECONDS);
    }

    private CompletableFuture<StompSession> connectAsync(RecordingHandler handler, String origin, String token) {
        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        WebSocketHttpHeaders httpHeaders = new WebSocketHttpHeaders();
        if (origin != null) {
            httpHeaders.set(HttpHeaders.ORIGIN, origin);
        }
        return stompClient.connectAsync(wsUri(), httpHeaders, connectHeaders, handler);
    }

    private void publishHintInTransaction(UUID stationId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new EquipmentStateChangedEvent(
                        stationId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "AVAILABLE",
                        "IN_USE",
                        EquipmentChangeType.CONNECTOR_RUNTIME,
                        Instant.now()
                )));
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
