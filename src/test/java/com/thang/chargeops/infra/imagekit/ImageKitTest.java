package com.thang.chargeops.infra.imagekit;

import io.imagekit.client.ImageKitClient;
import io.imagekit.client.okhttp.ImageKitOkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ImageKitTest {

    @Test
    @DisplayName("Should generate authentication parameters using ImageKit SDK helper")
    void testGetAuthParameters() {
        ImageKitClient client = ImageKitOkHttpClient.builder()
                .privateKey("test_private_key")
                .build();

        Map<String, Object> authParams = client.helper().getAuthenticationParameters(null, null);
        assertNotNull(authParams);
        assertNotNull(authParams.get("token"));
        assertNotNull(authParams.get("expire"));
        assertNotNull(authParams.get("signature"));
    }
}
