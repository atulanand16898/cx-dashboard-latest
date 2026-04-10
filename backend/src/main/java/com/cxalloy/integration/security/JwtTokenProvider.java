package com.cxalloy.integration.security;

import com.cxalloy.integration.model.DataProvider;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.access-token.expiration}")
    private long accessTokenExpiration;

    @Value("${jwt.refresh-token.expiration}")
    private long refreshTokenExpiration;

    private SecretKey getSigningKey() {
        try {
            // SHA-256 hash of secret to get exactly 32 bytes (256-bit key)
            byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(jwtSecret.getBytes());
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create signing key", e);
        }
    }

    public String generateAccessToken(UserDetails userDetails, DataProvider provider) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "ACCESS");
        claims.put("roles", userDetails.getAuthorities().toString());
        claims.put("provider", provider.getKey());
        return buildToken(claims, userDetails.getUsername(), accessTokenExpiration);
    }

    public String generateRefreshToken(UserDetails userDetails, DataProvider provider) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "REFRESH");
        claims.put("provider", provider.getKey());
        return buildToken(claims, userDetails.getUsername(), refreshTokenExpiration);
    }

    private String buildToken(Map<String, Object> extraClaims, String subject, long expiration) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            logger.warn("JWT token expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            logger.warn("JWT token unsupported: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            logger.warn("JWT token malformed: {}", e.getMessage());
        } catch (SecurityException e) {
            logger.warn("JWT signature invalid: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.warn("JWT claims empty: {}", e.getMessage());
        }
        return false;
    }

    public boolean isRefreshToken(String token) {
        try {
            String type = extractClaim(token, claims -> claims.get("type", String.class));
            return "REFRESH".equals(type);
        } catch (Exception e) {
            return false;
        }
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public DataProvider extractProvider(String token) {
        String provider = extractClaim(token, claims -> claims.get("provider", String.class));
        return DataProvider.fromValue(provider);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        return claimsResolver.apply(extractAllClaims(token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getAccessTokenExpiration() { return accessTokenExpiration; }
    public long getRefreshTokenExpiration() { return refreshTokenExpiration; }
}
