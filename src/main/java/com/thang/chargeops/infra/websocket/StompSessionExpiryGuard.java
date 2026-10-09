package com.thang.chargeops.infra.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Closes a connected WebSocket session exactly at the access-token {@code exp} recorded by
 * {@link StompAuthChannelInterceptor} during {@code CONNECT}, and cancels the pending close when
 * the session disconnects first.
 *
 * <p>The token is decoded once at {@code CONNECT} and never re-decoded during the session;
 * this scheduled close plus the {@code SUBSCRIBE} re-check are the only expiry enforcement.
 * A new connection with a fresh token is a brand new session, so token rotation is simply
 * "reconnect before expiry" on the client.
 */
@Component
@Slf4j
public class StompSessionExpiryGuard {

    private final WebSocketSessionRegistry sessionRegistry;
    private final TaskScheduler taskScheduler;

    private final ConcurrentHashMap<String, ScheduledFuture<?>> closeTasks = new ConcurrentHashMap<>();

    public StompSessionExpiryGuard(
            WebSocketSessionRegistry sessionRegistry,
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler taskScheduler
    ) {
        this.sessionRegistry = sessionRegistry;
        this.taskScheduler = taskScheduler;
    }

    @EventListener
    public void onSessionConnect(SessionConnectEvent event) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null
                || !(sessionAttributes.get(StompAuthChannelInterceptor.SESSION_ATTR_TOKEN_EXPIRES_AT)
                        instanceof Long expiresAtEpochMs)) {
            return;
        }
        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            return;
        }
        cancel(sessionId);
        ScheduledFuture<?> task = taskScheduler.schedule(
                () -> closeAtExpiry(sessionId, expiresAtEpochMs),
                Instant.ofEpochMilli(expiresAtEpochMs)
        );
        closeTasks.put(sessionId, task);
        log.debug("Scheduled WebSocket close for session {} at {}", sessionId, Instant.ofEpochMilli(expiresAtEpochMs));
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        cancel(event.getSessionId());
    }

    private void closeAtExpiry(String sessionId, long expiresAtEpochMs) {
        // Remove without cancel: this is the running task itself.
        closeTasks.remove(sessionId);
        log.debug("Closing WebSocket session {} — access token expired at {}", sessionId,
                Instant.ofEpochMilli(expiresAtEpochMs));
        sessionRegistry.closeSession(sessionId, "access token expired");
    }

    private void cancel(String sessionId) {
        ScheduledFuture<?> previous = closeTasks.remove(sessionId);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
    }
}
