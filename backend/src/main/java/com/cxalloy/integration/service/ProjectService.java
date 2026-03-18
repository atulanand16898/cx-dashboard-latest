package com.cxalloy.integration.service;

import com.cxalloy.integration.client.CxAlloyApiClient;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.ApiSyncLog;
import com.cxalloy.integration.model.Project;
import com.cxalloy.integration.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;

@Service
@Transactional
public class ProjectService extends BaseProjectService {

    private final ProjectRepository projectRepository;
    private final CxAlloyApiClient apiClient;
    private final ProjectAccessService projectAccessService;

    public ProjectService(ProjectRepository projectRepository,
                          CxAlloyApiClient apiClient,
                          ProjectAccessService projectAccessService) {
        this.projectRepository = projectRepository;
        this.apiClient = apiClient;
        this.projectAccessService = projectAccessService;
    }

    @Caching(evict = {
        @CacheEvict(value = "projects-all",      allEntries = true),
        @CacheEvict(value = "projects-by-id",    allEntries = true),
        @CacheEvict(value = "projects-external", allEntries = true)
    })
    public SyncResult syncAllProjects() {
        long start = System.currentTimeMillis();
        ApiSyncLog log = startLog("/project", "GET");
        try {
            String raw = apiClient.get("/project");
            saveRaw("/project", "projects_list", null, raw);
            int count = parseAndSave(raw);
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", count, dur, null);
            logger.info("Synced {} projects from CxAlloy", count);
            return new SyncResult("/project", "SUCCESS", count, "Synced " + count + " projects", dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", 0, dur, e.getMessage());
            throw new RuntimeException("Sync projects failed: " + e.getMessage(), e);
        }
    }

    @Caching(evict = {
        @CacheEvict(value = "projects-all",      allEntries = true),
        @CacheEvict(value = "projects-by-id",    allEntries = true),
        @CacheEvict(value = "projects-external", allEntries = true)
    })
    public SyncResult syncProjectById(String projectId) {
        long start = System.currentTimeMillis();
        ApiSyncLog log = startLog("/project/" + projectId, "GET");
        try {
            String raw = apiClient.get("/project/" + projectId);
            saveRaw("/project/" + projectId, "project_single", projectId, raw);
            JsonNode root = objectMapper.readTree(raw);
            JsonNode data = extractData(root, "/project/" + projectId);
            Project p = map(data.isArray() ? data.get(0) : data);
            upsert(p);
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", 1, dur, null);
            return new SyncResult("/project/" + projectId, "SUCCESS", 1, "Project synced", dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", 0, dur, e.getMessage());
            throw new RuntimeException("Sync project failed: " + e.getMessage(), e);
        }
    }

    @Cacheable(value = "projects-all")
    @Transactional(readOnly = true)
    public List<Project> getAll() { return projectRepository.findAll(); }

    @Transactional(readOnly = true)
    public List<Project> getAllForCurrentUser() {
        return projectAccessService.filterProjectsForCurrentUser(getAll());
    }

    @Cacheable(value = "projects-by-id", key = "#id")
    @Transactional(readOnly = true)
    public Optional<Project> getById(Long id) {
        Optional<Project> project = projectRepository.findById(id);
        project.ifPresent(value -> projectAccessService.requireProjectAccess(value.getExternalId()));
        return project;
    }

    @Cacheable(value = "projects-external", key = "#extId")
    @Transactional(readOnly = true)
    public Optional<Project> getByExternalId(String extId) {
        Optional<Project> project = projectRepository.findByExternalId(extId);
        project.ifPresent(value -> projectAccessService.requireProjectAccess(value.getExternalId()));
        return project;
    }

    private int parseAndSave(String json) throws Exception {
        if (json == null || json.isBlank()) { logger.warn("Empty response from /project"); return 0; }
        JsonNode root = objectMapper.readTree(json);

        // CxAlloy wraps the project list under a key — try known wrappers
        JsonNode data = extractData(root, "/project");

        List<Project> list = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode n : data) list.add(map(n));
        } else if (data.isObject() && data.size() > 0) {
            list.add(map(data));
        }

        list.forEach(this::upsert);
        logger.info("Parsed and saved {} projects", list.size());
        return list.size();
    }

