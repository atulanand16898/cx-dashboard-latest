package com.cxalloy.integration.dto;

public class ReportAutomationSettingsRequest extends SavedReportRequest {

    private Boolean enabled;
    private String scheduleTime;
    private String exportFolderPath;
    private String exportFormat;
    private Boolean syncBeforeExport;
    private Boolean useCurrentDateAsEndDate;
    private Boolean emailAfterExport;
    private String emailTo;
    private String emailCc;
    private String emailSubject;
    private String emailBody;
    private Boolean emailAttachFile;

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getScheduleTime() { return scheduleTime; }
    public void setScheduleTime(String scheduleTime) { this.scheduleTime = scheduleTime; }
    public String getExportFolderPath() { return exportFolderPath; }
    public void setExportFolderPath(String exportFolderPath) { this.exportFolderPath = exportFolderPath; }
    public String getExportFormat() { return exportFormat; }
    public void setExportFormat(String exportFormat) { this.exportFormat = exportFormat; }
    public Boolean getSyncBeforeExport() { return syncBeforeExport; }
    public void setSyncBeforeExport(Boolean syncBeforeExport) { this.syncBeforeExport = syncBeforeExport; }
    public Boolean getUseCurrentDateAsEndDate() { return useCurrentDateAsEndDate; }
    public void setUseCurrentDateAsEndDate(Boolean useCurrentDateAsEndDate) { this.useCurrentDateAsEndDate = useCurrentDateAsEndDate; }
    public Boolean getEmailAfterExport() { return emailAfterExport; }
    public void setEmailAfterExport(Boolean emailAfterExport) { this.emailAfterExport = emailAfterExport; }
    public String getEmailTo() { return emailTo; }
    public void setEmailTo(String emailTo) { this.emailTo = emailTo; }
    public String getEmailCc() { return emailCc; }
    public void setEmailCc(String emailCc) { this.emailCc = emailCc; }
    public String getEmailSubject() { return emailSubject; }
    public void setEmailSubject(String emailSubject) { this.emailSubject = emailSubject; }
    public String getEmailBody() { return emailBody; }
    public void setEmailBody(String emailBody) { this.emailBody = emailBody; }
    public Boolean getEmailAttachFile() { return emailAttachFile; }
    public void setEmailAttachFile(Boolean emailAttachFile) { this.emailAttachFile = emailAttachFile; }
}
