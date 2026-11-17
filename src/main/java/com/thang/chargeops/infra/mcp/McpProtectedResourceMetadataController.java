package com.thang.chargeops.infra.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
public class McpProtectedResourceMetadataController {

    private final McpProperties properties;

    public McpProtectedResourceMetadataController(McpProperties properties) {
        this.properties = properties;
    }

    @GetMapping({"/.well-known/oauth-protected-resource",
            "/.well-known/oauth-protected-resource/mcp"})
    public ResponseEntity<Map<String, Object>> metadata() {
        Map<String, Object> metadata = Map.of(
                "resource", properties.getResourceUrl(),
                "authorization_servers", List.of(properties.getAuthorizationServerUrl()),
                "scopes_supported", List.of(properties.getReadScope()),
                "bearer_methods_supported", List.of("header")
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(metadata);
    }
}
