package com.cxalloy.integration.service;

import com.cxalloy.integration.client.CxAlloyApiClient;
import com.cxalloy.integration.dto.SyncResult;
import com.cxalloy.integration.model.Project;
import com.cxalloy.integration.model.ProjectedFile;
import com.cxalloy.integration.repository.FileRecordRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final int PAGE_SIZE = 500;

    private final CxAlloyApiClient apiClient;
    private final ProjectService projectService;
    private final FileRecordRepository fileRecordRepository;
    private final ObjectMapper objectMapper;
    // internal calls (enables @Transactional(REQUIRES_NEW) on syncFilesForProject
    // when called from analyzeFiles). @Lazy breaks the circular-dependency error.
    @Lazy
    @Autowired
    private FileStorageService self;

    public FileStorageService(CxAlloyApiClient apiClient,
                              ProjectService projectService,
                              FileRecordRepository fileRecordRepository,
                              ObjectMapper objectMapper) {
        this.apiClient = apiClient;
        this.projectService = projectService;
        this.fileRecordRepository = fileRecordRepository;
        this.objectMapper = objectMapper;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SYNC — always read-write, evicts all related caches
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "file-report",     key = "#projectId", condition = "#projectId != null"),
        @CacheEvict(value = "file-duplicates", key = "#projectId", condition = "#projectId != null"),
        @CacheEvict(value = "file-largest",    key = "#projectId", condition = "#projectId != null"),
        @CacheEvict(value = "file-heaviest",   key = "#projectId", condition = "#projectId != null"),
    })
    public SyncResult syncFiles(String projectId) {
        long start = System.currentTimeMillis();
        List<String> pids = resolveProjectIds(projectId);
        int total = 0;
        for (String pid : pids) total += self.syncFilesForProject(pid);
        long dur = System.currentTimeMillis() - start;
        log.info("File sync complete: {} files, {} projects, {}ms", total, pids.size(), dur);
        return new SyncResult("/file", "SUCCESS", total, "Synced " + total + " files", dur);
    }

    /**
     * Always runs in its own READ-WRITE transaction, even when called from
     * a read-only parent. REQUIRES_NEW suspends any existing transaction so
     * the INSERT never inherits readOnly=true from the caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int syncFilesForProject(String projectId) {
        int page = 1, totalSynced = 0;
        log.info("File sync start project={}", projectId);
        while (true) {
            String path = "/file?project_id=" + projectId + "&page=" + page;
            try {
                String raw = apiClient.get(path);
                if (raw == null || raw.isBlank()) break;
                List<ProjectedFile> batch = parseAndMapFiles(raw, projectId);
                if (batch.isEmpty()) break;
                upsertBatch(batch);
                totalSynced += batch.size();
                log.debug("Synced {} files project={} page={}", batch.size(), projectId, page);
                if (batch.size() < PAGE_SIZE) break;
                if (++page > 100) { log.warn("Page cap hit project={}", projectId); break; }
            } catch (Exception e) {
                log.error("Failed project={} page={}: {}", projectId, page, e.getMessage());
                break;
            }
        }
        log.info("File sync done project={}: {} files {} pages", projectId, totalSynced, page);
        return totalSynced;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  READ — DB-backed, Spring @Cacheable (Caffeine L1, 10-min TTL)
    //
    //  NOTE: analyzeFiles and getReport do NOT use readOnly=true because they
    //  may trigger an auto-sync (INSERT) on first access. Using readOnly=true
    //  here caused "cannot execute INSERT in a read-only transaction".
    // ══════════════════════════════════════════════════════════════════════════

    @Cacheable(value = "file-report", key = "#projectId")
    public Map<String, Object> analyzeFiles(String projectId) {
        long start = System.currentTimeMillis();
        List<String> pids = resolveProjectIds(projectId);

        // Step 1: For each project, sync if empty — each sync runs in its OWN short transaction.
        // We do NOT hold a DB connection open during the slow CxAlloy HTTP pages.
        for (String pid : pids) {
            boolean empty = self.isProjectEmpty(pid);
            if (empty) {
                log.info("No files in DB for project {} — auto-syncing on first access", pid);
                self.syncFilesForProject(pid);
            }
        }

        // Step 2: Load all files in a fresh short read-only transaction.
        List<ProjectedFile> all = self.loadAllFiles(pids);

        Map<String, Object> report = buildReport(all, pids);
        report.put("durationMs", System.currentTimeMillis() - start);
        report.put("analyzedAt", Instant.now().toString());
        report.put("servedFrom", "database");
        return report;
    }

    @Cacheable(value = "file-report", key = "#projectId")
    public Map<String, Object> getReport(String projectId) {
        return self.analyzeFiles(projectId);
    }

    /** Short read-only transaction — just checks count. */
    @Transactional(readOnly = true)
    public boolean isProjectEmpty(String projectId) {
        return fileRecordRepository.findByProjectId(projectId).isEmpty();
    }

    /** Short read-only transaction — loads files after sync is complete. */
    @Transactional(readOnly = true)
    public List<ProjectedFile> loadAllFiles(List<String> pids) {
        List<ProjectedFile> all = new ArrayList<>();
        for (String pid : pids) all.addAll(fileRecordRepository.findByProjectId(pid));
        return all;
    }

    @Cacheable(value = "file-duplicates", key = "#projectId")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getDuplicates(String projectId) {
        return findDuplicates(loadFromDb(projectId));
    }

    @Cacheable(value = "file-largest", key = "#projectId + '-' + #limit")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getLargestFiles(String projectId, int limit) {
        return loadFromDb(projectId).stream()
                .filter(f -> f.getFileSize() != null)
                .sorted(Comparator.comparingLong(f -> -f.getFileSize()))
                .limit(limit).map(this::toSlimMap).collect(Collectors.toList());
    }

    @Cacheable(value = "file-heaviest", key = "#projectId + '-' + #limit")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getHeaviestAssets(String projectId, int limit) {
        return buildAssetSummaries(loadFromDb(projectId), limit);
    }

    @Caching(evict = {
        @CacheEvict(value = "file-report",     allEntries = true),
        @CacheEvict(value = "file-duplicates", allEntries = true),
        @CacheEvict(value = "file-largest",    allEntries = true),
        @CacheEvict(value = "file-heaviest",   allEntries = true),
    })
    public void evictCache(String projectId) {
        log.info("Spring cache evicted for project {}", projectId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private List<ProjectedFile> loadFromDb(String projectId) {
        List<ProjectedFile> all = new ArrayList<>();
        for (String pid : resolveProjectIds(projectId))
            all.addAll(fileRecordRepository.findByProjectId(pid));
        return all;
    }

    @SuppressWarnings("unchecked")
    private List<ProjectedFile> parseAndMapFiles(String raw, String projectId) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode data = root.isArray() ? root : (root.has("data") ? root.get("data") : root);
            if (!data.isArray()) return Collections.emptyList();
            List<Map<String, Object>> maps = objectMapper.convertValue(data,
                    new TypeReference<List<Map<String, Object>>>() {});
            return maps.stream().map(m -> mapToEntity(m, projectId)).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Parse error: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private ProjectedFile mapToEntity(Map<String, Object> m, String projectId) {
        ProjectedFile f = new ProjectedFile();
        f.setExternalId(str(m, "id", str(m, "file_id", null)));
        f.setProjectId(projectId);
        f.setName(str(m, "name", str(m, "filename", null)));
        f.setFileSize(toLong(m.get("file_size")));
        f.setMimeType(str(m, "mime_type", str(m, "content_type", null)));
        f.setAssetType(str(m, "asset_type", null));
        f.setAssetId(str(m, "asset_id", null));
        f.setSha256(str(m, "sha256", str(m, "checksum", null)));
        f.setUrl(str(m, "url", str(m, "download_url", null)));
        f.setCreatedDate(str(m, "created_date", str(m, "date_created", null)));
        try { f.setRawJson(objectMapper.writeValueAsString(m)); } catch (Exception ignore) {}
        f.setSyncedAt(LocalDateTime.now());
        return f;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void upsertBatch(List<ProjectedFile> batch) {
        for (ProjectedFile f : batch) {
            if (f.getExternalId() != null) {
                fileRecordRepository.findByExternalId(f.getExternalId()).ifPresentOrElse(ex -> {
                    ex.setName(f.getName()); ex.setFileSize(f.getFileSize());
                    ex.setMimeType(f.getMimeType()); ex.setAssetType(f.getAssetType());
                    ex.setAssetId(f.getAssetId()); ex.setSha256(f.getSha256());
                    ex.setUrl(f.getUrl()); ex.setRawJson(f.getRawJson());
                    ex.setSyncedAt(LocalDateTime.now());
                    fileRecordRepository.save(ex);
                }, () -> fileRecordRepository.save(f));
            } else {
                fileRecordRepository.save(f);
            }
        }
    }

    private Map<String, Object> buildReport(List<ProjectedFile> files, List<String> pids) {
        long totalBytes = files.stream().mapToLong(f -> f.getFileSize() != null ? f.getFileSize() : 0L).sum();

        Map<String, Long> byExt = files.stream().collect(Collectors.groupingBy(
                f -> extractExt(f.getName()), Collectors.counting()));
        Map<String, Long> byExtBytes = files.stream().filter(f -> f.getFileSize() != null)
                .collect(Collectors.groupingBy(
                        f -> extractExt(f.getName()).toUpperCase(),
                        Collectors.summingLong(ProjectedFile::getFileSize)));
        Map<String, Long> byMime = files.stream().collect(Collectors.groupingBy(
                f -> f.getMimeType() != null ? f.getMimeType() : "unknown", Collectors.counting()));

        List<Map<String, Object>> dups = findDuplicates(files);
        long dupCount = dups.stream().mapToLong(g -> toLong(((List<?>) g.get("files")).size()) - 1L).sum();
        long wasteBytes = dups.stream().mapToLong(g -> toLong(g.get("wastedBytes"))).sum();
        double uniqueness = totalBytes > 0
                ? Math.round(((double)(totalBytes - wasteBytes) / totalBytes) * 1000.0) / 10.0 : 100.0;

        List<Map<String, Object>> top5Assets = buildAssetSummaries(files, 5).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String t = String.valueOf(s.getOrDefault("asset_type","Asset"));
            m.put("name", t.length() > 22 ? t.substring(0,22)+"…" : t);
            m.put("bytes", s.get("total_size_bytes"));
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("projectIds", pids);
        r.put("totalFiles", (long) files.size());
        r.put("checklistsScanned", (long) files.size());
        r.put("totalSizeBytes", totalBytes);
        r.put("totalSizeMb", Math.round(totalBytes / 1024.0 / 1024.0 * 100.0) / 100.0);
        r.put("duplicateFileCount", dupCount);
        r.put("uniqueFileCount", (long) files.size() - dupCount);
        r.put("wasteBytes", wasteBytes);
        r.put("uniquenessPercent", uniqueness);
        r.put("redundantCopies", dupCount);
        r.put("dupGroups", (long) dups.size());
        r.put("byMimeType", byMime);
        r.put("byExtension", byExt);
        r.put("byExtensionBytes", byExtBytes);
        r.put("largestFiles", buildLargestList(files, 10));
        r.put("heaviestAssets", buildAssetSummaries(files, 5));
        r.put("top5Assets", top5Assets);
        return r;
    }

    private List<Map<String, Object>> buildLargestList(List<ProjectedFile> files, int limit) {
        return files.stream().filter(f -> f.getFileSize() != null)
                .sorted(Comparator.comparingLong(f -> -f.getFileSize()))
                .limit(limit).map(this::toSlimMap).collect(Collectors.toList());
    }

    private Map<String, Object> toSlimMap(ProjectedFile f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getExternalId()); m.put("name", f.getName());
        m.put("sizeBytes", f.getFileSize()); m.put("file_size", f.getFileSize());
        m.put("mime_type", f.getMimeType()); m.put("asset_type", f.getAssetType());
        m.put("asset_id", f.getAssetId()); m.put("project_id", f.getProjectId());
        m.put("sha256", f.getSha256());
        m.put("ext", extractExt(f.getName()).toUpperCase());
        return m;
    }

    private List<Map<String, Object>> findDuplicates(List<ProjectedFile> files) {
        return files.stream().filter(f -> f.getSha256() != null && !f.getSha256().isBlank())
                .collect(Collectors.groupingBy(ProjectedFile::getSha256))
                .entrySet().stream().filter(e -> e.getValue().size() > 1)
                .map(e -> {
                    List<ProjectedFile> g = e.getValue();
                    long unit = g.get(0).getFileSize() != null ? g.get(0).getFileSize() : 0L;
                    Map<String, Object> gm = new LinkedHashMap<>();
                    gm.put("sha256", e.getKey()); gm.put("count", g.size());
                    gm.put("wastedBytes", (long)(g.size()-1)*unit);
                    gm.put("files", g.stream().map(this::toSlimMap).collect(Collectors.toList()));
                    return gm;
                })
                .sorted(Comparator.comparingLong(g -> -toLong(g.get("wastedBytes"))))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildAssetSummaries(List<ProjectedFile> files, int limit) {
        return files.stream()
                .filter(f -> f.getAssetType() != null && f.getAssetId() != null)
                .collect(Collectors.groupingBy(f -> f.getAssetType() + "::" + f.getAssetId()))
                .entrySet().stream().map(e -> {
                    List<ProjectedFile> g = e.getValue();
                    long total = g.stream().mapToLong(f -> f.getFileSize()!=null?f.getFileSize():0L).sum();
                    String[] parts = e.getKey().split("::");
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("asset_type", parts[0]);
                    s.put("asset_id", parts.length > 1 ? parts[1] : null);
                    s.put("file_count", g.size()); s.put("total_size_bytes", total);
                    s.put("total_size_mb", Math.round(total/1024.0/1024.0*100.0)/100.0);
                    s.put("project_id", g.get(0).getProjectId());
                    return s;
                })
                .sorted(Comparator.comparingLong(s -> -toLong(s.get("total_size_bytes"))))
                .limit(limit).collect(Collectors.toList());
    }

    private List<String> resolveProjectIds(String pid) {
        if (pid != null && !pid.isBlank()) return List.of(pid.trim());
        return projectService.getAll().stream()
                .map(Project::getExternalId).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private long toLong(Object v) {
        if (v==null) return 0L;
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0L; }
    }

    private String str(Map<String,Object> m, String k, String def) {
        Object v = m.get(k);
        return (v!=null && !String.valueOf(v).isBlank() && !"null".equals(String.valueOf(v)))
                ? String.valueOf(v) : def;
    }

    private String extractExt(String name) {
        if (name==null || name.isBlank()) return "no_ext";
        int d = name.lastIndexOf('.');
        return (d>=0 && d<name.length()-1) ? name.substring(d+1).toLowerCase() : "no_ext";
    }
}
