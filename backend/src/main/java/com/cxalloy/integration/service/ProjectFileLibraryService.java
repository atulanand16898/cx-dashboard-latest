package com.cxalloy.integration.service;

import com.cxalloy.integration.model.ProjectFileFolder;
import com.cxalloy.integration.model.ProjectFileStatus;
import com.cxalloy.integration.model.ProjectManagedFile;
import com.cxalloy.integration.repository.ProjectFileFolderRepository;
import com.cxalloy.integration.repository.ProjectManagedFileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.DirectoryStream;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ProjectFileLibraryService {

    private final ProjectFileFolderRepository folderRepository;
    private final ProjectManagedFileRepository fileRepository;
    private final Path storageRoot;

    public ProjectFileLibraryService(ProjectFileFolderRepository folderRepository,
                                     ProjectManagedFileRepository fileRepository,
                                     @Value("${app.files.storage-dir:}") String storageDir) {
        this.folderRepository = folderRepository;
        this.fileRepository = fileRepository;
        String effectiveStorageDir = Optional.ofNullable(storageDir)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .orElseGet(() -> Paths.get(System.getProperty("java.io.tmpdir"), "project-files").toString());
        this.storageRoot = Paths.get(effectiveStorageDir).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(String projectId) {
        List<ProjectFileFolder> folders = folderRepository.findByProjectIdOrderByNameAsc(projectId);
        List<ProjectManagedFile> files = fileRepository.findByProjectIdOrderByUploadedAtDesc(projectId);

        List<Map<String, Object>> folderRows = folders.stream().map(folder -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", folder.getId());
            row.put("name", folder.getName());
            row.put("createdAt", folder.getCreatedAt());
            row.put("updatedAt", folder.getUpdatedAt());
            row.put("fileCount", fileRepository.countByFolderId(folder.getId()));
            return row;
        }).toList();

        List<Map<String, Object>> fileRows = files.stream().map(file -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", file.getId());
            row.put("projectId", file.getProjectId());
            row.put("folderId", file.getFolder().getId());
            row.put("folderName", file.getFolder().getName());
            row.put("name", file.getOriginalName());
            row.put("contentType", file.getContentType());
            row.put("sizeBytes", file.getSizeBytes());
            row.put("status", file.getStatus().getApiValue());
            row.put("uploadedAt", file.getUploadedAt());
            row.put("updatedAt", file.getUpdatedAt());
            return row;
        }).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("folders", folderRows);
        payload.put("files", fileRows);
        payload.put("statusOptions", Arrays.stream(ProjectFileStatus.values()).map(ProjectFileStatus::getApiValue).toList());
        return payload;
    }

    @Transactional
    public Map<String, Object> createFolder(String projectId, String name) {
        String cleanName = sanitizeFolderName(name);
        if (folderRepository.existsByProjectIdAndNameIgnoreCase(projectId, cleanName)) {
            throw new IllegalArgumentException("Folder already exists: " + cleanName);
        }
        ProjectFileFolder folder = new ProjectFileFolder();
        folder.setProjectId(projectId);
        folder.setName(cleanName);
        folder = folderRepository.save(folder);
        ensureFolderPath(projectId, folder.getId());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", folder.getId());
        row.put("name", folder.getName());
        row.put("createdAt", folder.getCreatedAt());
        row.put("updatedAt", folder.getUpdatedAt());
        row.put("fileCount", 0);
        return row;
    }

    @Transactional
    public List<Map<String, Object>> upload(String projectId, Long folderId, MultipartFile[] files) {
        ProjectFileFolder folder = folderRepository.findByIdAndProjectId(folderId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Folder not found"));
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("No files selected");
        }

        Path folderPath = ensureFolderPath(projectId, folder.getId());
        List<Map<String, Object>> uploadedRows = new ArrayList<>();

        for (MultipartFile multipartFile : files) {
            if (multipartFile == null || multipartFile.isEmpty()) {
                continue;
            }
            String originalName = Optional.ofNullable(multipartFile.getOriginalFilename()).orElse("file");
            String storedName = UUID.randomUUID() + "-" + sanitizeFileName(originalName);
            Path target = folderPath.resolve(storedName).normalize();
            try {
                Files.copy(multipartFile.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to store file: " + originalName, e);
            }

            ProjectManagedFile file = new ProjectManagedFile();
            file.setProjectId(projectId);
            file.setFolder(folder);
            file.setOriginalName(originalName);
            file.setStoredName(storedName);
            file.setStoragePath(target.toString());
            file.setContentType(Optional.ofNullable(multipartFile.getContentType()).orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE));
            file.setSizeBytes(multipartFile.getSize());
            file.setStatus(ProjectFileStatus.OPEN);
            file = fileRepository.save(file);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", file.getId());
            row.put("projectId", file.getProjectId());
            row.put("folderId", folder.getId());
            row.put("folderName", folder.getName());
            row.put("name", file.getOriginalName());
            row.put("contentType", file.getContentType());
            row.put("sizeBytes", file.getSizeBytes());
            row.put("status", file.getStatus().getApiValue());
            row.put("uploadedAt", file.getUploadedAt());
            row.put("updatedAt", file.getUpdatedAt());
            uploadedRows.add(row);
        }
        return uploadedRows;
    }

    @Transactional
    public Map<String, Object> updateStatus(String projectId, Long fileId, String statusValue) {
        ProjectManagedFile file = fileRepository.findByIdAndProjectId(fileId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("File not found"));
        file.setStatus(ProjectFileStatus.fromValue(statusValue));
        file.setUpdatedAt(LocalDateTime.now());
        file = fileRepository.save(file);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", file.getId());
        row.put("status", file.getStatus().getApiValue());
        row.put("updatedAt", file.getUpdatedAt());
        return row;
    }

    @Transactional(readOnly = true)
    public DownloadPayload download(String projectId, Long fileId) {
        ProjectManagedFile file = fileRepository.findByIdAndProjectId(fileId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("File not found"));
        Resource resource = new FileSystemResource(file.getStoragePath());
        if (!resource.exists()) {
            throw new IllegalArgumentException("Stored file not found on disk");
        }
        return new DownloadPayload(file.getOriginalName(), file.getContentType(), resource);
    }

    @Transactional
    public Map<String, Object> deleteFile(String projectId, Long fileId) {
        ProjectManagedFile file = fileRepository.findByIdAndProjectId(fileId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("File not found"));

        deleteStoredFile(file.getStoragePath());
        Long folderId = file.getFolder().getId();
        String fileName = file.getOriginalName();
        fileRepository.delete(file);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", fileId);
        payload.put("folderId", folderId);
        payload.put("name", fileName);
        payload.put("deleted", true);
        return payload;
    }

    @Transactional
    public Map<String, Object> deleteFolder(String projectId, Long folderId) {
        ProjectFileFolder folder = folderRepository.findByIdAndProjectId(folderId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Folder not found"));

        List<ProjectManagedFile> folderFiles = fileRepository.findByFolderId(folderId);
        int deletedCount = folderFiles.size();
        for (ProjectManagedFile file : folderFiles) {
            deleteStoredFile(file.getStoragePath());
        }
        fileRepository.deleteAll(folderFiles);
        folderRepository.delete(folder);
        deleteFolderPath(projectId, folderId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", folderId);
        payload.put("name", folder.getName());
        payload.put("deletedFiles", deletedCount);
        payload.put("deleted", true);
        return payload;
    }

    private Path ensureFolderPath(String projectId, Long folderId) {
        try {
            Path folderPath = storageRoot.resolve(sanitizeSegment(projectId)).resolve(String.valueOf(folderId));
            Files.createDirectories(folderPath);
            return folderPath;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare file storage folder", e);
        }
    }

    private void deleteStoredFile(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) return;
        try {
            Files.deleteIfExists(Paths.get(storagePath));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete stored file", e);
        }
    }

    private void deleteFolderPath(String projectId, Long folderId) {
        Path folderPath = storageRoot.resolve(sanitizeSegment(projectId)).resolve(String.valueOf(folderId));
        try {
            if (!Files.exists(folderPath)) return;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folderPath)) {
                for (Path path : stream) {
                    Files.deleteIfExists(path);
                }
            }
            Files.deleteIfExists(folderPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete folder from disk", e);
        }
    }

    private String sanitizeFolderName(String name) {
        String value = Optional.ofNullable(name).orElse("").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Folder name is required");
        }
        return value.replaceAll("[\\\\/:*?\"<>|]", "-");
    }

    private String sanitizeFileName(String name) {
        return Optional.ofNullable(name).orElse("file").replaceAll("[\\\\/:*?\"<>|]", "-");
    }

    private String sanitizeSegment(String value) {
        return Optional.ofNullable(value).orElse("project").replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public record DownloadPayload(String fileName, String contentType, Resource resource) {}
}
