package com.cxalloy.integration.model;

public enum ProjectFileStatus {
    APPROVED("approved"),
    OPEN("open"),
    IN_PROGRESS("inprogress"),
    NA("na");

    private final String apiValue;

    ProjectFileStatus(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }

    public static ProjectFileStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            return OPEN;
        }
        String normalized = value.trim().toLowerCase();
        for (ProjectFileStatus status : values()) {
            if (status.apiValue.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unsupported file status: " + value);
    }
}
