package com.cxalloy.integration.xerprocessing.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class RegisterXerBaselineRequest {

    @NotBlank
    private String fileName;

    private String storedFileName;

    private String projectCodeFromFile;

    private String revisionLabel;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dataDate;

    private String storagePath;

    private String processorName;

    private String processorVersion;

    private String summaryJson;
}
