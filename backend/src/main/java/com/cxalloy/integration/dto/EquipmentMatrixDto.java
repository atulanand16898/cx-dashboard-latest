package com.cxalloy.integration.dto;

import java.util.List;
import java.util.Map;

/**
 * DTO returned by GET /api/equipment/matrix
 *
 * Represents the Equipment-Checklist Matrix view:
 * each row = one equipment item,
 * each column = one commissioning slice (L1/L2/L3/issues/tests).
 *
 * Mirrors the screenshot layout:
 *   EQUIPMENT/SYSTEM | L1 | L2 | L3 | ISSUES | TESTS | TOTAL
 */
public class EquipmentMatrixDto {

    /** Top-level summary stats shown in the header pills */
    private int totalUnits;
    private int totalSystems;
    private int totalTypes;

    /** Flat list of equipment rows (each with per-level checklist breakdowns) */
    private List<EquipmentRow> rows;

    // ── Inner types ──────────────────────────────────────────────────────────

    public static class EquipmentRow {
        private String equipmentId;       // DB id (String for JSON)
        private String externalId;        // CxAlloy equipment_id
        private String name;              // Equipment display name
        private String tag;               // e.g. "DXB9-OB022X-MDB-031"
        private String description;
        private String status;            // "Closed", "Open", etc.
        private String systemName;        // parent system / group name (buildingId or discipline)
        private String assignedTo;        // Assigned person
        private String spaceName;
        private String equipmentType;
        private String discipline;

        /** Aggregated checklist stats per commissioning level */
        private ChecklistLevelStat l1Checklist;
        private ChecklistLevelStat l2Checklist;
        private ChecklistLevelStat l3Checklist;

        /** Checklist counts */
        private int checklistTotal;
        private int checklistClosed;

        /** Issue counts */
        private int issuesOpen;
        private int issuesClosed;

        /** Test counts */
        private int testsTotal;
        private int testsClosed;

        /** Total checklists across all levels */
        private int total;

        // Getters / Setters
        public String getEquipmentId() { return equipmentId; }
        public void setEquipmentId(String v) { this.equipmentId = v; }
        public String getExternalId() { return externalId; }
        public void setExternalId(String v) { this.externalId = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getTag() { return tag; }
        public void setTag(String v) { this.tag = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { this.description = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { this.status = v; }
        public String getSystemName() { return systemName; }
        public void setSystemName(String v) { this.systemName = v; }
        public String getAssignedTo() { return assignedTo; }
        public void setAssignedTo(String v) { this.assignedTo = v; }
        public String getSpaceName() { return spaceName; }
        public void setSpaceName(String v) { this.spaceName = v; }
        public String getEquipmentType() { return equipmentType; }
        public void setEquipmentType(String v) { this.equipmentType = v; }
        public String getDiscipline() { return discipline; }
        public void setDiscipline(String v) { this.discipline = v; }
        public ChecklistLevelStat getL1Checklist() { return l1Checklist; }
        public void setL1Checklist(ChecklistLevelStat v) { this.l1Checklist = v; }
        public ChecklistLevelStat getL2Checklist() { return l2Checklist; }
        public void setL2Checklist(ChecklistLevelStat v) { this.l2Checklist = v; }
        public ChecklistLevelStat getL3Checklist() { return l3Checklist; }
        public void setL3Checklist(ChecklistLevelStat v) { this.l3Checklist = v; }
        public int getChecklistTotal() { return checklistTotal; }
        public void setChecklistTotal(int v) { this.checklistTotal = v; }
        public int getChecklistClosed() { return checklistClosed; }
        public void setChecklistClosed(int v) { this.checklistClosed = v; }
        public int getIssuesOpen() { return issuesOpen; }
        public void setIssuesOpen(int v) { this.issuesOpen = v; }
        public int getIssuesClosed() { return issuesClosed; }
        public void setIssuesClosed(int v) { this.issuesClosed = v; }
        public int getTestsTotal() { return testsTotal; }
        public void setTestsTotal(int v) { this.testsTotal = v; }
        public int getTestsClosed() { return testsClosed; }
        public void setTestsClosed(int v) { this.testsClosed = v; }
        public int getTotal() { return total; }
        public void setTotal(int v) { this.total = v; }
    }

    /**
     * Stats for one tag-level column for a given equipment row.
     * Mirrors: "Checklist Approved / 16/16 closed | 0 issues / 16/16" in screenshot.
     */
    public static class ChecklistLevelStat {
        /** "Checklist Approved", "In Progress", "Not Started", "Ready", etc. */
        private String statusLabel;
        private int total;
        private int closed;
        private int issueCount;
        private int issuesClosed;
        /** 0-100 completion percentage */
        private int completionPct;
        /** Vendor/discipline name for L2/L3 columns e.g. "ELECTRICAL" */
        private String vendorName;
        /** e.g. "Ready", "Approved" sub-label */
        private String vendorStatus;

        public String getStatusLabel() { return statusLabel; }
        public void setStatusLabel(String v) { this.statusLabel = v; }
        public int getTotal() { return total; }
        public void setTotal(int v) { this.total = v; }
        public int getClosed() { return closed; }
        public void setClosed(int v) { this.closed = v; }
        public int getIssueCount() { return issueCount; }
        public void setIssueCount(int v) { this.issueCount = v; }
        public int getIssuesClosed() { return issuesClosed; }
        public void setIssuesClosed(int v) { this.issuesClosed = v; }
        public int getCompletionPct() { return completionPct; }
        public void setCompletionPct(int v) { this.completionPct = v; }
        public String getVendorName() { return vendorName; }
        public void setVendorName(String v) { this.vendorName = v; }
        public String getVendorStatus() { return vendorStatus; }
        public void setVendorStatus(String v) { this.vendorStatus = v; }
    }

    // ── Root getters/setters ─────────────────────────────────────────────────

    public int getTotalUnits() { return totalUnits; }
    public void setTotalUnits(int v) { this.totalUnits = v; }
    public int getTotalSystems() { return totalSystems; }
    public void setTotalSystems(int v) { this.totalSystems = v; }
    public int getTotalTypes() { return totalTypes; }
    public void setTotalTypes(int v) { this.totalTypes = v; }
    public List<EquipmentRow> getRows() { return rows; }
    public void setRows(List<EquipmentRow> v) { this.rows = v; }
}
