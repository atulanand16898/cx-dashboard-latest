package com.cxalloy.integration.service;

import com.cxalloy.integration.config.LeadCaptureProperties;
import com.cxalloy.integration.dto.LeadCaptureRequest;
import com.cxalloy.integration.model.LeadCapture;
import com.cxalloy.integration.repository.LeadCaptureRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class LeadCaptureService {

    private static final Logger logger = LoggerFactory.getLogger(LeadCaptureService.class);

    private final LeadCaptureRepository leadCaptureRepository;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final LeadCaptureProperties leadCaptureProperties;

    public LeadCaptureService(LeadCaptureRepository leadCaptureRepository,
                              ObjectProvider<JavaMailSender> mailSenderProvider,
                              LeadCaptureProperties leadCaptureProperties) {
        this.leadCaptureRepository = leadCaptureRepository;
        this.mailSenderProvider = mailSenderProvider;
        this.leadCaptureProperties = leadCaptureProperties;
    }

    public Map<String, Object> captureLead(LeadCaptureRequest request, HttpServletRequest servletRequest) {
        LeadCapture lead = new LeadCapture();
        lead.setFullName(trimToNull(request.getFullName()));
        lead.setEmail(normalizeEmail(request.getEmail()));
        lead.setPhone(trimToNull(request.getPhone()));
        lead.setCompany(trimToNull(request.getCompany()));
        lead.setSource(defaultIfBlank(request.getSource(), "landing-client-login"));
        lead.setIpAddress(extractClientIp(servletRequest));
        lead.setUserAgent(trimToNull(servletRequest.getHeader("User-Agent")));
        lead.setCreatedAt(LocalDateTime.now());
        lead.setNotificationSent(Boolean.FALSE);

        lead = leadCaptureRepository.save(lead);
        sendNotification(lead);
        lead = leadCaptureRepository.save(lead);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("leadId", lead.getId());
        response.put("notificationSent", Boolean.TRUE.equals(lead.getNotificationSent()));
        response.put("continuePath", leadCaptureProperties.getContinuePath());
        response.put("capturedAt", lead.getCreatedAt());
        return response;
    }

    private void sendNotification(LeadCapture lead) {
        List<String> recipients = leadCaptureProperties.getNotificationRecipients().stream()
                .map(this::trimToNull)
                .filter(StringUtils::hasText)
                .toList();

        if (recipients.isEmpty()) {
            lead.setNotificationError("notification_recipients_not_configured");
            logger.warn("Lead {} captured but no notification recipients are configured", lead.getId());
            return;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            lead.setNotificationError("mail_sender_not_configured");
            logger.warn("Lead {} captured but mail sender is not configured", lead.getId());
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (StringUtils.hasText(leadCaptureProperties.getFromAddress())) {
                message.setFrom(leadCaptureProperties.getFromAddress());
            }
            message.setTo(recipients.toArray(new String[0]));
            message.setSubject("New MODUM IQ lead: " + lead.getFullName());
            message.setText(buildNotificationBody(lead));
            mailSender.send(message);
            lead.setNotificationSent(Boolean.TRUE);
            lead.setNotificationError(null);
            logger.info("Lead notification email sent for lead {}", lead.getId());
        } catch (Exception ex) {
            lead.setNotificationSent(Boolean.FALSE);
            lead.setNotificationError(ex.getMessage());
            logger.error("Failed to send lead notification for lead {}: {}", lead.getId(), ex.getMessage());
        }
    }

    private String buildNotificationBody(LeadCapture lead) {
        String lineBreak = System.lineSeparator();
        return "A new MODUM IQ sales lead was captured." + lineBreak + lineBreak +
                "Name: " + valueOrDash(lead.getFullName()) + lineBreak +
                "Email: " + valueOrDash(lead.getEmail()) + lineBreak +
                "Phone: " + valueOrDash(lead.getPhone()) + lineBreak +
                "Company: " + valueOrDash(lead.getCompany()) + lineBreak +
                "Source: " + valueOrDash(lead.getSource()) + lineBreak +
                "IP Address: " + valueOrDash(lead.getIpAddress()) + lineBreak +
                "Captured At: " + lead.getCreatedAt() + lineBreak + lineBreak +
                "User Agent:" + lineBreak +
                valueOrDash(lead.getUserAgent());
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwardedFor = trimToNull(request.getHeader("X-Forwarded-For"));
        if (StringUtils.hasText(forwardedFor)) {
            int commaIndex = forwardedFor.indexOf(',');
            return commaIndex >= 0 ? forwardedFor.substring(0, commaIndex).trim() : forwardedFor;
        }
        return trimToNull(request.getRemoteAddr());
    }

    private String normalizeEmail(String email) {
        String value = trimToNull(email);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String defaultIfBlank(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private String valueOrDash(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }
}
