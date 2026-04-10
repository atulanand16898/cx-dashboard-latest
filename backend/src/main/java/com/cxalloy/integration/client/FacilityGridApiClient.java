package com.cxalloy.integration.client;

import com.cxalloy.integration.config.FacilityGridApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Component
public class FacilityGridApiClient {

    private static final Logger logger = LoggerFactory.getLogger(FacilityGridApiClient.class);

    private final RestTemplate restTemplate;
    private final FacilityGridApiProperties apiProperties;

    public FacilityGridApiClient(@Qualifier("facilityGridRestTemplate") RestTemplate restTemplate,
                                 FacilityGridApiProperties apiProperties) {
        this.restTemplate = restTemplate;
        this.apiProperties = apiProperties;
    }

    public String post(String absoluteOrRelativePath, String body, String bearerToken) {
        String url = resolveUrl(absoluteOrRelativePath);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.setBearerAuth(bearerToken);
        }

        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        logger.debug("Facility Grid POST {}", url);
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            logger.error("Facility Grid POST {} => {} : {}", url, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Facility Grid POST failed [" + e.getStatusCode() + "]: " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new RuntimeException(resourceAccessMessage("POST", url, e), e);
        }
    }

    public String postForm(String absoluteOrRelativePath, String formBody, String bearerToken) {
        String url = resolveUrl(absoluteOrRelativePath);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.setBearerAuth(bearerToken);
        }

        HttpEntity<String> entity = new HttpEntity<>(formBody, headers);
        logger.debug("Facility Grid POST FORM {}", url);
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            logger.error("Facility Grid POST FORM {} => {} : {}", url, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Facility Grid POST failed [" + e.getStatusCode() + "]: " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new RuntimeException(resourceAccessMessage("POST", url, e), e);
        }
    }

    public String get(String absoluteOrRelativePath, String bearerToken) {
        String url = resolveUrl(absoluteOrRelativePath);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.setBearerAuth(bearerToken);
        }

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        logger.debug("Facility Grid GET {}", url);
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            logger.error("Facility Grid GET {} => {} : {}", url, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Facility Grid GET failed [" + e.getStatusCode() + "]: " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new RuntimeException(resourceAccessMessage("GET", url, e), e);
        }
    }

    private String resourceAccessMessage(String method, String url, ResourceAccessException e) {
        String detail = e.getMessage() == null ? "request failed" : e.getMessage();
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "Facility Grid " + method + " timed out calling " + url
                    + " after " + apiProperties.getRequestTimeoutSeconds() + " seconds";
        }
        return "Facility Grid " + method + " could not reach " + url + ": " + detail;
    }

    private String resolveUrl(String absoluteOrRelativePath) {
        if (absoluteOrRelativePath == null || absoluteOrRelativePath.isBlank()) {
            return apiProperties.getBaseUrl();
        }
        if (absoluteOrRelativePath.startsWith("http://") || absoluteOrRelativePath.startsWith("https://")) {
            return absoluteOrRelativePath;
        }
        return apiProperties.getBaseUrl() + absoluteOrRelativePath;
    }
}
