package com.cxalloy.integration.xerprocessing.service;

import com.cxalloy.integration.xerprocessing.dto.CreateXerProjectRequest;
import com.cxalloy.integration.xerprocessing.dto.RegisterXerBaselineRequest;
import com.cxalloy.integration.xerprocessing.dto.SaveXerProgressMeasurementRequest;
import com.cxalloy.integration.xerprocessing.dto.XerBaselineAnalysisResponse;
import com.cxalloy.integration.xerprocessing.dto.XerDcmaCheckpointResponse;
import com.cxalloy.integration.xerprocessing.dto.XerProgressMeasurementOption;
import com.cxalloy.integration.xerprocessing.dto.XerProgressMeasurementOptionsResponse;
import com.cxalloy.integration.xerprocessing.dto.XerWorkflowSummaryResponse;
import com.cxalloy.integration.xerprocessing.model.XerActivity;
import com.cxalloy.integration.xerprocessing.model.XerActivityCode;
import com.cxalloy.integration.xerprocessing.model.XerDcmaCheckpoint;
import com.cxalloy.integration.xerprocessing.model.XerImportScopedEntity;
import com.cxalloy.integration.xerprocessing.model.XerImportSession;
import com.cxalloy.integration.xerprocessing.model.XerProgressMeasurementConfig;
import com.cxalloy.integration.xerprocessing.model.XerProject;
import com.cxalloy.integration.xerprocessing.model.XerResource;
import com.cxalloy.integration.xerprocessing.model.XerTaskActivityCode;
import com.cxalloy.integration.xerprocessing.model.XerTaskPredecessor;
import com.cxalloy.integration.xerprocessing.model.XerTaskResource;
import com.cxalloy.integration.xerprocessing.model.XerCalendar;
import com.cxalloy.integration.xerprocessing.repository.XerActivityCodeRepository;
import com.cxalloy.integration.xerprocessing.repository.XerActivityRepository;
import com.cxalloy.integration.xerprocessing.repository.XerCalendarRepository;
import com.cxalloy.integration.xerprocessing.repository.XerDcmaCheckpointRepository;
import com.cxalloy.integration.xerprocessing.repository.XerImportSessionRepository;
import com.cxalloy.integration.xerprocessing.repository.XerProgressMeasurementConfigRepository;
import com.cxalloy.integration.xerprocessing.repository.XerProjectRepository;
import com.cxalloy.integration.xerprocessing.repository.XerResourceRepository;
import com.cxalloy.integration.xerprocessing.repository.XerTaskActivityCodeRepository;
import com.cxalloy.integration.xerprocessing.repository.XerTaskPredecessorRepository;
import com.cxalloy.integration.xerprocessing.repository.XerTaskResourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@Service
public class XerProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(XerProcessingService.class);

    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm"),
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss")
    );

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.BASIC_ISO_DATE,
            DateTimeFormatter.ofPattern("M/d/yyyy")
    );

    private static final List<BaselineTableProgressConfig> BASELINE_TABLES = List.of(
            new BaselineTableProgressConfig("TASK", "activities", "Activities"),
            new BaselineTableProgressConfig("TASKRSRC", "taskResources", "Task Resources"),
            new BaselineTableProgressConfig("RSRC", "resources", "Resources"),
            new BaselineTableProgressConfig("CALENDAR", "calendars", "Calendars"),
            new BaselineTableProgressConfig("ACTVCODE", "activityCodes", "Activity Codes"),
            new BaselineTableProgressConfig("TASKACTV", "taskActivityCodes", "Task Activity Codes"),
            new BaselineTableProgressConfig("TASKPRED", "taskPredecessors", "Predecessors")
    );

    private final XerProjectRepository xerProjectRepository;
    private final XerImportSessionRepository xerImportSessionRepository;
    private final XerProgressMeasurementConfigRepository xerProgressMeasurementConfigRepository;
    private final XerActivityRepository xerActivityRepository;
    private final XerTaskResourceRepository xerTaskResourceRepository;
    private final XerResourceRepository xerResourceRepository;
    private final XerCalendarRepository xerCalendarRepository;
    private final XerActivityCodeRepository xerActivityCodeRepository;
    private final XerTaskActivityCodeRepository xerTaskActivityCodeRepository;
    private final XerTaskPredecessorRepository xerTaskPredecessorRepository;
    private final XerDcmaCheckpointRepository xerDcmaCheckpointRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService xerProcessingExecutor;
    private final String configuredStorageDir;
    private final String configuredPythonCommand;
    private final String configuredProcessorScript;

    public XerProcessingService(
            XerProjectRepository xerProjectRepository,
            XerImportSessionRepository xerImportSessionRepository,
            XerProgressMeasurementConfigRepository xerProgressMeasurementConfigRepository,
            XerActivityRepository xerActivityRepository,
            XerTaskResourceRepository xerTaskResourceRepository,
            XerResourceRepository xerResourceRepository,
            XerCalendarRepository xerCalendarRepository,
            XerActivityCodeRepository xerActivityCodeRepository,
            XerTaskActivityCodeRepository xerTaskActivityCodeRepository,
            XerTaskPredecessorRepository xerTaskPredecessorRepository,
            XerDcmaCheckpointRepository xerDcmaCheckpointRepository,
            ObjectMapper objectMapper,
            @Qualifier("xerProcessingExecutor") ExecutorService xerProcessingExecutor,
            @Value("${app.xer-processing.storage-dir:}") String configuredStorageDir,
            @Value("${app.xer-processing.python-command:}") String configuredPythonCommand,
            @Value("${app.xer-processing.processor-script:}") String configuredProcessorScript
    ) {
        this.xerProjectRepository = xerProjectRepository;
        this.xerImportSessionRepository = xerImportSessionRepository;
        this.xerProgressMeasurementConfigRepository = xerProgressMeasurementConfigRepository;
        this.xerActivityRepository = xerActivityRepository;
        this.xerTaskResourceRepository = xerTaskResourceRepository;
        this.xerResourceRepository = xerResourceRepository;
        this.xerCalendarRepository = xerCalendarRepository;
        this.xerActivityCodeRepository = xerActivityCodeRepository;
        this.xerTaskActivityCodeRepository = xerTaskActivityCodeRepository;
        this.xerTaskPredecessorRepository = xerTaskPredecessorRepository;
        this.xerDcmaCheckpointRepository = xerDcmaCheckpointRepository;
        this.objectMapper = objectMapper;
        this.xerProcessingExecutor = xerProcessingExecutor;
        this.configuredStorageDir = configuredStorageDir;
        this.configuredPythonCommand = configuredPythonCommand;
        this.configuredProcessorScript = configuredProcessorScript;
    }

    public List<XerProject> listProjects() {
        return xerProjectRepository.findAllByOrderByUpdatedAtDesc();
    }

    public Optional<XerProject> getProject(Long id) {
        return xerProjectRepository.findById(id);
    }

    @Transactional
    public XerProject createProject(CreateXerProjectRequest request) {
        XerProject project = new XerProject();
        project.setName(request.getName());
        project.setProjectCode(request.getProjectCode());
        project.setNotes(request.getNotes());
        project.setStatus("draft");
        project.setWorkflowStage("project_created");
        project.setBaselineStatus("pending");
        project.setProgressMeasurementStatus("pending");
        return xerProjectRepository.save(project);
    }

    @Transactional
    public XerImportSession registerBaselineImport(Long projectId, RegisterXerBaselineRequest request) {
        XerProject project = requireProject(projectId);

        XerImportSession importSession = new XerImportSession();
        importSession.setProject(project);
        importSession.setImportType("baseline");
        importSession.setWorkflowStep("baseline_upload");
        importSession.setStatus("registered");
        importSession.setOriginalFileName(request.getFileName());
        importSession.setStoredFileName(request.getStoredFileName());
        importSession.setProjectCodeFromFile(request.getProjectCodeFromFile());
        importSession.setRevisionLabel(request.getRevisionLabel());
        importSession.setDataDate(request.getDataDate());
        importSession.setStoragePath(request.getStoragePath());
        importSession.setProcessorName(
                !StringUtils.hasText(request.getProcessorName())
                        ? "Baseline_Workout.py"
                        : request.getProcessorName()
        );
        importSession.setProcessorVersion(request.getProcessorVersion());
        importSession.setSummaryJson(request.getSummaryJson());
        importSession.setStartedAt(LocalDateTime.now());

        project.setBaselineStatus("registered");
        project.setWorkflowStage("baseline_registered");
        project.setStatus("baseline_pending");
        xerProjectRepository.save(project);

        return xerImportSessionRepository.save(importSession);
    }

    @Transactional
    public XerImportSession uploadBaseline(Long projectId, MultipartFile file) {
        XerProject project = requireProject(projectId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a baseline XER file first");
        }

        String originalFileName = sanitizeFileName(file.getOriginalFilename());
        if (!originalFileName.toLowerCase(Locale.ROOT).endsWith(".xer")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only .xer baseline files are supported");
        }

        try {
            Path storageDir = resolveProjectImportDirectory(projectId);
            Files.createDirectories(storageDir);

            String storedFileName = UUID.randomUUID() + "-" + originalFileName;
            Path targetFile = storageDir.resolve(storedFileName);
            Files.copy(file.getInputStream(), targetFile, StandardCopyOption.REPLACE_EXISTING);

            XerImportSession importSession = new XerImportSession();
            importSession.setProject(project);
            importSession.setImportType("baseline");
            importSession.setWorkflowStep("baseline_upload");
            importSession.setStatus("processing");
            importSession.setOriginalFileName(originalFileName);
            importSession.setStoredFileName(storedFileName);
            importSession.setStoragePath(targetFile.toString());
            importSession.setProcessorName("process_baseline.py");
            importSession.setProcessorVersion("v1");
            importSession.setStartedAt(LocalDateTime.now());
            importSession.setProgressJson(buildProgressSnapshotJson(
                    importSession,
                    "uploading",
                    "Baseline file uploaded. Waiting for the Primavera processor to start.",
                    8,
                    null,
                    Map.of(),
                    null
            ));
            importSession = xerImportSessionRepository.save(importSession);

            project.setBaselineStatus("processing");
            project.setWorkflowStage("baseline_processing");
            project.setStatus("baseline_processing");
            xerProjectRepository.save(project);

            Long importSessionId = importSession.getId();
            queueBaselineProcessing(importSessionId, projectId);
            return importSession;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store the uploaded baseline file");
        }
    }

    @Transactional
    public XerProgressMeasurementConfig saveProgressMeasurement(Long projectId, SaveXerProgressMeasurementRequest request) {
        XerProject project = requireProject(projectId);
        XerImportSession baselineImport = resolveBaselineImport(projectId, request.getBaselineImportId());
        List<String> normalizedResourceTypes = normalizeSelections(request.getResourceTypes());
        List<String> normalizedResourceNames = normalizeSelections(request.getResourceNames());
        boolean allResourceNames = Boolean.TRUE.equals(request.getAllResourceNames());

        if (normalizedResourceTypes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one resource type");
        }
        if (!allResourceNames && normalizedResourceNames.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Choose at least one resource name or use the All option"
            );
        }

        XerProgressMeasurementConfig config = new XerProgressMeasurementConfig();
        config.setProject(project);
        config.setBaselineImport(baselineImport);
        config.setSelectionMode(allResourceNames ? "resource_filter_multi_all_names" : "resource_filter_multi");
        config.setResourceType(summarizeSelection(normalizedResourceTypes, "type", "types"));
        config.setResourceName(allResourceNames
                ? "ALL"
                : summarizeSelection(normalizedResourceNames, "name", "names"));
        config.setResourceTypesJson(writeJson(normalizedResourceTypes));
        config.setResourceNamesJson(writeJson(normalizedResourceNames));
        config.setAllResourceNames(allResourceNames);
        config.setNotes(request.getNotes());
        config.setConfiguredAt(LocalDateTime.now());

        project.setProgressMeasurementStatus("configured");
        project.setWorkflowStage("progress_measurement_configured");
        project.setStatus("ready_for_progress_logic");
        xerProjectRepository.save(project);

        return xerProgressMeasurementConfigRepository.save(config);
    }

    public XerProgressMeasurementOptionsResponse getProgressMeasurementOptions(Long projectId) {
        XerProject project = requireProject(projectId);
        XerImportSession baselineImport = xerImportSessionRepository
                .findTopByProjectIdAndImportTypeOrderByCreatedAtDesc(projectId, "baseline")
                .orElse(null);

        if (baselineImport == null) {
            return new XerProgressMeasurementOptionsResponse(null, "missing", List.of(), List.of());
        }

        if (!"completed".equalsIgnoreCase(baselineImport.getStatus())) {
            return new XerProgressMeasurementOptionsResponse(
                    baselineImport.getId(),
                    baselineImport.getStatus(),
                    List.of(),
                    List.of()
            );
        }

        List<XerProgressMeasurementOption> resources = collectProgressMeasurementOptions(project, baselineImport);
        List<String> resourceTypes = resources.stream()
                .map(XerProgressMeasurementOption::getResourceType)
                .filter(StringUtils::hasText)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        return new XerProgressMeasurementOptionsResponse(
                baselineImport.getId(),
                baselineImport.getStatus(),
                resourceTypes,
                resources
        );
    }

    public XerWorkflowSummaryResponse getWorkflowSummary(Long projectId) {
        XerProject project = requireProject(projectId);
        long baselineImportCount = xerImportSessionRepository.countByProjectIdAndImportType(projectId, "baseline");
        XerImportSession latestBaselineImport = xerImportSessionRepository
                .findTopByProjectIdAndImportTypeOrderByCreatedAtDesc(projectId, "baseline")
                .orElse(null);
        XerProgressMeasurementConfig progressMeasurementConfig = xerProgressMeasurementConfigRepository
                .findTopByProjectIdOrderByConfiguredAtDesc(projectId)
                .orElse(null);

        return new XerWorkflowSummaryResponse(
                project,
                baselineImportCount,
                latestBaselineImport,
                progressMeasurementConfig,
                buildNextSteps(latestBaselineImport, progressMeasurementConfig)
        );
    }

    public XerBaselineAnalysisResponse getBaselineAnalysis(Long projectId, LocalDate selectedDataDate) {
        XerProject project = requireProject(projectId);
        XerImportSession baselineImport = xerImportSessionRepository
                .findTopByProjectIdAndImportTypeOrderByCreatedAtDesc(projectId, "baseline")
                .filter(item -> "completed".equalsIgnoreCase(item.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Complete a baseline import before analyzing the Primavera baseline"
                ));

        XerProgressMeasurementConfig progressConfig = xerProgressMeasurementConfigRepository
                .findTopByProjectIdOrderByConfiguredAtDesc(projectId)
                .orElse(null);

        List<XerActivity> activities = xerActivityRepository.findByProjectIdAndImportSessionId(projectId, baselineImport.getId());
        List<XerTaskResource> taskResources = xerTaskResourceRepository.findByProjectIdAndImportSessionId(projectId, baselineImport.getId());

        Map<String, XerActivity> activitiesByTaskId = new LinkedHashMap<>();
        for (XerActivity activity : activities) {
            if (StringUtils.hasText(activity.getExternalTaskId()) && !activitiesByTaskId.containsKey(activity.getExternalTaskId())) {
                activitiesByTaskId.put(activity.getExternalTaskId(), activity);
            }
        }

        List<String> configuredTypes = progressConfig == null
                ? List.of()
                : readStringListJson(progressConfig.getResourceTypesJson(), progressConfig.getResourceType());
        List<String> configuredNames = progressConfig == null
                ? List.of()
                : readStringListJson(progressConfig.getResourceNamesJson(), progressConfig.getResourceName());
        boolean allResourceNames = progressConfig != null && Boolean.TRUE.equals(progressConfig.getAllResourceNames());

        List<XerTaskResource> scopedTaskResources = filterTaskResources(taskResources, configuredTypes, configuredNames, allResourceNames);
        boolean usingAllResourcesFallback = scopedTaskResources.isEmpty() && !taskResources.isEmpty();
        if (usingAllResourcesFallback) {
            scopedTaskResources = taskResources;
        }

        LinkedHashSet<String> scopedTaskIds = new LinkedHashSet<>();
        for (XerTaskResource resource : scopedTaskResources) {
            if (StringUtils.hasText(resource.getExternalTaskId())) {
                scopedTaskIds.add(resource.getExternalTaskId());
            }
        }

        List<XerActivity> scopedActivities;
        if (scopedTaskIds.isEmpty()) {
            scopedActivities = activities;
        } else {
            scopedActivities = activities.stream()
                    .filter(activity -> scopedTaskIds.contains(activity.getExternalTaskId()))
                    .toList();
        }

        List<PlannedBand> bands = buildPlannedBands(scopedTaskResources, activitiesByTaskId, scopedActivities);
        LocalDate effectiveDataDate = selectedDataDate != null
                ? selectedDataDate
                : (baselineImport.getDataDate() != null ? baselineImport.getDataDate() : LocalDate.now());

        double totalPlannedUnits = bands.stream().mapToDouble(PlannedBand::plannedUnits).sum();
        double plannedUnitsAtDataDate = cumulativeUnitsAtDate(bands, effectiveDataDate);
        double totalPlannedCost = bands.stream().mapToDouble(PlannedBand::plannedCost).sum();
        double plannedCostAtDataDate = cumulativeCostAtDate(bands, effectiveDataDate);
        double planPercent = totalPlannedUnits <= 0 ? 0d : (plannedUnitsAtDataDate / totalPlannedUnits) * 100d;

        List<XerBaselineAnalysisResponse.CurvePoint> curvePoints = buildCurvePoints(bands);
        List<String> scopedTypes = configuredTypes.isEmpty()
                ? scopedTaskResources.stream()
                    .map(XerTaskResource::getResourceType)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList()
                : configuredTypes;
        List<String> scopedNames = configuredNames.isEmpty() && !allResourceNames
                ? scopedTaskResources.stream()
                    .map(XerTaskResource::getResourceName)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .limit(12)
                    .toList()
                : configuredNames;

        String scopeSummary = usingAllResourcesFallback
                ? "Saved progress-measurement filter found no rows in this import, so the analysis is using all imported task resources."
                : buildScopeSummary(scopedTypes, scopedNames, allResourceNames, scopedTaskResources.size(), scopedActivities.size());

        return new XerBaselineAnalysisResponse(
                "analyze_baseline",
                effectiveDataDate,
                baselineImport.getDataDate(),
                scopeSummary,
                "Plan % and the planned S-curves are linear spreads across baseline target start and end dates for the current Primavera scope. When target dates are missing, the analysis falls back to activity early dates.",
                scopedTypes,
                scopedNames,
                allResourceNames,
                scopedActivities.size(),
                scopedTaskResources.size(),
                round(planPercent),
                round(totalPlannedUnits),
                round(plannedUnitsAtDataDate),
                round(totalPlannedCost),
                round(plannedCostAtDataDate),
                curvePoints,
                curvePoints
        );
    }

    private void processBaselineInBackground(Long importSessionId) {
        XerImportSession importSession = xerImportSessionRepository.findWithProjectById(importSessionId).orElse(null);
        if (importSession == null) {
            logger.warn("Skipped XER baseline processing because import session {} no longer exists", importSessionId);
            return;
        }

        try {
            logger.info(
                    "Starting XER baseline processing importSessionId={} file={}",
                    importSessionId,
                    importSession.getOriginalFileName()
            );
            updateImportProgress(
                    importSession,
                    "parsing",
                    "Reading the raw XER file and detecting the core Primavera tables.",
                    null,
                    Map.of(),
                    null
            );
            Path baselinePath = Paths.get(importSession.getStoragePath());
            Path processorScript = resolveProcessorScriptPath();
            Path outputPath = baselinePath.resolveSibling(importSession.getStoredFileName() + ".json");

            Files.createDirectories(outputPath.getParent());
            runProcessor(baselinePath, outputPath, processorScript);

            JsonNode root = objectMapper.readTree(Files.readString(outputPath, StandardCharsets.UTF_8));
            updateImportProgress(
                    importSession,
                    "parsed",
                    "Baseline parsed successfully. Saving the detected Primavera tables into the workspace.",
                    root,
                    Map.of(),
                    null
            );
            persistProcessedBaseline(importSessionId, root);
            logger.info("Completed XER baseline processing importSessionId={}", importSessionId);
        } catch (Exception ex) {
            logger.error("XER baseline processing failed for importSessionId={}", importSessionId, ex);
            markImportFailed(importSessionId, summarizeException(ex));
        }
    }

    private void queueBaselineProcessing(Long importSessionId, Long projectId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitBaselineProcessing(importSessionId, projectId);
                }
            });
            logger.info("Registered XER baseline import {} for post-commit processing on project {}", importSessionId, projectId);
            return;
        }

        submitBaselineProcessing(importSessionId, projectId);
    }

    private void submitBaselineProcessing(Long importSessionId, Long projectId) {
        try {
            xerProcessingExecutor.submit(() -> processBaselineInBackground(importSessionId));
        } catch (RejectedExecutionException ex) {
            logger.error("Failed to queue XER baseline import {}", importSessionId, ex);
            markImportFailed(importSessionId, "XER background processor is busy. Please retry the upload.");
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "XER background processor is busy. Please retry the upload."
            );
        }
        logger.info("Queued XER baseline import {} for project {}", importSessionId, projectId);
    }

    private void runProcessor(Path baselinePath, Path outputPath, Path processorScript) throws IOException, InterruptedException {
        if (!Files.exists(processorScript)) {
            throw new IOException("Processor script not found at " + processorScript);
        }

        List<String> command = List.of(
                resolvePythonCommand(),
                processorScript.toString(),
                "--file",
                baselinePath.toString(),
                "--output",
                outputPath.toString()
        );

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        StringBuilder processLog = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                processLog.append(line).append(System.lineSeparator());
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String message = processLog.length() == 0
                    ? "Python baseline processor failed with exit code " + exitCode
                    : processLog.toString().trim();
            throw new IOException(message);
        }
        logger.info(
                "XER processor finished successfully for file={} output={}",
                baselinePath.getFileName(),
                outputPath.getFileName()
        );
    }

    private void persistProcessedBaseline(Long importSessionId, JsonNode root) {
        XerImportSession importSession = xerImportSessionRepository.findWithProjectById(importSessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Baseline import not found"));
        XerProject project = importSession.getProject();

        String projectCode = text(root, "projectCode");
        String revisionLabel = text(root, "revisionLabel");
        LocalDate dataDate = parseDate(text(root, "dataDate"));
        Map<String, Integer> persistedCounts = new LinkedHashMap<>();

        importSession.setProjectCodeFromFile(projectCode);
        importSession.setRevisionLabel(revisionLabel);
        importSession.setDataDate(dataDate);
        importSession.setSummaryJson(root.path("summary").toString());
        importSession.setWorkflowStep("baseline_persisting");
        xerImportSessionRepository.save(importSession);

        persistTable(
                importSession,
                root,
                persistedCounts,
                "TASK",
                "Saving activities into the Primavera workspace.",
                "Activities saved into the Primavera workspace.",
                () -> saveActivities(project, importSession, projectCode, revisionLabel, dataDate, root.path("activities"))
        );
        persistTable(
                importSession,
                root,
                persistedCounts,
                "TASKRSRC",
                "Saving task resources into the Primavera workspace.",
                "Task resources saved into the Primavera workspace.",
                () -> saveTaskResources(project, importSession, projectCode, revisionLabel, dataDate, root.path("taskResources"))
        );
        persistTable(
                importSession,
                root,
                persistedCounts,
                "RSRC",
                "Saving resources into the Primavera workspace.",
                "Resources saved into the Primavera workspace.",
                () -> saveResources(project, importSession, projectCode, revisionLabel, dataDate, root.path("resources"))
        );
        persistTable(
                importSession,
                root,
                persistedCounts,
                "CALENDAR",
                "Saving calendars into the Primavera workspace.",
                "Calendars saved into the Primavera workspace.",
                () -> saveCalendars(project, importSession, projectCode, revisionLabel, dataDate, root.path("calendars"))
        );
        persistTable(
                importSession,
                root,
                persistedCounts,
                "ACTVCODE",
                "Saving activity codes into the Primavera workspace.",
                "Activity codes saved into the Primavera workspace.",
                () -> saveActivityCodes(project, importSession, projectCode, revisionLabel, dataDate, root.path("activityCodes"))
        );
        persistTable(
                importSession,
                root,
                persistedCounts,
                "TASKACTV",
                "Saving task activity codes into the Primavera workspace.",
                "Task activity codes saved into the Primavera workspace.",
                () -> saveTaskActivityCodes(project, importSession, projectCode, revisionLabel, dataDate, root.path("taskActivityCodes"))
        );
        persistTable(
                importSession,
                root,
                persistedCounts,
                "TASKPRED",
                "Saving predecessors into the Primavera workspace.",
                "Predecessors saved into the Primavera workspace.",
                () -> saveTaskPredecessors(project, importSession, projectCode, revisionLabel, dataDate, root.path("taskPredecessors"))
        );

        if (StringUtils.hasText(projectCode) && !StringUtils.hasText(project.getProjectCode())) {
            project.setProjectCode(projectCode);
        }
        project.setBaselineStatus("completed");
        project.setWorkflowStage("baseline_processed");
        if (xerProgressMeasurementConfigRepository.findTopByProjectIdOrderByConfiguredAtDesc(project.getId()).isPresent()) {
            project.setProgressMeasurementStatus("configured");
            project.setStatus("ready_for_progress_logic");
        } else {
            project.setProgressMeasurementStatus("pending");
            project.setStatus("waiting_for_progress_measurement");
        }
        xerProjectRepository.save(project);

        importSession.setStatus("completed");
        importSession.setCompletedAt(LocalDateTime.now());
        importSession.setWorkflowStep("baseline_processed");
        updateImportProgress(
                importSession,
                "completed",
                "Baseline processing finished. All detected Primavera tables are now ready for the next step.",
                root,
                persistedCounts,
                null
        );

        // Persist DCMA checkpoints if Python emitted them
        JsonNode dcmaNode = root.path("dcmaCheckpoints");
        if (!dcmaNode.isMissingNode() && !dcmaNode.isNull()) {
            persistDcmaCheckpoints(project, importSession, dcmaNode);
        }
    }

    private void persistDcmaCheckpoints(XerProject project, XerImportSession importSession, JsonNode dcmaNode) {
        try {
            xerDcmaCheckpointRepository.deleteByProjectIdAndImportSessionId(project.getId(), importSession.getId());

            List<XerDcmaCheckpoint> toSave = new ArrayList<>();
            JsonNode checkpointsNode = dcmaNode.path("checkpoints");
            if (checkpointsNode.isArray()) {
                for (JsonNode cpNode : checkpointsNode) {
                    XerDcmaCheckpoint cp = new XerDcmaCheckpoint();
                    cp.setProject(project);
                    cp.setImportSession(importSession);
                    cp.setCheckpointId(cpNode.path("id").asInt());
                    cp.setCheckpointName(cpNode.path("name").asText());
                    cp.setStatus(cpNode.path("status").asText());
                    cp.setScore(cpNode.path("score").asDouble());
                    cp.setThreshold(cpNode.path("threshold").asDouble());
                    cp.setViolatingCount(cpNode.path("violatingCount").asInt());
                    cp.setTotalCount(cpNode.path("totalCount").asInt());
                    cp.setExceptionsJson(cpNode.path("exceptions").toString());

                    // Capture extra metric fields (cpliValue, beiValue, note, etc.)
                    Map<String, Object> extra = new LinkedHashMap<>();
                    cpNode.fields().forEachRemaining(entry -> {
                        String key = entry.getKey();
                        if (!Set.of("id", "name", "status", "score", "threshold",
                                    "violatingCount", "totalCount", "exceptions").contains(key)) {
                            extra.put(key, entry.getValue());
                        }
                    });
                    if (!extra.isEmpty()) {
                        cp.setExtraJson(writeJson(extra));
                    }
                    toSave.add(cp);
                }
            }
            xerDcmaCheckpointRepository.saveAll(toSave);
            logger.info("Persisted {} DCMA checkpoints for importSessionId={}", toSave.size(), importSession.getId());
        } catch (Exception ex) {
            logger.warn("Failed to persist DCMA checkpoints for importSessionId={}: {}", importSession.getId(), ex.getMessage());
        }
    }

    public XerDcmaCheckpointResponse getDcmaCheckpoints(Long projectId) {
        requireProject(projectId);
        XerImportSession importSession = xerImportSessionRepository
                .findTopByProjectIdAndImportTypeOrderByCreatedAtDesc(projectId, "baseline")
                .filter(s -> "completed".equalsIgnoreCase(s.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Complete a baseline import before viewing DCMA checkpoints"
                ));

        List<XerDcmaCheckpoint> rows = xerDcmaCheckpointRepository
                .findByProjectIdAndImportSessionIdOrderByCheckpointIdAsc(projectId, importSession.getId());

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "DCMA checkpoints not found for this baseline. Re-upload the XER file to generate them."
            );
        }

        List<XerDcmaCheckpointResponse.CheckpointDetail> details = rows.stream().map(cp -> {
            JsonNode exceptions = parseJsonNode(cp.getExceptionsJson());
            JsonNode extra = parseJsonNode(cp.getExtraJson());
            return new XerDcmaCheckpointResponse.CheckpointDetail(
                    cp.getCheckpointId(),
                    cp.getCheckpointName(),
                    cp.getStatus(),
                    cp.getScore() != null ? cp.getScore() : 0.0,
                    cp.getThreshold() != null ? cp.getThreshold() : 95.0,
                    cp.getViolatingCount() != null ? cp.getViolatingCount() : 0,
                    cp.getTotalCount() != null ? cp.getTotalCount() : 0,
                    exceptions,
                    extra
            );
        }).toList();

        long passCount = rows.stream().filter(cp -> "PASS".equalsIgnoreCase(cp.getStatus())).count();
        long failCount = rows.size() - passCount;

        return new XerDcmaCheckpointResponse(
                failCount == 0 ? "PASS" : "FAIL",
                (int) passCount,
                (int) failCount,
                0,
                0,
                importSession.getId(),
                details
        );
    }

    private JsonNode parseJsonNode(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (IOException ex) {
            return objectMapper.nullNode();
        }
    }

    private int saveActivities(XerProject project, XerImportSession importSession, String projectCode, String revisionLabel, LocalDate dataDate, JsonNode rows) {
        if (!rows.isArray() || rows.isEmpty()) {
            return 0;
        }
        List<XerActivity> entities = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!StringUtils.hasText(text(row, "externalTaskId"))) {
                continue;
            }
            XerActivity entity = new XerActivity();
            applyScope(entity, project, importSession, projectCode, revisionLabel, dataDate, row);
            entity.setExternalTaskId(text(row, "externalTaskId"));
            entity.setExternalProjectId(text(row, "externalProjectId"));
            entity.setExternalWbsId(text(row, "externalWbsId"));
            entity.setExternalCalendarId(text(row, "externalCalendarId"));
            entity.setPrimaryResourceId(text(row, "primaryResourceId"));
            entity.setTaskCode(text(row, "taskCode"));
            entity.setTaskName(text(row, "taskName"));
            entity.setTaskType(text(row, "taskType"));
            entity.setDurationType(text(row, "durationType"));
            entity.setStatusCode(text(row, "statusCode"));
            entity.setCompletePctType(text(row, "completePctType"));
            entity.setPhysCompletePct(decimal(row, "physCompletePct"));
            entity.setEarlyStartDate(parseDateTime(text(row, "earlyStartDate")));
            entity.setEarlyEndDate(parseDateTime(text(row, "earlyEndDate")));
            entity.setTargetDurationHours(decimal(row, "targetDurationHours"));
            entity.setRemainingDurationHours(decimal(row, "remainingDurationHours"));
            // DCMA fields
            entity.setTotalFloatHours(decimal(row, "totalFloatHours"));
            entity.setFreeFloatHours(decimal(row, "freeFloatHours"));
            entity.setConstraintType(text(row, "constraintType"));
            entity.setConstraintDate(parseDateTime(text(row, "constraintDate")));
            entity.setActivityTargetStartDate(parseDateTime(text(row, "targetStartDate")));
            entity.setActivityTargetEndDate(parseDateTime(text(row, "targetEndDate")));
            entity.setLateStartDate(parseDateTime(text(row, "lateStartDate")));
            entity.setLateEndDate(parseDateTime(text(row, "lateEndDate")));
            entities.add(entity);
        }
        if (!entities.isEmpty()) {
            xerActivityRepository.saveAll(entities);
        }
        return entities.size();
    }

    private int saveTaskResources(XerProject project, XerImportSession importSession, String projectCode, String revisionLabel, LocalDate dataDate, JsonNode rows) {
        if (!rows.isArray() || rows.isEmpty()) {
            return 0;
        }
        List<XerTaskResource> entities = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!StringUtils.hasText(text(row, "externalTaskId"))) {
                continue;
            }
            XerTaskResource entity = new XerTaskResource();
            applyScope(entity, project, importSession, projectCode, revisionLabel, dataDate, row);
            entity.setExternalTaskId(text(row, "externalTaskId"));
            entity.setExternalProjectId(text(row, "externalProjectId"));
            entity.setExternalResourceId(text(row, "externalResourceId"));
            entity.setResourceName(text(row, "resourceName"));
            entity.setResourceShortName(text(row, "resourceShortName"));
            entity.setResourceType(text(row, "resourceType"));
            entity.setRemainingQty(decimal(row, "remainingQty"));
            entity.setTargetQty(decimal(row, "targetQty"));
            entity.setActualRegularQty(decimal(row, "actualRegularQty"));
            entity.setCostPerQty(decimal(row, "costPerQty"));
            entity.setTargetCost(decimal(row, "targetCost"));
            entity.setActualRegularCost(decimal(row, "actualRegularCost"));
            entity.setRemainingCost(decimal(row, "remainingCost"));
            entity.setActualStartDate(parseDateTime(text(row, "actualStartDate")));
            entity.setActualEndDate(parseDateTime(text(row, "actualEndDate")));
            entity.setRestartDate(parseDateTime(text(row, "restartDate")));
            entity.setReendDate(parseDateTime(text(row, "reendDate")));
            entity.setTargetStartDate(parseDateTime(text(row, "targetStartDate")));
            entity.setTargetEndDate(parseDateTime(text(row, "targetEndDate")));
            entity.setRemainingLateStartDate(parseDateTime(text(row, "remainingLateStartDate")));
            entity.setRemainingLateEndDate(parseDateTime(text(row, "remainingLateEndDate")));
            entities.add(entity);
        }
        if (!entities.isEmpty()) {
            xerTaskResourceRepository.saveAll(entities);
        }
        return entities.size();
    }

    private int saveResources(XerProject project, XerImportSession importSession, String projectCode, String revisionLabel, LocalDate dataDate, JsonNode rows) {
        if (!rows.isArray() || rows.isEmpty()) {
            return 0;
        }
        List<XerResource> entities = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!StringUtils.hasText(text(row, "externalResourceId"))) {
                continue;
            }
            XerResource entity = new XerResource();
            applyScope(entity, project, importSession, projectCode, revisionLabel, dataDate, row);
            entity.setExternalResourceId(text(row, "externalResourceId"));
            entity.setParentResourceId(text(row, "parentResourceId"));
            entity.setExternalCalendarId(text(row, "externalCalendarId"));
            entity.setExternalRoleId(text(row, "externalRoleId"));
            entity.setResourceName(text(row, "resourceName"));
            entity.setResourceShortName(text(row, "resourceShortName"));
            entity.setResourceTitleName(text(row, "resourceTitleName"));
            entity.setResourceType(text(row, "resourceType"));
            entity.setActiveFlag(text(row, "activeFlag"));
            entity.setLoadTasksFlag(text(row, "loadTasksFlag"));
            entity.setLevelFlag(text(row, "levelFlag"));
            entity.setResourceNotes(text(row, "resourceNotes"));
            entities.add(entity);
        }
        if (!entities.isEmpty()) {
            xerResourceRepository.saveAll(entities);
        }
        return entities.size();
    }

    private int saveCalendars(XerProject project, XerImportSession importSession, String projectCode, String revisionLabel, LocalDate dataDate, JsonNode rows) {
        if (!rows.isArray() || rows.isEmpty()) {
            return 0;
        }
        List<XerCalendar> entities = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!StringUtils.hasText(text(row, "externalCalendarId"))) {
                continue;
            }
            XerCalendar entity = new XerCalendar();
            applyScope(entity, project, importSession, projectCode, revisionLabel, dataDate, row);
            entity.setExternalCalendarId(text(row, "externalCalendarId"));
            entity.setExternalProjectId(text(row, "externalProjectId"));
            entity.setCalendarName(text(row, "calendarName"));
            entity.setCalendarType(text(row, "calendarType"));
            entity.setCalendarData(text(row, "calendarData"));
            entities.add(entity);
        }
        if (!entities.isEmpty()) {
            xerCalendarRepository.saveAll(entities);
        }
        return entities.size();
    }

    private int saveActivityCodes(XerProject project, XerImportSession importSession, String projectCode, String revisionLabel, LocalDate dataDate, JsonNode rows) {
        if (!rows.isArray() || rows.isEmpty()) {
            return 0;
        }
        List<XerActivityCode> entities = new ArrayList<>();
        for (JsonNode row : rows) {
            XerActivityCode entity = new XerActivityCode();
            applyScope(entity, project, importSession, projectCode, revisionLabel, dataDate, row);
            entity.setExternalActivityCodeId(text(row, "externalActivityCodeId"));
            entity.setExternalCodeTypeId(text(row, "externalCodeTypeId"));
            entity.setCodeType(text(row, "codeType"));
            entity.setShortName(text(row, "shortName"));
            entity.setCodeName(text(row, "codeName"));
            entity.setSequenceNumber(text(row, "sequenceNumber"));
            entities.add(entity);
        }
        if (!entities.isEmpty()) {
            xerActivityCodeRepository.saveAll(entities);
        }
        return entities.size();
    }

    private int saveTaskActivityCodes(XerProject project, XerImportSession importSession, String projectCode, String revisionLabel, LocalDate dataDate, JsonNode rows) {
        if (!rows.isArray() || rows.isEmpty()) {
            return 0;
        }
        List<XerTaskActivityCode> entities = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!StringUtils.hasText(text(row, "externalTaskId"))) {
                continue;
            }
            XerTaskActivityCode entity = new XerTaskActivityCode();
            applyScope(entity, project, importSession, projectCode, revisionLabel, dataDate, row);
            entity.setExternalTaskId(text(row, "externalTaskId"));
            entity.setExternalProjectId(text(row, "externalProjectId"));
            entity.setExternalActivityCodeId(text(row, "externalActivityCodeId"));
            entity.setCodeType(text(row, "codeType"));
            entity.setCodeValue(text(row, "codeValue"));
            entities.add(entity);
        }
        if (!entities.isEmpty()) {
            xerTaskActivityCodeRepository.saveAll(entities);
        }
        return entities.size();
    }

    private int saveTaskPredecessors(XerProject project, XerImportSession importSession, String projectCode, String revisionLabel, LocalDate dataDate, JsonNode rows) {
        if (!rows.isArray() || rows.isEmpty()) {
            return 0;
        }
        List<XerTaskPredecessor> entities = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!StringUtils.hasText(text(row, "externalTaskId"))) {
                continue;
            }
            XerTaskPredecessor entity = new XerTaskPredecessor();
            applyScope(entity, project, importSession, projectCode, revisionLabel, dataDate, row);
            entity.setExternalTaskId(text(row, "externalTaskId"));
            entity.setExternalProjectId(text(row, "externalProjectId"));
            entity.setExternalPredecessorTaskId(text(row, "externalPredecessorTaskId"));
            entity.setRelationshipType(text(row, "relationshipType"));
            entity.setLagHours(decimal(row, "lagHours"));
            entities.add(entity);
        }
        if (!entities.isEmpty()) {
            xerTaskPredecessorRepository.saveAll(entities);
        }
        return entities.size();
    }

    private void applyScope(
            XerImportScopedEntity entity,
            XerProject project,
            XerImportSession importSession,
            String projectCode,
            String revisionLabel,
            LocalDate dataDate,
            JsonNode row
    ) {
        entity.setProject(project);
        entity.setImportSession(importSession);
        entity.setProjectCode(projectCode);
        entity.setRevisionLabel(revisionLabel);
        entity.setDataDate(dataDate);
        entity.setRawJson(row.toString());
    }

    private List<XerTaskResource> filterTaskResources(
            List<XerTaskResource> taskResources,
            List<String> selectedTypes,
            List<String> selectedNames,
            boolean allResourceNames
    ) {
        if (taskResources == null || taskResources.isEmpty()) {
            return List.of();
        }

        return taskResources.stream()
                .filter(resource -> selectedTypes.isEmpty() || containsIgnoreCase(selectedTypes, resource.getResourceType()))
                .filter(resource -> allResourceNames || selectedNames.isEmpty() || containsIgnoreCase(selectedNames, resource.getResourceName()))
                .toList();
    }

    private List<PlannedBand> buildPlannedBands(
            List<XerTaskResource> taskResources,
            Map<String, XerActivity> activitiesByTaskId,
            List<XerActivity> scopedActivities
    ) {
        List<PlannedBand> bands = new ArrayList<>();

        for (XerTaskResource taskResource : taskResources) {
            XerActivity linkedActivity = activitiesByTaskId.get(taskResource.getExternalTaskId());
            LocalDate startDate = resolveTaskResourceStartDate(taskResource, linkedActivity);
            LocalDate endDate = resolveTaskResourceEndDate(taskResource, linkedActivity);
            if (startDate == null && endDate == null) {
                continue;
            }
            if (startDate == null) {
                startDate = endDate;
            }
            if (endDate == null || endDate.isBefore(startDate)) {
                endDate = startDate;
            }

            double plannedUnits = numericOrFallback(taskResource.getTargetQty(), 1d);
            double plannedCost = numericOrFallback(taskResource.getTargetCost(), 0d);
            bands.add(new PlannedBand(startDate, endDate, plannedUnits, plannedCost));
        }

        if (!bands.isEmpty()) {
            return bands;
        }

        for (XerActivity activity : scopedActivities) {
            LocalDate startDate = toLocalDate(activity.getEarlyStartDate());
            LocalDate endDate = toLocalDate(activity.getEarlyEndDate());
            if (startDate == null && endDate == null) {
                continue;
            }
            if (startDate == null) {
                startDate = endDate;
            }
            if (endDate == null || endDate.isBefore(startDate)) {
                endDate = startDate;
            }

            bands.add(new PlannedBand(
                    startDate,
                    endDate,
                    1d,
                    0d
            ));
        }

        return bands;
    }

    private LocalDate resolveTaskResourceStartDate(XerTaskResource taskResource, XerActivity linkedActivity) {
        LocalDate targetStart = toLocalDate(taskResource.getTargetStartDate());
        if (targetStart != null) return targetStart;
        LocalDate actualStart = toLocalDate(taskResource.getActualStartDate());
        if (actualStart != null) return actualStart;
        LocalDate restart = toLocalDate(taskResource.getRestartDate());
        if (restart != null) return restart;
        if (linkedActivity != null) {
            LocalDate activityStart = toLocalDate(linkedActivity.getEarlyStartDate());
            if (activityStart != null) return activityStart;
            return toLocalDate(linkedActivity.getEarlyEndDate());
        }
        return null;
    }

    private LocalDate resolveTaskResourceEndDate(XerTaskResource taskResource, XerActivity linkedActivity) {
        LocalDate targetEnd = toLocalDate(taskResource.getTargetEndDate());
        if (targetEnd != null) return targetEnd;
        LocalDate lateEnd = toLocalDate(taskResource.getRemainingLateEndDate());
        if (lateEnd != null) return lateEnd;
        LocalDate actualEnd = toLocalDate(taskResource.getActualEndDate());
        if (actualEnd != null) return actualEnd;
        LocalDate reend = toLocalDate(taskResource.getReendDate());
        if (reend != null) return reend;
        if (linkedActivity != null) {
            LocalDate activityEnd = toLocalDate(linkedActivity.getEarlyEndDate());
            if (activityEnd != null) return activityEnd;
            return toLocalDate(linkedActivity.getEarlyStartDate());
        }
        return resolveTaskResourceStartDate(taskResource, linkedActivity);
    }

    private List<XerBaselineAnalysisResponse.CurvePoint> buildCurvePoints(List<PlannedBand> bands) {
        if (bands == null || bands.isEmpty()) {
            return List.of();
        }

        LocalDate minDate = bands.stream()
                .map(PlannedBand::startDate)
                .min(Comparator.naturalOrder())
                .orElse(null);
        LocalDate maxDate = bands.stream()
                .map(PlannedBand::endDate)
                .max(Comparator.naturalOrder())
                .orElse(null);

        if (minDate == null || maxDate == null) {
            return List.of();
        }

        List<LocalDate> curveDates = new ArrayList<>();
        LocalDate cursor = minDate;
        while (!cursor.isAfter(maxDate)) {
            curveDates.add(cursor);
            cursor = cursor.plusDays(7);
        }
        if (curveDates.isEmpty() || !curveDates.get(curveDates.size() - 1).equals(maxDate)) {
            curveDates.add(maxDate);
        }

        double totalUnits = bands.stream().mapToDouble(PlannedBand::plannedUnits).sum();
        List<XerBaselineAnalysisResponse.CurvePoint> points = new ArrayList<>();
        for (LocalDate curveDate : curveDates) {
            double cumulativeUnits = cumulativeUnitsAtDate(bands, curveDate);
            double cumulativeCost = cumulativeCostAtDate(bands, curveDate);
            double progressPercent = totalUnits <= 0 ? 0d : (cumulativeUnits / totalUnits) * 100d;
            points.add(new XerBaselineAnalysisResponse.CurvePoint(
                    curveDate.format(DateTimeFormatter.ofPattern("dd MMM")),
                    curveDate,
                    round(progressPercent),
                    round(cumulativeUnits),
                    round(cumulativeCost)
            ));
        }
        return points;
    }

    private double cumulativeUnitsAtDate(List<PlannedBand> bands, LocalDate date) {
        return bands.stream()
                .mapToDouble(band -> band.plannedUnits() * progressRatio(band, date))
                .sum();
    }

    private double cumulativeCostAtDate(List<PlannedBand> bands, LocalDate date) {
        return bands.stream()
                .mapToDouble(band -> band.plannedCost() * progressRatio(band, date))
                .sum();
    }

    private double progressRatio(PlannedBand band, LocalDate date) {
        if (date == null || date.isBefore(band.startDate())) {
            return 0d;
        }
        if (!date.isBefore(band.endDate())) {
            return 1d;
        }
        long totalDays = ChronoUnit.DAYS.between(band.startDate(), band.endDate()) + 1;
        if (totalDays <= 0) {
            return 1d;
        }
        long elapsedDays = ChronoUnit.DAYS.between(band.startDate(), date) + 1;
        double ratio = (double) elapsedDays / (double) totalDays;
        return Math.max(0d, Math.min(1d, ratio));
    }

    private LocalDate toLocalDate(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toLocalDate();
    }

    private double numericOrFallback(Double value, double fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private boolean containsIgnoreCase(List<String> values, String candidate) {
        if (values == null || values.isEmpty() || !StringUtils.hasText(candidate)) {
            return false;
        }
        String normalizedCandidate = candidate.trim();
        return values.stream().anyMatch(value -> value.equalsIgnoreCase(normalizedCandidate));
    }

    private List<String> readStringListJson(String json, String fallbackValue) {
        if (StringUtils.hasText(json)) {
            try {
                JsonNode node = objectMapper.readTree(json);
                if (node.isArray()) {
                    List<String> values = new ArrayList<>();
                    for (JsonNode item : node) {
                        String text = item.asText(null);
                        if (StringUtils.hasText(text)) {
                            values.add(text.trim());
                        }
                    }
                    if (!values.isEmpty()) {
                        return values.stream().distinct().toList();
                    }
                }
            } catch (IOException ignored) {
                // Fall through to legacy fallback handling.
            }
        }

        if (StringUtils.hasText(fallbackValue)
                && !"ALL".equalsIgnoreCase(fallbackValue.trim())
                && !fallbackValue.toLowerCase(Locale.ROOT).contains(" selected")) {
            return List.of(fallbackValue.trim());
        }
        return List.of();
    }

    private String buildScopeSummary(
            List<String> selectedTypes,
            List<String> selectedNames,
            boolean allResourceNames,
            int taskResourceCount,
            int activityCount
    ) {
        String typeSummary = selectedTypes == null || selectedTypes.isEmpty()
                ? "all resource types"
                : selectedTypes.size() + " selected resource type" + (selectedTypes.size() == 1 ? "" : "s");
        String nameSummary;
        if (allResourceNames) {
            nameSummary = "all names under the selected types";
        } else if (selectedNames == null || selectedNames.isEmpty()) {
            nameSummary = "all imported resource names";
        } else {
            nameSummary = selectedNames.size() + " selected resource name" + (selectedNames.size() == 1 ? "" : "s");
        }
        return "Current scope uses " + typeSummary + " and " + nameSummary
                + ", covering " + taskResourceCount + " task-resource rows across " + activityCount + " activities.";
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private List<XerProgressMeasurementOption> collectProgressMeasurementOptions(XerProject project, XerImportSession baselineImport) {
        Map<String, Set<String>> optionsByType = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        List<XerTaskResource> taskResources = xerTaskResourceRepository.findByProjectIdAndImportSessionIdOrderByResourceTypeAscResourceNameAsc(
                project.getId(),
                baselineImport.getId()
        );
        for (XerTaskResource resource : taskResources) {
            addOption(optionsByType, resource.getResourceType(), resource.getResourceName());
        }

        if (optionsByType.isEmpty()) {
            List<XerResource> resources = xerResourceRepository.findByProjectIdAndImportSessionIdOrderByResourceTypeAscResourceNameAsc(
                    project.getId(),
                    baselineImport.getId()
            );
            for (XerResource resource : resources) {
                addOption(optionsByType, resource.getResourceType(), resource.getResourceName());
            }
        }

        List<XerProgressMeasurementOption> options = new ArrayList<>();
        optionsByType.forEach((resourceType, resourceNames) -> resourceNames.forEach(resourceName ->
                options.add(new XerProgressMeasurementOption(resourceType, resourceName))
        ));
        return options;
    }

    private void addOption(Map<String, Set<String>> optionsByType, String resourceType, String resourceName) {
        if (!StringUtils.hasText(resourceName)) {
            return;
        }
        String normalizedType = StringUtils.hasText(resourceType) ? resourceType.trim() : "Unspecified";
        optionsByType.computeIfAbsent(normalizedType, key -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                .add(resourceName.trim());
    }

    private void markImportFailed(Long importSessionId, String errorMessage) {
        xerImportSessionRepository.findWithProjectById(importSessionId).ifPresent(importSession -> {
            importSession.setStatus("failed");
            importSession.setErrorMessage(errorMessage);
            importSession.setCompletedAt(LocalDateTime.now());
            importSession.setWorkflowStep("baseline_failed");
            importSession.setProgressJson(buildProgressSnapshotJson(
                    importSession,
                    "failed",
                    errorMessage,
                    100,
                    null,
                    Map.of(),
                    null
            ));
            xerImportSessionRepository.save(importSession);

            XerProject project = importSession.getProject();
            project.setBaselineStatus("failed");
            project.setWorkflowStage("baseline_failed");
            project.setStatus("baseline_failed");
            xerProjectRepository.save(project);
        });
    }

    private String summarizeException(Exception ex) {
        if (ex == null) {
            return "Unknown XER processing error";
        }
        if (StringUtils.hasText(ex.getMessage())) {
            return ex.getMessage();
        }
        return ex.getClass().getSimpleName();
    }

    private XerProject requireProject(Long projectId) {
        return xerProjectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "XER project not found"));
    }

    private XerImportSession resolveBaselineImport(Long projectId, Long baselineImportId) {
        if (baselineImportId != null) {
            return xerImportSessionRepository.findById(baselineImportId)
                    .filter(item -> item.getProject().getId().equals(projectId))
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Baseline import not found for this XER project"
                    ));
        }

        return xerImportSessionRepository.findTopByProjectIdAndImportTypeOrderByCreatedAtDesc(projectId, "baseline")
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Upload and process a baseline before saving progress measurement"
                ));
    }

    private List<String> buildNextSteps(
            XerImportSession latestBaselineImport,
            XerProgressMeasurementConfig progressMeasurementConfig
    ) {
        List<String> steps = new ArrayList<>();
        if (latestBaselineImport == null) {
            steps.add("Create a new report project and upload the baseline XER file.");
            return steps;
        }

        if ("processing".equalsIgnoreCase(latestBaselineImport.getStatus())) {
            steps.add("Baseline is processing in the background. Wait for completion before configuring progress measurement.");
            return steps;
        }

        if ("failed".equalsIgnoreCase(latestBaselineImport.getStatus())) {
            steps.add("Baseline processing failed. Re-upload the file or inspect the processor error.");
            return steps;
        }

        if (progressMeasurementConfig == null) {
            steps.add("Select progress measurement using rsrc_type and rsrc_name from the processed baseline.");
            return steps;
        }

        steps.add("Baseline is ready and the progress measurement is saved for future progress imports.");
        return steps;
    }

    private Path resolveProjectImportDirectory(Long projectId) {
        return resolveStorageRoot().resolve("project-" + projectId).resolve("baseline-imports");
    }

    private Path resolveStorageRoot() {
        if (StringUtils.hasText(configuredStorageDir)) {
            return Paths.get(configuredStorageDir).toAbsolutePath().normalize();
        }

        List<Path> candidates = List.of(
                Paths.get("").toAbsolutePath().resolve("..").resolve("xer_processing").resolve("runtime"),
                Paths.get("").toAbsolutePath().resolve("xer_processing").resolve("runtime"),
                Paths.get(System.getProperty("java.io.tmpdir")).resolve("xer-processing")
        );

        for (Path candidate : candidates) {
            Path normalized = candidate.normalize();
            if (Files.exists(normalized.getParent() != null ? normalized.getParent() : normalized)) {
                return normalized;
            }
        }

        return candidates.get(candidates.size() - 1).normalize();
    }

    private Path resolveProcessorScriptPath() {
        if (StringUtils.hasText(configuredProcessorScript)) {
            return Paths.get(configuredProcessorScript).toAbsolutePath().normalize();
        }

        List<Path> candidates = List.of(
                Paths.get("").toAbsolutePath().resolve("..").resolve("xer_processing").resolve("python").resolve("process_baseline.py"),
                Paths.get("").toAbsolutePath().resolve("xer_processing").resolve("python").resolve("process_baseline.py"),
                Paths.get("/app/xer_processing/python/process_baseline.py")
        );

        return candidates.stream()
                .map(Path::normalize)
                .filter(Files::exists)
                .findFirst()
                .orElse(candidates.get(0).normalize());
    }

    private String resolvePythonCommand() {
        if (StringUtils.hasText(configuredPythonCommand)) {
            return configuredPythonCommand.trim();
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "python" : "python3";
    }

    private String sanitizeFileName(String fileName) {
        String value = StringUtils.hasText(fileName) ? fileName.trim() : "baseline.xer";
        return value.replace("\\", "_").replace("/", "_");
    }

    private List<String> normalizeSelections(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String summarizeSelection(List<String> values, String singularLabel, String pluralLabel) {
        if (values == null || values.isEmpty()) {
            return "None";
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        return values.size() + " " + pluralLabel + " selected";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize selection");
        }
    }

    private void persistTable(
            XerImportSession importSession,
            JsonNode root,
            Map<String, Integer> persistedCounts,
            String tableKey,
            String activeMessage,
            String completedMessage,
            TablePersistenceAction action
    ) {
        updateImportProgress(importSession, "persisting", activeMessage, root, persistedCounts, tableKey);
        int persisted = action.persist();
        persistedCounts.put(tableKey, persisted);
        updateImportProgress(importSession, "persisting", completedMessage, root, persistedCounts, null);
    }

    private void updateImportProgress(
            XerImportSession importSession,
            String phase,
            String message,
            JsonNode root,
            Map<String, Integer> persistedCounts,
            String activeTableKey
    ) {
        importSession.setProgressJson(buildProgressSnapshotJson(
                importSession,
                phase,
                message,
                resolveProgressPercent(phase, root, persistedCounts, activeTableKey),
                root,
                persistedCounts,
                activeTableKey
        ));
        xerImportSessionRepository.save(importSession);
    }

    private int resolveProgressPercent(
            String phase,
            JsonNode root,
            Map<String, Integer> persistedCounts,
            String activeTableKey
    ) {
        return switch (phase) {
            case "uploading" -> 8;
            case "parsing" -> 18;
            case "parsed" -> 35;
            case "completed", "failed" -> 100;
            case "persisting" -> {
                int completedTables = 0;
                for (BaselineTableProgressConfig table : BASELINE_TABLES) {
                    if (persistedCounts.containsKey(table.tableKey())) {
                        completedTables++;
                    }
                }
                double activeBonus = StringUtils.hasText(activeTableKey) ? 0.5d : 0d;
                double ratio = (completedTables + activeBonus) / BASELINE_TABLES.size();
                yield Math.min(96, 35 + (int) Math.round(ratio * 60));
            }
            default -> 12;
        };
    }

    private String buildProgressSnapshotJson(
            XerImportSession importSession,
            String phase,
            String message,
            int percent,
            JsonNode root,
            Map<String, Integer> persistedCounts,
            String activeTableKey
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", phase);
        payload.put("message", message);
        payload.put("percent", percent);
        payload.put("fileName", importSession.getOriginalFileName());
        payload.put("startedAt", importSession.getStartedAt());
        payload.put("updatedAt", LocalDateTime.now());

        int completedTables = 0;
        List<Map<String, Object>> tables = new ArrayList<>();
        for (BaselineTableProgressConfig table : BASELINE_TABLES) {
            int detectedRows = detectedRowCount(root, table);
            Integer persistedRows = persistedCounts.get(table.tableKey());
            String tableStatus;
            if (persistedRows != null) {
                tableStatus = detectedRows == 0 ? "skipped" : "completed";
                completedTables++;
            } else if (StringUtils.hasText(activeTableKey) && table.tableKey().equalsIgnoreCase(activeTableKey)) {
                tableStatus = "persisting";
            } else if (root != null) {
                tableStatus = detectedRows == 0 ? "skipped" : "detected";
            } else {
                tableStatus = "pending";
            }

            Map<String, Object> tablePayload = new LinkedHashMap<>();
            tablePayload.put("key", table.tableKey());
            tablePayload.put("label", table.label());
            tablePayload.put("status", tableStatus);
            tablePayload.put("detectedRows", detectedRows);
            tablePayload.put("persistedRows", persistedRows == null ? (detectedRows == 0 && root != null ? 0 : null) : persistedRows);
            tables.add(tablePayload);
        }

        payload.put("completedTables", completedTables);
        payload.put("totalTables", BASELINE_TABLES.size());
        payload.put("tables", tables);

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize XER progress snapshot");
        }
    }

    private int detectedRowCount(JsonNode root, BaselineTableProgressConfig table) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return 0;
        }

        JsonNode detectedTables = root.path("summary").path("detectedTables");
        if (detectedTables.has(table.tableKey()) && detectedTables.path(table.tableKey()).canConvertToInt()) {
            return detectedTables.path(table.tableKey()).asInt();
        }

        JsonNode payloadRows = root.path(table.payloadKey());
        return payloadRows.isArray() ? payloadRows.size() : 0;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String textValue = value.asText();
        return StringUtils.hasText(textValue) ? textValue.trim() : null;
    }

    private Double decimal(JsonNode node, String field) {
        String value = text(node, field);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value.trim(), formatter);
            } catch (DateTimeParseException ignored) {
                // try next formatter
            }
        }
        return null;
    }

    private LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String candidate = value.trim();
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(candidate, formatter);
            } catch (DateTimeParseException ignored) {
                // try next formatter
            }
        }

        LocalDate date = parseDate(candidate);
        return date == null ? null : date.atStartOfDay();
    }

    @FunctionalInterface
    private interface TablePersistenceAction {
        int persist();
    }

    private record PlannedBand(LocalDate startDate, LocalDate endDate, double plannedUnits, double plannedCost) {
    }

    private record BaselineTableProgressConfig(String tableKey, String payloadKey, String label) {
    }
}
