package com.thang.chargeops.infra.mcp;

import com.thang.chargeops.infra.persistence.CurrentActorContext;
import com.thang.chargeops.infra.security.CurrentActorFilter;
import com.thang.chargeops.infra.security.JwtRoleConverter;
import com.thang.chargeops.infra.security.RestAccessDeniedHandler;
import com.thang.chargeops.infra.security.abuse.AbuseRateLimiter;
import com.thang.chargeops.infra.security.abuse.ClientIpResolver;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
public class McpSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain mcpEndpointSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            McpProperties mcpProperties,
            McpTokenRequirements tokenRequirements,
            JwtRoleConverter roleConverter,
            RestAccessDeniedHandler accessDeniedHandler,
            UserProfileRepository userProfileRepository,
            CurrentActorContext currentActorContext,
            ObjectMapper objectMapper,
            AbuseRateLimiter limiter,
            ClientIpResolver ipResolver) throws Exception {

        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(roleConverter);
        JwtDecoder mcpJwtDecoder = token -> {
            Jwt decoded = jwtDecoder.decode(token);
            return tokenRequirements.validate(decoded);
        };

        http.securityMatcher("/mcp")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("OWNER"))
                .oauth2ResourceServer(oauth -> oauth
                        .authenticationEntryPoint(new McpOAuthChallengeEntryPoint(mcpProperties, objectMapper))
                        .jwt(jwt -> jwt.decoder(mcpJwtDecoder)
                                .jwtAuthenticationConverter(authenticationConverter)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new McpOAuthChallengeEntryPoint(mcpProperties, objectMapper))
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(new McpOriginHostGuardFilter(mcpProperties, objectMapper),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new CurrentActorFilter(userProfileRepository, currentActorContext, objectMapper),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new McpRateLimitFilter(limiter, ipResolver, objectMapper), CurrentActorFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain mcpMetadataSecurityFilterChain(HttpSecurity http) throws Exception {
        RequestMatcher metadataRequests = request -> HttpMethod.GET.matches(request.getMethod())
                && ("/.well-known/oauth-protected-resource".equals(path(request))
                || "/.well-known/oauth-protected-resource/mcp".equals(path(request)));
        http.securityMatcher(metadataRequests)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
