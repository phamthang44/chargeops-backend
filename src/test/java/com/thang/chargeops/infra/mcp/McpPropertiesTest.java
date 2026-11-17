package com.thang.chargeops.infra.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpPropertiesTest {

    @Test
    void exposesCanonicalMetadataUrlAndGuardsHostAndOrigin() {
        McpProperties properties = new McpProperties();
        properties.setResourceUrl("https://api.chargeops.online/mcp");

        assertEquals("https://api.chargeops.online/.well-known/oauth-protected-resource/mcp",
                properties.metadataUrl());
        assertTrue(properties.acceptsHost("api.chargeops.online", 443));
        assertFalse(properties.acceptsHost("attacker.example", 443));
        assertTrue(properties.acceptsOrigin("https://api.chargeops.online"));
        assertFalse(properties.acceptsOrigin("https://attacker.example"));
        assertFalse(properties.acceptsOrigin("//api.chargeops.online"));
        assertFalse(properties.acceptsOrigin("https://api.chargeops.online:444"));
    }

    @Test
    void checksExplicitLocalPort() {
        McpProperties properties = new McpProperties();
        properties.setResourceUrl("http://localhost:8081/mcp");

        assertTrue(properties.acceptsHost("localhost", 8081));
        assertFalse(properties.acceptsHost("localhost", 8082));
        assertTrue(properties.acceptsOrigin("http://localhost:8081"));
        assertFalse(properties.acceptsOrigin("http://localhost:8082"));
    }
}
