package com.cxalloy.integration.dto;

import java.util.ArrayList;
import java.util.List;

public class ProjectVisibilityRequest {

    private List<String> projectIds = new ArrayList<>();

    public List<String> getProjectIds() {
        return projectIds;
    }

    public void setProjectIds(List<String> projectIds) {
        this.projectIds = projectIds == null ? new ArrayList<>() : projectIds;
    }
}
