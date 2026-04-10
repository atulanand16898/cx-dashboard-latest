package com.cxalloy.integration.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class LeadCaptureRequest {

    @NotBlank(message = "Full name is required")
    @Size(max = 160, message = "Full name must be 160 characters or fewer")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Enter a valid email address")
    @Size(max = 255, message = "Email must be 255 characters or fewer")
    private String email;

    @NotBlank(message = "Phone number is required")
    @Size(min = 7, max = 32, message = "Phone number must be between 7 and 32 characters")
    private String phone;

    @Size(max = 160, message = "Company must be 160 characters or fewer")
    private String company;

    @Size(max = 120, message = "Source must be 120 characters or fewer")
    private String source;

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
