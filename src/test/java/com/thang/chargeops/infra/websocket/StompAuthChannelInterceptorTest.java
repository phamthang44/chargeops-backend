package com.thang.chargeops.infra.websocket;

import com.thang.chargeops.infra.security.JwtRoleConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    private static final String VALID_TOKEN = "valid-token";
    private static final UUID STATION_ID = UUID.randomUUID();

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private MessageChannel channel;

    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(jwtDecoder, new JwtRoleConverter());
    }

    // --- CONNECT ---

    @Test
    void connectWithValidBearerTokenSetsPrincipalAndStoresExpiry() {
        Instant expiresAt = Instant.now().plusSeconds(600);
        Jwt jwt = jwt(expiresAt);
        when(jwtDecoder.decode(VALID_TOKEN)).thenReturn(jwt);

        StompHeaderAccessor accessor = accessor(StompCommand.CONNECT, a -> {
            a.setNativeHeader("Authorization", "Bearer " + VALID_TOKEN);
            a.setSessionAttributes(new HashMap<>());
        });

        interceptor.preSend(message(accessor), channel);

        assertThat(accessor.getUser()).isInstanceOf(JwtAuthenticationToken.class);
        assertThat(accessor.getUser().getName()).isEqualTo("user-1");
        assertThat(((JwtAuthenticationToken) accessor.getUser()).getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_OWNER", "ROLE_DRIVER");
        assertThat(accessor.getSessionAttributes())
                .containsEntry(StompAuthChannelInterceptor.SESSION_ATTR_TOKEN_EXPIRES_AT,
                        expiresAt.toEpochMilli());
    }

    @Test
    void connectWithoutAuthorizationHeaderIsRejected() {
        StompHeaderAccessor accessor = accessor(StompCommand.CONNECT,
                a -> a.setSessionAttributes(new HashMap<>()));

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Bearer");
        assertThat(accessor.getUser()).isNull();
    }

    @Test
    void connectWithNonBearerSchemeIsRejected() {
        StompHeaderAccessor accessor = accessor(StompCommand.CONNECT, a -> {
            a.setNativeHeader("Authorization", "Basic dXNlcjpwYXNz");
            a.setSessionAttributes(new HashMap<>());
        });

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void connectWithInvalidTokenIsRejectedWithoutEchoingTheToken() {
        when(jwtDecoder.decode("garbage")).thenThrow(new JwtException("malformed"));

        StompHeaderAccessor accessor = accessor(StompCommand.CONNECT, a -> {
            a.setNativeHeader("Authorization", "Bearer garbage");
            a.setSessionAttributes(new HashMap<>());
        });

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
                .isInstanceOf(AccessDeniedException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("garbage"));
        assertThat(accessor.getUser()).isNull();
    }

    // --- SUBSCRIBE ---

    @Test
    void subscribeToExactHardwareTopicIsAllowed() {
        StompHeaderAccessor accessor = subscribedAccessor(
                "/topic/stations/" + STATION_ID + "/hardware",
                Instant.now().plusSeconds(600)
        );

        Message<?> result = interceptor.preSend(message(accessor), channel);

        assertThat(result).isNotNull();
    }

    @Test
    void subscribeToExactNotificationUserQueueIsAllowed() {
        StompHeaderAccessor accessor = subscribedAccessor(
                "/user/queue/notifications",
                Instant.now().plusSeconds(600)
        );

        Message<?> result = interceptor.preSend(message(accessor), channel);

        assertThat(result).isNotNull();
    }

    @Test
    void subscribeAfterTokenExpiryIsRejected() {
        StompHeaderAccessor accessor = subscribedAccessor(
                "/topic/stations/" + STATION_ID + "/hardware",
                Instant.now().minusSeconds(5)
        );

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void subscribeWithoutAuthenticationIsRejected() {
        StompHeaderAccessor accessor = accessor(StompCommand.SUBSCRIBE, a -> {
            a.setDestination("/topic/stations/" + STATION_ID + "/hardware");
            a.setSessionAttributes(new HashMap<>());
        });

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void subscribeToNonAllowlistedDestinationsIsRejected() {
        List<String> deniedDestinations = List.of(
                "/topic/stations/*/hardware",
                "/topic/**",
                "/topic/stations/" + STATION_ID + "/hardware/extra",
                "/topic/stations/not-a-uuid/hardware",
                "/topic/stations/" + STATION_ID + "/telemetry",
                "/queue/notifications",
                "/user/queue/receipts",
                "/topic/bookings/" + STATION_ID
        );

        for (String destination : deniedDestinations) {
            StompHeaderAccessor accessor = subscribedAccessor(destination, Instant.now().plusSeconds(600));
            assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
                    .as("destination %s must be denied", destination)
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void subscribeToOtherUserQueuesAndResolvedQueueFormsIsRejected() {
        // Only the exact raw intent is allowlisted: other user queues, wildcard forms, case
        // variants and the already-resolved broker queue must all stay fail-closed (plan #69 §3).
        List<String> deniedDestinations = List.of(
                "/user/queue/bookings",
                "/user/queue/notifications/extra",
                "/user/queue/notifications-user_test",
                "/queue/notifications-user_test",
                "/user/queue/*",
                "/user/queue/**",
                "/user/queue/Notifications",
                "/user/queue/ notifications",
                "/notifications"
        );

        for (String destination : deniedDestinations) {
            StompHeaderAccessor accessor = subscribedAccessor(destination, Instant.now().plusSeconds(600));
            assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
                    .as("destination %s must be denied", destination)
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    // --- SEND / lifecycle / heart-beat / other frames ---

    @Test
    void sendIsAlwaysDeniedEvenForAllowedTopic() {
        StompHeaderAccessor accessor = accessor(StompCommand.SEND, a -> {
            a.setDestination("/topic/stations/" + STATION_ID + "/hardware");
            a.setUser(() -> "user-1");
            a.setSessionAttributes(new HashMap<>());
        });

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SEND");
    }

    @Test
    void unsubscribeAndDisconnectLifecycleFramesPass() {
        for (StompCommand command : List.of(StompCommand.UNSUBSCRIBE, StompCommand.DISCONNECT)) {
            StompHeaderAccessor accessor = accessor(command, a -> {
                a.setUser(() -> "user-1");
                a.setSessionAttributes(new HashMap<>());
            });
            assertThat(interceptor.preSend(message(accessor), channel))
                    .as("frame %s must pass", command)
                    .isNotNull();
        }
    }

    @Test
    void heartbeatWithoutStompCommandIsNotTreatedAsSend() {
        StompHeaderAccessor accessor = StompHeaderAccessor.createForHeartbeat();
        accessor.setLeaveMutable(true);
        accessor.setSessionAttributes(new HashMap<>());

        Message<?> result = interceptor.preSend(message(accessor), channel);

        assertThat(result).isNotNull();
    }

    @Test
    void unsupportedFramesAreDenied() {
        StompHeaderAccessor accessor = accessor(StompCommand.ACK, a -> {
            a.setUser(() -> "user-1");
            a.setSessionAttributes(new HashMap<>());
        });

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    // --- helpers ---

    private static Jwt jwt(Instant expiresAt) {
        return Jwt.withTokenValue(VALID_TOKEN)
                .header("alg", "none")
                .subject("user-1")
                .claim("realm_access", Map.of("roles", List.of("Owner", "driver")))
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(expiresAt)
                .build();
    }

    private static StompHeaderAccessor accessor(StompCommand command, Consumer<StompHeaderAccessor> customizer) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        // Keep the accessor attached to the message headers so the interceptor's
        // MessageHeaderAccessor.getAccessor(...) finds it, exactly as the inbound
        // pipeline does before ImmutableMessageChannelInterceptor freezes the headers.
        accessor.setLeaveMutable(true);
        customizer.accept(accessor);
        return accessor;
    }

    private StompHeaderAccessor subscribedAccessor(String destination, Instant expiresAt) {
        return accessor(StompCommand.SUBSCRIBE, a -> {
            a.setDestination(destination);
            a.setUser(() -> "user-1");
            Map<String, Object> attributes = new HashMap<>();
            attributes.put(StompAuthChannelInterceptor.SESSION_ATTR_TOKEN_EXPIRES_AT,
                    expiresAt.toEpochMilli());
            a.setSessionAttributes(attributes);
        });
    }

    private static Message<?> message(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
