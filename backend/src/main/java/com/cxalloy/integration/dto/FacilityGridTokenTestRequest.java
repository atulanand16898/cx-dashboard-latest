package com.cxalloy.integration.dto;

import jakarta.validation.constraints.NotBlank;

public class FacilityGridTokenTestRequest {

    @NotBlank(message = "Client ID is required")
    private String clientId;

    @NotBlank(message = "Client secret is required")
    private String clientSecret;

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
}