    /**
     * Maps a CxAlloy project JSON node to the Project entity.
     *
     * CxAlloy uses "project_id" (NOT "id") as the unique identifier.
     * All fields from the actual API response are explicitly mapped here.
     *
     * Example CxAlloy response shape:
     * {
     *   "project_id": "50156",
     *   "account_id": "990",
     *   "name": "QAJ01 Khazna RFS01",
     *   "status": "Active",
     *   "number": null,
     *   "client": "Khazna",
     *   "building_owner": null,
     *   "location": "Dubai, UAE",
     *   "size": "40MW",
     *   "cost": null,
     *   "phase": "Construction",
     *   "timezone": "Asia/Dubai",
     *   "date_created": "02/26/2026",
     *   "is_upgraded": 1
     * }
     */
    private Project map(JsonNode n) {
        Project p = new Project();

        // PRIMARY FIX: CxAlloy uses "project_id", not "id"
        String extId = getAsText(n, "project_id",
                       getAsText(n, "id",
                       getAsText(n, "_id", null)));
        p.setExternalId(extId);

        p.setAccountId(getAsText(n, "account_id", null));
        p.setName(getAsText(n, "name", getAsText(n, "project_name", "Unknown")));
        p.setNumber(getAsText(n, "number", null));
        p.setStatus(getAsText(n, "status", null));
        p.setClient(getAsText(n, "client", null));
        p.setBuildingOwner(getAsText(n, "building_owner", null));
        p.setLocation(getAsText(n, "location", null));
        p.setSize(getAsText(n, "size", null));
        p.setCost(getAsText(n, "cost", null));
        p.setPhase(getAsText(n, "phase", null));
        p.setTimezone(getAsText(n, "timezone", null));
        p.setDescription(getAsText(n, "description", null));
        p.setOwner(getAsText(n, "owner", getAsText(n, "created_by", null)));
        p.setCreatedAt(getAsText(n, "date_created", getAsText(n, "created_at", null)));
        p.setUpdatedAt(getAsText(n, "updated_at", null));

        // is_upgraded comes as an integer (0/1) — store as boolean
        if (n.has("is_upgraded") && !n.get("is_upgraded").isNull()) {
            p.setIsUpgraded(n.get("is_upgraded").asInt() == 1);
        }

        p.setRawJson(n.toString());
        p.setSyncedAt(now());

        logger.debug("Mapped project: externalId='{}' name='{}'", extId, p.getName());
        return p;
    }

    private void upsert(Project p) {
        if (p.getExternalId() == null || p.getExternalId().isBlank()) {
            logger.warn("Skipping project with null externalId — raw JSON: {}", p.getRawJson());
            return;
        }
        projectRepository.findByExternalId(p.getExternalId()).ifPresentOrElse(existing -> {
            existing.setName(p.getName());
            existing.setAccountId(p.getAccountId());
            existing.setNumber(p.getNumber());
            existing.setStatus(p.getStatus());
            existing.setClient(p.getClient());
            existing.setBuildingOwner(p.getBuildingOwner());
            existing.setLocation(p.getLocation());
            existing.setSize(p.getSize());
            existing.setCost(p.getCost());
            existing.setPhase(p.getPhase());
            existing.setTimezone(p.getTimezone());
            existing.setDescription(p.getDescription());
            existing.setOwner(p.getOwner());
            existing.setUpdatedAt(p.getUpdatedAt());
            existing.setIsUpgraded(p.getIsUpgraded());
            existing.setRawJson(p.getRawJson());
            existing.setSyncedAt(now());
            projectRepository.save(existing);
        }, () -> projectRepository.save(p));
    }
}
