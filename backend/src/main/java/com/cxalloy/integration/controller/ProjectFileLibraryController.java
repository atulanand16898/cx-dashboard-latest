package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.service.ProjectFileLibraryService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/project-files")
public class ProjectFileLibraryController {

    private final ProjectFileLibraryService projectFileLibraryService;

    public ProjectFileLibraryController(ProjectFileLibraryService projectFileLibraryService) {
        this.projectFileLibraryService = projectFileLibraryService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(@RequestParam String projectId) {
        return ResponseEntity.ok(ApiResponse.success(projectFileLibraryService.list(projectId)));
    }

    @PostMapping("/folders")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createFolder(@RequestParam String projectId,
                                                                         @RequestBody Map<String, String> payload) {
        return ResponseEntity.ok(ApiResponse.success(
                projectFileLibraryService.createFolder(projectId, payload.get("name")),
                "Folder created"));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> upload(@RequestParam String projectId,
                                                                         @RequestParam Long folderId,
                                                                         @RequestPart("files") MultipartFile[] files) {
        return ResponseEntity.ok(ApiResponse.success(
                projectFileLibraryService.upload(projectId, folderId, files),
                "Files uploaded"));
    }

    @PutMapping("/{fileId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateStatus(@RequestParam String projectId,
                                                                         @PathVariable Long fileId,
                                                                         @RequestBody Map<String, String> payload) {
        return ResponseEntity.ok(ApiResponse.success(
                projectFileLibraryService.updateStatus(projectId, fileId, payload.get("status")),
                "Status updated"));
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteFile(@RequestParam String projectId,
                                                                       @PathVariable Long fileId) {
        return ResponseEntity.ok(ApiResponse.success(
                projectFileLibraryService.deleteFile(projectId, fileId),
                "File deleted"));
    }

    @DeleteMapping("/folders/{folderId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteFolder(@RequestParam String projectId,
                                                                         @PathVariable Long folderId) {
        return ResponseEntity.ok(ApiResponse.success(
                projectFileLibraryService.deleteFolder(projectId, folderId),
                "Folder deleted"));
    }

    @GetMapping("/{fileId}/download")
    public ResponseEntity<Resource> download(@RequestParam String projectId, @PathVariable Long fileId) {
        ProjectFileLibraryService.DownloadPayload payload = projectFileLibraryService.download(projectId, fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + payload.fileName() + "\"")
                .body(payload.resource());
    }
}
