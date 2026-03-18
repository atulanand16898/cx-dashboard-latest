package com.cxalloy.integration.service;

import com.cxalloy.integration.config.CxAlloyApiProperties;
import com.cxalloy.integration.model.ApiSyncLog;
import com.cxalloy.integration.model.RawApiResponse;
import com.cxalloy.integration.repository.ApiSyncLogRepository;
import com.cxalloy.integration.repository.RawApiResponseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

/**
 * Base class providing shared helpers for all project-scoped services.
 * Most CxAlloy endpoints require ?project_id=<id> as a query param.
 */
public abstract class BaseProjectService {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired protected CxAlloyApiProperties apiProperties;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected ApiSyncLogRepository syncLogRepository;
    @Autowired protected RawApiResponseRepository rawApiResponseRepository;

    /**
     * Resolves the project_id to use.
     * Priority: passed-in value FIRST, then application.properties fallback.
     * This ensures per-project iteration in SyncService always uses the correct ID.
     */
    protected String resolveProjectId(String projectId) {
        if (projectId != null && !projectId.isBlank()) return projectId.trim();
        String configured = apiProperties.getProjectId();
        if (configured != null && !configured.isBlank()) return configured.trim();
        throw new IllegalStateException(
            "No project_id available. Either pass one explicitly or set cxalloy.api.project-id in application.properties.");
    }

    /**
     * Robustly extracts the data array/object from a CxAlloy JSON response.
     * Tries multiple common wrapper keys: data, items, results, records, response.
     * Falls back to the root node itself if it is an array.
     */
    protected JsonNode extractData(JsonNode root, String context) {
        for (String key : new String[]{"data", "items", "results", "records", "response"}) {
            if (root.has(key) && !root.get(key).isNull()) {
                JsonNode node = root.get(key);
                if (node.isArray() || node.isObject()) {
                    logger.debug("[{}] Found data under key '{}'", context, key);
                    return node;
                }
            }
        }
        if (root.isArray()) {
            logger.debug("[{}] Root node is array, using directly", context);
            return root;
        }
        logger.warn("[{}] Could not find data array in response. Root keys: {}", context, root.fieldNames());
        return root; // return root as last resort; parseAndSave will handle empty gracefully
    }

    protected String getAsText(JsonNode node, String field, String defaultVal) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return defaultVal;
    }

    protected void saveRaw(String endpoint, String type, String externalId, String body) {
        RawApiResponse raw = new RawApiResponse(endpoint, type, body);
        raw.setExternalId(externalId);
        rawApiResponseRepository.save(raw);
    }

    protected ApiSyncLog startLog(String endpoint, String method) {
        return new ApiSyncLog(endpoint, method);
    }

    protected void finishLog(ApiSyncLog log, String status, int count, long duration, String error) {
        log.setStatus(status);
        log.setRecordsSynced(count);
        log.setDurationMs(duration);
        log.setErrorMessage(error);
        syncLogRepository.save(log);
    }

    protected LocalDateTime now() { return LocalDateTime.now(); }
}
