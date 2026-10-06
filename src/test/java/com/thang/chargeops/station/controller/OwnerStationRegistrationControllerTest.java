package com.thang.chargeops.station.controller;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.station.service.usecase.OwnerStationRegistrationUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OwnerStationRegistrationController.class)
@Import({GlobalHandlerError.class, OwnerStationRegistrationControllerTest.Security.class})
class OwnerStationRegistrationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OwnerStationRegistrationUseCase useCase;
    private final UUID id = UUID.randomUUID();
    private final String route = "/api/v1/owner/stations/{id}/registration";

    @TestConfiguration
    @EnableMethodSecurity
    static class Security {
        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable).authorizeHttpRequests(a -> a.anyRequest().authenticated()).build();
        }
    }

    @Test @WithMockUser(roles = "STAFF")
    void staffCannotReadOrWithdrawOwnerRegistration() throws Exception {
        mvc.perform(get(route, id)).andExpect(status().isForbidden());
        mvc.perform(delete(route, id).param("version", "3")).andExpect(status().isForbidden());
        verifyNoInteractions(useCase);
    }

    @Test @WithMockUser(roles = "OWNER")
    void invalidPhoneProducesFieldErrorAndNoUpdate() throws Exception {
        mvc.perform(put(route, id).param("version", "3").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Station","addressLine":"Address","provinceCode":"79","wardCode":"26734",
                 "latitude":10.8,"longitude":106.7,"contactPhone":"0123456789","plannedChargePointCount":3}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.contactPhone.messageKey").value("validation.station.contactPhone.invalid"));
        verifyNoInteractions(useCase);
    }

    @Test @WithMockUser(roles = "OWNER")
    void withdrawalRequiresExplicitVersion() throws Exception {
        mvc.perform(delete(route, id)).andExpect(status().isBadRequest());
        verifyNoInteractions(useCase);
    }

    @Test @WithMockUser(roles = "OWNER")
    void withdrawalReturnsNoContent() throws Exception {
        mvc.perform(delete(route, id).param("version", "3")).andExpect(status().isNoContent());
        verify(useCase).withdraw(id, 3);
    }
}
