package com.thang.chargeops.infra.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * STOMP over raw WebSocket (no SockJS) for the Hardware Path MVP.
 *
 * <p>Configuration contract (WebSocket plan #02 §5/§6):
 * <ul>
 *   <li>Raw endpoint {@code GET /ws} — handshake is permitAll in the HTTP filter chain;
 *       authentication happens at the STOMP {@code CONNECT} frame via
 *       {@link StompAuthChannelInterceptor}.</li>
 *   <li>Simple in-memory broker on {@code /topic} and {@code /queue} with explicit
 *       {@code {10000,10000}} heart-beats. No {@code SEND} handler and no application
 *       destination prefix — clients are publish-denied by the interceptor.</li>
 *   <li>The broker's shared {@code messageBrokerTaskScheduler} is injected lazily to avoid a
 *       bean-creation cycle inside the broker configuration (the scheduler bean is declared by
 *       the same infrastructure config that invokes this configurer).</li>
 *   <li>Handshake origins reuse {@code app.cors.allowed-origins}, normalized to scheme/host/port
 *       (the current config contains one URL with a trailing slash which would never match an
 *       {@code Origin} header); {@code ws(s)} scheme is rewritten to {@code http(s)}; {@code *} is
 *       rejected. Native clients that send no {@code Origin} are accepted — Origin is not an
 *       authentication mechanism.</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
@Slf4j
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.thang.chargeops.infra.security.abuse.AbuseStompInterceptor abuseInterceptor;

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final WebSocketSessionRegistry sessionRegistry;
    private final List<String> allowedOrigins;
    /**
     * The broker infrastructure's shared scheduler, injected lazily so this configurer's
     * creation stays independent of the very configuration that invokes it (no bean cycle).
     * The proxy resolves on first use: broker heart-beat start / scheduled session close.
     */
    private final TaskScheduler brokerTaskScheduler;

    public WebSocketConfig(
            StompAuthChannelInterceptor stompAuthChannelInterceptor,
            WebSocketSessionRegistry sessionRegistry,
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler brokerTaskScheduler,
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins
    ) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
        this.sessionRegistry = sessionRegistry;
        this.brokerTaskScheduler = brokerTaskScheduler;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(org.springframework.web.socket.config.annotation.StompEndpointRegistry registry) {
        String[] origins = normalizedOrigins();
        log.info("WebSocket endpoint /ws registered, allowed origin patterns: {}", Arrays.toString(origins));
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins);
        // No .withSockJS() on purpose: STOMP 1.2 over raw WebSocket only.
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10_000L, 10_000L})
                .setTaskScheduler(brokerTaskScheduler);
        // No application destination prefix and no SEND mapping: inbound SEND is denied
        // by StompAuthChannelInterceptor.
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
        if (abuseInterceptor != null) registration.interceptors(abuseInterceptor);
    }

    @Override
    public void configureWebSocketTransport(
            org.springframework.web.socket.config.annotation.WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(sessionRegistry.decoratorFactory());
        // Bounded send: a stalled client must not block a thread indefinitely (plan §5 B9 note).
        registration.setSendTimeLimit(10_000);
        registration.setSendBufferSizeLimit(64 * 1024);
    }

    /**
     * Normalizes {@code app.cors.allowed-origins} for the handshake {@code Origin} check:
     * trim, drop trailing slashes (the current prod entry ends with {@code /}), rewrite
     * {@code ws(s)} to {@code http(s)}, reject {@code *}. Fails fast when nothing valid remains —
     * an open wildcard origin is never an acceptable fallback.
     */
    private String[] normalizedOrigins() {
        List<String> normalized = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(origin -> origin.replaceAll("/+$", ""))
                .map(WebSocketConfig::toHttpScheme)
                .filter(origin -> !"*".equals(origin) && !"*/*".equals(origin))
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalStateException(
                    "app.cors.allowed-origins must contain at least one concrete http(s) origin for the WebSocket handshake");
        }
        return normalized.toArray(String[]::new);
    }

    private static String toHttpScheme(String origin) {
        if (origin.startsWith("ws://")) {
            return "http://" + origin.substring("ws://".length());
        }
        if (origin.startsWith("wss://")) {
            return "https://" + origin.substring("wss://".length());
        }
        return origin;
    }
}
