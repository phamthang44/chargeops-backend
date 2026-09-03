package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.station.dto.station.request.ChangeStationOperationalStatusRequest;
import com.thang.chargeops.station.dto.station.response.StationOperationalStatusResponse;
import com.thang.chargeops.station.service.StationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerStationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalHandlerError.class)
class OwnerStationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StationService stationService;

    @Test
    void ownerChangesStationOperationalStatus() throws Exception {
        UUID stationId = UUID.randomUUID();
        StationOperationalStatusResponse response = new StationOperationalStatusResponse(
                stationId,
                StationOperationalStatus.PAUSED,
                "Power outage"
        );
        when(stationService.changeOperationalStatusForCurrentOwner(
                eq(stationId),
                eq(new ChangeStationOperationalStatusRequest(
                        StationOperationalStatus.PAUSED,
                        "Power outage"
                ))
        )).thenReturn(response);

        mockMvc.perform(patch(
                        "/api/v1/owner/stations/{stationId}/operational-status",
                        stationId
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operationalStatus": "PAUSED",
                                  "reason": "Power outage"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stationId").value(stationId.toString()))
                .andExpect(jsonPath("$.data.operationalStatus").value("PAUSED"))
                .andExpect(jsonPath("$.data.reason").value("Power outage"));

        verify(stationService).changeOperationalStatusForCurrentOwner(
                stationId,
                new ChangeStationOperationalStatusRequest(
                        StationOperationalStatus.PAUSED,
                        "Power outage"
                )
        );
    }
}
