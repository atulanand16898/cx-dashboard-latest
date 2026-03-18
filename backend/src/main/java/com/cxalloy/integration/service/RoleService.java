package com.cxalloy.integration.service;

import com.cxalloy.integration.client.CxAlloyApiClient;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.ApiSyncLog;
import com.cxalloy.integration.model.Role;
import com.cxalloy.integration.repository.RoleRepository;
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
public class RoleService extends BaseProjectService {

    private final RoleRepository roleRepository;
    private final CxAlloyApiClient apiClient;

    public RoleService(RoleRepository roleRepository, CxAlloyApiClient apiClient) {
        this.roleRepository = roleRepository;
        this.apiClient = apiClient;
    }

    @Caching(evict = {
        @CacheEvict(value = "roles-by-project", allEntries = true),
        @CacheEvict(value = "roles-all", allEntries = true)
    })
    public SyncResult syncRoles(String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        ApiSyncLog log = startLog("/role", "GET");
        int totalSynced = 0; int page = 1;
        try {
            while (true) {
                String url = "/role?project_id=" + pid + (page > 1 ? "&page=" + page : "");
                String raw = apiClient.get(url);
                if (raw == null || raw.isBlank()) break;
                if (raw.trim().startsWith("{") && !raw.contains("\"data\"") &&
                    (raw.contains("\"error\"") || raw.contains("\"message\""))) break;
                saveRaw("/role?page=" + page, "roles_list_p" + page, pid, raw);
                int count = parseAndSave(raw, pid);
                totalSynced += count;
                if (count < 500) break;
                if (++page > 50) break;
            }
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", totalSynced, dur, null);
            return new SyncResult("/role", "SUCCESS", totalSynced,
                "Synced " + totalSynced + " roles (" + page + " pages)", dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", 0, dur, e.getMessage());
            throw new RuntimeException("Sync roles failed: " + e.getMessage(), e);
        }
    }

    @Cacheable(value = "roles-all")
    @Transactional(readOnly = true)
    public List<Role> getAll() { return roleRepository.findAll(); }

    @Cacheable(value = "entity-by-id", key = "\"roles-\" + #id")
    @Transactional(readOnly = true)
    public Optional<Role> getById(Long id) { return roleRepository.findById(id); }

    @Cacheable(value = "roles-by-project", key = "#projectId")
    @Transactional(readOnly = true)
    public List<Role> getByProject(String projectId) { return roleRepository.findByProjectId(projectId); }

    private int parseAndSave(String json, String pid) throws Exception {
        if (json == null || json.isBlank()) { logger.warn("Empty response for /role project={}", pid); return 0; }
        JsonNode root = objectMapper.readTree(json);
        JsonNode data = extractData(root, "/role project=" + pid);
        List<Role> list = new ArrayList<>();
        if (data.isArray()) { for (JsonNode n : data) list.add(map(n, pid)); }
        else if (data.isObject()) list.add(map(data, pid));
        list.forEach(this::upsert);
        logger.info("Parsed {} roles for project {}", list.size(), pid);
        return list.size();
    }

    private Role map(JsonNode n, String pid) {
        Role r = new Role();
        r.setExternalId(getAsText(n, "id", getAsText(n, "_id", null)));
        r.setProjectId(pid);
        r.setName(getAsText(n, "name", null));
        r.setAbbreviation(getAsText(n, "abbreviation", null));
        r.setDescription(getAsText(n, "description", null));
        r.setCreatedAt(getAsText(n, "created_at", getAsText(n, "date_created", null)));
        r.setRawJson(n.toString());
        r.setSyncedAt(now());
        return r;
    }

    private void upsert(Role r) {
        if (r.getExternalId() != null) {
            roleRepository.findByExternalId(r.getExternalId()).ifPresentOrElse(existing -> {
                existing.setName(r.getName()); existing.setAbbreviation(r.getAbbreviation());
                existing.setDescription(r.getDescription()); existing.setRawJson(r.getRawJson());
                existing.setSyncedAt(now()); roleRepository.save(existing);
            }, () -> roleRepository.save(r));
        } else { roleRepository.save(r); }
    }
}
