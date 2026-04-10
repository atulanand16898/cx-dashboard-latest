package com.cxalloy.integration.service;

import com.cxalloy.integration.config.AdminCredentialsProperties;
import com.cxalloy.integration.dto.LoginRequest;
import com.cxalloy.integration.dto.RefreshTokenRequest;
import com.cxalloy.integration.dto.TokenResponse;
import com.cxalloy.integration.model.DataProvider;
import com.cxalloy.integration.security.JwtTokenProvider;
import com.cxalloy.integration.security.TokenBlacklistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsService userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;
    private final AdminCredentialsProperties adminCredentialsProperties;

    public AuthService(AuthenticationManager authenticationManager,
                       JwtTokenProvider jwtTokenProvider,
                       UserDetailsService userDetailsService,
                       TokenBlacklistService tokenBlacklistService,
                       AdminCredentialsProperties adminCredentialsProperties) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userDetailsService = userDetailsService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.adminCredentialsProperties = adminCredentialsProperties;
    }

    /**
     * Authenticate user credentials and return JWT tokens
     */
    public TokenResponse login(LoginRequest request) {
        DataProvider provider = resolveProvider(request.getProvider());

        String username = normalizeUsername(request.getUsername());
        String password = normalizePassword(request.getUsername(), request.getPassword());
        validateProviderSpecificAdmin(username, provider);
        logger.info("Login attempt for user: {} via {}", username, provider.getKey());

        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(username, password)
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();

        String accessToken = jwtTokenProvider.generateAccessToken(userDetails, provider);
        String refreshToken = jwtTokenProvider.generateRefreshToken(userDetails, provider);

        logger.info("Login successful for user: {} via {}", userDetails.getUsername(), provider.getKey());
        return buildTokenResponse(accessToken, refreshToken, userDetails.getUsername(), provider);
    }

    /**
     * Issue a new access token using a valid refresh token
     */
    public TokenResponse refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();

        if (tokenBlacklistService.isBlacklisted(refreshToken)) {
            throw new BadCredentialsException("Refresh token has been revoked. Please login again.");
        }

        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new BadCredentialsException("Invalid or expired refresh token.");
        }

        if (!jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw new BadCredentialsException("Provided token is not a refresh token.");
        }

        String username = jwtTokenProvider.extractUsername(refreshToken);
        DataProvider provider = jwtTokenProvider.extractProvider(refreshToken);
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);

        // Blacklist the old refresh token (rotation)
        tokenBlacklistService.blacklist(refreshToken, jwtTokenProvider.extractExpiration(refreshToken));

        // Issue fresh pair
        String newAccessToken = jwtTokenProvider.generateAccessToken(userDetails, provider);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(userDetails, provider);

        logger.info("Token refreshed for user: {} via {}", username, provider.getKey());
        return buildTokenResponse(newAccessToken, newRefreshToken, username, provider);
    }

    /**
     * Logout — blacklist both access and refresh tokens
     */
    public void logout(String accessToken, String refreshTokenOptional) {
        if (accessToken != null && jwtTokenProvider.validateToken(accessToken)) {
            tokenBlacklistService.blacklist(accessToken, jwtTokenProvider.extractExpiration(accessToken));
            logger.info("Access token blacklisted for logout");
        }

        if (refreshTokenOptional != null && !refreshTokenOptional.isBlank()
                && jwtTokenProvider.validateToken(refreshTokenOptional)) {
            tokenBlacklistService.blacklist(refreshTokenOptional, jwtTokenProvider.extractExpiration(refreshTokenOptional));
            logger.info("Refresh token blacklisted for logout");
        }
    }

    private TokenResponse buildTokenResponse(String accessToken, String refreshToken, String username, DataProvider provider) {
        TokenResponse response = new TokenResponse();
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);
        response.setUsername(username);
        response.setProvider(provider.getKey());
        response.setAccessTokenExpiresIn(jwtTokenProvider.getAccessTokenExpiration() / 1000); // seconds
        response.setRefreshTokenExpiresIn(jwtTokenProvider.getRefreshTokenExpiration() / 1000);
        return response;
    }

    private String normalizeUsername(String username) {
        String value = username == null ? "" : username.trim();
        if (value.contains("@")) {
            return value.toLowerCase(Locale.ROOT);
        }
        return value;
    }

    private String normalizePassword(String username, String password) {
        String value = password == null ? "" : password.trim();
        if (StringUtils.hasText(username) && username.contains("@")) {
            return value.toLowerCase(Locale.ROOT);
        }
        return value;
    }

    private DataProvider resolveProvider(String provider) {
        try {
            return DataProvider.fromValue(provider);
        } catch (IllegalArgumentException ex) {
            throw new BadCredentialsException(ex.getMessage());
        }
    }

    private void validateProviderSpecificAdmin(String username, DataProvider provider) {
        if (!adminCredentialsProperties.isAdminUsername(username)) {
            return;
        }
        if (adminCredentialsProperties.matchesProviderAdmin(provider, username)) {
            return;
        }
        throw new BadCredentialsException("Use the " + adminCredentialsProperties.usernameFor(provider)
                + " account for " + provider.getLabel());
    }
}
