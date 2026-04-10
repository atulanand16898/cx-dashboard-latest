package com.cxalloy.integration.service;

import com.cxalloy.integration.client.FacilityGridApiClient;
import com.cxalloy.integration.config.FacilityGridApiProperties;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.ApiSyncLog;
import com.cxalloy.integration.model.CxTask;
import com.cxalloy.integration.repository.CxTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@Transactional
public class FacilityGridTaskService extends BaseProjectService {

    private static final int PAGE_LIMIT = 5000;

    private final CxTaskRepository taskRepository;
    private final FacilityGridApiClient facilityGridApiClient;
    private final FacilityGridApiProperties facilityGridApiProperties;
    private final FacilityGridAuthService facilityGridAuthService;

    public FacilityGridTaskService(CxTaskRepository taskRepository,
                                   FacilityGridApiClient facilityGridApiClient,
                                   FacilityGridApiProperties facilityGridApiProperties,
                                   FacilityGridAuthService facilityGridAuthService) {
        this.taskRepository = taskRepository;
        this.facilityGridApiClient = facilityGridApiClient;
        this.facilityGridApiProperties = facilityGridApiProperties;
        this.facilityGridAuthService = facilityGridAuthService;
    }

    @Caching(evict = {
        @CacheEvict(value = "tasks-by-project", allEntries = true),
        @CacheEvict(value = "tasks-all", allEntries = true)
    })
    public SyncResult syncTasks(String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        String endpoint = subtasksPath(pid);
        ApiSyncLog log = startLog(endpoint, "GET");
        int totalSynced = 0;

        try {
            String accessToken = facilityGridAuthService.requestConfiguredAccessToken();
            int offset = 0;

            while (true) {
                String pagePath = paged(endpoint, PAGE_LIMIT, offset);
                String raw = facilityGridApiClient.get(pagePath, accessToken);
                saveRaw(pagePath, "facilitygrid_subtasks_list_o" + offset, pid, raw);

                int count = parseAndSave(raw, pid, true);
                totalSynced += count;
                if (count < PAGE_LIMIT) {
                    break;
                }
                offset += PAGE_LIMIT;
            }

            if (totalSynced == 0) {
                String fallbackRaw = facilityGridApiClient.get(tasksPath(pid), accessToken);
                saveRaw(tasksPath(pid), "facilitygrid_tasks_list", pid, fallbackRaw);
                totalSynced += parseAndSave(fallbackRaw, pid, false);
            }

            long duration = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", totalSynced, duration, null);
            return new SyncResult(endpoint, "SUCCESS", totalSynced,
                    "Synced " + totalSynced + " Facility Grid task records", duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", totalSynced, duration, e.getMessage());
            throw new RuntimeException("Facility Grid task sync failed: " + e.getMessage(), e);
        }
    }

    private int parseAndSave(String json, String projectId, boolean subtasks) throws Exception {
        if (!StringUtils.hasText(json)) {
            return 0;
        }

        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) {
            throw new IllegalStateException("Facility Grid task response was not an array");
        }

        List<CxTask> rows = new ArrayList<>();
        for (JsonNode node : root) {
            rows.add(map(node, projectId, subtasks));
        }
        rows.forEach(this::upsert);
        return rows.size();
    }

    private CxTask map(JsonNode node, String projectId, boolean subtasks) {
        CxTask task = new CxTask();

        String externalId = subtasks
                ? getAsText(node, "subtask_uuid", null)
                : firstNonBlank(getAsText(node, "task_external_id", null), numberText(node.get("task_id")));

        task.setExternalId(externalId);
        task.setProvider(currentProviderKey());
        task.setSourceKey(sourceKey(subtasks ? "subtask" : "task", externalId));
        task.setProjectId(projectId);
        task.setTitle(firstNonBlank(
                getAsText(node, subtasks ? "subtask_name" : "task_name", null),
                getAsText(node, "task_name", null),
                externalId));
        task.setDescription(buildDescription(node, subtasks));
        task.setStatus(normalizeTaskStatus(node.get("status")));
        task.setPriority(firstNonBlank(getAsText(node, "cx_level", null), numberText(node.get("weight"))));
        task.setAssignedTo(null);
        task.setDueDate(firstNonBlank(
                getAsText(node, "anticipated_completion_date", null),
                getAsText(node.path("date_changes"), "forecasted_end_date", null)));
        task.setCompletedDate(getAsText(node, "actual_completion_date", null));
        task.setIssueId(null);
        task.setCreatedAt(firstNonBlank(
                getAsText(node, "anticipated_start_date", null),
                getAsText(node, "baseline_start_date", null)));
        task.setUpdatedAt(firstNonBlank(
                getAsText(node, "actual_completion_date", null),
                getAsText(node.path("date_changes"), "forecasted_end_date", null),
                getAsText(node, "anticipated_completion_date", null)));
        task.setRawJson(node.toString());
        task.setSyncedAt(now());
        return task;
    }

