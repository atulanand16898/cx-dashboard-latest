package com.cxalloy.integration.xerprocessing.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class XerProgressMeasurementOptionsResponse {

    private Long baselineImportId;

    private String baselineImportStatus;

    private List<String> resourceTypes;

    private List<XerProgressMeasurementOption> resources;
}
