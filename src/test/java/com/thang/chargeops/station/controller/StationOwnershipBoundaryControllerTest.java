package com.thang.chargeops.station.controller;

import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.station.service.StationService;
import com.thang.chargeops.station.service.usecase.OwnerStationRegistrationUseCase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({OwnerStationController.class, AdminStationController.class, OwnerStationRegistrationController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalHandlerError.class)
class StationOwnershipBoundaryControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean StationService stations;
    @MockitoBean OwnerStationRegistrationUseCase registrations;

    @ParameterizedTest
    @ValueSource(strings = {"owner", "admin"})
    void noStationDeleteOrOwnerTransferCommandIsExposed(String role) throws Exception {
        String path = "/api/v1/" + role + "/stations/" + UUID.randomUUID();
        mvc.perform(delete(path)).andExpect(status().is4xxClientError());
        mvc.perform(patch(path + "/owner").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ownerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(stations, registrations);
    }
}
