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

    public String usernameFor(DataProvider provider) {
        return provider == DataProvider.FACILITY_GRID ? facilitygridUsername : cxalloyUsername;
    }

    public String passwordFor(DataProvider provider) {
        return provider == DataProvider.FACILITY_GRID ? facilitygridPassword : cxalloyPassword;
    }

    public boolean matchesProviderAdmin(DataProvider provider, String username) {
        return normalize(usernameFor(provider)).equals(normalize(username));
    }

    public boolean isAdminUsername(String username) {
        String normalized = normalize(username);
        return normalized.equals(normalize(cxalloyUsername))
                || normalized.equals(normalize(facilitygridUsername));
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
