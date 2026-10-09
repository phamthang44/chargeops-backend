package com.thang.chargeops.infra.websocket;

import com.thang.chargeops.infra.security.JwtRoleConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fail-closed authorization for every inbound STOMP frame.
 *
 * <p>Policy (Hardware MVP, see WebSocket plan #02 §6):
 * <ul>
 *   <li>{@code CONNECT} — one {@code Bearer} JWT, decoded with the shared {@link JwtDecoder}
 *       and mapped through {@link JwtRoleConverter}; missing/invalid/expired token rejects the
 *       frame, which closes the session with a STOMP {@code ERROR}. No anonymous fallback.
 *       The token {@code exp} is copied into the WebSocket session attributes so the session
 *       can be closed exactly at expiry and {@code SUBSCRIBE} can be re-checked.</li>
 *   <li>{@code SUBSCRIBE} — authenticated session, token still valid, and the destination must
 *       match exactly {@code /topic/stations/<uuid>/hardware} or the private hint queue
 *       {@code /user/queue/notifications}. Wildcards ({@code *}, {@code **}), extra segments,
 *       {@code /queue/**}, every other {@code /user/queue/*} and every other topic are denied.
 *       Per-user isolation is enforced by Spring's user-destination resolution (session
 *       principal), not by the pattern: a subscriber only ever resolves to its own queue.</li>
 *   <li>{@code SEND} — denied for all destinations: clients must never fake a hint.</li>
 *   <li>{@code UNSUBSCRIBE}/{@code DISCONNECT} — allowed session lifecycle.</li>
 *   <li>Heart-beat / transport frames carry no {@link StompCommand} and pass through untouched
 *       (they must never be mistaken for {@code SEND}).</li>
 *   <li>Any other frame — denied (fail closed; e.g. transactions, ACK/NACK are not used).</li>
 * </ul>
 *
 * <p>This interceptor runs on the {@code clientInboundChannel} <em>before</em>
 * {@code ImmutableMessageChannelInterceptor}, so mutating the accessor here is supported —
 * the same mechanism as Spring's documented token-auth-at-CONNECT example.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    /** WebSocket session attribute key holding the decoded token expiry (epoch millis). */
    public static final String SESSION_ATTR_TOKEN_EXPIRES_AT = "chargeops.stomp.tokenExpiresAt";

    /**
     * Exact-match destination of the hardware hint topic. Anchored and UUID-constrained on
     * purpose: no Ant-style {@code *} / {@code **} and no extra segments can slip through.
     */
    private static final Pattern HARDWARE_TOPIC = Pattern.compile(
            "^/topic/stations/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/hardware$"
    );

    /**
     * Exact-match destination of the private notification hint queue (BKG-071 plan #69 §3).
     * The frame carries the raw {@code /user/queue/notifications} before
     * {@code UserDestinationMessageHandler} resolves it against the session principal, so
     * anchoring here allows the intent without exposing any other user queue.
     */
    private static final Pattern NOTIFICATION_USER_QUEUE = Pattern.compile("^/user/queue/notifications$");

    /**
     * Exact-match destination of the ticket messages topic (/topic/tickets/{ticketId}/messages).
     */
    private static final Pattern TICKET_MESSAGES_TOPIC = Pattern.compile(
            "^/topic/tickets/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/messages$"
    );

    private final JwtDecoder jwtDecoder;
    private final JwtRoleConverter jwtRoleConverter;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == null) {
            // Heart-beat or transport-level message: not a STOMP frame, never treated as SEND.
            return message;
        }
        switch (command) {
            case CONNECT -> authenticate(accessor);
            case SUBSCRIBE -> authorizeSubscribe(accessor);
            case SEND -> throw new AccessDeniedException("STOMP SEND is not permitted");
            case UNSUBSCRIBE, DISCONNECT -> { /* session lifecycle of a connected session */ }
            default -> throw new AccessDeniedException("STOMP frame not permitted: " + command);
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new AccessDeniedException("STOMP CONNECT requires a Bearer access token");
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(authHeader.substring("Bearer ".length()));
        } catch (JwtException | IllegalArgumentException ex) {
            // Never echo the token back in the STOMP ERROR frame.
            throw new AccessDeniedException("STOMP CONNECT token rejected");
        }
        accessor.setUser(new JwtAuthenticationToken(jwt, jwtRoleConverter.convert(jwt)));
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt != null) {
            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
            if (sessionAttributes != null) {
                sessionAttributes.put(SESSION_ATTR_TOKEN_EXPIRES_AT, expiresAt.toEpochMilli());
            }
        }
        log.debug("STOMP CONNECT authenticated for subject={}", jwt.getSubject());
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            throw new AccessDeniedException("STOMP SUBSCRIBE requires an authenticated session");
        }
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null
                && sessionAttributes.get(SESSION_ATTR_TOKEN_EXPIRES_AT) instanceof Long expiresAtEpochMs
                && expiresAtEpochMs <= System.currentTimeMillis()) {
            throw new AccessDeniedException("Access token expired; reconnect with a fresh token");
        }
        String destination = accessor.getDestination();
        if (destination == null || !(HARDWARE_TOPIC.matcher(destination).matches()
                || NOTIFICATION_USER_QUEUE.matcher(destination).matches()
                || TICKET_MESSAGES_TOPIC.matcher(destination).matches())) {
            log.warn("STOMP SUBSCRIBE denied for destination: {}", destination);
            throw new AccessDeniedException("Subscription destination is not allowed");
        }
        log.info("STOMP SUBSCRIBE allowed for destination: {}", destination);
    }
}
