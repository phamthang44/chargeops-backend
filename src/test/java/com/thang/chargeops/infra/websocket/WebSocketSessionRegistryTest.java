package com.thang.chargeops.infra.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketSessionRegistryTest {

    private WebSocketSessionRegistry registry;
    private WebSocketHandler delegate;
    private WebSocketHandler decorated;
    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        registry = new WebSocketSessionRegistry();
        WebSocketHandlerDecoratorFactory factory = registry.decoratorFactory();
        delegate = mock(WebSocketHandler.class);
        decorated = factory.decorate(delegate);
        session = mock(WebSocketSession.class);
    }

    @Test
    void tracksSessionAcrossLifecycle() throws Exception {
        connect();

        assertThat(registry.getSession("session-1")).isSameAs(session);
        assertThat(registry.size()).isEqualTo(1);

        decorated.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(registry.getSession("session-1")).isNull();
        assertThat(registry.size()).isZero();
        verify(delegate).afterConnectionClosed(session, CloseStatus.NORMAL);
    }

    @Test
    void transportErrorUnregistersSession() throws Exception {
        connect();

        decorated.handleTransportError(session, new IllegalStateException("boom"));

        assertThat(registry.getSession("session-1")).isNull();
    }

    @Test
    void closeSessionClosesOpenSessionAndClearsRegistry() throws Exception {
        connect();
        when(session.isOpen()).thenReturn(true);

        registry.closeSession("session-1", "access token expired");

        verify(session).close(any(CloseStatus.class));
        assertThat(registry.getSession("session-1")).isNull();
    }

    @Test
    void closeSessionToleratesUnknownSession() {
        registry.closeSession("missing", "access token expired");

        assertThat(registry.size()).isZero();
    }

    @Test
    void closeSessionSwallowsCloseFailureAndClearsRegistry() throws Exception {
        connect();
        when(session.isOpen()).thenReturn(true);
        doThrow(new IOException("already closed")).when(session).close(any(CloseStatus.class));

        registry.closeSession("session-1", "access token expired");

        assertThat(registry.getSession("session-1")).isNull();
    }

    @Test
    void decoratorDelegatesToUnderlyingHandler() throws Exception {
        assertThat(decorated).isInstanceOf(WebSocketHandlerDecorator.class);
        assertThat(((WebSocketHandlerDecorator) decorated).getDelegate()).isSameAs(delegate);

        connect();

        verify(delegate).afterConnectionEstablished(session);
        verify(delegate, never()).afterConnectionClosed(any(), any());
    }

    private void connect() throws Exception {
        when(session.getId()).thenReturn("session-1");
        decorated.afterConnectionEstablished(session);
    }
}
