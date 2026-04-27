package com.cxalloy.integration.xerprocessing.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SaveXerProgressMeasurementRequest {

    private Long baselineImportId;

    private List<String> resourceNames;

    private List<String> resourceTypes;

    private Boolean allResourceNames;

    private String notes;
}
