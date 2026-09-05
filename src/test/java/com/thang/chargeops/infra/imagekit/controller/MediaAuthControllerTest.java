package com.thang.chargeops.infra.imagekit.controller;

import com.thang.chargeops.exception.GlobalHandlerError;
import io.imagekit.client.ImageKitClient;
import io.imagekit.lib.Helper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MediaAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalHandlerError.class)
class MediaAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ImageKitClient imageKitClient;

    @Test
    @DisplayName("Should return ImageKit auth credentials")
    void getImageKitAuth_success() throws Exception {
        Helper helperMock = mock(Helper.class);
        when(imageKitClient.helper()).thenReturn(helperMock);
        when(helperMock.getAuthenticationParameters(null, null)).thenReturn(Map.of(
                "token", "mock-token-123",
                "expire", 1757080000L,
                "signature", "mock-signature-abc"
        ));

        mockMvc.perform(get("/api/v1/media/imagekit-auth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("mock-token-123"))
                .andExpect(jsonPath("$.data.expire").value(1757080000L))
                .andExpect(jsonPath("$.data.signature").value("mock-signature-abc"));
    }
}
