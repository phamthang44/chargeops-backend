package com.thang.chargeops.infra.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    @DisplayName("CorsConfigurationSource should return restricted allowed headers and explicit exposed headers without wildcard allowed headers")
    void corsConfigurationRestrictsHeaders() {
        CorsConfig corsConfig = new CorsConfig();
        ReflectionTestUtils.setField(corsConfig, "allowedOrigins", List.of("http://localhost:3000"));

        CorsConfigurationSource source = corsConfig.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/stations");
        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
        assertThat(config.getAllowedOrigins()).containsExactly("http://localhost:3000");
        assertThat(config.getAllowedHeaders())
                .contains("Authorization", "Content-Type", "Accept", "Idempotency-Key", "Client-Message-Id", "X-Requested-With")
                .doesNotContain("*");
        assertThat(config.getExposedHeaders())
                .contains("Content-Disposition", "Idempotency-Key", "Client-Message-Id");
    }
}
