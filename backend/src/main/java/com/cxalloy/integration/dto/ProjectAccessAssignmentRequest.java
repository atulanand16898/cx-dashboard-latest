package com.cxalloy.integration.dto;

public class ProjectAccessAssignmentRequest {

    private String projectId;
    private String personExternalId;
    private String personEmail;
    private String personName;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getPersonExternalId() {
        return personExternalId;
    }

    public void setPersonExternalId(String personExternalId) {
        this.personExternalId = personExternalId;
    }

    public String getPersonEmail() {
        return personEmail;
    }

    public void setPersonEmail(String personEmail) {
        this.personEmail = personEmail;
    }

    public String getPersonName() {
        return personName;
    }

    public void setPersonName(String personName) {
        this.personName = personName;
    }
}
