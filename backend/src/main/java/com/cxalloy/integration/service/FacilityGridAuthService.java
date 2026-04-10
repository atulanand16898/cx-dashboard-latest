package com.cxalloy.integration.service;

import com.cxalloy.integration.client.FacilityGridApiClient;
import com.cxalloy.integration.config.FacilityGridApiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class FacilityGridAuthService {

    private final FacilityGridApiClient facilityGridApiClient;
    private final FacilityGridApiProperties facilityGridApiProperties;
    private final ObjectMapper objectMapper;

    public FacilityGridAuthService(FacilityGridApiClient facilityGridApiClient,
                                   FacilityGridApiProperties facilityGridApiProperties,
                                   ObjectMapper objectMapper) {
        this.facilityGridApiClient = facilityGridApiClient;
        this.facilityGridApiProperties = facilityGridApiProperties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> testClientCredentials(String clientId, String clientSecret) {
        JsonNode root = requestTokenNode(clientId, clientSecret);
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("authUrl", normalizeUrl(authPath()));
            result.put("tokenType", stringValue(root, "token_type"));
            result.put("expiresIn", numberValue(root, "expires_in"));
            result.put("scope", stringValue(root, "scope"));
            result.put("hasAccessToken", root.hasNonNull("access_token"));
            result.put("accessTokenPreview", preview(root.path("access_token").asText(null)));
            result.put("rawKeys", root.properties().stream().map(Map.Entry::getKey).toList());
            return result;
        } catch (Exception ex) {
            throw new RuntimeException("Facility Grid token response could not be parsed: " + ex.getMessage(), ex);
        }
    }

    public Map<String, Object> testEndpoint(String clientId, String clientSecret, String path) {
        String accessToken = requestAccessToken(clientId, clientSecret);

        String raw = facilityGridApiClient.get(path, accessToken);
        try {
            JsonNode root = objectMapper.readTree(raw);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", path);
            result.put("fullUrl", normalizeUrl(path));
            result.put("nodeType", nodeType(root));
            result.put("topLevelKeys", root.isObject()
                    ? root.properties().stream().map(Map.Entry::getKey).toList()
                    : java.util.List.of());
            result.put("arrayLength", root.isArray() ? root.size() : null);
            result.put("sample", root.isArray() && root.size() > 0 ? root.get(0) : root);
            return result;
        } catch (Exception ex) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", path);
            result.put("fullUrl", normalizeUrl(path));
            result.put("nodeType", "text");
            result.put("sample", raw);
            return result;
        }
    }

    private JsonNode requestTokenNode(String clientId, String clientSecret) {
        String raw = facilityGridApiClient.postForm(authPath(), buildTokenBody(clientId, clientSecret), null);
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new RuntimeException("Facility Grid token response could not be parsed: " + ex.getMessage(), ex);
        }
    }

    public String requestAccessToken(String clientId, String clientSecret) {
        JsonNode tokenRoot = requestTokenNode(clientId, clientSecret);
        String accessToken = tokenRoot.path("access_token").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new RuntimeException("Facility Grid token response did not include access_token");
        }
        return accessToken;
    }

    public String requestConfiguredAccessToken() {
        String clientId = facilityGridApiProperties.getClientId();
        String clientSecret = facilityGridApiProperties.getClientSecret();
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new RuntimeException("Facility Grid client credentials are not configured");
        }
        return requestAccessToken(clientId, clientSecret);
    }

    private String buildTokenBody(String clientId, String clientSecret) {
        return "grant_type=client_credentials"
                + "&client_id=" + urlEncode(clientId)
                + "&client_secret=" + urlEncode(clientSecret);
    }

    private String authPath() {
        String path = facilityGridApiProperties.getAuthUrl();
        if (path == null || path.isBlank()) {
            return "/oauth/token";
        }
        return path;
    }

    private String preview(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (token.length() <= 16) {
            return token;
        }
        return token.substring(0, 8) + "..." + token.substring(token.length() - 4);
    }

    private String stringValue(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private Long numberValue(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asLong() : null;
    }

    private String normalizeUrl(String path) {
        if (path == null || path.isBlank()) {
            return facilityGridApiProperties.getBaseUrl();
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        return facilityGridApiProperties.getBaseUrl() + path;
    }

    private String nodeType(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isObject()) {
            return "object";
        }
        return "value";
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
