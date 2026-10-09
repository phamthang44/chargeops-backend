package com.thang.chargeops.assistant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "app.assistant.dify")
@Validated
@Getter
@Setter
public class AssistantProperties {
    private boolean enabled;
    private String baseUrl = "https://api.dify.ai/v1";
    // No generated toString: credentials must not appear in logs.
    private String apiKey = "";
    @Min(1) @Max(30)
    private int connectTimeoutSeconds = 5;
    @Min(1) @Max(120)
    private int readTimeoutSeconds = 60;
    @Min(1) @Max(60)
    private int requestsPerMinute = 3;
    @Min(1) @Max(10000)
    private int requestsPerDay = 10;
    @Min(1) @Max(100000)
    private int totalRequestsPerDay = 30;
}
