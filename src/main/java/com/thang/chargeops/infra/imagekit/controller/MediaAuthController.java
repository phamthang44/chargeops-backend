package com.thang.chargeops.infra.imagekit.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.infra.imagekit.dto.ImageKitAuthResponse;
import io.imagekit.client.ImageKitClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "media")
@RequiredArgsConstructor
@Slf4j
public class MediaAuthController {

    private final ImageKitClient imageKitClient;

    @Value("${app.imagekit.public-key:}")
    private String publicKey;

    @Value("${app.imagekit.url-endpoint:https://ik.imagekit.io/chargeops}")
    private String urlEndpoint;

    @GetMapping("/imagekit-auth")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResult<ImageKitAuthResponse>> getImageKitAuth() {
        Map<String, Object> authParams = imageKitClient.helper().getAuthenticationParameters(null, null);

        String token = String.valueOf(authParams.get("token"));
        long expire = Long.parseLong(String.valueOf(authParams.get("expire")));
        String signature = String.valueOf(authParams.get("signature"));

        ImageKitAuthResponse response = ImageKitAuthResponse.builder()
                .token(token)
                .expire(expire)
                .signature(signature)
                .publicKey(publicKey)
                .urlEndpoint(urlEndpoint)
                .build();

        return ResponseEntity.ok(ApiResult.success(response));
    }
}
