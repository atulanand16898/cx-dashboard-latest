package com.cxalloy.integration.dto;

import com.cxalloy.integration.model.DataProvider;
import java.time.LocalDateTime;

public class TokenResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private long accessTokenExpiresIn;
    private long refreshTokenExpiresIn;
    private String username;
    private String provider = DataProvider.CXALLOY.getKey();
    private LocalDateTime issuedAt;

    public TokenResponse() {
        this.issuedAt = LocalDateTime.now();
    }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }

    public long getAccessTokenExpiresIn() { return accessTokenExpiresIn; }
    public void setAccessTokenExpiresIn(long accessTokenExpiresIn) { this.accessTokenExpiresIn = accessTokenExpiresIn; }

    public long getRefreshTokenExpiresIn() { return refreshTokenExpiresIn; }
    public void setRefreshTokenExpiresIn(long refreshTokenExpiresIn) { this.refreshTokenExpiresIn = refreshTokenExpiresIn; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime issuedAt) { this.issuedAt = issuedAt; }
}
