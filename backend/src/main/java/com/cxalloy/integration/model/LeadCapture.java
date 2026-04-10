package com.cxalloy.integration.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "lead_captures", indexes = {
        @Index(name = "idx_lead_captures_email", columnList = "email"),
        @Index(name = "idx_lead_captures_created_at", columnList = "created_at"),
        @Index(name = "idx_lead_captures_source", columnList = "source")
})
public class LeadCapture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "phone", nullable = false, length = 32)
    private String phone;

    @Column(name = "company", length = 160)
    private String company;

    @Column(name = "source", length = 120)
    private String source;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "notification_sent", nullable = false)
    private Boolean notificationSent = Boolean.FALSE;

    @Column(name = "notification_error", columnDefinition = "TEXT")
    private String notificationError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (notificationSent == null) {
            notificationSent = Boolean.FALSE;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Boolean getNotificationSent() {
        return notificationSent;
    }

    public void setNotificationSent(Boolean notificationSent) {
        this.notificationSent = notificationSent;
    }

    public String getNotificationError() {
        return notificationError;
    }

    public void setNotificationError(String notificationError) {
        this.notificationError = notificationError;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
