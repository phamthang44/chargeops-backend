package com.thang.chargeops.infra.security.abuse;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

/** Observe is deliberate: enforcement requires an operator-owned HMAC key. */
@Component
@ConfigurationProperties(prefix = "app.abuse")
@Getter @Setter
public class AbuseProperties {
    public enum Mode { OFF, OBSERVE, ENFORCE }
    private Mode mode = Mode.OBSERVE;
    private String hmacKey = "";
    private List<String> trustedProxies = List.of();
    private int strikeWindowSeconds = 600;
    private int cooldownSeconds = 900;
    private int strikeMinutes = 3;
    private java.util.Map<AbuseGroup, Integer> limits = new java.util.EnumMap<>(AbuseGroup.class);
}
