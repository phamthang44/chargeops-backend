package com.thang.chargeops.infra.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StompSessionExpiryGuardTest {

    private static final String SESSION_ID = "session-1";

    @Mock
    private WebSocketSessionRegistry sessionRegistry;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private ScheduledFuture<?> closeTask;

    private StompSessionExpiryGuard guard;

    @BeforeEach
    void setUp() {
        guard = new StompSessionExpiryGuard(sessionRegistry, taskScheduler);
    }

    @Test
    void schedulesCloseAtTokenExpiryAndClosesSessionWhenItFires() {
        long expiresAtEpochMs = System.currentTimeMillis() + 600_000L;
        doReturn(closeTask).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        guard.onSessionConnect(connectEvent(expiresAtEpochMs));

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> triggerCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(taskCaptor.capture(), triggerCaptor.capture());
        assertThat(triggerCaptor.getValue()).isEqualTo(Instant.ofEpochMilli(expiresAtEpochMs));

        taskCaptor.getValue().run();

        verify(sessionRegistry).closeSession(eq(SESSION_ID), anyString());
    }

    @Test
    void cancelledCloseTaskIsNotCancelledTwiceAndSessionNotClosedOnDisconnectBeforeExpiry() {
        doReturn(closeTask).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        guard.onSessionConnect(connectEvent(System.currentTimeMillis() + 600_000L));
        guard.onSessionDisconnect(disconnectEvent());

        verify(closeTask).cancel(false);
        verify(sessionRegistry, never()).closeSession(anyString(), anyString());
    }

    @Test
    void expiryTaskIsReplacedWhenConnectEventArrivesAgain() {
        ScheduledFuture<?> secondTask = mock(ScheduledFuture.class);
        doReturn(closeTask).doReturn(secondTask)
                .when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        guard.onSessionConnect(connectEvent(System.currentTimeMillis() + 600_000L));
        guard.onSessionConnect(connectEvent(System.currentTimeMillis() + 900_000L));

        verify(closeTask).cancel(false);
        verify(secondTask, never()).cancel(anyBoolean());
    }

    @Test
    void connectWithoutExpiryAttributeSchedulesNothing() {
        guard.onSessionConnect(connectEvent(null));

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    // --- helpers ---

    private static SessionConnectEvent connectEvent(Long expiresAtEpochMs) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.setSessionId(SESSION_ID);
        HashMap<String, Object> attributes = new HashMap<>();
        if (expiresAtEpochMs != null) {
            attributes.put(StompAuthChannelInterceptor.SESSION_ATTR_TOKEN_EXPIRES_AT, expiresAtEpochMs);
        }
        accessor.setSessionAttributes(attributes);
        Message<byte[]> message = MessageBuilder.createMessage(
                new byte[0], accessor.getMessageHeaders());
        return new SessionConnectEvent(new Object(), message);
    }

    private static SessionDisconnectEvent disconnectEvent() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setLeaveMutable(true);
        accessor.setSessionId(SESSION_ID);
        Message<byte[]> message = MessageBuilder.createMessage(
                new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(
                new Object(), message, SESSION_ID, new org.springframework.web.socket.CloseStatus(1000));
    }
}
