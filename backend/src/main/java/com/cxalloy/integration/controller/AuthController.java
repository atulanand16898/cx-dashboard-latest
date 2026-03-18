package com.cxalloy.integration.controller;

import com.cxalloy.integration.dto.ApiResponse;
import com.cxalloy.integration.dto.LoginRequest;
import com.cxalloy.integration.dto.RefreshTokenRequest;
import com.cxalloy.integration.dto.TokenResponse;
import com.cxalloy.integration.service.AuthService;
import com.cxalloy.integration.service.ProjectAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final ProjectAccessService projectAccessService;

    public AuthController(AuthService authService, ProjectAccessService projectAccessService) {
        this.authService = authService;
        this.projectAccessService = projectAccessService;
    }

    /**
     * POST /api/auth/login
     * Body: { "username": "admin", "password": "admin123" }
     * Returns: access token + refresh token
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        logger.info("POST /api/auth/login - user: {}", request.getUsername());
        try {
            TokenResponse tokens = authService.login(request);
            return ResponseEntity.ok(ApiResponse.success(tokens, "Login successful"));
        } catch (BadCredentialsException e) {
            logger.warn("Login failed for user '{}': {}", request.getUsername(), e.getMessage());
            return ResponseEntity.status(401).body(ApiResponse.error("Invalid username or password"));
        } catch (Exception e) {
            logger.error("Login error: {}", e.getMessage());
            return ResponseEntity.status(500).body(ApiResponse.error("Login failed: " + e.getMessage()));
        }
    }

    /**
     * POST /api/auth/refresh
     * Body: { "refreshToken": "<your-refresh-token>" }
     * Returns: new access token + new refresh token (old refresh token is rotated/invalidated)
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        logger.info("POST /api/auth/refresh");
        try {
            TokenResponse tokens = authService.refreshToken(request);
            return ResponseEntity.ok(ApiResponse.success(tokens, "Token refreshed successfully"));
        } catch (BadCredentialsException e) {
            logger.warn("Refresh failed: {}", e.getMessage());
            return ResponseEntity.status(401).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("Refresh error: {}", e.getMessage());
            return ResponseEntity.status(500).body(ApiResponse.error("Token refresh failed: " + e.getMessage()));
        }
    }

    /**
     * POST /api/auth/logout
     * Header: Authorization: Bearer <access-token>
     * Body (optional): { "refreshToken": "<refresh-token>" }
     * Blacklists both tokens so they can no longer be used
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Map<String, String>>> logout(
            HttpServletRequest request,
            @RequestBody(required = false) RefreshTokenRequest body) {

        logger.info("POST /api/auth/logout");

        String accessToken = extractBearerToken(request);
        String refreshToken = (body != null) ? body.getRefreshToken() : null;

        authService.logout(accessToken, refreshToken);

        return ResponseEntity.ok(ApiResponse.success(
            Map.of("message", "Logged out successfully. Tokens have been invalidated."),
            "Logout successful"
        ));
    }

    /**
     * GET /api/auth/me
     * Header: Authorization: Bearer <access-token>
     * Returns info about the currently authenticated user
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCurrentUser(HttpServletRequest request) {
        String accessToken = extractBearerToken(request);
        if (accessToken == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("No token provided"));
        }
        try {
            com.cxalloy.integration.security.JwtTokenProvider jwtProvider =
                request.getServletContext().getAttribute("jwtProvider") != null
                    ? (com.cxalloy.integration.security.JwtTokenProvider) request.getServletContext().getAttribute("jwtProvider")
                    : null;

            // Get current user from security context
            org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();

            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(401).body(ApiResponse.error("Not authenticated"));
            }

            Map<String, Object> userInfo = Map.of(
                "username", auth.getName(),
                "roles", auth.getAuthorities().toString(),
                "authenticated", true,
                "isAdmin", projectAccessService.isAdmin(auth.getName()),
                "accessibleProjectIds", projectAccessService.getAccessibleProjectIdsForUser(auth.getName())
            );
            return ResponseEntity.ok(ApiResponse.success(userInfo));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(ApiResponse.error("Could not retrieve user info"));
        }
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
