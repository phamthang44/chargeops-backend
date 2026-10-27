package com.thang.chargeops.infra.security.abuse;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Runs after STOMP auth/authorization. Heartbeats and cleanup frames spend no quota. */
@Component @RequiredArgsConstructor
public class AbuseStompInterceptor implements ChannelInterceptor {
    private final AbuseRateLimiter limiter;
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && accessor.getCommand() == StompCommand.SUBSCRIBE && accessor.getUser() != null) {
            var decision = limiter.acquire(AbuseGroup.WS_SUBSCRIBE, accessor.getUser().getName(), null);
            if (!decision.allowed() && limiter.mode() == AbuseProperties.Mode.ENFORCE)
                throw new AccessDeniedException(decision.code() + "; retryAfterSeconds=" + decision.retryAfterSeconds());
        }
        return message;
    }
}
