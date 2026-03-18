package com.cxalloy.integration.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Generates HMAC-SHA256 signatures for CxAlloy API requests.
 *
 * CxAlloy signature rules (from official docs):
 *   GET  requests : HMAC_SHA256(secret, timestamp)
 *   POST requests : HMAC_SHA256(secret, jsonBody + timestamp)   ← body BEFORE timestamp
 */
@Component
public class HmacSignatureUtil {

    private static final Logger logger = LoggerFactory.getLogger(HmacSignatureUtil.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    public String generateSignature(String secret, String timestamp, String requestBody) {
        try {
            // CxAlloy spec: body + timestamp (body comes FIRST)
            String message = (requestBody != null && !requestBody.isEmpty())
                    ? requestBody + timestamp
                    : timestamp;

            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder();
            for (byte b : hmacBytes) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.error("Error generating HMAC signature: {}", e.getMessage());
            throw new RuntimeException("Failed to generate HMAC signature", e);
        }
    }

    public String getCurrentTimestamp() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }
}
