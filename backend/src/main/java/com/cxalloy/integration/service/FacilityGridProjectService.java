package com.cxalloy.integration.service;

import com.cxalloy.integration.client.FacilityGridApiClient;
import com.cxalloy.integration.config.FacilityGridApiProperties;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.ApiSyncLog;
import com.cxalloy.integration.model.Project;
import com.cxalloy.integration.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
public class FacilityGridProjectService extends BaseProjectService {

    private final ProjectRepository projectRepository;
    private final FacilityGridApiClient facilityGridApiClient;
    private final FacilityGridApiProperties facilityGridApiProperties;
    private final FacilityGridAuthService facilityGridAuthService;

    public FacilityGridProjectService(ProjectRepository projectRepository,
                                      FacilityGridApiClient facilityGridApiClient,
                                      FacilityGridApiProperties facilityGridApiProperties,
                                      FacilityGridAuthService facilityGridAuthService) {
        this.projectRepository = projectRepository;
        this.facilityGridApiClient = facilityGridApiClient;
        this.facilityGridApiProperties = facilityGridApiProperties;
        this.facilityGridAuthService = facilityGridAuthService;
    }

    @Caching(evict = {
        @CacheEvict(value = "projects-all", allEntries = true),
        @CacheEvict(value = "projects-by-id", allEntries = true),
        @CacheEvict(value = "projects-external", allEntries = true)
    })
    public SyncResult syncAllProjects() {
        long start = System.currentTimeMillis();
        String endpoint = projectsPath();
        ApiSyncLog log = startLog(endpoint, "GET");
        try {
            String accessToken = facilityGridAuthService.requestConfiguredAccessToken();
            String raw = facilityGridApiClient.get(endpoint, accessToken);
            saveRaw(endpoint, "facilitygrid_projects_list", null, raw);
            int count = parseAndSave(raw);
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", count, dur, null);
            logger.info("Synced {} projects from Facility Grid", count);
            return new SyncResult(endpoint, "SUCCESS", count, "Synced " + count + " Facility Grid projects", dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", 0, dur, e.getMessage());
            throw new RuntimeException("Facility Grid project sync failed: " + e.getMessage(), e);
        }
    }

    @Caching(evict = {
        @CacheEvict(value = "projects-all", allEntries = true),
        @CacheEvict(value = "projects-by-id", allEntries = true),
        @CacheEvict(value = "projects-external", allEntries = true)
    })
    public SyncResult syncProjectById(String projectId) {
        long start = System.currentTimeMillis();
        String endpoint = projectsPath();
        ApiSyncLog log = startLog(endpoint, "GET");
        try {
            String accessToken = facilityGridAuthService.requestConfiguredAccessToken();
            String raw = facilityGridApiClient.get(endpoint, accessToken);
            saveRaw(endpoint, "facilitygrid_project_single", projectId, raw);
            JsonNode root = objectMapper.readTree(raw);
            JsonNode projectNode = findProjectNode(root, projectId);
            if (projectNode == null) {
                throw new RuntimeException("Facility Grid project not found: " + projectId);
            }
            upsert(map(projectNode));
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", 1, dur, null);
            return new SyncResult(endpoint, "SUCCESS", 1, "Facility Grid project synced", dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", 0, dur, e.getMessage());
            throw new RuntimeException("Facility Grid project sync failed: " + e.getMessage(), e);
        }
    }

    private int parseAndSave(String json) throws Exception {
        if (json == null || json.isBlank()) {
            logger.warn("Empty response from {}", projectsPath());
            return 0;
        }
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) {
            throw new RuntimeException("Facility Grid projects response was not an array");
        }

        List<Project> projects = new ArrayList<>();
        for (JsonNode node : root) {
            projects.add(map(node));
        }
        projects.forEach(this::upsert);
        return projects.size();
    }

    private JsonNode findProjectNode(JsonNode root, String projectId) {
        if (!root.isArray()) {
            return null;
        }
        for (JsonNode node : root) {
            String id = getAsText(node, "id", null);
            if (projectId.equals(id)) {
                return node;
            }
        }
        return null;
    }

    private Project map(JsonNode node) {
        Project project = new Project();

        String externalId = getAsText(node, "id", null);
        project.setExternalId(externalId);
        project.setProvider(currentProviderKey());
        project.setSourceKey(sourceKeyFor(externalId));
        project.setName(getAsText(node, "name", "Unknown"));
        project.setNumber(getAsText(node, "number", null));
        project.setStatus(getAsText(node, "status", null));
        project.setClient(getAsText(node, "client_name", null));
        project.setLocation(resolveLocation(node));
        project.setSize(resolveSize(node));
        project.setTimezone(getAsText(node, "timezone", null));
        project.setDescription(getAsText(node, "description", null));
        project.setCreatedAt(firstNonBlank(
                getAsText(node, "L2_start_date", null),
                getAsText(node, "start_date", null),
                getAsText(node, "created_at", null)
        ));
        project.setUpdatedAt(getAsText(node, "end_date", null));
        project.setRawJson(node.toString());
        project.setSyncedAt(now());
        return project;
    }

    private void upsert(Project project) {
        if (!StringUtils.hasText(project.getExternalId())) {
            logger.warn("Skipping Facility Grid project with null externalId");
            return;
        }
        projectRepository.findBySourceKey(project.getSourceKey()).ifPresentOrElse(existing -> {
            existing.setExternalId(project.getExternalId());
            existing.setProvider(project.getProvider());
            existing.setSourceKey(project.getSourceKey());
            existing.setName(project.getName());
            existing.setNumber(project.getNumber());
            existing.setStatus(project.getStatus());
            existing.setClient(project.getClient());
            existing.setLocation(project.getLocation());
            existing.setSize(project.getSize());
            existing.setTimezone(project.getTimezone());
            existing.setDescription(project.getDescription());
            existing.setCreatedAt(project.getCreatedAt());
            existing.setUpdatedAt(project.getUpdatedAt());
            existing.setRawJson(project.getRawJson());
            existing.setSyncedAt(project.getSyncedAt());
            projectRepository.save(existing);
        }, () -> projectRepository.save(project));
    }

    private String resolveLocation(JsonNode node) {
        JsonNode building = firstBuilding(node);
        if (building != null) {
            String city = getAsText(building, "city", null);
            String state = getAsText(building, "state_province", null);
            String combined = joinNonBlank(city, state);
            if (StringUtils.hasText(combined)) {
                return combined;
            }
            String buildingName = getAsText(building, "building_name", null);
            if (StringUtils.hasText(buildingName)) {
                return buildingName;
            }
        }
        String region = getAsText(node, "region", null);
        String number = getAsText(node, "number", null);
        if (StringUtils.hasText(region) && !region.equals(number)) {
            return region;
        }
        return null;
    }

    private String resolveSize(JsonNode node) {
        JsonNode grossSf = node.get("gross_sf");
        if (grossSf != null && grossSf.isNumber() && grossSf.asDouble() > 0) {
            return grossSf.asText() + " SF";
        }
        JsonNode building = firstBuilding(node);
        if (building != null) {
            JsonNode areaSf = building.get("area_SF");
            if (areaSf != null && areaSf.isNumber() && areaSf.asDouble() > 0) {
                return areaSf.asText() + " SF";
            }
        }
        return null;
    }

    private JsonNode firstBuilding(JsonNode node) {
        JsonNode buildings = node.get("buildings");
        if (buildings != null && buildings.isArray() && buildings.size() > 0) {
            return buildings.get(0);
        }
        return null;
    }

    private String joinNonBlank(String left, String right) {
        if (StringUtils.hasText(left) && StringUtils.hasText(right)) {
            return left + ", " + right;
        }
        return firstNonBlank(left, right);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String projectsPath() {
        String path = facilityGridApiProperties.getProjectsPath();
        if (!StringUtils.hasText(path)) {
            return "/api/v2_2/projects";
        }
        return path.trim();
    }
}
