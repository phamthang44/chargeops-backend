package com.thang.chargeops.common.constant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConstantsTest {

    @Test
    void publicEndpointsAllowHealthDocsErrorAndWebhook() {
        assertThat(SecurityConstants.isPublicRequest("GET", "/api/health")).isTrue();
        assertThat(SecurityConstants.isPublicRequest("GET", "/v3/api-docs/openapi.json")).isTrue();
        assertThat(SecurityConstants.isPublicRequest("GET", "/swagger-ui/index.html")).isTrue();
        assertThat(SecurityConstants.isPublicRequest("GET", "/error")).isTrue();
        assertThat(SecurityConstants.isPublicRequest("GET", "/api/v1/administrative-units/provinces")).isTrue();
        assertThat(SecurityConstants.isPublicRequest("POST", "/api/v1/webhooks/sepay")).isTrue();
    }

    @Test
    void publicEndpointsAllowGetStationsOnly() {
        assertThat(SecurityConstants.isPublicRequest("GET", "/api/v1/stations")).isTrue();
        assertThat(SecurityConstants.isPublicRequest("GET", "/api/v1/stations/" + java.util.UUID.randomUUID())).isTrue();
        assertThat(SecurityConstants.isPublicRequest("POST", "/api/v1/stations")).isFalse();
        assertThat(SecurityConstants.isPublicRequest("PATCH", "/api/v1/stations/x")).isFalse();
    }

    @Test
    void adminAndAuthenticatedRoutesAreNotPublic() {
        assertThat(SecurityConstants.isPublicRequest("GET", "/api/v1/admin/users")).isFalse();
        assertThat(SecurityConstants.isPublicRequest("PATCH", "/api/v1/admin/users/x/status")).isFalse();
        assertThat(SecurityConstants.isPublicRequest("GET", "/api/v1/me/profile")).isFalse();
        assertThat(SecurityConstants.isPublicRequest("POST", "/api/v1/bookings")).isFalse();
    }

    @Test
    void nullInputsAreNotPublic() {
        assertThat(SecurityConstants.isPublicRequest(null, "/api/v1/stations")).isFalse();
        assertThat(SecurityConstants.isPublicRequest("GET", null)).isFalse();
    }
}
