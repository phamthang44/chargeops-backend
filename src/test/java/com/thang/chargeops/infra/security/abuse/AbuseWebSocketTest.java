package com.thang.chargeops.infra.security.abuse;

import com.thang.chargeops.infra.websocket.WebSocketSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.CloseStatus;
import org.springframework.security.access.AccessDeniedException;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AbuseWebSocketTest {
    @Test void atomicCapacityCleanupAndNoResurrectedSlots() throws Exception {
        var registry = new WebSocketSessionRegistry(); var limiter = mock(AbuseRateLimiter.class);
        when(limiter.mode()).thenReturn(AbuseProperties.Mode.ENFORCE);
        ReflectionTestUtils.setField(registry, "abuseLimiter", limiter);
        var handler = registry.decoratorFactory().decorate(mock(WebSocketHandler.class));
        var sessions = new ArrayList<WebSocketSession>();
        for (int i=0; i<20; i++) {
            var session = mock(WebSocketSession.class); when(session.getId()).thenReturn("s" + i);
            handler.afterConnectionEstablished(session); sessions.add(session);
        }
        // Late auth callbacks from closed transports must not reserve account slots.
        for (int i=0; i<10; i++) {
            handler.afterConnectionClosed(sessions.get(i), CloseStatus.NORMAL);
            registry.bindSubject("s" + i, "actor");
        }
        var accepted = new CopyOnWriteArrayList<Integer>();
        try (var executor = Executors.newFixedThreadPool(10)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i=10; i<20; i++) {
                final int index=i;
                tasks.add(executor.submit(() -> {
                    try { registry.bindSubject("s" + index, "actor"); accepted.add(index); }
                    catch (AccessDeniedException expected) { /* capacity */ }
                }));
            }
            for (var task : tasks) task.get();
        }
        assertThat(accepted).hasSize(5);
        int released=accepted.getFirst();
        handler.afterConnectionClosed(sessions.get(released), CloseStatus.NORMAL);
        int rejected=java.util.stream.IntStream.range(10,20).filter(i -> !accepted.contains(i)).findFirst().orElseThrow();
        assertThatCode(() -> registry.bindSubject("s" + rejected, "actor")).doesNotThrowAnyException();
        assertThatThrownBy(() -> registry.bindSubject("s" + rejected, "another-actor")).isInstanceOf(AccessDeniedException.class);
    }
}
