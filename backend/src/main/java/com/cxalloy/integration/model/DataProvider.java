package com.cxalloy.integration.model;

import java.util.Locale;

public enum DataProvider {
    CXALLOY("cxalloy", "CxAlloy"),
    FACILITY_GRID("facilitygrid", "Facility Grid");

    private final String key;
    private final String label;

    DataProvider(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public String sourceKeyFor(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return null;
        }
        return key + ":" + externalId.trim();
    }

    public static DataProvider fromValue(String value) {
        if (value == null || value.isBlank()) {
            return CXALLOY;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');

        for (DataProvider provider : values()) {
            if (provider.key.equals(normalized) || provider.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return provider;
            }
        }

        throw new IllegalArgumentException("Unsupported provider: " + value);
    }
}
