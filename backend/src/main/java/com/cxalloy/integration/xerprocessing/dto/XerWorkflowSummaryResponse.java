package com.cxalloy.integration.xerprocessing.dto;

import com.cxalloy.integration.xerprocessing.model.XerImportSession;
import com.cxalloy.integration.xerprocessing.model.XerProgressMeasurementConfig;
import com.cxalloy.integration.xerprocessing.model.XerProject;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class XerWorkflowSummaryResponse {

    private XerProject project;

    private long baselineImportCount;

    private XerImportSession latestBaselineImport;

    private XerProgressMeasurementConfig progressMeasurementConfig;

    private List<String> nextSteps;
}
