package com.cxalloy.integration.service;

import com.cxalloy.integration.client.CxAlloyApiClient;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.ApiSyncLog;
import com.cxalloy.integration.model.Person;
import com.cxalloy.integration.repository.PersonRepository;
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
public class PersonService extends BaseProjectService {

    private final PersonRepository personRepository;
    private final CxAlloyApiClient apiClient;

    public PersonService(PersonRepository personRepository, CxAlloyApiClient apiClient) {
        this.personRepository = personRepository;
        this.apiClient = apiClient;
    }

    @Caching(evict = {
        @CacheEvict(value = "persons-by-project", allEntries = true),
        @CacheEvict(value = "persons-all", allEntries = true)
    })
    public SyncResult syncPersons(String projectId) {
        long start = System.currentTimeMillis();
        String pid = resolveProjectId(projectId);
        ApiSyncLog log = startLog("/person", "GET");
        int totalSynced = 0; int page = 1;
        try {
            while (true) {
                String url = "/person?project_id=" + pid + (page > 1 ? "&page=" + page : "");
                String raw = apiClient.get(url);
                if (raw == null || raw.isBlank()) break;
                if (raw.trim().startsWith("{") && !raw.contains("\"data\"") &&
                    (raw.contains("\"error\"") || raw.contains("\"message\""))) break;
                saveRaw("/person?page=" + page, "persons_list_p" + page, pid, raw);
                int count = parseAndSave(raw, pid);
                totalSynced += count;
                if (count < 500) break;
                if (++page > 50) break;
            }
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "SUCCESS", totalSynced, dur, null);
            return new SyncResult("/person", "SUCCESS", totalSynced,
                "Synced " + totalSynced + " persons (" + page + " pages)", dur);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            finishLog(log, "FAILED", 0, dur, e.getMessage());
            throw new RuntimeException("Sync persons failed: " + e.getMessage(), e);
        }
    }

    @Cacheable(value = "persons-all")
    @Transactional(readOnly = true)
    public List<Person> getAll() { return personRepository.findAll(); }

    @Cacheable(value = "entity-by-id", key = "\"persons-\" + #id")
    @Transactional(readOnly = true)
    public Optional<Person> getById(Long id) { return personRepository.findById(id); }

    @Cacheable(value = "persons-by-project", key = "#projectId")
    @Transactional(readOnly = true)
    public List<Person> getByProject(String projectId) { return personRepository.findByProjectId(projectId); }

    private int parseAndSave(String json, String projectId) throws Exception {
        if (json == null || json.isBlank()) { logger.warn("Empty response for /person project={}", projectId); return 0; }
        JsonNode root = objectMapper.readTree(json);
        JsonNode data = extractData(root, "/person project=" + projectId);
        List<Person> list = new ArrayList<>();
        if (data.isArray()) { for (JsonNode n : data) list.add(map(n, projectId)); }
        else if (data.isObject()) list.add(map(data, projectId));
        list.forEach(this::upsert);
        logger.info("Parsed {} persons for project {}", list.size(), projectId);
        return list.size();
    }

    private Person map(JsonNode n, String projectId) {
        Person p = new Person();
        p.setExternalId(getAsText(n, "id", getAsText(n, "_id", null)));
        p.setProjectId(projectId);
        p.setFirstName(getAsText(n, "first_name", getAsText(n, "firstName", null)));
        p.setLastName(getAsText(n, "last_name", getAsText(n, "lastName", null)));
        p.setEmail(getAsText(n, "email", null));
        p.setCompany(getAsText(n, "company", null));
        p.setRole(getAsText(n, "role", null));
        p.setStatus(getAsText(n, "status", null));
        p.setCreatedAt(getAsText(n, "created_at", getAsText(n, "date_created", null)));
        p.setRawJson(n.toString());
        p.setSyncedAt(now());
        return p;
    }

    private void upsert(Person p) {
        if (p.getExternalId() != null) {
            personRepository.findByExternalId(p.getExternalId()).ifPresentOrElse(existing -> {
                existing.setFirstName(p.getFirstName()); existing.setLastName(p.getLastName());
                existing.setEmail(p.getEmail()); existing.setCompany(p.getCompany());
                existing.setRole(p.getRole()); existing.setStatus(p.getStatus());
                existing.setRawJson(p.getRawJson()); existing.setSyncedAt(now());
                personRepository.save(existing);
            }, () -> personRepository.save(p));
        } else { personRepository.save(p); }
    }
}
