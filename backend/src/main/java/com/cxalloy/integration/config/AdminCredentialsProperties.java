package com.cxalloy.integration.config;

import com.cxalloy.integration.model.DataProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
@ConfigurationProperties(prefix = "app.admin")
public class AdminCredentialsProperties {

    private String cxalloyUsername = "admin";
    private String cxalloyPassword = "admin123";
    private String facilitygridUsername = "fg-admin";
    private String facilitygridPassword = "fgadmin123";
    private String primaveraUsername = "p6-admin";
    private String primaveraPassword = "p6admin123";

    public String getCxalloyUsername() {
        return cxalloyUsername;
    }

    public void setCxalloyUsername(String cxalloyUsername) {
        this.cxalloyUsername = cxalloyUsername;
    }

    public String getCxalloyPassword() {
        return cxalloyPassword;
    }

    public void setCxalloyPassword(String cxalloyPassword) {
        this.cxalloyPassword = cxalloyPassword;
    }

    public String getFacilitygridUsername() {
        return facilitygridUsername;
    }

    public void setFacilitygridUsername(String facilitygridUsername) {
        this.facilitygridUsername = facilitygridUsername;
    }

    public String getFacilitygridPassword() {
        return facilitygridPassword;
    }

    public void setFacilitygridPassword(String facilitygridPassword) {
        this.facilitygridPassword = facilitygridPassword;
    }

    public String getPrimaveraUsername() {
        return primaveraUsername;
    }

    public void setPrimaveraUsername(String primaveraUsername) {
        this.primaveraUsername = primaveraUsername;
    }

    public String getPrimaveraPassword() {
        return primaveraPassword;
    }

    public void setPrimaveraPassword(String primaveraPassword) {
        this.primaveraPassword = primaveraPassword;
    }

    public String usernameFor(DataProvider provider) {
        return switch (provider) {
            case FACILITY_GRID -> facilitygridUsername;
            case PRIMAVERA -> primaveraUsername;
            case CXALLOY -> cxalloyUsername;
        };
    }

    public String passwordFor(DataProvider provider) {
        return switch (provider) {
            case FACILITY_GRID -> facilitygridPassword;
            case PRIMAVERA -> primaveraPassword;
            case CXALLOY -> cxalloyPassword;
        };
    }

    public boolean matchesProviderAdmin(DataProvider provider, String username) {
        return normalize(usernameFor(provider)).equals(normalize(username));
    }

    public boolean isAdminUsername(String username) {
        String normalized = normalize(username);
        return normalized.equals(normalize(cxalloyUsername))
                || normalized.equals(normalize(facilitygridUsername))
                || normalized.equals(normalize(primaveraUsername));
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
