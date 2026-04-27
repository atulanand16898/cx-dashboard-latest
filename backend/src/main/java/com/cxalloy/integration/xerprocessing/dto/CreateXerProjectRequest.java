package com.cxalloy.integration.xerprocessing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateXerProjectRequest {

    @NotBlank
    private String name;

    private String projectCode;

    private String notes;
}
