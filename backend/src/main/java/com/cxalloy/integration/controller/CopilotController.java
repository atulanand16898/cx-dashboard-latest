package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.CopilotChatRequest;
import com.cxalloy.integration.service.CopilotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/copilot")
public class CopilotController {

    private final CopilotService copilotService;
    private final ObjectMapper objectMapper;

    public CopilotController(CopilotService copilotService, ObjectMapper objectMapper) {
        this.copilotService = copilotService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/context")
    public ResponseEntity<ApiResponse<Map<String, Object>>> context(
            @RequestParam(required = false) List<String> projectIds,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "true") boolean includeProjectFiles
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                copilotService.getWorkspaceContext(projectIds, query, includeProjectFiles),
                "Copilot workspace context"
        ));
    }

    @GetMapping("/config")
    public ResponseEntity<ApiResponse<Map<String, Object>>> config() {
        return ResponseEntity.ok(ApiResponse.success(
                copilotService.getCopilotConfig(),
                "Copilot configuration"
        ));
    }

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chat(
            @RequestPart("payload") String payload,
            @RequestPart(value = "files", required = false) List<MultipartFile> files
    ) throws Exception {
        CopilotChatRequest request = objectMapper.readValue(payload, CopilotChatRequest.class);
        return ResponseEntity.ok(ApiResponse.success(
                copilotService.chat(request, files),
                "Copilot response generated"
        ));
    }
}
