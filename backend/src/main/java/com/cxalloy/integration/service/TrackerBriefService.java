package com.cxalloy.integration.service;

import com.cxalloy.integration.model.TrackerBrief;
import com.cxalloy.integration.repository.ChecklistRepository;
import com.cxalloy.integration.repository.IssueRepository;
import com.cxalloy.integration.repository.TrackerBriefRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.List;

@Service
@Transactional
public class TrackerBriefService {

    private final TrackerBriefRepository briefRepository;
    private final ChecklistRepository    checklistRepository;
    private final IssueRepository        issueRepository;
    private final ProviderContextService providerContextService;

    public TrackerBriefService(TrackerBriefRepository briefRepository,
                               ChecklistRepository    checklistRepository,
                               IssueRepository        issueRepository,
                               ProviderContextService providerContextService) {
        this.briefRepository     = briefRepository;
        this.checklistRepository = checklistRepository;
        this.issueRepository     = issueRepository;
        this.providerContextService = providerContextService;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TrackerBrief> getBriefsForProject(String projectId, String period) {
        if (period != null && !period.isBlank() && !period.equalsIgnoreCase("Overall")) {
            return briefRepository.findByProjectIdAndPeriodAndProviderOrderByExportedAtDesc(
                    projectId, period, providerContextService.currentProviderKey());
        }
        return briefRepository.findByProjectIdAndProviderOrderByExportedAtDesc(
                projectId, providerContextService.currentProviderKey());
    }

    @Transactional(readOnly = true)
    public long countForProject(String projectId) {
        return briefRepository.countByProjectIdAndProvider(projectId, providerContextService.currentProviderKey());
    }

    // ── Manual create (called when user clicks Export CSV in frontend) ─────────

    public TrackerBrief create(String projectId, String title, String subtitle,
                               Integer items, Integer issues, String period) {
        TrackerBrief b = new TrackerBrief();
        b.setProjectId(projectId);
        b.setProvider(providerContextService.currentProviderKey());
        b.setTitle(title);
        b.setSubtitle(subtitle);
        b.setItems(items != null ? items : 0);
        b.setIssues(issues != null ? issues : 0);
        b.setPeriod(period != null ? period : "Overall");
        b.setExportedAt(LocalDateTime.now());
        return briefRepository.save(b);
    }

    // ── Auto-generate snapshot from DB data ────────────────────────────────────
    //
    // Counts checklists and open issues directly from the local DB for the
    // given projectId, then saves a TrackerBrief row.  This is what powers
    // the "Generate Snapshot" button — no CSV download required.

    public TrackerBrief generateSnapshot(String projectId, String period) {
        // Count checklists
        long totalChecklists = checklistRepository.findByProjectId(projectId).stream()
                .filter(checklist -> providerContextService.matchesCurrentProvider(checklist.getProvider()))
                .count();

        // Count finished checklists — must cover all canonical closed tokens
        // including checklist_approved and complete (emitted by updated ChecklistService)
        long finishedChecklists = 0;
        for (String s : new String[]{
                "checklist_approved", "complete", "finished", "completed",
                "done", "closed", "signed_off", "approved"}) {
            finishedChecklists += checklistRepository.findByProjectIdAndStatus(projectId, s).stream()
                    .filter(checklist -> providerContextService.matchesCurrentProvider(checklist.getProvider()))
                    .count();
        }

        // Count issues
        long totalIssues = issueRepository.findByProjectId(projectId).stream()
                .filter(issue -> providerContextService.matchesCurrentProvider(issue.getProvider()))
                .count();

        // Count open issues
        long openIssues = 0;
        for (String s : new String[]{"open", "in_progress", "active", "correction_in_progress",
                "gc_to_verify", "cxa_to_verify", "ready_for_retest", "additional_information_needed"}) {
            openIssues += issueRepository.findByProjectIdAndStatus(projectId, s).stream()
                    .filter(issue -> providerContextService.matchesCurrentProvider(issue.getProvider()))
                    .count();
        }

        // Build label
        LocalDateTime now = LocalDateTime.now();
        String periodLabel = periodLabel(period);
        String weekLabel   = isoWeekLabel(now);

        String title    = String.format("Snapshot %s %s", weekLabel, now.format(DateTimeFormatter.ofPattern("MMM d")));
        String subtitle = String.format("%s | %s", periodLabel, weekLabel);

        TrackerBrief b = new TrackerBrief();
        b.setProjectId(projectId);
        b.setProvider(providerContextService.currentProviderKey());
        b.setTitle(title);
        b.setSubtitle(subtitle);
        b.setItems((int) totalChecklists);
        b.setIssues((int) totalIssues);
        b.setPeriod(period != null ? period : "Overall");
        b.setExportedAt(now);
        return briefRepository.save(b);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String periodLabel(String period) {
        if ("D".equals(period)) return "Daily Report";
        if ("W".equals(period)) return "Weekly Snapshot";
        if ("M".equals(period)) return "Monthly Report";
        return "Period Report";
    }

    private static String isoWeekLabel(LocalDateTime dt) {
        WeekFields wf = WeekFields.ISO;
        int year = dt.get(wf.weekBasedYear());
        int week = dt.get(wf.weekOfWeekBasedYear());
        return String.format("%d-W%02d", year, week);
    }
}
