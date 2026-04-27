package com.cxalloy.integration.xerprocessing.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class XerBaselineAnalysisResponse {

    private String analysisMode;

    private LocalDate selectedDataDate;

    private LocalDate baselineDataDate;

    private String scopeSummary;

    private String calculationNote;

    private List<String> selectedResourceTypes;

    private List<String> selectedResourceNames;

    private boolean allResourceNames;

    private int activityCount;

    private int taskResourceCount;

    private Double planPercent;

    private Double totalPlannedUnits;

    private Double plannedUnitsAtDataDate;

    private Double totalPlannedCost;

    private Double plannedCostAtDataDate;

    private List<CurvePoint> progressCurve;

    private List<CurvePoint> costCurve;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurvePoint {

        private String label;

        private LocalDate date;

        private Double plannedProgressPercent;

        private Double cumulativePlannedUnits;

        private Double cumulativePlannedCost;
    }
}
