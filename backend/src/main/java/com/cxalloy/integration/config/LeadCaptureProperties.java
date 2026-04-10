package com.cxalloy.integration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.leads")
public class LeadCaptureProperties {

    private List<String> notificationRecipients = new ArrayList<>();
    private String fromAddress = "no-reply@modum.local";
    private String continuePath = "/login";

    public List<String> getNotificationRecipients() {
        return notificationRecipients;
    }

    public void setNotificationRecipients(List<String> notificationRecipients) {
        this.notificationRecipients = notificationRecipients == null ? new ArrayList<>() : notificationRecipients;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public String getContinuePath() {
        return continuePath;
    }

    public void setContinuePath(String continuePath) {
        this.continuePath = continuePath;
    }
}
