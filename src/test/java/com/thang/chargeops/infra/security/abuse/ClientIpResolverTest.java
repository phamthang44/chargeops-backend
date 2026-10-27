package com.thang.chargeops.infra.security.abuse;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ClientIpResolverTest {
    @Test void onlyExplicitSocketPeersCanAssertOneIp() {
        var p = new AbuseProperties(); p.setTrustedProxies(List.of("172.30.240.3/32"));
        var resolver = new ClientIpResolver(p);
        var req = new MockHttpServletRequest(); req.setRemoteAddr("192.0.2.1");
        req.addHeader("X-Real-IP", "198.51.100.1"); req.addHeader("CF-Connecting-IP", "198.51.100.2");
        assertThat(resolver.resolve(req)).isEqualTo("192.0.2.1");
        req.setRemoteAddr("172.30.240.3");
        assertThat(resolver.resolve(req)).isEqualTo("198.51.100.1");
        req.removeHeader("X-Real-IP"); req.addHeader("X-Real-IP", "198.51.100.1, 192.0.2.9");
        assertThat(resolver.resolve(req)).isEqualTo("172.30.240.3");
    }
    @Test void ipv6EquivalentRepresentationsUseOneCanonicalAddress() {
        assertThat(ClientIpResolver.normalize("2001:db8::1")).isEqualTo(ClientIpResolver.normalize("2001:0db8:0:0:0:0:0:1"));
        assertThat(ClientIpResolver.normalize("attacker.example")).isEqualTo("unknown");
    }
}
