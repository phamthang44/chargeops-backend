package com.thang.chargeops.infra.security;

import com.thang.chargeops.infra.persistence.CurrentActorContext;
import com.thang.chargeops.location.controller.AdministrativeLocationController;
import com.thang.chargeops.location.service.AdministrativeLocationService;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@WebMvcTest(AdministrativeLocationController.class)
@ActiveProfiles("test")
@Import({
        SecurityConfig.class,
        JwtRoleConverter.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class
})
class SecurityConfigHeaderTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdministrativeLocationService administrativeLocationService;

    @MockitoBean
    private UserProfileRepository userProfileRepository;

    @MockitoBean
    private CurrentActorContext currentActorContext;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("Should include Content-Security-Policy, X-Frame-Options, and Referrer-Policy headers on responses")
    void responseContainsSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/administrative-units/provinces"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; form-action 'self'"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }
}