    private void upsert(CxTask task) {
        if (!StringUtils.hasText(task.getExternalId())) {
            logger.warn("Skipping Facility Grid task row with null externalId");
            return;
        }

        taskRepository.findBySourceKey(task.getSourceKey()).ifPresentOrElse(existing -> {
            existing.setProvider(task.getProvider());
            existing.setSourceKey(task.getSourceKey());
            existing.setProjectId(task.getProjectId());
            existing.setTitle(task.getTitle());
            existing.setDescription(task.getDescription());
            existing.setStatus(task.getStatus());
            existing.setPriority(task.getPriority());
            existing.setAssignedTo(task.getAssignedTo());
            existing.setDueDate(task.getDueDate());
            existing.setCompletedDate(task.getCompletedDate());
            existing.setIssueId(task.getIssueId());
            existing.setCreatedAt(task.getCreatedAt());
            existing.setUpdatedAt(task.getUpdatedAt());
            existing.setRawJson(task.getRawJson());
            existing.setSyncedAt(task.getSyncedAt());
            taskRepository.save(existing);
        }, () -> taskRepository.save(task));
    }

    private String buildDescription(JsonNode node, boolean subtasks) {
        List<String> parts = new ArrayList<>();
        if (subtasks) {
            addIfPresent(parts, getAsText(node, "subtask_code", null));
            addIfPresent(parts, getAsText(node, "project_phase", null));
            addIfPresent(parts, getAsText(node, "task_name", null));
        } else {
            addIfPresent(parts, getAsText(node, "task_abbreviation", null));
            addIfPresent(parts, getAsText(node, "project_phase_name", null));
        }
        return parts.isEmpty() ? null : String.join(" | ", parts);
    }

    private void addIfPresent(List<String> parts, String value) {
        if (StringUtils.hasText(value)) {
            parts.add(value.trim());
        }
    }

    private String normalizeTaskStatus(JsonNode statusNode) {
        String raw = null;
        if (statusNode != null && !statusNode.isNull()) {
            raw = statusNode.isObject()
                    ? firstNonBlank(getAsText(statusNode, "resolved_status", null), getAsText(statusNode, "name", null))
                    : statusNode.asText(null);
        }
        if (!StringUtils.hasText(raw)) {
            return "not_started";
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (normalized) {
            case "not_started", "new", "open" -> "not_started";
            case "in_progress", "started", "active" -> "in_progress";
            case "complete", "completed", "done", "closed" -> "completed";
            default -> normalized;
        };
    }

    private String subtasksPath(String projectId) {
        return expandProjectPath(
                facilityGridApiProperties.getAnalyticsSubtasksPathTemplate(),
                projectId,
                "/api/v2_2/project/{project_id}/analytics/subtasks");
    }

    private String tasksPath(String projectId) {
        return expandProjectPath(
                facilityGridApiProperties.getAnalyticsTasksPathTemplate(),
                projectId,
                "/api/v2_2/project/{project_id}/analytics/tasks");
    }

    private String expandProjectPath(String template, String projectId, String fallback) {
        String value = StringUtils.hasText(template) ? template : fallback;
        return value.replace("{project_id}", projectId);
    }

    private String sourceKey(String kind, String externalId) {
        if (!StringUtils.hasText(externalId)) {
            return null;
        }
        return currentProviderKey() + ":" + kind + ":" + externalId.trim();
    }

    private String paged(String path, int limit, int offset) {
        String separator = path.contains("?") ? "&" : "?";
        return path + separator + "limit=" + limit + "&offset=" + offset;
    }

    private String numberText(JsonNode node) {
        return node != null && !node.isNull() ? node.asText() : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
