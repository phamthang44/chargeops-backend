package com.thang.chargeops.infra.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks live {@link WebSocketSession}s keyed by session id (the id equals the STOMP session id
 * used in {@code SessionConnectEvent}/{@code SessionDisconnectEvent}), so a session can be closed
 * when its access token expires even though STOMP events do not carry the WebSocket session.
 *
 * <p>Registration is performed by a {@link WebSocketHandlerDecoratorFactory} installed through
 * {@code configureWebSocketTransport}, which wraps the handler before any handshake succeeds —
 * no extra REST endpoint or thread-local bridging is involved.
 */
@Component
@Slf4j
public class WebSocketSessionRegistry {

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public WebSocketHandlerDecoratorFactory decoratorFactory() {
        return this::decorate;
    }

    private WebSocketHandler decorate(WebSocketHandler handler) {
        return new TrackingHandlerDecorator(handler);
    }

    /**
     * Closes the session at token expiry. Failures are logged and swallowed: the client is
     * expected to reconnect with a fresh token regardless, and the SUBSCRIBE-side expiry check
     * in {@link StompAuthChannelInterceptor} already blocks new subscriptions.
     */
    public void closeSession(String sessionId, String reason) {
        WebSocketSession session = sessions.remove(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(CloseStatus.POLICY_VIOLATION.withReason(reason));
        } catch (Exception ex) {
            log.warn("Failed to close WebSocket session {} at token expiry: {}", sessionId, ex.toString());
        }
    }

    public WebSocketSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public int size() {
        return sessions.size();
    }

    private final class TrackingHandlerDecorator extends WebSocketHandlerDecorator {

        private TrackingHandlerDecorator(WebSocketHandler delegate) {
            super(delegate);
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            sessions.put(session.getId(), session);
            super.afterConnectionEstablished(session);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
            sessions.remove(session.getId());
            super.afterConnectionClosed(session, closeStatus);
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
            sessions.remove(session.getId());
            super.handleTransportError(session, exception);
        }
    }
}
