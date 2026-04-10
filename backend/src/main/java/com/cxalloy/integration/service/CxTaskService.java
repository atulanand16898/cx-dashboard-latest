package com.cxalloy.integration.service;

import com.cxalloy.integration.client.CxAlloyApiClient;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.ApiSyncLog;
import com.cxalloy.integration.model.CxTask;
import com.cxalloy.integration.model.DataProvider;
import com.cxalloy.integration.repository.CxTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;

@Service
@Transactional
public class CxTaskService extends BaseProjectService {

    private final CxTaskRepository taskRepository;
    private final CxAlloyApiClient apiClient;
    private final FacilityGridTaskService facilityGridTaskService;

    public CxTaskService(CxTaskRepository taskRepository,
                         CxAlloyApiClient apiClient,
                         FacilityGridTaskService facilityGridTaskService) {
        this.taskRepository = taskRepository;
        this.apiClient = apiClient;
        this.facilityGridTaskService = facilityGridTaskService;
    }

    @Caching(evict = {
        @CacheEvict(value = "tasks-by-project", allEntries = true),
        @CacheEvict(value = "tasks-all", allEntries = true)
    })
    public SyncResult syncTasks(String projectId) {
        if (currentProvider() == DataProvider.FACILITY_GRID) {
            return facilityGridTaskService.syncTasks(projectId);
        }
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        ApiSyncLog log = startLog("/task", "GET");
        int totalSynced = 0; int page = 1;
        try {
            while (true) {
                String url = "/task?project_id=" + pid + (page > 1 ? "&page=" + page : "");
                String raw = apiClient.get(url);
                if (raw == null || raw.isBlank()) break;
                if (raw.trim().startsWith("{") && !raw.contains("\"data\"") &&
                    (raw.contains("\"error\"") || raw.contains("\"message\""))) break;
                saveRaw("/task?page=" + page, "tasks_list_p" + page, pid, raw);
                int count = parseAndSave(raw, pid);
                totalSynced += count;
                if (count < 500) break;
                if (++page > 50) break;
            }
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", totalSynced, dur, null);
            return new SyncResult("/task", "SUCCESS", totalSynced,
                "Synced " + totalSynced + " tasks (" + page + " pages)", dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", 0, dur, e.getMessage());
            throw new RuntimeException("Sync tasks failed: " + e.getMessage(), e);
        }
    }

    @Cacheable(value = "tasks-all", key = "@providerContextService.currentProviderKey()")
    @Transactional(readOnly = true)
    public List<CxTask> getAll() {
        return taskRepository.findAll().stream()
                .filter(task -> providerContextService.matchesCurrentProvider(task.getProvider()))
                .toList();
    }

    @Cacheable(value = "entity-by-id", key = "\"tasks-\" + #id")
    @Transactional(readOnly = true)
    public Optional<CxTask> getById(Long id) { return taskRepository.findById(id); }

    @Cacheable(value = "tasks-by-project", key = "#projectId + '::' + @providerContextService.currentProviderKey()")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<CxTask> getByProject(String projectId) {
        ensureProviderTasksLoaded(projectId);
        return taskRepository.findByProjectId(projectId).stream()
                .filter(task -> providerContextService.matchesCurrentProvider(task.getProvider()))
                .toList();
    }

    public List<CxTask> getByIssue(String issueId) {
        return taskRepository.findByIssueId(issueId).stream()
                .filter(task -> providerContextService.matchesCurrentProvider(task.getProvider()))
                .toList();
    }

    private void ensureProviderTasksLoaded(String projectId) {
        if (currentProvider() != DataProvider.FACILITY_GRID || !StringUtils.hasText(projectId)) {
            return;
        }
        if (taskRepository.countByProjectIdAndProviderIgnoreCase(projectId.trim(), currentProviderKey()) > 0) {
            return;
        }
        try {
            facilityGridTaskService.syncTasks(projectId);
        } catch (Exception ex) {
            logger.warn("Facility Grid task auto-sync failed for project {}: {}", projectId, ex.getMessage());
        }
    }

    private int parseAndSave(String json, String pid) throws Exception {
        if (json == null || json.isBlank()) { logger.warn("Empty response for /task project={}", pid); return 0; }
        JsonNode root = objectMapper.readTree(json);
        JsonNode data = extractData(root, "/task project=" + pid);
        List<CxTask> list = new ArrayList<>();
        if (data.isArray()) { for (JsonNode n : data) list.add(map(n, pid)); }
        else if (data.isObject() && data.size() > 0) list.add(map(data, pid));
        list.forEach(this::upsert);
        logger.info("Parsed {} tasks for project {}", list.size(), pid);
        return list.size();
    }

    private CxTask map(JsonNode n, String pid) {
        CxTask t = new CxTask();
        t.setExternalId(getAsText(n, "id", getAsText(n, "_id", null)));
        t.setProvider(currentProviderKey());
        t.setSourceKey(sourceKeyFor(t.getExternalId()));
        t.setProjectId(pid);
        t.setTitle(getAsText(n, "title", getAsText(n, "name", null)));
        t.setDescription(getAsText(n, "description", null));
        t.setStatus(getAsText(n, "status", null));
        t.setPriority(getAsText(n, "priority", null));
        t.setAssignedTo(getAsText(n, "assigned_to", null));
        t.setDueDate(getAsText(n, "due_date", null));
        t.setCompletedDate(getAsText(n, "completed_date", null));
        t.setIssueId(getAsText(n, "issue_id", null));
        t.setCreatedAt(getAsText(n, "created_at", getAsText(n, "date_created", null)));
        t.setUpdatedAt(getAsText(n, "updated_at", null));
        t.setRawJson(n.toString());
        t.setSyncedAt(now());
        return t;
    }

    private void upsert(CxTask t) {
        if (t.getExternalId() != null) {
            findExistingTask(t).ifPresentOrElse(existing -> {
                existing.setTitle(t.getTitle()); existing.setStatus(t.getStatus());
                existing.setProvider(t.getProvider()); existing.setSourceKey(t.getSourceKey());
                existing.setAssignedTo(t.getAssignedTo()); existing.setUpdatedAt(t.getUpdatedAt());
                existing.setRawJson(t.getRawJson()); existing.setSyncedAt(now());
                taskRepository.save(existing);
            }, () -> taskRepository.save(t));
        } else { taskRepository.save(t); }
    }

    private Optional<CxTask> findExistingTask(CxTask task) {
        if (StringUtils.hasText(task.getSourceKey())) {
            Optional<CxTask> bySourceKey = taskRepository.findBySourceKey(task.getSourceKey());
            if (bySourceKey.isPresent()) {
                return bySourceKey;
            }
        }
        Optional<CxTask> byCurrentProvider = taskRepository.findByExternalIdAndProvider(
                task.getExternalId(),
                currentProviderKey());
        if (byCurrentProvider.isPresent()) {
            return byCurrentProvider;
        }
        if (currentProvider() == DataProvider.CXALLOY) {
            return taskRepository.findAllByExternalId(task.getExternalId()).stream()
                    .filter(existing -> isLegacyCurrentProviderRecord(existing.getProvider()))
                    .findFirst();
        }
        return Optional.empty();
    }
}
