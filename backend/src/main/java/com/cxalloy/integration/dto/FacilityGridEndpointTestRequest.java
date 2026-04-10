package com.cxalloy.integration.dto;

import jakarta.validation.constraints.NotBlank;

public class FacilityGridEndpointTestRequest {

    @NotBlank(message = "Client ID is required")
    private String clientId;

    @NotBlank(message = "Client secret is required")
    private String clientSecret;

    @NotBlank(message = "Endpoint path is required")
    private String path;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
