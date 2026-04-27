package com.cxalloy.integration.xerprocessing.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record XerDcmaCheckpointResponse(
        String overallStatus,
        int passCount,
        int failCount,
        int schedulableActivityCount,
        int relationshipCount,
        Long importSessionId,
        List<CheckpointDetail> checkpoints
) {
    public record CheckpointDetail(
            int checkpointId,
            String name,
            String status,
            double score,
            double threshold,
            int violatingCount,
            int totalCount,
            JsonNode exceptions,
            JsonNode extra
    ) {}
}
