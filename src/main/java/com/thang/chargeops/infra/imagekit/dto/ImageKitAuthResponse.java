package com.thang.chargeops.infra.imagekit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageKitAuthResponse {
    private String token;
    private long expire;
    private String signature;
    private String publicKey;
    private String urlEndpoint;
}
