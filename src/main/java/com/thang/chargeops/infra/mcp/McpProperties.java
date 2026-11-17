package com.thang.chargeops.infra.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

@Component
@ConfigurationProperties(prefix = "app.mcp")
public class McpProperties {

    private String resourceUrl = "https://api.chargeops.online/mcp";
    private String authorizationServerUrl = "https://auth.chargeops.online/realms/chargeops";
    private String readScope = "chargeops:mcp:read";

    public String getResourceUrl() {
        return resourceUrl;
    }

    public void setResourceUrl(String resourceUrl) {
        this.resourceUrl = resourceUrl;
    }

    public String getAuthorizationServerUrl() {
        return authorizationServerUrl;
    }

    public void setAuthorizationServerUrl(String authorizationServerUrl) {
        this.authorizationServerUrl = authorizationServerUrl;
    }

    public String getReadScope() {
        return readScope;
    }

    public void setReadScope(String readScope) {
        this.readScope = readScope;
    }

    public URI resourceUri() {
        URI uri = URI.create(resourceUrl);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("https") && !scheme.equals("http")) || uri.getHost() == null
                || !"/mcp".equals(uri.getPath()) || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("app.mcp.resource-url must be an absolute HTTP(S) /mcp URL");
        }
        return uri;
    }

    public URI authorizationServerUri() {
        URI uri = URI.create(authorizationServerUrl);
        if (uri.getHost() == null || (!"https".equalsIgnoreCase(uri.getScheme())
                && !"http".equalsIgnoreCase(uri.getScheme())) || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("app.mcp.authorization-server-url must be an absolute HTTP(S) URL");
        }
        return uri;
    }

    public String metadataUrl() {
        URI resource = resourceUri();
        return resource.getScheme() + "://" + resource.getRawAuthority()
                + "/.well-known/oauth-protected-resource/mcp";
    }

    public boolean acceptsHost(String host, int port) {
        URI resource = resourceUri();
        if (host == null || !resource.getHost().equalsIgnoreCase(host)) {
            return false;
        }
        int configuredPort = effectivePort(resource);
        if (configuredPort == 443) {
            // The public TLS terminator forwards API traffic to the backend over port 80.
            return port == 80 || port == 443;
        }
        return configuredPort == port;
    }

    public boolean acceptsOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            URI candidate = URI.create(origin);
            URI resource = resourceUri();
            return candidate.getHost() != null && candidate.getScheme() != null
                    && candidate.getRawUserInfo() == null
                    && candidate.getRawQuery() == null && candidate.getRawFragment() == null
                    && (candidate.getRawPath() == null || candidate.getRawPath().isEmpty()
                    || "/".equals(candidate.getRawPath()))
                    && candidate.getHost().equalsIgnoreCase(resource.getHost())
                    && candidate.getScheme().equalsIgnoreCase(resource.getScheme())
                    && effectivePort(candidate) == effectivePort(resource);
        } catch (IllegalArgumentException invalidOrigin) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
