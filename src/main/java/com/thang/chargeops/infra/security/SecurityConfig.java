package com.thang.chargeops.infra.security;

import com.thang.chargeops.common.constant.SecurityConstants;
import com.thang.chargeops.infra.persistence.CurrentActorContext;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration. The backend is a stateless OAuth2 resource server:
 * it only verifies Keycloak-issued JWTs, it never logs users in.
 *
 * <p>Token extraction/validation is handled by Spring Security's built-in
 * {@code BearerTokenAuthenticationFilter} (installed by {@code oauth2ResourceServer}).
 * We only customise role mapping ({@link JwtRoleConverter}) and the JSON error
 * responses ({@link RestAuthenticationEntryPoint}, {@link RestAccessDeniedHandler}).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtRoleConverter jwtRoleConverter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final UserProfileRepository userProfileRepository;
    private final CurrentActorContext currentActorContext;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SecurityConstants.publicEndpoints()).permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/stations",
                                "/api/v1/stations/*"
                        ).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .addFilterAfter(
                        new CurrentActorFilter(userProfileRepository, currentActorContext),
                        BearerTokenAuthenticationFilter.class
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * Lazy JWT decoder: it resolves the issuer's keys on first token validation
     * rather than at startup, so the backend can boot before Keycloak is reachable.
     */
    @Bean
    @Profile("!test")
    public JwtDecoder jwtDecoder() {
        String jwkSetUri = issuerUri.replaceAll("/+$", "") + "/protocol/openid-connect/certs";
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtClaimValidator<String>("iss", iss -> iss != null && (iss.equals(issuerUri) || iss.contains("/realms/chargeops")))
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwtRoleConverter);
        return converter;
    }
}
