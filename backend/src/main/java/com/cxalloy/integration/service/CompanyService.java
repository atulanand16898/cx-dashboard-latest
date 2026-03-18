package com.cxalloy.integration.service;

import com.cxalloy.integration.client.CxAlloyApiClient;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.ApiSyncLog;
import com.cxalloy.integration.model.Company;
import com.cxalloy.integration.repository.CompanyRepository;
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
public class CompanyService extends BaseProjectService {

    private final CompanyRepository companyRepository;
    private final CxAlloyApiClient apiClient;

    public CompanyService(CompanyRepository companyRepository, CxAlloyApiClient apiClient) {
        this.companyRepository = companyRepository;
        this.apiClient = apiClient;
    }

    @Caching(evict = {
        @CacheEvict(value = "companies-by-project", allEntries = true),
        @CacheEvict(value = "companies-all", allEntries = true)
    })
    public SyncResult syncCompanies(String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        ApiSyncLog log = startLog("/company", "GET");
        int totalSynced = 0; int page = 1;
        try {
            while (true) {
                String url = "/company?project_id=" + pid + (page > 1 ? "&page=" + page : "");
                String raw = apiClient.get(url);
                if (raw == null || raw.isBlank()) break;
                if (raw.trim().startsWith("{") && !raw.contains("\"data\"") &&
                    (raw.contains("\"error\"") || raw.contains("\"message\""))) break;
                saveRaw("/company?page=" + page, "companies_list_p" + page, pid, raw);
                int count = parseAndSave(raw, pid);
                totalSynced += count;
                if (count < 500) break;
                if (++page > 50) break;
            }
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", totalSynced, dur, null);
            return new SyncResult("/company", "SUCCESS", totalSynced,
                "Synced " + totalSynced + " companies (" + page + " pages)", dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", 0, dur, e.getMessage());
            throw new RuntimeException("Sync companies failed: " + e.getMessage(), e);
        }
    }

    @Cacheable(value = "companies-all")
    @Transactional(readOnly = true)
    public List<Company> getAll() { return companyRepository.findAll(); }

    @Cacheable(value = "entity-by-id", key = "\"companies-\" + #id")
    @Transactional(readOnly = true)
    public Optional<Company> getById(Long id) { return companyRepository.findById(id); }

    @Cacheable(value = "companies-by-project", key = "#projectId")
    @Transactional(readOnly = true)
    public List<Company> getByProject(String projectId) { return companyRepository.findByProjectId(projectId); }

    private int parseAndSave(String json, String pid) throws Exception {
        if (json == null || json.isBlank()) { logger.warn("Empty response for /company project={}", pid); return 0; }
        JsonNode root = objectMapper.readTree(json);
        JsonNode data = extractData(root, "/company project=" + pid);
        List<Company> list = new ArrayList<>();
        if (data.isArray()) { for (JsonNode n : data) list.add(map(n, pid)); }
        else if (data.isObject()) list.add(map(data, pid));
        list.forEach(this::upsert);
        logger.info("Parsed {} companys for project {}", list.size(), pid);
        return list.size();
    }

    private Company map(JsonNode n, String pid) {
        Company c = new Company();
        c.setExternalId(getAsText(n, "id", getAsText(n, "_id", null)));
        c.setProjectId(pid);
        c.setName(getAsText(n, "name", null));
        c.setAbbreviation(getAsText(n, "abbreviation", null));
        c.setPhone(getAsText(n, "phone", null));
        c.setAddress(getAsText(n, "address", null));
        c.setCreatedAt(getAsText(n, "created_at", getAsText(n, "date_created", null)));
        c.setRawJson(n.toString());
        c.setSyncedAt(now());
        return c;
    }

    private void upsert(Company c) {
        if (c.getExternalId() != null) {
            companyRepository.findByExternalId(c.getExternalId()).ifPresentOrElse(existing -> {
                existing.setName(c.getName()); existing.setAbbreviation(c.getAbbreviation());
                existing.setPhone(c.getPhone()); existing.setAddress(c.getAddress());
                existing.setRawJson(c.getRawJson()); existing.setSyncedAt(now());
                companyRepository.save(existing);
            }, () -> companyRepository.save(c));
        } else { companyRepository.save(c); }
    }
}
