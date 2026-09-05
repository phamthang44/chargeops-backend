package com.thang.chargeops.infra.imagekit;

import io.imagekit.client.ImageKitClient;
import io.imagekit.client.okhttp.ImageKitOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ImageKitConfig {

    @Bean
    public ImageKitClient imageKitClient(
            @Value("${app.imagekit.private-key:}") String privateKey
    ) {
        String effectiveKey = (privateKey != null && !privateKey.isBlank())
                ? privateKey
                : "default_chargeops_private_key";

        return ImageKitOkHttpClient.builder()
                .privateKey(effectiveKey)
                .build();
    }
}
