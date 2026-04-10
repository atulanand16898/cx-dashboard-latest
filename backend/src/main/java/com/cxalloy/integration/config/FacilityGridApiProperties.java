package com.cxalloy.integration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "facilitygrid.api")
public class FacilityGridApiProperties {

    private String baseUrl;
    private String authUrl;
    private String projectsPath;
    private String issuesPathTemplate;
    private String issuePathTemplate;
    private String checklistPathTemplate;
    private String checklistDetailsPathTemplate;
    private String functionalTestPathTemplate;
    private String functionalTestDetailsPathTemplate;
    private String equipmentPathTemplate;
    private String equipmentTypesPathTemplate;
    private String analyticsTasksPathTemplate;
    private String analyticsSubtasksPathTemplate;
    private String clientId;
    private String clientSecret;
    private int requestTimeoutSeconds = 20;
    private int checklistSyncTimeoutSeconds = 60;
    private int checklistStatusSyncTimeoutSeconds = 60;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAuthUrl() {
        return authUrl;
    }

    public void setAuthUrl(String authUrl) {
        this.authUrl = authUrl;
    }

    public String getProjectsPath() {
        return projectsPath;
    }

    public void setProjectsPath(String projectsPath) {
        this.projectsPath = projectsPath;
    }

    public String getIssuesPathTemplate() {
        return issuesPathTemplate;
    }

    public void setIssuesPathTemplate(String issuesPathTemplate) {
        this.issuesPathTemplate = issuesPathTemplate;
    }

    public String getIssuePathTemplate() {
        return issuePathTemplate;
    }

    public void setIssuePathTemplate(String issuePathTemplate) {
        this.issuePathTemplate = issuePathTemplate;
    }

    public String getChecklistPathTemplate() {
        return checklistPathTemplate;
    }

    public void setChecklistPathTemplate(String checklistPathTemplate) {
        this.checklistPathTemplate = checklistPathTemplate;
    }

    public String getChecklistDetailsPathTemplate() {
        return checklistDetailsPathTemplate;
    }

    public void setChecklistDetailsPathTemplate(String checklistDetailsPathTemplate) {
        this.checklistDetailsPathTemplate = checklistDetailsPathTemplate;
    }

    public String getFunctionalTestPathTemplate() {
        return functionalTestPathTemplate;
    }

    public void setFunctionalTestPathTemplate(String functionalTestPathTemplate) {
        this.functionalTestPathTemplate = functionalTestPathTemplate;
    }

    public String getFunctionalTestDetailsPathTemplate() {
        return functionalTestDetailsPathTemplate;
    }

    public void setFunctionalTestDetailsPathTemplate(String functionalTestDetailsPathTemplate) {
        this.functionalTestDetailsPathTemplate = functionalTestDetailsPathTemplate;
    }

    public String getEquipmentPathTemplate() {
        return equipmentPathTemplate;
    }

    public void setEquipmentPathTemplate(String equipmentPathTemplate) {
        this.equipmentPathTemplate = equipmentPathTemplate;
    }

    public String getEquipmentTypesPathTemplate() {
        return equipmentTypesPathTemplate;
    }

    public void setEquipmentTypesPathTemplate(String equipmentTypesPathTemplate) {
        this.equipmentTypesPathTemplate = equipmentTypesPathTemplate;
    }

    public String getAnalyticsTasksPathTemplate() {
        return analyticsTasksPathTemplate;
    }

    public void setAnalyticsTasksPathTemplate(String analyticsTasksPathTemplate) {
        this.analyticsTasksPathTemplate = analyticsTasksPathTemplate;
    }

    public String getAnalyticsSubtasksPathTemplate() {
        return analyticsSubtasksPathTemplate;
    }

    public void setAnalyticsSubtasksPathTemplate(String analyticsSubtasksPathTemplate) {
        this.analyticsSubtasksPathTemplate = analyticsSubtasksPathTemplate;
    }

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

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getChecklistSyncTimeoutSeconds() {
        return checklistSyncTimeoutSeconds;
    }

    public void setChecklistSyncTimeoutSeconds(int checklistSyncTimeoutSeconds) {
        this.checklistSyncTimeoutSeconds = checklistSyncTimeoutSeconds;
    }

    public int getChecklistStatusSyncTimeoutSeconds() {
        return checklistStatusSyncTimeoutSeconds;
    }

    public void setChecklistStatusSyncTimeoutSeconds(int checklistStatusSyncTimeoutSeconds) {
        this.checklistStatusSyncTimeoutSeconds = checklistStatusSyncTimeoutSeconds;
    }
}
