package com.thang.chargeops.infra.security.abuse;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import java.net.InetAddress;
import java.util.List;

/** Never uses CF/XFF directly. Only the gateway may supply one canonical X-Real-IP. */
@Component
public class ClientIpResolver {
    private final List<IpAddressMatcher> proxies;
    public ClientIpResolver(AbuseProperties properties) {
        proxies = properties.getTrustedProxies().stream().filter(s -> !s.isBlank()).map(String::trim)
                .map(cidr -> {
                    if (cidr.endsWith("/0")) throw new IllegalArgumentException("Universal proxy trust is forbidden");
                    return new IpAddressMatcher(cidr);
                }).toList();
    }
    public String resolve(HttpServletRequest request) {
        String remote = normalize(request.getRemoteAddr());
        if (!"unknown".equals(remote) && proxies.stream().anyMatch(proxy -> proxy.matches(remote))) {
            String forwarded = request.getHeader("X-Real-IP");
            if (forwarded != null && !"unknown".equals(normalize(forwarded))) return normalize(forwarded);
        }
        return remote;
    }
    static boolean literal(String value) {
        return value.length() <= 45 && (value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}") ||
                (value.contains(":") && value.matches("[0-9a-fA-F:.]+")));
    }
    static String normalize(String value) {
        if (value == null || !literal(value)) return "unknown";
        try { return InetAddress.getByName(value).getHostAddress(); }
        catch (Exception ignored) { return "unknown"; }
    }
}
