package com.cxalloy.integration.service;

import com.cxalloy.integration.client.FacilityGridApiClient;
import com.cxalloy.integration.config.FacilityGridApiProperties;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.ApiSyncLog;
import com.cxalloy.integration.model.Equipment;
import com.cxalloy.integration.repository.EquipmentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class FacilityGridEquipmentService extends BaseProjectService {

    private static final int PAGE_LIMIT = 5000;

    private final EquipmentRepository equipmentRepository;
    private final FacilityGridApiClient facilityGridApiClient;
    private final FacilityGridApiProperties facilityGridApiProperties;
    private final FacilityGridAuthService facilityGridAuthService;

    public FacilityGridEquipmentService(EquipmentRepository equipmentRepository,
                                        FacilityGridApiClient facilityGridApiClient,
                                        FacilityGridApiProperties facilityGridApiProperties,
                                        FacilityGridAuthService facilityGridAuthService) {
        this.equipmentRepository = equipmentRepository;
        this.facilityGridApiClient = facilityGridApiClient;
        this.facilityGridApiProperties = facilityGridApiProperties;
        this.facilityGridAuthService = facilityGridAuthService;
    }

    @Caching(evict = {
        @CacheEvict(value = "equipment-by-project", allEntries = true),
        @CacheEvict(value = "equipment-all", allEntries = true)
    })
    public SyncResult syncEquipment(String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        String endpoint = equipmentPath(pid);
        ApiSyncLog log = startLog(endpoint, "GET");
        int totalSynced = 0;

        try {
            String accessToken = facilityGridAuthService.requestConfiguredAccessToken();
            Map<String, String> equipmentTypes = fetchEquipmentTypes(pid, accessToken);

            int offset = 0;
            while (true) {
                String pagePath = paged(endpoint, PAGE_LIMIT, offset);
                String raw = facilityGridApiClient.get(pagePath, accessToken);
                saveRaw(pagePath, "facilitygrid_equipment_list_o" + offset, pid, raw);

                int count = parseAndSave(raw, pid, equipmentTypes);
                totalSynced += count;
                if (count < PAGE_LIMIT) {
                    break;
                }
                offset += PAGE_LIMIT;
            }

            long duration = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", totalSynced, duration, null);
            return new SyncResult(endpoint, "SUCCESS", totalSynced,
                    "Synced " + totalSynced + " Facility Grid equipment records", duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", totalSynced, duration, e.getMessage());
            throw new RuntimeException("Facility Grid equipment sync failed: " + e.getMessage(), e);
        }
    }

    public List<Equipment> fetchLive(String projectId) {
        String pid = resolveProjectId(projectId);
        try {
            String accessToken = facilityGridAuthService.requestConfiguredAccessToken();
            Map<String, String> equipmentTypes = fetchEquipmentTypes(pid, accessToken);
            String raw = facilityGridApiClient.get(paged(equipmentPath(pid), 500, 0), accessToken);
            JsonNode root = objectMapper.readTree(raw);
            List<Equipment> rows = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    rows.add(map(node, pid, equipmentTypes));
                }
            }
            return rows;
        } catch (Exception e) {
            logger.warn("Facility Grid live equipment fetch failed for project {}: {}", pid, e.getMessage());
            return List.of();
        }
    }

    private Map<String, String> fetchEquipmentTypes(String projectId, String accessToken) throws Exception {
        Map<String, String> lookup = new LinkedHashMap<>();
        int offset = 0;
        while (true) {
            String pagePath = paged(equipmentTypesPath(projectId), PAGE_LIMIT, offset);
            String raw = facilityGridApiClient.get(pagePath, accessToken);
            saveRaw(pagePath, "facilitygrid_equipment_types_o" + offset, projectId, raw);

            JsonNode root = objectMapper.readTree(raw);
            if (!root.isArray()) {
                break;
            }
            for (JsonNode node : root) {
                String uuid = getAsText(node, "uuid", null);
                String typeName = getAsText(node, "type_name", null);
                if (StringUtils.hasText(uuid) && StringUtils.hasText(typeName)) {
                    lookup.put(uuid, typeName);
                }
            }
            if (root.size() < PAGE_LIMIT) {
                break;
            }
            offset += PAGE_LIMIT;
        }
        return lookup;
    }

    private int parseAndSave(String json, String projectId, Map<String, String> equipmentTypes) throws Exception {
        if (!StringUtils.hasText(json)) {
            return 0;
        }
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) {
            throw new IllegalStateException("Facility Grid equipment response was not an array");
        }

        List<Equipment> rows = new ArrayList<>();
        for (JsonNode node : root) {
            rows.add(map(node, projectId, equipmentTypes));
        }
        rows.forEach(this::upsert);
        return rows.size();
    }

    private Equipment map(JsonNode node, String projectId, Map<String, String> equipmentTypes) {
        Equipment equipment = new Equipment();

        String externalId = getAsText(node, "uuid", null);
        equipment.setExternalId(externalId);
        equipment.setProvider(currentProviderKey());
        equipment.setSourceKey(sourceKeyFor(externalId));
        equipment.setProjectId(projectId);
        equipment.setName(firstNonBlank(getAsText(node, "sched_name", null), getAsText(node, "unit_number", null), externalId));
        equipment.setTag(getAsText(node, "unit_number", null));
        equipment.setDescription(firstNonBlank(getAsText(node, "notes", null), getAsText(node, "drawing_number", null)));
        equipment.setStatus(null);
        equipment.setEquipmentType(resolveEquipmentType(node, equipmentTypes));
        equipment.setDiscipline(getAsText(node, "discipline", null));

        String location = firstNonBlank(getAsText(node, "location", null), firstServiceAreaLocation(node));
        String[] parts = splitLocation(location);
        equipment.setBuildingId(parts[0]);
        equipment.setFloorId(parts[1]);
        equipment.setSpaceId(location);

        JsonNode systems = node.get("systems");
        if (systems != null && systems.isArray() && systems.size() > 0) {
            JsonNode first = systems.get(0);
            equipment.setSystemId(first.isObject() ? getAsText(first, "uuid", null) : first.asText(null));
            equipment.setSystemName(first.isObject() ? firstNonBlank(getAsText(first, "name", null), getAsText(first, "title", null)) : first.asText(null));
        }

        equipment.setCreatedAt(getAsText(node, "installation_date", null));
        equipment.setUpdatedAt(getAsText(node, "warranty_start_date", null));
        equipment.setRawJson(node.toString());
        equipment.setSyncedAt(now());
        return equipment;
    }

    private void upsert(Equipment equipment) {
        if (!StringUtils.hasText(equipment.getExternalId())) {
            logger.warn("Skipping Facility Grid equipment row with null externalId");
            return;
        }

        equipmentRepository.findBySourceKey(equipment.getSourceKey()).ifPresentOrElse(existing -> {
            existing.setProvider(equipment.getProvider());
            existing.setSourceKey(equipment.getSourceKey());
            existing.setProjectId(equipment.getProjectId());
            existing.setName(equipment.getName());
            existing.setTag(equipment.getTag());
            existing.setDescription(equipment.getDescription());
            existing.setStatus(equipment.getStatus());
            existing.setEquipmentType(equipment.getEquipmentType());
            existing.setDiscipline(equipment.getDiscipline());
            existing.setBuildingId(equipment.getBuildingId());
            existing.setSystemId(equipment.getSystemId());
            existing.setSystemName(equipment.getSystemName());
            existing.setFloorId(equipment.getFloorId());
            existing.setSpaceId(equipment.getSpaceId());
            existing.setChecklistCount(equipment.getChecklistCount());
            existing.setIssueCount(equipment.getIssueCount());
            existing.setTestCount(equipment.getTestCount());
            existing.setCreatedAt(equipment.getCreatedAt());
            existing.setUpdatedAt(equipment.getUpdatedAt());
            existing.setRawJson(equipment.getRawJson());
            existing.setSyncedAt(equipment.getSyncedAt());
            equipmentRepository.save(existing);
        }, () -> equipmentRepository.save(equipment));
    }

    private String resolveEquipmentType(JsonNode node, Map<String, String> equipmentTypes) {
        String uuid = getAsText(node, "equipment_type_uuid", null);
        String resolved = StringUtils.hasText(uuid) ? equipmentTypes.get(uuid) : null;
        if (StringUtils.hasText(resolved)) {
            return resolved;
        }
        return getAsText(node, "sched_name", null);
    }

    private String firstServiceAreaLocation(JsonNode node) {
        JsonNode areas = node.get("service_area");
        if (areas != null && areas.isArray() && areas.size() > 0) {
            JsonNode first = areas.get(0);
            if (first.isObject()) {
                return getAsText(first, "location", null);
            }
            return first.asText(null);
        }
        return null;
    }

    private String[] splitLocation(String location) {
        if (!StringUtils.hasText(location)) {
            return new String[]{null, null};
        }
        String[] tokens = java.util.Arrays.stream(location.split("/"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toArray(String[]::new);
        String building = tokens.length > 0 ? tokens[0] : null;
        String floor = tokens.length > 1 ? tokens[1] : null;
        return new String[]{building, floor};
    }

    private String equipmentPath(String projectId) {
        return expandProjectPath(
                facilityGridApiProperties.getEquipmentPathTemplate(),
                projectId,
                "/api/v2_2/project/{project_id}/equipment");
    }

    private String equipmentTypesPath(String projectId) {
        return expandProjectPath(
                facilityGridApiProperties.getEquipmentTypesPathTemplate(),
                projectId,
                "/api/v2_2/project/{project_id}/equipment_types");
    }

    private String expandProjectPath(String template, String projectId, String fallback) {
        String value = StringUtils.hasText(template) ? template : fallback;
        return value.replace("{project_id}", projectId);
    }

    private String paged(String path, int limit, int offset) {
        String separator = path.contains("?") ? "&" : "?";
        return path + separator + "limit=" + limit + "&offset=" + offset;
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
