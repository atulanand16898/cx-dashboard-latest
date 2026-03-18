package com.cxalloy.integration.client;

import com.cxalloy.integration.config.CxAlloyApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Component
public class CxAlloyApiClient {

    private static final Logger logger = LoggerFactory.getLogger(CxAlloyApiClient.class);

    private final RestTemplate restTemplate;
    private final CxAlloyApiProperties apiProperties;
    private final HmacSignatureUtil hmacUtil;

    public CxAlloyApiClient(RestTemplate restTemplate,
                            CxAlloyApiProperties apiProperties,
                            HmacSignatureUtil hmacUtil) {
        this.restTemplate = restTemplate;
        this.apiProperties = apiProperties;
        this.hmacUtil = hmacUtil;
    }

    private HttpHeaders buildHeaders(String requestBody) {
        String timestamp = hmacUtil.getCurrentTimestamp();
        String signature = hmacUtil.generateSignature(apiProperties.getSecret(), timestamp, requestBody);

        HttpHeaders headers = new HttpHeaders();
        // CxAlloy uses lowercase header names
        headers.set("cxalloy-identifier", apiProperties.getIdentifier());
        headers.set("cxalloy-timestamp", timestamp);
        headers.set("cxalloy-signature", signature);
        headers.set("user-agent", "cxalloy-spring-integration/v1.0");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    public String get(String path) {
        String url = apiProperties.getBaseUrl() + path;
        HttpHeaders headers = buildHeaders(null);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        logger.debug("CxAlloy GET {}", url);
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            logger.error("GET {} => {} : {}", url, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("CxAlloy GET failed [" + e.getStatusCode() + "]: " + e.getResponseBodyAsString(), e);
        }
    }

    public String post(String path, String body) {
        String url = apiProperties.getBaseUrl() + path;
        HttpHeaders headers = buildHeaders(body);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        logger.debug("CxAlloy POST {}", url);
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            logger.error("POST {} => {} : {}", url, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("CxAlloy POST failed [" + e.getStatusCode() + "]: " + e.getResponseBodyAsString(), e);
        }
    }

    public String put(String path, String body) {
        String url = apiProperties.getBaseUrl() + path;
        HttpHeaders headers = buildHeaders(body);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        logger.debug("CxAlloy PUT {}", url);
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            logger.error("PUT {} => {} : {}", url, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("CxAlloy PUT failed [" + e.getStatusCode() + "]: " + e.getResponseBodyAsString(), e);
        }
    }

    public String delete(String path) {
        String url = apiProperties.getBaseUrl() + path;
        HttpHeaders headers = buildHeaders(null);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        logger.debug("CxAlloy DELETE {}", url);
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            logger.error("DELETE {} => {} : {}", url, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("CxAlloy DELETE failed [" + e.getStatusCode() + "]: " + e.getResponseBodyAsString(), e);
        }
    }
}
