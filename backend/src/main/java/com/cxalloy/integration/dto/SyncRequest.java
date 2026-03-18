package com.cxalloy.integration.dto;

import java.util.List;

/**
 * Optional request body for individual sync endpoints.
 *
 * Usage:
 *   POST /api/sync/issues           → syncs ALL projects
 *   POST /api/sync/issues  { "projectIds": ["123","456"] }  → syncs only those projects
 *   POST /api/sync/issues?projectId=123                      → syncs single project (path param variant)
 */
public class SyncRequest {

    /** Optional list of CxAlloy externalIds to sync. Null/empty = sync all projects. */
    private List<String> projectIds;

    public SyncRequest() {}

    public SyncRequest(List<String> projectIds) {
        this.projectIds = projectIds;
    }

    public List<String> getProjectIds() { return projectIds; }
    public void setProjectIds(List<String> projectIds) { this.projectIds = projectIds; }

    public boolean hasFilter() {
        return projectIds != null && !projectIds.isEmpty();
    }
}
