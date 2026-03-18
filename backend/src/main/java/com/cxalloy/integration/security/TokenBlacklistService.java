package com.cxalloy.integration.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token blacklist for logout.
 * Tokens are stored until their natural expiry, then cleaned up.
 */
@Service
@EnableScheduling
public class TokenBlacklistService {

    private static final Logger logger = LoggerFactory.getLogger(TokenBlacklistService.class);

    // token -> expiry time
    private final Map<String, Date> blacklistedTokens = new ConcurrentHashMap<>();

    public void blacklist(String token, Date expiry) {
        blacklistedTokens.put(token, expiry);
        logger.info("Token blacklisted, expires at: {}", expiry);
    }

    public boolean isBlacklisted(String token) {
        return blacklistedTokens.containsKey(token);
    }

    /**
     * Cleanup expired tokens from blacklist every 10 minutes
     */
    @Scheduled(fixedDelay = 600_000)
    public void cleanupExpiredTokens() {
        Date now = new Date();
        int before = blacklistedTokens.size();
        blacklistedTokens.entrySet().removeIf(entry -> entry.getValue().before(now));
        int removed = before - blacklistedTokens.size();
        if (removed > 0) {
            logger.debug("Cleaned up {} expired blacklisted tokens", removed);
        }
    }

    public int getBlacklistSize() {
        return blacklistedTokens.size();
    }
}
