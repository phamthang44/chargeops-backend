package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.enums.StationAssetType;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.station.dto.station.response.StationAssetResponse;
import com.thang.chargeops.station.entity.StationAsset;
import com.thang.chargeops.station.mapper.StationMapper;
import com.thang.chargeops.station.service.StationAssetUploadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerStationAssetController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalHandlerError.class)
class OwnerStationAssetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StationAssetUploadService stationAssetUploadService;

    @MockitoBean
    private StationMapper stationMapper;

    @Test
    @DisplayName("GET /api/v1/owner/stations/{stationId}/assets returns list of assets")
    void getStationAssets_success() throws Exception {
        UUID stationId = UUID.randomUUID();
        StationAsset asset = mock(StationAsset.class);
        StationAssetResponse response = new StationAssetResponse();
        response.setId(UUID.randomUUID());
        response.setAssetUrl("https://ik.imagekit.io/chargeops/img.jpg");
        response.setAssetType(StationAssetType.IMAGE);

        when(stationAssetUploadService.getStationAssets(stationId)).thenReturn(List.of(asset));
        when(stationMapper.toStationAssetResponse(asset)).thenReturn(response);

        mockMvc.perform(get("/api/v1/owner/stations/{stationId}/assets", stationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].assetUrl").value("https://ik.imagekit.io/chargeops/img.jpg"));
    }

    @Test
    @DisplayName("POST /api/v1/owner/stations/{stationId}/assets/register registers asset metadata")
    void registerStationAsset_success() throws Exception {
        UUID stationId = UUID.randomUUID();
        StationAsset asset = mock(StationAsset.class);
        StationAssetResponse response = new StationAssetResponse();
        response.setId(UUID.randomUUID());
        response.setAssetUrl("https://ik.imagekit.io/chargeops/img.jpg");
        response.setStorageKey("file_123");

        when(stationAssetUploadService.registerStationAsset(eq(stationId), any())).thenReturn(asset);
        when(stationMapper.toStationAssetResponse(asset)).thenReturn(response);

        mockMvc.perform(post("/api/v1/owner/stations/{stationId}/assets/register", stationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "assetUrl": "https://ik.imagekit.io/chargeops/img.jpg",
                                  "storageKey": "file_123",
                                  "assetType": "IMAGE",
                                  "primary": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.assetUrl").value("https://ik.imagekit.io/chargeops/img.jpg"))
                .andExpect(jsonPath("$.data.storageKey").value("file_123"));
    }

    @Test
    @DisplayName("DELETE /api/v1/owner/stations/{stationId}/assets/{assetId} deletes asset")
    void deleteStationAsset_success() throws Exception {
        UUID stationId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();

        doNothing().when(stationAssetUploadService).deleteStationAsset(stationId, assetId);

        mockMvc.perform(delete("/api/v1/owner/stations/{stationId}/assets/{assetId}", stationId, assetId))
                .andExpect(status().isOk());

        verify(stationAssetUploadService).deleteStationAsset(stationId, assetId);
    }
}
