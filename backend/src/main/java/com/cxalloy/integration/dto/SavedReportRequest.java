package com.cxalloy.integration.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SavedReportRequest {

    private String projectId;
    private String title;
    private String reportType;
    private String dateFrom;
    private String dateTo;
    private List<String> sections = new ArrayList<>();
    private List<String> issueStatuses = new ArrayList<>();
    private List<String> checklistStatuses = new ArrayList<>();
    private List<String> equipmentTypes = new ArrayList<>();
    private List<Map<String, Object>> sectionSettings = new ArrayList<>();
    private String summaryText;
    private String safetyNotes;
    private String commercialNotes;
    private String customSectionText;
    private String progressPhotosText;
    private String projectDescription;
    private String clientName;
    private String projectCode;
    private String shiftWindow;
    private String reportAuthor;
    private String peopleOnSite;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }
    public String getDateFrom() { return dateFrom; }
    public void setDateFrom(String dateFrom) { this.dateFrom = dateFrom; }
    public String getDateTo() { return dateTo; }
    public void setDateTo(String dateTo) { this.dateTo = dateTo; }
    public List<String> getSections() { return sections; }
    public void setSections(List<String> sections) { this.sections = sections == null ? new ArrayList<>() : sections; }
    public List<String> getIssueStatuses() { return issueStatuses; }
    public void setIssueStatuses(List<String> issueStatuses) { this.issueStatuses = issueStatuses == null ? new ArrayList<>() : issueStatuses; }
    public List<String> getChecklistStatuses() { return checklistStatuses; }
    public void setChecklistStatuses(List<String> checklistStatuses) { this.checklistStatuses = checklistStatuses == null ? new ArrayList<>() : checklistStatuses; }
    public List<String> getEquipmentTypes() { return equipmentTypes; }
    public void setEquipmentTypes(List<String> equipmentTypes) { this.equipmentTypes = equipmentTypes == null ? new ArrayList<>() : equipmentTypes; }
    public List<Map<String, Object>> getSectionSettings() { return sectionSettings; }
    public void setSectionSettings(List<Map<String, Object>> sectionSettings) { this.sectionSettings = sectionSettings == null ? new ArrayList<>() : sectionSettings; }
    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }
    public String getSafetyNotes() { return safetyNotes; }
    public void setSafetyNotes(String safetyNotes) { this.safetyNotes = safetyNotes; }
    public String getCommercialNotes() { return commercialNotes; }
    public void setCommercialNotes(String commercialNotes) { this.commercialNotes = commercialNotes; }
    public String getCustomSectionText() { return customSectionText; }
    public void setCustomSectionText(String customSectionText) { this.customSectionText = customSectionText; }
    public String getProgressPhotosText() { return progressPhotosText; }
    public void setProgressPhotosText(String progressPhotosText) { this.progressPhotosText = progressPhotosText; }
    public String getProjectDescription() { return projectDescription; }
    public void setProjectDescription(String projectDescription) { this.projectDescription = projectDescription; }
    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }
    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }
    public String getShiftWindow() { return shiftWindow; }
    public void setShiftWindow(String shiftWindow) { this.shiftWindow = shiftWindow; }
    public String getReportAuthor() { return reportAuthor; }
    public void setReportAuthor(String reportAuthor) { this.reportAuthor = reportAuthor; }
    public String getPeopleOnSite() { return peopleOnSite; }
    public void setPeopleOnSite(String peopleOnSite) { this.peopleOnSite = peopleOnSite; }
}
