package com.cxalloy.integration.service;

import com.cxalloy.integration.dto.ReportAutomationSettingsRequest;
import com.cxalloy.integration.dto.SavedReportRequest;
import com.cxalloy.integration.model.ReportAutomationSetting;
import com.cxalloy.integration.repository.ReportAutomationSettingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Transactional
public class ReportAutomationService {

    private static final Logger logger = LoggerFactory.getLogger(ReportAutomationService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final ReportAutomationSettingRepository repository;
    private final SavedReportService savedReportService;
    private final SyncService syncService;
    private final ProjectAccessService projectAccessService;
    private final ProviderContextService providerContextService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final Path exportRoot;
    private final ZoneId automationZone;
    private final String reportMailFrom;
    private final AtomicBoolean schedulerRunning = new AtomicBoolean(false);

    public ReportAutomationService(ReportAutomationSettingRepository repository,
                                   SavedReportService savedReportService,
                                   SyncService syncService,
                                   ProjectAccessService projectAccessService,
                                   ProviderContextService providerContextService,
                                   ObjectMapper objectMapper,
                                   ObjectProvider<JavaMailSender> mailSenderProvider,
                                   @Value("${app.reports.export-root:/tmp/report-exports}") String exportRootValue,
                                   @Value("${app.reports.automation.zone:Asia/Dubai}") String automationZoneValue,
                                   @Value("${app.reports.mail.from:${app.leads.from-address:no-reply@modum.local}}") String reportMailFrom,
                                   @Value("${spring.mail.username:}") String mailUsername) {
        this.repository = repository;
        this.savedReportService = savedReportService;
        this.syncService = syncService;
        this.projectAccessService = projectAccessService;
        this.providerContextService = providerContextService;
        this.objectMapper = objectMapper;
        this.mailSenderProvider = mailSenderProvider;
        this.exportRoot = Path.of(StringUtils.hasText(exportRootValue) ? exportRootValue.trim() : "/tmp/report-exports");
        this.automationZone = parseZone(automationZoneValue);
        this.reportMailFrom = resolveMailFrom(reportMailFrom, mailUsername);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSettings(String projectId) {
        projectAccessService.requireAdmin();
        projectAccessService.requireProjectAccess(projectId);
        Optional<ReportAutomationSetting> existing = repository.findByProjectIdAndProvider(projectId.trim(), providerContextService.currentProviderKey());
        Map<String, Object> response = existing.map(this::toResponse).orElseGet(LinkedHashMap::new);
        response.putIfAbsent("projectId", projectId.trim());
        response.putIfAbsent("enabled", false);
        response.putIfAbsent("scheduleTime", "08:00");
        response.putIfAbsent("exportFolderPath", "");
        response.putIfAbsent("exportFormat", "pdf");
        response.putIfAbsent("syncBeforeExport", true);
        response.putIfAbsent("useCurrentDateAsEndDate", false);
        response.putIfAbsent("emailAfterExport", false);
        response.putIfAbsent("emailTo", "");
        response.putIfAbsent("emailCc", "");
        response.putIfAbsent("emailSubject", "");
        response.putIfAbsent("emailBody", "");
        response.putIfAbsent("emailAttachFile", true);
        response.put("exportRoot", exportRoot.toString());
        response.put("automationZone", automationZone.getId());
        response.put("mailFrom", reportMailFrom);
        return response;
    }

    public Map<String, Object> saveSettings(ReportAutomationSettingsRequest request) {
        projectAccessService.requireAdmin();
        ReportAutomationSetting setting = upsertSetting(request);
        repository.save(setting);
        return toResponse(setting);
    }

    public Map<String, Object> runNow(ReportAutomationSettingsRequest request) {
        projectAccessService.requireAdmin();
        ReportAutomationSetting setting = upsertSetting(request);
        repository.save(setting);
        return execute(setting, true);
    }

    @Scheduled(fixedDelay = 60000)
    public void runDueSchedules() {
        if (!schedulerRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            List<ReportAutomationSetting> dueSettings = repository.findByEnabledTrueOrderByUpdatedAtAsc();
            if (dueSettings.isEmpty()) {
                return;
            }
            ZonedDateTime now = ZonedDateTime.now(automationZone);
            for (ReportAutomationSetting setting : dueSettings) {
                if (isDue(setting, now)) {
                    try {
                        logger.info("Running scheduled report export for project {} at {} {}", setting.getProjectId(), now.toLocalDate(), now.toLocalTime().withSecond(0).withNano(0));
                        execute(setting, false);
                    } catch (Exception ex) {
                        logger.error("Scheduled report export failed for project {}", setting.getProjectId(), ex);
                    }
                }
            }
        } finally {
            schedulerRunning.set(false);
        }
    }

    private ReportAutomationSetting upsertSetting(ReportAutomationSettingsRequest request) {
        if (request == null || !StringUtils.hasText(request.getProjectId())) {
            throw new IllegalArgumentException("projectId is required");
        }
        String projectId = request.getProjectId().trim();
        projectAccessService.requireProjectAccess(projectId);
        String providerKey = providerContextService.currentProviderKey();
        ReportAutomationSetting setting = repository.findByProjectIdAndProvider(projectId, providerKey)
                .orElseGet(ReportAutomationSetting::new);

        boolean enabled = Boolean.TRUE.equals(request.getEnabled());
        String scheduleTime = normalizeScheduleTime(request.getScheduleTime());
        String exportFolderPath = normalizeFolderPath(request.getExportFolderPath());
        String exportFormat = normalizeExportFormat(request.getExportFormat());
        boolean syncBeforeExport = request.getSyncBeforeExport() == null || request.getSyncBeforeExport();
        boolean useCurrentDateAsEndDate = Boolean.TRUE.equals(request.getUseCurrentDateAsEndDate());
        boolean emailAfterExport = Boolean.TRUE.equals(request.getEmailAfterExport());
        String emailTo = normalizeFreeText(request.getEmailTo());
        String emailCc = normalizeFreeText(request.getEmailCc());
        String emailSubject = normalizeFreeText(request.getEmailSubject());
        String emailBody = normalizeFreeText(request.getEmailBody());
        boolean emailAttachFile = request.getEmailAttachFile() == null || request.getEmailAttachFile();
        ReportAutomationSettingsRequest storedRequest = copyReportRequest(request);

        if (enabled && !StringUtils.hasText(exportFolderPath)) {
            throw new IllegalArgumentException("Export folder location is required when scheduled export is enabled");
        }
        if (emailAfterExport && parseEmailList(emailTo).isEmpty()) {
            throw new IllegalArgumentException("At least one email recipient is required when post-export email is enabled");
        }

        setting.setProjectId(projectId);
        setting.setProvider(providerKey);
        setting.setEnabled(enabled);
        setting.setScheduleTime(scheduleTime);
        setting.setExportFolderPath(exportFolderPath);
        setting.setExportFormat(exportFormat);
        setting.setSyncBeforeExport(syncBeforeExport);
        storedRequest.setEnabled(enabled);
        storedRequest.setScheduleTime(scheduleTime);
        storedRequest.setExportFolderPath(exportFolderPath);
        storedRequest.setExportFormat(exportFormat);
        storedRequest.setSyncBeforeExport(syncBeforeExport);
        storedRequest.setUseCurrentDateAsEndDate(useCurrentDateAsEndDate);
        storedRequest.setEmailAfterExport(emailAfterExport);
        storedRequest.setEmailTo(emailTo);
        storedRequest.setEmailCc(emailCc);
        storedRequest.setEmailSubject(emailSubject);
        storedRequest.setEmailBody(emailBody);
        storedRequest.setEmailAttachFile(emailAttachFile);
        setting.setRequestJson(writeJson(storedRequest));
        if (!StringUtils.hasText(setting.getCreatedBy())) {
            setting.setCreatedBy(projectAccessService.currentUsername());
        }
        setting.setUpdatedBy(projectAccessService.currentUsername());
        return setting;
    }

    private Map<String, Object> execute(ReportAutomationSetting setting, boolean manualRun) {
        return runWithAutomationContext(setting, () -> {
            ReportAutomationSettingsRequest automationRequest = readAutomationRequest(setting.getRequestJson());
            SavedReportRequest request = readReportRequest(setting.getRequestJson());
            if (Boolean.TRUE.equals(automationRequest.getUseCurrentDateAsEndDate())) {
                request.setDateTo(LocalDate.now(automationZone).toString());
            }
            if (setting.isSyncBeforeExport()) {
                syncService.syncProject(setting.getProjectId());
            }

            Map<String, Object> report = savedReportService.generateReport(request);
            Long reportId = longValue(report.get("id"));
            byte[] bytes = savedReportService.downloadReport(reportId, setting.getExportFormat());
            String fileName = savedReportService.downloadFileName(reportId, setting.getExportFormat());
            Path targetFolder = resolveExportFolder(setting.getExportFolderPath());
            Files.createDirectories(targetFolder);
            Path outputFile = uniqueTarget(targetFolder.resolve(fileName));
            Files.write(outputFile, bytes, StandardOpenOption.CREATE_NEW);

            setting.setLastRunAt(LocalDateTime.now(automationZone));
            setting.setLastOutputPath(outputFile.toString());
            setting.setLastGeneratedReportId(reportId);
            if (Boolean.TRUE.equals(automationRequest.getEmailAfterExport())) {
                sendCompletionEmail(automationRequest, request, report, outputFile);
            }
            setting.setLastRunStatus("SUCCESS");
            setting.setLastRunMessage(successMessage(Boolean.TRUE.equals(automationRequest.getEmailAfterExport()), manualRun));
            repository.save(setting);

            Map<String, Object> response = toResponse(setting);
            response.put("savedTo", outputFile.toString());
            response.put("reportId", reportId);
            response.put("reportTitle", report.get("title"));
            response.put("ranMode", manualRun ? "manual" : "scheduled");
            response.put("emailSent", Boolean.TRUE.equals(automationRequest.getEmailAfterExport()));
            return response;
        });
    }

    private boolean isDue(ReportAutomationSetting setting, ZonedDateTime now) {
        if (!setting.isEnabled()) {
            return false;
        }
        LocalTime scheduledTime = parseScheduleTime(setting.getScheduleTime());
        if (now.toLocalTime().isBefore(scheduledTime)) {
            return false;
        }
        if (setting.getLastRunAt() == null) {
            return true;
        }
        LocalDateTime todayScheduledWindow = LocalDateTime.of(now.toLocalDate(), scheduledTime);
        return setting.getLastRunAt().isBefore(todayScheduledWindow);
    }

    private <T> T runWithAutomationContext(ReportAutomationSetting setting, AutomationAction<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext next = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                firstNonBlank(setting.getCreatedBy(), "system"),
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        auth.setDetails(setting.getProvider());
        next.setAuthentication(auth);
        SecurityContextHolder.setContext(next);
        try {
            return action.run();
        } catch (Exception ex) {
            setting.setLastRunAt(LocalDateTime.now(automationZone));
            setting.setLastRunStatus("FAILED");
            setting.setLastRunMessage(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            repository.save(setting);
            throw new IllegalStateException("Report export failed: " + setting.getLastRunMessage(), ex);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private Map<String, Object> toResponse(ReportAutomationSetting setting) {
        ReportAutomationSettingsRequest storedRequest = readAutomationRequestSafely(setting.getRequestJson());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", setting.getId());
        response.put("projectId", setting.getProjectId());
        response.put("provider", setting.getProvider());
        response.put("enabled", setting.isEnabled());
        response.put("scheduleTime", normalizeScheduleTime(setting.getScheduleTime()));
        response.put("exportFolderPath", firstNonBlank(setting.getExportFolderPath(), ""));
        response.put("resolvedExportFolderPath", resolveExportFolder(setting.getExportFolderPath()).toString());
        response.put("exportFormat", normalizeExportFormat(setting.getExportFormat()));
        response.put("syncBeforeExport", setting.isSyncBeforeExport());
        response.put("useCurrentDateAsEndDate", storedRequest.getUseCurrentDateAsEndDate() != null && storedRequest.getUseCurrentDateAsEndDate());
        response.put("emailAfterExport", storedRequest.getEmailAfterExport() != null && storedRequest.getEmailAfterExport());
        response.put("emailTo", firstNonBlank(storedRequest.getEmailTo(), ""));
        response.put("emailCc", firstNonBlank(storedRequest.getEmailCc(), ""));
        response.put("emailSubject", firstNonBlank(storedRequest.getEmailSubject(), ""));
        response.put("emailBody", firstNonBlank(storedRequest.getEmailBody(), ""));
        response.put("emailAttachFile", storedRequest.getEmailAttachFile() == null || storedRequest.getEmailAttachFile());
        response.put("createdBy", setting.getCreatedBy());
        response.put("updatedBy", setting.getUpdatedBy());
        response.put("lastRunAt", setting.getLastRunAt());
        response.put("lastRunStatus", firstNonBlank(setting.getLastRunStatus(), ""));
        response.put("lastRunMessage", firstNonBlank(setting.getLastRunMessage(), ""));
        response.put("lastOutputPath", firstNonBlank(setting.getLastOutputPath(), ""));
        response.put("lastGeneratedReportId", setting.getLastGeneratedReportId());
        response.put("createdAt", setting.getCreatedAt());
        response.put("updatedAt", setting.getUpdatedAt());
        response.put("exportRoot", exportRoot.toString());
        response.put("automationZone", automationZone.getId());
        response.put("mailFrom", reportMailFrom);
        return response;
    }

    private void sendCompletionEmail(ReportAutomationSettingsRequest automationRequest,
                                     SavedReportRequest request,
                                     Map<String, Object> report,
                                     Path outputFile) throws Exception {
        List<String> recipients = parseEmailList(automationRequest.getEmailTo());
        if (recipients.isEmpty()) {
            throw new IllegalStateException("No recipients are configured for report automation email");
        }
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new IllegalStateException("Mail sender is not configured on this server");
        }

        String reportTitle = firstNonBlank(stringValue(report.get("title")), request.getTitle(), "Scheduled report");
        String subject = StringUtils.hasText(automationRequest.getEmailSubject())
                ? automationRequest.getEmailSubject().trim()
                : reportTitle + " export is ready";
        String body = StringUtils.hasText(automationRequest.getEmailBody())
                ? automationRequest.getEmailBody().trim()
                : "";

        var message = mailSender.createMimeMessage();
        boolean attachFile = automationRequest.getEmailAttachFile() == null || automationRequest.getEmailAttachFile();
        var helper = new MimeMessageHelper(message, attachFile, "UTF-8");
        if (StringUtils.hasText(reportMailFrom)) {
            helper.setFrom(reportMailFrom);
        }
        helper.setTo(recipients.toArray(new String[0]));
        List<String> ccRecipients = parseEmailList(automationRequest.getEmailCc());
        if (!ccRecipients.isEmpty()) {
            helper.setCc(ccRecipients.toArray(new String[0]));
        }
        helper.setSubject(subject);
        helper.setText(body, false);
        if (attachFile) {
            helper.addAttachment(outputFile.getFileName().toString(), outputFile.toFile());
        }
        mailSender.send(message);
        logger.info("Scheduled report email sent for project {} to {}", request.getProjectId(), recipients);
    }

    private List<String> parseEmailList(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split("[,;\\r\\n]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private String normalizeFreeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String successMessage(boolean emailed, boolean manualRun) {
        if (emailed) {
            return manualRun ? "Manual export completed and email sent" : "Scheduled export completed and email sent";
        }
        return manualRun ? "Manual export completed" : "Scheduled export completed";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private ReportAutomationSettingsRequest copyReportRequest(ReportAutomationSettingsRequest source) {
        ReportAutomationSettingsRequest target = new ReportAutomationSettingsRequest();
        target.setProjectId(source.getProjectId());
        target.setTitle(source.getTitle());
        target.setReportType(source.getReportType());
        target.setDateFrom(source.getDateFrom());
        target.setDateTo(source.getDateTo());
        target.setSections(source.getSections());
        target.setIssueStatuses(source.getIssueStatuses());
        target.setChecklistStatuses(source.getChecklistStatuses());
        target.setEquipmentTypes(source.getEquipmentTypes());
        target.setSectionSettings(source.getSectionSettings());
        target.setSummaryText(source.getSummaryText());
        target.setSafetyNotes(source.getSafetyNotes());
        target.setCommercialNotes(source.getCommercialNotes());
        target.setCustomSectionText(source.getCustomSectionText());
        target.setProgressPhotosText(source.getProgressPhotosText());
        target.setProjectDescription(source.getProjectDescription());
        target.setClientName(source.getClientName());
        target.setProjectCode(source.getProjectCode());
        target.setShiftWindow(source.getShiftWindow());
        target.setReportAuthor(source.getReportAuthor());
        target.setPeopleOnSite(source.getPeopleOnSite());
        target.setLogoLeft(source.getLogoLeft());
        target.setLogoRight(source.getLogoRight());
        target.setEnabled(source.getEnabled());
        target.setScheduleTime(source.getScheduleTime());
        target.setExportFolderPath(source.getExportFolderPath());
        target.setExportFormat(source.getExportFormat());
        target.setSyncBeforeExport(source.getSyncBeforeExport());
        target.setUseCurrentDateAsEndDate(source.getUseCurrentDateAsEndDate());
        target.setEmailAfterExport(source.getEmailAfterExport());
        target.setEmailTo(source.getEmailTo());
        target.setEmailCc(source.getEmailCc());
        target.setEmailSubject(source.getEmailSubject());
        target.setEmailBody(source.getEmailBody());
        target.setEmailAttachFile(source.getEmailAttachFile());
        return target;
    }

    private SavedReportRequest readReportRequest(String json) {
        if (!StringUtils.hasText(json)) {
            throw new IllegalStateException("No report template is saved for automation");
        }
        try {
            return objectMapper.readValue(json, SavedReportRequest.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read saved report automation request", ex);
        }
    }

    private ReportAutomationSettingsRequest readAutomationRequest(String json) {
        if (!StringUtils.hasText(json)) {
            throw new IllegalStateException("No report automation settings are saved yet");
        }
        try {
            return objectMapper.readValue(json, ReportAutomationSettingsRequest.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read saved report automation settings", ex);
        }
    }

    private ReportAutomationSettingsRequest readAutomationRequestSafely(String json) {
        if (!StringUtils.hasText(json)) {
            return new ReportAutomationSettingsRequest();
        }
        try {
            return objectMapper.readValue(json, ReportAutomationSettingsRequest.class);
        } catch (IOException ex) {
            logger.warn("Could not read saved report automation settings JSON: {}", ex.getMessage());
            return new ReportAutomationSettingsRequest();
        }
    }

    private String writeJson(ReportAutomationSettingsRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not save report automation settings", ex);
        }
    }

    private Path resolveExportFolder(String folderPath) {
        String normalized = normalizeFolderPath(folderPath);
        if (!StringUtils.hasText(normalized)) {
            return exportRoot;
        }
        Path path = Path.of(normalized);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path resolved = exportRoot.resolve(path).normalize();
        if (!resolved.startsWith(exportRoot.normalize())) {
            throw new IllegalArgumentException("Folder path must stay within the configured export root when using a relative path");
        }
        return resolved;
    }

    private Path uniqueTarget(Path desired) {
        if (!Files.exists(desired)) {
            return desired;
        }
        String fileName = desired.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot >= 0 ? fileName.substring(dot) : "";
        String stamp = LocalDateTime.now(automationZone).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return desired.getParent().resolve(base + "-" + stamp + ext);
    }

    private ZoneId parseZone(String value) {
        try {
            return ZoneId.of(firstNonBlank(value, "Asia/Dubai"));
        } catch (DateTimeException ex) {
            return ZoneId.of("Asia/Dubai");
        }
    }

    private String resolveMailFrom(String configuredFrom, String mailUsername) {
        String normalizedFrom = firstNonBlank(configuredFrom).trim();
        String normalizedMailUsername = firstNonBlank(mailUsername).trim();
        if (StringUtils.hasText(normalizedFrom) && !normalizedFrom.toLowerCase(Locale.ROOT).endsWith(".local")) {
            return normalizedFrom;
        }
        if (StringUtils.hasText(normalizedMailUsername)) {
            return normalizedMailUsername;
        }
        return StringUtils.hasText(normalizedFrom) ? normalizedFrom : "no-reply@modum.local";
    }

    private LocalTime parseScheduleTime(String value) {
        try {
            return LocalTime.parse(normalizeScheduleTime(value), TIME_FORMATTER);
        } catch (DateTimeParseException ex) {
            return LocalTime.of(8, 0);
        }
    }

    private String normalizeScheduleTime(String value) {
        if (!StringUtils.hasText(value)) {
            return "08:00";
        }
        try {
            return LocalTime.parse(value.trim()).format(TIME_FORMATTER);
        } catch (DateTimeParseException ex) {
            try {
                return LocalTime.parse(value.trim(), TIME_FORMATTER).format(TIME_FORMATTER);
            } catch (DateTimeParseException ignored) {
                throw new IllegalArgumentException("Schedule time must be in HH:mm format");
            }
        }
    }

    private String normalizeExportFormat(String value) {
        String normalized = firstNonBlank(value, "pdf").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "pdf", "csv", "json" -> normalized;
            default -> throw new IllegalArgumentException("Export format must be pdf, csv, or json");
        };
    }

    private String normalizeFolderPath(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @FunctionalInterface
    private interface AutomationAction<T> {
        T run() throws Exception;
    }
}
